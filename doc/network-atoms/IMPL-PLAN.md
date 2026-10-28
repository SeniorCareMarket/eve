> **Historical record** — the network-atom lane's build contract (2026-10-16, Phases 0–5, amendments A1…), mostly about the VS
> broker and kernel. It describes eve as it stood then, not eve 0.9. Current authority: `doc/network-atoms/README.md` (the user
> guide), `WIRE.md`, `ADAPTERS.md`.

# EVE network atoms: implementation plan

2026-10-16, ET. The build plan for `SCOPE.md` (revision 2, `509977778`). Branch `claude/eve-network-atoms-impl`,
based on `509977778` merged with `origin/main` at `5f71fa47e` (merge `893acb5`, no conflicts). Box: Claude Code cloud
container, Node 24.21, wasmtime 45.0.1, wabt 1.0.39, OpenJDK 21.0.12, bb 1.13.226, python wasmtime 45.0.0.

**Provenance tags**, as the SCOPE's: **[ran]** executed here (command + result in §0.3 or the phase), **[read]**
opened at the cited commit, **[unverified]** inference. Sizes are line counts of the sources named, never hours.

This file is the lane's contract from its first commit. Deviations are recorded in §9 as amendments, never made
silently.

---

## 0. Ground

### 0.1 Where the lane prompt overrides the SCOPE

| # | SCOPE says | the prompt says | this plan |
|---|---|---|---|
| D1 | §5 A9: "the spike's remaining demo checks" (incl. the 422 content-address refusal) | A9 = 401, trap containment, non-ASCII round trip, size refusal | the prompt's four. The 422 is the spike coordinator's own rule; production (`coordinator.cljs` cas-root!, [read] §1.5) has no such check and the wire spec (G5) is production's. The 422 goes with the spike's guests. |
| D2 | §4 P13: the mirror gate "against a mirror atom written by the ported client" | no production changes: nothing deploys to `dev.atom.broker`, `eve-db` or Tigris | the gate runs against the Phase-1 sim (workerd broker + `blobstore.py`), its endpoint and store host parameterised; the 13 assertions stay. |
| D3 | §8 Phase 0 lists the fixture corpus as "(M)" with no writer named | "which VS-side program writes them" | a `.vs` generator over rows the runtime already has (`rt-addr`, `rt-page-text`, `rt-page-cid-at`, `rt-slot-text` [read] `program_runtime.vs:23914-23975`); no kernel change in Phase 0. §2.3. |
| D4 | §3 "B-dialect: You said fixes are landing in main" | "if a fix has since landed, cite it; if not, the dialect correction is Phase 0/1 work" | none landed (§0.2). The correction is Phase 1 unit 1.1, the dialect test that pins it is Phase 0 unit 0.2. |
| D5 | §1.5 lists `graft_sha`/`atom_blob`/`witnesses` as sent-and-ignored | P6 drops the atom block; P7 adds `graft_sha` to the broker "in the open, with the wire spec updated in the same commit" | the wire spec carries a "production today" column and a "this port" column; P6 and P7 edit the second. |

### 0.2 The merged base, measured

* `services/atom-broker/src/broker/coordinator/atoms.vs:48-49` reads `root_key` / `new_root_key`; `:89` answers 409
  `current_root_key`; `watch.vs:29` pushes `root-change` [read, this worktree after the merge]. `git log origin/main
  -- services/atom-broker/src/broker/coordinator` shows nothing after the SCOPE's `5f71fa47e` check [ran]. **No dialect
  fix landed.**
* `vs/src/vs/cf/` holds `analytics.vs kv.vs worker.vs` — **no `vs.cf.d1`** [ran `ls`]; `broker.middleware` and the
  auth namespaces require it; `test/broker/d1_standin.vs` stands in on the wasm leg. The CF wrapper serves kv, do,
  binding, analytics, sql (DO only), idb (DO only) [read `vs/hosts/cloudflare/wasi.mjs:232-300`]. Phase 1 needs a D1
  answer (§3.2).
* `vs.cf.worker/upgrade-websocket` throws 501: no `ws` family on any host [read `worker.vs:165-168`].
* The runtime rows a `.vs` program has over the slab [read `program_runtime.vs`]: `rt-addr` (a value's slab offset,
  :23992), `rt-page-cid-at k` (:23915), `rt-page-text cid from n` (base64 of any byte range of a page, :23934),
  `rt-slot-text v` (the 256-B slot naming v, SHA@80 included, :23965), `rt-slot-value text` (:23978, faults through the
  tier), `rt-child-image c` (:23767), `rt-page-tier!` / `rt-tier-policy!` (:23793/:23774), `rt-kstat-*`, the epoch
  rows, `rt-crypto-sha256`. There is **no row that answers a block's masked content image or adopts a single block**:
  the page tier adopts whole pages (`eve_page_adopt_b64`). P8 adds both (§4.4).
* A page fault is thrown by `eve_slot_child` → `_page_fault` with a 32-B marker (`'vpm '`, CID at +8..23, the slot at
  +24) and served by `pm_serve`, which asks the tier by CID hex and adopts the page [read `eve.vs:3336-3349`,
  `program_runtime.vs:23155-23184`]. A slot with CID 0, inner 0, ACTIVE, SHA@80 set already resolves to -1 (a fault)
  [read `eve.vs:3270-3281`, "a reference by content"] — the by-SHA fault P8 needs is one branch of `pm_serve` away
  from today's marker.
* `kernel-rebuild` rebuilds the kernel module (kernel.vs and its required modules: eve, hamt, vec, set, list, alloc,
  retire …) from `.vs` [read `vs/scripts/kernel-rebuild:1-20`]; `program_runtime.vs` is a bake-cut module
  (`vs/CLAUDE.md` §Working here). A P8 change spans both: the kernel half through `kernel-rebuild`, the
  program-runtime half through the chain. §4.4 sequences the kernel half first and probes it kernel-direct.

### 0.3 Baseline re-run (the SCOPE §10 commands, this box, this base) [ran]

Script: `/tmp/…/scratchpad/baseline.sh` (the commands below in sequence, one heavy leg at a time), 18:45–18:50 UTC.

| command (from the worktree root) | result |
|---|---|
| `cd vs && bin/vs test tl_02b_coordinator_lifecycle_test tl_02c_coordinator_cas_test tl_02d_coordinator_lease_test net_run_test` | `VS-TEST: files=4 pass=4 fail=0 walled=0` |
| `cd vs && python3 test-hosts/wasmtime/blob_io_test.py` | `checks=10 pass=10` |
| `cd vs && python3 test-hosts/wasmtime/fetch_test.py` | `checks=4 pass=4` |
| `cd vs && python3 test-hosts/wasmtime/fetch_on_miss_test.py` | `checks=6 pass=1 fail=5`; run 1 traps: `… prog:vs.core/rt-vconj ← vs.core/prog--conj ← vs.tier/page-text` |
| `spike/build.sh && spike/demo.sh` | `RESULT: PASS (0 failed checks) in 25.2s` |
| `bash test/broker/run` | `BROKER-TEST: files=21 pass=21 fail=0 walled=0` |
| `python3 eve/doc/network-atoms/probes/run_probe.py` | `client: final:10 conflicts:5 cas-ok:10` · `do: final:5 conflicts:0 cas-ok:10` |

So: P1 and P2's "Done" reproduce (the client's wire and blob-io run on the wasm leg and on the wasmtime host); the
B-tier trap reproduces; the sim is green; the broker's worker half is green with its stand-ins; the dialect probe
still shows the `services/atom-broker` renames losing updates.

### 0.4 The production wire, transcribed (the G5 source)

From `b80bb555f:old/src/eve/coordinator.cljs` and `worker.cljs` [read this session; line numbers below]. The
Worker routes (`worker.cljs:218-370`, every data-plane call `POST` with `Authorization: Bearer <key>`) rewrite onto
the DO's paths (`:381-433` forward-to-do, adding `X-Domain-Id`):

| client route | DO path, method | request body | 200 body | other statuses |
|---|---|---|---|---|
| `/v1/domains/:d/atoms/:a/create` | `POST /create-atom` (`:1001-1006`) | `{atom_id, initial_root_key?}` | `{status "ok", epoch}` (`:223-240`: an existing atom answers its epoch; a new one 0) | — |
| `/v1/domains/:d/atoms/:a/deref` | `GET /deref?atom_id=` (`:933-951`) | — | `{root_key, epoch, lease_id, lease_ttl 60, root_blob?, atom_blob?, skeleton_blob?, skeleton_sha?, freelist_sha?, freelist_blob?, root_count?, branch_blobs?, _server_timing}` (`:292-330`; a key is absent, not null, when the column is NULL) | 404 `{status "not-found", _server_timing}` |
| `/v1/domains/:d/atoms/:a/cas-root` | `POST /cas-root` (`:953-999`) | `{atom_id, old_key, new_key, retired_keys, root_blob, skeleton_blob, skeleton_sha, freelist_sha, freelist_blob, root_count}` (`:960-963`; `graft_sha`, `atom_blob`, `witnesses` ignored) | `{status "ok", epoch, _server_timing}` (`:332-396`; an unknown atom is auto-created at `old_key` then CASed) | 409 `{status "conflict", current_key, current_epoch, root_blob?, skeleton_blob?, skeleton_sha?, freelist_sha?, freelist_blob?}`; 403 `{status "denied", reason, detail}` (the domain-spec gate, `:358-368`) |
| `/v1/domains/:d/release-reader` | `POST /release-reader` (`:1008-1014`) | `{lease_id}` | `{status "ok"}` (`:397-401`) | — |
| (no Worker route) | `POST /renew-lease` (`:1016-1024`) | `{lease_id}` | `{status "ok", lease_ttl 60}` (`:403-418`) | 404 `{status "not-found"}` |
| `/v1/domains/:d/atoms/:a/watch` | `GET /watch?atom_id=` upgrade (`:908-931`) | — | 101; pushes `{type "root-changed", atom_id, root_key, epoch, delta_keys [], root_blob? (≤ 4096 b64 chars), skeleton_blob?, skeleton_sha?, freelist_sha?}` (`:777-800`) | 400 `{error}` without `atom_id` |
| `/v1/domains/:d/presign` | Worker-side (`:461-500`) | `{keys [hex…], mode "read"|"write"|"delete", ttl?}` | `{urls {hex url…}, ttl}`; keys `blobs/<hex>` (`s3_delete.cljs:229-232`, virtual-hosted style) | 503 `{error}` when no bucket |
| `/v1/domains/:d/credentials` | Worker-side (`:781-822`) | `{}` | `{access_key_id, secret_access_key, endpoint, bucket, region}` | 503 `{error "No bucket provisioned"}` |

Server constants [read `:140-144`]: lease TTL 60 s, GC grace 300 s, GC every 60 s. Blob bodies are base64 strings in
JSON. The skeleton blob (`SKEL`, `:242-268`) is read with **little-endian** u32 counts at +8/+12 — `vs.net.coordinator`
writes big-endian (`coordinator.vs:132-171`, "u32be") [read]: a second dialect drift, pinned by the G5 test (0.2.4).

