# Lane B1 STATUS — a builder with no garbage

Lane: Wave 1 lane B1 of `IMPL-PLAN.md` §3.3 (requirements B1.1–B1.4; board row
B5, plain → atom). Branch `claude/static-b1-builder`, based on the research tip
`7402ef9` + merged Wave 0 contracts (`claude/static-w0-contracts`, via the local
path remote `w0`). Pushes to origin fail on this box (no GitHub credential) —
local commits only; the coordinator collates.

## What the research established, re-derived at this pin before building

- Today's plain → slab writers (the ones `swap!` uses via `eve.atom`'s
  `write-plain-collection!` → the codec's registered coll writers):
  - `:vec` (`eve.vec/-write-vec-coll!`) already assembles bottom-up:
    element value blocks, then `assemble-vec!` writes trie + tail + header.
    No path copying, no garbage.
  - `:list` (`eve.list/-write-list-coll!`) already builds back-to-front in
    one pass. No garbage.
  - `:map` (`eve.map/-write-map-coll!`) builds the HAMT by repeated
    `hamt-assoc`: every insert path-copies, and the writer frees the replaced
    nodes back to the allocator. In a slab that is allocator churn (the cost
    M6 prices); in an append-only buffer, where free is a no-op, it is the
    6.2 %-live build of M7.
  - `:set` (`eve.set/-write-set-coll!`) is the same HAMT pattern via
    `hamt-conj`.
- So the direct builder's new work is exactly: a **bottom-up HAMT** for maps
  and sets (group entries by hash fragment level by level; every node
  allocated once, in final form), composed with direct vec/list assembly and
  the codec's scalar encodings, recursing with its own writers so a nested
  map costs no path copy either. Layouts mirrored from the constructors in
  `eve.map` / `eve.set` / `eve.vec` / `eve.list` at this pin, so the built
  value is byte-for-byte the structure today's writers produce.
- The image: blocks in one buffer under the FORMAT-V1 chunk header (32 B,
  kind 1, layout 1), then the block table and relocation table
  (`eve.static.contract` write fns, W0). The tables are computed at sealing
  by ONE walk of `eve.retire/children` over the finished blocks —
  metadata only, no byte moves, no compaction; owner/target are block
  ordinals (root first), positions the pointer field's byte offset in the
  buffer — the shape lane Z1's `detach` emits and lane Z3 consumes. One
  walk as the single source of truth (rather than recording pointers at
  write time) is what lets a fallback subtree, written by today's writer
  into the same buffer, seal correctly too.

## Plan

1. Step 1 (measurement): B5 baseline at the starting commit —
   `test/eve/static/b1_bench.clj`, scoreboard method (fresh pre-sized atom
   per rep, min of 5 after 2 warm). Today arm only until the builder exists.
2. Tests first: `test/eve/static/build_test.cljc`, the four requirement
   tests of `requirements.edn` (B1.1–B1.4), red.
