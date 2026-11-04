> **Historical record** — the network-atom lane's Phase 3 report (local tiers, in progress when written), 2026-10-16. It describes eve as it stood then, not eve 0.9. Its acceptance rows are VS and broker commands (atom.broker work).
> Current authority: `doc/network-atoms/README.md` (the user guide), `WIRE.md`, `ADAPTERS.md`.

# Phase 3 report — local tiers under the network atom (in progress)

2026-10-16, ET. Branch `claude/eve-network-atoms-impl`. Plan: `eve/doc/network-atoms/IMPL-PLAN.md` §5. Claims tagged
[ran] / [read].

## 3.1 B-tier — closed in Phase 2

Root-caused and fixed at its producer in unit 2.4's kernel build (IMPL-PLAN §9 A9 (g)): `pm_page_text` conj'd
`string_create`'s −1 sentinel into the page text (trap 0xFFF10018); it builds the leaf with `leaf_of_buf`. Re-measured
on the landed builder by `do_pages_test.py` `/write` (result below).

## 3.2 P9 — the working set over adopted BLOCKS: the change list, frozen before the build (CLAUDE.md rule 22)

What the reading found [read]: `vs.tier`'s `:memory` policy evicts REGIONS the page tier adopted (`rt-kstat-adopted`,
`rt-region-release!`). A block the block tier adopts (`eve_block_adopt`) is an ordinary slab allocation in a region the
program shares, so that policy never sees it: under a network atom the bound bounds nothing.

REVISED 2026-10-17, before any build (the first list, kept below as written, is withdrawn) [read]: the first list dropped
an evicted block's table entry and nominated the block to retire's epoch gate. That breaks the HELD VALUES ruling
(`vs/src/vs/tier.vs` ns docstring, operator 2026-10-13): a value a form binds is not pinned across rows, so the sweep
could free and reuse a block under a `let` that holds it. What keeps a held value reading its own bytes is REGION-grained
(L04 MF1: `eve_region_bind` holds an ADOPTED region, `claim_region_reuse` refuses a held one, `alloc.vs` THE HOLD
COUNTS), and the `:memory` policy already evicts ADOPTED regions oldest-first. So the block tier adopts INTO adopted
regions, and the page tier's eviction, reuse gate and holds cover blocks unchanged.

1. `eve.vs` — BLOCK PAGES. An adopted block, and every scalar box `_adopt_slot` makes for its slots, is bump-allocated in
   a block page — one current page per size class, because the kernel sizes a block by its page header
   (`eve_block_alloc_size` reads `PHV2_BLOCK_SIZE`; a parent's tier-1 image needs ≥ 288). A block page is a region
   claimed as `eve_page_adopt_b64` claims one (`claim_region_reuse` past its epoch gate, else the bump) whose header is
   a page's: `PAGE_MAGIC`; a block-page CID `'BLKP' ‖ region slot ‖ generation ‖ 'BLKP'` (a slab-wide counter; the two
   markers make it no content CID); class index, block size, page size; the cursor in `PHV2_NEXT_FIT`, advanced by
   CAS. It is registered in the page table (owner `PT_ADOPTED_OWNER`) by `_bp_register`, which takes over the entry of
   an earlier generation in the same region (one entry per region slot: the table never fills with dead pages), so
   `rt-kstat-adopted` lists it with a fresh registration sequence. The current page of each class lives in the SHA
   table region's header (`ST_HDR` 64 → 128: +64..123, fifteen cells — 0 none, 1 being made, else the base); a full
   page is replaced by CAS.
2. `eve.vs` — a table entry records its block page's generation at +12; `_st_holds` answers a block only while its page
   is resident (`PAGE_MAGIC`) and carries that generation: an evicted or reused page names nothing, and the next read
   faults the block in again by key. A raced or refused adoption leaves its bytes in the page (no free: the page is
   reclaimed whole).
3. `eve.vs` — `eve_slot_child`: a box slot whose block page is gone (evicted, or reused under a new generation:
   `eve_slot_resolve` answers -1) is rebuilt from the slot's own inline image (bytes 80..111, which `_adopt_slot`
   leaves in place) into the current box page, the slot not patched. Every block-page byte can then be had again —
   blocks by key, boxes from the slot that names them — so a held block page is evicted like a content page (MF1:
   its bytes stay until reuse, and reuse is refused while it is held), and a map entry a local path copy took over
   verbatim (`hamt.vs` copies kv bytes) still reads after the page that held its box is gone.
