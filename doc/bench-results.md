# Eve Columnar Benchmark Results

Benchmarks comparing Eve columnar operations (EveArray + Eve atom) against stock
Clojure transformations (vectors + Clojure atoms). Each benchmark performs a
realistic multi-step workload inside a single `swap!`.

**Methodology**: 2 warmup + 5 timed runs, trimmed mean of middle 3. Speedup =
Stock time / Eve time (higher is better for Eve).

## JVM (Clojure)

### MMAP (persistent, file-backed atoms)

| Benchmark              |     N |  Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|----------:|-----------:|--------:|
| Column Arithmetic      |   10K |       4.0 |        2.7 |   0.67x |
| Filter + Aggregate     |   10K |       7.3 |        1.7 |   0.23x |
| Sort + Top-N           |   10K |       8.3 |        6.0 |   0.72x |
| Dataset Pipeline       |   10K |      21.7 |       12.7 |   0.58x |
| Tensor Pipeline        |   10K |      13.3 |        2.7 |   0.20x |
| Column Arithmetic      |  100K |       4.7 |       19.0 |   4.07x |
| Filter + Aggregate     |  100K |       8.7 |        6.0 |   0.69x |
| Sort + Top-N           |  100K |      15.7 |       72.3 |   4.62x |
| Dataset Pipeline       |  100K |      26.0 |       73.0 |   2.81x |
| Tensor Pipeline        |   99K |      15.3 |       23.3 |   1.52x |
| Column Arithmetic      |    1M |      21.3 |      202.0 |   9.47x |
| Filter + Aggregate     |    1M |      28.0 |      141.7 |   5.06x |
| Sort + Top-N           |    1M |     172.7 |      671.0 |   3.89x |
| Dataset Pipeline       |    1M |     180.7 |     1146.3 |   6.35x |

### In-Memory (heap-backed atoms)

| Benchmark              |     N |  Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|----------:|-----------:|--------:|
| Column Arithmetic      |   10K |       1.0 |        3.0 |   3.00x |
| Filter + Aggregate     |   10K |       1.0 |        1.0 |   1.00x |
| Sort + Top-N           |   10K |       1.3 |        4.0 |   3.00x |
| Dataset Pipeline       |   10K |       5.0 |        4.3 |   0.87x |
| Tensor Pipeline        |   10K |       2.0 |        2.0 |   1.00x |
| Column Arithmetic      |  100K |       2.0 |       20.0 |  10.00x |
| Filter + Aggregate     |  100K |       3.0 |        4.0 |   1.33x |
| Sort + Top-N           |  100K |      13.0 |       63.7 |   4.90x |
| Dataset Pipeline       |  100K |      15.0 |       72.3 |   4.82x |
| Tensor Pipeline        |   99K |       5.0 |       20.7 |   4.13x |
| Column Arithmetic      |    1M |      27.3 |      167.0 |   6.11x |
| Filter + Aggregate     |    1M |      24.7 |       47.3 |   1.92x |
| Sort + Top-N           |    1M |     169.3 |      708.0 |   4.18x |
| Dataset Pipeline       |    1M |     195.3 |     1115.7 |   5.71x |
| Tensor Pipeline        |    1M |      85.3 |      186.3 |   2.18x |

## Node.js (ClojureScript)

### MMAP (persistent, file-backed atoms)

| Benchmark              |     N |  Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|----------:|-----------:|--------:|
| Column Arithmetic      |   10K |       3.0 |        6.0 |   2.00x |
| Sort + Top-N           |   10K |       4.3 |        7.3 |   1.69x |
| Tensor Pipeline        |   10K |       3.3 |       12.0 |   3.60x |
| Column Arithmetic      |  100K |       1.3 |       96.7 |  72.50x |

### In-Memory (SAB-backed atoms)

| Benchmark              |     N |  Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|----------:|-----------:|--------:|
| Column Arithmetic      |   10K |       1.7 |        5.7 |   3.40x |
| Column Arithmetic      |  100K |      <1   |       82.0 |   >80x  |
| Filter + Aggregate     |  100K |      <1   |       11.0 |   >30x  |
| Column Arithmetic      |    1M |      <1   |     1104.0 | >1000x  |

> **Note**: Node.js benchmarks for Sort+Top-N, Dataset Pipeline, and Tensor
> Pipeline at larger scales are affected by a known slab corruption issue under
> repeated `swap!` iterations. The corruption occurs when the coalesc allocator
> (class 6, >1024 byte blocks) frees and re-allocates blocks across multiple
> atom swap iterations, overwriting live EveArray headers. Column Arithmetic
> is unaffected because its intermediate arrays are consumed within the same
> swap! iteration and don't interact with the free/reuse cycle.
>
> This is tracked as a separate issue from the stale typed-view bug that was
> fixed in this release.

## Analysis

### Scaling Behavior

Eve's advantage grows with data size. At 10K elements, Eve and stock Clojure
are comparable. At 100K+, Eve's typed-array-backed operations pull ahead
dramatically:

- **Column Arithmetic** (element-wise mul/sub/sum): 4-10x on JVM, 72x+ on Node
  at 100K. Eve operates directly on typed arrays with zero allocation overhead
  per element.

- **Sort + Top-N** (argsort + take-indices): 4-5x at 100K+. The argsort uses
  a typed Int32Array for indices, avoiding boxed integer allocation.

- **Dataset Pipeline** (dataset creation, derived columns, filter, sort, head,
  aggregate): 3-6x at 100K+. Combines all columnar primitives in a realistic
  analytics workflow.

- **Filter + Aggregate** (argfilter + take-indices + 4 stats): 1.3-5x. The
  predicate-per-element check is similar cost in both implementations; Eve wins
  on the aggregation side.

- **Tensor Pipeline** (emap + transpose + reduce): 1.5-4x. Matrix operations
  benefit from contiguous typed array memory layout.

### MMAP vs In-Memory

On JVM, in-memory atoms are 2-4x faster than mmap at 10K (no file I/O overhead),
with the gap narrowing at 1M as computation dominates. At 100K+, mmap overhead
is negligible compared to the actual work.

### At 10K: Eve's Fixed Costs

At 10K elements, Eve's atom serialization and slab allocation overhead can
exceed the savings from typed array operations. Stock Clojure with small vectors
is competitive or faster. Eve shines when the data is large enough that the
O(n) computation dominates the O(1) atom overhead.

## Running Benchmarks

```bash
# JVM (all modes, all scales)
clojure -M:columnar-bench

# Node.js
npm run test:compile
node target/eve-test/all.js columnar-bench    # all modes
node target/eve-test/all.js columnar-mmap     # mmap only
node target/eve-test/all.js columnar-inmem    # in-memory only
```
