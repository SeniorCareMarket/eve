# Static Eve — the research lane's deliverables

A research lane, not a feature: verdicts with evidence, and a plan. Nothing under `src/` or `adapters/` is changed.

| Read | For |
|---|---|
| `IMPL-PLAN.md` (D5) | what to build, in what order, the probe that closes each phase, what is decided, and the five forks that are the operator's |
| `ASSUMPTIONS.md` (D1) | ten premises that died at the pin, and the 18 assumptions with verdict, evidence and provenance |
| `FORMAT-V1.md` (D4) | the format: container, scalars, packed nodes, columns, types, the image, validation, evolution |
| `BLIND-SPOTS.md` (D3) | the twelve questions nobody had asked, and the ValueScript aside assessed |
| `CENSUS.md` (D2) | the map to the file:line census in `census/` and `research/`; the allocator surface; tree differences; defects found on the way |
| `MEASUREMENTS.md` | every figure, with the probe that produced it |
| `probes/` | the probes' source and raw output; `probes/eve-cp.sh` is the classpath they ran on |
| `STATUS.md` | the lane's log |

Pins: `SeniorCareMarket/eve` `4a92129`; `johnmn3/atom.broker` `163cb656c`. Everything measured ran on one JVM
(OpenJDK 21.0.12, Clojure 1.12.0) on a 4-core box, 2026-11-11 and -12 ET.

## The findings in eight lines

1. Eve's own types already run over one append-only byte buffer, unchanged, on every host whose instances carry
   their slab I/O: an old `(buffer, length, root)` view stays equal, truncatable and hash-stable under appends.
   One type's read path (the list's seq) allocates and must be fixed.
2. Leaving a domain as a block image is 2–8× `eve->clj`; entering one from an image through a real `swap!` is 3–45×
   storing the plain value — and the commit's holder count, not placement, is then nearly all of the cost.
3. Neither allocator can take more than one block per call. A run claimed per size class is the missing entry.
4. Eve's existing byte forms are not a wire format: the flat codec is the key encoding, 1.8–3.7× Transit
   MessagePack, with no bytes scalar and no validation; a block image is 2.6–7× EDN.
5. A packed chunk — a symbol table, shapes, typed columns, Eve's scalar bytes verbatim — is 0.55–0.86× Transit
   MessagePack on Eve-shaped data, decodes 3–5× faster, scans a column at 1.4 ns a row and reads one cell in 88 ns.
6. 64 KiB holds as a chunk target: within about 1 % of one big chunk on rows, the best size on datoms because
   every offset inside it is 16 bits; compression belongs to a run of chunks, not to one.
7. "The 1 MiB Eve page" is the kernel's region, not canonical Eve's allocator; 15 chunks fit one, not 16; the
   kernel's way of taking bytes in without a copy is page adoption, beside its allocator.
8. `IPlain` is not the seam (fifteen paths go round it), type ids are name-derived, not expansion-order, and
   constructing outside a swap does not throw at this pin.
