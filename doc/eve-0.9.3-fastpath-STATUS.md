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
