# CHANGELOG — DataScript for Eve

The release change log for the Eve port of DataScript, researched from
the lanes' own git logs, STATUS and REPORT files on 2026-11-07. Every
entry names its evidence. States are stated the way the record supports
them: **landed** (in a named tree), **in flight** (work banked, proof
not passed), **blocked** (wall named, owner named). Branch tips cited
were verified against the remote refs where a remote ref exists.

Upstream baseline: **DataScript 1.8.1** (EPL-1.0) on
**persistent-sorted-set 0.3.1** (MIT) and **extend-clj 0.1.0** (MIT) —
see [PROVENANCE.md](PROVENANCE.md).

## The ports (2026-11-06)

- **Phase 1 — persistent-sorted-set comes to Eve.** Tonsky's B-tree
  persistent sorted set, upstream 0.2.3 CLJS form ported: namespace and
  public API unchanged; the persistent types became Eve types so sets
  are slab-resident Eve values; the `arrays` namespace re-based from
  host arrays to Eve vectors. Verbatim vanilla copies and the vendored
  upstream suite banked alongside. (`contract-test/PROVENANCE.md`,
  `contract-test/PORTING.md` on the flagship branches.)
- **Substrate upgrade — PSS 0.2.3 → 0.3.1.** Before DataScript itself
  was ported, the Eve BTSet was upgraded to exactly the version
  DataScript 1.8.1 pins, gaining the 0.3.x API DataScript calls
  (`sorted-set*`, `union`, `intersection`, `difference`, `subset?`,
  `est-count`, `settings`). (PORTING.md §U.)
- **Phase 2 — DataScript 1.8.1 ported near-verbatim.** 17 namespaces in
  `lib-ds/datascript/`, same names, same public API; extend-clj 0.1.0
  vendored verbatim as the second pinned dependency. The deltas
  (full catalogue: `contract-test/PORTING-DS.md`):
  - **D1 — storage:** vanilla's storage layer replaced by Eve-atom
    semantics. A DB is an Eve value; storing it *is* placing it in an
    Eve atom — a disk atom for durability, a shared disk atom for
    cross-process / cross-host access.
  - **D2 — query tuples are Eve vectors;** writes thread functionally
    through the substrate array API. No semantic change.
  - **D3–D8 — the seat-surface deltas:** a host class import dropped
    from serialize; comparators via core `compare` rather than a host
    `compareTo` call (D4); the `IFn$OOL` import and primitive-invoke
    arm dropped (D6, F-IFNOOL); a primitive tag quoted in patch-tag
    (D7); a spliced reader hint in pull-api re-spelled for the JVM
    reader (D8). D5 records a *non*-delta: a flagship smoke failure
    traced to a stale classpath, corrected by measurement.
  - **T1/T2 — Datom and DB became Eve types** (`eve-deftype`s) with
    record semantics expressed as protocol groups; vanilla's mutable
    hash caches dropped (hashes compute on demand); **T3 — the three
    datom index comparators registered by keyword**, so a DB built on
    any host has field-identical indexes everywhere.
  - **Residence proven (JVM):** a whole DB built inside a swap lives in
    a shared disk Eve atom; a second process derefs a real
    `datascript.db.DB`, queries it identically, its seal matches the
    writer's exactly (72 blocks), and it transacts against the resident
    DB. (PORTING-DS.md, Types — AS IMPLEMENTED.)

## Correctness campaign (2026-11-06 → 11-07)

- **D9 — the join fix** (lane `lane-m/datascript-cljc`): joins over free
  entity variables died on ClojureScript-family seats because the
  contained/plain getters' `:cljs` face read ILookup tuples with the
  array getter. Fixed in three steps — `1da0306a24be` (getters read via
  `get`), `85232fe7ab41` (second site, in tuple-key fill),
  `96b5d7768fb6` (the `:cljs` face fully mirrors the `:clj` arm) —
  **F-NBB-JOINGETTER closed; nbb behave fully green.**
- **Suite reconciliation.** The upstream DataScript suite, run
  per-namespace on the JVM tally runner: an early clean tally read
  189 tests / 1,171 passing / 2 failing / 54 errors
  (`d89e198f3f64`); the **corrected, reconciled tally is 181 tests —
  1,081 passing, 2 failing, 55 errors** (`b8e0ad821a98`), with the 38th
  suite row (`datascript.test.cljs`) throwing on the JVM runner exactly
  as it does on the unported baseline — an artifact of the runner, not
  a port regression. Per-namespace rows are identical to the successor
  baseline: **zero regressions** is the claim, and it is the measured
  one. Babashka behave **fully green** on the final tree.
