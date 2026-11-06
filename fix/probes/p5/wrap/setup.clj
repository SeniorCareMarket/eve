(ns wrap.setup)
(defonce orig-deftype @#'clojure.core/deftype)
(def ^:private piecemeal-ifaces '#{clojure.lang.ILookup clojure.lang.Associative ILookup Associative})
(def ^:private map-iface 'clojure.lang.IPersistentMap)
(defmacro deftype-presenter [type-name fields & opts]
  (let [ifaces (take-while symbol? opts)
        more   (drop-while symbol? opts)]
     (if (and (some piecemeal-ifaces ifaces) (not (some #(= map-iface %) ifaces)))
      (apply orig-deftype type-name fields (concat ifaces [map-iface] more))
      (apply orig-deftype type-name fields opts))))
(alter-var-root #'clojure.core/deftype (fn [_] @#'wrap.setup/deftype-presenter))
(println :setup-installed)
