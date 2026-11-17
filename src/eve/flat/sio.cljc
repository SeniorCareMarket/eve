(ns eve.flat.sio
  "A slab I/O over ONE host byte array — the flat form's address space
   (FORMAT-V1.md §1, §9). An offset is a byte position in the array
   (4-aligned, < 2^29, so the class bits a slab offset would carry decode
   as 0). Two modes:

   - a BUILDER: alloc! bumps the tail (the array grows by replacement),
     free! does nothing, writes are allowed, each block's requested size
     is recorded. Log-appending physics: an update appends, an earlier
     (length, root) view never sees it.
   - a VIEW: frozen. Reads only — every mutator (alloc!, free!, write-*,
     cas!, copy-block!) throws a typed refusal, {:type ::frozen-view}.
     The research prototype's view only refused allocation, so a write
     through it silently landed (MEASUREMENTS M1); a product view has no
     write path at all.

   The reads are plain loads: the hot path of a flat is this sio's read,
   not a region hop (Z1.7). They go through eve.flat-io (doc/flat/FLAT-IO.md;
   flat lanes U2a): the sio keeps the chunk over its array (c, fio/chunk of
   b, made again whenever a builder's array is replaced), and reads u8, u16
   and i32 from it."
  (:require [eve.deftype-proto.alloc :as alloc]
            [eve.flat-io :as fio])
  #?(:clj (:import [java.util Arrays])))

(defprotocol IBlockSize
  "The byte size of the block at an offset, as the sio that holds it knows it:
   a builder's recorded request, a view's block table. -1 when unknown."
  (-block-size [sio off]))

(defprotocol IFlatSioState
  "A FlatSio's raw state. Field access (.-b, .-t) does NOT work on this
   deftype from outside its own methods in this codebase's compilation:
   the mutable fields compile package-private, so .- falls back to
   reflection and throws \"No matching field found\" (measured at the Z1
   pin, 2026-11-12 — builder-length threw on every call). All outside
   readers go through these two methods."
  (-sio-bytes [sio])
  (-sio-tail [sio]))

(defn- frozen! [op]
  (throw (ex-info (str "eve.flat: a flat view is read-only; " op " refused")
                  {:type ::frozen-view :op op})))

(defn- put-i32-at! [#?(:clj ^bytes a :cljs a) ^long i ^long v]
  (let [i (int i)]
    (aset a i (unchecked-byte v))
    (aset a (inc i) (unchecked-byte (bit-shift-right v 8)))
    (aset a (+ i 2) (unchecked-byte (bit-shift-right v 16)))
    (aset a (+ i 3) (unchecked-byte (bit-shift-right v 24)))
    nil))

(deftype FlatSio [^{:unsynchronized-mutable true #?@(:clj [:tag bytes])} b   ;; the byte array (a builder's grows by replacement)
                    ^{:unsynchronized-mutable true} c   ;; the chunk over b (fio/chunk b): what the reads take
                    ^{:unsynchronized-mutable true} t   ;; the tail: one past the last written byte
                    ^{:unsynchronized-mutable true} na  ;; blocks allocated (builder) / recorded (view)
                    ^{:unsynchronized-mutable true #?@(:clj [:tag ints])} offs ;; ^ints ascending block offsets, or nil
                    ^{:unsynchronized-mutable true #?@(:clj [:tag ints])} szs  ;; ^ints the size of the block at offs[i]
                    frozen?]
  IFlatSioState
  (-sio-bytes [_] b)
  (-sio-tail [_] t)

  IBlockSize
  (-block-size [_ off]
    (if (nil? offs)
      -1
      (let [i #?(:clj (Arrays/binarySearch offs 0 (int na) (int off))
                 ;; the same binary search over the JS int array (offs is
                 ;; ascending; na entries are live) — without it an opened
                 ;; image on CLJS answered -1 for every block size
                 :cljs (loop [lo 0 hi (dec na)]
                         (if (> lo hi)
                           -1
                           (let [mid (quot (+ lo hi) 2)
                                 mv (aget offs mid)]
                             (cond (== mv (long off)) mid
                                   (< mv (long off)) (recur (inc mid) hi)
                                   :else (recur lo (dec mid)))))))]
        (if (neg? i) -1 (long (aget szs i))))))

  alloc/ISlabIO
  (-sio-read-u8 [_ o f] (fio/u8 c (+ (long o) (long f))))
  (-sio-write-u8! [_ o f v]
    (when frozen? (frozen! "write-u8!"))
    (aset b (int (+ (long o) (long f))) (unchecked-byte v)))
  (-sio-read-u16 [_ o f] (fio/u16 c (+ (long o) (long f))))
  (-sio-write-u16! [_ o f v]
    (when frozen? (frozen! "write-u16!"))
    (let [i (int (+ (long o) (long f)))]
      (aset b i (unchecked-byte v))
      (aset b (inc i) (unchecked-byte (bit-shift-right (long v) 8)))))
  (-sio-read-i32 [_ o f] (fio/i32 c (+ (long o) (long f))))
  (-sio-write-i32! [_ o f v]
    (when frozen? (frozen! "write-i32!"))
    (put-i32-at! b (+ (long o) (long f)) (long v)))
  (-sio-cas-i32! [_ o f e dv]
    (when frozen? (frozen! "cas-i32!"))
    ;; a builder is thread-confined: compare-and-write is the whole semantics
    (let [p (+ (long o) (long f)) cur (fio/i32 c p)]
      (when (== cur (long e)) (put-i32-at! b p (long dv)))
      cur))
  (-sio-read-bytes [_ o f n]
    (let [i (int (+ (long o) (long f)))]
      #?(:clj (Arrays/copyOfRange b i (int (+ i (long n))))
         :cljs (.slice b i (+ i (long n))))))
  (-sio-write-bytes! [_ o f src]
    (when frozen? (frozen! "write-bytes!"))
    #?(:clj (System/arraycopy ^bytes src 0 b (int (+ (long o) (long f))) (alength ^bytes src))
       :cljs (.set b src (int (+ (long o) (long f))))))
  (-sio-alloc! [_ n]
    (when frozen? (frozen! "alloc!"))
    (let [n (long n)
          off t
          end (+ off (bit-and (+ n 3) (bit-not 3)))]
      (when (> end (alength b))
        (set! b #?(:clj (Arrays/copyOf b (int (max end (* 2 (alength b)))))
                   :cljs (let [nb (js/Uint8Array. (max end (* 2 (alength b))))]
                           (.set nb b) nb)))
        (set! c (fio/chunk b)))
      (when offs
        (when (== na (alength offs))
          (set! offs #?(:clj (Arrays/copyOf offs (int (* 2 na)))
                        :cljs (let [no (js/Int32Array. (* 2 (alength offs)))]
                                (.set no offs) no)))
          (set! szs #?(:clj (Arrays/copyOf szs (int (* 2 na)))
                       :cljs (let [ns (js/Int32Array. (* 2 (alength szs)))]
                               (.set ns szs) ns))))
        (aset offs (int na) (int off))
        (aset szs (int na) (int n)))
      (set! t end)
      (set! na (inc na))
      off))
  (-sio-free! [_ o]
    (when frozen? (frozen! "free!"))
    true) ;; a builder frees nothing: superseded blocks stay as garbage until a detach compacts
  (-sio-copy-block! [_ dd df s sf n]
    (when frozen? (frozen! "copy-block!"))
    #?(:clj (System/arraycopy b (int (+ (long s) (long sf))) b (int (+ (long dd) (long df))) (int n))
       :cljs (.set b (.subarray b (int (+ (long s) (long sf))) (int (+ (long s) (long sf) (long n))))
                   (int (+ (long dd) (long df)))))))

