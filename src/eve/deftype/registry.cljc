(ns eve.deftype.registry
  (:require [eve.host]
            [eve.codec :as codec]
            [eve.hamt-util :as hu]))

;; Compile-time type registry. Populated by eve-deftype macro during
;; macro expansion. Read by subsequent macros to resolve type references.
;;
;; NOT available at runtime. The macro bakes all needed info into
;; the generated code.

(defonce registry (atom {}))

;; ---------------------------------------------------------------------------
;; Type ids. An instance's block starts [type-id:u8][name-hash:u24]; the
;; type-id byte is also the pointer tag that names the type inside a
;; serialized value. Both are format facts: every process that shares the
;; memory must derive the same ones, whatever it loaded and in whatever
;; order. So both come from the type's namespace-qualified name, through
;; eve's own hash — the xxh3-128 of the name's canonical key bytes
;; (eve.codec/encode-key, the scalar hash of Merkle §1b) folded to an
;; int32 (eve.hamt-util/fold-hash-int) — computed here so every host
;; derives it alike:
;;
;;   type-id    0x40 + (name-hash key) mod 173    0x40-0xEC
;;   name-hash  (name-hash key) & 0xFFFFFF
;;
;; DECLARED BREAK (Oct 25 2026 — "move to eve's hash"): this
;; derivation replaces the previous one, Murmur3's hashInt of Java's
;; String.hashCode (the JVM's and ClojureScript's `hash` of a string).
;; The change re-keys every type-id and name-hash: values persisted
;; under the old ids do not read back under the new ones, and there is
;; no migration — a clean break, like the hash break before it. No
;; host `hash` is used on either side of the break: hosts disagree
;; with each other (ClojureCLR's own `hash` of a string differs —
;; measured 2026-10-16: "a" is 1455541201 on the JVM, 1867108634 on
;; ClojureCLR), which is why the derivation is computed here.
;;
;; 0x00-0x3F are eve's pointer and fast tags (deftype-proto.serialize),
;; 0xED-0xEF the HAMT map and set headers and the flat vector, 0xF0-0xFF
;; unassigned. 173 values are few, so two types may share a type-id; when a
;; value is read back, the name hash in its header picks the type
;; (eve.deftype-proto.serialize/register-deftype-constructor!). Two types in
;; one process with the same type-id and name hash are refused here.
;; ---------------------------------------------------------------------------

(def type-id-base 0x40)
(def type-id-count 173)

(defn string-hash
  "eve's hash of the string s, on every host: the int32 fold
   (eve.hamt-util/fold-hash-int) of the xxh3-128 of s's canonical key
   bytes (eve.codec/encode-key) — the scalar hash of Merkle §1b. Not
   any host's `hash`, and not the retired Murmur3/String.hashCode
   derivation (see the declared-break note above)."
  [s]
  (hu/fold-hash-int (hu/xxh3-128-bytes (codec/encode-key s))))

(defn type-id-of
  "The type-id byte of the eve-deftype named type-key (\"my.ns/MyType\")."
  [type-key]
  (+ type-id-base (mod (string-hash type-key) type-id-count)))

(defn name-hash-of
  "The 24-bit name hash in bytes 1-3 of the type's blocks."
  [type-key]
  (bit-and (string-hash type-key) 0xFFFFFF))

;; [type-id name-hash] -> the type-key that holds it, in this process
(defonce claimed (atom {}))

