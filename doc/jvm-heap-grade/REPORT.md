# JVM heap-grade, step 1: REPORT (STOP)

**Declaration: STOPPED at step 1.1. Stop condition: "the baseline does not reproduce".**
I banked the baseline. I did not start the design (DESIGN.md) or the prototype.
The rest of this report is what I measured, and why it changes what step 1 should build.

- Branch: `claude/eve-jvm-heap-grade` on SeniorCareMarket/eve
- Base: `origin/main` @ `d241de3fbc27e5dd80d5f1e17661103b0680b9c0`, verified with `git ls-remote` at 11:51 ET on 2026-11-05 before I branched
- Seat: JVM. OpenJDK 21.0.12, Clojure 1.12.0, 4 cores, load average about 0.3 (not a loaded box)
- Bench: `doc/jvm-heap-grade/bench/baseline.clj`. All arms ran in one session, interleaved: each round runs every arm once, 3 rounds, and each cell is that round's minimum over 3 batches.
- Raw output: `doc/jvm-heap-grade/bench/baseline-run1.out`

## 1. Baseline as printed (one session, 3 interleaved rounds)

```
eve  map get, 100-map, direct (:k42)           ["810.1 ns" "840.5 ns" "768.3 ns"]
stock map get, 100-map (:k42)                  ["33.8 ns" "33.4 ns" "33.8 ns"]
eve  map get, 1k-map, direct (rand int key)    ["1.08 µs" "1.07 µs" "985.6 ns"]
stock map get, 1k-map (rand int key)           ["54.4 ns" "55.6 ns" "54.7 ns"]
eve  vec nth, 1k-vec, direct                   ["318.5 ns" "328.6 ns" "326.9 ns"]
stock vec nth, 1k-vec                          ["34.0 ns" "36.9 ns" "34.6 ns"]
eve  (get @atom k), 100-map root               ["55.90 µs" "64.82 µs" "53.60 µs"]
eve  $ (get @atom k), 100-map root             ["1.86 µs" "2.04 µs" "1.91 µs"]
stock (get @atom k), 100-map root              ["37.4 ns" "43.4 ns" "41.0 ns"]
eve  deref scalar root                         ["641.8 ns" "610.3 ns" "599.9 ns"]
stock deref scalar                             ["27.5 ns" "30.0 ns" "26.2 ns"]
eve  deref 100-map root                        ["63.17 µs" "53.12 µs" "48.56 µs"]
eve  swap! inc                                 ["24.71 µs" "21.33 µs" "22.25 µs"]
stock swap! inc                                ["33.3 ns" "32.5 ns" "31.5 ns"]
eve  swap! assoc, 100-map root                 ["185.24 µs" "165.54 µs" "139.44 µs"]
stock swap! assoc, 100-map root                ["81.4 ns" "92.2 ns" "92.9 ns"]
eve  map assoc, 1k-map, direct (rand key)      ["4.48 µs" "3.97 µs" "4.10 µs"]
stock map assoc, 1k-map (rand key)             ["134.6 ns" "111.0 ns" "123.2 ns"]
=== capacity (default make-jvm-heap-slab-ctx: 6×1 MB bitmap classes + 64 KB coalescing class) ===
map, 1000 int entries                          [:ok 1000]  (10 ms)
map, 5000 int entries                          [:FAIL "coalesc-alloc!: out of memory"]  (55 ms)
map, 10000 int entries                         [:FAIL "coalesc-alloc!: out of memory"]  (31 ms)
vec, 1000 int elements                         [:ok 1000]  (13 ms)
vec, 10000 int elements                        [:ok 10000]  (30 ms)
vec, 30000 int elements                        [:FAIL "coalesc-alloc!: out of memory"]  (53 ms)
vec, 100000 int elements                       [:FAIL "coalesc-alloc!: out of memory"]  (55 ms)
stock 10k-entry map build: 1.52 ms
```

