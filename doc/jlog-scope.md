# Scope: the mmapped journal ring (`<base>.jlog`)

Status: SCOPE, not started. Written 2026-11-23 4:30 PM ET against eve `9536ef2` (origin/main at that time) and
eve.db.ds `lane-m/contention-shootout` at `9109890`. Provenance tags: **[read]** = read in the code at those
commits in this session; **[John]** = from `doc/perf/contention-shootout.md` on the shootout branch (John's runs, not
re-run here); **[proposed]** = design, to be proven by the spike.

## 0. What the job is

Replace the eleven per-runtime `eve.journal-file` adapters with ONE cljc journal: a preallocated, fixed-size ring of
frames in `<base>.jlog`, mapped with the same `region/open-mmap-region` every host already uses for `.root`, `.rmap`
and the slabs, and coordinated only by CAS on mapped words, like the atom table is. No OS file locks. Durability is kept:
an acknowledged `swap!` survives `kill -9` and power loss.

## 1. What exists today

**[read]** The journal is `adapters/journal-file/<host>/src/eve/journal_file.*`, eleven adapters, 2,430 lines (java, jolt,
node, clr, python, beam, jank, glojure, letgo, dart, plus `unsupported`). `eve.atom` owns the sequence
(`src/eve/atom.cljc:734-957`): lock → sweep → append → force.

- **Record:** EVJ1 = `[magic][len][epoch][table-len][2,048-byte atom table][crc32]`, a full snapshot of the 256 × 8-byte
  atom table per commit (`data.cljc:427-431`: a slot is `[ptr i32][name-hash u32]`).
- **Commit (J1):** CAS the slot pointer (`atom.cljc:1663`), bump the epoch, drop the old root, then `journal-flush!`
  (`:777`). The flush takes the journal lock, sweeps this commit's touched classes, appends a snapshot read live inside
  the lock, and forces.
- **Recovery (`:878`):** runs when no worker slot is live. It installs the newest valid record *in file order* whenever
  its epoch or table differs from the root, then rewrites the journal down to that one record.
- **Locks differ by family:**
  - JVM uses a `FileChannel` lock (fcntl).
  - jolt uses `lockf` (fcntl).
  - Node uses a lock *directory* (`node/journal_file.cljs:1-15` says so).

  The JVM and jolt adapters write at a sampled file size (`java/journal_file.clj:184`, `jolt/journal_file.clj:330`)
  while Node appends `O_APPEND` (`node :58`).

**Why the shootout lost data.**
- **[read]** Node's lock directory doesn't exclude the fcntl seats, so a JVM or jolt append can overwrite a Node record.
  A framing failure ends the scan (`java :126`), and recovery then installs an older record over a newer root.
- **[John]** He observed exactly this: entities kept / committed of jvm+cljs 7/595, jvm+jolt+cljs 82/743, jolt+cljs
  225/688 and 5-way 18/79. The same mixes are exact with `nodurable`.

**H1 at this pin [read].** Regions without `ISyncableRegion` turn every slab and root force into a silent no-op.
- `jolt-ffi`, `glojure` and `beam` have none.
- bb's default `BbNativeRegion` **does** `msync` (`jvm-nio-lock/region.clj:665`). Only the lock-only fallback
  `BbMmapRegion` (`:224`) has none. The brief's "bb has none" is half right.
- jolt's journal itself does `fsync` (`jolt/journal_file.clj:355`), so jolt's commits today force the journal but not the
  slab.

