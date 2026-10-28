(ns eve-media.page
  "eve-media's frontend: eve on ClojureScript in the browser — a runtime of the application's data plane, and its view.

   The runtime (a worker this page starts: the main thread cannot block): it joins the job atom's registry over the
   network rung (eve.thread.net, through the gateway the media server proxies on this origin) as runtime \"browser\",
   with the cap :browser, and serves the :browser pool on threads of its own (eve.thread's browser adapter: Web
   Workers, every one running this program). The coordinator — the member the launcher names, the JVM's — plans a
   :waveform job per file into that pool, after its loudness; the browser's threads claim it, draw the waveform from the peaks the loudness job recorded, and write it into the library. The runtime also
   submits what the page asks for (a re-queue) with eve.thread.domain's moves, so a swap that keeps losing takes a turn.

   The view: reagami — the UI the cljs-thread reagami-counter example renders with — on the main thread, over what a
   reader worker sends it: the library's file entries that changed, the index, and the control atom's notes (each
   process's run, the supervisor), read as network atoms once a second. It says what the application is doing: what
   each runtime is, what it is running now and has run, and a feed of every step it sees.
   The main thread does no network I/O of its own (a synchronous request would block it), so it is free to fetch its
   workers' batched requests at once (eve's browser http adapter).

   ?check=<file-id>:<kind> — the done bar's row: once the index is complete, re-queue that job, watch it to :done, and
   write what the page saw into eve-media/control (:page-check), where the launcher reads it."
  (:require [clojure.string :as str]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as h]
            [eve.thread.domain :as dom]
            [eve.thread.meta :as mt]
            [eve.thread.net :as tn]
            [eve.thread.rung :as rung]
            [eve-media.core :as em]
            [eve-media.tasks :as tasks]
            [reagami.core :as reagami]))

(defn- now [] (js/Date.now))

;; ===========================================================================
;; The runtime (workers)
;; ===========================================================================

(defn- requeue-in!
  "Job kind of the file at path submitted again, from this runtime: one swap of the metadata (eve.thread.meta/submit;
   a starved swap takes a turn), then the file's entry pointed at the new descriptor. Its id."
  [dh library path kind]
  (let [e (get-in (rung/value library) [:files path])
        {:keys [out window]} (:cfg (tasks/ctx))
        args {:path path :id (:id e) :kind (:kind e) :out out :window window}
        id (dom/swap-meta! dh (fn [m]
                                (let [offered (reduce into #{} (map (fn [[_ r]] (set (:caps r))) (:runtimes m)))
                                      [m ids] (mt/submit m (em/job-pool kind) [{:task (em/task-sym kind) :args [args]}]
                                                         (:rid dh) {:retry :restart :needs (em/needs kind offered)
                                                                    :scope (:id e)}
                                                         (now))]
                                  [m (first ids)])))]
    (when-not id (throw (ex-info "the pool took no submission" {:kind kind})))
    (rung/-swap! library (fn [v] (-> v
                                     (assoc-in [:files path :jobs kind] id)
                                     (assoc-in [:files path :status] :planned)
                                     (update-in [:files path :requeued] (fnil conj []) {:kind kind :id id :at (now)
                                                                                        :by (:rid dh)}))))
    id))

