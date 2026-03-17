# Fix EveArray Performance Regressions in Unified Slab-Backed Implementation

## Context

Commit `cf5b7e8` (on `KmdoW`) rebuilt EveArray using `eve/deftype` for unified
CLJS+JVM slab-backed arrays. This eliminated SAB-specific code but introduced
severe CLJS performance regressions AND correctness bugs.

The original agent's regression analysis (Regressions 1–8) is accurate.
This revised plan addresses those regressions plus two additional bugs found
during review:
- **Bug A**: CLJS deftype has only `offset__`, but free functions reference
  nonexistent `sio__` field (macro emits `(deftype EveArray [offset__])` for CLJS)
- **Bug B**: `wait!` calls `(resolve-element-region arr idx)` with 2 args,
  but the function requires 3 (arr, idx, subtype)

## Approach: CLJS Manual Deftype + Cached Fields; JVM Unchanged

The `eve/deftype` macro on CLJS emits `(deftype T [offset__])` — a single field.
There is no mechanism for extra cached CLJS fields. Rather than modifying the
macro, we use a manual `deftype` on CLJS (inside `#?(:cljs ...)`) that:

1. Keeps `offset__` for serialization/ISlabIO compatibility
2. Caches all performance-critical values as fields
3. Works with **both** SAB-backed and mmap-backed slab memory

On JVM, `JvmEveArray` (with `sio__` and `slab-off__`) is already correct and
not regressed. No JVM changes needed.

### Key Insight: TypedArray Views Work for Both SAB and mmap

- SAB slab: buffer is `SharedArrayBuffer` → TypedArray views + `Atomics.*` (fast, thread-safe)
- mmap slab: buffer is `ArrayBuffer` (from Node.js `Buffer`) → TypedArray views + `aget`/`aset` (fast, no Atomics)
- The `atomic?` flag encodes this: `(and (subtype->atomic? subtype) (instance? js/SharedArrayBuffer buf))`
- For atomic ops on mmap, route through `IMemRegion` (native addon provides `cas32`, `load32`, etc.)

## CLJS EveArray Deftype (Step 1)

```clojure
#?(:cljs
(deftype EveArray [^number offset__        ;; slab-qualified offset (serialization key)
                   ^number length          ;; cached element count (immutable)
                   ^number subtype-code    ;; cached subtype byte
                   ^number elem-shift      ;; cached log2(elem-size)
                   ^boolean atomic?        ;; SAB + integer type → use Atomics
                   ^js typed-view          ;; cached TypedArray over slab buffer
                   ^number data-elem-base  ;; (>>> data-byte-offset elem-shift)
                   ^js region              ;; cached IMemRegion (for atomic ops)
                   ^number data-byte-off   ;; absolute byte offset of data start
                   ^:mutable __hash
                   ^IPersistentMap _meta]
  ;; ... protocols ...
))
```

### Construction (alloc-eve-region + make-eve-array)

At construction time, resolve all cached fields once:

```clojure
(let [slab-off  (alloc/alloc-offset total-size)
      class-idx (alloc/decode-class-idx slab-off)
      inst      (wasm/get-slab-instance class-idx)
      buf       (.-buffer (:u8 inst))      ;; SAB or ArrayBuffer
      region    (:region inst)              ;; IMemRegion
      byte-base (alloc/slab-offset->byte-offset slab-off)
      data-off  (+ byte-base HEADER_SIZE)
      ;; Write header via alloc fns (stateless on CLJS, no sio__ needed)
      _ (alloc/write-u8!  slab-off 0 EveArray-type-id)
      _ (alloc/write-u8!  slab-off 1 subtype)
      _ (alloc/write-u16! slab-off 2 0)
      _ (alloc/write-i32! slab-off 4 n)
      ;; Cached fields
      es        (subtype->elem-shift subtype)
      atomic?   (and (subtype->atomic? subtype)
                     (instance? js/SharedArrayBuffer buf))
      view      (make-typed-view buf subtype)
      base-idx  (unsigned-bit-shift-right data-off es)]
  ;; Fill via TypedArray (fast)
  (if atomic?
    (dotimes [i n] (js/Atomics.store view (+ base-idx i) fill-val))
    (dotimes [i n] (clojure.core/aset view (+ base-idx i) fill-val)))
  (EveArray. slab-off n subtype es atomic? view base-idx region data-off nil nil))
```

## Hot-Path Restorations (Steps 2–8)

### Step 2: -nth (IIndexed)

```clojure
(-nth [_ n]
  (if (and (>= n 0) (< n length))
    (let [idx (+ data-elem-base n)]
      (if atomic?
        (js/Atomics.load typed-view idx)
        (clojure.core/aget typed-view idx)))
    (throw (js/Error. (str "Index out of bounds: " n " for length " length)))))
```

~3 ops vs ~20 in the regressed version.

### Step 3: aget / aset!

Use cached `typed-view`, `data-elem-base`, `atomic?`, `length` directly.
No protocol dispatch, no slab decode, no DataView.

### Step 4: Atomic ops (cas!, add!, sub!, exchange!, band!, bor!, bxor!)

Branch on backing store:

- **SAB + int32**: Direct `Atomics.compareExchange` / `Atomics.add` / etc. on `typed-view`.
  One field read + one Atomics call. Done.
- **SAB + sub-word integer**: CAS loop on containing Int32 word using `Atomics.compareExchange`
  on an Int32Array view (obtained from region or created from buffer).
- **mmap (any integer type)**: Route through `IMemRegion` protocol
  (`mem/-cas-i32!`, `mem/-add-i32!`, etc.) which uses native addon.
  Compute byte offset from cached `data-byte-off` + `idx * elem-size`.

