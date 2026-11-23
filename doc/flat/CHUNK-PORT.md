# CHUNK-PORT — the C1 post-pin fixes and the H5 sister integration, ported into the collated chunk.cljc

Base: the collated tree's `src/eve/flat/chunk.cljc` was lane X1's merged
file (2,662 lines, ONE chunk.cljc: shared writer/reader with reader
conditionals, deftype protocol handles per host) plus the header-ctx nil
guard from C1 f243c1d. Two bodies of work built on C1's JVM-only chunk at /
after pin 9ea620b were not in X1's transplant. This port moves them in, form
by form, keeping X1's structure: shared forms stay shared, host differences
stay in reader conditionals, and the :cljs side keeps X1's protocol handles
everywhere C1/H5 have no CLJS counterpart.

## A1 — C1 7969199: proxy-based map/set handles (commit e69fb4c)

Source: `git show 7969199 -- src/eve/flat/chunk.cljc` (lane C1).

Landed, all in the `#?(:clj …)` handle section unless noted:

- `defmaphandle` → **`defmapproxy`**: the map handles are now proxies over
  `clojure.lang.APersistentMap` (a plain Clojure map's equiv dispatches on
  the other side's runtime class; a deftype IPersistentMap fails
  `(= plain flat)`, an APersistentMap accepts any java.util.Map). The
  macro defines the `->Flat*` factory; the closure holds the node
  position, one 2-slot object array holds the hash/digest caches seeded
  from the factory's `_h`/`_d` args. Instantiations: `FlatMap`,
  `FlatRecord` and `FlatTagged` with `:record? true`, `FlatRow` with
  `:row? true`; new marker protocols `IRecordInfo` (already present) and
  **`IRowHandle`** (`-row-index`), added beside it.
- `FlatSet` deftype → **`->FlatSet`**, a proxy over
  `clojure.lang.APersistentSet` for the same reason.
