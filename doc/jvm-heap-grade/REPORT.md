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
