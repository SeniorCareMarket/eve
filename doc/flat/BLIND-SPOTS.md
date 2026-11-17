# D3 — the questions we had not asked

Each of the research plan's twelve, answered from the pin — or carried as a fork with its options priced. Then the
ValueScript aside (O10), assessed. Evidence is cited by `ASSUMPTIONS.md` row (A*n*, C*n*), `MEASUREMENTS.md` section
(M*n*) and census file. Pins and tools: as in `ASSUMPTIONS.md`.

## Q1 — logical or physical packing?

Both, by job; per type the answer is the same, because the choice is not about the type.

| | physical (the image) | logical (packed) |
|---|---|---|
| What happens to a HAMT, a trie, a treap | kept: the nodes are copied as they are | discarded: values in key or index order, re-found by binary search or shape |
| Bytes | 2.6–7× EDN; 142 B a datom for a DataScript DB | 0.55–0.86× Transit MessagePack; 17–19 B a datom |
| Out of a slab | 0.4–12 ms where `eve->clj` takes 3–44 ms (M5, M6) | an encoder walk; not built |
| Into a slab | 3–45× a plain `swap!` (M6) | one block per chunk if it stays a chunk; a full build if it must become a HAMT |
| New reader code | none on the ISlabIO hosts | all of it |
| Works for a type the process cannot name | yes, as bytes; no, to read (the block carries 32 bits of name) | as a generic record (A11) |