The `services/atom-broker` port's drift, row by row [read]: `root_key`/`new_root_key` (cas-root in), 409
`current_root_key`, `root-change`, deref 404 `{error "atom not found"}` (production: `{status "not-found"}`), create
`{status "ok"}` without `epoch`, cas-root first write `{status "created", epoch 1}` (production: `ok`), `/presign`
body `{keys [{method key}]}` → `{urls [{key url method}]}` (production: `{keys [hex] mode ttl}` → `{urls {hex url} ttl}`),
no `renew-lease` route in the Worker, lease TTL 30 s. Each is a Phase 1 row.

### 0.5 `eve.atom` at `323ab7ab9`, module by module, against SCOPE §6.1

The port table, with every SCOPE §2.2 defect marked **FIX-DON'T-TRANSCRIBE** at the line it lives [read this session]:

| module (lines) | what is design, what is defect | becomes (Phase 4 unless noted) |
|---|---|---|
| `tiered_remote_atom.cljc` (761) | the atom type (IDeref/ISwap/IReset/IWatchable/ILeaseStore), deref → load-root → lazy map; swap = deref, f, commit!, retry; `MAX_RETRIES 1000` (:33, :519) jitter ≤ 2 s (:36). **Defect**: the 409 branch (:180-199 CLJS, :635-651 JVM) tests `(:conflict ed)` / `(== 409 (:status ed))` on an `ex-data` — but `json-fetch` (`tiered_slab_net.cljc:75-89`) throws `(ex-info (str "HTTP " status) {:status :body})` on every non-2xx, so `ed` is `{:status 409 :body …}`: the branch is reached only if `winner-skel` is read from `(:body ed)`, which the JVM arm never does (the JVM `json-fetch` has no skeleton on 409). The graft branch therefore never runs live. | `TieredRemoteAtom` beside `MmapAtom` in `eve/src/eve/atom.cljc` (`:788-829` [read]); swap semantics per P3: 409 → the winner's graft → disjoint retry, else backoff; 403 → throw; retries bounded as VSv1 (50). The 409 handling is a **tested path** (§6.2 T-4.6). |
| `tiered_slab.cljc` (707) | `TieredSlabIO`: ISlabIO wrapper, 64-B self-ExtCID prefix (:38-41), seal = SHA-256(content) (:109-119), read-child memcmp + nil-on-miss (:145-179). The 64-B layout is dropped (§0.8). | `eve.net.slab`: an ISlabIO wrapper over `eve/`'s slab (`deftype_proto/alloc.cljc:79`) whose child reads at the network boundary resolve VSv2 256-B slots (SHA@80) through fetch-on-miss + the codec; EVE's local format untouched. |
| `tiered_slab_net.cljc` (2146) | the wire (:1164-1165 `old_key`/`new_key` — correct dialect), SigV4 with `blobs/<sha>` (:253 — **drop**: bare `<sha>`, B-creds), sibling batch, `diff-trees` (:701-800), skeleton, watcher `root-changed` (:1799 — correct), JVM arm (:1848-2146). **Defects**: `diff-trees` recurses only into vec headers 0x12, map/set/list headers 0x10/0x11/0x13, vec nodes 0x14, list nodes 0x15 and HAMT nodes 1/3 — a nested 0xED/0xEE **header** reached as a kv VALUE is not walked (:701-800: no arm for 0xED/0xEE), so a nested map's blocks are never uploaded; JVM `commit!` (:2031-2060) builds `sha->blob` but the function ends without a PUT — it never uploads; `:encrypt?` resolved in `remote_config.cljc` and dropped (no call site in `tiered_slab_net.cljc` reads it [ran `grep encrypt`: 0 hits]). | `eve.net.wire` (§0.4 routes, `http-request!` per host), `eve.net.blobs` (presigned PUT/GET from `/presign`, the 32-sibling batch, SHA check), `eve.net.walk` (the O(path) upload walker that **walks nested headers**), the watcher (Phase 5). Uploads happen on every host (T-4.4). `:encrypt?` reaches `blob-crypto` (G4). |
| `hamt_util.cljc` at `323ab7ab9` (JVM arm) | **Defect**: the JVM "xxhash128" is 4×xxHash32 / truncated SHA (header :5, "NOT wire-compatible" per SCOPE §2.2) | `eve.net.xxh3`: real XXH3-128, bit-exact to the kernel's `xxhash.vs` (G2). EVE's local Murmur3 (`eve/src/eve/hamt_util.cljc`) stays for the local format. |
| `paged_alloc.cljc` (1927) | the 64-B ExtCID allocator | dropped (SCOPE §6.1) |
| `future_fetch.cljc` (142), `lease.cljc` (26), `remote_config.cljc` (163), `remote_atom.cljc` (`$`, 99), `s3_sign.cljc` (268) | design | ported as they are: blocking fetch on CLJS (Phase 5), leases, config; `$` as it is; SigV4 is not needed by a client that takes presigned URLs (B-creds) — `s3_sign.cljc` is not ported. |
| `lru_cache_slab.cljc` (351) | unused by the network factory (SCOPE §2.1) | not ported |

### 0.6 The kernel block format (the G1 parity target), with the sources the fixtures and the codec read

[read] `vs/src/vs/kernel/wasm/`: `eve.vs:1488-1531` (ExtCID v2: page-CID@0, inner@16 (48-bit LE), version@32 = 2,
flags@40 (ACTIVE 1, EMBEDDED 2, HAS bits 4..32), spec-id@64, SHA-256@80, Poseidon@112, Merkle@160, group@192,
principal@224; type tag@256; payload@257), `:1533-1540` (T_VEC 0x12, T_LIST 0x13, T_ARRAYMAP 0x16, T_ATOM 0xA0, T_MAP
0xED, T_SET 0xEE, bitmap node 0x01, collision 0x03), `:1997-2047` (`seal_block`: SHA-256 over `[tag][payload]` with
every inner slot's bytes 0..47 masked to zero; a full T_VEC node through `vec_leaf_hash`), `hamt.vs:33-51, 90-135`
(map root 517 B: count@257, child slot@261; bitmap node: flags u16@257, kv_total u16@259, data_bm@261, node_bm@265,
children cc×256@269, hashes dc×16, kv `[klen:i32][k][vlen:i32][v]`), `vec.vs:1-45` (root 785 B: count@257, shift@261,
tail_len@265, tail slot@273, trie slot@529; node 8457 B: flags@257, 32 slots@265), `list.vs:1-22` (root 517: count,
head slot@261; node: vlen@257, next slot@261, bytes@517), `set.vs:107-125` (root 517; node: flags u16@257 = 0x80,
vtotal@259, bitmaps@261/265, children@269, values `[len][bytes]`), `eve.vs:3986-4117` (`serialize_value`: `[0xEE
0xDB][tag][payload]`, tags FALSE 1 TRUE 2 INT32 3 FLOAT64 4 STR_SHORT 5 STR_LONG 6; a collection value is its 256-B
ExtCID verbatim), `xxhash.vs:571` (`xxh3_128` → 16 B big-endian).

The wire blob of a block is its **masked content image** `[tag][payload]`: exactly the bytes `seal_block` hashes, so
`sha256(blob) == SHA@80` and `fetch-blob`'s address check holds (`blob_io.vs:118-139` [read]). On the wire a child slot
is therefore bytes 0..47 zero (CID, inner, VERSION and FLAGS alike — corrected by §9 A9 (a); this paragraph said
"VERSION 2 / FLAGS as sealed" before measurement) with the key @80; the adopter writes it back as the "reference by
content" form (CID 0, inner 0, VERSION 2, ACTIVE, key @80) that `eve_slot_resolve` answers through the SHA table (§0.2). That is the contract P4 (writer), P5 (the snapshot's
collection values) and P8 (the reader) share.

---

## 1. Phase order and the gates (fixed by the prompt)

