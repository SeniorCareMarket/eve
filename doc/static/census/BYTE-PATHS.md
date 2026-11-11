STATUS: PARTIAL — banked 2026-11-11 5:35 PM ET. Done: Part 1 (tags, coverage, JVM probes), Part 2 (flat codec).
In progress: Part 3 (int32 / typed-view adapters unread), Part 4 (codec, bytes, wire, pages, atom read; json, run,
platform, presign, store, blobs, snapshot, graft, broker, broker_disk, gateway, crypto files, keyring, net-local, tests
unread). Not started: Part 5 (digests), Part 6 (only the codec rows below), Part 7 (only eve-bytes.edn read), Part 8
table, "Evidence for the assumptions".

# Byte paths in Eve — census

Provenance: tree `/home/user/eve`, branch `claude/sweet-carson-6pt29d` at `7d3f507`; `git diff 4a92129 HEAD -- . ':!doc/static'`
is empty, so every `path:line` below is the pin `4a92129` (release 0.9.3.1). Read-only: nothing built, no suite run.
Every row is "read in the code" unless it says "probed" (a host one-liner run here) or "doc says". Paths are relative
to `/home/user/eve`. Short names: **BA** = `adapters/codec/byte-array/src/eve/codec.cljc`, **JS** =
`adapters/codec/js/src/eve/codec.cljs`, **JK** = `adapters/codec/jank/src/eve/codec.jank`, **SER** =
`src/eve/deftype_proto/serialize.cljc`, **ENCJ** = `adapters/encoding/java/src/eve/encoding.clj`.

## 0. The five byte paths (map of this file)

| # | Path | Where | What it is | Self-contained? |
|---|---|---|---|---|
| A | EVE value bytes, **value** position | `eve.codec/encode-value`, `encode-val` (BA:751-761, JS:1397-1418) | `[EE DB tag …]`; scalars inline, a collection a 7-byte pointer into the domain's slab | no (offsets mean something in one domain only, `doc/internals.md:187-190`) |
| B | EVE value bytes, **key** position = the "flat codec" | `eve.codec/encode-key` (BA:778-782 → `value->eve-bytes` BA:469-643; JS:1438-1447 → `serialize-key` JS:683-696) | same scalar tags; collections flat `0xED/0xEE/0xEF`, canonical order | yes for scalars + plain collections; see §2.6 for the leaks |
| C | `eve.net.codec` block images | `src/eve/net/codec.cljc` | the ValueScript kernel's "ExtCID v3" Merkle blocks: a **different scalar universe** (16-byte type CIDs, not EVE tags) | yes, content-addressed by SHA-256 |
| D | `eve.net.snapshot` VSNP root blob / `eve.net.graft` GRFT / `eve.net.pages` PAGE | `src/eve/net/{snapshot,graft,pages}.cljc` | envelopes around C (unread at this banking, except pages) | — |
| E | Slab block layouts (HAMT nodes, vector tries, list cells, arrays, objs, deftypes) | `src/eve/{map,set,vec,list,array}.cljc`, `obj/`, `deftype/` | hold A/B bytes inline | no |

There is no codec file in `src/eve/mem.cljc` any more (387 lines: `IMemRegion` and bitmap helpers only,
`src/eve/mem.cljc:43-122`, `:326-387`). The names `serialize-flat-*` exist only in the JS codec (JS:838, JS:883);
`deserialize-flat-*` exists nowhere (grep over `src adapters net-local test` — not found).

---

## Part 1 — the tag universe, from code

### 1.1 Codec adapters that exist

| Adapter | File | Lines | Wired in `hosts.edn`? |
|---|---|---|---|
| byte-array | `adapters/codec/byte-array/src/eve/codec.cljc` | 800 | every host but the JS family: `:jvm :bb :cljr :lpy :jolt :clje :vs :glj :jank :lg :cljd :bb-minimal` (`hosts.edn:7,34,60,64,68,72,84,91,100,108,115,124`) |
| js | `adapters/codec/js/src/eve/codec.cljs` | 1,476 | `:cljs :browser :nbb :scittle :cherry` (`hosts.edn:13,27,38,47,56`) |
| jank | `adapters/codec/jank/src/eve/codec.jank` | 803 | **no row lists it.** `hosts.edn:100` gives jank `adapters/codec/byte-array/src` ("the inbuilt codec", `hosts.edn:97`); `adapters/README.md:87-88` lists two codec implementations, not three. Added by commit `16180c5` ("14 adapters/*/jank"). |

JK is BA with the byte primitives in C++ (`cpp/raw`, JK:28-60) and **without** BA's `IPlain` arm (BA:631-639 has no
counterpart in JK — `diff` of the two files). Its reads are raw pointer arithmetic with no bounds check:
`long read_u32_le(object_ref b, long i) { uint32_t v; std::memcpy(&v, B(b) + i, 4); return (long)v; }` (JK:43).

### 1.2 Every tag at byte 2 of `[0xEE 0xDB tag …]`

Constant names are SER's; BA and JK also carry private copies (BA:54-77, JK:90-113). "→ctor" means the arm looks the
tag up in `ser/type-constructors` (SER:132) and calls `(ctor sio offset)`.

