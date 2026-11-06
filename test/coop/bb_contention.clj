;; bb_contention.clj — the bb cross-process control: JVM threads (this process) and bb processes (workers/bb_worker.clj
;; contend) swap one eve mmap atom at once; eve's own coop.scenario invariants (violations) say whether every op landed.
;; bb is a lock-only host (eve.region jvm-nio-lock: FileChannel byte-range lock); eve.atom's exclusive hold makes each bb
;; write exclusive across processes. Before that hold (eve 10760f1, bb-lock) contention-bb-only failed 0 of 5. No Node,
;; nbb or wasm needed (contrast run.sh).
;;
;;   cd <eve> && java -cp "$(clojure -Spath):test" clojure.main test/coop/bb_contention.clj <eve-dir> <bb> <base> <jvm-threads> <bb-procs> <ops-per-writer>
;;
;; e.g. ... . bb /tmp/bbc-$(date +%s) 4 4 200 — prints MIXED {...}, SNAPSHOT {...}, RESULT PASS | RESULT FAIL [violations];
;; exits 0 on PASS, 1 on FAIL. Measured on release/eve-0.9.3 (bb 1.13.226, 2026-11-06): 2x2x40, 2x2x200, 4x4x200 (3 of 3),
;; 2x6x400, 0x2x40, 0x4x200 (3 of 3) all PASS.
(require '[eve.atom :as atom] '[coop.scenario :as sc] '[clojure.string :as str] '[clojure.edn :as edn])

(let [[eve bb base jt bp n] *command-line-args*
      jt (parse-long jt) bp (parse-long bp) n (parse-long n)
      hosts (edn/read-string (slurp (str eve "/hosts.edn")))
      bb-cp (str/join ":" (concat [(str eve "/src")] (map #(str eve "/" %) (:bb hosts)) [(str eve "/test")]))
      _ (let [d (atom/persistent-atom-domain base)
              a (atom/persistent-atom {:id :eve/main :persistent base} nil)]
          (reset! a sc/initial-contention)
          (atom/close-atom-domain! d))
      t0 (System/currentTimeMillis)
      sh (fn [cmd] (let [p (.start (ProcessBuilder. ^java.util.List cmd))
                         out (future (slurp (.getInputStream p)))
                         err (future (slurp (.getErrorStream p)))]
                     {:exit (.waitFor p) :out @out :err @err :cmd cmd}))
      futs (doall (concat
                   (for [t (range jt)]
                     (future (let [w (str "jvm" t)
                                   d (atom/persistent-atom-domain base)
                                   a (atom/persistent-atom {:id :eve/main :persistent base} nil)]
                               (dotimes [i n] (swap! a sc/contend-op w i))
                               (atom/close-atom-domain! d)
                               {:exit 0 :out "" :err "" :cmd [w]})))
                   (for [i (range bp)]
                     (future (sh [bb "--classpath" bb-cp (str eve "/test/coop/workers/bb_worker.clj")
                                  "contend" base (str "bb" i) (str n)])))))
      rs (mapv deref futs)
      ms (- (System/currentTimeMillis) t0)
      pw (merge (into {} (for [t (range jt)] [(str "jvm" t) n]))
                (into {} (for [i (range bp)] [(str "bb" i) n])))
      d (atom/persistent-atom-domain base)
      a (atom/persistent-atom {:id :eve/main :persistent base} nil)
      snap (sc/snapshot @a)
      bad (sc/violations snap (reduce + 0 (vals pw)) pw)]
  (atom/close-atom-domain! d)
  (doseq [r rs :when (not (zero? (:exit r)))]
    (println "WRITER FAILED" (:cmd r) "exit" (:exit r) (subs (str (:err r) (:out r)) 0 (min 2000 (count (str (:err r) (:out r)))))))
  (doseq [r rs :when (and (zero? (:exit r)) (seq (:out r)))] (println "bb writer" (last (butlast (:cmd r))) (str/trim (:out r))))
  (println "MIXED" {:jvm-threads jt :bb-procs bp :ops-per-writer n :total (reduce + 0 (vals pw)) :ms ms})
  (println "SNAPSHOT" (pr-str snap))
  (println (if (and (empty? bad) (every? #(zero? (:exit %)) rs)) "RESULT PASS" (str "RESULT FAIL " (pr-str bad))))
  (shutdown-agents)
  (System/exit (if (and (empty? bad) (every? #(zero? (:exit %)) rs)) 0 1)))
