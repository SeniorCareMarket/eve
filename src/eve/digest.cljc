(ns eve.digest
  "Merkle Phase 3, eve half — the digest cache, layout B-prime (decided
   2026-10-25: the digest lives in a WRITE-ONCE side block; the
   pointer lives in the block's DESCRIPTOR (its POINTER column). No header
   ever mutates — the blob store is write-once; the descriptor is where
   mutable bookkeeping and CAS already live).

   A collection root (map, set, vector, list header block) remembers two
   facts about its content, each computed once and then served:

     the digest — SHA-256 over the root's canonical codec bytes
     (eve.codec/value->eve-bytes, the flat form), the Phase 1 content
     identity in its scheme-independent form (plan 3a);
     the hash word — exactly what the resident `hash` computes for the
     root today (plan 3b's mechanics). The word is scheme-transparent:
     this namespace stores it and serves it, and never interprets it.

   THE SIDE BLOCK (48 bytes, slab-allocated on first store):
     +0  magic 0x31475344 (\"DGS1\")
     +4  back-pointer: the root header's slab offset
     +8  flags: bit 0 digest latched, bit 1 hash latched
     +12 the hash word
     +16..+47 the digest, 8 i32 words, little-endian packed
   Every field is a write-once latch: the payload words are written once,
   then its presence bit is CASed on; no latched field is ever rewritten
   (a racing latch writes the identical value — the digest and the hash
   of one root's content are single-valued). The pointer itself is
   stamped by ONE CAS on the root header's descriptor POINTER column
   (coalesc/DESC_DIGEST_PTR) from the word as read — 0 and -1 both
   mean unset (bitmap descriptors are born 0, coalescer descriptors are
   born -1; the free resets in eve.deftype-proto.alloc/ctx-free! and
   the CLJS process slab write 0). A pointer is SERVED only after validation — the named
   block is allocated, carries the magic, and its back-pointer names
   this root — so a stale word (its block freed, the offset since
   reused) is detected, never served. Content held by reference at any
   depth (a fn, a regex, a host object) has no canonical bytes: the
   digest computation refuses it (eve.deftype-proto.serialize/
   *shared-write*) and the root simply has no digest — its consumers
   walk, exactly as before this cache existed.

   THE COUNT. The side block is the root's own, held by the root through
   eve.tally: it joins the root's references (tally's refs, for the four
   root kinds — so a publish walk counts it and the flush's release of
   the root drops it), and a stamp onto an already-counted root holds it
   at stamp time (tally/hold-cache!). Transients wrap persistent roots,
   so no stamp can land mid-build: stamps happen only in equiv/hasheq
   on finished roots, and a derived root is a new header block whose row
   is born unset — a cache is never inherited and never moves.

   The cache is an optimisation only: every entry point here answers
   the plain computed value when there is nowhere to keep it (no
   descriptor row, a full slab), and a failure to stamp never fails the
   caller's hash or equality."
  (:require [eve.thread-host :as h]   ;; attempt: the catch-all shared code cannot spell (the host adapter's)
           [eve.slab-io :as slab]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.crypto :as crypto]
            [eve.int32 :as i32]
            [eve.tally :as tally]))

(def ^:const CACHE_SIZE 48)
(def ^:const MAGIC 0x31475344) ;; "DGS1"
(def ^:const FLAG_DIGEST 1)
(def ^:const FLAG_HASH 2)
;; side-block field offsets
(def ^:const F_MAGIC 0)
(def ^:const F_BACKPTR 4)
(def ^:const F_FLAGS 8)
(def ^:const F_HASHWORD 12)
(def ^:const F_DIGEST 16)

;; ---------------------------------------------------------------------------
;; The pointer word (the POINTER column of the root header's descriptor —
;; coalesc/DESC_DIGEST_PTR, the desc-of handle's third pair)
;; ---------------------------------------------------------------------------

(defn- ptr-word [row]
  (slab/region-load-i32 (nth (nth row 2) 0) (nth (nth row 2) 1)))

(defn- cas-ptr! [row expected desired]
  (slab/region-cas-i32! (nth (nth row 2) 0) (nth (nth row 2) 1) expected desired))

(defn- valid-block?
  "p names root hdr's cache block: allocated, the magic, the back-pointer.
   Anything else — 0, -1, a freed block, a reused offset — is not served."
  [sio hdr p]
  (and (some? p)
       (not (zero? p))
       (not (== p alloc/NIL_OFFSET))
       (alloc/allocated? sio p)
       (== MAGIC (slab/read-i32 sio p F_MAGIC))
       (== hdr (slab/read-i32 sio p F_BACKPTR))))

(defn cache-block
  "The validated cache-block offset of the root header at hdr, or nil."
  [sio hdr]
  (when-let [row (alloc/desc-of sio hdr)]
    (let [p (ptr-word row)]
      (when (valid-block? sio hdr p) p))))

(defn- ensure-block!
  "The root header hdr's cache block, allocating and stamping one on first
   use: the block's magic + back-pointer are written BEFORE the one CAS
   that publishes the pointer, and a racing stamp's loser frees its own
   (never-published) block and takes the winner's. nil when the header has
   no descriptor row, or the stamp cannot be made (the cache then simply
   does not exist for this root — callers compute as before)."
  [sio hdr]
  (or (cache-block sio hdr)
      (let [[ok? blk] (h/attempt (fn [] (when-let [row (alloc/desc-of sio hdr)]
          (loop []
            (let [cur (ptr-word row)]
              (cond
                (valid-block? sio hdr cur) cur
                :else
                (let [blk (slab/alloc! sio CACHE_SIZE)]
                  (slab/write-i32! sio blk F_MAGIC MAGIC)
                  (slab/write-i32! sio blk F_BACKPTR hdr)
                  (slab/write-i32! sio blk F_FLAGS 0)
                  (if (== cur (cas-ptr! row cur blk))
                    (do (tally/hold-cache! sio hdr blk) blk)
                    (do (slab/free! sio blk)
                        (recur))))))))))]
        (when ok? blk))))

;; ---------------------------------------------------------------------------
;; The latches
;; ---------------------------------------------------------------------------

(defn- latched? [sio blk flag]
  (not (zero? (bit-and (slab/read-i32 sio blk F_FLAGS) flag))))

(defn- latch!
  "write! stores the field's words; then the field's presence bit is CASed
   on. The bit is the publication: readers serve a field only under its bit."
  [sio blk flag write!]
  (write!)
  (loop []
    (let [f (slab/read-i32 sio blk F_FLAGS)]
      (cond (not (zero? (bit-and f flag))) true
            (== f (slab/cas-i32! sio blk F_FLAGS f (bit-or f flag))) true
            :else (recur)))))

(defn- pack-words
  "32 digest bytes (unsigned, in order) as 8 i32 words, little-endian.
   Each word is wrapped to a signed i32 (eve.int32/i32): the computed
   digest and the latched read-back must be the SAME values — a word
   left as an unsigned long never equals its i32 read-back, and a
   digest served from the block would then differ from the digest
   computed in the process that stamped it (the persist probe caught
   exactly that, 2026-10-25)."
  [bs]
  (mapv (fn [i]
          (let [o (* 4 i)]
            (i32/i32 (bit-or (nth bs o)
                             (bit-shift-left (nth bs (+ o 1)) 8)
                             (bit-shift-left (nth bs (+ o 2)) 16)
                             (bit-shift-left (nth bs (+ o 3)) 24)))))
        (range 8)))

(defn- compute-digest
  "The root value v's digest words, or nil when its content has no
   canonical bytes (anything held by reference, at any depth: the codec
   refuses a reference under a shared write, and a reference is process-
   local — a digest over it would name nothing outside this process)."
  [v]
  (let [[ok? words]
        (h/attempt
         (fn []
          (let [bs (binding [ser/*shared-write* true]
               (codec/value->eve-bytes v))
          ;; eve.crypto's contract is byte vectors in and out; iterate the
          ;; byte array itself (a per-index aget through eve.host cost
          ;; ~50us a call — 2.5 s on a 47 KB encoding, measured)
          raw (crypto/sha256 (mapv (fn [b] (bit-and b 0xFF)) bs))]
      (pack-words (mapv (fn [b] (bit-and b 0xFF)) raw)))))]
    (when ok? words)))

;; ---------------------------------------------------------------------------
;; The consumers' entry points
;; ---------------------------------------------------------------------------

(defn root-digest
  "The root value v's content digest (8 i32 words): the latched one when
   the root's cache block carries it, else computed now and latched.
   nil when the content has no digest (by-reference content) — callers
   walk. hdr is v's header-block slab offset; v is the root value itself."
  [sio hdr v]
  (let [blk (cache-block sio hdr)]
    (if (and blk (latched? sio blk FLAG_DIGEST))
      (mapv (fn [i] (slab/read-i32 sio blk (+ F_DIGEST (* 4 i)))) (range 8))
      (when-let [d (compute-digest v)]
        (when-let [b (or blk (ensure-block! sio hdr))]
          (latch! sio b FLAG_DIGEST
                  (fn [] (doseq [i (range 8)]
                           (slab/write-i32! sio b (+ F_DIGEST (* 4 i)) (nth d i))))))
        d))))

(defn root-hash
  "The root's hash word: the latched word when the cache block carries
   one, else (compute) — a 0-arg fn answering the resident hash exactly
   as the caller computes it today — latched on the way out. The word is
   stored and served verbatim. Always answers: with no cache (no row, a
   full slab) it is exactly (compute)."
  [sio hdr compute]
  (let [blk (cache-block sio hdr)]
    (if (and blk (latched? sio blk FLAG_HASH))
      (slab/read-i32 sio blk F_HASHWORD)
      (let [h (compute)]
        (when-let [b (or blk (ensure-block! sio hdr))]
          (latch! sio b FLAG_HASH
                  (fn [] (slab/write-i32! sio b F_HASHWORD h))))
        h))))

(defn digest-verdict
  "Phase 4's §2b verdict for two roots OF THE SAME collection type (the
   caller's seam carries that partition gate): :equal when both roots'
   content digests are latched-or-computed here and byte-equal, :unequal
   when both are known and differ, :walk for anything else — a root with
   no digest (by-reference content), or any doubt."
  [sio-a hdr-a a sio-b hdr-b b]
  (let [da (root-digest sio-a hdr-a a)
        db (root-digest sio-b hdr-b b)]
    (cond (or (nil? da) (nil? db)) :walk
          (= da db) :equal
          :else :unequal)))
