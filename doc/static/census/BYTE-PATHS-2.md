STATUS: COMPLETE — 2026-11-11 11:10 PM ET. §1 digests · §2 network files and the live wire generation · §3 defenses ·
§4 FORMAT-DECISIONS / HOST-CONTRACT · §5 fixture discipline · §6 evidence for the assumptions.

# Byte paths in Eve — census, parts 5–8 (continues `BYTE-PATHS.md`)

Provenance: tree `/home/user/eve`, HEAD `67ab7da` (the pin `4a92129` plus `doc/static/**` only). Read-only: nothing
built, no suite run. Each row is tagged **[code]** (read in the source), **[doc]** (a document says), or **[ran]** (a
JVM one-liner run here: OpenJDK 21, Clojure 1.12.0, classpath `doc/static/probes/eve-cp.sh`, fresh heap domains, each
under 5 s). Paths are relative to `/home/user/eve`. Short names as in the prior file: **BA** =
`adapters/codec/byte-array/src/eve/codec.cljc`, **JS** = `adapters/codec/js/src/eve/codec.cljs`.

## 1. Digests and canonical bytes

### 1.1 Every digest / hash at the pin

| # | Name, where | Bytes hashed | Algorithm | Cached | Same on two hosts / two build orders? |
|---|---|---|---|---|---|
| H1 | root content digest, `eve.digest/root-digest` `src/eve/digest.cljc:163-199` | the **flat codec bytes of the whole root**, built in one array: `(binding [ser/*shared-write* true] (codec/value->eve-bytes v))` (`:172-173`) | SHA-256 (`eve.crypto/sha256`, `:177`) | 48-byte write-once side block "DGS1" in the slab, `+16..+47` as 8 LE i32 words (`:18-23`, `:146-161`); its pointer in the root header's descriptor POINTER column (`:79-83`). Map, set, vector, list roots only (`src/eve/map.cljc:1078`, `set.cljc:696`, `vec.cljc:387`, `list.cljc:133`) | build order: **yes** [ran] — `{:a 1 :b 2 :c 3}` built a,b,c and c,b,a in two domains: one digest (`2a69be9c cbee0a96 …`, word-wise hex). Hosts: **byte-array-codec hosts only.** The JS codec has no flat form: `(throw (ex-info "eve.codec: no flat form on this codec (value->eve-bytes)" {:codec :js}))` (JS:1470-1476), so on the ClojureScript family `compute-digest` answers nil and `=` walks. By-reference content: nil [ran] (`:f inc`) |
| H2 | root hash word, `eve.digest/root-hash` `digest.cljc:201-215` | **not bytes**: "exactly what the resident `hash` computes" (`:14-16`) — `p/hash-unordered` / `p/hash-ordered` (`src/eve/platform.cljc:29-41`) → `eve.core/hash-unordered-coll` → the host's collection fold over the **host `hash` of each element** (`adapters/coll/jvm/src/eve/core.clj:87-88`: `clojure.core/hash-unordered-coll`) | the host's (Clojure's Murmur3 mix on the JVM) | same side block, `+12` | **no, by ruling**: "an eve map's hash is NOT expected to equal the host map's hash of the same contents — on the JVM the two happen to coincide, on jank they do not" (`test/eve/collection_hash_test.cljc:18-26`). [ran] JVM: eve `{:a 1 :b 2 :c 3}` and the host map both hash 1685874909 |
| H3 | HAMT placement hash (map, set, sorted) | the key's flat codec bytes (`encode-key`) | Murmur3_x86_32, seed 0, `eve.hamt-util/portable-hash-bytes` `src/eve/hamt_util.cljc:44-99` | in the HAMT node beside the entry (prior file §2.2) | yes: arithmetic through `eve.int32`, "identical output on every host" (`hamt_util.cljc:5-6`) |
| H4 | "eve's hash", `xxh3-128-bytes` `hamt_util.cljc:661-665`; `fold-hash-int` `:673-679` | a host byte array. One caller: the deftype type id, over `(codec/encode-key name)` (`src/eve/deftype/registry.cljc:57`). `key-hash-bytes` (`:667-671`) has no caller | XXH3-128, seed 0, default secret, 16 big-endian bytes; fold = XOR of four BE u32 | none | yes (portable 32-bit limbs, `:635-649`) |
| H5 | a **second** XXH3-128, `eve.net.xxh3` `src/eve/net/xxh3.cljc` (175 lines), over byte **vectors**; reached as `eve.crypto/xxh3-128` (`adapters/crypto/java/src/eve/crypto.clj:37` → `src/eve/net/portable_crypto.cljc:30-31`) | `eve.net.codec` key images (`prim/xxh3-128 kb`, `src/eve/net/codec.cljc:392-393`); the snapshot's entry order (`src/eve/net/snapshot.cljc:121`) | same algorithm, separate source text from H4's (`hamt_util.cljc:635-649`) | `*checked*` memo | yes |
| H6 | ExtCID v3 block key, `eve.net.codec` | the **block image** (kernel layout; a child appears as its 32-byte key) | SHA-256; a set root's key is `SHA-256 XOR 0xEE×32`; an attested record's is `SHA-256(image ‖ 128-byte attest band)` (`codec.cljc:286-293`, `:601-610`, `:731-734`) | the key *is* the address; `*memo*` | yes — canonical by construction and **checked on read** (`decode-rec`, prior file §4.1) |
| H7 | atom seal, `eve.atom/seal` `src/eve/atom.cljc:1526-1553` | for each block the root reaches (`retire/reach`, sorted by offset): **the slab offset, then the raw block bytes** — "a fixed-size class's whole block, an overflow block's first 32 bytes" (`:1530-1531`, `:1544-1548`) | 32-bit FNV-1a-style fold, basis `0x811c9dc5`, prime `0x01000193` (`:1524`, `:1549`); the offset is folded as one i32, not four bytes | none; recomputed under a pin | **no — it names a stored version in one domain**, not content. Used by the thread rendezvous tests (`test/eve/thread_domain_test.cljc:6`) |
| H8 | aegis record leaf, `eve.aegis.merkle/record-leaf` `src/eve/aegis/merkle.cljc:79-127` | its own field encoding, **not** eve bytes: string → `sha256(utf-8)`, keyword → the same over its **name** (namespace dropped, `:97`), non-negative integer → 32-byte BE, nil/false → 32 zeros; chained `acc = sha256(acc ‖ f)` | SHA-256 | none | yes (byte vectors + `eve.crypto`). Overlaps by design: nil = false = `[]` (`:15-16`, `:99`); `"a"` = `:a` |
| H9 | aegis sidecar tree, `merkle.cljc:148-191` | leaf `sha256(0x00 ‖ u32-be(len kb) ‖ kb ‖ field)`, node `sha256(0x01 ‖ l ‖ r)`, entries sorted by key UTF-8 bytes | SHA-256 | none (rebuilt per call, `build` `:166-179`) | yes |
| H10 | aegis content commitment, `src/eve/aegis/commit.cljc:33-34` | `label ‖ iv ‖ plaintext` (caller's bytes) | HMAC-SHA256 under the room key | — | yes |

### 1.2 What the tests and fixtures pin

| Test / fixture | Rows | What it pins | Runs where |
|---|---|---|---|
| `test/eve/digest_cache_test.cljc` (123 lines) | 5 deftests | hash computed once (fold counters, `:45-65`); `=` exact across separately built domains (`:67-93`); "the latched read-back is the computed digest, word for word" (`:95-103`); by-reference content walks (`:105-113`). **No digest value is pinned** — only self-consistency | named by **no** runner under `test/eve/runner/` (grep); reached only by the JVM directory scan `cognitect.test-runner -d test` (`deps.edn:61`) |
| `test/eve/collection_hash_test.cljc` (60 lines) | 2 deftests | vector / list / set hash = the host value's hash; maps only "equal eve maps hash equally" (`:27-54`) | bb, cljr, glj, lpy, clje, nbb, jolt, jank, cljs runners (grep) |
| `test/eve/merkle_phase5_test.cljs` (117 lines) | 3 deftests | **no digest at all.** The legacy `eve.shared-atom` change decision "identity → root digest → structural `=` (the digest step is a Phase 3a seam; today it falls through to `=`)" (`:4-6`): an equal-but-rebuilt swap installs no new root and still fires the watch | ClojureScript only (`shadow-cljs.edn:12-15`) |
| `test/eve/fixtures/eve-bytes.edn` | 72 rows | flat/key bytes per value, 15 with the Murmur3 `:hash` (H3) | every host runner (prior file Part 7) |
| `test/eve/fixtures/xxh3-vectors.edn` | **311** rows `{:len n :hash "32 hex"}` | XXH3-128 "of the kernel", lengths 0..300 and long inputs to 10,000; input byte i of length n is `(i*31 + n*7) mod 256` (`:1`). Checked against `eve.net.xxh3` (H5) by `test/eve/net/xxh3_test.cljc:23` | — |
| `test/eve/xxh3_kat_test.cljc` (141 lines) | inline KATs | H4 against "the reference XXH3-128 … generated with the python xxhash library" (`:4-8`); gates `register-xxh3-128-impl!` | — |
| `test/eve/fixtures/vsv2-blocks.edn` | **22** values, **226** blocks (108 hamt-node, 49 vec-node, 29 value, 12 vec-root, 11 map-root, 8 list-node, 5 set-root, 4 list-root) [ran: grep -c] | H6: every block image and its SHA-256 as ValueScript's kernel cut it; `eve.net.codec` must "decode and re-encode byte-exact" (`test/eve/fixtures/README.md:3-5`; `test/eve/net/codec_fixture_test.cljc:4,16`); its `:keys` rows pin H5 (`xxh3_test.cljc:27-28`) | — |
| `test/eve/fixtures/aegis-kernel-vectors.edn` | **53** rows (23 opening, 12 bytes, 7 root, 4 hash, 4 leaf, 3 t3) [ran] | "the kernel's answers for the aegis primitives eve holds … Fields: 32-byte little-endian hex; the digest 48 bytes" (`:1-2`) — proof-era field-hash vectors | **loaded by no test** at the pin (grep `aegis-kernel`, `:kind :t3`, `:kind :opening` over the tree: the file alone) |
| `vsv2-blocks-256.edn` | — | named by `test/eve/fixtures/README.md:3` and `test/eve/net/codec_compat_test.cljc:5` | **not in the tree** (`ls test/eve/fixtures`) |

### 1.3 The digest changes what `=` answers [ran]

`equiv` asks the digest first and walks only on `:walk` (`src/eve/map.cljc:1151-1156`; `digest-verdict` `digest.cljc:217-228`).

| Expression (JVM) | Host maps | Eve maps (each in its own heap domain) |
|---|---|---|
| `(= {:a 0.0} {:a -0.0})` | `true` | **`false`** — the flat bytes differ in the sign bit, so the digests differ and the verdict is `:unequal` |
| `(= {:a ##NaN} {:a ##NaN})` | `false` | **`true`** — equal bytes, equal digests |
| `(= {:a 1} {:a 1N})` | `true` | `true` (both `eedb03…`), hashes equal |

So byte equality of the canonical form already *is* eve's equality for digest-carrying roots on byte-array hosts, and it
is not Clojure's `=` at the float edges.

## 2. The rest of the network layer

### 2.1 One row per file (all [code]; `bytes.cljc` is in the prior file, Part 3)

| File (`src/eve/net/`) | Role | Format | Entry points |
|---|---|---|---|
| `snapshot.cljc` (140) | the atom's root blob | `VSNP`: magic u32 LE `0x56534E50`, entry count u32 LE, per top-level entry `[klen u32][key][vlen u32][value]` (`:2-3`, `:63-67`). A scalar is "the kernel's scalar serial form": nil empty; `[EE DB][tag][payload]` with tags 1 false, 2 true, 3 int32, 4 float64, 5 / 6 string (u8 / u32 length); **a keyword is a string led by `:`, a symbol a string led by `0x01`; tag 7 is an int64** (`:4-9`, `:47-61`). A collection is a **256-byte** reference slot — VERSION 2 @32, ACTIVE @40, the key @80 (`:21-24`). Entries ordered by the XXH3-128 of their key bytes (`:121`); root key = hex SHA-256 of the blob (`:123`) | `snapshot` :115, `frame` :63, `unframe` :69, `serialize-scalar` :47, `unserialize` :82, `entries` :125, `ref-keys` :133, `value-of` :136 |
| `graft.cljc` (63) | swap graft, read Bloom | `GRFT` V3, LE: magic u32 `0x47524654` @0, version u8 3 @4, flags u8 @5, epoch u32 **@6**, timestamp u64 **@10**, 6 reserved, old root SHA @24, new root SHA @56, three u32 counts @88, SHA lists from @100 (`:4-5`, `:24-28`); Bloom 512 bytes | `encode` :20, `decode` :30, `bloom` :51, `might-contain?` :57, `possible?` :60 |
| `blobs.cljc` (120) | blocks to / from the object store, as steps | one object per block: body = the image (ciphertext under `:gak`), key = hex SHA-256 of the plaintext (a set root: XOR 0xEE) | `fetch-blocks` :64, `upload-blocks` :105, `key-of` :29, `address-ok?` :34; 32 requests at a time (`PARALLEL` :41) |
| `crypto.cljc` (63) | blob encryption | §2.3 | `derive-block-key` :34, `crypt-blob` :45, `decrypt-blob` :55, `hkdf-sha256` :23 |
| `store.cljc` (79) | the object-store role (dev) | HTTP `PUT/GET/HEAD/DELETE …/blobs/<key>?mode=&exp=&sig=`; a blob is kept **as the base64 text it arrived as** (`:19`, `:56`) | `store` :67, `handle` :40, `stats` :77 |
| `presign.cljc` (23) | dev presigner | URL query `mode`, `exp`, `sig`; `sig` = hex of HMAC-SHA256 under the secret over the key, the mode and the expiry joined by a vertical bar (`:9-10`) | `hmac-presigner` :12, `hmac-ok?` :20 |
| `broker.cljc` (266) | passive broker: "compares root keys, stores root blobs and leases, and never reads a value" (`:2-3`) | JSON routes; a row is `{:root_key :epoch :root_blob …}` | `broker` :255, `dispatch` :216, `route` :161, `IAtomRows` :41, `mem-rows` :94, `row-step` :64 |
| `broker_disk.cljc` (31) | the broker's rows in one eve disk atom | a slab map | `disk-rows` :9 |
| `gateway.cljc` (277) | a member serving its disk domain's atoms as network atoms | same routes. **Slab ↔ wire goes through plain host values both ways**: deref = `(ea/read-pinned a ed/eve->clj)` (`:164`) → `na/snapshot` (`:151`); cas-root = `na/value-step` (`:203`) → `patch` (`:175-190`) → `swap!` (`:208`) | `gateway` :253, `stats` :276, `atom-id` :52 |
| `run.cljc` (69) | sans-IO driver | steps `{:done}` `{:request}` `{:requests}` `{:sleep}`; a body is base64 text (`:as :b64`) or the host's byte array (`:as :bytes`) (`:8-12`) | `run` :47, `then` :33, `fmap` :42 |
| `platform.cljc` (15) | the host's platform map | — | `platform` :9 |
| `json.cljc` (186) | JSON for wire bodies, no host library | reads UTF-16 units; integers exact below 10^17 (`:101-105`) | `write-str` :34, `read-str-or-nil` :176, `read-str` :181 |

### 2.2 Which wire generation is live at the pin

* **Live** [code]: the *envelope* of `doc/network-atoms/WIRE.md` §7 — one content-addressed object per block, the VSNP
  snapshot as `root_blob`, the GRFT V3 graft — carrying **ExtCID v3 block images** (32-byte child slots) written by
  `eve.net.codec/encode-rec`. `eve.net.atom` requires `eve.net.snapshot`, `graft`, `blobs`, `codec`; not `pages`.
* **Not live** [code]: `eve.net.pages` (v3 pages, 1 MiB regions). Its only reference in `src adapters net-local` is a
  docstring (`src/eve/net/codec.cljc:372`; grep), plus `test/eve/aegis/pages_v3_test.cljc`.
* **Doc vs code**: WIRE.md §7 still describes the block body as the v2 seal image — "every inner 256-B child slot
  replaced by its bands 48..112 — 64 bytes" (`doc/network-atoms/WIRE.md:81-85`) — while the codec says "every slot its
  child's 32 bytes … A v2 image (a slot's 64 or 256 bytes) is refused" (`src/eve/net/codec.cljc:662`) and the fixture's
  one-entry map root is 37 bytes (`ed 01000000` + 32; `test/eve/fixtures/vsv2-blocks.edn:6`). The snapshot paragraph
  (`WIRE.md:106-109`) matches the code, including the 256-byte slot for a top-level collection.
* **A swap uploads** [code, with prior file §4.3]: the fresh block images (a presigned HEAD each, a PUT for those the
  store lacks, `blobs.cljc:105-120`), the snapshot blob and the graft blob (no HEAD); then `cas-root` JSON with
  `old_key`, `new_key`, `root_blob` (base64), `root_count`, `graft_sha`, `retired_keys`.
* **A deref downloads** [code]: the `deref` JSON (`root_key`, `root_blob`, epoch, lease). When the key moved: the
  changed entries' block trees, level by level — one presign call per 100 keys, GETs 32 at a time — each decrypted,
  checked against its address, then `codec/decode-rec` (`src/eve/net/atom.cljc:281-317`).

### 2.3 Encryption

| Layer | Key | Cipher / IV | Chunking | Integrity | Source |
|---|---|---|---|---|---|
| network blob, with `:gak` | per block: HKDF-SHA256(ikm = the 32-byte GAK, salt = nonce = **the first 16 bytes of the blob's key**, info = page-id u16 BE ‖ slot u32 BE = 0, 0) | AES-256-CTR, IV = SHA-256(block key)[0..16), counter big-endian | **none — the whole blob is one transform** | none in the cipher; "the address is the check": the decrypted bytes must hash to the key (`blobs.cljc:59-62`). Deterministic: "equal plaintexts encrypt equally" (`blobs.cljc:10`) | `crypto.cljc:5-10,34-51`; `blobs.cljc:21-27` |
| the root snapshot in `cas-root` / `deref` | — | **plaintext, even with `:gak`** (`SECURITY.md:19`). The broker's docstring says the opposite: "root and skeleton blobs (ciphertext when clients encrypt)" (`broker.cljc:31`) | — | — | doc vs doc |
| disk domain at rest, `eve.keyring` | per slab class: HKDF-SHA256(GAK, domain salt, `"eve.keyring"` ‖ class) (`src/eve/keyring.cljc:255-259`) | AES-256-CTR XOR over block bytes, IV = class u32 BE ‖ block u32 BE ‖ counter u64 BE (`:14-16`) | keystream computed 256 bytes at a time (`CHUNK 256`, `:261`); "1 MB of keystream" is the **cache bound** — "cleared past 4,096 chunks" (`:271-273`) — not a block or chunk size | "no integrity"; "a block rewritten in place reuses" its keystream (`:18-19`) | `keyring.cljc:13-25` |

## 3. Defenses against untrusted bytes in the network layer

### 3.1 Checks that exist, and reads that trust

| Reader | Checked (file:line) | Trusted / not checked |
|---|---|---|
| `eve.net.codec/decode-rec` (`codec.cljc:1028`) — the canonical verifier | size before every read (`need!` `:817`); value block byte count `:834`; char pad and code point `:837`; keyword leads `:` `:840`; node tag `:859`; **HAMT depth: `shift > 125` refused** `:860`; data/child overlap `:865`; a kv value is one slot `:878`; **each key hash recomputed** `:880`, its path `:882`; nil child `:888`; a one-entry inner node `:892`; **the image re-encoded and compared** `:893`, `:846`; map root size `:921`, count `:926`, duplicate keys `:928`; vec node flags / pad / slot count `:945-951`; vec root count / shift / tail `:968-979`; list length `:1000-1004`; empty block `:1021`; unknown tag `:1025`. Refusal: `ex-info {:type :non-canonical :reason code}` (`:812-815`) | nesting depth of collections inside collections; total bytes or block count |
| `eve.net.codec/decode`, `value-of-band` (`:782`, `:1071`) — the plain decoder | root image size and tag `:677-679`; kv value length `:699`; node tag `:702`; list node shape `:761-762`; tag `:766` | **no canonical check, no key-hash check**; key length `kl` (`:695-697`) trusted to the host's bounds error. This is the reader behind `snapshot/value-of` (`snapshot.cljc:140`) and `na/value-step` (`atom.cljc:342-356`) — i.e. the **gateway's read of a network writer's value** (`gateway.cljc:203`) |
| `eve.net.pages/read-pages` (`pages.cljc:372`) | a slot's page is given `:351`, its offset lies in the page `:353`; value block image = its band `:416`; header version byte = 3 `:419`; block digest = header SHA `:434`; = the parent slot's band (or XOR for a map root) `:435-436`; then `decode-rec` over the blocks `:450` | block size from the page header `(rd32 page 24)` `:421` (clamped by `min (count page)` `:422`); recursion per child (`visit` `:441`, `reuse` `:395-402`) |
| `eve.net.snapshot/unframe` (`snapshot.cljc:69`) | length ≥ 8 and magic `:73-74`; entry end ≤ blob length `:79` | `vl` is read **before** that check (`:78`): [ran] a count of 5 over one entry → the host's `IndexOutOfBoundsException`; `klen` `0xFFFFFFFF` → `ArithmeticException: integer overflow`. Trailing bytes ignored [ran]. Entry order and duplicate keys unchecked (`into {}` `:140`: last wins). **`sha256(root_blob) = root_key` is not checked on a client deref** (`atom.cljc:288-289`); the gateway checks it on cas-root (`gateway.cljc:199-200`); the passive broker never does (`broker.cljc:182-201`) |
| `eve.net.snapshot/unserialize` (`:82`) | magic `:90`; unknown tag `:100` | **"256 bytes" means "reference" before the magic is looked at** (`:89-90`). [ran] a 252-byte string's serial form is 3 + 1 + 252 = 256 bytes and reads back `[::ref "7878…"]`. String length byte trusted (`:97-98`) |
| `eve.net.graft/decode` (`graft.cljc:30`) | length ≥ 100 `:35`; magic `:36`; version = 3 `:37`; length ≥ 100 + 32·(counts) `:42` — [ran] a count of 2^32−1 → `"graftV3: short SHA lists (100 < 137438953540)"` | flags and reserved bytes; trailing bytes ignored [ran] |
| `eve.net.blobs/got` (`blobs.cljc:53`) | HTTP 2xx `:57-58`; decrypt, then address `:59-62` (either the SHA, or for a `0xED` image the SHA XOR 0xEE, `:38-39`) | body size; base64 text — `from-b64` skips any character outside the alphabet ([ran] `"AQ!!ID"` → `[1 2 3]`) |
| `eve.net.json` reader (`json.cljc:140-174`) | strict grammar; nil, not an exception, on malformed text `:48-52` | **nesting depth**: [ran] 1,000 levels of `[` parse, 3,000 → `StackOverflowError` (default 1 MB stack). Both servers parse the body **before** they check the bearer (`broker.cljc:244-249`; `gateway.cljc:232-240`) |

### 3.2 Depth and size limits

* Depth: only the two structural ones above (HAMT ≤ 26 levels; a vector's shift must equal what its count names). A
  collection nested in a collection has **no limit**, on either side: [ran] `codec/encode` of a vector nested 300 deep
  works (602 blocks) and `decode-rec` reads it; at 600 deep `encode` throws `StackOverflowError`.
* Total size: **none** (grep `MAX|limit` over `src/eve/net`). What exists are cache bounds — `MEMO-MAX 20000`
  (`codec.cljc:366`), `BLOCKS-MAX 100000` (`gateway.cljc:127`) — a retry bound (`MAX-RETRIES 50`, `atom.cljc:42`), 100
  keys a presign call (`broker.cljc:205`), and one writer-side refusal: a block past 10,240 bytes has no page class
  (`pages.cljc:99-101`).

### 3.3 The stated threat model

* `SECURITY.md` has no section on malformed or hostile bytes (grep for untrusted / malformed / adversar / validate /
  canonical: no hit). Its scope: "What eve protects, what it does not" (`SECURITY.md:3`), i.e. what is encrypted.
* Slab domains are trusted: "A disk domain is shared memory: every process that can open its files can read and write
  every atom in it … a process that writes garbage into a domain's files can corrupt it for every reader. Give a
  domain's directory the file permissions you would give the data itself." (`SECURITY.md:73-75`)
* The broker's own statement: "Writer: whoever holds :api-key, refused further only by :validate. What it sees: root
  keys, root and skeleton blobs …, timing; never a block. What it refuses: a stale old_key (409), what :validate names
  (403). It deletes nothing itself." (`src/eve/net/broker.cljc:30-32`)
* The store learns content: "a blob's address is the SHA-256 of its plaintext, so the store can confirm a guessed
  value" (`SECURITY.md:42-43`).

## 4. FORMAT-DECISIONS and HOST-CONTRACT

### 4.1 `doc/unification/FORMAT-DECISIONS.md` — every numbered section [doc]

| § (lines) | Statement | Constraint on a new self-contained byte format |
|---|---|---|
| 1 (`:10-54`) | two i32 words in the `.root` header (offsets 24, 28) let a lock-only host join the CAS protocol | none on value bytes; whatever publishes a value into a domain goes through this protocol |
| 2 (`:56-83`) | flat `[EE DB ED/EE/EF][count i32]([len i32][bytes])*`; set elements and map entries "in ascending order of their encoded (key) bytes — unsigned, lexicographic, a shorter prefix first"; "A reader accepts any order" | this is the existing self-contained form and the digest's input (H1). A new form is either these bytes or must define its mapping to them. Canonical order is a writer rule only |
| 3 (`:85-100`) | a key's (and a set element's) bytes "must … be a pure function of the key's value"; a collection used as a value "is still a pointer (lazy, shared, path-copied)" | key bytes inside a static form must equal `encode-key`'s, or every lookup and every conversion re-encodes and re-hashes |
| 4 (`:102-135`) | scalar tags 01–0F, 14; a symbol segment past 255 bytes is refused; int32 vs int64 by magnitude; on CLJS "an integral double and `-0.0` go out as int32 and an integer past ±2^53 as float64 … the host's number model, not the format" | reuse the tags unchanged. Bytes are a function of the value **per number model**, not across hosts |
| 5 (`:137-147`) | `eve-bytes.edn` rows run on every host; a `:hosts` skip "is counted and printed" | a new format ships its golden rows this way (§5 below) |
| 6 (`:149-191`) | a HAMT node at shift s indexes by bits `[s, s+5)` of the hash's 32-bit pattern; `node-bit` reads pre-fix nodes | a static form that carries a hash index uses this Murmur3 pattern or pays a re-hash on conversion; it inherits no legacy slot |
| 7 (`:193-221`) | a deftype block starts `[type-id u8][name-hash u24 LE]`; id = `0x40 + hash mod 173`; "0x00–0x3F stay eve's pointer and fast tags … 0xF0–0xFF are reserved". **Stale on the hash**: the code derives the id from xxh3 (H4; prior file M7) | the tag byte alone does not name a type (173 values, collisions settled by the name hash *in the block*): self-contained bytes must carry the name or its hash. New tags come from 0x20–0x3F |
| 8 (`:223-267`) | BIGINT 16, RATIO 17, BIGDEC 18; "Canonical by value, because a key's bytes are a function of its value"; a host without the number "refuses its bytes loudly on read — never a double"; "`eve.net.codec` (the wire, the kernel's own format) still refuses all three … neither is this format" | canonical-by-value binds any byte-compared form; readers refuse, never approximate. States outright that the wire is a different format from eve bytes |
| 9 (`:269-306`) | REF 15 `[realm u32][handle u32]` is process-local; "Never in a shared domain"; `0xF0–0xFF` block kinds are process-private | a self-contained form must refuse a reference at any depth (the `*shared-write*` binding already does, `digest.cljc:172`) |
| 10 (`:308-329`) | a value past 64 bytes goes out of line as `[EE DB 19][block i32][len u32]`; "the writer is not wired into `encode-val`" | 0x19 is a slab pointer and cannot occur in self-contained bytes; the 64-byte threshold is "a format fact" of the slab form only |
| 11 (`:331-360`) | every holder of a block is counted on its 28-byte descriptor row; "a cross-domain plain reference is an error (`$` materializes a crossing)" | a static value has no descriptor row and no count: a slab block may not point at it under this rule, and nothing reclaims it |
| 12 (`:363-385`) | the atom slot table grows by root words 20 and 36 | none |

### 4.2 `doc/unification/HOST-CONTRACT.md` — the clauses that bind byte access [doc]

| Clause (lines) | One line | Constraint |
|---|---|---|
| §2.1 (`:80-95`) | `-load-i32` / `-store-i32!`: signed, LE, at **any** offset; `cas` / `add` / `sub` need 4-byte alignment | plain i32 fields may sit anywhere; anything atomic must be aligned. An immutable form needs no atomics |
| §2.2 (`:97-112`) | i64 ops need 8-byte alignment; "exactness only up to 2^53" on JS | no i64 field a JS host must read exactly; none unaligned |
| §2.4 (`:126-139`) | `-read-bytes` "**May alias the region** … valid only until the next write to that range or the end of the current epoch pin, and must copy before retaining" | a view's lifetime is defined by the pin. Bytes outside a domain have no pin: the contract is silent on their lifetime |
| §3 (`:143-158`) | bytes through five operations; "only ever read bytes through an unsigned accessor" | signedness |
| §4 (`:162-185`) | "little-endian throughout"; UTF-8 lengths are byte lengths; hash arithmetic masked to 32 bits; offsets are class (3 bits) and block (29 bits) packed in a signed i32, `-1` nil, `-2` claimed; "NaN payloads are not normalised" | LE; an in-chunk offset typed as this i32 collides with the slab-qualified encoding unless kept apart; NaN bytes are not canonical today |
| §8.1 (`:265`) | `-read-bytes` is a view on Node mmap and the CLJS slab, a copy elsewhere | "operable in place" is not uniform across hosts: on the JVM, bb and SAB a byte read allocates |
| §8.2 (`:266`) | unaligned i32 is "a DataView behaviour the HAMT layout bakes in; the JVM VarHandle throws on misalignment" (byte-wise fallback) | a new layout can align its words and skip the fallback |
| §8.11 (`:275`) | "`hasheq` of Eve collections uses the host's Clojure hash … *not* the on-disk hash (that is Murmur3 over EVE bytes)" | the language-visible hash is not a function of the bytes (A10) |
| §12 (`:488-522`) | every slab access is an `eve.slab-io` name (`read-u8/u16/i32/bytes`, `write-*`, `cas-i32!`, `alloc!`, `free!`, `bytes-equal-at?`) over "a domain's slab I/O" | existing collection code reads only through a slab I/O. No name exists for "a byte array that is not a domain"; reuse means presenting static bytes as one |

## 5. Fixture discipline

| Fact | Evidence |
|---|---|
| A fixture is an `.edn` file under `test/eve/fixtures/`; "Each file says in its first lines what made it" | `test/eve/fixtures/README.md:3` |
| Row: `{:note .. :value .. :bytes "hex" :hash int :hosts #{..}}`, **one row per line**; lower-case hex; no `:hosts` = every host | `test/eve/fixtures/eve-bytes.edn:1-9` |
| One-row-per-line and the literal text `:hosts #{` are load-bearing: clojerl reads line by line and drops a row by text match; basilisp rewrites `#char ` and bare exponents before reading; glojure has no `clojure.edn` | `test/eve/eve_bytes_fixture_test.cljc:27-53`; `test/eve/net/codec_fixture_test.cljc:12-13` |
| The test is one `.cljc`; the file is read by relative path from the repository root (`slurp`, `fs.readFileSync`) | `eve_bytes_fixture_test.cljc:24,55-64` |
| Both directions and the hash: encode = hex (`:102`), decode = value (`:104`), Murmur3 of the bytes (`:107-111`); skipped rows counted and printed (`:97-99`). The encode side copies: "a codec may answer a scratch buffer, valid until its next call" (`:81-83`) | same file |
| `same-value?` says "holds … NaN to itself" but is `(= a b)` for everything but instants — a NaN row would fail its decode assertion as written | `:88-93` |
| The host is a reader-conditional with seven arms, no default: `:cljs :bb :clj :cljr :lpy :clje :jank` | `:22` |
| **Each runner names its test namespaces in a literal list**: `bb_main.clj:15`, `cljr_main.clj:14`, `glj_main.glj:25`, `lpy_main.lpy:50`, `clje_main.clje:22`, `jolt_main.clj:21`, `jank_main.jank:42`, `nbb_main.cljs:19,43`, `vs_main.vs:13`, `eve_test_main.cljs:71,271,379`. The JVM alone scans `test/` (`deps.edn:61`). No runner in that directory for `:lg` or `:cljd` | grep, `ls test/eve/runner` |
| Kernel-shared fixtures are copies: "When ValueScript cuts them again, copy the new files here"; `scripts/gen-xxh3-vectors.py` is the one generator in the tree; `eve-bytes.edn`'s `gen-fixtures.clj` is not in it | `README.md:5-8`; `ls scripts`; `find` |
| `eve.net.codec-fixture-test`, `eve.net.xxh3-test`, `eve.xxh3-kat-test` are named by `eve_test_main.cljs` only among the runners (plus the JVM scan) | grep |

To add a new format's golden bytes so every host checks them: (1) `test/eve/fixtures/<name>.edn`, header lines naming
the producer, one row per line in the row shape above; (2) a `.cljc` test that asserts encode, decode and any hash, with
host arms for the reader differences; (3) its namespace added to each of the ten runner lists; (4) rows for the values
no fixture pins today (NaN, `-0.0`, ±Infinity, non-BMP, a lone surrogate, a string past 256 bytes, bytes) — which needs
`same-value?` fixed first; (5) if the kernel must agree, the rows are cut by the kernel and copied.

## 6. Evidence for the assumptions

**A1 — is the scalar tag set a sufficient foundation? No, not as it stands.** The prior file's §1.5 holes: no bytes /
blob tag (a JVM `byte[]` comes back a vector of longs); no float32; a non-BMP char loses its high bits; `-0.0`, NaN and
integral doubles are not canonical across hosts; lone surrogates and a BOM change per host; symbols past 255 bytes
refused; a keyword whose name holds `/` reads back split; instants to the millisecond (whole seconds on clojerl); ratio
and bigdec refused on three hosts; records lose their type (zero bytes on CLJS); metadata never written; sorted
collections lose their comparator; `0x1A` / `0x1B` are CLJS-only; nil is zero bytes with no magic; an atom whose whole
value is a scalar past 1,023 bytes reads nil. Added here: (a) the tag byte is not one namespace even under the same
magic — VSNP's `[EE DB 07]` is an int64 (`snapshot.cljc:36,59`), eve's `0x07` a keyword; (b) in the VSNP form a string
`":a"` and the keyword `:a` are the same bytes and it reads back the keyword [ran] (`eve.net.codec` refuses such a
string inside a collection, `codec.cljc:209-212`; whether every writer reaches that refusal for a top-level scalar was
not traced); (c) length is used as a type: 256 bytes means "reference" [ran]; (d) the fixture suite cannot hold a NaN
row. The tags are a workable *alphabet* for a JVM-family value; they are not a cross-host canonical scalar set.

**A10 — hash / digest from packed bytes without materializing?**

| What | Hashed today | From packed bytes? |
|---|---|---|
| content digest (H1) | SHA-256 of the whole flat encoding, built as one byte array then copied into a vector of ints (`digest.cljc:172-178`) | yes if the static bytes **are** the flat bytes; if the form adds an index, offsets, chunks or another entry order, the digest needs a canonical re-emit (bytes → bytes, sorted by key bytes), not host values |
| HAMT placement (H3) | Murmur3 of each key's flat bytes | yes, per key, if key bytes are unchanged |
| `hash` of a collection (H2) | the host's fold over the host's `hash` of each element: a long, a string (UTF-16 on the JVM), a keyword, a double by the host's rule; a nested eve collection by its own word | **no** without decoding each element or re-implementing each host's scalar hash; and the word is host-specific by ruling, so it cannot ride in a cross-host form. The byte-derived candidate exists unwired: `fold-hash-int` — "what the language-visible `hash` returns for wide-hashable values" (`hamt_util.cljc:673-677`), one caller (the type id) |
| ExtCID key (H6) | SHA-256 of kernel block images | no — a transcode to another layout |
| seal (H7) | slab offsets and raw block bytes | no — names a place, not a value |

**A17 — scalar universes at the pin: six byte encodings of a scalar.** (1) EVE tags `[EE DB tag]`, in two wired codecs
with different subsets; (2) ExtCID v3 bands and value blocks — 16-byte type CIDs (prior file §4.1); (3) `eve.net.codec`
key images `[01][text]` / `[00][f64]`, where `1` and `1.0` are one key; (4) the VSNP serial form — eve's magic and tags
1–6, but keyword and symbol as prefixed strings and 7 = int64; (5) aegis fields — 32-byte SHA or big-endian integers
(H8); (6) JSON on the envelope. Plus the typed-array element subtypes 1–11 for columns. To not add a seventh, a chunk
format must take (1)'s scalar bytes verbatim — so a scalar's static bytes, key bytes and digest bytes are one string —
and say, per value kind, how it maps to (2), which the wire fixtures bind byte-exact to ValueScript's kernel; (2) has no
uuid, instant, big number, bytes or reference [ran: "no image for #uuid …"].

**Q6 — what eve does about untrusted bytes, honestly.** Slab bytes are trusted outright (`SECURITY.md:73-75`): the EVE
decoders check no magic (BA), follow offsets as read, and bound nothing (prior file Part 6). The network layer is
better where it is content-addressed: every fetched block is hashed against its key, and a client deref re-encodes each
node and compares (`decode-rec`) — a store cannot substitute or reshape a block. Around that: the root blob is taken on
the broker's word; the gateway reads writers through the plain decoder; no reader has a depth or size limit and the
failure is a `StackOverflowError` (an `Error`, past `catch Exception`) at a few hundred to a few thousand levels [ran];
malformed framing surfaces as host exceptions (`IndexOutOfBounds`, `ArithmeticException`), not typed refusals [ran];
base64 skips junk; JSON is parsed before the bearer is checked. Tests exist for refusals on the canonical path
(`test/eve/net/codec_fixture_test.cljc`, `test/eve/aegis/pages_v3_test.cljc`, `graft_test.cljc`, `json_test.cljc`); no
fixture of malformed bytes exists.

**Against the framing.** (i) "The wire and storage form": the live wire is ValueScript's kernel format, pinned
byte-exact by fixtures copied from VS and shared with VS's own client (`snapshot.cljc:12-14`); FORMAT-DECISIONS §8 says
of it "neither is this format". A static-eve wire is a second generation other clients cannot read. (ii) "Converts to
and from slab fast": the only slab ↔ wire path today (the gateway) materializes plain host values both ways. (iii)
"Append-only, self-contained": the block wire already is append-only and incremental (`encode-rec` images changed paths
only); one packed buffer re-emits the whole value per change unless it is cut into content-addressed pieces — blocks
again. (iv) "Outside any slab domain": every collection read is an `eve.slab-io` name, view lifetime is the epoch pin
(§2.4), and the tally forbids a plain cross-domain reference (§11). (v) Equality by canonical bytes already ships (the
digest) and already disagrees with Clojure's `=`: eve `{:a 0.0}` ≠ eve `{:a -0.0}` by digest, yet = the host map
`{:a -0.0}` by the walk [ran].
