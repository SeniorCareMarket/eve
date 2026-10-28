# eve 0.9 — the adoption story in three tiers

Written 2026-10-20 (ET) on branch `claude/eve-0.9-release`, base `112f6ced2b`, derived from the code:
`eve/adapters/` (every directory listed), `eve/hosts.edn` (every row), `eve/doc/unification/HOST-CONTRACT.md`
(the contract's sections), and the shared code's own calls into each feature namespace (`grep` over `eve/src`).
Provenance: **[ran]** executed on this box this session (command given); **[read]** read from the named file;
nothing here is carried from another document without one of those.

The audience is the author of a Clojure-like language who wants eve on it.

---

## 0. What a host is

eve's shared code (`eve/src`) is written once and names no host (`eve/CLAUDE.md`, "eve is portable"; held at 0 by
`scripts/check-host-leak`, which reported `conditionals 0 predicates 0 host-files 1 net-interop 0` on this branch's
first commit [ran: the pre-commit gate]). A **host** is the list of adapter roots placed on the classpath after
`src`: one row of `eve/hosts.edn`. An **adapter** implements one **feature** (one interface namespace) over one host
capability, under `adapters/<feature>/<implementation>/src/`. Hosts with the same capability share the same adapter
(`process/java` serves the JVM, babashka and jolt).

So adopting eve is: choose, for each feature, an existing implementation your language can load, or write one;
list the roots in a new `hosts.edn` row; run the shared test suites on it.

## 1. The families

