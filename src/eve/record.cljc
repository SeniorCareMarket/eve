(ns eve.record
  "Immutable nominal records in Eve memory. No process-local payloads."
  (:refer-clojure :exclude [defrecord])
  #?(:cljs (:require-macros [eve.record]))
  (:require [eve.host]
            [eve.core :as e]
            [eve.deftype]
            [eve.deftype.registry :as registry]
            [eve.type-readers :as readers]
            [eve.record-context :as context]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.deftype.slab-runtime :as runtime]
            [eve.slab-io :as slab]
            [eve.codec :as codec]
            [eve.hamt-util :as hu]
            [eve.encoding :as encoding]
            [eve.map :as em]
            [eve.vec]
            [eve.set]
            [eve.list]))

(defprotocol IRecordData
  (-declared-keys [this])
  (-record-values [this])
  (-extras [this])
  (-record-meta [this])
  (-copy-slot [this slot value]))

(defn record? [x] (satisfies? IRecordData x))

(defn type-key [x] (d/-sabp-type-key x))

(declare assert-portable! canonical)

(defn- fail! [path reason]
  (throw (ex-info (str "Eve record: " reason " at " (pr-str path))
                  {:eve.record/error :non-portable :path path :reason reason})))

(defn assert-portable!
  "Preflight BEFORE allocation, including nested existing slab values. Unknown
   opaque slab types are refused rather than assuming their host caches are data.
   A future producer may certify an entry :record/portable? true, provided its
   IPlain exposes every persisted field and it has no host-only data."
  [sio x path]
  (when (fn? x)
    (throw (ex-info "functions can't be stored in Eve memory; pass them as call arguments"
                    {:eve.record/error :function :path path})))
  (when (and (or (map? x) (set? x) (sequential? x)) (meta x)
             (not (satisfies? d/IEveRoot x)))
    (assert-portable! sio (meta x) (conj path :meta)))
  (cond
    (record? x)
    (do (when-not (ser/same-domain? sio (d/-eve-sio x))
          (fail! path "foreign-domain Eve reference"))
        (doseq [[k v] (map vector (-declared-keys x) (-record-values x))]
          (assert-portable! sio v (conj path k)))
        (assert-portable! sio (-extras x) (conj path :extmap))
        (assert-portable! sio (-record-meta x) (conj path :meta)))

    (satisfies? d/IEveRoot x)
    (do (when-not (ser/same-domain? sio (d/-eve-sio x))
          (fail! path "foreign-domain Eve reference"))
        (let [key (when (satisfies? d/ISabpType x) (type-key x))
              tag (slab/read-u8 sio (d/-root-header-off x) 0)]
          (when-not (or (contains? #{0xED 0xEE 0x12 0x13} tag)
                        (:record/portable? (registry/lookup-type key)))
            (fail! path "opaque Eve type is not certified portable")))
        ;; Do not trust encode-value here: its pointer fast path bypasses REF
        ;; validation of an already-existing collection in a private domain.
        ;; A value WITH an IPlain view is walked through it. A certified Eve
        ;; type with no IPlain view is accepted on its certification: its
        ;; persisted fields were encoded by the slab codec at its own
        ;; construction, so no function or host value can be inside an
        ;; existing block (eve.db.ds's DB/Datom are this shape). Giving every
        ;; eve-deftype a default IPlain instead would make eve->clj descend
        ;; into them — the F-BB-DATOM-CMP landmine eve->clj's IsEve arm
        ;; exists to avoid.
        (when (satisfies? d/IPlain x)
          (assert-portable! sio (d/-plain x) path))
        (when-let [m (e/meta x)] (assert-portable! sio m (conj path :meta))))

    (map? x)
    (doseq [[k v] x]
      (assert-portable! sio k (conj path :key))
      (assert-portable! sio v (conj path k)))

    (or (set? x) (sequential? x))
    (doseq [[i v] (map-indexed vector x)] (assert-portable! sio v (conj path i)))

    :else
    (do
      ;; ifn? is a lookup protocol, not callability: symbols and keywords
      ;; implement it and are data. Only a value that is invocable as a
      ;; function and is not one of those data types is refused here
      ;; (a plain fn is already refused by the fn? check above; this net
      ;; catches the fn values a host's fn? does not recognize).
      (when (and (ifn? x) (not (keyword? x)) (not (symbol? x)))
        (throw (ex-info "functions can't be stored in Eve memory; pass them as call arguments"
                        {:eve.record/error :function :path path})))
      (try
      (binding [ser/*shared-write* true] (codec/encode-key x))
      (catch #?(:cljs :default :lpy python/Exception :default Exception) _
        (fail! path "private REF/host value is forbidden")))))
  x)

(defn normalize
  "Normalize a host collection into this slab domain. Keep an existing,
   validated same-domain Eve reference; never copy it to a foreign domain."
  [sio x path]
  (assert-portable! sio x path)
  (binding [ser/*shared-write* true context/*slab-values* true]
    (if (or (satisfies? d/IEveRoot x)
            (not (or (map? x) (set? x) (sequential? x))))
      x
      (codec/decode sio (codec/encode-value sio x)))))

(defn entries [r]
  (concat (map (fn [k v] (encoding/map-entry k v))
               (-declared-keys r) (-record-values r))
          (e/seq (-extras r))))

(defn lookup [r k not-found]
  (let [i (first (keep-indexed (fn [i key] (when (= key k) i)) (-declared-keys r)))]
    (if (nil? i)
      (binding [context/*slab-values* true] (e/get (-extras r) k not-found))
      (nth (-record-values r) i))))

(defn contains-key? [r k]
  (or (boolean (some #(= k %) (-declared-keys r))) (e/contains? (-extras r) k)))

(defn entry-at [r k]
  (when (contains-key? r k) (encoding/map-entry k (lookup r k nil))))

(defn as-map [r]
  (reduce (fn [m entry] (e/assoc m (key entry) (val entry)))
          (em/empty-hash-map (d/-eve-sio r)) (entries r)))

(defn assoc-record [r k v]
  (let [ks (-declared-keys r)
        i (first (keep-indexed (fn [i key] (when (= key k) i)) ks))
        sio (d/-eve-sio r)
        v (normalize sio v [k])]
    (if (nil? i)
      (let [k (normalize sio k [:key])]
        (-copy-slot r (count ks) (e/assoc (-extras r) k v)))
      (-copy-slot r i v))))

(defn dissoc-record [r k]
  (if (some #(= k %) (-declared-keys r))
    (e/dissoc (as-map r) k)
    (if (e/contains? (-extras r) k)
      (-copy-slot r (count (-declared-keys r)) (e/dissoc (-extras r) k))
      r)))

(defn with-record-meta [r m]
  (when-not (or (nil? m) (map? m) (em/eve-hash-map? m))
    (fail! [:meta] "metadata must be a map"))
  (-copy-slot r (inc (count (-declared-keys r)))
              (normalize (d/-eve-sio r) m [:meta])))

(defn conj-record [r x]
  (if (or (map? x) (record? x))
    (reduce (fn [r kv] (assoc-record r (key kv) (val kv))) r (e/seq x))
    (assoc-record r (e/nth x 0) (e/nth x 1))))

(defn equiv-record [r other]
  (and (record? other) (= (type-key r) (type-key other))
       ;; Equality is over portable data, not the host wrappers used by a
       ;; collection adapter (SCI cannot implement native IPersistentVector).
       (= (canonical (-record-values r)) (canonical (-record-values other)))
       (= (canonical (-extras r)) (canonical (-extras other)))))

(defn canonical [x]
  (cond
    (record? x) [:eve.record/value (type-key x)
                 (mapv canonical (-record-values x)) (canonical (-extras x))]
    (and (satisfies? d/IEveRoot x) (satisfies? d/IPlain x)) (canonical (d/-plain x))
    (map? x) (into {} (map (fn [[k v]] [(canonical k) (canonical v)])) x)
    (set? x) (into #{} (map canonical) x)
    (sequential? x) (mapv canonical x)
    :else x))

(defn hash-record [r]
  ;; Type identity is part of the content; metadata never is. Canonical key
  ;; bytes, NOT host hash, are identical on every host and independent of slab offsets.
  (hu/portable-hash-bytes (codec/encode-key (canonical r))))

(defn print-record [r]
  (str "#" (type-key r)
       (pr-str (into {} (map (fn [kv] [(key kv) (d/eve->clj (val kv))])) (entries r)))))

(defn copy-slot!
  "Path-copy only the record header and changed slot. All untouched serialized
   payload offsets (including extmap and meta) are shared. Adoption by tally
   follows eve-deftype's existing type-refs, including both hidden fields."
  [r total-size specs slot value]
  (let [sio (d/-eve-sio r)
        spec (nth specs slot)
        offset (:offset spec)
        ;; Encode/validate before allocating the new record header.
        value (normalize sio value [slot])
        new-off (slab/alloc! sio total-size)]
    (slab/write-bytes! sio new-off 0
                       (slab/read-bytes sio (d/-root-header-off r) 0 total-size))
    (ser/stored-piece! sio)
    (case (:type-class spec)
      :serialized (binding [ser/*shared-write* true]
                    (runtime/slab-write-serialized! sio new-off offset value))
      :primitive (case (:type-hint spec)
                   (:int8 :uint8) (slab/write-u8! sio new-off offset (int value))
                   (:int16 :uint16) (slab/write-u16! sio new-off offset (int value))
                   (:int32 :uint32) (slab/write-i32! sio new-off offset (unchecked-int value))
                   :float32 (slab/write-bytes! sio new-off offset (encoding/f32-le-bytes value))
                   :float64 (slab/write-bytes! sio new-off offset (encoding/f64-le-bytes value))))
    ((ser/get-header-constructor (slab/read-u8 sio new-off 0)) sio new-off)))

(defn install-record-io!
  "Register nominal record layout and its map->T EDN reader in Eve's existing
   per-type entry (e1b API). The reader preserves extension fields. No new registry."
  [type-key host-type keys reader]
  (registry/register-type! type-key
    {:type-key type-key :host-type host-type :record/portable? true :record/keys keys})
  ;; Decode the fixed header's cached fields as slab views. In particular bb's
  ;; ordinary collection reader otherwise materializes whole host vectors.
  (let [id (registry/type-id-of type-key)
        name-hash (registry/name-hash-of type-key)
        factory (get-in @ser/deftype-constructors [id name-hash])]
    (ser/register-deftype-constructor! id name-hash
      (fn [sio off]
        (binding [context/*slab-values* true] (factory sio off)))))
  (readers/register! type-key (symbol type-key) reader
                     {:mode :protocol :protocol 'IPrintWithWriter :method '-pr-writer}))

(defmacro defrecord
  "Define an immutable slab-backed nominal record. ^:host/mutable/raw pointer
   fields are forbidden. Public ->T and map->T normalize/validate at the boundary.
   A leading explicit sio is supported by ->T-in and map->T-in; otherwise the
   current swap!'s domain is used. Additional protocol implementations may follow."
  [type-name fields & body]
  (when-not (vector? fields) (throw (ex-info "record fields must be a vector" {})))
  (when (or (not= (count fields) (count (set (map name fields))))
            (some #{"record-extmap__" "record-meta__"} (map name fields)))
    (throw (ex-info "Duplicate or reserved record field" {:fields fields})))
  (doseq [f fields]
    (when-not (symbol? f) (throw (ex-info "record field must be a symbol" {:field f})))
    (let [m (meta f)]
      (when (or (:host m) (:mutable m) (:volatile-mutable m) (:ref m) (:eve/pointer m))
        (throw (ex-info "Eve record fields cannot be host-local, mutable or raw pointers" {:field f})))
      (let [spec (registry/parse-field f)]
        (when (= :eve-type (:type-class spec))
          (throw (ex-info "Record references use unhinted fields (domain-checked), not unchecked offsets" {:field f}))))))
  (let [ns-name (str (or (get-in &env [:ns :name]) (ns-name *ns*)))
        tk (str ns-name "/" (name type-name))
        ks (mapv #(keyword (name %)) fields)
        fs (mapv #(symbol (name %)) fields)
        hidden ['record-extmap__ 'record-meta__]
        all-fields (into fields hidden)
        layout (registry/compute-layout (mapv registry/parse-field all-fields))
        specs (mapv #(select-keys % [:offset :type-class :type-hint]) (:fields layout))
        ctor (symbol (str "->" type-name))
        raw (symbol (str type-name "__slab-constructor"))
        ctor-in (symbol (str "->" type-name "-in"))
        mapctor (symbol (str "map->" type-name))
        mapctor-in (symbol (str "map->" type-name "-in"))
        this (gensym "this") other (gensym "other")
        k (gensym "key") val (gensym "value") nf (gensym "not-found")
        sio (gensym "sio") m (gensym "map")]
    `(do
       (declare ~raw ~ctor-in ~mapctor ~mapctor-in)
       (eve.deftype/eve-deftype ~type-name ~all-fields
         IRecordData
         (~'-declared-keys [~this] ~ks)
         (~'-record-values [~this] [~@fs])
         (~'-extras [~this] ~'record-extmap__)
         (~'-record-meta [~this] ~'record-meta__)
         (~'-copy-slot [~this ~k ~val] (copy-slot! ~this ~(:total-size layout) '~specs ~k ~val))
         ~'clojure.lang.ILookup
         (~'valAt [~this ~k] (lookup ~this ~k nil))
         (~'valAt [~this ~k ~nf] (lookup ~this ~k ~nf))
         ~'clojure.lang.Associative
         (~'containsKey [~this ~k] (contains-key? ~this ~k))
         (~'assoc [~this ~k ~val] (assoc-record ~this ~k ~val))
         ~'clojure.lang.IPersistentMap
         (~'entryAt [~this ~k] (entry-at ~this ~k))
         (~'without [~this ~k] (dissoc-record ~this ~k))
         (~'assocEx [~this ~k ~val]
           (if (contains-key? ~this ~k)
             (throw (ex-info "Key already present" {:key ~k}))
             (assoc-record ~this ~k ~val)))
         ~'clojure.lang.Counted
         (~'count [~this] (+ ~(count fields) (e/count ~'record-extmap__)))
         ~'clojure.lang.Seqable
         (~'seq [~this] (seq (entries ~this)))
         ~'java.lang.Iterable
         (~'iterator [~this] (~'clojure.lang.SeqIterator. (seq (entries ~this))))
         ~'clojure.lang.IPersistentCollection
         (~'cons [~this ~val] (conj-record ~this ~val))
         (~'empty [~this] (em/empty-hash-map (d/-eve-sio ~this)))
         (~'equiv [~this ~other] (equiv-record ~this ~other))
         ~'clojure.lang.IMeta
         (~'meta [~this] ~'record-meta__)
         ~'clojure.lang.IObj
         (~'withMeta [~this ~m] (with-record-meta ~this ~m))
         ~'clojure.lang.IHashEq
         (~'hasheq [~this] (hash-record ~this))
         d/IsEve
         (~'-eve? [~this] true)
         ~'IPrintWithWriter
         (~'-pr-writer [~this w# opts#] (e/-write w# (print-record ~this)))
         ~'java.lang.Object
         (~'equals [~this ~other] (equiv-record ~this ~other))
         (~'hashCode [~this] (hash-record ~this))
         (~'toString [~this] (print-record ~this))
         ~@body)
       ;; Keep the private slab factory before rebinding the public constructor.
       (def ~(with-meta raw {:private true}) ~ctor)
       (defn ~ctor-in [~sio ~@fs]
         (let [~@(mapcat (fn [f key] [f `(normalize ~sio ~f [~key])]) fs ks)]
           (binding [d/*domain-sio* ~sio ser/*shared-write* true]
             (~raw ~sio ~@fs (em/empty-hash-map ~sio) nil))))
       (defn ~ctor [~@fs] (~ctor-in (alloc/current-sio) ~@fs))
       (defn ~mapctor-in [~sio ~m]
         (assert-portable! ~sio ~m [:constructor])
         (binding [d/*domain-sio* ~sio ser/*shared-write* true]
           (~raw ~sio
             ~@(map (fn [key] `(normalize ~sio (e/get ~m ~key) [~key])) ks)
             (normalize ~sio (reduce (fn [extras# kv#]
                                      (if (contains? ~(set ks) (key kv#)) extras#
                                        (e/assoc extras# (key kv#) (val kv#))))
                                    (em/empty-hash-map ~sio) (e/seq ~m)) [:extmap])
             (normalize ~sio (e/meta ~m) [:meta]))))
       (defn ~mapctor [~m] (~mapctor-in (alloc/current-sio) ~m))
       (install-record-io! ~tk ~type-name ~ks ~mapctor)
       ~type-name)))
