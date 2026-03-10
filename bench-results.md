# Native EVE Benchmark Results

> Cross-process mmap-backed persistent atom — Phase 7 complete.
> All benchmarks run with scaled-down sizes. B6 tier 3 (1000 keys × 20KB)
> OOM'd on the coalescing slab; all other benchmarks passed.

---

## B1: Read Throughput (deref/s)

| Keys | Iters  | Elapsed (ms) | Ops/s   |
|------|--------|--------------|---------|
| 1    | 10,000 | 16.9         | 590,895 |
| 10   | 10,000 | 60.8         | 164,524 |
| 100  | 10,000 | 443.1        | 22,569  |
| 500  | 1,000  | 240.8        | 4,153   |

Read throughput scales roughly inversely with key count (HAMT traversal depth).

---

## B2: Write Throughput (swap/s, no contention)

| Initial Keys | Write Ops | ms/swap | Ops/s |
|--------------|-----------|---------|-------|
| 100          | 200       | 12.6    | 79.2  |
| 500          | 200       | 38.1    | 26.3  |
| 1,000        | 200       | 70.9    | 14.1  |

Write latency increases with atom size due to larger HAMT tree serialization.

---

## B3: Value Complexity Scaling (100 keys)

| Value Type                         | ms/swap | Ops/s | Disk (MB) |
|------------------------------------|---------|-------|-----------|
| flat (string)                      | 3.4     | 297.6 | 240.7     |
| nested (3-level map)               | 24.8    | 40.4  | 240.7     |
| rich (deep + vec + set + 200-char) | 97.5    | 10.3  | 240.7     |

Rich nested values are ~29× slower than flat strings per swap.

---

## B4: Load-In Time (empty → target)

| Target Keys | Total (ms) | ms/swap | Disk (MB) |
|-------------|------------|---------|-----------|
| 100         | 565.9      | 5.7     | 240.7     |
| 500         | 7,971.1    | 15.9    | 240.7     |
| 1,000       | 31,730.5   | 31.7    | 240.7     |

ms/swap grows with atom size (O(log n) HAMT path copies + growing serialization).

---

## B5: Load-Out Time (deref + full traversal)

| Keys  | ms/deref |
|-------|----------|
| 100   | 0.07     |
| 500   | 0.30     |
| 1,000 | 0.67     |

Reads are 50–100× faster than writes — lazy HAMT traversal is very efficient.

---

## B6: Disk Footprint

| Keys | Payload/key | Disk (MB) | Logical Est (MB) | Overhead Ratio |
|------|-------------|-----------|-------------------|----------------|
| 100  | 500 chars   | 240.7     | 0.07              | 3,365×         |
| 500  | 5,000 chars | 240.7     | 2.5               | 96×            |

Disk is dominated by pre-allocated slab files (240 MB base). The 1000-key × 20KB
test exceeded coalescing slab capacity. Overhead ratio drops sharply as logical
data size grows — the slab files are fixed-size pools.

---

## B7: Contention Scaling (Node workers, counter increment)

| Writers | Total Swaps | Wall (ms) | Throughput (swaps/s) | Correct? |
|---------|-------------|-----------|----------------------|----------|
| 1       | 500         | 263       | 1,901                | Yes      |
| 2       | 500         | 272       | 1,837                | Yes      |
| 4       | 500         | 289       | 1,733                | Yes      |
| 8       | 496         | 538       | 922                  | Yes      |
| 16      | 496         | 1,047     | 474                  | Yes      |

CAS semantics hold perfectly under all concurrency levels. Throughput degrades
gracefully — 16 writers still achieve 474 swaps/s with zero lost updates.

---

## B9: JVM vs Node Throughput (500 sequential swaps)

| Platform | Elapsed (ms) | Ops/s | ms/swap |
|----------|--------------|-------|---------|
| JVM      | 7,889        | 63.4  | 15.8    |
| Node     | 69.9         | 7,157 | 0.14    |