3. `src/eve/static/build.cljc`: BuildSio (bump allocator over a growable
   byte array, allocation log = the block table's source), direct writers,
   image assembly + sealing.
4. Perf loop on board row B5: build + place (per-block `alloc-run!`
   default) + `swap!`, against the gate (under the probe's 0.37–0.56× of
   today's `swap!` per corpus).

## Load gate

Box load1 has run 10–20 all session (gate: no heavy run starts above 8).
Bench and full-corpus test runs wait for dips; small test runs are scoped.

## Log

- 2026-11-12 ~01:50 AM ET — tree cloned, W0 contracts merged, seams read
  (IMPL-PLAN §3/§3.3-B1, FORMAT-V1 §2/§9, MEASUREMENTS M5–M7, probes
  lib/p1/p6, eve.vec/map/set/list writers, eve.retire/children,
  eve.digest, W0 contract + scoreboard + corpora).
- Bench written (`test/eve/static/b1_bench.clj`): today arm = `swap!` of the
  plain value; direct arm = `build-image` + place (`alloc-run!` per class) +
  `swap!` of the reopened value. First --tiny run exposed a bench defect,
  not a product one: creating a fresh 44 MB domain PER REP priced region
  zeroing (today arm 475–1325 ms at tiny sizes). Fixed to the probe's
  accounting: domain once per corpus, per-rep freshness is the atom. The
  full-size baseline rerun waits on the load gate (box has spiked to 19–28
  repeatedly); the gate comparison itself is unaffected — both arms share
  the harness.
- Requirement tests committed red (`f7ee391`): B1.1 equal / same root digest
  / further assoc, B1.2 allocation set == reached set (incl. a 10k map),
  B1.3 tables round-trip + relocation table == an independent
  `eve.retire/children` edge walk + an actual placement through `swap!`
  that reads back and takes a further write, B1.4 fallbacks recorded
  (host array / reference / foreign Eve value).
- Builder landed (`1ee25cf`): `src/eve/static/build.cljc` — BuildSio (bump
  allocator + allocation log over a growable byte array, first block at
  offset 32 under the reserved chunk header), bottom-up HAMT for maps/sets
  (fragment grouping; collision nodes only as bitmap children; entry order
  preserved inside collisions — mirrors `hamt-assoc`'s canonical shape),
  vec via `eve.vec/build-vec`, list back-to-front, codec scalar/key
  encodings, fallback paths per B1.4, sealing walk via `eve.retire/children`.
- **Requirement tests GREEN**: `clojure -M:jvm-test -n eve.static.build-test`
  — 4 tests, 354 assertions, 0 failures, 0 errors. Next: the B5 bench row
  (baseline + direct), load permitting.
- First full bench attempt (started at load1 7.81): NOT a board row. Other
  lanes drove the box far past the gate mid-run — map-10k today 4,709.70 ms
  (the probe's plain arm is ~80 ms scale; ~50× contention inflation), direct
  7,062.54 ms (1.50×), read-backs ok; then the JVM was OOM-killed during the
  vec-100k corpus (6 GB cell shared with the other lanes' JVMs, -Xmx4g too
  greedy). Retry plan: -Xmx2g, a quiet window, and a build-only attribution
  arm so a persistent gap can be priced (builder vs default placement).
- Attribution found the real defect, and it was in the seal: build-image
  vec-10k cost 21.2 s of which build! was 0.49 s and the walk 0.68 s —
  **the table writes cost ~15 s**. Cause: `eve.static.contract`'s
  write-block-table!/write-relocation-table! take the byte array
  unhinted, so every `aset` is reflective (~0.8 ms per table entry
  measured on this box, both tables ≈ 50k reflective ops per seal). B1's
  seal now writes the tables with a ^bytes-hinted local writer — the
  bytes are the contract's exactly, and B1.3 still reads both tables
  back through the contract's readers. build-image vec-10k: 21.2 s →
  1.12 s; map-10k: 3.14 s → 0.79 s (same-or-worse load). Flagged for W0:
  the contract writers want ^bytes hints.
- Second harness defect, also mine: the direct arm placed into the SAME
  domain the today arm had churned through seven stores — a fragmented
  allocator. Per-arm domains (the probe's arrangement) cut map-10k
  place/swap from ~880 ms (inferred) to 162.53 ms. Bench also split to
  one corpus per JVM after the second cell OOM (W0's scoreboard hit the
  same wall and did the same).
- **Board row B5, first valid figures** (min of 5 after 2 warm, per-corpus
  JVMs, -Xmx1g; load cycled 7–45 during the runs, so read as ratios):
  map-10k today 1,586.19 ms, direct 561.49 ms = **0.35× — under the 0.37
  gate** (build 398.96 + place/swap 162.53, read-backs ok). vec-100k
  first attempt ran in a worse window (load 16–36): 0.92× vs the 0.47
  gate (build 3,263.80 + place/swap 3,408.70) — re-run in a fair window
  in flight, with records-10k and messages-5k behind it.
- Perf passes 1–3 (each: one change, re-run, keep): scalars via
  `encode-value`; loop-based node writers; HAMT grouping on a 32-slot
  array; per-build key memo. Records: 2.02× → 0.77× → 0.65× → **0.52×**
  (gate 0.51 — at parity, a hair over as measured). Messages: 1.76× →
  0.95×. Map (interleaved, pass 2): **0.16×**. Vec (pass 2): 1.60×,
  place/swap the larger half. Build tests re-run green after every
  pass (354 assertions).
- **VENUE SHIFT (operator order, 2026-11-12 05:19 ET): no heavy runs on
  hatch, at any load.** The local final bench chain was stopped
  mid-messages the moment the order landed. Remaining heavy legs are
  filed with the job agent (lane `static-eve/builder`, venue_pref `j`,
  class `heavy`), one JSON per leg in ~/workspace/job-agent/inbox/:
  `b1-bench-messages.json`, `b1-bench-vec.json`, `b1-bench-map.json`,
  `b1-bench-records.json` (records' hatch final exists — 0.52× — the J
  leg completes a same-box row set), and `b1-tests-postmerge.json`
  (the post-merge build+requirements+contract verification still owed). Each run clones
  ~/static-eve-bundles/b1.bundle on J, checks out
  claude/static-b1-builder, and writes ~/static-eve-results/<leg>.log.
  **BUNDLE REFRESH NEEDED**: b1.bundle must be refreshed from this
  branch's tip at the coordinator's sweep before the legs run (a
  bundle created from the tip at filing time sits in
  ~/static-eve-bundles/ as the refresh source). Results return via the
  coordinator; the B5 row in B1-REPORT.md is filled from J's logs.
- 2026-11-12 ~06:00 ET — **VENUE RESULTS IN (J, clean box, min of 9,
  interleaved) — lane closed out.** Post-merge verification suite
  GREEN: 9 tests, 917 assertions, 0 failures, 0 errors — B1.1–B1.4
  confirmed on the venue (requirements.edn already `:built`). B5
  rows: map-10k 484.46 → 52.56 ms = **0.11× (PASS, beats the
  0.37–0.56 band)**; records-10k 747.39 → 449.92 = 0.60× (near-miss,
  just over the band's 0.56 top); messages-5k 1,195.92 → 811.59 =
  0.68× (MISS — build the larger half, 600.56 of 811.59); vec-100k
  387.35 → 623.21 = 1.61× (MISS — place/swap the larger half, 344.60
  of 623.21, which is Z2/Z3/Z4 territory, not the builder's). **B5
  gate as written (under the probe's figure on every corpus) NOT
  MET** — met on map only. B1-REPORT.md carries the venue table of
  record alongside the banked hatch ratios (0.16× / 0.52× / 0.95× /
  1.60×); the B5 row set is appended to doc/static/SPIKE-LOG.md.
