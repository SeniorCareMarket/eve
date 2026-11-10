# PROVENANCE — DataScript for Eve: where the brought-in code came from, under what license, and whether the obligations are met

Researched firsthand on 2026-11-07: every license below was read from the
upstream repository itself (not from memory, not from a registry summary),
and every in-tree claim was checked against the trees that carry the port —
principally `contract-test/` on the flagship branches
(`lane-m/datascript-flagship4` @ `1c3f2f331dfc`, verified on the remote ref)
and its successor fix lanes. The Eve-side lane records are
`contract-test/PROVENANCE-DS.md` (DataScript) and
`contract-test/PROVENANCE.md` (persistent-sorted-set) on those branches;
this document is the consolidated, license-verdict-carrying version.

## Thank you

**Nikita Prokopov (Tonsky)** wrote all three of the libraries this work
stands on. DataScript is his design: an immutable database and Datalog
engine small enough to hold in your head and exact enough to test against
itself. persistent-sorted-set — the B-tree set underneath DataScript's
indexes — is his too, as is extend-clj, DataScript's tiny second
dependency. Eve did not invent a database for this project; Eve was given
one of the best-designed libraries in the Clojure ecosystem and asked to
make it at home. The port exists because the original was worth porting
near-verbatim. Thank you, Nikita — and thank you to the DataScript
contributors whose names live in the upstream history and in the test
suite this port is measured against.

These were deliberate picks, not accidents of the dependency graph:
persistent-sorted-set was the operator's own October 2026 choice as the
leading candidate for Eve sorted collections — a real community library,
not a structure invented for a test — and DataScript was named the
flagship external library on 2026-11-06.

## Census — what was brought in, from where

### 1. DataScript 1.8.1

- **What:** DataScript — immutable in-memory database + Datalog query
  engine for Clojure / ClojureScript / JS, by Nikita Prokopov (Tonsky).
- **Upstream:** https://github.com/tonsky/datascript
- **Version pinned:** tag **1.8.1** (the tag was the upstream master tip
  at fetch time, 2026-11-06).
