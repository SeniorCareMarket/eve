# Lane SISTERFIX — the deftype sister registration breaks CLJS load

Branch `claude/static-sister-load-fix` off the collated tip 4fb369f.
Defect (COLLATION-REPORT third pass): on the collated tree the Node
image suite dies at load — "Cannot read properties of undefined
(reading 'sister')" at eve.dataset.js:408, zero tests run — while
every JVM suite is green. H4's own tip (pre-Sister macros) runs the
same suite fine.

## Step 1 — re-derivation at the current pin: CONFIRMED

Filed on J at the unmodified code (bundle staged + list-heads
verified before filing; HEAD first line in-log):
`sisterfix-repro-node.log`, HEAD=ccc9cea (the collated tip 4fb369f +
this STATUS file; code delta zero). The log, read firsthand:

- shadow compiles the suite, and the compiler itself names the
  mechanism: **"Use of undeclared Var
  eve.static.sister/register-sister!"** — the var is undeclared in
  the CLJS graph because no namespace in it requires
  `eve.static.sister`.
- Node dies at load: `TypeError: Cannot read properties of
  undefined (reading 'sister')`, EXIT=1, zero tests run.

The defect is a measurement at this pin, matching the collation
pass's collate-w3-node.log. Provenance: tree = collated 4fb369f
(code-identical at ccc9cea); tool = J's shadow-cljs via
`clojure -M:node-test`, Node v24.4.1; fresh clone from the staged
bundle.

## Step 2 — the persistent mechanism, studied before any fix

**What the expansion emits** (`src/eve/deftype/macros.cljc`,
`eve-deftype*`): a `do` whose top-level forms name fully-qualified
vars and carry no requires of their own — the host type form, then
`(eve.deftype-proto.serialize/register-deftype-layout! …)`, then
`(eve.deftype-proto.serialize/register-type-refs! …)`, then, where
the host realizes one, the generated sister type, and finally
`(eve.static.sister/register-sister! type-key sister-fields
factory-or-nil)` (macros.cljc:181–184). Resolution of every one of
those vars depends entirely on the using namespace's load graph
having loaded the named namespaces before the expansion's top level
executes.

