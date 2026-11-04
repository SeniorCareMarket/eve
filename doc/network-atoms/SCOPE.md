> **Historical record** — the network-atom lane's scope, revision 2 (2026-10-16). It describes eve as it stood then, not eve 0.9;
> what it scoped is built (`src/eve/net/*`). Current authority: `doc/network-atoms/README.md` (the user guide), `WIRE.md`,
> `ADAPTERS.md`.

# EVE network atoms: scope (revision 2)

2026-10-16, ET. Revision 2 replaces revision 1 (`4a336e71c`), which was wrong in its foundations (§0). Main was at
`739d79cb1` when this lane started and is at `ffc9401b4` now (docs-only since). Box: Claude Code cloud container,
Node 24.22, wasmtime 45.0.1, OpenJDK 21, bb 1.13.226.

**Provenance tags.**
* **[ran]**: executed on this box this session; the command and result are in §10.
* **[read]**: I opened the file at the cited commit this session.
* **[agent-read]**: a history agent of this session read it in git and I did not re-open it. I spot-checked these
  claims before using them: the 04-20 revert, the line counts, the 13/13 receipt, the CLJS broker's fields, and the
  ExtCID layouts.
* **[unverified]**: inference.

Citations are `commit:path:line`, or `path:line` at main.

---

## 0. What revision 1 got wrong

Revision 1 scoped from a shallow clone of one commit. It treated "not in the tree at BASE" as "does not exist".

1. **"There is no VS network atom."** Wrong. Network atoms worked in VSv1 end to end: live against
   `dev.atom.broker` + Tigris on 04-18/19 (§1.2), and in local suites that I re-ran green today (§1.3). The code
   was deleted from main as part of retiring JS-era trees (10-03, 10-08). It is in git and it runs.
2. **"The simulator is dead."** Half wrong.
   * VSv1's `simulation.vs` never served a request (§1.6).
   * The VSv2 simulator is the sim/WASM spike (`spike/`): its `demo.sh` passes all 16 checks here [ran].
   * The broker's worker half runs on the wasm leg: `test/broker/run` is 21/21 [ran].
   * What is missing is the real coordinator Durable Object (DO) on VSv2, and the network-atom client (§3).
3. **"The field-name mismatch causes silent data loss."** Misframed. The production broker (`eve-db`, the CLJS
   coordinator behind `dev.atom.broker`) speaks the client's dialect, `old_key`/`new_key`
   (`b80bb555f:old/src/eve/coordinator.cljs:960-963`) [read]. Only `services/atom-broker`'s VS port renamed the
   fields (`root_key`/`new_root_key`, `services/atom-broker/src/broker/coordinator/atoms.vs:48-49`), and that
   port was never deployed. My probe's lost-update result is true of that port only (§4.4).
4. **"Look at `eve/`."** Wrong EVE for network atoms. `eve/` (imported 10-15 from SeniorCareMarket/eve `c7dc390`)
   has no network code and uses Murmur3 (`eve/src/eve/hamt_util.cljc:5`) [read]. The network-capable EVE is
   `eve.atom`, which lived in this repo until 07-30 (§2). The full picture of what matches VSv1 is in §2.3, and it
   is more mixed than "brought to parity" suggests.
5. **The host-capability survey should have come before the report.** It is in §6, measured where possible.

**Corrected after John's review of revision 2.** Revision 2 put four settled things up as decisions:

6. **Remote atoms (G8) and grafting (G10) were never closed.** John: "I never meant for that stuff to be 'closed.'"
   They are open port work (§3.1).
7. **`vs.tier`'s page tier is not a design option.** It is the port-gap lane L14's port of VSv1's fetch-on-miss
   (`492f1c24e`, 10-13, "vs.tier (the page tier, C18 library)") [read]. It changed VSv1's unit: whole 1 MiB pages
   by page ID, where VSv1 fetched single blocks by SHA-256. Nobody authorized a different design. It is a port
   that deviates from VSv1, and it is also broken (§3). It gets corrected to VSv1's behaviour (§4.2).
