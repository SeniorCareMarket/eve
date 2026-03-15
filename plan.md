# Implementation Plan: Columnar Data Structures for Eve

## Overview

Three composable layers building Arrow-compatible columnar data on Eve's
existing slab infrastructure. All `.cljc` (JVM + Node + Babashka + future
targets). All atom-native — datasets are values you `swap!` on. All columns
live inside existing slab classes 0–6, using Eve's existing CAS-gated mutation,
epoch GC, and coalescing allocator. No new memory management machinery.

### Design Principle: Eve is the Extensible Value Encoding

The serializer registration system (`register-cljs-to-sab-builder!`,
`register-header-constructor!`, `register-header-disposer!`) is the general
extensibility mechanism. Arrow columns are the first foreign format we bring
in, but the pattern works for any format: Parquet pages, FlatBuffers,
Protocol Buffers, CSV chunks, etc. Users define a type, register
builder/constructor/disposer, and their format lives in Eve's slabs natively
with zero-copy, cross-process sharing, and epoch GC — for free.

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                   User API Layer                      │
│                                                       │
│  eve.dataset (.cljc)        eve.tensor (.cljc)        │
│  tabular: named columns     N-dim views over columns  │
│  filter, sort, group-by     reshape, transpose, slice │
├──────────────────────────────────────────────────────┤
│  eve.dataset.functional (.cljc)                       │
│  eve.dataset.argops (.cljc)                           │
│  sum, mean, mul, add        argsort, argfilter        │
├──────────────────────────────────────────────────────┤
│         eve.column (.cljc)                            │
│         Arrow-compatible column type                  │
│         [validity-bitmap | data-buffer]               │
│         Supports: fixed-width numeric, strings        │
│         Lives in slab classes 0–6                     │
├──────────────────────────────────────────────────────┤
│         eve.array (existing) + ISlabIO (existing)     │
│         Slab allocator, CAS, epoch GC                 │
│         SAB (cross-worker) or mmap (cross-process)    │
└──────────────────────────────────────────────────────┘
```

---

## New Files

| File | Purpose |
|------|---------|
| `src/eve/column.cljc` | Arrow-compatible column: validity bitmap + typed data buffer |
| `src/eve/dataset.cljc` | Dataset: named columns, atom-storable, cross-platform |
| `src/eve/tensor.cljc` | NDBuffer: shape/stride views over columns/arrays |
| `src/eve/dataset/functional.cljc` | Element-wise column operations |
| `src/eve/dataset/argops.cljc` | Index-space operations |
| `test/eve/dataset_test.cljs` | Tests for dataset + column + functional + argops |
| `test/eve/tensor_test.cljs` | Tests for tensor |

## Modified Files (small additions only)

| File | Change |
|------|--------|
| `src/eve/deftype_proto/serialize.cljc` | 4 new constants (type-ids) |
| `test/eve/runner/eve_test_main.cljs` | Wire in new test suites |

---

## Layer 0: eve.column — Arrow-Compatible Column

This is the foundation. A column is a slab block with Arrow-compatible byte
layout: validity bitmap + data buffer. It uses ISlabIO for all reads/writes,
making it cross-platform by construction.

### Column types

**Fixed-width numeric** (Arrow equivalent: Int32, Float64, etc.):
```
Slab block layout (type-id 0x1F):
[0x1F : u8]            type-id
[subtype : u8]         dtype code (reuses array subtype codes 0x01–0x09)
[null-count : u16]     number of null values
[row-count : u32]      number of rows
[validity-bitmap...]   ceil(row-count / 8) bytes, 1=valid 0=null
[padding to 4-byte alignment]
[data...]              row-count × elem_size bytes
```

**Variable-length (strings)** (Arrow equivalent: Utf8):
```
Slab block layout (type-id 0x21):
[0x21 : u8]            type-id
[flags : u8]           0x01 = string
[null-count : u16]     number of null values
[row-count : u32]      number of rows
[validity-bitmap...]   ceil(row-count / 8) bytes
[padding to 4-byte alignment]
[offsets...]           (row-count + 1) × 4 bytes (int32, Arrow offset format)
[data...]              variable-length UTF-8 bytes
```

Arrow's offset format: string i occupies bytes `data[offsets[i]..offsets[i+1]]`.

### Where columns live in memory

- Small columns (< 1024 bytes total): slab classes 0–5 (fixed-size blocks)
- Large columns: slab class 6 (coalescing allocator)
  - CAS-locked allocation, coalescing on free, auto-growth
  - No fragmentation concerns — coalesc handles it

The allocator choice is automatic: `alloc/alloc-offset` picks the right slab
class based on total block size.

### ISlabIO access (cross-platform)

All column reads/writes go through ISlabIO:
```clojure
;; Read element i from a numeric column:
;; 1. Check validity: bit (i % 8) of byte at bitmap-offset + (i / 8)
;; 2. Read data: read typed value at data-offset + (i * elem-size)

