(ns eve.reference-tag-test
  "EVM-11c (EM5): the process-local reference tag 0x15 (eve/doc/unification/FORMAT-DECISIONS.md §9) — a value eve
   has no bytes for (a fn or closure, a regex, a var, a volatile, an atom, a host object) is stored by reference:
   [0xEE 0xDB 0x15][realm:u32LE][handle:u32LE], the handle naming it in this process's reference table. It reads
   back as the same object, by identity, in the process that wrote it; another process's bytes, or a handle this
   process never issued, are refused. And it never reaches a shared domain (C3): a value holding one written into
   an mmap domain throws, and the wire (eve.net.codec) refuses it."
  ;; eve.host's where clojerl's core lacks the name; the codec's ^bytes return hint names eve.host's bytes where it
  ;; is called (clojerl resolves it there)
  (:refer-clojure :exclude [bytes volatile!])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [field bytes volatile!]]
            [eve.codec :as codec]
            [eve.core :as e]
            [eve.deftype-proto.alloc :as alloc]
            ;; the collection writers a host map or vector is written through
            [eve.map] [eve.set] [eve.vec] [eve.list]
            [eve.net.codec :as wire]
            [eve.process :as proc]))

(defn- sio [] (alloc/heap-domain-sio! {}))

(defn- u8 [b i] (bit-and (int #?(:cljs (aget b i) :default (nth b i))) 0xFF))

(defn- blen [b] #?(:cljs (field b length) :default (count b)))

(defn- round-trip [v] (let [s (sio)] (codec/decode s (codec/encode-value s v))))

(defn- in-map [k v] (let [s (sio)] (codec/decode s (codec/encode-value s {k v}))))

(defn- closure [n] (fn [x] (+ x n)))

(deftest a-closure-reads-back-by-identity
  (let [f (closure 41)]
    (is (identical? f (round-trip f)) "inline, as a scalar value")
    (is (identical? f (e/get (in-map :f f) :f)) "as a map's value")
    (is (= 42 ((e/get (in-map :f f) :f) 1)) "and it still runs")
    (testing "a fn as a key: the same object, the same bytes, one key"
      (is (= 1 (e/get (in-map f 1) f))))
    (testing "the layout: 11 bytes, tag 0x15"
      (let [b (codec/encode-key f)]
        (is (= 11 (blen b)))
        (is (= [0xEE 0xDB 0x15] [(u8 b 0) (u8 b 1) (u8 b 2)]))))
    (testing "one object is one handle; two objects two"
      ;; on the BEAM a fun is a value: (closure 41) twice is one term, so one handle; another environment is another
      (let [g #?(:clje (closure 42) :default (closure 41))]
        #?(:clje (is (= (map #(u8 (codec/encode-key f) %) (range 11))
                        (map #(u8 (codec/encode-key (closure 41)) %) (range 11)))
                     "equal funs are one value, one handle"))
        (is (= (map #(u8 (codec/encode-key f) %) (range 11))
               (map #(u8 (codec/encode-key f) %) (range 11))))
        (is (not= (map #(u8 (codec/encode-key f) %) (range 11))
                  (map #(u8 (codec/encode-key g) %) (range 11))))))))

(deftest other-reference-values
  (doseq [[what v] [["an atom" (atom 1)] ["a volatile" (volatile! 2)] ["a regex" #"a+b"]]]
    (is (identical? v (round-trip v)) what)
    (is (identical? v (e/get (in-map :v v) :v)) what)))

(defn- with-byte [b i x]
  #?(:cljs (let [c (js/Uint8Array. b)] (aset c i x) c)
     :lpy (let [c (python/bytearray b)] (aset c i (bit-and x 0xFF)) c)
     :clje (let [c (eve.host/aclone b)] (eve.host/aset c i (eve.host/unchecked-byte x)) c)
     :default (let [c (aclone ^bytes b)] (aset-byte c i (unchecked-byte x)) c)))

(deftest a-foreign-or-unknown-reference-is-refused
  (let [f (closure 7)
        s (sio)
        b (codec/encode-key f)]
    (testing "another process's realm: the handle names nothing here"
      (is (thrown? #?(:cljs js/Error :jank nil :default Exception)
                   (codec/decode s (with-byte b 3 (bit-xor (u8 b 3) 0xFF))))))
    (testing "a handle this process never issued"
      (is (thrown? #?(:cljs js/Error :jank nil :default Exception)
                   (codec/decode s (with-byte (with-byte b 9 0xFF) 10 0x7F)))))))

(deftest the-wire-refuses-a-reference
  (testing "eve.net.codec has no image for one: :unencodable"
    (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (wire/encode {:f (closure 1)})))))

#?(:cljs nil
   :default
   (deftest an-mmap-domain-refuses-a-reference
     (let [dir (str #?(:bb (System/getProperty "java.io.tmpdir")
                       :clj (System/getProperty "java.io.tmpdir")
                       :default "/tmp")
                    "/eve-reftag-" (rand-int 1000000000))
           _ (proc/ensure-parent-dir! (str dir "/d"))
           s (alloc/mmap-domain-sio! (str dir "/d") {:create? true})
           f (closure 3)]
       (testing "C3: a value holding a reference, written into a shared domain, throws — in every position"
         (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (codec/encode-value s f)) "inline")
         (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (codec/encode-value s {:f f})) "a map's value")
         (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (codec/encode-value s {f 1})) "a map's key")
         (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (codec/encode-value s #{[1 f]})) "deep in a set's element")
         (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (codec/encode-value s [{:a [f]}])) "deep in a vector"))
       (testing "and the same domain takes the values eve has bytes for"
         (is (= "plain" (codec/decode s (codec/encode-value s "plain"))))
         (is (= 2 (e/count (codec/decode s (codec/encode-value s {:a 1 :b [1 2]})))))))))

;; Java object arrays are not Eve typed arrays. Query tuples and transaction
;; inputs may contain them; do not route them to the primitive-array writer.
#?(:bb
   (deftest object-arrays-remain-references
     (doseq [v [(to-array [1 "two" nil]) (into-array String ["one" "two"])]]
       (is (identical? v (round-trip v)))
       (is (identical? v (e/get (in-map :v v) :v)))))
   :clj
   (deftest object-arrays-remain-references
     (doseq [v [(to-array [1 "two" nil]) (into-array String ["one" "two"])]]
       (is (identical? v (round-trip v)))
       (is (identical? v (e/get (in-map :v v) :v))))))
