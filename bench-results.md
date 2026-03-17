# Eve Benchmark Results

> Cross-process mmap-backed persistent atom — comprehensive benchmark report.
> Run date: 2026-03-17 | Platform: JVM (Clojure 1.12) + Node.js (CLJS)
> Dataset: 416 users + 833 orders (~5 MB), mmap-backed Eve atoms.

---

## 1. Platform Throughput Comparison (B9: Sequential Swaps)

500 sequential `assoc` swaps on an mmap-backed Eve atom, single writer, no contention.

| Platform | Elapsed (ms) | Ops/s   | ms/swap |
|----------|-------------|---------|---------|
| **Node** | 87.0        | 5,749   | 0.17    |
| **JVM**  | 151.8       | 3,294   | 0.30    |

Node is ~1.7× faster than JVM for sequential swaps. Both use lazy slab-backed
types — the gap is much smaller than the 113× baseline before OBJ-8.

---

## 2. Stock CLJ Atom vs Eve mmap Atom — 24 Data-Transformation Benchmarks

All operations run on JVM. "CLJ" = plain `clojure.core/atom` (in-memory).
"EVE" = `eve.atom/atom` (mmap-backed, cross-process capable).
Dataset: 416 users + 833 orders (~11.3 MB on disk).

### 2a. Map Operations (swap! on mmap atom)

| Operation                 | CLJ ms/op | EVE ms/op | EVE p50  | EVE p99  | Ratio  |
|---------------------------|-----------|-----------|----------|----------|--------|
| assoc new key             | 0.019     | 4.189     | 3.7 ms   | 11.5 ms  | 225×   |
| update existing key       | 0.007     | 0.313     | 0.3 ms   | 0.8 ms   | 45×    |
| dissoc key                | 0.010     | 0.303     | 0.2 ms   | 2.2 ms   | 31×    |
| get-in deep (read-only)   | 0.006     | 0.243     | 0.2 ms   | 0.4 ms   | 43×    |
| update-in nested          | 0.006     | 0.673     | 0.6 ms   | 2.4 ms   | 113×   |
| merge two maps            | 0.008     | 0.759     | 0.7 ms   | 2.7 ms   | 92×    |
| select-keys (5 keys)      | 0.005     | 0.247     | 0.2 ms   | 0.4 ms   | 47×    |
| reduce-kv sum all IDs     | 0.059     | 3.858     | 3.5 ms   | 7.7 ms   | 65×    |

### 2b. Vector Operations

| Operation                 | CLJ ms/op | EVE ms/op | EVE p50  | EVE p99  | Ratio  |
|---------------------------|-----------|-----------|----------|----------|--------|
| conj (append)             | 0.005     | 0.566     | 0.5 ms   | 1.2 ms   | 115×   |
| assoc at index            | 0.005     | 0.545     | 0.5 ms   | 0.9 ms   | 106×   |
| nth random access (read)  | 0.003     | 0.220     | 0.2 ms   | 0.4 ms   | 79×    |
| mapv transform            | 0.012     | 3.516     | 3.3 ms   | 8.6 ms   | 287×   |
| filterv select            | 0.006     | 0.210     | 0.2 ms   | 0.3 ms   | 36×    |
| into [] rebuild           | 0.009     | 0.220     | 0.2 ms   | 0.3 ms   | 25×    |

### 2c. Set Operations

| Operation                 | CLJ ms/op | EVE ms/op | EVE p50  | EVE p99  | Ratio  |
|---------------------------|-----------|-----------|----------|----------|--------|
| conj (add)                | 0.004     | 0.263     | 0.2 ms   | 0.7 ms   | 73×    |
| disj (remove)             | 0.005     | 0.263     | 0.2 ms   | 1.0 ms   | 58×    |
| contains?                 | 0.003     | 0.214     | 0.2 ms   | 0.3 ms   | 78×    |
| union via into            | 0.007     | 0.265     | 0.2 ms   | 0.5 ms   | 37×    |

### 2d. Rich Data Transformations

