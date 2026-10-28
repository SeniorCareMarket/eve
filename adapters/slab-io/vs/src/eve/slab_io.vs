(ns eve.slab-io
  "eve's memory-level seam (adapters/README.md, slab-io; HOST-CONTRACT §12) on ValueScript: plain functions over the
   program's own linear memory, read and written with the program-runtime mmap arms — no protocol dispatch, no
   IMemRegion layer.

   A domain's slab I/O is the address of its record (`domain`), 64 bytes in a window of linear memory: for slab class
   c (0-6), at 8c, the address its blocks start at (its region's address and the class's data offset) and at 8c+4 its
   block size; at 56, the domain's index among the slab contexts registered here (eve.deftype-proto.alloc's
   JvmSlabCtx, whose allocator `alloc!` and `free!` call directly). A slab-qualified offset [class:3 | block:29] is an
   address in two loads, as docs/eve-on-vs/experiments/direct-slab measured. A region is an address too (eve.region,
   adapters/region/vs). Any other slab I/O or region — eve.deftype-proto.alloc's LoggingSlabIO, a test's wrapper —
   answers through eve's protocols, as on every host.

   An aligned i32 or i64 takes the atomic arm and an unaligned one the plain arm, as eve's JVM region does
   (bb-load-i32): the HAMT writes node fields at any byte offset, and the atomic arm traps unaligned.

   The fused primitives (bytes-equal-at?, bitmap-find-free, copy-block!) are one call over memory each. The names
   eve.deftype-proto.alloc/… are called by their full names: alloc requires this namespace.")

;; ---------------------------------------------------------------------------
;; Domains: records in one window, the slab contexts behind them
;; ---------------------------------------------------------------------------

(def ^:private RECORD 64)
(def ^:private BOOK 1048576)
;; word 0: the bytes of records claimed; records from byte 64. 16,383 domains: a heap domain holds 7 MB of slab
;; regions, so the program's memory is gone first.
(def ^:private book (mmap/reserve! BOOK))
(def ^:private domains (atom []))   ;; [ctx record] by a record's index

(defn- claim-record! []
  (loop []
    (let [o (mmap/load-i32 book) n (+ o RECORD)]
      (when (> (+ n RECORD) BOOK)
        (throw (ex-info "eve.slab-io: no room for another domain record" {:domains (quot o RECORD)})))
      (if (= o (mmap/cas-i32! book o n)) (+ book RECORD o) (recur)))))

(defn- sync-record!
  "the record h from ctx's regions, data offsets and block sizes"
  [ctx h]
  (let [regions (.-regions ctx) data (.-data-offsets ctx) sizes (.-block-sizes ctx)]
    (dotimes [c (min 7 (alength regions))]
      (let [r (aget regions c)]
        (when r
          (mmap/i32! (+ h (* 8 c)) (+ r (aget data c)))
          (mmap/i32! (+ h (* 8 c) 4) (aget sizes c)))))
    h))

(defn domain
  "The slab I/O of slab context ctx: its record's address."
  [ctx]
  (let [h (claim-record!)
        i (loop [] (let [v @domains] (if (compare-and-set! domains v (conj v [ctx h])) (count v) (recur))))]
    (mmap/i32! (+ h 56) i)
    (sync-record! ctx h)))

(defn- ctx-of [h] (nth (nth @domains (mmap/i32 (+ h 56))) 0))

(defn domain-ctx
  "The slab context behind a domain's slab I/O."
  [sio]
  (if (number? sio) (ctx-of sio) sio))

(defn regions-changed!
  "ctx's regions were re-mapped (a class grew): its records follow."
  [ctx]
  (doseq [[c h] @domains] (when (identical? c ctx) (sync-record! ctx h)))
  nil)

;; ---------------------------------------------------------------------------
;; A domain's slab I/O
;; ---------------------------------------------------------------------------

(defn- addr [h off f]
  (let [e (+ h (* 8 (unsigned-bit-shift-right (bit-and off 0xFFFFFFFF) 29)))]
    (+ (mmap/i32 e) (* (bit-and off 0x1FFFFFFF) (mmap/i32 (+ e 4))) f)))

(defn read-u8 [s off f]
  (if (number? s) (mmap/u8 (addr s off f)) (eve.deftype-proto.alloc/-sio-read-u8 s off f)))

