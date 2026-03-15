# Implementation Plan: Columnar Data Structures for Eve

## Overview

Three composable layers — dataset (tabular), tensor (N-dimensional), and functional/argops (operations) — all atom-native, all backed by EveArray, all inheriting Eve's sharing semantics (SAB = cross-worker, mmap = cross-process).

## New Files

| File | Purpose |
|------|---------|
| `src/eve/dataset.cljc` | Dataset type: named columns of EveArrays, atom-storable |
| `src/eve/tensor.cljs` | NDBuffer type: shape/stride views over EveArray |
| `src/eve/dataset/functional.cljs` | Element-wise column ops (sum, mul, add, mean) |
| `src/eve/dataset/argops.cljs` | Index-space ops (argsort, argfilter, arggroup) |
| `test/eve/dataset_test.cljs` | Dataset + functional + argops tests |
| `test/eve/tensor_test.cljs` | Tensor tests |

## No files modified (except wiring)

These existing files get **small additions only** (no logic changes):

| File | Change |
|------|--------|
| `src/eve/deftype_proto/serialize.cljc` | Add 3 new constants: `FAST_TAG_EVE_DATASET 0x1D`, `EVE_DATASET_SLAB_TYPE_ID 0x1F`, `EVE_TENSOR_SLAB_TYPE_ID 0x20` |
| `test/eve/runner/eve_test_main.cljs` | Wire in `dataset-test` and `tensor-test` suites |

All serializer registrations happen in the new files themselves (same pattern as `eve.array` and `eve.obj`).

---

## Layer 1: eve.dataset

### Dataset type

A Dataset is a lightweight wrapper: `{schema, row-count, columns}` where columns is a map of keyword → EveArray. Each column is a separate EveArray (already slab-allocatable via type-id `0x1D`).

```clojure
(deftype EveDataset [schema    ;; {:col-name :dtype, ...}
                     row-count ;; int
                     columns   ;; {keyword -> EveArray}
                     _meta])
```

### Core API (eve.dataset)

```clojure
;; Construction
(dataset {:price (arr/eve-array :float64 [10.5 20.3])
          :qty   (arr/eve-array :int32 [100 200])})
(dataset-from-rows :float64 [{:a 1 :b 2} {:a 3 :b 4}])

;; Column access (zero-copy — returns the EveArray directly)
(column ds :price)        ;; => EveArray
(column-names ds)         ;; => [:price :qty]
(row-count ds)            ;; => 2

;; Schema
(dtypes ds)               ;; => {:price :float64, :qty :int32}

;; Structural operations (return new Dataset, structural sharing on unchanged columns)
(select-columns ds [:price])
(add-column ds :total some-eve-array)
(drop-column ds :qty)
(rename-columns ds {:price :cost})

;; Row operations (return new Dataset with new column arrays)
(filter-rows ds :price (fn [v] (> v 15.0)))
(sort-by-column ds :price)
(sort-by-column ds :price :desc)
(head ds 10)
(tail ds 10)
(slice ds 5 15)           ;; zero-copy view (sub-array offset+length)
```

### Slab serialization (atom-storable)

Block format for `EVE_DATASET_SLAB_TYPE_ID = 0x1F`:

```
[0x1F : u8]            type-id
[ncols : u8]           column count (max 255)
[pad : u16]            alignment padding
[row-count : u32]      number of rows
--- per column (repeated ncols times) ---
[name-len : u8]        column name length
[name : name-len bytes] column name (UTF-8)
[dtype : u8]           column dtype code (reuse array subtype codes)
[slab-offset : u32]    slab-qualified offset of the column's EveArray block
```

Registration pattern (in `eve.dataset` ns, CLJS branch):
```clojure
(ser/register-cljs-to-sab-builder!
  (fn [v] (instance? EveDataset v))
  (fn [ds] ...write manifest block, columns are already slab-allocated...))

(ser/register-header-constructor!
  ser/EVE_DATASET_SLAB_TYPE_ID
  (fn [sab blk-off] ...reconstruct Dataset from manifest + column offsets...))

(ser/register-header-disposer!
  ser/EVE_DATASET_SLAB_TYPE_ID
  (fn [blk-off] ...free manifest block; column blocks freed by their own disposers...))
```

