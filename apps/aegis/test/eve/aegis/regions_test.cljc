(ns eve.aegis.regions-test
  "eve.aegis.regions: members' X25519 keys from their Ed25519 keys, the lockbox lifecycle (create, extend, rotate, the
   history chain), the broker's structural checks with every refusal, a split-view admin caught by the key commitment,
   content and blob sealing, and a blob read by a reader added two epochs later without the blob changing."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.hpke :as hpke]
            [eve.aegis.regions :as r]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.net.sha512 :as sha]))

(def ^:private ATOM "aegis.chat.public")
(def ^:private REGION "private")

(defn- seed [name] (subvec (vec (sha/sha512 (b/utf8 (str "seed:" name)))) 0 32))
(defn- pk [name] (vec (crypto/ed25519-public-key (seed name))))
(defn- hexpk [name] (b/hex (pk name)))
(defn- random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n)
                               (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))
(defn- key-of [label] (crypto/sha256 (b/utf8 (str "region-key:" label))))

(deftest members-encryption-keys-are-their-roster-keys
  (doseq [n ["alice" "bob" "carol"]]
    (is (= (hpke/public-key (r/x25519-secret (seed n))) (r/x25519-public (pk n)))
        (str n ": X25519(sk, 9) equals the birational map of the Ed25519 public key"))))

