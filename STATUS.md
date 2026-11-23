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

## Third lane — session 2 bank 4 (2026-11-23, ~12:30 ET)

Closed since bank 3: unused-namespace (recount-9
verified, plus four batch defects repaired — empty
(:require) clauses, an emptied #?(:clj) arm, the
eve-test-main attr map moved after its docstring),
unused-referred-var (285: 17 dead removed, mirror/
per-pass spec annotations), redefined-var (33:
:refer-clojure :exclude declarations; one real dead
duplicate — start.cljc's first display? — deleted),
unresolved-namespace (54: config exclusion for host/
dialect namespaces as a named Eve exception + four
real missing requires), protocol classes (26: Eve
protocol methods on macro-built deftypes annotated at
the carrying form; deliberate partial implementations
named). TWO MECHANISM LESSONS, both measured in
scratch: (1) stacked #_ discards suppress NOTHING —
annotations on one spec must be a single combined
discard (tools/merge-ignores.py repairs blocks);
(2) derived-location protocol findings answer only to
an annotation on the whole deftype form. Recount 9:
1588 total. Remaining: unresolved-var 444,
unused-binding 427, unresolved-symbol 124E,
type-mismatch 30E, invalid-arity 15E, plus the two
by-design info classes (143).

## Third lane — session 2 bank 5 (2026-11-23, ~13:45 ET)

Recount 10: 1267 total (was 1588). Closed since bank 4:
invalid-arity (15 — defcfn :& hook fix, extend-default
and extend-host-vectors hooks, five merged-namespace
call annotations), type-mismatch (30 — mem cluster died
with the extend-default hook; pool cap restructured;
array_test lazy-init scoped at the ns; five per-site
reasons), unresolved-var merged-namespace batch (22
seat files, ns-config exclusions of their own qualified
defs — mechanism miniature-proven: kondo's registry
keeps one file's defs for a shared ns), register-eve-
type! hook (type-id fns). Recount-10 repair batch:
the Arrays inline annotations had commented the import
specs out on their own lines — repaired across 22
files, gate-verified (container-test green at gate11);
deftype/array's duplicated (:require repaired.
Gate: thread-domain IMPROVED past baseline (exit 0,
was 1F). Remaining big classes: unresolved-symbol
126E, unresolved-var residue (~240: squint cljs.core
family, host_completion internals, proc/exit! drivers,
runner api-defs vars), unused-binding 427.

## Third lane — session 2 bank 6 (2026-11-23, ~14:20 ET)

MEASUREMENT CORRECTION (banked per the phantom-defect
doctrine): recounts 10-12 ran clj-kondo with
--parallel, which breaks per-file ns-config handling
for namespaces shared by several seat files — it
manufactured ~240 phantom unresolved-var findings.
The gate (bb.edn lint-kondo) runs serial; recount.sh
no longer uses --parallel and the serial numbers
govern. Also measured: which of a shared namespace's
files loses kondo's registry varies by run mode, so
each seat file's ns-config exclusion unions its own
defs across both analyses; and kondo reads only the
FIRST {:clj-kondo/config} map in an ns form (the .cljd
files carried two — merged). REAL DEFECT found by
kondo and fixed: start.cljc browser-args called
display? before its def — the duplicate def deleted
in the redefined-var batch was load-bearing for
compile order; a (declare display?) now precedes it.

## Third lane — session 2 bank 7 (2026-11-23, ~16:40 ET)

KONDO LEG CLOSED. Recount17 (serial, fresh cache, gate
scope): kondo exit=0 — 0 errors, 0 warnings. Census was
1455E / 2195W / 265I; the 141 remaining findings are all
info: unresolved-excluded-var 82 + unused-excluded-var 58
(the recorded consequence of the central exclusion config —
the excluded names ARE the host faces; account, don't
chase) and aliased-referred-var 1 (the known merged-
namespace ghost). Classes closed since bank 6:
unresolved-var (centralized: per-file ns-config exclusions
moved into the central config — a qualified name inside an
ns-form config itself reads as unresolved-namespace;
433 names with the merged-namespace reason in the header),
unused-binding 445→0 (hook synthetic args underscore-
prefixed; lang-aware renames; :keys removals — audited:
ONE misfire, deftype_guard_test's `error` key, restored
and gate-verified; per-pass discards on the inner forms),
unused-referred-var (four host-face mirror files closed by
file-level ns config — per-spec discards proved unstable
across kondo's spec attribution), type-mismatch (site
reasons + the array_test ns scoping), unresolved-symbol
66→0 (teaching: deftype hook declares <Type>__Flat,
<Type>__from-header, <Type>-type-key — the last emitted by
deftype-proto.sio:590; defdelay lint-as; Dart core names;
with-heap-slab anaphora hook binding sio only when the
body names it; site discards for host core faces on the
pass with no seat). DEAD END banked: :config-in {:ns ...}
exclusions bind nothing in kondo 2026.10.26, standalone or
at gate scope (measured both) — the table was removed.
REAL DEFECTS found and fixed en route: place.cljc's
owner-base binding (a prior lane's rename had broken its
:clj arm; restored to the base-pin shape; provenance:
665bcf84 text vs commit 1ee1c9b) and the deftype_guard
`error` key above. PRE-EXISTING, proven at the pristine
base pin under the lane's own gate driver: eve.flat
.place-test and eve.flat.build-test die at load with
"Unable to resolve symbol: definterface" (the bb/SCI seat
provides no definterface for these isolated runs) — not
lane damage; recorded here as their baseline state under
this driver. OPEN Eve finding for RETRO: eve.atom's $,
validate-storage-model!, dump-block-detail!,
dump-block-stats! are called by test tooling but not
defined at this pin. Remaining: Splint campaign, Carve
campaign, full baseline reproduction, RETRO completion.

## Bank 8 — 2026-11-23 ~17:35 ET (lane 3, batch C redo + repairs)

- KONDO LEG CLOSED at recount 17 (0E/0W); recounts 19-21 tracked the Splint batches' regressions back to zero: recount 21 = 0 errors, 2 warnings (the eve.set/eve.vec containsAll sites, reverted in c0d19df — a confirming recount is owed at the next checkpoint).
- Splint: batches A+B committed (9e2db9d, 1691 warnings); batch C attempt 1 REVERTED WHOLESALE (reader-conditional destruction + same-line column collisions — full story in commit c0d19df's message); batch C redo (let-if/let-when/if-nil-else/if-else-nil) committed as c0d19df under the READER-SKIP + DEFERRED-SAME-LINE guards now in tools/splint-fix.bb (BOTH loops — the sugg loop was the unguarded one). Gate cone green at baseline counts after the redo.
- Tool lesson banked: suggestion mode is only safe with (1) the reader-conditional guard, (2) one edit per row per pass with fresh positions per iteration, (3) a kondo recount after every rule group. Pattern-verified transforms remain preferred.
- REMAINING: splint restructure families group 2 (if-not-both, if-same-truthy, when-not-call, into-literal, new-object, prefer-condp, assoc-fn, when-do, useless-do, redundant-call, assoc-assoc, mapcat-concat-map, prefer-vary-meta, multiply-by-one/zero, body-unquote-splicing, empty-loop-in-fn, loop-empty-when, redundant-nested-call, redundant-let, no-op-assignment) under the guarded iterate discipline; READER-SKIP hand dispositions (flat.cljc delegate, image, chunk, place sites); the giants (prefer-method-values ~847, lisp-case 392, catch-throwable 49, identical-branches 47, conversion-functions 9, misc naming); then the Carve campaign; then bb lint full pass + baseline reproduction + RETRO.

## Bank 9 — 2026-11-23 ~19:05 ET (lane 3, splint batch D)

- Splint 1478 at splint9 (census 2532). Batch D committed + pushed. Kondo recount 24: 0E/0W (infos only) — the kondo leg remains CLOSED. Gate cone green at baseline counts (gate29).
- Suggestion-mode guard set is now complete, each guard measured from a real corruption: READER-SKIP (#? in/around the node), TAG-SKIP (tagged literals — Splint prints them as splint/tagged-literal), DISCARD/COMMENT-SKIP (Splint's printer drops inner #_ and ; content), DEFERRED-SAME-LINE (one edit per row per pass), fresh positions per iteration, kondo recount after every group.
- Remaining splint: prefer-method-values 847 (per-file exclusion precedent from the valuescript lane where the receiver class is not statically known), naming/lisp-case 392 (per-site verdicts), catch-throwable 49, identical-branches 47, restructure residues ~80 (guard-skips for hand disposition), conversion-functions 9 + naming tail. Then Carve, then the finish line.
- Environment: a VM cycle wiped /root again mid-batch — /root/.m2/repository symlink and /root/.ssh/config restored; the splint driver now fails loud when splint output lacks its completion line (a deps failure once read as zero sites).

## Bank 10 — 2026-11-23 ~21:10 ET (lane 3, splint at 252 and falling)

- Splint trajectory: census 2532 -> 2268 (lane tip) -> 1478 (batch D) -> 252 (splint10) -> naming cluster committed (tip pushed). Kondo recount 35: 0E/0W — leg remains CLOSED. Gate cone green at gate31.
- prefer-method-values (847) closed by per-file exception in .splint.edn (all suggestions carry the CLASS/ placeholder; receivers are host-seat values). lisp-case (392) closed by renames: 154 file-local, 85 multi-seat, 143 cross-referenced + generated-family follow-ups, 17 protocol/host-mandated keeps with disables.
- Remaining splint (~200): catch-throwable 49, identical-branches 47, restructure residues ~85 (guard-skips for hand disposition), prefer-clj-string 11, fn-wrapper 5, redundant-nested-call 5, misc singles. Then Carve, then the finish line (bb lint full, fmt x2, baseline reproduction, RETRO).

## Bank 11 — 2026-11-23 ~22:40 ET (lane 3, SPLINT LEG CLOSED)

- SPLINT LEG CLOSED: census 2532 -> 66 (splint12) -> 0 (splint14 census: 575 files, 0 style warnings). Final commits: catch-throwable 49 disabled by taxonomy (953c6f5), identical-branches 47 disabled (d13cfb9), restructure residues via the guarded driver (cb10990, 5670fb4), hand dispositions for the last 66 (50f8725, pushed + read back).
- Kondo recount 41 at the same tree: 0 errors / 0 warnings; 142 info = the accounted exclusion residue (82 unresolved-excluded-var + 58 unused-excluded-var + aliased-referred-var 1 + redundant-ignore 1). Gate cone32: all six namespaces green at baseline counts.
- New tool: tools/formfix.py — extent-precise form surgery (top-level span edits on the flagged form's own text; comments, discards and reader-conditionals preserved verbatim). Built after two textual paren-surgery failures on the codec seats (paren removal hit pre-existing when-let forms); the span approach closed the codec decode ladders in both seats with per-file 0/0 on both linters.
- Disable-placement lessons (measured): a splint discard inside a #? arm does not suppress a finding on the arm's form, and one before the #? crashes splint's reader on repro_test (splint/unknown-error) — the working placement there is the enclosing call; an ns-form alias finding is suppressed only by a discard before the (:require clause (the eve.edn precedent), not inside the spec.
- OPEN FINDING (recorded, deliberately not touched): eve.net.bignum modinv-limbs contains ((when (seq k) (sub n k)) n k) — the limb-difference vector invoked as a two-argument function; if that branch is ever reached with non-empty k it throws ArityException. Provenance: adapters/crypto/portable/src/eve/net/bignum.cljc at 50f8725; the lane's transforms preserved the shape exactly. Needs a bignum owner's verdict.
- Remaining: Carve campaign (998 report lines at carve5), then the finish line (bb lint full pass, fmt x2, full baseline reproduction, RETRO completion).

## Bank 12 — 2026-11-23 ~late ET (lane 3, CARVE CLOSED, LINT-DEPEND REPAIRED, REPRODUCTION + CORRUPTION REPAIRS)

- CARVE LEG CLOSED: 1194 reports / 163 files adjudicated, 0 deleted, `bb carve-report` exit 0. `.carve/ignore` (9110a05) carries all 1194 in 11 mechanism groups with reasons (fleet-kondo-reached 44, cross-scope reach 40, register-eve-type generated family 484, type predicates 100, deftype constructors 18, cljs protocol vars 43, eve.alpha surface 22, adapter seat surfaces 208, app surfaces 22, test/runner/task surface 65, namespace surface 148). Zero deletions deliberately: the one deletable-looking class (eve.flat.chunk private helpers) carries definition-site annotations recording host-arm callers kondo's branch model cannot see (the gate6 blindness). RETRO recommends a runtime-tracing harvest over static deletion. Mirrors the accepted cohost precedent.
- LINT-DEPEND WAS VACUOUSLY GREEN ALL CAMPAIGN; repaired (3dff0b6). Two defects: (1) the bb.edn task could not run under bb 1.13.230 (SCI analyzes the task body before :extra-deps loads, so the qualified clj-depend.api/analyze call failed analysis — prior lanes measured via `bb -Sdeps`, never through the task); (2) run by hand it still reported nothing — clj-depend 0.11.1 filters by Path.startsWith and scanned files carry a ./ prefix from the relative project-root, so :namespaces-to-be-analyzed was empty on every tree. Proven on fixtures (relative → result-code 0 on a violating tree; canonical absolute → 1 with the violation). Fix: requiring-resolve after require, canonical absolute root + :files, exit 1 on any non-zero :result-code. Run for real it reported 7 violations, all core→eve.net.*: adjudicated as a mis-modeled layer config, not a code defect — `.clj-depend/config.edn` gained a :net layer between :core and :apps with the evidence in its header. `bb lint-depend` now exits 0 for real. Every "clj-depend 0 violations" measurement before this fix, the census included, was the vacuous pass.
- `bb lint` EXIT 0 at 909ba8f (all five legs; fmt idempotent — second `bb fmt` a no-op).
- BASELINE REPRODUCTION (54 namespaces, final1+final2) diverged on FIVE namespaces at 909ba8f — all traced to SPLINT-SUGGESTION CORRUPTION and repaired: dataset 29E (chunk write-node! cond scrambled by batch C — its :else body became the first test, write-batch! its result), tensor 19E + array-hosts truncated (same scramble), host-parity dead at load (splint-tail def-fn made register-eve-type's template a two-name defn SCI rejects), thread-fold 6F/7E (batch D stripped comp from =>>'s syntax-quote templates). Repairs: 8ed085a (chunk cond, bignum, http regex, into-[] surface, ^bytes hints), 2e0b8be (JS-seat cmp-bytes ladder, two docstrings), fb0a098 (sorted print graft), 040eed1+b6902f6+73c07fb (record template + disable spelling + fmt), 6b4ac37 (thread templates). Each diverged namespace re-run at the repaired tip matches its baseline verdict EXACTLY (dataset 23/98 baseline errors only; tensor 15/58 1F/2E; array-hosts 3/50 17E; host-parity 1/259 green; thread-fold 7/332 0/0). All other namespaces match baseline verdict-for-verdict (aegis set vs baseline2 logs included); the segfault family (thread-domain/net/fold-group) and the two timeouts are no-verdict at both pins; rrb-vector fails identically at both; eve.deftype.type-id-test crashed at baseline and is green at the tip.
- CORRECTION to bank 11's open finding: the bignum modinv-limbs shape was NOT pre-existing — the form-tree audit proved batch C installed it (bindings rotated into the body); the lane's later transforms preserved it, which is how it was misread as original. Repaired in 8ed085a. The remaining open findings stand as recorded in RETRO (eve.atom's four missing defs; the :net layer question for the Eve owners; the hosts.edn aegis-root wiring gap).
- AUDIT METHOD banked (tools in eve-lane-logs/lane3/tools/): formdiff.py / batchc_audit.py / audit2.py — parse both pins to form trees, adjudicate every delta that is not a sanctioned shape of its batch, plus a signature sweep (duplicated subtrees, mutated string literals, list↔atom changes) over all seven splint batches. Every sweep suspect was adjudicated; the corruption sites are enumerated in RETRO.
- Remaining: final `bb lint` at the repaired tip + push + this bank's commit. NO merge, NO PR (John's word required).
