# STEP 3: the slab access path itself (REPORT)

**Declaration: STEP 3 COMPLETE, stopped at two named walls.** The hot read path for heap-domain collections now uses:

- plain loads from the region's heap `byte[]`;
- no allocation per read;
- an unboxed, hinted hash;
- one dispatch per read instead of two.

There is no format change, and the correctness gates are green. Two pieces of the anatomy resist, and I stopped at each one rather than route around it (§4):

1. The last region call. The layering needs it: the slab context is shared code and cannot name the JVM's region.
2. The linear KV scan. The stored format needs it.

- Branch: `claude/eve-jvm-heap-grade` on SeniorCareMarket/eve, on top of step 2 (8bf50a2). Commits: 7283dc7, fc9ad4b, plus this report.
- Seat: JVM. OpenJDK 21.0.12, Clojure 1.12.0, 4 cores, load 0.95–1.23 during the runs. Fresh container; same Maven-jar classpath as steps 1–2 (deps.edn `:paths` under `clojure.main`).
- Bench: `doc/jvm-heap-grade/bench/hotpath.clj`.
  - One JVM, 14 groups, two arms each (step2 and step3), 5 interleaved rounds. Within a round the two arms of a group run back to back, and the arm order alternates by round.
  - Every group is warmed for 0.3 s under both arms before calibration.
  - Each cell is that round's min-of-3 batches of ~40 ms.
  - bytes/op come from ThreadMXBean.
- Banked output:
  - `doc/jvm-heap-grade/bench/hotpath-run2.out`: the run quoted below.
  - `hotpath-run1.out`: the same bench, one run earlier. Its numbers agree, but it printed µ as `?` (stdout encoding).
- Checksums are EQUAL across both arms in all 14 groups, in both runs.

## 1. How the step-2 arm is built (read this before the numbers)

Both arms run in the same JVM. The step-2 arm restores step 2's read path with `alter-var-root` for the duration of each measurement:

- `alloc/node-u8`, `node-i32` and `node-bytes-equal?` become the ISlabIO ops they replaced: `-sio-read-u8`, `-sio-read-i32` and `slab/bytes-equal-at?`. These are exactly what the map, the vector and the deftype fields called at step 2.
- `node-bytes` becomes `-sio-read-bytes`, and `node-heap-array` answers nil, so there is no in-place scan.
- `eve.map/kv-pos` becomes step 2's scan: a closure over a loop, allocated and boxed on every call.
- `portable-hash-bytes` and the bit helpers become verbatim copies of their step-2 bodies, over verbatim copies of step-2's `eve.int32`.

Two approximations:

- The call boundaries stay step 3's. A primitive call goes into a wrapper, which calls the boxed step-2 body.
- Each call site sees two function classes (bimorphic), in both arms alike.

The step-2 arm reproduces step 2's printed baselines (§2), so the approximation is close.

The harness floor is ~40 ns per op: a no-op `(identity x)` through the same loop. It is included in every cell, and dominates the single-read anatomy rows.

## 2. Step-2 baselines, re-measured in this session (step2 arm, run2)

| | step-2 REPORT | step2 arm, this session |
|---|---|---|
| guarded `(get @a k)`, 100 root, extent/op | 2.7 µs | 2.48–2.93 µs |
| guarded get, in an open extent | 1.5–1.8 µs | 1.45–1.69 µs |
| guarded deref alone, 100 | 1.4 µs | 1.30–1.47 µs |
| direct get, 100-map | ~866 ns (step 1, fixed `:k42`) | 1.03–1.10 µs (random key, incl. the ~40 ns floor) |

All four reproduce. Direct get reads slightly higher here: random-key selection and the harness floor are inside the cell.

## 3. Measured (run2, as printed: 5 rounds; bytes/op from the last round)

