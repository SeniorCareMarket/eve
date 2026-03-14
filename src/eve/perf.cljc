(ns eve.perf
  "Lightweight JVM-only profiling for eve hot paths.

   Toggle with (eve.perf/enable!) / (eve.perf/disable!).
   When disabled, the `timed` macro compiles to zero overhead (just executes body).
   When enabled, records nanosecond timings and event counts per thread into
   lock-free accumulators, then merges across threads on report.

   Usage:
     (require '[eve.perf :as perf])
     (perf/enable!)
     ;; ... run workload ...
     (perf/report)   ; prints table
     (perf/disable!)

   Instrument code:
     (perf/timed :swap-cas (mem/-cas-i32! ...))
     (perf/count! :cas-retry)
     (perf/timed :hamt-assoc (jvm-hamt-assoc! ...))")

#?(:bb
   ;; Babashka stubs — no profiling support, timed just executes body.
   (do
     (defn enabled? [] false)
     (defn enable!  [] :disabled)
     (defn disable! [] :disabled)
     (defn count!   [_section] nil)
     (defn record-timing! [_section _elapsed-ns] nil)
     (defmacro timed [_section & body] `(do ~@body))
     (defn snapshot  [] [])
     (defn report    [] (println "  (profiling not available in bb)"))
     (defn reset-all! [] nil))

   :clj
   (do

;; ---------------------------------------------------------------------------
;; Global toggle
;; ---------------------------------------------------------------------------

(defonce ^:private enabled_ (atom false))

(declare reset-all!)

(defn enabled? [] @enabled_)
(defn enable!  [] (reset! enabled_ true)  (reset-all!) :enabled)
(defn disable! [] (reset! enabled_ false) :disabled)

;; ---------------------------------------------------------------------------
;; Per-thread accumulators
;; ---------------------------------------------------------------------------
;; Each thread gets its own HashMap<String, long[4]> where:
;;   [0] = count
;;   [1] = total-ns
;;   [2] = min-ns (init Long/MAX_VALUE)
;;   [3] = max-ns (init 0)
;;
;; No synchronization on the hot path — each thread writes only to its own map.

(defonce ^:private all-accumulators
  ;; CopyOnWriteArrayList of WeakReference<HashMap> — one per thread that has
  ;; ever called `get-acc`. We use weak refs so dead threads get GC'd.
  (java.util.concurrent.CopyOnWriteArrayList.))

(defonce ^:private ^ThreadLocal thread-acc
  (proxy [ThreadLocal] []
    (initialValue []
      (let [m (java.util.HashMap.)]
        (.add all-accumulators (java.lang.ref.WeakReference. m))
        m))))

(defn- ^longs ensure-slot ^longs [^java.util.HashMap m ^String k]
  (or (.get m k)
      (let [arr (long-array 4)]
        (aset arr 2 Long/MAX_VALUE)
        (.put m k arr)
        arr)))

;; ---------------------------------------------------------------------------
;; Recording API (called from instrumented code)
;; ---------------------------------------------------------------------------

(defn record-timing!
  "Record a timing observation. Called by the `timed` macro."
  [^clojure.lang.Keyword section ^long elapsed-ns]
  (let [^java.util.HashMap m (.get thread-acc)
        k                    (name section)
        ^longs arr           (ensure-slot m k)]
    (aset arr 0 (unchecked-inc (aget arr 0)))
    (aset arr 1 (unchecked-add (aget arr 1) elapsed-ns))
    (when (< elapsed-ns (aget arr 2)) (aset arr 2 elapsed-ns))
    (when (> elapsed-ns (aget arr 3)) (aset arr 3 elapsed-ns))))

(defn count!
  "Increment an event counter (no timing)."
  [^clojure.lang.Keyword section]
  (when (enabled?)
    (let [^java.util.HashMap m (.get thread-acc)
          k                    (name section)
          ^longs arr           (ensure-slot m k)]
      (aset arr 0 (unchecked-inc (aget arr 0))))))

;; ---------------------------------------------------------------------------
;; The `timed` macro
;; ---------------------------------------------------------------------------

(defmacro timed
  "Execute body, recording wall-clock ns under `section` (a keyword).
   When profiling is disabled, compiles to just `(do ~@body)` — zero overhead."
  [section & body]
  `(if (enabled?)
     (let [t0#  (System/nanoTime)
           ret# (do ~@body)
           dt#  (unchecked-subtract (System/nanoTime) t0#)]
       (record-timing! ~section dt#)
       ret#)
     (do ~@body)))

;; ---------------------------------------------------------------------------
;; Merge + Report
;; ---------------------------------------------------------------------------

(defn- merge-accumulators
  "Merge all per-thread accumulators into a single sorted map."
  []
  (let [merged (java.util.TreeMap.)]
    (doseq [^java.lang.ref.WeakReference wr all-accumulators]
      (when-let [^java.util.HashMap m (.get wr)]
        (doseq [^java.util.Map$Entry e (.entrySet m)]
          (let [k     (.getKey e)
                ^longs src (.getValue e)
                ^longs dst (or (.get merged k)
                               (let [a (long-array 4)]
                                 (aset a 2 Long/MAX_VALUE)
                                 (.put merged k a)
                                 a))]
            (aset dst 0 (+ (aget dst 0) (aget src 0)))
            (aset dst 1 (+ (aget dst 1) (aget src 1)))
            (aset dst 2 (min (aget dst 2) (aget src 2)))
            (aset dst 3 (max (aget dst 3) (aget src 3)))))))
    merged))

(defn- fmt-ns
  "Format nanoseconds as human-readable string."
  [^long ns]
  (cond
    (>= ns 1000000000) (format "%,.2fs"  (/ (double ns) 1e9))
    (>= ns 1000000)    (format "%,.2fms" (/ (double ns) 1e6))
    (>= ns 1000)       (format "%,.1fμs" (/ (double ns) 1e3))
    :else              (format "%dns"    ns)))

(defn snapshot
  "Return merged profiling data as a vector of maps, sorted by total-ns descending."
  []
  (let [^java.util.TreeMap merged (merge-accumulators)]
    (->> (for [^java.util.Map$Entry e (.entrySet merged)]
           (let [^longs arr (.getValue e)
                 cnt  (aget arr 0)
                 tot  (aget arr 1)]
             (let [min-v (aget arr 2)
                   ;; count-only events have no timing — show 0 instead of MAX_VALUE
                   min-v (if (== min-v Long/MAX_VALUE) 0 min-v)]
               {:section  (.getKey e)
                :count    cnt
                :total-ns tot
                :avg-ns   (if (pos? cnt) (quot tot cnt) 0)
                :min-ns   min-v
                :max-ns   (aget arr 3)})))
         (sort-by :total-ns >)
         vec)))

(defn report
  "Print a profiling report table to stdout, sorted by total time descending."
  []
  (let [rows (snapshot)]
    (if (empty? rows)
      (println "  (no profiling data collected)")
      (do
        (println)
        (printf "  %-28s %8s %12s %10s %10s %10s%n"
                "Section" "Count" "Total" "Avg" "Min" "Max")
        (println (apply str (repeat 88 "-")))
        (doseq [{:keys [section count total-ns avg-ns min-ns max-ns]} rows]
          (printf "  %-28s %,8d %12s %10s %10s %10s%n"
                  section count (fmt-ns total-ns) (fmt-ns avg-ns)
                  (fmt-ns min-ns) (fmt-ns max-ns)))
        (println)))))

(defn reset-all!
  "Clear all accumulated profiling data across all threads."
  []
  (doseq [^java.lang.ref.WeakReference wr all-accumulators]
    (when-let [^java.util.HashMap m (.get wr)]
      (.clear m))))

)) ;; end :clj