For SAB + int32 (the dominant case), this is 1 Atomics call vs
~10+ ops (3 slab decodes + protocol dispatch + vector allocation).

### Step 5: wait! / notify! (fix arity bug)

Fix: pass subtype arg to resolve-element-region, and branch on backing store:
- SAB: direct `Atomics.wait` / `Atomics.notify` on cached Int32Array
- mmap: `mem/-wait-i32!` / `mem/-notify-i32!` through IMemRegion

### Step 6: require-atomic! / require-int32! / bounds-check!

Use cached `atomic?`, `subtype-code`, `length` fields.
Zero memory reads. Zero slab decodes.

### Step 7: reduce / hash / equiv / print

All use cached fields. No per-element `-sio-read-u8` for subtype.
No per-element DataView reads.

### Step 8: get-typed-view / get-sab / get-offset / get-int32-view

Use cached fields:
- `get-typed-view`: `.subarray typed-view data-elem-base (+ data-elem-base length)`
- `get-sab`: `(.-buffer typed-view)` — the buffer backing the cached view
- `get-offset`: `data-byte-off`

No re-decode, no new TypedArray allocation.

### Step 9: Functional ops (areduce, amap, amap!, afill!, acopy!)

Use cached `length` and call fast `aget`/`aset!`.

### Step 10: SIMD ops

Use cached `data-byte-off` for byte offset calculations.
Use cached `length` instead of slab decode.
Use cached `subtype-code` for `require-int32!`.

### Step 11: Serialization / Deserialization

- `ISabStorable.-sab-encode`: use `offset__` (slab-qualified offset)
- `ISabStorable.-sab-dispose`: `(alloc/free! offset__)`
- `IDirectSerialize.-direct-serialize`: return `offset__`
- `IEveRoot.-root-header-off`: return `offset__`
- Header constructor (slab block → EveArray): resolve cached fields from slab-off
- SAB type constructor (FAST_TAG_EVE_ARRAY): resolve cached fields from SAB block offset
- CLJS-to-SAB builder (EveArray → slab block): use cached `subtype-code`, `length`, `elem-shift`,
  get source bytes via `(js/Uint8Array. (.-buffer typed-view) data-byte-off byte-len)`

### Step 12: from-typed-array

Allocate slab block, write bytes via alloc functions, resolve cached fields.

## JVM Path

**No changes needed.** The `JvmEveArray` on KmdoW uses `ISlabIO` correctly:
- `sio__` is the JVM slab context (bound via `*jvm-slab-ctx*`)
- `slab-off__` is the slab-qualified offset
- Field access via `-sio-read-*` is fast enough on JVM (JIT-compiled virtual dispatch)

Keep the existing `eve/deftype EveArray [^:int32 cnt]` for JVM, or use the manual
`JvmEveArray` deftype — whichever is already working on KmdoW.

Wait, the KmdoW `eve/deftype` approach has a naming mismatch: JVM field is `slab-off__`
but shared free functions reference `offset__`. Fix: make free functions
platform-specific via `#?(:cljs ... :clj ...)` since the CLJS manual deftype and
JVM deftype have different field names and different fast-path strategies.

## Files to Modify

- `src/eve/array.cljc` — Main rewrite (CLJS manual deftype + all free functions)

## Files to Merge from KmdoW (infrastructure, not array)

All other KmdoW changes are infrastructure that the unified array depends on:
- `src/eve/atom.cljc` — SAB domain support
- `src/eve/mem.cljc` — i64 emulation, JsSabRegion, NodeMmapRegion
- `src/eve/deftype_proto/alloc.cljc` — ISlabIO, CljsSlabIO, SAB coalescence
- `src/eve/deftype_proto/data.cljc` — new protocols/constants
- `src/eve/deftype_proto/serialize.cljc` — typed-array resolver
- `src/eve/deftype_proto.clj` — slab macro (JVM path)
- `src/eve/deftype.clj` — SAB macro updates
- Other files (map.cljc, vec.cljc, set.cljc, list.cljc, etc.)

## Verification

```bash
npx shadow-cljs compile eve-test

# Core array tests
node target/eve-test/all.js typed-array 2>&1 | tee /tmp/typed-array-test.txt

# Slab/mmap tests
node target/eve-test/all.js slab 2>&1 | tee /tmp/slab-test.txt
node target/eve-test/all.js mmap 2>&1 | tee /tmp/mmap-test.txt
node target/eve-test/all.js mmap-slab 2>&1 | tee /tmp/mmap-slab-test.txt
node target/eve-test/all.js mmap-atom 2>&1 | tee /tmp/mmap-atom-test.txt
node target/eve-test/all.js mmap-atom-e2e 2>&1 | tee /tmp/mmap-atom-e2e-test.txt

# Broader tests
node target/eve-test/all.js batch2 2>&1 | tee /tmp/batch2-test.txt
node target/eve-test/all.js batch3 2>&1 | tee /tmp/batch3-test.txt
node target/eve-test/all.js batch4 2>&1 | tee /tmp/batch4-test.txt
node target/eve-test/all.js obj 2>&1 | tee /tmp/obj-test.txt
node target/eve-test/all.js epoch-gc 2>&1 | tee /tmp/epoch-gc-test.txt
node target/eve-test/all.js int-map 2>&1 | tee /tmp/int-map-test.txt
node target/eve-test/all.js rb-tree 2>&1 | tee /tmp/rb-tree-test.txt
node target/eve-test/all.js mem 2>&1 | tee /tmp/mem-test.txt

# JVM tests
clojure -M:jvm-test 2>&1 | tee /tmp/jvm-test.txt

# Full green baseline
node target/eve-test/all.js all 2>&1 | tee /tmp/all-test.txt
```
