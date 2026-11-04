# eve/obj — Typed Objects

`eve.obj` provides schema-based typed objects in slab memory, on every host (`src/eve/obj.cljc`; the types and the
`0x1E` block format are `eve.obj.impl`'s). Two representations, for different access patterns:

- **`obj`** (AoS — array of structures): one object with named typed fields, one `0x1E` block. Good for individual
  records, tree nodes, whole-object operations.
- **`obj-array`** (SoA — structure of arrays): N objects stored column-wise, one eve array per field. Good for batch
  processing and cache-efficient iteration.

Both reach memory through `eve.slab-io`. `int32`/`uint32` fields (and int32 columns) carry the atomics `cas!`, `add!`,
`sub!`, `exchange!`; `cas!` and `exchange!` also take `:obj` and `:array` fields.

**Where they live.** Like every eve type, an object allocates in the running `swap!`'s domain, else in this process's
own slab. Create it inside the function you pass to `swap!` and store it in the atom's value; one built outside
belongs to another domain and is refused when stored (`doc/collections.md`).

**Reading.** `@atom` materializes: an object reads back as a plain map of its fields. To use the object itself — its
fields in place, its atomics — work inside `swap!`'s function or inside the read region `eve.alpha/$`, where `@`
answers the atom's value in place, held until the region exits.

Every sample below was run on babashka 1.13.226 and on the JVM (Clojure 1.12.0, JDK 21) on 2026-10-20, with identical
output. They read fields through `obj/get` and count with `e/count`: on the JVM and ClojureScript an object also
answers keyword lookup (`(:key node)`), invocation (`(node :key)`) and `clojure.core/count`; on babashka, whose eve
types answer eve's protocols rather than the host's interfaces, keyword lookup reads nil and invocation throws.

## Setup

```clojure
(require '[eve.alpha :as e]
         '[eve.atom :as ea]
         '[eve.obj :as obj])
```

## Schemas

A schema defines the shape of an object — like a V8 hidden class. Create once, reuse for many objects:

```clojure
(def node-schema
  (obj/create-schema {:key :int32 :left :int32 :right :int32 :value :float64}))
```

### Supported Field Types

| Type | Size | Atomic ops | Description |
|------|------|---------|-------------|
| `:int8` | 1B | — | Signed 8-bit integer |
| `:uint8` | 1B | — | Unsigned 8-bit integer |
| `:int16` | 2B | — | Signed 16-bit integer |
| `:uint16` | 2B | — | Unsigned 16-bit integer |
| `:int32` | 4B | `cas!` `add!` `sub!` `exchange!` | Signed 32-bit integer |
| `:uint32` | 4B | `cas!` `add!` `sub!` `exchange!` | Unsigned 32-bit integer |
| `:float32` | 4B | — | 32-bit float |
| `:float64` | 8B | — | 64-bit float |
| `:obj` | 4B | `cas!` `exchange!` | Offset reference to another object (`obj/get-offset`) |
| `:array` | 4B | `cas!` `exchange!` | Offset reference to an eve array |

Fields are sorted by alignment (descending) for packing.

## Single Objects

```clojure
(def state (e/atom ::state {}))

;; create the object inside a swap!
(swap! state (fn [s] (assoc s :tree (obj/obj node-schema {:key 42 :left -1 :right -1 :value 3.14}))))
```

### Field Access

```clojure
(e/$ (let [node (:tree @state)]
       [(obj/get node :key) (obj/get node :value) (e/count node) (obj/get node :left)]))
;=> [42 3.14 4 -1]

@state  ;=> {:tree {:value 3.14, :key 42, :left -1, :right -1}}   — materialized: a plain map
```

### Mutation

`obj/assoc!` writes a field in place; the atomics are lock-free updates of one field's word. Do them inside `swap!`
(or `$`), or where you otherwise hold the object exclusively:

```clojure
(swap! state (fn [s] (obj/assoc! (:tree s) :key 100) s))
(e/$ (obj/get (:tree @state) :key))                             ;=> 100

(e/$ (obj/cas! (:tree @state) :key 100 200))                    ;=> true   (swapped)
(e/$ (obj/add! (:tree @state) :key 10))                         ;=> 200    (the old value)
(e/$ (obj/sub! (:tree @state) :key 5))                          ;=> 210
(e/$ (obj/exchange! (:tree @state) :key 999))                   ;=> 205
(e/$ (obj/get (:tree @state) :key))                             ;=> 999

(e/$ (obj/add! (:tree @state) :value 1))
;; throws: "add! not supported on :float64 fields"
```

### Inline Schema

If you don't reuse the schema, pass the field map directly:

```clojure
(swap! state (fn [s] (assoc s :point (obj/obj {:x :float64 :y :float64} {:x 1.5 :y 2.5}))))
(e/$ [(obj/get (:point @state) :x) (obj/get (:point @state) :y)])         ;=> [1.5 2.5]
```

## Object Arrays

`obj-array` stores N objects as one eve array per field:

```clojure
(def particles (e/atom ::particles {}))
(swap! particles (fn [s] (assoc s :data (obj/obj-array 1000 {:x :int32 :y :int32 :velocity :int32}))))
```

### Element Access

```clojure
(swap! particles (fn [s]
                   (let [data (:data s)]
                     (obj/assoc-in! data [0 :x] 42)
                     (obj/assoc-in! data [0 :y] 100)
                     (obj/assoc-in! data [1 :x] 8))
                   s))

(e/$ (obj/get-in (:data @particles) [0 :x]))                        ;=> 42
(e/$ (e/get (e/nth (:data @particles) 0) :x))                       ;=> 42   (a row view)
(e/$ (obj/add-in! (:data @particles) [0 :velocity] 3))              ;=> 0    (the old value)
```

`cas-in!`, `sub-in!` and `exchange-in!` are the other per-element atomics.

### Column Access

`obj/column` answers the field's eve array (an `eve.array` value — on the JVM family a `HeapEveArray`; on
ClojureScript and nbb `eve.typed-view/get-typed-view` gives a JS TypedArray over its elements):

```clojure
(e/$ (obj/column-reduce (:data @particles) :x 0 (fn [acc _idx v] (+ acc v))))   ;=> 50
(swap! particles (fn [s] (obj/column-map! (:data s) :x (fn [_idx v] (* v 2))) s))
(e/$ [(obj/get-in (:data @particles) [0 :x]) (obj/get-in (:data @particles) [1 :x])])   ;=> [84 16]
```

## Low-level

`obj/get-schema` (an object's or array's schema), `obj/get-offset` (the object's block, what an `:obj` field holds),
`obj/obj-from-offset`, and `obj/retire!` (free the object's block at once, with no epoch gate — only for an object no
atom's value holds; one stored in an atom is released by the write that drops it).

## When to Use eve/obj

Most application state should use regular Clojure maps — they're stored as eve hash maps inside atoms. Use `eve.obj`
when you need:

- **typed numeric fields** with lock-free field updates (CAS, add, sub);
- **SoA layout** for batch processing;
- **memory-efficient** storage for many uniform records.
