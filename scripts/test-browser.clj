;; bb test:browser — the thread feature's conformance in a browser (eve.runner.thread-browser-main).
;;
;; Builds the page with shadow-cljs (the :eve-thread-browser build, at :simple with no output wrapper, so the
;; kernel a worker importScripts keeps its names global) through deps.edn's :browser-test alias — eve's src and
;; hosts.edn's :browser roots — serves it cross-origin isolated (COOP + COEP: SharedArrayBuffer needs it) on
;; 127.0.0.1, opens it in headless Chromium and waits for the page to POST its result to /result. Exits 0 when the
;; suite and the main thread's rows are green.
;;
;;   EVE_CHROME (default: /opt/pw-browsers/chromium when present, else a
;;   Playwright-installed Chromium from the per-OS cache — `npx playwright
;;   install chromium` puts it in ~/Library/Caches/ms-playwright on macOS,
;;   ~/.cache/ms-playwright on Linux), EVE_BROWSER_TIMEOUT_S (default 300).
;;   EVE_BROWSER_PERF=1: eve's performance suite instead (the :eve-perf-browser build, eve.runner.perf-browser-main),
;;   its lines printed. EVE_BROWSER_OPFS=1: the OPFS disk tier's suites instead (the :eve-opfs-browser build,
;;   eve.runner.opfs-browser-main: the page mounts the OPFS pool first). EVE_BROWSER_SUITE=dom (bb test:browser:dom):
;;   eve.dom's conformance instead (the :eve-dom-browser build, eve.runner.dom-browser-main: the program's main a
;;   worker driving the page's DOM); EVE_BROWSER_SUITE=dom-demo (bb test:browser:dom-demo): its acceptance demo,
;;   reagami + canvas-confetti.
;;   EVE_BROWSER=firefox|webkit: that engine instead of Chromium, through Playwright (scripts/browser-open.cjs; NODE_PATH
;;   to find playwright, PLAYWRIGHT_BROWSERS_PATH for its browsers). For eve.dom's transports: EVE_BROWSER_COI=0 serves
;;   the page without COOP/COEP (it must isolate itself through eve-sw.js), EVE_BROWSER_QUERY is the page URL's query
;;   (eve-dom-transport=sw: the service worker's relay), EVE_BROWSER_EXPECT the transport the result must report.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[org.httpkit.server :as http])

(def eve (str (fs/absolutize ".")))
(def perf? (some? (System/getenv "EVE_BROWSER_PERF")))
(def opfs? (and (not perf?) (some? (System/getenv "EVE_BROWSER_OPFS"))))
(def suite (System/getenv "EVE_BROWSER_SUITE"))
(def dom? (contains? #{"dom" "dom-demo"} suite))
(def build (cond perf? "eve-perf-browser" opfs? "eve-opfs-browser" (= "dom" suite) "eve-dom-browser"
                 (= "dom-demo" suite) "eve-dom-demo" :else "eve-thread-browser"))
(def out (str eve (cond perf? "/target/perf-browser" opfs? "/target/opfs-browser" (= "dom" suite) "/target/dom-browser"
                        (= "dom-demo" suite) "/target/dom-demo" :else "/target/thread-browser")))
(def aliases (if (= "dom-demo" suite) "-M:browser-test:dom-demo" "-M:browser-test"))
(def public (str out "/public"))

(defn- step [& xs] (apply println "test:browser:" xs) (flush))

(fs/create-dirs public)
(spit (str public "/index.html")
      (if dom?
        "<!doctype html><meta charset=utf-8><title>eve.dom</title><body><div id=\"app\"></div><script src=\"/js/main.js\"></script></body>"
        "<!doctype html><meta charset=utf-8><title>eve.thread-host conformance</title><script src=\"/js/main.js\"></script>"))
(when dom?
  ;; eve's service worker at the site root: isolation headers for an unisolated page, and eve.dom's relay
  (fs/copy (str eve "/adapters/sw/browser/eve-sw.js") (str public "/eve-sw.js") {:replace-existing true}))
(when (= "dom-demo" suite)
  ;; the demo's third-party scripts (unmodified, licences beside them), loaded by its main worker
  (fs/copy-tree (str eve "/adapters/dom/browser/demo/public") public {:replace-existing true}))

;; the OPFS page boots through eve's service worker (eve.opfs.boot: it isolates a page served without COOP/COEP)
(when opfs? (fs/copy (str eve "/adapters/sw/browser/eve-sw.js") (str public "/eve-sw.js") {:replace-existing true}))

(step (str "building the page (shadow-cljs release " build ", :simple)"))
(let [r (p/shell {:dir eve :continue true :out :string :err :string}
                 "clojure" aliases "-m" "shadow.cljs.devtools.cli" "release" build)]
  (when-not (zero? (:exit r))
    (println (:out r) (:err r))
    (step "build FAILED")
    (System/exit 1)))

(def result (promise))

(defn- serve [req]
  (let [uri (:uri req)
        isolated (if (= "0" (System/getenv "EVE_BROWSER_COI"))
                   ;; served unisolated: the page must get there itself (eve.dom: through eve-sw.js)
                   {"Cache-Control" "no-store"}
                   {"Cross-Origin-Opener-Policy" "same-origin" "Cross-Origin-Embedder-Policy" "require-corp"
                    "Cross-Origin-Resource-Policy" "same-origin" "Cache-Control" "no-store"})]
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

(defn- find-chrome
  "EVE_CHROME wins; then the fleet path /opt/pw-browsers/chromium; then a
   Playwright-installed Chromium under the per-OS cache dir (the fleet
   path exists on the Linux boxes; a Mac only has the cache)."
  []
  (or (System/getenv "EVE_CHROME")
      (let [fleet "/opt/pw-browsers/chromium"
            home (System/getProperty "user.home")
            mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac")
            cache (if mac?
                    (str home "/Library/Caches/ms-playwright")
                    (str home "/.cache/ms-playwright"))
            full (if mac?
                   ["chromium-*/chrome-mac*/Chromium.app/Contents/MacOS/Chromium"]
                   ["chromium-*/chrome*/chrome"])
            shell (if mac?
                    ["chromium_headless_shell-*/chrome-mac*/headless_shell"]
                    ["chromium_headless_shell-*/chrome*/chrome-headless-shell"
                     "chromium_headless_shell-*/chrome*/headless_shell"])
            newest (fn [pats] (last (sort (map str (mapcat #(fs/glob cache %) pats)))))]
        (or (when (fs/exists? fleet) fleet)
            (newest full)
            (newest shell)))))

(def stop (http/run-server serve {:ip "127.0.0.1" :port 0 :legacy-return-value? false}))
(def port (http/server-port stop))
(def profile (str (fs/create-temp-dir {:prefix "eve-chrome-"})))
(step (str "serving on 127.0.0.1:" port "; opening headless " (or (System/getenv "EVE_BROWSER") "Chromium")))
(def page-url (str "http://127.0.0.1:" port "/index.html" (some->> (System/getenv "EVE_BROWSER_QUERY") (str "?"))))
(def engine (System/getenv "EVE_BROWSER"))
(def chrome
  (if engine
    ;; another engine (firefox, webkit) through Playwright (NODE_PATH, PLAYWRIGHT_BROWSERS_PATH as usual)
    (p/process {:out :string :err :string :dir eve}
               "node" "scripts/browser-open.cjs" engine page-url
               ;; the OPFS page needs a persistent profile (WebKit has no OPFS in an ephemeral context)
               (if opfs? profile ""))
    (p/process {:out :string :err :string}
               (or (find-chrome)
                   (throw (ex-info "test:browser: no Chromium found — set EVE_CHROME or run: npx playwright install chromium" {})))
               "--headless=new" "--no-sandbox" "--disable-gpu" "--no-proxy-server" "--no-first-run"
               "--enable-logging=stderr" "--v=0"
               (str "--user-data-dir=" profile) page-url)))

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
        expect (System/getenv "EVE_BROWSER_EXPECT")
        main-ok (and (every? true? (vals (:main r)))
                     ;; eve.dom: the transport the run was meant to reach
                     (or (nil? expect) (= expect (name (:transport r :none)))))]
    (step "result" (pr-str (dissoc r :failures)))
    (doseq [f (:failures r)] (println "  " f))
    (println :summary (assoc (:summary r) :main (:main r)))
    (System/exit (if (and (= :suite (:op r)) (zero? (+ fail error)) (pos? test) main-ok) 0 1))))
