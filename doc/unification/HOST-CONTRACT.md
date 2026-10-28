# EVE Host Contract — the port kit (Unit 2)

> **Reading this in 0.9 (note of 2026-10-20).** The `file.cljc:NNN` line references in §1–§8 are at BASE `b24c8c034`
> and point elsewhere now (`mem.cljc` is 259 lines; the region openers live in `adapters/region/*`; most host
> primitives moved into adapters). The names are current unless a section says otherwise; §5, §6, §7 and §9 were
> brought up to date on 2026-10-20, and §10–§12 were written after the move and are current. The per-host
> composition today is `hosts.edn` (tabulated in `doc/release-0.9/ADOPTER-TIERS.md` §5.1); the tiers a new host
> works through are `doc/release-0.9/ADOPTER-TIERS.md` and `doc/adopting-eve-minimal.md`.

Written 2026-10-15 16:05 ET against BASE `b24c8c034`. Companion to `SEAM-MAP.md`
(`L-nn` references point there). This is the exact set of primitives a Clojure-like host
must supply to run the shared `.cljc` EVE. Every primitive is justified by the call sites
that consume it at BASE; anything with no consumer is marked optional and is not part of
the contract. §5 lists the operations whose *current* semantics secretly depend on the
JVM or on JS — portability defects whether or not Unit 4 changes them.

The division of labour this contract fixes:

| Layer | Who writes it | Namespaces at BASE |
|---|---|---|
| Algorithms: HAMT map/set, vector trie, list, typed arrays, Obj layout, epoch GC, CAS loop, slab allocator, overflow allocator, registry | **shared `.cljc`** | `eve.map`, `eve.set`, `eve.vec`, `eve.list`, `eve.array`, `eve.obj`, `eve.atom`, `eve.deftype-proto.{alloc,coalesc,data,serialize}`, `eve.hamt-util` |
| The byte codec (values ⇄ EVE bytes) | **the host's adapter**, one interface (`eve.codec`); two implementations, `codec/js` and `codec/byte-array` (the fork `L-25` names, now behind one interface) | `eve.codec`; what they share, `eve.deftype-proto.serialize` |
| The slab context (`ISlabIO`) over a set of regions | **shared `.cljc`** (today a JVM value and a CLJS token, `L-19`) | `eve.deftype-proto.alloc` |
| **This contract**: regions, bytes, encoding primitives, clock, sleep, timers, threads, fs, Clojure interop | **the host's adapters** (`eve/adapters/README.md`) | `eve.region` (regions over `eve.mem`'s `IMemRegion`), `eve.encoding` (§3-4), `eve.process` (§5), `eve.host` (§10); the CLJS `wasm`/`wasm-mem` layer, `native/mmap_cas.cc` |

---

## 1. Region creation

A *region* is a fixed-size byte range with the operations of §2. The shared code opens
regions in exactly two ways:

### 1.1 `(make-heap-region size-bytes) → region`
In-process memory, zero-initialised, shared by every thread/worker of the process that
can reach the object. Consumers: `alloc/init-jvm-heap-slab!` (alloc.cljc:565, slab
header + bitmap + data in one region), `jvm-open-heap-domain!` (atom.cljc:1435, root and
reader-map regions), coalesc overflow growth copy (alloc.cljc:300–309). CLJS equivalent:
`alloc/init-slab!` (:978) creates a `WebAssembly.Memory`/`SharedArrayBuffer` and wraps it
with `js-sab-region`; `cljs-open-sab-domain!` (atom.cljc:334) wraps fresh SABs.
Requirement: **zero-initialised** — the slab code relies on it for a fresh bitmap
(alloc.cljc:581 "byte-array is zero-initialized … no explicit zeroing needed"; CLJS
`init-slab!` zero-fills the bitmap explicitly at :997). A port must either guarantee
zeroed memory or zero the bitmap range itself.

