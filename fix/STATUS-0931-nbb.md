# STATUS — fix/0931-nbb (F-NBB-GOOG) — Lane M, Eve 0.9.3.1

## The defect
F-NBB-GOOG: a vanilla library's ClojureScript face requires Google Closure
namespaces — DataScript 1.8.1's `datascript.db` requires `goog.array` — and
Eve's nbb adapter roots provide no goog shim, so the library cannot load on
Eve's nbb seat: "Could not find namespace: goog.array".
Evidence: contract-test/REPORT-FLAGSHIP.md (six1) on atom.broker branch
lane-m/datascript-flagship2. John's ruling: the fix lives in Eve's host
adapter layer; the library stays untouched.

## Census (port read-only: wt/datascript-multihost2/contract-test/lib-ds)
- goog namespaces referenced by the port's :cljs face: exactly one —
  `goog.array` (datascript/db.cljc:3).
- goog.array vars referenced: exactly one — `defaultCompare`
  (datascript/db.cljc:377, :436, :512).

## Plan
1. New adapter root `adapters/goog/nbb/src` carrying `goog/array.cljs`
   (ns goog.array) implementing the census set faithfully; add the root to
   hosts.edn's :nbb row.
2. Proofs on nbb with the fixed Eve: (1) require datascript.db/core;
   (2) create-conn/transact/query smoke; (3) flagship nbb leg;
   (4) Eve nbb seat probes unregressed (bb test:nbb subset).
3. Report: fix/REPORT-0931-nbb.md. F8 (nbb SCI deftype self-construction)
   is a different mechanism — noted if it intersects, not fixed here.

