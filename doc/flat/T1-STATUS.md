# Lane T1 STATUS — atom to atom (IMPL-PLAN §3.3, board row B4)

Branch `claude/static-t1-transfer`, tree `/home/hatch/workspace/static-eve/t1`.
Base: research tip `7402ef9` + Wave 0 merged at `cfa86c6` (contracts, `eve.static`
API, `alloc-run!` default, scoreboard, requirements manifest). All times ET.

## Pins and tools (2026-11-12)

| thing | value |
|---|---|
| eve pin | `7402ef9` (code identical to the census pin `4a92129` outside `doc/static/`) + W0 `cfa86c6` |
| java | OpenJDK 21.0.13 (on PATH) |
| clojure CLI | 1.12.6.1673 at `/home/hatch/.local/bin/clojure` |
| deps | `CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig` → `:mvn/local-repo "/home/hatch/m2repo"` |
| tests | `clojure -X:jvm-test`, scoped `-n eve.static.transfer-test` |
| pushes | none — this box has no GitHub credential for SeniorCareMarket/eve (coordinator correction); commits are local, cross-lane code via path remotes `w0`, `z1`, `z3` |

## What T1 owns and the shape chosen

Files: `src/eve/static/transfer.cljc`, the foreign-value branch of
`resolve-new-ptr` in `src/eve/atom.cljc`, tests at
`test/eve/static/transfer_test.clj` (namespace and test vars are fixed by W0's
`requirements.edn`: `transfer-in-process`, `transfer-under-writes`,
`stm-foreign-value-atomic`, `transfer-cross-process`,
`transfer-refuses-local-reference`, `transfer-direct-equal`).

Design, from the evidence read at this pin:

- **The seam.** `resolve-new-ptr` (atom.cljc:852) already routes a foreign
  slab root by the receiving domain's `:foreign-policy` — `:throw` (default)
  or `:copy` (materialize via `eve->clj`, rebuild). T1 adds a third policy,
  **`:zip`**: detach the foreign value to an image and place it — no host
  collection is built. `set-foreign-policy!`'s precondition widens to
  `#{:throw :copy :zip}`. `eve.static/transfer!` (W0's frozen signature
  `[a x]`, returns a) is the explicit form: it detaches x (under the source
  pin when x is an atom) and swaps it into a through the same placement.
- **Nested foreign values.** A plain collection containing a foreign slab
  value is written by `write-plain-collection!` → `eve.codec`, whose nested
  check throws (codec.cljc, "cannot be put in this one"). Under `:zip`,
  `resolve-new-ptr` pre-walks the plain value and replaces each foreign
  `IEveRoot` with its placed same-domain counterpart before the writers see
  it. Under `:throw` / `:copy` the nested behaviour is exactly today's.
- **Detach (hand-built image, the Wave 0 default until Z1 lands).** A
  relocating block walk over the kinds `eve.retire` reads, ported from the
  validated probes (`doc/static/probes/lib.clj` `image!`, Java
  `ImageCopier`): byte positions of every pointer, each block copied once,
  sharing kept. Block sizes: classes 0–5 copy the class block size (source
  geometry, JVM `JvmSlabCtx` block-sizes), class 6 copies its kind-computed
  used size (self-delimiting kinds) or descriptor data length. Pointers in
  the image hold buffer offsets; the block + relocation tables are written
  with `eve.static.contract` (FORMAT-V1 §9). A `0x15` reference scalar
  anywhere in a value block is refused by name (T1.5; FORMAT-V1 §3, A12).
