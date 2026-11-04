;; sab_cap.clj — a Chromium renderer's SharedArrayBuffer cap (doc/hosts/browser.md, CHANGELOG.md): in one
;; cross-origin-isolated page, fixed 1 GB buffers until one is refused; beside them, growable buffers of 64 KB
;; with a 1 GB maximum (up to 400); then a worker of that page, 256 MB buffers until one is refused.
;;   bb sab_cap.clj        (headless Chromium from /opt/pw-browsers, as scripts/test-browser.clj)
(require (quote [clojure.string :as str]))
(require '[babashka.fs :as fs] '[babashka.process :as p] '[org.httpkit.server :as http])
(def page "<!doctype html><script>
const out = {};
let bufs = [], n = 0;
try { while (n < 64) { bufs.push(new SharedArrayBuffer(1 << 30)); n++; } } catch (e) { out.fixedError = String(e); }
out.fixed1GB = n;
let gs = [], g = 0;
try { while (g < 400) { gs.push(new SharedArrayBuffer(65536, {maxByteLength: 1 << 30})); g++; } } catch (e) { out.growableError = String(e); }
out.growable1GBMax = g;
const w = new Worker(URL.createObjectURL(new Blob(['let k=0,b=[],err=null;try{while(k<400){b.push(new SharedArrayBuffer(256<<20));k++}}catch(e){err=String(e)}postMessage({k,err})'], {type: 'text/javascript'})));
w.onmessage = (e) => { out.worker256MB = e.data.k; out.workerError = e.data.err; out.isolated = self.crossOriginIsolated;
  fetch('/result', {method: 'POST', body: JSON.stringify(out)}); };
</script>")
(def result (promise))
(def iso {"Cross-Origin-Opener-Policy" "same-origin" "Cross-Origin-Embedder-Policy" "require-corp"
          "Cross-Origin-Resource-Policy" "same-origin" "Cache-Control" "no-store"})
(def stop (http/run-server (fn [req] (if (= "/result" (:uri req))
                                       (do (deliver result (slurp (:body req))) {:status 200 :headers iso :body "ok"})
                                       {:status 200 :headers (assoc iso "Content-Type" "text/html") :body page}))
                           {:ip "127.0.0.1" :port 0 :legacy-return-value? false}))
(def profile (str (fs/create-temp-dir {:prefix "eve-sab-"})))
(def chrome (p/process {:out :string :err :string} "/opt/pw-browsers/chromium" "--headless=new" "--no-sandbox"
                       "--disable-gpu" "--no-proxy-server" "--no-first-run" (str "--user-data-dir=" profile)
                       (str "http://127.0.0.1:" (http/server-port stop) "/")))
(println "Chromium:" (-> (p/sh "/opt/pw-browsers/chromium" "--version") :out str/trim))
(println "result:" (deref result 120000 "timed out"))
(p/destroy-tree chrome)
(http/server-stop! stop)
(fs/delete-tree profile)
(shutdown-agents)