| Operation                          | CLJ ms/op | EVE ms/op | EVE p50   | EVE p99   | Ratio  |
|------------------------------------|-----------|-----------|-----------|-----------|--------|
| filter-map-reduce orders           | 0.243     | 8.564     | 7.9 ms    | 14.4 ms   | 35×    |
| group-by status                    | 0.358     | 10.199    | 10.0 ms   | 11.8 ms   | 28×    |
| flatten profiles                   | 0.173     | 10.895    | 10.5 ms   | 13.9 ms   | 63×    |
| bulk swap 50 records               | 0.116     | 16.438    | 15.8 ms   | 20.7 ms   | 142×   |
| leaderboard build (sort + take)    | 0.758     | 10.809    | 10.4 ms   | 14.8 ms   | 14×    |
| rewrite nested matrix              | 0.009     | 0.796     | 0.8 ms    | 1.2 ms    | 85×    |

### 2e. Bulk Operations

| Operation                 | CLJ ms/op | EVE ms/op | EVE p50  | EVE p99  | Ratio  |
|---------------------------|-----------|-----------|----------|----------|--------|
| counter inc × 500         | 0.003     | 0.278     | 0.3 ms   | 0.4 ms   | 93×    |
| assoc 100 new orders      | 0.023     | 3.015     | 2.9 ms   | 5.3 ms   | 134×   |

**Note:** The CLJ atom is purely in-memory with no persistence. Eve's overhead
is the cost of mmap-backed cross-process persistence with HAMT path-copying,
slab allocation, epoch GC, and CAS serialization. Read-only operations (get-in,
nth, contains?, filterv, leaderboard) have the smallest ratios (14–79×) since
they skip the write path entirely.

---

## 3. Write Throughput by Atom Size (B2: JVM mmap atom)

| Initial Keys | Write Ops | ms/swap | Ops/s  |
|--------------|-----------|---------|--------|
| 100          | 200       | 0.356   | 2,809  |
| 500          | 200       | 0.345   | 2,902  |
| 1,000        | 200       | 0.337   | 2,971  |

Write throughput is nearly constant regardless of atom size — HAMT path-copy
is O(log32 n). This is a 38–145× improvement over the original baseline.

---

## 4. Value Complexity Scaling (B3: 100 keys, JVM)

| Value Type                         | ms/swap | Ops/s | Disk (MB) |
|------------------------------------|---------|-------|-----------|
| flat (string)                      | 0.318   | 3,143 | 0.99      |
| nested (3-level map)               | 0.752   | 1,329 | 0.99      |
| rich (deep + vec + set + 200-char) | 2.192   | 456   | 1.24      |

Rich nested values are ~7× slower than flat strings — much better than the
29× gap at baseline.

---

## 5. Read Throughput (B1: deref/s, JVM)

| Keys | Iters  | Elapsed (ms) | Ops/s   |
|------|--------|-------------|---------|
| 1    | 10,000 | 1,628       | 6,144   |
| 10   | 10,000 | 1,637       | 6,110   |
| 100  | 10,000 | 1,628       | 6,142   |
| 500  | 1,000  | 162         | 6,158   |

Read throughput is **constant across all sizes** (~6,100 ops/s) — deref returns
a lazy slab-backed EveHashMap without materialization. This is a transformative
improvement from the baseline where 500-key derefs were 143× slower than 1-key.

---

## 6. Load-In Time (B4: empty → target)

| Target Keys | Total (ms) | ms/swap | Disk (MB) |
|-------------|-----------|---------|-----------|
| 100         | 120.8     | 1.208   | 0.99      |
| 500         | 286.5     | 0.573   | 0.99      |
| 1,000       | 422.3     | 0.422   | 1.06      |

ms/swap actually *decreases* with larger atoms (amortization of JVM warmup).
98% improvement over baseline (31.7 ms/swap for 1000 keys → 0.42 ms/swap).

---

## 7. Load-Out Time (B5: deref + full traversal, JVM)

| Keys  | ms/deref |
|-------|----------|
| 100   | 0.85     |
| 500   | 1.35     |
| 1,000 | 1.62     |

Full materialization (deref + `into {}`) still sub-2ms for 1000 keys.

---

## 8. Disk Footprint (B6: Lazy Slab Growth)

