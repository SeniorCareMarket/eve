> **Historical record** — the measured inventory of Clojure-likes (2026-10-15/16), before glojure, ClojureCLR, basilisp, jolt,
> clojerl and ValueScript joined `hosts.edn`. It describes eve as it stood then, not eve 0.9. Current authority for adopted
> hosts: `hosts.edn` and `doc/release-0.9/ADOPTER-TIERS.md`; this file is still the evidence for let-go, squint, jank and phel.

# EVE port targets — the Clojure-likes, installed and measured

Lane follow-up to `UNIFICATION-REPORT.md` §8 ("Porting to host #4"). Asked
for: the Clojure-likes that `in-1` (https://in-1.cc/installables/) can
install, installed here with that tool and measured against the host
contract; plus jank and jolt, named as targets.

Sources for the inventory: the in-1 installables table (159 tools, read
2026-10-15), `chr15m/awesome-clojure-likes`, `ilevd/clojure-like` (113
entries). Everything in §3 onward was measured on the boxes named in §2
(Ubuntu 24.04, x86_64, 4 CPUs, 15 GB) by one prober agent per host following
`ports/PROBE-PROTOCOL.md`; the skeptic pass re-ran every host's
`ports/probes/<host>/run.sh` and diffed its summary block against the one its
evidence file quotes (`ports/rerun-all.sh`, §3.3). The per-host evidence
files are `ports/evidence/<host>.md`.

**Where `ports/` is now.** The protocol, the probes and the evidence were
removed from the tree after commit `1fb9cbca` (operator, 2026-10-16: the docs
are not carried; a place for canonical implementations is). Every `ports/…`
path in this document reads at that commit:
`git show 1fb9cbca:eve/doc/unification/ports/<path>`. The per-language
implementation code the probes built moved to `eve/impls/` (its README says
what each one is).

## 1. Inventory

### 1.1 Clojure implementations and dialects on in-1 (13)

| in-1 name | Command | Host runtime | What it is | On awesome-clojure-likes | On ilevd/clojure-like |
|---|---|---|---|---|---|
| clojure | `clojure` / `clj` | JVM | the reference | yes | yes |
| babashka | `bb` | GraalVM native image, sci | fast-start Clojure scripting (sci interpreter) | yes | yes |
| nbb | `nbb` | Node | sci on Node, ClojureScript scripting | yes | yes |
| squint | `squint` | Node | ClojureScript syntax over JS data structures | yes | yes |
| cljr | `cljr`, `Clojure.Main` | .NET (CLR) | ClojureCLR, the official CLR port | yes (Clojure CLR) | yes (ClojureCLR) |
| basilisp | `basilisp` | Python 3 | Clojure-compatible Lisp on Python | yes | yes |
| joker | `joker` | Go (static binary) | small Clojure interpreter and linter | yes | yes |
| glojure (+ gloat) | `glj`, `gloat` | Go | Clojure interpreter hosted on Go; gloat is its AOT tool | yes | yes (both) |
| grenadine | `grenadine` | Go | Clojure-inspired runtime for Go | no | no |
| let-go (+ lgx) | `lg`, `lgx` | Go | bytecode compiler + VM for a Clojure dialect; lgx is its package manager | yes | yes |
| cljgo | `cljgo` | Go | Clojure on Go: AOT emits Go source; tree-walk REPL | yes | yes |
| jolt | `jolt` | Chez Scheme (native) | self-hosted Clojure compiler on a Scheme layer; C interop via `defcfn` | no | yes |
| yamlscript | `ys` | GraalVM native image, sci (libys) | YAML syntax compiling to Clojure, run by a sci runtime | no | no |

### 1.2 Clojure-inspired Lisps on in-1 (5)

| in-1 name | Command | Host runtime | Relationship to Clojure |
|---|---|---|---|
| phel | `phel` | PHP 8 | "inspired by Clojure and Janet"; persistent collections, PHP interop |
| hy | `hy` | Python 3 | Lisp over Python's data model; not Clojure semantics |
| fennel | `fennel` | Lua 5.4 | Lisp over Lua tables; not Clojure semantics |
| janet | `janet` | C (own VM) | Lisp-like syntax, mutable tables; has a C FFI |
| carp | `carp` | native (Haskell-built compiler) | statically typed Lisp without GC, Clojure-flavoured syntax |

### 1.3 Named targets not on in-1

| Name | Install route | Host runtime |
|---|---|---|
| jank | apt repository `ppa.jank-lang.org` (Ubuntu 24.04+) | C++ / LLVM: Clang JIT and AOT |

### 1.4 Notable Clojure-likes on the two reference lists that in-1 does not install

ClojureDart (Dart/Flutter), Clojerl (Erlang VM), ClojureWasm (Zig),
ClojureRS / ClojuRS / rusjure (Rust), Lokke (Guile), Cloture / CLClojure
(Common Lisp), uclj and Cream (native Clojure via GraalVM), Venice (JVM,
sandboxed), nanoclj and JO Clojure (C/C++ embeddable), Toccata (Clang
native), Kalai and Rustly (transpilers), Cherry and Choq (squint family),
Planck and Lumo (standalone ClojureScript, unmaintained). They are not
measured here; §5 says which of the measured hosts each resembles.

## 2. Install record

Three boxes. Box 1 (2026-10-15, 9–11 PM ET) is the record the first session
left; its box and scratchpad did not survive. Box 2 (2026-10-15 from 10:04
PM ET) installed with in-1 again and measured node and grenadine (§3) before
its session hit its usage limit; its box did not survive either. Box 3 (from
2026-10-15 10:39 PM ET) measured every other host in §3. Each box started
from a fresh clone with nothing but `bb`, `clojure`, `java`, `node`,
`python3` and `php` present (box 3 also had `go` 1.24.7).

**Box 3 did not use in-1.** This session's permission policy refused it
three ways, each refusal quoted from the tool: `source <(curl -sL in-1.cc)
-L in-1` ("Code from External"); running the downloaded bootstrap after
reading it (it clones `github.com/in-1-cc/in-1` at `v0.1.0-47-gc0866d8` and
runs that clone's `bin/in-1`) ("Code from External"); and fetching the
`makeplus/makes` recipes as text (refused, no reason given). Box 3 installed
from each tool's own package registry instead, and only where one exists.
The tools that ship only through in-1's recipes (GitHub release tarballs)
are not on box 3 and are not measured in §3.

Boxes 1 and 2: `source <(curl -sL in-1.cc) -L in-1` put `in-1 c0866d8` at
`/usr/local/bin/in-1` (3 s); then `in-1 -q <tool>` per tool, three chains in
parallel after the first. in-1 clones `makeplus/makes` and runs the tool's
`<tool>.mk`; every in-1 install was a prebuilt download. Box 3's routes are
the commands in its column (logs under the session scratchpad; times are
wall clock).

| Tool | Version (box 3 where it differs) | Box 1 (in-1) | Box 2 (in-1) | Box 3 (package registries) |
|---|---|---|---|---|
| joker | 1.10.0 | installed, 3 s | installed, 3 s | **not installed** — `go install github.com/candid82/joker@v1.10.0` fails, 26 s: `core/data.go:34:15: undefined: linter_cljData` (the module needs `go generate` before it builds); its other route is a release tarball |
| glojure / gloat | 0.7.22 / 0.1.91 | installed | installed, 2 s (`gloat` not on PATH) | `glj` installed, 26 s: `go install github.com/glojurelang/glojure/cmd/glj@v0.7.22` (go1.24.7); `gloat` not installed |
| grenadine | 0.1.15 | installed | installed, 1 s | not installed (in-1 only) — measured on box 2 |
| let-go / lgx | 1.13.0 / 0.4.2 | installed | installed, 2 s (`lgx` not on PATH) | `lg` installed, 24 s: `go install github.com/nooga/let-go@v1.13.0` (binary `let-go`, linked as `lg`); `lgx` not installed |
| cljgo | 0.9.0 (bundles Go 1.26.5, Clojure 1.12.5 core) | installed | installed, 4 s | not installed (in-1 only) |
| jolt | 0.8.21 (box 3: **v0.8.22-27-gf79e64d8** from source) | installed | installed, 3 s | installed from source: `git clone --recurse-submodules https://github.com/jolt-lang/jolt` at `f79e64d8` (the seed is checked in; no build), on a **threaded Chez Scheme 10.4.1** built from its release tag (`git clone -b v10.4.1 --recurse-submodules https://github.com/cisco/ChezScheme`, `./configure --threads`, `make -j4` 232 s) — jolt's own `make` provisions Chez through the same `makeplus/makes` recipes in-1 uses, so it was not used. ~9–12 s per start from the clone |
| janet | 1.42.1 | installed | installed, 2 s | not installed (in-1 only; no `janet` package in Ubuntu 24.04: `apt-cache policy janet` is empty) |
| yamlscript | 0.3.5 | installed | installed, 2 s | not installed (in-1 only) |
| nbb | 1.6.214 | installed (270 MB with its Node) | installed, 8 s | installed: `npm install -g nbb` (runs on the box's Node v24.21.0) |
| squint | 0.14.210 (box 3: **0.15.212**) | installed | installed, 5 s | installed: `npm install -g squint-cljs` — the registry's current release, one minor version past in-1's |
| basilisp | 0.5.1 (with its own Python; box 3: CPython 3.13.16) | installed | installed, 5 s | installed: `pip install basilisp hy` into the venv `/opt/pyhosts` |
| hy | 1.3.1 | installed, 27 s | installed, 4 s | installed with basilisp (above) |
| phel | 0.54.0 with php 8.3 (box 3: **0.34.1** on PHP 8.3.6) | installed | installed, 6 s | installed: `composer require phel-lang/phel-lang` in `/opt/phel` (composer 2.7.1 from apt). `phel-lang/phel-lang:0.54.0` is refused: `requires php >=8.5 -> your php version (8.3.6) does not satisfy that requirement`; 0.34.1 is the newest release Composer resolves for PHP 8.3 |
| fennel | 1.6.1 on PUC Lua 5.4 | installed | installed, 3 s | installed, 3 s: `luarocks --lua-version 5.4 install fennel` (lua5.4 5.4.6 and luarocks 3.8.0 from apt, 12 s with the .NET SDK) |
| cljr | 1.12.6 (with dotnet) | installed | installed, 20 s | installed, 2 s: `dotnet tool install -g Clojure.Main --version 1.12.*` (NuGet; .NET SDK 8.0.131 / runtime 8.0.31 from apt `dotnet-sdk-8.0`) |
| babashka | 1.13.226 | already on the box; in-1 refused to overwrite `/usr/local/bin/bb` | already on the box | already on the box |
| clojure | CLI 1.12.0.1530 | already on the box | already on the box — Maven Central answers **429 Too Many Requests** through the proxy, so the CLI cannot fetch `org.clojure/clojure 1.12.0` | the same 429; the JVM column is measured through the CLI's own uberjar, which bundles Clojure 1.12.0: `java -cp /usr/local/lib/clojure/libexec/clojure-tools-1.12.0.1530.jar clojure.main` on OpenJDK 21.0.12 (`evidence/clojure.md`) |
| carp | 0.6.0 | **not installed**: release tarball 403 through the proxy (5 retries, 354 s) | **not installed**: the same 403, 5 retries, 390 s | not attempted: a 403 is a policy denial and the proxy's instructions say not to retry one |
| jank | jank-0.1-alpha | installed from the apt repo (`apt-get install jank`) | installed, 27 s, package `0.1-noble` — the `jank.list` the repo publishes names suite `./`, which has no `Release` file (404); the working line is `deb [signed-by=/etc/apt/trusted.gpg.d/jank.gpg] https://ppa.jank-lang.org noble main` | installed, 40 s, `0.1-noble`, with the same `noble main` line and the repo's `KEY.gpg`. Refreshing only `jank.list` (`apt-get update -o Dir::Etc::sourcelist=…`) drops Ubuntu's lists, and jank's dependencies (`libboost-all-dev`, `libzstd-dev`, `libxml2-dev`, `libstdc++-14-dev`) then read as "not installable"; a full `apt-get update` fixes it. The first `jank run` builds a precompiled header (7 s) |
| clojerl | not on in-1 | — | — | installed from source: Erlang/OTP 25.3 + rebar3 3.19 from apt (26 s), `git clone https://github.com/clojerl/clojerl` at `4ad14e5` (2025-01-07), `make` (54 s); `bin/clojerl` |

Node (the CLJS arm's runtime) was already present on every box. On box 3
it is measured as EVE's ClojureScript host: EVE's N-API addon built in 19 s
(`npm install` in `eve/`, node-gyp, `eve/native/mmap_cas.cc`) and EVE's CLJS
suites compiled with shadow-cljs (35 s). Box 3's first pass did not build the
addon, which is how plain Node was scored "no shared memory" (§3.0).

Maven Central answered 429 through the proxy for most of box 3's session and
the JVM resolver later reported "Could not find artifact"; pointing
`~/.m2/settings.xml` at Google's mirror of Maven Central
(`maven-central.storage-download.googleapis.com`) made the `clojure` CLI and
shadow-cljs resolve. That file existed before and was overwritten without
being read first; its old contents are not known.

## 3. Per-host scorecards

Sixteen rows: fifteen hosts measured on box 3 (§2) and grenadine from box 2.
Every row is the summary block the host's evidence file quotes
(`ports/evidence/<host>.md`, PROBE-PROTOCOL §8 as revised on 2026-10-16),
regenerated from those files; each evidence file holds the commands and
outputs behind its block, and `ports/probes/<host>/run.sh` re-measures it.

### 3.0 The correction

The first version of this section (commits `f8ff874` … `a66d54d`) was wrong,
and the operator caught it: *"Node is Level D? But Eve was born on node and
browser. It has full parity there. That's the baseline against which the JVM
eve gets its validation."* Three errors, all mine:

1. **The scale.** LEVEL came from one question — can the stock runtime open a
   cross-process file mapping. It ignored HOST-CONTRACT §1.1's heap region
   (`SharedArrayBuffer` + `Atomics` across workers — EVE's original region),
   and it ignored the route EVE itself takes where the runtime has no
   mapping: EVE's own N-API addon, which had simply not been built. So Node,
   nbb and squint read "no shared memory", and so did glojure and let-go,
   whose own evidence measured goroutines sharing memory.
2. **TYPES from the dialect alone.** jank and phel were "materializing"
   because their `deftype` could not host EVE's types; nobody tried the
   implementation-language route (a C++ type, a PHP class). bb's summary
   said values were "copied out", when bb hosts EVE maps lazily and reads its
   slab files directly.
3. **Coverage.** Clojerl and jolt were not measured.

The fix: PROBE-PROTOCOL §8 now records the stock runtime (`P4.*`) and,
separately, what an EVE port reaches — `HEAP` (§1.1), `FILE` (§1.2 with
cross-process RMW) and `TYPES` — each with its route: the runtime's own API,
its FFI, or an extension the port ships (the model is EVE's Node addon), which
counts only when built and measured on the box. Every host was re-measured
against it, and Clojerl and jolt were installed and measured. Letters that
moved: node, nbb, squint and glojure **D → A**; let-go **D → B**; jank, phel
and bb stay B for measured, narrower reasons (§4).

### 3.1 The scorecard

| Host | Level | Version | Runtime | Start ms | P1 shared .cljc | P2 | P3 | HEAP (route/kind) | FILE (route/kind) | TYPES (route/kind) | Stock runtime: mmap · rmw · lock · x-proc | P5 | P6 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| basilisp | **A** | 0.5.1 | CPython | 617 | as-is | 10/10 | 8/8 | `ffi/atomic` | `ffi/atomic` | `dialect:deftype/full` | native · atomic · fcntl · pass | 8/8 | 6/7 |
| cljr | **A** | 1.12.6 | .NET/CoreCLR-8.0.31 | 6545 | as-is | 10/10 | 8/8 | `native/atomic` | `native/atomic` | `dialect:deftype/full` | native · atomic · fcntl · pass | 8/8 | 7/7 |
| clojure | **A** | 1.12.0 | JVM/OpenJDK-21.0.12.1 | 616 | as-is | 10/10 | 8/8 | `native/atomic` | `native/atomic` | `dialect:deftype/full` | native · atomic · fcntl · pass | 8/8 | 7/7 |
| glojure | **A** | v0.7.22 | Go-binary(go1.24.7) | 12 | as-is | 10/10 | 8/8 | `native/atomic` | `ext:go-pkg/atomic` | `impl:go/full` | none · atomic · flock · fail | 7/8 | 3/7 |
| nbb | **A** | v1.6.214 | Node/V8+SCI | 162 | as-is | 10/10 | 8/8 | `native/atomic` | `ext:N-API/atomic` | `dialect:sci-deftype/full` | none · atomic · none · fail | 8/8 | 7/7 |
| node | **A** | v24.22.0 | Node/V8+CLJS | 37 | as-is | 10/10 | 8/8 | `native/atomic` | `ext:N-API/atomic` | `dialect:cljs-deftype/full` | none · atomic · none · fail | 8/8 | 7/7 |
| squint | **A** | 0.15.212 | Node/V8 | 146 | as-is | 10/10 | 8/8 | `native/atomic` | `ext:N-API/atomic` | `dialect:squint-deftype/full` | none · atomic · none · fail | 8/8 | 7/7 |
| bb | **B** | v1.13.226 | SubstrateVM-java25.0.4+SCI | 24 | as-is | 10/10 | 8/8 | `native/atomic` | `ext:pod/atomic` | `dialect:deftype/maps` | native · plain · FileLock · pass | 8/8 | 7/7 |
| clojerl | **B** | 0.9.0-2194.4ad14e5 | BEAM/OTP-25-erts-13.2.2.5 | 498 | fails | 10/10 | 8/8 | `native/atomic` | `ext:nif/atomic` | `dialect:deftype/colls` | none · atomic · none · fail | 7/8 | 5/7 |
| jank | **B** | 0.1-alpha | C++/LLVM-JIT(clang-23.1.2) | 302 | fails | 8/10 | 8/8 | `ffi/atomic` | `ffi/atomic` | `impl:cpp-object/reads` | ffi · atomic · fcntl · pass | 8/8 | 2/7 |
| jolt | **B** | v0.8.22-27-gf79e64d8 | Chez-10.4.1-threaded | 12298 | fails | 10/10 | 8/8 | `ffi/atomic` | `ffi/atomic` | `dialect:deftype/colls` | ffi · atomic · fcntl · pass | 8/8 | 6/7 |
| let-go | **B** | 1.13.0 | Go-go1.27.2+lg-bytecode-VM | 25 | as-is | 10/10 | 8/8 | `ext:go-pkg/atomic` | `ext:go-pkg/atomic` | `impl:go/maps` | none · plain · none · fail | 6/8 | 5/7 |
| phel | **B** | v0.34.1 | PHP-8.3.6 | 132 | fails | 10/10 | 8/8 | `native/n/a` | `ffi/atomic` | `impl:php-class/colls` | ffi · atomic · fcntl · pass | 8/8 | 2/7 |
| fennel | **N** | 1.6.1 | PUC-Lua-5.4.6 | 32 | n/a | 10/10 | 8/8 | `native/n/a` | `ext:lua-c-module/atomic` | `n/a` | none · plain · none · fail | 8/8 | n/a |
| grenadine | **N** | v0.1.15 | Go-binary(go1.24.0,glojure-0.7.18-aot) | 10 | n/a | 0/10 | 0/8 | `none/none` | `none/none` | `n/a` | none · none · none · n/a | 0/8 | n/a |
| hy | **N** | 1.3.1 | CPython/3.13.16 | 124 | n/a | 10/10 | 8/8 | `ffi/atomic` | `ffi/atomic` | `n/a` | native · atomic · fcntl · pass | 8/8 | n/a |

Column notes. *Stock runtime* is what the runtime reaches with nothing
added (`P4.*`). *HEAP* / *FILE* / *TYPES* are what an EVE port reaches, as
`route/kind`: `native` the runtime's own API, `ffi` its foreign-function
interface (to libc and the system `libatomic.so.1`), `ext:<what>` an
extension built and measured here — EVE's N-API addon, a Go package compiled
into the runtime (glojure, let-go), a babashka pod, an Erlang NIF, a Lua C
module; `dialect:` / `impl:` for TYPES, the dialect's own `deftype` or a type
written in the implementation language. `HEAP n/a`: the host has no threads,
so a plain buffer is its heap region. TYPES `full` every collection kind and
the atom, `colls` every collection kind with the atom short of an op, `maps`
some collection kinds, `reads` reads only. The atom is scored as the host's
core dispatches it: `@`, `reset!` and `swap!` must reach the type (`swap!` is
where EVE's CAS loop runs); `compare-and-set!` too where the core makes it a
dispatch point (the JVM's `IAtom.compareAndSet`). ClojureScript's and
squint's `compare-and-set!` are deref-then-reset for any atom not their own —
no type can reach them; across two processes, core CAS on a shared EVE atom
lost 136 of 1000 updates while `eve.atom/compare-and-set!*` and `swap!` were
exact (`ports/evidence/node.md`), so EVE's CLJS API exports its own CAS.

### 3.2 What the measurements changed in EVE

The probes ran EVE's shared code on new readers and EVE's own atom across
processes; six defects were fixed at the producer, each pinned by a test.

1. **One trie index on every host** (`e2b6d3d`, FORMAT-DECISIONS §6). At
   shift 30 the JVM arm of `mask-hash` read a negative hash as a
   sign-extended long: a JVM-written `{371 :A 53312 :B}` answered `nil` for
   53312 under the CLJS arm's index. `test/eve/hamt_util_test.cljc`.
2. **A portable `:default` arm for `eve.hamt-util`** (`e2b6d3d`,
   `e98615d`): `quot` / `mod` / `*` / `+` and two-argument bit ops; `js*` →
   `js/Math.imul`. The shared file now runs as is on nbb, cljr, basilisp,
   glojure and let-go (before: fails or needed an edited copy):

   | Host | P1 before | P1 after | Why it failed before |
   |---|---|---|---|
   | nbb | fails | **as-is** | `js*` at `hamt_util.cljc:86` |
   | cljr | with-default-arm | **as-is** | reads only `:cljr`: unmatched conditionals vanish, the `let` at :37 lost a binding |
   | basilisp | fails | **as-is** | no `:lpy` arm; the probe's `:default` copy needed `unsigned-bit-shift-right` |
   | glojure | with-default-arm | **as-is** | falls back to `:clj` without `:glj` / `:default`: `Integer/rotateLeft` |
   | let-go | fails | **as-is** | reads only `:lg`: "Can't resolve nblocks" |
   | jolt | — | fails | reads `:clj` first (features `jolt clj default`); the `:default` arms are 10/10 with `:clj` hidden — a `:jolt` arm before `:clj` |
   | clojerl | — | fails | bytes are binaries (no `aget`; `count` rejects non-UTF-8); a `:clje` arm per conditional is 10/10 |
   | jank, phel | fails | fails | jank's arithmetic (`mod` returns a real, `+`/`*` wrap at 32 bits); phel lacks `mod`/`quot`/`==` and miscompiles `recur` in a rebinding `let` |

3. **`:default` is for portable code** (`d5f4c18`, CANDIDATE-VERDICTS §6):
   37 JVM-only `:default` keys became `:clj`, each proven to leave every `.cljc`
   reading identically on CLJS, the JVM and bb; `compare-bytes` gained a
   portable arm. let-go now loads `eve.mem`.
4. **`compare-and-set!` reported an abandoned attempt** (`b4fd116`): a
   matching attempt that lost its root CAS left the flag set; two Node
   processes landed 912 of 1000 before, 1000 after.
   `test/eve/jvm_atom_cas_test.clj` (fails on the old file).
5. **`contains?` / `find` on CLJS answered true for every key**
   (`b4fd116`): two `::absent` keyword literals are separate objects in
   compiled CLJS. CLJS conformance: the four failures that predate this lane
   (`:114 :121 :127 :167`) → 0.
6. **A mapping per slab class per deref** (`b4fd116`): CLJS
   `refresh-mmap-slabs!` opened a 64-byte peek each call, ~12 mappings per
   deref until `vm.max_map_count` aborted processes. Now it reads the header
   through the mapping it holds: 6058 mappings over 500 derefs before; +55
   then +11 over 500 + 1500 after (`test-deref-does-not-leak-mappings`).

### 3.3 EVE's own suites on box 3

The baseline the ports are measured against, on the final tree:
`clojure -M:jvm-test` 219 tests, 0 failures (plus the new CAS test, 4/4);
`clojure -M:jvm-e2e-test` 34 tests / 6364 assertions, 0 failures (after
`npx shadow-cljs compile bench-worker`); `node target/eve-test/all.js all`
359 tests / 31327 assertions, `conformance` 64 / 306, `mmap`, `mmap-slab`,
`mmap-atom` (with the mapping test), `mmap-atom-e2e`, `mmap-domain`, `fuzz` —
0 failures. An earlier report of "9 pre-existing `reduce` errors" came from a
hand-built classpath runner used before the CLI resolved; EVE's own suite has
no such errors — retracted.

### 3.4 The skeptic pass

`bash eve/doc/unification/ports/rerun-all.sh` (at `1fb9cbca`) re-runs every
host's `run.sh` and compares the summary block it prints with the one its evidence file
quotes — every field but `STARTUP_MS`, which is re-measured on each run. The
final pass, on the tree of `c4c074b` (every fix in §3.2 in place), finished
2026-10-16 2:32 AM ET (06:32 UTC):

| Host | rc | Wall | Verdict |
|---|---|---|---|
| basilisp | 0 | 65 s | match (STARTUP_MS 617 → 647) |
| bb | 0 | 6 s | match (STARTUP_MS 24 → 26) |
| cljr | 0 | 91 s | match (STARTUP_MS 6545 → 6622) |
| clojerl | 0 | 14 s | match (STARTUP_MS 498 → 478) |
| clojure | 0 | 52 s | match (STARTUP_MS 616 → 578) |
| fennel | 0 | 2 s | match (STARTUP_MS 32 → 23) |
| glojure | 0 | 4 s | match (STARTUP_MS 12 → 15) |
| hy | 0 | 12 s | match (STARTUP_MS 124 → 105) |
| jank | 0 | 67 s | match (STARTUP_MS 302 → 329) |
| jolt | 0 | 130 s | match (STARTUP_MS 12298 → 13313) |
| let-go | 0 | 6 s | match (STARTUP_MS 25 → 14) |
| nbb | 0 | 9 s | match (STARTUP_MS 162 → 160) |
| node | 0 | 48 s | match (STARTUP_MS 37 → 38) |
| phel | 0 | 11 s | match (STARTUP_MS 132 → 125) |
| squint | 0 | 7 s | match (STARTUP_MS 146 → 140) |
| grenadine | 2 | 0 s | no summary: not installed on box 3 (in-1 only); its `run.sh` refuses to print a block without the binary |

**15 of 15 runnable hosts match.** joker and janet have box 2's partial
scripts under `ports/probes/` and no `run.sh`.

The skeptic pass also found one defect in the probes themselves (round 1):
grenadine's `run.sh` printed its summary block as literal text, so a box
without grenadine still "matched". It now reads the version from the binary
and prints nothing without it (`fa6d7cb`); every other `run.sh` prints
computed values.

## 4. Ranking

Ordered by what a port would write to make the host a participating EVE host
(UNIFICATION-REPORT §8), measured in §3. Every host that runs code reaches
both region kinds by some route; the differences are TYPES and the work.

| # | Host | Level | Regions (HEAP · FILE) | TYPES | What a port writes |
|---|---|---|---|---|---|
| — | clojure (JVM) | A | native · native | `deftype`, full | nothing — the reference |
| — | node (EVE's CLJS) | A | SAB + `Atomics` · EVE's N-API addon | CLJS `deftype`, full | nothing — EVE's home; the browser is the heap region alone |
| 1 | **glojure** | A | goroutines + `sync/atomic` · a Go package compiled into `glj` through glojure's own package registration (`pkg/pkgmap/pkgmap.go:28`) | Go types implementing glojure's `lang` interfaces, full (embed `lang.Counted` / `Sequential`, implement `IReduceInit`) | the Go package (mmap, atomics, fcntl) and EVE's types in Go; the shared HAMT file runs as is |
| 2 | **cljr** | A | `MemoryMappedFile` / pinned arrays + `Interlocked` through a five-instruction `DynamicMethod` · the same, cross-process futex | `deftype`, full | **ported 2026-10-16** (`impls/README.md`): `:cljr` arms for the regions, the codec's host calls and the atom's process primitives; the JVM's arms serve it elsewhere (`:default`); the CLR's names at expansion (`cljr-translate-protos`); a capstone writer. Startup with eve compiled from source: ~17 s |
| 3 | **basilisp** | A | `ctypes` + the system `libatomic.so.1` · `mmap` + the same | `deftype`, full | **ported 2026-10-16** (`impls/README.md`): `:lpy` arms for the regions, the codec's host calls and the atom's process primitives; the JVM's arms elsewhere (`:default`); basilisp's interfaces at expansion (`lpy-translate-protos`); a capstone writer. What basilisp 0.5.1 does otherwise than Clojure is made Clojure's in `src/eve/lpy_host.lpy`, each measured: `unchecked-int` does not narrow; `ns` keeps only the last clause of each kind; `swap!`/`reset!` are deref + `compare-and-set!` (and a contended `(swap! a f)` with no extra args throws on retry); `if-let`, `if-some` and `if-not` require the else; `case` branches are functions (a `recur` in one fails); `"\u0000b"` reads five hex digits; a protocol method named `-foo` and implemented inline is dispatched as the field `foo` ("object has no attribute"); `iterate` calls `f` one element ahead (`(nth (iterate f x) 5)` calls it six times) and ends at a nil element; `interleave` nests a `concat` per step and its lazy seqs do not unwrap a lazy body that returns another, so `(count (interleave (range 3000) (range 3000)))` exceeds Python's recursion limit; no `reductions`, `chunked-seq?`, `list*`, `sorted-set`, `locking`, `hash-ordered-coll`. basilisp hashes its collections its own way (a vector as the tuple of its items), so on basilisp an eve collection hashes as the equal basilisp one does (a basilisp map or set finds the one by the other). And basilisp.test's fixtures are zero-argument functions, not clojure.test's `(fn [f] … (f))`, so eve's runner (`eve.runner.lpy-main`) applies the shared tests' fixtures itself. Host traits the sources go around: no char type; `True == 1` (a map cannot hold both as keys, so coop.scenario's data is not read there); a namespace that is also a directory is a Python package. Startup: 55 s compiling eve the first time, 1.8 s from basilisp's cache (measured 2026-10-16; `run.sh` builds the cache once). An op is slow: basilisp's own `dotimes` costs ~35 µs per iteration here, and a capstone `swap!` ~0.5–1 s |
| 4 | **nbb** | A | SAB + `Atomics` · EVE's addon | sci `deftype`, full | EVE's CLJS arm loads to `mem.cljc:405`, where a `deftype` is referenced before it is defined (sci reads in order) |
| 5 | squint | A | SAB + `Atomics` · EVE's addon | squint `deftype`, full | keywords and strings are one JS value and its CAS compares by identity: a squint port cannot round-trip other hosts' keyword data |
| 6 | **jolt** | B | `jolt.ffi` to libc and `libatomic`, nothing built · the same | `deftype`, colls: `swap!` / `reset!` / `compare-and-set!` are bound to jolt's own atom (ClassCastException) | **ported 2026-10-16** (`impls/README.md`): the JVM's `:clj` arms serve it (jolt reads them, and `eve/deftype.clj`); `:jolt` arms before `:clj` for the regions (`jolt.ffi`), the three `Integer` statics it lacks (`rotateLeft`, `toUnsignedLong`, `bitCount`) and the printers; a capstone writer. Measured in jolt v0.8.22, each worked around in eve: `swap!`, `reset!` and `compare-and-set!` cast any atom to jolt's own — an `IAtom` deftype answers `(instance? IAtom a)` true and still throws "cannot be cast to class clojure.lang.IAtom" (eve.atom rebinds the three to call another atom's `IAtom` methods; `alter-var-root` reaches calls compiled after it); no chunked seqs (`chunk-first` is the first element, `chunk-rest` a lazy `rest`, so an eve chunked seq's `rest` recursed until the stack ran out); `clojure.lang.ArrayChunk.` takes other arguments; `#'clojure.core/print-map` absent; `seq` rewraps any other type's seq in jolt's own cells, so an eve vector's seq lost its `IReduceInit` (`eve.core/seq` returns a type's own); `into` takes the transient path for jolt's collections only, so `(into eve-vec xs)` built one persistent vector per element and ran out of memory on test-splicing's 40,000 (`eve.core/into` goes through any `IEditableCollection`); `chunked-seq?` is compiled to a native test no rebinding reaches, false for every type but jolt's (eve's test asks `IChunkedSeq`). One finding was eve's, not jolt's, and is fixed for every host: a full heap slab class (one that cannot grow) was scanned whole, twice, on every allocation that spilled past it (`try-alloc` now reads the class's free count first) — test-splicing went from over 10 min to 24 s on jolt. ~18 s per start, compiling eve from source |
| 7 | **clojerl** | B | OTP `atomics` across BEAM processes, no extension · a 276-line C NIF | `deftype`, colls: the atom ops are hard-wired to `clojerl.Atom` | **ported 2026-10-17** (`impls/README.md`): `:clje` arms for the regions, the codec's host calls and the atom's process primitives; eve's NIF (`native/eve_clje_nif.c`: regions over an mmap'd file or heap memory, seq-cst atomics, futex, `fcntl` locks, term arrays), built on first load; what clojerl's core lacks — arrays, cells, locks, transients, fixed-width integers, `swap!` on another atom — in `eve.host` (HOST-CONTRACT §10); clojerl's protocols at expansion (`clje-deftype`, mutable fields as cells); a capstone writer. Measured in clojerl `4ad14e5`, each met in eve: a `def`'s value is compiled in as a literal term (no arrays, regions or delays: `const-bytes`, `const-objects`, `defdelay`); a `def`'s metadata is evaluated; a type hint is resolved where it is used, a function's return hint in its caller's namespace; `defprotocol` inside `do` fails; a protocol cannot be `:refer`red; `deftype` has no mutable fields and no `set!` on one; a method parameter named as a field is a duplicate variable in the compiled function; a fn that a top-level form (not a `def`) makes is run by clojerl's Core Erlang interpreter at every call; keywords are Erlang atoms (255 characters at most), dates whole seconds, no chars, no ratio literals; `slurp` reads Latin-1 unless given `:encoding`; `-pa` takes every following argument up to an option as a code path. Two clojerl bugs, in its own code: a vector's seq hashes its whole vector wherever it stands (`clojerl.Vector.ChunkedSeq:hash/1`), and its vector has no `reduce-kv`. Startup with eve compiled from source: ~20 s |
| 8 | **let-go** | B | a Go package linked into a custom `lg` (`cli.Main` + `rt.RegisterNS`) for both | Go types, maps: `disj` and the atom ops take only let-go's own types | the Go package; a measured five-line let-go patch (`eve/impls/let-go/proposal-let-go.patch`) makes TYPES full → A |
| 9 | **phel** | B | no threads (n/a) · PHP FFI + `libatomic` | PHP classes implementing phel's interfaces, colls: `swap!` is deref then `reset!`, no `compare-and-set!` in core | P1 needs `mod`/`quot`/`==` and a phel without the `recur`-in-`let` miscompile (measured 0.34.1; in-1's 0.54.0 needs PHP 8.5) |
| 10 | **bb** | B | `AtomicLong` cells · a Go pod (or `FileLock`, EVE's current route) | `deftype` map mode + `IAtom`, maps: lazy views of vectors / sets / lists read the mapping live but sci admits no route to `vector?` / `set?` / `list?` | already a host; list could become a lazy seq at the cost of `list?` |
| 11 | **jank** | B | `cpp/raw` for both, cross-process futex | C++ object types, reads: reads dispatch through behaviour bits + virtuals (15/15), writes through a closed switch (0/10) | wrapper fns or redefined `clojure.core` vars today; the jank change that would make it full is written up in `evidence/jank.md` ("How EVE's types could live in jank") |
| — | hy | N | `ctypes` + `libatomic` · the same | n/a | a rewrite in Hy |
| — | fennel | N | no threads · a Lua C module | n/a | a rewrite in Lua plus the C module |
| — | grenadine | — | — | — | not a host: a dependency resolver built on Glojure |

**The next hosts are glojure, cljr and basilisp** — the measured A's that
need no change to the host and run the shared HAMT file as is. For the BEAM,
Clojerl is the platform: the heap region needs nothing beyond OTP, the file
region is one NIF, every collection type is hosted, and the port supplies
the atom's write ops. let-go is one small upstream patch from A; jank's write
path is the change jank's own `object.hpp:354-360` already plans.

## 5. What each unmeasured Clojure-like resembles

None of these is measured; each line names the measured host it most
resembles **by design**, and the row of §3 a probe would check first. "In-1
only" means box 3 could not install it (§2). Clojerl and jolt, listed here in
the first version, are measured now (§3, §4).

| Clojure-like | Runtime | Resembles | The question a probe answers first |
|---|---|---|---|
| joker 1.10.0 (in-1 only) | Go interpreter | let-go: a fixed set of standard namespaces, no general Go interop | P4.2 — is any mapping reachable? (box 2 left unverified partial probes in `probes/joker/`) |
| cljgo 0.9.0 (in-1 only) | Go, AOT to Go source | glojure, but with the Go toolchain in the loop | whether its AOT path can import `syscall` / `sync/atomic` — if so, A-shaped regions |
| janet 1.42.1 (in-1 only) | C VM with an FFI | phel's regions (FFI to libc), hy's P1 (not a Clojure reader) | N, with the regions sub-level from `ffi/native` |
| yamlscript 0.3.5 (in-1 only) | sci in a GraalVM native image | bb | P4 — `FileChannel` / `FileLock` exposed by its sci config; P6 — sci's `deftype` limits |
| carp 0.6.0 (403 on every box) | native, no GC, C interop | jank, without Clojure semantics | N or not, from its reader |
| ClojureDart | Dart / Flutter, `dart:ffi` | phel's FFI route, with a real `deftype` | A-shaped if `dart:ffi` reaches `__atomic` on a mapping |
| ClojureWasm, ClojureRS / ClojuRS / rusjure, nanoclj, JO Clojure, Toccata | native (Zig, Rust, C, C++, Clang) | jank | P6 first — whether `deftype` exists decides A vs B |
| Lokke | Guile | basilisp (a VM with an FFI to libc) | P4.3 through Guile's FFI |
| Cloture, CLClojure | Common Lisp | basilisp, with native CAS in SBCL | P4.3 on a mapping |
| uclj, Cream | GraalVM native-image Clojure | the JVM if `VarHandle` survives native-image, else bb | P4.3 — does a `VarHandle` resolve |
| Venice | JVM, sandboxed | the JVM, minus what the sandbox forbids | which of `FileChannel` / `VarHandle` the sandbox allows |
| Cherry, Choq | squint family | squint | the value model (P3, and keyword ≠ string) |
| Lumo | Node, self-hosted CLJS | nbb | as nbb |
| Planck | JavaScriptCore, self-hosted CLJS | squint / nbb without Node's `fs` | P4.1 — is there a `SharedArrayBuffer` at all |
| Kalai, Rustly | transpilers to Rust / Java | their target language | a port is to the target, not to them |
