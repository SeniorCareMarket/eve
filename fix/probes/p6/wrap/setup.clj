(ns wrap.setup)
(defonce orig-deftype @#'clojure.core/deftype)
;; presenter: installed on clojure.core/deftype; receives plain args.
(defn presenter [& args]
  (println :presenter-got (count args) (pr-str (take 2 args)))
  (let [[type-name fields & opts] args
        ifaces (take-while symbol? opts)
        more   (drop-while symbol? opts)
        opts'  (if (and (some '#{clojure.lang.ILookup clojure.lang.Associative} ifaces)
                        (not (some '#{clojure.lang.IPersistentMap} ifaces)))
                 (concat ifaces '[clojure.lang.IPersistentMap] more)
                 opts)]
    (list 'wrap.setup/finish (list 'quote type-name) (list 'quote fields) (list 'quote opts'))))
(alter-var-root #'clojure.core/deftype (fn [_] wrap.setup/presenter))
;; finish: a normal defmacro — receives the caller's &form/&env; calls orig.
(defmacro finish [type-name fields opts]
  (println :finish-env-ns (:ns &env))
  (let [form (list* 'deftype type-name fields opts)]
    (apply orig-deftype form &env type-name fields opts)))
(println :setup-installed)