### 1.2 `(open-mmap-region path size-bytes) → region`
A file-backed, **shared** (`MAP_SHARED`) mapping of `path` covering bytes `[0, size)`.
Semantics every host implements identically today (mem.cljc:376 CLJS, :585 bb, :1015 JVM):
* create the file if missing;
* **grow, never truncate**: if the file is shorter than `size`, extend it (sparse); if it
  is longer, map only `size` bytes and leave the rest (mem.cljc:1021–1023: "a 1 MB domain
  file peeked at 4096 bytes must not be truncated");
* newly extended bytes read as zero (sparse-file semantics; the slab growth path relies
  on it for the new bitmap words, alloc.cljc:353–366);
* the mapping stays valid after the file handle is closed (JVM closes the channel
  immediately, :1035; Node keeps the mapping in a Buffer);
* writes through one mapping are visible to every other mapping of the same file, in the
  same process (the peek-then-remap pattern maps the same file twice: alloc.cljc:369–373,
  :1657–1660) and in other processes;
* many mappings of one file may coexist and are never explicitly unmapped (growth simply
  opens a larger one; old ones are dropped).

Consumers: slab data + bitmap files (`.slab0–5`, `.slab0–5.bm`), the overflow file
(`.slab6`), the root (`.root`, 8320 bytes) and reader map (`.rmap`, 262144 bytes).

Optional provider: a *locked* region (the Lustre pattern, mem.cljc:401/:1077) implements
the same interface with byte-range file locks instead of hardware atomics; it is a provider
choice, not a second contract (`L-14`).

### 1.3 `(-byte-length region) → int`
Consumers: coalesc growth check (alloc.cljc:510, :548), heap overflow growth (:303).

---

## 2. Region operations (`IMemRegion`)

All offsets are byte offsets from the start of the region. The *shared* code is written
against these exact contracts (mem.cljc:57–133 is the protocol; the semantics below are
what the callers rely on, which is stricter than the docstrings in two places, marked ★).

### 2.1 32-bit integer operations

| Op | Contract | Alignment | Atomicity | Consumers (why) |
|---|---|---|---|---|
| `-load-i32 [r off] → i32` | signed 32-bit value of the 4 LE bytes at `off` | ★ **any** | acquire when aligned | every slab-header field, root pointer, epoch, worker slots, atom slots, bitmap words (aligned); **and** every `ISlabIO -sio-read-i32` of HAMT node interiors (`map.cljc:125,138,160,469…`), which are at arbitrary offsets (`[len:i32][bytes]` packing) |
| `-store-i32! [r off v]` | store low 32 bits of `v` LE | ★ **any** | release when aligned | same two families as load (`-sio-write-i32!` writes node interiors unaligned, e.g. map.cljc:148) |
| `-cas-i32! [r off expected desired] → i32` | atomic compare-and-exchange; **returns the witness** (the value found); success ⇔ `(== witness expected)` | 4 | required, cross-thread and (file regions) cross-process | root-pointer CAS (atom.cljc:595, :1010), epoch/worker-slot claims (:160–185), atom-slot claim (:1283), bitmap alloc/free (mem.cljc:1370–1399), slab growth leader election (alloc.cljc:378, :1670), coalesc descriptor locks (coalesc.cljc:125), deftype `cas!` (`-sio-cas-i32!`), array `cas!` |
| `-add-i32! [r off delta] → i32` | atomic fetch-and-add; returns the **old** value | 4 | required | epoch bump (atom.cljc:597, :1013), free-count maintenance (alloc.cljc:326, :381, :435), array `add!` |
| `-sub-i32! [r off delta] → i32` | atomic fetch-and-sub; returns old | 4 | required | free-count decrement (alloc.cljc:329), array `sub!` |
| `-exchange-i32! [r off v] → i32` | atomic swap; returns old | 4 | **optional** | only `eve.array/exchange!` (array.cljc:760, CLJS-only API). Derivable from `-cas-i32!` (NodeMmapRegion does exactly that, mem.cljc:330) |

Value domain: `i32` is a **signed** 32-bit integer on every host (JS `Int32Array`,
JVM `int`). Callers that need the unsigned reading mask explicitly
(`alloc/decode-class-idx` :48 — `(bit-and (long x) 0xFFFFFFFF)`). A host whose integers
are wider must sign-extend on load and wrap on store; a host whose integers are narrower
than 32 bits cannot host EVE.

### 2.2 64-bit integer operations

| Op | Contract | Alignment | Consumers |
|---|---|---|---|
| `-load-i64 [r off] → i64` | 8 LE bytes | 8 | coalesc header `COALESC_HDR_DATA_SIZE` (coalesc.cljc:101, :240, :266; alloc.cljc:509, :542, :546, :1743; atom.cljc:727) |
| `-store-i64! [r off v]` | | 8 | coalesc init (coalesc.cljc:211) |
| `-cas-i64! [r off expected desired] → i64` | witness return, like i32 | 8 | coalesc growth leader election (coalesc.cljc:243) |
| `-add-i64!` / `-sub-i64!` | — | — | **no callers at BASE** — not part of the contract |

Required for every region that backs a **class-6 overflow header**: the file-backed
`.slab6` region on all hosts, and the SAB-backed coalesc region on CLJS
(`init-sab-coalesc!`, alloc.cljc:1793). See §5.6 for the SAB hole.

Precision: the stored value is a full i64. The *host* value type may be lossy above 2^53
(JS `Number`); the contract requires exactness only up to 2^53 because the only stored
quantity is a data-region byte size (capped at 4 PiB = 2^52, coalesc.cljc:48–50).

### 2.3 Wait / notify

| Op | Contract | Consumers |
|---|---|---|
| `-wait-i32! [r off expected timeout-ms] → :ok ⎮ :not-equal ⎮ :timed-out` | block until the i32 at `off` ≠ `expected` or `timeout-ms` elapses; must return `:not-equal` without blocking when the value already differs | `eve.array/wait!` (array.cljc:841, :853 — CLJS-only API); CLJS CAS backoff uses `Atomics.wait` on a private SAB directly (atom.cljc:273) |
| `-notify-i32! [r off n] → int` | wake up to `n` waiters; returns the count woken, **0 is a legal answer** (bb, Lustre: mem.cljc:541, :613, :436, :1225) | `eve.array/notify!` (array.cljc:866) |
| `-supports-watch?` | **no production consumer** (tests only) — not part of the contract | — |

So for the slab/atom path proper, wait/notify are **optional**; a host that cannot block
on an address may implement `wait` as a bounded poll (what bb and Lustre do) and `notify`
as a no-op returning 0. A host exposing `eve.array` atomics should provide real ones.

### 2.4 Bulk bytes

| Op | Contract | Consumers |
|---|---|---|
| `-read-bytes [r off len] → bytes` | the `len` bytes at `off` as a host byte array (§3). ★ **May alias the region** (NodeMmapRegion returns a view, mem.cljc:363; JVM/SAB/bb copy). Shared code must treat the result as valid only until the next write to that range or the end of the current epoch pin, and must copy before retaining | every `ISlabIO -sio-read-bytes` (key/value bytes, HAMT scans), `jvm-read-eve-array`, `imr-bitmap-find-free-bulk` |
| `-write-bytes! [r off src]` | copy all of `src` into the region at `off` | every `-sio-write-bytes!`, scalar blocks, array/obj writers |
| `copy-region! [src soff dst doff len]` | region-to-region copy; implemented as read+write in every host (mem.cljc:1295–1305); a host may fuse it | `JvmSlabCtx -sio-copy-block!` (alloc.cljc:420) |

Non-atomic; the shared code publishes written blocks with an aligned atomic store or CAS
of a pointer *after* the bytes are written (node construction then root CAS), and reads
bytes only after acquiring the pointer. A host whose plain byte stores could become
visible *after* a later atomic store needs a release fence in `-store-i32!`/`-cas-i32!`
(the JVM gets this from `VarHandle` volatile semantics; JS from `Atomics`; the native
addon from `__atomic_*` with seq-cst).

---

## 3. Byte arrays

The shared code manipulates "bytes" through five operations only. A host supplies its
native byte-array type and these:

| Operation | Consumers at BASE |
|---|---|
| `(byte-array n) → bytes` (zero-filled) | codec encoders, `-sio-write-u8!`/`-sio-write-u16!` on the JVM (alloc.cljc:205, :226) |
| `(alength bytes) → int` | `bytes-length` ×4 (map.cljc:98, set:78, vec:65, list:63), `hamt-util/portable-hash-bytes` (:37) |
| `(aget bytes i) → unsigned 0..255` | `hamt-util/ubyte` (:18 — note the JVM arm masks `(bit-and b 0xFF)` because `byte[]` is signed), codec decoders |
| `(aset bytes i v)` | encoders |
| `(bytes-equal? a b) → boolean` | key match in `hamt-get`/`hamt-assoc` (map.cljc:89, set.cljc:71) — the JVM uses `Arrays/equals`, CLJS a loop |
| `(copy-bytes src soff dst doff len)` / slice | codec composition, `copy-region!` |

Signedness is the only trap: JVM `byte[]` elements are signed; `Uint8Array` elements are
unsigned. The shared code must only ever read bytes through an unsigned accessor.

---

## 4. Encoding primitives, numbers and strings

The EVE wire format (internals.md "Serialization Format") is little-endian throughout.
The unified codec (`L-25`) needs from the host:

| Primitive | Contract | Consumers |
|---|---|---|
| `put-i32-le!`/`get-i32-le` into bytes | 4 bytes LE, signed result | lengths, counts, pointers, `INT32` tag |
| `put-i64-le!`/`get-i64-le` | 8 bytes LE | `INT64` tag, coalesc header |
| `put-f64-le!`/`get-f64-le` | IEEE-754 binary64 bits, LE | `FLOAT64`, `DATE` (ms since epoch as f64) |
| `utf8-encode string → bytes`, `utf8-decode bytes → string` | UTF-8 both ways; lengths are **byte** lengths (`STRING_SHORT` ≤ 255 bytes, not chars) | strings, keywords, symbols, Obj schema field names |
| integer classification | `(int32? n)` for `[-2^31, 2^31)`; `(exact-integer? n)` — which integers the host can represent exactly | encoder tag choice (`INT32` vs `INT64` vs `FLOAT64`) |
| 32-bit wrapping multiply, rotate-left, logical shift right | Murmur3_x86_32 (`hamt-util.cljc:22–30`) | HAMT hash — must produce bit-identical results to the JVM/CLJS implementations; the fixtures pin known hashes |
| UUID ⇄ 16 bytes (msb/lsb big-endian) | `0x0B` tag | codec |
| `Date` ⇄ ms since epoch | `0x0E` tag | codec |

Number model requirements (what the shared code assumes):
* `i32` arithmetic on offsets and bitmaps wraps or is masked — the CLJS arm coerces with
  `(bit-or h 0)` (hamt_util.cljc:51, :74), the JVM with `unchecked-int`/`long`. A port
  with 64-bit integers must mask hash arithmetic to 32 bits.
* Slab-qualified offsets are `[class:3 | block:29]` packed into a signed i32 and compared
  with `==`; `NIL_OFFSET` is `-1` (alloc.cljc:46) and the "claimed" sentinel is `-2`
  (atom.cljc:72). A host must preserve the sign bit through store/load.
* Doubles: the codec writes the IEEE bits; NaN payloads are not normalised (fixture note).

---

## 5. Process-level primitives

The interface is `eve.process` (one adapter per host capability, `adapters/process/*`); the names below are its names
as of 2026-10-20 (`adapters/process/java/src/eve/process.clj`). The consumer column's line references are BASE's.

| Primitive (`eve.process`) | Consumers | How hosts supply it |
|---|---|---|
| `(now-ms)` (wall clock, ms since Unix epoch, fits in 53 bits) | heartbeats (written as two i32 halves), flush throttle | `js/Date.now`, `System/currentTimeMillis`, ... |
| `(sleep-ms! ms)` — block this thread for about `ms` | CAS backoff | `Atomics.wait` on a scratch SAB, `Thread/sleep`, ... |
| `(start-timer! period-ms f) → handle`, `(stop-timer! handle)` | heartbeat (`eve.atom`, every 10,000 ms; stopped when the domain closes) | `setInterval`; a daemon `Thread` loop (`process/java`: the JVM, babashka, jolt) |
| `(on-process-exit! f)` | worker-slot release | `process.on "exit"`, `Runtime/addShutdownHook` |
| `(file-exists? path)` | a test's check (keyring_test) | `fs.existsSync`, `java.io.File` |
| `(create-exclusive! path)` → true for the one process that created the file | domain create-vs-join (`persistent-atom-domain`): the creator is decided by one atomic create, never by an existence check then a create, which two processes opening a fresh domain together both passed (2026-10-19, lane EVE-THREAD) | `File.createNewFile`, `fs.openSync(p, "wx")`, `file:open(P, [write, exclusive])`, `FileMode.CreateNew`, `os.open(O_CREAT\|O_EXCL)` |
| `(ensure-parent-dir! base-path)` | creating a disk domain's directory (the default domain's `./eve/`), `eve.atom` and `eve.keyring` | `File.mkdirs`, `fs.mkdirSync(..., {recursive: true})`, ... |
| `(thread-id)` (**capability**) | per-thread epoch aggregation (threads of one process share its worker slot) | JVM `Thread/currentThread`; on ClojureScript a worker holds its own slot; bb collapses to one key (`L-57`) |
| `(identity-hash x)` | eve's reference table (`0x15` REF, FORMAT-DECISIONS §9: a host-only value keyed by identity) | `System/identityHashCode`; on ValueScript the value's address |
| `(random-bytes n)` — n bytes of the OS's entropy, a vector of 0..255 | a domain's realm id (`serialize.cljc`), the key ring's salt and auto key (`eve.keyring`), `eve.crypto/random-bytes` (a network atom's group key, nonces) | `SecureRandom`, `getRandomValues`, `os.urandom`, `RandomNumberGenerator`, `crypto:strong_rand_bytes`, `/dev/urandom`; ValueScript's is clock-seeded, not cryptographically strong |
| mutable queue / cell for the retire queue and flush timestamp | atom.cljc domain-state | not a process primitive: a portable `atom` of a persistent vector satisfies every consumer, and that is what eve uses |

