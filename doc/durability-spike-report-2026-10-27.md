# Eve 0.9 durability spike — report (2026-10-27)

Branch: `claude/eve-durability-spike` (off main `d2f163f056c6`). Spike only —
nothing here is landed to main. All changes are confined to `eve/`.
Evidence: `eve/doc/durability-spike-evidence/`. Status log:
`eve/doc/durability-spike-STATUS.md`.

## The hole (anchors verified firsthand on both hosts)

On the default path, nothing forces a commit's writes to durable storage:

- **JVM:** `JvmByteBufferRegion` (eve/adapters/region/jvm-varhandle/src/
  eve/region.clj:230) CASes via VarHandle on the mapped buffer with no
  `.force` anywhere in the type. Every `.force` in the file is in
  `LustreJvmMmapRegion` (line 428+), whose job is cross-node visibility
  under fcntl locks, not durability.
- **Node:** the addon's default `cas32`/`cas64` are a bare
  `__atomic_compare_exchange_n` (native/mmap_cas.cc:193, :246). Every
  `::msync(..., MS_SYNC)` in the file (lines 456, 502, 528, 554, …) sits
  in the Lustre fcntl functions under the "Lustre mode" header
  (mmap_cas.cc:336–342).
- A commit is one unforced CAS on the root slot (eve/src/eve/atom.cljc,
  `swap-attempt!`); `close-atom-domain!` never syncs; there is no
  sync!/flush! in the public API. Process crash is safe (MAP_SHARED —
  the kernel owns dirty pages); **system crash / power loss has no
  ordering guarantee**: the root's page can reach disk before the value
  pages it names, leaving a root that points at blocks never written.
  Reopen checks only a type-id tag at the root — no checksum, no
  previous-root fallback.

## The fix, stated in epoch terms

An epoch is a generation of the root: the root page holds one
current-epoch word (ROOT_EPOCH_OFFSET 8) and one root pointer per atom
slot; each commit advances the epoch and moves the pointer. (Retirement
*reads* epochs — retire entries are epoch-tagged and the min-safe-epoch
across worker pins gates reclamation. Epochs belong to the root, not to
retirement.)

The mechanism is a **durable-epoch watermark**, under one ordering
constraint, stated exactly: *the root-file update that names epoch E
(the atom-table slot CAS + the epoch bump) may only be written after
epoch E's slab writes are forced; the root file is forced after them.*
Writeback can only write what memory already holds, so a root that
reaches disk can never name an epoch whose blocks were not forced
first. The spike does not alter epoch semantics in any way — the bump
stays exactly where it is; pin / min-safe / retire-flush logic is
untouched. Durability here is forces + ordering around the existing
publish step, and nothing else.

Per commit, in `swap-attempt!`'s success path: (a) the new value is
written as today; (b) the domain's slab regions are forced (whole
regions — the spike's stated ceiling; see the scoped-force note);
(c) the root CAS lands and the epoch bumps, as today; (d) the root
range is forced — scoped to cover **everything in the root file that
names the epoch: the header's epoch word and the atom-table slot
that moved** ([ROOT_EPOCH_OFFSET .. slot pointer + 4)).

`sync!` (new public API, on an atom or a domain) **advances the
durable point to the live epoch**: force all slab state through the
current epoch, then the root file. The close-time sync in
`release-domain!` is a final `sync!`.

The variants, reported by the guarantee each buys:

- **A (current):** no epoch is guaranteed durable. On power loss the
  root file's pages can land before the slab pages they name; reopen
  can land on a root naming blocks never written. Price: baseline.
- **B (per-commit force, ordered; `:durable? true`, the default):**
  when a commit returns, its epoch is durable; a crash reopens at a
  complete epoch. **This is the only variant that may be called
  durable.**
- **C (lagged flush; `:durable? false` + `sync!` every 10 swaps):**
  **not crash-safe, and not reported as durability with lag.**
  Between flushes, commits dirty the root page and the kernel may
  land it early; open reads the single current root state, which can
  name an unforced epoch. C's numbers price **the force work of a
  lag policy only**; its safety half is the open design question
  below.

What is forced, per host:

- **JVM:** new protocol `eve.mem/ISyncableRegion` (`-sync!`,
  `-sync-range!`); `JvmByteBufferRegion` answers with
  `MappedByteBuffer.force()` / `.force(off, len)` (no-op for heap
  buffers). `alloc/sync-sio!` forces the slab ctx's slab + bitmap +
  descriptor regions for all 7 slab classes.
- **Node:** the addon gains a standalone `msync(buf, off, len)` export
  — the same `::msync(MS_SYNC)` primitive its Lustre fcntl ops use,
  page-aligned range form (mmap_cas.cc). `NodeMmapRegion` implements
  `ISyncableRegion` via it. The CLJS module slabs register a
  `:sync-domain-sio!` host op forcing each class's slab, bitmap and
  descriptor regions plus the coalescing region; `alloc/sync-sio!`
  dispatches host-op first, JVM fallback otherwise (the same shape as
  `refresh!`). The commit ordering itself lives in the shared
  atom.cljc path, so both hosts commit identically.

Proof the force is real: strace on the JVM — 10 counter swaps produce
**261 msync syscalls** with durable commits on, **21** with
`:durable? false` (the 21 are the close-time whole-domain force).

## The open design question (named, not built)

