# eve on glojure

glojure (`hosts.edn :glj`) is Clojure interpreted in Go. eve runs on it as **glj-eve**: glojure v0.7.22 built with
eve's Go packages (regions, host helpers, types) and three patches to glojure, by `impls/glojure/build.sh`
(`bb glj-eve`, cached by a source stamp; ~40 s on Go 1.24). glojure has neither `deftype` nor `reify`, so eve's
completion builds them from records; eve's types answer eve's own protocols (babashka's `coll/protocols`). Memory is a
Go byte slice or a `MAP_SHARED` mapping with `sync/atomic` and futex (a 100 µs poll outside Linux); threads are goroutines.

**0.9.3.1 (2026-11-09).** This release fixes the `*compile-files*` panic (the file now realizes its
entry's value last; seven codegen patches in eve's completion Go patches) and the window-shape
`RTEvalError: Casting: Unsupported type *go/types.Basic` (the completion routes glj values through its
own `goValue` writer). glj tests: 24 tests / 310 assertions / 0 failures / 0 errors. The journal-file
adapter is new in this release and its A3 probe is unchanged-exact. Diagnosis-only leftovers:
`(:go entity)` derefs to nil (one measured nil; a workaround is named), the `d`/`q` lru death in the
window shape reproduces only inside the glj-eve harness, and the completion is not itself
AOT-compilable.

```bash
bb test:glj                    # builds glj-eve if its stamp is stale, then the battery
GLJ_CLASSPATH=src:<hosts.edn :glj roots>:test target/glj-eve/glj-eve -e "(require 'eve.runner.glj-main) (eve.runner.glj-main/-main \"perf\")"
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:glj` | **182 tests, 4,458 assertions, 0 failures, 0 errors** (473 s, peak 2.8 GB, the process tree) | 2026-10-22, this tree (the final pass) |
| `bb test:glj`, again over `4eac2122e` (glj-eve's region: futex on Linux, a poll elsewhere) | **182 tests, 4,458 assertions, green** (`f2-glj.log`) | 2026-10-22, this tree |
| `bb test:glj eve.thread-host-test eve.thread-domain-test` | 20 tests, 153 assertions, green — the same change's Linux side (`f-mac-glj-linux.log`); glj-eve cross-built for darwin/arm64 (`f-mac-glj-cross.log`) | 2026-10-22, this tree |
| `bb test:glj eve.thread-fold-group-test` | 3 tests, 49 assertions, green (26 s) | 2026-10-22, this tree (the final pass) |
| `bb scripts/thread-rendezvous.clj jvm glj` (and `glj jvm`, `glj glj`) | 18 of 18 checks, 22–34 s a pair | 2026-10-22, this tree (the final pass) |
| `bb test:glj` | **168 tests, 2,866 assertions, 0 failures, 0 errors** (497 s, peak 2.4 GB, beside basilisp's battery) — fold, fold on a group, hashing and the network rung in the default run now | 2026-10-20, the final sweep |
| `bb test:glj` | **149 tests, 2,403 assertions, 0 failures, 0 errors** (206 s), peak 1.2 GB resident, beside `bb test:jolt` | 2026-10-20, earlier in this round |
| the same, before glj-eve set GOGC | the same counts; peak 7.1 GB, and OOM-killed beside `bb test:jolt` | 2026-10-20 |
| `bb test:glj eve.collection-hash-test eve.thread-net-test` | green: hashing and the network rung (a domain pool over a network atom, a gateway) on glojure | 2026-10-20 |
| `bb test:glj eve.thread-fold-test eve.thread-fold-group-test` | 47 errors before the completion's `sequence` / `dedupe` / `partition-all` (glojure's reached JVM classes it lacks); green after, and in the default run since (above) | 2026-10-20 |
| `bb test:glj eve.rrb-vector-test` | **22 tests, 66 assertions, green** (21 min, peak 2.3 GB, the process tree) | 2026-10-22, this tree (the final pass) |
| `bb test:glj eve.rrb-vector-test` | 22 tests, 66 assertions, green (~24 min) | 2026-10-20 |
| `bb scripts/thread-rendezvous.clj jvm glj` (and `glj jvm`, `glj glj`) | 18 of 18 checks, 26–38 s a pair | 2026-10-20 |

The runner (`test/eve/runner/glj_main.glj`) runs the fixtures, the HAMT helpers, type ids, the red-black set, int-map,
sorted collections, big values, value buffers, the key ring, the numeric and reference tags, the vector retire walk,
domain open, the thread conformance, eve.thread, the meta-atom and the domain cell — and, added in this round (they
were left out without a reason), collection hashing, the network rung (`eve.thread-net-test`), fold and fold on a
group. Every namespace babashka's runner runs but the typed-array suites, glojure's runs. Not run by default:
core.rrb-vector's suite (21 min on glj-eve in the final pass: `test-reduce-subvec-catvec` alone 229 s, 2,371 catvecs; it runs when
named) and the typed-array suites.

## Performance

<!-- perf:glj (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 2.1 ms | 512 | 178 |
| `atom-deref-small` | 318.4 µs | 4096 | 19 |
| `atom-swap-map-1k` | 4.6 ms | 256 | 991 |
| `atom-deref-map-1k` | 72.6 ms | 16 | 192 |
| `atom-$-get-map-1k` | 383.8 µs | 4096 | 187 |
| `atom-reset-vec-1k` | 218.8 ms | 8 | 142 |
| `sorted-set-build-200` | 2295.0 ms | 1 | 206 |
| `int-map-build-200` | 1039.0 ms | 2 | 232 |
| `disk-swap-counter` | 2.2 ms | 512 | 128 |
| `thread-submit-64` | 7.4 ms | 256 | 7.5 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:glj -->

Reading it: ~130–230× the JVM's on most workloads (a counter swap 2.1 ms, a sorted-set build 2.3 s) and ~1,000× on a
swap into a 1,000-key map (4.6 ms): glojure interprets its AST and a protocol call is a multimethod on class.
Goroutines run the thread workload in 7.4 ms.

What sets glojure's numbers:

- **glojure interprets**: every call walks glojure's AST, and a protocol call through glojure's own protocols is a
  multimethod on class, ~3 µs (3.06 µs a call against 0.10 µs for a plain fn call: the final pass's probe,
  2026-10-22 ET). eve's completion defines protocols defined
  after it as fixed-arity functions caching the implementation per class.
- **The collector**: glojure sets Go's GC target to 400 when `GOGC` is unset (fewer collections of its short-lived
  boxed values), so the heap may grow to five times the live data before a collection — `bb test:glj` peaked at
  7.1 GB resident with ~1.4 GB live. glj-eve sets Go's own default, 100, unless `GOGC` is in the environment: 1.0 GB
  resident for 2% more time (217 s → 222 s).

## How eve is built on glojure

| Feature | Adapter | How |
|---|---|---|
| completion | `completion/glojure` (+ Go patches) | `deftype` as a record extended to its protocols (a mutable field a `volatile!` cell built by `->T`), `reify` as one record per form, `satisfies?` / `extends?`, `unchecked-int` that wraps (glojure's widens), `deref` / `swap!` / `reset!` / `compare-and-set!` / `swap-vals!` / `reset-vals!` / `realized?` reaching a type's own, fast fixed-arity protocols, `=` and `hash` reaching a type's own, Clojure's Murmur3 collection hashes (glojure refers to a class it lacks), `range` with a negative step, `into` a sorted collection, `subseq` / `rsubseq`, `sequence` with a transducer, `dedupe` of a collection, `partition-all`'s transducer; `clojure.edn` (v0.7.22 ships none) |
| Go patches | `impls/glojure/`, `adapters/completion/glojure/go/` | `host-field.patch` (the interpreter's `OpHostField` case), `record-factory.patch` (`new` and `->T` for a type), `goroutine-id.patch` (a goroutine's id without a stack trace, which cost tens of µs) |
| region | `region/glojure` | `GljRegion` over eve's Go package: a Go `[]byte` or a `MAP_SHARED` mapping, `sync/atomic` (seq-cst), `SYS_FUTEX`; `GljLockedRegion` serves a Lustre mount by fcntl locks with a 1 ms polling wait |
| types | `types/glojure` | the completion's `deftype` on eve.core's protocols; `IDeref`, `IAtom`, `IPending` onto the completion's |
| coll | `coll/protocols` | babashka's protocols |
| encoding | `encoding/go` | eve's Go `host` package: UTF-8, array copies, little-endian floats; a byte array is `[]int8` |
| int32 | `int32/portable` | quot / mod arithmetic |
| process | `process/go` | the goroutine id as the thread id, a goroutine heartbeat (by `AgentSubmit`), exit hooks on SIGINT / SIGTERM, `/dev/urandom` |
| thread | `thread/glojure` | a goroutine per thread (`lang.AgentSubmit`); a mailbox an atom of a queue and a promise; `:block :cheap :parallel true` |
| crypto | `crypto/portable` | the portable code (stock glojure has no crypto packages) |
| http | `http/go` | Go `net/http`, synchronous; `request-all` a future per request |
| core, slab, slab-io, codec | `core/clojure`, `slab/heap`, `slab-io/protocol`, `codec/byte-array` | as the JVM's |

## Gotchas

- **glojure refuses inline protocol methods in a record.** Extend a record with `extend-type` (eve.thread.rung's and
  eve.thread.net's records failed to load before 0.9 for this).
- **`clojure.core` sees an eve type as a map** (a record is a map to glojure): use `eve.core`'s functions on eve values;
  the completion makes `map?`, `coll?`, `counted?` answer false for a type.
- **`eduction` does not exist** in glojure v0.7.22 (eve does not use it).
- **No exit hook on a normal exit or a kill**: `on-process-exit!` fires on SIGINT / SIGTERM only, so a disk domain's
  slot is released by heartbeat staleness after a normal exit.
- **Threads start with the root binding frame**: glojure conveys no bindings to a goroutine (eve.thread conveys what it
  declares, by `defconveyed`).
- `ex-message` carries glojure's "GLJ Stack" (the thread adapter strips it); var metadata has no `:name`.
- **Unaligned atomics panic** (an unaligned plain load or store is done byte by byte).
- glojure's Go code still compares a type as a record where it calls Go-side equality directly.

## Future directions

Optimization:

- **Native crypto.** glj-eve can add Go's `crypto/sha256`, `crypto/hmac`, `crypto/aes` and `crypto/ed25519` to its
  package map (`gen-import-interop`) and a `crypto/go` adapter calls them — the portable code is ~150× slower for
  SHA-256 and ~1,000× for Ed25519 (measured on babashka).
- eve's hot paths (the codec, the HAMT walk) as Go functions in eve's Go package, called once per block rather than
  interpreted per word.
- A published glj-eve binary (not published for 0.9).

Coverage:

- A typed-array adapter (Go slices; `encoding/go` already reads host typed arrays).
- glojure upstream: the three patches and the completion's core fixes (`sequence`, `partition-all`, `range`, `into`,
  `subseq`, the Murmur3 hashes) are glojure defects worth reporting.