---

## 6. Clojure-level interop the host must have

* Types: eve's types are written once in eve's type language (`eve.deftype-proto.vocab`:
  protocol impls with the JVM's or ClojureScript's names, the table between them in
  `eve.deftype-proto.proto-map`); the host's `eve.types` adapter realizes them as its own
  (`eve/adapters/README.md`, "types"). A host whose types reach eve memory through
  `ISlabIO` passes its steps as hooks to the shared `eve.deftype-proto.sio`.
* An atom interface (`IDeref`/`IAtom`/`ISwap`/`IReset`) the host's `deref`/`swap!`/
  `reset!`/`compare-and-set!` dispatch to.
* Printing hooks (`print-method` / `IPrintWithWriter`).
* `satisfies?` on the EVE protocols (`IEveRoot`, `IPlain`, …).
* Macros at compile time (`eve/deftype`, `eve-deftype`). (At BASE a host whose `deftype` could not take its
  collection interfaces — SCI/bb — was called a **materializing host**, reading through the registry's
  materializers. That is no longer so: babashka realizes eve's lazy slab-backed types as sci deftypes whose
  interfaces `adapters/types/bb` translates onto `eve.core`'s protocols (`adapters/coll/protocols`), and glojure
  does the same (ValueScript's `types/vs` translates the same way, but `eve/deftype`'s expansion does not run there
  yet: `hosts.edn :vs`); `bb test:bb` runs `eve.deftype.int-map-test`, `eve.deftype.rb-tree-test` and
  `eve.rrb-vector-test`. The pairing of a types adapter with its coll adapter is `doc/release-0.9/ADOPTER-TIERS.md`
  §2.3.)

