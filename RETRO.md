# Retro — Eve syntax/formalization-1 (Phase 2)

State at this writing (2026-11-23, third lane, campaign
complete): `bb lint` exits 0 at the branch tip — every leg
green, each one re-derived before it was believed (see the
clj-depend section: one leg's green was vacuous for the whole
campaign and is now really green). The per-namespace baseline
reproduces with no new reds. The accounting is below.

## Numbers

| Measure          | Census (pin)   | Final                                             |
| ---------------- | -------------- | ------------------------------------------------- |
| kondo errors     | 1455           | 0                                                 |
| kondo warnings   | 2195           | 0                                                 |
| kondo info       | 265            | 142 (the accounted exclusion residue)             |
| Splint warnings  | 2532           | 0 (575 files)                                     |
| Carve candidates | 1206           | 1194 reported, all adjudicated, 0 deleted         |
| cljfmt           | 37/589 claimed | clean; `bb fmt` twice = second run no-op          |
| clj-depend       | 0 violations   | 0 violations — first REAL measurement (see below) |
| dprint           | no config      | check exit 0                                      |

Kondo counts are on the gate scope (src adapters apps test
contract-test/drivers test-e2e net-local capstone); the census
scope additionally included the vendored contract-test trees,
which the gate now excepts by name (CENSUS.md has the decision
and the accounting).

## What the lane taught the fleet

1. **The fleet cljfmt template was broken.** `:indents {}`
   in templates/cljfmt.edn replaces cljfmt 0.16.6's built-in
   indent table in the standalone loader instead of merging
   with it, silently dropping every default rule (defn/ns
   bodies align under their first argument). Measured: 2089
   vs 111 diff lines on one file. Fixed in the templates with
   the reason in the header and propagated here. **Every repo
   that ran a format pass under the old template (Phase 1:
   narcis, cohost-media-example) carries the alignment style
   and needs one `cljfmt fix` after the corrected template
   propagates, or its fmt-check fails.** The Eve census's
   "37/589 unformatted" was measured without the correction
   in force and is superseded.

2. **Kondo unused-binding in .cljc is per-lang.** A binding
   unused on the :cljs/:jank pass may be used on :clj. A
   rename tool driven by kondo's TEXT output (which drops the
   finding's lang) renamed eve.atom/scalar-leaf?'s `v` and
   broke the :clj branch; the gate spot-check caught it at
   load time. The correction is committed with the full story.
   Rule for any successor tooling: use the EDN output, group
   findings by position, and rename only when the position
   carries a finding for EVERY lang the file supports.

