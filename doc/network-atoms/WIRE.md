# The atom.broker wire (G5)

2026-10-16, ET. The one spec every client and every broker in this repository tests against. Column **production
today** is `eve-db` behind `dev.atom.broker`, transcribed from `b80bb555f:old/src/eve/coordinator.cljs` and
`worker.cljs` [read; line numbers below]. Column **this port** is what `atom-broker/services/atom-broker` and every client here
speak after the IMPL-PLAN's phases; a row whose two columns differ names the phase that changes it. A field in
`(…)` with `?` is present only when its column is non-NULL (production builds the map with `cond->`); the client reads
an absent field as nil.

Executable form: `vs/test/vs/net_wire_fixtures.vs` in atom.broker (`production-broker`, the stand-in whose shapes are
this file's, with the same line cites), and on eve's side `eve/test/eve/net/stand_in.cljc` — an in-process broker and
object store with the same routes and shapes — driven by `eve/test/eve/net/wire_test.cljc`. (The `eve/test/eve/
fixtures/wire.edn` this line named while Phase 4 was planned was never written; `eve/test/eve/fixtures/` holds the
byte, XXH3, aegis-kernel and VSv2-block fixtures. Note of 2026-10-20.)

## 1. Transport

* Every data-plane call is `POST {endpoint}/v1/domains/:domain-id/…` with `Authorization: Bearer <api-key>` and a
  JSON body (`worker.cljs:218-370` parse-route; the Worker forwards to the per-domain Durable Object with
  `X-Domain-Id`, `:381-433`). A missing or invalid key is 401.
* Blob bodies in JSON (`root_blob`, `skeleton_blob`, `freelist_blob`, `atom_blob`) are base64 text.
* Blobs themselves never cross this wire: a client PUTs and GETs them at the object store by presigned URL (repo
  CLAUDE.md rule 4; §8 below).

## 2. Routes

| client route | DO path, method | request body | 200 body | other statuses | production cite |
|---|---|---|---|---|---|
| `atoms/:a/create` | `POST /create-atom` | `{atom_id, initial_root_key?}` | `{status "ok", epoch}` — an existing atom answers its epoch, a new one 0 | — | `:223-240, :1001-1006` |
| `atoms/:a/deref` | `GET /deref?atom_id=` | — | `{root_key, epoch, lease_id, lease_ttl 60, root_blob?, atom_blob?, skeleton_blob?, skeleton_sha?, freelist_sha?, freelist_blob?, root_count?, branch_blobs?, _server_timing}` — **this port:** never `atom_blob` / `branch_blobs` (no blob rides the broker), plus `graft_sha?` (P7) | 404 `{status "not-found", _server_timing}` | `:292-330, :933-951` |
| `atoms/:a/cas-root` | `POST /cas-root` | `{atom_id, old_key, new_key, retired_keys, root_blob, skeleton_blob, skeleton_sha, freelist_sha, freelist_blob, root_count}` | `{status "ok", epoch, _server_timing}` | 409 `{status "conflict", current_key, current_epoch, root_blob?, skeleton_blob?, skeleton_sha?, freelist_sha?, freelist_blob?, _server_timing}` · 403 `{status "denied", reason, detail, _server_timing}` | `:332-396, :953-999` |
| `release-reader` | `POST /release-reader` | `{lease_id}` | `{status "ok"}` | — | `:397-401, :1008-1014` |
| (none in production's Worker; **this port: `renew-lease`**, P11 — Phase 1) | `POST /renew-lease` | `{lease_id}` | `{status "ok", lease_ttl 60}` | 404 `{status "not-found"}` | `:403-418, :1016-1024` |
| `atoms/:a/watch` (WebSocket) | `GET /watch?atom_id=` | — | 101; pushes §6 | 400 `{error "Missing atom_id parameter"}` | `:908-931` |
| `presign` | Worker-side | `{keys [hex…], mode "read"|"write"|"delete", ttl?}` | `{urls {hex url…}, ttl}` | 503 `{error "Domain bucket not provisioned"}` | `:461-500` |
| `credentials` | Worker-side | `{}` | **production today:** `{access_key_id, secret_access_key, endpoint, bucket, region}` · **this port (B-creds):** `{endpoint, bucket, region, expires_in}` — no key, no secret | 503 `{error "No bucket provisioned"}` | `:781-822` |

Semantics the shapes carry:

* **cas-root** compares `old_key` with the atom's `root_key` (`nil` = `nil` for an empty atom); an unknown atom is
  created at `old_key` and then CASed (`:339-346`). On success the row's `root_key` becomes `new_key`, `root_blob`,
  `skeleton_*`, `freelist_*`, `root_count` are stored, `epoch` increments by one, and each of `retired_keys` is queued
  for GC at the new epoch (`:371-384`). The broker never checks that `new_key` is a content address of `root_blob`.
* **deref** inserts a reader lease `{lease_id, atom_id, epoch, expires_at = now + 60 s}` (`:305-309`); GC expires
  leases, tombstones retired keys below the lowest leased epoch, and deletes tombstones older than 300 s
  (`:420-560`). A client holding a value derived from a deref holds the lease (renews it before 60 s, P11) and
  releases it when done.
* **403** is the pre-CAS validator's veto (production: the domain spec from `X-Eve-Domain-Spec`, `:188-193,
  :358-368`); a client throws and does not retry.
* **Fields production ignores on cas-root:** `graft_sha`, `atom_blob`, `witnesses` (not in the destructure at
  `:960-963`). This port: `atom_blob` is not sent (P6); `graft_sha` is stored and echoed on 409 (P7, §7); `witnesses`
  stays optional and absent.

## 3. The `atom-broker/services/atom-broker` drift at the base (corrected in Phase 1 — every row below is the production column now; `test/broker/coordinator_wire_test.vs`, `vs/test-hosts/workerd/broker_sim_test.py`)

| row | the port at `5f71fa47e` | production |
|---|---|---|
| cas-root in | `root_key` / `new_root_key` (`coordinator/atoms.vs:48-49`) | `old_key` / `new_key` |
| 409 | `{status "conflict", current_root_key, current_epoch}` (`:89`) | `current_key`, plus the stored blobs |
| watch push | `root-change` (`watch.vs:29`) | `root-changed` |
| deref 404 | `{error "atom not found"}` | `{status "not-found"}` |
| create | `{status "ok"}` | `{status "ok", epoch}` |
| first cas-root | `{status "created", epoch 1}` | `{status "ok", epoch 1}` |
| lease TTL | 30 s | 60 s |
| presign | `{keys [{method key}]}` → `{urls [{key url method}]}`, keys `blobs/<key>` | `{keys [hex] mode ttl}` → `{urls {hex url} ttl}` |
| renew-lease | DO route only | (none; this port exposes it on the Worker) |

## 4. The skeleton blob (`SKEL`)

Bytes: `"SKEL"` (0x53 0x4B 0x45 0x4C) · version u32 **little-endian** · prior-count u32 LE · branch-count u32 LE ·
prior SHAs (32 B each) · branch SHAs (32 B each). Production reads the counts little-endian
(`coordinator.cljs:242-268`, `getUint32 … true`). `vs.net.coordinator` at the base wrote them big-endian — corrected
in Phase 0 unit 0.2.

## 5. Keys

* Atom root key (`root_key` / `new_key` / `current_key`): the client's content address of its root blob — SHA-256
  hex (64 lowercase hex) of the VSNP snapshot (§7). Production stores it as an opaque string.
* Object-store keys: the live mirror data is keyed by the bare `<sha>` (VSv1 signed its own URLs, path-style
  `/{bucket}/{sha}`); production's `/presign` prefixes `blobs/` (`s3_delete.cljs:229-232`). **This port:** bare
  `<sha>` everywhere (B-creds); the `blobs/` prefix is retired with the client-side signing.

## 6. The watch push

`{type "root-changed", atom_id, root_key, epoch, delta_keys [], root_blob? (when its base64 is ≤ 4096 characters),
skeleton_blob?, skeleton_sha?, freelist_sha?}` (`coordinator.cljs:775-800`), sent to every socket tagged with the
atom id after a successful cas-root. A client may send `{type "ping"}`; `pong` is not part of production's wire. One
type everywhere: `root-changed`. (`graft` — VSv1's client — and `root-change` — the port's — are deleted, P10.)

## 7. The blob wire (this port)

* **A block** is the object whose key is the SHA-256 hex of its body; the body is the block's seal image exactly as
  the kernel seals it (`vs/src/vs/kernel/wasm/eve.vs` THE SEAL IMAGE, `seal_block`, cut by `eve_block_image`):
  `[type-tag:1][payload]` with every inner 256-B child slot replaced by its bands 48..112 — 64 bytes, `[the epoch /
  spec band 32][the child's band 32]` (the spec half zero when the slot's HAS_SPEC_ID is set; a relaxed vector
  interior's RRB size table at the first 4) — its bytes 0..47 (CID, inner offset, VERSION, FLAGS) and 112..255 (the
  descriptor band) left out; an EMBEDDED-value slot as `[48 zero bytes][its bytes 48..255]`; a deferred-seal slot's
  band filled from its child, a T_VEC node cut at its last occupied slot. (D-OPT-1, operator ruling 2026-10-19; before
  it each slot travelled as its 256 bytes with 0..47 zeroed. `docs/optimization/seal-image-report-2026-10-19.md`.) A
  reader ADOPTS a body (`eve_block_adopt`): it expands the image to the block's layout, rebuilds the block — its type
  CID @0 from the tag, its own offset @16, VERSION 2, ACTIVE, the root's meta words — writes every child slot as a
  REFERENCE BY CONTENT (CID 0, inner 0, VERSION 2, ACTIVE, the child's key @80), answered through the kernel's SHA
  table once the child is adopted, else faulted by key (P8), and re-seals the rebuilt block against the key. The
  image carries no slot FLAGS: a body whose vector node holds a 64-byte run of zeros where a slot is (an EMBEDDED
  value) is refused. IMPL-PLAN.md §9 A9.
* **A set root** is a map root block (tag 0xED) whose set-ness is a flag byte outside the sealed payload; its parent
  slot carries the root's SHA XOR 0xEE (32 bytes). A key equal to a body's SHA XOR 0xEE…EE therefore names a set
  root: the reader adopts the body as a map root with the set flag (IMPL-PLAN.md §9 A1).
* **Scalar children** ride inline in the slot's child band (slot bytes 80..111, the last 32 of its 64 image bytes:
  `[type-CID 16][payload][zeros]`): nil, booleans,
  int32, the 8-byte kinds, and a program number's FLOAT64 box — a double (8 payload bytes) or a long (16: the double
  and the exact i64).
* **A value child** — a string (one leaf), keyword, symbol, char, bigint, bigdec or ratio — is a blob: its value
  block's CANONICAL bytes `[CID or typekey 16][count u32 @16][word u32 @20][count bytes @24]`, every head byte that is
  not content written as zero (a string leaf's index word @20; a flat value's bytes 4..15, and its word @20 unless it
  is a char's code point). The band and the key are the SHA-256 of those bytes.
* **Not portable** (the walker, `vs/src/vs/net/walk.vs`, refuses the value naming it): a string past one leaf (a
  rope), a function, an atom, a regex or a big-integer box (a band holding a local offset), a list holding a
  collection (the list copies the child root inline: no child block), a sorted map.
* **The snapshot** (`root_blob`): `VSNP` — magic u32 LE 0x56534E50, entry count u32 LE, then per top-level entry
  `[klen u32 LE][key bytes][vlen u32 LE][value bytes]`, each in the kernel's serial form (`eve.vs` `serialize_value`:
  `[0xEE 0xDB][tag][payload]`, a collection value as its masked 256-B slot), plus tag 7 — an integer outside int32,
  its 8 bytes little-endian two's complement (EM6, 2026-10-19: the kernel's form had none, so every writer refused
  one; `eve.net.snapshot` writes and reads it for every client). `root_key = sha256(root_blob)`.
