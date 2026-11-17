# eve.db.ds #56 — Eve producer coverage

Source: `johnmn3/atom.broker`, `lane-m/eve-db-ds-gaps` commit
`2dce388a25606337d837ec5ca9763faf62ca3fe1`, `docs/eve-db-ds-gaps/GAPS.md`.
Rows below are numbered in that table's original order (134 data rows).
`built_ins.cljc:127` and several hash rows have an extra empty column; locations
are recognized by file:line rather than by trusting column offsets.

## Newly supplied APIs

- `eve.map-entry/map-entry`: one slab-backed `eve-deftype MapEntry`. Require
  `eve.map-entry`. Implements Clojure map entry and two-element vector faces,
  `key`/`val`, indexed lookup, seq/count, equality, hash, printing and codec
  round trip. No host MapEntry factory and no reader conditional in its body.
- `eve.host/byte-array`: the interpreted-JS host adapter now realizes signed
  byte storage; the Basilisp adapter supports scalar initialization too.
  This is memory infrastructure supporting the type, not blanket closure of
  the application's array/dict rows.
- `eve.core/nth`: already defined on the release's adapters. The release's E1
  `eve-core-ready?` fix answers #404. `test/eve/seam_bootstrap.clj` proves the
  previously failing crypto/hamt-util/thread-only startup on both bb and
  bb-minimal. No invented second nth implementation was added.

## Proof and limits

Focused contract: JVM, nbb and fresh-cache Basilisp each pass **3 tests / 76
assertions**, including MapEntry's native map conj and codec round trip.
Commands and full-suite results are in `docs/EVE-DB-DS-TESTS.md`.
Babashka native-core parity is **OPEN**: its `SciType` cannot be consumed by
all native collection operations. Eve protocol success is not native parity.
The bb adapter work is isolated in wakes DISPATCH #2. Remaining in-scope API
work is isolated in muse DISPATCH #37; neither may overwrite this branch.

No GAPS row is called fully closed solely from JVM proof or from an existing
API name. Consumer branch deletion belongs to eve.db.ds's `one-eve-impl`
worker, not this producer branch. No release merges or tags have been made.

## Per-row coverage

