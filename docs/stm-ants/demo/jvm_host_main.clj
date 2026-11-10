;; jvm_host_main.clj — the JVM seat's ant-HOST program for the
;; packaged demo (docs/stm-ants/demo/run.cljc).
;;
;; WHY THIS FILE EXISTS: the stm-ants tree ships ant-host mains for
;; bb, jolt, nbb, glj, lpy and cljr — but for the JVM seat it ships
;; only the DRIVER (hosts/jvm/ants_driver.clj), because in the
;; lane's M1/M2 runs the JVM seat was always the driver and never
;; needed a second process. The demo's elastic dealing can assign
;; the JVM seat more than the driver's one process (on the 144
;; board, 12 column-group processes are dealt across the joined
;; members; with 5 members joined the JVM's share is 3), so the
;; demo carries this host main for the JVM seat's extra processes.
;; It is modeled line-for-line on hosts/bb/ants_bb_main.clj — same
;; await/build/place/behave flow over the same ants.cljc API, same
;; READY/DONE log lines — with the JVM seat's own line prefixes.
;; ants.cljc itself is untouched.
;;
;; args: jvm_host_main.clj <ants-config.edn>
;;   config {:root <world-root> :dim n :ants n :seconds n}
(ns ants-demo-jvm-host-main
  (:require [clojure.edn :as edn]
            [eve.atom :as ea]
            [ants :as ants]))

(defn -main [& args]
  (let [cfg (edn/read-string (slurp (or (first args) (first *command-line-args*))))
        {:keys [root dim ants seconds]} cfg
        deadline (+ (System/currentTimeMillis) (* 1000 (long seconds)))
        errors (atom [])]
    (try
      (ants/await-built! root)
      (let [w (ants/open-world root dim)]
        (ants/await-ready! w)
        (let [mine (ants/place-ants! w ants)]
          (println "JVM-ANT-HOST-READY tag=" (:tag w) "ants=" mine)
          (flush)
          (loop [ids (vec mine)]
            (when (< (System/currentTimeMillis) deadline)
              (let [r (try
                        (ants/behave-once! w ids)
                        (catch Throwable t
                          (swap! errors conj
                                 (str (.getName (class t)) ": " (ex-message t)))
                          ids))]
                (when (and (empty? @errors) (seq r)) (recur (vec r))))))
          (ea/sync! (:clock-atom w))
          (println "JVM-ANT-HOST-DONE tag=" (:tag w) "errors=" @errors)
          (flush)))
      (catch Throwable t
        (println "JVM-ANT-HOST-DIED" (.getName (class t)) (ex-message t))
        (flush)
        (System/exit 1)))
    (System/exit 0)))

(-main)
