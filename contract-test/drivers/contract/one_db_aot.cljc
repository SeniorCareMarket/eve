(ns contract.one-db-aot
  "AOT entry for contract.one-db (the write-only job over datascript.db
   alone): the completion first, the job, exit through eve.process/exit!."
  (:require [eve.host-completion] [eve.process :as proc] [contract.one-db]))

(defn -main [& args]
  (let [code (try (apply contract.one-db/-main args) 0
                  (catch jank.runtime.object_ref e
                    (println (pr-str {:mark :ONE-DB-ERROR :error (ex-message e)})) 1))]
    (proc/exit! code)))
