(ns contract.one-cljs
  "The compiled ClojureScript row of the DataScript shootout contract
   (shadow-cljs :node-script): the SAME durable shape contract.one opens on
   every host — a fresh persistent Eve domain, journaled by default, over
   the node mmap region (journal-file/node forces with fsync; the region's
   sync is a real msync). The mmap_cas addon is loaded explicitly and
   LOUDLY first: a persistent run without it must fail here, never fall
   back to a region nothing forces (js/require throws on a missing file;
   the loaded? check throws on anything else). Then the process slab (the
   nbb entry's launcher shape), then contract.one.

     EVE_MMAP_ADDON=<eve>/build/Release/mmap_cas.node node target/one.js <fresh-db-dir>
     ... --open-only <db-dir>   open/create + the empty DB, no transact (the journal proof)
     ... --readback <db-dir>    reopen + count the datoms + entity 1's :name, no transact"
  (:require [eve.region :as region]
            [eve.process-slab :as ps]
            [eve.atom :as atom]
            [eve.core :as e]
            [datascript.core :as d]
            [contract.one]))

(defn- open-only [dir]
  (let [dom (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! dom "contract/ds-cli-update-db" nil)]
    (when (nil? @a) (swap! a (fn [_] (d/empty-db))))
    (println (pr-str {:mark :OPEN-ONLY :datoms (e/count (d/datoms @a :eavt))}))))

(defn- readback [dir]
  (let [dom (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! dom "contract/ds-cli-update-db" nil)]
    (println (pr-str {:mark :READBACK
                      :datoms (if (nil? @a) :no-db (e/count (d/datoms @a :eavt)))
                      :name (when (some? @a) (:name (d/entity @a 1)))}))))

(defn main [& args]
  (let [addon-path (or (aget (.-env js/process) "EVE_MMAP_ADDON")
                       (str (.cwd js/process) "/build/Release/mmap_cas.node"))]
    (region/load-native-addon! (js/require addon-path))
    (when-not (region/native-addon-loaded?)
      (throw (ex-info "mmap_cas addon did not load" {:path addon-path})))
    (-> (ps/init! :force true)
        (.then (fn [_]
                 (let [[flag dir] args]
                   (cond (= "--open-only" flag) (open-only dir)
                         (= "--readback" flag) (readback dir)
                         :else (apply contract.one/-main args)))))
        (.catch (fn [e]
                  (println (pr-str {:mark :ONE-CLJS-ERROR :error (str e)}))
                  (js/process.exit 1))))))
