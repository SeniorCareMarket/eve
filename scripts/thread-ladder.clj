;; The network rung over real HTTP (C3 across processes): bb scripts/thread-ladder.clj <gateway-host> <remote-host>
;;
;; Run from eve/. Three processes over real HTTP and a disk domain in the window (/dev/shm): an object store (babashka:
;; eve.thread-ladder-gateway store — blobs behind presigned URLs, never the gateway's), the gateway member G (jvm or bb:
;; attached by the disk, serving the domain's atoms on eve.net's routes) and the remote member R (any host with an http
;; adapter: jvm bb nbb clje cljr lpy), whose developer chose the network rung: R opens the rendezvous atom at G's gateway,
;; joins the one registry there, runs G's work and G runs R's; G's disk swaps and R's network swaps of one counter all
;; count; a hello R writes over the network is refused. The members' EDN lines are the receipts; this script checks them
;; and prints one summary line.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(load-file "scripts/thread-ladder-cmd.clj")

(defn lines [f]
  (keep (fn [l] (when (and (str/starts-with? l "{") (str/includes? l ":step "))
                  (let [m (edn/read-string {:default (fn [_ v] v)} l)] (when (:step m) m))))
        (str/split-lines (if (fs/exists? f) (slurp f) ""))))

(defn step [f s] (first (filter (fn [m] (= s (:step m))) (lines f))))

(defn now [] (System/currentTimeMillis))

(defn wait-for [ms what f]
  (let [deadline (+ (now) ms)]
    (loop []
      (or (f) (if (> (now) deadline) (throw (ex-info (str "launcher: timed out waiting for " what) {})) (do (Thread/sleep 50) (recur)))))))

(defn- failed [& fs] (some (fn [f] (step f :error)) fs))

(let [[hg hr] *command-line-args*
      dir (str (fs/create-temp-dir {:dir "/dev/shm" :prefix "eve-ladder-"}))
      base (str dir "/d")
      secret (str (random-uuid))
      ls (str dir "/store.log") lg (str dir "/g.log") lr (str dir "/r.log")
      t0 (now)
      ps (apply p/process {:out ls :err (str ls ".err")} (cmd "bb" "eve.thread-ladder-gateway" "store" secret))
      store-url (:url (wait-for 120000 "the store" (fn [] (step ls :store))))
      pg (apply p/process {:out lg :err (str lg ".err")} (cmd hg "eve.thread-ladder-gateway" "gateway" base store-url secret))
      gw (wait-for 600000 "the gateway" (fn [] (or (step lg :gateway) (when (failed lg) (throw (ex-info "G failed" (step lg :error)))))))
      pr (apply p/process {:out lr :err (str lr ".err")} (cmd hr "eve.thread-ladder" "remote" (:endpoint gw)))
      _ (deref pr 3600000 nil)
      _ (deref pg 600000 nil)
      _ (p/destroy-tree ps)
      j (step lr :joined) rw (step lr :work) rc (step lr :counted)
      gw1 (step lg :work) gc (step lg :counted) cnt (step lg :count) gd (step lg :g-done)
      only (fn [res k] (and (:ok res) (= [k] (keys (:ran res)))))
      checks {:joined-by-network (= :net (:via j))
              :one-registry (and (= #{"G" "R"} (set (keys (:runtimes (:registry gw1)))) (set (keys (:runtimes (:registry rw)))))
                                 (= (get-in gw1 [:registry :pools]) (get-in rw [:registry :pools])))
              :r-ran-g's-work-over-the-network (only (:result gw1) ["R" :net])
              :g-ran-r's-work-by-the-disk (only (:result rw) ["G" :window])
              :network-hello-refused (and (false? (get-in rw [:network-hello :ok]))
                                          (str/includes? (str (get-in rw [:network-hello :message])) "denied"))
              :every-swap-counted (and (some? (:n gc)) (= (+ (:n gc) (:n rc)) (:n cnt)))
              :seal-same (:seal-same gd)
              :value-same (:value-same gd)}
      ok (every? true? (vals checks))]
  (println (pr-str {:hosts [hg hr] :dir dir :ok ok :checks checks
                    :errors (vec (keep identity [(step ls :error) (step lg :error) (step lr :error)]))
                    :attach-ms (:attach-ms j)
                    :work-ms {:g->r-net (get-in gw1 [:result :ms]) :r->g-disk (get-in rw [:result :ms])}
                    :count {:disk (:n gc) :net (:n rc) :net-ms (:ms rc) :final (:n cnt)}
                    :gateway (:stats gd) :wall-ms (- (now) t0)}))
  (System/exit (if ok 0 1)))