The brief names "a 12-family adapter capability matrix". The tree has **16** feature directories [read:
`ls adapters/`]. Twelve are what a host composes for eve proper and the network atom (core, coll, region, encoding,
slab, int32, codec, process, thread, types, http, crypto); four are seams or extras around them (completion — the
first half of core; slab-io — the memory seam; http-server — the gateway; typed-view — a host's own array views).
This document covers all sixteen and does not assume which twelve the brief meant.

| Feature | Interface ns | Contract | Names a host supplies | Implementations in the tree |
|---|---|---|---|---|
| region | `eve.region` | HOST-CONTRACT §1–2 | 3 fns (`make-heap-region`, `open-mmap-region`, `lustre-region?`) + a type implementing `eve.mem/IMemRegion` (17 methods) and `IHostAtomics` (2) | 9: beam clr ctypes glojure jolt-ffi js jvm-nio-lock jvm-varhandle vs |
| encoding | `eve.encoding` | §3–4 | 53 on the byte-array hosts (`encoding/java`); 29 where the codec is `codec/js` | 7: beam clr go java js python vs |
| process | `eve.process` | §5 | 11 (`now-ms` `thread-id` `sleep-ms!` `start-timer!` `stop-timer!` `on-process-exit!` `file-exists?` `create-exclusive!` `ensure-parent-dir!` `identity-hash` `random-bytes`) | 7: beam clr go java node python vs |
| types | `eve.types` | §6 | 10 (`host-type` `plain-type` `slab-type` `eve-type` `extend-eve-type` `existing-def` `rename-fields` `reader-of` `print-form` `vector-types`); hosts that reach eve memory through `ISlabIO` pass hooks to the shared `eve.deftype-proto.sio` | 9: basilisp bb beam clr glojure js jvm nbb vs |
| core | `eve.host` | §10 | ~55 names: arrays, fixed-width integer ops, cells, `locking`, transients, array casts, `const-bytes` `const-objects` `defdelay`, `swap!` `reset!` `compare-and-set!`, `class` `token`, `extend-default` | 4: clojure cljs-compiler beam vs |
| completion | `eve.host-completion` | §10 | whatever the host's core lacks of Clojure's (loaded first by `eve.host`) | 4: clojure (empty) basilisp jolt glojure |
| coll | `eve.core` | adapters/README "coll" | ~77–80: the collection vocabulary (`count` `nth` `get` `seq` `conj` ...) and ClojureScript's protocol names a type is written in (`-count` `-nth` ...) | 6: basilisp beam cljs jvm nbb protocols |
| slab-io | `eve.slab-io` | §12 | 28 (slab I/O, the domain, region words, fused `bytes-equal-at?` / `bitmap-*`) | 3 + conformance test: protocol cljs-compiler vs (`slab-io/test`) |
| slab | `eve.process-slab` | adapters/README "slab" | 1 (`init!`) on `slab/heap`; the module slab carries 68 | 2: heap module |
| int32 | `eve.int32` | adapters/README "int32" | 5 (`i32` `imul` `rotl` `ushr` `popcount`) | 4: js jvm portable vs |
| codec | `eve.codec` | adapters/README "codec" | 14 on `codec/byte-array`, 23 on `codec/js` | 2: byte-array js |
| crypto | `eve.crypto` | adapters/README "crypto"; network-atoms/ADAPTERS.md §4.1 | 8 (`sha256` `hmac-sha256` `aes256-ctr` `xxh3-128` `random-bytes` `ed25519-public-key` `ed25519-sign` `ed25519-verify`) | 7: beam clr java node portable python vs |
| thread | `eve.thread-host` | §11 | 14 (`run-thread!` the 15th on Node and the browser) | 8: beam browser clr glojure jvm node none python |
| http | `eve.http` | adapters/README "http" | `platform` → `{:request f}` (+ optional `:request-all`) | 7: beam browser clr go java node python |
| http-server | `eve.http-server` | adapters/README "http-server" | 1 (`serve!`) | 3: httpkit jdk node |
| typed-view | `eve.typed-view` | adapters/README "typed-view" | 12 (host-only extras; shared code never requires it) | 1: js |

Counts are top-level public defs [ran: a scratch census script over `src` and `adapters`, not kept; 162 files, 0 reader
errors] — except core's names, which `eve.host`'s `defcore` macro generates from its `names` vector
(`adapters/core/clojure/src/eve/host.cljc:23-31`, 54 names + `extend-default`).

What the shared code actually calls is narrower than some adapters export [ran: `grep -rhoE` over `src/`]:
`eve.region` — `open-mmap-region` (21 call sites), `make-heap-region` (5), `lustre-region?` (2);
`eve.process` — all of the 11 but `file-exists?` (a test's); `eve.int32` — all 5.

---

## 2. THE MINIMAL SET — what no default covers

The smallest set a new language must write itself. Every item is either the outside world (memory, the clock,
files, entropy, I/O) or the host's own type system; eve's portable code cannot spell either.

### 2.1 Always

| Feature | Why no portable default exists | Contract highlights (HOST-CONTRACT) | Smallest real example to copy |
|---|---|---|---|
| **region** | shared memory and its atomics are the host's | §1: a heap region is **zero-initialised**; an mmap region **grows, never truncates**, reads new bytes as zero, survives its handle's close, and is visible to every other mapping of the file in and across processes. §2: `-load-i32`/`-store-i32!` take **any alignment** (HAMT node interiors are unaligned); `-cas-i32!` **returns the witness**; i32 is **signed**; i64 exact to 2^53; wait/notify may be a bounded poll and a no-op returning 0. A host with no cross-process atomics implements the lock-only region (`IHostAtomics/-atomic-rmw?` false, `-with-file-lock`) — the Lustre / babashka pattern | `adapters/region/jvm-nio-lock` (290 lines; babashka: a `MappedByteBuffer` whose read-modify-writes a `FileChannel` byte-range lock makes exclusive) |
| **encoding** | strings, floats, the host's numbers, exceptions, map entries | §3: bytes are read through an unsigned accessor (a JVM `byte[]` is signed). §4: little-endian throughout; UTF-8 lengths are **byte** lengths; IEEE-754 bits unnormalised; UUID msb/lsb big-endian; `utf16-units` (a character's code is a host fact: `(int c)` is 0 on ClojureScript, network-atoms/ADAPTERS.md §2) | `adapters/encoding/java` (361 lines) or `encoding/python` (311) |
| **process** | the clock, sleep, timers, exit hooks, files, entropy | §5: `now-ms` fits 53 bits; `create-exclusive!` is true for exactly one creator (a domain's create-vs-join is decided by it, never by exists-then-create); `random-bytes` is the OS's entropy | `adapters/process/java` (76 lines) |
| **types** | a type written in eve's type language must become the host's own | §6: realize `eve.deftype-proto.vocab`'s protocol impls (JVM or ClojureScript names) as host types; printing hooks; the atom interface the host's `deref`/`swap!`/`reset!` dispatch to. A host whose types reach eve memory through `ISlabIO` passes its steps as hooks to the shared `eve.deftype-proto.sio` | `adapters/types/bb` (245 lines; sci's deftype, every interface translated onto `eve.core`'s protocols) |

### 2.2 Only when the host's core is not Clojure's

| Feature | When | Example |
|---|---|---|
| **completion** | the core lacks or answers differently some of Clojure's names (`eve.host-completion`, loaded first) | `completion/jolt` (66 lines: `swap!`/`reset!`/`compare-and-set!` taking any `IAtom`); `completion/basilisp` (805); `completion/glojure` (553 + three Go patches) |
| **core** | the core cannot be completed to Clojure's arrays, cells, locks, transients and wrapping integer ops | `core/beam` (clojerl: native memory through a NIF, cells in the process dictionary, the BEAM's global lock) |

### 2.3 Paired: coll goes with types

`eve.core` is the collection vocabulary every eve value answers. Which implementation a host takes is decided by its
types adapter, not chosen freely [read: hosts.edn pairs]:

* the host's `deftype` **cannot** implement its own collection interfaces → `coll/protocols` (inbuilt, §3) and a types
  adapter that translates each interface onto `eve.core`'s protocols — babashka, glojure, ValueScript;
* it **can** → a coll that calls the host's `clojure.core`, so eve values answer the host's own `count`/`get`/`seq`
  directly — `coll/jvm` (JVM, ClojureCLR, jolt), `coll/cljs`, `coll/nbb`, `coll/basilisp`, `coll/beam` (§4).

### 2.4 Per capability, only if you want it

| Capability | Feature to write | Why no default |
|---|---|---|
| network atoms (`eve.net`) | **http** — a **synchronous** request (`{:method :url :headers :body}` → `{:status :headers :body}` or `{:error msg}`) | I/O; synchronous by ruling ("Deref and swap will not be returning promises on any clojure-like impl", network-atoms/ADAPTERS.md §2) |
| a gateway (`eve.net.gateway`, a member serving its disk domain on eve.net's routes) | **http-server** — `(serve! {:port :host :threads} handler)` → `{:port :stop}` | I/O |
| real threads (`eve.thread`'s pools, `eve.thread.domain`) | **thread** — §11's 14 functions | the host's threads; the inbuilt `thread/none` runs eve single-threaded |

### 2.5 The minimal set, as a list

A new language whose core is Clojure's writes **four** namespaces — `eve.region`, `eve.encoding`, `eve.process`,
`eve.types` — and takes the inbuilt nine (§3). Measured by the hosts already in the tree [read: line counts], those
four are 650–1,100 lines on a JVM-family host (bb: 290 + 361 + 76 + 245 = 972) and similar elsewhere (basilisp:
128 + 311 + 73 + 470 = 982, before its 805-line completion). §7 runs exactly that composition.

---

## 3. THE INBUILT SET — portable, no host code

Nine roots that ship with eve and load on any host that reads `.cljc` (one, `coll/protocols`, is `.clj`) [read]:

| Root | What you get | Its cost (what the fast replacement buys back) |
|---|---|---|
| `slab-io/protocol` | `eve.slab-io` as 28 macros expanding to the protocol calls (`ISlabIO` on the slab I/O, `IMemRegion` on the region); nothing added at run time | two protocol dispatches per slab access; `slab-io/vs` replaces them with plain loads over linear memory (§4) |
| `core/clojure` | `eve.host`: each name a macro over `clojure.core`'s own call (type hints reach the host's compiler as written) | none where the core is Clojure's — this is also what the fast hosts use |
| `completion/clojure` | an empty `eve.host-completion` | none |
| `coll/protocols` | `eve.core` as eve's own protocols (ClojureScript's names), falling through to `clojure.core` for non-eve values | eve values answer `eve.core`'s vocabulary, not the host's `clojure.core` (on babashka `(count m)` of an eve map goes through `e/count`); one protocol dispatch per call |
| `slab/heap` | `eve.process-slab`: alloc's own `JvmSlabCtx` heap domain is the process slab (`init!` → nil) | no module slab, no WASM bitmap scan (ClojureScript's `slab/module`) |
| `int32/portable` | Murmur3 / FNV-1a / bitmap arithmetic by `quot`, `mod`, `*`, `+` on values kept in [0, 2^32) | slower hashing than a host's own 32-bit ops: §6 measures it |
| `codec/byte-array` | the whole codec (values ⇄ EVE bytes) over `eve.host`'s byte arrays and `eve.encoding` | ClojureScript's `codec/js` uses typed arrays, DataViews and scratch buffers instead |
| `crypto/portable` | all of `eve.crypto` from `eve.net.portable-crypto` (SHA-256, HMAC, AES-256-CTR, XXH3-128, Ed25519), random bytes from `eve.process` | 1–3 orders of magnitude on digests, the cipher and signatures: §6 |
| `thread/none` | `eve.thread-host` for one thread: `start-threads!` refuses (`:max-threads 0`), a thread sends only to itself | no parallelism: `eve.thread`'s pools have no threads; a host with threads plugs its own |