8. **The 256-byte ExtCID is decided and current.** It landed 04-18 (`b4939a271`, "flip ExtCID layout from 128 B to
   256 B") [read], is on main, and the VSv2 kernel uses it. Revision 2 told the port to trace against
   `0621c9989`, which predates it. That would have meant reverting a decision. The port follows the current design
   (§1.1); `0621c9989` is only the record of the features the 04-20 merge dropped. The 128-byte data in the live
   mirror atom is just what that old deployed build wrote. It is not a migration question.
9. **The simulator runtime is not a decision.** "The simulator" is the sim/WASM spike (`spike/`, branch
   `claude/sim-wasm-spike`) plus the `sim-fixes` lanes that worked its defect list. It is green here (§3). Revision
   2 proposed running the broker on a different host instead. That was off target. What the sim still lacks is
   in §5.

---

## 1. The reference: VSv1 network atoms

### 1.1 The current design, and the 04-20 regression

* Main's net stack reached its working form on 04-18/19.
* On **04-20 20:39 ET**, merge `4cc1388b7` brought in the aegis branch with `-X ours`. Its message reads "Merge
  strategy: ort -X ours … main's side loses" [read]. PR #187 (`13c4685aa`) carried that onto main.
* Line counts at `0621c9989` → `13c4685aa` [ran `git show … | wc -l`]:

  | File | Before | After |
  |---|---|---|
  | `remote_atom.js` | 823 | 679 |
  | `sio_net.js` | 885 | 678 |
  | `blob_io.js` | 493 | 411 |
  | `network_tier.js` | 237 | 229 |

  The pre-merge `0621c9989` and `477b5e55a` are identical in these files [ran].
* What the merge dropped [agent-read]: the per-block upload walker in `remoteSwap`, `fetchMissSync`,
  `fetchBlobsBatchSync`, `createBrowserNetTieredSlab`, the injectable signer, and `_preloadedShaAddrs`.
* Everything later descends from the reverted copies:
  * `b80bb555f` (the pre-pivot tip that coop-atom uses as VSv1);
  * `1b37d45e` (the port-gap analysis's VSv1);
  * the 07-24 `.vs` twin, `src/vs/net/remote_atom.vs`. Its `v3-delta` docstring says "compound value types inside
    KV data are not walked", and it uploads only the snapshot and the graft (`19371a631:src/vs/net/remote_atom.vs:438-464, 562-564`) [read].
* The tests prove the regression (§1.3): two suites green at `0621c9989` fail at `1b37d45e`.

**What to trace against.**

* **The target design is the current one.** That means the latest VSv1 network code
  (`19371a631:src/vs/net/remote_atom.vs`, `ba91dce91^:vs/src/vs/core_surface.vs`) on the current formats: 256-B
  ExtCID v2 (`b4939a271`), the kernel's block layout and the aegis fields. Nothing goes back to `0621c9989`'s
  128-B format.
* **`0621c9989` is the record of the features the 04-20 merge dropped**: per-block seal + upload of every changed
  block, `fetchMissSync`, `fetchBlobsBatchSync`, and the browser net slab. Those are bugs in the latest code. They
  get re-done on the current formats, with 0621's code, tests (§1.3) and live receipts (§1.2) as the spec of how
  they behaved.

### 1.2 Live evidence

* **Gate, `5ef13416d` (04-18 21:48 ET)** [read]: "13/13 assertions passed · byte totals: broker=1452B across 4
  resps; tigris=74160B across 290 resps · 51:1 Tigris:broker byte ratio. 290/290 Tigris responses presigned + 200
  + non-empty". This is the CLAUDE.md "13/13 … 290 presigned blob GETs" line.
* **Writer** [agent-read]: `scripts/backfill-mirror-atom.mjs` (`remoteAtom` + `remoteSwap`, `5ef13416d:…:48`) and
  `scripts/backfill-mirror-tree.mjs` (seals every block, HEAD-check, PUT, then raw cas-root). Receipt in
  `599665353`: "Verified against dev.atom.broker".
* **`0621c9989` (live worker, vec-of-HAMTs)** [agent-read]: "99 Tigris GETs in bursts [3, 32, 32, 32] … final
  rendered msgs: 50 / 50 … compare-live gate still 4/4 green."
* **Production today** [ran]: `https://dev.atom.broker/health` → 200, and `https://clojurians-mirror9.john-d6c.workers.dev/` → 200.
* **Limits of the live proof** [agent-read]:
  * The live write was one swap from an empty atom; the conflict path was never exercised live.
  * The browser read used an eager async prefetch into `_preloadedShaAddrs`, not a synchronous fetch-on-miss.
  * Persistence was off: an OPFS allocator defect (`5ebfcac6f`).

### 1.3 Local suites, re-run here

Worktrees `/home/user/vsv1-0621` and `/home/user/vsv1-1b37`, with npm deps `hash-wasm ws acorn …` (§10).

| Suite | `0621c9989` (pre-revert) | `1b37d45e` (post-revert) |
|---|---|---|
| `test/net/remote_swap_v3_test.js`: remoteSwap end to end against an inline coordinator + blob store (read tracking → seal → V3 graft → upload → CAS → conflict recovery) | **4/4** | 4/4 |
| `test/net/swap_graft_test.js`: `graft_sha` on CAS, the winner's graft on 409, watcher broadcast + reconnect | **6/6** | 6/6 |
| `test/net/ws_graft_test.js`: WebSocket graft push via `test/net/local_server.js` | **8/8** | 8/8 |
| `test/net/blob_encrypt_test.js` | **6/6** | 2/6 (`node:crypto not available`) |
| `test/eve/network_tier_test.js` | **6/6** | 6/6 |
| `test/eve/net_graft_test.js` | **5/5** | — |
| `test/eve/network_fetch_on_miss_test.js` | **5/5** | 2/5 (`unresolved ExtCID but SHA is zero`) |
| `test/eve/network_fetch_on_miss_sync_test.js` | 2/4 | — |
| `test/eve/graft_v3_test.js` | — | 11/11 |
| `test/eve/tiered_eviction_e2e_test.js` | — | 5/5 |
| `test/eve/network_tier_presign_test.js` | — | 4/4 |
| `test/net/cross_thread_readset_test.js` | — | 7/10 |
| `test/eve/net_tiered_slab_test.js`, `test/tiered-atom/phase-1/2*.test.js` | skipped: needs the `mmap_cas` native addon, not built here | skipped |

| `test/tiered-atom/phase-5-s3-sign-browser.test.js` | **6/6** | — |
| `test/tiered-atom/phase-3-sync-coordinator.test.js` | timed out at 300 s, no verdict | — |

The two `network_fetch_on_miss_sync` failures and the phase-3 timeout at `0621c9989` are not root-caused here.

### 1.4 The client stack at `0621c9989` (the record of how the dropped features worked; formats there are pre-v2)

All rows [agent-read], with spot-checks [read] as marked.

| Piece | Where | What it does |
|---|---|---|
| Remote atom | `src/vs/net/remote_atom.js` (823 lines) | `remoteDeref` (310-352), which releases the lease right away. `remoteSwap` (364-711): read Bloom (4096 bits) around `f`; **seal + upload every allocated block, plus a universal walker over map/set/RRB-vec/list** (447-575) [read: 447-460]; snapshot + graft + blocks uploaded in parallel; `:witness-fn`; cas-root; 409 → fetch the winner's graft, `graftPossibleV3` disjoint → retry without backoff, else `min(10·2^n, 2000)·(0.5+rand)` ms; 403 → `PolicyDeniedError`; `MAX_RETRIES 50`. `register` wraps core `deref`/`swap!`/`reset!` (763-823). |
| Atom block | `remote_atom.js:44-115` | 136 B: `0xA0`, version u32 = epoch, two **64-B** slots, root SHA at slot+32. It is sent as `atom_blob`, but **the production broker never reads it** (it is not in `coordinator.cljs:960-963` [read]). |
| Snapshot | `remote_atom.js:170-245` | `VSNP`: magic, count, then `[klen][k][vlen][v]` per top-level entry. The root key is SHA-256(snapshot). |
| Coordinator client | `src/vs/net/coordinator.js` | Wire per §1.5; `SKEL` skeleton codec, big-endian (104-149); credential cache 40 s. |
| Blob I/O | `src/vs/net/blob_io.js` | Presigned fetch/upload by SHA; per-slab SHA index; `fetchBlobsBatchSync` (216); encryption hooks. |
| Signing | `src/vs/net/s3_sign.js:182`, `s3_sign_browser.js:144` | SigV4, path-style `/{bucket}/{sha}`, no prefix. |
| SwapGraft V3 | `src/vs/eve/graft.js:13-56` | `GRFT` header (24 B), old/new root SHA, three counts, three lists of 32-B SHAs; `graftPossibleV3` = loser Bloom vs winner's branch ∪ retired SHAs. Same layout as `ba91dce91^:vs/src/vs/core_surface.vs:1212-1271` [read]. |
| Network tier | `src/vs/eve/network_tier.js` | **Block-level**: "NOT a page pool — blobs are individual bytes keyed by SHA-256. Lower tiers (L3 mmap/OPFS) cache pages holding blobs" (`1b37d45e:…:1-10`) [read]. |
| Fetch-on-miss + sibling batch | `src/vs/eve/sio.js:597-640`, `sio_net.js` | `readChildAddr` catches "page not found", then the hook fetches. A 32-sibling batch (`SIBLING_SCAN_MAX 32`). Sync path via `Atomics.wait` (`fetchMissSync` 726-806). |
| LRU tiers | `heap_lru_pool.js`, `sab_lru_pool.js`, `mmap_lru_pool.js`, `opfs_lru_pool.js`, `three_tier_page_pool.js`; factories `alloc.js` `createNetTieredSlab` (1402), `createBrowserNetTieredSlab` (1291) | Memory LRU → disk LRU (mmap or OPFS) → network. This is the tier stack the network-atom design describes. |
| Watch | `src/vs/net/graft_watcher.js` | `…/atoms/:aid/watch?token=`; handles only `type:'graft'`; ping/pong; backoff. |
| Encryption | `src/vs/eve/crypto.js:559-610` | HKDF-SHA256(GAK, nonce, pageId‖slotIdx), AES-256-CTR, IV = SHA-256(key)[0..16). `remoteSwap` passes no ctx; the mirror runs `encrypt:false`. |
| ExtCID | `src/vs/eve/constants.js:240`, `sio.js:378-387` [read] | **128 B (v1)**: page CID@0, inner@16 (3 B), flags@19, version@20, SHA@32, Poseidon@64. v2 (256 B) arrived with the 04-20 merge. |
| Reader app | `src/clojurians/mirror/core-worker.js` | Prefetch skeleton (BFS ≤ 500), then a 32-parallel viewport fetch, on a plain SAB slab + NetworkTier. |

### 1.5 The production wire

This is what `eve-db` at `dev.atom.broker` serves: `b80bb555f:old/src/eve/{worker,coordinator}.cljs`, worker
`old/wrangler.toml` name `eve-db`, route `dev.atom.broker` [read]. The VSv1 client and `eve.atom` both speak it.

* Every call is `POST {endpoint}/v1/domains/:d/…` with a `Bearer` header:
  * `atoms/:a/create`
  * `atoms/:a/deref` → `root_key root_blob(b64) epoch lease_id root_count skeleton_* branch_blobs freelist_*`
    [agent-read]
  * `atoms/:a/cas-root {atom_id old_key new_key retired_keys root_blob skeleton_blob skeleton_sha freelist_sha freelist_blob root_count}`
    (`coordinator.cljs:960-963`) [read] → 200 `ok`. On conflict it answers 409
    `{status conflict, current_key, current_epoch, root_blob?, skeleton_*?, freelist_*?}` (`:384-396`) [read];
    on policy refusal, 403.
  * `release-reader {lease_id}` (lease TTL 60 s on the server)
  * `credentials`
  * WebSocket `…/watch`, pushing `type:"root-changed"` (`:775-800`) [agent-read]
* The VSv1 client additionally sends `graft_sha`, `atom_blob` and `witnesses`. The CLJS broker ignores all three,
  so live conflicts fall back to jittered retry [agent-read; inference from the destructure, which I read].
* **Watch types disagree everywhere:**
  * production CLJS: `root-changed`;
  * the VSv1 client listens for `graft`, which only `test/net/local_server.js` sends;
  * `services/atom-broker`: `root-change`.

  So in production the VSv1 client never consumed a watch event [unverified: inference].
* **Blob keys disagree.** VSv1 uses a bare `<sha>`; `eve.atom` uses `blobs/<sha>`
  (`323ab7ab9:eve.atom/src/eve/tiered_slab_net.cljc:253`) [read]; the `services/atom-broker` proxy uses `blobs/<hex>`.

### 1.6 Local simulators in VSv1

* `test/net/local_server.js`: coordinator + S3 in one Node process, plus WebSocket.
* `test/net/atom_server.js` + `blob_server.js`: split stand-ins ("dev.atom.broker — Coordinator DO simulator").
* Inline servers inside tests.
* `PeerServer` + `NativeBroker` + `BlobBroker` (the aegis chat stack).

These are what the green suites in §1.3 ran against [agent-read; ran for the tests named].

`services/atom-broker/simulation.vs` (04-16) **never served a request**: "the first non-public request … returns
HTTP 500 … `s.startsWith is not a function`" (`2faa074fd:docs/tiered-lru/bug-vlj-fetch-keyword-dispatch.md`,
status "parked") [read].

---

## 2. The EVE network client: `eve.atom` at `323ab7ab9` (04-12)

All [agent-read] unless marked. The full report is in this lane's history; this section keeps what the port needs.

### 2.1 Where and what

* `eve.atom/` is a copy of SeniorCareMarket/eve v0.1.0 embedded on 03-19 (`6899fb519`).
* The network work was all done here (03-24 … 04-12). It was moved to `old/eve.atom/` on 04-17 and deleted with
  `old/` on 07-30 (`ea05f4639`). Final tree at `ea05f4639^:old/eve.atom/src/eve/`.
* It was never upstreamed. `eve/` on main is unrelated (Murmur3, no network) [read].
* No branch carries a newer Clojure EVE with network code. (The checked branches are listed in the agent report:
  `eve/type-system`, the squint branches, `zf11`, `knceve`, `zero-js`, `test-eve-node-mmap-contention`.)

Modules:

| Module (lines) | Role |
|---|---|
| `tiered_remote_atom.cljc` (761) | `TieredRemoteAtom` deftype: IDeref, ISwap, IReset, IWatchable (WebSocket on first watch), ILeaseStore. Async and sync swap (`MAX_RETRIES 1000` [read: :33]; jitter ≤ 2 s). Graft on 409 when the read-set is disjoint. JVM arm is blocking and has no watcher. |
| `tiered_slab.cljc` (707) | `TieredSlabIO`: an **ISlabIO wrapper** that prefixes every block with a 64-B self-ExtCID. `-sio-seal!` = SHA-256 of the content. `-sio-read-child-addr` memcmps the expected SHA and returns nil to trigger fetch-on-miss. |
| `tiered_slab_net.cljc` (2146) | The wire (§1.5 dialect; `old_key`/`new_key` [read: :1164-1165]). Local SigV4 with `blobs/<sha>` keys [read: :253]. Fetch-on-miss with sibling batch; `diff-trees` (O(changed path)) for upload; skeleton; `graft-possible?`; WebSocket watcher handling `root-changed` [read: :1799]; a JVM arm on `java.net.http`. |
| `paged_alloc.cljc` (1927) | Backing slab on SAB / WebAssembly.Memory / JVM heap / mmap. ExtCID **64 B**: page CID@0..15, inner@16, flags@19, page size@20, SHA@32 [read: :19-27]. |
| `future_fetch.cljc`, `lease.cljc`, `remote_config.cljc`, `remote_atom.cljc` (`$`), `s3_sign.cljc`, `lru_cache_slab.cljc` | Blocking fetch on cljs-thread + `Atomics.wait`; lease store; config (inline map / EDN / env); SigV4 (WebCrypto async, node crypto sync); a write-through LRU, which the network factory does not use. |

### 2.2 How far it worked

* **Last live run: 04-05 03:01 ET** (`820dec16b`): "All 9 tiered-remote tests pass … All 13 e2e-cloud tests pass …
  All 495 local tests pass".
* Browser mirror1 against `dev.atom.broker` passed on 04-05 17:53 (`33784522b`).
* **After 04-05: local or mocked runs only.** That window includes the xxHash128 switch (04-05 09:35) and the
  self-ExtCID prefix (04-05 12:58).
* Defects on the conflict path [agent-read; inference]:
  * The production broker's 409 throws in `json-fetch` before the graft branch is reached, so every conflict
    re-runs `f`.
  * `diff-trees` does not walk nested `0xED`/`0xEE` headers.
  * The JVM `commit!` never uploads blocks.
  * The JVM "xxhash128" is SHA-256 truncated, with the comment "NOT wire-compatible".
  * `:encrypt?` is resolved and then dropped.

### 2.3 Parity with VSv1: what git shows

| | `eve.atom` (04-12) | VSv1 `0621c9989` (04-19) | VSv2 kernel (main) |
|---|---|---|---|
| HAMT node header | 12 B | 12 B (`hamt.js:44`) [read] | 256-B self ExtCID + 13-B header (`vs/src/vs/kernel/wasm/hamt.vs:41-50`) [read] |
| Per-entry nav hash | 16 B XXH3-128 (hash-wasm) | 16 B XXH3-128 (`hamt.js:49`) [read] | 16 B xxh3_128 (`hamt.vs:51, 486-488`) [read] |
| ExtCID | 64 B, SHA@32 | **128 B**, SHA@32 [read] | **256 B**, SHA@80 (= VSv1 v2 from 04-20: `1b37d45e:src/vs/eve/sio.js:401-413`) [read] |
| Map root header | 44 B | 136 B (`hamt.js:46`) [read] | 517-B root block (`hamt.vs:33-37`) [read] |
| Content address | SHA-256 of the block content | SHA-256 of the sealed block | SHA-256 @80 of the slot (filled on the default path since L14 C5 [they report]) |
| Network root key | SHA of the root node | SHA-256(VSNP snapshot) | — (no client) |
| Blob key | `blobs/<sha>` | `<sha>` | `<sha>` (`vs/src/vs/net/blob_io.vs:102-103`) [read]; pages `pages/<cid>` (`vs/src/vs/tier.vs:143`) [read] |

**The verdict, stated plainly.**

* `eve.atom` and VSv1 share the HAMT node header and the 16-byte XXH3-128 navigation hash.
  * Seed compatibility, native ↔ seed maps, was verified 04-07 (`448bb9fe1`).
  * The 04-08 "Valjean + Eve on one slab.bin" tests ran Valjean's own JS as the "Eve" worker (`cbf9aacc4`'s message).
