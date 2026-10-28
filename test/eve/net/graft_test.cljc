(ns eve.net.graft-test
  "SwapGraft V3 and the read Bloom in portable cljc: the G3 rows of VS's test/vs/net_graft_test.vs
   — SHAs of labels, epoch 7, timestamp 1700000000000, the layout by offset, the refusals, graft-possible."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.bytes :as b]
            [eve.net.graft :as g]
            [eve.net.sha256 :as s]))

(defn- sha [label] (s/sha256-hex (b/utf8 (str label))))

(deftest encode-decode-round-trips
  (let [d (g/decode (g/encode {:old-root (sha "old") :new-root (sha "new") :epoch 7 :timestamp-ms 1700000000000}))]
    (is (= [3 7 1700000000000] [(:version d) (:epoch d) (:timestamp d)]))
    (is (= [(sha "old") (sha "new")] [(:old-root d) (:new-root d)]))
    (is (= [] (:branch d) (:blobs d) (:retired d))))
  (let [br [(sha "b0") (sha "b1") (sha "b2")]
        d (g/decode (g/encode {:old-root (sha "o") :new-root (sha "n") :branch br :blobs [(sha "x")] :retired [(sha "r0")]}))]
    (is (= br (:branch d)))
    (is (= [(sha "x")] (:blobs d)))
    (is (= [(sha "r0")] (:retired d))))
  (is (= (apply str (repeat 64 "0")) (:old-root (g/decode (g/encode {:new-root (sha "n")}))))))

(deftest the-layout-by-offset
  (let [v (g/encode {:old-root (sha "old") :new-root (sha "new") :epoch 7 :timestamp-ms 1700000000000
                     :branch [(sha "b0") (sha "b1")] :blobs [(sha "x")] :retired [(sha "r0")]})]
    (is (= 228 (count v)))
    (is (= "54465247" (b/hex (subvec v 0 4))) "magic 0x47524654 as a little-endian u32 (graft.js:117)")
    (is (= [3 0] [(nth v 4) (nth v 5)]))
    (is (= 7 (b/read-u32le v 6)))
    (is (= [(mod 1700000000000 4294967296) (quot 1700000000000 4294967296)] [(b/read-u32le v 10) (b/read-u32le v 14)]))
    (is (= [0 0 0 0 0 0] (subvec v 18 24)))
    (is (= (sha "old") (b/hex (subvec v 24 56))))
    (is (= [2 1 1] [(b/read-u32le v 88) (b/read-u32le v 92) (b/read-u32le v 96)]))
    (is (= (sha "b0") (b/hex (subvec v 100 132))))
    (is (= (sha "r0") (b/hex (subvec v 196 228))))))

(deftest decode-refuses
  (let [v (g/encode {:old-root (sha "o") :new-root (sha "n") :branch [(sha "a") (sha "b")]})
        throws? (fn [x] (try (g/decode x) false (catch #?(:cljs :default :lpy python/Exception :default Exception) _ true)))]
    (is (throws? (assoc v 0 0)) "bad magic")
    (is (throws? (assoc v 4 99)) "wrong version")
    (is (throws? (subvec v 0 (dec (count v)))) "short SHA lists")
    (is (throws? (subvec v 0 50)) "truncated")))

(deftest graft-possible
  (let [winner (fn [m] (g/decode (g/encode (merge {:old-root (sha "o") :new-root (sha "n")} m))))]
    (is (true? (g/possible? (g/bloom []) (winner {:branch [(sha "b0")] :retired [(sha "r0")]}))))
    (is (true? (g/possible? (g/bloom [(sha "l0") (sha "l1")]) (winner {:branch [(sha "w0") (sha "w1")] :retired [(sha "w2")]}))))
    (is (false? (g/possible? (g/bloom [(sha "r0") (sha "shared")]) (winner {:branch [(sha "w0") (sha "shared")]}))))
    (is (false? (g/possible? (g/bloom [(sha "stale")]) (winner {:retired [(sha "stale")]}))))
    (is (true? (g/possible? nil (winner {:branch [(sha "b0")]}))))))

(deftest the-bloom-rule
  (let [k (sha "a-node") bl (g/bloom [k])
        on (count (filter (fn [i] (odd? (quot (nth bl (quot i 8)) (nth [1 2 4 8 16 32 64 128] (mod i 8))))) (range 4096)))]
    (is (= 512 (count bl)))
    (is (<= 1 on 4))
    (is (g/might-contain? bl k))
    (is (not (g/might-contain? (g/bloom [(sha "other")]) k)))))