- **Port copies reconciled** (`0c76ffff0fbf`): the flagship and CLJC
  lane copies of the port were diffed and unified — the D4 comparator
  delta applied to both (`db.cljc` byte-identical across copies), the
  D9 form of `query.cljc` standing as the reconciled one; reconciled
  copy re-proven (Babashka behave fully green; JVM tally rows identical
  to the prior run).

## The flagship parallel runs (2026-11-07)

- **fs3run9 (predecessor lane):** JVM + Jolt transacting concurrently
  for full 60 s windows into one shared DB; counter sums exact
  (`[7 7 7 6 6]`); readback agreement exact across JVM / Jolt / nbb
  (748 datoms, one seal digest). Babashka died before READY — the
  defect that opened F-BB-DATOM-CMP.
- **Babashka unblocked — F-BB-DATOM-CMP fixed and landed** in the
  0.9.3.1 tree (Eve-side: the Babashka types adapter's IsEve arms in
  `eve/src/eve/deftype_proto/data.cljc` +
  `eve/adapters/types/bb/src/eve/types.clj`; report:
  `REPORT-BB-DATOM-CMP.md` on `lane-m/datascript-collate-0931`).
  Flagship-setting solo proof passed: READY reached, full window,
  `:inc-fail 0`, 246 datoms seen, seed seal identical.
- **fs4run1 — four-host rendezvous** (JVM, Babashka, Jolt, nbb READY
  and released together). JVM / Babashka / Jolt windows clean; **nbb
  died in-window and the shared root was torn.** Discriminator runs
  (fs4disc2 / fs4discA3 / fs4discB) isolated it: **F-EVE-NBB-VIEW** —
  when the shared slab grows past the size nbb mapped at open and the
  nbb process dies mid-transaction, its stray writes can land on live
  blocks and corrupt the shared DB for every host (JVM+Jolt-only runs
  are clean; runs where nbb survives are clean). Owner: Eve's nbb seat
  (region-js growable-mapping handling). Verdict report:
  `d4d1c5718a1b`.
- **Glojure lands in the release tree.** The residence fixes merged
  into `lane-m/eve-release-0931` (absorb commit `328ba6b4601a`), then
  the arithmetic×completion interaction fix (the `neg` self-recursion
  through the overflow re-root) landed at **`303d5c6d751e`** — the
  current remote tip of the release branch — with the R1 gate green on
  the build server. En route, **F-GLJ-ON-J was retracted**
  (`54cb06e727db`): the "glojure cannot load Eve on the build server"
  finding was an artifact of an rsync-copied tree; from a git checkout
  it loads fine. Standing rule from that retraction: glojure legs run
  only from git checkouts.
- **★ fs4run2 — FIVE-HOST RUN, FULL PASS** (`06e5371dd62`, flagship4):
  JVM, Babashka, Jolt, nbb and glojure — five processes, one shared
  DataScript DB in one shared on-disk Eve atom, all READY, released
  together, ~68 s of five-way overlap. **Every seat `:inc-fail 0`;
  readbacks unanimous — 1,138 datoms, one identical seal on all five;
  counters exact (`[2 2 2 2 0]`).** The five-host milestone is met.

## Seat expansion — in flight (as of 2026-11-07 ~13:45 ET)

