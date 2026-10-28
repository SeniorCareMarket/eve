# eve on basilisp

basilisp (`hosts.edn :lpy`) is a Clojure dialect compiled to Python bytecode, running on CPython. Its core differs
from Clojure's in many small ways, so eve's basilisp completion (`adapters/completion/basilisp`) patches basilisp's core
to Clojure's semantics before eve loads — the largest completion of any host. Memory is Python's `mmap` and
`bytearray`, with libatomic's atomics and futex(2) called through `ctypes`. Threads are Python threads: real, but
serialized by the GIL.

```bash
python3 -m venv /opt/pyhosts && /opt/pyhosts/bin/pip install basilisp==0.5.1   # basilisp on PATH
bb test:lpy                    # the battery, compiled afresh into target/lpycache-test each run
basilisp run -p ... -n eve.runner.lpy-main perf      # the performance suite (scripts/perf-all.clj spells the -p roots)
```

## Correctness

| Run | Result | When (ET) |
|---|---|---|
| `bb test:lpy` | **180 tests, 4,350 assertions, 0 failures, 7 errors** on 9.34's tree (56 min alone, peak 1.2 GB, the process tree) — every one an eve.thread.domain 120 s wait run out late in the run: 9.26's (the network rung's domain pool), the gateway, doorbells, the drivers, and fold on a group's three, whose fixture leaks as the network tests did before 9.34; 9.34's fix itself held — the failed `attach!`'s ticker stopped, the network tests' handles detached (`f3-lpy-threads.log`). Each namespace alone: green (below) | 2026-10-22, 9.34's tree (REPORT §0) |
| `bb test:lpy`, the tree before 9.34 | **180 tests, 4,360 assertions, 0 failures, 5 errors** (57 min alone, peak 1.0 GB, the process tree) — 9.26's error (`eve.thread-net-test`'s domain pool over a network atom: "the ticker did not answer"), then four its failure left behind: the failed tests' tickers kept beating under the GIL, and the gateway test and fold on a group's three tests could not start their threads. Each namespace alone: green (below) | 2026-10-22, this tree (the final pass) |
| `bb test:lpy eve.thread-net-test` | **5 tests, 47 assertions, green** (20 min on 9.34's tree, `f3-lpy-net.log`; 21 min before it — the domain pool 501 s, the gateway 239 s) | 2026-10-22, 9.34's tree and the final pass (`f-lpy-net.log`) |
| `bb test:lpy eve.thread-fold-group-test` | **3 tests, 49 assertions, green** (17 min on 9.34's tree, `f3-lpy-fold-group.log`; 16 min before it) | 2026-10-22, 9.34's tree and the final pass (`f-lpy-fold-group.log`) |
| `bb test:lpy eve.domain-open-test eve.thread-test` | **16 tests, 159 assertions, green** (114 s) | 2026-10-22, this tree (the final pass, `f-lpy-open-thread.log`) |
| `bb scripts/thread-rendezvous.clj jvm lpy` | 18 of 18 checks (133 s) | 2026-10-22, this tree (the final pass) |
| `bb test:lpy` | **174 tests, 4,396 assertions, 1 failure, 1 error** (65 min beside the other batteries) — the error 9.26's (below), the failure `eve.thread-fold-test`'s chunk-size timing bar under that load ("672 < 672"; the namespace alone after them 7 / 332, green) | 2026-10-21, `08b94cbf4`, before 9.31 |
| `bb test:lpy` | **166 tests, 2,825 assertions, 0 failures, 1 error** (52 min alone, peak 852 MB) — the error is `eve.thread-net-test`'s domain pool over a network atom: the ticker's first beat past `attach!`'s 120 s late in the full run | 2026-10-21, before 9.25, basilisp 0.5.1 on CPython 3.13 |
| `bb test:lpy eve.thread-net-test` | **5 tests, 47 assertions, green** (17 min; the domain pool 604 s, the gateway 229 s — 1,234 s before the `str` / `hex` fixes) | 2026-10-21, before 9.25 |
| `bb test:lpy eve.thread-domain-test eve.thread-net-test` | **12 tests, 145 assertions, green** (32 min; the domain pool 795 s) | 2026-10-21, before 9.25 |
| `bb test:lpy eve.domain-open-test eve.thread-test` | 16 tests, 159 assertions, green — over the inline and periodic-cancel fixes (the final sweep's two failures) | 2026-10-21, before 9.25 |
| `bb test:lpy`, in the final sweep beside the other batteries | 2 failures, 5 errors: the two above (fixed) and the network rung's and a group's bring-up past their bounds under load | 2026-10-20 |
| `bb test:lpy`, before this tree's two completions | 149 tests, 1,959 assertions, 0 failures, **2 errors** — `eve.reference-tag-test` and `eve.thread-net-test` failed at load | 2026-10-20 |
| `bb test:lpy eve.thread-net-test` | 5 tests, 47 assertions, green (the network-atom tests 633 s and 282 s) | 2026-10-20 |
| `bb scripts/thread-rendezvous.clj jvm lpy` | 18 of 18 checks | 2026-10-19 |

The two load errors were basilisp gaps, now completed: basilisp's `long-array` (and `int-`, `short-`, `float-`,
`double-array`) are its `object-array` — n nils, not n zeros — so `eve.net.bn254`'s Montgomery multiply added nil to a
product and every namespace loading `eve.net.poseidon2` failed; and basilisp has no `sorted-map`, which `eve.net.atom`
keeps a network atom's snapshot entries in.

The runner (`test/eve/runner/lpy_main.lpy`) runs the fixtures (**67 of 71 rows, 4 skipped** — basilisp has no char
type, among them), the HAMT helpers, type ids, the red-black set, int-map, sorted collections, big values, value
buffers, the key ring, the numeric and reference tags, collection hashing, and the whole thread feature including the
network rung. Not run by default: core.rrb-vector's suite — it is slow on basilisp, not timed whole (a 40,000-element vector
takes 46 s to build and an assoc 10.9 ms in the final pass's probe; 100 s and 55 ms on
2026-10-16; `test-assoc` alone does 40,000 assocs), and runs when named; the four
typed-array suites (no typed-array adapter). The vector retire walk and domain open joined the default run in this
round (left out without a reason before; domain open's first create is what exposed basilisp's double-evaluating
`true?`). Every other namespace babashka's runner runs, basilisp's runs.

## Performance

<!-- perf:lpy (from doc/hosts/perf-results.edn, written by bb scripts/perf-all.clj; formatted by hand) -->
| Workload | per operation | operations in the timed round | × the JVM's |
|---|---:|---:|---:|
| `atom-swap-counter` | 8.9 ms | 128 | 757 |
| `atom-deref-small` | 1.3 ms | 1024 | 77 |
| `atom-swap-map-1k` | 27.0 ms | 64 | 5810 |
| `atom-deref-map-1k` | 636.0 ms | 2 | 1684 |
| `atom-$-get-map-1k` | 2.1 ms | 512 | 1040 |
| `atom-reset-vec-1k` | 1630.0 ms | 1 | 1061 |
| `sorted-set-build-200` | 9197.0 ms | 1 | 827 |
| `int-map-build-200` | 2539.0 ms | 1 | 567 |
| `disk-swap-counter` | 9.6 ms | 128 | 545 |
| `thread-submit-64` | 280.5 ms | 8 | 285 |

Measured 2026-10-22 1:44–1:46 AM ET on the final tree by `bb scripts/perf-all.clj lpy`, alone (the all-host run's
column, 10:56–11:06 PM, overlapped a short nbb process); a round of at least 1 s.
<!-- /perf:lpy -->

Reading it: 550–1,700× the JVM's on most workloads (a counter swap 8.9 ms, a 1,000-key map's deref 636 ms) and
~5,800× on a swap into a 1,000-key map (27.0 ms), the slowest host on every workload; 64 tasks in 281 ms under the
GIL. What a profile found and this round fixed is in the bullets below; what
is left is basilisp's per-call cost (a variadic call 3.4 µs, a closure made per call 9.8 µs, against 0.54 µs for an
empty call: the final pass's probe).

