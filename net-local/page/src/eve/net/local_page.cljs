(ns eve.net.local-page
  "eve's local network, the page (bb net:local serves it from thread server 0): eve on ClojureScript in the browser.

   Its runtime runs in a worker this page starts (the main thread cannot block on a request): it opens the network atom
   app/counter at the broker (blocks direct to the store, by presigned URL), joins the domain cell eve.thread/cell as a
   runtime of its own and submits eve.thread.probe/who to the :work pool by name — whichever thread server claims the job
   runs it, and its receipt names that server. The main thread draws the map of the nodes from each one's /health,
   polled every 2 s, and the counter and the jobs.

   ?check=1: once its runtime has joined, the page swaps the counter and runs one job by itself, and leaves its report
   in window.__evecheck (scripts/net-local-check.py reads it)."
  (:require [clojure.string :as str]
            [eve.net.platform :as platform]
            [eve.net.run :as r]
            [eve.net.wire :as wire]
            [eve.thread-host :as h]
            [eve.thread.domain :as dom]
            [eve.thread.net :as tn]
            [eve.thread.probe]
            [eve.thread.rung :as rung]))

;; ===========================================================================
;; The runtime (a worker)
;; ===========================================================================

(defn- fetch-config
  "the run's config.json from this origin (the launcher writes it): the domain id, the key, every node's address"
  []
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "GET" (str (.-origin js/location) "/config.json") false)
    (.send xhr nil)
    (js->clj (js/JSON.parse (.-responseText xhr)) :keywordize-keys true)))

(defn- epoch-of
  "the atom's epoch at the broker now (a deref, its lease released at once)"
  [p nc nm]
  (let [c (assoc nc :atom-id (tn/atom-id nm))
        d (r/run p (wire/deref-atom c))]
    (r/run p (wire/release-reader c (:lease-id d)))
    (:epoch d)))

