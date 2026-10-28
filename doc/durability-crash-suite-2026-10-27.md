# Eve 0.9 durability — simulated-crash suite addendum (2026-10-27)

Phase 2 of the durability spike (branch `claude/eve-durability-spike`):
the SQLite-pattern crash test. SQLite does not kill processes to
test power loss — a shim records every write and sync, and a
simulated crash keeps only what had been synced, crashing at every
sync boundary in turn. This suite does the equivalent at Eve's force
boundary. Harness: `eve/test/eve/durability_crash_recorder.clj`,
`eve/test/eve/durability_crash_reader.clj`,
`eve/doc/durability-spike-evidence/crash_suite.py`. Raw per-point
results: `crash-suite-<variant>-<size>.txt` in the evidence dir.

## The model

A recording run drives a scripted commit sequence (counter; and a
~66 KB value — a 2,200-element vector of 30-char strings, see the
shape note below), snapshotting every domain file after each commit.
For crash point k, a materializer composes a domain image in which
**only the effects of force events 1..k exist**:

- slab bytes come from the snapshot taken after the last forced
  sweep; unforced files keep the checkpoint snapshot's bytes;
- the root file carries only its forced bytes (the epoch word and
  the whole atom table, copied from the snapshot of the last forced
  root state) — all other root bytes stay at checkpoint, modeling
  dead writers' liveness state as volatile (in the real design the
  root's non-forced bytes may land in any state; the suite's images
  are the harshest consistent choice: nothing unforced exists);
- the journal is truncated to its forced prefix, or torn at a chosen
  offset for the mid-append points.

A fresh JVM process then reopens each image and the value is
compared, exactly, against the value of the epoch the point's
durable state should resolve to.

**The model's assumption, named:** a force makes its ranges durable
atomically with respect to the crash point; unforced data is
entirely absent. This is the powersafe-write assumption — real
storage can tear inside an unforced page or land unforced pages
early (the root-ahead points model exactly that landing for the
root file); no claim here reaches beyond what the simulator models.

## Results

| variant | size | points | pass | violations |
|---|---|---|---|---|
| B | counter | 9 | 9 | 0 |
| B | ~66 KB vector | 9 | 9 | 0 |
| J1 | counter | 17 | 16 | 1 |
| J1 | ~66 KB vector | 17 | 16 | 1 |
| J10 | counter | 10 | 9 | 1 |
| J10 | ~66 KB vector | 10 | 6 | 4 |

Point coverage: B — crash after each commit's slab force and after
its root force. J1 — after each commit's slab sweep, after its
journal force, with the live root landed ahead of the journal, and
mid-append torn tails at 4 offsets (4 / 100 / 1030 / 2067 bytes into
the record). J10 — inside the batch window (nothing forced), between
the flush's slab sweep and its journal force, mid-append torn tails
(100 / 1030 bytes), at the completed flush, in the next window, and
with the live root landed mid-window.

## The one finding, stated plainly: the recovery floor is not defended

Every violation is the recovery floor giving way, in two halves.
Recovery can install the newest valid journal record — but when
the journal holds nothing past the checkpoint, the floor is the
checkpoint epoch, and **neither the checkpoint's table nor its
blocks are retained by anything**.

**Half 1 — the checkpoint's table exists only in the root file.**
When the live root's pages land (kernel writeback — J never forces
the root, and it may land at any time) while the journal is still
empty past the checkpoint, there is no record to install and the
landed root survives:

- **J1, both sizes** (`c1-root-ahead-of-journal`): reopen succeeds
  and reads the *unjournaled* epoch 1 instead of the checkpoint
  epoch. The epoch is at least complete — J1 forces its blocks
  before the CAS — but it was never journaled, and a crash there
  exposes it as if it were durable.
- **J10, both sizes** (`window-c5-root-ahead`): worse. The landed
  root names epoch 5 inside an unflushed batch — its blocks were
  never forced, so in the durable image they are pre-image bytes.
  Reopen **fails**: `eve.atom/read-root-value — mmap-atom:
  unknown root value type-id`.

**Half 2 — a journaled epoch's blocks are not retained either.**
At J10's flush points on the ~66 KB legs (`flush-after-sweep`,
`flush-torn-100b`, `flush-torn-1030b`), the journal is empty or
torn, so recovery resolves to the checkpoint epoch — and reading
it **fails**: `eve.codec/eve-bytes->value — Unknown EVE type
tag`. The checkpoint's root block was superseded at commit 1 and
its page recycled by later epochs' blocks; in the durable image
(slab state as of the flush's sweep — the same slab set from
which `flush-done` reads epoch 10 correctly) those bytes are a
different epoch's data. The allocator was entitled to recycle
them: retirement respects *live readers'* pinned epochs, and a
journaled epoch is pinned by nobody. The journal's records name
epochs whose blocks survive only by luck of low churn — the
counter legs' small values never triggered recycling in these
short sequences (all their fallbacks pass), the 66 KB churn did
within ten commits. In a longer-running domain the same exposure
reaches *valid* records near the journal's tail, not only the
checkpoint: any record recovery might install names blocks that
later epochs were free to reuse.

