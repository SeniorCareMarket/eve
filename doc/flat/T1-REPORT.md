# Lane T1 (Transfer) — REPORT

Branch `claude/static-t1-transfer`. Requirement ids T1.1–T1.6, all `:built`
on the evidence below. Board row B4: complete (see §4).

## 1. What the lane built

Atom→atom transfer of Eve values without a host round-trip:

- A third foreign-value policy, **`:zip`**, in `resolve-new-ptr`
  (`src/eve/atom.cljc`): where `:throw` refuses a foreign slab value and
  `:copy` materializes it through `eve->clj` and rebuilds, `:zip` detaches
  the foreign value to an image and places it into the receiving domain. No
  host collection is built; sharing that exists in the source is preserved
  block-for-block. Nested foreign values inside plain collections are
  pre-walked and replaced with their placed counterparts before the writers
  see them. `:throw` / `:copy` behaviour is unchanged.
- `eve.static/transfer!` — detach the source value (under the source pin
  when the source is an atom) and place it into the target atom, through
  Zip's `place` with its validation-before-claim and the CheckedBytes gate.
- `transfer-direct!` — the same transfer domain-to-domain with no buffer
  between; T1.6 measures it allocating exactly the image route's blocks.
- The lane's own relocating block walk (`eve.static.transfer`) produces the
  FORMAT-V1 §9 image: every reachable block copied once, pointers rewritten
  to buffer offsets, block + relocation tables per `eve.static.contract`.
  A `0x15` process-local reference anywhere in a value is refused by name
  (`:eve.static.transfer/refusal :process-local-reference`).

## 2. Final evidence

Suite round 4, J, HEAD-verified (`t1-suite4.log`, first line
`HEAD=2c6fefc…`, the probe3-fix commit):

- `eve.static.transfer-test`: **6 tests, 150 assertions, 0 failures,
  0 errors** — T1.1–T1.6 all green in one run.
- `eve.static.requirements-test`: 508 assertions, 0 failures, 1 error —
  the error is W0's `api-surface` nil-probe (`(detach nil)` throws the
  protocol `IllegalArgumentException` before lane code runs). Attributed
  to W0, as in every lane's run at these pins; the collation tree carries
  the contract amendment as a flagged commit.

## 3. How the reds resolved (all test-side; transfer code never implicated)

The suite's failure history is a chain of measurement defects in the
lane's own tests, each killed by a staged probe on J:

- **Round 1 (6F/1E)** — the test built both atoms anonymously; anonymous
  atoms resolve through one process-wide cached default domain, so the
  "two atoms" were one domain and every cross-domain assertion measured
  something else (probe P0/P1). Fixed by named separate domains
  (`ea/atom-domain` + `lookup-or-create-mmap-atom!`).
- The cross-process failure was the test receiver's own
  `Path/of` String→URI cast (P3); an in-process place of the identical
  image was clean. Fixed via `java.io.File`.
- **Suite2** re-measured the pre-rewrite tests — a stale bundle voided
  the run; banked as a correction with the discriminator (no HEAD line,
  bundle held the old tip).
- **Suite3 (3F)** — assertion-encoding defects in the rewritten test,
  settled by probe3's four controlled arms (`t1-probe3.log`, HEAD
  75016e3): detach has **zero** effect on its source domain's free
  counts (arm D); transfer counted before any deref reads residual
  **[2 1 0 0 0 0]** vs census (arm T) — the committing swap frees the
  fresh atom's initial root (2 class-0 + 1 class-1) into the same
  delta; the test's old assertion order reproduces **[-1 1 0 0 0 0]**
  exactly (arm R), because deref/equality allocate 3 class-0 blocks in
  the domain they read and the count was staged after them; the writer
  control counted clean against its **own** census also reads
  [2 1 0 0 0 0] (arm W).
- **Correction banked:** probe2's Q2b reading — "a writer store matches
  its census exactly, residual zero" — is **dead**. Under probe3's
  controlled staging the writer route shows the same [2 1] superseded-
  root signature as transfer. The discriminator is the staging. A
  related premise also corrected: sharing is a property of in-domain
  construction (probe2 Q1) — a host-built value stored three times is
  three blocks after writer conversion (census 3330) where the
  slab-built value is one (census 1262); transfer preserves exactly
  the sharing that exists (reach == census on both routes).

## 4. Board row B4 (atom→atom) — complete

At pin a1eaab6 (messages row at ae9366e, reduced config 0 warmup / 1 run,
stated in the row), min of 5 after 2 warmups, all arms read back equal:

| corpus      | transfer!    | ratio vs detach+zip | cap          |
| ----------- | ------------ | ------------------- | ------------ |
| map-10k     | 1,539.74 ms  | 0.93 PASS           | 5 ms MISSED  |
| vec-100k    | 28,645.78 ms | 1.02 PASS           | 26 ms MISSED |
| records-10k | 8,371.81 ms  | 1.10 PASS           | 9 ms MISSED  |
| messages-5k | 45,284.57 ms | 1.73 PASS           | 26 ms MISSED |

`transfer!` ≈ detach+zip composed — no composition tax on any corpus.
The caps are missed by ~300–1000× at these pins because the pins carry
the pre-fix detach walk (Detach's four walk fixes and Zip's later place
work are not merged here) and the walk itself costs ~2.3 ms/block on
message shapes in per-block slab reads. **The caps' verdict belongs to
the collated tree** and must be re-measured there; nothing in this row
stands in for it. The messages corpus's two silent bench deaths were a
bench-config leash kill (24 detach-family calls × ~71k blocks at that
per-block cost exceeds the 3600 s leash), named by in-process probes —
not a corpus or correctness defect.

## 5. A16 — explicit disposition

**The census claim CONFIRMS at this lineage** (probe
`doc/static/probes-t1/a16_half_commit.clj`, run at 7402ef9 + W0 cfa86c6):
a two-ref `dosync` whose second ref is set to a foreign slab value
**half-commits** — the first ref's install lands (ra = 1) and the second
never happens (rb = 0) — because the foreign value throws at store time,
after the first ref has already installed. Mid-commit atomicity against
arbitrary store failures is a property of `eve.stm`'s commit protocol.

What this lane ships against it: under the **`:zip`** policy the
store-time refusal does not occur — the foreign value is zipped into
each cell's domain during the transaction — and T1.3
(`stm-foreign-value-atomic`) proves both refs commit and the zipped
value takes part in STM like any other. **The general defect is a named
residual owned by `eve.stm`, not by T1**; it is reported, not absorbed.

## 6. Named residuals and hand-offs

- **A16 / STM mid-commit atomicity** — §5; owner: `eve.stm`.
- **B4 caps at the collated pin** — owed by collation's scoreboard.
- **W0.6 baseline row** (atom→atom through today's `:copy` as one run) —
  still owed by W0; T1's SPIKE-LOG section does not stand in for it.
- **Collation integration note:** on the collated tree, Transfer's
  raw-byte doors into `place` were dead against Check's CheckedBytes
  gate (each lane's own pin predated the combination). Collation
  repaired it there (both doors vouch through `check/open-checked`,
  collate commit 7f2645a). This tree's suite predates that combination
  and is green as measured; on any future merge of this tip into the
  collated tree, collation's version of those doors wins.
- The walk's per-block cost (~2.3 ms/block on message shapes) is the
  same cost centre Detach's B3 row reports; closing it is a walk/read-
  path question above this lane.
