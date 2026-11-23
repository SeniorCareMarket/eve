# Z1 — the image: a static slab I/O and `detach` — lane status

Branch `claude/static-z1-image`, base `claude/sweet-carson-6pt29d` @ 7402ef9. JVM only (Wave 1).
Board row: B3 (detach under the pin vs `@a`, gate ≥ 2× on every corpus).

## Design (from FORMAT-V1 §9 + the probes, re-derived at this pin)

- `eve.static.sio` — one slab I/O over one byte array, two modes: a **builder** (alloc bumps the
  tail and records each block's requested size, free is a no-op, writes allowed) and a **view**
  (frozen: every mutator — alloc!, free!, write-*, cas!, copy-block! — throws a typed ex-info;
  M1: "a product view must have no write path"; the prototype only refused allocation).
- `eve.static.image` — `detach` walks the value with a pointer enumeration that mirrors
  `eve.retire/children` kind for kind, but returns **byte positions** (retire's positions are
  logical: a HAMT bit index, a varr slot index). The Z1.3 test cross-checks the (owner, target)
  multiset against retire's on generated values.
- Block sizes are **content sizes** (the size the writer requested — every writer at this pin
  allocates exactly its content: HAMT node-byte-size formulas, vblock 4+len, vec trie/tail
  128 B, list cell 8+vlen, varr align4(8+2n)+4n, array 8+n·elem-size, scalar block
  1+EVE-scalar-length). Exceptions by source: a builder/view sio's recorded sizes win; an
  eve-deftype block's size is its registered total size (below); where content cannot size a
  block of a JvmSlabCtx, the class size is the fallback (Eve's own convention:
  `scalar-block->value` reads a scalar block at class size).
- `register-deftype-layout!` gains the type's **total size** (serialize registry + the
  `eve-deftype` expansion in macros.cljc passes it), because a deftype block's content size is
  its total size and nothing at runtime recorded it.
