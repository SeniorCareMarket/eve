# Static Eve — the research lane's deliverables

A research lane, not a feature: verdicts with evidence, and a plan. Nothing under `src/` or `adapters/` is changed.

| Read | For |
|---|---|
| `IMPL-PLAN.md` (D5) | what to build, in what order, the probe that closes each phase, what is decided, the two stop conditions that fired (§0), and the four forks that are the operator's |
| `ASSUMPTIONS.md` (D1) | ten premises that died at the pin, and the 18 assumptions with verdict, evidence and provenance |
| `FORMAT-V1.md` (D4) | the format: container, scalars, packed nodes, columns, types, the image, validation, evolution |
| `BLIND-SPOTS.md` (D3) | the twelve questions nobody had asked, and the ValueScript aside assessed |
| `CENSUS.md` (D2) | the map to the file:line census in `census/` and `research/`; the allocator surface; tree differences; defects found on the way |
| `MEASUREMENTS.md` | every figure, with the probe that produced it |
| `probes/` | the probes' source and raw output; `probes/eve-cp.sh` is the classpath they ran on |
| `STATUS.md` | the lane's log |

Pins: `SeniorCareMarket/eve` `4a92129`; `johnmn3/atom.broker` `163cb656c`. Everything measured ran on one JVM
(OpenJDK 21.0.12, Clojure 1.12.0) on a 4-core box, 2026-11-11 ET.

## Read this before the findings

- **The stop conditions.** A9 ("the allocator can consume pre-mapped spacing") is absent as a present capability
  on both trees. A8 was first reported dead, read as bytes resident in a page uncopied; as the operator has since
  clarified it — a chunk carrying the map by which the allocator zips it into free blocks — it is the mechanism
  this lane measured. What is missing on both trees is one allocator entry, a run claim (`IMPL-PLAN.md` §0;
  `ASSUMPTIONS.md` A8, A9).
- **The review of these documents was cut short** (two of six reviewers, about six minutes each, five errors found
  and fixed — `review/`). The format's soundness as an implementer or an attacker would read it, the kernel
  citations and the plan's thresholds were not independently checked (`IMPL-PLAN.md` R11).
- Nothing ran on a disk domain, in a second process, on a second host, or in the kernel.

## The findings in eight lines

1. Eve's own types already run over one append-only byte buffer, unchanged, on every host whose instances carry
   their slab I/O: an old `(buffer, length, root)` view stays equal, truncatable and hash-stable under appends.
   One type's read path (the list's seq) allocates and must be fixed.
2. Leaving a domain as a block image is 2–8× `eve->clj`; entering one from an image through a real `swap!` is 3–45×
   storing the plain value — and the commit's holder count (≈ 1 µs a block), not placement, is then most of the
   cost.
3. No allocator entry Eve's types use takes more than one block per call (the ClojureScript slab has an uncalled
   `batch-alloc`, not guaranteed contiguous). A run claimed per size class is the missing entry
   (3–9× per-block allocation in a probe).
4. Eve's existing byte forms are not a wire format: the flat codec is the key encoding, 1.8–3.7× Transit
   MessagePack, with no bytes scalar and no validation; a block image is 2.6–7× EDN.
5. A packed chunk — a symbol table, shapes, typed columns, Eve's scalar bytes verbatim — is 0.55–0.86× Transit
   MessagePack on Eve-shaped data, decodes 3–5× faster, scans a column at 1.4 ns a row and reads one cell in
   106 ns (the host map: 59). Its weak cases are measured too: 3.04× on an int → int map, 1.10× on plain
   column-major data.
6. 64 KiB holds as a chunk target: within about 1 % of one big chunk on rows, the best size on datoms because
   every offset inside it is 16 bits; compression belongs to a run of chunks, not to one.
7. "The 1 MiB Eve page" is the kernel's region, not canonical Eve's allocator; 15 chunks fit one, not 16; the
   kernel's way of taking bytes in without a copy is page adoption, which stays beside its allocator for good.
8. `IPlain` is not the seam (fifteen paths go round it), type ids are name-derived, not expansion-order, and
   constructing outside a swap does not throw at this pin. For ValueScript, the thread-local question is already
   ruled on (transients, not a nursery); what the kernel lacks is a run entry and a block copier.