| Keys | Payload/key | Disk (MB) | Logical Est (MB) | Overhead |
|------|-------------|-----------|-------------------|----------|
| 100  | 500 chars   | 1.23      | 0.07              | 17×      |
| 500  | 5,000 chars | 5.15      | 2.50              | 2.1×     |
| 1,000| 20,000 chars| 33.40     | 19.31             | 1.7×     |

Overhead drops from 17× (small) to 1.7× (large) — slab files grow lazily
and proportionally. The pre-OBJ-9 baseline was 240 MB fixed for all sizes.

---

## 9. Contention Scaling (B7: Node workers, counter increment)

| Writers | Total Swaps | Wall (ms) | Throughput (swaps/s) | Correct? |
|---------|-------------|-----------|----------------------|----------|
| 1       | 500         | 235       | 2,128                | Yes      |
| 2       | 500         | 235       | 2,132                | Yes      |
| 4       | 500         | 269       | 1,856                | Yes      |
| 8       | 496         | 493       | 1,007                | Yes      |
| 16      | 496         | 910       | 545                  | Yes      |

CAS correctness holds at all concurrency levels. Throughput degrades
gracefully — 16 writers still achieve 545 swaps/s with zero lost updates.

### JVM Multi-Threaded Contention (4 threads, same process)

| Atom | Threads | Ops/thread | Wall (ms) | Ops/s    | Correct? |
|------|---------|------------|-----------|----------|----------|
| CLJ  | 4       | 100        | 4         | 110,951  | Yes      |
| EVE  | 4       | 100        | 86        | 4,674    | Yes      |

Eve is ~24× slower than in-memory CLJ under contention, reflecting the cost
of mmap CAS + serialization. But CAS semantics are perfect.

---

## 10. Swap Latency Percentiles (B14)

### JVM (no contention, 500 swaps on 100-key atom)

| min    | p50    | p95    | p99    | max    |
|--------|--------|--------|--------|--------|
| 0.28ms | 0.36ms | 3.36ms | 4.58ms | 4.99ms |

### Node (no contention, 500 swaps)

| min    | p50    | p95    | p99    | max    |
|--------|--------|--------|--------|--------|
| 0.08ms | 0.13ms | 0.50ms | 0.94ms | 1.76ms |

### Node (4 writers contending, 125 swaps each)

| Writer | p50    | p95   | p99    | max    |
|--------|--------|-------|--------|--------|
| 0      | 0.24ms | 3.1ms | 8.6ms  | 9.7ms  |
| 1      | 0.23ms | 4.1ms | 5.4ms  | 7.0ms  |
| 2      | 0.23ms | 0.8ms | 1.8ms  | 1.9ms  |
| 3      | 0.19ms | 1.4ms | 7.7ms  | 9.6ms  |

Under contention, p50 stays sub-millisecond. Tail latency (p95–max) increases
due to CAS retries.

---

## 11. Cold Start Time (B10)

| Operation                    | Elapsed (ms) |
|------------------------------|-------------|
| JVM create (persistent-atom) | 5.3         |
| JVM join (join-atom)         | 1.3         |
| Node join (join-atom)        | 1.3         |

Join is ~1.3ms across platforms. Create includes slab file initialization.

---

## 12. Cross-Process Visibility Latency (B11: JVM write → Node read)

| Stat | ms    |
|------|-------|
| min  | 152.6 |
| p50  | 154.6 |
| p95  | 162.4 |
| max  | 162.4 |

~155ms round-trip includes JVM swap + Node process spawn + addon load + deref.
The mmap write itself is instant — latency is dominated by Node startup.

---

## 13. Epoch GC (B12: 100-key atom, 500 update swaps)

| Metric       | Value    |
|--------------|----------|
| Total (ms)   | 144.2    |
| ms/swap      | 0.289    |
| Disk before  | 0.99 MB  |
| Disk after   | 0.99 MB  |
| Disk growth  | 0 MB     |

Epoch GC reclaims retired HAMT nodes — zero disk growth despite 500 mutations.

---

## 14. Slab Utilization (B13: 500 keys with labels)

