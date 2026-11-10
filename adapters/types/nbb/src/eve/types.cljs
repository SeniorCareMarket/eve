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

;; F-NBB-PRWRITER: ClojureScript's writer helpers — cljs.core/pr-writer
;; and cljs.core/pr-sequential-writer — are private in compiled
;; ClojureScript and absent from nbb's core, so a vanilla ClojureScript
;; library naming them unqualified in a :cljs arm (DataScript's pr-db,
;; db.cljc) fails to analyse on this seat: "Unable to resolve symbol:
;; pr-writer". Eve's nbb eve.core already carries a pr-writer with
;; ClojureScript's name and arity for Eve's own consumers; both helpers
;; are interned here, into the core namespace sci keeps (clojure.core),
;; so library code resolves them unchanged. pr-sequential-writer is
;; eve.coll's (itself written once for every host) in core vocabulary.
;; Guarded by resolve, so a name a future nbb supplies itself is never
;; shadowed.
(defn- nbb-pr-sequential-writer
  [writer print-one begin sep end opts coll]
  (cljs.core/-write writer begin)
  (loop [xs (seq coll) n cljs.core/*print-length* first? true]
    (when xs
      (if (and n (zero? n))
        (do (when-not first? (cljs.core/-write writer sep))
            (cljs.core/-write writer "..."))
        (do (when-not first? (cljs.core/-write writer sep))
            (print-one (first xs) writer opts)
            (recur (next xs) (when n (dec n)) false)))))
  (cljs.core/-write writer end))

(when-not (resolve 'pr-writer)
  (intern 'clojure.core 'pr-writer eve.core/pr-writer))
;; F-NBB-PRWRITER (companion): cljs.core/pr-str* — ClojureScript's
;; one-arg print-to-string, which a vanilla library's :cljs arms name
;; unqualified (DataScript's Entity toString) — is likewise absent
;; from nbb's core; Eve's nbb eve.core carries it, interned here too.
(when-not (resolve 'pr-str*)
  (intern 'clojure.core 'pr-str* eve.core/pr-str*))
(when-not (resolve 'pr-sequential-writer)
  (intern 'clojure.core 'pr-sequential-writer nbb-pr-sequential-writer))

;; F-NBB-ES6ITER: cljs.core's iterator types (ES6Iterator,
;; ES6EntriesIterator) are types compiled ClojureScript defines and
;; nbb's core does not, so a vanilla ClojureScript library patching
;; their prototypes with the iteration symbol at load (DataScript's
;; impl.entity) fails to analyse on this seat: "Unable to resolve
;; symbol: ES6Iterator". On this seat nothing constructs those types —
;; iteration is Eve's seqs — so each name is interned as a fresh,
;; unused constructor function: the library's prototype patch
;; resolves and lands on a prototype nothing reads. Guarded by
;; resolve, as above.
(doseq [t '[ES6Iterator ES6EntriesIterator]]
  (when-not (resolve t)
    (intern 'clojure.core t (fn []))))

;; F-NBB-ES6ITER (companion): cljs.core/ITER_SYMBOL — the iteration
;; symbol those prototype patches assign — is likewise absent from
;; nbb's core; interned here with its ClojureScript value,
;; js/Symbol.iterator (cljs.core aliases the core namespace sci keeps,
;; so the qualified name resolves to this intern).
;; The value is ClojureScript's own fallback for a host without
;; js/Symbol: cljs.core defines ITER_SYMBOL as Symbol.iterator when
;; Symbol exists and "@@iterator" when it does not — and js/Symbol
;; itself does not resolve in this seat's analysis context (tried
;; js/Symbol.iterator, (.-iterator js/Symbol), and via js/globalThis;
;; all three fail to analyse here, banked in the lane STATUS), so the
;; fallback is the honest value on this seat.
(when-not (resolve 'ITER_SYMBOL)
  (intern 'clojure.core 'ITER_SYMBOL "@@iterator"))

;; F-NBB-ES6ITER (companion): cljs.core's es6-iterator /
;; es6-entries-iterator — the seq -> ES6-iterator helpers a vanilla
;; library's JS-Map prototype methods call (DataScript's Entity keys /
;; entries / values) — are likewise absent from nbb's core. On this
;; seat those prototype methods never install (the interpreted
;; deftype has no JS prototype), but SCI analysis still requires the
;; names; the interns are real implementations (a JS object whose
;; next walks the seq), not stubs.
(defn- nbb-es6-iterator [coll]
  (let [s (volatile! (seq coll))]
    #js {:next (fn []
                 (if-let [xs @s]
                   (do (vreset! s (next xs))
                       #js {:value (first xs) :done false})
                   #js {:value nil :done true}))}))

(defn- nbb-es6-entries-iterator [coll]
  (nbb-es6-iterator (map (fn [e] #js [(nth e 0) (nth e 1)]) coll)))

(when-not (resolve 'es6-iterator)
  (intern 'clojure.core 'es6-iterator nbb-es6-iterator))
(when-not (resolve 'es6-entries-iterator)
  (intern 'clojure.core 'es6-entries-iterator nbb-es6-entries-iterator))

;; F-NBB-EDNREADER: nbb's bundled cljs.reader exports only read-string
;; — no register-tag-parser!, no *data-readers* — so a vanilla
;; ClojureScript library registering its EDN tag readers at load
;; (DataScript's core, for datascript/Datom and datascript/DB) fails
;; to analyse on this seat: "Unable to resolve symbol:
;; edn/register-tag-parser!". The seat's completion: cljs.reader is
;; required here, as Eve loads (the goog pattern below), and the
;; missing registry is interned into it — an atom-backed
;; *data-readers*, a register-tag-parser! that records into it, and a
;; read-string that wraps nbb's own, merging the registered tags into
;; its :readers (nbb's read-string honours a :readers opts map —
;; probed, fix/probes/probe3.cljs), so a registered tag genuinely
;; dispatches on read. Guarded by resolve: a future nbb supplying
;; the registry itself is never shadowed.
(require 'cljs.reader)

(def ^:private nbb-data-readers (atom {}))

(when-not (resolve 'cljs.reader/register-tag-parser!)
  (intern 'cljs.reader '*data-readers* nbb-data-readers)
  (intern 'cljs.reader 'register-tag-parser!
          (fn [tag f] (swap! nbb-data-readers assoc tag f) nil))
  (let [orig-read-string @(resolve 'cljs.reader/read-string)]
    (intern 'cljs.reader 'read-string
            (fn
              ([s] (orig-read-string {:readers @nbb-data-readers} s))
              ([opts s] (orig-read-string
                         (update opts :readers (fn [r] (merge @nbb-data-readers r)))
                         s))))))

;; F-NBB-GOOG (the base namespace): ClojureScript code also names the
;; ambient Closure base — (goog/exportSymbol ...), (goog/getUid ...) —
;; without requiring it, and sci resolves such a reference only once the
;; namespace is loaded. The seat's goog adapter (adapters/goog/nbb)
;; provides it; loading it here, as eve loads, registers it before a
;; consumer's library is analysed.
(require 'goog)

(def ^:private nbb-missing-protocols
  "ClojureScript protocols nbb's cljs.core lacks; eve.core defines them there."
  '#{IChunkedSeq IChunkedNext ASeq IDerefWithTimeout})

;; F-NBB-DEREFTIMEOUT (companion): a library's :cljs reify names
;; IDerefWithTimeout unqualified, resolved against the core namespace
;; — the deftype remap above cannot reach a reify — so the protocol
;; and its method var are interned into the core namespace sci keeps,
;; holding eve.core's protocol. Guarded by resolve, as every intern
;; in this file.
(when-not (resolve 'IDerefWithTimeout)
  (intern 'clojure.core 'IDerefWithTimeout eve.core/IDerefWithTimeout)
  (intern 'clojure.core '-deref-with-timeout eve.core/-deref-with-timeout))

(def ^:private nbb-absent-protocol-groups
  "Protocol groups that do not exist at all on this seat: nbb's bundled
   clojure.data is a shim exporting only the `diff` fn — it defines no
   EqualityPartition or Diff protocol, so a type body naming them
   (a JVM-written body, e.g. an external library's DB type naming
   clojure.data/EqualityPartition + clojure.data/Diff unguarded) dies
   at load with \"Protocol not found: clojure.data/Diff\". F-NBB-DATAGROUPS:
   those groups are dropped in this seat's deftype translation, exactly
   as PORTING-DS.md's F-NBB-MACROS entry recorded the design intent
   (\"clojure.data groups dropped on nbb\"); the library loads unchanged,
   and the type simply does not answer clojure.data diffing on this
   seat — the same disposition as a CLJ-only group on any CLJS seat.
   Every other host keeps the groups verbatim."
  '#{clojure.data/EqualityPartition clojure.data/Diff})

(defn- nbb-translate-protos
  "On nbb, the ClojureScript protocols its cljs.core lacks are eve.core's,
   and protocol groups this seat does not carry at all are dropped."
  [impls]
  (mapv (fn [{:keys [protocol] :as impl}]
          (let [p (v/strip-cljs-core protocol)]
            (if (contains? nbb-missing-protocols p)
              (assoc impl :protocol (symbol "eve.core" (name p)))
              impl)))
        (filterv (fn [{:keys [protocol]}]
                   (not (contains? nbb-absent-protocol-groups protocol)))
                 impls)))

;; sci resolves a form's symbols when it reads the form: a type's name is not
;; declared first
(def ^:private twist {:translate nbb-translate-protos :declare-type? false
                      ;; SCI resolves a (T. ...) inside a method body to
                      ;; a non-constructor for some type shapes
                      ;; (F-NBB-SEQWALK): plain-type rewrites own-type
                      ;; constructions to ->T factory calls
                      :factory-ctor-calls true})

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