### Against the spike (HEAP-SPIKE.md §P3)

| op | spike | measured here | verdict |
|---|---|---|---|
| map lookup | ~119–134 µs | **0.77–1.08 µs** on the collection (`(get @atom k)` is 54–65 µs) | **does not reproduce.** About 2 orders lower. The 10² µs class appears only when the lookup goes through a deref, which materializes the whole root. |
| vec nth | (not given) | 0.32 µs | — |
| atom deref, scalar root | ~104 µs | **0.60–0.64 µs** | **does not reproduce.** About 2 orders lower. |
| deref, 100-entry map root | ~1.78 ms | **49–63 µs** | **does not reproduce.** About 1.5 orders lower. |
| atom swap! inc | ~178 µs | 21–25 µs | Same class (10¹–10² µs). Reproduces. |
| swap! on a 100-map root | ~199 µs | 139–185 µs (swap! assoc) | Reproduces. |
| map assoc | 0.5–5 ms each | **~4 µs** on the collection | **does not reproduce.** 2–3 orders lower. |
| capacity wall | 10k map and 1k vec both die | the map dies between 1k and 5k entries; the vec survives 10k and dies by 30k | **Map wall reproduces. The vec claim does not** (1k vec is fine). |

## 2. The spike's root cause does not match the code

The dispatch, citing the spike, says: "Eve's JVM collections deserialize on access … a lookup decodes."
The code and the measurements say otherwise for both the map and the vector:

- `hamt-get` (`src/eve/map.cljc:430`) walks the stored HAMT where it sits in the slab. It reads the bitmap, hashes the key bytes, follows child pointers and compares the stored hash, then compares the key bytes in the slab with `bytes-equal-at?`. The only decode is the matched value's bytes, once per lookup.
- `vec` `nth-impl` (`src/eve/vec.cljc:115`) also walks the trie in place.
- I broke down the 100-map direct `get` (about 800 ns) in the same session:

```
encode-key :k42                 35.6 ns
portable-hash-bytes             179.2 ns   ← Murmur3 over 6 key bytes
read-i32 (one field read)       46.5 ns    ← one slab read via ISlabIO
read-u8 (node type)             30.7 ns    ← allocates a 1-byte byte[] (alloc.cljc JvmSlabCtx -sio-read-u8)
hamt-get-bytes (walk, no decode) 709.0 ns
decode value bytes              39.1 ns    ← the "deserialize" is ~5% of the lookup
full get                        865.6 ns
```

The cost is the access path, not decoding. A lookup makes about 10–15 slab reads. Each one goes through:

- `ISlabIO` protocol dispatch, then
- a class/block decode and an `aget` on the region array, then
- `IMemRegion` protocol dispatch, then
- a **volatile** VarHandle load through a spread MethodHandle (`adapters/region/jvm-varhandle/src/eve/region.clj:61-94, 162`).

On top of that come a linear `skip-kv-at` scan inside the node and a Murmur3 hash that costs 175 ns for 6 bytes, which looks boxed or unhinted. `read-u8` allocates a `byte[]` on every call.

The stored format already supports in-place reads. The "typed-view-style navigation of the stored form" that step 1 was to design is, in mechanism, what the code already does. What the JVM seat lacks is a cheap way to perform each of those reads.

## 3. The capacity wall: mechanism, with evidence

`make-jvm-heap-slab-ctx` (`src/eve/deftype_proto/alloc.cljc:755-774`) builds:

- six bitmap classes of 1 MB each, held in heap regions with no backing file, and
- a 64 KB coalescing class 6.

Two separate allocation paths reach class 6, and they behave differently:

