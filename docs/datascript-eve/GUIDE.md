# DataScript for Eve — the guide

**This is DataScript** — the immutable database and Datalog query engine
by Nikita Prokopov (Tonsky) — running on **Eve** as an external library.
Not a re-implementation, not a lookalike: the upstream library
(DataScript 1.8.1) ported near-verbatim onto Eve's value substrate, with
the same namespaces, the same public API, and the upstream test suite as
its conformance bar. Where it came from and under what license:
[PROVENANCE.md](PROVENANCE.md). What changed release by release:
[CHANGELOG.md](CHANGELOG.md).

Why this matters is what Eve puts underneath it. In ordinary DataScript,
a database is a value in one process's memory. On Eve, a database is
still a value — but an *Eve* value: it can live in a persistent Eve atom,
in a domain on disk, shared by other processes, and — this is the part
no other DataScript host can do — the **same database can be transacted
in parallel by programs running on different host runtimes at once**,
all reading back byte-identical state. One library, written once, as
plain .cljc — running everywhere Eve runs.

## What Eve provides underneath

- **Persistent atoms and domains.** Eve's atoms can be backed by a
  persistent (memory-mapped) domain on disk. Anything that is an Eve
  value can be an atom's value — and the port makes the whole DataScript
  DB an Eve value (its `DB` and `Datom` types are Eve types), so
  *storing the database is placing it in an atom*. There is no separate
  storage layer to configure; vanilla DataScript's storage machinery is
  replaced by exactly this (porting delta D1).
- **The journal.** A persistent Eve domain is journaled by default — the disk tier is
  journaled by design, and the flagship shape below depends on it: every commit of the
  DB atom appends a journal record and the journal force is the commit point. This is
  a requirement, not an option to design around: a seat with no journal adapter cannot
  carry a durable shared database (ValueScript is the one such seat), and a domain
  opened `:journal? false` gives up the journal's durability story by the opener's own
  choice.
- **The sorted set.** DataScript's EAVT/AEVT/AVET indexes *are*
  persistent sorted sets upstream — tonsky's persistent-sorted-set.
  Eve's port of that same library (upgraded to 0.3.1, the version
  DataScript 1.8.1 pins) is the index substrate here, its sets
  slab-resident Eve values over Eve's HAMT-backed storage. The index
  comparators are registered by keyword, so a database built on one
  host has field-identical indexes on every other host — which is what
  lets hashes, equality and seals agree across hosts.
- **The seal.** An Eve value can be sealed — a content digest over its
  blocks. Two processes that read the same database can compare seals
  and *know* they are looking at the same data, not merely data that
  prints the same.
- **One source, every seat.** The library is portable .cljc; each Eve
  host seat (JVM, Babashka, Jolt, nbb, glojure, and more as they are
  proven) loads the same files. Host differences are absorbed by Eve's
  per-seat completion layers, not by forking the library.

## Boot it up

### Plain form — a conn in your process

This is ordinary DataScript, unchanged (the port's first-light driver,
`contract-test/drivers/contract/ds_behave.cljc`, runs exactly this):

```clojure
(require '[datascript.core :as d])

(def conn (d/create-conn))                    ; optionally (d/create-conn schema)

(d/transact! conn [{:db/id 1 :name "Ivan" :age 34}
                   {:db/id 2 :name "Petr" :age 22}
                   {:db/id 3 :name "Oleg" :age 34}])

(d/q '[:find [?n ...] :where [?e :age 34] [?e :name ?n]] @conn)
;; => ["Ivan" "Oleg"]

(:name (d/entity @conn 2))
;; => "Petr"

(d/transact! conn [[:db/add 2 :age 23]])
(:age (d/entity @conn 2))
;; => 23
```

### Eve form — a database that persists and is shared

Open (or create) a persistent Eve atom in a domain on disk, and put the
database in it. The DB is constructed *inside* the swap, so it allocates
in the atom's own domain (Eve's domain-construction contract). This is
the flagship driver pattern, verbatim in shape
(`contract-test/drivers/contract/ds_flagship.cljc`):

