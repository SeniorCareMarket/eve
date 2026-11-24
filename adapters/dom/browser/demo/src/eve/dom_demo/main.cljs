(ns eve.dom-demo.main
  "eve.dom's acceptance demo: a reagami counter with a d3 bar, and canvas-confetti, all made for a page, running in
   the program's main worker against the page's DOM through eve.dom's proxies. The page's own thread runs nothing of
   the program.

   It drives itself: ten clicks on the counter's button (dispatched on the page, handled in the worker, each
   re-rendering through reagami and moving the bar with a d3 transition, timed by the page's animation frames) until
   the bar is 100% wide, then a confetti burst (canvas-confetti draws through the proxied 2D context,
   timed by the page's animation frames). The checks go to /result for bb test:browser:dom-demo; opened by hand, the
   page just shows the demo."
  (:require [eve.dom.app :as app]
            [eve.dom.proxy :as proxy]
            [reagami.core :as r]))

(defonce ^:private clicks (atom 0))

(declare render!)

(def ^:private d3-files
  ;; d3's UMD modules in dependency order (unmodified; licences beside them)
  ["d3-dispatch-3.0.2/d3-dispatch.min.js" "d3-selection-3.0.1/d3-selection.min.js" "d3-color-3.1.1/d3-color.min.js"
   "d3-interpolate-3.0.2/d3-interpolate.min.js" "d3-ease-3.0.2/d3-ease.min.js" "d3-timer-3.0.2/d3-timer.min.js"
   "d3-transition-3.0.2/d3-transition.min.js"])

(defn- update-bar!
  "The bar to n tenths of its track: a d3 transition, run in this worker on the proxied rect."
  [n]
  (-> (.select js/d3 "#bar")
      (.transition)
      (.duration 200)
      (.attr "width" (str (* 10 (min n 10)) "%"))))

(defn- view []
  [:div {:id "demo"}
   [:h1 "eve.dom"]
   [:p "This counter and the confetti run in a worker; the page only executes their DOM operations."]
   [:button {:id "inc" :on-click (fn [_] (swap! clicks inc) (render!) (update-bar! @clicks))}
    (str "clicked " @clicks " times")]
   [:svg {:width 300 :height 20 :style {:border "1px solid #ccc"}}
    [:rect {:id "bar" :height 20 :width "0%" :fill "steelblue"}]]])

(defn- render! []
  (r/render (.getElementById js/document "app") (view)))

(defn- origin [] (.-origin js/location))

(defn- post! [m]
  (js/fetch (str (origin) "/result") #js {:method "POST" :body (pr-str m)}))

(defn- wait-until
  "A Promise: resolves once (ok?) holds, polling each animation frame; rejects after ms."
  [ok? ms]
  (let [t0 (js/Date.now)]
    (js/Promise.
     (fn [resolve reject]
       (letfn [(step [_]
                 (cond (ok?) (resolve true)
                       (> (- (js/Date.now) t0) ms) (reject (js/Error. "timed out"))
                       :else (js/requestAnimationFrame step)))]
         (step 0))))))

(defn- self-test []
  (let [checks (atom {})
        check! (fn [k v] (swap! checks assoc k (boolean v)))
        btn (.getElementById js/document "inc")]
    (check! :rendered (and btn (= "clicked 0 times" (.-textContent btn))))
    (dotimes [_ 10] (.click btn))
    (-> (wait-until #(= 10 @clicks) 10000)
        (.then (fn [_]
                 (check! :clicks-handled (= 10 @clicks))
                 (check! :re-rendered (= "clicked 10 times" (.-textContent (.getElementById js/document "inc"))))
                 (check! :same-button (identical? btn (.getElementById js/document "inc")))
                 (wait-until #(= "100%" (.getAttribute (.getElementById js/document "bar") "width")) 10000)))
        (.then (fn [_]
                 (check! :d3-bar-full (= "100%" (.getAttribute (.getElementById js/document "bar") "width")))
                 (let [before (:ops (proxy/stats))
                       p (js/confetti #js {:particleCount 120 :spread 70 :ticks 90 :origin #js {:y 0.6}})]
                   (check! :confetti-canvas (some? (.querySelector js/document "canvas")))
                   (.then p (fn [_] (- (:ops (proxy/stats)) before))))))
        (.then (fn [ops]
                 (check! :confetti-drew (> ops 1000))
                 (check! :confetti-done true)
                 (check! :no-page-errors (zero? (:page-errors (proxy/stats))))
                 (let [cs @checks
                       fail (count (remove true? (vals cs)))]
                   (post! {:op :suite :summary {:test 1 :pass (- (count cs) fail) :fail fail :error 0}
                           :failures (vec (keep (fn [[k v]] (when-not v (str k))) cs))
                           :main cs :confetti-ops ops :transport (proxy/transport-kind) :stats (proxy/stats)}))))
        (.catch (fn [e]
                  (post! {:op :suite :summary {:test 1 :pass 0 :fail 0 :error 1} :failures [(str e)] :main @checks}))))))

(defn main
  "main, in the program's main worker."
  []
  (doseq [f d3-files] (js/importScripts (str (origin) "/vendor/" f)))
  (js/importScripts (str (origin) "/vendor/canvas-confetti-1.9.4/confetti.browser.js"))
  (render!)
  (self-test))

(defn init []
  (app/run-app! {:main 'eve.dom-demo.main/main}))
