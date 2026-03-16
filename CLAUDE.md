# CLAUDE.md — Eve

> Read this before touching any code.

---

## Project Identity

Eve is a shared-memory persistent data structure library for ClojureScript.
It provides persistent maps, vectors, sets, and lists backed by SharedArrayBuffer
(in-process) or mmap files (cross-process).

The native-eve subsystem provides cross-process mmap-backed persistent atoms:
JVM Clojure and Node.js ClojureScript processes share and atomically mutate a
Clojure data structure via memory-mapped files on disk.

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

node target/eve-test/all.js all
# → all suites combined
```

If ANY of these fail, stop and fix the regression before any other work.

---

## Forbidden Patterns

### 1. The `.main` file does not exist

There is no `.main` file, block descriptor table, status-mirror array,
capacity-mirror array, WASM scratch region, or `BD_ARRAY_START = 6168` constant.

The cross-process atom files are: `.slab0`–`.slab5`, `.root`, `.rmap`.

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
# Suites: all, core, slab, epoch-gc, obj, int-map, rb-tree, batch2, batch3,
#         batch4, typed-array, array, mem, mmap, mmap-slab, mmap-atom,
#         mmap-atom-e2e, mmap-domain, large-scale, deftype, validation

# Build native addon
npm run build:addon

# Run JVM tests
clojure -M:jvm-test
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
| `eve.alpha` | `src/eve/alpha.cljs` + `.clj` | Public API entry point |
| `eve.atom` | `src/eve/atom.cljc` | Cross-process mmap atom |
| `eve.mem` | `src/eve/mem.cljc` | IMemRegion protocol |
| `eve.hamt-util` | `src/eve/hamt_util.cljc` | Shared HAMT bitwise helpers + portable hash |
| `eve.deftype-proto.alloc` | `src/eve/deftype_proto/alloc.cljc` | Slab allocator |
| `eve.deftype-proto.data` | `src/eve/deftype_proto/data.cljc` | Slab constants |
| `eve.deftype-proto.serialize` | `src/eve/deftype_proto/serialize.cljc` | Serializer |
| `eve.deftype-proto.coalesc` | `src/eve/deftype_proto/coalesc.cljc` | Coalescing overflow allocator |
| `eve.map` | `src/eve/map.cljc` | Eve HAMT map |
| `eve.vec` | `src/eve/vec.cljc` | Eve persistent vector |
| `eve.set` | `src/eve/set.cljc` | Eve persistent set |
| `eve.list` | `src/eve/list.cljc` | Eve persistent list |
| `eve.array` | `src/eve/array.cljc` | Typed array API |
| `eve.obj` | `src/eve/obj.cljc` | Typed shared objects (AoS/SoA) |
| `eve.shared-atom` | `src/eve/shared_atom.cljs` | SAB-backed atom (CLJS) |
| `eve.deftype` | `src/eve/deftype.cljs` + `.clj` | deftype macro |
| `eve.deftype.int-map` | `src/eve/deftype/int_map.cljs` | Integer map (CLJS) |
| `eve.deftype.rb-tree` | `src/eve/deftype/rb_tree.cljs` | Sorted set (CLJS) |
| `eve.perf` | `src/eve/perf.clj` | JVM profiling tools |

---

## Protected Files

**Must not be deleted. Must not be modified without reading first.**

```
src/eve/shared_atom.cljs
```