- **License:** **Eclipse Public License 1.0.** Verified firsthand
  2026-11-07 by reading the `LICENSE` file at upstream `master` and at
  tag `1.8.1` — both are the full EPL-1.0 text ("Eclipse Public
  License - v 1.0").
- **What the Eve port carries:**
  - `contract-test/lib-ds/datascript/` — the port itself: 17 namespaces
    (`core`, `db`, `conn`, `query`, `query_v3`, `parser`, `pull_api`,
    `pull_parser`, `entity` (impl), `lru`, `util`, `built_ins`,
    `datafy`, `serialize`, `storage`, `inline`, `pprint`), ported
    near-verbatim — same namespace names, same public API. Every
    difference from the vanilla text is catalogued in
    `contract-test/PORTING-DS.md` (deltas D1–D9, types T1–T3) and marked
    at the site with a `PORT DELTA` comment.
  - `contract-test/vanilla-ds/` — verbatim reference copies of the
    complete upstream `src/datascript/` tree at 1.8.1 (21 files,
    ~7,350 lines) plus `vanilla-ds/LICENSE`.
  - `contract-test/upstream-ds/` — the upstream test suite (the
    conformance bar the port is measured against) plus
    `upstream-ds/LICENSE`.
- **What Eve changed, in one paragraph:** the DB value and the Datom
  became Eve values (`eve-deftype`s) so a whole database can live inside
  an Eve atom; the index sets ride the Eve BTSet port (below); vanilla's
  storage layer is replaced by Eve-atom semantics (PORTING-DS D1); query
  tuples are Eve vectors instead of host arrays (D2); the datom
  comparators are registered by keyword so indexes are field-identical
  on every host (T3). The query engine, parser, pull API and public
  surface are the upstream ones.

### 2. persistent-sorted-set 0.2.3 → 0.3.1

- **What:** a B-tree based persistent sorted set for Clojure /
  ClojureScript, by Nikita Prokopov (Tonsky) — almost a drop-in
  replacement for `clojure.core/sorted-set`, with custom comparators,
  fast iteration and efficient slices. **This is the sorted set that
  runs across all of Eve's seats, and it is DataScript's index
  substrate:** `datascript.db` requires
  `me.tonsky.persistent-sorted-set` for its EAVT/AEVT/AVET indexes.
- **Upstream:** https://github.com/tonsky/persistent-sorted-set
- **License:** **MIT — Copyright (c) 2019 Nikita Prokopov.** Verified
  firsthand 2026-11-07 by reading the `LICENSE` file at upstream
  `master` and at tag `0.3.1` — both carry the identical MIT text with
  the 2019 copyright line.
- **Versions:** the Phase-1 port took **0.2.3** (the CLJS form was
  ported — `persistent_sorted_set.cljs`, 1,097 lines, plus
  `arrays.cljc`, 133 lines — because the JVM form is Java classes, not
  portable code). Before DataScript was ported, the substrate was
  upgraded to **0.3.1**, the exact version DataScript 1.8.1 pins
  (cross-checked 2026-11-07 against DataScript 1.8.1's published
  dependency list: persistent-sorted-set 0.3.1). 0.3.x API DataScript
  calls — `sorted-set*`, `union`, `intersection`, `difference`,
  `subset?`, `est-count`, `settings` — is present in the port.
- **What the Eve port carries:**
  - `contract-test/lib/me/tonsky/persistent_sorted_set.cljc` — the
    library, same namespace and public API, its persistent types as
    `eve-deftype`s so sets are slab-resident Eve values.
  - `contract-test/lib/me/tonsky/persistent_sorted_set/arrays.cljc` —
    the array-operations namespace, its substrate re-based from host
    arrays to Eve vectors.
  - `contract-test/vanilla/` — verbatim copies of the two ported 0.2.3
    files; `contract-test/upstream-pss/` — a plain vendored copy of the
    upstream repo at 0.2.3 (commit
    `db09ff6d0c478f60d97cb5f16ce234935506f2ea`) carrying the upstream
    test suite and its MIT `LICENSE` inside the copy.
  - `contract-test/PORTING.md` — every delta from vanilla, categorized.

### 3. extend-clj 0.1.0

- **What:** a tiny macro/protocol utility library — DataScript 1.8.1's
  second pinned dependency (cross-checked against the published
  dependency list: `io.github.tonsky/extend-clj 0.1.0`).
- **Upstream:** https://github.com/tonsky/extend-clj
- **Author:** Nikita Prokopov.
- **License:** **MIT — Copyright (c) 2023 Nikita Prokopov.** Verified
  firsthand 2026-11-07 by reading the `LICENSE` file at upstream
  `master`.
- **What the Eve port carries:**
  `contract-test/lib-ds-deps/extend_clj/core.cljc` — vendored
  **verbatim, unmodified** (porting delta: NONE, per PORTING-DS.md's
  Dependencies section).

### 4. Eve itself (the host, not brought in)

The port runs on Eve — Eve's persistent atoms and domains, its HAMT,
its codec and seal. Eve's own license (in `eve/LICENSE` in the
development trees) is the ISC-style permission notice ("Permission to
use, copy, modify, and/or distribute this software for any purpose with
or without fee is hereby granted"). Eve is the porting project's own
work; it is listed here only so the boundary is unambiguous: the
brought-in components above keep their own licenses, and nothing in
Eve's license reaches into them.

## License obligations — verdict per component

EPL-1.0 §3 (read from the license text itself) requires, for source
form: the Program must be made available under the EPL; **a copy of the
license must be included with each copy of the Program**; copyright
notices contained within the Program may not be removed or altered; and
each Contributor must identify itself as the originator of its
Contribution in a manner that reasonably allows Recipients to identify
it. MIT requires the copyright notice and the permission notice to be
included in all copies or substantial portions of the Software.

| Component | License | Obligation | State in the current trees | Verdict |
|---|---|---|---|---|
| DataScript 1.8.1 (port in `lib-ds/`) | EPL-1.0 | EPL text must accompany each copy of the Program in source form; notices preserved; port's changes identified as the Eve project's Contribution | EPL text rides in-tree at `vanilla-ds/LICENSE` and `upstream-ds/LICENSE` (both read and confirmed EPL-1.0, 2026-11-07); every port delta is marked `PORT DELTA` at the site and catalogued in PORTING-DS.md; ported files carry no upstream copyright headers upstream either — none were removed | **Satisfied within the development trees. One gap for standalone distribution:** `lib-ds/` itself carries no LICENSE file, so a copy of the port shipped *without* its sibling `vanilla-ds/`/`upstream-ds/` trees would travel without the EPL text. Remedy drafted with this document: `NOTICE-datascript-epl-1.0.txt` (which itself directs that the EPL-1.0 text ship alongside) — the release packaging must place the EPL text + that NOTICE beside the ported sources |
| persistent-sorted-set (port in `lib/`) | MIT (c) 2019 Nikita Prokopov | Copyright + permission notice included in all copies | The MIT text with the 2019 copyright line sits at `contract-test/LICENSE` (the harness root — read and confirmed, 2026-11-07) and inside the vendored `upstream-pss/` copy | **Satisfied in-tree.** For a standalone copy of `lib/`, ship `LICENSE-persistent-sorted-set-MIT.txt` (drafted with this document) beside it |
| extend-clj 0.1.0 (vendored in `lib-ds-deps/`) | MIT (c) 2023 Nikita Prokopov | Copyright + permission notice included in all copies | The vendored file is verbatim, but **no LICENSE file sits beside it** anywhere in the tree | **Gap — remedy drafted:** `LICENSE-extend-clj-MIT.txt` (the upstream MIT text with the 2023 copyright line, read from upstream 2026-11-07) must be placed beside `lib-ds-deps/extend_clj/` at packaging time |
| Upstream test suites (`upstream-ds/test/`, `upstream-pss/`) | EPL-1.0 / MIT respectively | Same as their libraries | Both vendored copies carry their LICENSE files inside the copy | **Satisfied** |
| Eve (host platform) | Eve's own ISC-style notice | n/a (project's own code) | `eve/LICENSE` in-tree | n/a |

Tree-hygiene note: `contract-test/LICENSE` at the harness root is the
persistent-sorted-set MIT text (it entered with the Phase-1 PSS port).
The same tree also holds EPL-1.0 material (DataScript). The
per-component LICENSE files under `vanilla-ds/`, `upstream-ds/` and
`upstream-pss/` are the operative notices for their components; the
NOTICE drafted here exists so no reader has to guess which license
governs which part.

**Bottom line:** no license blocks this library. Two packaging actions
remain, both drafted and ready: (1) ship the EPL-1.0 text +
`NOTICE-datascript-epl-1.0.txt` beside the ported `datascript.*`
sources in any standalone distribution; (2) place
`LICENSE-extend-clj-MIT.txt` beside the vendored extend-clj copy.
Attribution to Nikita Prokopov is carried in this document, in the
NOTICE, in the lane PROVENANCE files, and in the preserved upstream
LICENSE copies.
