# Static Eve — measurements

Every figure here was produced in this lane, on this box, by a probe whose source and raw output are committed in
`doc/static/probes/`. Arms that are compared ran in the same JVM process on the same data.

**Provenance (all sections).** Tree: `SeniorCareMarket/eve` `4a92129` (release 0.9.3.1) plus `doc/static/**` only —
`git diff 4a92129 HEAD -- . ':!doc/static'` is empty. Tools on the PATH: OpenJDK 21.0.12, Clojure 1.12.0 (jar from
Maven Central; no `clojure` CLI, no `bb` on the box), transit-clj 1.0.333 / transit-java 1.0.371. Box: 4 cores, 15 GB,
Linux; load1 0.8–0.9 when the timed probes started (census agents were reading files in the background — they wait on
the network, they do not compute). Dates: 2026-11-11, 3:58 PM – 11:40 PM ET (the earlier "– 2026-11-12 12:30 AM" was written without reading the clock). Timings are the minimum of 5–9
runs after 2–5 warm-up runs unless a row says otherwise. Nothing here ran on ClojureScript, babashka or any host but
the JVM: there is no such host on this box.

**What these are and are not.** The static arms are research prototypes (`probes/lib.clj`, `probes/pack.clj`,
`probes/java/staticprobe/ImageCopier.java`): Clojure with boxing on some paths, no tuning. They bound what the
mechanisms cost from above; they are diagnostics for the design, not acceptance of a product.

Vocabulary used below:

- **slab** — a value in an Eve domain (blocks in size-class slabs).
- **plain** — the host's own collections (`eve->clj`'s answer).
- **image** — every block a value reaches, copied once into one byte array, pointers rewritten to positions in that
  array. Eve's own types read it through a slab I/O over the array.
- **packed** — the prototype chunk of `probes/pack.clj`: a per-chunk symbol table, shapes (key tuples stored once),
  typed vectors, batches (rows of one shape stored column by column); scalars are Eve's scalar bytes without the two
  magic bytes.

## M1 — Eve's types over one append-only buffer (P1, P5)

Probe: `p1_bump_sio.clj` (19/19 pass), `p5_types.clj` (19 pass, 1 error of 20). Outputs: `p1_bump_sio.out.txt`,
`p5_types.out.txt`.

| Check | Result |
|---|---|
| `EveHashMap`, `EveVector`, `EveHashSet`, nested, built into a single growing `byte[]` through a custom `ISlabIO` whose `alloc!` bumps the tail and whose `free!` does nothing — **no new type code** | reads, `get-in`, `nth`, `count`, `contains?`, `seq`, `(= static plain)` both ways, `(= (hash static) (hash plain))`: all pass |
| An update appends; the old `(length, root)` view | still equal to the old value over the grown buffer; no byte below the old length changed; reads from a copy truncated at its own length; its hash unchanged |
| Property: 80 generated maps × 15 mixed updates (assoc, dissoc, nested replace), every earlier view checked after all appends, over the grown buffer and over a truncated copy, with hash | pass |
| File out → file in → read in place | pass, no transform; a frozen view refuses allocation |
| Sorted map and set (`eve.sorted`, a treap of eve-deftypes), rb-tree set, int-map, `:float64` eve array, a 2-column Dataset — each built in a buffer, copied out of a slab as an image and read in place, and placed back into a fresh domain | all pass |
| A user `eve-deftype` with a primitive, a mutable primitive, a serialized and a reference field | the **same class** reads the image on the JVM; a later in-place write to the slab original is not seen by the image |
| A DataScript DB from the repository's port (`DB`, `BTSet`, `Node`, `Leaf`, `Datom` are eve-deftypes), 150 people = 900 datoms | the image has the same datoms, a Datalog query runs over it in place, and placed into a fresh domain it can be transacted on |
| **`EveList`** | **fails on a frozen view**: its `seq` / `next` allocate a header block per step (`src/eve/list.cljc:157-161,195-203`), so a read needs an allocator |
| A write through a frozen view | **not refused** by the prototype (it only refuses allocation) — a product view must have no write path |

## M2 — bytes (P2 §1)

Probe: `p2_sizes.clj`, output `p2_sizes.out.txt`. One self-contained value each. In parentheses: the same bytes under
zlib level 6.

