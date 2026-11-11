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