Two caveats a new host meets [read]:
* `thread/none`'s catch-all is spelled per host (`adapters/thread/none/src/eve/thread_host.cljc:53`:
  `#?(:cljs :default :cljr Exception :lpy python/BaseException :clje _ :glj go/any :default Throwable)`): a host
  whose root throwable is none of those adds its arm there — the one place an inbuilt root names hosts.
* `core/clojure`'s `extend-default` extends `Object` (`:default`) or `default` (ClojureScript): a host whose root
  type is neither needs its own core adapter.

---

## 4. THE FAST SET — native seams a host plugs in instead

Per capability: the contract the replacement must keep, the inbuilt default it replaces, and a worked host
example in the tree. A fast adapter may implement part of its feature and delegate the rest by name to the
portable code (`crypto/python` delegates XXH3, `crypto/clr` XXH3 and Ed25519 [read]); the conformance tests hold
every replacement to the portable answer byte for byte.

| Capability | Contract kept | Inbuilt default | Fast implementations (hosts) | Worked example | Held to |
|---|---|---|---|---|---|
| **int32** | signed 32-bit results identical on every host | `int32/portable` | `int32/jvm` (JVM, bb, jolt; ClojureScript's compiler), `int32/js` (ClojureScript, nbb), `int32/vs` | `adapters/int32/jvm/src/eve/int32.clj` (31 lines: `unchecked-int`, `Long/bitCount`) | the byte fixtures' pinned hashes (`eve.eve-bytes-fixture-test`, `eve.hamt-util-test`) |
| **crypto** | byte-for-byte the portable answers; `ed25519-verify` refuses exactly what `eve.net.ed25519/well-formed?` refuses | `crypto/portable` | `crypto/java` (JVM, bb), `crypto/node` (Node, nbb), `crypto/python` (basilisp), `crypto/clr`, `crypto/beam`; `crypto/vs` exists but no host row uses it | `adapters/crypto/java/src/eve/crypto.clj` (66 lines: MessageDigest, Mac, Cipher, Signature "Ed25519") | `test/eve/net/crypto_test.cljc`, `ed25519_test.cljc` |
| **codec** | values ⇄ EVE bytes, the format of FORMAT-DECISIONS | `codec/byte-array` | `codec/js` (ClojureScript, nbb) | `adapters/codec/js/src/eve/codec.cljs` (1,385 lines) | the byte fixtures (`test/eve/eve_bytes_fixture_test.cljc`) |
| **coll** | the `eve.core` vocabulary | `coll/protocols` | `coll/jvm` (JVM, ClojureCLR, jolt: `clojure.core` inlined through `clojure.lang.RT`), `coll/cljs`, `coll/nbb`, `coll/basilisp`, `coll/beam` | `adapters/coll/jvm/src/eve/core.clj` (189 lines) | every shared suite |
| **slab** | `eve.process-slab` | `slab/heap` | `slab/module` (ClojureScript, nbb: module slabs, one WASM instance per slab, SIMD bitmap scan) | `adapters/slab/module/src/eve/process_slab.cljs` (1,324 lines) | `node target/eve-test/all.js slab`, `epoch-gc` |
| **slab-io** | §12; `adapters/slab-io/test` is the conformance test | `slab-io/protocol` | `slab-io/vs` (plain functions over linear memory: a slab offset is an address in two loads) | `adapters/slab-io/vs/src/eve/slab_io.vs` (242 lines) | `adapters/slab-io/test/eve/slab_io_test.cljc` (`bb test:vs`) |
| **region** (always host code; fast = hardware atomics) | §1–2 | none — but a lock-only region is the slow form | `region/jvm-varhandle` (VarHandle atomics, park/unpark), `region/js` (SAB + Atomics, the N-API addon), `region/clr`, `region/ctypes`, `region/jolt-ffi`, `region/beam`, `region/glojure`, `region/vs` | `adapters/region/jvm-varhandle` (626 lines) vs `jvm-nio-lock` (290) | every shared suite; the coop capstone |
| **thread** | §11; `test/eve/thread_host_test.cljc` | `thread/none` | `thread/jvm` (JVM, bb, jolt), `thread/node`, `thread/browser`, `thread/beam`, `thread/python`, `thread/clr`, `thread/glojure` | `adapters/thread/jvm/src/eve/thread_host.clj` (97 lines) | `eve.thread-host-test` on every runner |
| **typed-view** | host extras only | none | `typed-view/js` | `adapters/typed-view/js/src/eve/typed_view.cljs` (194 lines) | `node target/eve-test/all.js typed-array` |

---

## 5. Capability × tier

`M` minimal (the host writes it), `I` inbuilt (portable, ships with eve), `F` fast (a native replacement for `I`),
`C` per-capability (only for that capability).

| Family | Tiers | Inbuilt default | Hosts plugging a fast / own implementation today |
|---|---|---|---|
| region | M | — | every host its own (9 implementations) |
| encoding | M | — | every host its own (`encoding/java` shared by JVM, bb, jolt) |
| process | M | — | every host its own (`process/java` shared by JVM, bb, jolt; `process/node` by ClojureScript, nbb, the browser) |
| types | M | — | every host its own (`types/jvm` shared by JVM, jolt and ClojureScript's compiler) |
| core | I, M if the core is not Clojure's | `core/clojure` | `core/beam` (clojerl), `core/vs` (ValueScript), `core/cljs-compiler` (shadow's macro side) |
| completion | I, M if needed | `completion/clojure` | `completion/basilisp`, `completion/jolt`, `completion/glojure` |
| coll | I or F (paired with types) | `coll/protocols` (bb, glj, vs) | `coll/jvm` (JVM, cljr, jolt), `coll/cljs`, `coll/nbb`, `coll/basilisp`, `coll/beam` |
| slab-io | I, F | `slab-io/protocol` (everyone but vs) | `slab-io/vs` |
| slab | I, F | `slab/heap` (all but cljs, browser, nbb) | `slab/module` (cljs, browser, nbb) |
| int32 | I, F | `int32/portable` (cljr, lpy, clje, glj) | `int32/jvm` (jvm, bb, jolt), `int32/js` (cljs, browser, nbb), `int32/vs` |
| codec | I, F | `codec/byte-array` (all but cljs, browser, nbb) | `codec/js` |
| crypto | I, F, C (network atom) | `crypto/portable` (jolt, vs, glj, browser) | `crypto/java` (jvm, bb), `crypto/node` (cljs, nbb), `crypto/python`, `crypto/clr`, `crypto/beam` |
| thread | I, F, C (threads) | `thread/none` (no shipped host row; `:bb-minimal` uses it) | `thread/jvm` (jvm, bb, jolt), `thread/node` (cljs, nbb), `thread/browser`, `thread/beam`, `thread/python`, `thread/clr`, `thread/glojure`; none on the vs row |
| http | C (network atom) | — | `http/java` (jvm, bb), `http/node` (cljs, nbb), `http/python`, `http/clr`, `http/beam`, `http/go` (glj), `http/browser` (browser); none on jolt, vs |
| http-server | C (gateway) | — | `http-server/jdk` (jvm), `http-server/httpkit` (bb), `http-server/node` (cljs) |
| typed-view | C (host extra) | — | `typed-view/js` (cljs, browser, nbb) |

### 5.1 Host × family, as `hosts.edn` lists it

Generated from `eve/hosts.edn` [ran: a bb script over the file]. `a+b` = both roots on the classpath (ClojureScript
and the browser carry the JVM's adapters for the compiler's own macro-time Clojure); `—` = no root.

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

Unreferenced implementations [ran]: `crypto/vs` (no host row: `hosts.edn :vs` takes `crypto/portable`, and its own
comment says "VS has no native crypto adapter" — the adapter exists, unwired), `thread/none` (the inbuilt default no
shipped host needs), `slab-io/test` (the conformance test root, added by `bb test:vs`).

---

## 6. What the fast seams buy — measured

[ran] 2026-10-20, babashka v1.13.226 on this box, one process per classpath, the same script
(`examples/seam_bench.clj`: median of 5 runs after 2 warm-ups, milliseconds; run in
`doc/adopting-eve-fast.md`). "Fast" is `hosts.edn :bb` as shipped (`crypto/java`, `int32/jvm`, `thread/jvm`);
"inbuilt" is the §7 composition (`crypto/portable`, `int32/portable`, `thread/none`).

| Operation | Inbuilt (portable) | Fast (bb's own) | Ratio |
|---|---|---|---|
| SHA-256, 16 KiB | 147.44 ms | 1.00 ms | ~147× |
| AES-256-CTR, 16 KiB | 209.02 ms | 2.31 ms | ~90× |
| Ed25519 sign, 200 B | 977.48 ms | 0.84 ms | ~1,160× |
| Ed25519 verify, 200 B | 894.15 ms | 0.92 ms | ~970× |
| XXH3-128, 16 KiB | 21.34 ms | 35.03 ms | — (portable on both: `crypto/java` delegates XXH3; the gap is run-to-run noise between processes, not an adapter difference) |
| Murmur3 over 10,000 keys (`eve.hamt-util/portable-hash-bytes`, i.e. `eve.int32`) | 133.15 ms | 70.11 ms | ~1.9× |
| `eve.thread-host/driving` | `{:parallel false :max-threads 0 :runtime :none}` | `{:parallel true :max-threads nil :runtime :bb}` | threads vs none |

Re-measured on the final tree (the final pass, 2026-10-21 11:40 PM ET, one run each, `logs-2026-10-22/f-seam-*.log`;
`doc/adopting-eve-fast.md` §2 prints both): SHA-256 157.86 / 1.46 ms (~108×), AES-256-CTR 186.02 / 3.31 (~56×),
Ed25519 sign 741.20 / 0.69 (~1,074×), verify 792.94 / 0.73 (~1,086×), XXH3-128 16.73 / 16.63, Murmur3 134.59 / 37.50
(~3.6×). Each seam alone on the inbuilt set: `crypto/java` gives SHA-256 0.90 ms, AES 2.00, sign 0.71, verify 0.76
(`f-seam-bbm-cryptojava`); `int32/jvm` gives Murmur3 39.74 ms (`f-seam-bbm-int32jvm`).

What it means for an adopter: a host on the inbuilt crypto pays ~0.15 s per 16 KiB block hashed and ~0.2 s
encrypted, and most of a second per signature — workable for a test run, not for a network atom with real traffic
(every stored block is hashed, and encrypted under a group key). **Native crypto is the first fast seam to plug.**
int32 cuts the hash cost of every HAMT operation to half or less (1.9× on 2026-10-20, 3.4–3.6× in the final pass). Threads turn `eve.thread`'s pools on.

---

## 7. Cross-check: a host built from the minimal set and the inbuilt set only

The claim of §2.5 — four host namespaces plus the inbuilt nine make a working eve — run on babashka, whose
`hosts.edn` row is the closest to it [ran, 2026-10-20]. The composition is babashka's four minimal roots and the nine
inbuilt ones; every other root of `hosts.edn :bb` (`crypto/java`, `int32/jvm`, `thread/jvm`, `http/java`,
`http-server/httpkit`) is left off:

```
src
adapters/region/jvm-nio-lock/src  adapters/encoding/java/src  adapters/process/java/src  adapters/types/bb/src   ; minimal
adapters/slab-io/protocol/src  adapters/core/clojure/src  adapters/completion/clojure/src  adapters/coll/protocols/src
adapters/slab/heap/src  adapters/int32/portable/src  adapters/codec/byte-array/src  adapters/crypto/portable/src
adapters/thread/none/src                                                                                       ; inbuilt
```

| Run | Result |
|---|---|
| base: `bb test:bb` (bb's shipped row, every shared namespace) | **231 tests, 3135 pass, 0 fail, 0 error**, 7 m 00 s |
| minimal + inbuilt, every shared namespace but `eve.thread-net-test` (`bb -cp <the roots above>:test -m eve.runner.bb-main test <25 namespaces>`) | **226 tests, 3010 pass, 0 fail, 0 error**, 6 m 21 s |
| minimal + inbuilt, all 26 namespaces | does not load: `eve.thread-net-test` requires `eve.net`, whose `eve.http` no root supplies — the network atom is a per-capability feature (§2.4), as designed |
| minimal + inbuilt + `http/java`, `eve.thread-net-test` alone | 5 tests, 32 pass, **1 error** — the same error on bb's shipped row run alone at base (5 / 32 / 1): `Can't dynamically bind non-dynamic var #'eve.net.codec/*memo*`, a defect of the shared code (§8.5), not of the composition |

So the composition answers every data-structure, codec, fixture, domain, key-ring, sorted-collection, RRB-vector and
type-id suite, and the thread feature's conformance and executor suites on `thread/none` (eve.thread's pools run their
descriptors on the calling thread when `:max-threads` is 0). The difference from base is exactly the network
namespace's 5 tests / 125 assertions. The adopter guide (`doc/adopting-eve-minimal.md`) carries this composition as a
`hosts.edn` row a reader can run.

---

## 8. Defects found while tiering

1. **`hosts.edn :glj` has no `eve.slab-io` root** [ran]. 16 shared namespaces require `eve.slab-io`; the `:glj` row
   lists no `slab-io/*` root. At base, `bb test:glj` exits 1 after 1.4 s: `error evaluating
   eve/eve_bytes_fixture_test.cljc: error evaluating eve/deftype_proto/alloc.cljc: failed to load /eve/slab_io: not
   found in load path`. With `adapters/slab-io/protocol/src` added, `eve.map` loads. `eve/CLAUDE.md`'s
   "bb test:glj → 149 tests, 2403 pass (2026-10-20)" does not hold at this base. Fix: one root in `hosts.edn :glj`
   (Phase 2 of this lane, with `bb test:glj` re-run). **Fixed in `007d4edc8`.**
2. **`adapters/README.md`'s Features table repeats four features** (slab, typed-view, int32, codec appear twice), and
   the copies disagree: the second slab row lists ValueScript where the first lists glojure (`hosts.edn :vs` and
   `:glj` both take `slab/heap`); the codec rows list `encoding/vs` and `encoding/go` as codec implementations; the
   second int32/portable row drops glojure. The table this document carries (§5.1) is generated from `hosts.edn`.
   **Fixed in `007d4edc8`** (one row per feature now).
3. **`crypto/vs` is unwired** (§5.1).
4. **`thread/none` names hosts** in its catch-all (§3) — inside an adapter, so not a host leak by `check-host-leak`'s
   rule, but the one inbuilt root a new host must touch.
5. **The network atom's first gateway deref fails on babashka** [ran]. `eve.net.codec`'s `coll-rec` carries four
   top-level forms inside its body (`(def ^:dynamic *memo* …)`, `*decoded*`, `MEMO-MAX`, `remember!`), put there by the
   ET-3 collation merge (`e23b14903`, which composed ET-3's memo with MD-4's restructured codec). The JVM interns and
   marks the vars dynamic when it compiles the body; sci does so only when `coll-rec` first runs, so a gateway's
   `(binding [codec/*memo* …])` before any encode throws. In the full suite an earlier test encodes first, which is why
   `bb test:bb` reads green; alone, `eve.thread-net-test` errors. `*memo*` is bound by the gateway and read nowhere
   since the merge (MD-4's pruned swap replaced the encode memo); `*decoded*` is read (`decode-coll`). Fix: the four
   forms back at top level (Phase 2 of this lane). **Fixed in `007d4edc8`.**
