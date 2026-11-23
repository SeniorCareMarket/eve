# L1 status — the list reads without allocating

Lane L1, branch `claude/static-l1-list`, base `claude/sweet-carson-6pt29d` @ 7402ef9.
File owned: `src/eve/list.cljc`. Tests: `test/eve/static/list_test.clj`.
Pushes to origin are impossible from this box (no credential) — local
commits only; the coordinator collates from this tree.

## The claim, re-derived at this pin (7402ef9, OpenJDK 21.0.13)

CENSUS defect 3 said: one plain deref of a 50-element list takes 50
blocks (class-0 free 32716 → 32666 at eve 4a92129, JVM 21.0.12).
Re-derived here with the allocation log over a fresh heap domain
(logging-sio, the reclaim test's instrument): the requirement test's
four full walks of a 50-element list allocated **197 blocks** through
the list's own sio, none freed — ≈49 leaked 12-byte headers per walk
(one per `next` step). Same mechanism, same magnitude: **claim stands**.
The frozen-view test errored the same way P5's list row did (allocation
attempted through a view that refuses it).

Mechanism, read first-hand: `EveList.next`/`more` called `make-list`,
which allocates a fresh 12-byte header block per step
(`write-list-header!`); `-plain` walks by `seq`, so a deref leaked one
header per element. Deftype fields are always `[sio__ offset__]`
(deftype_proto/macros.cljc:11) — an EveList's `cnt`/`head-off` exist
only as bytes in its slab header block; there is no host-side field to
carry a tail in.

## The fix (committed 4be0814)

`seq` returns a lazy host seq over the node chain (EveVector's seq
precedent); `next`/`more` return the same walk. No `make-list` on the
read path. Walks — seq, next/rest chains, `eve->clj` (the deref),
reduce, into — allocate zero domain blocks; a walk over a frozen view
never calls a mutator. Both L1 tests green.

## L1.3 disposition — the two assertions pinned the old representation; amended (353851d)

`test/eve/jvm_list_test.clj` asserted `(instance? eve.list.EveList
(next l))` (list-next, line 117) and `(instance? eve.list.EveList
(rest l))` (list-rest, line 127). With allocation-free walking,
`next`/`rest` answer seqs, not EveList tails: exactly those 2
assertions failed; the other 36 assertions of the combined run passed.

**Verdict: stale encoding of the old allocating representation — not a
regression in the fix.** Evidence, all read first-hand at this pin:

1. An EveList value is only `[sio__ offset__]` (deftype fields,
   deftype_proto/macros.cljc:11); its `cnt`/`head-off` exist solely as
   bytes in its slab header block. There is no host-side field in which
   a tail could carry its own (cnt, head).
2. Pre-fix, `next`/`more` answered an EveList _because_ they called
   `make-list` per step, minting that header (visible in the 4be0814
   diff). The assertions therefore held only under per-step domain
   allocation — the exact mechanism of the re-derived defect (197
   blocks over 4 walks of a 50-list). L1.1 and the assertions are
   mutually exclusive: satisfying them _is_ re-introducing the leak.
3. Nothing a reader can observe changed except the concrete class of
   the object `next`/`rest` return. The ISeq contract — values, order,
   nil termination, rest count — is asserted by the neighbouring
   assertions in the same two deftests (`'(2 3)`, `'(3)`, nil at the
   end, `(zero? (count (rest l)))`) and all of them still pass.
   Clojure's contract for `next`/`rest` promises an ISeq, never the
   input's concrete collection type.
4. EveList-ness of a tail remains a real, assertable property — on
   `pop` (list.cljc:171), the update whose result is a first-class
   EveList tail and which still pays for its header via `make-list`,
   as `cons` does. The amendment asserts it there.

Amendment (353851d, deliberately loud, not silent): both deftests now
assert `(instance? eve.list.EveList (pop l))` (plus seq/count of the
pop) with a comment recording this disposition. The earlier draft of
this section called the amendment a coordinator/operator call and left
it unmade; the successor session's brief ordered the disposition
executed with evidence, so it is made here, on this branch, for the
coordinator to accept or revert at collation with the evidence in hand.

Every zero-domain-allocation header scheme that could have kept the
old assertions alive was priced and rejected —

- Virtual header (host byte array behind a wrapper sio, or a class-7
  offset): the tail's root offset is then meaningless in the domain.
  `eve.atom/resolve-new-ptr` (atom.cljc:865-873, not L1's file) decides
  same-domain by `alloc/sio-root` identity: a wrapper that unwraps to
  the domain gets its virtual offset stored as an atom root (silent
  corruption on the next deref); one that doesn't unwrap is treated as
  a foreign value and `(swap! a (fn [v] (next v)))` throws. Both are
  worse than the leak.
- Memoized/arena/pooled real headers: still allocate on first walk,
  leak by design, or corrupt when a stored root shares the block.
- `pop` is the operation whose result is a first-class EveList tail;
  it still allocates its header, as `cons` does — it is an update,
  not a walk.

## Remaining

- Final confirmation run at the current tip — FILED as a venue leg
  (see below), not run on this box (operator venue order: no heavy
  runs on hatch at any load).
- Broad JVM suite run with the fix (blast radius beyond the list
  suite) — still owed; not part of the filed leg.

## Venue leg (filed 2026-11-12, successor session)

- W0 contracts merged (38f2181, path remote `w0`); L1.1/L1.2 flipped
  to `:built` in test/eve/static/requirements.edn; the two
  representation assertions dispositioned and amended (353851d).
- **Bundle refresh need:** `~/static-eve-bundles/l1.bundle` on J must
  be recreated from this tree's `claude/static-l1-list` before the leg
  runs — code tip at filing: 353851d (later commits are docs-only).
- Leg filed in ~/workspace/job-agent/inbox/ as
  `static-eve-list-final.json`: lane `static-eve/list`, venue_pref
  `j`, class `heavy`; clones the bundle on J, checks out
  `claude/static-l1-list`, runs `eve.static.list-test` +
  `eve.jvm-list-test` via `clojure -M:jvm-test`, log to
  `~/static-eve-results/l1-final.log` on J. Coordinator delivers the
  result; the lane does not idle on it.

## Log

- 2026-11-12 ~01:50 ET — lane set up; plan + census read; tests written (881ea0a).
- ~02:00 ET — red run at pre-fix source: L1.1 FAIL (197 blocks), L1.2 ERROR (frozen alloc) — claim re-derived.
- ~02:10 ET — fix committed (4be0814); scoped run: L1 tests green, jvm-list-test 36/38 (the 2 representation assertions above).
- ~05:35 ET — successor session: W0 contracts merged (38f2181); L1.1/L1.2 registered :built; the 2 assertions dispositioned as stale encodings of the allocating representation and amended (353851d); final confirmation run filed as a J venue leg; L1-REPORT.md written.