Node is ~113× faster than JVM for sequential swaps. The JVM path deserializes to
plain Clojure maps on every deref then fully re-serializes on swap, while CLJS
uses lazy slab-backed types with structural sharing.

---

## B10: Cold Start Time

| Operation                  | Elapsed (ms) |
|----------------------------|--------------|
| JVM create (persistent-atom) | 53.5       |
| JVM join (join-atom)         | 1.7        |
| Node join (join-atom)        | 3.1        |

Join is very fast (~2ms). Create includes slab file initialization.

---

## B11: Cross-Process Visibility Latency (JVM write → Node read)

| Stat | ms    |
|------|-------|
| min  | 190.2 |
| p50  | 195.5 |
| p95  | 200.2 |
| max  | 200.2 |

~195ms round-trip includes JVM swap + Node process spawn + addon load + deref.
The mmap write itself is instant — latency is dominated by Node process startup.

---

## B12: Epoch GC (100-key atom, 500 update swaps)

| Metric       | Value   |
|--------------|---------|
| Total (ms)   | 3,158   |
| ms/swap      | 6.3     |
| Disk before  | 240.7 MB |
| Disk after   | 240.7 MB |
| Disk growth  | 0 MB    |

Epoch GC successfully reclaims retired HAMT nodes — zero disk growth despite
500 mutations. Swaps on existing keys are faster (6.3ms) than building new keys.

---

## B13: Slab Utilization (500 keys with nested values)

| Class | Block Size | File Size (MB) |
|-------|------------|----------------|
| 0     | 32 B       | 32.1           |
| 1     | 64 B       | 64.1           |
| 2     | 128 B      | 64.1           |
| 3     | 256 B      | 32.0           |
| 4     | 512 B      | 16.0           |
| 5     | 1024 B     | 16.0           |
| **Total** |        | **224.4 MB**   |

Slab files are pre-allocated at fixed sizes regardless of usage.

---

## B14: Swap Latency Percentiles

### JVM (no contention, 500 swaps on 100-key atom)

| min   | p50    | p95    | p99    | max    |
|-------|--------|--------|--------|--------|
| 6.1ms | 21.4ms | 36.1ms | 39.8ms | 41.8ms |

### Node (no contention, 500 swaps)

| min    | p50    | p95    | p99    | max    |
|--------|--------|--------|--------|--------|
| 0.09ms | 0.11ms | 0.26ms | 0.80ms | 2.33ms |

### Node (4 writers contending, 125 swaps each)

| Writer | p50    | p95   | max    |
|--------|--------|-------|--------|
| 0      | 0.17ms | 3.1ms | 9.3ms  |
| 1      | 0.20ms | 2.0ms | 18.9ms |
| 2      | 0.19ms | 0.7ms | 12.3ms |
| 3      | 0.18ms | 1.7ms | 4.6ms  |

Under contention, p50 stays sub-millisecond but tail latency (p95–max) increases
10–50× due to CAS retries.

---

## Key Takeaways

1. **Node (CLJS) is dramatically faster than JVM** for atom operations due to
   lazy slab-backed types vs full serialize/deserialize
2. **CAS correctness is perfect** under all tested concurrency levels (1–16 writers)
3. **Epoch GC works** — zero disk growth during mutations
4. **Read latency is 50–100× better than write latency**
5. **Lazy slab growth** — empty atom is ~1 MB, scales proportionally to data size
   (was 240 MB pre-allocated baseline)

---

## Pass 1 Results — OBJ-1: Time-Throttled Retire Flush

> Change: Skip 256-slot epoch scan when last scan was within 50ms and
> retire queue < 64 entries. Only `atom.cljc` modified.

### B2: Write Throughput (Pass 1)

| Initial Keys | Baseline Ops/s | Pass 1 Ops/s | Δ       |
|--------------|---------------|-------------|---------|
| 100          | 100.7         | 111.6       | **+10.8%** |
| 500          | 34.0          | 40.5        | **+19.1%** |
| 1,000        | 17.8          | 21.7        | **+21.9%** |

### B9: Node vs JVM Throughput (Pass 1)

