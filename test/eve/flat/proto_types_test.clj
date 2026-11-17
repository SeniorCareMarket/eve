(ns eve.flat.proto-types-test
  "Plan §4, slices M0+M1+M2: FIELD-ORIENTED proto types — defined with
   eve.deftype-proto.macros/deftype (the proto engine, not
   eve.deftype) — and the flat wiring M1+M2 build into that macro:
   the sister descriptor, the generated <TypeName>? predicate, the
   registration parity (:name-hash, :type-tag), the IFreezeThaw
   methods, and (M2) the generated flat sister class itself: packed
   values open as the type's own sister, its methods run against
   the field source, and its ^:cached fields are rebuilt by their
   derivations (plan §3(b)(i)).

   The fixtures are the plan's M0 discriminators:
     PrimRec   — all-primitive, with ^:int16 fields: under proto
                 layout arithmetic the second field sits at offset 6,
                 under eve.deftype's padded arithmetic at 8 (§3(a)).
                 The descriptor's offsets, and field reads by them,
                 prove which arithmetic the machinery consumed.
     SerRec    — a :serialized field beside a primitive.
     CachedRec — EveArray-shaped: a slab field plus ^:cached fields
                 (zero slab space, carried on the instance). The
                 descriptor carries the slab field alone; the sister
                 rebuilds the immutable cached field by derivation
                 and starts the mutable memo nil (§3(b)(i), M2).
     MethRec   — a real method (Counted) reading two slab fields:
                 on the sister the method runs against the source.

   Instances are built the way the collections build theirs
   (eve.list's write-list-header! pattern): allocate a block in a
   heap domain, write the header byte and the fields at their
   layout offsets, wrap [sio offset] in the type. IEveRoot/IEveSio
   are supplied in the body, as EveVector's own body supplies them."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.macros :as pm]
            [eve.deftype-proto.macros.registry :as pm-reg]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.deftype.registry :as dt-reg]
            [eve.deftype.slab-runtime :as slab-runtime]
            [eve.slab-io :as slab-io]
            [eve.atom :as ea]
            [eve.host :as host]
            [eve.flat.chunk :as chunk]
            [eve.flat.freeze]
            [eve.flat.sister :as sister]
            [eve.flat.source :as source]))

;; ---------------------------------------------------------------------------
;; the fixture types
;; ---------------------------------------------------------------------------

(pm/deftype PrimRec [^:int16 a ^:int16 b ^:int32 c]
  d/IEveRoot
  (d/-root-header-off [_] offset__)
  d/IEveSio
  (d/-eve-sio [_] sio__)
  ;; the plain view every materializing type supplies in its body
  ;; (Dataset's and EveArray's pattern): eve->clj — and with it the
  ;; deref opt-outs — answer the field map. The macro emits no
  ;; default IPlain, deliberately (eve.record's certification
  ;; note). The field locals read the slab on the persistent kind
  ;; and the packed source on the sister, so the one body is
  ;; correct on both kinds.
  d/IPlain
  (d/-plain [_] {:a (d/eve->clj a) :b (d/eve->clj b) :c (d/eve->clj c)}))

(pm/deftype SerRec [^:int32 n note]
  d/IEveRoot
  (d/-root-header-off [_] offset__)
  d/IEveSio
  (d/-eve-sio [_] sio__))

(defn derive-marker
  "CachedRec's ^:cached derivation (plan §3(b)(i)): the marker is
   rebuilt from the packed source — here, from the cnt field — when
   a sister is built. It is never a packed value itself."
  [src]
  [:derived-from (source/-field src :cnt)])

(pm/deftype CachedRec [^:int32 cnt ^{:cached true :derive derive-marker} marker__ ^:cached ^:mutable memo__]
  d/IEveRoot
  (d/-root-header-off [_] offset__)
  d/IEveSio
  (d/-eve-sio [_] sio__))