| phase | gate (acceptance commands in the phase's §) |
|---|---|
| 0 | the wire spec and the fixtures exist; the dialect test passes against the transcription |
| 1 | A1, A2, A6–A9 green on workerd + `blobstore.py` with the real coordinator; the spike's stand-ins deleted |
| 2 | A3–A5 on the Phase-1 sim; the mirror gate 13/13 against an atom the ported client wrote into the sim |
| 3 | `fetch_on_miss_test.py` and `do_pages_test.py` re-pointed at the block path, green; the working-set bound holds with a network-backed atom |
| 4 | G1 rows green on JVM and bb; A1–A8 with mixed VS + JVM + bb clients on one atom |
| 5 | P10 (`root-changed` only, the other spellings deleted), the CLJS seam, measured host verdicts |
| B-creds | no secret crosses the wire (test); the identical change spec'd for `eve-db` (not deployed) |
| B-do | the 10,000-request soak green, or a proven bound |

Each phase banks (commit + push) its code, its tests and a report under `eve/doc/network-atoms/reports/phase-N.md`
before the next starts. A phase that cannot reach its gate stops the lane at that boundary with the report saying
where.

---

## 2. Phase 0 — wire spec + fixtures + ledger

### 2.1 Unit 0.1: the G5 wire spec — `eve/doc/network-atoms/WIRE.md`

The §0.4 table in full, one section per route, three columns per field: **production today** (`eve-db`,
transcribed), **this port** (what `services/atom-broker` and every client here speak after Phases 1–5), **delta**
(P6: `atom_blob` dropped; P7: `graft_sha` carried and echoed on 409; B-creds: `/credentials` without the secret,
`/presign` keys bare `<sha>`; P11: `/renew-lease` exposed on the Worker; the SKEL counts little-endian as production
reads them). Plus the blob wire: object key `<sha>`, body = the masked content image (§0.6), the VSNP snapshot (P5),
the GRFT V3 graft (P7). Plus §B-creds's rollout note for `eve-db` (John's, not this lane's).

### 2.2 Unit 0.2: P1's dialect test — `vs/test/vs/net_wire_fixtures.vs` + `vs/test/vs/net_wire_dialect_test.vs`

* `net_wire_fixtures.vs` (ns `net-wire-fixtures`): `production-broker` — an in-memory broker whose every response is
  the §0.4 shape **field for field** (keys present/absent as `coordinator.cljs` builds them: `cond->` on NULL columns,
  `_server_timing` present, `lease_ttl` 60) and whose request parsing reads **only** `old_key`/`new_key`/… (a request
  carrying `root_key`/`new_root_key` is a conflict against `nil`, as production would treat it). It records every
  request body. It is NOT `tl_fixtures/stub-broker` (that one is self-consistent with the client, not with
  production); it cites the `coordinator.cljs` line of each shape in a comment.
* `net_wire_dialect_test.vs`: `vs.net.coordinator` against it — create → `{:status "ok" :epoch 0}`; deref → the
  lease and the nil root; cas-root body carries `old_key`, `new_key`, `root_blob`, `root_count`, `retired_keys`; a
  stale CAS answers `:status "conflict"` with `:current-key`/`:current-epoch` read from `current_key`/`current_epoch`;
  403 `{status denied reason detail}` → `:status "denied"`; `/release-reader` body `{lease_id}`; `/credentials` fields;
  the SKEL blob the client builds is read by a little-endian parser as production's (`:242-268`) — this row is **red
  first** (the client writes big-endian) and fixed in the same unit in `coordinator.vs` (`-u32be` → `-u32le`,
  `-read-u32be` → LE; `parse-skeleton`'s own round trip keeps passing).
* Expected values come from the transcription (§0.4 line cites), never from the client.
* Accept: `cd vs && bin/vs test net_wire_dialect_test` → `VS-TEST: files=1 pass=1 fail=0 walled=0`.

### 2.3 Unit 0.3: the G1 fixture corpus

* Writer: `vs/scripts/gen-net-fixtures.vs`, run `cd vs && bin/vs run scripts/gen-net-fixtures.vs >
  ../eve/test/eve/fixtures/vsv2-blocks.edn`. For each value of the corpus it builds the value, finds its root block
  (`rt-addr`), reads the block's bytes from its page (`rt-page-cid-at` over `(:regions (tier/stats))` to find the
  region holding the offset; `rt-page-text cid (- off base) n` for the bytes), walks children through the slots in the
  image (layouts §0.6) and emits, per block: `{:sha <64 hex> :tag <int> :image <hex of the masked [tag][payload]>}`
  plus the value as EDN, the root SHA, and — for every map/set node — the `(key-bytes → 16-B hash)` pairs read out
  of the node (the G2 vectors, on real keys). The masking (zeroing bytes 0..47 of every inner 256-B slot the type's
  layout places) is done in `.vs` over the image, and the generator **checks itself**: `(= sha
  (sha256-hex image))` for every block, else it throws.
* Corpus (SCOPE G1): `{}`, `{:a 1}`, a 40-key map (two levels), a map with the shift-30 hash pair (two keys whose
  xxh3 low 30 bits agree — found by the generator by search over `"k<i>"` keys, the pair recorded), a collision pair
  (equal 128-bit hash is not reachable by search; the collision node row is **not** in the corpus and is named as such),
  `[]`, `[1 2 3]`, a 40-element vector (tail + one trie level), a 1100-element vector (two levels), `'()`, `'(1 2 3)`,
  `#{}`, `#{1 2 3}`, a 40-element set, nested `{:v [1 {:m #{:x}}] :l '(1)}`, strings of length 0, 3, 8, 16, 128, 240,
  241, 300 and 70,000 (STR_SHORT/LONG; the xxh3 length classes 0, 1-3, 4-8, 9-16, 17-128, 129-240, >240), keywords,
  symbols, `true`/`false`/`nil`, int32 edges (0, -1, 2^31-1, -2^31), a long outside int32 (the kernel refuses: the row
  records the refusal), floats (0.0, -0.0, 1.5, 1e300, NaN).
* VS consumer: `vs/test/vs/net_fixtures_test.vs` re-reads the file (as `eve_disk_test.vs:156-176` reads
  `eve-bytes.edn` [read]) and re-derives every SHA from every image; EVE consumers are Phase 4's.
* Accept: the file exists with ≥ 25 value rows; `cd vs && bin/vs test net_fixtures_test` green.
* Measured risk: `rt-page-text`'s 61,440-B chunk `conj` trap (B-tier) is in `vs.tier/page-text`'s `(conj acc t)`, not
  in `rt-page-text` itself [read the backtrace §0.3]; the generator reads block-sized ranges (≤ 10 KB) and does not go
  through `page-text`. If the trap is in the row, the generator is the B-tier repro brought forward and Phase 3's fix
  moves into Phase 0 (an amendment, §9).

### 2.4 Unit 0.4: the ledger rows

`docs/port-gap-closure/lanes/L14-persistence-tiers.md:20` ("G8, G9, G10 are by ruling"),
`docs/port-gap-closure/00-master-plan.md:118` (the L14 row, "minus by-ruling"),
`docs/port-gap-analysis/evidence-ten-readers.md` rows G8, G9, G10 (:131-134): each gets "**OPEN** — reopened
2026-10-16 (SCOPE §0.6/§3.1: never closed by the operator); the port is this lane, `eve/doc/network-atoms/`". G6's
C18 row (`L14-persistence-tiers.md:240-249`) gets the deviation note of SCOPE §4.2 (page-by-ID is L14's, not VSv1's;
corrected by P8) in the same change.

Order inside the phase: 0.1 → 0.2 → 0.3 → 0.4 (0.3 and 0.4 are independent of 0.2). Commit per unit.

---

## 3. Phase 1 — the sim runs the real coordinator

### 3.1 Unit 1.1: the coordinator on Ring + `vs.host`

Files: `services/atom-broker/src/broker/coordinator/{core,atoms,leases,watch,schema,gc}.vs` (509 lines today
[ran `wc`]) rewritten; `services/atom-broker/src/broker/coordinator/app.vs` (new: the DO's router);
`services/atom-broker/src/broker/main.vs` (new: the two-role entry, the `counter_do.vs` pattern [read
`vs/test-hosts/workerd/progs/counter_do.vs:62-66`]: `(if (= "cloudflare-do" (host/host-name)) (server/serve
do-app) (server/serve worker-app))`).

* Every handler `(fn [ring-req] ring-resp)`; the body via `cf/json-body`; SQL via `host/sql-exec` (rows are maps keyed
  by column name [read `host.vs:346-349`]); time via `System/currentTimeMillis`; lease ids via `random-uuid`
  (`core.vs:5349` lists it [read]) — no `js/`, no `js-await`, no `vs.uuid`/`vs.json`/`vs.time` (none exists under
  `vs/src/vs` [ran `ls`]).
* The wire per §0.4 **production** column: `old_key`/`new_key`; 409 `{status conflict current_key current_epoch …}`;
  403 `{status denied reason detail}`; deref 404 `{status not-found}`; create `{status ok epoch}`; cas-root's auto-create
  then `ok`; lease TTL 60; `/renew-lease` 200/404; `delta_keys` on the watch push. The 403 gate: production's
  `X-Eve-Domain-Spec` path (`spec/header->spec`, `diff/write-allowed-shallow?`, `coordinator.cljs:188-193, 358-368`)
  — unit 1.1 reads `b80bb555f:old/src/eve/{spec,diff}.cljs` first (sizes unknown until read) and ports the header
  decode + the shallow write check if the two are ≤ 400 lines together; otherwise the port carries the header decode
  and ONE rule (`{:writes :deny}` → denied), the rest an amendment. A7 drives that path.
* `gc.vs`'s Tigris deletes (`blob-delete/delete-batch!`, `broker.blobs.delete`) stay as they are in shape (an
  `s3-sign` DELETE through `host/fetch`); the alarm is `host/timer-wait` past 30 s (the wrapper's alarm, [read
  `do.mjs:17-21`]).
* Tests (wasm leg, `test/broker/run` composition): `test/broker/coordinator_wire_test.vs` — the real handlers against
  an in-memory `sql` stand-in (`test/broker/sql_standin.vs`, the `d1_standin` pattern: a map of tables keyed by the
  statement's parameters) checked against the **same** expected shapes `net_wire_fixtures.vs` holds (0.2): create,
  deref (lease row inserted, TTL 60), cas-root ok / auto-create / 409 with `current_key` / 403, release, renew 404,
  the watch push text. Expected values: §0.4 line cites.
* Accept: `bash test/broker/run coordinator_wire_test` PASS; `bash test/broker/run` stays 21/21 + 1.

### 3.2 Unit 1.2: the broker's auth on workerd — the D1 answer

The real Worker role authenticates through `broker.middleware/wrap-auth` → `api-keys/resolve-key` over `vs.cf.d1`,
which does not exist (§0.2). Production's Worker also accepts an env API key (`worker.cljs:114 validate-env-api-key`
[read index]); the VS port has no such path [ran `grep API_KEY` on `middleware.vs`, `api_keys.vs`: 0 hits].

* Step 1.2.1 (measured, bounded to one probe): local workerd's D1. `hostlib.capnp` has no D1 binding; wrangler's
  local D1 is the `cloudflare-internal:d1-api` wrapped binding over a SQLite-backed fetcher. Probe: one capnp with
  `wrapped = (moduleName = "cloudflare-internal:d1-api", innerBindings = [(name = "fetcher", service = …)])` over a
  Durable Object service with `enableSql`, driven by a 20-line `.vs` that calls one query through a new `d1` op.
  Outcome A (the probe answers a row): `vs/src/vs/cf/d1.vs` in `.vs` (connect/execute/query/query-one over
  `host/d1-query binding sql params`), the `d1` op in `wasi.mjs` (`env[binding].prepare(sql).bind(...params).all()` —
  one platform call), `test/broker/d1_standin.vs` deleted (one implementation). Outcome B (no answer): the Worker role
  takes production's env-API-key path, ported from `worker.cljs:99-150` into `broker.middleware` (a `BROKER_API_KEY`
  var; D1 untouched), and D1 on local workerd is recorded as the reason in the report. Either way the sim's 401 is
  the real middleware's.
* Accept: A9's 401 on the sim (3.4).

### 3.3 Unit 1.3: B-creds on the broker (the ship-blocker, placed here)

`services/atom-broker/src/broker/blobs/proxy.vs`: `handle-credentials` answers `{endpoint, bucket, region,
expires_in}` — **no `secret_access_key`, no `access_key_id`**; `handle-presign` takes production's `{keys [hex…] mode
ttl}` and answers `{urls {hex url} ttl}` with keys **bare `<sha>`** (the live data's), ≤ 100 keys, TTL default 300;
`handle-read` / `handle-write` keys bare too. `presign.vs` is deleted: it required `vs.crypto`/`vs.codec`/`vs.time`
(none exist) and duplicated `vs.net.s3-sign/presignUrl` — the broker presigns through `vs.net.s3-sign` (one SigV4).
Test: `test/broker/presign_test.vs` — the response carries no key named `secret_access_key` and no value equal to the
domain's secret; every URL's path is `/<bucket>/<sha>` with `X-Amz-Signature`. The `eve-db` rollout note goes into
`WIRE.md` (0.1 is amended with the final shape).

### 3.4 Unit 1.4: the stack driver — `vs/test-hosts/workerd/broker_sim_test.py`

* Bundle: `hostlib.build_cf("services/atom-broker/src/broker/main.vs", out, ["--do-class", "Coordinator",
  "--lib-root", "services/atom-broker/src"])`; capnp with `do=True, do_class="Coordinator", do_binding="COORDINATOR",
  outbound=True` (the Worker's presign needs no outbound; GC's deletes do), the broker's string bindings
  (`TIGRIS_ENDPOINT=http://127.0.0.1:<blobstore port>`, the domain's bucket/keys for the sim as the D1 rows or the
  env path of 1.2 provide them).
* Store: `vs/test-hosts/blobstore.py` (unchanged; it already records every request and answers `/<bucket>/<key>`).
* Clients: `vs/test-hosts/progs/net_client.vs` under `vs-term --bridge` (the `blob_io.vs` driver pattern [read
  `blob_io_test.py`]): one op per invocation named by `OP=` (create, deref, cas, cas-stale, cas-denied, release),
  printing one line the driver checks.
* Rows: **A1** create → 200 `{status ok epoch 0}`; **A2** deref → `root_key` absent/nil, `epoch 0`, a `lease_id`;
  **A6** cas with a stale `old_key` → 409 `{status conflict current_key current_epoch}` and the root unchanged (a
  deref after); **A7** cas under the denying spec → 403 `{status denied}`, the client throws `PolicyDeniedError`-shaped
  (`:status "denied"` from `cas-root` [read `coordinator.vs:269-272`]) and makes no second CAS (the broker's request
  log counts one); **A8** `w.stop(); serve again` → deref answers the same root and epoch (on-disk DO storage); **A9**:
  no key → 401; a trapping request (`/trap` is not a broker route — the trap is a cas-root whose body is not JSON:
  the Worker's `wrap-error-handling` answers 400 and the next request is served; for the DO, a malformed body
  forwarded by a raw DO fetch answers 500 and the next request is served, `x-instantiations` 2 — the M3 contract
  [read `do_test.py`]); a non-ASCII `root_blob` (base64 of `café ✓ 中`) round-trips byte-exact through cas-root and
  deref; a `root_blob` over the broker's size bound (16 KiB, the spike's; kept as the DO's own 413) → 413.
