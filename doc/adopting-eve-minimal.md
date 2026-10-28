# Adopting eve: the minimal set

How to give a new Clojure-like language a working eve by writing only what no portable code can supply — four
namespaces — and taking eve's nine portable adapter roots as they are. When you are done, every shared test suite but the
network one runs on your language, and eve's in-process atoms, disk domains shared with other hosts, collections, codec,
key ring and executor service (single-threaded) work there.

The worked example throughout is **babashka on the minimal set** — `hosts.edn :bb-minimal`, babashka's four host roots
and the nine inbuilt ones, nothing else. It is a real, tested profile: every command below was run on this tree on
2026-10-20 (ET) and its output is shown. The tiers this guide is built on are measured in
[`release-0.9/ADOPTER-TIERS.md`](release-0.9/ADOPTER-TIERS.md); the contract each namespace implements is
[`unification/HOST-CONTRACT.md`](unification/HOST-CONTRACT.md); the per-feature table is
[`../adapters/README.md`](../adapters/README.md). The next step, replacing portable seams with native ones, is
[`adopting-eve-fast.md`](adopting-eve-fast.md).

## 1. What a host is

eve's shared code (`src/`) is written once and names no host: no reader conditional keyed by a host and no host method
calls in `eve.net` (`scripts/check-host-leak` holds both at 0; one host-only file, `src/eve/shared_atom.cljs`, remains). A **host** is the list of adapter roots placed on the classpath after
`src` — one row of [`hosts.edn`](../hosts.edn). An adapter implements one **feature** (one interface namespace) for one
host capability, under `adapters/<feature>/<implementation>/src/`. Adopting eve for your language is: for each feature,
pick an implementation your language can load or write one; list the roots in a row; run the shared suites.

The minimal profile, verbatim from `hosts.edn` (lines 74–78):

```clojure
 :bb-minimal ["adapters/region/jvm-nio-lock/src" "adapters/encoding/java/src" "adapters/process/java/src"
              "adapters/types/bb/src"
              "adapters/slab-io/protocol/src" "adapters/core/clojure/src" "adapters/completion/clojure/src"
              "adapters/coll/protocols/src" "adapters/slab/heap/src" "adapters/int32/portable/src"
              "adapters/codec/byte-array/src" "adapters/crypto/portable/src" "adapters/thread/none/src"]
```

The first four are babashka's own: the **minimal set**. The other nine are the **inbuilt set**: portable code every host
may take. `bb classpath <host>` prints a row's full classpath (`src`, then its roots):

```
$ bb classpath bb-minimal | tr ':' '\n' | sed 's|/home/user/atom.broker/eve/||'
src
adapters/region/jvm-nio-lock/src
adapters/encoding/java/src
adapters/process/java/src
adapters/types/bb/src
adapters/slab-io/protocol/src
adapters/core/clojure/src
adapters/completion/clojure/src
adapters/coll/protocols/src
adapters/slab/heap/src
adapters/int32/portable/src
adapters/codec/byte-array/src
adapters/crypto/portable/src
adapters/thread/none/src
```

## 2. The four namespaces you write

Each is the outside world (memory, the clock, files, entropy) or your language's own types; eve's portable code cannot
spell either. Babashka's are 972 lines together (`region` 290, `encoding` 361, `process` 76, `types` 245); basilisp's
are 982.

### 2.1 `eve.region` — shared memory and its atomics (HOST-CONTRACT §1–2)

Supply three functions and a region type:

| Name | Contract |
|---|---|
| `(make-heap-region size-bytes)` | in-process memory, **zero-initialised** (a fresh bitmap relies on it) |
| `(open-mmap-region path size-bytes)` | a `MAP_SHARED` mapping of the file: create it if missing, **grow, never truncate**, new bytes read as zero, the mapping valid after the handle closes, writes visible to every other mapping of the file in this process and in others |
| `(lustre-region? r)` | true only for a region whose atomics are fcntl byte-range locks |
| a type implementing `eve.mem/IMemRegion` (17 methods) and `eve.mem/IHostAtomics` (2) | below |

The traps, each a rule the shared code relies on:

- `-load-i32` / `-store-i32!` take **any alignment**: HAMT node interiors are packed at arbitrary offsets.
- An i32 is **signed** on every host; a host with wider integers sign-extends on load and wraps on store.
- `-cas-i32!` answers **the witness** (the value it found), not a boolean; success is `(== witness expected)`.
- Everything is **little-endian**.
- `-wait-i32!` may be a bounded poll and `-notify-i32!` a no-op answering 0.
- A host with no cross-process atomic read-modify-write implements the **lock-only** region: `-atomic-rmw?` false and
  `-with-file-lock` taking a byte-range lock; eve.atom then takes an exclusive hold around each swap.