| Corpus | EDN text | Transit JSON | Transit MessagePack | Eve flat codec | packed, bare | + symbols | + shapes | + batches | + typed vectors |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 10k chat messages | 2,468,641 | 2,451,231 | **1,739,898** (344,745) | 4,159,580 | 2,914,612 | 2,215,385 | 1,735,408 | 1,632,981 | **1,490,480** (298,139) |
| 50k records, as rows | 5,512,613 | 5,037,512 | **3,278,635** (726,632) | 9,404,124 | 6,354,153 | 4,389,597 | 2,989,608 | 2,789,664 | **1,795,907** (556,463) |
| 30k datoms (5k people) | 1,459,941 | 1,750,033 | **1,003,917** (133,385) | 2,927,681 | 1,817,710 | 1,434,155 | 954,163 | 864,196 | **564,188** (100,306) |
| the repo's own EDN files (6) | 57,298 | 57,554 | **46,839** (15,686) | 84,478 | 64,338 | 48,404 | 42,139 | 40,484 | **38,688** (17,460) |
| 50k records, column-major (a map of 7 vectors) | 3,062,674 | 2,937,566 | **2,178,675** (577,008) | 4,704,284 | 4,004,280 | 3,389,697 | 3,389,664 | 3,389,664 | 2,395,927 (697,015) |
| map 10k int → int | 107,780 | 137,786 | **59,235** (23,972) | 220,007 | 180,035 | — | — | — | 180,035 (71,403) |
| vector 100k ints | 588,891 | 588,891 | **368,549** (211,988) | 1,100,007 | 900,036 | — | — | — | 400,040 (138,413) |