So `detach` (leaving a domain) is physical and `pack` (to the wire, to storage, to columns) is logical, for every
type. The one per-type fact: a type whose slab form is already dense — an eve array, a Dataset's columns, a tensor's
data — is the same bytes in both (a column *is* the array's element bytes), so for those `pack` is a copy.
*Shared-heavy structure is priced in Q2.*

## Q2 — sharing and dedup across values

Measured (M7): one 1,000-element vector held at k places of a map. k = 100: the image 19,380 B; the flat codec
1,102,197 B; EDN 389,790 B; `eve->clj` builds 100 separate host vectors. Every logical form in Eve today multiplies;
the image does not, because it copies a block once and points at it.

Decision: the dictionary keys on **both**. Scalars are keyed by value (their bytes) — that is the symbol table.
Nodes are keyed by identity — the source block's offset when packing a slab value, object identity when packing
host data — and referenced by offset, which the packed form's node addressing allows inside a chunk and REF allows
across chunks (`FORMAT-V1.md` §4). Sharing that exists only across *versions* of a value (path copies) is not a
property of one value and is not carried.

## Q3 — when a chunk seals; values past one chunk

- **Open** exists only inside a builder, in host memory private to one thread. **Sealed** = handed to anyone: a
  wire, a file, an atom, another thread. Sealing is the builder's last act; nothing reopens a sealed chunk.
- **Appending to a sealed value** makes a new chunk that points back into the old ones (REF) and carries the new
  root; the old `(chunks, root)` still names the old value. Inside a builder with room, an append is new nodes and
  a new root in the same chunk (children first, root last — `FORMAT-V1.md` §4). The image appends by bumping its
  tail (M1).
- **A value past one chunk** is chunks and a directory chunk: a vector by index ranges; a map or set by key ranges;
  a sorted batch by key ranges with each chunk's first and last key. A reader finds the chunk by binary search in
  the directory and never opens the others. This is the plan's fourth phase; v1 defines the REF node and the
  directory kind and nothing more.
- **One scalar past a chunk** (a long string, a blob) makes one WIDE chunk, to 2^32 − 1 bytes. Against today: the
  slab stores such a value as one overflow-class block of any size; the kernel's allocator serves up to 960 KiB
  from class 15 and beyond that claims raw regions it never frees; `eve.net.pages` refuses any block past 10,240 B
  (`census/VS-KERNEL.md` §1.3; `census/BYTE-PATHS.md` §4.4). And one defect found on the way: an atom whose whole
  value is a string past 1,016 bytes reads back `nil` (ran: JVM, `4a92129`, fresh heap and disk domains —
  `census/BYTE-PATHS.md` §1.6 P-1; the scalar block carries no length and the overflow class answers size 1).

## Q4 — append concurrency

The contract the design needs is the smallest one: **one thread appends, and only before sealing; sealed bytes are
never written again.** Then sealed chunks need no synchronization at all — any number of threads or workers read
one SharedArrayBuffer or mapping freely — and nothing in the design shares an open tail. A second appender is not a
concurrency problem to solve: it is a second value. Publishing "the newest root" to others is state, and state
lives in an atom (Q8). The prototype view refuses allocation but not writes (M1); the product has no write path on
a view.

## Q5 — format evolution

Designed now (`FORMAT-V1.md` §11): a magic that never changes; a version a reader refuses when it is higher than
its own; flag bits split into optional (skippable) and required (refuse if unknown); reserved tags refused; types
evolving by fingerprint with a generic record as the floor; golden bytes under the existing fixture discipline. A
v1 reader given a v2 chunk refuses by name; a v2 reader reads v1. The precedent that this must be decided first:
Vortex moved its magic twice before declaring it constant; Lance and Nimble still ship unstable versions
(`research/FORMATS.md` A1g, A2, A3).

## Q6 — untrusted bytes

What Eve does today, plainly (`census/BYTE-PATHS.md` Part 6, `-2` §3): slab bytes are trusted outright; the
byte-array codec does not look at the magic, ignores trailing bytes, follows lengths as read; nesting has no limit
and the failure is a `StackOverflowError` at a few hundred to a few thousand levels; a hostile length is an
`OutOfMemoryError`. The network layer is sound where it is content-addressed — every fetched block is hashed
against its key and re-encoded to check it is canonical — and loose around that (the root blob is taken on the
broker's word; the gateway reads writers through the unchecked decoder; JSON is parsed before the bearer is
checked). `SECURITY.md` has no section on malformed bytes.

What a static reader checks before any protocol operation trusts the bytes: `FORMAT-V1.md` §10 — one pass, a typed
refusal, never a host `Error`; and a fixture file of malformed chunks, which no Eve byte form has today.

## Q7 — metadata, printing, reading back, and the API's shape

- **Metadata.** No materialization path preserves it today and the four core collections drop it at `with-meta`
  (`census/TYPES-AND-DEREF.md` §2.6). Sorted collections, the int-map and the RRB vector keep it in a serialized
  field, so an image keeps theirs. The packed form has a META node; what it can carry is bounded by what Eve keeps.
- **Printing.** A static prints as its value, as Eve's types do (`{:a 1}`), not as a handle.
- **EDN text.** A static round-trips through EDN exactly as far as its value does; the form is not in the text.
- **API shape — recommended: both layers, small.** Value functions, because the in-memory role is values:
  `detach`, `pack`, `bytes`, `open`, `open-checked`, `place`. And a Transit-shaped writer / reader pair, because the
  wire role is a stream with handlers: `(writer out opts)`, `(write w v)`, `(reader in opts)`, `(read r)`, with
  `:handlers` keyed by type key and a default that answers the generic record. One without the other fails a job:
  value functions alone cannot stream or extend; a writer / reader alone makes every in-process hand-off pay for a
  stream.

## Q8 — the memory-model law

The line: **a static is a value; it holds no state, and nothing in Eve holds state about it.** Concretely —

| Thing | Verdict |
|---|---|
| The bytes of a sealed static, anywhere | a value. No epoch, no count, no registry |
| A hash or digest remembered on the host handle | a property of the value, per handle; not Eve state |
| A registry of type readers keyed by type key | code, like the constructor registry today |
| An open builder | one thread's scratch until sealed: never shared, never published, thrown away — the four conditions of the 2026-10-29 ruling, read as the kernel already reads them for its own per-thread memos (`census/VS-KERNEL-2.md` §3.6) |
| A registry of live buffers; a cache keyed by buffer; a shared mutable tail; "the newest root of this static" | **banned** — that is state outside an atom. The design contains none |
| A static stored in an atom | becomes blocks of that atom's domain (placed), or one leaf block the domain's own reclamation counts and frees (a chunk). Never a pointer out of the domain: FORMAT-DECISIONS §11 forbids a plain cross-domain reference and nothing would reclaim it |
| A chunk as a leaf block in an atom, read in place by Eve's own types | the one place rule 26 ("no out-of-band stand-ins") could be read either way — fork F3. Such a chunk must be **closed**: it names no block of the domain, because the tally does not descend into a leaf and anything reachable only through it would be freed under it (`census/VS-KERNEL-2.md` §3.7 (b), the same on canonical Eve). The case for in-band: it has a block header and type id, it is counted, freed, digested and pointed at like an eve array block, and Eve reads inside it natively; it stands in for nothing |

On the kernel the same line has a sharper edge, found by reading: a region registered as an adopted or block page
**can be evicted and reused while an atom's slot still names a block in it**, once no compiled form binds a value
there — the hold a stored value was to get is described in comments and absent from the code
(`census/VS-KERNEL-2.md` §3.7 (e)). Any design that keeps atom state in adopted pages inherits that.

## Q9 — the flagship shapes

**(a) A DataScript database.** Works as an image today, in the probe (M1): 900 datoms built in 1,369 ms; copied out
in 44.5 ms by the slow copier; read in place, queried by Datalog over the view; placed into a fresh domain in 11 ms
and transacted on. Its cost is its shape: `Datom` is an eve-deftype of five serialized fields, so a datom is six
blocks — **296 B a datom in the slab**, 142 in the image. The datoms themselves pack to 17–19 B each (M2). A
datom-range static is therefore not an image but a sorted batch — E, A, V, Tx as columns (narrowed, dictionary,
any, run-end; M7) with each chunk's first and last key in a directory — which is what Datomic's index segments are
(up to about 50 KB, 1,000–20,000 datoms; C9). That is a new index leaf for the port, not a serialization of the
current one; it is the plan's fourth phase and not promised earlier.

**(b) A Dataset under columnar ops with row assembly interleaved.** The packed batch *is* a Dataset with more
column types; measured on 50,000 rows × 7 columns at 64 KiB chunks: scan 1.4 ns a row, filter 3.0, one cell 106 ns,
a row assembled in 0.64 µs (M4). Today's Dataset has numeric columns only, no row view, and its bulk path copies a
column out and back (`census/DEFTYPE-AND-COLUMNAR.md` §2.2, §2.5).

The design is not vectors-of-scalars only. Its weak case is named: a map with non-symbol keys (M2).

## Q10 — the conversion matrix

Costs are from M5 / M6 on the 5,000-message corpus (70,997 blocks) unless a cell says otherwise. "—" = identity.

| from ↓ to → | plain | slab | static |
|---|---|---|---|
| **plain** | — | the direct writer: 883 ms; through a `swap!` 1,190 ms. Built in a buffer and placed: 0.56× that | `pack`: an encoder (prototype, unmeasured for speed); or build in a buffer and compact to an image: the buffer build 420 ms |
| **slab** | `eve->clj`: 41–44 ms | same domain: by pointer, free. Another domain: **refused today** (`:copy` materializes and rebuilds); with the walk: copy out 12 ms + place | `detach` to an image: 10.5–12 ms; under the pin 4.2× `@a` |
| **static** | decode: packed 2.5 µs a row (Transit MessagePack 7.1); an image through `eve->clj` 27 ms | an image placed by run claims: 80 ms through a `swap!`, of which ~2.5 ms is placement and the rest the tally's hold (M6). A chunk: one block, a `memcpy` | image ↔ packed: `pack` / lay out; same form: — |

And the plan's specific questions: `eve->clj` of a static decodes it (an image by the walk Eve's types already do,
a packed one by its reader). `static` of a static is itself. `static` of plain data packs it. A slab value on
another host is reached only as bytes — a static is the way it gets there.

