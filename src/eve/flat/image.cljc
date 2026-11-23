(ns eve.flat.image
  "The image (FORMAT-V1 kind 1): a value's reachable blocks copied
   root-first into one byte array, pointers rewritten to buffer
   positions — the flat form for leaving and entering a domain.

   detach walks the same graph eve.retire walks, kind for kind: its
   reachability IS detach's (Z1.3's test derives the expected tables
   from eve.retire/children independently). Each block is sized by its
   content — what its writers requested — so the copy holds no dead
   byte (Z1.6).

   Byte form: the FORMAT-V1 32-byte chunk header (magic EVS1, version
   1, kind 1, the layout this writer places = 1, string interning
   absent = 0), the blocks packed 4-aligned root-first, then the
   block table {offset u32, size u32} per block, then the relocation
   table {field position u32 (absolute in the buffer), owner u32,
   target u32} per pointer (ordinals into the block table). The root
   block is ordinal 0; the header's root field is its buffer offset."
  (:require [eve.deftype-proto.data :as d]
            [eve.atom :as ea]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.hamt-util :as hu]
            [eve.flat.contract :as contract]
            [eve.flat.sio :as ssio])
  ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
  #?(:clj (:import #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays ArrayDeque])))

(def ^:const NIL -1)
(def ^:const HEADER 32)

;; ---------------------------------------------------------------------------
;; byte-array readers and writers (the image side; little-endian)
;; ---------------------------------------------------------------------------

