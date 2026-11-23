# STATUS — syntax/formalization-1 lane, SeniorCareMarket/eve

Base: origin main 665bcf84cc72105f257a831e9366e8dc4eebc153 (ls-remote
verified, local HEAD matches). Branch: syntax/formalization-1.
Public repo: branch work only — no merge, no PR from this lane.

## The gate

Eve's test gate for this lane: `bb test:bb` — the shared (.cljc)
tests on babashka over hosts.edn's :bb roots (test + apps/aegis/test
+ apps/network-atoms/test), run with bb 1.13.230. This is the gate
the repo's own bb.edn defines for the shared suite and the one this
box can run end to end; the JVM suite (clojure -M:jvm-test) is not
this lane's gate and no partial run will be called the gate.

Lint gate: `bb lint` (fleet): clj-kondo default fail level, Splint
1.25.0, Carve report, cljfmt check, clj-depend, dprint check.

## Pre-existing defect, re-derived at this pin (not this lane's fix)

`bb test:bb` cannot run at the base pin: hosts.edn has no
apps/aegis/src root (grep: 0 occurrences) while
test/eve/runner/bb_main.clj's shared-tests list eve.aegis.*
namespaces whose sources live there. Fresh repro at 665bcf84,
bb 1.13.230, pristine tree: the run dies loading
eve.aegis.rsabssa-test — FileNotFoundException, could not locate
eve/aegis/rsabssa.cljc on classpath. So the lane's gate is the
test:bb suite run through the runner with the one missing root
composed into the classpath, verbatim:

  bb -cp "$(bb classpath bb):apps/aegis/src:test:apps/aegis/test:apps/network-atoms/test" \
     -m eve.runner.bb-main test

Same namespaces, same runner; the wiring gap itself is Eve's,
already on the release-line backlog, and is reported in CENSUS.md
with this provenance. It is not fixed by this lane.

Lint scope (decided up front, before counts): src, adapters, apps,
test, contract-test, test-e2e, net-local, capstone. Tooling/prose
dirs (bench, dev, scripts, examples, impls, doc, docs) are outside
the lint gate's scope — they are not the shipped library or its
tests; the format of record still applies to anything edited here.

## Log

- Step 0: clone re-verified (HEAD == origin main, clean tree).
  A transient disk-quota block on /home/hatch (writes refused with
  EDQUOT despite 27G free volume-wide) delayed the start; it
  cleared on its own. Baseline gate launched on the pristine tree
  (composed command above), log:
  ~/workspace/syntax-formalization/eve-lane-logs/baseline-gate.log
- Step 1: fleet templates installed — .cljfmt.edn + .splint.edn
  byte-identical copies (cljfmt re-copied after the fleet
  correction: :sort-ns-references? false — sorting requires
  reorders load order, semantics not formatting; media retro
  item 1), AGENTS.md verbatim, bb fleet tasks merged into bb.edn
  over Eve's real roots, .clj-kondo/config.edn = fleet template +
  the named Eve :lint-as exception, .clj-depend/config.edn with
  the seam/core/apps layers derived from the tree.
  clj-depend at the base pin: 0 violations (measured, bb with
  com.fabiodomingues/clj-depend 0.11.1, seeded from Clojars).
  Kondo exports: src/clj-kondo.exports/seniorcaremarket/eve/
  config.edn — mechanism proven with a scratch consumer:
  `clj-kondo --lint <eve src> --copy-configs --dependencies`
  copied it to the consumer's .clj-kondo/imports/seniorcaremarket/
  eve/ ("Configs copied"), activated via :config-paths.
  .gitignore: `.clj-kondo/` narrowed to `.clj-kondo/.cache/` so
  the fleet config is committable.
- Step 2 (census) measured at 86fbaa2, tools: clj-kondo
  2026.10.26, Splint 1.25.0, cljfmt 0.16.6, bb 1.13.230:
  kondo 1455 errors / 2195 warnings / 265 info over 589 files;
  Splint 2532 warnings; cljfmt 37 of 589 files unformatted;
  clj-depend 0 violations (step 1). Carve's first run crashed
  (its bundled kondo pod quit under load) — retried in two halves.
  Full breakdown: CENSUS.md.
- Baseline gate: the first full run died silently at 26 of 57
  namespaces (process gone, no summary; box at load 30+ on 8 GB —
  consistent with an OOM kill, unproven). Partial baseline banked
  in the log: eve.type-io-test FAIL/ERRORs at the pin (tag-reader
  rows) are pre-existing on bb. The baseline will be re-run in
  namespace chunks before the format pass.