| Tag | Constant (SER line) | Meaning | Bytes after `EE DB tag` | BA enc | BA dec | JS enc | JS dec (`deserialize-element`) | JK enc / dec |
|---|---|---|---|---|---|---|---|---|
| — | (none) | `nil` | **zero bytes, no magic, no tag** | BA:143, 484-485 | BA:187-188 | JS:362-363 | JS:909-910 | JK:497 / same guard as BA |
| 0x01 | `FAST_TAG_FALSE` :17 | false | — (3 total) | BA:144, 487-488 | BA:196 | JS:365-369, 409-413 | JS:917 | JK:500 / 209 |
| 0x02 | `FAST_TAG_TRUE` :18 | true | — | BA:145, 487-488 | BA:197 | same | JS:918 | JK:500 / 210 |
| 0x03 | `FAST_TAG_INT32` :19 | integer in [-2^31, 2^31) | i32 LE (7 total) | BA:496-505 (cache BA:149-156) | BA:198 | JS:371-377, 415-421; BigInt JS:494-498 | JS:920-922 | JK:509-518 / 211 |
| 0x04 | `FAST_TAG_FLOAT64` :20 | double | f64 LE (11) | BA:512-517 | BA:200 | JS:386-391, 430-435 | JS:928-930 | JK:525 / 213 |
| 0x05 | `FAST_TAG_STRING_SHORT` :21 | string ≤ 255 UTF-8 bytes | len:u8, bytes | BA:535-543 | BA:204-205 | JS:644-654, 766-776 | JS:932-934 | JK:548-556 / 217 |
| 0x06 | `FAST_TAG_STRING_LONG` :22 | string > 255 bytes | len:u32 LE, bytes | BA:544-548 | BA:207-208 | JS:655-662, 777-784 | JS:936-939 | JK:559 / 220 |
| 0x07 | `FAST_TAG_KEYWORD_SHORT` :23 | keyword, no ns, ≤ 255 | len:u8, name | BA:555-562 | BA:210-211 | JS:272-282 | JS:941-943 | JK:573 / 223 |
| 0x08 | `FAST_TAG_KEYWORD_LONG` :24 | keyword, no ns, > 255 | len:u32, name | BA:563-566 | BA:213-214 | JS:283-290 | JS:945-948 | JK:577 / 226 |
| 0x09 | `FAST_TAG_KEYWORD_NS_SHORT` :25 | ns keyword, both ≤ 255 | ns-len:u8, ns, name-len:u8, name | BA:571-577 | BA:216-222 | JS:251-260 | JS:950-955 | JK:586 / 229 |
| 0x0A | `FAST_TAG_KEYWORD_NS_LONG` :26 | ns keyword, either > 255 | ns-len:u32, ns, name-len:u32, name | BA:578-583 | BA:224-230 | JS:261-270 | JS:957-963 | JK:592 / 237 |
| 0x0B | `FAST_TAG_UUID` :27 | UUID | 16 bytes, msb then lsb, big-endian (19) | BA:609-614 (ENCJ:146-154) | BA:243-244 (ENCJ:156-163) | JS:599-610, 724-735 (from the hex text) | JS:975-985 | JK:622 / 256 |
| 0x0C | `FAST_TAG_SYMBOL_SHORT` :28 | symbol, no ns | len:u8, name | BA:589-596 | BA:232-233 | JS:631-641, 754-764 | JS:987-989 | JK:599-607 / 245 |
| 0x0D | `FAST_TAG_SYMBOL_NS_SHORT` :29 | ns symbol | ns-len:u8, ns, name-len:u8, name | BA:597-607 | BA:235-241 | JS:613-628, 737-752 | JS:991-996 | JK:616 / 248 |
| 0x0E | `FAST_TAG_DATE` :30 | instant | ms since epoch as f64 LE (11) | BA:528-533 | BA:201 | JS:393-398, 437-442 | JS:998-1000 | JK:541 / 214 |
| 0x0F | `FAST_TAG_INT64` :31 | integer past int32, in int64 | i64 LE (11) | BA:506-510 | BA:199 | JS:379-384, 423-428; BigInt JS:500-504 | JS:924-926 (`js/Number`, lossy past 2^53) | JK:521 / 212 |
| 0x10 | `FAST_TAG_SAB_MAP` :63 | pointer → EveHashMap header | offset:i32 LE (7) | BA:711-714; existing eve value BA:692-706 (header 0xED→0x10 at BA:703) | BA:261-264 →ctor | `direct-map-encoder` JS:595-596; `-sab-encode` `src/eve/map.cljc:1199` | JS:1003-1008 →ctor (nil when none) | as BA (JK:274) |
| 0x11 | `FAST_TAG_SAB_SET` :64 | pointer → EveHashSet header | offset:i32 | BA:716-719; BA:703 (0xEE→0x11) | BA:265-268 | `convert-to-eve` JS:678-679; `src/eve/set.cljc:794` | JS:1003-1008 | JK:278 |
| 0x12 | `FAST_TAG_SAB_VEC` :65 | pointer → vector header | offset:i32 | BA:726-729 | BA:269-272 | JS:678-679; `src/eve/vec.cljc:527` | JS:1003-1008 | JK:282 |
| 0x13 | `FAST_TAG_SAB_LIST` :66 | pointer → list header | offset:i32 | BA:721-724 | BA:273-276 | JS:678-679; `src/eve/list.cljc:241` | JS:1003-1008 | JK:286 |
| 0x14 | `FAST_TAG_CHAR` :34 | char | UTF-16 unit:u16 LE (5) | BA:519-526 | BA:202 | JS:339-352 (an `EveChar` only, JS:301-324) | JS:965-966 → `EveChar` | JK:532 / 215 |
| 0x15 | `FAST_TAG_REF` :52 | reference to a host object, process-local | realm:u32 LE, handle:u32 LE (11) | BA:382-391 (from BA:641-643, 741-745) | BA:253 → `ser/handle->ref` SER:248-259 | JS:526-538 (from JS:681, 803) | JS:550-552 | JK:395 / 266 |
| 0x16 | `FAST_TAG_BIGINT` :44 | integer past int64 | len:u32 LE, two's complement big-endian minimal | BA:353-359 (from BA:491-494) | BA:248 (BA:161-166) | JS:489-512 | JS:545 (JS:514-524) | JK:366 / 261 |
| 0x17 | `FAST_TAG_RATIO` :45 | ratio | len:u32, numerator, len:u32, denominator | BA:361-369 | BA:249 | **none** | **throws** JS:546-547 | JK:374 / 262 |
| 0x18 | `FAST_TAG_BIGDEC` :46 | big decimal | scale:i32 LE, len:u32, unscaled | BA:371-380 | BA:250 | **none** | **throws** JS:548-549 | JK:384 / 263 |
| 0x19 | `FAST_TAG_VALUE_BLOCK` :57 | a value > 64 bytes (`VALUE_INLINE_MAX` SER:58) out of line | block:i32 LE, len:u32 LE (11) | BA:763-776 `encode-val-out-of-line` — **not what `encode-val` writes** (BA:757-761) | BA:256 | JS:1420-1436 (same: not wired) | JS:557-561 | JK:766 / 269 |
| 0x1A | `FAST_TAG_RECORD` :70 | CLJS record → eve map carrying `:eve/record-tag` | map offset:i32 (7) | **none** | **none** (falls to BA:325-328) | JS:128-141 (only for a type registered with `register-record-type!` JS:119-126) | JS:1011-1021 | none |
| 0x1B | `FAST_TAG_TYPED_ARRAY` :74 | JS typed array | **two forms**: pointer `offset:i32` (7) to a module-slab block `[subtype:u8][reserved:7][byte-len:u32 LE][reserved:4][data @+16]`; or inline `[subtype:u8][byte-len:u32 LE][raw bytes]` (8+N) | **none** | **none** | pointer: `adapters/slab/module/src/eve/process_slab.cljs:1384-1429`; inline fallback JS:221-232, process_slab.cljs:1431-1439 | pointer JS:1037-1094; **inline → `nil`** (JS:1095-1096: "Inline format (8+N bytes) — not currently used in deserialize-element path") | none |
| 0x1C | `FAST_TAG_EVE_ARRAY` :78 | pointer → eve array block (the "SAB pointer tag") | block offset:i32 (7) | not written by BA (it writes 0x1D) | BA:277-280 →ctor `src/eve/array.cljc:830-837` | **still written**: `EveArray/-sab-encode` `src/eve/array.cljc:344-345`; tag map `:eve/array` SER:473 | JS:1024-1029 | JK:290 |
| 0x1D | `EVE_ARRAY_SLAB_TYPE_ID` :99 | pointer → eve array block `[0x1D][subtype:u8][pad:u16][count:i32][elements]` (`src/eve/array.cljc:245-246`) | offset:i32 | BA:731-739 (a host array / `IBackingArray`), BA:692-706 | BA:281-284 →ctor `src/eve/array.cljc:790-791` | none of its own | generic →ctor arm JS:1134-1140 | JK:294 |
| 0x1E | `EVE_OBJ_SLAB_TYPE_ID` :103 | pointer → `eve.obj` block | offset:i32 | BA:692-706 | BA:285-288 →ctor `src/eve/obj/impl.cljc:412` | `-sab-encode` | JS:1134-1140 | JK:298 |
| 0x1F | `ValueArray-type-id` `src/eve/deftype/array.cljc:47` | pointer → eve value array | offset:i32 | `src/eve/deftype/array.cljc:193` | generic →ctor BA:325-328 (registered `src/eve/deftype/array.cljc:240`) | same | JS:1134-1140 | generic JK:338 |
| 0x40–0xEC | `type-id-base 0x40`, 173 ids (`src/eve/deftype/registry.cljc:47-48,59-62`) | pointer → an eve-deftype instance; id = `0x40 + (string-hash name) mod 173` | offset:i32 | BA:692-706 | BA:325-328 | `adapters/types/js/src/eve/types_js.cljc:344` | JS:1134-1140 | JK:338 |
| 0xED | `FAST_TAG_FLAT_MAP` :89 | flat map | count:i32 LE, then per entry `klen:i32 LE, k bytes, vlen:i32 LE, v bytes` | BA:452-467 | BA:291-301 | JS:850-873 | JS:1099-1110 | JK:465 / 304 |
| 0xEE | `FAST_TAG_FLAT_SET` :90 | flat set | count:i32, then `elen:i32, e bytes` | BA:422-436 | BA:304-311 | JS:875-877 (JS:818-836) | JS:1113-1121 | JK:435 / 317 |
| 0xEF | `FAST_TAG_FLAT_VEC` :91 | flat vector (and any sequential) | count:i32, then `elen:i32, e bytes` | BA:438-450 | BA:314-321 | JS:879-881 | JS:1124-1132 | JK:451 / 327 |

Notes on the table:

* **Two namespaces share numbers.** Byte 2 of a value (above) and byte 0 of a slab block (`doc/internals.md:210-225`).
  `0xED` is flat map (byte 2), EveHashMap header (byte 0, `src/eve/map.cljc:49`), and `DIRECT_MARKER_MAP`
  (`src/eve/deftype_proto/data.cljc:613`); `0xEE` is flat set, EveHashSet header (`src/eve/set.cljc:44`) **and magic
  byte 0**; `0x01` is false (byte 2), scalar block (byte 0, SER:95) and, in `eve.net.codec`, the HAMT node tag
  (`T-NODE`, `src/eve/net/codec.cljc:43`); `0x12`/`0x13` are pointer tags, block type ids (`src/eve/vec.cljc:34`,
  `src/eve/list.cljc:27`) and `eve.net.codec`'s `T-VEC`/`T-LIST` (`src/eve/net/codec.cljc:44-45`).
* A second ClojureScript reader, `deserialize-from-dv` (JS:1148-1379), has **no arm for 0xED/0xEE/0xEF, 0x1D, 0x1E,
  0x1F or deftype ids** — they fall to `:else nil` (JS:1377). Its only callers are the legacy SharedArrayBuffer atom
  (`src/eve/shared_atom.cljs:2703,2765,2797,2960,3040,3215,3250`).
