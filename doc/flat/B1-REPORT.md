# Lane B1 — completion declaration: a builder with no garbage

Branch `claude/static-b1-builder` (local commits only — this box has no
GitHub credential for SeniorCareMarket/eve; the coordinator collates).
Base: research tip `7402ef9` + Wave 0 contracts merged twice via the `w0`
path remote (final merge `b296231`, incl. `doc/static/SPIKE-LOG.md`).

## Deliverable

`src/eve/static/build.cljc` — `(build! v)` builds plain data into Eve
blocks in final layout, in one pass, in an append-only build buffer
(`BuildSio`: a slab I/O over one growable byte array, allocation-logged,
first block at offset 32 under the reserved chunk header); `(image b)`
seals the buffer into a FORMAT-V1 image chunk (kind 1, layout 1) by
appending the block table and relocation table; `(build-image v)` is
the two composed. Public helpers: `new-sio`, `allocations`,
`alloc-count`, `image-tables`, `read-i32-at`.

How it builds: vectors through `eve.vec/build-vec` (the same
`assemble-vec!` today's writer finishes with), lists back-to-front as
today's writer builds them, and a **bottom-up HAMT** for maps and sets —
entries grouped by hash fragment level by level over a 32-slot array,
every node allocated once in the exact layout `eve.map`/`eve.set`
read. That bottom-up HAMT is the lane's reason to exist: today's map
and set writers build by repeated assoc/conj, path-copying per insert,
which in an append-only buffer is the 6.2 %-live build of MEASUREMENTS
M7. Scalars and keys ride the codec's own encoders, so encodings are
the product's, not a dialect.

Sealing computes both tables with ONE walk of `eve.retire/children`
over the finished blocks — metadata only, no byte moves, no compaction
pass. Positions are the pointer field's byte offset in the buffer (the
probe copier's convention, and Z1's); owner/target are block ordinals,
root first. One walk as the single source of truth is also what lets a
fallback subtree — written by today's writer into the same buffer —
seal correctly.

## Requirements (test/eve/static/build_test.cljc; requirements.edn B1.1–B1.4 :built)

- **B1.1 `direct-build-equal-and-updatable` — PASS.** For hand cases and
  the W0 corpora at tiny sizes: built = plain both ways, built =
  today's writer's value, hashes agree, root digests agree (SHA-256
  over `value->eve-bytes`, computed the way `eve.digest` computes
  them); a further assoc/conj on the built value works and the
  pre-update view is untouched; scalar roots incl. nil.
- **B1.2 `direct-build-no-garbage` — PASS.** The allocation set of a
  fresh build equals `eve.retire/reach`'s reached set exactly, counts
  equal — including a 10,000-entry map and a 5,000-entry set, the
  shapes that are 94 % garbage through today's writers.
- **B1.3 `direct-build-is-placeable` — PASS.** The chunk header reads
  back through `eve.static.contract`; both tables round-trip through
  the contract's readers; the relocation table names exactly the
  pointers an independent `eve.retire/children` edge walk finds, and
  every relocation's position holds its target's offset in the image;
  the image actually places — into an atom's domain inside a `swap!`,
  per-class `alloc-run!` claims, bytes copied, pointers patched — and
  the placed value reads back equal and takes a further write.
- **B1.4 `direct-build-fallback` — PASS.** A host array element is
  written by today's array writer and recorded `{:reason :host-array}`;
  a fn is written by reference and recorded `{:reason :reference}`,
  reading back identical in-process; a foreign Eve value is
  materialized and rebuilt, recorded `{:reason :eve-value}`; covered
  values record nothing.

Suite: `clojure -M:jvm-test -n eve.static.build-test` — 4 tests, 354
assertions, 0 failures, 0 errors, re-run green after every perf pass.
Confirmed on the venue (J, post-merge verification leg: build +
requirements + contract suites together) — 9 tests, 917 assertions,
0 failures, 0 errors.

## Board row B5 — plain → atom (gate: under the probe's 0.37–0.56× of today's swap!, per corpus)

Bench: `test/eve/static/b1_bench.clj` — scoreboard method (fresh
pre-sized atom per rep, min of 9 after 3 warm), arms interleaved per
rep, each arm in its own domain, one corpus per JVM, -Xmx1g. The
direct arm is build-image + place (the bench's Z3 stand-in on the
Wave 0 default `alloc-run!`) + `swap!`. Read-backs verified ok on
every corpus and every run.

