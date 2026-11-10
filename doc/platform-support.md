# Hosts and platform support

eve's shared code (`src/`) has no reader conditional keyed by a host and no host predicate, held at 0 by
`scripts/check-host-leak`; one host-only file remains, the legacy `src/eve/shared_atom.cljs` (`.host-leak`:
`host-files 1`). A **host** is `src/` plus a list of adapter roots, its row in [`hosts.edn`](../hosts.edn).
Hosts with the same capability share an adapter. This page is the matrix of the eleven hosts `hosts.edn` ships: what
runs each one, its adapters, its test runner, what it lacks, and what was run for 0.9.
What 0.9.3.1 ran, and the per-host journal matrix it adds, are in [0.9.3.1](#0931) below.

For how to add a host, see [adopting-eve-minimal.md](adopting-eve-minimal.md); for the three adapter tiers,
[doc/adopter-tiers.md](adopter-tiers.md).

## The hosts

| Host (`hosts.edn` key) | What runs it | Runner (`bb.edn` / `deps.edn` / `package.json`) | What it lacks in 0.9 | The final pass on the final tree (2026-10-21/22 ET) |
|---|---|---|---|---|
| JVM (`:jvm`) | JVM Clojure | `clojure -M:jvm-test` | — | **green: 546 tests, 9,615 assertions** (136 s) |
| ClojureScript on Node (`:cljs`) | shadow-cljs builds on Node; disk domains through eve's N-API addon | `npm run test:compile`, then `node target/eve-test/all.js <suite>` | — | **green**: `all` 482 tests, 34,980 assertions; `net` 62 / 1,429; `thread-domain` 12 / 146; `thread-fold-group` 3 / 49; `slab-grow` 105 / 28,902 |
| ClojureScript in a browser (`:browser`) | shadow-cljs; Web Workers; the page must be cross-origin isolated (COOP + COEP) for SharedArrayBuffer | `bb test:browser` (headless Chromium) | no disk domains; crypto is the portable code (WebCrypto is promise-only) | **green: 48 tests, 633 assertions** (headless Chromium) |
| babashka (`:bb`) | babashka (sci) | `bb test:bb` (named namespaces run alone) | — | **green: 247 tests, 4,748 assertions** |
| nbb (`:nbb`) | nbb (sci on Node) | `bb test:nbb` | — | **green: 242 tests, 4,617 assertions** |
| ClojureCLR (`:cljr`) | ClojureCLR on .NET | `bb test:cljr` | typed arrays (`eve.array`, datasets, tensors) | **green: 202 tests, 4,499 assertions** |
| basilisp (`:lpy`) | basilisp on CPython | `bb test:lpy` | typed arrays; no parallelism under the GIL (`thread/python` declares `:parallel false`) | 180 tests, 4,360 assertions, 0 failures, **5 errors** — the network rung's domain pool late in the full run, and four errors its failed tests' tickers caused by beating on (9.34); `eve.thread-net-test` and `eve.thread-fold-group-test` alone: green |
| jolt (`:jolt`) | jolt on Chez Scheme | `bb test:jolt` | typed arrays; http (no network atom); crypto is the portable code | **green: 199 tests, 4,479 assertions** |
| clojerl (`:clje`) | clojerl on the BEAM; eve's NIF built on first load | `bb test:clje` | typed arrays; the codec cannot hold a char, a date finer than a second, or a keyword past 255 characters (fixture rows skipped) | **green: 198 tests, 4,400 assertions** |
| ValueScript (`:vs`) | `vs compile`, then wasmtime | `bb test:vs` | eve's types (`eve/deftype`, the collections built on it), typed arrays, threads (no thread root), http, native crypto, entropy (`random-bytes` is clock-seeded); its adapters require ValueScript's `vs.thread`, `vs.mmap` and `vs.interop` | 260 passed, 0 failed (the three namespaces it compiles); not part of 0.9 |
| glojure (`:glj`) | `glj-eve`: glojure v0.7.22 with eve's Go packages, built by `impls/glojure/build.sh` (`bb glj-eve`) | `bb test:glj` | typed arrays; crypto is the portable code | **green: 182 tests, 4,458 assertions** |

Also in `hosts.edn`, but not a shipped host: **`:bb-minimal`** is babashka on the minimal and inbuilt adapters only
([adopting-eve-minimal.md](adopting-eve-minimal.md)). `bb test:bb-minimal` runs every shared namespace but eve.net's
(which need the http feature): **green, 242 tests, 4,623 assertions** (the final pass, 2026-10-21 ET).

Each host's runs, its performance (the suite every host runs, `bb scripts/perf-all.clj`), how eve is built on it
and its gotchas are on its page in [hosts/](hosts/README.md). Earlier runs of each battery are logged in
[testing.md](testing.md), with their dates.

### What "supported" means

A host is **supported** in a release when its `bb test:<host>` line (or the JVM's and Node's) is green in that
release's report. Otherwise it is **experimental**: present, with no green run recorded for the release
. By that rule, the 0.9 preparation's final sweep has
every shipped host green but basilisp, whose full run errs at the network rung's domain pool late in the run (9.26) and
four times after it (9.34: the failed tests' tickers run on) — each of those namespaces alone green;
ValueScript shipped in 0.9 as a partial seat (the changelog's 0.9.0 section carries it as part of the release): its three namespaces pass (260 / 0), but eve's types, `eve.atom` and `eve.stm` wait on VS machinery, and it remains the only host with no journal adapter (see [0.9.3.1](#0931)).

## 0.9.3.1

The 0.9 table above is the 0.9 preparation's final pass and stands as its record. What this
release ran on its integrated tree (the ship-packet gate, 2026-11-09 ET; verdict file in the
development record):

| Host | 0.9.3.1 gate |
|---|---|
| JVM | **green: 659 tests, 10,730 assertions, 0 failures, 0 errors** (630 s) |
| babashka | **green: 251 tests / 4,678 assertions** (30 namespaces) **+ 42 / 890** (the remaining 9), 0 / 0; `eve.thread-net-test` and `eve.aegis.rsabssa-test` excluded by name (the http feature; an RSA-2048 row past 30 min) |
| jolt | journal leg **green**: journal created, 5 fsync, fresh-process read exact (the JVM's journal leg likewise: 8,272 B, 5 syncs, read exact) |

Per-host work this release is on each host's page and in the changelog: jank's compiled form
proven at the pin (AOT contract 5/5, 0.317 s cold median), glojure's `*compile-files*` panic
fixed, compiled-ClojureScript persistent writes proven, clojerl's first `swap!` fixed,
basilisp's crypto and serialization fixes. `eve.stm` is shared code and ships on every host
but ValueScript.

### Journal support per host

The disk tier is journaled by design in 0.9.3.1; a host's journal is its `eve.journal-file`
adapter (`adapters/journal-file/<impl>`). A host with no adapter cannot carry a journaled disk
domain.

| Host | journal-file adapter | State in 0.9.3.1 |
|---|---|---|
| JVM (`:jvm`) | `java` | yes — gate journal leg green |
| babashka (`:bb`) | `java` (the JVM seat's) | yes |
| jolt (`:jolt`) | `jolt` | yes — gate journal leg green |
| glojure (`:glj`) | `glojure` | yes — new in this release; the A3 probe is unchanged-exact on glj |
| nbb (`:nbb`) / ClojureScript on Node (`:cljs`) | `node` | yes |
| ClojureCLR (`:cljr`) | `clr` | yes |
| basilisp (`:lpy`) | `python` | yes |
| clojerl (`:clje`) | `beam` | yes |
| jank (`:jank`) | `jank` | adapter in tree; its Mac proof is outstanding (venue-blocked, job file prepared) |
| browser (`:browser`) | — | no disk domains on this seat |
| **ValueScript (`:vs`)** | `unsupported` | **no working adapter — the only host with none; its row carries the `unsupported` journal-file root** |

## Adapter roots per host

Generated from `hosts.edn` on 2026-10-20. `a+b` means both roots are on the classpath: ClojureScript and the browser
carry the JVM's adapters for the compiler's own macro-time Clojure. `—` means no root.

| family | jvm | cljs | browser | bb | nbb | cljr | lpy | jolt | clje | vs | glj |
|---|---|---|---|---|---|---|---|---|---|---|---|
| region | jvm-varhandle | js+jvm-varhandle | js+jvm-varhandle | jvm-nio-lock | js | clr | ctypes | jolt-ffi | beam | vs | glojure |
| encoding | java | js+java | js+java | java | js | clr | python | java | beam | vs | go |
| process | java | node+java | node+java | java | node | clr | python | java | beam | vs | go |
| types | jvm+js | jvm+js | jvm+js | bb | js+nbb | clr | basilisp | jvm+js | beam | vs | glojure |
| core | clojure | clojure+cljs-compiler | clojure+cljs-compiler | clojure | clojure | clojure | clojure | clojure | beam | vs | clojure |
| completion | clojure | clojure | clojure | clojure | clojure | clojure | basilisp | jolt | — | clojure | glojure |
| coll | jvm | cljs+jvm | cljs+jvm | protocols | nbb | jvm | basilisp | jvm | beam | protocols | protocols |
| slab-io | protocol | protocol+cljs-compiler | protocol+cljs-compiler | protocol | protocol | protocol | protocol | protocol | protocol | vs | protocol |
| slab | heap | module+heap | module+heap | heap | module | heap | heap | heap | heap | heap | heap |
| int32 | jvm | js+jvm | js+jvm | jvm | js | portable | portable | jvm | portable | vs | portable |
| codec | byte-array | js+byte-array | js+byte-array | byte-array | js | byte-array | byte-array | byte-array | byte-array | byte-array | byte-array |
| crypto | java | node | portable | java | node | clr | python | portable | beam | portable | portable |
| thread | jvm | node+jvm | browser+jvm | jvm | node | clr | python | jvm | beam | — | glojure |
| http | java | node | browser | java | node | clr | python | — | beam | — | go |
| http-server | jdk | node | — | httpkit | — | — | — | — | — | — | — |
| typed-view | — | js | js | — | js | — | — | — | — | — | — |

What each column means for a user:

- **Network atoms** need an `http` root: the JVM, ClojureScript on Node and in a browser, babashka, nbb, ClojureCLR,
  basilisp, clojerl and glojure. **Serving a gateway** needs `http-server` as well: the JVM, babashka and ClojureScript
  on Node.
- **Cross-process disk domains** are lock-free CAS on every region with hardware atomics on a mapped file. babashka's
  region (`jvm-nio-lock`) is lock-only, through `FileChannel` byte-range locks, and takes part through the domain's
  exclusive hold ([atom-domains.md](atom-domains.md#many-processes-on-one-domain)).
- **Crypto** is native on the JVM, babashka, Node and nbb, basilisp, ClojureCLR and clojerl. The browser, jolt, glojure
  and ValueScript run the portable code, which is the reference, not hardened ([../SECURITY.md](../SECURITY.md)).
- **eve values answer the host's own `clojure.core`** where `coll` is the host's (`jvm`, `cljs`, `nbb`, `basilisp`,
  `beam`). Where it is `protocols` (babashka, glojure, ValueScript), use `eve.core`'s functions on eve values.

## Runtime versions

What ran on the 0.9 preparation box (Linux x86-64), 2026-10-20 ET:

| Runtime | Version | Used for |
|---|---|---|
| OpenJDK | 21.0.12.1 | the JVM samples |
| Clojure | 1.12.0 (`deps.edn`) | the JVM |
| babashka | 1.13.226 | `bb test:bb`, `bb test:bb-minimal`, every sample in the docs |
| Go | 1.24.7 | building `glj-eve` (glojure v0.7.22, `impls/glojure/go.mod`) |
| Node | 24.22.0 | ClojureScript's suites; nbb |
| Chromium | Playwright's (`/opt/pw-browsers`) | `bb test:browser` |
| nbb | 1.6.214 | `bb test:nbb` |
| ClojureCLR | 1.12.6 on .NET SDK 8.0.132 | `bb test:cljr` |
| basilisp | 0.5.1 on CPython 3.13.16 | `bb test:lpy` |
| jolt | v0.8.22-27-gf79e64d8 on a threaded Chez Scheme 10.4.1 | `bb test:jolt` |
| clojerl | 0.9.0-2194.4ad14e5 on OTP 25 | `bb test:clje` |
| wasmtime | 45.0.1 | `bb test:vs` |

nbb, ClojureCLR and basilisp were installed on the box for the preparation (2026-10-20); jolt, Chez and clojerl under
`/opt/eve-tools`.

The JVM needs **JDK 21**: `adapters/thread/jvm` starts virtual threads (`Thread/ofVirtual`). Its Ed25519 is the JDK's
`Signature "Ed25519"`, available from JDK 15. The JVM region is `MappedByteBuffer` / `ByteBuffer` with `VarHandle`
atomics; no Panama FFM and no `sun.misc.Unsafe`. The `--add-opens` and `--enable-native-access` flags in `deps.edn`
belong to the test aliases; the first-atom sample ran without them.

## Operating systems

Linux is the only OS the 0.9 preparation ran on. macOS and Windows are **unverified**. eve's N-API addon
(`native/mmap_cas.cc`) is POSIX-only, with no `_WIN32` branch, so Node disk domains on Windows are not expected to
build.

## The native pieces

| Piece | Where | Who needs it |
|---|---|---|
| eve's N-API addon (`mmap_cas.cc`: mmap, 32/64-bit atomics, futex, fcntl locks) | `native/`, built by `npm install` (`binding.gyp`, C++20) | ClojureScript on Node and nbb, for disk domains |
| eve's clojerl NIF (`eve_clje_nif.c`) | `adapters/core/beam/native/`, built on first load (gcc and the BEAM's `erl_nif.h`) | clojerl |
| glojure's Go packages (regions, host helpers, types) and three patches to glojure v0.7.22 | `impls/glojure/`, `adapters/completion/glojure/go/` | glojure (`glj-eve`) |
