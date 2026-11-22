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