babashka's region is that lock-only kind. Its type, `adapters/region/jvm-nio-lock/src/eve/region.clj:64-83`:

```clojure
(deftype BbMmapRegion [^MappedByteBuffer mbb ^long size
                       ^FileChannel chan
                       ^java.util.concurrent.locks.ReentrantLock in-process]
  mem/IMemRegion
  (-byte-length [_] size)

  (-load-i32 [_ byte-off]
    (.getInt mbb (int byte-off)))

  (-store-i32! [_ byte-off val]
    (.putInt mbb (int byte-off) (unchecked-int val))
    (fence!)
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (let [cur (.getInt mbb (int byte-off))]
      (when (== cur (int expected))
        (.putInt mbb (int byte-off) (unchecked-int desired))
        (fence!))
      cur))
```

and the half that tells eve it is lock-only (`:156-167`):

```clojure
  mem/IHostAtomics
  (-atomic-rmw? [_] false)
  (-with-file-lock [_ byte-off f]
    (when-not chan
      (throw (ex-info "BbMmapRegion: not opened :lockable? — no channel for the file lock"
                      {:byte-off byte-off})))
    (.lock in-process)
    (try
      (let [fl (.lock chan (long byte-off) 4 false)]
        ;; .release is not reachable from bb; close releases (measured).
        (try (f) (finally (.close fl))))
      (finally (.unlock in-process)))))
```

Grow-never-truncate is the opener's first step (`:174-179`):

```clojure
  ([path-str size-bytes] (open-mmap-region path-str size-bytes nil))
  ([path-str size-bytes opts]
   (let [size  (long size-bytes)
         _     (let [^RandomAccessFile raf (RandomAccessFile. ^String path-str "rw")]
                 (try (when (< (.length raf) size) (.setLength raf size))
                      (finally (.close raf))))
```

A host with hardware atomics on mapped memory implements the full region instead — `region/jvm-varhandle` (the JVM),
`region/ctypes` (basilisp: mmap + libatomic), `region/clr`, `region/jolt-ffi`, `region/beam` (a NIF),
`region/glojure` (Go packages) are the models.

### 2.2 `eve.encoding` — strings, floats, numbers, exceptions (HOST-CONTRACT §3–4)

53 names on a byte-array host (`adapters/encoding/java`): UTF-8 both ways (lengths are **byte** lengths), IEEE-754 bits
and little-endian float32/float64 bytes, your integers' classes (fixed, big), ratios and big decimals as parts, instants
as ms, UUIDs as 16 big-endian bytes, a string's **UTF-16 units** (a character's code is a host fact), exceptions,
map entries, small caches, array copies. Bytes are read through an **unsigned** accessor: a JVM `byte[]` is signed.

From `adapters/encoding/java/src/eve/encoding.clj` (`:44-52`, `:165-170`, `:223-226`):

```clojure
(defn utf8-bytes
  "The UTF-8 bytes of string s."
  ^bytes [s]
  (.getBytes ^String s "UTF-8"))

(defn utf8-string
  "The string len UTF-8 bytes of b encode, from off."
  [^bytes b off len]
  (String. b (int off) (int len) "UTF-8"))

(defn utf16-units
  "The UTF-16 code units of string s, as integers (what a JVM or
   JavaScript string holds; a clojerl string is UTF-8, so a code point
   above U+FFFF is two units there, a surrogate pair)."
  [s]
  (mapv int s))

(defn read-f64-le
  "The float64 at byte off of b (little-endian)."
  [^bytes b off]
  (.getDouble (le-buffer b) (int off)))
```

### 2.3 `eve.process` — the clock, sleep, timers, files, entropy (HOST-CONTRACT §5)

Eleven names: `now-ms`, `thread-id`, `sleep-ms!`, `start-timer!` / `stop-timer!`, `on-process-exit!`, `file-exists?`,
`create-exclusive!`, `ensure-parent-dir!`, `identity-hash`, `random-bytes`. Two carry correctness:
`create-exclusive!` must be **one atomic create** — true for exactly one of several processes opening a fresh domain at
once — and `random-bytes` must come from the **operating system's entropy** (it mints group keys). From
`adapters/process/java/src/eve/process.clj` (`:6-12`, `:47-52`, `:70-76`):

```clojure
(defn now-ms []
  (System/currentTimeMillis))

(defn thread-id
  "The identity a pinned epoch is kept under (threads share a worker slot)."
  []
  (.getId (Thread/currentThread)))

(defn create-exclusive!
  "Create the file at path, empty, if no file is there — one atomic step
   against every other process: true for the process that created it, false
   when it already existed (an mmap domain's creator is the one that gets true)."
  [path]
  (.createNewFile (java.io.File. ^String path)))

(defn random-bytes
  "n bytes from the operating system's entropy (java.security.SecureRandom), as a vector of 0..255 — the one crypto
   input no portable code can supply (eve.crypto/random-bytes)"
  [n]
  (let [a (byte-array n)]
    (.nextBytes ^java.security.SecureRandom @rng a)
    (mapv (fn [b] (bit-and b 255)) a)))
```