(defn builder-sio
  "A fresh append-only builder over one growing byte array. Blocks are
   4-aligned from offset 8 (offset 0 is never a block, as in the probes)."
  ([] (builder-sio 4096))
  ([cap]
   (let [b #?(:clj (byte-array cap) :cljs (js/Uint8Array. cap))]
     (FlatSio. b (fio/chunk b) 8 0
               #?(:clj (int-array 1024) :cljs (js/Int32Array. 1024))
               #?(:clj (int-array 1024) :cljs (js/Int32Array. 1024))
               false))))

(defn view-sio
  "A frozen view over the first len bytes of bs (no copy). boff/bsz, when
   given, are a block table (ascending offsets and their sizes) — the
   image's — so the view can answer block sizes."
  ([bs len] (view-sio bs len nil nil))
  ([bs len boff bsz]
   (FlatSio. bs (fio/chunk bs) len (if (nil? boff) 0 #?(:clj (alength ^ints boff) :default (alength boff))) boff bsz true)))

(defn builder-bytes
  "A builder's current byte array (it grows by replacement; call again
   after building). Only the first (builder-length) bytes are written."
  [sio]
  (-sio-bytes sio))

(defn builder-length
  "The written length of a builder: one past the last allocated byte."
  ^long [sio]
  (-sio-tail sio))

(defn block-size
  "The size of the block at off as sio knows it, or -1."
  ^long [sio ^long off]
  (if (satisfies? IBlockSize sio) (-block-size sio off) -1))