(defn read-u16 [s off f]
  (if (number? s) (mmap/u16 (addr s off f)) (eve.deftype-proto.alloc/-sio-read-u16 s off f)))

(defn read-i32 [s off f]
  (if (number? s)
    (let [a (addr s off f)] (if (zero? (bit-and a 3)) (mmap/load-i32 a) (mmap/i32 a)))
    (eve.deftype-proto.alloc/-sio-read-i32 s off f)))

(defn read-bytes [s off f len]
  (if (number? s)
    (let [a (addr s off f) b (byte-array len)]
      (loop [i 0] (when (< i len) (aset b i (unchecked-byte (mmap/u8 (+ a i)))) (recur (inc i))))
      b)
    (eve.deftype-proto.alloc/-sio-read-bytes s off f len)))

(defn write-u8! [s off f v]
  (if (number? s)
    (do (mmap/u8! (addr s off f) (bit-and v 0xFF)) nil)
    (eve.deftype-proto.alloc/-sio-write-u8! s off f v)))

(defn write-u16! [s off f v]
  (if (number? s)
    (do (mmap/u16! (addr s off f) (bit-and v 0xFFFF)) nil)
    (eve.deftype-proto.alloc/-sio-write-u16! s off f v)))

(defn write-i32! [s off f v]
  (if (number? s)
    (let [a (addr s off f)]
      (if (zero? (bit-and a 3)) (mmap/store-i32! a (unchecked-int v)) (mmap/i32! a (unchecked-int v)))
      nil)
    (eve.deftype-proto.alloc/-sio-write-i32! s off f v)))

(defn write-bytes! [s off f src]
  (if (number? s)
    (let [a (addr s off f) n (alength src)]
      (loop [i 0] (when (< i n) (mmap/u8! (+ a i) (bit-and (aget src i) 0xFF)) (recur (inc i))))
      nil)
    (eve.deftype-proto.alloc/-sio-write-bytes! s off f src)))

(defn cas-i32! [s off f expected desired]
  (if (number? s)
    (mmap/cas-i32! (addr s off f) (unchecked-int expected) (unchecked-int desired))
    (eve.deftype-proto.alloc/-sio-cas-i32! s off f expected desired)))

(defn copy-block! [s dst df src sf len]
  (if (number? s)
    (do (mmap/copy! (addr s dst df) (addr s src sf) len) nil)
    (eve.deftype-proto.alloc/-sio-copy-block! s dst df src sf len)))

(defn alloc! [s size-bytes]
  (if (number? s)
    (eve.deftype-proto.alloc/ctx-alloc! (ctx-of s) size-bytes)
    (eve.deftype-proto.alloc/-sio-alloc! s size-bytes)))

(defn free! [s slab-off]
  (if (number? s)
    (eve.deftype-proto.alloc/ctx-free! (ctx-of s) slab-off)
    (eve.deftype-proto.alloc/-sio-free! s slab-off)))

(defn mem-region [s off f]
  (eve.deftype-proto.alloc/-sio-mem-region (domain-ctx s) off f))

(defn bytes-equal-at?
  "Do the (alength b) bytes at slab-off + field-off equal byte array b: compared in place."
  [s off f b]
  (if (number? s)
    (let [a (addr s off f) n (alength b)]
      (loop [i 0]
        (cond (>= i n) true
              (= (mmap/u8 (+ a i)) (bit-and (aget b i) 0xFF)) (recur (inc i))
              :else false)))
    (eve.encoding/bytes= (eve.deftype-proto.alloc/-sio-read-bytes s off f (alength b)) b)))

;; ---------------------------------------------------------------------------
;; A region: the slab allocators' header words and bitmaps
;; ---------------------------------------------------------------------------

(defn region-load-i32 [r off]
  (if (number? r)
    (let [a (+ r off)] (if (zero? (bit-and a 3)) (mmap/load-i32 a) (mmap/i32 a)))
    (eve.mem/-load-i32 r off)))

(defn region-store-i32! [r off v]
  (if (number? r)
    (let [a (+ r off)]
      (if (zero? (bit-and a 3)) (mmap/store-i32! a (unchecked-int v)) (mmap/i32! a (unchecked-int v)))
      nil)
    (eve.mem/-store-i32! r off v)))

