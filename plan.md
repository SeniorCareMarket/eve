# Restore EveArray Performance While Preserving eve/deftype Unification

## Situation

The KmdoW branch successfully unified CLJS and JVM EveArray under a single
`eve/deftype EveArray [^:int32 cnt]` definition. This was the culmination of
a multi-phase effort to eliminate separate `EveArray` (CLJS, 11 fields) and
`JvmEveArray` (JVM, 5 fields) types.

The unification is correct and desirable. But it regressed performance because
`eve/deftype` emits only `[sio__ offset__]` — every field access goes through
ISlabIO reads from the slab on every call.

### What was fast before (CLJS)

```clojure
(deftype EveArray [sab block-start offset length descriptor-idx
                   subtype-code elem-shift atomic? typed-view __hash _meta]
  IIndexed
  (-nth [this n]
    ;; One aget — all values are cached JS fields
    (let [idx (+ (>>> offset elem-shift) n)]
      (if atomic? (Atomics.load typed-view idx) (aget typed-view idx)))))
```

### What was fast before (JVM)

```clojure
(deftype JvmEveArray [^long cnt ^long slab-off ^long subtype-code sio _hash]
  clojure.lang.Indexed
  (nth [this i]
    ;; subtype-code is a cached field — one case dispatch + one ISlabIO read
    (let [es (subtype->elem-size subtype-code)
          raw (-sio-read-bytes sio slab-off (+ 8 (* i es)) es)
          bb (ByteBuffer/wrap raw)]
      (case (int subtype-code) ...))))
```

### What's slow now (unified)

```clojure
(eve/deftype EveArray [^:int32 cnt]
  clojure.lang.Indexed
  (nth [this n]
    ;; Must read subtype from slab EVERY call, then dispatch through read-element
    (read-element sio__ offset__ n (-sio-read-u8 sio__ offset__ 1))))
```

Every `nth`:
1. Reads subtype byte from slab via ISlabIO (CLJS: protocol dispatch + DataView)
2. Inside `read-element`: computes elem-shift, computes field offset
3. Dispatches on subtype via `case`
4. For float types: allocates temp byte array + ByteBuffer/DataView per element
5. On CLJS: lost the direct `typed-view` access entirely — no more `aget`

## Strategy

**Don't fight the macro. Work alongside it.**

The `eve/deftype` macro owns the type definition and field layout. We don't
modify the macro. Instead, we cache performance-critical derived values in
a mutable wrapper or protocol extension that's computed once at construction
time.

### Approach: Platform-specific constructor wrapper with cached fields

On CLJS, after constructing the `eve/deftype EveArray`, we attach cached
performance fields via a closure or JS object stored in a `^:mutable` slot
(or, since the macro doesn't support extra mutable fields, via `goog.object/set`
on the instance, or a WeakMap, or a wrapper type).

Actually, the cleanest approach: **extend the `eve/deftype` macro** to support
an optional `^:cached` field annotation that adds extra non-slab fields to the
generated deftype. These fields are not stored in the slab — they're regular
JS/JVM fields computed at construction time.

But that's macro surgery. The simpler, less invasive approach:

**Add cached fields directly in array.cljc's protocol implementations by
reading once and closing over the values.** But since `eve/deftype` generates
the type with fixed fields `[sio__ offset__]`, we can't add fields to the type
itself without changing the macro.

### Chosen approach: Extend the macro to support `^:cached` fields

Add support for fields annotated with `^:cached` in the `eve/deftype` macro.
These fields:
- Are added to the generated deftype's field list (after `sio__` and `offset__`)
- Are NOT stored in the slab
- Are passed as constructor arguments
- Are available in method bodies like regular fields

This is a small, additive change to the macro. It doesn't affect existing types
(map, vec, set, list) that don't use `^:cached`.