- Baseline, second attempt (one bb process per namespace — a
  19-namespace chunk was OOM-killed at exit 137, measured): the
  per-namespace baseline is the lane's gate reference. Reds are
  compared per namespace after every batch; a red that exists at
  baseline is pre-existing, a new one is the batch's. In progress.
- Kondo hook mechanics proven in scratch (hooktest2): an
  analyze-call hook for the deftype family rewrites Eve deftype
  to clojure.core/deftype with sio__/offset__ prepended to the
  fields, the {:print} options map re-homed in a let binding the
  fields, and protocol groups translated per pass from
  eve.deftype-proto.proto-map's own tables. Two traps measured:
  (1) :meta field nodes must be rebuilt as plain tokens or
  kondo's parser dies ("Key must be integer"); (2) kondo's cache
  does not key on hook code — clear .clj-kondo/.cache after any
  hook edit or stale-hook results masquerade as hook bugs.
  api/coerce of a whole sexpr is NOT recognized as deftype;
  synthetic nodes preserving the original subnodes are.

## Successor lane session (2026-11-22 evening, agent 2fd20618)

Inherited state verified firsthand (branch, commits 86fbaa2 /
9a5c57a / 88255d4, dirty STATUS.md committed as 46fc194).

BASELINE GATE — COMPLETE. Per-namespace (one bb process per ns,
composed classpath per the hosts.edn wiring gap): 51 of 54
namespaces have verdicts across baseline/ + baseline2/ +
baseline3/ logs. Pre-existing reds at the pin: eve.type-io-test
(2F/6E), eve.array-hosts-test (17E), eve.dataset-test (5E),
eve.tensor-test (1F/2E), eve.edn-test (1F), eve.read-region-test
(1F), eve.bb-logical-protocol-test (4E), eve.thread-host-test
(1F), eve.thread-fold-group-test (1E). eve.thread-domain-test
SEGFAULTS the bb native image (3 attempts; memory-map dump in
baseline3 log) — pre-existing at the pin; this lane's changes
are kondo-only and bb never reads them. eve.aegis.rsabssa-test
and eve.net.crypto-fast-path-test exceed the 600 s/ns timeout
on this loaded box (no verdict). A duplicate baseline driver
left by the dead lane was found and killed (it was the
contention source behind the exit=99 kills).

KONDO TEACHING — commits 9a8906a (deftype hook), 3c45849
(api-defs hook), 79b92ab (flat-handles + eve.host/field hooks),
each mirrored into src/clj-kondo.exports/. Recounts (full
scope): census 1455E/2195W/265I -> deftype hook 711E/2225W ->
api-defs hook 711E/2112W/225I. Details in CENSUS.md.

FLEET TEMPLATE DEFECT FOUND + FIXED (coordinator: propagate):
templates/cljfmt.edn carried `:indents {}` — in the cljfmt
0.16.6 standalone loader an :indents key REPLACES the built-in
indent table instead of merging, so every default rule was
silently dropped (defn/ns bodies aligned under first args;
measured 2089 vs 111 diff lines on one file). Fixed in the
templates with the reason in the header; propagated here as
9e19e0f. CONSEQUENCE: trees formatted while `:indents {}` was
in force (Phase 1 narcis + cohost-media-example, and any other
lane that ran cljfmt fix) carry the alignment style and need
one `cljfmt fix` after the corrected template propagates, or
their fmt-check fails. The census's "37/589 unformatted" was
measured without the correction in force — superseded.

GATE SCOPE — 38fe8c6: contract-test enters the lint tasks as
contract-test/drivers only (vendored lib/lib-ds/lib-ds-deps
excepted per CENSUS.md; drivers stay in scope).

FORMAT PASS — 94ba995: cljfmt fix over the gate scope, 213
files. Verified: cljfmt check exit 0; a second fix changed 0
files (idempotent); gate spot-check (portable-surface,
map-entry, edn-test, associative-transient) identical to
baseline, edn-test's pre-existing 1F unchanged.

REMAINING: fix campaign (recount on gate scope first), Splint
+ Carve legs to zero, dprint + docs pass, .githooks/pre-commit,
bb lint exit 0, RETRO.md (incl. the consumer kondo-exports
proposal), push branch. clj-depend: config in, 0 violations at
the pin (confirm again at the endgame).