- **Large blocks (>1024 B)** go straight to class 6 through `try-coalesc-alloc!`. When that fails, `grow-coalesc-region!` runs and a heap region is copied into a larger one (`alloc.cljc:333-366`). **This path grows.**
- **Spills** happen when bitmap classes fill. A heap bitmap class cannot grow: `grow!` needs a file path (`try-alloc` skips full heap classes, `alloc.cljc:372-379`). Allocation then spills class by class to class 5, and then to class 6 through **`coalesc/coalesc-alloc!` (`alloc.cljc:459-465`)**. That call has no growth loop and throws `coalesc-alloc!: out of memory` (`coalesc.cljc:422-426`). **This path does not grow.**

So the wall is two specific gaps:

1. Heap bitmap classes cannot grow.
2. The spill-to-class-6 path skips the growth that already exists for direct class-6 allocations.

A HAMT is mostly small nodes. It fills the ≤1 KB classes, spills, and dies when the spilled data reaches about 64 KB.

## 4. Why this is a stop and not a redirect

Two conditions in the dispatch apply:

- **The baseline does not reproduce.** Lookup, deref, deref-of-map and assoc are 1.5–3 orders below the spike's figures, on a quiet box, with stock controls in the same session.
- **Step 1 is aimed at a mechanism the code does not have.** Its target was in-place reads to replace decode-on-access. Building it would re-implement reads that are already in place.

The dispatch says to report what I measured rather than proceed. Choosing the new step-1 target is the operator's decision. These are the options the measurements support, none of which I started:

- **(a) A hot read path for heap-domain collections. No format change.** Published nodes are immutable, so reads can use plain (non-volatile) loads. Add a byte[]-direct fast path that skips both protocol hops when the region is a `JvmByteBufferRegion`. Use a primitive, hinted Murmur3. Make `read-u8` allocation-free. Cache each node's KV positions or replace the linear `skip-kv-at`. Reaching the ~10¹ ns class from ~10³ ns is plausible here, but unproven. The disk/net kinds are untouched: same bytes, same layout.
- **(b) Deref semantics.** The atom-level 10⁴–10⁵ ns costs (`(get @a k)`, deref of a map root) come from `plain-deref` materializing through `d/eve->clj` (`src/eve/atom.cljc:820-826`). That is a contract question (`$` / region-deref already returns the lazy value at ~2 µs), not a collection-read question. It needs a ruling on what a heap-atom deref returns before anyone builds it.
- **(c) Growth.** Close the two gaps in §3: let heap bitmap classes grow (a new, larger heap region, the way class 6 already does it), and route the spill path through the existing growth loop. The tier naming the dispatch asked for (L1 JVM heap regions → L2 mmap'd file regions → L3 disk/net) belongs in that design.

swap! (~22 µs inc, ~140–185 µs assoc on a 100-map root, against ~30–90 ns stock) reproduces as the spike described. It is write-path work, which is out of scope for step 1.

## 5. Notes on the run

- The Clojure CLI installer was blocked by the session's sandbox policy (it runs a remote install script). I fetched the deps.edn Maven jars with `mvn dependency:get` and ran a hand-built classpath (deps.edn `:paths`) under `clojure.main`, with a socket REPL for the probes. Nothing was shrunk because of this.
- Eve's collection test suite was not run: no prototype exists for it to check.
- Files on the branch:
  - `doc/jvm-heap-grade/STATUS.md`
  - `doc/jvm-heap-grade/REPORT.md` (this file)
  - `doc/jvm-heap-grade/bench/baseline.clj`
  - `doc/jvm-heap-grade/bench/baseline-run1.out`

---

# STEP 2: the automatic guard vs whole-root materialization (REPORT)

**Declaration: STEP 2 COMPLETE. No wall hit.**

- Branch `claude/eve-jvm-heap-grade` on SeniorCareMarket/eve, on top of step 1 (f97dde6).
- Seat: JVM. OpenJDK 21, Clojure 1.12.0, 4 cores. Fresh container; same Maven-jar classpath as step 1.
- Bench: `doc/jvm-heap-grade/bench/guard.clj`. One JVM, 47 arms × 3 interleaved rounds; each cell is the round's min over 3 batches of ~40 ms; bytes/op come from ThreadMXBean.
- Banked output: `doc/jvm-heap-grade/bench/guard-run2.out`. Checksums are EQUAL across arms in all 14 groups.
- `guard-run1-noop-swap.out` is the previous run. It is kept because its same-domain swap arms measured swap!'s "same pointer, nothing to publish" path by mistake. run2 alternates two subtrees.

## 1. What was built (prototype grade)

- **`src/eve/guard.clj`**: the guard extent, `guard*` / `with-guard`, with an optional receiving-domain state.
  - A **chain of cells**: each extent's cell is a volatile that links to the enclosing extent's cell. A lazy value is valid only while every cell on its chain is open.
  - Proxies: `LazyMap` (ILookup, Associative, IPersistentMap, Counted, Seqable, IKVReduce, IFn, IHashEq) and `LazyColl` (vec/set/list: Indexed, ILookup, Counted, Seqable, IReduceInit). Every access checks the chain.
  - Scalar access delegates to the slab value's own in-place navigation (hamt-get / nth) and decodes only the leaf. Collection results come back as child proxies on the same cell.
  - `materialize` is the one explicit materialization point.
  - The proxies keep `IEveRoot` / `IEveSio`, so `swap!` sees the source pointer.
  - Pins are taken once per domain per extent chain and released, last first, by the extent that took them.
- **`src/eve/atom.cljc`** (hooks are `:clj` only; the cljs path is unchanged):
  - `mmap-deref` checks, in order: `$` → region-deref; **guard extent → `guard-deref`**; otherwise plain-deref (unchanged).
  - `guard-deref` is the **domain-aware read-root-value**. It pins through `guard/hold!` (the pin is owned by the extent that receives it) and returns `read-root-value` at the slot pointer, behind the proxy. When `eve.guard/*proxy-same-domain?*` is false and the atom is in the extent's receiving domain, it returns the raw slab value.
  - **`resolve-new-ptr`'s foreign-domain throw (was atom.cljc:752) is now per-domain policy.** Domain state carries `:foreign-policy` (an atom: `:throw`, the default, or `:copy`, which materializes under the reader's pin and then writes), set by `eve.atom/set-foreign-policy!`.