Under any lag (variant C's policy, or any future lazy mode), reopening
at the durable epoch needs **an on-disk record tying epochs to the
root pointers current at them**, so open can resolve the domain at the
newest durable epoch instead of reading the single current root
state. Today's layout — established from the code — retains exactly
**one** root pointer per atom slot and **one** current-epoch word;
previous epochs' root pointers are kept nowhere on disk. Scale,
honestly: the record is one pointer per atom per epoch, and the old
root *blocks* already persist by path-copying — what is missing is
only the epoch→pointer index and open-time resolution to it.

Also named there: **the root file's own torn-writeback risk.** A
crash during the root file's own writeback can leave the root page
itself torn; today open checks only the creation magic. A checksum
or a second copy of the root page belongs to that problem, not to
this spike.

**Scoped force (the known refinement, not built):** variant B forces
whole regions per commit — the stated ceiling. The attempt's
allocation log (alloc's LoggingSlabIO) records only the set of
32-bit slab offsets the attempt allocated/freed; it does not record
byte ranges written, descriptor-row writes, or bitmap pages touched,
so a commit's written ranges do **not** fall out of it cheaply —
deriving them is a range-tracking subsystem. The log's allocated set
is where a production scoped force would start (those blocks' slab
pages + their classes' desc/bitmap pages); the spike does not
establish that the log captures every write (serializer spill,
coalescing, tally pages), so whole-region forcing stands as the
measured ceiling.

## Perf — JVM (single host)

Medians of 3 interleaved rounds, µs/op (raw: evidence `single-r1..r3.txt`).
A = current code, B = durable fix, C = fix with `:durable? false` +
`sync!` every 10 swaps.

| workload | A | B | B/A | C |
|---|---|---|---|---|
| counter (3000) | 594 | 1,685 | **2.84×** (+1,091 µs) | 580 |
| ~1 KB value (1000) | 3,073 | 3,981 | **1.30×** (+909 µs) | 3,025 |
| ~64 KB value (300) | 3,601 | 4,444 | **1.23×** (+843 µs) | 3,624 |

The durable cost is a near-constant **~+0.9–1.1 ms per commit**,
independent of value size: forcing ~22 regions per commit. The
lagged-flush force work (C) is within noise of A at every size —
force work only; C buys no reopen guarantee (see guarantees).

## Perf — JVM (contention: 4 processes × 400 swaps, one shared atom)

Two interleaved rounds per variant (raw: evidence `contention-*.txt`).

| variant | makespan (ms) | aggregate ops/s | final value |
|---|---|---|---|
| A | 2,815 / 2,899 | 568.5 / 551.8 | 1600 ✓ / 1600 ✓ |
| B | 166,089 / 163,621 | **9.6 / 9.8** | 1600 ✓ / 1600 ✓ |
| C (lagged flush, sync/10) | 6,277 / 6,029 | 254.9 / 265.4 | 1600 ✓ / 1600 ✓ |

(C's row prices force work only — see the variant guarantees above;
its reopen safety is not established.)

B under contention is **~58× slower** than A — still exactly correct.
C is ~2.2× off A's pace and ~27× faster than B.

### Contention mechanics

- The force sits **inside** the CAS retry loop (per attempt, in
  `swap-attempt!`). It has to: the instant a CAS lands, readers in
  other processes can read the new root, so the epoch's writes must
  be durable *before* the pointer moves. Forcing once outside the
  loop would publish a root whose data is not yet durable.
- A lost CAS wastes that attempt's pre-CAS force. The retry writes
  its own new blocks and forces them; **nothing is held across the
  retry** — no lock, no reservation. (The default path has no lock;
  that is Lustre's design, not this one's.)
- Forcing adds no contention *mechanism*, but it lengthens the
  window between write and CAS by ~1 ms, so collision probability
  rises, attempts multiply, and every attempt pays ~22 msyncs — a
  positive feedback loop, measured above as 58×. The mitigations
  are written-range scoped forcing (fewer, cheaper forces per
  attempt) and lagged flushing (C's force-work price, measured:
  the loop nearly closes — at the cost of the reopen guarantee,
  per the open design question).

Ordering evidence (JVM, strace): five counter swaps produce an
identical 20-msync pattern per commit — 19 slab-region forces
(lengths 65600, 65600, 32832, 32832, 16448, 16448, 524352, 256, 128,
32, 16, 16, 16, 57344, 28672, 7168, 3584, 896, 448) followed by ONE
root force (length 6284 — the scoped [epoch word .. slot] range)
**last, never before its epoch's slab forces** (evidence:
`strace-order-jvm.txt`).

## Perf — Node (single host)

Medians of 3 interleaved rounds, µs/op (raw: `node-single-r1..r3.txt`;
A compiled from the pristine tree — its addon has no msync export at
all — B/C from the fixed tree).

| workload | A | B | B/A | C (lagged flush) |
|---|---|---|---|---|
| counter (A/C 1000, B 300) | 68.7 | 85,161 | **≈1,240×** (+85.1 ms) | 9,089 |
| ~1 KB value (200) | 2,098 | 50,338 | **24.0×** (+48.2 ms) | 8,448 |
| ~64 KB value (50) | 607 | 49,256 | **81×** (+48.6 ms) | 12,870 |

A's ~64 KB row is noisy across rounds (415–2,552 µs); medians used
throughout. The Node force round costs **~+48 to +85 ms per commit** —
the same ~20 forces as the JVM, at ~50–100× the JVM's per-force
price (next section). C's force-work price amortizes to ~+6–12 ms/op
at a flush every 10 swaps; as on the JVM, C buys **no** reopen
guarantee.

## Perf — Node (contention: 4 processes × 100 swaps, one shared atom)

Two rounds per variant (raw: `node-contention-*.txt`); every run's
final value is exactly 400.

| variant | makespan (ms) | aggregate ops/s |
|---|---|---|
| A | 304 / 65 | 1,315 / 6,128 |
| B | 40,503 / 38,873 | **9.9 / 10.3** |
| C (lagged flush) | 1,354 / 1,334 | 295.5 / 299.8 |

A's makespan varies (short loops, worker startup jitter); B is
~10 ops/s — the same positive-feedback shape as the JVM (longer
write→CAS window, every retry re-forces), at Node's per-force price.

## The JVM-vs-Node cost shape (the msync comparison)

Same primitive, same ordering, wildly different price — measured, not
assumed:

- JVM: the whole ~22-force commit round is **~1.1 ms** (~50 µs per
  `MappedByteBuffer.force` on the live domain files).
- Node: the same round is **~50–110 ms**. strace with durations on a
  live domain: 221 msyncs ≈ 1.19 s of a 1.13 s 10-swap loop; single
  calls run 1–30 ms **regardless of range size** (a 4 KB force can
  cost 31 ms; a 516 KB force 1.5 ms).
- Controls: the identical addon `msync` on a **quiescent** domain
  file costs ~0.67 ms/call; on a plain fresh file, ~0.001 ms/call
  clean and ~0.6 µs per dirtied page.

So the Node price is the writeback state of eve's live domain files,
not the syscall, the range, or the primitive. The spike does not
diagnose the kernel/filesystem reason further. Consequence for a
production form: **scoped forcing matters far more on Node** — force
only the pages a commit touched and the ~20-force round collapses;
the whole-region ceiling measured here is the pessimistic bound on
both hosts, ~100× more pessimistic on Node.

## Correctness

- **JVM, no regression:** the spike's 4 new reopen tests
  (write-sync-reopen; durable commits read back; group-commit shape;
  `sync!` on a domain) + the existing JVM atom suites (jvm-atom,
  jvm-atom-cas, jvm-atom-lost-attempt, jvm-atom-retire): **44 tests,
  210 assertions, 0 failures** (evidence: `jvm-atom-tests-fixed.txt`).
- **Coop scenario, JVM threads** (the suite's own scenario source;
  4 writers × 40 ops, one shared disk atom): snapshot exact
  (counter 160, checksums 40/40/40/40, balance-sum 10000),
  **violations []** (evidence: `coop-jvm-contention-fixed.txt`).
- **Coop scenario, mixed host** (JVM init; 2 JVM writer threads +
  2 Node worker processes × 40 ops on the same atom, Node workers
  running the durable path): Node workers complete 40/40 ops; JVM
  verify **violations []**; the Node `coop-read` prints the identical
  value (counter 160) — cross-host agreement on the shared bytes
  (evidence: `coop-mixed-*.txt`).
- **Node atom suites** on the fixed tree: see
  `node-atom-suites-fixed.txt` (mmap-atom, mmap-domain,
  mmap-atom-e2e).
- **The coop suite itself still cannot run as a suite**, for a
  pre-existing reason unrelated to this spike: its orchestrator
  namespace does not compile against the vendored eve at main
  ("No such var: atom/$" at coop_atom_test.clj:166), verified
  identical on the pristine base tree. The scenario-level checks
  above drive the suite's own shared source and its built Node
  worker — the participants run; the orchestrator does not.
- **Journal crash-recovery** (driver:
  `journal-crash-driver.clj`; halt without close, root wounded by
  hand, reopen after heartbeat staleness; evidence:
  `journal-crash-tests.txt`): **J1 reopens at 15/15** swaps;
  **J10 reopens at 8** — correct, not a shortfall: the batch
  counts epochs, the first flush lands at epoch 10 = 2 setup
  commits + 8 swaps, and swaps 9–15 were never journaled, lost
  whole as the guarantee states; **J1 with 100 garbage bytes
  appended reopens at 3/3**, torn tail discarded. Journals are
  0 bytes after recovery + close (checkpoint truncation works).
- **Cross-host journal** (driver: `journal-xhost-driver.clj`;
  evidence: `journal-xhost-check.txt`): a JVM process wrote 5
  epochs to a J1 domain and halted unclosed; the root was wounded
  by hand; a **Node** process opened the domain, validated the
  JVM-written records (framing + CRC32 identical), installed
  epoch 5's table, checkpointed at close; the JVM then read the
  value back: **5**. The journal byte format is host-independent
  in fact, not just in design.

## Remainders (named, out of spike scope)

1. **Allocator bitmap crash-consistency as a design.** The spike
   forces bitmap regions with everything else; whether the
   allocator's mmap'd bitmaps are themselves crash-consistent (a
   bitmap page landing without its slab page, or vice versa) is a
   design question, not a call — untouched here.
2. **The epoch→roots record** for any lagged mode (the open design
   question above), including the root file's torn-writeback
   protection (checksum / second copy).
3. **Scoped forcing** (production refinement of the whole-region
   ceiling; the attempt log gives allocated offsets only — see the
   design section).
4. **Power-loss simulation is not tested.** `kill -9` does not
   simulate power loss for mmap (the kernel still owns the dirty
   pages); nothing here claims it does. The guarantees stated are
   ordering guarantees, evidenced by the strace force order.
   (The follow-on phase runs the SQLite-pattern simulated-crash
   suite against this branch's force boundary; its addendum is
   `durability-crash-suite-2026-10-27.md`.)
5. **Browser/SAB regions** have nothing to force (no file); `sync!`
   on a heap domain is a no-op by construction.
6. **Torn-tail repair by live appenders** (leaderless section,
   point 5): a process dying mid-append leaves bytes the next
   live appender does not validate or cut; repair happens at
   recovery. The fix shape is named there; not built.
7. **Watermark-waiting `sync!`** (point 3): `sync!` always pays
   its own force; the form that waits for another process's flush
   to carry the caller's epoch past the watermark is not built.
8. **Heterogeneous batch triggers** in one contention leg:
   coordination is by construction (§leaderless, point 1); the
   measured legs ran homogeneous triggers.

## Machine note

Numbers come from two disks: this box (the hatch VM; btrfs over
overlay) and the build server j.slaa.us — each table is labeled.
JVM 21.0.7+6 (server: 21.0.9) / Node 24, eve 0.9 vendored at main
`d2f163f056c6`. This box's VM was replaced mid-day and the
replacement's disk prices forces ~50× higher than the morning
session's; every comparison in this report is between variants
interleaved inside one session on one disk, never across sessions.
The portable facts are the force counts, the ordering, and the
curve's shape (J1 ≈ B; batching divides; J100/J1000 ≈ A); the
milliseconds are each disk's own — which is precisely why the
batch capacity recommendation is a latency-derived range, not a
constant. A third data point is set up for John's Mac:
`doc/durability-spike-evidence/run-bench.sh` runs the whole
matrix in one command and prints paste-able tables.

Branch tip: `claude/eve-durability-spike` (see STATUS for the tip
commit at declaration time).

## Variant J — the commit journal (the fix this spike recommends)

B's weakness is structural: it trusts the root file. The root file
is one mmap'd page set whose writeback the kernel schedules freely,
its own torn writeback is unprotected (open checks only the creation
magic), and an epoch is durable only because its root range happened
to be forced after its blocks. J removes the root file from the
trust path: **the root file becomes a checkpoint, and a per-domain
append-only journal becomes the durable record of which epochs
committed.**

**The journal.** `<base>.journal` beside `<base>.root`. Records
carry commit metadata only — never values:
`[magic][len][epoch][table-len][atom-table bytes][crc32]`. The
table is the root region's raw atom-table range (256 slots × 8 B =
2,048 B — one pointer per atom per epoch), so the newest valid
record *is* the newest fully journaled epoch, installed whole — no
folding. Framing + CRC: a torn record (crash mid-append) is skipped
by its length; garbage that does not frame ends the scan.

**Commit path** (live machinery untouched — the root CAS + epoch
bump still coordinate running processes exactly as today; epochs,
pins and reclamation are unchanged in semantics, and the journal
decides nothing about liveness):

- **J1:** write the epoch's blocks → force the slab ranges (B's
  whole-region sweep) → root CAS + epoch bump → append the flush
  record → **force the journal: that force is the commit point.**
  A commit that returns has its record durable.
