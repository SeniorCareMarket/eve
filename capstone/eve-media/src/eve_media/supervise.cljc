(ns eve-media.supervise
  "The supervisor's notes in the control atom, whichever runtime supervises — clojerl's OTP supervisor
   (members/clje/src/eve_media/supervisor.clje), or the JVM's when there is no BEAM
   (members/jvm/src/eve_media/supervisor.clj): the children the launcher wrote (control :children), each one's starts
   and exits (control :members), the restarts the coordinator asks for, the stop. The restart policy is the
   supervisor's own — OTP's on the BEAM, the same policy written out on the JVM: a child that exits other than cleanly
   is started again, at most `intensity` times in `period-ms`, after which it is given up on.

   A child (control :children rid):
     {:host     :jvm :bb :node :glj :jolt :browser :gateway
      :cmd      [program arg ...]   :env {name value}   :dir working directory
      :logs     the run's log directory (its stdout <rid>.log, its stderr <rid>.log.err; <rid>.<n>.log from its 2nd start)
      :wait     [server ...]        started once control :servers holds each (:gateway, :media)
      :member?  a runtime of the job atom (the checks look for it in the registry and in :ran-on)}

   Portable cljc over eve-media.domain; no host is named here."
  (:require [eve-media.domain :as domain]))

(def intensity 5)
(def period-ms 60000)

(defn value [control] (domain/read-atom control))

(defn children [control] (:children (value control)))

(defn stopping? [control] (boolean (:stop (value control))))

(defn ready?
  "Are the servers child waits for up (control :servers)?"
  [control child]
  (let [servers (:servers (value control))]
    (every? (fn [s] (contains? servers s)) (:wait child))))

(defn log-path
  "Child rid's stdout for its start n (its stderr: the same path with .err): <logs>/<rid>.log, then <rid>.<n>.log."
  [child rid n]
  (str (:logs child) "/" rid (when (> n 1) (str "." n)) ".log"))

(defn next-start
  "The number child rid's next start will have (1 for its first)."
  [control rid]
  (inc (get-in (value control) [:members rid :incarnation] 0)))

(defn started!
  "Child rid started as OS process pid, its start n."
  [control rid child pid n now]
  (domain/swap-atom! control update-in [:members rid]
                     (fn [m] (merge m {:host (:host child) :member? (boolean (:member? child)) :state :running
                                       :pid pid :started now :incarnation n :joined false}))))

(defn exited!
  "Child rid's process ended with exit code code: :stopped when cleanly (0), else :exited."
  [control rid code now]
  (domain/swap-atom! control update-in [:members rid]
                     (fn [m] (-> m
                                 (assoc :state (if (= 0 code) :stopped :exited) :code code)
                                 (update :exits (fnil conj []) {:code code :at now :incarnation (:incarnation m)})))))

(defn gave-up!
  "Child rid exited more than intensity times in period-ms: not started again."
  [control rid now]
  (domain/swap-atom! control update-in [:members rid] assoc :state :gave-up :gave-up-at now))

(defn restart?
  "May a child that has just exited (its exits so far, this one included) be started again at now?"
  [exits now]
  (<= (count (filter (fn [e] (> (:at e) (- now period-ms))) exits)) intensity))

(defn asked
  "The children the coordinator asked to restart (a member whose lease lapsed while its process lives): [rid pid]."
  [control]
  (keep (fn [[rid m]] (when (= :restart-asked (:state m)) [rid (:pid m)])) (:members (value control))))

(defn restarting!
  "Child rid's restart ask taken: its process is being killed, and its supervisor starts it again."
  [control rid now]
  (domain/swap-atom! control (fn [v] (if (= :restart-asked (get-in v [:members rid :state]))
                                       (-> v
                                           (assoc-in [:members rid :state] :restarting)
                                           (assoc-in [:members rid :restart-taken] now))
                                       v))))

(defn up!
  "The supervisor (on host, OS process pid) is running: the launcher waits for this note."
  [control host pid now]
  (domain/swap-atom! control assoc :supervisor {:host host :pid pid :started now}))

(defn sh-script
  "The shell line a child runs through (/bin/sh -c line program arg ...): the program exec'd in place of the shell,
   its stdin /dev/null, its stdout and stderr appended to $EVE_LOG and $EVE_ERR — so the child's OS process is the
   program's own, and killing it kills the program."
  []
  "exec \"$0\" \"$@\" </dev/null >>\"$EVE_LOG\" 2>>\"$EVE_ERR\"")
