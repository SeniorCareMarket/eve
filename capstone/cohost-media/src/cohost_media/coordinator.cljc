(ns cohost-media.coordinator
  "The coordinator — run by the one member the launcher names (control :config :coordinator: the JVM's; babashka's in a
   run without a JVM), no election. Each pass it: plans every :new file's DAG and submits it
   into the pools in ONE swap of the metadata — deferring, and naming in the library entry, the jobs whose caps no
   lease-alive runtime covers, and submitting those on the first later pass whose coverage reaches them; reads the jobs' descriptors and mirrors their states into the library;
   resubmits a job that failed, escalating it after three failures; schedules the index (and, on its own queue, the
   duplicate grouping) once every file is settled; and asks the supervisor to restart a member whose lease lapsed.
   Everything it knows is in the domain, so when the supervisor restarts it, it carries on where it stopped.

   Portable cljc over eve.thread.domain and eve.thread.meta's pure moves."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve.thread.domain :as dom]
            [eve.thread.meta :as mt]
            [cohost-media.core :as em]
            [cohost-media.domain :as domain]))

(def max-failures 3)

(def join-wait
  "How long the coordinator waits, ms from a launched member's start, for it to join before planning without it."
  120000)

(defn- offered-caps [dh]
  (reduce into #{} (map (fn [[_ r]] (set (:caps r))) (:runtimes (dom/registry dh)))))

(defn- live-caps
  "The caps the lease-alive runtimes offer now — planning's coverage. Read from the metadata's runtimes on
   mt/runtime-alive?'s own terms (the registry view strips the leases): a runtime whose lease lapsed covers
   nothing until it beats again, and one that has not joined covers nothing yet. Recomputed every pass, so a
   member that joins mid-run covers the jobs deferred for its caps from that pass on."
  [dh]
  (let [runtimes (dom/read-meta dh [:runtimes])
        m {:runtimes runtimes}
        now (proc/now-ms)]
    (reduce (fn [caps [rid r]]
              (if (mt/runtime-alive? m rid now) (into caps (set (:caps r))) caps))
            #{} runtimes)))

(defn- work [kind args] {:task (em/task-sym kind) :args args})

(defn submit-dag!
  "The file entry e's (path p) jobs submitted in one swap of the job atom's metadata — those whose :needs the
   lease-alive runtimes cover. A job whose needs no live runtime covers (or whose input is itself deferred) is
   DEFERRED, not submitted: {:jobs {kind id} :deferred {kind {:needs :missing}}}. A descriptor queued for a cap
   no member of the shape advertises would sit :queued in silence while its file reads planned and can never
   complete (heron #52: 23 in the bb,jvm shape); a deferred job is named in the library entry and in the
   progress report, and supervise! submits it on the first pass whose coverage reaches it."
  [dh p e out-dir window]
  (let [offered (offered-caps dh)
        coverage (live-caps dh)
        args {:path p :id (:id e) :kind (:kind e) :out out-dir :window window}
        rid (:rid dh)]
    (dom/swap-meta!
     dh
     (fn [m]
       (let [now (proc/now-ms)
             [m jobs deferred]
             (reduce (fn [[m ids deferred] [kind after]]
                       (let [needs (em/needs kind offered)
                             missing (into (into #{} (remove coverage needs))
                                           (mapcat (fn [a] (:missing (get deferred a))) after))]
                         (if (seq missing)
                           [m ids (assoc deferred kind {:needs needs :missing missing})]
                           (let [[m' got] (mt/submit m (em/job-pool kind) [(work kind [args])] rid
                                                     {:retry :restart :needs needs
                                                      :after (mapv ids after) :scope (:id e)}
                                                     now)]
                             (if got [m' (assoc ids kind (first got)) deferred]
                               (reduced [m ids deferred]))))))
                     [m {} {}]
                     (em/file-jobs (:kind e) offered))]
         [m {:jobs jobs :deferred deferred}])))))

(defn- submit-one! [dh pool kind args opts]
  (first (dom/swap-meta! dh (fn [m] (let [[m ids] (mt/submit m pool [(work kind [args])] (:rid dh)
                                                             (merge {:retry :restart} opts) (proc/now-ms))]
                                      [m ids])))))

(defn- descs [dh ids] (let [all (dom/read-meta dh [:descs])] (into {} (map (fn [id] [id (get all id)]) ids))))

(defn- settled? [d] (contains? #{:done :failed :cancelled} (:state d)))

(defn- plan-new!
  "Every :new file's DAG submitted — once the members the launcher started have joined (bounded: join-wait ms from
   each one's start), so a job is routed by the caps of the whole set (a slow-starting member's native transcoder, not
   the CLI's because it was still loading)."
  [{:keys [dh library control out window]}]
  (let [now (proc/now-ms)
        pending (some (fn [[_ m]]
                        (and (= :running (:state m)) (not (:joined m))
                             (< (- now (:started m 0)) join-wait)))
                      (:members (domain/read-atom control)))]
    (when-not pending
      (doseq [[p e] (:files (domain/read-atom library))
              :when (= :new (:status e))]
        (let [{:keys [jobs deferred]} (submit-dag! dh p e out window)]
          (when (or (seq jobs) (seq deferred))
            (domain/swap-atom! library
                   (fn [v]
                     (if (= :new (get-in v [:files p :status]))
                       (cond-> (-> v
                                   (assoc-in [:files p :status] :planned)
                                   (assoc-in [:files p :jobs] jobs)
                                   (assoc-in [:files p :planned-by] (:rid dh)))
                         (seq deferred)
                         (assoc-in [:files p :deferred] deferred))
                       v)))))))))

(defn- job-state
  "Job kind k's state for the file entry e: its descriptor d's while the metadata holds it; else :done when the job
   recorded its result in the library (every job records before it completes), :lost when it did not. A descriptor can
   go before this coordinator mirrors it: eve.thread forgets a departed runtime's settled descriptors past a horizon
   (the coordinator that submitted them was killed), and a descriptor whose input was forgotten is cancelled."
  [e k d]
  (cond
    d (:state d)
    (some? (get-in e [:results k])) :done
    :else :lost))

(defn- redo!
  "The jobs of kinds again (a set) of the file entry e at path p submitted afresh, in its DAG's order, each after those
   of its inputs submitted with it (an input already done is not waited on): {kind id}."
  [dh p e again out window]
  (let [offered (offered-caps dh)
        args {:path p :id (:id e) :kind (:kind e) :out out :window window}
        dag (em/file-jobs (:kind e)
                          (cond-> offered
                            (contains? (:jobs e) :waveform) (conj :browser)))]
    (reduce (fn [ids [k after]]
              (if (contains? again k)
                (assoc ids k
                       (submit-one! dh (em/job-pool k) k args
                                    {:needs (em/needs k offered)
                                     :scope (:id e)
                                     :after (vec (keep ids after))}))
                ids)) {} dag)))

(defn- submit-deferred!
  "The file entry e's deferred jobs that coverage now reaches, submitted in the DAG's order, each after the
   inputs the file already has submitted (an input still deferred holds its dependents back another pass):
   {kind id} of the jobs submitted. An input whose descriptor is already forgotten (it settled and its result
   is the record, job-state's reading) is not waited on — a descriptor wired to a forgotten input would be
   cancelled at claim time. The entry e given here carries the file's current :jobs and the :deferred subset
   in play (the caller keeps the ones whose inputs were escalated out of it — those never submit)."
  [dh p e coverage out window]
  (let [offered (offered-caps dh)
        deferred (:deferred e)
        args {:path p :id (:id e) :kind (:kind e) :out out :window window}
        dag (em/file-jobs (:kind e) offered)
        present (volatile! (set (keys (dom/read-meta dh [:descs]))))]
    (second
     (reduce (fn [[ids submitted] [k after]]
               (if (contains? deferred k)
                 (let [needs (em/needs k offered)]
                   (if (and (every? coverage needs)
                            (every? (fn [a] (contains? ids a)) after))
                     (let [id (submit-one! dh (em/job-pool k) k args
                                           {:needs needs
                                            :scope (:id e)
                                            :after (vec (keep (fn [a]
                                                                (let [id (get ids a)]
                                                                  (when (contains? @present id) id)))
                                                              after))})]
                       (if id (do (vswap! present conj id)
                                  [(assoc ids k id) (assoc submitted k id)])
                         [ids submitted]))
                     [ids submitted]))
                 [ids submitted]))
             [(:jobs e) {}]
             dag))))

(defn- supervise!
  "Each planned file's jobs read from their descriptors and mirrored into its library entry (states, attempts, ran-on);
   a job that failed, was cancelled or was lost (job-state) submitted again with its dependents, escalated after
   max-failures; the file settled when every job is. A file's deferred jobs (plan time: no live runtime covered
   their needs) are retried against this pass's coverage: newly covered ones are submitted, one whose input was
   escalated is escalated with it, and the file does not settle while a deferred job remains. Settled descriptors are forgotten from the metadata once mirrored
   (eve.thread.meta/forget): the library is their record, and the metadata — every runtime's hot atom, network
   members' included — stays the size of the work in flight."
  [{:keys [dh library out window]}]
  (let [coverage (live-caps dh)]
    (doseq [[p e] (:files (domain/read-atom library))
            :when (= :planned (:status e))]
    (let [ids (:jobs e)
          ds (descs dh (vals ids))
          states (into {}
                       (map (fn [[k id]] [k (job-state e k (get ds id))]) ids))
          attempts (into {}
                         (map (fn [[k id]]
                                [k
                                 (if-let [d (get ds id)]
                                   (:attempts d)
                                   (get-in e [:attempts k]))]) ids))
          ran-on (into {}
                       (keep (fn [[k id]]
                               (when-let [r (:ran-on (get ds id))] [k r])) ids))
          bad (vec (keep (fn [[k s]]
                           (when (contains? #{:failed :cancelled :lost} s) k))
                         states))
          again (set (filter (fn [k]
                               (< (inc (get-in e [:failures k] 0)) max-failures))
                             bad))
          resubmit (if (seq again) (redo! dh p e again out window) {})
          escalated (vec (remove again bad))
          ;; deferred jobs: submit the ones this pass's coverage reaches; escalate the ones whose input was
          ;; escalated (they can never run); the rest stay deferred, their missing caps refreshed
          deferred (:deferred e)
          dag (when (seq deferred) (into {} (em/file-jobs (:kind e) (offered-caps dh))))
          dead-inputs (into (set escalated) (:escalated e))
          orphan? (fn [k] (boolean (some dead-inputs (get dag k))))
          deferred-escalated (vec (filter orphan? (keys deferred)))
          submittable (reduce dissoc deferred deferred-escalated)
          submitted (if (seq submittable)
                      (submit-deferred! dh p (assoc e :jobs (merge ids resubmit) :deferred submittable)
                                        coverage out window)
                      {})
          deferred-left (into {}
                              (keep (fn [[k info]]
                                      (when-not (or (contains? submitted k) (some #{k} deferred-escalated))
                                        [k (assoc info :missing
                                                  (into #{} (remove coverage (:needs info))))])))
                              deferred)
          states (into states (map (fn [k] [k :queued])) (keys submitted))
          escalated (into escalated deferred-escalated)
          deferred-write (fn [v]
                           (if (empty? deferred-left)
                             (update-in v [:files p] dissoc :deferred)
                             (assoc-in v [:files p :deferred] deferred-left)))
          all-settled (and (empty? deferred-left)
                           (every? (fn [[k s]]
                                     (or (= :done s) (some #{k} escalated))) states))
          forget (vec (concat (keep ids bad) (when all-settled (vals ids))))]
      (domain/swap-atom! library
                         (fn [v]
                           (cond-> (-> v
                                       (assoc-in [:files p :job-state] states)
                                       (assoc-in [:files p :attempts] attempts)
                                       (update-in [:files p :ran-on]
                                         merge
                                         ran-on))
                             (seq bad)
                             (update-in [:files p :failures]
                               (fn [f]
                                 (reduce (fn [f k] (update f k (fnil inc 0)))
                                         (or f {}) bad)))
                             (seq resubmit)
                             (update-in [:files p :jobs] merge resubmit)
                             (seq submitted)
                             (update-in [:files p :jobs] merge submitted)
                             (contains? e :deferred)
                             (deferred-write)
                             (seq escalated)
                             (assoc-in [:files p :escalated] escalated)
                             all-settled
                             (assoc-in [:files p :status]
                               (if (seq escalated) :error :done)))))
      (when (seq forget)
        (dom/swap-meta! dh (fn [m] [(mt/forget m forget) nil])))))))

(defn- index! [{:keys [dh library index]}]
  (let [files (:files (domain/read-atom library))
        gen (count files)
        ix (domain/read-atom index)]
    (when (and (seq files)
               (every? (fn [[_ e]] (contains? #{:done :error} (:status e)))
                       files))
      (cond
        ;; a new generation (files added since the last index): the index job, then the grouping on our queue
        (not= gen (:generation (:job ix)))
        (let [ii (submit-one! dh :index :index {:generation gen}
                              {:needs #{:index}})
              gi (dom/swap-meta! dh
                                 (fn [m]
                                   (let [[m ids] (mt/submit m
                                                            (em/group-pool (:rid dh))
                                                            [(work :group
                                                                   [{:generation gen}])]
                                                            (:rid dh)
                                                            {:retry :restart
                                                             :after [ii]}
                                                            (proc/now-ms))]
                                     [m (first ids)])))]
          (domain/swap-atom! index
                             (fn [v]
                               (assoc v :complete? false :job
                                      {:generation gen :index ii :group gi}))))

        (not (:complete? ix))
        (let [{ii :index gi :group} (:job ix)
              ds (descs dh [ii gi])
              ;; (a descriptor gone — forgotten with a departed coordinator's — is read from what its job recorded)
              st (fn [id recorded?]
                   (if-let [d (get ds id)]
                     (:state d)
                     (if recorded? :done :lost)))
              states [(st ii (= gen (:generation ix)))
                      (st gi (= gen (:grouped-generation ix)))]]
          (cond
            (every? #{:done} states)
            (do
              (domain/swap-atom! index
                                 (fn [v]
                                   (assoc v :complete? true :coordinator (:rid dh)
                                          :ran-on
                                          {:index (:ran-on (get ds ii))
                                           :group (:ran-on (get ds gi))})))
              (dom/swap-meta! dh (fn [m] [(mt/forget m [ii gi]) nil])))

            ;; failed, cancelled or lost: the generation's jobs submitted afresh on the next pass
            (some #{:failed :cancelled :lost} states)
            (do
              (dom/swap-meta! dh (fn [m] [(mt/forget m [ii gi]) nil]))
              (domain/swap-atom! index (fn [v] (assoc v :job nil))))))))))

;; A member marks itself :joined once it has attached (cohost-media.member, the Node member, the page's runtime); the
;; coordinator never does: just after the supervisor starts a member again, the registry still holds its previous
;; process's entry under the same rid until that lease lapses, and a member marked :joined from it would be asked to
;; restart while it is still starting (verify's full-kill, 2026-10-22: jolt's second start killed by a restart ask).
(defn- restarts! [{:keys [dh control]}]
  (let [live (set (keys (:runtimes (dom/registry dh))))
        now (proc/now-ms)]
    (doseq [[rid m] (:members (domain/read-atom control))
            :when (and (= :running (:state m)) (:joined m) (not (contains? live rid))
                       (> (- now (:started m 0)) (* 2 em/ttl)))]
      (domain/swap-atom! control (fn [v] (if (= :running (get-in v [:members rid :state]))
                               (-> v
                                   (assoc-in [:members rid :state] :restart-asked)
                                   (update :restarts (fnil conj []) {:rid rid :asked-by (:rid dh) :at now}))
                               v))))))

(defn pass!
  "One coordinator pass (the caller is the named coordinator)."
  [ctx]
  (plan-new! ctx)
  (supervise! ctx)
  (index! ctx)
  (restarts! ctx))
