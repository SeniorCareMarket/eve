(ns eve-media.node-member
  "The Node member (compiled ClojureScript, eve's N-API addon for the disk domain): it joins the job atom with the :npm
   cap and pulls the :npm pool from Node's event loop — claim one descriptor (a swap), run its task (a promise:
   sharp and music-metadata are asynchronous), complete it (a swap) — renewing its lease every ttl/4. Its handle has no
   threads of its own (attach! :threads false): an eve.thread pump thread would be a worker isolate, and npm's libraries
   are already off the main thread. Never the coordinator (the launcher names the JVM's member).

     node target/node-member.js <base> <out-dir> <window> <rid>"
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve.thread.domain :as dom]
            [eve.thread.meta :as mt]
            [eve.thread.task :as task]
            [eve-media.core :as em]
            [eve-media.domain :as domain]
            [eve-media.state :as st]
            [eve-media.tasks :as tasks]))

(defn- say [m] (println (pr-str (assoc m :rid (:rid @st/ctx) :t (proc/now-ms)))))

(defn- error-data [e] {:class "js/Error" :message (or (.-message e) (str e))})

(defn- run-claimed
  "x's task called with its arguments: a promise of its value."
  [x]
  (let [{:keys [task args]} (:work x)]
    (js/Promise.resolve (apply (task/task-fn task) args))))

(defn main [& [base out window rid]]
  (let [atoms (domain/open-atoms base)
        dh (dom/attach! (:jobs atoms) {:rid rid :ttl em/ttl :caps (vec tasks/caps) :threads false})
        tid "main"]
    (reset! st/ctx (merge atoms {:dh dh :rid rid :base base :out out :window window}))
    (doseq [nm em/pools] (dom/pool dh nm {:retry :restart}))
    (dom/swap-meta! dh (fn [v] [(mt/thread-up v rid tid) nil]))
    (swap! (:control atoms) assoc-in [:members rid :joined] true)
    (say {:step :joined :caps tasks/caps :pools [:npm] :coordinator false})
    (let [beat (js/setInterval (fn [] (dom/beat! dh [tid] {} 0)) (quot em/ttl 4))
          stop! (fn []
                  (js/clearInterval beat)
                  (say {:step :stopping})
                  (dom/detach! dh)
                  (say {:step :stopped})
                  (js/process.exit 0))]
      (letfn [(pump []
                (if (:stop @(:control atoms))
                  (stop!)
                  (let [{:keys [claimed]} (dom/claim! dh :npm tid 1)]
                    (if-let [x (first claimed)]
                      (-> (run-claimed x)
                          (.then (fn [v] (dom/complete! dh (:id x) (:claim x) true v)))
                          (.catch (fn [e]
                                    (say {:step :task-failed :id (:id x) :error (error-data e)})
                                    (dom/complete! dh (:id x) (:claim x) false (error-data e))))
                          (.finally (fn [] (js/setTimeout pump 0))))
                      (js/setTimeout pump 100)))))]
        (pump)))))
