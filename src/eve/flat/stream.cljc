(ns eve.flat.stream
  "STREAM — the atom-transfer wire protocol (doc/flat/STREAM-PLAN.md,
   plan of record). The sender walks a value from its root with a raw
   slab cursor and streams blocks in POST-ORDER (children before
   parents, root last), cut into segments the receiver places with one
   run claim each. Pointers travel as stream-global block indices;
   shapes carry the pointer-slot maps, so no per-block relocation list
   travels; payloads are byte-verbatim.

   S0 (this namespace's first landing): the cursor + the post-order
   walk itself — count + checksum, emitting nothing. The walk
   enumerates exactly the block graph eve.flat.transfer/block-scan
   defines (the shipped detach/transfer walk), driven over direct
   per-class slab access instead of the generic per-block read path."
  (:require [eve.atom :as ea]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.deftype-proto.serialize :as ser]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.digest :as digest]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.transfer :as t]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [eve.slab-io :as slab])
  #?(:clj (:import [eve.deftype_proto.alloc JvmSlabCtx]
                   ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
                   #_{:clj-kondo/ignore [:unused-import]} [java.util ArrayList Arrays])))

;; ---------------------------------------------------------------------------
;; the cursor: direct per-class slab access.
;; For each class, resolve ONCE how its bytes are reached: a heap
;; region's byte array + data offset, or a mapped region + its
;; IPlainLoad. A block fetch is then one arraycopy / one bulk region
;; read at a computed offset — never the generic per-word descent.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftype Cursor [sio root
                    ^objects arrs ^longs bases ^longs sizes
                    ^booleans resolved]
     ;; per class ci: arrs[ci] = the class region's heap byte array
     ;; (nil for mapped regions), bases[ci] = the byte offset of
     ;; block 0 in it, sizes[ci] = the class block size.
     ))

#?(:clj
   (defn cursor
     "A raw slab cursor over sio (the domain's storage). LoggingSlabIO
      is unwrapped for reads (its log is a write-side concern)."
     [sio]
     (let [root (alloc/sio-root sio)]
       (->Cursor root root (object-array 7) (long-array 7) (long-array 7)
                 (boolean-array 7)))))

#?(:clj
   (defn- class-size
     "The block size of class ci in sio's domain (classes 0-5): the JVM
      ctx's own geometry where there is one (transfer's class-size,
      mirrored — the canonical CLASS_BLOCK_SIZES otherwise)."
     ^long [sio ^long ci]
     (let [root (alloc/sio-root sio)]
       (if (instance? JvmSlabCtx root)
         (long (aget ^ints (.-block-sizes ^JvmSlabCtx root) (int ci)))
         (long (nth alloc/CLASS_BLOCK_SIZES ci))))))

#?(:clj
   (defn- cursor-class-size
     "class-size over a cursor's cached root (the walk's hot path)."
     ^long [^Cursor cur ^long ci]
     (let [root (.-root cur)]
       (if (instance? JvmSlabCtx root)
         (long (aget ^ints (.-block-sizes ^JvmSlabCtx root) (int ci)))
         (long (nth alloc/CLASS_BLOCK_SIZES ci))))))

#?(:clj
   (defn- class-access
     "Resolve class ci's direct access from a sample offset in it,
      cached per class on the cursor's primitive arrays. The array is
      nil for mapped regions (no heap bytes) — the fetch then uses
      the region's own bulk read."
     [^Cursor cur ^long off]
     (let [ci (alloc/decode-class-idx off)]
       (when-not (aget ^booleans (.-resolved cur) (int ci))
         (let [bsz (cursor-class-size cur ci)
               sio (.-sio cur)
               arr (alloc/node-heap-array sio off)]
           (aset ^objects (.-arrs cur) (int ci) arr)
           (aset ^longs (.-bases cur) (int ci)
                 (if arr
                   (- (alloc/node-base sio off)
                      (* (alloc/decode-block-idx off) bsz))
                   0))
           (aset ^longs (.-sizes cur) (int ci) bsz)
           (aset ^booleans (.-resolved cur) (int ci) true))))
     nil))

#?(:clj
   (defn- cursor-fetch
     "The block at off, fetched long enough for block-scan: the whole
      class block for ci < 6 (block-scan never reads past the block's
      own size, which the class bounds); the shipped windowed fetch
      for the overflow class."
     ^bytes [^Cursor cur ^bytes scratch off kind ci]
     (if (== ci 6)
       (t/fetch-block (.-sio cur) off kind ci)
       (do (class-access cur off)
           (let [arr ^bytes (aget ^objects (.-arrs cur) (int ci))
                 bsz (aget ^longs (.-sizes cur) (int ci))]
             (if arr
               (do (System/arraycopy arr
                                     (int (+ (aget ^longs (.-bases cur) (int ci))
                                             (* (alloc/decode-block-idx off) bsz)))
                                     scratch 0 (int bsz))
                   scratch)
               ;; no heap array (mapped region, encrypted or foreign
               ;; sio): the region's own bulk read, which itself falls
               ;; back to the generic read when the sio has no slab ctx.
               (alloc/node-bytes (.-sio cur) off 0 bsz)))))))

;; ---------------------------------------------------------------------------
;; the walk machinery: an open-addressing offset->index table and a
;; primitive frame stack. The first sender's walk paid for boxed
;; HashMap keys, Object-array frames read through reflective aget,
;; and a reflective arraycopy on every block (~100-300 us/block
;; measured); this machinery is all primitive arrays and direct
;; field access. Keys are (inc (bit-and off 0xFFFFFFFF)) as before,
;; so key 0 is free as the empty sentinel; values are 0 = absent,
;; 1 = in flight, else the block's stream index + 2.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftype Idx [^objects cells]
     ;; cells: [0] keys long[], [1] vals int[], [2] mask long[1],
     ;; [3] count long[1]. The arrays live behind one public field:
     ;; deftype mutable fields are package-private, and access to
     ;; them from other functions compiles to a reflective lookup
     ;; that cannot see them. Array contents are mutated in place;
     ;; growth swaps the arrays inside the cells.
     ))

