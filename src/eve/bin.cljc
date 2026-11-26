(ns eve.bin
  "THE BINARY: eve's run of bytes as an eve value of its own (doc/flat/BIN.md; FORMAT-V1 §3's bytes scalar, tag 0x28).

   A binary is a VIEW where its run lies — a flat chunk (eve.flat-io's chunk: the JVM's byte[], JavaScript's
   Uint8Array, ValueScript's B1 binary) or a block of an eve domain (a heap slab or an mmap'd disk domain, read through
   the slab's node reads). Decoding a 0x28 scalar never copies its run: the codec, the flat reader and a map's lookup
   answer a view of the bytes they already hold. Every host conforms through the byte seams it already plugs in
   (flat-io's u8 / bytes= / bytes-cmp, the slab's block reads); this namespace is the one place a binary's semantics
   live:

   - `count` the run's length; `nth` a byte SIGNED, -128..127, as Clojure reads a byte[]; `ubyte` the same unsigned.
   - `=` by content (another binary of the same bytes), never `=` to text or to a host byte array; `hash` the HAMT's
     key hash of its scalar bytes [EE DB 28][len varint][run] — a binary's flat bytes, key bytes and hash input are one
     string (FORMAT-V1 §3, §8).
   - `compare` unsigned lexicographic, a shorter run first on a common prefix (flat-io's bytes-cmp order).
   - `slice` another view of the same bytes, no copy.
   - host byte arrays only at the edges: `of-bytes` (in, one copy) and `->bytes` (out, one copy). A host byte array
     written to eve still encodes as the 0x28 scalar.
   - a view must not outlive its window (binary-native §2): a view of a domain's block read under a pin carries the
     read's cell (`pinned`); one that escapes is copied once (`own`); a view of a chunk is already owned (the host's
     GC keeps the chunk).

   eve.codec and eve.flat.chunk load below this namespace (eve.deftype's macros load them), so they reach it through
   eve.deftype-proto.serialize's binary slot, which this namespace fills at load (register-bin!)."
  (:refer-clojure :exclude [aget aset alength unchecked-byte compare])
  (:require [eve.host :refer [aget aset alength unchecked-byte field]]
            [eve.encoding :as enc]
            [eve.flat-io :as fio]
            [eve.hamt-util :as hu]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.deftype-proto.macros :as eve]
            [eve.type-readers]))

(declare bin= bin-hash bin-hex ubyte)

;; c: the flat-io chunk the run lies in, or nil — then the run lies in block blk of the domain whose slab I/O is sio,
;; at byte off of the block. off / n: the run's first byte and its length. pin: the read's cell that keeps the block's
;; version alive (eve.view), or nil.
(eve/host-deftype Bin [c sio blk off n pin]
  clojure.lang.Counted
  (count [_] n)
  clojure.lang.Indexed
  (nth [this i]
    (let [u (ubyte this i)] (if (< u 128) u (- u 256))))
  (nth [this i nf]
    (if (and (integer? i) (<= 0 i) (< i n))
      (let [u (ubyte this i)] (if (< u 128) u (- u 256)))
      nf))
  clojure.lang.Seqable
  (seq [this] (when (pos? n) (map (fn [i] (let [u (ubyte this i)] (if (< u 128) u (- u 256)))) (range n))))
  clojure.lang.IHashEq
  (hasheq [this] (bin-hash this))
  java.lang.Object
  (toString [this] (str "#eve/bytes \"" (bin-hex this) "\""))
  (equals [this o] (bin= this o))
  (hashCode [this] (bin-hash this))
  IPrintWithWriter
  (-pr-writer [this w _opts] (enc/write! w (str "#eve/bytes \"" (bin-hex this) "\""))))

(defn bin?
  "x is an eve binary."
  [x]
  (instance? Bin x))

(defn- run-u8
  "The unsigned byte at absolute offset i of b's source."
  [^Bin b i]
  (let [c (field b c)]
    (if (some? c)
      (fio/u8 c i)
      (alloc/node-u8 (field b sio) (field b blk) i))))

(defn ubyte
  "The byte at index i of binary b, unsigned 0..255 (`nth` reads it signed)."
  [^Bin b i]
  (if (and (integer? i) (<= 0 i) (< i (field b n)))
    (run-u8 b (+ (field b off) i))
    (enc/throw-index-out-of-bounds (str "Index " i " out of bounds for a binary of " (field b n) " bytes"))))

(defn view
  "The binary of the n bytes at off of flat-io chunk c (the host's chunk over its byte array: `fio/chunk`) — no copy."
  [c off n]
  (Bin. c nil 0 off n nil))

(defn view-bytes
  "The binary of the n bytes at off of host byte array bs — a view of bs, no copy (the caller hands bs over: the codec's
   own decode buffer, a chunk's bytes)."
  [bs off n]
  (view (fio/chunk bs) off n))

(defn slab-view
  "The binary of the n bytes at byte off of block blk of the domain whose slab I/O is sio — no copy. Valid while the
   block's version is: under the read's pin (`pinned`), or inside the swap that read it."
  [sio blk off n]
  (Bin. nil sio blk off n nil))

(defn slab-view?
  "b is a binary viewing a domain's block (not a chunk)."
  [b]
  (and (bin? b) (nil? (field ^Bin b c))))

(defn pin-of
  "The read's cell b holds, or nil."
  [^Bin b]
  (field b pin))

(defn pinned
  "b holding the read's cell (eve.view), so the block it views is reclaimed after no reader; a chunk view as it is."
  [^Bin b cell]
  (if (slab-view? b) (Bin. nil (field b sio) (field b blk) (field b off) (field b n) cell) b))

(def ^:private host-bytes-type
  ;; the host's byte array type, from eve.encoding's own constructor (as the byte-array codec names it)
  (type (enc/make-bytes 0)))

(defn- host-bytes? [x] (and (some? x) (identical? host-bytes-type (type x))))

(defn- copy-run
  "A fresh host byte array of the n bytes of host byte array bs from off (eve.encoding's make-bytes and arraycopy: every
   host's encoding has them)."
  [bs off n]
  (let [out (enc/make-bytes n)] (enc/arraycopy bs off out 0 n) out))

(defn ->bytes
  "A fresh host byte array of binary b's bytes (the edge out: one copy). A chunk whose bytes are not the host's byte
   array (ValueScript's chunk is a B1 binary) is read through flat-io, byte by byte."
  [^Bin b]
  (let [n (field b n) off (field b off) c (field b c)]
    (if (some? c)
      (let [hb (fio/chunk-bytes c)]
        (if (host-bytes? hb)
          (copy-run hb off n)
          (let [#?(:clj ^bytes out :default out) (enc/make-bytes n)]
            (loop [i 0] (when (< i n) (aset out i (unchecked-byte (fio/u8 c (+ off i)))) (recur (inc i))))
            out)))
      (alloc/node-bytes (field b sio) (field b blk) off n))))

(defn of-bytes
  "The binary of host byte array bs's bytes (the edge in: one copy, so a later write to bs is not seen)."
  [#?(:clj ^bytes bs :default bs)]
  (let [n (alength bs)]
    (view-bytes (copy-run bs 0 n) 0 n)))

(defn own
  "b as a value that outlives every read: a chunk view is itself; a view of a domain's block is copied once."
  [^Bin b]
  (if (slab-view? b) (view-bytes (->bytes b) 0 (field b n)) b))

(defn run
  "[host-bytes off] of binary b's run: a chunk view's own host bytes (no copy), else its bytes copied once — what a
   writer copies the run from."
  [^Bin b]
  (let [c (field b c) hb (when (some? c) (fio/chunk-bytes c))]
    (if (host-bytes? hb) [hb (field b off)] [(->bytes b) 0])))

(defn slice
  "The binary of bytes [from, to) of b — another view of the same bytes, no copy."
  ([^Bin b from] (slice b from (field b n)))
  ([^Bin b from to]
   (when-not (and (<= 0 from) (<= from to) (<= to (field b n)))
     (enc/throw-index-out-of-bounds (str "slice [" from ", " to ") out of bounds for a binary of " (field b n) " bytes")))
   (Bin. (field b c) (field b sio) (field b blk) (+ (field b off) from) (- to from) (field b pin))))

(defn- varint-len [n] (loop [n n k 1] (if (< n 0x80) k (recur (bit-shift-right n 7) (inc k)))))

(defn scalar-bytes
  "Binary b's eve bytes: [EE DB 28][len varint][run] — its value bytes, key bytes and hash input (FORMAT-V1 §3)."
  [^Bin b]
  (let [n (field b n) k (varint-len n) #?(:clj ^bytes out :default out) (enc/make-bytes (+ 3 k n))
        r (run b)]
    (aset out 0 (unchecked-byte 0xEE)) (aset out 1 (unchecked-byte 0xDB)) (aset out 2 (unchecked-byte 0x28))
    (loop [v n i 3]
      (if (< v 0x80)
        (aset out i (unchecked-byte v))
        (do (aset out i (unchecked-byte (bit-or 0x80 (bit-and v 0x7F)))) (recur (bit-shift-right v 7) (inc i)))))
    (enc/arraycopy (nth r 0) (nth r 1) out (+ 3 k) n)
    out))

(defn- bin-hash [b] (hu/portable-hash-bytes (scalar-bytes b)))

(defn- bin-hex [^Bin b]
  (let [hx "0123456789abcdef"]
    (apply str (map (fn [i] (let [u (ubyte b i)] (str (nth hx (quot u 16)) (nth hx (mod u 16)))))
                    (range (field b n))))))

(defn- bin= [^Bin a o]
  (and (bin? o)
       (== (field a n) (field ^Bin o n))
       (let [ca (field a c) co (field ^Bin o c) n (field a n)]
         (if (and (some? ca) (some? co))
           (boolean (fio/bytes= ca (field a off) n (fio/chunk-bytes co) (field ^Bin o off)))
           (loop [i 0]
             (cond (>= i n) true
                   (== (run-u8 a (+ (field a off) i)) (run-u8 o (+ (field ^Bin o off) i))) (recur (inc i))
                   :else false))))))

(defn compare
  "-1 / 0 / 1: binary a against binary b, unsigned lexicographic, a shorter run first on a common prefix."
  [^Bin a ^Bin b]
  (let [na (field a n) nb (field b n) ca (field a c) cb (field b c)]
    (if (and (some? ca) (some? cb))
      (fio/bytes-cmp ca (field a off) na (fio/chunk-bytes cb) (field b off) nb)
      (let [m (min na nb)]
        (loop [i 0]
          (if (< i m)
            (let [x (run-u8 a (+ (field a off) i)) y (run-u8 b (+ (field b off) i))]
              (cond (< x y) -1 (> x y) 1 :else (recur (inc i))))
            (cond (< na nb) -1 (> na nb) 1 :else 0)))))))

;; ── THE LINE VIEW (TATWD, perf-B 2026-11-26): a binary's lines as views of its own bytes ──
;; Lines end as java.io.BufferedReader's readLine ends them: at \n, at \r\n (one terminator), at a lone \r; the bytes
;; after the last terminator are the last line; a terminator at the very end adds no empty line. Each line is a
;; `slice` of b — no copy, the same window and pin as b — so a reduce over the lines allocates one view a line and
;; nothing else (no string, no seq cell, no lazy thunk). The seq face is a chunked seq of 32 lines a chunk, for the
;; observers that ask for a seq. Text is decoded by the caller (eve.encoding), never here: bytes stay the binary.

(defn- line-at
  "The line of b spanning absolute offsets [s, e): a view sharing b's window and pin."
  [^Bin b s e]
  (Bin. (field ^Bin b c) (field ^Bin b sio) (field ^Bin b blk) s (- e s) (field ^Bin b pin)))

;; The scan reads b's run ONCE (`run`: a chunk view's own host bytes, no copy; a domain block's bytes copied once) and
;; walks the host byte array directly — no per-byte call through the window (run-u8's chunk-or-block branch). A line is
;; still a slice of b itself, so it keeps b's window and pin, whatever the scan read. MEASURED before (JVM, 500,000
;; lines of ~11.5 B, per-thread allocated bytes): a per-byte run-u8 scan cost 38,793 B and 7.4 us a line.

(defn- u8-at
  "The unsigned byte at index i of host byte array hb."
  [#?(:clj ^bytes hb :default hb) #?(:clj ^long i :default i)]
  (bit-and (aget hb i) 0xFF))

(defn- line-end
  "The run index of the first terminator byte (\\n or \\r) at or after k (k, lim: run indexes), or lim."
  #?(:clj ^long [^bytes hb ^long k ^long lim]
     :default [hb k lim])
  (loop [k k]
    (if (< k lim)
      (let [u (u8-at hb k)] (if (or (== u 10) (== u 13)) k (recur (inc k))))
      lim)))

(defn- line-next
  "The run index just past the terminator at t (< lim): \\r\\n is one terminator."
  #?(:clj ^long [^bytes hb ^long t ^long lim]
     :default [hb t lim])
  (if (and (== (u8-at hb t) 13) (< (inc t) lim) (== (u8-at hb (inc t)) 10)) (+ t 2) (inc t)))

(defn- reduce-lines
  "f over b's lines from init; a reduced answer ends the walk with its value."
  [^Bin b f init]
  (let [r (run b) hb (nth r 0) base (long (nth r 1))
        lim (+ base (long (field ^Bin b n))) d (- (long (field ^Bin b off)) base)]   ;; d: a run index's offset in b's source
    (loop [s base acc init]
      (if (< s lim)
        (let [t (line-end hb s lim)
              x (f acc (line-at b (+ s d) (+ t d)))]
          (cond (reduced? x) @x
                (< t lim) (recur (line-next hb t lim) x)
                :else x))
        acc))))

(defn- lines-seq
  "The lines of b from run index s (of b's run hb; d its offset in b's source) as a chunked seq, 32 lines a chunk."
  [^Bin b hb d s lim]
  (lazy-seq
    (when (< s lim)
      (let [cb (chunk-buffer 32)
            s2 (loop [s s k 0]
                 (if (and (< s lim) (< k 32))
                   (let [t (line-end hb s lim)]
                     (chunk-append cb (line-at b (+ s d) (+ t d)))
                     (recur (if (< t lim) (line-next hb t lim) lim) (inc k)))
                   s))]
        (chunk-cons (chunk cb) (lines-seq b hb d s2 lim))))))

(eve/host-deftype Lines [b]
  clojure.lang.IReduce
  (reduce [_ f]
    (let [r (run b) hb (nth r 0) base (nth r 1)
          lim (+ base (field ^Bin b n)) d (- (field ^Bin b off) base)]
      (if (< base lim)
        (let [t (line-end hb base lim)]
          (if (< t lim)
            (reduce-lines (slice b (- (line-next hb t lim) base)) f (line-at b (+ base d) (+ t d)))
            (line-at b (+ base d) (+ t d))))
        (f))))
  (reduce [_ f init] (reduce-lines b f init))
  clojure.lang.Seqable
  (seq [_]
    (let [r (run b) hb (nth r 0) base (nth r 1)]
      (seq (lines-seq b hb (- (field ^Bin b off) base) base (+ base (field ^Bin b n)))))))

(defn lines
  "The lines of binary b, each a binary viewing b's own bytes (no copy). `reduce` / `transduce` walk b once, one view a
   line; `seq` answers 32 lines a chunk. Terminators as java.io.BufferedReader's readLine: \\n, \\r\\n, a lone \\r."
  [b]
  (Lines. b))

;; the slot eve.codec and eve.flat.chunk read (they load below this namespace)
(ser/register-bin! {:bin? bin? :view-bytes view-bytes :scalar-bytes scalar-bytes :run run})