* After that the formats diverged:
  * ExtCID: 64 → 128 → 256 B;
  * root headers;
  * VSv1's whole-value snapshot root;
  * blob key prefixes.
* No `eve.atom` ↔ VSv1 network interop test exists.
* The "VSv1 and VSv2 together on disk" that runs today is coop-atom: EVE (JVM/Node/bb) plus `vs.eve-disk`. Both
  write **`eve/`'s Murmur3 disk format** (`vs/src/vs/eve_disk.vs:1-30`) [read], not VSv1's.

No Clojure EVE has ever been at the current format. The work (John, 10-16): **use `eve.atom` as the design and
bring it up to parity with the current VSv2** (§6).

---

## 3. VSv2 today: measured

| Area | State | Evidence |
|---|---|---|
| Coordinator client `vs.net.coordinator` | ported, runs natively | `vs/src/vs/net/coordinator.vs:215-308` [read]; `tl_02b/c/d`, `net_run_test` pass [ran] |
| Blob I/O + encryption `vs.net.blob-io`, `vs.net.blob-crypto`, `vs.net.s3-sign` | ported, run on a real host | `vs/test-hosts/wasmtime/blob_io_test.py` **10/10** against `blobstore.py` [ran]; `blob_enc_test` pass [ran] |
| Host transport `vs.host` `fetch` | works | `wasmtime/fetch_test.py` **4/4** [ran]; probe `coord_wire.vs` over real HTTP converges 10/10 against a client-dialect broker [ran] |
| ExtCID 256 B with page CID + SHA on the default path; CID-resolving readers; page fault + adopt | ported in kernel (L14 C3–C6) | `docs/port-gap-closure/coordination/reports/L14-round3-report-2026-10-13.md` "kernel-direct … 69/69" [they report] |
| Page tier `vs.tier` (fetch-on-miss by page CID, working-set LRU, SQL/browser stores) | **blocked**: one defect | `wasmtime/fetch_on_miss_test.py` **1/6**; run 1 traps in `vs.tier/page-text` → `rt-vconj` [ran]. `workerd/do_pages_test.py` **4/11**; writing pages traps out of bounds in the same frame chain [ran] |
| Cloudflare hosts | Worker good, DO leaks | `workerd/worker_test.py` **16/16**; `workerd/do_test.py` **9/11**: SQL, eviction, alarm, trap recovery pass; the 10,000-request soak traps at request 2,082 with memory 125 MB → 1.35 GB [ran] |
| Broker worker half on the wasm leg | green, with a stubbed DO | `test/broker/run` **21/21** files [ran] |
| Coordinator DO (`services/atom-broker/src/broker/coordinator/*.vs`) | **not ported** | JS-host idiom (`js/Response.`, `.queryOne`, `js-await`) [read]; `vs compile core.vs` → `no source for namespace vs.uuid` [ran]; dialect drift §0.3 |
| Simulator | **spike green**; real broker not on it | `spike/demo.sh` **PASS, 16 checks** (swap round trips, no lost update under a race, 422 content-address refusal, 401, restart durability, trap containment) [ran]. Its next step 7: "Port the real coordinator onto this ABI" (`spike/SPIKE-NOTES.md:554-587`) [read] |
| Remote atom (deref/swap!/reset!, atom block, snapshot, retry/backoff, 403) | **not ported** | gone from main at `78dc666bf`; port-gap G8 "by-ruling" (§3.1) |
| SwapGraft V3 + read-set Bloom + graft watcher | **not ported** | gone at `ba91dce91` (`core_surface.vs`, "could not compile at all") [read]; G10 "by-ruling" |
| Block-level network tier + 32-sibling batch | **not ported**; L14's `vs.tier` fetches pages by ID instead | §4.2: a deviation, corrected by P8 |
| Memory → disk → network LRU stack | partly: `vs.tier` working-set policy (`:file` under `vs-eve-host`, `:memory`) plus SQL/browser stores (`tier.vs:20-75`) [read] | heap/SAB/mmap/OPFS/SQLite pools deleted 09-04 (`586d15f78`) [agent-read] |
| WebSocket watch | no host family | `vs/src/vs/cf/worker.vs:158-159` [read] |