- **Jank — blocked, fix in flight.** The flagship entry ticket failed
  on F-JANK-READER-LIBSPEC: a reader conditional inside a libspec
  (`[#?(:cljs cljs.reader :clj clojure.edn) :as edn]`), which jank's
  loader rejects (`a5b535c7345e`). The flagshipfix lane spliced the
  libspecs into one plain libspec per feature across
  `datascript/core.cljc`, `datascript/query.cljc` (committed:
  `733a5c029594` — **local-only; that branch has no remote ref**) and
  `datascript/impl/entity.cljc` (third splice, uncommitted in the
  lane's worktree at last check). The entry proof then hit the next
  wall — `clojure.lang.Seqable could not be resolved` inside
  `datascript.db` — and the collation lane root-caused it on
  2026-11-07: **F-JANK-CLASS-SIMPLE-NAME** (`da6cabc3565a` on
  `lane-m/datascript-collate-0931`, local commits ahead of that
  branch's remote tip `350a3498f020`): on jank, a dotted symbol has no
  namespace, so `(name 'clojure.lang.Seqable)` is the full dotted
  string and the completion's class→protocol tables, keyed by simple
  names, never match; the fix looks up the segment after the last dot
  at all four sites. A `clojure.data` supply for the jank completion
  (DataScript's db requires it; jank 0.1 ships none) exists as
  uncommitted work on a detached tree. **State: root cause found, fix
  banked in a lane, entry proof not yet green, nothing landed.**
- **Basilisp — blocked, fix in flight** (lane `lane-m/eve-lpy-fix`;
  remote tip `ae9a82e4` trails the lane's local tip `ec8e507da97d`).
  The entry ticket failed on the core ns form plus **F-LPY-LRU-CTOR**:
  `datascript.lru` defined its LRU type only under `:clj`/`:cljs`
  reader arms, so under basilisp's `:lpy` feature set no LRU type
  exists at all (`74727fe4c442`). The lane's wall-walk since:
  an `:lpy` deftype arm for the LRU (`f9d243c2`), the LRU rebuilt on
  Eve's own deftype machinery plus a `:lpy` raise arm
  (`e3c38db33dc5`), a root finding that basilisp's reader rejects
  top-level `#?@` splices (F-LPY-TOPLEVEL-SPLICE) with `:lpy` arms
  added at 47 sites in `db.cljc` (`3a669b5dc3d0`), surgical `db.cljc`
  fixes (`74c7a8efb1d5`), completion-layer supplies (a `java.lang`
  value-class module, `Util/hashCombine`, a `clojure.data` for the
  basilisp completion — `c642d925ce1c`, `c76fc836c020`,
  `ec8e507da97d`), per-seat `ihash` and `python/TypeError` catch arms
  (`4d8d02f47286`), the comparator re-spelled as a plain function
  because reified dotted class names cannot resolve on this seat
  (`e2307ebb878f`), and LRU sequence members (`f3460bff8f91`).
  **State: the solo entry proof (db loads + transact + query) has
  never passed; the current wall is the DB deftype group naming
  dotted class interfaces. Not merged anywhere.**
- **Clojerl (BEAM) — Eve-side walls mostly cleared; DataScript proof
  not yet run.** The lane opened on Eve failing to load at all:
  `alloc.cljc:897` — "Unable to resolve symbol: unchecked-add-int"
  (the `:clje` seat's core lacks the `unchecked-*` names; two Eve
  source files called them bare). The wall-walk (STATUS-CLJE.md in the
  lane's worktree): the two call sites qualified through `eve.host`;
  sibling audit of every `unchecked-*` call in the tree; `hamt_util`
  given the exclude+refer treatment for `aset`/`reset!`/`long`; an
  `IStringable` extension for the array types; the xxh3 secret made a
  runtime construction instead of a module literal; and a seat law
  discovered in the analyzer — clojerl resolves a callee's declared
  type tags in the *caller's* namespace — remedied by stripping pure
  decoration tags from function bodies (118 tags, 20 files).
  **Green so far: `eve.hamt-util` loads and passes its own suite on
  BEAM (5 tests, 51 assertions, 0 failures).** The domain-open proof
  was re-running and the DataScript entry proof is staged but unrun.
  **State: all 26 files of fixes are uncommitted in the lane's
  worktree; the branch still points at the release base. Nothing here
  can merge until the lane commits.**
- **ClojureCLR — surveyed only.** Runtime present (Clojure.Main under
  `~/.dotnet`); the entry proof is the lane's next step and has not
  run (`1c3f2f331dfc`).
- **ClojureScript/Node and browser — not yet attempted** for this
  library; the browser seat also needs a rendezvous design before it
  can join a shared-domain run.
- **ValueScript seat — blocked** on Eve-in-VS residence machinery.

## Release state (2026-11-09, supersedes the 2026-11-07 note)

- **The DataScript port is in the 0.9.3.1 release tree** — verified by
  tree listing: `lane-m/eve-release-0931` carries `contract-test/lib-ds`
  (the library), `contract-test/lib/` (the sorted set),
  `contract-test/lib-ds-deps/` (extend-clj) and `contract-test/drivers/`.
  This supersedes the 2026-11-07 release-state note ("not yet in the
  release tree"): that was true of the tree it measured and is false
  of this one.
- Seat movement since 2026-11-07, per the release record: jank's
  compiled form is proven at the pin (AOT build clean; the one-datum
  contract 5/5 cold at 0.317 s, journal engaged, readback exact);
  compiled-ClojureScript persistent writes are proven (people-1k
  written by one compiled process; fresh compiled-CLJS, nbb and JVM
  readers 9/9 exact); ClojureCLR's people-1k is complete-verified and
  its people-20k is walled at the region's 2 GiB ceiling; clojerl's
  first `swap!` is fixed, its people-1k diagnosed slow-not-stuck with
  the Iter-hash rebuild un-run; basilisp carries its crypto, serialize
  and Iter-hash fixes, its dataset acceptance re-run still owed. The
  one named cross-host divergence: `:db-hash` throws on bb (a standing
  SciMap limitation); every other readback field was identical.
- Nothing in this changelog has moved to the public repositories. Per
  standing rule, public repos receive releases only, as collated
  snapshots, on the operator's word — which has not been given for
  this library.
