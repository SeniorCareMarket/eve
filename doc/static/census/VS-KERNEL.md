STATUS: PARTIAL — banked early. Sections 0–2 are from code read in full (alloc.vs all 4,859 lines; eve.vs page/adopt/serialize parts; tier.vs; mmap.vs). Remaining to finish: §1 leftovers (export check, other alloc docs), §2 leftovers (vs.net/*, eve_disk.vs, vs.merkle, the policy evaluator's invariant-L enforcement), §3 (bnd / hmemo / thread_word / lazy / vec_conj_lin, memcost numbers), §4 (interop.vs, canonical Eve adapters), §5 (intern.vs, kval.vs, record type boxes), §6 (eve-extraction docs, deps.edn, docs grep), then Kill/support evidence for A8 and A9.

# VS kernel census — allocator, pages, off-allocator construction, type identity

Read-only census. Pins: `atom.broker` at `163cb656c` (main tip, 2026-11-11 1:58 AM ET), `eve` at `4a92129` plus the
research-lane commit `f605cd8` on top (doc only). Citations are `atom.broker:<path>:<line>` or `eve:<path>:<line>`,
paths relative to the repository root. Provenance tags: **code read** (the source lines were read this session),
**doc read** (a document says so; the code was not checked), **computed here** (arithmetic done in this session by
transcribing the cited formulas; nothing was executed in the kernel), **not found** (searched; the search is named).
Nothing in either tree was built, run or modified.

## 0. The standing rules, verbatim

| rule | sentence (verbatim) | where |
|---|---|---|
| rule 12, title | "eve memory-model invariants — never zero, never arena, free lists out-of-band. eve forgets memory; it does not scrub it." | atom.broker:CLAUDE.md:364 (rule 12 runs 364–443) — doc read |
| rule 12 | "No arenas. No scratch buffers. Both are forms of 'hold memory aside and reuse the bytes' that fight the forget model." | atom.broker:CLAUDE.md:364–443 — doc read |
| rule 12 | "Free lists are external / out-of-band, never intrusive." | same |
| rule 12, the 2026-10-29 carve-out | "a per-thread scratch (a call temporary's block, a worklist chunk kept aside) is allowed when it (a) is a `ThreadUnsafe…` global of the wasm instance, never shared and never published; (b) holds no value and is never reachable from one — no reader, no slot, no count names it; (c) is fully overwritten by each use before any read (never zeroed); (d) is thrown away by its thread when done, never adopted by the allocator's lifetime rules or the tally's walk. Anything that needs the tally, the epoch, or a free list to know about it is an arena and is not built." | atom.broker:CLAUDE.md:364–443; the same block at atom.broker:vs/docs/memory-model.md:131–144 — doc read |
| rule 12 | "Thread-local-memory-backed structures must be named `ThreadUnsafe…`." | atom.broker:CLAUDE.md:364–443 — doc read |
| rule 26 | "No out-of-band stand-ins for eve or VS functionality. Ever." … "a request to build something in eve or VS is a request for blocks with headers in the atom — state in atoms, identity in the ExtCID, transitions as claims the gate evaluates. A JSON blob, a sidecar map, a value envelope, a side record standing in for a header field: none of these is built" | atom.broker:CLAUDE.md:967 ff. — doc read |
| memory-model, mechanism | "A block whose count reaches zero is only queued on the zero queue, under the epoch. The flush frees it once no pinned reader can reach it, and only if its count is still zero. … A free reclaims the descriptor and forgets the blob." | atom.broker:vs/docs/memory-model.md:8–11 — doc read |
| memory-model point 6 | "Nothing is zeroed; the next owner of the block overwrites every field it uses — the builder initialises every field it reads" | atom.broker:vs/docs/memory-model.md:72–74 — doc read |
| memory-model, what it does not contain | "a collector of any kind, an arena, zeroing — or a scratch buffer, but for rule 12's per-thread scratch below." | atom.broker:vs/docs/memory-model.md:129 — doc read |
| THE TALLY | "Counts and counted bits live in a side table beside the blocks, never in them, and update atomically." | atom.broker:vs/docs/memory-model.md:167–168 — doc read |
| THE TALLY, scopes | "A scope is an atom of the runtime domain; its state holds what it keeps and the record of what it allocated. The toss drops it: a recorded block no holder took is freed; a held one stays (merged, for free). Exact: counts decide, nothing is scanned. **Every form is a scope (L24, 2026-10-24).**" | atom.broker:vs/docs/memory-model.md:173–176 — doc read |
| contention domains | "Storing a foreign value into an atom copies it in. There are no foreign references inside an atom — its tree is wholly its own." | atom.broker:vs/docs/contention-domains.md:26–27 — doc read |
| contention domains §6 | "VS is its own host — there is no heap underneath — so the runtime domain is where heap-like economics must live if they live anywhere." and "**Laxity is cost-only.** No observable program behavior … may depend on whether the runtime domain used its heap-like internals or strict slab discipline." | atom.broker:vs/docs/contention-domains.md:144–154 — doc read |
| contention domains §8 | "A count never lives on a remotely-owned page: cross-domain bare references do not exist (a foreign value is refused at `swap!`; a crossing copies)." | atom.broker:vs/docs/memory-model.md:166–167 and contention-domains.md:205–207 — doc read |
| ExtCID v3 | "The **own header** is the first 256 bytes of every block in the slab. The **slot** is the 64-byte copy of a child's core that a parent holds for each child it references." | atom.broker:vs/docs/extcid-v3-format.md:11–12 — doc read; constants `EXTCID_OWN 256`, `EXTCID_CORE 64` at atom.broker:vs/src/vs/kernel/wasm/extcid.vs:17–18 — code read |
| invariant C | "The body starts at 256 for every ExtCID-headed block … Flat value blocks (J) have no ExtCID header; their payload starts at 16." | atom.broker:vs/docs/extcid-v3-format.md:68–69 — doc read |
| invariant J | "Scalars are blocks, integers included; collections hold blocks, never values. … A scalar is a flat value block of its kind … a 16-byte type-CID followed by the payload, with no ExtCID header and no SHA band of its own. … A program's static literal is such a block, owned by the program and never asked of the allocator." | atom.broker:vs/docs/extcid-v3-format.md:85–91 — doc read |
| invariant L | "A wire page is a value page. A client or a dialect posts pages it built for the post, holding exactly the blocks the posted root reaches that the receiver does not hold (deferred to by digest otherwise), with addresses rewritten and digests unchanged. A page of the program's own memory is never posted or served: it carries whatever the program held." | atom.broker:vs/docs/extcid-v3-format.md:97–100 — doc read |

## 1. The kernel allocator (`atom.broker:vs/src/vs/kernel/wasm/alloc.vs`)

### 1.1 Granularities

| unit | constant = value | where | provenance |
|---|---|---|---|
| wasm page (the `memory.grow` unit) | `WASM_PAGE_SIZE` = 65,536 | atom.broker:vs/src/vs/kernel/wasm/alloc.vs:106 | code read |
| slab header band (metadata, never allocated from) | `HEADER_SIZE` = 65,536; "first 64 KB reserved for metadata" | alloc.vs:67, :45 | code read |
| **region** (the allocator's claim unit; "page" in most comments means a region) | `REGION_SIZE` = 1,048,576 ("1 MB per region") | alloc.vs:105 | code read |
| region count ceiling | `LAST_SOUND_REGION_SLOT` = 2046 → 2,047 regions; offsets are signed i32, so the slab ends at 2 GiB | alloc.vs:107–121 | code read |
| linear memory | one shared memory, 1..32,768 wasm pages: `(wasm/import env/memory :memory 1 32768 :shared :as data)` | atom.broker:vs/src/vs/kernel/wasm/kernel.vs:78 | code read |
| **sub-page** (a bitmap-class page smaller than a region) | 65,536 / 262,144 / 1,048,576, picked by how many pages the thread already owns in the class: "the k-th page I claim in the class is 64 KB, then 256 KB, then 1 MB" | alloc.vs:1939–1948 (the pick), :2049–2070 (the clamp), :2099–2116 (one region cut into equal sub-pages, the first owned, the rest orphan), :2416–2439 `page_of`, :2466–2479 `region_sub_size` | code read |
| page header | `PAGE_HDR_SIZE` = 64, `PAGE_MAGIC` = 1346455365 ('PAGE' LE), `PAGE_HEADER_VERSION` = 5 | alloc.vs:161, :162, :197 | code read |
| region claim | `claim_region()` — one atomic add on `REGION_CTR_OFF` (header cell 0) then `memory.grow`; `claim_regions(k)` — k consecutive regions in one atomic add | alloc.vs:742–763, :773–789 | code read |
| "superpage" | no such term in alloc.vs | grep pending (see §1.7 list) | — |

Layout of every bitmap-class page, from the header comment: "[header 64][bitmap][return queue][pad][descriptor columns][blocks]" (alloc.vs:153). A class-15 page is "[0..63] PHV2 page header · descriptor columns · return queue · data region" (alloc.vs:262–271, :2956–2961).

### 1.2 Size classes

`CLASS_COUNT` 16, `OVERFLOW_CLASS` 15 (alloc.vs:60–61). `size_to_class` (alloc.vs:533–549) and `class_to_block_size` (alloc.vs:555–571) — code read. The blocks-per-page columns are **computed here** by transcribing `layout_blocks` (alloc.vs:668–682), `bm_bytes_for` (:697–702), `ret_bytes_for` (:651–660) and `desc_bytes_for` (atom.broker:vs/src/vs/kernel/wasm/tally.vs:2600–2601); they were not executed in the kernel.

| class | block size (B) | request range (B) | blocks in a 64 KiB sub-page | in a 256 KiB sub-page | in a 1 MiB page (data offset) |
|---:|---:|---|---:|---:|---|
| 0 | 4 | ≤ 4 | 2,696 | 10,804 | 43,228 (875,632) |
| 1 | 8 | 5–8 | 2,315 | 9,274 | 37,108 (751,680) |
| 2 | 16 | 9–16 | 1,804 | 7,228 | 28,920 (585,840) |
| 3 | 32 | 17–32 | 1,252 | 5,014 | 20,065 (406,496) |
| 4 | 64 | 33–64 | 776 | 3,110 | 12,444 (252,160) |
| 5 | 128 | 65–128 | 441 | 1,767 | 7,072 (143,344) |
| 6 | 288 | 129–288 | 212 | 850 | 3,401 (68,992) |
| 7 | 576 | 289–576 | 109 | 439 | 1,758 (35,712) |
| 8 | 832 | 577–832 | 76 | 307 | 1,230 (25,024) |
| 9 | 1,088 | 833–1,088 | 59 | 236 | 946 (19,280) |
| 10 | 1,600 | 1,089–1,600 | 40 | 161 | 647 (13,232) |
| 11 | 2,112 | 1,601–2,112 | 30 | 122 | 491 (10,048) |
| 12 | 3,200 | 2,113–3,200 | 20 | 81 | 325 (6,704) |
| 13 | 4,224 | 3,201–4,224 | 15 | 61 | 247 (5,104) |
| 14 | 10,240 | 4,225–10,240 | 6 | 25 | 102 (2,176) |
| 15 | variable ("coalescing overflow") | > 10,240 | no sub-pages ("class 15 has no sub-pages", alloc.vs:4546) | — | one 989,600-B data region, ≤ 1,636 descriptors |

- **Max fixed block: 10,240 B** (`size_to_class` returns −1 above it, alloc.vs:548–549).
- **Doc/code disagreement.** The alloc.vs header comment (alloc.vs:7–39) and `atom.broker:vs/docs/reclaim/size-classes.md:46–75` describe what lands in each class under "256-byte ExtCID v2 child slots" (e.g. "14: 10240 B — HAMT 32-child (8220), full 32-slot vec leaf"; "A map node is `269 + 256·children + …`", size-classes.md:71). The code at this pin uses 64-byte slots (`EXTCID_CORE 64`, extcid.vs:18), and eve.vs's own measured table is "class 7 (576 B) cap 4 · class 8 (832) cap 8 · class 9 (1088) cap 12 · class 10 (1600) cap 20 · class 11 (2112) cap 28 … class 12 (3200) cap 32" for a vector node (atom.broker:vs/src/vs/kernel/wasm/eve.vs:1384–1387). The class sizes are unchanged; the occupancy notes in the header comment and in size-classes.md (dated 2026-10-06) are pre-v3.
- size-classes.md also says classes 0–2 "hold nothing a program makes" and that `$alloc_sized` "rounds every size ≤ 64 B up to class 4" (size-classes.md:54–56) — the latter is code read at atom.broker:vs/src/vs/kernel/wasm/program_runtime.vs:748–749.

### 1.3 Requests larger than the fixed classes; contiguous runs

`alloc_block(class)` refuses class 15 ("Class 15 is served by coalesc_alloc_in_page, not here", alloc.vs:1990–1992). The size router is one layer up, `alloc_sized` (atom.broker:vs/src/vs/kernel/wasm/program_runtime.vs:695–755, code read; twins named `alloc_sized` also exist at boot.vs:13578 and closures.vs:427, not read):

| size | served by | where | freed? |
|---|---|---|---|
| ≤ 64 B | class 4 (64 B) | program_runtime.vs:748–749 | yes, `free_block_off` |
| 65–10,240 B | `size_to_class` → `alloc_block` | program_runtime.vs:750–754 | yes |
| 10,241–983,040 B | **class 15**: with an allocator id `coalesc_alloc_owned(my, size)` (no lock); with none, the shared protocol under the one class-15 spinlock (`HSC_LOCK`, header cell 584) | program_runtime.vs:716–747; alloc.vs:4514–4541, :4550–4577, :4387–4397 | yes (`coalesc_free_in_page`, alloc.vs:3289–3323) |
| > 983,040 B | `claim_regions(ceil(size / 1 MiB))`: consecutive raw regions, "no page header is written, so no allocator hands a block out of them" | program_runtime.vs:712–715; alloc.vs:765–789 | **never** ("they stay claimed", program_runtime.vs:756–764) |

Class 15, code read:
- one page = one whole region (`alloc_coalesc_page`, alloc.vs:2955–3057); descriptors sized "~1/20th the page", `coalesc_max_descs_for_page` (alloc.vs:2926–2933) → 1,636 for 1 MiB; `coalesc_data_off` (alloc.vs:2939–2946) → 58,976; data region 1,048,576 − 58,976 = **989,600 B** (computed here; the header comment says "For a 1 MB page: budget = 52368, maxDesc = … = 1636", alloc.vs:295–297).
- allocation is "first-fit descriptor scan + split": "size is rounded up to 4-byte alignment … first FREE descriptor with capacity >= size wins … If remainder >= 32 B, splits into a new descriptor" (alloc.vs:3059–3072; body :3073–3172). A free marks the descriptor FREE and merges left and right neighbours at once (`coalesce_adjacent`, alloc.vs:3183–3279).
- **Largest class-15 block:** the allocator itself would hand out the whole 989,600-B data region; `alloc_sized` sends anything past 983,040 B (960 KiB) to raw regions instead (program_runtime.vs:712).
- **Can a caller get a contiguous 64 KiB run?** Yes: one class-15 block of 65,536 B. **How many fit in one 1 MiB region:** floor(989,600 / 65,536) = **15** (15 × 65,536 = 983,040 B, 6,560 B left), each using one of the 1,636 descriptors (computed here).
- **A whole page?** Not as an allocator block. A whole region can be had raw with `claim_region()` (no header, the allocator never allocates in it or frees it): the precedents are the EH1 region (alloc.vs:420–449), the crypto staging region (alloc.vs:4399–4462), the region free list's region (alloc.vs:845–890), the page table and the SHA table (eve.vs:1832–1869, :2562–2598), the tally's zero queue (tally.vs:111, :158).
- **Multiple contiguous pages?** Yes, raw: `claim_regions(k)` (alloc.vs:773–789) — used by `alloc_sized` past 960 KiB and by `(mmap/reserve! n)` (program_runtime.vs:21436–21457), which marks the run foreign ('FRGN' + count at the first region, alloc.vs:164–169; the out-of-band record `region_foreign_mark`, alloc.vs:1007–1025).

### 1.4 Ownership and page lifecycle

| fact | evidence | provenance |
|---|---|---|
| A page's owner is the `u32` at header +44: "OWNER u32 (thread id; 0 = shared)" | alloc.vs:134, :177 | code read |
| The allocator id is per wasm instance = per thread: `ThreadUnsafe_ALLOC_CURRENT_THREAD_ID`; "Hosts pass host thread id + 1 (main = 1, workers >= 2 …). 0 = UNSET: the instance takes the shared atomic path and mints owner-0 NO-QUEUE pages" | alloc.vs:396, :3442–3449, :3553–3567 | code read |
| Only the owner allocates in an owned page, with plain (non-atomic) bitmap stores: `bitmap_alloc_in_page` — "Owner-only: plain loads/stores, no atomics" | alloc.vs:2118–2188 | code read |
| Own free: plain bit clear + push onto the thread's out-of-band free list | `free_on_owned_page`, alloc.vs:3684–3692; `bitmap_free_in_page_plain`, :3647–3676 | code read |
| **Foreign free (bitmap classes)** = `ret_push`: the block's descriptor STATUS is stored PENDING, then "an atomic OR of the block's bit in the pending bitmap; then an atomic OR of the word's bit in the summary, UNCONDITIONALLY … then ONE atomic add of 1 on write." The allocation bitmap is untouched until the owner drains. "It cannot refuse for want of room: capacity is the block count." | alloc.vs:2366–2414; the queue layout :202–233 | code read |
| Doc/code note on "one atomic add": memory-model.md says "a foreign thread's block goes onto its page's return queue with one atomic add — `eve/thread_alloc.js:317-345@bb18e8e35^`" — that cites VSv1. The VSv2 path above is 1 atomic store + 2 atomic ORs + 1 atomic add (preceded by `tl_forget`'s descriptor resets, alloc.vs:3347–3351). | atom.broker:vs/docs/memory-model.md:70–72 vs alloc.vs:2388–2414 | doc read vs code read |
| Foreign free (class 15) = `rq_push`: CAS-reserve a slot on the write cursor, one plain store of `offset|READY` | alloc.vs:2294–2341 | code read |
| Drain is the owner's (or the adopter's): `ret_drain` exchanges summary and pending words with 0, clears each allocation bit, pushes the block on the free list | alloc.vs:3707–3809; class 15 `rq_drain_ex` :3817–3876 | code read |
| The owned allocation order: drain current page → free-list pop → current page → drain all my pages → free list → current page → my directory (no region walk) → adopt an orphan → mint a graduated page | `alloc_block_owned`, alloc.vs:1894–1955 | code read |
| **Lifecycle**: minted (`alloc_page_for_thread`: first sub-page stamped with the owner id, sibling sub-pages owner 0) → owned → released at thread exit (`alloc_release_thread_pages` drains then `release_page_ownership` stores owner 0) → orphan → adopted by another thread by CAS 0 → id (`try_adopt_page`, `adopt_orphan_page`) | alloc.vs:2038–2116, :4028–4077, :2767–2776, :3935–4015 | code read |
| **Transfer**: only through owner 0. There is no direct owner-to-owner hand-off; an adopter takes "only a page of its own domain" (`PHV2_LABEL`, header +52) | alloc.vs:136–138, :3995–3998 | code read |
| A bitmap-class or class-15 page is never returned to the region pool. The only regions that are reused are *adopted* (foreign) pages evicted by the page tier: `region_free_push` / `claim_region_reuse`, gated by "min_safe - stamp >= 2" and the per-region hold count | alloc.vs:791–813, :910–932, :941–982, :820–829 | code read |
| A page **adopted from another slab** has owner `PT_ADOPTED_OWNER` = 2147483646 — "no thread's id, never 0"; `free_block_off` refuses any block of such a region ("nothing of this slab is reclaimed from it"); the tally manages no block of it | atom.broker:vs/src/vs/kernel/wasm/eve.vs:416; alloc.vs:1153–1185, :3380–3382; tally.vs:290–295 | code read |

### 1.5 Free tracking

- The **bitmap is the allocation index**: "The bitmap stays the allocation index (a claim never reads or writes a descriptor)" (alloc.vs:149). Claim = lowest clear bit from a next-fit word cursor (`PHV2_NEXT_FIT`, header +60; "-1 = full since the last free"), alloc.vs:141–142, :1424–1445, :1447–1514.
- The **free list is out of band**, per thread, in that thread's EH1 region: "{offset} entries per class in this instance's EH1 region … the freed block's own bytes are never touched" (alloc.vs:87–99); layout "[header 64][16 owned-page directories x EH1_DIR_STRIDE …][16 free lists x EH1_FL_STRIDE …][the seal reserve]" with `EH1_DIR_CAP` 2,558 pages and `EH1_FL_CAP` 1,279 offsets per class (alloc.vs:420–449). A full list drops the push; the clear bit still finds the block (alloc.vs:2682–2688).
- **Descriptor columns** (the tally's per-block management record, never the allocation index): bitmap classes carry five 4-byte columns per block — STATUS, COUNT, FLAGS, SIDE, EPOCH — "block i's entry in column c at table_base + TOTAL_BLKS * 4 * c + i * 4 — the columns end where the blocks begin" (alloc.vs:144–158; `DESC_COL_BYTES_BM` 20, :316). "descriptor i is block i's, positionally, for the page's life" (alloc.vs:151–152). Class 15 has 32 bytes of columns per descriptor: STATUS, GEOM (data offset, capacity), COUNT, LOCK+FLAGS, BIRTH, EPOCH (alloc.vs:300–315).
- Rule 12 is restated in the code: "eve forgets memory; it never threads an intrusive next-pointer through a freed block" (alloc.vs:88–91); "`bitmap_free_in_page` just clears the bit; the builder that writes into a reused block is responsible for fully overwriting every live field" (alloc.vs:97–99).

### 1.6 Alignment of returned blocks

| class | guarantee | evidence | provenance |
|---|---|---|---|
| 2–14 | 16 bytes: `DATA_OFF` is 16-aligned for every block count (the descriptor columns' region is rounded to 16 with its pad *before* the columns) and every block size ≥ 16 is a multiple of 16 | alloc.vs:42–43, :153–158, :2092–2098; tally.vs:2596–2601 | code read |
| 0, 1 | 4 and 8 bytes (block sizes 4 and 8 from a 16-aligned `DATA_OFF`) | alloc.vs:556–557 | code read (the header's "every size is a multiple of 16 B" does not hold for these two classes) |
| 15 | **4 bytes**: "size is rounded up to 4-byte alignment"; a block starts at `page + data_off + Σ earlier capacities` | alloc.vs:3062, :3074, :3165 | code read |
| raw regions | 1 MiB + 64 KiB: `HEADER_SIZE + slot * REGION_SIZE` | alloc.vs:745–746 | code read |

No entry point takes an alignment argument (all signatures in §1.7).

### 1.7 Every public entry point of alloc.vs

`(wasm/defn name …)` is module-public; `(wasm/defn- …)` private (the 95 private helpers are not listed). Whether each public name is also in kernel.wasm's export section is checked in the next pass; program_runtime.vs imports many of them as `kernel/<name>` (e.g. program_runtime.vs:144, :158, :168), so at least those are exported.

| name [args] | line | semantics |
|---|---:|---|
| `size_to_class [size]` | 533 | class index for a byte size; −1 above 10,240 |
| `class_to_block_size [class_idx]` | 555 | block size of a class; −1 for 15 or out of range |
| `claim_region []` | 742 | one fresh 1 MiB region (atomic bump + grow); −1 at the ceiling |
| `claim_regions [k]` | 773 | k consecutive regions in one atomic add; −1 at the ceiling |
| `region_free_push [base stamp]` | 910 | put an evicted region on the reuse list, stamped with an epoch |
| `region_free_len []` | 936 | entries on that list |
| `claim_region_reuse [min_safe]` | 945 | oldest reusable, unheld region from the list, else `claim_region` |
| `region_free_stats [out]` | 985 | four counters |
| `region_foreign_mark [base n]` | 1007 | record n regions as a foreign run (out-of-band bitmap) |
| `region_in_foreign_run [off]` | 1033 | 1 iff off's region is recorded foreign (reads no byte of it) |
| `region_foreign_clear [base]` | 1050 | clear that record when the page table takes the region as an adopted page |
| `region_hold_add [base delta]` | 1072 | per-region hold count ±1 (a held region is never reused) |
| `region_held [base]` | 1099 | the hold count |
| `region_hold_stats [out]` | 1105 | three counters |
| `region_adopted_base [off]` | 1116 | the region base if off lies in an adopted page (owner 2147483646, magic PAGE or EVCT), else 0 |
| `region_hold_reset []` | 1138 | zero every hold count at a run's start |
| `alloc_census [class_idx out_off]` | 1647 | pages / total / used blocks of a class (region walk) |
| `alloc_c15_audit [out_off]` | 1720 | read-only class-15 census and tiling check |
| **`alloc_block [class_idx]`** | 1986 | **the** allocation primitive: one block of a bitmap class; −1 bad class or class 15, −2 failure; notes the block in the tally's open log |
| `alloc_page_for_thread [class_idx page_size owner_id]` | 2038 | claim a region and format it as 1 / 4 / 16 pages of a bitmap class; returns the first |
| `bitmap_alloc_in_page [page]` | 2118 | owner-only claim of one block in a given page; −1 full |
| `bitmap_free_in_page [page block_off]` | 2190 | atomic clear of one block's bit; 0 on double free |
| `read_page_owner [page]` | 2232 | atomic load of the owner word |
| `rt_kstat_header_u32 [off]`, `rt_kstat_page_owner [off]` | 2244, 2250 | observability |
| `read_page_rq_off [page]` | 2283 | the page's return-queue offset, 0 when it has none |
| `rq_push [rq_abs entry_off]`, `rq_read_write`, `rq_read_read`, `rq_set_read`, `rq_entry_at` | 2294–2353 | class-15 return-queue accessors |
| `page_of [off]` | 2416 | the (sub-)page holding an offset |
| `release_page_ownership [page]` | 2767 | owner := 0 |
| `try_adopt_page [page new_owner]` | 2771 | CAS owner 0 → new_owner |
| `alloc_coalesc_page [owner_id]` | 2955 | claim a region and format it as a class-15 page |
| `coalesc_alloc_in_page [page size]` | 3073 | first-fit + split in one class-15 page; −1 when nothing fits |
| `coalesc_free_in_page [page block_off]` | 3289 | free + coalesce one class-15 block |
| **`free_block_off [off]`** | 3343 | **the** free: descriptor reset (`tl_forget`), then dispatch by class and owner; 0 for a block of a foreign region or of no allocator page |
| `alloc_set_current_thread_id [tid]`, `alloc_current_thread_id []` | 3553, 3574 | this instance's allocator id |
| `rq_drain [page]` | 3704 | drain a page's return queue |
| `alloc_release_thread_pages []` | 4028 | worker exit: drain, orphan every owned page, free the EH1 region |
| `alloc_thread_stats [out]`, `alloc_page_info [off out]` | 4120, 4144 | observability |
| `alloc_blocks [cls n out]` | 4190 | "allocate n blocks, writing their offsets to out; stops at the first failure" — a **loop over `alloc_block`** (alloc.vs:4188–4201), not a reservation |
| `alloc_free_blocks [in n]`, `alloc_free_pairs [cls n]` | 4204, 4219 | loops over `free_block_off` / alloc+free pairs (benchmark rows: "the caller times it") |
| `alloc_owner_census`, `alloc_my_pages`, `alloc_track_start/stop`, `alloc_trace_on/off/read` | 4234–4358 | observability |
| `alloc_c15_lock []`, `alloc_c15_unlock []` | 4387, 4396 | the one class-15 spinlock (shared protocol only) |
| `crypto_stage [size]`, `crypto_stage_release [size]` | 4429, 4467 | a raw staging region for the crypto rows (one shared region under a spinlock; a private run of regions past 1 MiB) |
| `alloc_c15_page_for_thread [my]` | 4474 | a fresh owned class-15 page |
| `coalesc_alloc_owned [my size]` | 4514 | owned class-15 allocation (drain, current page, directory, new page) |
| `coalesc_alloc_shared_walk [size]` | 4550 | shared class-15 allocation over owner-0 pages |
| `alloc_kstat_*` (page, counters, page_format_legacy, alloc_as, alloc_from_page, free_as, block_free, page_extent) | 4604–4812 | test and export rows; `alloc_kstat_page_extent` is what the page exporter uses to cut a page at its last live block |

**Bulk / batch / reserve / adopt / placement / bump hunt.** Case-insensitive counts over alloc.vs (grep run this session): `bulk` 0 · `batch` 0 · `ingest` 0 · `graft` 0 · `presize` / `pre-size` / `pre_size` 0 · `placement` 0 · `page_alloc` 0 · `reserve` 33 (all "reserve-then-commit" of the region counter, "monotonic reserve", and `(mmap/reserve! n)`) · `adopt` 94 (orphan-page adoption between threads, and the page table's adopted pages) · `bump` 25 (the region counter is the only bump allocator here) · `span` 3 · `import` 4 · `claim` 154.

**Plain answer: alloc.vs has no entry point that reserves a run of blocks, accepts a list of {offset, length, alignment} placements, or adopts caller-laid-out bytes as allocator blocks.** `alloc_blocks` is a loop of single allocations. The nearest things, all outside block allocation:
1. `claim_region` / `claim_regions(k)` — a raw contiguous reservation the allocator will never allocate from or free (the caller owns layout and lifetime).
2. `alloc_page_for_thread` — formats a fresh region as empty pages of one class; it cannot be given pre-filled bytes.
3. The page table's adoption (eve.vs, §2): a whole pre-laid-out page image is taken *beside* the allocator (owner `PT_ADOPTED_OWNER`), never into it.
4. eve.vs's BLOCK PAGES (§2.5): a CAS bump cursor inside a region formatted as an adopted page — the one bump-style block allocator in the kernel, used only for blocks rebuilt from wire images.

### 1.8 What the allocator requires of a block's first bytes

Nothing. "Caller gets back a block offset in $data and writes fields directly at that offset — no stage-then-commit pattern" (alloc.vs:3–5); a free never reads or writes the block ("the freed block's own bytes are never touched", alloc.vs:93–94). Who writes what, and who later *reads* the first bytes:

| bytes | writer | reader that depends on them | evidence |
|---|---|---|---|
| own header 0..255 (ExtCID-headed blocks only) | the builder: `init_block_extcid` fills all 256 bytes with 0, then INNER_OFFSET = the block's own offset, VERSION 3, FLAGS ACTIVE | `_hdr_live` — "INNER_OFFSET = b (the init_block_extcid self-reference), VERSION = EXTCID_VERSION, FLAGS.ACTIVE — the structural header probe" | eve.vs:1165–1179, :2700–2726 — code read |
| type-CID 0..15 | "the collection init that follows writes the block's type-CID there (invariant B)" | the tally classifies a block's edges "by the 16-B type-CID at +0 — never by the tag @+256 of a block not known to be a root" | eve.vs:1167–1168; tally.vs:72–80 — code read |
| type tag at +256, payload from +257 | the builder (`write_block_type_tag`) | the seal's walk and every collection reader | eve.vs:1162–1163, :1183–1193 — code read |
| flat value block `[type-CID or typekey:16][payload]` | the builder (boxes, strings, keywords) | `_slot_image` tiers 2–4, `_val_kind` | eve.vs:3909–3911, :2619–2635 — code read |
| the SHA at +32 | `seal_block`, when the block goes "from 'under construction' to 'immutable + content-addressed'" | every `=`, the CAS key, the wire | eve.vs:1270–1309 — code read |
| **the first word of a region** | — | every region walk treats 'PAGE' / 'EVCT' / 'FRGN' at a region base as a page header or a foreign run: "a region base holds a page header, a foreign-run marker, or the start of a multi-region block … whose first word is its TYPE TAG, never program bytes" | alloc.vs:1160–1168 — code read. A packed chunk placed at a region base must not begin with those words. |

## 2. Pages on the wire and in storage

### 2.1 Names cited by `eve:src/eve/net/pages.cljc`, confirmed in the kernel

| name | value / signature | where | provenance |
|---|---|---|---|
| `REGION_SIZE` | 1,048,576 | atom.broker:vs/src/vs/kernel/wasm/alloc.vs:105 | code read |
| `HEADER_SIZE` | 65,536 | alloc.vs:67 | code read |
| `PAGE_HDR_SIZE` | 64 | alloc.vs:161 | code read |
| `PAGE_HEADER_VERSION` | 5 ("the same region carries per-field descriptor COLUMNS") | alloc.vs:192–197 | code read |
| `class_to_block_size` | 4 8 16 32 64 128 288 576 832 1088 1600 2112 3200 4224 10240 | alloc.vs:555–571 | code read |
| `PT_ADOPTED_OWNER` | 2147483646 — "PHV2_OWNER of an adopted page: no thread's id, never 0" | atom.broker:vs/src/vs/kernel/wasm/eve.vs:416 | code read |
| `eve_page_adopt_b64 [src len want]` | → region base or −1..−5 | eve.vs:2119–2155 | code read |
| `eve_page_b64_chunk [base from n dst]` | base64 text of n bytes of a region | eve.vs:2157–2180 | code read |
| `eve_slot_resolve [slot]` | → child offset in this slab, 0 empty, −1 not resident | eve.vs:3612–3652 | code read |
| `next_page_cid [dst_off class_idx scratch]` (private) | "seed = [salt:32][counter:8][classIdx:1] (41 B); cid = SHA-256(seed)[0..16]; cid[0] = classIdx & 0xFF" | alloc.vs:1207–1208, :1241–1271 | code read |
| page header fields | magic +0 · PAGE_CID +4..19 · class +20 · block size +24 · page size +28 · total blocks +32 · bitmap offset +36 · data offset +40 · owner +44 · version +48 · label +52 · queue offset +56 · next-fit +60 | alloc.vs:124–142, :170–181 | code read |

### 2.2 What `eve_page_adopt_b64` does, exactly (eve.vs:2127–2155, code read)

1. If `want` (a 16-byte CID) is already in the CID-to-region table → return that region, nothing claimed (eve.vs:2131–2134).
2. Claim **one whole fresh region**: from the region free list when it has an entry past its epoch gate (`claim_region_reuse`), else the bump (`claim_region`) (eve.vs:2135).
3. **Decode the base64 text straight into that region** — "decoded straight into a freshly claimed region (eve_b64_decode, no staging copy)" (eve.vs:2122, :2137). This is the one pass over the bytes: a base64 decode, byte by byte (eve.vs:2094–2117). There is no per-block step.
4. Check the bytes are a page: ≥ 64 bytes, `PAGE_MAGIC` at +0, and (when `want` is given) the header CID equals `want` (eve.vs:2138–2149). A refusal marks the region foreign ('FRGN') and returns it to the free list (`_pt_release_refused`, eve.vs:2064–2066).
5. **Register** the region under its page CID in the CID-to-region table (`eve_page_register`, eve.vs:1989–2045): an open-addressed table of `PT_SLOTS` 16,384 entries in a region of its own (eve.vs:1774–1783, :1800–1803). Registration stamps every page header in the region with owner `PT_ADOPTED_OWNER` (`_pt_own_region`, eve.vs:1966–1982).

After adoption:
- **The allocator never allocates into it.** "A registered region is ADOPTED: its PHV2_OWNER becomes PT_ADOPTED_OWNER, no thread's id and never 0 — the allocator's walks (scan_pages_for_free, adopt_orphan_page) hand nothing out of it and no thread adopts it as an orphan (L14 F5)" (eve.vs:1986–1988). The free tail of an adopted page is dead space for the page's life.
- **Nobody may free a block in it.** `free_block_off` answers 0 for any block of an adopted, evicted or refused region (alloc.vs:3380–3382, `region_foreign_p` :1153–1185); the retire diff and the tally skip it (tally.vs:290–295).
- **It leaves memory whole.** `eve_region_evict` turns the header magic to 'EVCT' by one CAS — "no other byte is written (rule 12)" — and `eve_region_release` puts the region on the free list under the eviction's epoch stamp; it is reused only when `min_safe − stamp ≥ 2` and no form holds a value in it (eve.vs:2201–2235; alloc.vs:941–982, :820–829).
- **Children are found by page CID, not by address.** A slot names its child by PAGE_CID@0 and INNER_OFFSET@16 "(its absolute offset in the slab that wrote it; its page-relative offset is inner − the region base of inner, the same arithmetic in every slab: HEADER_SIZE + k * REGION_SIZE)". `eve_slot_resolve`: no CID → local; region at INNER_OFFSET carries that CID → the child is where the slot says; else the table → "that region + the page-relative offset"; else −1 and `eve_slot_child` throws the page fault (eve.vs:1752–1762, :3615–3652, :3741–3746). So adoption rewrites nothing: a page lands at any region and every pointer into it is rebased at read time by one table probe.
- The other adopter: `eve_page_register(base)` alone, for a region the *host* filled — "A TIER THAT MAPS … the tier may answer a NUMBER, the base of a region where it mapped the page itself (a page file past 2 GiB remapped over a window slot); that region is registered under its CID" (program_runtime.vs:26349–26351, :26382–26387). With `vs.mmap/map-value-page!` (mapped PRIVATE) this is a **zero-copy** adoption: "the program registers the page in its CID-to-region table, the kernel stamps its header (owner; the eviction mark) in memory only, and the file is never written through the mapping" (atom.broker:vs/src/vs/mmap.vs:308–314; atom.broker:vs/src/vs/tier.vs:651–668).

### 2.3 What a "wire page" is, as the VS writer builds it (`vs.tier/write-pages`, atom.broker:vs/src/vs/tier.vs:178–379, code read)

- **Size and shape:** an allocator page image of one size class, cut after its last block: "[64-byte header][bitmap][descriptor columns][blocks], the prefix a reader adopts (eve_page_adopt_b64), exactly the layout eve.net.pages writes" (tier.vs:184–186). The header is written with page size 1,048,576, the block count, the data offset, owner `PT_ADOPTED_OWNER`, version 5 (tier.vs:366–372). Carried as base64 text (tier.vs:377).
- **Which blocks go in:** a walk from the root "through every slot, never entering a block whose page the receiver holds (held?, a page CID's 32 hex digits …)" (tier.vs:182–184, :317–343). Blocks are grouped by class, `-page-cap` blocks per page (tier.vs:345–348).
- **Address rewriting:** "Only addressing moves: a headed block's INNER_OFFSET becomes its place in its new page, and a slot that names a block written here takes that block's new page CID and offset; a slot that names a held page is kept as it is. The SHA, the attested band, the root's stamp and every slot's image are the bytes the kernel sealed, so no digest moves" (tier.vs:186–189; the code :356–365). Pages are placed "from region 16 of the writer's address space" (tier.vs:344, :350).
- **Deferred to by digest:** a held block is not written; the slot that names it keeps the receiver's page CID and the 32-byte image (tier.vs:333–336; extcid-v3-format.md:103–105 — doc read).
- **Refusals:** "a block of the coalescing class (15) is past the page writer's classes", a list, a relaxed vector node, "a block outside every allocator page", "a slot names a page this slab does not hold" (tier.vs:250, :254, :325–327, :339).
- **The other export, not for the wire:** `page-text` exports a region as the slab holds it, through its last live block (`page-extent`, `alloc_kstat_page_extent`), for local stores only (tier.vs:110–163; HEADER-RESTORE-STATUS.md:9277).

### 2.4 Cost evidence (docs and comments only; nothing was run here)

| measurement | where | provenance |
|---|---|---|
| One chat write: the page writer's record "17,712 B of base64. Eve's writer: 8,048–15,456 B. page-text for the same write: 7,798,000–8,886,000 B." | atom.broker:aegis/docs/extcid-v3/HEADER-RESTORE-STATUS.md:9301–9302 | doc read |
| "run 7: client.wasm posted ~10 MB a write, 9-10 pages near a whole region each, where eve's writer posts 8-15 KB" (before the writer) | atom.broker:vs/src/vs/tier.vs:180–182 | doc read (comment) |
| "a 4-key-map program's 31 pages exported as whole regions, 43,341,224 B of base64" (before the extent cut) | tier.vs:115–116 | doc read (comment) |
| `-page-cap` walked "~15,000 candidates a write (MEASURED 2026-10-26: 5.3 ms of a chat post's client)" before its bound was tightened | tier.vs:272–279 | doc read (comment) |
| Dialect round trips: "bb post 8565 ms · client.wasm render 4629 ms"; "client.wasm post-message 3215 ms · bb read 16174 ms" | HEADER-RESTORE-STATUS.md:9253–9254 | doc read |
| "lpy reads an 8.7 MB /deref in about 168 s, 150 s of it the outer base64" | HEADER-RESTORE-STATUS.md:9293 | doc read |
| A base64 host frame: "a 266,668-byte string: 88-200 ms checked a character at a time, 12.6 ms by the scan" | atom.broker:vs/src/vs/host.vs:69–73 | doc read (comment) |
| **No measurement of adoption speed** (bytes/s of `eve_page_adopt_b64`, or of a mapped `eve_page_register`) was found in the files read so far. | — | not found (so far) |

### 2.5 The second ingest path: blocks by content (`eve_block_adopt`) and BLOCK PAGES

A block can also arrive as its **wire image** — "THE SEAL IMAGE seal_block hashed ([tag][payload], each inner slot as its 32-byte image …)" (eve.vs:3078–3085) — keyed by its SHA-256. `eve_block_adopt [src len key]` (eve.vs:3560–3597, code read) does, **per block**: SHA-256 the image and compare with the key; measure, then allocate a block of the right class; `init_block_extcid`; **expand** the image back to the block layout (each 32-byte slot image becomes a 64-byte slot, `_adopt_expand`, eve.vs:3337–3430); rehydrate every slot (`_adopt_slots`: a scalar's inline image becomes a fresh 32-B box, any other becomes a reference by content, eve.vs:3129–3164); **re-seal** and compare with the digest; check canonical form; insert in the SHA table (eve.vs:3452–3558). That is a copy, an expansion and two hashes per block — per-value planning in full.

Where those rebuilt blocks live is the kernel's one bump allocator: **BLOCK PAGES** — "An adopted block (eve_block_adopt) and every scalar box _adopt_slot makes for its slots live in a BLOCK PAGE: a region written as an ADOPTED page, so the page tier's machinery governs it unchanged … One current page per class 0..14 … a full page is replaced. A block page is never freed block by block … it leaves memory whole" (eve.vs:2810–2822). `_bp_alloc(cls)` moves the page's cursor (the header's NEXT_FIT word, reused) by CAS: `cur → cur + block size` (eve.vs:2914–2951); the page header has `TOTAL_BLOCKS` 0 and `BM_OFF` 0 — no bitmap, no descriptors (eve.vs:2884–2912). alloc.vs knows the shape: "a page with no block index (TOTAL_BLOCKS 0 or BLOCK_SIZE 0: eve.vs's BLOCK PAGES, bump-filled)" (alloc.vs:4809–4810).

Status of this path at the pin: "THE NETWORK IS NO TIER (EM6). … The block tier that served a reference-by-content fault … is deleted with it" (tier.vs:17–21); the kernel functions remain and `vs.core/rt-block-adopt!` still calls `eve_block_adopt` (program_runtime.vs:27168–27179).

### 2.6 Invariant L's enforcement — pending (the policy evaluator's code has not been read yet)

## 3. Off-allocator / thread-local / ephemeral construction today — partial

### 3.1 The constraint that shapes everything: one linear memory

- "All values live in linear memory — one shared memory, defined in the module (never imported), at least 268 pages and at most 32,768 … Nothing a program computes lives outside the slab." (atom.broker:vs/docs/architecture.md:60–64 — doc read)
- Thread-local state in VS means **wasm globals of the instance** ("Wasm globals are per instance = per thread", alloc.vs:376) plus regions of the shared memory that only one thread names (the EH1 region). There is no second, per-thread linear memory for values.
- Raw pages cannot simply be grown past the slab: "the allocator addresses its regions by counter (alloc.vs REGION_CTR x REGION_SIZE), so raw pages grown in its path would be claimed under it" (atom.broker:vs/src/vs/kernel/wasm/boot.vs:7558–7559 — code read, comment). Off-allocator memory therefore comes from (a) the 64 KiB header band, (b) a claimed region, (c) the module's data segments, or (d) raw pages grown by a process that will exit before another region is claimed (the link arena, below).

### 3.2 `ThreadUnsafe…` structures (definitions; `grep -rn "(wasm/global ThreadUnsafe" vs/src`)

| name | file:line | what it holds | memory | lifetime |
|---|---|---|---|---|
| `ThreadUnsafe_ALLOC_CURRENT_THREAD_ID` | alloc.vs:396 | this instance's allocator id | wasm global | the instance |
| `ThreadUnsafe_ALLOC_MY_PAGE_0..15` | alloc.vs:403–418 | current page per class | wasm globals | until the id changes or the thread exits |
| `ThreadUnsafe_ALLOC_EH1` | alloc.vs:438 | base of this thread's **EH1 region**: 16 owned-page directories, 16 out-of-band free lists, the 1,664-B seal reserve — "it holds page and block OFFSETS only, never a value (rule 5)" | one raw claimed region (1 MiB), rooted only in this global | run-scoped; freed at thread exit (`alloc_release_thread_pages`, alloc.vs:4069–4072) |
| `ThreadUnsafe_ALLOC_ST_*` (11), `_TRACK_ON`, `_TRACK_A0..15`, `_TRACK_F0..15`, `_KSTAT_*` (3) | alloc.vs:454–517 | counters | wasm globals | the instance |
| `ThreadUnsafe_ALLOC_MY_C15_PAGE`, `_C15_CURSOR`, `_C15_REARM` | alloc.vs:503–511 | owned class-15 page and scan cursor | wasm globals | the instance |
| `ThreadUnsafe_EVE_DEFER_SEAL`, `_SEALS`, `_SEAL_BITS`, `_PM_DEPTH`, `_READ_BLOOM` | eve.vs:403–429 | per-thread flags and counters; the read Bloom is a caller's 512-B block | wasm globals | the instance / a `swap!` |
| `ThreadUnsafe_EVE_HOLD_TOP`, `_HOLD_DEPTH` | eve.vs:425–426 | this thread's hold list (regions of adopted pages a form holds) | chain of class-9 slab blocks (`rt_alloc_manual 9`, eve.vs:2300–2314) | a unit (form / task / request) |
| `ThreadUnsafe_EVE_SEAL_EMERG_M`, `_T` | eve.vs:967–968 | busy flags of **the seal reserve** — "Rule 12's per-thread scratch, all four conditions: rooted only in ThreadUnsafe_ALLOC_EH1, never published; it holds no value and nothing names it; every use overwrites what it reads …, never cleared; thrown away with the EH1 region at thread exit" | 1,664 B inside the EH1 region | engaged only when a seal's own allocation fails (eve.vs:943–966) |
| `ThreadUnsafe_TALLY_LOG`, `_QUIET`, `_SCOPE`, `_DEPTH`, `_SDEPTH`, `_PINS`, `_REQ`, `_FLUSHING`, `_TRACE` | tally.vs:191–198, :221 | the open allocation log, the scope stack's top and depth, pins | wasm globals naming slab blocks | per form / per write attempt |
| `ThreadUnsafe_TALLY_SSEG`, `_SSP` | tally.vs:205–206 | **the scope frame stack**: 20-byte frames "[keep @+0][parent @+4][depth @+8][marker @+12][inline @+16] … raw words only: no frame word owns a count or is read as an edge, and no value names a frame" | class-14 slab blocks (511 frames each) allocated with `tl_alloc` (never logged, never counted) | thrown away by `tally_thread_release` (tally.vs:1958–2011) |
| `ThreadUnsafe_TALLY_CSTK5`, `_CSTK6`, `_CSTK14` | tally.vs:215–217 | **the chunk stack**: empty worklist chunks kept aside for reuse — "this thread's own backing, named by no value … A take writes every header word …; nothing is zeroed" | class-5 slab blocks of raw chunk offsets | thrown away by `tally_thread_release` |
| `ThreadUnsafe_BND_TOP`, `_N`, `_GEN` | atom.broker:vs/src/vs/kernel/wasm/bnd.vs:26–29 | the per-thread binding stack | slab blocks of [name value] pairs (kernel.vs:117–119) | — (bnd.vs body not read yet) |
| `ThreadUnsafe_HMEMO_TAB` | atom.broker:vs/src/vs/kernel/wasm/hmemo.vs:37 | per-thread hash memo table | — (not read yet) | — |
| `ThreadUnsafe_THW_RAND`, `_TXN`, `_BASE`, `_MC_PREC`, `_MC_MODE`, `_READ_MARK`, `_MEMO_BDM` (512 KB), `_MEMO_DBL` (64 KB), `_MEMO_LNG` (64 KB), `_MEMO_FNV` (8 KB) | atom.broker:vs/src/vs/kernel/wasm/thread_word.vs:45–54 | rand state, STM transaction, math context, four direct-mapped memo tables | wasm globals; the memo tables' backing not read yet | the instance |
| `ThreadUnsafe_EC_VLAST`, `_EC_RAND`, `ThreadUnsafe_EC_V_eve_hamt_util__*` (15), `_EC_S_0aab5c30` | atom.broker:vs/src/vs/kernel/wasm/eve_core.vs:629, :3237; eve_hamt_util.vs:109 ff. | eve-core's per-thread vars (generated) | wasm globals | the instance |

Per-instance globals that are thread-local but **not** `ThreadUnsafe`-named (found while reading): `rt_manual_hold` (atom.broker:vs/src/vs/kernel/wasm/retire.vs:876), `VECT_CURRENT_THREAD_ID` (vec_transient.vs:139, "NO LONGER READ by any guard"), `SARR_CURRENT_THREAD_ID` (sarr.vs:42).

### 3.3 Other memory outside allocator blocks (found so far)

| what | where it lives | evidence | provenance |
|---|---|---|---|
| Unboxed numeric locals and temporaries (the "rung": raw f64 / i64 / i32) | **wasm locals** of the compiled frame; "a form the analyzer proves allocates nothing (literals, locals, the unboxed arithmetic of the rung) opens none" | atom.broker:vs/docs/memory-model.md:178–179; atom.broker:vs/src/vs/jit_wasm.vs:9297–9298, :9388–9411, :9452–9456 | doc read + code read |
| Baked string leaves | the module's own data segments: "a baked leaf (the template's own data segments, 16-aligned CID words — the interned-leaf layout)"; "a program's static constant (a string literal's block in the template's data, which a v3 slot names where v2 embedded the value)" — on no allocator page, so `free_block_off` refuses it and the tally never counts it | boot.vs:3231–3232; alloc.vs:2441–2447, :3383–3392 | code read (comments) |
| A linked program's own literals | "its bytes ride a passive data segment, and the linked `_start` re-interns them (kernel string_intern) before the first form" — i.e. copied into allocator blocks at start | boot.vs:817–821 | code read (comment) |
| **THE LINK's arena** — a real bump arena, for the linker's working data only | "Everything the link allocates lives in raw pages grown past the compile's memory, never the slab"; "the arena: raw pages past the snapshot bound — the template + up to 256 MB of work"; "THE LINK's arena is a process that exits, never the resident one" | boot.vs:830–831, :12637–12648, :11662–11663 | code read |
| Slab header band cells (the first 64 KiB): registry roots, locks, `EVE_BOOT_NAME_BUF` 7240, `EVE_REG_EXTCID_BUF` 7272 … | fixed offsets below 65,536; "Every cell below 65,536 is a row of header-map.vs" | eve.vs:482–488; alloc.vs:75–77 | code read |
| The crypto staging region | one shared raw region under a spinlock, because "A page-data block cannot serve" in a keystream build | alloc.vs:4399–4462 | code read |
| `(vs.mmap/scratch! n)` | "a private window of n bytes no file backs (staging memory)" — a foreign run of raw regions | atom.broker:vs/src/vs/mmap.vs:24, :224 | code read |
| boot's `stg` | a context/staging **slab block** the boot driver passes down (fields at `stg+4000…4172`); "boot.vs `core_main` filled its staging block only to `stg+4095`, while `pairlink` / `$link` read `stg+4100`–`4164`" | boot.vs:308–323, :539, :596–610; memory-model.md:242–248 | code read + doc read |

### 3.4 The scope mechanism for temporaries (L24)

- **Where:** the emitter, `atom.broker:vs/src/vs/jit_wasm.vs:9280–9456` ("THE SCOPE PER FORM"); the frame's imports `r.so` / `r.sc` are `leg:r.so` → `kernel/tally_scope_open` and `leg:r.sc` → `kernel/tally_scope_close` (program_runtime.vs:27426–27427); the kernel side is `tally.vs:1815–2313`.
- **What opens a scope:** "A binding form — a let (value or tail position), a loop ITERATION, a fn body … opens a scope on entry … and closes it at every exit with its value (`r.sc` (v, s) → v: v kept into the PARENT scope, s tossed" (jit_wasm.vs:9284–9287); since 2026-10-25 "a let opens no scope" (jit_wasm.vs:9431–9435).
- **What a temporary is:** an ordinary slab block from `alloc_block`, noted in the innermost scope's log by the allocator's hook: "a block handed out while this instance's write attempt has its log open is noted there" (alloc.vs:1999–2001; `tl_log_note`, tally.vs:1538–1559).
- **The toss:** the scope's log closes LOST — "every noted block that no holder took (still uncounted) and that was not freed since it was noted (its logged bit) is freed now, without a walk" (tally.vs:1599–1601, :1633–1661); then the flush (tally.vs:2207–2250).
- **Promotion of a kept value:** no copy. "Promotion is a STORE by the adopter (tally_atom_cas): no copy, no forward table" (tally.vs:1820); the close holds the value across the toss and the parent scope keeps it (`tally_scope_close`, tally.vs:2288–2305).
- **"Merge … for free", precisely:** on a WON write, the attempt's log chunks are linked onto the enclosing log — "the chunks are spliced onto it and nothing is read (the operator, 2026-10-20 6:36 PM ET: 'they should merge into the runtime atom if they aren't thrown away, for free')" (tally.vs:1601–1604; the splice is the pointer walk at :1619–1632). On a toss, a block a holder counted simply stays where it is. **Nothing moves in either case: every temporary was allocated at its final slab address.** There is no notion at this pin of a value built outside the slab and merged in.
- **Lazy scopes:** "a scope OPENS as a depth … and MATERIALIZES — its record … and its log — at the first thing it must own" (tally.vs:1855–1867).
- **Measured cost (comments):** "70,202,721 scopes, 40,221,326 (57%) tossed with nothing kept and nothing logged, and 98.7% of the keeps of a block were of a PERMANENT block; each scope minted and freed an atom, a keep block and a log chunk (~4,700 instructions)" before the lazy scope (tally.vs:1855–1858); "a form's scope close was 45–63 % of every row and tl_desc + tl_col + their page helpers ~35 % of it" (tally.vs:259–261); "let-scopes were 46.6 M of 70.2 M opens and 11.0 M of 12.8 M materializations" (jit_wasm.vs:9433–9434).
- **Measured cost (docs):** "a `swap!` of one record into a 500-entry map, with a closure as its fn, keeps ~21 KB" (memory-model.md:285–287); one write's blocks per class, e.g. `(assoc m 0 x)` on a small map allocates classes "4, 4 + 7 + a node" (atom.broker:vs/docs/reclaim/size-classes.md:118–134, measured 2026-10-06 under 256-B slots).

### 3.5 Linear / transient builders (read so far)

| builder | how it avoids churn | thread-safety guard | where |
|---|---|---|---|
| transient vector (`vec_transient.vs`) | "mutates one owned, UNSEALED block in place"; `vec_push_mut` is `vec_conj` "minus the clone, minus the new root, minus the seal"; `persistent!` flips the tag and seals | the root carries TRANSIENT in its **descriptor** (tally side table), not in the block; "Every mutating op runs tally.vs tally_transient_guard on the root first: a persisted root, a persistent vector or another thread's transient traps"; "a TRANSIENT root is refused by every publishing store" | atom.broker:vs/src/vs/kernel/wasm/vec_transient.vs:13–42, :73–78, :100–112 — code read (header) |
| slab array (`sarr.vs`) — **the closest existing shape to a packed chunk** | one block: "contiguous mixed-value array with an offset table (O(1) indexed access to variable-size serialized elements, amortized O(1) push, 2× growth)"; elements are "opaque byte strings (the .vs wrapper serializes / decodes, as eve's serializeValue does)"; growth mints a 2× block and copies | owner stamp in the block (+277) checked against `SARR_CURRENT_THREAD_ID`; a mismatch traps | atom.broker:vs/src/vs/kernel/wasm/sarr.vs:1–20, :50–55, :107–122 — code read. Capped at class 14: `(alloc_block (size_to_class total))` returns −1 past 10,240 B (sarr.vs:65–67) |
| `vec_conj_lin` (vec.vs), `lazy.vs` | not read yet | — | — |

## 4. How values leave the kernel — partial

- The host bridge is text: a program's op is "ESC _ vshost SP <json> ESC \\ … [rid-or-null, family, verb, arg …]" on fd 1; answers are "one JSON object a line" on fd 0; "bytes cross as base64 text or as UTF-8 text" (atom.broker:vs/src/vs/host.vs:6–21 — code read).
- Kernel `serial.vs` is "the in-evaluator wire format … NOT eve's serializer (eve.vs serialize_value is)": one tag byte + payload; scalar tags 0x00–0x04 "match eve's serializer", 0x10+ kernel-internal; `TAG_REF` 0x13 carries a 64-byte slot (atom.broker:vs/src/vs/kernel/wasm/serial.vs:1–12, :55–129 — code read).
- Kernel `eve.vs` `serialize_value_write` emits "eve's serial wire form (eve's serializeValue — [0xEE 0xDB][tag][payload])": FALSE 0x01, TRUE 0x02, INT32 0x03, FLOAT64 0x04, STR_SHORT 0x05, STR_LONG 0x06; an ExtCID-headed block is emitted as its 64-byte core verbatim; a LONG that does not fit i32, a rope and a collection key are refused (eve.vs:4299–4350, :4372–4513 — code read).
- interop.vs, the Eve adapters and `eve:doc/hosts/valuescript.md` — pending.

## 5. Type identity in VS — partial

- **Kernel type-CIDs are pinned placeholders, not derived.** `META_TYPE_CID` is "0xAAAA… / 0xBBBB… — deliberately bogus"; BOOL, INT32, NIL, CLOSURE likewise, each "Canonical derivation (not done)" (eve.vs:102–128, :149–165, :184–192, :210–219, :234–243). Collections: "The CIDs are uniform-byte placeholders (0x1111… / 0xCCCC… etc.), not SHA-256-derived" (eve.vs:930–932). They are compile-time constants, so they are identical in every process and build — and they collide: "placeholder CIDs collide across modules: KEYWORD ≡ HAMT_NODE ≡ IR_LIT/IR_CALL; SYMBOL_LO ≡ VEC_NODE_LO", which is why readers use a structural header probe instead of a CID table (eve.vs:3918–3926).
- A derived scheme is specified in comments only: primitive "canonical bytes are `[META_TYPE_CID:16][name_len:i32 LE:4][name_utf8:N]`"; user types add "[field_count][per-field: name, field_type_cid:16, encoding_tag, offset] sorted by field-name" (eve.vs:502–519, :709–738). `eve_alloc_user_typedef` takes a caller-computed CID (eve.vs:778–790).
- **Slab structs (the kernel's fixed-layout record):** the shape registry is "a kernel hamt keyed by the signature bytes (the caller's 'k:type|k:type' string) → the record offset"; "SHAPE RECORD block (its offset IS the shape id; stable per slab)"; a struct block stores `shape:i32` at +260 — **a slab offset, not a portable id** (atom.broker:vs/src/vs/kernel/wasm/sstruct.vs:7–23, :248 — code read). Field types: 1 int32, 2 float64, 3 boolean, 4 ref (a 64-byte slot) (sstruct.vs:11–15, :45–54).
- **`deftype` exists in VS** as an analyzer special form (`vs-deftype`, atom.broker:vs/src/vs/analyzer.vs:2693–2749) and `defrecord` as a macro over it (`defrecord-core`, atom.broker:vs/src/vs/macros.vs:1685–1709). The fixed layout is computed **at macro expansion** by `-leg-layout` (macros.vs:1947–1968), mirroring `sstruct.vs shape_register`: field hints `^:int32 ^:float64 ^:boolean ^:ref` (analyzer.vs:2644–2649); the layout's identity is a keyword "`:vs-lay-<h>` with h Clojure's hash of the shape's signature `<qname>|<f>:<code>|…`" — a 32-bit string hash of the qualified type name and its fields, deterministic from source (macros.vs:1953–1955).
- intern.vs, kval.vs, record type boxes ('rtyp') — pending.

## 6. Where did Eve go in atom.broker — pending

Confirmed so far: the pin's commit subject is "Land the Eve extraction: eve/ subtree removed; Eve lives in SeniorCareMarket/eve and atom.broker consumes it as a dep pin (0.9.3.1)" (`git log -1` in atom.broker, run this session).

## Kill/support evidence — pending (A8, A9)

Interim, from §1–§2 (to be finalised):
- **A9** — no bulk/placement entry exists in alloc.vs (§1.7). The working precedent for "placement, not planning" is whole-page adoption (§2.2), which keeps the bytes *outside* the allocator for good.
- **A8** — no fixed class holds 64 KiB; one class-15 block can (15 per region), 4-byte aligned; nothing inside such a block is a block to the allocator, the tally or the page writer (§1.3, §1.6, §2.3).
