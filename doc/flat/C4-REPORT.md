# C4 — bytes nobody vouches for: REPORT

Lane C4 · Branch `claude/static-c4-check` · Base pin `claude/sweet-carson-6pt29d` @ 7402ef9
Merged: W0 contracts `w0/claude/static-w0-contracts` @ 0ebb480, C1 chunk `c1/claude/static-c1-chunk` @ 9ea620b
Files owned: `src/eve/static/check.cljc`, `test/eve/fixtures/static-malformed.edn`
(plus test support `test/eve/static/check_test.clj`, `test/eve/static/chunk_gen.clj`,
`test/eve/static/gen_fixtures.clj`)

## Verdicts (IMPL-PLAN §3.3, lane C4)

| Req                              | Test var                                          | Result                                                                                                                                                                                                                                                          |
| -------------------------------- | ------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| C4.1 `malformed-refused-by-name` | `eve.static.check-test/malformed-refused-by-name` | **green** — every one of the 46 fixture rows is refused with exactly its named check, as a typed `ex-info` carrying `:check`; never a host Error                                                                                                                |
| C4.2 `limits-enforced`           | `eve.static.check-test/limits-enforced`           | **green** — 10,000 levels of nesting refused `:nesting-too-deep` (no stack overflow); the boundary is exact (64 deep accepted, 65 refused); lying counts refused `:count-exceeds-bytes` before any element is read (VEC count 4294967295; COL count 10,000,000) |
| C4.3 `fuzz-never-crashes`        | `eve.static.check-test/fuzz-never-crashes`        | **green** — 250 seeded mutants × 6 valid chunks (byte set / bit flip / truncate / append): every outcome a typed refusal or an accepted handle; no crash, no hang, no untyped throwable                                                                         |
| C4.4 `place-requires-checked`    | `eve.static.check-test/place-requires-checked`    | **green** — at the contract level (see "What Z3/T1 must call")                                                                                                                                                                                                  |

Final suite run (this branch, after both merges): **5 tests, 73 assertions, 0 failures, 0 errors**
(`eve.static.check-test`, via `clojure.main`/clojure.test on JDK 25 — this box has no
`clojure` CLI on PATH; classpath = repo deps.edn paths + m2repo jars, CLJ_CONFIG at
`~/workspace/eve-env/cljconfig`). Tests-first evidence: commit `f0d0e92` (fixtures + tests)
was run with the checker absent and failed to load (`Could not locate eve/static/check…`),
then commit `3a2e79a` (the checker) turned it green.

Cross-lane verification, measured on the merged tree:

- **W0 contract**: `eve.static/open-checked` delegates to `eve.static.check/open-checked`
  (frozen API, W0.1) — exercised end to end: pack `[1 2 3]` with C1's writer, hand the
  bytes to `eve.static/open-checked`, answer is a `CheckedBytes` (`checked?` true).
- **W0 manifest**: `test/eve/static/requirements.edn` entries C4.1–C4.4 name exactly this
  lane's four test vars; all four resolve in the merged tree.
- **C1 writer output**: `open-checked` accepts C1's `pack` output for every value the
  writer can currently pack: `[]`, `#{}`, `[1 2 3]`, `(vec (range 1000))`, `[1.5 2.5 3.5]`,
  `["a" "bb" "ccc"]`, 300 distinct keywords (codes column), `[true false true true]`,
  `#{1 2 3}`, `(list 1 2 3)`, and the mixed vector `[1 "a" 2.5 :k]` (any column).
- **C1 golden bytes** (`test/eve/fixtures/static-bytes.edn`): 2 of 3 rows accepted.
  Row 3 is refused — correctly; it is defective (finding F2 below).

## What Z3/T1 must call (the C4.4 contract)

