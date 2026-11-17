# Lane F3FIX — the three F3 seams · STATUS

Branch `claude/flat-f3fix` off the assembled tree (`claude/flat-rename`
@ 6124bb3). Source of the claims: the F3 measurement lane's
F3-MEASURE.md, measured at the collated pin by composing shipped
pieces no suite had composed. Each claim was re-derived at this pin
before any fix (probe `eve.flat.f3fix-probe`, log
`~/static-eve-results/f3fix-probe1.log` on J, HEAD 9204621 in-log);
acceptance is the same probe re-run on the fixed tree
(`f3fix-probe2.log`, HEAD cfee774) plus the affected suites
(`f3fix-suites3.log`, HEAD aa876f6 — content-identical to cfee774;
`f3fix-place2.log`, HEAD ba6f94d). Commits are per-defect, named
below, so the release rebase replays them independently.

## Claim 1 — builder→place drops relocations · CONFIRMED, FIXED

Commit `3328ce7`.

**Re-derivation.** `build/build-image` of three pointer-bearing
values emits its relocation table under `:relocations` (counts
22 / 608 / 353) and nothing under `:relocs`. Composed through the
shipped `place/place` into a fresh atom: the small nested map read
back **unequal** (placed with zero relocations applied — silent
wrong data, not an error); records x200 and vec-of-maps x50 died in
the installing swap with `Unknown EVE type tag`. The F3 claim holds
at this pin in both its failure shapes.

**Convicted side: the builder.** The canonical key is `:relocs`:
place's documented image-map contract names it, `transfer` emits
it, place's bytes door (`chunk->image`, via the contract's
relocation-table reader) produces it, place-test's hand images use
it. `eve.flat.build` was the lone `:relocations` producer, and its
only consumers were its own test/bench stand-ins — which is why
each side's suite passed while the composition failed.

**Fix.** `image-tables` and `image` in build.cljc emit `:relocs`
(docstrings included); the stand-ins in build_test.cljc and
b1_bench.clj consume `:relocs`. Composed-path test:
`build-image-places-through-the-shipped-door` in place_test.cljc —
builder images (nested map, records x200, tagged maps x50) placed
through the shipped door read back equal, with the `:relocs` table
asserted non-empty.

**Acceptance.** Probe arms post-fix: all three read back `true`.
place-test 8 tests / 105 assertions green (see the repair note
below); build-test 354 assertions green.

## Claim 2 — chunk/open-checked refuses the writer's own chunks · CONFIRMED, FIXED

Commit `9cf567c`.

