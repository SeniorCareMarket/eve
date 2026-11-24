(ns eve.opfs-relay-mode-test
  "eve.opfs's relay pool (the OPFS disk tier spec, W10): run in the one worker of a test page that is not
   cross-origin isolated (eve.runner.opfs-browser-main, ?eve-opfs-relay=1), after it mounted the relay pool. A
   region's synced bytes are the file's after the io worker closes every file; a sync writes only what changed;
   and a persistent atom keeps its value across a domain close and every file closed."
  (:require
   [cljs.test :refer [deftest is testing]]
   [eve.atom :as atom]
   [eve.mem :as mem]
   [eve.opfs :as opfs]))

(def ^:private dir (str "r" (js/Date.now)))

(defn- p [f] (str dir "/" f))

(deftest the-pool-is-the-relay-pool
  (is (some? (.-relay ^js (opfs/pool-of))))
  (is (opfs/owner?) "the only tab owns the files")
  (is (not (true? js/crossOriginIsolated))))

(deftest synced-bytes-are-the-files
  (opfs/ensure-dirs! dir)
  (let [r (opfs/open-region (p "a") 8192)]
    (is (mem/fresh-region? r))
    (mem/-store-i32! r 0 41)
    (mem/-store-i32! r 5000 42)
    (mem/sync-region! r)
    (opfs/close-all!)
    (let [r2 (opfs/open-region (p "a") 8192)]
      (is (= [41 42] [(mem/-load-i32 r2 0) (mem/-load-i32 r2 5000)]))
      (testing "a sync after nothing changed sends nothing; one store, one page"
        (let [sent #(or (.-pagesSent ^js (opfs/pool-of)) 0)
              s0 (sent)]
          (mem/sync-region! r2)
          (is (= s0 (sent)))
          (mem/-store-i32! r2 100 7)
          (mem/sync-region! r2)
          (is (= (inc s0) (sent))))))
    (opfs/close-all!)
    (is (= 7 (mem/-load-i32 (opfs/open-region (p "a") 8192) 100)))
    (opfs/close-all!)))

(deftest a-persistent-atom-on-the-relay-pool
  (let [b (p "db")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} {:count 0})]
    (dotimes [_ 20] (swap! a update :count inc))
    (is (= 20 (:count @a)))
    (atom/close-atom-domain! d)
    (testing "every file closed, the domain reopened from OPFS"
      (opfs/close-all!)
      (let [d2 (atom/persistent-atom-domain b)
            c (atom/persistent-atom {:id :eve/main :persistent b} nil)]
        (is (= 20 (:count @c)))
        (swap! c update :count inc)
        (atom/close-atom-domain! d2)))
    (opfs/close-all!)
    (let [d3 (atom/persistent-atom-domain b)
          c (atom/persistent-atom {:id :eve/main :persistent b} nil)]
      (is (= 21 (:count @c)))
      (atom/close-atom-domain! d3))))
