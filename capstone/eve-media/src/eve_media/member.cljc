(ns eve-media.member
  "One eve-media member: a process of some host that joins the domain's job atom with the caps its eve-media.tasks
   declares and serves the pools it can run. The member the launcher names coordinator (control :config :coordinator —
   the JVM's) also runs the coordinator's passes. There is no election: the coordinator is named, and when it dies the
   supervisor restarts it and it carries on from what the domain holds. The same source on the JVM, babashka,
   glojure and jolt; the host's own part is its eve-media.tasks (members/<host>/src).

     <host runner> -m eve-media.member <base> <out-dir> <window> <rid> [<gateway> <api-key>]

   Prints EDN lines {:step ...}."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve.thread.domain :as dom]
            [eve-media.core :as em]
            [eve-media.coordinator :as coord]
            [eve-media.domain :as domain]
            [eve-media.state :as st]
            [eve-media.tasks :as tasks]))

(defn ctx [] @st/ctx)

(defn say [m] (println (pr-str (assoc m :rid (:rid @st/ctx) :t (proc/now-ms)))))

(defn- stop? [control] (:stop (domain/read-atom control)))

(defn serve!
  "Join and serve (and, as the coordinator, coordinate) until the launcher sets :stop in the control atom."
  [base out window rid gateway api-key]
  (let [atoms (if-let [open (:open tasks/options)]
                ;; a member whose host must not write the disk domain joins over the network rung (eve-media.domain)
                (open gateway api-key)
                (domain/open-atoms base))
        dh (dom/attach! (:jobs atoms)
                        {:rid rid
                         :ttl (or (:ttl tasks/options) em/ttl)
                         :caps (vec tasks/caps)
                         :requires (quote [eve-media.tasks])})
        coordinator? (= rid (get-in (domain/read-atom (:control atoms)) [:config :coordinator]))]
    (reset! st/ctx
            (merge atoms {:dh dh :rid rid :base base :out out :window window}))
    (tasks/init! (ctx))
    ;; every pool registered (a submitter may submit before a server joins), then this host's own served
    ;; (a member over the network rung registers only its own: each registration is a network swap)
    (when-not (:open tasks/options)
      (doseq [nm em/pools]
        (dom/pool dh nm {:retry :restart})))
    (doseq [[nm n] tasks/pools]
      (dom/pool dh nm {:threads n :retry :restart}))
    (when coordinator?
      (dom/pool dh (em/group-pool rid) {:threads 1 :retry :restart}))
    (domain/swap-atom! (:control atoms) assoc-in [:members rid :joined] true)
    (say {:step :joined
          :caps tasks/caps
          :pools (vec (keys tasks/pools))
          :coordinator coordinator?})
    (loop []
      (if (stop? (:control atoms))
        (do
          (say {:step :stopping})
          (dom/detach! dh)
          (say {:step :stopped}))
        (do
          (when coordinator?
            (let [[ok? e] (tasks/attempt (fn [] (coord/pass! (ctx))))]
              (when-not ok? (say {:step :coordinator-error :error (str e)}))))
          ;; (a member over the network rung polls less often: each poll of the control atom is a network read)
          (proc/sleep-ms! (or (:poll-ms tasks/options) 250))
          (recur))))))

(defn -main [& [base out window rid gateway api-key]]
  (let [[ok? e] (tasks/attempt (fn [] (serve! base out window rid gateway api-key)))]
    (when-not ok? (say {:step :error :error (str e)}))
    (tasks/exit! (if ok? 0 1))))
