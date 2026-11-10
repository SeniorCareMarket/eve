(ns contract.one-db
  "contract.one's job WITHOUT datascript.core: datascript.db (the DB value,
   transact-tx-data) and datascript.conn (with / db-with over it) only, so
   none of the query machinery — query, query_v3, pull_api, pull_parser,
   parser, built_ins, serialize, impl.entity — is in the graph or
   initialized: a write-only job needs none of it. Same domain, same atom,
   same datum, same readback. Compiled as its own binary, it prices the
   query machinery's eager module init on jank AOT against the full graph."
  (:require [eve.atom :as atom] [datascript.db :as db] [datascript.conn :as conn] [eve.core :as e]))

(defn -main [& [dir]]
  (let [dom (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! dom "contract/ds-cli-update-db" nil)]
    (when (nil? @a) (swap! a (fn [_] (db/empty-db nil nil))))
    (swap! a (fn [d] (conn/db-with d [{:db/id 1 :name "one" :age 1}])))
    (println (pr-str {:mark :ONE-DONE :datoms (e/count (db/-datoms @a :eavt nil nil nil nil)) :graph :db+conn-only}))))
