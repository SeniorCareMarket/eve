# STREAM — the atom-transfer wire protocol: implementation plan

Status: plan of record for the place/detach re-engine lane (operator-approved
shape, Nov 12 2026). Working code home: `eve.flat.stream`, on the flat
lineage; the spike is JVM-first, the format is host-neutral.

## Why

The shipped image paths are correct and unusable for transfer: detach pays
per-block generic slab reads (~2.3 ms/block on message shapes; totals
114–733× slower than `@a`), place pays per-block validation and writes
(15,275–38,030 ns/block) beside a claim+copy batch engine in the same tree
that runs 68.5–176.0 ns/block. Every fast result in the flat campaign came
from bulk one-pass work (run claim, the Builder); every disaster came from
per-block round-trips through a generic path. This protocol is the transfer
form designed so both endpoints do only bulk work.

## The design in one paragraph

The sender walks the value from the root with a raw slab cursor and streams
blocks in **post-order** (children before parents, root last). The stream is
cut into **segments**; each segment header declares its byte length and block
count before the segment starts, so the receiver claims **one run per
segment** and computes nothing. Repeated structure rides **dictionaries by
kind** (descriptor, shape, symbol), declared in segment headers and reset at
segment boundaries. A block's **shape carries its pointer-slot map**, so
per-block relocation lists do not travel. Pointers travel as stream-global
block indices; because the order is post-order, every pointer targets an
already-placed block, and the receiver's patch is one pass of
`word = base(segment) + offset(block)` through a per-segment prefix-offset
map and a segment-base table. Block payloads are **byte-verbatim** — the
receiver's work per segment is one claim, one copy, one patch loop. Where the
walk meets homogeneous runs (scalar leaf arrays, vector levels), the sender
may emit a **typed run** the receiver expands into consecutive blocks inside
the same claim. Dataset-class values travel as chunk-leaf blocks, verbatim.

Governing rule (the operator's): **a transform is allowed only if it shrinks
the wire; layout transforms are never allowed.** Dictionary substitution
shrinks the wire. Columnizing graph data does not — it moves cost to both
endpoints. Payload dictionary coding is therefore out of v0 (it would kill
the verbatim copy); it may be measured later against its expansion pass.

## Format (v0)

- Stream = header, segment*, trailer. Header: magic, version, root
  descriptor reference, policy flags (dictionary mode; compression = none).
- Segment = header + payload.
  - Header: byte length, block count, dictionary additions (descriptors,
    shapes, symbols first used in this segment), reset flag (start a fresh
    dictionary generation).
  - Payload: block records in post-order. A record is a descriptor id
    (varint, dictionary-coded) followed by the block's verbatim bytes.
  - A typed run is a record variant: run tag, scalar/shape id, count,
    packed payload.
- Descriptor = {type tag, byte length (or its rule), shape id}.
  Shape = {pointer-slot map}. For tags whose pointer layout is
  data-dependent (e.g. HAMT nodes, where the bitmap decides), the shape is a
  per-tag slot resolver, not a static list — the spike enumerates which tags
  need resolvers.
- Pointer words inside payloads are stream-global block indices in Eve's
  pointer encoding. The sender rewrites slab address → stream index as it
  emits (one pass over the shape's slots); the receiver rewrites index →
  placed address in its patch pass.
- Segment geometry is header-declared: no counting at placement (the F5
  lesson stands — this is geometry the sender states up front, not counts
  the receiver discovers).

## Sender

1. Raw slab cursor: direct offset reads over the domain's slab. Never the
   generic per-word I/O descent (the Z1.7 mechanism is the cost being
   designed out).
2. Post-order walk from the root; shared blocks emitted once, at first
   visit; later references are backward indices to the emitted position.
3. Index table (slab block id → stream block index) maintained during the
   walk. Alternative measured in S0/S1: a two-pass walk (index pass, emit
   pass) if the table's cost shows.
4. Segmenter: buffer records until the segment byte budget; flush with its
   header (geometry + dictionary additions). Segment size is a knob
   (swept in S2: 64 KiB / 256 KiB / 1 MiB).
5. Typed runs: when the walk reaches ≥ threshold consecutive same-shape
   scalar blocks, emit a run instead. Threshold is a knob (swept in S2:
   8 / 16 / 32).

## Receiver

1. Read segment header; verify geometry (sum of descriptor lengths ==
   segment bytes) — the fast door checks nothing else; the checked door
   (S3) runs the `eve.flat.check` discipline per segment before claiming.
2. Claim one run of the segment's block count via `alloc-run!`.
3. Copy payload blocks into the run in order, building the segment map
   (prefix offsets per block index).
4. Patch pass: per block, walk its shape's slot map; each pointer word
   becomes `segment_base[target segment] + segment_offset[target block]`
   via the segment-base table. Post-order guarantees targets are placed.
5. The final segment's last block is the root. `transfer!` swaps the placed
   root into the target atom; `place` returns it.

## Phases and gates

All measurement on J through the job agent, HEAD-verified logs, min-of-5,
absolutes always beside the shipped absolutes they replace.

- **S0 — the walk.** Post-order cursor walk emitting nothing (count +
  checksum) on map-10k / vec-100k / records-10k / messages-5k.
  Gate: ≥10× faster than the shipped detach walk per corpus, and within 5×
  of `@a` totals (16.06 / 51.02 / 71.97 / 95.64 ms). If the cursor cannot
  beat the generic walk by 10×, STOP and report — the design's premise is
  that the walk's cost is generic-machinery, not data.
- **S1 — the pipe.** Format + sender + receiver, fast door, per-segment
  dictionaries only, no typed runs. Gates, all four corpora: values equal;
  receiver all-in ≤ 500 ns/block (shipped place: 15,275–38,030);
  end-to-end transfer ≥20× better than shipped transfer totals
  (1,539.74 / 28,645.78 / 8,371.81 / 45,284.57 ms); allocator claims per
  transfer == segment count (± small constant).
- **S2 — the knobs.** Cross-segment dictionaries + reset policy; segment
  size sweep; typed runs for scalar leaves + threshold sweep. Gates: wire
  bytes ≤ image bytes on the graph corpora (map, messages); vec-100k
  transfer improves ≥2× from typed runs over S1; fragmentation report —
  the infilled domain's free-run structure vs the source domain's,
  published either way.
- **S3 — the landing shape.** `transfer!` re-based on the stream behind the
  existing API; per-segment checked door; zip/detach/place/transfer/check
  suites green; the full comparison table (every row of the Nov 12 readout)
  republished with stream numbers beside the shipped ones. Image v1 stays
  as the storage form; the stream is the transfer form; the packed chunk
  stays the columnar/storage form.

## Out of scope for the spike

Compression codecs (dictionaries only); payload dictionary coding; CLJS and
other hosts (format is host-neutral, spike is JVM); persistence of streams
beyond a byte-array and a file arm; the F3 leaf question (chunk leaves ride
as verbatim blocks — the fit is noted, the decision is the operator's).

## Open questions the spike must answer with measurements

1. Post-order vs the image walk's natural order — does the retire walk's
   ordering fight post-order emission, and what does reordering cost?
2. Sender index-table cost per block; whether two-pass beats it.
3. Which block tags need slot resolvers rather than static slot maps.
4. Segment size vs wire bytes vs claim count — where the goldilocks point
   actually sits on these corpora.
5. Typed-run threshold — where runs start paying.
