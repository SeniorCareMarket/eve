(ns eve.eve-bytes-fixture-test
  "The byte-fixture conformance suite (eve/doc/unification/FORMAT-DECISIONS.md §5).

   test/eve/fixtures/eve-bytes.edn pins, for each value, the EVE bytes every
   host writes for it as a HAMT key (scalars inline, collections flat and in
   canonical order) and reads back. The same file runs on the JVM
   (clojure -M:jvm-test), on bb (bb -cp src:test -e \"(require
   'eve.eve-bytes-fixture-test) (clojure.test/run-tests
   'eve.eve-bytes-fixture-test)\") and on Node (node target/eve-test/all.js
   fixtures). A row names the hosts it applies to in :hosts; a host not
   named skips it, and the skips are counted."
  ;; the host's arrays and integer casts are eve.host's (HOST-CONTRACT §10)
  (:refer-clojure :exclude [aclone alength byte-array unchecked-byte bytes])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [aclone alength byte-array unchecked-byte bytes]]
            #?(:cljs [cljs.reader :as edn] :default [clojure.edn :as edn])
            [clojure.string :as str]
            [eve.hamt-util :as hu]
            [eve.codec :as codec]
            [eve.deftype-proto.alloc :as alloc]))

(def host #?(:cljs :cljs :bb :bb :clj :clj :cljr :cljr :lpy :lpy :clje :clje))

(def fixture-path "test/eve/fixtures/eve-bytes.edn")

(defn- read-fixtures []
  #?(:lpy
     ;; basilisp 0.5.1's readers: basilisp.edn splits 1e300 into 1 and e300,
     ;; the core reader reads it as an integer (measured 2026-10-16); the core
     ;; reader, with a bare exponent written 1.0e300, reads the file (#uuid
     ;; and #inst as Python's UUID and datetime)
     (read-string (-> (slurp fixture-path)
                      ;; basilisp has no reader for the #char tag (measured:
                      ;; "No data reader found for tag #char") and no char
                      ;; type; the #char row is not basilisp's (its :hosts
                      ;; exclude it), so the tag is dropped in the text read
                      ;; here and the payload stands as a one-character string
                      (str/replace "#char " "")
                      (str/replace #":value (-?\d+)e(\d+)"
                                   ;; basilisp hands the function the matched text
                                   (fn [s] (let [[_ m e] (re-matches #":value (-?\d+)e(\d+)" s)]
                                             (str ":value " m ".0e" e))))))
     :clje
     ;; clojerl's keywords are Erlang atoms, 255 characters at most, and the
     ;; reader refuses a longer one: a row for other hosts only (one row per
     ;; line) is skipped unread, as one this host does not represent. (Its
     ;; slurp reads Latin-1 unless told otherwise.)
     (vec (keep (fn [line]
                  (when (str/starts-with? line "{")
                    (if (and (str/includes? line ":hosts #{") (not (str/includes? line ":clje")))
                      {:hosts #{}}
                      (edn/read-string line))))
                (str/split-lines (slurp fixture-path :encoding "UTF-8"))))
     :default
     (edn/read-string
      ;; clojure.edn reads #uuid and #inst itself (the JVM's UUID and Date, the
      ;; CLR's Guid and DateTime). #char is eve's own reader on both hosts: a
      ;; Character here, the codec's EveChar on ClojureScript (§4).
      #?(:cljs {:readers {'uuid uuid 'inst (fn [s] (js/Date. s)) 'char codec/eve-char}}
         ;; (first s): the host's own character — .charAt is the JVM's, and ClojureCLR's String has none
         ;; (System.MissingMethodException: both fixture tests errored there)
         :default {:readers {'char (fn [s] (first s))}})
      #?(:cljs (.readFileSync (js/require "fs") fixture-path "utf8")
         :default (slurp fixture-path)))))

(defn- bytes->hex [b]
  #?(:cljs (str/join (map (fn [i] (let [h (.toString (aget b i) 16)] (if (< (aget b i) 16) (str "0" h) h)))
                          (range (.-length b))))
     :default (str/join (map #(let [x (bit-and % 0xFF)]
                                (str (nth "0123456789abcdef" (quot x 16)) (nth "0123456789abcdef" (mod x 16))))
                             b))))

(defn- hex->bytes [s]
  (let [n (quot (count s) 2)
        ;; (str c): a char on the JVM and the CLR, a string on ClojureScript
        digit (fn [c] (str/index-of "0123456789abcdef" (str c)))
        vals (map #(+ (* 16 (digit (nth s (* 2 %)))) (digit (nth s (inc (* 2 %))))) (range n))]
    #?(:cljs (js/Uint8Array. (to-array vals))
       :default (byte-array (map #(unchecked-byte %) vals)))))

(defn- encode-key [v]
  ;; a copy: a codec may answer a scratch buffer, valid until its next call
  (aclone (codec/encode-key v)))

(defn- decode [b]
  (codec/decode (alloc/current-sio) b))

(defn- same-value?
  "Equality that holds a date by its time and NaN to itself."
  [a b]
  (cond
    (inst? a) (and (inst? b) (= (inst-ms a) (inst-ms b)))
    :else (= a b)))

(deftest eve-bytes-fixtures
  (let [rows (read-fixtures)
        mine (filter #(contains? (:hosts % #{host}) host) rows)
        skipped (- (count rows) (count mine))]
    (println (str "eve-bytes fixtures on " (name host) ": " (count mine) " rows, " skipped " skipped"))
    (doseq [{:keys [value bytes note]} mine]
      (testing (or note (pr-str value))
        (is (= bytes (bytes->hex (encode-key value)))
            (str "encode " (pr-str value)))
        (is (same-value? value (decode (hex->bytes bytes)))
            (str "decode " bytes))))))

(deftest key-hash-fixtures
  (testing "Murmur3_x86_32 over the key bytes is the HAMT hash on every host"
    (doseq [{:keys [value bytes hash]} (filter :hash (read-fixtures))]
      (is (= hash (hu/portable-hash-bytes (hex->bytes bytes)))
          (str "hash of " (pr-str value))))))

(defn- bytes-length [b] #?(:cljs (.-length b) :default (alength ^bytes b)))

(deftest long-symbol-has-no-encoding
  (testing "a symbol name or namespace past 255 UTF-8 bytes is refused on every host, never truncated (seam map L-40)"
    (is (thrown? #?(:cljs js/Error :default Exception)
                 (encode-key (symbol (apply str (repeat 300 "s"))))))
    (is (thrown? #?(:cljs js/Error :default Exception)
                 (encode-key (symbol (apply str (repeat 300 "n")) "k"))))
    (is (= 255 (bytes-length (encode-key (symbol (apply str (repeat 251 "s"))))))
        "251 bytes of name + the 4-byte header is the longest symbol")))
