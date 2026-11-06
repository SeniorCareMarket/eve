> **Historical record** — the unification's leak register (Unit 1, 2026-10-15, BASE `b24c8c034`: 371 reader-conditional
> sites, the `L-nn` ids HOST-CONTRACT §8 still cites). It describes eve as it stood then, not eve 0.9 (`src/` has 0 today).
> Current authority: `scripts/check-host-leak` and `.host-leak` (the gate), `doc/unification/HOST-CONTRACT.md` (the contract).
>
> **Since BASE — bb atomics (L-23, L-55, §5 item 14; 2026-11-06):** closed. `bb-lock` is gone; bb's region is
> `adapters/region/jvm-nio-lock` (`BbMmapRegion`, a lock-only region: `-atomic-rmw?` false, `-with-file-lock` a FileChannel
> byte-range lock), and `eve.atom` runs every bb write under the exclusive hold (ROOT_HOLD under that file lock, lock-free
> hosts draining through ROOT_INFLIGHT). Measured on release/eve-0.9.3: `test/coop/bb_contention.clj` (bb-only and JVM+bb
> writers on one atom, no lost update) and `test/coop/bb_lock_price.clj`.

# EVE Seam Map — Unit 1 of the unification

Written 2026-10-15 15:40 ET against BASE (eve `c7dc390`). Every claim below was
re-derived from the code at BASE by reading the source, not from the September 2026
inventory or from the candidate's own descriptions; where they disagree, this document
says so (§3). Line numbers are BASE line numbers in `eve/src/eve/...`.

This document is the work's contract: Units 4–5 are judged against the leak register in
§2, and Unit 6 must account for every `L-nn` below as closed or open-with-reason.

---

## 0. Summary

| Measure | At BASE |
|---|---|
| `.cljc` files under `src/eve/` | 23 |
| Reader-conditional sites (`#?`) in the 20 non-trivial `.cljc` files | **371** (array 58, atom 45, map 43, vec 41, set 40, list 31, tensor 21, dataset/functional 14, dataset/argops 13, mem 11, dataset 9, obj 8, hamt_util 8, data 8, alloc 8, platform 4, serialize 4, slab_runtime 4, perf 1, coalesc 0) |
| First-arm histogram of those sites | `:cljs` 240 · `:bb` 78 · `:clj` 62 · `:default` 1 |
| Intended seams | 3 protocols: `IMemRegion` (mem.cljc:57), `ISlabIO` (alloc.cljc:79), `IEveRoot` (data.cljc:371) |
| Leaks registered below | **58** (`L-01`…`L-58`) |
| Of which closed cleanly by the candidate | 1 (`L-45` Unsafe → VarHandle) |
| Partially closed by the candidate (name-level merge, store-level fork remains) | 3 (`L-24`, `L-05`/alpha, `L-34` cas!) |
| Introduced or made worse by the candidate | 3 (`L-27` per-call heap islands, `L-58` bb load regression, `L-18` ThreadLocal logs kept as the only mechanism) |
| Defects the inventory missed entirely | 27 (§4) |

The headline finding: the three seams exist and the HAMT/trie *algorithms* genuinely run
through `ISlabIO` on both hosts (map.cljc, set.cljc, vec.cljc, list.cljc are one
implementation). Everything *around* the algorithms is still two implementations: how a
slab context is obtained (module globals on CLJS, a value on the JVM), how a value becomes
bytes (two codecs), how a type is registered (three registries), how an atom is opened,
read, swapped and retired (six domain openers, two deref paths, two swap paths), and what
a protocol method returns (bytes on one host, an offset on the other).

---

## 1. The seams as found at BASE

### 1.1 `IMemRegion` (mem.cljc:57–133) — a byte region with atomics

Seven implementations exist. The protocol's contract is what the *callers* rely on, not
what each implementation does:

| Implementation | Host | Backing | i32 atomics | i64 atomics | wait/notify | `-read-bytes` returns |
|---|---|---|---|---|---|---|
| `JsSabRegion` (:173) | CLJS | SharedArrayBuffer + Atomics | real | **non-atomic emulation**; `add/sub` throw (:200–222) | real (`Atomics.wait`) | copy (`.slice`) |
| `NodeMmapRegion` (:307) | CLJS/Node | `MAP_SHARED` via native addon | real (`__atomic_*`) | real | real (futex) | **view** into the mapping (:363) |
| `LustreMmapRegion` (:401) | CLJS/Node | mmap + fcntl locks | lock-based | lock-based | poll; notify returns 0 | view (:460) |
| `BbMmapRegion` (:491) | bb | `MappedByteBuffer` | **process-local lock** (`bb-lock` :488) | same | poll; notify 0 | copy |
| `BbHeapRegion` (:605) | bb | `ByteBuffer.wrap` | process-local lock | same | poll; notify 0 | copy |
| `JvmByteBufferRegion` (:879) | JVM | `ByteBuffer.wrap` **or** `MappedByteBuffer` | VarHandle (`invokeWithArguments`) | VarHandle | `LockSupport.park/unpark` registry (:964–1000) | copy (:1003) |
| `LustreJvmMmapRegion` (:1077) | JVM | mmap + `FileChannel.lock` | lock-based | lock-based | poll; notify 0 | copy (:1239) |

What the callers actually need (every call site audited): `-load-i32`, `-store-i32!`,
`-cas-i32!` (witness return), `-add-i32!`/`-sub-i32!` (old-value return),
`-exchange-i32!`, `-load-i64`/`-store-i64!`/`-cas-i64!` (coalesc header only:
coalesc.cljc:32/`grow-coalesc-region!`), `-wait-i32!`/`-notify-i32!` (eve.array wait!/
notify! only — CLJS-only API), `-read-bytes`, `-write-bytes!`, `-byte-length`,
`-supports-watch?` (no production caller; tests only).

### 1.2 `ISlabIO` (alloc.cljc:79–115) — slab-qualified block I/O

Two implementations:

* `JvmSlabCtx` (alloc.cljc:185) — a **value**: arrays of regions, bitmap regions, layout
  caches, file paths, growth caps. Also used by bb. Every method resolves
  `(class, block) → (region, byte-offset)` and delegates to `IMemRegion`.
