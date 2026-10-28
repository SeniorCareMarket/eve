(ns eve.net.poseidon2-test
  "aegis's primitives in eve's crypto feature, held to the
   kernel's own answers — test/eve/fixtures/aegis-kernel-vectors.edn, read from the committed kernel.wasm: the Poseidon2 permutation, the 2-to-1 hash, bytes → field, the
   48-byte POSEIDON_384 digest, the sidecar leaf, the depth-20 root and an opening. Plus permutation.nr test_3's first
   vector and the reference's hash vectors."
  (:require [clojure.test :refer [deftest is testing]]
            #?@(:glj [] :default [[clojure.edn :as edn]])
            [eve.net.bytes :as b]
            [eve.net.bn254 :as f]
            [eve.net.poseidon2 :as p]
            [eve.net.merkle :as m]))

(def ^:private read-edn #?(:glj read-string :default edn/read-string))

(def ^:private rows
  (let [path "test/eve/fixtures/aegis-kernel-vectors.edn"]
    (read-edn #?(:cljs (.readFileSync (js/require "fs") path "utf8") :default (slurp path)))))

(defn- fe "a field from 32-byte little-endian hex" [h] (f/from-bytes-le (b/unhex h)))
(defn- hexle "32-byte little-endian hex of a field" [x] (b/hex (f/to-bytes-le x)))
(defn- pat "the generator's byte pattern" [n seed] (mapv (fn [i] (mod (+ (* i 37) seed) 256)) (range n)))
(defn- of-kind [k] (filter (fn [r] (= k (:kind r))) rows))

(deftest published-vectors
  (testing "permutation.nr test_3: t3([0, 1, 2])"
    (is (= ["0bb61d24daca55eebcb1929a82650f328134334da98ea4f847f760054f4a3033"
            "303b6f7c86d043bfcbcc80214f26a30277a15d3f74ca654992defe7ff8d03570"
            "1ed25194542b12eef8617361c3ba7c52e660b145994427cc86296242cf766ec8"]
           (mapv (fn [x] (b/hex (f/to-bytes-be x))) (p/t3 [(f/from-long 0) (f/from-long 1) (f/from-long 2)])))))
  (testing "the reference's hash vectors"
    (is (= "2afac3bdc3663b71eefeecdf21b147d0ba7dd7a169a7757c05ed6bfb065bffd2"
           (b/hex (f/to-bytes-be (p/hash (f/from-long 1) (f/from-long 2))))))
    (is (= "2ed1da00b14d635bd35b88ab49390d5c13c90da7e9e3a5f1ea69cd87a0aa3e82"
           (b/hex (f/to-bytes-be (p/hash (f/from-long 0) (f/from-long 0))))))))

(deftest the-kernel's-permutation-and-hash
  (doseq [r (of-kind :t3)] (is (= (:out r) (mapv hexle (p/t3 (mapv fe (:in r)))))))
  (doseq [r (of-kind :hash)] (is (= (:out r) (hexle (p/hash (fe (:a r)) (fe (:b r))))))))

(deftest the-kernel's-bytes-to-field-and-digest
  (doseq [r (of-kind :bytes)]
    (testing (str (:n r) " bytes")
      (is (= (:field r) (hexle (p/string->field (pat (:n r) (:seed r))))))
      (is (= (:digest384 r) (b/hex (p/digest384 (pat (:n r) (:seed r)))))))))

(deftest the-kernel's-sidecar-leaf
  (doseq [r (of-kind :leaf)]
    (is (= (:out r) (hexle (p/leaf (b/unhex (:kvhash r)) (b/unhex (:key r)) (b/unhex (:val r))))))))

(deftest the-kernel's-root-and-openings
  (doseq [r (of-kind :root)]
    (testing (str (count (:leaves r)) " leaves")
      (is (= 0 (:rc r)))
      (is (= (:out r) (hexle (m/root (mapv fe (:leaves r))))))))
  (doseq [r (of-kind :opening)]
    (let [leaves (mapv fe (:leaves (first (filter (fn [x] (= (:leaves r) (count (:leaves x)))) (of-kind :root)))))
          o (m/opening leaves (:index r))]
      (testing (str (:leaves r) " leaves, slot " (:index r))
        (is (= (:leaf r) (hexle (:leaf o))))
        (is (= (:directions r) (:directions o)))
        (is (= (:siblings r) (mapv hexle (:siblings o))))
        (is (= (hexle (m/root leaves)) (hexle (m/path-root o))))))))
