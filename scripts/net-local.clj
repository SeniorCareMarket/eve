;; bb net:local — eve's local network in one command (doc/release-split E1): an atom broker (eve.net.broker, passive)
;; and a blob store (eve.net.store) in this process, each on its own port; N thread servers (eve.thread.server, one
;; process each: eve.net.local-node), thread server 0 also the app host serving the page; then the page in a browser.
;;
;;   bb net:local [--port 8700] [--threads 3] [--hosts bb,jvm,nbb] [--dir D] [--no-open] [--no-build]
;;
;; Ports: the broker at --port, the store at +1, thread server i at +2+i (server 0 is the app host). --dir keeps the
;; broker's rows in an eve disk atom under D/broker and the store's blobs as files under D/store, one per key (both
;; read back at start, so a restart keeps every root and its blocks). --hosts names each
;; server's host in turn, the last repeated (default bb). Prints the addresses, the domain id and the API key (minted
;; for this run), and one EDN line {:step :ready ...}; Ctrl+C (SIGINT/SIGTERM) stops every node it started.
(require '[babashka.fs :as fs]
         '[babashka.http-client :as http]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[eve.atom :as ea]
         '[eve.http-server :as hs]
         '[eve.net.broker :as broker]
         '[eve.net.broker-disk :as broker-disk]
         '[eve.net.gateway :as gw]
         '[eve.net.json :as json]
         '[eve.net.store :as es]
         '[eve.process :as proc])

(def eve (str (fs/absolutize ".")))

(defn- say [& xs] (apply println "net:local:" xs) (flush))

(defn- opts [args]
  (loop [[a & more] args o {}]
    (cond
      (nil? a) o
      (#{"--no-open" "--no-build"} a) (recur more (assoc o (keyword (subs a 2)) true))
      (str/starts-with? a "--") (recur (rest more) (assoc o (keyword (subs a 2)) (first more)))
      :else (throw (ex-info (str "net:local: unknown argument " a) {})))))

(defn- file-blobs
  "The store's blobs kept as files under dir, one per key (its path the key; the base64 text as it arrived): an atom
   holding what dir holds now, whose every change is written back (a new or changed key written to a temporary file,
   then moved over its own; a gone key deleted). A key that would leave dir is kept in memory only."
  [dir]
  (let [root (fs/absolutize dir)
        _ (fs/create-dirs root)
        file-of (fn [k] (when-not (or (str/starts-with? k "/") (some #{".." "."} (str/split k #"/")))
                          (fs/file root k)))
        blobs (atom (into {} (for [f (fs/glob root "**") :when (and (fs/regular-file? f) (not (str/ends-with? (str f) ".tmp")))]
                               [(str/join "/" (map str (fs/relativize root f))) (slurp (fs/file f))])))]
    (add-watch blobs ::files
               (fn [_ _ old new]
                 (doseq [[k v] new :when (not (identical? v (get old k)))]
                   (when-let [f (file-of k)]
                     (fs/create-dirs (fs/parent f))
                     (let [tmp (fs/file (str f "." (random-uuid) ".tmp"))]
                       (spit tmp v)
                       (fs/move tmp f {:replace-existing true :atomic-move true}))))
                 (doseq [k (keys old) :when (not (contains? new k))]
                   (when-let [f (file-of k)] (fs/delete-if-exists f)))))
    blobs))

(defn- roots [host & more]
  (map (fn [r] (str (fs/absolutize r))) (concat ["src"] (get (edn/read-string (slurp "hosts.edn")) host) more)))

(defn- cmd
  "the command line that runs eve.net.local-node on host"
  [host args]
  (case host
    "bb" (concat ["bb" "-cp" (str/join ":" (roots :bb "net-local/src")) "-m" "eve.net.local-node"] args)
    "jvm" (concat ["clojure" "-Sdeps" "{:aliases {:nl {:extra-paths [\"net-local/src\"]}}}" "-M:nl" "-m" "eve.net.local-node"] args)
    "nbb" (concat ["nbb" "--classpath" (str/join ":" (roots :nbb "adapters/http-server/node/src" "net-local/src"))
                   "-m" "eve.net.local-node"] args)
    (throw (ex-info (str "net:local: no thread server on host " host) {}))))

(defn- with-health
  "handler h answering GET /health with {role ...} and (more), CORS open"
  ([role h] (with-health role h (fn [] {})))
  ([role h more]
   (fn [req]
     (if (and (= "GET" (:method req)) (= "/health" (first (str/split (:url req) #"\?"))))
       {:status 200 :headers {"content-type" "application/json" "access-control-allow-origin" "*"}
        :body (json/write-str (merge {"role" role} (more)))}
       (h req)))))

(defn- healthy? [url]
  (try (= 200 (:status (http/get url {:throw false :timeout 1000}))) (catch Exception _ false)))

(defn- wait-for [ms what f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (f) (if (> (System/currentTimeMillis) deadline)
                (throw (ex-info (str "net:local: timed out waiting for " what) {}))
                (do (Thread/sleep 100) (recur)))))))

(defn- build-page!
  "the page compiled into public/js when its sources are newer than it (shadow-cljs release net-local-page)"
  [public]
  (let [out (fs/file public "js/main.js")
        srcs (fs/glob "net-local/page" "**.cljs")
        stale? (or (not (fs/exists? out)) (some (fn [f] (pos? (compare (fs/last-modified-time f) (fs/last-modified-time out)))) srcs))]
    (when stale?
      (say "building the page (shadow-cljs release net-local-page)")
      (let [r (p/shell {:dir eve :continue true :out :string :err :string}
                       "clojure" "-M:browser-test:net-local-page" "-m" "shadow.cljs.devtools.cli" "release" "net-local-page")]
        (when-not (zero? (:exit r))
          (say "the page's build FAILED; serving without it\n" (:out r) (:err r)))))))

(let [o (opts *command-line-args*)
      port (parse-long (or (:port o) "8700"))
      n (max 3 (parse-long (or (:threads o) "3")))
      hosts (let [hs (str/split (or (:hosts o) "bb") #",")] (mapv (fn [i] (get hs i (last hs))) (range n)))
      key (str "local-" (random-uuid))
      did "local"
      base "http://127.0.0.1:"
      broker-url (str base port) store-url (str base (inc port) "/blobs/")
      thread-urls (mapv (fn [i] (str base (+ port 2 i))) (range n))
      public (str eve "/target/net-local/public")
      secret (str (random-uuid))
      domain (when (:dir o) (fs/create-dirs (:dir o)) (ea/persistent-atom-domain (str (fs/absolutize (:dir o)) "/broker")))
      store (es/store (cond-> {:url store-url :secret secret :now-ms proc/now-ms :log? true}
                        (:dir o) (assoc :blobs (file-blobs (fs/file (:dir o) "store")))))
      s-srv (hs/serve! {:port (inc port) :threads 16} (with-health "store" (:handle store)
                                                        (fn [] {"puts" (count (filter (fn [[m _ ok?]] (and (= "PUT" m) ok?)) @(:log store)))})))
      b (broker/broker (cond-> {:api-key key :domain-id did
                                :presign (gw/hmac-presigner {:url store-url :secret secret :now-ms proc/now-ms})}
                         domain (assoc :rows (broker-disk/disk-rows domain))))
      b-srv (hs/serve! {:port port :threads 16} (with-health "broker" (:handle b)
                                                  (fn [] (select-keys @(:state b) [:requests :conflicts]))))
      _ (fs/create-dirs (fs/file public "js"))
      _ (spit (fs/file public "config.json")
              (json/write-str {"domain_id" did "api_key" key "broker" broker-url "store" (str base (inc port))
                               "threads" thread-urls "app" (first thread-urls)}))
      _ (fs/copy "net-local/page/index.html" (fs/file public "index.html") {:replace-existing true})
      _ (when-not (:no-build o) (build-page! public))
      logs (str eve "/target/net-local/logs")
      _ (fs/create-dirs logs)
      procs (mapv (fn [i]
                    (let [args (concat ["thread" "--endpoint" broker-url "--domain-id" did "--rid" (str "t" i)
                                        "--port" (str (+ port 2 i))]
                                       (when (zero? i) ["--static" public]))]
                      (apply p/process {:dir eve :extra-env {"EVE_API_KEY" key}
                                        :out (str logs "/t" i ".log") :err (str logs "/t" i ".err")}
                             (cmd (nth hosts i) args))))
                  (range n))
      stop! (fn []
              (doseq [pr procs] (p/destroy-tree pr))
              (doseq [pr procs] (deref pr 5000 nil))
              ((:stop b-srv)) ((:stop s-srv))
              (when domain (ea/close-atom-domain! domain)))]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (fn [] (stop!) (say "stopped"))))
  (doseq [[i u] (map-indexed vector thread-urls)]
    (wait-for 600000 (str "thread server t" i)
              (fn [] (or (healthy? (str u "/health"))
                         (when-not (p/alive? (nth procs i))
                           (throw (ex-info (str "net:local: thread server t" i " exited; see " logs "/t" i ".err") {})))))))
  (say "broker " broker-url " (domain " did ", API key " key ")")
  (say "store  " (str base (inc port)))
  (doseq [[i u] (map-indexed vector thread-urls)] (say (str "thread t" i " ") u (str "(" (nth hosts i) ")") (if (zero? i) " + the page" "")))
  (println (pr-str {:step :ready :broker broker-url :store (str base (inc port)) :threads thread-urls :app (first thread-urls)
                    :domain did :api-key key :hosts hosts :pids (mapv (fn [pr] (.pid ^Process (:proc pr))) procs)}))
  (flush)
  (when-not (:no-open o)
    (try (p/process ["xdg-open" (str (first thread-urls) "/")]) (catch Exception _ (say "open" (str (first thread-urls) "/")))))
  @(promise))
