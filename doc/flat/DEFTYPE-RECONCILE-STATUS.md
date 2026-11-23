# DEFTYPE RECONCILIATION — sister generation × release/eve-0.9.3.2

Lane: claude/flat-deftype-reconcile (staging clone of h5, rebased onto
release tip 356df6b). This file moves to doc/flat/ when the rename
replay carries it into the final tree.

## The collision (measured by the rebase coordinator, REBASE-STATUS.md)

Sister's generation commit c97fe26 rewrites the tail expression of the
deftype macro's emitted expansion in src/eve/deftype/macros.cljc;
release/eve-0.9.3.2 rewrote the same expression (its reader /
register-type-refs emission). Every lane containing c97fe26 (h5,
collate, f3measure, and the stack above collate) stopped at that
commit. All other lanes (19) rebased clean.

## Mechanism account (studied firsthand before the merged form)

BASE (campaign base, after Z1's 2bc436f): eve-deftype*'s expansion is
a `do` of three emissions — (1) t/eve-type (the host type), (2)
register-deftype-layout! (layout for the retire walk; the campaign
form carries total-size as a 4th argument, Z1's change), (3)
register-type-refs! (ref fields for the retire walk / tally).

RELEASE 0.9.3.2 (diff vs merge-base 4a92129): adds type-io-spec and
reader-form (default ITypeFields/IPrintWithWriter for every type,
eve.type-readers read/print registration with :tag/:read/:print
options); type-key computed by reg/qualified-type-key; the record
layout gains :record/portable? true; and the `do` gains two emissions
in the release's own structure — (reg/registration-form …), the
unified deftype.registry entry, placed between eve-type and the
layout registration; and `reader` (a let capturing the positional
factory ->T at load time, registering it with eve.type-readers),
appended after register-type-refs! as the expansion's final form.
The reader must evaluate after eve-type defines the factory it
captures.

SISTER c97fe26 (against the same base): adds let bindings
sister-fields (the slab fields as data, declaration order),
static-name/sister-name, and static-forms — the sister's generation,
asked of the host at macro time via (resolve 'eve.types/static-type),
the same host-realization hook eve-type itself uses. It switches
(list 'do …) to (apply list 'do …) and appends at the tail, after
register-type-refs!: the generated sister form (where the host
realizes one) then (register-sister! type-key sister-fields
factory-or-nil). static-forms must evaluate before register-sister!
references the sister factory it defines.

WHAT A FULLY REGISTERED TYPE NEEDS, IN ORDER: eve-type first (every
factory capture/reference depends on it); the registry entry; layout;
type-refs; the reader (captures the persistent factory); the sister
generation + sister registration (depends on eve-type only through
its own generated factory, and on the macro-time sister-fields data).
The reader's registry (eve.type-readers) and the sister registry
(eve.static.sister) are independent — their relative order is free;
the reconciliation keeps the release's relative order untouched and
the sister appended last, as it was appended after the then-final
form in the campaign's structure.

ARITY NOTE (measured in the merged tree): the merged
register-deftype-layout! is multi-arity — the 3-arity is the release's
call shape, the 4-arity ("also registers the type's total block
size") is Z1's campaign addition; both emission shapes are valid
against it, and the layout vec's keep-fn is byte-identical in both
lineages. The reconciled macros emit the campaign's 4-arity form
(total-size feeds Z1's detach registry), and the release's
registration-form independently carries :total-size into the new
registry. No arity conflict exists in the merged tree.

## The reconciled form

- macros.cljc: the release's eve-deftype* intact, with c97fe26's let
  bindings added after provides? (they inherit the release's
  qualified type-key) and c97fe26's concat appended after `reader`:
  (apply list 'do eve-type registration-form layout type-refs reader
  (concat (when static-forms [static-forms])
  [(register-sister! …)])). Diff vs release = sister hunks
  only (verified firsthand). Diff vs the campaign form = release
  hunks only.
- deftype.cljc: the disjoint union of the require blocks — the
  release's [eve.deftype.registry], #?(:cljs [eve.process-slab]) and
  [eve.type-readers], plus Sister's [eve.static.sister] with its
  comment. The require is what makes the emitted register-sister!
  loadable on every host — the sister load-fix's discipline (each
  host realization of eve.deftype requires every namespace the
  expansion names), one level up.
- No #? added by the campaign side; the only #? in the merged files
  is the release's own #?(:cljs [eve.process-slab]) in deftype.cljc.

## Rebase record (this lane)

h5 rebased in the staging clone as claude/flat-deftype-reconcile:
84 steps, two stops — (1) the d102173 merge-replay (requirements.edn
add/add), resolved to the original merge's own result blob
(md5 e4629753d0079562635fef076c9ff08e, matching the coordinator's
recorded resolutions); (2) c97fe26, resolved by the reconciled form
above (paren balance verified; zero conflict markers). New tip
a8a1e92; release tip 356df6b verified an ancestor of HEAD.

## Acceptance (J — filed, verdicts appended on delivery)

(a) Eve's own release-lineage suites: eve.deftype.type-id-test,
eve.deftype.type-id-steps-test, eve.deftype.int-map-test,
eve.deftype.rb-tree-test, eve.dataset-test.
(b) eve.static.sister-test (S1–S6).
(c) eve.static.requirements-test + eve.static.contract-test
(green modulo the known requirements host-blindness error).
(d) Node image suite via clojure -M:node-test (the sisterfix
acceptance: loads and runs; only H4.1's Dataset row red).

## Acceptance verdicts (J, HEAD-verified in-log; bundle discipline held)

- (a) **Release-lineage suites GREEN** (dtr2-release-suites.log, at
  f7132df): the five suites above — 79 tests / 795 assertions,
  0 failures, 0 errors, EXIT=0. The reconciliation does not break the
  release's machinery.
- (b) **Sister suite on the h5 tree: not green, residuals lawfully
  attributed — none to the reconciliation.**
  (i) Four errors were the release's _construction law_ meeting the
  pre-release test: the test's own `(->Gadget …)` call (sister_test.clj:41,
  from :191) threw `{:eve.alloc{:error:no-domain-context}}` at
  alloc.cljc:1764 — no sister code in the stack. The release changed the
  contract the pre-release test was written against; the release's own
  suites bind `d/*domain-sio*` for exactly this (dataset_test.cljc:21).
  Fixed in the test by that pattern: fixture `in-a-domain-of-its-own`
  (dced716, this branch's tip; the same rider rides the collate stack as
  c4911c2).
  (ii) After the fixture (dtr3-campaign-suites.log: 601 assertions,
  4F/2E overall) ONE sister arm stayed red — sister-protocols'
  twin-equality `(= s (nth batch 0))` failed and the run took a
  StackOverflowError in chunk's parse path (chunk.cljc:800 parse-column
  via row-lookup/Delay/batch-parts). Both trace to **h5's stale
  chunk.cljc**: h5 carries the mid-flight C1 file its own STATUS
  flagged; the collated chunk differs by 2,067 diff lines (the port's
  rewritten row-lookup and equality domain) and sister is green
  (45 assertions) on the collated lineage. Not fixed in the h5 tree —
  the fix exists downstream; final judgment = the step-7 confirming leg
  on the rebased final tree (below).
  (iii) The remaining reds in the same log are h5-lineage artifacts,
  measured: requirements-covered cites W0.6's doc/static/SPIKE-LOG.md,
  absent from h5's tree pre- and post-rebase; scoreboard-smoke reads
  this lineage's scoreboard.cljc in its unbuilt-arm state ("not built"
  ×9); the api-surface error is the pre-existing attributed probe error
  (its stack changed because the release changed data.cljc). All three
  resolve in the collate lineage (collation judged requirements 594
  0F/1E and contract 33 green at its pin).
- (c) judged with (b) — see (b)(iii).
- (d) **Node image suite: unmeasurable on the h5 tree** — "no build with
  id :static-h4-test": this lineage's shadow-cljs.edn has no such build
  (grep count 0) and no h4 runner file; the build + runner are
  collate-lineage files. Judged on the rebased final tree, where the
  build exists as `:flat-h4-test` — verdict in the confirming section.

## The blocked stack, re-run (protocol of REBASE-STATUS.md)

All six lanes rebased onto the release lineage; no NEW semantic
conflict was met anywhere in the stack. Recurring stops were all of
the coordinator's known classes: merge-replay conflicts at collate's
own merges (resolved to the original merges' recorded results for the
conflicted paths — 14 in collate, 16 in f3measure), the two
requirements.edn merge-replays (original merge blobs), racily-clean
stat phantoms (stat refresh; the coordinator's b1 class), and the
deftype stops resolved by the reconciled form.

| lane                     | old tip                                                                               | new tip                            | stops                                                                                                                                                                                                                                                                                                                                                                                                                    |
| ------------------------ | ------------------------------------------------------------------------------------- | ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| h5 (this staging branch) | 512b619                                                                               | dced716                            | d102173 merge-replay; **c97fe26 reconciled** (reconciled equivalent 662dee4); construction-law rider on the test                                                                                                                                                                                                                                                                                                         |
| collate                  | d2a5cff                                                                               | d7d2cb4, then rider c4911c2        | d102173, 2e98d4d merge-replays; stat phantom; **c97fe26 reconciled** (stage-2 bases blob-identical to the h5 stop — macros b1fab69, deftype.cljc 4f29051 — reconciled files taken from 662dee4); aa1e624 chunk merge-replay; 14 merge-replays to original results; sisterfix pick cc108d5 conflicted in the CLJS shim — union resolution (release's [eve.type-readers] kept, sister require added; mirrors deftype.cljc) |
| sisterfix                | d2a5cff                                                                               | d7d2cb4                            | its three commits are collate's final three by construction (its old tip WAS collate's tip); a cherry rebase was attempted and aborted — it re-walked the whole lineage to the same wall — and the branch was re-pointed at the replayed tip; equivalence by identical pre-rebase tip commit and subject-identical replay                                                                                                |
| rename                   | 271a9bd (6124bb3 + its own verification commit, banked after the coordinator's table) | dea1514                            | the rename commit conflicted only in the two deftype files: resolved by applying the rename's own FULL sed rules to the reconciled parent content — zero `eve.static` survivors in code, `eve.flat.sister/register-sister!` at the reconciled tail (reader line 242, registration line 252 pre-rename numbering); re-run over the collate rider with the same rule for sister_test.clj                                   |
| flatdoc                  | 2743a7e                                                                               | tip over dea1514 (0fe8cc0 lineage) | none — both commits replayed clean over the rebased rename, twice (before and after the rider)                                                                                                                                                                                                                                                                                                                           |
| f3measure                | fd51026                                                                               | 82e5b72                            | stat phantom; **c97fe26 reconciled** (same blob-identical stage-2 bases); 16 merge-replays to original results                                                                                                                                                                                                                                                                                                           |

Every rebased tip verified firsthand: release tip 356df6b is an
ancestor of HEAD; trees clean. The collate rider (c4911c2) carries the
construction-law fixture into the stack's sister test (the dced716
adaptation), so the final tree judges sister under the release's law.

## Step-7 confirming leg (rebased final tree)

Filed against the flat lineage tip (flatdoc branch) — JVM:
eve.flat.sister-test, eve.flat.dataset-batch-test,
eve.flat.requirements-test; Node: `:flat-h4-test` build + runner
(acceptance (d) judged here). Verdicts appended on landing.