### 3.1 G8 / G9 / G10 are open

* Port-gap G8 (remote atoms), G9 (peer/broker/sync) and G10 (grafting) are mislabelled "by-ruling" in the ledgers.
  The cited "ruling" is the deletion report `vs/docs/RETIRE-SRC-VS-REPORT.md:8-12`
  (`docs/port-gap-analysis/evidence-ten-readers.md:131, 134`) [read].
* John never closed them (§0.6). They are open port work. The ledger rows
  (`docs/port-gap-closure/lanes/L14-persistence-tiers.md:20`, `00-master-plan.md` L14 row) need correcting in
  the same change that starts the work.

---

## 4. The port-gap trace (current VSv1 design → VSv2)

### 4.1 Item by item

| # | VSv1 piece (§1.4) | VSv2 counterpart | Gap | Size |
|---|---|---|---|---|
| P1 | coordinator wire client | `vs.net.coordinator` | Done. Pin it to the production dialect (§1.5) with a test against a transcription of `coordinator.cljs`, not against `tl_fixtures`' self-consistent stub. | S |
| P2 | blob I/O, signing, encryption | `vs.net.blob-io`, `s3-sign`, `blob-crypto` | Done. Missing: the batched sync GET (`fetchBlobsBatchSync`) and HEAD-before-PUT dedup. | S |
| P3 | remote atom: deref, swap loop, 409/403, `register` into core deref/swap!/reset! | none | **Port** the latest design (`19371a631:src/vs/net/remote_atom.vs`), restoring what the 04-20 merge dropped from `0621c9989:src/vs/net/remote_atom.js` (§1.1). On VSv2 the value is a kernel tree, so the snapshot, seal and upload are kernel-slab walks. Synchronous by construction (`vs.host` ops block). | M–L |
| P4 | per-block seal + upload walker (map/set/RRB/list) | kernel `seal_block` exists (`hamt.vs:313, 887`) [read]; no walker | **Port** the 0621 walker over kernel blocks. This is what the 04-20 revert lost. | M |
| P5 | VSNP snapshot root | none | Port with P3: the latest VSv1 sends the VSNP snapshot as `root_blob` and keys the root by its SHA-256 (`19371a631:src/vs/net/remote_atom.vs:216-260, 533-548`) [read]. | S |
| P6 | 136-B atom block | kernel atom block, 256-B single slot (`vs/src/vs/kernel/wasm/atom.vs:4, 29, 80-82`) [read] | The production broker ignores `atom_blob`. **Drop it from the wire**, or re-spec it on the 256-B slot. The format really is stale (64-B slots). | S |
| P7 | SwapGraft V3 codec + `graftPossibleV3` + read Bloom | none (codec last at `ba91dce91^:vs/src/vs/core_surface.vs:1212-1271`) | **Port** codec and Bloom to `.vs`. The read Bloom needs read instrumentation in kernel `hm_get`. The production broker does not echo `graft_sha`, so this only pays once the broker carries it. | M |
| P8 | fetch-on-miss + 32-sibling batch (block by SHA) | kernel page fault → `vs.tier` (fetches whole pages by page ID) | Correct the port to VSv1's behaviour: the fault fetches the missing block by its slot's SHA@80 through `blob-io` (with the 32-sibling batch) and places it in a local page (§4.2). | L |
| P9 | memory/disk/network LRU pools | `vs.tier` working set + `vs-eve-host --cache-path` per-page files (C9–C12) + SQL/OPFS stores | Those are the local page tiers VSv1 also had; the network atom's blocks land in them. Prove the bound with a network-backed atom. The `rt-vconj` trap (§4.3) blocks them today. | M |
| P10 | graft watcher (WebSocket) | none; no `ws` host family | Add a `ws` family to `vs.host` + the wrappers. Settle the message type (§1.5). | M |
| P11 | reader lease | wire calls exist | Hold the lease while faulting (VSv1 deferred this as "phase 6"); add a renew route to the worker. | S |
| P12 | credentials | `fetch-credentials` exists | B-creds (§4.3): the client takes presigned URLs from `/presign` instead of keys. The key prefix aligns to the bare `<sha>` the live data uses (`/presign` prefixes `blobs/`, `proxy.vs:72`). | M |
| P13 | the mirror app as acceptance | `src/clojurians/mirror/core_worker.vs` requires `vs.net.remote-atom` and still says `EXTCID 128` [read: :37-42] | Dead on main until P3/P8. Bring it to the current 256-B format along with the rest, then use its 13/13 gate as the end-to-end acceptance, against a mirror atom written by the ported client. | M |