### 2.4 `eve.types` — eve's types as your host's (HOST-CONTRACT §6)

eve's types are written once in its type language (`eve.deftype-proto.vocab`: protocol implementations with the JVM's
or ClojureScript's names). Your `eve.types` realizes them as your language's own. Ten entry points, which every
`eve.types` has (`adapters/types/bb/src/eve/types.clj:7-9`): `host-type`, `plain-type`, `slab-type`, `eve-type`,
`extend-eve-type`, `existing-def`, `rename-fields`, `reader-of`, `print-form`, `vector-types`. A host whose types reach
eve memory through `ISlabIO` passes its steps as hooks to the shared `eve.deftype-proto.sio` and keeps only what
differs — babashka's (`:197-214`):

```clojure
(defn host-type
  "A plain type written with the JVM's names (host-deftype)."
  [_env type-name fields parsed]
  `(~'deftype ~type-name ~fields ~@(impls->forms (bb-translate-protos parsed type-name))))

(defn plain-type
  "A plain type (eve.deftype/deftype) on eve.core's protocols."
  [_env type-name fields parsed]
  (let [[fields parsed] (sio/plain-renames fields parsed nil)
        impls (bb-translate-protos parsed type-name)
        printed? (some #(= 'eve.core/IPrintWithWriter (:protocol %)) impls)]
    `(do (clojure.core/deftype ~type-name ~fields ~@(impls->forms impls))
         ~@(when printed? [(sio/print-method-form type-name 'eve.core/-pr-writer)])
         ~type-name)))

(defn slab-type "An eve/deftype type (eve.deftype-proto.macros/deftype)." [m] (sio/slab-type m (hooks)))
(defn eve-type "An eve-deftype (eve.deftype.macros/eve-deftype*)." [ctx] (sio/eve-type ctx (hooks)))
(defn extend-eve-type "eve-extend-type." [ctx] (sio/extend-eve-type ctx))
```

Three rules that hold on every host (HOST-CONTRACT §10): a type with a mutable field is built with its `->T`
factory, never `(T. …)`; a hint naming a host class belongs in an adapter; a function eve registers at load is a
`defn`, never a `(fn …)` in a top-level form.

### 2.5 `types` decides `coll`

`eve.core` is the collection vocabulary every eve value answers. Which implementation you take follows from your types
adapter, not from taste:

- your `deftype` **cannot** implement your core's own collection interfaces → take the inbuilt `coll/protocols` (eve's
  protocols with ClojureScript's names, falling through to `clojure.core` for anything else) and translate every
  interface onto them, as `types/bb` does (`bb-translate-protos`) — babashka, glojure and ValueScript do this;
- it **can** → write a `coll` that calls your core's functions, so eve values answer your `count`, `get`, `seq` directly
  (`coll/jvm` for the JVM, ClojureCLR and jolt; `coll/basilisp`, `coll/beam`) — that is a fast seam
  ([adopting-eve-fast.md](adopting-eve-fast.md#coll)).

On a protocols host an eve type answers `eve.core`'s functions, not `clojure.core`'s: write host-neutral code with
`eve.core` (`examples/in_memory.cljc` does: `(ec/assoc m k v)`, `(ec/seq m)`).

### 2.6 Only if your core is not Clojure's: `completion`, or `core`

`eve.host` (HOST-CONTRACT §10) is ~55 names — arrays, fixed-width integer ops, cells, `locking`, transients, array
casts, `const-bytes` `const-objects` `defdelay`, `swap!` `reset!` `compare-and-set!`, `class`, `token`,
`extend-default`. The inbuilt `core/clojure` expands each to `clojure.core`'s own call. If your core lacks some, write
an `eve.host-completion` that adds them before `eve.host` loads (`completion/jolt` is 66 lines: its `swap!` family taking
any `IAtom`; `completion/basilisp` 805; `completion/glojure` 553 plus three Go patches). If it cannot be completed —
no mutable memory, no arrays — write your own `eve.host`, as clojerl's `core/beam` does over a NIF.

## 3. The nine you take as they are

| Root | What you get |
|---|---|
| `slab-io/protocol` | `eve.slab-io`, the memory seam: 28 macros over `ISlabIO` / `IMemRegion` |
| `core/clojure` | `eve.host` over `clojure.core` |
| `completion/clojure` | an empty `eve.host-completion` |
| `coll/protocols` | `eve.core` as eve's own protocols (§2.5) |
| `slab/heap` | `eve.process-slab`: the allocator's own heap domain is this process's |
| `int32/portable` | Murmur3 / FNV-1a / bitmap arithmetic by `quot`, `mod`, `*`, `+` |
| `codec/byte-array` | the whole codec, values ⇄ EVE bytes, over `eve.host`'s byte arrays and your `eve.encoding` |
| `crypto/portable` | SHA-256, HMAC, AES-256-CTR, XXH3-128, Ed25519 in portable code; random bytes from your `eve.process` |
| `thread/none` | one thread: `start-threads!` refuses, eve.thread's pools run their work on the calling thread |

Two of them meet your host once:

- `thread/none`'s catch-all is spelled per host (`adapters/thread/none/src/eve/thread_host.cljc:53`):
  `(catch #?(:cljs :default :cljr Exception :lpy python/BaseException :clje _ :glj go/any :default Throwable) e`.
  If your root throwable is none of those, add your arm there.
- `core/clojure`'s `extend-default` extends a protocol to `nil` and `Object` (`default` for a ClojureScript compile)
  (`adapters/core/clojure/src/eve/host.cljc:56-63`). If your root type is neither, `eve.host` is yours to write.

## 4. A launcher's duties

What your language's entry point does before eve's namespaces load — a launcher's job, never shared code's:

- **Load the completion first** where it adds names to your core: on glojure, `eve.host-completion` loads before any
  namespace that uses `deftype` is made (`test/eve/runner/glj_main.glj:8-10`; `examples/in_memory.cljc`'s glojure
  command does `(require 'eve.host-completion)` first).
- **Run a started thread's entry** where a thread re-runs the program: a compiled ClojureScript program's entry point
  calls `eve.thread-host/run-thread!` first (`adapters/thread/node/src/eve/thread_host.cljs:267`; HOST-CONTRACT
  §11.1).

## 5. The conformance to run, in order

Each command runs from `eve/` on the minimal profile; write your language's runner on the pattern of
`test/eve/runner/bb_main.clj` (a list of namespaces, `clojure.test`, a non-zero exit on failure) and a `bb test:<host>`
task in `bb.edn`.

1. **The byte format and the hashes** — every host must write and read the same bytes:

   ```
   $ bb test:bb-minimal eve.eve-bytes-fixture-test eve.hamt-util-test
   Ran 8 tests containing 209 assertions.
   :summary {:test 8, :pass 209, :fail 0, :error 0}
   ```

2. **The memory seam** — slab I/O at every width and alignment, the allocator, the fused primitives, a region's words
   (its own root, `adapters/slab-io/test`, added to the classpath):

   ```
   $ bb -cp "$(bb classpath bb-minimal):adapters/slab-io/test:test" -m eve.runner.bb-main test eve.slab-io-test
   Ran 4 tests containing 51 assertions.
   :summary {:test 4, :pass 51, :fail 0, :error 0}
   ```

3. **The thread feature's contract** — rows read only the contract and the declared capabilities, so `thread/none`
   answers it too:

   ```
   $ bb test:bb-minimal eve.thread-host-test
   Ran 13 tests containing 30 assertions.
   :summary {:test 13, :pass 30, :fail 0, :error 0}
   ```

4. **Every shared suite** (every namespace of the runner but eve.net's, which need the `http` feature):

   ```
   $ bb test:bb-minimal
   :summary {:test 242, :pass 4623, :fail 0, :error 0}        # the final pass, 2026-10-21 ET, 5 min 15 s
   ```

5. **The smoke program** — atoms, a domain with member atoms, eve collections, a zero-copy read, futures and fold:

   ```
   $ bb -cp "$(bb classpath bb-minimal)" examples/in_memory.cljc
   counter => {:n 10}
   ...
   fold => 499500
   in_memory: ok
   ```

## 6. You are done when

- every step of §5 is green on your language;
- your row's minimal roots are your own and its other roots are the inbuilt nine — cross-check against `hosts.edn`;
- `bb examples/disk_domain_processes.clj` (with your host added to its `command` table) ends `ok`: your processes and
  other hosts' write one disk domain and no update is lost.

Then compare your row with a shipped host's. babashka's real row differs from the minimal profile in exactly the fast
seams it plugs:

| Feature | `:bb-minimal` (inbuilt) | `:bb` (shipped) | What the swap buys |
|---|---|---|---|
| crypto | `crypto/portable` | `crypto/java` | native SHA-256 / HMAC / AES / Ed25519 |
| int32 | `int32/portable` | `int32/jvm` | cheaper hashing |
| thread | `thread/none` | `thread/jvm` | real threads: eve.thread's pools run in parallel; the thread contract's full rows (55 assertions, against 30 on `thread/none`) |
| http | — | `http/java` | network atoms (`eve.net`) |
| http-server | — | `http-server/httpkit` | serving a gateway |

The measured difference, seam by seam, and how to plug each one, is [adopting-eve-fast.md](adopting-eve-fast.md).
