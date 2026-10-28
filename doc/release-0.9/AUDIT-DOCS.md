# eve 0.9 — docs audit

> **History:** this audit describes the tree at `112f6ced2` (2026-10-20). The lane acted on it afterwards (the docs
> repaired from `007d4edc8` on, the defects below fixed or recorded). The current state is `CHANGELOG.md` 0.9.0,
> `doc/release-0.9/RELEASE-CHECKLIST.md` §9–§10 and `doc/release-0.9/STATUS.md`.

Every document in `eve/`, judged against the code at base `112f6ced2b` (branch `claude/eve-0.9-release`), 2026-10-20
(ET). Claims were checked against `eve/src`, `eve/adapters`, `eve/hosts.edn`, `eve/bb.edn`, `eve/deps.edn`,
`eve/package.json`, `eve/shadow-cljs.edn`, `eve/test`, `eve/native` and `eve/impls` — never against another document.
The per-document findings (Parts A and B) cite `file:line` for each; spot checks of their load-bearing claims were
re-run by the integrator (zero reader conditionals in `src/`; `src/eve/lpy_host.lpy` absent; the NIF under
`adapters/core/beam/native/`; `test/eve/fixtures/` without `wire.edn`; `init-eve-on-worker!` defined nowhere;
`IWatchable` only in `eve.shared-atom`, which nothing requires; `atom-domain`'s options unread; `notify!` waking all;
`try-flush-retires!`'s margin and `value-reach` sets in `atom.cljc`) [ran].

Verdicts: **CURRENT** accurate at base · **STALE** wrong claims, each quoted with what the code says · **ARCHIVE** a
historical lane record — keep for its rationale, banner it as history and name the current authority · **DELETE** no
rationale worth keeping in a release tree · **EXCLUDE** not eve's document; keep out of release artifacts.
**MISSING** documents the release needs are listed at the end.

## Inventory and verdicts (every document in `eve/`)

| Document | Lines | Verdict | One-line reason |
|---|---|---|---|
| `README.md` | 175 | STALE → rewrite | 4 platforms (11 hosts ship); every performance number unsourced; "exabyte", "no locks", `sun.misc.Unsafe`, "bb has no in-process mode" false; install yields no working ClojureScript classpath |
| `CHANGELOG.md` | 32 | STALE | stops at 0.1.0 (2026-03-10); `[Unreleased]` empty; "Panama FFM" line false of the tree |
| `CONTRIBUTING.md` | 112 | STALE | Panama/Java rationale false; 21 of 45 suites; no adapters/hosts, no host-leak rule; project tree outdated |
| `CLAUDE.md` | 356 | STALE (maps) | identity "for ClojureScript"; namespace map misses 53 namespaces incl. `eve.keyring`, `eve.net.*`; suite list misses ~25; glj baseline does not hold (red at base); invariants and forbidden patterns CURRENT |
| `adapters/README.md` | 109 | STALE | merge-union duplicates (slab, typed-view, int32, codec twice, disagreeing); `encoding/vs`/`encoding/go` filed under codec; `crypto/vs` listed as ValueScript's (unwired) |
| `impls/README.md` | 50 | STALE (table 1) | describes `:cljs`/`:clj`/`:cljr`/`:lpy`/`:jolt`/`:clje` arms (src has 0); wrong paths (`src/eve/lpy_host.lpy`, `native/eve_clje_nif.c`); `test/coop-atom` ambiguous (the parent repo's); folders table CURRENT |
| `doc/getting-started.md` | 212 | STALE | Panama; `init-eve-on-worker!` does not exist; `:node-test` alias in a consumer's config cannot work; C++ compiler row contradicts C++20 |
| `doc/api-guide.md` | 400 | STALE | a fraction of the API; `atom-domain` options, watches, `xray-replay!`, `.-s-atom-env` do not exist; `notify!` "wake one" wrong |
| `doc/persistent-atoms.md` | 371 | STALE | Panama/Unsafe/ConcurrentHashMap/ScheduledExecutorService false; deref is not lazy; file table misses `.bm` and `.keyring`; every number unsourced |
| `doc/data-structures.md` | 1004 | STALE (parts CURRENT) | hosts and RRB sections largely current; atom/memory-model/diagnostics describe the retired descriptor-table design; `e/dataset`, `e/tensor-from-array` do not exist; every benchmark but one dated table unsourced |
| `doc/collections.md` | 200 | STALE (minor) | int-map is 16-way, not log32; three samples wrong as written; omits `eve.sorted` |
| `doc/obj.md` | 162 | STALE (minor) | "SharedArrayBuffer" → slab memory on every host; `obj/column` returns an eve array |
| `doc/architecture.md` | 301 | STALE | layer diagram has `eve.shared-atom` and Panama; HAMT node layout order wrong; no slab-io/codec/host/adapter layers |
| `doc/internals.md` | 339 | STALE | class-6 descriptor fields/statuses wrong; 8 tags missing; "0x10–0x13 SAB-only" contradicts the code's rule; 8 of 27 addon exports; ThreadLocal alloc log is `LoggingSlabIO` |
| `doc/platform-support.md` | 242 | STALE → rewrite as host matrix | 5 platforms; Panama/Unsafe; API-differences table wrong on every "unavailable" row; version matrix unrecorded |
| `doc/testing.md` | 264 | STALE | 14 of 45 suites; counts disagree with CLAUDE.md; e2e under wrong alias; no per-host runners |
| `doc/unification/HOST-CONTRACT.md` | 494 | STALE refs, living contract | 0 of 12 sampled line refs still point at their target; §5 names (`schedule-periodic!`, `current-thread-id`) old; §6–§7 call bb "materializing"; §10–§12 CURRENT |
| `doc/unification/FORMAT-DECISIONS.md` | 321 | CURRENT (gap) | every tag it covers matches `serialize.cljc`; no complete tag table |
| `doc/unification/SEAM-MAP.md` | 287 | ARCHIVE | the BASE leak register (371 conditionals → 0 today); L-ids still cited |
| `doc/unification/CANDIDATE-VERDICTS.md` | 180 | ARCHIVE | rationale for the VarHandle region and unaligned access |
| `doc/unification/UNIFICATION-REPORT.md` | 339 | ARCHIVE | 2026-10-15 scoreboard; §8 superseded by HOST-CONTRACT §9 |
| `doc/unification/PORT-TARGETS.md` | 358 | ARCHIVE | measured inventory of Clojure-likes; still the evidence for hosts not yet adopted (let-go, squint, jank, phel) |
| `doc/network-atoms/WIRE.md` | 145 | CURRENT but one claim | routes match `eve.net.wire`; `test/eve/fixtures/wire.edn` does not exist |
| `doc/network-atoms/ADAPTERS.md` | 230 | STALE (status) | §2–§5 current rationale; §1/§4.3/§7 status (glojure "not in hosts.edn", http "not done" on cljr/clojerl/glojure) outdated |
| `doc/network-atoms/SCOPE.md` | 569 | ARCHIVE | lane plan, now built |
| `doc/network-atoms/IMPL-PLAN.md` | 869 | ARCHIVE | lane contract, mostly VS/broker work |
| `doc/network-atoms/reports/phase-0..3.md` | 61–129 | ARCHIVE / move to atom.broker | VS and broker acceptance rows, not eve's surface |
| `doc/network-atoms/reports/phase-4.md` | 176 | ARCHIVE | its binding rulings ("Synchronous on every host") belong in a current network-atom page |
| `doc/network-atoms/probes/*` (3 scripts) | — | ARCHIVE / move to atom.broker | probe scripts against VS and the stand-in broker |
| `bench/legacy/contention/runs/**/*.md` (5) | 69–81 | ARCHIVE (or DELETE); EXCLUDE from artifacts | February 2026 runs of pre-0.1.0 code; never cite |
| `.claude/bundled/clojure-mcp-light/**` (10) | 26–583 | EXCLUDE | third-party agent tooling, EPL-2.0 |
| `doc/release-0.9/*` | — | this lane | STATUS, ADOPTER-TIERS, RELEASE-CHECKLIST, the audits |

Totals: 46 Markdown files before this lane — **0 CURRENT among the 13 user-facing documents**; 2 design documents
CURRENT or nearly so (FORMAT-DECISIONS, WIRE); 11 to ARCHIVE; 15 to EXCLUDE from artifacts (the bundle and the
legacy bench reports).

## The cross-cutting staleness (why so many documents went stale at once)

Six changes since 0.1.0 invalidate most of the user documentation; each is a fact of the code [read]:

1. **The public atom is `eve.atom`'s `MmapAtom` on every host.** `e/atom` is `eve.atom/atom` (`alpha.cljc:41`), over a
   heap domain or a disk (mmap) domain; `eve.shared-atom` (3,500 lines, the 0.1.0 SAB atom with watches and X-RAY) is
   **required by no namespace** in `src/`, `adapters/`, `test/` or `bench/` [ran: grep].
2. **Deref materializes.** `mmap-deref` returns plain data; lazy slab access is `swap!`'s function argument and
   `eve.atom/$`. Every "deref is lazy" sentence is wrong.
3. **No Panama, no Unsafe.** The JVM region is `MappedByteBuffer`/heap `ByteBuffer` + `VarHandle` + `LockSupport`.
4. **Eleven hosts, zero reader conditionals.** Hosts are `hosts.edn` rows of adapter roots; `src/` names no host.
5. **New surfaces with no user doc**: atom domains as atoms, meta-atoms, `seal`/`version`/`$`; network atoms
   (`eve.net.*`); encryption at rest (`eve.keyring`); the executor service (`eve.thread*`); `eve.sorted`; columnar
   `eve.alpha.ds`/`tensor`/`col`.
6. **The memory-model authorities moved.** The model is `vs/docs/memory-model.md` + `vs/docs/contention-domains.md`
   (the lane brief names them); eve's docs pointed nowhere at them at base (`doc/memory-model.md` now does), and eve's
   code diverges in five places (RELEASE-CHECKLIST §10).


---

## Part A — the user-facing documents

Facts used repeatedly (cited once here):

- **F1 — the public atom is `eve.atom/MmapAtom` on every host.** `eve.alpha`'s api table maps `atom` → `atm/atom`
  (`src/eve/alpha.cljc:41`); `eve.atom/atom` (`src/eve/atom.cljc:1111-1121`) returns `lookup-or-create-mmap-atom!` over
  a heap domain (`open-heap-domain!`, `atom.cljc:365`) or an mmap domain. `eve.shared-atom` (the old SAB atom,
  `src/eve/shared_atom.cljs`) is required by **no** namespace in `src/`, `adapters/`, or `test/` (grep `[eve.shared-atom`).
- **F2 — no watches.** `IWatchable` / `add-watch` appear only in `src/eve/shared_atom.cljs:2976,3363`. `MmapAtom` and
  `MmapAtomDomain` (`atom.cljc:760-815`) implement IDeref, IAtom, ILookup (domain), IEveAtom — no watches, no validators.
- **F3 — `atom-domain` ignores options.** `(defn atom-domain [initial-value & {:as _opts}] ...)` (`atom.cljc:1159-1169`):
  `:sab-size` and `:max-blocks` are read by nothing. It returns an in-process `MmapAtomDomain`.
- **F4 — deref materializes.** `mmap-deref` returns `(d/eve->clj (read-root-value ...))` "as plain data"
  (`atom.cljc:632-645`). Lazy slab-backed access is `swap!`'s f argument (`swap-attempt!`, `atom.cljc:647-664`) and
  `eve.atom/$` (`atom.cljc:734-746`).
- **F5 — no Panama, no Unsafe.** The JVM region is `JvmByteBufferRegion` over a heap `ByteBuffer` or a
  `MappedByteBuffer`, atomics through `VarHandle` view handles, "no sun.misc.Unsafe"
  (`adapters/region/jvm-varhandle/src/eve/region.clj:41,201,352`). No `java.lang.foreign`/`MemorySegment` anywhere in
  `src/` or `adapters/`. Types `JvmHeapRegion` / `JvmMmapRegion` do not exist (deftypes found: `JvmByteBufferRegion`,
  `LustreJvmMmapRegion`, `BbMmapRegion`, `BbHeapRegion`, `ClrRegion`, `LpyRegion`, `JoltRegion`, `CljeRegion`,
  `JsSabRegion`, `NodeMmapRegion`, `LustreMmapRegion`).
- **F6 — JVM process primitives.** Backoff sleeps by `Thread/sleep` (`adapters/process/java/src/eve/process.clj:14-17`,
  called from `cas-backoff!` `atom.cljc:76-82`), the heartbeat is a plain `Thread` loop (`process.clj:19-25`) every
  10 000 ms (`atom.cljc:359`), and epoch pins are an atom map `{thread-id [epoch depth]}` under a `locking` token
  (`atom.cljc:304-314,389-407`) — not `LockSupport.parkNanos`, not `ScheduledExecutorService`, not `ConcurrentHashMap`.
- **F7 — eleven hosts.** `hosts.edn` keys: `:jvm :cljs :browser :bb :nbb :cljr :lpy :jolt :clje :vs :glj`
  (`hosts.edn:5-67`). Shared `src/` has **0** reader conditionals (`eve/.host-leak`: `conditionals 0`; `grep -c '#?('
  src` is 0 everywhere).
- **F8 — what exists that no user doc names:** see `## MISSING`.

---

### eve/README.md
Verdict: **STALE** (rewrite for 0.9)

- L3-8, L16 "Shared-memory persistent data structures for ClojureScript and Clojure … JVM Clojure, Node.js
  ClojureScript, and Babashka processes"; "Four platforms, one format — browser, Node.js, JVM, Babashka". The code has
  eleven hosts (F7) — ClojureCLR, basilisp, jolt, clojerl, glojure, nbb, ValueScript are absent.
- L12, L20-58: every performance number (swap p50 per host, the 11 MB / 103 MB / 1.1 GB table, the ASCII chart, the
  663 ops/s contention row) is **UNMEASURED-HERE**; L23 "Measured on Linux, JDK 21, Node 18, Babashka 1.x" gives no
  date, commit, or command. The Features bullet (L12: JVM ~1 ms, Node ~0.16 ms, bb ~1.4 ms, "12 MB … 1,800 keys
  through 1 GB … 150,000 keys") contradicts the table four lines below (L27-31: JVM 0.71-0.82, Node 0.07-0.09, bb
  0.38-0.43 ms; 2,812 / 17,428 / 62,012 keys). Remove or re-measure.
- L13 "Exabyte-scale durable atoms … grow … to terabytes": the per-class maximum capacities are 1 GB / 1 GB / 1 GB /
  512 MB / 256 MB / 256 MB (`src/eve/deftype_proto/data.cljc:116-122`); nothing in code supports "exabyte".
- L14 "lock-free CAS … No coordination server, no locks, no leader election": babashka's region is lock-only
  (`adapters/region/jvm-nio-lock/src/eve/region.clj`, a `FileChannel` byte-range lock; the domain state carries
  `:lock-only?`, `atom.cljc:308`; writes go through `with-exclusive-hold*`, `atom.cljc:229`), and `eve.thread.domain`
  ships `elect!` (named-lease election). True for the hardware-atomic hosts only.
- L18 "Zero-copy reads — swap! walks mmap'd/SAB memory directly. No deserialization into intermediate heap objects":
  true of `swap!`'s f argument; false of deref, which materializes (F4).
- L68-79 install: `:git/sha "0e084fff"` and `"eve-native": "github:SeniorCareMarket/eve"` name the standalone repo;
  the sha is unverifiable in this tree and predates every 0.9 namespace. `package.json` name `eve-native` matches
  (`package.json:2`) but its version is `1.0.0` (`package.json:3`) against CHANGELOG's 0.1.0. A git dep of
  `deps.edn` gives a consumer only the JVM `:paths` (`deps.edn:2-4`); a ClojureScript consumer cannot get
  `:node-test`'s adapter roots from a git coordinate (aliases are not transitive) — the documented CLJS install does
  not yield a working classpath.
- L91-95 in-process table: JVM "`byte[]` + `sun.misc.Unsafe` atomics" — false (F5: heap `ByteBuffer` + VarHandle).
  Babashka "mmap files only (no in-process heap mode)" — false: `adapters/region/jvm-nio-lock/src/eve/region.clj:280`
  defines `make-heap-region` (`BbHeapRegion`, `:195`) and bb's `hosts.edn` row carries `slab/heap`.
- L102-103 "Named atoms — same :id returns the same atom across threads/workers": in-process domains are cached per
  id in this process (`heap-domain-cache`, `atom.cljc:1101-1109`); each id is its own heap domain; nothing shares it
  with another worker without `eve.sab/sab-transfer-data` on ClojureScript.
- L144-150 Building: `npx shadow-cljs compile eve-test` alone leaves `mmap-atom-e2e` without its worker; the
  package script is `npm run test:compile` (both builds, `package.json:7`). "builds native addon via postinstall":
  the script is `install`, not `postinstall` (`package.json:10`).
- L154-167 doc table omits every 0.9 surface (network atoms, atom domains/meta-atoms, key ring, eve.thread, eve.sorted,
  dataset/tensor/col, adapters, hosts, impls).
- L173-175 "License MIT": `src/eve/rrb_vector*.cljc` (8 files) carry EPL 1.0 notices (core.rrb-vector port);
  the README states no mixed license. LICENSE reads "Copyright (c) 2022 John Newman" (`LICENSE:3`).

### eve/CHANGELOG.md
Verdict: **STALE**

- Ends at `[0.1.0] - 2026-03-10` (L11); `[Unreleased]` is empty (L9) though every namespace in `## MISSING` is new
  since. No 0.9 entry.
- L18 (0.1.0) "WASM-accelerated bitmap allocator with SIMD": exists today only on ClojureScript/nbb
  (`adapters/slab/module/src/eve/deftype_proto/wasm.cljs`). L26 "JVM persistent atoms via Panama FFM (Java 21+)":
  false of the current tree (F5); as a 0.1.0 history line it is unverifiable here (pre-import history is in the
  standalone repo; this tree's eve history starts at the import `b24c8c034`, 2026-10-15).
- L27 "X-RAY memory diagnostics": lives only in the unreachable `eve.shared-atom` (F1).
- L31-32 compare/tag links point at `SeniorCareMarket/eve` tags (`v0.1.0`) — unverifiable here.
- Version disagreement: `package.json:3` says `1.0.0`; deps.edn has no version; CHANGELOG says 0.1.0.

### eve/CONTRIBUTING.md
Verdict: **STALE**

- L10 "Java 21+ (for JVM persistent atoms via Panama FFM)": no Panama (F5). The JDK floor the code needs is not
  stated anywhere; `crypto/java` uses `Signature "Ed25519"` (JDK 15+, `adapters/README.md` row, code at
  `adapters/crypto/java/src`); virtual threads (`-Deve.thread.virtual=true`) need JDK 21.
- L11 "Node.js (18+)": unverified; this box has Node 24.22.0. UNMEASURED-HERE.
- L26 compiles only `eve-test`; mmap-atom-e2e needs `mmap-worker` (`shadow-cljs.edn:23-26`); use `npm run test:compile`.
- L44-48 suite list is 21 names; the runner registers 45 (`test/eve/runner/eve_test_main.cljs:417-462`: also
  conformance, fuzz, fixtures, em5, hamt-util, lustre, lustre-bench, dataset, array-hosts, tensor, rrb-vector,
  columnar-*, perf-audit, repro, slab-leak, net, thread-host, thread, thread-meta, thread-domain, thread-fold,
  thread-fold-group). No mention of the per-host runners (`bb test:bb|nbb|cljr|lpy|jolt|clje|vs|glj|browser`,
  `bb.edn:12-50`) or `clojure -M:jvm-e2e-test` (`deps.edn` `:jvm-e2e-test`).
- L61-84 project tree: `alpha.cljs / .clj` → one `src/eve/alpha.cljc`; `perf.clj` is `bench/eve/perf.clj`, not
  `src/eve/`; `deftype/int_map.cljs`, `rb_tree.cljs` → `.cljc`; omits `adapters/` (the host interface), `hosts.edn`,
  `impls/`, `src/eve/net/`, `src/eve/thread/`, `keyring.cljc`, `sorted.cljc`, `dataset*`, `tensor.cljc`, `rrb_vector*`.
- Omits the first rule of the codebase: shared `src/` names no host; host code goes in an adapter; the
  `scripts/check-host-leak` gate and its `.host-leak` baseline. In this tree that gate runs only through the parent
  repo's `scripts/pre-commit-gates:25,40`; a standalone clone has no hook wiring it.
- L112 "licensed under the MIT License": EPL 1.0 files exist (see README).

### eve/doc/getting-started.md
Verdict: **STALE**

- L3 "for ClojureScript and Clojure … JVM Clojure and Node.js": eleven hosts (F7).
- L12 "Java 21+ Required for JVM persistent atoms (Panama FFM)": F5.
- L15 "C++ compiler | C++20 capable | GCC 5+, Clang 3.4+": `binding.gyp` builds with `-std=c++20` (`binding.gyp`
  cflags); GCC 5 / Clang 3.4 cannot compile C++20. Internally inconsistent.
- L27-40 install: same defects as README L68-79 (standalone sha, non-transitive CLJS classpath).
- L62-71 shadow-cljs config `{:deps {:aliases [:node-test]}}`: `:node-test` is eve's own alias (`deps.edn:7`); in a
  consumer project it names the consumer's deps.edn, which has no such alias. Does not work as written.
- L79 "SharedArrayBuffer-backed atoms … work across web workers or Node.js worker threads": every in-process atom is
  an `MmapAtom` over a heap domain (F1); on ClojureScript its slabs are the module SABs, shareable only by handing
  `eve.sab/sab-transfer-data` to the worker.
- L93 "Named atoms — same :id returns the same atom": per process only (README L102).
- L98 "deserialized back on deref": correct (F4) — but contradicts architecture.md/persistent-atoms.md "lazy".
- L148-160 "Cross-Worker Sharing": `(eve/atom-domain {:counter 0})` uses alias `eve` the page never requires (the page
  requires `e`); `(eve/init-eve-on-worker! transfer)` — **no such function exists** anywhere in `src/`, `adapters/`,
  or `test/`. `sab-transfer-data` exists in `adapters/region/js/src/eve/sab.cljs:45` and returns
  `{:sab :reader-map-sab :slab-sabs :root-sab}`, not the two keys shown (L154).
- L164-182 value-type table omits chars (0x14), big integers (0x16), ratios (0x17), big decimals (0x18), typed arrays
  (0x1B), eve arrays/objects, records (0x1A) — all encoded (`src/eve/deftype_proto/serialize.cljc:34,44-46,70-78`).
- L190 "Integer Map … O(log32 n) lookups": a 16-way trie (`src/eve/deftype/int_map.cljc:4`), so log16.
- L208-212 next steps omit every 0.9 surface.

### eve/doc/api-guide.md
Verdict: **STALE** (covers a fraction of the API; several documented calls do not exist)

- L9-17 namespace table: `eve.shared-atom` listed as public "SAB-backed atom internals" — unreachable from the API
  (F1). `eve.obj`, `eve.array` "CLJS + CLJ" — every host (`src/eve/obj.cljc` docstring "on every host").
  Missing: `eve.atom`, `eve.alpha.ds/tensor/col`, `eve.sorted`, `eve.keyring`, `eve.net.*`, `eve.thread*`,
  `eve.rrb-vector`, `eve.deftype` (`deftype`, `eve-deftype`, `extend-host-vectors`), `eve.typed-view`, `eve.sab`.
- L35-36 "In-memory (SAB-backed on CLJS, heap-backed on JVM)": both are `MmapAtom` over a heap domain (F1).
- L60 "Returns: An atom implementing IDeref, IReset, ISwap, IWatchable (SAB)": no IWatchable (F2).
- L64-82 `e/atom-domain` "Standalone `SharedArrayBuffer`-backed AtomDomain", `:sab-size` (default 256 MB),
  `:max-blocks` (default 65 536), `add-watch`/`remove-watch`: options ignored (F3), no watches (F2).
  `e/atom-domain` does exist and is available on every host (not "CLJS").
- L48-55 `:persistent true` default domain `./eve/` — correct (`atom.cljc:1067-1076`).
- L96-104 `e/aget`/`e/aset!` "atomic for integer types": `eve.array/aget` docstring; CURRENT.
  `eve.typed-view/get-typed-view` exists (`adapters/typed-view/js/src/eve/typed_view.cljs`).
- L106-110 `e/shared-atom?` "true if SharedAtom": it is true for `MmapAtom` or `MmapAtomDomain` (`atom.cljc:1131-1134`).
- L114-119 `eve.sab/sab-transfer-data` returns four keys, not two (`sab.cljs:45-55`).
- L121-137 macros "CLJS — requires :include-macros true": `eve.alpha/deftype`, `extend-type` exist on every host
  (`alpha.cljc:27-35`); the `:include-macros` note applies to ClojureScript only.
- eve.obj section L141-216: every named fn exists (`create-schema obj obj-array assoc! cas! add! sub! exchange!
  assoc-in! get-in column column-reduce column-map!`, census of `src/eve/obj.cljc`). Stale details: "backed by
  SharedArrayBuffer" → slab memory through ISlabIO on every host (`obj.cljc:1-14`); `obj/column` "raw Int32Array" →
  the field's eve-array column (`obj.cljc:123`, `obj/impl.cljc:310-313`); `cas!` also takes `:obj`/`:array` fields
  (`obj.cljc:67-71`). Undocumented: `get`, `cas-in!`, `add-in!`, `sub-in!`, `exchange-in!`, `get-schema`,
  `get-offset`, `retire!`, `obj-from-offset`.
- eve.array section L221-284: "storage in SharedArrayBuffer" — on the JVM, bb and jolt a fresh `eve-array` is a
  `HeapEveArray` over Java's primitive arrays (`src/eve/array.cljc:483-485`). L255 `(arr/notify! my-array idx) ;; wake
  one waiting worker` — **wrong**: count defaults to `##Inf`, wake all (`array.cljc:647-651`). Supported types list
  (incl. `:uint8-clamped`) matches `type-kw->subtype` (`array.cljc:72-87`) but the `eve-array` docstring omits
  `:uint8-clamped` (`array.cljc:487`). Undocumented: `eve-array-uninit`, `int32-array`, `int32-array-from`,
  `amap!`, `afill!`, `acopy!`, `exchange!`, `from-double-array`, `from-int-array`, `from-byte-array`,
  `array-values`. `eve.typed-view` also has `amin-simd`, `amax-simd`, `aequal-simd?`, `from-typed-array`.
- int-map L290-324: `int-map merge merge-with range` exist; `range` is inclusive `[min-k, max-k]` (`int_map.cljc:737-
  742`) — CURRENT. Undocumented: `update`, `(int-map k v & kvs)` constructor (`int_map.cljc:698-706`).
- rb-tree L328-350: `sorted-set`, `member?` exist (`rb_tree.cljc:436-446`). CURRENT. The far more complete
  `eve.sorted` (comparators, `sorted-map`, `subseq`, `rank`) is not mentioned.
- L354-399 "eve/deftype — Custom SAB-Backed Types (CLJS)": every host; field-hint table L363-369 "(no hint) Serialized,
  stored via pr-str/cljs.reader" contradicts data-structures.md L391 ("an eve value in eve's encoding") — the macro
  expansion is `src/eve/deftype/macros.cljc`; pr-str storage is not in it (grep `pr-str` there: none). Missing hint
  `^:host`.
- L403-409 Diagnostics: `(.-s-atom-env my-atom)` — `MmapAtom` has no such field (`atom.cljc:760`);
  `a/xray-replay!` — **does not exist** anywhere; `validate-storage-model!` exists only in the unreachable
  `src/eve/shared_atom.cljs:756`.

### eve/doc/persistent-atoms.md
Verdict: **STALE**

- L5-6, L82-85: "JVM Clojure and Node.js ClojureScript" — eleven hosts (F7); the multi-host coop scenario lives at
  `eve/test/coop/mix.cljc`.
- L34-53 JVM example: names exist (`atom/atom`, `persistent-atom-domain`, `persistent-atom`, `close-atom-domain!`,
  `atom.cljc:834,867,1086,1111`). CURRENT as API.
- L97-99 "On JVM this uses JvmHeapRegion (off-heap byte arrays with sun.misc.Unsafe atomics)": F5.
- L156-168 file table omits the six bitmap files `<path>.slab<N>.bm` (`src/eve/deftype_proto/alloc.cljc:835`) and the
  key ring file `<path>.keyring` of an encrypted domain (`src/eve/keyring.cljc` ns doc).
- L190 "Deref … lazy on CLJS and JVM": materialized (F4); lazy only inside `swap!` and `$`.
- L197-198 "The CAS … provided by the native addon (Linux futex) on Node.js and Panama FFM on JVM": the addon's CAS is
  `__atomic_*` (`native/mmap_cas.cc:36-39`); futex is its wait/notify; the JVM's is VarHandle (F5).
- L205-210 epoch GC: 256 worker slots (`data.cljc:294`), 30 s stale (`data.cljc:314`, `atom.cljc:104-111`), 50 ms flush
  (`atom.cljc:57`) — CURRENT. The flush is also forced past 64 queued entries (`FLUSH_QUEUE_THRESHOLD`,
  `atom.cljc:58,609-618`) — unstated.
- L216-219, L353-354 "up to 256 named atoms": 256 slots including slot 0, the registry (`data.cljc:319-335`) — at most
  255 names, and each meta-atom (`eve.atom/meta-atom`, `atom.cljc:988-998`, used by `eve.thread.domain`) takes a slot.
- L236-244 platform table: "Panama FFM", "sun.misc.Unsafe via Panama", "LockSupport.parkNanos",
  "ScheduledExecutorService", "ConcurrentHashMap", "JvmHeapRegion (byte[] + Unsafe)" — all false (F5, F6). "Returns
  lazy EveHashMap (post-OBJ-8)" — F4. "Single-threaded (Node event loop)" — `eve.thread` runs `worker_threads`
  (`adapters/thread/node`).
- L251-329 every throughput, latency, footprint, cold-start and round-trip number: **UNMEASURED-HERE** (no date,
  commit or command). L264-265 explains JVM reads by laziness that deref no longer has (F4).
- L333-347 value types: omits chars, big numbers, sets/vectors via typed arrays, UUIDs, dates, symbols that ARE
  encoded (`serialize.cljc`).
- L359-360 "No watches or validators": CURRENT (F2).
- L366-367 "macOS and Windows work": Windows has no mmap path in the addon (`native/mmap_cas.cc` is POSIX; no
  `_WIN32` branch: grep finds none).

### eve/doc/data-structures.md
Verdict: **STALE** (the hosts section L235-449 and RRB section L450-524 are largely CURRENT; the atom, memory-model,
diagnostics and performance sections are STALE)

- L3, L7-18 overview: "SharedArrayBuffer-backed atoms" (F1). `e/dataset` and `e/tensor-from-array` **do not exist** —
  they are `eve.alpha.ds/dataset` and `eve.alpha.tensor/from-array` (`src/eve/alpha/ds.cljc:15`,
  `src/eve/alpha/tensor.cljc:15`); `eve.alpha`'s api has neither (`alpha.cljc:40-72`).
- L41, L94: AtomDomain/SharedAtom "implements … IWatchable, IMeta, IWithMeta" — F2; no IMeta on `MmapAtom`.
- L44-49, L537-583: `:sab-size` / `:max-blocks` and their defaults — F3.
- L104, L125 "deserialized back to heap memory" CURRENT (F4). L125 "Vectors and lists do not have standalone
  constructors" — `eve.vec/eve-vec` and `eve.list/eve-list` exist (census), and L277 of the same page names
  `eve.vec/eve-vec`.
- L173 `(arr/notify! my-array idx) ;; wake one` — wakes all (`array.cljc:647-651`).
- L288-325 ClojureCLR / basilisp: "`eve.deftype-proto.macros/cljr-translate-protos`", "`lpy-translate-protos`" — these
  are now in the types adapters (`adapters/types/clr/src/eve/types.cljr:84`, `adapters/types/basilisp/src/eve/
  types.lpy:178`). "`eve.lpy-host` (`src/eve/lpy_host.lpy`)" — **no such file**; it is `adapters/completion/basilisp/
  src/eve/host_completion.lpy`. "are its `:cljr` arms" / "`:lpy` arms" (L303, L325) — `src/` has no reader
  conditionals (F7); host primitives are adapters (`hosts.edn` rows).
- L327-345 jolt "reads a `.cljc` file's `:clj` arms … A `:jolt` arm, before `:clj`": no arms in `src/` (F7); jolt's
  regions are `adapters/region/jolt-ffi`, its completion `adapters/completion/jolt`.
- L347-365 clojerl: `eve.deftype-proto.macros/clje-deftype` → `adapters/types/beam/src/eve/types.clje:303`;
  "NIF `native/eve_clje_nif.c`, built … into `target/clje/`" → `adapters/core/beam/native/eve_clje_nif.c`, built into
  `adapters/core/beam/target/clje/` (`bb.edn:49` doc string). "reads a .cljc file's :clje arms" — none (F7).
- No glojure or ValueScript row in the L242-251 host table though both are `hosts.edn` hosts (`hosts.edn:58-67`).
- L512-513 "`atom.broker/test/coop-atom`, `clj/coop/mix.cljc`": exists in the parent repo
  (`test/coop-atom/clj/coop/mix.cljc`); eve's own copy of the scenario is `eve/test/coop/mix.cljc`. Make the path
  repo-qualified.
- L518-524 RRB status counts and "26 s" — UNMEASURED-HERE (CLAUDE.md's baseline says ~45 s; the two disagree).
- L526-533 Memory Model "Descriptor table … SoA mirrors of descriptor fields": describes the retired design
  (`eve/CLAUDE.md` Forbidden Patterns §1: "There is no … block descriptor table, status-mirror array,
  capacity-mirror array"). The slab allocator is bitmap-per-class (`alloc.cljc`, `data.cljc:161-174`).
- L537-573 Default memory budget: sources `shared_atom.cljs atom-domain`, `data.cljs SIZE_OF_BLOCK_DESCRIPTOR`,
  `READER_MAP_SAB_SIZE_BYTES`, `wasm_mem.cljs MAX_PAGES`, `SCRATCH_SIZE` — **none of these constants exist**;
  `MAX_PAGES` is in `adapters/slab/module/src/eve/deftype_proto/wasm.cljs:128`. The slab class table L553-562
  matches `data.cljc:110-134` — CURRENT. L585 "`deftype_proto/data.cljs` (`SLAB_CLASS_CAPACITIES`)": file is
  `data.cljc`, and capacities are overridable per mmap domain (`persistent-atom-domain … :capacities`,
  `atom.cljc:842`).
- L587-595 Block states FREE/ALLOCATED/RETIRED/EMBEDDED/ORPHANED: no RETIRED/EMBEDDED/ORPHANED constants in code;
  the overflow descriptors know FREE=0, ALLOCATED=1, ZEROED_UNUSED=-1 (`coalesc.cljc` ns doc).
- L597-607 Diagnostics: `.-s-atom-env`, `xray-replay!` — do not exist (see api-guide).
- L611-754 dataset / tensor / col: every named fn exists (`eve.alpha.ds`, `eve.alpha.tensor`, `eve.alpha.col` census).
  CURRENT. `(ds/dataset {...})` with one arg allocates "in a fresh heap slab domain" outside a swap
  (`src/eve/dataset.cljc:126-131`) — the page's "must be created in atom context" (L620) overstates.
- L756-944 every benchmark figure: **UNMEASURED-HERE**. L823 dates one run (2026-10-16, "one 4-core box") — not this
  tree's commit. L864 `IBulkAccess` exists (`data.cljc:448`).
- L948-966 bench commands: `clojure -M:columnar-bench` (deps.edn alias exists), `columnar-bench|mmap|inmem` suites
  (`eve_test_main.cljs:448-450`), `bb bench:bb`, `bench:nbb` (`bb.edn:14,18`) — CURRENT.
- L968-1004 "Fixed issues": changelog material, not a user doc — move to CHANGELOG.

### eve/doc/collections.md
Verdict: **STALE** (minor)

- L5, L17 "SharedArrayBuffer-backed" / "all in shared memory": slab memory of the atom's domain (F1).
- L23 "Integer Map: O(log32 n)": 16-way (`int_map.cljc:4`).
- L26, L57 "must be used within atoms" / "all EVE types must live in atoms": constructors allocate in the running
  swap's domain, else the process's own (`int_map.cljc:699-700`, `rb_tree.cljc` `sorted-set`).
- L87-96 samples call `update :index` / `update :counts` on an atom (`state`, L76) that holds only `:temps`, so
  `im/merge`/`im/merge-with` would receive nil — not run here; reads wrong.
- L103 `(swap! state update-in [:board 1] inc)`: `state` has no `:board` (that is `scores`, L61).
- L106-107 `(im/range (:board state) 3 7)`: `state` is the atom, not the swap's value — wrong as written.
- L143, L152 deref shows a list `("clojure" …)`: a sorted set materializes to `clojure.core/sorted-set`
  (`rb_tree.cljc:419-420`), printed `#{…}`.
- Omits `eve.sorted` (sorted maps/sets with comparators, `subseq`, `rsubseq`, `rank`, `nth-entry`; census of
  `src/eve/sorted.cljc`) and `eve.rrb-vector`.

### eve/doc/obj.md
Verdict: **STALE** (minor)

- L3, L8 "backed by SharedArrayBuffer … full Atomics API for cross-worker access": slab memory through ISlabIO on every
  host; atomics on int32/uint32 fields (plus `cas!` on `:obj`/`:array`) only (`obj.cljc:1-14,67-80`).
- L10 "allocated from the global AtomDomain's shared memory … must be used within atoms": allocates in the running
  swap's domain, else this process's own (no global AtomDomain exists).
- L44-45 `:obj` / `:array` "Atomic? Yes": only `cas!` accepts them; `add!`/`sub!` refuse them (`obj.cljc:67-80`).
- L140 `obj/column` "raw Int32Array": returns the field's eve-array (`obj/impl.cljc:310-313`).
- Names used (`create-schema obj obj-array assoc! assoc-in! get-in column column-reduce column-map!`) all exist.
  Undocumented: `cas-in! add-in! sub-in! exchange-in! get get-schema get-offset retire! obj-from-offset`.

### eve/doc/architecture.md
Verdict: **STALE**

- L11 "JVM Clojure and Node.js ClojureScript share identical on-disk formats": eleven hosts (F7).
- L12 "No locks": bb's lock-only region (README L14 finding).
- L18-46 layer diagram: `eve.shared-atom` as a peer of `eve.atom` (F1); bottom row `JvmMmapRegion (Panama FFM)` (F5).
  Missing layers that now exist: `eve.slab-io` (the memory seam every slab access goes through,
  `adapters/slab-io/*`), `eve.codec` (codec adapters), `eve.host`, the adapters/hosts model.
- L56-61 in-process table: JVM `byte[] + sun.misc.Unsafe`, `Unsafe.compareAndSwapInt` (F5); CLJS "AtomDomain — a
  single SAB holding many named atoms" (F1, F3).
- L70 "Cross-process CAS via Linux futex(2) (Node.js) or Panama FFM / sun.misc.Unsafe (JVM)": addon CAS is
  `__atomic_*`, JVM VarHandle.
- L91-96 slab structure, L100-111 offsets, L156 `MAX_SWAP_RETRIES` 1000 (`data.cljc:527`), L167 50 ms flush, L176-185
  root layout (64 + 6 144 + 64 + 2 048 = 8 320, `data.cljc:280-338`), L196-218 file layout with `.bm` files — CURRENT.
- L129-139 bitmap node layout "[inline KV data…][child offsets…]": the node is header(12) → child offsets
  (4 × popcount node-bm) → hash array (4 × popcount data-bm) → KV data (`src/eve/map.cljc:42-43,95-105`). Order wrong
  and the hash array is missing.
- L149 "Deref … lazy on both platforms" (F4).
- L164-165 "pins its current epoch via an atomic write to the reader map": the pin is the worker slot's epoch word in
  `.root` (`mmap-pin-epoch!`, `atom.cljc:150`; `slot-pin!` `:384`); per-thread pins aggregate into it.
- L176 "up to 256 named atoms": 255 plus slot 0 (persistent-atoms finding).
- L224-237 IMemRegion implementations table: `JvmMmapRegion`, `JvmHeapRegion` do not exist (F5); omits the bb, CLR,
  basilisp, jolt, clojerl, glojure, VS and Lustre regions.
- L245-257 ISlabIO listing omits `-sio-cas-i32!` (and the protocol's other members, `alloc.cljc:99-140`); omits that
  shared code reaches it only through `eve.slab-io` (HOST-CONTRACT §12 seam; `adapters/slab-io/protocol`).
  `CljsSlabIO` exists (`adapters/slab/module`… deftype found at `:721`).
- L266-301 every performance number: **UNMEASURED-HERE**; L276's laziness rationale contradicts F4.

### eve/doc/internals.md
Verdict: **STALE**

- L11 "each size class gets its own SharedArrayBuffer (CLJS) or mmap file (JVM)": every host's mmap domain is files;
  in-process is heap regions (JVM family) or module SABs (CLJS).
- L13-27 slab header — CURRENT (`data.cljc:161-174`). L29-74 bitmap/growth/split files — CURRENT in shape; the
  growth table matches `data.cljc:110-134`.
- L84-108 overflow class 6: header [8] is `max_descriptors`, [12] `alloc_cursor`, [24] `desc_table_offset`
  (`coalesc.cljc` ns doc), not the slab header's fields; descriptor STATUS values RETIRED=2, EMBEDDED_ATOM_HEADER=3,
  ORPHANED=4 and fields VALUE_DATA_DESC_IDX@16, RETIRED_EPOCH@24 **do not exist** — @16 and @24 are RESERVED / RESERVED2
  (`coalesc.cljc:43-56`).
- L114-134 fast tags — CURRENT as far as listed; **missing** `0x14` CHAR, `0x15` REF (process-private, never in a
  shared domain), `0x16` BIGINT, `0x17` RATIO, `0x18` BIGDEC, `0x19` VALUE_BLOCK (values > 64 bytes out of line),
  `0xEE` FLAT_SET, and slab type ids `0x1D` (eve array) / `0x1E` (eve obj) (`serialize.cljc:17-103`).
- L136-147 "Tags 0x10-0x13 are SAB-only and must never appear in cross-process atom serialization": contradicted by
  the code's own rule — `eve/CLAUDE.md` Forbidden Patterns §3: in cross-process atoms these tags point into mmap
  slabs; "The tag format is shared; only the backing store differs." (`serialize.cljc:60-66`: "nested collections
  stored as SAB data structures", slab-qualified offsets.)
- L174-176 keyword cache (codec/js) — CURRENT for ClojureScript only (`adapters/codec/js`).
- L193-203 bitmap node layout — same error as architecture.md L129-139.
- L240-265 native addon: lists 8 exports; the addon exports 27 (`native/mmap_cas.cc:737-765`: also `load64 store64
  cas64 add64 sub64`, `openWithFd`, the `fcntl*` Lustre set, `closeFd`, `nanosleep`).
- L279-286 worker slot layout matches `data.cljc:294-314` (24 B, heartbeat lo/hi @8/@12, 30 s) — CURRENT. L284-286
  "JVM: via ScheduledExecutorService" — F6.
- L296-305 flush: also forced by queue length > 64 (`atom.cljc:58,609-618`); a retired entry frees when its epoch is
  below the minimum pinned epoch (`safe?`, `atom.cljc:618`).
- L306-308 "Thread-Local Alloc Log (JVM) … ThreadLocal<ArrayList>": the log is a per-attempt `LoggingSlabIO`
  (`alloc/logging-sio`, `atom.cljc:658`; `LoggingSlabIO` deftype in `alloc.cljc:671`) on every host; a lost CAS frees
  `alloc/logged-allocs` (`atom.cljc:676-681`).
- L312-339 root V2 format — CURRENT (`data.cljc:280-338`; ROOT_MAGIC_V2 0x524F4F55).
- Missing: the encrypted domain (`eve.keyring`: `.keyring` record, CTR keystream, plaintext metadata), meta-atoms,
  `seal`/`version` (`atom.cljc:1000-1043`), the retire walk (`retire-set`, `atom.cljc:589` at base; since 9.31
  `src/eve/retire.cljc`).

### eve/doc/platform-support.md
Verdict: **STALE** (rewrite as the host matrix)

- L9-18 platform table: five platforms; `hosts.edn` has eleven hosts (F7). "JVM 21+ … JvmHeapRegion … mmap + Panama
  FFM" (F5). "babashka … Runs EVE's `:clj` source" — no `:clj` arms in `src/` (F7).
- L36-48, L153-164, L211-215: Panama FFM / MemorySegment / Unsafe / `LockSupport.parkNanos` / `JvmMmapRegion` /
  `JvmHeapRegion` — all false (F5, F6). The JVM flags `--add-opens`, `--enable-native-access` are only in the test
  aliases (`deps.edn` `:jvm-test` etc.), not required by the library's `:paths`.
- L64-86: "Atomic operations | sun.misc.Unsafe", "Per-thread via ConcurrentHashMap", "ScheduledExecutorService",
  "Persistent atom deref returns lazy" — F4, F5, F6. Node "Threading: Single-threaded" — `eve.thread`
  (`adapters/thread/node`: worker_threads).
- L90-100 API differences — **wrong on every row it marks unavailable**: "Close domain: Not available" on CLJS —
  `e/close-atom-domain!` is in `eve.alpha`'s api for every host (`alpha.cljc:43`); "deftype macro (CLJS only) / JVM
  Not available", "Integer map … (CLJS only) / Not available", "Sorted set (CLJS only) / Not available" — all three
  are one `.cljc` source for every host (`src/eve/deftype.cljc`, `int_map.cljc`, `rb_tree.cljc`) and run in
  `clojure -M:jvm-test` (`test/eve/deftype/int_map_test` per eve/CLAUDE.md, files under `test/eve/deftype/`).
  "AtomDomain `(eve/atom-domain val :sab-size N)`" — option ignored (F3).
- L108-112 addon exports: 3 groups of 27 (internals finding).
- L120-124 "Or manually: `cd native && node-gyp configure build`": `binding.gyp` is at `eve/binding.gyp`, not `native/`;
  `package.json:6` runs `node-gyp build` from the root. "via the `postinstall` script": it is `install`
  (`package.json:10`).
- L232-242 version matrix ("Tested: Node 18, 20, 22", "Java 21", "ClojureScript 1.12.134", "Linux kernel 2.6.28+ / 6.x"):
  UNMEASURED-HERE; nothing in the tree records those runs. shadow-cljs 3.3.6 matches `package.json`/`deps.edn`.
- Missing: the eleven hosts, their adapter roots, the thread feature per host (HOST-CONTRACT §11 table), the
  network-atom feature per host (http/crypto adapters), the browser's COOP/COEP need for `eve.thread`
  (`bb test:browser` page is cross-origin isolated).

### eve/doc/testing.md
Verdict: **STALE**

- L16 compiles `eve-test` only (needs `mmap-worker` too: `npm run test:compile`, `package.json:7`).
- L42-58 suite table: 14 suites; the runner has 45 (`eve_test_main.cljs:417-462`). Counts disagree with eve/CLAUDE.md's
  newer baseline (rb-tree 25/80 vs 28/293; int-map 22/368 vs 23/373; batch4 22/82 vs 13/34; mmap-atom 6/7 vs 7/8;
  slab 40/464 vs 47/1250) — all UNMEASURED-HERE; publish only re-measured counts.
- L58 "`all` … excluding mmap suites": CURRENT — `run-all!` lists no mmap namespace (`eve_test_main.cljs:378-411`); it
  also excludes `net`, `rrb-vector`, `thread-domain`, `thread-fold-group`, `fuzz`, `lustre`, `conformance`.
- L64-69 meta suites: `deftype` also runs `eve.deftype.type-id-test` (eve/CLAUDE.md lists type-id); unverified here.
- L97-110 JVM test files: `jvm_atom_e2e_test.clj`, `readme_example_test.clj`, `bench_test.clj` are under
  `test-e2e/eve/` and run by `clojure -M:jvm-e2e-test` (`deps.edn` `:jvm-e2e-test`, `-d test-e2e`), not by
  `:jvm-test`. The table omits the shared `.cljc` tests the JVM runs (`*_test.cljc`, `test/eve/deftype/`,
  `test/eve/net/`).
- L117-126 build targets: subset of `shadow-cljs.edn` (omits `:eve-thread-browser`, `:array-debug`,
  `:eve-alloc-race`, the `*-unified` builds, `:eve-xray`, `:xray-test-browser`).
- Missing entirely: the per-host shared-test runners (`bb test:bb test:nbb test:cljr test:lpy test:jolt test:clje
  test:vs test:glj test:browser`, `bb.edn:12-50`), the multi-process scripts (`scripts/thread-rendezvous.clj`,
  `thread-rotation.clj`, `thread-fold-rendezvous.clj`), the slab-io conformance test (`adapters/slab-io/test`), the
  thread conformance (`test/eve/thread_host_test.cljc`), the byte fixtures (`test/eve/fixtures`).
- L245-257 X-RAY: `xray-replay!` does not exist; `validate-storage-model!` lives in the unreachable `eve.shared-atom`.
- Adjacent (code, not doc): `package.json:8-9` `test:nbb`/`bench:nbb` use `--classpath src:test` without the
  `hosts.edn :nbb` adapter roots — they cannot resolve `eve.host` etc.; `bb test:nbb` is the working one (at base;
  since `52982fc60` both scripts run through bb's `test:nbb` / `bench:nbb`).
  `test/eve/runner/eve_bench_main.cljs:289` calls `a/validate-storage-model!` on `eve.atom`, which has none.

---

## Part B — the design and internal documents

### eve/adapters/README.md

Verdict: **STALE.** This is the most important repair in group B. The Features table is the union of two branches'
rows: merge `e74a02aa` (EM4 eve-types-vs, 2026-10-20) brought the ValueScript side's rows in beside the glojure side's;
`11183b104` ("adapters README composed") only removed the conflict markers left in the crypto rows. Lines 48–57 are
the glojure side (lane EVE-GLJ) and lines 58–68 the ValueScript side (lane EM4/EM6). Each row below is reconciled
against `hosts.edn`, inverted from adapter root to the hosts that list it.

| Row(s) | What the doc says | What hosts.edn and the tree say | Right row |
|---|---|---|---|
| `slab` interface, `adapters/README.md:48` and `:58` | identical | identical | either; delete one |
| `slab/heap`, `:49` vs `:59` | `:49` "JVM, babashka, ClojureCLR, basilisp, jolt, clojerl, **glojure**"; `:59` "… clojerl, **ValueScript**" | `slab/heap` is listed by jolt, bb, browser, lpy, **glj**, clje, jvm, cljr, cljs, **vs** (`hosts.edn:5-67`) | **neither**. The merged row is "JVM, babashka, ClojureCLR, basilisp, jolt, clojerl, glojure, ValueScript; ClojureScript's compiler" |
| `typed-view`, `:50` and `:62` | identical | `typed-view/js`: browser, nbb, cljs | either; delete one |
| `int32` interface and `int32/jvm`, `:51-52` and `:63-64` | identical | `int32/jvm`: jolt, bb, browser, jvm, cljs | either. Both omit the browser build's compiler, the same as "ClojureScript's compiler" |
| `int32/portable`, `:53` vs `:65` | `:53` "ClojureCLR, basilisp, clojerl, **glojure**"; `:65` "ClojureCLR, basilisp, clojerl" | lpy, glj, clje, cljr (`hosts.edn:38,42,50,66`) | **`:53`** |
| `int32/vs`, `:66` only | ValueScript | vs (`hosts.edn:59`) | `:66` (keep) |
| `codec` interface and `codec/byte-array`, `:54` vs `:67` | `:54` "… clojerl, **glojure**"; `:67` "… clojerl, **ValueScript**" | byte-array: jolt, bb, browser, lpy, glj, clje, jvm, cljr, cljs, vs | **neither**. The merged row is "JVM, babashka, ClojureCLR, basilisp, jolt, clojerl, glojure, ValueScript; ClojureScript's compiler" |
| `codec/js`, `:68` only | ClojureScript, nbb | browser, nbb, cljs | `:68` (keep) |
| `encoding/vs` **inside the codec block**, `:55` and `:57` | two separate `encoding/vs` rows (the wide surface; the network-atom subset) | one adapter, `adapters/encoding/vs/src` (vs only) | move both into the `encoding` block and merge them into one row |
| `encoding/go`, `:47` and `:56` (in the codec block) | two rows | one adapter (glj) | merge into the `:47` row |
| `process/go`, `:74` and `:76` | two rows | one adapter (glj) | merge |
| `encoding/java`, `:42` | "JVM, babashka, jolt" | jolt, bb, browser, jvm, cljs | add "ClojureScript's compiler" |
| `slab-io/protocol`, `:60` | "JVM, babashka, ClojureCLR, basilisp, jolt, clojerl, nbb, ClojureScript" | jolt, bb, browser, lpy, clje, jvm, cljr, nbb, cljs. **glj lists no slab-io root** | the row is right about the doc. The code had a defect (below; since fixed) |
| `crypto/vs`, `:108` | "the kernel's SHA-256 …; random-bytes refuses \| ValueScript" | `adapters/crypto/vs/src/eve/crypto.vs` exists but **no host lists it**. `:vs` takes `adapters/crypto/portable/src` (`hosts.edn:60`), and the comment at `hosts.edn:56-57` says "eve.crypto is the portable one … VS has no native crypto adapter" | stale. Change the row's host to "none: :vs takes crypto/portable", or wire it into `:vs` |
| `thread/none`, `:84` | "a host with no threads" | listed by no host at base. Correct as written: it is a fallback (now listed by `:bb-minimal`, not a shipped host, `bb test:bb-minimal`) | CURRENT. Note that `:vs` has **no thread root at all** (`hosts.edn:58-60`), so eve.thread does not run on VS |
| `process` interface, `:69` | lists now-ms, thread-id, sleep-ms!, start/stop-timer!, on-process-exit!, file-exists?, create-exclusive!, ensure-parent-dir!, random-bytes | `adapters/process/java/src/eve/process.clj:6-70` also defines **`identity-hash`** (`:63`) | add `identity-hash` (process/vs's row at `:75` mentions it; the interface row does not) |
| `core completion/glojure`, `:26` | names `go/host-field.patch` | `adapters/completion/glojure/go/` has `goroutine-id.patch`, `host-field.patch`, `record-factory.patch` | add the other two |

**Code defect found while reconciling (outside docs, one sentence; [static]):** `hosts.edn :glj` (`hosts.edn:64-67`)
lists no `adapters/slab-io/protocol/src`. Sixteen shared files require `eve.slab-io` (for example `src/eve/map.cljc`,
`src/eve/deftype_proto/alloc.cljc`), and nothing on glj's classpath defines it (the only definitions are under
`adapters/slab-io/{protocol,cljs-compiler,vs}`). The `:glj` row was written at `96662f619`, where `hosts.edn` had no
slab-io root at all (0 occurrences). The seam arrived with `77e4a40d2` (EM4) and the merge did not add it to `:glj`.
`bb test:glj` at HEAD most likely fails to load `eve.map`. CLAUDE.md's "149 tests, 0 failures (2026-10-20)" was
measured on the EVE-GLJ tree before that merge. The fix is likely one root added to `:glj`; it needs a run to confirm.
Fixed in `007d4edc8` (the root added, `hosts.edn:67`; RELEASE-CHECKLIST 9.1); `bb test:glj` 182 tests / 4,458
assertions, 0 failures, on the final tree (2026-10-21 ET).

---

### eve/CLAUDE.md

Verdict: **STALE** in its identity and maps. The rules, the invariants and the protected-file list are CURRENT.

- **CURRENT:** the slab header invariants. `SLAB_HDR_*` at `src/eve/deftype_proto/data.cljc:164-171` match the
  offset table exactly. `SLAB_MAGIC 0x534C4142` is at `data.cljc:174`. The class sizes `[32 64 128 256 512 1024]`
  are in `data.cljc:85` (as `[… 1024 1]`; the 7th entry is the overflow class).
- **CURRENT:** forbidden pattern 2. `serialize-flat-element` / `value->eve-bytes` occur 0 times in `src/eve/atom.cljc`.
- **CURRENT:** the protected file `src/eve/shared_atom.cljs` exists. The `.host-leak` baseline is
  `conditionals 0 / predicates 0 / host-files 1 / net-interop 0`, and `grep '#?(' src` finds 0 sites.
- **STALE, Project Identity** (`CLAUDE.md` §Project Identity): "a shared-memory persistent data structure library
  for ClojureScript", and "JVM Clojure and Node.js ClojureScript processes share…". `hosts.edn` ships 11 hosts (jvm,
  cljs, browser, bb, nbb, cljr, lpy, jolt, clje, vs, glj).
- **STALE, Namespace Map, missing shared namespaces.** 53 namespaces in `src/` are absent (census of `src/`, 0 read
  errors): `eve.alpha.{col,ds,tensor}`; `eve.dataset` (+ `.argops`, `.functional`); `eve.tensor`; `eve.sorted`;
  **`eve.keyring`** (encryption); `eve.platform`; `eve.deftype.{registry,slab-runtime,value-buffer}`;
  `eve.deftype-proto` (+ `.macros.registry`, `.proto-map`, `.sio`, `.vocab`); `eve.obj.impl` (named in prose only);
  `eve.rrb-vector.{interop,macros,nodes,protocols,rrbt,transients,trees}`; `eve.thread.{future,pool}` (named only
  as "`.future` / `.pool`"); and **every `eve.net.*` except the gateway**: aes, **atom** (the network atom itself),
  auth, blobs, bn254, bytes, codec, crypto, ed25519, graft, json, merkle, platform, portable-crypto, poseidon2, run,
  sha256, sha512, snapshot, u64, wire, xxh3.
- **STALE, Namespace Map, missing adapter interface namespaces.** `eve.host`, `eve.host-completion`,
  `eve.region`, `eve.encoding`, `eve.process`, `eve.types`, `eve.crypto`, `eve.http` (each is defined under
  `adapters/<feature>/*/src/eve/`). Every row the map does list points at a namespace that exists.
- **STALE, Build Commands suite list** ("Suites: all, core, slab, epoch-gc, …, deftype, validation"). Every listed
  suite is registered (`test/eve/runner/eve_test_main.cljs:415-462`). Registered suites the list leaves out:
  conformance, fuzz, fixtures, em5, hamt-util, lustre, lustre-bench, dataset, array-hosts, tensor, rrb-vector,
  columnar-bench, columnar-mmap, columnar-inmem, columnar-one, perf-audit, repro, slab-leak, net, thread-host, thread,
  thread-meta, thread-domain, thread-fold, thread-fold-group. The Green Baseline block covers the dataset, tensor,
  array-hosts, rrb-vector, net and thread-* suites. These are named nowhere in the file: conformance, fuzz, fixtures,
  em5, hamt-util, lustre, lustre-bench, the four columnar suites, perf-audit, repro, slab-leak. `repro`,
  `columnar-one`, `columnar-mmap` and `columnar-inmem` are registered but missing from the help list `suite-names`
  (`:473-478`).
- **STALE, `bb test:jolt` comment:** "`jolt -Scp <abs src>:<abs test>`". `bb.edn:39` composes `(roots :jolt "test")`,
  which is src plus the `:jolt` adapter roots plus test.
- **STALE, Green Baseline figures.** These are dated measurements, for example `bb test:glj` "149 tests … 2026-10-20".
  They cannot be verified statically, and the glj figure predates the slab-io defect above. They are fine as a
  working log, but the release README must not republish them.
- Out of scope, one sentence: `package.json:8` `test:nbb` runs `nbb --classpath src:test`, with no adapter roots.
  It cannot load `eve.host`; `bb test:nbb` (`bb.edn:17`) is the working form (at base; since `52982fc60`
  `package.json:22` is `bb test:nbb`).

---

### eve/impls/README.md

Verdict: **STALE** in its implementations table. The folders table is CURRENT.

- **CURRENT, folders table:** every file named exists. The pod has ops `open load32 store32 add32 cas32`
  (`impls/bb/eve_atomics_pod/main.go`); glojure has `build.sh`, `region/`, `host/`, `types/`, `cmd/glj-eve`; let-go
  has `evenative/` and `proposal-let-go.patch`; jank has `src/eve/{region,types}.jank`; phel has
  `src/EveTypes.php`, `composer.json`, `bootstrap.php`. "`eve.atom` does not call it [the pod]" holds: no reference to
  the pod in `src`, `adapters` or `bb.edn`. Minor: the glojure row names two patches; three ship
  (`adapters/completion/glojure/go/*.patch`).
- **STALE, ClojureScript / JVM / ClojureCLR / basilisp / jolt / clojerl rows:** "the `.cljs` files and the `:cljs`
  arms of `.cljc`", "the `:clj` arms", "`:cljr` arms", "`:lpy` arms", "`:jolt` arms placed before `:clj`", "`:clje`
  arms". `src/` has **0** reader conditionals (`grep '#?(' src` → 0; `.host-leak` `conditionals 0`). Every host is
  `src/` plus its `hosts.edn` adapter roots.
- **STALE, basilisp row:** "`src/eve/lpy_host.lpy`". No such file exists. The completion is
  `adapters/completion/basilisp/src/eve/host_completion.lpy`.
- **STALE, clojerl row:** "`src/eve/clje_nif.clje`, and the NIF `native/eve_clje_nif.c`". The actual paths are
  `adapters/core/beam/src/eve/clje_nif.clje` and `adapters/core/beam/native/eve_clje_nif.c`; `eve/native/` holds only
  `mmap_cas.cc`. The NIF builds into the adapter's own `target/clje/` (`clje_nif.clje:12,52`), not `eve/target/clje/`.
- **STALE, ClojureScript row:** "**primary**: the reference every other implementation is checked against". The
  references now are the byte fixtures and `crypto/portable` (`test/eve/eve_bytes_fixture_test`,
  `test/eve/net/crypto_test.cljc`). This is a framing call, not a factual error; reword it.
- **Ambiguous:** "a writer in the coop capstone (`test/coop-atom`)". That path is `atom.broker/test/coop-atom`
  (exists) and not `eve/test/coop-atom` (absent). Make the path repo-qualified.

---

### eve/doc/unification/HOST-CONTRACT.md

Verdict: **STALE** in its references and in parts of §5–§7. §10–§12 are CURRENT. It is still the living port
contract, so it needs a refresh rather than an archive.

- **Line references pinned to BASE `b24c8c034`.** Of 12 sampled, **none** still points at the named thing at the
  cited line:
  1. `alloc.cljc:565` `init-jvm-heap-slab!` → now `src/eve/deftype_proto/alloc.cljc:591`.
  2. `atom.cljc:1435` `jvm-open-heap-domain!` → renamed `open-heap-domain!`, `src/eve/atom.cljc:365` (`:400` on the
     final tree).
  3. `atom.cljc:334` `cljs-open-sab-domain!` → no longer exists.
  4. `alloc.cljc:978` `init-slab!` → `adapters/slab/module/src/eve/process_slab.cljs:122` (`:123` on the final tree).
  5. `alloc.cljc:1793` `init-sab-coalesc!` → `process_slab.cljs:1123` (`:1197` on the final tree).
  6. `mem.cljc:376/:585/:1015` `open-mmap-region` per host → `mem.cljc` is now 259 lines. The openers live in the
     region adapters (for example `adapters/region/jvm-nio-lock/src/eve/region.clj:169`,
     `adapters/region/ctypes/src/eve/region.lpy:102`).
  7. `mem.cljc:1370–1399` bitmap alloc/free → past the end of the file.
  8. `alloc.cljc:48` `decode-class-idx` → `alloc.cljc:75`.
  9. `alloc.cljc:46` `NIL_OFFSET` → `alloc.cljc:67`.
  10. `atom.cljc:72` claimed sentinel → `atom.cljc:64` `CLAIMED_SENTINEL` (`:65` on the final tree).
  11. `atom.cljc:1254` FNV name hash → `atom.cljc:886` `fnv-1a-hash` (`:815` on the final tree).
  12. `hamt_util.cljc:22–30` Murmur3 → `hamt_util.cljc:27` `portable-hash-bytes`.

  Fix: re-cite §1–§8 to names (`ns/var`) rather than line numbers, or banner them "line numbers at BASE".
- **STALE, §5 and §9 step 3:** "`schedule-periodic! [f period-ms]`, `cancel! handle`" and "`current-thread-id`". The
  interface is `start-timer!`/`stop-timer!` and `thread-id` (`adapters/process/java/src/eve/process.clj:9,19,35`).
  §5 also omits `ensure-parent-dir!` (`:54`), `identity-hash` (`:63`) and `random-bytes` (`:70`).
- **STALE, §6 and §7, bb as a "materializing host":** "a host without macros (SCI/bb-like) … cannot host the lazy
  slab-backed types", and bb "B. Materializing". bb now realizes eve types through `adapters/types/bb` (sci deftype
  onto eve.core's protocols). bb's runner runs `eve.deftype.int-map-test`, `eve.deftype.rb-tree-test` and
  `eve.rrb-vector-test` (`test/eve/runner/bb_main.clj`). bb's region is lock-only (`region/jvm-nio-lock`), which is
  the §7 "B with the fcntl provider" path. §7 says its "Hosts at BASE" column is history, but there is **no current
  level table for the 11 hosts anywhere**; see MISSING.
- **Minor, §10:** the table omits `extend-default`, which `adapters/core/clojure/src/eve/host.cljc:63-71` defines and
  `adapters/README.md:19` lists.
- **CURRENT, §10 names:** they match `eve.host/names` (`host.cljc:22-29`). glojure's completion route is right
  (`hosts.edn:65` takes `core/clojure` plus `completion/glojure`).
- **CURRENT, §11:** the 14 thread functions match `adapters/thread/jvm/src/eve/thread_host.clj`. The 8 adapters match
  the `adapters/thread/` dirs.
- **CURRENT, §12:** all 28 `eve.slab-io` names match the macros in
  `adapters/slab-io/protocol/src/eve/slab_io.cljc`. The conformance test is `adapters/slab-io/test`.
- **CURRENT, §8 defects (spot):** 8.10 (symbol long form refused) agrees with FORMAT-DECISIONS §4 and the codec.
  8.15 (CLJS slab context = module) still holds: `adapters/slab/module` registers through
  `alloc/register-host-slab!`.

### eve/doc/unification/SEAM-MAP.md

Verdict: **ARCHIVE.** It is the leak register `L-01…L-58` of BASE `b24c8c034` and a historical record. Its headline
"Reader-conditional sites … **371**" is now 0 (`grep '#?(' src`), and its line numbers are BASE's. The `L-nn` ids are
still cited by HOST-CONTRACT §8, so keep the file. Add a banner: "history at BASE; the leak gate is
`scripts/check-host-leak` + `.host-leak`".

### eve/doc/unification/CANDIDATE-VERDICTS.md

Verdict: **ARCHIVE.** Unit 3's keep/modify/replace verdicts on the September candidate, against BASE line numbers
(for example "alloc.cljc:102/:256/:1483"; `alloc.cljc` was 940 lines at base, 990 on the final tree). It is the
rationale record for the VarHandle region and the unaligned access rule (HOST-CONTRACT §2.1 ★), so keep it with a banner.

### eve/doc/unification/UNIFICATION-REPORT.md

Verdict: **ARCHIVE.** The Unit 6 report of 2026-10-15, 9:00 PM ET. Its scoreboard ("JVM 215 tests", "CLJS 359
tests", "bb … loads") is that day's. CLAUDE.md now claims 489 JVM and 460 CLJS tests. Its §8 "Porting to host #4" is
superseded by HOST-CONTRACT §9 and the hosts that have shipped since. Keep it as history and banner it.

### eve/doc/unification/PORT-TARGETS.md

Verdict: **ARCHIVE.** A measured inventory of Clojure-likes (2026-10-15/16). The probes and evidence are only in git at
`1fb9cbca`, as its header says. Its ranking's conclusion, "The next hosts are glojure, cljr and basilisp"
(`PORT-TARGETS.md:328`), is done: all three are in `hosts.edn` (`:glj :cljr :lpy`), as are jolt, clojerl and VS. §3.1's
scorecard is still the best evidence for hosts *not yet* adopted (let-go, squint, jank, phel). Keep it; add a banner
pointing at `hosts.edn` for the current host set.

### eve/doc/unification/FORMAT-DECISIONS.md

Verdict: **CURRENT** for what it covers. It is the status authority for the bytes.

- Verified against `src/eve/deftype_proto/serialize.cljc`:
  - §2's flat collections: `FLAT_MAP 0xED`, `FLAT_SET 0xEE`, `FLAT_VEC 0xEF` (`:89-91`).
  - §4's scalars: false/true `0x01/0x02` (`:17-18`), `INT32 0x03`, `FLOAT64 0x04`, `INT64 0x0F` (`:19,20,31`),
    strings/keywords `0x05–0x0A`, UUID `0x0B`, symbols `0x0C/0x0D`, DATE `0x0E`, CHAR `0x14` (`:21-34`).
  - §8's BIGINT/RATIO/BIGDEC `0x16/0x17/0x18` (`:44-46`).
  - §9's REF `0x15` (`:52`) and process-private kinds from `0xF0` (`:241`).
  - §10's VALUE_BLOCK `0x19` (`:57`).
- Root header: `ROOT_HOLD_OFFSET 24` and `ROOT_INFLIGHT_OFFSET 28` (`src/eve/deftype_proto/data.cljc:364-365`).
  `.root` 8320 bytes and `.rmap` 262144 bytes (`src/eve/atom.cljc:52-53`).
- **Gap, not an error:** no complete tag table. These tags are absent from the doc: `RECORD 0x1A` (`:70`),
  `TYPED_ARRAY 0x1B` (`:74`), the SAB pointer tags `0x10–0x13` (`:63-66`; only mentioned), and the slab type ids
  `0x1D` EveArray / `0x1E` Obj (`:99,103`). A release needs one wire-format page (MISSING).

---

### eve/doc/network-atoms/WIRE.md

Verdict: **STALE** in one claim. Otherwise CURRENT as a living spec.

- **CURRENT:** the routes the eve client speaks match `src/eve/net/wire.cljc`: create `:33`, deref `:41`, cas-root
  `:55`, release-reader `:75`, renew-lease `:80`, presign `:92`, all under `/v1/domains/:d/…` (`:11-12`). The
  client has no `credentials` or `watch` route, which matches §6 (the watch is not built) and ADAPTERS §4.5.
- **STALE:** "Executable form: … and `eve/test/eve/fixtures/wire.edn`" (`WIRE.md:11`). No such file exists.
  `test/eve/fixtures/` holds `aegis-kernel-vectors.edn`, `eve-bytes.edn`, `vsv2-blocks*.edn`,
  `vsv2-landing-a.edn`, `vsv2-sealed-v2.edn` and `xxh3-vectors.edn`. IMPL-PLAN `:570` planned it. The VS form
  `vs/test/vs/net_wire_fixtures.vs` exists.

### eve/doc/network-atoms/ADAPTERS.md

Verdict: **STALE** in its status sections. §2–§5 are CURRENT design rationale.

- **CURRENT:** §2's minimal network set (http, clock/sleep, random-bytes, utf16-units); §3's measured portable-vs-native
  crypto table (a dated measurement on bb); §4.1's crypto feature shape; §5's open defects. `vs.crypto` still has no
  Ed25519: `vs/src/vs/crypto.vs` has 0 occurrences of `ed25519`.
- **STALE, §4.3:** "glojure … is not in hosts.edn yet; it needs the region, types and http adapters to join". glojure
  is `hosts.edn:64-67`, with `http/go`, `region/glojure` and `types/glojure`.
- **STALE, §1 table:** http "not done: ClojureCLR, clojerl, jolt, glojure, the browser". `http/clr`, `http/beam` and
  `http/go` exist and are wired (`hosts.edn:36,48,64`). At base only **jolt and the browser** had no http root
  (`hosts.edn:21-27,44-47`), so neither could open a network atom; the browser's (`http/browser`, `hosts.edn:23`)
  landed in `4bcd756cc` — jolt alone has none.
- **STALE, §7 build order:** item 3 (cljr, clojerl, glojure http and crypto) is done. Item 4 (the browser) is done
  since (`4bcd756cc`); item 6 (`ws`) remains open.
- Its §6 "264 method calls in 20 files" outside eve.net is a dated count. A rough re-count of `(.method` in `src` outside
  `src/eve/net` gives 259 in 19 files: same order, not gated.

### eve/doc/network-atoms/SCOPE.md

Verdict: **ARCHIVE.** "Scope (revision 2)", 2026-10-16. A lane plan whose subjects (VSv1 → VSv2, the simulator, the
phased plan §8) are now built (`src/eve/net/*`) or tracked elsewhere. It records why revision 1 was wrong (§0) and the
VSv1 reference (§1), which is rationale worth keeping. Banner it as history.

### eve/doc/network-atoms/IMPL-PLAN.md

Verdict: **ARCHIVE.** The lane's build contract (Phases 0–5, amendments A1…). Phases 0–4 shipped. Phase 5 (watch,
remaining hosts) is partly done (hosts) and partly not (watch). Most of it is about the VS broker and kernel (`vs/`,
`services/atom-broker`), not eve. Banner it. It is not a release doc.

### eve/doc/network-atoms/reports/phase-0.md · phase-1.md · phase-2.md · phase-3.md

Verdict: **ARCHIVE** (or move out of `eve/doc`). Lane reports of 2026-10-16/17 whose acceptance rows are VS and broker
commands (`bin/vs test …`, `bash test/broker/run …`, `python3 test-hosts/workerd/…`, kernel builds). They describe
atom.broker work, not eve's surface. Phase-3 is headed "(in progress)". For a public eve release they belong in
atom.broker's docs, or in git history with a pointer.

### eve/doc/network-atoms/reports/phase-4.md

Verdict: **ARCHIVE.** "Phase 4 report — the EVE network domain, JVM then bb (in progress)", 2026-10-17. It carries
design rulings that are still binding: "Synchronous on every host", eve.net as eve's adapters, and the cross-host
adapter set. Move those rulings into a current network-atom design page (MISSING) and banner this report as history.

---

### eve/bench/legacy/contention/runs/**/*.md (5 files)

Verdict: **ARCHIVE** (exclude from any package; never cite). The five files:

- `2026-02-11T203437_8029674/eve-vs-cljs.md`
- `2026-02-11T204150_8029674/jvm-clj.md`
- `2026-02-12T020215_69a6b0f/old-sab-vs-slab.md`
- `2026-02-12T055241_eac80ed/eve-vs-cljs.md`
- `2026-02-13T230811_a94c92e/jvm-clj.md`

They are raw benchmark output from February 2026, before 0.1.0, made at standalone-repo commits that are not in this
tree's history. One is marked "Dirty: yes", on "Node.js v22.22.0", with "SAB size 512 MB (legacy)". They measure a
system that predates the slab-io seam, the codec unification and the adapter split. They are already under `legacy/`;
the README's performance tables must not be sourced from them. If space matters, DELETE them; their git history
remains.

### eve/.claude/bundled/clojure-mcp-light/** (README, CHANGELOG, CLAUDE.md, GEMINI.md, LICENSE.md, commands/, skills/, test/README.md)

Verdict: **not eve's docs; exclude from the release.** It is a vendored copy of Bruce Hauman's `clojure-mcp-light`
(`README.md:61,115`: `github.com/bhauman/clojure-mcp-light`, tag v0.2.0), used as editor/agent tooling.
**License: Eclipse Public License 2.0** (`LICENSE.md:1`), while eve is MIT (`eve/LICENSE:1`, "Copyright (c) 2022 John
Newman"). It must not ship inside an MIT package without its license and notice; better, keep it out of published
artifacts. Related stale tooling config: `eve/.mcp.json` and `eve/cclsp.json` point at `/home/user/eve` (the
standalone checkout path), not this tree. Fix or drop them before 0.9.

---

### Memory model: eve/doc against the two authorities

The authorities are `/home/user/atom.broker/vs/docs/memory-model.md` and
`/home/user/atom.broker/vs/docs/contention-domains.md`. `memory-model.md` itself names eve's
`architecture.md` "Epoch-Based Garbage Collection" and `internals.md` "Epoch GC Details" as its sources. Group B docs
make **no** statement that conflicts with them. HOST-CONTRACT §1.1's "zero-initialised" is about **fresh** regions
and fresh bitmaps, not reuse, so it agrees with "nothing is zeroed [on free]". Its §5 "mutable queue / cell for the
retire queue" is the model's retire queue.

The conflicts sit in group A docs, and in code the docs accurately describe. Each is listed for the docs-A owner:

(At base `112f6ced2`. Now: RELEASE-CHECKLIST §10 — 10.2 closed by 9.31 (`a3693674c`, `2397b2d6d`, `f6bb7f1a3`,
`38b96e21b`): the walk is `src/eve/retire.cljc`, counts in `src/eve/tally.cljc`, no reachability set remains; 10.5
repaired in `007d4edc8`; 10.6, the tally, added; 10.1, 10.3 and 10.4 still hold — the flush at `atom.cljc:520-534`,
`mmap-min-safe-epoch` at `:161`, `pin-epoch!` at `:424-431`.)

1. **The flush margin.** The authority (model item 4) frees "entries stamped below the minimum pinned epoch − 2". eve
   frees `epoch < min-safe-epoch` with no margin (`src/eve/atom.cljc:618-619` `try-flush-retires!`), and
   `doc/internals.md` "Flush Protocol" step 4 says the same.
2. **A reachability set.** The authority says "What the model does not contain: a reachability scan … over a whole
   value (old, new …)". The code at base built one: for orphaned nested collections,
   `(set (value-reach sio new-map new-hdr))` (`atom.cljc:583`). On a kind change, `retire-set`'s `:else` arm
   computes `(set (value-reach sio new-val new-ptr))` and removes it from the old value's reach (`atom.cljc:605-607`).
   `value-reach` enumerates every nested collection block (`:543-548`). `doc/internals.md:295` describes it as "old
   nodes that are no longer reachable from the new root". This is a code divergence from the authority, flagged and
   not judged here. Closed by 9.31 (RELEASE-CHECKLIST 10.2): `value-reach` and `retire-set` no longer exist.
3. **Scratch buffers.** The authority's "does not contain" list includes "an arena, a scratch buffer". eve's JS codec
   keeps reusable scratch buffers (`adapters/codec/js/src/eve/codec.cljs:5,27-34`), and the docs say so
   (`doc/internals.md:175` "Two reusable scratch buffers (A/B)", `doc/data-structures.md:564-572` "Per-worker WASM
   scratch … Scratch pool 1 MB", `adapters/README.md:68`).
4. **Stale description of the allocation log.** `doc/internals.md` "Thread-Local Alloc Log (JVM)" said at base
   "`ThreadLocal<ArrayList>`". The code is the portable `LoggingSlabIO` ("No thread-local",
   `src/eve/deftype_proto/alloc.cljc:663-668`), which is exactly what the authority's item 5 cites. Repaired in
   `007d4edc8` (RELEASE-CHECKLIST 10.5).
5. **Tensions with the operator's lane rule** ("no liveness scans, no counters"), reported and not resolved. eve's
   `mmap-min-safe-epoch` scans 256 worker slots and skips those with a stale heartbeat (`atom.cljc:160-172`;
   `doc/architecture.md:71,168`). The slab header carries an atomic free-block counter
   (`data.cljc:167 SLAB_HDR_FREE_COUNT`). `pin-epoch!` keeps a per-thread nesting depth (`atom.cljc:389-396`).
   contention-domains.md's "No counters (D2)" names header counts. These are eve's shipped design, documented as such;
   whether the 0.9 docs should describe them as the model or as a divergence is the operator's call.

---

## MISSING — what the release needs that does not exist (merged from both parts)

| # | Missing document | Covers | Built by this lane? |
|---|---|---|---|
| M1 | A real README | the current system, hosts, tiers, domains, network atoms, impls; no unmeasured number | yes (Phase 2) |
| M2 | CHANGELOG 0.9 entry | 0.1.0 → 0.9, from git history since the import | yes (Phase 2) |
| M3 | `doc/adopting-eve-minimal.md`, `doc/adopting-eve-fast.md` | implementing eve for a language; the fast seams | yes (Phase 2) |
| M4 | Atom domains / atom API reference | `eve.atom`'s 49 public vars: domains as atoms, member atoms as slots, `persistent-atom-domain` options, `meta-atom`, `seal`, `version`, `$`, create-vs-join, 255 names per domain | yes (Phase 2: `doc/atom-domains.md`) |
| M5 | Network atoms user guide | `eve.net.atom` config, synchronous deref/swap!, broker/gateway, blobs direct to a presigned store, sign-in | yes (Phase 2: `doc/network-atoms/README.md`) |
| M6 | SECURITY.md | plaintext defaults, the key ring's modes and limits, deterministic blob encryption, portable crypto not constant-time, entropy on VS, disclosure | yes (Phase 2) |
| M7 | Executor service guide (`eve.thread*`) | pools, `future`/`pmap`/`fold`/`=>>`, domain cells, rungs, per-host `driving` | partly: `doc/api-guide.md` §eve.thread (pools, futures, `pmap`, `fold`, `=>>`); domain cells and rungs open |
| M8 | `eve.sorted` reference | sorted maps/sets with comparators, `subseq`, `rank` | yes (`doc/api-guide.md` §eve.sorted; `doc/collections.md` "Sorted Maps and Sets With Comparators") |
| M9 | Host matrix | one current table: host, roots, tier, threads/http/typed arrays, runner command | yes (README + ADOPTER-TIERS §5.1) |
| M10 | Memory-model page for eve | the model as the authorities state it; eve's divergences listed as divergences | yes (Phase 2: `doc/memory-model.md`) |
| M11 | Complete wire-format reference | every tag (0x01–0x1C, 0xED–0xEF, 0xF0+), type ids 0x1D/0x1E, slab and root layouts | yes (`doc/internals.md` "Serialization Format") |
| M12 | NOTICE + EPL text | the 8 EPL-1.0 rrb-vector files, crypto provenance | yes (Phase 2) |
| M13 | UPGRADING from 0.1.0 | `eve.shared-atom` → `eve.atom`, watches gone, `atom-domain` options gone, Panama gone | folded into the CHANGELOG 0.9 entry's "Removed"/"Changed" |
| M14 | An archive convention | banners on history documents | yes (Phase 2: banners) |
| M15 | Examples | in-memory domain, mmap domain across processes, network atom, a non-JVM host | yes (Phase 2) |
