# U2a ledger (append-only, newest at the tail)

## 2026-11-17 00:47 AM ET — baselines at the Phase-0 pin (before any change)

Provenance: atom.broker worktree /home/user/w/eve-u2a, branch flat-u2a at 3b656fea20 (unmodified tree); OpenJDK 21.0.12.1,
Clojure CLI (deps.edn: clojure 1.12.0), babashka v1.12.218, Node v22.23.4 (/opt/node22/bin/node), shadow-cljs 3.3.6 from ~/.m2.
Fresh: a clean checkout, no target/ before the Node build. Logs: the session scratchpad (logs/*-before.log).

- **JVM flat suite** (host_suite.cljc :jvm, 15 namespaces):
  `clojure -M:jvm-test -n eve.flat.requirements-test -n eve.flat.contract-test -n eve.flat.chunk-test -n eve.flat.host-suite-test -n eve.flat.image-test -n eve.flat.alloc-run-test -n eve.flat.place-test -n eve.flat.tally-count-test -n eve.flat.transfer-test -n eve.flat.build-test -n eve.flat.column-test -n eve.flat.container-test -n eve.flat.check-test -n eve.flat.list-test -n eve.flat.sister-test`
  → **72 tests, 11,530 assertions, 5 failures, 5 errors** (1 m 04 s). None in chunk-test, contract-test, build, column,
  container, check, list, sister, place, alloc-run. The ten: host-flat-suite (hosts.edn has :squint, suites does not),
  image-read-speed (ratio 1.90 > 1.2), requirements-covered (eve.flat.image-node-test not loadable on the JVM),
  tally-count written-counts-equal-walked ×2 + written-counts-shared, transfer-test ×4 OutOfMemoryError (heap domains).
- **bb flat suite** (host_suite.cljc :bb, 14 namespaces; chunk-test added as a 15th, since the suite's Murmur3 exclusion
  reason no longer matches chunk.cljc's ns form). As one `bb -m eve.runner.bb-main test <14 ns>` call the run does not
  load at all (place-test: `Unable to resolve symbol: definterface`), so each namespace runs alone (scratch runner
  run-bb-flat.sh: `bb -cp $(bb classpath bb):test:test.check-1.1.1.jar -m eve.runner.bb-main test <ns>`):
  requirements 2t 574p/0f/20e · contract 3t 33p · host-suite 1t 330p/1f · image 7t 65p/1f/7e · alloc-run 6t 32p ·
  place LOAD FAIL (definterface) · tally-count 5t 0p/5e · transfer 6t 0p/6e · build LOAD FAIL (definterface) ·
  column LOAD FAIL (java.nio.DoubleBuffer) · container 5t 38p · check 5t 73p · list 2t 12p · sister 6t 19p/6f/12e ·
  chunk-test 8t 150p/3f (chunk-equality-domain ×3). Wall 7 min.
- **Node flat suite** (host_suite.cljc :cljs, eve.flat.chunk-node-test, its own small build):
  `clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-x1-test && node target/flat-x1-test/main.js`
  → build 88 files, 6.6 s; **4 tests, 33 assertions, 5 failures, 0 errors** — golden-bytes ×2 and jvm-node-same-bytes ×3
  (the fixture hex differs from the packed hex: pre-existing at this pin).
- **A defect re-derived (Node, this pin):** a COL of negative i32s reads back unsigned. Probe (scratch u2a.probe,
  `--config-merge` onto flat-x1-test): `(fc/pack [-5 -1 3 -2147483648])` → `[4294967291 4294967295 3 2147483648]`,
  `=` false. Cause: chunk.cljc's cljs ri32 is `(unchecked-int (ru32 b i))`, and cljs.core/unchecked-int is `fix`
  (truncation), not a 32-bit wrap. It is in the byte layer this lane moves: fixed in flat-io/portable's i32 (`|0`),
  pinned by the conformance test.

## 2026-11-17 00:59 AM ET — the seam: flat-io/portable, chunk.cljc over it, every row wired (12295ecefc)

- adapters/flat-io/portable/src/eve/flat_io.cljc: chunk.cljc's byte layer moved with its :clj / :bb / :glj / :jank /
  :cljs arms (ru8 … rf64 → u8 … f64, rvarint → varint, varint-len, ba-copy-of, IBuf + the three Buf deftypes, dv-of,
  the BigInt helpers the writer's i64 needs) plus the contract's additions (varint-v, varint-next, utf8, bytes=,
  bytes-cmp, key-cmp, chunk / chunk-bytes / chunk-len, buf-chunk, buf-align, tuple / col). The IBuf methods are renamed
  w-prefixed (wU8 …): on bb / glojure / jank a protocol method is a var, and u8 … f64 are now the namespace's reads.
- src/eve/flat/chunk.cljc: every byte read / write through fio; the Ctx holds the chunk (b) and the host bytes (bs);
  host byte arrays made / copied / text-coded with eve.encoding's portable calls (make-bytes, arraycopy, utf8-bytes,
  f32-le-bytes); the recipe slot patched through the writer (buf-patch-u32, buf-seek) instead of aset on the copy.
- Equivalence (scratch harness samebytes.clj: the Phase-0 chunk.cljc loaded beside it as eve.flat.chunk-orig; 52 values
  — every node kind, every column kind incl. f32 / i64 / narrowed / run-end / utf-8, metadata, byte arrays, records,
  a WIDE chunk, the corpora :tiny set, the 1BRC map; pack bytes, unpack, hash, digest, seq, get, conj, assoc,
  pack-all): **JVM 0 differences, bb 0** (two byte-array values compared outside the recipe slot: the ORIGINAL's slot
  is not deterministic for them — a byte array's identity hash and by-reference digest).
- JVM flat suite on it: **72 tests, 11,530 assertions, 5 failures, 5 errors — the same ten as the baseline**.
- Node flat suite on it: **4 tests, 33 assertions, 5 failures — the same five, same text** (diffed); warnings 44 → 54
  (10 :infer-warning in flat_io.cljc's moved buf-* dispatch; chunk.cljc's stay 33).
- The cljs i32 defect: probe after the fix `[-5 -1 3 -2147483648]`, `=` true.

## 2026-11-17 01:04 AM ET — the conformance test (29e18468eb)

adapters/flat-io/test/eve/flat_io_conformance_test.cljc, 11 tests; runners `clojure -M:flat-io-test` (deps.edn),
`bb test:flat-io` (bb.edn), `clojure -M:node-test:flat-io-node -m shadow.cljs.devtools.cli compile flat-io-test && node
target/flat-io-test/main.js` (shadow-cljs.edn :flat-io-test, test/eve/runner/flat_io_main.cljs).
On flat-io/portable: **JVM 11 tests / 264 assertions / 0 failures (10.7 s); bb 11 / 264 / 0 (1.2 s); Node 11 / 264 / 0**.
First bb run: 2 errors in the TEST (not the root): `(/ 1.0 -0.0)` throws on bb (boxed Numbers.divide refuses a zero
divisor; the JVM passed only because the read stayed a primitive double) — the test checks the sign with
Math/copySign (ClojureScript: 1/x) instead.

## 2026-11-17 01:11 AM ET — the lookup (ab723cde77)

Measured first (scratch getprof.clj, JVM, the 1BRC map, best of 30, at 3b656fea20's chunk): get×407 ≈ 900 µs, of
which the shape's linear `=` index ≈ 960 µs alone and the value's nth ≈ 52 µs — the key walk is the cost.
lookup-node / row-lookup / assoc-flat: from 16 keys a binary search in key-byte order — MAP over its shape's symbol
codes (parse-shapetab keeps :codes), KMAP over its keys node (COL kinds 17/18 → symbol entries, 19 → utf-8 in place,
number kinds → the cell's key bytes; VEC → SYM or scalar bodies in place) — only for a key whose `=` is its bytes'
equality (string, keyword, symbol, integer, boolean, char) and a keys node of scalars (no nil, no collection; memoized
in the Ctx's plans). fio/key-cmp for a string against a stored string; fio/bytes-cmp against encode-key bytes else.

- Equivalence (scratch lookups.clj vs the Phase-0 chunk's linear `=`): 22 maps (every key layout: 407 strings,
  keywords, ns keywords, symbols, mixed, long + 255/256 boundary strings, ints narrowed / negative / wide / run-coded,
  mixed scalars, chars, bigints, nil key, doubles, 0.0 with -0.0 probed, collection keys, uuids, a record, batch rows)
  × 108 probes + each map's own keys: **JVM 0 differences, bb 0**.
- flatx interleaved A/B in one JVM (scratch flatxab.clj; both chunks loaded, same load; round 3 of 4, load 6.1):
  **get×407 1,084 → 347 µs**, pack 3,625 → 3,340, seq 37 → 37, format 287 → 288, `=` true on both.
- The standalone probe (doc/flat/lanes/flatx.clj, 3 runs each, best-µs) — before at load ≈ 2.7, after at 4.8–6.0:
  pack 6,884 / 6,198 / 9,571 → 25,183 / 22,366 / 17,552; get×407 1,060 / 1,049 / 1,064 → 902 / 963 / 901;
  seq 132 / 114 / 121 → 126 / 139 / 132; format 565 / 427 / 471 → 443 / 500 / 453. The pack column is the box, not
  the change: the interleaved A/B above (same JVM, same moment) has the seam's pack at or below the old one's.

## 2026-11-17 01:18 AM ET — column.cljc and sio.cljc read through the seam; doc rows (7e95edfcbf)

- eve.flat.column: open-column / open-batch / col-ref / scan-sum's kind-22 arm / count-eq read through fio (a handle
  carries :c, the chunk); its private ru8 … ri64 and read-varint are gone. Kept as the JVM's own, by name: scan-sum's
  NIO sums over kinds 6 / 8 / 9 / 10 and typed-array-view's NIO views (bulk host views; the contract has no bulk read
  — fio/col on a fast root is the candidate). eve.flat.sio: a FlatSio keeps c = (fio/chunk b), remade when a
  builder's array is replaced; read-u8 / read-u16 / read-i32 / cas's read through fio. (image.cljc's own
  read-i32-at over image bytes is outside this brief's list and untouched.)
- JVM flat suite: **72 tests, 11,530 assertions, 5F / 5E — the baseline's ten** (image-read-speed's ratio 1.44 this
  run, 1.90 at the baseline: noise on a loaded box; the bound is 1.2 either way).
- Reader conditionals: chunk.cljc **131 → 75** in all; **byte-level 58 → 2**. Byte-level means an arm that differs
  in how bytes are represented, read, written, copied, measured or text-coded. Before (Phase-0 line numbers): the
  byte layer (84, 90, 91, 115), the writer (174, 182, 215, 226, 257, 283), the 12 buf-* dispatches (338–349),
  host-bytes? (40), byte-len (74), MAGIC (361), scalar-body ×2 (365, 394), scalar-at (458, 461), utf8-bytes (640),
  the f32 and utf-8 column writes (1032, 1038), the type table's utf-8 (1259), the header magic (1308), the chunk
  copy and recipe patches (1323, 1330, 1338), pack's byte-array test (1358), the Ctx's DataView (1411, 1500), the
  type table's utf-8 / i64 reads (1444, 1446), kind 22's base (1527), the cljs TextDecoder/Encoder block (1556),
  the i64 / f32 / f64 / utf-8 cells (1615, 1617, 1620, 1625), bytes-range (1903), emit-magic! (1911), sha-vec's
  byte loops (1974), grown-buf (4843, 4844), finish-append (4864, 4866), the set key fallback's utf-8 (4936, 4940),
  column-view (5157). After, two, each with its reason: host-bytes? (which host values ARE byte arrays — the
  contract has no predicate) and ClojureScript's column-view (typed arrays over the chunk's ArrayBuffer — a host API;
  fio/col on U2b's js root is the candidate). The other 73: handle surfaces (protocol names per host) 30, host types
  (record / typed machinery, the scan's substrate, Ctx field hints) 15, exception classes 14, the number model (BigInt
  vs long, Float boxing) 8, ns form 4, the codec's flat-element name 1, eve.crypto's input type 1 (its arms now read
  through fio). column.cljc 15 → 15 (its :clj / :cljs arm structure; the reads inside are fio's), sio.cljc 17 → 16.
  The byte-level conditionals live where the contract puts them: flat_io.cljc has 28.

## 2026-11-17 01:45 AM ET — the done bar, measured at 915402b9dc

Provenance: branch flat-u2a at 915402b9dc (flat_io.cljc unchanged since 12295ecefc); JDK 21.0.12.1, bb 1.12.218,
Node 22.23.4, shadow-cljs 3.3.6; the box shared with two lanes (load 3.5–7.6 on 4 cores during these runs). Base
comparisons for the bb image suite and the extra Node suites ran on a `git archive 3b656fea20` export (scratch),
never a second worktree.

- **JVM flat suite** (15 ns, `clojure -M:jvm-test -n …`): before 72 t / 11,530 a / 5 F / 5 E → after (7e95edfcbf)
  72 / 11,530 / 5 / 5, the same ten (host-flat-suite, image-read-speed, requirements-covered, tally-count ×3,
  transfer ×4 OOM). Plus `-n eve.flat.chunk-test -n eve.flat.contract-test` at 915402b9dc: 12 t / 252 a / 0 F
  (the new lookup test among them).
- **bb flat suite** (each ns alone): every namespace's summary equal to the baseline's except two —
  chunk-test 8 t / 150 p / 3 F → 9 t / 216 p / 3 F (the new lookup test, green; the same three failures), and
  image-test 7 t / 65 p / 1 F / 7 E → 7 / 20 / 1 / 7. RECONCILED: image-test's pass count moves run to run on the
  BASE tree too (two base runs: 20 p and 35 p of 28 / 43 assertions; the errors are bb's — `ByteBufferAsIntBufferL.put
  not allowed`, Arrays.binarySearch reflection — the same 1 F / 7 E every run).
  CORRECTION (baseline entry above): bb chunk-test's three failures are three tests — chunk-equality-domain,
  chunk-unknown-type (a SciRecord's tag), chunk-roundtrip-values ({[1 2] :nested-key}) — not chunk-equality-domain ×3.
- **Node flat suite** (flat-x1-test): 4 t / 33 a / 5 F before and after, the failure text identical (diffed). Extra,
  base export vs after, identical failures: flat-parity-test 8 t / 27 a / 2 F / 1 E; flat-h4-test (the image on
  Node — FlatSio's reads) 4 t / 31 a / 3 F.
- **Conformance test**: JVM 11 t / 264 a / 0 F · bb 11 / 264 / 0 · Node 11 / 264 / 0.
- **Reader conditionals in chunk.cljc**: 131 → 75; byte-level 58 → 2 (the entry above lists both sets).
- **flatx, JVM** (doc/flat/lanes/flatx.clj, best-µs of 20 after a first run; 3 runs each):
  |                                                                                                                | before (3b656fea20, load ≈ 2.7) | after (915402b9dc, load ≈ 4.9) |
  | -------------------------------------------------------------------------------------------------------------- | ------------------------------- | ------------------------------ |
  | pack                                                                                                           | 6,884 / 6,198 / 9,571           | 5,843 / 6,663 / 5,438          |
  | get ×407                                                                                                       | 1,060 / 1,049 / 1,064           | 731 / 978 / 840                |
  | seq walk                                                                                                       | 132 / 114 / 121                 | 116 / 128 / 115                |
  | format                                                                                                         | 565 / 427 / 471                 | 555 / 584 / 414                |
  | `equal?` true in all six. The standalone probe stops at 21 calls a measure, before the JIT has settled on the  |                                 |                                |
  | search; interleaved in one JVM (scratch flatxab.clj, the Phase-0 chunk beside the seam's, 4 rounds, load 4.9), |                                 |                                |
  | round 3: **get ×407 933 → 258 µs**, pack 3,289 → 2,961, seq 38 → 40, format 288 → 290.                         |                                 |                                |
- **flatx, bb** (same probe, `bb -cp $(bb classpath bb)`; base on the export): pack 742,875 → 735,599 µs;
  **get ×407 86,265 → 23,670**; seq 851 → 848; format 16,082 → 16,446; `equal?` true.

## 2026-11-17 01:48 AM ET — for U2b and the collation

- **What the flat code calls** (the subset a fast root must answer for chunk.cljc / column.cljc / sio.cljc to run on
  it): chunk, chunk-bytes, chunk-len, u8, u16, u32, i32, i64, f32, f64, varint, varint-v, utf8, bytes-cmp, key-cmp,
  buf, buf-pos, buf-seek, buf-u8, buf-u16, buf-u32, buf-i64, buf-f64, buf-varint, buf-bytes, buf-off, buf-patch-u32,
  buf-align, buf-chunk. Not called by them yet: bytes=, varint-next, varint-len, buf-raw, tuple, col (the
  conformance test covers all).
- **What it relies on, per host.** JVM: f32 may answer a double — col-at makes the Float with `(float x)`. i64 a
  long. ClojureScript: i64 a js/BigInt (i64->host / i64-add do the number model); u32 a JS number; buf-u32 is given
  a JS number (BigInt cells are made numbers first), buf-i64 a BigInt or a number, buf-f64 a number. i32 must wrap
  (the conformance test pins FF FF FF FF → -1: the portable cljs arm was wrong at the base and is fixed here).
  key-cmp's n is the stored utf-8 byte count; bytes-cmp's bs is a host byte array (encode-key bytes, boff 2).
  buf-seek only moves back inside what is written. buf-chunk: chunk.cljc writes to a writer after taking one
  chunk of it (build-chunk patches the recipe slot, then takes the chunk again) and uses the first only before the
  patch, so a view is safe; a copy is what the portable root gives.
- **fio/chunk is on hot paths**: once per chunk opened, once per scalar written (scalar-body reads the codec's bytes
  through a chunk), once per digest on non-JVM hosts (byte-vec), per FlatSio growth and per column handle. On the js
  root a chunk that builds its DataView lazily keeps the writer's per-scalar cost down.
- **The IBuf method names changed** (wRaw, wPos, wSeek, wU8 … wPatchU32) in the portable root: nothing outside it
  names them.
- **Tuples, by host**: an f32 element is a Float on the JVM-class hosts; u64 past 2^63 reads two's complement there
  and exactly (BigUint64) on ClojureScript — the conformance test pins it per host; i64 / u64 elements on
  ClojureScript are BigInts.
- **Wiring**: hosts.edn's every row ends with adapters/flat-io/portable/src; a TODO(U2b) comment at the top says
  where flat-io/jvm and flat-io/js go (before portable on :jvm / :jolt and :cljs / :browser / :nbb / :scittle /
  :cherry); deps.edn's :paths and :node-test carry TODO(U2b) comments (:browser-test mirrors :node-test), and
  bb.edn's :paths mirrors hosts.edn's :bb row, which stays portable (U2b's brief: bb is the portable host).
  The roots' stubs (adapters/flat-io/{jvm,js}) are untouched here, so a merge with U2b's branch conflicts only if
  U2b also edits hosts.edn / deps.edn / bb.edn / adapters/README.md / doc/adopter-tiers.md / the conformance test
  file (Phase-0 stub) — take U2a's versions and add the fast roots.
- **Testing a fast root with the conformance test**: put its root on the runner's classpath before portable
  (JVM: `clojure -Sdeps '{:aliases {:j {:extra-paths ["adapters/flat-io/jvm/src"]}}}' -M:j:flat-io-test` — a .clj
  beats the portable .cljc on the JVM wherever it sits); the Node build: add the js root to :node-test (or an alias)
  and drop portable from that classpath, since how shadow resolves a .cljs beside a .cljc of one ns is unmeasured.
- **Stale lists noticed, not changed** (not this lane's files): test/eve/flat/host_suite.cljc's bb exclusion of
  chunk-test cites a Murmur3 import chunk.cljc no longer has (chunk-test loads and runs on bb: 9 t, 3 F, all three
  pre-existing); doc/adopting-eve-minimal.md still says "nine" inbuilt roots (adopter-tiers §3 now names the tenth).
