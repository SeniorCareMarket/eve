(ns eve.char-key-test
  "Char keys across hosts (eve/doc/unification/FORMAT-DECISIONS.md §4; John's
   Oct 23 2026 ruling on the register's D7 — the tagged-literal approach).

   A char is a distinct scalar on every host: the JVM's Character,
   ClojureScript's EveChar (eve.codec), denoted in text by #char \"a\". A
   char key and the one-character string of the same code are two different
   keys in one map, and the map's bytes are the same on every host, so a
   map written on the JVM is read on ClojureScript with both keys found."
  (:require [clojure.test :refer [deftest is testing]]
            #?(:cljs [cljs.reader :as edn] :default [clojure.edn :as edn])
            [clojure.string :as str]
            [eve.host :refer [aclone]]
            [eve.codec :as codec]
            [eve.deftype-proto.alloc :as alloc]))

(def a-char
  "The char a, as this host represents it."
  #?(:cljs (codec/eve-char "a") :default \a))

(def ^:private char-readers
  "The readers map that gives the 'char tag its meaning on this host."
  #?(:cljs {'char codec/eve-char}
     :default {'char (fn [s] (.charAt s 0))}))

(defn- read-value [s]
  (edn/read-string {:readers char-readers} s))

(defn- bytes->hex [b]
  #?(:cljs (str/join (map (fn [i] (let [h (.toString (aget b i) 16)] (if (< (aget b i) 16) (str "0" h) h)))
                          (range (.-length b))))
     :default (str/join (map #(let [x (bit-and % 0xFF)]
                                (str (nth "0123456789abcdef" (quot x 16)) (nth "0123456789abcdef" (mod x 16))))
                             b))))

(defn- hex->bytes [s]
  (let [n (quot (count s) 2)
        digit (fn [c] (str/index-of "0123456789abcdef" (str c)))
        vals (map #(+ (* 16 (digit (nth s (* 2 %)))) (digit (nth s (inc (* 2 %))))) (range n))]
    #?(:cljs (js/Uint8Array. (to-array vals))
       :default (byte-array (map #(unchecked-byte %) vals)))))

(def two-key-map
  "One map holding both the char a (→ 1) and the string \"a\" (→ 2)."
  #?(:cljs {(codec/eve-char "a") 1 "a" 2}
     :default {\a 1 "a" 2}))

(def two-key-map-hex
  "two-key-map's flat encoding: the same bytes on every host (the string
   entry sorts first — its tag 0x05 precedes the char tag 0x14)."
  (str "eedbed02000000"
       "05000000" "eedb050161" "07000000" "eedb0302000000"
       "05000000" "eedb146100" "07000000" "eedb0301000000"))

(deftest char-tag-reads-as-a-char
  (testing "#char denotes this host's char, never the one-character string"
    (is (= a-char (read-value "#char \"a\"")))
    (is (not= "a" (read-value "#char \"a\"")))))

(deftest char-print-read-round-trip
  (testing "a char prints so the host's reader reads it back as the same char"
    #?(:cljs (is (= "#char \"a\"" (pr-str a-char)))
       :default (is (= "\\a" (pr-str a-char))))
    #?(:cljs (is (= a-char (read-value (pr-str a-char))))
       :default (is (= a-char (read-string (pr-str a-char)))))))

(deftest char-hash-is-the-hamt-hash-of-its-key-bytes
  (testing "on ClojureScript, (hash char) is eve's hash of the char's
            canonical key bytes — the fixture row's pinned hash (§5)"
    #?(:cljs (is (= -396117810 (hash a-char))))))

(deftest char-key-bytes
  (testing "a char encodes as the char tag, a one-character string as a string"
    (is (= "eedb146100" (bytes->hex (aclone (codec/encode-key a-char)))))
    (is (= "eedb050161" (bytes->hex (aclone (codec/encode-key "a")))))))

(deftest char-and-string-keys-are-distinct
  (testing "both keys coexist in one map, byte-identical on every host"
    (is (= two-key-map-hex (bytes->hex (aclone (codec/encode-key two-key-map))))))
  (testing "a map decoded from those bytes finds each key by its own key"
    (doseq [bs [(aclone (codec/encode-key two-key-map))
                (hex->bytes two-key-map-hex)]]
      (let [m (codec/decode (alloc/current-sio) bs)]
        (is (= 2 (count m)))
        (is (= 1 (get m a-char)))
        (is (= 2 (get m "a")))))))

(deftest decode-after-aclone
  (testing "decode accepts the bytes as a host aclone hands them back —
            on ClojureScript aclone element-copies a Uint8Array into a
            plain JS array, whose missing .buffer made the flat arms'
            DataView construction throw (register #64)"
    (is (= two-key-map
           (codec/decode (alloc/current-sio) (aclone (codec/encode-key two-key-map)))))
    (is (= [1 2]
           (codec/decode (alloc/current-sio) (aclone (codec/encode-key [1 2])))))
    (is (= "a"
           (codec/decode (alloc/current-sio) (aclone (codec/encode-key "a")))))))

(deftest char-as-a-value-round-trips
  (testing "a char as a value (not a key) encodes inline and decodes back"
    (let [bs (aclone (codec/encode-value (alloc/current-sio) a-char))]
      (is (= "eedb146100" (bytes->hex bs)))
      (is (= a-char (codec/decode (alloc/current-sio) bs))))))
