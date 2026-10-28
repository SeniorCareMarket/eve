# EVE on-disk format — the decisions the unification made

Status authority for the bytes every host writes and reads. The protocol
oracle is `atom.broker/test/coop-atom` (its round-trip and contention
scenarios run the JVM, Node, bb and the wasm host on one set of files):
`.slab0–.slab6`, `.slabN.bm`, `.root`, `.rmap` and the CAS protocol stay
byte-compatible. Every addition below lives in bytes that were zero before.
Dates are US Eastern.

## 1. The root header: the exclusive-hold words (2026-10-15)

`eve.deftype-proto.data` `ROOT_HOLD_OFFSET` 24, `ROOT_INFLIGHT_OFFSET` 28 —
two i32 words in the 64-byte `.root` header that every earlier writer left
at zero.

A host whose root region has no atomic read-modify-write takes part in the
CAS protocol through them. Measured on babashka 1.13.226 (JDK 25.0.4):
`java.lang.invoke.MethodHandles` does not resolve (so no VarHandle CAS),
`sun.misc.Unsafe` has no reachable methods, `MemorySegment/ofBuffer` does
not resolve, `AtomicIntegerArray` is absent. What bb has is `MappedByteBuffer`
plain loads and stores, `FileChannel.lock` (released by `.close`), and a
lock-prefixed `AtomicInteger.incrementAndGet` that serves as a full fence.

* A **lock-free host** (VarHandle, Node's native addon, Atomics) brackets
  every write to the domain — a swap attempt, a worker-slot claim, an atom
  slot claim — with `-add-i32!` on `ROOT_INFLIGHT` before and `-sub-i32!`
  after. Having added, it loads `ROOT_HOLD`; if the hold is set it subtracts
  again, waits for the hold to clear, and retries.
* A **lock-only host** takes an exclusive byte-range file lock on the four
  bytes at `ROOT_HOLD` (serialising against its own kind; POSIX record locks
  are per process, so an in-process lock guards its own threads), stores
  `1 + its worker slot` into `ROOT_HOLD` (the store is fenced), waits until
  `ROOT_INFLIGHT` reads 0, runs its whole write with plain loads and
  stores, and stores 0. A joining participant that has no slot yet holds
  with `HOLD_JOINING` (257).
* Why it is correct: both sides write their word with a full fence before
  reading the other's, so of any lock-free write and any held write, at
  least one sees the other and yields; the lock-free side yields by
  subtracting and waiting, so the two never wait on each other.
* A dead holder: a hold whose holder's heartbeat is `HEARTBEAT_TIMEOUT_MS`
  (30 s) old, or a joining hold watched that long, is cleared by the waiter
  with a CAS.
* Cost to a lock-free host: two atomic adds and one load per write — on
  the `.root` header line, which the epoch counter already shares.
* The gap: a participant that implements neither word races a lock-only
  participant and nothing else. At the time of writing `vs.eve-disk` (the
  wasm host) is such a participant; it must add the two words to share a
  domain with bb. It is already safe with the JVM and Node, whose root CAS
  is unchanged.

MEASURED 2026-10-15: `atom.broker/test/coop-atom` `contention-bb-only`
(4 bb processes) and `contention-eve-only` (2 JVM threads + 2 Node + 2 bb
processes), unpatched eve: 0 failures; see UNIFICATION-REPORT.md for the
tally.

## 2. Flat collections: tags 0xED, 0xEF and 0xEE, in canonical order

A value that is a collection is written either as a pointer to a slab
collection (tags 0x10 map, 0x11 set, 0x12 vector, 0x13 list, 0x1C array) or
flat, self-contained in its bytes:

    FLAT_MAP  [0xEE 0xDB 0xED][count:i32LE] ([k-len:i32LE][k-bytes][v-len:i32LE][v-bytes])*
    FLAT_SET  [0xEE 0xDB 0xEE][count:i32LE] ([e-len:i32LE][e-bytes])*
    FLAT_VEC  [0xEE 0xDB 0xEF][count:i32LE] ([e-len:i32LE][e-bytes])*

The flat tags are the header type-ids of the slab collections they mirror
(`EveHashMap-type-id` 0xED, `EveHashSet-type-id` 0xEE), with 0xEF for a
vector — the format's own history: the JVM codec (`eve.mem`) has written
sets as 0xEE since its flat encoding existed (`TAG-FLAT-SET`), and
`vs.eve-disk` writes 0xEE. The CLJS codec (`eve.deftype-proto.serialize`)
wrote a set inside a flat collection as a 0xEF vector and could not read
0xEE at all (round 2, K1: Node read a set key as nil). That was a gap in
the CLJS codec, not a second design; the asymmetry is fixed on the CLJS
side.

**Canonical order.** A flat set's elements are written in ascending order
of their encoded bytes, and a flat map's entries in ascending order of their
encoded key bytes — unsigned, lexicographic, a shorter prefix first
(`eve.deftype-proto.data/compare-bytes`, one definition for every host). Two writers that encode the same value produce the
same bytes, so the same key bytes and the same hash, on every host. Before
this, each writer used its own iteration order (round 2, K3: the JVM wrote
`#{1 2 3}` as `1 3 2`, VS as `2 3 1`, and neither could find the other's
set key). A reader accepts any order.

## 3. Keys: a collection used as a key is flat on every host

A HAMT entry's key bytes are what the map hashes (`portable-hash-bytes`,
Murmur3_x86_32 over the bytes) and compares. They must therefore be a pure
function of the key's value. The JVM and bb codec encoded a collection key
flat; the CLJS codec encoded it as a slab pointer, so a Node-written vector
key could be found by nobody, Node included (round 2, K1). Keys are flat on
every host now; a collection used as a value is still a pointer (lazy,
shared, path-copied).