(defn region-cas-i32! [r off expected desired]
  (if (number? r)
    (mmap/cas-i32! (+ r off) (unchecked-int expected) (unchecked-int desired))
    (eve.mem/-cas-i32! r off expected desired)))

(defn region-add-i32! [r off delta]
  (if (number? r) (mmap/add-i32! (+ r off) delta) (eve.mem/-add-i32! r off delta)))

(defn region-sub-i32! [r off delta]
  (if (number? r) (mmap/add-i32! (+ r off) (- delta)) (eve.mem/-sub-i32! r off delta)))

(defn region-load-i64 [r off]
  (if (number? r)
    (let [a (+ r off)] (if (zero? (bit-and a 7)) (mmap/load-i64 a) (mmap/i64 a)))
    (eve.mem/-load-i64 r off)))

(defn region-store-i64! [r off v]
  (if (number? r)
    (let [a (+ r off)] (if (zero? (bit-and a 7)) (mmap/store-i64! a v) (mmap/i64! a v)) nil)
    (eve.mem/-store-i64! r off v)))

(defn region-cas-i64! [r off expected desired]
  (if (number? r) (mmap/cas-i64! (+ r off) expected desired) (eve.mem/-cas-i64! r off expected desired)))

(defn- low-bit
  "the index of the lowest set bit of m, a nonzero 32-bit pattern"
  [m]
  (loop [m m n 0]
    (cond (zero? (bit-and m 0xFFFF)) (recur (unsigned-bit-shift-right m 16) (+ n 16))
          (zero? (bit-and m 0xFF)) (recur (unsigned-bit-shift-right m 8) (+ n 8))
          (zero? (bit-and m 0xF)) (recur (unsigned-bit-shift-right m 4) (+ n 4))
          (zero? (bit-and m 3)) (recur (unsigned-bit-shift-right m 2) (+ n 2))
          (zero? (bit-and m 1)) (inc n)
          :else n)))

(defn bitmap-find-free
  "The first clear bit at or after start-bit of the total-bits bitmap at bm-off in r, or -1: one scan over its words."
  [r bm-off total-bits start-bit]
  (if (number? r)
    (let [base (+ r bm-off) words (unsigned-bit-shift-right (+ total-bits 31) 5)]
      (loop [w (unsigned-bit-shift-right start-bit 5) b (bit-and start-bit 31)]
        (if (>= w words)
          -1
          (let [free (bit-and (bit-not (mmap/load-i32 (+ base (* 4 w)))) 0xFFFFFFFF)
                m    (if (pos? b) (bit-and free (bit-and (bit-shift-left 0xFFFFFFFF b) 0xFFFFFFFF)) free)]
            (if (zero? m)
              (recur (inc w) 0)
              (let [bit (+ (* 32 w) (low-bit m))] (if (< bit total-bits) bit -1)))))))
    (eve.mem/imr-bitmap-find-free r bm-off total-bits start-bit)))

(defn bitmap-alloc-cas!
  "bit set 0 -> 1: true, or false when it was set already."
  [r bm-off bit]
  (if (number? r)
    (let [a (+ r bm-off (* 4 (unsigned-bit-shift-right bit 5))) mask (bit-shift-left 1 (bit-and bit 31))]
      (loop []
        (let [old (mmap/load-i32 a)]
          (cond (not (zero? (bit-and old mask))) false
                (= old (mmap/cas-i32! a old (unchecked-int (bit-or old mask)))) true
                :else (recur)))))
    (eve.mem/imr-bitmap-alloc-cas! r bm-off bit)))

(defn bitmap-free!
  "bit cleared 1 -> 0: true, or false when it was clear already."
  [r bm-off bit]
  (if (number? r)
    (let [a (+ r bm-off (* 4 (unsigned-bit-shift-right bit 5))) mask (bit-shift-left 1 (bit-and bit 31))]
      (loop []
        (let [old (mmap/load-i32 a)]
          (if (= old (mmap/cas-i32! a old (unchecked-int (bit-and old (bit-not mask)))))
            (not (zero? (bit-and old mask)))
            (recur)))))
    (eve.mem/imr-bitmap-free! r bm-off bit)))