```clojure
(require '[eve.atom :as atom]
         '[eve.host :refer [swap!]]
         '[datascript.core :as d])

(def domain (atom/persistent-atom-domain "/path/to/domain-dir"))
(def db-atom (atom/lookup-or-create-mmap-atom! domain "my/db" nil))

(when (nil? @db-atom)
  (swap! db-atom (fn [_] (d/empty-db schema))))

;; transact: compute the new DB value with the ordinary API,
;; install it with a swap
(swap! db-atom (fn [db] (:db-after (d/with db [{:db/id 1 :name "Ivan" :age 34}]))))

;; read from any process that opens the same domain:
@db-atom         ; the same DB value — query it, transact against it
(d/q '[:find [?n ...] :where [?e :name ?n]] @db-atom)
```

Any Eve program — on any proven seat — that opens the same domain sees
the same database. A second process derefs a real `datascript.db.DB`,
queries it identically, and its seal matches the writer's exactly
(measured first on the JVM seat: residence proof, PORTING-DS.md T1–T3).

## Using it as a database, day to day

Everything you know transfers: schema as a map, transactions as data
(entity maps or `[:db/add …]` / `[:db/retract …]` lists), `d/q` with the
full Datalog surface (rules, pull syntax, aggregates), `d/entity`,
`d/datoms` / `d/seek-datoms` over the three indexes, `d/pull`,
listeners on a conn, `db-with` / `with` for hypothetical transactions.
The upstream test suite is the contract: against the reconciled port
copies the suite stands at **181 tests — 1,081 passing, 2 failing,
55 errors** (per-namespace JVM tally identical to the successor
baseline; the 2 failures and the error rows are catalogued, not hidden —
see CHANGELOG.md). Babashka's behave driver runs **fully green**.

## Normally vs with Eve

| | Ordinary DataScript | DataScript on Eve |
|---|---|---|
| The DB is… | an immutable value in one process's memory | an immutable **Eve value** — same semantics, new residence |
| Persistence | none built in; serialize it yourself | put it in a persistent Eve atom; the domain on disk *is* the database file |
| Sharing | one process (or copy the value around) | any number of processes open the same domain and deref the same DB |
| Cross-runtime | JVM *or* ClojureScript builds, separately | the same .cljc library on every Eve seat; a DB written by the JVM seat is read — identically, seal and all — by Babashka, Jolt, nbb, glojure |
| Concurrent writers | one process transacts at a time | multiple host processes transact **in parallel** into one shared DB via atom swaps |
| Indexes | persistent-sorted-set (host arrays underneath) | the same library's Eve port — Eve vectors, slab-resident sets |
| API | `datascript.core` | `datascript.core` — unchanged |

What is identical: the API, the transaction format, the query language,
the data model, the failure modes of the library itself. What changes
is only where the value can live and who can share it.

## The parallel run — what is actually proven

On 2026-11-07 the flagship test ran **five host runtimes at once** —
JVM, Babashka, Jolt, nbb and glojure — as separate processes, all
transacting in parallel into **one shared DataScript database in one
shared on-disk Eve atom**, under a rendezvous protocol (all workers
READY, released together on a common GO). Result, as banked by the
flagship lane (commit `06e5371dd62`, logs under `contract-test/logs/`
on `lane-m/datascript-flagship4`):

- every seat `:inc-fail 0` — no lost or failed increments;
- **readback unanimous: 1,138 datoms, one identical seal on all five
  seats;**
- shared counters exactly the sum of all increments
  (`[2 2 2 2 0]` on every seat);
- ~68 seconds of five-way overlap.

That is the Eve claim, measured: five different language runtimes, one
database, zero divergence.

## Seat status — the honest table (2026-11-07, updated 2026-11-09 for 0.9.3.1)

