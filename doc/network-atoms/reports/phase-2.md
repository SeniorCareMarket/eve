> **Historical record** — the network-atom lane's Phase 2 report (the VSv2 network atom), 2026-10-16. It describes eve as it stood then, not eve 0.9. Its acceptance rows are VS and broker commands (atom.broker work).
> Current authority: `doc/network-atoms/README.md` (the user guide), `WIRE.md`, `ADAPTERS.md`.

# Phase 2 report — the VSv2 network atom

2026-10-16, ET. Branch `claude/eve-network-atoms-impl` (= `claude/intelligent-edison-235352`). Plan:
`eve/doc/network-atoms/IMPL-PLAN.md` §4, amendments §9 A8–A12. Claims tagged [ran] (this box, this commit) or [read].

## Acceptance

| unit | command (from `vs/` unless noted) | result |
|---|---|---|
| landing | `toolchain/chain /tmp/netimpl-chain2 cut gen1 gen2 gen3 land`, the eight files copied; `scripts/check-artifact-freshness`; `bash scripts/check-unresolved-callees` | [ran] FIXED POINT gen3 == gen2 (5,752,597 B, sha 1f5f7bc3); `check-unresolved-callees` CLEAN (383 sites, 60 names); freshness STALE on the same 11 orphaned template members as the builder it replaced (John's ruling of 2026-10-16, recorded on c1cf93d); gate battery 15/15 after gate 10 was re-based (+1,947 B, section-exact) and gate 11's three findings fixed |
| 2.2 + 2.4 kernel | `python3 test-xfail/persistence-tiers/kernel_direct_block.py` | [ran on the kernel of 62f8f59] `PASS 175 FAIL 0` |
| 2.1–2.5 `.vs` | `bin/vs test net_walk_test net_fixtures_test net_run_test net_load_test blob_enc_test tl_01b_blob_roundtrip_test tl_01c_blob_sha_index_test tl_01d_blob_missing_error_test net_snapshot_test net_remote_atom_test net_graft_test net_wire_dialect_test` | [ran] `VS-TEST: files=12 pass=11 fail=0 walled=1` — the wall is `tl_01b` (a zero-byte `rt-block-text` read answers nil: Phase 3 build item 7) |
| 2.4 fetch on miss | `python3 test-hosts/wasmtime/fetch_on_miss_test.py` | [ran] `HOST-TEST: wasmtime checks=6 pass=6 fail=0` (78 blobs, 78 GETs over 29 presign calls) |
| 2.3 blob I/O on three hosts | `python3 test-hosts/{wasmtime,node,workerd}/blob_io_test.py` | [ran] wasmtime `checks=11 pass=11`, node `checks=11 pass=11`, workerd `checks=12 pass=12` |
| 2.6 A3–A5 | `python3 test-hosts/workerd/broker_sim_test.py` | [ran] `HOST-TEST: workerd checks=44 pass=40 fail=4` — A1–A4 and A6–A9 green; the four reds are A5: the broker Worker runs out of slab after ~1,300 requests (each request's garbage stays; the signer's share is fixed, below). The reclaim of what a request made is main's L04 C17 unit-end scan, which `vs.http.server/serve` already brackets per request: it arrives with the merge and the next landing (Phase 3's one build) |
| 2.7 edge relay | `python3 test-hosts/workerd/apps_test.py` | RESULT-APPS |
| 2.7 the mirror gate | `python3 test-hosts/workerd/broker_sim_test.py --serve ENV` then, from the repo root, `node test/tiered-atom/phase-7-mirror-gate-real.pw.mjs` with ENV's settings | RESULT-GATE |

## What landed

* **2.1 P3 + P5 + P6 + P11, 2.3 B-creds on the client** — `vs.net.bytes`, `vs.net.snapshot` (VSNP), `vs.net.remote-atom`
  (deref / swap! / reset!, leases held and renewed), `vs.net.coordinator` (`presign`, `renew-lease`; `fetch-credentials`
  deleted), `vs.net.blob-io` on a URL supplier with the HEAD dedup; `blobstore.py`'s `/presign` (§9 A8).
* **2.2 + 2.4 blocks by content, one kernel build and one chain** — the SHA table, `eve_block_image`, `eve_block_adopt`,
  the no-patch resolve, `_st_forget` at retire's nomination, inline FLOAT64 boxes, canonical value blobs (eve.vs, retire.vs);
  the rows `rt-block-image`, `rt-block-text`, `rt-block-adopt!`, `rt-block-lookup`, `rt-block-tier!` and the by-key serve
  (program_runtime.vs); `vs.net.walk` (the walker and its refusals), `vs.tier/block-tier` (sibling batches),
  `vs.net.blob-io` on the kernel's rows (its index, patcher and injected platform deleted), the page-by-ID tier deleted;
  B-tier fixed at its producer. §9 A9 records every departure from §4.2 / §4.4, WIRE.md §7 the corrected wire.
