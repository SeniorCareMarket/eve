(ns eve.runner.opfs-browser-main
  "eve's OPFS disk tier in a browser (EVE_BROWSER_OPFS=1 bb test:browser builds this into a page): the page mounts
   the OPFS pool (eve.opfs.mount/mount!) before its threads start, then runs the suite in a worker, as the thread
   conformance page does; the result is POSTed to /result (a mount that fails is the result)."
  (:require [cljs.reader :as reader]
            [cljs.test :as t]
            [eve.atom :as atom]
            [eve.opfs :as opfs]
            [eve.opfs.boot :as boot]
            [eve.opfs.follow :as follow]
            [eve.opfs.mount :as mount]
            [eve.runner.opfs-tabs :as tabs]
            [eve.thread-host :as h]
            [eve.opfs-browser-test]
            [eve.opfs-relay-mode-test]
            [eve.opfs-relay-test]
            [eve.region-opfs-test]))

(def ^:private report-to (atom nil))
(def ^:private report-fn (atom nil))   ; the relay page's worker: no eve threads, it posts its summary
(def ^:private failures (atom []))

(defn- note! [m]
  (swap! failures conj (pr-str (select-keys m [:type :message :expected :actual :testing-contexts]))))

(defmethod t/report [::t/default :fail] [m]
  (t/inc-report-counter! :fail)
  (note! (assoc m :testing-contexts (t/testing-contexts-str)))
  (println "FAIL" (pr-str (select-keys m [:message :expected :actual]))))

(defmethod t/report [::t/default :error] [m]
  (t/inc-report-counter! :error)
  (note! (assoc m :actual (str (:actual m) (when-let [st (some-> ^js (:actual m) .-stack)] (str " " (subs st 0 (min 1500 (count st))))))
                :testing-contexts (t/testing-contexts-str)))
  (println "ERROR" (str (:actual m))))

(defmethod t/report [::t/default :end-run-tests] [m]
  (let [msg {:op :suite :summary (select-keys m [:test :pass :fail :error]) :failures @failures}]
    (when-let [r @report-to] (h/send! r msg))
    (when-let [f @report-fn] (f msg))))

