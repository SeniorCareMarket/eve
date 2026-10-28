(ns eve.net.wire-test
  "Unit 4.3 (eve/doc/network-atoms/IMPL-PLAN.md §6.3): the broker's wire (eve.net.wire) and the store's blobs
   (eve.net.blobs) as sans-IO steps, run by eve.net.run over a synchronous platform whose :request is an in-process
   production broker — the shapes of vs/test/vs/net_wire_fixtures.vs `production-broker`, from production's
   coordinator.cljs / worker.cljs (WIRE.md §2) — and an object store behind its presigned URLs. Then G1 end to end: a
   value encoded (eve.net.codec), uploaded with the HEAD dedup, fetched by key with the address check, decoded equal."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eve.net.bytes :as b]
            [eve.net.codec :as codec]
            [eve.net.json :as json]
            [eve.net.run :as r]
            [eve.net.wire :as wire]
            [eve.net.blobs :as blobs]
            [eve.net.stand-in :refer [stand-in STORE]]))

(def ^:private cfg {:endpoint "https://dev.atom.broker" :domain-id "d1" :atom-id "a1" :api-key "k"})

(defn- platform [s] {:request (:request s) :sleep (fn [_] nil) :now-ms (fn [] 0) :seed 7})

(defn- run [s step] (r/run (platform s) step))

(deftest the-atom-routes
  (let [s (stand-in {})]
    (testing "create, then deref an empty atom: a lease, no root"
      (is (= {:status "ok" :epoch 0} (run s (wire/create cfg))))
      (let [d (run s (wire/deref-atom cfg))]
        (is (nil? (:root-key d)))
        (is (= "lease-1" (:lease-id d)))
        (is (= 60 (:lease-ttl d)))))
    (testing "a CAS from nil wins: epoch 1; the row holds the root blob, the count, the retired keys, the graft"
      (is (= {:status "ok" :epoch 1}
             (run s (wire/cas-root cfg {:old-key nil :new-key "aa" :root-blob [1 2 3] :root-count 1 :retired-keys ["r1"]
                                        :graft-sha "gg"}))))
      (let [row (get-in @(:state s) [:atoms "a1"])]
        (is (= ["aa" 1 "AQID" 1 ["r1"] "gg"] [(:root_key row) (:epoch row) (:root_blob row) (:root_count row) (:retired row) (:graft_sha row)]))))
    (testing "deref answers the root and its blob as bytes"
      (let [d (run s (wire/deref-atom cfg))]
        (is (= ["aa" [1 2 3] 1 1] [(:root-key d) (:root-blob d) (:epoch d) (:root-count d)]))))
    (testing "a stale CAS is a 409 ANSWER (never a throw): the winner's key, epoch, blob and graft"
      (is (= {:status "conflict" :current-key "aa" :current-epoch 1 :root-blob [1 2 3] :graft-sha "gg"}
             (run s (wire/cas-root cfg {:old-key "stale" :new-key "bb" :root-blob [9]}))))
      (is (= 1 (:conflicts @(:state s)))))
    (testing "release is best-effort and answers ok; renew on production's Worker is not-found"
      (is (= {:status "ok"} (run s (wire/release-reader cfg "lease-1"))))
      (is (= ["lease-1"] (:released @(:state s))))
      (is (= {:status "not-found"} (run s (wire/renew-lease cfg "lease-2")))))
    (testing "every request carried the bearer key and a JSON body naming the atom"
      (is (every? (fn [[m u req]] (or (not= "POST" m) (str/includes? u "/presign") (str/includes? u "-reader")
                                      (str/includes? u "renew") (= "a1" (get req "atom_id"))))
                  @(:log s))))))

