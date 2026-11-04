# The network atom's wire

The protocol between a network atom (`eve.net.atom`, and ValueScript's `vs.net.remote-atom`) and a broker: the
routes, their bodies and answers, the blob formats, and the rules for the object store the blobs live in. Every client
and every broker in eve tests against it. Its executable form on eve's side is `eve.net.broker` (§3), wrapped with an
in-process object store by `test/eve/net/stand_in.cljc` and driven by `test/eve/net/wire_test.cljc` and
`test/eve/net/broker_test.cljc`. A field in `(…)` with `?` is present only when it has a value; a client reads an
absent field as nil.

## 1. Transport

* Every data-plane call is `POST {endpoint}/v1/domains/:domain-id/…` with `Authorization: Bearer <credential>` (an
  API key, or a token from signing in) and a JSON body. A missing or invalid credential is 401.
* Blob bodies in JSON (`root_blob`, `skeleton_blob`, `freelist_blob`) are base64 text.
* Blobs themselves never cross this wire: a client PUTs and GETs them at the object store by presigned URL (§8).
  A broker has no `/blob/<sha>` route.

## 2. Routes

| route | request body | 200 body | other statuses |
|---|---|---|---|
| `atoms/:a/create` | `{atom_id, initial_root_key?}` | `{status "ok", epoch}` — an existing atom answers its epoch, a new one 0 | — |
| `atoms/:a/deref` | — | `{root_key, epoch, lease_id, lease_ttl 60, root_blob?, skeleton_blob?, skeleton_sha?, freelist_sha?, freelist_blob?, root_count?, graft_sha?}` | 404 `{status "not-found"}` |
| `atoms/:a/cas-root` | `{atom_id, old_key, new_key, retired_keys, root_blob, skeleton_blob, skeleton_sha, freelist_sha, freelist_blob, root_count, graft_sha?}` | `{status "ok", epoch}` | 409 `{status "conflict", current_key, current_epoch, root_blob?, skeleton_blob?, skeleton_sha?, freelist_sha?, freelist_blob?, graft_sha?}` · 403 `{status "denied", reason, detail}` |
| `release-reader` | `{lease_id}` | `{status "ok"}` | — |
| `renew-lease` | `{lease_id}` | `{status "ok", lease_ttl 60}` | 404 `{status "not-found"}` |
| `atoms/:a/watch` (WebSocket) | — | 101; pushes §6 | 400 `{error "Missing atom_id parameter"}` |
| `presign` | `{keys [hex…], mode "read"\|"write"\|"head"\|"delete", ttl?}` | `{urls {hex url…}, ttl}` | 503 `{error "Domain bucket not provisioned"}` |
| `credentials` | `{}` | `{endpoint, bucket, region, expires_in}` — never a key or a secret | 503 `{error "No bucket provisioned"}` |

An atom id is one path segment: an atom's registered name with each `/` written `%2F`. A broker may add a
`_server_timing` field to any answer; a client ignores fields it does not know.

Semantics the shapes carry:

* **cas-root** compares `old_key` with the atom's `root_key` (`nil` = `nil` for an empty atom); an unknown atom is
  created at `old_key` and then CASed. On success the atom's `root_key` becomes `new_key`, `root_blob`,
  `skeleton_*`, `freelist_*`, `root_count` and `graft_sha` are stored, `epoch` increments by one, and each of
  `retired_keys` is queued for collection at the new epoch. A broker need not check that `new_key` is a content address
  of `root_blob`; a reader does (§7).
* **deref** takes a reader lease `{lease_id, atom_id, epoch, expires_at = now + 60 s}`. A broker that collects
  retired blobs never deletes one below the lowest leased epoch. A client holding a value derived from a deref holds
  the lease (renews it before 60 s) and releases it when done.
* **403** is the broker's write policy refusing the move; a client throws and does not retry.
* **409** carries the winner's root and, when it has one, its graft (§7): a loser can retry without a second deref.

## 3. What a broker serves

A broker must serve `create`, `deref`, `cas-root`, `presign`, `release-reader` and `renew-lease`. `watch` and
`credentials` are optional: a client that finds no watch polls with deref.

eve has a broker of each kind. `eve.net.broker` is passive, as production's is: it compares root keys, stores root
blobs, keeps leases that expire (`renew-lease` moves the expiry), queues each CAS's `retired_keys` at its epoch and
hands them to an `:on-retire` hook once no lease is below that epoch (it deletes nothing itself), presigns through a
`:presign` function, and refuses with a 403 what its `:validate` hook names. Its rows sit behind a row store — in memory,
or in one eve disk atom so the broker survives a restart. It never reads a value.

`eve.net.gateway` is a member serving its own domain: it serves the required routes over the atoms of an eve disk domain, its store an object
store it presigns for (`eve.net.store`, or any S3-compatible store). It holds no blob, answers every request from the
domain's own atoms, keeps no lease past a request, and deletes nothing from the store.

## 4. The skeleton blob (`SKEL`)

Bytes: `"SKEL"` (0x53 0x4B 0x45 0x4C) · version u32 **little-endian** · prior-count u32 LE · branch-count u32 LE ·
prior SHAs (32 B each) · branch SHAs (32 B each).

## 5. Keys

* An atom's root key (`root_key` / `new_key` / `current_key`): the client's content address of its root blob — the
  SHA-256 hex (64 lowercase hex characters) of the VSNP snapshot (§7). A broker stores it as an opaque string.
* An object-store key is the bare `<sha>` of the blob it names.

## 6. The watch push

`{type "root-changed", atom_id, root_key, epoch, delta_keys [], root_blob? (when its base64 is ≤ 4096 characters),
skeleton_blob?, skeleton_sha?, freelist_sha?}`, sent to every socket watching the atom after a successful cas-root. A
client may send `{type "ping"}`.

## 7. The blob wire

* **A block** is the object whose key is the SHA-256 hex of its body; the body is the block's seal image exactly as
  the kernel seals it (ValueScript's `src/vs/kernel/wasm/eve.vs`, THE SEAL IMAGE): `[type-tag:1][payload]` with every
  inner 256-B child slot replaced by its bands 48..112 — 64 bytes, `[the epoch / spec band 32][the child's band 32]`
  (the spec half zero when the slot's HAS_SPEC_ID is set; a relaxed vector interior's RRB size table at the first 4)
  — its bytes 0..47 (CID, inner offset, VERSION, FLAGS) and 112..255 (the descriptor band) left out; an
  EMBEDDED-value slot as `[48 zero bytes][its bytes 48..255]`; a deferred-seal slot's band filled from its child, a
  T_VEC node cut at its last occupied slot. A reader ADOPTS a body: it expands the image to the block's layout,
  rebuilds the block — its type CID @0 from the tag, its own offset @16, VERSION 2, ACTIVE, the root's meta words —
  writes every child slot as a REFERENCE BY CONTENT (CID 0, inner 0, VERSION 2, ACTIVE, the child's key @80),
  answered through its SHA table once the child is adopted, else faulted by key, and re-seals the rebuilt block
  against the key. The image carries no slot FLAGS: a body whose vector node holds a 64-byte run of zeros where a slot
  is (an EMBEDDED value) is refused.
* **A set root** is a map root block (tag 0xED) whose set-ness is a flag byte outside the sealed payload; its parent
  slot carries the root's SHA XOR 0xEE (32 bytes). A key equal to a body's SHA XOR 0xEE…EE therefore names a set
  root: the reader adopts the body as a map root with the set flag.
* **Scalar children** ride inline in the slot's child band (slot bytes 80..111, the last 32 of its 64 image bytes:
  `[type-CID 16][payload][zeros]`): nil, booleans, int32, the 8-byte kinds, and a number's FLOAT64 box — a double (8
  payload bytes) or a long (16: the double and the exact i64).
* **A value child** — a string (one leaf), keyword, symbol, char, bigint, bigdec or ratio — is a blob: its value
  block's CANONICAL bytes `[CID or typekey 16][count u32 @16][word u32 @20][count bytes @24]`, every head byte that is
  not content written as zero (a string leaf's index word @20; a flat value's bytes 4..15, and its word @20 unless it
  is a char's code point). The band and the key are the SHA-256 of those bytes.
* **Not portable** (a writer refuses the value naming it): a string past one leaf (a rope), a function, an atom, a
  regex or a big-integer box (a band holding a local offset), a list holding a collection (the list copies the child
  root inline: no child block), a sorted map.
* **The snapshot** (`root_blob`): `VSNP` — magic u32 LE 0x56534E50, entry count u32 LE, then per top-level entry
  `[klen u32 LE][key bytes][vlen u32 LE][value bytes]`, each in the kernel's serial form (`[0xEE 0xDB][tag][payload]`,
  a collection value as its masked 256-B slot), plus tag 7 — an integer outside int32, its 8 bytes little-endian two's
  complement. `root_key = sha256(root_blob)`.
* **The graft**: `GRFT` V3 — magic u32 LE 0x47524654, version u8 3, flags u8 0, epoch u32 LE, timestamp u64 LE (lo,
  hi), 6 reserved, old root SHA (32), new root SHA (32), three u32 LE counts, branch SHAs, new blob SHAs, retired SHAs.
  It is a blob under its SHA, which is `graft_sha` on cas-root; the broker stores it with the root and echoes it on
  409 as `graft_sha`. A loser fetches the winner's graft by that key and retries at once when its READ BLOOM holds
  none of the winner's branch and retired keys. The read Bloom is 512 bytes (4,096 bits); a key's four positions are
  the u32 LE at its bytes 0, 4, 8 and 12, each mod 4,096; bit h is bit h & 31 of the LE word h >> 5. A reader marks
  the node each map lookup ends in, by that node's key.
* **Encryption** (optional, default on for network atoms): a block's body is AES-256-CTR under the per-block HKDF key
  (`eve.net.crypto`); the key (`<sha>`) stays the plaintext's SHA.

## 8. Credentials

Clients never hold the domain's object-store secret. `POST …/presign` is the only way to a blob URL: a client batches
≤ 100 SHAs per call, `mode "read"` for GET, `"write"` for PUT, `"head"` for the HEAD a client makes before a PUT (the
dedup: a presigned URL signs its method, so a HEAD needs its own URL), `"delete"`; `ttl` ≤ the broker's cap (300 s
default, 3600 s max). `POST …/credentials`, where a broker serves it, answers `{endpoint, bucket, region,
expires_in}` and nothing else: no answer of any route carries the store's secret or access key id.