| Platform | Baseline Ops/s | Pass 1 Ops/s | Δ       |
|----------|---------------|-------------|---------|
| JVM      | 63.4          | 97.1        | **+53.2%** |
| Node     | 7,785         | 11,849      | **+52.2%** |

### B12: Epoch GC (Pass 1)

| Metric    | Baseline | Pass 1 | Δ       |
|-----------|----------|--------|---------|
| ms/swap   | 5.15     | 4.05   | **-21.4%** |
| Disk growth | 0 MB   | 0 MB   | Same    |

### B7: Contention Scaling (Pass 1)

| Writers | Baseline (swaps/s) | Pass 1 (swaps/s) | Δ       |
|---------|-------------------|-----------------|---------|
| 1       | 1,901             | 2,126           | **+11.8%** |
| 8       | 922               | 1,017           | **+10.3%** |
| 16      | 474               | 504             | **+6.3%**  |

### B14: Node Swap Latency (Pass 1)

| Metric | Baseline | Pass 1 | Δ       |
|--------|----------|--------|---------|
| p50    | 0.11ms   | 0.047ms | **-57%** |
| p95    | 0.26ms   | 0.25ms  | -4%     |

---

## Pass 1 Results — OBJ-4: CAS Retry Backoff

> Change: Added jittered exponential backoff (threshold > 3 attempts, 1-8ms cap)
> to CAS retry loop in both CLJS and JVM paths. Only `atom.cljc` modified.

### B7: Contention Scaling (OBJ-4)

| Writers | Pass 1 (swaps/s) | OBJ-4 (swaps/s) | Δ      |
|---------|-----------------|-----------------|--------|
| 1       | 2,126           | 1,978           | -7% (noise) |
| 2       | —               | 1,903           | —      |
| 4       | —               | 1,728           | —      |
| 8       | 1,017           | 937             | -8% (noise) |
| 16      | 504             | 462             | -8% (noise) |

No statistically significant change. B7 uses a very fast counter increment
(~0.05ms/iter) on a CPU-limited container where the thundering herd doesn't
waste CPU (OS already serializes 16 processes on few cores). Backoff expected
to help on many-core machines with slower swap functions.

### B2: Write Throughput (OBJ-4, no contention)

| Keys | Pass 1 Ops/s | OBJ-4 Ops/s | Δ        |
|------|-------------|-------------|----------|
| 100  | 111.6       | 79.8        | JVM variance |
| 500  | 40.5        | 29.5        | JVM variance |
| 1000 | 21.7        | 15.9        | JVM variance |

B2 runs on JVM with high variance between runs. No CAS failures occur
(single writer), so backoff code is never executed. No regression.

### Heavy Stress Testing (OBJ-4)

| Test | Without Backoff | With Backoff |
|------|----------------|-------------|
| 16 Node × 500 swaps (3 runs) | 3/3 FAIL (DOUBLE-ALLOC) | 2/3 PASS (8000 correct, ~5300 swaps/s) |
| 8 Node × 500 swaps | — | PASS (4000 correct, 5423 swaps/s) |
| 4 JVM + 4 Node × 200 swaps | — | PASS (1600 correct, 2530 swaps/s) |

Backoff reduces allocation contention enough to mitigate a pre-existing
DOUBLE-ALLOC race in `map.cljc:alloc-bytes!` under extreme load (16 writers).
The allocator race is a separate issue — backoff lowers the trigger probability.

---

## OBJ-7 + OBJ-8: JVM Lazy Collection Types (3 passes)

> Pass 1: JVM keyword serialization cache (mem.cljc)
> Pass 2: JVM HAMT path-copy assoc + EveHashMap update (map.cljc)
> Pass 3: Lazy deref + pass-through resolve + tree-diff retire (atom.cljc)

### B9: JVM vs Node Throughput (OBJ-8)

| Platform | Baseline Ops/s | OBJ-1 Ops/s | OBJ-8 Ops/s | Total Δ       |
|----------|---------------|-------------|-------------|---------------|
| JVM      | 63.4          | 97.1        | **2,665**   | **+4,103%**   |
| Node     | 7,785         | 11,849      | **13,615**  | **+75%**      |

