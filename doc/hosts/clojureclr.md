# eve on ClojureCLR

ClojureCLR (`hosts.edn :cljr`) is Clojure on .NET. Its core is Clojure's, so eve's collections answer `clojure.core`
directly (the JVM's `coll` adapter, through `clojure.lang.RT`); what is .NET's own is the memory and the types. A
region is a `MemoryMappedFile` view or a pinned `byte[]`, and its atomics are `Interlocked` / `Volatile` operations
emitted as IL, because ClojureCLR's by-ref reaches only a local. eve's types are the JVM's interface names translated
to the CLR's at expansion.

```bash
dotnet tool install -g Clojure.Main --version '1.12.*'   # ClojureCLR 1.12.6 (the command is Clojure.Main)
ln -s ~/.dotnet/tools/Clojure.Main /usr/local/bin/cljr   # cljr on PATH
bb test:cljr                             # the battery (CLOJURE_LOAD_PATH=src:<hosts.edn :cljr roots>:test)
CLOJURE_LOAD_PATH=... cljr -m eve.runner.cljr-main perf     # the performance suite
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:cljr` | **202 tests, 4,499 assertions, 0 failures, 0 errors** (188 s, peak 958 MB, the process tree) | 2026-10-21, this tree (the final pass, `doc/release-0.9/logs-2026-10-22/f-cljr.log`) |
| `bb scripts/thread-rendezvous.clj jvm cljr` | 18 of 18 checks (51 s) | 2026-10-22, this tree (the final pass) |
| `bb test:cljr` | **188 tests, 2,907 assertions, 0 failures, 0 errors** (203 s, peak 773 MB) | 2026-10-20, the final sweep, ClojureCLR 1.12.6 on .NET 8 |
| `bb test:cljr` | **181 tests, 2,107 assertions, 0 failures, 0 errors** (199 s) | 2026-10-20, ClojureCLR 1.12.6 on .NET 8 |
| `bb test:cljr eve.vec-retire-test eve.domain-open-test` | **5 tests, 779 assertions, green** — now in the default run (`domain-open-test`'s temp directory needed a `:cljr` arm, as `keyring-test` has) | 2026-10-20 |
| `bb scripts/thread-rendezvous.clj jvm cljr` | 18 of 18 checks | 2026-10-19 |

The runner (`test/eve/runner/cljr_main.clj`) runs the fixtures (71 rows, none skipped), the HAMT helpers, type ids,
the red-black set, int-map, sorted collections, core.rrb-vector's suite, big values, value buffers, the key ring, the
numeric and reference tags, collection hashing, the vector retire walk, transient reclaim, the tally, domain open, and
the whole thread feature
including the network rung. Not run: the four typed-array suites — ClojureCLR has no typed-array adapter yet.

## Performance

<!-- perf:cljr (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 92.1 µs | 16384 | 7.8 |
| `atom-deref-small` | 89.7 µs | 16384 | 5.3 |
| `atom-swap-map-1k` | 33.6 µs | 32768 | 7.2 |
| `atom-deref-map-1k` | 4.2 ms | 256 | 11 |
| `atom-$-get-map-1k` | 25.2 µs | 65536 | 12 |
| `atom-reset-vec-1k` | 9.1 ms | 128 | 6.0 |
| `sorted-set-build-200` | 77.8 ms | 16 | 7.0 |
| `int-map-build-200` | 22.2 ms | 64 | 5.0 |
| `disk-swap-counter` | 98.1 µs | 16384 | 5.6 |
| `thread-submit-64` | 2.4 ms | 512 | 2.4 |

Measured 2026-10-21 10:56–11:06 PM ET on the final tree (`77b649aa5`) by `bb scripts/perf-all.clj`, every host one at
a time; a round of at least 1 s.
<!-- /perf:cljr -->

Reading it: the fastest host outside the JVM and V8 on swaps (92.1 µs, 33.6 µs for a 1,000-key map's), and faster
than ClojureScript on encoding a 1,000-element vector (9.1 ms against 11.5 ms). A small deref (89.7 µs) costs as much as
a swap — not investigated. The CLR resolves untyped interop by reflection as the JVM does; eve on ClojureCLR has not been scanned
for it (the JVM's scan took its swap from 169 µs to 15 µs).

What sets ClojureCLR's numbers: start-up (the CLR loads and ClojureCLR compiles eve's sources: ~17 s of a battery's
start); each atomic is a `DynamicMethod` call (four to six IL instructions, but a delegate invocation); integer
arithmetic goes through `int32/portable` (quot / mod), because ClojureCLR's fixed-width casts cannot be relied on to
narrow.

## How eve is built on ClojureCLR

| Feature | Adapter | How |
|---|---|---|
| region | `region/clr` | `ClrRegion` over a `MemoryMappedFile` view or a `GCHandle`-pinned `byte[]`; atomics are `Interlocked` / `Volatile` emitted as IL (`DynamicMethod`: .NET's atomics take `ref int`); futex(2) through libc's `syscall()` by `calli`. Measured exact across two processes on one `MAP_SHARED` file |
| types | `types/clr` | the JVM's interface names translated against the CLR's types: `Object`'s methods become `Equals` / `GetHashCode` / `ToString`, `Iterable` becomes `IEnumerable`, `java.util.Map` a read-only `IDictionary`; interfaces the CLR lacks are dropped; a method overloaded by return type is defined once per type, hinted |
| coll | `coll/jvm` | the JVM's adapter: `clojure.core` through `clojure.lang.RT` |
| int32 | `int32/portable` | quot / mod arithmetic |
| encoding | `encoding/clr` | .NET's `System` |
| process | `process/clr` | `DateTimeOffset`, `ManagedThreadId`, a background heartbeat thread, `ProcessExit`, `FileMode.CreateNew`, `RandomNumberGenerator` |
| thread | `thread/clr` | a background `Thread` per thread; a `PersistentQueue` mailbox in an atom under a monitor (`Monitor.Wait` / `Pulse`: `BlockingCollection`'s timed take needs an out parameter); `:wake :futex` |
| crypto | `crypto/clr` | `SHA256.HashData`, `HMACSHA256.HashData`, AES-256-CTR as `Aes.EncryptEcb` over counter blocks (.NET has no CTR mode); XXH3 and Ed25519 are the portable code |
| http | `http/clr` | `HttpClient` (`Send` synchronous, `request-all` by `SendAsync`) |
| core, completion, slab, slab-io, codec | `core/clojure`, `completion/clojure`, `slab/heap`, `slab-io/protocol`, `codec/byte-array` | as the JVM's |

## Gotchas

- **Linux x86-64 in effect.** The region loads `libc.so.6` and calls futex as syscall 202 (x86-64's number).
- **No Lustre regions** (`:lustre? true` throws `NotSupportedException`).
- **No typed arrays**: `eve.array`'s constructors answer nil on ClojureCLR.
- **Ed25519 is the portable code** (.NET has no Ed25519): a signature by it took 0.94 s on babashka (2026-10-20); not
  measured on ClojureCLR.

## Future directions

Optimization:

- The atomics are `DynamicMethod` delegates; a small precompiled assembly with `Interlocked` over a pointer, called
  directly, would drop the delegate hop — after measuring what the hop costs against the IL it runs.
- A native `int32` adapter (`unchecked-int` where ClojureCLR's casts narrow, measured per operation) instead of the
  portable arithmetic.
- Ed25519 through libsodium or OpenSSL by P/Invoke instead of the portable code.

Coverage:

- A typed-array adapter (.NET arrays and `Span<T>` over the slab).
- Futex by `RuntimeInformation.ProcessArchitecture` (202 on x86-64, 98 on arm64) so the region runs on arm64 Linux.
- A NuGet package (RELEASE-CHECKLIST, after 0.9).
