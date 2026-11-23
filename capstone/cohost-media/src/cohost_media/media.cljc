(ns cohost-media.media
  "The media facility behind cohost-media.tasks' publish and the Go member's init: the media server
   (range-checked serving of the outputs, the page, and the gateway's routes proxied) and the watched
   folder's watcher. The implementation is the runtime's own — glojure's, in cohost-media.media-impl
   (the eveglj/media Go package) — resolved lazily: only a runtime that carries it ever loads it.")

(defn- impl-fn
  "The runtime's media implementation var, when this runtime carries it."
  [sym]
  #?(:cljs nil
     :default (try (requiring-resolve sym) (catch Throwable _ nil))))

(defn init!
  "Start the media server and the folder watcher (the member ctx's control config names the ports)."
  [ctx]
  (if-let [f (impl-fn 'cohost-media.media-impl/init!)]
    (f ctx)
    (throw (ex-info "media: no media facility on this runtime" {}))))

(defn publish!
  "The publish job: a transcode asked of the media server by a range request, recorded with the URL
   the page plays it from."
  [job]
  (if-let [f (impl-fn 'cohost-media.media-impl/publish!)]
    (f job)
    (throw (ex-info "media: no media facility on this runtime" {}))))
