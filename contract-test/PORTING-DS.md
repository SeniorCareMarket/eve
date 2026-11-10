# PORTING-DS — every DataScript delta from vanilla, categorized

The port: `lib-ds/datascript/*.cljc` — DataScript 1.8.1 (PROVENANCE-DS.md)
ported near-vanilla onto this lane's persistent-sorted-set Eve port.
Upstream 1.8.1 already requires `me.tonsky.persistent-sorted-set` in
db/conn/core/pull_api, so the lane's BTSet port is the index substrate
by construction; the substrate was first upgraded to PSS 0.3.1 (the
version DataScript pins) — that delta is PORTING.md section U.

State at first writing (2026-11-06 ~2:05 PM ET): of the 17 staged
namespaces, exactly three differ from vanilla-ds. Everything else is
byte-identical to vanilla and stays that way unless a measured Eve
surface forces a delta, recorded here in the same style as PORTING.md
(category: Eve-surface adaptation / host-quirk workaround / NONE).

## D1 — storage: vanilla's storage layer replaced by Eve-atom semantics
`datascript/storage.cljc` — NEW file, no vanilla counterpart in the
staged set (vanilla's storage rides PSS 0.3.x's storage-capable sets: a
StorageAdapter inside each index set, the JVM form's classes doing the
work). On Eve that layer has no counterpart and needs none: a DB is an
Eve value, and storing it IS placing it in an Eve atom — a disk atom
for durability, a shared disk atom for cross-process/cross-host access.
The port keeps vanilla's IStorage protocol and the entry points
datascript.core touches, with Eve semantics: maybe-adapt-storage is
the identity when no :storage opt is given (the common path, inside
empty-db/init-db); an explicit :storage opt raises with the Eve
guidance rather than being silently ignored; storage/storage-adapter
answer nil (a DB's residence is whichever Eve atom holds it);
store/restore/addresses/collect-garbage/file-storage raise with the
guidance. The shared-atom drivers demonstrate the Eve form:
lookup-or-create a (disk) Eve atom + swap!/deref.
Category: Eve-surface adaptation (the project's headline shape: the
whole DB as a user value in one shared Eve atom).

## D2 — query: tuple arrays are Eve vectors; writes thread functionally
`datascript/query.cljc`, two sites (join-tuples, -collect-tuples).
Vanilla's tuples are host Object[] arrays, mutated in place. Here a
tuple is a substrate array — an Eve vector, via
`me.tonsky.persistent-sorted-set.arrays` (required as `da`, the same
namespace DataScript itself uses for its index arrays) — so writes
are functional: `da/aset` returns the new array and the copy loops
thread it; reads go through `da/aget` / `da/array?`. No semantic
change: same tuples, same joins, same results.
Category: Eve-surface adaptation (substrate swap, as PORTING.md S).
Addendum (multi-host lane, from upstream pull-api's 62 errors):
`datascript/db.cljc` init-db built the avet index array with
clojure.core `to-array` — a host Object[] fed into the substrate,
where `arrays/aslice` (subvec) threw ClassCastException. Vanilla's
line is identical and consistent there (host arrays throughout); in
the port it is `arrays/into-array`. Same category, one site.

## D3 — serialize: the JVM form's class import dropped
`datascript/serialize.cljc`: vanilla imports
`[me.tonsky.persistent_sorted_set PersistentSortedSet]` — the JVM
form's class, which does not exist in the Eve world (the port's type
is me.tonsky.persistent_sorted_set.BTSet) and is never referenced in
the file. Dropped; the `[datascript.db Datom]` import stays.
Category: host-quirk workaround (a name that cannot resolve on Eve).

## D4 — comparators: core `compare`, not a host `compareTo` method call
`datascript/db.cljc`, four sites — every `.compareTo` call in the
port, plus the one static host compare the same value path depends on
(F-JOLT-CMP, the 0.9.3.1 jolt lane's finding):
- `value-compare`'s `:clj` Comparable clause: `(.compareTo ^Comparable x y)` → `(compare x y)`.
- `cmp-attr-quick`'s `:inline` expansion: `` `(long (.compareTo <Comparable-tagged> a1 a2)) `` → `` `(long (compare a1 a2)) ``.
- `cmp-attr-quick`'s `:clj` body: `(.compareTo ^Comparable a1 a2)` → `(long (compare a1 a2))`.
- `value-compare`'s `:clj` Number clause: `(clojure.lang.Numbers/compare x y)` → `(compare x y)`. Jolt has no static `Numbers/compare` either ("No matching field or method: clojure.lang.Numbers/compare" — hit the moment `value-compare` met a number); `clojure.core/compare` on numbers IS `Numbers.compare` on the JVM (Long.compare for longs, Numbers.compare otherwise), so this is the same identity argument.

Why: jolt's `clojure.lang.Keyword` names `java.lang.Comparable` among
its supers and `(compare :a :b)` answers −1, but the class carries no
`compareTo` method and no registration API reaches jolt's interop
dispatch for an existing class (the adapter lane's evidence:
REPORT-0931-jolt). The port's comparators are the port's own code, so
they speak the vocabulary every seat serves — `clojure.core/compare`
— instead of a host method call one seat cannot. Semantics are
identical by construction: on the JVM, `clojure.core/compare` on a
Comparable IS `(.compareTo x y)` (`clojure.lang.Util/compare`), so
keyword ordering stays Keyword.compareTo's name ordering and string
ordering stays String.compareTo's; on cljs nothing changes (the
`:cljs` arms already used `-compare`/`garray/defaultCompare`).
Census dispositions, no change: `defcomp`'s `java.util.Comparator`
reify and its `(.compare this# …)` are the port calling its OWN
reified comparator object, which jolt already dispatched through to
reach these sites; every other ordering site in the port already
routes through `clojure.core/compare` (int-compare, class-compare,
built_ins, pull_api, pull_parser).
Category: host-quirk workaround (a host method a seat cannot serve),
fixed on the port side per the operator's ruling — the port is our
implementation; vanilla-ds/ is untouched. (db.cljc therefore leaves
the byte-identical list below.)

## Not deltas (verified byte-identical to vanilla-ds at first writing)
built_ins, conn, core, datafy, impl/entity, inline, lru, parser,
pull_api, pull_parser, query_v3, util, pprint. (db left this list
under D4.) query_v3 is upstream's
own newer query engine, carried unchanged; whether the port's drivers
exercise it is a suite-coverage question, not a porting delta.

## D5 — FilteredDB: NO delta — the flagship smoke's failure was a stale classpath (successor lane, 2026-11-06; corrected by measurement)
History, kept in full because two lanes acted on the first read:
the flagship3 lane's JVM smoke failed with "No such var:
db/FilteredDB" at conn.cljc:27, and its STATUS diagnosed a missing
:clj var def in db.cljc (`#?(:clj (def FilteredDB FilteredDB))`),
by analogy with a Datom precedent. Both halves of that diagnosis
fail against the actual trees:
- The port's conn.cljc:27 is a doc COMMENT ("the types are named
  qualified (db/DB)"); the code at conn.cljc:31 and every other
  FilteredDB use in the port (core.cljc:247/261/266-268) already
  splices the name per dialect — `(instance? #?(:clj FilteredDB
  :cljs db/FilteredDB) x)`, `(FilteredDB. ...)` on :clj,
  `(db/->FilteredDB ...)` on :cljs — the F-NBB-MACROS round's
  established shape for these names, imports on :clj included.
  There is no `(instance? db/FilteredDB ...)` form anywhere in the
  port (grep-verified across lib-ds). The smoke's error text names
  the VANILLA conn form; the flagship classpath (its
  logs/proofs/cp-nbb.txt analogue, generated against the old
  equiv-fix tree) resolved a stale port copy.
- The prescribed def is illegal where it was prescribed: in the
  class's home namespace the name is mapped to the class, and
  `(def FilteredDB FilteredDB)` throws "Expecting var, but
  FilteredDB is mapped to class datascript.db.FilteredDB"
  (measured directly — the def broke this lane's clean JVM run at
  db.cljc:1069 until removed; the run's remaining namespaces then
  proceeded). The Datom precedent does not exist either: Datom is
  an eve-deftype, whose expansion interns its name as a var on
  every seat — no manual def was ever written for it.
Disposition: the delta is REMOVED (a comment at the site records
this). Verification, honestly scoped: with the def removed,
datascript.db and datascript.conn now COMPILE on the JVM seat
against eve-release-0931 (the def's throw is gone); the load then
reaches the NEXT, older defect (below), so a full
datascript.core load is not yet claimed. The flagship lane should
regenerate its classpaths against the 0.9.3.1 tree + this
branch's tip and re-run its smoke; if the "No such var" error
recurs there with a verified-current classpath, it reopens here
with that evidence.

## D8 — pull_api's spliced hint in binding-vector position does not read on the JVM (successor lane, 2026-11-06; the db.cljc:707-family throw, found by the clean JVM run)
With D5's illegal def removed, the standalone JVM load of
datascript.core (probe contract.ds-loadprobe) dies READING
datascript/pull_api.cljc:65:35: "Metadata can only be applied to
IMetas" at the binding form `:let [#?(:clj ^Datom :cljs
^db/Datom) datom (first-seq datoms)]`. A non-splicing #? in a
binding vector returns the hinted symbol only if the hint can
attach — here the reader produces a value the binding reader
rejects. The established port shape for this exact spot is the
SPLICED pair — `(defn #?@(:clj [^DB db-with] :cljs [^db/DB
db-with]) ...)` in conn/core, and #?@ at pull_api:224/237/279/291
— but the binding-vector sites were left in the non-splicing form:
pull_api:65/73/97/105/144/296, serialize:99/186/193/194,
core:261's let. (pull_api:19/24/29's arg hints are VANILLA's own
forms — vanilla has them identically and they read; they are not
sites.) This is the same family as the suite-context deftype*
throw the STATUS carried as "db.cljc:707" (the parser/parser-find
namespaces load pull_api through datascript.core; in-suite the
throw surfaced downstream of macroexpansion, misattributed to
db.cljc). Fix shape: rewrite each binding site as
`#?@(:clj [^Datom datom] :cljs [^db/Datom datom])` spliced into the
binding vector. APPLIED (same bank as this entry's update): 13
sites — pull_api:65/73/97/105/144/296, serialize:99 (×2 in one
vector)/186/193/194, core:261 (FilteredDB) and core:89 (Entity —
found by iterating the load probe past each fix; the probe is the
measurement loop: pull_api:65 → core:89 → load OK).
**datascript.core now LOADS on the JVM seat** against
eve-release-0931 (probe contract.ds-loadprobe:
`{:load :ok, :filtereddb-var false}` — the false is D5's point:
no FilteredDB var exists or is needed).

## D6 — F-IFNOOL: the IFn$OOL import and defcomp's invokePrim arm dropped (successor lane, 2026-11-06)
`datascript/db.cljc`: vanilla's ns form carries
`(:import clojure.lang.IFn$OOL)` and defcomp's reify carries an
`IFn$OOL (invokePrim ...)` arm — the import's only use in the file.
Babashka's SCI cannot register that class (it exists in bb's image
but SCI's :import registry has no entry and bb exposes no
registration path), so the ns cannot load on bb at all. Fix: the
import and the invokePrim arm (both defcomp arms, :clj and :cljs —
identical bodies) are dropped. The comparator reify keeps
java.util.Comparator + clojure.lang.IFn/invoke, which is how the
comparators are actually consumed (PSS calls .compare / invokes as
IFn). JVM semantics unchanged: every compare result is identical;
the only loss is the compiler's unboxed invokePrim fast path when a
comparator is invoked through a statically-IFn$OOL-typed site —
there are none in the port. Category: port-layout fix (our
implementation; host quirk NOT absorbed — Eve's bb adapter layer
cannot register a class into SCI, so the port must not name it).

