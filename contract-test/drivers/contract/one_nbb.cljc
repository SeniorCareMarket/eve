(ns contract.one-nbb
  "nbb entry for the DataScript shootout contract — the SAME durable shape
   contract.one opens on every host (a fresh persistent Eve domain, journaled
   by default on eve.jlog, over the node mmap region):
   the process slab first (the nbb seat's launcher pattern, as
   contract.flagship-main / contract.ds-nbb-main), then contract.one.

     cd eve && nbb --classpath <eve :nbb roots>:<lib>:<lib-ds>:<lib-ds-deps>:<drivers> -m contract.one-nbb <fresh-db-dir>"
  (:require #?(:cljs [eve.process-slab :as ps])
            [contract.one]))

(defn -main [& args]
  #?(:cljs (-> (ps/init! :force true)
               (.then (fn [_] (apply contract.one/-main args))))
     :default (apply contract.one/-main args)))