* `CljsSlabIO` (alloc.cljc:1475) — a **stateless token**. Every method ignores `ctx` and
  reads module-level state: `slab-data-offsets`/`slab-bitmap-offsets`/
  `slab-total-blocks`/`slab-file-paths` (:895–903), the WASM slab instances
  (`wasm/get-slab-instance`), `mmap-coalesc-region` (:1703) and the scratch
  `resolved-dv`/`resolved-u8`/`resolved-base` (:1322–1324).

Consequence: "explicit `sio`" is only real on the JVM. On CLJS `(alloc/->CljsSlabIO)` is
the one and only slab domain of the process; opening an mmap domain *replaces* it
(atom.cljc:280, :311 "NOTE: Replaces the module-level slab instances").

### 1.3 `IEveRoot` (data.cljc:371) — and the protocol zoo around it

`IEveRoot` is one method (`-root-header-off`) and is genuinely portable. Around it sit
seven more protocols whose *implementations* are host-divergent even where the name is
shared: `IDirectSerialize` (:377), `ISabStorable` (:380, see `L-50`), `ISabRetirable`
(:385), `ISabpType` (:388), `IsEve` (:391), `IBackingArray` (:396, JVM-only consumer),
`IPlain` (:401, candidate's materialization hook), `IBulkAccess` (:425, `:clj` only).

---

## 2. Leak register

Columns: **What** (file:line) · **Host assumption it encodes** · **Absorbing seam** ·
**BASE status**. `open` = present at BASE; `cand-closed` = closed by the candidate and the
closure is clean; `cand-partial` = candidate touched it, fork remains; `cand-new` =
introduced by the candidate; `shared-defect` = same bug on all hosts (not a fork, still a
portability item).

### A. Forked entry points (eve.atom)

| # | What | Host assumption | Absorbing seam | BASE |
|---|---|---|---|---|
| L-01 | Six domain openers: `cljs-open-mmap-domain!` (atom.cljc:275), `cljs-join-mmap-domain!` (:308), `cljs-open-sab-domain!` (:334), `jvm-open-mmap-domain!` (:630), `jvm-join-mmap-domain!` (:707), `jvm-open-heap-domain!` (:1435). Two domain-state shapes: CLJS `{:root-r :rmap-r :base-path :slot-idx :timer-id :retire-q(cljs atom) :flush-ts(array)}`; JVM adds `:sio :heartbeat-sched :tree-logs :thread-epochs :pin-lock`, `:retire-q` is a `ConcurrentLinkedQueue` (`LinkedList` on bb), `:flush-ts` a volatile. | CLJS: one slab domain per isolate held in module globals (§1.2). JVM: domain is a value. | One `open-domain!` over `ISlabIO` + a *region provider* (`heap` vs `file`), one domain-state shape, one retire-queue representation. | open |
| L-02 | Two derefs: `cljs-mmap-deref` (:364) vs `jvm-mmap-deref` (:850) + `jvm-read-root-value` (:803). Same algorithm (refresh → load epoch → pin → load ptr → header byte → constructor → `eve->clj` → unpin) with four forks: header byte via module global `alloc/read-header-type-byte` vs `alloc/jvm-read-header-type-byte sio`; constructor called `(ctor nil ptr)` vs `(ctor sio ptr)`; CLJS pins the slot directly (`mmap-pin-epoch!` :194) while JVM aggregates per-thread epochs (`jvm-pin-thread-epoch!` :770); JVM hard-codes `0x1D`/`0x1E` fallbacks (:837–841) and bb materializes set/vec/list inline (:815–831) instead of using the registry. | Thread identity (JVM), single thread (CLJS), no lazy types (bb). | One `mmap-deref` over `ISlabIO` + the single header-constructor registry (`L-24`); epoch pinning becomes a domain-state operation whose per-thread aggregation is a host *capability* (multi-threaded hosts aggregate; single-threaded hosts don't). | open |
| L-03 | Two swaps: `cljs-mmap-swap!` (:545) vs `jvm-mmap-swap!` (:982). Divergent on every axis: **(a) failure cleanup** — CLJS frees only the new root via `-sab-retire-diff! … :free` / `alloc/free!` (:604–611), so blocks allocated for nested values during `f` leak on CAS failure; JVM frees every block in the ThreadLocal alloc log (:1047–1051). **(b) success retirement** — CLJS tree-diffs old vs new and *also* diffs nested collections inside map values (`collect-old-value-offsets` :484, `collect-nested-collection-offsets` :417, `collect-map-nested-sab-diffs` :468); JVM uses the replaced-log for Map→Map (:1017) and `jvm-collect-old-value-offsets` (:893) otherwise, and **never diffs nested collections** (orphaned nested maps/vecs/sets/lists inside replaced map values are leaked on the JVM). **(c)** CLJS binds `d/*parent-atom*` around `f` (:581); JVM binds nothing. **(d)** JVM wraps steps in `perf/timed` (perf.cljc is `:clj`/`:bb` only). **(e)** JVM keeps `tree-logs` (:1033–1041) to retire non-Eve new trees later; CLJS has no equivalent. | Thread-local = per-swap (JVM); single-threaded + global slab (CLJS). | One `mmap-swap!` whose per-swap allocation log and replaced-node log live in an explicit per-swap record passed through `ISlabIO` (an `alloc-log` field on the slab context handle), with one retirement collector (the CLJS nested-aware one) on all hosts. | open |
| L-04 | Two root resolvers: `cljs-resolve-new-ptr` (:523) via the CLJS builder registry (`ser/convert-to-sab`) vs `jvm-resolve-new-ptr` (:911) via `mem/jvm-write-collection!`. JVM-only extras: any map with both `:schema` and `:values` keys becomes an **Obj block** (:937) — `(reset! a {:schema 1 :values 2})` stores a typed object on the JVM and a map on CLJS; JVM-only foreign-domain guard (:928). | — | One value→root converter over the single builder/writer registry (`L-24`/`L-26`); the Obj coercion is dropped (an Obj is constructed explicitly, never inferred from map keys). | open; the Obj rule is a semantic divergence |
| L-05 | Two retire flushers: `cljs-try-flush-retires!` (:391) vs `jvm-try-flush-retires!` (:867); queue type, clock, and a dead `eve-map/untrack-debug-offset!` call (CLJS) differ. | — | One flusher over a portable retire queue + host clock. | open |
| L-06 | Two-and-a-half `cas-backoff!`: `Atomics.wait` on a scratch SAB (:265–273), `LockSupport/parkNanos` (:628), `Thread/sleep` on bb (:626). | sleep primitive | Host `park-nanos`/`sleep-ms` primitive — legitimate residue, one call site. | open (residue) |
| L-07 | Heartbeat timer: `js/setInterval` (:300, :326) vs `ScheduledExecutorService` (:690, :753) vs nothing on bb. **L-07b**: CLJS registers `process.on "exit"` to release the worker slot (:301, :327); JVM and bb register no shutdown hook — a JVM process that exits without `close!` leaves its slot ACTIVE until the 30 s heartbeat timeout, and its pinned epoch (if any) blocks reclamation for that long. | timers, process lifecycle | Host `schedule-periodic!`/`cancel!` and `on-exit!` primitives (residue), one call site each. | open |
| L-08 | `update-registry!` (:1297) / `read-registry` (:1308) dispatch to the forked swap/deref. | — | Vanishes with L-02/L-03. | open |
| L-09 | `MmapAtom` ×3 (:1073 cljs, :1095 bb, :1111 clj) and `MmapAtomDomain` ×3 (:1129, :1151, :1156). bb's `compareAndSet` (:1104) is deref-compare-swap — **not atomic**. JVM's `MmapAtom` implements `clojure.lang.IAtom` without `compareAndSet` → `(compare-and-set! a …)` throws `AbstractMethodError`. CLJS `compare-and-set!` requires a `cljs.core/Atom` → unsupported. | — | One deftype body (interface names are the only residue) and a real CAS: `compare-and-set!` = one iteration of the swap loop with `f` = `(fn [cur] (if (= cur old) new ::abort))`. | open |
| L-10 | `domain-files-exist?` (:1185): `js/require "fs"` vs `java.io.File`. | fs | Host `file-exists?` primitive (residue). | open |
| L-11 | `persistent-atom-domain` binds `mem/*lustre-mode*` (:1204) → see L-14. | — | — | open |
| L-12 | `atom-sio` JVM-only (:1511); CLJS compat shims `get-env`/`alloc`/`free`/`eve->cljs`/`sab-transfer-data`/`atom-serialize` (:1600–1700) CLJS-only. | — | `atom-sio` on every host (returns the domain's `ISlabIO`); shims are CLJS host API, named as such. | open (parity) |
| L-13 | In-process domain caches duplicated: `heap-domain-cache`/`resolve-heap-domain` (:1474–1486) vs `sab-domain-cache`/`resolve-sab-domain` (:1541–1553); `atom` itself is defined twice (:1489, :1555). | — | One `in-process-domain` resolver over the region provider. | open |

### B. Ambient / dynamic state

| # | What | Host assumption | Absorbing seam | BASE |
|---|---|---|---|---|
| L-14 | `mem/*lustre-mode*` (mem.cljc:143) is read inside `open-mmap-region` on CLJS (:385) and JVM (:1027). It is *bound* in alloc.cljc:360, :373, :519 — the JVM remap/grow/refresh paths **infer** lustre-ness from the existing region's class and re-bind the var because "it may not be bound on remap threads" — and in atom.cljc:1204. This is the ambient-parameter pattern the `ISlabIO` note (alloc.cljc:143) forbids, one layer down. | — | A region provider function carried by the slab context (`:open-region (fn [path size-bytes])`); lustre is a provider choice at domain-open time, never a dynamic var. | open |
| L-15 | `d/*parent-atom*` (data.cljc:437): bound by `cljs-mmap-swap!` only (:581); read by the serializer's typed-array path to decide "in transaction → mutable view, else copy" (serialize.cljc:1055, :1067, :1297), by `array.cljc:1141` (`has-slab?`) and by the CLJS `eve-slab-deftype` constructor guard (deftype_proto.clj:558). Never bound on the JVM. | single-threaded CLJS | The deref boundary (Unit 3's `$`/`eve->clj` decision) replaces "is a dynamic var bound" as the definition of "inside a transaction"; allocation context is the explicit `sio`. | open |
| L-16 | `atom/*global-atom-instance*` (atom.cljc:1583; set at :1595 by the CLJS compat `atom-domain`; read at array.cljc:1141). `shared_atom.cljs:18` has its own copy. | — | Same as L-15. | open |
| L-17 | `*global-persistent-atom-domain*` (atom.cljc:1379): the default-domain override, portable, bound only by tests (mmap_domain_test.cljs:56, :108). | — | Keep as API-level configuration; it is not on the slab/atom data path (the report names it). | open (acceptable) |
| L-18 | JVM ThreadLocal allocation log and replaced-node log (alloc.cljc:149, :165) started/drained by `jvm-mmap-swap!` and fed by `alloc/log-replaced-node!` from the shared map path-copy code (map.cljc:275, :295, :343, :388, :608, :620, :631, :687, :705, :738). CLJS defines `log-replaced-node!` as a no-op (alloc.cljc:878). set/vec/list path-copies never log (set.cljc `copy-node-patch-child!` does not call it). | "one swap per thread at a time" — true on the JVM, false for any host with green threads, async swaps, or re-entrant `f`. | An explicit per-swap log record reachable from the `sio` passed to the HAMT code (the candidate replaced one ambient context with another, thread-keyed one). | open; the candidate kept it as the only JVM mechanism |
| L-19 | CLJS module-level slab state (alloc.cljc:895–903, :1060–1062, :1322–1324, :1703–1706, overflow hooks, `initialized?`); `CljsSlabIO` ignores its `ctx` (:1475–1497). | one slab domain per JS isolate | `CljsSlabIO` holding the per-domain layout arrays and instances as fields, so a CLJS process can hold an SAB domain and an mmap domain at once and `sio` means the same thing on every host. The N-API/DataView hot paths are untouched by *where the layout arrays live*. | open |
| L-20 | `eve-map/mmap-mode?` (map.cljc:1240), set by the CLJS openers (atom.cljc:284, :314); every consumer is a no-op stub (`untrack-debug-offset!` :1243). | — | Delete. | open (dead) |
| L-21 | CLJS serializer scratch aliasing: `serialize-key` returns scratch A, `serialize-val` scratch B, `encode-sab-pointer` returns `sab-ptr-buf` (serialize.cljc:372–391) — "valid until the next call". The HAMT write path relies on this ordering silently (`write-kv!` consumes before the next serialize). | single-threaded, non-reentrant codec | The codec contract must say so ("returned bytes may alias a reusable scratch buffer; consume before the next encode") or return fresh bytes. A port that copies is correct; a port that caches the returned view is not. | open (contract gap) |
| L-22 | Keyword caches: CLJS `keyword-cache`/`kw-deser-cache` (identity/offset keyed), JVM `kw-encode-cache` (mem.cljc:1443). | — | One cache policy in the unified codec, keyed by value. | open (duplication) |
| L-23 | bb `bb-lock` (mem.cljc:488): a process-wide `Object` used to *simulate* atomics. | — | See L-55. | open at BASE; closed since (banner) |

### C. Duplicated registries and parallel paths

| # | What | Host assumption | Absorbing seam | BASE |
|---|---|---|---|---|
| L-24 | Three constructor registries: `:bb` atoms (serialize.cljc:97–115), `:clj` `ConcurrentHashMap`s (:117–156), `:cljs` `js/Map`s (:192–197). Two constructor conventions: `(fn [_sab header-off])` on CLJS (macros.clj:441; array.cljc:1220, :1237) vs `(fn [sio header-off])` on the JVM (macros.clj:458; array.cljc:1477). CLJS-only registries with no JVM twin: disposers (:228), builders (:244), direct-map-encoder (:270), typed-array encoder/resolver (:280, :291), records (:302), `sab-tag->fast-tag*` (:406). JVM-only: `header-tag-reader` (:163) and `jvm-coll-writers` (mem.cljc:1922). The `:bb` arm lacks `register-header-tag-reader!` while alloc.cljc:607 calls it from a `#?(:clj …)` block that bb also loads → bb cannot load `eve.deftype-proto.alloc` at BASE (`L-58`). | bb/JVM/JS collection identity | **One** registry implementation in portable Clojure (an atom holding `{int → fn}` maps), one constructor signature `(fn [sio header-off])`, and one registration record per type (`L-30`). | cand-partial (names merged, stores and conventions not) |
| L-25 | Two codecs: CLJS `serialize-key/val/element`, `deserialize-element`, `deserialize-from-dv` (serialize.cljc:634–1376) vs JVM/bb `value->eve-bytes`, `value+sio->eve-bytes`, `eve-bytes->value` (mem.cljc:1534–2004). Byte-level divergences enumerated in §D (`L-36`–`L-44`). | TextEncoder/DataView vs `String.getBytes`/`ByteBuffer` | One codec in `.cljc` over a small byte-buffer seam (`u8/i32/i64/f64` LE put/get, UTF-8 encode/decode). | open |
| L-26 | Collection writers (`-write-map-coll!` map.cljc:1625, set:989, vec:741, list:381, registered via `register-jvm-collection-writer!`) vs CLJS builders (`into-hash-map`/`into-hash-set`/`eve-vec`/`eve-list` registered via `register-cljs-to-sab-builder!`). Same algorithm (assoc/conj loop), two registries, two return types (header offset vs instance). | — | One registry (`L-24`); builders return instances, the root resolver takes `-root-header-off`. | open |
| L-27 | `default-sio` ×7 (map.cljc:80, set:62, vec:56, list:54, array:543, tensor.cljc, dataset.cljc): CLJS returns the global token; JVM/bb return **a fresh 6×1 MB heap slab domain per call**. `(eve.alpha/hash-map :a 1)` on the JVM is an island. Islands cannot be combined: `swap!` rejects a foreign-domain root loudly (atom.cljc:928), but `value+sio->eve-bytes` (mem.cljc:1958–1967) accepts any `IEveRoot` value and writes its header offset as a pointer into the *target* slab **without checking the value's domain** → nesting an island map inside another domain's map writes a dangling cross-domain pointer silently. | — | Either a process default slab context on every host (what CLJS has) or no default at all (explicit `sio` everywhere, constructors throw without one). Not both-and-neither. | **cand-new** |
| L-28 | bb parallel types: `BbEveHashMap` (map.cljc:855–953) duplicates `EveHashMap`'s behavior by hand; set/vec/list have no bb type (`#?(:bb nil …)` set.cljc:660, vec.cljc:330, list.cljc:124) so bb materializes them (atom.cljc:815–831) and re-serializes whole collections on write (no structural sharing); bb-specific materializing constructors for `0x10–0x13` (atom.cljc:63–105). | SCI cannot host the Java interfaces `eve/deftype` emits (`java.util.Map`, `IEditableCollection`, `MapEquivalence`, …) | Either the macro's CLJ arm emits an SCI-compatible interface subset for bb, or bb is declared a *materializing host* in the host contract (a legitimate capability level) and the materializers move behind the registry instead of living in eve.atom. | open |
| L-29 | Transients JVM-only (map.cljc:1208, set.cljc:965). | — | Parity item (minor): a CLJS `IEditableCollection` arm with the same wrapper. | open |
| L-30 | `register-eve-type!` (macros.clj:411) emits different registration sets per host: CLJS ctor+disposer+builder; JVM ctor+collection-writer+print. The JVM never registers disposers (the `dispose!` fns exist but nothing routes to them by type-id). | — | One portable registration record `{:type-id :fast-tag :from-header :dispose :builder :print}`. | open |
| L-31 | JVM-only compat aliases: `jvm-write-map!`/`jvm-eve-hash-map-from-offset` (map.cljc:1663–1680), set (:1038–1050), vec (:815–828, `SabVecRoot`), list (:416–428, `SabList`), `mem/jvm-write-collection!`; consumed by `jvm_conformance_test.clj` (coll-factory style). | — | Name residue; retire with the tests retargeted to the portable names. | open |
| L-32 | Obj: CLJS `Obj` (obj.cljc:122) holds a raw SAB + byte offset, reads with `js/DataView`/`Atomics` directly and allocates via `alloc/alloc-offset` (:313) — bypasses `ISlabIO`; JVM `JvmObj` (:573) is `ISlabIO`-backed but has **no** constructor, `assoc!`, `cas!`, `add!`… (read-only). Both compute the field layout by `sort-by` over a *map's* seq (obj.cljc:72, alloc.cljc:758) and decode the schema into `array-map`/`{}` + `assoc` (obj.cljc:515, :655; alloc.cljc:791): for schemas with >8 fields the iteration order — hence the byte layout — depends on the host's hash-map ordering and on whether the decoded map got promoted; writer and reader can disagree on where a field lives. | JS vs JVM map iteration order | Layout computed from the schema's *encoded field order* (a vector), stable sort by alignment; one Obj implementation over `ISlabIO` on both hosts. | open; layout bug is a portability defect |
| L-33 | Array: the shared `EveArray` (array.cljc:236, `eve/deftype`) is portable, but the JVM arm **redefines** `eve-array` (:1429) and `aset!` (:1454) to the heap-only `JvmHeapEveArray` (:1308); `eve.alpha` re-exports those (alpha.clj:40–42). On the JVM `(e/eve-array :int32 [1 2 3])` is not slab-backed, cannot be stored in an atom without copying through `value+sio->eve-bytes`'s `IBackingArray` branch, and `aget` (:612) on it reads `.-sio__`, a field it does not have. CLJS atomics (`cas!`/`add!`/`sub!`/`exchange!`/`band!`/`bor!`/`bxor!`/`wait!`/`notify!`, :650–890) and SIMD ops are CLJS-only. | — | One slab-backed `eve-array` on every host (`[sio type n]` + a default); `JvmHeapEveArray` stays a JVM convenience under its own name; atomics route through `ISlabIO`/`IMemRegion` on both hosts (the CLJS implementations already do — `resolve-slab-mem-region` then `mem/-cas-i32!`). | open |
| L-34 | `eve-deftype`/`eve-slab-deftype` codegen (deftype.clj:268, deftype_proto.clj:465) is two generators: CLJS emits `alloc/resolve-dv!` + `resolved-dv` DataView reads with no `sio` and `js/Atomics.compareExchange` on an `Int32Array` (deftype_proto.clj:227, deftype.clj:148–160); JVM emits `ISlabIO` reads/writes and `-sio-cas-i32!` (deftype.clj:417). Instances are `[offset__]` vs `[slab-off__ sio__]`; constructors are `(->T args)` vs `(->T sio args)` — the asymmetry leaks into user code (tensor.cljc:211/237, dataset.cljc:171/202 fork on it). `cas!`: JVM throws for non-`:int32` fields (deftype.clj:223); CLJS silently CASes the containing i32 word for any width. Serialized fields fork again in slab_runtime.cljc (:20 DataView, :80 ISlabIO). | DataView-per-method vs protocol-per-field | The already-portable `eve/deftype` codegen (macros.clj: `[sio__ offset__]` on both hosts, ISlabIO on both hosts) as the base of `eve-deftype`; `cas!` restricted to 4-byte aligned fields on both hosts and tested on both. | cand-partial (JVM cas! now works for `:int32`; nothing tests it: no `cas!` in any JVM test file) |
| L-35 | `eve.perf` (`timed` macro) exists only for `:clj`/`:bb` (perf.cljc:21); the JVM swap is instrumented with it, the CLJS swap cannot be. | — | A portable `timed` (no-op arm for CLJS/bb). | open |

### D. Wire-format divergences (the Unit-5 fixture list)

| # | What | Hosts | Absorbing seam | BASE |
|---|---|---|---|---|
| L-36 | **Flat set tag 0xEE**: the JVM/bb encoder writes `[EE DB EE count …]` for sets (mem.cljc:1434, :1693–1703, :1894) and decodes it (:1670). The CLJS decoder has no `0xEE` arm (serialize.cljc:921–1140: only `FLAT_MAP 0xED` and `FLAT_VEC 0xEF`) → falls through to the constructor lookup → `nil`. The CLJS *flat* encoder turns sets into `FLAT_VEC` (:913) → decodes as a vector on both hosts. `0xEE` is also `EveHashSet-type-id` (set.cljc:44) and the magic byte `0xEE` — three meanings. The docs (internals.md "Flat Collection Tags") list only `0xED`/`0xEF`; `data.cljc:483` still carries the pre-flat `DIRECT_MARKER_SET 0xEC` (unused). | JVM writes; CLJS can't read | One codec; the fixture decides which side is the spec (Unit 5, argued from the format's history: the documented format has no flat-set tag; the JVM invented one). | open |
| L-37 | **Composite keys**: the JVM serializes map keys with `value->eve-bytes` (map.cljc:65), i.e. flat `0xED/0xEE/0xEF` encodings for map/set/vector keys; CLJS serializes keys with `ser/serialize-key` (:61), which pointer-encodes any collection key through the builders (`0x10–0x13`) — allocating a *new* slab tree per lookup, so the key bytes (and hash) of `[1 2]` differ on every call: a CLJS map with a vector key can never find it again, and a JVM-written map with a vector key hashes differently on CLJS. Neither conformance suite tests a collection key (conformance_test.cljs:275, jvm_conformance_test.clj:283 test int/string/keyword/nil only). | — | One codec with *value* encoding for keys (flat, content-addressed) on every host. | open; CLJS-side correctness bug |
| L-38 | **int64**: CLJS encodes `INT64` only for safe integers (> 2^31, < 2^53; serialize.cljc:563) and decodes via `js/Number` (:943, :1169) — values ≥ 2^53 are lossy; `JsSabRegion/-load-i64` returns a double. JVM is exact. | JS number model | Host contract names i64 as "exact ≥ 2^53 required for region headers; a host whose number type is lossy above 2^53 must say so" — CLJS is the deficient host, not the format. | open (contract) |
| L-39 | **UUID**: both write msb/lsb as 16 big-endian bytes; CLJS does it by parsing the hex string (serialize.cljc:661, :766). Byte-identical; method differs. | — | Fixture only. | ok |
| L-40 | **Symbols > 255 bytes**: both encoders write `(unchecked-byte len)` / `(aset buf 3 len)` for the short-only symbol tags `0x0C/0x0D` → the stored length is `len mod 256` → corrupt bytes on both hosts. | — | shared-defect: the unified codec must throw (no long-symbol tag exists in the format and adding one is a format change outside this work). | shared-defect |
| L-41 | **Obj layout order** (see L-32). | — | — | open |
| L-42 | **`{:schema :values}` map → Obj** on the JVM only (L-04). | — | — | open |
| L-43 | **FNV-1a name hash** (atom.cljc:1254) is computed over `charCodeAt`/`charAt` code units masked to 8 bits: a UTF-16 artefact that a UTF-8-string host would compute differently for non-ASCII names. Write-only at BASE (`claim-atom-slot!` stores it; nothing reads it). | JS/JVM strings are UTF-16 | Host contract: define it over the UTF-8 bytes of the name (identical for ASCII, which is every existing fixture). | open (contract) |
| L-44 | **Typed arrays / records**: `0x1B` (typed-array blob) and `0x1A` (record) are CLJS-only tags; `0x1D`/`0x1E`/`0x1C` exist on both. A JVM reader of a CLJS-written `0x1A`/`0x1B` value gets "Unknown EVE type tag". | — | Host contract lists them as CLJS host extensions; fixtures pin the shared subset. | open (contract) |

### E. Seam return/argument conventions that leak a host

| # | What | Absorbing seam | BASE |
|---|---|---|---|
| L-45 | `sun.misc.Unsafe`: gone. `JvmByteBufferRegion` (mem.cljc:879) uses `MethodHandles/byteBufferViewVarHandle` + `toMethodHandle` + `invokeWithArguments` (:709–760); wait/notify are real (:964–1000). Residual cost: every atomic boxes through `invokeWithArguments`. | — | **cand-closed** (clean) |
| L-46 | `IMemRegion/-read-bytes` copy-vs-view is unspecified: JVM/`JsSabRegion`/bb copy, `NodeMmapRegion`/`LustreMmapRegion` return a view of the mapping (mem.cljc:363, :460). | Contract must specify "the returned bytes may alias the region" (and callers must not hold them past the pinned epoch) or require a copy. | open |
| L-47 | `ISlabIO/-sio-read-bytes`: CLJS `read-bytes` returns a `.subarray` **view into slab memory** (alloc.cljc:1412–1418); JVM copies. A key/value view held past the swap's pin is use-after-free under epoch GC. | Same contract decision as L-46, one level up. | open |
| L-48 | `JsSabRegion` i64: `-load-i64`/`-store-i64!`/`-cas-i64!` are non-atomic two-i32 emulations, `-add-i64!`/`-sub-i64!` throw (mem.cljc:200–222). `grow-coalesc-region!` (coalesc.cljc:~200) uses `-cas-i64!` on whatever backs class 6 — including the SAB coalesc region (`init-sab-coalesc!`), so SAB overflow growth is single-threaded by accident. | Contract: i64 atomics are required for file regions and for any region that backs a coalesc header; a SAB host must implement them (two-word CAS is impossible on SAB → a lock word, or forbid class-6 growth on SAB). | open (contract hole) |
| L-49 | `-notify-i32!` returns 0 (no-op) on bb and both Lustre regions; `-wait-i32!` polls there. | Contract: wait is required (bounded by timeout), notify is a *hint* — stated. | open (contract) |
| L-50 | `ISabStorable/-sab-encode` returns the 7-byte pointer **bytes** on CLJS and the header **offset** on the JVM (map.cljc:1071, set.cljc:764, vec.cljc:456, list.cljc:224, array.cljc:388). Same protocol method, two return types. | One return type (the offset; the pointer bytes are a codec concern). | open |
| L-51 | Header/type constructor signature `(fn [_sab off])` vs `(fn [sio off])` (L-24). | `(fn [sio header-off])` everywhere. | open |
| L-52 | `deserialize-element` takes a legacy `s-atom-env` map (`{:sab …}`, `{:data-view …}`) that constructors receive as their first argument (serialize.cljc:1019, :1035, :1139); `deserialize-from-dv` needs `(:data-view s-atom-env)` for strings. | The `sio` is the only context a decoder needs. | open |
| L-53 | `-sio-cas-i32!` (alloc.cljc:102, candidate-added): witness-return matches `Atomics.compareExchange` and `VarHandle.compareAndExchange` — no leak. CLJS resolves the region through the module-global `resolve-slab-mem-region` (L-19). | — | cand-closed (modulo L-19) |
| L-54 | Error types: `js/Error` vs `IllegalArgumentException`/`ex-info` chosen ad hoc (array.cljc, obj.cljc, tensor, dataset); `platform.cljc` exists for exactly this and is used by vec/list only. | `eve.platform` throw helpers everywhere (residue, one place). | open |

### F. Babashka-specific residue

| # | What | Absorbing seam | BASE |
|---|---|---|---|
| L-55 | bb "atomics" are a process-local `locking` (mem.cljc:491–700). Against a concurrent JVM or Node process, a bb `-cas-i32!` is read-compare-write with no cross-process atomicity: the root CAS and the bitmap-alloc CAS can both lose updates. The README's "2 JVM + 2 Node + 2 bb … CORRECT" is a low-rate measurement, not a guarantee. bb has `java.nio.channels.FileChannel`/`FileLock` (probed 2026-10-15: available) but not `java.lang.invoke.MethodHandles` (missing) — so the correct bb region is the fcntl byte-range-lock pattern already used by the Lustre regions, not VarHandles. | Host contract: atomic RMW across processes is a *required* primitive of a file-mapped region; the bb region must implement it with `FileLock` or declare bb a single-process host. | open (correctness) at BASE; closed since (banner) |
| L-56 | `value+sio->eve-bytes` bb arm hard-codes tag `0x1D` for any `IEveRoot` value that is not a map/set/vector/list (mem.cljc:1967) — a bb Dataset/Tensor nested in a map is written as an EveArray pointer. | `read-header-tag` through the one registry on every host (L-24). | open |
| L-57 | bb per-thread epoch pinning keys `thread-epochs` by the constant `:main` (atom.cljc:770–800); `bench/bb_stress_atom.clj` Phase 4 runs four bb threads → the slot's pinned epoch is whichever thread wrote last, so a reader can be unpinned while still reading. | Thread identity is a host primitive (`current-thread-id`); the aggregation logic is portable and should not be forked. | open (correctness) |
| L-58 | **bb cannot load `eve.atom` at BASE**: `eve.deftype-proto.alloc` fails at line 607 (`ser/register-header-tag-reader!` unresolved — defined only in serialize.cljc's `:clj` arm, :163). Verified 2026-10-15 with `bb -cp src -e "(require 'eve.atom)"`. The coop-atom patch `0001-bb-loads-eve-atom.patch` targets a *different* failure (`bb-hash-map?`, `into-hash-set` bb arm, a bb guard on `jvm-collect-old-value-offsets`) and its map.cljc hunk no longer applies (it references `alloc/*jvm-slab-ctx*`). | — | **cand-new** regression |

### G. Legitimate platform residue (keep, but each must be one named call site)

Not leaks; the host contract (Unit 2) names them as primitives. At BASE each is spread over
several call sites and sometimes inlined into shared logic:

* **Clock**: `js/Date.now` vs `System/currentTimeMillis` (atom.cljc:127–150 heartbeat, :391/:867 flush throttle; `System/nanoTime` in bench code).
* **Sleep/park** (L-06), **periodic timer** and **process-exit hook** (L-07), **file-exists** (L-10).
* **Byte arrays**: `Uint8Array` vs `byte[]` — `bytes-equal?`/`bytes-length` ×4 (map.cljc:89/98, set:71/78, vec:65, list:63), `hamt_util.cljc` `ubyte`/`imul32`/`rotl32`/`ushr32` (:18–30) and the `(bit-or h 0)` 32-bit coercions (:51, :74), `-read-bytes`/`-write-bytes!` types.
* **UTF-8**: `TextEncoder`/`TextDecoder` vs `String.getBytes "UTF-8"` (serialize.cljc:355–362; mem.cljc `read-utf8`).
* **Number model**: CLJS bit ops are 32-bit and numbers are doubles; `unchecked-int`/`long` coercions on the JVM.
* **Exceptions** (L-54). **`MapEntry`** construction (map.cljc:813, :1036). **Clojure-equality hashes** (`platform.cljc:38/46`: `hash-ordered-coll` vs `Murmur3/hashOrdered`) — for `hasheq`, never on disk.
* **Interface names** in deftypes (handled by `proto_map.clj`), CLJS `IPrintWithWriter` vs JVM `print-method`.
* **The region implementations themselves** (§1.1) and the CLJS WASM bitmap scanner (`deftype_proto/wasm.cljs`) vs the portable `imr-bitmap-*` scan (mem.cljc:1313–1399) used by JVM/bb.

---

## 3. Reconciliation with the September 2026 inventory

| Inventory item | Finding at BASE | Candidate closure |
|---|---|---|
| JVM-specific deref/swap entry points duplicating the CLJS paths, with forked pin-epoch, try-flush-retires, refresh-slab-regions, collect-replaced-nodes, resolve-new-ptr | **Confirmed** (L-01…L-05). Corrections: `jvm-collect-replaced-nodes` (atom.cljc:948) is **dead code** — nothing calls it; the live JVM mechanism is the ThreadLocal replaced-log (L-18). The pin-epoch fork is `mmap-pin-epoch!` (:194) vs `jvm-pin-thread-epoch!` (:770); refresh is `alloc/refresh-mmap-slabs!` (:1684) vs `alloc/refresh-jvm-slab-regions!` (:479). | not attempted (workstream 4 never landed) |
| Ambient `*jvm-slab-ctx*` dynamic var reached for inside serialization/collection code | **Closed**: no `*jvm-slab-ctx*` in `src/`, `test/`, `test-e2e/`, `bench/` (only the stale coop-atom patch mentions it). **Not clean**: replaced by per-call heap islands (L-27), ThreadLocal logs remain the per-swap context (L-18), `*lustre-mode*` and `*parent-atom*`/`*global-atom-instance*` remain (L-14–L-16). | cand-partial |
| Two serializer constructor registries | **Confirmed, partially merged**: the portable names exist in all three arms, but there are three stores, two constructor conventions, seven CLJS-only side registries, and the bb arm is missing a function the shared code calls (L-24, L-58). | cand-partial, with a regression |
| JVM regions on `sun.misc.Unsafe`, heap wait/notify spin/no-op | **Closed, clean** (L-45): `JvmByteBufferRegion` for heap and mmap, VarHandle atomics, real `LockSupport` wait/notify. | cand-closed |
| Public API split (`eve.alpha` CLJS exposes hash-map/hash-set/eve-array/aget/aset!, JVM a subset) | **Names now present on both** (alpha.clj:33–42); semantics still split: JVM `eve-array`/`aset!` are heap-only (L-33); CLJS alpha lacks `close-atom-domain!` and the `deftype`/`extend-type` macros that alpha.clj exports; neither exposes `vec`/`list` constructors or `atom-sio`. | cand-partial |
| JVM deftype `cas!` threw "not yet supported" | **Closed for `:int32` fields** via `-sio-cas-i32!` (deftype.clj:417, deftype_proto.clj:417); a throwing stub remains for other widths (deftype.clj:223) — which is the honest contract, since CLJS silently CASes the enclosing word. **Unverified**: no JVM test exercises `cas!`. | cand-partial (closed, untested) |
| JVM encodes a flat set with `0xEE`; CLJS decoder has no case | **Confirmed** (L-36) and reachable in practice through composite keys (L-37). | not attempted |
| No byte-fixture conformance suite across hosts | **Confirmed**: `conformance_test.cljs` and `jvm_conformance_test.clj` are behavioral, single-host suites (no byte literal appears in either); coherence evidence is the e2e round trips. | not attempted |

Candidate claims checked against the code:

* "phases 1–3 complete (unified `JvmByteBufferRegion`, shared `init-domain-root!`, the
  dynamic var deleted, registries merged)" — region: true; `init-domain-root!`
  (atom.cljc:224): true and genuinely shared; dynamic var: true; registries: names only.
