(ns eve.types
  "eve's types on nbb: ClojureScript types over the module slab
   (eve.types-js), the protocols nbb's cljs.core lacks taken from eve.core.
   The entry points eve's macros call (every eve.types has them):
     host-type, plain-type, slab-type, eve-type, extend-eve-type,
     existing-def, rename-fields, reader-of, print-form, vector-types."
  (:require [eve.core]
            [eve.deftype-proto.vocab :as v]
            [eve.types-js :as js]))

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
