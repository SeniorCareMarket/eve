> **Historical record** — the unification's Unit 6 report (2026-10-15, 9:00 PM ET): that day's scoreboard and the plan for
> host #4. It describes eve as it stood then, not eve 0.9. Current authority: `doc/unification/HOST-CONTRACT.md` §9 (the port
> checklist), `hosts.edn` (the hosts), `doc/adopter-tiers.md`.

# EVE Unification — Report (Unit 6)

2026-10-15, 21:00 ET, over eve `c7dc390`. Every number below was measured on this
box in this work; the re-measure commands are in §9.

Commits, in order: `d73e230` seam map · `e0d8dc8` host contract · `a0c6aa2`
verdicts + plan · `9480bf5` phase A (one registry) · `b5c2ffc` phase B (one
atom) · `326b010` phase C (bb inherits the JVM implementation) · `c057264`
Unit 5 (one byte format, fixtures) · `805b6f5` dead thread-local logs and the
lustre flag deleted · `a9c0e13` JVM `cas!` test, CLJS alpha parity ·
`d219165` long symbols refused (L-40) · and this report's commit (three unread
dynamic vars and `mmap-mode?` deleted).

## 0. Scoreboard

| Measure | BASE `b24c8c034` | Now |
|---|---|---|
| JVM unit `clojure -M:jvm-test` | 211 tests, 3090 assertions, **0 failures** | 215 tests, 0 failures (3 fixture tests + the `cas!` test added; assertions 3175–3264 — randomized tests vary run to run) |
| JVM e2e `clojure -M:jvm-e2e-test` | 34 tests, 6364 assertions, **3 failures** (`capstone-big-data-concurrent-disk-stress`) | 34 tests, 6364 assertions, **0 failures** |
| CLJS `node target/eve-test/all.js all` | 356 tests, 31210 assertions, 0 failures | 359 tests, 31327 assertions, 0 failures |
| CLJS `mmap`, `mmap-slab`, `mmap-atom`, `mmap-atom-e2e`, `mmap-domain`, `fuzz` | green | green |
| CLJS `conformance` | 4 failures (`map-get-test` :114, `map-contains?-test` :121 :127, `map-find-test` :167) | the same 4 — pre-existing, untouched |
| CLJS compiler warnings, `eve-test` build alone | 62 | 55 (atom.cljc's 5 and list.cljc's 2 gone; none added) |
| bb | **cannot load `eve.atom`** (alloc.cljc:607) | loads; derefs, swaps, `compare-and-set!`, nested map/set/vec/list; a CAS participant |
| Byte-fixture suite (54 rows) | — | JVM 54 rows / 123 assertions / 0 failures · bb 54 / 123 / 0 · Node 51 rows + 3 skipped / 117 / 0 |
| coop-atom `contention-eve-only` (2 JVM + 2 Node + 2 bb) | round 2: **0 / 5** (N1 / N2, one JVM SIGSEGV) | **9 / 9** (5 of them under a concurrent bench) |
| coop-atom `contention-jvm-only` / `-node-only` / `-bb-only` | controls | 5 / 5 each |
| coop-atom round trip (node jvm bb node jvm bb node, every handoff asserted) | K1 / K2 / K3 | every handoff exact; divergence classes **K1 0, K2 0, K3 0**, unnamed 0 (§6) |
| `bench/eve/contention_bench.clj` (2 JVM + 2 Node + 2 bb) | not measured here (round 2's control was 0/5) | Counter 300 / 300 CORRECT, 5 runs; p50 JVM 1.16–1.20 ms, Node 0.59–0.61 ms, bb 0.66–0.75 ms |
| `bench/eve/build_atom.clj` 10 MB + `stress_atom.clj` | — | 601 keys / 12.5 MB; JVM update p50 3.44 ms, Node new-key p50 0.28 ms, bb p50 1.15 ms; Phase 4 (4 JVM threads + 4 Node) Counter 1000 / 1000 CORRECT |
| Reader conditionals in `eve/src` | 371 sites (81 `:bb`) | 351 sites (51 `:bb`), each accounted for in §4 |
| Dynamic vars on the slab/atom path | `*lustre-mode*`, `*parent-atom*`, `*persistent?*`, `*worker-id*`, `*worker-slot-idx*`, `*read-epoch*`, `*global-persistent-atom-domain*`, `*global-atom-instance*` + two JVM ThreadLocal logs | `*parent-atom*`, `*worker-id*`, `*global-persistent-atom-domain*`, `*global-atom-instance*` (§5) |

On-disk compatibility held throughout: `.slabN` / `.root` / `.rmap` / `.bm`
layouts and the CAS protocol are byte-compatible with BASE; the two root
header words the hold protocol uses (§1, phase B) were zero before. The
coop-atom harness ran against this tree unpatched.

## 1. What was built

**Phase A — one type registry (`9480bf5`).** `eve.deftype-proto.serialize`
holds the registry on every host: type and header constructors (one
convention, `(fn [sio header-off])`), disposers, builders, collection writers,
the header-tag reader, the default sio, the pointer encoding. The bb / clj /
cljs triplet of stores is gone; `register-eve-type!` is one emission.

**Phase B — one atom (`b5c2ffc`).** `eve.atom` is one implementation (1277
lines, from 1837): one domain opener per region kind over one domain-state
shape, one `mmap-deref`, one `mmap-swap!` (`swap-attempt!`), one flusher, one
root resolver, one retirement collector (structural diff + nested counterpart
diff + an orphan reach guard), a real `compare-and-set!` (one swap iteration),
`$`, depth-counted per-thread epoch pins keyed by the host's thread id, the
heartbeat's high word fixed (N3), the root re-read behind the refresh (0004 /
0007), retire walks under the pin (0008), and a per-swap `LoggingSlabIO` so a
lost CAS frees every block the attempt allocated. The host primitives are ten
named functions at the top of the file (§4). The bb region was rewritten:
plain loads, fenced stores, and — because bb has no `MethodHandles`, hence no
VarHandle, hence no cross-process atomic RMW (measured) — the **exclusive-hold
protocol** over two previously-zero root header words (`ROOT_HOLD_OFFSET` 24,
`ROOT_INFLIGHT_OFFSET` 28, `FORMAT-DECISIONS.md` §1): lock-free hosts count
themselves in flight and back off while a hold is set; a lock-only host takes
a `FileLock`, sets the hold, waits for in-flight to drain, does its swap with
plain RMW, clears the hold. This is what turns bb from a corrupter (round 2,
N2) into a participant.

