;; cohost-media's boot entry — the thinnest host entry: it installs this seat's cohost adapter
;; (cohost.hosts.auto, which reads as whichever host is reading) and calls cohost-media.start,
;; the boot program itself (portable cljc over cohost.thread). This namespace is the run's only
;; main, on the primary sister.
;;
;;   bb cohost-media demo [--hosts bb,jvm,...] [--kill transcode|coordinator]   (from eve/)
;;   bb cohost-media scan <dir> [--hosts ...]     the library of a folder of your own
;;   bb cohost-media verify                        the done bar: the runs of doc README §"The done bar", compared
(ns cohost-media.boot
  "The boot program's host entry: cohost-media.start is the program; this namespace installs
   the seat's adapter and exits with start/dispatch's answer."
  (:require [cohost.hosts.auto]
            [cohost-media.start :as start]))

#?(:clj
   (defn -main [& [cmd & args]]
     (try
       (System/exit (start/dispatch cmd args))
       ;; what this machine lacks: the message alone, no stack trace
       (catch clojure.lang.ExceptionInfo e
         (if (let [d (ex-data e)] (and (map? d) (or (:unready d) (:missing d))))
           (do (binding [*out* *err*] (println (ex-message e))) (System/exit 1))
           (throw e)))))
   :cljs
   (defn -main [& args]
     (js/process.exit (start/dispatch (first args) (rest args)))))
