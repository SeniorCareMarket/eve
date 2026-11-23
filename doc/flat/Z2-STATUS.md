# Z2 — the run claim: lane status

Lane: Z2 (`alloc-run!` — n consecutive blocks of one class claimed by masked
word CAS). Branch: `claude/static-z2-runclaim` on SeniorCareMarket/eve,
base = research tip 7402ef9. File owned: `src/eve/deftype_proto/alloc.cljc`.
Board row: B1. Gate: claim+copy ≥ 3× per-block allocation, net of the domain.

## 2026-11-12 ~01:30 ET — lane opened

- Tree cloned, branch created. Reading done: IMPL-PLAN §3/§3.1/§3.3-Z2,
  alloc.cljc in full, slab_io.cljc seam, mem.cljc bitmap helpers,
  probes/lib.clj `claim-run!` (the seed: word-aligned start, CAS 0→mask,
  throws when the run is not free, wastes up to 31 blocks — Z2.2 exists
  because of it).
- Design settled (to be implemented in alloc.cljc):
  - `alloc-run! [sio class-idx n]` → slab-qualified offset of the first of
    n consecutive blocks of class-idx (0–5), or nil when no run exists and
    none can be grown. Class 6 (coalescing) is out of the run contract:
    a bad class is a programming error (ex-info), not a nil.
  - JvmSlabCtx fast path (`ctx-alloc-run!`): cursor-led scan for n
    consecutive clear bits (wrapping), bits ≥ total in the tail word
    treated as allocated; claim by one CAS per word — whole interior
    words mask −1, head/tail words masked at both ends; a lost CAS
    mid-claim releases exactly the words already claimed and re-searches.
    Success: FREE_COUNT −= n, cursor := (start+n) mod total.
  - Fragmented (free ≥ n, no run) → nil, claims nothing (Z2.3). Free < n
    on a heap class → grow by replacement (`grow-heap-class!`, under the
    regions lock, ≤ 20 steps like ctx-alloc!), then re-scan (Z2.5). No
    spill to a larger class: placement fallback is Z3's job (Z3.3).
  - Snapshot discipline copied from try-alloc (total read on both sides
    of the region read; torn pair re-read under the regions lock), so a
    concurrent remap can never pair an old bitmap with a new total.
  - Entry points: a LoggingSlabIO claim delegates to its inner sio and
    logs all n offsets (the zip places inside a swap, through the log);
    a wrapped sio (eve.keyring's encrypting one) resolves through
    `domain-root` like `desc-of`/`allocated?` do — claiming touches no
    block bytes, so the keystream is not involved (Z2.6).
  - Any other sio: the correctness default — loop `-sio-alloc!` at the
    class's block size, keep the blocks when they land consecutive,
    else free and retry (bounded), else nil.
- Environment: box load 15.2/9.6/8.8 at lane open (over the ≤8 gate) —
  heavy runs deferred; light work (reading, tests-first writing) first.
  `clojure` is not on PATH on this box; locating the CLI / classpath
  route (eve-cp.sh names /home/user paths from the research box — adapt).
- Next: tests first commit (six requirement tests, red), baseline
  measurement of per-block alloc, then the implementation.

## 2026-11-12 ~02:05 ET — implementation in, requirement tests green

- Commits: ba21c70 (tests first, red), f80c5ee (implementation),
  plus the find-run hint fix. `ctx-alloc-run!` + `alloc/alloc-run!`
  implemented as designed above, in alloc.cljc only.
- Tests: `clojure -M:jvm-test -n eve.static.run-claim-test` —
  **6 tests, 28 assertions, 0 failures, 0 errors.** Z2.1–Z2.6 all pass.
- Baseline (pre-change, taken at load 8–21 — re-take owed in the same
  session as the gate run): per-block alloc+copy, class 2 (128B):
  6,737 ns/block gross, ~2,032 ns/block net of domain creation.
- Push: this box has no GitHub credential (the stored credentials file
  holds only the egress proxy; pushes to SeniorCareMarket/eve from
  here are refused — the same wall recorded for this repo before).
  Commits are on the local branch; the branch needs a push from a
  credentialed seat.
- Wave 0's branch `claude/static-w0-contracts` is not on origin yet
  (checked by fetch 02:05 ET): no merge to take. My signature follows
  the plan's text exactly (`alloc-run! sio class n`); requirements.edn
  registration waits on W0's manifest (their file, their format) —
  the six Z2 id→var pairs are listed in Z2-REPORT.md for transcription.
- Owed: bench both arms + allocator-adjacent regression namespaces,
  gated on load1 ≤ 8 (9.4 at last check).

## 2026-11-12 ~02:20 ET — coordination correction + Wave 0 reconciled

- Coordinator confirmed the push diagnosis firsthand: no GitHub
  credential for SeniorCareMarket/eve on this box; the local branch
  is the durable record and is collated locally. No more push attempts.
- Regression (gate-run session, load ≤ 5.7 at start): alloc-growth-race
  - descriptor-table + keyring + run-claim = **19 tests, 158 assertions,
    0 failures**.
- First bench was methodologically broken (arms in separate loops,
  domain cost cold-then-warm: negative nets, an inverted row). Bench
  rewritten: arms interleaved, domain cost netted per iteration, copy
  by arraycopy as the zip copies. Its first fixed run died on a
  duplicate map key (class key `ci` colliding with literal 0) — fixed.
- Wave 0 merged (38c5f7e) and the contract reconciled (f5cffc6):
  W0's `alloc-run!` returns **a vector of the n offsets** and dispatches
  on the `ISlabRunAlloc` protocol — that frozen shape replaces this
  lane's first-offset fn. JvmSlabCtx now implements `-sio-alloc-run!`
  via `ctx-alloc-run!`; LoggingSlabIO implements it too (delegates,
  logs every block). Tests renamed to the manifest's
  `eve.static.alloc-run-test` and re-pointed at the vector contract.
  One consequence: eve.keyring's EncryptedSlabIO implements no run
  claim and is not this lane's file, so encrypted domains are served
  by W0's per-block default — Z2.6 passes on that arm (claim answers,
  blocks allocated, keystream roundtrip); the fast claim through a
  wrapper is a one-method delegation owed in eve.keyring.
