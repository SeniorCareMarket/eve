(ns contract.flagship-main
  "Flagship entry point for the seats whose completion/setup must load
   before the driver (the eve.runner.<host>-main pattern, after
   contract.jank-main / contract.nbb-main):
     jank --module-path <eve :jank roots>:<lib>:<lib-ds>:<drivers> run-main contract.flagship-main -- <mode> <dir> [w]
     nbb  --classpath <eve :nbb roots>:<lib>:<lib-ds>:<drivers> -m contract.flagship-main <mode> <dir> [w]
     jolt run -m contract.flagship-main <mode> <dir> [w]  (deps.edn carries the :jolt roots)
   On the JVM/bb seats the driver is run directly (contract.ds-flagship)."
  (:require
   #?@(:jank [[eve.host-completion] [eve.process :as proc]]
       :cljs [[eve.process-slab :as ps]])
   [contract.ds-flagship :as flagship]
   [contract.ds-window :as window]))

(defn -main [& args]
  ;; fs3run8 window modes (seed/go/window/counters) dispatch to
  ;; contract.ds-window; the classic rounds stay on contract.ds-flagship.
  (let [run (fn [] (if (#{"seed" "go" "window" "counters"} (first args))
                     (apply window/-main args)
                     (apply flagship/-main args)))]
    #?(:cljs (-> (ps/init! :force true)
                 (.then (fn [_] (run))))
       :default (do (run)
                    #?(:jank (proc/exit! 0) :default nil)))))
