# eve

Persistent data structures in shared memory, for Clojure-like languages — the same values, byte for byte, on the JVM,
ClojureScript, babashka, nbb, ClojureCLR, basilisp, jolt, clojerl, glojure and ValueScript. (ValueScript's row in
`hosts.edn` carries its thread, http and http-server adapters; it is part of the 0.9 release.)

eve keeps Clojure's maps, vectors, sets, lists, sorted collections, int-maps, RRB vectors, typed arrays and objects in
slab memory: a process's heap, or memory-mapped files that processes of any of those hosts open together and swap
atomically. The values live in **atoms**; atoms live in **atom domains**; a **network atom** carries the same values to
a broker or a gateway. eve is written once, against a host interface: a language gets eve by supplying a few adapters,
and gets faster by supplying more.

> **0.9 — prepared, not yet released.** 0.9 is the API and host contract 1.0 will freeze. The on-disk and wire formats
> may still change before 1.0 (see [CHANGELOG.md](CHANGELOG.md)).

## What it does

- **Atoms of eve values.** `(e/atom ::state {:count 0})`, then `swap!`, `reset!`, `@`. A swap is a compare-and-set on
  the atom's root pointer, retried on conflict; `@` answers the value as the host's own collections; inside
  `(e/$ ...)`, a read region, `@` answers the stored value in place instead (nothing materialized but what you touch),
  every read held until the region exits and only its result materialized.
- **Atom domains.** A domain is itself an atom (slot 0 holds its registry); its member atoms are slots of one
  domain-state, each a named atom of its own, with a **meta-atom** beside it for coordination. A domain is in-process
  (heap memory) or on disk: a set of files (`<base>.root`, `.rmap`, `.slab0`–`.slab6`, bitmaps) that every process
  opening the same base path shares — the first to open it creates it, the rest join, and their swaps interleave with
  no lost update. [doc/atom-domains.md](doc/atom-domains.md)
- **Network atoms** (`eve.net.atom`): an eve value at a broker or a gateway, with synchronous `deref` / `swap!` /
  `reset!` on every host that has an http adapter. Blocks go to and from an object store through presigned URLs;
  with a group key (`:gak`) they are encrypted. Any member of a disk domain can serve its atoms as a gateway
  (`eve.net.gateway`). [doc/network-atoms/README.md](doc/network-atoms/README.md)
- **Collections, one source for every host**: HAMT maps and sets, persistent vectors and lists, `eve.sorted` (sorted
  maps and sets with comparators, `subseq`, `rank`), `eve.deftype.int-map`, the red-black sorted set,
  `eve.rrb-vector` (core.rrb-vector in eve memory), typed arrays (`eve.array`) and objects (`eve.obj`), columnar
  datasets and tensors (`eve.alpha.ds`, `eve.alpha.tensor`, `eve.alpha.col`), and your own types (`eve/deftype`).
  [doc/collections.md](doc/collections.md), [doc/data-structures.md](doc/data-structures.md)
- **Threads** (`eve.thread`): `future`, `promise`, `pmap`, `pcalls`, pools, `fold` and `=>>`, written once over each
  host's threads — and pools whose registry lives in a domain, shared by processes of different hosts
  (`eve.thread.domain`).
- **Encryption at rest, opt-in** (`eve.keyring`). Disk domains and network atoms are **plaintext by default**; read
  [SECURITY.md](SECURITY.md) for what the key ring and `:gak` do and do not protect.

## Quick start

From this directory. On babashka the classpath is `src` plus the host's adapter roots, which `bb classpath <host>`
prints; on the JVM it is `deps.edn`'s `:paths`.

```clojure
(require '[eve.alpha :as e] '[eve.atom :as ea])

;; an atom in this process
(def counter (e/atom ::counter {:n 0}))
(swap! counter update :n inc)
@counter                                   ;=> {:n 1}

;; an atom in a disk domain: any process (of any host) opening the same path shares it
(def shared (e/atom {:id :app/hits :persistent "/tmp/eve-readme/db"} {:n 0}))
(swap! shared update :n inc)
(ea/close-atom-domain! (ea/persistent-atom-domain "/tmp/eve-readme/db"))
```

```bash
bb -cp "$(bb classpath bb)" examples/quick_start.cljc   # babashka (the blocks above, as a file)
clojure -M examples/quick_start.cljc                    # the JVM
```

Eve collections live in an atom's own domain: build them **inside** `swap!` (one built outside belongs to another
domain and is refused today):