| Row | Original location | State | API or next action |
|---:|---|---|---|
| 1 | `built_ins.cljc:127` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 2 | `conn.cljc:8` | Type API already provided; consumer conversion open | eve.deftype/eve-deftype for shared data; eve.deftype/deftype only for process-local views |
| 3 | `conn.cljc:13` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 4 | `conn.cljc:30` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 5 | `conn.cljc:41` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 6 | `conn.cljc:53` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 7 | `conn.cljc:114` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 8 | `conn.cljc:189` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 9 | `core.cljc:9` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 10 | `core.cljc:39` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 11 | `core.cljc:103` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 12 | `core.cljc:194` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 13 | `core.cljc:233` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 14 | `core.cljc:257` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 15 | `core.cljc:277` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 16 | `core.cljc:281` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 17 | `core.cljc:293` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 18 | `core.cljc:615` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 19 | `core.cljc:628` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 20 | `core.cljc:672` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 21 | `db.cljc:3` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 22 | `db.cljc:8` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 23 | `db.cljc:22` | Type API already provided; consumer conversion open | eve.deftype/eve-deftype for shared data; eve.deftype/deftype only for process-local views |
| 24 | `db.cljc:36` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 25 | `db.cljc:358` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 26 | `db.cljc:577` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 27 | `db.cljc:596` | API supplied; bb native parity open | eve.map-entry/map-entry; JVM/nbb/Basilisp native contract + codec proof; consumer deletion separate |
| 28 | `db.cljc:662` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 29 | `db.cljc:686` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 30 | `db.cljc:697` | Type API already provided; consumer conversion open | IPrintWithWriter in one Eve type; adapter owns native print realization |
| 31 | `db.cljc:872` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 32 | `db.cljc:980` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 33 | `db.cljc:982` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 34 | `db.cljc:1159` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 35 | `db.cljc:1387` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 36 | `db.cljc:1416` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 37 | `db.cljc:1453` | API supplied; bb native parity open | eve.map-entry/map-entry; JVM/nbb/Basilisp native contract + codec proof; consumer deletion separate |
| 38 | `db.cljc:1496` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 39 | `db.cljc:1566` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 40 | `db.cljc:1630` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 41 | `db.cljc:1678` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 42 | `db.cljc:2142` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 43 | `db.cljc:2179` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 44 | `db.cljc:2382` | Type API already provided; consumer conversion open | IPrintWithWriter in one Eve type; adapter owns native print realization |
| 45 | `db.cljc:2402` | Type API already provided; consumer conversion open | IPrintWithWriter in one Eve type; adapter owns native print realization |
| 46 | `impl/entity.cljc:22` | Type API already provided; consumer conversion open | eve.deftype/eve-deftype for shared data; eve.deftype/deftype only for process-local views |
| 47 | `impl/entity.cljc:24` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 48 | `impl/entity.cljc:54` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 49 | `impl/entity.cljc:66` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 50 | `impl/entity.cljc:70` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 51 | `impl/entity.cljc:74` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 52 | `impl/entity.cljc:82` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 53 | `impl/entity.cljc:87` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 54 | `impl/entity.cljc:93` | Type API already provided; consumer conversion open | eve.deftype/eve-deftype for shared data; eve.deftype/deftype only for process-local views |
| 55 | `impl/entity.cljc:155` | API supplied; bb native parity open | eve.map-entry/map-entry; JVM/nbb/Basilisp native contract + codec proof; consumer deletion separate |
| 56 | `impl/entity.cljc:184` | API supplied; bb native parity open | eve.map-entry/map-entry; JVM/nbb/Basilisp native contract + codec proof; consumer deletion separate |
| 57 | `impl/entity.cljc:226` | API supplied; bb native parity open | eve.map-entry/map-entry; JVM/nbb/Basilisp native contract + codec proof; consumer deletion separate |
| 58 | `impl/entity.cljc:291` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 59 | `impl/entity.cljc:299` | Type API already provided; consumer conversion open | IPrintWithWriter in one Eve type; adapter owns native print realization |
| 60 | `impl/entity.cljc:319` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 61 | `impl/entity.cljc:344` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 62 | `lru.cljc:14` | Type API already provided; consumer conversion open | eve.deftype/eve-deftype for shared data; eve.deftype/deftype only for process-local views |
| 63 | `lru.cljc:20` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 64 | `lru.cljc:185` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 65 | `lru.cljc:193` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 66 | `lru.cljc:200` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 67 | `pull_api.cljc:12` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 68 | `pull_api.cljc:46` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 69 | `pull_api.cljc:49` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 70 | `pull_api.cljc:51` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 71 | `pull_api.cljc:54` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 72 | `pull_api.cljc:56` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 73 | `pull_api.cljc:59` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 74 | `pull_api.cljc:88` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 75 | `pull_api.cljc:89` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 76 | `pull_api.cljc:95` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 77 | `pull_parser.cljc:8` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 78 | `pull_parser.cljc:73` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 79 | `query.cljc:6` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 80 | `query.cljc:37` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 81 | `query.cljc:57` | Type API already provided; consumer conversion open | IPrintWithWriter in one Eve type; adapter owns native print realization |
| 82 | `query.cljc:121` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 83 | `query.cljc:213` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 84 | `query.cljc:434` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 85 | `query.cljc:687` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 86 | `query_v3.cljc:62` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 87 | `query_v3.cljc:107` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 88 | `query_v3.cljc:178` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 89 | `query_v3.cljc:274` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 90 | `query_v3.cljc:306` | Type API already provided; consumer conversion open | IPrintWithWriter in one Eve type; adapter owns native print realization |
| 91 | `query_v3.cljc:345` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 92 | `query_v3.cljc:347` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 93 | `query_v3.cljc:357` | Type API already provided; consumer conversion open | IPrintWithWriter in one Eve type; adapter owns native print realization |
| 94 | `query_v3.cljc:516` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 95 | `query_v3.cljc:519` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 96 | `query_v3.cljc:525` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 97 | `query_v3.cljc:539` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 98 | `query_v3.cljc:570` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 99 | `query_v3.cljc:571` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 100 | `query_v3.cljc:597` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 101 | `query_v3.cljc:598` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 102 | `query_v3.cljc:610` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 103 | `query_v3.cljc:634` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 104 | `query_v3.cljc:641` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 105 | `query_v3.cljc:650` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 106 | `query_v3.cljc:758` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 107 | `query_v3.cljc:811` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 108 | `query_v3.cljc:814` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 109 | `query_v3.cljc:819` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 110 | `query_v3.cljc:824` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 111 | `query_v3.cljc:840` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 112 | `query_v3.cljc:878` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 113 | `query_v3.cljc:881` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 114 | `query_v3.cljc:884` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 115 | `query_v3.cljc:887` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 116 | `query_v3.cljc:890` | Already provided in source inventory; not a new closure | Inventory verdict retained; require consumer/test evidence before banking full closure |
| 117 | `serialize.cljc:15` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 118 | `serialize.cljc:41` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 119 | `serialize.cljc:42` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 120 | `serialize.cljc:43` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 121 | `serialize.cljc:127` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 122 | `serialize.cljc:133` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 123 | `serialize.cljc:145` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 124 | `serialize.cljc:161` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 125 | `serialize.cljc:256` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 126 | `serialize.cljc:257` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 127 | `serialize.cljc:258` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 128 | `serialize.cljc:362` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 129 | `serialize.cljc:363` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 130 | `serialize.cljc:364` | Out of Eve scope | Plain clojure.core call per coordinator #56h/#56i; no new Eve shim |
| 131 | `util.cljc:10` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 132 | `util.cljc:488` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 133 | `util.cljc:491` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
| 134 | `util.cljc:523` | Open; remaining core/adapter unit | muse DISPATCH #37 (bb visibility subset: wakes #2); require API/source/host proof |
