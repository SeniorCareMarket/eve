(ns cohost-media.member
  "One cohost-media member: it joins the domain's job atom with the caps its pool config declares and
   serves the pools the config sizes. The config is DATA the boot program hands the pool (an argument
   here; cohost-media.boot holds the table) — every runtime loads the same namespaces, this one
   included, and the same cohost-media.tasks; what differs per member is the config, never the code.
   The member the boot program names coordinator (control :config :coordinator) also runs the
   coordinator's passes. There is no election: the coordinator is named, and when a member dies its
   pool's watch starts it again and it carries on from what the domain holds.

     <host runner> -m cohost-media.member <base> <out-dir> <window> <rid> <config-edn> [<gateway> <api-key>]

   Prints EDN lines {:step ...}."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.process :as proc]
            [eve.thread.domain :as dom]
            [eve.thread.meta :as mt]
            [eve.thread.rung :as rung]
            [eve.thread.task :as task]
            [cohost-media.core :as em]
            [cohost-media.coordinator :as coord]
            [cohost-media.domain :as domain]
            [cohost-media.media :as media]
            [cohost-media.state :as st]
            [cohost-media.tasks :as tasks]))

(defn ctx [] @st/ctx)

(def pool-configs
  "Each member's pool config, handed to its pool as data by the boot program (cohost-media.boot reads
   this table; serve! takes the member's entry as an argument): caps claim job kinds, pools size the
   member's own queues, options tune its serve loop, :init names a facility to start, :facilities
   names the facility implementations its tasks dispatch to. Every runtime loads the same namespaces;
   this table is all that differs per member. (The five per-member tasks files' caps/pools/options
   vars, gathered.)"
  {:bb {:caps #{:index}
        :pools {:index 1}
        :options {}}
   :jvm {:caps #{:meta :phash :transcode-cli :pcm-cli :loudness}
         :pools {:meta 2 :phash 1 :transcode 1 :pcm 1 :loudness 1}
         :options {}}
   :jolt {:caps #{:transcode-native :pcm-native}
          :pools {:transcode 1 :pcm 1}
          :options {}
          :facilities {:av :native}}
   :glj {:caps #{:serve}
         :pools {:publish 1}
         :options {}
         :init :media}
   :node {:caps #{:npm}
          :pools {}
          :options {}}})

(defn say [m] (println (pr-str (assoc m :rid (:rid @st/ctx) :t (proc/now-ms)))))

(defn attempt
  "Run f, answering [true value] or [false error] — each host's own catch, here in the one member
   namespace instead of repeated per tasks file. The cljs arm catches :default: Throwable
   names no cljs type, so the shared arm analyzed there referenced an undeclared var and
   its catch could not fire as written (the analyzer's warning named it, 2026-11-17)."
  [f]
  #?(:glj (try [true (f)] (catch go/any e [false e]))
     :cljs (try [true (f)] (catch :default e [false e]))
     :default (try [true (f)] (catch Throwable e [false e]))))

(defn exit!
  "Leave the process with code — each host's own way out. A realm member (cljs) has no
   process of its own to leave: the seat owns the process and node-serve! detaches
   instead, so an exit! from a realm is a programming error and throws. (The arm it
   replaces named System/exit and shutdown-agents unconditionally, which analyze on
   no cljs host — the warnings the sweep removed.)"
  [code]
  #?(:glj (os.Exit code)
     :cljs (throw (ex-info "member: exit! has no realm arm — the seat owns the process; node-serve! detaches"
                           {:code code}))
     :default (do (try (shutdown-agents) (catch Throwable _ nil))
                  (System/exit code))))

