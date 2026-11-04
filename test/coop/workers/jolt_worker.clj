;; jolt_worker.clj — the jolt participant of the cooperative disk-atom scenarios: eve.atom under jolt (Clojure on
;; Chez Scheme; eve's jolt regions: an mmap'd file through jolt.ffi, atomics from libatomic, futex through libc)
;; joins the mmap atom eve/main at a base path and takes its turn with coop.scenario or coop.mix.
;;
;;   jolt -Scp <eve>/src:<eve's :jolt roots in eve/hosts.edn>:<eve>/test <eve>/test/coop/workers/jolt_worker.clj <mode> <base> [args...]
;;     read     <base>                 the value as EDN
;;     rt-step  <base> <tag>           prints the value read before it, then swap! coop.scenario/rt-step
;;     contend  <base> <writer> <n>    n × swap! coop.scenario/contend-op; prints {:ops :ms}
;;     mix-contend <base> <writer> <n> n × swap! coop.mix/mix-op (the capstone); prints {:ops :ms}
;;     mix-snapshot <base>             prints coop.mix/snapshot of the value
;;
;; Ends with System/exit: a jolt script whose process has started a thread (eve's heartbeat) does not exit at its
;; last form.
(require '[eve.atom :as atom] '[coop.scenario :as sc] '[coop.mix :as mix])

(defn- plain [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(plain k) (plain v)])) x)
        (set? x) (into #{} (map plain) x)
        (vector? x) (mapv plain x)
        (seq? x) (apply list (map plain x))
        :else x))

(let [[mode base & more] *command-line-args*
      d (atom/persistent-atom-domain base)
      a (atom/lookup-or-create-mmap-atom! d "eve/main" nil)]
  (case mode
    "read" (println (pr-str (plain @a)))
    "rt-step" (do (println (pr-str (plain @a)))
                  (swap! a sc/rt-step (first more)))
    "contend" (let [w (first more) n (parse-long (second more)) t0 (System/currentTimeMillis)]
                (dotimes [i n] (swap! a sc/contend-op w i))
                (println (pr-str {:ops n :ms (- (System/currentTimeMillis) t0)})))
    "mix-contend" (let [w (first more) n (parse-long (second more)) t0 (System/currentTimeMillis)]
                    (dotimes [i n] (swap! a mix/mix-op w i))
                    (println (pr-str {:ops n :ms (- (System/currentTimeMillis) t0)})))
    "mix-snapshot" (println (pr-str (mix/snapshot @a))))
  (atom/close-atom-domain! d)
  (flush)
  (System/exit 0))