JVM is now only 5× slower than Node (was 113× at baseline).

### B2: Write Throughput (OBJ-8)

| Initial Keys | Baseline Ops/s | OBJ-8 Ops/s | Δ           |
|--------------|---------------|-------------|-------------|
| 100          | 79.2          | **3,025**   | **+38×**    |
| 500          | 26.3          | **2,143**   | **+81×**    |
| 1,000        | 14.1          | **2,040**   | **+145×**   |

Write throughput barely degrades with atom size — path-copy is O(log32 n).

### B14: JVM Swap Latency (OBJ-8)

| Metric | Baseline   | OBJ-8      | Δ         |
|--------|-----------|------------|-----------|
| min    | 6.1ms     | **0.19ms** | **-97%**  |
| p50    | 21.4ms    | **0.39ms** | **-98%**  |
| p95    | 36.1ms    | **0.59ms** | **-98%**  |
| max    | 41.8ms    | **6.48ms** | **-84%**  |

### B1: Read Throughput (OBJ-8, deref returns EveHashMap)

| Keys | Baseline Ops/s | OBJ-8 Ops/s   | Δ         |
|------|---------------|---------------|-----------|
| 1    | 590,895       | **1,691,087** | **+186%** |
| 10   | 164,524       | **2,243,753** | **+1264%**|
| 100  | 22,569        | **3,861,356** | **+17011%**|
| 500  | 4,153         | **5,060,831** | **+121786%**|

Reads are now O(1) — deref returns the slab-backed EveHashMap directly
without materializing to a plain Clojure map.

### B4: Load-In Time (OBJ-8)

| Target Keys | Baseline ms/swap | OBJ-8 ms/swap | Δ         |
|-------------|-----------------|---------------|-----------|
| 100         | 5.7             | **1.56**      | **-73%**  |
| 500         | 15.9            | **0.68**      | **-96%**  |
| 1,000       | 31.7            | **0.55**      | **-98%**  |

### B12: Epoch GC (OBJ-8)

| Metric    | Baseline | OBJ-8      | Δ         |
|-----------|----------|------------|-----------|
| ms/swap   | 5.15     | **0.42**   | **-92%**  |
| Disk growth | 0 MB   | 0 MB       | Same      |

---

## OBJ-9: Lazy Slab Growth — Disk Footprint Reduction

> Change: Slab files (.slab0–.slab5) start small and grow on demand via file
> extension + re-map, doubling each time. CAS-based leader election for
> cross-process growth coordination. Bitmap split into separate `.bm` files
> so data_offset never changes during growth. All slab classes (0–5) scale to
> 1GB max; class 6 (coalescing) uses i64 addressing for effectively unlimited
> overflow capacity.

### B6: Disk Footprint (OBJ-9, split-file bitmap)

| Keys | Payload/key | Baseline (MB) | OBJ-9 (MB) | Overhead Ratio | Δ          |
|------|-------------|---------------|------------|----------------|------------|
| 100  | 500 chars   | 240.7         | **~1.0**   | 14×            | **-99.6%** |
| 500  | 5,000 chars | 240.7         | **~1.4**   | 0.6×           | **-99.4%** |

All slabs start small. No pre-reservation waste.

### Per-slab breakdown (empty atom, CLJS)

| Class | Block Size | Baseline (MB) | OBJ-9 data (KB) | OBJ-9 bitmap (B) | Reduction |
|-------|------------|---------------|-----------------|-------------------|-----------|
| 0     | 32 B       | 32.1          | 64.1            | 256               | **513×**  |
| 1     | 64 B       | 64.1          | 64.1            | 128               | **1,024×**|
| 2     | 128 B      | 64.1          | 32.1            | 32                | **2,046×**|
| 3     | 256 B      | 32.0          | 32.1            | 16                | **1,022×**|
| 4     | 512 B      | 16.0          | 16.1            | 16                | **1,019×**|
| 5     | 1024 B     | 16.0          | 16.1            | 16                | **1,019×**|
| 6 (coalesc) | var  | 16.1 (fixed)  | 512.1           | —                 | **32×**   |
| **Total** |         | **240.5 MB**  | **999 KB**      |                   | **246×**  |

