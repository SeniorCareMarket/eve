# Adopting eve: the fast seams

How to replace eve's portable (inbuilt) adapters with native ones, one capability at a time, once your language runs eve
on the minimal set ([adopting-eve-minimal.md](adopting-eve-minimal.md)). A fast seam keeps its feature's contract
exactly — the conformance tests hold it to the portable answer byte for byte — so plugging one can only change speed and
capability, never results. It may implement part of its feature and delegate the rest by name to the portable code.

Every command here was run on this tree on 2026-10-20 (ET) on babashka 1.13.226 — every one with a figure again on the
final tree in the final pass, 2026-10-21 ET — swapping one root of the minimal profile (`hosts.edn :bb-minimal`) for
the one babashka ships (`hosts.edn :bb`), with its output shown. Hosts whose
runtimes were not on the box (ClojureScript, ValueScript) are described from the tree and say so. The tiers are measured
in [`release-0.9/ADOPTER-TIERS.md`](release-0.9/ADOPTER-TIERS.md) §4–§6.

## 1. How a swap is made

A seam is one root in your `hosts.edn` row. Replace the inbuilt root with the native one and nothing else changes.
On babashka a one-root swap of the minimal profile is a `sed` over its classpath:

```
CP=$(bb classpath bb-minimal | sed 's|/crypto/portable/|/crypto/java/|')
bb -cp "$CP" examples/seam_bench.clj
```

## 2. Measuring a seam: the seam bench

