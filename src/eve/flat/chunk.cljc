(ns eve.flat.chunk
  "The packed flat form (FORMAT-V1.md): one value as one 64 KiB-target
   chunk of bytes — header, symbol table, shape table, type table, then
   nodes with children before their parents and the root last — read in
   place by the handle types in this namespace, never by whole-chunk
   decode. Lane C1 of doc/flat/IMPL-PLAN.md, with lane X1's
   ClojureScript side transplanted into this same file: ONE
   implementation in .cljc (operator directive, 2026-11-12). Every read
   and write of a chunk's bytes goes through eve.flat-io, the byte seam
   (doc/flat/FLAT-IO.md; a root per host — flat lanes U2a moved this
   file's byte layer into adapters/flat-io/portable); the host
   differences left here (BigInt i64 math, the handle protocol
   surfaces) live in reader conditionals, and the byte layout is the
   host-independent contract of FORMAT-V1 §2–5 — one value packs to the
   same bytes on the JVM and on Node (X1.2)."
  (:refer-clojure :exclude [bytes])
  (:require #?(:clje [eve.host :refer [long double bytes shorts ints longs floats doubles objects booleans]])
            [eve.codec :as codec]
            [eve.encoding :as enc]
            #?(:clj [eve.crypto :as crypto]
               :cljs [eve.crypto :as crypto]
               :lg [eve.crypto :as crypto]
               :lpy [eve.crypto :as crypto]
               :cljr [eve.crypto :as crypto]
               :clje [eve.crypto :as crypto])
            #?(:clje [eve.host :refer [long alength]])
            [eve.deftype-proto.data :as d]
            #?@(:cljr [[eve.types :as etypes]
                       [eve.deftype-proto.vocab :as dtv]])
            [eve.flat.source :as source]
            ;; every read and write of a chunk's bytes (doc/flat/FLAT-IO.md):
            ;; the byte layer that stood in this file moved there (flat lanes U2a)
            [eve.flat-io :as fio]
            #?(:jolt [jolt.scheme :as scheme])
            [eve.platform :as platform]
            #?(:cljs [eve.flat.pack-plain :as pack-plain])
            ;; The protocol hosts (bb, glojure) spell a handle's surface
            ;; through the seat's eve.core protocols — the same surface
            ;; eve's other types use there. Their eve.core is the
            ;; coll/protocols adapter, which nothing in this namespace's
            ;; load path cycles back through.
            #?@(:bb [[eve.core :as ecore]]
                :glj [[eve.core :as ecore]]
                :jank [[eve.core :as ecore]]
                :lg [[eve.core :as ecore]])
            ;; squint: this file is SCI-evaluated in macro cones there,
            ;; and SCI resolves a deftype's protocol names from the
            ;; current ns's refers — eve.core's, whose SCI face carries
            ;; the protocols as defprotocol stubs and whose runtime
            ;; face aliases squint core's own protocol objects (the
            ;; adapters/codec/squint shape). The cljs faces below keep
            ;; the bare names the ClojureScript compiler requires (a
            ;; protocol named through a def alias is "not a protocol"
            ;; to its analyzer, measured); staging keeps this refer
            ;; for squint only, so no other host's reader sees it.
            #?@(:squint [[eve.core :refer [ICounted ILookup IAssociative
                                           ISeqable ISequential IVector
                                           IEquiv IHash IMeta IWithMeta
                                           IPrintWithWriter IStack
                                           ICollection IEmptyableCollection
                                           IReduce IFn IMap ISet IList
                                           -lookup]]]
                :default []))
  #?(:clj (:import [java.util Arrays HashMap ArrayList])
     :lpy (:import eve_flat_pack_plain)))

#?(:clj (set! *warn-on-reflection* true))

#?(;; jank and glojure read the :clj arm but have no Class/forName (jank: the
   ;; symbol does not resolve; glojure: EvalASTMaybeHostForm), so the def lives
   ;; on the JVM-class hosts only; host-bytes? answers through their own arms.
   :jank nil
   :glj nil
   :clj (def ^:private byte-array-class
          ;; the JVM's [B, looked up once (Class/forName per call was a name lookup per value written or read)
          (Class/forName "[B")))

(defn- host-bytes?
  "x is a host byte array (the JVM's [B): the test the flat writer and
   reader use wherever a value itself might be chunk bytes. Jank has
   no Class/forName: its byte arrays are the completion's arrays whose
   eve.encoding subtype is 2 (the byte kinds), discriminated through
   the portable encoding surface."
  [x]
  #?(:jank (boolean (and (enc/host-array? x)
                         (let [sb (enc/array-subtype+bytes x)]
                           (and sb (= 2 (nth sb 0))))))
     ;; glojure's byte arrays are Go []int8, which its [B class does
     ;; not name (measured: unpack of chunk bytes answered the bytes
     ;; themselves) — the encoding subtype discriminates them, as
     ;; on jank above.
     :glj (boolean (and (enc/host-array? x)
                        (let [sb (enc/array-subtype+bytes x)]
                          (and sb (= 2 (nth sb 0))))))
     :clj (instance? byte-array-class x)
     :cljs (instance? js/Uint8Array x)
     ;; ClojureCLR's byte-array is System.Byte[] (subtype 1); SByte is 2.
     :cljr (boolean (and (some? x) (.IsArray (class x))
                         (let [et (str (.GetElementType (class x)))]
                           (or (= et "System.Byte") (= et "System.SByte")))))
     :clje (or (instance? eve.host.ByteArray x)
               (instance? eve.host.ConstByteArray x))
     :default false))

(defn- flat-bytes-of-plain
  "A plain value's flat (eve.codec) bytes — the digest's input for a
   value that is not a chunk node. One name per host codec: the JVM
   codec's value->eve-bytes; the JS codec carries the same bytes
   under serialize-flat-element (its value->eve-bytes throws — the
   X1 finding). The codec adapter's own host variation, surfaced
   once, here."
  [v]
  #?(:cljs (codec/serialize-flat-element v)
     :default (codec/value->eve-bytes v)))

(defn- byte-len
  "The length of a host byte array (a Uint8Array on ClojureScript, whose
   alength is its .length). The ^bytes hint is load-bearing, not style:
   unhinted, this call went reflective under the bb launcher's SCI
   (RT.alength(byte[]) has no GraalVM registration there —
   MissingReflectionRegistrationError, measured in the
   cohost-media demo, 2026-11-16). atom.cljc's digest loop hints
   the same way."
  ^long [bs]
  (alength #?(:clje bs :default ^bytes bs)))

(defn- bytes-range
  "bs[start..end) as a fresh host byte array."
  [bs start end]
  (let [n (- end start) out (enc/make-bytes n)]
    (enc/arraycopy bs start out 0 n)
    out))

;; ---------------------------------------------------------------------------
;; bytes: every read and write of a chunk's bytes goes through eve.flat-io
;; (doc/flat/FLAT-IO.md) — the little-endian reads, the varint, the
;; children-first writer that stood here moved to adapters/flat-io/portable
;; (flat lanes U2a, 2026-11-17), with every host arm they had. A reader holds
;; the chunk (fio/chunk over the host bytes) in the Ctx's b, the host bytes
;; themselves in its bs.
;; ---------------------------------------------------------------------------

;; The number model under the bytes, per host (not bytes: fio reads and
;; writes those). On ClojureScript an i64 does not fit a JS number, so the
;; column planner and the reader's i64 arithmetic run in BigInt space and a
;; cell comes back a JS number while exactly representable (X1.3); on the
;; JVM-class hosts the same names are a long's. Written without js*: nbb's
;; analyzer has no js* (measured: the flat load died "Unable to resolve
;; symbol: js*"), and every form below means the same in compiled
;; ClojureScript — the operators are JS's own on BigInt operands, and the
;; type tag is Object.prototype.toString's. (js/typeof is not the portable
;; spelling either: nbb resolves it to a broken call.)
#?(:cljs (defn- bigint? [x] (= "[object BigInt]" (.call (.-toString (.-prototype js/Object)) x))))
#?(:cljs (defn- bi [x] (if (bigint? x) x (js/BigInt x))))
#?(:cljs (defn- bi< [a b] (< (bi a) (bi b))))
#?(:cljs (defn- bi<= [a b] (<= (bi a) (bi b))))
#?(:cljs (defn- bi= [a b] (= (bi a) (bi b))))
#?(:cljs (defn- bi- [a b] (- (bi a) (bi b))))
#?(:cljs (defn- bi+ [a b] (+ (bi a) (bi b))))
#?(:cljs (defn- host-num [x] (if (bigint? x) (js/Number x) x))
   :default (defn- host-num "A u32 cell's value as the writer takes it: itself." [x] x))
#?(:cljs (def ^:private I64-SAFE (js/BigInt "9007199254740991")))
#?(:cljs (def ^:private I64-SAFE-NEG (js/BigInt "-9007199254740991")))
#?(:cljs (defn- i64->host
           "A BigInt cell as a host value: a JS number while exactly
   representable, the BigInt itself past that (X1.3)."
           [v]
           (if (and (bi<= I64-SAFE-NEG v) (bi<= v I64-SAFE)) (js/Number v) v))
   :default (defn- i64->host "An i64 cell: the long fio read." [v] v))
#?(:cljs (defn- i64-add
           "A narrowed cell (FORMAT-V1 §5 kind 22): base + x, as a host value."
           [base x]
           (i64->host (bi+ base x)))
   :default (defn- i64-add "A narrowed cell (FORMAT-V1 §5 kind 22): base + x." [base x] (+ (long base) (long x))))
#?(:cljs (defn- i64-delta
           "A narrowed cell's stored delta, v - base, as a JS number."
           [v base]
           (js/Number (bi- (bi v) base)))
   :default (defn- i64-delta "A narrowed cell's stored delta, v - base." [v base] (- (long v) (long base))))
#?(:cljs (defn- f32->host "An f32 cell: the JS number fio read." [x] x)
   :default (defn- f32->host
              "An f32 cell as this host's float: a Float on the JVM and bb (fio's f32
   answers it widened to a double, exactly); jank's and glojure's f32 is
   already the value the column always read back."
              [x]
              #?(:jank x :glj x :default (float x))))
#?(:cljs (defn- fp-key
           "A layout fingerprint as a registry / scan key: its decimal string. A
   fingerprint is a js/BigInt here (eve.flat.sister's :cljs
   layout-fingerprint; fio/i64 from a type table), and ClojureScript
   hashes no BigInt by value."
           [fp] (str fp))
   :default (defn- fp-key "A layout fingerprint as a registry / scan key: the long itself." [fp] fp))
#?(:cljs (defn- fp-cell "A fingerprint as the writer's i64 cell: a BigInt." [fp] (bi fp))
   :default (defn- fp-cell "A fingerprint as the writer's i64 cell: a long." [fp] (long fp)))

(defn- off-at ^long [b ^long i wide?]
  (if wide? (fio/u32 b i) (fio/u16 b i)))

;; ---------------------------------------------------------------------------
;; scalars — Eve's bytes without the two magic bytes (FORMAT-V1 §3)
;; ---------------------------------------------------------------------------

(def ^:private MAGIC
  "Eve's two magic bytes, EE DB, as a host byte array (made through the
   writer, so no host spells them). clojerl compiles a def's value into its module as a literal, which a heap
   array is not: there it is held as eve.host's const array of the same bytes."
  (#?(:clje eve.host/const-bytes :default identity)
   (let [w (fio/buf 4)]
     (fio/buf-u8 w 0xEE) (fio/buf-u8 w 0xDB)
     (fio/chunk-bytes (fio/buf-chunk w 2)))))

(defn scalar-body
  "x's Eve bytes without the magic prefix; nil is the single byte 0x00,
   a host byte array the 0x28 node of §3. A reference (tag 0x15) is
   refused. The bytes are the host codec's flat element
   (flat-bytes-of-plain: value->eve-bytes, or on ClojureScript the JS
   codec's serialize-flat-element — the same bytes on every host), so
   scalar identity is Eve's existing cross-host contract."
  ^bytes [x]
  (cond
    (nil? x) (enc/make-bytes 1)
    (host-bytes? x)
    (let [n (byte-len x) w (fio/buf 4)]
      (fio/buf-u8 w 0x28) (fio/buf-varint w n) (fio/buf-bytes w x 0 n)
      (fio/chunk-bytes (fio/buf-chunk w (fio/buf-pos w))))
    :else
    (let [bs (flat-bytes-of-plain x)
          c (fio/chunk bs) n (fio/chunk-len c)
          ;; the flat element carries the two magic bytes; the body
          ;; strips them
          strip (if (and (> n 2) (== 0xEE (fio/u8 c 0)) (== 0xDB (fio/u8 c 1))) 2 0)]
      (when (and (> n strip) (== 0x15 (fio/u8 c strip)))
        (throw (ex-info "flat chunk: a value held by reference cannot be packed"
                        {:type ::unwritable :value x})))
      (bytes-range bs strip n))))

(defn- scalar-len
  "Byte length of the Eve scalar body at i of chunk b (tag + payload)."
  ^long [b ^long i]
  (let [t (fio/u8 b i)]
    (case (int t)
      0x00 1
      (0x01 0x02) 1
      0x03 5
      (0x04 0x0E 0x0F) 9
      (0x05 0x07 0x0C) (+ 2 (fio/u8 b (inc i)))
      (0x06 0x08) (+ 5 (fio/u32 b (inc i)))
      (0x09 0x0D) (let [nl (fio/u8 b (inc i))] (+ 3 nl (fio/u8 b (+ i 2 nl))))
      0x0A (let [nl (fio/u32 b (inc i))] (+ 9 nl (fio/u32 b (+ i 5 nl))))
      0x0B 17
      0x14 3
      0x16 (+ 5 (fio/u32 b (inc i)))
      0x17 (let [n1 (fio/u32 b (inc i))] (+ 9 n1 (fio/u32 b (+ i 5 n1))))
      0x18 (+ 9 (fio/u32 b (+ i 5)))
      0x1B (+ 6 (fio/u32 b (+ i 2)))
      0x28 (let [[n p] (fio/varint b (inc i))] (+ (- p i) n))
      (throw (ex-info "flat chunk: a scalar tag this reader does not size"
                      {:type ::bad-tag :tag t :offset i})))))

(defn- scalar-at
  "The plain value of the scalar body at i of chunk b."
  [b i]
  (let [t (fio/u8 b i)]
    (cond
      (== t 0x00) nil
      (== t 0x28) (let [[n p] (fio/varint b (inc i))]
                    (bytes-range (fio/chunk-bytes b) p (+ p n)))
      :else (let [n (scalar-len b i)
                  bs (enc/make-bytes (+ 2 n))]
              (enc/arraycopy MAGIC 0 bs 0 2)
              (enc/arraycopy (fio/chunk-bytes b) i bs 2 n)
              (codec/decode nil bs)))))

(defn- key-bytes
  "Eve's canonical key bytes of a plain key value (ascending order is
   d/compare-bytes over these)."
  ^bytes [k]
  (codec/encode-key k))

;; ---------------------------------------------------------------------------
;; reps — the writer's input tree. Plain values convert to reps; the chunk
;; handles of part 3 convert back, so re-packing a flat is a rep walk.
;; ---------------------------------------------------------------------------

(deftype BytesVal [^bytes bs])
(deftype TypedVal [tkey fp fields])   ;; fields: array-map field-keyword -> rep, declaration order
#?(:lg (deftype MetaVal [inner meta-rep])
   ;; let-go's extend-type* takes protocols only: an Object section
   ;; does not compile there (measured on lg-eve, 2026-11-22). Its
   ;; default deftype print names the fields, which is what this
   ;; section exists for (reps surface in error messages and debug
   ;; prints); every other host keeps the wrapped value's print.
   ;; Clojerl is the same: Object/toString is not a deftype method
   ;; there (and clojure.core has no long, referred from eve.host).
   :clje (deftype MetaVal [inner meta-rep])
   :default (deftype MetaVal [inner meta-rep]
              ;; A rep carrying metadata prints as the value it wraps: reps surface
              ;; in error messages and debug prints, and the default Object print
              ;; (eve.flat.chunk.MetaVal@hash) hides the value entirely. Basilisp's
              ;; deftype* admits no Object clause — its spelling of the same
              ;; override is a bare __str__ method. ClojureCLR's Object methods
              ;; are the .NET names (ToString, not toString).
              #?@(:lpy [(__str__ [_] (pr-str inner))]
                  :cljr [Object
                         (ToString [_] (pr-str inner))]
                  :default [Object
                            (toString [_] (pr-str inner))])))

(defprotocol IFlatHandle
  (-rep [h] "this handle's value as a rep, metadata included")
  (-chunk-bytes [h] "the bytes of the chunk this handle reads"))

;; jolt: "a clojure.lang collection" — the JVM arm's class-name test — without (class x), .getName and .startsWith
;; (measured on jolt v0.8.28: ~400 ns a collection, the class name a hashed string lookup; string-hash was 7% of a ds
;; find, jseam r6). A jolt built-in collection is a Chez value that is not a jolt record (jrec): every deftype and
;; defrecord instance is one, and their class names are their own, never clojure.lang's — checked equal to the
;; class-name test on 37 values (maps, vectors, sets, lists, seqs, sorted, queue, subvec, map entry, records,
;; deftypes, vector-of, scalars; probe/jseam/src/jseam/hashprobe3.clj). ~40 ns.
#?(:jolt
   (do (scheme/eval-string "(define (eve-not-jrec? x) (not (jrec? x)))")
       (def ^:private not-jrec?* (scheme/proc "eve-not-jrec?"))))

;; THE FAST HANDLE TEST (2026-11-16, the eve-in-VS unit). `satisfies?` on a value whose class does not implement the
;; protocol walks the class's supers on every call (clojure.core/find-protocol-impl -> supers -> a fresh hash set):
;; MEASURED on the JVM (the 1BRC result shape, 407 string keys -> [min sum max count]), 64 % of fc/pack in three such
;; checks run on every key, vector and long. Every eve handle type implements IFlatHandle inline (a deftype body, or the
;; interface named in a generated type's list), so on the JVM its interface answers in one instanceof; a plain scalar or
;; a clojure.lang collection is no handle; anything else asks satisfies? as before. bb, glojure and jank (whose
;; protocols generate no JVM interface) keep satisfies?.
#?(:clj
   (defn- plain-non-handle? [x]
     #?(:jank false :bb false :glj false
        :clj (or (string? x) (number? x) (keyword? x) (symbol? x) (boolean? x) (char? x)
                 (and (instance? clojure.lang.IPersistentCollection x)
                      #?(:jolt (not-jrec?* x)
                         :clj (.startsWith (.getName (class x)) "clojure.lang.")))))))
;; the satisfies? answer of a class no interface answers, remembered per class in an atom (these marker protocols are
;; only ever implemented inline, so a class's answer never changes): the supers walk once a class, not once a value
#?(:clj
   (def ^:private class-answers (atom {})))
#?(:clj
   (defn- class-satisfies? [k p x]
     ;; jolt: satisfies? itself (jolt remembers the answer per type: 20–250 ns measured), where the remembered map
     ;; below hashes [k (class x)] — the class a name-string lookup — at 130–2,000 ns a call (jseam hashprobe4)
     #?(:jolt (satisfies? p x)
        :jank (satisfies? p x) :bb (satisfies? p x) :glj (satisfies? p x)
        :clj (let [c (class x) key [k c] hit (get @class-answers key ::none)]
               (if (identical? hit ::none)
                 (let [r (boolean (satisfies? p x))] (swap! class-answers assoc key r) r)
                 hit)))))
(defn flat-handle?
  "THE FAST HANDLE TEST: x implements IFlatHandle. On the JVM one instanceof for every eve handle type, a per-class
   remembered answer for anything else; satisfies? where protocols generate no JVM interface."
  [x]
  #?(:jank (satisfies? IFlatHandle x) :bb (satisfies? IFlatHandle x) :glj (satisfies? IFlatHandle x)
     :clj (or (instance? eve.flat.chunk.IFlatHandle x)
              (and (not (plain-non-handle? x)) (class-satisfies? :flat-handle IFlatHandle x)))
     :default (satisfies? IFlatHandle x)))

;; The typed-value machinery is the JVM's (class reflection, §6); on
;; ClojureScript, on jank (whose analyzer has no class reflection to
;; read it with) and on glojure (whose record class is not a
;; java.lang.Class) a record packs as its field map in v1: no type
;; table is written on those hosts (the generated sisters are H5's).
#?(:jank (do) :glj (do) :clj (do
(defn- type-key-of
  "The \"ns/Type\" key of a record class (FORMAT-V1 §6)."
  ^String [^Class c]
  (let [n (.getName c) i (.lastIndexOf n ".")]
    (if (neg? i) n (str (subs n 0 i) "/" (subs n (inc i))))))

(defn- record-basis
  "A defrecord class's field keywords in declaration order, or nil."
  [^Class c]
  (try (let [m (.getMethod c "getBasis" (into-array Class []))]
         (vec (.invoke m nil (object-array 0))))
       (catch Throwable _ nil)))

(declare sha-vec utf8-bytes)

(defn- fingerprint
  "The 64-bit layout fingerprint of §6: a hash over the type key, the
   field names and the field value classes, in declaration order.
   The digest goes through eve.crypto (sha-vec) rather than
   java.security.MessageDigest directly: the bytes are the same
   SHA-256 on every host, and a :clj-reading host without a JDK
   provider for MessageDigest (jolt, measured) still answers."
  ^long [tkey fields]
  (let [s (pr-str [tkey (mapv (fn [[k v]] [k (cond (instance? TypedVal v) [:type (.-tkey ^TypedVal v)]
                                                   (instance? BytesVal v) :bytes
                                                   (coll? v) :coll
                                                   :else (class v))]) fields)])
        bs (sha-vec (utf8-bytes s))]
    (loop [i 0 acc 0]
      (if (== i 8) acc (recur (inc i) (bit-or (bit-shift-left acc 8) (nth bs i)))))))
))

(declare to-rep)

#?(:jank nil
   :glj nil
   :clj (def ^:private object-array-class
          ;; the JVM's Object[], looked up once (as byte-array-class; not on jank or glojure, as there)
          (Class/forName "[Ljava.lang.Object;")))

(defn- host-object-array?
  "x is a host object array: the JVM's Object[], a JS array. Jank and
   glojure keep the per-call lookup they had before object-array-class
   (they read :clj but have no top-level object-array-class)."
  [x]
  #?(:jank (instance? (Class/forName "[Ljava.lang.Object;") x)
     ;; glojure has no Class/forName (EvalASTMaybeHostForm, measured:
     ;; eve.record-test / eve.flat.chunk to-rep, 2026-11-24 ET). Object
     ;; and string slices are IsArray and have no numeric subtype.
     :glj (enc/host-object-array? x)
     :clj (instance? object-array-class x)
     :cljs (array? x)
     :default false))

;; Converters for values that are neither plain data nor flat handles:
;; (fn [value] -> rep or nil). The generated sister registers one for
;; eve-deftype instances (eve.flat.sister, lane H5): an instance of a
;; type with a sister registration packs as that type's typed rep.
(defonce ^:private rep-converters (atom []))

(defn register-rep-converter!
  "Register a rep converter, consulted by to-rep (and pack) for any value
   the plain branches do not claim. Answers f."
  [f]
  (swap! rep-converters conj f)
  f)

(defn- convert-rep
  "The first registered converter's rep for v, or nil."
  [v]
  (some (fn [f] (f v)) @rep-converters))

;; Nominal converters: for values whose NOMINAL type is their flat
;; form. A sister-registered record is the case in point: it is
;; map-shaped, so to-rep's plain map branch would pack its content
;; and lose the type before the converter above is ever consulted.
;; A nominal converter is consulted BEFORE the plain collection
;; branches and answers nil for every value it does not claim, so
;; plain data and the collection types pack exactly as they do
;; without one.
(defonce ^:private nominal-rep-converters (atom []))

(defn register-nominal-rep-converter!
  "Register a nominal rep converter, consulted by to-rep before the
   plain collection branches. Answers f."
  [f]
  (swap! nominal-rep-converters conj f)
  f)

(defn- convert-nominal-rep
  "The first registered nominal converter's rep for v, or nil."
  [v]
  (some (fn [f] (f v)) @nominal-rep-converters))

#?(:jank (do) :glj (do) :clj (do
(defn- typed-rep [v]
  (let [c (class v) tkey (type-key-of c)
        ;; a record's field keys are keywords; the class basis names
        ;; them as symbols -- one enumeration, keyword form, basis order
        basis (mapv (fn [f] (keyword (name f))) (or (record-basis c) []))
        fields (reduce (fn [m k] (assoc m k (to-rep (get v k)))) (array-map)
                       (distinct (concat basis (keys v))))]
    (TypedVal. tkey (fingerprint tkey fields) fields)))
))

(defn to-rep
  "A plain value (or a flat handle) as a rep tree."
  [v]
  (let [nom (convert-nominal-rep v)
        rep (cond
              (flat-handle? v) (-rep v)
              (some? nom) nom
              (host-bytes? v) (BytesVal. v)
              ;; a host object array (an eve array's flat form, made outside
              ;; every atom): its elements, as a vector
              (host-object-array? v) (mapv to-rep v)
              #?@(:jank [] :glj [] :clj [(record? v) (typed-rep v)])
              (map? v) (into {} (map (fn [[k val]] [(to-rep k) (to-rep val)])) v)
              (set? v) (into #{} (map to-rep) v)
              (vector? v) (mapv to-rep v)
              (sequential? v) (apply list (map to-rep v))
              :else (or (convert-rep v) v))
        m #?(:clj (when (instance? clojure.lang.IObj v) (meta v))
             :cljr (when (instance? clojure.lang.IObj v) (meta v))
             ;; bare IMeta: cljs.core's on the ClojureScript face;
             ;; under squint the ns refer (eve.core's stub) answers it.
             :cljs (when (satisfies? IMeta v) (meta v))
             :lpy (when (python/isinstance v basilisp.lang.interfaces/IMeta) (meta v))
             ;; clojerl: no arm above reads it, and a let binding needs a value
             :default nil)]
    (if m
      (MetaVal. rep (to-rep m))
      rep)))

;; ---------------------------------------------------------------------------
;; the scan — symbol table, shapes, types for one chunk (FORMAT-V1 §4, §6)
;; ---------------------------------------------------------------------------

(defn- utf8-bytes ^bytes [s] (enc/utf8-bytes s))

(defn- sort-by-key-bytes
  "xs sorted by Eve's canonical order: ascending encoded key bytes.
   A rep with no key bytes (a byte array inside a collection key) falls
   back to its printed form, so the order stays deterministic. Keyed
   and unkeyed reps sort in one byte space (lane C1's scheme; the
   CLJS development vehicle prefixed a flag byte instead — the
   divergence is finding F-A in doc/flat/X1-STATUS.md, and this
   shared form follows the JVM pin on both hosts)."
  [xs]
  (mapv second (sort-by first d/compare-bytes
                        (#?(:jolt mapv :default map)
                         (fn [x] [(try (key-bytes x)
                                       (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _
                                         (utf8-bytes (pr-str x)))) x]) xs))))

(defn- shaped-keys
  "The canonical key list of rep map m when every key may live in a shape
   (keyword, symbol or string), else nil."
  [m]
  (when (every? (fn [k] (or (keyword? k) (symbol? k) (string? k))) (keys m))
    (sort-by-key-bytes (keys m))))

(declare scan!)

;; The scan's algorithm is one: counts by [total uses, key uses],
;; symbol codes by descending use (ties by string), shapes and types
;; in first-met order. The accumulator substrate is the host's — a
;; java.util.HashMap of long arrays on the JVM, volatile maps on
;; ClojureScript, atoms of persistent maps on jank and on glojure
;; (whose analyzers have no java.util to read the JVM text with) — so the
;; walk is written once per host. (The JVM text carries the F-E
;; repair: entries are sorted as entries, not mapped to vectors
;; first.)
#?(:jank (do) :glj (do) :clj (do
(defn- scan-map! [m ^HashMap counts ^HashMap shapes ^HashMap types]
  (doseq [[k v] m] (scan! k counts shapes types true) (scan! v counts shapes types false))
  (when-let [ks (shaped-keys m)]
    (when-not (.containsKey shapes [nil ks]) (.put shapes [nil ks] (.size shapes)))))

(defn- scan!
  [rep ^HashMap counts ^HashMap shapes ^HashMap types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [^longs c (or (.get counts rep) (let [c (long-array 2)] (.put counts rep c) c))]
      (aset c 0 (inc (aget c 0)))
      (when key? (aset c 1 (inc (aget c 1)))))
    (instance? MetaVal rep)
    (do (scan! (.-inner ^MetaVal rep) counts shapes types key?)
        (scan! (.-meta-rep ^MetaVal rep) counts shapes types false))
    (instance? TypedVal rep)
    (let [tv ^TypedVal rep tk [(.-tkey tv) (.-fp tv)]]
      (when-not (.containsKey types tk) (.put types tk (.size types)))
      (doseq [[k v] (.-fields tv)] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys (.-fields tv)))]
        (when-not (.containsKey shapes [(.get types tk) ks])
          (.put shapes [(.get types tk) ks] (.size shapes)))))
    (map? rep) (scan-map! rep counts shapes types)
    (instance? BytesVal rep) nil
    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil))

(defn- field-class
  "A type-table field class from the first instance's rep (§6):
   [:prim subtype] [:ref type-index] [:ser]. Subtype codes are Eve's
   typed-array codes where the value has one, else 0 (a general scalar)."
  [rep ^java.util.Map types]
  (cond
    (instance? TypedVal rep) [:ref (.get types [(.-tkey ^TypedVal rep) (.-fp ^TypedVal rep)])]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner ^MetaVal rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (instance? Double rep) 9
                       (instance? Float rep) 8
                       (instance? Long rep) 10
                       (instance? Integer rep) 6
                       (instance? Boolean rep) 0
                       :else 0)]))

(defn- scan
  "The chunk context for rep: symbol codes, shapes, types."
  [rep]
  (let [counts (HashMap.) shapes (HashMap.) types (HashMap.)]
    (scan! rep counts shapes types false)
    (let [member? (fn [e] (let [k (key e) ^longs c (val e)]
                            (or (keyword? k) (symbol? k) (>= (aget c 0) 2) (pos? (aget c 1)))))
          syms #?(:jolt (mapv key
                              (sort-by (fn [e] [(- (aget ^longs (val e) 0)) (str (key e))])
                                       (filterv member? counts)))
                  :clj (->> (filter member? counts)
                            (sort-by (fn [e] [(- (aget ^longs (val e) 0)) (str (key e))]))
                            (mapv key)))
          shape-list #?(:jolt (mapv (fn [e] {:t (first (key e)) :ks (second (key e))})
                                    (sort-by val (vec shapes)))
                        :clj (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                                   (sort-by second (map (fn [e] [(key e) (val e)]) shapes))))
          type-list #?(:jolt (mapv (fn [e] {:key (first (key e)) :fp (second (key e))})
                                   (sort-by val (vec types)))
                       :clj (mapv (fn [[tk _]] {:key (first tk) :fp (second tk)})
                                  (sort-by second (map (fn [e] [(key e) (val e)]) types))))]
      ;; field classes need one instance per type; find them in the rep
      (let [instances (HashMap.)]
        (letfn [(find! [r]
                  (cond (instance? TypedVal r)
                        (let [tv ^TypedVal r tk [(.-tkey tv) (.-fp tv)]]
                          (when-not (.containsKey instances tk) (.put instances tk tv))
                          (doseq [[_ v] (.-fields tv)] (find! v)))
                        (instance? MetaVal r) (find! (.-inner ^MetaVal r))
                        (instance? BytesVal r) nil
                        (coll? r) (doseq [e r] (find! e))
                        (map? r) (doseq [[_ v] r] (find! v))
                        :else nil))]
          (find! rep))
        {:sym (zipmap syms (range)) :syms syms
         :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
         :shape-list shape-list
         :types (zipmap (map (fn [t] [(:key t) (:fp t)]) type-list) (range))
         :type-list (mapv (fn [t]
                            (let [tv (.get instances [(:key t) (:fp t)])]
                              (assoc t :fields
                                     (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields ^TypedVal tv) k) types)})
                                           (sort-by-key-bytes (keys (.-fields ^TypedVal tv)))))))
                          type-list)}))))
))

