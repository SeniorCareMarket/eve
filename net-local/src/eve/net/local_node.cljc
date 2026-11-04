(ns eve.net.local-node
  "eve's local network, a node's host code (bb net:local starts these; scripts/net-local.clj): a thread server
   (eve.thread.server) in this process, and when it is the app host, the page's static files served beside its
   /health. Host code, not shared code: it reads files.

     <host's runner> -m eve.net.local-node thread --endpoint E --domain-id D --rid R [--port P] [--threads N]
                                                   [--static DIR] [--ttl MS]

   The broker's API key comes from the environment (EVE_API_KEY), never the command line. Prints one EDN line
   {:step :up :rid :port :pool} once it serves, and runs until its process is stopped."
  (:require [clojure.string :as str]
            [eve.process :as proc]
            [eve.thread.server :as server]
            #?@(:cljs [["fs" :as fs] ["path" :as path]])))

(def COOP
  "the page is cross-origin isolated (eve's browser threads share memory); the broker and store answer it with CORS"
  {"cross-origin-opener-policy" "same-origin" "cross-origin-embedder-policy" "require-corp"
   "cross-origin-resource-policy" "cross-origin" "cache-control" "no-store"})

(def TYPES {"html" "text/html; charset=utf-8" "js" "text/javascript" "json" "application/json" "css" "text/css"
            "map" "application/json" "svg" "image/svg+xml" "png" "image/png"})

(defn- type-of [p] (get TYPES (last (str/split p #"\.")) "application/octet-stream"))

(defn- file-b64
  "the base64 of the regular file at p under root, or nil"
  [root p]
  #?(:cljs (let [f (.resolve path root (str "." p))]
             (when (and (str/starts-with? f (.resolve path root)) (.existsSync fs f) (.isFile (.statSync fs f)))
               (.toString (.readFileSync fs f) "base64")))
     :default (let [r (.getCanonicalFile (java.io.File. ^String root))
                    f (.getCanonicalFile (java.io.File. r ^String (str "." p)))]
                (when (and (str/starts-with? (str f) (str r)) (.isFile f))
                  (.encodeToString (java.util.Base64/getEncoder) (java.nio.file.Files/readAllBytes (.toPath f)))))))

(defn static-handler
  "GET of a file under dir (/ is index.html), cross-origin isolated; nil for anything else"
  [dir]
  (fn [{:keys [method url]}]
    (when (= "GET" method)
      (let [p (first (str/split url #"\?"))
            p (if (str/ends-with? p "/") (str p "index.html") p)]
        (when-not (str/includes? p "..")
          (when-let [b (file-b64 dir p)]
            {:status 200 :headers (assoc COOP "content-type" (type-of p)) :body b :body-as :b64}))))))

(defn- flags [args] (into {} (map (fn [[k v]] [(keyword (subs k 2)) v])) (partition 2 args)))

(defn- getenv [k] #?(:cljs (aget (.-env js/process) k) :default (System/getenv k)))

(defn thread [args]
  (let [o (flags args)
        s (server/serve! {:endpoint (:endpoint o) :domain-id (:domain-id o) :api-key (getenv "EVE_API_KEY")
                          :rid (:rid o) :pool (keyword (or (:pool o) "work"))
                          ;; (30 s: a server beats every ttl/4 — each beat a swap of the domain cell, which a page's slower
                          ;; swaps over the browser's connections must win against)
                          :ttl (if (:ttl o) (parse-long (:ttl o)) 30000)
                          :threads (if (:threads o) (parse-long (:threads o)) 2)
                          :port (if (:port o) (parse-long (:port o)) 0)
                          :requires '[eve.thread.probe]
                          :handler (when (:static o) (static-handler (:static o)))})]
    (println (pr-str {:step :up :rid (:rid s) :port (:port s) :pool (:pool s) :static (some? (:static o))}))
    ;; (Node's event loop serves the requests: its server keeps the process alive, and a blocking sleep would stop it)
    #?(:cljs nil :default (loop [] (proc/sleep-ms! 60000) (recur)))))

(defn -main [& [mode & args]]
  (case mode
    "thread" (thread args)))
