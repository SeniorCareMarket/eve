(ns eve.flat-io
  "The byte-level seam under eve's flat layer — the portable root (the inbuilt
   implementation; doc/flat/FLAT-IO.md is the contract, adapters/flat-io/test
   the conformance test every root answers).

   What this file is: eve.flat.chunk's byte layer, MOVED here (flat lanes,
   U2a, 2026-11-17) — the little-endian reads (ru8 … rf64 → u8 … f64), the
   unsigned LEB128 varint, the growable children-first writer (IBuf and its
   Buf deftypes) and the helpers those need, with every host arm they had:
   :clj (the JVM and jolt; bb, glojure and jank read it too, their own arms
   inside), :bb, :glj, :jank and :cljs. So every host writes and reads exactly
   the bytes it did before the seam. Added for the contract: varint-v /
   varint-next (the varint without its vector), utf8, bytes=, bytes-cmp,
   key-cmp (eve's key-byte order, in place), the chunk constructors, buf-chunk
   and buf-align, and tuple / col — here a plain persistent vector built from
   the reads (a fast root makes it a view).

   A chunk, on this root, IS the host byte array (a byte[] on the JVM-class
   hosts, a Uint8Array on ClojureScript): (chunk bs) is bs. Shared code never
   assumes that — it reaches the bytes through chunk-bytes — so a fast root
   may carry more (a DataView, a VS leaf). All offsets are absolute from the
   chunk's start; all multi-byte reads little-endian (FORMAT-V1 §2)."
  (:refer-clojure :exclude [chunk])
  (:require [eve.encoding :as enc]
            #?(:jolt [jolt.scheme :as scheme])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; reads (moved from chunk.cljc: ru8 … rf64, rvarint, varint-len, ba-copy-of)
;; The JVM reads a byte array with bit ops; ClojureScript reads a
;; Uint8Array (values already unsigned) with arithmetic — a u32 and a
;; varint do not fit CLJS's 32-bit bit ops — plus a DataView for the
;; float and i64 widths. Same bytes, host substrates.
;; ---------------------------------------------------------------------------

;; jolt: the little-endian reads and the writer's multi-byte puts as Chez's bytevector refs and setters on the byte
;; array's own bytevector (a jolt byte array is one), and the writer's IBuf a protocol (below). jolt's bit-shift-left
;; never open-codes (each shift masks with a 2^64 bignum and wraps, wrap64), and a definterface method is called by name
;; through jolt's interop dispatch: u32 was 110 ns and i64 254 ns a read, buf-u8 40 ns and buf-u32 270 ns a write
;; (2026-11-22 ET); flat chunk building is ~23% of eve.db.ds's runtime on jolt. The same values and bytes as the :clj
;; arms (adapters/flat-io/test).
#?(:jolt
   (do
     (scheme/eval-string
      "(begin
         (define (eve-fio-m v m) (if (fixnum? v) (fxand v m) (bitwise-and v m)))
         (define (eve-fio-u16 a i) (bytevector-u16-ref (jolt-array-vec a) i (endianness little)))
         (define (eve-fio-u32 a i) (bytevector-u32-ref (jolt-array-vec a) i (endianness little)))
         (define (eve-fio-i32 a i) (bytevector-s32-ref (jolt-array-vec a) i (endianness little)))
         (define (eve-fio-i64 a i) (bytevector-s64-ref (jolt-array-vec a) i (endianness little)))
         (define (eve-fio-f64 a i) (bytevector-ieee-double-ref (jolt-array-vec a) i (endianness little)))
         (define (eve-fio-put-u8! a i v) (bytevector-u8-set! (jolt-array-vec a) i (eve-fio-m v #xFF)))
         (define (eve-fio-put-u16! a i v) (bytevector-u16-set! (jolt-array-vec a) i (eve-fio-m v #xFFFF) (endianness little)))
         (define (eve-fio-put-u32! a i v)
           (bytevector-u32-set! (jolt-array-vec a) i (eve-fio-m v #xFFFFFFFF) (endianness little)))
         (define (eve-fio-put-i64! a i v)
           (if (fixnum? v)
               (bytevector-s64-set! (jolt-array-vec a) i v (endianness little))
               (bytevector-u64-set! (jolt-array-vec a) i (bitwise-and v #xFFFFFFFFFFFFFFFF) (endianness little))))
         (define (eve-fio-put-f64! a i v)
           (bytevector-ieee-double-set! (jolt-array-vec a) i (if (flonum? v) v (inexact v)) (endianness little)))
         #t)")
     (def ^:private u16* (scheme/proc "eve-fio-u16"))
     (def ^:private u32* (scheme/proc "eve-fio-u32"))
     (def ^:private i32* (scheme/proc "eve-fio-i32"))
     (def ^:private i64* (scheme/proc "eve-fio-i64"))
     (def ^:private f64* (scheme/proc "eve-fio-f64"))
     (def ^:private put-u8* (scheme/proc "eve-fio-put-u8!"))
     (def ^:private put-u16* (scheme/proc "eve-fio-put-u16!"))
     (def ^:private put-u32* (scheme/proc "eve-fio-put-u32!"))
     (def ^:private put-i64* (scheme/proc "eve-fio-put-i64!"))
     (def ^:private put-f64* (scheme/proc "eve-fio-put-f64!"))))

#?(:clj (do
(defn u8 ^long [^bytes c ^long i] (bit-and (long (aget c (int i))) 0xFF))
(defn u16 ^long [^bytes c ^long i] #?(:jolt (u16* c i) :default (bit-or (u8 c i) (bit-shift-left (u8 c (inc i)) 8))))
(defn u32 ^long [^bytes c ^long i] #?(:jolt (u32* c i) :default (bit-or (u16 c i) (bit-shift-left (u16 c (+ i 2)) 16))))
(defn i32 ^long [^bytes c ^long i] #?(:jolt (i32* c i) :default (long (unchecked-int (u32 c i)))))
(defn i64 ^long [^bytes c ^long i] #?(:jolt (i64* c i) :default (bit-or (u32 c i) (bit-shift-left (u32 c (+ i 4)) 32))))
(defn f64 ^double [^bytes c ^long i] #?(:jank (enc/long-bits->double (i64 c i)) :jolt (f64* c i) :default (Double/longBitsToDouble (i64 c i))))
(defn f32 ^double [^bytes c ^long i] #?(:jank (enc/int-bits->float (unchecked-int (u32 c i))) :default (Float/intBitsToFloat (unchecked-int (u32 c i)))))

(defn varint
  "[value next-pos] — unsigned LEB128."
  [^bytes c ^long i]
  (loop [i i sh 0 v 0]
    (let [x (u8 c i)]
      (if (< x 128) [(bit-or v (bit-shift-left x sh)) (inc i)]
          (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn varint-v
  "The unsigned LEB128 value at i (varint's first element, no vector)."
  ^long [^bytes c ^long i]
  (loop [i i sh 0 v 0]
    (let [x (u8 c i)]
      (if (< x 128) (bit-or v (bit-shift-left x sh))
          (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn varint-next
  "The position after the unsigned LEB128 at i (varint's second element)."
  ^long [^bytes c ^long i]
  (loop [i i] (if (< (u8 c i) 128) (inc i) (recur (inc i)))))

(defn varint-len ^long [^long v]
  (loop [v v n 1] (if (< v 128) n (recur (unsigned-bit-shift-right v 7) (inc n)))))

(defn- hb
  "Byte i of host byte array bs, unsigned (the in-place compares' other side)."
  ^long [^bytes bs ^long i]
  (bit-and (long (aget bs (int i))) 0xFF))

(defn- ba-copy-of
  "java.util.Arrays/copyOf's semantics (zero-padded grow, truncating
   shrink) spelled with the portable eve.encoding surface, so the
   writer's buffer growth reads the same on hosts with no
   java.util.Arrays (jank, ClojureCLR)."
  ^bytes [^bytes b ^long n]
  (let [nb (byte-array (int n))]
    (enc/arraycopy b 0 nb 0 (int (min (alength b) n)))
    nb))

(defn chunk "The chunk over host byte array bs: bs itself." [bs] bs)
(defn chunk-bytes "The host byte array of chunk c." ^bytes [c] c)
(defn chunk-len "The byte length of chunk c." ^long [^bytes c] (alength c))
(defn utf8 "The string of the n utf-8 bytes at off." [^bytes c off n] (enc/utf8-string c off n))
))

#?(:lpy (do
;; basilisp: the :clj arm's reads over its byte array (array('b'), signed, as
;; the JVM's), with Python's unbounded ints sign-converted where the JVM
;; wraps, and the float widths through eve.encoding (no Float/Double here)
(defn u8 [c i] (bit-and (aget c i) 0xFF))
(defn u16 [c i] (bit-or (u8 c i) (bit-shift-left (u8 c (inc i)) 8)))
(defn u32 [c i] (bit-or (u16 c i) (bit-shift-left (u16 c (+ i 2)) 16)))
(defn i32 [c i] (let [v (u32 c i)] (if (>= v 0x80000000) (- v 0x100000000) v)))
(defn i64 [c i]
  (let [v (bit-or (u32 c i) (bit-shift-left (u32 c (+ i 4)) 32))]
    (if (>= v 0x8000000000000000) (- v 0x10000000000000000) v)))
(defn f64 [c i] (enc/long-bits->double (i64 c i)))
(defn f32 [c i] (enc/int-bits->float (u32 c i)))

(defn varint
  "[value next-pos] — unsigned LEB128."
  [c i]
  (loop [i i sh 0 v 0]
    (let [x (u8 c i)]
      (if (< x 128) [(bit-or v (bit-shift-left x sh)) (inc i)]
          (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn varint-v
  "The unsigned LEB128 value at i (varint's first element, no vector)."
  [c i]
  (loop [i i sh 0 v 0]
    (let [x (u8 c i)]
      (if (< x 128) (bit-or v (bit-shift-left x sh))
          (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(defn varint-next
  "The position after the unsigned LEB128 at i (varint's second element)."
  [c i]
  (loop [i i] (if (< (u8 c i) 128) (inc i) (recur (inc i)))))

(defn varint-len [v]
  (loop [v v n 1] (if (< v 128) n (recur (bit-shift-right v 7) (inc n)))))

(defn- hb
  "Byte i of host byte array bs, unsigned (the in-place compares' other side)."
  [bs i]
  (bit-and (aget bs i) 0xFF))

(defn- ba-copy-of
  "java.util.Arrays/copyOf's semantics (zero-padded grow, truncating shrink)."
  [b n]
  (let [nb (byte-array n)]
    (enc/arraycopy b 0 nb 0 (min (alength b) n))
    nb))

(defn chunk "The chunk over host byte array bs: bs itself." [bs] bs)
(defn chunk-bytes "The host byte array of chunk c." [c] c)
(defn chunk-len "The byte length of chunk c." [c] (alength c))
(defn utf8 "The string of the n utf-8 bytes at off." [c off n] (enc/utf8-string c off n))
))

#?(:cljs (do
(defn u8 [^js/Uint8Array c i] (aget c i))
(defn u16 [^js/Uint8Array c i] (+ (aget c i) (* 256 (aget c (inc i)))))
(defn u32 [^js/Uint8Array c i]
  ;; arithmetic, not bit ops: a u32 does not fit a CLJS 32-bit bit-op
  (+ (u16 c i) (* 65536 (u16 c (+ i 2)))))
(defn i32
  "The i32 at i. `|0` (bit-or 0) is the wrap: cljs.core/unchecked-int is
   Math truncation (fix), which leaves a u32 past 2^31 positive — the
   moved ri32's spelling answered 4294967295 for FF FF FF FF (measured on
   Node, U2a ledger); the conformance test pins -1."
  [^js/Uint8Array c i] (bit-or (u32 c i) 0))

(defn- dv-of
  "A DataView over the whole of Uint8Array b (its buffer, its offset)."
  [^js/Uint8Array b]
  (js/DataView. (.-buffer b) (.-byteOffset b) (.-byteLength b)))

(defn i64
  "The i64 at i as a js/BigInt (exact past 2^53)."
  [c i]
  (.getBigInt64 (dv-of c) i true))
(defn f64 [c i] (.getFloat64 (dv-of c) i true))
(defn f32 [c i] (.getFloat32 (dv-of c) i true))

(defn varint
  "[value next-pos] — unsigned LEB128, accumulated arithmetically
   (CLJS bit ops are 32-bit). Chunk offsets stay under 2^32, counts
   under 2^53, so a JS number is exact."
  [^js/Uint8Array c i]
  (loop [i i mul 1 v 0]
    (let [x (aget c i)]
      (if (< x 128) [(+ v (* (bit-and x 127) mul)) (inc i)]
          (recur (inc i) (* mul 128) (+ v (* (bit-and x 127) mul)))))))

(defn varint-v
  "The unsigned LEB128 value at i (varint's first element, no vector)."
  [^js/Uint8Array c i]
  (loop [i i mul 1 v 0]
    (let [x (aget c i)]
      (if (< x 128) (+ v (* (bit-and x 127) mul))
          (recur (inc i) (* mul 128) (+ v (* (bit-and x 127) mul)))))))

(defn varint-next
  "The position after the unsigned LEB128 at i (varint's second element)."
  [^js/Uint8Array c i]
  (loop [i i] (if (< (aget c i) 128) (inc i) (recur (inc i)))))

(defn varint-len [v]
  (loop [v v n 1] (if (< v 128) n (recur (quot v 128) (inc n)))))

(defn- hb [^js/Uint8Array bs i] (aget bs i))

;; i64 math on this host runs in BigInt space: an i64 does not fit a
;; JS number. The writer's i64 takes either. Written without js*: nbb's
;; analyzer has no js* (measured: the flat load died "Unable to resolve
;; symbol: js*"), and every form below means the same in compiled
;; ClojureScript — the type tag is Object.prototype.toString's.
;; (js/typeof is not the portable spelling either: nbb resolves it to a
;; broken call.)
(defn- bigint? [x] (= "[object BigInt]" (.call (.-toString (.-prototype js/Object)) x)))
(defn- bi [x] (if (bigint? x) x (js/BigInt x)))

(def ^:private utf8-decoder (js/TextDecoder. "utf-8"))

(defn chunk "The chunk over host byte array bs: bs itself." [bs] bs)
(defn chunk-bytes "The host byte array of chunk c." [c] c)
(defn chunk-len "The byte length of chunk c." [c] (.-length c))
(defn utf8 "The string of the n utf-8 bytes at off." [c off n]
  (.decode utf8-decoder (.subarray c off (+ off n))))
))

;; ---------------------------------------------------------------------------
;; in place: compares over the stored bytes, no copy of them
;; ---------------------------------------------------------------------------

(defn bytes=
  "Are the n bytes of chunk c at off equal to host bytes bs from boff?
   False when bs holds fewer than n bytes from boff."
  [c off n bs boff]
  (let [off (long off) n (long n) boff (long boff)]
    (and (<= (+ boff n) (alength #?(:clj ^bytes bs :default bs)))
         (loop [i 0]
           (cond (== i n) true
                 (== (u8 c (+ off i)) (hb bs (+ boff i))) (recur (inc i))
                 :else false)))))

(defn bytes-cmp
  "-1 / 0 / 1: the n bytes of chunk c at off against the bn host bytes of bs
   from boff, unsigned and lexicographically, a shorter prefix first."
  [c off n bs boff bn]
  (let [off (long off) n (long n) boff (long boff) bn (long bn)
        m (if (< n bn) n bn)]
    (loop [i 0]
      (if (< i m)
        (let [x (u8 c (+ off i)) y (hb bs (+ boff i))]
          (cond (< x y) -1 (> x y) 1 :else (recur (inc i))))
        (cond (< n bn) -1 (> n bn) 1 :else 0)))))

(defn- len-cmp
  "-1 / 0 / 1: string lengths a and b (same length class) as their encoded
   length bytes compare — one byte below 256, else four little-endian bytes
   compared as bytes (eve.codec's [0x06][len u32 LE] is a byte string)."
  [a b]
  (let [a (long a) b (long b)]
    (if (< a 256)
      (cond (< a b) -1 (> a b) 1 :else 0)
      (loop [j 0]
        (if (== j 4)
          0
          (let [x (bit-and (bit-shift-right a (* 8 j)) 0xFF)
                y (bit-and (bit-shift-right b (* 8 j)) 0xFF)]
            (cond (< x y) -1 (> x y) 1 :else (recur (inc j)))))))))

(defn key-cmp
  "-1 / 0 / 1: the string key stored as utf-8 at [off, off+n) of chunk c
   against host string k, in eve's key-byte order — the order of
   eve.codec/encode-key's bytes: a string below 256 utf-8 bytes is
   [0x05][len][bytes], from 256 [0x06][len u32 LE][bytes]; so the length
   class, then the length bytes, then the bytes."
  [c off n k]
  (let [kb (enc/utf8-bytes k)
        kn (long (alength #?(:clj ^bytes kb :default kb)))
        n (long n)
        sc (if (< n 256) 5 6)
        kc (if (< kn 256) 5 6)]
    (cond (< sc kc) -1
          (> sc kc) 1
          :else (let [lc (long (len-cmp n kn))]
                  (if (zero? lc) (bytes-cmp c off n kb 0 kn) lc)))))

;; ---------------------------------------------------------------------------
;; the writer (moved from chunk.cljc: IBuf and its Buf deftypes) —
;; FORMAT-V1's children-first writer, growable, one offset width.
;; ---------------------------------------------------------------------------

;; The buf-* calls are declared here and defined once every Buf
;; realization exists: on the JVM their bodies are the IBuf interface
;; calls, on bb / glojure / jank the IBuf protocol fns, on cljs the Buf
;; methods. Member-call syntax (.wU8 w …) is not portable: jank's
;; completion objects have no native members, so its analyzer rejects
;; it outright, and the protocol hosts dispatch through the fns anyway.
(declare buf-raw buf-pos buf-seek buf-u8 buf-u16 buf-u32 buf-i64
         buf-f64 buf-varint buf-bytes buf-off buf-patch-u32)

#?(:clj (do
;; IBuf is a definterface on the JVM (primitive signatures — the
;; writer's inner loops) and a defprotocol on bb, glojure and jank,
;; whose compilers have no definterface. A protocol generates the same
;; method surface; every call site goes through the buf-* fns, the one
;; spelling both dispatches share. The methods carry a w prefix (in
;; chunk.cljc they were raw, u8, …): on the protocol hosts a protocol
;; method is a var of this namespace, and u8 / u16 / u32 / i64 / f64 /
;; varint are this namespace's reads.
#?@(:jank [(defprotocol IBuf
             (wRaw [_]) (wPos [_]) (wSeek [_ p]) (wU8 [_ v]) (wU16 [_ v])
             (wU32 [_ v]) (wI64 [_ v]) (wF64 [_ v]) (wVarint [_ v])
             (wBytes [_ src off n]) (wOff [_ v]) (wPatchU32 [_ at v]))]
    :bb [(defprotocol IBuf
           (wRaw [_]) (wPos [_]) (wSeek [_ p]) (wU8 [_ v]) (wU16 [_ v])
           (wU32 [_ v]) (wI64 [_ v]) (wF64 [_ v]) (wVarint [_ v])
           (wBytes [_ src off n]) (wOff [_ v]) (wPatchU32 [_ at v]))]
    :glj [(defprotocol IBuf
            (wRaw [_]) (wPos [_]) (wSeek [_ p]) (wU8 [_ v]) (wU16 [_ v])
            (wU32 [_ v]) (wI64 [_ v]) (wF64 [_ v]) (wVarint [_ v])
            (wBytes [_ src off n]) (wOff [_ v]) (wPatchU32 [_ at v]))]
    ;; jolt: a protocol, as on bb / glojure / jank: jolt calls a definterface method by name through its interop
    ;; dispatch, where a protocol fn is one dispatch on the type (buf-u8 40 -> 9 ns, 2026-11-22 ET)
    :jolt [(defprotocol IBuf
             (wRaw [_]) (wPos [_]) (wSeek [_ p]) (wU8 [_ v]) (wU16 [_ v])
             (wU32 [_ v]) (wI64 [_ v]) (wF64 [_ v]) (wVarint [_ v])
             (wBytes [_ src off n]) (wOff [_ v]) (wPatchU32 [_ at v]))]
    :clj [(definterface IBuf
            (^bytes wRaw [])
            (^long wPos [])
            (wSeek [^long p])
            (wU8 [^long v])
            (wU16 [^long v])
            (wU32 [^long v])
            (wI64 [^long v])
            (wF64 [^double v])
            (wVarint [^long v])
            (wBytes [^bytes src ^long off ^long n])
            (wOff [^long v])
            (wPatchU32 [^long at ^long v]))])

;; glojure's Buf: its deftype compiles set! of a mutable field to
;; vreset! on the field's VALUE, but the constructor stores raw
;; values — the shared text's first seek dies on an int64 that is
;; not a volatile (measured, heron #48 re-run 6). So on glj the two
;; mutable fields hold volatiles from construction and every method
;; works through them; the method surface and byte layout are the
;; shared text's, unchanged.
#?(:glj (do
(deftype Buf [b p ^long width]
  IBuf
  (wRaw [_] @b)
  (wPos [_] @p)
  (wSeek [_ np] (vreset! p np))
  (wU8 [_ v] (when (>= @p (alength @b)) (vreset! b (ba-copy-of @b (* 2 (alength @b)))))
    (aset @b (int @p) (unchecked-byte v)) (vreset! p (inc @p)))
  (wU16 [this v] (buf-u8 this v) (buf-u8 this (bit-shift-right v 8)))
  (wU32 [this v] (buf-u16 this v) (buf-u16 this (bit-shift-right v 16)))
  (wI64 [this v] (buf-u32 this v) (buf-u32 this (bit-shift-right v 32)))
  (wF64 [this v] (buf-i64 this #?(:jank (enc/double->long-bits v) :default (Double/doubleToRawLongBits v))))
  (wVarint [this v]
    (loop [v v]
      (if (< v 128) (buf-u8 this v)
          (do (buf-u8 this (bit-or (bit-and v 127) 128)) (recur (unsigned-bit-shift-right v 7))))))
  (wBytes [_ src off n]
    (when (> (+ @p n) (alength @b)) (vreset! b (ba-copy-of @b (max (+ @p n) (* 2 (alength @b))))))
    (enc/arraycopy src off @b @p n) (vreset! p (+ @p n)))
  (wOff [this v] (if (== width 2) (buf-u16 this v) (buf-u32 this v)))
  (wPatchU32 [_ at v]
    (aset @b (int at) (unchecked-byte v))
    (aset @b (int (+ at 1)) (unchecked-byte (bit-shift-right v 8)))
    (aset @b (int (+ at 2)) (unchecked-byte (bit-shift-right v 16)))
    (aset @b (int (+ at 3)) (unchecked-byte (bit-shift-right v 24)))))

(defn buf
  "A growable writer whose offsets (buf-off) are width bytes, 2 or 4."
  [width]
  (Buf. (volatile! (byte-array 256)) (volatile! 0) width)))
;; bb: aset on a byte array costs 13–16 µs a byte under SCI, aset-byte 47 ns (flat lanes U2b, measured bb 1.12.218);
;; the JVM keeps its hinted aset, a primitive store
:default (do
(deftype Buf [^:unsynchronized-mutable ^bytes b
              ^:unsynchronized-mutable ^long p
              ^long width]
  IBuf
  (wRaw [_] b)
  (wPos [_] p)
  (wSeek [_ np] (set! p np))
  (wU8 [_ v] (when (>= p (alength b)) (set! b (ba-copy-of b (* 2 (alength b)))))
    #?(:bb (aset-byte b (int p) (unchecked-byte v)) :jolt (put-u8* b p v) :default (aset b (int p) (unchecked-byte v)))
    (set! p (inc p)))
  (wU16 [this v]
    #?@(:jolt [(when (> (+ p 2) (alength b)) (set! b (ba-copy-of b (max (+ p 2) (* 2 (alength b))))))
               (put-u16* b p v) (set! p (+ p 2))]
        :default [(buf-u8 this v) (buf-u8 this (bit-shift-right v 8))]))
  (wU32 [this v]
    #?@(:jolt [(when (> (+ p 4) (alength b)) (set! b (ba-copy-of b (max (+ p 4) (* 2 (alength b))))))
               (put-u32* b p v) (set! p (+ p 4))]
        :default [(buf-u16 this v) (buf-u16 this (bit-shift-right v 16))]))
  (wI64 [this v]
    #?@(:jolt [(when (> (+ p 8) (alength b)) (set! b (ba-copy-of b (max (+ p 8) (* 2 (alength b))))))
               (put-i64* b p v) (set! p (+ p 8))]
        :default [(buf-u32 this v) (buf-u32 this (bit-shift-right v 32))]))
  (wF64 [this v]
    #?@(:jolt [(when (> (+ p 8) (alength b)) (set! b (ba-copy-of b (max (+ p 8) (* 2 (alength b))))))
               (put-f64* b p v) (set! p (+ p 8))]
        :default [(buf-i64 this #?(:jank (enc/double->long-bits v) :default (Double/doubleToRawLongBits v)))]))
  (wVarint [this v]
    (loop [v v]
      (if (< v 128) (buf-u8 this v)
          (do (buf-u8 this (bit-or (bit-and v 127) 128)) (recur (unsigned-bit-shift-right v 7))))))
  (wBytes [_ src off n]
    (when (> (+ p n) (alength b)) (set! b (ba-copy-of b (max (+ p n) (* 2 (alength b))))))
    (enc/arraycopy src off b p n) (set! p (+ p n)))
  (wOff [this v] (if (== width 2) (buf-u16 this v) (buf-u32 this v)))
  (wPatchU32 [_ at v]
    #?@(:jolt [(put-u32* b at v)]
        :default [#?(:bb (aset-byte b (int at) (unchecked-byte v)) :default (aset b (int at) (unchecked-byte v)))
                  #?(:bb (aset-byte b (int (+ at 1)) (unchecked-byte (bit-shift-right v 8))) :default (aset b (int (+ at 1)) (unchecked-byte (bit-shift-right v 8))))
                  #?(:bb (aset-byte b (int (+ at 2)) (unchecked-byte (bit-shift-right v 16))) :default (aset b (int (+ at 2)) (unchecked-byte (bit-shift-right v 16))))
                  #?(:bb (aset-byte b (int (+ at 3)) (unchecked-byte (bit-shift-right v 24))) :default (aset b (int (+ at 3)) (unchecked-byte (bit-shift-right v 24))))])))

(defn buf
  "A growable writer whose offsets (buf-off) are width bytes, 2 or 4."
  [width]
  (Buf. (byte-array 256) 0 width))))
))

;; The writer's buffer on ClojureScript: the same method surface the
;; shared writer calls, over a growable Uint8Array + DataView. The
;; position field is `p`, as on the JVM, so it cannot collide with the
;; pos method.
#?(:org.babashka/nbb (do
;; nbb: SCI's deftype takes protocols, not Object methods, so (.u8 w v)
;; on the cljs Buf does not resolve there (sci.impl.deftype/varint,
;; measured 2026-11-22). The same Uint8Array + DataView writer behind a
;; protocol with the JVM protocol hosts' w-names (the reads own u8 …
;; varint in this namespace); buf-* dispatch to it below.
(defprotocol IBuf
  (wGrow [_ need]) (wRaw [_]) (wPos [_]) (wSeek [_ p]) (wU8 [_ v]) (wU16 [_ v])
  (wU32 [_ v]) (wI64 [_ v]) (wF64 [_ v]) (wVarint [_ v])
  (wBytes [_ src off n]) (wOff [_ v]) (wPatchU32 [_ at v]))
(deftype Buf [^:mutable b ^:mutable dv ^:mutable p width]
  IBuf
  (wGrow [_ need]
    (when (< (.-length b) need)
      (let [nb (js/Uint8Array. (max (* 2 (.-length b)) need))]
        (.set nb b)
        (set! b nb)
        (set! dv (js/DataView. (.-buffer nb))))))
  (wRaw [_] b)
  (wPos [_] p)
  (wSeek [_ np] (set! p np))
  (wU8 [this v]
    (when (>= p (.-length b)) (wGrow this (* 2 (.-length b))))
    (aset b p (bit-and v 0xFF))
    (set! p (inc p)))
  (wU16 [this v] (wU8 this v) (wU8 this (bit-shift-right (bit-and v 0xFFFF) 8)))
  (wU32 [this v]
    (wGrow this (+ p 4))
    (.setUint32 dv p (bit-and v 0xFFFFFFFF) true)
    (set! p (+ p 4)))
  (wI64 [this v]
    (wGrow this (+ p 8))
    (.setBigInt64 dv p (bi v) true)
    (set! p (+ p 8)))
  (wF64 [this v]
    (wGrow this (+ p 8))
    (.setFloat64 dv p v true)
    (set! p (+ p 8)))
  (wVarint [this v]
    (loop [v v]
      (if (< v 128) (wU8 this v)
          (do (wU8 this (bit-or (bit-and v 127) 128))
              (recur (quot v 128))))))
  (wBytes [this src off n]
    (wGrow this (+ p n))
    (.set b (.subarray src off (+ off n)) p)
    (set! p (+ p n)))
  (wOff [this v] (if (== width 2) (wU16 this v) (wU32 this v)))
  (wPatchU32 [_ at v]
    (.setUint32 dv at (bit-and v 0xFFFFFFFF) true)))

(defn buf
  "A growable writer whose offsets (buf-off) are width bytes, 2 or 4."
  [width]
  (let [b (js/Uint8Array. 1024)]
    (->Buf b (js/DataView. (.-buffer b)) 0 width)))))

#?(:org.babashka/nbb nil :cljs (do
(deftype Buf [^:mutable b ^:mutable dv ^:mutable p width]
  Object
  (grow [_ need]
    (when (< (.-length b) need)
      (let [nb (js/Uint8Array. (max (* 2 (.-length b)) need))]
        (.set nb b)
        (set! b nb)
        (set! dv (js/DataView. (.-buffer nb))))))
  (raw [_] b)
  (pos [_] p)
  (seek [_ np] (set! p np))
  (u8 [this v]
    (when (>= p (.-length b)) (.grow this (* 2 (.-length b))))
    (aset b p (bit-and v 0xFF))
    (set! p (inc p)))
  (u16 [this v] (.u8 this v) (.u8 this (bit-shift-right (bit-and v 0xFFFF) 8)))
  (u32 [this v]
    (.grow this (+ p 4))
    (.setUint32 dv p (bit-and v 0xFFFFFFFF) true)
    (set! p (+ p 4)))
  (i64 [this v]
    (.grow this (+ p 8))
    (.setBigInt64 dv p (bi v) true)
    (set! p (+ p 8)))
  (f64 [this v]
    (.grow this (+ p 8))
    (.setFloat64 dv p v true)
    (set! p (+ p 8)))
  (varint [this v]
    (loop [v v]
      (if (< v 128) (.u8 this v)
          (do (.u8 this (bit-or (bit-and v 127) 128))
              (recur (quot v 128))))))
  (putBytes [this src off n]
    (.grow this (+ p n))
    (.set b (.subarray src off (+ off n)) p)
    (set! p (+ p n)))
  (off [this v] (if (== width 2) (.u16 this v) (.u32 this v)))
  (patchU32 [_ at v]
    (.setUint32 dv at (bit-and v 0xFFFFFFFF) true)))

(defn buf
  "A growable writer whose offsets (buf-off) are width bytes, 2 or 4."
  [width]
  (let [b (js/Uint8Array. 1024)]
    (->Buf b (js/DataView. (.-buffer b)) 0 width)))))

;; The one portable spelling of the writer calls (declared above): on
;; the JVM the body is the IBuf interface call, on cljs the Buf method
;; call, and on the protocol hosts the protocol fn call — the only
;; dispatch those hosts have (jank's completion objects have no native
;; members at all).
(defn buf-raw "The writer's host bytes so far (its whole buffer; buf-pos of them written)." [w] #?(:org.babashka/nbb (wRaw w) :jank (wRaw w) :bb (wRaw w) :glj (wRaw w) :jolt (wRaw w) :clj (.wRaw ^IBuf w) :default (.raw w)))
(defn buf-pos "The write position." [w] #?(:org.babashka/nbb (wPos w) :jank (wPos w) :bb (wPos w) :glj (wPos w) :jolt (wPos w) :clj (.wPos ^IBuf w) :default (.pos w)))
(defn buf-seek "Move the write position to p (inside what is written)." [w p] #?(:org.babashka/nbb (wSeek w p) :jank (wSeek w p) :bb (wSeek w p) :glj (wSeek w p) :jolt (wSeek w p) :clj (.wSeek ^IBuf w p) :default (.seek w p)))
(defn buf-u8 [w v] #?(:org.babashka/nbb (wU8 w v) :jank (wU8 w v) :bb (wU8 w v) :glj (wU8 w v) :jolt (wU8 w v) :clj (.wU8 ^IBuf w v) :default (.u8 w v)))
(defn buf-u16 [w v] #?(:org.babashka/nbb (wU16 w v) :jank (wU16 w v) :bb (wU16 w v) :glj (wU16 w v) :jolt (wU16 w v) :clj (.wU16 ^IBuf w v) :default (.u16 w v)))
(defn buf-u32 [w v] #?(:org.babashka/nbb (wU32 w v) :jank (wU32 w v) :bb (wU32 w v) :glj (wU32 w v) :jolt (wU32 w v) :clj (.wU32 ^IBuf w v) :default (.u32 w v)))
(defn buf-i64 [w v] #?(:org.babashka/nbb (wI64 w v) :jank (wI64 w v) :bb (wI64 w v) :glj (wI64 w v) :jolt (wI64 w v) :clj (.wI64 ^IBuf w v) :default (.i64 w v)))
(defn buf-f64 [w v] #?(:org.babashka/nbb (wF64 w v) :jank (wF64 w v) :bb (wF64 w v) :glj (wF64 w v) :jolt (wF64 w v) :clj (.wF64 ^IBuf w v) :default (.f64 w v)))
(defn buf-varint [w v] #?(:org.babashka/nbb (wVarint w v) :jank (wVarint w v) :bb (wVarint w v) :glj (wVarint w v) :jolt (wVarint w v) :clj (.wVarint ^IBuf w v) :default (.varint w v)))
(defn buf-bytes "Append n bytes of host byte array src from off." [w src off n] #?(:org.babashka/nbb (wBytes w src off n) :jank (wBytes w src off n) :bb (wBytes w src off n) :glj (wBytes w src off n) :jolt (wBytes w src off n) :clj (.wBytes ^IBuf w src off n) :default (.putBytes w src off n)))
(defn buf-off "Append an offset of the writer's width." [w v] #?(:org.babashka/nbb (wOff w v) :jank (wOff w v) :bb (wOff w v) :glj (wOff w v) :jolt (wOff w v) :clj (.wOff ^IBuf w v) :default (.off w v)))
(defn buf-patch-u32 "Overwrite the u32 at at (the position stays)." [w at v] #?(:org.babashka/nbb (wPatchU32 w at v) :jank (wPatchU32 w at v) :bb (wPatchU32 w at v) :glj (wPatchU32 w at v) :jolt (wPatchU32 w at v) :clj (.wPatchU32 ^IBuf w at v) :default (.patchU32 w at v)))

(defn buf-align
  "Zero bytes up to a multiple of a (chunk.cljc's align!)."
  [w a]
  (loop [] (when-not (zero? (rem (buf-pos w) a)) (buf-u8 w 0) (recur))))

(defn buf-chunk
  "The chunk of the writer's first n bytes: a copy of exactly n."
  [w n]
  #?(:cljs (js/Uint8Array. (.subarray (buf-raw w) 0 n))
     :default (ba-copy-of (buf-raw w) n)))

;; ---------------------------------------------------------------------------
;; tuples and columns: here the host's persistent vector built from the
;; reads (a fast root makes it a view over the bytes)
;; ---------------------------------------------------------------------------

(defn- elem-size
  "The element byte size of dense column kind k (FORMAT-V1 §5)."
  ^long [k]
  (case (int k) (1 2 3) 1 (4 5) 2 (6 7 8) 4 (9 10 11) 8
    (throw (ex-info "eve.flat-io: not a dense column kind" {:type ::not-dense :kind k}))))

(defn- elem-at
  "Element i of the dense column of kind k whose data starts at data-off."
  [c data-off k i]
  (let [s (elem-size k) at (+ (long data-off) (* s (long i)))]
    (case (int k)
      (1 3) (u8 c at)
      2 (let [v (long (u8 c at))] (if (>= v 128) (- v 256) v))
      4 (let [v (long (u16 c at))] (if (>= v 32768) (- v 65536) v))
      5 (u16 c at)
      6 (i32 c at)
      7 (u32 c at)
      8 #?(:jank (f32 c at)
           :clj (Float/intBitsToFloat (unchecked-int (u32 c at)))
           :default (f32 c at))
      9 (f64 c at)
      10 (i64 c at)
      11 #?(:cljs (.getBigUint64 (dv-of c) at true) :default (i64 c at)))))

(defn tuple
  "The n elements of column kind kind (FORMAT-V1 §5: 1 u8 · 2 i8 · 4 i16 · 5 u16
   · 6 i32 · 7 u32 · 8 f32 · 9 f64 · 10 i64 · 11 u64) stored at data-off of
   chunk c, as the host's persistent vector. On the JVM-class hosts an f32 is
   a Float, an i64 and a u64 a long (a u64 past 2^63 reads as its two's
   complement, as eve.flat.column reads kind 11); on ClojureScript an i64 /
   u64 is a js/BigInt (the contract's i64)."
  [c data-off n kind]
  (mapv (fn [i] (elem-at c data-off kind i)) (range n)))

(defn col
  "The n elements of the column of kind kind at data-off (as tuple)."
  [c data-off n kind]
  (tuple c data-off n kind))