### 4.2 Where the L14 port deviates from VSv1

* **VSv1.** Every sealed block is an object keyed by its SHA-256. A child slot names its child's page, offset and
  SHA. A missing child is fetched as that block, with its 32 siblings, and placed into a page of the local pool.
  The local pools (heap/SAB/mmap/OPFS) cache pages. The network tier "is NOT a page pool — blobs are individual
  bytes keyed by SHA-256" (`1b37d45e:src/vs/eve/network_tier.js:1-10`) [read]. Swaps upload the changed blocks.
* **L14's port (`vs.tier`).** It fetches whole 1 MiB kernel pages by page ID from `pages/<cid>`, checked by a
  `{cid sha}` manifest (`tier.vs:1-19, 118-167`) [read]. That is a different unit from VSv1's, and nothing in the
  plan asked for one.
* **Already in the tree.** `vs.net.blob-io` implements VSv1's form on the current format: 256-B ExtCID SHA@80
  and `patch-child-offset!` (`blob_io.vs:218-300`) [read]. The tree now carries both forms. Per the compiler law
  that is a finding: two ways of doing one thing.
* **The fix (P8).** The fault path resolves a missing child through `blob-io` by SHA, as VSv1 did. `vs.tier`'s
  working-set, eviction and store code keeps the role VSv1's local pools had. Its page-fetch-by-ID path goes. This
  is a correction of L14's port, owned by whoever reopens G6/G8.

