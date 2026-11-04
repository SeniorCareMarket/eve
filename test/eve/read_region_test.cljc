(ns eve.read-region-test
  "eve.alpha/$, the read region (eve.read-region): inside it a
   deref answers the atom's stored value — lazy, slab-backed — under a pin held until the region exits, and the
   region's result leaves as plain data (a lazy seq realized, its elements materialized); nothing the region's own
   writes retire is freed while it runs (the retire queue keeps every entry until the region lets go — without the
   pin the queue drains at each write); a region inside
   another joins it; the pins go when the body throws. Outside a region deref materializes and holds nothing. On
   every host (a network atom's leases: eve.net.remote-atom-test)."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.alpha :as e]
            [eve.atom :as atom]
            [eve.core :as ec]
            [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve.deftype-proto.data :as d]))

(defn- fresh
  "An atom of a new in-process domain, holding v."
  [v]
  (atom/lookup-or-create-mmap-atom! (atom/atom-domain nil) "a" v))

(defn- pinned?
  "Does this thread pin a's domain?"
  [a]
  (some? (get @(:pins (atom/-domain-state a)) (proc/thread-id))))

(defn- stored?
  "Is v an eve value (materializing it makes another kind of value)?"
  [v]
  (not= (type v) (type (d/eve->clj v))))

(deftest deref-in-a-region-is-the-stored-value
  (let [a (fresh {:by-id {1 {:name "ada"} 2 {:name "bob"}} :n 2})]
    (testing "outside a region deref materializes and holds nothing"
      (is (not (stored? @a)))
      (is (not (pinned? a))))
    (testing "inside, the stored value, its domain pinned; the region's result plain, the pin gone"
      (let [seen (clojure.core/atom nil)
            r (e/$ (let [v @a
                         by-id (ec/get v :by-id)]
                     (reset! seen [(stored? v) (stored? by-id) (pinned? a)])
                     (ec/get by-id 2)))]
        (is (= [true true true] @seen))
        (is (= {:name "bob"} r))
        (is (not (stored? r)))
        (is (not (pinned? a)))))))

(deftest a-lazy-result-is-realized-and-materialized
  (let [a (fresh {:rows [{:i 0} {:i 1} {:i 2}]})
        r (e/$ (map identity (ec/seq (ec/get @a :rows))))]
    (is (not (pinned? a)))
    (is (= [{:i 0} {:i 1} {:i 2}] (vec r)))
    (is (every? (fn [x] (not (stored? x))) r))))

(deftest what-a-region-read-is-not-freed-while-it-runs
  (let [a (fresh {:xs (vec (range 100))})
        queued (fn [] (count @(:retire-q (atom/-domain-state a))))
        [before after held]
        (e/$ (let [before (ec/get @a :xs)]
               (dotimes [i 20]
                 (swap! a (fn [m] (ec/assoc m :xs (vec (range i (+ i 100))))))
                 ;; past the flush interval: every write's commit runs a flush
                 (proc/sleep-ms! 60))
               [before (ec/get @a :xs) (queued)]))]
    (is (= (vec (range 100)) (vec before)) "the value read first, read again after the writes")
    (is (= (vec (range 19 119)) (vec after)))
    (is (<= 20 held) "every write's retired blocks still queued: the region's pin is older than each")
    (proc/sleep-ms! 60)
    (swap! a (fn [m] (ec/assoc m :n 1)))
    (is (zero? (queued)) "freed at the first flush after the region let go")
    (is (not (pinned? a)))))

(deftest a-region-inside-another-joins-it
  (let [a (fresh {:k {:v 1}})
        r (e/$ (let [inner (e/$ (ec/get @a :k))]
                 [(stored? inner) (pinned? a)]))]
    (is (= [true true] r) "the inner region's result is the outer's to materialize; the pin is the outer's")
    (is (not (pinned? a)))))

(deftest the-pins-go-when-the-body-throws
  (let [a (fresh {:k 1})
        thrown (try (e/$ (ec/get @a :k) (throw (ex-info "in the region" {:at :body})))
                    (catch #?(:cljs :default :lpy python/Exception :default Exception) ex (:at (ex-data ex))))]
    (is (= :body thrown))
    (is (not (pinned? a)))))
