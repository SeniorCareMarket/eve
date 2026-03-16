# Plan: Direct-Array Fast Path for Columnar Operations

## Problem

Every columnar operation (`func/sum`, `func/mul`, `argops/argsort`, etc.) loops
over elements calling `(nth col i)` per element.

On JVM slab-backed `JvmEveArray`, each `nth` call:
1. Decodes class-idx + block-idx from slab-offset
2. Calls `-sio-read-bytes` → allocates a fresh `byte[]`
3. Wraps it in a `ByteBuffer`
4. Type-dispatches via `case`

Result: 100K double sum = 4.31ms via nth, vs 0.49ms via raw `double[]`.

The data IS already contiguous bytes in memory. We just never expose it.

## Design Principle

**One extraction, flat loop.** Every bulk operation should:
1. Extract the backing array/buffer ONCE (O(1))
2. Loop with `aget`/`aset` over the raw typed array (O(n), no alloc)
3. Wrap result as EveArray at the end (O(1))

## Step 1: Add `IBulkAccess` protocol to `data.cljc`

```clojure
(defprotocol IBulkAccess
  (-as-double-array [this] "Return double[] view, or nil if not float64.")
  (-as-int-array    [this] "Return int[] view, or nil if not int32.")
  (-as-byte-array   [this] "Return byte[] of raw element data."))
```

Implement on:
- `JvmHeapEveArray`: trivial — return `backing` field directly
- `JvmEveArray`: ONE call to `-sio-read-bytes` for the whole data region,
  then wrap in typed array. This is the key change — one bulk read replaces
  N per-element reads.

On CLJS `EveArray`: not needed — already has `typed-view` field exposed.

**Why byte[] for slab?** The slab data is contiguous starting at offset 8 of the
block. One `-sio-read-bytes sio slab-off 8 (* cnt elem-sz)` copies the whole
column into a `byte[]`, then `ByteBuffer.wrap(...).asDoubleBuffer()` gives typed
access with zero additional allocation.

## Step 2: Add fast-path bulk functions to `functional.cljc`

Replace the current per-element loops with typed-array fast paths.

Example — `sum`:
```clojure
;; CURRENT (slow):
(loop [i 0 acc 0.0]
  (if (< i n) (recur (inc i) (+ acc (double (nth col i)))) acc))

;; NEW (fast):
(if-let [^doubles da (-as-double-array col)]
  (let [n (alength da)]
    (loop [i 0 acc 0.0]
      (if (< i n) (recur (inc i) (+ acc (aget da i))) acc)))
  ;; fallback to nth for unknown types
  <existing code>)
```

Same pattern for: `sum`, `mean`, `min-val`, `max-val`, `add`, `sub`, `mul`,
`div`, `gt`, `lt`, `eq`, `emap`, `emap2`.

The key: `aget` on a primitive `double[]` is a single JVM instruction.
No boxing, no protocol dispatch, no bounds check (JVM does its own).

## Step 3: Add fast-path to `argops.cljc`

### `argsort`
Current: `(sort (fn [a b] (compare (nth col a) (nth col b))) idxs)` — nth per comparison.

New:
```clojure
(if-let [^doubles da (-as-double-array col)]
  ;; Extract to double[], create int[] of indices, sort with Arrays.sort-like approach
  (let [idx (int-array (range n))]
    ;; Java's dual-pivot quicksort via manual implementation or sort-by
    ;; Sort idx by comparing da[idx[i]]
    ...)
  <fallback>)
```

Actually simpler: extract to Clojure vector once, then sort. The point is
we do ONE bulk extraction (O(n)), not O(n log n) individual nth calls.

Even simpler fix: `argsort` can just do:
```clojure
(let [data-vec (if (satisfies? IBulkAccess col)
                 (vec (-as-double-array col))  ;; one bulk read
                 (mapv #(nth col %) (range n)))
      cmp (if (= direction :desc) ...)]
  (sort cmp (range n)))  ;; comparisons now hit a vector, not slab
```

### `argfilter`, `argmin`, `argmax`, `take-indices`
Same pattern: extract backing array once, loop with `aget`.

## Step 4: Add fast-path to `tensor.cljc` (JVM only)

Current JVM tensor ops (line 382):
```clojure
(dotimes [i n] (arr/aset! out-arr i (f (flat-get t i))))
```

`flat-get` calls `nth` on the backing EveArray. For contiguous tensors:
```clojure
(if-let [^doubles src (-as-double-array (-data t))]
  (let [^doubles dst (double-array n)
        eo (-elem-offset t)]
    (dotimes [i n]
      (aset dst i (double (f (aget src (+ eo i))))))
    (from-array (wrap-double-array dst) sh))
  <fallback>)
```

## Step 5: `eve-array` construction fast-path

Current `arr/eve-array` on JVM creates a `JvmHeapEveArray`. The `aset!` path
goes through `jvm-heap-aset!` which does bounds check + case dispatch per element.

For the output arrays created in functional/argops inner loops, we should
construct directly from a primitive array:

```clojure
(defn from-double-array [^doubles arr]
  (JvmHeapEveArray. (alength arr) FLOAT64_SUBTYPE arr nil))

(defn from-int-array [^ints arr]
  (JvmHeapEveArray. (alength arr) INT32_SUBTYPE arr nil))
```

This avoids N `aset!` calls when building output arrays.

## What NOT to change

- `EveArray.nth` / `JvmEveArray.nth` — keep as-is for random access
- CLJS paths — `typed-view` + `aget` is already the fast path there
  (CLJS tensor/functional already use `get-view-and-base` + `direct-read`)
- Slab serialization — writing arrays to slab stays as-is
- Map assoc / HAMT — irrelevant to columnar ops
- `mem.cljc` and `alloc.cljc` — no changes needed

## Expected Impact (based on profiling)

| Operation | Current | Expected | Why |
|---|---|---|---|
| sum 100K f64 | 4.31ms | ~0.5ms | aget loop vs slab nth |
| mul 100K f64 | 17.7ms | ~4ms | two aget loops + from-double-array |
| argsort 10K | 298ms | ~25ms | one bulk extract, sort against vec |
| Column Stats 10K | 20ms | ~2ms | all 6 ops use aget |
| Dataset Analytics 10K | 269ms | ~10ms | argsort dominates |
| Tensor emap 10K | 1206ms | ~3ms | contiguous aget + direct array out |

## File changes

| File | Change |
|---|---|
| `src/eve/deftype_proto/data.cljc` | Add `IBulkAccess` protocol |
| `src/eve/array.cljc` | Implement `IBulkAccess` on `JvmEveArray`, `JvmHeapEveArray`; add `from-double-array`, `from-int-array` |
| `src/eve/dataset/functional.cljc` | Add typed-array fast paths to all functions |
| `src/eve/dataset/argops.cljc` | Add bulk-extract fast paths |
| `src/eve/tensor.cljc` | Add JVM fast paths for `emap`, `ereduce`, `to-array` |

No changes to: `mem.cljc`, `alloc.cljc`, `map.cljc`, `atom.cljc`,
`serialize.cljc`, `shared_atom.cljs`.

## Verification

1. All existing tests must pass (green baseline)
2. Re-run `columnar_profile.clj` — expect numbers matching "Expected" column
3. Re-run `columnar_bench.cljc` — expect speedup ratios > 1.0x for all benchmarks
