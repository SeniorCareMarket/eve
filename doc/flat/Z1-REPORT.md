# Z1 report — the image: a static slab I/O and `detach`

Lane Z1, branch `claude/static-z1-image`, SeniorCareMarket/eve.
Base `claude/sweet-carson-6pt29d` @ 7402ef9. JVM only (Wave 1).
Suite evidence at 80a3d6f (J, HEAD-guard verified in-log); commits after
80a3d6f touch only the diag2 script and docs. Board row: B3.

## What the lane built

- `eve.static.sio` — one slab I/O over one byte array, two modes: a
  **builder** (alloc bumps the tail and records each block's requested
  size; free is a no-op) and a **view** (frozen: every mutator — alloc!,
  free!, write-*, cas!, copy-block! — throws a typed ex-info,
  `:eve.static.sio/frozen-view`).
- `eve.static.image` — `detach` walks a value with a pointer enumeration
  mirroring `eve.retire/children` kind for kind, but returning byte
  positions; the answer is one FORMAT-V1 chunk: 32-byte header, blocks
  4-aligned root-first, block table, relocation table. `open-image` /
  `image-value` read the value back in place through the frozen view.
- `eve.static/detach` (the frozen public verb) delegates to this lane.

## Requirement verdicts (suite round 6, J, tip 80a3d6f)

image-test: 7 tests, 208 assertions, **2 failures, 0 errors** — both
failures are Z1.7 (below). requirements-test: 2 tests, 510 assertions,
**0 failures, 1 error** — the error is the W0-owned api-surface item
(`(detach nil)` throws the protocol IllegalArgumentException from
`-root-header-off` before lane code runs; the frozen probe accepts only
a return or `:eve.static/not-built`, and detach IS built — collation
owns the contract amendment; the same wall C1 documented).

- **Z1.1 view-refuses-every-mutator — PASS.** Every mutator on a frozen
  view throws the typed refusal.
- **Z1.2 image-reads-every-type — PASS.** Every corpus shape reads back
  in place equal to its slab original, including the 200-element list
  (via the merged L1 fix, below) and a DataScript db (datoms + a Datalog
  query over the image). The DataScript row SKIPS in the scoped J run —
  datascript.core is not on that classpath; the row's local evidence is
  in the lane's earlier scoped runs.
- **Z1.3 detach-tables-complete — PASS.** The image's (owner, target)
  multiset equals `eve.retire/children`'s on generated values; detach
  copies every reachable block (probes: 11/11, 54/54, 108/108 with
  exactly 10 edges onto the shared block, 1377/1377).
- **Z1.4 detach-under-concurrent-writes — PASS.**
- **Z1.5 views-survive-appends — PASS.** Appends past a view's length
  preserve the view's reads.
- **Z1.6 detach-compacts — PASS.** The image carries reachable blocks
  only.
- **Z1.7 image-read-speed — MISSED, mechanism named (next section).**
  Stays `:planned` in the manifest.

## The walk defects (all re-derived at their pins, all fixed)

1. **hash-of intCast overflow** (a3a0d20) — `(int 0x9E3779B1)` throws on
   every call (the literal is a Long past Integer/MAX_VALUE), so no
   detach ever completed a value at the first pin. One defect, three
   sightings (this lane, Transfer's STM cell map, Sister's log). Fixed:
   Fibonacci hashing in long arithmetic, one `unchecked-int` wrap.
2. **HAMT inline-pointer double-base** — the walk's scan positions are
   absolute in the image buffer; the HAMT data-entry sites passed them
   to block-relative consumers, so every map/set value pointer was
   missed: detach copied 2 of 24 reachable blocks. The 44
   detach-tables-complete failures at round 2 were this one defect.
3. **Unbounded content-size over an estimated copy window** — heap-block
   class sizes are unrelated to content; the scan read garbage past the
   copy (a 68 MB arraycopy; a scan off the 64 KiB buffer). Sizes are now
   window-bounded; detach doubles the window until the scan fits.
4. **StaticSio state access** — the deftype's mutable fields compile
   package-private in this toolchain; `.-b`/`.-t`/`.-na` all threw.
   State now goes through the `IStaticSioState` protocol.
5. **Set bitmap-node size used the map layout** — set nodes carry no
   per-entry hash array (values at 12+4nc, not 12+4nc+4nd); the scan
   started 4·nd into the values and walked garbage.
6. **Unsigned root offsets** (a78dac5) — a root offset delivered
   unsigned (an eve array's 0xC0000000) threw intCast in the ordinal
   table, which keyed on `(int off)`; child edges arrive sign-extended,
   so only roots hit it. Keys are now the offset's low 32 bits,
   bit-preserved, everywhere.

Test-side defects fixed on the way: a missing closer that made the
suite unloadable at the first pin; the sorted-map row calling
`clojure.core/subseq` (throws identically on the slab original —
EveSortedMap is not clojure.lang.Sorted; the row now uses
`eve.sorted/subseq`); the determinism assertions comparing byte arrays
with `clojure.core/=` (reference identity — no correct detach could
pass; measured byte-identical, now Arrays/equals).

## The L1 dependency

Z1.2's list row was red from the lane's first entry for a named reason:
`EveList.next`/`more` minted a fresh 12-byte header per step via
`make-list`, so walking a list through a frozen view threw on alloc!.
That was lane L1's fix (seq/next/more answer a lazy host seq, no
make-list on read paths). L1 completed and was merged here by path
remote (b467c61, clean). Round 6 confirms it in this tree: the
list-of-200 rows pass. (Round 5's apparent non-fix was a stale bundle —
see STATUS, finisher #5 entry: its stack traces cite the pre-merge
line numbers exactly.)