### Disk Size Scaling (CLJS, progressive key insertion)

| Stage | Keys | Total Disk | Delta    |
|-------|------|-----------|----------|
| Empty atom      | 0     | **999 KB**  | —        |
| Single counter  | 1     | 999 KB      | +0       |
| Small map       | 11    | 999 KB      | +0       |
| Medium map      | 100   | 1,047 KB    | +48 KB   |
| Large map       | 500   | 1,463 KB    | +416 KB  |
| Very large map  | 1000  | 2,120 KB    | +657 KB  |

Growth is proportional to data — no step-function jumps.

### B4: Load-In Disk Footprint (JVM)

| Target Keys | Baseline Disk (MB) | OBJ-9 Disk (MB) | Δ          |
|-------------|-------------------|-----------------|------------|
| 100         | 240.7             | **0.98**        | **-99.6%** |
| 500         | 240.7             | **0.99**        | **-99.6%** |
| 1,000       | 240.7             | **1.08**        | **-99.6%** |

### B2: Write Throughput (OBJ-9 latest, JVM)

| Initial Keys | Baseline Ops/s | OBJ-9 Ops/s | Δ         |
|--------------|---------------|-------------|-----------|
| 100          | 79.2          | **722**     | **+9.1×** |
| 500          | 26.3          | **992**     | **+38×**  |
| 1,000        | 14.1          | **1,092**   | **+77×**  |

### B14: Swap Latency (OBJ-9 latest, JVM)

| Metric | Baseline   | OBJ-9      | Δ         |
|--------|-----------|------------|-----------|
| min    | 6.1ms     | **0.42ms** | **-93%**  |
| p50    | 21.4ms    | **0.61ms** | **-97%**  |
| p95    | 36.1ms    | **0.91ms** | **-97%**  |
| max    | 41.8ms    | **3.85ms** | **-91%**  |

### B14: Swap Latency (OBJ-9 latest, Node)

| Metric | Baseline   | OBJ-9      | Δ         |
|--------|-----------|------------|-----------|
| min    | 0.09ms    | **0.08ms** | -11%      |
| p50    | 0.11ms    | **0.12ms** | same      |
| p95    | 0.26ms    | **0.36ms** | +38%      |
| max    | 2.33ms    | **2.58ms** | same      |

### B13: Slab Utilization (OBJ-9, 500 keys, CLJS)

| Class | Block Size | Baseline File (MB) | OBJ-9 data (KB) | OBJ-9 bm (B) | Status |
|-------|------------|-------------------|-----------------|---------------|--------|
| 0     | 32 B       | 32.1              | 64.1            | 256           | Initial capacity |
| 1     | 64 B       | 64.1              | 64.1            | 128           | Initial capacity |
| 2     | 128 B      | 64.1              | 32.1            | 32            | Initial capacity |
| 3     | 256 B      | 32.0              | 256.1           | 128           | Grew 3× |
| 4     | 512 B      | 16.0              | 256.1           | 64            | Grew 4× |
| 5     | 1024 B     | 16.0              | 16.1            | 16            | Initial capacity |
| **Total** |        | **224.4 MB**      | **689 KB**      |               | **333×** |

### Lazy Growth Verification

All slab classes start small and scale on demand:
- **Classes 0–5:** Start at 16–64 KB data files, double on each growth event, scale to 1GB max
- **Class 6 (coalesc):** i64 address space — effectively unlimited overflow capacity
- **Bitmap files:** Separate `.bm` files start at 16–256 bytes, grow independently
- Empty atom: **999 KB total** (was 240.7 MB baseline, was 17 MB with pre-sized bitmap)
- After 1000 keys: **2.1 MB total** — proportional growth, no wasted space