* The typed-array subtype byte (SER:106-116): 1 uint8, 2 int8, 3 uint8-clamped, 4 int16, 5 uint16, 6 int32, 7 uint32,
  8 float32, 9 float64, 0x0A bigint64, 0x0B biguint64. An eve array (`0x1D`) carries 1–9 only
  (`src/eve/array.cljc:53-60`).
* What follows a pointer is decided by the retire walk's own list, which **omits 0x1A and 0x1B**:
  `(or (<= 0x10 tag 0x13) (<= 0x1C tag 0x1F) (<= 0x40 tag 0xEC))` (`src/eve/retire.cljc:23-27`), and only a value of
  exactly 7 bytes is tested (`src/eve/retire.cljc:69`).

### 1.3 Mismatches between code and `doc/internals.md` "Serialization Format"

| # | Doc (`doc/internals.md`) | Code | Kind |
|---|---|---|---|
| M1 | :134 "Every encoded value starts with the two-byte magic `0xEE 0xDB`" | `nil` is zero bytes (BA:143; JS:363); a flat element that is nil is `[00 00 00 00]` and nothing else (`test/eve/fixtures/eve-bytes.edn:56`) | doc omits |
| M2 | :207 `0x1B` typed array = `[subtype:u8][byte-length:u32][raw bytes]` | that inline form is the pre-init fallback and reads back `nil` through `eve.codec/decode` (JS:1095-1096); the stored form is a 7-byte pointer to a 16-byte-header block (process_slab.cljs:1391-1393) | doc describes the fallback |
| M3 | :206-207 `0x1A`, `0x1B` listed as format tags | ClojureScript only; BA has no arm: `(throw (ex-info "Unknown EVE type tag" {:tag tag}))` (BA:328). `doc/unification/SEAM-MAP.md:168` (L-44) says so; internals.md does not | doc omits the host restriction |
| M4 | :182 "`0x1D` eve array … (`0x1C` below is read too)" | `0x1C` is still **written** on the JS codec path (`src/eve/array.cljc:344-345`, SER:473) | doc understates |
| M5 | :156 "character … (a one-character string on ClojureScript)" | ClojureScript reads and writes an `EveChar` type (JS:301-337, 965-966); a one-character string stays a string. `doc/unification/FORMAT-DECISIONS.md:111-120` has the current rule | doc stale |
| M6 | :242-244 HAMT hash "Murmur3_x86_32" | true for map/set placement (`src/eve/map.cljc:88-92`, `src/eve/set.cljc:719`); but `src/eve/hamt_util.cljc:667-671` also ships `key-hash-bytes` ("HAMT placement hash … the 16-byte xxh3-128 digest") with **no caller in `src/`** (grep) | code carries an unused second hash |
| M7 | `doc/unification/FORMAT-DECISIONS.md:197-203` (§7): type-id from Clojure's `hash` of the type name | `src/eve/deftype/registry.cljc:28-37,50-67`: "DECLARED BREAK (Oct 25 2026)" — `fold-hash-int (xxh3-128-bytes (encode-key name))` | FORMAT-DECISIONS §7 stale |
| M8 | :255, :271 node types "NODE_TYPE_BITMAP = 1", "NODE_TYPE_COLLISION = 3" | `src/eve/retire.cljc:49,74` agree (1, 3); `src/eve/deftype_proto/data.cljc:577-581` still defines `HAMT_BITMAP_NODE_TYPE 3`, `HAMT_COLLISION_NODE_TYPE 4`, `HAMT_BITMAP_NODE_HEADER_SIZE 10` ("same as eve.data") | dead constants disagree |
| M9 | :225 block ids `0xF0–0xFF` process-private | SER:264-269 defines the predicate; eve writes none | consistent |
| M10 | table has no row for `0x40–0xEC` under "Records and Arrays" but has one under pointer tags :185 | consistent with code | — |
| M11 | doc lists no tag `0x00`, `0x20–0x3F` | code assigns none (grep of SER and the three codecs) | consistent |

In code but not in the doc's tag tables: nil-as-zero-bytes (M1); the `0x1B` pointer form and its block layout (M2);
the JS-only second reader's missing arms; the reference table's realm rule is in the doc (:168).
In the doc but not in code: nothing found.

### 1.4 Free tag values (byte 2)

| Range | Count | State | Evidence |
|---|---|---|---|
| 0x00 | 1 | unassigned | no constant; a BA decode of it throws "Unknown EVE type tag" (BA:325-328), a JS decode answers `nil` (JS:1134-1140) |
| 0x01–0x1F | 31 | **all assigned** (table above) | SER:17-103, `src/eve/deftype/array.cljc:47` |
| 0x20–0x3F | 32 | **unassigned, reserved for eve** | "0x00–0x3F stay eve's pointer and fast tags" (`doc/unification/FORMAT-DECISIONS.md:218`); "0x00-0x3F are eve's pointer and fast tags" (`src/eve/deftype/registry.cljc:39`). Not in `pointer-tag?` (`src/eve/retire.cljc:27`), so a 7-byte value with one of these tags is not followed as a pointer |
| 0x40–0xEC | 173 | eve-deftype ids, by name hash — any may be in use | `src/eve/deftype/registry.cljc:47-48` |
| 0xED–0xEF | 3 | flat map / set / vec | SER:89-91 |
| 0xF0–0xFF | 16 | unassigned as value tags. As **block kinds** (byte 0) reserved for "a runtime's own block kinds … never written to a shared domain"; "eve assigns none" | SER:261-269; `doc/unification/FORMAT-DECISIONS.md:303-306` |

### 1.5 Coverage — how each value kind encodes today, or where it is refused

"BA hosts" = every host of the byte-array codec. "By reference" means tag `0x15`: process-local, and refused the
moment the write targets a shared (mmap) domain with:
`"eve: a value with no eve bytes (a fn, a regex, an atom, a host object, ...) is held by reference, and a reference never reaches a shared domain"` (SER:220-226).

