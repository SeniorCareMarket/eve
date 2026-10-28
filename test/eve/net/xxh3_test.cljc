(ns eve.net.xxh3-test
  "Unit 4.1 (eve/doc/network-atoms/IMPL-PLAN.md §6.1): the portable XXH3-128 against the VSv2 kernel — its two KATs, the
   311 vectors eve/scripts/gen-xxh3-vectors.py drew from kernel.wasm's own xxh3_128 (every length 0..300 and long inputs
   to 10,000 bytes: each path), and the fixture corpus's key-hash pairs (the hashes the kernel's map nodes carry)."
  (:require [clojure.test :refer [deftest is testing]]
            #?@(:glj [] :default [[clojure.edn :as edn]])
            [eve.net.bytes :as b]
            [eve.net.xxh3 :as x]))

;; glojure has no clojure.edn: its core read-string reads these fixtures
(def ^:private read-edn #?(:glj read-string :default edn/read-string))

(defn- fixture [f]
  (let [path (str "test/eve/fixtures/" f)]
    (read-edn #?(:cljs (.readFileSync (js/require "fs") path "utf8") :default (slurp path)))))

(deftest the-kernel-kats
  (is (= "99aa06d3014798d86001c324468d497f" (x/xxh3-128-hex [])))
  (is (= "06b05ab6733a618578af5f94892f3950" (x/xxh3-128-hex (b/utf8 "abc")))))

(deftest every-path-against-the-kernel
  (let [data (fn [n] (mapv (fn [i] (mod (+ (* i 31) (* n 7)) 256)) (range n)))]
    (doseq [{:keys [len hash]} (fixture "xxh3-vectors.edn")]
      (is (= hash (x/xxh3-128-hex (data len))) (str "length " len)))))

(deftest the-corpus-key-pairs
  (testing "the map key hashes of the G1 corpus (vsv2-blocks.edn :keys)"
    (let [rows (fixture "vsv2-blocks.edn")
          pairs (distinct (mapcat :keys rows))]
      (is (< 300 (count pairs)))
      (doseq [{k :key h :hash} pairs]
        (is (= h (x/xxh3-128-hex (b/unhex k))) k)))))

(deftest the-digest-is-big-endian-hi-then-lo
  (is (= 16 (count (x/xxh3-128 (b/utf8 "abc")))))
  (is (= "06b05ab6733a618578af5f94892f3950" (b/hex (x/xxh3-128 (b/utf8 "abc"))))))
