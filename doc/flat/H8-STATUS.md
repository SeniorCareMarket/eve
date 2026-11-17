# H8 — writer/reader API (IO) — status

Branch `claude/static-h8-io`, base: Chunk tip abbd260 (carries C1 + W0).
Requirements H8.1 `writer-reader-handlers`, H8.2 `reader-default`,
H8.3 `file-roundtrip` — registered in requirements.edn, all `:planned`.

Design (Transit-shaped, per IMPL-PLAN §3.4 + the lane brief):
- `src/eve/static/io.cljc`: a writer emits each value as its packed
  chunk (chunk/pack of the value framed as a 1-vector, so scalars
  travel too), framed by a 4-byte big-endian length; write! flushes —
  emission is incremental, one chunk per value.
- The reader's ONLY door for stream bytes is
  `eve.static.check/open-checked`: every frame's bytes are vouched
  (CheckedBytes) before chunk/open sees them. No unchecked read.
  (check.cljc merged from lane C4 by path remote — its contract.)
- Read-side dispatch by wire type key (§6): a record whose key has a
  handler is answered by the handler; otherwise the :default handler
  if given, else the generic record itself (H8.2). Non-record values
  answer as chunk/open answers them (statics in place, scalars plain).

## Log
- Tests first: test/eve/static/io_test.clj committed red (io + check
  namespaces absent at this pin in this tree).
- Bundle: h8.bundle creation needed (coordinator stages to J) when the
  suite leg is filed.

## Close-out
- Suite green in the lane's own run: 3 tests, 39 assertions, 0/0
  (io-test; includes a real second-JVM file read). H8.1–H8.3 flipped
  :built. Report: doc/static/H8-REPORT.md.
- Two in-lane fixes en route: deftype state via in-type protocols
  (toolchain package-private fields — Z1's wall); the checked-door
  test made deterministic (form vs integrity — see the report).
- J confirmation leg filed (h8-suite.log) at the close-out tip;
  bundle h8.bundle created locally for the coordinator to stage.
