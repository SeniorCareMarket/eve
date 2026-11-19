# eve.db.ds — DS in Eve, implementation plan v2

Written Nov 19 2026 for John. Replaces
`johnmn3/eve.db.ds` `lane-m/ds-in-eve-rewrite:docs/DS-IN-EVE-REWRITE-PLAN.md`
(v1). Its goal, alpha contract (§4), acceptance numbers (§7) and standing
rules (§9) carry over unchanged. Depends on `EVE-FIX-PLAN.md` (same folder).

## 1. What we are building

DataScript 1.8.1 with its types reimplemented as `eve/deftype`, its DB held
in an Eve atom, tested on the JVM and on ClojureScript in a browser. The
browser target exists to remove the graph cliff (v1 §7.2).

Model: narcis `src/narcis/store_eve.cljc`. One require,
`[eve.alpha :as eve :refer [$]]`. Values built inside `swap!`. Reads inside
`($ …)`. Anything that must outlive a swap is data inside the atom.
`eve/text/store.cljc` is NOT a model: it requires Eve internals.

## 2. Corrections to v1 (v1 is wrong here; do not build these)

- DB fields are `[schema eavt aevt avet max-eid max-tx rschema]`. There is
  no `vaet`. Datom is `[e a v tx]`; added is the sign of `tx`.
- `@conn` cannot return the DB (EVE-FIX E1). See §4.
- `d/q`, `d/pull`, `d/entity`, `d/datoms` take DB values, not conns.
- The sorted set never stores its comparator. Every op takes it as an
  argument, as upstream DS already calls it: `(set/conj s datom cmp)`.
- Never `add-watch`, `compare-and-set!` or `reset!` the conn's Eve atom.
  Never use `swap!`'s return value. Never use `identical?` or `=` on a DB,
  a set node or a stored Datom; use `eve/same?` (EVE-FIX F3).

## 3. Source baseline

Upstream `tonsky/datascript` tag 1.8.1, commit
`34915cf673c02cbc64779fe56e0288d70fea63f8`, copied by a script that renames
`datascript.` to `eve.db.ds.` (extend `scripts/vendor_upstream_ds.sh`). The
published port's source is not an input. Every file gets one class:

| Upstream file | Class | Allowed change |
|---|---|---|
| `parser`, `pull_parser`, `built_ins`, `lru`, `util` | verbatim | none |
| `query`, `pull_api`, `impl/entity`, `datafy` | verbatim + list | only hunks in the trap list (§6) |
| `db.cljc` | type swap | `Datom`, `DB`, `FilteredDB` become `eve/deftype`; mutable hash caches removed; index calls unchanged |
| persistent-sorted-set | new | `eve.db.ds.bset`, §5 |
| `conn.cljc` | new | §4 |
| `serialize` | new | datoms out inside `$`, datoms in through a transact |
| `storage` | deleted | — |

Gate: `scripts/diff_upstream.sh` fails on any hunk in a verbatim file that is
not listed in §6.

## 4. Conn and DB values

- **Atom value:** a plain map `{:db DB :kept {id DB}}`.
- **Conn:** a host type holding the Eve atom and a host atom of listeners.
  `create-conn` is `(eve/atom {:id id} nil)` or with `:persistent dir`, then
  one `swap!` that builds the empty DB inside it.
- **`transact!`:** one `swap!`. The swap fn runs upstream's
  `transact-tx-data` on `(:db m)` and returns `(assoc m :db db-after)`. It
  copies `tx-data` and `tempids` out as host data through a `volatile!`.
  Listeners fire after the swap.
- **`(d/db conn)` / `@conn`:** a host handle `{:atom a :tx n}`. Every read on
  a handle runs in `$`, takes `(:db @a)` if its max-tx is `n`, else the
  entry in `:kept`, else throws `:eve.db.ds/db-not-kept` naming both tx
  numbers.
- **Keeping an old DB:** `(d/keep! conn db)` puts it in `:kept` in one
  `swap!`; `(d/release! conn db)` removes it. Both are new and additive.
- **Returns:** every public fn returns host data only. Datoms leave as host
  Datoms (upstream's type, verbatim). Gate: a test calls each public fn and
  asserts no Eve type appears in the result.
- **Conn-less values** (`empty-db`, `init-db`, `db-with`, `with`, `filter`):
  each `empty-db`/`init-db` makes a private in-memory conn; `db-with` adds
  the new DB to that atom's `:kept` and returns a handle. OPEN, John's call,
  blocked on EVE-FIX F5: whether these are released explicitly or by the host.

## 5. `eve.db.ds.bset`

Two types: `Leaf [slots]` and `Node [slots]`, `slots` an Eve value array. A
leaf holds up to 32 keys. A node holds its children then each child's
largest key. Ops, each taking `cmp`: `conj`, `disj`, `slice`, `rslice`,
`seek`, `count`. `conj` and `disj` return nil when nothing changed (no
identity test). All path-copying; untouched children are shared by pointer.
Contract: the upstream persistent-sorted-set suite, run inside a harness
that builds in `swap!` and reads in `$`.

## 6. Trap list (derive mechanically in U0, then freeze)

`grep -n` the baseline for: `identical?`, `=` on db/set/datom,
`unsynchronized-mutable`, `volatile-mutable`, `reset!`, `add-watch`,
`compare-and-set!`, `.-e`/`.-a`/`.-v`/`.-tx` field reads, `hash` caches,
lazy seqs returned from index reads. Each hit gets a row: file, line,
replacement. Agents apply rows; they do not invent them.

## 7. Units — one agent each, files never shared

| Unit | Owns | Done when |
|---|---|---|
| U0 | scripts, deps, laws test, trap list, API var list | laws test green on JVM + browser at the fixed Eve pin; grep gate and diff gate run |
| U1 | `bset` | sorted-set suite green, both hosts |
| U2 | `db.cljc` types | db, index, components slices green |
| U3 | `conn.cljc`, handles | conn, transact, listen, upsert slices green |
| U4 | query, pull, entity, datafy trap rows | their slices green |
| U5 | serialize, persistence | round-trip and close/reopen digests match v1 §7.3 |
| U6 | acceptance | v1 §7, all six, on JVM and in the browser |
| U7 | README, GUIDE | states §4's rules where users see them |

Order: U0, then U1, then U2, then U3; U4 and U5 after U3; U6; U7.
Every unit runs its tests on both hosts. A unit that needs an Eve call
alpha lacks stops and files it (v1 §4). No unit edits another's files.

## 8. Before the swarm starts

John's word on §4's open item, and EVE-FIX F1–F3 green in a tagged pin.
