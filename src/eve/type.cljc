(ns eve.type
  "Portable identity for Eve types. Tokens are qualified keywords, not host
   class names. Use (token parser/Variable), (instance? token value), and
   (type-token value) for condp or case dispatch. No dummy allocation needed."
  #?(:cljs (:require-macros [eve.type]))
  (:refer-clojure :exclude [instance?])
  (:require [eve.deftype.registry :as registry]))

(defn type-token
  "The existing registry's qualified type key as a keyword, or nil.
   This namespace owns no state."
  [value]
  (when-let [k (registry/type-key-of value)] (keyword k)))

(defn instance-of?
  "Test an Eve token or exact native type token. Eve tokens work on every target."
  [token value]
  (if (keyword? token)
    (and (some? value) (= token (type-token value)))
    (and (some? value) (= token (type value)))))

(defmacro token
  "A type's stable token, accepting qualified aliases even where the host
   interns a class rather than a var. Example: (token parser/Variable)."
  [type-name]
  (let [n (namespace type-name)
        aliases (ns-aliases *ns*)
        full-ns (if n (str (or (get-in &env [:ns :requires (symbol n)])
                               (get aliases (symbol n)) n))
                   (str (or (get-in &env [:ns :name]) (ns-name *ns*))))]
    (keyword full-ns (name type-name))))

(defmacro instance?
  "Portable instance? accepting a qualified type name even on the JVM,
   where parser/Variable names a class, not a var. Dynamic tokens also work."
  [type-or-token value]
  (if (and (symbol? type-or-token) (namespace type-or-token))
    `(instance-of? (token ~type-or-token) ~value)
    `(instance-of? ~type-or-token ~value)))