;; Both operations use:
(-sio-read-u8 sio slab-offset field-off)     ;; bitmap byte
(-sio-read-i32 sio slab-offset field-off)    ;; int32 element
;; etc.
```

This means JVM, Node, Babashka, and any future target with IMemRegion
automatically get column read/write support.

### JVM support

```clojure
;; JVM reader (like jvm-eve-array-from-offset, jvm-obj-from-offset)
(defn jvm-column-from-offset [sio slab-off]
  ;; Read header, construct JvmColumn with sio reference
  ...)
```

### Arrow byte compatibility

The data region of a numeric column IS an Arrow array buffer. You can hand
the bytes (from data-offset onward) to any Arrow consumer. The validity
bitmap is Arrow's exact format (LSB bit-numbering, 1=valid). Zero conversion
needed for numeric columns.

For interop:
```clojure
;; Future: read Arrow IPC file → Eve columns (zero-copy if mmap'd)
;; Future: write Eve columns → Arrow IPC file (zero-copy for numeric)
```

---

## Layer 1: eve.dataset — Tabular Data

### Dataset type

A Dataset is a manifest pointing to columns. Each column is an `EveColumn`
slab block.

```clojure
;; CLJS
(deftype EveDataset [schema     ;; {col-name dtype-kw, ...}
                     row-count  ;; int
                     columns    ;; {keyword → EveColumn}
                     _meta])

;; JVM
(deftype JvmDataset [schema     ;; {col-name dtype-kw, ...}
                     row-count  ;; long
                     slab-off   ;; slab-qualified offset of manifest
                     sio        ;; ISlabIO
                     _meta])
```

### Slab manifest block

```
Type-id 0x20:
[0x20 : u8]            type-id
[ncols : u8]           column count (max 255)
[pad : u16]            alignment padding
[row-count : u32]      number of rows
--- per column (repeated ncols times) ---
[name-len : u8]        column name length
[name : name-len bytes] UTF-8 column name
[dtype : u8]           column dtype code
[col-slab-offset : u32] slab-qualified offset of the column's slab block
```

### Core API (eve.dataset)

```clojure
;; Construction
(dataset {:price  (column :float64 [10.5 20.3 15.7])
          :qty    (column :int32 [100 200 150])
          :symbol (column :string ["AAPL" "GOOG" "MSFT"])})

;; Column access (zero-copy — returns the EveColumn directly)
(column ds :price)         ;; → EveColumn
(column-names ds)          ;; → [:price :qty :symbol]
(row-count ds)             ;; → 3
(dtypes ds)                ;; → {:price :float64 :qty :int32 :symbol :string}

;; Structural ops (return new Dataset, structural sharing on unchanged columns)
(select-columns ds [:price :qty])
(add-column ds :total some-column)
(drop-column ds :symbol)
(rename-columns ds {:price :cost})

;; Row ops (return new Dataset with new column slab blocks)
(filter-rows ds :price #(> % 15.0))
(sort-by-column ds :price :desc)
(head ds 10)
(tail ds 10)
(slice ds 5 15)
```

### Atom integration

```clojure
;; Datasets are atom values — store, deref, swap!
(def a (eve/mmap-atom "/tmp/data"
         (dataset {:price (column :float64 [10.5 20.3])})))

;; swap! operates directly on columns inside the transaction
(swap! a (fn [ds]
           (let [prices (column ds :price)
                 doubled (functional/mul prices 2.0)]
             (add-column ds :doubled doubled))))

;; Structural sharing: unchanged columns keep their slab offsets
;; Epoch GC collects old manifest + replaced column blocks
```

### Null handling

```clojure
;; Create column with nulls
(column :int32 [1 nil 3 nil 5])
;; → validity bitmap: 0b10101 = [valid, null, valid, null, valid]
;; → data buffer: [1, 0, 3, 0, 5] (nulls are zero-filled)

;; Check null
(null? col 1)              ;; → true
(valid? col 0)             ;; → true
(null-count col)           ;; → 2
```

---

## Layer 2: eve.tensor — N-Dimensional Views

### NDBuffer type (.cljc)

A view over any flat typed buffer (EveColumn data region or EveArray).
Shape + strides metadata, no data copy.

```clojure
;; CLJS
(deftype EveNDBuffer [data       ;; backing buffer (EveColumn or EveArray)
                      shape      ;; vector [d0 d1 ... dn]
                      strides    ;; vector [s0 s1 ... sn]
                      offset     ;; element offset into data
                      rank       ;; number of dimensions
                      _meta])

;; JVM: same pattern with sio-based access
(deftype JvmNDBuffer [slab-off   ;; slab-qualified offset of tensor metadata block
                      sio        ;; ISlabIO
                      _meta])
```

Element `[i0, i1, ..., in]` → flat index `offset + i0*s0 + i1*s1 + ... + in*sn`.

### Core API (eve.tensor)

```clojure
;; Construction
(from-column col [3 4])            ;; wrap column data as 3×4 matrix
(zeros :float64 [3 4])            ;; allocate + fill
(ones :int32 [2 3 4])             ;; allocate + fill

;; Shape ops (zero-copy — same backing buffer)
(reshape t [4 3])                  ;; new shape, same data
(transpose t)                      ;; reverse strides
(transpose t [1 0 2])              ;; permute axes
(slice t 0 1)                      ;; select row 1 → rank-1 view
(select t 1 [0 2])                 ;; columns 0,2 (may copy if non-contiguous)

;; Element access
(mget t 1 2)                       ;; get element at [1,2]
(mset! t 1 2 42.0)                 ;; set element at [1,2]

;; Bulk
(emap + t1 t2)                     ;; element-wise (returns new buffer)
(ereduce + 0 t)                    ;; reduce all elements
(shape t)                          ;; → [3 4]
(dtype t)                          ;; → :float64
(contiguous? t)                    ;; true if C-order

;; Materialization
(to-column t)                      ;; flatten to EveColumn
(to-dataset t [:a :b :c :d])      ;; 2D → Dataset, one column per last-dim
```

### Slab metadata block

```
Type-id 0x22:
[0x22 : u8]            type-id
[rank : u8]            dimensions (max 8)
[dtype : u8]           element type
[pad : u8]             padding
[data-slab-offset : u32]  slab offset of backing column/array
[elem-offset : u32]    element offset into data
--- per dimension (rank times) ---
[shape-i : u32]        size of dimension i
[stride-i : u32]       stride of dimension i
```

Max block: 12 + 8×8 = 76 bytes → slab class 2 (128B blocks).

### Composition

```clojure
;; Pull column from dataset, reshape into matrix
(def prices (column ds :price))
(def mat (tensor/from-column prices [30 24]))  ;; 30 days × 24 hours
(def hourly (tensor/transpose mat))            ;; zero-copy: 24 × 30

;; Reduce back to a column, add to dataset
(def hourly-sum (tensor/reduce-axis + hourly 1))
(swap! a #(add-column % :hourly-sum (tensor/to-column hourly-sum)))
```

---

## Layer 3: eve.dataset.functional + eve.dataset.argops

### eve.dataset.functional (.cljc)

Element-wise operations on columns. Works on both EveColumn and dataset
columns (since columns ARE EveColumns).

```clojure
;; Arithmetic (return new EveColumn, null-propagating)
(add col1 col2)           ;; element-wise, null if either null
(sub col1 col2)
(mul col1 col2)           ;; or (mul col 2.0) for scalar broadcast
(div col1 col2)

;; Aggregations (return scalar, skip nulls)
(sum col)                 ;; uses SIMD for int32 when no nulls
(mean col)
(min-val col)
(max-val col)
(variance col)

;; Comparison (return EveColumn :uint8 of 0/1, null → 0)
(gt col 15.0)
(lt col1 col2)
(eq col val)

;; Mapping
(emap f col)              ;; (f elem) → new column
(emap2 f col1 col2)       ;; (f a b) → new column
```

Null propagation: if validity bitmap says null, result is null. Aggregations
skip nulls by default (Arrow semantics).

JVM implementation: same logic via ISlabIO reads.

### eve.dataset.argops (.cljc)

Index-space operations. Return int32 columns of indices.

```clojure
(argsort col)             ;; indices that would sort
(argsort col :desc)       ;; descending
(argfilter col pred)      ;; indices where (pred elem) is true
(argmin col)              ;; index of minimum (scalar)
(argmax col)              ;; index of maximum (scalar)
(arggroup col)            ;; {value → int32 column of indices}
(take-indices col idx)    ;; gather: new column from indices
```

Compose with dataset:
```clojure
(let [idx (argops/argsort (column ds :price) :desc)]
  (dataset/reindex ds idx))  ;; reorder ALL columns by price desc
```

---

## Type-ID Assignments

New constants in `serialize.cljc`:

```clojure
(def ^:const EVE_COLUMN_SLAB_TYPE_ID 0x1F)       ;; numeric column
(def ^:const EVE_DATASET_SLAB_TYPE_ID 0x20)       ;; dataset manifest
(def ^:const EVE_VARLEN_COLUMN_SLAB_TYPE_ID 0x21) ;; string/binary column
(def ^:const EVE_TENSOR_SLAB_TYPE_ID 0x22)        ;; tensor metadata
```

---

## Cross-Platform Strategy

Everything is `.cljc`. Platform-specific dispatch follows the existing pattern:

```clojure
#?(:cljs
   (do
     (deftype EveColumn [...] ...)
     (deftype EveDataset [...] ...)
     ;; register serializers
     (ser/register-cljs-to-sab-builder! ...)
     (ser/register-header-constructor! ...)
     (ser/register-header-disposer! ...)))

#?(:clj
   (do
     (deftype JvmColumn [...] ...)
     (deftype JvmDataset [...] ...)
     ;; JVM constructors from slab offset (like jvm-eve-array-from-offset)
     (defn jvm-column-from-offset [sio slab-off] ...)
     (defn jvm-dataset-from-offset [sio slab-off] ...)))
```

All field access goes through ISlabIO on JVM, DataView on CLJS — same bytes,
same layout, same semantics. A dataset written by Node is readable by JVM
and vice versa.

---

## Test Plan

### test/eve/dataset_test.cljs (~20 tests, ~120 assertions)

Suite name: `"dataset"`

1. Column construction: numeric types, with nulls, from collection
2. Column element access + null checking
3. String column construction and access
4. Column validity bitmap correctness
5. Dataset construction from column map
6. Column access returns EveColumn (identity, not copy)
7. select-columns / drop-column / add-column / rename
8. filter-rows with predicate
9. sort-by-column ascending/descending
10. head / tail / slice
11. Functional: sum, mean, min-val, max-val (with null skipping)
12. Functional: add, mul, sub, div (column×column, column×scalar)
13. Functional: gt, lt, eq (return uint8 mask)
14. Argops: argsort, argfilter, argmin, argmax
15. Argops: take-indices + reindex
16. Dataset in atom: store, deref, swap!
17. Dataset structural sharing (unchanged columns keep slab offset)
18. Null propagation through operations
19. String column in dataset
20. Composition: column → tensor → back to column

### test/eve/tensor_test.cljs (~15 tests, ~80 assertions)

Suite name: `"tensor"`

1. from-column + shape/strides
2. mget / mset! element access
3. reshape (zero-copy: same backing data)
4. transpose 2D (zero-copy)
5. transpose 3D with axis permutation
6. slice (dimension reduction)
7. contiguous? check
8. to-column materialization
9. emap (element-wise binary op)
10. ereduce (full reduction)
11. zeros / ones constructors
12. Tensor in atom: store, deref
13. Composition: tensor → to-dataset
14. Non-contiguous select
15. Scalar broadcast in emap

---

## Implementation Order

1. **Constants** — 4 new type-ids in `serialize.cljc`
2. **eve.column** — Arrow-compatible column type (CLJS + JVM)
3. **eve.dataset.functional** — operations on columns (null-aware)
4. **eve.dataset.argops** — index-space operations on columns
5. **eve.dataset** — Dataset type + manifest serialization + atom integration
6. **eve.tensor** — NDBuffer type + metadata serialization
7. **test/eve/dataset_test.cljs** — column + dataset + functional + argops
8. **test/eve/tensor_test.cljs** — tensor
9. **Test runner wiring** — add suites to eve_test_main.cljs
10. **Compile + run ALL existing tests** — verify zero regressions
11. **Run new tests** — verify new functionality

---

## Constraints

- **No changes to `atom.cljc`, `alloc.cljc`, or `coalesc.cljc`**
- No changes to existing type serialization logic
- Column/Dataset/Tensor register themselves via existing `register-*` hooks
- All column data lives in existing slab classes 0–6
- All I/O through ISlabIO (cross-platform by construction)
- All new code in new files (except 4 constants + test runner wiring)

## Extensibility for Future Formats

The same pattern used for Arrow columns works for any foreign format:

1. Define a type with slab-backed storage
2. Register builder (CLJS value → slab block)
3. Register constructor (slab block → type instance)
4. Register disposer (free slab blocks)

The type lives in Eve's atoms, participates in swap!/deref, gets epoch GC,
and shares cross-process via mmap. Future candidates: Parquet pages,
FlatBuffer tables, Protocol Buffer messages, CSV chunks.