**Venue rows of record — J (j.slaa.us, clean box), same bench,
min of 9, interleaved:**

| corpus             | gate (probe figure) | today       | direct    | ratio                                            | split (build + place/swap) |
| ------------------ | ------------------- | ----------- | --------- | ------------------------------------------------ | -------------------------- |
| map 10k int->int   | 0.37×               | 484.46 ms   | 52.56 ms  | **0.11× — PASS, beats the whole 0.37–0.56 band** | 35.68 + 16.88              |
| vector 10k records | 0.51×               | 747.39 ms   | 449.92 ms | 0.60× — near-miss, just over the band's 0.56 top | 348.97 + 100.96            |
| 5k chat messages   | 0.56×               | 1,195.92 ms | 811.59 ms | 0.68× — MISS                                     | 600.56 + 211.03            |
| vector 100k ints   | 0.47×               | 387.35 ms   | 623.21 ms | 1.61× — MISS                                     | 278.62 + 344.60            |

**B5 gate as written — under the probe's figure on every corpus —
is NOT met.** It is met on map-10k only (0.11×, under the band's
floor); records is a near-miss at 0.60×; messages (0.68×) and
vec-100k (1.61×) miss. Reported as measured; the row set is also in
`doc/static/SPIKE-LOG.md`.

Hatch rows (this box, banked during the perf loop before the venue
shift; min of 9, interleaved; provenance: B1-STATUS log): map 0.16×,
records 0.52×, messages 0.95×, vec 1.60×. Weather, stated plainly:
hatch is a 1.5-CPU shared cell that ran ~13 lanes; load1 swung 2–45
across those runs and the box rebooted twice mid-campaign, killing
two bench JVMs. Its absolute milliseconds are contention-inflated by
an order of magnitude against the probe's quiet-box figures (W0's
own baseline today-arm for map-10k reads 2,329 ms there against the
probe's ~80 ms scale), which is why the J rows above are the board
rows of record.

Where the remaining gap sits, per the venue splits: on messages the
**build** is the larger half (600.56 of 811.59 ms — many small maps;
this lane's next iteration, if any). On vec-100k **place/swap** is
the larger half (344.60 of 623.21 ms) — the default per-block
placement plus the commit's tally walk, which are lanes Z2/Z3/Z4's
charter (run claims; counts at placement, fork F5), not this lane's
file; the split is the evidence. The builder's own numbers improved
across three logged passes on hatch: the seal defect below (21.2 s →
1.12 s on vec-10k build-image), scalars via `encode-value`,
loop-based node writers, and array-based HAMT grouping (records
build 3,993.80 → 1,229.97 ms across passes, weather varying).

## Defects found on the way (measured at this pin, not rumored)

1. **The seal was 15× the build** — `eve.static.contract`'s
   `write-block-table!`/`write-relocation-table!` take the byte array
   unhinted, so every `aset` is reflective: ~0.8 ms per table entry
   measured, ≈50k reflective writes per seal of a 100k vector. B1's
   seal writes the tables with a ^bytes-hinted local writer; the bytes
   are the contract's exactly and B1.3 reads them back through the
   contract. **Flagged for W0**: the contract writers want ^bytes
   hints — every lane that seals through them pays this.
2. Two bench defects of mine, both fixed and logged in B1-STATUS: a
   fresh 44 MB domain per rep (measured region zeroing, not the
   store), and both arms sharing one domain (the direct arm inherited
   the today arm's fragmented allocator; per-arm domains cut map-10k
   place/swap ~5×).

## Divergences from FORMAT-V1

None. Chunk = header (32 B, kind 1, layout 1, root = root block's
buffer offset) + blocks as the slab holds them + block table +
relocation table, tables 8-aligned; block sizes are the blocks'
logical sizes (the 0–3 B of alignment slack after a block is not part
of it and is not copied at placement).

## Notes for the collator

- `eve.static.build` is JVM-only for now: the .cljc's ClojureScript
  side throws "not built on ClojureScript yet" (lane X1's territory
  is the Node bytes question; the done-bar here is the JVM runner).
- Sealing after updating a built value is out of contract: the tables
  name the value as built (documented on `image`).
- Metadata on plain values is not carried (today's writers do not
  carry it either — parity, not a cut).
