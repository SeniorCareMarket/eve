# STATUS — syntax/formalization-1 lane, SeniorCareMarket/eve

Base: origin main 665bcf84cc72105f257a831e9366e8dc4eebc153 (ls-remote
verified, local HEAD matches). Branch: syntax/formalization-1.
Public repo: branch work only — no merge, no PR from this lane.

## The gate

Eve's test gate for this lane: `bb test:bb` — the shared (.cljc)
tests on babashka over hosts.edn's :bb roots (test + apps/aegis/test

- apps/network-atoms/test), run with bb 1.13.230. This is the gate
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

bb -cp "$(bb classpath bb):apps/aegis/src:test:apps/aegis/test:apps/network-atoms/test"\
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

- Carve legs to zero, dprint + docs pass, .githooks/pre-commit,
  bb lint exit 0, RETRO.md (incl. the consumer kondo-exports
  proposal), push branch. clj-depend: config in, 0 violations at
  the pin (confirm again at the endgame).

## Successor lane — campaign state at handoff (2026-11-23)

Committed this session (after 7df8e51): defcfn hook;
redefined-var host-face ns ignores (98e12b9); host class-name
exclusion (2e5c43c); eve.set dead vars + renames (c367fb6);
unused-binding rename batch (4 commits) + its correction
(ab7497a — READ IT before touching unused-binding again:
kondo TEXT output drops the finding's lang, and .cljc
unused-binding is per-lang; the batch broke eve.atom's
scalar-leaf? and the gate caught it; 146 renames reverted,
24 re-applied, single-lang renames stand); dprint leg +
pre-commit (3365a4c); docs pass (141f1e2); RETRO.md.

RECOUNT 4 (gate scope): kondo 181E / 1768W / 219I (from
319E / 1872W at recount3, 1455E / 2195W at census). Splint
2363 over 572 files. Carve recount still running at bank
time (990+ report lines in eve-lane-logs/recount/carve2.txt).
clj-depend 0 violations. fmt-check and dprint check green;
bb lint is NOT green — the kondo/splint/carve legs still
have the tail RETRO.md enumerates class by class.

