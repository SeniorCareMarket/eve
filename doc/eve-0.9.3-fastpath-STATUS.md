# Eve 0.9.3 — JVM fast-path restoration lane — STATUS

Lane opened 2026-11-05 ~10:14 PM ET on the operator's direct order ("get that up now").
Branch: `release/eve-0.9.3` @ 12324bb. Worktree: `~/workspace/wt/eve-093`.
Work product: `~/workspace/wt/eve-093-fastpath/` (logs, probes).

## The defect (banked evidence, bench lane `muse/eve-093-release-bench` on atom.broker)

On the assembled release tree the JVM seat lost the heap-grade fast paths; the
slab layer itself is intact. Release-tree JVM medians vs the heap-grade line
(claude/eve-jvm-heap-grade @ 8cbf392, control-verified same box/hour):

- S2 direct `get`, 100-entry Eve map: **188 µs** (r2 cross-check band 350–560 µs) vs **0.39 µs**
- S1b guarded sparse `(get @a k)`: **185 µs** vs **1.89 µs**
- S1a plain sparse deref get: 126 µs vs 62.9 µs; S3a swap! 179 vs 39.6 µs; S3b reset! 222 vs 26.4 µs
- S4b raw root handle (guarded deref): 1.58 µs vs 1.63 µs — **unchanged**

Layer attribution (bench lane r2 controls, single-process, same box/hour):
heap-grade checkout S2 0.37 µs → pre-seat release tip 8213d78 S2 **5.02 µs**
→ post-seat 12324bb S2 **~350–560 µs**. Two layers: ~13× in the collation
before the seat merge, ~70× more from the seat merge's shared-tree deltas.
`mask-hash` exonerated by diff (byte-identical pre/post seat); `eve/map.cljc`'s
get path untouched except one `field` routing in a retire fn.

## Suspect list (to verify or refute firsthand, REPL-first)

Coordinator's assessment — assembly unwiring, not a step-3 regression — is
UNVERIFIED. Ordered suspects:

1. **Seat layer (~70×):** `src/eve/deftype_proto.cljc` — the seat delta rewired
   every eve-deftype method body's field bindings from `(.-slab-off__ this)` /
   `(.-sio__ this)` to `(eve.host/field this '…)`. On the JVM `eve.host/field`
   is a macro expanding to `(.-f x)`, but only where the macro resolves as a
   macro in the emitting namespace; check what the emitted bodies actually
   compile to on the JVM (macro vs reflective fallback).
2. **Seat layer:** `src/eve/deftype/registry.cljc` hint-keys change
   (`(remove namespace …)`) — could strip primitive type hints from eve-deftype
   field descriptors, un-primitivizing the int32 node fields step 3 relies on.
3. **Seat layer:** `src/eve/deftype_proto/alloc.cljc` (116-line delta) — the
   in-place KV scan / node-read path step 3 built.
4. **Pre-seat layer (~13×):** fault-1 fix b62b738 added `d/IPlain` to
   EveHashMap/EveVector/EveHashSet/EveList deftypes (preseat delta
   map/list/set/vec + atom.cljc bb-guard splice + net/codec F-1). Check whether
   a deftype change displaced step 3's direct-get dispatch.
5. **Wiring:** deps.edn / hosts.edn / completion classpath differences between
   the heap-grade branch checkout and the release tree (which `eve.host` /
   completion the JVM seat actually loads).

## Method

Same bench file both lanes banked (`heap_bench_as-run.cljc`), same runner
shape (deps.edn :paths + bench dir + Clojure 1.12.0), run per-tree in short
probe mode first (seconds–minutes, never an hour-scale leg). Diagnosis must
name file, form, and the collation commit that introduced each layer before
any fix. Fix must keep both arms correct — a fix that re-breaks the jank seat
is not a fix. Re-bench on j.slaa.us in a quiet window (one heavy leg at a
time); numbers go to the coordinator FIRST; README untouched. Final assembled
JVM gate on the fixed tree; then stage the tip for push via the atom.broker
channel (this box's Eve credential is read-only). No move to main, no tag —
the operator's call.

## Stop conditions

- A leg failing twice the same way stops; diagnosis banked here; no third
  blind attempt.
- If the evidence proves a genuine step-3 regression (not assembly), STOP and
  report — step 3 is not redesigned in this lane.
- If a fix candidate would change jank-seat behavior, it is not applied until
  its jank arm is probe-verified.

## DIAGNOSIS (2026-11-05 ~10:45 PM ET) — seat layer PINNED, mechanism named

Local per-tree bench reproduction (same bench file/runner, this box, load
1.2–7.7; absolutes inflated, ratios decisive): heap-grade tip S2 2.05 µs →
preseat 9.16 µs → release ~1.46 ms. Both bench-lane layers reproduce.

**Seat layer = ONE file: `src/eve/deftype_proto/alloc.cljc`.** Bisect on the
preseat tree: seat codegen family (deftype_proto.cljc, sio, vocab, serialize,
deftype/macros, registry) + seat `eve.host` → S2 unchanged (9.26 µs). Adding
seat `alloc.cljc` alone → S2 2.24 ms / 1.11 ms (two runs). A/B at the fn
level (probe2): `alloc/node-i32` 947 ns (base alloc) vs 67,227 ns (seat
alloc); direct get 4,030 vs 960,476 ns. Same sio class (JvmSlabCtx), heap
path live (node-heap-array non-nil) in both.

**The exact form:** step 3's hot node readers (`node-heap-bytes`, `node-at`,
`node-bytes`) read the ctx arrays as `(aget ^ints (field ctx data-offsets)
ci)` / `(aget ^objects (field ctx regions) …)`. Patching ONLY `node-at`'s
two forms back to `(.-data-offsets ctx)` / `(.-block-sizes ctx)`: node-at
115,816 → 373 ns/op.