(defn- stop?
  "The control atom's :stop flag, against cache [root-pointer flag]: re-read in full only when the atom's
   root pointer moved (eve.atom/version's second word — two i32 loads). A full deref of an eve atom costs
   ~100 ms of interpreter time on the slow hosts (measured on bb at 84bfc7c8: 102–272 ms for a tiny value),
   and this loop polls for the whole run — polling the value pinned a core. A net-rung
   control atom has no version: read it (its poll is a network fetch by design, at :poll-ms)."
  [cache control]
  (if (satisfies? rung/IRung control)
    (:stop (domain/read-atom control))
    (let [p (second (ea/version control))]
      (if (= p (first @cache))
        (second @cache)
        (let [s (:stop (domain/read-atom control))]
          (reset! cache [p s])
          s)))))

(defn serve!
  "Join and serve (and, as the coordinator, coordinate) until the boot program sets :stop in the
   control atom. config is the member's pool config: {:caps #{...} :pools {pool threads}
   :options {...} :init :media (optional) :facilities {:av :native} (optional)}."
  [base out window rid gateway api-key {:keys [caps pools options init facilities] :as config}]
  (let [atoms (domain/open-atoms base)
        dh (dom/attach! (:jobs atoms)
                        {:rid rid
                         :ttl (or (:ttl options) em/ttl)
                         :caps (vec caps)
                         :requires (quote [cohost-media.tasks])})
        coordinator? (= rid (get-in (domain/read-atom (:control atoms)) [:config :coordinator]))]
    (reset! st/ctx
            (merge atoms {:dh dh :rid rid :base base :out out :window window
                          :gateway gateway :api-key api-key :av (:av facilities)}))
    (case init
      nil nil
      :media (media/init! (ctx))
      (throw (ex-info "member: unknown :init in pool config" {:init init})))
    ;; every pool registered (a submitter may submit before a server joins), then this member's own served
    (doseq [nm em/pools]
      (dom/pool dh nm {:retry :restart}))
    (doseq [[nm n] pools]
      (dom/pool dh nm {:threads n :retry :restart}))
    (when coordinator?
      (dom/pool dh (em/group-pool rid) {:threads 1 :retry :restart}))
    (domain/swap-atom! (:control atoms) assoc-in [:members rid :joined] true)
    (say {:step :joined
          :caps caps
          :pools (vec (keys pools))
          :coordinator coordinator?})
    (loop [stop-cache (atom [nil false])]
      (if (stop? stop-cache (:control atoms))
        (do
          (say {:step :stopping})
          (dom/detach! dh)
          (say {:step :stopped}))
        (do
          (when coordinator?
            (let [[ok? e] (attempt (fn [] (coord/pass! (ctx))))]
              (when-not ok? (say {:step :coordinator-error :error (str e)}))))
          ;; (the poll reads the whole control atom — on an interpreted host a full deref is tens of
          ;; ms of CPU: at 250 ms that was a fifth of a core, forever, work or no work. 1000 ms: a
          ;; stop is seen within a second, which the boot program's seconds-long shutdown absorbs,
          ;; and the coordinator's pass runs at 1 Hz)
          (proc/sleep-ms! (or (:poll-ms options) 1000))
          (recur stop-cache))))))

;; ---------------------------------------------------------------------------
;; The node member's life, in a cohost :node realm (cohost-media.start starts
;; the seat and calls node-serve! there): the same member on Node's event loop
;; instead of on a thread. Attach with :threads false (an eve.thread pump
;; thread would be a worker isolate, and npm's libraries are already off the
;; main thread), pull the :npm pool — claim (a swap), run the task (a promise:
;; sharp and music-metadata are asynchronous), complete (a swap) — and renew
;; the lease every ttl/4. Stop is the control atom's :stop, seen by the pump:
;; clear the beat, detach, and node-state reads :stopped — never
;; js/process.exit; the seat owns the realm process and its close! ends it.
;; (The node member's former standalone main, folded in here when the node/
;; folder went: the member is one namespace on every seat. Never the
;; coordinator — the boot program names the coordinator, and it is never
;; this one.)

#?(:cljs
   (do
     (defonce ^:private node-life (atom :starting))

     (defn node-state
       "The node member's life, as the boot program's watch reads it:
        :starting, :running, :stopped."
       []
       @node-life)

     (defn- node-error-data [e] {:class "js/Error" :message (or (.-message e) (str e))})

     (defn- node-run-claimed
       "x's task called with its arguments: a promise of its value."
       [x]
       (let [{:keys [task args]} (:work x)]
         (js/Promise.resolve (apply (task/task-fn task) args))))

     (defn node-serve!
       "Join the domain at base and pull the :npm pool until :stop (see the
        section comment). config is the member's pool config, as data — the
        same table serve! takes. Answers :installed: the member's life runs
        on the realm's timers from here, and node-state reports it."
       [base out window rid config]
       (let [caps (:caps config)
             atoms (domain/open-atoms base)
             dh (dom/attach! (:jobs atoms) {:rid rid :ttl em/ttl :caps (vec caps) :threads false})
             tid "main"]
         (reset! st/ctx (merge atoms {:dh dh :rid rid :base base :out out :window window}))
         (doseq [nm em/pools] (dom/pool dh nm {:retry :restart}))
         (dom/swap-meta! dh (fn [v] [(mt/thread-up v rid tid) nil]))
         (swap! (:control atoms) assoc-in [:members rid :joined] true)
         (reset! node-life :running)
         (say {:step :joined :caps caps :pools [:npm] :coordinator false})
         (let [beat (js/setInterval (fn [] (dom/beat! dh [tid] {} 0)) (quot em/ttl 4))
               stop! (fn []
                       (js/clearInterval beat)
                       (say {:step :stopping})
                       (dom/detach! dh)
                       (reset! node-life :stopped)
                       (say {:step :stopped}))]
           (letfn [(pump []
                     (if (:stop @(:control atoms))
                       (stop!)
                       (let [{:keys [claimed]} (dom/claim! dh :npm tid 1)]
                         (if-let [x (first claimed)]
                           (-> (node-run-claimed x)
                               (.then (fn [v] (dom/complete! dh (:id x) (:claim x) true v)))
                               (.catch (fn [e]
                                         (say {:step :task-failed :id (:id x) :error (node-error-data e)})
                                         (dom/complete! dh (:id x) (:claim x) false (node-error-data e))))
                               (.finally (fn [] (js/setTimeout pump 0))))
                           (js/setTimeout pump 100)))))]
             (pump)))
         :installed))))

(defn -main [& [base out window rid a b c]]
  ;; the boot program's shape: [base out window rid config-edn gateway api-key]; a caller of the
  ;; older shape ([base out window rid gateway api-key], a supervisor started before the boot
  ;; program existed) gets its config from pool-configs by rid
  (let [config-edn? (and a (str/starts-with? (str/trim (str a)) "{"))
        config (if config-edn? (edn/read-string a) (get pool-configs (keyword rid)))
        gateway (if config-edn? b a)
        api-key (if config-edn? c b)
        [ok? e] (attempt (fn [] (serve! base out window rid gateway api-key config)))]
    (when-not ok? (say {:step :error :error (str e)}))
    (exit! (if ok? 0 1))))
