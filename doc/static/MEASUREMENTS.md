# Static Eve — measurements

Every figure here was produced in this lane, on this box, by a probe whose source and raw output are committed in
`doc/static/probes/`. Arms that are compared ran in the same JVM process on the same data.

**Provenance (all sections).** Tree: `SeniorCareMarket/eve` `4a92129` (release 0.9.3.1) plus `doc/static/**` only —
`git diff 4a92129 HEAD -- . ':!doc/static'` is empty. Tools on the PATH: OpenJDK 21.0.12, Clojure 1.12.0 (jar from
Maven Central; no `clojure` CLI, no `bb` on the box), transit-clj 1.0.333 / transit-java 1.0.371. Box: 4 cores, 15 GB,
Linux; load1 0.8–0.9 when the timed probes started (census agents were reading files in the background — they wait on
the network, they do not compute). Dates: 2026-11-11 3:58 PM – 2026-11-12 12:30 AM ET. Timings are the minimum of 5–9
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
| map 10k int → int | 107,780 | 137,786 | **59,235** (23,972) | 220,007 | 180,035 | — | — | — | 180,035 (71,403) |
| vector 100k ints | 588,891 | 588,891 | **368,549** (211,988) | 1,100,007 | 900,036 | — | — | — | 400,040 (138,413) |

Ratios to Transit MessagePack: the flat codec 1.8–3.7×; packed with everything on **0.55–0.86×** on the four
Eve-shaped corpora; **3.04×** on the int → int map and 1.09× on the int vector (the two shapes the prototype has no
compact form for: a map whose keys are not symbols keeps a tag and a 4-byte payload per integer plus two 16-bit
offsets; dense i32 loses to MessagePack's 1–3-byte small integers).

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
| host vector of maps | — | — | — | 22.90 | 16.21 | 0.052 | 59.4 |
| Transit MessagePack, whole value | — | 214.5 | 4.03 | — | — | — | — |
| Eve flat codec, whole value | — | 104.8 | 2.20 | — | — | — | — |
| packed 4 KiB | 9.0 | 0.054 | 0.85 | 18.29 / 90.67 | 24.01 | 0.985 / 6.27 | 151.5 |
| packed 16 KiB | 7.3 | 0.258 | 0.80 | 1.95 / 26.74 | 3.67 | 0.698 / 9.76 | 119.2 |
| packed 32 KiB | 7.3 | 0.677 | 0.80 | 1.54 / 11.07 | 2.95 | 0.638 / 8.85 | 101.4 |
| **packed 64 KiB** | 7.0 | **1.389** | 0.81 | **1.38** / 6.02 | **2.67** | 0.680 / 9.49 | **88.1** |
| packed 256 KiB | 7.3 | 5.101 | 0.82 | 1.34 / 2.60 | 2.46 | 0.618 / 9.84 | 75.8 |
| packed 1 MiB | 7.2 | 20.475 | 0.82 | 1.25 / 1.58 | 2.38 | 0.594 / 9.23 | 60.2 |
| packed, one chunk | 7.7 | 41.190 | 0.82 | 1.29 / 1.45 | 2.44 | 0.575 / 8.71 | 49.3 |

- A column scan over packed bytes is **~17× faster than the host's vector of maps** (1.3–1.4 ns/row against 22.9);
  a keyword-equality count ~6× (2.4–2.7 against 16.2).
- Whole-chunk decode to plain rows: 0.8 µs/row — 5× Transit MessagePack's 4.03 and 2.7× the flat codec's 2.20.
- Assembling one row into a fresh plain map costs 0.6–1.0 µs with the chunk's tables open — 11–19× a host `nth`,
  because it builds a map. Reading one cell is 49–152 ns against the host's 59.
- What chunk size changes: the decode unit (1.4 ms at 64 KiB, 20 ms at 1 MiB) and the fixed cost of opening a chunk
  (≈ 7 µs in the prototype, which decodes every symbol eagerly) — 4 ns/row of a cold scan at 64 KiB, 0.3 at 1 MiB.
  Warm scans and point reads are flat from 32 KiB up.

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
domain) — the run claim is 5–12× the per-block path net of the domain. Pre-sizing the domain does not help the direct
writer (130.19 → 128.14 ms on the first row): its cost is not growth.

Readings:

1. **Out of a slab**, a relocating block copy is 1.8–6.3× `eve->clj` (90–300 ns a block), and its result is still
   operable and still placeable.
2. **Into a slab**, placing a pre-laid-out image by claiming one run of blocks per size class costs 1–3.5 ms net on
   shapes whose build from plain data costs 72–883 ms. Placement, not planning — but see M6 for what a commit adds.
3. **The allocator is most of today's build.** The same writers allocating from a bump buffer instead of the slab,
   then compacted and placed, finish in 0.25–0.64× the time — with no change to any writer.

Point reads, 100,000 random (P3): host collection 2.0–2.7 ms; slab value 18–54 ms; image through the slab-I/O
protocol 16–60 ms. Reading Eve's block layouts in place costs 7–27× a host collection, in a slab or out of it.

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
Dictionary codes are in M4: a keyword column counted at 2.4–2.7 ns a row, one cell read at 49–152 ns.
