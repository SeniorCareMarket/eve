# CLAUDE.md — Eve

> Read this before touching any code.

---

## eve is portable — the first rule

eve is written **once**, against **eve's host interface**. A host (the JVM,
ClojureScript, babashka, ClojureCLR, basilisp, clojerl, jolt, glojure, ...)
is an **adapter** that implements that interface **outside eve's shared
code**. Porting eve to a Clojure-like means writing its adapter — never
editing the shared code.

- The shared code (`eve/src`) names no host: no reader conditional keyed by a
  host feature (`:clj` `:cljs` `:bb` `:cljr` `:lpy` `:clje` `:jolt` `:glj`
  ...), no branch on the host (`clje?`, `bb?`, `cljs-target?` ...), no host
  interop (`java.*`, `js/`, `erlang/`, `python/` ...), no per-host
  translator, no file only one host reads.
- What a host must supply is the interface; how it supplies it is its
  adapter's business, in its adapter.
- Adapters are organized by **feature**, not by host
  (`eve/adapters/<feature>/<implementation>/`, `adapters/README.md`): every
  host with the same capability plugs into the same adapter, and a host is
  the list of adapter roots on its classpath (`eve/hosts.edn`, which every
  launcher reads).
- Adding a host never adds a line to the shared code. A change that would is
  the wrong change: extend the interface instead, and implement it in every
  adapter.
- `eve/scripts/check-host-leak` (a pre-commit gate) counts what the shared
  code still names of a host and holds it to `eve/.host-leak`: it may only go
  down, each shrink banked in the commit that makes it. The goal is 0.

---

## Project Identity

Eve is portable shared-memory persistent data structures for Clojure-like
languages: maps, vectors, sets, lists, sorted collections, int-maps, RRB
vectors, typed arrays and objects, in slab memory that one format and one
protocol share across hosts. Eleven hosts ship in `hosts.edn`: the JVM,
ClojureScript (Node), ClojureScript in a browser, babashka, nbb, ClojureCLR,
basilisp, jolt, clojerl, ValueScript and glojure.

Its values live in atoms: in-process atoms, and atom domains — a domain is
itself an atom, and its member atoms are slots in one domain-state — in heap
memory or on disk (mmap files shared and atomically mutated by processes of any
host). Network atoms (`eve.net.*`) carry the same values to a broker or a
gateway, blobs direct to a presigned store; `eve.keyring` encrypts a disk
domain's blocks at rest; the thread feature (`eve.thread*`) is an executor
service written once over each host's threads. The user-facing overview is
`README.md`; the release-prep audits are `doc/release-0.9/`.

---

## The Green Baseline

These must pass before any code change.

