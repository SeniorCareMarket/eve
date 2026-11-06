# Testing

eve is one shared source run by many hosts, so its tests come in three layers: the **shared `.cljc` suites** every
host runs through its own runner; **ClojureScript's and the JVM's own runners**, which carry those suites plus tests
only those hosts have; and **conformance tests** a new adapter is held to. Multi-process scripts check what one
process cannot.

Counts are not pinned here: they move as suites grow, and a release records the run it was cut from
. Measured in the final pass on the final tree (2026-10-21 ET), on this
repository's box: `bb test:bb` — 247 tests, 4,748 assertions, 0 failures, 0 errors (5 min 06 s); `bb test:bb-minimal` —
242 tests, 4,623 assertions, 0 failures, 0 errors (5 min 15 s).

All commands run from `eve/`.

---

## The shared suites, per host

`bb.edn` composes each host's classpath from `hosts.edn` (`src`, the host's adapter roots, `test`) and starts the
host's runner (`test/eve/runner/<host>_main.*`), which runs the shared test namespaces the host loads as written:

| Command | Host | Needs on PATH | Runner |
|---|---|---|---|
| `bb test:bb` | babashka | `bb` | `eve.runner.bb-main` |
| `bb test:bb-minimal` | babashka on the minimal + inbuilt adapters only (`hosts.edn :bb-minimal`, `doc/adopting-eve-minimal.md`); every namespace of bb's runner but eve.net's, which need the http feature | `bb` | `eve.runner.bb-main` |
| `bb test:nbb` | nbb (Node + SCI) | `nbb`; eve's addon (`npm install`) for the key-ring and mmap tests | `eve.runner.nbb-main` |
| `bb test:cljr` | ClojureCLR | `cljr` (.NET) | `eve.runner.cljr-main` |
| `bb test:lpy` | basilisp | `basilisp` | `eve.runner.lpy-main` (compiled afresh into `target/lpycache-test`) |
| `bb test:jolt` | jolt | `jolt` | `eve.runner.jolt-main` |
| `bb test:clje` | clojerl | `clojerl`, gcc and the BEAM's `erl_nif.h` (eve's NIF builds on first load into `adapters/core/beam/target/clje/`) | `eve.runner.clje-main` |
| `bb test:glj` | glojure as glj-eve | `go` (`bb glj-eve` builds `target/glj-eve/glj-eve` from `impls/glojure/build.sh`, cached by its source stamp) | `eve.runner.glj-main` |
| `bb test:vs` | ValueScript | `vs`, `wasmtime` | `test/eve/runner/vs_main.vs` (the namespaces that compile on VS, and the slab-io conformance test) |
| `bb test:browser` | ClojureScript in headless Chromium | the JVM (shadow-cljs through `deps.edn :browser-test`), Chromium (`/opt/pw-browsers`) | `scripts/test-browser.clj` (the page served cross-origin isolated) |