**A further hole [read, not in the brief's H list as I have it].** J1 sweeps only *this* commit's touched set, but the
record it appends snapshots the whole table, including other writers' pointers whose blocks those writers haven't
forced yet.
- Example: A commits epoch 100. B builds 101 on A's value, sharing A's HAMT nodes. B gets the lock first, forces only
  its own blocks, appends and acks. Power fails before A's flush.
- Recovery then installs B's record, which reaches A's unforced nodes.
- `kill -9` can't show this, because the page cache survives. Only a power loss or kernel crash can. The design below
  closes it.

## 2. Design: the journal as one more Eve region [proposed]

### 2.1 What it reuses

| piece | reused from the atoms | new |
|---|---|---|
| mapping | `region/open-mmap-region`: the same native binding per host (`native/mmap_cas.cc` on Node, VarHandle + `MappedByteBuffer.force` on JVM, libc through `jolt.ffi` on jolt, the FFM region on bb) | nothing, except `msync` where H1 is missing |
| words | `-load-i64` `-cas-i64!` `-store-i64!` on mapped words, as the slab headers use | the ring's header words |
| waiting | `-wait-i32!` / `-notify-i32!` (futex on Linux) | group-commit waiters |
| liveness | the root's worker registry: slot index, status, heartbeat (`heartbeat-stale?` `:131`) | a per-worker claim word |
| forces | `mem/sync-region!` / `alloc/sync-touched!` | — |
| init | "magic written last", as `init-domain-root!` does | — |

All logic lives in one `src/eve/jlog.cljc` over `IMemRegion`. There's no per-host journal code: a host gets the journal
if its region maps a file, supports i64 CAS, and forces. Otherwise opening a journaled domain fails loudly, as
`journal-file/unsupported` does today.

### 2.2 File layout (fixed size, preallocated with real blocks)

```
page 0      header
  0   i32  magic "EVL1"          4  i32 version
  8   i64  capacity (ring bytes, multiple of 4096)
  16  i64  tail        next free virtual position — the tail CAS
  24  i64  durable     every frame below it is final and forced — CAS-max
  32  i64  anchor-pos  frames below are folded into the current anchor
  40  i32  anchor-sel  which anchor page is current (0/1), CAS'd with anchor-pos's publish
  44  i32  durable-seq bumped with durable; the futex word waiters sleep on
  48  i32  recover-claim / 52 i32 checkpoint-claim   worker slot + 1 of the holder (CAS from 0)
  1024 i64 × 256  claim[w]  the position worker w is claiming (written before its tail CAS)
pages 1-2   anchor A / anchor B: [seq i64][epoch i32][pos i64][atom table 2,048 B][crc32]
page 3..    ring: capacity/64 frames of 64 bytes (4 MiB default → 65,536 frames)
```

**Positions:** virtual positions grow forever, and the physical frame is `pos mod capacity`. Frames are a fixed
64 bytes: no length field, no padding frames at the wrap, and one cache line per writer (no false sharing between
concurrent appenders).

**Preallocation:** the file is preallocated with real blocks (`posix_fallocate`, or one write of the file at creation),
not `setLength`. A sparse mapped file turns `ENOSPC` into SIGBUS on a store and adds metadata work to every `msync`.
The slab files have the same `setLength` exposure; that's out of scope here and will be reported.

### 2.3 Frame (64 bytes; the writer writes every byte it owns, none are pre-zeroed)

```
0   i64  state  = pos | s      s: 1 RESERVED · 2 COMMITTED · 3 ABORTED · 4 VOID   (the CAS word)
8   i32  crc32 over 16..63     12 i16 kind (SWAP, CREATE, ...)   14 i16 owner worker slot
16  i64  pos (CRC-covered)     24 i32 atom slot   28 i32 old ptr   32 i32 new ptr
36  u32  slot name hash after  40 i32 epoch after the bump        44..63 reserved
```

Because the position sits in the low-6-bits-free state word, a stale frame from the previous lap can never match:
validity is `state = p | COMMITTED` plus the CRC.

### 2.4 Commit (J1), next to today's

1. Read `old-ptr`, run `f`, build `new-ptr`. Unchanged.
2. Force the attempt's blocks: logged allocations plus touched descriptors, as the non-journal form does pre-CAS today.
   *Spike option:* force after the CAS instead, as J1 does now. That's measured; the pre-CAS form keeps the window
   between reserving and committing at microseconds.
3. `claim[me] := tail`, then `CAS tail t → t+64`. This is the reservation.
4. Write the frame body `(slot, old, new, hash, owner)`, then `state := p | RESERVED`.
5. `CAS` the slot pointer and bump the epoch. Unchanged.
6. On success: write the epoch and CRC, then `state := p | COMMITTED`. On a lost CAS: `state := p | ABORTED`, then retry
   from 1.
7. Group commit: wait until `durable ≥ p+64`. Whoever finds the frames from `durable` up to its own frame all final
   `msync`s that range of the `.jlog` and CAS-maxes `durable` to the end of the final run. Others sleep on
   `durable-seq`.
8. `drop-root!` and the retire flush as today. The retention gate in 2.8 applies.

**Ordering invariant.** A frame is reserved after its attempt read `old-ptr` and before its CAS. So for any two
committed CASes on the same slot, log order equals CAS order. And any frame that read a published pointer sits after
that pointer's frame in the log.
- Step 7's prefix rule then makes "acked" imply "every frame before mine is final and its blocks forced". That closes
  the shared-block hole in §1.
- It also means recovery folds frames in log order with no compare: no ABA, and no reliance on epoch order. That
  matters because the epoch bump isn't atomic with the CAS, and `increment-epoch!` (`:2569`) bumps without a commit.

**Batch (`:journal-batch` N) and window (`:journal-window-ms`)** keep their meaning. Steps 1-6 run per commit, which costs
no sync. Step 7's `msync` and advance run every N commits or when the window elapses, and the ack doesn't wait.

### 2.5 A writer that dies mid-frame

A waiter stuck behind a non-final frame at `p` finds its owner: from the frame body if it's written, else every worker
`w` with `claim[w] = p`. It waits while any such owner is live, judged the way `domain-live?` judges.

Once every owner is judged dead, the waiter resolves the frame by CAS on its state word:
- **No body:** the owner never reached its CAS → `VOID`.
- **`RESERVED` body:** the owner published iff the live slot `= new`, or a later frame with a body on that slot has
  `old = new` (that frame read `new` from the live slot). Then `COMMITTED`, otherwise `ABORTED`.

With the pre-CAS force (2.4 step 2), the dead owner's blocks are already durable, so resolving needs no whole-domain
force.

**Zombie.** An owner wrongly judged dead (a stale heartbeat but alive) finds `ABORTED`/`VOID` at step 6's CAS. It is
fenced: its swap throws loudly, the same exposure eve's stale-slot judgement already accepts for atoms. Whether to
judge death by heartbeat plus pid is a spike measurement.

### 2.6 Checkpoint and the wrap

The ring must never overwrite frames above `anchor-pos`.
- **When:** at 50% fill, at close, and on demand.
- **Who:** whoever wins `checkpoint-claim`.
- **What:** fold the current anchor plus every COMMITTED frame in `[anchor-pos, durable)` into the inactive anchor page.
  Every block those frames reach was forced before they committed. Then force that page and publish `anchor-sel` and
  `anchor-pos`.

It never reads the live table, so there's no snapshot race and no whole-domain force. A reserver that finds the ring
full runs the checkpoint itself, or waits on the holder.

### 2.7 Recovery (open, domain not live)

1. Win `recover-claim`.
2. Take the valid anchor: higher `seq` with a good CRC.
3. Fold COMMITTED frames from `anchor-pos` in log order, skipping ABORTED and VOID. Stop at the first frame that is
   neither, or whose position or CRC is wrong: that's the torn or unfinished tail.
4. Install the table, and set the epoch to `max(root, anchor, frames)` so it never goes backwards.
5. Write a fresh anchor, set `tail = durable = anchor-pos` = the end of the fold, and force.

Torn writes: a frame lives in one sector-aligned 64-byte cell, and the state word is an aligned 8-byte store. A torn
body fails its CRC, and a torn anchor loses to the other anchor.

### 2.8 Retention floor

**Rule:** a block superseded by commit `c` may be recycled only once `c`'s frame is below `durable`. The fold can never
stop before a durable frame, so recovery can never reach the old block.

**Mechanism:** retire-queue entries carry the superseding frame's position next to the epoch. `tally/flush!`'s safe
test adds `pos < durable`. This replaces the per-process `:floor` / `:last-epoch` / anchor-floor logic (`atom.cljc:650-700`,
`:809-836`).

In J1 every commit is durable before `swap!` returns, so the gate only ever holds back batch-mode entries.

### 2.9 What goes away, what changes

- **Deleted:** all eleven adapters under `adapters/journal-file/`, their `hosts.edn` rows and the README row.
- **Rewritten in `eve.atom`:** `journal-flush!`, `journal-note-commit!`, `journal-recover!`, `journal-anchor!` and
  `journal-checkpoint!` become calls into `eve.jlog`.
- **Inventory:** list every write to the root's atom table (swap/reset, atom create via `CLAIMED_SENTINEL` at `:2276`,
  init, delete if any) and give each a frame kind. A table write without a frame is unrecoverable.
