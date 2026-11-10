;; ants_driver.clj — the M1 driver process (JVM seat).
;; Builds the world, places its own ants, runs the evaporator and
;; its ants on futures, and renders + audits transactional
;; snapshots for the run's duration. Other host processes join the
;; same world dir and drive their own ants concurrently.
;;
;; Args: <world-root> <dim> <ant-count> <seconds> [expect-total]
;; Run from docs/stm-ants/hosts/jvm (deps.edn there):
;;   clojure -M <this-file> <root> <dim> <ants> <seconds> [expect]
;; With expect-total, the timed audit window starts only once the
;; shared registry holds that many ants (all hosts in; 10-minute
;; deadline) — slow seats take minutes to open a world and place.
;; Exit 0 iff every audit's per-snapshot invariants held, the final
;; audit is also registry-exact, and no ant loop caught an error.

(require '[ants]
         '[eve.atom :as ea]
         '[eve.stm :as stm])

(let [[root dim-s ants-s secs-s expect-s] *command-line-args*
      dim (Long/parseLong dim-s)
      n-ants (Long/parseLong ants-s)
      secs (Long/parseLong secs-s)
      expect (when expect-s (Long/parseLong expect-s))
      w (ants/open-world root dim)
      _ (ants/build! w)
      ids (ants/place-ants! w n-ants)
      errors (atom []) traced (atom false)
      bad-audits (atom 0)
      stop (atom false)
      ant-futs (mapv (fn [id]
                       (future
                         (loop []
                           (when-not @stop
                             (try (ants/behave-once! w id)
                                  (catch Throwable t
                                    (swap! errors conj (str id " " (.getName (class t)) " " (.getMessage t))) (when (compare-and-set! traced false true) (.printStackTrace t) (try (let [cells (ants/snapshot w) at (first (filter (fn [i] (contains? (:ants (nth cells i)) id)) (range (count cells))))] (println "SPIKE-DUMP ant=" id "cached-loc=" (get @(:locs w) id) "snapshot-finds-at=" at "cached-cell=" (nth cells (get @(:locs w) id)))) (catch Throwable t2 (println "SPIKE-DUMP failed" t2))) (flush))))
                             (ants/sleep! (+ 40 (rand-int 80)))
                             (recur)))))
                     ids)
      evap (future
             (loop []
               (when-not @stop
                 (try (ants/evaporate-pass! w 0.99)
                      (catch Throwable t
                        (swap! errors conj (str "evaporator " (.getName (class t)) " " (.getMessage t)))))
                 (ants/sleep! 1000)
                 (recur))))]
  (println "DRIVER-READY tag=" (:tag w) "ants=" ids)
  (flush)
  (when expect
    (let [deadline (+ (System/currentTimeMillis) 600000)]
      (loop []
        (when (and (< (count @(:registry w)) expect)
                   (< (System/currentTimeMillis) deadline))
          (ants/sleep! 2000)
          (recur)))
      (println "DRIVER-ALL-IN registered=" (count @(:registry w))
               "expect=" expect)
      (flush)))
  (let [t0 (System/currentTimeMillis)]
  (loop []
    (let [cells (ants/snapshot w)
          a (ants/audit w cells)]
      (when-not (and (:ants-ok? a) (:food-ok? a))
        (swap! bad-audits inc))
      (println (ants/render dim cells))
      (println (str "AUDIT ants-ok=" (:ants-ok? a)
                    " food-ok=" (:food-ok? a)
                    " ants=" (:ant-count a) "/" (:registered a)
                    " food=" (:food-sum a) "+" (:carrying a) "/" (:food-total a)
                    " clock=" (:clock a)
                    " violations=" (:violations a)
                    " transients=" (:transients a)))
      (flush))
    (when (< (- (System/currentTimeMillis) t0) (* secs 1000))
      (ants/sleep! 2000)
      (recur)))
  (reset! stop true)
  (Thread/sleep 500)
  ;; one explicit durability barrier at shutdown (the world runs
  ;; :durable? false — the group-commit shape)
  (ea/sync! (:clock w))
  (doseq [r (:refs w)] (ea/sync! (:cell r)))
  (let [cells (ants/snapshot w)
        a (ants/audit w cells)]
    (println "FINAL-AUDIT" a)
    (println "DRIVER-STATS" (stm/stats) "bad-audits=" @bad-audits "errors=" @errors)
    (flush)
    (System/exit (if (and (:ants-ok? a) (:registered-ok? a) (:food-ok? a)
                          (zero? @bad-audits) (empty? @errors))
                   0 3)))))