(defn runtime-main
  "The worker: open the counter, join the domain cell, answer the page's :swap and :job."
  [{:keys [reply]}]
  (let [[ok? v]
        (h/attempt
         (fn []
           (let [cfg (fetch-config)
                 nc {:endpoint (:broker cfg) :domain-id (:domain_id cfg) :api-key (:api_key cfg)}
                 p (platform/platform)
                 t0 (js/Date.now)
                 step (fn [what] (js/console.log "eve local runtime:" what (- (js/Date.now) t0) "ms"))
                 counter (tn/rung nc "app/counter" p {:n 0})
                 _ (step "opened app/counter")
                 rid (str "browser-" (rand-int 100000))
                 cell (tn/rung nc "eve.thread/cell" p)
                 _ (step "opened eve.thread/cell")
                 mr (rung/-meta-rung cell {})
                 _ (step (str "read the cell's meta-atom (" (count (pr-str (rung/value mr))) " chars)"))
                 _ (rung/-swap! mr (fn [v] v))
                 _ (step "swapped the meta-atom unchanged")
                 dh (dom/attach! cell {:rid rid :ttl 60000 :requires '[eve.thread.probe]})
                 _ (step "joined the domain cell")
                 pool (dom/pool dh :work {:threads 0})
                 _ (step "registered for the :work pool")
                 ep (fn [] (epoch-of p nc "app/counter"))]
             (h/send! reply {:op :ready :rid rid :n (:n (rung/value counter)) :epoch (ep)})
             (loop [k 0]
               (let [m (h/receive! 500)]
                 (when (map? m)
                   (let [[ok? e] (h/attempt
                                  (fn []
                                    (case (:op m)
                                      :swap (let [before (ep)]
                                              (rung/-swap! counter (fn [v] (update v :n (fnil inc 0))))
                                              (h/send! reply {:op :counter :n (:n (rung/value counter)) :before before
                                                              :epoch (ep)}))
                                      :job (let [ids (dom/submit-all! pool [['eve.thread.probe/who [k]]])
                                                 [x] (dom/collect dh ids :in-order)]
                                             (h/send! reply {:op :job :x k :state (:state x) :ran (get-in x [:value :ran])
                                                             :y (get-in x [:value :y])}))
                                      nil)))]
                     (when-not ok? (h/send! reply {:op :error :error (str (or (ex-message e) e))}))))
                 (recur (inc k)))))))]
    (when-not ok? (h/send! reply {:op :error :error (str "the runtime did not start: " (or (ex-message v) v))}))))

;; ===========================================================================
;; The page (the main thread)
;; ===========================================================================

(defonce state (atom {:nodes {} :jobs [] :errors []}))

(defn- el [tag attrs & kids]
  (let [e (.createElement js/document tag)]
    (doseq [[k v] attrs] (if (fn? v) (.addEventListener e (name k) v) (.setAttribute e (name k) v)))
    (doseq [k kids] (.append e (if (string? k) (.createTextNode js/document k) k)))
    e))

(defn- render! []
  (let [{:keys [nodes rid n epoch jobs errors cfg]} @state
        app (.getElementById js/document "app")
        node (fn [label up?] (el "span" {:class (str "node " (if up? "up" "down"))} label))]
    (set! (.-innerHTML app) "")
    (.append app
             (el "h2" {} "Nodes")
             (el "div" {:id "nodes"}
                 (node (str "broker " (:broker cfg)) (get nodes :broker))
                 (node (str "store " (:store cfg)) (get nodes :store))
                 (apply el "span" {}
                        (map-indexed (fn [i u] (node (str "thread t" i " " u (when (zero? i) " (app host)")) (get nodes [:thread i])))
                                     (:threads cfg)))
                 (node (str "this page" (when rid (str " — " rid))) (some? rid)))
             (el "h2" {} "A network atom: app/counter")
             (el "p" {} (el "button" {:id "swap" :click (fn [_] (h/send! (:runtime @state) {:op :swap}))} "swap (+1)")
                 (str "n = " (if (some? n) n "…") ", epoch " (if (some? epoch) epoch "…")))
             (el "h2" {} "The thread pool :work")
             (el "p" {} (el "button" {:id "job" :click (fn [_] (h/send! (:runtime @state) {:op :job}))} "run a job")
                 (str (count jobs) " run"))
             (apply el "ul" {} (map (fn [j] (el "li" {} (str "who(" (:x j) ") = " (:y j) ", ran on " (:ran j) " (" (name (or (:state j) :unknown)) ")")))
                                    (take-last 10 jobs)))
             (apply el "ul" {:class "errors"} (map (fn [e] (el "li" {} (str "error: " e))) errors)))))

(defn- check-report! []
  (let [{:keys [nodes cfg before after-epoch jobs errors rid]} @state
        n-threads (count (:threads cfg))
        green (and rid (:broker nodes) (:store nodes) (every? (fn [i] (get nodes [:thread i])) (range n-threads)))]
    (set! (.-__evecheck js/window)
          (clj->js {:done (boolean (and (some? after-epoch) (seq jobs) green))
                    :all_green (boolean green)
                    :nodes (into {} (map (fn [[k v]] [(str/join "-" (map str (if (vector? k) k [k]))) v])) nodes)
                    :epoch_before before :epoch_after after-epoch
                    :ran_by (:ran (first jobs)) :error (first errors)}))))

(defn- on-message [m]
  (case (:op m)
    :ready (do (swap! state assoc :rid (:rid m) :n (:n m) :epoch (:epoch m))
               (when (:check @state) (h/send! (:runtime @state) {:op :swap})))
    :counter (do (swap! state assoc :n (:n m) :epoch (:epoch m))
                 (when (and (:check @state) (nil? (:after-epoch @state)))
                   (swap! state assoc :before (:before m) :after-epoch (:epoch m))
                   (h/send! (:runtime @state) {:op :job})))
    :job (swap! state update :jobs conj m)
    :error (swap! state update :errors conj (:error m))
    nil)
  (when (:check @state) (check-report!)))

(defn- poll-health! []
  (let [{:keys [cfg]} @state
        probe (fn [k u] (-> (js/fetch (str u "/health") #js {:cache "no-store"})
                            (.then (fn [r] (swap! state assoc-in [:nodes k] (.-ok r))))
                            (.catch (fn [_] (swap! state assoc-in [:nodes k] false)))))]
    (probe :broker (:broker cfg))
    (probe :store (:store cfg))
    (doseq [[i u] (map-indexed vector (:threads cfg))] (probe [:thread i] u))))

(defn- main []
  (-> (js/fetch "/config.json" #js {:cache "no-store"})
      (.then (fn [r] (.json r)))
      (.then (fn [c]
               (let [[w] (h/start-threads! 1 'eve.net.local-page/runtime-main {:reply (h/self)} {})]
                 (swap! state assoc :cfg (js->clj c :keywordize-keys true) :runtime w
                        :check (boolean (re-find #"[?&]check=1" (.-search js/location))))
                 (add-watch state ::render (fn [_ _ old new] (when-not (identical? old new) (render!) (when (:check new) (check-report!)))))
                 (render!)
                 (poll-health!)
                 (js/setInterval poll-health! 2000)
                 (js/setInterval (fn [] (loop [] (let [m (h/receive! 0)] (when (map? m) (on-message m) (recur))))) 100))))))

(defn init
  "The program's entry, on the page and in every worker it starts (they run the whole program)."
  []
  (when-not (h/run-thread!)
    (main)))