- **`test/eve/guard_test.clj`**: 3 tests, 18 assertions, pass. The existing atom and read-region suites (`eve.jvm-atom-*`, `eve.read-region-test`: 45 tests, 221 assertions) pass with the hooks in.
- **Memory rules:** no journals, liveness scans, counters or arenas. A cell is a volatile; an extent's holds are a vector in one atom; pins reuse the domain's existing epoch pin.

## 2. Step-1 numbers, reproduced in this session

| | step 1 | step 2 (run2, rounds 2–3) |
|---|---|---|
| plain `(get @a k)`, 100 root | 53.6–64.8 µs | 57.5–58.6 µs |
| plain `(get @a k)`, 1k root | 342–363 µs (probe) | 375–378 µs |
| plain deref, 100 root | 48.6–63.2 µs | 58.3–65.3 µs |
| `$ (get @a k)`, 100 root | 1.86–2.04 µs | 2.49–2.51 µs |

All four reproduce.

## 3. Measured (run2: ns/op as rounds 2–3, bytes/op from round 3)

**`(get @a k)`**

| root | plain | `$` (manual) | guard, extent per op | guard, in an open extent | read-pinned floor |
|---|---|---|---|---|---|
| 100 | 58 µs · 118 KB | 2.5 µs · 4.4 KB | **2.7 µs · 4.6 KB** | 1.5 µs · 2.8 KB | 2.0 µs |
| 1k | 375 µs · 955 KB | 2.6 µs · 5.5 KB | **2.7 µs · 5.6 KB** | 1.6 µs · 3.9 KB | 2.1 µs |
| 10k | 3.7 ms · 8.5 MB | 2.9 µs · 5.8 KB | **2.9 µs · 5.9 KB** | 1.8 µs · 4.3 KB | 2.3 µs |

