(ns eve.deftype-proto
  "The field reads and writes eve's type macros emit, and the parsing of a
   type body's protocol impls (eve.deftype-proto.sio, eve.deftype.macros and
   ClojureScript's eve.types-js build on them).

   Field types:
     ^:int8, ^:uint8, ^:int16, ^:uint16     - small integers
     ^:int32, ^:uint32                       - 32-bit integers (Atomics-capable)
     ^:float32, ^:float64                    - floating point
     ^:SomeType                              - reference to another eve type (offset)

   Mutability:
     (default)          - immutable after construction
     ^:mutable          - mutable via set! (single worker)
     ^:volatile-mutable - atomic via set!/cas! (multi-worker safe)"
  (:refer-clojure :exclude [aset volatile?])
  (:require [eve.host :refer [aset volatile?]]
            [clojure.string :as str]))

(defn has-volatile-fields? [fields]
  (some #(= :volatile-mutable (:mutability %)) fields))

(defn parse-protocol-impls [body]
  (loop [remaining body
         current-proto nil
         result []]
    (if (empty? remaining)
      (if current-proto (conj result current-proto) result)
      (let [form (first remaining)]
        (if (and (symbol? form) (not (list? form)))
          (recur (rest remaining)
                 {:protocol form :methods []}
                 (if current-proto (conj result current-proto) result))
          (if (and (seq? form) current-proto)
            (recur (rest remaining)
                   (update current-proto :methods conj
                           {:name (first form)
                            :args (second form)
                            :body (rest (rest form))})
                   result)
            (recur (rest remaining) current-proto result)))))))

(defn user-provides-protocol? [parsed-protos proto-sym]
  (some #(= proto-sym (:protocol %)) parsed-protos))

;;-----------------------------------------------------------------------------
;; JVM Code Generation Helpers (ISlabIO Pattern)
;;-----------------------------------------------------------------------------
;;
;; On JVM, slab field access uses the ISlabIO protocol:
;;   (eve.slab-io/read-i32 sio slab-offset field-off)
;;   (eve.slab-io/write-i32! sio slab-offset field-off val)
;; The deftype stores [^long slab-off__ sio__] instead of [offset__].
;; ISlabIO methods handle class/block resolution internally.

(defn jvm-read-fn-for-type
  "Return the JVM read expression for a field type.
   sio__ and slab-off__ are bound from deftype fields."
  [{:keys [type-hint type-class mutability]} slab-off-expr field-offset]
  (case type-class
    :primitive
    (case type-hint
      :int8    (list 'let ['b__jvm (list 'eve.slab-io/read-u8
                                         'sio__ slab-off-expr field-offset)]
                 (list 'if (list '> 'b__jvm 127) (list '- 'b__jvm 256) 'b__jvm))
      :uint8   (list 'eve.slab-io/read-u8 'sio__ slab-off-expr field-offset)
      :int16   (list 'let ['v__jvm (list 'eve.slab-io/read-u16
                                         'sio__ slab-off-expr field-offset)]
                 (list 'if (list '> 'v__jvm 32767) (list '- 'v__jvm 65536) 'v__jvm))
      :uint16  (list 'eve.slab-io/read-u16 'sio__ slab-off-expr field-offset)
      ;; an immutable field is written once, before its instance is published: the hot read path's plain
      ;; node read (eve.deftype-proto.alloc/node-i32; jvm-heap-grade step 3). A mutable one keeps the atomic read.
      :int32   (if (#{:mutable :volatile-mutable} mutability)
                 (list 'eve.slab-io/read-i32 'sio__ slab-off-expr field-offset)
                 (list 'eve.deftype-proto.alloc/node-i32 'sio__ slab-off-expr field-offset))
      :uint32  (list 'bit-and
                     (list 'long (list 'eve.slab-io/read-i32
                                       'sio__ slab-off-expr field-offset))
                     0xFFFFFFFF)
      :float32 (list 'Float/intBitsToFloat
                     (list 'int (list 'eve.slab-io/read-i32
                                      'sio__ slab-off-expr field-offset)))
      :float64 (list 'let ['lo__jvm (list 'bit-and
                                          (list 'long (list 'eve.slab-io/read-i32
                                                            'sio__ slab-off-expr field-offset))
                                          0xFFFFFFFF)
                            'hi__jvm (list 'bit-and
                                          (list 'long (list 'eve.slab-io/read-i32
                                                            'sio__ slab-off-expr (+ field-offset 4)))
                                          0xFFFFFFFF)]
                 (list 'Double/longBitsToDouble
                       (list 'bit-or (list 'bit-shift-left 'hi__jvm 32) 'lo__jvm))))
    :eve-type
    (list 'eve.slab-io/read-i32 'sio__ slab-off-expr field-offset)
    ;; default
    (list 'eve.slab-io/read-i32 'sio__ slab-off-expr field-offset)))

(defn jvm-write-fn-for-type
  "Return the JVM write expression for a field type."
  [{:keys [type-hint type-class]} slab-off-expr field-offset val-expr]
  (case type-class
    :primitive
    (case type-hint
      (:int8 :uint8)
      (list 'eve.slab-io/write-u8! 'sio__ slab-off-expr field-offset
            (list 'int val-expr))
      (:int16 :uint16)
      (list 'eve.slab-io/write-u16! 'sio__ slab-off-expr field-offset
            (list 'int val-expr))
      ;; its 32 bits, not a checked cast: an int32 field may hold a slab-qualified offset (int-map's and the
      ;; red-black tree's children), which is past 2^31 from class 4 up — the cast threw once a heap domain's
      ;; 128-byte class filled and its blocks spilled upward (30 rebuilds of a 200-key int-map, 2026-10-20 ET)
      :int32
      (list 'eve.slab-io/write-i32! 'sio__ slab-off-expr field-offset
            (list 'unchecked-int val-expr))
      ;; a uint32 past 2^31 is its two's-complement bits (read back masked)
      :uint32
      (list 'eve.slab-io/write-i32! 'sio__ slab-off-expr field-offset
            (list 'unchecked-int val-expr))
      :float32
      (list 'eve.slab-io/write-i32! 'sio__ slab-off-expr field-offset
            (list 'Float/floatToRawIntBits (list 'float val-expr)))
      :float64
      (list 'let ['bits__jvm (list 'Double/doubleToRawLongBits (list 'double val-expr))]
        (list 'do
          (list 'eve.slab-io/write-i32! 'sio__ slab-off-expr field-offset
                (list 'unchecked-int (list 'bit-and 'bits__jvm 0xFFFFFFFF)))
          (list 'eve.slab-io/write-i32! 'sio__ slab-off-expr (+ field-offset 4)
                (list 'unchecked-int (list 'unsigned-bit-shift-right 'bits__jvm 32))))))
    ;; a slab-qualified offset is unsigned 32-bit [class:3 | block:29]: a block of
    ;; class 4 or above is past 2^31, so its bits, not a checked cast (which
    ;; threw once a process's heap spilled into 512-byte blocks); reads mask
    ;; it back (alloc/decode-class-idx)
    :eve-type
    (list 'eve.slab-io/write-i32! 'sio__ slab-off-expr field-offset
          (list 'unchecked-int val-expr))
    ;; default
    (list 'eve.slab-io/write-i32! 'sio__ slab-off-expr field-offset
          (list 'int val-expr))))

(defn jvm-field-bindings
  "Generate let-bindings for reading all fields on JVM."
  [fields slab-off-expr]
  (vec (mapcat (fn [f]
                 [(symbol (:name f))
                  (jvm-read-fn-for-type f slab-off-expr (:offset f))])
               fields)))

(defn jvm-rewrite-set!-forms
  "Rewrite (set! field val) to JVM ISlabIO write operations."
  [form field-map slab-off-expr]
  (cond
    ;; set! on a field
    (and (seq? form)
         (= 'set! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          val-expr (jvm-rewrite-set!-forms (nth form 2) field-map slab-off-expr)]
      (when (= :immutable (:mutability field-spec))
        (throw (ex-info (str "Cannot set! immutable field: " (:name field-spec)) {})))
      (jvm-write-fn-for-type field-spec slab-off-expr (:offset field-spec) val-expr))

    ;; cas! — atomic compare-and-exchange via ISlabIO
    (and (seq? form)
         (= 'cas! (first form))
         (symbol? (second form))
         (contains? field-map (name (second form))))
    (let [field-spec (get field-map (name (second form)))
          expected-expr (jvm-rewrite-set!-forms (nth form 2) field-map slab-off-expr)
          new-expr (jvm-rewrite-set!-forms (nth form 3) field-map slab-off-expr)]
      (when (not= :volatile-mutable (:mutability field-spec))
        (throw (ex-info "cas! requires ^:volatile-mutable field" {:field (:name field-spec)})))
      (when (= :serialized (:type-class field-spec))
        (throw (ex-info "cas! not supported on serialized fields" {:field (:name field-spec)})))
      (list '==
            (list 'int expected-expr)
            (list 'eve.slab-io/cas-i32!
                  'sio__ slab-off-expr (:offset field-spec)
                  (list 'int expected-expr) (list 'int new-expr))))

    ;; Recurse
    (seq? form)
    (with-meta (apply list (map #(jvm-rewrite-set!-forms % field-map slab-off-expr) form))
               (meta form))
    (vector? form)
    (with-meta (mapv #(jvm-rewrite-set!-forms % field-map slab-off-expr) form)
               (meta form))
    (map? form)
    (with-meta (into {} (map (fn [[k v]]
                               [(jvm-rewrite-set!-forms k field-map slab-off-expr)
                                (jvm-rewrite-set!-forms v field-map slab-off-expr)])
                             form))
               (meta form))
    :else form))

(defn jvm-transform-extend-method-body
  "Transform method body for JVM extend-type. Binds slab-off__ and sio__
   from the this reference since extend-type methods don't have field access."
  [body fields field-map this-sym type-name]
  (let [slab-off-sym 'slab-off__
        field-read-bindings (jvm-field-bindings fields slab-off-sym)
        rewritten (map #(jvm-rewrite-set!-forms % field-map slab-off-sym) body)
        resolve-bindings ['slab-off__ (list '.-slab-off__
                                            (with-meta this-sym {:tag type-name}))
                          'sio__      (list '.-sio__
                                            (with-meta this-sym {:tag type-name}))]
        all-bindings (vec (concat resolve-bindings field-read-bindings))]
    (list (apply list 'let all-bindings (vec rewritten)))))

