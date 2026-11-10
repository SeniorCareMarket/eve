# eve on jank

jank (`hosts.edn :jank`) is Clojure on LLVM with C++ interop, at 0.1-alpha (the `jank` 0.1-noble package of
2026-10-28 from `ppa.jank-lang.org`, with a bundled clang 23). eve runs on it with its core completed
(`adapters/completion/jank`: jank 0.1 has no `defprotocol`, `deftype`, `defrecord`, `reify` or `extend-type`, and 121 of
its core names are `TODO: port` stubs), eve's types as one C++ object type (`eve_host::eve_obj`: the type's tag and
its fields; measured, below), its collection vocabulary on eve's own protocols (babashka's `coll/protocols` as jank
reads it, `adapters/coll/jank`), and regions, encoding and process primitives in C++ through `cpp/raw` in their
adapters. Memory is a `calloc`'d block or a `MAP_SHARED` mapping with the `__atomic` builtins and the futex syscall;
one thread (`thread/none`) until the thread seam (J3).

**0.9.3.1 (2026-11-09).** At this release's pin the compiled form is clean: the AOT build compiles
165 files with 0 failures, the one-datum DataScript contract passes 5/5 cold runs (0.317 s median,
journal engaged, fresh-process readback exact), and `eve.test.ffi-ns` passes 15/15. The take-2-era
notes further down this page (the AOT abort, the missing-descriptor set) describe the tree they
measured and do not hold at this pin; the remaining known gap is the warm-up tax on `-main` dispatch.
The journal-file adapter is in tree; its Mac proof is outstanding (venue-blocked).