**How the load is guaranteed — for the persistent registrations.**
A type's namespace requires only `eve.deftype`
(`src/eve/dataset.cljc`: `[eve.deftype :refer [eve-deftype]]`).
That suffices because of eve.deftype's own stated contract
(`src/eve/deftype.cljc` docstring): this namespace "is the macros
and the runtime an expansion calls (requiring eve.deftype is all a
type's namespace needs)". Each host's realization of eve.deftype
**requires the complete set of namespaces the expansion names**:

- JVM (`src/eve/deftype.cljc`): `eve.deftype-proto.alloc`,
  `eve.deftype-proto.data`, `eve.deftype-proto.serialize`,
  `eve.codec`, `eve.deftype.slab-runtime` — and `eve.static.sister`,
  with the comment "the expansion registers every type's generated
  static sister here (eve.deftype.macros, lane H5)".
- CLJS (`adapters/core/cljs-compiler/src/eve/deftype.cljs`):
  docstring "the runtime an expansion calls, and the macros of
  eve.deftype's Clojure side"; requires
  `eve.deftype-proto.alloc`, `eve.process-slab`,
  `eve.deftype-proto.data`, `eve.deftype-proto.serialize`,
  `eve.codec`, `eve.deftype.slab-runtime` — **no
  `eve.static.sister`**.

The guarantee is structural, not a guard or a laziness: you cannot
expand `eve-deftype` without loading `eve.deftype` (the macro lives
there), and loading `eve.deftype` loads its requires. The persistent
registrations therefore resolve on every host, in every namespace,
however early it loads. That is the whole mechanism.

**Where the registries live.** Persistent:
`eve.deftype-proto.serialize` (layout + type-refs, consumed by
eve.retire's walk and eve.tally). Sister: `eve.static.sister` —
descriptor registry (type key → descriptor + layout fingerprint),
the factory handed to `eve.static.chunk`'s packed reader on hosts
that have one (via requiring-resolve; the chunk require inside
sister.cljc is `#?(:clj …)` only).

**The break, against that mechanism.** Lane H5 added a third emitted
registration call and updated the JVM realization's require list to
match — but not the CLJS realization's. On CLJS, any namespace whose
expansion executes before something else happens to load
`eve.static.sister` dies: the compiled JS dereferences the namespace
object `eve.static.sister`, which was never loaded and is undefined.
`eve.dataset` is the measured case — a core namespace that follows
the contract (requires eve.deftype only) and neither requires nor
layering-wise can require the static layer. On the JVM the stale
list is invisible: the .cljc realization carries the require, and
the var resolves against the loaded namespace at call time.

Two facts that bound the fix:

- On CLJS no sister _class_ is generated
  (`adapters/types/js/src/eve/types_js.cljc`, `static-type`: the
  sister on ClojureScript is not realized; the expansion "still
  registers the type's sister layout (register-sister! with no
  factory)"). The CLJS emission is the registration call with a nil
  factory — but the call must still land in the registry.
- `eve.static.sister` is load-safe on CLJS: its load-time footprint
  is one `defonce` atom and function definitions; its only
  host-specific couplings are already inside the namespace (the
  `#?(:clj [eve.static.chunk])` require, the `:clj`-gated chunk
  registration in `register-descriptor!`). Its requires —
  `eve.deftype-proto.data`, `eve.deftype-proto.serialize`,
  `eve.deftype.slab-runtime`, `eve.slab-io`,
  `eve.static.source` (a leaf: "depends on nothing") — none requires
  `eve.deftype`, so the graph has no cycle on either host; the JVM
  build already runs this exact graph.

## Step 3 — the fix (mirrors the persistent mechanism)

Complete the CLJS realization's require list: add
`[eve.static.sister]` to
`adapters/core/cljs-compiler/src/eve/deftype.cljs`, with the same
comment the JVM file carries. Nothing else changes:

- `macros.cljc` untouched — the emission stays identical on every
  host; one implementation, no host conditional anywhere near it.
- No `#?` added anywhere. The only reader conditionals in play are
  the ones already inside `eve.static.sister` itself.
- JVM untouched by construction: the file changed is the CLJS
  adapter's runtime namespace, which no JVM load reads.
- Sister semantics (S1–S6) untouched: `register-sister!`, the
  descriptor registry, fingerprints, the Transfer foreign-value path
  all stand as they were; on CLJS the registration now lands in the
  registry instead of killing the load, exactly as the JVM's does.

This is the persistent mechanism applied to the sister: the macro's
home namespace requires every namespace the expansion names, on
every host realization of that namespace. The alternative shapes —
guarding the emitted call, or moving registration behind the static
layer's own load — were not chosen: the persistent machinery uses
neither, and both would leave the registry silently empty for types
defined in namespaces that load before the static layer (on CLJS
the registration would simply never happen, where the JVM's does).

## The fix as applied

One file: `adapters/core/cljs-compiler/src/eve/deftype.cljs` — the
CLJS realization of `eve.deftype` now requires `[eve.static.sister]`,
with the comment the JVM realization carries ("the expansion
registers every type's generated static sister here"). No other
file changes. (Note for whoever lands this after the F1 rename:
this branch predates the `eve.flat` rename lane; the require's
namespace name renames mechanically with everything else.)

## Step 5 — acceptance

- [x] Re-derivation at 4fb369f: Node suite load death reproduced
      (`sisterfix-repro-node.log`, HEAD=ccc9cea, EXIT=1, zero
      tests, compiler warning naming the undeclared var).
- [x] Fixed tree: Node image suite loads and runs
      (`sisterfix-node.log`, HEAD=cc108d5): **4 tests / 31
      assertions, 3 failures, 0 errors.** The 3 failures are all
      `image-reads-every-type` at image_node_test.cljs:79, 80, 113 —
      H4.1's Dataset row (the named Eve-core field-read residual),
      the same three lines H4's own report names. The byte-identity
      arm is green and run-wastes-nothing (H4.2) is green — both
      re-judged on the fixed tree, no longer standing on owner-tip
      evidence alone. The pre-fix compiler warning "Use of
      undeclared Var eve.static.sister/register-sister!" no longer
      appears (count 0 in the log). The acceptance probe that failed
      before the fix (load death, zero tests) runs clean after it.
- [x] Fixed tree: JVM set unchanged from the collated baselines
      (`sisterfix-jvm.log` + `sisterfix-jvm2.log`, HEAD=cc108d5):
      requirements 594 assertions 0F/**1E — the error is
      requirements-covered failing to resolve H4.2's CLJS test var
      from the JVM, the manifest host-blindness residual, identical
      to the collated tree at ca967b8**; contract 33 green; sister
      45 green (S1–S6 intact); dataset-batch 29 green; transfer 150
      green; image 208 assertions with **2 failures, both
      `image-read-speed` (image_test.cljc:412) — the Z1.7 pair**,
      identical to baseline. (Leg 1's `&&` chain stopped after
      requirements — its attributed error exits the runner nonzero —
      so the remaining five namespaces ran as leg 2.)

**Lane verdict: COMPLETE at tip cc108d5.** The one open item this
lane leaves behind is the environment note below, not a code item.

## Environment note (not a code finding)

Mid-lane, hatch developed a flip-flopping platform write fault
(the same one MEMORY.md records other lanes hitting Nov 12):
`write()` to fresh files returned EPERM — git loose-object writes
failed deterministically for a stretch (strace: the deflate
`write()` itself, fd on a fresh tmp_obj), while other writes landed
intermittently. The fix commit landed on a retry once a write
window opened; no workaround touched the tree's contents
(`git log`/`git diff` at cc108d5 show exactly the one-file fix over
the STATUS commit). Any lane whose local commits stall with
"unable to write loose object file: Operation not permitted"
should retry rather than diagnose its tree.

## For the collation report (append-ready section)

**Sister-registration CLJS load break — FIXED (lane SISTERFIX,
branch `claude/static-sister-load-fix`, tip cc108d5 over collated
4fb369f).** The residual named in the third pass is closed. Root
cause, re-derived at the pin (pre-fix repro on J,
`sisterfix-repro-node.log`, HEAD=ccc9cea: shadow's own warning
"Use of undeclared Var eve.static.sister/register-sister!", then
the load death, EXIT=1, zero tests): the persistent registrations
never fail at load because `eve.deftype` — the macro's home, "the
runtime an expansion calls" — requires every namespace the
expansion names, on **each host realization** of the namespace.
Lane H5's `register-sister!` emission came with the matching
require in the JVM realization (`src/eve/deftype.cljc`) but the
CLJS realization (`adapters/core/cljs-compiler/src/eve/deftype.cljs`)
was never updated, so on CLJS the emitted top-level call
dereferenced a namespace nothing had loaded. **The fix completes
the CLJS realization's require list with `[eve.static.sister]` —
one file, require + comment.** It mirrors the persistent mechanism
exactly (the operator's ruling: learn from how the persistent
machinery works; no `#?` legs in the deftype machinery — none were
added; `macros.cljc` untouched, the emission identical on every
host; JVM behavior unchanged by construction, the changed file
being the CLJS adapter's runtime namespace, which no JVM load
reads). Sister semantics unchanged: `register-sister!`, the
descriptor registry and fingerprints, the Transfer foreign-value
path all stand; on CLJS the registration now lands in the registry
(as the types_js note always said it should) instead of killing
the load. **Acceptance (J, HEAD=cc108d5 in every log):** the Node
image suite — the probe that died at load pre-fix — loads and
runs: 4 tests / 31 assertions, 3 failures, all H4.1's Dataset row
(image_node_test.cljs:79/80/113, the named field-read residual);
byte-identity arm green; run-wastes-nothing green, so **H4.2 and
the byte-identity arm are now judged on the collated lineage
itself**, not on owner-tip evidence alone. JVM set identical to
the collated baselines (requirements 594 0F/1E host-blindness
attributed as before; contract 33, sister 45, dataset-batch 29,
transfer 150 green; image 208 with only the Z1.7 pair). The
residual list loses "Deftype sister registration breaks CLJS load
for core deftypes"; H4.1 (CLJS deftype field reads) and the
manifest host-blindness remain open, untouched by this fix. One
forward note: the fix predates the F1 `eve.flat` rename — the
added require's namespace name renames mechanically with the rest.
