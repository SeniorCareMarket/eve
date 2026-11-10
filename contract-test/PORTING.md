# PORTING — every delta from vanilla, categorized

Vanilla = persistent-sorted-set 0.2.3, CLJS form (see PROVENANCE.md; reference
copies in `vanilla/`). The port aims at "almost vanilla": the B-tree algorithm,
the path encoding, the namespace names and the public API are unchanged. Each
delta below is listed once, with its category and reason. Anything not listed
here is byte-identical in structure to vanilla (modulo the mechanical
constructor/protocol renames in T1/T2, which are listed as rules, not per-site).

## S — substrate (host arrays → Eve vectors), `arrays.cljc` + construction sites
- **S1.** The arrays namespace is re-based on Eve persistent vectors: an
  "array" is an Eve vector. `make-array` → n nils; `aget` → `nth`; `alength` →
  `count`; `aconcat` → `into`; `amap` → `mapv`; `asort` → sorted copy;
  `into-array`/`array` → `vec`; `alast` → last `nth`; new `aslice` for
  vanilla's `(.slice arr …)` call sites (= `subvec`).
- **S2.** Vanilla mutates freshly-allocated arrays for effect (`acopy`,
  `arr-map-inplace`, build-then-fill). Persistent vectors can't be mutated, so
  the construction helpers (`cut-n-splice`, `merge-n-split`, `acopy`,
  `aset`) build and return the same final array functionally. Vanilla only
  ever mutates arrays before publication, so no observable semantics change.
- **S3.** `aclone` is the identity: persistent vectors need no defensive copy.

## N — numbers (JS 32-bit path machinery removed)
- **N1.** Vanilla encodes tree paths in JS numbers and carries a double-based
  fallback past 2^31 (`max-safe-path`, the `factors` table, branches in
  `path-get`/`path-set`/`path-same-leaf`, `Math/floor` in `path-str`). Eve
  hosts have 64-bit (or arbitrary-precision) integers, so the port uses plain
  long bit-ops at every level. Bound: paths stay exact while the set holds
  fewer than ~2^31 elements at branching 32 — far above anything this library
  is used at. `max-len`/`min-len` are computed with bit shifts instead of
  `js/Math.pow`.

## T — types and protocols
- **T1.** `BTSet`, `Node`, `Leaf` — the values that must live in slab memory —
  are `eve-deftype`s. `Iter`/`ReverseIter` (per-process cursors, never
  persisted) stay host types via `eve.deftype/deftype`, as in vanilla.
  Constructors `(X. …)` become `(->X …)` throughout (Eve's constructor idiom).
- **T2.** Protocol names are the JVM interface names, which eve-deftype
  translates per host (the idiom of Eve's own user types, e.g.
  `eve.deftype.rb-tree`): `ICollection`/`-conj` → `IPersistentCollection`/`cons`,
  `IEquiv`/`-equiv` → `IPersistentCollection`/`equiv`, `ICounted` → `Counted`,
  `ISeqable` → `Seqable`, `ILookup` → `ILookup`/`valAt`, `ISet`/`-disjoin` →
  `IPersistentSet`/`disjoin`, `IHash`/`-hash` → `IHashEq`/`hasheq`,
  `IMeta`/`IWithMeta` → `IMeta`/`IObj`, seq types get `ISeq`/`IReduce`/
  `IReversible` in place of the cljs `ISeq`/`INext`/`IReduce` family.
- **T3.** Vanilla's `Chunk`/`IChunkedSeq` (a seq performance device) is not
  ported; seqs are element-wise. No semantic difference.
- **T4.** Vanilla's transient protocols are identity shims (`-as-transient`
  returns `this`; `-conj!` is the persistent `conj`; `-persistent!` is the
  identity). The port implements them with exactly those semantics
  (`IEditableCollection`/`ITransientCollection` on BTSet), and
  `IReduce`/`IReduceInit` delegating to the seq (the JVM form implements
  them; upstream's `test-reduces` calls `.reduce` directly on `:clj`).
  Revised during the upstream-suite pass: the first cut omitted the
  transient groups, and upstream's suite is the authority — the shims are
  part of the library's tested contract. Two JVM class-model limits bind
  the identity transient on the `:clj` seat, both measured as
  duplicate-method ClassFormatErrors when attempted: `ITransientSet`
  cannot also live on BTSet — its `contains`/`get` duplicate
  `IPersistentSet`'s signatures and its `disjoin` shares name+params with
  an incompatible return type (the reason stock sets answer `transient`
  with a separate object) — so `disjoin!` refuses on the JVM seat while
  `conj!`/`persistent!` work on the set itself. And one upstream test
  remains JVM-form-only by upstream's own design: `iter-over-transient` is
  `#?(:clj ...)`-gated and demands the Java form's edit-tracked transients
  (iterators that throw on mutation during iteration); the CLJS form never
  runs it, and the port does not grow that machinery.
- **T5.** `ICloneable`/`-clone` dropped (it existed to copy the `_hash`
  field, E2). The internal `IIter` protocol is kept; `js/Error` throws become
  `ex-info` (X1).

## E — value encoding (what Eve's slab can carry)
- **E1.** The default comparator is stored as `nil` in the `comparator` field
  and unwrapped to `clojure.core/compare` at use sites (`cmp-of`). A host
  function is not a slab value; vanilla stores `compare` itself. Custom
  comparators are stored as-is and work in-heap; like any fn-valued field
  they are the user's responsibility across the slab boundary. Observable
  behavior of the default set is unchanged.
- **E2.** Vanilla caches the set hash in a `^:mutable _hash` field via cljs
  `caching-hash`. The port drops the field and computes `hasheq` on demand.
  First cut: an unordered SUM of element hashes — equal sets hashed equal
  to each other but NOT to stock's number. Amended (multi-host lane,
  2026-11-06): `hasheq` is `eve.core/hash-unordered-coll` over the seq —
  the stock Murmur3 unordered hash (sum + count mix), the same value on
  every Eve host AND stock's value. The amendment was forced by
  DataScript's `hash-fdb`, which mixes `(hash-unordered-coll datoms)`
  against `(hash eavt-index)`; upstream test-filter-db failed on the
  mismatch. A library hash must be THE hash, not a self-consistent one.
