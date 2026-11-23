# U4a ledger — eve's flat sisters over eve.flat-io on the JVM, bb and Node (append-only, newest at the tail)

Brief: specs/fold-fix/race-nk/flat/lanes/U4-deftype-sisters.md §U4a (valuescript repo). Worktree /home/user/w/eve-u4a,
local branch flat-u4a, base 036644a5c8 (the wave-1 collation of eve.flat-io).

## 2026-11-17 2:14 AM ET — step 1: the sister suite re-derived at the base, before any change

Provenance: eve (atom.broker) flat-u4a at 036644a5c88a8a63bfd850a80430eb67411f8edc, clean tree; JDK 21.0.12.1
(Clojure CLI 1.12.3.1577, clojure 1.12.0), babashka 1.12.218, Node v22.23.4; 4 cores shared with other lanes (load1
1.9–2.2 during these runs). Each run is a fresh process on the worktree as checked out (no reused state: the suite
builds its own atoms and chunks per run).

- **JVM** — `clojure -M:jvm-test -n eve.flat.sister-test` (8.4 s wall): **6 tests / 45 assertions, 0 failures, 0
  errors.** The flat-io root on that classpath is the JVM fast root (deps.edn :paths puts adapters/flat-io/jvm/src
  before portable; the COLLATION entry checked which file loads).
- **Reflection, JVM** — sister.cljc sets no `*warn-on-reflection*`, so a normal run reports nothing for it. Measured
  with a probe that loads eve.flat.chunk, sets the flag, then `(require 'eve.flat.sister :reload)` and
  `(require 'eve.flat.sister-test :reload)` (java -cp "$(clojure -Spath -M:jvm-test)" clojure.main probe.clj):
  **0 reflection warnings from sister.cljc, 0 from sister_test.clj** (whose eve-deftypes Widget / Gadget generate
  the sister classes). The 5 warnings the probe printed are in files loaded for the first time under the flag
  (eve/region.clj ×4, eve/atom.cljc ×1) — not this lane's. So "sister.cljc reflection-free" already holds at the
  base; what this lane adds is the flag in sister.cljc so every load checks it.