4. `retire.vs` — `rt_emit`'s `_st_forget` hook and `eve.vs` `_st_forget` deleted: no block of this slab's own allocator
   is in the table any more, and `rt_emit` already refuses a block of an adopted region (`region_foreign_p`).
5. `program_runtime.vs` — row `(vs.core/rt-kstat-blocks)` → `[entries pages bytes]` (the table's entries, the block
   pages claimed, the bytes their cursors hold); the WAT mirror re-generated (`scripts/dsl-port-gate` IDENTICAL). No
   `rt-block-evict!` row: the policy evicts pages.
6. `vs.tier` — the `:memory` policy's write-back skips a block page (`rt-page-cid-at`'s `'BLKP'` prefix: no store
   holds it); the bound stays in regions (a block page is one).
6a. Tests: `vs/test-xfail/persistence-tiers/kernel_direct_block.py` R10 (adopt blocks of two classes → two block pages;
   `eve_region_release` one → its blocks' lookups miss, re-adoption lands in a new page with equal bytes; a box slot of
   the released page reads its value again through `eve_slot_child`; a held page's region is not reused, an unheld
   one is, past the epoch gate, under a new generation); `do_pages_test.py` + `progs/do_pages.vs`
   re-pointed (a remote atom over `blobstore.py` under `(tier/working-set! 4)`: `rt-kstat-adopted` ≤ the bound after a
   run, the value equal across three reads); `fetch_on_miss_test.py` the same bound row on wasmtime.

### The kernel unit gate (the fast path, before the chain) [ran]

`scripts/kernel-rebuild` over the Phase 3 sources: 1,464 function bodies, 37 windows; `wat2wasm` + `wasm-validate`
clean. Against that kernel:

| check | result |
|---|---|
| `kernel_direct_block.py` R1–R10 | 515 PASS, 0 FAIL — R8 (retire frees no adopted block: 0 keys lost), R10 (two class-14 pages; the current one refused eviction; the full one released → exactly its 102 keys stop looking up; 2 slots naming them resolve −1; re-adopted into another page with equal images; the 5,000-element vector reads whole; a scalar slot of a gone generation rebuilt from its band; a held released region not reused, then reused under a new generation once released) |
| `kernel_direct_cid.py` (the page tier) | 100 PASS, 0 FAIL |
| `retirement-epochs/kernel_direct.py`, every check | 158 PASS, 0 FAIL, the same verdicts line for line on main's merged kernel. On both kernels alike, `scan` stops with a traceback building its 100,000-key map and `varread` at line 1240; the checks after each were run separately; `vars vrc watchwrite window xthread` stop at once on both (they take the `--vars` module, not given) |
| `rq_direct.py` | 69 PASS, 15 FAIL — the same verdicts, line for line, on main's merged kernel (the deferred queue's own mode is not ported on main: `retire.vs` header) |
| `test/core-census/kernel_direct_seals.py` | did not start on main's kernel either: it imported `../retirement-epochs/kernel_direct.py` from the directory main's graduation sweep moved it to. Path fixed: `KERNEL-DIRECT-SEALS: PASS`, 11 PASS, 0 FAIL |

Two test defects fixed on the way, both in R10's own fixture: the vector was built from raw integers where the
kernel's `vec_conj_off` takes boxes, and the page magic was written byte-reversed (the kernel's `PAGE_MAGIC` is
1,346,455,365).

The first list, withdrawn (kept as written):

1. `eve.vs` — the SHA table records, per entry, a registration sequence at +12 (a header counter, atomic add) and the
   table keeps the bytes adopted (its header, the class size of each live entry's block: added at insert, subtracted
   at drop).
2. `eve.vs` — `eve_block_evict(target)`: while the adopted bytes exceed target, the live entry with the lowest sequence
   is dropped (+4 ← 0) and its block nominated to retire's gate (item 3) — collection and value blocks alike (a value
   block is immortal only under the tree diff's owned-blob rule; eviction nominates it explicitly). Answers the count.
