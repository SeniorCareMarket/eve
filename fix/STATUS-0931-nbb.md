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
