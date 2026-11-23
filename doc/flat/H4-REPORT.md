# Lane H4 — the ClojureScript image · REPORT

Branch `claude/static-h4-cljs-image`. All evidence from the venue (J),
job-agent lane `static-eve/cljs-image`, logs in `~/static-eve-results/`
on J; every run's HEAD is in its job log. All times US Eastern,
2026-11-12.

## Declaration

- **H4.2 `run-wastes-nothing` — :built.** The module slab's run claim
  (`batch-alloc-run`, wired as CljsSlabIO's `ISlabRunAlloc`) satisfies
  Z2.2's pattern over class 0 on Node: fill exactly 512, free
  {37..76} ∪ {5, 90, 100}, a claim of 41 takes nothing and claims
  nothing, a claim of 40 takes exactly the run from block 37.
- **H4.1 `image-reads-every-type` — stays :planned, on one named
  Eve-core residual** (the Dataset row; §3.9 below). Every other row is
  green on Node, including the byte-identity arm: Node's image of
  `{:a [1 2 3] :b {:c "x"}}` equals the JVM's image of it byte for
  byte (512/512 bytes). The image bytes for a Dataset are also proven
  correct field-by-field; the block is in `eve-deftype`'s CLJS
  instance binding, not in the image. Flipping H4.1 while its named
  test carries a red row would be the diagnostic-as-acceptance
  overclaim; the lane instead hands the residual over convicted, the
  Z1.7 shape.

Final suite (at 3b74f4d; product/test code identical to cfa14f8, later
commits are probe-only and these docs): **4 tests, 31 assertions,
3 failures, 0 errors** — all three failures are the Dataset row
(`image_node_test.cljs:79, 80, 113`).

## What the lane stands on

Inherited: image.cljc's `:cljs` stubs repaired (`grow-ints`,
view `-block-size` binary search, builder growth); `batch-alloc-run`
in `eve.process-slab` (consecutive-run claim from batch-alloc's
machinery; a CAS lost mid-claim frees the claimed prefix) wired as
CljsSlabIO's `ISlabRunAlloc`; the Node runner + suite; L1 merged at
7973f05 for the list rows (L1's lazy host seqs — before the merge,
reading a list through the view threw the frozen-view alloc! refusal,
L1's defect on a second host).

## Defect ledger (every claim re-derived at the pin before any fix)

1. **`(byte-array n)` in shared code is an undefined call on CLJS —
   PRODUCT, fixed.** image.cljc:442 (detach's buffer) and sio.cljc:154
   (builder-sio) called `byte-array` unqualified; this build's analyzer
   names both as undeclared Vars (`eve.static.image/byte-array`,
   `eve.static.sio/byte-array` in the compile warnings), i.e. every
   CLJS detach and every builder-sio threw at runtime — this was the
   open "scalar-detach TypeError" (no stack in suite output). Both
   sites now reader-conditionalize to `js/Uint8Array.`, the files' own
   `:cljs` convention. Proven by probe arm P-B after the fix:
   value->scalar-block! / detach-image / open+read all OK
   ("hello scalar" round-trips).
2. **Detach's pad bytes carried source-block slack — PRODUCT, fixed.**
   The same-bytes arm differed in exactly 1 of 512 bytes: byte 340,
   the alignment pad after the last block (JVM 0x00, Node 0x05).
   Detach sizes a block's content _after_ copying a window, so window
   slack lands in the inter-block and pre-table gaps; the byte is a
   function of the source slab, not the value. detach-image now
   zero-fills every gap at table emission (shared code, both hosts),
   so an image's bytes are a function of the value. The arm passes.
3. **`eve.static`'s delegate threw not-built unconditionally on CLJS —
   PRODUCT gap, fixed for the lanes in this tree.** The `:cljs` branch
   had no resolution at all. It is now a static table wiring
   `eve.static.image/detach` and chunk's pack/open/bytes (no cycles:
   no lane namespace requires `eve.static`). place / transfer! /
   open-checked keep the typed not-built answer in this tree — their
   namespaces are not here; collation wires them when those lanes
   merge into a tree with a CLJS build.
4. **H4.2's fill could never fill — TEST PREMISE, fixed.** The fill
   looped `-sio-alloc!` until refusal; the module slab's size-routed
   alloc _spills to the next class on exhaustion by design_
   (process_slab.cljs `alloc`: "grow on OOM, spill to next class").
   Measured: the "class 0 fill" handed out **32,256 blocks = the sum
   of all six classes** at the suite geometry, the pattern frees then
   landed in other classes and the run claim rightly found class 0
   still full. The fill now goes through the slab's own per-class
   allocator (`ps/batch-alloc`, the machinery the run claim is built
   from). Probe arm P-C, independently of the suite: fill 512, all
   class 0; pattern free exactly; claim 41 → nil; claim 40 → first
   block 37, count 40.
5. **The async `:each` fixture zeroed the suite — TEST MACHINERY,
   fixed.** With an async fn fixture the run reported "Ran 0 tests"
   (the summary reporter exits on cljs.test's synchronous pass; the
   pre-fixture run at 10d6788 ran 4 tests). The fixture is now the
   synchronous `ps/reset-all-slabs!` — bitmaps and descriptor tables
   zeroed, free counts restored: the CLJS form of Z1/Z2's fresh heap
   domain per test, at the runner's one geometry.
6. **The probe's nested `->` chains mis-compiled — PROBE MACHINERY,
   fixed.** First probe run died on `.catch is not a function`; the
   compiled JS on the venue showed `}).catch());` — `.catch` invoked
   on the handler fn, no argument. Probe main rewritten flat (named
   promise, statement-level handlers); P-C rides the same
   reset-all-slabs! as the fixture.
7. **The JVM reference leg failed twice on its invocation —
   MACHINERY, fixed.** `-e "(h4-ref)"` evaluates in `user`; the fn is
   `h4-ref/h4-ref`, and the generator script's own docstring named
   the broken invocation (the docstring was the defect, copied twice).
   Working form: `clojure -M -i doc/static/h4_jvm_reference.clj -e
   "(h4-ref/h4-ref)"` → REF hex, **LEN: 512**.
8. **Corrections banked.** (a) The first embedded reference was
   hand-copied from a truncated tool result (944 of 1,024 hex chars);
   the reference now in the test was taken byte-exact from the venue
   log file and length-checked against its LEN line. (b) The first
   P-D probe arm built its dataset with an invented
   `{:columns :types :data}` arg, which this tree's constructor reads
   as a 3-column col-map — the probe's own defect; the arm was rebuilt
   with the suite's own construction before it convicted anything.
9. **THE RESIDUAL — Dataset instances read empty over a static view
   on CLJS: Eve-core `eve-deftype`, NOT the image.** Provenance
   (probe arms P-D/P-E at f903bbd/3b74f4d): the detached image of a
   2-column dataset carries 11 blocks / 10 relocations; manual
   `slab-read-serialized` over the opened view at the root's slots
   decodes col-order `[:price :qty]` and the col-map with both
   EveArrays intact — the image is correct. But the Dataset instance
   that `image-value` constructs reads col-map as {} and its column
   as []. The compiled `eve.dataset.js` (venue build, lines 319–320)
   shows why: the generated serialized-field reads call
   `slab_read_serialized(eve.process_slab.cljs_sio(), offset, …)` —
   **the module slab, hardcoded** — in place of the sio the instance
   was constructed over. That substitution is a documented design
   assumption (serialize.cljc:122: "On CLJS the sio is the module-slab
   token"), true until the static view made a second sio on CLJS.
   The fix belongs to the Eve line: eve-deftype's CLJS constructors
   and field accessors must honor the passed sio, as the JVM's do.
   Same class as Detach's Z1.7 residual (the `JvmSlabCtx` gate in
   alloc.cljc): a foreign-sio mechanism Eve core doesn't yet have.
   Until it lands, H4.1's dataset row is the suite's only red.

## Notes for collation

- image.cljc changed since Z1's tip in two shared-code places: the
  `:cljs` byte-array conditionalization (§1) and the pad zero-fill
  (§2, both hosts — JVM images of values whose source slack was
  nonzero change bytes in previously-unspecified pads only).
- eve.static.cljc's `:cljs` delegate table (§3) wants the remaining
  verbs wired when place/transfer/check are present.
- L1 is merged here (7973f05); requirements.edn carries L1's flips
  and H4.2's; H4.1 stays `:planned` per §3.9.
- No journals, liveness scans, header counters, or arenas were added
  anywhere in this lane; the run claim is bitmap machinery inside the
  slab's own allocator, and detach/open remain pure functions of
  atoms' committed values.
