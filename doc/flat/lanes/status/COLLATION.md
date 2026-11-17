# Wave-1 collation (coordinator race-nk), 2026-11-17 1:49 AM ET

Merged flat-u2a (12295ecefc … 9c3a73f457) and flat-u2b (fc32271c8b … 84ce7d2785) into claude/nice-keller-r7n2fz, no
conflicts; wired the fast roots (hosts.edn :jvm / :cljs / :browser, deps.edn :paths / :node-test / :browser-test, each
before flat-io/portable; every other row portable alone); the portable writer's bb arm writes with aset-byte (U2b's
finding). Commit ab84f8152c. Re-derived here, on the merged tree:

| host | root that loaded (checked) | tests | result |
|---|---|---|---|
| JVM | eve/flat_io.clj — the fast root (resolve's :file) | conformance + U2b fast test; chunk-test + contract-test | 23 tests / 63,153 assertions, 0 fail; 12 / 252, 0 fail |
| bb 1.12.218 | portable (hosts.edn :bb) | bb test:flat-io | 11 tests / 264 assertions, 0 fail |
| Node 22 | eve/flat_io.cljs — the fast root (the compiled eve.flat_io.js's source map; shadow-cljs takes the .cljs over the .cljc) | flat-io-test build | 11 tests / 264 assertions, 0 fail |

bb flatx after the aset-byte writer (bench/flat-io/run-bench bb flatx, best of 3 rounds, load1 2.3–2.7): fc-pack
377,520 µs (U2b measured 789,959 with aset), get ×407 20,846, equal? true, get [-5 5000 55 12] on both maps.
Open (named, not done here): flatx's end-to-end time on the JVM and Node is in digesting and chunk.cljc's own
reflection, not the byte layer (U2b's profile) — the next eve target; glojure / jank / jolt / ClojureCLR / basilisp /
clojerl arms moved verbatim but unrun (not installed here).

# Wave-2 collation: U4a (eve's sisters over eve.flat-io), 2026-11-17 10:00 AM ET

Merged flat-u4a (8419b411fd … 0d926732ca) into claude/nice-keller-r7n2fz (3d0dd2c232), no conflicts, no conflict markers.
Re-derived on the merged tree (JDK 21, Clojure 1.12.0, bb 1.12.218, Node 22 + shadow-cljs; fresh processes, the Node
build from a clean target/flat-sister-test):

| host | command | result |
|---|---|---|
| JVM | `clojure -M:jvm-test -n eve.flat.sister-test` | 6 tests / 45 assertions, 0 F, 0 E |
| bb | `bb test:flat-sister` | 6 / 37, 0 F, **1 E** at the merge (boundary-conversion: transfer! → stream.cljc:69) → after 59217afaca **6 / 45, 0 F, 0 E** |
| Node | `clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-sister-test && node target/flat-sister-test/main.js` | 4 / 30, 0 F, 0 E (H5.5 atom boundary and H5.6 classpath are JVM-class only) |

The bb error, fixed at the producer (59217afaca): stream.cljc (two sites) and transfer.cljc read JvmSlabCtx's field as
`(.-block_sizes ^JvmSlabCtx root)`; babashka refuses a munged field name on a sci type ("Method block_sizes on class
sci.impl.deftype.SciType not allowed!" — measured in a bb one-liner), and accepts `(.-block-sizes ^JvmSlabCtx x)`, which
the JVM also reads with no reflection warning (measured with *warn-on-reflection*). bb's sister suite now runs the JVM's
45 assertions.

Open, named (U4a's report, item 2 — a FORMAT decision, not taken at collation): a map whose values are records is
written as a MAP node of record nodes, so a map value's field read is a node-slot lookup; a vector of records is a
BATCH and reads at column[row]. Writing the map's values as a struct column (FORMAT-V1 §6, kind 21) changes chunk bytes
on every host and VS reads eve chunks as they lie (race-nk R7+), so it is a cross-repo format change: VS's U4b wrote its
record rows as an i64 tuple VEC + a type table — RE-DERIVED at collation (10:13 AM ET): eve's JVM reader at this tip,
fc/open-checked over valuescript flat/u4b/rec-v500k.chunk (22,008 B, type table key 'u4bprobe/Stats'), reads count 407,
= its persistent copy, every get equal, 1BRC answer hash 9259dd6c5f124c92 = cfast's (xp/vsfread.clj); the rows read as
vectors (fingerprint 0, no field entries), as U4b said.

# Release: claude/nice-keller-r7n2fz merged into release/eve-0.9.3.2 (operator's instruction, 2026-11-17 10:48 AM ET)
Operator: "Get your changes pushed to the release branch of eve on the atom.broker repo - 0.9.3.2." The release had moved
10 commits since this lane's base (86bf95cb8d → bbc78f5ff5: the cohost-media launcher / capstone work, W1b's flat
handles for the whole clj family 84bfc7c817, …). Merge bdfc8f2dc5 (release tip first parent), one conflict:
src/eve/flat/chunk.cljc, two hunks — W1b turned the map-handle factory and FlatSet from proxies into deftypes; this
lane's side changed only their -chunk-bytes, (.-b ctx) → (.-bs ctx) (U2a: Ctx's b is the eve.flat-io chunk, bs the host
bytes). Resolved as W1b's deftypes with the rename; every -chunk-bytes in the file reads bs. bb.edn / deps.edn auto-merged
(both sides' paths and tasks kept). Verified on the merged tree before the push (fresh processes; JDK 21, Clojure 1.12.0,
bb 1.12.218, Node 22 + shadow-cljs 3.3.6, Node builds from clean targets):

| host | suite | result |
|---|---|---|
| JVM | `clojure -M:flat-io-test` (conformance) | 11 / 264, 0 F, 0 E |
| JVM | `clojure -M:jvm-test -n eve.flat.chunk-test -n eve.flat.contract-test -n eve.flat.sister-test -n eve.flat.proto-types-test` | 28 / 380, 0 F, 0 E |
| bb | `bb test:flat-io` · `bb test:flat-sister` | 11 / 264 · 6 / 45, 0 F, 0 E |
| Node | flat-io-test · flat-sister-test builds | 11 / 264 · 4 / 30, 0 F, 0 E |

Pushed as a fast-forward of the release: bbc78f5ff5..bdfc8f2dc5. This branch fast-forwarded to the same commit.
