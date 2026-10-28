# eve on babashka

babashka (`hosts.edn :bb`) is Clojure interpreted by sci in a GraalVM native image: it starts in milliseconds and runs
eve's shared code as written, but sci can define neither classes nor the JVM's interfaces, and the native image
reaches neither `MethodHandles` nor `Unsafe`. So eve's types on babashka are sci types answering eve's own protocols,
and a babashka process takes part in a cross-process disk domain through byte-range locks rather than hardware CAS.
babashka is also where the tree's scripts, examples and test orchestration run.

```bash
bb test:bb                     # the battery (named namespaces after the task run alone)
bb test:bb-minimal             # the same runner on the minimal + inbuilt adapters (below)
bb -cp "$(bb classpath bb):test" -m eve.runner.bb-main perf    # the performance suite
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:bb` | **247 tests, 4,748 assertions, 0 failures, 0 errors** (306 s, peak 0.4 GB, the process tree) | 2026-10-21, this tree (the final pass, `doc/release-0.9/logs-2026-10-22/f-bb.log`) |
| `bb test:bb-minimal` | **242 tests, 4,623 assertions, 0 failures, 0 errors** (315 s) | 2026-10-21, this tree (the final pass, `f-bb-minimal.log`) |
| `bb test:bb`, `bb test:bb-minimal`, again over `28e2c611` (the region's lock on a channel of its own where bb refuses the lock's methods) | the same counts, green (417 s and 392 s on a second box; there, the tree before the change 407 s and HEAD again 411 s: the box, not the change — `f2-bb*.log`, `f-ab-bb-*.log`) | 2026-10-22, this tree |
| `bb scripts/thread-rendezvous.clj jvm bb` (both ways), `bb scripts/thread-fold-rendezvous.clj bb bb` | 18 of 18 checks (14 s each way); both members ran chunks (4 s) | 2026-10-22, this tree (the final pass) |
| `bb test:bb` | **233 tests, 3,156 assertions, 0 failures, 0 errors** (377 s, peak 450 MB) | 2026-10-20, the final sweep |
| `bb test:bb-minimal` | **228 tests, 3,031 assertions, 0 failures, 0 errors** (360 s, peak 240 MB) | 2026-10-20, the final sweep |
| `bb test:bb` | **231 tests, 3,135 assertions, 0 failures, 0 errors** (6 min 13 s) | 2026-10-20, the 0.9 release-prep lane's final tree |
| `bb test:bb-minimal` | **226 tests, 3,010 assertions, 0 failures, 0 errors** (6 min 21 s) | 2026-10-20 |
| `bb scripts/thread-rendezvous.clj bb <host>` | 18 of 18 checks, bb with bb and with nbb, both ways | 2026-10-19 |
| `bb scripts/thread-fold-rendezvous.clj bb bb` | both members ran chunks, every value equal to the sequential one | 2026-10-19 |

The runner (`test/eve/runner/bb_main.clj`) runs the 28 shared namespaces every host runs as written: the HAMT
helpers, the byte-format fixtures (71 rows, none skipped), the typed arrays (coalesc-align, array-hosts, dataset,
tensor), int-map, the red-black set, sorted collections, core.rrb-vector's suite, big values, value buffers, the key
ring, the vector retire walk, transient reclaim, the tally, concurrent domain open, type ids, collection hashing, the
numeric and reference tags, and the whole thread feature
(the conformance, eve.thread, the meta-atom, the domain cell, the network rung, fold, fold on a group).

eve.net's own suites (`test/eve/net/*`) are not in the default list; they run when named
(`bb test:bb eve.net.crypto-test`).

## Performance

