;; nbb_worker.cljs — the nbb participant of the cooperative disk-atom scenarios: eve.atom under nbb (ClojureScript
;; on SCI, Node; eve's native addon from the working directory, eve/) joins the mmap atom eve/main at a base path.
;;
;;   nbb --classpath <eve>/src:<eve's :nbb roots in eve/hosts.edn>:<eve>/test <eve>/test/coop/workers/nbb_worker.cljs <mode> <base> [args...]
;;     read         <base>                 the value as EDN
;;     contend      <base> <writer> <n>    n × swap! coop.scenario/contend-op; prints {:ops :ms}
;;     mix-contend  <base> <writer> <n>    n × swap! coop.mix/mix-op (the capstone); prints {:ops :ms}
;;     mix-snapshot <base>                 prints coop.mix/snapshot of the value
(ns nbb-worker
  (:require [eve.atom :as atom]
            [eve.region :as region]
            [eve.mem :as mem]
            [coop.scenario :as sc]
            [coop.mix :as mix]))

;; the addon by its path, as eve's Node worker loads it: this script is outside eve/, where node-gyp-build's own
;; lookup (from the script's directory) does not reach
(region/load-native-addon! (js/require (str (.cwd js/process) "/build/Release/mmap_cas.node")))

(let [[mode base & more] *command-line-args*
      d (atom/persistent-atom-domain base)
      a (atom/persistent-atom {:id :eve/main :persistent base} nil)]
  (case mode
    "read" (println (pr-str (sc/plain @a)))
    "contend" (let [w (first more) n (js/parseInt (second more) 10) t0 (js/Date.now)]
                (dotimes [i n] (swap! a sc/contend-op w i))
                (println (pr-str {:ops n :ms (- (js/Date.now) t0)})))
    "mix-contend" (let [w (first more) n (js/parseInt (second more) 10) t0 (js/Date.now)]
                    (dotimes [i n] (swap! a mix/mix-op w i))
                    (println (pr-str {:ops n :ms (- (js/Date.now) t0)})))
    "mix-snapshot" (println (pr-str (mix/snapshot @a))))
  (atom/close-atom-domain! d))
