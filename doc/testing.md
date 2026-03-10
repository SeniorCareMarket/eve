# Eve Testing Guide

Comprehensive guide to Eve's test suites, how to run them, and what they cover.

---

## Quick Start

```bash
# From the eve/ directory:

# Build native addon (only if mmap_cas.cc changes)
npm run build:addon

# Compile test bundle
npx shadow-cljs compile eve-test

# Run all tests
node target/eve-test/all.js all

# Run a specific suite
node target/eve-test/all.js slab

# Run JVM tests
clojure -M:jvm-test
```

---

## ClojureScript Test Suites

All CLJS tests are compiled into a single bundle via shadow-cljs and run with Node.js. Suite selection is via CLI argument.

### Usage

```bash
node target/eve-test/all.js <suite>
```

### Available Suites

| Suite | Tests | Assertions | What it covers |
|---|---|---|---|
| `epoch-gc` | 16 | 44 | Epoch-based garbage collection, retire queue, stale worker detection |
| `obj` | 27 | 2,075 | Typed shared objects (eve/obj), schemas, AoS/SoA, atomic ops, column operations |
| `rb-tree` | 25 | 80 | Red-black tree sorted set, insertion, deletion, membership, ordering |
| `int-map` | 22 | 368 | PATRICIA trie integer map, merge, range queries, negative keys |
| `batch2` | 6 | 59 | Batch validation — maps, vectors, sets with complex nested values |
| `batch3` | 7 | 33 | Batch validation — lists, additional nesting scenarios |
| `batch4` | 22 | 82 | Batch validation — edge cases, large collections, mixed types |
| `typed-array` | 9 | 70 | Eve typed arrays, atomic operations, wait/notify, SIMD |
| `mem` | 9 | 35 | IMemRegion protocol, JsSabRegion, NodeMmapRegion implementations |
| `mmap` | 15 | 27 | Raw mmap operations, native addon load/store/CAS/wait/notify |
| `mmap-slab` | 10 | 26 | mmap-backed slab allocator, bitmap operations, growth |
| `mmap-atom` | 6 | 7 | Persistent mmap atom, swap!/reset!/deref, CAS semantics |
| `mmap-atom-e2e` | 4 | 10 | Cross-process end-to-end tests (spawns separate Node.js process) |
| `slab` | 40 | 464 | Slab allocator, HAMT map/vec/list/set operations in SAB |
| `all` | all above | all | Runs all suites (excluding mmap suites which need native addon) |

### Meta Suites

| Suite | Includes |
|---|---|
| `core` | `eve.deftype-test` |
| `array` | `eve.array-test` |
| `slab` | `eve.map-test`, `eve.vec-test`, `eve.list-test`, `eve.set-test` |
| `deftype` | `eve.deftype-test`, `eve.deftype.int-map-test`, `eve.deftype.rb-tree-test` |
| `validation` | `batch2`, `batch3`, `batch4` |
| `mmap-domain` | `eve.mmap-domain-test` (multi-atom domain tests) |

### Listing Suites

```bash
node target/eve-test/all.js --list
```

---

## JVM Test Suites

JVM tests use `cognitect.test-runner` and cover the JVM-side implementations.

### Running

```bash
clojure -M:jvm-test
```

Requires JVM flags (already configured in `deps.edn`):
```
--add-opens java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

### Test Files

| Test File | What it covers |
|---|---|
| `jvm_atom_test.clj` | JVM heap-backed and mmap-backed atoms |
| `jvm_atom_e2e_test.clj` | Cross-process mmap atom end-to-end |
| `jvm_map_test.clj` | JVM EveHashMap (HAMT) |
| `jvm_vec_test.clj` | JVM persistent vector |
| `jvm_set_test.clj` | JVM persistent set |
| `jvm_list_test.clj` | JVM persistent list |
| `jvm_obj_test.clj` | JVM typed objects |
| `jvm_array_test.clj` | JVM typed arrays |
| `jvm_slab_test.clj` | JVM slab allocator |
| `readme_example_test.clj` | README code examples (smoke test) |
| `bench_test.clj` | Performance benchmarks |

---

## Test Infrastructure

### shadow-cljs Build Targets

| Target | Output | Purpose |
|---|---|---|
| `:eve-test` | `target/eve-test/all.js` | Main test runner |
| `:mmap-worker` | `target/eve-test/mmap-worker.js` | Worker for cross-process e2e tests |
| `:bench-worker` | `target/eve-test/bench-worker.js` | Worker for benchmarks |
| `:xray-stress-test` | `target/eve-test/xray-stress-test.js` | X-RAY diagnostics stress test |
| `:eve-integration` | `target/eve-test/integration.js` | Integration test entry |
| `:eve-smoke` | `target/eve-test/smoke.js` | Smoke test entry |
| `:eve-perf` | `target/eve-test/perf.js` | Performance test entry |
| `:eve-bench` | `target/eve-bench/bench.js` | Benchmark framework |

### Isolated Namespace Support

Some test namespaces (`eve.map-test`, `eve.vec-test`, `eve.list-test`, `eve.set-test`, `eve.large-scale-test`) run in isolation — the slab allocator pools are reset before each namespace to prevent state leaking between suites.

### Native Addon Loading

Mmap test suites (`mmap`, `mmap-slab`, `mmap-atom`, `mmap-atom-e2e`, `mmap-domain`) automatically load the native addon from `build/Release/mmap_cas.node`. The addon must be built first (`npm run build:addon`).

---

## The Green Baseline

These commands must all pass before any code change is considered complete:

```bash
cd eve