<!-- perf:bb (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 324.7 µs | 4096 | 27 |
| `atom-deref-small` | 68.9 µs | 16384 | 4.0 |
| `atom-swap-map-1k` | 115.4 µs | 16384 | 25 |
| `atom-deref-map-1k` | 12.2 ms | 128 | 32 |
| `atom-$-get-map-1k` | 88.3 µs | 16384 | 43 |
| `atom-reset-vec-1k` | 39.9 ms | 32 | 26 |
| `sorted-set-build-200` | 484.3 ms | 4 | 44 |
| `int-map-build-200` | 133.4 ms | 8 | 30 |
| `disk-swap-counter` | 415.5 µs | 4096 | 24 |
| `thread-submit-64` | 1.5 ms | 1024 | 1.5 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:bb -->

Reading it: ~25–45× the JVM's on in-memory work (sci interprets each call; a small deref 4×), a disk swap ~1.3× an
in-memory one, and real threads: 64 tasks in 1.5 ms, the second-fastest thread workload of any host.

What sets babashka's numbers:

- **sci interprets** every function call through its own dispatch, so the codec and the HAMT walk cost several times
  the JVM's.
- **eve values answer eve's protocols, not the JVM's interfaces** (`coll/protocols`): each operation is a protocol
  dispatch in sci.
- **Every store to a mapped region is followed by a full fence** (`region/jvm-nio-lock`: an
  `AtomicInteger.incrementAndGet`, the only fence the image reaches), and a read-modify-write on a shared domain is a
  `FileChannel` byte-range lock.

Measured in the tree (2026-10-20, [adopting-eve-fast.md](../adopting-eve-fast.md)): the native crypto seam against the
portable one — SHA-256 of 16 KiB 1.12 ms against 170 ms, an Ed25519 signature 0.84 ms against 941 ms, Murmur3 over
10,000 keys 43 ms against 134 ms. A network atom's cold deref of ~1,100 blocks through a gateway: 4.5 s
(RELEASE-CHECKLIST 9.17).

## How eve is built on babashka

| Feature | Adapter | How |
|---|---|---|
| region | `region/jvm-nio-lock` | `BbMmapRegion` over a plain `MappedByteBuffer`: plain loads and stores, a full fence after each store; `-atomic-rmw?` answers false and a read-modify-write runs under `FileChannel.lock(off, 4)` plus an in-process lock (a second lock of one range by one process throws); waits poll every 1 ms. A heap region (`BbHeapRegion`) runs every operation under `locking` |
| types | `types/bb` | sci's `deftype` takes protocols, `Object` and `IPersistentMap` only: the JVM interfaces a type names are dropped, its self-calls rewritten onto `eve.core`, `IDeref` / `IAtom` kept; an instance is built through its `->T` var |
| coll | `coll/protocols` | eve's own protocols with ClojureScript's names; each protocol's `Object` arm falls through to `clojure.core`, so host values answer them too. Its own `Subvec`, ordered hashing by walking the seq, Murmur3 `mix-collection-hash` (babashka has none) |
| core, completion, slab, slab-io | `core/clojure`, `completion/clojure`, `slab/heap`, `slab-io/protocol` | as the JVM's |
| encoding, int32, codec, process | `encoding/java`, `int32/jvm`, `codec/byte-array`, `process/java` | the JVM's, as sci reaches them |
| thread | `thread/jvm` | the JVM's adapter: babashka's threads, `:wake :poll` |
| crypto, http | `crypto/java`, `http/java` | the JDK's, as on the JVM |
| http-server | `http-server/httpkit` | http-kit's `run-server` on its worker pool: babashka can serve a gateway |

A disk domain shared with lock-free writers: a babashka writer takes the domain's **exclusive hold** (`ROOT_HOLD` /
`ROOT_INFLIGHT` in the root file) for its write, so lock-free writers on other hosts and lock-only writers on babashka
never interleave inside one write ([atom-domains.md](../atom-domains.md#many-processes-on-one-domain)).

## Gotchas

- **`clojure.core` does not see eve types.** `count`, `nth`, `seq`, `(:k x)` and `=` on an eve value that is not a map
  answer as for a sci object; use `eve.core`'s functions (`e/count`, `e/get`, `e/equiv`). A value read out by `deref`
  is a host collection and answers everything.
- **A babashka writer deadlocks a shared disk domain once a lock-free writer has died inside a write**
  (RELEASE-CHECKLIST 9.14): the dead writer leaves `ROOT_INFLIGHT` set and the next babashka write holds the domain for
  ever. Until that changes, babashka reads a shared domain and writes through a gateway. Since 9.31 a lock-free
  host's write that raises a tally count takes the same hold and waits for `ROOT_INFLIGHT` to drain
  (`with-held-write*`, `src/eve/atom.cljc`), so the hang reaches any host's raising write, not babashka's only.
- **`swap!` with seven or more extra arguments** fails in sci's `IAtom` dispatch ("Cannot call with 9 arguments" at
  seven; six work — babashka 1.13.226, `doc/release-0.9/logs-2026-10-22/f-bb-swap-args.log`); pass a closure instead.
- **No watches** on eve atoms (a sci type is not an `IRef`).
- **An RRB vector or a user `eve/deftype` value derefs as the eve type**, not a host collection.
- `#?(:bb ...)` is a reader feature babashka reads; eve's shared code never uses it (the host-leak gate), but tests
  skip a few checks sci cannot express.

## babashka-minimal (`hosts.edn :bb-minimal`)

Not a shipped host: babashka composed from **four host roots** (region `jvm-nio-lock`, encoding `java`, process
`java`, types `bb` — 972 lines between them) and the **nine inbuilt roots** every host may take as they are
(`slab-io/protocol`, `core/clojure`, `completion/clojure`, `coll/protocols`, `slab/heap`, `int32/portable`,
`codec/byte-array`, `crypto/portable`, `thread/none`). It is what a new language starts from
([adopting-eve-minimal.md](../adopting-eve-minimal.md)), and its battery is the proof that the inbuilt set is
complete: **242 tests, 4,623 assertions, green** (2026-10-21) — bb's list without eve.net's namespace (no http root).

Its differences: no threads (`thread/none` declares `:max-threads 0`; eve.thread's pools run on the calling thread,
and a wait for ever is refused as a deadlock), no network atoms, and the portable crypto (≈ 147× the native SHA-256,
≈ 1,100× an Ed25519 signature — not for a network atom with real traffic).

<!-- perf:bb-minimal (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 340.3 µs | 4096 | 29 |
| `atom-deref-small` | 74.2 µs | 16384 | 4.3 |
| `atom-swap-map-1k` | 140.0 µs | 8192 | 30 |
| `atom-deref-map-1k` | 12.2 ms | 128 | 32 |
| `atom-$-get-map-1k` | 97.5 µs | 16384 | 47 |
| `atom-reset-vec-1k` | 42.3 ms | 32 | 28 |
| `sorted-set-build-200` | 410.3 ms | 4 | 37 |
| `int-map-build-200` | 132.1 ms | 8 | 29 |
| `disk-swap-counter` | 383.1 µs | 4096 | 22 |
| `thread-submit-64` | 1.9 ms | 1024 | 1.9 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:bb-minimal -->

Reading it: babashka on the portable seams is within the box's noise of full babashka on these workloads; what the
fast seams buy is in crypto and hashing (ADOPTER-TIERS §6), which this suite does not time.

## Future directions

Optimization:

- The lock-only region is the cost of babashka's shared domains. A pod (`impls/bb/eve_atomics_pod`, Go, 32-bit
  atomics — it builds, nothing calls it) or a future babashka exposing `VarHandle` would give babashka the lock-free
  path for its ordinary writes (9.14 stays: since 9.31 a raising write on any host takes the exclusive hold).
- A per-type materializer (RELEASE-CHECKLIST 9.8a) so an RRB vector derefs as a host vector.

Coverage:

- eve.net's suites in the default list (they pass when named; they need the http root, which `:bb` has).
- 9.14's fix: a dead lock-free writer's in-flight mark cleared by heartbeat staleness, as a dead writer's slot is.