**Phase C — bb inherits the JVM implementation (`326b010`).** The operator's
direction: bb is built on Clojure, so bb loads the `:clj` arm. The deftype
macro filters, under bb, exactly the protocols sci's `deftype` rejects
(measured: `IReduceInit`, `IEditableCollection`, transients, `java.util.List`
/ `Set` / `Collection`, `Object.equals` / `hashCode`), so `EveHashMap` is
bb's map type too (sci "map mode"). sci rejects `IPersistentVector`,
`IPersistentSet` and `ISeq` outright, so sets, vectors and lists materialize
on bb through readers registered at their own registration sites — bb is a
*materializing* host for those three (HOST-CONTRACT §7 level B) and a full
host for maps. `BbEveHashMap` and every bb duplicate constructor are deleted.

**Unit 5 — one byte format (`c057264`, `d219165`).** `FORMAT-DECISIONS.md`
§2–§4: flat sets are tag 0xEE on every host (CLJS wrote 0xEF and could not
read 0xEE — round 2, K1); flat sets' elements and flat maps' entries are
written in canonical order, ascending by encoded bytes, through one
comparator `eve.deftype-proto.data/compare-bytes` (K3); a collection used as
a key — map key or set element — is flat on every host (CLJS wrote pointers,
and the JVM's set elements went through the value encoder, so `#{[1 2]}`
could not find its own element on either); symbols past 255 bytes are
refused instead of truncated (L-40). `test/eve/fixtures/eve-bytes.edn` pins
54 values' bytes and Murmur3 hashes, generated from the JVM codec and
reviewed by hand against the tag table; `eve.eve-bytes-fixture-test` runs
them on the JVM, bb and Node.

