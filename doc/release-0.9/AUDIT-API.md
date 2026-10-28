# eve 0.9 — API census and impls audit

> **History:** this audit describes the tree at `112f6ced2` (2026-10-20). The lane acted on it afterwards (the docs
> repaired from `007d4edc8` on, the defects below fixed or recorded). The current state is `CHANGELOG.md` 0.9.0,
> `doc/release-0.9/RELEASE-CHECKLIST.md` §9–§10 and `doc/release-0.9/STATUS.md`.

What eve exposes at base `112f6ced2b` (branch `claude/eve-0.9-release`, 2026-10-20 ET), diffed against what
`README.md` and `doc/api-guide.md` (and getting-started, persistent-atoms) claim; then (§7) the implementations under
`eve/impls/`. The census is mechanical (a reader over every source file of `src/` and `adapters/`, 162 files, 0 read
errors; macro-generated `api-defs` expanded by hand); arglists and snippets were run on babashka 1.13.226 and the JVM
(JDK 21, Clojure 1.12.0). The integrator re-ran the load-bearing defects (§5) on this box [ran]: §5.2 reproduced on
babashka's shipped row with `eve.thread-net-test` run alone (5 tests, 1 error), and confirmed order-dependent — the full
`bb test:bb` reads green because an earlier test encodes first.

Measured 2026-10-20, 1:45–2:05 PM ET, on the tree at `112f6ced2b` (branch `claude/eve-0.9-release`, no source edits).
Provenance tags: **[ran]** executed here this session (command and output quoted); **[read]** from the source, file:line
cited; **[docs]** what a document claims.

Method:
- **Mechanical census [ran]**: a reader script (`LispReader`, `:read-cond :preserve`) over every `.clj/.cljc/.cljs/.lpy/.clje/.cljr`
  file in `eve/src` and `eve/adapters` — 162 files, 0 read errors — listing each namespace's top-level defs, public vs
  private, with their reader-conditional features. It cannot see macro-generated defs: `eve.alpha`'s and `eve.thread`'s
  `api-defs` tables were expanded by hand from `src/eve/alpha.cljc:41-77` and `src/eve/thread.cljc` (the `api` vector).
- **Arglists [ran]**: every user namespace loaded on the JVM (`java -cp $(clojure -Spath -M:jvm-test) clojure.main`, ~6 s
  to load all of eve) and `ns-publics` dumped with `:arglists` and the docstring's first line; the `api-defs` names
  resolved to their target vars for theirs.
- **Doc coverage [ran]**: every `alias/name` call form in README.md, doc/api-guide.md, getting-started.md,
  persistent-atoms.md, data-structures.md, collections.md and obj.md resolved against the census; and every public
  user-API var grepped for in the four user docs and in all of `doc/`.
- **Snippets [ran]**: on babashka 1.13.226 (`bb -cp src:<hosts.edn :bb roots>`) and the JVM (JDK 21, Clojure 1.12.0);
  §6.

---

## 1. Classification: where every namespace sits

178 namespace files (`src/`: 86, `adapters/`: 92 — the adapters' 92 files are 30 namespace names, each implemented once
per capability). Classified by reading each namespace's docstring and its callers, not its name.

### 1.1 USER API — what an application calls

| Namespace | Public (census) | Role |
|---|---|---|
| `eve.alpha` | 23 + 2 macros (`api-defs`) | the entry point, every host: `atom`, `atom-domain`, `close-atom-domain!`, `shared-atom?`, `hash-map`, `empty-hash-map`, `hash-set`, `empty-hash-set`, `aget`, `aset!`, `eve-array`, eve.core's `count nth get seq conj empty contains? disj meta with-meta invoke reduce`; macros `deftype`, `extend-type` (alpha.cljc:27-77). ClojureScript's `adapters/core/cljs-compiler/src/eve/alpha.cljs:20` calls the same `(eve.alpha/api-defs)`, so the names are identical by construction |
| `eve.atom` | 49 public | atoms and atom domains (§3a); about half of the public vars are the epoch-GC test surface (atom.cljc:1171 "the epoch-GC surface the eve.shared-atom tests call") |
| `eve.thread` | 40 + 9 macros (`api-defs`) | the executor service: `future`, `future-call`, `promise`, `deliver`, `pmap`, `pcalls`, `pvalues`, `with-pool`, `=>>`, pools, `submit`, `invoke-all/any`, `shutdown!`…, `deftask`, `defconveyed`, `tfn`, `deref` |
| `eve.thread.fold` | 21 | `fold` (and the chunk planner behind `=>>`) |
| `eve.thread.domain` | 45 | the domain cell: `attach!`, `pool`, `registry`, `detach!`, `elect!`/`leader`/`resign!`, `desire!`/`start!`, `submit-all!`, `collect`, `read-meta`, `swap-meta!`, `meta-value`, `via`, the upgrade (`hello!`, `ack-hellos!`, `upgrade!`, `migrate-pool!`) |
| `eve.thread.rung` | 19 | where a domain cell lives: `place`, `rendezvous`, `open-atom`, `value`, doorbells (`ring!`, `wait-bell!`) |
| `eve.thread.net` | 4 | `rung`: a domain cell over a network atom |
| `eve.net.atom` | 13 | the network atom (§3b) |
| `eve.net.gateway` | 6 | `gateway`: serve a disk domain's atoms on eve.net's broker routes |
| `eve.net.auth` | 7 | signing in to the broker (RFC 8628 device flow, refresh, token `claims`) |
| `eve.net.platform` | 1 | `platform`: the host's `{:request :sleep :now-ms :seed}`, overridable |
| `eve.keyring` | 49 (20 fns) | at-rest encryption of a domain's slab blocks (§3c) |
| `eve.sorted` | 23 | sorted-map/-by, sorted-set/-by, subseq, rsubseq, rank, nth-entry |
| `eve.deftype.int-map` | 15 | `int-map`, `merge`, `merge-with`, `update`, `range` |
| `eve.deftype.rb-tree` | 16 | `sorted-set`, `member?` (+ `rb-*` node fns) |
| `eve.rrb-vector` | 4 | `vector`, `vec`, `catvec`, `subvec` (core.rrb-vector in eve memory) |
| `eve.obj` | 22 | typed objects (AoS) and object arrays (SoA) |
| `eve.array` | 40 | typed arrays + atomics + wait/notify |
| `eve.alpha.ds` / `eve.dataset` | 16 / 18 | columnar datasets |
| `eve.alpha.tensor` / `eve.tensor` | 17 / 19 | N-d views over EveArrays |
| `eve.alpha.col` (over `eve.dataset.functional`, `.argops`) | 19 | element-wise and index-space column ops |
| `eve.deftype` | 4 macros | `eve-deftype`, `deftype`, `eve-extend-type`, `extend-host-vectors` |
| `eve.http-server` (adapter) | 1 | `serve!` — what a gateway member needs (JVM, bb; Node since `ba99b61cc`) |
| `eve.crypto` (adapter) | 8 | `sha256`, `hmac-sha256`, `aes256-ctr`, `xxh3-128`, `random-bytes`, `ed25519-*` |
| `eve.typed-view`, `eve.sab` (adapters, ClojureScript/nbb only) | — | `get-typed-view`, `get-sab`, `afill-simd!`, `acopy-simd!`, `asum-simd`; `sab-transfer-data` |