```bash
# Build native addon (only if mmap_cas.cc changes)
npm run build:addon

# Compile test bundles (eve-test + mmap-worker for e2e tests)
npm run test:compile

# Run test suites
node target/eve-test/all.js epoch-gc
# → "Ran 16 tests containing 44 assertions. 0 failures, 0 errors."

node target/eve-test/all.js obj
# → "Ran 27 tests containing 2075 assertions. 0 failures, 0 errors."

node target/eve-test/all.js rb-tree
# → "Ran 28 tests containing 293 assertions. 0 failures, 0 errors."
#   (same file on the JVM: clojure -M:jvm-test -n eve.deftype.rb-tree-test)

node target/eve-test/all.js int-map
# → "Ran 23 tests containing 373 assertions. 0 failures, 0 errors."
#   (the same file on the JVM and every shared-test host: clojure -M:jvm-test -n eve.deftype.int-map-test)

node target/eve-test/all.js batch2
# → "Ran 10 tests containing 73 assertions. 0 failures, 0 errors." (2026-10-21: four growth tests, their skip arms on
#   Node's fixed slabs — slab-grow runs them over growable ones; 6 / 59 before)

node target/eve-test/all.js batch3
# → "Ran 7 tests containing 33 assertions. 0 failures, 0 errors."

node target/eve-test/all.js batch4
# → "Ran 13 tests containing 34 assertions. 0 failures, 0 errors."

node target/eve-test/all.js typed-array
# → "Ran 9 tests containing 70 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mem
# → "Ran 9 tests containing 35 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mmap
# → "Ran 15 tests containing 27 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mmap-slab
# → "Ran 10 tests containing 26 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mmap-atom
# → "Ran 7 tests containing 8 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mmap-atom-e2e
# → "Ran 4 tests containing 10 assertions. 0 failures, 0 errors."

node target/eve-test/all.js slab
# → "Ran 63 tests containing 2863 assertions. 0 failures, 0 errors." (2026-10-21 ET, the final tree's final pass,
#   `doc/release-0.9/logs-2026-10-22/f-node-slab.log`, 6 s; eve.reclaim-test (9.25) and eve.tally-test (9.31) joined the
#   slab suite since "Ran 49 tests containing 1271 assertions" on 2026-10-20 and 47 / 1250 on 2026-10-19)

node target/eve-test/all.js slab-grow
# → "Ran 105 tests containing 28902 assertions. 0 failures, 0 errors." (2026-10-21 ET, the final tree's final pass
#   (`doc/release-0.9/logs-2026-10-22/f-node-slab-grow.log`), 20 s). Earlier: "Ran 99 tests containing 28885 assertions. 0 failures, 0 errors." (2026-10-21, the 9.25 round's batteries on `0eddc94d0` and again on `08b94cbf4`, three lanes). Earlier: "Ran 91 tests containing 27310 assertions. 0 failures, 0 errors." (2026-10-20, 18 s: batch2 and the slab suites over
#   slabs that start small and grow — ps/init! :growable? true, a browser's default; Node's hold their capacity)

node target/eve-test/all.js all
# → all suites combined ("Ran 482 tests containing 34980 assertions", 0 failures, on 2026-10-21 ET — the final tree's
#   final pass (`doc/release-0.9/logs-2026-10-22/f-node-all.log`), 72 s, peak 2.7 GB (the process tree). Earlier: "Ran 476 tests containing 34963 assertions", 0 failures, on 2026-10-21 — the 9.25 round's batteries on
#   `0eddc94d0` and `08b94cbf4`, 76 s; "Ran 468 tests containing 33388 assertions" on 2026-10-20 — the 0.9 preparation's
#   final sweep, 84 s, peak 2.2 GB; "Ran 462 tests containing 33353 assertions" on the release-prep lane's final tree, 72 s; "Ran 460 tests containing 33339 assertions" on 2026-10-19, eve.thread's battery, the meta-atom's
#   suite (eve.thread.meta), eve.thread.fold's suite and the thread
#   feature's conformance with them)

node target/eve-test/all.js thread-host   # → 13 tests, 54 assertions (workers by the fat-kernel route)
node target/eve-test/all.js thread        # → 14 tests, 144 assertions (eve.thread's battery: the routed form, forms refused — no eval)
node target/eve-test/all.js thread-meta   # → 14 tests, 103 assertions (eve.thread.meta: the meta-atom's value; in "all")
node target/eve-test/all.js thread-domain # → 12 tests, 146 assertions (eve.thread.domain over a disk domain; not in "all";
                                          #   peak 1.6 GB, the process tree, the final pass, 36 s; ~3.8 GB before Node's heartbeat timer was
                                          #   unref'd)
node target/eve-test/all.js thread-fold   # → 7 tests, 332 assertions (eve.thread.fold: fold and =>> over the pools; in "all")
node target/eve-test/all.js thread-fold-group # → 3 tests, 49 assertions (the final pass, 16 s) (fold and =>> on a group of runtimes over a disk
                                              #   domain; not in "all", as thread-domain)

node target/eve-test/all.js dataset       # → 23 tests, 98 assertions
node target/eve-test/all.js tensor        # → 15 tests, 59 assertions
node target/eve-test/all.js array-hosts   # → 3 tests, 50 assertions
node target/eve-test/all.js deftype       # → 63 tests, 710 assertions (deftype, int-map, rb-tree, type-id)
node target/eve-test/all.js rrb-vector    # → 22 tests, 68 assertions (24 s, the final pass; not in "all")
node target/eve-test/all.js net           # → 62 tests, 1429 assertions, 0 failures (2026-10-21 ET, the final tree's final pass,
                                          #   274 s, peak 1.9 GB, the process tree; 2026-10-20, the final sweep, 313 s, peak 4.2 GB; the release-prep lane's final tree, 302 s; earlier: 53 tests, 1348 assertions (eve.net, the network atom, MD-4's pruned swap, and aegis's Poseidon2 / the v2 seal; counts as ET-3 measured them — not in "all"))

clojure -M:jvm-test
# → 546 tests, 9,615 assertions, 0 failures, 0 errors (2026-10-21 ET, the final tree's final pass
#   (`doc/release-0.9/logs-2026-10-22/f-jvm.log`), 136 s, peak 3.4 GB, the process tree; the assertion count moves run to
#   run, jvm-fuzz-test asserting per generated case: 9,634 and 9,766 on the same code earlier that evening). Earlier: 537 tests, 9,584 assertions, 0 failures, 0 errors (2026-10-21, the 9.25 round's batteries on `08b94cbf4`, 183 s; eve.jvm-atom-retire-test and eve.reclaim-test with them; 9,742 on `0eddc94d0` — jvm-fuzz-test asserts per generated case). Earlier: 522 tests, 8,078 assertions, 0 failures, 0 errors (2026-10-20, the 0.9 preparation's final sweep, 163 s, peak 3.4 GB:
#   364 s before the JVM region's reflection fix). 520 tests, 7,964 assertions on the release-prep lane's final tree.
#   Before it: 489 tests, 0 failures (composed at collation 2026-10-19: ET-4 measured 483 on its tree — fold's ten tests with
#   them, 7259 assertions — and MD-4's six eve.net deftests compose on top; re-measured at the next full battery; the
#   assertion count moves run to run: jvm-fuzz-test asserts per generated case; eve.thread.meta's, eve.thread.domain's
#   and eve.thread.fold's suites, eve.net's tests, the thread conformance and eve.thread's battery included)

# The shared .cljc tests on nbb and babashka (see doc/data-structures.md#hosts)
bb test:nbb
# → ":summary {:test 242, :pass 4617, :fail 0, :error 0}" (2026-10-21 ET, the final tree (`doc/release-0.9/logs-2026-10-22`)). Earlier: ":summary {:test 236, :pass 4600, :fail 0, :error 0}" (2026-10-21, the 9.25 round's batteries on `0eddc94d0` and again on `08b94cbf4`, three lanes). Earlier: ":summary {:test 228, :pass 3025, :fail 0, :error 0}" (2026-10-20, the final sweep, 606 s, peak 4.6 GB; killed at 12.2 GB
#   before Node's heartbeat timer was unref'd: every worker that opened a disk domain stayed alive). Earlier:
#   ":summary {:test 219, :pass 2946, :fail 1, :error 0}" (2026-10-19, eve.thread's and eve.thread.fold's suites with them;
#   the fail: ET-1's conveyance-across-the-pool once, a routed worker's exit notice not received in 30 s — ET-4's report — the runner
#   yields to the event loop between test vars: Node releases an ended worker only on its starter's loop; the keyring tests
#   need eve's mmap_cas addon: npm install first)
bb test:bb
# → ":summary {:test 247, :pass 4748, :fail 0, :error 0}" (2026-10-21 ET, the final tree's final pass
#   (`doc/release-0.9/logs-2026-10-22/f-bb.log`), 306 s, peak 0.4 GB, the process tree; the same again over `28e2c611`'s
#   lock change on a second box, 417 s — the tree before the change 407 s there: the box, `f-ab-bb-*.log`). Earlier: ":summary {:test 241, :pass 4731, :fail 0, :error 0}" (2026-10-21, the 9.25 round's batteries on `0eddc94d0` and again on `08b94cbf4`, three lanes). Earlier: ":summary {:test 233, :pass 3156, :fail 0, :error 0}" (2026-10-20, the final sweep, 377 s). Earlier:
#   ":summary {:test 231, :pass 3135, :fail 0, :error 0}" (2026-10-20, 7 min 00 s, the 0.9 release-prep lane's base run,
#   and the same on its final tree, 6 min 13 s;
#   one order-dependent defect in eve.net.codec fixed 2026-10-20: eve.thread-net-test alone now 5 tests, 45 pass —
#   `bb test:bb <ns> ...` runs the named namespaces alone)
bb test:bb-minimal
# → ":summary {:test 242, :pass 4623, :fail 0, :error 0}" (2026-10-21 ET, the final tree's final pass
#   (`doc/release-0.9/logs-2026-10-22/f-bb-minimal.log`), 315 s). Earlier: ":summary {:test 236, :pass 4606, :fail 0, :error 0}" (2026-10-21, the 9.25 round's batteries on `0eddc94d0` and again on `08b94cbf4`, three lanes). Earlier: ":summary {:test 228, :pass 3031, :fail 0, :error 0}" (2026-10-20, the final sweep, 360 s). Earlier:
#   ":summary {:test 226, :pass 3010, :fail 0, :error 0}" (2026-10-20, 6 min 21 s, and the same on the lane's final tree;
#   babashka on the minimal + inbuilt
#   set, hosts.edn :bb-minimal — every shared namespace but eve.net's, which need the http feature;
#   doc/adopting-eve-minimal.md)

# The shared .cljc tests on ClojureCLR (cljr on PATH; CLOJURE_LOAD_PATH=src:<hosts.edn :cljr roots>:test)
bb test:cljr
# → ":summary {:test 202, :pass 4499, :fail 0, :error 0}" (2026-10-21 ET, the final tree (`doc/release-0.9/logs-2026-10-22`)). Earlier: ":summary {:test 196, :pass 4482, :fail 0, :error 0}" (2026-10-21, the 9.25 round's batteries on `0eddc94d0` and again on `08b94cbf4`, three lanes). Earlier: ":summary {:test 188, :pass 2907, :fail 0, :error 0}" (2026-10-20, the final sweep, 203 s; ClojureCLR 1.12.6, .NET 8).
#   Earlier: ":summary {:test 174, :pass 2051, :fail 0, :error 0}" (2026-10-19, ~2.5 min; the typed-array
#   suites — array-hosts, dataset, tensor, coalesc-align — are not on ClojureCLR yet)

# The shared .cljc tests on basilisp (basilisp on PATH; compiled afresh into
# target/lpycache-test each run)
bb test:lpy
# → ":summary {:test 180, :pass 4350, :fail 0, :error 7}" (2026-10-22 ET, 9.34's tree — every battery re-run on it,
#   doc/release-0.9/REPORT.md §0 (`logs-2026-10-22/f3-lpy.log`), 56 min alone, peak 1.2 GB: every error an
#   eve.thread.domain 120 s wait run out late in the run (9.26), fold on a group's three with its own leak (9.35); the
#   failed attach!'s ticker now stops (9.34, `f3-lpy-threads.log`); eve.thread-net-test alone 5 / 47 (1,214 s) and
#   eve.thread-fold-group-test alone 3 / 49 (1,016 s), green). Earlier: ":summary {:test 180, :pass 4360, :fail 0, :error 5}" (2026-10-22 ET, the final tree's final pass
#   (`doc/release-0.9/logs-2026-10-22/f-lpy.log`), 57 min alone, peak 1.0 GB: 9.26's error, then four left by it — the
#   failed tests' tickers beat on under the GIL (RELEASE-CHECKLIST 9.34); eve.thread-net-test alone 5 / 47 (21 min) and
#   eve.thread-fold-group-test alone 3 / 49 (16 min), green). Earlier: ":summary {:test 174, :pass 4397, :fail 0, :error 1}" (2026-10-21, before 9.31; the 9.25 round on `0eddc94d0`, 75 min beside the other lanes; the error is 9.26's, the same test. On `08b94cbf4`, 65 min beside them: 4,396 pass, the same error and one fail — eve.thread-fold-test's chunk-size timing bar, "672 < 672", under the lanes' load; that namespace alone after them, 7 / 332, 0 failures, 84 s). Earlier: ":summary {:test 166, :pass 2825, :fail 0, :error 1}" (2026-10-21, alone, 52 min, peak 852 MB; the error:
#   eve.thread-net-test's domain pool over a network atom, "the ticker did not answer" — attach!'s 120 s for the ticker's
#   first beat, late in the full run; that namespace alone 5 tests, 47 pass (17 min), and after eve.thread-domain-test
#   12 / 145: checklist 9.26). bb test:lpy eve.domain-open-test eve.thread-test 16 / 159 over this round's fixes.
#   Earlier: ":summary {:test 142, :pass 1588, :fail 0, :error 0}" (2026-10-19, ~6.7 min, eve.thread's battery with them; eve.thread.fold's
#   two suites add 10 tests, 381 assertions (2026-10-19, run alone: ET-4's report); 61 / 844 on
#   2026-10-17: hamt, fixtures, type ids, rb-tree, int-map, after basilisp's fixed-arity
#   completion and the bitmap search fix). core.rrb-vector's suite runs when named
#   (bb test:lpy eve.rrb-vector-test, or one test: eve.rrb-vector-test/test-seq):
#   it is slow on basilisp (a 40,000-element vector: 46 s to build, 10.9 ms per assoc, and basilisp's own
#   dotimes 1.8 µs an iteration, in the final pass's probe — doc/release-0.9/logs-2026-10-22/f-probe-lpy-costs.log;
#   100 s, 55 ms and ~35 µs were quoted before it)

# The shared .cljc tests on jolt (jolt on PATH; ~18 s of it compiling eve)
bb test:jolt
# → ":summary {:test 199, :pass 4479, :fail 0, :error 0}" (2026-10-21 ET, the final tree (`doc/release-0.9/logs-2026-10-22`)). Earlier: ":summary {:test 193, :pass 4462, :fail 0, :error 0}" (2026-10-21, the 9.25 round's batteries on `0eddc94d0` and again on `08b94cbf4`, three lanes). Earlier: ":summary {:test 185, :pass 2887, :fail 0, :error 0}" (2026-10-20, the final sweep, 233 s, peak 1.9 GB beside basilisp's
#   battery; heap regions are Chez's immobile bytevectors, bounded by jolt's heap ceiling). Earlier:
#   ":summary {:test 176, :pass 2078, :fail 0, :error 0}" (2026-10-19, ~3.7 min; eve.thread.fold's suites, hamt, fixtures,
#   type ids, rb-tree, int-map, core.rrb-vector's suite, the numeric tags, the thread conformance)
#   2026-10-20, the 0.9 release-prep lane's final tree: ":summary {:test 178, :pass 2087, :fail 0, :error 0}" (247 s;
#   jolt from /opt/eve-tools needs PATH and JOLT_CHEZ=/opt/eve-tools/chez/bin/scheme; before the memory fixes of
#   2026-10-20 it and bb test:glj together were OOM-killed on a 15 GB box, glj-eve at 7.1 GB and jolt's Chez at 5.8 GB;
#   side by side after them (2026-10-20): 1.2 GB and 1.9 GB peaks, green; in the final pass (`f-side-*.log`) 2.7 GB and
#   1.7 GB, the process trees, both green)

# The shared .cljc tests that compile on ValueScript (vs and wasmtime on PATH; eve/deftype's
# types wait on VS's analyzer and macro evaluator, EM4) — hamt-util, the byte fixtures — and the
# slab-io conformance test
bb test:vs
# → 260 passed, 0 failed (2026-10-22 ET, the final tree's final pass on atom.broker main `f8cfdb0dd`'s builder
#   (`doc/release-0.9/logs-2026-10-22/f-vs.log`), 123 s). Earlier: 260 / 0 (2026-10-21, the 9.25 round: eve.slab-io's conformance test expected the allocation log as a vector, a set since `7faf6597f` — 259 / 1 before its fix). Earlier: hamt-util and the byte fixtures (71 rows, 0 skipped): 209 passed, 0 failed (2026-10-19, main's
#   builder); then the slab-io test's first allocation stops in ctx-alloc!'s letfn (regions read
#   as nil): VS's letfn-capture defect, L20's (vs/test-xfail/jit-backend/letfn_outer_capture_test.vs),
#   which docs/eve-on-vs/setup.sh's patch works around (L19: 102 passed over that tree)

# The shared .cljc tests on clojerl (clojerl on PATH; eve's NIF is built on first
# load into target/clje/ — gcc and the BEAM's erl_nif.h; ~20 s compiling eve)
bb test:clje
# → ":summary {:test 198, :pass 4400, :fail 0, :error 0}" (2026-10-22 ET, the final tree's final pass
#   (`doc/release-0.9/logs-2026-10-22/f-clje.log`), 16 min, peak 11.1 GB, the process tree — not investigated; the same
#   again over 4eac2122e's NIF change, f2-clje.log). Earlier: ":summary {:test 192, :pass 4383, :fail 0, :error 0}" (2026-10-21, before 9.31; the 9.25 round, 19 min, after `ca70b7c34`: eve.reclaim-test's value defs as functions — clojerl's def takes literals only; the same on `08b94cbf4`, 18 min). Earlier: ":summary {:test 184, :pass 2808, :fail 0, :error 0}" (2026-10-21, 21 min; the final sweep's run stopped at load —
#   eve.keyring's new ^bytes hint without eve.host's bytes referred: clojerl resolves a hint's symbol). Earlier:
#   ":summary {:test 175, :pass 2731, :fail 0, :error 0}" (2026-10-19: eve.thread's battery, eve.thread.fold's suites, hamt, fixtures,
#   type ids, rb-tree, the numeric and reference tags, big values, sorted collections,
#   the key ring, value buffers, the vector retire walk, domain open, int-map, core.rrb-vector's
#   suite; most of the time the RRB suite; 4
#   fixture rows skipped: a char, a date finer than a second, two keywords past
#   255 characters — clojerl cannot hold them)
#   2026-10-20, the 0.9 release-prep lane: red at its base (112f6ced2b) — exit 1 at load in ~40 s, eve.net.merkle's
#   constants were defs of delays (clojerl compiles a def's value as a literal; eve.thread-net-test loads the namespace
#   through eve.net.codec); now kept in an atom. Final tree: ":summary {:test 182, :pass 2787, :fail 0, :error 0}"
#   (18 min; clojerl from /opt/eve-tools on PATH)

# The shared .cljc tests on glojure (glj-eve: glojure v0.7.22 with eve's Go packages and its completion's patches,
# built into target/glj-eve by impls/glojure/build.sh — bb glj-eve, cached by its source stamp; go on PATH)
bb test:glj
# → ":summary {:test 182, :pass 4458, :fail 0, :error 0}" (2026-10-21 ET, the final tree (`doc/release-0.9/logs-2026-10-22`)). Earlier: ":summary {:test 176, :pass 4441, :fail 0, :error 0}" (2026-10-21, the 9.25 round's batteries on `0eddc94d0` and again on `08b94cbf4`, three lanes). Earlier: ":summary {:test 168, :pass 2866, :fail 0, :error 0}" (2026-10-20, the final sweep, 497 s beside basilisp's battery,
#   peak 2.4 GB; hashing, the network rung, fold and fold on a group now in the default run). Earlier:
#   ":summary {:test 149, :pass 2403, :fail 0, :error 0}" (2026-10-20, 141 s, lane EVE-GLJ: hamt, fixtures, type ids,
#   rb-tree, the numeric and reference tags, big values, sorted collections, the key ring, value buffers, the vector
#   retire walk, domain open, int-map, the thread conformance, eve.thread's battery, the meta-atom, the domain cell).
#   core.rrb-vector's suite runs when named (bb test:glj eve.rrb-vector-test): 22 tests, 66 pass, 21 min there (the final
#   pass, `doc/release-0.9/logs-2026-10-22/f-glj-rrb.log`; ~24 min on 2026-10-20)
#   2026-10-20, the 0.9 release-prep lane: red at its base (112f6ced2b) — hosts.edn :glj had no eve.slab-io root and
#   eve.thread.rung / .net's records carried inline protocol methods, which glojure refuses; both fixed, re-measured
#   ":summary {:test 149, :pass 2403, :fail 0, :error 0}" (3 min 17 s); the lane's final tree: the same (182 s)
# The thread feature's conformance in a browser (HOST-CONTRACT §11)
bb test:browser
# → ":summary {:test 48, :pass 633, :fail 0, :error 0, :main {...all true}}" (2026-10-22 ET, the final tree's final pass
#   (`doc/release-0.9/logs-2026-10-22/f-browser.log`), 36 s; 2026-10-21, the 9.25 round on `08b94cbf4`, 42 s; and 2026-10-20, the final sweep, 44 s; the
#   browser's slabs grow: Chromium caps a renderer's SharedArrayBuffers at ~16 GB). Earlier the same (2026-10-19, the conformance, eve.thread's battery and
#   eve.thread.fold's suite; headless
#   Chromium from /opt/pw-browsers, the page cross-origin isolated); 2026-10-20, the 0.9 release-prep lane's final
#   tree (the browser http adapter's batches, the thread adapter's worker-message hook): the same, 65 s

# The performance suite (test/eve/perf_suite.cljc: ten workloads over the public API) on every host, one at a time
bb scripts/perf-all.clj              # → doc/hosts/perf-results.edn (merged by host); the tables in doc/hosts/
bb scripts/perf-all.clj jvm glj      # the hosts named; each host's runner also takes `perf` (eve.runner.*-main perf)
EVE_BROWSER_PERF=1 bb test:browser   # the browser's, a page of its own (:eve-perf-browser)

# The two-process rendezvous (lane ET-2): two processes, one domain, one registry, a member killed and adopted
bb scripts/thread-rendezvous.clj jvm lpy    # (any pair of jvm bb nbb clje cljr lpy jolt glj)
# → {... :ok true ...}: 18 of 18 checks on each of nine pairs (2026-10-19: jvm with jvm, nbb both ways, bb both ways,
#   clje, cljr, jolt, lpy; 15 s – 3 min a pair), and with glojure (2026-10-20: jvm glj, glj jvm, glj glj; 26–38 s)

# The multi-runtime rotation (the EVE-THREAD plan's S3 on ET-2's domain cell): one member per host named, one disk
# domain, one domain pool, the coordinator role passed along the turn order
bb scripts/thread-rotation.clj jvm glj bb nbb clje    # (any of jvm bb nbb clje cljr lpy jolt glj, a host twice ok)
# → {... :ok true ...}: every turn's sum right, the turn order kept, one registry, every member's threads ran work
#   (2026-10-20: five members, 57 s)

# fold on a group of processes (lane ET-4): two members serve one domain pool, each folds over it
bb scripts/thread-fold-rendezvous.clj bb bb    # (any pair of jvm bb nbb clje cljr lpy jolt)
# → {... :ok true ...} (2026-10-19, bb with bb: both members ran chunks, every value equal to the sequential one, each
#   member's swap cost published, b's chunks sized from the slower member's, the seal read back; the other pairs not run)
```