### 4.3 Bugs on the path (each a unit, with its repro)

* **B-tier:** `vs.tier/page-text` traps in `rt-vconj`. Repros: `python3 vs/test-hosts/wasmtime/fetch_on_miss_test.py`
  (1/6) and `python3 vs/test-hosts/workerd/do_pages_test.py` (4/11, same frames) [ran].
* **B-do:** DO memory grows without bound: `do_test.py` 10,000-request soak, trap at request 2,082 [ran]. This is
  the port-gap RE-14 family [they report: `docs/port-gap-analysis/hosts.md` HOST-05/09]. A long-lived coordinator
  DO cannot run until it is fixed.
* **B-dialect:** `services/atom-broker`'s DO renamed the cas-root/409 fields away from production
  (`atoms.vs:42-92` vs `coordinator.cljs:384-396, 960-963`) [read]. You said fixes are landing in main; the
  acceptance test in §5 pins the production names.
* **B-watch:** three watch message types (§1.5).
* **B-creds (security):** `POST /v1/domains/:d/credentials` returns the domain's stored Tigris secret key, labelled
  `expires_in 300`; nothing makes it expire (`services/atom-broker/src/broker/blobs/proxy.vs:79-96`) [read]. The
  production CLJS broker does the same [agent-read]. **Fix:** clients take presigned URLs from the broker's existing
  `POST /v1/domains/:d/presign` (`routes.vs`, `blobs/proxy.vs:57-77`) [read], and `/credentials` stops returning the
  secret. Signing stays on the broker; only small JSON crosses it (repo rule 4).
* **B-v1:** `network_fetch_on_miss_sync` 2/4 at `0621c9989` [ran]; not root-caused. It matters only if the sync
  path is ported verbatim.

### 4.4 About revision 1's probe

`probes/coord_wire.vs` + `coord_standin.py` remain valid for what they measure:

* the VSv2 client converges over real HTTP against the production dialect (10 increments, 5 conflicts);
* against `services/atom-broker`'s renamed fields the same client loses updates silently.

The second line describes an undeployed port, not production.

---

## 5. The simulator

**What it is.** The sim is the sim/WASM spike (`spike/`, branch `claude/sim-wasm-spike`, merged `d9fe8c689`).

* Node A is the broker's Worker + COORDINATOR object.
* Nodes B and C are clients.
* Every node is a VSv2 wasm guest under a host that gives it Cloudflare's shape (`spike/README.md`,
  `spike/SPIKE-NOTES.md` §2–§4) [read].
* The `sim-fixes`, `sim-fixes-2` and `sim-fixes-3` lanes worked its defect list. All are in main
  (`spike/SPIKE-NOTES.md` §6 → `FIXES.md` on `claude/sim-fixes-3`) [read].

**State here: green.** `spike/demo.sh` → "RESULT: PASS (0 failed checks)", 16 checks [ran]. They cover real
`swap!` round trips, a lost-update race (409s, no lost update), the coordinator's own 422 content-address refusal,
401, restart durability, trap containment, a byte-exact non-ASCII round trip and the 413 size refusal.

**What it does not yet carry** (its own next-work list, `spike/SPIKE-NOTES.md:554-587`) [read]:

* **The real coordinator (item 7).** Node A runs `spike/guest/coordinator.vs`, a stand-in for
  `services/atom-broker/src/broker/coordinator/*.vs`. The real one is still JS-host code (§3). The broker's worker
  half already runs on the wasm leg (21/21, §3).
* **Blobs.** The sim's value rides in `root_blob` with a 16 KiB cap. It has no object store and no presigned blob
  I/O. A network atom needs `blobstore.py` (or an equivalent node) beside node A, with the client's presigned
  PUT/GET going to it.
