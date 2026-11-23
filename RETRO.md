# Retro — Eve syntax/formalization-1 (Phase 2)

State at this writing (2026-11-23, successor lane): the branch
carries the fleet configs, the full kondo teaching (five hooks),
the format pass, the docs pass, the pre-commit hook, and the
first fix-campaign classes. It does NOT yet carry a green
`bb lint`: the remaining findings are counted below, class by
class, with the working method for each. Nothing in this file
is a claim the gate passes.

## Numbers

| Measure          | Census (pin)   | Now                                     |
| ---------------- | -------------- | --------------------------------------- |
| kondo errors     | 1455           | 181                                     |
| kondo warnings   | 2195           | 1768                                    |
| kondo info       | 265            | 219                                     |
| Splint warnings  | 2532           | 2363 (572 files)                        |
| Carve candidates | 1206           | ~1000 (recount running at this writing) |
| cljfmt           | 37/589 claimed | clean (see the template defect below)   |
| clj-depend       | 0 violations   | 0 violations                            |
| dprint           | no config      | check exit 0                            |

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

`.clj-depend/config.edn` layers (host-seam / core / apps) were
derived from the tree by the prior lane and report 0
violations at the pin and at every recount since. Final state:
config in, wired as the lint-depend leg of `bb lint`, no
violations. Nothing further owed.

## What remains (the campaign tail)

Kondo, by class (recount4 EDN is the working list):

- unresolved-var 450 — the big one. Concentration:
  adapters/completion/bb host_completion.clj (133, the
  intern/presentation machinery — needs its own modeling
  session), adapters/coll/squint core.cljs (31, protocol
  method vars), the region/thread/types adapter faces
  (20-27 each). File-by-file adjudication.
- unused-binding 427 — the residue after the rename batch:
  :keys-destructuring entries (fix = remove the entry) and
  the per-lang .cljc sites (fix per the EDN-lang rule above).
- unresolved-symbol 125 — capstone FFI-adjacent residue,
  per-site host names not in the excluded family, the
  jvm_*_test `sio` locals, `byte-array` refers in flat/.
- unused-private-var 78 — delete-or-keep per site with a
  repo-wide reference grep (the eve.set pair was deleted
  this way).
- type-mismatch 30, invalid-arity 12 — per-site; several
  arity findings are protocol methods called through the
  sio__ convention and need the same care as the hooks.
- The long tail of mechanical warnings (redundant-let 42,
  redundant-str-call 41 info, redefined-var singles ~33,
  duplicate-require 13, unused-import 17,
  non-arg-vec-return-type-hint 19, missing-else-branch 27).

Splint 2363 and Carve ~1000 are essentially untouched; both
were censused on the old scope and need a gate-scope census
pass before fixing (Splint's count above is that measure).

Gate: the per-namespace baseline is the reference (51/54
verdicts; reds and the thread-domain segfault are pre-existing
at the pin — CENSUS.md). Every batch so far has been checked
against it; the format pass and the corrected rename batch
reproduce it exactly.

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
