# Static Eve — research lane STATUS

Lane: validate/kill the static-Eve assumptions, census the integration surface, produce the impl plan
(deliverables D1–D5 of the research plan). Branch `claude/sweet-carson-6pt29d`. All times US Eastern.

## Pins (recorded 2026-11-11 2:41 PM ET)

| tree | commit | note |
|---|---|---|
| SeniorCareMarket/eve | `4a92129` (main tip, 2026-11-09 10:11 PM ET, release 0.9.3.1) | the design tree |
| johnmn3/atom.broker | `163cb656c` (main tip, 2026-11-11 1:58 AM ET) | VS kernel (`vs/`); **no `eve/` subtree at this pin** |
| johnmn3/valuescript | no commits on any branch | empty repository |

Tools on PATH: OpenJDK 21.0.12, Node v22.23.4, Python 3 (`/usr/local/bin/python3`), Maven 3.9.11. No `clojure`
CLI and no `bb` on the box (Clojure jars fetched from Maven Central for probes). 4 cores, 15 GB.

## Log

- 2:41 PM — pins recorded. Proto-research files (SCOPE.md, SPELUNK.md, FORMAT-RESEARCH.md) are **not in any of the
  three repositories** (searched by name and by content); the lane works from the research plan's own summary of them.
- 2:55 PM — two premises of the plan re-derived and found stale at the pin (corrections banked in D1):
  1. atom.broker main has no `eve/` subtree: commit `163cb656c` "Land the Eve extraction: eve/ subtree removed; Eve
     lives in SeniorCareMarket/eve and atom.broker consumes it as a dep pin (0.9.3.1)". The net/codec surface
     (`src/eve/net/*`, 23 files) is in canonical Eve at the pin.
  2. "The 1 MiB Eve page" is not canonical Eve's allocator unit. Canonical Eve allocates from six fixed size classes
     (32–1024 B) plus a coalescing overflow class (doc/internals.md). 1 MiB appears as `eve.net.pages` `REGION`
     (the network atom's region) and as the JVM heap slab's default capacity per class. Page ownership / return
     queues are the VS kernel's allocator (atom.broker `vs/src/vs/kernel/wasm/alloc.vs`).
- 3:05 PM — census fan-out dispatched (formats; VS kernel; types+deref; byte paths; deftype+columnar).
- 3:10–3:58 PM — firsthand reads: `eve.deftype-proto.{data,alloc}`, `eve.deftype{,.macros,.registry,.slab-runtime}`,
  `eve.retire`, `eve.net.pages`, `doc/{memory-model,internals}.md`, `doc/network-atoms/WIRE.md`; atom.broker
  `vs/docs/{memory-model,extcid-v3-format}.md`. JVM probe harness stood up (Clojure 1.12.0 + test.check + transit
  jars from Maven Central; classpath = deps.edn `:paths` + `test`; `doc/static/probes/eve-cp.sh`).
- 3:58 PM — **P1 PASS 19/19** (`doc/static/probes/p1_bump_sio.clj`, output `p1_bump_sio.out.txt`; JVM 21.0.12,
  Clojure 1.12.0, eve `4a92129`): Eve's own EveHashMap / EveVector / EveHashSet run unchanged over ONE append-only
  byte array presented as an `ISlabIO` (alloc = bump the tail, free = nothing). `(= static plain)` both ways, equal
  `hash`, lookups in place; an update appends and never rewrites a byte below the old length; every earlier
  `(length, root)` view stays equal, truncatable at its own length and hash-stable (property: 80 random maps × 15
  updates); file out → file in → read in place with no transform. Same session, same data: building 100k ints into a
  1 MB-per-class heap slab domain 358.9 ms vs 32.2 ms into the bump buffer; 10k-entry map 121.0 ms vs 23.1 ms — the
  slab allocator, not the encoding, is most of a build. (Diagnostic numbers: the builder used is the path-copying
  one and the slab domain grows by replacement; P3 separates the two.)
- 4:00 PM — session hit the account usage limit; all five census agents stopped before writing a file.
- 5:02 PM — resumed. Census agents resumed from their saved context with an instruction to bank early.
- 5:02–5:45 PM — P3 (transformation costs), the packed-codec prototype; census agents banked partial files.
- 5:45 PM — the usage limit cut the lane a second time (all five agents). Lesson applied: fresh, narrow agents that
  bank after each section instead of resuming large contexts.
- 10:49 PM — resumed. Census files read; four gap-fill agents run as one workflow (deref/STM/threads; digests,
  defenses, fixtures; the kernel's remainder; format mechanism cards).
- 11:00 PM – 12:35 AM (Nov 12) — P2 (sizes, chunk sweep), P5 (type generality, 19/20), P4 (access by chunk size), P3c
  (the copier written tight), P6 (one `swap!` end to end; sharing; retention), P6b (column encodings).
- 12:35–1:30 AM — D1–D5 drafted: `ASSUMPTIONS.md`, `CENSUS.md`, `BLIND-SPOTS.md`, `FORMAT-V1.md`, `IMPL-PLAN.md`,
  with `MEASUREMENTS.md` and `README.md`. No stop condition fired (A4, A8, A9 each hold, with the corrections D1
  records).

## Open questions (for the next reader of this file, not for the operator)

- A14 is open until a ClojureScript build packs the same value to the same bytes (plan probe 2.2).
- The count written at placement (plan Phase 1) is unmeasured; M6 is the decomposition that motivates it.
- `pack` from a slab value has no speed figure: only the image's walk was built.
- Nothing ran on a disk domain, on a second host, or in the kernel.