3. `retire.vs` — `retire_nominate(off)`: `rt_retire_block` through the module's own mode-0 sink (marked, enqueued at
   the current epoch, freed by the sweep once no reader is pinned at or before it).
4. `program_runtime.vs` — rows `(vs.core/rt-block-evict! bytes)` → the count, `(vs.core/rt-kstat-blocks)` →
   `[entries bytes evictions]`; the WAT mirror re-generated (`scripts/dsl-port-gate` IDENTICAL).
5. `vs.tier` — the `:memory` policy also evicts adopted blocks to the bound (bound × region bytes) when a block tier is
   installed; `tier-stats` gains `:block-evictions`.
6. Tests: R10 (adopt n blocks, evict to a target, read → refault by key → equal, the sweep frees, a later adoption
   reuses); `do_pages_test.py` + `progs/do_pages.vs` re-pointed; `fetch_on_miss_test.py` the same bound row.

7. `program_runtime.vs` — `pm_block_text`: a read of zero bytes answers the empty byte string, not nil (its `k <= 0`
   guard becomes `k < 0`; `leaf_of_buf o 0` is `string_create o 0`). Measured 2026-10-16 on the Phase-2 landing:
   `tl_01b_blob_roundtrip_test` walls (`length`) on its empty payload — `(rt-block-text (+ off 24) 0)` answered nil.

8. `program_runtime.vs` — rows `(vs.core/rt-b64-decode text)` → one byte-string leaf (over the kernel's
   `eve_b64_decode`, which `rt-slot-value` already uses) and `(vs.core/rt-b64-encode bytes)` → the text (over
   `eve_page_b64_chunk`); `vs.net.bytes/from-b64` / `to-b64` call them. Measured 2026-10-16 on the Phase-2 landing:
   decoding a fetched 2 KB blob in `.vs` cost 48 wasm pages (3 MB) after the join fix, one leaf per byte plus a
   264-byte vector slot per piece — every block a reader faults pays it.

One kernel rebuild + one chain carry items 1–5, 7 and 8, and main's merged sources (the L04 C16/C17 unit reclaim, fix
wave A, adoption K: merged 2026-10-17); items 6–6a are `.vs` / drivers, written and unit-gated before the build starts.

## 3.3 The landing, and the runtime defect it exposed (2026-10-17)

The chain (`toolchain/chain /tmp/p3-chain all`, 12:09–12:54 AM ET) reached its fixed point: gen3 == gen2, 6,185,897 B,
sha `bb1382a0b13b5ded`; the core walk E 2301 64293 0 0; SYMTAB-VERIFY OK on both; unresolved callees CLEAN (387
sites, all declared). Its first run (stopped 12:06 AM ET) had bound seven threading-macro call sites to a throw stub:
main's new `-core-head-name` (9255487f) called `.lastIndexOf` and `.slice`, host-served methods whose modules THE
LINK's DROP_HC rule drops for the template's member, and a new defn has none. f43da1d3 rewrote it over `-last-segment`
and `str`, and the rerun resolved it.

The landing (5788ccb7) was reverted (f1a2454a): programs its builder compiles fail at run time [ran]. The DSL port
driver throws "reset!: not an atom" in every mode; a one-line edit of it traps in a kernel function instead;
check-kernel-modules reads "vs2wat: unrecognized body form #object[fn]"; check-core-minimal reads an index past its
array. The gate battery was RED on dsl-port-gate, check-core-minimal and check-kernel-modules. The same driver compiled
by the previous builder runs correctly; compiled by the chain's gen1 (the frozen compiler over the new template) it
fails the same way. So the defect is in the runtime this landing first assembled, not in how the corpus compiles.
That runtime is main's backlog unlanded since 35bf604 (the deferred-retirement queue, L17's retire, adoption K, L04's
unit reclaim, the veil-oom retire paths, core.vs's write path through var-table rows), which main's own lanes report
they could not prove at program level against the checked-in core, together with this lane's Phase 3 kernel.

Next, in order: a cut and gen1 from main's own tree (no Phase 3) to place the defect; the fix at its producer; the
chain again; the landing with tl_04a's `tl-04a-qualified-heads` (reverted with it, 9ebb40a3) and vs.net.bytes's
base64 on the kernel rows (restored to the .vs codec meanwhile, 9cd519a7).