(pm/deftype MethRec [^:int32 x ^:int32 y]
  d/IEveRoot
  (d/-root-header-off [_] offset__)
  d/IEveSio
  (d/-eve-sio [_] sio__)
  clojure.lang.Counted
  (count [_] (+ x y)))

(def ^:private prim-key "eve.flat.proto-types-test/PrimRec")
(def ^:private ser-key "eve.flat.proto-types-test/SerRec")
(def ^:private cached-key "eve.flat.proto-types-test/CachedRec")
(def ^:private meth-key "eve.flat.proto-types-test/MethRec")

;; ---------------------------------------------------------------------------
;; instance construction (the collections' pattern: alloc + header + fields)
;; ---------------------------------------------------------------------------

(defn- prim-rec [sio a b c]
  ;; proto layout: a@4 (2 bytes), b@6 (2 bytes), c@8 (4 bytes); total 12
  (let [off (slab-io/alloc! sio 12)]
    (slab-io/write-u8! sio off 0 PrimRec-type-id)
    (slab-io/write-u16! sio off 4 (bit-and a 0xFFFF))
    (slab-io/write-u16! sio off 6 (bit-and b 0xFFFF))
    (slab-io/write-i32! sio off 8 c)
    (PrimRec. sio off)))

(defn- ser-rec [sio n note]
  ;; proto layout: n@4 (4 bytes), note@8 (serialized slot); total 12
  (let [off (slab-io/alloc! sio 12)]
    (slab-io/write-u8! sio off 0 SerRec-type-id)
    (slab-io/write-i32! sio off 4 n)
    (slab-runtime/slab-write-serialized! sio off 8 note)
    (SerRec. sio off)))

(defn- cached-rec [sio cnt marker memo]
  ;; proto layout: cnt@4 (4 bytes); the ^:cached fields take no slab
  ;; space and ride the instance, in declaration order after [sio off]
  (let [off (slab-io/alloc! sio 8)]
    (slab-io/write-u8! sio off 0 CachedRec-type-id)
    (slab-io/write-i32! sio off 4 cnt)
    (CachedRec. sio off marker memo)))

(defn- cached-memo
  "The value of CachedRec's ^:mutable memo__ on a persistent
   instance. Clojure deftype emits mutable fields package-private
   (stock semantics — measured on a bare clojure.core/deftype
   control), so a .-memo__ read cannot resolve the field
   reflectively; read the declared field. (On the sister the
   same field is public final, and .-memo__ reads it directly.)"
  [inst]
  (let [f (.getDeclaredField CachedRec "memo__")]
    (.setAccessible f true)
    (.get f inst)))

(defn- meth-rec [sio x y]
  ;; proto layout: x@4, y@8 (4 bytes each); total 12
  (let [off (slab-io/alloc! sio 12)]
    (slab-io/write-u8! sio off 0 MethRec-type-id)
    (slab-io/write-i32! sio off 4 x)
    (slab-io/write-i32! sio off 8 y)
    (MethRec. sio off)))

(defn- a-sio [] (alloc/heap-domain-sio! {}))

;; ---------------------------------------------------------------------------
;; M1: the descriptor, from the proto registry's own layout
;; ---------------------------------------------------------------------------

(deftest proto-descriptor-registered
  (let [desc (sister/descriptor prim-key)]
    (is (some? desc) "no sister descriptor was registered for a proto type")
    (is (some? (:factory desc)) "a proto type's factory is nil (M2: the sister class is realized on this host)")
    (is (= :proto-deftype (:generated-by desc)))
    (is (= ["a" "b" "c"] (mapv :name (:fields desc))))
    (testing "the offsets are the PROTO layout's: b at 6, not eve.deftype's padded 8"
      (is (= [4 6 8] (mapv :offset (:fields desc)))))
    (is (= (:fingerprint desc)
           (sister/layout-fingerprint prim-key (:fields desc)))
        "the registered fingerprint does not recompute from the descriptor's fields"))
  (let [desc (sister/descriptor ser-key)]
    (is (some? desc))
    (is (= ["n" "note"] (mapv :name (:fields desc))))
    (is (= [4 8] (mapv :offset (:fields desc))))
    (is (= :serialized (:type-class (second (:fields desc))))))
  (testing "a ^:cached field is not in the descriptor: it is not written to the slab"
    (let [desc (sister/descriptor cached-key)]
      (is (some? desc))
      (is (= ["cnt"] (mapv :name (:fields desc))))
      (is (= [4] (mapv :offset (:fields desc)))))))

(deftest proto-registration-parity
  (testing "the load-time registration carries :name-hash and :type-tag"
    (let [entry (dt-reg/lookup-type prim-key)]
      (is (= (dt-reg/name-hash-of prim-key) (:name-hash entry)))
      (is (= :slab (:storage entry)))
      (is (= PrimRec-type-id (:type-id entry)))))
  (testing "the compile-time registry carries them too"
    (let [entry (pm-reg/lookup-type "PrimRec")]
      (is (= (dt-reg/name-hash-of prim-key) (:name-hash entry)))
      (is (= "eve/PrimRec" (:type-tag entry))))))

;; ---------------------------------------------------------------------------
;; M1: the generated predicate — both forms answer to the type's name
;; ---------------------------------------------------------------------------

(deftest proto-predicate
  (let [sio (a-sio)
        p (prim-rec sio 1 2 3)
        s (ser-rec sio 4 "x")
        c (cached-rec sio 5 :m nil)]
    (is (PrimRec? p))
    (is (SerRec? s))
    (is (CachedRec? c))
    (is (not (PrimRec? s)))
    (is (not (PrimRec? nil)))
    (is (not (PrimRec? {})))))

;; ---------------------------------------------------------------------------
;; M1: field reads by the descriptor — the ^:int16 offset proof — and the
;; packed form: an instance packs by its descriptor and reads back as a
;; generic record (nil factory)
;; ---------------------------------------------------------------------------

(deftest proto-field-values-and-pack
  (let [sio (a-sio)
        p (prim-rec sio -7 1234 987654)
        desc (sister/descriptor prim-key)]
    (is (= {:a -7 :b 1234 :c 987654} (sister/field-values desc p))
        "field reads by the descriptor's offsets are torn — the wrong layout arithmetic")
    (testing "the instance packs by its descriptor and opens as its sister (M2)"
      (let [rec (chunk/open (chunk/chunk-bytes (chunk/pack p)))]
        (is (not (chunk/generic-record? rec)))
        (is (= prim-key (d/type-key-of rec)))
        (is (= {:a -7 :b 1234 :c 987654}
               (source/-field-map (source/-sister-source rec)))))))
  (testing "a serialized field round-trips through field-values and the pack"
    (let [sio (a-sio)
          s (ser-rec sio 5 "hello")
          desc (sister/descriptor ser-key)]
      (is (= {:n 5 :note "hello"} (sister/field-values desc s)))
      (let [rec (chunk/open (chunk/chunk-bytes (chunk/pack s)))]
        (is (not (chunk/generic-record? rec)))
        (is (= {:n 5 :note "hello"}
               (source/-field-map (source/-sister-source rec)))))))
  (testing "a cached field rides the instance, not the slab or the descriptor"
    (let [sio (a-sio)
          c (cached-rec sio 3 :marker-1 nil)
          desc (sister/descriptor cached-key)]
      (is (= {:cnt 3} (sister/field-values desc c)))
      (is (= :marker-1 (.-marker__ c))))))

;; ---------------------------------------------------------------------------
;; M1: the IFreezeThaw wiring on the persistent kind
;; ---------------------------------------------------------------------------

(deftest proto-freeze-thaw-wiring
  (let [sio (a-sio)
        p (prim-rec sio -7 1234 987654)]
    (is (satisfies? sister/IFreezeThaw p)
        "the proto type does not carry the generated IFreezeThaw methods")
    (testing "-freeze routes to eve.flat.freeze's writer and the frozen
              value's content packs back to the same fields"
      (let [fv (sister/freeze (a-sio) p)
            rec (chunk/open (chunk/chunk-bytes (chunk/pack fv)))]
        (is (some? fv))
        (is (not (chunk/generic-record? rec)))
        (is (= {:a -7 :b 1234 :c 987654}
               (source/-field-map (source/-sister-source rec))))))))

;; ---------------------------------------------------------------------------
;; M2: the generated sister class — packed values open as the type's
;; own flat kind; methods run against the source; ^:cached fields are
;; rebuilt by derivation, never packed
;; ---------------------------------------------------------------------------

(deftest proto-sister-class
  (let [sio (a-sio)
        p (prim-rec sio -7 1234 987654)
        rec (chunk/open (chunk/chunk-bytes (chunk/pack p)))]
    (testing "a packed proto value opens as its generated sister"
      (is (instance? PrimRec__Flat rec))
      (is (satisfies? source/ISister rec))
      (is (PrimRec? rec) "the generated predicate answers for the sister too")
      (is (= (.-src__ ^PrimRec__Flat rec) (source/-sister-source rec))))
    (testing "sister equality and hash are by type key and fields"
      (let [rec2 (chunk/open (chunk/chunk-bytes (chunk/pack p)))
            other (chunk/open (chunk/chunk-bytes (chunk/pack (prim-rec sio -7 1234 1))))]
        (is (= rec rec2))
        (is (= (hash rec) (hash rec2)))
        (is (not= rec other))))
    (testing "the sister prints by its field map"
      (is (= "#eve.flat/PrimRec {:a -7, :b 1234, :c 987654}" (str rec)))))
  (testing "a sister's methods run against the source (MethRec count reads x and y)"
    (let [sio (a-sio)
          m (meth-rec sio 30 12)
          rec (chunk/open (chunk/chunk-bytes (chunk/pack m)))]
      (is (some? (:factory (sister/descriptor meth-key))))
      (is (instance? MethRec__Flat rec))
      (is (= 42 (count m)) "the persistent kind's own count")
      (is (= 42 (count rec)) "the sister's count, read from the packed source")))
  (testing "the sister carries the IFreezeThaw pair and freezes back to the same fields"
    (let [sio (a-sio)
          p (prim-rec sio -7 1234 987654)
          rec (chunk/open (chunk/chunk-bytes (chunk/pack p)))]
      (is (satisfies? sister/IFreezeThaw rec))
      (let [fv (sister/freeze (a-sio) rec)
            rec2 (chunk/open (chunk/chunk-bytes (chunk/pack fv)))]
        (is (= {:a -7 :b 1234 :c 987654}
               (source/-field-map (source/-sister-source rec2))))))))

(deftest proto-sister-cached-derivation
  (let [sio (a-sio)
        c (cached-rec sio 3 :marker-1 :memo-1)
        desc (sister/descriptor cached-key)]
    (testing "the descriptor records the derivation; the cached fields stay unpacked"
      (is (= ["cnt"] (mapv :name (:fields desc))))
      (is (= {:marker__ derive-marker} (:cached-derivations desc))))
    (let [rec (chunk/open (chunk/chunk-bytes (chunk/pack c)))]
      (is (instance? CachedRec__Flat rec))
      (testing "the sister's immutable cached field is the derivation's answer over the packed source"
        (is (= [:derived-from 3] (.-marker__ rec))))
      (testing "the mutable cached memo starts nil on the sister — it is not packed either"
        (is (nil? (.-memo__ rec))))
      (testing "the persistent instance's own cached values are untouched"
        (is (= :marker-1 (.-marker__ c)))
        (is (= :memo-1 (cached-memo c)))))))

;; ---------------------------------------------------------------------------
;; M4: the construction law — the generated ->T with no domain bound
;; produces the flat sister; with a domain bound (or a leading sio)
;; the slab instance — and the from-header constructor atom roots
;; and nested reads dispatch to
;; ---------------------------------------------------------------------------

(deftest proto-construction-law
  (testing "no domain bound: the flat sister, by the generated ->T"
    (let [r (->PrimRec -7 1234 987654)]
      (is (instance? PrimRec__Flat r))
      (is (PrimRec? r))
      (is (= {:a -7 :b 1234 :c 987654}
             (source/-field-map (source/-sister-source r)))))
    (let [r (->MethRec 30 12)]
      (is (instance? MethRec__Flat r))
      (is (= 42 (count r))))
    (let [r (->SerRec 5 "hello")]
      (is (instance? SerRec__Flat r))
      (is (= {:n 5 :note "hello"}
             (source/-field-map (source/-sister-source r)))))
    (testing "the flat route's cached arguments are not packed: the
              sister derives the immutable one and starts the memo nil"
      (let [r (->CachedRec 3 :not-packed :not-packed)]
        (is (instance? CachedRec__Flat r))
        (is (= [:derived-from 3] (.-marker__ r)))
        (is (nil? (.-memo__ r))))))
  (testing "a domain bound: the slab instance, in that domain"
    (let [sio (a-sio)]
      (binding [d/*domain-sio* sio]
        (let [p (->PrimRec -7 1234 987654)]
          (is (instance? PrimRec p))
          (is (= {:a -7 :b 1234 :c 987654}
                 (sister/field-values (sister/descriptor prim-key) p)))))))
  (testing "the leading-sio arity builds in that sio; cached values ride it"
    (let [sio (a-sio)
          p (->PrimRec sio -7 1234 987654)
          c (->CachedRec sio 3 :marker-1 :memo-1)]
      (is (instance? PrimRec p))
      (is (= sio (d/-eve-sio p)))
      (is (= {:a -7 :b 1234 :c 987654}
             (sister/field-values (sister/descriptor prim-key) p)))
      (is (= :marker-1 (.-marker__ c)))
      (is (= :memo-1 (cached-memo c))))))

(deftest proto-from-header-registration
  (testing "the header constructor atom roots dispatch to is registered"
    (is (some? (ser/get-header-constructor PrimRec-type-id))))
  (let [sio (a-sio)
        p (->PrimRec sio -7 1234 987654)
        q (PrimRec__from-header sio (d/-root-header-off p))]
    (is (instance? PrimRec q))
    (is (= {:a -7 :b 1234 :c 987654}
           (sister/field-values (sister/descriptor prim-key) q))))
  (testing "a derived ^:cached field is rebuilt from the block's field
            values; an underived one starts nil"
    (let [sio (a-sio)
          c (->CachedRec sio 3 :marker-1 :memo-1)
          q (CachedRec__from-header sio (d/-root-header-off c))]
      (is (= [:derived-from 3] (.-marker__ q)))
      (is (nil? (cached-memo q))))))

;; ---------------------------------------------------------------------------
;; M4: the deref law — an atom holding a proto-typed value delivers
;; the flat form (R5/R11/R12). There is no plain opt-out: deref
;; delivers the flat form on every host, and materialization is the
;; caller's explicit eve->clj (operator ruling, 2026-11-16; the
;; set-deref-plain! / *deref-plain* opt-outs are removed).
;; ---------------------------------------------------------------------------

(deftest proto-atom-deref-delivery
  (let [a (ea/atom ::proto-m4-deref nil)]
    (host/swap! a (fn [_] (->PrimRec -7 1234 987654)))
    (testing "deref delivers the flat sister"
      (let [dv @a]
        (is (instance? PrimRec__Flat dv))
        (is (PrimRec? dv))
        (is (= {:a -7 :b 1234 :c 987654}
               (source/-field-map (source/-sister-source dv))))))))
