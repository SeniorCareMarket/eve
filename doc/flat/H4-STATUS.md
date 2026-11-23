# Lane H4 — the ClojureScript image · STATUS

Branch `claude/static-h4-cljs-image` (local only; the coordinator collates
lane branches from the trees under `/home/hatch/workspace/static-eve/`).
Cross-lane code by path remote: `x1` = ../x1 (base: the merged chunk.cljc),
`z1` = ../z1 (Detach, merged at e3320f4), `z2` = ../z2 (Claim, merged at
d575706 — its `alloc.cljc` carries the shared run claim). All times US Eastern.

## What H4 must prove (IMPL-PLAN §3.4, lane H4; requirements.edn H4.1–H4.2)

- H4.1 `eve.static.image-node-test/image-reads-every-type` — Eve's types
  read an image on Node: Z1.2's rows (nested map/vector/set, hash set,
  eve array, dataset, list, scalar root, the public detach verb) built in
  the module slab, detached, opened, read in place — plus byte identity:
  Node's image of a value is the JVM's image of it, byte for byte.
- H4.2 `eve.static.image-node-test/run-wastes-nothing` — the module slab's
  uncalled `batch-alloc` becomes the run claim: Z2.2's pattern over the
  module slab's class 0 (run of 40 at unaligned start 37, isolated singles
  at 5/90/100; a claim of 40 takes exactly the run, a claim of 41 nothing).

## Ground truth established at lane start (read firsthand)

- `src/eve/static/image.cljc` is already CLJC-shaped (`:cljs` branches
  throughout: Uint8Array/Int32Array forms of the ordinal tables, `.slice`,
  `.set`/`.subarray` copies). What is NOT there yet is a host that runs it:
  no Node runner, no Node test, and several `:cljs` branches are stubs —
  `grow-ints` answers its input unchanged (an Int32Array cannot grow, so
  detach past 1024 blocks / 4096 relocations cannot complete on CLJS),
  and `eve.static.sio`'s view `-block-size` answers -1 on CLJS (no binary
  search) so an opened image cannot answer block sizes there.
- The module slab is `adapters/slab/module/src/eve/process_slab.cljs`.
  Its `batch-alloc` (line 548) has **zero callers** in the tree — the
  plan's "uncalled batch-alloc" verbatim. Its `CljsSlabIO` implements
  `ISlabIO` + `ISlabRegion` but not `ISlabRunAlloc`, so on CLJS
  `alloc-run!` falls to the looping default (one bitmap scan + CAS per
  block). Note the semantic gap the lane must close honestly:
  `batch-alloc` claims _up to_ n blocks, not necessarily consecutive,
  partial fills allowed; the run claim's contract (Z2) is n _consecutive_
  blocks or nil, nothing claimed. The batch machinery (cursor scan,
  `bitmap-find-free`, `bitmap-alloc-cas!`, cursor/free-count bookkeeping
  in `batch-alloc-once`) is the substrate; the run claim is built from it.
- On CLJS a "heap domain" is the module slab itself:
  `alloc/heap-domain-sio!` → the `:heap-domain-sio!` host op →
  `(cljs-sio)`, the one stateless CljsSlabIO. Z2.2's fresh-domain
  geometry is reproduced by the runner's `ps/init! :capacities`
  (class 0 at 16384 bytes = 512 blocks, as in Z2's heap-sio).

## Log

- 06:4x ET — lane start: tree cloned from ../x1 (tip e1480cd, the merged
  chunk.cljc), Z1 merged (e3320f4), Z2 merged (d575706), plan §3.4 +
  X1-STATUS read, ground truth above established.
- Tests-first commit: `test/eve/static/image_node_test.cljs` (H4.1 rows,
  scalar-detach, jvm-node-image-same-bytes with the reference still
  PENDING, H4.2), the runner `test/eve/runner/static_h4_main.cljs`, the
  `:static-h4-test` shadow build. Red until the venue legs run.
- Legs filed (job-agent, lane static-eve/cljs-image, venue j): the JVM
  reference (`h4_jvm_reference.clj` prints the image hex of
  `{:a [1 2 3] :b {:c "x"}}`) → `h4-jvm-ref.log`; the Node probe at the
  tests-first tip → `h4-node-probe.log`. Both clone the staged h4.bundle;
  bundle refresh to the current tip noted for the coordinator.
- Fix commit (desk-provable stubs, before the probe's verdict — the
  probe at the earlier tip stays the red baseline): (1) `eve.static.sio`
  view `-block-size` on CLJS was a constant -1 — now the binary search
  over the JS int arrays, mirroring `Arrays/binarySearch`; (2) the
  builder's offs/szs growth on CLJS was a no-op `set!` — now replacement
  with doubled Int32Arrays, as the byte array already grew; (3)
  `eve.static.image`'s `grow-ints` on CLJS answered its input — now the
  same doubled-copy replacement; (4) H4.2 itself: `batch-alloc-run-once`
  / `batch-alloc-run` in `eve.process-slab`, a consecutive-run claim
  built from batch-alloc's machinery (cursor scan, bitmap-find-free,
  bitmap-alloc-cas!, the one-shot cursor/free-count bookkeeping; a CAS
  lost mid-claim frees the claimed prefix, so a failed claim changes
  nothing), wired as `CljsSlabIO`'s `ISlabRunAlloc`. batch-alloc's own
  contract (up to n, any blocks) is untouched — it had no callers to
  break; the run claim is the strict form its machinery now also serves.