- `defseqhandle`: the `_h`/`_d` fields are generated
  `^:unsynchronized-mutable` (`mfields`) and the hash/digest caches are
  `set!` **inline** in `hasheq`/`-digest` — C1's finding: a field cannot be
  assigned from a nested fn (the closure form is what threw
  IllegalAccessException on X1's JVM deftype handles).
- `pack-all`: the approximate seal replaced by C1's **exact-greedy** seal —
  reps are computed once, a candidate chunk is fully encoded per row, and
  the chunk seals when the candidate passes CHUNK-TARGET.
- Scan sort fix (shared with B4, below): shape/type lists are built from
  `[key count]` pairs **sorted by `second`**, destructured — see §B4.
- KMAP entry ordering in `write-node!`: `sort-by` with a comparator →
  `sort` with the comparator (C1's form).
- `chunk-bytes` accepts raw bytes (host conditional: `[B` on :clj,
  `js/Uint8Array` on :cljs).
- `generic-record?`: by node tag (0x2A) instead of `instance? FlatTagged`
  (the class no longer exists on the JVM).
- `assoc-flat` / `unpack` dispatch on the marker protocols
  (`IRowHandle`/`IRecordInfo`, `-row-index`) instead of the removed
  classes. `unpack`'s byte-array door gained C1's EVS1 magic check, so a
  non-chunk byte array is itself, not an open attempt.
- `unpack` is in the :clj-only block in this file (X1 structure), so it
  took C1's form directly; `assoc-flat` is shared, so its row test is
  `#?(:clj (satisfies? IRowHandle h) :cljs (instance? FlatRow h))` and
  the record test is `(satisfies? IRecordInfo h)` on both hosts (the CLJS
  FlatRecord/FlatTagged deftypes implement IRecordInfo too).

Choice: C1 7969199 _deletes_ `cached-hash`/`cached-digest`. They are
**kept** in the collated file: the task's constraint lists them as X1
features that must survive, and the CLJS-adjacent shared plumbing still
names them. On :clj they are now unused by the seq handles (which inline
their caches per C1) — retained, not deleted, and harmless.

## A2 — C1 921fb7a + a9ca18e: handle meta via IAttachMeta (commit 1471ad2)

Sources: `git show 921fb7a`, `git show a9ca18e` (lane C1).

- **`IAttachMeta`** protocol (`-attach-meta`/`-hmeta`), defined :clj-only
  after `(declare read-value row-handle)`, where C1 puts it (before
  `col-at`). Rationale is C1's, kept in the docstring: the proxy classes
  do not satisfy IObj in every classloader world, so `with-meta` silently
  drops map/set metadata.
- `defseqhandle` deftypes implement IAttachMeta; the section is written
  with a9ca18e's paren correction (`(-hmeta [_] ~'mm)` does not close the
  preceding group).
- `defmapproxy` and `->FlatSet` moved to their C1-final forms: IAttachMeta
  in the interface list, proxy method names in the protocol-interface
  mangled spellings (`_rep`, `_attach_meta`, `_hmeta`, `_tkey`, `_fp`,
  `_row_index`, …), a `SeqIterator` `iterator`, and `IPersistentSet` in the
  set proxy's interfaces.
- **`IGenericRecord`** marker protocol; the FlatTagged proxy carries it
  (`:generic? true`); `generic-record?` is `(satisfies? IGenericRecord h)`
  on :clj. On :cljs there is no marker (X1's tagged handle is a deftype),
  so the :cljs branch keeps the A1 node-tag form.
- `read-value`'s META node (shared form): the :clj branch attaches via
  `-attach-meta`; the :cljs branch is X1's IObj/`with-meta` form verbatim.
- `unpack` re-applies handle meta to the materialized value: `m` read via
  `-hmeta` when the handle satisfies IAttachMeta, else `meta`; a handle
  meta is itself unpacked before `with-meta`.
- Equality domain: `node-equiv` rewritten digest-first on **both** sides
  (a plain side's digest is `eve-digest`); new **`equiv-any`** — scalar
  leaves compare by their Eve bytes (float edges follow the digest),
  handles recurse, byte arrays by `Arrays/equals` — used by
  `node-equiv-walk` (seq/map/set walks; the set walk is a `some` over
  equiv-any, not `contains?`) and by `map-equiv-of`. The equality
  section's declare was widened exactly as C1's (`handle-ctx handle-off
  eve-digest`); `eve-digest` itself already existed in X1's transplant
  (digest section), so nothing was duplicated.
- `typed-rep`: record fields enumerated once — basis mapped to keyword
  form (`(keyword (name f))`), no `(vec (keys v))` fallback union.

## B — H5 sister integration (this commit)

Source: `git diff 9ea620b h5/claude/flat-h5-sister -- src/eve/flat/chunk.cljc`
(lane H5). H5's names `lookup-node` / `entries-of-node` / `row-lookup` /
`row-entries` all exist in X1's file under exactly those names
(`lookup-node`/`entries-of-node` shared, `row-lookup`/`row-entries` in the
:clj handle section; the :cljs section has its own `row-lookup`), so no
adaptation of the source bodies was needed beyond host placement.

- **Rep converters**: `[eve.flat.source :as source]` added to the ns;
  `rep-converters` atom, `register-rep-converter!`, `convert-rep` after
  `(declare to-rep)` (shared — they are host-neutral); `to-rep`'s fallback
  is `:else (or (convert-rep v) v)`; `pack`'s scalar door gains the claim
  check `(nil? (convert-rep v))` after the host splice, so a registered
  eve-deftype instance packs as its type while an unclaimed scalar stays
  itself. `eve.flat.sister` reaches both vars by `requiring-resolve`,
  and its converter registration is :clj-only, so :cljs behavior is
  unchanged (no converters ever register there).
- **Type registry as a map**: `type-registry` is now
  `[tkey fp] → sister factory | nil`; `register-type!` gains the 3-arity
  (2-arity delegates with a nil factory, same answer `[tkey fp]`);
  `sister-factory` added. `sister.cljc`'s
  `register-descriptor!` calls the 3-arity through `chunk-var`.
- **Field sources**: `RecordSource` / `RowSource` deftypes implementing
  `eve.flat.source/IFieldSource`, reading via `lookup-node` /
  `entries-of-node` / `row-lookup` / `row-entries` (declared forward with
  the handle factories). They are **:clj-only**: `row-entries` exists only
  in the JVM handle section, and the task's bar is the JVM batch reader;
  the :cljs build never sees the types. (-field-map ordering is the
  node's entry order on both sources, as in H5.)
- **Generated sisters in the reader**: `read-value`'s typed branch and
  `row-handle` answer `(f (->RecordSource ctx off))` /
  `(f (->RowSource ctx off i))` when a factory is registered, else the
  previous record/tagged/row handles. Both are shared forms, so the
  factory dispatch sits in the :clj branch of a reader conditional and
  the :cljs branch is the pre-port form verbatim (no sister factories
  register on CLJS in v1; `sister-factory` itself is shared and simply
  always nil there).

## B4 — the shape-list/type-list sort fix ("scan throw")

Both Sister and Node hit it: the pin built `[key count]` pairs and then
sorted them **by `val`** — `val` on a vector pair is a ClassCastException
waiting for the first shaped map. The corrected semantics, applied in
A1 in both of X1's scan bodies: map the table to `[key count]` pairs,
`sort-by second`, and destructure the pair's `first` for `:t`/`:ks`
(`:key`/`:fp`). H5's diff destructures `(first e)` in the mapping fn;
C1 7969199 destructures `[[k _]]` in the binding — same pairs, same
order; the collated file uses C1's binding form in the :clj scan and the
same form in the :cljs scan (whose pre-fix form sorted the volatile map
by `val`).

## The i64 column-range mechanism (must not regress — it did not)

X1's planner computes a narrowed column's range **without ever forming
a host long difference that can overflow**: on :clj `col-range` is the
_promoting_ subtraction `(-' mx mn)` (C1's pin had `(- mx mn)`, which
throws ArithmeticException on a full-range i64 column — finding F-C);
on :cljs the planner's integer math is BigInt throughout (`col-min`/
`col-max` reduce over `bi`, `col-range` is `bi-`). The port does not
touch the planner, `col-min`/`col-max`/`col-range`, `column-view`,
`decode-all`/`decode-plain`, the BigInt helpers (`bi`, `bi+`, `bi-`,
`bi<`, `bi<=`, `bi=`, `bigint?`), or the `dv-of`/Buf accessors — verified
by the diff: no hunk lands in those sections.