**Mechanism:** `eve.host/field` is a macro whose expansion
`(list (symbol ".-…") x)` is a FRESH list — it drops the source form's
metadata, including the `^ints`/`^objects` tag the reader put on the
`(field …)` form. `aget` (via eve.host's verbatim-forwarding macro →
clojure.core/aget) selects its primitive overload from that tag; tag lost →
slow generic aget on every node read. Runtime controls (probe5, alloc ns):
aget(field) 21,474 ns vs aget(direct) 61 ns vs aget(let-bound field) 377 ns
— a let binding carries the type on the symbol, which is why only the
inline-in-aget sites burn. The seat's `(field …)` routings are correct on
jank (they expand to the completion's get-field there); the defect is the
macro's metadata loss, on the JVM side of the shared macro.

**Fix (applied next):** `eve.host/field` preserves `(meta &form)` on its
expansion — one macro, every call site, compile-time only, jank expansion
unchanged in shape. Pre-seat layer (~13×) still to bisect after this fix is
measured on the full release tree.

## Fix measured + pre-seat layer local verdict (~11:00 PM ET)

Fix committed 6f44aef on the release branch. Full release tree, local bench:
S2 1.46 ms → **5.50 µs** — the seat layer is gone; the tree now reads in the
pre-seat band locally.

Pre-seat layer: the ENTIRE content delta heap-grade tip → pre-seat tip is
16 files (src IPlain/atom/codec + codec adapters + tests + slab binaries).
Applied to the heap-grade worktree in two steps (src, then + codec
adapters), S2 stayed at the heap-grade local band (1.96 / 3.19 µs vs 2.05 µs
control, loads 1.3–4.7): **the pre-seat delta does not reproduce the 13× on
this box.** Either the bench lane's single-process pre-seat control on J
(5.02 µs) is a staging/measurement artifact, or the effect only manifests at
full speed. The J re-bench settles it: heap-grade control, pre-seat tip, and
fixed release tree, staged and run identically, 3 processes per arm.

## RE-BENCH VERDICT (2026-11-05 ~11:46 PM ET, j.slaa.us) — FAST PATHS RESTORED

Fixed tree = release @ 6f44aef staged on J as `~/jvj-bench/eve-fix`
(archive md5-verified files; bench file byte-identical to the bench lane's
as-run variant). Method identical to the bench lane's: probes first
(JVM 7/7, jank 6/6, all checksums green), 3 processes per arm, min-of-3
batches per process, headline = median of 3; loads 0.42–1.93 recorded per
run in `fix-passes-driver.log`. Raw logs: `~/workspace/wt/eve-093-fastpath/logs/fix-*`
and on J under `~/jvj-bench/logs/`.

| Shape | JVM pre-fix (rel median) | JVM FIXED (median of 3) | Heap-grade line (banked) | Jank pre-fix | Jank FIXED (median of 3) |
|---|---|---|---|---|---|
| S1a sparse get @a | 126.49 µs | **71.08 µs** | 62.89 µs | 2,917.99 µs | 2,981.83 µs |
| S1b guarded get | 184.79 µs | **1.82 µs** | 1.89 µs | — | — |
| S2 direct get 100-map | 188.06 µs | **0.40 µs** | 0.39 µs | 243.87 µs | 250.94 µs |
| S3a swap! inc | 178.54 µs | **107.53 µs** | 39.63 µs † | 633.65 µs | 660.16 µs |
| S3b reset! alt | 222.19 µs | **65.25 µs** | 26.42 µs † | 614.57 µs | 623.64 µs |
| S4a deref @a | 109.04 µs | **47.16 µs** | 57.79 µs | 3,100.96 µs | 3,012.61 µs |
| S4b raw root handle | 1.58 µs | **1.59 µs** | 1.63 µs | 19.83 µs | 19.77 µs |

† S3a/S3b: the round-1 "line" figures did not reproduce on the heap-grade
checkout itself today — same-day control (eve-jvm, its own staging/harness):
S3a 108.05 µs, S3b 108.84 µs. Against the same-day control the fixed tree is
at parity on S3a (107.53) and faster on S3b (65.25). Every other shape
matches the banked line within noise. **The jank arm is unchanged on every
shape** — the fix is compile-time metadata only, as designed.

**Pre-seat "layer 1" — CLOSED as a harness artifact.** Attribution controls
in the bench lane's r2 series ran a different harness copy (its S2 calls
`(get m100 …)` on a top-level def). Same preseat tree, both files, today:
r2 variant S2 2.24 µs vs as-run variant **S2 0.383 µs**, S1b 1.82 µs, S4b
1.59 µs (log `fix-preseat-asrun.log`). Under the canonical harness the
preseat tree never lost the fast paths; the only real regression was the
seat layer, fixed above. (Local bisect corroborates: the full preseat
content delta applied to the heap-grade tree left S2 in the fast band.)

