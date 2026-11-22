# AGENTS.md — working in this repo

## The gate

`bb lint` is the gate: clj-kondo (default fail level — warnings
fail), Splint, Carve report, cljfmt check, and dprint check, all with
the fleet-pinned tool versions. Run it before you consider any work
done; fix findings to zero and re-run. The repo's own tests/boot
smoke also stay green — a lint-clean tree is not a compiling tree,
so run the repo gate after every batch of fixes, not only at the
end.

## Editing Clojure code — one pipeline

1. **clj-replace** makes form-level changes (it matches the old form
   ignoring layout; write the new form for content, never style).
2. **clj-paren-repair** (clojure-mcp-light) repairs structure if an
   edit unbalanced parens — then the fleet **`cljfmt fix`** renders
   the format of record. (The repair tool bundles an older cljfmt;
   its internal formatting is never the final word.)
3. **clj-kondo** lints the edited file; the edit is not done until
   kondo is no worse than before it.

Non-form edits (comments, docstrings, single tokens) may be plain
text edits; if they change a file's layout, the format pass still
runs.

## The rules are fleet rules

`.cljfmt.edn`, `.clj-kondo/config.edn`, and `.splint.edn` in this
repo are byte-identical copies of the fleet templates — CI, editors,
and agent tools all read these same files, so system-wide and
edit-local rules cannot drift. Never edit them here to make this
repo pass: a rule change is proposed to the fleet templates and
propagates to every repo. Per-repo exceptions live in the config
file's header, named, with the reason written.

## No cheats

- Warnings fail. Never pass `--fail-level error`, demote a linter
  level, or add an `:exclude` to make the gate pass.
- A suppression (`#_{:clj-kondo/ignore …}`, a Splint disable, a
  Carve keep-list entry) is allowed only with the reason written at
  the site or in the commit — a suppression without a reason is
  reverted.
- Fixes fix the code: delete the unused var, correct the arity,
  rename the shadowed binding. Never hide a finding.
- Tool versions are pinned (clj-kondo 2026.10.26 · cljfmt 0.16.6 ·
  Splint 1.25.0 · clojure-mcp-light 0.2.2); Splint needs babashka
  ≥ 1.12.205 on PATH.