### Atom integration

Datasets work in atoms naturally because:
1. `swap!` receives the deserialized Dataset (columns are live EveArray views into slabs)
2. The swap function operates on columns, producing new EveArrays for modified columns
3. The returned Dataset is serialized back: new manifest + new column blocks for changed columns, old column blocks for unchanged columns
4. Epoch GC collects old manifest and old column blocks

```clojure
(def a (eve/mmap-atom "/tmp/data" (dataset {:price (arr/eve-array :float64 [10.5])})))
(swap! a (fn [ds]
           (add-column ds :doubled
             (functional/mul (column ds :price) 2.0))))
```

---

## Layer 2: eve.tensor

### NDBuffer type

An NDBuffer is a view over any EveArray. It adds shape + strides metadata without copying data.

```clojure
(deftype EveNDBuffer [^EveArray data   ;; backing flat array
                      shape             ;; vector of ints [d0 d1 ... dn]
                      strides           ;; vector of ints [s0 s1 ... sn]
                      ^number offset    ;; element offset into data
                      ^number rank      ;; number of dimensions
                      _meta])
```

Element at indices `[i0, i1, ..., in]` maps to flat index: `offset + i0*s0 + i1*s1 + ... + in*sn`.

### Core API (eve.tensor)

```clojure
;; Construction
(from-array arr [3 4])              ;; wrap 12-elem array as 3x4 matrix
(zeros :float64 [3 4])             ;; allocate + fill
(ones :int32 [2 3 4])              ;; allocate + fill

;; Shape operations (zero-copy, return new view)
(reshape t [4 3])                   ;; new shape, same data
(transpose t)                       ;; reverse strides
(transpose t [1 0 2])               ;; permute axes
(slice t 0 1)                       ;; select row 1 → rank-1 view
(select t 1 [0 2])                  ;; select columns 0,2 (may copy if non-contiguous)

;; Element access
(mget t 1 2)                        ;; get element at [1,2]
(mset! t 1 2 42.0)                  ;; set element at [1,2]

;; Bulk operations
(emap + t1 t2)                      ;; lazy element-wise (returns new array)
(ereduce + 0 t)                     ;; reduce over all elements
(shape t)                           ;; => [3 4]
(dtype t)                           ;; => :float64
(contiguous? t)                     ;; true if strides match C-order

;; Materialization
(to-array t)                        ;; copy to flat EveArray (if not already contiguous)
(to-dataset t [:a :b :c :d])       ;; 2D tensor → Dataset, one column per last-dim
```

### Slab serialization

Block format for `EVE_TENSOR_SLAB_TYPE_ID = 0x20`:

```
[0x20 : u8]            type-id
[rank : u8]            number of dimensions
[pad : u16]            padding
[data-offset : u32]    slab-qualified offset of backing EveArray
[elem-offset : u32]    element offset into the array
--- per dimension (repeated rank times) ---
[shape-i : u32]        size of dimension i
[stride-i : u32]       stride of dimension i
```

Max tensor rank: 8 dimensions (fits in a 128-byte slab block: 12 + 8*8 = 76 bytes).

### Composition with Dataset

```clojure
;; Pull column, reshape into matrix
(def prices (column ds :price))
(def price-matrix (tensor/from-array prices [30 24]))  ;; 30 days × 24 hours

;; Transpose for time-series analysis
(def hourly (tensor/transpose price-matrix))  ;; 24 × 30

;; Compute mean per hour → back to dataset column
(def hourly-mean (tensor/reduce-axis + hourly 1))  ;; reduce over days
(def ds2 (add-column ds :hourly-mean (tensor/to-array hourly-mean)))
```

---

## Layer 3: eve.dataset.functional + eve.dataset.argops

### eve.dataset.functional

Element-wise operations on EveArrays. Works with both standalone arrays AND dataset columns (since columns ARE EveArrays).

