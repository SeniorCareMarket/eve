(ns cohost-media.tasks
  "Every cohost-media task, in one namespace every runtime loads whole (the runtime-pool cleanup:
   the five per-member tasks files collapse into this one; which tasks a member runs is DATA — the
   caps in the pool config the boot program hands it — not which file its runtime loaded).

   The bodies call the portable facilities: cohost-media.work (indexing, duplicate grouping),
   cohost-media.av (transcode and PCM: the runtime's native FFmpeg implementation where its pool
   config names it, the ffmpeg CLI otherwise), cohost-media.media (the media server's publish),
   cohost-media.meta (metadata, a JVM facility resolved lazily — only a :meta-capped member runs
   it). Perceptual hashes and EBU R128 loudness are plain Clojure over an ffmpeg frame and the PCM
   window region. The npm tasks (thumb/sheet/tags) are ClojureScript — their bodies are the :cljs
   arms, run by the Node member; each answers a promise the member's pump settles. The browser
   member's waveform task and its workers' way to the domain (this origin's config, the gateway's
   network rungs) are the last section: they lived in the page's own tasks file while the page's
   sources were .cljs — a second file of this same name, which the page build's extension
   precedence silently preferred — and moved here when the page's sources became .cljc, so the
   namespace has one home again."
  (:refer-clojure :exclude [meta])
  (:require ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [clojure.string :as str]
   [cohost-media.av :as av]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [cohost-media.core :as em]
   [cohost-media.media :as media]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [cohost-media.state :as st]
   [cohost-media.work :as work]
   #?@(:clj [[cohost.pool :as pool]
             [cohost-media.phash :as ph]
             [cohost-media.r128 :as r128]
             [cohost.region :as region]]
       :cljs [[cohost.pool :as pool :include-macros true]])))

(pool/deftask index [job] (work/index! job))

(pool/deftask group [job] (work/group! job))

(pool/deftask meta [job]
  #?(:cljs (throw (ex-info "meta is a JVM facility" {}))
     :default ((requiring-resolve 'cohost-media.meta/run!) job)))

#?(:clj
   (defn- sh
     "Run a command, answer {:exit :out (bytes) :err (text)}."
     [& args]
     (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args)) (.redirectErrorStream false)))
           out (future (with-open [in (.getInputStream p)] (.readAllBytes in)))
           err (future (slurp (.getErrorStream p)))]
       {:exit (.waitFor p) :out @out :err @err})))

#?(:clj
   (defn- ffmpeg [& args] (apply sh "ffmpeg" "-nostdin" "-v" "error" "-y" args)))

(pool/deftask phash [{:keys [path kind]}]
  #?(:clj
     (let [{:keys [exit out err]} (apply ffmpeg (concat (when (= :video kind) ["-ss" "1"])
                                                        ["-i" path "-frames:v" "1" "-vf" "scale=32:32:flags=area,format=gray"
                                                         "-f" "rawvideo" "-"]))
           px (mapv (fn [b] (bit-and b 0xFF)) out)]
       (when-not (and (zero? exit) (= 1024 (count px)))
         (throw (ex-info "phash: no 32x32 frame" {:exit exit :err err})))
       (work/record! path :phash {:phash (ph/phash px)}))
     :default (throw (ex-info "phash: no facility on this runtime" {}))))

(pool/deftask transcode [job] (av/transcode! job))

(pool/deftask pcm [job] (av/pcm! job))

#?(:clj
   (defn- region-floats
     "The f32le samples of a window region, read in place through the pool's region read."
     [{:keys [window offset length]}]
     (let [bs ^bytes (region/read-bytes window offset length)
           fb (.asFloatBuffer (.order (java.nio.ByteBuffer/wrap bs) java.nio.ByteOrder/LITTLE_ENDIAN))
           xs (double-array (.remaining fb))]
       (dotimes [i (alength xs)] (aset xs i (double (.get fb i))))
       xs)))

(pool/deftask loudness [{:keys [path]}]
  #?(:clj
     (let [pcm (get-in @(st/library) [:files path :results :pcm])]
       (work/record! path :loudness
                     (if (or (nil? pcm) (:none pcm))
                       {:lufs nil :no-audio true}
                       (let [xs (region-floats pcm)]
                         {:lufs (some-> (r128/integrated xs (:channels pcm) (:rate pcm)) (* 100.0) Math/round (/ 100.0))
                          :waveform (r128/peaks xs (:channels pcm) 100)
                          :read-from (:window pcm)}))))
     :default (throw (ex-info "loudness: no facility on this runtime" {}))))

(pool/deftask publish [job] (media/publish! job))

