(ns eve.flat.sister
  "The generated flat sister of an eve-deftype (FORMAT-V1 §6, IMPL-PLAN
   lane H5; the operator's two-kinds rule, 2026-11-12): every type
   eve-deftype generates — Eve's own and user types alike — generates two
   kinds from the same macro expansion, the persistent kind in a domain
   and this flat kind over packed bytes. A persistent value turns flat
   when it crosses or leaves an atom boundary; the conversion in both
   directions goes by the registrations the expansion emits.

   This namespace is the registry those registrations land in, and the
   writer-side conversion they enable:

     register-sister!   the expansion's own call: a type's key, its slab
                        fields (as data) and its generated sister factory.
                        Computes the layout fingerprint, holds the
                        descriptor, and registers the factory with the
                        packed reader (eve.flat.chunk) so the type's
                        instances and batch rows read as sisters.

     the rep converter  registered with eve.flat.chunk's writer: a
                        slab-backed instance of a registered type becomes
                        the writer's typed rep by its descriptor — its
                        fields read from its block by class — and a
                        sister becomes the same rep by its field source.

   The chunk is reached by requiring-resolve where a host has it, the
   codebase's pattern where a lower namespace must not require a higher
   one (eve.flat's own delegates, T1's transfer), and through a static
   table where it has none (ClojureScript, clojerl).

   Bytes: a sister reads its fields through the chunk's field sources
   (eve.flat.chunk RecordSource / RowSource: column[row] through
   eve.flat-io); this namespace's own byte reads — a slab instance's
   fields, read to pack it — go through eve.slab-io, and a float
   field's bits are decoded by eve.flat-io (fio/f32, fio/f64 over the
   field's bytes), with no host branch (flat lanes U4a, 2026-11-17:
   the sister realized on the JVM, babashka and ClojureScript)."
  (:refer-clojure :exclude [unchecked-multiply])
  (:require [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as serialize]
            [eve.deftype.slab-runtime :as slab-runtime]
            [eve.slab-io :as slab-io]
            [eve.flat.source :as source]
            [eve.deftype.registry :as registry]
            [eve.flat-io :as fio]
            ;; unchecked-multiply is eve.host's: ClojureDart's core has
            ;; none, and sister's siblings refer it from there the same way
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
            #_{:clj-kondo/ignore [:unused-namespace]}   ; this spec's refer list mirrors the file's :refer-clojure :exclude declaration - the host-face surface, kept in sync by construction; the flagged names are not called from this file's body 
            #_{:clj-kondo/ignore [:unused-referred-var]}   ; this spec's refer list mirrors the file's :refer-clojure :exclude declaration - the host-face surface, kept in sync by construction; the flagged names are not called from this file's body 
            #_{:clj-kondo/ignore [:unused-referred-var]}   [eve.host :refer [unchecked-multiply]]
            ;; on the JVM the chunk is loaded with this namespace: a
            ;; generated sister class implements its IFlatHandle, so
            ;; the protocol must exist before any type is defined.
            ;; ClojureScript loads it too, under an alias: the chunk
            ;; has a full cljs side (the X1 transplant in chunk.cljc),
            ;; and chunk-var's cljs arm reaches it through the alias
            ;; (cljs has no runtime require). ClojureDart reads :clj
            ;; arms too, but the chunk is JVM-only at this pin —
            ;; registration tolerates its absence there — so :cljd
            ;; splices nothing here and the chunk stays out of the
            ;; Dart compile graph.
            #?@(:cljd [] :clj [[eve.flat.chunk]]
                :cljs [[eve.flat.chunk :as flat-chunk]]
                :clje [[eve.flat.chunk :as flat-chunk]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; the layout fingerprint (FORMAT-V1 §6)
;; ---------------------------------------------------------------------------

#?(:clje
   (defn layout-fingerprint
     "The 64-bit layout fingerprint of a type — the :default arm below
      carries the full doc. Clojerl's analyzer resolves neither the
      ^long return tag nor the long coercion (Erlang has one integer
      type), so this arm keeps the FNV-1a in unsigned 64-bit — mask
      after each multiply, sign-convert at the end — which is bit-exact
      with the wrapping long arithmetic of the other hosts."
     [type-key fields]
     (let [s (pr-str [type-key (mapv (fn [f] [(:name f) (:type-class f) (:type-hint f)])
                                     fields)])]
       ;; a clojerl string is a UTF-8 binary and (nth s i) answers a
       ;; one-character string, not a code — so hash its byte list,
       ;; which is the JVM's char codes for the ASCII a pr-str of type
       ;; keys, field names and hints consists of
       (loop [bs (erlang/binary_to_list s) h 14695981039346656037]
         (if (seq bs)
           (recur (rest bs)
                  (bit-and (* (bit-xor h (first bs))
                              1099511628211)
                           0xFFFFFFFFFFFFFFFF))
           (if (>= h 9223372036854775808)
             (- h 18446744073709551616)
             h)))))
   :cljs
   (defn layout-fingerprint
     "The 64-bit layout fingerprint of a type — the :default arm below
      carries the full doc. A JS number holds 53 bits and bit-xor works
      on 32, so this arm keeps the FNV-1a in js/BigInt space, wrapped to
      64 bits after each multiply and sign-converted at the end: the
      JVM's long, exactly, as a BigInt (what fio/i64 reads back from a
      chunk's type table on this host). The operators are JS's own on
      BigInt operands (eve.flat.chunk's number-model note)."
     [type-key fields]
     (let [s (pr-str [type-key (mapv (fn [f] [(:name f) (:type-class f) (:type-hint f)])
                                     fields)])
           mask (js/BigInt "0xFFFFFFFFFFFFFFFF")
           prime (js/BigInt "1099511628211")
           n (count s)]
       (loop [i 0 h (js/BigInt "14695981039346656037")]
         (if (< i n)
           (recur (inc i) (bit-and (* (bit-xor h (js/BigInt (.charCodeAt s i))) prime) mask))
           (if (>= h (js/BigInt "9223372036854775808"))
             (- h (js/BigInt "18446744073709551616"))
             h)))))
   :default
   (defn layout-fingerprint
     "The 64-bit layout fingerprint of a type: a hash over the type key and,
   per field in declaration order, its name, class and hint — declaration
   data only, so every process that defines the type derives the same
   fingerprint for the same layout, and a changed layout (a field added,
   dropped, reclassed or rehinted) derives a different one. FNV-1a over
   the pr-str of that data."
     ^long [type-key fields]
     (let [s (pr-str [type-key (mapv (fn [f] [(:name f) (:type-class f) (:type-hint f)])
                                     fields)])]
       ;; the seed is the FNV offset basis as a signed long,
       ;; -3750763034362895579 (0xCBF29CE484222325), built from its two
       ;; 32-bit halves instead of written as a negative literal:
       ;; jolt's --dev AOT constant path mis-encodes a negative long
       ;; literal (it emits magnitude - 2^64), so (long -3750763034362895579)
       ;; throws "Value out of range for long: -14695981039346656037" in
       ;; the AOT binary while interpreted jolt and the JVM compute it
       ;; fine — measured 2026-11-21, micro-probe + the fingerprint
       ;; probe. The bit-built form is bit-exact on all three.
       (loop [i 0 h (long (bit-or (bit-shift-left 3421674724 32) 2216829733))]
         (if (< i (count s))
           (recur (inc i)
                  ;; the char's code, in eve.numeric/char-code's arms:
                  ;; (int (nth s i)) is the JVM's Character cast, but on
                  ;; basilisp (nth s i) is a one-char string and (int str)
                  ;; is a parse — it died on the pr-str's leading '[' —
                  ;; and on cljs the code is charCodeAt's, not int's.
                  (unchecked-multiply (bit-xor h (long #?(:lpy (python/ord (nth s i))
                                                          :default (int (nth s i)))))
                                      (long 1099511628211)))
           h)))))

;; ---------------------------------------------------------------------------
;; the descriptor registry
;; ---------------------------------------------------------------------------

;; type key → descriptor {:type-key :fingerprint :fields :factory
;;                        :generated-by}. One per eve-deftype, written by
;; the code its expansion generates, at the load of the type's namespace.
(defonce ^:private descriptors (atom {}))

(defn descriptor
  "The sister descriptor registered for type key (\"ns/Type\"), or nil
   when the type has none — a type eve-deftype did not generate, or one
   defined before the sister was part of the expansion."
  [type-key]
  (get @descriptors type-key))

#?(:cljs
   (defn- chunk-var
     "The fn of sym in eve.flat.chunk. ClojureScript has no runtime
      require: the chunk is statically required with this namespace
      (as on the JVM) and reached through this table — the flat
      delegation shape eve.flat's own cljs table uses. Every caller
      calls the answer as a fn, which is what the table holds."
     [sym]
     (case sym
       register-type! flat-chunk/register-type!
       ->TypedVal flat-chunk/->TypedVal
       to-rep flat-chunk/to-rep
       register-rep-converter! flat-chunk/register-rep-converter!
       register-nominal-rep-converter! flat-chunk/register-nominal-rep-converter!
       pack-rep flat-chunk/pack-rep
       open flat-chunk/open
       nil))
   :clje
   (defn- chunk-var
     "The fn of sym in eve.flat.chunk. Clojure Erlang's analyzer
      rejects requiring-resolve in this context (measured at this
      defn): the chunk is statically required with this namespace
      and reached through the same table the cljs arm uses."
     [sym]
     (case sym
       register-type! flat-chunk/register-type!
       ->TypedVal flat-chunk/->TypedVal
       to-rep flat-chunk/to-rep
       register-rep-converter! flat-chunk/register-rep-converter!
       register-nominal-rep-converter! flat-chunk/register-nominal-rep-converter!
       pack-rep flat-chunk/pack-rep
       open flat-chunk/open
       nil))
   :default
   (do
     ;; each name resolved once: the delivery path asks for chunk fns for every value it packs, and
     ;; requiring-resolve built a symbol and walked the namespace map each time (7% of a ds read's samples,
     ;; 2026-11-20 ET). A name that does not resolve is asked again (the chunk may load later).
     (defonce ^:private chunk-vars (atom {}))
     (defn- chunk-var
       "The var of sym in eve.flat.chunk, or nil when the chunk is not
        loadable on this host."
       [sym]
       (or (get @chunk-vars sym)
           (when-let [v (try (requiring-resolve (symbol "eve.flat.chunk" (name sym)))
                             (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))]
             (swap! chunk-vars assoc sym v)
             v)))))

;; defined with the converter at the foot of this namespace; registering
;; a descriptor is what first forces it
(declare converter-registered)

(defn register-descriptor!
  "Register a sister descriptor: held here, and — on a host with the
   packed reader — its factory registered with eve.flat.chunk under
   [type key, fingerprint], so the type's typed nodes and batch rows
   read as its sister. Answers the descriptor."
  [desc]
  ;; whether it is an eve.record type's, answered once here: the delivery path asks it for every value it
  ;; packs (record->typed-rep, own-type-instance?), and it built a set of the field keywords each time
  (let [ks (into #{} (map :keyword) (:fields desc))
        desc (assoc desc :record? (and (contains? ks :record-extmap__) (contains? ks :record-meta__)))]
    (swap! descriptors assoc (:type-key desc) desc)
    @converter-registered
    (when-let [factory (:factory desc)]
      (when-let [register! (chunk-var 'register-type!)]
        (register! (:type-key desc) (:fingerprint desc) factory)))
    desc))

(defn register-sister!
  "What eve-deftype's expansion calls for every type it defines:
   (register-sister! type-key fields factory), fields the type's slab
   fields as data ({:name :keyword :type-class :type-hint :mutability
   :offset} in declaration order; ^:host fields are not among them —
   they are not written, FORMAT-V1 §6), factory the generated
   Type__sister fn, or nil on a host that realizes no sister class.

   The 4-arity takes the proto engine's options (plan §4, M2):
   :generated-by names the emitting macro, and :cached-derivations
   records a proto type's ^:cached derivations (plan §3(b)(i)) —
   {field-keyword fn}, the fns a sister's factory rebuilds the
   cached values from the packed source with. Cached values are
   not packed; the descriptor's :fields never carry them."
  ([type-key fields factory]
   (register-sister! type-key fields factory nil))
  ([type-key fields factory {:keys [generated-by cached-derivations]}]
   (register-descriptor!
    (cond-> {:type-key type-key
             :fingerprint (layout-fingerprint type-key fields)
             :fields (vec fields)
             :factory factory
             :generated-by (or generated-by :eve-deftype)}
      (seq cached-derivations) (assoc :cached-derivations cached-derivations)))))

(defn deregister!
  "Remove a type's sister registration (the descriptor only; the packed
   reader's factory table is keyed by fingerprint and is left — a chunk
   written by the type still reads by it). Used by tests that prove the
   conversion runs on the registration."
  [type-key]
  (swap! descriptors dissoc type-key)
  nil)

;; ---------------------------------------------------------------------------
;; the generated freeze/thaw methods
;; ---------------------------------------------------------------------------

(defprotocol IFreezeThaw
  "The flat methods eve-deftype generates for every type it
   defines, beside register-sister!: -freeze on the persistent
   kind, -thaw on the flat sister — an implicit method pair
   off every deftyped object, no user code. The bodies route
   to the hooks eve.flat.freeze registers (freeze-instance /
   thaw-instance), so this namespace never requires it."
  (-freeze [x sio] "x's flat form, written into sio's domain: a FrozenValue.")
  (-thaw [x] "This flat value's persistent form."))

(defonce ^:private freeze-hook (atom nil))
(defonce ^:private thaw-hook (atom nil))

(defn register-flat-hooks!
  "eve.flat.freeze's registration: the domain writer
   (freeze-value) and the materializer (plain-of) that the
   generated methods route to."
  [freeze-f thaw-f]
  (reset! freeze-hook freeze-f)
  (reset! thaw-hook thaw-f))

(defn freeze-instance
  "The generated -freeze body: freeze any Eve value into
   sio's domain through eve.flat.freeze's registered writer."
  [sio x]
  (if-let [f @freeze-hook]
    (f sio x)
    (throw (ex-info "freeze: eve.flat.freeze is not loaded" {:value x}))))

(defn thaw-instance
  "The generated -thaw body: a flat value's persistent form
   through eve.flat.freeze's registered materializer."
  [x]
  (if-let [f @thaw-hook]
    (f x)
    (throw (ex-info "thaw: eve.flat.freeze is not loaded" {:value x}))))

(defn freeze
  "The freeze method as a function: a deftyped object's
   generated -freeze where it has one, else the generic
   freeze of any Eve value into sio's domain."
  [sio x]
  (if (satisfies? IFreezeThaw x)
    (-freeze x sio)
    (freeze-instance sio x)))

(defn thaw
  "The thaw method as a function: a flat value's persistent
   form — a sister's generated -thaw where it has one, else
   the generic materialization."
  [x]
  (if (satisfies? IFreezeThaw x)
    (-thaw x)
    (thaw-instance x)))

;; ---------------------------------------------------------------------------
;; reading a slab-backed instance's fields, by its descriptor
;; ---------------------------------------------------------------------------

;; A float field's bits decoded by eve.flat-io over the field's own bytes
;; (eve.slab-io/read-bytes): one route on every host, where these were
;; per-host bit casts (:clj Float/intBitsToFloat, :jank eve.encoding, and
;; on ClojureScript a throw). Off the hot path: only the writer-side
;; conversion of a slab instance reads them.
(defn- read-f32 [sio off o]
  ;; fio's f32 answers the float widened (a double on the JVM); the
  ;; persistent kind's read of a float32 field answers a float
  (float (fio/f32 (fio/chunk (slab-io/read-bytes sio off o 4)) 0)))

(defn- read-f64 [sio off o]
  (fio/f64 (fio/chunk (slab-io/read-bytes sio off o 8)) 0))

(defn- read-primitive
  "A primitive field's value, read as the persistent kind's own field
   read of it answers (eve.deftype-proto/jvm-read-fn-for-type, at runtime)."
  [sio off f]
  (let [o (:offset f)]
    (case (:type-hint f)
      "int8"    (let [b (slab-io/read-u8 sio off o)] (if (> b 127) (- b 256) b))
      "uint8"   (slab-io/read-u8 sio off o)
      "int16"   (let [v (slab-io/read-u16 sio off o)] (if (> v 32767) (- v 65536) v))
      "uint16"  (slab-io/read-u16 sio off o)
      "int32"   (slab-io/read-i32 sio off o)
      ;; bit-and answers a long on the JVM (Numbers.and), an integer on
      ;; clojerl — the persistent kind's unsigned read, no host branch
      "uint32"  (bit-and (slab-io/read-i32 sio off o) 0xFFFFFFFF)
      "float32" (read-f32 sio off o)
      "float64" (read-f64 sio off o)
      (slab-io/read-i32 sio off o))))

(defn- read-nested
  "A reference field's value: the referenced instance, built by the
   constructor its own type registered (eve.deftype-proto.serialize),
   or nil for the -1 sentinel — as the persistent kind's read answers."
  [sio off f]
  (let [target (slab-io/read-i32 sio off (:offset f))]
    (when (not= -1 target)
      (let [type-id (slab-io/read-u8 sio target 0)
            name-hash (bit-or (slab-io/read-u8 sio target 1)
                              (bit-shift-left (slab-io/read-u8 sio target 2) 8)
                              (bit-shift-left (slab-io/read-u8 sio target 3) 16))
            ctor (get-in @serialize/deftype-constructors
                         [(int type-id) (int name-hash)])]
        (when-not ctor
          (throw (ex-info "eve.flat.sister: no constructor registered for a referenced type"
                          {:type-id type-id :name-hash name-hash})))
        (ctor sio target)))))

(defn- read-field [sio off f]
  (case (:type-class f)
    :primitive  (read-primitive sio off f)
    :eve-type   (read-nested sio off f)
    :serialized (slab-runtime/slab-read-serialized sio off (:offset f))
    nil))

(defn field-values
  "The field values of a slab-backed instance of a registered type, as
   an array-map of field keyword → value in declaration order: a
   primitive's number, a reference field's instance, a serialized
   field's stored value."
  [desc inst]
  (let [sio (d/-eve-sio inst)
        off (d/-root-header-off inst)]
    (into (array-map)
          (map (fn [f] [(:keyword f) (read-field sio off f)]))
          (:fields desc))))

;; ---------------------------------------------------------------------------
;; the writer-side conversion: an instance (or a sister) → the typed rep
;; ---------------------------------------------------------------------------

(defn- typed-rep-of
  "The chunk writer's typed rep for type key at fingerprint over field
      values (an array-map of keyword → plain value or handle): each
      value converted by the writer's own to-rep. Nil when the chunk
      is not loadable on this host."
  [type-key fingerprint values]
  (let [->TypedVal (chunk-var '->TypedVal)
        to-rep (chunk-var 'to-rep)]
    (when (and ->TypedVal to-rep)
      (->TypedVal type-key fingerprint
                  (into (array-map)
                        (map (fn [[k v]] [k (to-rep v)]))
                        values)))))

;; The converters below are consulted by the chunk writer for EVERY value it
;; packs (to-rep: the nominal converters before its plain branches, the
;; plain converter for every scalar), and on the JVM a satisfies? on a class
;; that does not implement the protocol walks the class's supers on every
;; call. Measured (flat lanes U4a, 2026-11-17, the 1BRC shape: 407 string
;; keys -> [min sum max count]): fc/pack 4.6 ms with this namespace not
;; loaded, 34 ms once it is loaded with one type registered — at the base
;; too (3.8 -> 35 ms). A plain scalar or a clojure.lang collection is never
;; a slab instance, a sister or a frozen value: answered before any
;; satisfies? (eve.flat.chunk's fast handle test, the same rule). bb,
;; glojure and jank keep satisfies? (their types generate no JVM classes).
(defn- plain-value? [x]
  #?(:bb false :jank false :glj false
     :clj (or (nil? x) (string? x) (number? x) (keyword? x) (symbol? x) (boolean? x) (char? x)
              (and (instance? clojure.lang.IPersistentCollection x)
                   (.startsWith (.getName (class x)) "clojure.lang.")))
     :default false))

(defonce ^:private flat-rep-hook (atom nil))

(defn register-flat-rep-hook!
  "Register the rep hook for flat values that are neither sisters
   nor slab instances — eve.flat.freeze's FrozenValue, a
   slab-resident flat handle: the chunk writer consults it
   through this converter, and the hook answers the value's
   CONTENT rep (the rep of the handle the frozen value wraps),
   so packing a value that contains a frozen subtree packs the
   subtree's content, not the frozen wrapper."
  [f]
  (reset! flat-rep-hook f))

(defn instance->typed-rep
  "A slab-backed eve-deftype instance, or a generated sister, as the
      chunk writer's typed rep — by the type's generated sister
      registration. Nil when the value is neither, or its type has no
      sister registered (the writer then treats it as it does today)."
  [v]
  (when-not (plain-value? v)
    (or (when-let [h @flat-rep-hook] (h v))
        (cond
       ;; an eve value array (e/make-array) packs as the vector of its
       ;; slots: it is content, not a reference (EVE-FIX-PLAN X3)
          (and (satisfies? d/ISabpType v)
               (= "eve/ValueArray" (d/-sabp-type-key v)))
          (when-let [to-rep (chunk-var 'to-rep)]
            (to-rep (vec (seq v))))

          (satisfies? source/ISister v)
          (let [tkey (source/-sister-type-key v)]
            (when-let [desc (descriptor tkey)]
              (typed-rep-of tkey (:fingerprint desc)
                            (source/-field-map (source/-sister-source v)))))

          (and (satisfies? d/ISabpType v)
               (satisfies? d/IEveSio v)
               (satisfies? d/IEveRoot v))
          (let [tkey (d/-sabp-type-key v)]
            (when-let [desc (descriptor tkey)]
              (typed-rep-of tkey (:fingerprint desc)
                            (field-values desc v))))

          :else nil))))

(defn- record-descriptor?
  "A descriptor for an eve.record type: defrecord's expansion adds
      the two hidden fields record-extmap__ / record-meta__ to every
      record type's slab fields, and they ride the descriptor. The
      collection types' descriptors never carry them."
  [desc]
  (if (contains? desc :record?)
    (:record? desc)                                 ;; register-descriptor!'s answer
    (let [ks (into #{} (map :keyword) (:fields desc))]
      (and (contains? ks :record-extmap__)
           (contains? ks :record-meta__)))))

(defn record->typed-rep
  "A slab-backed eve.record instance as the chunk writer's typed
      rep — the same rep instance->typed-rep builds, claimed through
      the writer's NOMINAL converter instead: a record is map-shaped,
      so the writer's plain map branch packs its content and loses
      the nominal type before the plain converter is consulted.
      Registered as a nominal converter, this claims the record for
      its sister first. Nil for every non-record value, so maps,
      vectors, sets and the collection types pack as they do today."
  [v]
  (when (and (not (plain-value? v))
             (satisfies? d/ISabpType v)
             (satisfies? d/IEveSio v)
             (satisfies? d/IEveRoot v))
    (let [tkey (d/-sabp-type-key v)]
      (when-let [desc (descriptor tkey)]
        (when (record-descriptor? desc)
          (typed-rep-of tkey (:fingerprint desc)
                        (field-values desc v)))))))

;; ---------------------------------------------------------------------
;; Own-type identity for user eve-deftypes (Nov 15 2026, design call
;; option (b) on the 96ee re-pin): an Eve deftype owns its type identity
;; on every host through the existing type-id registry
;; (eve.deftype.registry). A slab instance whose registry entry names
;; its own class as :host-type and whose sister was generated by
;; eve-deftype is an OWN-TYPE instance: the writer claims it nominally
;; (typed rep by its descriptor, never the plain set/map branch that
;; drops the type — a BTSet is set-shaped), and atom delivery hands it
;; back as its own class (eve.flat.freeze/deliver-flat). Records keep
;; their own claim (record->typed-rep); Eve's collection types are
;; proto-deftypes and map entries pack as entries, as before.
;; ---------------------------------------------------------------------

(defn own-type-instance?
  "v is a slab instance of a registered user eve-deftype (not a
      record, not a map entry): its registry entry's :host-type is v's
      own class and its sister descriptor was generated by eve-deftype."
  [v]
  (and (some? v)
       (not (plain-value? v))
       (not (map-entry? v))
       (satisfies? d/ISabpType v)
       (satisfies? d/IEveSio v)
       (satisfies? d/IEveRoot v)
       (let [tkey (d/-sabp-type-key v)
             desc (descriptor tkey)]
         (and desc
              (= :eve-deftype (:generated-by desc))
              (not (record-descriptor? desc))
              (= (type v) (:host-type (registry/lookup-type tkey)))))))

(def ^:dynamic *own-type-seen*
  "Bound by eve.flat.freeze's delivery to a volatile: the writer sets
      it when it claims an own-type instance, so a delivery that packed
      one nested in a collection can deliver it as its own class."
  nil)

(defn own-type->typed-rep
  "A slab own-type instance (own-type-instance?) as the chunk
      writer's typed rep, claimed through the NOMINAL converter so a
      collection-shaped deftype keeps its type key. Nil otherwise."
  [v]
  (when (own-type-instance? v)
    (when-let [seen *own-type-seen*] (vreset! seen true))
    (let [tkey (d/-sabp-type-key v)
          desc (descriptor tkey)]
      (typed-rep-of tkey (:fingerprint desc) (field-values desc v)))))

(defonce ^:private converter-registered
  (delay (when-let [register! (chunk-var 'register-rep-converter!)]
           (register! instance->typed-rep)
           (when-let [register-nominal! (chunk-var 'register-nominal-rep-converter!)]
             (register-nominal! record->typed-rep)
             (register-nominal! own-type->typed-rep))
           true)))

(defn construct-flat
  "Build type-key's flat sister from its slab-field values, in
      declaration order — the construction path when no Eve domain
      is bound. Instantiating an eve-deftype outside a transaction
      produces the type's flat variant (John, Nov 14 2026 — the
      no-domain throw is retired for eve-deftype types): the result
      is the generated sister over a packed chunk, satisfying the
      type's flat surface; swapped into an atom it embeds by
      reference at its position. Throws when the type has no
      sister registered."
  [type-key field-vals]
  (if-let [desc (descriptor type-key)]
    (let [pack-rep (chunk-var 'pack-rep)
          open (chunk-var 'open)]
      (if (and pack-rep open)
        (let [values (into (array-map)
                           (map (fn [f v] [(:keyword f) v])
                                (:fields desc) field-vals))
              rep (typed-rep-of type-key (:fingerprint desc) values)]
          #?(:cljs (try (open (pack-rep rep))
                        (catch :default e
                             ;; no type table in this host's chunk
                             ;; writer (v1): a typed rep is refused
                             ;; as unwritable, and the type's flat
                             ;; form here is its field map — the
                             ;; posture the writer's own scan
                             ;; docstring names for this host.
                          (if (= :eve.flat.chunk/unwritable (:type (ex-data e)))
                            (open (pack-rep ((chunk-var 'to-rep) values)))
                            (throw e))))
             :default (open (pack-rep rep))))
        (throw (ex-info "construct-flat: the flat path is unavailable on this host"
                        {:eve.flat/error :no-flat-path :type-key type-key}))))
    (throw (ex-info "construct-flat: the type has no flat sister registered"
                    {:eve.flat/error :no-sister :type-key type-key}))))
