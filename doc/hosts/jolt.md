# eve on jolt

jolt (`hosts.edn :jolt`) is Clojure compiled to Chez Scheme. Its core is Clojure's and its `deftype` takes the JVM's
interfaces, so eve's types, collections and `clojure.core` vocabulary are the JVM's adapters as written; what is jolt's
own is the memory. A region is memory at a fixed address — a file mapped `MAP_SHARED`, or an immobile bytevector in
Chez's heap — reached through jolt's FFI: on Linux with the system libatomic's atomics and futex(2) waits (x86-64 and
arm64), on macOS with libSystem's OSAtomic functions and polled waits (100 µs, as the JVM's mapped region and eve's
Node addon there). jolt needs a threaded Chez (`./configure --threads`); its release builds (Homebrew's
`jolt-lang/jolt/jolt`, macOS arm64) carry their own.

```bash
export PATH=/opt/eve-tools/jolt/bin:/opt/eve-tools/chez/bin:$PATH JOLT_CHEZ=/opt/eve-tools/chez/bin/scheme
bb test:jolt                   # the battery (named namespaces after the task run alone)
jolt -Scp "<src:hosts.edn :jolt roots:test>" -m eve.runner.jolt-main perf   # the performance suite
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:jolt` | **199 tests, 4,479 assertions, 0 failures, 0 errors** (203 s, peak 1.7 GB, the process tree) | 2026-10-21, this tree (the final pass, `doc/release-0.9/logs-2026-10-22/f-jolt.log`) |
| `bb test:jolt` beside `bb test:glj` | both green, the same counts; peaks 1.7 GB and 2.7 GB (the process trees; `f-side-*.log`) | 2026-10-22, this tree (the final pass) |
| `bb scripts/thread-rendezvous.clj jvm jolt` | 18 of 18 checks (44 s) | 2026-10-22, this tree (the final pass) |
| `bb test:jolt` | **185 tests, 2,887 assertions, 0 failures, 0 errors** (233 s, peak 1.9 GB, beside basilisp's battery) | 2026-10-20, the final sweep |
| `bb test:jolt` | **178 tests, 2,087 assertions, 0 failures, 0 errors**; peak 1.9 GB resident, beside `bb test:glj` (the pair 245 s) | 2026-10-20, earlier in this round |
| the same, before the region fix | the same counts; peak 5.8 GB, and OOM-killed beside `bb test:glj` | 2026-10-20 |
| `bb scripts/thread-rendezvous.clj jvm jolt` | 18 of 18 checks | 2026-10-19 |

The runner (`test/eve/runner/jolt_main.clj`) runs the HAMT helpers, the byte-format fixtures, type ids, the red-black
set, int-map, sorted collections, core.rrb-vector's whole suite, big values, value buffers, the key ring, the numeric
and reference tags, collection hashing, the vector retire walk, domain open (both added to the runner in this round:
they were left out without a reason), and the thread feature (the conformance, eve.thread, the meta-atom, the domain
cell, fold, fold on a group). Not run, each for a reason: the typed-array suites (coalesc-align, array-hosts, dataset,
tensor — jolt has no typed-array adapter). Every other
namespace babashka's runner runs, jolt's runs.

## Performance

<!-- perf:jolt (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 160.8 µs | 8192 | 14 |
| `atom-deref-small` | 17.2 µs | 65536 | 1.0 |
| `atom-swap-map-1k` | 47.3 µs | 32768 | 10 |
| `atom-deref-map-1k` | 4.7 ms | 256 | 12 |
| `atom-$-get-map-1k` | 25.0 µs | 65536 | 12 |
| `atom-reset-vec-1k` | 16.5 ms | 64 | 11 |
| `sorted-set-build-200` | 182.8 ms | 8 | 16 |
| `int-map-build-200` | 81.4 ms | 16 | 18 |
| `disk-swap-counter` | 177.9 µs | 8192 | 10 |
| `thread-submit-64` | 2.2 ms | 512 | 2.2 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:jolt -->

Reading it: reads near the JVM's (a small deref 17.2 µs), writes ~10–14× (a counter swap 161 µs: every region access is
a foreign call and a CAS allocates its witness), and real threads (64 tasks in 2.2 ms, Chez threads).

What sets jolt's numbers:

- **Every region access is a foreign call.** A load, a store, a CAS is a call into libatomic through Chez's foreign
  procedures, and a CAS also allocates and frees an 8-byte witness for the value it saw. Scanning a full heap class
  word by word was a foreign call a word; eve's allocator now scans its bitmaps in bulk (`alloc.cljc`), which took
  core.rrb-vector's test-splicing on jolt from over 10 minutes to 24 s.
- **Start-up compiles eve**: ~18 s of each start is compiling eve's sources.

## How eve is built on jolt

| Feature | Adapter | How |
|---|---|---|
| region | `region/jolt-ffi` | `JoltRegion` [base size backing]. Linux: `__atomic_{load,store,fetch_add,exchange,compare_exchange}_{4,8}` from `libatomic.so.1`, seq-cst; futex as `syscall` (202 on x86-64, 98 on arm64; another architecture polls), declared blocking so the collector runs while a thread waits. macOS: `OSAtomicCompareAndSwap{32,64}Barrier` and `OSAtomicAdd{32,64}Barrier` from libSystem, the load, store, exchange and value-answering CAS built over them; a wait polls the word every 100 µs and a notify wakes no one (`doc/release-0.9/logs-2026-10-22/probes/jolt_region_mac.clj` runs that arm on Linux over shims). Both: `open` (variadic, for Apple arm64's convention) / `ftruncate` / `mmap` / `munmap` through the FFI, `O_CREAT` the platform's. Where alignment is strict (macOS, arm64 — their atomics fault at an address not a multiple of the word's width) a load or store at an unaligned address is a plain one: eve's codec reads and writes words at any byte of a block (an eve-media domain's open makes 57 such accesses, `probes/jolt_region_alignment.clj`), and its read-modify-writes are on aligned words; `EVE_JOLT_STRICT_ALIGNMENT=1` takes that path on x86-64 too. A **heap region is an immobile bytevector** in Chez's heap (never moved, so its address holds), held by the region and reclaimed with it; a **mapped region** is unmapped by a Chez guardian once the collector finds it unreachable |
| completion | `completion/jolt` | re-roots `swap!`, `reset!`, `compare-and-set!` (jolt casts any atom to its own), `into` (an editable eve type goes through its transient), `chunk-first` / `chunk-rest` / `chunk-next`, and `replace`'s transducer arity |
| coll | `coll/jvm` | the JVM's adapter; its own `seq` and `into`, because jolt's `seq` is a native op that wraps a type's seq in jolt's cells and its `into` folds a type with persistent `conj` (measured) |
| types | `types/jvm` (+ `types/js`) | the JVM's: jolt's `deftype` takes the JVM's interfaces; `print-form` prints jolt's own way (no `print-map`) |
| encoding | `encoding/java` | the JVM's, with two jolt arms: two's-complement bytes by arithmetic (jolt's BigInt has no `toByteArray`), `BigDecimal` by `movePointLeft` (jolt's ignores the scale) |
| thread | `thread/jvm` | the JVM's adapter on jolt's threads (Chez threads); `:wake :futex` on jolt's regions |
| core, slab, slab-io, int32, codec, process | `core/clojure`, `slab/heap`, `slab-io/protocol`, `int32/jvm`, `codec/byte-array`, `process/java` | as the JVM's |
| crypto | `crypto/portable` | `eve.net.portable-crypto`: jolt has no crypto library binding in eve |
| http | `http/socket` | HTTP/1.1 written and read over jolt's `java.net.Socket` (jolt.socket, the host's C sockets): one connection a request, a chunked answer joined, http:// only — enough for a broker or gateway on the machine or the LAN; https:// answers `{:error …}` |

## Gotchas

- **eve's heap domains are Chez heap memory** (since 2026-10-20): they count against jolt's heap ceiling —
  `JOLT_MAX_HEAP`, else 25% of physical RAM, the JVM's `MaxRAMPercentage` — exactly as the JVM's heap domains count
  against `-Xmx`. A process holding more in-process domains than the ceiling allows fails as an out-of-memory, not by
  growing past it. (Before, they were foreign memory, never released: every heap domain a process dropped stayed
  resident.)
- **The completion reaches only what is compiled after it**, and `seq` is a native op it cannot reach: call
  `eve.core`'s `seq` on eve values where a lazy walk matters.
- **Network atoms over http:// only** (`http/socket`: no TLS) and **the portable crypto only** — the reference implementation, not
  constant-time and not audited ([SECURITY.md](../../SECURITY.md)).
- **No Lustre regions** (`:lustre? true` throws).
- `PATH` and `JOLT_CHEZ` must name the jolt and threaded Chez to use (`/opt/eve-tools` on the release box).

## Future directions

Optimization:

- **Inline the loads and stores.** Every load or store through libatomic is a foreign call. Chez's `foreign-ref` /
  `foreign-set!` are inline primitives on an address (an aligned word is read and written whole on x86-64); with a
  fence where eve needs sequential consistency, a plain load or store would make no foreign call. CAS, add and
  exchange stay libatomic's: Chez has no primitive compare-and-swap on raw memory (`box-cas!` and `vector-cas!` take
  Scheme objects).
- **No witness allocation per CAS**: a thread-local 8-byte witness (the region's, per thread) instead of
  `ffi/alloc` + `free` on every compare-and-swap.
- Ship an eve program as a `jolt build -m <ns>` binary (jolt's compiled executable, its boot a prebuilt heap image)
  rather than loading eve's sources, so the ~18 s compile happens at build time, once; not yet measured with eve.

Coverage:

- https:// for the http feature: libcurl through `jolt.ffi`, or jolt's `java.net.http` if it grows one.
- A typed-array adapter: Chez bytevectors and its `fxvector` / `flvector` are the natural backing.
- A native crypto adapter (libcrypto through the FFI), as basilisp's does through ctypes.