## B3 — the board row, as measured (gate MISSED on every corpus)

Per-corpus legs on J (JVM 21.0.9, min of 5, at 017ddad), today (@a)
vs the static arm (detach):

| corpus             | @a       | detach       | ratio |
| ------------------ | -------- | ------------ | ----- |
| map 10k int->int   | 16.06 ms | 1,830.73 ms  | 0.01x |
| vector 100k ints   | 51.02 ms | 37,375.29 ms | 0.00x |
| vector 10k records | 71.97 ms | 19,770.32 ms | 0.00x |
| 5k chat messages   | 95.64 ms | 45,070.88 ms | 0.00x |

Gate: detach ≥2× faster than @a on every corpus. **Missed by
114–733×.** Detach as built is a correctness mechanism; the walk's
per-block slab reads are the cost. Banked in doc/static/SPIKE-LOG.md.

## Z1.7 — image reads vs slab reads: MISSED, mechanism named

Suite round 6 (tip 80a3d6f): map 10k — slab 30.58 ms, image
2,151.22 ms, **70.36×**; vector 100k — slab 11.43 ms, image
323.73 ms, **28.31×**. Gate: within 1.2×. Missed.

The mechanism is Eve core read machinery, not this lane's files —
established by the layered diag2 bisect (J, one session, min of 5
after 2 warmups, tip 3d22d90; z1-diag2c.log):

| layer                                    | slab      | view/image  | reading                        |
| ---------------------------------------- | --------- | ----------- | ------------------------------ |
| G0 full get ×50k                         | 24.73 ms  | 1,536.70 ms | 62× end to end                 |
| G1 key bytes+hash ×50k                   | 3.01 ms   | (shared)    | prep is innocent               |
| G2 hamt-get-words ×50k, precomputed keys | 16.02 ms  | 1,112.21 ms | the descent itself, 69× by sio |
| G3 hamt-get-view on slab sio ×50k        | 9.98 ms   | —           | the production descent         |
| G4 node-i32 ×200k, varied offsets        | —         | 2.92 ms     | ~15 ns/read through the view   |
| G5 -sio-read-i32 ×200k, same offsets     | —         | 2.57 ms     | ~13 ns/read; G4≈G5             |
| G6 node-bytes ×50k                       | 209.64 ms | 670.36 ms   | byte copies 3.2× via the view  |
| G7 full vec nth ×50k                     | 20.69 ms  | 348.40 ms   | 17×, same class                |

Reading of the layers: Eve's HAMT get has two descents. A JvmSlabCtx
(or a wrapper over one) resolves each node **once** via
`alloc/node-view` and plain-loads its words (G3). Every other sio
walks `hamt-get-words` (eve.map:472): per-word node-* calls, kv-pos's
per-entry skip walk, and byte-copying key compares
(`node-bytes-equal?`'s fallback copies the key bytes out per compare).
diag2 shows no single view op is slow — word reads through the view
at varied real offsets run at parity class (G4/G5) — and key prep is
3 ms of a 1,537 ms arm. The price is the fallback's per-get call/copy
structure: ~22 µs per descent through the view against ~0.3 µs for
the same code on a slab sio (G2), because on the slab sio the node-*
ops resolve to direct region loads while on a foreign sio every op
takes the generic protocol path and the compares copy bytes.

There is **no view-side fix in this lane's files**: the fast path's
gate is a concrete class test — `alloc/node-view` (alloc.cljc:1290)
resolves only `instance? JvmSlabCtx` (or a wrapper whose `node-ctx*`
reaches one) and answers nil for a foreign sio; `eve.map/hamt-get`
dispatches on exactly that. A StaticSio is foreign by construction
and no protocol hook exists for it to implement. The fix direction,
for Eve core / collation: extend node resolution to foreign sios (an
INodeView-style hook a byte-array sio can answer — the view's
backing array + base is precisely the [arr region base] shape
node-view returns). Z1's own read op is a direct array read
(sio.cljc:90); nothing in sio.cljc or image.cljc is the bottleneck,
and nothing there was patched around this.

## Process corrections banked in this lane

- Round 5 + diag2 round 1 ran a **stale bundle** (pre-L1 line numbers,
  pre-fix assertion forms, the diag2 namespace absent — it was added
  in the tip commit itself). Both rounds' reds are void as evidence
  about 80a3d6f; round 6 ran under an in-script HEAD guard after a
  clone-verification of the restaged bundle on J.
- The round-5 `&&` chain suppressed requirements-test entirely; round
  6 filed the two namespaces as separate legs.
