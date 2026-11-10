# Software transactional memory: `eve.stm`

Eve ships an STM in its own substrate: `eve.stm`, software transactional
memory implemented *in* Eve, on Eve atoms, with no host STM beneath it.
The semantics are stock Clojure's — refs, `dosync`, `alter`, `commute`,
`ensure`, snapshot reads as of a commit point — with the mechanism
rebuilt so that every piece of transactional state lives in Eve atoms
and a transaction can span processes, not just threads.

```clojure
(require '[eve.stm :as stm])

(def account-a (stm/make-ref 100))
(def account-b (stm/make-ref 0))

(stm/dosync
  (stm/alter* account-a - 40 [])
  (stm/alter* account-b + 40 []))

(stm/deref-ref account-a)   ; 60
```

## Each ref is its own domain

A ref's committed state is a cell — `{:value :point :version
:history}` — held by an atom that is the ref's alone: the sole atom of
a fresh Eve atom domain. The ref's domain is its contention domain:
its own slab, root, epoch and arbitration. Transactions touching
disjoint refs lock, validate and CAS disjoint atoms; contention on one
ref never serialises a transaction that does not touch it.

There is **no single atomic swap anywhere in this construction**, and
none is needed for the semantics. A transaction:

1. fixes a **read point** — the commit-point counter, one atom holding
   no ref's data;
2. reads each ref's value **as of that point** from its per-ref
   versioned history;
3. at commit, locks each ref it installs into and each ref it ensured
   (in one global order, ref creation order), validates that every
   written or ensured ref still stands at the point the attempt read,
   claims the next commit point, and installs each written ref with
   **one CAS on its own cell atom**.

Cross-ref atomicity is the composed lock / validate / install, exactly
as in stock Clojure. Commutes are not validated; at commit they fold
over the ref's *current* committed value, in call order. Plain
read-only refs are not validated either — that is stock's answer, and
why `ensure` exists: `ensure` enrols a ref in the attempt's validated
set, so a concurrent commit to it retries this attempt (the write-skew
guard).

### Jagged transactions

The defect this design exists to prevent has a name: a **jagged
transaction** — a snapshot assembled from several ref-atoms read at
different moments, producing a combination of values that never
existed at any single commit point. It is not a torn read: a single
Eve atom's deref is one consistent snapshot and cannot tear. The
hazard lives in the layer above the atoms, and the read point is the
answer: every read in a transaction is served from the ref's versioned
history *as of the transaction's one read point*, so the attempt sees
exactly one committed world. A read, in a transaction or out, waits
while another attempt holds the ref's commit lock, so no reader sees
part of a commit either.

## Contention domains are tiered

A ref's Eve domain is its contention domain at the memory tier. The
tiers widen with the atom's own tier:

| Tier | A ref's contention domain is |
|---|---|
| memory | the ref's own Eve atom domain |
| disk | a whole process |
| network | a whole server — in a network organisation each ref is its own server with its own atom broker, and cross-broker commit is protocol design, not a substrate primitive |

The substrate at the network tier is Eve's network atoms
(`eve.net.atom`); the STM construction is written against deref / CAS /
own-domain atoms only, so the same mechanism seats at every tier —
only the state changes home.

## Disk-tier seating: transactions across processes

By default the mechanism above is the memory tier: the clock is one
host atom and locks live in refs' host state, so one process's
transactions are the whole world. Two seats move the same mechanism to
the disk tier, changing nothing else — lock order, validation, claim,
install and retry are the same code paths:

- `(stm/seat-clock! a)` seats the commit-point clock in a shared
  persistent Eve atom `a` (which must hold a long: the last claimed
  commit point);
- `make-ref`'s `:seat {:domain-path … :name … :durable? …}` opt seats
  the ref's cell in its own persistent domain at `:domain-path`, with
  its commit lock in the cell's Eve meta-atom.

Seated this way, two processes transact against the same refs: the
shared clock hands out commit points both honour, and each ref's lock
and history live in the shared domain, not in either process.

## The worked example: the ants demo

The example project for this STM — the peer of the
[DataScript example](../docs/datascript-eve/GUIDE.md) — is the **ants
demo**: Rich Hickey's `ants.clj` re-hosted on `eve.stm` as closely to
the original as possible. Every cell of the world is a ref — its own
Eve atom, its own contention domain — and one simulation step is one
STM transaction spanning two cell refs: the cell an ant leaves and the
cell it enters. It is the acceptance vehicle for the contention side of
this design: several hosts, and several processes, transacting against
the same world of refs at once.

- The project: [`docs/stm-ants/`](../docs/stm-ants/) — the program at
  [`ants.cljc`](../docs/stm-ants/ants.cljc), Hickey's original kept
  beside it at `ants-original.clj` for comparison, the runner at
  `demo/run.cljc`, and a host entry point per seat under `hosts/`.
- The demo's own doc:
  [`docs/stm-ants/demo/README.md`](../docs/stm-ants/demo/README.md).

What it has proven so far: two-ref transfers under contention on
Babashka; a jank member joining a running world; and a mixed-host run
— JVM, Babashka, jolt and jank as members of one world — completing
with its final audit clean (every ant and every food accounted for,
no invariant violations).

## The API

| Var | What |
|---|---|
| `(make-ref x)`, `(make-ref x opts)` | a new ref holding `x`. Opts: `:meta`, `:validator`, `:min-history`, `:max-history`, and `:seat` (disk-tier seating, above). The cell — and its Eve domain — are minted immediately; creation is not transactional, as stock's is not |
| `(dosync & body)` | run the body in a transaction; a nested call joins the running attempt |
| `(sync flags & body)` | the same runner (`flags` are accepted; the `:release-pending-sends` flag is not modelled — pending sends are the agent seam's, released at commit) |
| `(run-in-transaction f)` | the runner itself: run `f` in an attempt, retrying per the rules below |
| `(deref-ref r)` | in a transaction, the attempt's view (the value as of its read point, folded with its own writes); outside, the latest committed value, read once no commit holds the ref's lock |
| `(ref-set* r v)` | the attempt's value of `r` becomes `v` |
| `(alter* r f args)` | apply `f` to the attempt's view of `r` and record the result as a set; an altered ref is validated at commit |
| `(commute* r f args)` | record `f` to fold over the ref's *current* committed value at commit time, in call order; a commute-only ref is not validated |
| `(ensure* r)` | enrol `r` in the attempt's validated set (the write-skew guard) |
| `(ref-history-count r)`, `(ref-min-history r)`, `(ref-max-history r)`, `(set-min-history! r n)`, `(set-max-history! r n)` | the ref's retained versioned history: never more than max-history entries (default 10), never fewer than min-history (default 0). A read point older than the oldest retained entry retries with a fresh point |
| `(add-watch* r k f)`, `(remove-watch* r k)`, `(get-watches r)` | ref watches; watches fire once per written ref after the winning installs, before held sends are released. (Refs carry watches and a validator in their own state; Eve *atoms* have neither) |
| `(set-validator* r f)`, `(get-validator r)` | the ref's validator, run at commit |
| `(seat-clock! a)` | seat the commit-point clock in a shared Eve atom (disk tier) |
| `(dispatch! msg)`, `(set-send-dispatcher! f)` | the agent seam: Eve has no agent machinery in its standalone tree, so sends are a seam — inside a transaction a send is held and released through the installed dispatcher exactly once, after the winning commit; outside, it dispatches immediately. Until a dispatcher is installed, released sends are dropped |
| `(stats)`, `(reset-stats!)` | attempt / retry / commit counters for the namespace |
| `(in-transaction?)`, `(ref-value? x)` | predicates |

## Retry semantics — decided

Ordinary contention retries are **unbounded and never counted**: a
contended attempt re-runs against a fresh read point until it commits.
The one bounded failure is the **basis fault**: a cell standing beyond
the commit-point clock, a state no legal commit sequence can produce —
only a writer violating the single-writer contract (below) can put one
there. Basis faults are counted per invocation; at `basis-fault-limit`
(10,000 — stock `LockingTransaction` `RETRY_LIMIT` parity) the runner
throws `IllegalStateException` "Transaction failed after reaching
retry limit" instead of spinning.

**The single-writer contract:** a ref's cell is written *only* through
this namespace — transactions, and sends this namespace releases at
commit. The cell rides an ordinary Eve atom and nothing physically
stops other code from swapping it, but doing so is outside the
contract: state that needs uncoordinated writers is a plain Eve atom,
not a ref.

Interruption is cooperative, as it must be at the Eve level: the
runner checks the running thread's interrupt state once per attempt
and abandons with `InterruptedException`.

## History encoding — read this before seating worlds

**This release (0.9.3.1) ships the OLD history encoding.** A ref's
versioned history is stored on disk in the original cell/history
representation that `eve.stm` has used since it landed.

An accepted replacement — the **flat encoding** — exists, but it is
**not in this release**: it lives only on the development branch
`lane-m/fix-for-release-d1`, pending the operator's ruling on whether
it displaces the old encoding in a later release.

The two encodings do not read each other:

- a world seated under the old encoding is **not readable** by the
  flat encoding, and vice versa;
- there is **no in-place migration** — when the encoding changes,
  seated worlds must be **re-created** under the new encoding.

If you seat STM refs on disk with this release, plan for that
re-creation. Nothing in this release writes the flat encoding, and no
tool in this release converts between them.
