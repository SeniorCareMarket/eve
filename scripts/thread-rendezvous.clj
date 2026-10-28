;; The two-process rendezvous (lane ET-2): bb scripts/thread-rendezvous.clj <host-a> <host-b> [base]
;;
;; Run from eve/. A fresh disk domain; setup (host a) makes the rendezvous atom and records its seal; members a and b
;; (each its host's own runner) start at the same instant, attach to the rendezvous atom's meta-atom, read the one
;; registry, submit work to one pool and claim each other's; b's threads take what a submits to pool :held and hold it,
;; and this launcher kill -9's b; a adopts what b held once b's leases expire; verify (host b, a fresh process) reads
;; the rendezvous atom's seal from disk. The members' EDN lines are the receipts; this script checks them and prints
;; one summary line. Hosts: jvm bb nbb clje cljr lpy jolt glj (glj-eve, built first into target/glj-eve).
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(defn roots [host]
  (map (fn [r] (str (fs/absolutize r))) (concat ["src"] (get (edn/read-string (slurp "hosts.edn")) host) ["test"])))

(def lpy-cache (str (fs/create-temp-dir {:prefix "eve-rendezvous-lpycache-"})))

(defn cmd [host & args]
  (let [m "eve.thread-rendezvous"]
    (case host
      "jvm" (concat ["clojure" "-Sdeps" "{:aliases {:rv {:extra-paths [\"test\"]}}}" "-M:rv" "-m" m] args)
      "bb" (concat ["bb" "-cp" (str/join ":" (roots :bb)) "-m" m] args)
      "nbb" (concat ["nbb" "--classpath" (str/join ":" (roots :nbb)) "-m" m] args)
      "clje" (concat ["clojerl"] (mapcat (fn [r] ["-pa" r]) (roots :clje)) ["-m" m] args)
      "cljr" (concat ["env" (str "CLOJURE_LOAD_PATH=" (str/join ":" (roots :cljr))) "cljr" "-m" m] args)
      ;; (compiled afresh, as bb test:lpy does: basilisp's cache checks a namespace's own source, not its macros')
      "lpy" (concat ["env" (str "PYTHONPYCACHEPREFIX=" lpy-cache) "basilisp" "run"]
                    (mapcat (fn [r] ["-p" r]) (reverse (roots :lpy))) ["-n" m] args)
      "jolt" (concat ["jolt" "-Scp" (str/join ":" (roots :jolt)) "-m" m] args)
      ;; (glj has no -m: the arguments after -e are *command-line-args*, so the kill's pattern finds them)
      "glj" (concat ["env" (str "GLJ_CLASSPATH=" (str/join ":" (roots :glj))) (str (fs/absolutize "target/glj-eve/glj-eve"))
                     "-e" (str "(require 'eve.host-completion) (require '" m ") (apply " m "/-main *command-line-args*)")]
                    args))))

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

(let [[ha hb base-arg] *command-line-args*
      _ (when (some #{"glj"} [ha hb]) (p/shell "bash" "impls/glojure/build.sh" (str (fs/absolutize "target/glj-eve"))))
      dir (str (fs/create-temp-dir {:prefix "eve-rendezvous-"}))
      base (or base-arg (str dir "/d"))
      la (str dir "/a.log") lb (str dir "/b.log") ls (str dir "/setup.log") lv (str dir "/verify.log")
      t0 (now)
      _ (apply p/shell {:out ls :err (str ls ".err")} (apply cmd ha ["setup" base]))
      setup (step ls :setup)
      t-start (now)
      pa (apply p/process {:out la :err (str la ".err")} (apply cmd ha ["member" base "a" "a"]))
      pb (apply p/process {:out lb :err (str lb ".err")} (apply cmd hb ["member" base "b" "b"]))
      holding (wait-for 900000 "b holding" (fn [] (or (step lb :holding) (when (step lb :error) (throw (ex-info "b failed" (step lb :error)))))))
      pid (.pid (:proc pb))
      t-kill (now)
      ;; every process of member b (its command line names this run's base): the runner and anything it started
      pat (str "member " base " b b")
      before (str/trim (:out (p/shell {:out :string :continue true} "pgrep" "-f" pat)))
      _ (p/shell {:continue true} "pkill" "-9" "-f" pat)
      _ (Thread/sleep 300)
      after (str/trim (:out (p/shell {:out :string :continue true} "pgrep" "-f" pat)))
      _ (deref pa 900000 nil)
      _ (apply p/shell {:out lv :err (str lv ".err")} (apply cmd hb ["verify" base]))
      ra (step la :registry) rb (step lb :registry)
      wa (step la :work) wb (step lb :work)
      ca (step la :cross) cb (step lb :cross)
      sv (step la :survived)
      vf (step lv :verify)
      [r1 nv bl q1 q2] (:descriptors sv)
      checks {:setup-value (:value-ok setup)
              :one-registry (and (some? (:registry ra)) (= (:registry ra) (:registry rb))
                                 (= #{"a" "b"} (set (keys (:runtimes (:registry ra))))))
              ;; (under the gate: four of one member's held at once take both members' threads)
              :a-claimed-b's-work (pos? (get (:ran-by cb) "a" 0))
              :b-claimed-a's-work (pos? (get (:ran-by ca) "b" 0))
              :work-values (and (:ok wa) (:ok wb) (:ok ca) (:ok cb))
              :b-held-three (= ["b" "b" "b"] (mapv :orid (take 3 (:states holding))))
              :b-held-one-blocked (= :blocked (:state (nth (:states holding) 2)))
              :restart-adopted (and (= :done (:state r1)) (= 2 (:attempts r1)) (= "a" (:orid r1))
                                    (= {:v :restart-1 :ran "a"} (nth (:outcomes sv) 0)))
              :never-owner-died (and (= :failed (:state nv)) (= "eve.thread/owner-died" (:error nv))
                                     (str/starts-with? (str (:owner-died nv)) "b#"))
              :blocked-restarted (and (= :done (:state bl)) (= 2 (:attempts bl)) (= "a" (:orid bl))
                                      (= {:v :cancelled :ran "a"} (nth (:outcomes sv) 2)))
              ;; (b's blocked thread helps while it waits — runs queued work — so either may have started one of these)
              :queued-work-done (= [:done :done] [(:state q1) (:state q2)])
              :adopted-after-kill (and (some? (:t-adopt sv)) (> (:t-adopt sv) t-kill))
              :b-gone-from-registry (= ["a"] (:runtimes sv))
              :b-dead (str/blank? after)
              :seal-same-in-a (:seal-same sv)
              :value-same-in-a (:value-same sv)
              :seal-same-on-disk (and (:same vf) (= (:seal setup) (:seal vf)))
              :value-same-on-disk (:value-same vf)}
      ok (every? true? (vals checks))]
  (println (pr-str {:hosts [ha hb] :dir dir :ok ok :checks checks
                    :errors (vec (keep identity [(step la :error) (step lb :error) (step lv :error)]))
                    :killed {:pid pid :pids-before before :pids-after after}
                    :ttl-ms 1500 :kill-to-adopt-ms (when (:t-adopt sv) (- (:t-adopt sv) t-kill))
                    :ran-by {:a (:ran-by wa) :b (:ran-by wb)} :cross {:a (:ran-by ca) :b (:ran-by cb)}
                    :b-holding (:states holding)
                    :descriptors (:descriptors sv) :outcomes (:outcomes sv)
                    :seal-before (:seal setup) :seal-after-a (:seal sv) :seal-after-disk (:seal vf)
                    :wall-ms (- (now) t0)}))
  (System/exit (if ok 0 1)))