# Compile
npx shadow-cljs compile eve-test

# Core suites
node target/eve-test/all.js epoch-gc
# → "Ran 16 tests containing 44 assertions. 0 failures, 0 errors."

node target/eve-test/all.js obj
# → "Ran 27 tests containing 2075 assertions. 0 failures, 0 errors."

node target/eve-test/all.js rb-tree
# → "Ran 25 tests containing 80 assertions. 0 failures, 0 errors."

node target/eve-test/all.js int-map
# → "Ran 22 tests containing 368 assertions. 0 failures, 0 errors."

node target/eve-test/all.js batch2
# → "Ran 6 tests containing 59 assertions. 0 failures, 0 errors."

node target/eve-test/all.js batch3
# → "Ran 7 tests containing 33 assertions. 0 failures, 0 errors."

node target/eve-test/all.js batch4
# → "Ran 22 tests containing 82 assertions. 0 failures, 0 errors."

node target/eve-test/all.js typed-array
# → "Ran 9 tests containing 70 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mem
# → "Ran 9 tests containing 35 assertions. 0 failures, 0 errors."

# Mmap suites (require native addon)
node target/eve-test/all.js mmap
# → "Ran 15 tests containing 27 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mmap-slab
# → "Ran 10 tests containing 26 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mmap-atom
# → "Ran 6 tests containing 7 assertions. 0 failures, 0 errors."

node target/eve-test/all.js mmap-atom-e2e
# → "Ran 4 tests containing 10 assertions. 0 failures, 0 errors."

node target/eve-test/all.js slab
# → "Ran 40 tests containing 464 assertions. 0 failures, 0 errors."

# JVM tests
clojure -M:jvm-test
```

---

## Writing New Tests

### ClojureScript

1. Create a test namespace in `test/eve/` (e.g., `test/eve/my_feature_test.cljs`).
2. Use `cljs.test`:
   ```clojure
   (ns eve.my-feature-test
     (:require [cljs.test :refer [deftest testing is]]
               [eve.alpha :as e]))

   (deftest basic-test
     (testing "my feature"
       (let [a (e/atom {:x 0})]
         (swap! a update :x inc)
         (is (= {:x 1} @a)))))
   ```
3. Add the namespace to `test/eve/runner/eve_test_main.cljs`:
   - Add the require
   - Add the `goog/exportSymbol` call
   - Add a runner function
   - Register in `suite-runners`
4. Recompile: `npx shadow-cljs compile eve-test`
5. Run: `node target/eve-test/all.js my-suite`

### JVM (Clojure)

1. Create a test file in `test/eve/` (e.g., `test/eve/my_feature_test.clj`).
2. Use `clojure.test`:
   ```clojure
   (ns eve.my-feature-test
     (:require [clojure.test :refer [deftest testing is]]
               [eve.alpha :as e]))

   (deftest basic-test
     (testing "my feature"
       (let [a (e/atom {:x 0})]
         (swap! a update :x inc)
         (is (= {:x 1} @a)))))
   ```
3. The cognitect test runner auto-discovers test namespaces in the `test/` directory.
4. Run: `clojure -M:jvm-test`

---

## Debugging Test Failures

### X-RAY Diagnostics

Eve includes an X-RAY diagnostic tool for inspecting storage model state:

```clojure
(require '[eve.shared-atom :as a])

;; Validate storage model at a checkpoint
(a/validate-storage-model! env {:width 80 :label "checkpoint"})

;; Replay X-RAY log
(a/xray-replay!)
```

### Common Issues

- **Native addon not built:** Run `npm run build:addon` before running mmap tests.
- **Stale compilation:** Run `npx shadow-cljs compile eve-test` after source changes.
- **Slab pool contamination:** The test runner resets pools for isolated namespaces, but if running suites in unusual order, pools may carry state. Use the `all` suite to run everything cleanly.
- **Leftover mmap files:** Tests create temporary files in `/tmp/`. If a test crashes, leftover `.slab*`, `.root`, and `.rmap` files may interfere. Clean them up manually.