`place` (Z3) and `transfer!` (T1) must accept **only** the handle returned by
`eve.static.check/open-checked` (a `CheckedBytes`), or equivalently gate their byte
input through `eve.static.check/require-checked`, which answers the handle or throws
`ex-info` with `:check :unchecked-bytes`. Raw byte arrays, nil, strings, vectors of
ints — anything that is not a `CheckedBytes` — are refused by `require-checked`.
One usage rule travels with the handle: **`CheckedBytes` wraps the caller's byte
array without copying** (the format's zero-copy ethos). Whoever holds the array must
not mutate it between `open-checked` and the placement; a caller that cannot promise
that must hand `open-checked` a copy. Z3/T1: state this in your own docs where you
take the handle.

## Format conflicts found (FORMAT-V1 is drafted, not reviewed — plan risk R11)

C4 was the first adversarial reader of §10. Four places where §10/§9 prose and the
producers disagree; in each case the checker follows the **producer of record**, and
the format owner should correct the prose:

1. **Table offsets are NOT 8-aligned.** §10 asks "every table offset inside the chunk
   and 8-aligned". §2–§5 lay the tables out contiguously, and C1's golden bytes put
   the shape table at 34 and 39. The checker enforces range only. (An earlier C4
   build enforced alignment and refused every real chunk; the alignment fixture row
   was retired with the rule.)
2. **Image relocation `owner`/`target` are ordinals into the block table**, and the
   field `position` is absolute in the buffer — Z1's producer (`eve.static.image`)
   writes ordinals. §10's "its target a block start" should read "a block ordinal".
3. **Column data alignment is achieved by padding INSIDE the column**, between the
   kind byte (and validity bitmap) and the data — §5's layout bracket shows no pad,
   but its "data aligned to the element size from the chunk's start" forces one:
   C1's golden COL has kind at 38, a pad byte at 39, i32 data at 40. The checker
   computes dense data positions at the aligned offset and skips the pad. The same
   convention holds for utf-8 offsets (kind at 38, pad at 39, u32 offsets at 40).
4. **Utf-8 column offsets are u32, always** — not the chunk's offset width (§2's
   WIDE definition notwithstanding). C1's writer emits u32 in a non-wide chunk and
   its reader reads u32; the round-trip verifies. §5 should say so, as it does for
   run-end ends.

Two further readings, flagged for W0/C1 to pin (recorded in C4-STATUS.md):
the nesting limit defaults to **64** (IMPL-PLAN C4.2; §10 says 512 — the plan wins,
`:max-depth` overrides), and the type-table entry layout is unpinned in §6, so the
checker bounds-checks the typetab offset only (C1's writer has since pinned an entry
shape — key + fingerprint — in its STATUS; entry validation can follow once C1's
writer is the merged producer of record).

## Findings (measured, with repros — none in C4's own files)

- **F1 — C1's writer cannot pack maps (pin 9ea620b).** `eve.static.chunk/pack` throws
  on any map value: `(pack {})` and `(pack {:a 1 :b [1 2 {:c #{1 2 3}}]})` →
  `ClassCastException: PersistentVector cannot be cast to Map$Entry`;
  `(pack (into {} (map (fn [i] [i (* i i)])) (range 5000))))` →
  `ArityException: Wrong number of args (1) passed to eve.static.chunk/write-node!/fn`.
  C1's own STATUS at that pin says "compiles; tests not yet run" — consistent.
  Consequence for C4: map/batch-shaped writer output could not be cross-checked;
  the checker covers those shapes against FORMAT-V1 via its own corpus instead.
- **F2 — C1's golden row 3 is internally inconsistent (pin 9ea620b).**
  `test/eve/fixtures/static-bytes.edn`, row "one-entry shaped map": the header
  carries `nsym 0, nshape 0` while its symbol table holds 1 symbol and its shape
  table 1 shape. C1's writer writes the true counts (`build-chunk`:
  `(.u16 w (count (:syms cx)))`), and C1's reader reads the header counts — so the
  row fails C1's own round-trip too. `open-checked` refuses it
  `:shape-out-of-range`. The row, not the checker, should change.
- **F3 — REF nodes (0x2C) diverge by design, named for C3.** C1's lone-chunk reader
  refuses a REF outright; `open-checked` accepts a structurally valid REF (its
  target chunk is the directory's business, C3). If the campaign wants the checked
  door to refuse lone REFs too, that is a one-line change in `eve.static.check` —
  the lane did not invent the stricter rule on its own.
- **No escapes in C4's own surface.** The fuzz corpus (C4.3) plus the cross-checks
  above produced no crash, hang, or silent bad value attributable to
  `eve.static.check`. One theoretical note: node extents in a packed chunk are not
  required to be disjoint — two parents may reference overlapping byte ranges and
  each parse is independently bounds-, count- and depth-checked, so no read can
  leave the chunk; §10 does not ask for disjointness and C1's writer never aliases,
  so the lane did not invent the check.

## Environment notes

- This box has no GitHub credential for SeniorCareMarket/eve (coordinator
  correction): all commits are local on `claude/static-c4-check`; W0/C1 were merged
  through the `w0`/`c1` path remotes. Nothing was pushed.
- The box restarted twice mid-lane and the fleet kept load1 above the ≤8 gate for
  long stretches; heavy runs were gated throughout, the fuzz is bounded (250×6) and
  ran inside the suite.
- `pack-all` probe (bonus, not a gate): C1's `(pack-all (vec (range 50000)))` was
  still running after 10 minutes at load1 ≈ 30 and was killed — its size
  estimation packs every row once (50,000 `pack-rep` calls) before sealing.
  Reported as observed at pin 9ea620b, not as a defect verdict: the box was
  contended throughout. Single-chunk writer output is verified across the
  battery above.