- **E3 (addition, not a change).** `BTSet` implements `eve.deftype-proto.data/
  IPlain`: the plain conversion a deref/boundary crossing answers is a host
  sorted set of the plain elements — the same choice Eve's own `SortedSet`
  user type makes. This is Eve's extensible value encoding surface for user
  types; vanilla has no equivalent because vanilla never crosses a slab.

## F — field access
- **F0 (deliberately unchanged).** Vanilla reads fields of *other* instances
  through untyped parameters — `(.-keys next)`, `(.-root set)`,
  `(.-pointers node)`, `(.-comparator set)`, … — and the port keeps every one
  of those reads verbatim. This is the shape the identity audit is measuring
  (cf. S-RRB, where the same shape in Eve's own rrb nodes file fails to load
  on the jank seat). Where the port needed a NEW accessor of its own
  (`cmp-of`), it reads the field the vanilla way, `(.-comparator set)`.

- **T5.** The iterator types carry an explicit `clojure.lang.Sequential`
  marker group (no methods). Measured on the JVM seat: a user `ISeq` type
  whose class does not also implement the `Sequential` marker fails EVERY
  host equality in the right-operand position — `(= (range 10 20) (seq s))`,
  `(= [10 11 12] (seq s3))`, list and lazy-seq left operands all false —
  while element-wise walks, counts, and `(= (seq s) (range 10 20))` are all
  true (host `equiv` gates on `instanceof Sequential` before walking).
  Vanilla CLJS has no such marker (its `ISeq` is the whole contract), so
  nothing in the vanilla text asks for it. Filed as finding F13: the marker
  is part of the JVM seat's seq contract and belongs to Eve's deftype
  translation vocabulary, not to each library's source.

- **T6.** The iterator types (`Iter`, `ReverseIter`) carry an explicit
  `java.lang.Iterable` group — `(iterator [this] (clojure.lang.SeqIterator. (.seq this)))`,
  the same one-liner Eve's own types use (eve.array, eve.dataset). Found by
  DataScript DS2 (2026-11-06, multi-host lane): any consumer that reaches
  the iterator through `clojure.lang.RT/iter` — `clojure.core.Eduction`'s
  iterator does exactly that — loops forever on a Seqable ISeq that is not
  Iterable, because RT/iter's fallback for an unknown type is
  `iter(seq x)` and this type's `seq` returns itself. Vanilla's JVM form
  implements Iterable (its iterators are java.util.Iterators); the CLJS
  form's ISeq-only contract is not sufficient on the JVM seat. Category:
  host-quirk workaround, same family as T5/F13 — a JVM-seat contract the
  CLJS text does not ask for. Measured: `(eduction (map identity) slice)`
  StackOverflowError before, exact values after; DataScript's -search
  eduction branches (every constant-bound pattern) unblocked.