| Value | BA hosts | ClojureScript family | Round-trips unchanged? | Pinned by a fixture row? |
|---|---|---|---|---|
| `nil` | zero bytes (BA:484-485) | zero bytes (JS:362-363) | yes | `eve-bytes.edn:11` |
| `true` / `false` | `eedb02` / `eedb01` | same | yes | :12-13 |
| int32 bounds | 0x03 (BA:498-505) | 0x03 for any `Number.isInteger` in range (JS:371-377) | JVM `Integer`/`Short`/`Byte` read back as `Long` (ENCJ:64-68; BA:99-103) | :14-22 |
| int64 bounds | 0x0F (BA:506-510) | 0x0F only for a safe integer (JS:379-384); read through `js/Number` (JS:924-926) — past 2^53 lossy | not on CLJS | :23-27; :26-27 exclude `:cljs` |
| integer past 2^53 as a JS number | — | **0x04 float64** (JS:386-391) | type changes across hosts (`doc/unification/FORMAT-DECISIONS.md:127-131`) | not pinned |
| big integer | by magnitude: 0x03 / 0x0F when it fits, else 0x16 (BA:491-494) | a `BigInt` likewise (JS:489-512) | `5N` reads back `5` (a Long) by design (§8); JVM `BigInteger` reads back `clojure.lang.BigInt` (ENCJ:97-104) | :68-72 (`:clj :bb :cljr :lpy :jank`) |
| double | 0x04, raw IEEE bits (`Double/doubleToRawLongBits`, ENCJ:59-62) | 0x04 only when not an integral value in int32 / not a safe integer | `1.0` is 0x04 on the JVM and **0x03** on CLJS (:31 excludes `:cljs`) | :28-31 |
| `NaN` | 0x04, payload as the host holds it — "NaN payloads are not normalised" (`doc/unification/HOST-CONTRACT.md:185`) | 0x04 | as a **key**, two NaN payloads are two byte strings | **no row** (grep `NaN` in `eve-bytes.edn`: none) |
| `-0.0` | 0x04 with the sign bit (raw bits) — distinct key bytes from `0.0` | **0x03 `0`** ("an integral double and `-0.0` go out as int32", `doc/unification/FORMAT-DECISIONS.md:128`) | no across hosts | **no row** |
| `±Infinity` | 0x04 | 0x04 (`Number.isInteger` false) | yes | **no row** |
| float32 | no tag: a JVM `Float` is `floating?` (ENCJ:70-73) and written `(double v)` as 0x04 (BA:512-517) | JS has no float32 scalar | reads back a `Double` | no row |
| ratio | 0x17 (BA:616-617) on hosts with one | no encoder; decode throws `"eve: a ratio has no representation on ClojureScript (FORMAT-DECISIONS.md §8)"` (JS:546-547) | refused on CLJS, Dart ("no ratio or decimal", `adapters/README.md:71`), clojerl ("big integers only", `FORMAT-DECISIONS.md:262`) | :73-75 |
| bigdec | 0x18, canonical (trailing zeros stripped, scale ≥ 0; ENCJ:117-123) | decode throws (JS:548-549) | `1.50M` reads back `1.5M`, `1E+2M` reads back `100M` (by design, §8) | :76-82 |
| char, BMP | 0x14 u16 (BA:519-526) on hosts with a char type | only an `EveChar` (JS:339-352); a 1-char string is a string | basilisp, clojerl, Dart have `host-char?` false (`adapters/encoding/python/src/eve/encoding.lpy:168-173`, `beam/…/encoding.clje:160-165`, `dart/…/encoding.cljd:114`): a char cannot be written there | :66-67 (`:clj :bb :cljr :jank`, + `:cljs` for `#char`) |
| char, non-BMP | not a JVM `char`. On hosts whose char is a code point (jank, glojure, let-go: "a rune", `adapters/encoding/go/…/encoding.glj:179-182`) the write is `(unchecked-byte (bit-and c 0xFF))`, `(unchecked-byte (bit-shift-right c 8))` (BA:524-525): **bits 16+ are dropped, no refusal** | `eve-char` takes `(.charCodeAt s 0)` (JS:326-332): the high surrogate only | no | no row |
| string, empty | `eedb0500` | same | yes | :32 |
| string ≤ 255 / > 255 bytes | 0x05 / 0x06 | same | yes | :33-36 (255 and 256) |
| string > 64 KiB | 0x06, u32 length, no size check in the encoder (BA:544-548). Probed: 70,000 chars → `eedb06 70110100 …`, 70,007 bytes | same | yes as a map value (probed: `{:k <70,000 chars>}` reads back 70,000; the node's u16 `kv_total_size` caches 0 past 65,535 and readers scan, `src/eve/map.cljc:202-219`). **Not as an atom's whole value: see §1.6 P-1** | no row |
| string with an unpaired surrogate | host's UTF-8 encoder decides. JVM `(.getBytes s "UTF-8")` (ENCJ:44-47) — probed: `"a\uD800b"` → `eedb0503 61 3f 62` (a `?`); Python `.encode "utf-8"` is strict (`encoding.lpy:44-47`) | `TextEncoder` (JS:147, 645) — probed on Node 22: `61 ef bf bd 62` (U+FFFD) | **no on any host, and the bytes differ per host** (so the same string is two keys) | no row |
| string led by U+FEFF (BOM) | carried (probed on the JVM: round-trips) | decoded with a default `(js/TextDecoder.)` (JS:148, 150-157), which strips a leading BOM — probed on Node 22 at the host level: `ef bb bf 61 62` decodes to a 2-character string | **no on the JS codec** (read + host probe; the codec itself was not run) | no row |
| string with embedded NUL | length-prefixed: carried (probed) | same | yes | no row |
| keyword plain / ns / long | 0x07 / 0x09 / 0x08 / 0x0A (u32 long forms exist) | same | decode is `(keyword text)` (BA:211): a no-ns keyword whose name contains `/` reads back split | :37-40 (long rows exclude `:clje`) |
| symbol plain / ns | 0x0C / 0x0D, u8 lengths only | same | yes | :41-42 |
| symbol segment > 255 bytes | **refused**: `"eve: symbol segment longer than 255 bytes has no encoding"` (BA:401-409) | **refused**: `"eve: symbol segment longer than 255 bytes has no encoding: "` (JS:563-570) | — | `test/eve/eve_bytes_fixture_test.cljc:115-122` |
| UUID | 0x0B | 0x0B | yes | :43 |
| Date / inst | 0x0E, **milliseconds** as f64; only `java.util.Date` is `date?` (ENCJ:131-134); decode truncates `(long (read-f64-le b 3))` (BA:201) | `inst?` → `.getTime` (JS:393-398) | clojerl "counts whole seconds: the milliseconds are lost" (`adapters/encoding/beam/src/eve/encoding.clje:123-127`); Python truncates microseconds (`encoding.lpy:135-139`); a `java.sql.Timestamp` loses nanos and its class | :44-45 |
| regex | by reference (0x15) | by reference | process-local only | none ("no fixture pins them", `FORMAT-DECISIONS.md:285`) |
| JVM `byte[]` as a value | **eve array block, subtype 2 `:int8`** (ENCJ:320; `src/eve/array.cljc:800-815`; BA:736-739), pointer `0x1D` (probed: `eedb1d…`) | — | **no.** Probed: `codec/decode` answers an `eve.array.EveArray` of `[1 2 -1]`; through an atom, `(:b @a)` is a `clojure.lang.PersistentVector` of longs — heap and disk domain alike. A `byte[]` never comes back | no row |
| JVM `byte[]` as a key / inside a flat collection | falls to `:else` → by reference (BA:641-643; probed: `eedb15`, 11 bytes) | — | process-local; in a disk domain refused (probed, the SER:224 message) | — |
| JS `Uint8Array` | — | 0x1B pointer to a module-slab block (process_slab.cljs:1384-1429); refused in an encrypted domain: `"eve: an encrypted domain holds no typed array (its views would read ciphertext)"` (process_slab.cljs:1399-1400) | yes on CLJS (`test/eve/typed_array_test.cljs:52-66`); unreadable by BA hosts (M3) | no row |
| JS `ArrayBuffer`, `DataView` | — | not a typed array (JS:192-209) → by reference (JS:676-681) | process-local | — |
| **a bytes / blob scalar tag** | **does not exist** | **does not exist** | — | — |
| other typed arrays | `int[]`→6, `double[]`→9, `float[]`→8, `short[]`→4 (ENCJ:302-321); `long[]`, `char[]`, `boolean[]`, `Object[]` are `host-array?` (ENCJ:179-182) but have no subtype → `"Not a host typed array: "` (`src/eve/array.cljc:806-807`) | all 11 JS kinds (JS:192-209) | JVM arrays read back as `EveArray` | no row |
| record | `(map? v)` is true for a record: written as a plain map (BA:622-623 flat, BA:711-714 slab) — **type dropped silently** | registered type → 0x1A; unregistered → **zero bytes**: `(or (encode-record elem) (js/Uint8Array. 0))` (JS:590-592, 716-718), i.e. reads back `nil` | no | none; `register-record-type!` has no caller in the tree (grep) |
| tagged literal, arbitrary host object | by reference | by reference | process-local | — |
| metadata | **never encoded.** No `meta` in BA, JS or JK (grep); eve collections answer `(meta [_] nil)` and `(withMeta [this m] this)` (`src/eve/map.cljc:1094-1097`, `src/eve/vec.cljc:410-413`, `src/eve/set.cljc:710-713`, `src/eve/list.cljc:148-151`) | same | metadata is dropped | — |
| function, var, atom, volatile | by reference (SER:186-193) | by reference | process-local | — |
| nested collection, value position | pointer 0x10–0x13 into the slab (BA:711-729) | pointer | within one domain | — |
| nested collection, key position / set element | flat 0xED/0xEE/0xEF, recursively (BA:622-629) | flat (JS:683-696) | decodes to **plain host collections** | :57-64 |
| sorted map / sorted set as a key | `(map? v)` → flat map in key-byte order: comparator and order dropped | same | no | — |
| map with a `nil` key or `nil` value | key/value length 0 | same | yes | :52 (nil key), :56 (nil element) |
| a collection containing itself | not expressible with persistent values; encoders recurse with no cycle or depth guard (BA:430, 444, 460; JS:852, 877, 881) | same | — | — |
| a list / lazy seq / queue as a key | flat **vector** 0xEF (BA:628-629); probed: `'(1 2)` reads back a `PersistentVector` | flat vector (JS:879-881) | sequence kind dropped (equal by Clojure's `=`, so one key by design; `eve-bytes.edn:49`) | :49 |
| `java.util.ArrayList`, `HashMap`, `java.time.Instant` | by reference (probed: `eedb15`) — not `sequential?`/`map?`/`date?` | — | process-local | — |

### 1.6 Probed on the JVM at the pin (what was watched, not read)

Run 2026-11-11 ~5:25 PM ET: OpenJDK 21.0.12, Clojure 1.12.0, classpath from `doc/static/probes/eve-cp.sh` (the pin's
`src` + the `:jvm` adapter roots), each run under 5 s, fresh heap domains and a fresh temp directory for the disk
domain. The scripts are outside the repository (session scratchpad); every expression is reproduced here.

| # | Expression | Answer |
|---|---|---|
| P-1 | `@(eve.atom/atom ::s (apply str (repeat 1100 "x")))` — a string whose encoding passes 1,023 bytes as an atom's **whole value** | **`nil`** (1,000 chars: fine). Same on a disk atom `{:id … :persistent dir}`, and after `reset!` to 5,000 chars. Cause as read: the scalar block `[0x01][EVE bytes]` carries no length; the reader takes `(dec blk-size)` bytes with `blk-size (d/slab-size ci)` (`src/eve/deftype_proto/alloc.cljc:1514-1522`), and class 6 answers 1 (`src/eve/deftype_proto/data.cljc:87-91`), so it reads 0 bytes, which decode as `nil`. `{:k <5,000 chars>}` in a map is fine |
| P-2 | `(codec/eve-bytes->value (unhex "00000305000000"))` — wrong magic | `5` — **BA never looks at bytes 0-1** |
| P-3 | `… "eedb0305000000ffff"` — trailing bytes | `5` — unconsumed bytes are ignored |
| P-4 | `… "eedb03"` | `ArrayIndexOutOfBoundsException` (the host's, not the codec's) |
| P-5 | `… "eedb05056869"` — string says 5, holds 2 | `StringIndexOutOfBoundsException` |
| P-6 | `… "eedb0501ff"` — invalid UTF-8 | a 1-character string (U+FFFD), no error |
| P-7 | `… "eedbefffffffff"` — flat vec, count −1 | `[]` — a negative count is an empty collection |
| P-8 | `… "eedbef01000000ffffff7f"` — element length 2^31−1 | `java.lang.OutOfMemoryError: Requested array size exceeds VM limit` — an `Error`, raised by the copy's allocation (ENCJ:40) |
| P-9 | `… "eedbef01000000ffffffff"` — element length −1 | `NegativeArraySizeException` |
| P-10 | flat set written `2, 1` (out of canonical order) | `#{1 2}`, accepted; its re-encoding is the canonical bytes |
| P-11 | flat map with key `:a` twice (values 1, 2) | `{:a 2}`, accepted, last wins |
| P-12 | BIGINT with length 0; with bytes `00 00 05`; RATIO `2/4`; RATIO with denominator 0 | `0N`; `5N`; `1/2` (all three non-canonical forms accepted); `ArithmeticException: Divide by zero` |
| P-13 | DATE whose f64 is NaN | `#inst "1970-01-01T00:00:00.000-00:00"` |
| P-14 | unknown tag `0x20`; tags `0x1A`, `0x1B` | `ExceptionInfo: Unknown EVE type tag` |
| P-15 | a pointer tag `0x10` inside flat bytes, decoded with no slab I/O | with `eve.map` loaded: `No implementation of method: :-sio-read-i32 … for class: nil`; without it: `UnsupportedOperationException: EVE SAB_MAP: no constructor registered.` |
| P-16 | `encode-key` of a vector nested n deep | n = 800 fine (8,807 bytes); **n = 1,200 `StackOverflowError`** (default thread stack) |
| P-17 | `eve-bytes->value` of a flat vector nested n deep | n = 500 fine; **n = 1,000 (11,007 bytes) `StackOverflowError`** |
| P-18 | `(= 0.0 -0.0)` and their key bytes | `true`; `eedb04 0000000000000000` vs `eedb04 0000000000000080` — one Clojure key, two eve keys |
| P-19 | `Double/NaN`; a NaN with payload `7ff0000000000001` | `…f87f`; `…010000000000f07f` — payload kept |
| P-20 | `1`, `1N`, `1.0`, `1M` | `eedb0301000000`, the same, `eedb04…f03f`, `eedb18 00000000 01000000 01` |
| P-21 | `(->R 1 2)` (a `defrecord`) as a key; as a value | flat-map bytes, reads back a `PersistentArrayMap`; pointer `eedb10`, reads back an `EveHashMap` — the record type is gone both ways |
| P-22 | `(with-meta {:a 1} {:m 1})` | bytes equal `{:a 1}`'s; `meta` after the round trip `nil` |
| P-23 | `(sorted-map-by > 1 :a 2 :b)` as a key | entries in key-byte order (1 then 2); reads back a `PersistentArrayMap` |
| P-24 | `(float 0.1)`; `(int 5)`, `(short 5)`, `(byte 5)`; `2^70` as a `BigInteger`; `5N` | `Double 0.10000000149011612`; `Long`; `clojure.lang.BigInt`; `Long` |
| P-25 | `(keyword nil "a/b")` | reads back namespace `"a"`, name `"b"`; `=` false |
| P-26 | `(java.sql.Timestamp. ms)`; `(java.time.Instant/now)` | reads back `java.util.Date`; by reference (`eedb15`) |
| P-27 | `long[]`, `Object[]` as a value in a slab | `IllegalArgumentException: Not a host typed array: class [J` (and `[Ljava.lang.Object;`) |
| P-28 | disk atom holding `{:f inc}`, `{:re #"a"}`, `{[1 (byte-array 1)] :v}` | each refused with the SER:224 message; in an in-process atom `{:f inc}` reads back the identical fn |

Not probed (no ClojureScript build, bb, or other host on this box): every statement about the JS codec and the
non-JVM hosts above is from the source.

---

## Part 2 — the flat codec (0xED / 0xEE / 0xEF)

### 2.1 Writers and readers

| Host family | Writer | Reader |
|---|---|---|
| BA | `flat-map->eve-bytes` BA:452-467, `flat-set->eve-bytes` BA:422-436, `flat-vec->eve-bytes` BA:438-450, entered from `value->eve-bytes` BA:622-629; public face `encode-key` BA:778-782 | arms `0xED` BA:291-301, `0xEE` BA:304-311, `0xEF` BA:314-321 of `eve-bytes->value`; public face `decode` BA:784-787 |
| JS | `serialize-flat-collection` JS:838-881, `serialize-flat-element` JS:883-891, entered from `serialize-key` JS:683-696; public face `encode-key` JS:1438-1447 | arms JS:1099-1132 of `deserialize-element` |
| JK (unwired) | JK:435-480 | JK:304-336 |

The JS codec refuses the BA entry point by name: `(throw (ex-info "eve.codec: no flat form on this codec (value->eve-bytes)" {:codec :js}))`
(JS:1470-1476) — the flat form on CLJS is reachable only through `encode-key`.

### 2.2 Every caller, and what the flat bytes are used for

| Caller | `path:line` | Use |
|---|---|---|
| map key | `src/eve/map.cljc:68`, `:1778` | the HAMT entry's key bytes: hashed (Murmur3) and compared byte-for-byte |
| set element | `src/eve/set.cljc:58`, `:1049` | same (an element is a key, `FORMAT-DECISIONS.md:95-100`) |
| sorted collections | `src/eve/sorted.cljc:115`, `:120` | key bytes and their Murmur3 |
| eve-deftype type id | `src/eve/deftype/registry.cljc:57` | xxh3-128 of the type name's key bytes |
| root digest | `src/eve/digest.cljc:173` (`codec/value->eve-bytes`) | the "scalar hash" input (Part 5, unread) |
| a legacy-index test | `test/eve/jvm_legacy_index_test.clj:33` | — |
| fixtures | `test/eve/eve_bytes_fixture_test.cljc:81-86` | the pinned bytes |

**Not** a caller (grep for `eve.codec` requires and `codec/` calls over `src adapters net-local`): nothing under
`src/eve/net/` (the network atom uses its own codec, path C), nothing under `src/eve/thread/`, no journal adapter.
So at this pin flat bytes are **key bytes and digest input only** — never a cross-process message, a journal record,
a network payload or a thread descriptor. (Thread descriptors and the journal: to confirm when those files are read.)

### 2.3 Canonical order

Rule: "A flat set's elements are written in ascending order of their encoded bytes, and a flat map's entries in
ascending order of their encoded key bytes — unsigned, lexicographic, a shorter prefix first"
(`doc/unification/FORMAT-DECISIONS.md:76-83`). One definition: `compare-bytes`
(`src/eve/deftype_proto/data.cljc:627-640`). Enforced **by writers only**: BA:430 (`sort d/compare-bytes`), BA:459-460
(`sort-by first d/compare-bytes`), JS:851-853, JS:876-877. "A reader accepts any order" (`FORMAT-DECISIONS.md:83`) —
neither decoder checks order, duplicate keys, or that a length-prefixed element is itself canonical. A vector keeps
its order. Consequence: canonical bytes are a property of bytes a current eve writer produced, not of bytes received.

### 2.4 Nesting

A flat collection's children are flat: BA encodes every element with `value->eve-bytes` (BA:430, 444, 460), JS with
`serialize-flat-element` (JS:883-891). BA decodes children with the **1-arity** decoder (`sio` nil; BA:297, 300, 310,
320), so a pointer tag met inside flat bytes is handed to its constructor with a nil slab I/O.

### 2.5 Limits

* Lengths and counts are **signed i32** (`read-i32-le` BA:292, 296, 299; `.getInt32` JS:1101, 1105). No maximum is
  checked; the practical bound is the host array (2^31-1 on the JVM).
* No nesting-depth limit: both encoders and both decoders recurse per level (BA:297-300; JS:1106-1109). The limit in
  practice is the thread's stack — probed on the JVM (§1.6 P-16, P-17): encode fails between 800 and 1,200 levels,
  decode between 500 and 1,000 (an 11 KB input), each with a `StackOverflowError`.
* A scalar's bytes are not checked to be fully consumed (§1.6 P-3), and the one place that stores a bare scalar — the
  scalar block — relies on that: it hands the decoder the whole block, stale tail included
  (`src/eve/deftype_proto/alloc.cljc:1514-1522`).
* The slab adds its own: a value over 64 bytes was meant to go out of line (tag 0x19) but "the writer is not wired
  into `encode-val`" (`doc/unification/FORMAT-DECISIONS.md:324-329`; BA:757-776).

### 2.6 Where "flat" is not self-contained

| Leaf inside a flat collection | BA | JS |
|---|---|---|
| host object, fn, regex, byte array | `0x15` reference (BA:641-643) or the shared-domain refusal | reference (JS:676-681) |
| JS typed array | — | **a slab pointer** `0x1B` (JS:883-891 → `serialize-value-a` → JS:668-670 → process_slab.cljs:1429) |
| eve array, obj, deftype instance | `IPlain` → its plain form, flat (BA:631-639; an `EveArray` becomes a vector of numbers, `src/eve/array.cljc:359-366`); none → reference | **a slab pointer**: `(satisfies? d/ISabStorable elem) (d/-sab-encode elem nil)` (JS:586-587) |
| record | flat map (type dropped) | `serialize-key` keeps "its record encoding" (JS:693-696): a `0x1A` pointer or zero bytes |

### 2.7 Decode target, in-place access, wrapper types

* Decode target is always plain host collections: `(transient {})`, `(transient #{})`, `(transient [])` (BA:293, 306,
  316; JS:1102, 1116, 1127). Every element is first **copied out** on BA (`enc/copy-of-range`, BA:297) and sub-viewed
  on JS (`.subarray`, JS:1106).
* In-place read access over flat bytes: **none.** No function looks a key up, counts, or indexes inside flat bytes
  without decoding the whole collection. The only in-place operations on encoded bytes are whole-key byte equality
  (`bytes-equal-at?`, `doc/unification/HOST-CONTRACT.md:503`) and Murmur3 over them.
* A type that wraps flat bytes and implements collection protocols: **none found.** Searched `src adapters net-local
  test` for `FlatMap`, `flat-map`, `LazyFlat`, `NetMap`, "network map", "lazy flat", and `deftype` near the flat
  readers. `src/eve/deftype/value_buffer.cljc` (96 lines) is an accumulator over slab value arrays
  (`src/eve/deftype/value_buffer.cljc:27-33`), not a view over flat bytes. The research claim holds at this pin.
* Aside: `adapters/region/js/src/eve/wasm_mem.cljs` ships a pre-compiled WASM blob with `deser_tag_info`,
  `hamt_collect_kv`, `hamt_reduce_sum`, `vec_collect_values` (`wasm_mem.cljs:103`, `:401-428`) — in-place readers of
  EVE bytes inside **slab** nodes. No caller of those four wrappers exists in the tree (grep).

---

## Part 3 — `eve.encoding` adapters and other byte utilities (IN PROGRESS)

Ten `eve.encoding` adapters exist: `adapters/encoding/{beam,clr,dart,go,jank,java,js,letgo,python,vs}`.
All but `js` define the same 55-odd names; `js` (195 lines) omits everything the JS codec does not call: `unsupported`,
`copy-of-range`, `long-bits->double`, `double->long-bits`, `fixed-integer?`, `floating?`, `big-integer?`,
`integer->twos`, `twos->integer`, `ratio?`, `ratio-parts`, `make-ratio`, `bigdec?`, `bigdec-parts`, `make-bigdec`,
`date?`, `date-ms`, `ms->date`, `uuid->bytes!`, `bytes->uuid`, `host-char?`.

API of the Java adapter (ENCJ), arities in brackets — the reference shape:

| Group | Functions |
|---|---|
| errors | `throw-index-out-of-bounds [msg]` :12, `throw-unsupported []` :17, `unsupported [msg]` :22, `illegal-argument [msg]` :214, `index-out-of-bounds [msg]` :219 |
| arrays | `arraycopy [src soff dst doff len]` :32, `copy-of-range [b from to]` :37, `make-bytes [n]` :247, `bytes-of [xs]` :252, `bytes-copy [b]` :257, `bytes= [a b]` :209 |
| UTF-8 / UTF-16 | `utf8-bytes [s]` :44, `utf8-string [b off len]` :49, `utf16-units [s]` :165, `code-points->string [cps]` :364 |
| IEEE-754 | `long-bits->double [x]` :54, `double->long-bits [d]` :59 (raw: "NaN payloads kept"), `read-f32-le [b off]` :227, `read-f64-le [b off]` :232, `f32-le-bytes [v]` :237, `f64-le-bytes [v]` :242 |
| numbers | `fixed-integer? [v]` :64, `floating? [v]` :70, `big-integer? [v]` :78, `integer->twos [n]` :85, `twos->integer [bs]` :97, `ratio? [v]` :106, `ratio-parts [r]` :108, `make-ratio [n d]` :113, `bigdec? [v]` :115, `bigdec-parts [d]` :117, `make-bigdec [unscaled scale]` :125 |
| instants, uuids, chars | `date? [v]` :131, `date-ms [d]` :136, `ms->date [ms]` :141, `uuid->bytes! [u b off]` :146, `bytes->uuid [b off]` :156, `host-char? [v]` :172 |
| typed arrays | `host-array? [v]` :179, `array-subtype+bytes [a]` :302, `typed-array [subtype n]` :326, `typed-aget [a subtype i]` :338, `typed-aset! [a subtype i v]` :352, `f64s-le [b off n]` :274, `i32s-le [b off n]` :281, `f64s->bytes-le [da]` :288, `i32s->bytes-le [ia]` :295 |
| misc | `map-entry [k v]` :27, `new-cache []` :184, `cache-get [c k]` :198, `cache-put! [c k v max]` :201, `map-hash-code [m]` :263, `write! [w s]` :269 |
| byte codec slot (for `eve.net.bytes`) | `bytes->b64 [bs]` :393, `b64->bytes [s]` :399, `bytes->hex [bs]` :411, `hex->bytes [s]` :420 — `nil` on `js`, `beam`, `clr`, `go`, `jank`, `vs` (those take the portable code) |

Guarantees read so far:

* **Endianness**: little-endian everywhere, by explicit byte arithmetic in BA (BA:93-134) or a `ByteBuffer` ordered
  `LITTLE_ENDIAN` (ENCJ:224-225); never the host's native order.
* **UTF-8 is the host's**, with host defaults for malformed input. Decode: `(String. b off len "UTF-8")` (ENCJ:49-52),
  `TextDecoder` (`adapters/encoding/js/src/eve/encoding.cljs:33,40-43`; JS:148), Dart `.allowMalformed true`
  (`adapters/encoding/dart/src/eve/encoding.cljd:42-45`), Python strict `.decode "utf-8"`
  (`adapters/encoding/python/src/eve/encoding.lpy:49-52`). No shared validation layer.
* `copy-of-range` allocates `(byte-array n)` **before** it copies (ENCJ:37-42).

Per-host differences that reach the bytes (each read in its adapter):

| Host | Unpaired surrogate on encode | Malformed UTF-8 on decode | Instant precision kept | Char type |
|---|---|---|---|---|
| JVM / bb / jolt (`java`) | `?` 0x3F (probed) | U+FFFD, no error (probed) | ms (`java.util.Date`) | UTF-16 unit |
| ClojureScript family (`js`) | U+FFFD `EF BF BD` (probed on Node) | U+FFFD, no error; a leading BOM is dropped (probed on Node) | ms (`js/Date`) | none (`EveChar`) |
| basilisp (`python`) | `.encode "utf-8"` — Python's strict codec (`encoding.lpy:44-47`) | `.decode "utf-8"` strict (`:49-52`) | ms; microseconds truncated (`:135-139`) | none (`host-char?` false, `:168-173`) |
| ClojureCLR (`clr`) | `Encoding.UTF8.GetBytes` (`encoding.cljr:43-46`) | `GetString` (`:48-51`) | ms (`:148-156`) | UTF-16 unit |
| clojerl (`beam`) | a clojerl string is already its UTF-8 binary (`encoding.clje:46-50`) | bytes become the string as they are (`:52-55`) | **whole seconds** (`:123-127`) | none (`:160-165`) |
| ClojureDart (`dart`) | `convert/utf8.encode` (`encoding.cljd:40`) | `.allowMalformed true` (`:42-45`) | ms (`:92-94`) | none (`:114`) |
| glojure (`go`), let-go (`letgo`), jank, ValueScript (`vs`) | the host's native call (`encoding.glj:45-53`, `encoding.lg:35-37`, `encoding.jank:178-186`) | same | ms | a code point ("a rune") on glojure, let-go, jank |

#### `src/eve/net/bytes.cljc` (157 lines) — eve.net's byte **vectors**

"Byte vectors (vectors of 0..255) — the one binary form eve.net's portable core reads and writes"
(`src/eve/net/bytes.cljc:2`). Not host byte arrays; a host converts at its edge.

| Function | Line | Guarantee |
|---|---|---|
| `hex-portable [bs]`, `hex [bs]` | 22, 25 | lower-case; `hex` uses the host's `eve.encoding/bytes->hex` when it is not nil |
| `hex-value [c]`, `unhex-portable [s]`, `unhex [s]` | 35, 40, 44 | either case in; throws `"hex text of odd length"`, `"not a hex digit: "` |
| `utf8 [s]` | 47-66 | by code point over `utf16-units`. A high surrogate followed by **anything** is combined (`:57-59` checks only that a next unit exists); a lone surrogate is written as its own 3-byte sequence — a third behaviour beside the JVM's `?` and JS's U+FFFD |
| `code-points [us]`, `units->str [us]` | 68, 81 | "a lone surrogate stands as itself" (`:69`) |
| `decode-cp [bs i]`, `utf8->str [bs]` | 86, 94 | **no validation**: the lead byte alone picks 1–4 bytes; continuation bytes are taken `mod 64` unchecked; no overlong or range check; a short tail throws the host's index error |
| `to-b64-portable`, `to-b64` | 101, 113 | standard alphabet, padded |
| `from-b64-portable`, `from-b64` | 116, 140 | characters outside the alphabet are **skipped**; "A short tail decodes as a 4-digit group does with the missing digits zero" (`:124`) |
| `u32le [x]`, `read-u32le [bs i]` | 146, 148 | little-endian by arithmetic (no bit ops) |
| `slice [bs from to]`, `join [parts]` | 151, 153 | `subvec`, `into` |

Pinned by `test/eve/net/bytes_codec_test.cljc` (each host's native plug against the portable code — read in Part 4).

#### `src/eve/hamt_util.cljc` (726 lines)

| Function | Line | What |
|---|---|---|
| `portable-hash-bytes [buf]` | 44-99 | Murmur3_x86_32, seed 0, over a host byte array (signed or unsigned elements: `ubyte` `:39-42`); arithmetic through `eve.int32`. The HAMT's placement hash and the `:hash` column of `eve-bytes.edn` |
| `xxh3-128-bytes [buf]` | 661-665 | XXH3-128, seed 0, default secret, "16 big-endian digest bytes" (`:663`); portable 32-bit-limb implementation `:635-649`; a native one may be plugged with `register-xxh3-128-impl!` (`:653-659`) only after the KAT suite |
| `key-hash-bytes [buf]` | 667-671 | = `xxh3-128-bytes`; **no caller in `src/`** |
| `fold-hash-int [hb]` | 673-679 | XOR of the digest's four big-endian u32 words, as a signed int32 |
| `popcount32`, `mask-hash`, `bitpos`, `has-bit?`, `node-bit`, `get-index` | 685-726 | HAMT bit helpers; `mask-hash` is bits `[shift, shift+5)` of the 32-bit pattern (FORMAT-DECISIONS §6); `node-bit` reads the pre-§6 JVM slot |

#### `adapters/int32/*` — `eve.int32`

Five implementations, one five-function surface: `i32 [x]`, `imul [a b]`, `rotl [x r]`, `ushr [x n]`, `popcount [x]`
— "signed results, the same on every host". `jvm` (`adapters/int32/jvm/src/eve/int32.clj:7-31`: longs narrowed by
`unchecked-int`), `js` (`adapters/int32/js/src/eve/int32.cljs:8-34`: `Math.imul` and 32-bit bit ops), `portable`
(`adapters/int32/portable/src/eve/int32.cljc:17-49`: values kept in [0, 2^32) by `quot`/`mod`, every intermediate
under 2^53 — ClojureCLR, basilisp, clojerl, glojure, ClojureDart), `vs`, `jank`.

#### `adapters/typed-view/*` — `eve.typed-view`

Two implementations (`js` 194 lines, `jank` 207 lines); "only a host that has them carries this feature; shared code
never requires it" (`adapters/README.md:82`). Surface (`adapters/typed-view/js/src/eve/typed_view.cljs`): `get-sab`
:39, `get-offset` :47, `get-typed-view` :52, `get-int32-view` :68, `get-descriptor-idx` :77, `afill-simd!` :98,
`acopy-simd!` :111, `asum-simd` :125, `amin-simd` :138, `amax-simd` :152, `aequal-simd?` :166, `from-typed-array`
:184. These are **host views straight onto slab memory** of an eve array's element bytes — the one existing
"operate on the bytes in place" surface, for numeric arrays only, in host byte order (a JS typed array is
little-endian on every platform eve runs on; nothing checks).

#### Unaligned access and atomics

Shared code reads i32 words at arbitrary offsets — "Fields inside a node are at arbitrary alignment, which is why
HOST-CONTRACT §2.1 requires a region's i32 load and store at any offset" (`doc/internals.md:265-266`). The atomic
loads need 4-byte alignment (`src/eve/mem.cljc:21-23`), so published-node reads go through `IPlainLoad`, whose default
composes the word from four bytes "never the region's atomic -load-i32 … on arm64 the atomic fallback FAULTS on
exactly the reads this protocol exists for" (`src/eve/mem.cljc:198-216`).

---

## Part 4 — the network layer (IN PROGRESS)

`src/eve/net/` holds **29** files at the pin (the brief says 23): aes, atom, bignum, bigops, blobs, broker,
broker_disk, bytes, codec, crypto, ed25519, edwards, ell2, gateway, gcm, graft, json, pages, platform,
portable_crypto, presign, run, sha256, sha512, snapshot, store, u64, wire, xxh3. `net-local/` holds 3 source files
(`net-local/src/eve/net/local_node.cljc`, `net-local/page/src/eve/net/local_page.cljs`,
`net-local/test/eve/net/local_node_test.cljc`).

Read so far:

### 4.1 `eve.net.codec` (1,097 lines) — a second value codec, the kernel's

* What it is: "A value ⇄ its ExtCID v3 block images … the kernel's own layouts" (`src/eve/net/codec.cljc:2-4`).
  `encode` → `{:root key-hex :set-root? bool :blocks {key-hex image}}` (`:647-651`); `decode [root set-root? get-block]`
  (`:782-786`); `encode-rec [v r0 known]` (`:635-641`) images only the paths that changed against a previous value's
  record; `decode-rec [band get-block]` (`:1028-1033`) checks a fetched tree canonical, then decodes;
  `attest [x at]` (`:588-597`) marks a map or vector as a record whose digest also covers a 128-byte band.
* **Scalars are not EVE tags.** A scalar is a 32-byte "band": a 16-byte type CID then the payload —
  `NIL-CID`, `TRUE-CID`, `FALSE-CID`, `INT32-CID`, `DOUBLE-CID` ("UPA2dbl "), `LONG-CID` ("UPA2lng ")
  (`:27-32`, `scalar-band` `:168-179`). A long carries both its f64 and its i64 (`:175`).
* Value blocks (content-addressed, key = SHA-256 of the image, `put-block` `:288-293`): string
  `[STR-CID 16][count u32][0 ×4][utf-8]`; keyword `["flwk" + 12 zeros][count][0][":" + text]`; symbol as a string led by
  byte `0x01`; char `["flhc" …][count][code point u32][utf-8]` (`value-image` `:206-216`).
* Collections: map/set root `[0xED][count u32][slot 32]` (`:433`), set root keyed `SHA XOR 0xEE…` (`:292-293`);
  HAMT bitmap node `[0x01][u16 0][u16 kv-bytes][data bitmap 4][node bitmap 4][child slots][16-byte hashes][kvs]`
  (`node-image` `:299-313`), placed by **XXH3-128 of the key bytes**, 5 bits a level to shift 125 (`frag` `:256-261`,
  `hamt-build` `:318-328`); vector root `[0x12][count][shift][tail-len][0 ×4][tail slot][trie slot]` (`:523-525`),
  vector node `[0x12][0 ×8][slots]` (`:472`); list root and one node per element (`:528-537`).
* Keys: `[0x01][text]` for string / keyword / symbol, `[0x00][f64]` for **any number** (`key-bytes` `:233-238`) — so
  `1` and `1.0` are one key image and are refused together (`apply-changes` `:330-340`).
* **Refused** (`ex-info` `:type :unencodable`, `:166`): "an integer past 64 bits" (`:176`); "a number that is neither
  an integer nor a float" — ratio, bigdec (`:178`); "a string whose first byte the kernel reads as a type (':' or
  0x01)" (`:209-212`); "a key that is not a string, keyword, symbol or number" (`:238`); "two keys share a full 128-bit
  hash (a collision node)" (`:321-322`); anything else — UUID, Date, byte array, record-as-such, fn —
  `(str "no image for " (pr-str x))` (`:568`, `:628`). So path C's value universe is **smaller** than the EVE tag set:
  no uuid, no instant, no big number, no bytes, no typed array, no reference.
* Framing / versioning: no version byte in an image; a v3 image is recognised by its exact size (`root-image!`
  `:674-680`: "not a v3 collection root image"); "A v2 image (a slot's 64 or 256 bytes) is refused" (`:662`).
* Caching: `*memo*` (`:539-544`), `*decoded*` (`:546-549`), `*checked*` (`:368-375`), each emptied past
  `MEMO-MAX 20000` (`:366`). Dedup: by content address only. Dictionary: none. Compression: none (grep `compress`,
  `gzip`, `deflate`, `zstd` over `src/eve/net`: not found — to re-confirm at the Part 4 pass).
* Canonical form: enforced **on read** by `decode-rec` with reason codes 8, 9, 12, 13, 14, 15, 17
  (`CANON-REASONS` `:806-810`): every node's image must equal "the image this codec writes for its content"
  (`:893`), each key hash recomputed (`:880`), counts checked (`:926`). NaN is canonicalised to
  `[0 0 0 0 0 0 F8 7F]` (`:85`).
* It is byte-level arithmetic over **vectors of 0..255**, not host byte arrays (`:17-18`, `src/eve/net/bytes.cljc:2`).

### 4.2 `eve.net.wire` (101 lines) — the envelope

JSON over `POST {endpoint}/v1/domains/:domain-id/…` with `Authorization: Bearer` (`src/eve/net/wire.cljc:11-15`);
blobs in JSON are base64 (`:28`, `:57`). Steps: `create` `:30-36`, `deref-atom` `:38-48`, `cas-root` `:50-70`,
`release-reader` `:72-75`, `renew-lease` `:77-83`, `presign` `:87-101` (100 keys a call, `PRESIGN-BATCH` `:85`).

### 4.3 `eve.net.atom` (476 lines) — what crosses on deref and swap

* **deref** (`load!` `:322-338`): one `POST …/deref`; if the answered `root_key` equals the handle's, nothing else
  (`:334-335`). Otherwise `sync-step` (`:281-317`): unframe the VSNP `root_blob` (`:289`), diff its entries against
  the handle's, fetch the changed entries' block trees level by level — one presign batch and GETs per level
  (`fetch-tree-step` `:171-188`) — then `codec/decode-rec` each (`:305`).
* **swap!** (`write-attempt!` `:390-436`): `codec/encode-rec` against the previous record (`:398`); count references
  to find the fresh blocks (`recount` `:235-240`); build the VSNP snapshot (`snap-of` `:265-271`) and a GRFT graft
  (`:407-415`); `blobs/upload-blocks` of the fresh blocks + the snapshot + the graft (`:418`); `wire/cas-root` with
  `old_key`, `new_key`, `root_blob`, `root_count`, `graft_sha`, `retired_keys` (`:419-428`). The value must be a map
  (`:394-397`). 409 → backoff, ≤ 50 attempts (`:42`, `:461-474`).
* **It does not use `eve.net.pages`.** The only references to `eve.net.pages` outside the file are a docstring
  (`src/eve/net/codec.cljc:372`) and `test/eve/aegis/pages_v3_test.cljc` (grep). So at this pin the live network atom
  speaks snapshot + content-addressed blocks; the page writer/reader is a library with a test and no caller in `src/`.
  (Which generation WIRE.md §7 describes vs what `blobs`/`snapshot` do: to answer after reading those files.)

### 4.4 `eve.net.pages` (461 lines)

Per the coordinator's own reading (cited, not re-derived): ExtCID v3 page writer/reader; 1 MiB `REGION`
(`src/eve/net/pages.cljc:32`), 64-byte page header (`:34`), one size class per page, 256-byte own headers (`:40`),
64-byte slots (`:41`), every block SHA-256-verified on read (`:416`, `:433-436`), `:known` so only new blocks are
written (`:288-339`). Added from this read: 15 block classes up to 10,240 bytes (`CLASS-SIZES` `:38`), and a block
past that is refused — `"pages: a block of " n " bytes is past the largest class"` (`:99-101`); a page's id is
`SHA-256(salt ‖ seq ‖ class)[0..16]` with byte 0 the class (`cid-of` `:224-226`) — salted and sequential, **not** a
content address; header version byte must be 3 (`:419`); pages may be handed over as base64 text and are decoded on
first touch (`:389-391`).

Remaining for Part 4: json, run, platform, presign, store, blobs, snapshot, graft, broker, broker_disk, gateway,
crypto one-liners, `src/eve/keyring.cljc`, net-local, the test inventory under `test/eve/net/`, the per-file table.

---

## Part 5 — digests and canonical bytes (NOT STARTED)

## Part 6 — defenses against malformed / untrusted bytes (codec rows only so far)

| Check | Where | Note |
|---|---|---|
| length < 3 → `"EVE bytes truncated"` | BA:190-191 | the only explicit truncation check in BA |
| **magic is not checked on BA**: the tag is read from byte 2 with bytes 0-1 unread | BA:194 | any 3+ bytes decode by their third byte |
| magic checked on JS; a miss answers **`nil`**, not an error | JS:912-914, 1141-1142 | "No magic prefix — unsupported legacy format" |
| unknown tag: BA throws `"Unknown EVE type tag"`; JS answers `nil` | BA:325-328; JS:1134-1140 | the two families disagree |
| string / keyword / symbol lengths | BA:205, 208, 211… ; JS:934, 939… | **trusted**; BA relies on the host array's bounds exception, JS `.subarray` clamps silently (a short read, no error) |
| flat `count`, `klen`, `vlen`, `elen` | BA:292-320; JS:1101-1131 | **trusted**, signed i32; BA allocates `klen` bytes before copying (ENCJ:37-42) |
| bigint length | BA:161-166; JS:514-524 | **trusted** u32 |
| reference realm and handle | SER:248-259 | checked: `"eve: a reference written by another process names nothing in this one"`, `"eve: a reference handle this process never issued"` |
| pointer offset (0x10–0x13, 0x1C–0x1F, 0x40–0xEC) | BA:261-288, 325-327 | **trusted**: handed to the constructor as read |
| depth | — | **no limit** in either codec |
| total size | — | **no limit** |
| jank variant | JK:39-58 | unchecked `memcpy` from `B(b) + i` |

Rest of Part 6 (net codec / pages / wire / snapshot / graft, SECURITY.md threat model): pending.

## Part 7 — fixtures (eve-bytes.edn only so far)

`test/eve/fixtures/eve-bytes.edn`: **72 rows** (`:11-82`), each `{:note :value :bytes "hex" [:hash int] [:hosts #{…}]}`;
15 rows carry a Murmur3 `:hash`; 23 rows are restricted by `:hosts`, 49 run on every host. Produced "from the JVM
codec … and reviewed by hand" (`:3-5`). Tags pinned: 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 14 16 17 18 ED EE EF.
Tags with **no** row: 10–13, 15, 19, 1A, 1B, 1C, 1D, 1E, 1F, 40–EC. Values with no row: NaN, ±Infinity, -0.0,
a non-BMP character, an unpaired surrogate, a string past 256 bytes, a byte array.
Test: `test/eve/eve_bytes_fixture_test.cljc` — encode through `codec/encode-key` and compare hex (`:102`), decode and
compare (`:104`), Murmur3 of the bytes (`:107-111`), long-symbol refusal (`:115-122`). Host arms in the file:
`:cljs :bb :clj :cljr :lpy :clje :jank` (`:22`). Runners that load it: `test/eve/runner/{bb_main.clj, cljr_main.clj,
glj_main.glj, lpy_main.lpy, vs_main.vs, clje_main.clje, nbb_main.cljs, jolt_main.clj, jank_main.jank,
eve_test_main.cljs}` (grep).

Rest of Part 7 (`vsv2-blocks.edn`, `xxh3-vectors.edn`, `aegis-kernel-vectors.edn`, README): pending.

## Part 8 — FORMAT-DECISIONS and HOST-CONTRACT (pending final pass)

Both documents are read; the per-§ table is written at the end.

## Evidence for the assumptions (NOT WRITTEN YET)