| Class | Block Size | File Size (KB) |
|-------|------------|----------------|
| 0     | 32 B       | 64.1           |
| 1     | 64 B       | 64.1           |
| 2     | 128 B      | 128.1          |
| 3     | 256 B      | 32.1           |
| 4     | 512 B      | 32.1           |
| 5     | 1024 B     | 16.1           |
| **Total** |        | **336 KB**     |

Slabs grow lazily — 500 keys with labels use only 336 KB total.

---

## 15. Profiled Swap Breakdown (JVM, 5 swaps on 416-user dataset)

| Phase             | Avg       | Min       | Max       |
|-------------------|-----------|-----------|-----------|
| apply-f           | 6.20 ms   | 3.49 ms   | 9.03 ms   |
| refresh-regions   | 567.1 µs  | 295.8 µs  | 1.51 ms   |
| retire-enqueue    | 65.5 µs   | 14.3 µs   | 179.1 µs  |
| read-root         | 25.2 µs   | 17.2 µs   | 45.6 µs   |
| unpin-epoch       | 22.9 µs   | 8.8 µs    | 70.3 µs   |
| pin-epoch         | 16.5 µs   | 13.7 µs   | 19.7 µs   |
| flush-retires     | 13.7 µs   | 5.5 µs    | 44.3 µs   |
| resolve-ptr       | 4.6 µs    | 2.8 µs    | 10.1 µs   |
| cas               | 1.2 µs    | 977 ns    | 1.8 µs    |

