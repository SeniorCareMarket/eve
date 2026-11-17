# REENGINE — place & detach on the batch primitives: lane status

Branch `claude/flat-reengine`, base = `claude/flat-rename` tip 6124bb3.
Scope: `src/eve/flat/place.cljc` (the zip), `src/eve/flat/image.cljc`
(the canonical detach), `src/eve/flat/transfer.cljc` (its own walk —
transfer!'s detach half), their tests, this file. The checked gate
(`eve.flat.check/open-checked`) is untouched: no raw bytes are placed,
ever; placement still consumes only CheckedBytes handles or the
in-process map form, validated in full before the first claim.

## Re-derivation at the base pin (6124bb3) — J, firsthand

Toolchain on J: OpenJDK 21.0.9, Clojure CLI 1.12.6.1673, bare
`clojure` on J's own m2/gitlibs. Every log's first line is
`HEAD=<sha>` inside the redirect; legs whose HEAD differs from the
claimed pin are void. J was busy (load ~5–7, fleet legs concurrent);
min-of-5 discipline throughout, so absolutes run ~1.3× the quiet-box
session the brief cited — every claim below is confirmed or killed on
this pin's own numbers, not the brief's.

### B1 — shipped place! (eve.flat.place-bench, HEAD 6124bb3, claim mode: run)

| corpus | blocks | place! | ns/block |
|---|---|---|---|
| map 10k int->int | 2,380 | 60.15 ms | 25,274 |
| vector 100k ints | 103,229 | 2,374.32 ms | 23,001 |
| vector 10k records | 30,325 | 557.89 ms | 18,397 |
| 5k chat messages | 70,997 | 1,475.31 ms | 20,780 |

CONFIRMED: shipped place costs 18.4–25.3 µs/block against the batch
claim+copy engine's measured 68.5–176.0 ns/block (Z2's bench, this
tree: one `alloc-run!` per class + arraycopy into the heap array) and
the raw claim's 32.8–50.7 ns/block. Gate ≤50 ns/block missed ~400×.

Phase decomposition (reengine-bench --placeprobe, HEAD 443f8be —
shipped sources identical to base):

| corpus | plan (normalize+validate) | full | no-reloc | pointer pass |
|---|---|---|---|---|
| map | 15.73 ms | 50.21 ms | 46.00 ms | 4.20 ms |
| vec | 168.84 ms | 1,664.05 ms | 1,354.46 ms | 309.58 ms |
| records | 47.10 ms | 389.86 ms | 396.61 ms | ~0 |
| messages | 165.48 ms | 807.12 ms | 793.85 ms | 13.26 ms |