- **J10:** no per-commit force. Each commit publishes live and
  counts toward a batch of 10; the flush does one whole-domain
  slab sweep → appends one flush record (epoch + table at flush
  time) → one journal force. Full-table records + a whole-domain
  sweep make cross-process batching sound: the sweep forces every
  dirty block in the domain, whichever process wrote it, before
  the record that names it can land.

**Recovery.** At open, before the opener claims its worker slot,
under the journal lock — and only when the domain is **not live**
(no ACTIVE worker slot with a fresh heartbeat; heartbeats tick
every 10 s and go stale at 30 s, so a post-crash opener inside the
stale window defers recovery to a later open — it never corrupts):
read the checkpoint's epoch, take the newest valid journal record,
and when its epoch differs from the mapped root's, install its
table + epoch into the root region and force the root file (the
checkpoint is now current). The torn tail is discarded logically —
the scan stops at the first invalid record; physical truncation
happens at the next checkpoint. A live domain is joined through
its live root, journal untouched.

**Checkpointing** (the picked trigger): `sync!` and close. Both
flush any pending batch, force the whole domain (root file
included — it becomes the checkpoint), then truncate the journal
to empty under the lock. Between checkpoints the journal grows
one record per flush (~2 KB); a size bound is the production
refinement.

**Bitmaps — the named choice.** Bitmap consistency is *deferred*;
alloc/free deltas do not ride the records. The closing is
**rebuild-from-roots at recovery**: walk the installed epoch's
reachable blocks and rebuild the allocation bitmaps from them.
Rationale: bitmap pages are forced with the sweep, but writeback
between flushes can leave them at no epoch in particular; a
recovery that must be exact cannot trust them, and a root walk is
the one reconstruction that depends only on the installed table.
Named, not built.

