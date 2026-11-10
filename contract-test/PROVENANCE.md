# PROVENANCE — the library under test

**Library:** persistent-sorted-set — a B-tree based persistent sorted set for
Clojure/ClojureScript, by Nikita Prokopov (Tonsky). Almost a drop-in replacement
for `clojure.core/sorted-set`; supports custom comparators, fast iteration, and
efficient slices. It is the operator's own October 2026 pick as the leading
candidate for Eve sorted collections — a real community library, not a structure
invented for this test.

**Upstream:** https://github.com/tonsky/persistent-sorted-set
**License:** MIT, Copyright (c) 2019 Nikita Prokopov (LICENSE copy in vanilla/).
**Version pinned:** tag **0.2.3**.

## Which upstream form was ported, and why

Upstream ships two implementations of the same design:

- **JVM form** (`src-clojure/me/tonsky/persistent_sorted_set.clj` +
  `src-java/me/tonsky/persistent_sorted_set/*.java`): thin Clojure wrappers over
  Java classes. Not portable .cljc code and not a deftype implementation — it
  cannot be what an "external .cljc library" means, so it was not ported.
- **CLJS form** (`src-clojure/me/tonsky/persistent_sorted_set.cljs`, 1,097 lines,
  plus `src-clojure/me/tonsky/persistent_sorted_set/arrays.cljc`, 133 lines):
  the complete B-tree in the portable Clojure dialect — `deftype BTSet`,
  `deftype Node`, `deftype Leaf`, iterator types, and an array-operations
  namespace (`arrays`) abstracting the host array substrate. **This is the form
  ported.** It is the library's vanilla deftype implementation.

Verbatim reference copies of both ported files are banked in `vanilla/`
(`vanilla/persistent_sorted_set.cljs`, `vanilla/arrays.cljc`), fetched from the
0.2.3 tag on 2026-11-06.

## What "the port" is

- `lib/me/tonsky/persistent_sorted_set/arrays.cljc` — the arrays namespace with
  its substrate re-based from host arrays (JS Array / Java Object[]) to Eve
  vectors. Same namespace name, same function names, same contracts.
- `lib/me/tonsky/persistent_sorted_set.cljc` — the library itself. Same namespace
  (`me.tonsky.persistent-sorted-set`), same public API (`sorted-set`,
  `sorted-set-by`, `conj`, `disj`, `slice`, `rslice`, `seek`, `from-sorted-array`,
  `from-sequential`), same B-tree algorithm, same path encoding. The persistent
  types (BTSet, Node, Leaf) are `eve-deftype`s so instances are slab-resident Eve
  values; the iterator types (Iter, ReverseIter) remain host `deftype`s, exactly
  as in vanilla, because they are per-process runtime cursors that never persist.

Every difference from the vanilla text is listed and categorized in PORTING.md.

## upstream-pss/ — pinned suite sources (vendored, 2026-11-06)

`upstream-pss/` is a PLAIN VENDORED COPY of the upstream repo at tag **0.2.3**
(commit db09ff6d0c478f60d97cb5f16ce234935506f2ea), checked in as ordinary files
so this branch is self-contained (it was briefly an embedded git repo/gitlink;
converted in the bank-tidy commit). It carries the upstream test suite the
Phase 1 closeout runs unchanged: `test-clojure/me/tonsky/persistent_sorted_set/test/`
(core.cljc, small.cljc, stress.cljc — portable; storage.clj excluded, see
STATUS.md). Upstream LICENSE (MIT) is inside the copy.
