(ns eve.net.remote-atom-test
  "Unit 4.4 (eve/doc/network-atoms/IMPL-PLAN.md §6.4): eve.net.atom against the in-process production broker and store
   (eve.net.stand-in) — T-4.4 every swap PUTs the changed blocks, each under the SHA of its bytes; T-4.5 a nested map's
   inner blocks are uploaded; T-4.6 a 409 reaches the conflict branch and the retry wins; T-4.7 a 403 throws once; the
   lease held, released and renewed, and held to the end of a read region (eve.alpha/$); the root blob a VSNP snapshot
   whose key is its SHA-256; the retired keys."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eve.alpha :as e]
            [eve.net.atom :as a]
            [eve.net.bytes :as b]
            [eve.net.codec :as codec]
            [eve.net.sha256 :as sha]
            [eve.net.snapshot :as snap]
            [eve.net.stand-in :refer [stand-in]]))

(def ^:private cfg {:endpoint "https://dev.atom.broker" :domain-id "d1" :atom-id "a1" :api-key "k"})

(defn- setup
  ([] (setup {}))
  ([opts]
   (let [s (stand-in opts)
         slept (atom [])
         p {:request (:request s) :sleep (fn [ms] (swap! slept conj ms)) :now-ms (fn [] 1700000000000) :seed 42}
         ra (a/remote-atom cfg p)]
     ((:request s) {:method "POST" :url (str (:endpoint cfg) "/v1/domains/d1/atoms/a1/create")
                    :headers {"Authorization" "Bearer k"} :body "{\"atom_id\":\"a1\"}"})
     [ra s slept])))

(defn- puts [s] (map second (filter (fn [e] (= "PUT" (first e))) @(:log s))))