- **T7.** `BTSet` carries a `clojure.lang.Sorted` group. Vanilla's JVM
  form implements Sorted, and DataScript's own `find-datom` hard-casts
  the index set to it on the :clj branch
  (`(.comparator ^clojure.lang.Sorted set)`, db.cljc) — 12 upstream-suite
  errors, all ClassCastException BTSet→Sorted. `comparator` returns a
  java.util.Comparator over the set's comparator (registered keywords
  resolve through the E3 registry first); `entryKey` is the identity
  (a set's entries are its keys); `seqFrom` rides the slice/seek
  machinery. (Clojure 1.12's Sorted has exactly three methods —
  comparator, entryKey, seqFrom.) Category: host-quirk workaround,
  same family as T5/T6.

## V — vocabulary (Eve's core routing for Eve values; added on measured evidence)
Vanilla's bodies call cljs.core functions on the set itself (`(seq this)`,
`(count other)`, `(contains? this %)`, `(set? other)`, `(rseq iter)`). On
Eve seats, host core functions do not reliably route to Eve types: bb's SCI
`count`/`conj`/`seq` throw on a SciType and its `get` silently answers nil;
`(set? btset)` and `(satisfies? IPersistentSet btset)` are both false there.
Measured per seat (see STATUS.md Findings F1–F3), the port therefore speaks
Eve's vocabulary inside the library wherever an operation touches an Eve
value. Semantics are unchanged — the JVM reference values (including hash
-177922471) are identical before and after.
- **V1.** `hasheq`, `print-btset`, `-plain`: `(e/seq this)` for core `seq`.
  `IFn/invoke`: `e/get` for core `get`. `BTSet/rseq`: `e/rseq` for core
  `rseq` (core rseq casts the iterator to the host Reversible interface,
  which a SciType is not).
- **V2.** `equiv` follows Eve's own user type (eve.deftype.rb-tree's
  SortedSet) exactly in shape: gate `(or (set? other) (instance? BTSet
  other))`, count via `e/count`, membership walk via `e/seq` + `e/contains?`.
  With the vanilla gate, `(= btset btset)` for independent instances was
  FALSE on bb; with V2 it is true on JVM and bb. (What no equiv body can fix
  on bb: SCI `=` never consults it for a mixed Eve-type/host pair — F2.)

## U — the 0.3.1 upgrade (Phase 2 substrate step, 2026-11-06)
Phase 1 pinned PSS 0.2.3. DataScript 1.8.1 (Phase 2) pins PSS **0.3.1**,
so the port was upgraded to 0.3.1's CLJS form (banked:
vanilla/persistent_sorted_set_031.cljs; its arrays.cljc is byte-identical
to 0.2.3's, so the arrays port is untouched). The entire 0.2.3→0.3.1 CLJS
delta is: from-sorted-array arities, sorted-set*, settings.
- **U1 (Eve-surface adaptation).** `from-sorted-array` gains the
  ([cmp arr _len]) and ([cmp arr _len _opts]) arities delegating to the
  existing body; _len is advisory and _opts (0.3.x storage settings) is
  accepted-and-ignored, as the 0.3.1 CLJS form ignores it.
- **U2 (Eve-surface adaptation).** `sorted-set*` (opts :cmp/:meta) added,
  constructing through normalize-cmp like sorted-set-by.
- **U3 (NONE).** `settings` added with vanilla's exact CLJS answer
  {:branching-factor max-len :ref-type :strong}.
- **E3 (Eve-surface adaptation).** Comparator registry:
  `register-comparator!` stores a fn under a keyword; a set's comparator
  field may hold that keyword (a slab value on every host) and cmp-of
  resolves it at each use site. Motivated by DataScript: its three index
  comparators are host fns, which cannot cross Eve's codec; as registered
  names they encode, decode, and compare identically on every host.
  Verified: sorted-set* {:cmp :rev} (registered) iterates (3 2 1); the
  Phase-1 reference hash is unchanged after the upgrade (-177922471).

## X — miscellaneous
- **X1.** `js/Error.` → `ex-info` (portable throw).
- **X2.** `pr-str*`/`pr-sequential-writer` (cljs printing internals) →
  an explicit `print-btset` producing the identical `#{…}` text, wired
  through `IPrintWithWriter` + `Object/toString` (Eve's user-type idiom).
- **X3.** `sorted-set-by`/`from-sorted-array` normalize the comparator per E1.
