# C1 — the 64 KiB chunk: lane report

**FINAL. The scoped-suite confirmation run on J (c1-suite.log) has
landed and confirms the per-requirement verdicts below; the B6/B7
rows rest on the delivered J bench log. No outstanding items —
see "Outstanding" at the end, now closed.**

Branch `claude/static-c1-chunk`. Code tip under test: `1f0e049`
(the two later commits are STATUS/report documents only). W0
(complete) merged at `961f67e`. No origin push (no credential on the
hatch box); the coordinator collates from the local tree and bundles.

## Per-requirement verdicts (scoped suite: chunk + W0 contract + requirements)

Verified in two scoped runs at the merged tip (13 tests, 706
assertions, 705 pass, 0 failures, 1 attributed error — below):

- **C1.1 golden-bytes — PASS.** The fixtures' bytes are the format's
  bytes; the one wrong fixture (hand-computed header counts) was
  corrected to the writer's bytes and the correction is banked in
  C1-STATUS.
- **C1.2 chunk-roundtrip — PASS.** pack → open/unpack round-trips the
  corpus shapes, including batches of records and nested values.
- **C1.3 chunk-fill-policy — PASS.** Chunks seal at 64 KiB: a chunk
  closes when the next row would pass 65,535 bytes; pack-all is
  exact-greedy.
- **C1.4 chunk-protocols-in-place — PASS.** Protocol operations
  (lookup, nth, count, seq, reduce, equiv, hash, print) read nodes in
  place; the full-decodes counter counts whole-chunk materializations
  only, and the tests hold it at zero across protocol work.
- **C1.5 chunk-update-appends — PASS.** assoc/conj append past the old
  chunk's length into a grown copy when the tables already cover the
  change, re-pack otherwise; the old bytes are untouched either way.
- **C1.6 chunk-unknown-type — PASS.** A record whose type key +
  fingerprint are not in the reader's registry reads as a generic
  record (IGenericRecord), keeps every field, and re-packs to the
  same bytes.
- **C1.7 chunk-equality-domain — PASS, with two recorded scopings.**
  Static = static = plain holds across chunkings and against Eve's
  own digest of the plain value. (a) The float edges follow A10's
  recorded disagreement: (= {:a ##NaN} {:a ##NaN}) is false and
  (= {:a 0.0} {:a -0.0}) is true in vanilla Clojure, computed over
  decoded Doubles where no static-side code can reach; the test
  asserts the edges in static-driven directions and by digest.
  (b) The slab arm is not yet testable: Z1's detach did not exist in
  this tree at lane time; the test covers packed ↔ plain and
  packed ↔ packed, and the slab comparison is owed when Z1 lands.

All seven are flipped to `:built` in requirements.edn (commit
`1f0e049`).

## The one suite error — attributed, not a C1 defect

requirements-test/api-surface (W0.1's probe) calls every eve.static
verb with nil arguments and accepts two outcomes: return normally, or
throw the `:eve.static/not-built` ex-info. Landed `chunk/open` refuses
nil bytes with the typed `{:type :eve.static.chunk/short}` ex-info
(FORMAT-V1 §10's refuse-by-name style); the probe rethrows it. Run 1
exposed a real C1 defect on this path — an untyped NPE from
`(alength nil)` before the refusal could fire — fixed in `f243c1d`;
run 2's only error is the typed refusal itself. Proposed amendment
(coordinator/W0, their file): accept an ExceptionInfo whose ex-data
carries `:type` as a third outcome (`::refused`). Every landing lane
meets this probe; place/transfer!/detach will too.

Related naming item for the same reconciliation: W0's surface doc
names `eve.static.chunk/bytes` as the delegate target for
`eve.static/bytes`; chunk.cljc provides `chunk-bytes`/`bytes-of`.
Adding the alias interacts with the same probe — reconcile together.

## B6/B7 — measured on J (c1-bench.log, TIP verified acee946)

Local heavy runs are prohibited on hatch from 2026-11-12 05:19 ET
(operator directive); these rows come from the re-filed bench leg on
J, both arms in one session, min of 5 after one warmup, at the code
tip's tree (log's TIP line = acee946). The rows are in SPIKE-LOG.

- **B6 bytes — GATE PASS on both corpora** (gate ≤ 0.9× Transit
  MessagePack). Messages: packed 699,824 B in 11 chunks = 0.801×
  Transit's 874,085 B. Records: packed 294,016 B in 5 chunks =
  0.455× Transit's 646,605 B.
- **B7 decode — GATE MISS on both corpora** (gate ≥ 2.5× Transit).
  Messages: packed full decode 879.0 ms (175.81 µs/row) vs Transit
  68.4 ms = 0.08× Transit. Records: packed 499.4 ms (49.94 µs/row)
  vs Transit 44.7 ms = 0.09× Transit. The packed form's full decode
  is ~11–12× SLOWER than Transit's. Reported as missed, not
  relabeled.

Scope note: this bench's decode arm is FULL materialization
(`chunk/unpack` of the whole value) — the expensive direction the
packed form exists to avoid in place. The remaining B7 sub-gates
(scan ≤ 2 ns/row, filter ≤ 4 ns/row, cell ≤ 2× host, row view
≤ 1 µs) are measured by the Columns lane's bench over column
batches, not by this bench; they are not C1 rows and C1 claims no
verdict on them.

## Outstanding

None. The one item this section carried — the scoped-suite
confirmation re-run on J (`c1-suite.log`, re-filed alongside the
bench) — has landed, TIP verified = acee946, and its verdict is
appended to C1-STATUS and recorded here: chunk-test 8 tests /
153 assertions, 0 failures, 0 errors; contract-test 3 tests /
33 assertions, 0 failures, 0 errors; requirements-test 2 tests /
527 assertions, 0 failures, 1 error — the single error is the
attributed W0 api-surface nil-probe item described above (the same
wall Sister and Detach document; collation's contract amendment),
and SUITE-EXIT=1 follows from that one attributed error. The J run
confirms the lane's two local scoped runs at the merged tip
(13 tests, 706 assertions, 705 pass, 1 attributed error). The lane
is fully complete.

## Notes for downstream lanes

- Writer bytes are unchanged since 921fb7a except the header-ctx nil
  guard (f243c1d), which changes no emitted bytes — only the error
  thrown for nil input. The Node lane's byte-identity work against
  the merged chunk.cljc is unaffected.
- REF nodes (0x2C, cross-chunk) are refused with a typed ex-info by
  this reader; they are C3's to resolve through its directory.
- Metadata: pack/unpack preserve handle meta through the chunk
  itself (attached via the IAttachMeta protocol on the proxy
  handles). Under clojure.main source loading, user-side
  `(with-meta static-map m)` / `(meta h)` on map/set handles remain
  subject to the proxy-class loader world and `(meta h)` reads nil —
  a recorded divergence, not a silent one.