```clojure
;; Arithmetic (return new EveArray)
(add col1 col2)          ;; element-wise addition
(sub col1 col2)
(mul col1 col2)          ;; or (mul col 2.0) for scalar
(div col1 col2)

;; Aggregations (return scalar)
(sum col)                ;; uses SIMD for int32
(mean col)
(min-val col)
(max-val col)
(variance col)

;; Comparison (return EveArray :uint8 of 0/1)
(gt col 15.0)            ;; element > scalar
(lt col1 col2)           ;; element-wise <
(eq col val)

;; Mapping
(emap f col)             ;; (f elem) → new array
(emap2 f col1 col2)      ;; (f a b) → new array
```

Implementation: loops over EveArray elements using `arr/aget` and `arr/aset!`. For `:int32` aggregations, delegates to existing SIMD ops (`arr/asum-simd`, `arr/amin-simd`, `arr/amax-simd`). For other dtypes, scalar loops.

### eve.dataset.argops

Index-space operations: return `EveArray :int32` of indices.

```clojure
(argsort col)              ;; indices that would sort the column
(argsort col :desc)        ;; descending
(argfilter col pred)       ;; indices where (pred elem) is true
(argmin col)               ;; index of minimum (scalar)
(argmax col)               ;; index of maximum (scalar)
(arggroup col)             ;; {value → EveArray of indices}
(take-indices col idx-arr) ;; gather: new array from indices
```

These compose with dataset operations:
```clojure
(let [idx (argops/argsort (column ds :price) :desc)]
  (dataset/reindex ds idx))  ;; reorder all columns by price descending
```

---

## Test Plan

### test/eve/dataset_test.cljs

Suite name: `"dataset"` — ~15-20 tests, ~80-120 assertions.

Tests:
1. Dataset construction from column map
2. Column access returns EveArray (identity, not copy)
3. select-columns / drop-column / add-column / rename
4. filter-rows with predicate
5. sort-by-column ascending/descending
6. head/tail/slice (zero-copy check: same SAB)
7. row-count, column-names, dtypes
8. Functional: sum, mean, min-val, max-val
9. Functional: add, mul, sub, div (array × array, array × scalar)
10. Functional: gt, lt, eq (return uint8 mask)
11. Argops: argsort, argfilter, argmin, argmax
12. Argops: take-indices + reindex
13. Dataset in atom: store, deref, swap!
14. Dataset in atom: structural sharing (unchanged columns keep same slab offset)
15. Composition: column → tensor → back to column

### test/eve/tensor_test.cljs

Suite name: `"tensor"` — ~12-15 tests, ~60-80 assertions.

Tests:
1. from-array + shape/strides
2. mget / mset! element access
3. reshape (zero-copy: same backing array)
4. transpose 2D (zero-copy)
5. slice (dimension reduction)
6. contiguous? check
7. to-array materialization
8. emap (element-wise binary op)
9. ereduce (full reduction)
10. zeros / ones constructors
11. Tensor in atom: store, deref
12. Composition: tensor → to-dataset

### Test runner wiring

Add to `eve_test_main.cljs`:
- `"dataset"` → `run-dataset!` (runs `eve.dataset-test`)
- `"tensor"` → `run-tensor!` (runs `eve.tensor-test`)
- Add both to `run-all!`

---

## Implementation Order

Within the single pass:

1. **Constants** — add 3 new constants to `serialize.cljc`
2. **eve.dataset.functional** — pure operations on EveArrays (no types needed)
3. **eve.dataset.argops** — pure index operations on EveArrays
4. **eve.dataset** — Dataset type + slab serialization + atom integration
5. **eve.tensor** — NDBuffer type + slab serialization
6. **test/eve/dataset_test.cljs** — comprehensive tests for dataset + functional + argops
7. **test/eve/tensor_test.cljs** — comprehensive tests for tensor
8. **Test runner wiring** — add suites to eve_test_main.cljs
9. **Compile + run all existing tests** — verify no regressions
10. **Run new tests** — verify new functionality

## Constraints

- No changes to `atom.cljc` or `alloc.cljc`
- No changes to existing type serialization
- Dataset and Tensor register themselves via the existing `register-*` hooks
- All new code in new files only (except 3 constants + test runner wiring)