* **A network-atom client.** The sim's client is `spike/guest/client.vs`, a counter. The real client is P3/P4 (§4).

**How the real coordinator gets onto the sim.**

* Everything shipped talks to its host through `vs.host` (`vs/src/vs/host.vs:1-25`) [read]: the broker's worker
  half (`vs.cf.worker`), `vs.net.coordinator` and `vs.net.blob-io`.
* The spike's guests use the spike's own request-file ABI (`spike/guest/abi.vs:1-40`) [read]. It predates
  `vs.host`.
* So node A runs the real broker (worker + COORDINATOR DO) through the repo's Cloudflare wrapper
  (`vs/hosts/cloudflare/{worker,do}.mjs`, built by `vs/scripts/build-cf-worker`). That wrapper already serves
  `vs.host`'s http, do and sql (`do.mjs:18`) [read], under local workerd, the way `vs/test-hosts/workerd/` drives it.
* `vs/test-hosts/blobstore.py` is the object store. Clients are VSv2 programs under `vs-term --bridge`.
* The spike's 16 demo checks become this stack's checks. The spike's stand-in guests (worker, coordinator, client)
  are deleted once the real stack passes them; one implementation of each stays.

**Acceptance: "the sim runs network atoms".** Wire per §1.5.

| Step | Action | Expected |
|---|---|---|
| A1 | create | 200 |
| A2 | deref an empty atom | root_key nil, epoch 0, a `lease_id` |
| A3 | one client `swap!`s a nested map + vector | only presigned PUTs reach the store, keyed `<sha>`; root key = SHA-256 of the root blob |
| A4 | a fresh client with empty caches derefs | value equal; every GET presigned; every blob's plaintext hash checked |
| A5 | two clients on two nodes, 50 increments each | 100; conflicts > 0; no 5xx; leases released |
| A6 | CAS with a stale `old_key` | 409 `{current_key current_epoch …}`; root unchanged |
| A7 | 403 from a validator | the client throws a denied error and does not retry |
| A8 | restart node A, deref | same root and epoch (the demo already checks this for its counter) |
| A9 | the spike's remaining demo checks (401, trap containment, non-ASCII round trip, size refusal) | as in `spike/demo.sh` |

**Can the VSv2 client half pass today?** A1, A2 and A6 pass at the wire level (the probe, §4.4). A3–A5 need P3/P4.
The server half needs item 7.

**B-do applies here.** The DO wrapper keeps one instance across requests (`vs/test-hosts/workerd/do_test.py`:
"three requests answer 1, 2, 3 from one instance") and traps at request 2,082 (§4.3). A5 is about 400 requests,
so the sim runs before B-do is fixed. A long soak does not.

---

## 6. EVE network atoms: `eve.atom`'s design at the current VSv2 format

### 6.1 What gets built

The design is `eve.atom`'s, ported onto the unified `eve/` and moved to the current VSv2 block format.