- **bb** — the suite is a .clj; bb loads it as written: `bb -cp "$(bb classpath bb):test" -m eve.runner.bb-main
  test eve.flat.sister-test` (3.3 s): **6 tests / 37 assertions: 19 pass, 6 fail, 12 errors.** Every sister
  assertion fails for one measured reason: `the sister of eve.flat.sister-test/Widget has no generated factory`
  (and Gadget) — bb's eve.types (adapters/types/bb/src/eve/types.clj) has no `flat-type`, so
  eve.deftype-proto.emission/flat-forms answers nil there and the type registers its layout alone: rows read as
  FlatRow / FlatRecord handles, which satisfy none of the type's protocols (`No implementation of method:
  :field-map of protocol IFields found for: :sci.impl.protocols/reified`). boundary-conversion errors separately:
  `sci.lang.Type cannot be cast to java.lang.Class` (bb's types are sci.lang.Type, not classes).
- **Node** — not runnable at the base: the suite is a .clj (ClojureScript compiles no .clj test). And the sister is
  not realized on ClojureScript at all, read in the source at the base: adapters/types/js/src/eve/types_js.cljc
  `flat-type` answers nil ("not realized at this pin"); chunk.cljc's RecordSource / RowSource are `#?(:clj …)` only,
  `row-handle`'s and `read-value`'s :cljs arms never consult a sister factory; sister.cljc `register-descriptor!`'s
  :cljs arm is nil; and the ClojureScript writer has no type table in v1 (chunk.cljc's :cljs `scan` returns
  `:types {}`, `write-node!`'s TypedVal arm is :clj only — a typed value packs as its field map). Also, the :cljs
  arm of `layout-fingerprint` computes FNV-1a on JS numbers (32-bit bit-xor), so it cannot equal the JVM's 64-bit
  fingerprint, and a fingerprint read back from a chunk is a js/BigInt (fio/i64), which cljs cannot hash as a map
  key (no IHash on BigInt in eve or cljs.core).
- **sister.cljc's byte reads at the base**: read-primitive reads a slab instance's fields through eve.slab-io
  (u8 / u16 / i32), and its float32 / float64 arms decode bits through host branches (`i32->f32` / `i64->f64`:
  :jank enc/…, :clj Float/intBitsToFloat · Double/longBitsToDouble, **:cljs throws "not realized on this host
  yet"**). A sister's own field read (eve.flat.source/-field on chunk.cljc's RowSource) goes row-lookup →
  batch-parts → shape-index → col-at, whose reads are already fio's (fio/i32 · fio/i64 · fio/f64 · fio/u8 …).

So the work at this base is larger than "port the test": sisters exist on the JVM only. To be green on three hosts
the sister must be realized on bb (a flat-type for sci's deftype) and on ClojureScript (a flat-type, the field
sources, the reader's factory lookups, the writer's type table, an exact 64-bit fingerprint).

## 2026-11-17 2:33 AM ET — the sister on three hosts (commits f33c3aadf9, a04fc5f0b3, 956dae1350, 1f4c7d552c)

What changed (each measured below):

- **sister.cljc**: a float32 / float64 field of a slab instance is decoded by `fio/f32` / `fio/f64` over the field's
  own bytes (`slab-io/read-bytes`) — the per-host casts and ClojureScript's throw are gone; `uint32` by `bit-and`, no
  clojerl branch; no `#?` is left in the byte reads. The factory registers with the chunk on every host. The `:cljs`
  `layout-fingerprint` is FNV-1a in js/BigInt space, the JVM's long exactly. `*warn-on-reflection*` is set for the
  file. The rep converters answer a plain value before any `satisfies?` (below: a 7x pack cost, at the base too).
- **vocab.cljc** `flat-method-body` (moved from sio.cljc; every host's sister generator calls it): binds only the live
  fields a method body names, so a field read is one column read at the row, not one per field of the type.
- **chunk.cljc**: RecordSource / RowSource and the reader's factory lookups (`read-value`, `row-handle`) on every
  host; a row source reads a field through the batch's field plan (`batch-field-cols`: keyword → parsed column, once
  a batch in the Ctx's plans) then `col-at` — fio's typed read at data + width × row. Registry keyed by `fp-key` (a
  BigInt fingerprint hashes by its string on ClojureScript). The ClojureScript scan writes the type table (types,
  shapes under their type, field classes in its number model); `write-node!`'s and the batch writer's TypedVal arms
  are host-neutral (`typed-index` / `typed-shape`; jank / glojure, no type table, still write the field map).
  `generic-record?` by the IGenericRecord marker on every host (the cljs FlatTagged now carries it). `equiv-any`
  compares a sister by its own equality (a sister has no node position; a flat map holding sisters threw through
  `handle-ctx` on `=`).
- **bb eve.types** `flat-type`: the shared `sio/flat-type` with bb's hooks, built through `->T`.
- **types_js.cljc** `flat-type`: a ClojureScript sister type (field source, ISister, IFlatHandle, IHash / IEquiv by
  type key + fields, `#eve.flat/` printing) and its factory; the no-domain constructor takes the flat path
  (`construct-flat`) as on the JVM and bb (it threw `no-domain-context!` there).
- **sister_test.clj → .cljc**: byte reads through eve.flat-io (the fingerprint flip walks the type table with
  `fio/varint` / `fio/utf8` and rewrites one byte through fio's writer); H5.5 (atom boundary) / H5.6 (classpath)
  stay `:clj` (the JVM-class hosts). shadow-cljs.edn `:flat-sister-test` (`:node-test`, `:ns-regexp
  "eve.flat.sister-test$"`); bb.edn `test:flat-sister`; host_suite.cljc lists the suite on `:cljs`.

Measured (provenance: flat-u4a at the commit named per line, JDK 21.0.12.1 / Clojure 1.12.0, bb 1.12.218, Node
22.23.4 + shadow-cljs 3.3.6; load1 2.1–3.8; each run a fresh process; the Node build recompiled from the tree):

- **JVM** `clojure -M:jvm-test -n eve.flat.sister-test` (a04fc5f0b3): **6 tests / 45 assertions, 0 F, 0 E** (= base).
  sister.cljc under its own `*warn-on-reflection*`: 0 warnings; chunk.cljc's 52 warnings the same 52 messages as at
  the base (diffed), none on the field-read path (col-at, batch-parts, RowSource, batch-field-cols, row-handle).
- **bb** `bb -cp "$(bb classpath bb):test" -m eve.runner.bb-main test eve.flat.sister-test` (a04fc5f0b3): **6 tests /
  37 assertions: 36 pass, 0 F, 1 E** (base: 19 pass, 6 F, 12 E). The one error is boundary-conversion's first arm:
  `st/transfer!` → eve.flat.stream `cursor-class-size` (stream.cljc:69, `(aget ^ints (.-block_sizes ^JvmSlabCtx
  root) …)`): `sci.lang.Type cannot be cast to java.lang.Class` — identical at the base (same message, same line),
  outside the sister and outside this lane's files; the deftest stops there, so bb runs 37 of the JVM's 45
  assertions. Recorded for the coordinator (repro: the command above, at 036644a5c8 or at a04fc5f0b3).
- **Node** `clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-sister-test && node
  target/flat-sister-test/main.js` (a04fc5f0b3; compile 31 s): **4 tests / 30 assertions, 0 F, 0 E** — the four
  portable tests (H5.1–H5.4), which carry 30 of the JVM's 45 assertions; H5.5 (9) and H5.6 (6) are `:clj`.
- **Regressions, JVM** (a04fc5f0b3): `clojure -M:jvm-test` with -n sister, own-type-delivery, proto-types,
  dataset-batch, build, image, record, deftype-guard, deftype.type-id, chunk, contract, column, host-suite,
  requirements: 72 tests / 12,695 assertions, 2 F / 1 E — host-flat-suite (`:squint` undispositioned),
  image-read-speed (ratio 1.61 > 1.2), requirements-covered (image_node_test not loadable on the JVM): the three are
  among the ten U2a's ledger names as red at its base; nothing else red.
- **Regressions, Node**: flat-x1-test 4 / 33 / 5 F, flat-parity-test 8 / 27 / 2 F / 1 E, flat-h4-test 4 / 31 / 3 F —
  the output diffed **identical** to the same three builds compiled from a `git archive 036644a5c8` export (scratch).
- **The converter cost** (one JVM, the 1BRC shape with vector values, best of 30 ×3): `fc/pack` 4.6 ms with
  eve.flat.sister not loaded, **34 ms** once it is loaded with one type registered; at the base export **3.8 → 35
  ms** (pre-existing: every pack consulted the converters' `satisfies?` per value). After 1f4c7d552c: **3.4–5.0 ms**
  loaded.

## 2026-11-17 2:30–2:41 AM ET — flatx with record values on three hosts (bench/flat-io/flatx_rec.cljc)

The bench: `bench/flat-io/run-bench <jvm|bb|node> rec <root>` (ROUNDS=3; JVM the jvm root, bb the portable root, Node
the js root in an `:advanced` build), one process running flatx's rows with **vector values** (the before) and then with
**Stats eve-deftype values** (the after, `(edt/deftype Stats [mn sm mx ct] IStats … clojure.lang.ILookup …)`). Each
number is a row's best-of-20 in µs, one per round. Provenance: flat-u4a at 1f4c7d552c (JVM, bb) and a43dc13b61 (Node:
the same source plus run-bench's Node classpath fix), JDK 21.0.12.1, bb 1.12.218, Node 22.23.4 + shadow-cljs 3.3.6,
load1 3.1–5.0; every run a fresh process. Answers, every round on every host: `get "Station-5"` = [-5 5000 55 12] for
both; `equal?` true for both; field-sum −82,621 for both; format 10,605 chars for both; a record value is the
generated sister (`Stats__Flat`, `ISister` true); chunk bytes 15,720 (vector) / 19,864 (record).

| row (µs)                                | JVM vec               | JVM rec                  | bb vec                      | bb rec                      | Node vec                 | Node rec                 |
| --------------------------------------- | --------------------- | ------------------------ | --------------------------- | --------------------------- | ------------------------ | ------------------------ |
| build the persistent map ×407           | 47 / 48 / 76          | 21,238 / 19,908 / 21,914 | 4,290 / 6,867 / 4,281       | 842,079 / 922,708 / 943,303 | 182 / 138 / 193          | 75,804 / 55,746 / 58,630 |
| fc/pack                                 | 9,720 / 6,636 / 5,670 | 12,669 / 14,676 / 15,211 | 393,797 / 437,472 / 395,462 | 445,479 / 467,645 / 465,735 | 21,890 / 17,136 / 22,515 | 29,328 / 23,556 / 24,325 |
| get ×407 (+ one field)                  | 1,204 / 901 / 768     | 368 / 359 / 513          | 21,304 / 21,743 / 20,561    | 34,701 / 34,746 / 35,449    | 1,446 / 1,320 / 1,407    | 1,259 / 1,186 / 1,391    |
| field read on every row (min, summed)   | 279 / 313 / 484       | 801 / 775 / 1,251        | 3,596 / 3,679 / 3,549       | 29,687 / 30,384 / 30,497    | 1,313 / 1,014 / 1,095    | 1,438 / 1,362 / 1,185    |
| … as `(:min s)`                         | —                     | 1,133 / 772 / 807        | —                           | n/a (below)                 | —                        | 1,648 / 1,262 / 1,363    |
| format                                  | 614 / 633 / 860       | 1,395 / 978 / 866        | 7,077 / 7,273 / 7,145       | 16,687 / 16,895 / 17,114    | 4,712 / 3,232 / 3,438    | 4,088 / 2,428 / 2,689    |
| `=` flat vs persistent                  | 4,602 / 4,536 / 4,392 | 817 / 1,353 / 1,069      | 510,849 / 504,616 / 485,916 | 41,663 / 43,005 / 43,135    | 12,787 / 9,828 / 9,548   | 4,926 / 4,738 / 4,544    |
| 407 records as a vector: pack           | —                     | 10,179 / 9,544 / 14,726  | —                           | 340,340 / 348,126 / 357,729 | —                        | 23,635 / 24,660 / 23,437 |
| … field read on every row (column[row]) | —                     | 203 / 217 / 217          | —                           | 5,067 / 5,165 / 5,153       | —                        | 1,101 / 1,164 / 1,082    |

Read with it:

- **The record values before this lane** (the same bench, one round, on a `git archive 036644a5c8` export with only
  flatx_rec.cljc and run-bench copied in): **JVM** — the record rows run through `rec/format` (pack 32,131; get 292;
  field-sum 603; format 744) and the run **dies at `rec/eq-flat-persistent`: `IllegalArgumentException` at
  chunk.cljc:1984** (the IHandlePos dispatch on a sister — the equality defect fixed in 956dae1350); its `vec/fc-pack`
  is 38,543 (the converter cost fixed in 1f4c7d552c). **bb** — dies at `rec/get-x407`: `No implementation of method:
  :scount of protocol IStats found for: :sci.impl.protocols/reified` (no sister on bb; a value read back as a
  generic record). **Node** — dies at `rec/persistent-map-x407`: "Eve data must be constructed inside a swap!"
  (`no-domain-context!`: the ClojureScript constructor had no flat path). So the record column exists on bb and Node
  only after this lane, and on the JVM it completes only after it.
- **Where a record value's field read goes.** The writer packs a map whose values are records as a MAP node of the
  407 keys whose every value is its own record node (a MAP of the type's 4-key shape) — so a map value's field read
  is that node's slot (`RecordSource` → `lookup-node`: the shape's index, then the scalar), not column[row]. A
  _vector_ of the same records is a BATCH, and there a sister's field read is column[row] (`RowSource` → the batch's
  field plan → `col-at` → `fio/i64` …): 203–217 µs for 407 rows on the JVM, under the vector values' 279–484. Writing a
  map of one type's instances as a KMAP whose values are a struct column (FORMAT-V1 §6 "a collection of instances is a
  BATCH of it"; a KMAP's values node may be a COL, and kind 21 is the struct column — the reader already reads it:
  `elem-at` → `col-at` 21 → `row-handle`) would put the map's field reads at column[row] too. Not done here: it changes
  the chunk bytes of every map of records on every host and the VS reader reads eve chunks as they lie (U3 / U4b), so
  it is a format decision for the coordinator, not a lane edit.
- **The construction row**: a Stats built outside an Eve domain is its flat sister (eve-deftype's rule), i.e. one
  one-record chunk packed and opened per `->Stats` — 407 of them cost 20–22 ms on the JVM, 0.84–0.94 s on bb,
  56–76 ms on Node. Inside a swap! the constructor allocates in the domain instead.
- **`(:min s)` on bb**: sci's deftype refuses `clojure.lang.ILookup` on a type that is not a full map (measured on bb
  1.12.218: "Babashka's deftype supports full map types (add IPersistentMap to the interface list)"); bb's eve.types
  translates the type's ILookup onto eve.core's protocol, which clojure.core's keyword invoke does not reach. The
  accessor row (`IStats/smin`, every host) is the field read there.
- **Node's `get ×407`** is at parity (1.2–1.4 ms either way); the JVM's record get (359–513) is under its vector get
  (768–1,204): `(scount s)` names one field, so the sister reads one slot (vocab's flat-method-body), where the
  vector row builds a column handle and `nth`s it.

## 2026-11-17 9:56 AM ET — resumed after the session pause; final verification at a43dc13b61

`ps`: no process survived the pause; every log above was complete on disk (the base Node run had finished its round
and died where recorded). Re-run at a43dc13b61, each a fresh process (load1 1–5, the box otherwise idle):

- **JVM** `clojure -M:jvm-test -n eve.flat.sister-test`: **6 tests / 45 assertions, 0 F / 0 E** (10.9 s). With -n
  host-suite-test, chunk-test, contract-test beside it: 19 / 628, 1 F — host-flat-suite's `:squint` row, red at the
  base too. **0 reflection warnings from sister.cljc** (the file sets the flag itself now).
- **bb** `bb test:flat-sister`: **6 tests / 37 assertions: 36 pass, 0 F, 1 E** — boundary-conversion's `transfer!`
  in eve.flat.stream:69, as at the base.
- **Node** `clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-sister-test` (31 s, from a clean
  target/flat-sister-test) `&& node target/flat-sister-test/main.js`: **4 tests / 30 assertions, 0 F / 0 E.**

**Defects outside this lane's files** (recorded, not fixed):

1. eve.flat.stream `cursor-class-size` (stream.cljc:69, and its twin `class-size` at :60) on babashka: `(aget ^ints
   (.-block_sizes ^JvmSlabCtx root) (int ci))` — `sci.lang.Type cannot be cast to java.lang.Class` once `root` is a
   JvmSlabCtx (a sci type on bb, so the class hint cannot resolve). Reached by `eve.flat.transfer/transfer!`. Repro:
   `bb test:flat-sister` (or, at 036644a5c8, `bb -cp "$(bb classpath bb):test" -m eve.runner.bb-main test
   eve.flat.sister-test`) → boundary-conversion ERROR at its first arm. It is the whole difference between bb's 37
   assertions and the JVM's 45.
2. (format, not a defect) a map of records packs as record nodes, not a struct column — above.