- **Migration:** opening a domain that has `<base>.journal` and no `.jlog` reads EVJ1's newest valid record with a small
  portable reader and writes it as anchor A. Q1 and Q2 below decide the version bump and the old file.

## 3. Per-host work

| host | region | journal needs | status |
|---|---|---|---|
| JVM | jvm-varhandle | nothing new | runnable here once the Clojure CLI is installed (Maven answered 429 once; to retry) |
| Node / cljs | js + `mmap_cas.cc` | nothing new (i64 as Number ≤ 2^53, `msync`, futex present) | runnable here (Node 22, gcc present; addon to build) |
| jolt | jolt-ffi | `msync` `defcfn` + `ISyncableRegion` (H1) | **John's box**: no jolt here |
| bb | jvm-nio-lock | the native region is fine. The lock-only fallback CASes under file locks, which breaks "no OS locks", so the journal refuses it loudly. I haven't yet checked whether that fallback's CAS even excludes other families' hardware CAS; to check, and report if not | John's box (GitHub release download blocked here) |
| glojure, beam | glojure, beam | `ISyncableRegion` (H1) | John's box |
| clr, python, jank, letgo, dart | have sync + i64 CAS | nothing new | John's box |
| ValueScript | vs | today `unsupported`; stays so unless its region maps a file | — |