**Append serialization — the named choice.** A lock on the journal
file (JVM `FileLock`), held across each flush (append + force) and
across recovery/checkpoint truncation, plus this process's monitor
on the channel. It never touches the live CAS path: commits race
exactly as today; only flushes queue. A crash mid-append leaves a
torn tail record that fails framing/CRC at scan — that epoch is
uncommitted-durably. No survivor can have read it from a durable
world: durability is defined at the journal, the record never
validated, and in a system crash every local process dies together
anyway. (A lone process crash leaves the same torn tail for the
next opener's scan; the epoch's live readers died with the process.)

**Guarantee, in epoch terms.** J reopens at the newest fully
journaled epoch — batching included: a J10 domain reopens at its
last flushed epoch, losing the unflushed batch *cleanly* (whole
epochs, never a torn one), because recovery installs a complete
journaled table or the checkpoint, never a root that names
unforced blocks. That is the property C cannot state and B states
only per-commit at force prices C and J10 amortize.

**What J costs** is measured in the next section, on two disks.
The shape everywhere: J1 ≈ B (the same slab force work dominates
both), and batching divides it — J10 is 3–10× cheaper per commit
than B/J1, J100 and J1000 sit at or near A's price. The price of
a batch is stated beside it in every table: a crash loses up to
N−1 unjournaled epochs (J10: 9, J100: 99, J1000: 999) — whole
epochs, never torn ones.

## Perf — Variant J (single host, three hosts/disks)

JVM, this box (evening session — the VM was replaced mid-day and
the replacement's disk prices forces ~50× higher than the morning
session's; only same-session interleaved rounds compare, and these
are they). Medians of 3 interleaved rounds, µs/op (raw:
`j-single-r1..r3.txt`, `j2-single-r1..r3.txt`):

| workload | A | B | J1 | J10 | J100 | J1000 |
|---|---|---|---|---|---|---|
| counter | 898 | 110,331 | 106,821 | 11,832 | 1,216 | 410 |
| ~1 KB | 6,792 | 64,202 | 60,349 | 12,314 | 2,391 | 1,832 |
| ~64 KB | 5,610 | 63,476 | 65,468 | 11,572 | 1,873 | 1,311 |

(J100/J1000 columns are medians over the batch-curve rounds, whose
A/J10 anchors — A counter 438, J10 counter 9,587 — sit inside the
same session's drift band.)

JVM, build server j.slaa.us (a second disk; medians of 2
interleaved rounds, µs/op; raw: `server/server-single-r1..r2.txt`):

| workload | A | B | J1 | J10 | J100 | J1000 |
|---|---|---|---|---|---|---|
| counter | 205 | 2,558 | 3,439 | 481 | 186 | 149 |
| ~1 KB | 982 | 2,506 | 3,334 | 1,227 | 959 | 994 |
| ~64 KB | 856 | 2,524 | 3,543 | 1,111 | 916 | 792 |

Node, this box (medians of 2 interleaved rounds, µs/op; raw:
`node-j-single-r1..r2.txt`):

| workload | A | B | J1 | J10 | J100 | J1000 |
|---|---|---|---|---|---|---|
| counter | 270 | 118,755 | 128,796 | 12,754 | 2,127 | 1,141 |
| ~1 KB | 8,766 | 97,351 | 67,761 | 21,661 | 19,795 | 17,270 |
| ~64 KB | 10,467 | 87,143 | 78,825 | 27,704 | 30,996 | 24,028 |

Node's A at the KB sizes is itself noisy on this box (allocation
variance); the counter row is the clean read. Everywhere, the
ordering is the same: **J1 ≈ B; batching is the whole game.**