If ANY of these fail, stop and fix the regression before any other work.

---

## Forbidden Patterns

### 1. The `.main` file does not exist

There is no `.main` file, block descriptor table, status-mirror array,
capacity-mirror array, WASM scratch region, or `BD_ARRAY_START = 6168` constant.

The cross-process atom files are: `.slab0`–`.slab5`, their bitmaps `.slab0.bm`–`.slab5.bm`, the overflow class
`.slab6`, `.root`, `.rmap`, and, for an encrypted domain (`eve.keyring`), `.keyring`.

### 2. Flat serialization functions are forbidden in `atom.cljc`

`atom.cljc` must not use `serialize-flat-element` or `value->eve-bytes`.

Note: `0xED` and `0xEE` are also the HAMT type-id headers for EveHashMap and
EveHashSet respectively — their use as type-id headers in `jvm-read-root-value`
is correct. The prohibition is specifically on flat *serialization* functions,
not on the HAMT type-id constants.

### 3. SAB pointer tags (0x10–0x13) encoding

Tags `0x10`–`0x13` encode nested collection slab-qualified offsets. In
cross-process atom serialization, these tags point into mmap-backed slabs.
In SAB atoms, they point into SharedArrayBuffer-backed slabs. The tag
format is shared; only the backing store differs.