#?(:clj
   (defn- idx-new ^Idx []
     (let [cap 65536]
       (->Idx (object-array [(long-array cap) (int-array cap)
                             (long-array [(dec cap)]) (long-array 1)])))))

#?(:clj
   (defn- idx-get
     "0 = absent, 1 = in flight, else the stream index + 2."
     ^long [^Idx ix ^long k]
     (let [cells ^objects (.-cells ix)
           ks ^longs (aget cells 0)
           vs ^ints (aget cells 1)
           mask (aget ^longs (aget cells 2) 0)]
       (loop [h (let [p (unchecked-multiply k -7046029254386353131)]
                  (bit-and (bit-xor p (unsigned-bit-shift-right p 16)) mask))]
         (let [kk (aget ks (int h))]
           (cond (== kk 0) 0
                 (== kk k) (aget vs (int h))
                 :else (recur (bit-and (inc h) mask))))))))

#?(:clj
   (defn- idx-put [^Idx ix ^long k ^long v]
     (let [cells ^objects (.-cells ix)
           nc ^longs (aget cells 3)]
       (when (> (* (inc (aget nc 0)) 10) (* (alength ^longs (aget cells 0)) 7))
         (let [oks ^longs (aget cells 0) ovs ^ints (aget cells 1)
               ncap (* 2 (alength oks))
               nks (long-array ncap) nvs (int-array ncap)
               nmask (dec ncap)]
           (dotimes [i (alength oks)]
             (let [kk (aget oks i)]
               (when (not (== kk 0))
                 (loop [h (let [p (unchecked-multiply kk -7046029254386353131)]
                            (bit-and (bit-xor p (unsigned-bit-shift-right p 16)) nmask))]
                   (if (== (aget nks (int h)) 0)
                     (do (aset nks (int h) kk) (aset nvs (int h) (aget ovs i)))
                     (recur (bit-and (inc h) nmask)))))))
           (aset cells 0 nks) (aset cells 1 nvs)
           (aset ^longs (aget cells 2) 0 nmask)))
       (let [ks ^longs (aget cells 0) vs ^ints (aget cells 1)
             mask (aget ^longs (aget cells 2) 0)]
         (loop [h (let [p (unchecked-multiply k -7046029254386353131)]
                    (bit-and (bit-xor p (unsigned-bit-shift-right p 16)) mask))]
           (let [kk (aget ks (int h))]
             (cond (== kk 0) (do (aset ks (int h) k) (aset vs (int h) (int v))
                                 (aset nc 0 (inc (aget nc 0))))
                   (== kk k) (aset vs (int h) (int v))
                   :else (recur (bit-and (inc h) mask)))))))
     nil))

#?(:clj
   (deftype Frames [^objects cells]
     ;; cells: [0] fs long[] (6 longs per frame: off kind p1 p2 p3
     ;; state), [1] bs Object[] (frame i's kept block bytes),
     ;; [2] ps Object[] (frame i's scan pointer list), [3] top
     ;; long[1]. Same one-public-field shape as Idx, same reason.
     ))

#?(:clj
   (defn- frames-new ^Frames []
     (->Frames (object-array [(long-array (* 6 4096)) (object-array 4096)
                              (object-array 4096) (long-array 1)]))))

#?(:clj
   (defn- frames-top
     "The Frames top counter cell (a long[1] the walks hold directly)."
     ^longs [^Frames fr]
     (aget ^objects (.-cells fr) 3)))

#?(:clj
   (defn- frames-push
     "Push the frame staged in f6 (6 longs: off kind p1 p2 p3 state)
      with its kept bytes / pointer list. f6 is copied, so callers
      stage into one reusable array."
     [^Frames fr ^longs f6 ^bytes bytes ptrs]
     (let [cells ^objects (.-cells fr)
           topc ^longs (aget cells 3)
           top (aget topc 0)
           bs ^objects (aget cells 1)]
       (when (== top (alength bs))
         (let [ofs ^longs (aget cells 0) ops ^objects (aget cells 2)
               nfs (long-array (* 2 (alength ofs)))
               nbs (object-array (* 2 (alength bs)))
               nps (object-array (* 2 (alength ops)))]
           (System/arraycopy ofs 0 nfs 0 (alength ofs))
           (System/arraycopy bs 0 nbs 0 (alength bs))
           (System/arraycopy ops 0 nps 0 (alength ops))
           (aset cells 0 nfs) (aset cells 1 nbs) (aset cells 2 nps)))
       (System/arraycopy f6 0 (aget cells 0) (int (unchecked-multiply top 6)) 6)
       (aset ^objects (aget cells 1) (int top) bytes)
       (aset ^objects (aget cells 2) (int top) ptrs)
       (aset topc 0 (inc top)))
     nil))

