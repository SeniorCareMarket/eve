(ns eve.record-assoc
  "JVM allocation microbench. Run with the Eve host classpath plus test and bench."
  (:require [eve.record-test :as fixture]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.slab-io :as slab])
  (:import [java.lang.management ManagementFactory]
           [com.sun.management ThreadMXBean]))

(clojure.core/defrecord HostFive [a b c d e])
(def sink (atom nil))

(defn run-loop [record n]
  (loop [i 0 r record]
    (if (= i n) r (recur (inc i) (assoc r :a i)))))

(defn measure [label make-record]
  (let [scope (alloc/logging-sio (alloc/current-sio))
        ^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        tid (.getId (Thread/currentThread))]
    (try
      (binding [d/*domain-sio* scope]
        (let [record (make-record)
              before (.getThreadAllocatedBytes bean tid)
              started (System/nanoTime)
              result (run-loop record 10000)
              elapsed (/ (- (System/nanoTime) started) 1e6)
              allocated (- (.getThreadAllocatedBytes bean tid) before)
              blocks (count (alloc/logged-allocs scope))]
          (reset! sink (:a result))
          (println (pr-str {:implementation label :n 10000 :ms elapsed
                            :jvm-allocated-bytes allocated :slab-blocks blocks}))))
      (finally
        ;; No atom has adopted these values; these are this benchmark's own,
        ;; uncounted allocations only. No cache or default-domain data is removed.
        (doseq [off (vec (alloc/logged-allocs scope))] (slab/free! scope off))))))

(defn -main [& _]
  (dotimes [_ 2]
    (measure :host-warmup #(->HostFive 1 2 3 4 5))
    (measure :slab-warmup #(fixture/->Five 1 2 3 4 5)))
  (measure :host #(->HostFive 1 2 3 4 5))
  (measure :slab #(fixture/->Five 1 2 3 4 5)))
