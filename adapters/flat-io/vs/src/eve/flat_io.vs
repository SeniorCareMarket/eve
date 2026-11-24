;; adapters/flat-io/vs/src/eve/flat_io.vs — eve.flat-io, the ValueScript root (doc/flat/FLAT-IO.md is the contract;
;; adapters/flat-io/test/eve/flat_io_conformance_test.cljc the test every root answers). Written against eve 8e46085's
;; portable root (adapters/flat-io/portable/src/eve/flat_io.cljc), whose bytes it answers byte for byte.
;;
;; A CHUNK IS A BINARY (VS's B1 value: a flat, immutable run of bytes, = by its bytes, never = a string). The ruling it
;; serves (operator, 2026-11-24): "the scalar binary blob that gets written in to the disk atom persistent blob store
;; should be the same as we store in flat data and on disk." A chunk, the bytes scalar 0x28 inside it, an atom's value
;; and a file are the same run of bytes: `slurp-bytes` answers a binary that IS a chunk, `spit` writes one back
;; verbatim, and a binary moves between them by copy (VS binary-native.md §7.1.2). So on VS:
;;   (chunk bs)        a binary as itself; a byte-array (eve.encoding's make-bytes) or a string, a binary of its bytes
;;   (chunk-bytes c)   the binary — VS's host bytes are binaries (§7.1.2: "on VS, eve's host bytes become B1's binary")
;;   reads             through binary-ubyte, unsigned, in place: no copy of the stored bytes
;;   the writer        a growable byte-array (aset), sealed by buf-chunk into a binary of exactly n bytes
;;   tuple / col       the host's persistent vector built from the reads (as the portable root; a view is later work)
;; Every multi-byte read is little-endian; i64 is a VS long (64-bit two's complement, as the JVM's).
;;
;; CONFORMANCE (MEASURED 2026-11-24 3:5x AM ET, VS probe image ff28d5568771bf97, `vs run` of this file renamed
;; fioprobe.flat-io — a program may not require eve.* — over a copy of adapters/encoding/vs without its eve.host require):
;; 112 of 112 rows of adapters/flat-io/test/eve/flat_io_conformance_test.cljc ported as plain checks, 0 failed —
;; `chunks`, `offsets-narrow-and-wide`, `varints` (all nine edges, varint / -v / -next / -len), `utf8-of-every-width`,
;; `i64-past-2-53` (all seven), `floats-nan-and-negative-zero` (f64 and f32, -0.0, NaN, Inf), `bytes-equal-and-compare`,
;; three `key-cmp` rows, `the-writer` (width 2 and 4, patch, seek, align, i64 / f64, varints, growth past 256 to 5,200)
;; and u8 / i8 `tuples`. Not run: `golden-chunks-every-offset` (eve's fixture files), the other tuple kinds and
;; `key-cmp`'s full table, and the conformance test itself under VS (it needs this root on VS's classpath: eve's commit
;; plus the eve family's re-mint).
;;
;; A binary eve READS (the 0x28 scalar) is eve.bin's value (doc/flat/BIN.md), a view over the chunk its run lies in:
;; on VS that chunk is a B1 binary, so eve.bin's reads are this root's u8 / bytes= / bytes-cmp over it. eve.encoding
;; (adapters/encoding/vs) keeps the byte-array for the codec's own scratch buffers; a byte-array handed to `chunk` is
;; copied once into a binary (B6 retires the byte-array emulation).
(ns eve.flat-io
  (:refer-clojure :exclude [chunk tuple])
  (:require [eve.encoding :as enc]))

;; ── chunks ───────────────────────────────────────────────────────────────────────────────────────────────────────

(defn chunk
  "The chunk over bs: a binary as itself; a byte-array or a string, the binary of its bytes (one copy)."
  [bs]
  (if (binary? bs) bs (binary bs)))

(defn chunk-bytes "The host bytes of chunk c: the binary itself." [c] c)
(defn chunk-len "The byte length of chunk c." [c] (count c))

;; ── reads, in place ──────────────────────────────────────────────────────────────────────────────────────────────

(defn u8 [c i] (binary-ubyte c i))
(defn u16 [c i] (bit-or (u8 c i) (bit-shift-left (u8 c (inc i)) 8)))
(defn u32 [c i] (bit-or (u16 c i) (bit-shift-left (u16 c (+ i 2)) 16)))
(defn i32 [c i] (let [v (u32 c i)] (if (>= v 2147483648) (- v 4294967296) v)))
(defn i64 [c i] (bit-or (u32 c i) (bit-shift-left (u32 c (+ i 4)) 32)))
(defn f64 [c i] (enc/long-bits->double (i64 c i)))
(defn f32
  "The f32 at i, widened to a double: its four bytes through eve.encoding's read-f32-le."
  [c i]
  (let [b (byte-array 4)]
    (dotimes [k 4] (aset b k (unchecked-byte (u8 c (+ i k)))))
    (enc/read-f32-le b 0)))

(defn varint
  "[value next-pos] — the unsigned LEB128 at i."
  [c i]
  (loop [i i sh 0 v 0]
    (let [x (u8 c i)]
      (if (< x 128)
        [(bit-or v (bit-shift-left x sh)) (inc i)]
        (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn varint-v
  "The unsigned LEB128 value at i (varint's first element, no vector)."
  [c i]
  (loop [i i sh 0 v 0]
    (let [x (u8 c i)]
      (if (< x 128)
        (bit-or v (bit-shift-left x sh))
        (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn varint-next
  "The position after the unsigned LEB128 at i (varint's second element)."
  [c i]
  (loop [i i] (if (< (u8 c i) 128) (inc i) (recur (inc i)))))

(defn varint-len [v] (loop [v v n 1] (if (< v 128) n (recur (unsigned-bit-shift-right v 7) (inc n)))))

(defn- hb
  "Byte i of host bytes bs, unsigned: a binary's through binary-ubyte, a byte-array's masked."
  [bs i]
  (if (binary? bs) (binary-ubyte bs i) (bit-and (aget bs i) 255)))

(defn- hlen [bs] (if (binary? bs) (count bs) (alength bs)))

(defn utf8
  "The string of the n utf-8 bytes at off (nothing rendered: eve.encoding's utf8-string over their copy)."
  [c off n]
  (let [b (byte-array n)]
    (dotimes [k n] (aset b k (unchecked-byte (u8 c (+ off k)))))
    (enc/utf8-string b 0 n)))

(defn bytes=
  "Are the n bytes of chunk c at off equal to host bytes bs from boff? False when bs holds fewer than n bytes from boff."
  [c off n bs boff]
  (and (<= (+ boff n) (hlen bs))
       (loop [i 0]
         (cond (== i n) true
               (== (u8 c (+ off i)) (hb bs (+ boff i))) (recur (inc i))
               :else false))))

(defn bytes-cmp
  "-1 / 0 / 1: the n bytes of chunk c at off against the bn host bytes of bs from boff, unsigned and lexicographically,
   a shorter prefix first."
  [c off n bs boff bn]
  (let [m (if (< n bn) n bn)]
    (loop [i 0]
      (if (< i m)
        (let [x (u8 c (+ off i)) y (hb bs (+ boff i))]
          (cond (< x y) -1 (> x y) 1 :else (recur (inc i))))
        (cond (< n bn) -1 (> n bn) 1 :else 0)))))

(defn- len-cmp
  "-1 / 0 / 1: string lengths a and b (same length class) as their encoded length bytes compare — one byte below 256,
   else four little-endian bytes compared as bytes."
  [a b]
  (if (< a 256)
    (cond (< a b) -1 (> a b) 1 :else 0)
    (loop [j 0]
      (if (== j 4)
        0
        (let [x (bit-and (bit-shift-right a (* 8 j)) 255)
              y (bit-and (bit-shift-right b (* 8 j)) 255)]
          (cond (< x y) -1 (> x y) 1 :else (recur (inc j))))))))

(defn key-cmp
  "-1 / 0 / 1: the string key stored as utf-8 at [off, off+n) of chunk c against host string k, in eve's key-byte
   order (eve.codec/encode-key: below 256 utf-8 bytes [0x05][len][bytes], from 256 [0x06][len u32 LE][bytes])."
  [c off n k]
  (let [kb (enc/utf8-bytes k)
        kn (alength kb)
        sc (if (< n 256) 5 6)
        kc (if (< kn 256) 5 6)]
    (cond (< sc kc) -1
          (> sc kc) 1
          :else (let [lc (len-cmp n kn)]
                  (if (zero? lc) (bytes-cmp c off n kb 0 kn) lc)))))

;; ── the writer: FORMAT-V1's children-first writer, growable, one offset width ────────────────────────────────────
;; {:b (atom byte-array) :p (atom position) :width 2|4}; the byte-array grows by doubling, every byte past the
;; position is written before it is read (buf-align writes its zeros)

(defn buf
  "A growable writer whose offsets (buf-off) are width bytes, 2 or 4."
  [width]
  {:b (atom (byte-array 256)) :p (atom 0) :width width})

(defn- grow! [w need]
  (let [b @(:b w) n (alength b)]
    (when (> need n)
      (let [nb (byte-array (max need (* 2 n)))]
        (enc/arraycopy b 0 nb 0 n)
        (reset! (:b w) nb)))))

(defn buf-raw "The writer's host bytes so far (its whole buffer; buf-pos of them written)." [w] @(:b w))
(defn buf-pos "The write position." [w] @(:p w))
(defn buf-seek "Move the write position to p (inside what is written)." [w p] (reset! (:p w) p))
(defn buf-u8 [w v]
  (let [p @(:p w)]
    (grow! w (inc p))
    (aset @(:b w) p (unchecked-byte v))
    (reset! (:p w) (inc p))))
(defn buf-u16 [w v] (buf-u8 w v) (buf-u8 w (bit-shift-right v 8)))
(defn buf-u32 [w v] (buf-u16 w v) (buf-u16 w (bit-shift-right v 16)))
(defn buf-i64 [w v] (buf-u32 w v) (buf-u32 w (bit-shift-right v 32)))
(defn buf-f64 [w v] (buf-i64 w (enc/double->long-bits v)))
(defn buf-varint [w v]
  (loop [v v]
    (if (< v 128)
      (buf-u8 w v)
      (do (buf-u8 w (bit-or (bit-and v 127) 128)) (recur (unsigned-bit-shift-right v 7))))))
(defn buf-bytes
  "Append n bytes of host bytes src (a binary or a byte-array) from off."
  [w src off n]
  (let [p @(:p w)]
    (grow! w (+ p n))
    (let [b @(:b w)]
      (dotimes [i n] (aset b (+ p i) (unchecked-byte (hb src (+ off i))))))
    (reset! (:p w) (+ p n))))
(defn buf-off "Append an offset of the writer's width." [w v] (if (== (:width w) 2) (buf-u16 w v) (buf-u32 w v)))
(defn buf-patch-u32
  "Overwrite the u32 at at (the position stays)."
  [w at v]
  (let [b @(:b w)]
    (aset b at (unchecked-byte v))
    (aset b (+ at 1) (unchecked-byte (bit-shift-right v 8)))
    (aset b (+ at 2) (unchecked-byte (bit-shift-right v 16)))
    (aset b (+ at 3) (unchecked-byte (bit-shift-right v 24)))))
(defn buf-align
  "Zero bytes up to a multiple of a."
  [w a]
  (loop [] (when-not (zero? (rem (buf-pos w) a)) (buf-u8 w 0) (recur))))
(defn buf-chunk
  "The chunk of the writer's first n bytes: a binary of exactly those n bytes."
  [w n]
  (let [b @(:b w) out (byte-array n)]
    (enc/arraycopy b 0 out 0 n)
    (binary out)))

;; ── tuples and columns: the host's persistent vector built from the reads ────────────────────────────────────────

(defn- elem-size [k]
  (case (int k) (1 2 3) 1 (4 5) 2 (6 7 8) 4 (9 10 11) 8
    (throw (ex-info "eve.flat-io: not a dense column kind" {:type ::not-dense :kind k}))))

(defn- elem-at [c data-off k i]
  (let [at (+ data-off (* (elem-size k) i))]
    (case (int k)
      (1 3) (u8 c at)
      2 (let [v (u8 c at)] (if (>= v 128) (- v 256) v))
      4 (let [v (u16 c at)] (if (>= v 32768) (- v 65536) v))
      5 (u16 c at)
      6 (i32 c at)
      7 (u32 c at)
      8 (f32 c at)
      9 (f64 c at)
      (10 11) (i64 c at))))

(defn tuple
  "The n elements of column kind kind (FORMAT-V1 §5: 1 u8 · 2 i8 · 4 i16 · 5 u16 · 6 i32 · 7 u32 · 8 f32 · 9 f64 ·
   10 i64 · 11 u64) stored at data-off of chunk c, as the host's persistent vector."
  [c data-off n kind]
  (mapv (fn [i] (elem-at c data-off kind i)) (range n)))

(defn col "The n elements of the column of kind kind at data-off (as tuple)." [c data-off n kind] (tuple c data-off n kind))