## Log
- Worktree + branch created off origin/main (0.9.3). Census done (above).
- Repro BEFORE the fix (fix/logs/repro-unfixed.log): nbb with Eve's :nbb
  roots + port lib/lib-ds: (require 'datascript.db) → exit 1,
  "Could not find namespace: goog.array" — the flagship defect, verbatim.
- goog shim implemented: adapters/goog/nbb/src/goog/array.cljs
  (defaultCompare = Closure's own definition); hosts.edn :nbb row gains
  adapters/goog/nbb/src; adapters/README.md gains the goog feature row;
  CHANGELOG Unreleased entry. Semantics verified against Node's own
  evaluation of Closure's definition: numbers/strings/bools/arrays all
  [-1 1 0] (fix/logs/p1-require.log) — the shim itself is correct.
- Finding past goog #1 (load order, not a code fix): with the shim in
  place, a bare (require 'datascript.db) fails analysing the sorted-set
  lib: eve's types adapter emits eve.process-slab/cljs-sio references into
  deftype expansions before eve.process-slab has loaded
  (fix/logs/exp-b.log). The seat's own consumption pattern loads the slab
  first — eve.runner.nbb-main and the flagship entry both require
  eve.process-slab up front; with it pre-required, analysis proceeds
  (fix/logs/exp-a.log: (resolve 'eve.process-slab/cljs-sio) → true).
  Proofs are therefore run the seat's way; the bare-require result is
  recorded in the report for honesty.
- Finding past goog #2 (F-NBB-SORTED — fixed in this lane, Eve-side):
  eve-deftype's CLJ→CLJS table (src/eve/deftype_proto/proto_map.cljc) had
  NO clojure.lang.Sorted entry — every sibling interface is mapped, split,
  or marked clj-only — so BTSet's Sorted group reached nbb's SCI
  untranslated: "Protocol not found: clojure.lang.Sorted". Census: Sorted
  is the ONLY unmapped interface name across the port's deftype forms.
  Fix: one table entry → cljs ISorted (seqFrom → -sorted-seq-from;
  comparator and entryKey drop with :cljs-name nil — on cljs the
  comparator is a field read, (.-comparator set) at datascript.db:1271,
  and entryKey is the identity function; the drop follows the table's
  ITransientSet get/contains pattern).
- Next: proof 1 rerun the seat's way (slab first), then proofs 2–4.
- CONTINUATION (Lane M, second session): state adopted — both fix
  commits' diffs verified firsthand (goog shim + hosts.edn row;
  proto_map Sorted entry). Proof drafts p1-seatway/p2-smoke adopted.
- Proof 1 (seat's way) ALREADY RAN in the prior session
  (fix/logs/p1-seatway.log): past goog and past the sorted-set
  analysis, it stops at a THIRD wall — F-NBB-TAG: datascript.db's
  :cljs face declares (defn ^number hash-datom [d]) at db.cljc:162
  and nbb's SCI analysis cannot resolve the primitive tag `number`
  ("Unable to resolve symbol: number", via cljs.core/defn).
  Control (fix/logs/exp-c-stocktag.log): the bare shape
  (defn ^number f [x]) fails the same way standalone — a stock
  nbb/SCI tag-resolution gap the port's vanilla cljs face assumes
  away. Seat-side fix to be designed in Eve's nbb adapter/completion
  layer, as with the sibling seats' tag fixes; the port stays
  untouched. Census of the port's primitive tags comes first.
- Next: push this branch (prior session never pushed), tag census,
  F-NBB-TAG fix design, then proofs 1–4.
- Branch PUSHED (continuation): this box's git credential 403s on
  SCM/eve writes (the standing route fact) — the branch was created +
  pushed through the GitHub connector (push-files) and verified on the
  fetched ref: tree diff vs local HEAD = 0 lines. Later banks push the
  same way.
- F-NBB-TAG SOLVED at probe scale (fix/probes/): position census —
  arg-position tags resolve, name-position (def/defn) tags throw;
  boolean/array/symbol resolve unaided (real cljs.core vars), number/
  string/object/function/bigint/undefined do not (stock probe). Dead
  ends, banked so they are not retried: sci eval-string (forks its
  context), in-ns+def in a loaded file (def lands in the file's own
  ns — verified via ns-interns). Working mechanism: plain
  (intern 'clojure.core tag tag) from any loaded namespace — sci keeps
  its core as clojure.core — after which a later-loaded namespace's
  (defn ^number ...) and (def ^number ...) analyse and run
  (probe tagmain11: :tagmain11-ok 3 42).
- F-NBB-TAG FIX implemented: adapters/types/nbb/src/eve/types.cljs
  interns the six missing primitive tags at load, each resolve-guarded;
  CHANGELOG entry added. types.cljs rides every Eve load on nbb
  (eve.deftype-proto.macros requires eve.types), so a consumer loading
  Eve first — the seat's pattern — gets the tags before its library.
- Next: proof 1 (seat's way) rerun, then proofs 2–4.
- Proof 1 rerun with the tag fix (fix/logs/p1-seatway2.log): PAST
  db.cljc:162 — the ^number wall is down — and stopped at a fourth
  wall, F-NBB-OBJHASH: (eve-deftype Datom ...) at db.cljc:192 throws
  "Unable to resolve symbol: sci.impl.deftype/hashCode". Datom's bare
  `Object` group declares hashCode explicitly. Eve's translation
  gives java.lang.Object its cljs disposition (proto map :clj-only?,
  cljs-object-impls re-homes toString/equals, hashCode drops), but a
  BARE Object group had no proto-map entry and passed through
  verbatim — hashCode and all — into the emitted cljs deftype, whose
  sci deftype cannot register it. (bb's seat strips Object methods in
  its types adapter; compiled cljs never sees Eve's own types written
  this way — the port is the first bare-Object carrier.)
- F-NBB-OBJHASH FIX: proto_map gains a bare `Object` entry mirroring
  java.lang.Object's; vocab/cljs-object-impls' object? matches both
  names. CHANGELOG entry added.
- Next: proof 1 rerun; then proofs 2–4.
- Proof 1 continues (fix/logs/p1-seatway3.log): Datom's eve-deftype
  analyses clean — F-NBB-OBJHASH is down. seatway4: stopped at
  (goog/exportSymbol ...) (db.cljc:233) — the ambient Closure BASE
  namespace, named without a require (full goog census, all
  positions: goog.array, goog/exportSymbol x2, goog/getUid x1).
  FIX: adapters/goog/nbb/src/goog.cljs (ns goog) with Closure's own
  exportSymbol and getUid; sci resolves a bare goog/* reference only
  once the namespace is loaded, so eve.types (nbb) now requires goog
  as eve loads. seatway5: PAST exportSymbol — stopped at
  (defn+ ...) db.cljc:242.
- F-NBB-MACROS — the nbb leg's remaining blocker (platform gap, NOT
  Eve-fixable): datascript.db's macros (defn+, case-tree, combine-cmp,
  defcomp, int-compare, validate-attr/val; also parser's and
  serialize's sets) are defined under #?(:clj ...) and referred via
  self (:require-macros [datascript.db ...]). Probes (fix/probes/,
  stock nbb 1.6.214 = the latest nbb on npm): self require-macros
  with :clj-branch defmacros FAILS ("Unable to resolve symbol:
  defn-plus"); the same macros bare or under :cljs branches WORK;
  a separate macro ns with :clj defmacros FAILS ("does not exist").
  By elimination, sci's macro-namespace harvest reads .cljc sources
  with the :cljs feature set only, so a dual-face library's :clj
  macros are invisible to it. An adapter cannot supply another
  library's macro code without forking it (the goog/tag fixes supply
  missing NAMES from the platform library; macros are the port's own
  algorithms). Disposition: named blocker for proofs 1–3 with this
  evidence; upstream observation for sci/nbb, recorded here (no
  upstream patch per standing rule).
- Next: flagship nbb leg run (evidence on the flagship's own path),
  proof 4 (Eve nbb seat probes), report.