For EveArray:
```clojure
(eve/deftype EveArray [^:int32 cnt
                       ^:cached subtype     ;; read once at construction
                       ^:cached elem-shift  ;; computed once
                       ^:cached atomic?     ;; computed once
                       #?@(:cljs [^:cached typed-view])]  ;; CLJS only
  ...)
```

## Implementation Steps

### Step 1: Merge KmdoW into our branch

Cherry-pick or merge the KmdoW commits (cf5b7e8, 1dc4821) that contain
the unified EveArray and supporting infrastructure.

### Step 2: Add `^:cached` field support to `eve/deftype` macro

File: `src/eve/deftype_proto/macros.clj`

In `parse-field`: detect `^:cached` metadata and mark the field as cached.

In `compute-layout`: skip cached fields (they don't consume slab bytes).

In `emit-cljs` and `emit-clj`: include cached fields in the deftype field
list after `sio__` and `offset__`.

In `field-bindings` (the let-binding generation for method bodies): skip
cached fields — they're already available as direct type fields.

In `transform-method-body`: allow cached field symbols to pass through
without rewriting to ISlabIO reads.

### Step 3: Rewrite EveArray to use `^:cached` fields

File: `src/eve/array.cljc`

Change the deftype to:
```clojure
(eve/deftype EveArray [^:int32 cnt
                       ^:cached subtype
                       ^:cached elem-shift
                       ^:cached atomic?
                       ^:cached ^:mutable __hash
                       #?@(:cljs [^:cached typed-view])]
  ...)
```

Rewrite `nth` (CLJS) to use cached `typed-view`:
```clojure
(-nth [this n]
  (if (and (>= n 0) (< n cnt))
    (let [idx (+ (unsigned-bit-shift-right data-byte-offset elem-shift) n)]
      (if atomic?
        (js/Atomics.load typed-view idx)
        (clojure.core/aget typed-view idx)))
    (throw ...)))
```

Rewrite `nth` (JVM) to use cached subtype:
```clojure
(nth [this i]
  (if (and (>= i 0) (< i cnt))
    (let [es (bit-shift-left 1 elem-shift)
          raw (-sio-read-bytes sio__ offset__ (+ 8 (* i es)) es)
          bb (doto (ByteBuffer/wrap raw) (.order LE))]
      (case (int subtype)
        (1 3) (bit-and ...) ...))
    (throw ...)))
```

Update constructor functions (`eve-array`, `eve-array-from-offset`) to
compute and pass cached values.

### Step 4: Restore CLJS-specific fast paths

- `aget`/`aset!`: Use `typed-view` directly instead of going through `read-element`
- `cas!`, `add!`, `sub!`, etc.: Use `typed-view` + `Atomics` directly
- `afill-simd!`, `asum-simd`: Use `typed-view` for WASM SIMD calls
- `reduce`: Use cached `typed-view` in tight loop

### Step 5: Restore JVM-specific fast paths

- `IBulkAccess`: Use cached `subtype` instead of reading from slab each time
- `nth`: Use cached `subtype` and `elem-shift` — one fewer ISlabIO call per access

### Step 6: Verify all tests pass

Run the full green baseline from CLAUDE.md. The unification is preserved —
same type on both platforms — but hot paths are fast again.

## Files Changed

| File | Change |
|------|--------|
| `src/eve/deftype_proto/macros.clj` | Add `^:cached` field support |
| `src/eve/array.cljc` | Use `^:cached` fields, restore fast paths |
| `src/eve/deftype_proto/macros/registry.clj` | Handle cached in `parse-field` |

No changes to: `atom.cljc`, `mem.cljc`, `alloc.cljc`, `serialize.cljc`,
`map.cljc`, `vec.cljc`, `set.cljc`, `list.cljc`.

## What this does NOT do

- Does not revert the unification — `EveArray` stays as one `eve/deftype`
- Does not introduce a separate CLJS type — same type definition, both platforms
- Does not change the slab layout — cached fields are JS/JVM-only, not serialized
- Does not change any other Eve data structure