```bash
apt-get install -y jank                                   # jank's own apt repository (book.jank-lang.org, Installation)
locale-gen en_US.UTF-8                                    # once: jank's lexer reads source through the locale
bb test:jank                                              # the shared suites (LC_ALL=en_US.UTF-8 set by the task)
LC_ALL=en_US.UTF-8 jank --module-path "$(bb classpath jank):adapters/completion/jank/test:adapters/slab-io/test:test" \
  run-main eve.runner.jank-main -- test eve.hamt-util-test
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:jank eve.hamt-util-test` (the J0 probe: the 28 mask rows, popcount, get-index, node-bit, 53312's slot) | **5 tests, 51 assertions, 0 failures, 0 errors** (8.9 s with the completion) | 2026-11-04 |
| `bb test:jank eve.host-completion-test eve.slab-io-test` (the DJ-2 pins: two deftypes told apart; an instance never = a map or record; and the slab-io conformance — over the protocol adapter at DJ-2; the `:jank` row's slab-io root is `slab-io/jank` since the flagship gate, conformance identical) | **8 tests, 97 assertions, 0 failures, 0 errors** (the pins 4 / 46, slab-io 4 / 51) | 2026-11-05 |
| type-id contract (upstream defects 21 and 27 answered in the completion): `bb test:jank <ns>`, one process each | `eve.xxh3-kat-test` **4 / 83, 0 failures** (4 pass / 79 fail before); `eve.deftype.type-id-steps-test` **2 / 19, 0 failures** (7 pass / 12 fail before: the digest the first step to leave); `eve.deftype.type-id-test` **2 / 7, 0 failures, 0 errors** (2 fail / 3 error before: the errors were the deftype-in-an-atom family, closed by the completion's eve_obj behaviors and re-root table — int-map 24 / 378, rb-tree 28 / 293 in the same pass); the step-1 pair unchanged 8 / 212; logs in atom.broker's vendored tree, `eve/doc/release-0.9/logs-2026-11-05-vsa3/` | 2026-11-05 |
| adopting-eve-minimal §5 step 1: `bb test:jank eve.eve-bytes-fixture-test eve.hamt-util-test` | **8 tests, 212 assertions, 0 failures, 0 errors** — the fixtures 3 / 161 with all 72 rows, 0 skipped (`:jank` joins every row's `:hosts`); babashka's count for the pair is the same 8 / 212 | 2026-11-05 |
| §5 step 2: `bb test:jank eve.slab-io-test` | **4 tests, 51 assertions, 0 failures, 0 errors** — re-verified over the native root (`slab-io/jank`) at the flagship gate, identical counts | 2026-11-05 |
| §5 step 3: `bb test:jank eve.thread-host-test` (thread/none: a single-threaded host's contract) | **13 tests, 30 assertions, 0 failures, 0 errors** | 2026-11-05 |
| §5 step 4: `bb test:jank` (the runner's list, 28 namespaces) | **completed per-namespace at the flagship gate**: the single-process run aborts in `eve.reference-tag-test` on both the protocol and native rows (jank runner enumeration — a raw `char const*` through `sequence_length`; both reference-tag vars pass singly), so the list was run as chunks + singles on both arms with every delta adjudicated — no FAIL/ERROR attributable to the native roots; full record in `docs/port-gap-closure/coordination/reports/eve-jank-J3-flagship-REPORT.md` | 2026-11-05 |
| §5 step 5: `examples/in_memory.cljc` by `run-main eve.runner.jank-main -- run in-memory` | in work (the launcher written) | 2026-11-05 |

## Performance

The int32 seam (J3, 2026-11-04): one bench loop of 1,200,000 `eve.int32` calls (`imul`, `i32`, `rotl`,
`popcount` over a running value; the same bench run on both roots, this box, back to back) — portable 970 ms
(808 ns/call), `int32/jank` 276 ms (230 ns/call): 3.5×, with identical checksums. The whole-suite rows are not
banked yet: `scripts/perf-all.clj` has no `:jank` host entry and the jank runner has no perf mode, so
`bb scripts/perf-all.clj jank` collects nothing today.

The crypto seam (J3, 2026-11-05): a chained SHA-256 bench (5,000 digests, digest → digest from a fixed
64-byte seed; three rounds, arms interleaved, this box) — portable 33,399 / 26,639 / 49,598 ms,
`crypto/jank` 186 / 196 / 594 ms (round 3 ran under load; the clean rounds give 136–180×). The native
digest equals an independent python chain of the same shape; the portable root's differs (it miscomputes
SHA-256 of multi-block inputs on this host — the defect-21 family, below). A third arm, OpenSSL's SHA256
(SHA-NI-dispatched) behind the same byte crossing, measured 198 / 367 / 591 ms — no win at this shape:
the per-call cost is the byte-vector crossing, not the compression. Hardware SHA does not ship.

### The cost of a type

Measured 2026-11-04 ET on jank 0.1-alpha, this box (`bash time`, one process each):

| What | Load |
|---|---:|
| a `cpp/raw` struct deriving `jank::runtime::object`, per type: 1 / 10 / 30 structs in one file | 0.659 s / 0.869 s / 1.116 s — **~23 ms a struct** past the first |
| the completion alone (`eve.host-completion`: its one struct, the protocol and type macros, the re-roots) | 4.186 s |
| 30 `deftype`s on the completion's one struct, with the completion | 6.043 s — **~60 ms a deftype** (the macro expansion and `extend`, not C++) |

jank's own `(type x)` answers `opaque_box` for every user struct (30 distinct structs, each `opaque_box`), so a struct
per deftype gives jank no runtime type of its own: the type that tells instances apart is the completion's either way.
DJ-2 (2026-11-04) keeps the one shared, tagged object.

## How eve is built on jank

| Feature | Adapter | How |
|---|---|---|
| completion | `completion/jank` | `defprotocol` as a table per method keyed by a value's type key (a deftype's tag, a record's, `::nil`, else jank's type name), each method a fn of fixed arities caching its impl; `deftype` as one C++ object type `eve_host::eve_obj {tag, fields}` (a mutable field a `volatile!` cell made by `->T`; `T.` a var holding the factory, since jank keeps `T.` a plain symbol); `defrecord` a map carrying `:eve/record` in its meta; `reify` an instance holding its closures; `type`/`class`/`instance?` over the tag; `=` and `hash` reaching a type's own through `set-type-equality!` on both of jank's paths (its inlined `=` and `hash` call the object's virtual `equal`/`to_hash`, which call jank hooks); `deref`/`swap!`/`reset!`/`compare-and-set!`/`swap-vals!`/`reset-vals!`/`realized?` onto completion protocols; the `unchecked-*` family, `byte`, `short`, `class`, `instance?`, `record?`, `locking` (one thread), `hash-ordered-coll`/`hash-unordered-coll`/`mix-collection-hash` (Murmur3), `subseq`/`rsubseq`/`sequence`/`rseq`/`into-array`/`requiring-resolve`; `meta` of a type nil; type hints as `object_ref` aliases (a hint is a C++ type to jank); `clojure.edn` (jank ships none) |
| region | `region/jank` | `JankRegion` over C++: `calloc`, or a file `MAP_SHARED` grown never truncated; `__atomic` builtins (seq-cst), unaligned i32 by `memcpy`; the futex syscall (shared); bulk bytes jank's `u8` arrays; `JankLockedRegion` for a Lustre mount by fcntl locks; the descriptor kept open |
| encoding | `encoding/jank` | UTF-8 and IEEE-754 by `memcpy` in C++ over jank's typed arrays (`obj::array<T>`: a byte reads back 0..255, as on let-go); jank's inst (built from ms through the text its reader takes) and uuid; its big integer, ratio and big decimal; a map entry a vector of two; `string-writer`, an atom a printer writes to |
| process | `process/jank` | the clock and `gettid`; a timer a `std::thread` registered with the collector; `O_CREAT|O_EXCL`; `/dev/urandom`; the exit hooks run from `eve.process/exit!` (below) |
| types | `types/jank` | babashka's translation of every interface onto eve.core's protocols (a map type's too), the atom interfaces onto the completion's; a printer through `set-printer!` |
| coll | `coll/jank` | `coll/protocols` as jank reads it (`.jank`; jank's catch arms; no `subvec` in jank 0.1) |
| int32 | `int32/jank` | the five ops as C++ (cpp/raw, called as `cpp/eve_i32.<fn>`): the `int32_t` cast, a `uint32_t` multiply and rotate, a `uint32_t` logical shift, `__builtin_popcount` |
| crypto | `crypto/jank` | SHA-256/512/384, HMAC-SHA256, AES-256-CTR, XXH3-128 as C++ (cpp/raw, called as `cpp/eve_crypto.<fn>`); AES-256-GCM as eve.net.gcm's composition over the native CTR; `modpow`/`modmul`/`modinv` and the ed25519/x25519 group over cpp_int bignum ops; `random-bytes` the process adapter's |
| slab-io | `slab-io/jank` | the fused primitives in C++ over the region base (cpp/raw, `cpp/eve_slab.<fn>`): bitmap find-free a ctz scan, bitmap alloc a CAS loop, free, bytes-equal a memcmp in place. A wrapped sio (`IWrappedSio` — the keyring's encrypting wrapper above all) is always read through ISlabIO: `bytes-equal-at?` branches on the wrapper and never takes its mem-region |
| typed-view | `typed-view/jank` | the bulk ops on :int32 (fill/copy/sum/min/max/eq) as hand-written C++ loops over the array's region bytes; ~2,400–4,900× eve's element loops at n=8000. Highway through cpp/raw measured 10–27× slower (it lands on its portable target without a per-target recompile) and does not ship — DECISIONS seam 6 |
| http | `http/jank` | HTTP/1.1 over C sockets in C++ (cpp/raw, called as `cpp/eve_http.<fn>`): fetch writes the request's bytes and reads the whole answer; the head's parsing, the chunked joining and the body conventions in Clojure; its last-error string a mutex-guarded static, never a `thread_local` (defect 28) |
| http-server | `http-server/jank` | the same C-socket shape on the server side (cpp/raw, `cpp/eve_httpd.<fn>`): a listener thread plus a fixed worker pool, the handler rooted and the threads GC-registered on process/jank's pattern; the last-error string mutex-guarded (defect 28) |
| journal-file | `journal-file/jank` | the commit journal over a POSIX fd in C++ (cpp/raw, `cpp/eve_journal.<fn>`): `O_RDWR\|O_CREAT` (no `O_APPEND`, under which Linux `pwrite` ignores its offset), an append `lseek` to the end then `write`, write-at `pwrite`; the lock an fcntl POSIX write lock over the whole file (`F_SETLKW`, excluding and excluded by the JVM FileChannel lock and the python adapter's `lockf`); force `fsync` (full) or `fdatasync`; the EVJ1 record, its big-endian i32s and its CRC-32 built and read in C++ over eve's `i8` arrays, the scan's loop in Clojure |
| core, slab, codec, thread | the inbuilt roots | `core/clojure`, `slab/heap`, `codec/byte-array`, `thread/none` (its catch arm `:jank jank.runtime.object_ref`) |

## Gotchas

- **An eve realm is assembled with bit ops, not arithmetic** (`eve.deftype-proto.serialize`'s
  `fresh-ref-table`): a multiply-accumulate that crosses 2^31 wraps to a negative i32 on jank
  (defect 21's family — `long` casts do not help; measured over fixed bytes), while the codec's
  wire read of the same bits is unsigned (`read-u32-le`), so a wrapped realm made `handle->ref`
  refuse the process's own references in ~half of all processes. Bit assembly
  (`bit-or`/`bit-shift-left`) is the i64-clean path.
- **The locale.** jank's lexer decodes source through the process locale: under POSIX any non-ASCII character in a
  string is "Unterminated string", in a comment or symbol "The Unicode character literal is unfinished". `bb test:jank`
  sets `LC_ALL=en_US.UTF-8`; the box needs the locale generated (`locale-gen en_US.UTF-8`; `C.UTF-8` lexes but breaks
  `#inst`).
- **No exit hook on a normal end.** jank tears its runtime down before `std::atexit` handlers run, and a handler
  calling into it — or a timer thread alive at the end — segfaults the exit (exit 139, measured). So
  `eve.process/on-process-exit!` records the hook and `eve.process/exit!` runs the hooks, flushes and `_exit`s; the
  runner ends through it. A program that ends otherwise releases its disk domain's slot by heartbeat staleness, as on
  glojure.
- **A catch arm is a C++ type.** A thrown jank value is a `jank.runtime.object_ref`; a runtime error of jank's own core
  (an index out of bounds) a `std.exception`; `Exception` and `Throwable` do not exist; `:default` binds nil.
- **A type hint is a C++ type.** `^long i` makes `i` a native long (which works); `^bytes`, `^objects`, a JVM class or
  an eve type's name must be a C++ name: the completion aliases every one eve's sources use to `object_ref`. A new hint
  naming an eve type is a load error naming it: add the alias.
- **An eve type answers `eve.core`'s functions, not `clojure.core`'s** (a protocols host, as babashka): `count`, `get`,
  `seq` of jank's core do not see it (a `std.exception`, which `eve.core`'s Object arm turns into a walk of its seq).
- **A statically held `object_ref` in `cpp/raw` code is not a GC root** (JIT-compiled code's statics are not scanned):
  the completion's hooks and the process adapter's timer register theirs with `GC_add_roots`. A ref built from `this`
  goes through `jank::runtime::detail::untagged` (a raw `this` read as a `small_real`).

## Upstream jank defects

Met on jank 0.1-alpha (package 0.1-noble, 2026-10-28), each with its one-line repro; worked around in eve's adapters
where named. Filing them against jank-lang/jank is the operator's (no GitHub access to that repository from the lane).

1. No `defprotocol`, `deftype`, `defrecord`, `reify`, `extend-type`, `extend-protocol`, `satisfies?`, `extends?`:
   `(defprotocol P (m [x]))` → "analyze/unresolved-symbol: The symbol `defprotocol` could not be resolved". The
   completion supplies them.
2. The lexer decodes source through the process locale: `(println "em—dash")` → "lex/unterminated-string" under
   `LC_ALL=POSIX`; prints under `en_US.UTF-8`. The task sets the locale.
3. `(try (throw (ex-info "b" {})) (catch :default e e))` → `e` is nil. Not used.
4. `(meta <opaque_box>)` never returns (a 60 s timeout). The completion's `meta` answers nil for a type.
5. `(let [o (object-array 2)] (aset o 0 :k))` → C++ "call to 'aset' is ambiguous" (the inline `aset` on a typed object
   array). The completion drops `object-array`'s `:inline`.
6. `#inst "2024-01-02T03:04:05.678Z"` → "Unrecognized date/time syntax" (the `-00:00` form reads). `clojure.edn`
   rewrites `Z`; the encoding adapter builds instants in the `-00:00` form.
7. 121 core names are `(throw (ex-info "TODO: port …"))` stubs, among them `unchecked-byte` and the fixed-width family,
   `class`, `instance?`, `locking`, `subseq`, `rsubseq`, `sequence`, `hash-ordered-coll`, `mix-collection-hash`,
   `flush`, `special-symbol?`: `(unchecked-byte 255)` → "TODO: port unchecked-byte". The completion re-roots the ones
   eve uses.
8. A static `object_ref` in `cpp/raw` code is not a GC root: a closure held in one was collected under the object that
   called it (a crash at random). `GC_add_roots`.
9. `(defn f [^bytes b] b)` → "Unable to find `bytes` within the global namespace": a hint is a C++ type. The
   completion's aliases.
10. A jank fn called from a `std::atexit` handler, or a `std::thread` alive at a normal end, segfaults the exit.
    `eve.process/exit!`.
11. `jank run-main ns args` → "Extra positional args": the arguments go after `--`. The task passes them so.
12. A defn whose name is munged (`+`, `->`, `!` in it) and that takes itself as a value inside a nested closure of its
    own body does not compile: `(defn d! [x] (if (coll? x) (mapv (fn [y] (map d! y)) x) x)) (d! [[1]])` →
    "unordered_map::at" (a plain name, a call instead of a value, or another var's value compile). Met in
    `codec/byte-array`'s `(partial value+sio->eve-bytes sio)` inside `#()`, now `(fn [x] (value+sio->eve-bytes sio x))`.
13. A fixed-arity fn takes at most 10 parameters ("This function has too many parameters. The max is 10"): the
    completion's factory of a type with more fields takes a rest argument, and its `defn`/`fn` re-roots turn any fn
    with a wider arity (eve.deftype-proto.sio's `->IntMapBranch`, 19 and 20) into one variadic arity dispatching on
    its argument count to each arity's body under a `let` of its parameters.
14. `(.-f x)` compiles only as C++ member access on x's static C++ type: `(defn f [x] (.-regions x))` → "There is
    no `regions` member within `jank::runtime::oref<jank::runtime::object>`"; a dynamic value has no field read, and
    a macro named `.-regions` is not consulted (member access is analyzed first). eve's shared code reads a field
    through `eve.host/field` (`(field x f)`: `(.-f x)` on every other host, the completion's `get-field` on jank —
    DJ-3, 2026-11-04).
15. A reader conditional with no `:default` arm reads as nothing: `(def host #?(:clj :clj :cljs :cljs))` leaves
    `host` unbound, and jank's `test-all-vars` then fails on the unbound var ("Objects of type `var_unbound_root` are
    not nameable"). The tests' conditionals carry a `:jank` arm where a value is read.
16. The number tower at run time: `(defn d [a b] (/ a b)) (d 1 2)` → `0` (integer division; the reader's `1/2` is a
    ratio), `(/ 1N 2N)` → "An object of type `ratio` was expected here, but a `small_integer` was provided", and
    `quot` of a big integer past 2^53 is one off (`(quot -9223372036854776064N 256)` → -36028797018963968, through a
    double). The encoding adapter builds ratios and a big integer's two's-complement bytes in C++ over jank's own
    `ratio_data` and `cpp_int`.
17. A syntax-quote with `~@` read in another namespace splices through the private `clojure.core/concat*`: "The var
    `clojure.core/concat*` is private and can only be accessed from namespace `clojure.core`" (the completion's own
    syntax-quotes load; `types/jank` builds its deftype forms with `list*`).
18. A native value in a `case`: `(defn f [^long h] (case h 1 :one h))` → C++ "no viable overloaded '=' … long to
    oref" (core.jank:4523, the `case*` expansion), for the dispatch expression and a branch alike; the hint is read
    as `:tag (quote long)`. The completion drops the native tags (`long double int boolean float short byte char`)
    from every `defn`/`fn`/`let`/`loop` binding before the core macro expands, so no eve value is a C++ native on
    jank.
19. A `case` whose default clause is C++-typed natively: `case*` assigns each branch's value to its `object_ref`
    result boxed, but not the default's — `(defn g [x] (case x :a 1 :b 2 3))` → C++ "no viable overloaded '=' … int
    to oref" (core.jank:4531 for keywords, 4527 for strings and symbols, 4523 for integers). Amended 2026-11-06 (VS
    Agent 3, 25 shapes, `probes/jank-case/out1..4.txt`): it is the default, whatever the constants — a literal
    true/false, integer, double or string (`const char*`), a `let` ending in one, an `if` of literals, `(boolean x)`;
    `(case x 1 true false)` fails, `(case x 1 false 2 true :d)` and `(case x 1 :a (identity false))` compile, and so
    do keyword, string and symbol cases once their default is boxed. eve.set's `hamt-find` ends `(case (int nt) 1 …
    2 … false)`: two Eve sets holding a by-reference member (their digests abstain, so the first walk compiles)
    stopped the run. The completion re-roots `case` to pass the default through `identity` (left as it is when it
    holds a `recur`; an `if` over a `recur` and a literal compiles); every case is jank's own dispatch. (Before the
    amendment the completion expanded a keyword, string or symbol case with a default to a `cond`, which worked
    around the same defect.)
20. `defmacro` expands to `(reset-meta! (var name) (assoc (meta (var name)) :macro true))` with `meta` and `assoc`
    unqualified: a namespace that excludes `meta` from clojure.core (`eve.alpha`, which refers `eve.core`'s) cannot
    define a macro — "The symbol `meta` could not be resolved" at its `defmacro`. The completion re-roots `defmacro`
    to qualify them.
21. An integer loop local wraps at 32 bits: `(loop [p 1 i 0] (if (< i 32) (recur (* p 2) (inc i)) p))` answers 0,
    not 4294967296 — a literal-typed local keeps C++ `int` width through `recur` (2^31 reads back −2147483648).
    It silently corrupts `int32/portable`'s `pow2` table on this host (row 31 negative, row 32 zero, so `ushr`
    by 32 throws division-by-zero) while every suite row stays green — no suite row shifts by 31 or more through
    it. Answered on the eve side by `int32/jank` (J3); the narrowing itself is jank's. It also corrupted
    eve.hamt-util's own `pow2` table (the portable XXH3-128's shifts), so the completion's `loop` re-root now boxes
    an integer-literal init (`(clojure.core/identity 1)`): the local is an object and holds what `recur` gives it
    (measured 4294967296; `eve/probes/jank-xxh3/probe4.cljc`) — for every loop eve compiles on jank.

22. A syntax-quote qualifies `set!` with the namespace: `` `(set! ~x 1) `` in eve.core reads as `(eve.core/set! x 1)`
    ("The symbol `eve.core/set!` could not be resolved", eve.core's `caching-hash`); the other special forms read
    unqualified. coll/jank writes `~'set!`.
23. A macro's `&env` is a symbol, not a map of the locals (jank passes `(form env & args)` with env a symbol: measured
    at J0; `(keys &env)` → "Objects of type 'symbol' do not support the 'seqable' behavior", eve.thread's `tfn`).
    eve.thread's analysis reads an env that is no map as holding no locals (as a ClojureScript compiler's), so a
    travelling form carries none on jank and a form that closes over a local is refused before it travels.
24. An inner `loop` whose local shadows the enclosing loop's — `(loop [i 0 j 10] (let [i (loop [i i] (if … (recur (inc i)) i))] …))`
    — fails C++ codegen ("use of undeclared identifier 's15'; did you mean 's5'?"); the same with the inner local
    renamed compiles (measured). eve.dataset.argops's quicksort scans name theirs `a` and `b`.
25. `sort` and `sort-by` of an empty collection answer a `native_vector_sequence` whose `first` dereferences null:
    `(first (sort []))`, `(str/join " " (sort []))` end the process (exit 139; `seq` and `count` of it are fine) — the
    segfault that ended the full run (eve.thread.task's `canonical` joins a sorted empty map or set; eve.thread.meta's
    `claim-component` takes the first of a sorted empty candidate list). The completion re-roots both to answer `()`
    for an empty collection.
26. A var defined twice in one top-level form — `(do (def ->T (fn [a b c d] …)) … (defn ->T ([a b] …) ([s a b] …)))`,
    an eve-deftype's expansion: the completion's factory, then the constructor — holds the constructor until its
    first call compiles the deferred fn, after which the var holds the FIRST definition (the factory: measured, the
    same shape in plain jank; `(->Dataset sio n cols m)` → "Invalid call … with 4 args" from the second call on).
    The completion's deftype binds `->T` and `T.` by `intern`, so one `def` of each remains in the form.
27. Adding, subtracting or multiplying two integers of the int32 range wraps at 32 bits: jank boxes such an integer
    as a `small_integer` (a wider one as `integer`), and `cpp/jank.runtime.add`/`sub`/`mul` — what `+ - *` inline to
    — keep two small integers' result in 32 bits: `(+ 2147483647 1)` → −2147483648, `(* 239 16777216)` →
    −285212672, `(* 65535 65535)` → −131071, through a fn's parameters and a `(long …)` cast the same; an `integer`
    operand, `inc`, `dec`, `quot`, `mod` and the shifts are exact, and so are jank's promoting ops
    (`promoting_add`/`sub`/`mul`, what `+'` `-'` `*'` call). Measured on jank 0.1-noble (cf627abb…),
    `eve/probes/jank-xxh3/probe2.cljc` and `probe3.cljc`. It made eve's portable XXH3-128 wrong at every length
    (`eve.xxh3-kat-test` 4 pass / 79 fail), so every type-id and name hash on jank differed from every other host's
    (`eve.deftype.type-id-steps-test`: the digest the first step to leave). The completion points `+ - *`, inline
    and var, at the promoting ops as its first forms. (`unchecked-multiply` is a "TODO: port" stub in jank's core.)
28. A `thread_local` in `cpp/raw` code segfaults when the JIT-compiled code touches it: a raw block holding
    `static thread_local std::string e;` and a fn returning its size ends the process (exit 139) on the fn's
    first call; the same fn over a plain static answers. `http/jank`'s and `http-server/jank`'s last-error
    strings were `thread_local` — every error path (fetch's failures, `last_error`, serve's failures) faulted,
    which is what kept seam 8 red. Both adapters keep the string in a mutex-guarded static instead (seam 8).