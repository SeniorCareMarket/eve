(ns eve.net.json
  "JSON for the broker's small wire bodies (eve/doc/network-atoms/WIRE.md §2) in portable cljc: objects as maps with
   string keys, arrays as vectors, strings, numbers, true / false / null (nil). No host JSON library;
   a host may override `write-str` / `read-str` with its own."
  (:refer-clojure :exclude [long])
  (:require [eve.host :refer [long]]
            [eve.encoding :as e]
            [eve.net.bytes :as b]))

(def ^:private HEX "0123456789abcdef")

(defn- esc-of
  "the escape of UTF-16 unit i, or nil for a unit that stands as itself"
  [i]
  (cond (= i 34) "\\\"" (= i 92) "\\\\"
        (= i 10) "\\n" (= i 13) "\\r" (= i 9) "\\t"
        (< i 32) (str "\\u00" (nth HEX (quot i 16)) (nth HEX (mod i 16)))
        :else nil))

(defn- esc [s]
  ;; by UTF-16 unit (eve.encoding): a character's code is the host's business; a plain run is rebuilt by the host. A
  ;; string with nothing to escape — a key, a lease id, a base64 blob: most of the wire — is its own text
  (let [us (e/utf16-units s)]
    (if (not-any? esc-of us)
      s
      (loop [us (seq us) run [] out []]
        (if us
          (let [i (first us) x (esc-of i)]
            (if x
              (recur (next us) [] (conj out (b/units->str run) x))
              (recur (next us) (conj run i) out)))
          (apply str (conj out (b/units->str run))))))))

(defn write-str
  "the JSON text of v: a map (keys named: a keyword's name, else str), a sequential, a string, a number, a boolean,
   nil; a keyword value is its name"
  [v]
  (cond (nil? v) "null"
        (true? v) "true"
        (false? v) "false"
        (string? v) (str "\"" (esc v) "\"")
        (keyword? v) (write-str (name v))
        (number? v) (let [s (str v)] (if (and (= (count s) (count (str (long v)))) (== v (long v))) (str (long v)) s))
        (map? v) (str "{" (apply str (interpose "," (map (fn [[k x]] (str (write-str (if (keyword? k) (name k) (str k))) ":" (write-str x))) v))) "}")
        (sequential? v) (str "[" (apply str (interpose "," (map write-str v))) "]")
        :else (throw (ex-info (str "json: cannot write " (pr-str v)) {:value v}))))

;; The reader is total: each step answers [value next-index], or nil when the text is not JSON there — so a caller that
;; wants "nil for malformed" needs no catch, and the shared code names no host's exception class (adapters/README.md, "eve is
;; portable"). read-str turns nil into its throw. It reads the text's UTF-16 units (eve.encoding/utf16-units), integers
;; on every host, so no host's characters, string indexing or number parser is in it: a string is rebuilt from its units
;; (eve.net.bytes/units->str, a \u surrogate pair one code point), a number from its digits.

(declare -value)

(defn- at "the unit at i, or nil past the end" [us i] (when (< i (count us)) (nth us i)))