JVM, John's Mac (Apple Silicon, OpenJDK 26; run via
`run-bench.sh`, one round; µs/op):

| workload | A | B | J1 | J10 | J100 | J1000 |
|---|---|---|---|---|---|---|
| counter (2000) | 75.5 | 328.8 | 4,311.3 | 505.1 | 126.2 | 61.4 |
| ~1 KB (500) | 418.7 | 1,391.2 | 5,161.9 | 876.9 | 462.2 | 376.6 |
| ~64 KB (200) | 625.6 | 1,734.5 | 4,634.3 | 769.3 | 1,214.9 | 669.9 |

The Mac is the one venue where **J1 loses to B — by ~13×** — and
the mechanism is measurable: the two primitives price oppositely
there. B's entire per-commit force round costs ≈0.25 ms over A
(the mmap forces are nearly free on that disk), while J1's commit
additionally pays the journal flush — lock + append + fdatasync —
measured at ≈3.8–3.9 ms per flush on that machine; macOS prices
a file fsync like a full barrier, and J1 pays it per commit. On
this box's evening disk the terms invert (the ~22-force sweep is
the ~100 ms term, the fsync comparatively cheap), so J1 ≈ B.
Which of B/J1 wins per-commit is a property of the disk's
fsync-vs-msync pricing, not of the designs — and batching
amortizes exactly the expensive term either way: on the Mac J10
already beats B, and **J1000 ≈ A at every size on all three
disks.**

## Perf — Variant J (contention: 4 processes × 100 swaps, one shared atom)

Aggregate ops/s; every leg's final counter exactly 400 (raw:
`j-contention-r1..r2.txt`, `server/server-contention-r1.txt`,
`node-j-contention-r1..r2.txt`):

| host | A | B | J1 | J10 | J100 | J1000 |
|---|---|---|---|---|---|---|
| JVM, this box (median, 2 rounds) | 294 | 9 | 9 | 69 | 145 | 368 |
| JVM, server (1 round) | 1,434 | 291 | 225 | 1,006 | 1,302 | 1,490 |
| Node, this box (median, 2 rounds) | 2,283 | 9 | 10 | 86 | 680 | 1,726 |

J1000 meets or beats A under contention on both JVM disks: its
workers almost never flush inside the window, while A pays pure
CAS contention. J1 inherits B's positive-feedback collapse
(§Contention mechanics) — the pre-CAS sweep inside the retry loop
is the cost, not the journal append (next section).

## Flush latency — the batch is a time window

A flush is one slab sweep + one journal append + one journal force,
timed whole-call at `journal-flush!` (`eve.atom/*flush-timing*`,
bench instrumentation). Means are flush-count-weighted; max is
the worst single flush.

| where | batch | mean | max |
|---|---|---|---|
| JVM this box, single-host | J10 | 51–93 ms | 460 ms |
| | J100 | 67–94 ms | 469 ms |
| | J1000 | 128–147 ms | 399 ms |
| JVM this box, contention (per worker) | J10 | ~500 ms | 1,142 ms |
| | J100 | ~750 ms | 1,002 ms |
| | J1 (append+force only; its sweep is pre-CAS) | 42 ms | 365 ms |
| JVM server, single-host | J1 (append+force) | 1.34 ms | 14 ms |
| | J10 | 3.2–4.6 ms | 26 ms |
| | J100 | 4.4–17.6 ms | 31 ms |
| | J1000 | 10.4–10.8 ms | 25 ms |
| JVM Mac, single-host | J1 (append+force) | 3.79–3.91 ms | 42.9 ms |
| | J10 | 3.89–3.95 ms | 7.8 ms |
| | J100 | 4.01–5.85 ms | 8.0 ms |
| | J1000 | 6.36 ms | — |

The Mac's flush latency is **~4 ms flat regardless of batch
size** — its sweep is cheap, so a flush is one fdatasync plus
overhead at any batch. There the capacity product is the
smallest of the three venues: A's counter rate (1/75.5 µs ≈
13,244 commits/s) × 4 ms ≈ **53 commits accumulate per flush**,
~120 at the ~9 ms max — a J1000 window on that disk holds barely
a tenth of a second of commits.

Two facts the sizing turns on:

- **Within a run, the signal is tame**: max/mean ≈ 2–9×. **Across
  sessions and disks it is not**: the same flush costs ~1–5 ms on
  the morning disk and the server, ~50–150 ms on this box's
  evening disk — a ~30–100× swing in the mean itself.
- Flush latency × the unflushed commit rate = commits that
  accumulate while one flush is in flight. At this box's evening
  counter rate (A ≈ 438 µs/op ≈ 2,280 commits/s), one mean flush
  (93–128 ms) admits ~210–290 more commits; one worst flush
  (1,142 ms, contention) admits ~2,600. That product — not the
  batch count — is what sizes the journal.

## Adaptive batch capacity (design; the spike measures, this sizes from the measurements)

Fixed batch counts cannot be right: the two disks measured differ
~30–70× in flush latency, and one disk swung ~50× between morning
and afternoon. Capacity should be **derived from measured flush
latency, continuously**:

- **Control law.** capacity = commit rate × EWMA flush latency ×
  headroom (≈2). The batch then always holds roughly one
  flush-latency's worth of commits, plus margin — the journal is
  sized by the time a flush actually takes on this disk, not by a
  constant fitted to someone else's.
- **Feedback signal.** An exponentially-weighted average of flush
  latencies — never the latest sample. Within-run max/mean of
  2–9× makes a raw signal unusable; across-session drift of
  30–100× makes a static one wrong. Resize **only at flush
  boundaries** (a flush is the natural sample point), never
  mid-batch.
- **Cutoffs.** A floor (~100: below it the per-commit flush share
  exceeds the commit's own cost at every measured size — see the
  range below) and a ceiling stated **both** as a count and as a
  maximum flush interval in time — whichever hits first triggers
  the flush — so the loss window stays bounded in *time* when
  commits are slow and in *epochs* when commits are fast.