---

## Invariants

### Slab header (per-slab file, byte offsets)
| Offset | Field |
|---|---|
| 0 | magic `0x534C4142` |
| 4 | block size |
| 8 | total blocks |
| 12 | free count (atomic) |
| 16 | alloc cursor |
| 20 | class index |
| 24 | bitmap offset |
| 28 | data offset |

Slab class sizes: `[32, 64, 128, 256, 512, 1024]` bytes.

---

## Build Commands

```bash
# Compile test bundles (eve-test + mmap-worker for e2e tests)
npm run test:compile

# Run specific test suite
node target/eve-test/all.js <suite>
# Suites (test/eve/runner/eve_test_main.cljs, suite-runners): all, core, array, slab, large-scale, epoch-gc, obj,
#         batch2, batch3, batch4, int-map, rb-tree, deftype, validation, typed-array, mem, mmap, mmap-slab, slab-grow, perf,
#         mmap-atom, mmap-atom-e2e, mmap-domain, conformance, fuzz, fixtures, em5, hamt-util, lustre, lustre-bench,
#         dataset, array-hosts, tensor, rrb-vector, columnar-bench, columnar-mmap, columnar-inmem, columnar-one,
#         perf-audit, repro, slab-leak, net, thread-host, thread, thread-meta, thread-domain, thread-fold,
#         thread-fold-group
#   (aliases: map-test vec-test list-test set-test → slab; deftype-test → core; array-test → array;
#    epoch-gc-test → epoch-gc. "all" is not every suite: it runs no mmap or mem suite, net, rrb-vector,
#    thread-domain, thread-fold-group, fuzz, lustre, conformance, em5, repro, slab-leak or bench)

# Build native addon
npm run build:addon

# Run JVM tests
clojure -M:jvm-test

# The shared (.cljc) tests on babashka, nbb and ClojureCLR, from the source tree
bb test:bb        # bb -cp src:<hosts.edn :bb roots>:test -m eve.runner.bb-main test [ns ...]
bb test:bb-minimal # the same runner on hosts.edn :bb-minimal (the minimal + inbuilt adapters), eve.thread-net-test (the network rung, which needs http) left out
bb test:nbb       # nbb --classpath src:<hosts.edn :nbb roots>:test -m eve.runner.nbb-main test
bb test:cljr      # CLOJURE_LOAD_PATH=src:<hosts.edn :cljr roots>:test cljr -m eve.runner.cljr-main test
bb test:lpy       # basilisp run -p test -p <each hosts.edn :lpy root, last first> -p src -n eve.runner.lpy-main test (basilisp searches the last -p first)
bb test:jolt      # jolt -Scp src:<hosts.edn :jolt roots>:test (absolute) -m eve.runner.jolt-main test
bb test:clje      # clojerl -pa src -pa <each hosts.edn :clje root> -pa test -m eve.runner.clje-main test
bb test:vs        # VS_CLJ_ROOTS=src:<hosts.edn :vs roots>:adapters/slab-io/test:test vs compile test/eve/runner/vs_main.vs; wasmtime run
bb test:glj       # bb glj-eve, then GLJ_CLASSPATH=src:<hosts.edn :glj roots>:test target/glj-eve/glj-eve -e "(eve.runner.glj-main/-main \"test\")"
bb test:browser   # the thread conformance in headless Chromium: shadow (deps.edn :browser-test) + http-kit + /opt/pw-browsers
```