(defn runtime-main
  "The runtime's entry (a worker): join, serve the :browser pool on threads of its own, answer the page."
  [{:keys [reply]}]
  (let [{:keys [cfg p library]} (tasks/ctx)
        nc (tasks/net-cfg cfg)
        jobs (tn/rung nc em/jobs-name p)
        control (tn/rung nc em/control-name p)
        ;; (60 s: a network runtime's first beat comes after its ticker worker has loaded the program and read the
        ;; metadata cold — ~1,100 blocks over the browser's six connections, beside its other workers' reads)
        dh (dom/attach! jobs {:rid "browser" :ttl 60000 :caps [:browser] :requires '[eve-media.tasks]})]
    (js/console.log "eve-media runtime: joined the registry as" (:rid dh))
    (dom/pool dh :browser {:threads 2 :retry :restart})
    (js/console.log "eve-media runtime: serving :browser on 2 threads")
    (rung/-swap! control (fn [v] (assoc-in v [:members "browser" :joined] true)))
    (h/send! reply {:op :joined})
    ;; every read here is a network request: one that fails is reported and the loop goes on (a throw would end it,
    ;; the page's re-queues with it, while the pool's own workers kept drawing waveforms)
    (loop [t0 0]
      (let [[ok? stop] (h/attempt (fn [] (:stop (rung/value control))))]
        (when-not ok? (js/console.log "eve-media runtime: reading control failed:" (str stop)))
        (if (and ok? stop)
          (do (dom/detach! dh) (h/send! reply {:op :stopped}))
          (let [m (h/receive! 500)
                t0 (if (> (- (now) t0) 5000)
                     (let [[ok? e] (h/attempt
                                    (fn []
                                      (let [r (dom/registry dh)]
                                        (h/send! reply {:op :registry :runtimes (vec (sort (keys (:runtimes r))))
                                                        :caps (into {} (map (fn [[rid x]] [rid (vec (sort (map name (:caps x))))])
                                                                            (:runtimes r)))
                                                        :coordinator (get-in (rung/value control) [:config :coordinator])}))))]
                       (when-not ok? (js/console.log "eve-media runtime: reading the registry failed:" (str e)))
                       (now))
                     t0)]
            (when (and (map? m) (= :requeue (:op m)))
              (js/console.log "eve-media runtime: re-queue asked:" (name (:kind m)) (:path m))
              (let [[ok? v] (h/attempt (fn [] (requeue-in! dh library (:path m) (:kind m))))]
                (js/console.log "eve-media runtime: re-queue" (if ok? (str "submitted as " v) (str "failed: " v)))
                (h/send! reply (if ok? {:op :requeued :id v :path (:path m) :kind (:kind m)}
                                   {:op :error :error (str (or (ex-message v) v))}))))
            (recur t0)))))))

;; ?probe=1: the network rung's cost from a worker, printed to the console (a diagnostic)
(defn probe-main [_]
  (let [t (fn [label f] (let [t0 (now) r (f)] (js/console.log "eve-media probe:" label (- (now) t0) "ms") r))
        [ok? v] (h/attempt
                 (fn []
                   (let [{:keys [cfg p]} (t "open the library's rung" tasks/ctx)
                         jobs (t "open the jobs rung" (fn [] (tn/rung (tasks/net-cfg cfg) em/jobs-name p)))
                         meta (t "open the metadata's rung" (fn [] (rung/-meta-rung jobs {})))]
                     (dotimes [i 3] (t (str "read the metadata #" i) (fn [] (count (rung/value meta)))))
                     (dotimes [i 2] (t (str "swap a key of the metadata #" i)
                                       (fn [] (rung/-swap! meta (fn [v] (assoc v :probe (now))))))))))]
    (when-not ok? (js/console.log "eve-media probe: failed" (str (or (ex-message v) v))))))

;; ===========================================================================
;; The view's reader (a worker)
;; ===========================================================================

(defn- send-to-page!
  "msg into the page's mailbox, waiting while it is full (the page drains it every 100 ms)."
  [reply msg]
  (loop [n 0]
    (let [[ok? e] (h/attempt (fn [] (h/send! reply msg)))]
      (when-not ok?
        (if (and (< n 600) (re-find #"mailbox is full" (str (ex-message e))))
          (do (proc/sleep-ms! 50) (recur (inc n)))
          (throw e))))))

(defn- control-view
  "What the page shows of the control atom: each process's host and run (never its command or environment), the
   supervisor, the coordinator named, the restarts the coordinator asked for."
  [c]
  {:hosts (into {} (map (fn [[rid ch]] [rid (:host ch)]) (:children c)))
   :members (into {} (map (fn [[rid m]]
                            [rid (-> (select-keys m [:host :member? :state :pid :incarnation :joined :code])
                                     (assoc :exits (count (:exits m))))])
                          (:members c)))
   :supervisor (select-keys (:supervisor c) [:host :pid])
   :coordinator (get-in c [:config :coordinator])
   :restarts (vec (take-last 20 (:restarts c)))})

(defn view-main
  "The view's reader (a worker): the library, the index and the control atom read once a second, and what changed
   sent to the page — one message per changed file entry (a library does not fit one mailbox), the index whole, the
   control atom's notes (control-view); the page's check written into control when the page sends it."
  [{:keys [reply]}]
  (let [{:keys [cfg p library]} (tasks/ctx)
        nc (tasks/net-cfg cfg)
        index (tn/rung nc em/index-name p)
        control (tn/rung nc em/control-name p)]
    (loop [sent {} ix ::none cv ::none]
      (let [[ok? v] (h/attempt (fn [] [(:files (rung/value library)) (rung/value index)
                                       (control-view (rung/value control))]))
            [sent ix cv] (if ok?
                           (let [[files ix' cv'] v]
                             (when (not= cv cv') (send-to-page! reply {:op :control :control cv'}))
                             (doseq [[path e] files :when (not= e (get sent path))]
                               (send-to-page! reply {:op :file :path path :entry e}))
                             (doseq [path (keys sent) :when (not (contains? files path))]
                               (send-to-page! reply {:op :file :path path :entry nil}))
                             (when (not= ix ix') (send-to-page! reply {:op :index :index ix'}))
                             (send-to-page! reply {:op :swept})
                             [(or files {}) ix' cv'])
                           (do (send-to-page! reply {:op :error :error (str (or (ex-message v) v))}) [sent ix cv]))
            m (h/receive! 1000)]
        (when (and (map? m) (= :page-check (:op m)))
          (rung/-swap! control (fn [c] (assoc c :page-check (:check m)))))
        (recur sent ix cv)))))

;; ===========================================================================
;; The view (the page's main thread)
;; ===========================================================================

(defonce state (atom {:polls 0 :requeues {} :progress [] :lib {:files {}} :feed () :since {}}))

(defn- rid-of [owner] (when owner (first (str/split (str owner) #"#"))))

(defn- thread-of [owner] (when owner (second (str/split (str owner) #"#"))))

(def ^:private colours
  {:done "#2e7d32" :running "#1565c0" :queued "#8d6e63" :blocked "#8d6e63" :failed "#c62828" :cancelled "#757575"
   :forgotten "#757575"})

;; ---------------------------------------------------------------------------
;; What the page says of each runtime
;; ---------------------------------------------------------------------------

(def ^:private hosts-info
  "Each host's card: the runtime it is, the language, how it reaches the atoms, and its part in the run."
  {:jvm {:runtime "JVM" :lang "Clojure" :reach "maps the atoms' files"
         :role (str "The coordinator: plans every file's jobs and follows them to the end. Runs the metadata (Apache "
                    "Tika), perceptual hashes, loudness (EBU R128, read in place from shared memory) and the duplicate "
                    "grouping — and the transcode, with the ffmpeg CLI, when jolt is not in the run.")}
   :jolt {:runtime "Chez Scheme" :lang "jolt" :reach "maps the atoms' files"
          :role (str "Transcodes each video and audio file to a web MP4 and decodes its sound to PCM, calling FFmpeg's "
                     "C libraries directly through jolt's FFI.")}
   :node {:runtime "Node.js" :lang "ClojureScript" :reach "maps the atoms' files"
          :role "Thumbnails and contact sheets with sharp (libvips); audio tags with music-metadata — npm's libraries."}
   :glj {:runtime "Go" :lang "glojure" :reach "maps the atoms' files"
         :role (str "The media server (Go's net/http: this page, the videos, the gateway behind it) and the folder "
                    "watcher that adds each new file to the library; publishes each transcode.")}
   :bb {:runtime "babashka" :lang "Clojure (SCI)" :reach "over HTTP, through the gateway"
        :role "Builds the index once every file has settled."}
   :browser {:runtime "this browser" :lang "ClojureScript in Web Workers" :reach "over HTTP, through the gateway"
             :role (str "Draws each file's waveform from its loudness peaks — this page's own workers, a runtime like "
                        "the others. The ↻ buttons below submit jobs from here.")}
   :gateway {:runtime "JVM" :lang "Clojure" :reach "maps the atoms' files"
             :role (str "Runs no jobs: serves the atoms over HTTP (eve.net's routes) to the runtimes that cannot map "
                        "their files — babashka and this browser.")}})

(def ^:private host-colours
  {:jvm "#e65100" :jolt "#8e24aa" :node "#00897b" :glj "#0288d1" :bb "#9e7700" :browser "#d81b60"
   :gateway "#6d4c41" :clje "#a90533"})

(def ^:private host-order [:jvm :jolt :node :glj :bb :browser :gateway])

(defn- host-of
  "The host of the process rid (control's children; a rid is its host's name otherwise)."
  [rid]
  (or (get-in @state [:control :hosts rid]) (keyword rid)))

(defn- colour-of [rid] (get host-colours (host-of rid) "#9e9e9e"))

(defn- badge
  "rid's name in its runtime's colour (rid nil: the application itself)."
  [rid]
  (if rid
    [:span.rt {:style {:background (colour-of rid)}} rid]
    [:span.rt {:style {:background "#757575"}} "eve-media"]))

(defn- supervisor-line [{:keys [host]}]
  (case host
    :clje ["clojerl" "BEAM"
           (str "The supervisor: an Erlang/OTP supervision tree — a supervisor per process, each process started "
                "again when it dies (at most 5 times a minute), stopped together at the end. It runs no jobs.")]
    :jvm ["JVM" "JVM"
          (str "The supervisor, standing in because clojerl could not run here: the same children and the same "
               "restart policy as clojerl's OTP tree. It runs no jobs.")]
    nil))

;; ---------------------------------------------------------------------------
;; The feed: every step the page sees, newest first
;; ---------------------------------------------------------------------------

(defn- clock [] (.toLocaleTimeString (js/Date.) js/undefined #js {:hour12 false}))

(defn- feed! [events]
  (when (seq events)
    (swap! state update :feed (fn [f] (take 60 (concat (reverse (map #(assoc % :t (clock)) events)) f))))))

(def ^:private verbs {:running "started" :done "finished" :failed "failed" :cancelled "cancelled" :lost "lost"})

(defn- file-events
  "A file entry's steps between what the page had (old) and what it reads now (new)."
  [path old new]
  (let [id (:id new)
        t (now)]
    (concat
     (when (and new (nil? old))
       [{:who (:found-by new) :what (str "added " id " (" (em/base-name path) ") to the library")}])
     (when (and (= :planned (:status new)) (= :new (:status old)) (:planned-by new))
       [{:who (:planned-by new)
         :what (str "planned " id ": " (count (:jobs new)) " jobs into the pools — "
                    (str/join ", " (map name (sort (keys (:jobs new))))))}])
     (for [[k st] (sort-by (comp name key) (:job-state new))
           :let [owner (get-in new [:ran-on k])
                 since (get-in @state [:since [path k]])]
           :when (and (not= st (get-in old [:job-state k])) (verbs st))]
       {:who (rid-of owner)
        :what (str (verbs st) " " (name k) " of " id
                   (when-let [th (thread-of owner)] (str " on thread " th))
                   (when (and since (= :done st)) (str " in " (.toFixed (/ (- t since) 1000) 1) " s"))
                   (when (and (= :failed st) (seq (:escalated new))) " — escalated after 3 failures"))})
     (when (and (#{:done :error} (:status new)) (not= (:status new) (:status old)))
       [{:who nil :what (if (= :done (:status new))
                          (str id " is done: every job settled")
                          (str id " settled with errors: " (str/join ", " (map name (:escalated new)))))}]))))

(defn- control-events [old new]
  (let [sup (:supervisor new)
        [sname] (supervisor-line sup)]
    (concat
     (when (and (:pid sup) (not= sup (:supervisor old)))
       [{:who "supervisor" :what (str sname " is supervising, pid " (:pid sup))}])
     (for [[rid m] (sort-by key (:members new))
           :let [o (get-in old [:members rid])]
           :when (not= (select-keys m [:state :incarnation :joined]) (select-keys o [:state :incarnation :joined]))]
       {:who rid
        :what (cond
                (and (= :running (:state m)) (not= (:incarnation m) (:incarnation o)))
                (if (> (:incarnation m 1) 1)
                  (str "started again by the supervisor (start " (:incarnation m) ", pid " (:pid m) ")")
                  (str "started by the supervisor (pid " (:pid m) ")"))
                (and (:joined m) (not (:joined o))) "joined the jobs atom's registry"
                (= :exited (:state m)) (str "exited (code " (:code m) ")")
                (= :restart-asked (:state m)) "its lease lapsed: the coordinator asked the supervisor to restart it"
                (= :restarting (:state m)) "being restarted by the supervisor"
                (= :gave-up (:state m)) "exited too often: the supervisor gave up on it"
                (= :stopped (:state m)) "stopped"
                :else (name (or (:state m) :pending)))}))))

(defn- index-events [old new]
  (when (and (:complete? new) (not (:complete? old)))
    (let [{:keys [index group]} (:ran-on new)]
      [{:who (rid-of index) :what (str "built the index of " (or (:files new) "the") " files")}
       {:who (rid-of group) :what (str "grouped the near-duplicates (a parallel fold over the pHash pairs): "
                                       (if (seq (:duplicates new)) (pr-str (:duplicates new)) "none"))}])))

;; ---------------------------------------------------------------------------
;; The parts of the page
;; ---------------------------------------------------------------------------

(defn- chip [kind st attempts owner]
  [:span.chip {:style (cond-> {:background (get colours st "#9e9e9e")}
                        owner (assoc :border-left (str "5px solid " (colour-of (rid-of owner)))))
               :title (str (name kind) ": " (name (or st :pending)) (when owner (str " on " owner)))}
   (str (name kind) " " (name (or st :pending)) (when (and attempts (> attempts 1)) (str " ×" attempts)))
   (when owner [:small.on (str " " (rid-of owner))])])

(defn- requeue! [path kind]
  (when-let [w (:runtime @state)]
    (h/send! w {:op :requeue :path path :kind kind})))

(defn- waveform-svg [{:keys [points]}]
  (when points
    [:svg {:viewBox "0 0 100 20" :width 120 :height 24 :preserveAspectRatio "none"}
     [:polygon {:points points :fill "currentColor" :opacity "0.7"}]]))

(defn- file-row [[p e]]
  (let [r (:results e)
        jobs (sort-by (comp name key) (:job-state e))]
    [:tr {:data-id (:id e)}
     [:td (when (:thumb r) [:img {:src (str "/media/thumbs/" (:id e) ".jpg?v=" (:polls @state)) :width 96}])]
     [:td [:b (:id e)] [:br] [:small (em/base-name p)]]
     [:td (name (:kind e))]
     [:td.status (name (:status e))]
     (into [:td] (map (fn [[k st]] (chip k st (get-in e [:attempts k]) (get-in e [:ran-on k]))) jobs))
     (into [:td] (map badge (sort (set (keep rid-of (vals (:ran-on e)))))))
     [:td (cond (get-in r [:loudness :lufs]) (str (.toFixed (get-in r [:loudness :lufs]) 1) " LUFS")
                (get-in r [:loudness :no-audio]) [:small "no audio"]
                :else "")
      (waveform-svg (:waveform r))]
     [:td (when-let [u (get-in r [:publish :url])] [:a {:href u} "mp4"])]
     (into [:td] (map (fn [[k _]] [:button {:on-click #(requeue! p k)} (str "↻ " (name k))])
                      (filter (fn [[k _]] (#{:thumb :sheet :phash :meta :loudness :waveform} k)) jobs)))]))

(def ^:private parts
  "Each host's part, in a few words (the card's second line; the whole of it is the card's tooltip)."
  {:jvm "coordinator · metadata · pHash · loudness · grouping"
   :jolt "transcode + PCM: FFmpeg's C libraries via FFI"
   :node "thumbnails · contact sheets · tags (npm)"
   :glj "media server · folder watcher · publish"
   :bb "builds the index (over HTTP)"
   :browser "draws the waveforms, in this page's workers"
   :gateway "serves the atoms over HTTP to bb and this page"})

(defn- explainer [{:keys [control]} hosts]
  (let [[sname] (supervisor-line (:supervisor control))]
    [:div.explain
     [:p.lead "One application, several runtimes, one set of shared " [:b "eve atoms"] " (persistent maps in "
      "memory-mapped files): the coordinator plans each file's jobs, every runtime claims the jobs it can run, "
      (if sname [:span [:b sname] " keeps every process alive"] "a supervisor keeps every process alive") "."]
     [:details
      [:summary "how it works"]
      [:ol
       [:li [:b "Files arrive: "] "the Go runtime watches the folder and adds each new file to " [:code "eve-media/library"] "."]
       [:li [:b "The coordinator plans: "] (badge (or (:coordinator control) "jvm")) " turns each file into a graph of "
        "jobs in the pools of " [:code "eve-media/jobs"] "; a job names the capability it needs, never a runtime. "
        "It waits (up to 120 s from each start) for every runtime it started to join first."]
       [:li [:b "The runtimes claim: "] "each runtime's threads claim the jobs their capabilities match, run them, and "
        "write the results into the library; a job waits for its inputs. "
        (if (contains? hosts :jolt) "Transcodes go to jolt." "No jolt in this run: the JVM transcodes with the ffmpeg CLI.")]
       [:li [:b "Nothing stays down: "] "a process that dies is started again; a job it held is claimed again once its "
        "lease lapses."]
       [:li [:b "The index: "] "once every file settles, babashka builds the index and the coordinator groups "
        "near-duplicates by perceptual hash."]]
      [:p [:small "JVM, jolt, Node and Go map the atoms' files and swap them in place; babashka and this browser reach "
           "them over HTTP through the gateway. A job's colour is its state; its left edge and label, the runtime "
           "that ran it. Hover a card for the whole of its part."]]]]))

(defn- state-label [m]
  (case (:state m)
    :running (str "running" (when (> (:incarnation m 1) 1) (str " · start " (:incarnation m))))
    nil "starting"
    :gave-up "gave up"
    (str (name (:state m)) (when (:code m) (str " (" (:code m) ")")))))

(defn- member-card [rid host m caps running done coordinator?]
  (let [{:keys [runtime lang reach role]} (get hosts-info host)
        n-done (reduce + 0 (vals done))]
    [:div.card {:style {:border-left-color (get host-colours host "#9e9e9e")}
                :title (str runtime " · " lang " · " reach (when (:pid m) (str " · pid " (:pid m))) "\n\n" role
                            (when (seq caps) (str "\n\noffers " (str/join " " (map #(str ":" %) caps))))
                            (when (seq done)
                              (str "\n\nhas run: " (str/join ", " (map (fn [[k n]] (str (name k) " ×" n))
                                                                        (sort-by (comp name key) done))))))}
     [:div.head (badge rid) [:small runtime]
      [:small.st {:class (when (#{:gave-up :exited} (:state m)) "err")} (state-label m)]]
     [:div.role (when coordinator? [:b "coordinator · "]) (get parts host)]
     (when (:member? m)
       [:div.now (if (seq running) (into [:span] (interpose ", " running)) [:small "idle"])
        (when (pos? n-done) [:small (str " · ran " n-done)])])]))

(defn- runtime-cards [{:keys [control caps lib ix]}]
  (let [members (:members control)
        rids (sort-by (fn [rid] [(or (first (keep-indexed (fn [i h] (when (= h (host-of rid)) i)) host-order)) 99) rid])
                      (distinct (concat (keys (:hosts control)) (keys members))))
        jobs (for [[_ e] (:files lib) [k st] (:job-state e)]
               {:owner (get-in e [:ran-on k]) :kind k :st st :id (:id e)})
        extra (keep (fn [[k owner]] (when owner {:owner owner :kind k :st :done}))
                    (when (:complete? ix) (:ran-on ix)))
        by-rid (group-by (comp rid-of :owner) (concat jobs extra))
        sup (:supervisor control)
        [sname sruntime srole] (supervisor-line sup)]
    (into [:div.cards]
          (concat
           (map (fn [rid]
                  (let [js (get by-rid rid)]
                    (member-card rid (host-of rid) (get members rid) (get caps rid)
                                 (for [j js :when (= :running (:st j))]
                                   [:b.job (str (name (:kind j)) " " (:id j))])
                                 (frequencies (map :kind (filter #(= :done (:st %)) js)))
                                 (= rid (:coordinator control)))))
                rids)
           (when sname
             [[:div.card {:style {:border-left-color (get host-colours (:host sup) "#9e9e9e")}
                          :title (str sruntime " · " sname " · pid " (:pid sup) "\n\n" srole
                                      (when-let [rs (seq (:restarts control))]
                                        (str "\n\nrestarts the coordinator asked for: " (str/join ", " (map :rid rs)))))}
               [:div.head [:span.rt {:style {:background (get host-colours (:host sup) "#9e9e9e")}} "supervisor"]
                [:small sname] [:small.st "running"]]
               [:div.role (if (= :clje (:host sup)) "keeps every process alive (OTP)"
                              "keeps every process alive (clojerl could not run)")]]])))))

(defn- waiting-for
  "The runtimes the coordinator waits for before planning (started, not joined, within its 120 s), while files wait."
  [{:keys [control lib]}]
  (when (some (fn [[_ e]] (= :new (:status e))) (:files lib))
    (seq (sort (keep (fn [[rid m]] (when (and (:member? m) (= :running (:state m)) (not (:joined m))) rid))
                     (:members control))))))

(defn- feed-view [feed]
  (if (seq feed)
    (into [:ol.feed] (map (fn [{:keys [t who what]}] [:li [:small.t t] " " (badge who) " " what]) feed))
    [:p [:small "Each step appears here as it happens."]]))

(defn- app []
  (let [{:keys [lib ix runtimes control coordinator err requeues polls joined feed]} @state
        files (sort-by (comp :id val) (:files lib))
        hosts (set (vals (:hosts control)))
        jobs (for [[_ e] files [_ st] (:job-state e)] st)
        waiting (waiting-for @state)]
    [:div
     [:header
      [:h1 "eve-media"]
      [:p.sub (str (count files) " files, " (count (filter #(= :done (:status (val %))) files)) " done · "
                   (count jobs) " jobs, " (count (filter #{:done} jobs)) " done, "
                   (count (filter #{:running} jobs)) " running · index " (if (:complete? ix) "complete" "in progress")
                   (when (seq (:duplicates ix)) (str " · duplicates " (pr-str (:duplicates ix)))))
       (when waiting [:span.wait (str " · planning waits for " (str/join ", " waiting) " to join")])
       (when err [:span.err " · " err])]]
     (explainer @state hosts)
     (runtime-cards @state)
     (feed-view feed)
     (when (seq requeues)
       [:section#requeues
        (into [:ul] (map (fn [[id {:keys [file kind seen ran-on]}]]
                           [:li (str "re-queued " file " " (name kind) " as " id ": " (str/join " → " (map name seen))
                                     (when ran-on (str " on " ran-on)))])
                         requeues))])
     [:table
      [:thead [:tr [:th] [:th "file"] [:th "kind"] [:th "status"] [:th "jobs"] [:th "ran on"] [:th "loudness"]
               [:th "web"] [:th "re-queue"]]]
      (into [:tbody] (map file-row files))]
     [:p [:small (str "coordinator " (or coordinator (:coordinator control) "—") " · registry " (str/join ", " runtimes)
                      " · this browser " (cond joined "joined" (:runtime @state) "joining" :else "a view only")
                      " · sweep " polls)]]]))

(defn- render! [] (reagami/render (.querySelector js/document "#app") [app]))

(defn- watch-requeues!
  "Each re-queued job's state as the library mirrors it (the coordinator's supervise)."
  [lib]
  (swap! state update :requeues
         (fn [rq]
           (into {} (map (fn [[id r]]
                           (let [e (get-in lib [:files (:path r)])
                                 st (when (= id (get-in e [:jobs (:kind r)])) (get-in e [:job-state (:kind r)]))]
                             [id (cond-> r
                                   (and st (not= st (peek (:seen r)))) (update :seen conj st)
                                   (get-in e [:ran-on (:kind r)]) (assoc :ran-on (get-in e [:ran-on (:kind r)])))]))
                         rq)))))

(defn- check! []
  (when-let [spec (:check @state)]
    (let [{:keys [lib ix requeues asked]} @state
          [fid kind] (str/split spec #":")
          kind (keyword kind)
          [p e] (first (filter (fn [[_ e]] (= fid (:id e))) (:files lib)))]
      (cond
        (and (:complete? ix) p (not asked) (:joined @state))
        (do (js/console.log "eve-media check: the index is complete; re-queue" (name kind) "of" fid)
            (swap! state assoc :asked true) (requeue! p kind))

        (and (seq requeues) (not (:check-sent @state)))
        (let [[id r] (first requeues)]
          (when (and (= :done (peek (:seen r))) (= :done (:status e)))
            (h/send! (:viewer @state)
                     {:op :page-check
                      :check {:ok true :rows (.-length (js/document.querySelectorAll "tbody tr"))
                              :chips (.-length (js/document.querySelectorAll ".chip")) :descriptor id :file fid
                              :kind kind :seen (:seen r) :ran-on (:ran-on r) :polls (:polls @state)
                              :index-complete (boolean (:complete? ix)) :coordinator (:coordinator @state)
                              :progress (:progress @state) :errors (:errors @state)
                              :waveforms (count (filter (fn [[_ e]] (get-in e [:results :waveform :points]))
                                                        (:files lib)))
                              :at (now)}})
            (js/console.log "eve-media check: sent" id (pr-str (:seen r)))
            (swap! state assoc :check-sent true)))))))

(defn- swept! []
  (let [lib (:lib @state)
        done (count (filter (fn [[_ e]] (= :done (:status e))) (:files lib)))]
    ;; the first sweep is what the page found: the feed says so, and follows the run from there
    (when (zero? (:polls @state))
      (let [states (for [[_ e] (:files lib) [_ st] (:job-state e)] st)]
        (feed! [{:who nil
                 :what (if (seq states)
                         (str "this page opened on " (count (:files lib)) " files, "
                              (count (filter #{:done} states)) " of " (count states) " jobs done — the cards say which "
                              "runtime ran them; from here every step appears as it happens")
                         "this page opened before any file was planned: every step appears here as it happens")}])))
    (swap! state (fn [s] (cond-> (assoc s :err nil :polls (inc (:polls s)))
                           (not= done (peek (:progress s))) (update :progress conj done))))
    (watch-requeues! lib)
    (check!)))

(defn- on-file!
  "A file entry read anew: its steps into the feed (from the second sweep: the first is what the page found), each
   running job's start noted (a finish says how long it took), the entry kept."
  [path entry]
  (let [old (get-in @state [:lib :files path])]
    (when (pos? (:polls @state)) (feed! (file-events path old entry)))
    (swap! state (fn [s]
                   (-> s
                       (update :since (fn [since]
                                        (reduce (fn [since [k st]]
                                                  (if (= :running st)
                                                    (update since [path k] #(or % (now)))
                                                    (dissoc since [path k])))
                                                since (:job-state entry))))
                       (update-in [:lib :files] (fn [fs] (if entry (assoc fs path entry) (dissoc fs path)))))))))

(defn- on-message [m]
  (case (:op m)
    :file (on-file! (:path m) (:entry m))
    :index (do (when (pos? (:polls @state)) (feed! (index-events (:ix @state) (:index m))))
               (swap! state assoc :ix (:index m)))
    :control (do (when (:control @state) (feed! (control-events (:control @state) (:control m))))
                 (swap! state assoc :control (:control m)))
    :swept (swept!)
    :joined (do (js/console.log "eve-media page: this page's runtime joined")
                (feed! [{:who "browser" :what "joined the jobs atom's registry from this page's workers"}])
                (swap! state assoc :joined true))
    :registry (swap! state assoc :runtimes (:runtimes m) :caps (:caps m) :coordinator (:coordinator m))
    :requeued (do (feed! [{:who "browser" :what (str "re-queued " (name (:kind m)) " of "
                                                     (get-in @state [:lib :files (:path m) :id]) " from this page")}])
                  (swap! state assoc-in [:requeues (:id m)] {:path (:path m) :kind (:kind m) :seen [:queued] :at (now)
                                                             :file (get-in @state [:lib :files (:path m) :id])}))
    :error (do (js/console.log "eve-media page: error:" (:error m))
               (swap! state (fn [s] (-> s (assoc :err (:error m)) (update :errors (fnil inc 0))))))
    nil))

(defn- main []
  (let [check (second (re-find #"[?&]check=([^&]+)" js/location.search))
        [viewer] (h/start-threads! 1 'eve-media.page/view-main {:reply (h/self)} {})
        ;; ?runtime=0: a view only (the launcher's screenshot), no runtime of its own
        [w] (when-not (re-find #"[?&]runtime=0" js/location.search)
              (h/start-threads! 1 'eve-media.page/runtime-main {:reply (h/self)} {}))
        _ (when (re-find #"[?&]probe=1" js/location.search)
            (h/start-threads! 1 'eve-media.page/probe-main {} {}))]
    (swap! state assoc :check check :runtime w :viewer viewer)
    (add-watch state ::render (fn [_ _ old new] (when-not (identical? old new) (render!))))
    (js/setInterval (fn [] (loop [] (let [m (h/receive! 0)] (when (map? m) (on-message m) (recur))))) 100)
    (render!)))

(defn init
  "The program's entry, on the page and in every worker this page starts (they run the whole program)."
  []
  (when-not (h/run-thread!)
    (main)))