**Is the signal stable enough to control on?** Plainly: yes with
damping and clamps, no without. Within a session the latency
distribution is tight enough (max/mean 2–9×) that an EWMA tracks
it; between sessions the mean itself moves 30–100×, so the
controller's job is exactly tracking that drift at flush
boundaries, inside the clamps. An undamped controller would
oscillate with the 9× within-run spikes; an unclamped one would
let a slow disk stretch the loss window without bound.

### Recommended valid range

**[100, 1000] epochs, with a ~1 s maximum flush interval —
whichever limit hits first.**

- **Floor = 100**, resting on the pooled single-host flush means
  (both disks) against the unflushed commit costs. Per-commit
  flush share is F/N. This box evening: F ≈ 90–130 ms, so N=10
  leaves a 9–13 ms share against commit costs of 0.44–1.8 ms —
  the flush dominates the commit at every size (measured: J10 is
  still 6–22× A). At N=100 the share is 0.9–1.3 ms ≈ the counter's
  own cost and below the KB sizes' (measured: J100 within
  1.3–2.8× of A everywhere; J1000 indistinguishable from A). The
  server's F ≈ 3–5 ms gives the same knee (N=10 share 0.3–0.5 ms
  vs commits of 0.2–1.0 ms). Below 100, batching stops paying on
  every measured combination.
- **Ceiling = 1000 epochs / ~1 s**, resting on the batch curve
  plus the worst measured flush. Past 1000 the measured µs/op
  gain is zero (J1000 ≈ A at every size on both disks) while the
  loss window grows linearly for nothing. The time form binds the
  other direction: worst case at the ceiling, a crash finds
  N + rate × F_max unjournaled epochs — at this box's counter
  rate (2,280/s) and the worst measured flush (1,142 ms,
  contention) that is 1000 + ~2,600 ≈ 3,600 epochs ≈ 1.6 s of
  commits; capping the flush interval at ~1 s of wall time bounds
  the window in time regardless of rate. Journal growth at the
  ceiling is bounded too: one ~2 KB record per flush, ~2,280/1000
  flushes/s at full counter rate ≈ 4.7 KB/s between checkpoints.

## Multi-process coordination of the shared journal, leaderless

How the design as built answers the coordination questions — and
where the spike is weaker than the point, stated plainly.

1. **Capacity is never agreed on and never shared.** The batch
   size is each process's local flush trigger (a per-open option;
   the pending counter lives in the process's own domain state).
   A flush is domain-wide — whole-domain sweep, full-table
   record — so any process's flush makes every epoch up to that
   point durable, whoever committed it. Processes running
   different triggers coexist; the effective flush rate is the
   most conservative process's. No invariant depends on the
   capacity value. *(As built: yes. The contention legs ran
   homogeneous triggers; heterogeneity is by construction, not
   separately measured.)*
2. **The only shared durable state is the journal itself** and
   the durable-epoch watermark — the newest valid record's
   epoch. It is monotone, readable by all (a scan), advanceable
   by any process under the append lock. Nothing is voted on;
   there is no leader to elect and no quorum to lose.
3. **Guarantees are per-epoch, not per-process.** A process that
   opens without the journal (or with `:durable? false`) commits
   without flushing; its epochs become durable when anyone
   flushes, because the sweep is whole-domain and the record's
   table is read live at flush time. `sync!` is the only promise:
   *as built, it does not wait on the watermark — a journal
   domain's `sync!` flushes and checkpoints itself under the
   lock, and a non-journal domain's `sync!` pays its own full
   force.* The wait-until-the-watermark-passes-my-epoch form is
   the production refinement; the spike's form is stronger
   (always forces) and dumber (never rides another process's
   flush). Stated plainly: that part is not built.
4. **Checkpoint under concurrency.** Checkpoint (flush pending →
   force the root file → truncate the journal) holds the same
   journal lock appends take. A racing commit's record either
   landed before the checkpoint's flush (it is covered by the
   checkpoint's epoch — appends happen after the commit's CAS in
   program order, and the root force follows) or appends after
   the truncation into the fresh journal. Never between: the
   lock serializes the two, and the append-after-CAS order makes
   coverage exact.
5. **A process dying mid-append while others live.** The OS file
   lock dies with the process; the torn bytes it left fail
   framing/CRC. *Here the spike is weaker than the ideal:* a live
   appender does **not** validate and cut the torn tail before
   appending — it appends after it, so records written after the
   tear are unreachable at recovery (the scan stops at the first
   invalid record). The loss is bounded — epochs journaled after
   the tear, visible only at the next recovery; live operation is
   unaffected, and no survivor's already-journaled work is
   touched — but the next-appender-repairs property is **not
   built**. The fix shape is small (under the lock, scan from the
   process's cached valid end: adopt valid records, truncate at
   the first invalid byte) and belongs to the production form;
   repair today happens where the scan already runs — at
   recovery, on a non-live domain.

## VS seat parity (follow-on unit, 2026-10-27)

John's order: fix both VS-seat journal blockers — the VS region's
stub `-with-file-lock` and its missing force — "we want parity
maintained." Scope note: the doorbell host and `vs.mmap` live
outside `eve/` (`vs/hosts/eve-mmap/vs-eve-host.c`,
`vs/src/vs/mmap.vs`); John's order names them, so this unit's
changes extend there. Nothing lands to main, as everywhere in
this spike. Evidence: `durability-spike-evidence/vs-seat/`.

**Ground truth first (two census corrections).** VS has been a
full participant in shared disk atoms since 2026-10-07 through
the coop route — `test/coop-atom/run.sh`'s JVM orchestrator
drives eve's JVM, Node, nbb, bb and the wasm participant
(`wasm/coop_worker.vs`, shipped builder, run by vs-eve-host)
against one mmap atom; COOP-ATOM-REPORT.md's bottom line: the
VS program "reads and writes the same files with the same CAS
protocol as eve's JVM, Node and bb runtimes" (round trip
Node→JVM→bb→wasm, JVM+wasm contention 5/5 in round 1's terms;
ROUND2 §6: 3/3 at 5× scale, the 20,000-op soak 3/3). The census
probes that suggested otherwise never used that route — bare
wasmtime has no doorbell host, so a first-call trip there
measures the missing host, not VS — and were compiled with an
installed `vs` whose pair anchors to another clone. Both census
drafts are corrected in STATUS (errata 1–2). The coop suite
coordinates by the CAS protocol; it never needed the journal's
append lock or a force. Those two were the real VS journal
blockers, and both are now built.

