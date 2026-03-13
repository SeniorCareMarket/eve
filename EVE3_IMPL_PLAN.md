# Eve3 Implementation Plan

## Executive Summary

Build a parallel `eve3.*` namespace tree that unifies CLJ/CLJS data structures using **Option 1** from the Migration Plan: thread `sio` (ISlabIO) explicitly through every deftype and every algorithm function, on both platforms. No dynamic vars, no module-level singletons, no macro-time platform divergence for field access. The macro maps from **CLJ protocol names to CLJS protocol names** (CLJ is the superset), so users write JVM-style names and the macro emits CLJS equivalents.

### How Eve3 differs from Eve2

| Concern | Eve2 | Eve3 |
|---|---|---|
| Canonical protocol names | CLJS (`ICounted`, `-count`) | CLJ (`clojure.lang.Counted`, `count`) |
| CLJS field access | Macro inlines DataView reads | ISlabIO protocol calls (same as CLJ) |
| CLJ field access | ISlabIO via dynamic var binding | ISlabIO threaded explicitly |
| `sio` on CLJS | Module-level singleton (`eve-alloc/cljs-sio`) | Stored in deftype field, threaded to algorithms |
| `sio` on CLJ | Dynamic var (`*jvm-slab-ctx*`), macro auto-binds | Stored in deftype field, threaded to algorithms |
| `get-sio` calls in method bodies | ~53 across 4 files | Zero — `sio` is a deftype field |
| Macro complexity | High (two completely different emission paths) | Low (one emission path, only protocol name translation differs) |

---

## Architecture

```
User writes (.cljc):
  (eve3/eve3-deftype EveHashMap
    [^:int32 cnt ^:int32 root-off]
    clojure.lang.Counted
    (count [_] cnt)
    clojure.lang.ILookup
    (valAt [_ k] ...))

Both platforms expand to:
  (deftype EveHashMap [sio__ ^long offset__]
    <platform-appropriate protocol/interface>
    (<platform-appropriate method name> [_]
      (let [cnt (-sio-read-i32 sio__ offset__ 4)]
        cnt)))

On CLJ: protocol names pass through verbatim (they're already JVM names).
On CLJS: macro translates clojure.lang.Counted → ICounted, count → -count, etc.
```

### Key Design Decisions

1. **CLJ protocol names are canonical.** CLJ interfaces are a superset of CLJS protocols. The mapping table translates CLJ→CLJS at compile time. For protocols with no CLJS equivalent (e.g., `java.util.Map`), the macro emits them only on CLJ via `#?@(:clj ...)`.

2. **ISlabIO is threaded, not ambient.** Every eve3 deftype carries `sio__` as its first field. Every algorithm function takes `sio` as its first parameter. No dynamic vars, no module-level singletons. This makes the code referentially transparent and testable with any ISlabIO implementation.

3. **One code path for field access.** Both CLJS and CLJ read fields via `(-sio-read-i32 sio__ offset__ field-offset)`. On CLJS, `CljsSlabIO` dispatches to the existing DataView functions. The performance cost (~20-50ns per protocol dispatch) is accepted as a worthwhile tradeoff for code simplicity.

4. **Separate eve3 tree.** No changes to `eve.*` or `eve2.*`. The three trees coexist.

---

## Source Tree

```
src/eve3/
├── deftype.clj              # THE MACRO (detects CLJS/CLJ via &env)
├── deftype/
│   └── registry.clj         # Compile-time type registry (field parsing, layout)
├── proto_map.clj             # Protocol mapping table (CLJ→CLJS)
├── alloc.cljc               # Slab allocator bridge
├── map.cljc                 # HAMT map — single deftype, unified algorithms
├── vec.cljc                 # Persistent vector
├── set.cljc                 # HAMT set
├── list.cljc                # Persistent list
├── atom.cljc                # Cross-process mmap atom
└── alpha.cljc               # Public API entry point
```

Test tree mirrors this under `test/eve3/`.

---

## Phase 1: Macro Foundation

### 1.1 Protocol Mapping Table (`eve3/proto_map.clj`)

Maps CLJ→CLJS. Data-only file read at macro expansion time.

