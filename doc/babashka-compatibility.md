# Eve + Babashka Compatibility Research

## Summary

Eve's core infrastructure — mmap regions, slab allocator, serialization, HAMT
hashing — works in Babashka with minimal changes. The collection deftypes
(EveHashMap, EveHashSet, SabVecRoot, SabList) need 3 Java interfaces removed
to fully load in bb. This document details the findings and the path forward.

## What Works Today (bb v1.12.216)

After adding `:bb` reader conditionals to `eve.mem` and `eve.perf`:

| Namespace                     | Status | Notes                              |
|-------------------------------|--------|------------------------------------|
| `eve.deftype-proto.data`      | OK     | Pure constants, no platform code   |
| `eve.deftype-proto.serialize` | OK     | Pure constants, no platform code   |
| `eve.hamt-util`               | OK     | `Integer/rotateLeft` etc. all work |
| `eve.perf`                    | OK     | No-op stubs via `:bb` branch       |
| `eve.mem`                     | OK     | `BbMmapRegion` via MappedByteBuffer|
| `eve.deftype-proto.coalesc`   | OK     | Uses only IMemRegion protocol      |
| `eve.deftype-proto.alloc`     | OK     | Protocol + slab logic              |
| `eve.map`                     | BLOCKED| EveHashMap deftype (3 interfaces)  |
| `eve.vec`                     | BLOCKED| SabVecRoot deftype (same issue)    |
| `eve.set`                     | BLOCKED| EveHashSet deftype (same issue)    |
| `eve.list`                    | BLOCKED| SabList deftype (same issue)       |
| `eve.atom`                    | BLOCKED| Depends on collection namespaces   |

### Capabilities Demonstrated

- **mmap regions**: `open-mmap-region` creates file-backed shared memory using
  `MappedByteBuffer` with simulated atomics via `locking`. Full i32/i64
  read/write/CAS/add/sub work correctly.

- **Heap regions**: `make-heap-region` creates in-process byte-array-backed
  regions using `ByteBuffer.wrap`.

- **EVE serialization**: `value->eve-bytes` and `eve-bytes->value` round-trip
  all primitive types (nil, boolean, int32, int64, float64, string, keyword,
  symbol, UUID, Date) and flat collections (maps, vectors, sets).

- **Portable hash**: `portable-hash-bytes` produces identical Murmur3 hashes
  across bb, JVM Clojure, and ClojureScript.

- **Slab addressing**: `encode-slab-offset` / `decode-slab-offset` work
  correctly for all class/block combinations.

## What Blocks Full Compatibility

### 1. Three unsupported Java interfaces in `deftype`

Babashka's `deftype` supports most `clojure.lang.*` interfaces but explicitly
rejects these three:

- `clojure.lang.IReduceInit`
- `clojure.lang.IReduce`
- `clojure.lang.IEditableCollection`

Eve's JVM collection deftypes (EveHashMap, EveHashSet, SabVecRoot, SabList)
all implement these. Removing them is straightforward — `reduce` falls back to
`seq`-based reduction, and transients become unavailable.

All other interfaces used by Eve's deftypes work in bb:
`IMeta`, `IObj`, `MapEquivalence`, `ILookup`, `IPersistentMap`, `Counted`,
`Seqable`, `IPersistentCollection`, `IKVReduce`, `IFn`, `Iterable`,
`java.util.Map`, `IHashEq`, `Object`.

### 2. `eve.perf` was JVM-only

Used `ThreadLocal`, `proxy`, `CopyOnWriteArrayList` — none available in bb.
**Fixed**: Converted to `.cljc` with `:bb` no-op stubs.

### 3. `eve.mem` used Panama FFM + sun.misc.Unsafe

`java.lang.foreign.Arena`, `java.lang.foreign.MemorySegment`, and
`sun.misc.Unsafe` are not available in GraalVM native-image (bb).
**Fixed**: Added `:bb` branch using `MappedByteBuffer` with simulated atomics.

### 4. `(:import ...)` for deftype-generated classes

`atom.cljc` imports `[eve.map EveHashMap]` etc. — bb's deftype doesn't
generate real Java classes. **Fixed**: `#?(:bb nil :clj (:import ...))`.

## Path to Full bb Support

### Phase 1: Core infrastructure (DONE)
- [x] `BbMmapRegion` using `MappedByteBuffer`
- [x] `BbHeapRegion` using `ByteBuffer.wrap`
- [x] `eve.perf` no-op stubs
- [x] `bb.edn` + example + test scripts
- [x] 8 tests, 54 assertions passing

### Phase 2: Collection deftypes
- [ ] Add `:bb` branch to `map.cljc` JVM section — same deftype minus
      `IReduceInit`, `IReduce`, `IEditableCollection`
- [ ] Same for `set.cljc`, `vec.cljc`, `list.cljc`
- [ ] Skip `TransientEveHashMap` etc. in bb (no transient support)
- [ ] Add `:bb` branch for `atom.cljc` imports

### Phase 3: Full atom support
- [ ] `eve.atom` loads and provides `atom`/`swap!`/`deref`/`reset!`
- [ ] Cross-process interop test: JVM writes, bb reads

## Architecture Notes

### Why MappedByteBuffer (not Unsafe)?

Babashka is compiled with GraalVM native-image, which:
- Does NOT include `sun.misc.Unsafe` (internal API)
- Does NOT include `java.lang.foreign.*` (Panama FFM, Java 21+)
- DOES include `java.nio.MappedByteBuffer` and `FileChannel`

`MappedByteBuffer.getInt`/`.putInt` are not truly atomic, but for
single-process bb scripts this is sufficient. Cross-process atomicity requires
the JVM or Node.js implementations.

### Available Java NIO classes in bb

All of these are confirmed available:
- `java.nio.MappedByteBuffer`
- `java.nio.ByteBuffer`, `java.nio.ByteOrder`
- `java.nio.channels.FileChannel`, `FileChannel$MapMode`
- `java.nio.file.StandardOpenOption`, `Paths`
- `java.io.RandomAccessFile`
- `java.util.concurrent.ConcurrentHashMap`
- All `Integer` and `Long` static methods

### bb deftype interface support

bb's deftype supports "map interfaces" — a curated set that covers the core
Clojure collection protocols. The full supported list includes:
`IMeta`, `IObj`, `MapEquivalence`, `ILookup`, `IPersistentMap`, `Counted`,
`Seqable`, `IPersistentCollection`, `IKVReduce`, `IFn`, `Iterable`,
`java.util.Map`, `IHashEq`, `Associative`, `IPersistentStack`, `Indexed`,
`IPersistentVector`, `IPersistentSet`, `Object`.

NOT supported: `IReduceInit`, `IReduce`, `IEditableCollection`,
`ITransientMap`, `ITransientCollection`, `ITransientAssociative`.

## Running

```bash
# Install babashka (if needed)
curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
chmod +x install && ./install

# Run bb tests (8 tests, 54 assertions)
bb test:bb

# Run bb example
bb example:bb
```

## Sources

- [Babashka book](https://book.babashka.org/)
- [Babashka GitHub](https://github.com/babashka/babashka)
- [SCI (Small Clojure Interpreter)](https://github.com/babashka/sci)
- [Making markdown-clj babashka-compatible](https://blog.michielborkent.nl/markdown-clj-babashka-compatible.html)
- [Malli bb compatibility PR](https://github.com/metosin/malli/pull/718)
- [bb available Java classes](https://github.com/babashka/babashka/blob/master/src/babashka/impl/classes.clj)