**Residue (`805b6f5`, `a9c0e13`, this commit).** The JVM ThreadLocal
allocation and replaced-node logs (dead since phase B) and `*lustre-mode*`
deleted; the first JVM test of deftype `cas!` (L-34) — a `^:volatile-mutable
^:int32` field under four contending threads for 200 rounds, one winner each;
`close-atom-domain!` exported from the CLJS alpha; `*persistent?*`,
`*worker-slot-idx*`, `*read-epoch*` (no reader anywhere) and `mmap-mode?`
(no consumer) deleted.

## 2. The seam map, final state

Every `L-nn` from `SEAM-MAP.md`. *closed* = the fork is gone; *residue* = a
named host primitive at one call site; *contract* = documented in
`HOST-CONTRACT.md` / `FORMAT-DECISIONS.md`, not a code change; *open* = still
there, with the reason.

| L | Was | Now |
|---|---|---|
| L-01 | six domain openers, two state shapes | **closed** — `open-mmap-domain!` / `open-heap-domain!` over `make-domain-state` (one shape; retire queue and flush timestamp are atoms) |
| L-02 | two derefs | **closed** — one `mmap-deref` / `read-root-value` through the registry; pins are per-thread, depth-counted, on every host |
| L-03 | two swaps (cleanup, retirement, binding, logs all differed) | **closed** — one `swap-attempt!`; per-swap `LoggingSlabIO`; one nested-aware retirement; `*parent-atom*` bound on every host |
| L-04 | two root resolvers; JVM-only `{:schema :values}` → Obj | **closed for the resolver**; **open**: the `{:schema :values}` → Obj coercion survives on JVM/bb (`atom.cljc:499`) — a semantic divergence from CLJS, left because `eve.obj` itself is still forked (L-32) |
| L-05 | two flushers | **closed** — `try-flush-retires!` |
| L-06 | three back-offs | **residue** — `sleep-ms!` (`Atomics.wait` / `Thread/sleep`), one call site (`cas-backoff!`) |
| L-07 / L-07b | timers, exit hooks per host | **residue** — `start-timer!` / `stop-timer!` / `on-process-exit!`; the JVM now registers a shutdown hook too |
| L-08 | registry read/update on the forked paths | **closed** with L-02 / L-03 |
| L-09 | `MmapAtom` ×3, no real CAS | **closed** — one body per collection-interface family (`IAtom` names are the residue), `mmap-compare-and-set!` = one swap iteration; bb's flat-argument `swap` arities are a measured sci residue |
| L-10 | file-exists per host | **residue** — `file-exists?`, `ensure-parent-dir!` |
| L-11 / L-14 | `*lustre-mode*` bound at four sites | **closed** — `:lustre?` rides `region-opts`; the var is deleted |
| L-12 | `atom-sio` JVM-only; CLJS shims | **closed** — `atom-sio` on every host; the CLJS shims stay as the named CLJS host API |
| L-13 | two in-process domain caches, `atom` defined twice | **closed** |
| L-15 | `*parent-atom*` bound only on CLJS | **kept, named** (§5) — bound by `swap-attempt!` on every host, one binder |
| L-16 | `*global-atom-instance*` | **kept, named** (§5) — the legacy CLJS SAB API |
| L-17 | `*global-persistent-atom-domain*` | **kept, named** (§5) — API-level default, bound by tests only |
| L-18 | JVM ThreadLocal logs as the only mechanism | **closed** — deleted (`805b6f5`) |
| L-19 | CLJS module-level slab state | **open by scope** — the work preserves CLJS hot paths; `cljs-sio` is the one token every CLJS call site passes, so the shared code is already written as if the context were a value |
| L-20 | `mmap-mode?` | **closed** — deleted (this commit) |
| L-21 | scratch aliasing in the CLJS codec | **contract** — `serialize-key` / `serialize-element` docstrings; `serialize-flat-element` copies |
| L-22 | keyword caches ×2 | **open** (duplication) — both are value-keyed; bb's is a `ConcurrentHashMap` now |
| L-23 / L-55 | bb `locking` as atomics | **closed** — `IHostAtomics` + `FileLock` + the exclusive-hold protocol; `contention-bb-only` and `contention-eve-only` pass |
| L-24 / L-26 / L-30 | three registries, two conventions, per-host registration sets | **closed** |
| L-25 | two codecs | **open by decision** — the codec split is the one named split (`FORMAT-DECISIONS.md`); their output is one format, pinned by 54 fixtures on three hosts |
| L-27 | `default-sio` islands per call on the JVM | **closed** — one process-default heap domain (`process-heap-ctx`) |
| L-28 | bb parallel types | **closed** — bb inherits `EveHashMap`; set/vec/list materialize through the registry (level B, measured sci limits) |
| L-29 | transients JVM-only | **open** (parity, minor) |
| L-31 | JVM compat aliases (`jvm-write-map!`, `SabVecRoot`, `SabList`, …) | **open** (name residue; `jvm_conformance_test.clj` consumes them) |
| L-32 / L-41 | two `Obj` implementations; layout follows map order | **open** — `obj.cljc` is still the CLJS direct-SAB `Obj` and the read-only `JvmObj`; not on the atom data path this work unified |
| L-33 | JVM `eve-array` / `aset!` redefined to the heap-only array | **open** — `array.cljc:1293` block |
| L-34 | deftype `cas!`: untested on the JVM | **closed** — `test/eve/jvm_deftype_cas_test.clj` |
| L-35 | `eve.perf` JVM/bb-only | **closed by disuse** — the one swap has no `perf/timed`; `perf.cljc` is an unused utility |
| L-36 | flat set 0xEE unreadable on CLJS | **closed** (Unit 5) |
| L-37 | composite keys as pointers on CLJS | **closed** (Unit 5; and set elements on the JVM) |
| L-38 | int64 past 2^53 on CLJS | **contract** — `FORMAT-DECISIONS.md` §4; fixture rows skipped by `:hosts` |
| L-39 | UUID | **ok** — fixture pins the bytes |
| L-40 | symbols > 255 bytes truncated on both hosts | **closed** — refused on every host (`d219165`) |
| L-42 | `{:schema :values}` → Obj on the JVM | **open** (with L-04 / L-32) |
| L-43 | FNV-1a over UTF-16 code units | **contract** — kept as is, deliberately: changing it changes the hash of every existing registry on disk; `FORMAT-DECISIONS.md` §4 defines it so the wasm host matches (identical to UTF-8 for ASCII) |
| L-44 | CLJS-only tags 0x1A / 0x1B | **contract** — CLJS host extensions |
| L-45 | `Unsafe` | **closed** (candidate; VarHandle) |
| L-46 / L-47 | read-bytes view vs copy | **contract** — HOST-CONTRACT 8.1, unchanged |
| L-48 | `JsSabRegion` i64 emulation | **open** (contract hole, unchanged) |
| L-49 | notify no-op on bb / Lustre | **contract** — stated |
| L-50 | `-sab-encode` returns bytes on CLJS, an offset on the JVM | **closed** — `(ser/encode-eve-pointer this)` on every host |
| L-51 / L-52 | constructor signature; legacy `s-atom-env` | **closed** — `(fn [sio off])`; the first argument of a decoder is the sio |
| L-53 | `-sio-cas-i32!` | **closed** |
| L-54 | error types ad hoc | **open** — 20 `#?(:cljs js/Error. :clj …Exception.)` throw sites remain (array 7, tensor 6, dataset 4, obj 1, platform 2) |
| L-56 | bb arm hard-coding 0x1D | **closed** — the bb arm is gone; the JVM arm reads the header tag through the registry |
| L-57 | bb thread epochs keyed by `:main` | **closed** — pins keyed by the host thread id on every host |
| L-58 | bb cannot load `eve.atom` | **closed** |

