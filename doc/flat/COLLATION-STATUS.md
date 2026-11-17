# Wave 1 collation — STATUS

Branch `collate` in ~/workspace/static-eve/collate, based on the research tip
7402ef9. Merge order follows IMPL-PLAN §4 (meeting points): W0, Z1, Z2, Z3,
Z4+T1, C1, C2, C3, C4, X1, L1, B1, H5. All lane tips as briefed were fetched
by path remote and merged 2026-11-12.

## Merged (tip merged → collation state)

- W0 contracts 36fc651 — fast-forward.
- Z1 detach e3320f4 — clean. (In flight: finisher #3; refresh at the end.)
- Z2 claim d575706 — clean.
- Z4 count 8736e96 — SPIKE-LOG add/add conflict, union-resolved (all sections
  preserved). F5 NOT ADOPTED stands: `*count-at-place*` flag OFF in the
  collated tree; counts stay walked.
- Z3 zip 835e6fc — clean (its tree already carried C1@9ea620b, C4, Z1 merges).
- T1 transfer ae9366e — SPIKE-LOG union-resolved.
- C1 chunk abbd260 — SPIKE-LOG union-resolved (one section initially dropped
  by a faulty line-membership check, restored line-for-line from C1's file in
  c229af8 and verified by section headings).
- C2 columns 55c570b — SPIKE-LOG union-resolved.
- C3 container 196914e — SPIKE-LOG union-resolved (its B8 section appended).
  B8 gate FAIL stands as measured; the format-level fix is above-lane and is
  NOT attempted here.
- C4 check 339dbe3 — already an ancestor via Z3's meeting-point merge;
  check.cljc and check_test.clj verified byte-identical to C4's tip.
- X1 node e1480cd — chunk.cljc conflict resolved to X1's merged chunk.cljc
  (2,662 lines, the single chunk implementation; chunk.cljs does not exist).
  C1's nil-guard fix (f243c1d) ported in the same commit.
- L1 list 2eac271 — clean.
- B1 builder 79bd1e8 — SPIKE-LOG union-resolved (B5 section appended).
- H5 sister 512b619 — conflicts in chunk.cljc, transfer.cljc, chunk_test.clj;
  reconciliations below.

## Cross-lane fix reconciliation (H5's flagged fixes)

- place.cljc: collated file is byte-identical to Z3's tip. H5's snapshot
  predated Z3's own later work, which contains the substance (the C4
  CheckedBytes gate in normalize-image, the relocation-span pad fix) and
  more. **Owner (Z3) won; H5's place copy superseded, nothing lost.**
- transfer.cljc: collated file is byte-identical to T1's tip. T1's tip
  carries the same HAMT-scan extraction H5 made (hamt-bitmap-scan /
  hamt-collision-scan) plus the (long …) argument coercions in block-scan
  that H5's copy lacks. **Owner (T1) won; H5's transfer copy superseded.**