(defn read-i32-at
  "The little-endian i32 at byte i of bs."
  ^long [#?(:clj ^bytes bs :cljs bs) ^long i]
  (let [i (int i)]
    (long (unchecked-int
           (bit-or (bit-and (long (aget bs i)) 0xFF)
                   (bit-shift-left (bit-and (long (aget bs (inc i))) 0xFF) 8)
                   (bit-shift-left (bit-and (long (aget bs (+ i 2))) 0xFF) 16)
                   (bit-shift-left (bit-and (long (aget bs (+ i 3))) 0xFF) 24))))))

(defn- u8-at ^long [#?(:clj ^bytes bs :cljs bs) ^long i]
  (bit-and (aget bs (int i)) 0xFF))

(defn- put-i32-at! [#?(:clj ^bytes bs :cljs bs) ^long i ^long v]
  (let [i (int i)]
    (aset bs i (unchecked-byte v))
    (aset bs (inc i) (unchecked-byte (bit-shift-right v 8)))
    (aset bs (+ i 2) (unchecked-byte (bit-shift-right v 16)))
    (aset bs (+ i 3) (unchecked-byte (bit-shift-right v 24)))
    nil))

(defn- align4 ^long [^long n] (bit-and (+ n 3) (bit-not 3)))
(defn- align8 ^long [^long n] (bit-and (+ n 7) (bit-not 7)))

;; ---------------------------------------------------------------------------
;; sizes: a block's content size, by kind (what its writers requested —
;; verified at the Z1 pin in doc/flat/Z1-STATUS.md)
;; ---------------------------------------------------------------------------

(defn- scalar-content-size
  "1 + payload for a scalar root block (tag 0x01): sized by re-encoding its
   value (scalar roots are rare and small; exactness over speed)."
  ^long [sio ^long off]
  (let [v (alloc/scalar-block->value sio off)]
    (inc (alength #?(:clj ^bytes (codec/encode-value sio v) :cljs (codec/encode-value sio v))))))

(def ^:const SHORT
  "content-size-in's answer when the block runs past the window it was
   given: the caller copies more and asks again. Distinct from -1
   (unknown kind — no window will help)."
  -2)

(defn- hamt-content-size
  "A HAMT node's content end, scanning the same layout retire scans.
   Only the first limit bytes from base are the block's — a scan that
   would read past base+limit answers SHORT, never a size derived from
   whatever bytes follow the window (the unbounded scan over a short
   copy window produced wild sizes at the Z1 pin, 2026-11-12)."
  ^long [bs ^long base map? ^long limit]
  (let [t (u8-at bs base)
        end (+ base limit)]
    (cond
      (== t 1)
      (let [data-bm (bit-and (read-i32-at bs (+ base 4)) 0xFFFFFFFF)
            node-bm (bit-and (read-i32-at bs (+ base 8)) 0xFFFFFFFF)
            nc (hu/popcount32 node-bm)
            nd (hu/popcount32 data-bm)
            ;; entries follow the child pointers — and, for a MAP only,
            ;; the per-entry hash array (eve.map's layout; eve.set's
            ;; writer has none: its values start at 12+4nc, measured at
            ;; the Z1 pin, 2026-11-12 — the set scan starting 4·nd late
            ;; walked garbage lengths and threw)
            entries (+ base 12 (* 4 nc) (if map? (* 4 nd) 0))]
        (if (> entries end)
          SHORT
          (if map?
            (loop [r data-bm pos entries]
              (if (zero? r)
                (- pos base)
                (if (> (+ pos 4) end)
                  SHORT
                  (let [klen (read-i32-at bs pos)
                        vpos (+ pos 4 klen)]
                    (if (> (+ vpos 4) end)
                      SHORT
                      (let [vlen (read-i32-at bs vpos)
                            nxt (+ vpos 4 vlen)]
                        (if (> nxt end)
                          SHORT
                          (recur (bit-and r (dec r)) nxt))))))))
            (loop [i 0 pos entries]
              (if (== i nd)
                (- pos base)
                (if (> (+ pos 4) end)
                  SHORT
                  (let [vlen (read-i32-at bs pos)
                        nxt (+ pos 4 vlen)]
                    (if (> nxt end)
                      SHORT
                      (recur (inc i) nxt)))))))))
      (and map? (== t 3))
      (let [cnt (u8-at bs (inc base))]
        (loop [i 0 pos (+ base 8)]
          (if (== i cnt)
            (- pos base)
            (if (> (+ pos 4) end)
              SHORT
              (let [klen (read-i32-at bs pos)
                    vpos (+ pos 4 klen)]
                (if (> (+ vpos 4) end)
                  SHORT
                  (let [vlen (read-i32-at bs vpos)
                        nxt (+ vpos 4 vlen)]
                    (if (> nxt end)
                      SHORT
                      (recur (inc i) nxt)))))))))
      (and (not map?) (== t 2))
      (let [cnt (u8-at bs (inc base))]
        (loop [i 0 pos (+ base 12)]
          (if (== i cnt)
            (- pos base)
            (if (> (+ pos 4) end)
              SHORT
              (let [vlen (read-i32-at bs pos)
                    nxt (+ pos 4 vlen)]
                (if (> nxt end) SHORT (recur (inc i) nxt)))))))
      :else -1)))

(def ^:private slab-class-sizes [32 64 128 256 512 1024])

(defn- fallback-size
  "The size when content cannot speak: the source sio's recorded size,
   then a slab block's class size (an over-estimate a leaf tolerates)."
  ^long [sio ^long off]
  (let [rec (ssio/block-size sio off)]
    (if (pos? rec)
      rec
      (let [ci (alloc/decode-class-idx off)]
        (if (< ci 6) (nth slab-class-sizes ci) -1)))))

(defn content-size-in
  "The content size of the block of kind at off in sio, read from a byte
   window: (window base) must be off's bytes in bs, and only the first
   limit bytes from base are the block's. -1 when unknown; SHORT when a
   scan-derived size runs past the window."
  [sio bs base off kind limit]
  (cond
    ;; a source that records sizes (a flat builder, an image's view)
    ;; knows the block's size exactly — that answer wins
    (pos? (ssio/block-size sio off)) (ssio/block-size sio off)
    (= kind :vblock) (+ 4 (read-i32-at bs base))
    (= kind :cell) (+ 8 (read-i32-at bs (+ base 4)))
    (= kind :map-node) (hamt-content-size bs base true limit)
    (= kind :set-node) (hamt-content-size bs base false limit)
    (vector? kind) (case (nth kind 0) :vec 128 :leaf 128 -1)
    (= kind :val)
    (let [t (u8-at bs base)]
      (case (int t)
        0xED 12
        0xEE 12
        0x12 24
        0x13 12
        0x01 (scalar-content-size sio off)
        0x1D (let [st (u8-at bs (inc base))
                   cnt (read-i32-at bs (+ base 4))
                   es (case (int st) (1 2 3) 1 (4 5) 2 (6 7 8) 4 (9 10 11) 8 -1)]
               (if (neg? es) -1 (+ 8 (* cnt es))))
        0x1E (fallback-size sio off)
        0x1F (let [n (read-i32-at bs (+ base 4))]
               (+ (align4 (+ 8 (* 2 n))) (* 4 n)))
        (or (ser/deftype-total-size sio off t) -1)))
    :else -1))

(defn content-size
  "The content size of the block of kind at off in sio, read from a byte
   window: (window base) must be off's bytes in bs. -1 when unknown."
  [sio bs base off kind]
  (content-size-in sio bs base off kind (alength bs)))

;; ---------------------------------------------------------------------------
;; pointers: a copied block's pointer fields, mirroring eve.retire/children
;; kind for kind but answering byte positions in the block
;; [[byte-pos child-kind target-source-offset] …]
;; ---------------------------------------------------------------------------

(defn- pointer-tag? [tag]
  (or (<= 0x10 tag 0x13) (<= 0x1C tag 0x1F) (<= 0x40 tag 0xEC)))

(defn- inline-ptr-at
  "The target of the 7-byte pointer form whose bytes start at byte p of
   the block at base in bs, or -1: [0xEE 0xDB tag][i32], tag a pointer tag."
  ^long [bs ^long base ^long p]
  (let [w (read-i32-at bs (+ base p))]
    (if (and (== 0xDBEE (bit-and w 0xFFFF))
             (pointer-tag? (bit-and (bit-shift-right w 16) 0xFF)))
      (read-i32-at bs (+ base p 3))
      -1)))

(defn- hamt-pointers
  [bs ^long base map? add]
  (let [t (u8-at bs base)]
    (cond
      (== t 1)
      (let [data-bm (bit-and (read-i32-at bs (+ base 4)) 0xFFFFFFFF)
            node-bm (bit-and (read-i32-at bs (+ base 8)) 0xFFFFFFFF)
            nc (hu/popcount32 node-bm)
            nd (hu/popcount32 data-bm)]
        (loop [r node-bm i 0]
          (when-not (zero? r)
            (let [c (read-i32-at bs (+ base 12 (* 4 i)))]
              (when (not= c NIL)
                (add (+ 12 (* 4 i)) (if map? :map-node :set-node) c))
              (recur (bit-and r (dec r)) (inc i)))))
        (when map?
          (loop [r data-bm pos (+ base 12 (* 4 nc) (* 4 nd))]
            (when-not (zero? r)
              (let [klen (read-i32-at bs pos)
                    vpos (+ pos 4 klen)
                    vlen (read-i32-at bs vpos)
                    ;; pos/vpos are absolute in bs; inline-ptr-at and add
                    ;; both take BLOCK-RELATIVE positions (the form starts
                    ;; at vpos+4, its target i32 at vpos+7). Passing the
                    ;; absolute position added base twice and every value
                    ;; pointer in a HAMT node was missed (measured at the
                    ;; Z1 pin, 2026-11-12: detach copied 2 of 24 blocks).
                    p (when (== vlen 7) (inline-ptr-at bs base (+ (- vpos base) 4)))]
                (when (and p (not= p -1)) (add (+ (- vpos base) 7) :val p))
                (recur (bit-and r (dec r)) (+ vpos 4 vlen)))))))
      (and map? (== t 3))
      (let [cnt (u8-at bs (inc base))]
        (loop [i 0 pos (+ base 8)]
          (when-not (== i cnt)
            (let [klen (read-i32-at bs pos)
                  vpos (+ pos 4 klen)
                  vlen (read-i32-at bs vpos)
                  p (when (== vlen 7) (inline-ptr-at bs base (+ (- vpos base) 4)))]
              (when (and p (not= p -1)) (add (+ (- vpos base) 7) :val p))
              (recur (inc i) (+ vpos 4 vlen))))))
      :else nil)))

(defn- trie-live ^long [^long toff ^long base ^long sh]
  (let [n (- toff base)]
    (if (<= n 0)
      0
      (let [w (bit-shift-left 1 sh)]
        (min 32 (quot (+ n (dec w)) w))))))

(defn- block-pointers
  "Calls (add byte-pos child-kind target) for every pointer in the block
   whose bytes sit at base in bs, of kind, mirroring eve.retire/children."
  [sio bs base off kind add]
  (cond
    (= kind :val)
    (let [t (u8-at bs base)]
      (case (int t)
        0xED (let [r (read-i32-at bs (+ base 8))] (when (not= r NIL) (add 8 :map-node r)))
        0xEE (let [r (read-i32-at bs (+ base 8))] (when (not= r NIL) (add 8 :set-node r)))
        0x12 (let [cnt (read-i32-at bs (+ base 4))
                   sh (read-i32-at bs (+ base 8))
                   root (read-i32-at bs (+ base 12))
                   tail (read-i32-at bs (+ base 16))
                   tail-len (read-i32-at bs (+ base 20))
                   toff (if (< cnt 32) 0 (bit-shift-left (bit-shift-right (dec cnt) 5) 5))]
               (when (and (pos? toff) (not= root NIL)) (add 12 [:vec sh 0 toff] root))
               (when (not= tail NIL) (add 16 [:leaf tail-len] tail)))
        0x13 (let [h (read-i32-at bs (+ base 8))] (when (not= h NIL) (add 8 :cell h)))
        0x1F (let [n (read-i32-at bs (+ base 4))
                   pb (align4 (+ 8 (* 2 n)))]
               (loop [i 0]
                 (when-not (== i n)
                   (let [k (u8-at bs (+ base 8 i))]
                     (cond (== k 2) (add (+ pb (* 4 i)) :val (read-i32-at bs (+ base pb (* 4 i))))
                           (== k 4) (add (+ pb (* 4 i)) :vblock (read-i32-at bs (+ base pb (* 4 i))))
                           :else nil))
                   (recur (inc i)))))
        ;; 0x01 scalar, 0x1D array, 0x1E obj: leaves, as in eve.retire
        (when (<= 0x40 t 0xEC)
          (when-let [layout (ser/deftype-layout sio off t)]
            (when (vector? layout)
              (doseq [[foff k] layout]
                (let [r (read-i32-at bs (+ base foff))]
                  (when (not= r NIL)
                    (add foff (if (= k :ref) :val :vblock) r)))))))))
    (= kind :map-node) (hamt-pointers bs base true add)
    (= kind :set-node) (hamt-pointers bs base false add)
    (= kind :vblock)
    (when (== 7 (read-i32-at bs base))
      (let [p (inline-ptr-at bs base 4)]
        (when (not= p -1) (add 7 :val p))))
    (= kind :cell)
    (let [nx (read-i32-at bs base)]
      (when (not= nx NIL) (add 0 :cell nx))
      (when (== 7 (read-i32-at bs (+ base 4)))
        (let [p (inline-ptr-at bs base 8)]
          (when (not= p -1) (add 11 :val p)))))
    (vector? kind)
    (case (nth kind 0)
      :vec (let [[_ sh base0 toff] kind
                 w (bit-shift-left 1 sh)
                 n (trie-live toff base0 sh)]
             (loop [i 0]
               (when-not (== i n)
                 (let [c (read-i32-at bs (+ base (* 4 i)))]
                   (when (not= c NIL)
                     (add (* 4 i) (if (zero? sh) :vblock [:vec (- sh 5) (+ base0 (* i w)) toff]) c)))
                 (recur (inc i)))))
      :leaf (let [n (nth kind 1)]
              (loop [i 0]
                (when-not (== i n)
                  (let [c (read-i32-at bs (+ base (* 4 i)))]
                    (when (not= c NIL) (add (* 4 i) :vblock c)))
                  (recur (inc i)))))
      nil)
    :else nil))

;; ---------------------------------------------------------------------------
;; detach
;; ---------------------------------------------------------------------------

(deftype Image [bs        ;; the image byte array
                length    ;; its written length (<= (alength bs))
                root      ;; the root block's buffer offset
                boff      ;; ^ints block offsets, ascending (buffer order)
                bsz       ;; ^ints block sizes, by ordinal
                rpos      ;; ^ints relocation field positions (absolute)
                rown      ;; ^ints relocation owner ordinals
                rtgt      ;; ^ints relocation target ordinals
                src])     ;; ^ints source offset per ordinal (detach only; nil opened)

(defn- copy-block-bytes!
  "Copies size bytes of the block at off in src into dst at dst-off: a
   direct array copy when the source exposes its array, the sio's bulk
   read otherwise."
  [src off ^bytes dst dst-off size]
  ;; let+if, not if-let: on the :cljs pass ha is bound to nil and the branch is nil there too
  ;; (which is also why splint's :cljs projection reads the if as if-nil-else),
  ;; so an if-let binding reads as unused to kondo on that pass; the if test names the use
  #_{:splint/disable [lint/let-if lint/if-nil-else]} (let [ha #?(:clj (alloc/node-heap-array src off) :cljs nil)]
                                                       (if ha
      ;; never read past the source array's end: a window copy may ask for
      ;; more than the array holds past this block (the bounded size scan
      ;; then reports the window short instead of copying garbage)
                                                         #?(:clj (System/arraycopy ha (int (alloc/node-base src off)) dst (int dst-off)
                                                                                   (int (min (long size)
                                                                                             (- (alength ha) (alloc/node-base src off)))))
                                                            :cljs nil)
                                                         (if (instance? eve.flat.sio.FlatSio src)
                                                           #?(:clj (System/arraycopy (ssio/-sio-bytes src) (int off) dst (int dst-off) (int size))
                                                              :cljs (.set dst (.subarray (ssio/-sio-bytes src) (int off) (int (+ off size))) (int dst-off)))
                                                           (let [b (alloc/-sio-read-bytes src off 0 size)]
                                                             #?(:clj (System/arraycopy ^bytes b 0 dst (int dst-off) (int size))
                                                                :cljs (.set dst b (int dst-off))))))))

;; The detach walk's bookkeeping: one object array of slots —
;;   0 keys ^ints (ordinal-table keys: low 32 bits of source offsets)
;;   1 vals ^ints (ordinal-table values: ordinals)
;;   2 cap  long  (ordinal-table capacity)
;;   3 n-ins long (ordinal-table population)
;;   4 img  ^bytes (the image byte array; grows by replacement)
;;   5 tail long  (one past the last block's aligned end)
;;   6 boff ^ints (block buffer offsets, by ordinal)
;;   7 bsz  ^ints (block sizes, by ordinal)
;;   8 srcs ^ints (source offsets, by ordinal)
;;   9 nb   long  (blocks placed)
;;   10 rpos ^ints (relocation field positions, absolute)
;;   11 rown ^ints (relocation owner ordinals)
;;   12 rtgt ^ints (relocation target ordinals)
;;   13 nr   long  (relocations recorded)
;; A deftype cannot hold this state for the walk's free fns: Clojure
;; emits :unsynchronized-mutable deftype fields package-private, and
;; neither the compiler's direct access nor Reflector can read a
;; non-public field from another class — the deftype form died on its
;; first detach (Reflector: "No matching field found: keys", place-test
;; at the re-engine pin; the class's declared fields verify present but
;; modifier-less). Slot access through aget/aset is direct on both
;; hosts and keeps the primitive arrays the rebuild was for.
(defn- detach-state []
  (object-array
   [(let [a #?(:clj (int-array 65536) :cljs (js/Int32Array. 65536))]
      #?(:clj (Arrays/fill a (int -1)) :cljs (.fill a -1))
      a)
    #?(:clj (int-array 65536) :cljs (js/Int32Array. 65536))
    65536 0
    #?(:clj (byte-array (max 65536 (+ HEADER 4096)))
       :cljs (js/Uint8Array. (max 65536 (+ HEADER 4096))))
    HEADER
    #?(:clj (int-array 1024) :cljs (js/Int32Array. 1024))
    #?(:clj (int-array 1024) :cljs (js/Int32Array. 1024))
    #?(:clj (int-array 1024) :cljs (js/Int32Array. 1024))
    0
    #?(:clj (int-array 4096) :cljs (js/Int32Array. 4096))
    #?(:clj (int-array 4096) :cljs (js/Int32Array. 4096))
    #?(:clj (int-array 4096) :cljs (js/Int32Array. 4096))
    0]))

(defn- d-hash-of ^long [^long off]
  ;; Fibonacci hashing in long arithmetic, wrapped once at the end (the
  ;; file's convention, cf. read-i32-at).
  (unchecked-int (unchecked-multiply (bit-and off 0xFFFFFFFF) 0x9E3779B1)))

(defn- d-lookup
  "The ordinal already placed for the source offset off, or -1. The key
   is the offset's low 32 bits, bit-preserved (see the shipped note in
   this file's history: keys arrive sign-extended or unsigned, and
   (int off) throws on the unsigned form past 2^31)."
  ^long [^objects st ^long off]
  (let [k (unchecked-int off)
        ^ints ks (aget st 0) ^ints vs (aget st 1)
        mask (dec (long (aget st 2)))]
    (loop [h (bit-and (d-hash-of off) mask)]
      (let [kk (aget ks h)]
        (cond (== kk k) (aget vs h)
              (== kk -1) -1
              :else (recur (bit-and (inc h) mask)))))))

(defn- d-insert! [^objects st ^long off ^long ord]
  (when (> (* 4 (inc (long (aget st 3)))) (* 3 (long (aget st 2))))
    ;; grow past 3/4 load: rehash into doubled arrays (a 100k-block
    ;; value overflows the initial table)
    (let [ncap (* 2 (long (aget st 2)))
          nk #?(:clj (int-array ncap) :cljs (js/Int32Array. ncap))
          nv #?(:clj (int-array ncap) :cljs (js/Int32Array. ncap))
          ^ints oks (aget st 0) ^ints ovs (aget st 1) mask (dec ncap)]
      #?(:clj (Arrays/fill nk (int -1)) :cljs (.fill nk -1))
      (dotimes [i (alength oks)]
        (let [k (aget oks i)]
          (when (not= k -1)
            (loop [h (bit-and (d-hash-of (long k)) mask)]
              (if (== (aget nk h) -1)
                (do (aset nk h k) (aset nv h (aget ovs i)))
                (recur (bit-and (inc h) mask)))))))
      (aset st 0 nk) (aset st 1 nv) (aset st 2 ncap)))
  (let [k (unchecked-int off)
        ^ints ks (aget st 0) ^ints vs (aget st 1)
        mask (dec (long (aget st 2)))]
    (loop [h (bit-and (d-hash-of off) mask)]
      (let [kk (aget ks h)]
        (if (or (== kk -1) (== kk k))
          (do (aset ks h k) (aset vs h (int ord)))
          (recur (bit-and (inc h) mask))))))
  (aset st 3 (inc (long (aget st 3)))))

(defn- d-ensure! [^objects st ^long end]
  (let [im (aget st 4)]
    (when (> end (alength im))
      (aset st 4 #?(:clj (Arrays/copyOf ^bytes im (int (max end (* 2 (alength im)))))
                    :cljs (let [nb (js/Uint8Array. (max end (* 2 (alength im))))]
                            (.set nb im) nb))))))

(defn- d-grow-ints
  "a, or a doubled copy when a cannot hold need entries (the shipped
   growth rule: new length 2 * max(need, current length))."
  [#?(:clj ^ints a :cljs a) ^long need]
  (if (< (alength a) need)
    #?(:clj (Arrays/copyOf a (int (* 2 (max need (alength a)))))
       :cljs (let [na (js/Int32Array. (* 2 (max need (alength a))))]
               (.set na a) na))
    a))

(defn- d-add-reloc! [^objects st ^long pos ^long owner ^long target]
  (let [i (long (aget st 13))]
    (aset st 10 (d-grow-ints (aget st 10) (inc i)))
    (aset st 11 (d-grow-ints (aget st 11) (inc i)))
    (aset st 12 (d-grow-ints (aget st 12) (inc i)))
    (aset ^ints (aget st 10) (int i) (int pos))
    (aset ^ints (aget st 11) (int i) (int owner))
    (aset ^ints (aget st 12) (int i) (int target))
    (aset st 13 (inc i))))

(defn detach-image
  "The Image record of the value whose header/value block is at root in
   sio: its reachable blocks copied root-first, pointers rewritten, both
   tables emitted. Under a pin (eve.atom/read-pinned) the image is the
   pinned value, whole, while other threads swap (Z1.4).

   The walk is one streaming pass (REENGINE): a raw cursor over the
   source slab — each block copied once, sized from its own content,
   scanned in the image buffer, its table rows appended as the walk
   proceeds. Traversal order, patch points and emitted bytes are the
   shipped detach's, byte for byte; only the bookkeeping machinery
   changed (see detach-state)."
  ([v] (when (nil? v)
         (throw (ex-info "eve.flat: cannot detach nil" {:type ::nil-root})))
       (detach-image (d/-eve-sio v) (d/-root-header-off v)))
  ([sio root]
   (when (== (long root) NIL)
     (throw (ex-info "eve.flat: cannot detach nil" {:type ::nil-root})))
   (let [stack #?(:clj (ArrayDeque.) :cljs (array))
         st (detach-state)
         push! (fn [kind ^long off ^long patch-pos ^long owner]
                 #?(:clj (.push stack [kind off patch-pos owner])
                    :cljs (.push stack [kind off patch-pos owner])))]
     (push! :val (long root) -1 -1)
     (loop []
       (when-let [e #?(:clj (when-not (.isEmpty stack) (.pop stack)) :cljs (.pop stack))]
         (let [[kind off patch-pos owner]   e
               off   (long off) placed   (d-lookup st off)] (if (not= placed -1)
                 ;; already placed (an earlier edge reached it first): this
                 ;; edge's field is patched now and its relocation recorded —
                 ;; a shared block is copied once but patched per edge
                                                              (do (when (<= 0 (long patch-pos))
                                                                    (put-i32-at! (aget st 4) (long patch-pos) (aget ^ints (aget st 6) placed))
                                                                    (d-add-reloc! st (long patch-pos) (long owner) placed))
                                                                  (recur))
                                                              (let [known (ssio/block-size sio off)
                                                                    est0 (if (pos? known)
                                                                           known
                                                                           (let [ci (alloc/decode-class-idx off)]
                                                                             (if (< ci 6) (nth slab-class-sizes ci) 65536)))
                       ;; The copy window doubles until the bounded size
                       ;; scan fits inside it. A heap-domain block's class
                       ;; size says nothing about its content size, and
                       ;; the old unbounded scan over a short window read
                       ;; garbage past the copy (measured at the Z1 pin,
                       ;; 2026-11-12: wild sizes, a 68 MB arraycopy, a
                       ;; scan running off the image buffer).
                                                                    size (loop [est est0]
                                                                           (d-ensure! st (+ (long (aget st 5)) est))
                                                                           (copy-block-bytes! sio off (aget st 4) (long (aget st 5)) est)
                                                                           (let [sz (content-size-in sio (aget st 4) (long (aget st 5)) off kind est)]
                                                                             (cond (not= sz SHORT) sz
                                                                                   (< est 16777216) (recur (* 2 est))
                                                                                   :else -1)))
                                                                    base (long (aget st 5))]
                                                                (when (neg? size)
                                                                  (throw (ex-info (str "eve.flat: no size for the block at " off
                                                                                       " (kind " kind ") — unregistered deftype, or a kind "
                                                                                       "this reader does not size")
                                                                                  {:type ::unsized-block :off off :kind kind})))
                                                                (when (> size est0)
                     ;; a header-derived size (exact, not scan-derived)
                     ;; past the first window: re-copy at the true size
                                                                  (d-ensure! st (+ base size))
                                                                  (copy-block-bytes! sio off (aget st 4) base size))
                                                                (let [ord (long (aget st 9))]
                                                                  (d-insert! st off ord)
                                                                  (aset st 6 (d-grow-ints (aget st 6) (inc ord)))
                                                                  (aset st 7 (d-grow-ints (aget st 7) (inc ord)))
                                                                  (aset st 8 (d-grow-ints (aget st 8) (inc ord)))
                                                                  (aset ^ints (aget st 6) (int ord) (int base))
                                                                  (aset ^ints (aget st 7) (int ord) (int size))
                                                                  (aset ^ints (aget st 8) (int ord) (unchecked-int off))
                                                                  (aset st 9 (inc ord))
                                                                  (when (<= 0 (long patch-pos))
                                                                    (put-i32-at! (aget st 4) patch-pos base)
                                                                    (d-add-reloc! st patch-pos owner ord))
                                                                  (aset st 5 (+ base (align4 size)))
                   ;; scan the copied block: patch fields whose targets are
                   ;; already placed; queue the rest as stack entries carrying
                   ;; their patch position
                                                                  (block-pointers
                                                                   sio (aget st 4) base off kind
                                                                   (fn [bpos ck target]
                                                                     (let [tgt (d-lookup st (long target))
                                                                           field-pos (+ base (long bpos))]
                                                                       (if (not= tgt -1)
                                                                         (do (put-i32-at! (aget st 4) field-pos (aget ^ints (aget st 6) tgt))
                                                                             (d-add-reloc! st field-pos ord tgt))
                                                                         (push! ck (long target) field-pos ord)))))
                                                                  (recur)))))))
     ;; tables + header
     (let [n (long (aget st 9))
           r (long (aget st 13))
           blocks-end (long (aget st 5))
           tables-off (align8 blocks-end)
           total (align8 (+ tables-off (* 8 n) (* 12 r)))]
       (d-ensure! st total)
       (let [bs (aget st 4)
             boff (aget st 6) bsz (aget st 7)
             rpos (aget st 10) rown (aget st 11) rtgt (aget st 12)
             srcs (aget st 8)]
         (dotimes [i n]
           (put-i32-at! bs (+ tables-off (* 8 i)) (aget boff i))
           (put-i32-at! bs (+ tables-off (* 8 i) 4) (aget bsz i)))
         (dotimes [i r]
           (let [p (+ tables-off (* 8 n) (* 12 i))]
             (put-i32-at! bs p (aget rpos i))
             (put-i32-at! bs (+ p 4) (aget rown i))
             (put-i32-at! bs (+ p 8) (aget rtgt i))))
        ;; canonical pads: the copy window is sized before a block's
        ;; content is, so the window copy writes source-block slack
        ;; past each block's content into the gaps between blocks and
        ;; before the tables. No reader reads those bytes, but an
        ;; image's bytes must not depend on the source slab's slack —
        ;; measured on Node: byte 340 (the pad after the last block)
        ;; carried a 5 from the CLJS source block where the JVM image
        ;; of the same value carried 0, the ONLY differing byte of 512.
        ;; Zero every gap so the image is a function of the value.
         (dotimes [i n]
           (let [end (+ (aget boff i) (aget bsz i))
                 nxt (if (< (inc i) n) (aget boff (inc i)) tables-off)]
             (when (< end nxt)
               #?(:clj (java.util.Arrays/fill bs (int end) (int nxt) (byte 0))
                  :cljs (.fill bs 0 end nxt)))))
         ;; the header, by the frozen contract (§2/§9): kind image, the
         ;; layout id in the nsym field, symtab = the block table,
         ;; shapetab = the relocation table, no type table (types
         ;; resolve in the reading registry, as the probe's images did)
         (contract/write-header! bs 0 {:kind :image
                                       :length total
                                       :root (aget boff 0)
                                       :layout-id contract/layout-canonical
                                       :symtab tables-off
                                       :shapetab (+ tables-off (* 8 n))
                                       :typetab 0})
         (Image. #?(:clj (Arrays/copyOf bs (int total)) :cljs (.slice bs 0 total))
                 total (aget boff 0)
                 #?(:clj (Arrays/copyOf boff (int n)) :cljs boff)
                 #?(:clj (Arrays/copyOf bsz (int n)) :cljs bsz)
                 #?(:clj (Arrays/copyOf rpos (int r)) :cljs rpos)
                 #?(:clj (Arrays/copyOf rown (int r)) :cljs rown)
                 #?(:clj (Arrays/copyOf rtgt (int r)) :cljs rtgt)
                 #?(:clj (Arrays/copyOf srcs (int n)) :cljs srcs)))))))

(declare image-bytes)

(defn detach
  "The image of x, as one byte array (the frozen eve.flat verb's lane
   implementation): x is an Eve atom — its committed value, copied under
   the pin — or a slab-backed value of some domain."
  [x]
  (if #?(:clj (instance? clojure.lang.IAtom x) :cljs false)
    (ea/read-pinned x (fn [lv] (image-bytes (detach-image lv))))
    (image-bytes (detach-image x))))

;; ---------------------------------------------------------------------------
;; opening and reading
;; ---------------------------------------------------------------------------

(defn open-image
  "An Image over bs: the header is parsed by the frozen contract (a wrong
   magic is refused there, by name), the tables read. Content is NOT
   walked — a hostile buffer is C4's checked opening, not this one's."
  [bs]
  (let [len (alength bs)]
    (when (< len contract/header-size)
      (throw (ex-info "eve.flat: an image is at least a header" {:type ::short-image})))
    (let [hdr (contract/read-header bs 0)
          total (:length hdr)
          root (:root hdr)
          layout (contract/layout-id hdr)
          btab (:symtab hdr)
          rtab (:shapetab hdr)]
      (when-not (= :image (:kind hdr))
        (throw (ex-info "eve.flat: not an image chunk (kind is not 1)" {:type ::bad-kind})))
      (when-not (== contract/format-version (:version hdr))
        (throw (ex-info "eve.flat: image version is not 1" {:type ::bad-version})))
      (when-not (== contract/layout-canonical layout)
        (throw (ex-info (str "eve.flat: layout " layout " is not placed by this reader")
                        {:type ::unknown-layout :layout layout})))
      (when (> total len)
        (throw (ex-info "eve.flat: the header's length runs past the buffer"
                        {:type ::short-image})))
      (if (or (zero? btab) (>= btab total))
        (Image. bs total root (int-array 0) (int-array 0) (int-array 0) (int-array 0) (int-array 0) nil)
        (let [n (quot (- rtab btab) 8)
              r (quot (- total rtab) 12)
              boff (int-array n)
              bsz (int-array n)
              rpos (int-array r)
              rown (int-array r)
              rtgt (int-array r)]
          (dotimes [i n]
            (aset boff i (int (read-i32-at bs (+ btab (* 8 i)))))
            (aset bsz i (int (read-i32-at bs (+ btab (* 8 i) 4)))))
          (dotimes [i r]
            (let [p (+ rtab (* 12 i))]
              (aset rpos i (int (read-i32-at bs p)))
              (aset rown i (int (read-i32-at bs (+ p 4))))
              (aset rtgt i (int (read-i32-at bs (+ p 8))))))
          (Image. bs total root boff bsz rpos rown rtgt nil))))))

(defn image-bytes "The image's byte array (its written length is image-length)." [^Image im] (.-bs im))
(defn image-length "The image's written length." ^long [^Image im] (.-length im))
(defn image-root "The root block's buffer offset." ^long [^Image im] (.-root im))

(defn image-blocks
  "The block table: [{:ordinal :image-off :size (:src-off)} …] in buffer order."
  [^Image im]
  (let [boff (.-boff im) bsz (.-bsz im) src (.-src im)]
    (mapv (fn [i] (cond-> {:ordinal i :image-off (aget boff i) :size (aget bsz i)}
                    src (assoc :src-off (aget src i))))
          (range (alength boff)))))

(defn image-relocations
  "The relocation table: [{:field-pos :owner :target} …] (ordinals into the block table)."
  [^Image im]
  (let [rpos (.-rpos im) rown (.-rown im) rtgt (.-rtgt im)]
    (mapv (fn [i] {:field-pos (aget rpos i) :owner (aget rown i) :target (aget rtgt i)})
          (range (alength rpos)))))

(defn image-view
  "The frozen view sio over the image: reads in place, every mutator refused."
  [^Image im]
  (ssio/view-sio (.-bs im) (.-length im) (.-boff im) (.-bsz im)))

(defn read-value
  "The value whose header/value block is at off in sio, read in place."
  [sio ^long off]
  (let [tag (ser/read-header-tag sio off)]
    (if (== tag 0x01)
      (if (satisfies? ssio/IBlockSize sio)
        (let [len (ssio/block-size sio off)]
          (codec/decode sio (alloc/-sio-read-bytes sio off 1 (dec len))))
        (alloc/scalar-block->value sio off))
      (let [ctor (ser/get-header-constructor tag)]
        (when-not ctor
          (throw (ex-info (str "eve.flat: no constructor for tag " tag)
                          {:type ::unreadable-tag :tag tag})))
        (ctor sio off)))))

(defn image-value
  "The image's root value, read in place over its frozen view."
  [^Image im]
  (read-value (image-view im) (.-root im)))