## 4. Tests (the brief's DONE MEANS → where each runs)

| test | content | where |
|---|---|---|
| spike | `eve.jlog` alone: N processes × M frames on JVM, jolt and Node at once. Every frame exactly once, CAS outcomes match, recovery from each runtime agrees byte for byte | JVM + Node here; jolt on John's box |
| conformance | the region contract the journal needs (i64 CAS on a mapped file, `sync-range`, wait/notify), run per region adapter | per host |
| recovery wounds | `journal_recovery_test.clj` W1-W4 rewritten for frames and anchors: stale root, same-epoch table corruption, torn tail, bad magic | JVM here, then each host |
| kill -9 | the existing crash recorders (`durability_crash_recorder*.clj/.lpy`), per runtime and per pair, killed mid-commit at random points incl. between reserve and commit | JVM + Node here; pairs with jolt on John's box |
| torn-write fuzz | random byte-range corruption and truncation of frames and anchors, then recovery must install a prefix-consistent state or refuse | here (pure file work) |
| shootout | every pair plus all together | John's box (jolt, bb, jank) |
| gates | jolt jtest-all, JVM `:jvm-test`, cljs all, dsgate U3, bset, jolt | JVM/cljs here if the toolchain installs; jolt and dsgate on John's box |
| perf | durable-commit ns per runtime before and after: the same workload as `durability_bench.cljs`, plus a JVM and a jolt twin | before = this tree as-is |

**Shootout caveat [John].** Two eve.db.ds bugs fail shootout checks regardless of the journal: known failure 2 (the
`:db/cas` report read after commit, a use-after-free) and failure 3 (cljs `:advanced` `d/q` binds nil). Both are outside
this job, so they're reported, not fixed. The journal's verdict will be read from the checks it owns: exactly-once, no
lost swap, readers agree. It runs with and without `WORKLOAD_FLAGS=nocas`, and the reason is stated beside each failing
cell.

## 5. Phases and exit criteria

1. **P0 inputs.**
   - Get the plan doc and BASELINE §18: they live in `/workspace/eve-perf` on John's box, which this cloud session can't
     see.
   - Install the Clojure CLI and build the Node addon here.
   - Measure base durable-commit ns (JVM, Node here; jolt on John's box).
   - Exit: numbers recorded with commit and tool versions.
2. **P1 spike.**
   - Build `eve.jlog` standalone, plus the multi-process append test, kill -9 and recovery, on JVM, Node and jolt.
   - Exit: one `.jlog` written by all three at once and recovered identically by each.
3. **P2 wire-in.**
   - `eve.atom` on `eve.jlog`, the table-write inventory, migration, the retention gate.
   - Delete the eleven adapters.
   - Exit: W1-W4 green, JVM and cljs suites no worse than base.
4. **P3 hosts.**
   - H1 for jolt, glojure, beam and the bb fallback decision.
   - Exit: conformance green per host.
5. **P4 crash and fuzz.**
   - kill -9 per runtime and per pair; torn-write fuzz.
   - Exit: zero acknowledged commits lost, zero unrecoverable opens.
6. **P5 gates, shootout, perf, PR.**
   - Every gate no worse than base, shootout per §4, before/after ns per runtime.
   - PR to SeniorCareMarket/eve, and to eve.db.ds only if a journal-side change needs one.

## 6. Risks I can name now

- Prefix group commit makes a slow committer delay its successors' acks. It's bounded by the window between reserving
  and committing, which the pre-CAS force keeps short. To measure.
- A zombie owner judged dead by heartbeat (2.5) is fenced, not silent. Whether pid liveness is available on every host
  is to check per host.
- `-cas-i64!` on Node is a JS Number. That's fine below 2^53, which is about 9 PB of positions.
- macOS: `msync` doesn't flush the drive cache (Q4).
- Out of scope, will be reported: the bb lock-only fallback's cross-family CAS (3) and the slab files' sparse
  preallocation (2.2).
