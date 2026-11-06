(ns eve.types
  "eve's types on nbb: ClojureScript types over the module slab
   (eve.types-js), the protocols nbb's cljs.core lacks taken from eve.core.
   The entry points eve's macros call (every eve.types has them):
     host-type, plain-type, slab-type, eve-type, extend-eve-type,
     existing-def, rename-fields, reader-of, print-form, vector-types."
  (:require [eve.core]
            [eve.deftype-proto.vocab :as v]
            [eve.types-js :as js]))

;; F-NBB-TAG: ClojureScript's primitive type tags (^number, ^string, ...)
;; on a def or defn name are compiler hints in ClojureScript — no var by
;; those names exists. sci resolves a name-position tag as a symbol, so a
;; vanilla ClojureScript library carrying them (DataScript's
;; (defn ^number hash-datom [d]), 37 sites across the port) fails to
;; analyse on this seat: "Unable to resolve symbol: number". The tags
;; that happen to name a cljs.core function (boolean, array, symbol)
;; resolve on their own; the rest are interned here, into the core
;; namespace sci keeps (clojure.core), each holding its own symbol — a
;; tag is metadata, nothing derefs it. Guarded by resolve, so a name a
;; future nbb supplies itself is never shadowed.
(doseq [tag '[number string object function bigint undefined]]
  (when-not (resolve tag)
    (intern 'clojure.core tag tag)))

;; F-NBB-GOOG (the base namespace): ClojureScript code also names the
;; ambient Closure base — (goog/exportSymbol ...), (goog/getUid ...) —
;; without requiring it, and sci resolves such a reference only once the
;; namespace is loaded. The seat's goog adapter (adapters/goog/nbb)
;; provides it; loading it here, as eve loads, registers it before a
;; consumer's library is analysed.
(require 'goog)

(def ^:private nbb-missing-protocols
  "ClojureScript protocols nbb's cljs.core lacks; eve.core defines them there."
  '#{IChunkedSeq IChunkedNext ASeq})

(defn- nbb-translate-protos
  "On nbb, the ClojureScript protocols its cljs.core lacks are eve.core's."
  [impls]
  (mapv (fn [{:keys [protocol] :as impl}]
          (let [p (v/strip-cljs-core protocol)]
            (if (contains? nbb-missing-protocols p)
              (assoc impl :protocol (symbol "eve.core" (name p)))
              impl)))
        impls))

;; sci resolves a form's symbols when it reads the form: a type's name is not
;; declared first
(def ^:private twist {:translate nbb-translate-protos :declare-type? false})

(defn host-type "A plain type written with the JVM's names." [_env type-name fields parsed]
  (js/plain-type type-name fields parsed twist))
(defn plain-type "A plain type (eve.deftype/deftype)." [_env type-name fields parsed]
  (js/plain-type type-name fields parsed twist))
(defn slab-type "An eve/deftype type." [m] (js/slab-type m))
(defn eve-type "An eve-deftype." [ctx] (js/eve-type ctx twist))
(defn extend-eve-type "eve-extend-type." [ctx] (js/extend-eve-type ctx))
(defn rename-fields "No field name is reserved." [_env fields body] [fields body])

(defn existing-def
  "{:value v} when the namespace being compiled already defines sym, else nil."
  [_env sym]
  (when-let [v (resolve sym)] {:value @v}))

(defn reader-of
  "What a registered eve collection reads as on this host: its eve type
   (from-header; host-value is for a host whose types cannot stand in for its
   collections)."
  [from-header _host-value]
  from-header)

(defn print-form
  "The printer of a registered eve collection: ClojureScript's (eve.types-js)."
  [_env type-name spec]
  (js/print-form type-name spec))

(defn vector-types
  "nbb's own vector types, ClojureScript's (eve.types-js)."
  [_env]
  (js/vector-types))