basilisp is eve's slowest host — 2.4–8.8× the next slowest on every workload but the thread one (18×) — for reasons
that are basilisp's:

- **Every call is a Python call**: with eve loaded, an iteration of basilisp's own `dotimes` costs 1.8 µs and an empty
  fn call 0.54 µs on this box (the final pass's probe; the page said ~35 µs an iteration
  before it).
- **Variadic core functions** take their second argument through a rest seq: before the completion fixed their
  two-argument arity, `==` and `not=` cost ~370 µs and `=` ~110 µs a call under cProfile (2026-10-17).
- **Var lookups at run time.** A profile of eve.net's codec on basilisp (py-spy, 2026-10-20) sits in
  `Var.find` / `find_safe` under each call to `eve.net.bytes/utf8`: a call to another namespace's function resolves its
  var by name every time.
- **Making a function, and a variadic call, cost several µs.** A basilisp fn's creation runs its decorator
  (`functools.wraps`, a `partial`, an arity set), and a call through a rest arity goes through basilisp's apply path:
  with eve's completion loaded, an empty fn call is 0.54 µs, a variadic one 3.4 µs and a closure made per call 9.8 µs
  (the final pass's probe), but basilisp's own `(str "a" "b")` — whose rest arity made a closure per call — took
  77 µs. A py-spy profile of the network rung's beat (2026-10-21) had
  `eve.net.bytes/hex` at a quarter of a network swap, `str` per byte under it. The completion now patches `str` with
  fixed arities (6.1 µs in the final pass's probe; 5.0 µs when fixed) and `hex` is a table lookup a byte: a 32-byte
  `hex` 4.1 ms → 1.0 ms (the probe), `bb test:lpy
  eve.thread-net-test`'s gateway test 1,234 s → 229 s (239 s in the final pass). Where eve's shared code makes a closure per element or calls
  a variadic function in a loop, basilisp pays this.
- **The GIL**: four threads ran four CPU loads in 1.17× the serial time (the final pass's probe; 1.11× before it). eve.thread's pools run, but in parallel only on
  a free-threaded CPython (`thread/python` declares `:parallel` from `sys._is_gil_enabled`).

## How eve is built on basilisp

| Feature | Adapter | How |
|---|---|---|
| completion | `completion/basilisp` | basilisp's core made Clojure's (below) |
| region | `region/ctypes` | `LpyRegion` over a `MAP_SHARED` `mmap` or a `bytearray`; `__atomic_*` from `libatomic.so.1`, seq-cst, by ctypes; futex through libc's `syscall` |
| coll | `coll/basilisp` | basilisp's core, as made Clojure's; an ordered collection hashes as basilisp's own does |
| types | `types/basilisp` | the JVM's interfaces onto basilisp's (Python ABCs, `^:abstract`): `count` → `__len__`, `valAt` → `val_at`, `equiv` → `__eq__`; arities dispatched by count |
| encoding | `encoding/python` | `struct`, `fractions`, `decimal`, `uuid`, `datetime` |
| int32 | `int32/portable` | quot / mod (Python's integers do not overflow) |
| process | `process/python` | `time.time`, `threading.get_ident`, a daemon heartbeat thread, `atexit`, `O_CREAT|O_EXCL`, `os.urandom` |
| thread | `thread/python` | a `threading.Thread` per thread, a `queue.Queue` mailbox |
| crypto | `crypto/python` | `hashlib`, `hmac.digest`; AES-256-CTR and Ed25519 through the system libcrypto's EVP by ctypes (the portable code where none loads); XXH3 portable |
| http | `http/python` | `urllib` (an HTTPError is an answer); `request-all` on a 32-worker `ThreadPoolExecutor` |
| core, slab, slab-io, codec | `core/clojure`, `slab/heap`, `slab-io/protocol`, `codec/byte-array` | as the JVM's |

What the completion patches (each item measured on basilisp 0.5.1): fixed-width integer ops (`unchecked-*` returned
their argument; `unchecked-byte` threw past 127); two-argument `=`, `==`, `not=`, `<`, `>`, `<=`, `>=`, `bit-and/or/xor`,
`+`, `-`, `*` without a rest seq; a signed `byte-array` (`array('b')`); zero-filled numeric arrays; Clojure's Murmur3
collection hashes; `list*`, `reductions`; `interleave` (basilisp's exceeds Python's recursion limit at 1,000 elements already, the final pass's probe
`f-probe-lpy-interleave.log`; first met at 3,000);
`iterate` (basilisp's called f one element ahead); chunked-seq functions; map equality with eve maps; `sorted-set`,
`sorted-map`, `sorted-map-by`; `locking`; `case` with `recur` in a branch; `if-not` / `if-let` / `if-some` without an
else; `ns` keeping every clause; `swap!` / `reset!` through eve's atom; the reader's `\u` escapes; `print-method`; a
`clojure.core` namespace and a `clojure.lang` module of the JVM's names; protocol dispatch for `-foo` methods;
`transduce`, `halt-when`, `sequence` and `into` with buffering transducers.

## Gotchas

- **basilisp's inlined functions may evaluate an argument twice.** basilisp compiles a call to a `^:inline` function
  by putting the argument's form into the body, so one that names its parameter twice evaluates the argument twice:
  `(true? (f))` called `f` twice (measured 2026-10-21; `true?`, `false?`, `seqable?`, `supers`, `peek`, `pop` in
  basilisp 0.5.1). eve's completion removes those six's `:inline`; code compiled before the completion (basilisp's
  own) keeps it. Bind a side-effecting expression in a `let` before testing it.
- **Classpath order matters**: basilisp searches the last `-p` first, and `src` must come before `test` (otherwise
  `test/eve/deftype/` makes `eve.deftype` an empty namespace package). `bb test:lpy` gives the roots in reverse.
- **basilisp's namespace cache checks a namespace's own source, not the macros it expanded**: after changing a macro,
  compile afresh (`bb test:lpy` deletes its cache each run).
- **No char type**, `True == 1`, a namespace that is also a directory is a Python package; `basilisp.edn` misreads
  `1e300`; `basilisp.test`'s `is` evaluates a predicate's argument twice.
- **basilisp's map hash follows `immutables.Map`'s formula**, not Clojure's: a basilisp map and an eve map with the same
  entries are equal but the hash row of the sorted-collection test is skipped.
- **No Lustre regions**; futex is x86-64's syscall 202.
- **Compile time**: the first load compiles eve — `require eve.alpha` 51 s in the final pass, from the cache 2.2 s
  (~55 s and 1.8 s before it).

## Future directions

Optimization:

- **Direct-link eve's hot calls.** basilisp direct-links a var reference when it can (`--use-var-indirection` is off by
  default) and falls back to a run-time `Var.find` when it cannot — the profile's `find_safe` calls. A run with
  `--warn-on-var-indirection` names each reference it could not link; making those linkable (or `^:inline`, which
  basilisp's compiler honours) removes the per-call lookup.
- The codec and slab I/O as a small C extension or `ctypes` struct calls batched per block, not per word.
- A free-threaded CPython (3.13t) for parallel pools — eve declares `:parallel` from the GIL's state already.

Coverage:

- core.rrb-vector's suite by default once its build is minutes, not hours.
- A typed-array adapter (`array`, `memoryview`, numpy where present).
- A PyPI package (after 0.9).