* "portable deref materialization (`eve->clj`/`IPlain`)" — present (data.cljc:401–420);
  both derefs call it; the CLJS Eve types do **not** implement `IPlain` (only `JvmObj`,
  `EveArray`, `NDBuffer`, `Dataset` do), so on CLJS `eve->clj` falls to the `map?`/`set?`/
  `vector?` branches, which work because the deftypes satisfy the core protocols.
* "JVM `cas!` via `-sio-cas-i32!`" — present; untested.
* "`eve.alpha` parity" — names, not semantics (above).
* "`$` operator designed but never implemented" — confirmed: no `$` anywhere in `src/`.

---

## 4. What the inventory missed

Listed in the order a port would hit them:

1. `CljsSlabIO` ignores its context; the slab domain on CLJS is the module (L-19).
2. `default-sio` makes every default-constructed JVM value an island, and cross-island
   nesting writes dangling pointers silently (L-27).
3. Composite map keys are encoded differently per host, and CLJS cannot look its own
   composite keys up (L-37).
4. Obj field layout depends on map iteration order (L-32/L-41).
5. The JVM `eve-array`/`aset!` shadow the portable API with a heap-only type (L-33).
6. The JVM swap never diffs nested collections on retirement (leak); the CLJS swap frees
   only the root on CAS failure (leak) (L-03a/b).