## Q11 — lifetime where there is a GC, and where there is not

- **A GC host** (JVM, JS, CLR, …): a view holds its buffer; the buffer goes when the last view does. A lazy seq
  over a static holds the view, hence the buffer — the retention of Q-A5, nothing worse. Policy: a lazy seq over a
  static is allowed and holds what it reads; a value kept past its parent is copied out (the same walk).
- **No GC** (the kernel; a slab holding chunks): the owner is explicit. In an atom a chunk block is owned by the
  domain's count like any leaf. Outside an atom on the kernel there is no owner short of a region hold, and the
  hold for stored values is the one found missing in Q8 — so on the kernel a static outside an atom lives exactly
  as long as the unit (form, task, request) that holds it, and escaping the unit is a store.
- Watch queues: `MmapAtom` has no watches (C8). If they return, they deliver views, which is cheap, and each view
  holds its buffer.

## Q12 — is 64 KiB a cap or a target? Where do small values live?

A target (`FORMAT-V1.md` §2; A7). Seal when the next row would pass 65,535 bytes; no padding; a row or scalar that
cannot fit makes one WIDE chunk. Many small values on a stream share a chunk — the writer fills one chunk with
successive values and their one symbol table, which is where the cache pays (hit rates 99 % at 64 KiB, M3). A small
value on its own is a short chunk of 32 bytes plus its nodes. A scalar is not a static at all. The effect on A5 and
A6: a view of one row pins at most its chunk; tables are 1.1–1.2 % of a 64 KiB chunk and 6–11 % of a 4 KiB one, so
a writer never seals early to make chunks small.

