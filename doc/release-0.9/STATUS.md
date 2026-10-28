# eve 0.9 release prep — status

Lane: EVE 0.9 RELEASE PREP. Branch `claude/eve-0.9-release`, base `origin/main` at
`112f6ced2b` (verified: `git rev-parse HEAD` = `112f6ced2b9b7c550576db9d8c4b2d154f36b88a`).
Work in `eve/` of atom.broker, plus the declaration report (`docs/port-gap-closure/coordination/reports/`) and two
notes in `vs/docs/`; SeniorCareMarket/eve got branch `claude/eve-0.9-release` (`bb1655e`, 2026-10-21 6:34 PM ET).
Times are US Eastern.

## Log (newest last)

- 2026-10-20 1:44 PM ET — base verified, branch created. Clone was shallow; deepened to
  2026-02-15 (`git fetch --shallow-since=2026-02-15 origin main`, 46 s): eve entered this repo at
  `b24c8c034` (2026-10-15, "import EVE at c7dc390"); its history before that is the standalone
  repo's, which this lane does not read.
- 1:49 PM ET — Maven Central answered 429 (rate limit) to the Clojure CLI; `~/.m2/settings.xml`
  given a Central mirror (Google's), the JVM classpath resolves. Public-var census of `src/` and
  `adapters/` taken mechanically (162 files, 0 reader errors). Phase 1 research running.

- 2:08 PM ET — Phase 1 committed. Measured on this box at base: `bb test:bb` 231 tests / 3135 pass / 0 fail / 0
  error (7 m 00 s); `bb test:glj` red at load (`failed to load /eve/slab_io`, 1.4 s; glj-eve built here, 40 s); the
  minimal + inbuilt composition on bb 226 / 3010 / 0 / 0 (6 m 21 s); `eve.thread-net-test` alone on bb 5 / 32 / 1
  error (`*memo*` not dynamic — order-dependent, green in the full suite). Seam timings (bb, portable vs native):
  SHA-256 16 KiB 147 vs 1.0 ms, AES-CTR 209 vs 2.3, Ed25519 sign 977 vs 0.84, verify 894 vs 0.92, Murmur3 10k keys
  133 vs 70. Not runnable here: nbb, cljr, basilisp, jolt, clojerl (runtimes absent).

- ~2:35 PM ET — Phase 2 part 1 committed (`007d4edc8`): three defects fixed and re-measured (glj red→149/2403;
  bb thread-net alone 1 error→0; bb atom-swap red→green), docs repaired, examples, CHANGELOG 0.9.
- ~2:40 PM ET — **operator pivot**: the eve-media capstone is part of 0.9 and is built inside `eve/capstone/eve-media/`
  (spec: plan §8 / lanes ET-9 on `claude/trusting-clarke-p4z2jt` @ `1afd09dd75`; overrides: in eve/, no VS, build the
  missing eve.thread units). Extended-set runtimes installed: Erlang/OTP + clojerl `4ad14e5` (61 s), threaded Chez
  10.4.1 (320 s) + jolt `f79e64d8`; libav dev headers.
- ~3:00 PM ET — Phase 2 part 2 committed (`44f3052bb`): both adopter guides, the seam bench; CLJS `net` suite after the
  codec fix 60 tests / 1422 / 0 failures.
- 3:08 PM ET — capstone unit 1 committed (`9e09b5f5a`): shared core, coordinator by election, JVM + bb members,
  launcher; trial (bb + JVM): every JVM job done; npm and Go jobs wait for their members.
- 3:20 PM ET — unit 2 (`4bcd756cc`): the Node member (sharp, music-metadata), eve's browser http adapter
  (`adapters/http/browser`, hosts.edn `:browser`), the Go media package and `glj/build.sh`.
- 3:38 PM ET — unit 3 (`8d16e8c77`): the Go member (media server, watcher, publish), the gateway, the page.
  **`bb eve-media demo --set core`: every check true in 60 s** — bb, JVM, Node, Go; the page's library and a
  re-queue from the page (f01's thumbnail, completed on `node#main`); screenshot in the run directory.
- 3:50 PM ET — extended set standing up: clojerl loads the shared code (after `Math/cos` and `double` were taken out
  of the capstone's portable pHash); jolt's native libav transcoder works through jolt's FFI alone — PCM byte-identical
  to the CLI's, MP4s H.264/AAC 1280×720, byte-deterministic across reruns. The first extended run showed two
  coordination gaps, fixed: the coordinator now plans once every launched member has joined (bounded), and a
  non-preferred leader hands the lease to the BEAM once it is live. `bb eve-media verify` added (the six runs).
- 3:53 PM ET — unit 4 (`febb1646b`): the jolt member (FFmpeg's libraries through jolt's FFI), the BEAM handoff,
  `verify`. 3:56 PM (`b20fe09a9`): the JDK http-server's `:stop` (its pool left running), checklist rows 9.10–9.13,
  VS outside 0.9 (operator).
- 4:43 PM ET (`ba99b61cc`): the core-kill run deadlocked the domain — babashka's exclusive hold waiting on a killed
  lock-free writer's `ROOT_INFLIGHT` (checklist 9.14, an operator decision: not fixed in eve, per the memory clause);
  babashka now joins over the network rung. Two eve.net defects found on that route and fixed with tests: a long past
  int32 through a network atom (9.15) and a starved network writer that never took its turn (9.16). The http-server
  feature on Node.
- 5:13 PM ET (`cf2987046`, operator ask): the browser is a runtime of the data plane — it joins the registry over the
  network rung and serves the `:browser` pool (the waveform job) on two Web Workers; the page is reagami; the gateway
  is a JVM process of its own. Core set with the browser: every check true (166 s, 234 s), but the browser's first
  incarnation lost its lease while joining (restarted once).
- 5:25 PM ET — measuring the network rung from a browser worker (the page batches a worker's requests).
- 5:41 PM ET (`b721eb5a9`): a browser worker's network batches are fetched by the page (eve's browser http and
  thread adapters), and eve-media's page reads through a view worker so its main thread never blocks on the network:
  a cold read of the job metadata from a browser worker 15.4 s → 5.7 s (one probe each). Core set with the browser:
  every check true in 130 s, the browser joined in 40 s and kept its lease. `bb eve-media verify` (the six runs) started.
- 5:45 PM ET — verify's core run: every check true in 136 s (eleven files with the drop-in, the browser's library and
  re-queue). Its screenshot was not written: a DevTools reply that large arrives in parts — fixed (`0cdd0987b`),
  re-tested on a 1.2 MB PNG. Core-kill: the JVM killed mid-transcode, babashka took the lease, no deadlock.
  Checklist 3.3, 3.6, 9.11 closed (`52982fc60`).
- 5:53 PM ET — core-kill did not finish: the killed JVM was the coordinator that had submitted every job, and
  eve.thread forgets a departed runtime's settled descriptors past its horizon (10 × its 4 s lease) — and cancels a
  descriptor whose input was forgotten — before babashka, the new leader over the network rung, had mirrored them
  (f02's and f08's loudness `:cancelled`, f10's thumb and phash gone). eve.thread behaves as designed; the capstone's
  coordinator did not cope. Fixed in the coordinator: a job whose descriptor is gone is read from the result it
  recorded in the library (`:done`) or is lost, and failed, cancelled and lost jobs are submitted again in the DAG's
  order; the index's jobs likewise from what they recorded. Verify stopped and re-run whole.
- 5:58 PM ET — verify re-run, core: every check true in 160 s (eleven files with the drop-in, the browser's library
  and re-queue, the screenshot: 11 of 11 rows). The browser's first incarnation lost its 30 s lease while joining
  (launched while every member was starting) and was restarted; the second joined in 36 s.
- 6:00 PM ET — core-kill: every check true in 117 s — the JVM (the leader) killed mid-transcode of f02, babashka
  took the lease 31 s later over the network rung, the JVM restarted, f02's transcode done at `:attempts 2`.
- 6:02 PM ET — core+beam: every check true in 108 s, the BEAM (clojerl) the leader.
- 6:05 PM ET — extended (all seven runtimes): every check true in 168 s — the BEAM the leader and the browser's
  waveforms planned by it; the browser joined in 14 s, kept its lease; screenshot 10 of 10 rows.
- 6:09 PM ET — **`bb eve-media verify` PASSED**: extended-kill (jolt killed mid native transcode, `:attempts 2`, the
  same SHA-256) and extended-no-beam (the JVM leads, the same outputs) green; every comparison row true. Evidence in
  `doc/release-0.9/eve-media-verify/`.
- 6:18 PM ET — the browser's lease 30 s → 60 s (and a view-only page's header): `verify --only core,extended` PASSED,
  no restart in either run.
- 6:19–7:27 PM ET — the batteries this lane touched, on its final tree, every one green: bb 231 / 3,135; bb-minimal
  226 / 3,010; glj 149 / 2,403 (red at base); clje 182 / 2,787 (**red at base too**: `eve.net.merkle`'s delays, which
  clojerl compiles as literals — confirmed on a scratch copy of the base tree; fixed, checklist 9.18); jolt 178 /
  2,087; browser 48 / 633; Node net 62 tests / 1,429 assertions, all 462 / 33,353; JVM 520 tests, 0 failures. glj-eve
  and jolt's Chez side by side were OOM-killed (7.1 + 5.8 GB): run one at a time.
- 7:28 PM ET — the final declaration committed: `docs/port-gap-closure/coordination/reports/eve-0.9-release-2026-10-20.md`
  (atom.broker's root). The lane is done; what remains is the release manager's and the operator's (its §3).
- 7:40 PM ET — **operator, verbatim**: "nbb, ClojureCLR and basilisp: not installed on this box. — Since when is that
  an excuse?" · "glojure's and jolt's batteries together ran out of memory on this 15 GB box … That's a bug for you to
  fix because we don't want to release like that." · "we want perf tests done across all the host types. I also want a
  doc made for each host lang we target … We want that doc for every lang we target."
- ~7:50 PM ET — installed: nbb 1.6.214 (npm), basilisp 0.5.1 (venv `/opt/pyhosts`), .NET SDK 8 + ClojureCLR 1.12.6.
  ClojureCLR's battery green: 181 / 2,107 (199 s).
- 8:18 PM ET — glojure's memory: glojure sets GOGC 400 → 7.1 GB with ~1.4 GB live; glj-eve sets Go's default 100
  (1.0 GB, +2% time). jolt's: its regions were foreign memory never released; a guardian (free once unreachable) got it
  to 4.7 GB, then heap regions as immobile Chez bytevectors (collector-paced, jolt's heap ceiling) to 1.9 GB.
- 8:49 PM ET — **glj and jolt side by side, green**: 149 / 2,403 and 178 / 2,087, the pair 245 s, peaks 1.2 + 1.9 GB
  (`14c8068d8`, `d545247f2`).
- 8:52 PM ET — basilisp's battery: 149 tests, 2 load errors — basilisp's numeric arrays are nil-filled (eve.net's
  BN254 added nil), and it has no `sorted-map` (eve.net.atom): both completed. nbb's battery killed at 12.2 GB: a
  Node worker that opened a disk domain never ended (its heartbeat `setInterval` kept the loop alive) — unref'd, as
  every other host's heartbeat is a daemon; nbb 226 / 3,004 green, peak 4.4 GB (`12abf6f17`); Node `thread-domain`
  peak 1.55 GB (its note said ~3.8 GB).
- 9:05 PM ET — coverage: namespaces runners skipped with no reason, run per host. jolt and ClojureCLR: vec-retire and
  domain-open green (5 / 779; ClojureCLR's needed a `:cljr` temp-dir arm). glojure: collection-hash and thread-net
  green; fold's suites had 47 errors — glojure's `sequence`, `dedupe`, `partition-all` reach JVM classes it lacks —
  completed (`817af17fd`), 0 errors; the fold planner's "reduced here" row failed under load-average 9 and passed alone.
- 9:08 PM ET — the browser conformance timed out twice ("Array buffer allocation failed"): the perf suite linked into
  the conformance page loaded `eve.alpha` into every worker (a module slab each). The perf suite has a page of its own;
  the conformance is green, 48 / 633, 48 s. Per-host docs (`doc/hosts/`) drafted from the tree, four agents' cited
  extracts and this session's measurements.
- 9:30 PM ET — basilisp's full battery stopped at its background bound (30 min; the network-rung tests run ~16 min
  on basilisp); 145 tests ran, one error: the ticker's first beat over a network atom past attach!'s 120 s under a load
  average of 9 (alone: green). ValueScript: `bb test:vs` 260 passed, 0 failed (its slab-io test passes now).
- 9:45 PM ET — the perf suite's first run on babashka-minimal: building a 200-key int-map failed on the 30th rebuild
  ("integer overflow"), on full babashka too. Two defects under it, both fixed and pinned (`7faf6597f`): an int32 field
  could not hold an offset past 2^31 (checked cast); a fresh map or set written into an atom kept 54 of the 391 / 443
  blocks it allocated (the writers' own path copies, never freed) — now exactly its tree. Not fixed, reported
  (checklist 9.25): intermediates a swap's own function builds are not reclaimed (the model's scope-local temporaries).
- 9:51 PM ET — the perf suite on every host, one at a time, the box otherwise idle.
- 10:29 PM ET — the perf run's two failures fixed (`80fa591f0`). jolt's disk workload, "open …: errno 2": jolt's
  launcher runs from jolt's own root, so open(2) resolved a relative path there; the region opens the absolute path.
  The browser's thread workload, "the thread running 1/2 died" / "Array buffer allocation failed": Chromium caps a
  renderer's SharedArrayBuffers at ~16 GB (measured: 15 × 1 GB in a page, then 3 × 256 MB in a worker) and each
  kernel loading `eve.alpha` reserved ~5 GB of module slab. Slabs in growable SharedArrayBuffers (which count by
  length: 400 of 1 GB maximum fit beside the 15 GB) start at their initial capacity and double; the overflow region
  likewise, its grown tail merged into the free block before it; `JsSabRegion`'s i64 store fixed on the way.
- 10:37 PM ET — an A/B on Node with one bundle: growable buffers cost in-memory work 1.05–1.32×, so slabs grow by
  default only in a browser (`04d3b3d21`); Node keeps fixed buffers; `slab-grow` (91 tests, 27,310 assertions) runs
  the slab suites over growable ones. The browser runs its thread workload (64 tasks, 8.8 ms); its conformance is
  green (48 / 633).
- 10:52 PM ET — the JVM's swap, 7× ClojureScript's, profiled (JFR): reflection was most of it —
  `invokeWithArguments` called with a vector, resolved by reflection at every slab word, and the unaligned reads'
  `ByteBuffer.get`. The region now spreads each handle once and calls `.invoke` on a typed array; the shared code's
  reflective sites are hinted (`92e730ec2`). A counter swap 169 µs → 14.7 µs; reflection loading every `src/`
  namespace: 0 sites but core.rrb-vector's 146 node-field reads. `clojure -M:jvm-test` 522 tests, 8,085 assertions,
  green in 162 s (364 s before). The JVM's perf: a counter swap 268.8 → 15.7 µs, a disk-domain swap 748.5 → 21.8 µs.
- 10:55 PM ET — the final sweep: every host's battery on this tree, memory-sampled (basilisp beside the rest).
- 11:46 PM ET — the sweep done: every battery green but clojerl's (stopped at load: `eve.keyring`'s new ^bytes hint
  without eve.host's `bytes` referred — clojerl resolves a hint's symbol) and basilisp's (2 failures, 5 errors). Fixed
  (`84d28650f`): the keyring's refer; basilisp's `true?` / `false?` / `seqable?` / `supers` / `peek` / `pop` evaluated
  their argument twice (inline expansions — `(is (true? (create-exclusive! f)))` created the file, then found it); a
  cancelled periodic's queued run ran after the cancel (eve.thread.pool). basilisp's five errors: bring-up past bounds
  under load (each green alone).
- 12:14 AM ET (10-21) — clojerl re-run 184 / 2,808 green; `eve.thread-test` on every host over the periodic fix, green
  (00:09–00:14); basilisp's full battery alone: 166 / 2,825, 1 error — the network rung's domain pool, on a quiet box.
- 1:19 AM ET — py-spy on that test: `eve.net.bytes/hex` a quarter of a network swap, basilisp's `str` making a closure
  per call under it (77 µs for two strings). The completion's fixed-arity `str` (5.0 µs) and a table `hex`
  (`2f663b5f0`): a 32-byte hex 4.1 → 1.1 ms on basilisp, the gateway test 1,234 → 229 s. Over them: the JVM battery,
  Node net / all, bb, the network rung on nbb, ClojureCLR, glojure, clojerl — green; basilisp's network rung alone
  green (5 / 47), its full battery still 1 error (the same test, late in the run; after the domain cell's suite it is
  green: 12 / 145) — checklist 9.26 rewritten with what remains.
- 3:02 AM ET — the final perf run, eleven hosts in one window; the host pages' tables and readings from it. The box
  varies: against the run before, every host was slower by a median 1.09–1.43× on unchanged code; the docs say so.
- 9:17 AM ET — the operator: 9.25 should be a regression ("EVE has been correct the whole time"); bisect it. A probe
  (blocks in use from a disk domain's slab headers after 20/40/80/160 swaps, one source for every tree, JVM and
  ClojureScript) on the import (`b24c8c034`) and HEAD: a swap making 49 intermediate versions of the stored map leaks at
  the import too (JVM ~50 blocks a swap, ClojureScript ~115) and at HEAD (~125 on both); host data rebuilt each swap
  leaked at the import (JVM 54,367, ClojureScript 87,106 blocks at 160 swaps) and is flat at HEAD; one assoc a swap is
  flat everywhere. No good commit in this repository to bisect from; `b5c2ffc59` removed the JVM's replaced-node log,
  which had caught part of it. Reported to the operator with the options; checklist 9.25 rewritten.
- 9:28–10:45 AM ET — the operator's choice, "transients reclaim", built (`acf4c26fc`): a scope log chained under the
  swap attempt's, each transient write freeing what it superseded among its own blocks; exact walks for int-map, the
  sorted treap and the red-black set, used by the atom's retire; the producers' throwaway nodes gone (int-map's leaf
  split and branch-above, the red-black balance and delete, a vector pop across a trie level). Found on the way and
  fixed: an eve list at an atom root broke after `conj`s (its walk retired the cells a conj keeps — 9.29); a user's
  eve-deftype at a root lost the value it replaced (9.30 — the operator chose the §10.2 reach for it); babashka had no
  transient for an eve hash map (9.32); eve.slab-io's conformance test red on ValueScript since `7faf6597f` (9.33).
- 10:50 AM ET — the operator: a user's deftype should work like a native structure, registered as defined and walked
  at the native walks' speed. `0eddc94d0`: the positional walk over each type's registered layout (a moved block held
  with all it holds); a 5,000-node user tree, one insert, 0.66 ms against 36.9 ms for the reach. Measured on the way: a
  nested map held at two keys of one value is freed when one key is replaced (the walk is single-pair, as the model's
  is) — 9.31, an operator decision. The operator also asked that the work reach VS: eve's ValueScript row runs the
  shared tests that compile there (hamt-util, the byte fixtures, slab-io) — the changed collections, atom and
  deftypes do not load on VS yet (docs/eve-on-vs/PLAN.md §3 E) and reach it when they do; VSv2's own runtime is its
  own kernel (`vs/src/vs/kernel/wasm/retire.vs`, a scan and a ledger) and takes none of this automatically.
  Correction: `0eddc94d0`'s message counts `eve.reclaim-test` as 9 tests; it is 8.
- 11:03 AM ET — the batteries on `0eddc94d0`, every host, three lanes. (The first start, on `acf4c26fc`, was stopped
  when the deftype walk changed the tree under it; what had finished there: node all 476 / 34,963, slab-grow 99 /
  28,885, deftype 63 / 710, net 62 / 1,429 — 0 failures.)
- 11:03 AM – 1:11 PM ET — what a reclaiming transient costs. Builds only, this tree against the tree before 9.25
  (`cb82277a6`), both on the box at once: the first cut (`0eddc94d0`) cost 1.8–5.1× over the leaking transients — each
  write walked the whole pair. `08b94cbf4`: a map or set write walks the written key's path, a vector `conj!` writes
  into a tail the transient made, in place, and a transient begun from an empty collection the code made keeps that
  header; ≤1.71× now, and a vector `into` and int-map's persistent `assoc` run faster than before. The 11:03 batteries
  (on `0eddc94d0`): green but basilisp's 9.26 error and two timing bars under the lanes (node's routed futures 105 ms
  against 100, the browser's 147 against 140), both green on a quiet box at 12:42–12:43.
- 1:12–2:47 PM ET — the batteries on `08b94cbf4`, three lanes: every host green, the counts unchanged but the JVM's
  assertions (9,584; jvm-fuzz-test asserts per generated case), except basilisp — 9.26's error and one fail,
  eve.thread-fold-test's chunk-size timing bar ("672 < 672") under the load. Then `bb scripts/perf-all.clj` (2:37–2:47)
  and the suite's workloads in `.vs` on VS's own atoms (2:47).
- 2:48–2:55 PM ET — basilisp's fold namespace alone: 7 / 332, 0 failures. Node's routed futures, this tree against
  `cb82277a6` alternated three times: 62 / 52 / 65 ms against 45 / 61 / 80 — the box, not 9.25.
- 2:55 PM ET — the host pages' tables regenerated from the 2:37–2:47 run and their prose rewritten to it; the README's
  run window and variance note (the unchanged workloads ran 1.24–1.64× faster than at 3 AM: the box); the build rows
  compare with no earlier run (a different workload); VS's own-atom numbers on `doc/hosts/valuescript.md`; the
  declaration's two pending items filled; CLAUDE.md's baselines.
- 4:20–4:56 PM ET — 9.31, the operator's steer (verbatim): "Use the tally rule: a count on the shared piece itself,
  raised when a write stores an existing piece anywhere — another atom, or a second place in the same value — lowered
  when a write drops a pointer to it, freed only at zero through the deferred release. One rule for both cases, O(1)
  per store." · "See how tally is used elsewhere. We don't inc every block in a branch (if that's what you're talking
  about)." · "to get to a branch that has a parent node that is incremented, you had to walk the branch to get there,
  so you should be able to remember that it's not at 0 members". This overrides the lane's MEMORY clause ("no
  counters") and contention-domains.md's "no counters on headers" for eve. Measured first: 9.31 also frees what a
  value **derived** from `:a` and stored at `:b` keeps (two forms, each in its own JVM). **The change list (one build,
  rule 22):**
  1. `eve.tally`: a piece's count (holders beyond the first, u16, saturating) in count pages — ordinary slab blocks,
     through the slab interface every host implements — found from the root header's reserved word 32; a page is
     installed by CAS and only when a count first leaves 0; a free resets the slot.
  2. `eve.retire`: what a commit does to counts, from the old and new values — every pointer a block the write made
     holds to an existing piece raises it, every pointer a dropped block held lowers it, a dropped block at zero is
     released and its own pointers dropped, above zero the walk stops there; a block and its copy that hold the same
     child at the same place cancel (an ordinary swap touches no count); blocks the write made and stored twice are
     counted before it publishes. Replaces `retire-set` / `nested-retire` / `value-reach` / `deftype-diff`, and with
     them the §10.2 reachability set (D2).
  3. `eve.atom`: the plan runs before the CAS; a write whose plan raises a count commits under the domain's exclusive
     hold (a lock-free host takes it by CAS, compatible with the lock-only protocol); every other write stays
     lock-free and only lowers. `seal` reads the new reach.
  4. eve-deftypes register their field layout always (the trees too).
  5. Transients: a transient that stores an eve value by pointer stops freeing within itself (the commit counts it).
  6. Tests: 9.31's three forms, a value at two keys and in two atoms, a fresh value stored twice, a transient storing
     its own value twice, counts back to zero, threads racing on a shared piece; the old retire tests retargeted.
  7. Docs (memory model, checklist 9.31 / 10.2 / D2 / D4, CHANGELOG), then every battery and the perf suite.
- 4:56–5:17 PM ET — built (`a3693674c`): `eve.tally`, `eve.retire`, the plan before the CAS and the held write in
  `eve.atom`, field layouts for every eve-deftype (int-map's int32 offset fields marked `^:eve/pointer`), the
  transient's stop on a stored piece. The three 9.31 forms and the cross-atom case read back whole on a disk domain
  after reuse, and fail on the tree before it ("Index … out of bounds") [ran]; every single-holder change in
  `eve.jvm-atom-retire-test` moves no count [ran]. Written with the editor and python replacements (new files and
  whole sections; `vs src!` not used — declared).
- 5:15–5:31 PM ET — the first JVM battery and `node all` found: ClojureScript's module slab registered its own
  default slab I/O over alloc's `current-sio` (a swap's nested host collections were allocated past the attempt's
  log — a lost CAS leaked them before this lane too — and the walk raised them as existing pieces); a HAMT child
  slot holding NIL under its bit (eve.thread's domain tests); the CAS test's stubs. And the plan's cost: reset-vec-1k
  5.66 ms against 1.54 ms (JVM) → merge pairing, no pairing of value blocks, a straight release path, a one-word
  pointer test, and no walk of a new value written from plain data with no eve value stored by pointer → 1.67 ms;
  every other JVM row within ~10% of 2:47 PM (`2397b2d6d`).
- 5:32–5:36 PM ET — the batteries started, then stopped within a minute (rule 22): ClojureScript's codec cleared its
  stored-piece report when an inner encode returned, so an eve value stored after a nested host map went unreported
  and the walk of the new value would have been skipped. Fixed, with a value just built from host data written
  through `encode-fresh-pointer` (no report) (`f6bb7f1a3`); the test made portable (one-pair `assoc`, a nested map
  where babashka reads a vector back as its own).

- 5:56 PM ET — the 9.31 battery (on `38b96e21b`) stopped after jolt, to move the count onto the block's descriptor
  (the operator's ask): green through jolt — compile; node all 482 / 34,980; browser 48 / 633; slab-grow 105 / 28,902;
  deftype 63 / 710; net 62 / 1,429; JVM 546 / 9,724; glojure 182 / 4,458; bb 247 / 4,748; jolt 199 / 4,479. Not run on
  that tree: bb-minimal, cljr, nbb, vs, lpy, clje, perf. The descriptor move was not started (nothing edited).
- 6:25 PM ET — **STEER — PIVOT (John's ruling; supersedes the brief's scope): EVE 0.9 is 100% compatible with VSv2.**
  VSv2 is the reference implementation (VS releases a few weeks after 0.9; the ordering does not lower the bar). The
  release-readiness work stands as the base. The job, in order: (1) a compatibility audit of every surface where eve
  meets VSv2 — atom domains and tiers; the tally (a count on the shared value, up on store, down on drop, free only at
  zero; today's 9.31 ruling: same-domain references tallied, cross-domain plain references an error, `$` materializes
  a crossing); swap! and foreign domains; network atoms (broker signaling, tiers, who owns the root pointer); ExtCID
  addressing (256 bytes; the 136-byte codec is stale); slab encryption defaults (on for network atoms, off for local);
  value encoding and descriptors; snapshots (per-atom, never a global instant); (2) a divergence list, committed and
  kept current — a genuine design fork stops for John's ruling; (3) converge in `eve/` with two-sided cross-checks;
  (4) report against the audit. **The reference is the live kernel on `origin/main` at `f65b23153`** — 176 commits past
  this lane's base: the kernel's own tally (`tally.vs`, new), the atom-domain implementation, `retire.vs` 4,084 →
  1,011 lines, `memory-model.md` and `contention-domains.md` rewritten, `vs/docs/reclaim/atom-domains.md`. Read there
  (`git show origin/main:…`), not merged into this lane (the brief's base rule). The count's move to the descriptor
  waits on the audit: the kernel's tally is what it must match.
- 6:30–6:50 PM ET — **the steers that followed (John's rulings, in order), and what was measured.** (a) The compatibility
  audit was withdrawn: "the 0.9 job is a copy" — copy this branch's `eve/` into SeniorCareMarket/eve, release hygiene,
  verify from a clean checkout there, tag 0.9.0. (b) Then: **eve is defined by the shared parity corpus** — `eve/test`
  per host (the bb tasks, the JVM and CLJS batteries, the VS battery) and, on the VS side, `vs/test/eval-parity`,
  `vs/test/eve-kernel` and the conformance leg; run it against VSv2 and against this branch's `eve/`; the reds alone are
  the divergence list; close them (eve behind → fix eve; a test encoding superseded behaviour → John's ruling, never an
  edit to green); then copy, verify, tag. Measured (VSv2 = `origin/main` at `f65b23153`, read in worktrees, never merged):
  * `eve/test` on the VS host, compiled by main's builder, over this branch's `eve/`: **260 passed, 0 failed** (142 s).
    The same `eve/` source (unchanged since `38b96e21b`) on the other hosts, 0 failures: node all 482 / 34,980, browser
    48 / 633, slab-grow 105 / 28,902, deftype 63 / 710, net 62 / 1,429, JVM 546 / 9,724, glojure 182 / 4,458, bb 247 /
    4,748, jolt 199 / 4,479. Not yet on this tree: bb-minimal, cljr, nbb, lpy, clje, coop-atom (main's harness with
    `EVE_DIR` = this `eve/`), perf — started 6:42 PM ET, not banked when this entry was written.
  * `vs/test/conformance` does not exist: an empty jank submodule removed 10-11 (`9457e09f2`), superseded by the UP1
    harness `test/up1-conformance/run` (`vs/docs/testing.md`'s conformance leg) — not yet run.
  * `vs/test/eve-kernel/run` on main: hamt-util 39 / 39 as the JVM. `vs/toolchain/eve-kernel --check` on **main itself**
    exits 1 (`FATAL shipped: 8 refusals`: `eve.hamt-util/*` reads `eve.int32/TWO32`, `pow2`, "no value def of the run")
    — a red of the reference tree, before this lane's `eve/` is involved. `run-codec` / `-colls` / `-values` need their
    test kernel built first (`--build`, a kernel rebuild).
  * This branch's `eve/` differs from main's in 184 files (+15,581 / −3,243), 85 of them new: the 0.9 release prep, the
    capstone, the 9.25 and 9.31 fixes.
  * SeniorCareMarket/eve: branch `claude/eve-0.9-release` at `bb1655e` — the tree replacement, byte-identical to this
    `eve/` at `fcb91485e` (tree `8f92b9c7c`), pushed 6:34 PM ET (the tool reported that call rejected; it had run).
    `main` (`10760f1`, 2026-03-25) untouched, no tag. `c7dc390` (the import base) was a local-only commit, never in the
    standalone repo; its branches after `main`: EM2's three fixes (already in `eve/`), EM5 (its work in atom.broker), and
    `claude/eve-float-support-4gkx2n`'s one line (`.gitignore`: `clojure-tools-*.tar.gz`, not in `eve/`).
  * Release hygiene (read-only inventory): no credentials; 35 tracked files name atom.broker. For John: the internal
    docs `doc/release-0.9/`, `doc/unification/`, `doc/network-atoms/` (plans, reports, probes describing the broker's
    internals), `doc/hosts/valuescript.md` (VS ships after 0.9); `eve.net.auth` accepts only `auth.atom.broker` issuers;
    `NOTICE` credits atom.broker's xxhash.vs and vendored poseidon2.
- 6:55 PM ET — **lane retired** (operator: "Retire this lane"). The runs were stopped; what finished, against VSv2 at
  `origin/main` `f65b23153`: `eve/test` on the VS host 260 / 0, bb-minimal 242 / 4,623 / 0, ClojureCLR 202 / 4,499 / 0;
  the eve-kernel checks give main's own results line for line on this lane's `eve/` (`--check` 8 refusals on both;
  hamt-util 39 / 39; colls and values identical). No red of this lane's `eve/` was found in what ran. Not run: nbb,
  basilisp, clojerl, coop-atom, perf, `run-codec` / `run-xxh3` (need their test kernels built), eval-parity, UP1.
  SeniorCareMarket/eve keeps branch `claude/eve-0.9-release` at `bb1655e` (not merged, not tagged). The declaration's
  "Retired" section (`docs/port-gap-closure/coordination/reports/eve-0.9-release-2026-10-20.md`) has the table and the
  open items.
## On main (2026-10-21, session_01VA9egFFp7vNEsCkSj2zFKA)

- 6:52–7:01 PM ET — a corpus run (every `eve/test` leg and the VS-side checks, main against main + this `eve/`) was set
  up and stopped before any leg finished. Kept from it: push access to SeniorCareMarket/eve is proven (a scratch branch
  `claude/push-probe-60t1jk`, `7a9a5cf`, pushed 6:53 PM ET; this session's git proxy refused its deletion, HTTP 403, so
  it is still there); every host runtime installed on the box in 6 min (Erlang/OTP + rebar3 + .NET 8 by apt, nbb 1.6.214,
  basilisp 0.5.1, ClojureCLR 1.12.6, clojerl `4ad14e5`, jolt `f79e64d8`, threaded Chez 10.4.1 built in 237 s).
- about 7:02 PM ET — operator, verbatim: "I'm not interested in a massive porting process. Mostly just copy the new code over.
  And import the optimizations and fixes found by the old 0.9 lane." · "We just want that work on the right codebase
  from the beginning, which is the new stuff."
- 7:04 PM ET — done on `claude/keen-feynman-60t1jk`, cut from `origin/main` `f65b23153` (176 commits past this lane's
  base, none of them in `eve/`): `6f1d2f5d` carries this `eve/` whole, byte-identical to the lane's tip (eve tree
  `5a846123741c`), with the declaration report; `a56a00a7` is the lane's change list
  (`docs/port-gap-closure/coordination/reports/eve-0.9-lane-changes.md`, cherry-picked from `748470496`). Not carried:
  the lane's notes in `vs/docs/memory-model.md` and `vs/docs/contention-domains.md` — main rewrote both and has its own
  THE TALLY sections; the notes describe VSv2's count as the hold ledger main has since replaced with `tally.vs`.
- Of VS's eve-kernel sets, only the codec TEST set reads namespaces this lane changed (`eve.net.codec`, `eve.net.bytes`);
  the shipped set (`eve.hamt-util`) and the xxh3 set read none.
- 7:07–7:13 PM ET — `vs/toolchain/eve-kernel --check --set codec`, the one regeneration the change list names, on this
  branch and on `origin/main`'s own `eve/` (worktree at `f65b23153`), one at a time: **red on both, red on main first.**
  main's `eve/`: 7 refusals — `eve.net.bytes/HEX-VAL`, `hex-digit`, `unhex` call `vs.core/rt-kvmap-own/1`;
  `eve.net.u64/mul`, `mul128`, `eve.net.xxh3/len17to128`, `len129to240` call `vs.core/rt-drop-own/1`: "no kernel function
  (eve-core's callee-table)" — then `RUN-TRIP form 293 … wat->vs: a statement between an instruction's operands and the
  instruction` (94 s). This branch: the same refusals as far as it got (the three `eve.net.bytes`, the two `u64`), then
  a wasm `unreachable` trap in a `forms` batch (144 s). main's compiler emits ops eve-core's callee table lacks, so the
  committed codec test set (`vs/test-kernels/codec/…/eve_codec.vs`) cannot be regenerated on main with or without this
  `eve/`; `run-codec --build` was therefore not run. Whether the lane's `eve.net.codec` adds a failure of its own is
  hidden behind main's until main's callee table carries those ops.

- **7:15 PM ET, 2026-10-21 — operator, verbatim: "Stop pushing there... We're not going public yet."** Nothing goes to
  SeniorCareMarket/eve (public) — no branch, no merge, no tag — until the operator says otherwise; the release brief's
  step 0 push proof and RELEASE steps 1–5 are suspended. Already public there, pushed by Claude: `claude/eve-0.9-release`
  `bb1655e` (6:34 PM ET, the whole `eve/` incl. `doc/release-0.9/`, `doc/network-atoms/`, `doc/unification/`) and
  `claude/push-probe-60t1jk` `7a9a5cf` (6:53 PM ET, one probe file); this session's git proxy refuses their deletion
  (HTTP 403).

- 7:16–7:20 PM ET — **lane finished** (operator: "Just finish this lane"). `bb test:vs` on this branch with main's
  builder: 260 passed, 0 failed (97 s). `eve/` reads nothing outside itself, so the 0.9 lane's per-host results on this
  same source stand (cited, not re-run). The report: `REPORT.md` (this directory), logs in `logs-2026-10-21/`.

- 8:07 PM ET (2026-10-21) — the box restarted; disk, branch and host runtimes intact (the egress proxy moved to port
  34333: `~/.m2/settings.xml` updated, the first JVM run had failed at dependency resolution, not a test). The
  operator: check every item of the lane's change list on this branch. main's `eve/` had no change since the lane's
  base (`git diff 112f6ced2 f65b23153 -- eve` empty), so none was fixed by main: each is here because it was copied.
  **Static, every item:** no `eve/` code file differs from the lane's tip (`git diff 36cc025a HEAD -- eve/ ':!eve/doc'`
  empty); per item, every line its commits added is on the branch or was replaced by a later lane commit in the same
  area (chiefly 9.31's `2397b2d6`/`a3693674` over 9.25's walks); 1.1 (`*memo*`, `MEMO-MAX`, `remember!` top-level before
  `coll-rec`) and 1.8 (`:int32` writes `unchecked-int`) read by hand. Proving runs, one at a time, in
  `logs-2026-10-22/` (`runs.tsv`): Node `all` 482 tests / 34,980 assertions, 0 failures (77 s).

- 8:08–9:16 PM ET — proving runs on this branch, one at a time (`logs-2026-10-22/runs.tsv`), every count the lane's own:
  Node all 482 / 34,980, net 62 / 1,429, slab-grow 105 / 28,902, thread-domain 12 / 146, deftype 63 / 710; JVM 546 tests /
  9,634 assertions (157 s); bb 247 / 4,748; bb-minimal 242 / 4,623; jolt 199 / 4,479 (box peak 2.6 GB); nbb 242 / 4,617
  (first on the lane's final tree; box peak 5.2 GB); ClojureCLR 202 / 4,499; browser 48 / 633; glojure 182 / 4,458 (box
  peak 3.1 GB) — 0 failures, 0 errors each. One defect found and fixed: `impls/glojure/build.sh` on a cold Go module
  cache (`9555b23a`; proved by a build from an empty cache, 25 s). basilisp's item namespaces running.

- 10:12 PM ET — clojerl did not load `eve.deftype-proto.serialize` (9.31's stored-piece fn held in an atom by a
  top-level form; clojerl compiles a def's value as a literal): fixed, with the leak probe building its trees inside
  the atom's domain; then eve.tally-test's `^bytes` hint and its `swap!` (clojerl's `swap!` takes only its own atom)
  referred from eve.host — eve's code final from here (the three commits before `logs-2026-10-22/rebase-map.tsv`'s
  `2f4c75b81`).
- 10:42 PM ET — `bb eve-media verify` PASSED on the final code: six runs, every check and comparison row (712 s).
- 10:56–11:06 PM ET — the perf suite on every host, one at a time; every host page's table and reading regenerated.
- 11:07 PM ET → — the final measurement pass: every battery, bench and probe the docs quote, one run at a time
  (`f-*.log`), after four read-only inventories of every statistic in eve's docs (operator: "every statistic that is
  stale will need to be made unstale").
- 12:30 AM ET (2026-10-22) — rebased onto `origin/main` `f8cfdb0dd` (operator: "Pull main and rebase on that... All
  work is dilineated by the 3 sub repos. All are private right now. "/eve" will be public soon. "/vs" a little later.
  "/atom.broker" never."): clean; the three commits spanning two sub-repos split one per sub-repo; `eve/` gains only
  main's six path edits. The public SeniorCareMarket/eve branches were deleted by the operator (12:17 AM ET:
  `git ls-remote` lists neither).

## Resumed on a fresh box (2026-10-22, session_01KMUtvFR1sxQD4sMtJGPP7U, branch `claude/adoring-hypatia-1zww0c`)

- 2:09 AM ET — the previous session ran out of usage at about 2:05 AM ET. This branch was fast-forwarded to its last
  push (`claude/keen-feynman-60t1jk` `8af95be9`) and continues there. Lost with the old box (nothing pushed was lost):
  basilisp's full battery (started 1:51 AM ET, about a third through), the queued re-runs, the results sheet and the
  unstaged edits of the host-page and release-paper doc agents. Every host runtime reinstalled, 2:10–2:17 AM ET
  (Erlang/OTP 25 + rebar3 + .NET 8 by apt, basilisp 0.5.1, nbb 1.6.214, ClojureCLR 1.12.6, clojerl `4ad14e5`, threaded
  Chez 10.4.1 built from its tag, jolt `f79e64d8`, eve's npm install with its addon).
- Found on the way: `eve/.gitignore` ignores `*.log`, so a new leg's log is added with `git add -f`. The two cost probes'
  first runs (1:17 AM ET) measured nothing: clojerl read the file argument after `-pa` as one more code path and opened
  a REPL (exit 0), and glojure has no `deftype` of its own (exit 1). Fixed (`4604fc40`, `e58c9bf8`); clojerl's ran 2:18
  AM ET: an eve long-array's `aset` 0.035 µs, `aget` 0.040 µs; the binding frame 20 vars, 3,105 bytes, a round trip
  2.4 µs. The docs had 1.3 µs and 6.5 µs; corrected (`913b98e7`).
- CHANGELOG's commit count (314 to 10-20, 374 through `7803976aa`) did not reproduce: it counted this branch's history,
  which carries the lane's `eve/` as one commit (311 through `7803976aa`'s rebased `6a111bdc`). Counted on the lane's
  own branch: 315 commits to `eve/` between 2026-10-15 and 2026-10-20 ET, 340 through `36cc025ad` (`6e3bf5ba`).
- Host pages' "this tree" rows moved to the final pass's figures for the JVM, Node, the browser, nbb, ClojureCLR, jolt
  and VS, with the final pass's rendezvous and rotation rows (`120ab97d`).
- 2:19 AM ET — basilisp's full battery (`f-lpy`) started, alone; then `eve.domain-open-test eve.thread-test` by name.
  Queued after them, one at a time: `eve.thread-net-test` on basilisp by name; the re-runs over the post-midnight code
  fixes (`28e2c61` bb's file lock → bb, bb-minimal; `4eac212` glj-eve's region and clojerl's NIF → glojure, clojerl);
  glojure's cost probe (needs glj-eve); the capstone's verify (`4eac212`–`8655067` changed its launcher); `npm pack
  --dry-run` last, after the doc edits.
- 2:20–3:00 AM ET — while basilisp ran, doc corrections where the final pass's own logs contradicted the docs (each
  commit names its log): babashka's `swap!` fails at seven extra arguments, not eight (`f-bb-swap-args`); basilisp's
  per-call costs, RRB build, GIL ratio, `interleave` and compile time (`f-probe-lpy-costs`, `-interleave`,
  `f-start-lpy`); glojure's core.rrb-vector suite 21 min (`f-glj-rrb`); the JVM's VarHandle costs
  (`f-probe-varhandle`); the browser's growable-buffer cost, recomputed from `f-node-perf-*` (median 1.06×,
  0.95–1.24×); nbb's thread-net memory (`f-probe-nbb-rss`); ADOPTER-TIERS §6 and network-atoms' crypto table
  (`f-seam-*`). The four earlier non-zero probe runs are explained in `logs-2026-10-22/probes/README.md`.
- 2:45–2:50 AM ET — **9.26 again on the final tree, and a second error behind it.** basilisp's full battery, alone on
  the box (load ~1.3): `eve.thread-net-test/a-domain-pool-over-a-network-atom-at-a-broker` "the ticker did not answer"
  (`:awaiting :ticker-up`), then `a-gateway-serves-the-disk-atom-on-eve-net's-routes` the same (`:awaiting
  :pumps-started`). At 2:58 AM ET the process held 11 threads; every one but the main thread started after 2:45:51
  (so `eve.thread-domain-test` left none), and those of the two failed tests were still beating, 10–20% of a core
  each (72 s and 96 s of CPU by then), under the GIL. Read in the source: `start-ticker!`
  (`src/eve/thread/domain.cljc:476-490`) waits 120 s for its ticker's first beat and, when that wait throws, nothing
  stops the ticker — `attach!` throws, so no handle reaches it, and it beats for the life of the process; and the
  two tests detach their handles only at their ends (no `finally`), so an earlier failure leaves its other handles'
  tickers running too. The second error is that cascade's; the first is 9.26's own (the first beat of a network-rung
  runtime past 120 s late in basilisp's run). Not changed here: a fix is a shared-code change, and every battery of
  this final pass would be re-run on it. Recorded as RELEASE-CHECKLIST 9.34 with the patch.
- 3:15 AM ET — basilisp's full battery ended (`f-lpy`, 57 min alone, peak 1.0 GB): **180 tests, 4,360 assertions, 0
  failures, 5 errors** — the two above and all three of `eve.thread-fold-group-test` ("pool :g's threads did not
  start"), run while the failed network tests' tickers and pumps still beat (at 3:10 AM ET six of their threads were
  alive, one at 40% of a core). Alone, each namespace is green: `eve.thread-net-test` 5 / 47 in 1,233 s (the domain pool
  501 s, the gateway 239 s; `f-lpy-net`), `eve.thread-fold-group-test` 3 / 49 in 953 s (`f-lpy-fold-group`).
  `eve.domain-open-test eve.thread-test` 16 / 159 (114 s, `f-lpy-open-thread`).
- 3:54–4:42 AM ET — over the post-midnight code fixes, each green with the same counts: bb 247 / 4,748 (417 s), bb-minimal
  242 / 4,623 (392 s), glojure 182 / 4,458 (753 s with glj-eve's first build here), clojerl 198 / 4,400 (1,297 s, peak
  11.6 GB). Every one ran longer than on the first box (306, 315, 473, 978 s): an A/B of bb's battery on this box,
  the tree before `28e2c61` against HEAD, is queued to tell the box from the code. The macOS ffmpeg check re-run: green
  (`f-mac-ffmpeg-fallbacks`). glojure's cost probe: a protocol call 3.06 µs, a fn call 0.10 µs.
- 4:42 AM ET — the capstone's verify stopped at its Node member's build: Maven Central answered 429 (Too Many
  Requests), as on 2026-10-20; `~/.m2/settings.xml` given Google's Central mirror again (this box's had only the proxy).
  Re-run 4:45 AM ET.
- 5:00 AM ET — **the capstone's verify PASSED** on the final tree (`f-capstone-verify`, 1,046 s with its first builds
  here): core, core-kill, core+beam, extended, extended-kill, extended-no-beam — every check and comparison row true.
- 5:00–5:14 AM ET — the A/B: bb's battery on this box on the tree before `28e2c611` 407 s, HEAD 411 s (and 417 s at
  4:01 AM ET): the second box's longer times are the box's. `npm pack --dry-run`: 197 files, 667.9 kB packed.
- 5:17 AM ET — **the final pass is finished.** Every leg the previous session queued has run on the final tree; every
  host page, README, CHANGELOG, CLAUDE.md, platform-support, the checklist and REPORT (§7 every item proved, §8 this
  session) cite its logs. Open: 9.26 and 9.34 (basilisp), clojerl's 11 GB, the codec set (§3). Not done: the release
  (not going public), merging into `main` (not instructed).

## 9.34 landed, and the batteries re-run on it (2026-10-22, operator-instructed)

- 9:24 AM ET — operator, verbatim: "Land checklist 9.34's patch on claude/adoring-hypatia-1zww0c exactly as written in
  the checklist — attach! cleans up after itself when it fails and the thread tests detach in a finally — then re-run
  the full battery set on the fixed tree and declare with basilisp's full-run result at the head of the report." The
  box had rebooted (uptime 1 min, disk and runtimes intact; `~/.m2/settings.xml` rewritten without the Central mirror,
  given it again).
- **The change list, frozen before the runs** (rule 22): (1) `src/eve/thread/domain.cljc` `start-ticker!` — the
  first-beat wait inside `h/attempt`; when it fails, `(h/attempt (fn [] (detach! dh)))`, then `(throw r)`; `detach!`
  declared ahead. (2) `test/eve/thread_net_test.cljc` — each of its five tests' `dom/detach!` in a `finally` covering
  everything after that handle's `attach!` (nested where a test attaches two); no assertion changed. The checklist's
  test scope is `eve.thread-net-test`'s tests; the other thread test files keep their end-of-test detaches.
- **The runs** (logs `logs-2026-10-22/f3-*.log`, one at a time; builds: Node's `npm run test:compile` once, the
  capstone's Node member and page by its own staleness check; glj-eve is cached by its Go sources, which this change
  does not touch): queue 1 — basilisp's full battery with a thread sampler beside it, glojure, clojerl; queue 2 —
  Node (`test:compile`, then the 28 suites the final pass ran), the JVM, bb, bb-minimal, nbb, ClojureCLR, jolt, the
  browser, VS; queue 3 — the capstone's verify, the twelve two-process rendezvous pairs, the rotation, the fold
  rendezvous.
- 9:27 AM ET — the patch landed (`fe9dc0c1`, eve tree `37382f7a`): `start-ticker!` 11 lines (the wait in `h/attempt`, the
  detach and rethrow on failure, `detach!` declared ahead); `eve.thread-net-test`'s five tests' detaches in a
  `finally`, no assertion changed. A formatter hook re-indented 50 unrelated lines of `domain.cljc` on the first edit;
  reverted, the change made by an exact replacement. Gate: `bb test:bb eve.thread-net-test eve.thread-domain-test`
  12 / 143, green (13 s, `f3-gate-bb`).
- 9:29 AM ET — queue 1 started: basilisp's full battery (`f3-lpy`), its thread sampler beside it (`f3-lpy-threads.log`,
  every 30 s), then clojerl.
- the operator's Mac (its log stamped 09:23–09:27): `bb eve-media verify --only core,core-kill,core+beam` (on `main` `28e2c611`) passed
  core and core-kill and failed core+beam: clojerl's member never joined (`clje.log` empty — its stderr is
  `clje.log.err`, asked for). And no page was seen: the browser member was always headless and every run stopped its
  members once the index was complete. Fixed (`eef4f63d`): a window of its own where the machine shows windows (macOS;
  Linux with `DISPLAY` / `WAYLAND_DISPLAY`), headless elsewhere or with `--headless`; `demo` and `scan` keep a shown
  page up until Enter.
- 9:50 AM ET — operator: "Put the fix on main". The GUI fix alone, cherry-picked onto `main` `28e2c611`: `3a0d887c`
  (its diff byte-identical to `eef4f63d`'s), pushed as a fast-forward. `main` now differs from this branch by that
  commit's hash; 9.34 and the docs stay on this branch.
- Found meanwhile: the first box outlived its session and pushed its basilisp battery to `claude/keen-feynman-60t1jk`
  (`bd4511cd`, 2:52 AM ET): 180 / 4,413, 1 fail (fold's chunk-size timing bar), 1 error (9.26, at the gateway test's
  attach). Carried here as `f-lpy-box1.log` (`9fe60dc1`).

## eve-media: the supervisor, one coordinator, jolt everywhere (2026-10-22, operator)

- 10:15 AM ET — operator, verbatim: "let's not use clojerl as the gateway or the main logic runner. Just the supervisor
  role." · "But yes, let's do the rest of those." · "We don't want coordinator by election. That's not part of this
  demo." · "But it's okay if the jvm takes over the role, if beam fails to install or whatever". (Before it: "Jolt would
  provide the ffmpeg transcoding... Can we make that multi platform?" · "Doesn't it make sense that each runtime is kept
  alive by clje's supervisor system?")
- **The change list, frozen before any run** (rule 22):
  1. No election. One coordinator, the JVM member (the launcher names it in `control :config :coordinator`; bb when a
     run has no JVM): `eve-media.member` runs the coordinator's passes on that member only — no lease, no preference,
     no handoff; the index records `:coordinator`. The duplicate grouping stays on the coordinator's own queue.
  2. The supervisor: clojerl, an OTP supervisor (one-for-one; a worker per child owning its OS process through a
     port; a child that exits abnormally restarted; at most 5 restarts in 60 s). The launcher writes the children into
     `control :children` (the gateway; the disk members; the network members and the browser once the servers are
     up) and starts only the supervisor, which it restarts if it dies. The coordinator's restart asks (a member whose
     lease lapsed) are honored by the supervisor, killing that child's process. With no BEAM, the JVM supervises:
     the same children and policy on `ProcessBuilder`. Their shared bookkeeping in `src/eve_media/supervise.cljc`.
     clojerl is no longer a job member.
  3. jolt on every platform: eve's `region/jolt-ffi` takes its atomics and wait/wake from a C helper built on first
     load (`__atomic` builtins; futex on Linux, a 100 µs poll elsewhere) — no `libatomic.so.1`, no syscall 202. The
     capstone's `eve-media.av` loads FFmpeg's libraries by path (`pkg-config`, `.so` or `.dylib`) and reads its struct
     offsets and constants from a C helper compiled against the installed headers — no FFmpeg 6.1 pins. Setup installs
     jolt and a threaded Chez on macOS.
  4. One default set: bb, JVM, Node, glojure, jolt, the browser, clojerl supervising; the JVM's ffmpeg CLI transcodes
     when jolt is missing, the JVM supervises when clojerl is. `verify`: full (with the dropped file), full-kill (jolt
     killed mid-transcode), coordinator-kill (the JVM killed mid-run), jvm-supervises (no BEAM), cli-transcode (no
     jolt); rows: each run's checks, full-kill at `:attempts 2` with full's checksum, coordinator-kill's and
     jvm-supervises' outputs equal to full's.
  5. The capstone README and the docs that describe its election rewritten.
- **Runs, after 9.34's queues** (one at a time): `bb test:jolt` (the region adapter changed), then `bb eve-media
  verify` (the five runs) on this box; the Mac's runs are the operator's. 9.34's queue 3 drops its capstone verify:
  this verify runs on the 9.34 tree.

- **Amended before any run (10:50 AM ET): item 2's last clause, item 3 as built, and a sixth item.**
  - 2 — the launcher does not restart the supervisor: a supervisor that ends before the run does ends the run (a
    failed run, logged). Its children outlive it (their processes are their own), so a second supervisor would start
    a second of each beside them; restarting it needs it to adopt or kill the first's children — not built, and not
    asked for (the operator's ask is that clojerl keeps the runtimes alive).
  - 3 — no C helper and no build step: jolt.ffi over what the system ships. The region: Linux keeps libatomic and the
    futex (`syscall` 202 on x86-64, now 98 on arm64, a poll on another architecture); macOS takes libSystem's
    OSAtomic barrier functions (a boolean CAS and an add answering the new value; the load, store, exchange and
    value-answering CAS built over them) and the 100 µs poll; `O_CREAT` per platform; `open` declared variadic (Apple
    arm64 passes its mode on the stack). `eve-media.av`: FFmpeg 6.1, 7.x or 8.x by libavutil's major, each library by
    the platform's file name (`.so.N`, `.N.dylib`; `EVE_FFMPEG_LIB`, Homebrew's and MacPorts' lib directories), the
    majors checked against each other; what moves between releases measured here with `offsetof` against n6.1's,
    n7.1's and n8.0's headers — three AVFrame fields (sample_rate, best_effort_timestamp, ch_layout) and
    AVCodecContext's sample_fmt (FFmpeg 7 removed `channels`: the 6.1-pinned code would have written the AAC
    encoder's sample format into another field on 7.x and 8.x); `AVERROR(EAGAIN)` and `open`'s flags per platform.
    Setup installs Homebrew's `jolt-lang/jolt/jolt` (a release build carrying its own Chez) rather than building jolt
    and Chez. Why: no compiler needed for jolt, nothing written beside the sources on first load, and the offsets
    taken from the headers by a compiler once, here, instead of on each user's machine.
  - 6 — the page. Operator, verbatim (mid-turn, ~10:30 AM ET): "add some more explanatory text to the demo app, which
    explains what the app is doing... maybe even something that shows what each runtime is doing, so that the demo
    exposes more the multi-runtime nature of the app." Built: what the application does, step by step; a card per
    process (runtime, language, how it reaches the atoms, its part, what it runs now, what it has run, its starts and
    exits; the supervisor's); a feed of every step the page sees; each job chip edged and labelled with the runtime
    that ran it. The view's worker now also reads the control atom (each child's host and run — never its command or
    environment).

- ~11:20 AM ET — operator, verbatim: "When the capstone is ready for me to demo merge that to main". Ready means: the
  jolt region change green (its macOS-arm probe, `bb test:jolt`), one demo run showing the new page, and `bb eve-media
  verify`'s five runs passing on this box; then this branch merges into `main`. To get there sooner, 9.34's queue 3 is
  split: 3a (the JVM battery's re-run — queue 2's stopped at classpath resolution, Maven Central 429 after
  `~/.m2/settings.xml` was rewritten without the mirror at 10:25 AM ET, restored 11:05 AM ET — and the JVM–jolt
  rendezvous: the legs that need the tree without the region change) right after queue 2; then the capstone's runs;
  then 3b (the other rendezvous pairs, the rotation, the fold rendezvous, basilisp's two namespaces alone).

- ~11:50 AM ET — operator, verbatim: "While we're waiting, I'd like you to get a fix on main so that I can see them...
  If you haven't yet impl'd them, do that and then put them on main so I can test." The auto-mode permission check
  refused the commits aimed at `main` (twice); operator, verbatim: "push to main". Done the plain way: `main` merged
  into this branch keeping the branch's tree (`2c204917`; main's one commit not here, `3a0d887c`, is `eef4f63d`
  cherry-picked, the same diff), the jolt region change committed here once 9.34's queue 3a had run jolt on the fixed
  tree (`21c6a510`), and `main` fast-forwarded to it: `3a0d887c..21c6a510`, 12:00 PM ET. Checked before: the launcher
  loads and `bb eve-media setup` answers "ready for bb browser clje glj jolt jvm node"; the page compiles; the jolt
  region's macOS arm on Linux over shims and its Linux arm on a mapped file (each a single process beside f3-glj).
  Not yet run: the redesigned capstone end to end (next here: jolt's battery on the region change, a demo run, the
  five-run verify), anything on a Mac.
- Queue 2 (10:47–11:53 AM ET): Node's 28 suites, bb, bb-minimal, nbb, ClojureCLR, jolt, the browser, VS, glojure — every
  leg exit 0, the counts as the final pass's; the JVM's leg stopped at classpath resolution (`f3-jvm.log`: Maven
  Central 429 — `~/.m2/settings.xml` had been rewritten without the mirror at 10:25 AM ET; restored 11:05 AM ET).
  Queue 3a: the JVM battery again (`f3-jvm-2`, 546 tests, 9,826 assertions, 0 failures, 0 errors, 191 s), the JVM–jolt
  rendezvous (65 s, exit 0).

- 12:05–12:12 PM ET — the capstone's runs on this box: the jolt region's macOS-arm probe in the tree (`c-jolt-region-mac`,
  every check true) and jolt's battery over the region change (`c-jolt`: 199 / 4,479, 0 failures, 0 errors, 262 s —
  261 s on the tree before it); the redesign's first run end to end, `bb eve-media demo --headless` (`c-demo`): clojerl
  supervising, the JVM coordinating, jolt transcoding natively, every check true, 266 s.
- The operator's Mac, on `main` `21c6a510` (their screenshots, ~12:06–12:08 PM ET): the JVM supervised (clojerl did not
  come up there), jolt exited six times in ~2 s and was given up on (its `jolt.log.err` asked for), the files waited
  `:new` while the coordinator waited for jolt to join, then every file done — the JVM's CLI transcodes, the browser's
  waveforms, the page's re-queue. Operator: "There's so much info that we can no longer see the table at the bottom
  and the info at the top at the same time... We need to have more economy with that text." The page compacted
  (`bd8f7fad`, `main` fast-forwarded to it): a line of totals with what planning waits for, one sentence and a closed
  "how it works", three-line cards (the rest in their tooltips), a five-line feed.
- 12:57 PM ET — the box had rebooted (uptime 0 min; disk, runtimes and the scratchpad intact). `~/.m2/settings.xml`
  was again without the mirror: the session's start rewrites it (`ccweb-setup` — the same at 10:25 AM ET, the session's
  previous start); given again before any JVM leg. Then: `bb eve-media verify` (the five runs), and 9.34's queue 3b.

- 12:58 PM – 2:10 PM ET — `bb eve-media verify` PASSED (`c-verify.log`, `.edn`: five runs, every check and row true);
  queue 3b: the other eleven rendezvous pairs, the rotation, the fold rendezvous, each `:ok true`; basilisp's
  `eve.thread-net-test` alone 5 / 47 (1,214 s) and `eve.thread-fold-group-test` alone 3 / 49 (1,016 s), green. 9.34's
  re-run is complete. Found in verify's full-kill: the coordinator's `restarts!` marked a restarted member `:joined`
  from its previous process's registry entry and asked to restart it while it was still starting — fixed
  (`5bbe4f80`: members mark themselves joined); verify re-run on it (`c-verify-2`).
- **Declared** (REPORT §0, basilisp's full run first): 9.34 fixed; every battery as before on its tree but basilisp's
  full run, 180 / 4,350 with 7 errors (9.26; 9.35). RELEASE-CHECKLIST 9.34 closed, 9.26 and 4.3 carry the fixed
  tree's run; `eve/CLAUDE.md`, `README.md`, `doc/hosts/README.md` and `doc/hosts/basilisp.md` carry basilisp's.

- The capstone's verifies after the coordinator fix (the launcher's log stamps are UTC; ET here): `c-verify-2`
  (2:10–2:41 PM ET) FAILED by its full run — babashka never started (clojerl's notes: no start between its ask and its
  go), the index never came; the other four runs' own checks true, full-kill with jolt started again once (the
  coordinator fix holding). The supervisor's waiting worker now asks again every 2 s, the notes log each ask and go
  (`2eb22d78`). `c-verify-3` (2:41–3:04 PM ET) FAILED by one check, the full run's page check: the page's re-queue never
  made (f01 no `:requeued`, control no `:page-check`), no error in the console; every other row true. The page's
  runtime loop now survives a failed network read, and the check's steps are logged (`c69579ee`). `c-full-4`
  (3:05–3:10 PM ET, `verify --only full`) PASSED, the check's steps in the console from join to sent. **`c-verify-4`
  (3:10–3:30 PM ET) PASSED**: five runs, every check and every row true (`c-verify-4.log`, `.edn`).
- Correction (direction: later than true, by the UTC offset): the commit messages of `2eb22d78` and `c69579ee` give
  verify-2 as "6:10–6:41 PM ET", verify-3 as "6:41–7:04 PM ET" and the lost start as "6:12 PM ET" — the launcher's UTC
  stamps quoted as ET. They were 2:10–2:41, 2:41–3:04 and 2:12 PM ET; the supervisor's docstring corrected.

## `$` restored as the read-region macro (2026-10-23, operator)

- **The ask.** Operator, 2026-10-22/23: "nah, you don't call `$` that way." · "obviously, `$` doesn't work that way." ·
  "You can't find how `$` works? There's entire docs written up on it." · "Nah, you can do the fix." The design
  (`docs/phase-4-namespace-registry.md` "The `$` Macro", `docs/network-atoms/phase-5-remote-atom.md` "Batched Lease
  Release"): a region around a body; inside it a deref answers the lazy value; leases (pins) accumulate and are let go
  together when it exits; only what the body answers is materialized. The 0.9 tree shipped the function
  `($ a f & args)` instead — the unification lane's verdict (`doc/unification/CANDIDATE-VERDICTS.md` §3, `b5c2ffc59`,
  2026-10-15), now marked reversed there and in UNIFICATION-REPORT §3 item 3.
- **Landed** `147088b52`: `src/eve/read_region.cljc` (new, portable cljc: `*region*`, `hold!`, `on-exit!`, `run`);
  `eve.alpha/$` (macro) → `eve.atom/$*`; `eve.atom`'s deref answers the lazy value inside a region (`region-deref`: the
  domain pinned on its first read, the pin held to the outermost exit) and plain data outside (`plain-deref`); the
  registry read stays plain; `eve.net.atom/deref` keeps its lease to the region's end, the lease a later deref
  replaces too. The function is `eve.atom/read-pinned`; eve's own callers (rung, gateway, tests, the perf workload
  `atom-$-get-map-1k`) use it. Docs, README, the in-memory example and CHANGELOG [Unreleased] to `eve.alpha/$`; every
  changed doc sample re-run on babashka and the JVM (`probes/dollar_docs.cljc`, both `dollar_docs: ok`). Not carried
  from the design, named in the CHANGELOG: the 30 s lease heartbeat (production's Worker has no renew route, and
  `renew!` releases a lease the broker does not know) and read-set tracking (eve.net.atom's named limit).
- **The test** (`test/eve/read_region_test.cljc`, every runner; the lease case in `eve.net.remote-atom-test`). Its
  negative control (`probes/dollar_no_pin.clj`: the hold a no-op) — first version: two assertions failed and the
  write-in-region test passed without the pin (`dollar-no-pin-bb.log`, `-2`): the allocator reuses a freed block only
  after its cursor wraps, so a value read stayed readable either way. Rewritten to assert the mechanism — every write's
  retired blocks stay queued while the region runs, and are freed at the first flush after it — the control then
  failed three assertions, the queue's among them (`dollar-no-pin-bb-3.log`: held 0 of 20).
- **Two defects the runs found, both mine, both fixed**: `eve.net.atom/deref` named `release!` before its definition
  (the JVM's first run, before the commit; `release!` moved above `deref`); the test used `volatile!`, which clojerl
  lacks (`g-clje`: "Unable to resolve symbol: volatile!"; an atom now, `fb00448e6`).
- **The batteries** (`logs-2026-10-23/g-*.log`, `runs.tsv`; one leg at a time, 11:14 AM – 2:05 PM ET). Every count is the
  last pass's (`logs-2026-10-22/f3-*`) plus exactly the new tests (+5 tests, +18 assertions where the runner lists
  `eve.read-region-test`; Node's `net` +1 / +3, the lease case):

| leg | result | last pass |
|---|---|---|
| Node: `npm run test:compile`, then the 28 suites | every suite green; `all` 487 / 34,998, `net` 63 / 1,432, `slab-grow` 110 / 28,920, the rest the last pass's; the compile's warnings 402, the last pass's | 482 / 34,980; 62 / 1,429; 105 / 28,902 |
| `clojure -M:jvm-test` | 552 tests, 9,835 assertions, 0 failures, 0 errors | 546 / 9,826 (its assertions move run to run) |
| `bb test:bb` | 252 / 4,766, green | 247 / 4,748 |
| `bb test:bb-minimal` | 247 / 4,641, green | 242 / 4,623 |
| `bb test:nbb` | 247 / 4,635, green | 242 / 4,617 |
| `bb test:cljr` | 207 / 4,517, green | 202 / 4,499 |
| `bb test:jolt` (`g-jolt-3`) | 204 / 4,497, green | 199 / 4,479 |
| `bb test:clje` (`g-clje-2`) | 203 / 4,418, green | 198 / 4,400 |
| `bb test:glj` | 187 / 4,476, green | 182 / 4,458 |
| `bb test:browser` | 48 / 633, green (no read-region test in its suite) | 48 / 633 |
| `bb test:vs` | 260 passed, 0 failed (its runner loads no eve.atom) | 260 |
| the twelve rendezvous pairs, the rotation, the fold rendezvous | each `:ok true` | each `:ok true` |
| `bb test:lpy` | 185 / 4,368, 0 failures, 7 errors — the last pass's seven tests, by the same messages (9.26, 9.35); the read-region test's five ok | 180 / 4,350, 7 errors |

- **Runs that did not test anything**: `g-jolt` (jolt not on part A's PATH) and `g-jolt-2` (`JOLT_CHEZ` unset: "No
  threaded Chez Scheme 10.x found") — the leg environment `doc/hosts/jolt.md` names (`PATH` with jolt, Chez, clojerl
  and basilisp; `JOLT_CHEZ`), which the last pass's queue set, was not carried into today's first script; part B was
  stopped at clje's start and re-run with it. `g-clje` stopped at
  analysis on the test's `volatile!` (above).

- **On main's tree** (`3de771f09`, 2:15–4:25 PM ET). main had moved 205 commits since the last fast-forward (the
  descriptor Part B landing, the aegis mainline), changing `atom.cljc`, the allocator, retire and tally under `eve/`.
  Four conflicts, resolved: `atom.cljc` (main removed `free-attempt!`; the region's `mmap-deref` kept), `tally_test`
  (main's rewrite; its nine calls of the old `($ a f)` renamed `read-pinned`), the bb runner (main's list plus the new
  test), the CHANGELOG; `atom-domains.md`'s citations remapped to the merged file. Then, one leg at a time
  (`m-*.log`; after the fixes below, `f-*.log`):

| leg | result |
|---|---|
| Node: compile, the 28 suites | 24 green at the branch's counts. `all` 488 / 35,185, `slab` 69 / 3,068, `slab-grow` 111 / 29,107: each 2 errors, main's tally test's two eve-deftype tests (open, below). `thread-fold` 1 failure once ("pool :eve.thread/fixed-1 terminated": `await-termination` false), green on its one re-run (`f-node-thread-fold`) — not root-caused |
| `clojure -M:jvm-test` | 599 / 11,097, green (`f-jvm`, after the list fix: 599 / 11,040) |
| `bb test:bb` | 300 / 6,189, green (the same after the fix) |
| `bb test:glj` (`f-glj`, after the list fix) | 188 / 4,675, green — before it, the tally test's 6 errors (`m-glj-rr`) |
| read-region + tally on nbb | 12 / 220, the same two eve-deftype errors |
| read-region + tally on cljr, jolt, clje (and after the fix), basilisp | each 12 / 234, green (basilisp's tally test 16 min) |
| rendezvous jvm–jvm, jvm–bb | `:ok true` |

- **Fixed on the merged tree**: a list stored on glojure (`9e23c0554`; CHANGELOG [Unreleased] Fixed) — the writer's
  `(reduce f init (rseq elems))` has no `coll-reduce` method there.
- **Open, main's: the tally test's two eve-deftype tests on ClojureScript and nbb** (`an-eve-deftype-value-two-atoms-hold`,
  `a-reference-field-written-in-place-keeps-its-counts`). On main's own tree too (`main-node-all.log`, the side worktree
  at `cc49a14e2`: the same two errors; its five keyring errors are that worktree's, "mmap_cas native addon not found" —
  the addon is built in the main checkout only). A JS eve-deftype answers no `IEveRoot`; given one (`9e23c0554`), the
  tests fail one read later, `(d/-eve-sio holder)` nil (`f-node-all.log`) — reverted (`40a2d56ec`): a JS eve-deftype
  allocates and frees through `eve.process-slab` and has no domain, and these tests count an instance in an atom's
  domain, which main's descriptor Part B lane has not given the JS emitter. To reproduce: `npm run test:compile`,
  `node target/eve-test/all.js all`; `bb test:nbb eve.tally-test`.

## Phase 1 — audits

| # | Deliverable | State |
|---|---|---|
| 1 | AUDIT-DOCS.md | committed: 46 documents, 0 of 13 user-facing CURRENT; 10 ARCHIVE; 15 EXCLUDE; 15 MISSING |
| 2 | AUDIT-API.md (+ impls, item 4) | committed: user / extension / internal classification, the three headline APIs, the diff, 5 code defects + 3 found by the integrator |
| 3 | ADOPTER-TIERS.md | committed: 16 families (the brief said 12), minimal 4 + inbuilt 9, fast per capability, host × family matrix generated from hosts.edn, cross-check run |
| 5 | RELEASE-CHECKLIST.md | committed: 13 sections; ❌ items listed in §13's order |

## Phase 2 — build

| # | Deliverable | State |
|---|---|---|
| 6 | README rewrite | committed |
| 7 | adopting-eve-minimal.md, adopting-eve-fast.md | committed |
| 8 | doc repairs, CHANGELOG 0.9 | committed |
| 9 | examples | committed (in_memory, disk_domain_processes, network_atom, quick_start, seam_bench) |
| C | eve-media capstone | **`bb eve-media verify` PASSED** (six runs, every row; 6:09 PM ET) and the browser re-run (6:18 PM ET); every touched battery green (7:27 PM ET) |
