(ns contract.contend-aot
  "AOT entry for the flagship contend driver on jank: the completion first,
   then contract.ds-contend's modes (init <dir> | worker <dir> <w> |
   readback <dir>), the process ending through eve.process/exit!.
   Several of these binaries on one shared disk domain is the jank seat's
   multi-process contention proof (the five-host flagship's scenario)."
  (:require [eve.host-completion]
            [eve.process :as proc]
            [contract.ds-contend]))

(defn -main [& args]
  (let [code (try (apply contract.ds-contend/-main args) 0
                  (catch jank.runtime.object_ref e
                    (println (pr-str {:mark :CONTEND-AOT-ERROR :error (ex-message e) :data (ex-data e)}))
                    1))]
    (proc/exit! code)))
