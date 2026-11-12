(ns eve.numeric
  "Clojure's exact numbers, on every Eve host: bigint (N), ratio (a/b) and
   BigDecimal (M). Arithmetic, =, compare, hasheq, accessors, and EDN print/read
   of the tokens 123N, 1/3 and 1.2300M follow JVM Clojure 1.12 / bb 1.13.226.

   Hosts that already have Clojure's types (JVM, bb, jolt, ClojureCLR) use
   them. ClojureScript / nbb get Eve deftypes over JavaScript BigInt (Ratio and
   BigDecimal; BigInt is wrapped so it prints and equals as in Clojure).
   basilisp's int / Fraction / Decimal are normalized to the same print and
   equality. clojerl (BEAM) has unbounded integers; Ratio and BigDecimal are
   the Eve types when the deftype expands, otherwise those tags stay documented
   as a gap.

   On JVM and bb, pr-str and read-string keep Clojure's own tokens (123N, 1/3,
   1.5M). On hosts that cannot represent or print those tokens (nbb / cljs,
   basilisp's spelling, BEAM ratio/decimal), the value is a reader-tagged
   literal that floats around losslessly:

     #eve/N plus a digit string          ; bigint, decimal digits (optional sign)
     #eve/ratio plus n/d      ; lowest terms, sign on the numerator
     #eve/M plus a decimal string       ; BigDecimal, scale preserved

   Tags follow the #inst / #uuid pattern (a namespaced tag and a string form).
   The eve/ namespace is Eve's portable exact-number surface; N and M are
   Clojure's own suffixes, and ratio is the name of the typetag (a tag named
   / would be illegal). pr-number / read-number always use the Clojure token
   spelling — that is what eve.db.ds serialize should thaw as [1 <EDN text>].
   host-pr-number / install! are the host printer and tagged reader. Arithmetic
   (add / sub / mul / divide / quot' / rem' / mod') is provided but optional:
   the required surface is lossless print/read, =, hash, and codec round-trip.
   When worker e1's deftype :print / :read wiring lands, it should call
   host-pr-number and the eve/* tag readers; no per-host print-method in user
   code.

   The Eve wire format is unchanged (FORMAT-DECISIONS.md §8: tags 0x16/0x17/0x18)."
  (:refer-clojure :exclude [bigint biginteger bigdec ratio? decimal?
                            numerator denominator])
  (:require [clojure.string :as str]
            #?(:cljs [eve.encoding :as enc]
               :clje [eve.encoding :as enc]
               :lpy [eve.encoding :as enc])
            #?(:cljs [cljs.reader :as cljs-reader]
               :bb [clojure.edn :as edn]
               :lpy [clojure.edn :as edn]
               :default [clojure.edn :as edn]))
  ;; cljs uses cljs.core/deftype: eve.deftype requires eve.codec, which
  ;; requires eve.numeric — a cycle. clje still uses Eve's deftype.
  #?(:clje (:require [eve.deftype :refer [deftype]]))
  #?(:lpy (:import fractions decimal io basilisp.lang.obj)))

;; ---------------------------------------------------------------------------
;; 32-bit helpers and Clojure hasheq (Murmur3.hashLong, BigInteger.hashCode,
;; BigDecimal.hashCode after stripTrailingZeros). Portable, so a test can
;; compare hasheq to JVM Clojure on every host without changing cljs.core/hash
;; of ordinary numbers (that would break existing cljs maps).
;; ---------------------------------------------------------------------------

(defn- i32
  "Low 32 bits as a signed int."
  [x]
  #?(:cljs (bit-or x 0)
     :default (let [u (bit-and (long x) 0xFFFFFFFF)]
                (if (>= u 0x80000000) (- u 0x100000000) u))))

(defn- imul
  "Java Math.imul / unchecked-multiply-int: low 32 bits of a*b."
  [a b]
  #?(:cljs (js/Math.imul a b)
     :default (i32 (* (long a) (long b)))))

(defn- long-low32
  "Low 32 bits of a signed 64-bit integer. JS bit shifts mask the count
   to 5 bits, so >>> 32 is not the high word; split through BigInt."
  [x]
  #?(:cljs (let [mod32 (js/BigInt 4294967296)
                 b (js/BigInt (if (or (number? x) (string? x)) x (str x)))
                 u (if (< b (js/BigInt 0)) (+ b (js/BigInt "18446744073709551616")) b)
                 lo (- u (* (/ u mod32) mod32))]
             (i32 (js/Number lo)))
     :default (i32 x)))

(defn- long-high32
  "High 32 bits of a signed 64-bit two's-complement integer."
  [x]
  #?(:cljs (let [mod32 (js/BigInt 4294967296)
                 b (js/BigInt (if (or (number? x) (string? x)) x (str x)))
                 u (if (< b (js/BigInt 0)) (+ b (js/BigInt "18446744073709551616")) b)]
             (i32 (js/Number (/ u mod32))))
     :default (i32 (unsigned-bit-shift-right (long x) 32))))

(defn- ushr32
  "32-bit unsigned right shift (Java's >>> on an int). JVM bit ops are
   64-bit; masking first matches ClojureScript and Murmur3."
  [x n]
  (let [n (bit-and n 31)
        u (bit-and (long (i32 x)) 0xFFFFFFFF)]
    (i32 (unsigned-bit-shift-right u n))))

(defn- shl32 [x n]
  (i32 (bit-shift-left (i32 x) (bit-and n 31))))

(defn- rotl [x n]
  (let [x (i32 x) n (bit-and n 31)]
    (i32 (bit-or (shl32 x n) (ushr32 x (- 32 n))))))

(def ^:private C1 (i32 0xcc9e2d51))
(def ^:private C2 (i32 0x1b873593))

(defn- mix-k1 [k1]
  (let [k1 (imul k1 C1)
        k1 (rotl k1 15)]
    (imul k1 C2)))

(defn- mix-h1 [h1 k1]
  (let [h1 (i32 (bit-xor (i32 h1) (i32 k1)))
        h1 (rotl h1 13)]
    (i32 (clojure.core/+ (imul h1 5) (i32 0xe6546b64)))))

(defn- fmix [h1 length]
  (let [h1 (i32 (bit-xor (i32 h1) (i32 length)))
        h1 (i32 (bit-xor h1 (ushr32 h1 16)))
        h1 (imul h1 (i32 0x85ebca6b))
        h1 (i32 (bit-xor h1 (ushr32 h1 13)))
        h1 (imul h1 (i32 0xc2b2ae35))]
    (i32 (bit-xor h1 (ushr32 h1 16)))))

(defn murmur3-hash-long
  "clojure.lang.Murmur3/hashLong. 0 hashes to 0."
  [x]
  (let [low (long-low32 x)
        high (long-high32 x)]
    (if (and (zero? low) (zero? high))
      0
      (let [h1 (mix-h1 0 (mix-k1 low))
            h1 (mix-h1 h1 (mix-k1 high))]
        (fmix h1 8)))))

;; ---------------------------------------------------------------------------
;; Integer magnitude helpers used on every host (strings stay exact).
;; ---------------------------------------------------------------------------

(def ^:private LONG-MIN "-9223372036854775808")
(def ^:private LONG-MAX "9223372036854775807")
(def ^:private LONG-MIN-N -9223372036854775808)
(def ^:private LONG-MAX-N 9223372036854775807)

(defn- digit-abs-cmp
  "Compare the decimal absolute values of two integer strings (optional sign)."
  [a b]
  (let [norm (fn [s] (let [s (if (or (identical? (first s) \+) (identical? (first s) \-)) (subs s 1) s)
                           s (or (second (re-matches #"^0+(.*)$" s)) s)]
                       (if (= s "") "0" s)))
        a (norm (str a))
        b (norm (str b))
        ca (count a) cb (count b)]
    (cond
      (< ca cb) -1
      (> ca cb) 1
      :else (compare a b))))

(defn- in-long-range-str?
  "True when the integer decimal string s (optional sign) fits in a signed long."
  [s]
  (let [s (str s)
        neg? (identical? (first s) \-)
        t (cond (identical? (first s) \-) (subs s 1)
                (identical? (first s) \+) (subs s 1)
                :else s)
        t (or (second (re-matches #"^0+(.*)$" t)) t)
        t (if (= t "") "0" t)]
    (if neg?
      (<= (digit-abs-cmp t (subs LONG-MIN 1)) 0)
      (<= (digit-abs-cmp t LONG-MAX) 0))))

(defn- parse-long-str [s]
  #?(:cljs (js/parseInt s 10)
     :default (let [t (if (and (pos? (count s)) (identical? (first s) \+)) (subs s 1) (str s))]
                #?(:lpy (python/int t) :clje (read-string t) :default (Long/parseLong t)))))

;; ---------------------------------------------------------------------------
;; Host-specific exact integer (the value inside a bigint).
;; ---------------------------------------------------------------------------

#?@(:cljs [
     (def ^:private BI-0 (js/BigInt 0))
     (def ^:private BI-1 (js/BigInt 1))
     (def ^:private BI-10 (js/BigInt 10))
     (def ^:private BI-32 (js/BigInt 32))
     (def ^:private BI-MASK32 (js/BigInt 0xffffffff))
     (def ^:private BI-MOD32 (js/BigInt 4294967296))
     (def ^:private BI-LONG-MIN (js/BigInt LONG-MIN))
     (def ^:private BI-LONG-MAX (js/BigInt LONG-MAX))
     (defn- bi? [x] (and (some? x) (identical? (.-constructor x) js/BigInt)))
     (defn- bi= [a b] (= a b))
     (defn- bi< [a b] (< a b))
     (defn- bi<= [a b] (<= a b))
     (defn- bi> [a b] (> a b))
     (defn- bi+ [a b] (+ a b))
     (defn- bi- [a b] (- a b))
     (defn- bi* [a b] (* a b))
     (defn- bi-quot [a b] (/ a b))
     (defn- bi-rem [a b] (- a (* (/ a b) b)))
     (defn- bi-neg [a] (- a))
     (defn- bi-abs [a] (if (bi< a BI-0) (bi-neg a) a))
     (defn- bi-zero? [a] (bi= a BI-0))
     (defn- bi-pos? [a] (bi> a BI-0))
     (defn- bi-neg? [a] (bi< a BI-0))
     (defn- bi-cmp [a b] (if (bi< a b) -1 (if (bi> a b) 1 0)))
     (defn- bi-from [x]
       (cond
         (bi? x) x
         (number? x) (js/BigInt (js/Math.trunc x))
         (string? x) (js/BigInt (if (and (pos? (count x)) (identical? (first x) \+)) (subs x 1) x))
         :else (js/BigInt (str x))))
     (defn- bi-str [a] (.toString a 10))
     (defn- bi-gcd [a b]
       (loop [a (bi-abs a) b (bi-abs b)]
         (if (bi-zero? b) a (recur b (bi-rem a b)))))
     (defn- bi>= [a b] (>= a b))
     (defn- bi-in-long? [a] (and (bi<= a BI-LONG-MAX) (bi>= a BI-LONG-MIN)))
     (defn- bi-long [a] (js/Number a))
     (defn- bi-pow10 [k]
       (loop [i 0 p BI-1]
         (if (< i k) (recur (inc i) (bi* p BI-10)) p)))
     (defn- bi-limbs32
       "Big-endian unsigned 32-bit limbs of |n|, or empty for 0."
       [n]
       (let [n (bi-abs n)]
         (if (bi-zero? n)
           []
           (loop [n n acc ()]
             (if (bi-zero? n)
               (vec acc)
               (recur (bi-quot n BI-MOD32)
                      (cons (js/Number (bi-rem n BI-MOD32)) acc)))))))
     ])

#?(:cljs nil
   :default
   (do
     (defn- bi-from-string [s]
       (let [t (if (and (pos? (count s)) (identical? (first s) \+)) (subs s 1) s)]
         #?(:lpy (python/int t)
            :clje (if (re-matches #"[+-]?\d+" (str s))
                    (read-string t)
                    (throw (ex-info (str "not an integer: " s) {})))
            :cljr (BigInteger. (str t))
            :default (BigInteger. ^String (str t)))))
     (defn- bi-from [x]
       (cond
         (integer? x) x
         (string? x) (bi-from-string x)
         :else #?(:clj (cond
                         (instance? clojure.lang.BigInt x)
                         (.toBigInteger ^clojure.lang.BigInt x)
                         (instance? java.math.BigInteger x) x
                         (instance? java.math.BigDecimal x)
                         (.toBigIntegerExact ^java.math.BigDecimal x)
                         (instance? clojure.lang.Ratio x)
                         (throw (ex-info "ratio has no integer value" {:value x}))
                         (number? x) (BigInteger/valueOf (long x))
                         :else (BigInteger. (str x)))
                  :default (bi-from (str x)))))
     (defn- bi-str [a] (str a))
     (defn- bi-zero? [a] (zero? a))
     (defn- bi-neg? [a] (neg? a))
     (defn- bi-pos? [a] (pos? a))
     ;; basilisp's unprimed + - * are checked long ops (an exact token
     ;; past a long threw ArithmeticException: integer overflow); its
     ;; primed ops promote, exactly as on :clj.
     (defn- bi-abs [a] (if (neg? a) #?(:clj (-' a) :lpy (-' a) :default (- a)) a))
     (defn- bi-neg [a] #?(:clj (-' a) :lpy (-' a) :default (- a)))
     (defn- bi+ [a b] #?(:clj (+' a b) :lpy (+' a b) :default (+ a b)))
     (defn- bi- [a b] #?(:clj (-' a b) :lpy (-' a b) :default (- a b)))
     (defn- bi* [a b] #?(:clj (*' a b) :lpy (*' a b) :default (* a b)))
     ;; basilisp's quot/rem are long ops: an unscaled value past a long
     ;; threw ArithmeticException: integer overflow (numeric-tags
     ;; decimals-round-trip). Python's floordiv/mod floor; Clojure's
     ;; quot truncates toward zero — adjust by one when a nonzero
     ;; remainder's sign differs from the dividend's.
     (defn- bi-quot [a b]
       #?(:lpy (let [q (operator/floordiv a b)
                     r (operator/mod a b)]
                 (if (or (zero? r) (= (neg? a) (neg? b))) q (+' q 1)))
          :default (quot a b)))
     (defn- bi-rem [a b]
       #?(:lpy (bi- a (bi* (bi-quot a b) b))
          :default (rem a b)))
     (defn- bi-cmp [a b] (compare a b))
     (defn- bi= [a b] (= a b))
     (defn- bi-gcd [a b]
       (loop [a (bi-abs a) b (bi-abs b)]
         (if (bi-zero? b) a (recur b (bi-rem a b)))))
     (defn- bi-in-long? [a]
       (in-long-range-str? (str a)))
     (defn- bi-long [a] (long a))
     (defn- bi-pow10 [k]
       #?(:clj (.pow (BigInteger/TEN) (int k))
          :lpy (python/pow 10 k)
          :default (loop [i 0 p 1] (if (< i k) (recur (inc i) (#?(:clj *' :default *) p 10)) p))))
     (defn- bi-limbs32 [n]
       #?(:clj
          (let [v (.abs ^java.math.BigInteger (bi-from n))
                ba (.toByteArray v)]
            ;; Java toByteArray is two's complement; for abs value the first byte
            ;; may be a 0 sign pad. Build unsigned 32-bit limbs big-endian.
            (if (or (nil? ba) (zero? (alength ba)) (and (= 1 (alength ba)) (zero? (aget ba 0))))
              []
              (let [bytes (if (and (pos? (alength ba)) (zero? (aget ba 0)))
                            (java.util.Arrays/copyOfRange ba 1 (alength ba))
                            ba)
                    n (alength bytes)
                    pad (let [m (mod n 4)] (if (zero? m) 0 (- 4 m)))
                    padded (byte-array (+ n pad))]
                (System/arraycopy bytes 0 padded pad n)
                (vec (for [i (range 0 (alength padded) 4)]
                       (bit-or (bit-shift-left (bit-and (aget padded i) 0xFF) 24)
                               (bit-shift-left (bit-and (aget padded (inc i)) 0xFF) 16)
                               (bit-shift-left (bit-and (aget padded (+ i 2)) 0xFF) 8)
                               (bit-and (aget padded (+ i 3)) 0xFF)))))))
          :default
          (loop [v (bi-abs n) acc ()]
            (if (bi-zero? v)
              (vec acc)
              (let [q (bi-quot v 4294967296) r (bi-rem v 4294967296)]
                (recur q (cons (long r) acc)))))))
     ))

(defn- bigint-jhash-from-limbs
  "java.math.BigInteger.hashCode of the signed value whose |n| limbs are mag."
  [sign mag]
  (if (or (nil? mag) (zero? (count mag)) (and (= 1 (count mag)) (zero? (nth mag 0))))
    0
    (let [h (reduce (fn [h limb]
                      (i32 (clojure.core/+ (long (imul 31 h))
                                           (long (bit-and limb 0xFFFFFFFF)))))
                    0
                    mag)]
      (imul h sign))))

(defn- bigint-jhash [n]
  (if (bi-zero? n)
    0
    (bigint-jhash-from-limbs (if (bi-neg? n) -1 1) (bi-limbs32 n))))

(defn- compact-bd-hash
  "Java BigDecimal.hashCode of unscaled (fits in long) at scale."
  [unscaled scale]
  (let [is-neg? #?(:cljs (< (js/BigInt (str unscaled)) (js/BigInt 0))
                   :default (neg? (long unscaled)))
        abs-n #?(:cljs (let [b (js/BigInt (str unscaled))] (if is-neg? (- b) b))
                 :default (let [c (long unscaled)] (if (neg? c) (- c) c)))
        temp (i32 (clojure.core/+ (long (imul (long-high32 abs-n) 31))
                                  (long-low32 abs-n)))
        signed (if is-neg? (- temp) temp)]
    (i32 (clojure.core/+ (long (imul 31 signed)) (long scale)))))

(defn- bigdec-jhash [unscaled scale]
  (if (bi-zero? unscaled)
    0
    (if (bi-in-long? unscaled)
      (compact-bd-hash (bi-long unscaled) scale)
      (i32 (clojure.core/+ (long (imul 31 (bigint-jhash unscaled))) (long scale))))))


;; ---------------------------------------------------------------------------
;; Predicates for host-native exact types (before our wrappers).
;; ---------------------------------------------------------------------------

(defn- js-bigint? [x]
  #?(:cljs (and (some? x) (identical? (.-constructor x) js/BigInt))
     :default false))

(defn- host-ratio? [v]
  #?(:clj (instance? clojure.lang.Ratio v)
     :lpy (instance? fractions/Fraction v)
     :default false))

(defn- host-decimal? [v]
  #?(:clj (instance? java.math.BigDecimal v)
     :lpy (instance? decimal/Decimal v)
     :default false))

(defn- host-bigint? [v]
  #?(:clj (or (instance? clojure.lang.BigInt v)
              (instance? java.math.BigInteger v))
     :default false))

;; ---------------------------------------------------------------------------
;; Eve types on hosts that lack Clojure's (cljs/nbb; BEAM ratio/decimal).
;; Field names are available in method bodies. IPrintWithWriter is the one
;; print hook (Eve's deftype translates it); install! covers host primitives.
;; ---------------------------------------------------------------------------


(declare canonical-unscaled-scale decimal-layout ratio bigint bigdec
         ->exact-int exact-compare hasheq read-bigdec-significand read-number
         divide numerator denominator make-bigdec host-pr-number)

(defn- tagged-form
  "The EDN tagged literal #eve/N|ratio|M with a string form, the host-visible
   spelling on nbb, basilisp and BEAM."
  [tag payload]
  (str "#" tag " " (pr-str (str payload))))


#?@(:cljs [
     (deftype EveBigInt [n]
       IEquiv
       (-equiv [_ other]
         (cond
           (instance? EveBigInt other) (bi= n (.-n other))
           (js-bigint? other) (bi= n other)
           (and (number? other) (js/Number.isInteger other)
                (bi= n (js/BigInt other))) true
           :else false))
       IHash
       (-hash [_]
         (if (bi-in-long? n)
           (hash (js/Number n))
           (bigint-jhash n)))
       IComparable
       (-compare [_ other]
         (cond
           (instance? EveBigInt other) (bi-cmp n (.-n other))
           (js-bigint? other) (bi-cmp n other)
           (and (number? other) (js/Number.isInteger other)) (bi-cmp n (js/BigInt other))
           :else (throw (js/Error. (str "Cannot compare bigint to " (pr-str other))))))
       IPrintWithWriter
       (-pr-writer [_ w _opts] (enc/write! w (tagged-form "eve/N" (bi-str n))))
       Object
       (toString [_] (bi-str n)))

     (deftype EveRatio [num den]
       IEquiv
       (-equiv [_ other]
         (and (instance? EveRatio other)
              (bi= num (.-num other))
              (bi= den (.-den other))))
       IHash
       (-hash [_] (i32 (bit-xor (bigint-jhash num) (bigint-jhash den))))
       IComparable
       (-compare [_ other]
         (if (instance? EveRatio other)
           (bi-cmp (bi* num (.-den other)) (bi* (.-num other) den))
           (throw (js/Error. (str "Cannot compare ratio to " (pr-str other))))))
       IPrintWithWriter
       (-pr-writer [_ w _opts] (enc/write! w (tagged-form "eve/ratio" (str (bi-str num) "/" (bi-str den)))))
       Object
       (toString [_] (str (bi-str num) "/" (bi-str den))))

     (deftype EveBigDec [unscaled scale]
       IEquiv
       (-equiv [_ other]
         (if (instance? EveBigDec other)
           (let [a (canonical-unscaled-scale unscaled scale)
                 b (canonical-unscaled-scale (.-unscaled other) (.-scale other))]
             (and (bi= (nth a 0) (nth b 0)) (= (nth a 1) (nth b 1))))
           false))
       IHash
       (-hash [_]
         (let [[u s] (canonical-unscaled-scale unscaled scale)]
           (bigdec-jhash u s)))
       IComparable
       (-compare [_ other]
         (if (instance? EveBigDec other)
           (let [s (max scale (.-scale other))
                 au (bi* unscaled (bi-pow10 (- s scale)))
                 bu (bi* (.-unscaled other) (bi-pow10 (- s (.-scale other))))]
             (bi-cmp au bu))
           (throw (js/Error. (str "Cannot compare decimal to " (pr-str other))))))
       IPrintWithWriter
       (-pr-writer [_ w _opts] (enc/write! w (tagged-form "eve/M" (decimal-layout unscaled scale))))
       Object
       (toString [_] (decimal-layout unscaled scale)))
     ])

#?(:clje
   (do
     (deftype EveRatio [num den]
       clojure.lang.IHashEq
       (hasheq [_] (i32 (bit-xor (bigint-jhash num) (bigint-jhash den))))
       java.lang.Object
       (equals [_ other]
         (and (instance? EveRatio other)
              (= num (.-num other))
              (= den (.-den other))))
       (toString [_] (str num "/" den))
       IPrintWithWriter
       (-pr-writer [_ w _opts] (enc/write! w (tagged-form "eve/ratio" (str num "/" den)))))
     (deftype EveBigDec [unscaled scale]
       clojure.lang.IHashEq
       (hasheq [_] (let [[u s] (canonical-unscaled-scale unscaled scale)] (bigdec-jhash u s)))
       java.lang.Object
       (equals [_ other]
         (if (instance? EveBigDec other)
           (let [a (canonical-unscaled-scale unscaled scale)
                 b (canonical-unscaled-scale (.-unscaled other) (.-scale other))]
             (and (= (nth a 0) (nth b 0)) (= (nth a 1) (nth b 1))))
           false))
       (toString [_] (decimal-layout unscaled scale))
       IPrintWithWriter
       (-pr-writer [_ w _opts] (enc/write! w (tagged-form "eve/M" (decimal-layout unscaled scale)))))))

#?(:lpy
   (deftype EveBigInt [n]
     ;; Typetag wrapper: basilisp's int is already unbounded, but 123 and 123N
     ;; must stay distinct so pr-number / host-pr-number can emit 123N or
     ;; #eve/N "123". (= 5 5N) is implemented on this type and in equiv.
     (__eq__ [_ other]
       (cond
         (instance? EveBigInt other) (= n (.-n other))
         (integer? other) (= n other)
         :else false))
     (__hash__ [_] (python/hash n))
     (__repr__ [_] (str n "N"))
     (__str__ [_] (str n))))

(defn- eve-bigint-type? [x]
  #?(:cljs (instance? EveBigInt x)
     :lpy (instance? EveBigInt x)
     :default false))

(defn- eve-ratio-type? [x]
  #?(:cljs (instance? EveRatio x)
     :clje (instance? EveRatio x)
     :default false))

(defn- eve-bigdec-type? [x]
  #?(:cljs (instance? EveBigDec x)
     :clje (instance? EveBigDec x)
     :default false))

(defn- unwrap-bi [x]
  (cond
    (eve-bigint-type? x) #?(:cljs (.-n x) :lpy (.-n x) :clje (.-n x) :default x)
    (js-bigint? x) x
    (host-bigint? x) #?(:clj (if (instance? clojure.lang.BigInt x)
                               (or (.-bipart ^clojure.lang.BigInt x)
                                   (BigInteger/valueOf (.-lpart ^clojure.lang.BigInt x)))
                               x)
                        :default x)
    :else (bi-from x)))

;; declared later; placeholders so deftype methods can close over names
(defn- canonical-unscaled-scale
  "Trailing zeros stripped down to scale 0; a negative scale is raised to 0
   only for the wire, not here. For hasheq / = we strip zeros and leave a
   negative scale (Java stripTrailingZeros of 100M is 1E+2)."
  [unscaled scale]
  (if (bi-zero? unscaled)
    [ (bi-from 0) 0 ]
    (loop [u unscaled s scale]
      (if (and (not (bi-zero? u))
               (bi-zero? (bi-rem u (bi-from 10)))
               ;; keep going while divisible by 10 (strip trailing zeros)
               true)
        (recur (bi-quot u (bi-from 10)) (dec s))
        [u s]))))

;; Java BigDecimal.toString layout (used by str; pr adds M).
(defn- decimal-layout [unscaled scale]
  ;; the sign local is `neg`, never `neg?`: the scientific branch calls
  ;; clojure.core/neg? on the exponent, and a boolean local of that name
  ;; shadows it (basilisp: 'bool' object is not callable).
  (let [neg (bi-neg? unscaled)
        digits (bi-str (bi-abs unscaled))
        ulen (count digits)
        adj (clojure.core/- ulen 1 scale)]
    (if (and (>= scale 0) (>= adj -6))
      (cond
        (zero? scale) (str (when neg "-") digits)
        (>= ulen scale)
        (str (when neg "-") (subs digits 0 (- ulen scale)) "." (subs digits (- ulen scale)))
        :else
        (str (when neg "-") "0." (apply str (repeat (- scale ulen) "0")) digits))
      ;; scientific
      (let [exp adj
            unsig (if (= ulen 1) digits (str (subs digits 0 1) "." (subs digits 1)))
            exp-str (if (neg? exp) (str "E" exp) (str "E+" exp))]
        (str (when neg "-") unsig exp-str)))))

;; ---------------------------------------------------------------------------
;; Public predicates and constructors
;; ---------------------------------------------------------------------------

(defn bigint?
  "A bigint typetag (123N, or the host's BigInt / wrapped JS BigInt). Not a
   fixed-precision int, even when the magnitude fits in a long."
  [x]
  (or (eve-bigint-type? x)
      (js-bigint? x)
      (host-bigint? x)))

(defn ratio?
  "A ratio typetag (1/3). An integer is never a ratio."
  [x]
  (or (eve-ratio-type? x) (host-ratio? x)))

(defn decimal?
  "An arbitrary-precision decimal typetag (1.5M)."
  [x]
  (or (eve-bigdec-type? x) (host-decimal? x)))

(defn- wrap-bigint [n]
  #?(:cljs (EveBigInt. (if (bi? n) n (bi-from n)))
     :clj (clojure.core/bigint n)
     :lpy (if (instance? EveBigInt n) n (EveBigInt. (bi-from n)))
     :default n))

(defn bigint
  "The bigint typetag for x (an integer, a decimal string, or an existing
   bigint). Always a bigint, even when the magnitude fits in a long — (pr-str
   (bigint 123)) is \"123N\", and (int? (bigint 123)) is false, matching
   Clojure."
  [x]
  (cond
    (bigint? x) #?(:clj (clojure.core/bigint x)
                   :cljs (if (eve-bigint-type? x) x (EveBigInt. (unwrap-bi x)))
                   :default x)
    (string? x) (wrap-bigint (bi-from x))
    (decimal? x)
    (wrap-bigint #?(:clj (.toBigIntegerExact ^java.math.BigDecimal x)
                    :cljs (let [u (.-unscaled x) s (.-scale x)]
                            (if (zero? s) u
                              (if (pos? s)
                                (let [q (bi-quot u (bi-pow10 s)) r (bi-rem u (bi-pow10 s))]
                                  (if (bi-zero? r) q
                                    (throw (js/Error. "Nonintegral decimal has no bigint value"))))
                                (bi* u (bi-pow10 (- s))))))
                    :lpy (python/int x)
                    :default (bi-from (str x))))
    (ratio? x) (throw (#?(:cljs js/Error. :default ex-info)
                       #?(:cljs "Ratio has no integer value"
                          :default "Ratio has no integer value")
                       #?(:default {:value x} :cljs nil)))
    (number? x) (wrap-bigint (bi-from x))
    :else (wrap-bigint (bi-from (str x)))))

(defn biginteger
  "The untyped integer magnitude (JVM BigInteger, JS BigInt, host int)."
  [x]
  (unwrap-bi (if (bigint? x) x (bigint x))))

(defn- reduce-ratio [n d]
  (when (bi-zero? d)
    (throw #?(:cljs (js/Error. "Divide by zero")
              :default (ex-info "Divide by zero" {:eve.numeric/div-by-zero true}))))
  (let [g (bi-gcd n d)
        n (bi-quot n g)
        d (bi-quot d g)
        [n d] (if (bi-neg? d) [(bi-neg n) (bi-neg d)] [n d])]
    (if (bi= d (bi-from 1))
      (wrap-bigint n)
      #?(:clj (/ (clojure.core/bigint n) (clojure.core/bigint d))
         :lpy (fractions/Fraction n d)
         :cljs (EveRatio. n d)
         :clje (EveRatio. n d)
         :default (EveRatio. n d)))))

(defn ratio
  "The ratio n/d in lowest terms with a positive denominator. A denominator of
   1 yields a bigint (Clojure: (+ 1/2 1/2) is 1N)."
  [n d]
  (reduce-ratio (unwrap-bi (if (integer? n) n (bigint n)))
                (unwrap-bi (if (integer? d) d (bigint d)))))

(defn bigdec
  "The decimal typetag. A string preserves scale (\"1.2300\" is 1.2300M). A
   number or exact integer is scale 0. Two-arg (bigdec unscaled scale) is
   unscaled × 10^-scale."
  ([x]
   (cond
     (decimal? x) x
     (string? x) (let [tok (if (and (pos? (count x)) (identical? (last x) \M)) (subs x 0 (dec (count x))) x)]
                   (read-bigdec-significand tok))
     (ratio? x) (divide (bigdec (numerator x)) (bigdec (denominator x)))
     (or (bigint? x) (integer? x) (js-bigint? x))
     (make-bigdec (unwrap-bi x) 0)
     (number? x) (read-bigdec-significand (str x))
     :else (read-bigdec-significand (str x))))
  ([unscaled scale]
   (make-bigdec (unwrap-bi unscaled) (int scale))))

#?(:clj
   (defn- bi->biginteger
     "unscaled as a java.math.BigInteger, whatever host integer reaches
      make-bigdec (a clojure.lang.BigInt from unscaled-value / +' sums —
      which has no BigDecimal ctor at all — or a long). The ctor call
      below is hinted, never reflective."
     ^java.math.BigInteger [x]
     (cond
       (instance? java.math.BigInteger x) x
       (instance? clojure.lang.BigInt x) (.toBigInteger ^clojure.lang.BigInt x)
       (instance? java.math.BigDecimal x) (.toBigIntegerExact ^java.math.BigDecimal x)
       (number? x) (java.math.BigInteger/valueOf (long x))
       :else (java.math.BigInteger. (str x)))))

(defn- make-bigdec [unscaled scale]
  ;; The (BigInteger, scale) ctor, not movePointLeft: the scale IS the
  ;; value's (1E+2M is unscaled 1 at scale -2), and movePointLeft
  ;; normalizes a negative scale away (1E+2 came back as 100).
  #?(:clj (java.math.BigDecimal. (bi->biginteger unscaled) (int scale))
     :lpy (decimal/Decimal (str unscaled "E" (- scale)))
     :cljs (EveBigDec. (bi-from unscaled) (int scale))
     :clje (EveBigDec. unscaled (int scale))
     :default (EveBigDec. unscaled (int scale))))

(defn numerator
  "The numerator of a ratio (sign lives here). Not defined on a non-ratio in
   Clojure — this also accepts an integer as n/1 for convenience on every host."
  [x]
  (cond
    (host-ratio? x) #?(:clj (clojure.core/numerator x)
                       :lpy (.-numerator x)
                       :default x)
    (eve-ratio-type? x) #?(:cljs (wrap-bigint (.-num x))
                           :default (.-num x))
    (or (integer? x) (bigint? x) (js-bigint? x)) (bigint x)
    :else (throw (#?(:cljs js/Error. :default ex-info)
                  #?(:cljs "numerator requires a ratio or integer"
                     :default "numerator requires a ratio or integer")
                  #?(:default {:value x} :cljs nil)))))

(defn denominator
  [x]
  (cond
    (host-ratio? x) #?(:clj (clojure.core/denominator x)
                       :lpy (.-denominator x)
                       :default 1)
    (eve-ratio-type? x) #?(:cljs (wrap-bigint (.-den x))
                           :default (.-den x))
    (or (integer? x) (bigint? x) (js-bigint? x)) (wrap-bigint 1)
    :else (throw (#?(:cljs js/Error. :default ex-info)
                  #?(:cljs "denominator requires a ratio or integer"
                     :default "denominator requires a ratio or integer")
                  #?(:default {:value x} :cljs nil)))))

(defn scale
  "The scale of a decimal (digits to the right of the decimal point; may be
   negative, as in 1E+2M). Not the canonical/wire scale."
  [x]
  (cond
    (host-decimal? x) #?(:clj (.scale ^java.math.BigDecimal x)
                         :lpy (- (.-exponent (.as-tuple x)))
                         :default 0)
    (eve-bigdec-type? x) (.-scale x)
    :else (throw (#?(:cljs js/Error. :default ex-info)
                  #?(:cljs "scale requires a decimal"
                     :default "scale requires a decimal")
                  #?(:default {:value x} :cljs nil)))))

(defn unscaled-value
  "The unscaled integer of a decimal (150 of 1.50M)."
  [x]
  (cond
    (host-decimal? x)
    #?(:clj (clojure.core/bigint (.unscaledValue ^java.math.BigDecimal x))
       :lpy (let [t (.as-tuple x)
                  mag (reduce (fn [acc d] (clojure.core/+ (* acc 10) d)) 0 (.-digits t))]
              (if (= 1 (.-sign t)) (- mag) mag))
       :default x)
    (eve-bigdec-type? x) (wrap-bigint (.-unscaled x))
    :else (throw (#?(:cljs js/Error. :default ex-info)
                  #?(:cljs "unscaled-value requires a decimal"
                     :default "unscaled-value requires a decimal")
                  #?(:default {:value x} :cljs nil)))))


;; ---------------------------------------------------------------------------
;; Category and mixed-type arithmetic (Clojure Numbers: FLOATING > DECIMAL >
;; RATIO > INTEGER).
;; ---------------------------------------------------------------------------

(defn- floating? [x]
  (and (number? x) (not (integer? x)) (not (bigint? x)) (not (ratio? x)) (not (decimal? x))
       #?(:cljs (not (js-bigint? x)) :default true)
       (or (float? x) (double? x)
           #?(:cljs true :default false))))

(defn- integer-like? [x]
  (or (bigint? x) (js-bigint? x)
      #?(:cljs (and (number? x) (js/Number.isInteger x))
         :default (integer? x))))

(defn- as-double [x]
  (cond
    (number? x) (double x)
    (bigint? x) #?(:cljs (js/Number (unwrap-bi x))
                   ;; basilisp: (double an EveBigInt) is float() of the
                   ;; wrapper, which is not a number; unwrap first.
                   :lpy (double (unwrap-bi x))
                   :default (double x))
    (js-bigint? x) #?(:cljs (js/Number x) :default (double x))
    (ratio? x) (/ (as-double (numerator x)) (as-double (denominator x)))
    (decimal? x) #?(:clj (double x)
                    :cljs (let [u (.-unscaled x) s (.-scale x)]
                            (if (zero? s) (js/Number u)
                              (/ (js/Number u) (js/Number (bi-pow10 s)))))
                    :lpy (double (str x))
                    :default (double (str x)))
    :else (double x)))

(defn- as-bigdec [x]
  (cond
    (decimal? x) x
    (ratio? x) (divide (make-bigdec (unwrap-bi (numerator x)) 0)
                       (make-bigdec (unwrap-bi (denominator x)) 0))
    :else (bigdec x)))

(defn- dec-add [a b]
  (let [sa (scale a) sb (scale b)
        s (max sa sb)
        ua (unwrap-bi (unscaled-value a))
        ub (unwrap-bi (unscaled-value b))
        ua (if (= s sa) ua (bi* ua (bi-pow10 (- s sa))))
        ub (if (= s sb) ub (bi* ub (bi-pow10 (- s sb))))]
    (make-bigdec (bi+ ua ub) s)))

(defn- dec-sub [a b]
  (dec-add a (make-bigdec (bi-neg (unwrap-bi (unscaled-value b))) (scale b))))

(defn- dec-mul [a b]
  (make-bigdec (bi* (unwrap-bi (unscaled-value a)) (unwrap-bi (unscaled-value b)))
               (clojure.core/+ (scale a) (scale b))))

(defn- factor-2-5
  "Strip factors 2 and 5 from positive integer d; the remainder is 1 iff a
   BigDecimal division by d can terminate."
  [d]
  (let [d (bi-abs d)
        two (bi-from 2) five (bi-from 5)]
    (loop [d d]
      (cond
        (bi-zero? (bi-rem d two)) (recur (bi-quot d two))
        (bi-zero? (bi-rem d five)) (recur (bi-quot d five))
        :else d))))

(defn- dec-div [a b]
  (when (bi-zero? (unwrap-bi (unscaled-value b)))
    (throw #?(:cljs (js/Error. "Divide by zero")
              :default (ex-info "Divide by zero" {:eve.numeric/div-by-zero true}))))
  #?(:clj (.divide ^java.math.BigDecimal a ^java.math.BigDecimal b)
     :default
     (let [ua (unwrap-bi (unscaled-value a))
           ub (unwrap-bi (unscaled-value b))
           sa (scale a) sb (scale b)
           ;; a/b = (ua/ub) * 10^(sb-sa). Reduce ua/ub, then scale.
           g (bi-gcd ua ub)
           n (bi-quot ua g)
           d (bi-quot ub g)
           rem-d (factor-2-5 d)]
       (when-not (bi= rem-d (bi-from 1))
         (throw #?(:cljs (js/Error. "Non-terminating decimal expansion; no exact representable decimal result.")
                   :default (ex-info "Non-terminating decimal expansion; no exact representable decimal result."
                                     {:eve.numeric/non-terminating true}))))
       ;; multiply n by 2s and 5s so d becomes 10^k, then apply scale sb-sa
       (let [two (bi-from 2) five (bi-from 5)]
         (loop [n n d d k 0]
           (if (bi= d (bi-from 1))
             (let [scale (clojure.core/+ k (clojure.core/- sa sb))]
               (make-bigdec n scale))
             (if (bi-zero? (bi-rem d two))
               (recur (bi* n five) (bi-quot d two) (inc k))
               (recur (bi* n two) (bi-quot d five) (inc k)))))))))

(defn- rat-ops [op a b]
  (let [na (unwrap-bi (numerator a)) da (unwrap-bi (denominator a))
        nb (unwrap-bi (numerator b)) db (unwrap-bi (denominator b))]
    (case op
      :add (reduce-ratio (bi+ (bi* na db) (bi* nb da)) (bi* da db))
      :sub (reduce-ratio (bi- (bi* na db) (bi* nb da)) (bi* da db))
      :mul (reduce-ratio (bi* na nb) (bi* da db))
      :div (reduce-ratio (bi* na db) (bi* da nb)))))

(defn- int-add [a b] (wrap-bigint (bi+ (unwrap-bi (bigint a)) (unwrap-bi (bigint b)))))
(defn- int-sub [a b] (wrap-bigint (bi- (unwrap-bi (bigint a)) (unwrap-bi (bigint b)))))
(defn- int-mul [a b] (wrap-bigint (bi* (unwrap-bi (bigint a)) (unwrap-bi (bigint b)))))

(defn- as-ratio [x]
  (if (ratio? x) x (ratio (unwrap-bi x) 1)))

(defn- both-fixed-int? [a b]
  (and (not (bigint? a)) (not (js-bigint? a))
       (not (bigint? b)) (not (js-bigint? b))
       (integer-like? a) (integer-like? b)))

(defn- int-div [a b]
  (when (bi-zero? (unwrap-bi b))
    (throw #?(:cljs (js/Error. "Divide by zero")
              :default (ex-info "Divide by zero" {:eve.numeric/div-by-zero true}))))
  (let [na (unwrap-bi a) nb (unwrap-bi b)
        q (bi-quot na nb) r (bi-rem na nb)]
    (if (bi-zero? r)
      (if (and (both-fixed-int? a b) (bi-in-long? q))
        (bi-long q)
        (wrap-bigint q))
      (reduce-ratio na nb))))

(defn- promote [a b]
  (cond
    (or (floating? a) (floating? b)) :floating
    (or (decimal? a) (decimal? b)) :decimal
    (or (ratio? a) (ratio? b)) :ratio
    :else :integer))

(defn add
  "Clojure + over exact numbers (and doubles). Multi-arity. (+ 1 1N) is 2N;
   (+ 1.5M 1/2) is 2.0M; a double operand yields a double."
  ([] 0)
  ([x] x)
  ([a b]
   (case (promote a b)
     :floating (clojure.core/+ (as-double a) (as-double b))
     :decimal (dec-add (as-bigdec a) (as-bigdec b))
     :ratio (rat-ops :add (as-ratio a) (as-ratio b))
     :integer (if (both-fixed-int? a b)
                (let [s (bi+ (bi-from a) (bi-from b))]
                  (if (bi-in-long? s) (bi-long s) (wrap-bigint s)))
                (int-add a b))))
  ([a b & more] (reduce add (add a b) more)))

(defn sub
  ([x]
   (cond
     (floating? x) (- (as-double x))
     (decimal? x) (make-bigdec (bi-neg (unwrap-bi (unscaled-value x))) (scale x))
     (ratio? x) (ratio (bi-neg (unwrap-bi (numerator x))) (unwrap-bi (denominator x)))
     (integer-like? x) (if (and (not (bigint? x)) (not (js-bigint? x)) (not= x LONG-MIN-N))
                         (- x)
                         (wrap-bigint (bi-neg (unwrap-bi x))))
     :else (- x)))
  ([a b] (add a (sub b)))
  ([a b & more] (reduce sub (sub a b) more)))

(defn mul
  ([] 1)
  ([x] x)
  ([a b]
   (case (promote a b)
     :floating (clojure.core/* (as-double a) (as-double b))
     :decimal (dec-mul (as-bigdec a) (as-bigdec b))
     :ratio (rat-ops :mul (as-ratio a) (as-ratio b))
     :integer (if (both-fixed-int? a b)
                (let [p (bi* (bi-from a) (bi-from b))]
                  (if (bi-in-long? p) (bi-long p) (wrap-bigint p)))
                (int-mul a b))))
  ([a b & more] (reduce mul (mul a b) more)))

(defn divide
  "Clojure / over exact numbers. Two integers that do not divide evenly yield
   a ratio. Two decimals throw on a non-terminating expansion, as JVM Clojure
   does without *math-context*."
  ([x] (divide 1 x))
  ([a b]
   (case (promote a b)
     :floating (/ (as-double a) (as-double b))
     :decimal (dec-div (as-bigdec a) (as-bigdec b))
     :ratio (rat-ops :div (as-ratio a) (as-ratio b))
     :integer (int-div a b)))
  ([a b & more] (reduce divide (divide a b) more)))

(defn quot'
  "Clojure quot: truncating toward zero. Ratios are first divided, then
   truncated; the integer result is a bigint when an operand was exact."
  [a b]
  (when (or (and (integer-like? b) (bi-zero? (unwrap-bi b)))
            (and (decimal? b) (bi-zero? (unwrap-bi (unscaled-value b))))
            (and (ratio? b) (bi-zero? (unwrap-bi (numerator b))))
            (and (number? b) (zero? b)))
    (throw #?(:cljs (js/Error. "Divide by zero")
              :default (ex-info "Divide by zero" {:eve.numeric/div-by-zero true}))))
  (case (promote a b)
    :floating (quot (as-double a) (as-double b))
    (let [q (divide a b)]
      (cond
        (ratio? q) (wrap-bigint (bi-quot (unwrap-bi (numerator q)) (unwrap-bi (denominator q))))
        (decimal? q) (bigint (make-bigdec (bi-quot (unwrap-bi (unscaled-value q))
                                                   (bi-pow10 (max 0 (scale q))))
                                          (min 0 (scale q))))
        (or (bigint? q) (integer-like? q)) (if (bigint? q) q (if (integer? q) q (bigint q)))
        :else (quot a b)))))

(defn rem'
  [a b]
  (let [q (quot' a b)]
    (sub a (mul q b))))

(defn mod'
  "Clojure mod: remainder with the sign of the divisor."
  [a b]
  (let [r (rem' a b)]
    (if (or (and (pos? (as-double b)) (neg? (as-double r)))
            (and (neg? (as-double b)) (pos? (as-double r))))
      (add r b)
      r)))

;; ---------------------------------------------------------------------------
;; compare and hasheq
;; ---------------------------------------------------------------------------

(defn- int-compare [a b]
  (bi-cmp (unwrap-bi a) (unwrap-bi b)))

(defn- rat-compare [a b]
  (let [na (unwrap-bi (numerator a)) da (unwrap-bi (denominator a))
        nb (unwrap-bi (numerator b)) db (unwrap-bi (denominator b))]
    (bi-cmp (bi* na db) (bi* nb da))))

(defn- dec-compare [a b]
  (let [sa (scale a) sb (scale b)
        s (max sa sb)
        ua (bi* (unwrap-bi (unscaled-value a)) (bi-pow10 (- s sa)))
        ub (bi* (unwrap-bi (unscaled-value b)) (bi-pow10 (- s sb)))]
    (bi-cmp ua ub)))

(defn exact-compare
  "Clojure compare over numbers: (compare 1 1M) is 0 and (compare 1/2 0.5) is
   0, even though = is false in both cases."
  [a b]
  (cond
    (or (floating? a) (floating? b))
    (compare (as-double a) (as-double b))
    (and (decimal? a) (decimal? b)) (dec-compare a b)
    (and (or (ratio? a) (integer-like? a))
         (or (ratio? b) (integer-like? b)))
    (rat-compare (as-ratio a) (as-ratio b))
    (or (decimal? a) (decimal? b))
    (dec-compare (as-bigdec a) (as-bigdec b))
    :else (compare a b)))

(defn hasheq
  "Clojure's hasheq, including Murmur3 for long-range integers and Java
   BigDecimal.hashCode of the stripped value. Use this when a test must match
   JVM Clojure's hash; cljs.core/hash of a JS number stays the host hash so
   existing cljs maps do not break."
  [x]
  (cond
    (or (bigint? x) (js-bigint? x))
    (let [n (unwrap-bi x)]
      (if (bi-in-long? n) (murmur3-hash-long (bi-long n)) (bigint-jhash n)))
    (ratio? x)
    (i32 (bit-xor (bigint-jhash (unwrap-bi (numerator x)))
                  (bigint-jhash (unwrap-bi (denominator x)))))
    (decimal? x)
    (let [[u s] (canonical-unscaled-scale (unwrap-bi (unscaled-value x)) (scale x))]
      (bigdec-jhash u s))
    (integer-like? x) (murmur3-hash-long (long x))
    :else (hash x)))

(defn equiv
  "Clojure = for numbers: (= 5 5N) true, (= 1 1M) false, (= 1.5M 1.50M)
   true, (= 1/2 0.5) false, (= 1 1.0) false."
  [a b]
  (cond
    (identical? a b) true
    (and (integer-like? a) (integer-like? b))
    (bi= (unwrap-bi a) (unwrap-bi b))
    (and (ratio? a) (ratio? b))
    (and (bi= (unwrap-bi (numerator a)) (unwrap-bi (numerator b)))
         (bi= (unwrap-bi (denominator a)) (unwrap-bi (denominator b))))
    (and (decimal? a) (decimal? b))
    (let [x (canonical-unscaled-scale (unwrap-bi (unscaled-value a)) (scale a))
          y (canonical-unscaled-scale (unwrap-bi (unscaled-value b)) (scale b))]
      (and (bi= (nth x 0) (nth y 0)) (= (nth x 1) (nth y 1))))
    ;; typetags never equal a different typetag or a double
    (or (ratio? a) (ratio? b) (decimal? a) (decimal? b)
        (bigint? a) (bigint? b) (js-bigint? a) (js-bigint? b))
    false
    :else (= a b)))


;; ---------------------------------------------------------------------------
;; EDN print / read of 123N, 1/3, 1.2300M (and the LispReader integer arms:
;; a decimal or radix token past a long becomes a bigint, a trailing M a
;; decimal). One function for every host; install! hooks the host reader and
;; printer so pr-str / read-string match Clojure where the host lets us.
;; ---------------------------------------------------------------------------

(defn str-number
  "Clojure str: digits of a bigint (no N), n/d of a ratio, BigDecimal.toString
   of a decimal (no M)."
  [x]
  (cond
    (or (bigint? x) (js-bigint? x)) (bi-str (unwrap-bi x))
    (ratio? x) (str (bi-str (unwrap-bi (numerator x))) "/" (bi-str (unwrap-bi (denominator x))))
    (decimal? x) (decimal-layout (unwrap-bi (unscaled-value x)) (scale x))
    :else (str x)))

(defn pr-number
  "Clojure pr-str of an exact number: 123N, 1/3, 1.2300M (scale preserved).
   Use this for eve.db.ds serialize's [1 <EDN text>] payload, on every host."
  [x]
  (cond
    (or (bigint? x) (js-bigint? x)) (str (str-number x) "N")
    (ratio? x) (str-number x)
    (decimal? x) (str (str-number x) "M")
    :else (pr-str x)))

(defn host-pr-number
  "What this host's printer emits for an exact number. JVM and bb: Clojure
   tokens. nbb / cljs, basilisp and BEAM (ratio/decimal): the tagged forms
   #eve/N, #eve/ratio, #eve/M, so the host reader can round-trip them."
  [x]
  #?(:cljs (cond
             (or (bigint? x) (js-bigint? x)) (tagged-form "eve/N" (str-number x))
             (ratio? x) (tagged-form "eve/ratio" (str-number x))
             (decimal? x) (tagged-form "eve/M" (str-number x))
             :else (pr-str x))
     :lpy (cond
            (or (bigint? x) (js-bigint? x)) (tagged-form "eve/N" (str-number x))
            (ratio? x) (tagged-form "eve/ratio" (str-number x))
            (decimal? x) (tagged-form "eve/M" (str-number x))
            :else (pr-str x))
     :clje (cond
             (or (bigint? x) (js-bigint? x)) (tagged-form "eve/N" (str-number x))
             (ratio? x) (tagged-form "eve/ratio" (str-number x))
             (decimal? x) (tagged-form "eve/M" (str-number x))
             :else (pr-str x))
     :default (pr-number x)))

(defn- char-code
  "Unicode code point of a character (basilisp (int ch) is int(str))."
  [ch]
  #?(:cljs (.charCodeAt (str ch) 0)
     :lpy (python/ord (str ch))
     :default (int ch)))

(defn- digit-val [ch radix]
  (let [c (char-code ch)]
    (cond
      (and (>= c 48) (<= c 57)) (let [d (- c 48)] (when (< d radix) d))
      (and (>= c 97) (<= c 122)) (let [d (+ 10 (- c 97))] (when (< d radix) d))
      (and (>= c 65) (<= c 90)) (let [d (+ 10 (- c 65))] (when (< d radix) d))
      :else nil)))

(defn- parse-int-body
  "Integer digits of s in radix, as a host bigint magnitude. nil if a char is
   not a digit of that radix."
  [s radix]
  (loop [i 0 acc (bi-from 0)]
    (if (>= i (count s))
      acc
      (if-let [d (digit-val (nth s i) radix)]
        (recur (inc i) (bi+ (bi* acc (bi-from radix)) (bi-from d)))
        :eve.numeric/invalid))))

(defn- apply-sign [neg? n]
  (if neg? (bi-neg n) n))

(defn- finish-int
  "A signed integer: typetag bigint if as-bigint? or past a long, else a long."
  [neg? n as-bigint?]
  (let [n (apply-sign neg? n)]
    (if (or as-bigint? (not (bi-in-long? n)))
      (wrap-bigint n)
      (bi-long n))))

(defn- parse-exponent [s]
  (let [neg? (identical? (first s) \-)
        pos? (identical? (first s) \+)
        body (if (or neg? pos?) (subs s 1) s)]
    (when (re-matches #"\d+" body)
      (let [v #?(:cljs (js/parseInt body 10) :lpy (python/int body) :clje (read-string body) :default (Long/parseLong body))]
        (if neg? (- v) v)))))

(defn- split-decimal-significand
  "Returns [digit-string frac-digits] for a significand such as \"1.2300\" or
   \"100\" (frac-digits is the count of digits after the point, 0 if none)."
  [s]
  (if-let [i (str/index-of s \.)]
    (let [a (subs s 0 i) b (subs s (inc i))]
      (when (and (re-matches #"\d*" a) (re-matches #"\d+" b))
        [(str (if (= a "") "0" a) b) (count b)]))
    (when (re-matches #"\d+" s)
      [s 0])))

(defn- make-dec-from-parts [neg? digits frac-digits exp]
  (let [exp (or exp 0)
        scale (- frac-digits exp)
        ;; drop leading zeros but keep the last
        digits (or (second (re-matches #"^0+(.*)$" digits)) digits)
        digits (if (= digits "") "0" digits)
        u (bi-from digits)
        u (if neg? (bi-neg u) u)]
    (make-bigdec u scale)))

(defn read-bigdec-significand
  "Read a BigDecimal significand with optional exponent (no trailing M)."
  [s]
  (let [s (str s)
        neg? (identical? (first s) \-)
        s (cond (identical? (first s) \-) (subs s 1)
                (identical? (first s) \+) (subs s 1)
                :else s)
        e-idx (or (str/index-of s \e) (str/index-of s \E))]
    (if e-idx
      (let [sig (subs s 0 e-idx)
            exp (parse-exponent (subs s (inc e-idx)))
            parts (split-decimal-significand sig)]
        (when (and parts exp)
          (make-dec-from-parts neg? (nth parts 0) (nth parts 1) exp)))
      (when-let [parts (split-decimal-significand s)]
        (make-dec-from-parts neg? (nth parts 0) (nth parts 1) 0)))))

(defn- ratio-token? [s]
  (re-matches #"[+-]?\d+/[+-]?\d+" s))

(defn- parse-ratio-token [s]
  (let [[_ a b] (re-matches #"([+-]?\d+)/([+-]?\d+)" s)]
    (when (and a b)
      (reduce-ratio (bi-from a) (bi-from b)))))

(defn- radix-prefix
  "If s (no sign) starts with a radix prefix, [radix body] else nil.
   0x / 0X is 16, 0 then octal digits is 8, Nr / nR is radix N (2..36)."
  [s]
  (cond
    (or (or (str/starts-with? s "0x") (str/starts-with? s "0X")))
    [16 (subs s 2)]
    (re-matches #"[0-9]+[rR].*" s)
    (let [[_ rs body] (re-matches #"([0-9]+)[rR](.*)" s)
          r #?(:cljs (js/parseInt rs 10) :lpy (python/int rs) :clje (read-string rs) :default (Long/parseLong rs))]
      (when (and (>= r 2) (<= r 36)) [r body]))
    (and (> (count s) 1) (identical? (first s) \0)
         (re-matches #"0[0-7]+N?" s))
    [8 (let [t (if (identical? (last s) \N) (subs s 1 (dec (count s))) (subs s 1))]
         t)]
    :else nil))

(defn read-number
  "Read one Clojure number token: 123N, 1/3, 1.2300M, 0xff, 2r1010, a decimal
   integer past a long (9223372036854775808), or a double (1e20). Returns nil
   if s is not a number token."
  [s]
  (let [s (str s)]
    (cond
      (ratio-token? s) (parse-ratio-token s)
      (and (pos? (count s)) (identical? (last s) \M)
           (re-matches #"[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?M" s))
      (read-bigdec-significand (subs s 0 (dec (count s))))
      :else
      (let [neg? (identical? (first s) \-)
            plus? (identical? (first s) \+)
            body (if (or neg? plus?) (subs s 1) s)
            as-n? (and (pos? (count body)) (identical? (last body) \N))
            int-body (if as-n? (subs body 0 (dec (count body))) body)]
        (cond
          ;; float / double (but not a bare integer)
          (and (not as-n?)
               (re-matches #"(\d+\.\d*|\.\d+|\d+)([eE][+-]?\d+)" body))
          #?(:cljs (js/parseFloat (str (when neg? "-") body))
             :lpy (python/float (str (when neg? "-") body))
             :default (Double/parseDouble (str (when neg? "-") body)))
          (and (not as-n?) (re-matches #"(\d+\.\d*|\.\d+)" body))
          #?(:cljs (js/parseFloat (str (when neg? "-") body))
             :lpy (python/float (str (when neg? "-") body))
             :default (Double/parseDouble (str (when neg? "-") body)))
          :else
          (if-let [[radix digits] (radix-prefix int-body)]
            (let [n (parse-int-body digits radix)]
              (when-not (= n :eve.numeric/invalid)
                (finish-int neg? n as-n?)))
            (when (re-matches #"\d+" int-body)
              (finish-int neg? (parse-int-body int-body 10) as-n?))))))))

(defn- scan-edn-numbers
  "Replace number tokens outside strings with #eve.numeric/n \"TOKEN\", so a
   host EDN reader can reconstruct them through read-number."
  [s]
  (let [n (count s)]
    (loop [i 0 in-str? false escaped? false acc (transient [])]
      (if (>= i n)
        (apply str (persistent! acc))
        (let [ch (nth s i)]
          (cond
            escaped? (recur (inc i) in-str? false (conj! acc ch))
            in-str? (recur (inc i) (if (identical? ch \") false true)
                           (identical? ch \\) (conj! acc ch))
            (identical? ch \") (recur (inc i) true false (conj! acc ch))
            (identical? ch \;) (let [j (or (str/index-of s "\n" i) n)]
                                 (recur j false false (conj! acc (subs s i j))))
            (identical? ch \\) (recur (+ i 2) false false
                                      (cond-> (conj! acc ch)
                                        (< (inc i) n) (conj! (nth s (inc i)))))
            (and (or (identical? ch \-) (identical? ch \+) (digit-val ch 10)
                     (identical? ch \.))
                 (or (zero? i)
                     (let [p (nth s (dec i))]
                       (or (identical? p \space) (identical? p \tab)
                           (identical? p \newline) (identical? p \return)
                           (identical? p \() (identical? p \[) (identical? p \{)
                           (identical? p \,) (identical? p \")
                           (identical? p \') (identical? p \`)
                           (identical? p \@) (identical? p \~)
                           (identical? p \^)))))
            (let [tok (loop [j i]
                        (if (>= j n)
                          (subs s i j)
                          (let [c (nth s j)]
                            (if (or (digit-val c 36) (identical? c \.)
                                    (identical? c \/) (identical? c \+)
                                    (identical? c \-) (identical? c \N)
                                    (identical? c \M) (identical? c \e)
                                    (identical? c \E) (identical? c \r)
                                    (identical? c \R) (identical? c \x)
                                    (identical? c \X))
                              (recur (inc j))
                              (subs s i j)))))]
              (if (and (pos? (count tok)) (read-number tok))
                (recur (+ i (count tok)) false false
                       (conj! acc (str "#eve.numeric/n \"" tok "\"")))
                (recur (inc i) false false (conj! acc ch))))
            :else (recur (inc i) false false (conj! acc ch))))))))

(defonce ^:private host-read-string* (atom nil))

(def ^:private number-reader
  (fn [tok] (or (read-number tok)
                (throw (#?(:cljs js/Error. :default ex-info)
                        (str "Not a number token: " tok)
                        #?(:default {:token tok} :cljs nil))))))

(defn- read-tagged-N [form]
  (bigint (if (string? form) form (str form))))

(defn- read-tagged-ratio [form]
  (cond
    (string? form) (or (read-number form)
                       (throw (#?(:cljs js/Error. :default ex-info)
                               (str "Not a ratio: " form)
                               #?(:default {:form form} :cljs nil))))
    (and (sequential? form) (= 2 (count form))) (ratio (first form) (second form))
    :else (throw (#?(:cljs js/Error. :default ex-info)
                  (str "eve/ratio expects \"n/d\" or [n d], got " form)
                  #?(:default {:form form} :cljs nil)))))

(defn- read-tagged-M [form]
  (bigdec (if (string? form) form (str form))))

;; Declarations only — the live tag -> reader table is
;; eve.deftype.registry/data-readers (per-type :type-io entries, the same
;; shape e1b uses). Do not add a second registry atom here.
(def ^:private exact-io
  [["eve.numeric/bigint" 'eve/N read-tagged-N]
   ["eve.numeric/ratio" 'eve/ratio read-tagged-ratio]
   ["eve.numeric/bigdec" 'eve/M read-tagged-M]
   ["eve.numeric/number-token" 'eve.numeric/n number-reader]])

(def ^:private exact-print-info
  {:mode :custom :fn host-pr-number})

(defn- register-exact-io!
  "Hang #eve/N, #eve/ratio, #eve/M and the pr-number rewrite tag on
   eve.deftype.registry's per-type entries. This namespace must not
   :require the registry at ns load (the registry requires eve.codec,
   which requires eve.numeric on cljs). On cljs we only register once
   the registry ns is already loaded; on the JVM/bb/basilisp we can
   require it here. Safe and idempotent — call again after the
   registry appears."
  []
  (let [go! (fn []
              (when-let [reg! (resolve 'eve.deftype.registry/register-type!)]
                (doseq [[type-key tag read-fn] exact-io]
                  (reg! type-key
                        {:type-key type-key
                         :type-io {:tag tag :read read-fn :print exact-print-info}}))))]
    #?(:cljs (when (find-ns 'eve.deftype.registry) (go!))
       :lpy (try (require 'eve.deftype.registry) (go!) (catch python/Exception _))
       :default (try (require 'eve.deftype.registry) (go!) (catch Throwable _)))))

(defn- exact-tag-readers
  "Readers for this ns's tags, derived from the deftype registry when
   those entries are installed."
  []
  (register-exact-io!)
  (let [local (into {} (map (fn [[_ tag read-fn]] [tag read-fn]) exact-io))]
    (if-let [dr (resolve 'eve.deftype.registry/data-readers)]
      (merge local (select-keys (dr) (keys local)))
      local)))


(defn read-edn
  "Read EDN s, honoring Clojure number tokens on every host. A single number
   token is read directly (what eve.db.ds serialize's [1 <EDN text>] thaw
   needs). A larger form is rewritten to tagged literals and handed to the
   host EDN reader with an eve.numeric/n tag."
  ([s] (read-edn s {}))
  ([s opts]
   (let [s (str s)
         t (str/trim s)]
     (if-let [n (and (not (or (str/starts-with? t "{") (str/starts-with? t "[")
                              (str/starts-with? t "(") (str/starts-with? t "#")
                              (str/starts-with? t "\"") (str/starts-with? t ":")))
                     (read-number t))]
       n
       (let [rewritten (scan-edn-numbers s)
             readers (merge (exact-tag-readers)
                            (:readers opts)
                            {'eve.numeric/n number-reader})
             host @host-read-string*]
         (if host
           #?(:cljs (host (assoc opts :readers readers) rewritten)
              :lpy (host rewritten (assoc opts :readers readers))
              :default (host (assoc opts :readers readers) rewritten))
           #?(:cljs (cljs-reader/read-string (assoc opts :readers readers) rewritten)
              :lpy (edn/read-string rewritten (assoc opts :readers readers))
              :default (edn/read-string (assoc opts :readers readers) rewritten))))))))

(defn- write-number-to [w x]
  #?(:cljs (enc/write! w (pr-number x))
     :clje (enc/write! w (pr-number x))
     :lpy (enc/write! w (pr-number x))
     :clj (.write ^java.io.Writer w ^String (pr-number x))
     :default nil))

(defonce ^:private installed* (atom false))

(defn install!
  "Hook the host printer and EDN reader so pr-str / clojure.edn/read-string
   (and cljs.reader/read-string) speak Clojure's exact-number tokens. Safe to
   call more than once. Loaded automatically with this namespace.

   Tag readers hang off eve.deftype.registry (per-type :type-io, the
   shape e1b uses); this is also the hook e1's deftype :print / :read
   wiring should share. Lexical 123N / 1/3 / 1.5M are not tagged
   literals — use pr-number / read-number for those. Do not add a
   per-host print-method in user code."
  []
  (register-exact-io!)
  (when (compare-and-set! installed* false true)
    #?(:cljs
       (do
         ;; (= 5 5N) in either order: keep cljs (= 1 1.0) (do not change it).
         ;; nbb/SCI cannot extend IEquiv onto number; tests use equiv.
         (try
           (extend-type number
             IEquiv
             (-equiv [x y]
               (or (identical? x y)
                   (and (number? y)
                        (or (identical? x y)
                            (and (js/Number.isNaN x) (js/Number.isNaN y))
                            (cljs.core/== x y)))
                   (and (or (bigint? y) (js-bigint? y) (eve-bigint-type? y))
                        (js/Number.isInteger x)
                        (bi= (js/BigInt x) (unwrap-bi y))))))
           (catch :default _ nil))
         (when (exists? js/BigInt)
           (try
             (let [proto (js/Object.getPrototypeOf (js/BigInt 0))]
               (set! (.-cljs$core$IPrintWithWriter$ proto) true)
               (aset proto "cljs$core$IPrintWithWriter$_pr_writer$arity$3"
                     (fn [this w opts] (enc/write! w (host-pr-number this)))))
             (catch :default _ nil)))
         ;; nbb's cljs.reader has only read-string until eve.types interns
         ;; register-tag-parser! (F-NBB-EDNREADER). Resolve so analysis
         ;; does not see a missing var. read-edn always passes :readers.
         (when-let [reg (resolve 'cljs.reader/register-tag-parser!)]
           (doseq [[tag f] (exact-tag-readers)]
             (reg tag f)))
         ;; Do not replace cljs.reader/read-string: lexical 123N / 1/3 / 1.5M
         ;; are not tag parsers, and a global rewrite would surprise other Eve
         ;; tests. read-edn / read-number remain for those tokens (serialize).
         (when-not @host-read-string*
           (reset! host-read-string* cljs-reader/read-string)))
       :lpy
       (do
         (defn- lpy-pr [o]
           (cond
             (or (decimal? o) (ratio? o) (bigint? o)) (host-pr-number o)
             (and (integer? o) (not (in-long-range-str? (str o))))
             (tagged-form "eve/N" (str o))
             :else nil))
         ;; Prefer a declared printer over Python's default.
         (when-let [reg (resolve 'basilisp.lang.obj/lrepr)]
           (doseq [t [decimal/Decimal fractions/Fraction python/int EveBigInt]]
             (try
               (.register basilisp.lang.obj/lrepr t
                          (fn [o & _] (or (lpy-pr o) (str o))))
               (catch python/Exception _ nil))))
         (when (resolve 'clojure.edn/read-string)
           (when-not @host-read-string*
             (reset! host-read-string* edn/read-string)
             (alter-var-root #'edn/read-string
                             (fn [_]
                               (fn
                                 ([s] (read-edn s))
                                 ([a b] (if (map? a) (read-edn b a) (read-edn a b)))))))))
       :clje
       ;; clojerl var roots cannot be altered (module-backed vars;
       ;; alter-var-root is unsupported). register-exact-io! above has
       ;; already installed the exact tag readers in Eve's own registry
       ;; via eve.type-readers/register!, and read-edn's rewrite path
       ;; passes them explicitly — there is no host root to merge into.
       nil
       :default
       (do
         ;; JVM / bb already print and read these tokens natively. Still
         ;; register the tagged reader used by read-edn's rewrite path.
         (when (resolve 'default-data-readers)
           (alter-var-root (var default-data-readers) merge (exact-tag-readers)))
         (alter-var-root #'*data-readers* merge (exact-tag-readers))
         (when (resolve 'clojure.edn/read-string)
           nil))))
  nil)

(install!)