---

## 7. Capability levels

| Level | Means | Hosts at BASE |
|---|---|---|
| **A. Full** | §1–§6 complete: real cross-process atomics on file regions, threads or workers, lazy slab-backed types | JVM, Node (CLJS) |
| **B. Materializing** | §1–§5 complete, but cannot host the macro-generated types; derefs materialize through registered constructors; swaps re-serialize whole values | bb *as designed* (`L-28`) — but see C |
| **C. Single-process** | file regions open but atomics are not cross-process | bb *as implemented* (`L-55`): `locking` is process-local. bb has `FileChannel`/`FileLock` (probed 2026-10-15) → it can reach level B with the fcntl provider |

A host declares its level once; the shared code never branches on the host name.

The "Hosts at BASE" column is history. The levels measured on 2026-10-16 —
the JVM A, bb B (a participant through the exclusive-hold protocol,
UNIFICATION-REPORT §1), Node A (the heap region is SharedArrayBuffer +
Atomics; files go through EVE's own N-API addon), and every installable
Clojure-like — are in `PORT-TARGETS.md` §3, with the per-host evidence under
`ports/evidence/` at commit `1fb9cbca`.

Where the hosts stand on 2026-10-20 (`hosts.edn`): every host but ValueScript realizes eve's types lazy and
slab-backed (no host is materializing; on ValueScript `eve/deftype`'s expansion does not run yet). babashka is lock-only on files — `adapters/region/jvm-nio-lock`, whose read-modify-writes a
`FileChannel` byte-range lock makes exclusive (the fcntl provider of §1.2, `IHostAtomics/-atomic-rmw?` false) — so it
is a cross-process participant through the exclusive-hold protocol, not a single-process host (C). The other ten
rows' regions use hardware atomics (ValueScript has no thread root yet, so eve.thread does not run there). The
adapter each host plugs per feature is tabulated in `doc/release-0.9/ADOPTER-TIERS.md` §5.1.

---

## 8. Portability defects in the current seams (flagged per the lane brief)

Each is a place where a seam's *current* semantics depend on the JVM or on JS, whether
or not Unit 4 changes it. `L-nn` = SEAM-MAP entry.

| # | Defect | Where | Depends on |
|---|---|---|---|
| 8.1 | `-read-bytes` returns a view on Node mmap, a copy elsewhere (`L-46`); `-sio-read-bytes` returns a `.subarray` view into *slab* memory on CLJS (`L-47`) | mem.cljc:363, :460; alloc.cljc:1412–1418 | JS typed-array views |
| 8.2 | `-load-i32`/`-store-i32!` must accept unaligned offsets — a DataView behaviour the HAMT layout bakes in; the JVM VarHandle throws on misalignment and the candidate added a byte-wise fallback (mem.cljc:772–820) | map.cljc node packing | JS DataView |
| 8.3 | `-sab-encode` returns pointer bytes (CLJS) or an offset (JVM) — `L-50` | map/set/vec/list/array deftypes | — |
| 8.4 | Constructor signature `(fn [_sab off])` vs `(fn [sio off])`; decoders take a legacy `s-atom-env` — `L-51`/`L-52` | serialize.cljc, macros.clj | CLJS legacy SAB env |
| 8.5 | i64 on `JsSabRegion` is a two-i32 emulation (non-atomic), `add/sub` throw, and the SAB coalesc growth path CASes it — `L-48` | mem.cljc:200–222, coalesc.cljc:243 | JS has no 64-bit atomics on SAB without BigInt64Array |
| 8.6 | INT64 encoding is gated on `js/Number.isSafeInteger`; decoding goes through `js/Number` — values ≥ 2^53 are lossy on CLJS only (`L-38`) | serialize.cljc:563, :943, :1169 | JS number model |
| 8.7 | UTF-8 decode copies SAB-backed views first because `TextDecoder` rejects them (serialize.cljc:355–362) — a JS-only cost the codec contract must not require of others | serialize.cljc | TextDecoder |
| 8.8 | UUID built by parsing the host's hex string form (CLJS) vs raw msb/lsb (JVM) (`L-39`) — identical bytes, host-shaped code | serialize.cljc:661/:766, mem.cljc UUID branch | — |
| 8.9 | FNV-1a name hash over UTF-16 code units & 0xFF (`L-43`) | atom.cljc:1254 | JVM/JS strings are UTF-16 |
| 8.10 | Symbol encoding has no long form; both hosts truncate the length byte (`L-40`) | mem.cljc symbol branch, serialize.cljc:681–713 | shared defect |
| 8.11 | `hasheq` of Eve collections uses the host's Clojure hash (`platform.cljc:38/46`) — correct, but a port must know this is *not* the on-disk hash (that is Murmur3 over EVE bytes, `hamt_util.cljc`) | — | — |
| 8.12 | Obj field order follows host map iteration order (`L-32`) | obj.cljc:72/:515, alloc.cljc:758/:791 | JVM/JS hash-map ordering |
| 8.13 | bb atomics are process-local (`L-55`); bb thread epochs collapse (`L-57`) | mem.cljc:488–700, atom.cljc:770–800 | SCI's limits; a correct bb provider exists (`FileLock`) |
| 8.14 | `JvmByteBufferRegion` atomics box every argument through `MethodHandle.invokeWithArguments` (mem.cljc:725–760) — a JVM-shaped cost, not a contract leak; noted because a port must not copy the *shape* (a VarHandle-per-call) as if it were the contract. 2026-10-21: the calls were also resolved by reflection (a vector argument), most of a JVM swap; the handles are now spread once and called with `.invoke` on an `Object[]` (`adapters/region/jvm-varhandle`): a counter swap 169 µs → 14.7 µs (11.8 µs in the final pass's perf suite, 2026-10-21 ET) | adapters/region/jvm-varhandle | JVM reflection limits |
| 8.15 | The CLJS slab context is the module (`L-19`): the contract's "slab context is a value" is violated by one host today | alloc.cljc:895–1497 | — |

---

## 9. Minimal port checklist (what host #4 writes)

1. **Regions**: a deftype implementing §2 over the host's shared memory, plus
   `make-heap-region` and `open-mmap-region` (§1). ≈ 20 methods. If the host has no
   cross-process atomics, implement the fcntl byte-range-lock provider (the Lustre
   pattern) and declare level C→B.
2. **Bytes** (§3): 6 functions. **Encoding** (§4): 8 functions. **The memory seam** (§11):
   nothing when the host's slab I/O can be eve's protocols (`adapters/slab-io/protocol`);
   otherwise its own `eve.slab-io`, held to `adapters/slab-io/test`. **The host seam** (§10):
   nothing when the host's core has Clojure's arrays, cells, locks, transients and
   wrapping integer ops (`adapters/core/clojure`); otherwise its core's completion or
   its own adapter.
3. **Process** (§5, `eve.process`): `now-ms`, `sleep-ms!`, `start-timer!`/`stop-timer!`,
   `on-process-exit!`, `file-exists?`, `create-exclusive!`, `ensure-parent-dir!`, `thread-id` (or a constant),
   `identity-hash`, `random-bytes`.
4. **Interop** (§6): the host's `eve.types` adapter (its collection interfaces), the atom
   interface, printing.
5. Run the byte-fixture suite (Unit 5) and the three-host round trip.

Nothing else: no second HAMT, no second codec, no second registry, no second swap loop.

---

## 10. The host seam: `eve.host` (2026-10-16)

What eve's shared code needs of a host beyond regions (§1–2) and the codec's primitives
(§3–4, `eve.platform`) — the host's own memory and arithmetic — has one place,
the namespace `eve.host`, which an adapter supplies (`eve/adapters/README.md`; never
`eve/src`). Every eve namespace takes these names from `eve.host`, not from its
host's core (`(:refer-clojure :exclude [...])` + `(:require [eve.host :refer [...]])`), and
a host supplies them there:

| Group | Names | What the shared code relies on |
|---|---|---|
| arrays | `byte-array` `short-array` `int-array` `long-array` `float-array` `double-array` `object-array` `into-array` `to-array` `aget` `aset` `alength` `aclone` | mutable, fixed-length, zero-filled; a byte reads back signed (§3's trap); `count`/`nth`/`seq` answer on one as on a JVM array |
| fixed-width integers | `unchecked-byte` `unchecked-short` `unchecked-int` `unchecked-long` `unchecked-add-int` `unchecked-multiply-int` `unchecked-inc-int` `unchecked-dec-int` `unchecked-add` `unchecked-multiply` `unchecked-inc` `unsigned-bit-shift-right` `long` `double` | wrap at their width as the JVM's do (§4's number model) — a host with unbounded integers wraps them here |
| cells | `volatile!` `volatile?` `vswap!` | a mutable reference read and written by one thread; `vswap!` is one expansion on every host, `(vreset! v (f @v args...))`, so `(vswap! v conj! x)` holds where a host's `vswap!` is a function (basilisp's) |
| locks | `locking` | reentrant mutual exclusion on a value, across the process's threads |
| collections built in place | `transient` `persistent!` `conj!` `assoc!` `dissoc!` `disj!` `pop!` | on the host's own collections; every op's return value is used (the transient contract), so a host without transients may build persistently |
| array casts and hints | `bytes` `shorts` `ints` `longs` `floats` `doubles` `objects` `booleans` (and `long`, `double` above) | a cast does nothing to the array; a namespace that hints `^bytes`, `^long`, ... takes the name from here, since clojerl resolves a hint's symbol where it is used (the JVM's compiler reads these hints as its primitive and array classes whatever the symbol names) |
| values a top-level `def` holds | `const-bytes` `const-objects` `defdelay` | an array written once, from a collection, and never after; `(defdelay name body)`, a process-global value computed on its first `@name` |
| atoms | `swap!` `reset!` `compare-and-set!` | on the host's atoms and on eve's (an mmap atom): the host's own reach only its own atoms on clojerl |
| misc | `class` `token` | the value's host type (ClojureScript's `type`); `(token)`, a fresh value `identical?` only to itself (core.rrb-vector's edit token) |
| protocols | `extend-default` | `(extend-default proto impls...)`: `proto` extended to nil and to the host's root type (`Object`; `default` in a ClojureScript compile) — a protocol for every value |

Its implementations, one adapter per capability, shared by every host that has it:

- **`adapters/core/clojure`** — every host whose core has them as Clojure's does (the
  JVM, babashka, ClojureCLR, jolt, nbb, ClojureScript, and basilisp once
  `eve.host-completion` has made its core Clojure's): each name is a macro expanding to
  `clojure.core`'s own call (a ClojureScript compiler reads `clojure.core/aget` as
  `cljs.core/aget`; a token is a fresh `volatile!`), so a type hint on an
  argument reaches the host's compiler as written — measured on the JVM: loading every
  eve namespace gives the same 238 reflection warnings before and after the seam, the
  same set. These names are called, never passed as values (a macro is not a value). A
  ClojureScript compiler whose macros run in its own Clojure takes them through
  `adapters/core/cljs-compiler` (`(:require-macros [eve.host])`).
