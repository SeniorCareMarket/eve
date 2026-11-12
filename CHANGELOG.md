# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.9.3.2] - 2026-11-12

- **DataScript on Eve is consumed from its own repository.** The library is [`eve.db.ds`](https://github.com/johnmn3/eve.db.ds) (namespaces `eve.db.ds.*`, require `eve.db.ds.core`, reader tags `#eve.db.ds/DB` and `#eve.db.ds/Datom`); it pulls Eve in transitively. The in-tree `contract-test/lib-ds` copy under `datascript.*` is a test-only fixture for the contract drivers. See the README and `docs/datascript-eve/GUIDE.md`.
- Fixed (babashka): `clojure.core` fns that walk a collection argument natively (`zipmap`, `set`, `apply`, `every?`, `not-every?`, `some`, `not-any?`, `partition`, `partition-all`, `partition-by`, `interleave`, `frequencies`, `reverse`) now accept Eve collections; they raised "Don't know how to create ISeq from: sci.impl.deftype.SciType" (eve.db.ds's `d/q` failed in `zipmap` on the parser's `:in` bindings). `nth` and `count` on an Eve vector or set stay the seat's named limit.
- #56: on babashka, an Eve MapEntry (eve.map-entry/MapEntry) is presented as a host `clojure.lang.MapEntry` of key/val with the Eve value kept behind it; the byte-array codec writes the Eve value behind a presented entry (`eve.deftype-proto.serialize/presentation-backing`, nil on other hosts), so it round-trips as an Eve MapEntry.
- #56: eve.thread-pending-test runs the future's work as a registered task (`deftask`), since nbb pool threads do not share the heap.
- #56: `clojure.core/realized?` works on eve.thread promise and future (they implement IPending).
- #56: fix a JVM `lookup-ns?` NPE and an unresolved `satisfies?` on jank in eve.atom.
- #56: the JS encoding adapter grows `unsupported` (it had only no-arg `throw-unsupported`), so eve.db.ds FilteredDB can analyze on nbb.
- #56: the CLJS/nbb module process slab initializes itself on first `cljs-sio` or `alloc`. Without eve.alpha or an explicit `init!`, `alloc-from-slab` was reading a nil `:region` and `mem/load-i32` found no IMemRegion implementation.
- #56: on babashka, process-local map types that name `IPersistentMap` stay in SciMap mode (keeping their `IPersistentMap` methods and `Iterable`), and `lookup-ns?` carves out `eve.db.ds.*` so library keyword/`get` lookup is presented as `eve.core/get`.
- #56: Basilisp byte-array size/scalar initialization fills signed bytes instead of trying to iterate the scalar (shared host byte-storage regression).
- #56: add `eve.map-entry/map-entry`, a single slab-backed Eve MapEntry with Clojure map-entry, indexed, sequence, equality and hash semantics.
- #56: translate `IMapEntry` key/val into JavaScript protocols, give Eve MapEntry Clojure's vector face for native map conj, and initialize the slab adapter on standalone require.
- #56: realize `eve.host/byte-array` in signed JavaScript byte storage on interpreted JS hosts, including size/sequence initialization; JVM behavior is unchanged.
- #404: add an isolated bb/bb-minimal regression for `eve.core/nth` bootstrap (the existing E1 completion fix is present on this release).
- Fixed: on babashka, an `eve.record/defrecord` record answers `clojure.core/get` and keyword lookup (`(:x r)`, `(get r :x)`). It was a plain `SciType` whose `ILookup`/`IPersistentMap` interfaces were translated onto eve.core's protocols only, which clojure.core's fns cannot see, so both returned nil while `eve.core/get` returned the value; a record now keeps its map interfaces as written (SciMap mode), like a process-local record-like type. Eve's own map collections (int-map, sorted map), which name `IPersistentMap` and stay excluded, are unchanged. JVM behavior is unchanged.

### Fixed
- **clojerl STM writes use Eve atom dispatch.** `eve.stm` called native core `swap!` on an Eve ref cell, failing in `clojerl.Atom:swap/2` with `:function_clause`. It now imports the existing `eve.host` atom operations (also covering seated clocks/locks). Verified on the original S3 probe: write and committed read both return 42; the write/abort regression passes on clojerl, JVM and Babashka (2 tests / 14 assertions), and JVM portable STM passes all 15 checks. See `doc/stm-clojerl-write-fix.md`.
- **nbb can load the bulk-fill default in `eve.mem`.** The new i32 pattern builder used `byte-array` / `unchecked-byte`, which SCI JavaScript does not provide. Its JS arm now uses `Uint8Array` and masked octets; other hosts retain signed byte arrays. Verified: nbb 1.6.214, Babashka 1.13.226 and JVM 21 each pass the default-fill regression (1 test / 108 assertions, chunk boundaries and signed patterns); nbb release one-datum returns `ONE-DONE`, 2 datoms.
- **`eve.tally` loads on clojerl.** `eve.hamt-util/popcount32`'s `^long` return tag is resolved by clojerl in the caller's namespace, so `(require 'eve.tally)` failed with "Unable to resolve symbol: long" at tally.cljc:190. `eve.tally` now takes `eve.host`'s `long` under `:clje`. Source: `clje-tally-long` dab410673579c8abf9d4eab90baafa3d28f7f2bc. (Verified on the JVM suite only; the clojerl load check is a separate run.)
- **`eve.atom` loads on clojerl.** The same `^long`-return-tag-in-the-caller's-namespace failure (see the `eve.tally` entry) also stopped `eve.vec` (vec.cljc:77, `alloc/node-i32`), `eve.list` and `eve.retire` (retire.cljc:41, `hamt-util/popcount32`); each now takes `eve.host`'s `long` under `:clje` (other hosts unchanged). Source: `clje-long-sweep` 4caf101. Verified on clojerl (OTP 27.3.4.9, clojerl 4ad14e5, tools/install-clojerl.sh on branch `clje-toolchain`): `(require 'eve.tally)` and `(require 'eve.atom)` load.
- **`eve.dataset` loads on clojerl.** `eve.dataset`, `.argops`, `.functional`, `eve.alpha.ds` and `eve.alpha.col` failed to load: `assoc!` unresolved at dataset/argops.cljc:202 (now `eve.host`'s under `:clje`) and `##Inf` at dataset/functional.cljc:34 (`:clje` spells `2147483647`, the value the callers' `(int (min ...))` saturates `##Inf` to on the JVM). Source: `clje-dataset-fixes` 4dadcf3c. Proof: on the release tree, `(require ...)` of each of the five namespaces plus `eve.atom`, `eve.tally`, `eve.vec`, `eve.list`, `eve.retire` returns rc=0 on clojerl 4ad14e5 / OTP 27.3.4.9 (:clje arms only; the JVM arms are untouched).
- **The ants demo (`docs/stm-ants/demo/run.cljc`) is disk-safe by default.** It wrote a world to disk (about 10 GB at dim 60, one Eve domain per board cell) and never deleted it or checked space: an operator's disk filled. Now it refuses to start below `--min-free-gb` free (default 15 GB at dim >= 60, else 5), a 5 s watchdog aborts below `--disk-floor-gb` (default 3: kills every seat's process tree, prints `DISK-FLOOR abort <free>`, deletes the world, exits 4), the world is deleted when the run ends by any route unless `--keep-run`, and any log over `--log-cap-mb` (50) is trimmed. Source: lane-m/stm-standing c7bb992c. Proof: on babashka 1.13.226, a preflight refusal (rc=2) and a forced floor trip with a 21 MB world and a live child (world size printed, world deleted, child killed, rc=3/4) were run on a stand-in root; the full launcher against a real driver was not run (no Clojure CLI on that box).
- **basilisp: `eve.host-completion` carries `sorted-set-by`, `comparator`, `array-map`, `transient`/`persistent!` duck-typing and the lpy lane's other completion deltas.** basilisp could not resolve `clojure.core/sorted-set-by`, which walled the DataScript one-datum run. Source: atom.broker lane-m/perf-doc-0931-lpyfix 5d1775f1 (`host_completion.lpy` only; byte-identical at the proven tip 01c42450). Proof: vm RESULT #55 (2026-11-10 20:35 ET), lpy one-datum 5/5 ONE-DONE on basilisp 0.5.1 (median 25,071 ms warm, 132,023 ms cold); and `(require 'eve.atom)` on the release tree with this file loads rc=0 with `sorted-set-by` resolved.
- **`eve.mem` bulk extend-default fill.** The fill is chunked through `-write-bytes!`. Source: `eve-bulk-fill-fix` f3a1e76d32e1ae8cd0896a52d879c5656831178b (record: BULK-FILL-FIX-REPORT.md).
### Added
- **`eve.aegis.chat-spec`** (`test/eve/aegis/chat_spec.cljc`): the self-contained data namespace the aegis chat's JVM suite requires, absent from 0.9.3.1. Source: `aegis-chat-spec` 032a6f1d23700ab69b624eab0cc00bbde612cf78 (sha256 3cb414f8d8362a61c25da0185033553adbeb0e427eadddeb0a13141d453bb823).

## [0.9.3.1] - 2026-11-09

### Added

- **STM in Eve (`eve.stm`).** Software transactional memory implemented in Eve, on Eve atoms, with no host STM beneath: each ref's cell is the sole atom of its own Eve atom domain — the ref's domain is its contention domain — and a transaction reads each ref's value as of one commit point from its per-ref versioned history, then commits by per-ref locks, validation, and one CAS per written ref. The `dosync` surface is stock Clojure's (`make-ref`, `alter*`, `commute*`, `ensure*`, watches and validators on refs); contention domains are tiered (a ref's domain at the memory tier, a process at the disk tier, a whole server at the network tier), and the disk-tier seating (`seat-clock!` + `make-ref`'s `:seat`) lets two processes transact against the same refs. Ordinary retries are unbounded; basis faults are bounded at 10,000. Full page: [doc/stm.md](doc/stm.md). **Encoding note:** this release ships the OLD history encoding. The accepted flat encoding is not in this release (it lives on a development branch, pending a ruling); the two encodings do not read each other, and seated worlds must be re-created when the encoding changes — there is no in-place migration.
- **Segmentation: domains past the 2 GiB ceiling.** A single `FileChannel.map` cannot map past 2^31 bytes, which stranded domains at ~2.1 GB. The JVM seat's region is now segmented (segmented NIO): a segment table in the region, growth maps only the delta, and a stranded domain re-opens in place. `:segment-bytes` (a power of two, 256 MiB by default) is an open-time parameter of `persistent-atom-domain` — presentation, not format: it is stored nowhere in the domain's files, and processes may join the same domain with different windows.
- **The DataScript port is in the release tree.** Upstream DataScript 1.8.1 ported onto Eve's value substrate lives at `contract-test/lib-ds/` (with the sorted-set port at `contract-test/lib/`), drivers under `contract-test/drivers/`; guide and its own changelog under `docs/datascript-eve/`.
- **Journal adapters.** The disk tier's journal (`eve.journal-file`) has an adapter on the JVM, babashka, jolt, glojure (new in this release), nbb/ClojureScript (node), clojerl (BEAM), basilisp (python) and ClojureCLR, with jank's adapter in tree (its Mac proof outstanding). ValueScript is the only host with no journal adapter. The matrix is in [doc/platform-support.md](doc/platform-support.md).

### Changed

- **The disk tier is journaled by default, and domains take a `:mem-budget`.** Tier repair: a disk domain carries its journal unless the opener opts out (`:journal? false` keeps durable commits without the journal file; `:durable? false` is the non-durable comms form). `:mem-budget` (bytes) caps a domain's total region growth — refused growth names the budget, never the host's out-of-memory; the `MEM-BUDGET-*` constants are the per-target defaults (desktop 2 GB, browser 1 GB, workerd 48 MB), developer-settable, never compiled-in ceilings.
- **Checked arithmetic is landed and documented.** Checked forms throw on overflow using each host's native overflow machinery, primed forms promote, unchecked forms wrap; the semantics are stated in [doc/arithmetic-overflow.md](doc/arithmetic-overflow.md).

### Fixed

- **DS11 tally: the void is closed, and single-swap writes are cheaper.** The tally-write-fix audit passes with zero live undercounts at every stage. The single-swap write regression (E2) is reduced, not closed: on fresh domains a commit now costs 1.24–1.56× the no-tally arm (add-1 1.51×, add-5 1.24×, retract-5 1.56×), from 2.0–2.4× — the retire flush no longer forces the whole domain a second time per commit, and the counting walk is 0.45 ms of a commit (was 2.22 ms). The remainder is the journal forcing the count pages a commit's holds touched: the price of durable counts.
- **Tier warm reads (E3): two causes landed.** The key memo is a ConcurrentHashMap and keys are compared in place on mapped regions: a warm get is ≈12× a plain map get (was ≈13.4×), allocation 122.4 → 69.6 B/op. The heap-speed bar is not met; the remaining causes are priced in the release record.
- **jank: the compiled form is clean at this pin.** One AOT build: 165 files translated, 0 failures, a 16.4 MB binary; the one-datum contract passes 5/5 cold runs at a 0.317 s median with the journal engaged, fresh-process readback exact. (The take-2-era abort and descriptor-loop remainder do not reproduce at this pin.)
- **glojure: the `*compile-files*` panic is fixed.** Seven gaps in glojure v0.7.22's code generator (host-field reads, core-fn values held in vars, primitive slices, `[]any`, PersistentQueue, letfn forward references, big.Int) are patched in Eve's glojure completion; all nine deftype-family namespaces compile standalone, 3/3 runs, and Eve's glj tests stand at 24 tests / 310 assertions / 0 / 0. The window-shape `RTEvalError` is also fixed: the completion never loaded `clojure.core.protocols`.
- **ClojureScript / nbb: the types emission carries the root protocols, and references are unsigned.** Both CLJS emission sites now emit `IEveRoot` and `IEveSio` as the JVM/bb emissions do (the nbb tally test goes from 2 errors to 7 tests / 216 passes / 0 / 0), and the JavaScript reference "realm" number no longer comes out signed — about half of all processes were refusing their own references. Compiled-ClojureScript persistent writes are proven: one compiled process writes people-1k and fresh compiled-CLJS, nbb and JVM readers each match the expected checks 9/9.
- **clojerl: the first `swap!` is fixed.** The dataset checks' opening wall on the BEAM seat is closed in this tree. (The people-1k run itself is diagnosed slow-not-stuck — superlinear in the transact — and its checks at the previous tree died on `clojerl.IHash` for the sorted set's Iter; the Iter fix below is in this tree and the rebuild carrying it has not been re-run.)
- **The sorted set's Iter hashes equal slices equally.** Two equal slices of `persistent-sorted-set`'s Iter hashed differently, so a hash set could hold both; the basilisp port's fix is taken into the shared code verbatim.
- **basilisp: crypto and serialization fixes.** The crypto load path is fixed through its four walls; serialize freeze/thaw no longer overflows, and its `pr-str` is bound.
- **jolt journal: a silent atom loss is fixed (W2)**, and jolt's `pull` is fixed.
- **babashka: entity `pr-str`.** A map-style instance (Entity) fell to the plain map printer on bb; the host completion now hands stamped instances to their own type's printer.

- **nbb: a ClojureScript library naming a goog namespace could not load.** nbb carries no Google Closure library, so a library written for ClojureScript whose source requires one — DataScript's `datascript.db` requires `goog.array` — failed on eve's nbb seat with "Could not find namespace: goog.array". The seat now carries a goog adapter (`adapters/goog/nbb`): the Closure namespaces such libraries name, each var with Closure's own definition; the surface is census-driven, starting with `goog.array`'s `defaultCompare` and the base `goog` namespace's `exportSymbol` and `getUid`, which the DataScript port names (the base namespace without even requiring it — ClojureScript treats it as ambient).
- **nbb: a deftype whose bare `Object` block carries `hashCode` could not load.** Eve's CLJS deftype translation gave a qualified `java.lang.Object` block its cljs disposition (toString stays, equals becomes IEquiv's, hashCode drops — the type's IHashEq is IHash there) but passed a bare `Object` block — the unqualified name a vanilla ClojureScript library's deftype face uses, as DataScript's Datom does — through verbatim, emitting its `hashCode` into the ClojureScript deftype, which sci cannot register ("Unable to resolve symbol: sci.impl.deftype/hashCode"). A bare `Object` block now gets the qualified name's disposition, in the proto map and in `cljs-object-impls`.
- **nbb: a ClojureScript library using primitive type tags on def names could not load.** ClojureScript's `^number` / `^string` tags on a `def`/`defn` name are compiler hints — no vars by those names exist — but sci resolves a name-position tag as a symbol, so such a library (DataScript carries 37, e.g. `(defn ^number hash-datom [d])`) failed analysis with "Unable to resolve symbol: number". Eve's nbb types adapter now interns the primitive tag names that sci's core lacks a var for (`boolean`, `array`, `symbol` already resolve — they name cljs.core functions), each guarded so a name a future nbb supplies itself is never shadowed.

## [0.9.3] - 2026-11-06

### Added

- **jank host.** Eve runs on jank (Clojure on LLVM): a full seat at `hosts.edn`'s `:jank` row — the completion (deftype / defprotocol / defrecord / reify / extend-type, the core's missing ops, `clojure.edn`), eve's types as one C++ object each on `eve.core`'s protocols, and native adapters for slab-io, typed views, regions, encoding, int32, process and crypto, with HTTP and HTTP-server over C sockets. `bb test:jank` runs the shared suite on the seat; `doc/hosts/jank.md` carries the seat's map, its gates and the upstream defect list it answers.

### Fixed

- **Type ids agree on jank (they differed from every other host).** jank keeps
  `+ - *` over two integers of the int32 range in 32 bits, and an integer loop
  local typed by a literal narrows the same way through `recur`, so the
  portable XXH3-128 eve hashes type names with came out wrong at every length
  on this host: the same type definition received a different id on jank than
  on the JVM, ClojureScript or any other host. The jank completion roots
  `+ - *` (inline and var) at jank's own promoting ops as its first forms and
  boxes an integer-literal loop init, so the digest is the portable one.
  `eve.xxh3-kat-test` 4 / 83 and `eve.deftype.type-id-test` 2 / 7, no failures
  or errors — the test's deftype-in-an-atom errors were the same family's:
  a deftype value inside an atom now reads, compares and prints as itself on
  jank (the types carry jank's object behaviors, and the operations the
  evaluator cannot dispatch re-root at `eve.core`).
- **HTTP on jank no longer faults on its error paths.** Both HTTP adapters kept
  their last-error string in a `thread_local` inside `cpp/raw`; jank's JIT
  segfaults when compiled code touches one, so every failure path ended the
  process. The string is a mutex-guarded static now; the seat checks pass
  14 / 14 and both modules AOT-compile clean (`doc/hosts/jank.md`, defect 28).
- jank: a field read written as stock Clojure — `(.-f x)` or `(. x -f)` on an untyped parameter, another instance's
  field included (`(.-keys next)` in a type's method: an external library's idiom) — could not compile (a C++ member
  access on an `object_ref`). The jank completion's `defn`/`defn-`/`fn`/`let`/`loop` walk their bodies first and read
  the field through the completion's field machinery; `(set! (.-f x) v)` resets a mutable field's cell
  (`eve.identity-test`'s stock-field-read row; `probes/jank-deferral`).
- jank: a `case` whose default clause is C++-typed natively (`false`, an integer or string literal, `(boolean x)`)
  failed to compile (jank's `case*` boxes its branches but not its default; upstream defect 19, amended): two eve sets
  holding a by-reference member ended the process at their first comparison (eve.set's `hamt-find`). The completion
  passes every case's default through `identity`; keyword, string and symbol cases keep jank's hashed dispatch.
- jank: jank's own predicates and lookups answered for an eve value by jank's C++ checks — `coll?`, `vector?`,
  `map?`, `set?`, `seq?`, `list?`, `counted?`, `associative?`, … false; `nth` of an eve list a throw. The completion's
  identity table (one mechanism with eve-in-cohost's) answers each by the value's type's protocol, as Eve's types answer
  on the JVM, and jank's own values keep jank's answers (`eve.identity-table-test`); `contains?`, `find`, `nth` and a
  call throw a catchable `ex-info` where the JVM throws. Two Eve sets holding a by-reference member compare equal, a
  hash set and a sorted set of the same members compare equal both ways, an Eve `[k v]` vector is a map entry to an
  Eve map, and `compare` reaches a type's own `IComparable` (`eve.identity-test`, every host).

## [0.9.1] - 2026-11-04

A re-cut of 0.9.0 on the release checker's export. 0.9.0's tree was the repository's whole `eve/` directory
(2,302 files): it carried the working records a release leaves behind — agent and tool configuration, the release
process's own notes, network-atom plans and probes, benchmark run records — 1,682 files `release/eve/exclude` holds
back. This tree is the 620 files the checker ships. No API change.

### Fixed

- `bb run atom-swap` (the README's example) could not load `eve.journal-file`: `bb.edn`'s `:paths` lacked the
  `adapters/journal-file/java/src` root the journal-file seam added to `hosts.edn :bb` on 2026-10-28.
- The README's status line cited a release-process document the release does not ship.

## [0.9.0] - 2026-11-04

0.9 is the API and host contract 1.0 will freeze, published for adopters to build against. Between 0.9 and 1.0 the
public namespaces (`doc/api-guide.md`) change only with an entry here under *Changed* or *Removed*; the host contract
(`doc/unification/HOST-CONTRACT.md` §1–§12) grows only by optional features. **The on-disk and wire formats may still
change before 1.0 without a migration**: the slab and root files carry a magic but no format-version field yet
.

Assembled from the tree's changes since the standalone repository's `c7dc390` (2026-10-15) — about 340 commits,
counted 2026-10-22 ET. Changes between 0.1.0 and `c7dc390` are in that repository's history and are summarized by
what the tree now contains.

### Added

- **Eleven hosts, one shared source.** eve's shared code (`src/`) names no host but one ClojureScript-only file
  (`src/eve/shared_atom.cljs`) — no reader conditional keyed by a host, no branch on the host, no host method call in
  eve.net, each held at 0 by `scripts/check-host-leak` (`.host-leak`) — and a host is a list of adapter roots
  (`hosts.edn`): the JVM, ClojureScript on Node and in a browser, babashka, nbb, ClojureCLR, basilisp, jolt, clojerl,
  glojure (as `glj-eve`, `impls/glojure/build.sh`) and ValueScript. A writer from each of the JVM, ClojureScript on Node, babashka,
  nbb, ClojureCLR, basilisp, jolt and clojerl shares one atom (the coop scenario, `test/coop/`).
  (`hosts.edn`'s ValueScript row carries its types, thread, http and http-server adapters; ValueScript is
  part of the 0.9 release.)
- **Adapters by feature** (`adapters/<feature>/<implementation>/`): region, encoding, process, types, core
  (`eve.host`), core completion, coll (`eve.core`), slab, slab-io (the memory seam, `eve.slab-io`), int32, codec,
  crypto, thread, http, http-server, journal-file, typed-view — with portable implementations a new host takes as they are
  (`slab-io/protocol`, `core/clojure`, `coll/protocols`, `slab/heap`, `int32/portable`, `codec/byte-array`,
  `crypto/portable`, `thread/none`). `doc/adopter-tiers.md`, `doc/adopting-eve-minimal.md`,
  `doc/adopting-eve-fast.md`.
- **Atom domains as atoms.** A domain is itself an atom (slot 0, its registry); member atoms are slots of one
  domain-state. `eve.atom/meta-atom` (a coordination atom beside an atom), `registered-name`, `version`, `seal`
  (a byte-exact digest of a stored version), `$` (the read region: zero-copy reads under pins held to its end),
  `compare-and-set!*`, `domain-lookup`.
  A fresh disk domain opened by several processes at once is created once (`create-exclusive!`); of processes
  creating one named atom at once, exactly one registers it. `doc/atom-domains.md`.
- **Network atoms** (`eve.net.*`): an eve value at a broker or a gateway, synchronous `deref` / `swap!` / `reset!` on
  every host with an http adapter, blobs fetched and stored directly through presigned URLs, per-blob encryption under
  a group key (`:gak`), the pruned swap (a one-key swap HEADs and encodes only what changed), and `eve.net.gateway` (any member of a disk domain serves its atoms on the
  broker's routes; a network CAS is one eve swap). Portable SHA-256, SHA-512, HMAC, AES-256-CTR, XXH3-128, Ed25519 and
  Poseidon2 / BN254, with native crypto adapters on the JVM, babashka, Node, basilisp, ClojureCLR and clojerl.
  `doc/network-atoms/README.md`.
- **Encryption at rest** (`eve.keyring`): a disk domain's slab blocks under AES-256-CTR with HKDF per-class keys; three
  key modes. See `SECURITY.md` for what it does not protect.
- **The thread feature and executor service** (`eve.thread`): `future`, `promise`, `pmap`, `pcalls`, `pvalues`,
  `with-pool`, fixed / single / cached / per-task / scheduled pools, `deftask` / `defconveyed` / `tfn`; one adapter
  per host's threads (`eve.thread-host`, HOST-CONTRACT §11) with a conformance suite. `eve.thread.fold` (`fold`,
  `=>>`, chunks sized from the measured swap cost); `eve.thread.domain` (pools, a registry, election and bring-up in
  a domain's meta-atom, across processes and hosts); `eve.thread.rung` / `eve.thread.net` (domain cells on heap, disk,
  tmpfs windows or a network atom).
- **Collections written once for every host**: `eve.sorted` (sorted maps and sets with comparators, `subseq`,
  `rsubseq`, `rank`, `nth-entry`); `eve.rrb-vector` (core.rrb-vector's ClojureScript implementation in eve memory, the
  worked example of an eve type; EPL-1.0, see `NOTICE`); int-map and the red-black sorted set as one `eve-deftype`
  source each; big values out of line (`eve.deftype.value-buffer`); columnar datasets, tensors and column ops
  (`eve.alpha.ds`, `eve.alpha.tensor`, `eve.alpha.col`).
- **One byte format, pinned by fixtures** on every host (`test/eve/fixtures/eve-bytes.edn`,
  `doc/unification/FORMAT-DECISIONS.md`): characters, big integers, ratios, big decimals, references (process-private),
  value blocks, records and typed arrays among the tags.
- `bb test:<host>` runners for every host but the JVM and ClojureScript on Node (`bb.edn`; those two run
  `clojure -M:jvm-test` and `node target/eve-test/all.js`), `bb test:bb-minimal` (babashka on the minimal and inbuilt
  adapters only), the multi-process scripts `scripts/thread-rendezvous.clj`, `thread-rotation.clj`,
  `thread-fold-rendezvous.clj`.
- `SECURITY.md`, `NOTICE`, `epl-v10.html`, `doc/memory-model.md`, `doc/adopter-tiers.md`.
- **The browser's http adapter** (`adapters/http/browser`: synchronous XHR, bytes as base64 through the
  `x-user-defined` charset): network atoms in a page (`hosts.edn :browser`). In a worker a batch (a level of a network
  atom's blocks) is fetched by the page at once, the worker waiting on a SharedArrayBuffer of its own; eve's browser
  thread adapter hands a worker's own messages to the page's hook (`globalThis.__eve_worker_message`) for it.
- **The http-server feature on Node** (`adapters/http-server/node`: Node's `http` module): ClojureScript on Node can
  serve a gateway (`hosts.edn :cljs`).
- **A performance suite on every host** (`test/eve/perf_suite.cljc`): ten workloads over eve's public API (atom swaps
  and derefs at small and 1,000-key sizes, a zero-copy read, building sorted sets and int-maps, a disk-domain swap,
  64 futures), each in time-budgeted doubling rounds; every host's runner has a `perf` command, and
  `bb scripts/perf-all.clj` runs them all into `doc/hosts/perf-results.edn`.
- **A page per host** (`doc/hosts/`): what was measured there, how eve is built on it, its gotchas, its next steps.
- **The capstone, `capstone/eve-media/`**: a media workbench on six host runtimes and a browser — babashka, the JVM,
  Node/ClojureScript, Go (glojure), the BEAM (clojerl), jolt (FFmpeg's libraries through its FFI), and ClojureScript in
  Chromium as a runtime of its own (a worker pool, over the network rung; the page in reagami) — one disk domain, every
  job a thread descriptor in eve.thread's pools, the coordinator by election.
  `bb eve-media demo | scan <dir> | verify`.
- **aegis's cryptographic primitives** (2026-10-23): `eve.aegis.ballot` (+ `request-message`, `sign-statement`),
  `rsabssa`, `clsag`, `h2c`, `merkle`, `session`, `commit`. Their suites joined the babashka and JVM batteries
  (`bb-minimal` names them out: its portable crypto signs the issuer's RSA row past 30 min). `crypto/vs-kernel`, a
  crypto implementation for ValueScript programs that build the kernel's ed25519.wasm.
- **Journal durability for disk domains** (`:journal?`): a flush appends one record — the epoch and the atom table
  it published — and forces the journal; recovery installs the newest valid record whole. The **retention floor**
  pins reclamation to the oldest epoch recovery may still install: it is set at recovery, advanced at every flush
  (flush-advance, to the previous flush's durably appended epoch) and at checkpoint, where truncation rewrites the
  newest record as the journal's sole anchor. The file, its lock and its forces are the host's adapter seam,
  `eve.journal-file` (`adapters/journal-file/{java,node,clr,python,unsupported}`); a host with no implementation
  refuses a journal domain rather than running it undurably.
- **The ValueScript thread adapter** (`thread/vs` in `hosts.edn :vs`): one long-lived mesh task per thread, a
  mailbox that is a VS atom plus a futex word, closures carried as themselves in the shared slab; the
  `eve.thread-host` conformance suite runs on the mesh (`bb test:vs-thread`).
- **`eve.http-server` and `eve.net.broker` on ValueScript**: a VS program serves the broker and the net store on
  one port (`adapters/http-server/vs`, `bb test:vs-serve`), and a network atom on another host derefs and swaps
  through them; `eve.http` runs on VS over the host bridge's fetch (`bb test:vs-http`).
- **`bb net:local --dir`**: one command stands up eve's local network — a broker, a store, three thread servers
  and the page — and `--dir` persists the broker's and store's state across runs (`scripts/net-local.clj`).

### Changed

- **License: 0BSD** (Zero-Clause BSD), no copyright holder (was MIT, © 2022 John Newman). The core.rrb-vector
  ClojureScript sources under `src/eve/rrb_vector*` remain Eclipse Public License 1.0, as noted in NOTICE.
- **`e/atom` is `eve.atom`'s atom on every host**: in process, a heap domain per `:id` (on ClojureScript the module's
  SharedArrayBuffers); with `:persistent`, a named atom in a disk domain. The 0.1.0 SharedArrayBuffer atom
  (`eve.shared-atom`) is no longer what `e/atom` returns; nothing in the tree requires it.
- **Deref materializes**: `@a` answers the value as the host's own collections. Zero-copy access is `swap!`'s function
  argument and the read region `eve.alpha/$`, inside which `@a` answers the stored value.
- **The JVM's regions** are `MappedByteBuffer` / `ByteBuffer` with `VarHandle` atomics — no Panama FFM, no
  `sun.misc.Unsafe`. JDK 21 is the tested floor (virtual threads in `thread/jvm`).
- **babashka** realizes eve types (`adapters/types/bb`) and takes part in cross-process domains through a lock-only
  region (`FileChannel` byte-range locks) and exclusive holds; it has the in-process heap mode too.
- The HAMT trie index is one 32-bit `mask-hash` on every host; maps and sets written before the change stay readable.
- Symbols past 255 bytes are refused on every host (they were truncated).
- **The tally's format** (9.31): the `.root` header's word at byte 32 (`ROOT_TALLY_OFFSET`) roots count pages, ordinary slab
  blocks (`doc/unification/FORMAT-DECISIONS.md` §11); a write that raises a count commits under the domain's exclusive
  hold.
- **`$` is the read-region macro it was designed as** (`eve.alpha/$`). `(e/$ body...)`: inside it `@` of an
  eve atom answers the stored value, lazy and slab-backed, so a branch of a large atom is reached without the rest
  being materialized; each domain is pinned on its first read and every pin held until the outermost `$` exits (a
  network atom's deref keeps its lease the same way); the body's result is materialized as it leaves; a `$` inside
  another joins it. `src/eve/read_region.cljc`, `test/eve/read_region_test.cljc` (every host). The function the 0.9
  tree shipped as `eve.atom/$`, `($ a f & args)` (the unification's verdict, `doc/unification/
  CANDIDATE-VERDICTS.md` §3, `b5c2ffc59`), is renamed `eve.atom/read-pinned`: eve's own bounded reads use it. Not
  carried from the design: its 30 s lease heartbeat inside a region (production's Worker has no renew route, and
  `eve.net.atom/renew!` releases a lease the broker does not know) and its read-set tracking (`eve.net.atom`'s named
  limit: a plain value records no reads).
- h2c's `C2` / `C1-ED` and clsag's `BASE` / `IDENTITY` are literals, each held to its computation by its test: VS's
  compiler evaluates a top-level def's init as it compiles, and `C1-ED`'s field square root alone took analysis to the
  slab's 32,753-page ceiling.

### Removed

- eve.net.poseidon2 / bn254 / merkle (the proof era's hash; nothing live calls them).
- `atom-domain`'s `:sab-size` and `:max-blocks` options (accepted and ignored), watches (`add-watch` on an eve atom),
  the X-RAY diagnostics and `validate-storage-model!` (both only on the unreachable `eve.shared-atom`).
- The JVM's thread-local allocation logs (one portable `LoggingSlabIO`), the `*lustre-mode*` dynamic var (`:lustre?`
  is now an option of `persistent-atom-domain`).

### Fixed (in the 0.9 release preparation)

- glojure could not load eve: `hosts.edn :glj` lacked the `eve.slab-io` root, and `eve.thread.rung` / `eve.thread.net`
  defined records with inline protocol methods, which glojure v0.7.22 refuses (now records extended by `extend-type`,
  as `eve.thread.cell` already did).
- A network atom's first deref through a gateway failed on babashka: four top-level forms of `eve.net.codec` sat inside
  `coll-rec`'s body after a merge, so `*memo*` was not dynamic until the codec first ran.
- `bb run atom-swap` (the example) and `bb -e` from `eve/` ran on a stale five-root classpath (`bb.edn :paths`).
- `eve.http-server`'s JDK adapter: `:stop` left the server's thread pool running, so a JVM that stopped a gateway did
  not exit.
- A network atom could not carry an integer past int32: a stale copy of the snapshot reader in `eve.net.atom` lacked
  the long's tag the writer emits, so the gateway refused every such write.
- clojerl could not load `eve.net.merkle` (its constants were `def`s of delays, which clojerl compiles as literals), so
  `bb test:clje` stopped at load: the constants are now kept in an atom, computed at first use.
- A network swap whose function answers the value it was given now commits nothing; eve.thread's starved writer over
  the network rung could not take its turn before (its unchanged answer was CASed, and lost, again and again).
- **Memory, three hosts.** glojure's and jolt's batteries together were OOM-killed on a 15 GB box (glj-eve at 7.1 GB,
  jolt at 5.8 GB), and nbb's battery passed 12 GB:
  - glj-eve now collects at Go's default `GOGC` (100) unless the environment sets one: glojure sets 400, letting the heap
    grow to five times the live data (`bb test:glj`: 2.8 GB peak, its process tree).
  - jolt's heap regions are immobile bytevectors in Chez's heap, reclaimed with the region and bounded by jolt's heap
    ceiling as the JVM's are by `-Xmx` (they were foreign memory, never released); mapped regions are unmapped once
    unreachable (`bb test:jolt`: 1.7 GB peak; the two batteries side by side: 445 s, green).
  - On Node and nbb a disk domain's heartbeat timer is unref'd, as every other host's is a daemon: a worker that opened
    a disk domain never ended, and held its isolate (~320 MB on nbb) for the life of the process. A Node program with a
    disk domain open now also exits when its work is done.
- basilisp: numeric arrays (`long-array`, `int-array`, `short-array`, `float-array`, `double-array`) start at zero, as
  Clojure's — basilisp's are n nils, and every namespace loading `eve.net.poseidon2` failed; `sorted-map` and
  `sorted-map-by`, which basilisp lacks (`eve.net.atom`'s snapshot entries).
- glojure: `sequence` with a transducer, `dedupe` of a collection and `partition-all`'s transducer reached JVM classes
  glojure lacks (`eve.thread.fold`'s suites: 47 errors); they are Clojure's in eve's glojure completion.
- A browser worker closes itself once its entry has returned (it stayed resident until the page went away).
- **A fresh map or set written into an atom keeps only its tree.** The HAMT writers path-copied one entry at a time
  and nothing freed the copies: a fresh 200-entry map allocated 391 blocks and kept 54, so every such `reset!` lost
  ~340 blocks and a heap domain filled after 32. Each writer step now frees what its own (never published) path copy
  superseded; eve.set's conj and eve.map's deep-collision assoc free the node they push down; the allocation log is a
  set a self-free leaves.
- An int32 field (an int-map's and the red-black tree's children) can hold a slab offset past 2^31: the field writer
  cast with a checked `int`, so a node threw "integer overflow" once its heap domain's small classes filled and its
  blocks spilled into class 4 and up.
- `bb test:nbb`, `bb test:cljr` and `bb test:jolt` dropped the namespaces named after the task.
- **A browser page could hold three eve kernels.** Each ClojureScript kernel that loads `eve.alpha` reserved its module
  slab at full capacity — six SharedArrayBuffers of 4 GB in all and a 1 GB overflow one — and Chromium caps the
  SharedArrayBuffer bytes a renderer holds at ~16 GB (measured: 15 × 1 GB in a page, then 3 × 256 MB in a worker), so
  a page and its first two workers filled it and the next worker died at start. In a browser a module slab is now a
  growable SharedArrayBuffer (Chromium 111+) at its class's initial capacity, doubling on out-of-memory, the buffer
  grown before the header publishes the new block count; the overflow region likewise, its grown tail merged into
  the free block before it (on mmap domains too). A growable SharedArrayBuffer counts by its length, not its maximum
  (400 of 1 GB maximum each fit beside the 15 GB). Node keeps fixed buffers — it has no such cap, and growable ones
  cost its in-memory work a median 1.06× (0.95–1.24×; one bundle, A/B, the median of three runs each) — unless
  `ps/init!` is given `:growable? true` (the Node suite `slab-grow` runs the slab suites so).
- `JsSabRegion`'s emulated 64-bit store wrote the low word into the high word as well (JS masks a shift count to five
  bits, so `(unsigned-bit-shift-right v 32)` is `v`); nothing read a 64-bit field through a SharedArrayBuffer region
  before the overflow region's growth.
- **The JVM's memory access ran through reflection.** Every aligned load, store and CAS of the JVM's region called
  `MethodHandle.invokeWithArguments` with a vector, which Clojure resolved by reflection at each call
  (`Class.getMethods` and `copyMethods` were most of a swap's samples under JFR), and the unaligned reads picked
  `ByteBuffer.get`'s overload the same way. The region now spreads each handle once to `(Object[])Object` and
  calls `.invoke` on a typed array — 139.8 ns a volatile get with `invokeWithArguments` against 19.1 ns so — and
  types its offsets. A counter `swap!` on the JVM: 169 µs → 28.3 µs (the reflection gone) → 14.7 µs (the spread
  handles), a 20 s / 10 s loop each (2026-10-20 ET; the performance suite's counter swap, 11.8 µs in the final pass,
  2026-10-21 ET). With them, the shared code's reflective sites on the JVM: the slab growth
  scan before each disk-domain swap, the bitmap scan's bytes, the codec's tagged header, `eve-deftype`'s offset
  accessor, the keyring's XOR loops, typed arrays and objects, `seal`'s digest, the http adapter — 73 sites in the
  core namespaces and 13 beyond them, none left but core.rrb-vector's 155 field reads on its untyped nodes and vectors.
- **A cancelled periodic schedule could run once more.** A periodic run is queued on the pool as its own descriptor,
  and was checked for cancellation only when fired: a run waiting in the queue when `future-cancel` returned ran
  after it (basilisp's battery: "cancel stopped it", 7 runs where 6). It checks its future first now: a cancelled
  periodic runs no more, as the JVM's `ScheduledFuture` (a run already started finishes).
- basilisp: `true?`, `false?`, `seqable?`, `supers`, `peek` and `pop` evaluated their argument twice (basilisp
  inlines a call by putting the argument's form into the function's body, and these name their parameter twice):
  `(true? (create-exclusive! f))` created a file, then found it there. eve's basilisp completion removes their
  `:inline`, so a call compiled after it evaluates its argument once.
- basilisp's `str` of two or more arguments made a closure per call (77 µs for `(str "a" "b")`); eve's completion
  patches it with fixed arities (6.1 µs, the same strings). `eve.net.bytes/hex` is a table lookup a byte on every
  host. On basilisp a 32-byte `hex` 4.1 ms → 1.0 ms, and `eve.thread-net-test`'s gateway test 1,234 s → 229 s (239 s in
  the final pass, the namespace alone).
- clojerl loads eve's hinted namespaces: a type hint's symbol must resolve on clojerl (eve.host defines the array
  casts for it), and `eve.keyring`, hinted for the JVM, now refers them.
- jolt's disk domains at a relative path failed (`open …: errno 2`): jolt's launcher runs from jolt's own root, and
  `open(2)` resolved the path there while `java.io.File` resolved it against the user's directory; the region now
  opens the absolute path.
- **What a write leaves allocated** (`doc/memory-model.md` "What a write leaves
  allocated"). Transients reclaim (2026-10-21): each write frees what it superseded among the
  blocks the transient made — int-map gains a transient, and eve.sorted's, the map's, the set's and the vector's free;
  the int-map, sorted and red-black constructors build through one from no tree and keep their tree only. Every tree
  has an exact walk (int-map, the sorted treap, the red-black set), which its transient uses; a stored int-map or
  sorted collection replaced in an atom now retires whole — it retired its header only, every node stayed — since
  9.31 by `eve.retire`'s one walk over its registered layout. int-map's own `assoc` made two throwaway
  branches at every leaf split, the red-black insert and delete dropped nodes mid-operation, and a vector pop across a
  trie level left the root it cloned; none does now. Measured on the JVM, over 160 swaps: one int-map `assoc` a swap
  531–700 blocks in use, flat (+5 a swap before); a 200-key int-map rebuilt each swap, bounded (+~1,026 a swap before).
- **An eve list kept at an atom root broke after `conj`s**: its walk retired every old cell a `conj` keeps; it aligns
  the two chains by their counts now (9.29).
- **A user's eve-deftype at an atom root lost the value it replaced** (a cons cell's tail): its old root block was
  retired. Every eve-deftype registers its pointing fields as it is defined, and a replaced one retires by the
  positional walk over them, at the native walks' speed — one insert into a 5,000-node user tree 0.16 ms, against
  25.0 ms for a whole-value reach (9.30).
- `eve.core/transient` of an eve hash map threw on babashka (sci refuses `IEditableCollection` on a map type, and
  the type dropped it); it answers eve.core's protocol now (9.32).
- **A value held at two places was freed when one place let go** (9.31): `(assoc m :b (:a m))`, a value made from
  `:a` that keeps `:a`'s interior, or a piece of one atom stored in another of the domain, then a new `:a` — the walk
  was single-pair, and the other place read freed blocks once they were reused. The tally rule (2026-10-21):
  a piece held at more than one place carries a count, raised when a write stores it again, lowered when a write drops
  it, released at zero through the epoch's deferred release (`eve.tally`, `eve.retire`). One walk for every kind now
  (`eve.retire`), each block read by its layout; an ordinary swap moves no count; a write that raises one commits
  under the domain's exclusive hold. The reachability set two retire arms built over the new value is gone (10.2), and
  the walk follows eve value arrays (an RRB vector's nodes) and array pointers, which it treated as opaque.
- The perf suite's two build workloads run on their time budget (they stopped at 16 builds).
- **A list stored on glojure.** eve's list writer walked its elements with `(reduce f init (rseq elems))`, and
  glojure's `reduce` has no `coll-reduce` method for a vector's rseq ("No method in multimethod 'coll-reduce' for
  dispatch value: *lang.apvRSeq"), so storing any list in an eve atom threw there; it walks them back to front by
  index now — the same nodes in the same order (`src/eve/list.cljc`). Found by main's rewritten tally test, the
  first to store a list on glojure.
- **basilisp's host load.** eve's basilisp completion did not load as written on 0.5.1: its division spellings
  now go through `operator/` (0.5.1's reader), `aset` is patched for bytearray targets, and the journal
  adapter uses Python's `len` — the lpy journal smoke runs green end to end, with no shadow copy.
- **ClojureCLR regions never forced to disk.** `mem/sync-region!` answered nil for every CLR region, so a journal
  flush's slab sweep and the recovery root force were silent no-ops there. The CLR region's `ISyncableRegion`
  now issues the msync syscall (`adapters/region/clr`).

### Known issues

Recorded in: **a babashka writer deadlocks a shared disk domain once
a lock-free writer has died inside a write** (9.14 — let babashka read a shared domain and write through a gateway;
since 9.31 a lock-free host's write that raises a tally count takes the same hold and waits the same way, so the hang
now reaches any host's raising write);
an `atom-domain`'s deref carries its registry entry; an eve
collection built outside `swap!` cannot be stored into an atom (build it inside); `swap!` returns the slab-backed value;
`im/range` on a value read through `$`; the `api-defs` exports lack docstrings; the divergences of the reclamation code
from the memory model (`doc/memory-model.md`: D1, D3–D5, and D6 — the tally); **a persistent
chain a swap's own function builds and drops is not reclaimed** (the model's scope-local temporaries: build with a
transient or a constructor, which reclaim — 9.25); **a domain shared with a participant whose walk predates the tally
(eve before 9.31, `vs.eve-disk`) must not hold one piece at two places** (10.6).

## [0.1.0] - 2026-03-10

### Added
- Shared-memory persistent data structures for ClojureScript and Clojure
  - `atom`, `hash-map`, `hash-set`, `vector`, `list` backed by SharedArrayBuffer
  - Cross-process mmap-backed persistent atoms (JVM + Node.js)
  - Native C++ addon (`mmap_cas.cc`) for memory-mapped CAS operations
  - Epoch-based garbage collection for safe cross-worker/process memory reclamation
  - WASM-accelerated bitmap allocator with SIMD optimizations
  - Slab allocator with six size classes (32B–1024B) plus coalescing overflow
  - Lazy slab growth — files start small and grow on demand
- Specialized collections
  - Integer map (PATRICIA trie) with merge-with and range queries
  - Red-black tree sorted set
- `eve/obj` typed shared objects (AoS and SoA layouts)
- `eve/deftype` for custom SAB-backed types with atomic field operations
- Typed array support with full Atomics API
- JVM persistent atoms via Panama FFM (Java 21+)
- X-RAY memory diagnostics for slab allocator debugging
- Comprehensive documentation and benchmark results

[Unreleased]: https://github.com/SeniorCareMarket/eve/compare/v0.9.0...HEAD
[0.9.1]: https://github.com/SeniorCareMarket/eve/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/SeniorCareMarket/eve/compare/v0.1.0...v0.9.0
[0.1.0]: https://github.com/SeniorCareMarket/eve/releases/tag/v0.1.0
