# EVE implementations

EVE is one data format and one protocol (`doc/unification/HOST-CONTRACT.md`,
`doc/unification/FORMAT-DECISIONS.md`); an implementation is that format and
protocol in one language.

## Where an implementation lives

A Clojure-like that can load eve's shared `.cljc` is a **host**, and it lives in three places, none of them `src/`:

1. its row in `eve/hosts.edn` — the adapter roots on its classpath after `src/` (`src/` has no reader conditionals
   and names no host: `scripts/check-host-leak`, `.host-leak`);
2. the adapters it had to write, under `eve/adapters/<feature>/<impl>/` (`adapters/README.md`), and a test runner
   `eve/test/eve/runner/<host>_main.*` with a `bb test:<host>` task;
3. only when the language itself must be extended natively, its native half: `impls/<lang>/` (below), a NIF beside its
   adapter (`adapters/core/beam/native/eve_clje_nif.c`), or eve's N-API addon (`native/mmap_cas.cc`).

Which features a host must write and which it may take as they are: `doc/adopter-tiers.md`,
`doc/adopting-eve-minimal.md`.

| Implementation | What it is | Status |
|---|---|---|
| ClojureScript | `src/` + `hosts.edn :cljs` (Node) / `:browser`; files through EVE's own addon (`native/mmap_cas.cc`) | a host; `node target/eve-test/all.js <suite>`, `bb test:browser`. The references every host is checked against are the byte fixtures (`test/eve/eve_bytes_fixture_test.cljc`, `test/eve/fixtures/eve-bytes.edn`) and `crypto/portable` (`test/eve/net/crypto_test.cljc`), not one host |
| nbb | `src/` + `hosts.edn :nbb` | a host; `bb test:nbb` |
| JVM Clojure | `src/` + `hosts.edn :jvm` (the same roots as `deps.edn :paths`) | a host; `clojure -M:jvm-test` |
| babashka | `src/` + `hosts.edn :bb`; types are sci deftypes over `eve.core`'s protocols (`adapters/types/bb`), the region lock-only (`adapters/region/jvm-nio-lock`) | a host; `bb test:bb` |
| ClojureCLR | `src/` + `hosts.edn :cljr`: regions over `MemoryMappedFile` (`adapters/region/clr`) | a host since 2026-10-16; `bb test:cljr`; a writer in the coop scenario (`eve/test/coop/`). eve's typed arrays (`eve.array`, the dataset and tensor over them) are not yet on it |
| basilisp | `src/` + `hosts.edn :lpy`; its core made Clojure's by `adapters/completion/basilisp/src/eve/host_completion.lpy`; regions over `mmap` + `ctypes` (`adapters/region/ctypes`) | a host since 2026-10-16; `bb test:lpy`; a coop writer. Typed arrays not yet on it |
| jolt | `src/` + `hosts.edn :jolt`; regions through `jolt.ffi` (`adapters/region/jolt-ffi`), completion `adapters/completion/jolt` | a host since 2026-10-16; `bb test:jolt`; a coop writer. Typed arrays not yet on it |
| clojerl | `src/` + `hosts.edn :clje`; `eve.host` over eve's NIF — `adapters/core/beam/src/eve/clje_nif.clje` and `adapters/core/beam/native/eve_clje_nif.c`, built on first load into `adapters/core/beam/target/clje/` | a host since 2026-10-17; `bb test:clje`; a coop writer. Typed arrays not yet on it |
| glojure | `src/` + `hosts.edn :glj` (`completion/glojure`, `region/glojure`, `types/glojure`, `encoding/go`, `process/go`, `thread/glojure`, babashka's `coll/protocols`) and the Go side of its build, `impls/glojure/` (below), run as `glj-eve` | a host since 2026-10-20; `bb test:glj`; a member of the two-process rendezvous and the multi-runtime rotation (`scripts/thread-rendezvous.clj`, `scripts/thread-rotation.clj`). Typed arrays not yet on it |
| ValueScript | `src/` + `hosts.edn :vs`, compiled by the installed `vs` and run under wasmtime; needs ValueScript's `vs.thread`, `vs.mmap` and `vs.interop` | `bb test:vs`: hamt-util, the byte fixtures and the slab-io conformance test (260 passed, 0 failed, 2026-10-21); eve's types wait on VS's analyzer; no thread root |
| every other language | `impls/<lang>/` | below |

A host that can load a file of `src/` as written needs nothing in `impls/`. Measured 2026-10-16 on
`src/eve/hamt_util.cljc` (EVE's 28 trie-index rows, the popcount rows, the key hash of 53312): nbb, let-go,
ClojureCLR, glojure and basilisp load it as written and answer every row. Clojerl could not (no `aget`) until
`eve.host` gave it one (2026-10-17); its probe folder is in git history at `ae90435d` (`eve/impls/clojerl/`).

## The folders

`glojure/` is the Go side of a host (above); the others are no EVE client yet. Each of those holds what a port to
that language needs first and was written while measuring the host; their types prove that the host's core functions
dispatch to a foreign type, over a stand-in layout (a count word, then elements), not EVE's slab format.

| Folder | Language | What is here | State (2026-10-20) |
|---|---|---|---|
| `glojure/` | glojure (Go) | glj-eve, the glj eve runs on (`build.sh`, `bb glj-eve`): `region/` — heap and file regions, 32- and 64-bit atomics (an unaligned load or store byte-wise), futex wait/notify (a 100 µs poll outside Linux), bulk bytes, an fcntl lock; `host/` — UTF-8, array copies, little-endian floats, a goroutine's id, identity hashes, Clojure's collection hashes; `types/` — the Go value an eve map type is (a field-less glojure record whose map methods call eve.core); `cmd/glj-eve` — glojure's main with the three linked in. The build applies `eve/adapters/completion/glojure/go/*.patch` — `host-field.patch`, `record-factory.patch`, `goroutine-id.patch` — to glojure v0.7.22 | built here: `bash impls/glojure/build.sh target/glj-eve`, 40 s (Go 1.24, the module cache warm after one `go mod download`; on a cold cache the build downloads glojure itself, `9555b23a`) |
| `let-go/` | let-go (Go) | `evenative/`: the same two layers as the `eve.native` namespace of a custom `lg` (`main.go`). `proposal-let-go.patch`: the upstream change that would let let-go's atom ops reach a foreign type | `lg-eve` built here with `go build` (34 s; go.mod pins Go 1.27, which Go fetched; 24 s again on the final tree, 2026-10-22 ET); it loads `src/eve/hamt_util.cljc` and hashes as babashka does. No `hosts.edn` row, runner or task |
| `jank/` | jank (C++) | `src/eve/region.jank`: the file region, atomics, futex and fcntl through `cpp/raw`. `src/eve/types.jank`: C++ object types; reads dispatch, writes do not yet | not run here (jank not installed); jank cannot load eve's shared `.cljc` yet |
| `phel/` | phel (PHP) | `src/EveTypes.php`: PHP classes implementing phel's collection interfaces over FFI memory; `composer.json`, `bootstrap.php` | not run here (phel not installed). **`composer.json` declares `"license": "proprietary"`**, which eve's MIT license contradicts — to fix before a release |
| `bb/` | Babashka | `eve_atomics_pod/`: a pod giving bb cross-process 32-bit atomics on a mapped file (`open`, `load32`, `store32`, `add32`, `cas32`) | builds (`go build`, 8 s, 2026-10-20; 2 s on the final tree, right after the let-go build, 2026-10-22 ET). Nothing in eve calls it: bb takes part through the lock-only region (`region/jvm-nio-lock`) and the exclusive-hold protocol |

The measurements behind these folders (the probe protocol, every host's evidence and probe scripts) are in git
history at commit `1fb9cbca`, under `eve/doc/unification/ports/`.
