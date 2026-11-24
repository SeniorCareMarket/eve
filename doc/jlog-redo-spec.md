# The redo commit log (jlog v2): one device flush per commit

Status: IMPLEMENTED 2026-11-24 ET (`eve.jlog` v2, `eve.atom` jlog2-*; §10 lists where the code differs from the
spec text). Written 2026-11-23 ET. Follows the jlog of `eve-mmap-journal-plan.md` (eve.db.ds), which this changes where
noted. Asked for by John: "do both option 1 and 2 … I don't like the idea of only some hosts getting some semantics.
But I'd like them all to go at 0.4 ms or better."

## 0. Why, measured

A J1 commit today (`9536ef2` + the jlog) forces before its root CAS every slab class it touched — each class's data,
bitmap and three descriptor-column files, a file each — then the record's 4 KiB slot. strace of 200 jolt commits of
`(swap! x update :n inc)` (this box, ext4 on virtio): **11 msyncs per commit** — 2 classes × 5 files + the slot; 8 of
the slab files dirty (~132–144 µs each), 2 clean (~38 µs), the slot ~100–250 µs. A device flush per dirty file is the
cost; measured alternatives on this disk: 8 sequential `fdatasync` 0.76 ms, 8 parallel 0.73 ms (no gain),
`sync_file_range` × 8 + one `fdatasync` 0.22–0.25 ms (but only safe for hole-free files on update-in-place
filesystems — see option 1, §9).