## FINAL ASSEMBLED JVM GATE (2026-11-05 ~11:16 PM ET) — PASS, reds = base verbatim

Continuation lane (fastpath closeout) ran the full JVM suite on the fixed
release tree @ 5041023 in this worktree: `CLJ_CONFIG=~/workspace/eve-env/cljconfig
clojure -M:jvm-test` (log: `~/workspace/wt/eve-093-fastpath/logs/final-jvm-gate-5041023.log`).

**Printed: Ran 630 tests containing 10422 assertions. 2 failures, 2 errors.**

The four reds, each identified:
- FAIL ×2 — `eve.aegis.pages_v3_test/the-kernel-gets-what-eve-wrote`
  (pages_v3_test.cljc:286, :287). Trace shows the cause plainly:
  `FileNotFoundError: ../vs/test/...` — the missing sibling `../vs` checkout.
- ERROR — `eve.aegis.pages_v3_test/cross` (pages_v3_test.cljc:308, NPE from
  the same missing-sibling path).
- ERROR — `eve.net.wire-test/three-servers-run-one-pmap` (wire.cljc:23):
  this box's sandbox refuses raw loopback TCP ("Other TCP connections is
  turned off for this assistant").

**All four are the untouched base tree's own reds, verbatim:** the collation
lane's baseline run on pristine main (622 tests / 10555 assertions,
`~/workspace/eve-093-baseline.log`) prints the identical four FAIL/ERROR
lines. The assembled tree introduces NO new red. Count delta (622 → 630
tests) is the collation pieces' + seat's added test files. Under the
manifest's scoring contract (same reds as base, none others) the gate
PASSES. `eve.jvm-legacy-index-test` ran in this suite with no FAIL/ERROR —
the 67ff60b test-fix suite is GREEN on the assembled tree, as required.

## PUSH STAGING + LANE CLOSEOUT (2026-11-05 ~11:16 PM ET)

This box's credential is read-only on SeniorCareMarket/eve, so the tip is
staged per the collation manifest's push sequencing: the release tip
carrying this STATUS is pushed into the atom.broker repo under the staging
ref `staging/release-eve-0.9.3`, and a push request is filed in the
atom.broker channel (docs/cohost/debug-seam/REPORTS.md on
`muse/cohost-reports`) addressed to cohost Agent 2 / Agent 3 — both hold
Eve write access — to fetch the staging ref into SeniorCareMarket/eve as
`release/eve-0.9.3` and verify on the fetched ref. Gate evidence named in
the request: this section (630 / 10422 / 2F / 2E = base reds verbatim,
legacy-index green) + the RE-BENCH VERDICT above (fast paths restored,
jank arm unchanged). The exact staged sha is named in the REPORTS entry
(this STATUS rides in that tip by construction).

Nothing moved to main; no tag; README untouched — the operator's calls,
on numbers already reported to him. **This lane is CLOSED.**

## Log

- ~10:20 PM ET — pickup. Branch tip verified 12324bb in worktree
  `~/workspace/wt/eve-093` (clean). Bench report + r2 cross-check read from
  atom.broker `muse/eve-093-release-bench` (commits 36a1256, 0b51d64).
  Read-path diffs taken: preseat delta (8cbf392→8213d78) = atom.cljc bb-guard
  splice, IPlain on map/list/set/vec, net/codec F-1. Seat delta
  (8213d78→729e909) = deftype_proto.cljc field-binding rewire,
  registry.cljc hint-keys, alloc.cljc 116 lines, hamt_util `cond`-on-jank
  (JVM `:default` keeps the `case`), map/list/set/vec `field` routings.
  `eve.host/field` confirmed a macro on the JVM clojure adapter expanding to
  `(.-f x)`. Next: per-tree probe runs (heap-grade / preseat / release).
