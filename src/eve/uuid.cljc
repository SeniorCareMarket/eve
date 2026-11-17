(ns eve.uuid
  "Native UUIDs with one big-endian, signed-64-bit semantic on every target.
   JS callers can pass decimal strings or [unsigned-hi32 unsigned-lo32] words
   when a 64-bit value cannot be represented exactly by a Number. Bit accessors
   return signed longs (BigInt on JS); words are portable on every target."
  (:refer-clojure :exclude [random-uuid])
  (:require [clojure.string :as str]
            #?(:cljs [cljs.reader :as edn] :default [clojure.edn :as edn])
            [eve.net.u64 :as u64]
            [eve.process :as process]
            [eve.clock :as clock]))

(defn from-string
  "Read a canonical UUID (case-insensitive); reject malformed input everywhere."
  [s]
  (when-not (and (string? s)
                (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" s))
    (throw (ex-info "Invalid UUID" {:value s})))
  (edn/read-string (str "#uuid \"" (str/lower-case s) "\"")))

(defn- hex-number [s]
  (reduce (fn [n c]
            (+ (* n 16) (get (zipmap "0123456789abcdef" (range 16)) c))) 0 s))

(defn to-words
  "UUID as [[msb-hi32 msb-lo32] [lsb-hi32 lsb-lo32]], in network order."
  [u]
  (let [s (str/replace (str (from-string (str u))) "-" "")]
    [[(hex-number (subs s 0 8)) (hex-number (subs s 8 16))]
     [(hex-number (subs s 16 24)) (hex-number (subs s 24 32))]]))

(defn- long-words [n]
  #?(:cljs (when (and (number? n) (not (js/Number.isSafeInteger n)))
             (throw (ex-info "Use decimal strings or words for a JS 64-bit value" {:value n}))))
  (if (vector? n)
    (do (when-not (and (= 2 (count n))
                       (every? #(and (integer? %) (<= 0 % 4294967295)) n))
          (throw (ex-info "Invalid 64-bit words" {:value n})))
        n)
    ;; Decimal accumulation in two words never loses precision on JS.
    (let [s (str n) neg? (str/starts-with? s "-")
          digits (if neg? (subs s 1) s)]
      (when-not (re-matches #"[0-9]+" digits)
        (throw (ex-info "Expected a signed 64-bit integer" {:value s})))
      (let [limit (if neg? "9223372036854775808" "9223372036854775807")
            digits (str/replace digits #"^0+(?=.)" "")]
        (when (or (> (count digits) 19)
                  (and (= (count digits) 19) (pos? (compare digits limit))))
          (throw (ex-info "64-bit integer out of range" {:value s})))
        (let [words (reduce (fn [v c]
                              (u64/add (u64/mul v [0 10])
                                       [0 (get (zipmap "0123456789" (range 10)) c)]))
                            [0 0] digits)]
          (if neg? (u64/neg words) words))))))

(defn from-longs
  "Construct a native UUID from its two signed 64-bit halves. Also accepts
   decimal strings or [hi32 lo32] words, so all 128 bits survive JS Numbers."
  [most least]
  (let [h (str (u64/hex (long-words most)) (u64/hex (long-words least)))]
    (from-string (str (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16)
                      "-" (subs h 16 20) "-" (subs h 20 32)))))

(defn- signed-long [[hi lo]]
  #?(:cljs (js/BigInt.asIntN 64 (js/BigInt (str "0x" (u64/hex [hi lo]))))
     :default (+ (* (if (>= hi 2147483648) (- hi 4294967296) hi) 4294967296) lo)))

(defn most-significant-bits [u] (signed-long (first (to-words u))))
(defn least-significant-bits [u] (signed-long (second (to-words u))))

(defn random-uuid
  "RFC 4122 version 4 UUID, with entropy from Eve's process adapter."
  []
  (let [b (vec (process/random-bytes 16))
        b (assoc b 6 (+ 64 (mod (nth b 6) 16))
                   8 (+ 128 (mod (nth b 8) 64)))
        words (fn [off]
                (reduce (fn [w x] (u64/add (u64/mul w [0 256]) [0 x]))
                        [0 0] (subvec b off (+ off 8))))]
    (from-longs (words 0) (words 8))))

(defn squuid
  "Semi-sequential v4 UUID: high 32 bits are unsigned Unix seconds (mod 2^32).
   Fractional seconds truncate toward zero, as in DataScript's Clojure default."
  ([] (squuid (clock/current-time-millis)))
  ([millis]
   (let [[[_ low] least] (to-words (random-uuid))]
     (from-longs [(mod (quot millis 1000) 4294967296) low] least))))

(defn squuid-time-millis
  "Recover the unsigned seconds prefix of a squuid, in milliseconds."
  [u]
  (* 1000 (first (first (to-words u)))))