| `eve.atom` piece (`323ab7ab9`) | Becomes |
|---|---|
| `tiered_remote_atom.cljc` `TieredRemoteAtom` (IDeref, ISwap, IReset, IWatchable, ILeaseStore) | A sibling atom type beside `MmapAtom` in `eve/` (`eve/src/eve/atom.cljc:788-829`) [read]. The local hot path stays untouched. Swap semantics follow VSv1/VSv2 P3: 409 → winner's graft → disjoint retry, else backoff; 403 → throw. |
| `tiered_slab.cljc` `TieredSlabIO` (an `ISlabIO` wrapper: seal, SHA-check, fetch-on-miss) | An `ISlabIO` wrapper over `eve/`'s slab (`eve/src/eve/deftype_proto/alloc.cljc:79`) [read], at the VSv2 layout: 256-B ExtCID, not 64 B. |
| `tiered_slab_net.cljc` (wire, sibling batch, `diff-trees`, skeleton, watcher, JVM arm) | `eve.net.*`: the §1.5 wire (G5), presigned I/O through `/presign` (B-creds), the 32-sibling batch, the O(path) upload walker (P4's), the watcher (P10). |
| `paged_alloc.cljc` (64-B ExtCID, own HAMT/codec) | Dropped. `eve/`'s allocator and registry stay. The VSv2 block format is produced by a network codec at the boundary (§6.2). |
| `future_fetch.cljc`, `lease.cljc`, `remote_config.cljc`, `$` | Ported as they are: blocking fetch on CLJS, leases, config. |

### 6.2 The parity target (what "the current VSv2" is, byte for byte)

EVE's network blocks must equal what the VSv2 kernel seals. These are the sources to match [read]:

* **Common block header:** 256-B ExtCID at 0..255, type tag at 256, payload from 257. ExtCID fields: page CID@0,
  inner offset@16, version@32 = 2, flags@40, SHA-256@80 (`vs/src/vs/kernel/wasm/eve.vs:1488-1531, 1566-1571`).
  Type tags: T_VEC 0x12, T_LIST 0x13, T_ARRAYMAP 0x16, T_ATOM 0xA0, T_MAP 0xED, T_SET 0xEE.
* **Map:** root and bitmap-node layouts, 16-B xxh3_128 key hashes, the kv region (`hamt.vs:33-51`, `:468-500`).
* **Vector:** root and node layouts, RRB (`vec.vs:1-30`).
* **List:** `list.vs:1-25`. **Set:** the kernel layout (`set.vs:18-80`).
* **Value bytes inside nodes:** `serialize_value` / `serialize_key_content` (`eve.vs:3986-4117`).
* **Seal:** `seal_block` (`eve.vs:2000-2060`) under the default policy AEGIS_SEAL 0, the SHA-only seal over the
  content bytes (`eve.vs:1597-1606`).
* **Hash:** `xxh3_128` (`xxhash.vs:571`).

EVE writes this format only at the network boundary. Its local mmap and heap formats stay as the unification fixed
them. The codec translates between the two.

### 6.3 Tiers in EVE terms

| Tier | EVE implementation |
|---|---|
| L0 (memory) | A heap domain (`open-heap-domain!`, `eve/src/eve/atom.cljc:399`) [read], holding materialized blocks |
| L1 (disk LRU) | A content-addressed block cache by SHA (immutable files) |
| L2 (network) | Coordinator root + Tigris blocks |

* deref materializes (`d/eve->clj`), or `$` (`atom.cljc:764`) runs `f` over the lazy view under a held lease.
* Swap uploads O(path) blocks, then cas-root.

---

## 7. Byte-identity gate (Phase 0)

The format is decided: the VSv2 kernel's block layout with the 256-B ExtCID v2 (§0.8). The gate proves that every
implementation that writes or reads network blocks produces the same bytes.

| # | Obligation | Go when |
|---|---|---|
| G1 | **VS ⇄ EVE:** a fixture set (VS-written sealed blocks for maps, sets, vectors, lists, nested values, the shift-30 hash pair, long strings) that the EVE codec decodes value-exact and re-encodes byte-exact, SHA equal, on the JVM, bb and Node | every row, every host |
| G2 | xxh3_128 bit-exactness: EVE's portable xxh3_128 against the kernel's | the kernel's vectors on 3 hosts |
| G3 | SwapGraft V3 + Bloom bit-exactness | 0621 `graft_v3` vectors |
| G4 | encryption (HKDF/AES-CTR/IV) and SigV4 against `blob_crypto.vs` / `s3_sign.vs` | KATs + 3 cross-decrypted blobs, the same URL string |
| G5 | the wire: one spec file (§1.5) that every client and every broker tests against | all implementations green |

---

## 8. Phased plan (dependency order)

Sizes S/M/L are [unverified] estimates from reading.

**Phase 0: wire spec + fixtures**
* Work:
  * the wire spec G5, aligned with the coordinator fixes landing in main (S);
  * the VS-written fixture corpus for G1 (M);
  * correct the G6/G8/G9/G10 ledger rows (S).
* Accept: the spec exists, and P1's test pins it.

**Phase 1: the sim runs the real coordinator** (`spike/SPIKE-NOTES.md` item 7)
* Work:
  * port `services/atom-broker/src/broker/coordinator/*` to Ring + `vs.host` sql, on the production wire (L);
  * the stack driver: workerd with the Cloudflare wrapper, plus `blobstore.py` (M);
  * A1, A2, A6–A9;
  * delete the spike's stand-in guests.
* Accept: those rows green on the sim.

**Phase 2: the VSv2 network atom**
* Work:
  * P3, P4, P6, P11 (L total);
  * P8, correcting `vs.tier`'s fault path to fetch blocks by SHA (L);
  * then P7 (M);
  * A3–A5.
* Accept: the mirror gate (P13) green against an atom the ported client wrote.

**Phase 3: local tiers under the network atom**
* Work: B-tier (`rt-vconj`) (M); P9.
* Accept: `fetch_on_miss_test.py` and `do_pages_test.py` re-pointed at the block path and green; the working-set
  bound holds with a network-backed atom.

**Phase 4: EVE network domain on the JVM, then bb**
* Work:
  * the network codec (G1–G2, M–L);
  * host primitives `http-request!` + `random-bytes` (S). Measured: the JDK's `HttpClient`, `MessageDigest` and
    `newWebSocketBuilder`, and bb's `HttpClient`, `babashka.http-client` and `Mac` [ran];
  * port `tiered_remote_atom` / `tiered_slab` / `tiered_slab_net` onto `eve/`'s seams per §6.1 (L).
* Accept: A1–A8 with mixed VS + JVM + bb clients on one atom.

**Phase 5: watch + Node/CLJS + the remaining hosts**
* Work:
  * P10;
  * the CLJS async→sync seam (`eve.atom`'s `future_fetch` is the prior art);
  * the network probe on cljr, glojure, basilisp, clojerl and jolt (revision 1 §4.2 has the probe line).

**Alongside:** B-do (the DO memory growth) and B-creds (credentials). Both are needed before anything ships.

**First EVE host: the JVM.**
* It is the unification's reference host.
* Every primitive is in the JDK [ran].
* `eve.atom` has a `java.net.http` arm to port from (`tiered_slab_net.cljc:1848-2146`). It is incomplete: no
  uploads, and its "xxhash" is a SHA stand-in.

---

## 9. Open items

None. Every call this scope needed is made above:

* EVE: `eve.atom`'s design at the current VSv2 format (§6).
* The sim's host: `vs.host` via the Cloudflare wrapper (§5).
* Credentials: presigned URLs from `/presign`, never keys (B-creds).

---

## 10. Commands and results (this session)

```
git fetch --unshallow origin main; git fetch origin '+refs/heads/*:refs/remotes/origin/*'
spike/build.sh && spike/demo.sh                                  → RESULT: PASS (0 failed checks) in 24.8s
bash test/broker/run                                             → BROKER-TEST: files=21 pass=21 fail=0 walled=0
python3 vs/test-hosts/wasmtime/{blob_io,fetch,fetch_on_miss}_test.py   → 10/10 · 4/4 · 1/6
python3 vs/test-hosts/workerd/{do,do_pages,worker}_test.py       → 9/11 · 4/11 · 16/16
python3 eve/doc/network-atoms/probes/run_probe.py                → client: final:10 conflicts:5 · do: final:5 conflicts:0
git worktree add --detach /home/user/vsv1-0621 0621c9989   (node_modules → npm i hash-wasm ws acorn @js-temporal/polyfill js-yaml smol-toml poseidon-lite node-gyp-build)
  node --test test/net/{remote_swap_v3,swap_graft,ws_graft,blob_encrypt}_test.js            → 4/4 · 6/6 · 8/8 · 6/6
  node --test test/eve/{network_tier,net_graft,network_fetch_on_miss,network_fetch_on_miss_sync}_test.js → 6/6 · 5/5 · 5/5 · 2/4
git worktree add --detach /home/user/vsv1-1b37 1b37d45e   → blob_encrypt 2/6, network_fetch_on_miss 2/5, graft_v3 11/11, tiered_eviction_e2e 5/5
curl https://dev.atom.broker/health → 200 ; curl https://clojurians-mirror9.john-d6c.workers.dev/ → 200
```