## The ValueScript aside (O10) — assessed, not built

The idea: build temporaries, locals and half-made objects off the allocator, thread-local, in a static form, and
feed them to the allocator with the spacing already mapped.

**On canonical Eve it is measured and it works.** The same writers allocating from an append-only buffer, then
compacted and placed by run claims, store a plain value in 0.37–0.56× the time of today's `swap!` (M6) — the
allocator, not the encoding, is most of a build. No writer changes.

**On the kernel, from the code** (`census/VS-KERNEL.md` §1–3, `-2` §3.6–3.7):

- Every temporary today is an ordinary allocator block at its final address, noted in a scope's log and freed at
  the scope's toss if no holder took it. Nothing is ever built outside the slab and moved in. The recorded cost of
  that: a form's scope close was 45–63 % of every row measured (`tally.vs:259-261`).
- There is **no block copier** in the kernel. "A crossing copies" is the rule; the code refuses instead (the tally
  answers −2, the runtime traps). Canonical Eve has the same gap (memory-model D5). The relocating copy this lane
  measured is that missing piece in both.
- Publishing a privately built region **without a copy** is possible only by dressing it as a page (one size class,
  blocks at final in-region offsets, registered by page CID) — and then it is an arena by rule 12's own test, it
  can never have a block freed singly, in-place builders trap inside it, and its eviction can pull it from under an
  atom (Q8). Not recommended.
- **The general question is already ruled on.** 2026-10-18, on the nursery ("a thread-local pool of short-lived
  eve values, promoted only when published"): "Let's fix the transients and go with that for local state."
  (atom.broker `docs/port-gap-closure/lanes/L22-eve-kernel.md:612-631`); its reason is on record — such a pool is
  "sound only if its regions die by scope and every publish point promotes" (`docs/eve-migration/ARCHITECTURE.md`
  §5.1). And rule 12's scratch conditions of 2026-10-29. So this lane proposes no thread-local space of values.
- **What is left, in the order the code supports** (`census/VS-KERNEL-2.md`, the O10 table — six options priced):
  1. The incumbent: transients, the linear conj, the per-form scope — blocks born at their final addresses.
  2. **A run entry in the allocator**: n consecutive blocks of one class on an owned page in one call. A decoder or
     a builder then lays blocks by pointer bump *inside allocator memory*; every guard, count, free and seal is
     unchanged; no standing rule is touched. This is what static Eve needs from the kernel, and it is the same
     operation the plan's Phase 1 adds to canonical Eve.
  3. A relocating block copier — the piece "a crossing copies" lacks. It may read from a thread's scratch only
     while that scratch is a call temporary: nothing in it published, dropped when the call returns.
  Neither 2 nor 3 is built or measured in the kernel. The two numbers to take first: the cost of one `alloc_block`
  call, and a block copy against a rebuild.

**What the Eve-side format must guarantee for ValueScript to use it:**

1. The image is the *consumer's* block layout. An image of canonical Eve blocks is not one of kernel blocks (256-byte
   own headers, 64-byte slots, a SHA in every header). The container, the block table and the relocation table are
   shared; the blocks are each side's own. `kind` 1 therefore carries a layout id.
2. The block table gives every block's size before any byte is read, so the allocator can claim by class.
3. The relocation table gives every pointer's position and target, so no reader has to parse a block to move it —
   and gives every block's holders, so counts can be written, not walked.
4. A packed chunk asks nothing of the allocator but one block of its size, 8-aligned (the kernel's class 15 is
   4-aligned today), and one type CID.
5. Sealed bytes are never written: a region or page holding them needs no owner and no return queue.
