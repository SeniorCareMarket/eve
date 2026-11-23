# L1 report — the list reads without allocating

Lane L1, branch `claude/static-l1-list`, SeniorCareMarket/eve.
Base `claude/sweet-carson-6pt29d` @ 7402ef9; code tip at filing 353851d
(later commits docs-only). File owned: `src/eve/list.cljc`; tests
`test/eve/static/list_test.clj`. Full record: doc/static/L1-STATUS.md.

## What the lane did

The CENSUS claim (defect 3: a plain deref of a 50-element list takes
~50 domain blocks) was re-derived at the pin with the allocation log:
four full walks of a 50-element list allocated **197 blocks** through
the list's own sio, none freed — one leaked 12-byte header per `next`
step, minted by `make-list` in `EveList.next`/`more`; `-plain` (the
deref path) walks by `seq`, so every deref leaked per element.

The fix (4be0814): `seq` returns a lazy host seq over the node chain —
one node read per step, no domain allocation (EveVector's seq
precedent); `next`/`more` return the same walk instead of a fresh
EveList. No `make-list` on any read path.

## Per-requirement verdicts

- **L1.1 `list-seq-allocates-nothing` — PASS.** Scoped run at 4be0814:
  seq, next-chains, `eve->clj` (the deref), reduce, and into over a
  50-element list allocate **zero** blocks in the list's domain
  (allocation-log instrument, the reclaim test's). No src change to
  the list since; the final confirmation run at the lane tip is filed
  (below) and its log is the collation-time proof.
- **L1.2 `list-reads-from-view` — PASS.** Same scoped run: a
  200-element list reopened through a frozen view (an ISlabIO whose
  every mutator throws, as a static view's does) reads in full —
  the value P5's probe could not read ("static view: allocation in a
  frozen buffer").
- **L1.3 the existing list suite (`test/eve/jvm_list_test.clj`), as
  dispositioned — DISPOSITIONED; green-as-amended rides the filed
  leg.** The combined run at 4be0814 stood at 36/38; the only failures
  were two assertions: `(instance? eve.list.EveList (next l))`
  (list-next) and `(instance? eve.list.EveList (rest l))` (list-rest).

  **Disposition: stale encodings of the old allocating representation,
  not a regression.** An EveList is only `[sio__ offset__]`; its
  cnt/head exist solely as bytes in a slab header block, so `next`
  could answer an EveList only by minting a header per step — the
  assertions held _because of_ the defect mechanism and are mutually
  exclusive with L1.1. Every observable behavior in their contract —
  values, order, nil termination, rest count — is asserted by the
  neighbouring assertions in the same deftests and all still pass;
  `next`/`rest` owe an ISeq, not the input's concrete type. Amended
  (353851d, with the reason commented in place): both deftests now
  assert EveList-ness on `(pop l)` — the update whose result is a
  first-class EveList tail and which rightly pays for its header —
  alongside the unchanged seq assertions. Every alternative that
  could have kept the old assertions (virtual header, shared/arena
  headers) was priced in STATUS and rejected as worse than the leak.

## Registration and contracts

W0 contracts merged by path remote (38f2181). In
`test/eve/static/requirements.edn`, L1.1 and L1.2 are flipped to
`:built` (their vars resolve: `eve.static.list-test/
list-seq-allocates-nothing`, `.../list-reads-from-view`). L1.3 stays
`:planned` in the manifest — its `:built` flip belongs with the
verified green run of the amended suite, which is the filed leg.

## Final run — filed, not run on this box

Per the operator's venue order (no heavy runs on hatch at any load),
the final confirmation run is filed with the job agent as
`static-eve-list-final.json` (lane `static-eve/list`, venue_pref `j`,
class `heavy`): on J, clone `~/static-eve-bundles/l1.bundle`, check
out `claude/static-l1-list`, run `eve.static.list-test` +
`eve.jvm-list-test`, log to `~/static-eve-results/l1-final.log`.
**The coordinator must refresh `l1.bundle` from this tree before the
leg runs** (code tip 353851d). The coordinator delivers the result;
this lane does not idle on it and claims no number from it yet.

## Still owed (named, not hidden)

- The filed leg's delivered log — the collation-time proof for all
  three requirements at the final tip.
- A broad JVM suite run with the fix (blast radius beyond the list
  suite). Not filed here; the list-suite leg was the brief.