| Seat | State |
|---|---|
| JVM | **Proven** — suite tally + flagship writer/reader |
| Babashka | **Proven** — behave fully green; flagship writer |
| Jolt | **Proven** — flagship writer |
| nbb | **Proven as a flagship writer** — with a named caveat: if the nbb process *dies mid-window* on a growing shared DB, its writes can corrupt the shared state (F-EVE-NBB-VIEW, isolated by discriminator runs; owner: Eve's nbb seat). Runs where nbb survives are clean — fs4run2's nbb survived |
| glojure | **Proven** — solo acceptance clean; flagship fifth host (its residence + interaction fixes are landed in the 0.9.3.1 tree) |
| jank | **Proven at the pin, compiled form** — the AOT build is clean (165 files, 0 failures) and the one-datum DataScript contract passes 5/5 cold runs (0.317 s median, journal engaged, fresh-process readback exact) |
| basilisp | **In progress** — 0.9.3.1 carries this seat's crypto, serialize freeze/thaw and Iter-hash fixes (the Iter fix is this seat's own port work, taken into the shared code); the dataset acceptance re-run on the fixed tree is still owed |
| clojerl (BEAM) | **In progress** — 0.9.3.1 fixes the seat's first `swap!` wall. The people-1k dataset run is diagnosed slow-not-stuck (its transact cost grows superlinearly); at the previous tree its checks died on `clojerl.IHash` for the sorted set's Iter, and the rebuild carrying this tree's Iter-hash fix has not been run |
| ClojureCLR | **Entry datasets run** — people-1k is complete-verified on this seat; people-20k is walled at the region's 2 GiB ceiling (signed-i32 slab addressing). The segmentation in 0.9.3.1 is the JVM seat's; this seat's wall is a separate, still-open item |
| ClojureScript / Node, browser | **Persistent writes proven (Node)** — one compiled-ClojureScript process writes people-1k, and fresh compiled-CLJS, nbb and JVM readers each match the expected checks 9/9; a single compiled datum reads back exactly in a fresh process. The browser seat is not attempted for this library (it additionally needs a rendezvous design) |
| ValueScript seat | **Blocked** on Eve-in-VS residence machinery |

One named caveat on the Babashka seat: `:db-hash` of a DB value throws there — a
standing SciMap limitation (bb's map type does not carry it). It is the sole
divergence found in the cross-host contention readbacks: every other field was
identical across JVM and bb.

The roster rule is strict: a seat joins the parallel run only after it
passes the solo entry ticket on its own (load, create-conn, transact,
datoms, query, entity, update — the `ds-behave` aspects). The campaign
to widen the run — first to eleven hosts — is underway; this table is
updated from the lanes' own records, and it does not claim seats that
have not earned their place.

## Where things live

The port lives in the 0.9.3.1 release tree under
`contract-test/lib-ds/` (the library), `contract-test/lib/` (the sorted
set), `contract-test/lib-ds-deps/` (extend-clj), with drivers under
`contract-test/drivers/`. Development happens in the
private atom.broker repository; public repositories receive releases
only, as collated snapshots, on the operator's word.

It is consumed **as a dependency**, the way any library made for Eve
is: each of the three directories carries its own `deps.edn`, and
`contract-test/lib-ds/deps.edn` declares the port's deps (Eve, the
sorted-set library, extend-clj) as `:local/root` deps. A consumer
names the library, nothing else:

```clojure
;; in this repo
{:deps {datascript-on-eve/datascript-on-eve {:local/root "contract-test/lib-ds"}}}

;; from your own project (git dep; the nested :local/root deps
;; resolve inside the checkout)
{:deps {datascript-on-eve/datascript-on-eve
        {:git/url "https://github.com/SeniorCareMarket/eve"
         :git/tag "v0.9.3.1"
         :deps/root "contract-test/lib-ds"}}}
```

The contract drivers under `contract-test/drivers/` consume it in the
first form, from their own `deps.edn`.