Round-2 findings (`COOP-ATOM-REPORT-ROUND2.md`): N1 (drop-live-offsets)
closed by the one retirement collector; N2 (bb not a CAS participant) closed
by the hold protocol; N3 (heartbeat high word) closed; K1 / K3 closed by Unit
5; K2 closed because bb no longer materializes maps (it hashes with eve's
HAMT hash like every host); F1 / F2 and C1–C4 are host-model facts, not format
defects, and still occur (§6).

## 3. The Unit-3 verdicts, as applied

1. *No `Unsafe`, VarHandle over `ByteBuffer` — keep.* Kept; the operator
   confirmed it mid-way. bb could not follow (no `MethodHandles`), which is
   the measured fact behind the hold protocol.
2. *Keep the heap domain, replace how it is reached — applied.* One opener
   per region kind; the JVM's process-default heap domain replaces the
   per-call islands.
3. *Keep the deref boundary; `$` as a function — applied.* `eve.atom/$`. **Reversed 2026-10-23**: `$` is the
   read-region macro it was designed as (`eve.alpha/$`, `src/eve/read_region.cljc`); the function is
   `eve.atom/read-pinned` (CHANGELOG [Unreleased]).
4. *Reference implementation first; CLJS hot paths are the constraint —
   applied.* The N-API / DataView paths in `alloc.cljc`, `array.cljc` and
   `tensor.cljc` are untouched; the CLJS codec keeps its scratch buffers.