**Re-derivation.** Every writer byte array probed — single-pack and
pack-all, records and nested values — was refused by
`chunk/open-checked` with `:eve.flat.chunk/bad-offset` ("a table
offset out of range"), while `chunk/open` decoded the same bytes to
exactly the source values and `eve.flat.check/open-checked`
vouched for them (`true`). The two checked doors disagreed about
the same bytes, as claimed.

**Convicted side: the chunk's own header door.** `header-ctx`'s
checked clause demanded every table offset (and the root) be
8-aligned. The writer guarantees no such thing: the tables are laid
out contiguously after the 32-byte header, so the shape table lands
wherever the symbol table's bytes end. FORMAT-V1 §10 arbitrates,
in the prose Check's lane already corrected against the producers:
table offsets are "range-checked only — table offsets are NOT
aligned: §2–§5 lay the tables out contiguously, and real chunks put
the shape table at 34 and 39". Not a fifth prose conflict — the
prose was right; the header door was the defect. (The chunk's own
8-alignment *in its container* and its length being a multiple of
8 are separate properties, and the writer does honor those.)

**Fix.** header-ctx's checked clause range-checks only
(`0 < off < length`); refusal type and message unchanged.
`open` (the trusted door) is untouched. Composed-path test:
`writer-bytes-pass-open-checked` in chunk_test.clj — single-pack
and pack-all bytes accepted and decode equal; the door still
refuses short, bad-magic, and truncated bytes by name.

**Acceptance.** Probe arms post-fix: every chunk `:accepted`,
decodes equal, check door still vouching. chunk-test 9 tests /
182 assertions green; check-test 73 and io-test 39 green (io
composes writer + eve.flat.check and is unaffected).

## Claim 3 — pack-all is superlinear · CONFIRMED, FIXED

Commit `9ad1f33`.

**Re-derivation.** The shipped seal test encodes the entire
accumulated candidate on every row. Series at this pin (single
timed calls, same instrument and arm order as the acceptance run):
250 rows 1,438 ms · 500 → 4,736 · 1,000 → 21,446 · 2,000 → 51,410 —
superlinear throughout, on the trajectory of the F3 lane's
662,564 ms at 10k rows.

**Fix.** The seal test stays exact — a candidate prefix is encoded
in full and its length measured — but the longest fitting prefix
is found by binary search over prefix lengths: O(log n) full
encodes per chunk instead of one per row. Two findings shaped the
implementation:

- Encoded length is **not** non-decreasing in prefix length. The
  measured prefix series for records (1..40) is monotone except a
  single step **down** at prefix 16 (440 → 424 bytes): the
  vec-of-nodes → batch representation switch. The search therefore
  only ever emits a prefix it measured fitting (or the lone
  oversize row, the documented wide-chunk case), so the 65,535 cap
  holds regardless of the dip; a search can in principle stop a
  row or two short of the longest fitting prefix, and the
  docstring says so.
- On the corpus that exercises sealing hardest (2,000 payload rows,
  7 chunks), the search's output is **byte-identical** to the
  pre-fix algorithm's — same chunk count, same lengths
  [65376 65488 65488 65392 65416 65416 8888], same bytes. The dip
  sits at prefix 16, far from any seal boundary at cap scale.

**Acceptance** (same probe, fixed tree, single timed calls):

| records rows | pre-fix | post-fix |
|---|---|---|
| 250 | 1,438 ms | 89 ms |
| 500 | 4,736 ms | 108 ms |
| 1,000 | 21,446 ms | 120 ms |
| 2,000 | 51,410 ms | 254 ms |
| 4,000 | — | 2,825 ms (2 chunks) |
| 10,000 | 662,564 ms (F3 pin) | 8,720 ms (4 chunks) |

Matched-size speedups ×16 / ×44 / ×179 / ×202. The residual growth
past one chunk is the search's log factor times the per-encode
linear cost (each probe is a full encode of a ≤64 KiB candidate);
no model is fitted. Every post-fix arm round-trips equal and no
chunk passes 65,535 (max 65,512). chunk-fill-policy's cap and
round-trip assertions pass unchanged inside chunk-test.

## Repair note (this lane's own defect, caught by count reconciliation)

The first suites run showed place-test at **7 tests / 73
assertions** against the expected 8 / 105. Reconciled: the
composed deftest's insertion landed inside the preceding
`#?(:clj …)` form, demoting `place-refuses-bad-tables` (Z3.2,
32 assertions) to an unmatched reader-conditional key — it
silently stopped running while the suite stayed green. Repaired
in commit `ba6f94d` (each deftest again its own `#?(:clj …)`
form; structure verified by an independent s-expression parse);
place-test re-run: **8 tests / 105 assertions, 0 failures,
0 errors**. The other suites' counts reconciled exactly
(build 354 = base, check 73 = base, io 39 = base, chunk 182 =
153 base + 29 composed).

## Suites at the declared tip (J, HEAD-verified in-log)

build-test 354 · place-test 105 · chunk-test 182 · check-test 73 ·
io-test 39 assertions — all 0 failures, 0 errors.

## Filing lessons recorded

The job router holds any filing whose `needs` is not a non-empty
string. The Cognitect runner's form in this tree is
`clojure -M:jvm-test -n <ns>` (the alias carries `:main-opts`);
two filings died on `-X` variants before the proven form ran.