- **`adapters/core/beam`** — clojerl (the BEAM: no mutable memory, no arrays, cells,
  locks or transients in its core, unbounded integers): functions over `eve.clje-nif` —
  the NIF `adapters/core/beam/native/eve_clje_nif.c`, built on first load into
  `adapters/core/beam/target/clje/`:
  - an array of numbers is native memory (a heap region), one element per width, read
    and written through the NIF (0.035 µs an `aset`, 0.040 µs an `aget` from compiled code, 2026-10-22 ET,
    `doc/release-0.9/logs-2026-10-22/f-probe-clje-costs.log`); an object array is a term array (the
    NIF copies a term in on `aset` and out on `aget`);
  - a cell is clojerl's process value (the process dictionary: one process's, and held
    until the process ends);
  - a lock is the BEAM's global lock on the value (`global:trans`, reentrant for one
    process);
  - a collection built in place is built persistently;
  - the integer ops mask and sign-convert to their width;
  - a `def`'s value is compiled into clojerl's module as a literal, which native
    memory, a cell or a delay is not: `const-bytes` is a binary there and
    `const-objects` a vector, and `defdelay`'s value is computed on its first
    deref (under the global lock on its name) and kept in the node's
    `persistent_term`;
  - `swap!`, `reset!` and `compare-and-set!` call clojerl's own on a clojerl atom and
    `eve.host/IAtom` otherwise (the deftype macros translate the JVM's
    `clojure.lang.IAtom` onto it: eve's mmap atom); clojerl's own are hard-wired to
    `clojerl.Atom`;
  - a token is an Erlang reference (`make_ref`).

- **`adapters/core/vs`** — ValueScript (its core Clojure's, written by one macro whose body
  holds the names, since a VS macro cannot call a defn): `locking`, which VS's core lacks, is
  a re-entrant lock per value — a slot of a table in linear memory keyed by the value's slab
  address (`vs.core/rt-addr`), its owner word taken by CAS with the thread's id
  (`eve.process/thread-id`), waited on with `memory.atomic.wait32` and released with a store
  and a notify (L19 C7: two mesh threads × 100,000 pins and unpins in eve.atom's shape, 0
  violations; the same program without the lock, 35,681).

Three rules for eve's own code follow from clojerl, and hold on every host: a type
whose field is mutable (other than a hash cache) is built with its `->T` factory,
never `(T. ...)` (clojerl's deftype has no mutable field: the field holds a cell
the factory makes); and a type hint that names a host class (`^String`,
`^java.io.Writer`) belongs in that host's adapter, not in shared
code (clojerl resolves every hint, and a caller of a function resolves its return
hint in the caller's namespace — so a namespace that calls a function hinted
`^long` takes `long` from `eve.host` too, as `eve.rrb-vector.rrbt` does for
`index-of-nil`); and a function eve registers at load (a type's constructor
from its header, the header readers, a builder) is a `defn`, registered by name,
never a `(fn ...)` in a top-level form — clojerl runs a fn that a top-level
expression makes in its Core Erlang interpreter, at every call (measured: the
header-tag reader, called on every read of a value, was one). The shared tests
follow the same rules (`eve.host`'s `swap!` on an eve atom, its arrays in the
byte fixtures).

A host that lacks some of these completes its core first (`eve.host-completion`, as
basilisp's does) or, where its core cannot be completed, supplies its own adapter.
glojure (2026-10-20, lane EVE-GLJ) takes `adapters/core/clojure` after its completion
(`adapters/completion/glojure`): glojure v0.7.22 has no `deftype` or `reify` (a type is a
record extended to its protocols, a mutable field a cell, as on clojerl), and its interpreter
has no `(.-field x)` read — the completion's Go patches (`go/*.patch`) give its build, glj-eve
(`impls/glojure/build.sh`), that read, a record type's factory for `new` (eve's map is a Go
value glojure takes as a map) and its goroutine id.

---

## 11. The thread feature: `eve.thread-host` (2026-10-19, lane ET-0)

What `eve.thread` — the executor service written once in shared cljc (lanes ET-1 on, the EVE-THREAD plan
`docs/port-gap-closure/coordination/reports/eve-thread-plan-2026-10-18.md` P§5) — needs of a host: threads, a
mailbox each, a real blocking wait, and the host's catch-all, binding frame, var names and evaluator. One namespace,
`eve.thread-host`, which an adapter supplies (`eve/adapters/thread/<impl>/`, one `hosts.edn` root per host; never
`eve/src`). It is a sibling of `eve.process` (§5), not an extension: the two vary independently (`process/java`
serves the JVM, babashka and jolt, which also share `thread/jvm`; `process/node` serves ClojureScript on Node and in
a browser, whose threads differ). No algorithm lives in an adapter — pools, descriptors, conveyance and drivers are
`eve.thread`'s — and nothing here names a host in shared code.

### 11.1 The contract — 14 functions (P§5.1; the frame pair counted as two, `eval-form` optional)

| fn | contract | blocking | memory |
|---|---|---|---|
| `(parallelism)` | the hardware threads this runtime offers | — | — |
| `(self)` | the calling thread's address: a value, `=`-comparable, sendable within the process (a thread the adapter did not start gets one on first call) | — | — |
| `(start-threads! n entry boot opts)` | start n threads, each running `((resolve entry) (assoc boot :self addr))`; their addresses. `opts`: `:kind` (`:platform` / `:virtual`), `:name`, `:requires` (the namespaces an isolated host's thread loads first). `n` 0 answers `[]` | never blocks the caller | an isolated host wires each new thread to every thread there is |
| `(send! addr msg)` | enqueue `msg` (any non-nil value) for the thread at `addr`; to oneself legal (onto one's own queue, in order); to a thread that has ended, dropped; answers `nil` | never blocks | a closure or host object only if `ships-closures?`; across heaps a message is data (EDN) and anything else is refused at the sender |
| `(receive! ms)` | this thread's next message, or `:eve.thread/timeout` after `ms`; `ms` < 0 waits for ever, 0 polls | the host's real block | — |
| `(stop-threads! addrs)` | ask each to end: the message `:eve.thread/stop`, which a thread's loop honours — a request, never a kill | — | — |
| `(ships-closures?)` | may a message carry a closure or a host reference | — | true where threads share a heap — and on the BEAM, whose funs are copyable terms |
| `(driving)` | the declared driving properties (P§5.7), the only host facts `eve.thread` reads: `:block` (`:thread` / `:cheap` / `:never`), `:parallel`, `:closures` (= `ships-closures?`), `:eval` (has `eval-form`), `:wake` (`:futex` / `:poll`: how a domain region's wait is made), `:spawn-ms` (a new thread's start, measured where it costs), `:max-threads` (nil unbounded, 0 none), `:runtime` | — | — |
| `(attempt f)` | `[true v]` or `[false e]`: the catch-all shared code cannot spell | — | — |
| `(error-data e)` | `{:class :message :data}`: the host's own exception as data | — | — |
| `(capture-frame)` / `(call-with-frame frame f)` | the host's dynamic binding frame, captured and re-established (a nil frame: `f` called as is) | — | heap-shared hosts; `nil` / identity where threads do not share a heap |
| `(var-symbol v)` | a var's qualified name | — | — |
| `(eval-form form)` *(optional)* | evaluate a form; where the runtime has no evaluator, refused loudly | — | — |

**The messages the contract itself sends.** `:eve.thread/stop` (`stop-threads!`); `:eve.thread/timeout` (what
`receive!` answers when nothing came); and the **exit notice** `{:eve.thread/exit addr :reason r}`, which the thread
that started `addr` receives when that thread's entry ends — `r` is `:normal`, or the error as `error-data`. A dead
thread is never silent (C2): JVM, CLR, Python and glojure threads report from a wrapper round the entry; a BEAM
process is monitored by its starter (`spawn_monitor`), whose `'DOWN'` `receive!` answers as the notice, so a killed
process reports too; a Node or browser worker reports itself, synchronously, when its entry returns or throws, when
its entry does not resolve, or (on Node) when it calls `process.exit` — that reaches a starter blocked in `receive!` —
and the `Worker`'s `exit` / `error` event, which runs only on the starter's event loop, is the backstop for a worker
killed from outside. (2026-10-19, lane ET-1: on Node the entry was resolved before the report was armed, so an entry
that did not resolve died silent until the starter's event loop ran — met when eve.thread's routed pools named an
entry wrongly; it is resolved inside the report now. A Node worker also marks itself ended in the doorbell after its
last send, and a thread about to wait drops its ports to ended threads, which it no longer polls on every
`receive!`. And Node joins a worker that has ended — releasing its isolate, ~75 MB each, ~210 MB with eve loaded
[measured] — only on its starter's event loop: a starter that runs synchronously keeps every worker it started until it
yields; eve's nbb runner yields between test vars for that reason.)

**Refusals** are `ex-info`s whose data carries `:eve.thread/refused`: `:host-object` (a message to another heap that
is not data), `:eval` (no evaluator), `:no-threads` (`thread/none`), `:deadlock` (a wait for ever where no other
thread exists), `:cannot-block` (a wait on a browser page's main thread).

**Code on an isolated host** (C4). A Node worker of a runtime that evals (nbb) runs nbb's CLI on the same classpath
and loads `:requires`; a worker of compiled ClojureScript loads the program itself by the later cljs-thread
lineage's fat-kernel route (P§1.8): on Node `new Worker(kernel-source, {eval: true})`, the kernel being the program
this process runs (`process.argv[1]`), its source handed down in `workerData`; in a browser a Blob bootstrap that
receives its init data and `importScripts` the kernel URL the page found from the build's `manifest.edn` (probed beside
each `<script src>`) and hands down. Either way a worker's own threads start the same way (the conformance row that
starts a grandchild). The program's entry point calls `eve.thread-host/run-thread!` first — in a thread the adapter
started it runs that thread's entry and answers true — a launcher's call, never shared code's.

### 11.2 The implementations

| adapter | hosts | a thread | its mailbox | `ships-closures?` | `:block` |
|---|---|---|---|---|---|
| `thread/jvm` | JVM, babashka, jolt (jolt answers every interop name it uses: probed) | `java.lang.Thread`, platform or virtual (`-Deve.thread.virtual=true` makes virtual the default) | `LinkedBlockingQueue` | true | `:thread` (`:cheap` virtual) |
| `thread/node` | ClojureScript on Node, nbb | a `worker_threads` worker | a `MessagePort` per pair of threads, a SharedArrayBuffer doorbell (`Atomics.wait`, `receiveMessageOnPort`); a start takes a lock word, so concurrent starters each wire to everyone | false | `:thread` |
| `thread/browser` | ClojureScript in a browser (cross-origin isolated) | a dedicated Web Worker | a ring per thread in one SharedArrayBuffer every thread holds (a browser has no synchronous port receive: a blocked worker could never read a port); no mesh to wire | false | `:thread` in a worker, `:never` on the page's main thread |
| `thread/beam` | clojerl | a BEAM process, monitored by its starter | the process's own (`erlang/send`, `receive* … after`) | true (funs are terms) | `:cheap` |
| `thread/python` | basilisp | `threading.Thread` | `queue.Queue` | true | `:thread`, `:parallel` false while the GIL is on |
| `thread/clr` | ClojureCLR | a background `System.Threading.Thread` | a queue under a monitor (`Monitor.Wait` / `Pulse`) | true | `:thread` |
| `thread/glojure` | glojure | a goroutine by `lang.AgentSubmit` (`future` without its binding conveyance: a thread starts with the root frame) | an atom holding a queue and a promise the next sender delivers | true | `:cheap` |
| `thread/none` | a host with no threads | the current thread only | its own queue | true (false on ClojureScript, which has no frame to capture) | — (`:max-threads` 0) |

The BEAM's binding frame is clojerl's own, copied with each message that carries it: in `clojure.main` it is 20
vars, 3,101 bytes, and a round trip carrying it measured 6.5 µs bare [ran] (the final pass's probe, 2026-10-22 ET:
20 vars, 3,105 bytes, 2.4 µs — `doc/release-0.9/logs-2026-10-22/f-probe-clje-costs.log`) — the spike's 7.3 ms round trip (P§6.1)
was its engine, not the frame (C3).

### 11.3 The conformance suite

`eve/test/eve/thread_host_test.cljc` — every row reads only this contract and the declared capabilities, never a
host's name: the contract's answers; a send to oneself; `receive!` with 0, n and a negative wait; start n; data and
order through a thread; stop and the exit notices; `attempt` and `error-data`; binding frames, here and on another
thread; closures in messages, or their refusal; `eval-form`, or its refusal; a thread whose entry throws and one
that dies while running (each one's notice reaches its starter); 100 round trips each carrying the captured frame
in under 100 ms (median batch of 5; the row prints its batches); and a thread that starts a thread, the grandchild
reaching its grandparent directly and its end reaching its starter. Run by every host's runner (`bb test:bb`,
`test:nbb`, `test:clje`, `test:cljr`, `test:lpy`, `test:jolt`, `test:glj`, `test:browser`, the JVM's
`clojure -M:jvm-test`, ClojureScript's `node target/eve-test/all.js thread-host` and `all`).

## 12. The memory seam: `eve.slab-io` (2026-10-17)

Every slab access in eve's shared code — a node field read, a key compare, the
allocator's header words and bitmaps — is a name of `eve.slab-io`, which an adapter
supplies (`eve/adapters/README.md`, slab-io). Before the seam each was two protocol
dispatches (`ISlabIO` on the domain's slab I/O, then `IMemRegion` on the region), and
no host could make the hot path static (L19 EVS-1, `docs/port-gap-closure/lanes/L19-eve-slab-io.md`).
The gate: `grep -rn "(-sio-\|(alloc/-sio-" eve/src | grep -v deftype_proto/alloc.cljc` is
empty — `ISlabIO`'s definition, `JvmSlabCtx` and `LoggingSlabIO` stay in alloc.

| Group | Names | What the shared code relies on |
|---|---|---|
| a domain's slab I/O | `read-u8` `read-u16` `read-i32` `read-bytes` `write-u8!` `write-u16!` `write-i32!` `write-bytes!` `cas-i32!` `copy-block!` `alloc!` `free!` `mem-region` | `ISlabIO`'s contract (alloc.cljc): an i32 at any alignment, signed; `cas-i32!` answers the value found; `free!` false on a double free; `mem-region` the `[region byte-off]` for wait and notify |
| the domain | `domain` `domain-ctx` `regions-changed!` | the slab I/O eve's values carry for a slab context (`JvmSlabCtx`, from `make-jvm-slab-ctx`), the slab context back from it (the allocator's, the refresh's), and a notice after the context re-maps a class (growth), so a representation that caches its regions follows |
| a region's words | `region-load-i32` `region-store-i32!` `region-cas-i32!` `region-add-i32!` `region-sub-i32!` `region-load-i64` `region-store-i64!` `region-cas-i64!` | §2's contracts, for the slab allocators' header words (alloc, coalesc) |
| fused primitives | `bytes-equal-at?` `bitmap-find-free` `bitmap-alloc-cas!` `bitmap-free!` | `(bytes-equal-at? sio off field b)`: the `(alength b)` bytes there equal `b` (the caller checks the length); the bitmap scan, set and clear of `eve.mem` |

Its implementations:

- **`adapters/slab-io/protocol`** — every host before ValueScript: each name a macro
  expanding to the call the shared code made before the seam (`-sio-read-i32` on the
  slab I/O, `-load-i32` on the region, `eve.mem`'s bitmap helpers; `bytes-equal-at?` the
  read and the compare it replaced; `domain` the context itself). Nothing is added at run
  time; a ClojureScript compiler takes the macros through `adapters/slab-io/cljs-compiler`.
- **`adapters/slab-io/vs`** — ValueScript: plain functions over the program's linear memory.
  A domain's slab I/O is the address of a 64-byte record (each class's block base and
  block size, and the slab context's index), so a slab-qualified offset is an address in
  two loads; a region is an address (`adapters/region/vs`); `alloc!` and `free!` call the
  shared allocator (`alloc/ctx-alloc!`, `ctx-free!`) directly. Any other slab I/O or region
  (an attempt's `LoggingSlabIO`, a test's wrapper) answers through the protocols.

Not here, by §9's rule: no HAMT, codec, registry or swap loop. A fused primitive is one
memory operation a host may do in one call; its default is what the shared code did.
`adapters/slab-io/test` is the conformance test every implementation answers (`bb test:vs`
runs it on ValueScript; any host runs it with the root added).

