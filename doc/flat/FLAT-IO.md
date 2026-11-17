# eve.flat-io — the contract (Phase 0, 2026-11-17)

The byte-level seam under eve's flat layer (eve src/eve/flat/*). ONE namespace, `eve.flat-io`, implemented once per
host root, the way every eve adapter is (adapters/README.md: same namespace, a different file on each host's
classpath). Eve's shared flat code requires `[eve.flat-io :as fio]` and reaches bytes through nothing else.

| root | hosts | role |
|---|---|---|
| adapters/flat-io/portable/src/eve/flat_io.cljc | every host without a fast one (bb, glojure, jank, ClojureCLR, …) | inbuilt: chunk.cljc's byte layer as it stands, moved (its :clj / :bb / :glj / :jank arms) |
| adapters/flat-io/jvm/src/eve/flat_io.clj | JVM (and jolt) | fast: byteArrayViewVarHandle little-endian loads, Arrays.mismatch / compareUnsigned, no reflection |
| adapters/flat-io/js/src/eve/flat_io.cljs | ClojureScript, nbb, the browser | fast: Uint8Array + DataView, TextDecoder, typed-array tuples |
| adapters/flat-io/vs/src/eve/flat_io.vs | ValueScript | fast: a chunk is a VS string leaf; reads are mmap loads at its payload (vs.core/rt-blob-off); the writer is a leaf written in place (R7's __row-flat); tuples are VS rows |

Every implementation answers byte for byte what flat-io/portable answers: the conformance test
(adapters/flat-io/test, U2a) holds it, and a fast root that implements part of the contract may delegate the rest by
name to the portable namespace's functions (adopter-tiers §4).

## The functions

All offsets are absolute byte offsets from the chunk's start; all multi-byte reads are little-endian (FORMAT-V1 §2).

**Chunks**
- `(chunk bs)` → the host's chunk over host byte array `bs` (JVM: `bs` itself; JS: an object carrying the Uint8Array
  and a DataView over it; VS: a string leaf). `(chunk-bytes c)` → the host byte array back. `(chunk-len c)` → bytes.

**Reads** (exact; never boxed where the host can avoid it)
- `(u8 c i) (u16 c i) (u32 c i) (i32 c i) (f32 c i) (f64 c i)` → numbers.
- `(i64 c i)` → the host's exact 64-bit integer: a long on the JVM, a js/BigInt on ClojureScript (chunk.cljc's `ri64`
  today), a VS long.
- `(varint c i)` → `[value next]` (unsigned LEB128); `(varint-v c i)` and `(varint-next c i)` the same without the
  vector (hot paths). `(varint-len v)` → its byte count.
- `(utf8 c off n)` → the host string of the n bytes at off.

**In place** (no copy of the stored bytes)
- `(bytes= c off n bs boff)` → are the n bytes at off equal to host bytes bs from boff.
- `(bytes-cmp c off n bs boff bn)` → -1 / 0 / 1, unsigned lexicographic, shorter first on a common prefix.
- `(key-cmp c off n k)` → -1 / 0 / 1: the string key stored as utf-8 at [off, off+n) against host string `k` in eve's
  key-byte order (the order of eve.codec/encode-key's bytes: a string below 256 utf-8 bytes is [0x05][len][bytes], from
  256 [0x06][len u32 LE][bytes] — so length class, then length, then bytes). What a KMAP / key-COL binary search
  calls; the open eve item "in-place key compare".

**The writer** (FORMAT-V1's children-first writer; today chunk.cljc's three `Buf` deftypes and `IBuf`)
- `(buf width)` → a growable writer; `width` 2 or 4, the chunk's offset width.
- `(buf-pos w)` `(buf-seek w p)` `(buf-u8 w v)` `(buf-u16 w v)` `(buf-u32 w v)` `(buf-i64 w v)` `(buf-f64 w v)`
  `(buf-varint w v)` `(buf-bytes w src off n)` `(buf-off w v)` (an offset of the writer's width)
  `(buf-patch-u32 w at v)` `(buf-align w a)` (zero bytes up to a multiple of a) `(buf-raw w)` (the host bytes so far).
- `(buf-chunk w n)` → the chunk of the first n bytes (JVM: a copy of exactly n; VS: the leaf).

**Tuples and columns** (the operator's "low level primitives like tuples")
- `(tuple c data-off n kind)` → a value that IS the host's vector of the n elements of column kind `kind` (FORMAT-V1
  §5: 1 u8 · 2 i8 · 4 i16 · 5 u16 · 6 i32 · 7 u32 · 8 f32 · 9 f64 · 10 i64 · 11 u64) stored at data-off — count, nth,
  get, seq, reduce, =, hash and print as that vector, no copy made. A write (assoc, conj, …) answers the host's own
  persistent vector.
- `(col c data-off n kind)` → the same over a column (it may be the same type).

**Not in the seam**: the format's own logic (headers, nodes, tables, cursors, the handles, the digest walk) — that is
eve's shared code, written once over these functions.
