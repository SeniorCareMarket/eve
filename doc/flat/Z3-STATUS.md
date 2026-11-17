# Z3 — the zip (`place`): lane status

Lane: Z3 of `IMPL-PLAN.md` §3.3 (requirements Z3.1–Z3.7). Branch
`claude/static-z3-zip`, base = research tip `7402ef9` + Wave 0 contracts
merged (`w0/claude/static-w0-contracts` @ cfa86c6: `eve.static` API,
`eve.static.contract`, requirements manifest, scoreboard, corpora).
Board row: B1 (zip, alone). Gate: ≤ 50 ns a block.

## Design (re-derived at this pin)

- `eve.static.place/place [a image]` is the `eve.static` delegate (W0 froze
  the surface): it runs `(swap! a (fn [_] (place-value attempt-sio image)))`.
  Lower doors in the same namespace: `place!` (a domain sio + an image → the
  root's slab offset) and `place-value` (→ the placed root as an Eve value of
  that domain, the swap-fn form). An image is the FORMAT-V1 §9 chunk byte
  array (header + blocks + block table + relocation table, parsed through
  `eve.static.contract`) or the equivalent map
  `{:bytes :length :root :blocks [{:offset :size}] :relocs [{:position
  :owner :target}] :layout}` — Z1's detach emits the chunk; the map is the
  hand-built form the plan's meeting-points paragraph sanctions until Z1
  lands.