**Deref of the root alone**

- plain: 58 µs / 376 µs / 3.7 ms
- guard: **1.4 µs at all three sizes**, 2.1 KB

**Sparse read: 8-key sum from one deref**

| root | plain | `$` | guard |
|---|---|---|---|
| 100 | 59 µs | 9.6 µs | **9.8 µs** |
| 1k | 380 µs | 11.2 µs | **11.4 µs** |
| 10k | 3.7 ms | 11.0 µs | **11.2 µs** |

**Full traversal: reduce-kv sum over all entries (the weak case)**

| root | plain | `$` | guard |
|---|---|---|---|
| 100 | 59 µs | 33 µs | 32 µs |
| 1k | 390 µs | 218 µs | 230 µs |
| 10k | 4.1 ms | 2.05 ms | 2.2 ms |

Allocation is about 0.8 KB per entry for both `$` and the guard.

**Against the spike (the ratios are the claim)**

| | spike | here |
|---|---|---|
| 10k root, plain ÷ guard | 15,111× | **1,290×** |
| 10k root, manual ÷ guard | 1.95× | **1.0×** |
| 100 root, plain ÷ guard | 110× | **22×** |
| 100 root, manual ÷ guard | 0.89× | **0.94×** |
| sparse access vs manual `$` | tracks it, flat in root size | **reproduces**: tracks within noise, flat 100 → 10k |
| full traversal vs manual `$` | ~4× | **does NOT reproduce: guard ≈ `$`** |
| full traversal vs plain | ~2.5× better | ~1.8× better |

The spike's absolute plain costs were inflated (step 1 §1). The ratios to plain are therefore smaller here, but still 1–3 orders of magnitude.

## 4. The same-domain arm (decisive)

Setup: a nested 100-entry root `aN` and a receiving atom `bN`. Both are anonymous heap atoms, which share one domain (their sio is `identical?`).

**Pointers**

- The guarded `@aN` is a `LazyMap` with header **517 == aN's slot pointer 517**, and its sio is `identical?` to the atom's.
- The raw domain-aware `@aN` is an `EveHashMap` with header **517**.
- Today's plain deref returns a PersistentHashMap: a full copy.

**Store: `(swap! bN (fn [_] (get @aN k)))`**

| path | bN slot pointer | result | cost | allocated |
|---|---|---|---|---|
| plain | 530 | **copy** | 4.0 ms | 9.4 MB/op |
| `$` | **108 == aN's subtree pointer** | shared | 49–54 µs | 110 KB |
| guard, proxied | **108** | shared | **14–15 µs** | 21 KB |
| guard, raw | **108** | shared | **14–15 µs** | 22 KB |

`$` is slower here because it materializes swap!'s return value when the region exits.

**Read: `(get-in @aN [k :tags 1])`**

- plain: 3.7 ms
- `$`: 4.1 µs
- guard, proxied: 4.2–4.5 µs
- guard, raw: 4.3–4.5 µs

**Does today's code do this?** Yes, but only under the manual `$`. region-deref returns `read-root-value` at the slot pointer, and resolve-new-ptr stores a value from the same sio-root by its own pointer. Outside `$` (plain-deref), every read and every store materializes and copies.

**Does the same-domain case need the guard?**

- It does **not need the proxy** for pointer equality or for avoiding materialization. Those come from the domain-aware read path. Raw and proxied measure the same, the proxy cost is within noise, and both share the pointer.
- It **does need the extent**: the pin, which keeps the read blocks alive.
- Better production shape: a domain-aware `read-root-value` behind the deref inside an extent, as built here in `guard-deref`.
- Keep the proxy anyway. It costs nothing measurable, and it is the only thing that turns a value escaping its extent into a loud error instead of a silent read of freed blocks.