* **The graft** (P7): `GRFT` V3 — magic u32 LE 0x47524654, version u8 3, flags u8 0, epoch u32 LE, timestamp u64 LE
  (lo, hi), 6 reserved, old root SHA (32), new root SHA (32), three u32 LE counts, branch SHAs, new blob SHAs, retired
  SHAs. It is a blob under its SHA, which is `graft_sha` on cas-root; the broker stores it with the root and echoes it
  on 409 as `graft_sha`. A loser fetches the winner's graft by that key and retries at once when its READ BLOOM holds
  none of the winner's branch and retired keys. The read Bloom is 512 bytes (4,096 bits); a key's four positions are
  the u32 LE at its bytes 0, 4, 8 and 12, each mod 4,096; bit h is bit h & 31 of the LE word h >> 5. The kernel marks
  the node each map lookup ends in, by that node's key (IMPL-PLAN.md §9 A10).
* **Encryption** (optional, default on for network atoms): a block's body is AES-256-CTR under the per-block HKDF key
  (`vs/src/vs/net/blob_crypto.vs`); the key (`<sha>`) stays the plaintext's SHA.

## 8. Credentials (B-creds)

Clients never hold the domain's object-store secret. `POST …/presign` is the only way to a blob URL: a client
batches ≤ 100 SHAs per call, `mode "read"` for GET, `"write"` for PUT, `"head"` for the HEAD a client makes before a
PUT (the dedup: S3 signs the method, so a HEAD needs its own URL — this port's addition to production's three modes),
`"delete"`; `ttl` ≤ the broker's cap (300 s default, 3600 s max). `POST …/credentials` answers `{endpoint, bucket, region, expires_in}` and
nothing else; the broker's test proves no response body of any route carries the stored secret or access key id.

**Rollout note for `eve-db` (production; John's, not this lane's):** the identical change in
`old/src/eve/worker.cljs` `handle-credentials` (`:781-822` at `b80bb555f`): drop `access_key_id` and
`secret_access_key` from the response; `handle-presign` (`:461-500`) keys without the `blobs/` prefix
(`s3_delete.cljs:229-232` `tigris-object-key` → `hex`); existing clients that sign locally (`eve.atom`'s
`tiered_slab_net.cljc:253`, VSv1's `s3_sign.js`) must be moved to `/presign` first, or they lose blob access the
moment `/credentials` stops serving the secret.