#?(:clj
   (defmacro ^:private push-frame!
     "Stage a frame into the caller's reusable f6 and push it."
     [fr f6 off kind p1 p2 p3 state bytes ptrs]
     `(do (aset ^longs ~f6 0 ~off)
          (aset ^longs ~f6 1 ~kind)
          (aset ^longs ~f6 2 ~p1)
          (aset ^longs ~f6 3 ~p2)
          (aset ^longs ~f6 4 ~p3)
          (aset ^longs ~f6 5 ~state)
          (frames-push ~fr ~f6 ~bytes ~ptrs))))

;; ---------------------------------------------------------------------------
;; S0: the post-order walk — count + checksum, emitting nothing.
;; Indices are assigned at EXIT (post-order): a block's index is
;; greater than every block it points to. Shared blocks are indexed
;; once, at their first completed visit.
;; ---------------------------------------------------------------------------

#_{:clj-kondo/ignore [:unused-binding]} ; bindings in this form are used on the :clj pass only; the :cljs arm does not reference them (per-pass binding surface)
(defn walk-count
  "Post-order cursor walk over the value rooted at root-off in sio.
   Answers {:blocks n :bytes total-content-bytes :checksum c
   :per-class [c0..c6]}. The traversal set is block-scan's — the same
   graph the shipped detach walks. The marking discipline is this
   walk's own (mark at push, index at exit); send-stream's pop-time
   discipline is the one the wire order is defined by."
  [sio ^long root-off]
  #?(:clj
     (let [cur (cursor sio)
           seen (idx-new)
           stack (frames-new)
           f6 (long-array 6)
           scratch (byte-array 1024)
           cnt (long-array 1)
           byt (long-array 1)
           csum (long-array 1)
           per-class (long-array 7)]
       (idx-put seen (unchecked-inc (bit-and root-off 0xFFFFFFFF)) 1)
       (push-frame! stack f6 root-off 0 0 0 0 0 nil nil)
       (while (pos? (aget (frames-top stack) 0))
         (aset (frames-top stack) 0 (dec (aget (frames-top stack) 0)))
         (let [top (aget (frames-top stack) 0)
               fs ^longs (aget ^objects (.-cells stack) 0)
               b (unchecked-multiply top 6)
               off (aget fs (int b))]
           (if (== (aget fs (int (+ b 5))) 1)
             ;; EXIT: assign the post-order index
             (let [idx (aget cnt 0)
                   size (aget fs (int (inc b)))]
               (idx-put seen (unchecked-inc (bit-and off 0xFFFFFFFF)) (+ idx 2))
               (aset cnt 0 (inc idx))
               (aset byt 0 (+ (aget byt 0) size))
               (aset csum 0 (unchecked-add (aget csum 0)
                                           (unchecked-multiply (inc idx)
                                                               (bit-xor size (bit-shift-left off 1)))))
               (let [ci (alloc/decode-class-idx off)]
                 (aset per-class (int ci) (inc (aget per-class (int ci))))))
             ;; ENTER
             (let [kind (aget fs (int (inc b))) p1 (aget fs (int (+ b 2)))
                   p2 (aget fs (int (+ b 3))) p3 (aget fs (int (+ b 4)))
                   ci (alloc/decode-class-idx off)
                   bytes (cursor-fetch cur scratch off kind ci)
                   scan (t/block-scan sio sio bytes kind p1 p2 p3 ci off)
                   size (long (:size scan))
                   ptrs (:ptrs scan)]
               (push-frame! stack f6 off size 0 0 0 1 nil nil)
               (loop [i (dec (count ptrs))]
                 (when (>= i 0)
                   (let [pr (nth ptrs i)
                         tgt (long (nth pr 5))]
                     (when (== (idx-get seen (unchecked-inc (bit-and tgt 0xFFFFFFFF))) 0)
                       (idx-put seen (unchecked-inc (bit-and tgt 0xFFFFFFFF)) 1)
                       (push-frame! stack f6 tgt (long (nth pr 1)) (long (nth pr 2)) (long (nth pr 3)) (long (nth pr 4)) 0 nil nil)))
                   (recur (dec i))))))))
       {:blocks (aget cnt 0)
        :bytes (aget byt 0)
        :checksum (aget csum 0)
        :per-class (vec per-class)})
     :cljs (throw (ex-info "stream: the spike is JVM-first (STREAM-PLAN: out of scope)"
                           {:refusal :not-built}))))

(defn walk-value-count
  "walk-count over a slab value's root in its home domain."
  [v]
  (walk-count (d/-eve-sio v) (d/-root-header-off v)))

;; ---------------------------------------------------------------------------
;; S1: the pipe — sender (post-order walk emitting wire bytes) and
;; receiver (one run claim per class segment, verbatim copies, one
;; patch pass). Wire v1, all words little-endian (v0 = v1 with one
;; segment per class and no run records; the receiver reads both):
;;
;;   header (20 B): magic "EVST", version u8, flags u8 = 0, pad u16,
;;                  total block count i32, segment count i32,
;;                  root index i32 (always total-1: post-order).
;;   segment: class i32, block count i32, payload bytes i32, records.
;;   record: block index i32, scan kind u8, the scan params p1 p2 p3 as
;;           i32 triple ONLY for kinds 5/6 (trie nodes — the only kinds
;;           whose slot layout needs parent context), block size i32,
;;           the block's bytes, verbatim, with every pointer word
;;           rewritten to its target's stream index.
;;
;; Segments group the post-order-indexed blocks by class (the lane's
;; recorded geometry: alloc-run! claims consecutive blocks of ONE
;; class, so one claim per segment requires class-homogeneous
;; segments; indices stay global post-order, root = last index).
;; Within a segment, records are in ascending index order.
;;
;; Shapes: no slot table travels. A block's pointer slots are a pure
;; function of its bytes plus its scan params — eve.flat.transfer/
;; block-scan IS the per-tag slot resolver, run identically by the
;; sender (to rewrite offsets to indices) and the receiver (to rewrite
;; indices to placed offsets). Descriptor bytes per block in v0: the
;; record header above; dictionary coding of descriptors is S2.
;; ---------------------------------------------------------------------------

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (the refusal paths in the #?(:clj ...) sections); no cljs caller exists
(defn- refuse! [name msg data]
  (throw (ex-info (str "eve.flat.stream: " msg)
                  (assoc data :eve.flat.stream/refusal name))))

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (the byte reader/writer sections); no cljs caller exists
(defn- w-i32 ^long [^bytes a ^long i]
  (let [i (int i)]
    (long (unchecked-int
           (bit-or (bit-and (long (aget a i)) 0xFF)
                   (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 1))) 0xFF) 8)
                   (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 2))) 0xFF) 16)
                   (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 3))) 0xFF) 24))))))

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (the byte reader/writer sections); no cljs caller exists
(defn- w-put-i32! [^bytes a ^long i ^long v]
  (let [i (int i)]
    (aset a i (unchecked-byte v))
    (aset a (unchecked-add-int i 1) (unchecked-byte (bit-shift-right v 8)))
    (aset a (unchecked-add-int i 2) (unchecked-byte (bit-shift-right v 16)))
    (aset a (unchecked-add-int i 3) (unchecked-byte (bit-shift-right v 24)))
    nil))

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (the byte reader/writer sections); no cljs caller exists
(defn- w-put-u8! [^bytes a ^long i ^long v]
  (aset a (int i) (unchecked-byte v))
  nil)

#?(:clj
   (defn- run-entry
     "Collapse records [i, i+cnt) of a class bucket into one typed-run
      entry: [:run start-idx kind size count payload-bytes]."
     [^ArrayList recs ^long i ^long cnt]
     (let [r0 (.get recs (int i))
           size (long (aget ^objects r0 5))
           payload (byte-array (* cnt size))]
       (dotimes [k cnt]
         (System/arraycopy ^bytes (aget ^objects (.get recs (int (+ i k))) 6) 0
                           payload (int (* k size)) (int size)))
       (object-array [:run (aget ^objects r0 0) (aget ^objects r0 1) size cnt payload]))))

#?(:clj
   (defn- collapse-runs
     "A bucket's records (ascending index order) with runs collapsed:
      a run is >= threshold bucket-consecutive, index-consecutive,
      leaf (no pointer slots), same-kind, same-size records. With
      threshold 0 the records pass through unchanged."
     [^ArrayList recs ^long threshold]
     (if (zero? threshold)
       recs
       (let [n (.size recs)
             out (ArrayList.)]
         (loop [i 0]
           (when (< i n)
             (let [r0 (.get recs i)
                   kind (long (aget ^objects r0 1))
                   size (long (aget ^objects r0 5))
                   start (long (aget ^objects r0 0))]
               (if (aget ^objects r0 7)
                 (let [j (loop [j (inc i)]
                           (if (and (< j n)
                                    (let [r (.get recs j)]
                                      (and (aget ^objects r 7)
                                           (== (long (aget ^objects r 1)) kind)
                                           (== (long (aget ^objects r 5)) size)
                                           (== (long (aget ^objects r 0)) (+ start (- j i))))))
                             (recur (inc j))
                             j))
                       cnt (- j i)]
                   (if (>= cnt threshold)
                     (do (.add out (run-entry recs i cnt)) (recur j))
                     (do (.add out r0) (recur (inc i)))))
                 (do (.add out r0) (recur (inc i)))))))
         out))))

#?(:clj
   (defn- entry-wire-size ^long [e]
     (if (identical? :run (aget ^objects e 0))
       (+ 13 (* (long (aget ^objects e 4)) (long (aget ^objects e 3))))
       (let [kind (long (aget ^objects e 1))]
         (+ 9 (if (or (== kind 5) (== kind 6)) 12 0) (long (aget ^objects e 5)))))))

#?(:clj
   (defn- latched-digest
     "The block at off's latched content digest (8 i32 words) when off
      is a collection root whose eve.digest cache carries one, else
      nil. Reads only what eve.digest already latched — never
      computes, never stamps."
     [sio ^long off]
     (when-let [blk (digest/cache-block sio off)]
       (when (not (zero? (bit-and (slab/read-i32 sio blk digest/F_FLAGS)
                                  digest/FLAG_DIGEST)))
         (mapv (fn [i] (slab/read-i32 sio blk (+ digest/F_DIGEST (* 4 i))))
               (range 8))))))

#?(:clj
   (defn send-stream
     "Encode the value rooted at root-off in sio as wire bytes: the S0
      cursor walk, extended — each block's bytes are kept at enter; at
      exit (post-order) its pointer words are rewritten from slab
      offsets to stream indices and its record is appended to its
      class's segment. Opts: :budget — max payload bytes per segment
      (segments are cut at entry boundaries, an oversize entry forms
      its own segment); :run-threshold — collapse runs of at least
      this many consecutive leaf records into typed runs (0 = off).
      Returns {:bytes :blocks :segments :per-class :runs}."
     ([sio root-off] (send-stream sio root-off {}))
     ([sio ^long root-off opts]
      (let [cur (cursor sio)
            seen (idx-new)
            stack (frames-new)
            f6 (long-array 6)
            scratch (byte-array 1024)
            buckets (object-array 7)
            cnt (long-array 1)
            per-class (long-array 7)
            use-digests (boolean (get opts :digests))
            dcache (java.util.HashMap.)
            pending (java.util.HashMap.)
            dtable (ArrayList.)
            deduped (long-array 1)]
        (dotimes [ci 7] (aset buckets ci (ArrayList.)))
        (push-frame! stack f6 root-off 0 0 0 0 0 nil nil)
        (while (pos? (aget (frames-top stack) 0))
          (aset (frames-top stack) 0 (dec (aget (frames-top stack) 0)))
          (let [top (aget (frames-top stack) 0)
                fs ^longs (aget ^objects (.-cells stack) 0)
                b (unchecked-multiply top 6)
                off (aget fs (int b))]
            (if (== (aget fs (int (+ b 5))) 1)
              ;; EXIT: index, rewrite, emit
              (let [kind (aget fs (int (inc b))) p1 (aget fs (int (+ b 2)))
                    p2 (aget fs (int (+ b 3))) p3 (aget fs (int (+ b 4)))
                    bytes ^bytes (aget ^objects (aget ^objects (.-cells stack) 1) (int top))
                    ptrs (aget ^objects (aget ^objects (.-cells stack) 2) (int top))
                    ci (alloc/decode-class-idx off)
                    idx (aget cnt 0)]
                (idx-put seen (unchecked-inc (bit-and off 0xFFFFFFFF)) (+ idx 2))
                (when use-digests
                  (when-let [dw (.remove pending (Long. off))]
                    (.put dcache dw (Long. idx))
                    (.add dtable (object-array [idx dw]))))
                (doseq [pr ptrs]
                  (let [tgt (long (nth pr 5))
                        tv (idx-get seen (unchecked-inc (bit-and tgt 0xFFFFFFFF)))]
                    (when (< tv 2)
                      (refuse! :forward-pointer
                               "a pointer whose target has no post-order index (the walk is post-order: every target exits first)"
                               {:target tgt}))
                    (w-put-i32! bytes (long (nth pr 0)) (int (- tv 2)))))
                (.add ^ArrayList (aget ^objects buckets (int ci))
                      (object-array [idx kind p1 p2 p3 (alength bytes) bytes (zero? (count ptrs))]))
                (aset per-class (int ci) (inc (aget per-class (int ci))))
                (aset cnt 0 (inc idx)))
              ;; ENTER — the seen discipline that makes the order a
              ;; true post-order on a DAG: a block is marked when its
              ;; ENTER frame is POPPED, never when pushed. A cross
              ;; edge to a block whose frame was pushed by an earlier
              ;; sibling (the shared-structure case: a map node and
              ;; the vector it points at, both children of the root)
              ;; then recurses into that block first — pushing at
              ;; discovery would let this block exit while its target
              ;; is still in flight, and the exit rewrite would read
              ;; the in-flight marker as the target's index.
              (let [k (unchecked-inc (bit-and off 0xFFFFFFFF))
                    prior (idx-get seen k)]
                (cond (== prior 1)
                      (refuse! :cycle "the walk reached a block already on its own path (a cycle — persistent values are acyclic)"
                               {:offset off})
                      (>= prior 2) nil
                      :else
                      (let [;; digests live only on collection roots,
                            ;; and a root is always reached as a kind-0
                            ;; (header-tagged) block — HAMT interiors,
                            ;; strings and scalar blocks skip the
                            ;; descriptor lookup entirely.
                            dw (when (and use-digests (== (aget fs (int (inc b))) 0))
                                 (latched-digest sio off))
                            hit (when dw (.get dcache dw))]
                        (if hit
                          ;; digest dedup: this subtree was already
                          ;; sent under index hit — alias this block
                          ;; to that index and never descend.
                          (do (idx-put seen k (+ (long hit) 2))
                              (aset deduped 0 (inc (aget deduped 0))))
                          (let [kind (aget fs (int (inc b))) p1 (aget fs (int (+ b 2)))
                                p2 (aget fs (int (+ b 3))) p3 (aget fs (int (+ b 4)))
                                ci (alloc/decode-class-idx off)
                                bytes0 (cursor-fetch cur scratch off kind ci)
                                scan (t/block-scan sio sio bytes0 kind p1 p2 p3 ci off)
                                size (long (:size scan))
                                bytes (Arrays/copyOf bytes0 (int size))
                                ptrs (:ptrs scan)]
                            (when dw (.put pending (Long. off) dw))
                            (idx-put seen k 1)
                            (push-frame! stack f6 off kind p1 p2 p3 1 bytes ptrs)
                            (loop [i (dec (count ptrs))]
                              (when (>= i 0)
                                (let [pr (nth ptrs i)
                                      tgt (long (nth pr 5))]
                                  (push-frame! stack f6 tgt (long (nth pr 1)) (long (nth pr 2)) (long (nth pr 3)) (long (nth pr 4)) 0 nil nil))
                                (recur (dec i))))))))))))
        ;; entries per class (runs collapsed), cut into segments by
        ;; budget; segments keep class order, entries keep index order
        (let [n (aget cnt 0)
              threshold (long (get opts :run-threshold 0))
              budget (long (get opts :budget Long/MAX_VALUE))
              segments (ArrayList.)
              runs (long-array 1)]
          (doseq [ci (range 7)]
            (when (pos? (aget per-class (int ci)))
              (let [entries (collapse-runs (aget buckets (int ci)) threshold)
                    cur-seg (ArrayList.)
                    cur-bytes (long-array 1)
                    cur-blocks (long-array 1)
                    flush! (fn []
                             (when (pos? (.size cur-seg))
                               (.add segments [ci (vec cur-seg) (aget cur-blocks 0) (aget cur-bytes 0)])
                               (.clear cur-seg)
                               (aset cur-bytes 0 0)
                               (aset cur-blocks 0 0)))]
                (doseq [e (seq entries)]
                  (let [ws (entry-wire-size e)
                        eb (if (identical? :run (aget ^objects e 0)) (long (aget ^objects e 4)) 1)]
                    (when (identical? :run (aget ^objects e 0)) (aset runs 0 (inc (aget runs 0))))
                    (when (and (pos? (.size cur-seg)) (> (+ (aget cur-bytes 0) ws) budget))
                      (flush!))
                    (.add cur-seg e)
                    (aset cur-bytes 0 (+ (aget cur-bytes 0) ws))
                    (aset cur-blocks 0 (+ (aget cur-blocks 0) eb))))
                (flush!))))
          (let [total (+ 20 (reduce (fn [s seg] (+ s 12 (nth seg 3))) 0 (seq segments))
                         (if (pos? (.size dtable)) (+ 4 (* 36 (.size dtable))) 0))
                wire (byte-array total)
                pos (int-array [20])]
            (w-put-i32! wire 0 0x54535645) ;; "EVST" little-endian
            (w-put-u8! wire 4 1) ;; version 1: run records exist in the grammar
            (w-put-u8! wire 5 (if (pos? (.size dtable)) 1 0)) ;; flag bit 0: digest trailer follows the segments
            (w-put-i32! wire 8 n)
            (w-put-i32! wire 12 (.size segments))
            (w-put-i32! wire 16 (dec n))
            (doseq [seg (seq segments)]
              (let [ci (nth seg 0) entries (nth seg 1)]
                (w-put-i32! wire (aget pos 0) ci)
                (w-put-i32! wire (+ (aget pos 0) 4) (nth seg 2))
                (w-put-i32! wire (+ (aget pos 0) 8) (nth seg 3))
                (aset pos 0 (+ (aget pos 0) 12))
                (doseq [e entries]
                  (if (identical? :run (aget ^objects e 0))
                    (do (w-put-i32! wire (aget pos 0) (long (aget ^objects e 1)))
                        (w-put-u8! wire (+ (aget pos 0) 4) (bit-or (long (aget ^objects e 2)) 0x80))
                        (w-put-i32! wire (+ (aget pos 0) 5) (long (aget ^objects e 4)))
                        (w-put-i32! wire (+ (aget pos 0) 9) (long (aget ^objects e 3)))
                        (aset pos 0 (+ (aget pos 0) 13))
                        (System/arraycopy ^bytes (aget ^objects e 5) 0 wire (aget pos 0)
                                          (int (* (long (aget ^objects e 4)) (long (aget ^objects e 3)))))
                        (aset pos 0 (+ (aget pos 0) (int (* (long (aget ^objects e 4)) (long (aget ^objects e 3)))))))
                    (let [kind (long (aget ^objects e 1))]
                      (w-put-i32! wire (aget pos 0) (long (aget ^objects e 0)))
                      (w-put-u8! wire (+ (aget pos 0) 4) kind)
                      (aset pos 0 (+ (aget pos 0) 5))
                      (when (or (== kind 5) (== kind 6))
                        (w-put-i32! wire (aget pos 0) (long (aget ^objects e 2)))
                        (w-put-i32! wire (+ (aget pos 0) 4) (long (aget ^objects e 3)))
                        (w-put-i32! wire (+ (aget pos 0) 8) (long (aget ^objects e 4)))
                        (aset pos 0 (+ (aget pos 0) 12)))
                      (w-put-i32! wire (aget pos 0) (long (aget ^objects e 5)))
                      (aset pos 0 (+ (aget pos 0) 4))
                      (System/arraycopy ^bytes (aget ^objects e 6) 0 wire (aget pos 0) (int (long (aget ^objects e 5))))
                      (aset pos 0 (+ (aget pos 0) (int (long (aget ^objects e 5))))))))))
            (when (pos? (.size dtable))
              (w-put-i32! wire (aget pos 0) (.size dtable))
              (aset pos 0 (+ (aget pos 0) 4))
              (doseq [e (seq dtable)]
                (w-put-i32! wire (aget pos 0) (long (aget ^objects e 0)))
                (aset pos 0 (+ (aget pos 0) 4))
                (doseq [w (aget ^objects e 1)]
                  (w-put-i32! wire (aget pos 0) (int w))
                  (aset pos 0 (+ (aget pos 0) 4)))))
            {:bytes wire :blocks n :segments (.size segments)
             :per-class (vec per-class) :runs (aget runs 0)
             :digests (.size dtable) :deduped (aget deduped 0)}))))))

#?(:clj
   (defn- claim-run!
     "Claim the segment's blocks of class ci into offs (at the records'
      stream indices): one alloc-run! for the whole segment, halves on
      a nil answer, singles through -sio-alloc! at the class size —
      the shipped Z3.3 chain. Every allocator call increments
      (aget counters 0). Returns nothing."
     [sio ci ^ints idxs ^longs offs ^longs counters]
     (letfn [(go [^long lo ^long hi]
               (let [k (- hi lo)]
                 (cond
                   (zero? k) nil
                   (== k 1) (let [r (try (aset counters 0 (inc (aget counters 0)))
                                         (alloc/alloc-run! sio ci 1)
                                         (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))]
                              (aset offs (aget idxs (int lo))
                                    (long (if (and r (seq r))
                                            (nth r 0)
                                            (do (aset counters 0 (inc (aget counters 0)))
                                                (alloc/-sio-alloc! sio (class-size sio ci)))))))
                   :else (let [r (try (aset counters 0 (inc (aget counters 0)))
                                      (alloc/alloc-run! sio ci k)
                                      (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))]
                           (if (and r (== (count r) k))
                             (dotimes [j k]
                               (aset offs (aget idxs (int (+ lo j))) (long (nth r j))))
                             (let [mid (+ lo (quot k 2))]
                               (go lo mid)
                               (go mid hi)))))))]
       (go 0 (alength idxs)))))

(declare reopen)

#?(:clj
   (defn receive-stream!
     "Place wire bytes (send-stream's output) into sio's domain,
      segment by segment: parse a segment, claim its blocks with one
      run claim, copy them verbatim, and — with :check? — vouch the
      segment before the next is claimed (the checked door: every
      placed block is re-scanned in its new home; its size must match
      its record and every pointer target must be a stream index).
      After all segments, one patch pass rewrites each pointer word
      from its stream index to the placed offset. On any failure,
      free every offset claimed so far and rethrow.

      Digest trust (the trailer send-stream's :digests ships):
      :digest-trust :trusted stamps the shipped digests onto the
      placed roots as-is — the owned path, when both ends are yours
      and rehashing is waste; :verify (the default) recomputes each
      digested root's digest from its placed content and stamps
      only on a match, refusing the stream on a mismatch; :off
      ignores the trailer. Returns
      {:root-off :blocks :segments :claims :wire-bytes
       :digests-stamped}."
     ([sio wire] (receive-stream! sio wire {}))
     ([sio ^bytes wire opts]
      (let [len (alength wire)
            check? (boolean (:check? opts))]
        (when (or (< len 20) (not= (w-i32 wire 0) 0x54535645)
                  (not (or (== (aget wire 4) 0) (== (aget wire 4) 1))))
          (refuse! :bad-stream "not an EVST v0/v1 stream" {:length len}))
        (let [n (w-i32 wire 8)
              nseg (w-i32 wire 12)
              root-idx (w-i32 wire 16)
              bci (int-array n) bkind (int-array n)
              bp1 (int-array n) bp2 (int-array n) bp3 (int-array n)
              bsize (int-array n) bwoff (int-array n)
              offs (long-array n)
              seg-ranges (ArrayList.)
              counters (long-array 1)
              fast-arr (object-array 7)
              dtrailer (object-array 1)
              pos (int-array [20])]
          (dotimes [i n] (aset offs i -1))
          (try
            (dotimes [sidx nseg]
              (let [ci (w-i32 wire (aget pos 0))
                    declared (w-i32 wire (+ (aget pos 0) 4))
                    payload (w-i32 wire (+ (aget pos 0) 8))
                    idxs-list (ArrayList.)]
                (aset pos 0 (+ (aget pos 0) 12))
                (let [seg-end (+ (aget pos 0) payload)]
                  (while (< (aget pos 0) seg-end)
                    (let [bi (w-i32 wire (aget pos 0))
                          kb (bit-and (aget wire (+ (aget pos 0) 4)) 0xFF)]
                      (aset pos 0 (+ (aget pos 0) 5))
                      (if (bit-test kb 7)
                        (let [kind (bit-and kb 0x7F)
                              rcnt (w-i32 wire (aget pos 0))
                              size (w-i32 wire (+ (aget pos 0) 4))]
                          (aset pos 0 (+ (aget pos 0) 8))
                          (dotimes [j rcnt]
                            (let [bj (+ bi j)]
                              (aset bci (int bj) (int ci)) (aset bkind (int bj) (int kind))
                              (aset bsize (int bj) (int size))
                              (aset bwoff (int bj) (+ (aget pos 0) (* j size)))
                              (.add idxs-list (Integer. (int bj)))))
                          (aset pos 0 (+ (aget pos 0) (int (* rcnt size)))))
                        (let [kind kb]
                          (when (or (== kind 5) (== kind 6))
                            (aset bp1 (int bi) (int (w-i32 wire (aget pos 0))))
                            (aset bp2 (int bi) (int (w-i32 wire (+ (aget pos 0) 4))))
                            (aset bp3 (int bi) (int (w-i32 wire (+ (aget pos 0) 8))))
                            (aset pos 0 (+ (aget pos 0) 12)))
                          (let [size (w-i32 wire (aget pos 0))]
                            (aset pos 0 (+ (aget pos 0) 4))
                            (aset bci (int bi) (int ci)) (aset bkind (int bi) (int kind))
                            (aset bsize (int bi) (int size))
                            (aset bwoff (int bi) (aget pos 0))
                            (.add idxs-list (Integer. (int bi)))
                            (aset pos 0 (+ (aget pos 0) (int size))))))))
                  (when (not= (.size idxs-list) declared)
                    (refuse! :bad-stream "a segment's records do not add up to its declared block count"
                             {:segment sidx :declared declared :parsed (.size idxs-list)})))
                (let [idxs (int-array (map int idxs-list))]
                  (.add seg-ranges idxs)
                  (if (== ci 6)
                    (dotimes [j (alength idxs)]
                      (let [bi (aget idxs j)]
                        (aset counters 0 (inc (aget counters 0)))
                        (aset offs bi (alloc/-sio-alloc! sio (aget bsize bi)))))
                    (claim-run! sio ci idxs offs counters))
                  ;; copies for this segment (owner array cached per class)
                  (when (and (< ci 6) (pos? (alength idxs)) (nil? (aget fast-arr ci)))
                    (aset fast-arr ci (alloc/node-heap-array sio (aget offs (aget idxs 0)))))
                  (dotimes [j (alength idxs)]
                    (let [bi (aget idxs j)
                          arr (when (< ci 6) (aget fast-arr ci))
                          wo (aget bwoff bi) size (aget bsize bi)]
                      (if arr
                        (System/arraycopy wire wo arr (int (alloc/node-base sio (aget offs bi))) size)
                        (alloc/-sio-write-bytes! sio (aget offs bi) 0
                                                 (Arrays/copyOfRange wire wo (int (+ wo size)))))))
                  ;; the checked door: vouch this segment's blocks in
                  ;; their new home before the next segment is claimed
                  (when check?
                    (dotimes [j (alength idxs)]
                      (let [bi (aget idxs j)
                            blk (Arrays/copyOfRange wire (aget bwoff bi)
                                                    (int (+ (aget bwoff bi) (aget bsize bi))))
                            scan (t/block-scan sio sio blk (aget bkind bi) (aget bp1 bi)
                                               (aget bp2 bi) (aget bp3 bi) (aget bci bi)
                                               (aget offs bi))]
                        (when (not= (long (:size scan)) (aget bsize bi))
                          (refuse! :unchecked-segment "a placed block scans at a different size than its record declares"
                                   {:segment sidx :block bi}))
                        (doseq [pr (:ptrs scan)]
                          (let [tidx (long (nth pr 5))]
                            (when (or (neg? tidx) (>= tidx n))
                              (refuse! :unchecked-segment "a placed block points outside the stream"
                                       {:segment sidx :block bi :target tidx}))))))))))
            ;; the digest trailer (header flag bit 0): stream index ->
            ;; the sender's latched digest words for that root.
            (when (== (aget wire 5) 1)
              (let [cnt (w-i32 wire (aget pos 0))]
                (aset pos 0 (+ (aget pos 0) 4))
                (loop [i 0 m (transient {})]
                  (if (== i cnt)
                    (aset dtrailer 0 (persistent! m))
                    (let [bi (w-i32 wire (aget pos 0))]
                      (aset pos 0 (+ (aget pos 0) 4))
                      (let [ws (mapv (fn [_] (let [w (w-i32 wire (aget pos 0))]
                                               (aset pos 0 (+ (aget pos 0) 4)) w))
                                     (range 8))]
                        (recur (inc i) (assoc! m bi ws))))))))
            ;; the patch pass: slots re-derived from the wire bytes by
            ;; the same resolver the sender used
            (dotimes [i n]
              (let [wo (aget bwoff i) size (aget bsize i)
                    blk (Arrays/copyOfRange wire wo (int (+ wo size)))
                    scan (t/block-scan sio sio blk (aget bkind i) (aget bp1 i)
                                       (aget bp2 i) (aget bp3 i) (aget bci i) (aget offs i))
                    ci (aget bci i)
                    arr (when (< ci 6) (aget fast-arr ci))]
                (doseq [pr (:ptrs scan)]
                  (let [rel (long (nth pr 0))
                        toff (aget offs (int (long (nth pr 5))))]
                    (if arr
                      (w-put-i32! arr (+ (alloc/node-base sio (aget offs i)) rel) toff)
                      (alloc/-sio-write-i32! sio (aget offs i) rel toff))))))
            (let [stamped (long-array 1)
                  mode (get opts :digest-trust :verify)]
              (when-let [dm (aget dtrailer 0)]
                (when (not= mode :off)
                  (doseq [[bi ws] dm]
                    (let [poff (aget offs (int (long bi)))]
                      (cond
                        ;; trusted: the sender is one of ours —
                        ;; stamp what it shipped, no rehashing.
                        (= mode :trusted)
                        (when (digest/latch-digest-words! sio poff ws)
                          (aset stamped 0 (inc (aget stamped 0))))
                        ;; verify (the default for raw bytes):
                        ;; recompute the placed root's digest and
                        ;; stamp only on a match; a stream that lies
                        ;; about content identity is refused whole.
                        :else
                        (let [computed (digest/digest-words-of (reopen sio poff))]
                          (if (= computed ws)
                            (when (digest/latch-digest-words! sio poff ws)
                              (aset stamped 0 (inc (aget stamped 0))))
                            (refuse! :digest-mismatch
                                     "a shipped digest does not match the placed root's content"
                                     {:stream-index bi}))))))))
              {:root-off (aget offs (int root-idx)) :blocks n :segments nseg
               :claims (aget counters 0) :wire-bytes len
               :digests-stamped (aget stamped 0)})
            (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) t
              (dotimes [i n]
                (when (not= (aget offs i) -1)
                  (try (alloc/-sio-free! sio (aget offs i)) (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))))
              (throw t))))))))

#?(:clj
   (defn- reopen
     "The placed value at root offset off in sio (transfer's reopen,
      mirrored: the lazy Eve value its header constructor makes)."
     [sio ^long off]
     (let [t (ser/read-header-tag sio off)
           ctor (ser/get-header-constructor t)]
       (when-not ctor
         (refuse! :bad-stream "the placed root block has no header constructor in this process"
                  {:tag t}))
       (ctor sio off))))

#?(:clj
   (defn receive-value
     "Place wire bytes into sio's domain and answer the placed root as
      an Eve value of that domain. Opts pass through to
      receive-stream! (:check? vouches each segment at its door)."
     ([sio wire] (receive-value sio wire {}))
     ([sio ^bytes wire opts]
      (reopen sio (:root-off (receive-stream! sio wire opts))))))

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (stream-transfer!, in the #?(:clj ...) section); no cljs caller exists
(def ^:private default-send-opts
  "The send-side knobs transfer! runs with — set from the STREAM-PLAN
   S2 sweep verdicts (budget + run threshold), recorded in
   REENGINE-STATUS. Empty (one segment per class, no runs) until the
   sweep lands."
  {})

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (stream-transfer!); no cljs caller exists
(defn- ea-atom? [x]
  (some? (try (ea/-domain-state x) (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))))

#?(:clj
   (defn stream-transfer!
     "transfer! over the stream (STREAM-PLAN S1 form): a slab value or
      an Eve atom's committed value is streamed out of its home domain
      and placed into Eve atom a; anything else is stored by a plain
      swap!. Returns a."
     [a x]
     (when (nil? a)
       (refuse! :no-atom "stream-transfer! wants an Eve atom, got nil" {}))
     (let [send (fn [v] (send-stream (d/-eve-sio v) (d/-root-header-off v)
                                     (assoc default-send-opts :digests true)))]
       (cond (ea-atom? x)
             (let [sent (ea/read-pinned x (fn [v] (when (satisfies? d/IEveRoot v)
                                                    (send v))))]
               (if sent
                 (swap! a (fn [_] (receive-value d/*domain-sio* (:bytes sent)
                                                 {:check? true :digest-trust :trusted})))
                 (swap! a (fn [_] @x))))
             (satisfies? d/IEveRoot x)
             (let [sent (send x)]
               (swap! a (fn [_] (receive-value d/*domain-sio* (:bytes sent)
                                               {:check? true :digest-trust :trusted}))))
             :else
             (swap! a (fn [_] x)))
       a)))
