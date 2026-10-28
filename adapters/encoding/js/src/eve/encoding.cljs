(ns eve.encoding
  "eve's encoding primitives (HOST-CONTRACT §3-4): what eve's codec and types
   take from the host where hosts' APIs differ — UTF-8, IEEE-754 bits, the
   host's numbers, instants, uuids, exceptions, map entries, small caches —
   over JavaScript's (ClojureScript, nbb; their codec, adapters/codec/js,
   needs only these)."
  (:refer-clojure :exclude [aget aset byte-array unchecked-byte unsigned-bit-shift-right
                            long class double bytes])
  (:require [eve.host :refer [aget aset byte-array unchecked-byte unsigned-bit-shift-right
                              long class double bytes]]))

(defn throw-index-out-of-bounds
  "Throw an index-out-of-bounds error."
  [msg]
  (throw (js/Error. msg)))

(defn throw-unsupported
  "Throw an unsupported-operation error."
  []
  (throw (js/Error. "Unsupported operation")))

(defn map-entry
  "A map entry [k v] (what seq on a map answers), on every host."
  [k v]
  (MapEntry. k v nil))

(defn arraycopy
  "Copy len elements of array src from soff to dst at doff."
  [src soff dst doff len]
  (.set dst (.subarray src soff (+ soff len)) doff))

(def ^:private utf8-encoder (js/TextEncoder.))
(def ^:private utf8-decoder (js/TextDecoder.))

(defn utf8-bytes
  "The UTF-8 bytes of string s."
  [s]
  (.encode utf8-encoder s))

(defn utf8-string
  "The string len UTF-8 bytes of b encode, from off."
  [b off len]
  (.decode utf8-decoder (.subarray b off (+ off len))))

(defn bytes=
  "Two byte arrays (Uint8Arrays) equal, byte for byte."
  [a b]
  (and (== (.-length a) (.-length b))
       (loop [i 0]
         (if (>= i (.-length a))
           true
           (if (not= (aget a i) (aget b i)) false (recur (inc i)))))))

(defn illegal-argument
  "The host's illegal-argument exception, with message msg."
  [msg]
  (js/Error. msg))

(defn index-out-of-bounds
  "The host's index-out-of-bounds exception, with message msg."
  [msg]
  (js/Error. msg))

(defn- data-view [b] (js/DataView. (.-buffer b) (.-byteOffset b) (.-byteLength b)))

(defn read-f32-le
  "The float32 at byte off of b (little-endian), as a double."
  [b off]
  (.getFloat32 (data-view b) off true))

(defn read-f64-le
  "The float64 at byte off of b (little-endian)."
  [b off]
  (.getFloat64 (data-view b) off true))

(defn f32-le-bytes
  "The 4 little-endian bytes of v as a float32."
  [v]
  (let [b (js/Uint8Array. 4)] (.setFloat32 (data-view b) 0 v true) b))

(defn f64-le-bytes
  "The 8 little-endian bytes of v as a float64."
  [v]
  (let [b (js/Uint8Array. 8)] (.setFloat64 (data-view b) 0 v true) b))

(defn utf16-units
  "The UTF-16 code units of string s, as integers (what a JVM or
   JavaScript string holds)."
  [s]
  (let [n (.-length s)]
    (loop [i 0 out (transient [])]
      (if (< i n)
        (recur (inc i) (conj! out (.charCodeAt s i)))
        (persistent! out)))))

(defn make-bytes
  "n zeroed bytes (the host's byte array, as eve's codec holds bytes)."
  [n]
  (js/Uint8Array. n))

(defn bytes-of
  "The bytes xs (each 0..255, or its signed byte), as a byte array."
  [xs]
  (js/Uint8Array. (into-array (map #(bit-and % 0xFF) xs))))

(defn bytes-copy
  "A copy of byte array b, in b's own representation (what the host's region
   and codec read: a read's bytes, changed, handed on as read)."
  [b]
  ;; a typed array's own slice (cljs.core/aclone answers a plain Array)
  (.slice b))

(defn map-hash-code
  "The host's object hash of a map m (its hash of an unordered collection)."
  [m]
  (hash-unordered-coll m))

(defn write!
  "Write s (str of it) to the host's writer w (IWriter): what a printer writes to."
  [w s]
  (cljs.core/-write w (str s)))

(defn f64s-le
  "The n float64s little-endian in b from byte off, as the host's double array. (A Float64Array over a copy: the slab's bytes may be shared or
   unaligned.)"
  [b off n]
  (js/Float64Array. (.-buffer (.slice b off (+ off (* 8 n)))) 0 n))

(defn i32s-le
  "The n int32s little-endian in b from byte off, as the host's int array. (An Int32Array over a copy.)"
  [b off n]
  (js/Int32Array. (.-buffer (.slice b off (+ off (* 4 n)))) 0 n))

(defn f64s->bytes-le
  "The little-endian bytes of the doubles in da (the host's double array), 8 each."
  [da]
  (js/Uint8Array. (.-buffer (js/Float64Array.from da))))

(defn i32s->bytes-le
  "The little-endian bytes of the ints in ia (the host's int array), 4 each."
  [ia]
  (js/Uint8Array. (.-buffer (js/Int32Array.from ia))))

(defn host-array?
  "A host array the codec writes as an eve array: none here (a JS typed array
   travels as itself, eve.codec's typed-array encoding)."
  [_v]
  false)

(defn array-subtype+bytes
  "[subtype little-endian-bytes] of a host array: none here (host-array?)."
  [_a]
  nil)

(defn typed-array
  "n elements of eve.array subtype code as the host's typed array: none here
   (a fresh eve array is slab-backed; typed-aget and typed-aset! are never
   reached)."
  [_subtype _n]
  nil)

(defn typed-aget "Never reached here (typed-array)." [_a _subtype _i] nil)

(defn typed-aset! "Never reached here (typed-array)." [_a _subtype _i v] v)

(defn code-points->string
  "The string of a sequence of Unicode code points (one past U+FFFF as its UTF-16 surrogate pair, as a JavaScript
   string holds it) — the inverse of utf16-units."
  [cps]
  (apply str (map (fn [cp] (.fromCodePoint js/String cp)) cps)))

;; ── the byte codec slot (eve.net.bytes, defect register #50) ──────────────────────────────────────────────
;; nil on this host: JavaScript has no native byte-array base64 or hex (btoa speaks a Latin-1 string, which is
;; built a unit at a time — the portable implementations' own cost), so eve.net.bytes' portable code answers.
(def bytes->b64 nil)
(def b64->bytes nil)
(def bytes->hex nil)
(def hex->bytes nil)