The pointer pass is a minor term (vec's 3.0 µs/reloc aside). The
dominant term is claims+copies inside `execute-plan!` at
~9–13 µs/block — the per-block `copyOfRange` + `-sio-write-bytes!`
path, not the claim (the run claim is engaged: claim mode "run").
The plan itself costs 1.6–6.6 µs/block and stays as-is (it is the
checked gate's validation; not this lane's to weaken).

### B3 — shipped detach vs @a (eve.flat.z1-board, HEAD 6124bb3, same session)

| corpus | @a | detach | missed by |
|---|---|---|---|
| map 10k int->int | 13.48 ms | 1,372.22 ms | ~102× |
| vector 100k ints | 32.07 ms | 48,658.76 ms | ~1,517× |
| vector 10k records | 46.80 ms | 28,017.30 ms | ~599× |
| 5k chat messages | 66.72 ms | 53,092.28 ms | ~796× |

CONFIRMED in substance (the brief's session read 114–733×; this
busier session reads worse). Detach as shipped is a correctness
mechanism.

### Detach census (reengine-bench --census, HEAD ede970c)

| corpus | blocks | classes | content B | image B | detach (mmap) | detach (FlatSio view) |
|---|---|---|---|---|---|---|
| map | 2,380 | {0:35, 1:1179, 2:216, 3:780, 4:170} | 298,072 | 346,952 | 1,533.91 ms (644.5 µs/blk) | 1,554.32 ms |
| vec | 103,229 | {0:100001, 2:3228} | 1,513,208 | 3,677,808 | 51,457.87 ms (498.5 µs/blk) | 176,326.61 ms (contention) |
| records | 30,325 | {0:20001, 2:10324} | 1,540,386 | 2,167,976 | 12,754.17 ms (420.6 µs/blk) | 27,270.16 ms |
| messages | 70,997 | {0:41668, 1:5416, 2:15579, 3:8334} | 3,701,012 | 5,162,744 | 23,732.64 ms (334.3 µs/blk) | 47,511.56 ms |

Byte-identity anchors (SHA-256 of the shipped detach image; the
re-engineered detach must reproduce them exactly — image v1 is a
frozen, cross-host byte-identical format):
map f356b6a3c4ef5484e7d5d9e13da22b8e0fbf1d840094ef84a4e8f04512e0969e ·
vec 33efcd5fa8125c531af7907bf8bc65e662191a58e838142701f075d577fe7772 ·
records 9dfd9dc8fdd26d608f9202be285e6f56308092526335a4e6113c8bfecdcbce6d ·
messages 8a0dd46c4d976455d99bc8c36127a96101f25ce9484dd57efda1d56076bba60c.
Images byte-identical across the mmap and view source paths; value
read-back equal on every corpus. NO overflow-class blocks anywhere.

TWO THEORIES KILLED by these rows:
- "per-block generic slab reads are the cost" (the Z1 report's
  attribution): the FlatSio arm has no slab reads at all — direct
  array copies — and is exactly as slow. The cost is inside the
  walk's per-block processing, source-independent.
- "the overflow-class window (64 KiB estimate, doubling re-copies)
  is the cost": no corpus has overflow blocks and all still pay
  334–645 µs/block.

### Stack attribution (reengine-bench --placesample / --detachprobe)

- place!: the pig is `execute-plan!`'s grouping scaffolding — the
  `filterv` predicate and `group-by` key fn over the UNHINTED
  `unit-of`/`classes` arrays (reflective `aget` per block), ~82% of
  samples on vec/records/messages. Claims, copies and the pointer
  pass underneath are already fast.
- detach: the pig is per-reloc/per-block bookkeeping — unhinted
  `put-i32-at!` the top frame, `add-reloc!` + `grow-ints` through
  volatiles on every relocation, `insert!`. Byte copies are minor.

### transfer! at the base pin (reengine-bench --transfer)

map-10k: detach 880.17 ms, zip 1,279.20 ms, detach+zip 2,159.37 ms,
transfer! 2,194.62 ms — composition ratio 1.02 (the ≤2× gate holds);
cap 5 ms missed ~439×. vec/records/messages rows: the leg's later
output never landed (log ends after the map row); the STREAM-PLAN's
shipped absolutes (1,539.74 / 28,645.78 / 8,371.81 / 45,284.57 ms)
stand as the S1/S3 comparison basis.

## Intermediate landings (pre-STREAM-PLAN charter work)

- place re-engineered on batch primitives (`305ac4c`): validate!
  unchanged in checks/order/refusals, tables lifted to primitive
  arrays once; `execute-plan!` rebuilt — counting-sort class
  membership (no group-by/filterv), unit-major run claims via
  per-class cursor slices (the shipped Z3.3 claim chain retained:
  whole alloc-run! → halving → singles), all claims → all copies →
  pointer pass; copies are direct arraycopy into the target heap
  array, generic -sio-write-bytes! retained for encrypting/foreign
  sios and the overflow class; full rollback of the claimed set on
  any throw. B1 re-judgment at the current tip: leg reengine-b1,
  pending at this writing.
- detach bookkeeping rebuilt (`b11ea63`, `416e24d`): the walk's
  volatile+closure bookkeeping moved onto a DetachState deftype with
  direct field access; traversal order, patch points, emission
  byte-identical by construction (proof = census re-run vs the
  SHA-256 anchors above; leg reengine-census2, pending at this
  writing). This is an intermediate step — the plan of record below
  supersedes the walk's role in transfer.

## STREAM-PLAN execution (plan of record, banked verbatim at doc/flat/STREAM-PLAN.md)

Geometry reading the lane implements (stated once, here): a segment
is a class-homogeneous cut of the post-order stream — `alloc-run!`
claims consecutive blocks of ONE class, so one claim per segment is
only possible when a segment's blocks share a class. Post-order
indices stay global (root = last index); segments partition the
index space by class. Claim count per transfer == segment count, by
construction.

- S0 — the walk: `eve.flat.stream` cursor + post-order walk-count
  (`b13e7be`). Gate: ≥10× the shipped detach walk per corpus AND
  within 5× of @a. Verdict: PENDING (leg reengine-s0).

## Successor seat — Nov 12, from ~15:13 ET

The inherited legs had never run: the first filings were HELD by the
router (missing `deliverable` field — the held records name it), the
re-filings sat in J's inbox, and the banked code had never compiled.
Re-derived at the pin, six defects, all in the never-ran line:

1. `place.cljc` `claim-slice!` — 8 args with ^long hints: Clojure
   refuses primitive-taking fns over 4 args. Never compiled.
2. `stream.cljc` `cursor-fetch` — same rule (5 args, 3 hints).
3. `stream.cljc` required `eve.alloc` / `eve.data` /
   `eve.data.store` and called `alloc/class-size` / `store/heap-ctx`
   — none exist in this tree. Real homes: `eve.deftype-proto.*`;
   `alloc/node-bytes` subsumes the cursor's heap branch.
4. Latent: walk scratch was 512 B; class-5 blocks are 1024 B.
   (Fixed together: `f4af3cd`.)
5. Detach rebuild: `DetachState`'s `:unsynchronized-mutable` fields
   are emitted package-private (reflection probe: every field
   modifier-less; immutable-field controls `public final`), so the
   walk's free fns could neither direct-link nor Reflector-read them
   — first detach died in `d-lookup`. Repair: the state is one
   object array of slots, all access via aget/aset (`24e7c75`).
   Local proof: place-test 7 tests / 96 assertions 0F/0E.
6. `cursor-fetch` read the class-access triple (a vector) with
   `(aget ^objects acc i)` — ClassCastException on any cursor walk.
   Fix: nth (`4774a0f`).

Provenance for 1–6: tree = this branch; tools = clojure CLI via
CLJ_CONFIG on hatch, JDK on PATH; fresh repro = local load checks,
place-test, and stream tests, each failing before and passing after
its fix.

### Collected verdicts (J, HEAD=24e7c75 unless noted)

- **b1 (place re-engine, place-bench)**: map 25,675.9 · vec 2,663.5 ·
  records 17,780.9 · messages 10,789.8 ns/block, vs shipped
  25,274 / 23,001 / 18,397 / 20,780. The batch re-engine is a real
  win only where blocks are uniform (vec 8.6×, messages 1.9×);
  pointer-dense map is unchanged, records ~unchanged. It does NOT
  rescue the shipped design — the stream receiver's bar (≤500
  ns/block) stands 5–50× below these numbers.