```clojure
(def jvm->cljs
  '{;; Core collection protocols
    clojure.lang.Counted
    {:cljs-proto ICounted
     :methods {count {:cljs-name -count :arities [[_]]}}}

    clojure.lang.ILookup
    {:cljs-proto ILookup
     :methods {valAt {:cljs-name -lookup :arities [[_ k] [_ k nf]]}}}

    clojure.lang.Associative
    {:cljs-proto IAssociative
     :methods {containsKey {:cljs-name -contains-key? :arities [[_ k]]}
               assoc       {:cljs-name -assoc         :arities [[_ k v]]}}}

    clojure.lang.IPersistentMap
    {:cljs-proto IMap
     :methods {without {:cljs-name -dissoc :arities [[_ k]]}}}

    clojure.lang.IPersistentCollection
    {:cljs-proto ICollection
     :methods {cons  {:cljs-name -conj  :arities [[_ v]]}
               empty {:cljs-name -empty :arities [[_]]}
               equiv {:cljs-name -equiv :arities [[_ other]]}}}

    clojure.lang.IHashEq
    {:cljs-proto IHash
     :methods {hasheq {:cljs-name -hash :arities [[_]]}}}

    clojure.lang.Seqable
    {:cljs-proto ISeqable
     :methods {seq {:cljs-name -seq :arities [[_]]}}}

    clojure.lang.IMeta
    {:cljs-proto IMeta
     :methods {meta {:cljs-name -meta :arities [[_]]}}}

    clojure.lang.IObj
    {:cljs-proto IWithMeta
     :methods {withMeta {:cljs-name -with-meta :arities [[_ m]]}}}

    clojure.lang.IFn
    {:cljs-proto IFn
     :methods {invoke {:cljs-name -invoke :arities [[_ a] [_ a b]]}}}

    clojure.lang.IReduceInit
    {:cljs-proto IReduce
     :methods {reduce {:cljs-name -reduce :arities [[_ f init]]}}}

    clojure.lang.IKVReduce
    {:cljs-proto IKVReduce
     :methods {kvreduce {:cljs-name -kv-reduce :arities [[_ f init]]}}}

    clojure.lang.Indexed
    {:cljs-proto IIndexed
     :methods {nth {:cljs-name -nth :arities [[_ n] [_ n nf]]}}}

    clojure.lang.IPersistentStack
    {:cljs-proto IStack
     :methods {peek {:cljs-name -peek :arities [[_]]}
               pop  {:cljs-name -pop  :arities [[_]]}}}

    clojure.lang.IPersistentVector
    {:cljs-proto IVector
     :methods {assocN {:cljs-name -assoc-n :arities [[_ n val]]}}}

    clojure.lang.IPersistentSet
    {:cljs-proto ISet
     :methods {disjoin {:cljs-name -disjoin :arities [[_ v]]}
               get     {:cljs-name -get     :arities [[_ v]]}}}

    clojure.lang.ISeq
    {:cljs-proto ISeq
     :methods {first {:cljs-name -first :arities [[_]]}
               more  {:cljs-name -rest  :arities [[_]]}}}

    clojure.lang.Sequential
    {:cljs-proto ISequential
     :marker? true
     :methods {}}

    ;; --- CLJ-only interfaces (no CLJS equivalent) ---
    ;; These are emitted only on CLJ, skipped on CLJS.

    clojure.lang.MapEquivalence
    {:cljs-proto nil  ;; CLJ-only marker
     :marker? true
     :methods {}}

    clojure.lang.IPersistentList
    {:cljs-proto nil  ;; CLJ-only marker
     :marker? true
     :methods {}}

    java.lang.Iterable
    {:cljs-proto nil  ;; CLJ-only
     :methods {iterator {:cljs-name nil :arities [[_]]}}}

    java.lang.Object
    {:cljs-proto nil  ;; CLJ-only
     :methods {toString {:cljs-name nil :arities [[_]]}
               equals   {:cljs-name nil :arities [[_ other]]}
               hashCode {:cljs-name nil :arities [[_]]}}}})
```

**Key difference from Eve2**: The mapping direction is reversed. CLJ names are canonical; the macro only needs to translate on CLJS. On CLJ, protocol/interface names pass through verbatim.