### 1.2 EXTENSION API — what a host author or a type author uses

- **The adapter interfaces** (one namespace each, implemented per capability under `adapters/<feature>/<impl>/`):
  `eve.host` (4 impls), `eve.host-completion` (4), `eve.slab-io` (3), `eve.region` (9), `eve.encoding` (7),
  `eve.process` (7), `eve.int32` (4), `eve.codec` (2), `eve.core` (6), `eve.types` (8), `eve.process-slab` (2),
  `eve.thread-host` (8), `eve.http` (6; 7 with the browser's since `4bcd756cc`), `eve.http-server` (2; 3 with Node's since
  `ba99b61cc`), `eve.crypto` (7), `eve.typed-view` (1).
- **Shared seams in `src/`**: `eve.mem` (`IMemRegion`, `IHostAtomics`, bitmap helpers), `eve.deftype-proto.alloc`
  (`ISlabIO`, offsets, `JvmSlabCtx`, `register-host-slab!`), `eve.deftype-proto.serialize` (tags, constructor and writer
  registries), `eve.deftype-proto.vocab` / `.proto-map` / `.sio` (the type language and its realization),
  `eve.deftype.macros`, `eve.deftype-proto.macros(.registry)`, `eve.deftype.array`, `eve.deftype.value-buffer`,
  `eve.deftype.slab-runtime`, `eve.deftype.registry`, `eve.coll`, `eve.rseq`, `eve.platform`, `eve.hamt-util`,
  `eve.deftype-proto.data` (124 constants), `eve.net.run` (the sans-IO step driver a platform runs),
  `eve.net.portable-crypto` (the reference every crypto adapter is held to).

### 1.3 INTERNAL

- Collections behind `eve.alpha`: `eve.map`, `eve.set`, `eve.vec`, `eve.list` (no vector or list constructor is exported
  by `eve.alpha`; values become eve collections when written into an atom).
- `eve.shared-atom` (`src/eve/shared_atom.cljs`, protected file): the legacy ClojureScript SAB atom. `e/atom` no longer
  returns it — `eve.alpha/atom` is `eve.atom/atom` (alpha.cljc:41), which returns an `MmapAtom` on every host. No
  namespace requires it; it is named only in comments and in the `#eve/shared-atom` print tag (`eve.deftype-proto.data`,
  `eve.atom`, `adapters/region/js/src/eve/sab.cljs`, `test/eve/batch4_validation_test.cljs`).
- eve.thread's engine: `eve.thread.cell`, `.descriptor`, `.task`, `.future`, `.pool`, `.meta`.
- eve.net's internals: `aes`, `bn254`, `blobs`, `bytes`, `codec`, `crypto`, `ed25519`, `graft`, `json`, `merkle`,
  `poseidon2`, `sha256`, `sha512`, `snapshot`, `u64`, `wire`, `xxh3`.