A set's elements are keys in the same sense: the set hashes and compares
their bytes. The CLJS codec encoded them through the value encoder (a
nested collection became a pointer) and so did the JVM codec (`eve.set`
called `value+sio->eve-bytes`), so `#{[1 2]}` could not find its own element
on either host. Fixed on both: `eve.set` encodes elements with the key
encoder (`serialize-key` / `value->eve-bytes`) on every host.

## 4. Scalars (unchanged, now pinned by fixtures)

    0x01 false · 0x02 true · 0x03 int32 LE · 0x04 float64 LE · 0x0F int64 LE
    0x05 string ≤255 bytes [len:u8][utf-8] · 0x06 string [len:i32LE][utf-8]
    0x07/0x08 keyword short/long · 0x09/0x0A ns-keyword short/long
    0x0B UUID (16 bytes, big-endian) · 0x0C symbol · 0x0D ns-symbol
    0x0E date (float64 ms) · 0x14 character [utf16:u16LE] (2026-10-16: the
    JVM had no encoding for one; the fixture row `\a` pins it on the JVM, bb
    and VS's eve participant, which reads and writes it too.
    2026-10-23, John's ruling on D7: a char is a distinct scalar on every
    host, and char and string never normalize into each other over round
    trips. Text denotes a char with the tagged literal #char "a": the JVM
    reads a java.lang.Character; ClojureScript, which has no char type,
    reads the codec's EveChar — a value equal only to a char of the same
    code, hashed by the HAMT hash of the char's key bytes, and printing as
    #char "a", so read ∘ print holds there too. ClojureScript's \a stays
    the one-character string "a", and a char key and the string of the
    same code are distinct keys in one map. The fixture row for #char "a"
    carries the same bytes as the `\a` row and runs on ClojureScript too)

A symbol's name and namespace each carry a one-byte length (tags 0x0C/0x0D
have no long form): a segment past 255 UTF-8 bytes has no encoding, and both
codecs refuse it (they wrote `len mod 256` before — corrupt bytes on every
host, seam map L-40). The fixture suite pins the refusal.

An integer in `[-2^31, 2^31)` is int32, else int64. A CLJS number is a
double: an integral double and `-0.0` go out as int32 and an integer past
±2^53 as float64 (round 2, C2) — that is the host's number model, not the
format; the fixtures pin the bytes each host must write for the values it
can represent.

Atom names in the registry are hashed with FNV-1a over the name's UTF-16
code units masked to 8 bits (`eve.atom/fnv-1a-hash`) on every host; the
wasm host must match. For ASCII names this is FNV-1a over the bytes.

## 5. The fixture suite

