# eve on ValueScript

ValueScript (`hosts.edn :vs`) is a Clojure-family language compiled to WebAssembly and run under
wasmtime. **VS is not in eve 0.9**, and its row is partial — eve's slab I/O,
regions, allocator, codec and HAMT functions run on it, but eve's types (`eve/deftype`, and so the collections and
`eve.atom` built on it) wait on VS's analyzer and macro evaluator (EM4's landing B), and it has no thread or http root
yet. Its adapters require VS's `vs.thread`, `vs.mmap` and `vs.interop` (the unwired `crypto/vs` also needs
`vs.net.bytes`).

```bash
bb test:vs        # VS_CLJ_ROOTS=src:<hosts.edn :vs roots>:adapters/slab-io/test:test vs compile test/eve/runner/vs_main.vs,
                  # then wasmtime run -W threads=y -W shared-memory=y -W exceptions=y -W max-wasm-stack=67108864
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:vs` | **260 passed, 0 failed** (123 s) | 2026-10-22, this tree (the final pass) |
| `bb test:vs` | **260 passed, 0 failed** (97 s) | 2026-10-21 |
| `bb test:vs` | **260 passed, 0 failed** (145 s): hamt-util, the byte fixtures (71 rows, none skipped) and the slab-io conformance test | 2026-10-20, the installed `vs` |
| `bb test:vs` | hamt-util and the byte fixtures: 209 passed; then the slab-io test's first allocation stopped in `ctx-alloc!`'s `letfn` (regions read as nil) — VS's letfn-capture defect, since fixed in VS (L20) | 2026-10-19 |

The runner (`test/eve/runner/vs_main.vs`) runs `eve.hamt-util-test`, the byte-format fixtures and the slab-io feature's
conformance test (`eve.slab-io-test`); a `deftest` runs as its namespace loads. Every other shared test is built on
`eve/deftype` and waits on EM4.

## Performance

The perf suite (`test/eve/perf_suite.cljc`) cannot run on VS: every workload is an eve atom, and `eve.atom` is built
on `eve/deftype`. What is measured (2026-10-17): a write 11.8 ms where the JVM
takes 0.6–1.0 ms; a lookup 0.90 ms against 91 µs; a protocol call 15–17 µs; `mmap/load-i32` 0.28 µs; one slab read
47 µs in total. VS kept 744 KB of its slab per write (291 KB with direct slab I/O), so a program ran out of its 2 GB
after about 2,000 writes. The `locking` table (two threads × 100,000 pins and unpins): 0
violations; the same program without the lock: 35,681.

For comparison, not eve: the suite's workloads written in `.vs` over VS's own atoms and collections, with the suite's
measure loop, run by the installed `vs` (before VS's tally landed; not re-measured since) on the release box at 2:47 PM ET
2026-10-21, beside the hosts' run of 2:37–2:47 PM: a counter swap 19.7 µs, a small deref 0.72 µs, a swap into a
1,000-key map 11.4 µs, a lookup in it 5.3 µs, its deref 0.72 µs (VS's deref copies nothing out), a reset to a
1,000-element vector 5.5 ms, a 200-element sorted-set build 10.8 ms, 64 futures 7.8 ms. One of four runs that day ran out
of VS's slab on the futures row ("no memory left in the slab, building a map"): VS's runtime at the release preparation's start freed
only what an atom's write replaced (VS's docs/architecture.md, Memory). Main's kernel now reclaims by the tally
(VS's docs/memory-model.md, THE TALLY): a unit's toss frees what it allocated and no holder took; a top level outside
every unit still grows.

## How eve is built on ValueScript

| Feature | Adapter | How |
|---|---|---|
| slab-io | `slab-io/vs` | plain functions over linear memory, not macros over a protocol: a domain is a 64-byte record, so a slab offset is an address in two loads; aligned offsets take the atomic op, unaligned a plain load or store; up to 16,383 domains |
| core | `core/vs` | the `eve.host` macros written by one macro (a VS macro cannot call a `defn`); `locking` is a re-entrant lock in a 65,536-slot linear-memory table keyed by the object's address, the owner word taken by CAS, a waiter on `wait32` with a 10 ms timeout |
| region | `region/vs` | a region is an address: a heap region an `mmap/reserve!` window, a mapped one `vs.mmap/map-file!` under vs-eve-host's doorbell; exchange and 64-bit add are CAS loops |
| encoding | `encoding/vs` | UTF-8 and IEEE bits through a 1,000,000-byte linear-memory cell; decimals read from printed digits; no typed arrays |
| int32 | `int32/vs` | `unchecked-int` narrowing; popcount by bit sums |
| process | `process/vs` | thread id is the worker id + 1; no timer, exit hook or files (`file-exists?` answers false); `random-bytes` is `rand-int` (xorshift128, clock-seeded) |
| coll | `coll/protocols` | babashka's protocols: VS's core never reaches a type's interfaces |
| types | `types/vs` | every interface translated onto `eve.core` (VS reads a deftype instance as its own typed map: `count` and `seq` refuse, `get` answers nil, `=` is identity) |
| crypto | `crypto/portable` | `eve.net.portable-crypto` (a `crypto/vs` exists, unwired) |
| slab, codec, completion | `slab/heap`, `codec/byte-array`, `completion/clojure` | as the JVM's |
| thread, http | — | none yet |

## Gotchas

- **`random-bytes` is clock-seeded.** Never mint a group key or anything secret on VS ([SECURITY.md](../../SECURITY.md)).
- **The VS kernel's Ed25519 verifier accepts the identity key** (SECURITY.md).
- eve values answer `eve.core`'s functions, not `clojure.core`'s.
- A heap region throws when no reserved window is left; a mapped region needs vs-eve-host.
- VS's letfn-capture defect (L20) stopped the slab-io test on 2026-10-19; the 2026-10-20 run above passes it — the
  defect is fixed in VS (VS's test/vs/letfn_outer_capture_test.vs).

## Future directions

Coverage, in dependency order:

- `eve/deftype` on VS (EM4 landing B: VS's analyzer lets a namespace replace a macro named `deftype`, its macro
  evaluator runs macro-time atoms and 64-bit words) — this brings the collections, `eve.atom`, and with them the whole
  shared battery and the perf suite.
- A thread root over `vs.thread`, then eve.thread; an http root over `vs.net`.
- `process/vs`'s timer, exit hook and files; real entropy for `random-bytes`.
- Wire or relabel `crypto/vs`.

Optimization:

- Running eve's writes inside a VS unit (task or request), whose toss reclaims temporaries under main's tally; the
  744 KB / 291 KB per write (2026-10-17) is not re-measured on main's kernel.
- Protocol dispatch (15–17 µs a call) dominates a slab read: direct calls for eve's hot protocols (`slab-io/vs` already
  bypasses the protocol for slab I/O).