- `eve.rrb-vector.*` sub-namespaces, `eve.obj.impl`, `eve.dataset.argops`/`.functional` (re-exported by `eve.alpha.col`).
- Adapter-internal: `eve.wasm-mem`, `eve.util`, `eve.data`, `eve.deftype-proto.wasm`, `eve.types-js`, `eve.clje-nif`,
  `clojure.edn` (glojure's completion shim), `eve.slab-io-test` (a conformance test, under `adapters/slab-io/test`).

---

## 2. The user API, namespace by namespace (arglists [ran] on the JVM)

Doc column: **D** named in README/api-guide/getting-started/persistent-atoms; **d** only elsewhere under `doc/`
(design docs, data-structures.md, collections.md, obj.md); **—** in no document.

### eve.alpha (the `api-defs` targets)

| Name | Arglists | Doc |
|---|---|---|
| `atom` → `eve.atom/atom` | `[& args]`: `(atom v)`, `(atom ::id v)`, `(atom {:id ::id} v)`, `(atom {:id ::id :persistent "path"} v)`, `(atom {:id ::id :persistent true} v)` (`./eve/`) | D |
| `atom-domain` | `[initial-value & {:as _opts}]` — options accepted and **ignored** (atom.cljc:1159-1172) | D (with options that do nothing) |
| `close-atom-domain!` | `[d]` | D (persistent-atoms) |
| `shared-atom?` | `[obj]` | D |
| `hash-map` / `empty-hash-map` / `hash-set` / `empty-hash-set` | `[& args]` / `[]` `[sio]` | D |
| `aget` / `aset!` / `eve-array` | `[arr idx]` / `[arr idx val]` / `[type-kw size-or-coll]` `[a b c]` `[sio type-kw n init-val]` | D |
| `count nth get seq conj empty contains? disj meta with-meta invoke reduce` | eve.core's | D (README only lists them as the "vocabulary") |
| `deftype`, `extend-type` (macros) | `[& args]` → `eve.deftype/eve-deftype`, `eve-extend-type` | D |

Every `api-defs` export is a plain `(def name value)`: **none carries an arglist or docstring** — `(doc eve.alpha/atom)`
prints nothing [ran: `:arglists` nil on every `eve.alpha` and `eve.thread` api name]. eve.core's `assoc`, `dissoc`,
`into`, `pop`, `peek`, `reduce-kv`, `transient` exist (`adapters/coll/*/src/eve/core.*`) but are **not** re-exported by
`eve.alpha`.

### eve.atom (user-facing subset)

| Var | Arglists | What | Doc |
|---|---|---|---|
| `atom` | `[& args]` | in-process (a heap domain per `:id`, cached; atom.cljc:1101-1121) or, with `:persistent`, `persistent-atom` | D |
| `persistent-atom` | `[& args]` | named atom in an mmap domain; `:persistent` a path or `true` (`./eve/`, or `*global-persistent-atom-domain*`) | D |
| `persistent-atom-domain` | `[base-path & {:keys [capacities lustre?]}]` | open-or-create the disk domain, cached by path | D |
| `join-atom-domain` | `[base-path]` | = `persistent-atom-domain` | — |
| `close-atom-domain!` / `close!` | `[d]` / `[a]` | release the worker slot, stop the heartbeat | D / d |
| `atom-domain` | `[initial-value & {:as _opts}]` | a fresh in-process domain whose own value is slot 0 | D |
| `domain-lookup` | `[domain k]` `[domain k not-found]` | the member atom named `k` | — |
| `lookup-or-create-mmap-atom!` | `[domain kw-str initial-val]` | a member atom by string name | — |
| `meta-atom` | `[a]` `[a initial-value]` | a's coordination atom, beside it in the same domain | — |
| `registered-name`, `version`, `seal` | `[a]` | a's registry name; `[epoch root-ptr]`; a byte-exact digest of a's stored version | — |
| `$` | `[a f & args]` | `(f lazy-value & args)` under a pin — the zero-copy read | — |
| `atom-sio` | `[atm]` | the domain's slab I/O, to build values in the atom's domain | — |
| `compare-and-set!*` | `[a old new]` | CAS on every host (ClojureScript's core CAS is for its own Atom) | — |
| `global-domain`, `*global-atom-instance*`, `*global-persistent-atom-domain*` | | default-domain plumbing | — |
| epoch/worker surface: `register-worker!`, `unregister-worker!`, `update-heartbeat!`, `check-worker-liveness`, `get-current-epoch`, `increment-epoch!`, `begin-read!`, `end-read-epoch!`, `with-read-epoch`, `get-min-active-epoch`, `retire-block!`, `try-free-retired!`, `sweep-retired-blocks!`, `mark-stale-workers!`, `init-domain-root!` | | the test surface (atom.cljc:1174-1257) — internal in fact, public in form | — |

### eve.thread (the `api-defs` targets + macros)

`future` (macro, `[& body]`), `future-call [f]`, `promise []`, `deliver [p v]`, `deref [f] [f ms timeout-val]`,
`realized? future-done? future-cancel future-cancelled? future? descriptor-of [f]`, `deref-within [f ms v]`,
`pmap [f coll & colls]` (unchunked), `pcalls [& fns]`, `pvalues` (macro), `with-pool` (macro `[p & body]`), `=>>` (macro
`[x & forms]`), pools `fixed-pool [n] [n opts]`, `single-pool`, `cached-pool`, `per-task-pool` `[] [opts]`,
`scheduled-pool [n] [n opts]`, `schedule! [p f delay-ms] [p f args delay-ms]`, `schedule-at-fixed-rate!`,
`schedule-with-fixed-delay!`, `default-pool [kind]`, `shutdown-default-pools! []`, `current-pool`, `pool-name`,
`pool-descriptor`, `registry`, `submit [p f] [p f args] [p f args opts]`, `invoke-all [p fs]`, `invoke-any [p fs]`,
`shutdown! shutdown-now! shutdown? terminated? [p]`, `await-termination [p ms]`, `register-task! [sym f h]`,
`task-fn [sym] [sym h]`, `attempt [f]`; macros `deftask`, `defconveyed`, `tfn`. `eve.thread.fold/fold`:
`[reducef coll] [combinef reducef coll] [n combinef reducef coll]`, `fold-in [p n combinef reducef coll]`.
**Doc: — for every name.** `eve.thread` appears in no user document; only in `doc/unification/HOST-CONTRACT.md` §11 and
CLAUDE.md's namespace map.

### eve.net.atom, eve.net.gateway, eve.net.auth

`remote-atom [cfg] [cfg platform]`, `deref [ra]`, `swap! [ra f & args]`, `reset! [ra v]`, `cached [ra]`, `release! [ra]`,
`renew! [ra] [ra force?]`, `value-step [cfg blob cache]`, `snapshot`. `gateway [domain opts]`, `hmac-presigner
[{:keys [url secret now-ms]}]`, `hmac-ok?`, `atom-id [nm]`, `atom-name [aid]`, `stats [g]`. `device-code [cfg]`,
`device-token [cfg device-code]`, `await-token [cfg {...}]`, `refresh [cfg refresh-token]`, `claims [token public-key
now-s]`, `with-token [cfg token]`. **Doc: none in a user doc**; the network atom is described only in the design
corpus `doc/network-atoms/` (IMPL-PLAN, SCOPE, WIRE, ADAPTERS, phase reports) — d.

### eve.sorted, eve.deftype.int-map, eve.deftype.rb-tree, eve.rrb-vector

- `eve.sorted`: `sorted-map [& kvs]`, `sorted-map-by [cmp & kvs]`, `sorted-set [& ks]`, `sorted-set-by [cmp & ks]`,
  `subseq`, `rsubseq` (`[sc test k] [sc start-test start-k end-test end-k]`), `rank [sc k]`, `nth-entry [sc i]`,
  `find [sc k]`, `shape [sc]`, `sorted-coll? [x]`, `disj! [t x]`, `dissoc! [t k]`. Doc: — for `sorted-map`,
  `sorted-map-by`, `sorted-set-by`, `subseq`, `rsubseq`, `nth-entry`, `sorted-coll?` (in no document at all).
- `eve.deftype.int-map`: `int-map`, `merge`, `merge-with`, `update`, `range [m min-k max-k]`, `default-merge`. Doc: D
  (except `default-merge`).
- `eve.deftype.rb-tree`: `sorted-set`, `member?` (D); `rb-insert rb-member? rb-find rb-seq rb-count rb-height rb-delete
  rb-black-height rb-valid?` (node-level, —).
- `eve.rrb-vector`: `vector`, `vec`, `catvec`, `subvec` — d (data-structures.md#rrb-vector).

### eve.obj, eve.array, datasets, tensors

- `eve.obj` 22 public; — : `obj-from-offset`, `cas-in!`, `add-in!`, `sub-in!`, `exchange-in!`, `get-schema`,
  `get-offset`.
- `eve.array` 40 public; — : `eve-array-uninit`, `int32-array`, `int32-array-from`, `amap!`, `afill!`, `acopy!`,
  `from-double-array`, `from-int-array`, `from-byte-array`, `write-host-array!`, `array-values`, plus layout helpers.
- `eve.alpha.ds` (16), `eve.alpha.tensor` (17), `eve.alpha.col` (19): described in `doc/data-structures.md`; in no
  user-guide document (api-guide, README, getting-started). `dataset?`, `tensor?`, `contiguous?` in no document.

---

## 3. The three headline APIs as they exist in code

### 3a. Atom domains [read; ran]

- **A domain is a set of regions** (atom.cljc:1-17): slab regions for size classes 0-5 with a bitmap each, an overflow
  class 6, a root region (`.root`, 8320 bytes: header, 256 worker slots, 256 atom slots — data.cljc:294, :333) and a
  reader map (`.rmap`, 262,144 bytes; atom.cljc:52-53).
- **Files of a disk domain** [ran: bb, `(e/atom {:id ::counter :persistent "<dir>/db"} 0)`, then listing `<dir>`]:
  `db.root db.rmap db.slab0 … db.slab5`, `db.slab0.bm … db.slab5.bm`, `db.slab6` (alloc.cljc:834-862), and, for an
  encrypted domain only, `db.keyring` (keyring.cljc:368).
- **A member atom is a slot in one domain-state.** Slot 0 of the root's atom table is the domain's **registry**
  (`{"ns/name" slot-idx}`), slot 1… the member atoms (atom.cljc:63 `DEFAULT_ATOM_SLOT 1 ;; slot 0 = registry`).
  `MmapAtom` is `[domain-state atom-slot-idx]` (atom.cljc:759); `MmapAtomDomain` is `[domain-state registry-cache]`
  whose `deref`/`swap`/`reset` act on slot 0 (atom.cljc:791-815) — **the domain is itself an atom**, and `(:kw domain)`
  (ILookup) or `domain-lookup` answers the member atom named `kw` (atom.cljc:803-826). Creating a member claims a free
  slot by CAS, writes its value, then registers its name with one swap of slot 0, so of two processes creating one name
  exactly one wins and the loser frees its slot (claim-atom-slot! :895, register-name! :912, create-mmap-atom! :931).
- **Constructors and the config keys they read** (every key, by grep of `opts`/`config` reads):
  - `eve.atom/atom [& args]`: `:id` (a namespaced keyword; anonymous → `"__anon__/<n>"`, a per-process counter,
    atom.cljc:1080-1084), `:persistent` (atom.cljc:1119). In-process: a heap domain **per `:id`**, cached
    (atom.cljc:1101-1108).
  - `persistent-atom [& args]`: `:id`, `:persistent` (string path, or any truthy non-path → the default domain
    `./eve/`), `:lustre?` (atom.cljc:1066-1076).
  - `persistent-atom-domain [base-path & {:keys [capacities lustre?]}]` (atom.cljc:834-850); its creator is decided by
    `create-exclusive!` of `<base>.root`, never exists-then-create (atom.cljc:839-847).
  - `atom-domain [initial-value & {:as _opts}]`: **reads no option** (atom.cljc:1159-1172).
  - `open-mmap-domain!` (private) passes `:capacities`, `:lustre?`, `:create?` to `alloc/mmap-domain-sio!`
    (atom.cljc:342-363).
- **Reads**: `@a` is `d/eve->clj` — the value **materialized** into the host's own collections under a pin
  (atom.cljc:632-645). The lazy, slab-backed value is reached only through `eve.atom/$` (atom.cljc:734-748) or inside
  `swap!`'s `f` (atom.cljc:647-651: "the old value is lazy"). [ran, JVM] after `(swap! s (fn [_] {:ints (im/int-map 1 :a
  3 :c) :tags (rb/sorted-set "b" "a") :sm (sorted/sorted-map 2 :b 1 :a)}))`: `(type @s)` →
  `clojure.lang.PersistentArrayMap`; `(:ints @s)` → `PersistentArrayMap`; `(:tags @s)` → `PersistentTreeSet`;
  `(:sm @s)` → `eve.sorted.EveSortedMap`; `(ea/$ s #(type (:ints %)))` → `PersistentIntMap`.
- **Meta-atoms** (atom.cljc:966-998): `(meta-atom a)` is the member atom registered as `"eve.meta/" + a's name`, made on
  first ask; its traffic never touches a's slot. [ran, bb] `(ea/meta-atom p {:note "beside"})` → `{:note "beside"}`,
  `(ea/registered-name p)` → `"user/counter"`.
- **Cross-process** [ran]: two bb processes × 200 swaps of one `:persistent` atom → `{:n 400}`; one JVM process and one
  bb process × 200 each, interleaved (bb finished seeing `{:n 349}`) → `{:n 400}`.

### 3b. Network atoms [read; ran]

- **Created** by `(eve.net.atom/remote-atom cfg)` or `(remote-atom cfg platform)` (net/atom.cljc:45-55). The handle is
  a **plain map** `{:cfg :platform :memo :decoded :state}` — not an `IDeref`/`IAtom`: operations are
  `na/deref`, `na/swap!`, `na/reset!` (net/atom.cljc:284-373); `@ra` does not apply. `eve.thread.net/rung` wraps one as an
  `IRung` for eve.thread.
- **Config keys**: `{:endpoint :domain-id :atom-id :api-key}` (wire.cljc:1-6, every URL built at wire.cljc:11-13:
  `<endpoint>/v1/domains/<domain-id>/atoms/<atom-id>/…`, `Authorization: Bearer <api-key>`), plus `:gak` (a 32-byte group
  key) to encrypt blobs (blobs.cljc:8-27). The platform: `{:request :sleep :now-ms :seed}` (net/platform.cljc), by
  default the host's `eve.http` + `eve.process` adapters.
- **Semantics**: synchronous on every host — `deref` and `swap!` answer the value, never a promise (net/atom.cljc:26-28).
  The value **must be a map** (`:not-a-map` thrown, net/atom.cljc:327-329). `swap!` retries a 409 up to 50 times with
  jittered exponential backoff 10 ms × 2^n capped at 2 s; a 403 throws (net/atom.cljc:40-43, :358-371). Deref holds a
  lease (`release!`, `renew!`).
- **Blobs go direct**: every block GET/HEAD/PUT is to a URL the broker presigns (`wire/presign`, blobs.cljc:105-120);
  the broker/gateway exchanges are small JSON with the root blob base64 (wire.cljc:50-68). No `/blob/<sha>` route.
- **Gateway** (net/gateway.cljc:1-48, :264-285): any member of a disk domain serves its atoms on the same routes
  (`create`, `deref`, `cas-root`, `presign`, `release-reader`, `renew-lease`); a network CAS is **one eve swap** that
  compares content keys, so network writers are atomic against disk writers. Its `:presign` is a fn into an object
  store (`hmac-presigner` is the in-tree stand-in for S3 SigV4). Threat model in its docstring: the gateway sees
  everything; the store sees ciphertext under `:gak`.
- **What an example can run against without a network**: the gateway (src) over a disk domain, plus
  `test/eve/net/dev_store.cljc` (a presigned-URL object store, HMAC-checked) — both reached through a platform whose
  `:request` routes by URL prefix; the same wiring as `test/eve/thread_net_test.cljc:172-184`. A stand-in broker exists
  too: `test/eve/net/stand_in.cljc` (`(si/stand-in {:api-key "k"})`). Both stand-ins are under `test/`, so an example
  needs `test` on its classpath (or a copy). For a real socket, `eve.http-server/serve!` (JVM `http-server/jdk`, bb
  `http-server/httpkit`; Node `http-server/node` since `ba99b61cc`) can serve `(:handle g)`.

### 3c. Encryption — what is encrypted by default, and what is not [read; ran]

- **In-process atoms**: never encrypted.
- **Disk domains through the atom API: NOT encrypted, and there is no option to encrypt them.** `persistent-atom-domain`,
  `persistent-atom` and `atom` read no key or cipher option (§3a), and nothing in `src/` outside `eve.keyring` and the
  alloc seam calls `eve.keyring` [ran: `grep -rln eve.keyring src adapters` → `alloc.cljc`, `keyring.cljc`,
  `process.vs`, `process_slab.cljs`, and only tests/runners otherwise].
- **`eve.keyring` (at rest, library level)**: `(open-encrypted-domain! base-path {:create? :status :key :capacities})`
  → `[slab-io ring]` (keyring.cljc:377-397) — a slab I/O, not an atom domain. Default status 1 = **auto**: a fresh
  32-byte GAK from `eve.process/random-bytes`, **stored in the clear in `<base>.keyring`** beside the data
  (keyring.cljc:102-125); status 2 = user key, stored; 3 = user key, not stored (only its fingerprint). Cipher:
  AES-256-CTR under per-class keys HKDF-SHA256(GAK, domain salt, "eve.keyring"‖class), IV class‖block‖counter. Named
  limits in its docstring (keyring.cljc:13-19): **metadata stays plaintext** (slab headers, bitmaps, overflow
  descriptors, the root file with its CAS words and atom table); **no integrity**; a block rewritten in place **reuses
  its keystream** (CTR confidentiality against a single copy of the files only). Direct region access to an encrypted
  domain is refused (keyring.cljc:340-343).
- **Network atoms: encrypted only when `:gak` is in the config** (blobs.cljc:8-27: `seal` is identity without it).
  With it, every blob in the object store is AES-256-CTR under a per-block key HKDF(gak, the first 16 bytes of the
  blob's key) — deterministic: "equal plaintexts encrypt equally" (blobs.cljc:10). **Not encrypted even with `:gak`**:
  the **root blob sent to the broker** in every `cas-root` body (net/atom.cljc:344 `:root-blob blob`, wire.cljc:57
  `"root_blob"`) and answered by `deref` — a VSNP snapshot that carries **every top-level key and every top-level scalar
  value in plaintext** (snapshot.cljc:1-12: scalars inline in the kernel's serial form, collections as 256-byte content
  references). The broker therefore sees the top-level keys, scalar values, entry count (`root_count`) and the content
  keys of every block.

---

## 4. DIFF

### 4(i) Documented APIs that do not exist, or whose behaviour differs

| Where | Claim | At 112f6ced | Evidence |
|---|---|---|---|
| api-guide.md "e/atom-domain" | `(eve/atom-domain {...} :sab-size … :max-blocks …)` "Creates a standalone SharedArrayBuffer-backed AtomDomain" | options ignored; an in-process heap/module-slab domain on every host | atom.cljc:1159-1172 |
| api-guide.md "e/atom-domain" | `@my-domain` is the value given | `@(e/atom-domain {:counter 0 :name "world"})` → `{"__atom-domain__/root" 1, :name "world", :counter 0}` — the registry entry leaks into the domain's value (**defect**, §5.1) | [ran, bb] |
| api-guide.md "e/atom-domain", "Returns" | `add-watch`/`remove-watch` on a domain; atoms implement `IWatchable` (SAB) | no watch on `MmapAtom` or `MmapAtomDomain`: JVM `ClassCastException: eve.atom.MmapAtom cannot be cast to clojure.lang.IRef`; bb `SciType cannot be cast to clojure.lang.IRef` | atom.cljc:759-815; [ran] |
| api-guide.md "Diagnostics" | `(a/validate-storage-model! (.-s-atom-env my-atom) …)`, `(a/xray-replay!)` | `validate-storage-model!` exists only on the legacy CLJS `eve.shared-atom` (shared_atom.cljs:756), whose `s-atom-env` an `e/atom` (an `MmapAtom`) does not have; `xray-replay!` exists nowhere in src or adapters | grep [ran] |
| data-structures.md:606 | `(a/xray-replay!)` | does not exist | grep [ran] |
| getting-started.md:159 | `(eve/init-eve-on-worker! transfer)` | does not exist anywhere | grep [ran] |
| getting-started.md:197; api-guide.md int-map and rb-tree sections | `(e/atom {:index (im/int-map) …})`, `(def state (e/atom {:data (im/int-map)}))`, `(e/atom {:tags (rb/sorted-set)})` | **throws** "An Eve value allocated in a different slab domain cannot be put in this one; build it inside swap!" — a heap domain per atom id (atom.cljc:1101-1121); same for `eve.sorted` | [ran, JVM and bb] |
| api-guide.md int-map section | "eve.core's vocabulary everywhere (`e/assoc`, `e/dissoc`, …)" with `e` = `eve.alpha` | `eve.alpha` exports no `assoc`/`dissoc` (they are `eve.core`'s) | alpha.cljc:41-71 |
| api-guide.md "Returns", README "Zero-copy reads" | atoms read in place, "No deserialization into intermediate heap objects" | `@a` materializes (`d/eve->clj`) into host collections; only `$`/`swap!` read lazily | atom.cljc:632-645, :734 |
| README "In-process atoms" table | "Babashka — mmap files only (no in-process heap mode)" | bb has the heap mode: `(e/atom ::state {:count 0})` works | [ran, bb] |
| README table | "JVM — `byte[]` + `sun.misc.Unsafe` atomics" | `adapters/region/jvm-varhandle`: ByteBuffer + VarHandle, "no sun.misc.Unsafe" | region.clj:4, :41 |
| README "Features" | "Exabyte-scale durable atoms … grow lazily from kilobytes to terabytes" | addressable 2^29 blocks a class (class 5: 512 GiB); default maximum 1 GB…256 MB a class (`data.cljc:116-122`, raised per domain by `:capacities`); overflow data capped at 4 PiB, 16,384 overflow descriptors by default; 256 atoms and 256 participants per domain | alloc.cljc:61; data.cljc:116-122; coalesc.cljc:50, :70-72; data.cljc:294, :333 |
| README "Four platforms, one format" | four platforms | eleven hosts share the format (`hosts.edn`: jvm cljs browser bb nbb cljr lpy jolt clje vs glj — 11 keys) | hosts.edn |
| README "Usage" | `eve/eve {:git/sha "0e084fff"}`, npm `eve-native` from github | a standalone-repo sha; the vendored tree is ahead of every public artifact | README.md |
| persistent-atoms.md "File Layout" | `.slab0-6`, `.root`, `.rmap` | also `.slab0.bm … .slab5.bm` (bitmaps), and `.keyring` for an encrypted domain | [ran]; alloc.cljc:834-862 |
| CLAUDE.md Forbidden-pattern 1 | "The cross-process atom files are: .slab0–.slab5, .root, .rmap" | plus `.slab6`, the six `.bm`, `.keyring` | same |
| api-guide/getting-started "anonymous atom" | `:id` "Required for persistent atoms" | not required — an anonymous persistent atom is `"__anon__/<n>"` from a per-process counter, so two processes' first anonymous atoms in one domain are the **same** atom | atom.cljc:1078-1084 |

### 4(ii) Public user API the docs miss

- **Whole subsystems with no user document**: `eve.thread` (+ `.fold`, `.domain`, `.rung`, `.net`) — 0 of ~130 public
  names in any user doc; `eve.net.atom`, `eve.net.gateway`, `eve.net.auth` — only in the design corpus
  `doc/network-atoms/`; `eve.keyring` — 18 of 20 fns in no document; `eve.sorted` — 7 of 13 in no document.
- **eve.atom**: `domain-lookup`, `join-atom-domain`, `lookup-or-create-mmap-atom!`, `meta-atom`, `registered-name`,
  `version`, `seal`, `$`, `atom-sio`, `compare-and-set!*`, `close!`, `global-domain`.
- **eve.array**: `eve-array-uninit`, `int32-array(-from)`, `amap!`, `afill!`, `acopy!`, `from-double-array`,
  `from-int-array`, `from-byte-array`, `write-host-array!`, `array-values`.
- **eve.obj**: `obj-from-offset`, `cas-in!`, `add-in!`, `sub-in!`, `exchange-in!`, `get-schema`, `get-offset`.
- **eve.deftype**: `eve-extend-type`, `extend-host-vectors`, and the host-side `deftype` (api-guide documents only the
  `eve.alpha/deftype` wrapper).
- **eve.http-server/serve!**, **eve.crypto** (8 fns), **eve.net.platform/platform**.
- **Docstrings**: the `api-defs` exports of `eve.alpha` (23) and `eve.thread` (40) carry no arglists or docstrings at the
  REPL — a release-quality gap even where a document covers them.

---

## 5. Defects found while taking the census (not fixed by this read-only pass)

1. **`atom-domain` leaks its registry into its value** [ran, bb]: `@(e/atom-domain {:counter 0 :name "world"})` →
  `{"__atom-domain__/root" 1, :name "world", :counter 0}`, and the entry survives swaps. Cause: `atom-domain` writes
  `initial-value` into slot 0 — the registry (atom.cljc:1166) — then `create-atom-domain!` registers
  `"__atom-domain__/root"` into that same map (atom.cljc:1153-1157; register-name! at :912).
2. **The network atom fails on babashka at base `112f6ced2`** (fixed in `007d4edc8`, §8) [ran]: through a gateway,
  `(na/deref ra)` throws `Can't dynamically bind non-dynamic var #'eve.net.codec/*memo*` (gateway.cljc:162 `binding`).
  Cause: `eve.net.codec`'s `coll-rec` (codec.cljc:474-498) has `(def ^:dynamic *memo* …)`, `(def ^:dynamic *decoded* …)`,
  `(def ^:private MEMO-MAX …)` and
  `(defn- remember! …)` **inside its body**, between its arglist and its `cond` — introduced by `a05771966` (ET-3 first
  cut, 2026-10-19 8:35 PM ET), lines blamed to it. The JVM tolerates it (its compiler interns and marks the vars dynamic
  at analysis), so the JVM run is green; sci does not; every call to `coll-rec` also re-runs the four defs (resetting
  their roots). The same JVM-green/bb-red pattern likely affects any host whose analyzer does not intern nested `def`s;
  `bb test:bb`'s last recorded green (224/3081, 2026-10-19) predates the commit.
3. **`eve.deftype.int-map/range` on a value read through `eve.atom/$`** [ran, JVM]: `(ea/$ s (fn [v] (into {} (im/range
  (:ints v) 0 5))))` → `NullPointerException` at int_map.cljc:209 (`load-node`); the same call inside `swap!`, or on an
  int-map outside any atom, works. `range` builds a new `PersistentIntMap` without the source's slab I/O.
4. **`swap!` returns the lazy, slab-backed new value** [ran, JVM]: `(swap! s (fn [v] (assoc v :r (im/range (:ints v) 0
  5))))` returned `{:ints #eve/IntMap{…} :r #eve/IntMap{…}}` — eve types, after the pin is released; `mmap-deref`'s own
  docstring says "Slab-backed wrappers must not escape" (atom.cljc:633-634).
5. **babashka `swap!` on an eve atom with more than ~8 extra arguments** [ran]: `(swap! s update :ints assoc 3 :c 1 :a
  -10 :neg)` → "Cannot call with 10 arguments" (sci's IAtom dispatch); a closure works. A host limit worth one line in
  the bb notes.

---

## 6. Verified "how to call it" snippets (for the README rewrite)

All on the tree at 112f6ced. bb: `bb -cp "src:<hosts.edn :bb roots joined by :>" file.clj` (≈0.9 s per run). JVM:
`java -cp "$(clojure -Spath -M:jvm-test)" clojure.main file.clj` (≈7-8 s, ~6 s of it loading eve).

```clojure
;; in-process atom [ran: bb, JVM] — eve.atom/atom (atom.cljc:1111), via eve.alpha (alpha.cljc:41)
(require '[eve.alpha :as e])
(def a (e/atom ::state {:count 0}))
(swap! a update :count inc)
@a                                   ;=> {:count 1}

;; disk domain atom [ran: bb] — persistent-atom (atom.cljc:1086), persistent-atom-domain (:834), domain-lookup (:817)
(def p (e/atom {:id ::counter :persistent "/tmp/eve-demo/db"} 0))
(swap! p inc)                        ;=> 1
(require '[eve.atom :as ea])
(def d (ea/persistent-atom-domain "/tmp/eve-demo/db"))   ; the same domain (cached by path)
@(ea/domain-lookup d ::counter)      ;=> 1
@(ea/meta-atom p {:note "beside"})   ;=> {:note "beside"}       (atom.cljc:988)
(ea/close-atom-domain! d)

;; two processes, one domain [ran: bb+bb, JVM+bb] — each process runs:
(def c (e/atom {:id :demo/counter :persistent path} {:n 0}))
(dotimes [_ 200] (swap! c update :n inc))
;; afterwards, any process: @c => {:n 400}

;; eve collections inside an atom [ran: JVM] — build them INSIDE swap! (a value built outside belongs to another domain)
(require '[eve.deftype.int-map :as im] '[eve.deftype.rb-tree :as rb] '[eve.sorted :as sorted])
(def s (e/atom ::colls {}))
(swap! s (fn [_] {:ints (im/int-map) :tags (rb/sorted-set)}))
(swap! s update :ints assoc 3 :c 1 :a -10 :neg)
(swap! s update :tags conj "python" "clojure")
(swap! s (fn [v] (assoc v :lo (im/range (:ints v) 0 5))))
(:lo @s)                             ;=> {1 :a, 3 :c}      (@ materializes: a plain map)
(ea/$ s (fn [v] (rb/member? (:tags v) "clojure")))   ;=> true  ($ reads the lazy value)
(into {} (im/merge-with + (im/int-map 1 10 2 20) (im/int-map 1 1 3 3)))  ;=> {1 11, 2 20, 3 3}
(seq (sorted/sorted-map 3 :c 1 :a 2 :b))             ;=> ([1 :a] [2 :b] [3 :c])
;; on babashka eve values answer eve.core, not clojure.core: (ec/assoc m k v), (ec/seq m) — and a registered
;; collection reads back as bb's own (an int-map as a map, an rb-tree set as a sorted set) [ran]

;; threads [ran: bb, JVM] — eve.thread api-defs (thread.cljc), eve.thread.fold/fold
(require '[eve.thread :as t] '[eve.thread.fold :as tf])
@(t/future (+ 1 2))                  ;=> 3
(doall (t/pmap inc [1 2 3]))         ;=> (2 3 4)
(tf/fold + (vec (range 1000)))       ;=> 499500
(t/shutdown-default-pools!)

;; network atom, in one process, no sockets [ran at base: JVM green; bb red — §5.2, since fixed]
;; — remote-atom (net/atom.cljc:45),
;; gateway (net/gateway.cljc:264), hmac-presigner (:63), dev-store (test/eve/net/dev_store.cljc:44; needs `test` on the path)
(require '[clojure.string :as str] '[eve.net.atom :as na] '[eve.net.gateway :as gw]
         '[eve.net.dev-store :as dstore] '[eve.process :as proc])
(def d (ea/persistent-atom-domain "/tmp/eve-net/db"))
(def counter (ea/lookup-or-create-mmap-atom! d "app/counter" {:n 0}))
(def store (dstore/dev-store {:url "http://store.test/blobs/" :secret "s" :now-ms proc/now-ms}))
(def g (gw/gateway d {:endpoint "http://gw.test" :domain-id "d1" :api-key "k"
                      :presign (gw/hmac-presigner {:url "http://store.test/blobs/" :secret "s" :now-ms proc/now-ms})
                      :platform {:request (:request store)}}))
(def platform {:request (fn [req] (if (str/starts-with? (:url req) "http://gw.test") ((:handle g) req) ((:request store) req)))
               :sleep proc/sleep-ms! :now-ms proc/now-ms :seed 1})
(def ra (na/remote-atom {:endpoint "http://gw.test" :domain-id "d1" :atom-id (gw/atom-id "app/counter") :api-key "k"} platform))
(na/deref ra)                        ;=> {:n 0}
(na/swap! ra update :n inc)          ;=> {:n 1}
(swap! counter update :n inc)        ; a disk writer of the same atom
(na/deref ra)                        ;=> {:n 2}   — @counter also {:n 2}
(count @(:blobs store))              ;=> 6   (blobs went to the store; gateway stats {:requests 12, :commits 1, :conflicts 0})
```

Not run here: nbb, ClojureScript (shadow), ClojureCLR, basilisp, jolt, clojerl, glojure, ValueScript (no runtime
installed but bb/JVM; out of this fork's bounds).

---

## 7. Impls audit — where Clojure-like implementations live, and what each `impls/` folder is

### 7.1 Where Clojure-like implementations live (the model the tree actually has)

There are THREE places, and they are different things:

1. **A host = adapter roots over the shared `src/`.** A Clojure-like that can load eve's shared `.cljc` is a *host*:
   a row in `eve/hosts.edn` (jvm, cljs, browser, bb, nbb, cljr, lpy, jolt, clje, vs, glj — hosts.edn:5-67) listing
   its roots under `eve/adapters/<feature>/<impl>/src`, plus a test runner `eve/test/eve/runner/<host>_main.*`
   (bb_main.clj, nbb_main.cljs, cljr_main.clj, lpy_main.lpy, jolt_main.clj, clje_main.clje, glj_main.glj,
   vs_main.vs, eve_test_main.cljs, thread_browser_main.cljs — `ls test/eve/runner/` [ran]) and a `bb test:<host>`
   task (bb.edn:12-50). `src/` names no host: `.host-leak` = conditionals 0, predicates 0, host-files 1, net-interop 0
   (eve/.host-leak:3-6); `grep -rln '#?(' src` → nothing [ran]; the one host file is `src/eve/shared_atom.cljs`.
2. **Native code a host's adapters call**, kept beside the adapter or at eve's root:
   - `eve/native/mmap_cas.cc` — the Node N-API addon (binding.gyp:3-4), used by `adapters/region/js`
     (adapters/region/js/src/eve/region.cljs:140-184, loaded via `node-gyp-build`).
   - `eve/adapters/core/beam/native/eve_clje_nif.c` — clojerl's NIF (NOT `eve/native/`; `ls` [ran]).
   - `eve/adapters/completion/glojure/go/{host-field,record-factory,goroutine-id}.patch` — patches to glojure v0.7.22.
3. **`eve/impls/<lang>/`** — the native/host-side half a language needs *outside* the Clojure source: Go packages
   compiled into a custom binary (glojure, let-go), a babashka pod (bb), and pre-port probes for languages that do
   not yet run eve's shared code (jank, phel). Only `impls/glojure/` is wired to a host row (`:glj`).

So "an impl" of eve for a Clojure-like = its hosts.edn row (adapter roots) + any adapters it had to write
(`adapters/<feature>/<newimpl>/`) + a runner + a bb task + (only if the language must be rebuilt/extended natively)
an `impls/<lang>/` directory.

### 7.2 Per directory

| dir | files (lines) | supplies | shares / wired to | completeness | "supported" would still need |
|---|---|---|---|---|---|
| `impls/bb/eve_atomics_pod/` | go.mod (3), main.go (238) | a babashka pod (`pod.eve.atomics`, JSON format): `open` (grow-never-truncate, MAP_SHARED), `load32`, `store32`, `add32`, `cas32` (witness), `pid` (main.go:1-10, :135-175) — HOST-CONTRACT §1.2/§2.1 cross-process 32-bit atomics bb itself lacks | nothing loads it: `grep -rln 'pod.eve.atomics\|eve_atomics_pod\|eve-atomics-pod' src adapters test scripts bb.edn` → no match [ran]. bb takes part through the exclusive-hold protocol on `region/jvm-nio-lock` instead (hosts.edn:28-31; impls/README.md:41) | **builds** [ran]: `go build` 8.0 s, exit 0, 3,014,609-byte binary (go 1.24.7). No test, no bb task, no consumer | a region adapter that calls it (a lock-free bb region) + tests, or delete it as superseded |
| `impls/glojure/` | build.sh (48), cmd/glj-eve/main.go (21), go.mod (16), go.sum (29), gljimports/README (3), host/host.go (205), region/region.go (292), types/types.go (265) | `region/` — heap + MAP_SHARED file regions, 32/64-bit atomics (unaligned i32 plain), futex wait/notify, bulk bytes, fcntl lock (region.go:1-17, :39-200): HOST-CONTRACT §1-2. `host/` — UTF-8, array copies, LE floats, IEEE bits, goroutine id, identity hash, Clojure collection hashes (host.go:1-11, :39-191): §3-4 helpers for `encoding/go`, `process/go`. `types/` — `MapRecord`, the Go value an eve map type is (types.go:1-17). `build.sh` — copies glojure v0.7.22 from the module cache, applies the three completion patches, generates the package map, builds `glj-eve` (build.sh:1-48) | `:glj` row (hosts.edn:61-67) with `region/glojure`, `encoding/go`, `process/go`, `thread/glojure`, `types/glojure`, `completion/glojure`, `http/go`, `crypto/portable`, `int32/portable`; bb tasks `glj-eve` and `test:glj` (bb.edn:40-46); runner `test/eve/runner/glj_main.glj` | **a host**. `target/glj-eve/glj-eve` exists (29,056,878 bytes, stamp 2026-10-20 1:56 PM ET — built on this box during this session, not by this fork). CLAUDE.md reports `bb test:glj` 149 tests / 2403 pass / 0 fail (2026-10-20) — not re-run here. Typed arrays not on it (impls/README.md:15) | typed arrays (`eve.array`, dataset, tensor); a released glj-eve binary or documented build prerequisite (Go 1.24 + module cache + network on first build); CI |
| `impls/let-go/` | main.go (15), go.mod (28), go.sum (46), evenative/region.go (238), evenative/types.go (516), proposal-let-go.patch (90) | `eve.native` namespace in a custom `lg` (`lg-eve`): `load32`, `store32!`, `cas32!`, `add32!`, `open-fd`, `mmap`, `lock!`, `unlock!`, `pid` (region.go:82-232) — §1.2/§2.1 + fcntl; `types.go` — stand-in lazy types over a `[count][e0][e1]…` i32 layout, NOT eve's slab format, with a call log (types.go:3-11); the patch makes let-go's `swap!`/`swap-vals!`/`disj` reach a foreign `IAtom`/`IDisj` (proposal-let-go.patch:1-30) | no hosts.edn row, no runner, no bb task. Loads eve's shared code as written: `lg-eve -source-paths src:adapters/core/clojure/src:adapters/completion/clojure/src:adapters/int32/portable/src` ran `eve.hamt-util`: `(portable-hash-bytes (byte-array [1 2 3]))` → -2133732860, `(popcount32 255)` → 8 [ran] — identical on babashka with `int32/jvm` [ran] | **builds** [ran]: `GOFLAGS=-mod=mod go build` 34.2 s exit 0, 22,817,776 bytes; go.mod pins `go 1.27` / `toolchain go1.27.2`, which Go auto-downloaded (`~/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.27.2`). Natives answer [ran]: `cas32!` witness 0, `load32` 7, `add32!` old 7, `load32` 10 | a hosts.edn row (region adapter over `eve.native`, encoding/process/types/thread adapters), the upstream patch landed (or a completion), a runner + bb task, the shared suites green |
| `impls/jank/` | src/eve/region.jank (118), src/eve/types.jank (236) | region.jank — MAP_SHARED file region, atomics, futex, fcntl through `cpp/raw` (region.jank:1-30): §1.2-2; types.jank — five C++ object types over a calloc'd stand-in region with a call log, reads dispatch, writes not yet (types.jank:1-6; impls/README.md:39) | nothing; jank cannot load eve's shared `.cljc` ("fails", doc/network-atoms/ADAPTERS.md §4.3.1) | **not run here**: `jank` not installed (`command -v jank` → missing [ran]) | jank running the shared src (the blocker), then a full adapter set |
| `impls/phel/` | bootstrap.php (6), composer.json (10), src/EveTypes.php (510) | PHP classes implementing phel 0.34.1's collection interfaces over FFI int32 cells, duck-typed atom, call log (EveTypes.php:1-30) — stand-in layout, not eve's | nothing; phel cannot load the shared `.cljc` (ADAPTERS.md §4.3.1). **composer.json:5 `"license": "proprietary"`** — conflicts with eve's MIT LICENSE | **not run here**: `php` and `composer` installed, `phel` not (`command -v phel` → missing [ran]); bootstrap.php:6 defaults to `/opt/phel/vendor/autoload.php` unless `PHEL_AUTOLOAD` is set | phel running the shared src; fix the license field |

### 7.3 impls/README.md claims vs the tree (mismatches)

| impls/README.md | claim | fact |
|---|---|---|
| :9 | ClojureScript = "the `.cljs` files and the `:cljs` arms of `.cljc`" | src has no reader conditionals (`.host-leak:3`, grep [ran]); CLJS = `src/` + hosts.edn `:cljs` roots |
| :10 | JVM = "the `.clj` files and the `:clj` arms of `.cljc`" | no `.clj` in src (`find src -name '*.clj'` → none [ran]); JVM = `src/` + `:jvm` roots |
| :11 | ClojureCLR: "`:cljr` arms … `eve.mem`" regions | no arms; regions are `adapters/region/clr` (hosts.edn:37) |
| :12 | basilisp: "`:lpy` arms … and `src/eve/lpy_host.lpy`" | `src/eve/lpy_host.lpy` MISSING [ran]; the completion is `adapters/completion/basilisp/src/eve/host_completion.lpy` |
| :13 | jolt: "`:jolt` arms placed before `:clj`" | no arms; `adapters/region/jolt-ffi`, `completion/jolt` (hosts.edn:44-47) |
| :14 | clojerl: "`src/eve/clje_nif.clje`, and the NIF `native/eve_clje_nif.c`" | both MISSING at those paths [ran]; they are `adapters/core/beam/src/eve/clje_nif.clje` and `adapters/core/beam/native/eve_clje_nif.c` |
| :11-14 | "a writer in the coop capstone (`test/coop-atom`)" | ambiguous, not missing: `eve/test/coop-atom` is absent, atom.broker's `test/coop-atom` exists, and its scenario is `eve/test/coop/` [ran: `ls -d`; corrected by the integrator — the research pass checked only `eve/test/`] |
| :9-15 | "a host since 2026-10-16" etc. for each; "eve's typed arrays are not yet on it" | consistent with CLAUDE.md test notes; not re-measured here |
| :41 | bb pod: "`eve.atom` does not call it" | true — nothing in src/adapters/test/scripts references it [ran] |
| :43-46 | "Each folder builds or loads as committed (2026-10-16)" | pod and lg-eve build [ran]; glj-eve exists (built this session); jank/phel not verifiable here |

Adjacent (outside this directive, noted only): `adapters/README.md` repeats the slab, typed-view, int32 and codec rows
(adapters/README.md:48-68) with conflicting host lists, and lists `encoding/vs` twice under codec (:55, :57).

---

## 8. Integrator's verdicts on the defects (what this lane does with each)

| # | Defect | Verdict | Action |
|---|---|---|---|
| 5.1 | `@(e/atom-domain …)` carries `"__atom-domain__/root" 1` | real [ran by the census] | open item: `atom-domain` writes the value into slot 0, the registry; the fix (a domain's own value in a slot of its own, or the registry key filtered from deref) changes a domain's on-disk meaning — the eve.atom owner's call (RELEASE-CHECKLIST §9) |
| 5.2 | network atom: first gateway deref fails on babashka | real, order-dependent [ran] | **fixed in Phase 2** (the four forms back at top level); re-run alone and in the full suite |
| 5.3 | `im/range` on a `$` value throws | real [ran by the census] | open item (int-map owner): `range` builds its result without the source's slab I/O |
| 5.4 | `swap!` returns the lazy slab-backed value | real [ran by the census] | open item (eve.atom owner): against `mmap-deref`'s own rule; documented in `doc/api-guide.md` "Known limitations in 0.9" until fixed |
| 5.5 | bb `swap!` with more than ~8 extra arguments (measured exactly in the final pass, 2026-10-22 ET: six work, seven or more throw — `logs-2026-10-22/f-bb-swap-args.log`) | a host limit (sci's IAtom dispatch) | documented (`doc/hosts/babashka.md`; api-guide's Known limitations); not eve's |
| — | `(e/atom {:x (im/int-map)})` throws "different slab domain" | real; also against `contention-domains.md` ("Storing a foreign value into an atom copies it in") | open item (eve.atom owner): the README and examples build eve collections inside `swap!`, which works on every host |
| — | `eve.shared-atom` (3,500 lines) is required by nothing in the tree | fact [ran: grep over src, adapters, test, bench, shadow-cljs.edn] | operator decision: the file is protected by `eve/CLAUDE.md`; RELEASE-CHECKLIST §9.4 |
| — | the `api-defs` exports (`eve.alpha`'s 23, `eve.thread`'s 40) carry no docstring or arglist | real | open item: `api-defs` could copy each target var's `:doc` and `:arglists` onto the def |
