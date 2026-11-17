(ns eve.deftype-proto.type-io
  "The type-io machinery of Eve's type macros: the optional EDN
   declaration (an options map — {:tag :read :print} — immediately
   after a type's field vector) and the tagged-reader registration
   every generated type emits.

   This is proto-layer machinery (plan §8, slice R0): it used to live
   privately in eve.deftype.macros, where only eve.deftype's macro
   could reach it — which is why proto's host-deftype had no reader
   generation at all. Both surviving macro families consume it from
   here: eve.deftype/deftype through eve.deftype.macros, and
   eve.deftype-proto.macros/host-deftype directly. The functions are expansion-time: they run in the
   defining namespace's context (*ns* is the namespace being compiled)
   and answer the body's parsed form plus the reader form to emit."
  (:require [eve.deftype-proto :as proto]
            [eve.deftype-proto.vocab :as v]))

(defn type-io-spec
  "Optional EDN declaration immediately after the field vector. Existing
   IPrintWithWriter implementations remain the portable override surface."
  [type-name fields body]
  (let [opts (if (map? (first body)) (first body) {})
        body (if (map? (first body)) (next body) body)
        tag (or (:tag opts) (symbol (str (ns-name *ns*)) (name type-name)))
        _ (when-not (symbol? tag)
            (throw (ex-info "Eve type :tag must be a symbol" {:tag tag})))
        parsed (proto/parse-protocol-impls body)
        fields? (some #(or (= 'eve.type-readers/ITypeFields (:protocol %))
                           ;; A consumer can use an alias for this protocol.
                           (= "ITypeFields" (name (:protocol %)))) parsed)
        printer? (some #(= 'IPrintWithWriter (v/strip-cljs-core (:protocol %))) parsed)
        _ (when (and printer? (:print opts))
            (throw (ex-info "Use either :print or IPrintWithWriter, not both" {:type type-name})))
        x (gensym "value") w (gensym "writer") o (gensym "opts")
        pairs (mapcat (fn [f] [(keyword (name f))
                               (symbol (name f))]) fields)
        print-form (or (:print opts)
                       (list 'fn [x w o]
                             (list 'eve.type-readers/write-tagged! w (list 'quote tag)
                                   (list 'eve.type-readers/-type-fields x)
                                   (mapv (fn [f] (keyword (name f))) fields))))
        body (if printer? body
               (concat body (when-not (or (:print opts) fields?)
                              ['eve.type-readers/ITypeFields
                               (list '-type-fields [x] (cons 'array-map pairs))])
                       ['IPrintWithWriter
                             (list '-pr-writer [x w o] (list print-form x w o))]))]
    {:body body :tag tag :read (:read opts)
     :print-info {:mode (cond (:print opts) :custom printer? :protocol :else :field-map)
                  :protocol 'IPrintWithWriter :method '-pr-writer}}))

;; LOAD-ORDER CONTRACT: the form this builds is a top-level
;; (eve.type-readers/register! ...) call in the DEFINING namespace's
;; compiled output. eve.type-readers must therefore be a runtime
;; require of the defining namespace itself — the macro-side require
;; in eve.deftype-proto.macros never enters the ClojureScript runtime
;; graph and orders nothing there (measured Nov 16 2026: rseq and the
;; vec/map/set transients registered their readers before
;; eve.type-readers' namespace object existed). eve.deftype-guard-test
;; enforces the require on every host-deftype / eve.deftype consumer.
(defn reader-form [type-name fields {:keys [tag read print-info]}]
  ;; Capture the positional factory NOW: a record wrapper may subsequently
  ;; rebind ->T to a public constructor hiding metadata/extmap fields.
  (let [ctor (gensym "constructor") data (gensym "data")
        factory (symbol (str "->" (name type-name)))
        reader (or read (list 'fn [data]
                              (list 'eve.type-readers/require-fields! (list 'quote tag) data)
                              (cons ctor (map (fn [f] (list 'get data (keyword (name f)))) fields))))]
    (list 'let [ctor factory]
          (list 'eve.type-readers/register!
                (str (ns-name *ns*) "/" (name type-name))
                (list 'quote tag) reader (list 'quote print-info)))))
