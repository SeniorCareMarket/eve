(ns eve.aegis.clsag-test
  "eve.aegis.clsag: completeness, tamper refusals, per-scope linkability, cross-scope unlinkability, the subgroup
   check on the key image; verify timings at ring sizes 8 / 16 / 32 / 64 printed (synthetic fixtures: seeds are
   SHA-512 of a counter, the signer's randomness a SHA-512 counter stream — no entropy source is needed)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.clsag :as clsag]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.net.ed25519 :as ed]
            [eve.process :as process]))

(defn- sha512 [bs] (vec (crypto/sha512 bs)))
(defn- public-key [seed] (vec (crypto/ed25519-public-key seed)))
(defn- seed [i] (subvec (sha512 (b/utf8 (str "clsag-seed-" i))) 0 32))
(defn- ring-of [n] (mapv (fn [i] (public-key (seed i))) (range n)))

(defn- counter-random "a deterministic (fn [n]) stream: SHA-512 of a label and a counter, concatenated" [label]
  (let [c (atom 0)]
    (fn [n]
      (loop [out []]
        (if (>= (count out) n) (subvec out 0 n)
            (recur (into out (sha512 (b/utf8 (str label "-" (swap! c inc)))))))))))

(def MSG (b/utf8 "vote: poll-7 option-2"))
(def SCOPE (b/utf8 "aegis.chat.public/poll-7"))

(defn- sign-with [ring index scope msg label]
  (clsag/sign {:msg msg :ring ring :seed (seed index) :index index :scope scope :random (counter-random label)}))

(deftest completeness-and-refusals
  (let [ring (ring-of 8)
        sig (sign-with ring 3 SCOPE MSG "a")]
    (is (clsag/verify {:msg MSG :ring ring :scope SCOPE :sig sig}))
    (is (= (:I sig) (clsag/key-image (seed 3) SCOPE)) "the key image is x·H_p(scope)")
    (testing "another message" (is (not (clsag/verify {:msg (conj MSG 33) :ring ring :scope SCOPE :sig sig}))))
    (testing "another scope" (is (not (clsag/verify {:msg MSG :ring ring :scope (conj SCOPE 33) :sig sig}))))
    (testing "another ring" (is (not (clsag/verify {:msg MSG :ring (assoc ring 0 (public-key (seed 99))) :scope SCOPE :sig sig}))))
    (testing "a flipped response" (is (not (clsag/verify {:msg MSG :ring ring :scope SCOPE :sig (update-in sig [:s 5 0] (fn [x] (mod (inc x) 256)))}))))
    (testing "a flipped c1" (is (not (clsag/verify {:msg MSG :ring ring :scope SCOPE :sig (update-in sig [:c1 0] (fn [x] (mod (inc x) 256)))}))))
    (testing "another member's key image" (is (not (clsag/verify {:msg MSG :ring ring :scope SCOPE :sig (assoc sig :I (clsag/key-image (seed 4) SCOPE))}))))
    (testing "the identity as key image" (is (not (clsag/verify {:msg MSG :ring ring :scope SCOPE :sig (assoc sig :I (into [1] (repeat 31 0)))}))))
    (testing "a seed outside the ring refuses to sign"
      (is (thrown? #?(:cljs js/Error :default Exception) (clsag/sign {:msg MSG :ring ring :seed (seed 50) :index 2 :scope SCOPE :random (counter-random "z")}))))
    (testing "every index signs" (doseq [i (range 8)] (is (clsag/verify {:msg MSG :ring ring :scope SCOPE :sig (sign-with ring i SCOPE MSG (str "i" i))}))))))

(deftest linkability
  (let [ring (ring-of 8)
        s1 (sign-with ring 3 SCOPE MSG "a")
        s2 (sign-with ring 3 SCOPE (b/utf8 "vote: poll-7 option-1") "b")
        s3 (sign-with ring 3 (b/utf8 "aegis.chat.public/poll-8") MSG "c")
        s4 (sign-with ring 5 SCOPE MSG "d")]
    (is (= (:I s1) (:I s2)) "one member, one scope: the same key image (a second vote is caught by the spent set)")
    (is (not= (:I s1) (:I s3)) "one member, two scopes: different key images")
    (is (not= (:I s1) (:I s4)) "two members, one scope: different key images")
    (is (not= (:s s1) (:s s2)))))

(deftest verify-timing
  (doseq [n [8 16 32 64]]
    (let [ring (ring-of n)
          sig (sign-with ring (quot n 2) SCOPE MSG (str "t" n))
          t0 (process/now-ms)
          ok (clsag/verify {:msg MSG :ring ring :scope SCOPE :sig sig})
          t1 (process/now-ms)]
      (is ok)
      (println (str "clsag verify ring " n ": " (- t1 t0) " ms (through eve.crypto's ed25519-mul-add rows)")))))

(deftest base-literal
         (is (= clsag/BASE (vec (clsag/base-computed)))
             "the base point's literal encoding is [1]B")
         (is (= clsag/IDENTITY (vec (ed/pack [ed/gf0 ed/gf1 ed/gf1 ed/gf0])))
             "the identity's literal encoding is its packing"))