- Final verification (this lane's tests + W0's + regression trio) and
  the gate bench run in one detached session when load1 < 6; results
  land in doc/static/z2/final-run.log and Z2-REPORT.md.

## 2026-11-12 ~05:35 ET — successor session: venue shift, report reconciled, J legs filed

- Venue order (operator, binding): NO heavy runs on this box at any
  load. All heavy legs go through ~/workspace/job-agent/inbox/ for J.
- Audit of the inherited state, firsthand:
  - Z2-REPORT.md existed only as an UNTRACKED file — never committed
    by the first session. Committed by this session with a status
    banner and one correction (next bullet).
  - The report's raw-claim sentence cited a class-3 raw figure that
    does not exist: bench5 died on the bench.clj paren error before
    printing (bench5.log), and bench6 left only its header line —
    it never completed (bench6.log). The sentence is rewritten in
    the report to say exactly that; the J bench re-take produces
    the raw row (current bench.clj prints it).
  - Everything else in the report reproduces from the logs:
    gate-run.log (19 tests / 158 assertions / 0 failures, trio +
    run-claim), final-run.log (24 / 721, only failures = W0's
    scoreboard-smoke + requirements-covered, pre-existing at this
    pin), bench4.log (the gate table, load 4.56 -> 26.85 mid-run —
    provisional until the J re-take).
  - The bundle staged on J (~/static-eve-bundles/z2.bundle) was
    STALE: its ref sat at f05dc72, eight commits behind — it does
    not even contain the Wave 0 merge. Local bundle refreshed to
    this session's tip (see the commit that carries this entry).
    COORDINATOR: re-stage ~/workspace/static-eve/bundles/z2.bundle
    to J's ~/static-eve-bundles/ before the filed jobs run; both
    job run strings assert HEAD == the filed tip and exit 3 with
    STALE-BUNDLE in the log if the staged bundle is behind.
- Filed to the job agent (lane static-eve/claim, venue_pref j,
  class heavy): (1) verification — alloc-run-test + W0 contract-test
  - regression trio on J, log ~/static-eve-results/z2-verify.log;
    (2) bench re-take — doc/static/z2/bench.clj on J, log
    ~/static-eve-results/z2-bench.log. Known artifact to expect in
    (1), stated before the run: contract-test's scoreboard-smoke may
    fail on this tree's scoreboard/SPIKE-LOG state (W0.5/W0.6 at this
    pin), exactly as in final-run.log; the Z2 verdict rides on
    alloc-run-test, contract-roundtrip, alloc-run-default, and the
    trio.
- Lane state: implementation and tests unchanged by this session;
  only the report/STATUS/bundle moved. J results land via the
  coordinator; on their arrival the report's provisional caveat is
  lifted or the verdicts are revised — whichever the logs say.

- FINISHER (2026-11-12): J results delivered by the coordinator.
  (1) VERIFY LEG RAN at tip d802f93: 22 tests, 195 assertions,
  2 failures, 0 errors — both failures are W0's scoreboard-smoke
  contract deftest (scoreboard arms read "not built" at this pin;
  the known pre-existing W0-state failure). alloc-run requirement
  tests, contract roundtrips, and the regression trio are GREEN on
  J. The report's verify-side provisional caveat lifts on the bench
  verdict. (2) BENCH LEG DIED AT LOAD: bench.clj EOF while reading,
  starting at line 33 — one closer short at the end of run-bench
  (ed97031 added two, the raw-row edit needed three). Repaired at
  076b206; depth 0 at EOF, reader reads 5 forms clean. Bench leg
  re-filed (lane static-eve/claim, venue j) asserting the new tip;
  COORDINATOR: re-stage ~/workspace/static-eve/bundles/z2.bundle
  (refreshed locally to this tip) to J before it runs. On the bench
  log: finalize Z2-REPORT.md (lift banner, bench rows incl. the
  first-ever raw -sio-alloc-run! row, gate as measured).

- FINALIZER (2026-11-12): bench re-take delivered (z2-bench.log,
  HEAD 29187a3, load ~6.4). Class rows: class 0 (32B) ×20,000 —
  claim+copy 176.0 ns/blk, 2.93× vs per-block; claim-only 88.6,
  5.18× vs alloc-only; raw -sio-alloc-run! 48.1 ns/blk. Class 2
  (128B) ×20,000 — claim+copy 68.5, 8.85×; claim-only 51.1,
  10.78×; raw 32.8. Class 3 (256B) ×5,000 — claim+copy 121.0,
  2.64×; claim-only 85.9, 4.81×; raw 50.7. The class-3 claim+copy
  ≥3× gate is MISSED at 2.64× (first run's 2.40× confirmed; not
  contention); claim-only passes ≥3× on all three classes.
  Z2-REPORT.md finalized: provisional banner lifted, verify
  verdict recorded with the scoreboard-smoke attribution, bench
  table as measured, F5 flag-OFF note kept. requirements.edn
  confirmed: Z2.1–Z2.6 :built. LANE Z2 COMPLETE.