`apply-f` (user's transformation function + HAMT path-copy + slab allocation)
dominates at ~93% of swap time. CAS itself is ~1.2 µs. Infrastructure overhead
(epoch, retire, refresh) is <1ms combined.

---

## 16. Columnar Operations — Eve EveArray vs Stock Clojure Vectors

Each benchmark runs a multi-step pipeline inside `swap!`. "Eve" uses `EveArray`
columns with typed-view fast paths (`aget`/`aset` loops). "Stock" uses plain
Clojure vectors with `mapv`/`reduce`/`sort`. 7 timed runs, trimmed mean of middle 5.

### JVM — mmap (persistent atom)

| Benchmark              |     N |   Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|-----------:|-----------:|--------:|
| Column Arithmetic      |   10K |        3.0 |        3.0 |   1.00x |
| Filter + Aggregate     |   10K |        3.2 |        0.8 |   0.25x |
| Sort + Top-N           |   10K |        3.8 |        4.4 |   1.16x |
| Dataset Pipeline       |   10K |       12.6 |        9.8 |   0.78x |
| Tensor Pipeline        |   10K |        5.8 |        2.0 |   0.34x |
| **Column Arithmetic**  |  100K |      **2.8** |     21.4 | **7.64x** |
| **Filter + Aggregate** |  100K |      **3.4** |      6.8 | **2.00x** |
| **Sort + Top-N**       |  100K |     **12.8** |     35.0 | **2.73x** |
| **Dataset Pipeline**   |  100K |     **20.8** |     64.8 | **3.12x** |
| **Tensor Pipeline**    |  100K |      **9.0** |     14.4 | **1.60x** |

### JVM — in-memory (heap atom, no persistence)

| Benchmark              |     N |   Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|-----------:|-----------:|--------:|
| Column Arithmetic      |   10K |        0.4 |        1.2 |   3.00x |
| Filter + Aggregate     |   10K |        0.6 |        0.2 |   0.33x |
| Sort + Top-N           |   10K |        1.2 |        2.2 |   1.83x |
| Dataset Pipeline       |   10K |        3.6 |        3.8 |   1.06x |
| Tensor Pipeline        |   10K |        2.0 |        1.6 |   0.80x |
| **Column Arithmetic**  |  100K |      **1.6** |     17.4 | **10.87x** |
| **Filter + Aggregate** |  100K |      **2.4** |      6.2 | **2.58x** |
| **Sort + Top-N**       |  100K |     **12.0** |     31.0 | **2.58x** |
| **Dataset Pipeline**   |  100K |     **16.2** |     57.4 | **3.54x** |
| **Tensor Pipeline**    |  100K |      **5.2** |     13.8 | **2.65x** |

### Node.js — mmap (persistent atom)

| Benchmark              |     N |   Eve (ms) | Stock (ms) | Speedup |
|------------------------|------:|-----------:|-----------:|--------:|
| Column Arithmetic      |   10K |       17.6 |        3.4 |   0.19x |
| Filter + Aggregate     |   10K |        4.2 |        0.8 |   0.19x |
| Sort + Top-N           |   10K |        4.4 |        4.6 |   1.05x |
| Tensor Pipeline        |   10K |       19.0 |        5.8 |   0.31x |
| Column Arithmetic      |  100K |      212.0 |       57.4 |   0.27x |
| Filter + Aggregate     |  100K |       40.2 |        7.2 |   0.18x |
| **Sort + Top-N**       |  100K |     **51.8** |     69.6 | **1.34x** |
| Dataset Pipeline       |  100K |      346.8 |      175.8 |   0.51x |
| Tensor Pipeline        |  100K |      188.6 |       56.4 |   0.30x |

**Key findings:**
- **JVM IBulkAccess fast paths dominate**: At 100K, Eve is 2–11× faster than stock
  Clojure vectors on JVM. The `double[]`/`int[]` `aget` loops completely eliminate
  per-element protocol dispatch and byte-array allocation.
- **Node.js typed-view fast paths** show modest gains for sort (1.34x at 100K) but
  the mmap atom serialization overhead dominates arithmetic ops. The CLJS fast paths
  eliminate `nth` protocol dispatch but mmap CAS + slab allocation per swap! is the
  bottleneck.
- **Sort + Top-N consistently benefits** on both platforms because `argsort` does
  one bulk extraction then sorts indices — eliminating O(n log n) `nth` calls.
- **Dataset Pipeline at 10K**: CLJS has a known serialization bug where EveArray
  `cnt` fields get corrupted after SAB atom round-trip in repeated swap! iterations.
  JVM works correctly at all scales.

---

## Key Takeaways

1. **Node is ~1.7× faster than JVM** for sequential mmap atom swaps (down from 113× at baseline thanks to JVM lazy collection types)
2. **Eve mmap is 25–287× slower than in-memory CLJ atoms** depending on operation — the cost of cross-process persistence with mmap CAS, HAMT path-copying, and slab serialization
3. **Read-only operations have the smallest overhead** (14–79×) since they skip the write path
4. **Write throughput is size-independent** — ~3,000 ops/s on JVM regardless of 100 or 1,000 keys (O(log32 n) HAMT path-copy)
5. **Read throughput is constant** — ~6,100 ops/s across all sizes (lazy EveHashMap deref)
6. **CAS correctness is perfect** under all tested concurrency levels (1–16 writers)
7. **Epoch GC works** — zero disk growth during mutations
8. **Disk footprint scales proportionally** — 1.7× overhead at 1000 keys with 20KB values (was 240 MB fixed)
9. **Swap hotspot is apply-f** (93% of time) — CAS and infrastructure are negligible
10. **Cold join is ~1.3ms** on both JVM and Node
11. **JVM columnar fast paths deliver 2–11× speedup** over stock Clojure vectors at 100K elements via `IBulkAccess` `double[]`/`int[]` aget loops
12. **CLJS typed-view fast paths** eliminate `nth` protocol dispatch; Sort+Top-N is 1.3× faster than stock at 100K on Node

---

## Known Issues

- **Babashka worker broken**: `empty-hash-set` unresolved in `set.cljc:947` under
  bb's SCI interpreter. Cross-process bb benchmarks (Phase 5/6 of data-bench,
  contention-bench) are currently skipped. This is a bb-specific reader conditional
  issue, not an Eve runtime bug.

- **CLJS Dataset Pipeline in atom swap!**: EveArray `cnt` field gets corrupted
  (negative or huge values like `-1425054019`) after SAB/mmap atom round-trip when
  newly-created EveArrays (from `func/mul`, `ds/filter-rows`, etc.) are stored back
  into the atom. Affects both mmap and in-memory modes on CLJS. JVM works correctly.
  Root cause: likely a serialization/deserialization mismatch for EveArray type-id
  `0x1D` blocks created inside `swap!` when nested inside a CLJS PersistentHashMap
  that gets converted via `convert-to-sab`.