- Validation is a separate pass over the tables, before anything is claimed
  (Z3.2); every refusal is a typed ex-info `{:eve.static.place/check <name>}`.
  Check names: `:bad-magic`/`:unsupported-version`/`:unsupported-features`
  (header), `:wrong-kind` (chunk kind ≠ image), `:unknown-layout` (layout
  ≠ 1 — the kernel's layout 2 is a consumer this lane does not place),
  `:length-out-of-range`, `:tables-absent`, `:root-not-a-block`,
  `:block-out-of-range`, `:blocks-overlap`, `:size-cannot-hold` (size ≤ 0;
  with {offset,size} tables the class is derived from the size, so this is
  the checkable form), `:unknown-kind` (the root block's type-id byte is no
  header kind retire/val-children knows), `:pointer-to-non-block` (relocation
  target/owner not a block ordinal), `:relocation-out-of-range` (position
  outside its owner's bytes), `:relocation-value` (the i32 at a relocation's
  position is not the target block's buffer offset — the image's own
  pointer representation, §9).
- Claims go through the one public entry, `alloc/alloc-run!` — never a
  private bitmap path — so the claim is logged by a swap attempt's logging
  sio (W0's default loops `-sio-alloc!`, which logs; Z2's arm logs the run
  block by block) and a lost CAS takes the whole placement back through the
  atom's own hold/drop + free-unheld. NOTE the two lanes' `alloc-run!`
  answers differ in shape: W0's returns a **vector of offsets**, Z2's the
  **first offset scalar**. `place` normalizes both; the collation must pick
  one (flagged for the coordinator in Z3-REPORT).
- Claim fallback (Z3.3): a nil run answer splits the class's blocks into
  halves recursively down to singles; a single that no run answers goes
  through `-sio-alloc!` at the block's own size. Class 6 (overflow) blocks
  are placed one by one through `-sio-alloc!` (Z3.5: the coalescing
  allocator's own alignment discipline — see `coalesc_align_test`).
- Bytes and pointer words are written only through the target sio's ISlabIO
  ops (`-sio-write-bytes!`, `-sio-write-i32!`), never through a region or a
  heap array directly: an encrypting sio (eve.keyring's) applies its
  keystream per (offset, field) to exactly those ops (Z3.6), and a mapped
  domain has no heap array to copy into.
- Rollback: any failure after the first claim frees every offset claimed so
  far, through the same sio, before the exception propagates — a failed
  `place!` leaves the domain's allocated set unchanged.
- Units (Z3.7): with `:unit-size`, blocks are grouped in buffer order into
  units (charged size + 8 + 12 per owned pointer, the P7 accounting); the
  full assignment plan (block → unit, class, run ordinal) is computed before
  the first unit is placed; claims and copies proceed unit by unit; the
  pointer pass resolves every relocation from the completed plan.

## The throw question — MEASURED (2026-11-12)

`eve.atom/swap-attempt!` has no catch around the swap fn. Probe (an ordinary
in-domain build of ~7,520 blocks inside a swap fn, then a throw; blocks in
use counted from the region headers, the lost-attempt test's method):

    before 4 · after-throw 7,524 · after retire-flush 7,524 · the same
    build committed: 15,042 (≈ 2 × the build + the prior 4)

A thrown attempt's allocations are never reclaimed — for every writer, not
just placement. The plan's Z3.4 wording ("after a throw … the allocated set
is what it was") is therefore not a property `place` can keep; it names a
catch `eve.atom` does not have. Banked as a correction in Z3-REPORT (the
fix, if wanted, is atom-level and out of this lane's file). What Z3.4
asserts: lost CAS exact (the atom's hold/drop + free-unheld); a failure
inside placement itself rolls back every claim exactly (place's own catch);
a user-fn throw after placement grows the set by exactly the image's block
count — no partial claims, no extras.

## Log

- 2026-11-12 ~01:50 AM ET — lane set up (tree, branch); IMPL-PLAN §3/§3.1/
  §3.3-Z3, FORMAT-V1 (§2, §9, §10), MEASUREMENTS (M5, M6, M8) read. Probes
  read: lib.clj (`image!`, `ingest!`, `claim-run!`), ImageCopier.java, P6
  (place inside swap! via `d/*domain-sio*`, reopen at the placed offset),
  P7 (unit-by-unit placement). Core source read: alloc.cljc (ISlabIO,
  LoggingSlabIO, ctx-alloc!, keystream), atom.cljc (swap-attempt!,
  resolve-new-ptr, hold/drop on CAS loss), tally.cljc (hold-root!,
  free-unheld!), retire.cljc (children/reach), keyring.cljc
  (EncryptedSlabIO: every data op XORs a keystream keyed by (offset,
  field); alloc/free delegate).
- Wave 0 contracts merged (cfa86c6). Z1 has tests only so far (no
  image.cljc): hand-built images per the plan. Z2's run claim is committed
  and green on its branch; NOT merged here (its `alloc-run!` redefinition
  collides with W0's — see Design note); the B1 gate is measured in this
  tree (W0 default claims) and again in a scratch tree with Z2's
  alloc.cljc, and the report says which is which.
- Environment: clojure CLI 1.12.6.1673 at /home/hatch/.local/bin/clojure
  (not on PATH); deps via CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig
  (:mvn/local-repo /home/hatch/m2repo); tests via `clojure -X:jvm-test`.
  Box load1 10–18 at lane open — over the ≤8 gate; light work first.
- Next: throw probe (when load dips) → tests first (red) → place.cljc.
- 2026-11-12 ~02:20 AM ET — tests-first commit 3989f7b (seven deftests,
  stub place). Throw probe MEASURED (see the section above): a thrown
  attempt leaks its blocks for every writer; Z3.4 asserts the exact arms
  place owns (lost CAS, mid-placement rollback, no-partial-no-extras) and
  the correction is banked for the report. place.cljc implemented
  (acffdd2) + B1 bench harness; desk-review fixes (map? dispatch,
  deterministic mid-placement failure arm). First scoped run: reader
  error — one missing paren in execute-plan! (the unit doseq tail);
  fixed, both files balance. Re-run waiting on the load gate (box at
  17–28 under the whole fleet).
- 2026-11-12 ~05:40 AM ET — SUCCESSOR SESSION (prior sessions died in
  platform restarts; state re-read firsthand). Venue order in force: no
  heavy runs on this box at any load — all runs are filed legs on J.
  Meeting points landed locally, all clean merges: Z1
  (z1/claude/static-z1-image @ 22ceacb — image.cljc/sio.cljc are in this
  tree), C4 (c4/claude/static-c4-check @ 339dbe3 — check.cljc in this
  tree), Z2 (z2/claude/static-z2-runclaim @ ed97031 — this tree's
  alloc-run! is now Z2's verified run claim, first-offset scalar; place
  still normalizes both answer shapes). The checked gate landed:
  tests first (9688f83 — Z3.2 gains the :unchecked-bytes arms, Z3.1's
  chunk arm goes through open-checked, and a real detach→check→place
  end-to-end arm), then the gate itself (4ef4dc8 — normalize-image takes
  byte form only as a CheckedBytes handle; raw bytes are refused via
  check/require-checked before parsing; the bench's claim-mode line now
  names Z2's scalar arm correctly). Bench docstring's run line
  corrected (there is no :test alias in deps.edn).
  LEGS FILED (job-agent inbox, lane static-eve/zip, venue_pref j):
  20261112-static-eve-z3-suite.json (place-test + requirements-test)
  and 20261112-static-eve-z3-bench.json (board row B1), both logging to
  ~/static-eve-results/ on J. **BUNDLE REFRESH NEEDED before the legs
  can run: the z3.bundle staged on J predates the merges and the gate;
  refresh it from branch claude/static-z3-zip (code under test =
  4ef4dc8; later commits are docs only).** No requirement is claimed
  green yet: the only scoped run on this branch died on the paren error
  above, and every re-run is now a venue leg. On a green suite leg:
  flip Z3.1–Z3.7 to :built in requirements.edn (one follow-up commit)
  and fill in Z3-REPORT.md's results section.
- 2026-11-12 ~05:52 AM ET — FINISHER SESSION. The first J legs both died
  at COMPILE time; re-derived locally at tip d4674da, TWO load-time
  defects in the lane's own test files (provenance: this tree, grep +
  Clojure read + mechanical depth count):
  (1) `e/eve->clj` (17 refs in place_test.cljc/place_bench.clj) resolves
  nowhere — `eve.core` is the coll adapter
  (adapters/coll/jvm/src/eve/core.clj: count/nth/get/seq/assoc…, no
  eve->clj, and never had it). The var is `eve.deftype-proto.data/eve->clj`
  (src/eve/deftype_proto/data.cljc:515), spelled `d/eve->clj` tree-wide
  incl. this lane's image_test.cljc. Bad alias in the test, not a rename
  and not a merge artifact. This was the legs' reported error
  (place_test.cljc:402:21).
  (2) place_test.cljc was ALSO missing the deftest + `#?` closers at the
  end of the Z3.1 section (the e2e arm added in 9688f83): depth count
  ended at 2, localized exactly there; Clojure `read` died "EOF while
  reading" at that form; every later section is internally balanced, so
  the whole file nested inside Z3.1's deftest. (The coordinator's
  summary named only defect (1); defect (2) would have killed the
  re-run the same way.)
  Fixed in e354c47: all refs → `d/eve->clj`; two closers appended after
  the e2e testing form. Both files now read clean to EOF locally
  (place_test 10 top-level forms, place_bench 5). LEGS RE-FILED in the
  working bash -c form (bare `clojure`, one -n per invocation):
  20261112-static-eve-z3-suite2.json → ~/static-eve-results/z3-suite2.log,
  20261112-static-eve-z3-bench2.json → ~/static-eve-results/z3-bench2.log
  (bench runs after the suite leg). **BUNDLE REFRESH NEEDED to e354c47**
  before the legs run. Still no requirement claimed green — the suite
  has never run anywhere; the verdict rides on z3-suite2.log.
- 2026-11-12 ~06:05 AM ET — FINISHER #2 SESSION. z3-suite2.log READ
  (coordinator delivered): the suite RAN — 7 tests, 88 assertions,
  **21 failures, 7 errors**, every red re-derived at this pin before any
  fix. Four root causes, four owners:
  (1) **place.cljc, product — FIXED (84600da).** `chunk->image` demanded
  the relocation span divide exactly by 12, but FORMAT-V1 §2 makes the
  chunk length a multiple of 8: both real producers pad (Z1's writer
  align8s its total; T1 met this same refusal and worked around it
  producer-side by setting typetab). Measured on the failing chunk:
  span 196 = 12×16 + 4 zero pad bytes for exactly the map's 16 relocs.
  Fix: count = quotient; the trailing bytes to the table end must be
  zero, else :tables-absent.
  (2) **test writer — FIXED (4282e67).** `image-chunk-bytes` shifted the
  tables by the 32-byte header but never relocated the pointer words
  inside the copied blocks (§9: words hold buffer offsets, and the
  buffer is the chunk). Measured: stored 12 vs target-offset 44. The
  writer now rewrites each reloc word as hdr + target offset.
  (3) **test byte choice — FIXED (4282e67).** The :unknown-kind arm set
  the root byte to 0x55, which IS an eve-deftype type-id
  (eve.deftype.registry: 0x40 + hash mod 173 → 0x40–0xEC); place
  accepted it correctly, then placed (the arm's 4 "nothing claimed"
  failures were that one placement cascading: 68 blocks in use where 0
  were expected, and the closer double-counted). The arm now uses
  0xF0, in the unassigned 0xF0–0xFF range; scoped probe: refused
  :unknown-kind.
  (4) **Z3.4's sabotage seam — FIXED (4282e67).** The rollback arm rode
  `with-redefs` on the protocol fn `alloc/-sio-alloc!`; measured three
  ways, that redef never intercepts compiled call sites (a var-deref
  call sees the stub; a direct call and all of place's claims do not),
  so place committed all 419 blocks and nothing threw. The failure now
  rides a delegating `FailNthAllocSio` whose 6th -sio-alloc! throws
  (the alloc-run! → nil redef, a plain defn, DOES engage — 2 calls
  counted). Scoped probe: throws, allocated set back at 0.
  Plus the e2e arm's uncaught ArithmeticException: Z1's `hash-of`
  (image.cljc:345, `(int 0x9E3779B1)`) — Detach's defect, already fixed
  by its owner (a3a0d20); consumed here as a meeting-point refresh
  merge (126b711, Z1 tip 5294dfc, clean).
  Scoped local probe after the fixes (light, <1 min): chunk-form place
  of the nested-collections shape reads back equal; 0xF0 refused;
  rollback exact. LEG RE-FILED: suite → z3-suite3.log (bench row rides
  the coordinator's already-re-filed z3-bench job). **BUNDLE REFRESH
  NEEDED to the tip carrying this entry** before the leg runs. No
  requirement claimed green until suite3 reads back.
- 2026-11-12 ~06:20 AM ET — FINISHER #3 SESSION. The bench leg died
  compiling place_bench.clj:46 — "No such namespace: d". Re-derived at
  beb3ce9: finisher #2's e/→d/ rewrite fixed the REF (line 46,
  `(d/eve->clj (pt/reopen sio root))` in bench-one — the file's only
  d/ ref) but the ns form never required the namespace: its :require
  carries eve.core, deftype-proto.alloc, corpora, place, place-test —
  no deftype-proto.data. Reading the file (finisher #2's check) cannot
  see this; only compilation resolves aliases. FIXED: added
  `[eve.deftype-proto.data :as d]` — the tree-wide spelling (identical
  line in image_test.cljc, chunk.cljc, image.cljc, place.cljc).
  Verified by a scoped local compile of just the bench namespace
  (light): `:BENCH-NS-COMPILED`. BENCH LEG FILED:
  20261112-static-eve-z3-bench3.json → ~/static-eve-results/
  z3-bench3.log (documented -Spath -X:jvm-test classpath + java form;
  gate ≤50 ns/block on chat-messages; the bench prints its claim
  mode — this tree's is Z2's run claim, first-offset scalar).
  **BUNDLE REFRESH NEEDED to the tip carrying this entry** before the
  bench leg runs (the suite round-3 leg is unaffected by this commit
  either way: it loads place-test + requirements-test, neither of
  which requires place-bench; the change is one require line in the
  bench file + this entry). Close-out rides delivery of
  z3-suite4.log + z3-bench3.log: green suite → flip Z3.1–Z3.7 to
  :built, fill Z3-REPORT's results section, B1 row to SPIKE-LOG.
- 2026-11-12 ~06:45 AM ET — FINISHER #4 SESSION. Two items closed.
  (1) **The suite4 error — test call shape, FIXED (58f22f4).**
  Round 3 ran 7 tests / 88 assertions / 0 failures / 1 error:
  `place-reads-back-and-updates` died at image.cljc:569,
  ClassCastException [B -> Image. The e2e arm passed
  `(static/detach src)` to `image/image-bytes` — but the frozen verb
  answers the image BYTES directly (image.cljc's `detach` IS
  `image-bytes` of the internal Image; image_test.cljc:185 asserts
  exactly this contract), and the detach array is already trimmed to
  the written length, so the arm now uses the bytes directly. The
  error had masked everything behind it in that deftest.
  (2) **Behind the error: a stale meeting point, NOT a place defect —
  consumed by merge (21bc5ac).** With the call fixed, a scoped local
  probe of the e2e shape (real detach -> open-checked -> place)
  measured the placed nested map's child collections EMPTY from the
  moment of placement ({:b [], :c #{}, :e {}}, scalars intact; the
  swap fn's own view already corrupt — so the write path was never
  implicated). This tree's image.cljc was at a3a0d20 (hash-of only);
  the signature is Z1 finisher #2's HAMT inline-pointer double-base
  defect (scan positions absolute, `inline-ptr-at` block-relative —
  every map/set value pointer missed), fixed by its owner at
  e3320f4 with three sibling walk fixes. Meeting-point refresh merge
  of Z1 @ e3320f4 (clean; image.cljc + sio.cljc + Z1-STATUS only);
  the same probe after the merge is green on every arm (place
  readback, deref, swap-identity view, assoc-on-top all equal).
  BENCH ROW BANKED (925347d): B1 appended to SPIKE-LOG from
  z3-bench3.log at 835e6fc, claim mode run — messages 15,275.1
  ns/block, gate <=50 MISSED by ~300x, probe-vs-shipped-path note
  recorded (the probe measured the raw zip loop; the row is the
  shipped path: validation + checked gate + per-block sio ops).
  LEG RE-FILED: suite round 4 -> z3-suite5.log at the tip carrying
  this entry. **BUNDLE REFRESH NEEDED to that tip** before it runs.
  On green: flip Z3.1-Z3.7 to :built, fill Z3-REPORT's results
  section, declare.
- 2026-11-12 ~06:54 AM ET — FINISHER #5 SESSION (close-out).
  z3-suite5.log READ firsthand: place-test 7 tests / 96 assertions,
  0 failures, 0 errors — GREEN at a8009db. requirements-test 2 tests
  / 514 assertions, 0 failures, 1 error: W0's api-surface deftest,
  its (detach nil) probe throwing a protocol IllegalArgumentException
  (IEveRoot, no impl for nil, core_deftype.clj:585) — the attributed
  item; requirements-covered passed in the same run, and no Z3
  verdict rests on the api-surface probe. Z3.1–Z3.7 flipped to
  :built in requirements.edn; Z3-REPORT's results section filled
  (suite numbers + the banked B1 row: messages 15,275.1 ns/block,
  gate ≤50 MISSED ~300×, claim mode run). LANE Z3 COMPLETE at the
  tip carrying this entry. Standing flags for collation unchanged:
  one canonical alloc-run! answer shape; T1 raw-byte callers route
  through open-checked; Z3.4's throw wording correction (atom-level,
  out of lane).
