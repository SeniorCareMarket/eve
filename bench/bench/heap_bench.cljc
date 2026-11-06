;; bench.heap-bench — Eve-on-jank vs Eve-on-JVM head-to-head heap bench.
;; Same file runs on both seats (reader conditionals only for the clock,
;; the exit, the jank host-completion preload, and eve.guard, which exists
;; only on the JVM heap-grade line).
;;
;; Shapes mirror doc/jvm-heap-grade/bench/{guard,hotpath}.clj on the JVM line:
;;   src100 = {:k0 0 ... :k99 99} in an Eve atom (eve.alpha/atom, default domain)
;;   S1a sparse get via plain deref   (get @a (rand-kw))
;;   S1b sparse get via guard         (eve.guard/with-guard (get @a (rand-kw)))  [JVM line only]
;;   S2  direct get on the raw Eve map (get m100 (rand-kw))
;;   S3a swap! inc on an Eve counter atom
;;   S3b reset! (alternating value) on an Eve counter atom
;;   S4a whole-root deref             @a — plain deref materializes the root
;;                                    into plain data on both seats (post-IPlain-fix)
;;   S4b raw root handle              guarded (JVM) / read-pinned (jank) deref:
;;                                    the slab-backed EveHashMap, no materialization
;; Timing: heap-grade method — calibrate to ~40 ms batches, min of 3 batches,
;; one warmup batch per shape first. A checksum per shape guards the reads.
(ns bench.heap-bench
  (:require #?@(:jank [[eve.host-completion]])
            [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.core :as ec]
            [eve.deftype-proto.data :as d]
            #?@(:clj [[eve.guard :as g]])))

#?(:jank (cpp/raw "
#include <cstdint>
#include <chrono>
#include <jank/runtime/object.hpp>
#include <jank/runtime/core/make_box.hpp>
namespace heap_bench_clock {
  static jank::runtime::object_ref now_ns() {
    auto const t(std::chrono::steady_clock::now().time_since_epoch());
    return jank::runtime::make_box((jank::i64)std::chrono::duration_cast<std::chrono::nanoseconds>(t).count());
  }
}
"))

(defn- now-ns ^long []
  #?(:clj (System/nanoTime)
     :jank (cpp/heap_bench_clock.now_ns)))

(defn- v [sym] @(resolve sym))

(def seat #?(:clj "jvm" :jank "jank" :cljs "cljs"))
(def has-guard #?(:clj true :jank false))

(defn- stage [s] (binding [*out* *err*] (println "STAGE" s))) ;; no (flush): unimplemented on jank ("TODO: port flesh")

;; ---------------------------------------------------------------------------
;; Setup (identical shapes to the heap-grade benches)
;; ---------------------------------------------------------------------------
(def src100 (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100))))
(def a100 (e/atom src100))
;; raw slab value: JVM line — domain-aware guarded deref (hotpath.clj's `raw`).
;; jank line — plain deref now plain-converts (that is the IPlain fix working),
;; so capture the raw EveHashMap under a read pin at setup; no writes touch
;; a100 during the bench, so its blocks stay live (hotpath.clj's justification).
(defn- raw-deref []
  #?(:clj (binding [g/*proxy-same-domain?* false]
            (g/guard* (ea/-domain-state a100) (fn [] @a100)))
     :jank (ea/read-pinned a100 (fn [m] m))))
(def m100 (raw-deref))

;; The seat's working direct read on the raw Eve map. JVM: clojure.core/get
;; (EveHashMap implements ILookup there). Jank, this line: core/get does NOT
;; dispatch into EveHashMap (returns nil — the get-behavior hook is a known
;; unlanded item), so the direct read is eve.core/get, Eve's own API.
(defn- direct-get [m k]
  #?(:clj (get m k)
     :jank (get m k)))
(def counter (e/atom 0))