(deftest the-lockbox-lifecycle
  (let [rnd (random "lb") k1 (key-of 1) k2 (key-of 2)
        lb1 (r/new-lockbox ATOM REGION [(pk "alice") (pk "bob")] k1 rnd)
        roster1 #{(hexpk "alice") (hexpk "bob")}]
    (testing "epoch 1: each reader opens the key, a non-reader does not"
      (is (= k1 (r/open-key lb1 ATOM REGION (seed "alice"))))
      (is (= k1 (r/open-key lb1 ATOM REGION (seed "bob"))))
      (is (nil? (r/open-key lb1 ATOM REGION (seed "mallory"))))
      (is (nil? (r/lockbox-refusal nil lb1 roster1 :grant)))
      (is (= :lockbox-recipients (r/lockbox-refusal nil lb1 #{(hexpk "alice")} :grant))))
    (let [roster2 (conj roster1 (hexpk "carol"))
          lb1x (r/extend-lockbox lb1 ATOM REGION [(pk "alice") (pk "bob") (pk "carol")] k1 rnd)]
      (testing "extend: carol added at the same epoch, every earlier box byte-identical"
        (is (= (get lb1 "boxes") (select-keys (get lb1x "boxes") (keys (get lb1 "boxes")))))
        (is (= k1 (r/open-key lb1x ATOM REGION (seed "carol"))))
        (is (nil? (r/lockbox-refusal lb1 lb1x roster2 :grant)))
        (is (= :region-stale (r/write-refusal lb1 roster2 1)) "before the extend the region is stale")
        (is (nil? (r/write-refusal lb1x roster2 1)))
        (is (= :region-epoch (r/write-refusal lb1x roster2 2))))
      (let [roster3 (disj roster2 (hexpk "bob"))
            lb2 (r/rotate-lockbox lb1x ATOM REGION [(pk "alice") (pk "carol")] k1 k2 rnd :grant)]
        (testing "rotate: bob removed, epoch 2, the old key in the history chain"
          (is (= 2 (get lb2 "epoch")))
          (is (nil? (r/open-key lb2 ATOM REGION (seed "bob"))))
          (is (= k2 (r/open-key lb2 ATOM REGION (seed "alice"))))
          (is (= k1 (r/key-at lb2 ATOM REGION k2 1)) "carol and alice walk back to epoch 1")
          (is (nil? (r/lockbox-refusal lb1x lb2 roster3 :grant)))
          (is (= :region-stale (r/write-refusal lb1x roster3 1)) "after the removal, before the rotate")
          (is (= :lockbox-recipients (r/lockbox-refusal lb1x (r/extend-lockbox lb1x ATOM REGION [(pk "alice") (pk "carol")] k1 rnd) roster3 :grant))
              "an extend cannot drop bob"))
        (testing "the broker's refusals"
          (is (= :lockbox-epoch (r/lockbox-refusal lb1x (assoc lb2 "epoch" 3) roster3 :grant)))
          (is (= :lockbox-recipients (r/lockbox-refusal lb1x lb2 (conj roster3 (hexpk "mallory")) :grant)))
          (is (= :lockbox-history (r/lockbox-refusal lb1x (assoc lb2 "history" {}) roster3 :grant)) "history missing under :grant")
          (is (= :lockbox-history (r/lockbox-refusal lb1x lb2 roster3 :withhold)) "history added under :withhold")
          (is (nil? (r/lockbox-refusal lb1x (r/rotate-lockbox lb1x ATOM REGION [(pk "alice") (pk "carol")] k1 k2 rnd :withhold) roster3 :withhold)))
          (is (= :lockbox-extend-changed
                 (r/lockbox-refusal lb1 (assoc-in lb1x ["boxes" (hexpk "alice")] (get-in lb1x ["boxes" (hexpk "carol")])) roster2 :grant)))
          (is (= :lockbox-extend-commit (r/lockbox-refusal lb1 (assoc lb1x "commit" (get lb2 "commit")) roster2 :grant)))
          (is (= :lockbox-malformed (r/lockbox-refusal lb1 (assoc-in lb1x ["boxes" (hexpk "carol") "ct"] "00") roster2 :grant))))
        (testing "the root a claim binds moves with any byte"
          (is (= (vec (repeat 32 0)) (r/lockbox-root nil)))
          (is (not= (r/lockbox-root lb1) (r/lockbox-root lb1x)))
          (is (not= (r/lockbox-root lb2) (r/lockbox-root (assoc-in lb2 ["history" "1"] (get-in lb2 ["boxes" (hexpk "alice") "ct"]))))))))))

(deftest a-split-view-admin-is-caught
  (let [rnd (random "split") k1 (key-of "honest") k-evil (key-of "evil")
        lb (r/new-lockbox ATOM REGION [(pk "alice") (pk "bob")] k1 rnd)
        ;; the admin seals a different key in bob's box under the same commitment
        lb (assoc-in lb ["boxes" (hexpk "bob")] (r/seal-box ATOM REGION 1 k-evil (pk "bob") (rnd 32)))]
    (is (= k1 (r/open-key lb ATOM REGION (seed "alice"))))
    (is (= :region/commit-mismatch (try (r/open-key lb ATOM REGION (seed "bob")) nil
                                        (catch #?(:cljs :default :jank jank.runtime.object_ref :default Exception) e (:reason (ex-data e))))))
    (is (nil? (r/lockbox-refusal nil lb #{(hexpk "alice") (hexpk "bob")} :grant))
        "the broker cannot see it (it holds no key): members detect it")))

(deftest content-and-blobs
  (let [rnd (random "blob") k1 (key-of 1) k2 (key-of 2) k3 (key-of 3)
        lb1 (r/new-lockbox ATOM REGION [(pk "alice") (pk "bob")] k1 rnd)
        aad (b/utf8 "m-1|alice")
        sealed (r/seal-content ATOM REGION 1 k1 aad (b/utf8 "the private text") (rnd 12))
        dek (rnd 32) blob (b/utf8 "a file's bytes, sealed once")
        blob-ct (r/seal-blob dek (rnd 12) blob)
        cid (b/hex (crypto/sha256 blob-ct))
        wrapped (r/wrap-dek ATOM REGION 1 k1 cid dek (rnd 12))]
    (testing "content: opens under its epoch's key and aad only"
      (is (= (b/utf8 "the private text") (r/open-content ATOM REGION 1 k1 aad sealed)))
      (is (nil? (r/open-content ATOM REGION 2 k1 aad sealed)) "another epoch")
      (is (nil? (r/open-content ATOM REGION 1 k1 (b/utf8 "m-1|mallory") sealed)) "another record")
      (is (nil? (r/open-content ATOM REGION 1 k2 aad sealed)) "another key"))
    (testing "a reader added two epochs later reads the epoch-1 blob; the blob never changed"
      (let [lb2 (r/rotate-lockbox lb1 ATOM REGION [(pk "alice")] k1 k2 rnd :grant)
            lb3 (r/rotate-lockbox lb2 ATOM REGION [(pk "alice") (pk "dave")] k2 k3 rnd :grant)
            dave-key (r/open-key lb3 ATOM REGION (seed "dave"))
            k1' (r/key-at lb3 ATOM REGION dave-key 1)
            dek' (r/unwrap-dek ATOM REGION 1 k1' cid wrapped)]
        (is (= k3 dave-key))
        (is (= k1 k1'))
        (is (= dek dek'))
        (is (= blob (r/open-blob dek' blob-ct)))
        (is (nil? (r/unwrap-dek ATOM REGION 1 k1' (str cid "0") wrapped)) "the wrap is bound to the blob's id")
        (is (nil? (r/open-key lb3 ATOM REGION (seed "bob"))) "bob, removed at epoch 2, holds no box after it")))
    (testing ":withhold: a new reader gets no history"
      (let [lb2 (r/rotate-lockbox lb1 ATOM REGION [(pk "alice") (pk "dave")] k1 k2 rnd :withhold)]
        (is (= k2 (r/open-key lb2 ATOM REGION (seed "dave"))))
        (is (nil? (r/key-at lb2 ATOM REGION k2 1)))))))
