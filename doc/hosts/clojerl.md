# eve on clojerl

clojerl (`hosts.edn :clje`) is Clojure compiled to Core Erlang, running on the BEAM. Erlang's terms are immutable
and its processes share no heap, so eve's memory on the BEAM is a NIF's: `adapters/core/beam/native/eve_clje_nif.c`
(526 lines) gives regions over `MAP_SHARED` files or BEAM-allocator memory with seq-cst atomics, fcntl locks and a
shared futex (a 100 µs poll outside Linux), and arrays as NIF resources. eve's threads are BEAM processes, which ship closures and block cheaply.
clojerl is the capstone's usual coordinator.

```bash
export PATH=/opt/eve-tools/clojerl/bin:$PATH
bb test:clje                   # the battery; eve's NIF is built on first load (gcc and the BEAM's erl_nif.h)
clojerl -pa src -pa <each hosts.edn :clje root> -pa test -m eve.runner.clje-main perf   # the performance suite
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:clje` | **198 tests, 4,400 assertions, 0 failures, 0 errors** (16 min; peak 11.1 GB, the process tree, not investigated) | 2026-10-22, this tree (the final pass, `doc/release-0.9/logs-2026-10-22/f-clje.log`) |
| `bb test:clje`, again over `4eac2122e` (clojerl's NIF off Linux) | **198 tests, 4,400 assertions, green** (`f2-clje.log`) | 2026-10-22, this tree |
| `bb test:clje eve.thread-host-test eve.thread-domain-test` | 20 tests, 153 assertions, green — the futex-free wait path's Linux side (`f-mac-clje-nif.log`) | 2026-10-22, this tree |
| `bb scripts/thread-rendezvous.clj jvm clje` | 18 of 18 checks (83 s) | 2026-10-22, this tree (the final pass) |
| `bb test:clje` | **192 tests, 4,383 assertions, 0 failures, 0 errors** (18 min) | 2026-10-21, `08b94cbf4`, before 9.31 |
| `bb test:clje` | **184 tests, 2,808 assertions, 0 failures, 0 errors** (21 min, most of it core.rrb-vector's suite) | 2026-10-21, before 9.25 |
| `bb test:clje`, in the final sweep | red: exit 1 at load (48 s) — `eve.keyring`, hinted for the JVM, did not refer eve.host's `bytes`, and clojerl resolves a hint's symbol | 2026-10-20 |
| `bb test:clje` | **182 tests, 2,787 assertions, 0 failures, 0 errors** (18 min, most of it core.rrb-vector's suite) | 2026-10-20, the 0.9 release-prep lane's final tree |
| `bb test:clje` at the lane's base | red: exit 1 at load (~40 s) — `eve.net.merkle`'s constants were `def`s of delays | 2026-10-20 |
| `bb scripts/thread-rendezvous.clj jvm clje` | 18 of 18 checks | 2026-10-19 |
| the capstone, `bb eve-media verify` | passed: six runs, every check and comparison row true; the BEAM the leader in core+beam, extended and extended-kill (`doc/release-0.9/logs-2026-10-22/f-capstone-verify.log`, `.edn`) | 2026-10-22, this tree (the final pass) |
| the capstone, `bb eve-media verify` | the BEAM as leader: core set and BEAM 108 s, the extended set 168 s | 2026-10-20 |

The runner (`test/eve/runner/clje_main.clje`) runs 24 shared namespaces: everything babashka's runner runs except the
four typed-array suites (clojerl has no typed-array adapter) — the fixtures, the collections, core.rrb-vector, the
key ring, value buffers, the vector retire walk, reclaim, the tally, domain open, and the whole thread feature
including the network rung.

The byte-format fixtures read **52 of 71 rows on clojerl, 19 skipped** (measured 2026-10-20): the rows the fixture
file marks for other hosts — a char, a date with milliseconds, two keywords past 255 characters, and fifteen
big-integer, ratio and big-decimal rows. (`eve/CLAUDE.md`'s baseline said 4 skipped, counting the first four; the
runner's own line is the measurement.)

## Performance

<!-- perf:clje (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 366.9 µs | 4096 | 31 |
| `atom-deref-small` | 74.8 µs | 16384 | 4.4 |
| `atom-swap-map-1k` | 122.1 µs | 8192 | 26 |
| `atom-deref-map-1k` | 7.2 ms | 256 | 19 |
| `atom-$-get-map-1k` | 75.5 µs | 16384 | 37 |
| `atom-reset-vec-1k` | 456.5 ms | 4 | 297 |
| `sorted-set-build-200` | 570.0 ms | 2 | 51 |
| `int-map-build-200` | 341.5 ms | 4 | 76 |
| `disk-swap-counter` | 524.9 µs | 2048 | 30 |
| `thread-submit-64` | 15.8 ms | 128 | 16 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:clje -->

Reading it: ~25–30× the JVM's on swaps (each slab access a NIF call), and encoding a 1,000-element vector is its
outlier (457 ms — ~1,240× its counter swap, where the other hosts sit at 100–390×): the codec's per-element array
operations are NIF calls. 64 tasks to BEAM processes: 15.8 ms.

What sets clojerl's numbers:

- **Every array or region operation is a NIF call**: from compiled code an eve long-array's `aset` takes 0.035 µs and
  its `aget` 0.040 µs (2026-10-22 ET, `doc/release-0.9/logs-2026-10-22/f-probe-clje-costs.log`; HOST-CONTRACT §10 had
  quoted ~1.3 µs). eve's slab I/O is many small reads and writes, so a swap is hundreds of NIF calls — at that cost
  ~10 µs of a counter swap's 367 µs: the NIF calls alone do not explain clojerl's swap times, and where the rest goes
  is not measured yet.
- **clojerl interprets what a top-level expression makes.** A function created by a top-level expression runs in
  Core Erlang's interpreter at every call; eve registers its load-time functions as `defn`s, which compile (HOST-CONTRACT
  §10's third rule — the header-tag reader once ran interpreted).
- **A thread is a BEAM process**: starting one is microseconds, and a message carrying the binding frame (20 vars,
  3,105 bytes as a term) round-trips in 2.4 µs (the same probe; the page said 6.5 µs before it).

## How eve is built on clojerl

| Feature | Adapter | How |
|---|---|---|
| core | `core/beam` (no completion root) | `eve.host` over the NIF: arrays are NIF heap memory and an object array a NIF term array; integer ops wrap at their width; `locking` through `global:trans`; cells are process values; transients are built persistently; `swap!` through eve's own `IAtom` for atoms that are not clojerl's; `defdelay` keeps its value in `persistent_term` |
| region | `region/beam` | `CljeRegion` over the NIF: a mapped file, or BEAM-allocator memory shared by the node's processes; RMW needs natural alignment (else `badarg`); `open` and `wait-i32!` run on dirty IO schedulers |
| coll | `coll/beam` | clojerl's core plus eve's transient protocols; `caching-hash` recomputes |
| types | `types/beam` | the JVM's interface names translated onto clojerl's protocols; a mutable field is a cell; a hash cache is never set |
| encoding | `encoding/beam` | BEAM primitives and the NIF |
| int32 | `int32/portable` | quot / mod arithmetic (the BEAM's integers are unbounded) |
| process | `process/beam` | `self()` as the thread id; the heartbeat a spawned process |
| thread | `thread/beam` | BEAM processes by `spawn_monitor`: closures ship, `:block :cheap`, `:wake :futex`, parallel up to the scheduler count |
| crypto | `crypto/beam` | OTP's `crypto` (hash, mac, `aes_256_ctr`, eddsa); XXH3 is the portable code |
| http | `http/beam` | OTP `httpc` (inets), synchronous; `request-all` in httpc's async mode |
| slab, slab-io, codec | `slab/heap`, `slab-io/protocol`, `codec/byte-array` | as the JVM's |

The NIF is built by `clje_nif.clje` with `gcc -O2 -fPIC -shared` into `adapters/core/beam/target/clje/` (a temporary
file renamed into place; rebuilt when the source is newer).

## Gotchas

- **A type hint's symbol must resolve.** clojerl resolves `^bytes`, `^ints`, `^objects` … wherever they appear (a
  binding, a parameter, a use) and stops the load when one does not (`Unable to resolve symbol: bytes`); eve.host
  defines the array casts on clojerl so the hints resolve, and a namespace that hints refers them from eve.host, as
  `eve.deftype-proto.alloc` does. (Measured 2026-10-21: the JVM's reflection fix hinted `eve.keyring` without the
  refer, and `bb test:clje` stopped at load.)
- **clojerl compiles a `def`'s value as a literal.** A `def` of a delay, an atom of a computed value, or anything that
  must run at load does not survive compilation: eve keeps such constants in an atom filled at first use
  (`eve.net.merkle`, fixed for 0.9) or in `persistent_term` (`defdelay`).
- **What the codec cannot hold on clojerl**: a char (clojerl has none), a date finer than a second (milliseconds are
  lost), ratios and big decimals (refused), keywords past 255 characters (Erlang atoms). Values carrying them can
  be written by another host and read back elsewhere; not here.
- **clojerl's own `swap!` / `reset!` reach only `clojerl.Atom`**: use `eve.host`'s on eve atoms.
- **No exit hook**: `on-process-exit!` does nothing, so a disk domain's slot is released when its heartbeat goes stale,
  not when the node stops.
- **First load needs a C toolchain** (gcc and the BEAM's `erl_nif.h`).
- clojerl's quirks eve was written around (PORT-TARGETS §4): `defprotocol` inside `do` fails; a protocol cannot be
  `:refer`red; a method parameter named like a field is a duplicate variable; `slurp` reads Latin-1 unless told
  otherwise; a vector's seq hashes the whole vector; vectors have no `reduce-kv`.

## Future directions

Optimization:

- **Batch the NIF calls.** A slab read of a node's header and children is several NIF calls; one NIF entry point per
  eve operation (read a HAMT node's bitmap and slots at once, encode a block in one call) would cut the per-call cost
  that dominates.
- A hash cache on eve types (`types/beam` never sets one; every `hash` recomputes).

Coverage:

- A typed-array adapter (the NIF's arrays are already byte-addressed; an Erlang binary view would serve reads).
- The 19 fixture rows: chars and sub-second dates could be represented by eve's own tags on clojerl (a char as a
  tagged code point, a date as milliseconds) rather than skipped.
- A hex package (RELEASE-CHECKLIST, after 0.9).