(def kw100 (mapv #(keyword (str "k" %)) (range 100)))
(def ks (int-array (shuffle (range 10000))))
(def ki (volatile! 0))
(defn nk ^long [^long n]
  (rem (aget ^ints ks (vswap! ki (fn [^long x] (if (== x 9999) 0 (inc x))))) n))
(defn k100 [] (nth kw100 (nk 100)))

#?(:clj (defn- guarded-get [] (g/with-guard (get @a100 (k100)))))
#?(:clj (defn- guarded-get-fixed [] (g/with-guard (get @a100 :k42))))
;; jank's analyzer resolves symbols in untaken runtime branches too; these
;; stubs are never called (has-guard is false on jank).
#?(:jank (defn- guarded-get [] nil))
#?(:jank (defn- guarded-get-fixed [] nil))

;; ---------------------------------------------------------------------------
;; Shapes: [label f checksum-f expected]
;; ---------------------------------------------------------------------------
(defn shapes []
  (let [alt (volatile! 0)]
    (cond-> [["S1a sparse (get @a k)"        (fn [] (get @a100 (k100))) (fn [] (get @a100 :k42)) 42]
             ["S2 direct get, 100-map"      (fn [] (direct-get m100 (k100)))  (fn [] (direct-get m100 :k42))  42]
             ["S4a deref @a (whole-root)"   (fn [] @a100)              (fn [] (count @a100))    100]]
      has-guard
      (conj ["S1b guarded (get @a k)" (fn [] (guarded-get)) (fn [] (guarded-get-fixed)) 42])
      :always
      (conj ["S3a swap! inc" (fn [] (swap! counter inc)) (fn [] @counter) :any]
            ["S3b reset! alt" (fn [] (reset! counter (vswap! alt (fn [x] (if (== x 0) 1 0))))) (fn [] @counter) :any]
            ["S4b raw root handle (guarded/pinned deref)"
             (fn [] (raw-deref))
             (fn [] (ec/count (raw-deref))) 100]))))

;; ---------------------------------------------------------------------------
;; Measurement (heap-grade: calibrate ~40 ms, min of 3 batches, warmup first)
;; ---------------------------------------------------------------------------
(defn calibrate ^long [f]
  (dotimes [_ 3] (f))
  (let [t0 (now-ns)] (dotimes [_ 5] (f))
    (let [per (/ (double (- (now-ns) t0)) 5)]
      (long (max 5 (min 2000000 (/ 4e7 per)))))))

(defn warm
  "Heap-grade warmup: ~0.3 s of continuous calls before any measurement.
  Without it a JIT seat measures interpreter/C1 code (first version of this
  bench measured JVM direct-get at 4.8 us vs the banked 0.42 us for exactly
  this reason — calibration from cold calls also under-sizes the batches)."
  [f]
  (let [t-end (+ (now-ns) 300000000)]
    (while (< (now-ns) t-end) (dotimes [_ 100] (f)))))

(defn measure [f n]
  (dotimes [_ n] (f)) ;; warmup batch
  (apply min
         (for [_ (range 3)]
           (let [t0 (now-ns)]
             (dotimes [_ n] (f))
             (/ (double (- (now-ns) t0)) n)))))

(defn fmt [ns]
  (cond (> ns 1e6) (format "%.2f ms" (/ ns 1e6))
        (> ns 1e3) (format "%.2f us" (/ ns 1e3))
        :else (format "%.1f ns" ns)))

(defn run-bench [probe?]
  (stage (str "setup seat=" seat " has-guard=" has-guard " m100-type=" (type m100)))
  (doseq [[label f cf expected] (shapes)]
    (try
      (let [n (if probe? 20 (do (warm f) (calibrate f)))
            _ (when (and cf (not= expected :skip))
                (let [got (cf)]
                  (when (and (not= expected :any) (not= got expected))
                    (throw (ex-info (str "checksum mismatch: got " got " want " expected) {})))))
            r (if probe? (do (f) :probe-ok) (measure f n))]
        (println (pr-str {:seat seat :shape label :ns-op (when (number? r) r) :readable (when (number? r) (fmt r)) :n n :result (when-not (number? r) r)})))
      (catch #?(:jank :default :clj Throwable) e
        (println (pr-str {:seat seat :shape label :error (str e)})))))
  (stage "done"))

(defn -main [& [mode]]
  #?(:jank (require 'eve.host-completion))
  (run-bench (= mode "probe"))
  #?(:clj (System/exit 0)
     :jank (try ((v 'eve.process/exit!) 0) (catch :default _ (cpp/exit 0)))))