`eve/test/eve/fixtures/eve-bytes.edn` holds `{:value v :bytes "hex" :note
…}` rows: the value and the bytes every host must write for it (and read
back). `eve.eve-bytes-fixture-test` (a `.cljc`) runs them on the JVM
(`clojure -M:jvm-test`), on bb (`bb -cp src:<hosts.edn :bb roots>:test -e "(require
'eve.eve-bytes-fixture-test) (clojure.test/run-tests
'eve.eve-bytes-fixture-test)"`) and on Node (`node target/eve-test/all.js
fixtures`). A host that cannot represent a value (bb/JVM have no typed
arrays at the codec level; CLJS has no int64 past 2^53) skips that row by
its `:hosts` set, and the skip is counted and printed.

## 6. The HAMT trie index: bits of the hash's 32-bit pattern (2026-10-16)

A map or set node at shift `s` indexes its children by `mask-hash`: bits
`[s, s+5)` of the key hash's **32-bit pattern**, so the last level (shift 30)
has two bits — slots 0–3 — on every host (`eve.hamt-util/mask-hash`, pinned
by `test/eve/hamt_util_test.cljc` on the JVM, bb and Node).

Measured before this decision (box 3, `ports/` at `1fb9cbca`): the `:clj` arm shifted the
hash as a sign-extended long, so at shift 30 a negative hash read three
sign bits as well. `(mask-hash -430950298 30)` was 31 on the JVM and bb and
3 on the CLJS arm (nbb) and in VS's eve participant (`vs.eve-disk`, whose
namespace compiles under 32-bit bit rows; measured 2026-10-16, pinned by
`vs/test/vs/eve_disk_test.vs`). The wasm kernel's own HAMT
(`vs/src/vs/kernel/wasm/hamt.vs`) indexes a 16-byte digest, not this hash.
Two keys reach a shift-30 node when their hashes share their low 30 bits —
the int keys 371 and 53312 do (hashes 642791526 and -430950298) — and a
map `{371 :A 53312 :B}` written by the JVM answered `nil` for 53312 when
read with the CLJS arm's `mask-hash` (simulated on the JVM: the CLJS build
cannot be compiled on that box). By the birthday bound a map of about
46 000 keys holds such a pair on average. Every shallower level (shift
0–25) was already identical on every host.

**Decision:** the 32-bit pattern, which is what CLJS and VS's participant
already do and what a 32-bit hash means. The `:clj` arm masks the hash to
32 bits before shifting.

**Compatibility:** a JVM- or bb-written map or set that holds a shift-30
node with a negative-hash key, written before this change, keeps that key 28
slots up (30/31 for 2/3). Every host reads, replaces and removes it there:
`eve.hamt-util/node-bit` resolves the bit in an existing node — the 32-bit
pattern's slot, or, at shift 30 for a negative hash when that slot is empty
and the one 28 up is set, that one — and a new key takes the pattern's slot.
CLJS, the JVM and bb use it through `map.cljc` and `set.cljc`, VS's
`vs.eve-disk` has its own `node-bit`, and the Clojerl port carries it.
Measured 2026-10-16 with the pre-fix `:clj` arm writing `{371 :a 53312 :b}`:
without `node-bit` the JVM's `get` of 53312 was `nil`, `conj` added a second
53312 to a set, and VS's `get-in*` answered `nil` while its `assoc-in!` left
a map of header count 3 holding 53312 twice; with it, every read finds the
key and every write keeps one entry (`test/eve/jvm_legacy_index_test.clj`,
the `node-bit` rows of `hamt_util_test.cljc` and of
`vs/test/vs/eve_disk_test.vs`). Nothing else moves: hashes are unchanged
(the 54 fixture rows pass), and every node at shift 0–25 is laid out as
before.

## 7. eve-deftype type ids: from the type's name (2026-10-16)

An eve-deftype instance's block starts `[type-id:u8][name-hash:u24 LE]`,
and the type-id byte is also the pointer tag that names the type inside a
serialized value (`[EE DB type-id offset:i32]`). Both come from the type's
namespace-qualified name (`"my.ns/MyType"`), through `hash`, which Clojure
and ClojureScript define alike for a string (measured 2026-10-16: the same
values on the JVM, bb and nbb):

    type-id    0x40 + (hash key) mod 173      0x40–0xEC
    name-hash  (hash key) & 0xFFFFFF          header bytes 1–3

`eve.deftype.registry/type-id-of` and `name-hash-of`. Before this, type ids
counted up from 64 in load order, so a process that loaded its types in a
different order, or loaded different types, gave the same type a different
id. `eve.deftype.type-id-test` asserts the ids as literals on every host.