- chunk_test.clj: one 2-line conflict — HEAD's `(gen/large-integer* {})`
  (C1's test.check arity fix) kept over H5's un-aritied form. Owner side won.
- chunk.cljc: H5's changes are NOT superseded — they are Sister's own
  integration (rep-converters, type-registry as [key fp]→factory,
  RecordSource/RowSource field sources, shape/type list sort fix). They were
  made against C1@9ea620b and must be ported into X1's chunk.cljc, together
  with C1's post-pin equality/meta fixes. See OWED below.

## Collation items — state

1. **chunk.cljc port — DONE** (commits e69fb4c A1 proxies, 1471ad2 A2
   IAttachMeta/equality, 265f1cf B sister integration; record in
   doc/flat/CHUNK-PORT.md). X1's planner/BigInt/column-view untouched —
   its i64 range fix is the promoting subtraction `(-' mx mn)` on :clj and
   BigInt math on :cljs, verified unregressed. Local gates: reader pass
   (:clj 118 forms, :cljs 125, clean) + bare JVM require OK. The suites on
   J are the real judge (filed, below).
2. FORMAT-V1 prose fixes — DONE (b7ca246, the 4 places).
3. One canonical alloc-run! answer shape — DONE (cb64210: the vector;
   place's dead scalar branch removed).
4. W0 api-surface contract amendment — DONE as its own flagged commit
   (15d3d3b) for the coordinator to surface to John.
5. Full runs on the collated tree — FILED on J (bundle staged by the
   collation agent at tip 265f1cf, clone-verified on J):
   collate-tests (14 flat namespaces) → ~/static-eve-results/
   collate-tests.log; collate-scoreboard (B1–B8, min of 5) →
   ~/static-eve-results/collate-scoreboard.log. Collation report follows
   their delivery.
6. Final refresh pass for Z1/Z3/T1 tips when their finishers declare
   (coordinator signals); their requirement flips ride that pass.

## Successor pass (2026-11-12) — refresh, audit, repair

- Refresh merges: Z1 80a3d6f, Z3 0eb8308 (COMPLETE), T1 83b4e88 (tip past
  c9e64be; finisher #6 still working — one more refresh at declaration).
  SPIKE-LOG add/add conflicts union-resolved as before. Content verified:
  image.cljc byte-identical to Z1's tip; transfer.cljc byte-identical to
  T1's tip (before the repair below); place.cljc differs from Z3's tip by
  exactly collation item 2 (canonical alloc-run! shape: docstring +
  dead scalar branch removed); chunk.cljc is the single implementation
  (2,774 lines, no chunk.cljs) carrying X1's planner fix `(-' mx mn)`,
  the BigInt :cljs planner, and Sister's rep-converters / RecordSource.
  Contract amendment 15d3d3b present in the merged requirements_test.cljc.
- AUDIT of the first pass's filings: collate-tests.log on J contains
  exactly ONE namespace's run (sister-test, 6 tests / 37 assertions,
  0F / 1E) and no scoreboard log exists — the "full test set + scoreboard
  filed" claim was a partial filing. The 1 error is NOT the api-surface
  probe: it is sister-test boundary-conversion dying on Check's
  :unchecked-bytes refusal — transfer's place-image!/zip-root! handed
  place raw detach bytes, which place's gate refuses. A genuine
  cross-lane break (every transfer! / :zip-policy call), invisible to
  each lane's own suite at its own pin.
- REPAIR (7f2645a): both byte doors in transfer.cljc now vouch through
  eve.flat.check/open-checked, the door the contract names. The full
  test set + scoreboard are re-filed against this tip; their logs judge
  the repair (transfer-test, sister-test) and the amendment
  (requirements-test's api-surface probe).
- REPAIRS 2–5 followed from the re-runs (full record in
  COLLATION-REPORT.md): nil guards ahead of protocol/swap dispatch in
  detach-image (7887386), place/transfer!/chunk-bytes (9e1cd75); the
  amendment's typed-refusal? predicate corrected to the vocabulary its
  own comment names (453273e); check's header range test allows the
  empty table at the buffer end (377902f).
- FULL RUNS DONE (J logs: collate-tests4/5/6, collate-scoreboard2,
  collate-b3, collate-b4b). Test set: 12 of 14 namespaces fully green;
  image-test 2F (image-read-speed — Detach's Z1.7), transfer-test 2F
  (Q2b writer-route control — T1's open residual). Scoreboard: today
  baselines re-taken; B3 gate MISSED ~100–250× on every corpus
  (Detach's wound, values correct); B4 ratios PASS 0.92–1.08, caps
  MISSED ~250–1,900×. COLLATION-REPORT.md carries the tables.
- STILL OWED at that pass: the final Z1 refresh, the final T1
  refresh, and the Wave 2 second pass — all three landed in the
  final pass below.

## Final pass (2026-11-12) — Detach refresh, Wave 2 second pass, Transfer refresh

- FINAL DETACH REFRESH: Z1 ab90820 (declared) merged. Delta over
  80a3d6f is report + flips + diag only; collated image.cljc differs
  from the owner's tip by exactly collation repair 2 (the nil
  guard), verified by diff. image-test re-judged at the final tip:
  208 assertions, 2F — both image-read-speed (118.25× / 27.62× vs
  ≤1.2). Z1.7 stands open as Eve-core work (the JvmSlabCtx
  concrete-class gate in hamt-get), not a merge artifact. B3 not
  re-benched (no walk changes in the refresh); its row stands.
- WAVE 2 SECOND PASS: Disk 16eaffd (clean), Journal 9d87ae2
  (requirements.edn union-by-owner), IO 9d2695b (requirements.edn
  auto-union; check.cljc add/add resolved to the collated file —
  the two sides differ by exactly repair 5), Hosts fd3e09e
  (requirements.edn union-by-owner). All four lanes' reports and
  suites are in the tree.
- H3 LIST FLIP applied in the collated tree (commit 3185e6b): the
  eleven Wave 1 suites :excluded → :namespaces on JVM-class hosts;
  basilisp/jank/CLJS-class exclusions for them carry measured host
  reasons; disk/journal/io reasons corrected to landed-unjudged;
  not-yet-landed renamed not-in-any-suite (holds only disk, journal,
  io, dataset-batch, chunk-leaf). host-suite-test judges the
  flipped lists green (329 assertions).
- FINAL TRANSFER REFRESH: T1 14aefaa (declared) merged clean.
  transfer.cljc verified by diff = owner's bytes + collation repair
  1 (both doors vouch through check/open-checked) + repair 3's
  :no-atom guard, nothing else. T1.1–T1.6 rode in as :built.
  transfer-test re-judged: 150 assertions, 0F/0E — the Q2b rewrite
  (writer route judged against its own census; both routes carry
  the identical [2 1 0 0 0 0] initial-root residual) closes the
  successor pass's last transfer red.
- SUITES AT THE FINAL TIP (7d47715; J logs collate-w2-suites.log +
  collate-w2-spotcheck.log, HEAD lines verified): disk 7,462 green ·
  journal 10 green · io 39 green · host-suite 329 green ·
  requirements 590 green · contract 33 green · image 208 with the
  2 Z1.7 failures · sister 45 green · transfer 150 green. Collated
  set: every namespace green except image-test's Z1.7 pair. No
  scoreboard re-run this pass; B1–B8 verdicts stand as recorded.
- FILING CORRECTION banked: this pass's first two filings were
  discarded unread — shared clone dir race (both legs used
  /tmp/colw2) + a trailing redirect that captured only the last
  namespace. Re-filed with distinct clone dirs and per-invocation
  redirects; HEAD-verified. Same lesson Hosts banked (EXIT=128).
- PENDING INPUTS, final: H4 (CLJS image — in flight, successor #2)
  and H6 (Dataset — in flight, finisher #2), each landing as a
  third Wave 2 pass at its declaration. Nothing else pending;
  H7/Leaf remains unstaffed on fork F3 (the operator's).

## Third pass (2026-11-12) — H4 + H6, the last two Wave 2 lanes

- H4 (CLJS image) ccc6730 merged (b4ae538): the only conflict was
  requirements.edn — union by owner (H4.2 → :built; H4.1 stays
  :planned on its named Eve-core residual; H5's :built flips kept).
  The image.cljc delta is H4's :cljs branches only (Uint8Array
  allocation, grow-ints doubled-copy, canonical zero pads); collation
  repair 2's nil guard verified present in the merged file.
- H6 (Dataset) merged at its DECLARED tip 917a01c (b3bf634 — the
  declaration landed during this pass, past the briefed fdf07d3).
  Conflicts: requirements.edn (union by owner — H6.1–H6.3 :built;
  collated H4.2/H5 flips kept) and SPIKE-LOG.md (resolved to the
  collated file: section-heading comparison shows H6's file is a
  strict subset — every heading present in the collated file, whose
  C1 bench section is byte-identical; the collated file adds five
  sections H6's base predates).
- host_suite.cljc: dataset-batch's not-in-any-suite reason corrected
  ("has not landed in any tree yet" → landed at this pass, suite
  membership unjudged), same pattern as disk/journal/io (ca967b8).
- LEGS FILED on J at ca967b8 (bundle recreated, scp'd, list-heads
  verified before filing; unique clone dirs; HEAD first line in-log):
  collate-w3-jvm.log (dataset-batch, requirements, contract,
  host-suite) and collate-w3-node.log (shadow compile of
  :flat-h4-test + the Node image suite). Verdicts land in
  COLLATION-REPORT.md when the logs deliver.
- VERDICTS DELIVERED (HEAD lines verified at ca967b8; full record
  in COLLATION-REPORT's third-pass section): dataset-batch 29
  GREEN · contract 33 GREEN · host-suite 331 GREEN · requirements
  594 assertions 0F/1E — the error is requirements-covered resolving
  H4.2's CLJS-only test var from the JVM (the manifest has no host
  dimension; named residual), NOT the api-surface probe, which
  passes on this tree.
- CROSS-LANE BREAK found by the H4 merge: the Node image suite
  DIES AT LOAD on the collated tree (eve.dataset.js: "Cannot read
  properties of undefined (reading 'sister')" — the deftype macros'
  unguarded top-level eve.flat.sister/register-sister! emission,
  absent from H4's pre-Sister macros, unresolvable in CLJS's load
  graph for core namespaces like eve.dataset; every JVM suite
  green). At H4's own tip the suite runs (31 assertions, 3F = the
  H4.1 Dataset row only). Banked as a named residual; H4.2's :built
  and the byte-identity arm stand on the owner's judged evidence.
- THIRD PASS COMPLETE. Pending inputs: NONE — all Wave 1 + Wave 2
  lanes are merged into the collated tree. Open items are the
  report's residual list only (Z1.7, H4.1, the sister-registration
  CLJS load break, manifest host-blindness, A16, Z3.4, B8,
  kind-18) and fork F3 (Leaf — unstaffed, the operator's).

## Known limitations / Eve-level residuals (named, not fixed here)

- Columns kind-18 writes u16 symbol codes unchecked — truncates past
  65,536 symbols in one chunk. Flagged limitation, carried from C2.
- A16 (Transfer's residual): a two-ref STM transaction can half-commit when
  a ref's store throws mid-commit. Eve-level (eve.stm commit protocol), not
  a flat defect.
- Z3.4 correction (Zip's): a thrown swap attempt leaks its claimed blocks
  for every writer (swap-attempt! has no catch around the swap fn).
  Atom-level Eve, out of scope for the flat lanes.
- Container B8: +24–25% over the ≤5% gate on messages/records; mechanism is
  per-chunk table reset (format-level), above-lane.
- F1/F2/F3 forks remain the operator's (name, equality default, chunk as
  leaf block). F2 implemented as (a) on the flat side by Sister.
