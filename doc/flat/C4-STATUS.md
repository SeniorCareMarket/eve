# C4 — bytes nobody vouches for: STATUS

Lane: C4 · Branch: `claude/static-c4-check` · Base pin: `claude/sweet-carson-6pt29d` @ 7402ef9
Files owned: `src/eve/static/check.cljc`, `test/eve/fixtures/static-malformed.edn`
Tests: `test/eve/static/check_test.clj` (+ test-support builder `test/eve/static/chunk_gen.clj`)

## 2026-11-12 ~01:50 ET — lane opened

- Tree cloned, branch cut. Read: IMPL-PLAN §3 + §3.3 C4, FORMAT-V1 (all), BLIND-SPOTS Q6,
  scalar layouts from `src/eve/deftype_proto/serialize.cljc` and `adapters/codec/byte-array/src/eve/codec.cljc`.
- W0 (`claude/static-w0-contracts`) and C1 (`claude/static-c1-chunk`) branches are NOT on origin
  yet — building the checker and fixtures against FORMAT-V1 directly, per the lane brief.
- Box load at open: 12.9 (gate is ≤ 8 for heavy runs). Light work first; test runs wait for a dip.

## Spec conflicts / ambiguities found while reading (for the report)

1. **Nesting limit: plan says 64, FORMAT-V1 §10 says 512.** IMPL-PLAN C4.2: "Limits: nesting 64".
   FORMAT-V1 §10: "nesting ≤ 512". Lane brief says requirements are exactly as the plan states, so
   the checker defaults to 64 (option `:max-depth`) and C4.2's test builds 10,000 levels either way.
   Needs one word from whoever owns the format doc.
2. **Image relocation table: `owner`/`target` are ORDINALS into the block table** — RESOLVED
   2026-11-12 by reading Z1's producer (`eve.static.image`, branch claude/static-z1-image):
   "{field position u32 (absolute in the buffer), owner u32, target u32} per pointer (ordinals
   into the block table)". The checker and the test image builder now use ordinals; FORMAT-V1
   §9's prose ("its target a block start") should be corrected to match the producer.
3. **§9's "(a flag)" for omitted image tables names no flag bit.** Checker treats block-table
   offset 0 as "tables omitted" and skips table checks for such an image.
4. **Type-table entry byte layout is not pinned in FORMAT-V1 §6** (contents yes, encoding no).
   Checker bounds-checks the typetab offset only; entry validation awaits the C1/W0 layout.
5. Symbol/shape table offsets: FORMAT-V1 pins "nsym + 1 offsets, then the entries" for the symbol
   table; the prototype (`probes/pack.clj`) makes those offsets relative to the entries region and
   gives the shape table the same structure. Checker follows the prototype on both points.

## Progress

- [x] W0 contract alignment verified firsthand: the frozen manifest names exactly this lane's
      four test vars; `eve.static/open-checked` delegates to `eve.static.check/open-checked`.
- [x] All lane files written and reader-verified (bb reader; every form parses):
      `src/eve/static/check.cljc`, `test/eve/static/chunk_gen.clj`,
      `test/eve/static/gen_fixtures.clj`, `test/eve/static/check_test.clj`.
- [x] Fixture EDN generated (46 rows) + tests proven red (checker absent: namespace
      fails to load), then green: commit 1 `f0d0e92` = fixtures+tests, commit 2
      `3a2e79a` = checker. No origin pushes (coordinator correction): local commits,
      path remotes w0/c1.
- [x] W0 (0ebb480) and C1 (9ea620b) merged; W0's requirements.edn C4.1–C4.4 entries
      resolve to this lane's test vars; `eve.static/open-checked` delegation verified
      end to end; open-checked cross-checked against C1's golden bytes (2/3 — row 3
      is a defective fixture, report F2) and C1's live writer output (all packable
      values accepted; maps throw inside C1's writer, report F1).
- [x] Final suite: 5 tests, 73 assertions, 0 failures, 0 errors.
- [x] `doc/static/C4-REPORT.md` written and committed. LANE DONE.