Ratios to Transit MessagePack: the flat codec 1.8–3.7×; packed with everything on **0.55–0.86×** on the four
Eve-shaped corpora; **3.04×** on the int → int map and 1.09× on the int vector (the two shapes the prototype has no
compact form for: a map whose keys are not symbols keeps a tag and a 4-byte payload per integer plus two 16-bit
offsets; dense i32 loses to MessagePack's 1–3-byte small integers). And **1.10×** on the same 50,000 records handed
over column-major as plain data: the prototype's typed vectors cover numbers only, so its string and keyword
vectors stay generic — the row form, whose batches dictionary-code them, is the smaller of the two (1,795,907 B
against 2,395,927). That row was missing here until the probe was re-run at 11:40 PM ET after an alignment fix in
the prototype; every other line of the output is byte-identical to the first run.

What each mechanism took off, on the three row corpora (messages / records / datoms): the symbol table −24 / −31 /
−21 %; shapes a further −22 / −32 / −33 %; batches −6 / −7 / −9 %; typed vectors −9 / −36 / −35 %.

The physical forms, from P3 (same shapes, smaller counts):

| Corpus | EDN | Transit MessagePack | flat codec | **image** | slab, class-padded |
|---|---:|---:|---:|---:|---:|
| map 10k int → int | 107,780 | 59,235 | 220,007 | 299,324 | 390,944 |
| vector 100k ints | 588,891 | 368,549 | 1,100,007 | 1,613,208 | 3,613,216 |
| vector 10k records | 595,373 | 358,526 | 1,098,897 | 1,561,456 | 1,961,504 |
| 5k chat messages | 1,204,681 | 840,236 | 2,050,152 | 3,712,760 | 5,789,440 |
| DataScript DB, 900 datoms (P5) | — | — | — | 127,576 (142 B a datom) | 266,688 (**296 B a datom**) |

The same datoms packed (P2): 16.9–18.8 B a datom.

## M3 — the chunk-size sweep (P2 §2)

Rows packed into self-contained chunks (each with its own tables), all mechanisms on; total includes an 8-byte
directory row per chunk. "vs one" = total ÷ the single-chunk total.

| Target | messages: chunks | vs one | tables | hit rate | zlib | records: chunks | vs one | tables | hit rate | zlib | datoms: chunks | vs one | tables | zlib |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 4 KiB | 478 | 1.134 | 6.08 % | 95.1 % | 698,392 | 782 | 1.465 | 11.04 % | 94.8 % | 1,113,892 | 164 | 0.981 | 7.72 % | 174,896 |
| 8 KiB | 239 | 1.095 | 3.93 % | 96.9 % | 599,396 | 327 | 1.397 | 7.98 % | 96.4 % | 976,622 | 82 | 0.937 | 4.16 % | 147,744 |
| 16 KiB | 115 | 1.026 | 4.60 % | 96.5 % | 503,034 | 156 | 1.204 | 5.09 % | 98.0 % | 832,166 | 41 | 0.915 | 2.14 % | 135,055 |
| 32 KiB | 58 | 1.000 | 2.38 % | 98.2 % | 447,586 | 59 | 1.025 | 2.29 % | 99.2 % | 718,533 | 21 | 0.905 | 1.11 % | 129,602 |
| **64 KiB** | 29 | **0.986** | 1.21 % | 99.1 % | 402,606 | 29 | **1.012** | 1.14 % | 99.6 % | 684,762 | 11 | **0.899** | 0.58 % | 127,242 |
| 256 KiB | 8 | 0.976 | 0.34 % | 99.8 % | 344,224 | 8 | 1.003 | 0.32 % | 99.9 % | 609,115 | 3 | 0.979 | 0.15 % | 108,431 |
| 1 MiB | 2 | 0.987 | 0.08 % | 99.9 % | 311,054 | 2 | 1.000 | 0.08 % | 99.97 % | 562,656 | 1 | 1.000 | 0.05 % | 100,306 |
| one chunk | 1 | 1.000 | 0.04 % | 99.97 % | 298,139 | 1 | 1.000 | 0.04 % | 99.99 % | 556,463 | 1 | 1.000 | 0.05 % | 100,306 |

"Hit rate" = 1 − (symbol-table entries ÷ every keyword / symbol / string occurrence in the chunk).

Three readings:

1. Below 32 KiB the per-chunk tables cost real bytes on record-shaped data (+20 % at 16 KiB, +46 % at 4 KiB).
2. 64 KiB is within 1.2 % of one chunk on rows, below it on messages, and the **smallest** total on datoms: a chunk
   under 65,536 bytes addresses everything inside it with 16-bit offsets, and past it the heterogeneous column needs
   32-bit ones.
3. Compressed chunk by chunk, small chunks lose: at 64 KiB zlib totals are 23–35 % above the single-chunk figure, at
   1 MiB 1–4 %. Opaque compression belongs to a unit larger than the chunk.

## M4 — reading in place, by chunk size (P4)

Probe: `p4_access.clj`, output `p4_access.out.txt`. 50,000 records (7 columns); baseline = the host's own vector of maps.

| | open µs/chunk | decode ms/chunk | decode µs/row | scan sum ns/row (tables open / opened per chunk) | filter count ns/row | row → plain map µs (open / cold) | one cell ns |
|---|---:|---:|---:|---:|---:|---:|---:|
| host vector of maps | — | — | — | 23.34 | 17.86 | 0.054 | 59.4 |
| Transit MessagePack, whole value | — | 226.3 | 4.18 | — | — | — | — |
| Eve flat codec, whole value | — | 109.7 | 2.18 | — | — | — | — |
| packed 4 KiB | 5.7 | 0.053 | 0.82 | 20.30 / 92.41 | 23.75 | 0.844 / 6.39 | 155.8 |
| packed 8 KiB | 12.9 | 0.120 | 0.78 | 3.08 / 50.06 | 4.79 | 0.782 / 8.34 | 144.4 |
| packed 16 KiB | 6.6 | 0.251 | 0.78 | 2.15 / 26.50 | 4.05 | 0.736 / 8.54 | 132.5 |
| packed 32 KiB | 6.8 | 0.620 | 0.73 | 1.55 / 10.77 | 3.23 | 0.696 / 9.04 | 122.0 |
| **packed 64 KiB** | 6.5 | **1.331** | 0.77 | **1.44** / 5.84 | **2.96** | 0.636 / 8.55 | **106.4** |
| packed 256 KiB | 6.6 | 4.926 | 0.79 | 1.35 / 2.55 | 2.67 | 0.631 / 9.59 | 92.6 |
| packed 1 MiB | 7.3 | 19.704 | 0.79 | 1.34 / 1.67 | 2.72 | 0.601 / 9.10 | 74.9 |
| packed, one chunk | 7.5 | 42.040 | 0.84 | 1.29 / 1.45 | 2.62 | 0.595 / 8.39 | 68.2 |

*Corrected 2026-11-11 11:40 PM ET (review finding R1-1): an earlier revision of this table carried the figures of
an earlier, uncommitted run of the same probe. Every cell above is now the committed `p4_access.out.txt`; the
readings below are recomputed from it.*

- A column scan over packed bytes is **16–18× faster than the host's vector of maps** from 64 KiB up (1.29–1.44
  ns/row against 23.34); a keyword-equality count 6–7× (2.6–3.0 against 17.86).