## D7 — patch-tag's primitive tag quoted (successor lane, 2026-11-06; found by the bb proof)
`datascript/db.cljc` patch-tag: vanilla stores the bare symbol
`clojure.core$long` as a defn+ :tag value. That resolves on the JVM
only incidentally — clojure.core's compiled `long` fn class makes
the name a loadable class — and SCI (bb 1.13.226, Eve's bb seat)
cannot resolve it at analysis, so datascript.db fails to load on bb
even after D6 (measured: logs/proofs/bb-ds-behave.log, analysis
error at db.cljc:100). Fix: quote it — `(assoc meta :tag
'clojure.core$long)`. A :tag is a name the analyzer resolves at the
use site; symbols are its conventional form (every source-written
^long hint arrives as one). One site; its sibling
`java.lang.Boolean` is a real class SCI registers, untouched.
Category: port-layout fix, same family as D6 (a name the
interpreter cannot resolve, re-homed to a form every host accepts).

## D2 amendment 2 (multi-host lane — measured by the upstream suite)
Two more sites of the same disease, found by the unchanged upstream
suite (not by the lane's probes):
- `query.cljc sum-rel*`: a bare `aset` filled each merged tuple —
  "No matching method aset found taking 3 args" on every or/or-join
  query (upstream query-or: 3 errors). Now a threaded da/aset reduce.
  (The bare `aset`s in tuple-key-fn are NOT sites: getters-arr/arr
  there are genuine host Object[] scratch holding host fns, consumed
  in-process — vanilla's shape is correct and stays.)
- `db.cljc init-db`: the avet index array was built with
  clojure.core `to-array` (host Object[]) — see the D2 addendum under
  the multi-host addenda below.
- `db.cljc init-db` again, the S2 threading rule: vanilla's three
  `arrays/asort` calls sort the host array in place and discard the
  result (`_` bindings); the port's asort returns a new vector, so
  the sorts silently did nothing and init-db's three indexes came
  out in INSERTION order. Every pull over an init-db-built DB then
  misread its indexes (upstream pull-api: 25 fail / 37 error).
  Threaded now. The transact path never hits these sorts (it conjs
  into sorted sets), which is why the lane's own probes — all
  transact-built — never saw it.

## D2 amendment (same category, more sites — measured during the first query runs)
The first JVM query runs found the same disease at three more sites in
query.cljc, all fixed the same way (functional da threading):
- the two int-getter :clj branches (`(aget ^objects tuple idx)` →
  `(da/aget tuple idx)`);
- `-call-fn`: static-args/tuples-args were built and per-call filled by
  `da/aset` used as a discarded-result mutation statement, and read back
  with core `aget` ("No matching method aget found taking 2 args" in the
  predicate-call path). Both are now threaded reduces; the per-call fill
  builds the argument array functionally — vanilla mutates a shared
  static-args, the per-call result is the same.
Measured motive chain: `(d/q ...)` first threw "PersistentVector cannot
be cast to [Ljava.lang.Object;" at -collect-tuples; after D2's first two
sites, name queries answered (#{[Bo] [Ada]}) and predicate calls exposed
the -call-fn sites. One subtlety the port had to learn by measurement:
at predicate/function-call time the production relation's tuples are
raw Datoms and its attrs map symbols to Datom FIELD NAMES
({?e e, ?a v}), not tuple indexes — vanilla's fill reads them with
`get` (Datom's ILookup answers field names), and an early version of
this port that substituted da/aget (= nth) there failed with
"String cannot be cast to Number". The fill keeps vanilla's
`(#?(:cljs da/aget :clj get)` read; da/aget is used only where the
tuple is provably a substrate array. After all D2 sites: full JVM smoke
green — q #{[Bo] [Ada]}, pred q #{[41]}, entity, datoms 4.

## Dependencies (vendored, not part of DataScript)
- extend-clj 0.1.0 (io.github.tonsky/extend-clj, MIT (c) 2023 Nikita
  Prokopov), DataScript's second pinned dependency, is vendored verbatim
  at lib-ds-deps/extend_clj/core.cljc — NONE.

## Types — AS IMPLEMENTED (2026-11-06 evening, JVM seat verified)

- **T1 Datom → eve-deftype [e a v tx idx]** (db.cljc): vanilla's
  mutable `_hash` cache dropped (hash-datom computes on demand, the
  PSS E2 pattern); `idx` fixed at construction — `datom-set-idx`
  throws, and its one caller (serialize's freeze) uses a
  datom→position side table. All vanilla protocol groups carried;
  prints in the vanilla `#datascript/Datom [...]` form.
- **T2 DB → eve-deftype [schema eavt aevt avet max-eid max-tx
  rschema]**: the three unencodable fields are gone — `:hash` (host
  atom cache; `hash-db` now computes vanilla's combine-hashes formula
  on demand, and the transact loop's two `(assoc :hash (atom 0))`
  reset sites are deleted) and `:pull-patterns`/`:pull-attrs` (LRU
  caches, now namespace-level vars in datascript.db — pure
  memoization; pull_api's two read sites rewired). Record semantics
  as groups: ILookup (via a `db-lookup` helper — Eve's deftype cannot
  self-call valAt across arities), Associative (assoc =
  reconstruction via a `db-assoc` helper), IPersistentCollection /
  IEditableCollection / ITransientCollection / Reversible / IHashEq;
  IDB/ISearch/IIndexAccess bodies carried verbatim. `map->DB` is a
  port fn of the same name/shape (extra keys ignored — vanilla's
  construction maps pass unchanged); `with-schema`, the codebase's
  one `(assoc db …)` site, reconstructs via `->DB`. FilteredDB stays
  a host defrecord (ephemeral query view holding a host fn).
- **T3 comparators**: cmp-datoms-eavt/aevt/avet registered as
  `:datascript.db/cmp-eavt` / `cmp-aevt` / `cmp-avet` in the PSS
  registry at db.cljc load; every index construction (empty-db,
  init-db, serialize thaw) passes the keyword. Verified:
  `(.-comparator (:eavt db))` = `:datascript.db/cmp-eavt`.
- **Measured after conversion (JVM)**: full smoke green (q, pred q,
  entity, pull, hash, keyword field reads). **Residence PROVEN**:
  a whole DB built inside a swap lives in a shared disk Eve atom; a
  second process derefs a real datascript.db.DB, queries it
  identically, its seal matches the writer's exactly (72 blocks),
  and it transacts a third entity against the resident DB.

## Types — the port's center of gravity (design measured 2026-11-06; implementation is the next step)
- **Measured fact T0.** Vanilla DataScript loads and runs on Eve's JVM
  seat as host code, but a host-record DB is not an Eve value: it cannot
  be an Eve atom's value or cross the codec. And a bare eve-deftype does
  NOT emulate a record (toy measured on the JVM seat: `(:a toy)` → nil,
  `(get toy :b)` → nil, `(assoc toy :a 9)` → ClassCastException).
- **T1 — Datom → eve-deftype** (db.cljc): Datom must be slab-resident
  (it lives inside the index BTSets). Vanilla's mutable idx/_hash host
  caches drop: _hash computes on demand (the PSS E2 pattern); idx is set
  at construction where vanilla assigns it before publication.
- **T2 — DB / FilteredDB → eve-deftype with record semantics as
  protocol groups**: ILookup valAt answering the ten field keywords; the
  codebase's single (assoc db ...) site (db.cljc:1097) rewritten to
  constructor reconstruction; map->DB kept as a port-defined constructor
  fn of the same name/shape. Keyword field reads number 17 across the
  codebase — all answered by the ILookup group. TxReport and parser's
  records stay HOST defrecords (per-process values; parser's deftrecord
  forms are byte-identical so far and are the many-field-defrecord
  surface the upstream suite will exercise per host).
- **T3 — index comparators as registered keywords**: db.cljc builds
  every index via (set/sorted-set* {:cmp cmp-datoms-*}); host fns cannot
  cross the codec. The port registers the three datom comparators in the
  PSS registry (:datascript.db/cmp-eavt, /cmp-aevt, /cmp-avet) and
  passes keywords. Heap behavior unchanged; the DB then encodes, and
  DBs built on different hosts have field-identical indexes, so
  hash/equiv/seal can agree — the property the Phase-2 drivers test.

## Open (updated 2026-11-06 evening)
- The port LOADS and ANSWERS on the JVM seat (host-typed DB): empty-db,
  db-with, datoms=4 for the 2-entity smoke, name query, entity lookup —
  with the substrate and D1/D2/D3 applied. The T1/T2/T3 conversion is
  what makes the DB an Eve value; until it lands, the shared-atom
  drivers (the lane's Phase-2 acceptance) cannot run, and no residence
  claim is made.
- Upstream's own DataScript test suite has not been pulled into the lane
  yet (vanilla-ds carries sources only); its per-host rows are Phase 2's
  validation, after the port loads and behaves under the Lane-M drivers.

## D2 addendum (2026-11-06 ~2:12 PM ET) — the -call-fn site, and a repair
The D2 rewrite has a THIRD site, -call-fn (~line 491): static-args /
tuples-args are built by a single reduce threading da/aset results
(vanilla mutates two preallocated arrays in a dotimes), and both
per-call fills build the argument array functionally. As banked, the
defn was one closing paren short — its second branch wraps the fill
in an inner let where vanilla's dotimes sits directly in the fn body,
so the tail needed six closers and had five; the file did not read
(EOF starting at the defn) and the port had never loaded. Repaired
2026-11-06 (one paren; verified by full-file edamame parse + the JVM
reader via the ds-behave run). Tooling note: the repair was a plain
text edit because the installed `vs src` is a stale build whose src
verb routes to a deleted JS leg — recorded here so the deviation from
the repo's structural-edit rule is explicit, not silent.

## Multi-host lane addenda (2026-11-06, lane-m/datascript-multihost)

The `vs src` deviation above covers this lane's edits too — same stale
build. All edits below were plain text edits under that recorded
deviation.

- **DS2 FIXED** — the fix is PORTING.md (PSS substrate) **T6**:
  `Iter`/`ReverseIter` implement `java.lang.Iterable`. Root cause was
  in the substrate, not DataScript: an `Eduction`'s iterator calls
  `RT/iter` on its source; RT/iter's fallback for a type that is
  neither Iterator nor Iterable is `iter(seq x)`, and the port's Iter
  is a Seqable ISeq whose seq returns itself — infinite recursion.
  After the fix, `:age34` answers [Ivan Oleg] and the whole behavior
  probe is green.
- **DS3 CLOSED, not a defect** — the port's join answers correctly in
  both find forms (set form and tuple form). The ClassCastException
  came from the probe sorting one heterogeneous [name age] tuple;
  stock Clojure 1.12.0 with no Eve and no port throws the identical
  exception on `(sort ["Ivan" 34])` — verified directly. The probe
  row was corrected to the set find spec.
- **T2 addendum (from upstream test-protocols)**: DB gained a
  `clojure.lang.Seqable` group — map-entry seq over its seven fields
  in declaration order, the record behavior vanilla inherits from
  APersistentMap; its absence surfaced as AbstractMethodError on
  `seq()` — and an empty `clojure.lang.Counted` marker group (T5
  pattern: the collection group already emits the count method; the
  marker makes RT.count dispatch to the datom count, 6 on the
  test-protocols fixture, not the 7 field entries). DB also gained
  an eighth field, `meta`, with IMeta/IObj groups — the record's
  meta slot; `(empty db)` cast to IObj without it. The field is
  ignored by equiv/hash/keys exactly as a record's __meta is, and
  every ->DB site passes it through. Recorded divergence that
  STANDS by T2's design: `(keys db)` lacks vanilla's three
  host-cache fields (:hash :pull-patterns :pull-attrs — here they
  are namespace-level Eve vars), and `(map? db)` is false — an
  eve-deftype presents Eve's protocol groups, not host
  IPersistentMap identity. Upstream test-protocols fails on the
  keys assertion for exactly that reason. Upstream test-db also
  reads vanilla's `(.-hash db)` cache field directly — same T2
  family, stands as a divergence (hash-db computes the same value
  on demand; there is no field to read).
- **T7 dependency**: DataScript's `find-datom` requires the
  substrate's new `clojure.lang.Sorted` group (PORTING.md T7): its
  `(.comparator ^clojure.lang.Sorted set)` hard-cast threw
  ClassCastException on every find-datom call (12 upstream-suite
  errors). The comparator must be dual Comparator+IFn — DataScript
  calls it as a function, `(cmp to datom)`.

## F-NBB-MACROS — the port's macros and type names on the interpreted CLJS seat (nbb)

John's ruling (Nov 6, ~6:26 PM): the DataScript port is OUR
implementation; the vanilla tree is a read-only provenance reference.
NBB's interpreter harvests a .cljc's macros with the :cljs feature
set only, so the port's macro definitions sitting under :clj (the
copied layout) were invisible to it. The fix re-homes the PORT's
macro definitions where the :cljs read sees them. Deltas, all in
the port tree (lib-ds/, lib-ds-deps/) unless marked Eve-side:

- **Macro definitions duplicated into :cljs arms** (the :clj arm is
  untouched): datascript.db's if-cljs, defn+, defrecord-updatable,
  combine-cmp, case-tree, int-compare, defcomp, validate-attr,
  validate-val; datascript.util's log, raise, if+, cond+, some-of
  and the if+ helper defs; datascript.parser's deftrecord;
  datascript.serialize's array, dict; extend-clj.core's
  deftype-atom. Where a macro body is JVM machinery, the :cljs arm
  carries the cljs computation instead: defrecord-updatable's
  :cljs body computes only make-record-updatable-cljs (the clj
  arm's deftype* surgery has no meaning in a cljs macro read);
  combine-cmp's :cljs body uses the literal 0 for (num 0)
  (clojure.core/num is JVM-only); util log's :cljs arm drops the
  System/getProperty gate (the runtime *debug* gate remains).
- **cljs-env?** (db.cljc, extend-clj): the :cljs arm answers true
  literally. Measured: in nbb's SCI macro environment &env is {}
  — the :clj test (boolean (:ns env)) answers false there and
  picks JVM arms (Integer/compare). The :cljs arm is only ever
  read by a cljs macro harvest, where expansions are always into
  cljs. defn+'s helper patch-tag gains a :cljs arm writing the
  cljs tag names (its JVM arm names clojure.core$long /
  java.lang.Boolean as code, unresolvable in the cljs read);
  case-tree's helper -case-tree and make-record-updatable-cljs
  gain :cljs arms for the same reason.
- **Type names are named qualified on :cljs, never referred and
  never interned.** Measured on nbb/SCI: a deftype/defrecord name
  is a class entry, not a var — a consumer's :refer [DB] fails
  ("DB does not exist"); interning the name as a var in the home
  namespace (def DB DB) answers the refer but shadows the class
  path, and (instance? db/DB x) then dies ("Right-hand side of
  'instanceof' is not callable"), in the home namespace too. So:
  the :cljs refers of type names in conn, core, pull-api,
  serialize, query, query-v3 are removed, and at each use the
  name is dialect-spliced — hints and defn heads via the spliced
  pair (defn #?@(:clj [^DB db-with] :cljs [^db/DB db-with]) ...),
  vanilla's own seqable? pattern (the non-splicing #? returning a
  metadata-carrying symbol is rejected by the JVM reader:
  "Metadata can only be applied to IMetas"); instance? first
  arguments and extend-protocol targets via
  #?(:clj X :cljs dp/X); :tag values likewise; constructors via
  the factory var on :cljs ((db/->FilteredDB ...),
  (dp/->Constant ...)). The :clj faces keep vanilla's imports
  and bare names exactly.
- **Entity's JS-Map face** (impl/entity.cljc): vanilla's deftype
  Object group carries equiv + the JS-Map methods (keys, entries,
  values, has, get, forEach, key_set, entry_set, value_set); the
  interpreted seat's deftype takes only toString on Object. equiv
  was already IEquiv's; the JS-Map methods move to guarded
  prototype assignments after the form (where compiled
  ClojureScript puts a deftype's Object extras anyway), guarded
  by (.-prototype Entity) — nil on the interpreted seat, whose
  iteration path is Eve's seqs. The existing ITER_SYMBOL patch
  gains the same guard. entity's (:refer-clojure :exclude
  [keys get]) drops `get`: SCI's deftype expands through core
  get in the user namespace and fails on the exclusion; the
  file names no var get (its JS-Map get is a prototype string).
- **db-entries** (db.cljc, port-written T2 code): the entry
  constructor is dialect-spliced — clojure.lang.MapEntry on
  :clj, cljs.core/MapEntry on :cljs.

Eve-side completions this round lives on (adapters/types/nbb,
src/eve/deftype_proto): the goog shim (goog.exportSymbol,
goog.array/defaultCompare, goog.getUid); the SCI class
registration of the cljs primitive type names (number, boolean,
string, array, object, function) so ^number-style tags analyze;
pr-writer, pr-sequential-writer, pr-str*, es6-iterator,
es6-entries-iterator and the IDerefWithTimeout protocol interned
into the core namespace; a working cljs.reader tag-parser
registry (register-tag-parser! + a read-string that consults
it); ES6Iterator/ES6EntriesIterator/ITER_SYMBOL stand-ins;
clojure.lang.Sorted dropped on cljs (its one consumer reads the
field there); a bare Object group's hashCode/equals normalized
in the cljs translation (hashCode's home is IHash via IHashEq;
equals is mined to IEquiv); clojure.data/EqualityPartition and
clojure.data/Diff groups dropped on nbb (its bundled
clojure.data is a shim exporting only diff, and a classpath
clojure.data cannot shadow the bundle — diff answers by its
generic algorithm there).