#?(:jank (do
;; The type table on jank: the JVM's walk — a TypedVal registers its
;; type in first-met order and its shape under that type — over atoms
;; of persistent maps, a type keyed by [tkey (fp-key fp)]. (Before
;; 2026-11-21 this host's scan wrote no type table: a typed value
;; packed as its field map and read back as a generic FlatMap, so a
;; delivered eve-deftype value carried none of its type's protocol
;; impls. ClojureScript's arm below is the same port, flat lanes U4a.)
(defn- scan!
  [rep counts shapes types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [c (or (get @counts rep) [0 0])
          c [(inc (nth c 0)) (if key? (inc (nth c 1)) (nth c 1))]]
      (swap! counts assoc rep c))

    (instance? MetaVal rep)
    (do (scan! (.-inner rep) counts shapes types key?)
        (scan! (.-meta-rep rep) counts shapes types false))

    (instance? TypedVal rep)
    (let [tk [(.-tkey rep) (fp-key (.-fp rep))]
          fields (.-fields rep)]
      (when-not (contains? @types tk)
        (swap! types assoc tk {:i (count @types) :fp (.-fp rep)}))
      (doseq [[k v] fields] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys fields)) sk [(:i (get @types tk)) ks]]
        (when-not (contains? @shapes sk)
          (swap! shapes assoc sk (count @shapes)))))

    (map? rep)
    (do (doseq [[k v] rep] (scan! k counts shapes types true) (scan! v counts shapes types false))
        (when-let [ks (shaped-keys rep)]
          (when-not (contains? @shapes [nil ks])
            (swap! shapes assoc [nil ks] (count @shapes)))))

    (instance? BytesVal rep) nil
    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil))

(defn- field-class
  "A type-table field class from the first instance's rep (§6), in
   this host's number model: an integer is i64's code 10 (jank's one
   integer type), a Double f64's 9; the rest as on the JVM."
  [rep types]
  (cond
    (instance? TypedVal rep) [:ref (:i (get types [(.-tkey rep) (fp-key (.-fp rep))]))]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (integer? rep) 10
                       (instance? Double rep) 9
                       :else 0)]))

(defn- scan
  "The chunk context for rep: symbol codes, shapes, types (FORMAT-V1
   §4, §6). The :types map is keyed by [tkey (fp-key fp)]; write-node!
   and the batch writer look types up through typed-index."
  [rep]
  (let [counts (atom {}) shapes (atom {}) types (atom {})]
    (scan! rep counts shapes types false)
    (let [member? (fn [[k c]] (or (keyword? k) (symbol? k)
                                  (>= (nth c 0) 2) (pos? (nth c 1))))
          syms (->> (filter member? @counts)
                    (sort-by (fn [[k c]] [(- (nth c 0)) (str k)]))
                    (mapv key))
          shape-list (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                           (sort-by second (map (fn [e] [(key e) (val e)]) @shapes)))
          type-list (mapv (fn [[tk t]] {:key (first tk) :fp (:fp t) :tk tk})
                          (sort-by (fn [[_ t]] (:i t)) @types))
          instances (atom {})]
      ;; field classes need one instance per type; find them in the rep
      (letfn [(find! [r]
                (cond (instance? TypedVal r)
                      (let [tk [(.-tkey r) (fp-key (.-fp r))]]
                        (when-not (contains? @instances tk) (swap! instances assoc tk r))
                        (doseq [[_ v] (.-fields r)] (find! v)))
                      (instance? MetaVal r) (find! (.-inner r))
                      (instance? BytesVal r) nil
                      (map? r) (doseq [[_ v] r] (find! v))
                      (coll? r) (doseq [e r] (find! e))
                      :else nil))]
        (find! rep))
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
       :shape-list shape-list
       :types (zipmap (map :tk type-list) (range))
       :type-list (mapv (fn [t]
                          (let [tv (get @instances (:tk t))]
                            {:key (:key t) :fp (:fp t)
                             :fields (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields tv) k) @types)})
                                           (sort-by-key-bytes (keys (.-fields tv))))}))
                        type-list)})))
))

;; glojure takes the jank text verbatim: the same walk on the same
;; substrate (atoms of persistent maps), the same type table.
#?(:glj (do
(defn- scan!
  [rep counts shapes types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [c (or (get @counts rep) [0 0])
          c [(inc (nth c 0)) (if key? (inc (nth c 1)) (nth c 1))]]
      (swap! counts assoc rep c))

    (instance? MetaVal rep)
    (do (scan! (.-inner rep) counts shapes types key?)
        (scan! (.-meta-rep rep) counts shapes types false))

    (instance? TypedVal rep)
    (let [tk [(.-tkey rep) (fp-key (.-fp rep))]
          fields (.-fields rep)]
      (when-not (contains? @types tk)
        (swap! types assoc tk {:i (count @types) :fp (.-fp rep)}))
      (doseq [[k v] fields] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys fields)) sk [(:i (get @types tk)) ks]]
        (when-not (contains? @shapes sk)
          (swap! shapes assoc sk (count @shapes)))))

    (map? rep)
    (do (doseq [[k v] rep] (scan! k counts shapes types true) (scan! v counts shapes types false))
        (when-let [ks (shaped-keys rep)]
          (when-not (contains? @shapes [nil ks])
            (swap! shapes assoc [nil ks] (count @shapes)))))

    (instance? BytesVal rep) nil
    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil))

(defn- field-class
  "A type-table field class from the first instance's rep (§6), in
   this host's number model: an integer is i64's code 10 (glojure's
   one integer type), a Double f64's 9; the rest as on the JVM."
  [rep types]
  (cond
    (instance? TypedVal rep) [:ref (:i (get types [(.-tkey rep) (fp-key (.-fp rep))]))]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (integer? rep) 10
                       (instance? Double rep) 9
                       :else 0)]))

(defn- scan
  "The chunk context for rep: symbol codes, shapes, types (FORMAT-V1
   §4, §6). The :types map is keyed by [tkey (fp-key fp)]; write-node!
   and the batch writer look types up through typed-index."
  [rep]
  (let [counts (atom {}) shapes (atom {}) types (atom {})]
    (scan! rep counts shapes types false)
    (let [member? (fn [[k c]] (or (keyword? k) (symbol? k)
                                  (>= (nth c 0) 2) (pos? (nth c 1))))
          syms (->> (filter member? @counts)
                    (sort-by (fn [[k c]] [(- (nth c 0)) (str k)]))
                    (mapv key))
          shape-list (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                           (sort-by second (map (fn [e] [(key e) (val e)]) @shapes)))
          type-list (mapv (fn [[tk t]] {:key (first tk) :fp (:fp t) :tk tk})
                          (sort-by (fn [[_ t]] (:i t)) @types))
          instances (atom {})]
      ;; field classes need one instance per type; find them in the rep
      (letfn [(find! [r]
                (cond (instance? TypedVal r)
                      (let [tk [(.-tkey r) (fp-key (.-fp r))]]
                        (when-not (contains? @instances tk) (swap! instances assoc tk r))
                        (doseq [[_ v] (.-fields r)] (find! v)))
                      (instance? MetaVal r) (find! (.-inner r))
                      (instance? BytesVal r) nil
                      (map? r) (doseq [[_ v] r] (find! v))
                      (coll? r) (doseq [e r] (find! e))
                      :else nil))]
        (find! rep))
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
       :shape-list shape-list
       :types (zipmap (map :tk type-list) (range))
       :type-list (mapv (fn [t]
                          (let [tv (get @instances (:tk t))]
                            {:key (:key t) :fp (:fp t)
                             :fields (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields tv) k) @types)})
                                           (sort-by-key-bytes (keys (.-fields tv))))}))
                        type-list)})))
))

;; let-go takes the glojure text verbatim: the same walk on the same
;; substrate (atoms of persistent maps), the same type table. Its
;; field-class differs only in the number checks — an integer is
;; i64's code 10 (let-go's fixed integer type), a float64 f64's 9;
;; there is no Double class to instance? against.
#?(:lg (do
(defn- scan!
  [rep counts shapes types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [c (or (get @counts rep) [0 0])
          c [(inc (nth c 0)) (if key? (inc (nth c 1)) (nth c 1))]]
      (swap! counts assoc rep c))

    (instance? MetaVal rep)
    (do (scan! (.-inner rep) counts shapes types key?)
        (scan! (.-meta-rep rep) counts shapes types false))

    (instance? TypedVal rep)
    (let [tk [(.-tkey rep) (fp-key (.-fp rep))]
          fields (.-fields rep)]
      (when-not (contains? @types tk)
        (swap! types assoc tk {:i (count @types) :fp (.-fp rep)}))
      (doseq [[k v] fields] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys fields)) sk [(:i (get @types tk)) ks]]
        (when-not (contains? @shapes sk)
          (swap! shapes assoc sk (count @shapes)))))

    (map? rep)
    (do (doseq [[k v] rep] (scan! k counts shapes types true) (scan! v counts shapes types false))
        (when-let [ks (shaped-keys rep)]
          (when-not (contains? @shapes [nil ks])
            (swap! shapes assoc [nil ks] (count @shapes)))))

    (instance? BytesVal rep) nil
    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil))

(defn- field-class
  "A type-table field class from the first instance's rep (§6), in
   this host's number model: an integer is i64's code 10 (let-go's
   one fixed integer type), a float64 f64's 9; the rest as on the JVM."
  [rep types]
  (cond
    (instance? TypedVal rep) [:ref (:i (get types [(.-tkey rep) (fp-key (.-fp rep))]))]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (integer? rep) 10
                       (float? rep) 9
                       :else 0)]))

(defn- scan
  "The chunk context for rep: symbol codes, shapes, types (FORMAT-V1
   §4, §6). The :types map is keyed by [tkey (fp-key fp)]; write-node!
   and the batch writer look types up through typed-index."
  [rep]
  (let [counts (atom {}) shapes (atom {}) types (atom {})]
    (scan! rep counts shapes types false)
    (let [member? (fn [[k c]] (or (keyword? k) (symbol? k)
                                  (>= (nth c 0) 2) (pos? (nth c 1))))
          syms (->> (filter member? @counts)
                    (sort-by (fn [[k c]] [(- (nth c 0)) (str k)]))
                    (mapv key))
          shape-list (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                           (sort-by second (map (fn [e] [(key e) (val e)]) @shapes)))
          type-list (mapv (fn [[tk t]] {:key (first tk) :fp (:fp t) :tk tk})
                          (sort-by (fn [[_ t]] (:i t)) @types))
          instances (atom {})]
      ;; field classes need one instance per type; find them in the rep
      (letfn [(find! [r]
                (cond (instance? TypedVal r)
                      (let [tk [(.-tkey r) (fp-key (.-fp r))]]
                        (when-not (contains? @instances tk) (swap! instances assoc tk r))
                        (doseq [[_ v] (.-fields r)] (find! v)))
                      (instance? MetaVal r) (find! (.-inner r))
                      (instance? BytesVal r) nil
                      (map? r) (doseq [[_ v] r] (find! v))
                      (coll? r) (doseq [e r] (find! e))
                      :else nil))]
        (find! rep))
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
       :shape-list shape-list
       :types (zipmap (map :tk type-list) (range))
       :type-list (mapv (fn [t]
                          (let [tv (get @instances (:tk t))]
                            {:key (:key t) :fp (:fp t)
                             :fields (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields tv) k) @types)})
                                           (sort-by-key-bytes (keys (.-fields tv))))}))
                        type-list)})))
))

#?(:cljr (do
(defn- scan!
  [rep counts shapes types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [c (or (get @counts rep) [0 0])
          c [(inc (nth c 0)) (if key? (inc (nth c 1)) (nth c 1))]]
      (swap! counts assoc rep c))

    (instance? MetaVal rep)
    (do (scan! (.-inner rep) counts shapes types key?)
        (scan! (.-meta-rep rep) counts shapes types false))

    (instance? TypedVal rep)
    (let [tk [(.-tkey rep) (fp-key (.-fp rep))]
          fields (.-fields rep)]
      (when-not (contains? @types tk)
        (swap! types assoc tk {:i (count @types) :fp (.-fp rep)}))
      (doseq [[k v] fields] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys fields)) sk [(:i (get @types tk)) ks]]
        (when-not (contains? @shapes sk)
          (swap! shapes assoc sk (count @shapes)))))

    (map? rep)
    (do (doseq [[k v] rep] (scan! k counts shapes types true) (scan! v counts shapes types false))
        (when-let [ks (shaped-keys rep)]
          (when-not (contains? @shapes [nil ks])
            (swap! shapes assoc [nil ks] (count @shapes)))))

    (instance? BytesVal rep) nil
    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil))

(defn- field-class
  "A type-table field class from the first instance's rep (§6), in
   this host's number model: an integer is i64's code 10 (ClojureCLR's
   one fixed integer type), a float64 f64's 9; the rest as on the JVM."
  [rep types]
  (cond
    (instance? TypedVal rep) [:ref (:i (get types [(.-tkey rep) (fp-key (.-fp rep))]))]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (integer? rep) 10
                       (float? rep) 9
                       :else 0)]))

(defn- scan
  "The chunk context for rep: symbol codes, shapes, types (FORMAT-V1
   §4, §6). The :types map is keyed by [tkey (fp-key fp)]; write-node!
   and the batch writer look types up through typed-index."
  [rep]
  (let [counts (atom {}) shapes (atom {}) types (atom {})]
    (scan! rep counts shapes types false)
    (let [member? (fn [[k c]] (or (keyword? k) (symbol? k)
                                  (>= (nth c 0) 2) (pos? (nth c 1))))
          syms (->> (filter member? @counts)
                    (sort-by (fn [[k c]] [(- (nth c 0)) (str k)]))
                    (mapv key))
          shape-list (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                           (sort-by second (map (fn [e] [(key e) (val e)]) @shapes)))
          type-list (mapv (fn [[tk t]] {:key (first tk) :fp (:fp t) :tk tk})
                          (sort-by (fn [[_ t]] (:i t)) @types))
          instances (atom {})]
      ;; field classes need one instance per type; find them in the rep
      (letfn [(find! [r]
                (cond (instance? TypedVal r)
                      (let [tk [(.-tkey r) (fp-key (.-fp r))]]
                        (when-not (contains? @instances tk) (swap! instances assoc tk r))
                        (doseq [[_ v] (.-fields r)] (find! v)))
                      (instance? MetaVal r) (find! (.-inner r))
                      (instance? BytesVal r) nil
                      (map? r) (doseq [[_ v] r] (find! v))
                      (coll? r) (doseq [e r] (find! e))
                      :else nil))]
        (find! rep))
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
       :shape-list shape-list
       :types (zipmap (map :tk type-list) (range))
       :type-list (mapv (fn [t]
                          (let [tv (get @instances (:tk t))]
                            {:key (:key t) :fp (:fp t)
                             :fields (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields tv) k) @types)})
                                           (sort-by-key-bytes (keys (.-fields tv))))}))
                        type-list)})))
))

#?(:clje (do
(defn- scan!
  [rep counts shapes types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [c (or (get @counts rep) [0 0])
          c [(inc (nth c 0)) (if key? (inc (nth c 1)) (nth c 1))]]
      (swap! counts assoc rep c))

    (instance? MetaVal rep)
    (do (scan! (.-inner rep) counts shapes types key?)
        (scan! (.-meta-rep rep) counts shapes types false))

    (instance? TypedVal rep)
    (let [tk [(.-tkey rep) (fp-key (.-fp rep))]
          fields (.-fields rep)]
      (when-not (contains? @types tk)
        (swap! types assoc tk {:i (count @types) :fp (.-fp rep)}))
      (doseq [[k v] fields] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys fields)) sk [(:i (get @types tk)) ks]]
        (when-not (contains? @shapes sk)
          (swap! shapes assoc sk (count @shapes)))))

    (map? rep)
    (do (doseq [[k v] rep] (scan! k counts shapes types true) (scan! v counts shapes types false))
        (when-let [ks (shaped-keys rep)]
          (when-not (contains? @shapes [nil ks])
            (swap! shapes assoc [nil ks] (count @shapes)))))

    (instance? BytesVal rep) nil
    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil))

(defn- field-class
  "A type-table field class from the first instance's rep (§6), in
   this host's number model: an integer is i64's code 10 (let-go's
   one fixed integer type), a float64 f64's 9; the rest as on the JVM."
  [rep types]
  (cond
    (instance? TypedVal rep) [:ref (:i (get types [(.-tkey rep) (fp-key (.-fp rep))]))]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (integer? rep) 10
                       (float? rep) 9
                       :else 0)]))

(defn- scan
  "The chunk context for rep: symbol codes, shapes, types (FORMAT-V1
   §4, §6). The :types map is keyed by [tkey (fp-key fp)]; write-node!
   and the batch writer look types up through typed-index."
  [rep]
  (let [counts (atom {}) shapes (atom {}) types (atom {})]
    (scan! rep counts shapes types false)
    (let [member? (fn [[k c]] (or (keyword? k) (symbol? k)
                                  (>= (nth c 0) 2) (pos? (nth c 1))))
          syms (->> (filter member? @counts)
                    (sort-by (fn [[k c]] [(- (nth c 0)) (str k)]))
                    (mapv key))
          shape-list (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                           (sort-by second (map (fn [e] [(key e) (val e)]) @shapes)))
          type-list (mapv (fn [[tk t]] {:key (first tk) :fp (:fp t) :tk tk})
                          (sort-by (fn [[_ t]] (:i t)) @types))
          instances (atom {})]
      ;; field classes need one instance per type; find them in the rep
      (letfn [(find! [r]
                (cond (instance? TypedVal r)
                      (let [tk [(.-tkey r) (fp-key (.-fp r))]]
                        (when-not (contains? @instances tk) (swap! instances assoc tk r))
                        (doseq [[_ v] (.-fields r)] (find! v)))
                      (instance? MetaVal r) (find! (.-inner r))
                      (instance? BytesVal r) nil
                      (map? r) (doseq [[_ v] r] (find! v))
                      (coll? r) (doseq [e r] (find! e))
                      :else nil))]
        (find! rep))
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
       :shape-list shape-list
       :types (zipmap (map :tk type-list) (range))
       :type-list (mapv (fn [t]
                          (let [tv (get @instances (:tk t))]
                            {:key (:key t) :fp (:fp t)
                             :fields (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields tv) k) @types)})
                                           (sort-by-key-bytes (keys (.-fields tv))))}))
                        type-list)})))
))

#?(:lpy (do
;; basilisp takes the jank text: the same walk on the same substrate
;; (atoms of persistent maps), the same type table. field-class
;; spells this host's number model: Python's int is code 10, a float
;; is f64's 9, a boolean is 0 and is tested first (Python's bool is
;; an int subclass and basilisp's integer? admits it).
(defn- scan!
  [rep counts shapes types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [c (or (get @counts rep) [0 0])
          c [(inc (nth c 0)) (if key? (inc (nth c 1)) (nth c 1))]]
      (swap! counts assoc rep c))

    (instance? MetaVal rep)
    (do (scan! (.-inner rep) counts shapes types key?)
        (scan! (.-meta-rep rep) counts shapes types false))

    (instance? TypedVal rep)
    (let [tk [(.-tkey rep) (fp-key (.-fp rep))]
          fields (.-fields rep)]
      (when-not (contains? @types tk)
        (swap! types assoc tk {:i (count @types) :fp (.-fp rep)}))
      (doseq [[k v] fields] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys fields)) sk [(:i (get @types tk)) ks]]
        (when-not (contains? @shapes sk)
          (swap! shapes assoc sk (count @shapes)))))

    (map? rep)
    (do (doseq [[k v] rep] (scan! k counts shapes types true) (scan! v counts shapes types false))
        (when-let [ks (shaped-keys rep)]
          (when-not (contains? @shapes [nil ks])
            (swap! shapes assoc [nil ks] (count @shapes)))))

    (instance? BytesVal rep) nil
    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil))

(defn- field-class
  "A type-table field class from the first instance's rep (§6), in
   this host's number model: an integer is i64's code 10, a float
   f64's 9, a boolean 0; the rest as on the JVM."
  [rep types]
  (cond
    (instance? TypedVal rep) [:ref (:i (get types [(.-tkey rep) (fp-key (.-fp rep))]))]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (or (true? rep) (false? rep)) 0
                       (integer? rep) 10
                       (float? rep) 9
                       :else 0)]))

(defn- scan
  "The chunk context for rep: symbol codes, shapes, types (FORMAT-V1
   §4, §6). The :types map is keyed by [tkey (fp-key fp)]; write-node!
   and the batch writer look types up through typed-index."
  [rep]
  (let [counts (atom {}) shapes (atom {}) types (atom {})]
    (scan! rep counts shapes types false)
    (let [member? (fn [[k c]] (or (keyword? k) (symbol? k)
                                  (>= (nth c 0) 2) (pos? (nth c 1))))
          syms (->> (filter member? @counts)
                    (sort-by (fn [[k c]] [(- (nth c 0)) (str k)]))
                    (mapv key))
          shape-list (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                           (sort-by second (map (fn [e] [(key e) (val e)]) @shapes)))
          type-list (mapv (fn [[tk t]] {:key (first tk) :fp (:fp t) :tk tk})
                          (sort-by (fn [[_ t]] (:i t)) @types))
          instances (atom {})]
      ;; field classes need one instance per type; find them in the rep
      (letfn [(find! [r]
                (cond (instance? TypedVal r)
                      (let [tk [(.-tkey r) (fp-key (.-fp r))]]
                        (when-not (contains? @instances tk) (swap! instances assoc tk r))
                        (doseq [[_ v] (.-fields r)] (find! v)))
                      (instance? MetaVal r) (find! (.-inner r))
                      (instance? BytesVal r) nil
                      (map? r) (doseq [[_ v] r] (find! v))
                      (coll? r) (doseq [e r] (find! e))
                      :else nil))]
        (find! rep))
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
       :shape-list shape-list
       :types (zipmap (map :tk type-list) (range))
       :type-list (mapv (fn [t]
                          (let [tv (get @instances (:tk t))]
                            {:key (:key t) :fp (:fp t)
                             :fields (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields tv) k) @types)})
                                           (sort-by-key-bytes (keys (.-fields tv))))}))
                        type-list)})))
))
;; The type table on ClojureScript (flat lanes U4a): the JVM's walk — a
;; TypedVal registers its type in first-met order and its shape under that
;; type — over volatile maps, a type keyed by [tkey (fp-key fp)] (a
;; fingerprint is a js/BigInt here, hashed by its decimal string).
#?(:cljs (declare plain-integer?))
#?(:cljs (defn- scan!
  [rep counts shapes types key?]
  (cond
    (or (keyword? rep) (symbol? rep) (string? rep))
    (let [c (or (get @counts rep) [0 0])
          c [(inc (nth c 0)) (if key? (inc (nth c 1)) (nth c 1))]]
      (vswap! counts assoc rep c))

    (instance? MetaVal rep)
    (do (scan! (.-inner rep) counts shapes types key?)
        (scan! (.-meta-rep rep) counts shapes types false))

    (instance? TypedVal rep)
    (let [tk [(.-tkey rep) (fp-key (.-fp rep))]
          fields (.-fields rep)]
      (when-not (contains? @types tk)
        (vswap! types assoc tk {:i (count @types) :fp (.-fp rep)}))
      (doseq [[k v] fields] (scan! k counts shapes types true) (scan! v counts shapes types false))
      (let [ks (sort-by-key-bytes (keys fields)) sk [(:i (get @types tk)) ks]]
        (when-not (contains? @shapes sk)
          (vswap! shapes assoc sk (count @shapes)))))

    (map? rep)
    (do (doseq [[k v] rep] (scan! k counts shapes types true) (scan! v counts shapes types false))
        (when-let [ks (shaped-keys rep)]
          (when-not (contains? @shapes [nil ks])
            (vswap! shapes assoc [nil ks] (count @shapes)))))

    (instance? BytesVal rep) nil

    (coll? rep) (doseq [e rep] (scan! e counts shapes types false))
    :else nil)))
#?(:cljs (defn- field-class
  "A type-table field class from the first instance's rep (§6), in this
   host's number model: an integer (a BigInt, or an integral number —
   what the column planner writes as an integer) is i64's code 10, any
   other number f64's 9; the rest as on the JVM."
  [rep types]
  (cond
    (instance? TypedVal rep) [:ref (:i (get types [(.-tkey rep) (fp-key (.-fp rep))]))]
    (instance? BytesVal rep) [:prim 1]
    (instance? MetaVal rep) (field-class (.-inner rep) types)
    (coll? rep) [:ser 0]
    :else [:prim (cond (plain-integer? rep) 10
                       (number? rep) 9
                       :else 0)])))
#?(:cljs (defn- scan
  "The chunk context for rep: symbol codes, shapes, types (FORMAT-V1 §4,
   §6). The :types map is keyed by [tkey (fp-key fp)]; write-node! and
   the batch writer look types up through typed-index."
  [rep]
  (let [counts (volatile! {}) shapes (volatile! {}) types (volatile! {})]
    (scan! rep counts shapes types false)
    (let [member? (fn [[k c]] (or (keyword? k) (symbol? k)
                                  (>= (nth c 0) 2) (pos? (nth c 1))))
          syms (->> (filter member? @counts)
                    (sort-by (fn [[k c]] [(- (nth c 0)) (str k)]))
                    (mapv key))
          shape-list (mapv (fn [[k _]] {:t (first k) :ks (second k)})
                           (sort-by second (map (fn [e] [(key e) (val e)]) @shapes)))
          type-list (mapv (fn [[tk t]] {:key (first tk) :fp (:fp t) :tk tk})
                          (sort-by (fn [[_ t]] (:i t)) @types))
          instances (volatile! {})]
      ;; field classes need one instance per type; find them in the rep
      (letfn [(find! [r]
                (cond (instance? TypedVal r)
                      (let [tk [(.-tkey r) (fp-key (.-fp r))]]
                        (when-not (contains? @instances tk) (vswap! instances assoc tk r))
                        (doseq [[_ v] (.-fields r)] (find! v)))
                      (instance? MetaVal r) (find! (.-inner r))
                      (instance? BytesVal r) nil
                      (map? r) (doseq [[_ v] r] (find! v))
                      (coll? r) (doseq [e r] (find! e))
                      :else nil))]
        (find! rep))
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) shape-list) (range))
       :shape-list shape-list
       :types (zipmap (map :tk type-list) (range))
       :type-list (mapv (fn [t]
                          (let [^TypedVal tv (get @instances (:tk t))]
                            {:key (:key t) :fp (:fp t)
                             :fields (mapv (fn [k] {:name k
                                                    :class (field-class (get (.-fields tv) k) @types)})
                                           (sort-by-key-bytes (keys (.-fields tv))))}))
                        type-list)}))))

;; A TypedVal's index in the chunk's type table, or nil on a host whose
;; scan writes none (the value then writes as its field map). The
;; scan's :types key is [tkey (fp-key fp)] on every host.
(defn- typed-index [cx tv]
  (get (:types cx) [(.-tkey ^TypedVal tv) (fp-key (.-fp ^TypedVal tv))]))

;; The shape index a TypedVal row writes under: its type's shape where the
;; chunk has a type table, else its field map's plain shape.
(defn- typed-shape [cx tv]
  (let [fields (.-fields ^TypedVal tv)]
    (if-let [tidx (typed-index cx tv)]
      (get (:shapes cx) [tidx (sort-by-key-bytes (keys fields))])
      (when-let [ks (shaped-keys fields)] (get (:shapes cx) [nil ks])))))

;; ---------------------------------------------------------------------------
;; columns (FORMAT-V1 §5). A plan is {:kind k} with :base/:width for the
;; narrowed kind and :base-plan/:runs for run-end.
;; ---------------------------------------------------------------------------

#?(:clj (defn- plain-integer? [x]
          (and (integer? x) (not (instance? clojure.lang.BigInt x))
               (not (instance? java.math.BigInteger x)) (not (instance? java.math.BigDecimal x))))
   ;; let-go: a plain integer is its fixed-width int64 — eve.encoding's
   ;; own word for exactly this (its BigInt is the separate type)
   :lg (defn- plain-integer? [x] (enc/fixed-integer? x))
   :cljr (defn- plain-integer? [x] (enc/fixed-integer? x))
   :clje (defn- plain-integer? [x] (enc/fixed-integer? x))
   :lpy (defn- plain-integer? [x] (and (integer? x) (not (or (true? x) (false? x)))))
   :cljs (defn- plain-integer?
           "An integer on this host: a js/BigInt, or an integral JS number
   (Eve's CLJS number model writes those as integers, as its scalar
   codec does). NaN and the infinities are not integers."
           [x]
           (or (bigint? x)
               (and (number? x) (== x (js/Math.floor x)) (js/isFinite x)))))
#?(:cljs (defn- val=
           "Column-value equality: integers compare in BigInt space, so a
   js/BigInt and an integral number of the same value are one value."
           [a b]
           (if (or (bigint? a) (bigint? b))
             (and (plain-integer? a) (plain-integer? b) (bi= a b))
             (= a b))))

;; basilisp: Python's int is the integer representation, arbitrary
;; precision — every integer is a plain integer (a boolean is not:
;; Python's bool is an int subclass, basilisp's integer? admits it),
;; equality is =, and the planner arithmetic below is the plain ops.
#?(:lpy (defn- val= [a b] (= a b)))