7. ThreadLocal alloc/replaced logs are an ambient, thread-keyed context (L-18).
8. `*lustre-mode*` is a dynamic var inferred-and-rebound inside the allocator (L-14).
9. `*parent-atom*` / `*global-atom-instance*` decide copy-vs-view semantics and
   allocation eligibility on CLJS only (L-15/L-16).
10. Serializer scratch aliasing is an unstated contract (L-21).
11. `-read-bytes`/`-sio-read-bytes` return views on some hosts and copies on others
    (L-46/L-47); views into slab memory outlive the epoch pin.
12. `-sab-encode` returns different types per host (L-50); constructors take different
    first arguments (L-51); decoders carry a legacy env map (L-52).
13. i64 atomics on SAB are not atomic and the SAB coalesc grow path uses them (L-48).
14. bb atomics are not atomic across processes (L-55); bb thread epochs collapse to one
    key (L-57); bb mis-tags nested deftype values (L-56); bb cannot load at BASE (L-58).
15. The JVM has no exit hook for slot release (L-07b); `compare-and-set!` is unsupported
    on CLJS/JVM and non-atomic on bb (L-09).
16. FNV-1a over UTF-16 code units (L-43); long symbols corrupt on both hosts (L-40);
    CLJS-only tags `0x1A`/`0x1B` (L-44).