**The lock — mechanism measured before building.** A JVM
process holding `FileChannel.lock` (the journal's lock);
contenders in a second process: fcntl `F_SETLK` BLOCKED,
`F_OFD_SETLK` BLOCKED, `flock(2)` ACQUIRED (no exclusion),
mkdir lock directory ACQUIRED (no exclusion). Reverse: fcntl
holders (either flavor) make the JVM's `tryLock` fail.
Conclusion: fcntl byte-range locks are the cross-host
mechanism; flock and mkdir live in separate lock domains.
Two consequences stated plainly: (1) the VS host uses
`F_OFD_SETLK` — the lock is owned by the mapping's own
descriptor, which the host holds for the window's life (a
classic lock would drop if the process closed any descriptor
on the file); (2) **Node's journal lock is a mkdir directory,
which does not exclude the JVM's fcntl lock either** — the
{JVM, VS} fcntl domain vs Node mkdir domain split predates
this unit (it is in the spike's own phase-1 journal code) and
is not a regression this unit introduces. Closing it is a
Node-side change (the native addon could export an fcntl
lock); not built here.

**What was built.**
- vs-eve-host: doorbell OP_SYNC 10 (msync `MS_SYNC` over a
  page-aligned range inside a file window), OP_FLOCK 11 /
  OP_FUNLOCK 12 (one non-blocking OFD attempt per request,
  refcounted held table — the guest spins, so the doorbell
  thread never blocks), OP_TRUNC 13 (ftruncate, shrink
  included — the journal's checkpoint op; EXTEND only grows
  by design).
- `vs.mmap`: `sync!`, `flock!` (EAGAIN/EACCES → false),
  `funlock!`, `truncate!`.
- `eve.region` (VS adapter): `-with-file-lock` on a file
  window is real (guest spin 5 ms × 6000 ≙ 30 s, the Node
  lock's shape; a heap window has no other seat that could
  contend and runs `f` directly); `eve.mem/ISyncableRegion`
  on a file window — `-sync!` / `-sync-range!` through
  `vs.mmap/sync!`, no-op for heap. B's path reaches the force
  unmodified (`alloc/sync-sio!` walks the ctx regions through
  `mem/sync-region!`), as does the journal's pre-CAS sweep.

**Proof (banked in `evidence/vs-seat/`).**
- Force: a VS program under vs-eve-host stores 4242 through an
  mmap region, `mem/sync-region!` whole + ranged → the word is
  on disk afterwards (`VS-SYNC-OK 4242`).
- Lock exclusion, both directions, real code: VS holding via
  `-with-file-lock` → the JVM's `FileChannel.tryLock(16,4)`
  BLOCKED/DENIED and a python OFD attempt BLOCKED (EAGAIN).
  The JVM holding `FileChannel.lock(16,4)` → VS's `flock!`
  returns false; after release, true.
- The other seats unharmed: JVM atom suites re-run — 44
  tests / 210 assertions, 0 fail (identical to before);
  journal crash tests re-run — J1 15/15, J10 reopens its
  journaled 8, torn+garbage 3/3, journals 2068 B after
  recovery+close; Node J1 bench re-run at all three sizes,
  every journal 2068 B after close. The parity diff touches
  no JVM or Node code path.

**The journal on VS — what is still missing, exactly.** The
census probe (journal domain, 2 swaps, `sync!`, read back),
run the coop route — this tree's `vs/bin/vs` compiling, the
result to run under vs-eve-host — does not get to run: the
COMPILE fails, `X 2 0 18161 macroexpand: Unable to resolve
symbol: translate-protos in this context` (rc 75). A
require-only probe (no calls) fails identically, so the wall
is in macroexpanding eve.atom's own graph: `eve.types`
(adapters/types/vs) defines its `deftype` macro over
`translate-protos`, and the shipped builder's macro evaluator
cannot resolve it. **Control: the same probe against pristine
main (`d2f163f056c6`) eve roots fails with the same error**
(17,825 forms vs the spike's 18,161 — the delta is the spike's
own added forms). The wall predates this spike entirely; it
is the EM4 held-half item the hosts.edn `:vs` comment names
("eve/deftype's expansion waits on a builder carrying EM4's
held half") — the shipped-builder deftype question, out of
scope for this unit by John's steer. The lockprobe graph
(eve.region + eve.mem + vs.mmap — no deftype macros) compiles
and runs under the same builder and host, which is why the
lock and force proofs above stand on their own.
One more missing piece sits behind that wall, named from a
code read (it cannot be run until the wall falls): VS compiles
`.cljc` with the `:clj` reader feature only (measured), so the
journal's `:clj` branch is what a VS build would execute —
and it calls `java.nio.channels.FileChannel`,
`java.nio.ByteBuffer` and `java.util.zip.CRC32`, for which no
VS shim exists anywhere in `vs/src` (searched). When a
held-half builder lands, the journal still needs that shim
surface (or a doorbell-backed channel) before
`journal-open!` can run on VS. The two blockers this unit was
ordered to fix are the ones beneath both of those: the
journal's lock and force now exist on the VS seat, with
cross-host exclusion proven.

**Mixed cell with a VS writer: not run, decision stated.** A
journal-participating VS writer waits on the same builder
wall. The coop suite's wasm writer (`vs.eve-disk`) is not a
journal participant — it speaks the CAS protocol only — so
adding it to a journal cell would test CAS interop, which the
coop reports already prove, not the journal. The cell is
recorded as gated on the held-half builder, not as passed.

## Phase 3 — extreme multi-runtime contention, 5 s journal window

Eight writer processes across six runtimes share one domain (a
counter atom + record-map atoms at ~1 KB / ~100 KB / ~1 MB /
~100 MB of many small records), seeded mix 40/35/15/8/2, 60 s
per writer, then SIGKILL; a fresh JVM recovery-opens a copy and
a checker verifies the op logs against the recovered state.
Cells: A (no durability) vs J with `:journal-window-ms 5000`
(time trigger on the commit path; count batch 1,000,000 as
backstop). Evidence: `phase3-cell-A.json`, `phase3-cell-J.json`,
`phase3_runner.py`, the workload sources under `eve/test/eve/`.

### Census corrections this phase forced

- **cljr and lpy cannot run the journal as built.** The
  journal's host branches are `#?(:clj …)` Java code
  (FileChannel/ByteBuffer/CRC32). Measured reader features:
  ClojureCLR reads `.cljc` with `#{:cljr}`, Basilisp with
  `#{:lpy}` — the `:clj` branch is dead on both, and any defn
  whose body is only a `#?(:clj …)` compiles empty there (the
  spike's `now-ms` returned nil on both hosts; their writers
  died at the first window check). Their phase-3 census "✓
  journal" probes are hereby downgraded: they opened, swapped
  and read back in-process, which a no-op flush also passes.
  In the J cell both join as **non-journal participants**
  (the leaderless design's standing case: their epochs become
  durable when anyone flushes). A real port needs per-host
  append branches (FileStream on CLR, os/fcntl on lpy) — named
  remainder, not built. `now-ms` itself now has a `:default`
  arm (proc/now-ms) so it cannot compile empty anywhere.
- **jolt** likewise joins as a non-journal participant (its
  host interop cannot resolve `FileChannel.lock`).
- **VS** is a full disk-atom participant via the coop route
  (see the parity section) but a journal writer waits on the
  shipped-builder deftype wall characterized there. **bb**
  cannot load eve.encoding under SCI as installed; **clje**
  was never staged (Erlang/OTP install stalled on this box's
  proxy); **cherry/browser/scittle** have no disk domain.

### Two bugs found by the cells, both fixed on this branch

1. `:journal-window-ms` was silently dropped by
   `persistent-atom-domain` (never forwarded to
   open-mmap-domain!) — no window flush ever fired until this
   phase's smoke cells exposed a 0-byte journal. Forwarded;
   probe: 15 flushes in 1.5 s at a 100 ms window.
2. The window/clock paths used JVM-only `System` calls in
   `:clj` branches (see the census correction above); the
   clock is now `eve.process/now-ms` on every host.

### Correctness verdicts

- **Cell A** (control): 67,499 ops; counter recovered exactly
  28,428 = logged increments; 3,597 records checked, every
  recovered `:v` a logged value; the only anomaly is the
  pre-existing map-scale defect (below), present in A too.
- **Cell J** (two full runs): counter always inside
  [confirmed, total] — run 1: confirmed 6,862 = recovered
  6,862 exactly; run 2: confirmed 5,615, recovered 5,616.
  Per-record: run 2 checked 1,976 records; every recovered
  `:v` was a logged value for that record, and all but one
  equal the last confirmed put exactly. The two residuals
  (one record in run 2; run 1's r134) both trace to the
  harness's epoch-attribution race — a writer reads the root
  epoch *after* its swap, so under contention a logged epoch
  can exceed the commit's true epoch. Run 1's case is proven:
  lpy's seeded first put (`:v 1` → r134, visible in cell A's
  log) published in J, then its process died at the journal
  clock throw before logging — an unlogged commit, correctly
  journaled by the other writers' flushes. No torn state in
  any cell: every atom opened and read whole.
- **Achieved window:** 30 flushes per J cell; per-writer
  cadence 5.7–6.0 s (the 5 s window + flush time); journal
  grew one record per flush to 62,040 B (30 records) by kill.

### Perf — A vs J-5s on this box's disk (ops per 60 s cell)

| class | A | J-5s | ratio |
|---|---|---|---|
| counter | 28,428 | 5,710 | 0.20 |
| ~1 KB | 23,501 | 4,911 | 0.21 |
| ~100 KB | 9,989 | 2,140 | 0.21 |
| ~1 MB | 5,524 | 1,230 | 0.22 |
| ~100 MB | 57 | 86 | ~1 (tiny counts) |

Aggregate: A 67,499 ops vs J 14,077 (≈ A/4.8). Flush latency
under this load: **mean 654–707 ms, max 1,444 ms** — versus
3–18 ms sweeping flushes on the j.slaa.us disk in phase 1.
The earlier counter-only contention legs showed J1000 *above*
A; that does **not** carry to this workload, and the mechanism
is measured, not conjectured: each 5 s flush sweeps the whole
domain — including the ~100 MB class's dirty pages written by
all eight processes — and on this disk that sweep costs ~0.7 s
on a writer's commit path while the journal lock serializes
flushers. The brake is uniform across classes (~0.21×),
which is the signature of a domain-wide cost, not a per-op
one. On a disk whose sweep costs milliseconds (the server's),
the same design's phase-1 contention numbers sat at parity
with A; a phase-3 cell on that disk was not run.

### The map-scale defect (pre-existing, both cells)

Setup's single-process 1.2M-record value round-trips with 23
keys missing (1,199,977), and recovered 100 MB-class maps in
both cells carry a spurious `:v` entry (a record's field as a
key of the parent map) with counts 1,199,977–1,199,978. It
reproduces with the journal off, in setup, before any writer
runs — an Eve large-map defect on this build, unrelated to
the journal. Repro banked (`map_scale_repro.clj`); it belongs
in the defect register, not in this spike's fix scope.

### Gaps named

cljr never got past boot inside the J cells' kill window
(it wrote 399 ops in cell A; in J run 1 it died at the journal
clock throw, in run 2 it was still booting when killed) —
its J-cell participation is via the A cell + the Jn design,
not a J measurement. lpy is a marginal writer (124 ops in J,
1 in A — interpreter speed under contention). Node writers'
flush events do not reach their op logs (the CLJS drain gap);
their throughput drop matches paying flushes they don't log.
Writers never `sync!` mid-cell by harness design (a checkpoint
would truncate the journal and erase the audit trail); `sync!`
is proven in phases 1–2. The 100 MB class is written only by
JVM-family writers (JVM, jolt, cljr) — Node/nbb/lpy draws
convert to counter increments rather than materialize ~100 MB
per op.