* **2.5 P7** — `vs.net.graft` (GRFT V3, the Bloom, `tracking`), the kernel's read Bloom (`rt-read-tracking!`, `_rb_note`
  in `node_get`), `remote_atom.vs`: f under tracking, the graft uploaded and named on cas-root, a disjoint winner retried
  at once (§9 A10).
* **2.6 A3–A5** — `broker_sim_test.py` rows over `net_client.vs`'s network-atom ops; the lease read from the object's
  SQLite with workerd stopped (no new unauthenticated route); production's CORS on the broker (`wrap-cors`).
* **2.7 P13** — the mirror is one vs.dom program in the page host's Worker (`core_worker.vs`); `dom.js` serves the
  `fetch` family; `boot.vs` / `core_worker_entry.vs` and their artifact rows deleted; the stager stages four files;
  `cf_entry.vs` relays `/presign` and `/renew-lease`; the gate classifies the wire by origin (§9 A12).

## Defects found on the landing and fixed at their producers (2026-10-16 evening, ET)

The first runs on the landed builder read `files=12 pass=7 fail=1 walled=4`; each red was root-caused in this unit:

| symptom | cause | fix | receipt |
|---|---|---|---|
| `net_remote_atom_test`: `Don't know how to create ISeq from: string` | a byte string whose first byte is 0x01 (a HAMT node image) is a Symbol to the program, so blob-io's `string?` test sent it down the int-vector path | `vs.net.bytes/byte-string?` in blob-io, coordinator, blob-crypto, s3-sign (§9 A9 (i)) | the walk's blobs upload; `net_remote_atom_test` green |
| `net_walk_test`, `net_fixtures_test` wall | the Phase-0 fixture (xxh3 value bands, the doubles row refused) met the new walker | the corpus regenerated by the walker, in a user namespace (§9 A9 (j)); comparisons fail rather than wall on a stale fixture | 22 rows, 217 blocks, 410 pairs; both green |
| two generator rows out of slab | the walker sliced a byte string per slot (one block per byte) | `hex-at` / `zero-at?` read in place | all 22 rows generate |
| a swap after a racer's non-SHA root throws | the graft encoder refused the current key | an unknown current key is zeros in the advisory graft | `conflict-retries-then-wins`, `retries-are-bounded` green |
| the first swap retired the empty map's root | the empty old value was walked | an empty old value retires nothing | the A10 retired set asserted |
| `net_snapshot_test` 1 failure | the test pinned the pre-P8 fault text | the by-key fault asserted (names the key, "no block tier") | green |
| `fetch_on_miss_test.py` run 2 out of slab (past 2 GB) | `bytes/join` copied a `subvec` per level (~24 KB of slab per fetched byte); address checks used a pure-`.vs` SHA-256 | index-range joins; the kernel's `rt-crypto-sha256` for every content digest (§9 A9 (k)) | `checks=6 pass=6` |
| A5: the broker answers `/presign` 500 after a few swaps | the broker Worker ran out of slab: `vs.net.s3-sign` built a vector per byte for its pure-`.vs` SHA-256 (~11 digests per URL), and its URI encoder and hex built one string per character, none of it ever freed | SHA-256 and HMAC on the kernel's `rt-crypto-sha256` over byte strings, the signing key's HMAC pads cached, text that needs no escape answered as itself, one join per hex / escaped string, one date / credential / key per batch | 32-key `/presign` calls before the Worker's first 500: 4 → 75 (kernel digest) → 152 (the rest); `s3_sign_test` green |
| `tl_01b` walls on its empty payload | `rt-block-text` answers nil for zero bytes | the row, in Phase 3's frozen build (item 7) | open until that build |
| gate 10 `LINK FAIL` | the bound was chain19's; two later landings and this one grew hello | re-based at 272,722 B, three spans section-exact | gate 10 OK |
| gate 11 | two stray markers (this lane's), one unmarked `vs.` name | markers removed, the name moved to its siblings' `l10.` | gate 11 PASS |

## History: the frozen change list of the one build (kept as written before the build)


* **2.3 B-creds on the client** and **2.1 P3 + P5 + P6 + P11** — see the commit of 2026-10-16 (vs.net.bytes,
  vs.net.snapshot, vs.net.remote-atom; vs.net.coordinator/presign + renew-lease, fetch-credentials deleted;
  vs.net.blob-io on a URL supplier with the HEAD dedup; blobstore.py's /presign route). Receipts in the commit.

## The one build of units 2.2 + 2.4 (CLAUDE.md rule 22: the change list, frozen BEFORE the build starts)

The kernel (`vs/scripts/kernel-rebuild` → `wat2wasm` → `kernel.wasm`, ~2 min) and the program runtime (the template
cut inside `vs/toolchain/chain`, 7–13 min, then the eight landed files) carry ALL of the following in one run:

1. `eve.vs` — THE SHA TABLE: a region claimed on first use like the CID-to-region table (`_st_root`, header cell
   `EVE_SHA_TABLE_ROOT_OFF` 180 — a free cell between `REGION_FL_OFF` 176 and `EVE_REGISTRY_ATOM_OFF` 192), an
   open-addressed hash of 16,384 entries × 48 B keyed by the SHA's first u32: entry +0 state (0 empty · 1 being
   written · 2 ready) · +4 the block offset · +8..15 zero · +16..47 the SHA-256. `_st_find(sha_ptr)` → the offset
   whose block still carries that SHA @80, else 0; `_st_insert(sha_ptr, off)` with the page table's CAS protocol.
2. `eve.vs` — `eve_block_adopt(src, len, sha_ptr)` → the offset of a fresh block of class `size_to_class(256 + len)`
   (−3 when no class holds it: a block past 9,984 image bytes), its ExtCID written field by field (the region's page
   CID @0, inner @16 = its own offset, 20..31 zero, VERSION 2 @32, 36 zero, FLAGS ACTIVE @40, 44 zero, 48..79 zero,
   the SHA @80, 112..255 zero — every field written, nothing assumed zero: rule 12), the image copied @256,
   `sha256(image) == sha` verified (else the block freed and −2), the entry inserted (−4 when the table is full), the
   offset answered. A SHA already in the table answers its offset without allocating.
3. `eve.vs` — `eve_slot_resolve`: in the "reference by content" arm (CID 0, inner 0, ACTIVE, not EMBEDDED, SHA @80
   set) the SHA table is consulted BEFORE answering −1; a hit patches the slot in place (inner = the offset, CID = the
   offset's region page CID through `_eve_page_cid_to` — the bytes every seal masks, so no digest moves) and answers
   the offset. `_page_fault` is unchanged: the marker's CID is zero and the slot sits at +24.
4. `program_runtime.vs` — `pm_serve`: when the marker's CID (+8..23) is zero and the slot at +24 carries a SHA @80,
   the BLOCK tier (header cell `PR_BLOCK_TIER_OFF` 184; `(vs.core/rt-block-tier! f)` installs it) is asked `(f
   "<64 hex>")`; a truthy answer means it adopted the block (through `rt-block-adopt!`), and the read is retried;
   no tier → `java.lang.IllegalStateException` "block <sha> is not resident and no block tier serves it"; a falsy
   answer → "… the block tier did not adopt it". The depth bound and the pin are the page path's.
5. `program_runtime.vs` — the rows: `(vs.core/rt-block-text off n)` the n raw bytes at off as a byte string (nil
   outside the slab); `(vs.core/rt-block-adopt! image sha-hex)` → the offset or a negative number;
   `(vs.core/rt-block-lookup sha-hex)` → the offset or nil; `(vs.core/rt-block-tier! f)`.
6. `header-map.vs` — the two cells' rows.
7. The WAT mirrors (`scripts/dsl-port-gate` IDENTICAL), `scripts/check-import-dag` green, `kernel.wasm` and the eight
   landed files copied, `scripts/check-artifact-freshness` FRESH, `.unresolved-callees` re-read.

Consumers written and unit-gated on the fast path BEFORE the build (the standalone `.vs` over the current builder,
with the rows stubbed where a row does not exist yet): `vs/src/vs/net/walk.vs` (the walker over `rt-block-text`,
lifted from `vs/scripts/gen-net-fixtures.vs`), `vs.net.blob-io`'s block doors (`fetch-blob` → `rt-block-adopt!`, the
index → `rt-block-lookup`, the injected platform deleted), `vs.tier/block-tier`, and the tests that read them.

### 2.2 measured before the build (2026-10-16)

- `cd vs && bin/vs test net_walk_test net_fixtures_test` [ran]: `net_fixtures_test` passes from `vs/` (the fixture now
  lives at `vs/test/vs/fixtures/vsv2-blocks.edn`; `eve/test/eve/fixtures/vsv2-blocks.edn` is a symlink to it; the
  generator's default output moved with it). `net_walk_test` WALLS on the page path: `no page CID for region 16 of
  offset 16868032` — the test program's values land in a region without a page header, which `rt-page-cid-at`
  answers nil for (the generator never saw this: one row per process keeps every value in the early regions). The
  page path is the pre-build reader only; `kernel-read` (`rt-block-text`, raw slab bytes) replaces it when the build
  lands, and the test is re-run then.
- `vs compile src/vs/net/walk.vs` [ran]: 6 unresolved callees, all unit 2.4 rows (`rt-block-text` ×4,
  `rt-block-adopt!`, `rt-block-lookup`) — resolved by the build.