- Everywhere the floor holds, recovery is exact: the root-ahead
  points at commits 2–4 (J1) install the journaled epoch, torn
  tails are discarded at every offset, J10's window resolves to
  the checkpoint whenever the root does not land and the
  checkpoint's blocks survive (counter), `flush-done` and the
  post-flush window read epoch 10 — and B, whose durable state
  is always its newest forced root (it never needs an older
  epoch), passes all 18 points.

Characterization, per the phase's rule (the fix is John's call,
not redesigned here): two things a production form must defend
that the spike does not. (a) *The checkpoint needs an anchor
record*: `sync!`/close truncate the journal to empty and leave
the checkpoint's table solely in the root file; one valid record
for the checkpoint epoch must survive truncation so "install the
newest valid record" always has a table to land on. (b) *The
journal needs a retention floor in the allocator*: the oldest
epoch recovery may install — the checkpoint's, or the oldest
live record's — must bound reclamation (an input to the
min-safe-epoch the retire path already reads), so an installable
epoch's blocks cannot be recycled while its record is.

## Shape note (a suite discovery, pre-existing)

The ~64 KB legs do not use one long string: a single string value
above ~1–5 KB (between 1000 and 5000 chars, bisected only to that
bracket) **does not round-trip through this eve build's mmap atom**
— it reads back empty, on pristine main exactly as on the spike
branch, with no error. That is an eve limitation independent of
durability, flagged here because it shaped the suite: the large
legs use a 2,200-element vector of 30-char strings (~66 KB across
many blocks), which round-trips and gives the multi-block coverage
the suite exists for. The string limit itself is a defect report
for the backlog, not something this phase touches.

## Floor fixes — the seven violations closed (2026-10-27, later)

John approved both production fixes named above; they are built on
this branch (commit `aaf2fde5370`) and the FULL suite was re-run on
the fixed code (sequences re-recorded; pre-fix outputs archived in
`evidence/crash-suite-prefix/`). **Result: 72/72 — B 18/18
(unchanged), J1 34/34, J10 20/20. All seven violations closed.**

**Fix (a) — anchor record.** `journal-anchor!` replaces checkpoint
truncation: at `sync!`/close the checkpoint first flushes and forces
the whole domain as before, then — under the journal lock —
regenerates the checkpoint epoch's record from the journal scan's
newest and rewrites it as the journal's SOLE record. The journal is
never empty while a checkpoint exists (a closed journal domain's
journal file is now 2,068 bytes, was 0; verified on JVM and Node).
Recovery's rule becomes uniform: whenever a valid record exists,
install from the journal, never from the landed root. Closed by
this fix: J1 `c1-root-ahead-of-journal` (both sizes) and J10
`window-c5-root-ahead` (both sizes) — all now reopen at the
checkpoint epoch with the checkpoint's value.

**Fix (b) — retention floor.** The journal state gains `:floor`:
the oldest epoch recovery may still install. Both hosts' journal
scans now also report `:oldest` (the first valid record's epoch);
recovery sets the floor from it (live joiners too, read-only), the
first flush into an empty journal pins it, and `journal-anchor!`
advances it — monotone, at checkpoint, under the journal lock, and
nowhere else. `flush-zero-queue!`'s safe epoch — the retire path's
existing input — is now `min(reader pins, floor)`: the journal pins
the floor epoch the way a slow reader pins its read epoch; no new
mechanism beside the retire path. (The reader-facing
`get-min-active-epoch` query is deliberately untouched.) Closed by
this fix: J10 ~66 KB `flush-after-sweep`, `flush-torn-100b`,
`flush-torn-1030b` — the fallback to the checkpoint epoch now reads
whole, because the checkpoint root's blocks are no longer recycled
during the batch window.

**Suite modeling note (harness, not implementation).** The suite's
J10 window/flush compositions originally truncated the modeled
journal to 0 bytes — the pre-fix checkpoint state. On the fixed
code the recorder's own snapshots show snap-0's journal at 2,068
bytes (the anchor), so `crash_suite.py` now composes those points
with the anchor (`man[0]` bytes; torn-flush points tear past it).
Expectations unchanged. First post-fix run (stale compositions)
scored J10 18/20 with only the two `window-c5-root-ahead` points
failing on the empty-journal images; with the compositions
corrected, both pass — the implementation was never the failing
side of those two points.

**Regressions.** JVM suites: 44 tests / 210 assertions, 0 failures
(identical counts to pre-fix). Journal crash tests (halt + wounded
root): J1 reopens 15/15, J10 reopens its journaled 8, garbage-tail
J1 reopens 3/3. Node: bench domains close to 2,068-byte journals.

## What this does not claim

Simulated crashes at modeled boundaries, on the powersafe-write
assumption above. Real power loss can also tear *inside* a forced
range on some storage, land unforced slab pages early (modeled here
only for the root file), and corrupt the root file's own bytes
(the torn-writeback remainder from the main report). The journal's
CRC/framing is what those tears meet; the suite's torn-tail points
exercise the journal side of it.
