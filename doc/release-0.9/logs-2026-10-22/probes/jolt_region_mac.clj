;; jolt_region_mac.clj — jolt's region adapter's macOS arm (adapters/region/jolt-ffi: libSystem's OSAtomic barrier
;; functions — a compare-and-swap that answers a boolean, an add that answers the new value — with the value-answering
;; compare-and-swap, the exchange and the store built over them, and the polled wait) run on Linux, over shims of
;; those two functions on libatomic: eight threads contending on one word, each op checked against its contract.
;;
;;   jolt -Scp "src:<hosts.edn :jolt roots>" doc/release-0.9/logs-2026-10-22/probes/jolt_region_mac.clj
(require '[eve.region :as r]
         '[jolt.ffi :as ffi])

(def mac-cas (deref #'r/mac-cas))
(def mac-xchg (deref #'r/mac-xchg))
(def poll-wait (deref #'r/poll-wait))
(def load4 (deref #'r/load4))
(def store4 (deref #'r/store4))
(def jolt-cas4 (deref #'r/jolt-cas4))
(def jolt-add4 (deref #'r/jolt-add4))

;; OSAtomicCompareAndSwap32Barrier(old, new, p) -> bool, and OSAtomicAdd32Barrier(d, p) -> the new value, on libatomic
(defn os-cas4 [old new p]
  (let [e (ffi/alloc 8)]
    (try (ffi/write e :int32 old) (jolt-cas4 p e new 5 5) (finally (ffi/free e)))))
(defn os-add4 [d p] (unchecked-int (+ (jolt-add4 p d 5) d)))

(def word (ffi/alloc 8))
(store4 word 0)

(defn now [] (System/currentTimeMillis))
(def threads 8)
(def per 2000)

;; 1. increments by the value-answering CAS (retry until the witness is the value read): no update lost
(let [t0 (now)
      fs (doall (for [_ (range threads)]
                  (future (dotimes [_ per]
                            (loop []
                              (let [v (load4 word)]
                                (when-not (= v (mac-cas os-cas4 os-add4 word v (unchecked-int (inc v))))
                                  (recur))))))))]
  (run! deref fs)
  (println (pr-str {:probe :cas-increments :threads threads :each per :expected (* threads per) :got (load4 word)
                    :ok (= (* threads per) (load4 word)) :ms (- (now) t0)})))

;; 2. the witness: a CAS against a stale expected answers the word's value and leaves it
(store4 word 41)
(let [w (mac-cas os-cas4 os-add4 word 7 99)]
  (println (pr-str {:probe :cas-witness :got w :word (load4 word) :ok (and (= 41 w) (= 41 (load4 word)))})))

;; 3. exchanges: every value written is answered by exactly one exchange or is the last one left
(store4 word -1)
(let [answers (doall (for [t (range threads)]
                       (future (vec (for [i (range per)] (mac-xchg os-cas4 os-add4 word (+ (* t per) i)))))))
      seen (concat (mapcat deref answers) [(load4 word)])
      written (conj (set (range (* threads per))) -1)]
  (println (pr-str {:probe :exchanges :values (count seen) :distinct (count (set seen))
                    :ok (and (= (count seen) (inc (* threads per))) (= (set seen) written))})))

;; 4. the polled wait: :not-equal at once, :timed-out after its time, :ok soon after another thread's store
(store4 word 5)
(let [t0 (now) a (poll-wait word 4 1000) t1 (now) b (poll-wait word 5 200) t2 (now)
      f (future (Thread/sleep 50) (store4 word 6))
      c (poll-wait word 5 5000) t3 (now)]
  @f
  (println (pr-str {:probe :poll-wait :not-equal [a (- t1 t0)] :timed-out [b (- t2 t1)] :woken [c (- t3 t2)]
                    :ok (and (= :not-equal a) (< (- t1 t0) 50) (= :timed-out b) (<= 200 (- t2 t1) 400)
                             (= :ok c) (<= 50 (- t3 t2) 300))})))
(ffi/free word)
