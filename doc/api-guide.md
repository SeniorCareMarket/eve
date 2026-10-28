# eve API guide

The user API of eve 0.9, namespace by namespace: what an application calls. The extension API (what a host author or
a type author implements) is in [adopting-eve-minimal.md](adopting-eve-minimal.md) and
[unification/HOST-CONTRACT.md](unification/HOST-CONTRACT.md).

Arglists are the source's. Every sample was run as written on 2026-10-20 ET on babashka 1.13.226 and on the JVM
(JDK 21.0.12, Clojure 1.12.0), against this tree; classpaths are in [getting-started.md](getting-started.md).

| Namespace | What it is | Hosts |
|---|---|---|
| [`eve.alpha`](#evealpha) | the entry point: atoms, the core constructors, typed-array access, eve.core's vocabulary, the type macros | every host but ValueScript |
| [`eve.atom`](#eveatom) | atoms and atom domains | every host but ValueScript |
| [`eve.thread`, `eve.thread.fold`](#evethread) | the executor service: futures, pools, `pmap`, `fold`, `=>>` | every host with a thread adapter (all but ValueScript) |
| [`eve.net.atom`, `.gateway`, `.auth`, `.platform`](#evenet) | network atoms | hosts with an http adapter |
| [`eve.keyring`](#evekeyring) | encryption at rest, at the slab level | every host but ValueScript |
| [`eve.sorted`](#evesorted) | sorted maps and sets with comparators | every host but ValueScript |
| [`eve.deftype.int-map`](#evedeftypeint-map) | integer maps (a PATRICIA trie) | every host but ValueScript |
| [`eve.deftype.rb-tree`](#evedeftyperb-tree) | the red-black sorted set | every host but ValueScript |
| [`eve.rrb-vector`](#everrb-vector) | RRB vectors (core.rrb-vector in eve memory) | every host but ValueScript |
| [`eve.obj`](#eveobj) | typed objects and object arrays | every host but ValueScript |
| [`eve.array`](#evearray) | typed arrays with atomics | JVM, babashka, ClojureScript, nbb (not yet ClojureCLR, basilisp, jolt, clojerl, glojure) |
| [`eve.alpha.ds`, `eve.alpha.tensor`, `eve.alpha.col`](#columnar-evealphads-evealphatensor-evealphacol) | datasets, tensors, column ops | as `eve.array` |
| [`eve.deftype`](#evedeftype) | types in eve memory, and plain host types | every host but ValueScript |
| [`eve.crypto`](#evecrypto) | the crypto feature | every host |
| [`eve.http-server`](#evehttp-server) | serve a handler over HTTP | JVM, babashka, ClojureScript on Node |
| [`eve.typed-view`, `eve.sab`](#clojurescript-only-evetyped-view-evesab) | JS views of eve arrays; SharedArrayBuffer transfer | ClojureScript, nbb |

**eve values on babashka, glojure and ValueScript.** On these hosts eve's types answer eve's own protocols
(`adapters/coll/protocols`), not the host's collection interfaces. Inside `swap!` and `$` (where you hold eve
values) use `eve.core`'s functions (`ec/get`, `ec/assoc`, `ec/seq`, `ec/count`, ...). They work on every host, and
the samples below use them. A dereferenced value is the host's own data everywhere.

---

## eve.alpha

```clojure
(require '[eve.alpha :as e])

(def counter (e/atom ::counter {:count 0}))   ; in-process, named
(swap! counter update :count inc)
(println @counter)                              ; {:count 1}
(reset! counter {:count 10})
(println (compare-and-set! counter {:count 10} {:count 11}) @counter)   ; true {:count 11}

;; a read region: inside e/$, @ answers the stored value in place (nothing materialized but what you touch), held
;; until the region exits; what the region answers leaves as plain data
(println (e/$ (e/get @counter :count)))         ; 11
```

| Name | Arglist | What |
|---|---|---|
| `atom` | `(atom v)` `(atom ::id v)` `(atom {:id ::id} v)` `(atom {:id ::id :persistent "path"} v)` `(atom {:id ::id :persistent true} v)` | an atom: in-process (one heap domain per `:id`), or with `:persistent` a named atom in the disk domain at the path (`true`: `./eve/`). An anonymous persistent atom is named `"__anon__/<n>"` from a per-process counter, so give persistent atoms an `:id` |
| `atom-domain` | `[initial-value & opts]` | a fresh in-process domain whose own value starts as `initial-value`. Reads no options |
| `close-atom-domain!` | `[d]` | releases this process's worker slot in domain `d` |
| `shared-atom?` | `[x]` | true for an eve atom or domain |
| `hash-map`, `empty-hash-map`, `hash-set`, `empty-hash-set` | `[& args]` (a first argument that is a slab I/O names the domain), `[]` `[sio]` | eve's HAMT map and set (rarely needed: plain maps and sets become eve's when stored) |
| `eve-array`, `aget`, `aset!` | `[type-kw size-or-coll]` `[a b c]` `[sio type-kw n init-val]`, `[arr idx]`, `[arr idx val]` | typed arrays (see `eve.array`) |
| `count nth get seq conj empty contains? disj meta with-meta invoke reduce` | eve.core's | the collection vocabulary every eve value answers on every host |
| `deftype`, `extend-type` (macros) | `[& args]` | `eve.deftype/eve-deftype` and `eve-extend-type` |
| `$` (macro) | `[& body]` | a read region: in body, `@` of an eve atom answers its stored, slab-backed value; every domain it reads stays pinned (a network atom's lease kept) until the outermost `$` exits; body's result is materialized as it leaves. A `$` inside another joins it |

`eve.alpha` does not re-export `eve.core`'s `assoc`, `dissoc`, `into`, `pop`, `peek`, `reduce-kv` or `transient`;
require `eve.core` for them.

## eve.atom

The domain API is described in [atom-domains.md](atom-domains.md), with a run sample. In brief:

| Var | Arglist | What |
|---|---|---|
| `atom`, `persistent-atom` | `[& args]` | as `eve.alpha/atom`; `persistent-atom` always on disk |
| `persistent-atom-domain` | `[base-path & {:keys [capacities lustre?]}]` | open or create a disk domain, cached by path; the creator is the one exclusive create of `<base>.root` |
| `join-atom-domain` | `[base-path]` | the same |
| `lookup-or-create-mmap-atom!` | `[domain "ns/name" init]` | a member atom by string name |
| `domain-lookup` | `[domain k]` `[domain k not-found]` | a member atom by keyword |
| `registered-name` | `[a]` | the name `a` is registered under |
| `meta-atom` | `[a]` `[a init]` | a's coordination atom, beside it in the same domain |
| `version` | `[a]` | `[domain-epoch root-pointer]` |
| `seal` | `[a]` | a digest of a's stored version, byte for byte |
| `$*` | `[f]` | the region `eve.alpha/$` expands to: `(f)` as a read region |
| `read-pinned` | `[a f & args]` | `(f value & args)` with the slab-backed value, under a pin released when f returns; f's result must not keep it (eve's own bounded reads; a program reads with `eve.alpha/$`) |
| `compare-and-set!*` | `[a old new]` | CAS on every host |
| `atom-sio` | `[a]` | the domain's slab I/O, to build a value in that domain outside `swap!` |
| `close-atom-domain!`, `close!` | `[d]`, `[a]` | release the worker slot |

Deref (`@a`) materializes the value into the host's own collections. Its other public vars (`register-worker!`,
`begin-read!`, `retire-block!`, and the like) are the epoch surface the tests drive, not application API.

## eve.thread

One executor service over each host's threads. On a host without threads (`adapters/thread/none`) the pools run
their work on the calling thread.

```clojure
(require '[eve.thread :as t]
         '[eve.thread.fold :as tf]
         '[eve.process])

(println @(t/future (+ 1 2)))                           ; 3
(println (doall (t/pmap inc [1 2 3])))                  ; (2 3 4)
(println (tf/fold + (vec (range 1000))))                ; 499500
(println (t/=>> (range 10) (map inc) (filter even?) (reduce +)))   ; 30

(def p (t/fixed-pool 2))
(println (t/with-pool p @(t/future :on-p)))             ; :on-p
(println (t/deref (t/future (eve.process/sleep-ms! 50) :late) 10 :timeout))
(t/shutdown! p)
(t/shutdown-default-pools!)
```

It printed `3`, `(2 3 4)`, `499500`, `30`, `:on-p` and `:timeout` on both hosts [ran].

| Name | Arglist | What |
|---|---|---|
| `future` (macro), `future-call` | `[& body]`, `[f]` | on `*pool*`, else the `:io` pool. A closure where the pool's threads share this heap; a form with the locals it uses where they do not |
| `promise`, `deliver` | `[]`, `[p v]` | |
| `deref` | `[f]` `[f ms timeout-val]` | the three-argument deref on every host |
| `realized?`, `future-done?`, `future-cancel`, `future-cancelled?`, `future?`, `deref-within`, `descriptor-of` | `[f]` (`deref-within` `[f ms v]`) | |
| `pmap`, `pcalls`, `pvalues` (macro) | `[f coll & colls]`, `[& fns]`, `[& exprs]` | on `*pool*`, else the `:compute` pool; `pmap` is unchunked |
| `with-pool` (macro) | `[p & body]` | `*pool*` bound to `p` |
| `=>>` (macro) | `[x & forms]` | `->>`, with its separable transducer runs (`map filter remove keep mapcat cat replace`) folded over the pool |
| `fixed-pool`, `single-pool`, `cached-pool`, `per-task-pool`, `scheduled-pool` | `[n]` `[n opts]` / `[]` `[opts]` | pools |
| `schedule!`, `schedule-at-fixed-rate!`, `schedule-with-fixed-delay!` | on a scheduled pool | |
| `submit`, `invoke-all`, `invoke-any` | `[p f]` `[p f args]` `[p f args opts]`, `[p fs]`, `[p fs]` | |
| `shutdown!`, `shutdown-now!`, `shutdown?`, `terminated?`, `await-termination` | `[p]` (`await-termination` `[p ms]`) | |
| `default-pool`, `shutdown-default-pools!`, `current-pool`, `pool-name`, `pool-descriptor`, `registry` | | |
| `deftask`, `defconveyed`, `tfn` (macros) | | a task that may cross a heap by name; a dynamic var conveyed to tasks; a fn that travels as its form |
| `eve.thread.fold/fold` | `[reducef coll]` `[combinef reducef coll]` `[n combinef reducef coll]` | `clojure.core.reducers/fold` on `*pool*`, chunks sized from the pool's measured swap cost |
| `eve.thread.fold/fold-in` | `[p n combinef reducef coll]` | fold on pool `p` |

The `api-defs` names above carry no docstring at the REPL in 0.9 (see the limitations below).

**Domain cells**, pools and a registry shared by processes of any host through a domain's meta-atom, are
`eve.thread.domain` (`attach!`, `pool`, `registry`, `elect!`, `leader`, `submit-all!`, `collect`, `detach!`, ...) over
the rungs of `eve.thread.rung` (`place`, `rendezvous`, `open-atom`: `:heap`, `:disk`, `:window`) and `eve.thread.net`
(`:net`). Their reference is the namespaces' docstrings and `scripts/thread-rendezvous.clj` /
`scripts/thread-rotation.clj`. No user guide yet.

## eve.net

Network atoms: see [network-atoms/README.md](network-atoms/README.md), with two run samples.

| Var | Arglist | What |
|---|---|---|
| `eve.net.atom/remote-atom` | `[cfg]` `[cfg platform]` | a handle on the atom `cfg` names (`{:endpoint :domain-id :atom-id :api-key}`, optional `:gak`) |
| `eve.net.atom/deref`, `swap!`, `reset!` | `[ra]`, `[ra f & args]`, `[ra v]` | synchronous on every host; the value is a map |
| `eve.net.atom/cached`, `release!`, `renew!` | `[ra]`, `[ra]`, `[ra]` `[ra force?]` | the last value without the network; the lease |
| `eve.net.gateway/gateway` | `[domain opts]` | serve a disk domain's atoms on the broker's routes: `{:handle (fn [req] resp) ...}`; opts `:endpoint :domain-id :api-key :presign :platform :deny :gak` |
| `eve.net.gateway/hmac-presigner`, `hmac-ok?` | `[{:keys [url secret now-ms]}]`, `[secret k mode exp sig now-s]` | a stand-in for an S3 store's presigning, and its check |
| `eve.net.gateway/atom-id`, `atom-name`, `stats` | `[name]`, `[id]`, `[g]` | |
| `eve.net.auth/device-code`, `device-token`, `await-token`, `refresh`, `claims`, `with-token` | | signing in (RFC 8628 device flow) |
| `eve.net.platform/platform` | `[]` `[overrides]` | the host's `{:request :sleep :now-ms :seed}` |

## eve.keyring

**Opt-in, slab-level encryption at rest.** The atom API (`e/atom`, `persistent-atom-domain`) never encrypts and has no
option to. `eve.keyring` opens a disk domain's slabs under AES-256-CTR and answers a slab I/O, not an atom domain.
Its default mode (`:status 1`, auto) **stores the group key in the clear** in `<base>.keyring` beside the data. There
is no integrity check, a block rewritten in place reuses its keystream, and slab headers, bitmaps and the root file stay
plaintext. Read [../SECURITY.md](../SECURITY.md) before relying on it.

```clojure
(require '[eve.keyring :as k])
(def base (str (first *command-line-args*) "/enc"))
(let [[sio ring] (k/open-encrypted-domain! base {:create? true})]
  (println (k/status ring) (k/encrypted-sio? sio) (k/keyring-path base)))
```

It printed `1 true <dir>/enc.keyring`. The directory held `enc.keyring` and the slab files `enc.slab0`–`enc.slab6`
with their bitmaps [ran].

| Var | Arglist | What |
|---|---|---|
| `open-encrypted-domain!` | `[base-path {:keys [create? status key capacities]}]` | `[slab-io ring]`. `:status` 1 auto (a fresh key, stored), 2 a user key (stored), 3 a user key not stored (only its fingerprint) |
| `encrypted-sio`, `encrypted-sio?` | `[inner gak salt]`, `[sio]` | |
| `open-ring`, `init!`, `status`, `read-gak`, `keyring-path`, `fingerprint`, `domain-salt`, `class-key` | | the key ring |

## eve.sorted

```clojure
(require '[eve.core :as ec]
         '[eve.sorted :as sorted]
         '[eve.deftype.int-map :as im]
         '[eve.deftype.rb-tree :as rb]
         '[eve.rrb-vector :as rrb]
         '[eve.obj :as obj]
         '[eve.array :as arr]
         '[eve.crypto :as crypto])

;; outside an atom an eve collection lives in this process's own slab
(def sm (sorted/sorted-map 3 :c 1 :a 2 :b))
(println (ec/seq sm))                                          ; ([1 :a] [2 :b] [3 :c])
(println (sorted/rank sm 2) (sorted/nth-entry sm 0))           ; 1 [1 :a]
(println (sorted/subseq sm >= 2))                              ; ([2 :b] [3 :c])
(println (ec/seq (sorted/sorted-set-by > 1 3 2)))              ; (3 2 1)

(def m (im/int-map 1 10 2 20))
(println (ec/get (im/merge-with + m (im/int-map 1 1 3 3)) 1))  ; 11
(println (ec/count (im/range (im/int-map 1 :a 5 :e 9 :i) 0 5))) ; 2

(def tags (rb/sorted-set "python" "clojure"))
(println (rb/member? tags "clojure") (ec/seq tags))            ; true (clojure python)

(def v (rrb/catvec (rrb/vec (range 3)) (rrb/vector 10 11)))
(println (ec/count v) (ec/nth v 3))                            ; 5 10

(def node (obj/obj {:key :int32 :value :float64} {:key 42 :value 3.14}))
(println (obj/get node :key) (obj/cas! node :key 42 43) (obj/get node :key))   ; 42 true 43

(def a (arr/eve-array :int32 4))
(arr/aset! a 0 7)
(println (arr/add! a 0 5) (arr/aget a 0) (arr/array-type a))  ; 7 12 :int32

(println (count (crypto/sha256 [97 98 99])))   ; 32
```

Every line printed what its comment shows, on both hosts [ran]. The same sample serves the sections below. Inside an
atom, build these collections inside `swap!` (see [getting-started.md](getting-started.md#eve-collections)).

| Var | Arglist | What |
|---|---|---|
| `sorted-map`, `sorted-map-by` | `[& kvs]`, `[cmp & kvs]` | |
| `sorted-set`, `sorted-set-by` | `[& ks]`, `[cmp & ks]` | |
| `subseq`, `rsubseq` | `[sc test k]` `[sc start-test start-k end-test end-k]` | Clojure's |
| `rank`, `nth-entry`, `find` | `[sc k]`, `[sc i]`, `[sc k]` | index of a key; the i-th entry; the stored entry |
| `sorted-coll?`, `shape`, `disj!`, `dissoc!` | | |

## eve.deftype.int-map

A 16-way PATRICIA trie over 32-bit integer keys, negative keys included.

| Var | Arglist | What |
|---|---|---|
| `int-map` | `[]` `[k v]` `[k v & kvs]` | |
| `merge`, `merge-with` | `[a b & more]`, `[f a b & more]` | |
| `update` | `[m k f & args]` | |
| `range` | `[m min-k max-k]` | the entries with keys in `[min-k, max-k]` |

## eve.deftype.rb-tree

An Okasaki-style red-black tree: `sorted-set` (`[]` `[& vals]`), `member?` (`[s v]`). Its node-level `rb-*` functions
are public for tests.

## eve.rrb-vector

core.rrb-vector's ClojureScript implementation in eve memory (EPL-1.0, see [../NOTICE](../NOTICE)): `vector`, `vec`,
`catvec` (logarithmic concatenation), `subvec`. It is also the worked example of writing an eve type
([data-structures.md](data-structures.md)).

## eve.obj

Typed objects (one block per object) and object arrays (one eve array per field). The int32/uint32 fields carry the
atomics; `cas!` and `exchange!` also take `:obj` and `:array` fields. See [obj.md](obj.md).

`create-schema`, `obj`, `obj-array`, `obj-from-offset`; `get`, `assoc!`, `cas!`, `add!`, `sub!`, `exchange!`;
`get-in`, `assoc-in!`, `cas-in!`, `add-in!`, `sub-in!`, `exchange-in!` (`[arr [idx k] ...]`); `column`,
`column-reduce`, `column-map!`; `get-schema`, `get-offset`, `retire!`.

## eve.array

Typed arrays (`:int8 :uint8 :uint8-clamped :int16 :uint16 :int32 :uint32 :float32 :float64`) with atomics on the
integer types. On the JVM and babashka a fresh array is the host's own primitive array (`encoding/java`'s
`typed-array`); on ClojureScript and nbb it is a slab block. Typed arrays are not yet on ClojureCLR, basilisp, jolt,
clojerl or glojure (their runners leave the typed-array suites out).

`eve-array` (`[type-kw size-or-coll]` `[a b c]` `[sio type-kw n init-val]`), `eve-array-uninit` (`[type-kw n]` `[sio type-kw n]`),
`int32-array` (`[n]` `[n init-val]`), `int32-array-from`, `aget`, `aset!`, `array-type`; atomics `cas!`
(`[arr idx expected new-val]`), `add!`, `sub!`, `band!`, `bor!`, `bxor!`, `exchange!` (`[arr idx v]`); `wait!`
(`[arr idx expected]` `[arr idx expected timeout-ms]`) and `notify!` (`[arr idx]` wakes **all** waiters,
`[arr idx n]`), `:int32` only; `areduce` (`[arr init f]`), `amap`, `amap!` (`[arr f]`), `afill!`, `acopy!`;
`from-double-array`, `from-int-array`, `from-byte-array` (`[host-arr]`); `retire!`.

## Columnar: eve.alpha.ds, eve.alpha.tensor, eve.alpha.col

```clojure
(require '[eve.alpha :as e]
         '[eve.alpha.ds :as ds]
         '[eve.alpha.tensor :as tensor]
         '[eve.alpha.col :as col])

(def d (ds/dataset {:price (e/eve-array :float64 [10.5 20.25 3.0])
                    :qty   (e/eve-array :int32 [100 200 300])}))
(println (ds/row-count d) (sort (ds/column-names d)))     ; 3 (:price :qty)
(println (col/sum (ds/column d :qty)))                    ; 600.0

(def t (tensor/from-array (e/eve-array :int32 [1 2 3 4 5 6]) [2 3]))
(println (tensor/shape t) (tensor/mget t 1 2))            ; [2 3] 6
```

Ran on both hosts [ran]. The full reference is [data-structures.md](data-structures.md):

- `eve.alpha.ds`: `dataset`, `dataset?`, `column`, `column-names`, `row-count`, `dtypes`, `select-columns`,
  `add-column`, `drop-column`, `rename-columns`, `reindex`, `filter-rows`, `sort-by-column`, `head`, `tail`, `slice`.
- `eve.alpha.tensor`: `from-array`, `zeros`, `ones`, `shape`, `rank`, `dtype`, `mget`, `mset!`, `reshape`,
  `transpose`, `slice-axis`, `emap`, `ereduce`, `to-array`, `to-dataset`, `tensor?`, `contiguous?`.
- `eve.alpha.col`: `add`, `sub`, `mul`, `div`, `emap`, `emap2`, `eq`, `gt`, `lt`, `sum`, `mean`, `min-val`,
  `max-val`, `argsort`, `argfilter`, `arggroup`, `argmin`, `argmax`, `take-indices`.

To store one in an atom, build it with the atom's slab I/O (`eve.atom/atom-sio`) or inside `swap!`.

## eve.deftype

| Macro | Arglist | What |
|---|---|---|
| `eve-deftype` | `[type-name fields & body]` | a type whose fields live in eve memory, written with ClojureScript's protocol names or the JVM's; each host's `eve.types` adapter realizes it |
| `eve-extend-type` | `[type-name & body]` | extend protocols to an existing eve type |
| `deftype` | `[type-name fields & body]` | a plain host type, written once for every host |
| `extend-host-vectors` | `[proto & impls]` | extend a protocol to the host's own vector types |

`eve.alpha/deftype` and `extend-type` are `eve-deftype` and `eve-extend-type`. The worked example is
`eve.rrb-vector` ([data-structures.md](data-structures.md)).

## eve.crypto

`sha256`, `hmac-sha256`, `aes256-ctr` (`[key iv data]`), `xxh3-128`, `random-bytes` (`[n]`, the OS's entropy),
`ed25519-public-key`, `ed25519-sign`, `ed25519-verify`. Bytes are vectors of 0..255 in and out. Each host's adapter
is native where its platform has the primitive, and the portable code otherwise. The adapters are held to the portable
answers byte for byte (`test/eve/net/crypto_test.cljc`).

## eve.http-server

`(serve! {:port :host :threads} handler)` → `{:port :stop}`. `handler` takes `{:method :url :headers :body}` and
answers `{:status :headers :body}`, on a pool of threads. JVM (`http-server/jdk`), babashka
(`http-server/httpkit`) and ClojureScript on Node (`http-server/node`: no `:threads`, the handler runs on Node's
event loop; it answers `{:port :stop :bound}`, and with `:port` 0 `:port` is nil — `(:bound srv)` is a promise of the
port). A run sample serving a gateway is in [network-atoms/README.md](network-atoms/README.md#over-sockets).

## ClojureScript only: eve.typed-view, eve.sab

On ClojureScript and nbb: `eve.typed-view` gives JS views of an eve array (`get-typed-view`, `get-sab`,
`from-typed-array`) and wasm-SIMD bulk ops on `:int32` arrays (`afill-simd!`, `acopy-simd!`, `asum-simd`,
`amin-simd`, `amax-simd`, `aequal-simd?`; not atomic). `eve.sab/sab-transfer-data` gives a domain's
SharedArrayBuffers (`{:sab :reader-map-sab :slab-sabs :root-sab}`) for handing to a worker. `eve.sab` is exercised by
Node's `all` suite (`eve.batch4-validation-test`); `eve.typed-view` has no test in it.

---

## Known limitations in 0.9

Each was reproduced in the 0.9 preparation; each is an open item for 1.0.

- **`atom-domain`'s value carries its registry entry.** `@(e/atom-domain {:counter 0})` is
  `{__atom-domain__/root 1, :counter 0}` [ran].
- **An eve collection built outside `swap!` cannot be stored into an atom.** `(e/atom ::x {:data (im/int-map 1 :a)})`
  throws "An Eve value allocated in a different slab domain cannot be put in this one; build it inside swap! (or with
  the atom's domain sio, eve.atom/atom-sio)" [ran]. Build it inside `swap!`.
- **`swap!` returns the slab-backed value**, not a materialized one. Use its return only for what `$` allows, or
  deref afterwards.
- **`im/range` on an int-map read through `eve.alpha/$`** throws; inside `swap!`, or outside any atom, it works.
- **The `api-defs` exports** of `eve.alpha` and `eve.thread` have no docstring or arglist at the REPL; this guide is
  their reference.
- No watches or validators on eve atoms. babashka's `swap!` on an eve atom takes at most six extra arguments
  (sci's dispatch: seven or more throw, babashka 1.13.226); pass a closure instead.