(defn- runs-of
  "[[value run-length] ...] over consecutive distinct values."
  [vals]
  (reduce (fn [rs v]
            (if (and (seq rs) (#?(:clj = :cljs val= :lg = :lpy val= :cljr = :clje =) v (first (peek rs))))
              (conj (pop rs) [v (inc (second (peek rs)))])
              (conj rs [v 1])))
          [] vals))

;; The planner's integer arithmetic, per host. On the JVM the range
;; is computed with promotion (-'): at the C1 pin it was (- mx mn),
;; which throws ArithmeticException on a full-range i64 column
;; (finding F-C) — the plan the decision tree prescribes was
;; unreachable for exactly the columns kind 10 exists for. On
;; ClojureScript the math is BigInt throughout and cannot overflow.
;; let-go: the JVM spellings (its int64 arithmetic and promoting -'
;; behave as the JVM's), with the bounds as literals, as on jank
;; (no Integer statics on either host)
#?(:cljs (def ^:private INT32-MIN (js/BigInt "-2147483648")))
#?(:cljs (def ^:private INT32-MAX (js/BigInt "2147483647")))
;; Python ints do not overflow: the range is the plain subtraction
;; the JVM needs promotion for and ClojureScript needs BigInt for.
#?(:clj (defn- col-min [present] (reduce min (map long present)))
   :lg (defn- col-min [present] (reduce min (map long present)))
   :cljr (defn- col-min [present] (reduce min (map long present)))
   :clje (defn- col-min [present] (reduce min (map long present)))
   :lpy (defn- col-min [present] (reduce min present))
   :cljs (defn- col-min [present] (reduce (fn [a b] (if (bi< b a) b a)) (map bi present))))
#?(:clj (defn- col-max [present] (reduce max (map long present)))
   :lg (defn- col-max [present] (reduce max (map long present)))
   :cljr (defn- col-max [present] (reduce max (map long present)))
   :clje (defn- col-max [present] (reduce max (map long present)))
   :lpy (defn- col-max [present] (reduce max present))
   :cljs (defn- col-max [present] (reduce (fn [a b] (if (bi< a b) b a)) (map bi present))))
#?(:clj (defn- col-range [mn mx] (-' mx mn))
   :lg (defn- col-range [mn mx] (-' mx mn))
   :cljr (defn- col-range [mn mx] (-' mx mn))
   :clje (defn- col-range [mn mx] (- mx mn))
   :lpy (defn- col-range [mn mx] (- mx mn))
   :cljs (defn- col-range [mn mx] (bi- mx mn)))
#?(:clj (defn- ile [a b] (<= a b))
   :lg (defn- ile [a b] (<= a b))
   :cljr (defn- ile [a b] (<= a b))
   :clje (defn- ile [a b] (<= a b))
   :lpy (defn- ile [a b] (<= a b))
   :cljs (defn- ile [a b] (bi<= a b)))
#?(:clj (def ^:private I32-MIN #?(:jank -2147483648 :default Integer/MIN_VALUE))
   :lg (def ^:private I32-MIN -2147483648)
   :cljr (def ^:private I32-MIN -2147483648)
   :clje (def ^:private I32-MIN -2147483648)
   :lpy (def ^:private I32-MIN -2147483648)
   :cljs (def ^:private I32-MIN INT32-MIN))
#?(:clj (def ^:private I32-MAX #?(:jank 2147483647 :default Integer/MAX_VALUE))
   :lg (def ^:private I32-MAX 2147483647)
   :cljr (def ^:private I32-MAX 2147483647)
   :clje (def ^:private I32-MAX 2147483647)
   :lpy (def ^:private I32-MAX 2147483647)
   :cljs (def ^:private I32-MAX INT32-MAX))

(defn- column-plan
  "The §5 column plan for scalar reps vals (nils allowed), or nil.
   One decision tree on both hosts (lane C1's, including its gate on
   the width-4 narrowing — finding F-B: the CLJS vehicle narrowed
   whenever the range fit u32; here width 4 is reached only inside
   the I32-MIN branch, as at the pin); only the integer arithmetic
   under it is the host's."
  [cx vals]
  (let [present (filterv some? vals) n (count vals)]
    (when (seq present)
      (let [base
            (cond
              (every? boolean? present) {:kind 16}
              (every? plain-integer? present)
              (let [mn (col-min present) mx (col-max present)
                    range (col-range mn mx)]
                (cond
                  (and (>= n 16) (ile range 255)) {:kind 22 :base mn :width 1}
                  (and (>= n 16) (ile range 65535)) {:kind 22 :base mn :width 2}
                  (ile I32-MIN mn) (if (ile mx I32-MAX)
                                     {:kind 6}
                                     (if (and (>= n 16) (ile range 4294967295))
                                       {:kind 22 :base mn :width 4}
                                       {:kind 10}))
                  :else {:kind 10}))
              #?@(:jank [(every? number? present) {:kind 9}]
                  :clj [(every? #(instance? Float %) present) {:kind 8}
                        (every? #(instance? Double %) present) {:kind 9}]
                  ;; no Float on this host: the numbers that are not
                  ;; integers are doubles (kind 9), as on the JVM
                  :cljs [(every? number? present) {:kind 9}]
                  :lg [(every? number? present) {:kind 9}])
              (every? #(contains? (:sym cx) %) present)
              {:kind (if (< (reduce max (map #(get (:sym cx) %) present)) 256) 17 18)}
              (every? string? present) {:kind 19}
              :else nil)]
        (when base
          (if (and (contains? #{6 10 17 18} (:kind base)) (>= n 32)
                   (<= (count (runs-of vals)) (max 2 (quot n 16))))
            {:kind 23 :base-plan base :runs (runs-of vals)}
            base))))))

(declare write-node!)

(defn- write-column!
  "Column bytes for plan over vals. node-offsets is required for kind 20
   (the element nodes are written by the caller, children first)."
  [w cx plan vals node-offsets]
  (let [n (count vals) kind (:kind plan) nils? (boolean (some nil? vals))]
    (fio/buf-u8 w (bit-or kind (if nils? 0x80 0)))
    (when nils?
      (dotimes [i (quot (+ n 7) 8)]
        (fio/buf-u8 w (reduce (fn [acc j] (let [idx (+ (* i 8) j)]
                                     (if (and (< idx n) (some? (nth vals idx)))
                                       (bit-or acc (bit-shift-left 1 j)) acc)))
                       0 (range 8)))))
    (case (int kind)
      16 (dotimes [i (quot (+ n 7) 8)]
           (fio/buf-u8 w (reduce (fn [acc j] (let [idx (+ (* i 8) j)]
                                        (if (and (< idx n) (nth vals idx))
                                          (bit-or acc (bit-shift-left 1 j)) acc)))
                          0 (range 8))))
      ;; a u32 cell goes to the writer as a host number (host-num: a BigInt
      ;; cell made a JS number on ClojureScript, itself elsewhere — the
      ;; writer's u32 unboxes it as (long v) did); an i64 cell as it is
      ;; (fio's i64 takes a long, or on ClojureScript a BigInt or number)
      6 (do (fio/buf-align w 4) (doseq [v vals] (fio/buf-u32 w (host-num (or v 0)))))
      10 (do (fio/buf-align w 8) (doseq [v vals] (fio/buf-i64 w (or v 0))))
      ;; an f32 cell: eve.encoding's 4 little-endian bytes of it (the
      ;; float's raw bits, as Float/floatToRawIntBits gave them)
      8 (do (fio/buf-align w 4) (doseq [v vals] (fio/buf-bytes w (enc/f32-le-bytes (or v 0.0)) 0 4)))
      9 (do (fio/buf-align w 8) (doseq [v vals] (fio/buf-f64 w (double (or v 0.0)))))
      17 (doseq [v vals] (fio/buf-u8 w (if (nil? v) 0 (get (:sym cx) v))))
      18 (doseq [v vals] (fio/buf-u16 w (if (nil? v) 0 (get (:sym cx) v))))
      19 (let [bs (mapv (fn [v] (if (nil? v) (enc/make-bytes 0) (enc/utf8-bytes v))) vals)]
           (fio/buf-align w 4)
           (reduce (fn [acc b] (fio/buf-u32 w acc) (+ acc (byte-len b))) 0 bs)
           ;; byte-len wrapped in a fn, not passed as a value: on bb
           ;; a bare alength var resolves to a reflective RT.alength
           ;; call, which its native image refuses (a direct call is fine).
           (fio/buf-u32 w (reduce + (map (fn [b] (byte-len b)) bs)))
           (doseq [b bs] (fio/buf-bytes w b 0 (byte-len b))))
      20 (doseq [o node-offsets] (fio/buf-off w o))
      22 (let [base (:base plan) width (:width plan)]
           (fio/buf-i64 w base) (fio/buf-u8 w width) (fio/buf-align w width)
           (doseq [v vals]
             (let [x (i64-delta (or v base) base)]
               (case (int width) 1 (fio/buf-u8 w x) 2 (fio/buf-u16 w x) 4 (fio/buf-u32 w x)))))
      23 (let [runs (:runs plan) base-plan (:base-plan plan)]
           (fio/buf-varint w (count runs))
           (write-column! w cx (assoc base-plan :kind (:kind base-plan))
                          (mapv first runs) nil)
           (reduce (fn [end [_ len]] (fio/buf-u32 w (+ end len)) (+ end len)) 0 runs))
      (throw (ex-info "flat chunk: no writer for column kind" {:kind kind})))))

;; ---------------------------------------------------------------------------
;; nodes (FORMAT-V1 §4): children before parents, root last, offsets
;; absolute and one width per chunk.
;; ---------------------------------------------------------------------------

(defn- write-offsets-node!
  "Tag, head bytes written by head!, then n width-offsets of children
   (already written by child-offsets, a vector of their offsets)."
  ^long [w tag head! child-offsets]
  (let [s (fio/buf-pos w)]
    (fio/buf-u8 w tag)
    (head!)
    (doseq [o child-offsets] (fio/buf-off w (long o)))
    s))

(defn- write-map-node!
  ^long [w cx shape-idx fields]
  (let [ks (:ks (nth (:shape-list cx) shape-idx))
        offs (mapv (fn [k] (write-node! w cx (get fields k))) ks)]
    (write-offsets-node! w 0x21 #(fio/buf-varint w shape-idx) offs)))

(defn- columnable-map-rows?
  "A BATCH: >= 2 rows, all plain maps of one shape, or all TypedVals of
   one type. Answers the shape index, or nil."
  [cx rows]
  (when (and (>= (count rows) 2)
             (or (every? map? rows) (every? #(instance? TypedVal %) rows)))
    (let [idxs (map (fn [r]
                      (if (instance? TypedVal r)
                        (typed-shape cx r)
                        (when-let [ks (shaped-keys r)] (get (:shapes cx) [nil ks]))))
                    rows)]
      (when (and (every? some? idxs) (apply = idxs)) (first idxs)))))

(defn- write-batch!
  ^long [w cx shape-idx rows]
  (let [ks (:ks (nth (:shape-list cx) shape-idx)) n (count rows)
        col-offsets
        (mapv (fn [k]
                (let [vals (mapv (fn [r] (if (instance? TypedVal r)
                                           (get (.-fields ^TypedVal r) k)
                                           (get r k))) rows)
                      nested (columnable-map-rows? cx vals)
                      plan (when-not nested (column-plan cx vals))]
                  (cond
                    nested ;; a struct column (§5 kind 21): the field's own batch, then [21][its offset]
                    (let [boff (write-batch! w cx nested vals)
                          s (fio/buf-pos w)]
                      (fio/buf-u8 w 21) (fio/buf-off w boff) s)
                    plan
                    (let [s (fio/buf-pos w)] (write-column! w cx plan vals nil) s)
                    :else
                    (let [offs (mapv (fn [v] (write-node! w cx v)) vals)
                          s (fio/buf-pos w)]
                      (write-column! w cx {:kind 20} vals offs) s))))
              ks)
        s (fio/buf-pos w)]
    (fio/buf-u8 w 0x26) (fio/buf-varint w shape-idx) (fio/buf-varint w n)
    (doseq [o col-offsets] (fio/buf-off w (long o)))
    s))

(defn- write-keys-node!
  "A VEC or COL node over already-sorted scalar-or-node elements."
  ^long [w cx elems]
  (let [plan (when (every? (fn [e] (or (nil? e) (not (coll? e)))) elems)
               (column-plan cx elems))]
    (if plan
      (let [s (fio/buf-pos w)]
        (fio/buf-u8 w 0x23) (fio/buf-varint w (count elems)) (write-column! w cx plan elems nil) s)
      (let [offs (mapv (fn [e] (write-node! w cx e)) elems)]
        (write-offsets-node! w 0x22 #(fio/buf-varint w (count elems)) offs)))))

(defn- write-node!
  ^long [w cx rep]
  (cond
    (nil? rep) (let [s (fio/buf-pos w)] (fio/buf-u8 w 0) s)

    (instance? BytesVal rep)
    (let [s (fio/buf-pos w) body (scalar-body (.-bs ^BytesVal rep))]
      (fio/buf-bytes w body 0 (byte-len body)) s)

    (instance? MetaVal rep)
    (let [mv ^MetaVal rep
          vo (write-node! w cx (.-inner mv))
          mo (write-node! w cx (.-meta-rep mv))
          s (fio/buf-pos w)]
      (fio/buf-u8 w 0x29) (fio/buf-off w vo) (fio/buf-off w mo) s)

    (instance? TypedVal rep)
    (let [tv ^TypedVal rep]
      (if (some? (typed-index cx tv))
        (write-map-node! w cx (typed-shape cx tv) (.-fields tv))
        ;; a host with no type table: the typed value writes as its
        ;; field map, the posture their scan takes
        (write-node! w cx (.-fields tv))))

    (map? rep)
    (if-let [ks (shaped-keys rep)]
      (write-map-node! w cx (get (:shapes cx) [nil ks]) rep)
      (let [es (mapv (fn [[k v]] [(try (key-bytes k) (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil)) k v]) rep)
            es (sort (fn [a b] (if (and (first a) (first b))
                                 (d/compare-bytes (first a) (first b))
                                 (compare (pr-str (second a)) (pr-str (second b))))) es)
            n (count es)
            keys-off (write-keys-node! w cx (mapv second es))
            vals (mapv #(nth % 2) es)
            plan (column-plan cx vals)
            vals-off (if plan
                       (let [s (fio/buf-pos w)] (fio/buf-u8 w 0x23) (fio/buf-varint w n)
                         (write-column! w cx plan vals nil) s)
                       (let [offs (mapv (fn [v] (write-node! w cx v)) vals)]
                         (write-offsets-node! w 0x22 #(fio/buf-varint w n) offs)))
            s (fio/buf-pos w)]
        (fio/buf-u8 w 0x27) (fio/buf-varint w n) (fio/buf-off w keys-off) (fio/buf-off w vals-off) s))

    (set? rep)
    (let [elems (sort-by-key-bytes (vec rep))
          keys-off (write-keys-node! w cx elems)
          s (fio/buf-pos w)]
      ;; the sort above orders by the reps' own key bytes; elements that
      ;; are not scalars have no key bytes and keep set order — sets of
      ;; collections are the documented weak case (see C1-STATUS)
      (fio/buf-u8 w 0x24) (fio/buf-varint w (count elems)) (fio/buf-off w keys-off) s)

    (vector? rep)
    (let [bidx (columnable-map-rows? cx rep)]
      (cond
        bidx (write-batch! w cx bidx rep)
        :else
        (let [plan (when (every? (fn [e] (or (nil? e) (not (coll? e)) (instance? BytesVal e))) rep)
                     ;; byte arrays have no §5 column; exclude them
                     (when-not (some #(instance? BytesVal %) rep) (column-plan cx rep)))]
          (if plan
            (let [s (fio/buf-pos w)]
              (fio/buf-u8 w 0x23) (fio/buf-varint w (count rep)) (write-column! w cx plan rep nil) s)
            (let [offs (mapv (fn [e] (write-node! w cx e)) rep)]
              (write-offsets-node! w 0x22 #(fio/buf-varint w (count rep)) offs))))))

    (sequential? rep)
    (let [offs (mapv (fn [e] (write-node! w cx e)) rep)]
      (write-offsets-node! w 0x25 #(fio/buf-varint w (count rep)) offs))

    :else
    (let [s (fio/buf-pos w)
          code (get (:sym cx) rep)]
      (if (some? code)
        (do (fio/buf-u8 w 0x20) (fio/buf-varint w (long code)))
        (let [body (scalar-body rep)] (fio/buf-bytes w body 0 (byte-len body))))
      s)))

;; ---------------------------------------------------------------------------
;; the tables and the chunk (FORMAT-V1 §2, §4, §6)
;; ---------------------------------------------------------------------------

(defn- write-symtab! [w cx width]
  (let [syms (:syms cx) n (count syms) start (fio/buf-pos w)]
    (dotimes [_ (inc n)] (fio/buf-off w 0))
    (let [entries (fio/buf-pos w)]
      (dotimes [i n]
        (let [at (+ start (* i width))]
          ;; patch offset i (relative to entries) at its slot
          (let [saved (fio/buf-pos w)]
            (fio/buf-seek w at)
            (fio/buf-off w (- saved entries))
            (fio/buf-seek w saved)))
        (let [body (scalar-body (nth syms i))] (fio/buf-bytes w body 0 (byte-len body))))
      (let [saved (fio/buf-pos w)]
        (fio/buf-seek w (+ start (* n width)))
        (fio/buf-off w (- saved entries))
        (fio/buf-seek w saved)))))

(defn- write-shapetab! [w cx width]
  (let [shapes (:shape-list cx) n (count shapes) start (fio/buf-pos w)]
    (dotimes [_ (inc n)] (fio/buf-off w 0))
    (let [entries (fio/buf-pos w)
          patch (fn [i]
                  (let [saved (fio/buf-pos w)]
                    (fio/buf-seek w (+ start (* i width)))
                    (fio/buf-off w (- saved entries))
                    (fio/buf-seek w saved)))]
      (dotimes [i n]
        (patch i)
        (let [{:keys [t ks]} (nth shapes i)]
          (fio/buf-varint w (count ks))
          (fio/buf-varint w (if (nil? t) 0 (inc (long t))))
          (doseq [k ks] (fio/buf-varint w (long (get (:sym cx) k))))))
      (patch n))))

(defn- write-typetab! [w cx]
  (let [types (:type-list cx)]
    (fio/buf-varint w (count types))
    (doseq [{:keys [key fp fields]} types]
      (let [kb (enc/utf8-bytes key)]
        (fio/buf-varint w (byte-len kb)) (fio/buf-bytes w kb 0 (byte-len kb))
        (fio/buf-i64 w (fp-cell fp))
        (fio/buf-varint w (count fields))
        (doseq [{:keys [name class]} fields]
          (fio/buf-varint w (long (get (:sym cx) name)))
          (let [[cls arg] class]
            (fio/buf-u8 w (case cls :prim 0 :ref 1 :ser 2))
            (fio/buf-varint w (long (or arg 0)))))))))

(def ^:private CHUNK-TARGET 65535)

(declare header-ctx handle-digest node-hasheq*)

(defn- build-chunk
  "rep -> the chunk's bytes, per FORMAT-V1 §2. Width attempt u16, then
   u32 (the WIDE flag) when the padded total passes the target.
   Every chunk carries the root recipe (R12): flag bit 1 and a
   40-byte slot after the header — [hasheq i32][digest 32 bytes]
   [pad] — hq an explicit hasheq when a caller insists on one;
   normally nil, and the slot's hasheq is latched from the
   finished chunk's own walk (node-hasheq*), NEVER from the
   source object's host hash: a recipe must be a function of
   the chunk's content alone, or a repack of the same value in
   another representation (a twin sister, a generic record)
   latches a different word and byte-identity — and hash
   equality across representations — breaks. The digest is
   computed over the finished chunk the same way. The tables
   are offset-addressed, so a reader that predates recipes
   skips the slot."
  ^bytes [cx rep hq]
  (loop [width 2]
    (let [w (fio/buf width)]
      (dotimes [_ 32] (fio/buf-u8 w 0))
      (dotimes [_ 40] (fio/buf-u8 w 0))
      (let [symtab (fio/buf-pos w)]
        (write-symtab! w cx width)
        (let [shapetab (fio/buf-pos w)]
          (write-shapetab! w cx width)
          (let [typetab (if (seq (:type-list cx)) (fio/buf-pos w) 0)]
            (when (pos? typetab) (write-typetab! w cx))
            (let [root (write-node! w cx rep)
                  len (fio/buf-pos w)
                  padded (bit-and (+ len 7) (bit-not 7))]
              (if (and (== width 2) (> padded CHUNK-TARGET))
                (recur 4)
                (do (loop [i len] (when (< i padded) (fio/buf-u8 w 0) (recur (inc i))))
                    (fio/buf-seek w 0)
                    (fio/buf-u8 w 0x45) (fio/buf-u8 w 0x56)    ;; magic "EVS1"
                    (fio/buf-u8 w 0x53) (fio/buf-u8 w 0x31)
                    (fio/buf-u8 w 1)                       ;; version
                    (fio/buf-u8 w 0)                       ;; kind: packed
                    (fio/buf-u16 w (bit-or (if (== width 4) 1 0)
                                    2
                                    (if (pos? typetab) 4 0)))
                    (fio/buf-u32 w padded)
                    (fio/buf-u32 w root)
                    (fio/buf-u16 w (count (:syms cx)))
                    (fio/buf-u16 w (count (:shape-list cx)))
                    (fio/buf-u32 w symtab)
                    (fio/buf-u32 w shapetab)
                    (fio/buf-u32 w typetab)
                    ;; the recipe: hasheq and digest computed over the
                    ;; finished chunk (its slot still zero — neither walk
                    ;; reads the slot), then patched into the writer and
                    ;; the chunk taken again
                    (let [out (fio/chunk-bytes (fio/buf-chunk w padded))
                          ctx (header-ctx out false)
                          hqv (int (or hq
                                       (try (node-hasheq* ctx root)
                                            (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil))
                                       -2147483648))
                          dg (try (handle-digest ctx root)
                                  (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil))]
                      (fio/buf-patch-u32 w 32 hqv)
                      (when dg
                        (fio/buf-seek w 36)
                        (doseq [x dg] (fio/buf-u8 w x)))
                      (fio/chunk-bytes (fio/buf-chunk w padded))))))))))))

(defn pack-rep
  "A rep as chunk bytes. Public for eve.flat.sister's construction
   path, which reaches it by requiring-resolve on the runtime-require
   hosts and by a static table on ClojureScript.
   On ClojureScript (and nbb) a plain EDN rep is packed by a native
   JS FORMAT-V1 writer (eve.flat.pack-plain): the same bytes as the
   scan/build-chunk path, including the root recipe."
  (^bytes [rep]
   (or #?(:cljs (pack-plain/pack-plain rep (hash rep)))
       (build-chunk (scan rep) rep nil)))
  (^bytes [rep hq]
   (or #?(:cljs (pack-plain/pack-plain rep hq))
       (build-chunk (scan rep) rep hq))))

(declare open)

(defn pack
  "A plain value as a flat: the handle reading its packed chunk.
   A scalar is never a flat value — it is itself (FORMAT-V1 §2).
   A value a rep converter claims (a registered eve-deftype instance)
   packs as its type."
  [v]
  (if (or (nil? v) (and (not (coll? v))
                        (not (host-bytes? v))
                        #?@(:clj [(not (record? v))])
                        (nil? (convert-rep v))))
    v
    #?(:lpy (or (when-let [bs (eve_flat_pack_plain/pack_value v)]
                  (open bs))
                (open (pack-rep (to-rep v))))
       :cljs (or (when-let [bs (pack-plain/pack-value v)]
                   (open bs))
                 (open (pack-rep (to-rep v))))
       :default (open (pack-rep (to-rep v))))))

(defn pack-all
  "Values packed into chunks of at most 65,535 bytes: a vector of chunk
   byte arrays, each chunk's root the vector of its values. A chunk
   seals when the next value would pass the target; one oversize value
   makes one wide chunk (FORMAT-V1 §2, C1.3). The seal test is exact —
   a candidate prefix is encoded in full and its length measured —
   but the longest fitting prefix is found by binary search over
   prefix lengths, so a chunk costs O(log n) full encodes, not one
   full encode per row against a chunk-sized accumulator. Encoded
   length is not strictly non-decreasing in prefix length (a vector
   of maps switches representation as it grows), so the search can in
   principle stop a row or two short of the longest fitting prefix;
   every emitted chunk is a prefix that was itself measured fitting
   (or the lone oversize row), so the size cap always holds."
  [values]
  (let [rows (vec values)]
    (if (empty? rows)
      [(pack-rep (to-rep []))]
      (let [reps (mapv to-rep rows)
            n (count reps)
            fits? (fn [from k]
                    (<= (byte-len (pack-rep (subvec reps from (+ from k))))
                        CHUNK-TARGET))]
        (loop [from (long 0) out []]
          (if (== from n)
            out
            (let [remaining (- n from)
                  k (long (if (fits? from remaining)
                      remaining
                      ;; largest k in [1, remaining) measured fitting;
                      ;; lo only ever takes a measured-fitting count,
                      ;; or the lone-row floor (the oversize rule)
                      (loop [lo (long 1) hi (long remaining)]
                        (if (>= lo hi)
                          lo
                          (let [mid (quot (+ lo hi 1) 2)]
                            (if (fits? from mid)
                              (recur mid hi)
                              (recur lo (dec mid))))))))]
              (recur (+ from k)
                     (conj out (pack-rep (subvec reps from (+ from k))))))))))))

;; ---------------------------------------------------------------------------
;; reading: the chunk context and in-place node access (FORMAT-V1 §2, §10)
;; ---------------------------------------------------------------------------

;; A chunk's reading context. b: the chunk as eve.flat-io holds it (fio/chunk
;; of the bytes; every read takes it); bs: the host bytes themselves (what a
;; handle answers as its chunk bytes). The two arms differ only in the JVM's
;; field hints.
#?(:clj (deftype Ctx [b ^bytes bs ^long root wide?
                      syms shapes types ^clojure.lang.Atom decodes
                      rhq rdigest ^clojure.lang.Atom plans])
   :cljs (deftype Ctx [b bs root wide? syms shapes types decodes rhq rdigest plans])
   ;; let-go: the unhinted arm, as on ClojureScript
   :lg (deftype Ctx [b bs root wide? syms shapes types decodes rhq rdigest plans])
   :cljr (deftype Ctx [b bs root wide? syms shapes types decodes rhq rdigest plans])
   :clje (deftype Ctx [b bs root wide? syms shapes types decodes rhq rdigest plans])
   :lpy (deftype Ctx [b bs root wide? syms shapes types decodes rhq rdigest plans]))

(defn- parse-symtab [b ^long at ^long nsym wide?]
  (let [w (if wide? 4 2)
        entries (+ at (* w (inc nsym)))]
    (mapv (fn [i] (scalar-at b (+ entries (off-at b (+ at (* w i)) wide?)))) (range nsym))))

(defn- parse-shapetab [b at nshape wide? syms]
  (let [w (if wide? 4 2)
        entries (+ at (* w (inc nshape)))]
    (mapv (fn [i]
            (let [p (+ entries (off-at b (+ at (* w i)) wide?))
                  [n p] (fio/varint b p)
                  [t p] (fio/varint b p)]
              (loop [j 0 p p ks [] codes []]
                (if (== j n)
                  {:t (when (pos? t) (dec t)) :ks ks :codes codes}
                  (let [[c p] (fio/varint b p)] (recur (inc j) p (conj ks (nth syms c)) (conj codes c)))))))
          (range nshape))))

(defn- parse-typetab [b ^long at]
  (if (zero? at)
    []
    (let [[nt p] (fio/varint b at)]
      (loop [i 0 p p out []]
        (if (== i nt)
          out
          (let [[kl p] (fio/varint b p)
                tkey (fio/utf8 b p kl) p (+ p kl)
                fp (fio/i64 b p) p (+ p 8)
                [nf p] (fio/varint b p)
                [fields p] (loop [j 0 p p fs []]
                             (if (== j nf)
                               [fs p]
                               (let [[_code p] (fio/varint b p)
                                     cls (fio/u8 b p) p (inc p)
                                     [_arg p] (fio/varint b p)]
                                 (recur (inc j) p (conj fs {:class (case (int cls) 0 :prim 1 :ref :ser)})))))]
            (recur (inc i) p (conj out {:key tkey :fp fp :fields fields}))))))))

(defn- header-ctx
  "The Ctx for chunk bytes bs. checked? enforces the §10 header door:
   magic, version, length = the bytes given, table offsets in range."
  [bs checked?]
  (let [b (when (some? bs) (fio/chunk bs))
        n (if (some? b) (fio/chunk-len b) 0)]
    (when (< n 32) (throw (ex-info "flat chunk: shorter than a header" {:type ::short})))
    (when-not (and (== 0x45 (fio/u8 b 0)) (== 0x56 (fio/u8 b 1)) (== 0x53 (fio/u8 b 2)) (== 0x31 (fio/u8 b 3)))
      (throw (ex-info "flat chunk: bad magic" {:type ::bad-magic})))
    (let [version (fio/u8 b 4) kind (fio/u8 b 5) flags (fio/u16 b 6)
          length (fio/u32 b 8) root (fio/u32 b 12)
          nsym (fio/u16 b 16) nshape (fio/u16 b 18)
          symtab (fio/u32 b 20) shapetab (fio/u32 b 24) typetab (fio/u32 b 28)
          wide? (pos? (bit-and flags 1))]
      (when (> version 1) (throw (ex-info "flat chunk: version above this reader's" {:type ::bad-version :version version})))
      (when-not (== kind 0) (throw (ex-info "flat chunk: not a packed chunk" {:type ::bad-kind :kind kind})))
      (when (pos? (bit-and flags 0xFF00))
        (throw (ex-info "flat chunk: a required feature this reader lacks" {:type ::required-feature :flags flags})))
      (when checked?
        (when-not (== length n)
          (throw (ex-info "flat chunk: header length is not the bytes given" {:type ::bad-length :length length :given n})))
        ;; §10: table offsets are range-checked only — they are NOT
        ;; aligned (§2–§5 lay the tables out contiguously). An
        ;; alignment demand here refuses the writer's own chunks;
        ;; eve.flat.check's door range-checks only. (F3FIX claim 2,
        ;; reconciled from the release lineage; this tree predated it.)
        (doseq [[nm off] [["symtab" symtab] ["shapetab" shapetab] ["typetab" typetab] ["root" root]]]
          (when (and (pos? off) (>= off length))
            (throw (ex-info "flat chunk: a table offset out of range" {:type ::bad-offset :table nm :offset off})))))
      (let [syms (parse-symtab b symtab nsym wide?)
            shapes (parse-shapetab b shapetab nshape wide? syms)
            types (parse-typetab b typetab)
            ;; the root recipe (R12): flag bit 1, a 40-byte slot
            ;; at offset 32 — [hasheq i32][digest 32 bytes][pad].
            ;; The tables are offset-addressed, so a reader that
            ;; predates recipes skips the slot without knowing.
            recipe? (pos? (bit-and flags 2))
            rhq (when recipe?
                  (let [u (fio/u32 b 32)]
                    (when (not (== u 2147483648))
                      (int (if (>= u 2147483648) (- u 4294967296) u)))))
            rdigest (when recipe?
                      #?(:jolt (loop [i 0 acc [] nz? false]
                                 (if (== i 32)
                                   (if nz? acc nil)
                                   (let [x (fio/u8 b (+ 36 i))]
                                     (recur (inc i) (conj acc x) (or nz? (pos? x))))))
                         :default (let [dg (mapv (fn [i] (fio/u8 b (+ 36 i))) (range 32))]
                                    (when (some pos? dg) dg))))]
        (->Ctx b bs root wide? syms shapes types (atom 0) rhq rdigest (atom {}))))))

;; --- columns, parsed -----------------------------------------------

(defn- parse-column
  "The column at p holding n values: {:kind :n :valid off|nil :data off
   :end off} plus kind extras (:base/:width, :values-col/:ends/:runs,
   :batch-off for struct)."
  [b ^long p ^long n wide?]
  (let [raw (fio/u8 b p) nils? (>= raw 128) kind (bit-and raw 0x7F)
        q (inc p)
        valid (when nils? q)
        q (if nils? (+ q (quot (+ n 7) 8)) q)]
    (case (int kind)
      16 {:kind 16 :n n :valid valid :data q :end (+ q (quot (+ n 7) 8))}
      6 (let [d (bit-and (+ q 3) (bit-not 3))] {:kind 6 :n n :valid valid :data d :end (+ d (* 4 n))})
      10 (let [d (bit-and (+ q 7) (bit-not 7))] {:kind 10 :n n :valid valid :data d :end (+ d (* 8 n))})
      8 (let [d (bit-and (+ q 3) (bit-not 3))] {:kind 8 :n n :valid valid :data d :end (+ d (* 4 n))})
      9 (let [d (bit-and (+ q 7) (bit-not 7))] {:kind 9 :n n :valid valid :data d :end (+ d (* 8 n))})
      17 {:kind 17 :n n :valid valid :data q :end (+ q n)}
      18 {:kind 18 :n n :valid valid :data q :end (+ q (* 2 n))}
      19 (let [d (bit-and (+ q 3) (bit-not 3))
               total (fio/u32 b (+ d (* 4 n)))]
           {:kind 19 :n n :valid valid :data d :end (+ d (* 4 (inc n)) total)})
      20 {:kind 20 :n n :valid valid :data q :end (+ q (* (if wide? 4 2) n))}
      21 {:kind 21 :n n :valid valid :batch-off (off-at b q wide?) :end (+ q (if wide? 4 2))}
      22 (let [base (fio/i64 b q) width (fio/u8 b (+ q 8))
               d (bit-and (+ q 9 (dec width)) (bit-not (dec width)))]
           {:kind 22 :n n :valid valid :base base :width width :data d :end (+ d (* width n))})
      23 (let [[runs q2] (fio/varint b q)
               vc (parse-column b q2 runs wide?)
               ends (:end vc)]
           {:kind 23 :n n :valid valid :runs runs :values-col vc :ends ends
            :end (+ ends (* 4 runs))})
      (throw (ex-info "flat chunk: unknown column kind" {:type ::bad-column :kind kind})))))

(declare read-value row-handle)

;; Defined on every host, though only the JVM-family handle types
;; implement it: shared code (unpack) asks (satisfies? IAttachMeta x)
;; on all of them, and on a host where the protocol itself is
;; undefined that question throws instead of answering false
;; (measured on ClojureScript: unpack of a batch handle died in
;; native-satisfies?). Where no type implements it — cljs, whose
;; handles carry native IMeta — the answer is simply false and the
;; caller reads (meta x), which is the correct semantics there.
(defprotocol IAttachMeta
  "Attach metadata without routing through clojure.lang.IObj: the
   map/set proxy classes do not satisfy IObj in every classloader
   world a source-loaded namespace runs in (measured 2026-11-12:
   (instance? IObj flat-map) is false under clojure.main source
   loading, so clojure.core/with-meta silently drops map metadata)."
  (-attach-meta [h m])
  (-hmeta [h]))

(declare col-at)

(defn- col-at-run
  "Kind 23 (run-coded column): binary-search the run containing i,
   then read that run's value column. Its own function, not a
   col-at case arm: the loop + recursive call inside col-at's
   large case makes jank's codegen emit the recursive call with
   an undeclared temporary (measured at the multichunk smoke
   row); the small function compiles cleanly."
  [^Ctx ctx col ^long i]
  (let [b (.-b ctx)
        ends (long (:ends col)) runs (long (:runs col))
        ;; binary search: the first end > i
        r (loop [lo 0 hi (dec runs)]
            (if (== lo hi) lo
                (let [mid (quot (+ lo hi) 2)]
                  (if (> (fio/u32 b (+ ends (* 4 mid))) i)
                    (recur lo mid)
                    (recur (inc mid) hi)))))]
    (col-at ctx (:values-col col) r)))

(defn- col-at
  "Row i of a parsed column: a scalar, or a handle for kinds 20/21.
   The column's fields are bound once in the let: the repeated
   (long (:data col)) shape in every case arm trips a jank codegen
   defect (an undeclared temporary in the generated C++ for this
   function, measured at the multichunk smoke row)."
  [^Ctx ctx col ^long i]
  (let [b (.-b ctx)
        ;; kinds 21/23 carry no :data (21 reads :batch-off, 23
        ;; delegates to col-at-run): the default keeps the hoist
        ;; from casting nil for them — the un-hoisted original
        ;; cast :data only inside the arms that use it, and the
        ;; hoist's first version NPE'd on every nested-batch read
        ;; (measured: eve.flat.build-test direct-build-is-placeable
        ;; and eve.flat.sister-test on the JVM, RT.longCast on nil
        ;; at this let, where the release tip reads clean).
        data (long (or (:data col) 0))
        valid (when (:valid col) (long (:valid col)))
        n (long (:n col))]
    (if (and (:valid col) (zero? (bit-and (fio/u8 b (+ valid (quot i 8)))
                                           (bit-shift-left 1 (rem i 8)))))
      nil
      (case (int (:kind col))
        16 (not (zero? (bit-and (fio/u8 b (+ data (quot i 8)))
                                (bit-shift-left 1 (rem i 8)))))
        6 (fio/i32 b (+ data (* 4 i)))
        10 (i64->host (fio/i64 b (+ data (* 8 i))))
        8 (f32->host (fio/f32 b (+ data (* 4 i))))
        9 (fio/f64 b (+ data (* 8 i)))
        17 (nth (.-syms ctx) (fio/u8 b (+ data i)))
        18 (nth (.-syms ctx) (fio/u16 b (+ data (* 2 i))))
        19 (let [d data o (fio/u32 b (+ d (* 4 i))) e (fio/u32 b (+ d (* 4 (inc i))))]
             (fio/utf8 b (+ d (* 4 (inc n)) o) (- e o)))
        20 (read-value ctx (off-at b (+ data (* (if (.-wide? ctx) 4 2) i)) (.-wide? ctx)))
        21 (row-handle ctx (long (:batch-off col)) i)
        22 (let [d (long (:data col)) width (long (:width col))]
             (i64-add (:base col)
                      (case (int width)
                        1 (fio/u8 b (+ d i))
                        2 (fio/u16 b (+ d (* 2 i)))
                        4 (fio/u32 b (+ d (* 4 i))))))
        23 (col-at-run ctx col i)
        (throw (ex-info "flat chunk: unknown column kind" {:kind (:kind col)}))))))

;; --- node parts ------------------------------------------------------

(defn- tag-at ^long [^Ctx ctx ^long off] (fio/u8 (.-b ctx) off))
(defn- width-of ^long [^Ctx ctx] (if (.-wide? ctx) 4 2))

(defn- seq-parts
  "VEC (0x22) / LIST (0x25): [n varint][offsets]. [n offsets-pos]"
  [^Ctx ctx ^long off]
  (let [[n p] (fio/varint (.-b ctx) (inc off))] [n p]))

(defn- col-parts
  "COL (0x23): [n varint][column]. The parsed column."
  [^Ctx ctx ^long off]
  (let [b (.-b ctx) [n p] (fio/varint b (inc off))]
    (parse-column b p n (.-wide? ctx))))

(defn- map-parts
  "MAP (0x21): [shape varint][offsets]. [shape-index offsets-pos]"
  [^Ctx ctx ^long off]
  (let [[s p] (fio/varint (.-b ctx) (inc off))] [s p]))

(defn- kmap-parts
  "KMAP (0x27): [n varint][keys offset][values offset]."
  [^Ctx ctx ^long off]
  (let [b (.-b ctx) [n p] (fio/varint b (inc off)) w (width-of ctx)]
    [n (off-at b p (.-wide? ctx)) (off-at b (+ p w) (.-wide? ctx))]))

(defn- set-parts
  "SET (0x24): [n varint][keys offset]."
  [^Ctx ctx ^long off]
  (let [b (.-b ctx) [n p] (fio/varint b (inc off))]
    [n (off-at b p (.-wide? ctx))]))

(declare batch-parts*)

(defn- batch-parts
  "BATCH (0x26): [shape varint][n varint][column offsets].
   Memoized in the Ctx's plans (the field plan, R12): a
   batch's parts and its shape's column layout are parsed
   once per chunk, not once per row."
  [^Ctx ctx ^long off]
  (or (get @(.-plans ctx) off)
      (let [p (batch-parts* ctx off)]
        (swap! (.-plans ctx) assoc off p)
        p)))

(defn- batch-parts*
  [^Ctx ctx ^long off]
  (let [b (.-b ctx) [s p] (fio/varint b (inc off)) [n p] (fio/varint b p)]
    {:shape s :n n :cols-pos p
     :cols (delay (let [sh (nth (.-shapes ctx) s)]
                    (mapv (fn [c]
                            (parse-column b (off-at b (+ p (* (width-of ctx) c)) (.-wide? ctx)) n (.-wide? ctx)))
                          (range (count (:ks sh))))))}))

(defn- elem-at
  "Element i of the VEC/COL/LIST node at off: a scalar or a handle."
  [^Ctx ctx ^long off ^long i]
  (let [t (tag-at ctx off)]
    (cond
      (== t 0x23) (col-at ctx (col-parts ctx off) i)
      (or (== t 0x22) (== t 0x25))
      (let [[_ p] (seq-parts ctx off)]
        (read-value ctx (off-at (.-b ctx) (+ p (* (width-of ctx) i)) (.-wide? ctx))))
      :else (throw (ex-info "flat chunk: not a sequential node" {:tag t :offset off})))))

(defn- elem-count ^long [^Ctx ctx ^long off]
  (let [t (tag-at ctx off)]
    (cond
      (== t 0x23) (long (:n (col-parts ctx off)))
      (or (== t 0x22) (== t 0x25)) (long (first (seq-parts ctx off)))
      (== t 0x26) (long (:n (batch-parts ctx off)))
      :else (throw (ex-info "flat chunk: not a sequential node" {:tag t :offset off})))))

(defn- seq-of-node
  "A lazy seq of read-value over the sequential node at off, or nil
   when the node is empty. The outer seq matters on ClojureScript:
   cljs.core/seq returns an ISeqable's -seq verbatim, so a raw
   (map ...) — an empty LazySeq object, not nil — would walk one
   phantom nil through map/reduce. (On the JVM, LazySeq.seq()
   normalizes this itself.)"
  [^Ctx ctx ^long off]
  (let [n (elem-count ctx off)]
    (seq (map (fn [i] (elem-at ctx off i)) (range n)))))

;; --- equality and hash over nodes (FORMAT-V1 §8) ----------------------

(declare flat-emit! sym-entry-bytes emit-flat-value! emit-framed! emit-i32le sha-vec handle-digest node-equiv-walk digest-of handle-ctx handle-off eve-digest)

(defn- rep-hasheq ^long [x]
  ;; clojure.core/hash IS hasheq over Eve values (and Murmur3's
  ;; hashLong over longs) on every host; the bare `hasheq` var is
  ;; not resolvable under bb's core.
  (hash x))

(declare node-hasheq*)

(defn- node-hasheq
  "eve.platform's fold, computed over the node at off without
   materializing the value: hash-ordered for sequentials,
   hash-unordered of [k v] entries for maps, hash-unordered for
   sets — as a slab value's would be. (clojure.lang.Murmur3's own
   folds compute the same values, but the class is not resolvable
   on every :clj host — bb's SCI has no Murmur3 — so the fold goes
   through eve.platform, whose eve.core folds every host has.)"
  ^long [^Ctx ctx ^long off]
  (if (and (== off (.-root ctx)) (some? (.-rhq ctx)))
    ;; the root recipe (R12): the hasheq latched at pack time
    (long (.-rhq ctx))
    (node-hasheq* ctx off)))

(defn- node-hasheq*
  ^long [^Ctx ctx ^long off]
  (let [t (tag-at ctx off)]
    (cond
      (or (== t 0x22) (== t 0x25) (== t 0x23))
      (platform/hash-ordered (seq-of-node ctx off))
      (== t 0x26)
      (platform/hash-ordered (map (fn [i] (row-handle ctx off i)) (range (long (:n (batch-parts ctx off))))))
      (== t 0x21)
      (let [[s p] (map-parts ctx off) sh (nth (.-shapes ctx) s) ks (:ks sh) w (width-of ctx)]
        (platform/hash-unordered
         (map (fn [i] [(nth ks i)
                       (read-value ctx (off-at (.-b ctx) (+ p (* w i)) (.-wide? ctx)))])
              (range (count ks)))))
      (== t 0x27)
      (let [[n ko vo] (kmap-parts ctx off)]
        (platform/hash-unordered
         (map (fn [i] [(elem-at ctx ko i) (elem-at ctx vo i)]) (range n))))
      (== t 0x24)
      (let [[n ko] (set-parts ctx off)]
        (platform/hash-unordered (map (fn [i] (elem-at ctx ko i)) (range n))))
      (== t 0x29)
      (let [w (width-of ctx) vo (off-at (.-b ctx) (+ off 1) (.-wide? ctx))]
        (node-hasheq ctx vo))
      :else (rep-hasheq (read-value ctx off)))))

(defn- entries-of-node
  "[[key value] ...] of the map node at off (MAP, KMAP, or a row's map)."
  [^Ctx ctx ^long off]
  (let [t (tag-at ctx off)]
    (cond
      (== t 0x21)
      (let [[s p] (map-parts ctx off) sh (nth (.-shapes ctx) s) w (width-of ctx)]
        (mapv (fn [i] [(nth (:ks sh) i)
                      (read-value ctx (off-at (.-b ctx) (+ p (* w i)) (.-wide? ctx)))])
              (range (count (:ks sh)))))
      (== t 0x27)
      (let [[n ko vo] (kmap-parts ctx off)]
        (mapv (fn [i] [(elem-at ctx ko i) (elem-at ctx vo i)]) (range n)))
      :else (throw (ex-info "flat chunk: not a map node" {:tag t :offset off})))))

(defn- key-equiv
  "Key comparison for map lookups: clojure.core/=, which is
   Util/equiv on the JVM and equiv over the value model on every
   other host."
  [a b]
  (= a b))

;; --- key search (FORMAT-V1 §4, "Key order") ----------------------------------
;; A MAP's keys are its shape's symbols and a KMAP's are its keys node's elements,
;; both in ascending key-byte order (eve.codec/encode-key's bytes, the order
;; d/compare-bytes gives). From SEARCH-MIN keys a lookup is a binary search in that
;; order, comparing the key against the stored bytes in place (fio/key-cmp for a
;; string against a stored string, fio/bytes-cmp against its key bytes otherwise);
;; below it, and for a key whose = is not its bytes' equality, the walk compares
;; decoded keys by =, as before. Either answers the same key.

(def ^:private SEARCH-MIN
  "From this many keys a lookup is a binary search; below it a walk (= on a
   few decoded keys costs less than one search's key bytes)."
  16)

(defn- in-place-key?
  "Is k's = the equality of its key bytes, so a search in key-byte order
   finds exactly the key = would? A string, keyword, symbol, integer (an
   integer's bytes are by magnitude: (= 5 5N) is one key), boolean or
   character. Not a float (0.0 = -0.0 and NaN != NaN where their bytes say
   otherwise) and not nil (a node stores nil as 0x00, a key as no bytes)."
  [k]
  (or (string? k) (keyword? k) (symbol? k) (integer? k) (boolean? k) (char? k)))

(defn- body-cmp
  "-1 / 0 / 1: the scalar body stored at [s, s+n) of chunk b against key k, in
   key-byte order (FORMAT-V1 §3: a scalar's stored bytes are its key bytes
   without the magic). A string k against a stored string: fio/key-cmp over
   its utf-8, in place; against any other body, by tag (a string's 0x05 / 0x06
   sorts below a keyword's or symbol's tag, above a number's). Any other k:
   the stored bytes against kb, k's encode-key bytes."
  [b s n k kb]
  (let [s (long s) n (long n) t (fio/u8 b s)]
    (if (string? k)
      (cond (== t 0x05) (fio/key-cmp b (+ s 2) (fio/u8 b (inc s)) k)
            (== t 0x06) (fio/key-cmp b (+ s 5) (fio/u32 b (inc s)) k)
            (< t 0x05) -1
            :else 1)
      (fio/bytes-cmp b s n kb 2 (- (byte-len kb) 2)))))

(defn- sym-cmp
  "body-cmp of symbol code c's symbol-table entry against key k."
  ^long [^Ctx ctx ^long c k kb]
  (let [b (.-b ctx) wide? (.-wide? ctx) w (if wide? 4 2)
        symtab (fio/u32 b 20) nsym (fio/u16 b 16)
        entries (+ symtab (* w (inc nsym)))
        s (+ entries (off-at b (+ symtab (* w c)) wide?))
        e (+ entries (off-at b (+ symtab (* w (inc c))) wide?))]
    (body-cmp b s (- e s) k kb)))

(defn- shape-index
  "The index of key k in shape sh, or -1."
  ^long [^Ctx ctx sh k]
  (let [ks (:ks sh) n (count ks)]
    (if (or (< n SEARCH-MIN) (not (in-place-key? k)))
      (loop [i 0] (cond (== i n) -1
                        (key-equiv k (nth ks i)) i
                        :else (recur (inc i))))
      (let [codes (:codes sh) kb (when-not (string? k) (key-bytes k))]
        (loop [lo 0 hi (dec n)]
          (if (> lo hi)
            -1
            (let [mid (quot (+ lo hi) 2)
                  r (sym-cmp ctx (nth codes mid) k kb)]
              (cond (neg? r) (recur (inc mid) hi)
                    (pos? r) (recur lo (dec mid))
                    :else mid))))))))

(defn- searchable-keys?
  "Does the KMAP keys node at ko hold scalars only, so every key has its key
   bytes in place or in its column? A COL without a validity bitmap (no nil
   key), or a VEC whose every element is a SYM or a scalar node (no nil, no
   collection). Asked once a node: the Ctx's plans keep the answer."
  [^Ctx ctx ^long ko]
  (let [pk [::searchable ko] hit (get @(.-plans ctx) pk ::none)]
    (if-not (identical? hit ::none)
      hit
      (let [b (.-b ctx) t (tag-at ctx ko)
            r (cond
                (== t 0x23) (let [col (col-parts ctx ko)]
                              (and (nil? (:valid col)) (not (== 20 (long (:kind col))))))
                (== t 0x22) (let [[n p] (seq-parts ctx ko) w (width-of ctx)]
                              (loop [i 0]
                                (cond (== i n) true
                                      ;; a SYM, or an Eve scalar body (tags 0x01–0x1B)
                                      (let [et (fio/u8 b (off-at b (+ p (* w i)) (.-wide? ctx)))]
                                        (or (== et 0x20) (<= 0x01 et 0x1B)))
                                      (recur (inc i))
                                      :else false)))
                :else false)]
        (swap! (.-plans ctx) assoc pk r)
        r))))

(defn- key-elem-cmp
  "-1 / 0 / 1: key i of the KMAP keys node at ko (searchable-keys?) against k."
  [^Ctx ctx ko i k kb]
  (let [ko (long ko) i (long i) b (.-b ctx) t (tag-at ctx ko)]
    (if (== t 0x23)
      (let [col (col-parts ctx ko) data (long (:data col))]
        (case (int (:kind col))
          17 (sym-cmp ctx (fio/u8 b (+ data i)) k kb)
          18 (sym-cmp ctx (fio/u16 b (+ data (* 2 i))) k kb)
          19 (let [n (long (:n col))
                   o (fio/u32 b (+ data (* 4 i))) e (fio/u32 b (+ data (* 4 (inc i))))
                   len (- e o)]
               (if (string? k)
                 (fio/key-cmp b (+ data (* 4 (inc n)) o) len k)
                 (let [st (if (< len 256) 0x05 0x06) kt (fio/u8 (fio/chunk kb) 2)]
                   (cond (< st kt) -1 (> st kt) 1 :else 0))))
          ;; a number or boolean column: the cell's key bytes against k's
          (let [kb (or kb (key-bytes k))
                r (d/compare-bytes (key-bytes (col-at ctx col i)) kb)]
            (cond (neg? r) -1 (pos? r) 1 :else 0))))
      (let [[_ p] (seq-parts ctx ko)
            o (off-at b (+ p (* (width-of ctx) i)) (.-wide? ctx))]
        (if (== (fio/u8 b o) 0x20)
          (sym-cmp ctx (fio/varint-v b (inc o)) k kb)
          (body-cmp b o (scalar-len b o) k (or kb (when-not (string? k) (key-bytes k)))))))))

(defn- kmap-index
  "The index of key k among the n keys of the KMAP keys node at ko, or -1."
  ^long [^Ctx ctx ^long ko ^long n k]
  (if (or (< n SEARCH-MIN) (not (in-place-key? k)) (not (searchable-keys? ctx ko)))
    (loop [i 0] (cond (== i n) -1
                      (key-equiv k (elem-at ctx ko i)) i
                      :else (recur (inc i))))
    (let [kb (when-not (string? k) (key-bytes k))]
      (loop [lo 0 hi (dec n)]
        (if (> lo hi)
          -1
          (let [mid (quot (+ lo hi) 2)
                r (key-elem-cmp ctx ko mid k kb)]
            (cond (neg? r) (recur (inc mid) hi)
                  (pos? r) (recur lo (dec mid))
                  :else mid)))))))

(defn- lookup-node
  "The value for k in the map node at off, or nf. A MAP finds k in its shape,
   a KMAP among its keys node's elements (shape-index, kmap-index: a binary
   search in key-byte order from SEARCH-MIN keys, FORMAT-V1 §4)."
  [^Ctx ctx ^long off k nf]
  (let [t (tag-at ctx off)]
    (cond
      (== t 0x21)
      (let [[s p] (map-parts ctx off) sh (nth (.-shapes ctx) s)
            i (shape-index ctx sh k)]
        (if (neg? i) nf (read-value ctx (off-at (.-b ctx) (+ p (* (width-of ctx) i)) (.-wide? ctx)))))
      (== t 0x27)
      (let [[n ko vo] (kmap-parts ctx off)
            i (kmap-index ctx ko n k)]
        (if (neg? i) nf (elem-at ctx vo i)))
      :else nf)))

(declare node-equiv)

;; A generated sister (eve.flat.source/ISister) is a flat handle with no
;; node position of its own (a batch row is not a node): it answers = by
;; its type key and fields (the generator's equals / IEquiv). The JVM
;; asks the interface every inline implementation carries, in one
;; instanceof (THE FAST HANDLE TEST above).
(defn- sister-value? [x]
  #?(:jank (satisfies? source/ISister x) :bb (satisfies? source/ISister x) :glj (satisfies? source/ISister x)
     :clj (instance? eve.flat.source.ISister x)
     :default (satisfies? source/ISister x)))

(defn- equiv-any
  "equiv between two read values (either may be a handle or plain):
   digests first per the format's equality section, scalar leaves by
   their Eve bytes (so the float edges follow the digest, not host
   number equiv), else a walk. A sister compares by its own equality."
  [a b]
  (cond
    (flat-handle? a) (if (sister-value? a) (= a b) (node-equiv (handle-ctx a) (handle-off a) b))
    (flat-handle? b) (if (sister-value? b) (= b a) (node-equiv (handle-ctx b) (handle-off b) a))
    (and (not (coll? a)) (not (coll? b))
         (not (host-bytes? a)) (not (host-bytes? b)))
    (let [ba (try (flat-bytes-of-plain a) (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil))
          bb (try (flat-bytes-of-plain b) (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil))]
      (if (and ba bb) (enc/bytes= ba bb) (= a b)))
    (host-bytes? a)
    (and (host-bytes? b) (enc/bytes= a b))
    :else (= a b)))

(defn- node-equiv
  "equiv between the node at off and any other value: digest first
   when both sides have one (a plain value's digest is Eve's own
   digest of it), else an element walk in the other side's own terms."
  [^Ctx ctx ^long off other]
  (cond
    (nil? other) false
    :else
    (let [da (handle-digest ctx off)
          db (if (flat-handle? other) (digest-of other) (eve-digest other))]
      (if (and da db)
        (= da db)
        (node-equiv-walk ctx off other)))))

(defn- node-equiv-walk [^Ctx ctx ^long off other]
  (let [t (tag-at ctx off)]
    (cond
      (or (== t 0x22) (== t 0x25) (== t 0x23) (== t 0x26))
      (and (sequential? other) (not (map? other)) (not (set? other))
           (== (elem-count ctx off) (count other))
           (loop [i 0 xs (seq other)]
             (cond (nil? xs) true
                   (equiv-any (if (== t 0x26) (row-handle ctx off i) (elem-at ctx off i)) (first xs))
                   (recur (inc i) (next xs))
                   :else false)))
      (or (== t 0x21) (== t 0x27))
      (and (map? other)
           (== (count (entries-of-node ctx off)) (count other))
           (every? (fn [[k v]] (and (contains? other k) (equiv-any v (get other k))))
                   (entries-of-node ctx off)))
      (== t 0x24)
      (and (set? other)
           (let [[n ko] (set-parts ctx off)]
             (and (== n (count other))
                  (loop [i 0] (cond (== i n) true
                                    (some (fn [e] (equiv-any (elem-at ctx ko i) e)) other) (recur (inc i))
                                    :else false)))))
      (== t 0x29)
      (node-equiv ctx (off-at (.-b ctx) (+ off 1) (.-wide? ctx)) other)
      :else (= (read-value ctx off) other))))

;; --- the content digest: SHA-256 over Eve's flat bytes (FORMAT-V1 §8),
;; --- re-emitted from the packed nodes without building host collections


;; The emit/digest machinery — ONE implementation for every host
;; (trunk merge, Nov 14 2026): the walk runs over an eve.flat-io
;; writer, whose buf-* surface every host realizes, so the emitted
;; bytes are the raw buffer's written prefix everywhere. It exists
;; on ClojureScript too: this is what gives the CLJS handles their
;; content digest, and with it the digest-first equality the JVM
;; handles always had. (Before the merge this walk existed twice —
;; a ByteArrayOutputStream copy for the JVM and this Buf copy for
;; jank — and not at all on CLJS.)

(defn- buf-bytes-out
  "A writer accumulator's written prefix as a fresh host byte array."
  [w]
  (fio/chunk-bytes (fio/buf-chunk w (fio/buf-pos w))))

(defn- emit-magic! [out]
  (fio/buf-bytes out MAGIC 0 2))

(defn flat-emit!
  "Write the flat (eve.codec) bytes of the node at off into out."
  [^Ctx ctx off out]
  (let [b (.-b ctx) t (tag-at ctx off)]
    (cond
      (== t 0x00) nil
      (== t 0x20) (let [[c _] (fio/varint b (inc off)) eb (sym-entry-bytes ctx c)]
                    (emit-magic! out) (fio/buf-bytes out eb 0 (byte-len eb)))
      (or (== t 0x21) (== t 0x27))
      (let [es (entries-of-node ctx off)]
        (emit-magic! out) (fio/buf-u8 out 0xED) (emit-i32le out (count es))
        (doseq [[k v] es] (emit-framed! out k) (emit-framed! out v)))
      (or (== t 0x22) (== t 0x25) (== t 0x23))
      (do (emit-magic! out) (fio/buf-u8 out 0xEF) (emit-i32le out (elem-count ctx off))
          (doseq [x (seq-of-node ctx off)] (emit-framed! out x)))
      (== t 0x26)
      (let [bp (batch-parts ctx off) sh (nth (.-shapes ctx) (:shape bp)) cols @(:cols bp)]
        (emit-magic! out) (fio/buf-u8 out 0xEF) (emit-i32le out (:n bp))
        (doseq [i (range (:n bp))]
          (let [tmp (fio/buf 4)]
            (emit-magic! tmp) (fio/buf-u8 tmp 0xED) (emit-i32le tmp (count (:ks sh)))
            (doseq [c (range (count (:ks sh)))]
              (emit-framed! tmp (nth (:ks sh) c))
              (emit-framed! tmp (col-at ctx (nth cols c) i)))
            (let [bs (buf-bytes-out tmp)]
              (emit-i32le out (byte-len bs)) (fio/buf-bytes out bs 0 (byte-len bs))))))
      (== t 0x24)
      (let [[n ko] (set-parts ctx off)
            parts (mapv (fn [i] (let [tmp (fio/buf 4)]
                                  (emit-flat-value! tmp (elem-at ctx ko i))
                                  (buf-bytes-out tmp)))
                        (range n))
            parts (sort d/compare-bytes parts)]
        (emit-magic! out) (fio/buf-u8 out 0xEE) (emit-i32le out n)
        (doseq [p parts] (emit-i32le out (byte-len p)) (fio/buf-bytes out p 0 (byte-len p))))
      (== t 0x29) (flat-emit! ctx (off-at b (+ off 1) (.-wide? ctx)) out)
      (== t 0x2A) (let [[_ p] (fio/varint b (+ off 2))]
                    (flat-emit! ctx (off-at b p (.-wide? ctx)) out))
      :else (let [n (scalar-len b off)]
              (emit-magic! out) (fio/buf-bytes out (.-bs ctx) off n)))))

(defn- handle-digest
  "The content digest of the node at off, or nil when any leaf has none
   (a value Eve cannot digest: the walk decides equality instead)."
  [^Ctx ctx off]
  (or (when (and (== off (.-root ctx)) (some? (.-rdigest ctx)))
        ;; the root recipe (R12): the digest latched at pack time
        (.-rdigest ctx))
      (try (let [out (fio/buf 4)]
             (flat-emit! ctx off out)
             ;; cljs: hash the writer's prefix in place (no copy, no byte-vec)
             (sha-vec #?(:cljs (.subarray (fio/buf-raw out) 0 (fio/buf-pos out))
                         :default (buf-bytes-out out))))
           (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil))))

;; --- read-value --------------------------------------------------------

(defn- byte-vec
  "Host bytes bs as a vector of 0..255."
  [bs]
  (let [c (fio/chunk bs)]
    (mapv (fn [i] (fio/u8 c i)) (range (fio/chunk-len c)))))

(defn- sha-vec
  "SHA-256 of bs as a vector of unsigned byte ints — eve.digest's own
   pipeline (eve.crypto takes and returns byte vectors)."
  [bs]
  (mapv (fn [b] (bit-and b 0xFF))
        ;; eve.crypto's input per host: the JVM's and bb's (adapters/crypto/java)
        ;; take a byte array as it is; every other host's takes the bytes as a
        ;; vector of 0..255, read here through fio
        (crypto/sha256 #?(:jank (byte-vec bs) :glj (byte-vec bs) :lg (byte-vec bs)
                          :bb bs :clj bs
                          ;; host bytes (array('b') / bytearray) go straight
                          ;; to hashlib; byte-vec + python/list of every byte
                          ;; was a second multi-ms walk on every pack
                          :lpy bs
                          :cljs (if (or (vector? bs) (instance? js/Uint8Array bs)) bs (byte-vec bs))
                          :default (byte-vec bs)))))

(defn eve-digest
  "Eve's content digest of a plain value, or nil when it has none."
  [v]
  (try (sha-vec (flat-bytes-of-plain v)) (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil)))

(defn- emit-i32le [out v]
  (fio/buf-u8 out (bit-and v 0xFF)) (fio/buf-u8 out (bit-and (bit-shift-right v 8) 0xFF))
  (fio/buf-u8 out (bit-and (bit-shift-right v 16) 0xFF)) (fio/buf-u8 out (bit-and (bit-shift-right v 24) 0xFF)))

(declare emit-flat-any!)

(defn- emit-flat-value!
  [out x]
  (emit-flat-any! out x))

(defn- emit-framed!
  "Frame x as [i32le length][bytes] in out. Length is patched after the
   payload is written into the same writer: a temp writer + copy per
   entry was the digest walk's allocation cost (thread-host pack-rep)."
  [out x]
  (let [start (fio/buf-pos out)]
    (emit-i32le out 0)
    (emit-flat-value! out x)
    (fio/buf-patch-u32 out start (- (fio/buf-pos out) start 4))))

(defn- sym-entry-bytes
  "The stored scalar bytes of symbol code c (no magic)."
  [^Ctx ctx c]
  (let [b (.-b ctx) symtab (fio/u32 b 20) nsym (fio/u16 b 16) w (width-of ctx)
        entries (+ symtab (* w (inc nsym)))
        start (+ entries (off-at b (+ symtab (* w c)) (.-wide? ctx)))
        end (+ entries (off-at b (+ symtab (* w (inc c))) (.-wide? ctx)))]
    (bytes-range (.-bs ctx) start end)))

(def ^:private type-registry
  "Type key + fingerprint pairs this reader has a sister for (§6), each
   mapped to its sister factory — (fn [field-source] -> sister), the
   generated Type__sister (eve.flat.source/IFieldSource in,
   eve.flat.source/ISister out) — or nil for a type registered
   without one. Anything else typed reads as a generic record."
  (atom {}))

(defn register-type!
  "Register a type (its \"ns/Type\" key and layout fingerprint) as one
   this reader has: its instances then read as records, not generic
   records. The 3-arity also registers the type's generated sister
   factory: its instances and batch rows then read as sisters.
   Answers the [key fingerprint] pair."
  ([tkey fp] (register-type! tkey fp nil))
  ([tkey fp factory]
   (swap! type-registry assoc [tkey (fp-key fp)] factory)
   [tkey fp]))

(defn- sister-factory
  "The sister factory registered for [tkey fp], or nil."
  [tkey fp]
  (get @type-registry [tkey (fp-key fp)]))

(defn- registered-type?
  "Is [tkey fp] a type this reader has (with or without a sister)?"
  [tkey fp]
  (contains? @type-registry [tkey (fp-key fp)]))

(declare ->FlatVec ->FlatColVec ->FlatList ->FlatSet ->FlatMap
         ->FlatBatch ->FlatRow ->FlatRecord ->FlatTagged
         handle-ctx handle-off
         lookup-node entries-of-node row-lookup row-entries)

;; --- the field sources a generated sister reads through (lane H5) ----
;; On every host that realizes a sister (the JVM, babashka, ClojureScript;
;; flat lanes U4a). A row's field read is column[row]: the batch's field
;; plan (batch-field-cols: field keyword -> its parsed column, built once a
;; batch and kept in the Ctx's plans) names the column, and col-at reads
;; the cell through eve.flat-io (fio/i64, fio/f64, fio/i32 … at the
;; column's data + width x row). A record's field is its map node's slot.

(declare batch-field-cols)

(deftype RecordSource [^Ctx ctx ^long off]
  source/IFieldSource
  (-field [_ k] (lookup-node ctx off k nil))
  (-field-map [_] (into (array-map) (entries-of-node ctx off)))
  (-source-bytes [_] (.-bs ctx)))

(deftype RowSource [^Ctx ctx ^long off ^long row fcols]
  source/IFieldSource
  (-field [_ k] (let [col (get fcols k)] (if (some? col) (col-at ctx col row) nil)))
  (-field-map [_] (into (array-map) (row-entries ctx off row)))
  (-source-bytes [_] (.-bs ctx)))

(declare materialize)

(defn- plain-tag?
  "A node tag the whole-value decode walk (materialize) decodes:
   the collection tags, META and the pointer tag."
  [^long t]
  (or (<= 0x21 t 0x27) (== t 0x29) (== t 0x2A)))

(defn read-value
  "The value of the node at off: a plain scalar, or a handle reading in
   place. A META node attaches its metadata to the inner handle.
   Scalars read as themselves on every host; materialize is the
   explicit whole-value decode walk, for callers that ask for it."
  [^Ctx ctx ^long off]
  (let [b (.-b ctx) t (tag-at ctx off)]
    (cond
      (== t 0x20) (let [[c _] (fio/varint b (inc off))] (nth (.-syms ctx) c))
      (== t 0x21)
      (let [[s _] (map-parts ctx off) sh (nth (.-shapes ctx) s)]
        (if-let [ti (:t sh)]
          (let [ty (nth (.-types ctx) ti)]
            (if-let [f (sister-factory (:key ty) (:fp ty))]
              (f (->RecordSource ctx off))
              (if (registered-type? (:key ty) (:fp ty))
                (->FlatRecord ctx off nil nil nil (:key ty) (:fp ty))
                (->FlatTagged ctx off nil nil nil (:key ty) (:fp ty)))))
          (->FlatMap ctx off nil nil nil)))
      (== t 0x22) (->FlatVec ctx off nil nil nil)
      (== t 0x23) (->FlatColVec ctx off nil nil nil)
      (== t 0x24) (->FlatSet ctx off nil nil nil)
      (== t 0x25) (->FlatList ctx off nil nil nil)
      (== t 0x26) (->FlatBatch ctx off nil nil nil)
      (== t 0x27) (->FlatMap ctx off nil nil nil)
      (== t 0x29)
      (let [w (width-of ctx)
            vo (off-at b (+ off 1) (.-wide? ctx))
            mo (off-at b (+ off 1 w) (.-wide? ctx))
            inner (read-value ctx vo)]
        #?(:clj (if (satisfies? IAttachMeta inner)
                  (-attach-meta inner (read-value ctx mo))
                  inner)
           :cljr (if (satisfies? IAttachMeta inner)
                   (-attach-meta inner (read-value ctx mo))
                   inner)
           ;; IWithMeta, as at this file's other meta-attach sites:
           ;; clojure.lang.IObj names nothing on the cljs face (an
           ;; undeclared var the JVM compiler only warns about; SCI
           ;; hosts reject it outright).
           :cljs (if (satisfies? IWithMeta inner)
                   (with-meta inner (read-value ctx mo))
                   inner)))
      (== t 0x2A)
      (let [[c p] (fio/varint b (+ off 2))
            vo (off-at b p (.-wide? ctx))]
        (->FlatTagged ctx vo nil nil nil (str (nth (.-syms ctx) c)) -1))
      (== t 0x2C)
      (throw (ex-info "flat chunk: a cross-chunk reference in a lone chunk (the directory is C3's)"
                      {:type ::cross-chunk-ref :offset off}))
      :else (scalar-at b off))))

(defn row-handle
  "Row i of the BATCH node at off, as a map handle — or as the row
   type's generated sister, when a factory is registered for the
   batch's type (lane H5; on every host that realizes a sister, flat
   lanes U4a)."
  [^Ctx ctx ^long off ^long i]
  (let [bp (batch-parts ctx off) sh (nth (.-shapes ctx) (:shape bp))]
    (if-let [ti (:t sh)]
      (let [ty (nth (.-types ctx) ti)]
        (if-let [f (sister-factory (:key ty) (:fp ty))]
          (f (->RowSource ctx off i (batch-field-cols ctx off)))
          (->FlatRow ctx off i nil nil nil)))
      (->FlatRow ctx off i nil nil nil))))

;; --- handle plumbing ----------------------------------------------------

(defprotocol IHandlePos (-hctx [h]) (-hoff [h]))
(defn handle-ctx [h] (-hctx h))
(defn handle-off [h] (-hoff h))
(defn root-handle?
  "h reads its chunk's root (its chunk's bytes are its value's), not a node
   nested inside a larger value's chunk."
  [h]
  (cond
    (satisfies? IHandlePos h) (== (-hoff h) (.-root ^Ctx (-hctx h)))
    ;; a sister reads through its field source: a record node is the root
    ;; only at its chunk's root; a batch row never is
    (satisfies? source/ISister h)
    (let [src (source/-sister-source h)]
      (cond (instance? RecordSource src) (== (.-off ^RecordSource src) (.-root ^Ctx (.-ctx ^RecordSource src)))
            (instance? RowSource src) false
            :else true))
    :else true))

(defprotocol IFlatDigest (-digest [h]))
(defn digest-of [h] (-digest h))

(defprotocol IRecordInfo (-tkey [h]) (-fp [h]))

(defprotocol IRowHandle (-row-index [h]))

(defprotocol IGenericRecord)

#?(:clj (do
(defprotocol IMapSource
  (-entries [h] "[[key value] ...] with values as scalars or handles")
  (-lookup [h k nf])
  (-mcount [h])
  (-assoc-entry [h k v])
  (-dissoc-entry [h k]))
(defn- map-source? [x]   ;; THE FAST HANDLE TEST's IMapSource twin (IFlatHandle's note above)
  #?(:jank (satisfies? IMapSource x) :bb (satisfies? IMapSource x) :glj (satisfies? IMapSource x)
     :clj (or (instance? eve.flat.chunk.IMapSource x)
              (and (not (plain-non-handle? x)) (class-satisfies? :map-source IMapSource x)))
     :default (satisfies? IMapSource x)))
))

;; let-go does not read :clj, so the protocol and its test are stated
;; for it directly: the protocol text is the :clj block's, the test
;; the satisfies? spelling (no JVM interface answers on this host).
#?(:lg (do
(defprotocol IMapSource
  (-entries [h] "[[key value] ...] with values as scalars or handles")
  (-lookup [h k nf])
  (-mcount [h])
  (-assoc-entry [h k v])
  (-dissoc-entry [h k]))
(defn- map-source? [x] (satisfies? IMapSource x))
))

#?(:cljr (do
(defprotocol IMapSource
  (-entries [h] "[[key value] ...] with values as scalars or handles")
  (-lookup [h k nf])
  (-mcount [h])
  (-assoc-entry [h k v])
  (-dissoc-entry [h k]))
(defn- map-source? [x] (satisfies? IMapSource x))
))

;; clojerl: each form its own top-level conditional (a defprotocol inside a do fails there: "Could not
;; dereference ... There is no Erlang function to back it up", eve.host's IHostArray note)
#?(:clje
   (defprotocol IMapSource
     (-entries [h] "[[key value] ...] with values as scalars or handles")
     (-lookup [h k nf])
     (-mcount [h])
     (-assoc-entry [h k v])
     (-dissoc-entry [h k])))
#?(:clje (defn- map-source? [x] (satisfies? IMapSource x)))

;; basilisp reads no :clj arm, so the block above never defines
;; IMapSource there; the :lpy section's map handles implement it.
#?(:lpy (do
(defprotocol IMapSource
  (-entries [h] "[[key value] ...] with values as scalars or handles")
  (-lookup [h k nf])
  (-mcount [h])
  (-assoc-entry [h k v])
  (-dissoc-entry [h k]))
(defn- map-source? [x] (satisfies? IMapSource x))))

(defn rep-of
  "A read value (scalar or handle) as a rep."
  [x]
  (if (flat-handle? x) (-rep x) (to-rep x)))

;; emit-flat-any! and the rep helpers — one implementation for every
;; host (trunk merge, Nov 14 2026; before, a JVM copy and a jank copy
;; differed only in the byte sink, and ClojureScript had neither).
;; The map-shaped emission dispatches on the host's map-source
;; surface: IMapSource on the JVM; on ClojureScript a batch row
;; (IRowHandle) emits as its column entries, and a plain map-node
;; handle emits through flat-emit! directly, as it does on the JVM
;; through the IFlatHandle arm below.

(defn emit-flat-any!
  "The flat bytes of any read value x into out (see emit-flat-value!)."
  [out x]
  (cond
    (nil? x) nil
    ;; a map handle (a batch row included) emits as its entries — the
    ;; same bytes its node emits, since entries come in canonical order
    #?@(:clj [(map-source? x)
              (let [es (-entries x)]
                (emit-magic! out) (fio/buf-u8 out 0xED) (emit-i32le out (count es))
                (doseq [[k v] es] (emit-framed! out k) (emit-framed! out v)))]
        :lg [(map-source? x)
             (let [es (-entries x)]
               (emit-magic! out) (fio/buf-u8 out 0xED) (emit-i32le out (count es))
               (doseq [[k v] es] (emit-framed! out k) (emit-framed! out v)))]
        :cljr [(map-source? x)
               (let [es (-entries x)]
                 (emit-magic! out) (fio/buf-u8 out 0xED) (emit-i32le out (count es))
                 (doseq [[k v] es] (emit-framed! out k) (emit-framed! out v)))]
        :clje [(map-source? x)
               (let [es (-entries x)]
                 (emit-magic! out) (fio/buf-u8 out 0xED) (emit-i32le out (count es))
                 (doseq [[k v] es] (emit-framed! out k) (emit-framed! out v)))]
        :cljs [(satisfies? IRowHandle x)
               (let [es (row-entries (handle-ctx x) (handle-off x) (-row-index x))]
                 (emit-magic! out) (fio/buf-u8 out 0xED) (emit-i32le out (count es))
                 (doseq [[k v] es] (emit-framed! out k) (emit-framed! out v)))])
    (flat-handle? x) (flat-emit! (handle-ctx x) (handle-off x) out)
    :else (let [bs (flat-bytes-of-plain x)] (fio/buf-bytes out bs 0 (byte-len bs)))))

(defn- row-digest-of [h]
  (try (let [out (fio/buf 4)]
         (emit-flat-any! out h)
         (sha-vec (buf-bytes-out out)))
       (catch #?(:clj Throwable :cljs :default :lpy python/BaseException :cljr Exception :clje _ :default Throwable) _ nil)))

(defn- vec-rep-of [ctx off]
  (mapv (fn [i] (rep-of (elem-at ctx off i))) (range (elem-count ctx off))))

(defn- list-rep-of [ctx off]
  (apply list (map (fn [i] (rep-of (elem-at ctx off i))) (range (elem-count ctx off)))))

(defn- batch-rep-of [ctx off]
  (mapv (fn [i] (rep-of (row-handle ctx off i))) (range (elem-count ctx off))))

(defn- batch-seq-of [ctx off]
  (map (fn [i] (row-handle ctx off i)) (range (elem-count ctx off))))

(defn- entries-rep-of
  "The rep of a map-shaped value from its [[k v] ...] entries."
  [entries]
  (into {} (map (fn [[k v]] [(rep-of k) (rep-of v)])) entries))

;; -entries is IMapSource's first method: the protocol is defined in
;; the JVM-family arm above (the cljs handles implement the surface by
;; name through entries-of-node, and these shared helpers are the JVM
;; protocol's callers). Declared here so the helpers below name it
;; before either host's definition point.
(declare -entries)

(defn- record-entries-rep
  "The rep of a typed value: a TypedVal over its entries' reps."
  [tkey fp entries]
  (TypedVal. tkey fp
             (reduce (fn [m [k v]] (assoc m k (rep-of v))) (array-map) entries)))

(defn- map-rep-of [h]
  (entries-rep-of (-entries h)))

(defn- entries-hasheq-of
  "hash-unordered over [[k v] ...] entries — a map-shaped value's
   hasheq, as a plain map's would be computed."
  ^long [entries]
  (platform/hash-unordered
   (map (fn [[k v]] [k v]) entries)))

(defn- entries-hasheq ^long [h]
  (entries-hasheq-of (-entries h)))

(defn- entries-equiv
  "equiv of a map-shaped value (its [[k v] ...] entries) against any
   other value: a map of the same count whose every key is present
   with an equiv value."
  [entries other]
  (and (map? other)
       (== (count entries) (count other))
       (every? (fn [[k v]] (and (contains? other k) (equiv-any v (get other k))))
               entries)))

(defn- map-equiv-of [h other]
  (entries-equiv (-entries h) other))

(declare cons-flat assocN-flat pop-flat assoc-flat dissoc-flat
         disjoin-flat open bytes-of)

;; row and set element walks, defined on neutral ground: every
;; host's FlatRow / FlatSet bodies call them, but they used to sit
;; inside the JVM handle block below, which ClojureScript does not
;; read — a set or row rep on cljs called an undefined fn (measured
;; in the flat smoke's assoc row: -rep of a set value threw).
(defn- row-entries [^Ctx ctx ^long boff ^long row]
  (let [bp (batch-parts ctx boff) sh (nth (.-shapes ctx) (:shape bp)) cols @(:cols bp)]
    (mapv (fn [c] [(nth (:ks sh) c) (col-at ctx (nth cols c) row)]) (range (count (:ks sh))))))

(defn- batch-field-cols
  "The field plan of the BATCH node at boff: its shape's keys mapped to
   their parsed columns, once a batch (the Ctx's plans) — what a row
   source reads a field through, column[row]."
  [^Ctx ctx boff]
  (let [pk [::field-cols boff] hit (get @(.-plans ctx) pk)]
    (or hit
        (let [bp (batch-parts ctx boff) sh (nth (.-shapes ctx) (:shape bp))
              m (zipmap (:ks sh) @(:cols bp))]
          (swap! (.-plans ctx) assoc pk m)
          m))))

(defn- row-lookup [^Ctx ctx boff row k nf]
  (let [bp (batch-parts ctx boff) sh (nth (.-shapes ctx) (:shape bp))
        i (shape-index ctx sh k)]
    (if (neg? i) nf (col-at ctx (nth @(:cols bp) i) row))))

(defn- set-elements [^Ctx ctx ^long off]
  (let [[n ko] (set-parts ctx off)]
    (mapv (fn [i] (elem-at ctx ko i)) (range n))))

#?(:cljs nil :default (do
;; On bb and glojure the handle macros below cannot run: SCI's
;; deftype takes only its emulated interfaces (not IReduce,
;; IPersistentVector, Indexed, IPersistentStack), and glojure has
;; neither those nor proxy-over-APersistentMap. On those hosts the
;; macros define only the ->Flat* factories, which throw — they are
;; never invoked: the call sites are gated the same way, because
;; glojure's loader reaches a call of a macro defined in this same
;; file and fails with "can't take value of a macro" (its scan
;; interns the macro var before the definition evaluates). The real
;; bb/glojure handles are the written-out deftypes further below
;; ("the protocol-host handles"), spelling the same surface through
;; the seat's eve.core protocols, the way the seat's other types do.
;; The JVM gets the real handle types from these macros.
#?(:cljr
   (defn- cljr-deftype-forms
     "A JVM-shaped deftype body as ClojureCLR takes it (Object method
      names, java.util.* dropped or rewritten, hinted overloads)."
     [body]
     (let [impls (mapv (fn [impl]
                         (update impl :methods
                                 (fn [ms]
                                   (mapv (fn [m] (update m :name #(symbol (name %))))
                                         ms))))
                       (dtv/parse-protocol-impls body))]
       (dtv/impls->forms (etypes/cljr-translate-protos impls)))))

#?(:cljr
   (defmacro ^:private deftype-cljr [tname fields & body]
     `(deftype ~tname ~fields ~@(cljr-deftype-forms body))))

#?@(:bb [(defmacro defseqhandle [tname & _]
           `(defn ~(symbol (str "->" tname)) [& ~'args]
              (throw (ex-info "eve.flat handles are not available on this host yet" {}))))]
    :glj [(defmacro defseqhandle [tname & _]
            `(defn ~(symbol (str "->" tname)) [& ~'args]
               (throw (ex-info "eve.flat handles are not available on this host yet" {}))))]
    :lg [(defmacro defseqhandle [tname & _]
           `(defn ~(symbol (str "->" tname)) [& ~'args]
              (throw (ex-info "eve.flat handles are not available on this host yet" {}))))]
    :clj [(defmacro defseqhandle
  "A sequential handle over the node at off. nthf/countf/seqf/repf are
   fns of (ctx off) (nthf also takes i); flags pick the interfaces."
  [tname fields nthf countf seqf repf & {:keys [vector? stack? list?]}]
  (let [remake (map (fn [f] (if (= f 'mm) 'm f)) fields)
        mfields (mapv (fn [f] (cond (#{'_h '_d} f)
                                    (with-meta f {:unsynchronized-mutable true})
                                    (= f 'ctx) (with-meta f {:tag 'Ctx})
                                    :else f)) fields)
        base-interfaces '[clojure.lang.IHashEq clojure.lang.IObj clojure.lang.Counted
                          clojure.lang.Seqable clojure.lang.IReduce clojure.lang.IReduceInit
                          clojure.lang.Indexed java.lang.Iterable
                          IFlatHandle IHandlePos IFlatDigest]
        interfaces (cond-> base-interfaces
                     vector? (into '[clojure.lang.IPersistentVector clojure.lang.Associative
                                     clojure.lang.ILookup clojure.lang.IPersistentStack
                                     java.util.List])
                     list? (into '[clojure.lang.IPersistentList clojure.lang.IPersistentStack
                                   java.util.List]))]
    `(deftype ~tname ~mfields
       ~@interfaces
       Object
       (equals [this# other#] (node-equiv ~'ctx ~'off other#))
       (hashCode [this#] (.hasheq this#))
       (toString [this#] (pr-str (-rep this#)))
       clojure.lang.IHashEq
       (hasheq [this#] (let [v# ~'_h] (if (some? v#) (long v#) (let [n# (node-hasheq ~'ctx ~'off)] (set! ~'_h n#) n#))))
       clojure.lang.IObj
       (meta [_#] ~'mm)
       (withMeta [_# m] (new ~tname ~@remake))
       IAttachMeta
       (-attach-meta [_# m] (new ~tname ~@remake))
       (-hmeta [_#] ~'mm)
       IFlatHandle
       (-rep [this#] (let [r# (~repf ~'ctx ~'off)]
                       (if (some? ~'mm) (MetaVal. r# (to-rep ~'mm)) r#)))
       (-chunk-bytes [_#] (.-bs ~'ctx))
       IHandlePos
       (-hctx [_#] ~'ctx)
       (-hoff [_#] ~'off)
       IFlatDigest
       (-digest [this#] (let [d# ~'_d] (if (some? d#) (when-not (identical? d# ::none) d#) (let [v# (handle-digest ~'ctx ~'off)] (set! ~'_d (or v# ::none)) v#))))
       clojure.lang.Counted
       (count [_#] (~countf ~'ctx ~'off))
       clojure.lang.Seqable
       (seq [this#] (when (pos? (.count this#)) (~seqf ~'ctx ~'off)))
       clojure.lang.Indexed
       (nth [this# i#] (let [n# (.count this#)]
                         (if (and (<= 0 i#) (< i# n#)) (~nthf ~'ctx ~'off i#)
                             (throw (IndexOutOfBoundsException. (str i#))))))
       (nth [this# i# nf#] (let [n# (.count this#)]
                             (if (and (<= 0 i#) (< i# n#)) (~nthf ~'ctx ~'off i#) nf#)))
       clojure.lang.IReduce
       (reduce [this# f#] (reduce f# (seq this#)))
       clojure.lang.IReduceInit
       (reduce [this# f# init#] (reduce f# init# (seq this#)))
       java.lang.Iterable
       (iterator [this#] (clojure.lang.SeqIterator. (or (seq this#) ())))
       ~@(when vector?
           '[clojure.lang.IPersistentVector
             (length [this] (.count this))
             (assocN [this i v] (assocN-flat this i v))
             (cons [this v] (cons-flat this v))
             (pop [this] (pop-flat this))
             (peek [this] (when (pos? (.count this)) (nth this (dec (.count this)))))
             clojure.lang.Associative
             (assoc [this k v] (assocN-flat this (long k) v))
             (containsKey [this k] (and (integer? k) (<= 0 (long k)) (< (long k) (.count this))))
             (entryAt [this k] (when (.containsKey this k) (clojure.lang.MapEntry. k (nth this (long k)))))
             clojure.lang.ILookup
             (valAt [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (.count this)))
                               (nth this (long k)) nil))
             (valAt [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (.count this)))
                                  (nth this (long k)) nf))
             clojure.lang.IPersistentCollection
             (empty [_] [])
             (equiv [this other] (node-equiv ctx off other))])
       ~@(when (or vector? list?)
           '[java.util.List
             ;; a host List, as APersistentVector/APersistentList are:
             ;; jolt's native sequential equiv accepts a java.util.List
             ;; second operand where it accepts no foreign
             ;; IPersistentVector (measured 2026-11-16, w1b probes), so
             ;; the handles carry the interface on every host of this
             ;; family — (= plain flat) is the same question everywhere.
             (size [this] (.count this))
             (isEmpty [this] (zero? (.count this)))
             (^boolean contains [this o] (boolean (some (fn [e] (= e o)) (seq this))))
             (^objects toArray [this] (object-array (seq this)))
             (^objects toArray [this ^objects a] (let [n (.count this)]
                                 (if (< (alength a) n)
                                   (object-array (seq this))
                                   (do (dotimes [i n] (aset a i (nth this i)))
                                       (when (> (alength a) n) (aset a n nil))
                                       a))))
             (^boolean add [this o] (throw (UnsupportedOperationException.)))
             (^boolean addAll [this ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean addAll [this ^int i ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean remove [this o] (throw (UnsupportedOperationException.)))
             (^boolean removeAll [this ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean retainAll [this ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean containsAll [this ^java.util.Collection c] (every? (fn [o] (.contains this o)) c))
             (^void clear [this] (throw (UnsupportedOperationException.)))
             (get [this ^int i] (nth this i))
             (set [this ^int i o] (throw (UnsupportedOperationException.)))
             (^void add [this ^int i o] (throw (UnsupportedOperationException.)))
             (remove [this ^int i] (throw (UnsupportedOperationException.)))
             (indexOf [this o] (int (loop [i 0]
                                 (cond (== i (.count this)) -1
                                       (= (nth this i) o) i
                                       :else (recur (inc i))))))
             (lastIndexOf [this o] (int (loop [i (dec (.count this))]
                                     (cond (neg? i) -1
                                           (= (nth this i) o) i
                                           :else (recur (dec i))))))
             (^java.util.ListIterator listIterator [this] (.listIterator (java.util.Collections/unmodifiableList (java.util.ArrayList. ^java.util.Collection (seq this)))))
             (^java.util.ListIterator listIterator [this ^int i] (.listIterator (java.util.Collections/unmodifiableList (java.util.ArrayList. ^java.util.Collection (seq this))) i))
             (^java.util.List subList [this ^int s ^int e] (java.util.Collections/unmodifiableList
                                  (java.util.ArrayList. ^java.util.Collection (map (fn [i] (nth this i)) (range s e)))))])
       ~@(when list?
           '[clojure.lang.IPersistentList
             (peek [this] (when (pos? (.count this)) (nth this 0)))
             (pop [this] (pop-flat this))
             clojure.lang.IPersistentCollection
             (cons [this v] (cons-flat this v))
             (empty [_] ())
             (equiv [this other] (node-equiv ctx off other))]))))]
    :cljr [(defmacro defseqhandle
  "A sequential handle over the node at off. nthf/countf/seqf/repf are
   fns of (ctx off) (nthf also takes i); flags pick the interfaces."
  [tname fields nthf countf seqf repf & {:keys [vector? stack? list?]}]
  (let [remake (map (fn [f] (if (= f 'mm) 'm f)) fields)
        mfields (mapv (fn [f] (cond (#{'_h '_d} f)
                                    (with-meta f {:unsynchronized-mutable true})
                                    (= f 'ctx) (with-meta f {:tag 'Ctx})
                                    :else f)) fields)
        base-interfaces '[clojure.lang.IHashEq clojure.lang.IObj clojure.lang.Counted
                          clojure.lang.Seqable clojure.lang.IReduce clojure.lang.IReduceInit
                          clojure.lang.Indexed java.lang.Iterable
                          IFlatHandle IHandlePos IFlatDigest]
        interfaces (cond-> base-interfaces
                     vector? (into '[clojure.lang.IPersistentVector clojure.lang.Associative
                                     clojure.lang.ILookup clojure.lang.IPersistentStack
                                     java.util.List])
                     list? (into '[clojure.lang.IPersistentList clojure.lang.IPersistentStack
                                   java.util.List]))
        body `(~'Object
       (~'Equals [this# other#] (node-equiv ~'ctx ~'off other#))
       (~'GetHashCode [this#] (.hasheq this#))
       (~'ToString [this#] (pr-str (-rep this#)))
       clojure.lang.IHashEq
       (hasheq [this#] (let [v# ~'_h] (if (some? v#) (long v#) (let [n# (node-hasheq ~'ctx ~'off)] (set! ~'_h n#) n#))))
       clojure.lang.IObj
       (meta [_#] ~'mm)
       (withMeta [_# m] (new ~tname ~@remake))
       IAttachMeta
       (-attach-meta [_# m] (new ~tname ~@remake))
       (-hmeta [_#] ~'mm)
       IFlatHandle
       (-rep [this#] (let [r# (~repf ~'ctx ~'off)]
                       (if (some? ~'mm) (MetaVal. r# (to-rep ~'mm)) r#)))
       (-chunk-bytes [_#] (.-bs ~'ctx))
       IHandlePos
       (-hctx [_#] ~'ctx)
       (-hoff [_#] ~'off)
       IFlatDigest
       (-digest [this#] (let [d# ~'_d] (if (some? d#) (when-not (identical? d# ::none) d#) (let [v# (handle-digest ~'ctx ~'off)] (set! ~'_d (or v# ::none)) v#))))
       clojure.lang.Counted
       (count [_#] (~countf ~'ctx ~'off))
       clojure.lang.Seqable
       (seq [this#] (when (pos? (.count this#)) (~seqf ~'ctx ~'off)))
       clojure.lang.Indexed
       (nth [this# i#] (let [n# (.count this#)]
                         (if (and (<= 0 i#) (< i# n#)) (~nthf ~'ctx ~'off i#)
                             (throw (IndexOutOfBoundsException. (str i#))))))
       (nth [this# i# nf#] (let [n# (.count this#)]
                             (if (and (<= 0 i#) (< i# n#)) (~nthf ~'ctx ~'off i#) nf#)))
       clojure.lang.IReduce
       (reduce [this# f#] (reduce f# (seq this#)))
       clojure.lang.IReduceInit
       (reduce [this# f# init#] (reduce f# init# (seq this#)))
       java.lang.Iterable
       (iterator [this#] (clojure.lang.SeqIterator. (or (seq this#) ())))
       ~@(when vector?
           '[clojure.lang.IPersistentVector
             (length [this] (.count this))
             (assocN [this i v] (assocN-flat this i v))
             (cons [this v] (cons-flat this v))
             (pop [this] (pop-flat this))
             (peek [this] (when (pos? (.count this)) (nth this (dec (.count this)))))
             clojure.lang.Associative
             (assoc [this k v] (assocN-flat this (long k) v))
             (containsKey [this k] (and (integer? k) (<= 0 (long k)) (< (long k) (.count this))))
             (entryAt [this k] (when (.containsKey this k) (clojure.lang.MapEntry. k (nth this (long k)))))
             clojure.lang.ILookup
             (valAt [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (.count this)))
                               (nth this (long k)) nil))
             (valAt [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (.count this)))
                                  (nth this (long k)) nf))
             clojure.lang.IPersistentCollection
             (empty [_] [])
             (equiv [this other] (node-equiv ctx off other))])
       ~@(when (or vector? list?)
           '[java.util.List
             ;; a host List, as APersistentVector/APersistentList are:
             ;; jolt's native sequential equiv accepts a java.util.List
             ;; second operand where it accepts no foreign
             ;; IPersistentVector (measured 2026-11-16, w1b probes), so
             ;; the handles carry the interface on every host of this
             ;; family — (= plain flat) is the same question everywhere.
             (size [this] (.count this))
             (isEmpty [this] (zero? (.count this)))
             (^boolean contains [this o] (boolean (some (fn [e] (= e o)) (seq this))))
             (^objects toArray [this] (object-array (seq this)))
             (^objects toArray [this ^objects a] (let [n (.count this)]
                                 (if (< (alength a) n)
                                   (object-array (seq this))
                                   (do (dotimes [i n] (aset a i (nth this i)))
                                       (when (> (alength a) n) (aset a n nil))
                                       a))))
             (^boolean add [this o] (throw (UnsupportedOperationException.)))
             (^boolean addAll [this ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean addAll [this ^int i ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean remove [this o] (throw (UnsupportedOperationException.)))
             (^boolean removeAll [this ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean retainAll [this ^java.util.Collection c] (throw (UnsupportedOperationException.)))
             (^boolean containsAll [this ^java.util.Collection c] (every? (fn [o] (.contains this o)) c))
             (^void clear [this] (throw (UnsupportedOperationException.)))
             (get [this ^int i] (nth this i))
             (set [this ^int i o] (throw (UnsupportedOperationException.)))
             (^void add [this ^int i o] (throw (UnsupportedOperationException.)))
             (remove [this ^int i] (throw (UnsupportedOperationException.)))
             (indexOf [this o] (int (loop [i 0]
                                 (cond (== i (.count this)) -1
                                       (= (nth this i) o) i
                                       :else (recur (inc i))))))
             (lastIndexOf [this o] (int (loop [i (dec (.count this))]
                                     (cond (neg? i) -1
                                           (= (nth this i) o) i
                                           :else (recur (dec i))))))
             (^java.util.ListIterator listIterator [this] (.listIterator (java.util.Collections/unmodifiableList (java.util.ArrayList. ^java.util.Collection (seq this)))))
             (^java.util.ListIterator listIterator [this ^int i] (.listIterator (java.util.Collections/unmodifiableList (java.util.ArrayList. ^java.util.Collection (seq this))) i))
             (^java.util.List subList [this ^int s ^int e] (java.util.Collections/unmodifiableList
                                  (java.util.ArrayList. ^java.util.Collection (map (fn [i] (nth this i)) (range s e)))))])
       ~@(when list?
           '[clojure.lang.IPersistentList
             (peek [this] (when (pos? (.count this)) (nth this 0)))
             (pop [this] (pop-flat this))
             clojure.lang.IPersistentCollection
             (cons [this v] (cons-flat this v))
             (empty [_] ())
             (equiv [this other] (node-equiv ctx off other))]))
        forms (cljr-deftype-forms body)]
    `(deftype ~tname ~mfields ~@forms)))])

#?@(:bb [(defmacro defmaphandle [tname & _]
           `(defn ~(symbol (str "->" tname)) [& ~'args]
              (throw (ex-info "eve.flat handles are not available on this host yet" {}))))]
    :glj [(defmacro defmaphandle [tname & _]
            `(defn ~(symbol (str "->" tname)) [& ~'args]
               (throw (ex-info "eve.flat handles are not available on this host yet" {}))))]
    :lg [(defmacro defmaphandle [tname & _]
           `(defn ~(symbol (str "->" tname)) [& ~'args]
              (throw (ex-info "eve.flat handles are not available on this host yet" {}))))]
    :clj [(defmacro defmaphandle
  "A map handle as a deftype over the node — one construction for
   every host of this family, replacing the proxy over
   APersistentMap. The proxy's generated subclass is a real
   APersistentMap on the JVM, but on jolt proxy materializes as a
   reify that extends nothing, and jolt's native collection
   dispatch has no arm for the reify class (measured 2026-11-16,
   w1b probes: (count proxy) throws \"count not supported on this
   type\", (map? proxy) is false, (instance? Counted proxy) true
   yet uncountable). A deftype is a real class on both hosts, and
   the handle implements the full map surface — IPersistentMap
   plus java.util.Map, the interfaces APersistentMap itself
   carries — so host dispatch reaches it by the same interfaces
   it reaches a plain map with (on the JVM, APersistentMap.equals
   accepts any java.util.Map; a minimal deftype IPersistentMap
   without java.util.Map fails (.equiv plain flat), measured).
   The macro defines the deftype; its positional constructor is
   the ->Flat* factory. _h/_d are the hash/digest caches,
   unsynchronized-mutable fields seeded from the factory's args.
   entries/lookup/count are forms over the field names (lookup's
   form may use k and nf); repf/digestf are fns of the handle,
   assocf/dissocf fns of (handle k v)/(handle k). toString is
   hand-rolled from the entries, as the proxy's was: hosts that
   print a handle through its toString must terminate."
  [tname fields entries lookup countf repf digestf assocf dissocf & {:keys [record? row? generic?]}]
  (let [factory (symbol (str "->" tname))
        mfields (mapv (fn [f] (cond (#{'_h '_d} f)
                                    (with-meta f {:unsynchronized-mutable true})
                                    (= f 'ctx) (with-meta f {:tag 'Ctx})
                                    :else f)) fields)
        wm-args (map (fn [f] (case f mm 'm f)) fields)
        ifaces (cond-> '[eve.flat.chunk.IFlatHandle eve.flat.chunk.IHandlePos
                         eve.flat.chunk.IFlatDigest eve.flat.chunk.IMapSource
                         eve.flat.chunk.IAttachMeta
                         ;; Clojure's map-equivalence marker:
                         ;; APersistentMap.equiv rejects an
                         ;; IPersistentMap that does not carry it
                         ;; (clojure-1.12.0 APersistentMap.java:140),
                         ;; so (= plain flat) is false without it.
                         clojure.lang.MapEquivalence]
                 record? (conj 'eve.flat.chunk.IRecordInfo)
                 row? (conj 'eve.flat.chunk.IRowHandle)
                 generic? (conj 'eve.flat.chunk.IGenericRecord))]
    `(deftype ~tname ~mfields
       ~@ifaces
       Object
       (equals [this# other#] (map-equiv-of this# other#))
       (hashCode [this#] (.hasheq this#))
       (toString [this#] (str "{" (apply str (interpose ", " (map (fn [e#] (str (pr-str (nth e# 0)) " " (pr-str (nth e# 1)))) (-entries this#)))) "}"))
       clojure.lang.IHashEq
       (hasheq [this#] (let [v# ~'_h]
                         (if (some? v#) (long v#)
                             (let [n# (entries-hasheq this#)] (set! ~'_h n#) n#))))
       clojure.lang.IObj
       (meta [_#] ~'mm)
       (withMeta [_# ~'m] (~factory ~@wm-args))
       eve.flat.chunk.IAttachMeta
       (-attach-meta [_# ~'m] (~factory ~@wm-args))
       (-hmeta [_#] ~'mm)
       eve.flat.chunk.IFlatHandle
       (-rep [this#] (let [r# (~repf this#)]
                       (if (some? ~'mm) (MetaVal. r# (to-rep ~'mm)) r#)))
       (-chunk-bytes [_#] (.-bs ~'ctx))
       eve.flat.chunk.IHandlePos
       (-hctx [_#] ~'ctx)
       (-hoff [_#] ~'off)
       eve.flat.chunk.IFlatDigest
       (-digest [this#] (let [d# ~'_d]
                          (if (some? d#) (when-not (identical? d# ::none) d#)
                              (let [v# (~digestf this#)] (set! ~'_d (or v# ::none)) v#))))
       eve.flat.chunk.IMapSource
       (-entries [_#] ~entries)
       (-lookup [_# ~'k ~'nf] ~lookup)
       (-mcount [_#] ~countf)
       (-assoc-entry [this# k# v#] (~assocf this# k# v#))
       (-dissoc-entry [this# k#] (~dissocf this# k#))
       ~@(when record?
           '[eve.flat.chunk.IRecordInfo
             (-tkey [_] tkey)
             (-fp [_] fp)])
       ~@(when row?
           '[eve.flat.chunk.IRowHandle
             (-row-index [_] row)])
       clojure.lang.IPersistentMap
       (assoc [this# k# v#] (-assoc-entry this# k# v#))
       (assocEx [this# k# v#] (if (.containsKey this# k#)
                                (throw (Exception. "Key already present"))
                                (-assoc-entry this# k# v#)))
       (without [this# k#] (-dissoc-entry this# k#))
       (iterator [this#] (clojure.lang.SeqIterator. (or (seq this#) ())))
       clojure.lang.Associative
       (containsKey [this# k#] (not (identical? ::absent (-lookup this# k# ::absent))))
       (entryAt [this# k#] (let [v# (-lookup this# k# ::absent)]
                             (when-not (identical? ::absent v#) (clojure.lang.MapEntry. k# v#))))
       clojure.lang.IPersistentCollection
       (count [this#] (int (-mcount this#)))
       (cons [this# o#] (cond (instance? clojure.lang.MapEntry o#)
                              (-assoc-entry this# (.key ^clojure.lang.MapEntry o#) (.val ^clojure.lang.MapEntry o#))
                              (vector? o#)
                              (if (== 2 (count o#)) (-assoc-entry this# (nth o# 0) (nth o# 1))
                                  (throw (IllegalArgumentException. "Vector arg to map conj must be a pair")))
                              (instance? java.util.Map o#)
                              (reduce (fn [mm# e#] (-assoc-entry mm# (.getKey ^java.util.Map$Entry e#) (.getValue ^java.util.Map$Entry e#)))
                                      this# (.entrySet ^java.util.Map o#))
                              :else
                              (reduce (fn [mm# e#] (-assoc-entry mm# (key e#) (val e#))) this# o#)))
       (empty [_#] {})
       (equiv [this# other#] (map-equiv-of this# other#))
       clojure.lang.ILookup
       (valAt [this# k#] (-lookup this# k# nil))
       (valAt [this# k# nf#] (-lookup this# k# nf#))
       clojure.lang.Seqable
       (seq [this#] (when (pos? (.count this#))
                      (seq (map (fn [e#] (clojure.lang.MapEntry. (nth e# 0) (nth e# 1))) (-entries this#)))))
       clojure.lang.IFn
       (invoke [this# k#] (-lookup this# k# nil))
       (invoke [this# k# nf#] (-lookup this# k# nf#))
       clojure.lang.IReduce
       (reduce [this# f#] (reduce f# (seq this#)))
       clojure.lang.IReduceInit
       (reduce [this# f# init#] (reduce f# init# (seq this#)))
       java.util.Map
       (size [this#] (int (-mcount this#)))
       (isEmpty [this#] (zero? (-mcount this#)))
       (containsValue [this# v#] (boolean (some (fn [e#] (= (nth e# 1) v#)) (-entries this#))))
       (get [this# k#] (-lookup this# k# nil))
       (put [_# _k# _v#] (throw (UnsupportedOperationException.)))
       (remove [_# _k#] (throw (UnsupportedOperationException.)))
       (putAll [_# _m#] (throw (UnsupportedOperationException.)))
       (^void clear [_#] (throw (UnsupportedOperationException.)))
       (keySet [this#] (into #{} (map (fn [e#] (nth e# 0))) (-entries this#)))
       (values [this#] (into [] (map (fn [e#] (nth e# 1))) (-entries this#)))
       (entrySet [this#] (into #{} (map (fn [e#] (clojure.lang.MapEntry. (nth e# 0) (nth e# 1))) (-entries this#)))))))]
    :cljr [(defmacro defmaphandle
  "A map handle as a deftype over the node — one construction for
   every host of this family, replacing the proxy over
   APersistentMap. The proxy's generated subclass is a real
   APersistentMap on the JVM, but on jolt proxy materializes as a
   reify that extends nothing, and jolt's native collection
   dispatch has no arm for the reify class (measured 2026-11-16,
   w1b probes: (count proxy) throws \"count not supported on this
   type\", (map? proxy) is false, (instance? Counted proxy) true
   yet uncountable). A deftype is a real class on both hosts, and
   the handle implements the full map surface — IPersistentMap
   plus java.util.Map, the interfaces APersistentMap itself
   carries — so host dispatch reaches it by the same interfaces
   it reaches a plain map with (on the JVM, APersistentMap.equals
   accepts any java.util.Map; a minimal deftype IPersistentMap
   without java.util.Map fails (.equiv plain flat), measured).
   The macro defines the deftype; its positional constructor is
   the ->Flat* factory. _h/_d are the hash/digest caches,
   unsynchronized-mutable fields seeded from the factory's args.
   entries/lookup/count are forms over the field names (lookup's
   form may use k and nf); repf/digestf are fns of the handle,
   assocf/dissocf fns of (handle k v)/(handle k). toString is
   hand-rolled from the entries, as the proxy's was: hosts that
   print a handle through its toString must terminate."
  [tname fields entries lookup countf repf digestf assocf dissocf & {:keys [record? row? generic?]}]
  (let [factory (symbol (str "->" tname))
        mfields (mapv (fn [f] (cond (#{'_h '_d} f)
                                    (with-meta f {:unsynchronized-mutable true})
                                    (= f 'ctx) (with-meta f {:tag 'Ctx})
                                    :else f)) fields)
        wm-args (map (fn [f] (case f mm 'm f)) fields)
        ifaces (cond-> '[eve.flat.chunk.IFlatHandle eve.flat.chunk.IHandlePos
                         eve.flat.chunk.IFlatDigest eve.flat.chunk.IMapSource
                         eve.flat.chunk.IAttachMeta
                         ;; Clojure's map-equivalence marker:
                         ;; APersistentMap.equiv rejects an
                         ;; IPersistentMap that does not carry it
                         ;; (clojure-1.12.0 APersistentMap.java:140),
                         ;; so (= plain flat) is false without it.
                         clojure.lang.MapEquivalence]
                 record? (conj 'eve.flat.chunk.IRecordInfo)
                 row? (conj 'eve.flat.chunk.IRowHandle)
                 generic? (conj 'eve.flat.chunk.IGenericRecord))
        body `(~'Object
       (~'Equals [this# other#] (map-equiv-of this# other#))
       (~'GetHashCode [this#] (.hasheq this#))
       (~'ToString [this#] (str "{" (apply str (interpose ", " (map (fn [e#] (str (pr-str (nth e# 0)) " " (pr-str (nth e# 1)))) (-entries this#)))) "}"))
       clojure.lang.IHashEq
       (hasheq [this#] (let [v# ~'_h]
                         (if (some? v#) (long v#)
                             (let [n# (entries-hasheq this#)] (set! ~'_h n#) n#))))
       clojure.lang.IObj
       (meta [_#] ~'mm)
       (withMeta [_# ~'m] (~factory ~@wm-args))
       eve.flat.chunk.IAttachMeta
       (-attach-meta [_# ~'m] (~factory ~@wm-args))
       (-hmeta [_#] ~'mm)
       eve.flat.chunk.IFlatHandle
       (-rep [this#] (let [r# (~repf this#)]
                       (if (some? ~'mm) (MetaVal. r# (to-rep ~'mm)) r#)))
       (-chunk-bytes [_#] (.-bs ~'ctx))
       eve.flat.chunk.IHandlePos
       (-hctx [_#] ~'ctx)
       (-hoff [_#] ~'off)
       eve.flat.chunk.IFlatDigest
       (-digest [this#] (let [d# ~'_d]
                          (if (some? d#) (when-not (identical? d# ::none) d#)
                              (let [v# (~digestf this#)] (set! ~'_d (or v# ::none)) v#))))
       eve.flat.chunk.IMapSource
       (-entries [_#] ~entries)
       (-lookup [_# ~'k ~'nf] ~lookup)
       (-mcount [_#] ~countf)
       (-assoc-entry [this# k# v#] (~assocf this# k# v#))
       (-dissoc-entry [this# k#] (~dissocf this# k#))
       ~@(when record?
           '[eve.flat.chunk.IRecordInfo
             (-tkey [_] tkey)
             (-fp [_] fp)])
       ~@(when row?
           '[eve.flat.chunk.IRowHandle
             (-row-index [_] row)])
       clojure.lang.IPersistentMap
       (assoc [this# k# v#] (-assoc-entry this# k# v#))
       (assocEx [this# k# v#] (if (.containsKey this# k#)
                                (throw (Exception. "Key already present"))
                                (-assoc-entry this# k# v#)))
       (without [this# k#] (-dissoc-entry this# k#))
       (iterator [this#] (clojure.lang.SeqIterator. (or (seq this#) ())))
       clojure.lang.Associative
       (containsKey [this# k#] (not (identical? ::absent (-lookup this# k# ::absent))))
       (entryAt [this# k#] (let [v# (-lookup this# k# ::absent)]
                             (when-not (identical? ::absent v#) (clojure.lang.MapEntry. k# v#))))
       clojure.lang.IPersistentCollection
       (count [this#] (int (-mcount this#)))
       (cons [this# o#] (cond (instance? clojure.lang.MapEntry o#)
                              (-assoc-entry this# (.key ^clojure.lang.MapEntry o#) (.val ^clojure.lang.MapEntry o#))
                              (vector? o#)
                              (if (== 2 (count o#)) (-assoc-entry this# (nth o# 0) (nth o# 1))
                                  (throw (IllegalArgumentException. "Vector arg to map conj must be a pair")))
                              (instance? System.Collections.IDictionary o#)
                              (reduce (fn [mm# k#] (-assoc-entry mm# k# (.get_Item ^System.Collections.IDictionary o# k#)))
                                      this# (seq (.get_Keys ^System.Collections.IDictionary o#)))
                              :else
                              (reduce (fn [mm# e#] (-assoc-entry mm# (key e#) (val e#))) this# o#)))
       (empty [_#] {})
       (equiv [this# other#] (map-equiv-of this# other#))
       clojure.lang.ILookup
       (valAt [this# k#] (-lookup this# k# nil))
       (valAt [this# k# nf#] (-lookup this# k# nf#))
       clojure.lang.Seqable
       (seq [this#] (when (pos? (.count this#))
                      (seq (map (fn [e#] (clojure.lang.MapEntry. (nth e# 0) (nth e# 1))) (-entries this#)))))
       clojure.lang.IFn
       (invoke [this# k#] (-lookup this# k# nil))
       (invoke [this# k# nf#] (-lookup this# k# nf#))
       clojure.lang.IReduce
       (reduce [this# f#] (reduce f# (seq this#)))
       clojure.lang.IReduceInit
       (reduce [this# f# init#] (reduce f# init# (seq this#)))
       java.util.Map
       (size [this#] (int (-mcount this#)))
       (isEmpty [this#] (zero? (-mcount this#)))
       (containsValue [this# v#] (boolean (some (fn [e#] (= (nth e# 1) v#)) (-entries this#))))
       (get [this# k#] (-lookup this# k# nil))
       (put [_# _k# _v#] (throw (UnsupportedOperationException.)))
       (remove [_# _k#] (throw (UnsupportedOperationException.)))
       (putAll [_# _m#] (throw (UnsupportedOperationException.)))
       (^void clear [_#] (throw (UnsupportedOperationException.)))
       (keySet [this#] (into #{} (map (fn [e#] (nth e# 0))) (-entries this#)))
       (values [this#] (into [] (map (fn [e#] (nth e# 1))) (-entries this#)))
       (entrySet [this#] (into #{} (map (fn [e#] (clojure.lang.MapEntry. (nth e# 0) (nth e# 1))) (-entries this#)))))
        forms (cljr-deftype-forms body)]
    `(deftype ~tname ~mfields ~@forms)))])

;; --- the sequential handles ----------------------------------------------

#?@(:jank []
    :bb []
    :glj []
    :lg []
    :lpy []
    :default [(defseqhandle FlatVec [ctx off mm _h _d] elem-at elem-count seq-of-node vec-rep-of :vector? true)
              (defseqhandle FlatColVec [ctx off mm _h _d] elem-at elem-count seq-of-node vec-rep-of :vector? true)
              (defseqhandle FlatList [ctx off mm _h _d] elem-at elem-count seq-of-node list-rep-of :list? true)
              (defseqhandle FlatBatch [ctx off mm _h _d] row-handle elem-count batch-seq-of batch-rep-of :vector? true)])

;; FlatList implements IPersistentList but not ISeq, so the printer
;; has no method for it and falls back to Object/toString — every
;; other handle prints through its collection interface. Print the
;; rep, like the value itself prints. bb and glojure print their
;; handles through their own sections below (Object/toString on bb,
;; print-method through eve.core/-pr-writer on glojure), so this
;; defmethod is spliced away there explicitly.
#?@(:bb []
    :glj []
    :clj [(defmethod print-method FlatList [v ^java.io.Writer w]
            (.write w (pr-str (-rep v))))])

#?(:clj
   ;; MetaVal likewise: a rep element carrying metadata must print as
   ;; its value, not #object. (MetaVal is defined on every host.)
   (defmethod print-method MetaVal [v ^java.io.Writer w]
     (.write w (pr-str (.-inner ^MetaVal v)))))

;; --- the map handles --------------------------------------------------------

(defn- node-digest-of [h] (handle-digest (handle-ctx h) (handle-off h)))

(defn- record-rep-of [h]
  (record-entries-rep (-tkey h) (-fp h) (-entries h)))

#?@(:jank []
    :bb []
    :glj []
    :lg []
    :lpy []
    :default [(defmaphandle FlatMap [ctx off mm _h _d]
                (entries-of-node ctx off)
                (lookup-node ctx off k nf)
                (count (entries-of-node ctx off))
                map-rep-of node-digest-of assoc-flat dissoc-flat)])

#?@(:jank []
    :bb []
    :glj []
    :lg []
    :lpy []
    :default [(defmaphandle FlatRecord [ctx off mm _h _d tkey fp]
                (entries-of-node ctx off)
                (lookup-node ctx off k nf)
                (count (entries-of-node ctx off))
                record-rep-of node-digest-of assoc-flat dissoc-flat
                :record? true)])

#?@(:jank []
    :bb []
    :glj []
    :lg []
    :lpy []
    :default [(defmaphandle FlatTagged [ctx off mm _h _d tkey fp]
                (entries-of-node ctx off)
                (lookup-node ctx off k nf)
                (count (entries-of-node ctx off))
                record-rep-of node-digest-of assoc-flat dissoc-flat
                :record? true :generic? true)])

#?@(:jank []
    :bb []
    :glj []
    :lg []
    :lpy []
    :default [(defmaphandle FlatRow [ctx off row mm _h _d]
                (row-entries ctx off row)
                (row-lookup ctx off row k nf)
                (count (row-entries ctx off row))
                map-rep-of row-digest-of assoc-flat dissoc-flat
                :row? true)])

;; --- the set handle ---------------------------------------------------------
;; a deftype over the node, for the same reason as the maps
;; (defmaphandle): the interfaces (IPersistentSet, java.util.Set)
;; carry the surface on every host of this family.

#?@(:jank []
    :bb []
    :glj []
    :clj [(deftype FlatSet [^Ctx ctx off mm ^:unsynchronized-mutable _h ^:unsynchronized-mutable _d]
  ;; a deftype, not a proxy over APersistentSet, for the same
  ;; reason as the map handles (defmaphandle): the interfaces
  ;; carry the surface on every host of this family.
  eve.flat.chunk.IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  eve.flat.chunk.IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  eve.flat.chunk.IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  eve.flat.chunk.IAttachMeta
  (-attach-meta [_ m] (->FlatSet ctx off m _h _d))
  (-hmeta [_] mm)
  Object
  (equals [_ other] (node-equiv ctx off other))
  (hashCode [this] (.hasheq this))
  (toString [_] (str "#{" (apply str (interpose " " (map pr-str (set-elements ctx off)))) "}"))
  clojure.lang.IHashEq
  (hasheq [_] (let [v _h]
                (if (some? v) (long v)
                    (let [n (node-hasheq ctx off)] (set! _h n) n))))
  clojure.lang.IObj
  (meta [_] mm)
  (withMeta [_ m] (->FlatSet ctx off m _h _d))
  clojure.lang.IPersistentSet
  (disjoin [this v] (disjoin-flat this v))
  (contains [_ v] (boolean (some (fn [e] (= e v)) (set-elements ctx off))))
  (get [_ v] (some (fn [e] (when (= e v) e)) (set-elements ctx off)))
  clojure.lang.IPersistentCollection
  (count [_] (int (first (set-parts ctx off))))
  (cons [this v] (cons-flat this v))
  (empty [_] #{})
  (equiv [_ other] (node-equiv ctx off other))
  clojure.lang.Seqable
  (seq [this] (when (pos? (.count this)) (seq (set-elements ctx off))))
  clojure.lang.IFn
  (invoke [this v] (.get this v))
  clojure.lang.IReduce
  (reduce [this f] (reduce f (seq this)))
  clojure.lang.IReduceInit
  (reduce [this f init] (reduce f init (seq this)))
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (or (seq this) ())))
  java.util.Set
  (size [this] (.count this))
  (isEmpty [this] (zero? (.count this)))
  (^objects toArray [this] (object-array (seq this)))
  (^objects toArray [this ^objects a] (let [n (.count this)]
                      (if (< (alength a) n)
                        (object-array (seq this))
                        (do (dotimes [i n] (aset a i (nth (seq this) i)))
                            (when (> (alength a) n) (aset a n nil))
                            a))))
  (add [_ _v] (throw (UnsupportedOperationException.)))
  (remove [_ _v] (throw (UnsupportedOperationException.)))
  (containsAll [this c] (every? (fn [v] (.contains this v)) c))
  (addAll [_ _c] (throw (UnsupportedOperationException.)))
  (removeAll [_ _c] (throw (UnsupportedOperationException.)))
  (retainAll [_ _c] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.))))]
    :cljr [(deftype-cljr FlatSet [^Ctx ctx off mm ^:unsynchronized-mutable _h ^:unsynchronized-mutable _d]
  ;; a deftype, not a proxy over APersistentSet, for the same
  ;; reason as the map handles (defmaphandle): the interfaces
  ;; carry the surface on every host of this family.
  eve.flat.chunk.IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  eve.flat.chunk.IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  eve.flat.chunk.IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  eve.flat.chunk.IAttachMeta
  (-attach-meta [_ m] (->FlatSet ctx off m _h _d))
  (-hmeta [_] mm)
  Object
  (Equals [_ other] (node-equiv ctx off other))
  (GetHashCode [this] (.hasheq this))
  (ToString [_] (str "#{" (apply str (interpose " " (map pr-str (set-elements ctx off)))) "}"))
  clojure.lang.IHashEq
  (hasheq [_] (let [v _h]
                (if (some? v) (long v)
                    (let [n (node-hasheq ctx off)] (set! _h n) n))))
  clojure.lang.IObj
  (meta [_] mm)
  (withMeta [_ m] (->FlatSet ctx off m _h _d))
  clojure.lang.IPersistentSet
  (disjoin [this v] (disjoin-flat this v))
  (contains [_ v] (boolean (some (fn [e] (= e v)) (set-elements ctx off))))
  (get [_ v] (some (fn [e] (when (= e v) e)) (set-elements ctx off)))
  clojure.lang.IPersistentCollection
  (count [_] (int (first (set-parts ctx off))))
  (cons [this v] (cons-flat this v))
  (empty [_] #{})
  (equiv [_ other] (node-equiv ctx off other))
  clojure.lang.Seqable
  (seq [this] (when (pos? (.count this)) (seq (set-elements ctx off))))
  clojure.lang.IFn
  (invoke [this v] (.get this v))
  clojure.lang.IReduce
  (reduce [this f] (reduce f (seq this)))
  clojure.lang.IReduceInit
  (reduce [this f init] (reduce f init (seq this)))
  java.lang.Iterable
  (iterator [this] (clojure.lang.SeqIterator. (or (seq this) ())))
  java.util.Set
  (size [this] (.count this))
  (isEmpty [this] (zero? (.count this)))
  (^objects toArray [this] (object-array (seq this)))
  (^objects toArray [this ^objects a] (let [n (.count this)]
                      (if (< (alength a) n)
                        (object-array (seq this))
                        (do (dotimes [i n] (aset a i (nth (seq this) i)))
                            (when (> (alength a) n) (aset a n nil))
                            a))))
  (add [_ _v] (throw (UnsupportedOperationException.)))
  (remove [_ _v] (throw (UnsupportedOperationException.)))
  (containsAll [this c] (every? (fn [v] (.contains this v)) c))
  (addAll [_ _c] (throw (UnsupportedOperationException.)))
  (removeAll [_ _c] (throw (UnsupportedOperationException.)))
  (retainAll [_ _c] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.))))])
))

;; ---------------------------------------------------------------------------
;; The protocol-host handles (bb, glojure): the same node walks over
;; the same layout, answered through the seat's eve.core protocols —
;; the surface eve's other types use on these hosts — instead of the
;; JVM collection interfaces the macros and proxies are built on.
;; Method for method they mirror the ClojureScript deftypes below
;; (which mirror the JVM macros): same rep builders, same walks
;; (node-equiv, node-hasheq, entries-equiv, entries-hasheq-of,
;; handle-digest), same update path (the shared cons-flat /
;; assocN-flat / pop-flat / assoc-flat / dissoc-flat / disjoin-flat,
;; which re-read through the ->Flat* factories). The hash/digest
;; cache fields ride along uncached here: -hash and -digest compute
;; on each call. Only speed differs.
;;
;; On bb the sequential and set handles also carry Object
;; equals/hashCode/toString (the seat's own bb types synthesize the
;; same from IEquiv/IHash), and the map handles carry sci's map
;; interface set — the proxy's method surface — so host collection
;; fns (get, seq, =, hash) see them as maps; their equals/hashCode
;; come from the map machinery over equiv/hasheq, as the seat's own
;; map types arrange. On glojure the map handles are ^:eve/map
;; records and every handle prints through eve.core/-pr-writer; the
;; completion hooks that route glojure's =/hash/collection fns onto
;; these types live in the glojure types adapter, beside the hooks
;; for the seat's other hand-written types.

(declare unpack)

(defn- pg-reduce
  "reduce over a node seq by first/next walking: the host's own
   reduce may not dispatch over the seq types the node walks
   answer (glojure's coll-reduce has no ChunkedCons dispatch,
   measured in the flat smoke's handle row)."
  ([f s] (if-let [s (seq s)] (pg-reduce f (first s) (next s)) (f)))
  ([f init s] (loop [s (seq s) acc init]
                (if (nil? s) acc (recur (next s) (f acc (first s)))))))




(defn- pg-entries-seq
  "A seq of map entries over [[k v] ...]: the host's entry values,
   as node-seq-entries builds on ClojureScript."
  [entries]
  (seq (map (fn [[k v]] (first (seq (array-map k v)))) entries)))

(defn- pg-map-conj
  "conj onto a map handle, the proxy's cons shape: an entry or
   two-element vector, a map, or a seq of entries."
  [h o]
  (cond (and (vector? o) (== 2 (count o))) (assoc-flat h (nth o 0) (nth o 1))
        (map? o) (reduce (fn [m [k v]] (assoc-flat m k v)) h o)
        :else (reduce (fn [m e] (assoc-flat m (nth e 0) (nth e 1))) h o)))

#?(:bb (do

(deftype FlatVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatVec ctx off m _h _d))
  Object
  (equals [_ other] (node-equiv ctx off other))
  (hashCode [_] (unchecked-int (node-hasheq ctx off)))
  (toString [_] (pr-str (-rep _))))

(deftype FlatColVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatColVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatColVec ctx off m _h _d))
  Object
  (equals [_ other] (node-equiv ctx off other))
  (hashCode [_] (unchecked-int (node-hasheq ctx off)))
  (toString [_] (pr-str (-rep _))))

(deftype FlatList [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (list-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatList ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] ())
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off)) (elem-at ctx off 0)))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatList ctx off m _h _d))
  Object
  (equals [_ other] (node-equiv ctx off other))
  (hashCode [_] (unchecked-int (node-hasheq ctx off)))
  (toString [_] (pr-str (-rep _))))

(deftype FlatBatch [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (batch-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatBatch ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (row-handle ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (row-handle ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (row-handle ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (row-handle ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (batch-seq-of ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (row-handle ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (batch-seq-of ctx off)))
  (-reduce [_ f init] (pg-reduce f init (batch-seq-of ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatBatch ctx off m _h _d))
  Object
  (equals [_ other] (node-equiv ctx off other))
  (hashCode [_] (unchecked-int (node-hasheq ctx off)))
  (toString [_] (pr-str (-rep _))))

(deftype FlatMap [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatMap ctx off m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatMap ctx off m _h _d))
  clojure.lang.ILookup
  (valAt [_ k] (lookup-node ctx off k nil))
  (valAt [_ k nf] (lookup-node ctx off k nf))
  clojure.lang.Associative
  (assoc [_ k v] (assoc-flat _ k v))
  (containsKey [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  (entryAt [_ k] (let [v (lookup-node ctx off k ::absent)]
                   (when-not (identical? ::absent v)
                     (clojure.lang.MapEntry. k v))))
  clojure.lang.IPersistentCollection
  (cons [_ o] (pg-map-conj _ o))
  (count [_] (count (entries-of-node ctx off)))
  (empty [_] {})
  (equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  clojure.lang.IPersistentMap
  (assocEx [_ k v] (if (identical? ::absent (lookup-node ctx off k ::absent))
                     (assoc-flat _ k v)
                     (throw (Exception. "Key already present"))))
  (without [_ k] (dissoc-flat _ k))
  clojure.lang.Seqable
  (seq [_] (pg-entries-seq (entries-of-node ctx off)))
  clojure.lang.IHashEq
  (hasheq [_] (entries-hasheq-of (entries-of-node ctx off)))
  clojure.lang.IObj
  (meta [_] mm)
  (withMeta [_ m] (->FlatMap ctx off m _h _d))
  java.lang.Iterable
  (iterator [_] (let [state (volatile! (pg-entries-seq (entries-of-node ctx off)))]
                  (reify java.util.Iterator
                    (hasNext [_] (boolean @state))
                    (next [_] (if-let [s @state]
                                (let [v (first s)] (vswap! state next) v)
                                (throw (java.util.NoSuchElementException.)))))))
  Object
  (toString [_] (pr-str (-rep _))))

(deftype FlatRecord [ctx off mm _h _d tkey fp]
  ;; a generic record carries its type's identity: the generated
  ;; <Type>? predicates answer by type key for both of a type's
  ;; forms (eve.deftype-proto.data/type-key-of)
  d/ISabpType
  (-sabp-type-key [_] tkey)
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  clojure.lang.ILookup
  (valAt [_ k] (lookup-node ctx off k nil))
  (valAt [_ k nf] (lookup-node ctx off k nf))
  clojure.lang.Associative
  (assoc [_ k v] (assoc-flat _ k v))
  (containsKey [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  (entryAt [_ k] (let [v (lookup-node ctx off k ::absent)]
                   (when-not (identical? ::absent v)
                     (clojure.lang.MapEntry. k v))))
  clojure.lang.IPersistentCollection
  (cons [_ o] (pg-map-conj _ o))
  (count [_] (count (entries-of-node ctx off)))
  (empty [_] {})
  (equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  clojure.lang.IPersistentMap
  (assocEx [_ k v] (if (identical? ::absent (lookup-node ctx off k ::absent))
                     (assoc-flat _ k v)
                     (throw (Exception. "Key already present"))))
  (without [_ k] (dissoc-flat _ k))
  clojure.lang.Seqable
  (seq [_] (pg-entries-seq (entries-of-node ctx off)))
  clojure.lang.IHashEq
  (hasheq [_] (entries-hasheq-of (entries-of-node ctx off)))
  clojure.lang.IObj
  (meta [_] mm)
  (withMeta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  java.lang.Iterable
  (iterator [_] (let [state (volatile! (pg-entries-seq (entries-of-node ctx off)))]
                  (reify java.util.Iterator
                    (hasNext [_] (boolean @state))
                    (next [_] (if-let [s @state]
                                (let [v (first s)] (vswap! state next) v)
                                (throw (java.util.NoSuchElementException.)))))))
  Object
  (toString [_] (pr-str (-rep _))))

(deftype FlatTagged [ctx off mm _h _d tkey fp]
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  IGenericRecord
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  clojure.lang.ILookup
  (valAt [_ k] (lookup-node ctx off k nil))
  (valAt [_ k nf] (lookup-node ctx off k nf))
  clojure.lang.Associative
  (assoc [_ k v] (assoc-flat _ k v))
  (containsKey [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  (entryAt [_ k] (let [v (lookup-node ctx off k ::absent)]
                   (when-not (identical? ::absent v)
                     (clojure.lang.MapEntry. k v))))
  clojure.lang.IPersistentCollection
  (cons [_ o] (pg-map-conj _ o))
  (count [_] (count (entries-of-node ctx off)))
  (empty [_] {})
  (equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  clojure.lang.IPersistentMap
  (assocEx [_ k v] (if (identical? ::absent (lookup-node ctx off k ::absent))
                     (assoc-flat _ k v)
                     (throw (Exception. "Key already present"))))
  (without [_ k] (dissoc-flat _ k))
  clojure.lang.Seqable
  (seq [_] (pg-entries-seq (entries-of-node ctx off)))
  clojure.lang.IHashEq
  (hasheq [_] (entries-hasheq-of (entries-of-node ctx off)))
  clojure.lang.IObj
  (meta [_] mm)
  (withMeta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  java.lang.Iterable
  (iterator [_] (let [state (volatile! (pg-entries-seq (entries-of-node ctx off)))]
                  (reify java.util.Iterator
                    (hasNext [_] (boolean @state))
                    (next [_] (if-let [s @state]
                                (let [v (first s)] (vswap! state next) v)
                                (throw (java.util.NoSuchElementException.)))))))
  Object
  (toString [_] (pr-str (-rep _))))

(deftype FlatRow [ctx off row mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (row-entries ctx off row))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (row-digest-of _))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRow ctx off row m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (row-entries ctx off row))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  (-mcount [_] (count (row-entries ctx off row)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRowHandle
  (-row-index [_] row)
  ecore/ICounted
  (-count [_] (count (row-entries ctx off row)))
  ecore/ILookup
  (-lookup [_ k] (row-lookup ctx off row k nil))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (row-lookup ctx off row k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (row-entries ctx off row)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (row-lookup ctx off row k nil))
  (-invoke [_ k nf] (row-lookup ctx off row k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (row-entries ctx off row))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (row-entries ctx off row))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (row-entries ctx off row) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (row-entries ctx off row)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRow ctx off row m _h _d))
  clojure.lang.ILookup
  (valAt [_ k] (row-lookup ctx off row k nil))
  (valAt [_ k nf] (row-lookup ctx off row k nf))
  clojure.lang.Associative
  (assoc [_ k v] (assoc-flat _ k v))
  (containsKey [_ k] (not (identical? ::absent (row-lookup ctx off row k ::absent))))
  (entryAt [_ k] (let [v (row-lookup ctx off row k ::absent)]
                   (when-not (identical? ::absent v)
                     (clojure.lang.MapEntry. k v))))
  clojure.lang.IPersistentCollection
  (cons [_ o] (pg-map-conj _ o))
  (count [_] (count (row-entries ctx off row)))
  (empty [_] {})
  (equiv [_ other] (entries-equiv (row-entries ctx off row) other))
  clojure.lang.IPersistentMap
  (assocEx [_ k v] (if (identical? ::absent (row-lookup ctx off row k ::absent))
                     (assoc-flat _ k v)
                     (throw (Exception. "Key already present"))))
  (without [_ k] (dissoc-flat _ k))
  clojure.lang.Seqable
  (seq [_] (pg-entries-seq (row-entries ctx off row)))
  clojure.lang.IHashEq
  (hasheq [_] (entries-hasheq-of (row-entries ctx off row)))
  clojure.lang.IObj
  (meta [_] mm)
  (withMeta [_ m] (->FlatRow ctx off row m _h _d))
  java.lang.Iterable
  (iterator [_] (let [state (volatile! (pg-entries-seq (row-entries ctx off row)))]
                  (reify java.util.Iterator
                    (hasNext [_] (boolean @state))
                    (next [_] (if-let [s @state]
                                (let [v (first s)] (vswap! state next) v)
                                (throw (java.util.NoSuchElementException.)))))))
  Object
  (toString [_] (pr-str (-rep _))))

(deftype FlatSet [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatSet ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (long (first (set-parts ctx off))))
  ecore/ILookup
  (-lookup [_ k] (some (fn [e] (when (= e k) e)) (set-elements ctx off)))
  (-lookup [_ k nf] (let [es (set-elements ctx off)]
                      (loop [i 0] (cond (== i (count es)) nf
                                         (= (nth es i) k) (nth es i)
                                         :else (recur (inc i))))))
  ecore/ISeqable
  (-seq [_] (when (pos? (long (first (set-parts ctx off))))
              (seq (set-elements ctx off))))
  ecore/ISet
  (-disjoin [_ v] (disjoin-flat _ v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] #{})
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq (set-elements ctx off))))
  (-reduce [_ f init] (pg-reduce f init (seq (set-elements ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatSet ctx off m _h _d))
  Object
  (equals [_ other] (node-equiv ctx off other))
  (hashCode [_] (unchecked-int (node-hasheq ctx off)))
  (toString [_] (pr-str (-rep _))))

;; print: SCI's pr-str wraps a deftype object as #object unless a
;; print-method answers for its type (the map kinds print through
;; SCI's map machinery, but their nested values and every seq/set
;; kind do not). Each kind registers its rep print — the same text
;; the glj block's print-methods write.
(defmethod print-method FlatVec [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatColVec [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatList [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatBatch [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatMap [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatRecord [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatTagged [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatRow [x w] (.write w (pr-str (-rep x))))
(defmethod print-method FlatSet [x w] (.write w (pr-str (-rep x))))

)) ; end bb handles

;; --- the basilisp handles -------------------------------------------------
;; Hand-written like the bb/glojure sections: the types implement
;; basilisp's collection interfaces directly (the basilisp coll
;; adapter's eve.core is clojure.core itself, so there is no eve.core
;; protocol surface to write against), plus this namespace's handle
;; protocols. Basilisp mechanics, each proven in miniature before this
;; section was written: deftype* refuses partial interface
;; implementations, so every inherited member is spelled; nth arrives
;; with Python kwargs (the runtime passes notfound= as a keyword, the
;; NTH_SENTINEL marking the two-arity call); meta is a Python property
;; on the interfaces, so each type's meta is installed as a property
;; after its deftype; IReversible is not an abstract class, so it
;; cannot be claimed in deftype* and rseq is installed the same way;
;; fields are not assignable, so the _h/_d caches stay uncomputed
;; fields like the bb section's (its -hash/-digest compute on each
;; call too).

#?(:lpy (do

(deftype FlatVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatVec ctx off m _h _d))
  (-hmeta [_] mm)
  basilisp.lang.interfaces/IPersistentVector
  (assoc [_ & kvs] (loop [h _ ps (seq kvs)]
                     (if ps (recur (assocN-flat h (first ps) (second ps)) (nnext ps)) h)))
  (cons [_ & xs] (cons-flat _ (first xs)))
  (seq [_] (seq-of-node ctx off))
  (contains [_ k] (and (integer? k) (<= 0 k) (< k (elem-count ctx off))))
  (entry [_ k] (when (and (integer? k) (<= 0 k) (< k (elem-count ctx off)))
                 (basilisp.core/map-entry k (elem-at ctx off k))))
  (val-at [_ & args] (let [k (first args)]
                       (if (and (integer? k) (<= 0 k) (< k (elem-count ctx off)))
                         (elem-at ctx off k)
                         (second args))))
  (empty [_] [])
  (^{:kwargs :collect} nth [_ i & kwargs]
    (let [nf (:notfound kwargs ::none)]
      (if (and (integer? i) (<= 0 i) (< i (elem-count ctx off)))
        (elem-at ctx off i)
        (if (or (identical? nf ::none)
                (identical? nf (.-NTH_SENTINEL basilisp.lang.interfaces/IIndexed)))
          (throw (python/IndexError (str i)))
          nf))))
  (peek [_] (when (pos? (elem-count ctx off))
              (elem-at ctx off (dec (elem-count ctx off)))))
  (pop [_] (pop-flat _))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (seq-of-node ctx off))
                       (pg-reduce (first args) (second args) (seq-of-node ctx off))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatVec ctx off m _h _d))
  (__len__ [_] (elem-count ctx off))
  (__getitem__ [_ i] (if (and (integer? i) (<= 0 i) (< i (elem-count ctx off)))
                       (elem-at ctx off i)
                       (throw (python/IndexError (str i)))))
  (__iter__ [_] (python/iter (seq-of-node ctx off)))
  (__eq__ [_ other] (node-equiv ctx off other))
  (__hash__ [_] (node-hasheq ctx off))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatVec "meta" (python/property (fn [this] (.-mm this))))
(python/setattr FlatVec "rseq" (fn [this] (seq (into () (seq-of-node (.-ctx this) (.-off this))))))

(deftype FlatColVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatColVec ctx off m _h _d))
  (-hmeta [_] mm)
  basilisp.lang.interfaces/IPersistentVector
  (assoc [_ & kvs] (loop [h _ ps (seq kvs)]
                     (if ps (recur (assocN-flat h (first ps) (second ps)) (nnext ps)) h)))
  (cons [_ & xs] (cons-flat _ (first xs)))
  (seq [_] (seq-of-node ctx off))
  (contains [_ k] (and (integer? k) (<= 0 k) (< k (elem-count ctx off))))
  (entry [_ k] (when (and (integer? k) (<= 0 k) (< k (elem-count ctx off)))
                 (basilisp.core/map-entry k (elem-at ctx off k))))
  (val-at [_ & args] (let [k (first args)]
                       (if (and (integer? k) (<= 0 k) (< k (elem-count ctx off)))
                         (elem-at ctx off k)
                         (second args))))
  (empty [_] [])
  (^{:kwargs :collect} nth [_ i & kwargs]
    (let [nf (:notfound kwargs ::none)]
      (if (and (integer? i) (<= 0 i) (< i (elem-count ctx off)))
        (elem-at ctx off i)
        (if (or (identical? nf ::none)
                (identical? nf (.-NTH_SENTINEL basilisp.lang.interfaces/IIndexed)))
          (throw (python/IndexError (str i)))
          nf))))
  (peek [_] (when (pos? (elem-count ctx off))
              (elem-at ctx off (dec (elem-count ctx off)))))
  (pop [_] (pop-flat _))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (seq-of-node ctx off))
                       (pg-reduce (first args) (second args) (seq-of-node ctx off))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatColVec ctx off m _h _d))
  (__len__ [_] (elem-count ctx off))
  (__getitem__ [_ i] (if (and (integer? i) (<= 0 i) (< i (elem-count ctx off)))
                       (elem-at ctx off i)
                       (throw (python/IndexError (str i)))))
  (__iter__ [_] (python/iter (seq-of-node ctx off)))
  (__eq__ [_ other] (node-equiv ctx off other))
  (__hash__ [_] (node-hasheq ctx off))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatColVec "meta" (python/property (fn [this] (.-mm this))))
(python/setattr FlatColVec "rseq" (fn [this] (seq (into () (seq-of-node (.-ctx this) (.-off this))))))

(deftype FlatList [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (list-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatList ctx off m _h _d))
  (-hmeta [_] mm)
  basilisp.lang.interfaces/IPersistentStack
  (peek [_] (when (pos? (elem-count ctx off)) (elem-at ctx off 0)))
  (pop [_] (pop-flat _))
  basilisp.lang.interfaces/IPersistentCollection
  (cons [_ & xs] (cons-flat _ (first xs)))
  (empty [_] ())
  (seq [_] (seq-of-node ctx off))
  basilisp.lang.interfaces/IIndexed
  (^{:kwargs :collect} nth [_ i & kwargs]
    (let [nf (:notfound kwargs ::none)]
      (if (and (integer? i) (<= 0 i) (< i (elem-count ctx off)))
        (elem-at ctx off i)
        (if (or (identical? nf ::none)
                (identical? nf (.-NTH_SENTINEL basilisp.lang.interfaces/IIndexed)))
          (throw (python/IndexError (str i)))
          nf))))
  basilisp.lang.interfaces/ILookup
  (val-at [_ & args] (let [k (first args)]
                       (if (and (integer? k) (<= 0 k) (< k (elem-count ctx off)))
                         (elem-at ctx off k)
                         (second args))))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (seq-of-node ctx off))
                       (pg-reduce (first args) (second args) (seq-of-node ctx off))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatList ctx off m _h _d))
  (__len__ [_] (elem-count ctx off))
  (__iter__ [_] (python/iter (seq-of-node ctx off)))
  (__eq__ [_ other] (node-equiv ctx off other))
  (__hash__ [_] (node-hasheq ctx off))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatList "meta" (python/property (fn [this] (.-mm this))))

(deftype FlatBatch [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (batch-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatBatch ctx off m _h _d))
  (-hmeta [_] mm)
  basilisp.lang.interfaces/IPersistentVector
  (assoc [_ & kvs] (loop [h _ ps (seq kvs)]
                     (if ps (recur (assocN-flat h (first ps) (second ps)) (nnext ps)) h)))
  (cons [_ & xs] (cons-flat _ (first xs)))
  (seq [_] (batch-seq-of ctx off))
  (contains [_ k] (and (integer? k) (<= 0 k) (< k (elem-count ctx off))))
  (entry [_ k] (when (and (integer? k) (<= 0 k) (< k (elem-count ctx off)))
                 (basilisp.core/map-entry k (row-handle ctx off k))))
  (val-at [_ & args] (let [k (first args)]
                       (if (and (integer? k) (<= 0 k) (< k (elem-count ctx off)))
                         (row-handle ctx off k)
                         (second args))))
  (empty [_] [])
  (^{:kwargs :collect} nth [_ i & kwargs]
    (let [nf (:notfound kwargs ::none)]
      (if (and (integer? i) (<= 0 i) (< i (elem-count ctx off)))
        (row-handle ctx off i)
        (if (or (identical? nf ::none)
                (identical? nf (.-NTH_SENTINEL basilisp.lang.interfaces/IIndexed)))
          (throw (python/IndexError (str i)))
          nf))))
  (peek [_] (when (pos? (elem-count ctx off))
              (row-handle ctx off (dec (elem-count ctx off)))))
  (pop [_] (pop-flat _))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (batch-seq-of ctx off))
                       (pg-reduce (first args) (second args) (batch-seq-of ctx off))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatBatch ctx off m _h _d))
  (__len__ [_] (elem-count ctx off))
  (__getitem__ [_ i] (if (and (integer? i) (<= 0 i) (< i (elem-count ctx off)))
                       (row-handle ctx off i)
                       (throw (python/IndexError (str i)))))
  (__iter__ [_] (python/iter (batch-seq-of ctx off)))
  (__eq__ [_ other] (node-equiv ctx off other))
  (__hash__ [_] (node-hasheq ctx off))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatBatch "meta" (python/property (fn [this] (.-mm this))))
(python/setattr FlatBatch "rseq" (fn [this] (seq (into () (batch-seq-of (.-ctx this) (.-off this))))))

(deftype FlatMap [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatMap ctx off m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  basilisp.lang.interfaces/IPersistentMap
  (assoc [_ & kvs] (loop [h _ ps (seq kvs)]
                     (if ps (recur (assoc-flat h (first ps) (second ps)) (nnext ps)) h)))
  (dissoc [_ & ks] (loop [h _ ps (seq ks)]
                     (if ps (recur (dissoc-flat h (first ps)) (next ps)) h)))
  (contains [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  (entry [_ k] (let [v (lookup-node ctx off k ::absent)]
                 (when-not (identical? ::absent v)
                   (basilisp.core/map-entry k v))))
  (val-at [_ & args] (lookup-node ctx off (first args) (second args)))
  (cons [_ & xs] (pg-map-conj _ (first xs)))
  (empty [_] {})
  (seq [_] (pg-entries-seq (entries-of-node ctx off)))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (pg-entries-seq (entries-of-node ctx off)))
                       (pg-reduce (first args) (second args) (pg-entries-seq (entries-of-node ctx off)))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatMap ctx off m _h _d))
  (__len__ [_] (count (entries-of-node ctx off)))
  (__getitem__ [_ k] (let [v (lookup-node ctx off k ::absent)]
                       (if (identical? ::absent v)
                         (throw (python/KeyError k))
                         v)))
  (__iter__ [_] (python/iter (pg-entries-seq (entries-of-node ctx off))))
  (__eq__ [_ other] (entries-equiv (entries-of-node ctx off) other))
  (__hash__ [_] (entries-hasheq-of (entries-of-node ctx off)))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatMap "meta" (python/property (fn [this] (.-mm this))))

(deftype FlatRecord [ctx off mm _h _d tkey fp]
  d/ISabpType
  (-sabp-type-key [_] tkey)
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  basilisp.lang.interfaces/IPersistentMap
  (assoc [_ & kvs] (loop [h _ ps (seq kvs)]
                     (if ps (recur (assoc-flat h (first ps) (second ps)) (nnext ps)) h)))
  (dissoc [_ & ks] (loop [h _ ps (seq ks)]
                     (if ps (recur (dissoc-flat h (first ps)) (next ps)) h)))
  (contains [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  (entry [_ k] (let [v (lookup-node ctx off k ::absent)]
                 (when-not (identical? ::absent v)
                   (basilisp.core/map-entry k v))))
  (val-at [_ & args] (lookup-node ctx off (first args) (second args)))
  (cons [_ & xs] (pg-map-conj _ (first xs)))
  (empty [_] {})
  (seq [_] (pg-entries-seq (entries-of-node ctx off)))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (pg-entries-seq (entries-of-node ctx off)))
                       (pg-reduce (first args) (second args) (pg-entries-seq (entries-of-node ctx off)))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  (__len__ [_] (count (entries-of-node ctx off)))
  (__getitem__ [_ k] (let [v (lookup-node ctx off k ::absent)]
                       (if (identical? ::absent v)
                         (throw (python/KeyError k))
                         v)))
  (__iter__ [_] (python/iter (pg-entries-seq (entries-of-node ctx off))))
  (__eq__ [_ other] (entries-equiv (entries-of-node ctx off) other))
  (__hash__ [_] (entries-hasheq-of (entries-of-node ctx off)))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatRecord "meta" (python/property (fn [this] (.-mm this))))

(deftype FlatTagged [ctx off mm _h _d tkey fp]
  d/ISabpType
  (-sabp-type-key [_] tkey)
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  basilisp.lang.interfaces/IPersistentMap
  (assoc [_ & kvs] (loop [h _ ps (seq kvs)]
                     (if ps (recur (assoc-flat h (first ps) (second ps)) (nnext ps)) h)))
  (dissoc [_ & ks] (loop [h _ ps (seq ks)]
                     (if ps (recur (dissoc-flat h (first ps)) (next ps)) h)))
  (contains [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  (entry [_ k] (let [v (lookup-node ctx off k ::absent)]
                 (when-not (identical? ::absent v)
                   (basilisp.core/map-entry k v))))
  (val-at [_ & args] (lookup-node ctx off (first args) (second args)))
  (cons [_ & xs] (pg-map-conj _ (first xs)))
  (empty [_] {})
  (seq [_] (pg-entries-seq (entries-of-node ctx off)))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (pg-entries-seq (entries-of-node ctx off)))
                       (pg-reduce (first args) (second args) (pg-entries-seq (entries-of-node ctx off)))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  (__len__ [_] (count (entries-of-node ctx off)))
  (__getitem__ [_ k] (let [v (lookup-node ctx off k ::absent)]
                       (if (identical? ::absent v)
                         (throw (python/KeyError k))
                         v)))
  (__iter__ [_] (python/iter (pg-entries-seq (entries-of-node ctx off))))
  (__eq__ [_ other] (entries-equiv (entries-of-node ctx off) other))
  (__hash__ [_] (entries-hasheq-of (entries-of-node ctx off)))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatTagged "meta" (python/property (fn [this] (.-mm this))))

(deftype FlatRow [ctx off row mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (row-entries ctx off row))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (row-digest-of _))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRow ctx off row m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (row-entries ctx off row))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  (-mcount [_] (count (row-entries ctx off row)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRowHandle
  (-row-index [_] row)
  basilisp.lang.interfaces/IPersistentMap
  (assoc [_ & kvs] (loop [h _ ps (seq kvs)]
                     (if ps (recur (assoc-flat h (first ps) (second ps)) (nnext ps)) h)))
  (dissoc [_ & ks] (loop [h _ ps (seq ks)]
                     (if ps (recur (dissoc-flat h (first ps)) (next ps)) h)))
  (contains [_ k] (not (identical? ::absent (row-lookup ctx off row k ::absent))))
  (entry [_ k] (let [v (row-lookup ctx off row k ::absent)]
                 (when-not (identical? ::absent v)
                   (basilisp.core/map-entry k v))))
  (val-at [_ & args] (row-lookup ctx off row (first args) (second args)))
  (cons [_ & xs] (pg-map-conj _ (first xs)))
  (empty [_] {})
  (seq [_] (pg-entries-seq (row-entries ctx off row)))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (pg-entries-seq (row-entries ctx off row)))
                       (pg-reduce (first args) (second args) (pg-entries-seq (row-entries ctx off row)))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatRow ctx off row m _h _d))
  (__len__ [_] (count (row-entries ctx off row)))
  (__getitem__ [_ k] (let [v (row-lookup ctx off row k ::absent)]
                       (if (identical? ::absent v)
                         (throw (python/KeyError k))
                         v)))
  (__iter__ [_] (python/iter (pg-entries-seq (row-entries ctx off row))))
  (__eq__ [_ other] (entries-equiv (row-entries ctx off row) other))
  (__hash__ [_] (entries-hasheq-of (row-entries ctx off row)))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatRow "meta" (python/property (fn [this] (.-mm this))))

(deftype FlatSet [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatSet ctx off m _h _d))
  (-hmeta [_] mm)
  basilisp.lang.interfaces/IPersistentSet
  (disj [_ & xs] (loop [h _ ps (seq xs)]
                   (if ps (recur (disjoin-flat h (first ps)) (next ps)) h)))
  (cons [_ & xs] (cons-flat _ (first xs)))
  (empty [_] #{})
  (seq [_] (when (pos? (long (first (set-parts ctx off))))
             (seq (set-elements ctx off))))
  basilisp.lang.interfaces/ILookup
  (val-at [_ & args] (let [k (first args)
                           es (set-elements ctx off)]
                       (loop [i 0]
                         (cond (== i (count es)) (second args)
                               (= (nth es i) k) (nth es i)
                               :else (recur (inc i))))))
  basilisp.lang.interfaces/IReduce
  (reduce [_ & args] (if (== 1 (count args))
                       (pg-reduce (first args) (seq (set-elements ctx off)))
                       (pg-reduce (first args) (second args) (seq (set-elements ctx off)))))
  basilisp.lang.interfaces/IWithMeta
  (meta [_] mm)
  (with-meta [_ m] (->FlatSet ctx off m _h _d))
  (__len__ [_] (long (first (set-parts ctx off))))
  (__contains__ [_ k] (boolean (some (fn [e] (= e k)) (set-elements ctx off))))
  (__iter__ [_] (python/iter (seq (set-elements ctx off))))
  (__eq__ [_ other] (node-equiv ctx off other))
  (__hash__ [_] (node-hasheq ctx off))
  (__str__ [_] (pr-str (-rep _))))

(python/setattr FlatSet "meta" (python/property (fn [this] (.-mm this))))

)) ; end lpy handles

#?(:glj (do

(deftype FlatVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatVec ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatColVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatColVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatColVec ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatList [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (list-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatList ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] ())
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off)) (elem-at ctx off 0)))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatList ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatBatch [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (batch-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatBatch ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (row-handle ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (row-handle ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (row-handle ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (row-handle ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (batch-seq-of ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (row-handle ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (batch-seq-of ctx off)))
  (-reduce [_ f init] (pg-reduce f init (batch-seq-of ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatBatch ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype ^:eve/map FlatMap [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatMap ctx off m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatMap ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype ^:eve/map FlatRecord [ctx off mm _h _d tkey fp]
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  ;; a generic record carries its type's identity: the generated
  ;; <Type>? predicates answer by type key for both of a type's
  ;; forms (eve.deftype-proto.data/type-key-of)
  d/ISabpType
  (-sabp-type-key [_] tkey)
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype ^:eve/map FlatTagged [ctx off mm _h _d tkey fp]
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  IGenericRecord
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype ^:eve/map FlatRow [ctx off row mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (row-entries ctx off row))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (row-digest-of _))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRow ctx off row m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (row-entries ctx off row))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  (-mcount [_] (count (row-entries ctx off row)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRowHandle
  (-row-index [_] row)
  ecore/ICounted
  (-count [_] (count (row-entries ctx off row)))
  ecore/ILookup
  (-lookup [_ k] (row-lookup ctx off row k nil))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (row-lookup ctx off row k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (row-entries ctx off row)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (row-lookup ctx off row k nil))
  (-invoke [_ k nf] (row-lookup ctx off row k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (row-entries ctx off row))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (row-entries ctx off row))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (row-entries ctx off row) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (row-entries ctx off row)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRow ctx off row m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatSet [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatSet ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (long (first (set-parts ctx off))))
  ecore/ILookup
  (-lookup [_ k] (some (fn [e] (when (= e k) e)) (set-elements ctx off)))
  (-lookup [_ k nf] (let [es (set-elements ctx off)]
                      (loop [i 0] (cond (== i (count es)) nf
                                         (= (nth es i) k) (nth es i)
                                         :else (recur (inc i))))))
  ecore/ISeqable
  (-seq [_] (when (pos? (long (first (set-parts ctx off))))
              (seq (set-elements ctx off))))
  ecore/ISet
  (-disjoin [_ v] (disjoin-flat _ v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] #{})
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq (set-elements ctx off))))
  (-reduce [_ f init] (pg-reduce f init (seq (set-elements ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatSet ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(defmethod print-method FlatVec [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatColVec [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatList [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatBatch [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatMap [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatRecord [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatTagged [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatRow [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatSet [x w] (ecore/-pr-writer x w nil))

)) ; end glj handles

;; let-go takes the glojure text: the same protocol surface over the
;; same walks. Two differences, both the host's own: the map handles
;; are plain deftypes (glojure's ^:eve/map routing meta has no
;; let-go counterpart — eve.types/letgo: its completion has no
;; set-type-equality!/set-coll-dispatch! hooks, so a type's =, hash
;; and collection access ride eve.core's protocols here), and the
;; print-methods ride eve.core/-pr-writer onto let-go's print
;; writer through eve.encoding/write!, as glojure's do.
#?(:lg (do

(deftype FlatVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatVec ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatColVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatColVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatColVec ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatList [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (list-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatList ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] ())
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off)) (elem-at ctx off 0)))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatList ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatBatch [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (batch-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatBatch ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (row-handle ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (row-handle ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (row-handle ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (row-handle ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (batch-seq-of ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (row-handle ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (batch-seq-of ctx off)))
  (-reduce [_ f init] (pg-reduce f init (batch-seq-of ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatBatch ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatMap [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatMap ctx off m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatMap ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatRecord [ctx off mm _h _d tkey fp]
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  ;; a generic record carries its type's identity: the generated
  ;; <Type>? predicates answer by type key for both of a type's
  ;; forms (eve.deftype-proto.data/type-key-of)
  d/ISabpType
  (-sabp-type-key [_] tkey)
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatTagged [ctx off mm _h _d tkey fp]
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  IGenericRecord
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatRow [ctx off row mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (row-entries ctx off row))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (row-digest-of _))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRow ctx off row m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (row-entries ctx off row))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  (-mcount [_] (count (row-entries ctx off row)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRowHandle
  (-row-index [_] row)
  ecore/ICounted
  (-count [_] (count (row-entries ctx off row)))
  ecore/ILookup
  (-lookup [_ k] (row-lookup ctx off row k nil))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (row-lookup ctx off row k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (row-entries ctx off row)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (row-lookup ctx off row k nil))
  (-invoke [_ k nf] (row-lookup ctx off row k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (row-entries ctx off row))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (row-entries ctx off row))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (row-entries ctx off row) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (row-entries ctx off row)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRow ctx off row m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatSet [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatSet ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (long (first (set-parts ctx off))))
  ecore/ILookup
  (-lookup [_ k] (some (fn [e] (when (= e k) e)) (set-elements ctx off)))
  (-lookup [_ k nf] (let [es (set-elements ctx off)]
                      (loop [i 0] (cond (== i (count es)) nf
                                         (= (nth es i) k) (nth es i)
                                         :else (recur (inc i))))))
  ecore/ISeqable
  (-seq [_] (when (pos? (long (first (set-parts ctx off))))
              (seq (set-elements ctx off))))
  ecore/ISet
  (-disjoin [_ v] (disjoin-flat _ v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] #{})
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq (set-elements ctx off))))
  (-reduce [_ f init] (pg-reduce f init (seq (set-elements ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatSet ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(defmethod print-method FlatVec [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatColVec [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatList [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatBatch [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatMap [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatRecord [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatTagged [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatRow [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatSet [x w] (ecore/-pr-writer x w nil))

)) ; end lg handles

#?(:jank (do

(deftype FlatVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatVec ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatColVec [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatColVec ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (elem-at ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (elem-at ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (elem-at ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatColVec ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatList [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (list-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatList ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (elem-at ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (elem-at ctx off i) nf))
  ecore/ISeqable
  (-seq [_] (seq-of-node ctx off))
  ecore/ISequential
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] ())
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off)) (elem-at ctx off 0)))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq-of-node ctx off)))
  (-reduce [_ f init] (pg-reduce f init (seq-of-node ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatList ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatBatch [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (batch-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatBatch ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (elem-count ctx off))
  ecore/IIndexed
  (-nth [_ i] (let [n (elem-count ctx off)]
                (if (and (<= 0 i) (< i n))
                  (row-handle ctx off i)
                  (throw (IndexOutOfBoundsException. (str i))))))
  (-nth [_ i nf] (if (and (<= 0 i) (< i (elem-count ctx off)))
                   (row-handle ctx off i) nf))
  ecore/ILookup
  (-lookup [_ k] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                   (row-handle ctx off (long k)) nil))
  (-lookup [_ k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off)))
                      (row-handle ctx off (long k)) nf))
  ecore/IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) (elem-count ctx off))))
  ecore/ISeqable
  (-seq [_] (batch-seq-of ctx off))
  ecore/ISequential
  ecore/IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] [])
  ecore/IStack
  (-peek [_] (when (pos? (elem-count ctx off))
               (row-handle ctx off (dec (elem-count ctx off)))))
  (-pop [_] (pop-flat _))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (batch-seq-of ctx off)))
  (-reduce [_ f init] (pg-reduce f init (batch-seq-of ctx off)))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatBatch ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatMap [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatMap ctx off m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatMap ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatRecord [ctx off mm _h _d tkey fp]
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  ;; a generic record carries its type's identity: the generated
  ;; <Type>? predicates answer by type key for both of a type's
  ;; forms (eve.deftype-proto.data/type-key-of)
  d/ISabpType
  (-sabp-type-key [_] tkey)
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatTagged [ctx off mm _h _d tkey fp]
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (entries-of-node ctx off))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  (-mcount [_] (count (entries-of-node ctx off)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  IGenericRecord
  ecore/ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ecore/ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (entries-of-node ctx off)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (lookup-node ctx off k nil))
  (-invoke [_ k nf] (lookup-node ctx off k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (entries-of-node ctx off))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (entries-of-node ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (entries-of-node ctx off)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatRow [ctx off row mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (row-entries ctx off row))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (row-digest-of _))
  IAttachMeta
  (-attach-meta [_ m] (->FlatRow ctx off row m _h _d))
  (-hmeta [_] mm)
  IMapSource
  (-entries [_] (row-entries ctx off row))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  (-mcount [_] (count (row-entries ctx off row)))
  (-assoc-entry [_ k v] (assoc-flat _ k v))
  (-dissoc-entry [_ k] (dissoc-flat _ k))
  IRowHandle
  (-row-index [_] row)
  ecore/ICounted
  (-count [_] (count (row-entries ctx off row)))
  ecore/ILookup
  (-lookup [_ k] (row-lookup ctx off row k nil))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  ecore/IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (row-lookup ctx off row k ::absent))))
  ecore/ISeqable
  (-seq [_] (pg-entries-seq (row-entries ctx off row)))
  ecore/IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  ecore/ICollection
  (-conj [_ o] (pg-map-conj _ o))
  ecore/IEmptyableCollection
  (-empty [_] {})
  ecore/IFn
  (-invoke [_ k] (row-lookup ctx off row k nil))
  (-invoke [_ k nf] (row-lookup ctx off row k nf))
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (pg-entries-seq (row-entries ctx off row))))
  (-reduce [_ f init] (pg-reduce f init (pg-entries-seq (row-entries ctx off row))))
  ecore/IEquiv
  (-equiv [_ other] (entries-equiv (row-entries ctx off row) other))
  ecore/IHash
  (-hash [_] (entries-hasheq-of (row-entries ctx off row)))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatRow ctx off row m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(deftype FlatSet [ctx off mm _h _d]
  IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (handle-digest ctx off))
  IAttachMeta
  (-attach-meta [_ m] (->FlatSet ctx off m _h _d))
  (-hmeta [_] mm)
  ecore/ICounted
  (-count [_] (long (first (set-parts ctx off))))
  ecore/ILookup
  (-lookup [_ k] (some (fn [e] (when (= e k) e)) (set-elements ctx off)))
  (-lookup [_ k nf] (let [es (set-elements ctx off)]
                      (loop [i 0] (cond (== i (count es)) nf
                                         (= (nth es i) k) (nth es i)
                                         :else (recur (inc i))))))
  ecore/ISeqable
  (-seq [_] (when (pos? (long (first (set-parts ctx off))))
              (seq (set-elements ctx off))))
  ecore/ISet
  (-disjoin [_ v] (disjoin-flat _ v))
  ecore/ICollection
  (-conj [_ v] (cons-flat _ v))
  ecore/IEmptyableCollection
  (-empty [_] #{})
  ecore/IReduce
  (-reduce [_ f] (pg-reduce f (seq (set-elements ctx off))))
  (-reduce [_ f init] (pg-reduce f init (seq (set-elements ctx off))))
  ecore/IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  ecore/IHash
  (-hash [_] (node-hasheq ctx off))
  ecore/IMeta
  (-meta [_] mm)
  ecore/IWithMeta
  (-with-meta [_ m] (->FlatSet ctx off m _h _d))
  ecore/IPrintWithWriter
  (-pr-writer [_ w _] (ecore/-write w (pr-str (-rep _)))))

(defmethod print-method FlatVec [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatColVec [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatList [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatBatch [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatMap [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatRecord [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatTagged [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatRow [x w] (ecore/-pr-writer x w nil))
(defmethod print-method FlatSet [x w] (ecore/-pr-writer x w nil))

)) ; end jank handles

;; ---------------------------------------------------------------------------
;; The ClojureScript handles: the same node walks over the same layout,
;; answered through this host's collection protocols. Since the trunk
;; merge (Nov 14 2026) the walks are the shared ones — node-equiv
;; (digest-first), node-hasheq / entries-hasheq-of, the rep family,
;; handle-digest — exactly as the JVM handles call them; these
;; deftypes are only the host realization (the cljs protocol surface,
;; the ^:mutable hash/digest caches, MapEntry-shaped seqs). Before
;; the merge this block carried its own decode-based walks
;; (decode-all): equiv/hash paid a full decode per operation and a
;; typed value's rep came back a plain map with its metadata dropped.
;; Edits route to the same shared update path the JVM handles use
;; (cons-flat & friends below).
;; ---------------------------------------------------------------------------

#?(:cljs (defn- node-seq-entries [ctx off]
  ;; seq-wrapped like seq-of-node: a CLJS -seq must answer nil, not an
  ;; empty LazySeq, for an empty node
  (seq (map (fn [[k v]] (first (seq (array-map k v)))) (entries-of-node ctx off)))))
#?(:cljs (deftype FlatVec [ctx off mm ^:mutable _h ^:mutable _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (elem-count ctx off))
  ILookup
  (-lookup [_ k] (if (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (elem-count ctx off)))
                   (elem-at ctx off (int k)) nil))
  (-lookup [_ k nf] (if (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (elem-count ctx off)))
                      (elem-at ctx off (int k)) nf))
  IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (elem-count ctx off))))
  ISeqable
  (-seq [_] (seq-of-node ctx off))
  ISequential
  IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (node-hasheq ctx off)] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatVec ctx off m _h _d))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatColVec [ctx off mm ^:mutable _h ^:mutable _d]
  IFlatHandle
  (-rep [_] (let [r (vec-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (elem-count ctx off))
  ILookup
  (-lookup [_ k] (if (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (elem-count ctx off)))
                   (elem-at ctx off (int k)) nil))
  (-lookup [_ k nf] (if (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (elem-count ctx off)))
                      (elem-at ctx off (int k)) nf))
  IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (elem-count ctx off))))
  ISeqable
  (-seq [_] (seq-of-node ctx off))
  ISequential
  IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (node-hasheq ctx off)] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatColVec ctx off m _h _d))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatList [ctx off mm ^:mutable _h ^:mutable _d]
  IFlatHandle
  (-rep [_] (let [r (list-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (elem-count ctx off))
  ISeqable
  (-seq [_] (seq-of-node ctx off))
  ISequential
  IList
  IStack
  (-peek [_] (when (pos? (elem-count ctx off)) (elem-at ctx off 0)))
  (-pop [_] (pop-flat _))
  IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (node-hasheq ctx off)] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatList ctx off m _h _d))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatMap [ctx off mm ^:mutable _h ^:mutable _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ISeqable
  (-seq [_] (node-seq-entries ctx off))
  IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (entries-hasheq-of (entries-of-node ctx off))] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatMap ctx off m _h _d))
  IFn
  (-invoke [this k] (-lookup this k))
  (-invoke [this k nf] (-lookup this k nf))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatSet [ctx off mm ^:mutable _h ^:mutable _d]
  IFlatHandle
  (-rep [_] (let [r (into #{} (map rep-of) (set-elements ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (first (set-parts ctx off)))
  ILookup
  (-lookup [_ k] (let [[n ko] (set-parts ctx off)]
                   (loop [i 0] (if (== i n) nil
                                   (let [e (elem-at ctx ko i)] (if (= k e) e (recur (inc i))))))))
  (-lookup [_ k nf] (let [[n ko] (set-parts ctx off)]
                      (loop [i 0] (if (== i n) nf
                                      (let [e (elem-at ctx ko i)] (if (= k e) e (recur (inc i))))))))
  ISeqable
  (-seq [_] (let [[n ko] (set-parts ctx off)]
              (seq (map (fn [i] (elem-at ctx ko i)) (range n)))))
  ISet
  (-disjoin [_ v] (disjoin-flat _ v))
  IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (node-hasheq ctx off)] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatSet ctx off m _h _d))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatBatch [ctx off mm ^:mutable _h ^:mutable _d]
  IFlatHandle
  (-rep [_] (let [r (batch-rep-of ctx off)]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (:n (batch-parts ctx off)))
  ILookup
  (-lookup [_ k] (if (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (:n (batch-parts ctx off))))
                   (row-handle ctx off (int k)) nil))
  (-lookup [_ k nf] (if (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (:n (batch-parts ctx off))))
                      (row-handle ctx off (int k)) nf))
  IAssociative
  (-assoc [_ n v] (assocN-flat _ n v))
  (-contains-key? [_ k] (and (number? k) (== k (js/Math.floor k)) (<= 0 k) (< k (:n (batch-parts ctx off)))))
  ISeqable
  (-seq [_] (seq (batch-seq-of ctx off)))
  ISequential
  IVector
  (-assoc-n [_ n v] (assocN-flat _ n v))
  IEquiv
  (-equiv [_ other] (node-equiv ctx off other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (node-hasheq ctx off)] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatBatch ctx off m _h _d))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatRow [ctx off row mm ^:mutable _h ^:mutable _d]
  IFlatHandle
  (-rep [_] (let [r (entries-rep-of (row-entries ctx off row))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IRowHandle
  (-row-index [_] row)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (row-digest-of _)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (count (:ks (nth (.-shapes ctx) (:shape (batch-parts ctx off))))))
  ILookup
  (-lookup [_ k] (row-lookup ctx off row k nil))
  (-lookup [_ k nf] (row-lookup ctx off row k nf))
  IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (row-lookup ctx off row k ::absent))))
  ISeqable
  (-seq [_] (seq (map (fn [[k v]] (first (seq (array-map k v)))) (row-entries ctx off row))))
  IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  IEquiv
  (-equiv [_ other] (entries-equiv (row-entries ctx off row) other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (entries-hasheq-of (row-entries ctx off row))] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatRow ctx off row m _h _d))
  IFn
  (-invoke [this k] (-lookup this k))
  (-invoke [this k nf] (-lookup this k nf))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatTagged [ctx off mm ^:mutable _h ^:mutable _d tkey fp]
  ;; the generic record's marker, as the JVM's and bb's FlatTagged carry
  ;; it: a typed node of a type this reader has no sister for (a changed
  ;; layout's fingerprint) is generic too, not only a 0x2A tagged node
  IGenericRecord
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ISeqable
  (-seq [_] (node-seq-entries ctx off))
  IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (entries-hasheq-of (entries-of-node ctx off))] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatTagged ctx off m _h _d tkey fp))
  IFn
  (-invoke [this k] (-lookup this k))
  (-invoke [this k nf] (-lookup this k nf))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _)))))
)
#?(:cljs (deftype FlatRecord [ctx off mm ^:mutable _h ^:mutable _d tkey fp]
  IRecordInfo
  (-tkey [_] tkey)
  (-fp [_] fp)
  ;; a generic record carries its type's identity: the generated
  ;; <Type>? predicates answer by type key for both of a type's
  ;; forms (eve.deftype-proto.data/type-key-of)
  d/ISabpType
  (-sabp-type-key [_] tkey)
  IFlatHandle
  (-rep [_] (let [r (record-entries-rep tkey fp (entries-of-node ctx off))]
              (if (some? mm) (MetaVal. r (to-rep mm)) r)))
  (-chunk-bytes [_] (.-bs ctx))
  IHandlePos
  (-hctx [_] ctx)
  (-hoff [_] off)
  IFlatDigest
  (-digest [_] (let [d _d]
                 (if (some? d) (when-not (identical? d ::none) d)
                     (let [v (handle-digest ctx off)] (set! _d (or v ::none)) v))))
  ICounted
  (-count [_] (count (entries-of-node ctx off)))
  ILookup
  (-lookup [_ k] (lookup-node ctx off k nil))
  (-lookup [_ k nf] (lookup-node ctx off k nf))
  IAssociative
  (-assoc [_ k v] (assoc-flat _ k v))
  (-contains-key? [_ k] (not (identical? ::absent (lookup-node ctx off k ::absent))))
  ISeqable
  (-seq [_] (node-seq-entries ctx off))
  IMap
  (-dissoc [_ k] (dissoc-flat _ k))
  IEquiv
  (-equiv [_ other] (entries-equiv (entries-of-node ctx off) other))
  IHash
  (-hash [_] (let [v _h]
               (if (some? v) v
                   (let [n (entries-hasheq-of (entries-of-node ctx off))] (set! _h n) n))))
  IMeta
  (-meta [_] mm)
  IWithMeta
  (-with-meta [_ m] (->FlatRecord ctx off m _h _d tkey fp))
  IFn
  (-invoke [this k] (-lookup this k))
  (-invoke [this k nf] (-lookup this k nf))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (pr-str (-rep _))))))

;; --- collection-op completions (flat-all-hosts lane, Nov 16 2026):
;; cljs.core's conj / pop / empty / reduce dispatch DIRECTLY to
;; ICollection / IStack / IEmptyableCollection / IReduce — there
;; is no seq fallback — and the deftypes above carried none of
;; them, so (conj flat-handle x) threw on this host while the
;; same call works on the JVM (proxy surface) and on bb/glj
;; (eve.core protocol arms). The bodies route to the same shared
;; update path (cons-flat, assoc-flat, pop-flat) every other
;; host's handles use. Map invocation (IFn) is carried in the map
;; deftypes' own bodies above — extend-protocol IFn breaks
;; shadow-cljs's IFn method synthesis, measured.
#?(:cljs (defn- cljs-map-conj [h x]
  (cond (map-entry? x) (assoc-flat h (key x) (val x))
        (vector? x) (assoc-flat h (nth x 0) (nth x 1))
        (map? x) (reduce-kv (fn [m k v] (assoc-flat m k v)) h x)
        :else (reduce (fn [m e] (assoc-flat m (key e) (val e))) h (seq x)))))
#?(:cljs (extend-protocol ICollection
  FlatVec (-conj [h x] (cons-flat h x))
  FlatColVec (-conj [h x] (cons-flat h x))
  FlatList (-conj [h x] (cons-flat h x))
  FlatBatch (-conj [h x] (cons-flat h x))
  FlatSet (-conj [h x] (cons-flat h x))
  FlatMap (-conj [h x] (cljs-map-conj h x))
  FlatRecord (-conj [h x] (cljs-map-conj h x))
  FlatTagged (-conj [h x] (cljs-map-conj h x))
  FlatRow (-conj [h x] (cljs-map-conj h x))))
#?(:cljs (extend-protocol IStack
  FlatVec (-peek [h] (let [c (elem-count (.-ctx h) (.-off h))]
                       (when (pos? c) (elem-at (.-ctx h) (.-off h) (dec c)))))
          (-pop [h] (pop-flat h))
  FlatColVec (-peek [h] (let [c (elem-count (.-ctx h) (.-off h))]
                          (when (pos? c) (elem-at (.-ctx h) (.-off h) (dec c)))))
             (-pop [h] (pop-flat h))
  FlatBatch (-peek [h] (let [c (elem-count (.-ctx h) (.-off h))]
                         (when (pos? c) (elem-at (.-ctx h) (.-off h) (dec c)))))
            (-pop [h] (pop-flat h))))
#?(:cljs (extend-protocol IEmptyableCollection
  FlatVec (-empty [_] [])
  FlatColVec (-empty [_] [])
  FlatList (-empty [_] ())
  FlatBatch (-empty [_] [])
  FlatMap (-empty [_] {})
  FlatRecord (-empty [_] {})
  FlatTagged (-empty [_] {})
  FlatRow (-empty [_] {})
  FlatSet (-empty [_] #{})))
#?(:cljs (extend-protocol IReduce
  FlatVec (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatColVec (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatList (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatBatch (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatMap (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatRecord (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatTagged (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatRow (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))
  FlatSet (-reduce ([h f] (reduce f (seq h))) ([h f init] (reduce f init (seq h))))))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; updates (C1.5): append new nodes and a new root past the old chunk's
;; length in a grown copy when the tables already cover the change;
;; otherwise re-pack. The old chunk's bytes are never mutated.
;; ---------------------------------------------------------------------------

(defn- writer-cx-of
  "A writer context (scan-shaped map) naming ctx's existing tables, so
   appended nodes resolve symbols and shapes to the codes already in
   the chunk."
  [^Ctx ctx]
  {:sym (zipmap (.-syms ctx) (range)) :syms (.-syms ctx)
   :shapes (zipmap (map (fn [s] [(:t s) (:ks s)]) (.-shapes ctx)) (range))
   :shape-list (.-shapes ctx)
   :types (zipmap (map (fn [t] [(:key t) (:fp t)]) (.-types ctx)) (range))
   :type-list (.-types ctx)})

(defn- rep-symbols
  "The symbol-table members a rep mentions."
  [rep acc]
  (cond
    (or (keyword? rep) (symbol? rep)) (conj acc rep)
    (instance? MetaVal rep) (rep-symbols (.-meta-rep ^MetaVal rep) (rep-symbols (.-inner ^MetaVal rep) acc))
    (instance? TypedVal rep) (reduce (fn [a [k v]] (rep-symbols v (conj a k))) acc (.-fields ^TypedVal rep))
    (map? rep) (reduce (fn [a [k v]] (rep-symbols v (rep-symbols k a))) acc rep)
    (instance? BytesVal rep) acc
    (coll? rep) (reduce (fn [a e] (rep-symbols e a)) acc rep)
    :else acc))

(defn- rep-has-map?
  "A plain map or typed value anywhere in rep — either may need a
   shape the chunk does not have."
  [rep]
  (cond
    (or (map? rep) (instance? TypedVal rep)) true
    (instance? MetaVal rep) (or (rep-has-map? (.-inner ^MetaVal rep)) (rep-has-map? (.-meta-rep ^MetaVal rep)))
    (instance? BytesVal rep) false
    (coll? rep) (boolean (some rep-has-map? rep))
    :else false))

(defn- appendable?
  "Can rep's nodes be appended to ctx's chunk? Every symbol it mentions
   must already be tabled, and it may not need a new shape."
  [^Ctx ctx rep]
  (and (not (rep-has-map? rep))
       (every? (fn [s] (contains? (:sym (writer-cx-of ctx)) s)) (rep-symbols rep #{}))))

(defn- grown-buf
  "A writer over a copy of ctx's bytes, positioned at the old length.
   Appends go here; the old array is untouched."
  [^Ctx ctx]
  (let [old (.-bs ctx) w (fio/buf (if (.-wide? ctx) 4 2))]
    (fio/buf-bytes w old 0 (byte-len old))
    w))

(defn- finish-append
  "Pad the grown copy, patch its header length and root, and answer the
   handle for new-root in a Ctx sharing the old chunk's tables."
  [^Ctx ctx w ^long new-root]
  (let [len (fio/buf-pos w) padded (bit-and (+ len 7) (bit-not 7))]
    (loop [i len] (when (< i padded) (fio/buf-u8 w 0) (recur (inc i))))
    (when (and (> padded CHUNK-TARGET) (not (.-wide? ctx)))
      (throw (ex-info "flat chunk: append past the u16 target" {:type ::append-overflow})))
    (fio/buf-patch-u32 w 8 padded)
    (fio/buf-patch-u32 w 12 new-root)
    ;; the recipe slot describes the ORIGINAL root: an appended
    ;; chunk must not carry it onto the new root
    (fio/buf-seek w 6)
    (fio/buf-u8 w (bit-and (fio/u8 (.-b ctx) 6) (bit-not 2)))
    (let [b (fio/buf-chunk w padded)
          nctx (->Ctx b (fio/chunk-bytes b) new-root (.-wide? ctx) (.-syms ctx) (.-shapes ctx)
                      (.-types ctx) (.-decodes ctx) nil nil (.-plans ctx))]
      (read-value nctx new-root))))

(defn- append-attempt
  "Run an append thunk; nil when it overflowed the u16 target, in which
   case the caller re-packs instead."
  [thunk]
  (try (thunk)
       ;; :cljs :default, as in eve.dataset.batch: cljs.core/ExceptionInfo
       ;; names nothing an SCI host evaluating this file can resolve,
       ;; and the body already discriminates on the ex-data type and
       ;; rethrows everything else.
       (catch #?(:jank Throwable :clj clojure.lang.ExceptionInfo :cljs :default :lpy basilisp.lang.exception/ExceptionInfo :cljr clojure.lang.ExceptionInfo :clje _) e
         (if (= ::append-overflow (:type (ex-data e)))
           nil
           (throw e)))))

(defn- repack
  "The update path of last resort: apply f to the handle's rep and
   pack the result into a fresh chunk."
  [h f]
  (open (pack-rep (f (-rep h)))))

(defn- append-vec-node
  "A new VEC node over child-offsets, appended in w; its offset."
  ^long [w child-offsets]
  (write-offsets-node! w 0x22 #(fio/buf-varint w (count child-offsets)) child-offsets))

(defn- child-offsets-of
  "The child offsets of the VEC node at off."
  [^Ctx ctx ^long off]
  (let [[n p] (seq-parts ctx off) w (width-of ctx)]
    (mapv (fn [i] (off-at (.-b ctx) (+ p (* w i)) (.-wide? ctx))) (range n))))

(defn cons-flat [h v]
  (let [ctx (handle-ctx h) off (handle-off h) t (tag-at ctx off) vr (to-rep v)]
    (cond
      ;; a vector: append the element node and a new VEC node over the
      ;; old children plus it — the COL case re-packs (columns are
      ;; contiguous), as do batches and sets handled below by rep
      (== t 0x22)
      (or (when (appendable? ctx vr)
            (append-attempt
             (fn [] (let [w (grown-buf ctx) cx (writer-cx-of ctx)
                          eo (write-node! w cx vr)
                          root (append-vec-node w (conj (child-offsets-of ctx off) eo))]
                      (finish-append ctx w root)))))
          (repack h (fn [r] (conj r vr))))
      ;; a list conses at the head: the elements are the same nodes in
      ;; the same order, so a new LIST node over the old children works
      (== t 0x25)
      (or (when (appendable? ctx vr)
            (append-attempt
             (fn [] (let [w (grown-buf ctx) cx (writer-cx-of ctx)
                          eo (write-node! w cx vr)
                          root (write-offsets-node! w 0x25 #(fio/buf-varint w (inc (elem-count ctx off)))
                                                    (into [eo] (child-offsets-of ctx off)))]
                      (finish-append ctx w root)))))
          (repack h (fn [r] (conj r vr))))
      (== t 0x24) ;; a set: append the element, a new keys node, a new SET node
      (or (when (and (appendable? ctx vr)
                     (== (tag-at ctx (second (set-parts ctx off))) 0x22)
                     (not (boolean (some (fn [e] (key-equiv e v)) (set-elements ctx off)))))
            (append-attempt
             (fn [] (let [w (grown-buf ctx) cx (writer-cx-of ctx)
                          [n ko] (set-parts ctx off)
                          old-elems (mapv (fn [i] (elem-at ctx ko i)) (range n))
                          eo (write-node! w cx vr)
                          ;; new keys node: a VEC over old key offsets + the
                          ;; new one, in canonical order of key bytes
                          pairs (sort-by first d/compare-bytes
                                         (conj (mapv (fn [i e] [(try (key-bytes e) (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ (utf8-bytes (pr-str e))))
                                                                (nth (child-offsets-of ctx ko) i)])
                                                     (range n) old-elems)
                                               [(try (key-bytes v) (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ (utf8-bytes (pr-str v)))) eo]))
                          keys-off (append-vec-node w (mapv second pairs))
                          s (fio/buf-pos w)]
                      (fio/buf-u8 w 0x24) (fio/buf-varint w (inc n)) (fio/buf-off w keys-off)
                      (finish-append ctx w s)))))
          (repack h (fn [r] (conj r vr))))
      :else (repack h (fn [r] (conj r vr))))))

(defn assocN-flat [h i v]
  (let [ctx (handle-ctx h) off (handle-off h) t (tag-at ctx off) vr (to-rep v)
        n (elem-count ctx off)]
    (cond
      (or (< i 0) (> i n)) (throw #?(:cljs (js/Error. (str i))
                    :cljr (IndexOutOfRangeException. (str i))
                    :clje (ex-info (str i) {})
                    :default (IndexOutOfBoundsException. (str i))))
      (== i n) (cons-flat h v)
      (== t 0x22)
      (or (when (appendable? ctx vr)
            (append-attempt
             (fn [] (let [w (grown-buf ctx) cx (writer-cx-of ctx)
                          eo (write-node! w cx vr)
                          root (append-vec-node w (assoc (child-offsets-of ctx off) i eo))]
                      (finish-append ctx w root)))))
          (repack h (fn [r] (assoc r i vr))))
      :else (repack h (fn [r] (assoc r i vr))))))

(defn pop-flat [h]
  (let [ctx (handle-ctx h) off (handle-off h) t (tag-at ctx off)]
    (cond
      (== t 0x22)
      (let [offs (child-offsets-of ctx off)]
        (if (empty? offs)
          (throw #?(:jank (ex-info "Can't pop empty vector" {})
                    :cljs (js/Error. "Can't pop empty vector")
                    :cljr (InvalidOperationException. "Can't pop empty vector")
                    :clje (ex-info "Can't pop empty vector" {})
                    :default (IllegalStateException. "Can't pop empty vector")))
          (or (append-attempt
               (fn [] (let [w (grown-buf ctx)
                            root (append-vec-node w (pop offs))]
                        (finish-append ctx w root))))
              (repack h (fn [r] (pop r))))))
      (== t 0x25)
      (let [offs (child-offsets-of ctx off)]
        (if (empty? offs)
          (throw #?(:jank (ex-info "Can't pop empty list" {})
                    :cljs (js/Error. "Can't pop empty list")
                    :cljr (InvalidOperationException. "Can't pop empty list")
                    :clje (ex-info "Can't pop empty list" {})
                    :default (IllegalStateException. "Can't pop empty list")))
          (or (append-attempt
               (fn [] (let [w (grown-buf ctx)
                            root (write-offsets-node! w 0x25 #(fio/buf-varint w (dec (count offs))) (subvec offs 1))]
                        (finish-append ctx w root))))
              (repack h (fn [r] (pop r))))))
      :else (repack h (fn [r] (pop r))))))

(defn assoc-flat [h k v]
  (let [^Ctx ctx (handle-ctx h) off (handle-off h) t (tag-at ctx off) vr (to-rep v)]
    (cond
      ;; a row, a record, a tagged record: the rep path (a row's assoc
      ;; answers a plain flat map; a record stays a record)
      (or #?(:clj (satisfies? IRowHandle h) :cljs (instance? FlatRow h)) (satisfies? IRecordInfo h))
      (repack h (fn [r] (if (instance? TypedVal r)
                          (TypedVal. (.-tkey ^TypedVal r) (.-fp ^TypedVal r) (assoc (.-fields ^TypedVal r) k vr))
                          (assoc r k vr))))
      ;; a shaped MAP whose shape already holds k: append the value and
      ;; a new MAP node over the old offsets with one replaced
      (== t 0x21)
      (let [[s p] (map-parts ctx off) sh (nth (.-shapes ctx) s) ks (:ks sh)
            i (shape-index ctx sh k)]
        (or (when (and (not (neg? i)) (appendable? ctx vr))
              (append-attempt
               (fn [] (let [w (grown-buf ctx) cx (writer-cx-of ctx)
                            eo (write-node! w cx vr)
                            offs (mapv (fn [j] (off-at (.-b ctx) (+ p (* (width-of ctx) j)) (.-wide? ctx))) (range (count ks)))
                            root (write-offsets-node! w 0x21 #(fio/buf-varint w s) (assoc offs i eo))]
                        (finish-append ctx w root)))))
            (repack h (fn [r] (assoc r (to-rep k) vr)))))
      :else (repack h (fn [r] (assoc r (to-rep k) vr))))))

(defn dissoc-flat [h k]
  (repack h (fn [r] (if (instance? TypedVal r)
                      (TypedVal. (.-tkey ^TypedVal r) (.-fp ^TypedVal r) (dissoc (.-fields ^TypedVal r) k))
                      (dissoc r (to-rep k))))))

(defn disjoin-flat [h v]
  (repack h (fn [r] (disj r (to-rep v)))))

;; ---------------------------------------------------------------------------
;; opening, unpacking, and the small public API
;; ---------------------------------------------------------------------------

(defn open
  "The handle reading the packed chunk in bs — the trusted door of
   FORMAT-V1 §10: the header is checked, the nodes are not."
  [^bytes bs]
  (let [^Ctx ctx (header-ctx bs false)]
    (read-value ctx (.-root ctx))))

(defn open-checked
  "The untrusted door of §10 (header checks here; the deep node checks
   are lane C4's eve.flat.check, which this delegates to when present)."
  [^bytes bs]
  (let [^Ctx ctx (header-ctx bs true)]
    (read-value ctx (.-root ctx))))

(defn chunk-bytes
  "The bytes of the chunk a flat handle reads."
  ^bytes [h]
  (cond
    (flat-handle? h) (-chunk-bytes h)
    (host-bytes? h) h
    :else (throw (ex-info "flat chunk: not a flat handle" {:type ::not-a-handle :value h}))))

(defn bytes-of
  "The bytes of a flat handle (alias kept for the eve.flat API)."
  ^bytes [h]
  (chunk-bytes h))

(defn bytes
  "The chunk bytes of a handle — the name the eve.flat API
   delegates to."
  [h]
  (chunk-bytes h))

(defn full-decodes
  "How many whole-chunk materializations handles over this chunk's
   context have run (C1.4's counter: protocol operations never run one)."
  ^long [h]
  @(.-decodes ^Ctx (handle-ctx h)))

(defn content-digest
  "The handle's content digest (FORMAT-V1 §8), or nil when it has none."
  [h]
  (digest-of h))

(defn generic-record?
  "Is h a generic record — a typed value this reader has no sister for?"
  [h]
  (satisfies? IGenericRecord h))

(defn record-tag
  "The type key of a record or generic-record handle."
  [h]
  (when (satisfies? IRecordInfo h) (-tkey h)))

(declare materialize-row)

(declare unpack)

(defn- materialize
  "The plain value of the node at off — the one whole-value decode
   walk, on every host (before the trunk merge it was :clj-only and
   ClojureScript ran its own decode-all copy)."
  [^Ctx ctx ^long off]
  (let [t (tag-at ctx off)]
    (cond
      (or (== t 0x22) (== t 0x25))
      (let [xs (mapv (fn [i] (materialize ctx (nth (child-offsets-of ctx off) i)))
                     (range (elem-count ctx off)))]
        (if (== t 0x25) (apply list xs) xs))
      (== t 0x23) (let [col (col-parts ctx off) n (long (:n col))]
                    (mapv (fn [i] (let [x (col-at ctx col i)]
                                    (if (flat-handle? x) (unpack x) x)))
                          (range n)))
      (== t 0x26)
      (let [bp (batch-parts ctx off)]
        (mapv (fn [i] (materialize-row ctx off i)) (range (long (:n bp)))))
      (or (== t 0x21) (== t 0x27))
      (into {} (map (fn [[k v]] [k (if (flat-handle? v) (unpack v) v)]))
            (entries-of-node ctx off))
      (== t 0x24)
      (let [[n ko] (set-parts ctx off)]
        (into #{} (map (fn [i] (let [x (elem-at ctx ko i)]
                                 (if (flat-handle? x) (unpack x) x))))
              (range n)))
      (== t 0x29)
      (let [w (width-of ctx)
            vo (off-at (.-b ctx) (+ off 1) (.-wide? ctx))
            mo (off-at (.-b ctx) (+ off 1 w) (.-wide? ctx))
            inner (materialize ctx vo)]
        (if #?(:clj (instance? clojure.lang.IObj inner) :cljs (satisfies? IWithMeta inner))
          (with-meta inner (materialize ctx mo))
          inner))
      (== t 0x2A)
      (let [[_ p] (fio/varint (.-b ctx) (+ off 2))]
        (materialize ctx (off-at (.-b ctx) p (.-wide? ctx))))
      :else (let [x (read-value ctx off)]
              (if (flat-handle? x) (unpack x) x)))))

(defn- materialize-row [^Ctx ctx ^long boff ^long row]
  (into {} (map (fn [[k v]] [k (if (flat-handle? v) (unpack v) v)]))
        (row-entries ctx boff row)))

(defn unpack
  "The plain value of a flat handle (or of chunk bytes): the
   whole-chunk decode C1.4's counter counts. A scalar is itself."
  [x]
  (cond
    (host-bytes? x)
    (let [c (fio/chunk x)]
      (if (and (>= (fio/chunk-len c) 4)
               (== 0x45 (fio/u8 c 0)) (== 0x56 (fio/u8 c 1))
               (== 0x53 (fio/u8 c 2)) (== 0x31 (fio/u8 c 3)))
        (unpack (open x))
        x))
    (flat-handle? x)
    (let [^Ctx ctx (handle-ctx x)]
      (swap! (.-decodes ctx) inc)
      (let [v (if (satisfies? IRowHandle x)
                (materialize-row ctx (handle-off x) (-row-index x))
                (materialize ctx (handle-off x)))
            m (if (satisfies? IAttachMeta x) (-hmeta x) (meta x))]
        ;; the handle's offset is the value node; its meta lives on
        ;; the handle (read from the META wrapper), so re-apply it
        (if (and (some? m) #?(:clj (instance? clojure.lang.IObj v) :cljs (satisfies? IWithMeta v)))
          (with-meta v (if (flat-handle? m) (unpack m) m))
          v)))
    :else x))

;; --- the typed-array handoff (ClojureScript's zero-copy column view) --

#?(:cljs (defn- column-typed-array [ctx col]
  (when (:valid col)
    (throw (ex-info "flat chunk: a column with a validity bitmap has no dense typed view"
                    {:type ::no-dense-view :kind (:kind col)})))
  (let [b (.-bs ctx) buf (.-buffer b) base (+ (.-byteOffset b) (:data col)) n (:n col)]
    (case (:kind col)
      6 (js/Int32Array. buf base n)
      10 (js/BigInt64Array. buf base n)
      9 (js/Float64Array. buf base n)
      8 (js/Float32Array. buf base n)
      17 (js/Uint8Array. buf base n)
      18 (js/Uint16Array. buf base n)
      (throw (ex-info "flat chunk: no dense typed view for this column kind"
                      {:type ::no-dense-view :kind (:kind col)}))))))
#?(:cljs (defn column-view
  "The dense typed array over a column of a flat chunk — zero copy,
   a view on the chunk's own buffer (the Node form of the §5/§8
   zero-copy handoff at chunk scope). One argument: the root is a COL
   node. Two arguments: the root is a BATCH and field names a column.
   i64 comes back a js/BigInt64Array (exact past 2^53), f64 a
   js/Float64Array. Narrowed, run-end, utf-8, any and struct columns,
   and any column carrying a validity bitmap, refuse with a typed
   ex-info."
  ([h]
   (let [ctx (handle-ctx h) off (handle-off h)]
     (when-not (== (tag-at ctx off) 0x23)
       (throw (ex-info "flat chunk: not a column node" {:type ::not-a-column :tag (tag-at ctx off)})))
     (column-typed-array ctx (col-parts ctx off))))
  ([h field]
   (let [ctx (handle-ctx h) off (handle-off h)]
     (when-not (== (tag-at ctx off) 0x26)
       (throw (ex-info "flat chunk: not a batch node" {:type ::not-a-batch :tag (tag-at ctx off)})))
     (let [bp (batch-parts ctx off) sh (nth (.-shapes ctx) (:shape bp)) ks (:ks sh)
           i (loop [i 0] (cond (== i (count ks)) -1
                               (= field (nth ks i)) i
                               :else (recur (inc i))))]
       (when (neg? i)
         (throw (ex-info "flat chunk: no such field" {:type ::no-field :field field})))
       (column-typed-array ctx (nth @(:cols bp) i)))))))
