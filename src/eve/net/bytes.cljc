(ns eve.net.bytes
  "Byte vectors (vectors of 0..255) — the one binary form eve.net's portable core reads and writes:
   hex, base64, little-endian words, slices, joins. A host converts at its edge (its :request bodies, its
   native overrides); nothing here names a host type. A string's characters come from eve.encoding/utf16-units (a
   character's code is the host's business: (int c) is no code on ClojureScript).

   Base64 and hex are the host's codec slot (defect register #50): a host plugs its native codec into eve.encoding
   (bytes->b64, b64->bytes, bytes->hex, hex->bytes — a function there, or nil), and to-b64 / from-b64 / hex / unhex
   answer through it. The *-portable implementations here are the default every host gets when it plugs nothing,
   and the reference every plug is held to byte for byte (eve/test/eve/net/bytes_codec_test.cljc)."
  (:refer-clojure :exclude [transient persistent! conj!])
  (:require [eve.host :refer [transient persistent! conj!]]
            [eve.encoding :as e]))

(def ^:private HEX "0123456789abcdef")
(def ^:private B64 "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

;; the two hex digits of each byte, so hex is one lookup a byte (it was two nths, a quot, a mod and a str of two:
;; on basilisp a quarter of a network swap's time, py-spy 2026-10-21)
(def ^:private HEX2 (mapv (fn [b] (str (nth HEX (quot b 16)) (nth HEX (mod b 16)))) (range 256)))

(defn hex-portable "lower-case hex of a byte sequence — the portable reference eve.encoding's bytes->hex plug is held to" [bs]
  (apply str (map (fn [b] (nth HEX2 b)) bs)))

(defn hex "lower-case hex of a byte sequence (the host's eve.encoding plug when it has one, else hex-portable)" [bs]
  (if e/bytes->hex (e/bytes->hex bs) (hex-portable bs)))

;; character tables keyed by the one-character string (str c): a character is a JVM char, a one-character string on
;; ClojureScript and basilisp — (str c) is the same on all, and no host's string method is called
(def ^:private HEX-VAL (into {} (map-indexed (fn [i c] [(str c) (mod i 16)]) "0123456789abcdef0123456789ABCDEF")))
;; base64's digit value of each UTF-16 unit below 128 (-1: not a digit), read by index
(def ^:private B64-OF-UNIT
  (let [us (e/utf16-units B64)] (reduce (fn [t i] (assoc t (nth us i) i)) (vec (repeat 128 -1)) (range 64))))

(defn hex-value "the value 0..15 of a hex digit (either case), or nil" [c] (get HEX-VAL (str c)))

(defn- hex-digit [c]
  (or (hex-value c) (throw (ex-info (str "not a hex digit: " c) {:char c}))))

(defn unhex-portable "the bytes of hex text (even length) — the portable reference eve.encoding's hex->bytes plug is held to" [s]
  (when (odd? (count s)) (throw (ex-info "hex text of odd length" {:len (count s)})))
  (mapv (fn [i] (+ (* 16 (hex-digit (nth s (* 2 i)))) (hex-digit (nth s (inc (* 2 i)))))) (range (quot (count s) 2))))

(defn unhex "the bytes of hex text (even length) (the host's eve.encoding plug when it has one, else unhex-portable)" [s]
  (if e/hex->bytes (e/hex->bytes s) (unhex-portable s)))

(defn utf8
  "the UTF-8 bytes of a string — by code point, so a host's string type does not matter"
  [s]
  (let [us (e/utf16-units s)
        n (count us)]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [c (nth us i)
              ;; a surrogate pair is one code point
              [cp step] (if (and (>= c 0xD800) (<= c 0xDBFF) (< (inc i) n))
                          [(+ 0x10000 (* (- c 0xD800) 1024) (- (nth us (inc i)) 0xDC00)) 2]
                          [c 1])]
          (recur (+ i step)
                 (cond (< cp 0x80) (conj! out cp)
                       (< cp 0x800) (-> out (conj! (+ 0xC0 (quot cp 64))) (conj! (+ 0x80 (mod cp 64))))
                       (< cp 0x10000) (-> out (conj! (+ 0xE0 (quot cp 4096))) (conj! (+ 0x80 (mod (quot cp 64) 64)))
                                          (conj! (+ 0x80 (mod cp 64))))
                       :else (-> out (conj! (+ 0xF0 (quot cp 262144))) (conj! (+ 0x80 (mod (quot cp 4096) 64)))
                                 (conj! (+ 0x80 (mod (quot cp 64) 64))) (conj! (+ 0x80 (mod cp 64)))))))))))

(defn code-points
  "the Unicode code points of UTF-16 units (a surrogate pair is one code point; a lone surrogate stands as itself)"
  [us]
  (let [us (vec us) n (count us)]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (persistent! out)
        (let [c (nth us i)
              d (when (< (inc i) n) (nth us (inc i)))]
          (if (and (>= c 0xD800) (<= c 0xDBFF) d (>= d 0xDC00) (<= d 0xDFFF))
            (recur (+ i 2) (conj! out (+ 0x10000 (* (- c 0xD800) 1024) (- d 0xDC00))))
            (recur (inc i) (conj! out c))))))))

