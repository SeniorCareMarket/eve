# PROVENANCE-DS — DataScript (Phase 2 library)

**Library:** DataScript — an immutable in-memory database and Datalog query
engine for Clojure, ClojureScript and JS, by Nikita Prokopov (Tonsky).
Named by the operator (2026-11-06 ~11:11 AM ET) as the flagship follow-on
and directed as Phase 2 of this lane (2026-11-06 ~12:00 PM ET, "Do it").

**Upstream:** https://github.com/tonsky/datascript
**Author:** Nikita Prokopov (Tonsky)
**License:** Eclipse Public License 1.0 (LICENSE copy in vanilla-ds/).
**Version pinned:** tag **1.8.1** (the tag is the upstream master tip at
fetch time, 2026-11-06).

**Verbatim vanilla copies:** `vanilla-ds/datascript/` — the complete
`src/datascript/` tree at 1.8.1 (21 files, ~7,350 lines of .cljc including
`impl/entity.cljc`), plus `vanilla-ds/LICENSE`.

## The index substrate — and a version fact that shapes the port

DataScript's indexes ARE persistent-sorted-sets: `datascript.db` requires
`[me.tonsky.persistent-sorted-set :as set]` and
`[me.tonsky.persistent-sorted-set.arrays :as arrays]`. DataScript 1.8.1
pins **persistent-sorted-set 0.3.1** (its deps.edn/project.clj), and calls
0.3.x API that the Phase-1 port (of PSS 0.2.3) does not have:
`set/sorted-set*`, `set/union`, `set/intersection`, `set/difference`,
`set/subset?`, `set/est-count`, `set/settings`, plus `set/slice`,
`set/rslice`, `set/seek`, `set/from-sorted-array`, `set/conj`, `set/disj`
(which the port has) and `arrays/{aget,alength,aset,asort,into-array,array?}`
(the port's arrays has all of these).

**Phase-2 substrate step (recorded before porting, per the brief):** the
Phase-1 Eve BTSet port is first upgraded from PSS 0.2.3 to **PSS 0.3.1** —
the same porting treatment applied to 0.3.1's CLJS form — so the substrate
is exactly the library version DataScript itself pins, in its Eve form.
The upgrade delta lands in PORTING.md as its own section; DataScript's own
deltas land in PORTING-DS.md.

## What "the port" is

`lib-ds/datascript/*.cljc` — DataScript's namespaces ported near-vanilla:
same namespace names, same public API (`datascript.core`: d/q, transact!,
create-conn, etc.), with the DB value and its index sets built on the Eve
BTSet port so a whole database is an Eve value that can live in a shared
Eve atom. Every difference from the vanilla text is listed and categorized
in PORTING-DS.md (Eve-surface adaptation / host-quirk workaround / NONE).
