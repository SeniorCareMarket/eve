;; Shared pieces of the static-Eve research probes (research code, not product code).
;;   BumpSio      — a slab I/O over ONE byte array: alloc bumps the tail, free does nothing.
;;   RecSio       — a slab I/O that records the size each block was requested at (over a real domain).
;;   image!       — the relocating block copier: every block a value reaches, copied once into one byte array, its
;;                  pointers rewritten to positions in that array; with the block table {offset,size} and the
;;                  relocation table {field position, owner, target} a consumer needs to place it without planning.
;;   ingest!      — an image fed into a slab domain: per-block allocation, or one run claimed per size class.
(ns static-probe.lib
  (:require [eve.atom :as ea]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.mem :as mem]
            [eve.hamt-util :as hu]
            [eve.map] [eve.vec] [eve.set] [eve.list])
  (:import [java.util Arrays HashMap ArrayDeque]
           [eve.deftype_proto.alloc JvmSlabCtx]))

(set! *warn-on-reflection* false)
(set! *unchecked-math* false)

;; ---------------------------------------------------------------------------------------------------------------
;; timing
;; ---------------------------------------------------------------------------------------------------------------

(defmacro ms [& body]
  `(let [t0# (System/nanoTime) r# (do ~@body)] [r# (/ (- (System/nanoTime) t0#) 1e6)]))

(defn bench
  "Run f warm times unmeasured, then n times measured: {:min ms :med ms :r last result}."
  ([f] (bench 3 7 f))
  ([warm n f]
   (dotimes [_ warm] (f))
   (let [ts (double-array n)
         r (loop [i 0 r nil]
             (if (== i n) r
                 (let [t0 (System/nanoTime) r (f)]
                   (aset ts i (/ (- (System/nanoTime) t0) 1e6))
                   (recur (inc i) r))))]
     (Arrays/sort ts)
     {:min (aget ts 0) :med (aget ts (quot n 2)) :r r})))

;; ---------------------------------------------------------------------------------------------------------------
;; little-endian words in a byte array
;; ---------------------------------------------------------------------------------------------------------------

(defn i32 ^long [^bytes a ^long i]
  (let [i (int i)]
    (long (unchecked-int
           (bit-or (bit-and (long (aget a i)) 0xFF)
                   (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 1))) 0xFF) 8)
                   (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 2))) 0xFF) 16)
                   (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 3))) 0xFF) 24))))))

(defn put-i32! [^bytes a ^long i ^long v]
  (let [i (int i)]
    (aset a i (unchecked-byte v))
    (aset a (unchecked-add-int i 1) (unchecked-byte (bit-shift-right v 8)))
    (aset a (unchecked-add-int i 2) (unchecked-byte (bit-shift-right v 16)))
    (aset a (unchecked-add-int i 3) (unchecked-byte (bit-shift-right v 24)))
    nil))

(defn u8 ^long [^bytes a ^long i] (bit-and (long (aget a (int i))) 0xFF))

;; ---------------------------------------------------------------------------------------------------------------
;; BumpSio
;; ---------------------------------------------------------------------------------------------------------------

(definterface IBump
  (^bytes buf [])
  (^long tail [])
  (^long nalloc [])
  (^long nfree [])
  (^long sizeOf [^long off]))

(deftype BumpSio [^:unsynchronized-mutable ^bytes b
                  ^:unsynchronized-mutable ^long t
                  ^:unsynchronized-mutable ^long na
                  ^:unsynchronized-mutable ^long nf
                  ^:unsynchronized-mutable ^ints offs     ;; ascending block offsets, or nil: sizes not recorded
                  ^:unsynchronized-mutable ^ints szs
                  frozen?]
  IBump
  (buf [_] b)
  (tail [_] t)
  (nalloc [_] na)
  (nfree [_] nf)
  (sizeOf [_ off]
    (if (nil? offs) -1
        (let [i (Arrays/binarySearch offs 0 (int na) (int off))]
          (if (neg? i) -1 (long (aget szs i))))))
  alloc/ISlabIO
  (-sio-read-u8 [_ o f] (bit-and (aget b (int (+ (long o) (long f)))) 0xFF))
  (-sio-write-u8! [_ o f v] (aset b (int (+ (long o) (long f))) (unchecked-byte v)))
  (-sio-read-u16 [_ o f]
    (let [i (int (+ (long o) (long f)))]
      (bit-or (bit-and (aget b i) 0xFF) (bit-shift-left (bit-and (aget b (inc i)) 0xFF) 8))))
  (-sio-write-u16! [_ o f v]
    (let [i (int (+ (long o) (long f)))]
      (aset b i (unchecked-byte v))
      (aset b (inc i) (unchecked-byte (bit-shift-right (long v) 8)))))
  (-sio-read-i32 [_ o f] (i32 b (+ (long o) (long f))))
  (-sio-write-i32! [_ o f v] (put-i32! b (+ (long o) (long f)) (long v)))
  (-sio-cas-i32! [_ o f e dv]
    (let [p (+ (long o) (long f)) cur (i32 b p)]
      (when (== cur (long e)) (put-i32! b p (long dv)))
      cur))
  (-sio-read-bytes [_ o f n]
    (let [i (int (+ (long o) (long f)))]
      (Arrays/copyOfRange b i (int (+ i (long n))))))
  (-sio-write-bytes! [_ o f src]
    (System/arraycopy ^bytes src 0 b (int (+ (long o) (long f))) (alength ^bytes src)))
  (-sio-alloc! [_ n]
    (when frozen? (throw (ex-info "static view: allocation in a frozen buffer" {})))
    (let [n (long n)
          off t
          end (+ off (bit-and (+ n 3) (bit-not 3)))]
      (when (> end (alength b))
        (set! b (Arrays/copyOf b (int (max end (* 2 (alength b)))))))
      (when offs
        (when (== na (alength offs))
          (set! offs (Arrays/copyOf offs (int (* 2 na))))
          (set! szs (Arrays/copyOf szs (int (* 2 na)))))
        (aset offs (int na) (int off))
        (aset szs (int na) (int n)))
      (set! t end)
      (set! na (inc na))
      off))
  (-sio-free! [_ o] (set! nf (inc nf)) true)
  (-sio-copy-block! [_ dd df s sf n]
    (System/arraycopy b (int (+ (long s) (long sf))) b (int (+ (long dd) (long df))) (int n))))

(defn bump-sio
  "A fresh append-only buffer. record? keeps each block's requested size."
  ([] (bump-sio 4096 true))
  ([cap] (bump-sio cap true))
  ([cap record?]
   (BumpSio. (byte-array cap) 8 0 0 (when record? (int-array 1024)) (when record? (int-array 1024)) false)))

(defn view-sio
  "A frozen view over the first len bytes of bs (no copy): reads only."
  [^bytes bs len]
  (BumpSio. bs len 0 0 nil nil true))

;; ---------------------------------------------------------------------------------------------------------------
;; RecSio — a real domain, with each block's requested size remembered
;; ---------------------------------------------------------------------------------------------------------------

(definterface IRec (^long sizeOf [^long off]) (inner []) (^long nalloc []) (^long nfree []))

(deftype RecSio [inner ^HashMap sizes ^:unsynchronized-mutable ^long na ^:unsynchronized-mutable ^long nf]
  IRec
  (sizeOf [_ off] (let [s (.get sizes (bit-and (long off) 0xFFFFFFFF))] (if s (long s) -1)))
  (inner [_] inner)
  (nalloc [_] na)
  (nfree [_] nf)
  alloc/ISlabIO
  (-sio-read-u8 [_ o f] (alloc/-sio-read-u8 inner o f))
  (-sio-write-u8! [_ o f v] (alloc/-sio-write-u8! inner o f v))
  (-sio-read-u16 [_ o f] (alloc/-sio-read-u16 inner o f))
  (-sio-write-u16! [_ o f v] (alloc/-sio-write-u16! inner o f v))
  (-sio-read-i32 [_ o f] (alloc/-sio-read-i32 inner o f))
  (-sio-write-i32! [_ o f v] (alloc/-sio-write-i32! inner o f v))
  (-sio-cas-i32! [_ o f e dv] (alloc/-sio-cas-i32! inner o f e dv))
  (-sio-read-bytes [_ o f n] (alloc/-sio-read-bytes inner o f n))
  (-sio-write-bytes! [_ o f src] (alloc/-sio-write-bytes! inner o f src))
  (-sio-alloc! [_ n]
    (let [off (alloc/-sio-alloc! inner n)]
      (.put sizes (bit-and (long off) 0xFFFFFFFF) (long n))
      (set! na (inc na))
      off))
  (-sio-free! [_ o] (set! nf (inc nf)) (alloc/-sio-free! inner o))
  (-sio-copy-block! [_ dd df s sf n] (alloc/-sio-copy-block! inner dd df s sf n)))

(defn rec-sio [inner] (RecSio. inner (HashMap.) 0 0))

(defn size-of
  "The size the block at off was requested at, from the sio that allocated it."
  ^long [sio ^long off]
  (cond (instance? BumpSio sio) (.sizeOf ^IBump sio off)
        (instance? RecSio sio) (.sizeOf ^IRec sio off)
        :else -1))

;; ---------------------------------------------------------------------------------------------------------------
;; building and reopening
;; ---------------------------------------------------------------------------------------------------------------

(defn reopen
  "The eve value whose header block is at off, read through sio."
  [sio off]
  (let [t (ser/read-header-tag sio off)
        ctor (ser/get-header-constructor t)]
    (ctor sio off)))

(defn build!
  "Plain collection v written into sio by the host's direct writer (what swap! uses for a plain root): its header
   offset."
  [sio v]
  (codec/write-collection! (cond (map? v) :map (set? v) :set (vector? v) :vec :else :list) sio v))

(defn slab-domain
  "A heap slab domain. caps: {class-idx bytes}; a class left out gets 1 MB (eve's default), so a large build grows
   it by replacement."
  ([] (alloc/heap-domain-sio! {}))
  ([caps] (alloc/heap-domain-sio! {:capacities caps})))

(defn class-of ^long [^long n]
  (cond (<= n 32) 0 (<= n 64) 1 (<= n 128) 2 (<= n 256) 3 (<= n 512) 4 (<= n 1024) 5 :else 6))

(def class-bytes [32 64 128 256 512 1024])

;; ---------------------------------------------------------------------------------------------------------------
;; the relocating copier
;; ---------------------------------------------------------------------------------------------------------------
;; Block kinds, as eve.retire reads them (src/eve/retire.cljc): 0 a value's header (by its type-id byte), 1 a map's
;; HAMT node, 2 a set's HAMT node, 3 a value block [len][EVE bytes], 4 a list cell, 5 a vector trie node (shift,
;; base, toff), 6 a vector tail (n live slots).

(defn- pointer-tag? [^long tag]
  (or (<= 0x10 tag 0x13) (<= 0x1C tag 0x1F) (<= 0x40 tag 0xEC)))

(defn- inline-ptr?
  "Do the bytes at p of a start an eve pointer [0xEE 0xDB tag off:i32]?"
  [^bytes a ^long p]
  (let [w (i32 a p)]
    (and (== 0xDBEE (bit-and w 0xFFFF)) (pointer-tag? (bit-and (bit-shift-right w 16) 0xFF)))))

(defn- popcount ^long [^long x] (long (Integer/bitCount (unchecked-int x))))

(defn- trie-live ^long [^long toff ^long base ^long sh]
  (let [n (- toff base)]
    (if (<= n 0) 0 (let [w (bit-shift-left 1 sh)] (min 32 (quot (+ n (dec w)) w))))))

(defn- slot-of
  "The first probe slot of key k in a table of mask m (a multiplicative mix)."
  ^long [^long k ^long m]
  (let [h (unchecked-multiply k -7046029254386353131)]
    (bit-and (bit-xor h (unsigned-bit-shift-right h 32)) m)))

(defrecord Image [^bytes img ^long len ^long root
                  ^ints boff ^ints bsz ^long nblocks        ;; the block table: offset and size of block i
                  ^ints rpos ^ints rown ^ints rtgt ^long nrel]) ;; the relocation table: field position, owner, target

(defn image!
  "Every block the value at root (in src) reaches, copied once into one byte array, pointers rewritten to positions
   in it. size-fn: (fn [old-off]) → the block's byte size."
  [src ^long root size-fn]
  (let [img (volatile! (byte-array (* 1024 1024)))
        tail (long-array 1)                       ;; [0] = image tail
        ;; open-addressing map: old offset (u32, +1 so 0 is empty) -> block ordinal
        cap0 (int 65536)
        keys (volatile! (long-array cap0))
        vals (volatile! (int-array cap0))
        nkeys (long-array 1)
        boff (volatile! (int-array 4096))
        bsz (volatile! (int-array 4096))
        nb (long-array 1)
        rpos (volatile! (int-array 4096))
        rown (volatile! (int-array 4096))
        rtgt (volatile! (int-array 4096))
        nr (long-array 1)
        stack (ArrayDeque.)
        lookup (fn ^long [^long k]
                 (let [^longs ks @keys ^ints vs @vals m (dec (alength ks))]
                   (loop [i (slot-of k m)]
                     (let [x (aget ks i)]
                       (cond (zero? x) -1
                             (== x k) (long (aget vs i))
                             :else (recur (bit-and (inc i) m)))))))
        insert! (fn [^long k ^long v]
                  (when (> (* 2 (aget nkeys 0)) (alength ^longs @keys))
                    (let [^longs ok @keys ^ints ov @vals n2 (* 2 (alength ok))
                          nk (long-array n2) nv (int-array n2) m (dec n2)]
                      (dotimes [j (alength ok)]
                        (let [x (aget ok j)]
                          (when-not (zero? x)
                            (loop [i (slot-of x m)]
                              (if (zero? (aget nk i))
                                (do (aset nk i x) (aset nv i (aget ov j)))
                                (recur (bit-and (inc i) m)))))))
                      (vreset! keys nk) (vreset! vals nv)))
                  (let [^longs ks @keys ^ints vs @vals m (dec (alength ks))]
                    (loop [i (slot-of k m)]
                      (if (zero? (aget ks i))
                        (do (aset ks i k) (aset vs i (int v)) (aset nkeys 0 (inc (aget nkeys 0))))
                        (recur (bit-and (inc i) m))))))
        add-rel! (fn [^long pos ^long own ^long tgt]
                   (let [n (aget nr 0)]
                     (when (== n (alength ^ints @rpos))
                       (vswap! rpos #(Arrays/copyOf ^ints % (int (* 2 n))))
                       (vswap! rown #(Arrays/copyOf ^ints % (int (* 2 n))))
                       (vswap! rtgt #(Arrays/copyOf ^ints % (int (* 2 n)))))
                     (aset ^ints @rpos n (int pos)) (aset ^ints @rown n (int own)) (aset ^ints @rtgt n (int tgt))
                     (aset nr 0 (inc n))))
        ;; push: [old-off kind p1 p2 p3 patch-pos owner]
        push! (fn [old kind p1 p2 p3 patch owner]
                (.push stack (doto (long-array 7)
                               (aset 0 (long old)) (aset 1 (long kind)) (aset 2 (long p1)) (aset 3 (long p2))
                               (aset 4 (long p3)) (aset 5 (long patch)) (aset 6 (long owner)))))]
    (push! root 0 0 0 0 -1 -1)
    (loop []
      (when-not (.isEmpty stack)
        (let [^longs it (.pop stack)
              old (aget it 0) kind (aget it 1) patch (aget it 5) owner (aget it 6)
              key (inc (bit-and old 0xFFFFFFFF))
              ord0 (lookup key)]
          (if (>= ord0 0)
            ;; a block already placed (shared): point at it
            (do (put-i32! @img patch (aget ^ints @boff ord0))
                (add-rel! patch owner ord0))
            (let [size (long (size-fn old))
                  _ (when (neg? size) (throw (ex-info "image!: a block of unknown size" {:off old :kind kind})))
                  base (aget tail 0)
                  end (+ base (bit-and (+ size 3) (bit-not 3)))
                  _ (when (> end (alength ^bytes @img))
                      (vswap! img #(Arrays/copyOf ^bytes % (int (max end (* 2 (alength ^bytes %)))))))
                  ^bytes a @img
                  ord (aget nb 0)]
              ;; copy the block's bytes
              (let [ha (alloc/node-heap-array src old)]
                (if ha
                  (System/arraycopy ^bytes ha (int (alloc/node-base src old)) a (int base) (int size))
                  (if (instance? BumpSio src)
                    (System/arraycopy (.buf ^IBump src) (int old) a (int base) (int size))
                    (let [bs (alloc/-sio-read-bytes src old 0 size)]
                      (System/arraycopy ^bytes bs 0 a (int base) (int size))))))
              (aset tail 0 end)
              (when (== ord (alength ^ints @boff))
                (vswap! boff #(Arrays/copyOf ^ints % (int (* 2 ord))))
                (vswap! bsz #(Arrays/copyOf ^ints % (int (* 2 ord)))))
              (aset ^ints @boff ord (int base))
              (aset ^ints @bsz ord (int size))
              (aset nb 0 (inc ord))
              (insert! key ord)
              (when (>= patch 0)
                (put-i32! a patch base)
                (add-rel! patch owner ord))
              ;; the block's own pointers, read from its copy
              (case (int kind)
                0 (let [t (u8 a base)]
                    (case (int t)
                      0xED (let [r (i32 a (+ base 8))] (when-not (== r -1) (push! r 1 0 0 0 (+ base 8) ord)))
                      0xEE (let [r (i32 a (+ base 8))] (when-not (== r -1) (push! r 2 0 0 0 (+ base 8) ord)))
                      0x12 (let [cnt (i32 a (+ base 4)) sh (i32 a (+ base 8)) root (i32 a (+ base 12))
                                 tl-off (i32 a (+ base 16)) tl (i32 a (+ base 20))
                                 toff (if (< cnt 32) 0 (bit-shift-left (bit-shift-right (dec cnt) 5) 5))]
                             (when (and (pos? toff) (not (== root -1))) (push! root 5 sh 0 toff (+ base 12) ord))
                             (when-not (== tl-off -1) (push! tl-off 6 tl 0 0 (+ base 16) ord)))
                      0x13 (let [h (i32 a (+ base 8))] (when-not (== h -1) (push! h 4 0 0 0 (+ base 8) ord)))
                      (0x01 0x1D 0x1E) nil
                      0x1F (let [n (i32 a (+ base 4)) pb (bit-and (+ 8 (* 2 n) 3) (bit-not 3))]
                             (dotimes [i n]
                               (let [k (u8 a (+ base 8 i)) p (+ base pb (* 4 i))]
                                 (cond (== k 2) (push! (i32 a p) 0 0 0 0 p ord)
                                       (== k 4) (push! (i32 a p) 3 0 0 0 p ord)))))
                      (let [layout (ser/deftype-layout src old t)]
                        (when (vector? layout)
                          (doseq [[foff k] layout]
                            (let [p (+ base (long foff)) r (i32 a p)]
                              (when-not (== r -1) (push! r (if (= k :ref) 0 3) 0 0 0 p ord))))))))
                (1 2) (let [t (u8 a base)]
                        (cond
                          (== t 1)
                          (let [dbm (i32 a (+ base 4)) nbm (i32 a (+ base 8))
                                nc (popcount nbm) nd (popcount dbm)]
                            (dotimes [i nc]
                              (let [p (+ base 12 (* 4 i)) c (i32 a p)]
                                (when-not (== c -1) (push! c kind 0 0 0 p ord))))
                            (when (== kind 1)
                              (loop [j 0 pos (+ base 12 (* 4 nc) (* 4 nd))]
                                (when (< j nd)
                                  (let [klen (i32 a pos) vpos (+ pos 4 klen) vlen (i32 a vpos)]
                                    (when (and (== vlen 7) (inline-ptr? a (+ vpos 4)))
                                      (push! (i32 a (+ vpos 7)) 0 0 0 0 (+ vpos 7) ord))
                                    (recur (inc j) (+ vpos 4 vlen)))))))
                          (and (== kind 1) (== t 3))
                          (let [cnt (u8 a (+ base 1))]
                            (loop [j 0 pos (+ base 8)]
                              (when (< j cnt)
                                (let [klen (i32 a pos) vpos (+ pos 4 klen) vlen (i32 a vpos)]
                                  (when (and (== vlen 7) (inline-ptr? a (+ vpos 4)))
                                    (push! (i32 a (+ vpos 7)) 0 0 0 0 (+ vpos 7) ord))
                                  (recur (inc j) (+ vpos 4 vlen))))))))
                3 (when (and (== 7 (i32 a base)) (inline-ptr? a (+ base 4)))
                    (push! (i32 a (+ base 7)) 0 0 0 0 (+ base 7) ord))
                4 (let [nx (i32 a base)]
                    (when-not (== nx -1) (push! nx 4 0 0 0 base ord))
                    (when (and (== 7 (i32 a (+ base 4))) (inline-ptr? a (+ base 8)))
                      (push! (i32 a (+ base 11)) 0 0 0 0 (+ base 11) ord)))
                5 (let [sh (aget it 2) b0 (aget it 3) toff (aget it 4)
                        n (trie-live toff b0 sh) w (bit-shift-left 1 sh)]
                    (dotimes [i n]
                      (let [p (+ base (* 4 i)) c (i32 a p)]
                        (when-not (== c -1)
                          (if (zero? sh)
                            (push! c 3 0 0 0 p ord)
                            (push! c 5 (- sh 5) (+ b0 (* i w)) toff p ord))))))
                6 (let [n (aget it 2)]
                    (dotimes [i n]
                      (let [p (+ base (* 4 i)) c (i32 a p)]
                        (when-not (== c -1) (push! c 3 0 0 0 p ord))))))))
          (recur))))
    (->Image @img (aget tail 0) (aget ^ints @boff 0)
             @boff @bsz (aget nb 0) @rpos @rown @rtgt (aget nr 0))))

(defn image-view
  "The value an image holds, read in place through a frozen view."
  [^Image im]
  (reopen (view-sio (:img im) (:len im)) (:root im)))

;; ---------------------------------------------------------------------------------------------------------------
;; ingest: an image into a slab domain
;; ---------------------------------------------------------------------------------------------------------------

(defn claim-run!
  "n consecutive blocks of bitmap class ci of heap domain ctx, claimed a bitmap word at a time: the first block's
   index. (Research: a whole free word is taken by one CAS 0 -> ones; the run starts at the cursor's next word.)"
  ^long [^JvmSlabCtx ctx ^long ci ^long n]
  (let [region (aget ^objects (.-regions ctx) ci)
        bm-off (long (aget ^ints (.-bitmap_offsets ctx) ci))
        total (long (aget ^ints (.-total_blocks ctx) ci))
        cursor (long (mem/-load-i32 region d/SLAB_HDR_ALLOC_CURSOR))
        start (bit-and (+ cursor 31) (bit-not 31))]
    (when (> (+ start n) total)
      (throw (ex-info "claim-run!: the class is too small for the run (size the domain)" {:class ci :n n :total total})))
    (loop [bit start left n]
      (when (pos? left)
        (let [take (min 32 left)
              mask (if (== take 32) -1 (dec (bit-shift-left 1 take)))
              woff (+ bm-off (* 4 (unsigned-bit-shift-right bit 5)))
              old (mem/-cas-i32! region woff 0 (unchecked-int mask))]
          (when-not (zero? old) (throw (ex-info "claim-run!: the run is not free" {:class ci :bit bit})))
          (recur (+ bit 32) (- left take)))))
    (mem/-sub-i32! region d/SLAB_HDR_FREE_COUNT (int n))
    (mem/-store-i32! region d/SLAB_HDR_ALLOC_CURSOR (int (mod (+ start n) total)))
    start))

(defn ingest!
  "Image im placed in heap slab domain ctx: the new root's slab offset. mode :each allocates block by block
   (-sio-alloc!); mode :runs claims one run per size class (claim-run!), larger blocks one by one."
  [^JvmSlabCtx ctx ^Image im mode]
  (let [n (int (:nblocks im))
        ^ints boff (:boff im) ^ints bsz (:bsz im) ^bytes img (:img im)
        nw (int-array n)]
    (case mode
      :each (dotimes [i n] (aset nw i (unchecked-int (alloc/-sio-alloc! ctx (aget bsz i)))))
      :runs (let [cnt (long-array 7)]
              (dotimes [i n] (let [c (class-of (aget bsz i))] (aset cnt c (inc (aget cnt c)))))
              (let [nxt (long-array 7)]
                (dotimes [c 6] (when (pos? (aget cnt c)) (aset nxt c (claim-run! ctx c (aget cnt c)))))
                (dotimes [i n]
                  (let [c (class-of (aget bsz i))]
                    (if (== c 6)
                      (aset nw i (unchecked-int (alloc/-sio-alloc! ctx (aget bsz i))))
                      (do (aset nw i (unchecked-int (alloc/encode-slab-offset c (aget nxt c))))
                          (aset nxt c (inc (aget nxt c))))))))))
    ;; the bytes
    (dotimes [i n]
      (let [o (long (aget nw i))]
        (System/arraycopy img (aget boff i) ^bytes (alloc/node-heap-array ctx o) (int (alloc/node-base ctx o)) (aget bsz i))))
    ;; the pointers
    (let [^ints rpos (:rpos im) ^ints rown (:rown im) ^ints rtgt (:rtgt im)]
      (dotimes [j (int (:nrel im))]
        (let [own (aget rown j)
              o (long (aget nw own))]
          (put-i32! (alloc/node-heap-array ctx o)
                    (+ (long (alloc/node-base ctx o)) (- (aget rpos j) (aget boff own)))
                    (long (aget nw (aget rtgt j)))))))
    (long (aget nw 0))))

(defn class-census
  "{class-bytes count} over an image's blocks (:overflow past 1024), and the bytes they take class-padded."
  [^Image im]
  (let [^ints bsz (:bsz im) n (int (:nblocks im)) cnt (long-array 7) pad (long-array 1)]
    (dotimes [i n]
      (let [s (aget bsz i) c (class-of s)]
        (aset cnt c (inc (aget cnt c)))
        (aset pad 0 (+ (aget pad 0) (if (== c 6) s (long (nth class-bytes c)))))))
    {:census (into (sorted-map) (keep (fn [c] (when (pos? (aget cnt c)) [(if (== c 6) 99999 (nth class-bytes c)) (aget cnt c)])) (range 7)))
     :padded (aget pad 0)}))

(defn caps-for
  "Heap-domain capacities ({class bytes}) that hold an image's blocks slack times over, so nothing grows."
  [^Image im slack]
  (let [{:keys [census]} (class-census im)]
    (into {} (map (fn [c] [c (max (* 64 1024) (long (* slack (get census (nth class-bytes c) 0) (nth class-bytes c))))]) (range 6)))))