- The image is a FORMAT-V1 chunk: 32-byte header (kind 1, layout id 1 in `nsym`), blocks
  4-aligned root-first, then the block table, then the relocation table
  ({field position = absolute buffer offset, owner = block ordinal, target = block ordinal},
  the probe's semantics). Type table: absent (typetab = 0) in this lane — placement resolves
  types through the target's own registry, as the probe did.

## Log

- 2026-11-12 ~1:25 AM ET — lane set up; plan/FORMAT-V1/MEASUREMENTS/probes read; layouts
  re-derived from source (map/set/vec/list/array/obj headers and node sizes, coalesc class 6,
  scalar-block class-size convention, `read-pinned` as the pin API).
- Re-derived defect (was a CENSUS/M1 claim): **EveList seq/next allocates** — `make-list`
  (src/eve/list.cljc:365-369) writes a fresh 12-byte header on every `next`/`more`/`pop`, so
  walking a list through a frozen view throws. That is lane L1's fix; Z1.2's list row is
  written in full and its seq half is expected red until L1 lands.
- Next: tests first (red), then sio.cljc, then image.cljc, then the B3 board run.

## Log (2026-11-12, ~02:00 ET)

- Push route closed by the coordinator (verified: this box has no GitHub
  credential for SeniorCareMarket/eve — pushes fail; the coordinator
  collates lane trees locally). All commits are local on
  `claude/static-z1-image`; nothing is blocked on push.
- W0 contracts merged from the path remote (`w0` →
  /home/hatch/workspace/static-eve/w0, tip cfa86c6). The frozen contract
  moved my header: version u8 @4, kind u8 @5, flags u16 @6, layout id
  u16 in nsym — my hand-rolled header had kind/flags misplaced against
  FORMAT-V1 §2. Header + tables now go through eve.static.contract.
  requirements.edn conflict resolved to W0's manifest (its Z1 entries
  name this lane's tests verbatim).
- eve.static.image/detach is the contract verb (one argument — an atom
  under the pin, or a value — answering one byte array); the
  record-returning core is detach-image.
- Re-reading detach caught two real defects before any run: (1) a shared
  block's later edges never patched their fields nor entered the
  relocation table (fixed: patch + record at pop time); (2) the ordinal
  table was fixed at 65536 entries — a 100k-block value would have hung
  the walk (fixed: rehash at 3/4 load). A third, reader-level: bare
  `#?(:clj ^bytes …)` hints do not read; rewritten. All lane files
  parse-checked with the Clojure reader.
- B3 board script written (test/eve/static/z1_board.clj): scoreboard
  arms/format over eve.static.corpora. Baseline of record for @a is
  MEASUREMENTS M6 (3.3/19.1/20.7/43.9 ms); the board re-measures @a
  in-session (the lane's diff does not touch the deref path).
- Box load has been 12–29 all hour (other lanes) — over the ≤8 gate, so
  no test or board run has started yet. Next: scoped jvm-test run of
  eve.static.image-test, iterate, then the board.

## Log (successor session, 2026-11-12 ~05:35 ET)

- Venue order (operator, binding): NO heavy runs on hatch at any load.
  All heavy legs run on J via the job agent; local work is light only.
- Re-derived defect, measured before any run: **the requirement suite
  could not even load** — `test/eve/static/image_test.cljc`'s
  `image-reads-every-type` deftest (line 120) never closed. Reader:
  "EOF while reading, starting at line 120"; mechanical depth count:
  exactly one closer missing in the file. The frozen-verb `testing`
  (indented at deftest level) was written as a deftest body form, so
  the missing closer belonged to the outer `let` at the end of the
  DataScript `testing`. Fixed in 9b68849; the file now reads clean
  (20 top-level forms; sio.cljc 11, image.cljc 35, z1_board.clj 9 —
  all parse-checked with the Clojure reader, light local check).
  This — not load — is why no verification run ever completed: any
  scoped run at the old tip would have failed at load time.
- **BUNDLE REFRESH NEEDED**: the paren fix is 9b68849 and this STATUS
  entry is the commit on top of it. The z1.bundle staged on J must be
  refreshed to the branch tip (this commit) before the jobs below
  run, or they will test stale code.
- Filed via ~/workspace/job-agent/inbox/ (venue_pref "j", class
  "heavy", lane "static-eve/detach"):
  1. `static-eve-detach-tests.json` — scoped suite on J:
     eve.static.image-test (Z1.1–Z1.7) + eve.static.requirements-test,
     log → ~/static-eve-results/z1-tests.log on J.
  2. `static-eve-detach-b3-board.json` — B3 board on J
     (eve.static.z1-board --runs 5; gate: detach ≥2× @a per corpus),
     log → ~/static-eve-results/z1-b3-board.log on J.
- Known expectation for the tests job: Z1.2's list row asserts the
  whole-list walk reads in place, which needs lane L1's make-list fix
  (not in this tree). If that single assertion fails on J, it is the
  named L1 dependency, not a Z1 defect.
- Next: on the tests result — fix failures light-side (re-derive
  first), re-file; on green + board numbers, write Z1-REPORT.md.

## Log (finisher session, 2026-11-12 ~05:52 ET)

- **First venue round read back (coordinator, firsthand from J logs):**
  (1) the suite leg ran NO tests — both invocations died with "No
  function found on command line or in :exec-fn": the filing used the
  `-X:jvm-test` run form against an alias that carries only
  `:main-opts`. (2) The B3 board ran at the older merged tip 22ceacb:
  the static arm threw `ArithmeticException: integer overflow` on all
  four corpora. The today column's ERROR was the board's own row
  formatting — `measure-corpus` benches `@a` first and detach's throw
  discarded the whole result map; **`@a` itself never errored**. Board
  fixed (98bd40b): the static arm is tried separately, an error row now
  prints the measured today number.
- **The overflow, re-derived at this pin before any fix (measured):**
  `hash-of` (image.cljc, was line 345) computed
  `(unchecked-multiply (int (bit-and off 0xFFFFFFFF)) (int 0x9E3779B1))`.
  The literal `0x9E3779B1` reads as a **Long** (2654435761 — past
  Integer/MAX_VALUE), so `(int 0x9E3779B1)` is an `RT/intCast` range
  check that throws `ArithmeticException: integer overflow` — evaluated
  on **every** `hash-of` call, i.e. no `detach` ever completed a value
  at this pin. Verified by direct eval: the original expression throws;
  the fixed form answers. This is the same throw Transfer measured on
  the STM cell map and Sister's defect log banked as "Z1 image/detach
  intCast overflow" — one defect, three sightings. It is also the only
  `(int 0x…)` site in `src/eve/static/` (grep).
- **Fix (a3a0d20):** Fibonacci hashing in long arithmetic, wrapped once
  by `unchecked-int` — the file's own convention (cf. `read-i32-at`).
  Both edited files parse-checked (image.cljc 35 forms, z1_board.clj 9).
- **Legs re-filed** via ~/workspace/job-agent/inbox/ (lane
  "static-eve/detach", venue_pref "j", class heavy, bash -c form, bare
  `clojure` on J's PATH): `static-eve-detach-tests2.json` — suite via
  `clojure -M:jvm-test -n <ns>` per namespace (image-test, then
  requirements-test), log → ~/static-eve-results/z1-tests.log;
  `static-eve-detach-b3-board2.json` — board via classpath from
  `clojure -Spath -M:jvm-test` + `clojure.main -m eve.static.z1-board -- --runs 5`, log → ~/static-eve-results/z1-b3-board.log (the board
  prints its SPIKE-LOG section to stdout, captured in the log).
- **BUNDLE REFRESH NEEDED to this commit's tip** before either leg
  runs, or they test the pre-fix tree.
- Still owed, unchanged: if Z1.2's whole-list-walk assertion fails for
  exactly the make-list reason, merge lane L1 (tip 2eac271,
  claude/static-l1-list, sibling tree ../l1) by path remote and
  re-file the suite. On green suite + real B3 numbers: append the B3
  row to doc/static/SPIKE-LOG.md, write doc/static/Z1-REPORT.md, flip
  Z1.1–Z1.7 in test/eve/static/requirements.edn.

## Log (finisher #2 session, 2026-11-12 ~06:05 ET)

- **Venue round 2 read back firsthand (z1-tests.log on J):** the suite
  ran at 5294dfc — image-test 7 tests / 151 assertions, 44 failures +
  9 errors; requirements-test 508 assertions, 1 failure + 1 error. The
  44 failures were all detach-tables-complete (241/245 per shape).
  Every failure class re-derived at this pin (local probes on the
  direct classpath; retire/children as ground truth):
  1. **HAMT inline-pointer double-base (image.cljc)** — the walk's scan
     positions are absolute in the image buffer, but inline-ptr-at and
     the add callback take block-relative positions; the HAMT
     data-entry call sites passed absolute, adding base twice, so
     EVERY map/set value pointer was missed. Measured locally:
     detach copied 2 of 24 reachable blocks; images truncated; image
     reads followed unpatched source offsets (AIOOBE at 0x200000 in a
     232-byte image). Fixed at both call sites (t==1, t==3): form at
     vpos+4, target field at vpos+7, block-relative.
  2. **Unbounded content-size over an estimated copy window** — detach
     copied the slab class size, then scanned unbounded: for
     heap-domain blocks the class size is unrelated to content, and
     the scan read garbage past the copy (wild sizes: a 68 MB
     arraycopy in the sharing test; a scan running off the 64 KiB
     image buffer in image-read-speed). hamt-content-size is now
     window-bounded and answers SHORT (-2); detach doubles the
     window until the scan fits; header-derived sizes keep the exact
     re-copy. copy-block-bytes! no longer reads past a heap array's
     end.
  3. **StaticSio state access (sio.cljc)** — the deftype's mutable
     fields compile package-private in this toolchain (reflection
     lists b/t/na/offs/szs with NO modifiers; .-b/.-t/.-na all throw
     "No matching field found", measured locally), so builder-length
     and builder-bytes threw on every call — the Reflector errors in
     view-refuses-every-mutator, views-survive-appends,
     detach-compacts. State now goes through the IStaticSioState
     protocol (-sio-bytes/-sio-tail); image's copy path used the
     protocol too (same latent throw on (.-b …)).
  4. **Set bitmap-node content size used the map layout** — eve.map
     bitmap nodes carry a per-entry hash array (entries at
     12+4nc+4nd); eve.set nodes have none (values at 12+4nc,
     eve.set/values-start-off). The scan started 4·nd bytes into a
     set node's values and walked garbage (detach of a 200-set threw
     AIOOBE at index -605159196, identical on J and locally). Set
     branch now starts entries at 12+4nc. (Set collision nodes,
     type 2, were already scanned correctly.)
- **Local probe verification (scoped, direct classpath):** builder
  length answers; small map 11/11 blocks + readback; 200-set 54/54 +
  readback; shared-vector map 108/108 blocks, 116 relocs, exactly 10
  edges onto the shared block, readback; generated 50×vec map
  1377/1377 + readback. detach-tables-complete's two assertions are
  exactly the block/reloc counts these probes now satisfy.
- **Not Z1's — requirements-test, recorded with evidence:**
  (a) api-surface ERROR: the frozen W0 probe applies every verb to
  nil and accepts only a return or an ExceptionInfo carrying
  :eve.static/not-built; (eve.static/detach nil) throws the protocol
  IllegalArgumentException from -root-header-off before any lane code
  runs. detach IS built, so neither accepted outcome is honest for
  it. This is the same wall lane C1 documented (its run 2's only
  error) with a proposed contract amendment. Owner: W0 / collation.
  (b) requirements-covered FAIL ":W0.6 :built but
  doc/static/SPIKE-LOG.md does not exist": the file is created by
  this lane's B3 deliverable (below); the check passes once the B3
  row lands in this tree.
- **Legs filed:** suite re-run (image-test + requirements-test) at
  the current tip. **BUNDLE REFRESH NEEDED to this tip** before it
  runs. The B3 board re-run at 5294dfc was already in flight via
  finisher #1's filing; its numbers come via the coordinator.
- On the suite result: if Z1.2's list assertion fails for exactly
  the make-list reason, merge L1 (../l1 @ 2eac271) and re-file; on
  green, B3 row → doc/static/SPIKE-LOG.md, Z1-REPORT.md, flip
  Z1.1–Z1.7.

## Log (finisher #3 session, 2026-11-12 ~06:36 ET)

- **Venue round 3 read back firsthand (z1-tests3.log at e3320f4):**
  the walk fixes held — detach-tables-complete's 44 failures are
  GONE. Remaining: image-test 2F/2E, requirements-test 1F/1E (the
  two W0-owned items finisher #2 recorded). Each re-derived:
  1. **image-reads-every-type ERROR (Math.java:1371) — Z1 defect,
     FIXED (a78dac5).** The failing row is the eve array :float64
     (test line 146), not a sorted row. Measured locally: the
     array's -root-header-off is 0xC0000000 (3221225472 — slab class
     6 << 29 | block 0), delivered UNSIGNED, and detach-image's
     ordinal table keyed on `(int off)`, whose intCast throws past
     2^31. Child edges arrive sign-extended from i32 field reads, so
     only an unsigned-delivered offset (a root) hit it. Fix: the
     table keys (lookup, insert!, the srcs record) are now
     `(unchecked-int off)` — the offset's low 32 bits, bit-preserved;
     hash-of already masked to the same bits, and both delivery
     forms unify to one key. Local repro: the array detach threw
     pre-fix at lookup (image.cljc:412), exactly as on J.
  2. **image-reads-every-type ERROR (core.clj:5162) — TEST defect,
     FIXED (a78dac5).** The sorted-map row called clojure.core/subseq
     on the image value; EveSortedMap does not implement
     clojure.lang.Sorted on EITHER arm — measured locally: core
     subseq throws the identical ClassCastException on the slab
     original, and the image value is the same class with get/count
     reading in place. The row now uses eve.sorted/subseq (the
     namespace's own verb, same argument shape) and also asserts the
     image arm's subseq equals the slab arm's.
  3. **image-read-speed (Z1.7) 2F — mechanism hunt filed, not
     guessed.** Ratios 120.6× (map get) / 28.5× (vec nth). Source
     pass: the read paths mix alloc/node-i32 (a heap-array fast path
     only a JvmSlabCtx answers; the view falls to -sio-read-i32 per
     word) with slab/read-i32 — no degenerate path found by reading.
     A layered diagnostic (test/eve/static/z1_diag.clj: raw-i32,
     node-i32, read-bytes, get, nth — slab vs view, one session) is
     filed on J; the fix follows its numbers. Local probes of the
     full shapes starved under box load (11+) twice — the venue is
     the measuring instrument, per the standing order.
  4. **Board leg board3 died silently — leg design FIXED
     (d20271e).** Its log was the ns-load warning and nothing else:
     the board printed only after ALL corpora finished, so a
     mid-run death (SIGKILL-class) left no trace and no row.
     z1_board.clj now takes --only KIND and prints a progress line
     (stderr) + the finished BOARD ROW per corpus as it completes.
- **Legs filed** (lane "static-eve/detach", venue_pref "j", class
  heavy, bash -c form, HEAD printed in-log): suite round 4 →
  z1-tests4.log; the Z1.7 diagnostic → z1-diag.log; four per-corpus
  board legs → z1-b3-{map,vec,records,messages}.log.
  **BUNDLE REFRESH NEEDED to tip d20271e** before any of them runs.
- Next: on the diag numbers — fix the read mechanism in sio/image,
  re-file the suite (Z1.7 is in image-test); on board rows — B3 to
  doc/static/SPIKE-LOG.md (closes requirements-covered W0.6),
  Z1-REPORT.md, flip Z1.1–Z1.7, declare.

## Log (finisher #4 session, 2026-11-12 ~06:56 ET)

- **Round-4 reds re-derived at 017ddad (z1-tests4.log + z1-diag.log read
  firsthand; local probes on the test's own construction):**
  1. **image-reads-every-type 2E (sio.cljc:38) — the L1 dependency, exactly
     as named since this lane's first entry.** Both errors are the list-of-200
     row: `EveList.next`/`more` → `make-list` → `write-list-header!` →
     `alloc!` on the frozen view. Not a Z1 defect: the walk allocates in
     Eve's list read path. Per the standing instruction for exactly this
     failure, lane L1 (COMPLETE, tip 2eac271, J-verified: seq/next/more
     answer a lazy host seq, no make-list on read paths) is MERGED into
     this tree by path remote (b467c61, clean — no file overlap with Z1's
     work). The merge also brings doc/static/SPIKE-LOG.md into this tree.
  2. **image-reads-every-type 2F (:200-201) — TEST defect, FIXED (117b923).**
     The assertions compared detach answers with `clojure.core/=`, which
     on byte arrays is reference identity — no correct detach could ever
     pass. Measured locally: two detaches of the test's own value are
     byte-identical (Arrays/equals true, 512 B both). The assertions now
     compare bytes via Arrays/equals; the claim (determinism) is unchanged,
     the comparison now expresses it.
  3. **image-read-speed 2F (Z1.7) — mechanism hunt, round 2.** diag1's
     layers: word reads at a fixed offset are at parity (L0 raw-i32 view
     FASTER 3.50 vs 6.67 ms; L1 node-i32 6.21 vs 6.84), L2 read-bytes8 is
     slow on BOTH arms (slab 674 / view 1292 ms — Eve's generic bulk-read
     price, not a view defect), yet L3 full reads blow up (map get 73x,
     vec nth 17x). Source pass: `hamt-get` dispatches on
     `alloc/node-viewable?` — a JvmSlabCtx under wrappers resolves each
     node ONCE (alloc/node-view) and plain-loads its words; ANY other sio
     (the static view included) walks `hamt-get-words`: every word a
     separate node-* call, each re-running node-ctx*'s wrapper walk, plus
     kv-pos's per-entry skip walk, node-bytes-equal? falling back to a
     byte-copying compare (slab/bytes-equal-at? = read-bytes + bytes=),
     and node-bytes copying the value bytes out. The slab arm pays none
     of the per-word structure. Whether the delta is the per-word call
     structure itself or the fixed-offset JIT artifact in L0/L1 is what
     z1-diag2 measures (G2 descent-only per sio with precomputed key
     hashes; G4/G5 node-i32 vs raw-i32 at VARIED real block offsets;
     G6 the per-get copies). Fix follows the numbers; if the mechanism
     is the per-word fallback path's structure (Eve core machinery, not
     this lane's files), Z1.7 is reported with that mechanism named.
- **B3 row BANKED (40d4ad8)** in doc/static/SPIKE-LOG.md, exactly as the
  per-corpus legs measured at 017ddad (JVM 21.0.9, min of 5): map 16.06
  vs 1830.73 ms (0.01x); vec 51.02 vs 37375.29 (0.00x); records 71.97 vs
  19770.32 (0.00x); messages 95.64 vs 45070.88 (0.00x). **Gate MISSED on
  every corpus** — the static arm is 114–733x slower than @a. Detach as
  built is a correctness mechanism; the walk's per-block slab reads are
  the cost. (Closes requirements-covered W0.6 in this tree.)
- **Legs filed** (lane "static-eve/detach", venue_pref "j", class heavy,
  bash -c form, HEAD printed in-log): suite round 5 → z1-tests5.log;
  diag2 → z1-diag2.log. **BUNDLE REFRESH NEEDED to this tip** before
  either runs.

## Log (finisher #5 session, 2026-11-12 ~07:05 ET)

- **Round 5 + diag2 round 1 re-derived: BOTH RAN A STALE BUNDLE — the
  reds are not this tree's.** z1-tests5.log's failures cite pre-fix line
  numbers exactly: EveList.next at list.cljc:203 / more at :199 /
  make-list at :365 (the PRE-L1 layout; this tree's next/more are at
  :213/:209 and answer list-seq), and the determinism failures print
  the assertion form `(= bs (img/detach v))` where this tree's file
  (117b923) reads Arrays/equals. diag2's FileNotFound is the same
  cause: z1_diag2.clj was added in the tip commit itself, so a bundle
  older than 80a3d6f does not contain the namespace at all — the
  script never ran; there is no script defect to fix. The coordinator's
  staging of 80a3d6f evidently did not land ahead of those clones.
  Correction banked: round 5's list errors and determinism failures
  are VOID as evidence about this tip; its Z1.7 ratios (136.82x /
  19.67x) are stale-tree numbers (the read machinery they measure is
  untouched by the differing commits, so the ballpark is expected to
  stand — round 6 decides).
- **Bundle discipline applied firsthand:** bundle recreated from the
  local tip, list-heads = 80a3d6f119ca1b9349b1e5c20291cf7edb38fd35,
  scp'd to J, and verified THERE by a fresh clone: HEAD = 80a3d6f,
  image_test.cljc carries Arrays/equals, list.cljc carries the
  list-seq read paths, z1_diag2.clj present.
- **Legs re-filed** (lane "static-eve/detach", venue_pref "j", class
  heavy, bash -c form, HEAD guard in-script: a mismatched HEAD writes
  STALE-BUNDLE and exits 3 before any test runs): suite round 6 as two
  single-namespace legs (the round-5 `&&` chain suppressed
  requirements-test entirely) → z1-tests6.log (image-test) +
  z1-req6.log (requirements-test); diag2 in the classpath form its own
  docstring prescribes (clojure -Spath -M:jvm-test → java -Xmx4g
  clojure.main -m eve.static.z1-diag2) → z1-diag2b.log.
- Z1.7 source pass (this tree): hamt-get dispatches on the sio — a
  JvmSlabCtx resolves each node ONCE via alloc/node-view
  (hamt-get-view, map.cljc:552+); every other sio walks
  hamt-get-words (:472), one node-* call per word, each re-running
  node-ctx*'s wrapper resolution, plus byte-copying key compares and
  a value-bytes copy per get. The view's own -sio-read-i32 is a direct
  array read (sio.cljc:90). If diag2's G2/G4/G5 land on that structure,
  the mechanism is Eve core read machinery (eve.map/eve.deftype-proto.
  alloc), not this lane's files: Z1.7 is then reported with the
  mechanism named + its numbers, per the lane brief.
- **Round 6 first returns (HEAD-verified 80a3d6f):**
  requirements-test GREEN modulo the one attributed W0 item — 2 tests,
  510 assertions, 0 failures, 1 error = api-surface (detach nil throws
  the protocol IllegalArgumentException from -root-header-off before
  lane code runs; W0-owned, exactly as finisher #2 recorded).
  requirements-covered passes (SPIKE-LOG.md is in this tree).
  diag2 RAN this time and died at G2 on a SCRIPT defect, measured from
  its own error: g2's loop binds its accumulator as `s`, shadowing the
  sio parameter, so hamt-get-words received a Long as its sio
  ("No implementation of :-sio-read-u8 ... for class java.lang.Long").
  Before dying it landed: G0 get x50k slab 68.37 ms vs image
  2843.17 ms (41.6x); G1 keyprep x50k 4.11 ms — key hashing is ~6% of
  the slab arm's total, so the blowup lives in the descent, not the
  prep. Fix: the accumulator is renamed (this commit); diag2 re-filed
  as diag2c against the refreshed bundle.

## Log (finisher #5 session, close-out, 2026-11-12 ~07:20 ET)

- **Suite round 6 (HEAD-verified 80a3d6f):** image-test 7 tests /
  208 assertions, **2 failures, 0 errors** — both failures are
  image-read-speed (Z1.7). requirements-test 510 assertions, 0
  failures, 1 error = the attributed W0 api-surface item. The stale
  bundle correction is CONFIRMED by measurement: with the verified
  bundle, the list-of-200 rows and the determinism rows pass — the
  L1 merge and 117b923 were in the tree all along.
- **Z1.7 at 80a3d6f:** map 70.36x (slab 30.58 / image 2,151.22 ms),
  vector 28.31x (slab 11.43 / image 323.73 ms); gate 1.2x MISSED.
- **diag2c (tip 3d22d90, EXIT=0) — the bisect landed.** G0 get x50k
  slab 24.73 / image 1,536.70 ms (62x); G1 keyprep 3.01 ms (innocent);
  G2 hamt-get-words with precomputed keys: slab sio 16.02 / view sio
  1,112.21 ms (69x — the descent itself, by sio); G3 hamt-get-view on
  the slab sio 9.98 ms (the production descent); G4 node-i32 x200k at
  varied offsets, view: 2.92 ms; G5 raw -sio-read-i32, same offsets:
  2.57 ms (G4~G5: no single view op is slow); G6 node-bytes x50k slab
  209.64 / view 670.36 (byte copies 3.2x via the view); G7 nth x50k
  slab 20.69 / image 348.40 (17x, same class).
- **Z1.7 verdict: mechanism is Eve core read machinery, NAMED, not
  patched around.** hamt-get takes the resolved-node descent
  (alloc/node-view) only for a JvmSlabCtx — a concrete `instance?`
  gate at alloc.cljc:1290 with no protocol hook; a StaticSio is
  foreign by construction, so image reads walk hamt-get-words
  (per-word node-* ops, kv-pos's per-entry skip walk, byte-copying
  key compares) at ~22 us/descent vs ~0.3 us for the same code on a
  slab sio. No view-side fix exists in this lane's files; the fix
  direction (extend node resolution to foreign sios, an
  INodeView-style hook) is Eve core / collation work. Full account
  in Z1-REPORT.md. Z1.7 stays :planned.
- **Close-out:** Z1-REPORT.md written; Z1.1–Z1.6 flipped to :built
  in requirements.edn on the round-6 evidence; Z1.7 stays :planned
  (gate missed, mechanism + numbers are the deliverable). B3 row
  already banked (40d4ad8). Lane Z1 is complete at this tip minus
  Z1.7-as-gated, exactly as scoped here and in the report.