173 values are few: two types can share a type-id (the test's Grape and
Apricot do). When a value is read back, the shared type-id's constructor
reads the block's name hash and calls that type's
(`eve.deftype-proto.serialize/register-deftype-constructor!`). A type-id
held by one type in the process skips that read. Two types in one process
that share both the type-id and the name hash are refused at expansion
(`claim-type-id!`).

0x00–0x3F stay eve's pointer and fast tags, 0xED–0xEF the HAMT map and set
headers and the flat vector, and 0xF0–0xFF are reserved (§9: a runtime's
process-private block kinds). Header bytes 1–3 were padding before this
change.

## 8. The numbers past a fixed width: BIGINT 0x16, RATIO 0x17, BIGDEC 0x18 (2026-10-18)

EM5's EVM-11b (`docs/eve-migration/lanes/EM5-eve-coverage.md`): VS's values
include big integers, ratios and big decimals, and eve had no bytes for them
(the JVM codec threw `unsupported type`; ClojureScript's wrote empty bytes, a
value read back as `nil`). Three tags, each a format fact pinned by
`eve-bytes.edn` rows:

    BIGINT  [0xEE 0xDB 0x16][len:u32LE][two's complement, big-endian, minimal]
    RATIO   [0xEE 0xDB 0x17][len:u32LE][numerator][len:u32LE][denominator]
    BIGDEC  [0xEE 0xDB 0x18][scale:i32LE][len:u32LE][unscaled]

A big integer's bytes are those of `java.math.BigInteger/toByteArray`:
two's complement, most significant byte first, the fewest bytes that hold the
sign (0 is `[00]`, 128 is `[00 80]`, -129 is `[ff 7f]`).

**Canonical by value, because a key's bytes are a function of its value (§3).**
The rule is Clojure's own equality, measured on the JVM 2026-10-18:
`(= 5 5N)` with one hash, `(= 1.5M 1.50M)` with one hash, `(= 0M 0.0M)`; but
`(= 1 1M)` false, `(= 1/2 0.5)` false. So:

* An integer is written by its magnitude, whatever its host type: INT32 in
  `[-2^31, 2^31)`, INT64 in `[-2^63, 2^63)`, BIGINT past that. `5N` is
  `eedb0305000000`, the bytes of `5`; it reads back as the host's fixed-width
  integer. A BIGINT reads back as the host's big integer (`BigInt` on the JVM
  and bb, `int` on basilisp, `BigInt` in JavaScript).
* A ratio is in lowest terms with a positive denominator (Clojure keeps it so;
  a ratio whose denominator is 1 is an integer and never a RATIO).
* A decimal's trailing zeros are stripped down to scale 0, and a negative
  scale is raised to 0: `1.50M` writes `1.5M`'s bytes, `1E+2M` writes
  `100M`'s, zero is unscaled 0 at scale 0.

