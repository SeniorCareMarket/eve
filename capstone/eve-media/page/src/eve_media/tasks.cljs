(ns eve-media.tasks
  "The browser's jobs (its eve-media.tasks: every member defines the tasks it serves in this namespace, the name the
   coordinator submits them by): the :waveform job, run on the browser runtime's own threads (Web Workers), and what
   a worker needs to reach the domain — this origin's config and the network rungs of the gateway it serves."
  (:require [clojure.string :as str]
            [eve.net.platform :as platform]
            [eve.thread :as t]
            [eve.thread.net :as tn]
            [eve.thread.rung :as rung]
            [eve-media.core :as em]))

(def caps #{:browser})

(defn fetch-config
  "This origin's config.json (the domain id and api key the launcher minted, the run's directories): read by the page
   and by every worker (a worker's location is its bootstrap's blob URL, so the origin is named)."
  []
  (let [xhr (js/XMLHttpRequest.)]
    (.open xhr "GET" (str (.-origin js/location) "/config.json") false)
    (.send xhr nil)
    (js->clj (js/JSON.parse (.-responseText xhr)) :keywordize-keys true)))

(defn net-cfg [{:keys [domain_id api_key]}]
  {:endpoint (str (.-origin js/location) "/gw") :domain-id domain_id :api-key api_key})

(defonce ^:private worker-ctx (atom nil))

(defn ctx
  "This worker's view of the domain: its config, its platform and the library's network rung, opened once per worker."
  []
  (or @worker-ctx
      (let [cfg (fetch-config)
            p (platform/platform)]
        (reset! worker-ctx {:cfg cfg :p p :library (tn/rung (net-cfg cfg) em/library-name p)}))))

(defn- fmt [x] (/ (js/Math.round (* 100 x)) 100))

(t/deftask waveform
  "The waveform of a file's audio, drawn in the browser: the loudness job's peaks (0..1, one per hundredth of the
   file) as the points of a mirrored polygon in a 100×20 box, written into the library."
  [{:keys [path]}]
  (js/console.log "eve-media waveform:" path)
  (let [{:keys [library]} (ctx)
        peaks (get-in (rung/value library) [:files path :results :loudness :waveform])
        v (if (seq peaks)
            (let [n (count peaks)
                  x (fn [i] (fmt (* 100 (/ i (max 1 (dec n))))))
                  top (map-indexed (fn [i p] (str (x i) "," (fmt (- 10 (* 10 p))))) peaks)
                  bottom (reverse (map-indexed (fn [i p] (str (x i) "," (fmt (+ 10 (* 10 p))))) peaks))]
              {:points (str/join " " (concat top bottom)) :peaks n :drawn-by "browser"})
            {:none true :drawn-by "browser"})]
    (rung/-swap! library (fn [lib] (assoc-in lib [:files path :results :waveform] v)))
    v))
