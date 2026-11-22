(ns eve.types
  "eve's types on squint: ClojureScript types over the module slab
   (eve.types-js), the protocols squint's core lacks taken from eve.core.
   The entry points eve's macros call (every eve.types has them):
     host-type, plain-type, slab-type, eve-type, extend-eve-type,
     existing-def, rename-fields, reader-of, print-form, vector-types.
   Pattern: adapters/types/nbb — squint, like nbb, runs its macros in SCI
   (existing-def resolves there) but compiles its types ahead, so a
   type's name is declared before its deftype, as on a ClojureScript
   compiler (eve.types-js's compiler-twist)."
  (:require [eve.core]
            [eve.deftype-proto.vocab :as v]
            [eve.types-js :as js]))

(def ^:private squint-missing-protocols
  "ClojureScript protocols whose impls are routed to eve.core's: the
   ones squint's core lacks (the chunked seqs, ASeq) and the ones its
   SCI cannot resolve under the bare name during deftype expansion
   ('Protocol not found: IEquiv' / 'ILookup', measured on 0.16.213) —
   eve.core's SCI face defprotocols the whole set as stubs and its
   runtime face aliases squint core's own protocol objects under the
   names, so an impl lands on the same protocol either way. This is
   sci-stub-protocols, applied on the translate path (plain-type,
   eve-type); the slab path has no translate hook and is repaired on
   the emitted form by squint-fix-form instead."
  '#{IChunkedSeq IChunkedNext ASeq IEquiv IHash IPrintWithWriter ILookup IKVReduce IAssociative ICollection ICounted IDeref IEditableCollection IEmptyableCollection IFn IIndexed IMap IMeta IReduce IReset IReversible ISeq ISeqable ISequential ISet IStack ISwap ITransientAssociative ITransientCollection ITransientMap ITransientSet ITransientVector IVector IWatchable IWithMeta INext IMapEntry IComparable IPending IRef IList})

(defn- squint-translate-protos
  "On squint, the ClojureScript protocols its core lacks are eve.core's."
  [impls]
  (mapv (fn [{:keys [protocol] :as impl}]
          (let [p (v/strip-cljs-core protocol)]
            (if (contains? squint-missing-protocols p)
              (assoc impl :protocol (symbol "eve.core" (name p)))
              impl)))
        impls))

;; squint compiles a type ahead: its deftype resolves the type's name
;; first, so declare it (eve.types-js's compiler-twist, not nbb's)
(def ^:private twist {:translate squint-translate-protos :declare-type? true})

(def ^:private sci-stub-protocols
  "Protocols eve.core's SCI face (adapters/coll/squint's core.cljc)
   defprotocols because squint's SCI core cannot resolve the bare
   names when it evaluates a deftype expansion during a load
   ('Protocol not found: ILookup'): an impl naming one of these must
   name eve.core's, whose runtime face (core.cljs) aliases squint
   core's own protocol object under the name, so the impl lands on
   the same protocol either way."
  '#{IChunkedSeq IChunkedNext ASeq IEquiv IHash IPrintWithWriter ILookup IKVReduce IAssociative ICollection ICounted IDeref IEditableCollection IEmptyableCollection IFn IIndexed IMap IMeta IReduce IReset IReversible ISeq ISeqable ISequential ISet IStack ISwap ITransientAssociative ITransientCollection ITransientMap ITransientSet ITransientVector IVector IWatchable IWithMeta INext IMapEntry IComparable IPending IRef IList})

(def ^:private protocol-fns
  "The protocol fns eve's machinery rewrites a type's own method calls
   to (eve.deftype-proto.vocab/cljs-own-calls): (cljs.core/-assoc this
   k v) for a body's (.assoc this k v). Squint's SCI core holds no
   protocol fns, so an expansion SCI evaluates during a load dies
   'Unable to resolve symbol: cljs.core/-assoc'; squint-fix-bodies
   routes the call heads to eve.core's, whose SCI face carries them
   as its defprotocol stubs' fns and whose runtime face carries
   squint core's own dispatch fns (or a wrapper over its generic
   fns, for the ones squint core does not export as fns)."
  '#{-count -lookup -contains-key? -assoc -dissoc -conj -empty -equiv
     -hash -seq -meta -with-meta -invoke -reduce -kv-reduce -nth -peek
     -pop -assoc-n -rseq -deref -swap! -reset! -as-transient -conj!
     -persistent! -assoc! -dissoc! -disjoin -disjoin! -pop! -assoc-n!
     -chunked-first -chunked-rest -chunked-next -pr-writer -next -first
     -rest -more -cons -key -val -compare -add-watch -remove-watch -notify-watches
     -set-validator! -get-validator -get-watches})

(defn- squint-fix-bodies
  "Rewrite (cljs.core/-<protocol-fn> ...) call heads anywhere in an
   emitted form to (eve.core/-<protocol-fn> ...) — see protocol-fns.
   Quoted forms are data and are left alone."
  [form]
  (letfn [(rw [x]
            (cond
              (seq? x) (if (= 'quote (first x))
                         x
                         (let [h (first x)]
                           (apply list
                                  (if (and (symbol? h)
                                           (= "cljs.core" (namespace h))
                                           (contains? protocol-fns (symbol (name h))))
                                    (symbol "eve.core" (name h))
                                    h)
                                  (map rw (rest x)))))
              (vector? x) (mapv rw x)
              (map? x) (into {} (map (fn [[k v]] [(rw k) (rw v)])) x)
              (set? x) (into #{} (map rw) x)
              :else x))]
    (rw form)))

(defn- squint-fix-form
  "Three squint repairs to an emitted type form, all in this adapter.

   1. eve.types-js emits its slab/eve type forms with BARE deftype /
      extend-type heads (plain-type qualifies cljs.core/deftype; the
      slab paths do not). A bare head is resolved by squint's compiler
      in the namespace being compiled — and a namespace that excludes
      the core name (:refer-clojure :exclude [... deftype ...], Eve's
      standard posture for its own deftype) makes squint skip macro
      resolution for that head entirely (its core-macro-shadowed?
      check consults the exclude set but not the referred macro),
      degrading the form to a runtime funcall. Qualify the heads:
      cljs.core's macro is resolved regardless of the target
      namespace's excludes.

   2. Bare stub-protocol names in a deftype's impl spine (and in an
      extend-type's protocol position) are routed to eve.core's (see
      sci-stub-protocols): the SCI load path resolves them there,
      the compiled path lands on the same protocol object.

   3. Protocol-fn call heads in method bodies are routed to
      eve.core's (squint-fix-bodies, see protocol-fns)."
  [form]
  (let [form (squint-fix-bodies form)
        fix (fn [x]
              (cond
                (not (seq? x)) x
                (#{'deftype 'cljs.core/deftype} (first x))
                (let [[_ tname fields & impls] x]
                  (apply list 'cljs.core/deftype tname fields
                         (map (fn [part]
                                (if (and (symbol? part)
                                         (not (namespace part))
                                         (contains? sci-stub-protocols part))
                                  (symbol "eve.core" (name part))
                                  part))
                              impls)))
                (#{'extend-type 'cljs.core/extend-type} (first x))
                (let [[_ tname proto & methods] x]
                  (apply list 'cljs.core/extend-type tname
                         (if (and (symbol? proto)
                                  (not (namespace proto))
                                  (contains? sci-stub-protocols proto))
                           (symbol "eve.core" (name proto))
                           proto)
                         methods))
                :else x))]
    (if (and (seq? form) (= 'do (first form)))
      (apply list 'do (map fix (rest form)))
      (fix form))))

(defn host-type "A plain type written with the JVM's names." [_env type-name fields parsed]
  (squint-fix-bodies (js/plain-type type-name fields parsed twist)))
(defn plain-type "A plain type (eve.deftype/deftype)." [_env type-name fields parsed]
  (squint-fix-bodies (js/plain-type type-name fields parsed twist)))
(defn slab-type "An eve/deftype type." [m] (squint-fix-form (js/slab-type m)))
(defn eve-type "An eve-deftype." [ctx] (squint-fix-form (js/eve-type ctx twist)))
(defn extend-eve-type "eve-extend-type." [ctx] (squint-fix-form (js/extend-eve-type ctx)))
(defn rename-fields "No field name is reserved." [_env fields body] [fields body])

(defn existing-def
  "{:value v} when the namespace being compiled already defines sym, else
   nil. Squint's macro environment is SCI: resolve sees its defs, as on
   nbb."
  [_env sym]
  (when-let [v (resolve sym)] {:value @v}))

(defn reader-of
  "What a registered eve collection reads as on this host: its eve type
   (from-header; host-value is for a host whose types cannot stand in for
   its collections)."
  [from-header _host-value]
  from-header)

(defn print-form
  "The printer of a registered eve collection: ClojureScript's (eve.types-js)."
  [_env type-name spec]
  (squint-fix-form (js/print-form type-name spec)))

(defn vector-types
  "Squint's host vector type: the plain JS Array (squint has no
   PersistentVector/Subvec classes — its vectors ARE arrays, and
   its subvec returns an array). Delegating to types-js here
   emitted cljs.core/PersistentVector.prototype writes against an
   undefined value and killed the graph at load (2026-11-13)."
  [_env]
  '[js/Array])
