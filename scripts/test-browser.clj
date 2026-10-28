;; bb test:browser — the thread feature's conformance in a browser (eve.runner.thread-browser-main).
;;
;; Builds the page with shadow-cljs (the :eve-thread-browser build, at :simple with no output wrapper, so the
;; kernel a worker importScripts keeps its names global) through deps.edn's :browser-test alias — eve's src and
;; hosts.edn's :browser roots — serves it cross-origin isolated (COOP + COEP: SharedArrayBuffer needs it) on
;; 127.0.0.1, opens it in headless Chromium and waits for the page to POST its result to /result. Exits 0 when the
;; suite and the main thread's rows are green.
;;
;;   EVE_CHROME (default /opt/pw-browsers/chromium), EVE_BROWSER_TIMEOUT_S (default 300).
;;   EVE_BROWSER_PERF=1: eve's performance suite instead (the :eve-perf-browser build, eve.runner.perf-browser-main),
;;   its lines printed.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[org.httpkit.server :as http])

(def eve (str (fs/absolutize ".")))
(def perf? (some? (System/getenv "EVE_BROWSER_PERF")))
(def build (if perf? "eve-perf-browser" "eve-thread-browser"))
(def out (str eve (if perf? "/target/perf-browser" "/target/thread-browser")))
(def public (str out "/public"))

(defn- step [& xs] (apply println "test:browser:" xs) (flush))

(fs/create-dirs public)
(spit (str public "/index.html")
      "<!doctype html><meta charset=utf-8><title>eve.thread-host conformance</title><script src=\"/js/main.js\"></script>")

(step (str "building the page (shadow-cljs release " build ", :simple)"))
(let [r (p/shell {:dir eve :continue true :out :string :err :string}
                 "clojure" "-M:browser-test" "-m" "shadow.cljs.devtools.cli" "release" build)]
  (when-not (zero? (:exit r))
    (println (:out r) (:err r))
    (step "build FAILED")
    (System/exit 1)))

(def result (promise))

(defn- serve [req]
  (let [uri (:uri req)
        isolated {"Cross-Origin-Opener-Policy" "same-origin" "Cross-Origin-Embedder-Policy" "require-corp"
                  "Cross-Origin-Resource-Policy" "same-origin" "Cache-Control" "no-store"}]
    (cond
      (and (= :post (:request-method req)) (= "/result" uri))
      (do (deliver result (slurp (:body req))) {:status 200 :headers isolated :body "ok"})
      :else
      (let [f (fs/file public (subs (if (= "/" uri) "/index.html" uri) 1))]
        (if (and (fs/regular-file? f) (str/starts-with? (str (fs/canonicalize f)) (str (fs/canonicalize public))))
          {:status 200
           :headers (assoc isolated "Content-Type" (cond (str/ends-with? uri ".js") "text/javascript"
                                                         (str/ends-with? uri ".html") "text/html"
                                                         :else "text/plain"))
           :body f}
          {:status 404 :headers isolated :body "not found"})))))

(def stop (http/run-server serve {:ip "127.0.0.1" :port 0 :legacy-return-value? false}))
(def port (http/server-port stop))
(def profile (str (fs/create-temp-dir {:prefix "eve-chrome-"})))
(step (str "serving on 127.0.0.1:" port "; opening headless Chromium"))
(def chrome (p/process {:out :string :err :string}
                       (or (System/getenv "EVE_CHROME") "/opt/pw-browsers/chromium")
                       "--headless=new" "--no-sandbox" "--disable-gpu" "--no-proxy-server" "--no-first-run"
                       "--enable-logging=stderr" "--v=0"
                       (str "--user-data-dir=" profile) (str "http://127.0.0.1:" port "/index.html")))

(def timeout-s (parse-long (or (System/getenv "EVE_BROWSER_TIMEOUT_S") "300")))
(def got (deref result (* 1000 timeout-s) nil))
(p/destroy-tree chrome)
(deref chrome 10000 nil)
(http/server-stop! stop)
(try (fs/delete-tree profile) (catch Exception _ nil))

(let [log (str (:err (deref chrome 1000 nil)))]
  ;; Chromium's log, the page's and its workers' console lines among it
  (spit (str out "/chrome.log") log)
  (when (or (not got) (System/getenv "EVE_BROWSER_CONSOLE"))
    (doseq [l (remove #(re-find #":(ERROR|WARNING):(?!CONSOLE)" %) (str/split-lines log))] (println "  log:" l))))

(if-not got
  (do (step (str "no result from the page within " timeout-s " s")) (System/exit 1))
  (let [r (edn/read-string got)
        _ (when (= :perf (:op r))
            (doseq [l (:lines r)] (println (pr-str l)))
            (System/exit 0))
        {:keys [test pass fail error]} (:summary r)
        main-ok (every? true? (vals (:main r)))]
    (step "result" (pr-str (dissoc r :failures)))
    (doseq [f (:failures r)] (println "  " f))
    (println :summary (assoc (:summary r) :main (:main r)))
    (System/exit (if (and (= :suite (:op r)) (zero? (+ fail error)) (pos? test) main-ok) 0 1))))