- b3 (z1-board, rebuilt detach) and census2 (byte-identity +
  transfer totals): RUNNING on J at this writing; s0 and suites
  legs queued behind them. (s0/suites will check out the moved
  bundle tip 6a86cba — walk semantics unchanged by the S1 commits:
  the only walk change after 24e7c75 is repair 6, an access-form
  fix. Judged tip will be recorded as 6a86cba.)
- **b3 partial (board rows landed before J access dropped)**:
  deref / rebuilt detach — map 15.35 ms / 1,456.10 ms · vec
  35.31 / 42,658.63 · records 43.97 / 12,371.10. Against the base
  pin's shipped detach (1,372.22 / 48,658.76 / 28,017.30 ms): the
  bookkeeping rebuild is a wash (0.94×–1.14×). Messages row pending.
- **census2 partial**: detach byte-identity CONFIRMED on map
  (sha f356b6a3… == anchor) and vec (33efcd5f… == anchor);
  value-ok and view==mmap-bytes true on both. Detach absolutes
  this session: map 1,879.06 ms mmap / 2,069.20 view; vec
  43,884.64 / 51,471.64. Records/messages rows + the transfer
  section pending.
- **J ACCESS DOWN (~16:2x ET)**: SSH banner-exchange timeouts
  through the egress proxy, three retests over ~5 min. The
  documented /root/.ssh/config restore was applied first (not the
  cause this time — failure is at connection level, not auth). A
  detached probe retests every ~45 s. The legs run autonomously
  on J; collection resumes when access returns.