## Public surface check

Every public var other lanes call survives: `pack`, `pack-all`, `open`,
`open-checked`, `bytes`, `bytes-of`, `chunk-bytes`, `full-decodes`,
`content-digest`, `generic-record?`, `record-tag`, `register-type!`
(extended, not renamed), plus new `register-rep-converter!`. The only
rename is the internal macro `defmaphandle` → `defmapproxy` (:clj only;
nothing outside this file referenced it — grepped `src/eve/flat/*.cljc`
and `src/eve/flat.cljc`: the only cross-file chunk callers are
`eve.flat`'s delegates to `pack`/`open`/`bytes` and sister.cljc's
`requiring-resolve` of `register-type!`/`register-rep-converter!`).

## Verification (local, light — full suites run on J via the coordinator)

- Paren/bracket balance over the whole file after each piece (clean).
- Clojure reader pass over the final file, both feature sets:
  `:clj` — 118 top-level forms read; `:cljs` — 125 top-level forms read.
- Bare require of the namespace on the JVM (deps.edn classpath,
  `timeout 55`): **REQUIRE OK** — `eve.flat.chunk` compiles and loads;
  `register-rep-converter!`, `register-type!`, `->FlatSet` and
  `->RecordSource` all resolve. The only warnings are pre-existing X1
  reflection warnings in the shared open/grown-buf code (unhinted Ctx
  field access), untouched by this port. Per the local-gate constraint,
  no suites were run on this box; chunk_test (C1-final form) and
  sister_test run on J via the coordinator.
