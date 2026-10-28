(ns eve-media.supervisor
  "The run's supervisor on the JVM — when there is no BEAM to supervise (clojerl not installed, or it did not start):
   the same children and the same policy as clojerl's OTP supervisor (members/clje), written out. A thread per child
   starts its OS process (/bin/sh exec'ing the child's command, its output appended to the run's logs), waits for its
   end, and starts it again when it exited other than cleanly and the run is not stopping — at most
   eve-media.supervise/intensity times in its period, after which the child is given up on. The main thread takes the
   coordinator's restart asks (killing the child's process: its thread starts it again) and, on the run's stop, lets
   the members end, kills what is left (the browser), and exits.

     java -cp <classpath> clojure.main -m eve-media.supervisor <base>

   It is no runtime of the job atom and runs no jobs."
  (:require [clojure.java.io :as io]
            [eve.process :as proc]
            [eve-media.domain :as domain]
            [eve-media.supervise :as sv])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect ProcessHandle]))

(defn- log [& xs] (locking *out* (println (pr-str (vec xs))) (flush)))

(defn- kill-9! [pid]
  (when pid
    (when-let [h (.orElse (ProcessHandle/of (long pid)) nil)]
      (.destroyForcibly ^ProcessHandle h))))

(defn- start-child!
  "Child rid's OS process, its start n."
  ^Process [rid child n]
  (let [out (sv/log-path child rid n)
        pb (ProcessBuilder. ^java.util.List (into ["/bin/sh" "-c" (sv/sh-script)] (map str (:cmd child))))]
    (.directory pb (io/file (:dir child)))
    (doto (.environment pb)
      (.putAll (into {} (map (fn [[k v]] [(str k) (str v)]) (:env child))))
      (.put "EVE_LOG" out)
      (.put "EVE_ERR" (str out ".err")))
    (.redirectInput pb ProcessBuilder$Redirect/INHERIT)
    (.start pb)))

(defn- run-child!
  "Child rid's thread: wait for its servers, then start it and start it again while the policy allows."
  [control rid child]
  (loop []
    (when-not (or (sv/stopping? control) (sv/ready? control child))
      (Thread/sleep 300)
      (recur)))
  (loop []
    (when-not (sv/stopping? control)
      (let [n (sv/next-start control rid)
            p (start-child! rid child n)]
        (sv/started! control rid child (.pid p) n (proc/now-ms))
        (log :started rid (.pid p) n)
        (let [code (.waitFor p)
              now (proc/now-ms)]
          (sv/exited! control rid code now)
          (log :exited rid code)
          (cond
            (or (= 0 code) (sv/stopping? control)) nil
            (sv/restart? (get-in (sv/value control) [:members rid :exits]) now) (recur)
            :else (do (log :gave-up rid) (sv/gave-up! control rid now))))))))

(defn -main [base]
  (let [{:keys [control]} (domain/open-atoms base)
        children (sv/children control)]
    (sv/up! control :jvm (.pid (ProcessHandle/current)) (proc/now-ms))
    (log :supervising (vec (sort (keys children))))
    (doseq [[rid child] children]
      (doto (Thread. ^Runnable (fn [] (run-child! control rid child)) (str "eve-media-child-" rid))
        (.setDaemon true)
        (.start)))
    (loop []
      (if (sv/stopping? control)
        (let [t0 (proc/now-ms)
              running (fn [] (keep (fn [[rid m]] (when (= :running (:state m)) [rid (:pid m) (:host m)]))
                                   (:members (sv/value control))))]
          ;; the members end on their own (they read :stop); the browser does not (Chromium): killed with what is left
          (loop []
            (when (and (some (fn [[_ _ h]] (not= :browser h)) (running)) (< (- (proc/now-ms) t0) 20000))
              (Thread/sleep 300)
              (recur)))
          (doseq [[rid pid] (running)]
            (log :killed-at-stop rid pid)
            (kill-9! pid))
          (log :stopped)
          (shutdown-agents)
          (System/exit 0))
        (do
          ;; the coordinator's asks: the child's process killed, its thread starts it again
          (doseq [[rid pid] (sv/asked control)]
            (log :restart-asked rid pid)
            (sv/restarting! control rid (proc/now-ms))
            (kill-9! pid))
          (Thread/sleep 300)
          (recur))))))