Commit cost with no forcing at all (measured, quiet box pending re-measure): jolt 0.05 ms, JVM 0.19 ms, **bb 1.16 ms**
(SCI interpretation — over 0.4 ms before any I/O). With one flush per commit (the slot's msync, ~0.13–0.2 ms) jolt and
the JVM can land at or under ~0.4 ms; bb cannot (profiled separately).

## 1. What a commit writes (inventory)

| | write | who sees it | durable today by | redo |
|---|---|---|---|---|
| W1 | the new value's blocks (logged allocations) | private until the CAS, immutable after | pre-CAS class force | bytes in the record |
| W2 | their bitmap bits | shared words (CAS) | pre-CAS class force | set-bit in the record (idempotent) |
| W3 | their descriptor rows (count, flags, pointer column) | private until the CAS | pre-CAS class force | flags + pointer in the record; count pinned at recovery |
| W4 | holds on existing rows (`hold-root!`'s walk, `store!`) | shared rows, CAS'd by any process | pre-CAS class force | row offsets in the record; pinned at recovery |
| W5 | in-place stores into published blocks (an eve-deftype `set!`/`cas!` inside a swap) | shared bytes | pre-CAS class force | byte ranges in the record |
| W6 | the root slot CAS + epoch bump | root file | the record (its table) | the record's delta |
| W7 | after the CAS: the drop of the old root, the retire flush's frees | shared | `:unforced`, next sweep | none: leak direction only |

## 2. The record (EVR3)

One 4 KiB slot: `[EVR3][seq][stripe][label][kind][nonce][writer][len]` then a body, then a CRC-32 over everything
before it. Kinds: COMMIT (delta + redo), COMMIT-NOREDO (delta only: its blocks were forced before its CAS — the
fallback), ABORT (a reserved slot whose CAS lost), VOID (a dead writer's slot, written by a resolver after a
whole-domain force), ANCHOR (a full atom table: a checkpoint). A COMMIT body: the delta `[slot old new hash]`, then
`n-blocks × [offset len bytes]` (W1; W2 is implied by the offsets), `n-rows × [offset flags pointer]` (W3),
`n-pins × [offset]` (W4), `n-writes × [offset field len bytes]` (W5). A commit whose body does not fit the slot, or that
allocates in the coalesced class (6, whose allocator state is not per-block), takes the fallback: today's pre-CAS
force of its classes, then a COMMIT-NOREDO record.

## 3. The commit (J1)

1. Read the old root, run f, build the new root (unchanged). The attempt's sio logs allocations (today) and, new, the
   byte ranges written into blocks it did not allocate (W5); the tally notes touched rows (today).
2. Fallback commits only: force the touched classes (today's pre-CAS sweep).
3. **Reserve** the next slot (eve.jlog's tail CAS) — after the old root was read, before the CAS. So for any two
   commits where one read the other's published root, the reader's slot is later: **log order extends happens-before.**
4. CAS the root slot.
   - Lost: write an ABORT record into the slot (the watermark below forces it with its neighbours), free what the
     attempt allocated (today), retry from 1.
   - Won: bump the epoch, read the label, build and write the COMMIT record (blocks' bytes read back from the slab).
5. **Group commit.** Header word `durable` = the first seq not yet known final-and-forced. The writer msyncs the
   slots from `durable` through its own in ONE call (contiguous pages: one flush), then CAS-advances `durable` over
   the run of valid records it forced; then waits (futex on `durable`, -wait-i32!) until `durable` passes its seq —
   usually it already has. One writer alone: one msync per commit.
6. **Drop the old root only now** (it was before the record), so no block a durable state still reaches is ever
   released: the retire flush frees nothing an installable state needs, and the retention floor (FLUSH-ADVANCE) is
   no longer needed for that (kept for readers' pins only).
7. Return.

## 4. A dead writer in the prefix

A writer waiting on `durable` behind a slot that stays unsealed checks the slot's owner (eve.jlog's sowner word: the
seq's low bits and the worker slot) against the root's heartbeats. Dead: force the whole domain (option 1's barrier
where it applies, else every region's msync) — the dead writer's blocks, if it published, are in the page cache —
then win the slot by CAS on its sowner word and write VOID. A zombie (judged dead, alive) loses that CAS at its seal:
it forces its own classes and appends a fresh ANCHOR (a whole table, folded) before it returns.

## 5. Checkpoint (sync!, close, ring half full)

Reserve a slot; wait until `durable` reaches it; force the whole domain; write ANCHOR = the previous anchor's table
folded with every COMMIT delta between them (read from the ring, not the live table: the live table may already hold
commits after this slot whose redo is not durable). The ring may then reuse slots before the anchor; a reservation
that would lap the newest anchor runs a checkpoint first.

## 6. Recovery (an open no live process holds; under the recovery hold, unchanged)

1. Scan the ring; the newest valid ANCHOR (highest seq) is the start; the **prefix** is the run of consecutive seqs
   after it whose slots hold valid records. The first missing or torn seq ends it (a commit after it was never
   acknowledged: its writer waited on `durable`, which never passed the hole).
2. Fold the COMMIT and COMMIT-NOREDO deltas of the prefix, in log order, over the anchor's table. Epoch := the highest
   label in the prefix (or the anchor's).
3. Replay the prefix's COMMIT redo in log order: block bytes (W1), bitmap bits set (W2), rows' flags and pointer column
   (W3), write ranges (W5); then **pin** — count := CPERM, held for ever — every W3 row and every W4 row. A pinned
   block is never freed: a count can only be too high, never too low (leak over use-after-free, eve's rule). The leak
   is bounded by the blocks the unflushed window touched; it is the price of no format change (the alternative, a
   version per descriptor row with every count change a 64-bit CAS, changes the tally's row format, which ValueScript's
   kernel reads as-is).
4. Force the whole domain; install the folded table and the epoch into the root; force the root.
5. Rebuild the reservation state; write a fresh ANCHOR at the new tail; claim the slot inside the hold (unchanged).

Proof sketch. An acknowledged commit c was in the durable prefix when it returned (§3.5), and the prefix only grows, so
recovery folds c. Every block c's root reaches was written by c (W1, replayed), or by a commit c depends on — earlier
in log order (§3.3), so in the prefix, replayed — or existed at the anchor (forced, §5). No reachable block was freed:
drops happen after the dropping commit is durable (§3.6), so a block dropped is unreachable from every state recovery
can fold, and an allocator bit or count lost to a crash errs only toward a leak (W2 set-only, W3/W4 pinned, W7 not
replayed).

## 7. The log file (EVL2)

eve.jlog's layout, unchanged except: version 2; stripes fixed at 1 (log order across stripes is not total, §3.3 needs
it); header word `durable` at 32 (the plan's `group-durable-seq` slot); records EVR3. A version-1 log (this branch's
EVR2 snapshot records) is recovered by its own rule (max label) and replaced by a version-2 log whose first record is
an ANCHOR of the recovered table — a non-live open only, as legacy migration is.

## 8. Tests (all must pass before the default changes)

The kill -9 suite (`scripts/jlog-crash.sh`, every runtime and pair), the torn-write fuzz (`jlog_fuzz_test`, extended:
tears inside a prefix, a hole followed by valid records, a VOID, an ABORT, an oversize fallback), the W1–W4 wounds,
the contention shootout (every pair + all together, 3 reps + `nodurable`), the gates; and a recovery test that a
crash leaves pinned rows only for blocks the window touched. Per host before/after `:disk-swap-counter` on a quiet box.

## 9. Option 1's place

With redo, slab files are forced only at a checkpoint, by a dead-writer resolver and at recovery — whole-domain
forces, off the commit path. There the single-flush barrier (write back every dirty range with `sync_file_range`,
then one `fdatasync`) replaces ~10–40 per-file flushes with one, where it is safe: Linux, an update-in-place
filesystem (ext4, xfs: checked by `fstatfs`), files with no holes (checked with `SEEK_HOLE`; new slab files written
once when they grow). Elsewhere — other filesystems, macOS, a file with holes — the per-file msync, which is the same
guarantee, slower. Bindings: jolt's FFI, the Node addon, bb's FFI, the JVM's FFM (`--enable-preview` on JDK 21, final
from 22).

## 10. As implemented (where the code differs from §1–§9)

- **W5 takes the fallback.** A record carries no byte ranges of shared blocks. The `set!`/`cas!` rewriters (JVM,
  generic ISlabIO, ClojureScript) emit `eve.deftype-proto.alloc/note-write!` before an in-place field write; a commit
  attempt binds `alloc/*inplace*` and, when it wrote into a block it did not allocate, forces its touched classes before
  its CAS and writes a NOREDO record. Rare (a mutable deftype field written inside a swap), so the fast path stays one
  flush.
- **Record layout.** `[EVR3][seq][kind][label][nonce][writer][len][0]` (32 bytes), body, CRC-32. COMMIT body: the delta
  `[slot old new hash]`, `n-blocks × [off flags pointer len bytes]` (W1–W3; W2 is the offset), `n-pins × [off flags]`
  (W4). NOREDO: the delta alone. ANCHOR: the 2048-byte table.
- **Atom-slot claims are records.** A claim (pointer := CLAIMED, the name hash) writes a delta-only NOREDO record and
  waits for it, so recovery keeps the slot claimed before the registry names it.
- **The gate.** A commit may reserve up to half the ring past the newest durable anchor; at that mark it runs a
  checkpoint (§5), whose own reservation may use the other half. Several processes may checkpoint at once: each folds
  from the newest anchor, `H_ANCHOR` advances by CAS-max.
- **Recovery voids the stale tail.** After installing, every valid record whose seq is at or past the end of the prefix
  (written, never acknowledged) has its magic zeroed and its slot forced before anything new is written at that seq;
  otherwise a later recovery could read it as the new prefix's continuation. Then the reservation state is rebuilt
  and a fresh ANCHOR written.
- **A live-looking domain is joined, not recovered** (as on v1): after `kill -9` the page cache is intact, and a dead
  writer's hole is voided by the first waiter once its heartbeat is stale (30 s). A power-loss image read before its
  heartbeats age is the same case.
- **Slab growth forces sizes.** A v2 commit forces no slab file, so a grown class's five files have their sizes forced
  (an msync of each file's first bytes) when they are re-mapped, before a block past the old end is handed out — JVM,
  jolt, bb (`alloc/remap-jvm!`, the refresh path) and Node (`remap-mmap-slab!`). Replay sets bitmap bits with
  `alloc/mark-allocated!` (a Node host op too).
- **Node's whole-domain force covers the flags and pointer columns** (it forced data, bitmap and count only: H3 on the
  module slab).
- **Batch (J10) on v2.** Records are written per commit; a commit waits on the watermark only at its batch boundary
  (count or window), and the old roots of the commits before it are let go then.
- **A zombie** (its reservation voided while alive) runs a checkpoint before it returns: the forced domain and the new
  ANCHOR cover its commit.
- **v1 logs** are recovered by their own rule and moved to v2 on the open that recovers them (`<base>.jlog` renamed
  `<base>.jlog.v1`, never deleted), unless that open asks for `:jlog-version 1`.
