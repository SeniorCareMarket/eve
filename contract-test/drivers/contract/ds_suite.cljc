(ns contract.ds-suite
  "Runs DataScript's own upstream test suite (upstream-ds/test, tag
   1.8.1, UNCHANGED) against the port on the JVM seat. Per-namespace
   require + run with throwables caught per namespace, so one bad load
   does not end the row."
  (:require [eve.host] [eve.atom] [clojure.test :as t]))

(def suite-nss
  '[datascript.test.components datascript.test.conn datascript.test.core
    datascript.test.datafy datascript.test.db datascript.test.entity
    datascript.test.explode datascript.test.filter datascript.test.ident
    datascript.test.index datascript.test.issues datascript.test.listen
    datascript.test.lookup-refs datascript.test.lru datascript.test.parser
    datascript.test.parser-find datascript.test.parser-query
    datascript.test.parser-return-map datascript.test.parser-rules
    datascript.test.parser-where datascript.test.pull-api
    datascript.test.pull-parser datascript.test.query
    datascript.test.query-aggregates datascript.test.query-find-specs
    datascript.test.query-fns datascript.test.query-not
    datascript.test.query-or datascript.test.query-pull
    datascript.test.query-return-map datascript.test.query-rules
    datascript.test.query-v3 datascript.test.serialize
    datascript.test.storage datascript.test.transact
    datascript.test.tuples datascript.test.upsert
    datascript.test.validation])

(defn -main [& _]
  (doseq [ns-sym suite-nss]
    (try
      (require ns-sym)
      (let [res (t/run-tests ns-sym)]
        (println {:suite-ns ns-sym :result res}))
      (catch Throwable e
        (println {:suite-ns ns-sym :threw (str (type e) " " (.getMessage e))}))))
  (println {:suite :done})
  (System/exit 0))