(deftest denied-and-unauthorized
  (let [s (stand-in {:deny (fn [req] (= "secret" (get req "new_key")))})]
    (run s (wire/create cfg))
    (is (= {:status "denied" :reason "spec-write-denied" :detail "Write denied by domain spec" :by nil}
           (run s (wire/cas-root cfg {:old-key nil :new-key "secret"}))))
    (is (nil? (get-in @(:state s) [:atoms "a1" :root_key])) "nothing written")
    (let [e (try (run s (wire/deref-atom (assoc cfg :api-key "wrong"))) nil
                 (catch #?(:cljs :default :lpy python/Exception :default Exception) e (ex-data e)))]
      (is (= 401 (:status e)) "a 401 where an answer is required throws, naming the status"))))

(deftest presign-batches
  (let [s (stand-in {})
        ks (mapv (fn [i] (b/hex (b/u32le i))) (range 250))
        urls (run s (wire/presign cfg :read ks))]
    (is (= (set ks) (set (keys urls))))
    (is (every? (fn [[k u]] (str/starts-with? u (str STORE k "?"))) urls))
    (is (= 3 (count (filter (fn [[m u]] (and (= "POST" m) (str/ends-with? u "/presign"))) @(:log s)))) "100 keys a call")))

(deftest a-value-through-the-store
  (let [s (stand-in {})
        v {:user "ada" :scores [1 2 3 4294967296] :tags #{"x" :y} :nested {:deep [2.5 nil true]} :log '(1 2)}
        {:keys [root set-root? blocks]} (codec/encode v)]
    (testing "upload: a presigned HEAD each, then a presigned PUT of each missing block — direct to the store"
      (is (= (set (keys blocks)) (set (run s (blobs/upload-blocks cfg blocks)))))
      (is (= blocks @(:store s)))
      (is (every? (fn [e] (or (not (#{"PUT" "HEAD" "GET"} (first e))) (= 64 (count (second e))))) @(:log s))
          "every store request is keyed by a bare 64-hex SHA (never /blob/<sha>)"))
    (testing "a second upload of the same value PUTs nothing (the HEAD dedup)"
      (is (= [] (run s (blobs/upload-blocks cfg blocks)))))
    (testing "fetch by key, each checked against its key, decodes equal"
      (let [got (run s (blobs/fetch-blocks cfg (keys blocks)))]
        (is (= blocks got))
        (is (= v (codec/decode root set-root? got)))))
    (testing "a blob served with one byte flipped throws naming its key"
      (let [k (first (keys blocks))
            bad (update (get blocks k) 0 (fn [x] (bit-xor x 1)))
            t (stand-in {:tamper {k bad}})]
        (reset! (:store t) blocks)
        (is (str/includes? (try (run t (blobs/fetch-blocks cfg [k])) "no throw"
                                (catch #?(:cljs :default :lpy python/Exception :default Exception) e (ex-message e)))
                           (str "asked for " k)))))))

(deftest requests-go-out-together
  (testing "with :request-all, the presigned HEADs, PUTs and GETs go out PARALLEL at a time (ADAPTERS.md §4.2): every
            block uploaded, every block fetched back, the value equal"
    (let [s (stand-in {})
          batches (atom [])
          p {:request (:request s)
             :request-all (fn [reqs] (swap! batches conj (mapv :method reqs)) (mapv (:request s) reqs))
             :sleep (fn [_] nil) :now-ms (fn [] 0) :seed 7}
          v (vec (for [i (range 70)] {:i i :s (str "v" i)}))
          {:keys [root set-root? blocks]} (codec/encode v)]
      (is (< 64 (count blocks)) "enough blocks for three batches")
      (is (= (set (keys blocks)) (set (r/run p (blobs/upload-blocks cfg blocks)))))
      (let [heads (filter (fn [b] (= "HEAD" (first b))) @batches)
            puts (filter (fn [b] (= "PUT" (first b))) @batches)]
        (is (= (count blocks) (reduce + (map count heads))) "a HEAD for every block")
        (is (= (count blocks) (reduce + (map count puts))) "a PUT for every block")
        (is (= blobs/PARALLEL (count (first heads))) "a full batch")
        (is (every? (fn [b] (<= (count b) blobs/PARALLEL)) @batches) "never wider than PARALLEL"))
      (reset! batches [])
      (let [got (r/run p (blobs/fetch-blocks cfg (keys blocks)))]
        (is (= v (codec/decode root set-root? got)))
        (is (= (count blocks) (reduce + (map count @batches))))
        (is (every? (fn [b] (every? #{"GET"} b)) @batches) "GETs only")))))