```
direct get, 100-map (rand kw)            step2  ["1.10 µs" "1.08 µs" "1.08 µs" "1.05 µs" "1.03 µs"]        2.8 KB
direct get, 100-map (rand kw)            step3  ["425.2 ns" "450.3 ns" "421.1 ns" "399.5 ns" "398.7 ns"]   72 B
direct get, 1k-map (rand int)            step2  ["1.27 µs" "1.26 µs" "1.24 µs" "1.20 µs" "1.26 µs"]        3.9 KB
direct get, 1k-map (rand int)            step3  ["453.2 ns" "423.3 ns" "408.0 ns" "413.9 ns" "452.6 ns"]   134 B
vec nth, 1k-vec, direct (rand)           step2  ["425.7 ns" "488.8 ns" "416.8 ns" "447.6 ns" "421.8 ns"]   1.5 KB
vec nth, 1k-vec, direct (rand)           step3  ["222.3 ns" "230.2 ns" "226.8 ns" "226.2 ns" "220.4 ns"]   293 B
guard (get @a k), 100, extent/op         step2  ["2.80 µs" "2.62 µs" "2.60 µs" "2.48 µs" "2.93 µs"]        4.9 KB
guard (get @a k), 100, extent/op         step3  ["2.17 µs" "1.98 µs" "1.96 µs" "2.18 µs" "1.91 µs"]        2.1 KB
guard (get @a k), 100, in open extent    step2  ["1.53 µs" "1.55 µs" "1.69 µs" "1.48 µs" "1.45 µs"]        3.2 KB
guard (get @a k), 100, in open extent    step3  ["781.1 ns" "680.9 ns" "850.2 ns" "795.0 ns" "717.0 ns"]   456 B
guard (get @a k), 1k, extent/op          step2  ["2.78 µs" "2.51 µs" "3.08 µs" "2.71 µs" "2.87 µs"]        5.9 KB
guard (get @a k), 1k, extent/op          step3  ["1.95 µs" "1.94 µs" "2.38 µs" "2.00 µs" "1.94 µs"]        2.2 KB
guard (get @a k), 10k, extent/op         step2  ["3.14 µs" "3.21 µs" "3.64 µs" "3.36 µs" "3.25 µs"]        6.4 KB
guard (get @a k), 10k, extent/op         step3  ["2.00 µs" "2.11 µs" "2.08 µs" "1.93 µs" "2.16 µs"]        2.2 KB
guard @a alone, 100                      step2  ["1.39 µs" "1.47 µs" "1.33 µs" "1.34 µs" "1.30 µs"]        2.0 KB
guard @a alone, 100                      step3  ["1.34 µs" "1.28 µs" "1.31 µs" "1.31 µs" "1.29 µs"]        2.0 KB
anatomy: portable-hash-bytes, 6 B        step2  ["260.2 ns" "241.4 ns" "251.7 ns" "244.3 ns" "249.7 ns"]   552 B
anatomy: portable-hash-bytes, 6 B        step3  ["58.5 ns" "55.6 ns" "57.8 ns" "54.9 ns" "74.5 ns"]        24 B
anatomy: node-u8 (node type)             step2  ["57.9 ns" "56.1 ns" "57.6 ns" "53.7 ns" "54.1 ns"]        72 B
anatomy: node-u8 (node type)             step3  ["48.4 ns" "46.6 ns" "46.8 ns" "44.5 ns" "47.1 ns"]        0 B
anatomy: node-i32, unaligned             step2  ["63.4 ns" "57.0 ns" "56.4 ns" "61.2 ns" "57.7 ns"]        128 B
anatomy: node-i32, unaligned             step3  ["48.2 ns" "49.5 ns" "50.2 ns" "45.2 ns" "45.1 ns"]        24 B
anatomy: node-i32, aligned               step2  ["65.5 ns" "67.4 ns" "63.9 ns" "63.8 ns" "61.7 ns"]        168 B
anatomy: node-i32, aligned               step3  ["53.1 ns" "49.4 ns" "44.9 ns" "48.5 ns" "51.7 ns"]        24 B
anatomy: hamt-get-bytes walk, :k42       step2  ["742.8 ns" "703.7 ns" "699.7 ns" "729.2 ns" "723.3 ns"]   2.2 KB
anatomy: hamt-get-bytes walk, :k42       step3  ["239.2 ns" "242.6 ns" "220.1 ns" "240.8 ns" "236.0 ns"]   72 B
harness floor: (identity x)              step2  ["41.1 ns" "40.9 ns" "38.4 ns" "39.0 ns" "39.0 ns"]        0 B
harness floor: (identity x)              step3  ["41.0 ns" "40.1 ns" "40.1 ns" "40.1 ns" "38.4 ns"]        0 B
=== ratio step2/step3 (median of rounds) ===
direct get, 100-map (rand kw)            1.08 µs -> 421.1 ns  (2.55x)
direct get, 1k-map (rand int)            1.26 µs -> 423.3 ns  (2.98x)
vec nth, 1k-vec, direct (rand)           425.7 ns -> 226.2 ns  (1.88x)
guard (get @a k), 100, extent/op         2.62 µs -> 1.98 µs  (1.32x)
guard (get @a k), 100, in open extent    1.53 µs -> 781.1 ns  (1.96x)
guard (get @a k), 1k, extent/op          2.78 µs -> 1.95 µs  (1.43x)
guard (get @a k), 10k, extent/op         3.25 µs -> 2.08 µs  (1.56x)
guard @a alone, 100                      1.34 µs -> 1.31 µs  (1.03x)
anatomy: portable-hash-bytes, 6 B        249.7 ns -> 57.8 ns  (4.32x)
anatomy: node-u8 (node type)             56.1 ns -> 46.8 ns  (1.20x)
anatomy: node-i32, unaligned             57.7 ns -> 48.2 ns  (1.20x)
anatomy: node-i32, aligned               63.9 ns -> 49.4 ns  (1.30x)
anatomy: hamt-get-bytes walk, :k42       723.3 ns -> 239.2 ns  (3.02x)
```