* Accept: `cd vs && python3 test-hosts/workerd/broker_sim_test.py` → `HOST-TEST: workerd checks=N pass=N fail=0` with
  the A1/A2/A6/A7/A8/A9 rows named; `bash test/broker/run` green; `cd vs && bin/vs test net_wire_dialect_test` green.

### 3.5 Unit 1.5: delete the spike's stand-ins

`git rm spike/guest/{abi,worker,coordinator,client,sha256,kat}.vs spike/host/fabric.clj spike/build.sh spike/demo.sh
spike/tools/unshare_memory.py` (`sha256.vs`/`kat.vs` duplicate `vs.net.s3-sign`'s SHA-256, pinned by
`tl_03a_digest_known_answer_test` [ran, §0.3]; the unshared-memory flip is `build-cf-worker`'s concern). Kept:
`SPIKE-NOTES.md`, `README.md` (a closing note added: "replaced by `vs/test-hosts/workerd/broker_sim_test.py` on
2026-10-xx"), `NETWORK-SURVEY.md`, `survey-probes/`, the two transcripts. `.vs-retired-surfaces` gets the guests'
paths. `spike/README.md`'s "Run it" section is replaced by the driver's command.

Order: 1.1 → 1.2 → 1.3 → 1.4 → 1.5. 1.3 can run beside 1.2.

---

## 4. Phase 2 — the VSv2 network atom

### 4.1 Unit 2.1: P3 + P5 + P6 — `vs/src/vs/net/remote_atom.vs`, `vs/src/vs/net/snapshot.vs`

Ported from `19371a631:src/vs/net/remote_atom.vs` (799 lines [ran]) with the 0621 features restored on the current
formats:

* `snapshot.vs` (P5): `VSNP` — magic `0x56534E50` LE, count u32 LE, per entry `[klen][k][vlen][v]` LE, each key/value
  in the kernel's serial form (§0.6 `serialize_value`: scalar tags; a collection as its 256-B slot with bytes 0..47
  zeroed and SHA@80 — read with `rt-slot-text` then masked). `snapshot-deserialize`: scalars → values; a 256-B ref →
  `rt-slot-value` of the masked slot (which faults by SHA: P8). Root key = `sha256-hex(snapshot)`.
* `remote_atom.vs` (P3): a handle `{:config … :cache (atom {:epoch :root-key :value :lease-id})}`; `deref` = `deref-atom`
  → snapshot-deserialize → **the lease is held** (P11: `:lease-id` kept on the handle, renewed by `renew-lease` when
  older than 40 s at the next fault, released by `release!` / after a swap); `swap!` = deref, `f`, snapshot + walker
  (2.2) + uploads + `cas-root` with `old_key`/`new_key`/`root_blob`/`root_count`/`retired_keys` (+ `graft_sha` after
  2.5); `409` → (2.5's graft check, else) jitter `min(10·2^n, 2000)·(0.5+rand)` ms through `host/timer-wait` and
  retry, `MAX_RETRIES 50`; `403` → `(throw (ex-info "swap! denied: <reason>" {:type :policy-denied :reason :by}))`,
  no retry; `reset!` = `swap!` with `(constantly v)`. **P6**: no atom block — `atom_blob` is not built or sent; the
  136-B codec is not ported (SCOPE P6: the broker never reads it; `coordinator.cljs:960-963` [read]).
* `register!`: core `deref`/`swap!`/`reset!` dispatch is not a Proxy on VSv2. The handle is a record type
  (`vs.net.remote-atom/RemoteAtom`) and the network atom is used through `ra/deref`, `ra/swap!`, `ra/reset!`; making
  core's `deref` dispatch on it is a runtime change outside this lane (it is noted in the report, not done).
* Tests: `vs/test/vs/net_snapshot_test.vs` (round trip of every scalar kind and a nested ref; the magic and LE layout
  by bytes; expected bytes derived by hand from §0.6 and from `0621c9989:src/vs/net/remote_atom.js:170-245` [read]),
  `vs/test/vs/net_remote_atom_test.vs` (the swap loop against `production-broker` (0.2) extended with an in-memory
  object store: ok / 409-then-ok / 403 throws once / 50-retry bound; no `atom_blob` key in any request body; the lease
  renewed, not released, across two derefs).
* Accept: `cd vs && bin/vs test net_snapshot_test net_remote_atom_test` green.

### 4.2 Unit 2.2: P4 — the per-block seal + upload walker, `vs/src/vs/net/walk.vs`

* Needs two runtime rows (kernel + program-runtime): `rt-block-image off` → the masked `[tag][payload]` image of the
  block at `off` as a byte string (kernel: `eve_block_image(off, dst, cap)` = `_seal_apply_mask` → copy → restore,
  the payload size by the type's own size function as `seal_block`'s callers compute it), and `rt-block-sha off` →
  the 64-hex SHA at `off+80` (or recomputed when the HAS/ACTIVE flags say unsealed). Kernel half via
  `vs/scripts/kernel-rebuild` (+ `wat2wasm`, the import-dag gate), program-runtime half through the chain (one bake,
  its change list frozen with P8's rows in 4.4 — **one build per unit**, CLAUDE.md rule 22: 2.2 and 2.4's rows land
  in the same chain run).
* The walker: from a root value, DFS over `rt-block-image` images by type tag (§0.6 layouts: map root → child slot;
  bitmap node → children + kv values that are 256-B refs; collision node → its kv; vec root → tail + trie; vec node →
  32 slots; list root/node; set root/node; arraymap 0x16 → its data slot), collecting `{sha image}` per block, and
  — given the old root — the **branch SHAs** of the new tree, the **new blob SHAs** (blocks not reachable from the old
  root: the old tree's SHA set is walked first and subtracted; O(whole tree) per swap, the 0621 behaviour; the O(path)
  diff is Phase 4's EVE walker and a later VS improvement, named not done) and the **retired SHAs** (old − new).
* Upload: `blob-io/upload-blob` per block, **HEAD-before-PUT** (P2's missing dedup: a `HEAD` through `host/fetch` on
  the presigned URL; 200 → skip) — `blobstore.py` gains `do_HEAD`.
* Tests: `vs/test/vs/net_walk_test.vs` — the fixture corpus (0.3) as the oracle: the walker's `{sha image}` set over
  each corpus value equals the fixture rows (every block, every SHA); a swap from `{:a 1}` to `{:a 1 :b [1 2]}`
  uploads exactly the new blocks.
* Accept: `cd vs && bin/vs test net_walk_test net_fixtures_test` green; `vs/scripts/check-import-dag` green.

### 4.3 Unit 2.3: B-creds on the client

`vs/src/vs/net/blob_io.vs`: `fetch-blob` / `upload-blob` / `fetch-blob-batch` take a **URL supplier** — a config
`{:presign (fn [method shas] {sha url})}` built by `vs.net.coordinator/presign` (new: `POST
/v1/domains/:d/presign {keys mode ttl}`, batched ≤ 100, the URLs cached until `ttl − 30 s`). `-presign-cfg` and the
`s3-sign` import leave `blob_io.vs`; `fetch-credentials` is deleted from `coordinator.vs` (the route no longer serves
a secret; `vs.net.s3-sign` stays for the broker). `tier.vs`'s `fetch-tier`/`put-page!` (page-by-ID) go in 2.4. Tests:
`net_run_test.vs` and `tl_01*` re-pointed at the supplier; `test-hosts/progs/blob_io.vs` + the three `blob_io_test.py`
drivers get a presign stand-in route in `blobstore.py` (the driver asserts the program never saw a secret: the
program prints the config keys it holds).

### 4.4 Unit 2.4: P8 — the fault fetches the block by SHA (correcting L14)

* Kernel (`eve.vs`): (a) `eve_block_adopt(image, len, sha)` — allocate `size_to_class(257 + len)`, write a fresh own
  ExtCID (inner = own, version 2, ACTIVE, SHA@80 = sha), copy the image at 256, verify `sha256(image) == sha` (else
  -2), register `sha → off` in a **SHA table** (a header-band table beside the CID-to-region table; probe its band
  the way `_pt_root` is laid out); (b) `eve_slot_resolve`'s "reference by content" arm consults the SHA table before
  answering -1 (a slot whose SHA is adopted resolves to that block — the slot is patched in place: inner, page-CID,
  as `patch-child-offset!` does from `.vs` today [read `blob_io.vs:262-279`]); (c) `_page_fault` marks a by-SHA fault:
  CID 0 at +8, the slot at +24 (already so), the SHA readable from the slot.
* Program runtime (`program_runtime.vs`): `pm_serve` branch for a marker whose CID is zero: read SHA@80 of the slot
  at +24, call the **block tier** fn (`rt-block-tier! f`, a second header cell beside `PR_PAGE_TIER_OFF`; `(f sha-hex
  sibling-shas)` answers nil or adopts and answers true), then retry. Rows: `rt-block-tier!`, `rt-block-adopt! image
  sha`, `rt-block-image`, `rt-block-sha` (2.2). The sibling list: the serve passes the parent block's other unresolved
  slots' SHAs (up to 32, `SIBLING_SCAN_MAX` [read `0621c9989:src/vs/eve/sio_net.js:726-806`]) — the parent is the
  block the slot lies in (`_eve_region_base` arithmetic + the type's layout, kernel side, written into the marker at
  +28.. as a list the row hands the tier).
* `vs/src/vs/tier.vs`: `block-tier` over `blob-io` — `(fn [sha sibs] …)`: `fetch-blob-batch` of `[sha & sibs]` (one
  presign call, N GETs), each adopted through `rt-block-adopt!`; **deleted**: `fetch-tier`, `put-page!`, `page-key`,
  `digest` (page-by-ID); `install!` keeps the page tier for the LOCAL stores (`sql-store`, `browser-store`, `window-tier`,
  `save!`/`restore` — the roles VSv1's local pools had, SCOPE §4.2).
* `blob_io.vs`: `patch-child-offset!`, `unresolved?`, the injected platform (`install-platform!`) and the per-slab SHA
  index are **deleted** — the kernel's SHA table and adopt row replace them (one form: the kernel's).
* Tests: `vs/test-hosts/wasmtime/fetch_on_miss_test.py` + `progs/page_tier.vs` **rewritten to the block path**: run 1
  builds the map, uploads its blocks (2.2's walker) and prints the root SHA; run 2 installs the block tier, reads the
  root through `rt-slot-value` of a masked slot carrying that SHA, reads the map back equal, each block fetched once,
  siblings batched (≤ 1 + ⌈n/32⌉ presign calls); a tampered block makes the read throw naming the SHA. Kernel-direct
  probe first: `vs/test-xfail/persistence-tiers/kernel_direct_block.py` (the `kernel_direct_cid.py` pattern) for
  adopt + resolve on a hand-built slot.
* Accept: `cd vs && python3 test-hosts/wasmtime/fetch_on_miss_test.py` → `checks=6 pass=6` (this row is also Phase 3's
  first gate; it is reached here only if B-tier does not block run 1 — `page-text` is no longer on the path, so it
  should not; if it does, Phase 3's fix is pulled forward, §9).

### 4.5 Unit 2.5: P7 — SwapGraft V3 + the read Bloom, `vs/src/vs/net/graft.vs`

* Codec per `0621c9989:src/vs/eve/graft.js:1-56, 100-200` [read]: `GRFT` 0x47524654 LE, version 3, flags, epoch u32,
  timestamp u64 (lo, hi), 6 reserved, old/new root SHA, three u32 counts, three SHA lists; fixed prefix 100 B.
  `graft-possible?` = loser Bloom vs winner's branch ∪ retired (`bloom.js:50-60`: four u32 LE hashes from SHA bytes
  0..15 mod 4096 bits).
* The read Bloom needs read instrumentation in kernel `hm_get` (SCOPE P7): a per-thread "read tracking" global
  (`ThreadUnsafe_EVE_READ_BLOOM`, a 512-B block the program hands in with `rt-read-tracking! bloom-off`), set by
  `hm_get`'s node walk (the SHA@80 of every node visited) — kernel half, same build as 2.2/2.4's rows.
* Broker: `coordinator/atoms.vs` stores `graft_sha` on cas-root and echoes it on 409 (`WIRE.md` "this port" column
  updated in the same commit); `remote_atom.vs` fetches the winner's graft blob by SHA, decodes, `graft-possible?` →
  retry without backoff.
* Tests: `vs/test/vs/net_graft_test.vs` — the G3 vectors: the encode/decode rows of `1b37d45e:test/eve/graft_v3_test.js`
  (SHAs of the labels `old`, `new`, `b1`…, epoch 7, timestamp 1700000000000; the byte layout asserted by offsets),
  Bloom add/might-contain with the four-hash rule; `net_remote_atom_test.vs` gains the disjoint-graft retry row;
  `coordinator_wire_test.vs` gains the `graft_sha` echo. Accept: those green.

### 4.6 Unit 2.6: A3–A5 on the sim

`broker_sim_test.py` gains: **A3** one client `swap!`s `{:k {:nested [1 2 3]} :s #{:a}}` → every PUT to the store is
presigned, keyed `/<bucket>/<sha>` (64 hex, no `blobs/`), no secret in any request the broker saw (its log), root key
= `sha256` of the `root_blob` the broker stored (the driver recomputes it); **A4** a fresh client with an empty slab
derefs → prints the value equal; every GET presigned; `fetch-blob`'s address check ran (the driver tampers one blob
in the store for a second fresh client → the read throws); **A5** two clients (two `vs-term` processes) 50 increments
each on `{:n 0}` → final 100, conflicts > 0 (the broker's 409 count), no 5xx in the broker log, every lease released or
expired (the DO's `reader_leases` table queried through an admin route the test adds: `/gc-status`'s `active_leases`
is production's [read `coordinator.cljs:565-585`]). Accept: `python3 test-hosts/workerd/broker_sim_test.py` all rows.

### 4.7 Unit 2.7: P13 — the mirror at 256 B, its gate against the sim

* `src/clojurians/mirror/core_worker.vs`: `EXTCID 128` → 256, slot offsets per §0.6 (SHA@80, child slots at the
  kernel's layout offsets), the prefetch over `fetch-blob-batch` with presigned URLs from `/presign` (no
  credentials); it consumes `vs.net.remote-atom` (deref of the snapshot) and `vs.net.blob-io`. The browser host: the
  worker runs the program through `vs/term/hosts/browser/` (the page host's `fetch` op [read `vs/test-hosts/README.md`
  C16 row]); `boot.vs` / `core_worker_entry.vs` are brought to that host (the JS-era `_vs_artifact.mjs` entry is not
  rebuilt). Size unknown until `vs/term/hosts/browser/*.js`'s worker path is read (Phase 2's last unit reads it first
  and records the measured scope in the report before editing).
* A writer: `vs/test-hosts/progs/net_client.vs OP=write-mirror` writes a mirror-shaped atom (`{:channels {"general"
  {:name :topic :count :messages [{:ts :author :text} …]}}}`, two channels, 64 messages) into the sim with the ported
  client (2.1/2.2).
* The gate: `test/tiered-atom/phase-7-mirror-gate-real.pw.mjs` parameterised by `MIRROR_ENDPOINT` (the workerd port)
  and `STORE_ORIGIN` (`blobstore.py`'s) in place of `dev.atom.broker` / `t3.storage.dev`; its 13 assertions unchanged
  (the byte ratio, every store request presigned, every store response non-empty, no slab on the page, no `/blob/`,
  two channels with distinct real text).
* Accept: `MIRROR_ENDPOINT=… STORE_ORIGIN=… node test/tiered-atom/phase-7-mirror-gate-real.pw.mjs` → `13/13
  assertions passed` (the sim started by `python3 test-hosts/workerd/broker_sim_test.py --serve`, a mode added for it).

Order: 2.1 → 2.2 (kernel+chain build frozen with 2.4's and 2.5's kernel rows: the change list is written in the
report before the build starts) → 2.3 → 2.4 → 2.5 → 2.6 → 2.7.

---

## 5. Phase 3 — local tiers under the network atom

### 5.1 Unit 3.1: B-tier — `vs.tier/page-text` traps in `rt-vconj`

Repro [ran, §0.3]: `python3 vs/test-hosts/wasmtime/fetch_on_miss_test.py` run 1, backtrace `rt-vconj ←
prog--conj ← vs.tier/page-text`. `page-text` conjs 61,440-byte base64 chunks (81,920-char strings) into a vector
[read `tier.vs:98-107`]. Minimal probe first: `vs -e '(count (conj [] (apply str (repeat 81920 "a"))))'`, then
halving the length to the threshold; the trap frame (`<wasm function 2686>` → 2039) named through `program.names`.
Root cause in the kernel's `vec_conj_off` / the embedded-value path (`EVE_EXTCID_FLAG_EMBEDDED`, eve.vs:1596-1600: a
value ≤ 144 B rides in the slot; a longer one is a reference — the suspect is the boundary) or in `prog--conj`'s
boxing of a long string; fixed at the producer, pinned by `vs/test/vs/vec_conj_long_string_test.vs` (`deftest`).
After P8 `page-text` serves only the local stores (`save!`, the `:memory` policy's write-back); the trap still blocks
`do_pages_test.py` `/write` (4/11 today, SCOPE §3 [they report]; re-measured here before the fix).

### 5.2 Unit 3.2: P9 — the working-set bound with a network-backed atom

`vs/test-hosts/workerd/do_pages_test.py` + `progs/do_pages.vs` re-pointed: the object's pages still save to its
SQLite (local store, unchanged); the **network** leg: the object holds a `vs.net.remote-atom` whose blocks fault in
from `blobstore.py` through the block tier (P8) under `(tier/working-set! (* 4 1048576))`; `/bound` reports `resident
≤ 4 regions after a run`, `reuses > 0`, the value equal across three reads. `fetch_on_miss_test.py` gains the same
bound row on wasmtime (`VS_HEAP_CAPACITY`). Accept: `python3 test-hosts/workerd/do_pages_test.py` →
`checks=N pass=N`; `python3 test-hosts/wasmtime/fetch_on_miss_test.py` → `checks=7 pass=7`.

---

## 6. Phase 4 — the EVE network domain, JVM then bb

Gated order inside the phase: 4.1 (G2) → 4.2 (G1) → 4.3 (wire + blobs) → 4.4 (the atom) → 4.5 (bb) → 4.6 (mixed A1–A8)
→ 4.7 (VS on `eve.net`).

### 6.0 The shape of `eve.net` (operator directive, 2026-10-16 — §9 A11; it governs every unit below)

One library, plain `.cljc`, platform-agnostic: a host that reads Clojure supplies a small **platform map** and gets the
whole network atom; eve supplies everything else; a host may override a default with a faster implementation.

* **The host supplies** (required): `:request` — `(fn [{:method :url :headers :body}] …)` answering `{:status
  :headers :body}` SYNCHRONOUSLY, on every host (the only I/O there is); `:seed` — a random seed (the PRNG eve runs
  for the backoff jitter; no nonce in the protocol needs entropy: a block's IV is SHA-256 of its key,
  `blob_crypto.vs:9`); `:now-ms`; `:sleep` (the backoff's wait, blocking). A network atom's deref and swap! answer the
  value, never a promise, on every Clojure-like (operator, 2026-10-17, verbatim: *"But in CLJS we depend on those
  blocking semantics to produce clojure like threading semantics. Deref and swap will not be returning promises on
  any clojure-like impl. So don't do that. And our prefetching machinery depends on the sync prefetch."*): a host
  whose only fetch is asynchronous blocks in its http adapter, as production's `future_fetch` did.
* **Eve supplies, in portable cljc** (no reader conditional and no host name anywhere: eve/CLAUDE.md's first rule,
  held by `eve/scripts/check-host-leak`; a step that can fail answers a value — `eve.net.json/read-str-or-nil` — so the
  core catches nothing): SHA-256, XXH3-128,
  HMAC / HKDF-SHA256, AES-256-CTR, base64, hex, a seeded PRNG — all over 32-bit limbs, so JVM, bb, CLJS and VS run
  the same arithmetic with no `BigInt` and no `long`-only arm — and the protocol: the VSv2 codec, the VSNP snapshot,
  the GRFT graft and the read Bloom, the wire client, presign batching, block fetch with its SHA check, the sibling
  batch, the upload walker, leases, retry / backoff / graft, the atom.
* **A host may override** `:sha256`, `:xxh3-128`, `:hmac-sha256`, `:aes-256-ctr`, `:base64` (same signatures, synchronous).
  The suite runs every override supplied against the default on the KATs, so an override cannot change a byte.
* **Sans-IO core**: a protocol step is a pure function from its state and the last answer to the next effect
  (`{:request req}`, `{:sleep ms}`, `{:done v}`); ONE driver, in cljc, runs it over the platform — a loop (no stack
  growth), synchronous on every host. The same code is the JVM's, bb's and Node's; Node's http adapter
  (`adapters/http/node`) runs the fetch on a worker thread and parks the caller on `Atomics.wait` (§7.2).
* **Host conveniences are eve's ADAPTERS** (2026-10-17, operator: *"add the network stuff as an adapter... All the
  clojure-likes should plug in their http request adapter and the eve cljc will take care of the rest"*, and *"if
  crypto needs another adapter you can add that"*; eve/adapters/README.md, eve/hosts.edn): two features —
  `http` (`eve.http/platform` → `{:request f}`, synchronous; `adapters/http/java` for the JVM and babashka,
  `adapters/http/node` for ClojureScript on Node and nbb) and `crypto`
  (`eve.crypto`: `sha256`, `hmac-sha256`, `aes256-ctr`; `adapters/crypto/java`, and `adapters/crypto/portable` —
  `eve.net.sha256` / `eve.net.aes` — for every other host). `eve.net.platform` composes `eve.http` with `eve.process`
  (the clock, a blocking sleep), and `eve.net.atom/remote-atom` defaults to it: a host puts its http adapter on its
  classpath and the network atom is eve's. The crypto suite holds every native adapter to the portable code.
* **VS is one such host.** VS reads a `.cljc` file with `:clj` features (`vs/docs/semantics.md` §reader features), so
  VS consumes the same `eve.net` with its platform map (`vs.host/fetch` as `:request`, the kernel's `sha256` / xxh3 as
  overrides, its kernel block rows as its local-domain adapter). The protocol code Phase 2 wrote in `vs.net.*`
  (snapshot, graft, coordinator wire, retry) converges on `eve.net` in unit 4.7 — one form, measured by the same sim
  rows (A1–A9, the mixed run), the duplicate `vs.net` namespaces deleted in that unit.

### 6.1 Unit 4.1: G2 — `eve/src/eve/net/xxh3.cljc`

XXH3-128 (the kernel's `xxhash.vs:571` arms: len 0, 1-3, 4-8, 9-16, 17-128, 129-240, long; the kSecret; output 16 B
big-endian as the kernel writes it). `:clj`/`:bb` arm in long arithmetic (`unchecked-multiply`, `unchecked-add`,
`bit-xor`, `unsigned-bit-shift-right` — all present on bb [to be measured in the unit's first commit with a 5-line
probe]), the 64×64→128 multiply in 32-bit halves; `:cljs` arm over `js/BigInt` (Node) — Phase 5's hosts get their
own arm or a measured "no". Test: `eve/test/eve/net/xxh3_test.cljc` — the `(key-bytes → hash)` pairs of the fixture
corpus (0.3; every length class), plus the published empty-input KAT (`xxh3_128("") = 99aa06d3014798d86001c324468d497f` [from memory; the kernel confirms or corrects it in the unit's first run]),
on JVM (`clojure -M:jvm-test -n eve.net.xxh3-test`) and bb (`bb -cp src:test -m eve.runner.bb-main test
eve.net.xxh3-test`; the ns added to `shared-tests`).

### 6.2 Unit 4.2: G1 — `eve/src/eve/net/codec.cljc`

The boundary codec, EVE value ⇄ VSv2 block images (§0.6): `encode-value` → the set of `{sha image}` blocks of a value
(maps: root + bitmap/collision nodes with xxh3 key hashes, kv in the kernel's serial form; vectors: root + trie +
tail; lists; sets; strings/keywords/symbols/numbers/booleans/nil in the serial tags); `decode-block` image → the
node's structure (children SHAs, inline entries); `seal` = SHA-256 of the image (AEGIS_SEAL 0). EVE's local
(Murmur3, 12-B header) format is **not** changed; the codec reads EVE values and writes VSv2 images, and reads VSv2
images into EVE values. Test: `eve/test/eve/net/codec_fixture_test.cljc` over `test/eve/fixtures/vsv2-blocks.edn`:
every row decodes value-exact (`=` to the EDN) and re-encodes byte-exact (every image, every SHA equal), on JVM and bb
(the runner's `shared-tests` + `clojure -M:jvm-test`), and Node (`node target/eve-test/all.js fixtures` after
`npm run test:compile` — the CLJS arm, measured, may wall on BigInt; recorded).

### 6.3 Unit 4.3: the wire and the blobs — `eve/src/eve/net/wire.cljc`, `eve/src/eve/net/blobs.cljc`

`wire.cljc`: the §0.4 client (`create`, `deref`, `cas-root`, `release-reader`, `renew-lease`, `presign`) over one
seam `http-request!` (`:clj` `java.net.http.HttpClient`, `:bb` `babashka.http-client` — measured in SCOPE §8 [they
ran]); 409 answers a value `{:status :conflict …}`, never a throw (the `eve.atom` defect); 403 `{:status :denied}`;
other non-2xx throw. `blobs.cljc`: `fetch-blocks!` (presign ≤ 100, GET each, SHA check, decode into the local domain),
`upload-blocks!` (HEAD, PUT), the 32-sibling batch. Test: `eve/test/eve/net/wire_test.cljc` against a stand-in broker
in-process (`http-request!` rebound) whose shapes are `net_wire_fixtures.vs`'s, transcribed to EDN **from the same
`coordinator.cljs` lines** (the EDN fixture `eve/test/eve/fixtures/wire.edn` is written by hand from §0.4 and
cross-checked by a `vs` test that reads it: `net_wire_fixtures_edn_test.vs`, so the two stand-ins cannot drift).

### 6.4 Unit 4.4: the atom — `TieredRemoteAtom` in `eve/src/eve/atom.cljc`, `eve.net.slab`

* `eve.net.slab`: `TieredSlabIO` — an ISlabIO wrapper over the heap domain's sio (`alloc/heap-domain-sio!`) that
  keeps, per local block, its VSv2 SHA (a side table `local-off → sha`) and, per remote child not yet materialised, a
  placeholder resolved on first read by `blobs/fetch-blocks!` (fetch-on-miss at the boundary with the sibling batch).
* `TieredRemoteAtom` (CLJ + bb arms; CLJS in Phase 5): `deref` → `wire/deref` → the snapshot (P5's VSNP, decoded by
  the codec) → the lazy local view under a held lease (renewed at faults; released by `close!`/after swap, or `$`'s
  exit); `swap!` → deref, `f`, the **O(path) walker** (`eve.net.walk`: diff old/new local trees by structural sharing
  (equal local offsets skip), **recursing into nested 0xED/0xEE/0x12/0x13 headers reached as values**) → encode the
  changed blocks → `upload-blocks!` → `cas-root`; 409 → the winner's graft (P7's `graft_sha`) → disjoint → retry
  without backoff, else backoff; 403 → throw; `reset!`; `$` as in `eve.atom` (`remote_atom.cljc`). `:encrypt?` → the
  per-block key through an `eve.net.crypto` port of `blob_crypto.vs` (G4: HKDF-SHA256 / AES-256-CTR / IV; KATs from
  `vs/test/vs/blob_enc_test.vs` [ran green §0.3] copied as EDN) — on by default for network atoms.
* Tests (`eve/test/eve/net/remote_atom_test.cljc`, JVM then bb): T-4.4 every swap PUTs the changed blocks (the
  stand-in store's log is non-empty and each key is a SHA of its body); T-4.5 a nested map's inner blocks are uploaded
  (the `diff-trees` defect); T-4.6 a 409 reaches the conflict branch (a competing writer between deref and CAS; the
  retry wins; one 409 counted); T-4.7 403 throws once; T-4.8 `:encrypt? true` stores ciphertext (the store's body ≠
  the image, the SHA still the plaintext's); T-4.9 G4: three blobs encrypted by VS (`blob_io.vs` under the fixture
  ctx) decrypt in EVE and the reverse.

### 6.5 Unit 4.5: bb

The same tests through `bb -cp src:test -m eve.runner.bb-main test eve.net.codec-fixture-test eve.net.wire-test
eve.net.remote-atom-test`; bb-specific arms only where measured (the atom interfaces' arities as `atom.cljc:803-813`
does [read]).

### 6.6 Unit 4.6: mixed clients on the sim

`broker_sim_test.py --mixed`: A1–A8 with the three clients — the VS program, `clojure -M:net-client` (a `-main` in
`eve/test-e2e/eve/net/client.clj`, OP-driven as the VS one) and `bb -cp src -m eve.net.client` — swapping ONE atom:
VS writes `{:n 0}`, JVM increments 20×, bb increments 20×, VS increments 20× concurrently → 60; each client then
derefs the value the others wrote (value-exact); every store key a SHA, no secret seen. Accept: `cd vs && python3
test-hosts/workerd/broker_sim_test.py --mixed` → all rows PASS.


### 6.7 Unit 4.7: VS on `eve.net` (§6.0, A11)

VS's platform map (`vs/src/vs/net/eve_host.vs`: `:request` over `vs.host/fetch`, `:seed` from the host's entropy,
`:now-ms`, `:sleep` over the `timer` family, the kernel's `rt-crypto-sha256` as the `:sha256` override, the kernel
block rows as the local-domain adapter that `eve.net`'s codec hands images to) and `vs.net.remote-atom` reduced to
`eve.net.atom` over it. Deleted in the same unit: the `vs.net` namespaces whose protocol `eve.net` now carries
(`snapshot`, `graft`, the coordinator wire, the retry loop) — only VS's host adapter stays. Accept: the Phase-2 rows
(`net_remote_atom_test`, `net_graft_test`, `broker_sim_test.py` A1–A9) green on the converged client, and
`--mixed` green with all three clients running `eve.net`.
---

## 7. Phase 5 — watch, Node/CLJS, the remaining hosts

### 7.1 Unit 5.1: P10 — the `ws` family

`vs/src/vs/host.vs`: `ws-connect url headers` → handle, `ws-send h text`, `ws-next h` (the next message, blocking),
`ws-close h`; `vs.cf.worker/upgrade-websocket` → a server-side `ws` op (`accept` on the `http` event; the DO's
Hibernation API in the wrapper). Wrappers: `vs/hosts/cloudflare/wasi.mjs` (`WebSocketPair`, `acceptWebSocket`,
`getWebSockets(tag)`), `vs/term/hosts/node/vs-serve-node.mjs` (the `ws` package is Node's built-in `WebSocket`
client; the server side via `node:http` upgrade — measured), `vs/term/hosts/wasmtime/vs-term` (Python `websockets`
— measured; else the family is announced absent and the client falls back to polling deref, which the test asserts).
`services/atom-broker/src/broker/coordinator/watch.vs` pushes `root-changed` (production's shape, §0.4);
`vs/src/vs/net/watcher.vs` (from `19371a631:src/vs/net/graft_watcher.vs`, 192 lines [ran]) listens for
`root-changed` only; the strings `root-change` and `graft` are deleted from every file this lane touches (`grep -rn
'root-change"\|"graft"' services vs/src eve/src` → 0 hits is the gate). `remote_atom.vs` uses the push to skip the
deref on swap. EVE: `eve.net.watch` on JVM (`HttpClient.newWebSocketBuilder`) and bb (measured). Test:
`broker_sim_test.py` row: a watcher receives `root-changed` with the new `root_key` and `epoch` after another client's
CAS, on VS and JVM.

### 7.2 Unit 5.2: the CLJS seam

`eve.atom`'s `future_fetch.cljc` (142 lines) has its design ported as eve's http adapter for Node,
`eve/adapters/http/node` (`eve.http`): the async `fetch` run on a worker thread, the calling thread parked on
`Atomics.wait`, the answer read back with `receiveMessageOnPort` — so the network atom on ClojureScript is synchronous
as the JVM's, deref and swap! answer values (never promises), and the block tier's prefetch stays synchronous.
DONE in Phase 4 (reports/phase-4.md, "Synchronous on every host"): every eve.net test on nbb, and Node as the mixed
run's fourth client. Remaining here: the same suite through `npm run test:compile` + `node target/eve-test/all.js`
(the compiled ClojureScript, beside nbb's interpreter), and a browser adapter (a Web Worker: synchronous XHR or
`Atomics.wait`; the page's main thread may block on neither).

### 7.3 Unit 5.3: the host probes

For cljr, glojure, basilisp, clojerl, jolt (`eve/impls/README.md` names them [unverified: `hamt_util.cljc:9` points there]): one probe each —
http POST + GET, SHA-256, the xxh3 arm's arithmetic (64-bit wrap), `Atomics`/blocking — yes/no per primitive with the
command and its output in the report; a port follows for a host that answers yes on every row (its own unit, named
in the report), none otherwise.

---

## 8. B-do — the Durable Object's memory growth

Repro [they ran; re-measured here first]: `cd vs && DO_MEM_REQUESTS=3000 python3 test-hosts/workerd/do_test.py` (the
fast loop; 10,000 is the gate). The header `x-memory-bytes` is `p.mem.buffer.byteLength` [read `wasi.mjs:225`] — wasm
memory growth, i.e. the program's slab, not the wrapper's JS. Method: a probe program (`counter_do.vs` + `rt-kstat-*`
per 100 requests printed to the DO's log: the allocator census `get-slab-census` [read `core.vs:1020-1050`], the
epoch counters, `rt-kstat-tier`) names the growing class; then the producer — a per-request allocation the sweep does
not reclaim (RE-14 is L04's; if the growth is a request's `http` event or the server loop's own strings never freed,
the fix is in `vs.http.server`/`vs.host`'s pump and `core.vs`'s task loop; if it is the kernel's, in the kernel) — or,
if the growth is bounded by design (the slab's high-water), the bound proven: `tier/working-set!` installed in the DO
role and the soak asserting `memory ≤ 2 × mem100` at 10,000. Placed after Phase 1 (the sim needs the DO; A5 fits
under 2,082) and before Phase 2's A5 soak variants; the fix lands as `vs/test-hosts/workerd/do_test.py` green at the
default 10,000: `python3 test-hosts/workerd/do_test.py` → `checks=11 pass=11`.

---

## 9. Amendments

**A1 (Phase 0, 2026-10-16) — the slot image is tiered; only tier 1 is a SHA-256.** Measured on the kernel's own
blocks (`vs/scripts/gen-net-fixtures.vs`'s probes; `eve.vs` `_slot_image` [read :3839-3975]): a child slot's band at
80..111 is the child's SHA-256 only for an ExtCID-headed child (tier 1; a set root's XORed with 0xEE…). A scalar
child is INLINE in the band (tier 2: `[type-CID 16][payload ≤ 8][zeros]` for nil, booleans, int32, the 8-byte
kinds); a string, keyword, symbol or char child is `[type-CID or typekey 16][xxh3_128 of its bytes 16]` (tiers 3/3b
— content-derived, not reconstructible, and a 128-bit non-cryptographic hash is not an address a store can be held
to, rule 9); every other kind (the UP1 numeric tower's Double `UPA2dbl`, BigInt, Ratio …) is `[CID 16][the local
block offset 4][zeros]` (tier 4 — not content: two equal doubles minted apart seal differently, and `(= [2.5]
[2.5])` holds only through the structural path). A list node stores its value's 32-B image inline (vlen 32) except a
string, stored as its block bytes. §0.6's "byte-identity" therefore holds for collections whose leaves are tier 1 and
tier 2; **Phase 2 (P4/P8) makes tiers 3, 3b and 4 content-addressed**: the band becomes the SHA-256 of the value
block's canonical bytes (`[CID 16][len @16][bytes @24]` for the string-shaped kinds; `[CID 16][payload]` for the
fixed kinds), the value block shipped as a blob under that SHA, and the adopt row rebuilds the value block from the
blob (a per-kind payload-length table in the kernel is the one new piece). This enforces the decided format (SHA@80,
the SHA-only seal) rather than reopening it. The Phase-0 corpus records tiers 3/3b/4 as they are (`:leaves` with the
xxh3 bands — the G2 vectors; `:portable? false` on the doubles row) and is regenerated when Phase 2 lands.
WIRE.md §7 carries the set-root rule (a key equal to the body's SHA XOR 0xEE… names a set root).

**A2 (Phase 0) — binary values on this leg, measured, and the library bug they hid.** A byte string's `count`,
`nth` and `subs` see UTF-8 characters (D2a); only `.-length` / `.charCodeAt` see bytes. `vs.net.s3-sign/-bytes` and
the `-ints` helpers of `vs.net.blob-io` and `vs.net.coordinator` measured strings with `count`, so any binary holding
a valid multi-byte sequence hashed, encoded and indexed SHORT — fixed in unit 0.2 (`.-length`); the blob tests had
passed only because their data (0..255 ascending) forms no valid sequence. Joining two byte strings with `str`
re-encodes them as text and loses bytes (a 296-byte image came out 294); the faithful join is `vs.core/rt-bytes`
(`rt-byte` makes a one-byte string; `(apply bytes …)` is the program-level all-at-once join — `vs.core/bytes` by name
trips, so the generator joins as a balanced tree of `rt-bytes`). `vs.net.coordinator/base64-decode` costs near 18 KB
of slab per decoded byte (its per-byte vectors and `apply str`); a string past ~8 K characters is a rope that `count`
/ `.-length` / `pr-str` trip on (16 K for `pr-str`). The kernel's `rt-crypto-sha256` over a byte string is exact
(checked against Python's hashlib) and costs no slab. Phase 2's client code (P4's images, P5's snapshot) is written
on these facts: byte strings, `rt-bytes` joins, kernel digests, chunked output; the base64 codec of
`vs.net.coordinator` gets the same treatment in P3 (a unit: its cost is a per-byte vector, not a design).

**A3 (Phase 0) — the fixture writer runs one row per process** (`vs/scripts/gen-net-fixtures`, the row named in
`./.gen-row`: `vs run` forwards no environment): a compiled program frees only what an atom's write replaces, and the
whole corpus in one process ran the slab out. The corpus's values are top-level defs (a value built inside the corpus
vector literal is sealed under the literal's own construction). The shift-30 hash pair and the collision node are not
in the corpus (unreachable by construction without the hash); strings in the corpus are built at run time (a literal
is an interned constant in a region without a page header, which `rt-page-text` cannot read).

**A4 (Phase 0) — the corpus is 22 rows, not the "≥ 25" of §2.3.** The plan's row list counted the scalar kinds as
rows; in the file they are leaves of rows (`vec-of-kinds`: nil, booleans, the int32 edges, keyword, symbol, char;
`string-values` and `vec-of-strings`: strings of length 0, 1, 3, 8, 16, 128, 240, 241, 300 and 4,000 — the xxh3
length classes; `vec-of-doubles`: the UP1 doubles, `:portable? false`), a long outside int32 is refused by the kernel
at construction (not a row), the 70,000-character string is a rope on this leg (A2) and is not in the corpus, and the
shift-30 pair and the collision node are A3's. The consumer test's bar is the plan's contents, not the number: every
block kind, > 300 G2 key pairs, ≥ 8 leaves, a set root; it measures 22 rows / 195 blocks / 410 pairs / 27 leaves.

**A5 (Phase 1, 2026-10-16) — the compiler and the composed test unit, measured.** (a) `(-> s (str/replace a b) …)`
trips at run time with `ArityException (2)` on this leg, in a plain and in a `{:vs/lang true}` namespace alike, while
the nested call and a `->` over `assoc` / a user function run (probes p4–p6): the broker's string code threads nothing
through `clojure.string/replace`. (b) `vs compile` resolves a `(:require …)` from the library roots only for the
FIRST namespace form of a unit; a later ns form's requires stay unresolved and trip at the call (`unresolved callee`;
probe p8b). So a `test/broker/run` unit opens with `test/broker/unit_libs.vs`, one ns that requires every vs.*
library the unit's namespaces use, and composes no library file as text (`vs/src/vs/cljs/json.vs` as text gaps on its
`(declare read-value)`). (c) A `(:require …)` naming a namespace with no source is a compile error (rc 75), so the
deployable program (`broker.main`) cannot require `broker.routes` (its admin and auth tables reach `vs.cf.d1`,
`vs.crypto`, `vs.json`, `vs.time`, `vs.codec`, none of which the tree carries) — A6.

**A6 (Phase 1) — the Worker role's auth and routes.** Unit 1.2's probe (`scratchpad/d1probe`, one capnp, three
starts): the wrapped `cloudflare-internal:d1-api` binding over a service runs in this workerd (the probe reached the
service), but the only local D1 implementation is miniflare's `D1DatabaseObject` worker, whose `miniflare:shared`
module imports `node-internal:internal_assert` — a module raw workerd grants only to its own internal workers
(`No such module "node-internal:internal_assert"`, workerd 1.20261012.1). **Outcome B**: `broker.middleware/wrap-auth`
carries production's environment-key path (`EVE_API_KEY`, `EVE_API_KEY_<DOMAIN>`, worker.cljs:114-128; the api_key
query parameter, :99-111) and a `resolver` hook; the D1 / JWT / IAM path lives in `broker.auth.resolve`, which installs
itself as the resolver when composed (the seven auth tests compose it; the sim's 401 is the middleware's). The
route table splits into `broker.routes.data` (data plane, blobs, health — what `broker.main` mounts) and
`broker.routes` (those plus admin and auth, composed with stand-ins by the tests). The object store's config comes
from `TIGRIS_*` bindings (`broker.worker/store-config`: one store per deployment) until a D1 library carries the
per-domain row. A new `/v1/domains/:d/renew-lease` route (P11) forwards to the object. `broker.worker` (the base's
entry over the nonexistent `vs.http.middleware`) is `broker.worker/handler` + `broker.main`.

**A7 (Phase 1) — the Coordinator's departures from production, each named.** No `blobs` table, no `/blobs/fetch`, no
`atom_blob` column and no `branch_blobs` on deref (rule 4: the broker serves no blob); `graft_sha` stored on cas-root
and echoed on deref and 409 (P7's broker side, landed here); GC's object-store deletes are one presigned DELETE per key
through the host's fetch (`broker.blobs.delete`; the base's `vs.net.s3-delete` XML batch does not exist in the tree),
and a cycle runs on `POST /gc-trigger` — the DO wrapper has no alarm event, only answers to `timer` waits, so a
periodic cycle needs the program to wait and serve at once, which `vs.http.server/serve` does not (Phase 5's host
probes name it); `storage-audit` is per atom; the 403 gate's `changed?` is `new_key ≠ old_key` (spec.vs). The
spike's bound of 16 KiB on `root_blob` is the object's 413. `vs.cf.worker/do-request` takes `:headers` (X-Domain-Id,
X-Eve-Domain-Spec) — absent from the request map when none are given, the shape `data_plane_relay_test` pins.

**A8 (Phase 2 units 2.1 and 2.3, 2026-10-16) — measured on the leg, and the port's choices.** (a) In a `{:vs/lang
true}` namespace `integer?` answers true for a whole-valued double (1e300): the serializer tests `float?` first. A
char is a one-character string and serializes as one. The kernel's string-shaped serial form carries the keyword /
symbol lead byte, so a string starting with ':' or 0x01 reads back as a keyword or symbol (the kernel form's own
ambiguity, kept). (b) `vs.net.bytes` is the byte-string row set (join, of-ints, slice, u32 LE, hex, base64) built on
rt-byte / rt-bytes; `vs.net.coordinator`'s base64 codec now runs on it (A2's cost closed). (c) `vs.net.snapshot`
masks bytes 0..31 of a collection's slot (page CID, inner offset) and keeps VERSION, FLAGS and the SHA — the form
`eve_slot_resolve` reads as "reference by content"; today such a slot faults as `page 0000… is not resident` (the
page tier is asked for CID 0; pinned by `net_snapshot_test`), which unit 2.4 turns into a fetch by SHA. (d) The
network atom's lease: a deref holds its lease and releases the one held before; `renew!` renews a lease older than
40 s and releases a lost one best-effort; a swap! releases its lease after the CAS — not VSv1's release-on-deref.
`swap!` on this leg takes `:sleep-fn` for the backoff (the host's timer outside a test). The top-level value must be
a map (VSv1 snapshotted HAMTs only). (e) B-creds on the client: a blob-io config carries `:presign` (fn [mode shas]
→ {sha url}), `vs.net.coordinator/presigner` over `/presign`; `fetch-credentials` and the local SigV4 in blob-io are
gone; the dedup HEAD needs a `"head"` mode (S3 signs the method) — this port's fourth mode (WIRE.md §8);
`blobstore.py` serves `/v1/domains/<d>/presign` so the host programs hold no key. `vs.tier`'s page-by-ID rows keep
signing locally until unit 2.4 deletes them.

**A9 (Phase 2 units 2.2 and 2.4, 2026-10-17) — blocks by content, as built.** Measured with the kernel-direct probe
(`vs/test-xfail/persistence-tiers/kernel_direct_block.py`, R1–R9 on the rebuilt `kernel.wasm`). (a) **The wire image
zeroes bytes 0..47 of every inner slot** — VERSION and FLAGS too; WIRE.md §7 said otherwise and is corrected. A
deferred-seal slot (transients, `into`, `mapv`, `zipmap`) has no live band until its seal; `eve_block_image` fills it
from the child, so the image equals what `seal_block` hashes. `rt-block-sha` is not a row: a block's key is the SHA of
its image (`rt-crypto-sha256`), XOR 0xEE… for a set root. (b) **Adoption rebuilds the block; it does not copy an image
under a fresh ExtCID** (§4.4 (a)): a native block carries a TYPE CID @0..15 (MAP_ROOT, HAMT_NODE, VEC_ROOT, VEC_NODE,
LIST_ROOT, LIST_NODE) that `core.vs`'s dispatch and the program runtime's `$mapp` / `$vecp` / `$listp` read, root meta
words (ROOT_FLAGS @517 — bit 0 a set —, the edit id @520, ROOT_META @524, VEC_META @788) and a vector node's stream
state (144..247, 252); a block holding the image @256 under an ExtCID is not one any reader recognises.
`eve_block_adopt(src, len, key)` writes each field from the tag and the image (rule 12: nothing assumed zero), writes
every child slot as a reference by content (CID 0, inner 0, VERSION 2, ACTIVE, the key @80), checks a vector node's
leaf hash (`_vec_leaf_hash0`), and answers −1 (an allocation failed) · −2 (the image does not hash to the key) · −3
(no class holds it) · −4 (the table is full) · −6 (not an image it rebuilds: malformed, a sorted map, the T_SET
layout); the row adds −5 (arguments not a byte string and 64 hex). (c) **No slot patching** (§4.4 (b)): the resolve answers the
SHA table's offset on every read (`_st_resolve`); a patch would be an unaligned atomic store, which traps, and would
race readers of the slot. (d) **Value children are content-addressed** (A1's promise): a value block's canonical bytes
(WIRE.md §7) are its blob and its band; the program's FLOAT64 boxes — doubles (`UPA2dbl`, 8 bytes) and longs
(`UPA2lng`, 16) — are inline tier 2, so `(= [2.5] [2.5])` holds by image. What stays tier 4 (a rope, fn, atom, regex,
`UPA2big`) is refused by the walker as not portable, as is a list holding a collection (`list.vs` copies the child
root inline: no block to address) and a sorted map. (e) **The SHA table**: 16,384 entries × 48 B (+0 state, +4 the
offset, +8 the kind: 0 a collection block, 1 a value block, +16 the key) in a region claimed on first use (header cell
180). A collection block's entry is dropped when retire nominates the block (`retire.vs` `rt_emit` → `_st_forget`),
so a reused block never answers for its old key; an adopted value block is never retired (it sits in class ≤ 14, and
retire's owned-blob rule takes class 15 only): immortal, a named limit. (f) **Siblings are batched in `.vs`**, not by
the kernel writing the parent's unresolved slots into the fault marker (§4.4): `vs.tier/block-tier` records the child
keys of every collection block it adopts (`vs.net.walk/child-keys` over `rt-block-image`) and fetches a faulted key
with up to 31 of its not-yet-resident siblings in one presign call. (g) **B-tier (§5.1) is fixed here**, at its
producer: `pm_page_text` conj'd `string_create`'s −1 sentinel into the page text (trap 0xFFF10018); it now builds the
leaf with `leaf_of_buf`. `_slot_image`'s tier-0 read is bounded by memory. (h) **Named limits**: retired keys below
a removed subtree that was never faulted in are named by that subtree's root key only (the store's GC finds the rest
or not); retire's settle scan is conservative — on the mixed corpus value it releases nothing, the local control
likewise (measured), while a 100-key int map releases 3 blocks (R8). (i) **A byte string's lead byte types it** (the kernel's
string form, A8 (a)): an image that starts with the HAMT node tag 0x01 is a Symbol to the program and one that starts
with ':' a Keyword, so `string?` answers false for a block image (measured on the landing: a `{:n 1}` node image typed
`clojure.lang.Symbol`, and blob-io treated it as a byte vector — `Don't know how to create ISeq from: string`). Byte
data is recognised by `vs.net.bytes/byte-string?` (a string, symbol or keyword leaf); `vs.net.bytes/hex-at` and
`zero-at?` read bands in place (a slice is one small block per byte, and the walker's per-slot slices ran the
generator's 300-key and 1,100-element rows out of slab). (j) **A number's image depends on the namespace's model**
(`vs/docs/semantics.md` §Which namespaces get the language's model): a user namespace (Clojure's tower) holds 2.5 as the
FLOAT64 box marked `UPA2dbl` (and a long past int32 as the box marked `UPA2lng` [read: program_runtime.vs fmark]; an
integer within int32 is the kernel's int32 band in both — the Phase-0 corpus's `{:a 1}` measured so), a language
namespace holds a JavaScript number as the kernel's plain FLOAT64 or int32 — so `[2.5]` seals to different keys in the two (measured on the landing: the
generator, briefly a language namespace, keyed `vec-of-doubles` 7fd465b7…, the test's user namespace 80ffc3ee…).
Network atoms carry user programs' values, so the G1 corpus is generated in a USER namespace and Phase 4's codec
writes the user model's images for Clojure numbers (an int32 → the int32 band; a long past it → `UPA2lng`, 16 bytes;
a double → `UPA2dbl`, 8). A
language-model value on the wire is read back by its own CID; equality across the two models is the language's
structural `=`, not the key. (k) **Byte joins and digests cost slab that a compiled program never frees**: `vs.net.bytes/join` took a `subvec` per
level (720 wasm pages, ~46 MB, to join a 2 KB blob's one-byte pieces; 0 by index ranges), and blob-io's address check
hashed with `vs.net.s3-sign`'s pure-`.vs` SHA-256 (a vector per byte) — a reader faulting a 300-entry map in grew the
slab past 2 GB. Both fixed (index-range joins; the kernel's `rt-crypto-sha256` for every content digest); the base64
codec moves to native rows in Phase 3's build (reports/phase-3.md item 8). The broker's Worker met the same cost
signing: SigV4's ~11 digests per URL on that SHA, its URI encoder and hex one string per character — the Worker's slab ran
out on the fifth 32-key `/presign`. Fixed in `vs.net.s3-sign` (the kernel digest, the signing key's HMAC pads cached, one
join per string, one date / credential / key per batch: 152 such calls before the first 500); what each request still
leaves is main's L04 C17 unit-end reclaim to free (`vs.http.server/serve` brackets every request as a unit).

**A10 (Phase 2 unit 2.5, 2026-10-17) — the read Bloom and what a graft buys.** The kernel marks, while a thread's
Bloom is installed (`rt-read-tracking!`), the node each map lookup ENDS in — a collision bucket, or a bitmap node whose
slot for the key holds no subtree (`hamt.vs` `node_get` → `eve.vs` `_rb_note`, by the node's key @80 and bloom.js's
four-position rule) — never a node it only descends through: a winner that changed a sibling subtree does not make the
read stale. A swap's top-level map is walked and uploaded although readers rebuild it from the snapshot: every client
rebuilds the same blocks from the same entries (content addressing), so the graft's branch and retired keys name the
top-level nodes an entry change touches, which is what a loser's lookups marked. A graft is a retry WITHOUT backoff,
never a merge: the loser's f runs again on the winner's value, so a false positive of the Bloom costs one backoff and
a false negative cannot lose a write.

**A11 (2026-10-16 08:31 PM ET) — operator directive, verbatim:** *"And remember, this should all be impl'd in cljc,
platform agnostically, as much as possible, so that clojure-like language impl'rs of eve can simply provide the
request object and the random seed, etc, and the rest is built into eve, unless a host chooses to override the
default impl with a more performant impl."* Applied as §6.0. What it changes in the units as written: 4.1's XXH3 is
ONE arm over 32-bit limbs (not a `:clj` long arm plus a `:cljs` `BigInt` arm); 4.3's `http-request!` seam with
`:clj java.net.http` / `:bb babashka.http-client` arms becomes the platform map's `:request` (those APIs move to the
host convenience namespaces); SHA-256, HMAC/HKDF and AES-CTR are eve's defaults in cljc (the host's
`MessageDigest` / `crypto` are overrides, not requirements); 4.4's atom is a sans-IO core plus one driver; 5.2's CLJS
seam is the platform's blocking `:request` (`future_fetch`'s design: §7.2, operator ruling of 2026-10-17); 5.3's host
probes ask what a host must supply (`:request`, `:seed`, `:now-ms`, `:sleep`) and run the defaults' KATs there — no
per-host port of the protocol. New unit 4.7: VS's client converges on `eve.net` (§6.0, last bullet).

**A12 (Phase 2 unit 2.7, 2026-10-16) — the mirror on the page host, and what the gate measures under B-creds.** (a) The
mirror is ONE `vs.dom` program (`src/clojurians/mirror/core_worker.vs`) in the page host's Web Worker
(`vs/term/hosts/browser/dom.js` loads `worker.js`, which runs `program.wasm`): it knows no block layout — §4.7's "EXTCID
128 → 256, slot offsets per §0.6" and the prefetch over `fetch-blob-batch` are the kernel's block tier's (faults by key,
sibling batches), so the app carries neither; `boot.vs` and `core_worker_entry.vs` (the JS era's :screen and platform
object) are deleted with their `build-inspector-app` rows, and the stager stages four files. (b) `dom.js` serves the
`vs.host` `fetch` family in the wasmtime host's answer shape. (c) The broker gains production's CORS
(`broker.middleware/wrap-cors`, `worker.cljs:1006-1229`) and `blobstore.py` a bucket's CORS — a page calls both
cross-origin. (d) `cf_entry.vs` relays `/presign` and `/renew-lease`. (e) The writer writes THREE channels (the gate's
unchanged `channels.length >= 3`; §4.7 said two). (f) The gate: the wire is classified by origin (`MIRROR_ENDPOINT`,
`STORE_ORIGIN`); `:core`'s Worker is Playwright's `page.workers()` (a page global the program would have to set
proved nothing); `/presign` answers are URL maps whose size grows with the blobs read (B-creds moved signing from the
client to the broker, which the gate's VSv1 byte accounting predates), so c1 requires every `/presign` answer to name
only store URLs and keeps them out of the 4 KB row and the byte ratio, which the gate prints separately. Still 13
assertions.