(defn units->str "the string of UTF-16 units (eve.encoding/code-points->string: a host's characters differ in width)" [us]
  ;; units with no surrogate in them are their code points already. (Two-argument comparisons: basilisp's three-argument
  ;; <= costs twenty times as much — 686 ms against 35 ms over 8,000 units, measured)
  (e/code-points->string (if (some (fn [u] (and (>= u 0xD800) (<= u 0xDFFF))) us) (code-points us) us)))

(defn decode-cp "[code point, byte count] of the UTF-8 sequence at i" [bs i]
  (let [c (nth bs i)]
    (cond (< c 0x80) [c 1]
          (< c 0xE0) [(+ (* 64 (mod c 32)) (mod (nth bs (+ i 1)) 64)) 2]
          (< c 0xF0) [(+ (* 4096 (mod c 16)) (* 64 (mod (nth bs (+ i 1)) 64)) (mod (nth bs (+ i 2)) 64)) 3]
          :else [(+ (* 262144 (mod c 8)) (* 4096 (mod (nth bs (+ i 1)) 64)) (* 64 (mod (nth bs (+ i 2)) 64))
                    (mod (nth bs (+ i 3)) 64)) 4])))

(defn utf8->str "the string of UTF-8 bytes" [bs]
  (let [n (count bs)]
    (loop [i 0 out (transient [])]
      (if (>= i n)
        (e/code-points->string (persistent! out))
        (let [[cp k] (decode-cp bs i)] (recur (+ i k) (conj! out cp)))))))

(defn to-b64-portable "standard base64 (padded) of a byte sequence — the portable reference eve.encoding's bytes->b64 plug is held to" [bs]
  (let [v (vec bs) n (count v)]
    (apply str
           (mapcat (fn [i]
                     (let [b0 (nth v i) b1 (if (< (+ i 1) n) (nth v (+ i 1)) 0) b2 (if (< (+ i 2) n) (nth v (+ i 2)) 0)
                           x (+ (* b0 65536) (* b1 256) b2)]
                       [(nth B64 (quot x 262144)) (nth B64 (mod (quot x 4096) 64))
                        (if (< (+ i 1) n) (nth B64 (mod (quot x 64) 64)) \=)
                        (if (< (+ i 2) n) (nth B64 (mod x 64)) \=)]))
                   ;; not (range 0 n 3): clojerl's reduce drops a stepped range shorter than its step (eve.net.u64/steps)
                   (take-while (fn [i] (< i n)) (iterate (fn [i] (+ i 3)) 0))))))

(defn to-b64 "standard base64 (padded) of a byte sequence (the host's eve.encoding plug when it has one, else to-b64-portable)" [bs]
  (if e/bytes->b64 (e/bytes->b64 bs) (to-b64-portable bs)))

(defn from-b64-portable
  "the bytes of base64 text (characters outside the alphabet — padding, line breaks — are skipped) — the portable
   reference eve.encoding's b64->bytes plug is held to"
  [s]
  ;; the text's UTF-16 units read once and by index, four digits gathered into x and their three bytes put out — no
  ;; lazy seq per character (a chain of lazy drops is walked again per step on clojerl: 13 s for 5 KB of text there;
  ;; a lazy keep cost basilisp ~160 µs a byte, 16 s for 100 KB: an 8.7 MB /deref, its pages base64 in a base64 body, ran
  ;; past e2e9's 1,800 s — measured 2026-10-24).
  ;; A short tail decodes as a 4-digit group does with the missing digits zero.
  (let [us (e/utf16-units s) m (count us)]
    (loop [j 0 x 0 k 0 out (transient [])]
      (if (>= j m)
        (persistent! (case k
                       1 (conj! out (bit-shift-left x 2))
                       2 (conj! out (bit-shift-right x 4))
                       3 (-> out (conj! (bit-shift-right x 10)) (conj! (bit-and (bit-shift-right x 2) 255)))
                       out))
        (let [u (nth us j) d (if (< u 128) (nth B64-OF-UNIT u) -1)]
          (cond (neg? d) (recur (inc j) x k out)
                (= k 3) (let [x (bit-or (bit-shift-left x 6) d)]
                          (recur (inc j) 0 0 (-> out (conj! (bit-shift-right x 16))
                                                 (conj! (bit-and (bit-shift-right x 8) 255)) (conj! (bit-and x 255)))))
                :else (recur (inc j) (bit-or (bit-shift-left x 6) d) (inc k) out)))))))

(defn from-b64
  "the bytes of base64 text (characters outside the alphabet — padding, line breaks — are skipped; the host's
   eve.encoding plug when it has one, else from-b64-portable)"
  [s]
  (if e/b64->bytes (e/b64->bytes s) (from-b64-portable s)))

(defn u32le "the 4 little-endian bytes of a u32" [x] [(mod x 256) (mod (quot x 256) 256) (mod (quot x 65536) 256) (quot (mod x 4294967296) 16777216)])

(defn read-u32le "the u32 at offset i" [bs i]
  (+ (nth bs i) (* 256 (nth bs (inc i))) (* 65536 (nth bs (+ i 2))) (* 16777216 (nth bs (+ i 3)))))

(defn slice "bytes [from, to)" [bs from to] (subvec (vec bs) from to))

(defn join
  "the byte sequences concatenated — into one vector part by part (a lazy concat of them cost basilisp a seq cell a byte
   through its singledispatch: b/join was 5.8 s of a seat's 37 s profile, 2026-10-26)"
  [parts]
  (reduce (fn [acc p] (into acc p)) [] parts))