The anatomy rows carry the ~40 ns harness floor. Net of it, one node read is ~7–9 ns against ~16–24 ns at step 2. A probe at the REPL with a ~4–10 ns floor read `node-i32` at 14 ns and `portable-hash-bytes` at 21–31 ns.

**Summary**

- Direct get: **~2.6–3.0× faster**. Allocation fell from 2.8–3.9 KB to 72–134 B per op.
- Walk alone: **3.0×**, from 2.2 KB to 72 B.
- Guarded get in an open extent: **2.0×**, from 1.45–1.69 µs to 0.68–0.85 µs.
- Guarded get with an extent per op: **1.3–1.6×**, from ~2.6–3.3 µs to ~1.9–2.2 µs. The remainder is the extent itself: "deref alone" is unchanged at 1.3 µs (§5).
- Every row reached the 10² ns class; none reached the 10¹ ns class (§4).

## 4. The anatomy, piece by piece

| step-2 anatomy | what step 3 did | status |
|---|---|---|
| ISlabIO protocol dispatch per read | Node reads call `alloc/node-u8` / `node-i32` / `node-bytes-equal?` / `node-bytes`: one `instance? JvmSlabCtx` check (LoggingSlabIO unwrapped), the slab offset decoded inline with primitive math, no protocol hop. | **gone** on node reads |
| IMemRegion protocol dispatch per read | Collapsed to one region call per read, `eve.mem/-heap-bytes`, which returns the region's own `byte[]`. In the KV scan it happens once per node (`node-heap-array` / `node-base`, then `bytes-i32`). | **resists: one call remains** (wall 1) |
| volatile VarHandle load through a spread MethodHandle (Object[] alloc + boxing) | Plain array loads from the heap `byte[]`. Immutability licenses them: a published node is written before its pointer is published and read after the reader acquired that pointer (step 2). A transient's nodes are read by the thread that writes them. | **gone** for heap regions; mmap keeps the volatile load (below) |
| `read-u8` allocates a 1-byte `byte[]` | `node-u8` is one `aget`. | **gone** (0 B/op) |
| hash ~175–250 ns, boxed | `eve.int32` and the hamt-util bit helpers are primitive-hinted. `portable-hash-bytes` returns a primitive. Its tail uses `case` instead of `cond`, whose implicit nil tail boxed every hash. | **4.3×** (250 → 58 ns in harness, 21–31 ns at the REPL) |
| linear `skip-kv-at` scan in the node | The scan's `loop` sat in a let binding, so it compiled to a closure, allocated and boxed per lookup (the JFR top frame `hamt_get$fn`). It is now a primitive fn that scans the node's heap bytes in place, two plain loads per skipped pair. | **cheaper; the scan itself resists** (wall 2) |
| `not=` on hashes (JFR top frame `Util.equiv`, 131 samples) | `==` on primitives. | **gone** |
| header field read (`root-off`) via `-sio-read-i32` on every valAt | eve/deftype's field reads (`vocab/sio-read-expr`): an **immutable** `:int32` field reads through `node-i32`. Mutable and volatile-mutable fields keep the atomic read. | **gone** for immutable fields |

**Wall 1: the last region call (a dispatch the layering needs).**

- `JvmSlabCtx` lives in `alloc.cljc`, which is shared by every host and has no host conditionals. Its regions are adapter types (`eve.region` per host).
- The shared slab context therefore cannot name `JvmByteBufferRegion` or `ByteBuffer`. Reaching the bytes takes one protocol call into the host's region (`IHeapBytes`).
- Removing that call would mean host code inside the shared allocator, or a cache of the arrays that every growth path (`aset regions …` at alloc.cljc 357/422/682/698/704) would have to keep coherent.
- I stopped there. On the JVM that call is a monomorphic interface call that the JIT inlines; what remains is its `hasArray` and `arrayOffset` checks.

**Wall 2: the linear KV scan (the format needs it).**

- A bitmap node stores its KV pairs as variable-length `[key-len:i32][key][val-len:i32][val]` with no offset table. Reaching pair *di* means walking the *di* pairs before it.
- After step 3 this is the largest remaining frame in a JFR of the direct get: `kv_pos`, 288 of 862 top-frame samples.
- Removing it needs either a format change (an offset table) or a per-node position cache (new state). The first is out of scope. The second is state that the memory rules ("atoms all the way down") do not license without a ruling.
- I stopped there.