(defn claim-type-id!
  "[type-id name-hash] for the eve-deftype named type-key. Throws when
   another type in this process already holds both."
  [type-key]
  (let [id [(type-id-of type-key) (name-hash-of type-key)]
        holder (get (swap! claimed update id #(or % type-key)) id)]
    (when (not= holder type-key)
      (throw (ex-info (str "eve-deftype " type-key " and " holder
                           " have the same type-id and name hash; rename one")
                      {:type-key type-key :holder holder :id id})))
    id))

(defn register-type!
  "Register an eve-deftype in the compile-time registry.
   Called by the eve-deftype macro during expansion."
  [type-name spec]
  (swap! registry assoc type-name spec))

(defn lookup-type
  "Look up a type by name in the registry. Returns nil if not found."
  [type-name]
  (get @registry type-name))

;; Primitive types recognized by the macro
(def primitive-types
  #{:int8 :uint8 :int16 :uint16 :int32 :uint32 :float32 :float64})

(defn primitive-type? [t]
  (contains? primitive-types t))

(defn field-size
  "Returns the byte size for a field type."
  [type-kw]
  (case type-kw
    (:int8 :uint8) 4      ;; padded to 4 for alignment
    (:int16 :uint16) 4    ;; padded to 4 for alignment
    (:int32 :uint32) 4
    :float32 4
    :float64 8
    :serialized 4          ;; offset to serialized block
    ;; eve-type reference: 4 bytes (offset)
    4))

(defn parse-field
  "Parse a field symbol with metadata into a field spec map.
   Returns {:name sym :mutability :immutable|:mutable|:volatile-mutable
            :type-hint kw-or-nil :type-class :primitive|:eve-type|:serialized
            :pointer? bool :ref? bool} — :pointer? for an int32 field marked
   ^:eve/pointer, which holds a block's offset (an int-map's children): the
   retire walk follows it (eve.retire). :ref? for the same field marked ^:ref:
   its value is another eve-deftype instance's offset, which eve.tally holds
   and drops."
  [field-sym]
  (let [m (meta field-sym)
        mutability (cond
                     (:volatile-mutable m) :volatile-mutable
                     (:mutable m)          :mutable
                     :else                 :immutable)
        ;; Find the type hint: any metadata key that's not mutable/volatile-mutable,
        ;; :tag, or reader-added metadata (:file, :line, :column, :end-line, :end-column,
        ;; :source, and a reader's own namespaced keys on every symbol — a hint is never
        ;; namespaced)
        hint-keys (remove namespace
                          (disj (set (keys m)) :mutable :volatile-mutable :tag :host :ref
                                :unsynchronized-mutable :eve/field :eve/pointer
                                :file :line :column :end-line :end-column :source))
        type-hint (first hint-keys)  ;; there should be 0 or 1
        type-class (cond
                     ;; ^:host — and ^:mutable with no slab type (a cache, a
                     ;; transient's edit token): on the instance, in the
                     ;; process's own memory, not in eve memory
                     (or (:host m)
                         (and (nil? type-hint) (:mutable m))) :host
                     (nil? type-hint)           :serialized
                     (primitive-type? type-hint) :primitive
                     ;; Otherwise assume it's an eve-type reference
                     ;; (will be validated at macro expansion time)
                     :else                       :eve-type)]
    {:name       (name field-sym)
     :sym        field-sym
     :mutability mutability
     :type-hint  type-hint
     :type-class type-class
     :pointer?   (boolean (:eve/pointer m))
     ;; ^:ref on an ^:int32 field: its value is another eve-deftype instance's offset (-1 none) — a reference of
     ;; any eve type, read as an offset (eve.tally holds and drops what it names)
     :ref?       (boolean (:ref m))}))

(defn compute-layout
  "Given parsed field specs, compute byte offsets and total size.
   Layout: [type-id:u8 + 3 pad][field0][field1]...
   Returns {:fields [updated-field-specs] :total-size n}"
  [parsed-fields]
  (loop [fields parsed-fields
         offset 4  ;; start after 4-byte header (type-id + padding)
         result []]
    (cond
      (empty? fields)
      {:fields result
       :total-size offset}

      ;; a host-local field takes no bytes in eve memory
      (= :host (:type-class (first fields)))
      (recur (rest fields) offset (conj result (first fields)))

      :else
      (let [f (first fields)
            sz (if (= :eve-type (:type-class f))
                 4
                 (field-size (:type-hint f)))
            ;; Align float64 to 8-byte boundary
            aligned-offset (if (= :float64 (:type-hint f))
                             (let [r (mod offset 8)]
                               (if (zero? r) offset (+ offset (- 8 r))))
                             offset)]
        (recur (rest fields)
               (+ aligned-offset sz)
               (conj result (assoc f :offset aligned-offset :size sz)))))))