`examples/seam_bench.clj` times eve.crypto, eve.int32 (through `eve.hamt-util`'s Murmur3) and reads the thread
feature's declared properties, as whatever adapters the classpath carries — median of 5 runs after 2 warm-ups:

```clojure
;; One process, one classpath: times eve.crypto, eve.int32 (through eve.hamt-util's Murmur3) and eve.thread-host
;; as whatever adapters the classpath carries. Median of 5 runs after 2 warm-ups, milliseconds.
(require '[eve.crypto :as c] '[eve.hamt-util :as hu] '[eve.thread-host :as th])

(defn median-ms [f]
  (dotimes [_ 2] (f))
  (let [ts (sort (for [_ (range 5)]
                   (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6))))]
    (nth ts 2)))

(def kib16 (vec (map #(mod (* 31 %) 256) (range 16384))))
(def msg200 (vec (map #(mod (* 7 %) 256) (range 200))))
(def seed (vec (range 32)))
(def pub (c/ed25519-public-key seed))
(def sig (c/ed25519-sign seed msg200))
(def key32 (vec (range 32)))
(def iv16 (vec (range 16)))
(def keys-bytes (mapv #(.getBytes (str "key-" %) "UTF-8") (range 10000)))

(defn row [label f] (println (format "%-40s %10.2f ms" label (double (median-ms f)))))

(println "driving:" (th/driving))
(row "sha256 16 KiB" #(c/sha256 kib16))
(row "aes256-ctr 16 KiB" #(c/aes256-ctr key32 iv16 kib16))
(row "xxh3-128 16 KiB" #(c/xxh3-128 kib16))
(row "ed25519-sign 200 B" #(c/ed25519-sign seed msg200))
(row "ed25519-verify 200 B" #(c/ed25519-verify pub msg200 sig))
(row "murmur3 10,000 keys (eve.int32)" #(doseq [b keys-bytes] (hu/portable-hash-bytes b)))
(println "verify ok:" (c/ed25519-verify pub msg200 sig))
```

(It uses `System/nanoTime` and `.getBytes`: a JVM-family script, run here on babashka.) Both ends, run in the final
pass, 2026-10-21 11:40 PM ET:

```
$ bb -cp "$(bb classpath bb-minimal)" examples/seam_bench.clj
driving: {:block :thread, :parallel false, :closures true, :eval false, :wake :poll, :spawn-ms 0, :max-threads 0, :runtime :none}
sha256 16 KiB                                157.86 ms
aes256-ctr 16 KiB                            186.02 ms
xxh3-128 16 KiB                               16.73 ms
ed25519-sign 200 B                           741.20 ms
ed25519-verify 200 B                         792.94 ms
murmur3 10,000 keys (eve.int32)              134.59 ms
verify ok: true

$ bb -cp "$(bb classpath bb)" examples/seam_bench.clj
driving: {:block :thread, :parallel true, :closures true, :eval true, :wake :poll, :spawn-ms 0, :max-threads nil, :runtime :bb}
sha256 16 KiB                                  1.46 ms
aes256-ctr 16 KiB                              3.31 ms
xxh3-128 16 KiB                               16.63 ms
ed25519-sign 200 B                             0.69 ms
ed25519-verify 200 B                           0.73 ms
murmur3 10,000 keys (eve.int32)               37.50 ms
verify ok: true
```

ADOPTER-TIERS §6's run of the same script on 2026-10-20 read 147.44 / 1.00 ms (SHA-256), 209.02 / 2.31 (AES),
977.48 / 0.84 (sign), 894.15 / 0.92 (verify), 133.15 / 70.11 (Murmur3) — the same orders; the Murmur3 native figure
moves most between runs. XXH3 is portable on both ends (no native adapter has one), so its row is run-to-run noise.

## 3. Capability by capability

### crypto

- **Contract**: `eve.crypto` — `sha256`, `hmac-sha256`, `aes256-ctr`, `xxh3-128`, `random-bytes`, `ed25519-public-key`,
  `ed25519-sign`, `ed25519-verify` over byte vectors (0..255); a native `ed25519-verify` answers only where
  `eve.net.ed25519/well-formed?` does, so every host refuses the eight small-order keys alike.
- **Inbuilt**: `crypto/portable` (all of it from `eve.net.portable-crypto`).
- **Native in the tree**: `crypto/java` (JVM, babashka), `crypto/node` (Node, nbb), `crypto/python` (basilisp),
  `crypto/clr` (ClojureCLR), `crypto/beam` (clojerl); `crypto/vs` (the kernel's SHA-256, the rest portable; no row uses
  it).
- **Per-primitive fallback**: implement what your platform has, delegate the rest by name. `crypto/java` has no XXH3
  and derives no public key from a seed, so (`adapters/crypto/java/src/eve/crypto.clj:21`, `:35-36`):

  ```clojure
  (defn sha256 "the 32-byte digest" [bs] (vec* (.digest (MessageDigest/getInstance "SHA-256") (arr bs))))
  (defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo (portable: the JDK has none)" [bs] (p/xxh3-128 bs))
  (defn random-bytes "n bytes of the operating system's entropy" [n] (process/random-bytes n))
  ```

  (`p` is `eve.net.portable-crypto`.) Exercised by `bb test:bb` and `clojure -M:jvm-test`.
- **The swap, measured**: only the crypto rows move.

  ```
  $ CP=$(bb classpath bb-minimal | sed 's|/crypto/portable/|/crypto/java/|'); bb -cp "$CP" examples/seam_bench.clj
  sha256 16 KiB                                  0.90 ms
  ed25519-sign 200 B                             0.71 ms
  murmur3 10,000 keys (eve.int32)              115.63 ms
  ```

  (lines of that run; the others as above). This is the first seam to plug: on the portable code every stored block of
  a network atom costs ~0.16 s per 16 KiB hashed and ~0.19 s encrypted, and a signature ~0.74 s.
- **Held to the portable answer by** `test/eve/net/crypto_test.cljc` (and `ed25519_test.cljc`):

  ```
  $ bb test:bb eve.net.crypto-test
  :summary {:test 6, :pass 85, :fail 0, :error 0}            # crypto/java, 2 s
  $ bb test:bb-minimal eve.net.crypto-test
  :summary {:test 6, :pass 85, :fail 0, :error 0}            # crypto/portable, 10 s
  ```

### int32

- **Contract**: `eve.int32` — `i32`, `imul`, `rotl`, `ushr`, `popcount`: signed 32-bit results identical on every host
  (the HAMT's hash is Murmur3 over EVE bytes; the trie's shape depends on it).
- **Inbuilt**: `int32/portable` (values kept in [0, 2^32) through `quot`, `mod`, `*`, `+`).
- **Native in the tree**: `int32/jvm` (JVM, babashka, jolt), `int32/js` (ClojureScript, nbb), `int32/vs`.
- **Worked example**, the whole of `adapters/int32/jvm/src/eve/int32.clj` (31 lines; exercised by `bb test:bb`):

  ```clojure
  (ns eve.int32
    "32-bit integer arithmetic as eve's hashes take it (Murmur3, FNV-1a, a
     HAMT's bitmaps): signed results, the same on every host. Over the JVM's
     (and babashka's and jolt's): longs, which unchecked-int narrows to their
     low 32 bits.")

  (defn i32
    "x's low 32 bits, as a signed integer."
    [x]
    (long (unchecked-int x)))

  (defn imul
    "a × b mod 2^32, signed."
    [a b]
    (long (unchecked-multiply-int (unchecked-int a) (unchecked-int b))))

  (defn rotl
    "x's 32-bit pattern rotated left by r bits (0 < r < 32), signed."
    [x r]
    (let [x (bit-and x 0xFFFFFFFF)]
      (long (unchecked-int (bit-or (bit-shift-left x r) (unsigned-bit-shift-right x (- 32 r)))))))

  (defn ushr
    "x's 32-bit pattern shifted right by n bits, zeros in: non-negative."
    [x n]
    (unsigned-bit-shift-right (bit-and x 0xFFFFFFFF) n))

  (defn popcount
    "The number of bits set in x's 32-bit pattern."
    [x]
    (Long/bitCount (bit-and x 0xFFFFFFFF)))
  ```

- **The swap, measured**: Murmur3 over 10,000 keys 134.59 → 39.74 ms; the crypto rows move only by run-to-run noise
  (portable in both runs).

  ```
  $ CP=$(bb classpath bb-minimal | sed 's|/int32/portable/|/int32/jvm/|'); bb -cp "$CP" examples/seam_bench.clj
  sha256 16 KiB                                129.33 ms
  ed25519-sign 200 B                           791.82 ms
  murmur3 10,000 keys (eve.int32)               39.74 ms
  ```

- **Held by** the byte fixtures and the pinned hash rows:

  ```
  $ CP=$(bb classpath bb-minimal | sed 's|/int32/portable/|/int32/jvm/|'); bb -cp "$CP:test" -m eve.runner.bb-main test eve.hamt-util-test eve.eve-bytes-fixture-test
  :summary {:test 8, :pass 209, :fail 0, :error 0}
  ```

### thread

- **Contract**: `eve.thread-host` (HOST-CONTRACT §11): 14 functions — `parallelism`, `self`, `start-threads!`, `send!`,
  `receive!` (a real blocking wait), `stop-threads!`, `ships-closures?`, `driving`, `attempt`, `error-data`,
  `capture-frame` / `call-with-frame`, `var-symbol`, `eval-form` (optional); a started thread's end reaches its starter
  as `{:eve.thread/exit addr :reason r}`.
- **Inbuilt**: `thread/none` (one thread; eve.thread's pools run their work on the caller).
- **Native in the tree**: `thread/jvm` (JVM, babashka, jolt — platform or virtual threads), `thread/node`,
  `thread/browser`, `thread/beam`, `thread/python`, `thread/clr`, `thread/glojure`.
- **Worked example**, `start-threads!` in `adapters/thread/jvm/src/eve/thread_host.clj:55-75` (exercised by
  `bb test:bb` and `clojure -M:jvm-test`):

  ```clojure
  (defn start-threads!
    "Start n threads, each running ((resolve entry) (assoc boot :self addr)); their addresses. opts: :kind :platform or
     :virtual, :name. When a thread's entry ends — it returned, or threw — its starter receives
     {:eve.thread/exit addr :reason r}: r :normal, or the error as error-data."
    [n entry boot opts]
    (let [f (requiring-resolve entry)
          starter (self)
          virtual? (case (:kind opts) :virtual true :platform false (virtual-default?))
          tname (or (:name opts) "eve-thread")]
      (vec (for [i (range n)]
             (let [id (box!)
                   r (fn []
                       (.set ^ThreadLocal local-id id)
                       (let [reason (try (f (assoc boot :self id)) :normal
                                         (catch Throwable e (error-data e)))]
                         (.remove ^java.util.concurrent.ConcurrentHashMap boxes id)
                         (send! starter {:eve.thread/exit id :reason reason})))]
               (if virtual?
                 (.start (.name (Thread/ofVirtual) (str tname "-" i)) ^Runnable r)
                 (doto (Thread. ^Runnable r (str tname "-" i)) (.setDaemon true) (.start)))
               id)))))
  ```

- **The swap**: the declared properties turn from `{:parallel false :max-threads 0 :runtime :none}` to
  `{:parallel true :max-threads nil :runtime :bb}` (the seam bench's `driving` line); the conformance runs its
  threaded rows:

  ```
  $ bb test:bb-minimal eve.thread-host-test
  :summary {:test 13, :pass 30, :fail 0, :error 0}           # thread/none
  $ CP=$(bb classpath bb-minimal | sed 's|/thread/none/|/thread/jvm/|'); bb -cp "$CP:test" -m eve.runner.bb-main test eve.thread-host-test
  :summary {:test 13, :pass 55, :fail 0, :error 0}           # thread/jvm
  ```

### coll

- **Contract**: `eve.core`, the collection vocabulary (~77–80 names) every eve value answers and a type is written in.
- **Inbuilt**: `coll/protocols` — eve's own protocols; an eve value answers `eve.core`'s functions.
- **Native in the tree**: `coll/jvm` (JVM, ClojureCLR, jolt), `coll/cljs`, `coll/nbb`, `coll/basilisp`, `coll/beam`:
  `clojure.core`'s own calls, so eve values answer your core's `count`, `get`, `seq` directly. From
  `adapters/coll/jvm/src/eve/core.clj:1-9`:

  ```clojure
  (ns eve.core
    "EVE's collection vocabulary (adapters/README.md, coll) on a host whose
     types implement its own collection interfaces and whose core is Clojure's
     with clojure.lang.RT — the JVM, ClojureCLR, jolt: clojure.core's calls,
     inlined through RT as clojure.core inlines them, so (count x) and
     (e/count x) are the same call, and -nth is nth. ...
  ```

- **Not a free swap**: `coll` is paired with `types` ([adopting-eve-minimal.md §2.5](adopting-eve-minimal.md)). A
  native `coll` needs a `types` adapter whose types implement your core's collection interfaces (`types/jvm`,
  `types/clr`, `types/basilisp`, `types/beam`); babashka's `deftype` cannot, so babashka stays on `coll/protocols` in
  its shipped row too. Held by every shared suite.

### codec

- **Contract**: `eve.codec` — values ⇄ EVE bytes (`encode-value`, `encode-key`, `decode`, …), the format
  FORMAT-DECISIONS fixes.
- **Inbuilt**: `codec/byte-array` (byte arrays through `eve.host` and your `eve.encoding`).
- **Native in the tree**: `codec/js` (ClojureScript, nbb: 1,385 lines over typed arrays, a DataView, a keyword cache
  and reusable buffers — `adapters/codec/js/src/eve/codec.cljs:1-9`). Held by the byte fixtures
  (`test/eve/eve_bytes_fixture_test.cljc`, every runner) and `node target/eve-test/all.js fixtures` (also in `all`, green
  2026-10-21).

### slab

- **Contract**: `eve.process-slab` — this process's own slab, where a value lands when no domain is named.
- **Inbuilt**: `slab/heap` (`init!` answers nil: the allocator's own heap domain is the process's).
- **Native in the tree**: `slab/module` (ClojureScript, nbb: module slabs over SharedArrayBuffers, one WASM instance per
  slab with SIMD bitmap scans; `adapters/slab/module/src/eve/process_slab.cljs`, 1,324 lines), registered with
  `eve.deftype-proto.alloc/register-host-slab!`. Held by `node target/eve-test/all.js slab` and `epoch-gc` (both in `all`, green
  2026-10-21).

### slab-io

- **Contract**: `eve.slab-io` (HOST-CONTRACT §12) — every slab access in eve's shared code: 28 names; no algorithm in an
  adapter.
- **Inbuilt**: `slab-io/protocol` (macros over `ISlabIO` / `IMemRegion`: two protocol dispatches per access).
- **Native in the tree**: `slab-io/vs` (plain functions over linear memory: a slab offset is an address in two loads;
  `adapters/slab-io/vs/src/eve/slab_io.vs`, 242 lines). A host that can make slab access static writes its own and
  holds it to the conformance test every implementation answers:

  ```
  $ bb -cp "$(bb classpath bb-minimal):adapters/slab-io/test:test" -m eve.runner.bb-main test eve.slab-io-test
  :summary {:test 4, :pass 51, :fail 0, :error 0}            # slab-io/protocol
  ```

  (`bb test:vs` runs the same test on `slab-io/vs`: 260 passed, 0 failed, 2026-10-21.)

### typed-view

- A host extra, not a seam: `eve.typed-view` gives a host its own views of an eve array (`typed-view/js`: a JS
  TypedArray over the elements, wasm-SIMD bulk ops). Shared code never requires it. Held by
  `node target/eve-test/all.js typed-array` — not run here.

### http — network atoms

- **Contract**: `eve.http/platform` → `{:request f}` (and optionally `:request-all`): `f` takes
  `{:method :url :headers :body :as :body-as}` and answers `{:status :headers :body}` or `{:error message}`
  **synchronously** on every host — a network atom's deref and swap answer the value, never a promise.
- **No inbuilt default**: it is I/O. Without an `http` root, `eve.net` does not load.
- **In the tree**: `http/java` (JVM, babashka), `http/node`, `http/browser` (synchronous XHR), `http/python`,
  `http/clr`, `http/beam`, `http/go`. The request, `adapters/http/java/src/eve/http.clj:37-41`, `:57` (exercised by `bb test:bb`'s `eve.thread-net-test`):

  ```clojure
  (defn request
    [req]
    (try
      (answer (.send ^HttpClient @client (build req) (HttpResponse$BodyHandlers/ofByteArray)) (:as req))
      (catch Exception e (failure e))))

  (defn platform [] {:request request :request-all request-all})
  ```

- **The addition**: one root on the minimal profile turns the network atom on:

  ```
  $ bb -cp "$(bb classpath bb-minimal):adapters/http/java/src:test" -m eve.runner.bb-main test eve.thread-net-test
  :summary {:test 5, :pass 45, :fail 0, :error 0}
  $ bb -cp "$(bb classpath bb-minimal):adapters/http/java/src" examples/network_atom.cljc
  ...
  blobs in the store => true
  network_atom: ok
  ```

### http-server — serving a gateway

- **Contract**: `(eve.http-server/serve! {:port :host :threads} handler)` → `{:port :stop}`; the handler takes
  `{:method :url :headers :body}` and answers `{:status :headers :body}` on a pool of threads.
- **In the tree**: `http-server/jdk` (JVM), `http-server/httpkit` (babashka), `http-server/node` (ClojureScript on
  Node: no `:threads`, the handler on Node's event loop, `{:port :stop :bound}` — `:bound` a promise of the port) —
  `adapters/http-server/httpkit/src/eve/http_server.clj:12-30` is all of babashka's. A host without one is a network
  client only; with one, any member of a disk domain can serve its atoms on eve.net's routes (`eve.net.gateway`).

## 4. What the shipped rows plug

From `hosts.edn` ([ADOPTER-TIERS §5.1](release-0.9/ADOPTER-TIERS.md) has the full table):

| Seam | Native on |
|---|---|
| crypto | jvm, bb (`crypto/java`); cljs, nbb (`crypto/node`); lpy; cljr; clje — portable on jolt, glj, vs, browser |
| int32 | jvm, bb, jolt (`int32/jvm`); cljs, browser, nbb (`int32/js`); vs — portable on cljr, lpy, clje, glj |
| thread | every row but vs (no thread root) |
| coll | jvm, cljr, jolt (`coll/jvm`); cljs, browser; nbb; lpy; clje — protocols on bb, glj, vs |
| codec | cljs, browser, nbb (`codec/js`) |
| slab | cljs, browser, nbb (`slab/module`) |
| slab-io | vs (`slab-io/vs`) |
| http | jvm, bb, cljs, browser (`http/browser`, synchronous XHR), nbb, lpy, cljr, clje, glj — none on jolt, vs |
| http-server | jvm (`http-server/jdk`), bb (`http-server/httpkit`), cljs (`http-server/node`) |