## 5. What remains unguarded or materializing

- **Every deref outside an extent** still materializes the whole root (plain-deref, unchanged). The guard is "automatic" only where a crossing opens an extent.
- **compare-and-set!** materializes the current root on every attempt (`(d/eve->clj cur)` in mmap-compare-and-set!).
- **`$`** materializes its result on exit. The registry read uses plain-deref.
- **`materialize` / `eve->clj` on nested data is dominated by `(satisfies? d/IPlain v)`.** In the REPL, that call costs ~19 µs on an `EveVector`, against a 3 µs seq of the same vector. A nested 100-entry root therefore derefs in ~3.7 ms, against ~58 µs for a flat one. This is a cheap, separate fix: an instance? check on the protocol interface. Out of scope here.
- **Full traversal** stays O(N), because every leaf is decoded. That is inherent: the guard removes the copy, not the visit.
- **Proxy interception gaps:**
  - assoc/assocEx/dissoc/conj/empty throw (read-only). Write through swap!, or materialize first.
  - Printing shows the deftype.
  - `vector?`, `set?` and `sequential?` are false on LazyColl (`map?` is true on LazyMap).
  - The seq of a proxy is a lazy seq whose steps are checked: realizing it after close throws.
  - JVM only: no cljs, bb or basilisp branch.
  - `=` and `hash` delegate to the slab value's in-place equiv/hash, not to identity as in the spike.
- Decoded scalars, including strings, are heap objects and escape safely.

## 6. Pin-lifetime hazards

1. **An open extent holds its epoch, so nothing retired after it can free.** Measured: 100 `swap! assoc` with a guard extent open left **99 blocks on the zero queue after a flush**, and **0** once the extent closed. Under write load, a long-lived extent grows memory without bound. Extents must be scoped to a crossing (a request or message), never to a session.
2. **Pins are keyed by thread id.** `pin-epoch!` / `unpin-epoch!` key the domain's `:pins` by `proc/thread-id`.
   - `guard*` takes and releases its pin in try/finally on one thread, so the prototype is safe.
   - In the production shape, "the pin is owned by the receiving domain's root lifecycle". If that lifecycle closes on another thread (an async crossing), it would unpin the wrong entry and leak the epoch, which is hazard 1 for good.
   - Production needs a pin that the extent owns, not one keyed by thread.
3. **Cross-thread reads inside the extent are fine.** The validity check is on the cell, not the thread, and the domain slot's pin covers every thread in the process. After close, they throw.
4. **Raw mode** (`*proxy-same-domain?*` false) has no check. A raw value used after its extent closes is a silent use-after-free read. This is why the proxy is recommended even in the same domain.
5. **A `:copy` foreign store materializes under the reader's pin**, inside the swap!, so it is safe. Its cost is the full materialization of the subtree that crosses.

## 7. Verdict

- **Sparse reads collapse.** The guard turns whole-root materialization into the navigation class: ~2.7–2.9 µs with an extent per op, ~1.5–1.8 µs inside an open one. That is flat from 100 to 10k entries and equal to manual `$`.
- **Same-domain:** pointers are identical and nothing is materialized. That property belongs to the domain-aware read path, not the proxy. The guard's job there is the pin's lifetime and the escape check.
- **Full traversal does not collapse.** It is ~1.8× better than plain and equal to `$`.
- **Still materializing:** plain deref outside extents, compare-and-set!, `$`'s exit, and `eve->clj`, whose protocol-check cost dominates nested materialization.
- **Hazards:** epoch retention while an extent is open, and the thread-keyed pin under an async lifecycle.
- **Files:**
  - `src/eve/guard.clj`
  - `src/eve/atom.cljc` (hooks)
  - `test/eve/guard_test.clj`
  - `doc/jvm-heap-grade/bench/guard.clj`
  - `guard-run2.out` (banked)
  - `guard-run1-noop-swap.out` (superseded)
