# FLATDOC — documentation lane status

Deliverables on branch `claude/flat-docs`:

- `doc/flat/FLAT.md` — the flat Eve document (the README link target).
- `README.md` — one new section, "The flat aspect", before
  Quick start; link target `doc/flat/FLAT.md` verified in-tree.
  Nothing else in the README touched.

## Sources used (all in-tree unless noted)

IMPL-PLAN.md, FORMAT-V1.md, SPIKE-LOG.md, COLLATION-REPORT.md
(scoreboard + residuals + amendment judgment), and the lane reports
Z1 (B3, Z1.7), Z2 (claim table), Z3 (B1), Z4 (F5 decider), T1 (B4),
B1 (B5), C1 (B6/B7), C3 (B8), C4 (checked gate), H1 (disk), H2
(journal), H3-STATUS (host board), H4 (CLJS image), H5 (sister),
H6 (Dataset bridge), H8 (IO), SISTERFIX-STATUS, RENAME-STATUS.

**F3 measurement: LANDED before writing.** `F3-MEASURE.md` exists
in the f3measure tree (its tip fd51026) and was read in full. Its
chunk-vs-persistent-vs-heap tables, B7 collated re-take, decode
attribution, chunk-count scaling, and its three measured defects
are used in FLAT.md and cited to it. **No F3 rows are pending.**

## Numbers NOT sourced, therefore omitted or hedged

- The operator's research-conversation anchors (today's swap!
  1,190 ms; build+compact+zip+commit 666 ms; laid-out zip+commit
  80 ms; zip ~2.5 ms) appear in no in-tree report as shipped-system
  measurements — omitted. FLAT.md prices the shipped paths from the
  lane/collation benches instead.
- MEASUREMENTS.md is research-prototype data (different tree, probe
  implementations). Its figures (e.g. prototype decode µs/row, the
  0.55–0.86× packed size band) were NOT used for shipped-system
  claims; FLAT.md's Sources section says so.
- B2 (zip committed ≤2× B1) has no standalone shipped time; FLAT.md
  states it through F5's deciding comparison, as collation does.
- Claim's bench measured classes 0/2/3 only; FLAT.md quotes those
  three and the raw primitive's 32.8–50.7 ns/block range — no
  class-1 figure exists and none was invented.
- The B7 lane-pin row view host arm (0.301 µs) and the collated
  re-take's absolutes differ by session; FLAT.md prints both pins
  labeled, per F3's own warning that absolutes are box-bound.

## Accuracy notes

- Every missed gate in FLAT.md is stated as missed, with the gate
  beside the actual: B1, B3, B4 caps, B5 as written, B7 sub-gates,
  B8, Z1.7, Claim class-3.
- The three F3 defects (builder→place key seam, chunk's
  open-checked refusing the writer's own chunks, pack-all
  superlinear) are stated in FLAT.md's Status section and, where
  they qualify a mechanism, at the mechanism itself (Ingest,
  Writers).
- H4.1's residual is stated as the deftype field-read binding;
  the sister load break it was once confused with is FIXED
  (SISTERFIX) and is not listed as open.
