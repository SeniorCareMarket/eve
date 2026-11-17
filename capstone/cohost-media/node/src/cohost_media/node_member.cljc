(ns cohost-media.node-member
  "The Node member (compiled ClojureScript, eve's N-API addon for the disk domain): it joins the job atom with the caps
   its pool config declares (an argument, handed by the boot program like every member's) and pulls the :npm pool from
   Node's event loop — claim one descriptor (a swap), run its task (a promise: sharp and music-metadata are
   asynchronous), complete it (a swap) — renewing its lease every ttl/4. Its handle has no threads of its own
   (attach! :threads false): an eve.thread pump thread would be a worker isolate, and npm's libraries
   are already off the main thread. Never the coordinator (the boot program names the coordinator).

     node target/node-member.js <base> <out-dir> <window> <rid> <config-edn>

   As a .cljc the member is the :cljs arm entire: it is a compiled-ClojureScript program, and no
   other seat loads this namespace (page.cljc's shape)."
  (:refer-clojure :exclude [swap!])
  #?(:cljs
     (:require ;; the type registrations the deftype macros emit (eve.type-readers/register!,
               ;; eve.flat.sister/register-sister!) run at load in the type-defining namespaces,
               ;; which do not require the registrars — this entry requires them first, so both
               ;; are loaded before any type in its graph registers
               [eve.type-readers]
               [eve.flat.sister]
               [cljs.reader :as reader]
               [eve.host :refer [swap!]]
               [eve.process :as proc]
               [eve.thread.domain :as dom]
               [eve.thread.meta :as mt]
               [eve.thread.task :as task]
               [cohost-media.core :as em]
               [cohost-media.domain :as domain]
               [cohost-media.state :as st]
               [cohost-media.tasks :as tasks])))

#?(:cljs
   (do
     (defn- say [m] (println (pr-str (assoc m :rid (:rid @st/ctx) :t (proc/now-ms)))))

     (defn- error-data [e] {:class "js/Error" :message (or (.-message e) (str e))})

     (defn- run-claimed
       "x's task called with its arguments: a promise of its value."
       [x]
       (let [{:keys [task args]} (:work x)]
         (js/Promise.resolve (apply (task/task-fn task) args))))

     (defn main [& [base out window rid config-edn]]
       (let [config (reader/read-string config-edn)
             caps (:caps config)
             atoms (domain/open-atoms base)
             dh (dom/attach! (:jobs atoms) {:rid rid :ttl em/ttl :caps (vec caps) :threads false})
             tid "main"]
         (reset! st/ctx (merge atoms {:dh dh :rid rid :base base :out out :window window}))
         (doseq [nm em/pools] (dom/pool dh nm {:retry :restart}))
         (dom/swap-meta! dh (fn [v] [(mt/thread-up v rid tid) nil]))
         (swap! (:control atoms) assoc-in [:members rid :joined] true)
         (say {:step :joined :caps caps :pools [:npm] :coordinator false})
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
             (pump)))))))