```clojure
(require '[eve.deftype.int-map :as im])
(def idx (e/atom ::index {}))
(swap! idx (fn [_] {:ids (im/int-map)}))
```

The runnable examples carry on from here: [examples/](examples/) — an in-process walk-through
(`in_memory.cljc`), processes of different hosts on one disk domain (`disk_domain_processes.clj`), a network atom
served by a gateway (`network_atom.cljc`).

## Hosts

A host is a list of adapter roots, one row of [`hosts.edn`](hosts.edn); eve's shared code (`src/`) names none of them
but one ClojureScript-only file (`src/eve/shared_atom.cljs`, counted in `.host-leak`).
Status is what this tree measured (the final pass, 2026-10-21/22 ET); each host's page in
[doc/hosts/](doc/hosts/README.md) has its runs, its performance, how eve is built on it and its gotchas.

| Host | Runs on | Runner | The final tree, 2026-10-21/22 ET |
|---|---|---|---|
| JVM | Clojure 1.12, JDK 21 | `clojure -M:jvm-test` | **green**: 546 tests, 9,615 assertions |
| ClojureScript (Node) | shadow-cljs, Node + eve's N-API addon | `node target/eve-test/all.js <suite>` | **green**: `all` 482 tests, 34,980 assertions; `net` 62 / 1,429, `slab-grow` 105 / 28,902, `thread-domain` 12 / 146, `deftype` 63 / 710 green |
| ClojureScript (browser) | a cross-origin-isolated page | `bb test:browser` | **green**: 48 tests, 633 assertions (headless Chromium) |
| babashka | bb 1.13 | `bb test:bb` | **green**: 247 tests, 4,748 assertions (babashka-minimal 242 / 4,623) |
| nbb | Node | `bb test:nbb` | **green**: 242 tests, 4,617 assertions |
| ClojureCLR | .NET 8, ClojureCLR 1.12.6 | `bb test:cljr` | **green**: 202 tests, 4,499 assertions |
| basilisp | CPython 3.13, basilisp 0.5.1 | `bb test:lpy` | 180 tests, 4,350 assertions passing, **7 errors**: eve.thread.domain's 120 s waits run out late in the full run (checklist 9.26; fold on a group's three with a leak of its own, 9.35); each of those namespaces alone green |
| jolt | threaded Chez Scheme 10.4 | `bb test:jolt` | **green**: 199 tests, 4,479 assertions |
| clojerl | the BEAM | `bb test:clje` | **green**: 198 tests, 4,400 assertions |
| glojure | Go (`glj-eve`, built by `bb glj-eve`) | `bb test:glj` | **green**: 182 tests, 4,458 assertions |
| ValueScript | wasmtime | `bb test:vs` | **green**: 260 passed, 0 failed (2026-10-21) |