### Test output rule

Always tee test output to files so you never have to run the same test twice:

```bash
node target/eve-test/all.js slab 2>&1 | tee /tmp/slab-test.txt
clojure -M:jvm-test 2>&1 | tee /tmp/jvm-test.txt
```

---

## Namespace Map

| Namespace | File | Purpose |
|---|---|---|
| `eve.alpha` | `src/eve/alpha.cljc` | Public API entry point, every host (a ClojureScript compiler takes its macros through `adapters/core/cljs-compiler`'s `eve/alpha.cljs`) |
| `eve.atom` | `src/eve/atom.cljc` | Cross-process mmap atom |
| `eve.mem` | `src/eve/mem.cljc` | IMemRegion protocol |
| `eve.hamt-util` | `src/eve/hamt_util.cljc` | Shared HAMT bitwise helpers + portable hash |
| `eve.deftype-proto.alloc` | `src/eve/deftype_proto/alloc.cljc` | Slab allocator: ISlabIO, the offset encoding, JvmSlabCtx (a domain over IMemRegions, every host), the openers through the host's process slab |
| `eve.process-slab` | `adapters/slab/{module,heap}/` | this process's own slab: ClojureScript's module slabs (registered with alloc; with them each slab's WASM instance, `eve.deftype-proto.wasm`, and the SAB atom's `eve.data` / `eve.util`), or nothing (alloc's heap domain) |
| `eve.typed-view` | `adapters/typed-view/js/` | ClojureScript's views of an EveArray: JS TypedArray, buffer, wasm-SIMD bulk ops |
| `eve.deftype-proto.data` | `src/eve/deftype_proto/data.cljc` | Slab constants |
| `eve.deftype-proto.serialize` | `src/eve/deftype_proto/serialize.cljc` | what the codecs share: tags, the constructor and writer registries |
| `eve.slab-io` | `adapters/slab-io/{protocol,cljs-compiler,vs}/` | the memory-level seam: every slab access in the shared code (a domain's slab I/O, the allocator's region words and bitmaps, the fused `bytes-equal-at?` / `bitmap-find-free`); macros over `ISlabIO` / `IMemRegion` on every host before ValueScript, plain functions over linear memory on ValueScript (HOST-CONTRACT §12) |
| `eve.int32` | `adapters/int32/{js,jvm,portable,vs}/` | 32-bit integer arithmetic (Murmur3, FNV-1a, HAMT bitmaps), the same signed results on every host |
| `eve.codec` | `adapters/codec/{js,byte-array}/` | the codec, values ⇄ EVE bytes: ClojureScript's typed arrays, every other host's byte arrays |
| `eve.deftype-proto.coalesc` | `src/eve/deftype_proto/coalesc.cljc` | Coalescing overflow allocator |
| `eve.map` | `src/eve/map.cljc` | Eve HAMT map |
| `eve.vec` | `src/eve/vec.cljc` | Eve persistent vector |
| `eve.set` | `src/eve/set.cljc` | Eve persistent set |
| `eve.list` | `src/eve/list.cljc` | Eve persistent list |
| `eve.array` | `src/eve/array.cljc` | Typed array API |
| `eve.obj` | `src/eve/obj.cljc` + `obj/impl.cljc` | Typed objects (AoS/SoA) in slab memory, every host; the types and the 0x1E block format are `eve.obj.impl`'s (sci's deftype calls `clojure.core/get` from its own namespace, which `eve.obj` replaces) |
| `eve.shared-atom` | `src/eve/shared_atom.cljs` | SAB-backed atom (CLJS) |
| `eve.deftype` | `src/eve/deftype.cljc` | `eve-deftype` (a type in eve memory) and `deftype` (a type on the host), written with ClojureScript's protocol names or the JVM's; each host's realization is its `eve.types` adapter (a ClojureScript compiler takes the macros through `adapters/core/cljs-compiler`'s `eve/deftype.cljs`) |
| `eve.deftype.macros` | `src/eve/deftype/macros.cljc` | their expansion, one for every host |
| `eve.deftype.array` | `src/eve/deftype/array.cljc` | arrays of eve values in eve memory (what an eve type holds where a host type holds an array) |
| `eve.deftype-proto.macros` | `src/eve/deftype_proto/macros.cljc` | the layer under it (collections, EveArray); bb translation onto `eve.core`, `host-deftype` |
| `eve.core` | `adapters/coll/{jvm,cljs,nbb,protocols,basilisp,beam}/` | the collection vocabulary every EVE value answers on every host (`clojure.core`'s on JVM/CLJR/jolt, cljs.core's on CLJS/nbb, protocols on bb, basilisp's, clojerl's), and the vocabulary a type is written in (ClojureScript's protocol fns) |
| `eve.coll` | `src/eve/coll.cljc` | cljs.core's collection helpers (equiv-sequential, ci-reduce, pr-sequential-writer, ...), written once over eve.core |
| `eve.rseq` | `src/eve/rseq.cljc` | RSeq, an indexed collection's reverse seq, written once |
| `eve.deftype.int-map` | `src/eve/deftype/int_map.cljc` | Integer map (a PATRICIA trie of eve-deftypes) — one source, every host |
| `eve.deftype.rb-tree` | `src/eve/deftype/rb_tree.cljc` | Sorted set — one eve-deftype source, every host |
| `eve.rrb-vector` | `src/eve/rrb_vector.cljc` + `rrb_vector/` | core.rrb-vector's ClojureScript implementation in eve memory, every host (EPL 1.0 files; the example of writing an eve type — doc/data-structures.md#rrb-vector--corerrb-vector-in-eve-memory) |
| `eve.thread-host` | `adapters/thread/{jvm,node,browser,beam,python,clr,glojure,none}/` | the thread feature (HOST-CONTRACT §11): threads, mailboxes, a real blocking wait, exit notices, the host's catch-all, binding frame, var names and evaluator — what `eve.thread`'s executor service runs on; proven per host by `test/eve/thread_host_test.cljc` |
| `eve.thread` | `src/eve/thread.cljc` (+ `adapters/core/cljs-compiler`'s `eve/thread.cljs`, the same api-defs) | the executor service, every host (lane ET-1): `future`, `promise`, `pmap` (unchunked), `pcalls`, `pvalues`, `with-pool`, the pools, `IExecutor`, `deftask` / `defconveyed` / `tfn`; proven by `test/eve/thread_test.cljc` |
| `eve.thread.descriptor` | `src/eve/thread/descriptor.cljc` | the thread descriptor: P§5.4's state machine (queued/running/blocked/done/failed/cancelled), claim tokens, adoption by restart type — data, never a stack |
| `eve.thread.cell` | `src/eve/thread/cell.cljc` | the engine: rung-0 cells (local: threads pull from host atoms; routed: an isolate's home routes data), the helping wait, the pumps |
| `eve.thread.task` / `.future` / `.pool` | `src/eve/thread/{task,future,pool}.cljc` | code identity and conveyance; the future and promise as eve types; fixed, single, cached, per-task and scheduled pools |
| `eve.thread.meta` | `src/eve/thread/meta.cljc` | the meta-atom's value (lane ET-2): descriptors, runtimes and pools, thread-grain leases, bring-up components, named leases (election), transports and the upgrade's hello/ack — every move a pure function of the value; proven by `test/eve/thread_meta_test.cljc` (random interleavings, seeded) |
| `eve.thread.domain` | `src/eve/thread/domain.cljc` | the domain cell (lane ET-2): a rendezvous atom's meta-atom (`eve.atom/meta-atom`) as the home of pools, the registry, bring-up, election and the upgrade — `attach!`, `pool`, `registry`, `desire!`/`start!`, `elect!`, `hello!`/`ack-hellos!`; a ticker thread per runtime keeps its leases and sweeps; a writer whose swaps keep losing the meta-atom's races takes a turn (announced in the meta-atom's own meta-atom); proven by `test/eve/thread_domain_test.cljc` and the two-process `scripts/thread-rendezvous.clj` |
| `eve.thread.fold` | `src/eve/thread/fold.cljc` (+ the `=>>` macro in `src/eve/thread.cljc`) | fold and `=>>` over eve.thread's pools (lane ET-4): `fold` (`clojure.core.reducers/fold` — chunks as thread descriptors, combined in order; a map folds as `reduce-kv`), `=>>` (`->>` with its separable transducer runs — map filter remove keep mapcat cat replace — folded over the pool, the others stepped here by `xf-seq`), chunks sized from the pool's measured swap cost and carry (`swap-cost`, `chunk-size`: a descriptor's work ≥ 10 × its swaps; none worth it → reduced here); called as `eve.thread.fold/fold` (a var `eve.thread/fold` would compile over this namespace's object in ClojureScript); proven by `test/eve/thread_fold_test.cljc`, `thread_fold_group_test.cljc` and the two-process `scripts/thread-fold-rendezvous.clj` |
| `eve.thread.rung` | `src/eve/thread/rung.cljc` | the rungs a domain cell lives on (lane ET-3): `IRung` over an eve atom (`:heap`, `:disk`, `:window`) or a network atom (`:net`, eve.thread.net's); placement by configuration only (`place`, `rendezvous`: `:placement :disk` — the current directory unless `:disk-root` says otherwise — or `:window`, tmpfs); doorbell words beside a domain (`ring!`, `wait-bell!`: a futex where the host's region has one). A handle keeps the rung it was opened on: the rung is its developer's choice |
| `eve.thread.net` | `src/eve/thread/net.cljc` | the network rung (lane ET-3): a domain cell whose rendezvous atom is an eve.net network atom at a broker or a gateway; `hello-over-disk-only`, eve.thread's gateway policy |
| `eve.net.gateway` | `src/eve/net/gateway.cljc` | a gateway (lane ET-3): eve.net's broker routes served over a disk domain's atoms — any member serving its domain; small JSON only, blobs direct to a presigned store; a network CAS is one eve swap comparing content keys |
| `eve.http-server` | `adapters/http-server/{jdk,httpkit,node}/` | the http-server feature: `serve!` — what a member needs to run a gateway (JVM, babashka, ClojureScript on Node) |
| `eve.keyring` | `src/eve/keyring.cljc` | at-rest encryption of a disk domain's slab blocks (`open-encrypted-domain!` → `[slab-io ring]`): AES-256-CTR under HKDF-SHA256 per-class keys, the `.keyring` record, key modes auto / user / user-not-stored; metadata stays plaintext, no integrity (`SECURITY.md`) |
| `eve.sorted` | `src/eve/sorted.cljc` | sorted map and sorted set in eve memory, every host: `sorted-map`, `sorted-map-by`, `sorted-set`, `sorted-set-by`, `subseq`, `rsubseq`, `rank`, `nth-entry`, `find`, `dissoc!`, `disj!` |
| `eve.alpha.ds` / `eve.alpha.tensor` / `eve.alpha.col` | `src/eve/alpha/{ds,tensor,col}.cljc` | the columnar public API: datasets, N-d tensors, element-wise and index-space column ops (over `eve.dataset`, `eve.tensor`, `eve.dataset.functional`, `eve.dataset.argops`) |
| `eve.dataset` (+ `.argops`, `.functional`) | `src/eve/dataset.cljc`, `src/eve/dataset/` | named columns of EveArrays with atom-native storage |
| `eve.tensor` | `src/eve/tensor.cljc` | N-dimensional views over EveArrays: shape and strides for zero-copy reshaping |
| `eve.deftype.registry` | `src/eve/deftype/registry.cljc` | the compile-time type registry `eve-deftype` fills and later macros read (not available at run time) |
| `eve.deftype.value-buffer` | `src/eve/deftype/value_buffer.cljc` | a runtime's own arrays in eve memory: buffers of eve values that outgrow their place stay where they are |
| `eve.platform` | `src/eve/platform.cljc` | what the shared code needs that is the same on every host (over `eve.encoding`) |
| `eve.retire` | `src/eve/retire.cljc` | what a committed write does to its domain's blocks (9.31): the one walk of the old value against the new, each block kind read by its layout (`plan`, `apply!`) |
| `eve.tally` | `src/eve/tally.cljc` | how many hold each block (9.31): the count on the block's own 28-byte descriptor row (u32 at +8, its flags at +20), held when a write stores the block, dropped when one lets it go, freed at zero through the domain's zero queue under the epoch |
| `eve.net.*` | `src/eve/net/` | the network atom. User namespaces: `eve.net.atom` (`remote-atom`, `deref`, `swap!`, `reset!`, `release!`, `renew!`), `eve.net.gateway` (a disk domain's atoms served on the broker's routes), `eve.net.auth` (sign-in: device flow, refresh, token claims), `eve.net.platform` (the host's `{:request :sleep :now-ms :seed}`). The rest — aes, blobs, bn254, bytes, codec, crypto, ed25519, graft, json, merkle, portable-crypto, poseidon2, run, sha256, sha512, snapshot, u64, wire, xxh3 — are internal (`eve.net.portable-crypto` is the reference every crypto adapter is held to) |
| `eve.host`, `eve.host-completion`, `eve.region`, `eve.encoding`, `eve.process`, `eve.types`, `eve.crypto`, `eve.http` | `adapters/<feature>/<impl>/src/eve/` | the adapter interfaces a host supplies (`adapters/README.md`; HOST-CONTRACT §1–§12): the host's core names, its core's completion, regions, encoding, process primitives, type realization, crypto, synchronous HTTP — one implementation per capability, chosen by the host's `hosts.edn` row |
| `eve.aegis.*` | `src/eve/aegis/` | aegis's clear-evaluation primitives (mainlined 2026-10-23 from the crypto spike, aegis/docs/simple-spike/REPORT.md): `claims` (the claim, its digest, the evaluator over an app spec's branches, the spec id, the JSON wire, apply), `ballot` (partially-blind RSA ballots — RSAPBSSA-SHA384, the CFRG draft over RFC 9474 — Option 1), `rsabssa`, `clsag` (linkable ring signatures, Option 2), `h2c` (hash to curve), `merkle` (SHA-256 frontiers and sidecar maps), `session` (delegated one-show session keys), `commit` (the hiding room-keyed commitment), `hpke` / `regions` (the read-regions spike), `chat.*` (the REPL chat client, capstone/eve-chat: `g2` the web page's message encryption, `records`, `client`, `repl`); suites in the bb and JVM batteries, `test/eve/aegis/claims_bench.cljc` and `chat_bench.cljc` the cost rows |
| `eve.perf` | `bench/eve/perf.clj` | JVM profiling tools (the benches') |

---

## Protected Files

**Must not be deleted. Must not be modified without reading first.**

```
src/eve/shared_atom.cljs
```