- Whole-chunk decode to plain rows: 0.73–0.84 µs/row — 5–6× Transit MessagePack's 4.18 and 2.6–3.0× the flat
  codec's 2.18.
- Assembling one row into a fresh plain map costs 0.60–0.84 µs with the chunk's tables open — 11–16× a host `nth`
  (0.054), because it builds a map. Reading one cell is 68–156 ns against the host's 59.
- What chunk size changes: the decode unit (1.3 ms at 64 KiB, 19.7 ms at 1 MiB); the fixed cost of opening a chunk
  (≈ 6–7 µs in the prototype, which decodes every symbol eagerly) — 4.4 ns/row of a cold scan at 64 KiB, 0.3 at
  1 MiB; and a one-cell read, which falls from 122 ns at 32 KiB to 68 ns in one chunk. Warm scans and row assembly
  are flat from 32 KiB up.

10,000 chat messages (nested maps, sets, small vectors of maps): decode 2.5 µs/row (Transit MessagePack 7.1, flat
codec 5.85); a row assembled in 2.4–2.8 µs at every chunk size; one cell 68–197 ns.

## M5 — transformations (P3, P3c)

Probes: `p3_transform.clj` (Clojure copier), `p3c_copier.clj` (the copier as a small Java class). Outputs:
`p3_transform.out.txt`, `p3c_copier.out.txt`. The "to a slab" rows of P3c include creating a pre-sized heap domain,
shown alone in its own column.

| Corpus | blocks | slab → plain (`eve->clj`) | **slab → image** | ×  | plain → slab (direct writer) | **image → slab, one run claimed per class** | (domain alone) | plain → buffer → image → slab |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| map 10k int → int | 2,380 | 3.66 ms | 0.70 ms | 5.2 | 162.98 ms | 4.19 ms | 2.01 ms | 61.30 ms (0.38×) |
| vector 100k ints | 103,229 | 17.54 | 9.55 | 1.8 | 151.47 | 5.73 | 2.32 | 38.19 (0.25×) |
| vector 10k records | 30,325 | 20.69 | 3.30 | 6.3 | 367.52 | 2.96 | 1.68 | 201.35 (0.55×) |
| map 1k keyword → vector of 20 | 22,313 | 5.74 | 2.02 | 2.8 | 71.90 | 3.02 | 1.81 | 35.34 (0.49×) |
| 5k chat messages | 70,997 | 41.14 | 11.94 | 3.4 | 882.82 | 5.51 | 3.02 | 567.52 (0.64×) |

From P3 (same session as each other, an earlier run): placing the same image by **per-block** allocation cost 2.22 /
27.69 / 9.72 / 8.21 / 23.16 ms against 1.25 / 4.92 / 2.49 / 2.38 / 5.29 ms by run claims (both including the
domain) — the run claim is 3–9× the per-block path net of the domain (3.2 / 9.1 / 8.7 / 7.7 / 9.3; 1.8–5.6× including
it; corrected from "5–12×", review finding R1-2). Pre-sizing the domain does not help the direct
writer (130.19 → 128.14 ms on the first row): its cost is not growth.

Readings:

1. **Out of a slab**, a relocating block copy is 1.8–6.3× `eve->clj` (90–300 ns a block), and its result is still
   operable and still placeable.
2. **Into a slab**, placing a pre-laid-out image by claiming one run of blocks per size class costs 1–3.5 ms net on
   shapes whose build from plain data costs 72–883 ms. Placement, not planning — but see M6 for what a commit adds.
