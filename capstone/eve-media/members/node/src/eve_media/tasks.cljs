(ns eve-media.tasks
  "The Node member's jobs, on npm's libraries: thumbnails and contact sheets with sharp (libvips) — a video's frames
   taken by the ffmpeg CLI, composited here — and an audio file's tags with music-metadata. Each task answers a
   promise; the member's pump (eve-media.node-member) completes the descriptor when it settles."
  (:require [eve.thread :as t]
            [eve-media.core :as em]
            [eve-media.work :as work]))

(def caps #{:npm})

(def ^:private sharp (js/require "sharp"))
(def ^:private mm (js/require "music-metadata"))
(def ^:private cp (js/require "child_process"))
(def ^:private fs (js/require "fs"))
(def ^:private node-path (js/require "path"))
(def ^:private os (js/require "os"))

(defn- ^js image [x] (sharp x))

(defn- ffmpeg! [& args]
  (.execFileSync cp "ffmpeg" (clj->js (concat ["-nostdin" "-v" "error" "-y"] args)) #js {:stdio "pipe"}))

(defn- mkdirs! [f] (.mkdirSync fs (.dirname node-path f) #js {:recursive true}))

(defn- tmp-dir [] (.mkdtempSync fs (.join node-path (.tmpdir os) "eve-media-node-")))

(defn- join [& xs] (apply (.-join node-path) xs))

(t/deftask thumb [{:keys [path id kind out]}]
  (let [dest (em/output-path out :thumb id)
        src (if (= :image kind)
              path
              (let [f (join (tmp-dir) "frame.png")]
                (ffmpeg! "-ss" "1" "-i" path "-frames:v" "1" f)
                f))]
    (mkdirs! dest)
    (-> ^js (.toFile ^js (.jpeg ^js (.resize (image src) em/thumb-width) #js {:quality 80}) dest)
        (.then (fn [^js info] (work/record! path :thumb {:path dest :width (.-width info) :height (.-height info)}))))))

(t/deftask sheet [{:keys [path id out]}]
  (let [dest (em/output-path out :sheet id)
        d (tmp-dir)
        n (* em/sheet-cols em/sheet-rows)
        w em/sheet-tile-width
        h em/sheet-tile-height]
    (mkdirs! dest)
    ;; n frames evenly over the first ten seconds, each scaled into a tile
    (ffmpeg! "-i" path "-vf" (str "fps=" n "/10,scale=" w ":" h ":force_original_aspect_ratio=decrease,pad=" w ":" h
                                  ":(ow-iw)/2:(oh-ih)/2")
             "-frames:v" (str n) (join d "t%02d.png"))
    (let [tiles (vec (sort (filter (fn [f] (.endsWith f ".png")) (vec (.readdirSync fs d)))))
          comps (clj->js (map-indexed (fn [i f] {:input (join d f)
                                                 :left (* w (mod i em/sheet-cols))
                                                 :top (* h (quot i em/sheet-cols))})
                                      tiles))]
      (-> (image #js {:create #js {:width (* w em/sheet-cols) :height (* h em/sheet-rows) :channels 3
                                   :background #js {:r 0 :g 0 :b 0}}})
          ^js (.composite comps)
          ^js (.jpeg #js {:quality 80})
          ^js (.toFile dest)
          (.then (fn [^js info] (work/record! path :sheet {:path dest :width (.-width info) :height (.-height info)
                                                       :tiles (count tiles)})))))))

(t/deftask tags [{:keys [path]}]
  (-> (.parseFile mm path)
      (.then (fn [^js m]
               (let [^js f (.-format m) ^js c (.-common m)]
                 (work/record! path :tags {:container (.-container f) :codec (.-codec f) :duration (.-duration f)
                                           :sample-rate (.-sampleRate f) :channels (.-numberOfChannels f)
                                           :title (.-title c) :artist (.-artist c)}))))))
