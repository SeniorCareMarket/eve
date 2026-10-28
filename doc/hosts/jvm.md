# eve on the JVM

The JVM (`hosts.edn :jvm`) is eve's reference host: Clojure 1.12 on JDK 21, every feature eve has, hardware atomics on
heap and mapped memory, and the largest test battery. The other hosts are measured against it.

```bash
clojure -M:jvm-test          # the battery (cognitect's test-runner over test/)
clojure -M:perf              # the performance suite (test/eve/perf_suite.cljc)
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `clojure -M:jvm-test` | **546 tests, 9,615 assertions, 0 failures, 0 errors** (136 s, peak 3.4 GB, the process tree; the same tests counted 9,634 assertions in 157 s earlier that evening — the fuzz test asserts per generated case) | 2026-10-21, this tree (the final pass) |
| `clojure -M:jvm-test` | **522 tests, 8,078 assertions, 0 failures, 0 errors** (163 s, peak 3.4 GB resident; 364 s before the reflection fix) | 2026-10-20, the final sweep |
| `clojure -M:jvm-test` | **520 tests, 7,964 assertions, 0 failures, 0 errors** (6 min 04 s) | 2026-10-20, the 0.9 release preparation's final tree |
| `bb scripts/thread-rendezvous.clj jvm <host>` | 18 of 18 checks with jvm, nbb, bb, clje, cljr, jolt, lpy and glj, and with nbb, bb and glj as the first member (14–133 s a pair; `f-rv-*.log`) | 2026-10-22, this tree (the final pass) |
| `bb scripts/thread-rotation.clj jvm glj bb nbb clje` | every turn's sum right, one registry (64 s, `f-rotation.log`) | 2026-10-22, this tree (the final pass) |
| `bb scripts/thread-rendezvous.clj jvm <host>` | 18 of 18 checks with jvm, glj, and each extended host it was paired with | 2026-10-19 / 2026-10-20 |
| `bb scripts/thread-rotation.clj jvm glj bb nbb clje` | every turn's sum right, one registry | 2026-10-20 |

What the battery covers: every namespace under `test/` the JVM loads, `.clj` and `.cljc` — the shared suites every host
runs, eve.net's fourteen suites (the network atom, gateways, the crypto primitives), and fifteen JVM-only files
(`test/eve/jvm_*_test.clj`: the mmap domains, fuzzing, contention). The assertion count moves from run to run:
`jvm-fuzz-test` asserts once per generated case.

What it does not run: the 25 `.cljs` test files (they are ClojureScript's), `eve.slab-io-test` (it needs the
`adapters/slab-io/test` root on the classpath), and `test-e2e/` (`clojure -M:jvm-e2e-test`). The byte-format fixtures
read all 71 rows on the JVM, none skipped.

## Performance

<!-- perf:jvm (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 11.8 µs | 131072 | 1.0 |
| `atom-deref-small` | 17.1 µs | 65536 | 1.0 |
| `atom-swap-map-1k` | 4.7 µs | 262144 | 1.0 |
| `atom-deref-map-1k` | 377.7 µs | 4096 | 1.0 |
| `atom-$-get-map-1k` | 2.1 µs | 524288 | 1.0 |
| `atom-reset-vec-1k` | 1.5 ms | 1024 | 1.0 |
| `sorted-set-build-200` | 11.1 ms | 128 | 1.0 |
| `int-map-build-200` | 4.5 ms | 256 | 1.0 |
| `disk-swap-counter` | 17.6 µs | 65536 | 1.0 |
| `thread-submit-64` | 985.4 µs | 2048 | 1.0 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:jvm -->

Reading it: the fastest host on every workload but the small deref (the browser's and Node's are faster there), and
on the thread workload by an order of magnitude over the hosts whose threads are isolates (64 tasks in 985 µs, ~15 µs a
task, on platform threads). Two things in the table are not investigated yet: a counter swap on a one-key map (11.8
µs) costs more than a swap of one key in a 1,000-key map (4.7 µs) — on ClojureScript too (33.2 µs, 10.1 µs) — and a
deref of a two-key map (17.1 µs) more than a swap.

The JVM is the column every other host's doc compares against. What sets its numbers, measured with JFR on a loop
of counter swaps (2026-10-21 ET):

- **Every slab word is a MethodHandle call.** The region's loads, stores and atomics are VarHandle access modes
  (`adapters/region/jvm-varhandle`), turned once into MethodHandles and spread to `(Object[])Object`, called with
  `.invoke` on an argument array: ~19 ns a volatile get, ~22 ns a CAS (a 2M-call microbench); through `eve.mem`'s
  `-load-i32` and `-cas-i32!`, 19.1 ns and 48.9 ns (the final pass's probe, 2026-10-22 ET).
  A swap reads and
  writes hundreds of words (the HAMT path, the allocator's bitmaps and headers, the epoch and the root), so these
  calls are still the largest part of a swap.
- **Until this tree they were reflection.** Each call went through `invokeWithArguments` with a vector, which
  Clojure resolved by reflection every time: `Class.getMethods` and `copyMethods` were most of a swap's samples,
  and a counter swap took 169 µs. Without the reflection it took 28.3 µs; with the spread handles, 14.7 µs
  (`invokeWithArguments` alone builds a spreader per call: 183 ns a get; 139.8 ns in the final pass's probe). The table above is after both; the run
  before, on the same box: a counter swap 268.8 µs, a 1,000-key map's deref 21.0 ms, a sorted-set build 148.8 ms,
  a disk-domain swap 748.5 µs. The shared code's own reflective sites on the JVM (the growth scan before a disk
  swap, the bitmap scan, the codec's header bytes, the types' offset accessor, the keyring's XOR loop) went with
  it; the JVM battery took 162 s against 364 s.
- **The codec and the HAMT walk** are the rest: a swap allocates the changed path's blocks and encodes the new values
  into them; `deref` materializes the value as Clojure collections (the 1,000-key map's deref, 495 µs, is that).

Other measured numbers in the tree: a gateway's network swap took 264 ms with Nagle's algorithm on and 73 ms with
`TCP_NODELAY` (`adapters/http-server/jdk`, ET-3); the columnar bench (2026-10-16, not re-measured for 0.9) has Eve's
column arithmetic over an mmap domain at 19 ms against 141 ms for the stock pipeline
([data-structures.md](../data-structures.md)).

## How eve is built on the JVM

| Feature | Adapter | How |
|---|---|---|
| region | `region/jvm-varhandle` | one `JvmByteBufferRegion` over a ByteBuffer — a heap `byte[]` or a `MappedByteBuffer` — with VarHandle atomics; wait/notify by `LockSupport` park/unpark; a Lustre mount gets `FileChannel.lock` byte-range locks instead |
| slab | `slab/heap` | the process's own slab is alloc's `JvmSlabCtx` heap domain (six 1 MB classes and a growing overflow class) |
| slab-io | `slab-io/protocol` | macros expanding to the `ISlabIO` / `IMemRegion` call, so a hint reaches the compiler and nothing is added at run time |
| core, completion | `core/clojure`, `completion/clojure` | each `eve.host` name is a macro over `clojure.core`'s own call; nothing to complete |
| coll | `coll/jvm` | `clojure.core`'s functions, inlined through `clojure.lang.RT`: eve values answer `count`, `get`, `seq`, `=` directly |
| types | `types/jvm` (+ `types/js` for a ClojureScript compile's macros) | `deftype` with the JVM's interfaces |
| encoding, int32, codec | `encoding/java`, `int32/jvm`, `codec/byte-array` | java.lang / java.util, `unchecked-int` narrowing, the codec over byte arrays |
| process | `process/java` | `System.currentTimeMillis`, a daemon heartbeat thread, a shutdown hook, `File.createNewFile` for exclusive creation, `SecureRandom` |
| thread | `thread/jvm` | platform threads (daemon) by default, virtual threads with `-Deve.thread.virtual=true`; a `LinkedBlockingQueue` mailbox each |
| crypto | `crypto/java` | `MessageDigest`, `Mac`, `Cipher "AES/CTR/NoPadding"`, `Signature "Ed25519"`; XXH3 is the portable code (the JDK has none) |
| http, http-server | `http/java`, `http-server/jdk` | `java.net.http.HttpClient` answered synchronously; `com.sun.net.httpserver` on a fixed pool, `TCP_NODELAY` on |

No Panama FFM and no `sun.misc.Unsafe`. The `--add-opens` and `--enable-native-access` flags in `deps.edn` belong to
the test aliases; an application does not need them.

## Gotchas

- **JDK 21 is the floor.** `thread/jvm` refers to `Thread/ofVirtual`, which must resolve when the namespace loads.
- **An atomic read-modify-write needs an aligned offset.** Plain loads and stores tolerate an unaligned one; a CAS or an
  add on one throws (eve's own layouts are aligned; this matters to a type writing its own words).
- **The JVM's region waits by polling** (`thread/jvm` declares `:wake :poll`): a thread blocked on a domain's word
  parks and re-reads; on a Lustre mount `notify` does nothing and the wait is a poll.
- **Build eve collections inside `swap!`.** A collection built outside belongs to the process's own heap domain and an
  atom of another domain refuses it ("allocated in a different slab domain").
- **`swap!` returns the slab-backed value**, lazily materialized, where `deref` answers host collections.
- **No watches on eve atoms** (`add-watch` throws: an eve atom is not a `clojure.lang.IRef`).
- **A user `eve/deftype` value and an `eve.sorted` map deref as eve-type instances**, not host collections
 .
- `eve.deftype.int-map/range` over a value read inside `eve.alpha/$` throws (known issues).
- **A persistent chain a swap's function builds and drops is not reclaimed** (every host — the model's scope-local
  temporaries): `(reduce assoc (int-map) ks)` inside one `swap!` leaves every intermediate version allocated. Build
  with a transient or a constructor, which free what each write supersedes (2026-10-21):
  `(apply int-map kvs)`, `(persistent! (reduce assoc! (transient m) …))`, or from host data. A transient begun from
  an empty collection the code made keeps that collection's header (one block). Their cost over the leaking
  transients (builds only, this box): a vector `into` 0.94×, a hash map's `assoc!` 1.5×, a sorted set's 1.7× —
  mostly the frees themselves; `doc/memory-model.md` "What a write leaves allocated".

## Future directions

Optimization:

- **core.rrb-vector's 155 reflective field reads** (`(.-arr node)` 102, `.-edit` 34, `.-tail`, `.-shift`, `.-root` …
  on untyped nodes and vectors in `eve.rrb-vector.rrbt`, `.trees`, `.transients`, `.nodes`): the last reflection loading
  eve on the JVM (`*warn-on-reflection*` over every namespace under `src/`, the final pass, 2026-10-21 ET;
  146 earlier that day). Hints on the node locals, as the rest of
  the shared code took them in this tree.
- **No argument array per slab word.** A spread handle still takes an `Object[]` and boxed arguments. VarHandle call
  sites of exact type (`compareAndSet(ByteBuffer, int, int, int)`) would let the JIT inline the access and box
  nothing; Clojure's compiler does not emit a signature-polymorphic call of exact type, so that is a small Java
  class beside the adapter — a build step this source-only library does not have yet.
- **Plain reads where the model allows them.** A node's interior is written before its pointer is published and read
  after it is acquired; such reads need not be volatile (`ByteBuffer.getInt` with an acquire fence at the pointer).
- `swap!` on a large map is dominated by encoding the changed path; a codec that writes primitives straight into the
  slab (no intermediate byte array) would cut a copy per value.
- Virtual threads as the default kind once the drivers that block (the domain cell's waits) are measured on them.

Coverage:

- A Clojars jar of `src` plus the JVM's roots; a support table stating JDK 21+ (8.1).
- `eve.slab-io-test` in the default run (its root added to `:jvm-test`).
- GraalVM native-image: the JVM row should run there if `VarHandle` survives native-image (PORT-TARGETS); not measured.