#?(:cljs
   (do
     ;; the Node member's facilities, bound only where a require
     ;; exists: every runtime loads this namespace whole, the browser
     ;; included, and a browser has no require to call (on the Node
     ;; member these bind exactly as they always have)
     (def ^:private sharp (when (exists? js/require) (js/require "sharp")))
     (def ^:private mm (when (exists? js/require) (js/require "music-metadata")))
     (def ^:private cp (when (exists? js/require) (js/require "child_process")))
     (def ^:private node-fs (when (exists? js/require) (js/require "fs")))
     (def ^:private node-path (when (exists? js/require) (js/require "path")))
     (def ^:private node-os (when (exists? js/require) (js/require "os")))

     (defn- ^js image [x] (sharp x))

     (defn- ffmpeg! [& args]
       (.execFileSync cp "ffmpeg" (clj->js (concat ["-nostdin" "-v" "error" "-y"] args)) #js {:stdio "pipe"}))

     (defn- mkdirs! [f] (.mkdirSync node-fs (.dirname node-path f) #js {:recursive true}))

     (defn- tmp-dir [] (.mkdtempSync node-fs (.join node-path (.tmpdir node-os) "cohost-media-node-")))

     (defn- join [& xs] (apply (.-join node-path) xs))))

(pool/deftask thumb [{:keys [path id kind out]}]
  #?(:cljs
     (let [dest (em/output-path out :thumb id)
           src (if (= :image kind)
                 path
                 (let [f (join (tmp-dir) "frame.png")]
                   (ffmpeg! "-ss" "1" "-i" path "-frames:v" "1" f)
                   f))]
       (mkdirs! dest)
       (-> ^js (.toFile ^js (.jpeg ^js (.resize (image src) em/thumb-width) #js {:quality 80}) dest)
           (.then (fn [^js info] (work/record! path :thumb {:path dest :width (.-width info) :height (.-height info)})))))
     :default (throw (ex-info "thumb is an npm task" {}))))

(pool/deftask sheet [{:keys [path id out]}]
  #?(:cljs
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
       (let [tiles (vec (sort (filter (fn [f] (.endsWith f ".png")) (vec (.readdirSync node-fs d)))))
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
                                                              :tiles (count tiles)}))))))
     :default (throw (ex-info "sheet is an npm task" {}))))

(pool/deftask tags [{:keys [path]}]
  #?(:cljs
     (-> (.parseFile mm path)
         (.then (fn [^js m]
                  (let [^js f (.-format m) ^js c (.-common m)]
                    (work/record! path :tags {:container (.-container f) :codec (.-codec f) :duration (.-duration f)
                                              :sample-rate (.-sampleRate f) :channels (.-numberOfChannels f)
                                              :title (.-title c) :artist (.-artist c)})))))
     :default (throw (ex-info "tags is an npm task" {}))))

;; ---------------------------------------------------------------------------
;; The browser member: what its workers need to reach the domain, and its
;; waveform task. (The page's own caps are pool-config data, as every
;; member's are; the old page tasks file's (def caps #{:browser}) had no
;; remaining consumer and did not move.)
;; ---------------------------------------------------------------------------

#?(:cljs
   (do
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
                 p (pool/net-platform)]
             (reset! worker-ctx {:cfg cfg :p p :library (pool/net-atom (net-cfg cfg) em/library-name p)}))))

     (defn- fmt [x] (/ (js/Math.round (* 100 x)) 100))))

(pool/deftask waveform
  "The waveform of a file's audio, drawn in the browser: the loudness job's peaks (0..1, one per hundredth of the
  file) as the points of a mirrored polygon in a 100×20 box, written into the library."
  [{:keys [path]}]
  #?(:cljs
     (do (js/console.log "cohost-media waveform:" path)
         (let [{:keys [library]} (ctx)
               peaks (get-in (pool/read-atom library) [:files path :results :loudness :waveform])
               v (if (seq peaks)
                   (let [n (count peaks)
                         x (fn [i] (fmt (* 100 (/ i (max 1 (dec n))))))
                         top (map-indexed (fn [i p] (str (x i) "," (fmt (- 10 (* 10 p))))) peaks)
                         bottom (reverse (map-indexed (fn [i p] (str (x i) "," (fmt (+ 10 (* 10 p))))) peaks))]
                     {:points (str/join " " (concat top bottom)) :peaks n :drawn-by "browser"})
                   {:none true :drawn-by "browser"})]
           (pool/swap-atom! library (fn [lib] (assoc-in lib [:files path :results :waveform] v)))
           v))
     :default (throw (ex-info "waveform is the browser's task" {}))))