- Successor session: both opening legs died EXIT=128 (cloned before
  h4.bundle was staged — no code verdict in either). Bundle verified
  staged at 10d6788; both legs re-filed in the same form with HEAD
  printed in-log: `h4-jvm-ref.log` (REF:/LEN: hex of the JVM image) and
  `h4-node-probe.log` (shadow compile + the suite at 10d6788; the
  same-bytes arm is expected to fail there — its reference is still
  PENDING until the ref leg lands). On delivery: embed the hex as
  `jvm-reference-hex`, refresh the bundle, re-file the Node suite.
- Successor #2 — DELIVERED and dispositioned: the Node probe at
  10d6788 RAN (4 tests / 12 assertions, 5F / 3E). The JVM-ref leg
  produced NO hex: its -e ran without the -i load ("Unable to resolve
  symbol: h4-ref") — re-filed with the generator script's own
  documented invocation. The probe's reds, by class: (1) ERROR ×2
  "Slab alloc failed: :all-slab-classes-full for 128 bytes" — the
  runner's 8192-byte classes give class 2 only 64 blocks; building a
  row's value exhausts it (JVM rows build in growable heap domains;
  the fixed module slab must be given the room). (2) ERROR
  scalar-detach — a TypeError, an undefined fn value called somewhere
  on the scalar path; the suite prints no stack, so P-B of the probe
  walks it step by step with stacks. (3) FAIL ×5 run-wastes-nothing —
  fill stopped at 399 of 512 and the freed pattern read back as still
  allocated: cross-test pollution of the singleton module slab (the
  earlier deftests' builds are never freed; on the JVM each test's
  heap-sio is a FRESH domain) and/or a free/allocated? defect — P-C of
  the probe runs the pattern on a force-reinitialized slab to
  discriminate. Fixes already in (desk-provable + the measured
  geometry): `slab-capacities` in the test ns (class 0 unchanged at
  512 blocks; classes 1–5 at 1 MiB), an :each fixture re-initing the
  slab per deftest, runner takes its geometry from the test ns.
  Diagnostic probe filed (`static_h4_probe_main.cljs`, build
  :static-h4-probe) → `h4-probe.log`. On delivery: embed the REF hex,
  fix what P-B/P-C convict, re-file the suite.
- Successor #3 — BOTH delivered legs re-derived; neither carried the
  evidence it was filed for. (1) `h4-probe.log`: the probe never
  compiled — one extra closer in its own `allocated-range`
  (static_h4_probe_main.cljs:32, "Unmatched delimiter"), so P-A/P-B/
  P-C never ran and NO suspect was convicted or cleared by it; the
  geometry/hermeticity fixes stand on the suite probe's numbers alone
  until the re-run. Paren removed (c3772ed). (2) `h4-jvm-ref.log`:
  failed the SAME way as the first filing — read from the router's
  done spec, the "corrected" re-file still ran `-e "(h4-ref)"`. The
  root is the generator script's own docstring: the fn is
  `h4-ref/h4-ref` (the script's ns is h4-ref) and `-e` evaluates in
  the user namespace, so the documented invocation could never
  resolve it; two filings copied the docstring faithfully.
  Docstring corrected (c3772ed). Bundle recreated at c3772ed, scp'd,
  clone-verified ON J (HEAD c3772ed, corrected invocation present,
  probe line 32 balanced). Both legs re-filed: `h4-jvm-ref.log`
  (with `(h4-ref/h4-ref)`) and `h4-probe.log` (the probe's first
  real run). On delivery: embed REF hex as `jvm-reference-hex`,
  convict/clear P-B (scalar-detach TypeError) and P-C (pollution vs
  free/allocated?/run-claim) from the probe's printed arms, fix in
  the one :cljs implementation, re-file the Node suite.
- Successor #3, close-out — full account in H4-REPORT.md. The JVM
  reference landed (LEN: 512) and is embedded byte-exact from the
  venue log file (the first embed was hand-copied truncated —
  correction banked, da03e20). Convictions, all measured at the pin:
  (a) the scalar-detach TypeError was `(byte-array n)` at
  image.cljc:442 + sio.cljc:154 — an undeclared Var under :cljs, an
  undefined call on every CLJS detach; conditionalized to
  js/Uint8Array (0add87f), P-B then round-trips the scalar.
  (b) The suite's 0-tests run was the async :each fixture; fixture is
  now the synchronous ps/reset-all-slabs!. The probe's own nested
  `->` chains had compiled to `}).catch());` (venue JS read
  firsthand); probe main rewritten flat. (c) H4.2's red was a test
  premise: -sio-alloc! spills classes by design (32,256 = all classes
  measured); the fill now uses ps/batch-alloc, P-C and the suite row
  both green — **H4.2 :built**. (d) The same-bytes arm differed by
  one pad byte (340: source slack through detach's sized-after-copy
  window); detach now zero-fills gaps at emission — arm green.
  (e) eve.static's :cljs delegate was an unconditional not-built;
  wired statically for image + chunk (place/transfer/open-checked
  stay typed not-built in this tree). (f) List rows cured by merging
  L1 (7973f05). (g) THE RESIDUAL: the Dataset row — the image is
  proven correct (P-E: slab-read-serialized over the view decodes
  col-order and col-map intact), but eve-deftype's CLJS generated
  field reads hardcode (eve.process-slab/cljs-sio) instead of the
  instance's sio (compiled eve.dataset.js:319-320; the assumption is
  documented at serialize.cljc:122). Eve-core's fix, Z1.7's class;
  **H4.1 stays :planned** on that row alone. Final suite at 3b74f4d:
  4 tests, 31 assertions, 3 failures (dataset row only), 0 errors.