5. *Sequencing — modified as the verdict said.* Registry → atom → bb → bytes;
   each phase ended green on both suites, the round trip and the bb load, and
   was one commit + push.
6. / 7. *The registry shape survives a host with different type identity.*
   It did: bb's map-mode instances are not `instance?` of their type, so
   `eve-hash-map?` compares the registered type key on bb.

The plan's P3 items not done: slab-backed `eve-array` on the JVM (L-33), one
`Obj` (L-32), `eve.platform` throw helpers everywhere (L-54), CLJS transients
(L-29). They are outside the atom data path this work unified and are listed
as open above rather than half-done.

## 4. Every surviving reader conditional

351 sites in `eve/src` (BASE 371). The seam map's four kinds: (a) algorithm
fork, (b) store fork, (c) bb exclusion, (d) host primitive. Kind (b) is zero.
Kind (c) is 51 sites, every one a measured sci limit. Kind (a) survives only
off the atom data path. Everything else is (d).

| File | Sites (BASE) | What they are |
|---|---|---|
| `array.cljc` | 57 (58) | (d) the typed-array fast path (`resolve-live-tv` ×8; JS has no `Float/intBitsToFloat`, so float decode goes through a `DataView`), `.-offset__` vs `.offset__` field access ×8, `(int k)` coercions ×6, `js/Error.` vs `IllegalArgumentException.` ×7 (L-54), `IPrintWithWriter`; (c) two `ISabRetirable` arms bb cannot extend onto a map-mode instance; (a) the JVM heap-array block (L-33) |
| `vec.cljc` / `set.cljc` / `list.cljc` | 40 / 39 / 31 (41 / 40 / 31) | (c) the deftype, constructor and disposal blocks bb cannot host (sci rejects `IPersistentVector` / `IPersistentSet` / `ISeq`), the `false` predicates, the materializing-reader registration arms (~10 each); (d) field access, `IReduce` / `IPrintWithWriter` vs the Java interface blocks, `-nth` / `.nth`, `-conj` / `.cons`, `(int cnt)`, byte length, the codec split (`ser/serialize-element` vs `value+sio->eve-bytes`, 3 each), `print-fn` / `coll-writer` registration arms; L-31 compat aliases (`SabVecRoot`, `SabList`) |
| `map.cljc` | 34 (43) | as vec/set, plus (c) `eve-hash-map?` by type key, transient declare, map-mode print; (d) `MapEntry.` vs `MapEntry/create`, `IKVReduce`; the CLJS direct-map-encoder block |
| `atom.cljc` | 30 (45) | (d) the ten host primitives (`now-ms`, `thread-id`, `sleep-ms!`, `new-lock`, `start-timer!`, `stop-timer!`, `on-process-exit!`, `file-exists?`, `ensure-parent-dir!`, `cas-backoff!` — lines 61–129, one call site each), `^js` hints ×6, byte length, 32-bit coercions, the `MmapAtom` / `MmapAtomDomain` interface families, the CLJS compat shims; (c) bb's flat-argument `swap` arities (sci spreads `swap!`'s extra args) and the `ILookup` exclusion |
| `tensor.cljc`, `dataset.cljc`, `dataset/functional.cljc`, `dataset/argops.cljc` | 21 / 9 / 14 / 13 (same) | (a) numeric kernels written for JS typed arrays and for JVM primitive arrays; `js/Error.` throws. Not on the slab/atom path; CLJS perf is out of this work's scope. Unchanged, listed. |
| `deftype_proto/data.cljc` | 14 (8) | (d) `Object` / `default` protocol-extension names ×2, the `nil` extension (JVM only), `compare-bytes` byte access ×4, per-host default capacity tables, the `IBulkAccess` JVM protocol |
| `deftype_proto/alloc.cljc` | 13 (8) | (d) the CLJS module slab (L-19) block boundaries and `cljs-sio`, the codec split ×3, refresh (CLJS module vs JVM regions), `SLAB_SIZES` indexing |
| `mem.cljc` | 9 (11) | (d) the region implementations themselves (contract §1), the bb region block (`IHostAtomics` lock-only — sci has no `MethodHandles`), `lustre-region?`, `Object` / `default` |
| `obj.cljc` | 8 (8) | (a) L-32 — two `Obj` implementations |
| `hamt_util.cljc` | 8 (8) | (d) 32-bit arithmetic (`imul`, `rotl`, `ushr`, unsigned byte) and array length — the portable Murmur3's host arithmetic |
| `deftype_proto/serialize.cljc` | 4 (4) | (d) the CLJS codec's block boundaries |
| `deftype/slab_runtime.cljc` | 4 (4) | (a) serialized deftype fields: `DataView` on CLJS, `ISlabIO` on the JVM (L-34's remaining fork) |
| `platform.cljc` | 2 (4) | (d) the throw helpers — the one place |
| `perf.cljc` | 1 (1) | (d) JVM/bb-only `timed`; no consumer in `src` |

The `:bb` sites: 51 (BASE 81). All are either a measured sci limit — no
`deftype` for vector/set/seq interfaces, no `IReduceInit` / transients /
`Object.equals`, no `#'clojure.core/print-map`, no `MethodHandles`, spread
`swap!` arguments — or the bb region block. None is a second implementation
of an algorithm: bb runs the JVM code.

## 5. Ambient state

| Var | Where | Why it stays |
|---|---|---|
| `d/*parent-atom*` | bound by `swap-attempt!` on every host (one binder); read by the typed-array codec path, `eve.array/has-slab?`, the deftype constructor guard | marks the dynamic extent of a swap's `f` — the "in transaction → mutable typed-array view" decision. Unit 3 proposed the deref boundary instead; kept because the typed-array path depends on it and it now behaves the same on every host |
| `d/*worker-id*` | `eve.shared-atom` (CLJS legacy SAB atom) | worker identity of the legacy CLJS API; not on the mmap path |
| `atom/*global-persistent-atom-domain*` | one reader (`resolve-domain`); bound by `mmap_domain_test.cljs` | API-level default-domain override, not on the slab data path |
| `atom/*global-atom-instance*` | CLJS compat `atom-domain`, `deftype_proto.clj`, `eve.array`, `shared_atom.cljs` | the legacy CLJS SAB API's instance |

Deleted: `*lustre-mode*`, `*persistent?*`, `*worker-slot-idx*`,
`*read-epoch*`, the JVM ThreadLocal logs, `mmap-mode?`, bb's `bb-lock`.
`data.cljs` (`*use-flat-hashtable*`, `*parallel-reduce*`) and
`shared_atom.cljs` are the CLJS host layer outside the seam map; they are
unchanged.

## 6. Cross-host results in detail

**Round trip.** Phase C: Node → bb → Node → JVM → bb → Node, exact. The
coop-atom chain (no wasm on this box, so the order is node jvm bb node jvm bb
node): every turn read the value the previous turn left and the JVM read
exactly the step each turn committed, 47 assertions. The semantics battery
found 64 divergences, all named: F1 12 (sortedness / metadata lost — the
format has no tag for them), F2 10 (JVM / bb refuse BigInt, Ratio, BigDecimal,
char — no tag), C1 5, C2 5, C3 13, C4 19 (the CLJS number and char model);
**K1 0, K2 0, K3 0**; unnamed 0. The harness's own `vanished` assertion fires
for K1, K2, K3 (fixed) and V1, V2 (no wasm participant here): per its text,
the exception list and `COOP-ATOM-REPORT-ROUND2.md` §4 now move with the fix
— on the harness branch, not here.

**Contention.** `contention-jvm-only`, `-node-only`, `-bb-only` 5 / 5 each;
`contention-eve-only` 9 / 9; `contention_bench` Counter 300 / 300 five times;
`stress_atom` Phase 4 Counter 1000 / 1000. Latencies in §0.

**One unexplained event.** During phase C, one `contention-eve-only` run
deadlocked with the root showing `hold 2 inflight -2` while a stale
bench-worker build (BASE code) ran concurrently against another domain. Not
reproduced in 3 + 5 reruns, 5 of them under the same load with the rebuilt
worker. `enter-inflight!` / `leave-inflight!` / `with-exclusive-hold*` now
throw "exclusive-hold protocol: ROOT_INFLIGHT count is corrupt" instead of
spinning. Cause: don't know.

**The wasm host (`vs.eve-disk`) — outside this work, named here.** It
already writes 0xEE for flat sets. To share a domain with bb it must honour
the two hold words (`FORMAT-DECISIONS.md` §1); to share collection keys with
anyone it must write flat sets and maps in canonical order (§2). Until then
K3 remains true for the wasm writer only.

## 7. bb, precisely

bb 1.13.226 (JDK 25). Level: full for maps, materializing for sets, vectors
and lists (HOST-CONTRACT §7 B); a cross-process CAS participant through the
hold protocol. What bb cannot do, measured: `deftype` with vector / set / seq
interfaces, `IReduceInit`, transients, `Object.equals` / `hashCode`;
`MethodHandles` / `VarHandle` / `Unsafe` / FFM; `#'clojure.core/print-map`;
`(.assoc ^Type x …)` hints; `swap!` with six or more arguments. What it can:
`FileChannel.lock`, `ConcurrentHashMap`, `ReentrantLock`, `Thread`, `future`,
`locking`, `swap-vals!`, shutdown hooks, absolute `MappedByteBuffer` gets and
puts.

## 8. Porting to host #4

`HOST-CONTRACT.md` §9 holds, with these updates from the implementation:

1. **Regions.** Implement `IMemRegion` (§2) and `make-heap-region` /
   `open-mmap-region` (§1). If the host has no cross-process atomic RMW,
   implement `IHostAtomics` with `-atomic-rmw?` false and `-with-file-lock`
   over the host's byte-range lock: the exclusive-hold protocol in `eve.atom`
   then makes the host a CAS participant with plain RMW — no second swap
   loop. bb is the worked example (`mem.cljc`, the `BbMmapRegion` block).
2. **Bytes and encoding.** A Clojure-on-JVM host runs `eve.mem`'s codec as
   is. Any other host implements the tag table (`FORMAT-DECISIONS.md` §2–§4:
   20 scalar tags, 3 flat tags in canonical order through `compare-bytes`,
   the 7-byte pointer, the refusal of long symbols) and must pass
   `test/eve/fixtures/eve-bytes.edn` — 54 values, bytes and hashes — before
   anything else.
3. **Process primitives.** The ten functions at the top of `eve.atom`
   (lines 61–129): `now-ms`, `thread-id`, `sleep-ms!`, `new-lock`,
   `start-timer!`, `stop-timer!`, `on-process-exit!`, `file-exists?`,
   `ensure-parent-dir!`, `cas-backoff!`. Each is one reader conditional with
   one call site.
4. **Interop.** The host's collection and atom interface names in the
   deftype bodies (`proto_map.clj`), printing. A host that cannot host a
   deftype at all declares itself materializing and registers readers for
   0x10–0x13 as `eve.set` / `eve.vec` / `eve.list` do for bb.
5. **Prove it.** The fixture suite on the host; the coop-atom round trip and
   `contention-*` scenarios with the host as a participant; the ratchet in §0.

Nothing else: there is one HAMT, one registry, one atom, one retirement
collector, one hold protocol.

**Measured (2026-10-16, `PORT-TARGETS.md`, re-measured on the corrected
scale):** fourteen Clojure-likes and Lisps plus EVE's own CLJS host on Node.
A: the JVM, Node (EVE's CLJS — SAB + `Atomics`, EVE's addon for files), nbb,
squint, glojure (a Go package compiled into `glj`), ClojureCLR and basilisp.
B: bb, jank, phel, let-go, Clojerl and jolt — every one reaches both region
kinds; what each lacks is hosting some of EVE's types (usually the atom's
write ops). The next hosts are glojure, cljr and basilisp; for the BEAM,
Clojerl (OTP `atomics` for the heap region, a NIF for files). The probes
found and fixed six defects in EVE (`PORT-TARGETS.md` §3.2): the JVM's
shift-30 trie index, the shared HAMT file's missing portable arm, `:default`
holding JVM code, `compare-and-set!` reporting an abandoned attempt,
`contains?` true for every key on CLJS (the four conformance failures), and a
mapping leaked per deref on Node.

## 9. Re-measure

```
cd eve
clojure -M:jvm-test                       # JVM unit + fixtures + cas!
clojure -M:jvm-e2e-test                   # JVM e2e
npm run test:compile && node target/eve-test/all.js all   # CLJS; also: fixtures mmap mmap-domain mmap-atom-e2e conformance fuzz
bb -cp src:<hosts.edn :bb roots>:test -e "(require 'eve.eve-bytes-fixture-test) (clojure.test/run-tests 'eve.eve-bytes-fixture-test)"
npx shadow-cljs compile bench-worker && clojure -M:contention-bench /tmp/cb
clojure -M:native-build-atom /tmp/s 10 && clojure -M:native-x-stress-atom /tmp/s
# coop-atom, unpatched eve, no wasm:
#   EVE_DIR=<this eve> run contention-jvm-only contention-node-only contention-bb-only contention-eve-only
#   and the two round-trip scenarios with "wasm" removed from `order`
```
