# H3 — other hosts: status

Lane: Wave 2 H3 (IMPL-PLAN §3.4). Requirement H3.1 — Wave 1's Z, T and C
tests green on babashka first, then the remaining slab-I/O hosts, each as
its `<host>-static-suite`. Files owned: runner lists, adapters.

## Base

Cloned from the X1 tree at e1480cd (Node's merged chunk.cljc — the single
chunk implementation — over Chunk's tip), branch `claude/static-h3-hosts`.
At this pin the tree carries Wave 0's contract/requirements tests and C1's
chunk tests only; the Z/T/C2–C4 test files arrive with the collation merge
(coordinator's call), and the suite lists grow to cover them then — the
list-keeper test enforces coverage for every namespace whose file exists.

## What exists

- `test/eve/static/host_suite_test.cljc` — H3.1's test, committed first, red
  (5e8714a: the lists it requires did not exist).
- `test/eve/static/host_suite.cljc` — the runner lists: every host in
  hosts.edn dispositioned. Suites: jvm (reference), bb, bb-minimal, cljr,
  lpy, jolt, clje, glj, jank (JVM-class: .clj + .cljc), and cljs, nbb,
  browser, scittle, cherry (compiled/interpreted ClojureScript: the Node
  chunk suite today). No suite, with reasons: vs (Wave 3 per plan §4), lg
  and cljd (no slab/region/types adapters — not slab-I/O hosts).

## Board (per host — filled from runs, never estimated)

| host                     | binary                                                                                                        | namespaces run                          | result                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | log                                 |
| ------------------------ | ------------------------------------------------------------------------------------------------------------- | --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| jvm (list-keeper)        | J's JDK 21                                                                                                    | host-suite-test                         | **GREEN: 1 test / 315 assertions, 0 failures** (round 2's 41 were the keeper's own munging defect, below); re-verified GREEN on J against the final lists at 85becad (EXIT=0)                                                                                                                                                                                                                                                                                                                                                                                               | h3-jvm-lists3.log; h3-jvm-final.log |
| jvm (requirements)       | J's JDK 21                                                                                                    | requirements-test                       | 2 tests / 521 assertions, 1F + 1E — both the known cross-lane items: W0.6 (SPIKE-LOG.md absent in this tree) and the api-surface `(open nil)` NPE at this pin's chunk.cljc (C1's typed-refusal fix lands at collation). Not H3 items                                                                                                                                                                                                                                                                                                                                        | h3-jvm-lists3.log                   |
| bb                       | /home/john/bin/bb = **1.13.226**                                                                              | requirements, contract, host-suite      | **Runs: 6 tests / 875 assertions, 3 failures, 0 errors — all three in W0's machinery** (requirements-covered W0.6; scoreboard-smoke ×2: the messages corpus arm refuses a babashka SciMap as foreign-domain inside the scoreboard's own swap). Contract roundtrips and the list-keeper green on bb                                                                                                                                                                                                                                                                          | h3-bb-3ns.log                       |
| bb                       | 1.13.226                                                                                                      | chunk-test                              | **Cannot load on bb**: chunk.cljc imports clojure.lang.Murmur3 under :clj, no :bb branch; bb has no Murmur3 class. Excluded in the lists with this reason; the :bb branch is a collation item for C1/X1's file                                                                                                                                                                                                                                                                                                                                                              | h3-bb-suite6.log                    |
| bb-minimal               | 1.13.226                                                                                                      | requirements, contract, host-suite      | **Identical to :bb** — 6/875, same 3 failures (diff-verified same set), portable crypto/int32 set                                                                                                                                                                                                                                                                                                                                                                                                                                                                           | h3-bb-minimal.log                   |
| cljs (Node)              | J's node v24.4.1                                                                                              | chunk-node-test (shadow static-x1-test) | **GREEN: 4 tests / 33 assertions, 0 failures**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | h3-cljs-node.log                    |
| nbb                      | nbb via npm (leg-installed in /tmp)                                                                           | —                                       | **chunk-node-test cannot load under nbb**: eve.static.chunk's CLJS side uses a `js*` form at chunk.cljc:84 that SCI cannot resolve at analysis (discriminator probe bypassing nbb-main localized it to the chunk file itself, not the runner). Excluded in the :nbb list with this reason; the shadow-compiled Node seat runs it green                                                                                                                                                                                                                                      | h3-nbb3.log                         |
| jolt                     | ~/bin/jolt v0.8.23                                                                                            | all four                                | **Suite ran end-to-end.** 4 problem assertions, none a jolt-host defect in static code: requirements-covered (W0.6, universal); api-surface NPE (the pin's nil item, all hosts); chunk-roundtrip `:short` on the first value, nil — reconciled on the JVM at this pin: `(pack nil)` itself throws here, so the root is the pin's chunk.cljc, not jolt; golden-bytes PASSED (byte layout identical on jolt). Plus scoreboard-smoke: W0's scoreboard needs java.time DateTimeFormatter, which no dependency provides on jolt (:jolt/provides gap — scoreboard machinery only) | h3-jolt3.log                        |
| jank                     | /usr/local/bin/jank                                                                                           | —                                       | **No namespace loads** (each measured alone): requirements + contract need clojure.java.io (no such jank module); chunk-test is a .clj file (not a jank module); host-suite-test needs java.io.File (unresolvable). Suite emptied in the lists, each exclusion carrying its measurement                                                                                                                                                                                                                                                                                     | h3-jank-probe.log                   |
| lpy                      | basilisp 0.5.1 (uv-installed by the leg; J has no basilisp and its python3-venv is broken — ensurepip absent) | —                                       | **No namespace loads** (summary {:error 4, :test 0}): requirements + contract die in basilisp macroexpansion of their `is` forms; host-suite-test on its java.io.File form; chunk-test is a .clj file (ImportError). Suite emptied in the lists with the four measurements                                                                                                                                                                                                                                                                                                  | h3-lpy3.log                         |
| cljr                     | —                                                                                                             | —                                       | **Venue-blocked, measured:** no dotnet, no cljr anywhere standard on J — no ClojureCLR runtime on the venue. Suite definition stands in the lists                                                                                                                                                                                                                                                                                                                                                                                                                           | —                                   |
| clje                     | —                                                                                                             | —                                       | **Venue-blocked, measured:** erl + rebar3 present on J, but no clojerl binary or checkout — ClojureErl itself absent                                                                                                                                                                                                                                                                                                                                                                                                                                                        | —                                   |
| glj                      | —                                                                                                             | —                                       | **Venue-blocked, measured:** no Go toolchain on J (PATH, /usr/local/go/bin, /usr/lib/go*/bin all empty) — glj-eve (impls/glojure/build.sh) cannot be built there                                                                                                                                                                                                                                                                                                                                                                                                            | —                                   |
| browser, scittle, cherry | —                                                                                                             | chunk-node-test each                    | Not attempted: no browser-worker harness on J for browser/scittle; cherry has no runner seat here. Their lists already exclude everything but chunk-node-test with reasons                                                                                                                                                                                                                                                                                                                                                                                                  | —                                   |

### Collation items named by this lane (not H3's files)

1. **eve.static.chunk needs a :bb branch** at its Murmur3/Util hasheq
   sites (chunk.cljc:17 import; 1239–1269 folds) following
   deftype_proto/sio.cljc's precedent ("bb has no Murmur3 class"). The
   substitute must be hash-identical to the JVM folds — chunk equality
   is a byte-level domain — so this is C1/X1's call, not a drive-by.
2. **This tree's chunk.cljc is behind C1's tip**: `(pack nil)` /
   `(open nil)` throw at this pin on every host (JVM-reconciled);
   C1's typed-refusal fixes land with the collation merge.
3. **W0's scoreboard on jolt** needs a :jolt/provides shim for
   java.time.format.DateTimeFormatter (RFC 0014) — scoreboard
   machinery only; no static requirement test touches it.
4. **W0's scoreboard on bb**: its messages arm swap!s host values under
   a policy that refuses babashka.impl.SciMap as foreign-domain;
   JVM/bb parity for the scoreboard's today-arm is W0's call.
5. **eve.static.chunk's CLJS side is not SCI-safe**: a `js*` form at
   chunk.cljc:84 fails analysis under nbb/SCI ("Unable to resolve
   symbol: js*"), so the nbb seat (and plausibly scittle, also SCI)
   cannot load the chunk at all. The shadow-compiled seats are
   unaffected. C1/X1's file, at collation.

## Successor #2 session (2026-11-12) — the 41 failures, re-derived

**All 41 JVM failures are ONE defect, and it is in H3's own test, not in
the lists.** `ns-exists?` built the file path by replacing `.` with `/`
only; Clojure source files munge `-` to `_`, so every hyphenated
namespace (requirements-test, contract-test, chunk-test, host-suite-test,
chunk-node-test — × every host that lists them = 41) was reported "not
in the tree" while its file sat in test/eve/static/. The lists named
only namespaces that exist. Fixed in 3331d6f (munge both), with the
measurement recorded in the fn's docstring. Side effect of the defect:
the coverage block never fired (it guards on ns-exists?), so round 3 is
also the coverage rule's first real run.

**Lists vs the tree at this pin, reconciled:** every listed namespace's
file exists in this tree (requirements/contract/chunk/host-suite under
test/eve/static/, chunk-node-test.cljs likewise). The Wave 1 exclusions'
old reason ("lands with the collation merge") was stale in one
direction: the collate tree at ~/workspace/static-eve/collate NOW
CONTAINS all eleven Wave 1 test files (measured) — **the collate tree is
the right base for those entries**; they stay :excluded here only
because this lane's tree is still based on the x1 tip, and each moves to
:namespaces when the tree is rebased onto collate (the coordinator's
merge call, not this lane's). Reasons updated to say exactly that. The
manifest also names five Wave 2 namespaces whose files exist in NO tree
yet (disk H1, journal H2, dataset-batch H6, chunk-leaf H7, io H8) — now
dispositioned in the lists with that reason.

**The bb round-2 death, re-derived:** not a classpath-form problem in
the runner — eve.host-completion's own typed refusal: the `bb` that ran
was 1.3.190 (J's ~/.local/bin/bb, first on the leg's PATH), Eve requires
≥1.12.214. J's fleet bb is /home/john/bin/bb = 1.13.226 (measured). Legs
from here call bb by absolute path and print its version in-log. (The
fleet's known 1.3.190-first-on-PATH trap; the same shape as the ants-bb
phantom.) Round 3 additionally inherited the successor's classpath
without `src` (bb.edn's `roots` is src + hosts.edn roots + more) —
round 4 carries src first.

## Successor session (2026-11-12)

- Both opening legs died EXIT=128 (bundle race: cloned before h3.bundle
  was staged). Successor re-filed both unchanged in the proven form
  against the bundle staged at 4ea7051 (coordinator verified list-heads):
  `20261112-static-eve-h3-jvm-lists2.json` → h3-jvm-lists.log;
  `20261112-static-eve-h3-bb-suite2.json` → h3-bb-suite.log (bb over the
  hosts.edn :bb roots + test, eve.runner.bb-main, the four bb-suite
  namespaces). Bundle discipline from here: commit → refresh bundle →
  note the tip here → then file.

## Coordinator actions needed

- **Bundle on J:** `bundles/h3.bundle` staged by successor #2 at the
  final tip (see Tips; list-heads verified after scp). Legs printed
  HEAD and were judged against the tip they claimed.
- **Rebase call:** the collate tree is now the right base for the eleven
  Wave 1 suite entries (above). When the coordinator rebases this lane
  onto collate, the lists' :excluded entries for them flip to
  :namespaces and every JVM-class host's suite grows by those eleven.
- **Filing lessons banked by successor #2** (each cost a leg): never
  share a clone dir between legs (EXIT=128 races, twice); bb on J is
  /home/john/bin/bb — PATH bb is 1.3.190 and Eve refuses it; classpath
  compositions follow bb.edn's `roots` (src FIRST, then hosts.edn
  roots, then extras) plus the :jvm-test dep test.check where a suite
  namespace requires it; `jank --version` does not exist (the CLI
  rejects it — a leg died on the probe alone); capture every step's
  output inside the deliverable log or the leg cannot diagnose itself.

## Tips

- test-first (red): 5e8714a
- lists + H3.1 :built: 4ea7051
- list-keeper munging fix + truthful reasons + Wave 2 dispositions: 3331d6f
- lists corrected to measured reality (bb chunk wall, jank/lpy measured
  empties, proven runner strings): 08b570e, 85becad
- nbb chunk wall measured + final board (this commit)

## Collation addendum (2026-11-12, written by the collation, not the lane)

This lane merged into the collated tree at fd3e09e (Wave 2 second
pass). The rebase call above was answered in that merge: the eleven
Wave 1 suites flipped from :excluded to :namespaces for the
JVM-class hosts in the collated tree's host_suite.cljc; basilisk/
jank/CLJS-class exclusions for them now carry the measured host
reasons; disk/journal/io reasons corrected to landed-unjudged;
not-yet-landed renamed not-in-any-suite. host-suite-test on the
collated tree judges the flipped lists green: 1 test, 329
assertions, 0 failures (J, collate-w2-suites.log, tip 7d47715).
