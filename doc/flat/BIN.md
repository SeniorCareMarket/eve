# eve.bin — eve's binary value (2026-11-24)

Beside FORMAT-V1 §3, which gives a run of bytes its one encoding — `[0x28][len varint][bytes]` in a chunk, with eve's
two magic bytes in front everywhere else (`[EE DB 28][len varint][bytes]`: a slab block, an atom's value, a map key, the
digest's input) — this file says what that run IS when eve reads it back: an eve value of its own, `eve.bin`'s binary.

The operator, 2026-11-24: "Why doesn't eve speak in these binary values natively?" · "what if we just had an eve.bin ns
that ships with eve, and all clj*s have to do is plug in their low level handlers to the eve.bin adapters and then it'll
automatically conform to the bin handling."

## 1. What a binary is

A binary is a VIEW of a run of bytes where the run lies:

| where the run lies | what the binary holds | read through |
|---|---|---|
| a flat chunk (a file read back, a wire message, `chunk/pack`'s output) | the chunk, the run's offset and length | eve.flat-io (`u8`, `bytes=`, `bytes-cmp`) — the host's own byte handlers (FLAT-IO.md) |
| the codec's decode buffer | that buffer as a chunk (`fio/chunk`) | eve.flat-io |
| a block of an eve domain — a heap slab, an mmap'd disk domain | the slab I/O, the block, the run's offset in it | the slab's node reads (`alloc/node-u8`, `alloc/node-bytes`) |

Decoding a `0x28` scalar never copies its run: the byte-array codec's `eve-bytes->value` answers a view of the bytes it
was handed, `chunk/unpack` a view of the chunk, and a map's lookup (`eve.map`'s HAMT get) a view of the node's block. So
the path from disk to a value read is allocationless for the run.

A host conforms by the handlers it already plugs in: its flat-io root (FLAT-IO.md: the JVM's byte[], JavaScript's
Uint8Array, ValueScript's B1 binary) and its slab I/O. `eve.bin` (`src/eve/bin.cljc`) is written once, in eve's shared
code, over them; no host is taught what a binary is.

## 2. Semantics (`eve.bin`)

- `count` — the run's length; `seq` — its bytes, signed (as `(seq byte-array)`). `nth` — a byte SIGNED, -128..127, as Clojure reads a `byte[]`. `ubyte` — the same byte
  unsigned, a named function.
- `=` — by content: a binary equals another binary of the same bytes; never text, never a host byte array.
- `hash` — the HAMT's key hash (`hamt-util/portable-hash-bytes`, Murmur3_x86_32) of the binary's eve bytes
  `[EE DB 28][len varint][run]`: one string is the binary's value bytes, key bytes and hash input (§3, §8). A binary
  used as a map key keys by the same bytes.
- `compare` — unsigned lexicographic, a shorter run first on a common prefix (flat-io's `bytes-cmp` order).
- `slice` — another view of the same bytes, no copy.
- Printed `#eve/bytes "<hex>"`.

## 3. Host byte arrays: only at the edges

`of-bytes` (a host byte array in: one copy, so a later write to the array is not seen) and `->bytes` (out: one copy).
A host byte array written to eve still encodes as the `0x28` scalar (FORMAT-V1 §3's fixtures hold), and reads back as a
binary. An eve binary written to eve writes the same scalar (`scalar-bytes`), its run copied once into the writer.

## 4. Lifetime: a view must not outlive its window

A chunk view is owned: the chunk is a host value the host's GC keeps while the view is reachable. A block view is valid
while the block's version is: read under a pin, the read's cell rides on the binary (`eve.view`'s `wrap` answers
`pinned` — the read ends when the GC collects its last view, binary or collection), and inside a `swap!` the swap's own
region. On a host whose GC cannot end a read (read-end C: `eve.gc/available?` false) a block view is copied once at the
read (`own`). Any binary that escapes its window is copied once (`own`): a chunk view is returned as it is.

## 5. Layering

`eve.codec` and `eve.flat.chunk` load below `eve.bin` (eve.deftype's macros, which `eve.bin`'s type is written with,
load them), so they reach it through `eve.deftype-proto.serialize`'s binary slot (`register-bin!`, `bin?`, `bin-view`,
`bin-scalar-bytes`, `bin-run`), which `eve.bin` fills at its load. `eve.map` and `eve.view` require `eve.bin`, so any
program holding eve data has it; a decode of `0x28` before it loads throws `:eve/no-bin`, never a silent host array.

## 6. Measured, and not

MEASURED 2026-11-24 (OpenJDK 21.0.13, Clojure CLI 1.12.0.1530, `clojure -M:jvm-test`): `eve.bytes-scalar-test` (the
value's semantics; the codec, chunk, file and disk-atom hops; the persistent disk atom's `(:b @a)` is a block view —
`slab-view?` true — equal to the bytes written, and `own` copies it once), `eve.eve-bytes-fixture-test` (75 rows),
`eve.flat.chunk-test`, `build-test`, `contract-test`, `column-test`, `flat-smoke-test`, `char-key-test`,
`keyring-test`: 59 tests, 10,461 assertions, 0 failures. nbb (`bb test:nbb eve.eve-bytes-fixture-test`): 56 rows, 129
assertions, 0 failures. Not measured: shadow-cljs Node, squint (its runner does not start on eve's main), jank's codec
(no `0x28` arm). ValueScript: binary-native.md §7.2 (a binary's chunk there is a B1 binary; a VS byte-array handed to
`fio/chunk` is copied once into one until B6 retires the byte-array emulation).