**Hosts.** The bytes are laid out by the codecs; what a host's numbers are is
its `eve.encoding` adapter's (`big-integer?`, `integer->twos`,
`twos->integer`, `ratio?`, `ratio-parts`, `make-ratio`, `bigdec?`,
`bigdec-parts`, `make-bigdec`), so shared code names no host. The JVM, bb and
jolt have all three (`clojure.lang.BigInt` / `java.math.BigInteger`,
`clojure.lang.Ratio`, `java.math.BigDecimal`); basilisp has all three (`int`
past 64 bits, `fractions.Fraction`, `decimal.Decimal`); ClojureCLR's adapter
has all three (written, not run on this box); clojerl has big integers only.
ClojureScript writes and reads a JavaScript `BigInt` by the same magnitude
rule and has no ratio or decimal. A host without the number refuses its bytes
loudly on read — never a double — and its rows are skipped by `:hosts`
(counted). `eve.net.codec` (the wire, the kernel's own format) still refuses
all three (`:unencodable`), as `vs.eve-disk` does: neither is this format.

## 9. References: tag 0x15, and 0xF0–0xFF as process-private block kinds (2026-10-18)

EM5's EVM-11c / EVM-11c′. A value eve has no bytes for — a fn or closure, a
regex, a var, a volatile, an atom, a host object — is stored **by reference**:

    REF  [0xEE 0xDB 0x15][realm:u32LE][handle:u32LE]

The handle names the value in this process's reference table
(`eve.deftype-proto.serialize`: `ref->handle!`, `handle->ref`); one object is
one handle, looked up by identity under the host's identity hash
(`eve.process/identity-hash`: a host object may answer no hash of its own —
basilisp's volatile — or an equality of its own). The realm is 32 random bits the process draws once
(`eve.process/random-bytes`), so another process's handles are refused, never
misread as this one's; a handle this process never issued is refused too. A
reference reads back as the same object, by identity, in the process that
wrote it — a sorted map's comparator (EVM-11a) rides this way. The bytes are
process-local by definition, so no fixture pins them; the layout is pinned by
`eve.reference-tag-test`. A referenced value lives as long as the process: eve
memory may hold its handle anywhere, and nothing can know when the last copy
goes.

**Never in a shared domain (C3).** A shared domain is one whose files another
process maps — an mmap domain (`eve.deftype-proto.alloc/shared-domain?`: a
`JvmSlabCtx` with file paths; ClojureScript's module slab while it holds an
mmap domain's files). Writing a value that holds a reference into one throws,
in every position: a value written with that domain's slab I/O, a key or
element at any depth of a host collection written there (the codec binds
`*shared-write*` around that write, and the key encoder, which takes no slab
I/O, refuses through it), and a reference encoded while the running `swap!`'s
domain (`d/*domain-sio*`) is shared. `eve.net.codec` refuses a reference as it
refuses anything without an image. Before this, the JVM codec threw for these
values everywhere and ClojureScript's wrote empty bytes (a value silently
read back as `nil`).

**0xF0–0xFF as the first byte of a slab block** are reserved for a runtime's
own block kinds (VS's: its closures' captures, its argument vectors —
`process-private-kind?`), never written to a shared domain. eve assigns none:
its type ids are 0x40–0xEC (§7), its headers end at 0xEF.

## 10. Big values out of line: tag 0x19 (2026-10-18)

EM5's EVM-13, decided by row R-6 (measured 2026-10-18; `docs/eve-migration/PERFORMANCE.md` R-6, the lane's
`.work/status.md`): an `assoc` into a 500-key map whose values are 1 KB strings costs eve 1.52× its small-value
figure on the JVM (a replace 2.31×) — every path copy copies every sibling's inline bytes — while the kernel's is
flat (~80 µs either way: its values are out of line), and eve on VS cannot run the 1 KB rows at all. So a map
entry's value whose encoding is longer than 64 bytes is written **out of line**:

    VALUE_BLOCK  [0xEE 0xDB 0x19][block:i32LE][len:u32LE]   → a scalar block [0x01][EVE bytes, len of them]

The node holds the 11-byte pointer; a path copy copies it, never the value. The block is an ordinary scalar block
(type-id 0x01, the layout an atom's scalar root already has); the pointer carries the length, so a block of the
overflow class (whose size the slab header does not give) reads back exactly. 64 bytes keeps a short string, a
keyword, a number and a small flat collection inline (a read with no second block). Keys stay inline: their bytes
are what the HAMT hashes and compares. The threshold is a format fact — every host decides it the same way, on the
same canonical encoding.

**Reclamation is the walk's, and it is not here yet.** A superseded value block is freed by the retire walk's
data-value arm — VSv1's "a scalar value block that changed is a superseded leaf" (`vs/docs/memory-model.md`
§THE WALK) — which eve's walk lacks (EVM-1, EM2's lane: the diff functions are EM2's files). Until that arm exists
the writer is not wired into `encode-val`, because every overwrite of a big value would leave its block behind; the
codecs read 0x19 on every host, and the writer is `eve.codec/encode-val-out-of-line`. EM2's arm and the one-line
switch of `encode-val` to it land together.

## 11. The tally: the count on the block's descriptor row (2026-10-22)

RELEASE-CHECKLIST 9.31, the operator's tally rule: every holder of a block is counted, and a block is freed only
when its count reaches zero. The count lives on the block itself — the operator's instruction, confirmed
2026-10-22: the descriptor table has a region showing whether a block was used; inc/dec how many members
(holders) it has there; at zero it's unused and freed. This supersedes the count-pages design this section first
recorded (a directory rooted at byte 32 of the `.root` header, 512 u16 counts to a page): that word is retired
with it — reserved, never written, and a domain whose header carries one has it ignored. `eve.tally`; the memory
model's statement is `vs/docs/reclaim/atom-domains.md` §0.

* **Where.** Every block of a bitmap class has a descriptor: eve's 28-byte row, the same row as class 6's
  coalescing table and the ValueScript kernel's. The count is the u32 at +8 (the row's DATA_LENGTH word, which
  nothing read): 0 unused, −1 held forever (a permanent root's), −2 claimed by a flush, −3 saturated (kept from
  then on). The count's flags ride the LOCK_OWNER word at +20: bit 0 is the coalescer's lock, bits 1–8 the
  count's. Every change is one CAS on one word of the block's own row; a free resets the freed block's row.
  Heap and SAB slabs carry the table between the bitmap and the data; an mmap domain carries it in a
  `{base}.slabN.desc` file beside the `.bm` (a domain written before the tables opens with a virgin, all-zero
  one). Class 6's rows are its coalescing descriptors.