3. **The allocator is most of today's build.** The same writers allocating from a bump buffer instead of the slab,
   then compacted and placed, finish in 0.25–0.64× the time — with no change to any writer.

Point reads, 100,000 random (P3): host collection 2.0–2.7 ms; slab value 18–54 ms; image through the slab-I/O
protocol 16–60 ms. Reading Eve's block layouts in place costs 7–31× a host collection, in a slab or out of it.

## M6 — one `swap!`, end to end (P6)

Probe: `p6_end_to_end.clj`, output `p6_end_to_end.out.txt`. Each row creates its own pre-sized in-process domain and
atom (shown alone), then does one `swap!`: the store, the tally's hold of the new root (`tally/hold-root!`, a walk of
every newly stored block), the CAS. The placed arm returns, from the swap's function, the value read at the offset
`ingest!` answered — an Eve value of the atom's own domain, stored by pointer.

| Corpus | domain + atom alone | `swap!` of the plain value | `swap!` placing an image by run claims | net of the domain | `swap!` building in a buffer, compacting, placing | `@a` (`eve->clj`) | an image copied under the pin |
|---|---:|---:|---:|---:|---:|---:|---:|
| map 10k int → int | 6.14 ms | 202.31 ms | 10.54 ms | **44.6×** | 75.73 ms (0.37×) | 3.30 ms | 0.40 ms (8.2×) |
| vector 100k ints | 3.34 | 324.88 | 108.41 | **3.1×** | 154.08 (0.47×) | 19.05 | 9.63 (2.0×) |
| vector 10k records | 2.26 | 475.17 | 36.62 | **13.8×** | 241.50 (0.51×) | 20.73 | 3.27 (6.3×) |
| 5k chat messages | 3.37 | 1,190.14 | 80.31 | **15.4×** | 666.10 (0.56×) | 43.86 | 10.54 (4.2×) |

Readings:

1. Through a real atom, storing a pre-laid-out value is **3–45× faster** than storing the same value as plain data,
   and every arm reads back equal; a further write on top of a placed value is sound.
2. **The holder count is now the cost.** Placement itself is 1–3.5 ms (M5); the placed `swap!` is 10–108 ms. On the
   vector of 100,000 integers that is ≈ 1 µs for each of 103,229 new blocks: per block the tally does a flags CAS, a
   count add, a read of the block's references and a `swap!` on a host set (`src/eve/tally.cljc:310-312,335-341`).
   An image's block table and relocation table already say who points at whom, so the counts could be written during
   placement instead of discovered by a walk. **Not measured** — it is the first item of the plan's allocator phase,
   with its probe named there.
3. Building plain data in a buffer and placing it is 0.37–0.56× the incumbent `swap!` — the allocator's share again,
   with no change to the writers.
4. On the way out, an image copied under the pin is 2.0–8.2× `@a`.

## M7 — sharing, retention, column encodings (P6 §6.2–6.3, P6b)

**Sharing.** One 1,000-element vector stored at k places of an Eve map (same domain, by pointer):

| k | image | Eve flat codec | EDN | distinct host vectors after `eve->clj` |
|---:|---:|---:|---:|---:|
| 1 | 16,308 B | 11,028 B | 3,897 B | 1 |
| 10 | 16,532 B | 110,217 B | 38,970 B | 10 |
| 100 | 19,380 B | 1,102,197 B | 389,790 B | 100 |

The image copies a block reached twice once; every logical form — the flat codec, EDN, and materialization itself —
grows with k.

**Retention.**

- A 10,000-entry map written by Eve's direct map writer into an append-only buffer: buffer 4,833,684 B, live image
  299,332 B — **6.2 % live**. The writer path-copies per entry and frees what it superseded; in a buffer whose free
  does nothing, that stays. A build needs a compaction (the same walk) before it is kept.