(deftest deref-and-swap
  (let [[ra s slept] (setup)]
    (testing "an empty atom derefs to {}"
      (is (= {} (a/deref ra))))
    (testing "swap! commits f's value; the broker holds the root key = sha256 of the root blob, epoch 1"
      (is (= {:n 1} (a/swap! ra assoc :n 1)))
      (let [row (get-in @(:state s) [:atoms "a1"])
            blob (b/from-b64 (:root_blob row))]
        (is (= 1 (:epoch row)))
        (is (= (sha/sha256-hex blob) (:root_key row)))
        (is (= snap/MAGIC (b/read-u32le blob 0)) "a VSNP blob")
        (is (= 1 (:root_count row)))))
    (testing "T-4.4: every PUT is keyed by the SHA of its bytes, each once"
      (let [ps (puts s)]
        (is (seq ps))
        (is (= (count ps) (count (distinct ps))))
        (is (every? (fn [k] (and (= 64 (count k)) (= k (sha/sha256-hex (get @(:store s) k))))) ps))))
    (testing "T-4.5: a nested map's inner blocks reach the store, and a fresh handle reads the value back"
      (a/swap! ra assoc :deep {:a {:b [1 2 3 {:c #{"x" "y"}}]} :s "str" :d 2.5})
      (let [want {:n 1 :deep {:a {:b [1 2 3 {:c #{"x" "y"}}]} :s "str" :d 2.5}}
            [_ blocks] (codec/band-of (:deep want))]
        (is (every? (fn [k] (contains? @(:store s) k)) (keys blocks)) "every block of the nested value is stored")
        (is (= want (a/deref (a/remote-atom cfg (:platform ra)))) "a fresh handle (empty cache) fetches and decodes it")))
    (testing "a swap retires the old root and the old blocks the new value no longer reaches"
      (let [old (get-in @(:state s) [:atoms "a1" :root_key])]
        (a/swap! ra update :deep dissoc :s)
        (let [retired (set (get-in @(:state s) [:atoms "a1" :retired]))]
          (is (contains? retired old))
          (is (< 1 (count retired))))))
    (testing "reset! sets the value; no conflict, no backoff"
      (is (= {:x "y"} (a/reset! ra {:x "y"})))
      (is (= {:x "y"} (a/deref ra)))
      (is (= [] @slept)))))

(deftest conflict-retries-then-wins
  (let [bumps (atom 0)
        [ra s slept] (setup {:before-cas (fn [st] (when (zero? @bumps)
                                                    (swap! bumps inc)
                                                    (swap! st assoc-in [:atoms "a1" :root_key] "racer")))})]
    (is (= {:n 1} (a/swap! ra assoc :n 1)) "T-4.6: the first CAS conflicts (409), the retry wins")
    (is (= 1 (:conflicts @(:state s))))
    (is (= 1 (count @slept)) "one backoff")
    (is (<= 5 (first @slept) 15) "attempt 0: 10 ms × (0.5 + a draw)")))

(deftest denied-throws-once
  (let [[ra s slept] (setup {:deny (fn [req] (some (fn [[kb _]] (str/includes? (apply str (map char kb)) ":secret"))
                                                      (snap/unframe (b/from-b64 (get req "root_blob")))))})
        e (try (a/swap! ra assoc :secret 1) nil (catch #?(:cljs :default :lpy python/Exception :default Exception) e e))]
    (is (= :policy-denied (:type (ex-data e))) "T-4.7")
    (is (= "spec-write-denied" (:reason (ex-data e))))
    (is (= [] @slept))
    (is (nil? (get-in @(:state s) [:atoms "a1" :root_key])) "nothing written")
    (is (= {:ok 1} (a/swap! ra assoc :ok 1)) "the handle is usable after a deny")))

(deftest retries-are-bounded
  (let [[ra s slept] (setup {:before-cas (fn [st] (swap! st update-in [:atoms "a1" :root_key] str "x"))})
        e (try (a/swap! ra assoc :n 1) nil (catch #?(:cljs :default :lpy python/Exception :default Exception) e e))]
    (is (= :retries-exhausted (:type (ex-data e))))
    (is (= 50 (count @slept)))
    (is (every? (fn [ms] (<= ms 3000)) @slept) "capped at 2000 ms × 1.5")))

(deftest the-lease-is-held
  (let [[ra s _] (setup)]
    (a/deref ra)
    (let [l1 (:lease-id @(:state ra))]
      (is (contains? (:leases @(:state s)) l1))
      (a/deref ra)
      (is (= [l1] (:released @(:state s))) "the next deref releases the first lease"))
    (is (= :fresh (a/renew! ra)))
    (is (= :lost (a/renew! ra true)) "production's Worker has no renew route: the lease is released")
    (a/swap! ra assoc :n 1)
    (is (empty? (:leases @(:state s))) "no lease left on the broker")))

(deftest leases-in-a-read-region-are-held-until-it-exits
  (let [[ra s _] (setup)]
    (a/swap! ra assoc :n 1)
    (let [held (e/$ (a/deref ra) (a/deref ra) (:leases @(:state s)))]
      (is (= 2 (count held)) "inside eve.alpha/$ each deref's lease is held, the one the second replaced too")
      (is (empty? (:leases @(:state s))) "every one released as the region exits")
      (is (nil? (:lease-id @(:state ra)))))))

(deftest an-encoded-value-is-the-vs-clients
  (testing "a snapshot entry for a collection is the reference by content to the codec's key"
    (let [{:keys [blob]} (a/snapshot {:v [1 2]})
          [[_ vb]] (snap/unframe blob)
          {:keys [root]} (codec/encode [1 2])]
      (is (= root (snap/slot-key vb))))))

(deftest encrypted-blocks
  (testing "T-4.8: with the domain's gak every stored block is ciphertext (not its image), keyed by its plaintext's SHA,
            and a reader holding the gak derefs the value equal"
    (let [gak (vec (range 200 232))
          s (stand-in {})
          p {:request (:request s) :sleep (fn [_] nil) :now-ms (fn [] 0) :seed 1}
          ecfg (assoc cfg :gak gak)
          ra (a/remote-atom ecfg p)
          v {:secret {:pin [1 2 3 4] :name "ada"}}]
      ((:request s) {:method "POST" :url (str (:endpoint cfg) "/v1/domains/d1/atoms/a1/create")
                     :headers {"Authorization" "Bearer k"} :body "{\"atom_id\":\"a1\"}"})
      (a/reset! ra v)
      (let [[_ blocks] (codec/band-of (:secret v))]
        (is (seq blocks))
        (is (every? (fn [[k img]] (let [stored (get @(:store s) k)] (and stored (not= img stored)))) blocks)
            "the store holds ciphertext under each block's plaintext key")
        (is (= v (a/deref (a/remote-atom ecfg p))) "a fresh reader with the gak reads it")
        (is (thrown? #?(:cljs :default :lpy python/Exception :default Exception) (a/deref (a/remote-atom cfg p)))
            "a reader without the gak fails the address check")))))

(deftest a-long-past-int32-round-trips
         ;; a top-level long is a snapshot entry's serial form, tag 7 (eve.net.snapshot); its reader in eve.net.atom once
         ;; lacked the tag, so every network write of a timestamp failed where the value was read back (the 0.9 capstone)
         (let [[ra _ _] (setup)
               want {:t 1792528000000
                     :neg -3000000000
                     :edge 2147483648
                     :int 2147483647
                     :nested {:t 1792528000000}}]
           (is (= want (a/reset! ra want)))
           (is (= want (a/deref (a/remote-atom cfg (:platform ra))))
               "a fresh handle reads every long back")
           (is (= (assoc want :t 1792528000001) (a/swap! ra update :t inc))
               "a swap from the read value")))

(deftest a-swap-answering-its-value-commits-nothing
         ;; eve.thread's starved writer answers the value it was given (then takes a turn): over the network that attempt is a
         ;; read — a CAS of the same value would have to win the race the writer keeps losing
         (let [[ra s _] (setup)
               _ (a/reset! ra {:n 1})
               cas (fn []
                     (count (filter (fn [e]
                                      (str/includes? (str (second e))
                                                     "/cas-root")) @(:log s))))
               before (cas)]
           (is (= {:n 1} (a/swap! ra (fn [v] v))))
           (is (= before (cas)) "no CAS sent")
           (is (= {:n 2} (a/swap! ra update :n inc)))
           (is (= (inc before) (cas)) "a real change is CASed")))
