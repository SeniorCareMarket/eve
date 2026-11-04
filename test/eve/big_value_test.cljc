(ns eve.big-value-test
  "Big values in a HAMT node (EM5, EVM-13's measurement): a node whose kv bytes pass 65,535 caches its kv total as 0
   (unknown, scanned) — the header word is a u16. Measured 2026-10-18 before the fix: six values of 12,000
   characters in one node, then a replace of one, read 1.7 GB past the slab (IndexOutOfBoundsException)."
  ;; the codec's ^bytes return hint names eve.host's bytes where it is called (clojerl resolves it there)
  (:refer-clojure :exclude [bytes])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [bytes]]
            [eve.codec :as codec]
            [eve.core :as e]
            [eve.deftype-proto.alloc :as alloc]
            ;; the collection writers a host map or vector is written through
            [eve.map] [eve.set] [eve.vec] [eve.list]))

(defn- big [c n] (apply str (repeat n c)))

(deftest a-node-past-64-kb-of-values
  (let [s (alloc/heap-domain-sio! {})
        src (into {} (map (fn [i] [i (big (nth "abcdef" i) 12000)]) (range 6)))
        m (codec/decode s (codec/encode-value s src))
        intact? (fn [m ks] (every? (fn [i] (= (get src i) (e/get m i))) ks))]
    (is (= 6 (e/count m)))
    (is (intact? m (range 6)))
    (testing "a new key"
      (let [m2 (e/assoc m 100 "x")]
        (is (= 7 (e/count m2)))
        (is (intact? m2 (range 6)))
        (is (= "x" (e/get m2 100)))))
    (testing "a replace, small then big"
      (let [m3 (e/assoc m 3 "small")
            m4 (e/assoc m3 3 (big "z" 20000))]
        (is (intact? m3 [0 1 2 4 5]))
        (is (= "small" (e/get m3 3)))
        (is (intact? m4 [0 1 2 4 5]))
        (is (= (big "z" 20000) (e/get m4 3)))))
    (testing "a dissoc"
      (let [m5 (e/dissoc m 2)]
        (is (= 5 (e/count m5)))
        (is (intact? m5 [0 1 3 4 5]))
        (is (nil? (e/get m5 2)))))
    (testing "the source is untouched"
      (is (intact? m (range 6))))))

(defn- blen [b] #?(:cljs (.-length b) :default (count b)))
(defn- u8 [b i] (bit-and (int #?(:cljs (aget b i) :default (nth b i))) 0xFF))

(deftest a-value-out-of-line
  (testing "FORMAT-DECISIONS.md §10: past 64 bytes, [EE DB 19][block:i32][len:u32] and a scalar block"
    (let [s (alloc/heap-domain-sio! {})
          v (big "q" 1000)
          b (codec/encode-val-out-of-line s v)]
      (is (= 11 (blen b)))
      (is (= [0xEE 0xDB 0x19] [(u8 b 0) (u8 b 1) (u8 b 2)]))
      (is (= 1007 (+ (u8 b 7) (* 256 (u8 b 8)))) "the length of the 1,000-character string's encoding")
      (is (= v (codec/decode s b)))
      (testing "the boundary: a 64-byte encoding stays inline, a 65-byte one does not"
        (let [b64 (codec/encode-val-out-of-line s (big "x" 60))
              n64 (blen b64)
              b65 (codec/encode-val-out-of-line s (big "x" 61))]
          (is (= 64 n64))
          (is (= 11 (blen b65)))
          (is (= (big "x" 61) (codec/decode s b65)))))
      (testing "any value past the bound: a big integer, a flat collection key, a reference stays a pointer"
        (let [w (vec (range 30))]
          (is (= "small" (codec/decode s (codec/encode-val-out-of-line s "small"))))
          (is (= w (vec (e/seq (codec/decode s (codec/encode-val-out-of-line s w)))))))))))
