# H8 — writer/reader API (IO) — report

Lane H8 of IMPL-PLAN §3.4 (Wave 2). Base: Chunk tip abbd260 (C1 + W0).
Owns: `src/eve/static/io.cljc` (new), `test/eve/static/io_test.clj` (new).
Merged by path remote: `src/eve/static/check.cljc` from lane C4
(339dbe3) — the reader's checked door, per C4's contract.

## What was built

The Transit-shaped stream surface for the packed form:

- **Writer** (`writer`, `write!`, `flush!`, `close!`, `file-writer`,
  `write-bytes`): each value is emitted as its packed chunk
  (eve.static.chunk), framed by a 4-byte big-endian length, and
  flushed per write — emission is incremental; a reader in another
  process sees values as they are written. A value is packed as the
  sole element of a vector, so scalars (never static values on their
  own, FORMAT-V1 §2) travel in the same frame shape as collections
  and records. The frame is internal: write! takes a value, read!
  answers one. Writers need no handlers — a record's type key and
  layout fingerprint go on the wire by construction (C1).
- **Reader** (`reader`, `read!`, `read-all`, `file-reader`,
  `read-bytes`): the ONLY door for stream bytes is
  `eve.static.check/open-checked` — every frame's bytes are vouched
  as a CheckedBytes handle before `chunk/open` sees them. There is
  no unchecked read. Refusals are typed ex-infos: check's own
  (ex-data `:check`, e.g. `:bad-magic`) for ill-formed chunks, and
  this namespace's (`:eve.static.io/check`) for framing faults —
  `:truncated-frame`, `:bad-frame`, `:writer-closed`,
  `:reader-closed`. Past the last value, read! answers the reader's
  eof value (`eve.static.io/eof` by default).
- **Handlers**, read-side, keyed by the wire type key (FORMAT-V1 §6,
  the `"ns/Type"` string): a value whose root is a record with a
  handler in `:handlers` is answered by that handler, called with
  the record handle. Else a `:default` handler is called with
  `[key handle]`. Else the record answers as the generic record
  itself (H8.2) — fields readable as a map, `record-tag` its key.
  Non-record values answer exactly as `chunk/open` answers them:
  statics in place, scalars themselves. Dispatch is by key whether
  or not the reader has a registered sister for the type.
- **Hosts**: JVM streams in this version. The ClojureScript
  constructors throw the typed not-built refusal (the chunk itself
  is host-identical — lane X1; a byte-source CLJS reader is H3/H4
  territory). Writer/Reader state leaves the deftypes only through
  protocol methods implemented inside them — this toolchain compiles
  deftype fields package-private, so external field access throws
  (Z1's StaticSio wall, Z1's fix pattern; hit and fixed in this lane,
  e147c5e).

## Requirements — evidence

Suite: `clojure -M:jvm-test -n eve.static.io-test` in the lane's own
run — **3 tests, 39 assertions, 0 failures, 0 errors** (hatch, load
low; the J confirmation leg is filed as `h8-suite.log`, running at
this branch's tip — its HEAD line governs).

| Req | Verdict | Evidence |
|---|---|---|
| H8.1 `writer-reader-handlers` | green | A 9-value stream (scalar, string, vector, map, two records of different types, a map nesting a record, nil, a set) round-trips in order; the Widget and Gadget handlers — keyed by their §6 keys `"eve.static.io_test/Widget"` / `"eve.static.io_test/Gadget"` — fire only for the two top-level records, in stream order; the nested record needs no handler (materializes by fields). Value-at-a-time write!/read! over raw streams; eof answered repeatedly. Checked door proven in the same test: the intact first value reads, the frame with a corrupted chunk magic refuses with check's `:bad-magic`; a stream cut mid-frame refuses typed. |
| H8.2 `reader-default` | green | No handlers: both records read as generic records (`generic-record?` true, `record-tag` the wire key, fields via map access). A handler for one key leaves the other to the default. A custom `:default` is called with `[key handle]` for both. |
| H8.3 `file-roundtrip` | green | Values written to a file by `file-writer`; read back by a fresh `file-reader` in-process (record as generic record, fields intact); then read by a **second JVM** spawned by the test (ProcessBuilder, same classpath, `eve.static.io/file-reader` + `read-all` + `chunk/unpack`), which prints the same values — `[42 {:a 1 :b [1 2 3]} {:name "file-widget" :size 5} "tail"]` — and exits 0. |

`requirements-covered` at this pin: passes for H8's ids; the
requirements run's one error is the known W0 api-surface nil-probe
item (chunk.cljc), attributed, not this lane's.

## One test-side correction, banked

The first checked-door draft flipped a byte inside a value body and
expected a refusal; the read succeeded with a different well-formed
value. That is correct checker behaviour — open-checked vouches
*form*; integrity is the digest flag's door, and pack does not set
it. The test now corrupts the frame's chunk magic (deterministic
ill-formedness) and asserts `:bad-magic`. Recorded here so the
distinction is not lost: **a static stream as packed today is
tamper-evident only where the digest flag is set** — a writer option
to set it is a collation/follow-up item, not built here.

## Not built / limits

- No batching across values (one chunk per value): packing several
  values per chunk is `chunk/pack-all`'s and the container's (C3)
  shape; the stream API is per-value by design, Transit-shaped.
- No writer-side handlers: none are needed — the type key is on the
  wire by construction. A future "write this record under another
  key" would be a new requirement, not a gap in this one.
- CLJS streams: not built (typed refusal), above.