- 100 single-key `assoc`s on it: 52,740 B more, 527 B an update. Every earlier view still reads, and pins the buffer.
- One message read out of a 5,000-message image: the view pins 3,746,412 B to hold 556 B (0.015 %). The same walk
  copies it out on its own (108 µs in the prototype, most of it the copier's fixed 1 MiB buffer).

**Column encodings** (`p6b_encodings.clj`; 30,000 datoms of 5,000 people in EAVT order; one value read at a random
index, 1,000,000 reads; P6 §6.4's figures for the narrowed and run-end columns priced a Clojure map lookup per read
and are superseded by these):

| Column | plain i32 | narrowed (frame of reference) | run-end |
|---|---|---|---|
| `:e` — sorted, 6 datoms an entity | 120,000 B · 0.57 ns | 2 bytes a value: 60,000 B · 1.38 ns | 5,000 runs: 40,000 B · 101.5 ns |
| `:tx` — one transaction per 100 people | 120,000 B · 4.26 ns | 1 byte a value: 30,000 B · 3.12 ns | 50 runs: 400 B · 42.0 ns |

(The loop and the index load alone: 0.35 ns.) Narrowing keeps a read at a load and an add and halves or quarters the
column; run-end shrinks it 3–300× and turns one read into a binary search over the runs (6–13 steps here, 42–102 ns).
Dictionary codes are in M4: a keyword column counted at 2.6–3.0 ns a row from 64 KiB up, one cell read at 68–156 ns.

## M8 — 64 KiB against 1 MiB as the unit (P7, a spike; with M3 and M4 re-read)

Probe: `p7_unit_size.clj`, output `p7_unit_size.out.txt`, run 2026-11-12 12:30 AM ET. An image cut in buffer order into units
of at most 64 KiB or 1 MiB (each block charged its 8-byte table row and 12 bytes a pointer) and placed unit by
unit: one run claimed per (unit, size class), the unit's blocks copied, pointers rewritten when every unit is in.
Every arm reads back equal to the plain value.

| Corpus (image bytes, blocks) | units: 64 KiB / 1 MiB | pointers leaving their unit | run claims |
|---|---:|---:|---:|
| map 10k int → int (299,324 B, 2,380) | 6 / 1 | 2.98 % / 0 | 30 / 5 |
| vector 100k ints (1,613,208 B, 103,229) | 55 / 4 | 1.47 % / 0.11 % | 110 / 8 |
| vector 10k records (1,561,456 B, 30,325) | 33 / 3 | 2.45 % / 0.08 % | 66 / 6 |
| 5k chat messages (3,710,432 B, 70,997) | 78 / 5 | 2.25 % / 0.26 % | 312 / 20 |

Placement time: no difference this probe can resolve. Every arm is 0.3–7.7 ms net of creating the domain and one
came out negative (−1.29 ms), so the domain's own variance is as large as the effect; the copies and the pointer
stores are the same at every unit size. Which pointers leave a unit depends on the copier's walk order.

The packed form at the same two sizes, from the runs already committed (M3, M4):

| | 64 KiB | 1 MiB |
|---|---:|---:|
| total bytes, messages / records / datoms | 1,469,968 / 1,817,668 / 507,264 | 1,471,180 / 1,796,701 / 564,196 |
| the same, each chunk under zlib | 402,606 / 684,762 / 127,242 | 311,054 / 562,656 / 100,306 |
| tables' share of a chunk (messages) | 1.21 % | 0.08 % |
| one chunk decoded to plain rows, records / messages | 1.33 ms / 0.88 ms | 19.70 ms / 12.26 ms |
| column scan, chunk opened each time (ns a row) | 5.84 | 1.67 |
| column scan, tables open (ns a row) | 1.44 | 1.34 |
| one cell (ns), records / messages | 106.4 / 110.4 | 74.9 / 80.8 |
| chunks for 50,000 records | 29 | 2 |

Reading: for **placement** 1 MiB is the better unit — 12–16× fewer units, a tenth or less of the pointers crossing
a unit boundary, 6–16× fewer run claims, at no measurable cost. For the **packed** form the two sizes trade: 1 MiB
is 18–23 % smaller when each chunk is compressed on its own and 3.5× faster on a scan that opens every chunk;
64 KiB decodes a unit 14–15× sooner, is 10 % smaller on datoms uncompressed (16-bit offsets), and pins or fetches a
sixteenth as much to read one row. That is the two-level shape of the original ask with the jobs assigned by
measurement: 64 KiB the cache, decode and wire unit; 1 MiB the placement, compression and stored-object unit.

