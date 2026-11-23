(ns eve.flat.stream-test
  "STREAM-PLAN S1: the pipe at small scale — send-stream out of one
   domain, receive-stream! into another, the placed value equal to the
   source; segments are the classes present; allocator calls equal
   segment count on a fresh domain. Run on the JVM (the spike is
   JVM-first)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.flat.stream :as stream]))

(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(defn- fresh-atom [name]
  (let [dom (ea/atom-domain nil :capacities capacities)]
    (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- send-of [a]
  (ea/read-pinned a (fn [lv] (stream/send-stream (d/-eve-sio lv) (d/-root-header-off lv)))))

(deftest stream-roundtrips-small-values
  (doseq [[nm v] [["map" (into {} (map (fn [i] [i (* i i)])) (range 50))]
                  ["vec" (vec (range 2000))]
                  ["nested" {:a [1 2 {:b #{3 4 5}}] "s" (apply str (repeat 700 "x"))}]
                  ["set" (into #{} (range 300))]
                  ["list" (apply list (range 100))]]]
    (testing nm
      (let [src (fresh-atom (str "st/s-" nm))
            _ (swap! src (fn [_] v))
            sent (send-of src)
            wc (ea/read-pinned src (fn [lv] (stream/walk-count (d/-eve-sio lv) (d/-root-header-off lv))))
            tgt (fresh-atom (str "st/t-" nm))
            _placed (swap! tgt (fn [_] (stream/receive-value d/*domain-sio* (:bytes sent))))]
        (is (= v @tgt) "placed value equals the source value")
        (is (= (:blocks sent) (:blocks wc))
            "the sender's block count is the S0 walk's count")))))

(deftest stream-segments-and-claims
  (let [v (vec (range 5000))
        src (fresh-atom "st/seg-src")
        _ (swap! src (fn [_] v))
        sent (send-of src)
        dom (ea/atom-domain nil :capacities capacities)
        tgt (ea/lookup-or-create-mmap-atom! dom "st/seg-tgt" nil)
        _ (swap! tgt (fn [_] {:seed 1}))
        tsio (ea/read-pinned tgt (fn [lv] (d/-eve-sio lv)))
        r (stream/receive-stream! tsio (:bytes sent))]
    (is (= (:segments r) (:segments sent)) "receiver sees the sender's segments")
    (is (= (:claims r) (:segments r))
        "one allocator call per segment on an uncontended domain")
    (is (= (:blocks r) (:blocks sent)))
    (is (pos? (:root-off r)))))

(deftest stream-transfer-verb
  (let [v {:users (mapv (fn [i] {:id i :name (str "u" i) :tags [:a :b]}) (range 30))}
        src (fresh-atom "st/tv-src")
        _ (swap! src (fn [_] v))
        tgt (fresh-atom "st/tv-tgt")]
    (stream/stream-transfer! tgt src)
    (is (= v @tgt)))
  (let [v (vec (range 1000))
        src (fresh-atom "st/tv-src2")
        _ (swap! src (fn [_] v))
        tgt (fresh-atom "st/tv-tgt2")]
    (ea/read-pinned src (fn [lv] (stream/stream-transfer! tgt lv)))
    (is (= v @tgt))))

(deftest stream-v1-runs-and-budgets
  (let [v (vec (range 5000))
        src (fresh-atom "st/v1-src")
        _ (swap! src (fn [_] v))
        plain (send-of src)
        runned (ea/read-pinned src (fn [lv] (stream/send-stream (d/-eve-sio lv) (d/-root-header-off lv)
                                                                {:run-threshold 8})))
        cut (ea/read-pinned src (fn [lv] (stream/send-stream (d/-eve-sio lv) (d/-root-header-off lv)
                                                             {:budget 4096})))
        dom (ea/atom-domain nil :capacities capacities)
        tgt (ea/lookup-or-create-mmap-atom! dom "st/v1-tgt" nil)
        _ (swap! tgt (fn [_] {:seed 1}))
        tsio (ea/read-pinned tgt (fn [lv] (d/-eve-sio lv)))
        r-run (stream/receive-stream! tsio (:bytes runned))
        tgt2 (fresh-atom "st/v1-tgt2")]
    (is (pos? (:runs runned)) "the vector's leaf blocks collapse into typed runs")
    (is (< (alength (:bytes runned)) (alength (:bytes plain)))
        "runs shrink the wire")
    (is (= (:blocks runned) (:blocks plain)))
    (is (= (:claims r-run) (:segments r-run)))
    (is (> (:segments cut) (:segments plain)) "a byte budget cuts segments")
    (swap! tgt2 (fn [_] (stream/receive-value d/*domain-sio* (:bytes cut))))
    (is (= v @tgt2) "a budget-cut stream places an equal value")
    (let [tgt3 (fresh-atom "st/v1-tgt3")]
      (swap! tgt3 (fn [_] (stream/receive-value d/*domain-sio* (:bytes runned))))
      (is (= v @tgt3) "a run-bearing stream places an equal value"))))

(deftest stream-checked-door-passes-valid-streams
  ;; S3: the per-segment checked door must vouch good streams without
  ;; a single false refusal, across shapes (trie nodes with params,
  ;; overflow strings, deftype-free collections).
  (doseq [[nm v] [["map" (into {} (map (fn [i] [i (* i i)])) (range 50))]
                  ["vec" (vec (range 2000))]
                  ["nested" {:a [1 2 {:b #{3 4 5}}] "s" (apply str (repeat 700 "x"))}]
                  ["set" (into #{} (range 300))]]]
    (testing nm
      (let [src (fresh-atom (str "st/cd-" nm))
            _ (swap! src (fn [_] v))
            sent (send-of src)
            tgt (fresh-atom (str "st/cdt-" nm))]
        (swap! tgt (fn [_] (stream/receive-value d/*domain-sio* (:bytes sent) {:check? true})))
        (is (= v @tgt))))))

(deftest stream-shared-structure-cross-edges
  ;; Regression: a root map whose :part/:x/:y entries share ONE
  ;; vector. The map node holding :part and :y is a sibling of the
  ;; vector's root block under the root — a cross edge. A walk that
  ;; marks blocks seen at push time lets the node exit while the
  ;; vector is still in flight and writes the in-flight marker (-2)
  ;; as the vector's index; the checked door refuses such a stream
  ;; (transfer-test caught it). Marking at pop time — a true
  ;; post-order — is the fix this test guards.
  (let [shared (vec (range 1000))
        sample {:name "transfer" :nums (vec (range 200))
                :nested {:deep [1 2 {:three 3}] :flag true}
                :tags #{:a :b :c} :items (list 1 2 3)}
        v (assoc sample :part shared :x shared :y shared)
        src (fresh-atom "st/shared-src")
        _ (swap! src (fn [_] (assoc sample :part shared)))
        _ (swap! src (fn [cur] (assoc cur :x (get cur :part) :y (get cur :part))))
        sent (send-of src)
        tgt (fresh-atom "st/shared-tgt")]
    (swap! tgt (fn [_] (stream/receive-value d/*domain-sio* (:bytes sent) {:check? true})))
    (is (= v @tgt) "the shared value roundtrips through the checked door")
    (let [tgt2 (fresh-atom "st/shared-tgt2")]
      (stream/stream-transfer! tgt2 src)
      (is (= v @tgt2) "and through stream-transfer!"))))

(deftest stream-refuses-garbage
  (let [dom (ea/atom-domain nil :capacities capacities)
        tgt (ea/lookup-or-create-mmap-atom! dom "st/garbage" nil)
        _ (swap! tgt (fn [_] {:seed 1}))
        tsio (ea/read-pinned tgt (fn [lv] (d/-eve-sio lv)))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (stream/receive-stream! tsio (byte-array 64))))))
