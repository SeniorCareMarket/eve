# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **aegis's clear-evaluation primitives, mainlined** (2026-10-23): `eve.aegis.claims` (with the reroute's spec id
  `SHA-256(spec bytes ‖ release hash)`, a group's `:anonymity` `:ballot` / `:ring`, `:commitFn "sha256"`, key-assoc
  roots bound to a broker's stored map, ballot spends held to the issuer's signed per-scope count, claim builders and
  signers, `next-state` / `next-frontier`, the JSON wire), `eve.aegis.ballot` (+ `request-message`, `sign-statement`),
  `rsabssa`, `clsag`, `h2c`, `merkle`, `session`, `commit`. Their suites joined the babashka and JVM batteries
  (`bb-minimal` names them out: its portable crypto signs the issuer's RSA row past 30 min). `crypto/vs-kernel`, a
  crypto implementation for ValueScript programs that build the kernel's ed25519.wasm. `test/eve/aegis/claims_bench.cljc`.

### Changed

- **`$` is the read-region macro it was designed as** (`eve.alpha/$`). `(e/$ body...)`: inside it `@` of an
  eve atom answers the stored value, lazy and slab-backed, so a branch of a large atom is reached without the rest
  being materialized; each domain is pinned on its first read and every pin held until the outermost `$` exits (a
  network atom's deref keeps its lease the same way); the body's result is materialized as it leaves; a `$` inside
  another joins it. `src/eve/read_region.cljc`, `test/eve/read_region_test.cljc` (every host). The function the 0.9
  tree shipped as `eve.atom/$`, `($ a f & args)` (the unification's verdict, `doc/unification/
  CANDIDATE-VERDICTS.md` §3, `b5c2ffc59`), is renamed `eve.atom/read-pinned`: eve's own bounded reads use it. Not
  carried from the design: its 30 s lease heartbeat inside a region (production's Worker has no renew route, and
  `eve.net.atom/renew!` releases a lease the broker does not know) and its read-set tracking (`eve.net.atom`'s named
  limit: a plain value records no reads).
- h2c's `C2` / `C1-ED` and clsag's `BASE` / `IDENTITY` are literals, each held to its computation by its test: VS's
  compiler evaluates a top-level def's init as it compiles, and `C1-ED`'s field square root alone took analysis to the
  slab's 32,753-page ceiling.

### Fixed

- **A list stored on glojure.** eve's list writer walked its elements with `(reduce f init (rseq elems))`, and
  glojure's `reduce` has no `coll-reduce` method for a vector's rseq ("No method in multimethod 'coll-reduce' for
  dispatch value: *lang.apvRSeq"), so storing any list in an eve atom threw there; it walks them back to front by
  index now — the same nodes in the same order (`src/eve/list.cljc`). Found by main's rewritten tally test, the
  first to store a list on glojure.

## [0.9.0] — prepared 2026-10-20, unreleased

0.9 is the API and host contract 1.0 will freeze, published for adopters to build against. Between 0.9 and 1.0 the
public namespaces (`doc/api-guide.md`) change only with an entry here under *Changed* or *Removed*; the host contract
(`doc/unification/HOST-CONTRACT.md` §1–§12) grows only by optional features. **The on-disk and wire formats may still
change before 1.0 without a migration**: the slab and root files carry a magic but no format-version field yet
.

Assembled from the tree's changes since the standalone repository's `c7dc390` (2026-10-15) — about 340 commits,
counted 2026-10-22 ET. Changes between 0.1.0 and `c7dc390` are in that repository's history and are summarized by
what the tree now contains.

### Added

- **Ten hosts, one shared source.** eve's shared code (`src/`) names no host but one ClojureScript-only file
  (`src/eve/shared_atom.cljs`) — no reader conditional keyed by a host, no branch on the host, no host method call in
  eve.net, each held at 0 by `scripts/check-host-leak` (`.host-leak`) — and a host is a list of adapter roots
  (`hosts.edn`): the JVM, ClojureScript on Node and in a browser, babashka, nbb, ClojureCLR, basilisp, jolt, clojerl,
  glojure (as `glj-eve`, `impls/glojure/build.sh`). A writer from each of the JVM, ClojureScript on Node, babashka,
  nbb, ClojureCLR, basilisp, jolt and clojerl shares one atom (the coop scenario, `test/coop/`).
  (`hosts.edn` also carries a ValueScript row — partial, no eve types or threads yet; ValueScript is not
  part of the 0.9 release.)
- **Adapters by feature** (`adapters/<feature>/<implementation>/`): region, encoding, process, types, core
  (`eve.host`), core completion, coll (`eve.core`), slab, slab-io (the memory seam, `eve.slab-io`), int32, codec,
  crypto, thread, http, http-server, typed-view — with portable implementations a new host takes as they are
  (`slab-io/protocol`, `core/clojure`, `coll/protocols`, `slab/heap`, `int32/portable`, `codec/byte-array`,
  `crypto/portable`, `thread/none`). `doc/adopter-tiers.md`, `doc/adopting-eve-minimal.md`,
  `doc/adopting-eve-fast.md`.
- **Atom domains as atoms.** A domain is itself an atom (slot 0, its registry); member atoms are slots of one
  domain-state. `eve.atom/meta-atom` (a coordination atom beside an atom), `registered-name`, `version`, `seal`
  (a byte-exact digest of a stored version), `$` (the read region: zero-copy reads under pins held to its end),
  `compare-and-set!*`, `domain-lookup`.
  A fresh disk domain opened by several processes at once is created once (`create-exclusive!`); of processes
  creating one named atom at once, exactly one registers it. `doc/atom-domains.md`.
- **Network atoms** (`eve.net.*`): an eve value at a broker or a gateway, synchronous `deref` / `swap!` / `reset!` on
  every host with an http adapter, blobs fetched and stored directly through presigned URLs, per-blob encryption under
  a group key (`:gak`), the pruned swap (a one-key swap HEADs and encodes only what changed), and `eve.net.gateway` (any member of a disk domain serves its atoms on the
  broker's routes; a network CAS is one eve swap). Portable SHA-256, SHA-512, HMAC, AES-256-CTR, XXH3-128, Ed25519 and
  Poseidon2 / BN254, with native crypto adapters on the JVM, babashka, Node, basilisp, ClojureCLR and clojerl.
  `doc/network-atoms/README.md`.
- **Encryption at rest** (`eve.keyring`): a disk domain's slab blocks under AES-256-CTR with HKDF per-class keys; three
  key modes. See `SECURITY.md` for what it does not protect.
- **The thread feature and executor service** (`eve.thread`): `future`, `promise`, `pmap`, `pcalls`, `pvalues`,
  `with-pool`, fixed / single / cached / per-task / scheduled pools, `deftask` / `defconveyed` / `tfn`; one adapter
  per host's threads (`eve.thread-host`, HOST-CONTRACT §11) with a conformance suite. `eve.thread.fold` (`fold`,
  `=>>`, chunks sized from the measured swap cost); `eve.thread.domain` (pools, a registry, election and bring-up in
  a domain's meta-atom, across processes and hosts); `eve.thread.rung` / `eve.thread.net` (domain cells on heap, disk,
  tmpfs windows or a network atom).
- **Collections written once for every host**: `eve.sorted` (sorted maps and sets with comparators, `subseq`,
  `rsubseq`, `rank`, `nth-entry`); `eve.rrb-vector` (core.rrb-vector's ClojureScript implementation in eve memory, the
  worked example of an eve type; EPL-1.0, see `NOTICE`); int-map and the red-black sorted set as one `eve-deftype`
  source each; big values out of line (`eve.deftype.value-buffer`); columnar datasets, tensors and column ops
  (`eve.alpha.ds`, `eve.alpha.tensor`, `eve.alpha.col`).
- **One byte format, pinned by fixtures** on every host (`test/eve/fixtures/eve-bytes.edn`,
  `doc/unification/FORMAT-DECISIONS.md`): characters, big integers, ratios, big decimals, references (process-private),
  value blocks, records and typed arrays among the tags.
- `bb test:<host>` runners for every host but the JVM and ClojureScript on Node (`bb.edn`; those two run
  `clojure -M:jvm-test` and `node target/eve-test/all.js`), `bb test:bb-minimal` (babashka on the minimal and inbuilt
  adapters only), the multi-process scripts `scripts/thread-rendezvous.clj`, `thread-rotation.clj`,
  `thread-fold-rendezvous.clj`.
- `SECURITY.md`, `NOTICE`, `epl-v10.html`, `doc/memory-model.md`, `doc/adopter-tiers.md`.
- **The browser's http adapter** (`adapters/http/browser`: synchronous XHR, bytes as base64 through the
  `x-user-defined` charset): network atoms in a page (`hosts.edn :browser`). In a worker a batch (a level of a network
  atom's blocks) is fetched by the page at once, the worker waiting on a SharedArrayBuffer of its own; eve's browser
  thread adapter hands a worker's own messages to the page's hook (`globalThis.__eve_worker_message`) for it.
- **The http-server feature on Node** (`adapters/http-server/node`: Node's `http` module): ClojureScript on Node can
  serve a gateway (`hosts.edn :cljs`).
- **A performance suite on every host** (`test/eve/perf_suite.cljc`): ten workloads over eve's public API (atom swaps
  and derefs at small and 1,000-key sizes, a zero-copy read, building sorted sets and int-maps, a disk-domain swap,
  64 futures), each in time-budgeted doubling rounds; every host's runner has a `perf` command, and
  `bb scripts/perf-all.clj` runs them all into `doc/hosts/perf-results.edn`.
- **A page per host** (`doc/hosts/`): what was measured there, how eve is built on it, its gotchas, its next steps.
- **The capstone, `capstone/eve-media/`**: a media workbench on six host runtimes and a browser — babashka, the JVM,
  Node/ClojureScript, Go (glojure), the BEAM (clojerl), jolt (FFmpeg's libraries through its FFI), and ClojureScript in
  Chromium as a runtime of its own (a worker pool, over the network rung; the page in reagami) — one disk domain, every
  job a thread descriptor in eve.thread's pools, the coordinator by election.
  `bb eve-media demo | scan <dir> | verify`.

### Changed

- **`e/atom` is `eve.atom`'s atom on every host**: in process, a heap domain per `:id` (on ClojureScript the module's
  SharedArrayBuffers); with `:persistent`, a named atom in a disk domain. The 0.1.0 SharedArrayBuffer atom
  (`eve.shared-atom`) is no longer what `e/atom` returns; nothing in the tree requires it.
- **Deref materializes**: `@a` answers the value as the host's own collections. Zero-copy access is `swap!`'s function
  argument and the read region `eve.alpha/$`, inside which `@a` answers the stored value.
- **The JVM's regions** are `MappedByteBuffer` / `ByteBuffer` with `VarHandle` atomics — no Panama FFM, no
  `sun.misc.Unsafe`. JDK 21 is the tested floor (virtual threads in `thread/jvm`).
- **babashka** realizes eve types (`adapters/types/bb`) and takes part in cross-process domains through a lock-only
  region (`FileChannel` byte-range locks) and exclusive holds; it has the in-process heap mode too.
- The HAMT trie index is one 32-bit `mask-hash` on every host; maps and sets written before the change stay readable.
- Symbols past 255 bytes are refused on every host (they were truncated).
- **The tally's format** (9.31): the `.root` header's word at byte 32 (`ROOT_TALLY_OFFSET`) roots count pages, ordinary slab
  blocks (`doc/unification/FORMAT-DECISIONS.md` §11); a write that raises a count commits under the domain's exclusive
  hold.

### Removed

- `atom-domain`'s `:sab-size` and `:max-blocks` options (accepted and ignored), watches (`add-watch` on an eve atom),
  the X-RAY diagnostics and `validate-storage-model!` (both only on the unreachable `eve.shared-atom`).
- The JVM's thread-local allocation logs (one portable `LoggingSlabIO`), the `*lustre-mode*` dynamic var (`:lustre?`
  is now an option of `persistent-atom-domain`).

### Fixed (in the 0.9 release preparation)

- glojure could not load eve: `hosts.edn :glj` lacked the `eve.slab-io` root, and `eve.thread.rung` / `eve.thread.net`
  defined records with inline protocol methods, which glojure v0.7.22 refuses (now records extended by `extend-type`,
  as `eve.thread.cell` already did).
- A network atom's first deref through a gateway failed on babashka: four top-level forms of `eve.net.codec` sat inside
  `coll-rec`'s body after a merge, so `*memo*` was not dynamic until the codec first ran.
- `bb run atom-swap` (the example) and `bb -e` from `eve/` ran on a stale five-root classpath (`bb.edn :paths`).
- `eve.http-server`'s JDK adapter: `:stop` left the server's thread pool running, so a JVM that stopped a gateway did
  not exit.
- A network atom could not carry an integer past int32: a stale copy of the snapshot reader in `eve.net.atom` lacked
  the long's tag the writer emits, so the gateway refused every such write.
- clojerl could not load `eve.net.merkle` (its constants were `def`s of delays, which clojerl compiles as literals), so
  `bb test:clje` stopped at load: the constants are now kept in an atom, computed at first use.
- A network swap whose function answers the value it was given now commits nothing; eve.thread's starved writer over
  the network rung could not take its turn before (its unchanged answer was CASed, and lost, again and again).
- **Memory, three hosts.** glojure's and jolt's batteries together were OOM-killed on a 15 GB box (glj-eve at 7.1 GB,
  jolt at 5.8 GB), and nbb's battery passed 12 GB:
  - glj-eve now collects at Go's default `GOGC` (100) unless the environment sets one: glojure sets 400, letting the heap
    grow to five times the live data (`bb test:glj`: 2.8 GB peak, its process tree).
  - jolt's heap regions are immobile bytevectors in Chez's heap, reclaimed with the region and bounded by jolt's heap
    ceiling as the JVM's are by `-Xmx` (they were foreign memory, never released); mapped regions are unmapped once
    unreachable (`bb test:jolt`: 1.7 GB peak; the two batteries side by side: 445 s, green).
  - On Node and nbb a disk domain's heartbeat timer is unref'd, as every other host's is a daemon: a worker that opened
    a disk domain never ended, and held its isolate (~320 MB on nbb) for the life of the process. A Node program with a
    disk domain open now also exits when its work is done.
- basilisp: numeric arrays (`long-array`, `int-array`, `short-array`, `float-array`, `double-array`) start at zero, as
  Clojure's — basilisp's are n nils, and every namespace loading `eve.net.poseidon2` failed; `sorted-map` and
  `sorted-map-by`, which basilisp lacks (`eve.net.atom`'s snapshot entries).
- glojure: `sequence` with a transducer, `dedupe` of a collection and `partition-all`'s transducer reached JVM classes
  glojure lacks (`eve.thread.fold`'s suites: 47 errors); they are Clojure's in eve's glojure completion.
- A browser worker closes itself once its entry has returned (it stayed resident until the page went away).
- **A fresh map or set written into an atom keeps only its tree.** The HAMT writers path-copied one entry at a time
  and nothing freed the copies: a fresh 200-entry map allocated 391 blocks and kept 54, so every such `reset!` lost
  ~340 blocks and a heap domain filled after 32. Each writer step now frees what its own (never published) path copy
  superseded; eve.set's conj and eve.map's deep-collision assoc free the node they push down; the allocation log is a
  set a self-free leaves.
- An int32 field (an int-map's and the red-black tree's children) can hold a slab offset past 2^31: the field writer
  cast with a checked `int`, so a node threw "integer overflow" once its heap domain's small classes filled and its
  blocks spilled into class 4 and up.
- `bb test:nbb`, `bb test:cljr` and `bb test:jolt` dropped the namespaces named after the task.
- **A browser page could hold three eve kernels.** Each ClojureScript kernel that loads `eve.alpha` reserved its module
  slab at full capacity — six SharedArrayBuffers of 4 GB in all and a 1 GB overflow one — and Chromium caps the
  SharedArrayBuffer bytes a renderer holds at ~16 GB (measured: 15 × 1 GB in a page, then 3 × 256 MB in a worker), so
  a page and its first two workers filled it and the next worker died at start. In a browser a module slab is now a
  growable SharedArrayBuffer (Chromium 111+) at its class's initial capacity, doubling on out-of-memory, the buffer
  grown before the header publishes the new block count; the overflow region likewise, its grown tail merged into
  the free block before it (on mmap domains too). A growable SharedArrayBuffer counts by its length, not its maximum
  (400 of 1 GB maximum each fit beside the 15 GB). Node keeps fixed buffers — it has no such cap, and growable ones
  cost its in-memory work a median 1.06× (0.95–1.24×; one bundle, A/B, the median of three runs each) — unless
  `ps/init!` is given `:growable? true` (the Node suite `slab-grow` runs the slab suites so).
- `JsSabRegion`'s emulated 64-bit store wrote the low word into the high word as well (JS masks a shift count to five
  bits, so `(unsigned-bit-shift-right v 32)` is `v`); nothing read a 64-bit field through a SharedArrayBuffer region
  before the overflow region's growth.
- **The JVM's memory access ran through reflection.** Every aligned load, store and CAS of the JVM's region called
  `MethodHandle.invokeWithArguments` with a vector, which Clojure resolved by reflection at each call
  (`Class.getMethods` and `copyMethods` were most of a swap's samples under JFR), and the unaligned reads picked
  `ByteBuffer.get`'s overload the same way. The region now spreads each handle once to `(Object[])Object` and
  calls `.invoke` on a typed array — 139.8 ns a volatile get with `invokeWithArguments` against 19.1 ns so — and
  types its offsets. A counter `swap!` on the JVM: 169 µs → 28.3 µs (the reflection gone) → 14.7 µs (the spread
  handles), a 20 s / 10 s loop each (2026-10-20 ET; the performance suite's counter swap, 11.8 µs in the final pass,
  2026-10-21 ET). With them, the shared code's reflective sites on the JVM: the slab growth
  scan before each disk-domain swap, the bitmap scan's bytes, the codec's tagged header, `eve-deftype`'s offset
  accessor, the keyring's XOR loops, typed arrays and objects, `seal`'s digest, the http adapter — 73 sites in the
  core namespaces and 13 beyond them, none left but core.rrb-vector's 155 field reads on its untyped nodes and vectors.
- **A cancelled periodic schedule could run once more.** A periodic run is queued on the pool as its own descriptor,
  and was checked for cancellation only when fired: a run waiting in the queue when `future-cancel` returned ran
  after it (basilisp's battery: "cancel stopped it", 7 runs where 6). It checks its future first now: a cancelled
  periodic runs no more, as the JVM's `ScheduledFuture` (a run already started finishes).
- basilisp: `true?`, `false?`, `seqable?`, `supers`, `peek` and `pop` evaluated their argument twice (basilisp
  inlines a call by putting the argument's form into the function's body, and these name their parameter twice):
  `(true? (create-exclusive! f))` created a file, then found it there. eve's basilisp completion removes their
  `:inline`, so a call compiled after it evaluates its argument once.
- basilisp's `str` of two or more arguments made a closure per call (77 µs for `(str "a" "b")`); eve's completion
  patches it with fixed arities (6.1 µs, the same strings). `eve.net.bytes/hex` is a table lookup a byte on every
  host. On basilisp a 32-byte `hex` 4.1 ms → 1.0 ms, and `eve.thread-net-test`'s gateway test 1,234 s → 229 s (239 s in
  the final pass, the namespace alone).
- clojerl loads eve's hinted namespaces: a type hint's symbol must resolve on clojerl (eve.host defines the array
  casts for it), and `eve.keyring`, hinted for the JVM, now refers them.
- jolt's disk domains at a relative path failed (`open …: errno 2`): jolt's launcher runs from jolt's own root, and
  `open(2)` resolved the path there while `java.io.File` resolved it against the user's directory; the region now
  opens the absolute path.
- **What a write leaves allocated** (`doc/memory-model.md` "What a write leaves
  allocated"). Transients reclaim (2026-10-21): each write frees what it superseded among the
  blocks the transient made — int-map gains a transient, and eve.sorted's, the map's, the set's and the vector's free;
  the int-map, sorted and red-black constructors build through one from no tree and keep their tree only. Every tree
  has an exact walk (int-map, the sorted treap, the red-black set), which its transient uses; a stored int-map or
  sorted collection replaced in an atom now retires whole — it retired its header only, every node stayed — since
  9.31 by `eve.retire`'s one walk over its registered layout. int-map's own `assoc` made two throwaway
  branches at every leaf split, the red-black insert and delete dropped nodes mid-operation, and a vector pop across a
  trie level left the root it cloned; none does now. Measured on the JVM, over 160 swaps: one int-map `assoc` a swap
  531–700 blocks in use, flat (+5 a swap before); a 200-key int-map rebuilt each swap, bounded (+~1,026 a swap before).
- **An eve list kept at an atom root broke after `conj`s**: its walk retired every old cell a `conj` keeps; it aligns
  the two chains by their counts now (9.29).
- **A user's eve-deftype at an atom root lost the value it replaced** (a cons cell's tail): its old root block was
  retired. Every eve-deftype registers its pointing fields as it is defined, and a replaced one retires by the
  positional walk over them, at the native walks' speed — one insert into a 5,000-node user tree 0.16 ms, against
  25.0 ms for a whole-value reach (9.30).
- `eve.core/transient` of an eve hash map threw on babashka (sci refuses `IEditableCollection` on a map type, and
  the type dropped it); it answers eve.core's protocol now (9.32).
- **A value held at two places was freed when one place let go** (9.31): `(assoc m :b (:a m))`, a value made from
  `:a` that keeps `:a`'s interior, or a piece of one atom stored in another of the domain, then a new `:a` — the walk
  was single-pair, and the other place read freed blocks once they were reused. The tally rule (2026-10-21):
  a piece held at more than one place carries a count, raised when a write stores it again, lowered when a write drops
  it, released at zero through the epoch's deferred release (`eve.tally`, `eve.retire`). One walk for every kind now
  (`eve.retire`), each block read by its layout; an ordinary swap moves no count; a write that raises one commits
  under the domain's exclusive hold. The reachability set two retire arms built over the new value is gone (10.2), and
  the walk follows eve value arrays (an RRB vector's nodes) and array pointers, which it treated as opaque.
- The perf suite's two build workloads run on their time budget (they stopped at 16 builds).

### Known issues

Recorded in: **a babashka writer deadlocks a shared disk domain once
a lock-free writer has died inside a write** (9.14 — let babashka read a shared domain and write through a gateway;
since 9.31 a lock-free host's write that raises a tally count takes the same hold and waits the same way, so the hang
now reaches any host's raising write);
an `atom-domain`'s deref carries its registry entry; an eve
collection built outside `swap!` cannot be stored into an atom (build it inside); `swap!` returns the slab-backed value;
`im/range` on a value read through `$`; the `api-defs` exports lack docstrings; the divergences of the reclamation code
from the memory model (`doc/memory-model.md`: D1, D3–D5, and D6 — the tally); **a persistent
chain a swap's own function builds and drops is not reclaimed** (the model's scope-local temporaries: build with a
transient or a constructor, which reclaim — 9.25); **a domain shared with a participant whose walk predates the tally
(eve before 9.31, `vs.eve-disk`) must not hold one piece at two places** (10.6).

## [0.1.0] - 2026-03-10

### Added
- Shared-memory persistent data structures for ClojureScript and Clojure
  - `atom`, `hash-map`, `hash-set`, `vector`, `list` backed by SharedArrayBuffer
  - Cross-process mmap-backed persistent atoms (JVM + Node.js)
  - Native C++ addon (`mmap_cas.cc`) for memory-mapped CAS operations
  - Epoch-based garbage collection for safe cross-worker/process memory reclamation
  - WASM-accelerated bitmap allocator with SIMD optimizations
  - Slab allocator with six size classes (32B–1024B) plus coalescing overflow
  - Lazy slab growth — files start small and grow on demand
- Specialized collections
  - Integer map (PATRICIA trie) with merge-with and range queries
  - Red-black tree sorted set
- `eve/obj` typed shared objects (AoS and SoA layouts)
- `eve/deftype` for custom SAB-backed types with atomic field operations
- Typed array support with full Atomics API
- JVM persistent atoms via Panama FFM (Java 21+)
- X-RAY memory diagnostics for slab allocator debugging
- Comprehensive documentation and benchmark results

[Unreleased]: https://github.com/SeniorCareMarket/eve/compare/v0.9.0...HEAD
[0.9.0]: https://github.com/SeniorCareMarket/eve/compare/v0.1.0...v0.9.0
[0.1.0]: https://github.com/SeniorCareMarket/eve/releases/tag/v0.1.0
