;; cljr_worker.clj — the ClojureCLR participant of the cooperative disk-atom scenarios: eve.atom under ClojureCLR
;; (eve's CLR regions: a MemoryMappedFile view, Interlocked/Volatile through DynamicMethods, futex through libc)
;; joins the mmap atom eve/main at a base path and takes its turn with coop.scenario or coop.mix.
;;
;;   CLOJURE_LOAD_PATH=<eve>/src:<eve's :cljr roots in eve/hosts.edn>:<eve>/test cljr <eve>/test/coop/workers/cljr_worker.clj <mode> <base> [args...]
;;     read     <base>                 the value as EDN
;;     rt-step  <base> <tag>           prints the value read before it, then swap! coop.scenario/rt-step
;;     contend  <base> <writer> <n>    n × swap! coop.scenario/contend-op; prints {:ops :ms}
;;     mix-contend <base> <writer> <n> n × swap! coop.mix/mix-op (the capstone); prints {:ops :ms}
;;     mix-snapshot <base>             prints coop.mix/snapshot of the value
(require '[eve.atom :as atom] '[coop.scenario :as sc] '[coop.mix :as mix])

(defn- plain [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(plain k) (plain v)])) x)
        (set? x) (into #{} (map plain) x)
        (vector? x) (mapv plain x)
        (seq? x) (apply list (map plain x))
        :else x))

(defn- now-ms [] (.ToUnixTimeMilliseconds DateTimeOffset/UtcNow))

(let [[mode base & more] *command-line-args*
      d (atom/persistent-atom-domain base)
      a (atom/lookup-or-create-mmap-atom! d "eve/main" nil)]
  (case mode
    "read" (println (pr-str (plain @a)))
    "rt-step" (do (println (pr-str (plain @a)))
                  (swap! a sc/rt-step (first more)))
    "contend" (let [w (first more) n (parse-long (second more)) t0 (now-ms)]
                (dotimes [i n] (swap! a sc/contend-op w i))
                (println (pr-str {:ops n :ms (- (now-ms) t0)})))
    "mix-contend" (let [w (first more) n (parse-long (second more)) t0 (now-ms)]
                    (dotimes [i n] (swap! a mix/mix-op w i))
                    (println (pr-str {:ops n :ms (- (now-ms) t0)})))
    "mix-snapshot" (println (pr-str (mix/snapshot @a))))
  (atom/close-atom-domain! d))