### S1 — the pipe: BUILT (`6a86cba`), leg filed

`send-stream` (the S0 walk extended: bytes kept at enter, pointer
words rewritten to stream indices at exit, records grouped into
class segments), `receive-stream!` (parse → one run claim per
segment → verbatim copies → one patch pass; rollback on failure),
`stream-transfer!` (the shipped verb's contract). Wire v0: EVST
header; per record the scan kind plus the p1/p2/p3 triple only for
trie kinds 5/6; NO slot tables travel — block-scan is the per-tag
slot resolver on both ends. Local proof: stream-test 4 tests / 17
assertions 0F/0E (roundtrips incl. class-5 string; claims ==
segments; garbage refused); --s1 --tiny: equal + claims==segments
on map/vec/records (messages tiny died in domain creation under the
local 2 g heap — make-heap-region OOM, not a pipe error). Gate
verdict: PENDING (leg reengine-s1, canonical corpora, J).

### S2 + S3 — code complete locally, legs queued behind J's return

- **S2 machinery (`ca2e6df`)**: send opts `:budget` (entry-granular
  segment cutting) and `:run-threshold` (typed runs: bucket- and
  index-consecutive leaf records of one kind/size collapse into one
  run record); wire v1 (receiver reads v0+v1, payload-bounded parse,
  declared-count check per segment). `--s2` bench mode (`f54f314`):
  budget sweep 64 KiB/256 KiB/1 MiB, threshold sweep 8/16/32, wire
  vs image, fragmentation probe (largest allocatable run per class,
  fresh control vs post-receive; frag := 1 − after/control).
  Local proof: stream-test v1 cases green; --s2 --tiny prints all
  sections (map row complete; vec died in local domain creation at
  the 2 g heap — the J leg runs 4 g).
- **S3 landing shape (`3647700`)**: receive restructured to
  per-segment parse → claim → copy → (:check?) vouch — the checked
  door; `transfer!` delegates slab cases to `stream-transfer!`
  (dynamic resolve; detach+zip fallback kept). Local proof:
  stream-test 6 tests / 28 assertions 0F/0E incl. the door passing
  every valid shape.
- **J ACCESS still down** (~50 min at this writing; probe running,
  34+ failed tries). Outstanding on J: b3 messages row, census2
  records/messages + transfer section, s0 leg, suites leg, s1 leg;
  to file on return: s2 leg, final suites leg (incl. stream-test),
  final --transfer/--s1 comparison leg. This box was itself
  VM-replaced mid-outage (~17:0x ET): /root/.ssh/config restored
  per the documented recovery; the worktree and all commits
  survived; /tmp scripts were lost and re-created under
  ~/workspace/static-eve/lane-tools/.

## Successor seat — Nov 12, ~17:48 ET: J was REBOOTED; the cross-edge defect

- J came back at 17:48 ET (probe try 45) with uptime 3 min: the
  outage was a box reboot. It killed b3 (at the messages row),
  census2 (records/messages + transfer pending) and s0 mid-run;
  the engine closed those jobs with partial logs — NOT-RUN by the
  lane's rule. All three re-filed at the current tip; suites/s1
  survived in the inbox; s2 filed.
- **Cross-edge defect (transfer-test, 3F/3E — the S3 rebasing's
  first full-suite judge)**: on a value whose root map shares one
  vector across :part/:x/:y, send-stream emitted the in-flight
  marker (-2) as the vector's stream index at the map node's two
  inline-pointer slots. Measured root cause: blocks were marked
  seen at ENTER-push time, so a cross edge (map node ↔ the vector
  root, both children of the root block) let the node exit while
  the vector was still in flight. The checked door refused the
  corrupt stream — its first catch. Fix (`39dc57e`): mark at pop
  time (true iterative post-order), skip already-indexed frames,
  refuse genuine cycles and in-flight lookups. walk-count is
  untouched (order-insensitive aggregates; S0 unaffected).
  Proof: wire repro scans clean (0 bad targets; was 2); stream-test
  7 tests / 30 assertions 0F/0E with the regression; transfer-test
  6 tests / 150 assertions 0F/0E locally through the stream-backed
  transfer!, including the exact-allocation census assertions that
  failed before the fix.

## Successor seat — Nov 12, ~18:5x ET: verdicts collected at 39dc57e

All legs below ran on J at HEAD=39dc57e (logs in
~/static-eve-results/ on J). J ran loaded (load 5-8, other
tenants) through most of this window; absolutes carry that, the
same-session ratios are the evidence.

- **b3 (detach rebuild, re-judged)**: deref / detach ms —
  map 11.89 / 1,474.89 · vec 81.84 / 48,152.67 · records 44.08 /
  19,753.47 · messages 101.33 / 27,649.81. Against the base-pin's
  shipped detach (1,372.22 / 48,658.76 / 28,017.30): 0.93× / 1.01×
  / 1.42× — the bookkeeping rebuild is a wash, as the earlier
  partial rows said. It is not a rescue and was never the plan's
  main line.
- **census2 (byte-identity + transfer arms)**: detach images are
  BYTE-IDENTICAL to the inherited anchors on all four corpora
  (map f356b6a3…, vec 33efcd5f…, records 9dfd9dc8…, messages
  8a0dd46c…). Transfer arm, map: detach 974.88 + zip 1,798.50 =
  2,773.37 ms vs stream-backed transfer! 1,621.17 ms (ratio
  0.58), zip-ok and tf-ok true. (Remaining transfer rows were
  still printing at this bank; they land in the final census
  collection.)
- **S1 (the pipe) — CORRECT, SLOW; both speed gates FAIL on all
  four corpora.** equal ✓ everywhere; claims == segments
  everywhere (5/2/2/4); wire < image everywhere (319,572<346,952 ·
  2,481,049<3,677,808 · 1,817,243<2,167,976 · 4,402,009<5,162,744).
  Receiver ns/block: map 5,352 · vec 709 · records 943 · messages
  1,067 vs the ≤500 gate — FAIL (1.4×-10.7× over). End-to-end
  send+receive vs shipped totals: map 840.28 vs 1,539.74 (1.83×) ·
  vec 40,560.17 vs 28,645.78 (0.71×) · records 14,202.13 vs
  8,371.81 (0.59×) · messages 58,822.93 vs 45,284.57 (0.77×) vs
  the ≥20× gate — FAIL; on three corpora the stream is SLOWER
  than the shipped transfer. The sender is 98-99.9% of the total
  (348-827 µs/block): the pipe's receiver is roughly right; its
  sender is the shipped walk's cost plus emit churn.
- **S2 (partial: map + vec; records/messages OOM'd in
  make-heap-region mid-sweep — re-run filed as a leaner leg)**:
  map budgets — 64 KiB: 8 segments / wire 319,608; 256 KiB: 5 /
  319,572; 1 MiB: 5 / 319,572 (segment count saturates at the
  class count; budget only matters below ~256 KiB). Map
  thresholds: 0 runs at 8/16/32 (no scalar runs in a HAMT graph).
  Vec thresholds: 3,125 runs at every threshold; wire 2,481,049 →
  1,621,674 with runs (1.53× from runs alone; 2.27× vs the image
  3,677,808). Fragmentation: map 0.0 on all classes; vec class 0
  measured -0.375 (the post-receive domain's largest run EXCEEDED
  the fresh control's — no fragmentation debit; the control
  domain is not empty, it holds its seed), class 2: 0.0.
- **S0 profile (local, steady state, the failure's shape)**: the
  cursor walk on map-10k costs ~103 µs/block locally (245 ms
  after 10 warmups, min of 5; send-stream 1,164 ms). Ablation:
  a bare fetch+scan traversal with NO seen-map and NO frame
  machinery costs the same as walk-count (269.9 vs 266.8 ms) —
  the bookkeeping is free; cursor-fetch is ~1.4 µs/block; the
  cost is transfer/block-scan itself, the very scan the shipped
  walk runs. The S0 premise — that a raw cursor over the same
  scan beats the generic walk 10× — does not hold: the scan IS
  the walk's cost. J's s0 leg (queued) gives the canonical
  same-box ratio; the gate (≥10× shipped detach AND ≤5× of @a
  16.06 ms on map) is expected to FAIL on both bars.
- **Hatch git writes are EPERM-blocked at this bank** (the
  platform write fault: shell/python writes land, git's
  link-based object writes are refused; it flip-flops and
  cleared earlier today). A retry loop is committing the bench
  --only change + this STATUS when it clears; the s2b leg files
  after that bundle ships.


## CLOSE-OUT — judged from the J legs at 39dc57e (coordinator, 2026-11-12 19:5x ET, logs read firsthand)

All legs HEAD-verified at 39dc57e (the cross-edge fix tip). The spike is
CLOSED as a judged result: the pipe is correct and direct; the speed
gates are missed on the sender, and that miss is the finding.

S0 — GATE MISSED. The S0 bench leg itself errored at its entry (the
walk-count path called -root-header-off on a plain PersistentHashMap)
— formally NOT-RUN as a leg. The substance is judged from S1's send
costs (walk+emit): 828 / 40,487 / 14,174 / 58,747 ms against the
shipped detach walk's 1,831 / 37,375 / 19,770 / 45,071 — 2.2× / 0.92× /
1.4× / 0.77×, against a ≥10× gate, and nowhere near within 5× of @a
(16.06 / 51.02 / 71.97 / 95.64 ms). The cursor walk premise, as
implemented, does not deliver an order of magnitude.

S1 — CORRECTNESS GATES PASS, SPEED GATES MISSED. Values equal on all
four corpora; allocator claims == segment count on all four (5 / 2 /
2 / 4 claims for 2,380 / 103,229 / 30,325 / 70,997 blocks).
Receiver ns/block: map 5,352 · vec 709 · records 943 · messages 1,067
vs the ≤500 gate — missed (map 10.7×, the rest 1.4–2.1×) — and vs
shipped place 15,998 / 23,001 / 29,860 / 15,275: 3–32× better.
End-to-end transfer vs shipped totals: 1.83× / 0.71× / 0.59× / 0.77×
vs the ≥20× gate — missed; the total is send-dominated on every
corpus (send is 98%+ of the stream total on vec/records/messages).

S2 — KNOBS ANSWERED. Wire ≤ image on all four corpora (319,572 ≤
346,952 · 2,481,049 ≤ 3,677,808 · 1,817,243 ≤ 2,167,976 · 4,402,009 ≤
5,162,744). Typed runs fire on vec (3,125 runs at every threshold):
wire 2,481,049 → 1,621,674 (−35%), receiver 164.2 → 76.2 ms at
threshold 16 (best of 8/16/32, ~2.2×). Segment budgets: 1 MiB
receives fastest on both swept corpora (map 9.9 ms vs 29.9 at 64 KiB;
vec 63.4 vs 129.9), wire flat across budgets — goldilocks leans big.
Fragmentation: map 0.0 on every class; vec class-0 −0.375 (the
infilled domain's largest free run exceeds the fresh control's) —
infill is no worse than fresh on this evidence.

S3 — LANDED IN TREE. transfer! delegates to stream-transfer! behind
the existing API with the detach+zip fallback kept; the per-segment
checked door is in; the door caught a real cross-edge defect on its
first full-suite run (send-stream marked blocks at enter time and
emitted the in-flight marker as an index), fixed at 39dc57e with a
post-order correction. Suites at the tip: green modulo the standing
Z1.7 image-read-speed red (present at every pin; the suites leg's
overall=1 is that red alone — chunk 354, check 73, stream 30
assertions green in the leg).

NAMED NEXT COST (not attacked): the sender's per-block emit work —
index table, byte copy, pointer rewriting at emit time. The receiver
proves the shape is right; the walk premise needs its emit side
rebuilt, or the walk itself replaced, before the end-to-end gate can
move.

## Sender rebuild (Nov 12 2026, coordinator personally)

The named next cost is attacked. Diagnosis, measured on hatch: fetch
~0.13 us/block and block-scan ~0.4 us/block, while the shipped walk
ran 87-158 us/block — a stack sampler showed the walk drowning in
java.lang.reflect.Method.copy. The shipped walk's per-block frame
reads went through reflective aget on Object-typed deque pops, its
index was a HashMap with boxed Long/Integer, and its cursor fetch's
arraycopy was reflective. Rebuilt in eve.flat.stream only: a
primitive frame stack (parallel long/Object arrays behind one public
cells field — deftype mutable fields are package-private and compile
to exactly the reflective lookups being removed), an open-addressing
offset->index table (keys/vals in primitive arrays, xor-folded
multiplicative hash), and a fully typed cursor. block-scan remains
the slot resolver (shared with detach, correctness by construction);
the wire format is unchanged.

Proof, same JVM, old (git HEAD) and new senders loaded side by side
on identical pinned values: wire bytes BYTE-IDENTICAL (sha256) on
map-2000, records-500, a cross-edge shared-structure value, and
vec-20000, plain and with {:run-threshold 16 :budget 262144}; walk
block/byte/per-class counts equal; the index growth path past
50k blocks runs clean. Speed, warm interleaved min-of-2/3, hatch
(load ~7; absolutes are this-box, ratios are the claim):
vec-20000 (20,647 blocks): walk 2,413 ms -> 8 ms; send 10,194 ms ->
361 ms (17.5 us/block). vec-2000 (2,067 blocks): walk 219 -> 12 ms;
send 777 -> 25 ms. First rebuild draft still read its array cells
through unhinted bindings (reflective aset/aget); the sampler named
idx-put/frames-push, hints fixed, hot path now compiles with zero
reflection warnings (two pre-existing singleton warnings remain:
class-access, once per class; receive-side line 686, untouched).
Suites: eve.flat.stream-test 7 tests / 30 assertions 0F/0E;
eve.flat.transfer-test 147 pass / 0F / 1E — the error is
transfer-cross-process, java.io.IOException: Operation not permitted
(the child JVM's mmap; hatch's documented sandbox-wide FileChannel.map
EPERM; the test exercises only the shipped detach/place-image! path).
Remaining sender cost, named: block-scan's per-block boxed result
(map + vector of vectors) and the kept-bytes copy; send at
12-17 us/block vs the receiver's ~1 us/block.