* **Who counts.** A store counts what it stores (`eve.tally` hold!/drop!/store!): an atom slot holds its new root
  before the CAS that publishes it and lets the old one go after; a reference an eve-deftype writes in place is
  counted by the store rule (the new reference held, then the old let go); every block a dropped value held is
  let go in turn. A hold descends only through blocks it newly counts — reaching a parent node you walked the
  branch, so you know it's not at 0 members. Same-domain references are tallied; a cross-domain plain reference
  is an error (`$` materializes a crossing).
* **Freeing.** Never on the spot: a block whose count reaches 0 is parked on its domain's zero queue under the
  epoch, and the flush frees it once no pinned reader can see it, claiming it by CAS (0 → −2) so a hold racing
  in is refused.
* **The gap.** A participant that does not read counts frees a piece another place still holds when it drops one
  of its places. A domain shared with one must not hold a piece at two places. (The wasm participant counts
  through `eve.tally` — `eve_disk.vs`'s hand-copy walk is deleted.)


## 12. The atom slot table grows: root words 20 and 36 (2026-10-24)

The operator's ruling (2026-10-24, `claude/l24-scope-atoms`): "at least tens of thousands … the more the better … make
that so and ensure it's fully scalable." The named-atom registry at slot 0 was already a map (name → slot, path-copied
under CAS, unbounded); what bounded a domain to 256 atoms was the slot table: 256 × 8-byte slots laid out in the
`.root` header (`ATOM_TABLE` 6272, `MAX_ATOM_SLOTS` / `MAX_ATOMS` 256, the claim throwing "no free atom slot").

* **Where.** Slot *i* lives at `ATOM_TABLE + 8 i` for every *i*: the 256 the header holds are followed, past
  `ROOT_BYTES` (8320), by as many as the `.root` file holds. Nothing else in the file moves.
* **Root word 20** (`ROOT_ATOM_TABLE_CAPACITY`, written 256 by every earlier writer) is the capacity: the number of
  slots the file holds. A claim that finds every slot below it taken doubles it by CAS (cap → 2 cap); the writer whose
  CAS won extends the file to `ATOM_TABLE + 8 · 2 cap` bytes; a writer whose CAS lost, and any participant that reads a
  capacity past its file's size, extends to the word's value. The bytes a file gains are zero, and a **zero ptr word is
  a slot never claimed: free** (NIL −1 is an atom holding nil, CLAIMED −2 a claim in flight). A domain written before
  this decision reads as capacity 256 and grows on its first claim past it.
* **Root word 36** is the next-free hint: a claim starts its scan there and stores *i* + 1 after claiming *i*. It is a
  hint, never a bound: a stale value only costs a scan, and 0 reads as 1 (slot 0 is the registry).
* **Bounds.** The format's bound is the file system's (the on-disk domain's 4 PiB: 2^49 slots). Each participant maps
  the root through a window: eve-disk's is `VS_EVE_ROOT_MB` (1 MiB by default, 130,000 slots; a claim or a read past
  the window throws, naming the variable), as a class's is `VS_EVE_WINDOW_MB`.
* **Who.** `vs/src/vs/eve_disk.vs` (`claim-atom-slot!`, `grow-slots!`, `ensure-root-capacity!`, `slot-capacity`) and
  `eve/src/eve/atom.cljc` / `deftype_proto/data.cljc` (`claim-atom-slot!`, `init`) — the same words, the same
  formula; `test/coop-atom` is where the participants prove they agree.