Every host task but `test:vs` and `test:browser` takes namespace names after it and runs only those (`bb test:bb
eve.thread-net-test`); the jolt, clje, glj and lpy runners also take `ns/test` for one test. Some suites
run only when named on slow hosts (core.rrb-vector's on basilisp and glojure); each runner's `shared-tests` docstring
says which.

## ClojureScript (Node)

```bash
npm install                 # eve's N-API addon (node-gyp-build compiles native/mmap_cas.cc)
npm run test:compile        # shadow-cljs: the eve-test bundle and the mmap-worker it spawns
node target/eve-test/all.js <suite>
```

The suites (`test/eve/runner/eve_test_main.cljs`, `suite-runners`):

| Suite | What |
|---|---|
| `all` | the in-memory suites together — **not** every suite: no `mem` or mmap suite, `slab-grow`, `net`, `rrb-vector`, `thread-domain`, `thread-fold-group`, `fuzz`, `lustre`, `conformance`, `perf` |
| `core`, `array`, `slab`, `large-scale`, `epoch-gc`, `obj` | deftypes, typed arrays, the collections (map, vec, list, set, the vector retire walk, reclaim, the tally), scale, the epoch surface, typed objects |
| `batch2`, `batch3`, `batch4`, `validation` | validation batches |
| `slab-grow` | batch2 and the slab suites over slabs that start small and grow (`ps/init! :growable? true`) |
| `int-map`, `rb-tree`, `deftype` | the eve-deftype collections (`deftype` = deftype, int-map, rb-tree, type ids) |
| `typed-array`, `mem` | typed arrays, regions |
| `mmap`, `mmap-slab`, `mmap-atom`, `mmap-atom-e2e`, `mmap-domain` | file regions, disk slabs, disk atoms, two processes (`mmap-atom-e2e` spawns the `mmap-worker` bundle), domains |
| `conformance`, `fuzz`, `fixtures`, `hamt-util` | core-function conformance, generative tests, the byte fixtures, HAMT helpers and coalesced alignment |
| `em5` | numeric tags, collection hashes, reference tags, big values, sorted collections, the key ring, value buffers |
| `dataset`, `array-hosts`, `tensor`, `rrb-vector` | columnar data, typed arrays across hosts, tensors, core.rrb-vector in eve memory |
| `lustre`, `lustre-bench` | the fcntl-lock region (Lustre) |
| `net` | eve.net: JSON, graft, XXH3, the codec and its fixtures, Poseidon2, the wire, the remote atom, crypto, Ed25519, sign-in, the pruned swap |
| `thread-host`, `thread`, `thread-meta`, `thread-domain`, `thread-fold`, `thread-fold-group` | the thread feature's conformance, eve.thread's battery, the meta-atom, the domain cell, fold (alone and on a group of runtimes) |
| `columnar-bench`, `columnar-mmap`, `columnar-inmem`, `columnar-one`, `perf-audit`, `repro`, `slab-leak`, `perf` | benches and diagnostics, not pass/fail suites (`perf` is the performance suite, `test/eve/perf_suite.cljc`) |

Aliases: `map-test`, `vec-test`, `list-test`, `set-test` → `slab`; `deftype-test` → `core`; `array-test` → `array`;
`epoch-gc-test` → `epoch-gc`. `thread-domain` and `thread-fold-group` start worker isolates and are heavy in memory;
they are kept out of `all`.

## The JVM

```bash
clojure -M:jvm-test                       # every namespace under test/ the JVM loads (.clj and .cljc)
clojure -M:jvm-test -n eve.thread-net-test   # one namespace (cognitect test-runner options)
clojure -M:jvm-e2e-test                   # test-e2e/: the JVM atom across processes, the README example, type-id order
```

`:jvm-test` runs cognitect's test-runner over `test/` with test.check on the classpath and the JVM options the
regions need (`--add-opens java.base/java.lang=ALL-UNNAMED`, `--enable-native-access=ALL-UNNAMED`). `:jvm-e2e-test`
runs the same over `test-e2e/` (`jvm_atom_e2e_test.clj`, `readme_example_test.clj`, `type_id_order_test.clj`,
`bench_test.clj`, `net/`).

## Conformance tests — what a new adapter is held to

| Test | Holds | Run |
|---|---|---|
| `adapters/slab-io/test/eve/slab_io_test.cljc` | an `eve.slab-io` implementation (HOST-CONTRACT §12) | `bb test:vs` adds the root; any host adds `adapters/slab-io/test` to its classpath |
| `test/eve/thread_host_test.cljc` | an `eve.thread-host` adapter (HOST-CONTRACT §11): the contract's answers, mailboxes, exit notices, frames, refusals, a grandchild thread — every row reads only the contract and the declared `driving` properties | every runner but ValueScript's (`thread-host` suite on Node) |
| `test/eve/eve_bytes_fixture_test.cljc` + `test/eve/fixtures/eve-bytes.edn` | the codec: every value's EVE bytes and hashes, byte for byte, the same on every host | every runner (`fixtures` suite on Node) |
| `test/eve/hamt_util_test.cljc` | `eve.int32` through Murmur3 and the trie index | every runner |
| `test/eve/net/crypto_test.cljc`, `ed25519_test.cljc` | an `eve.crypto` adapter against the portable code (`eve.net.portable-crypto`) and RFC 8032's vectors, the small-order refusals | the `net` suite; `clojure -M:jvm-test -n eve.net.crypto-test`; on another host, name them after its task (`bb test:bb eve.net.crypto-test eve.net.ed25519-test`) |

## Multi-process and multi-host checks

| Command | What |
|---|---|
| `bb scripts/thread-rendezvous.clj <host-a> <host-b>` | two processes, one disk domain, one registry; b is killed with `kill -9` and a adopts its work (hosts: `jvm bb nbb clje cljr lpy jolt glj`) |
| `bb scripts/thread-rotation.clj <host> <host> ...` | one member per host named, one domain pool, the coordinator role passed along the turn order |
| `bb scripts/thread-fold-rendezvous.clj <host-a> <host-b>` | fold on a group of processes serving one domain pool |
| `node target/eve-test/all.js mmap-atom-e2e` | ClojureScript: two Node processes on one disk atom |
| `clojure -M:jvm-e2e-test` | the JVM across processes |

The coop capstone — eve data structures mixed in one shared disk atom and updated under contention by several hosts at
once — lives in `test/coop/`: its transitions in `mix.cljc` and `scenario.cljc` (`coop.mix`, `coop.scenario`), its
orchestrator `orchestrator.clj` (`coop.orchestrator`, a JVM test namespace run by name, not by `:jvm-test`), and one
worker per host in `workers/` — bb, nbb, ClojureCLR, basilisp, jolt and clojerl over this tree, and a ValueScript
program (`coop_worker.vs`, built by ValueScript's compiler and run by its `hosts/eve-mmap` host; `VS_DIR` names
ValueScript's tree — an explicit cross-repo opt-in: with `VS_DIR` unset, `run.sh` reports SKIP and runs nothing,
because the shipped tree assumes no sibling checkout). Every participant joins one mmap atom at once.

```
test/coop/run.sh                                      # every scenario but the soak (needs nbb, cljr, basilisp, jolt, clojerl)
test/coop/run.sh coop.orchestrator/<deftest>          # one scenario
test/coop/tally.sh N [deftest …]                      # passes/attempts over N runs, each in its own JVM
```

Its environment: `COOP_ROOT` (the atoms' directory, default `/tmp/coop-atom`), `COOP_SCALE` (contention ops per
writer, default 40), `COOP_LOG_DIR`, `COOP_WASM_HOST`, `COOP_WASM_BATCH` (run.sh's header).

## The host-leak gate

`scripts/check-host-leak` counts what the shared code (`src/`) still names of a host — reader conditionals, host
predicates, host-only files, method calls in `src/eve/net` — and holds the counts to `.host-leak` (it may only go
down). Run it before a commit.

## Output

Tee test output to a file, so a run never has to be repeated to read it:

```bash
bb test:bb 2>&1 | tee /tmp/eve-bb-test.txt
clojure -M:jvm-test 2>&1 | tee /tmp/eve-jvm-test.txt
```