What each host lacks today: eve's typed arrays are not on ClojureCLR, basilisp, jolt, clojerl or glojure; jolt
has no http adapter (no network atoms; the browser's is new in 0.9: synchronous XHR); the JVM, babashka,
ClojureScript on Node and ValueScript can serve a gateway (the http-server feature; Node's is new in 0.9).

**babashka and a shared disk domain.** babashka's regions have no atomic read-modify-write, so it writes a domain
other processes write under an exclusive hold; a lock-free process killed inside a write leaves the hold waiting for
ever. Since 9.31 a lock-free host's write that raises a tally count
takes the same hold and waits for the writes in flight the same way (`with-held-write*`, `src/eve/atom.cljc`), so the
hang now reaches any host's raising write. Until that changes, let babashka **read** a shared domain, and
write it through a gateway (a network atom), as the capstone does — or be the domain's only writer.

**The capstone**, `capstone/eve-media/` (its README): a media workbench built from six of these runtimes and a browser
— babashka, the JVM, Node/ClojureScript, Go (glojure), the BEAM (clojerl), jolt and the browser — sharing one disk
domain, every job a thread descriptor in eve.thread's pools. `bb eve-media demo` runs it; `bb eve-media verify` runs its done
bar.

## The adapters, and adopting eve for your language

eve's host interface is sixteen features, each an adapter namespace with one implementation per host capability
(`adapters/<feature>/<implementation>/`). They fall in three tiers — measured, with every host's composition, in
[doc/adopter-tiers.md](doc/adopter-tiers.md):

| Tier | Features | What it means for a new language |
|---|---|---|
| **Minimal** — you write it | `region` (memory and atomics), `encoding` (strings, floats, numbers), `process` (clock, sleep, files, entropy), `types` (eve's types as your host's) — plus `core` / `completion` if your core is not Clojure's | four namespaces; on babashka they are 972 lines |
| **Inbuilt** — portable, ships with eve | `slab-io/protocol`, `core/clojure`, `completion/clojure`, `coll/protocols`, `slab/heap`, `int32/portable`, `codec/byte-array`, `crypto/portable`, `thread/none` | free: with the minimal four they run every shared suite but the network one (babashka on just these: 242 tests, 4,623 assertions, 0 failures, 2026-10-21 ET — `bb test:bb-minimal`) |
| **Fast** — native seams you plug in | `crypto`, `int32`, `codec`, `coll`, `slab`, `slab-io`, `thread`, `typed-view`; and `http` / `http-server` for network atoms and gateways | speed and capability: native crypto is ~55–1,100× the portable code on babashka (2026-10-21 ET); real threads turn the pools on |

- [doc/adopting-eve-minimal.md](doc/adopting-eve-minimal.md) — implement eve for your language with the minimal set
  and the inbuilt one.
- [doc/adopting-eve-fast.md](doc/adopting-eve-fast.md) — replace inbuilt seams with native ones, capability by
  capability.

**Where Clojure-like implementations live.** A language that loads eve's `.cljc` is a host: its adapters under
`adapters/`, its row in `hosts.edn`, a runner under `test/eve/runner/`, a `bb test:<host>` task. What a language needs
outside its Clojure source — Go packages compiled into its binary (glojure), a NIF (clojerl's, in
`adapters/core/beam/native/`), eve's N-API addon (`native/mmap_cas.cc`) — sits beside its adapter or under
[`impls/`](impls/README.md), which also holds the first steps of ports that do not yet run eve's shared code (let-go,
jank, phel) and a babashka atomics pod nothing uses.

## Performance

One performance suite (`test/eve/perf_suite.cljc`, ten workloads over the public API: swaps and derefs of in-process
atoms, a zero-copy read, a 1,000-element reset, sorted-set and int-map builds, a disk-domain swap, 64 tasks through
the compute pool) runs on every host: `bb scripts/perf-all.clj` runs them one at a time and writes
[doc/hosts/perf-results.edn](doc/hosts/perf-results.edn). The table across hosts and each host's reading of its own
numbers are in [doc/hosts/](doc/hosts/README.md). The cost of each seam, portable against native, on babashka is in
[ADOPTER-TIERS §6](doc/adopter-tiers.md); the older benches are in `bench/` (`deps.edn` aliases
`:native-build-atom`, `:native-profiled-stress`, `:contention-bench`, `:native-data-bench`, `:columnar-bench`).

## Documentation

| Document | What it covers |
|---|---|
| [doc/getting-started.md](doc/getting-started.md) | using eve from this tree on the JVM, babashka, Node |
| [doc/api-guide.md](doc/api-guide.md) | the public API, namespace by namespace |
| [doc/atom-domains.md](doc/atom-domains.md) | atoms, domains, disk domains, meta-atoms, processes sharing a domain |
| [doc/network-atoms/README.md](doc/network-atoms/README.md) | network atoms, gateways, sign-in |
| [doc/collections.md](doc/collections.md), [doc/data-structures.md](doc/data-structures.md), [doc/obj.md](doc/obj.md) | the collections, typed arrays, objects, datasets |
| [doc/platform-support.md](doc/platform-support.md) | the host matrix |
| [doc/hosts/](doc/hosts/README.md) | one page per host: its battery, its performance, how eve is built on it, gotchas, next steps |
| [doc/architecture.md](doc/architecture.md), [doc/internals.md](doc/internals.md) | the design, the byte format, the slab allocator |
| [doc/memory-model.md](doc/memory-model.md) | how memory is reclaimed, and where eve's code differs from the model |
| [doc/testing.md](doc/testing.md) | the test suites and runners |
| [doc/unification/HOST-CONTRACT.md](doc/unification/HOST-CONTRACT.md), [adapters/README.md](adapters/README.md) | the host interface and its adapters |
| [SECURITY.md](SECURITY.md), [CONTRIBUTING.md](CONTRIBUTING.md), [CHANGELOG.md](CHANGELOG.md) | |

## License

MIT ([LICENSE](LICENSE)), except core.rrb-vector's ClojureScript sources under `src/eve/rrb_vector*`, which are
Eclipse Public License 1.0 ([epl-v10.html](epl-v10.html)). See [NOTICE](NOTICE).