3. **Eve's macro surface is teachable, hook by hook.** Five
   analyze-call hooks now model what :lint-as cannot: the
   deftype family (per-pass protocol-group translation from
   proto_map.cljc's own tables), api-defs (eve.alpha/eve.thread
   generated APIs), defseqhandle/defmaphandle (macros defined
   inside a #?@ splice), eve.host/field (field name as data),
   and jolt.ffi/defcfn (arity from the type vector). Each hook
   is small, commented with the mechanism it models, and
   mirrored into the export (below). Two traps are recorded in
   the deftype hook's history: synthetic nodes must preserve
   original subnodes (api/coerce of a whole sexpr is not
   recognized), and kondo's cache does not key on hook code —
   clear .clj-kondo/.cache after every hook edit.

## The consumer kondo-exports answer (owned question)

How a consumer repo (narcis, eve.db.ds, any Eve consumer)
inherits Eve's macro teaching:

- Eve ships `src/clj-kondo.exports/seniorcaremarket/eve/`:
  a config.edn carrying the :lint-as entries, the :hooks
  registrations, and the sio__/offset__ exclusion, plus the
  hook sources under `hooks/` beside it. The local
  `.clj-kondo/config.edn` is the same content plus Eve-only
  additions (the host class-name exclusion, which is
  deliberately NOT exported — it models Eve's own sources,
  and a consumer's stray Throwable should still warn).
- The consumer runs
  `clj-kondo --lint <src> --copy-configs --dependencies`
  once; kondo copies the export into
  `.clj-kondo/imports/seniorcaremarket/eve/` (config AND hook
  files — verified firsthand in a scratch consumer: the
  deftype hook arrived and fired), and activates it with
  `:config-paths ["imports/seniorcaremarket/eve"]` in their
  own config.
- With that, a consumer's eve.alpha/deftype forms,
  api-defs-generated names (e/atom, t/submit), FFI defs, and
  field accesses lint as they do inside Eve. Narcis and
  eve.db.ds each need exactly those two steps; no per-repo
  hook writing. (eve.db.ds's own lane proved the same
  mechanism from the other side: its local hooks for its own
  macros were designed against this pattern.)
- Maintenance law: the export and the local config are kept
  in sync by hand in the same commit (every hook commit on
  this branch touched both); a drift check (diff the two
  :hooks maps) belongs in the release smoke.

## clj-depend (owned question)

The leg was VACUOUSLY GREEN from its first run until the
third lane's finish-line check — a pass claim that died on
re-derivation, in two stacked defects:

1. The bb.edn task never ran under bb 1.13.230: SCI analyzes
   the task body before the task's `:extra-deps` require is
   evaluated, so the qualified `clj-depend.api/analyze` call
   fails analysis. Every earlier "0 violations" line (the
   census included) came from running the analyzer some other
   way — or from the second defect.
2. Run by hand with the task's own arguments, the analyzer
   still reported nothing: clj-depend filters the namespaces
   to analyze by path prefix (`Path.startsWith`), and the
   `./`-prefixed paths produced by a relative `:project-root`
   match no `:files` entry — `:namespaces-to-be-analyzed`
   comes back empty and every tree "passes". Proven on a
   two-namespace fixture: relative paths → "No violations
   found" on a violating tree; canonical absolute paths →
   the violation, result-code 1.

The repaired leg (bb.edn: `requiring-resolve`, canonical
paths, exit on any non-zero result-code) reported 7 real
violations on this tree, all core→`eve.net.*`. Adjudication:
the layer model in `.clj-depend/config.edn` was wrong, not
the code's intent — eve.net.bytes/json/u64 are defined under
src/ (core), and the network-atoms namespaces are a subsystem
the core builds on (eve.thread.net is the thread layer's
network rung over eve.net.atom/platform/run/wire;
eve.keyring requires eve.net.crypto). The config now carries
a `:net` layer between `:core` and `:apps` with the evidence
in its header, and the leg passes for real. OPEN QUESTION
for the Eve owners: whether eve.thread.net and eve.keyring
should instead live above the net layer — the config models
the tree as it stands; it does not bless the shape.

Final state: config corrected in, leg wired and non-vacuous,
0 violations measured (namespaces-to-be-analyzed non-empty —
the fixture control is the proof the measurement is real).

## Completion record (third lane)

All five legs closed, in the order they fell:

- **Kondo** (recount 41): 0 errors / 0 warnings. The 142
  remaining info findings are the accounted consequence of
  the central unresolved-var exclusion (82 unresolved-
  excluded-var + 58 unused-excluded-var) plus one
  aliased-referred-var ghost and one redundant-ignore.
  Classes were closed by teaching first (hooks, lint-as,
  central config), then per-site fixes; the lang-aware
  unused-binding rule (EDN output, rename only where every
  lang pass flags the position) held after the ab7497a scar.
- **Splint**: 2532 → 0 (575 files). Pattern-verified
  transforms and the guarded suggestion driver did the mass;
  prefer-method-values (847) closed by per-file exception
  (every suggestion carries the literal `CLASS/` placeholder —
  the receiver's class is a host-seat fact); lisp-case (392)
  closed by renames; catch-throwable (49) and
  identical-branches (47) disabled by written taxonomy; the
  last 66 by hand, with a new tool (formfix.py — extent-
  precise span surgery on the flagged form's own text) built
  after textual paren surgery damaged the codec seats twice.
- **Carve**: 1194 reports, all adjudicated into
  `.carve/ignore` in 11 mechanism groups (fleet-reached 44,
  cross-scope reach 40, type-generated 484, type predicates
  100, constructors 18, protocol vars 43, eve.alpha surface
  22, seat surfaces 208, app surfaces 22, test/runner/task
  surface 65, namespace surface 148). Verdicts were
  re-derived against the fleet kondo analysis plus an
  alias-resolved reach index over the whole repo. ZERO
  deletions: the one class that looked deletable on static
  evidence (private helpers in eve.flat.chunk) carries
  definition-site annotations recording host-arm callers
  that kondo's reader-conditional branch model cannot see —
  the gate6 blindness, again. RECOMMENDATION: a dead-code
  harvest in this codebase needs runtime tracing (load every
  namespace, exercise the suites, record var touches), not
  static analysis; carve's bundled 2023 kondo is the weakest
  of the three analyzers here and its report should stay
  advisory.
- **fmt / docs**: `bb fmt` twice, second run a no-op;
  dprint check green.

CORRECTION to an earlier draft of this section: eve.net.bignum
`modinv-limbs` was recorded here as a pre-existing defect
(`((when (seq k) (sub n k)) n k)` — a vector invoked as a
function). It was not pre-existing: the form-tree audit (below)
proved the shape was installed by this campaign's own splint
batch C, which rotated the let's bindings into its body. The
lane's later transforms preserved the corrupted shape exactly,
which is how it came to be misread as original code. Repaired
in 8ed085a; the branch now reads `(when (seq k) (sub n k))`
over the intact bindings, as at the base pin.

Open findings recorded, deliberately not fixed by this lane:

- **eve.atom's `$`, `validate-storage-model!`,
  `dump-block-detail!`, `dump-block-stats!`** are called by
  test tooling but not defined in eve.atom at this pin
  (grep-verified); they sit in the central kondo exclusion.
- **The :net layer question** (above): eve.thread.net /
  eve.keyring in the core depending on the net subsystem.
- **hosts.edn has no `apps/aegis/src` root** while the test
  runner lists eve.aegis.* tests — a pre-existing wiring gap
  at the pin; the baseline gate driver composes the
  classpath around it.

Gate: the per-namespace baseline is the reference (51/54
verdicts at the pin; the reds, the two timeouts, the
place/build load wall and the thread-domain segfault are
pre-existing — CENSUS.md). The first reproduction at the
pre-repair tip (909ba8f) diverged on five namespaces —
eve.dataset-test (5E → 29E), eve.tensor-test (2E → 19E),
eve.array-hosts-test (50 assertions → 26),
eve.host-parity-test (green → dead at load) and
eve.thread-fold-test (green → 6F/7E). All five were
traced to splint-suggestion corruption (next section),
repaired in 8ed085a, 2e0b8be, fb0a098, 040eed1, b6902f6
and 6b4ac37, and each namespace was re-run at the repaired
tip and matches its baseline verdict exactly (dataset
23/98 with only the baseline dtypes errors; tensor 15/58,
1F/2E; array-hosts 3/50, 17E; host-parity 1/259 green;
thread-fold 7/332, 0F/0E). Every other reproduced
namespace matches the baseline verdict for verdict,
including the aegis set (baseline2 logs) and the
no-verdict timeouts; the thread segfault family
(thread-domain, thread-net, thread-fold-group) crashes
the bb native image at the base pin identically — no
verdict at either pin. One improvement: eve.deftype.
type-id-test crashed the runner at the baseline pin and
is green at the tip.

## Suggestion corruption — the campaign's one real defect class

Splint's printed suggestions are not faithful renderings of
the forms they replace. In a minority of cases the printed
suggestion had absorbed a neighbouring form, rotated a
form's children, or re-escaped string content, and the
batch drivers installed the text verbatim. Kondo stayed
0/0 throughout — every corrupted form is well-formed and
type-plausible — and the per-batch gate cone did not cover
the affected seats, so the damage survived until the final
full reproduction. Sites found and repaired:

- eve.flat.chunk `write-node!` (batch C): the batch-row
  cond's clauses scrambled — the :else body became the
  first test, write-batch! its result, a duplicate else
  remained. Broke dataset / tensor / array-hosts.
- eve.thread `=>>` / `with-pool` templates (batch D):
  body-unquote-splicing and redundant-call read `~@forms`
  as a single argument and stripped the `comp` out of
  three syntax-quote sites (seq-run, par-run's :xf,
  into-chunk), so =>> stopped composing transducers;
  with-pool's template gained a spurious let-wrap.
  Restored verbatim; the re-firing rules are disabled at
  the sites with the mechanism as the reason. Broke
  thread-fold.
- eve.record `register-eve-type` template (splint tail):
  def-fn produced `(defn <meta-name> <fn-name> [args] …)`,
  a two-name defn SCI's expansion rejects; host-parity
  died at load. Restored with a style/def-fn site disable.
- eve.flat_io (JS seat) `cmp-bytes` (batch D): if-not-both
  ate the ladder's `1` branch and grafted a duplicate
  comparison; key order wrong whenever byte 1 exceeded e1.
- eve.sorted SortedMap print method (batches A+B): the
  SortedSet branch's join grafted over `(-sk n)`.
- eve.net.bignum `modinv-limbs` (batch C): bindings
  rotated into the body (see the correction above).
- eve.http-server (Jolt seat): the header-split regex's
  backslashes doubled by the printer — CRLF split broken;
  two docstrings in the Node/browser seats took the same
  escaping damage and were restored from the base pin.

Method that found them: bisect the failing namespace
across batch commits in worktrees, then a form-tree
differ over the culprit batch (parse both pins, compare
trees, adjudicate every delta that is not one of the
batch's sanctioned shapes), then a signature sweep
(duplicated subtrees, mutated string literals, list↔atom
type changes) over all seven splint batches. Every other
sweep suspect was adjudicated as a sanctioned transform or
a pairing artifact of one. Lesson for the next campaign:
a suggestion is a claim, not a patch — install transforms
by structure (parse, rewrite the tree, re-emit), never by
printed text; and a gate cone only proves the namespaces
it runs.

## Method notes for the successor

- One bb process per test namespace; the composed classpath
  in the lane record (hosts.edn lacks the aegis root). Heavy
  legs one at a time; check /proc/loadavg first. This box
  lies about parallel test runs (exit 99/124 are contention
  artifacts until proven otherwise — except thread-domain,
  which segfaults deterministically).
- After a service/VM restart, /root/.m2 loses artifacts:
  re-sync from /home/hatch/m2repo (rsync --ignore-existing
  after deleting 0-byte stubs) before diagnosing tool
  failures. repo1.maven.org resets through the proxy; the
  durable repo is the fix.
- Commit per class, gate per batch, bank STATUS.md as you go.
