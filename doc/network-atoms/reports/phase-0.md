> **Historical record** — the network-atom lane's Phase 0 report (wire spec, dialect test, fixture corpus), 2026-10-16. It describes eve as it stood then, not eve 0.9. Its acceptance rows are VS and broker commands (atom.broker work).
> Current authority: `doc/network-atoms/README.md` (the user guide), `WIRE.md`, `ADAPTERS.md`.

# Phase 0 report — wire spec, dialect test, fixture corpus, ledger

2026-10-16, ET. Branch `claude/eve-network-atoms-impl`. Plan: `eve/doc/network-atoms/IMPL-PLAN.md` §2 (units 0.1–0.4),
amendments §9 A1–A4. Every claim below is tagged [ran] (this box, this commit) or [read].

## Acceptance [ran]

| unit | command (from `vs/` unless noted) | result |
|---|---|---|
| 0.2 dialect | `bin/vs test net_wire_dialect_test tl_02b_coordinator_lifecycle_test tl_02c_coordinator_cas_test tl_02d_coordinator_lease_test net_run_test` | `VS-TEST: files=5 pass=5 fail=0 walled=0` |
| 0.2 blob fixes (A2) | `bin/vs test tl_01b_blob_roundtrip_test tl_01c_blob_sha_index_test tl_01d_blob_missing_error_test blob_enc_test` | `VS-TEST: files=4 pass=4 fail=0 walled=0` |
| 0.3 corpus | `scripts/gen-net-fixtures` | `gen-net-fixtures: 22 rows -> …/eve/test/eve/fixtures/vsv2-blocks.edn (failed: 0)` |
| 0.3 consumer | `vs/bin/vs test net_fixtures_test` (repo root: the file is read from `eve/test/eve/fixtures/`) | `VS-TEST: files=1 pass=1 fail=0 walled=0` |

## What landed

* **0.1 `WIRE.md`** (G5): the production wire transcribed route by route with `coordinator.cljs` / `worker.cljs` line
  cites, the `services/atom-broker` drift table (nine rows, each named for Phase 1), SKEL little-endian, keys, the
  `root-changed` push, the blob wire (block = masked image; set root XOR 0xEE rule; scalar-child rule), B-creds and the
  `eve-db` rollout note.
* **0.2 P1** `vs/test/vs/net_wire_fixtures.vs` (`production-broker`, shapes field for field as production, reads only
  production's request fields) + `vs/test/vs/net_wire_dialect_test.vs` (5 deftests: lifecycle, denied, auth,
  credentials, skeleton blob). Red-first rows fixed in `vs/src/vs/net/coordinator.vs`: SKEL counts big-endian →
  little-endian (`-u32le` / `-read-u32le`, magic as the bytes S K E L); `create-atom` reads the body's `epoch`;
  `deref-atom` and the 409 map carry `lease_ttl`, `skeleton_sha`, `freelist_sha`, `freelist_blob`; a 403 carries
  `:detail`. `tl_02b` (hand-built SKEL header) and `net_run_test` (denied row) retargeted to the corrected shapes.
* **A2's library bug**, found by the corpus: `vs.net.s3-sign/-bytes`, `vs.net.blob-io/-ints`,
  `vs.net.coordinator/-ints` measured binary strings with `count` (UTF-8 characters) and so hashed, encoded and
  indexed short on any binary holding a valid multi-byte sequence; all three now use `.-length`. The blob tests had
  passed only because their data forms no valid sequence.
* **0.3 G1** `vs/scripts/gen-net-fixtures.vs` + the bash driver `vs/scripts/gen-net-fixtures` (one `vs run` per row,
  A3): for each corpus value the root block is found by `rt-addr`, read through `rt-page-cid-at` / `rt-page-text`,
  masked as `eve.vs` `seal_block` masks (bytes 0..47 of every inner slot; a vec node cut at its last occupied slot),
  and the generator checks `sha256(image) = the slot's SHA` for every block before printing it — the kernel's own
  seals are the oracle. `eve/test/eve/fixtures/vsv2-blocks.edn`: 22 rows, 195 blocks (108 hamt nodes, 11 map roots,
  5 set roots, 11 vec roots, 48 vec nodes, 4 list roots, 8 list nodes), 410 key/hash pairs (G2 on real keys), 27
  scalar leaves with their typekey + xxh3 bands, one `:portable? false` row (doubles, tier 4 — A1). 1,015,636 bytes.
  `vs/test/vs/net_fixtures_test.vs` re-derives every SHA through `rt-crypto-sha256`.
* **0.4 ledger**: `L14-persistence-tiers.md:20` and the C18 deviation bullet, `00-master-plan.md` L14 row,
  `evidence-ten-readers.md` rows G8/G9/G10 — OPEN, reopened 2026-10-16, the port is this lane.

## Deviations from the plan (recorded as amendments)

* A1: slot bands are tiered; only ExtCID-headed children seal by SHA-256. Tiers 3/3b/4 become content-addressed in
  Phase 2 — the corpus records them as they are.
* A2: byte strings on this leg (`count` sees characters; `str` joins lose bytes; `rt-bytes` / `rt-byte` /
  `rt-crypto-sha256` are the faithful rows).
* A3: one row per process; values as top-level defs; strings built at run time; no shift-30 pair, no collision row.
* A4: 22 rows, not 25 — the scalar kinds are leaves, the 70 K string is a rope, the long is refused.

## Measured costs [ran]

* The corpus driver's wall clock was not timed (it ran as a background task across a context reset; `vec-1100` and
  `str-keys-300` dominate, 592 KB and 70 blocks). A row alone: `printf 'empty-map' > vs/.gen-row && cd vs && bin/vs
  run scripts/gen-net-fixtures.vs` finished inside a 90 s bound.
* The B-tier trap (`fetch_on_miss_test.py` 1/6 at the base, `vs.tier/page-text`'s `conj`) was not met: the generator
  reads ≤ 6,000-byte chunks through `rt-page-text` directly. Phase 3 unit 3.1 stands.

## Not done in this phase

Nothing of §2 is open. Phase 1 unit 1.1 is next (IMPL-PLAN §3.1).
