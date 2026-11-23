# U2b ledger (append-only, newest at the tail)

## 2026-11-17 00:32 ET — start

Provenance: atom.broker worktree /home/user/w/eve-u2b, branch flat-u2b at 3b656fea20 (Phase 0). Tools: Clojure CLI
1.12.3.1577 (clojure 1.12.0), OpenJDK 21.0.12.1, babashka 1.12.218, Node v22.23.4 (/opt/node22/bin/node). load1 1.66.
Read: the brief, FLAT-IO.md, FORMAT-V1 §2–5, adapters/README.md, adopter-tiers §4, chunk.cljc's byte layer (85–350).

ClojureScript route: shadow-cljs 3.3.6 + clojurescript 1.12.134 were not in ~/.m2; `clojure -Spath -M:node-test`
(deps.edn's own alias) fetched them from clojars / central (no system package). nbb is not on the PATH.

## 2026-11-17 00:50 ET — the JVM root's call shape (measured)

`(.get ^VarHandle vh b (int 0))` from Clojure 1.12.0: reflection warning "no such method", then IllegalArgumentException
"No matching method get found taking 2 args" (VarHandle get / set are signature-polymorphic; eve's
region/jvm-varhandle adapter routes them through a spread MethodHandle, 18.6 ns a get, boxing). So the root emits one
small class, eve.flat_io.LE, with clojure.asm (Clojure's own assembler, as deftype uses) at namespace load: static
methods `u16 i16 u32 i32 i64 f32 f64 (byte[] long)` and `p16 p32 p64 (byte[] long long)`, each one exact-descriptor
VarHandle call on a static-final byteArrayViewVarHandle (LITTLE_ENDIAN). AOT writes the class file
(Compiler/writeClassFile). `clojure -M:u2b smoke.clj` with _warn-on-reflection_: clean.

## 2026-11-17 01:05 ET — contract test green on the JVM and Node

Test: adapters/flat-io/test/eve/flat_io_fast_test.cljc (ns eve.flat-io-fast-test) — every contract function against a
reference written from FLAT-IO.md's text (LE reads assembled byte by byte, LEB128 as sums of 7-bit groups, key bytes
built as [0x05|0x06][len][utf-8] and compared unsigned, the writer as a byte model with the reference's growth), random
inputs from a Park–Miller generator (the same sequence on both hosts) plus the edge rows; key-cmp also held to
eve.codec/encode-key under eve.deftype-proto.data/compare-bytes; a 37-row UTF-8 fixture of the JDK's answers.

- JVM: `clojure -Sdeps '{:aliases {:u2b {:extra-paths ["adapters/flat-io/jvm/src" "adapters/flat-io/test"]}}}'
  -M:jvm-test:u2b -d adapters/flat-io/test -n eve.flat-io-fast-test` → Ran 12 tests containing 62888 assertions.
  0 failures, 0 errors. (5.5 s wall, load1 1.08)
- Node: built with `clojure -Sdeps '{:aliases {:u2b-node {:extra-paths ["adapters/flat-io/js/src"
  "adapters/flat-io/test"]}}}' -M:node-test:u2b-node -e "(require 'shadow.cljs.devtools.api) (do
  (shadow.cljs.devtools.api/with-runtime (shadow.cljs.devtools.api/compile* {:build-id :u2b-flat-io-test :target
  :node-script :main 'eve.flat-io-fast-main/main :output-to \"target/u2b/flat-io-test.js\"} {})) nil)"` (no
  shadow-cljs.edn edit; bare compile* refuses with "shadow-cljs has not been started yet!", with-runtime starts one),
  then `node target/u2b/flat-io-test.js` → Ran 11 tests containing 69904 assertions. 0 failures, 0 errors. (load1 1.91)
- Mutation check (JVM, the four mutants carry the same primitive signatures as the roots' fns, else the prim call sites
  throw ClassCastException instead of answering wrong): key-cmp negated → 1022 failures (502 from the encode-key
  cross-check); u32 + 1 → 7072 failures; tuple as a plain list → 10 failures, 7 errors; utf8 dropping a leading U+FEFF
  → 3 failures; unmutated → 0 / 0.

## 2026-11-17 01:05 ET — UTF-8: two measured cross-host facts (for the coordinator and U2a)

JDK 21 String(b, off, n, UTF_8) vs Node 22 TextDecoder on the 37 fixture rows (probe: scratch cmp.js):

1. A leading EF BB BF: the JDK keeps U+FEFF; TextDecoder's DEFAULT (`new TextDecoder("utf-8")`, what chunk.cljc's
   cljs arm, adapters/codec/js and adapters/encoding/js all construct) drops it — so on ClojureScript today a string
   that starts with U+FEFF loses it through those decoders (TextEncoder writes it as EF BB BF). flat-io/js constructs
   `{fatal: false, ignoreBOM: true}` and answers the JDK's string. Defect outside my files: repro
   `node -e 'console.log(JSON.stringify(new TextDecoder("utf-8").decode(new Uint8Array([0xEF,0xBB,0xBF,0x41]))))'`
   → "A" (the JDK answers "﻿A").
2. A surrogate in three-byte form (ED A0..BF xx — malformed): the JDK replaces the three bytes with one U+FFFD,
   TextDecoder (WHATWG) each byte (3 rows of 37). TextDecoder has no option for it; flat-io/js keeps the WHATWG answer
   and the test pins both (utf8-js-divergence). Only corrupt chunks carry these bytes (the writers encode real strings).

## 2026-11-17 00:59 ET — correction

The two entries headed "01:05 ET" above were written at 00:55 ET (the heading typed before the clock was read).

## 2026-11-17 00:59 ET — the bench, and the stand-in it uses until U2a's portable root lands

bench/flat-io/run-bench <jvm|node|bb> <seam|flatx> <root-dir>... — eve's own classpath for the host (deps.edn :paths,
:node-test, hosts.edn :bb) with any adapters/flat-io/* entry taken out and the named root first; Node builds each
root once (shadow release, :advanced); ROUNDS rounds, each running every root in turn (interleaved), load1 printed
before every run. bench/flat-io/flat_io_seam_bench.cljc (u32 / i64 reads ×1M, a varint stream, a key-cmp binary
search over 407 keys, a 1024-element i32 tuple reduced, a 4-element tuple made and its nth read, key-sized utf8, 1M
writer u32s; best of 20 after a first; a checksum per row) and bench/flat-io/flatx.cljc (race-nk's flatx.clj, same
labels, count and = timed).
flat-u2a has no commit past 3b656fea20 yet (`git log flat-u2a`), so the portable column is a STAND-IN in my scratchpad
(never committed): chunk.cljc's byte layer at 3b656fea20 moved under the contract's names (the JVM writer its hinted
definterface, bb's its protocol), key-cmp over host-encoded key bytes, tuple a vector built from the reads. The real
portable column comes from rerunning run-bench with adapters/flat-io/portable/src once it exists. flatx through the
seam needs U2a's chunk.cljc; until then flatx measures chunk.cljc's own byte layer on every root alike.
Smoke (1 round, JVM, load1 3.34 → 4.91): every row's checksum equal between the fast root and the stand-in.

## 2026-11-17 01:02 ET — correction

The entry headed "00:59 ET" above was written at 00:57 ET (same slip as before; headings are now written after
reading the clock).

## 2026-11-17 01:02 ET — DEFECT (outside my files): ClojureScript reads a negative i32 as its u32 value

chunk.cljc:121 (cljs arm) `(defn- ri32 [b i] (unchecked-int (ru32 b i)))`. In ClojureScript `unchecked-int` is `fix`
(truncation toward zero, no 32-bit wrap): clojurescript-1.12.134.jar cljs/core.cljs:2937 `(defn- ^number fix [q] (if
(>= q 0) (Math/floor q) (Math/ceil q)))`, :2947 `(defn unchecked-int [x] (fix x))`, and core.cljc has no
unchecked-int macro. So every i32 column (kind 6) holding a negative value reads back as value + 2^32 on Node.
Re-derived (Phantom-defects checks): TREE — chunk.cljc in flat-u2b at 06ffd8a3bb is byte-identical to 3b656fea20
(`git diff 3b656fea20 -- src/eve/flat/chunk.cljc` empty). TOOLS — shadow-cljs 3.3.6, clojurescript 1.12.134, Node
v22.23.4. FRESHNESS — a fresh release build (`bench/flat-io/run-bench node flatx adapters/flat-io/js/src`, ROUNDS=1;
chunk.cljc does not touch the seam yet, so the root is irrelevant) prints `X node count 407 get [4294967291 5000 55
12] [-5 5000 55 12]` and `X node equal? false`. RECONCILE — the JVM answers [-5 5000 55 12] / equal? true on the same
shape (race-nk eve/flatx-jvm-before.txt). Fix: read with `(.getInt32 dv i true)` or `(bit-or (ru32 b i) 0)`.
flat-io/js's i32 is getInt32 (the contract test's r-i32 wraps, and it is green), so chunk.cljc over the seam with
flat-io/js answers -5 on Node; flat-io/portable's cljs arm carries the defect if ri32 moves verbatim — for U2a.

## 2026-11-17 01:02 ET — flatx on Node before the seam (chunk.cljc's own byte layer)

`ROUNDS=1 bench/flat-io/run-bench node flatx adapters/flat-io/js/src`, release :advanced, load1 6.23 (shared box):
persistent-map-x407 best 212 µs · fc-pack 17565 · count-flat 50 · seq-walk-flat 139 (persistent 24) · get-x407-flat
4896 (persistent 50) · format-flat 3620 (persistent 340) · eq-flat-persistent 84 (answers false: the defect above).

## 2026-11-17 01:02 ET — seam bench, Node, fast root vs the stand-in, preliminary (3 rounds interleaved)

`ROUNDS=3 bench/flat-io/run-bench node seam adapters/flat-io/js/src <scratch>/standin`, load1 7.23 / 8.57 / 8.13 —
heavily loaded, indicative only. Best ns/op across rounds, fast vs stand-in: u32-read 0.73 vs 1.93 · i64-read 26.98
vs 27.65 (both a BigInt per read plus a BigInt add) · varint-read 13.45 vs 41.03 · key-cmp search (per key, 407 keys)
550 vs 1942 · tuple-reduce-i32x1024 4.72 vs 23.66 per element · tuple-make-nth-i32x4 33.98 vs 220.6 · utf8-key 90.08
vs 79.9 · buf-u32-write 3.56 vs 5.13. Checksums equal except tuple-reduce / make-nth: the stand-in's i32 is
chunk.cljc's ri32 (the defect above), so its sums differ — the fast root's are the right ones.
utf8-key no better than TextDecoder: probe (scratch utf8probe*.js, Node 22, load1 6.1–6.3, best of 15 × 200k decodes
of "Station-N"): TextDecoder 80–84 ns, fromCharCode.apply(subarray) 83–86, += concat 81–85, fromCharCode.apply(Array)
46–47, fixed-arity fromCharCode(b[o], …) 18.4–19.1. → the root takes the fixed-arity form to 16 ASCII bytes.

## 2026-11-17 01:07 ET — root changes after measurement and after reading U2a's committed portable (12295ecefc)

- key-cmp (both roots): early answers before any encoding — a stored key below 256 bytes is below a k of more than n
  UTF-16 units / chars and above one of fewer than n/3 (k's UTF-8 length m satisfies km <= m <= 3 km on both hosts'
  encoders); a k of exactly n chars is compared in one ASCII pass (falling back to the whole rule at the first non-ASCII
  char). The whole rule is unchanged (key-cmp-general).
- flat-io/js utf8: ASCII runs to 16 bytes through a fixed-arity String.fromCharCode, 17–32 through
  fromCharCode.apply over a fresh Array, the rest TextDecoder (probe numbers in the 01:02 entry).
- flat-io/js tuple: no typed-array view. Probe (scratch viewprobe.js, Node 22, load1 3.96, best of 15): making an
  Int32Array view + one read 15.4–15.8 ns vs a DataView read 0.8 ns; a 1024-element reduce 0.53 ns/element through a
  view vs 0.55 through the DataView. Views cost at creation and win nothing on reads, so every element reads through
  the chunk's DataView (the brief named views; the measurement is the reason they are not made).
- Parity with U2a's portable (read from the object store, `git show flat-u2a:adapters/flat-io/portable/src/eve/flat_io.cljc`):
  tuple elements are portable's — JVM u64 a two's-complement long (portable: "as eve.flat.column reads kind 11"), cljs
  i64 / u64 a js/BigInt (was: a number while safe, after col-at; chunk.cljc calls no fio/tuple, so portable decides);
  bytes= answers false when bs holds fewer than n bytes from boff (the JVM root's Arrays/equals threw there).
- Type hints in flat-io/js (^Chunk, ^FlatBuf, ^js/Uint8Array, ^string) so shadow infers every interop target.
  Tests after these (before the hint-only edit): JVM `... -M:jvm-test:u2b -d adapters/flat-io/test -n
  eve.flat-io-fast-test` → 12 tests, 62889 assertions, 0 failures (load1 3.34); Node (the 00:55 build command) → 11
  tests, 69906 assertions, 0 failures. The hint-only edit is re-verified in the merge probe below.
  U2a found the cljs i32 defect independently (their ledger, 00:47) and fixed it in portable's i32 (`|0`).

## 2026-11-17 01:10 ET — merge probe: U2a's conformance test passes on both fast roots

A detached scratch worktree of mine (scratchpad/u2b/merge, no branch, nothing pushed): flat-u2b 2614b640e4 merged with
flat-u2a 29e18468eb — `git merge --no-edit flat-u2a` clean (no conflicting path: U2a touched portable/, test/
conformance + runners, chunk.cljc, hosts.edn, deps.edn, bb.edn, shadow-cljs.edn; I touched jvm/, js/, my test files,
bench/flat-io, my ledger).

- JVM: `clojure -Sdeps '{:aliases {:u2b {:extra-paths ["adapters/flat-io/jvm/src"]}}}' -M:flat-io-test:u2b` →
  eve.flat-io-conformance-test: Ran 11 tests containing 264 assertions. 0 failures, 0 errors. (load1 3.11) That run
  printed "flat_io.clj:301 recur arg for primitive local: r … Auto-boxing loop arg: r" — the proof my .clj was the one
  loaded, and a boxing slip in ascii-cmp (a cond's trailing nil branch), fixed in 2614b640e4 and re-run: same result,
  no warning.
- Node: `clojure -Sdeps '{:aliases {:u2b-node {:extra-paths ["adapters/flat-io/js/src"]}}}'
  -M:node-test:flat-io-node:u2b-node -m shadow.cljs.devtools.cli compile flat-io-test && node
  target/flat-io-test/main.js` → Ran 11 tests containing 264 assertions. 0 failures, 0 errors. (load1 3.34)
  Load order, for the wiring: Clojure's load tries a name's .clj before its .cljc, and ClojureScript prefers .cljs over
  .cljc — so a fast root next to flat-io/portable on a row wins whatever the path order. The same rule means bb would load
  flat-io/jvm's .clj if it were on bb's path: bb rows keep portable only.

## 2026-11-17 01:10 ET — seam bench, JVM, U2a's portable vs flat-io/jvm (the measured numbers)

In the merge probe: `ROUNDS=3 bench/flat-io/run-bench jvm seam adapters/flat-io/portable/src adapters/flat-io/jvm/src`,
load1 4.63–5.16 (shared box: indicative). Best ns/op across the three interleaved rounds, portable / fast (×):
loop-baseline 4.02 / 3.85 · u32-read 6.65 / 4.94 (×1.35; ×2.4 over the loop) · i64-read 10.71 / 5.06 (×2.12) ·
varint-read 12.44 / 10.46 (×1.19) · key-cmp search per key (407 keys) 450.5 / 454.9 (×0.99 — no gain on the JVM: a
key-sized getBytes is cheap on the JDK and the search's own vector reads dominate) · tuple-reduce-i32x1024 per element
25.24 / 9.65 (×2.62) · tuple-make-nth-i32x4 158.7 / 10.5 (×15.1) · utf8-key 19.12 / 13.89 (×1.38) · buf-u32-write
15.95 / 6.70 (×2.38). Every row's checksum equal between the roots.

## 2026-11-17 01:14 ET — flatx through the seam, JVM, portable vs flat-io/jvm; and a correction (= was noise)

Merge probe, `ROUNDS=3 bench/flat-io/run-bench jvm flatx adapters/flat-io/portable/src adapters/flat-io/jvm/src`,
load1 4.82–6.17. Best µs across the interleaved rounds, portable / fast: persistent-map-x407 50 / 68 (no flat-io in it:
noise) · fc-pack 7031 / 6709 · count-flat 41 / 41 · seq-walk-flat 112 / 126 · get-x407-flat 1000 / 1025 · format-flat
436 / 387 · eq-flat-persistent 3160 / 4548. Every run: get [-5 5000 55 12], equal? true. flatx on the JVM is not
bound by the byte seam: the roots are within the box's noise (U2a's lookup already took get×407 from race-nk's 9.8 ms
to ~1 ms). CORRECTION, eq-flat: the ×0.69 above is noise, not the root. Re-derived with a dedicated probe (scratch
eqprobe.clj: (= f m) ×400 after 50 warm-ups), alternated portable / fast three times at load1 5.0–5.3: portable 1085,
1115, 1302 ms; fast 1069, 1119, 991 ms. JFR on (= f m) (`-XX:StartFlightRecording=…,settings=profile`, `jfr print
--events jdk.ExecutionSample`): 2 of 242 (portable) and 2 of 274 (fast) samples under any eve.flat_io frame; the time is
the digest walk (node_equiv → handle_digest → flat_emit! → sha_vec / eve.crypto) and reflective Class.getFields in
chunk.cljc (its reflection warnings at 1631–1730, 2206–2320, 4714–4856 — chunk.cljc's, not a root's).

## 2026-11-17 01:14 ET — seam bench, Node, U2a's portable vs flat-io/js (the measured numbers)

`ROUNDS=3 bench/flat-io/run-bench node seam adapters/flat-io/portable/src adapters/flat-io/js/src` (release
:advanced), load1 3.83–4.35. Best ns/op, portable / fast (×): loop-baseline 0.61 / 0.65 · u32-read 1.65 / 1.00 (×1.65)
· i64-read 77.54 / 29.41 (×2.64: portable makes a DataView per read; both make a BigInt) · varint-read 13.37 / 14.14
(×0.95, parity: portable's varint-v is already vector-free) · key-cmp search per key 2379.8 / 431.8 (×5.51) ·
tuple-reduce-i32x1024 per element 24.29 / 2.60 (×9.34) · tuple-make-nth-i32x4 215.75 / 13.41 (×16.1) · utf8-key
83.19 / 31.40 (×2.65) · buf-u32-write 5.93 / 3.81 (×1.56). Every checksum equal.

## 2026-11-17 01:24 ET — flatx through the seam, Node, portable vs flat-io/js

`ROUNDS=3 bench/flat-io/run-bench node flatx adapters/flat-io/portable/src adapters/flat-io/js/src` (release
:advanced), load1 7.50–7.63 (heavily shared). Best µs, portable / fast: persistent-map-x407 245 / 181 (no flat-io:
noise) · fc-pack 18201 / 17495 · count-flat 29 / 29 · seq-walk-flat 85 / 91 · get-x407-flat 4753 / 4597 ·
format-flat 2967 / 3114 · eq-flat-persistent 8861 / 9080. Every run: get [-5 5000 55 12] [-5 5000 55 12], equal?
true (the i32 defect gone on both roots: U2a's |0, my getInt32). flatx on Node is not bound by the byte seam either:
the seam's rows are 1.6–16× faster on flat-io/js, flatx's are within noise.

## 2026-11-17 01:24 ET — seam bench, bb (portable, the only root bb runs) — and a measured bb writer cost

`ROUNDS=2 bench/flat-io/run-bench bb seam adapters/flat-io/portable/src` (N = 100k: bb interprets), load1 2.98–6.6.
Best ns/op: loop-baseline 74 · u32-read 503 · i64-read 990 · varint-read 722 · key-cmp search per key 41,752 ·
tuple-reduce-i32x1024 756 per element · tuple-make-nth-i32x4 5,160 · utf8-key 1,993 · buf-u32-write 50,176.
The writer row is 100× the read row: probe (scratch bbw.clj / bbset.clj, bb 1.12.218, load1 2.0–2.3): portable's
buf-u8 14.5–16.8 µs a byte; `(aset b i (unchecked-byte 1))` on a byte-array 13.4–16.1 µs, `(aset ^bytes b i (byte 1))`
14.8 µs, `(aset-byte b i 1)` 47 ns, `alength` 27 ns, `java.lang.reflect.Array/setByte` refused
(MissingReflectionRegistrationError). So on bb every byte the writer stores costs ~15 µs through aset and ~0.05 µs
through aset-byte — a one-call change in flat-io/portable's :bb writer arm (U2a's file; chunk.cljc's pre-seam writer
had the same aset, which is race-nk's 836 ms bb fc-pack). Not fixed here: outside my files.

## 2026-11-17 01:27 ET — flatx on bb (portable), and the fast roots under chunk.cljc's own tests

- bb flatx through the seam, portable: `ROUNDS=1 bench/flat-io/run-bench bb flatx adapters/flat-io/portable/src`,
  load1 1.91: persistent-map-x407 4250 µs · fc-pack 789,959 · count-flat 675 · seq-walk-flat 890 · get-x407-flat
  83,689 · format-flat 7245 · eq-flat-persistent 589,283; get [-5 5000 55 12], equal? true. (race-nk's pre-seam bb:
  fc-pack 836,376 · get 127,870 · format 24,318.) bb's pack and = are its writer's aset (the 01:24 entry).
- JVM, U2a's chunk.cljc over flat-io/jvm: `clojure -Sdeps '{:aliases {:u2b {:extra-paths
  ["adapters/flat-io/jvm/src"]}}}' -M:jvm-test:u2b -n eve.flat.chunk-test -n eve.flat.contract-test` (merge probe) →
  Ran 11 tests containing 186 assertions. 0 failures, 0 errors. (load1 2.27)
- Node, U2a's chunk.cljc over flat-io/js: `clojure -Sdeps '{:aliases {:u2b-node {:extra-paths
  ["adapters/flat-io/js/src"]}}}' -M:node-test:u2b-node -m shadow.cljs.devtools.cli compile flat-x1-test && node
  target/flat-x1-test/main.js` → Ran 4 tests containing 33 assertions. 5 failures, 0 errors — golden-bytes ×2 and
  jvm-node-same-bytes ×3, the same five U2a measured at the Phase-0 pin on the unmodified tree (their 00:47 entry:
  "the fixture hex differs from the packed hex: pre-existing"); no other failure. (load1 2.96)
- My contract test at 2614b640e4: JVM 12 tests / 62889 assertions, Node 11 / 69906, 0 failures (load1 3.67).

## 2026-11-17 01:27 ET — the done bar, in one place

Both roots implement the whole contract (FLAT-IO.md: chunk / chunk-bytes / chunk-len, u8 u16 u32 i32 i64 f32 f64,
varint varint-v varint-next varint-len, utf8, bytes= bytes-cmp key-cmp, buf + buf-pos buf-seek buf-u8 buf-u16 buf-u32
buf-i64 buf-f64 buf-varint buf-bytes buf-off buf-patch-u32 buf-align buf-raw buf-chunk, tuple col; plus buf-f32,
which chunk.cljc's writer does not call). Contract tests green: mine (JVM 62889 / Node 69906 assertions) and U2a's
conformance test over each fast root (JVM 264, Node 264). Numbers, portable vs fast, per host: the 01:10 (JVM seam),
01:14 (JVM flatx, Node seam), 01:24 (Node flatx, bb seam) and 01:27 (bb flatx) entries.

## 2026-11-17 01:27 ET — for the coordinator's merge of flat-u2b with flat-u2a

- `git merge flat-u2a` into flat-u2b is clean (done in a scratch worktree at 29e18468eb; no shared path).
- hosts.edn / deps.edn (U2a's TODO(U2b) lines): :jvm → adapters/flat-io/jvm/src beside flat-io/portable (a .clj
  loads before a .cljc of the same name, so order does not matter); deps.edn :paths the same; :node-test and
  :browser-test, and hosts.edn :cljs / :browser → adapters/flat-io/js/src (.cljs before .cljc). Keep flat-io/portable
  only on :nbb and :scittle (SCI: measured, no core protocol on a deftype, no outside field read), on :bb (bb would
  load flat-io/jvm's .clj, and runs portable by design), and on :cherry / :squint / :jolt (not measured here).
- My test (adapters/flat-io/test/eve/flat_io_fast_test.cljc + flat_io_fast_main.cljs) sits beside U2a's conformance
  test; to fold: its reference reads, the UTF-8 fixture with the JS divergence rows, the writer-vs-model test and the
  random key-cmp rows are what U2a's does not have.
- bench/flat-io/run-bench takes any root dir, so the coordinator can rerun every number above on the merged tree.
  (flat-io/js warning-free after: bigint? spells its string compare identical?; Node 11 tests / 69906 assertions, 0 failures, 0 shadow warnings in the root.)