**mmap and other regions.**

- `-heap-bytes` answers nil for a mapped buffer (no backing array), for every other host's region (the `extend-default` nil), and for any sio that is not a `JvmSlabCtx` under logging wrappers (for example keyring's byte-transforming sio).
- Those keep the ISlabIO op unchanged, volatile load included.
- Disk and net kinds are untouched: same bytes, same layout, same path.

**Still on the old path (not hot-read path, untouched by design).**

- `-sio-read-i32` for words other threads write (tallies, descriptors, digest cache, `eve.array`, atom slots). It is still the volatile load through the spread MethodHandle, ~25 ns aligned.
- Write paths (assoc / builders) still read their source nodes partly through `slab/*`. Only `skip-kv-at` / `kv-pos` were switched.
- Value bytes are still copied into a fresh `byte[]` for the decoder (`node-bytes`, the region's bulk read, without the ISlabIO hop). That is `Numbers.byte_array` in the JFR. The decoder's API takes a byte array.

## 5. What "deref alone" is (unchanged, 1.3 µs)

- JFR of `(g/with-guard (deref a100))`: top frames are `Util.equiv`, `RT.get`, `clojure.core/hash-map`, `Util.hasheq`, `PersistentArrayMap.indexOfObject`, `PersistentHashMap.create` and `Var.pushThreadBindings`.
- That is the guard extent's own bookkeeping: binding the extent, building its hold/domain maps, and the pin keyed by thread id.
- The slab read in it (the slot's VarHandle load and `bb-load-i32`) is 28 of 710 top-frame samples.
- This is step 2's extent machinery, not the slab access path, so step 3 does not touch it. It is now the larger half of a guarded get with an extent per op: ~1.3 of ~2.0 µs.

## 6. Correctness gates

Run on the final code (fc9ad4b), one JVM, `clojure.test`:

```
GATE guard {:test 3, :pass 18, :fail 0, :error 0}
GATE atom+read-region {:test 45, :pass 221, :fail 0, :error 0}
EXTRA map/vec/hamt-util/last-level {:test 55, :pass 329, :fail 0, :error 0}
```

- The guard suite is `eve.guard-test`.
- Atom + read-region is `eve.jvm-atom-test`, `-cas-test`, `-lost-attempt-test`, `-retire-test` and `eve.read-region-test`. The atom suites include mmap-backed atoms, which exercise the fallback path.
- Extra: `eve.jvm-map-test`, `eve.jvm-vec-test`, `eve.hamt-util-test` and `eve.map-last-level-test`. These cover hash identity across hosts and the shift-30 node-bit rule.
- Bench checksums: EQUAL across arms in all 14 groups. That includes the hash value and the raw value bytes of the walk.
- Not run: the ClojureScript, bb, CLR and other hosts' suites (no toolchains in this container). The shared-code changes are portable by construction:
  - type hints;
  - `case` for `cond`;
  - the `extend-default` nil for `IHeapBytes`;
  - the node readers falling back to the ISlabIO op wherever there is no `JvmSlabCtx` or heap array.

  This has not been verified on those hosts.

## 7. Memory rules

- No journals, liveness scans, counters, arenas or caches were added.
- The heap array is asked of the region on each read (or once per node in the scan). Nothing is stored, so region replacement on growth needs no coherence.

## 8. Files

**Code**

- `adapters/int32/jvm/src/eve/int32.clj`: primitive hints.
- `src/eve/hamt_util.cljc`: primitive hints; hash tail `case`.
- `src/eve/mem.cljc`: the `IHeapBytes` protocol, nil by default.
- `adapters/region/jvm-varhandle/src/eve/region.clj`: JvmByteBufferRegion answers its heap `byte[]`.
- `src/eve/deftype_proto/alloc.cljc`:
  - `node-u8`, `node-i32`, `node-bytes-equal?`, `node-bytes`;
  - `node-heap-array` / `node-base` / `bytes-i32`.
- `src/eve/map.cljc`:
  - the read walk (hamt-get, hamt-get-bytes, skip-kv-at, key-bytes-match?) on node reads;
  - `kv-pos`;
  - `==` for `not=`.
- `src/eve/vec.cljc`: `node-get` and value-block length on `node-i32`.
- `src/eve/deftype_proto/vocab.cljc`: immutable `:int32` fields on `node-i32`.

**Bench and output**

- `doc/jvm-heap-grade/bench/hotpath.clj`
- `hotpath-run2.out` (quoted above)
- `hotpath-run1.out`

**Not written:** STATUS.md (operator's rule: status goes in the session transcript).