(defn run-suite
  "A worker's entry: the OPFS suites, the summary sent to the page."
  [boot]
  (reset! report-to (:reply boot))
  (t/run-tests 'eve.region-opfs-test 'eve.opfs-browser-test 'eve.opfs-relay-test))

(defn- main-rows []
  (let [[ok? e] (h/attempt (fn [] (h/receive! 10)))]
    {:block-never (= :never (:block (h/driving)))
     :wait-refused (and (false? ok?) (= :cannot-block (:eve.thread/refused (:data (h/error-data e)))))
     :poll-answers (= :eve.thread/timeout (h/receive! 0))}))

(defn- post! [result]
  (js/fetch "/result" #js {:method "POST" :body (pr-str result)}))

(defn- start! [rows t0 mode by]
  (let [[w] (h/start-threads! 1 'eve.runner.opfs-browser-main/run-suite {:reply (h/self)} {})
        timer (atom nil)]
    (reset! timer
            (js/setInterval
             (fn []
               (let [m (h/receive! 0)]
                 (cond
                   (and (map? m) (= :suite (:op m)))
                   (do (js/clearInterval @timer)
                       (-> (tabs/takeover!)
                           (.then (fn [tk]
                                    (post! (assoc m :main (assoc rows :takeover (:ok tk)) :takeover (:steps tk)
                                                  :ms (- (js/Date.now) t0) :driving (h/driving) :opfs-mode mode :isolated-by by))))))
                   (and (map? m) (= w (:eve.thread/exit m)))
                   (do (js/clearInterval @timer)
                       (post! {:op :died :notice m :main rows})))))
             20))))

(defn- start-page!
  "The page boots as a program would (eve.opfs.boot/boot!): isolated (served so, or by eve-sw.js after one reload),
   the pool mounted, then the suite's worker. :isolated-by says which."
  []
  (let [t0 (js/Date.now)
        by (if (.getItem js/sessionStorage "eve.opfs/coi-reload") :sw :headers)]
    (-> (boot/boot! {:opfs {:root "eve-opfs-test"}})
        ;; eve's service worker controls the page before the suite's workers start (the relay's tests): registered
        ;; here when the headers isolated the page
        (.then (fn [r] (.then (tabs/controlled!) (fn [_] r))))
        (.then (fn [{:keys [pool]}]
                 (start! (assoc (main-rows) :mounted (some? pool)) t0 (.-mode ^js pool) by))
               (fn [e] (post! {:op :mount-failed :message (str e) :main {:mounted false}}))))))

;; ---- the relay pool (W10): the page served and kept unisolated (?eve-opfs-relay=1); one worker holds eve's memory

(defn- relay-worker!
  "The relay page's worker (the kernel's URL, ?eve-opfs-relay-worker=<root>): mount the relay pool, run the relay
   suite, post the summary; then, as the owner, serve the follower tab (its follower server answers on the relay).
   A follower tab's worker (&follow=1) swaps :count up 5 through its follower atom instead."
  [root follow?]
  (-> (mount/mount! {:root root :slots 128 :max-file-bytes (* 64 1024 1024)})
      (.then (fn [^js pool]
               (if follow?
                 (try
                   (follow/use-relay!)
                   (let [a (follow/follower-atom {:id :eve/main :persistent "/relay/shared"} nil)
                         end (last (repeatedly 5 #(swap! a update :count inc)))]
                     (.postMessage js/self (pr-str {:op :followed :role (.-role pool) :count (:count end)})))
                   (catch :default e (.postMessage js/self (pr-str {:op :followed :error (str e)}))))
                 (do (reset! report-fn (fn [m] (.postMessage js/self (pr-str (assoc m :role (.-role pool))))))
                     (t/run-tests 'eve.opfs-relay-mode-test)
                     ;; the domain the follower tab swaps through this tab: 10 here first, kept open
                     (let [_d (atom/persistent-atom-domain "/relay/shared")
                           a (atom/persistent-atom {:id :eve/main :persistent "/relay/shared"} {:count 0})]
                       (dotimes [_ 10] (swap! a update :count inc))
                       (.postMessage js/self (pr-str {:op :shared :count (:count @a) :owner (opfs/owner?)})))))))
      (.catch (fn [e] (.postMessage js/self (pr-str {:op :mount-failed :message (str e)}))))))

(defn- relay-start-worker!
  "On a relay page: the worker from the kernel's URL (so eve's service worker controls it); its messages to f."
  [root follow? f]
  (let [k (.-src (.querySelector js/document "script[src]"))   ; the build's one script (h/kernel reads its manifest:
                                                                  ; Firefox's synchronous read of it fails here)
        w (js/Worker. (str k (if (re-find #"\?" k) "&" "?") "eve-opfs-relay-worker=" (js/encodeURIComponent root)
                           (when follow? "&follow=1")))]
    (set! (.-onmessage w) (fn [e] (f (reader/read-string (.-data e)))))
    (set! (.-onerror w) (fn [e] (f {:op :worker-error :message (.-message e)})))
    w))

(defn- relay-page!
  "The relay page: boot unisolated, run the relay suite in its worker, then a follower tab (an iframe, also
   unisolated) whose worker swaps through this tab's; the result posted."
  []
  (let [t0 (js/Date.now)
        root (str "eve-opfs-relay-" t0)
        steps (atom [])]
    (-> (boot/boot! {:isolate false :start (fn [] nil)})
        (.then (fn [r]
                 (js/Promise.
                  (fn [resolve _]
                    (let [suite (atom nil)]
                      (relay-start-worker!
                       root false
                       (fn [m]
                         (case (:op m)
                           :suite (reset! suite m)
                           :shared (resolve (assoc @suite :relay (:relay r) :shared m))
                           (resolve (assoc m :relay (:relay r)))))))))))
        (.then (fn [m]
                 (swap! steps conj [:owner-shares (and (:owner (:shared m)) (= 10 (:count (:shared m)))) (:shared m)])
                 (js/Promise.
                  (fn [resolve _]
                    (let [f (.createElement js/document "iframe")]
                      (set! (.-src f) (str "index.html?eve-opfs-relay=1&follower=1&root=" (js/encodeURIComponent root)))
                      (.addEventListener js/window "message"
                                         (fn [e] (when (string? (.-data e))
                                                   (let [x (reader/read-string (.-data e))]
                                                     (when (= :followed (:op x)) (resolve [m x]))))))
                      (js/setTimeout #(resolve [m {:op :followed :timeout true}]) 60000)
                      (.appendChild (.-body js/document) f))))))
        (.then (fn [[m x]]
                 (swap! steps conj [:follower-swaps (and (= "follower" (:role x)) (= 15 (:count x))) x])
                 (post! (assoc (dissoc m :shared) :op :suite :relay-steps @steps :isolated-by :none
                               :main {:relay (and (:relay m) (every? second @steps)) :mounted (= "owner" (:role m))}
                               :ms (- (js/Date.now) t0)))))
        (.catch (fn [e] (post! {:op :mount-failed :message (str e) :main {:mounted false}}))))))

(defn- relay-follower-page!
  "The relay follower tab: its worker follows the first tab's; its report to the parent page."
  [root]
  (-> (boot/boot! {:isolate false :start (fn [] nil)})
      (.then (fn [_]
               (relay-start-worker! root true
                                    (fn [m] (.postMessage (.-parent js/window) (pr-str m) (.-origin js/location))))))))

(declare init-isolated!)

(defn init []
  (if (and (exists? js/WorkerGlobalScope) (not (.-__eve_thread js/self)))
    (let [q (js/URLSearchParams. (.-search js/location))]
      (when-let [root (.get q "eve-opfs-relay-worker")]
        (relay-worker! root (= "1" (.get q "follow")))))
    (let [q (js/URLSearchParams. (.-search js/location))]
      (if (= "1" (.get q "eve-opfs-relay"))
        (if (= "1" (.get q "follower"))
          (relay-follower-page! (.get q "root"))
          (relay-page!))
        (init-isolated!)))))

(defn- init-isolated! []
  (when-not (h/run-thread!)
    (let [q (js/URLSearchParams. (.-search js/location))]
      (if-let [tab (.get q "tab")]
        (tabs/tab! tab (.get q "root"))
        (start-page!)))))