- **Place (per-block default until Z3/Z2 land).** Validate the whole image
  (header, tables, every offset/owner/target) *before* claiming anything —
  a throw inside a swap frees nothing (atom.cljc:982,1085; census §4.2), so
  validation is the point of no return's front side. Then claim each block
  (`alloc/alloc-run!` per class where W0's contract applies, `-sio-alloc!`
  for the overflow class), copy bytes, rewrite pointers from the relocation
  table. Placed inside a swap attempt the blocks are the attempt's own
  logged allocations: a won CAS counts them by `hold-root!` reachability
  (A15's conditions: allocated, rows at zero, layouts `refs` reads), a lost
  CAS frees them by `drop-root!` + `free-unheld!` — exact either way.
- **STM (T1.3).** The A16 claim — a two-ref transaction whose second value
  is foreign throws at store time and leaves the first ref installed — is
  being re-derived at this pin before any fix (probe below). The mechanism
  by reading: `eve.stm/commit!` installs ref-by-ref (`mapv install!`) with
  no storability pre-check; the nested foreign value throws inside the
  second ref's cell swap, after the first ref's cell CAS already won. The
  fix inside T1's files is constructive: with `:zip` on the refs' cell
  atoms (public `ea/set-foreign-policy!` on `(:cell r)`), the store no
  longer refuses — the value is zipped into each cell's domain and the
  transaction commits both refs. All-or-nothing against *arbitrary*
  mid-commit store failures is a property of `eve.stm`'s commit protocol
  (pre-flight or rollback in `commit!`), which is not a file T1 owns; that
  residual is reported, not silently absorbed.

## Log

- ~01:50 AM — tree cloned, branch cut. Read IMPL-PLAN §3/§3.1/§3.3-T1,
  FORMAT-V1 §1/§2/§9, MEASUREMENTS M5/M6, ASSUMPTIONS A15/A16, census
  TYPES-AND-DEREF-2 §1/§4/§6, the probe library (image!/ingest!), atom.cljc
  (resolve-new-ptr, swap-attempt!), stm.cljc (new-cell, cell-cas!, commit!).
- ~02:00 AM — merged Wave 0 (`cfa86c6`) via path remote `w0`: contract.cljc
  (header + image tables), eve.static API (`transfer!` → this lane,
  signature `[a x]`), alloc-run! default, requirements manifest fixing my
  test namespace/vars, scoreboard (B4 baseline arm = one run through
  `:foreign-policy :copy` — W0.6's SPIKE-LOG row still owed by W0).
- Next: A16 re-derivation probe; tests first (red); baseline B4 in this
  session; then transfer.cljc + the `:zip` branch.
- ~02:05 AM — A16 probe written (`doc/static/probes-t1/a16_half_commit.clj`,
  commit `e1f296f`). **Not yet run**: box load 11–17, heavy runs gated at
  load1 ≤ 8. The claim stands UNVERIFIED until the probe runs.
- ~02:10 AM — tests first, committed red (`5258f91`):
  `test/eve/static/transfer_test.clj` — all six requirement tests. T1.1's
  exactness is checked three ways (B's per-class free-count deltas equal
  the image census; reach count equals the image block count; the `:x`/`:y`
  shared subvalue keeps ONE root header offset in B — through `:copy`
  sharing is lost, per M7, so this is the no-host-collection witness).
- ~02:20 AM — implementation committed (`dca77a7`): `transfer.cljc`
  (detach walk ported from the probes with fused per-kind sizes, image
  assembly via `eve.static.contract`, full pre-claim validation, place by
  `alloc-run!` per class, `transfer!`/`transfer-direct!`, `zip-root!` /
  `zip-nested`) and the `:zip` branch in `resolve-new-ptr` (root + nested)
  with `set-foreign-policy!` widened to `#{:throw :copy :zip}`;
  `eve.atom` reaches transfer by `requiring-resolve` (no require cycle).
  Two corrections to the design notes above: (1) block sizes — only the
  kinds whose size is their allocation (scalar blocks, deftype instances,
  obj blocks) record the class size; self-delimiting kinds record their
  used size, which places into the same class; an overflow-class block of
  a non-self-delimiting kind is a typed `:block-size-unknown` refusal, not
  a guess (Z1 owns generalizing). (2) Relocation `:owner`/`:target` in
  the image are block-table **ordinals** (the probes' semantics),
  `:position` the absolute buffer offset — flagged for Z3/collation.
  Ordinals are assigned at discovery so a shared block is copied once.
- Next, all load-gated: run the A16 probe → run the scoped transfer tests
  → iterate to green → re-fetch `z1`/`z3` and merge → measure B4 (both
  arms, one session) → T1-REPORT.md. Expectation from M6: without Z4's
  count-at-zip, the tally count walk (~1 µs/block) likely keeps B4 over
  the ≤ 5/26/9/26 ms gate on the bigger corpora — the gap gets reported,
  never relabeled.
- ~02:50 AM — Z1 (`162447e`) and Z3 (`582c017`) merged (both clean).
  Composition rewired (`8bc522a`): image assembly gained the §9 patch pass
  (pointer fields hold buffer offsets — Z3's `validate!` checks the stored
  word at every relocation position, so this was a real format bug in my
  first cut, caught by reading `validate!`); `place-image!` and
  `zip-root!` now delegate to `eve.static.place` (`place` / `place!`);
  my parallel place/validation deleted. Detach stays T1's walk: **Z1's
  detach has no process-local-reference screening** (grep over
  image.cljc + sio.cljc at `162447e`: no 0x15/reference/handle hits; Z1's
  requirements Z1.1–Z1.7 carry no refusal) — FORMAT-V1 §3's refusal lives
  on the transfer path until Z1 grows it. Noted for collation.
- ~03:00 AM — **Measured defect in Z3's committed code**: `place.cljc` at
  `582c017` does not parse — `execute-plan!` is missing one close paren
  (line 299; the reader reports EOF from line 272). My merged file was
  byte-identical to the committed blob (`git show … | cmp`). Z3's
  working tree already carries the fix (plus two further uncommitted
  hunks I did not take). Applied the one-paren fix locally as `1887f70`,
  labeled as a collation fix for the coordinator/Z3 to reconcile.
- ~03:05 AM — environment notes after the box/service restart: the exec
  PATH lost `/home/hatch/.local/bin` (java is
  `/home/hatch/.local/jre21/bin/java`, Temurin 21.0.13 — prefix PATH for
  every JVM run); `:jvm-test` is a `-M` alias (`clojure -M:jvm-test -n
  <ns>`), not `-X` (the `-X` attempt fails with "Key is missing value").
  First scoped test run died to the restart (SIGTERM, no verdict); the
  second reached compilation and stopped on the place.cljc paren above.
- ~02:35 AM — **A16 REPRODUCED at this pin** (probe run, load1 2.45):
  pin `7402ef9` + W0 `cfa86c6`, Clojure 1.12.0, Java 21.0.13. The two-ref
  `dosync` (ref-set* ra 1, ref-set* rb <foreign cell map of rc>) threw at
  store time — "An Eve value allocated in a different slab domain cannot
  be put in this one…" (the codec's nested-value refusal, as the census
  mechanism predicts: the foreign value sits under `:value` in rb's cell
  map) — and left **ra = 1 installed, rb = 0**. The claim is a measurement,
  not a phantom; T1.3 proceeds as designed (constructive fix via `:zip` on
  the refs' cell atoms; the general mid-commit atomicity residual belongs
  to `eve.stm`'s owner and goes in T1-REPORT.md).

## Update 2026-11-12 ~04:40 ET — detach walk debugged; two cross-lane defects measured

- **First full test run**: 6 tests, 11 assertions, 1 failure + 4 errors. Root
  causes found by probes under `doc/static/probes-t1/` (each probe's output
  is the measurement; pins: W0 `cfa86c6`, Z1 `162447e`, Z3 `582c017` as merged):
  1. `block-scan` NPE: `(when (== vlen 7) (inline-ptr ...))` yields nil into
     `(== ip -1)`. Fixed (if-form) at all four sites.
  2. **Walk emitted no relocations for newly discovered targets** (only for
     already-seen ones) — every tree image had an empty relocation table.
     Fixed in `bbf4d5d`.
  3. **The real detach defect**: in `block-scan`'s HAMT clause, a paren slip
     closed the `(if (== t 1) ...)` with no else; the second `if` became a
     separate body form whose set-collision fall-through was the clause's
     value — so EVERY HAMT node scanned as an 8-byte empty collision node.
     Bisected by probes: `fetch-block` returns the right 128 bytes;
     `block-scan` on them returned `{:size 8 :ptrs []}`; `u8`/`i32`/`case`
     micro-probe all correct; the reader-parsed structure showed the missing
     else. Fixed in `96279f0`: bitmap/collision scans are now named fns
     (`hamt-bitmap-scan`, `hamt-collision-scan`) under a flat `cond`. The
     cell map now walks to 6 blocks / 5 relocs, matching `eve.retire/reach`.
- **Cross-lane defect 1 (Z1, at `162447e`)**: `eve.static.image` did not
  compile — `content-size` takes >4 primitive args ("fns taking primitives
  support only 4 or fewer args", image.cljc:135); W0's delegate swallowed
  the load failure as "not built yet". Z1's newer tip `22ceacb` fixes
  exactly this; merged (`04127ae`).
- **Cross-lane defect 2 (Z1↔Z3 format gap)**: Z3's `chunk->image` derives
  the relocation table's end from the header `length` when `typetab` is 0;
  FORMAT-V1 §2 requires `length` to be a multiple of 8, and Z1's assembly
  sets `length = align8(tables + 8n + 12r)`, `typetab = 0`. For an odd
  reloc count r the span is 12r+4 — not divisible by 12 — and Z3 refuses
  ("the image's tables do not divide evenly"). Measured on T1's own image
  of the STM cell map (r=5) at Z3 `582c017`; the check is unchanged at
  Z3's new tip `8cdb908` (merged, `6df7c29`, subsuming my local one-paren
  collation fix). Pending: does Z1's own detach→Z3 place hit it too
  (probe 8 rerun owed now that Z1 compiles)?
- T1's detach stays T1's walk for now (Z1's detach also has no
  process-local-reference screening — FORMAT-V1 §3's refusal is T1.5's
  requirement and lives on this path).

## Update 2026-11-12 ~05:35 ET — VENUE SHIFT (operator directive 05:19 ET)

NO heavy runs on hatch from 05:19 ET, at any load — local work is light
only. The local transfer-test run started minutes before the order was
stopped with no verdict (log shows only the runner banner); it is NOT a
result. All heavy legs now go through the job agent to J.

Composition state at the shift: the detach walk is fixed and verified by
probes on hatch (block-scan verdict on the STM cell-map node: size 128,
both value pointers; round-trip through Z3 place reads back equal;
stm_zip probe: the :zip policy swaps the nested foreign cell map in
correctly, deref-ref reads the donor's map). Requirement-suite verdict
and B4 numbers are owed from J.

Jobs filed (~/workspace/job-agent/inbox/):
- `static-eve-t1-transfer-tests-1.json` — the 6-test requirement suite
  (`clojure -M:jvm-test -n eve.static.transfer-test`); log on J:
  ~/static-eve-results/t1-transfer-tests-1.log.
- `static-eve-t1-b4-1.json` — board row B4 (doc/static/probes-t1/b4_bench.clj,
  scoreboard method/capacities, both gate legs printed per corpus);
  log on J: ~/static-eve-results/t1-b4-1.log.

**BUNDLE REFRESH NEEDED**: both jobs clone ~/static-eve-bundles/t1.bundle
on J and check out claude/static-t1-transfer — the bundle must be
refreshed to local tip `7371532` (or later) before they run; everything
they need is committed locally (tree clean at filing time).

## Update 2026-11-12 — finisher session: first J results + re-derivation

First venue round (coordinator-read): suite 6 tests / 152 assertions,
**6 failures, 1 error**; the B4 leg died on the stale bundle (re-filed by
the coordinator as t1-b4-2). Re-derivation of the suite failures at this
pin, from source:

- **transfer-in-process lines 94, 98, 118 — TEST CONSTRUCTION DEFECT.**
  `eve.alpha/atom` is `eve.atom/atom`, and an anonymous `(e/atom x)`
  resolves through `resolve-heap-domain` with a nil id — a process-wide
  cached DEFAULT domain (atom.cljc, `heap-domain-cache`). The test built
  a and b as anonymous atoms, so **both lived in one domain**: the
  line-94 "A unchanged" free-count check measured B's placement inside
  the shared domain (A "lost" 2221 class-0 blocks; B's placement
  consumed 2220), the line-98 per-class deltas were polluted by the
  second atom's creation inside the same domain, and the line-118
  "foreign" nested value was not foreign at all — same domain, so the
  writers correctly accepted it and nothing threw. The transfer code is
  not implicated by these three failures. Discriminator: named domains
  (`ea/atom-domain` + `lookup-or-create-mmap-atom!`) are the API's
  separate-domain construction; the fix is in the test.
- **Line 104 (x-off 2223 vs y-off 2220) and the cross-process failure +
  error — NOT yet re-derived.** Both are measured by
  `doc/static/probes-t1/finisher_probe.clj` (P1 staged counts and
  shared-block offsets in the separate-domain construction; P2 the
  nested refusal against a genuinely foreign value; P3 the
  cross-process leg replicated with receiver stderr captured whole, plus
  an in-process place of the same image to isolate image vs subprocess).
  Probe leg filed to J; verdicts land when its log returns.

**BUNDLE REFRESH NEEDED** to the finisher tip (probe commit) before the
probe leg runs.

## Update 2026-11-12 — finisher #2: probe verdicts received, residuals staged

Coordinator delivered the J probe log (t1-finisher-probe3.log). Verdicts:

- **P0 CONFIRMED**: anonymous `e/atom` atoms share one sio-root — the
  round-1 failures at lines 94/98/118 were the test's construction
  (a and b in ONE default domain), not transfer defects.
- **P1 (separate domains)**: detach moved A by [0 0 0 0 0 0]; transfer!
  moved A by [0 0 0 0 0 0]; @b = v, @a = v, A's root unchanged; census
  nblocks 2295 == reach-count 2295, exact.
- **P2**: a genuinely foreign nested value under the default policy
  THREW (ExceptionInfo, the domain-crossing message). Refusal path works.
- **P3**: in-process place of the identical image ok/equal; the receiver
  subprocess dies on its own harness — ClassCastException String→URI at
  receive.clj:2 (the generated script's `java.nio.file.Path/of` call).
  The image and the transfer path are exonerated; the fix is the
  script's path construction (Paths/get).
- **Residuals staged for disposition** in
  `doc/static/probes-t1/finisher_probe2.clj` (committed this session):
  R-a census-minus-delta [2 1 0 0 0 0] (Q2 staging: freed on supersede?
  per-transfer or one-time? Q2b writer-route control), R-b the shared-
  block offsets (Q1: does the premise hold in A itself? host-assoc vs
  slab-assoc construction), R-c reads allocating [3 0 0 0 0 0] in A
  (Q3: no-transfer control atom), Q4 whether a named mmap domain can
  hold a process-local reference (T1.5's construction).

**BUNDLE REFRESH NEEDED** to the tip carrying finisher_probe2.clj
before the probe2 leg runs.

## Update 2026-11-12 — finisher #3: B4 delivered, messages row dispositioned

Coordinator delivered t1-b4-4.log (pin a1eaab6, min of 5 after 2
warmups, zip-ok = tf-ok = true on every measured corpus):

- map-10k: detach 844.68, zip 807.32, sum 1,652.00, transfer!
  1,539.74 ms — ratio 0.93 (≤2× PASS), cap 5 ms MISSED.
- vec-100k: 15,699.44 / 12,347.38 / 28,046.81 / 28,645.78 — ratio
  1.02 PASS, cap 26 ms MISSED.
- records-10k: 4,230.98 / 3,395.83 / 7,626.80 / 8,371.81 — ratio
  1.10 PASS, cap 9 ms MISSED.

Composition carries no tax (transfer! ≈ detach+zip); caps missed
~300–1000×. STALE-COMPONENTS CAVEAT: this pin's walk is pre-fix —
Z1's four walk fixes (tip e3320f4) and Z3's place advances
(beb3ce9+) are not merged here, so this row measures composition
correctness; the caps' verdict belongs to the collated tree. Row +
caveat appended to doc/static/SPIKE-LOG.md (created by this lane;
W0.6's :copy baseline row still owed by W0, noted in the file).

MESSAGES-5K ROW ABSENT — dispositioned: the complete log on J is
6 lines, ending after records-10k: no messages row, no BENCH DONE,
no error/refusal text. A refusal would have printed inline (the
bench captures detach refusals), so this is a truncated run —
process died mid-corpus without a message (SIGKILL-class), not a
measurement. b4_bench.clj gained a `--only <corpus>` filter
(finisher #3); a messages-only leg is filed to close the row.

**BUNDLE REFRESH NEEDED** to the finisher-#3 tip before the
messages-only leg runs. Probe4 (finisher_probe2) still in flight.

## Update 2026-11-12 — finisher #4: probe4 dispositions applied; messages death named

Probe4 (t1-finisher-probe4.log, read firsthand by finisher #4)
dispositions ALL residuals:

- **Q1 (sharing)** — host-assoc construction in A: x/y offsets
  [1217 2218], NOT shared (the writers duplicate a host value stored
  twice); slab-assoc (assoc of one pinned slab block, in-domain):
  [1217 1217], shared; after transfer A2→B2: [7 7], reach 1262 ==
  census 1262, value equal. Sharing is a property of in-domain
  construction; transfer preserves exactly the sharing that exists.
- **Q2 (+3 census-minus-delta)** — transfer#1 residual [-2 -1 0 0 0 0];
  Q2b writer-store control residual [0 0 0 0 0 0] EXACT (accounting
  method sound); after churn transfer#2 residual [-1 0 0 0 0 0]. The
  shortfall is destination bookkeeping freed when the placed root
  supersedes the fresh atom's initial root structure — reach ==
  census throughout, so no value blocks are unconsumed.
- **Q3 (read allocation)** — no-transfer control atom: deref moved
  free by [3 0 0 0 0 0], an equality check [6 ...] total, pinned gets
  no further movement. Reads allocate a few class-0 blocks in Eve
  generally; NOT transfer-related.
- **Q4 (T1.5 construction)** — a named mmap atom CAN hold a fn-bearing
  value; detach REFUSES it with {:eve.static.transfer/refusal
  :process-local-reference} — correct by design.

Test fixes applied (commit c7706cf): T1.1 rewritten on the probe's
constructions — named domains throughout (fresh-atom), the shared
value built slab-side (:part stored once, assoc'ed to :x/:y from the
pinned current value), the premise asserted in A itself, A's
free-counts checked after transfer! and BEFORE any deref (Q3
ordering), census exactness via reach == census + the Q2 residual
asserted as the measured [2 1 0 0 0 0] + a Q2b writer control
asserting its census exactly. T1.5 rebuilt on a named mmap atom per
Q4. Suite leg filed (t1-suite2.log) — verdict rides on delivery.

**MESSAGES-5K SILENT DEATH — NAMED.** Local probes
(msg_probe.clj, msg_probe2.clj, committed): the corpus completes
in-process at reduced n with stderr/heap visible — n=500: store
5.1 s, detach 13.9 s, place 7.3 s, transfer 18.5 s (throttled hatch
core); n=1000: detach steady-state 33.6/32.9 s per call (not JIT),
~2.3 ms/block. No error, no OOM at -Xmx1g, no unscannable shape.
The walk's per-block cost on message shapes (small HAMT maps,
strings, sets; fetch-block's per-block sio reads incl. the
wide-window read for overflow-class HAMT nodes) makes the canonical
bench config — correctness arms + (2 warmup + 5 runs) × 3 arms ≈ 24
detach-family calls at ~71k blocks — exceed the 3600 s job leash;
SIGKILL mid-measure prints nothing, which is exactly the observed
291-byte header-only log, both runs. Disposition: a bench-config
death, not a corpus or walk correctness defect. A reduced-runs leg
(--only messages-5k --warmup 0 --runs 1, START/END timestamps in the
log) is filed as t1-b4-messages2.log to land the fourth row; the row
will state the reduced config and why.

**BUNDLE REFRESH NEEDED** to the branch tip before both filed legs
run (suite2 + b4-messages2). The filings name c7706cf in their needs
text; the tip has since advanced by STATUS-only commits — the legs'
HEAD lines will read the refreshed tip, whose test/bench code is
byte-identical to c7706cf. On delivery: suite green → T1-REPORT.md
(with the explicit A16 disposition), requirements flips T1.1–T1.6,
B4 messages row into SPIKE-LOG, declare.

## Update 2026-11-12 — finisher #5: suite2 re-derived as a STALE-BUNDLE re-run; suite3 filed

**Suite2's 5 failures are NOT failures of the rewritten tests.** Read
firsthand: every failure is in `transfer-in-process`, and the failure
texts are the ROUND-1 test's shapes — per-class "class 0/1 delta"
assertions of the form `(= census delta)`, the host-assoc sharing
premise, and actuals byte-identical to round 1 (A lost exactly
[2221 2 77 1 0 0]; census 2215 vs delta 2220; x-off 2223 vs y-off
2220; the default-policy nested swap not throwing). Provenance:
`git show ae9366e:test/eve/static/transfer_test.clj` — at that tip
T1.1 still built `(e/atom v)` / `(e/atom nil)` (anonymous atoms, ONE
default domain: the consumed P0 cause) and asserted census == delta
per class. The bundle staged on J when suite2 ran held ae9366e;
finisher #4's flagged refresh to c7706cf/c9e64be never landed before
the leg executed, and the suite2 log carries no HEAD line to catch
it. So suite2 re-measured the pre-rewrite tests and reproduced round
1 exactly. It is evidence about the bundle race, not about c7706cf.

Actions: bundle recreated at c9e64be, list-heads verified
(c9e64be2640…), scp'd to J; suite re-filed as t1-suite3.log whose
run prints HEAD — a log reading any other tip is stale-bundle
evidence and must be discarded unread. DISCRIMINATOR for the real
verdict: the rewritten T1.1 asserts the Q2 residual vector
[2 1 0 0 0 0], checks A's free counts before any deref, builds the
shared value slab-side, and uses named domains throughout — round
1's five failure shapes cannot recur in that code except as new
defects with new numbers.

**B4 messages row banked** in doc/static/SPIKE-LOG.md (reduced
config stated: detach 14,078.28, zip 12,063.03, sum 26,141.31,
transfer! 45,284.57 ms, ratio 1.73 PASS, cap 26 ms MISSED). B4
complete: 4/4 ratio-pass, 4/4 caps missed at pre-fix pins.

On suite3 delivery: green → T1-REPORT.md (with the explicit A16
disposition), requirements flips T1.1–T1.6, declare. Red with NEW
numbers → re-derive at c9e64be before any fix.

## Update 2026-11-12 — finisher #6: suite3 red with new numbers; provenance caveat; probe3 filed

**Suite3's log carries NO HEAD line** (first line is the runner
banner) — finisher #5's filing evidently echoed HEAD outside the
redirected region. By the lane's own discriminator the run's tip is
unproven and the log is not verdict-grade. Its failure texts,
however, are the REWRITTEN tests' assertions (messages that exist
only at c7706cf+), and 83b4e88 differs from c9e64be in docs only —
so the 3 failures are treated as leads and re-derived below; the
verdict will ride on a correctly-filed suite4.

Suite3: 6 tests / 150 assertions, 3 failures, 0 errors — all in
transfer-in-process, all assertion-ENCODING defects on re-derivation
at c9e64be (no transfer-code change):

1. **L132 (residual [2 1 0 0 0 0] vs actual [-1 1 0 0 0 0])** — the
   test evaluates `(free-counts b)` AFTER `(is (= v @b))` and
   `(is (= v @a))`: deref + equality allocate in B's domain before
   the count (probe2 Q3: one deref moves [3 0 0 0 0 0]; 2 − 3 = −1
   reproduces the actual exactly, class-1 untouched). The test
   applied Q3 ordering to A and violated it for B. Fix: stage B's
   count immediately after transfer!, before any deref.
2. **L139 (`(= (image-census (detach c)) census`)** — cross-
   construction census equality, contradicted by probe2 Q1 itself:
   writers duplicate a host block stored twice; the test's v stores
   `shared` three times (:part/:x/:y), A's slab construction once
   (suite censuses: 3330 vs 1262). Q2b never claimed cross-
   construction equality — it measured the control against its OWN
   census. Fix: the control is judged against its own census only.
3. **L142 (`(= cvec delta-c`)`)** — the control's delta against the
   TRANSFER's census vector: wrong on its face given (2). The
   control's residual vs its own census reads [2 1 0 0 0 0] in
   suite3, where Q2b reported [0 0 0 0 0 0] — but the test evaluates
   `(free-counts c)` AFTER `(st/detach c)` (L139), so detach's
   effect on its source domain is inside the measurement. Which
   staging is sound is probe3's question, not a guess.

**Probe3 filed** (`doc/static/probes-t1/finisher_probe3.clj`,
75016e3): stages free-counts immediately around detach (arm D),
transfer! before any deref (arm T), the test's exact assertion
order replicated (arm R — must reproduce [-1 1 0 0 0 0]), and the
writer store counted BEFORE its census detach (arm W). Bundle
recreated at 75016e3, list-heads verified, scp'd to J by the lane
before filing. (First filing carried a malformed run string —
an unclosed command substitution in the HEAD echo; the lane
removed the job from J's queue inbox before it ran and re-filed
clean as probe3b.)

On probe3 delivery: apply the three test fixes per its numbers,
re-file the suite (HEAD inside the redirect), iterate to green,
then T1-REPORT.md + flips + declare.

## Finisher #7 — probe3 verdicts applied; suite GREEN; lane COMPLETE

**Probe3 delivered** (`t1-probe3.log` on J, HEAD 75016e3 = the probe
commit; this branch's tip at read time was its STATUS-only child —
reconciled, verdict-grade). Arms: **D** detach's effect on its source
= [0 0 0 0 0 0]; **T** transfer counted clean: residual vs census
[2 1 0 0 0 0], reach 1262 == census 1262, deref effect on B afterwards
[3 0 0 0 0 0]; **R** the test's exact order reproduces
[-1 1 0 0 0 0] byte-exact — deref pollution confirmed as suite3's L132
mechanism; **W** writer control vs its OWN census (3330, not A's 1262)
counted clean: residual [2 1 0 0 0 0]. Arm W kills probe2 Q2b's
residual-zero reading; the correction is banked in the test's header
comment and in T1-REPORT §3 (discriminator: probe3's controlled
staging).

**Fixes (2c6fefc, test file only):** B's allocated-set assertions moved
before any deref (arm T staging); the writer control is judged against
its own census — reach == own census, residual [2 1 0 0 0 0] — with
its count staged immediately after the swap (arm W staging); header
comment rewritten to the probe3 verdicts.

**Suite round 4 GREEN** (`t1-suite4.log`, first line
HEAD=2c6fefc…, bundle staged + list-heads verified by the lane before
filing): transfer-test **6 tests / 150 assertions, 0 failures,
0 errors**; requirements-test 508 assertions, 0 failures, 1 error =
W0's api-surface nil-probe, attributed (collation carries the
amendment as a flagged commit).

**Close-out:** T1.1–T1.6 flipped to `:built` in requirements.edn;
`doc/static/T1-REPORT.md` written — mechanism, the probe chain,
B4 complete (4/4 ratio-pass, 4/4 caps missed at pre-fix pins; caps'
verdict belongs to collation), and the explicit A16 disposition:
the two-ref dosync half-commit CONFIRMS at this lineage (ra=1
installed, rb=0, second ref's foreign value threw at store time);
`:zip` removes the refusal (T1.3 proves both-commit under it); general
mid-commit atomicity is `eve.stm` commit-protocol territory, a named
residual T1 does not own.

**Lane T1 is COMPLETE.**
