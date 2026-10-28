;; fold on a group of processes (lane ET-4): bb scripts/thread-fold-rendezvous.clj <host-a> <host-b> [base]
;;
;; Run from eve/. A fresh disk domain; setup (host a) makes the rendezvous atom and records its seal; members a and b
;; (each its host's own runner) start at the same instant, attach to the rendezvous atom's meta-atom and serve one
;; domain pool; a folds over it, then b (eve.thread-fold-rendezvous: a gated fold whose chunks take both members'
;; threads, a fold, an =>>, a measured fold); verify (host b, a fresh process) reads the rendezvous atom's seal from
;; disk. The members' EDN lines are the receipts; this script checks them and prints one summary line.
;; Hosts: jvm bb nbb clje cljr lpy jolt.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn roots [host]
  (map (fn [r] (str (fs/absolutize r))) (concat ["src"] (get (edn/read-string (slurp "hosts.edn")) host) ["test"])))

(def lpy-cache (str (fs/create-temp-dir {:prefix "eve-fold-rendezvous-lpycache-"})))

(defn cmd [host & args]
  (let [m "eve.thread-fold-rendezvous"]
    (case host
      "jvm" (concat ["clojure" "-Sdeps" "{:aliases {:rv {:extra-paths [\"test\"]}}}" "-M:rv" "-m" m] args)
      "bb" (concat ["bb" "-cp" (str/join ":" (roots :bb)) "-m" m] args)
      "nbb" (concat ["nbb" "--classpath" (str/join ":" (roots :nbb)) "-m" m] args)
      "clje" (concat ["clojerl"] (mapcat (fn [r] ["-pa" r]) (roots :clje)) ["-m" m] args)
      "cljr" (concat ["env" (str "CLOJURE_LOAD_PATH=" (str/join ":" (roots :cljr))) "cljr" "-m" m] args)
      ;; (compiled afresh, as bb test:lpy does: basilisp's cache checks a namespace's own source, not its macros')
      "lpy" (concat ["env" (str "PYTHONPYCACHEPREFIX=" lpy-cache) "basilisp" "run"]
                    (mapcat (fn [r] ["-p" r]) (reverse (roots :lpy))) ["-n" m] args)
      "jolt" (concat ["jolt" "-Scp" (str/join ":" (roots :jolt)) "-m" m] args))))

(defn lines [f]
  (keep (fn [l] (when (and (str/starts-with? l "{") (str/includes? l ":step "))
                  (let [m (edn/read-string {:default (fn [_ v] v)} l)] (when (:step m) m))))
        (str/split-lines (if (fs/exists? f) (slurp f) ""))))

(defn step [f s] (first (filter (fn [m] (= s (:step m))) (lines f))))

(defn now [] (System/currentTimeMillis))

(let [[ha hb base-arg] *command-line-args*
      dir (str (fs/create-temp-dir {:prefix "eve-fold-rendezvous-"}))
      base (or base-arg (str dir "/d"))
      la (str dir "/a.log") lb (str dir "/b.log") ls (str dir "/setup.log") lv (str dir "/verify.log")
      t0 (now)
      _ (apply p/shell {:out ls :err (str ls ".err")} (apply cmd ha ["setup" base]))
      setup (step ls :setup)
      pa (apply p/process {:out la :err (str la ".err")} (apply cmd ha ["member" base "a"]))
      pb (apply p/process {:out lb :err (str lb ".err")} (apply cmd hb ["member" base "b"]))
      ea (:exit (deref pa 1800000 {:exit :timeout}))
      eb (:exit (deref pb 1800000 {:exit :timeout}))
      _ (apply p/shell {:out lv :err (str lv ".err")} (apply cmd hb ["verify" base]))
      fa (step la :fold) fb (step lb :fold)
      da (step la :done) db (step lb :done)
      vf (step lv :verify)
      both (fn [f] (= #{"a" "b"} (set (keys (:ran-by f)))))
      checks {:setup-value (:value-ok setup)
              :members-exited [ea eb]
              ;; (under the gate: the first four chunks of a turn run at once — both members' two threads)
              :a's-fold-ran-in-both (both fa)
              :b's-fold-ran-in-both (both fb)
              :counts (and (:count-ok fa) (:count-ok fb))
              :fold-values (and (:fold-ok fa) (:fold-ok fb))
              :=>>-values (and (:=>>-ok fa) (:=>>-ok fb))
              :measured-values (and (:measured-ok fa) (:measured-ok fb))
              :plans-sized (and (:plan-sized fa) (:plan-sized fb))
              ;; each member measured its meta-atom swaps at its first fold and published them; b, folding second,
              ;; sized its chunks from the slower of the two
              :a-published (pos? (or (get (:published fa) "a") 0))
              :both-published (and (pos? (or (get (:published fb) "a") 0)) (pos? (or (get (:published fb) "b") 0)))
              :b-sized-from-the-slowest (= (:swap-us (:plan fb)) (max (get (:published fb) "a") (get (:published fb) "b")))
              :seal-same-in-members (and (:seal-same da) (:seal-same db) (:value-same da) (:value-same db))
              :seal-same-on-disk (and (:same vf) (= (:seal setup) (:seal vf)) (:value-same vf))}
      ok (every? true? (vals (assoc checks :members-exited (= [0 0] [ea eb]))))]
  (println (pr-str {:hosts [ha hb] :dir dir :ok ok :checks checks
                    :errors (vec (keep identity [(step la :error) (step lb :error) (step lv :error)]))
                    :ran-by {:a (:ran-by fa) :b (:ran-by fb)}
                    :plans {:a (:plan fa) :b (:plan fb)} :published (:published fb)
                    :seal-before (:seal setup) :seal-after-disk (:seal vf)
                    :wall-ms (- (now) t0)}))
  (System/exit (if ok 0 1)))