17. `mmap-mode?` is dead ambient state (L-20); `perf` cannot instrument CLJS (L-35).
18. The coop-atom patches `0003` (CLJS list retire walk reads a node's `next` at +8;
    `list.cljc:36` says `NODE_NEXT_OFFSET 0` — atom.cljc:456–465 walks value bytes as
    pointers) and `0004` (the JVM reads the root pointer *before* re-checking slab
    growth, atom.cljc:1004–1006 vs :994) are real bugs in BASE's shared code, not
    test-harness conveniences.

---

## 5. Reader-conditional census and what survives

Every one of the 371 sites falls into one of four kinds. The unification's done-bar for
Unit 4 is: kinds (a)–(c) go to zero in the slab/atom data path; kind (d) remains and each
site is listed in the Unit 6 report with its primitive.

| Kind | Count (approx., by file inspection) | Example | Fate |
|---|---|---|---|
| (a) **Algorithm fork** — same algorithm written twice | ~95 | atom.cljc deref/swap/open; serialize vs mem codec; obj.cljc; array.cljc ctor/aset!; slab_runtime.cljc | delete one copy |
| (b) **Store/registry fork** — same registry, three stores | ~20 | serialize.cljc:97–197; mem.cljc jvm-coll-writers; macros.clj:434–466 | one store |
| (c) **bb exclusion** — `#?(:bb nil …)` because bb cannot host the type/macro | 78 first-arm `:bb` sites | set.cljc:660, vec.cljc:330, list.cljc:124, map.cljc:855 | collapse to a declared host capability (materializing host) with the materializers behind the registry |
| (d) **Host primitive** — clock, sleep, timer, fs, bytes, UTF-8, numbers, exceptions, interface names, print | ~180 | hamt_util.cljc (8), platform.cljc (4), the `#?@(:clj [java.util.List …])` interface blocks in the deftypes, `MapEntry`, `js/Date.now` | keep; one call site per primitive where the shared code consumes it |

The 20 audited files are listed in `doc/unification/` as the scope of the audit; the three
`.cljs`/`.clj` pairs (`alpha`, `deftype`) and the CLJS-only namespaces (`shared_atom.cljs`
— protected, legacy SAB atom still required by `batch4_validation_test.cljs`; `data.cljs`,
`util.cljs`, `wasm_mem.cljs`, `deftype_proto/{wasm,simd,xray}.cljs`, `deftype/{int_map,
rb_tree,runtime}.cljs`) are the CLJS host layer and CLJS-only collections; they are out of
the seam map except where a `.cljc` file reaches into them (array.cljc/obj.cljc →
`proto-wasm/get-slab-instance`, atom.cljc → `wasm-mem/init-views-from-sab!`), which are
instances of L-19.