REMAINING WORK = RETRO.md "What remains": unresolved-var
450 (host_completion.clj is the deep one), unused-binding
427 (residue shapes named there), unresolved-symbol 125,
unused-private-var 78, type-mismatch 30, invalid-arity 12,
mechanical warning tail; Splint + Carve campaigns not yet
started past measurement. Push state: branch pushed at
handoff (see the lane's final report); merges to main are
John's word, not the lane's.

## Third lane session (2026-11-23, agent 662d86ab)

Inherited state verified firsthand: branch
syntax/formalization-1 at 8f0a0bc (== origin, clean tree),
STATUS/RETRO/CENSUS read in full. Recount4 EDN re-analyzed:
181E / 1768W / 219I confirmed (invalid-arity is 15 in the
EDN, not 12 as RETRO's summary said — the EDN governs).
Per-class working lists extracted to
eve-lane-logs/lane3/classes/. Carve recount DID finish:
carve2.txt is complete (992 lines, done.txt ALLDONE) — the
"still running" note in the handoff section above is stale.
Campaign order: small error classes first, mechanical
warning classes, unused-private-var, lang-aware
unused-binding, the refer/namespace classes, then the
unresolved-* modeling classes; Splint and Carve after kondo.

Progress (2026-11-23 ~00:50 ET): commits 08ffc9b (small error
classes: syntax + ns-mismatch site ignores, static-field call,
conflicting aliases), 8714216 (private-call closed: 5 defn- →
defn for the -write-*-coll!/eve-array-from-sab-pointer
registration surface + cljs.core/pr-writer site ignore),
a5b3c6f (duplicate-require real fixes + cheshire lib +
f3fix :relocs site ignore; 3 recount4 dup findings were
stale-cache phantoms, proven standalone), da601c5
(redundant-declare: 9 removed, sha-vec + -entries kept with
site ignores — per-lang declares), 457591e (redundant-do ×21,
single-logical-operand ×2). Fresh recount5 banked at
eve-lane-logs/lane3/ (kondo5.edn 170E/1766W/219I at the
post-batch-2 tree, splint5 2363/572 files, carve5 998 lines).
Gate spot sets lane3-gate1 + lane3-gate2 all
baseline-identical. Environment: /root/.m2 had 0-byte stubs
after a VM cycle; restored from /home/hatch/m2repo (durable).

Progress (2026-11-23 ~03:30 ET): dc35c39 (unused-import ×17),
2a8c50c (return-type-hint: 1 JVM fix + 4 .cljd ns-scoped
linter-offs with reasons), 9f4dc24 (redundant-let ×42),
84de259 (unreachable-code ×8, constant-condition ×10,
unused-value ×5, missing-test-assertion ×1), 18932bb
(inline-def ×5, aliased-referred-var ×3, redundant-ignore
×4). CORRECTION banked: gate3's eve.thread-fold-group-test
exit=99 (bb native segfault) is NOT a redundant-let
regression — the control run at the pre-batch commit
2a8c50c segfaults identically under current box conditions
(logs lane3-fg-now vs lane3-fg-control); it is the
pre-existing native-crash family that took
eve.thread-domain-test at baseline. Gate3's other 8
namespaces baseline-identical. /root/.m2/repository is now
a symlink to /home/hatch/m2repo (the wipes recur; the
symlink is a one-command restore).

## Third lane — session 2 bank (2026-11-23, ~10:15 ET)

Since the last bank: redundant-str-call closed (80792c4,
d2a34c9; start.cljc's 36 are Path->string coercions, scoped
off in its ns config with reason); missing-else-branch ×27
closed in the rrb-vector port (5e91ae0, when/when-not/
when-let). RECOUNT 7 (fresh cache, EDN, tools/recount.sh —
NOTE: --output '{:format :edn}' is IGNORED by kondo
2026.10.26; the working form is --config '{:output
{:format :edn}}') read 1943 total: unresolved-var 443,
unused-binding 427, unused-referred-var 284,
unused-namespace 233, unresolved-symbol 125E,
type-mismatch 54E, unresolved-namespace 54,
redefined-var 33, unused-private-var 78. Recount-7
cleanups (82c4959, e03b48e): my do-unwrap created
let-in-let ×17 in the jvm host tests (merged); my
inline-def fix typed xray's fixtures nil via (def x nil)
-> 24 type-mismatches, fixed with declare; the
collection-hash MTA is a kondo quirk (a bare (dissoc! ...)
statement in a deftest reads as missing-test-assertion;
minimal repro in scratch) — the mutation is now folded
into its count assertion; duplicate-require ×7 SOLVED:
they fire only with the full dependency cache (standalone
runs are blind to transitive requires) — the glj probe
re-requires as its bisect method and the coop workers
name their whole cone in one require; site ignores with
reasons. Phantom lesson, third time: a full-scope recount
must start from a cache no other process is touching, or
per-file results go stale (recount7 briefly showed
region.clj imports deleted two batches earlier).
unused-private-var: 21 deleted outright (4ddc512; name
occurs only at its def, no string/keyword mentions),
bi-pos? deleted on both passes + 7 :clj-only-caller defs
site-ignored with reasons (98e0762). Gate5:
collection-hash baseline-identical. Recount 8 running.

## Third lane — session 2 bank 2 (2026-11-23, ~10:35 ET)

unused-private-var CLOSED (78 -> 0 by classes): 30
deleted outright (tranches 1-4 + int-compare), the rest
kept with site reasons in four measured families:
(a) :clj-pass-only callers (tranche 2 pattern), (b) the
chunk handle-helper web + freeze assoc-n chain — callers
in host-conditional arms kondo's passes split, (c) the
stream five, (d) host branches kondo does not model
(:clje MAXL/MINL). CORRECTION banked in commit bbd3c12:
the first chunk deletions (pg-reduce & 4 more) passed
delete-and-relint but the GATE proved pg-reduce's def
was the JVM/bb-loaded one — chunk load failed, gate6
all red; all five restored as reasoned keeps and gate7
re-verified green (map-entry, vec-retire 795 passes).
LESSON: in branch-heavy files delete-and-relint is not
evidence; the gate is. Also solved: the region
unused-import pair belonged to the jvm-nio-lock copy of
eve.region (three files, one namespace, kondo merges
and misattributes); the types.clj aliased-referred-var
info is the same merged-namespace effect at a position
that exists in no copy (info-level, left with this
note). rrb-vector-test: gate7 shows it ERROR
(host-completion sci-concat over a SciType) — CONTROL
at pre-edit commit d2a34c9 files fails byte-identically;
pre-existing under current conditions, not the
missing-else batch. Recount 8: 1865 total (from 2155 at
recount 5). Remaining big classes: unresolved-var 443,
unused-binding 427 (lang-aware protocol only),
unused-referred-var 285, unused-namespace 233,
unresolved-symbol 125E, unresolved-namespace 54,
redefined-var 33, type-mismatch 30E (pre-existing
sites), invalid-arity 15E, protocol classes 16+10.

## Third lane — session 2 bank 3 (2026-11-23, ~11:20 ET)

unused-namespace CLOSED (233 -> 0 by per-file standalone
checks; full recount pending): eve.atom pilot proved
per-spec #_ ignores work inside ns forms. eve-test-main
(69) scoped off in its ns config — its require list IS
the test registry. eve.flat.build (12) + the eve.alpha
cone (13): per-spec ignores (load-for-registration /
consumer surface). The remaining 78 files ran through
tools/uns-batch.bb (boundary-aware alias recount;
verdicts: per-pass -> ignore, cone -> ignore, dead
clojure.* library spec -> delete; ~20 clojure.string/
java.io/edn/data.json specs deleted). Two hand fixes:
place_test's cone block + framework require, and
column_test's cljs.test arm (the driver annotated the
clj arm). Gate9 (6 ns incl. baseline-red dataset at its
5E) arbitrating the deletions now. New tools in
eve-lane-logs/lane3/tools/: recount.sh (EDN via --config),
analyze.bb, extract.bb, delform.bb, upv-del.sh,
uns-fix.bb, uns-batch.bb, whenify.bb + the let tools.
