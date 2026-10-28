(ns clojure.edn
  "clojure.edn on cherry (part of its core completion, adapters/completion/cherry: cherry ships no reader): read-string
   over EDN's grammar — nil, booleans, numbers (integers, hex, decimals, exponents, an N or M suffix dropped, a ratio
   its quotient, ##Inf ##-Inf ##NaN), strings, characters, keywords, symbols, lists, vectors, maps (an array map up to
   eight entries, as ClojureScript's reader), sets, ;-comments, #_ discards — and tagged values through opts' :readers
   and :default, #inst (a js/Date) and #uuid built in. read takes a string: cherry has no PushbackReader."
  (:require [clojure.string :as str]))

(defn- fail [st msg] (throw (ex-info (str "EDN: " msg " at " @(:i st)) {:type :reader-exception})))

(defn- ch [st]
  (let [i @(:i st)] (when (< i (:n st)) (.charAt (:s st) i))))

(defn- take! [st]
  (let [c (ch st)] (vreset! (:i st) (inc @(:i st))) c))

(def ^:private WS #{" " "," "\n" "\t" "\r" "\f"})
(def ^:private DELIM #{"(" ")" "[" "]" "{" "}" "\"" ";"})

(defn- skip! [st]
  (loop []
    (let [c (ch st)]
      (cond (contains? WS c) (do (take! st) (recur))
            (= c ";") (do (loop [] (let [d (take! st)] (when (and d (not= d "\n")) (recur)))) (recur))
            :else nil))))

(defn- token! [st]
  (let [s (:s st) i0 @(:i st)]
    (loop [] (let [c (ch st)] (when (and c (not (contains? WS c)) (not (contains? DELIM c))) (take! st) (recur))))
    (subs s i0 @(:i st))))

(defn- number [t]
  (cond (re-matches #"[+-]?\d+N?" t) (js/parseInt (str/replace t "N" "") 10)
        (re-matches #"[+-]?0[xX][0-9a-fA-F]+N?" t)
        (let [neg (str/starts-with? t "-") h (str/replace (str/replace t #"^[+-]?0[xX]" "") "N" "")]
          (* (if neg -1 1) (js/parseInt h 16)))
        (re-matches #"[+-]?\d+/\d+" t) (let [[n d] (str/split t #"/")] (/ (js/parseInt n 10) (js/parseInt d 10)))
        (re-matches #"[+-]?\d+(\.\d*)?([eE][+-]?\d+)?M?" t) (js/parseFloat (str/replace t "M" ""))))

(defn- atom-of [st t]
  (cond (= t "nil") nil
        (= t "true") true
        (= t "false") false
        (re-find #"^[+-]?\d" t) (let [n (number t)] (if (nil? n) (fail st (str "invalid number " t)) n))
        (str/starts-with? t "::") (fail st (str "no namespace to resolve " t))
        (str/starts-with? t ":") (if (= t ":") (fail st "a lone :") (keyword (subs t 1)))
        :else (symbol t)))

(def ^:private CHARS {"newline" "\n" "space" " " "tab" "\t" "return" "\r" "backspace" "\b" "formfeed" "\f"})

(defn- character [st]
  (let [t (str (take! st) (token! st))]
    (cond (= 1 (count t)) t
          (contains? CHARS t) (get CHARS t)
          (re-matches #"u[0-9a-fA-F]{4}" t) (.fromCharCode js/String (js/parseInt (subs t 1) 16))
          :else (fail st (str "unsupported character \\" t)))))

(def ^:private ESC {"t" "\t" "n" "\n" "r" "\r" "b" "\b" "f" "\f" "\\" "\\" "\"" "\"" "'" "'"})

(defn- string [st]
  (loop [acc []]
    (let [c (take! st)]
      (cond (nil? c) (fail st "EOF in a string")
            (= c "\"") (str/join acc)
            (= c "\\") (let [e (take! st)]
                         (cond (contains? ESC e) (recur (conj acc (get ESC e)))
                               (= e "u") (let [h (subs (:s st) @(:i st) (+ 4 @(:i st)))]
                                           (vreset! (:i st) (+ 4 @(:i st)))
                                           (recur (conj acc (.fromCharCode js/String (js/parseInt h 16)))))
                               :else (fail st (str "unsupported escape \\" e))))
            :else (recur (conj acc c))))))

(declare form)

(defn- forms-until [st close]
  (loop [acc []]
    (skip! st)
    (let [c (ch st)]
      (cond (nil? c) (fail st (str "EOF, expected " close))
            (= c close) (do (take! st) acc)
            :else (let [f (form st)] (recur (if (identical? f st) acc (conj acc f))))))))

(defn- a-map [st xs]
  (when (odd? (count xs)) (fail st "a map with an odd number of forms"))
  (if (<= (count xs) 16) (apply array-map xs) (apply hash-map xs)))

(defn- tagged [st tag v]
  (let [{:keys [readers default]} (:opts st)]
    (if-let [f (get readers tag)]
      (f v)
      (case (str tag)
        "inst" (js/Date. v)
        "uuid" (uuid v)
        (if default (default tag v) (fail st (str "no reader function for tag " tag)))))))

(defn- dispatch [st]
  (let [c (ch st)]
    (cond (= c "{") (do (take! st) (set (forms-until st "}")))
          (= c "_") (do (take! st) (form st) st)
          (= c "#") (do (take! st)
                        (case (token! st) "Inf" js/Infinity "-Inf" (- js/Infinity) "NaN" js/NaN (fail st "unknown ## value")))
          :else (let [tag (form st)]
                  (when-not (symbol? tag) (fail st "a tag must be a symbol"))
                  (tagged st tag (form st))))))

(defn- form
  "the next form; st itself for a discarded one (#_)"
  [st]
  (skip! st)
  (let [c (take! st)]
    (cond (nil? c) (fail st "EOF")
          (= c "(") (apply list (forms-until st ")"))
          (= c "[") (forms-until st "]")
          (= c "{") (a-map st (forms-until st "}"))
          (= c "\"") (string st)
          (= c "\\") (character st)
          (= c "#") (dispatch st)
          (contains? #{")" "]" "}"} c) (fail st (str "unmatched " c))
          :else (do (vreset! (:i st) (dec @(:i st))) (atom-of st (token! st))))))

(defn read-string
  "One object from string s, as EDN; nil for a blank string."
  ([s] (read-string {} s))
  ([opts s]
   (when-not (str/blank? s)
     (let [st {:s s :n (count s) :i (volatile! 0) :opts opts}]
       (loop []
         (let [f (form st)]
           (if (identical? f st) (do (skip! st) (if (ch st) (recur) nil)) f)))))))

(defn read
  "One object from string s, as EDN (cherry has no PushbackReader)."
  ([s] (read-string s))
  ([opts s] (read-string opts s)))