**Unmapped protocols**: If a user writes a symbol not in this table:
- On CLJ: emit verbatim (it's already a JVM interface/protocol name)
- On CLJS: emit verbatim (user must ensure it's a valid CLJS protocol, or use `#?`)

### 1.2 Type Registry (`eve3/deftype/registry.clj`)

Fork of `eve2/deftype/registry.clj`. Identical field parsing and layout computation. The only change: the computed deftype fields are `[sio__ ^long offset__]` instead of Eve2's `[^long offset__]` (CLJS) / `[field1 field2 ... offset__ sio _meta]` (CLJ).

### 1.3 The Macro (`eve3/deftype.clj`)

The macro is dramatically simpler than Eve2 because both platforms use the same field access pattern.

**Detection**: `(if (:ns &env) :cljs :clj)` — standard shadow-cljs pattern.

**Shared expansion pipeline (both platforms):**

1. Parse fields → compute layout (offsets, sizes)
2. For each method body: walk AST, replace field symbols with `(-sio-read-<type> sio__ offset__ <field-offset>)` calls
3. `set!` on fields → `(-sio-write-<type>! sio__ offset__ <field-offset> val)`
4. `cas!` on fields → `(sio-cas! sio__ offset__ <field-offset> expected new)`
5. Emit `(deftype TypeName [sio__ ^long offset__] ...protocols...)`
6. Emit constructor: `(-sio-alloc! sio total-size)`, write fields via ISlabIO, return instance

**Platform-specific step (protocol translation):**
- On CLJ: protocol names and method names pass through verbatim
- On CLJS: translate via `proto_map`: `clojure.lang.Counted` → `ICounted`, `count` → `-count`
- Protocols with `{:cljs-proto nil}` are **dropped entirely** on CLJS (CLJ-only interfaces)

**Boilerplate generation** (auto-added unless user provides):
- CLJ: `IHashEq/hasheq`, `Object/hashCode`, `Object/equals`, `Object/toString`
- CLJS: `IHash/-hash`, `IEquiv/-equiv`, `IPrintWithWriter/-pr-writer`

**Constructor:**
The auto-generated `->TypeName` takes `sio` + field values, allocates a slab block, writes fields, returns the instance. On both platforms.

```clojure
;; Auto-generated constructor (both platforms):
(defn ->EveHashMap [sio cnt root-off]
  (let [offset (-sio-alloc! sio <total-size>)]
    (-sio-write-u8! sio offset 0 <type-id>)
    (-sio-write-i32! sio offset 4 cnt)
    (-sio-write-i32! sio offset 8 root-off)
    (EveHashMap. sio offset)))
```

### 1.4 Deliverables for Phase 1

- `eve3/proto_map.clj` — CLJ→CLJS mapping table
- `eve3/deftype/registry.clj` — field parsing + layout
- `eve3/deftype.clj` — the macro (one emission path + protocol translation)
- `test/eve3/deftype_test.cljc` — macro expansion tests

---

## Phase 2: Allocator Bridge

### 2.1 `eve3/alloc.cljc`

Thin wrapper over `eve.deftype-proto.alloc`:
- Re-exports `ISlabIO` and all `-sio-*` methods, `NIL_OFFSET`
- On CLJS: exposes `cljs-sio` (the `CljsSlabIO` singleton) for constructing top-level instances
- On CLJ: exposes helper to create `JvmSlabCtx` instances

**Key difference from Eve2**: No `*jvm-slab-ctx*` dynamic var. No `get-sio` helper. The caller always passes `sio` explicitly.

---

## Phase 3: Data Structure Port

Every data structure follows this pattern:

1. **All algorithm functions take `sio` as first parameter.** This is already the case in Eve2's algorithm functions (e.g., `hamt-conj [sio root-off vh vb shift]`). No change needed there.

2. **The deftype carries `sio__` as a field.** Method bodies use `sio__` directly instead of calling `(get-sio)`.

3. **Constructors take `sio` as first parameter.** `(make-eve3-hash-map sio cnt root-off)`.

4. **No `#?` in method bodies for sio access.** The identical code runs on both platforms.

5. **`#?` is still used for:**
   - Platform-specific serialization (`serialize-element-bytes`, `deserialize-element-bytes`)
   - Exception types (`js/Error.` vs `IllegalStateException.`)
   - Hash functions (`hash-ordered-coll` vs `Murmur3/hashOrdered`)
   - MapEntry construction
   - `_meta` field (CLJ only — CLJS doesn't support metadata on custom types via DataView)

### 3.1 Map (`eve3/map.cljc`)

```clojure
(eve3/eve3-deftype ^{:type-id 0xED} EveHashMap [^:int32 cnt ^:int32 root-off]
  clojure.lang.Counted
  (count [_] #?(:cljs cnt :clj (int cnt)))

  clojure.lang.ILookup
  (valAt [_ k]
    (let [kb (serialize-key-bytes k)
          kh (portable-hash-bytes kb)]
      (hamt-get sio__ root-off kh kb 0)))
  (valAt [_ k not-found]
    (let [kb (serialize-key-bytes k)
          kh (portable-hash-bytes kb)]
      (or (hamt-get sio__ root-off kh kb 0) not-found)))

  ;; ... all protocols use sio__ directly, no (get-sio) calls ...

  ;; CLJ-only interfaces:
  #?@(:clj
      [clojure.lang.MapEquivalence

       clojure.lang.IPersistentMap
       (entryAt [this k] ...)

       java.lang.Iterable
       (iterator [this] (clojure.lang.SeqIterator. (.seq this)))

       java.lang.Object
       (toString [this] (pr-str this))
       (equals [this other] ...)
       (hashCode [this] ...)]))
```

### 3.2 Vec (`eve3/vec.cljc`)

Same pattern. Trie algorithms already take `sio` as first param. Method bodies use `sio__` directly.

### 3.3 Set (`eve3/set.cljc`)

Same pattern. HAMT algorithms shared with map (or independent set HAMT as in eve2).

### 3.4 List (`eve3/list.cljc`)

Simplest. Cons-cell linked list. All node operations already take `sio`.

### 3.5 Atom (`eve3/atom.cljc`)

Port after data structures are stable. The atom creates/manages the ISlabIO context and passes it to the data structures it constructs.

---

## Phase 4: Public API + Migration

### 4.1 `eve3/alpha.cljc`

Re-exports:
- `eve3/eve3-deftype` macro
- `eve3-hash-map`, `eve3-vec`, `eve3-hash-set`, `eve3-list` constructors
- `eve3-atom`

### 4.2 Cleanup

Once proven, `eve.alpha` can re-export from `eve3.*`.

---

## Protocol Mapping: Edge Cases

### Protocols that map to the same CLJ interface

In CLJ, `ICollection/-conj`, `IEmptyableCollection/-empty`, and `IEquiv/-equiv` all map to `clojure.lang.IPersistentCollection`. Eve3 avoids this problem entirely because CLJ names are canonical — if users write `clojure.lang.IPersistentCollection` with methods `cons`, `empty`, and `equiv`, there's no mapping collision.

### INext (CLJS-only)

On CLJ, `ISeq` covers `first`, `next`, `more`. On CLJS, `ISeq` has `-first` and `-rest`, while `INext` has `-next`. Since Eve3 writes CLJ names, users write `clojure.lang.ISeq` with `first`, `next`, `more`. The macro maps `first` → `-first`, `more` → `-rest` on CLJS, and emits `next` under a separate `INext` protocol on CLJS:

```clojure
;; In proto_map:
clojure.lang.ISeq
{:cljs-proto ISeq
 :methods {first {:cljs-name -first ...}
           more  {:cljs-name -rest  ...}
           ;; next is split out to INext on CLJS:
           next  {:cljs-name -next  :cljs-proto-override INext ...}}}
```

Or more simply: the macro detects `next` on `clojure.lang.ISeq` and emits it under `INext` on CLJS.

### IPrintWithWriter / toString

On CLJ, users write `Object/toString`. On CLJS, the macro maps to `IPrintWithWriter/-pr-writer` — but the method signature differs (`toString` returns String; `-pr-writer` takes `writer` and `opts`). The macro can:
- Drop CLJ's `toString` on CLJS and let the CLJS boilerplate generate a default `IPrintWithWriter`
- Or require users to provide CLJS-specific print via `#?` if custom printing is needed

For Eve3 data structures, custom printing is always platform-specific anyway (CLJ uses `defmethod print-method`), so this is handled in `#?@(:clj [...])` blocks.

### IReduce arity asymmetry

CLJS `IReduce` has two arities: `(-reduce [coll f])` and `(-reduce [coll f init])`. CLJ splits these: `clojure.lang.IReduce/reduce` (no init) and `clojure.lang.IReduceInit/reduce` (with init). The macro maps `clojure.lang.IReduceInit/reduce` → `IReduce/-reduce` (2-arg init version on CLJS). For the no-init arity, users add it via `#?@(:cljs [IReduce (-reduce [this f] ...)])`.

Alternatively, the proto_map can include a synthetic entry:

```clojure
;; Optional: map no-init reduce
clojure.lang.IReduce
{:cljs-proto IReduce
 :methods {reduce {:cljs-name -reduce :arities [[_ f]]}}}
```

---

## Comparison: What changes from Eve2 data structures

### Before (Eve2 — list.cljc pattern):

```clojure
;; Eve2: CLJS has (get-sio), CLJ auto-binds via macro
(defn- get-sio []
  #?(:cljs eve-alloc/cljs-sio
     :clj  alloc/*jvm-slab-ctx*))

;; In deftype methods:
IStack
(-peek [_]
  (when (pos? cnt)
    (read-node-value (get-sio) head-off)))
```

### After (Eve3):

```clojure
;; Eve3: sio is a field on the deftype, no get-sio needed
clojure.lang.IPersistentStack
(peek [_]
  (when (pos? cnt)
    (read-node-value sio__ head-off)))
```

### Before (Eve2 — constructor):

```clojure
;; Eve2: CLJS and CLJ constructors differ
(defn- make-eve2-list [sio cnt head-off]
  (let [hdr (write-list-header! sio cnt head-off)]
    #?(:cljs (EveList. hdr)
       :clj  (EveList. cnt head-off hdr sio nil))))
```

### After (Eve3):

```clojure
;; Eve3: same constructor on both platforms
(defn- make-eve3-list [sio cnt head-off]
  (let [hdr (write-list-header! sio cnt head-off)]
    (EveList. sio hdr)))
```

---

## Build Order

```
Phase 1 (Macro):
  1. eve3/proto_map.clj
  2. eve3/deftype/registry.clj
  3. eve3/deftype.clj
  4. test/eve3/deftype_test.cljc

Phase 2 (Bridge):
  5. eve3/alloc.cljc (thin wrapper, no dynamic vars)

Phase 3 (Data Structures):
  6. eve3/list.cljc (simplest — validate the macro)
  7. eve3/vec.cljc
  8. eve3/set.cljc
  9. eve3/map.cljc (biggest, do last)

Phase 4 (Integration):
  10. eve3/atom.cljc
  11. eve3/alpha.cljc
  12. Update eve.alpha to re-export
```

Each phase runs the full green baseline before proceeding.

---

## Risk Mitigation

1. **CLJS performance**: ISlabIO protocol dispatch adds ~20-50ns per field read vs Eve2's inline DataView. For data structure operations that do many field reads per call (e.g., HAMT traversal reads ~5 fields per level × ~6 levels = ~30 reads), this adds ~600ns-1.5µs per operation. This is acceptable for correctness and simplicity. If profiling shows it matters, hot paths can be optimized later with direct DataView calls behind `#?(:cljs ...)`.

2. **Macro simplicity**: Eve3's macro is fundamentally simpler than Eve2's. Eve2 has two completely different emission paths (DataView inlining vs ISlabIO calls). Eve3 has one emission path with a protocol name translation step. Less code, fewer bugs.

3. **sio threading**: Every function already takes `sio` as first param in Eve2's algorithm layer. The only change is removing `(get-sio)` calls from deftype method bodies and using `sio__` directly. This is mechanical.

4. **Correctness**: Eve1 and Eve2 tests remain as regression baselines. Eve3 tests run the same assertion suite.

---

## Open Decisions

| Question | Proposed Decision |
|---|---|
| Protocol mapping scope | Curated CLJ→CLJS mappings (~20 entries). Unmapped = verbatim on both platforms. |
| INext handling | `next` on `clojure.lang.ISeq` auto-splits to `INext` on CLJS |
| IPrintWithWriter | Users provide custom print via `#?` blocks or `defmethod print-method` (CLJ). Macro generates default print on both platforms. |
| IReduce arity split | Map `IReduceInit` → `IReduce` (init arity). No-init arity via `#?@(:cljs ...)` or additional mapping entry. |
| _meta support | CLJ-only via `#?@(:clj [clojure.lang.IObj ...])`. CLJS types don't carry metadata. |
| Constructor API | `(->TypeName sio & field-values)` — sio is always first param |