(defn- ws [us i] (loop [i i] (if (contains? #{32 9 10 13} (at us i)) (recur (inc i)) i)))

(defn- hex-unit [u]
  (cond (nil? u) nil (<= 48 u 57) (- u 48) (<= 97 u 102) (- u 87) (<= 65 u 70) (- u 55) :else nil))

(defn- hex4 [us i]
  ;; the value of the four hex digits at i, or nil
  (reduce (fn [a k] (let [d (hex-unit (at us (+ i k)))] (if (and a d) (+ (* 16 a) d) (reduced nil)))) 0 (range 4)))

(defn- -escaped-string [us i]
  ;; i at the opening quote of a string with an escape in it: [string next-index], or nil
  (loop [i (inc i) out []]
    (let [c (at us i)]
      (cond (nil? c) nil
            (= c 34) [(b/units->str out) (inc i)]
            (= c 92) (let [e (at us (inc i))]
                       (case e
                         34 (recur (+ i 2) (conj out 34))
                         92 (recur (+ i 2) (conj out 92))
                         47 (recur (+ i 2) (conj out 47))
                         98 (recur (+ i 2) (conj out 8))
                         102 (recur (+ i 2) (conj out 12))
                         110 (recur (+ i 2) (conj out 10))
                         114 (recur (+ i 2) (conj out 13))
                         116 (recur (+ i 2) (conj out 9))
                         117 (let [u (hex4 us (+ i 2))] (when u (recur (+ i 6) (conj out u))))
                         nil))
            (< c 32) nil                                  ;; a control character must be escaped
            :else (recur (inc i) (conj out c))))))

(defn- -string [us i]
  ;; i at the opening quote: [string next-index], or nil. A string with no escape in it (most of the wire) is rebuilt
  ;; from its units in one piece
  (let [j (loop [j (inc i)] (let [c (at us j)] (if (and c (not= c 34) (not= c 92) (>= c 32)) (recur (inc j)) j)))]
    (if (= 34 (at us j))
      [(b/units->str (subvec us (inc i) j)) (inc j)]
      (-escaped-string us i))))

(defn- digit [u] (when (and u (<= 48 u 57)) (- u 48)))

(defn- digits-at "[the digits' units from i, the index past them]" [us i]
  (let [j (loop [j i] (if (digit (at us j)) (recur (inc j)) j))] [(subvec us i j) j]))

(defn- fold-digits
  "the integer of decimal digit units — exact while it stays below 10^17 (a 64-bit long holds it on every host), a
   double past that"
  [ds]
  (reduce (fn [a u] (if (< a 100000000000000000) (+ (* 10 a) (digit u)) (+ (* 10.0 a) (digit u)))) 0 ds))

(defn- pow10 [e] (loop [k 0 v 1.0] (if (< k e) (recur (inc k) (* v 10.0)) v)))

(defn- -number [us i]
  ;; JSON's grammar, strictly: -? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)?
  (let [minus? (= 45 (at us i))
        i0 (if minus? (inc i) i)
        [ip i1] (digits-at us i0)
        int-ok? (and (seq ip) (or (= 1 (count ip)) (not= 48 (first ip))))
        [fp i2] (if (= 46 (at us i1)) (digits-at us (inc i1)) [nil i1])
        frac-ok? (or (nil? fp) (seq fp))
        e? (contains? #{101 69} (at us i2))
        esign (when e? (at us (inc i2)))
        es (if (contains? #{43 45} esign) (inc (inc i2)) (inc i2))
        [xp i3] (if e? (digits-at us es) [nil i2])
        exp-ok? (or (not e?) (seq xp))]
    (when (and int-ok? frac-ok? exp-ok?)
      (if (and (nil? fp) (not e?))
        (let [n (fold-digits ip)] [(if minus? (- n) n) i3])
        ;; a fraction: mantissa · 10^E, exact where both fit a double exactly (Clinger's fast path: under 2^53 and
        ;; |E| <= 22), the nearest double otherwise
        (let [m (fold-digits (into ip fp))
              x (* (if (= 45 esign) -1 1) (if xp (fold-digits xp) 0))
              ex (- x (count fp))
              v (if (neg? ex) (/ (* 1.0 m) (pow10 (- ex))) (* (* 1.0 m) (pow10 ex)))]
          [(if minus? (- v) v) i3])))))

(def ^:private TRUE [116 114 117 101])
(def ^:private FALSE [102 97 108 115 101])
(def ^:private NULL [110 117 108 108])

(defn- -literal [us i word v]
  (when (= word (subvec us i (min (count us) (+ i (count word))))) [v (+ i (count word))]))

(defn- -value [us i]
  (let [i (ws us i) c (at us i)]
    (cond
      (nil? c) nil
      (= c 123) (loop [i (ws us (inc i)) m {}]
                  (if (and (empty? m) (= 125 (at us i)))   ;; a close only for {}: {"a":1,} is not JSON
                    [m (inc i)]
                    (let [[k i :as r] (when (= 34 (at us i)) (-string us i))
                          i (when r (ws us i))
                          [v i :as r2] (when (and r (= 58 (at us i))) (-value us (inc i)))
                          i (when r2 (ws us i))]
                      (cond (nil? r2) nil
                            (= 44 (at us i)) (recur (ws us (inc i)) (assoc m k v))
                            (= 125 (at us i)) [(assoc m k v) (inc i)]
                            :else nil))))
      (= c 91) (loop [i (ws us (inc i)) v []]
                 (if (and (empty? v) (= 93 (at us i)))   ;; a close only for []: [1,] is not JSON
                   [v (inc i)]
                   (let [[x i :as r] (-value us i)
                         i (when r (ws us i))]
                     (cond (nil? r) nil
                           (= 44 (at us i)) (recur (ws us (inc i)) (conj v x))
                           (= 93 (at us i)) [(conj v x) (inc i)]
                           :else nil))))
      (= c 34) (-string us i)
      (= c 116) (-literal us i TRUE true)
      (= c 102) (-literal us i FALSE false)
      (= c 110) (-literal us i NULL nil)
      :else (-number us i))))

(defn- -read [s]
  ;; [value] for JSON text, nil for anything else (trailing text included)
  (let [us (e/utf16-units s)
        [v i :as r] (-value us 0)]
    (when (and r (= (ws us i) (count us))) [v])))

(defn read-str-or-nil
  "the value of JSON text (objects as string-keyed maps), or nil for text that is not JSON"
  [s]
  (first (-read s)))

(defn read-str
  "the value of JSON text (objects as string-keyed maps); throws on malformed text"
  [s]
  (let [r (-read s)]
    (when (nil? r) (throw (ex-info "json: malformed text" {:length (count s)})))
    (first r)))
