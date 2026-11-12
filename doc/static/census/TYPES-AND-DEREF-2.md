STATUS: PARTIAL — §1 (STM), §2 (threads) banked; REMAINING: §3 shared_atom.cljs, §4 tally / lost CAS / journal sweep, §5 journal adapter API, §6 Evidence.

# Census 2 — value-delivery paths the first pass did not reach

Continues `doc/static/census/TYPES-AND-DEREF.md` (its §3.1 `eve.atom`, §3.3 the 15 `IPlain` bypasses, §4.4 the
`swap-attempt!` line table are not repeated). Tree `/home/user/eve` at `67ab7da` = pin `4a92129` + `doc/static/**`
(ran: `git diff --stat 4a92129 HEAD` → 13 files, all `doc/static/`). Paths are relative to `/home/user/eve`.
Tags: **[code]** read from source, not executed · **[doc]** a doc or comment's claim, not re-derived · **[ran]** a
command run here, output quoted.

## 1. STM — `src/eve/stm.cljc` (971 lines, read whole), `doc/stm.md`

| # | Question | Answer | Where | Tag |
|---|---|---|---|---|
| S1 | What a ref is | A **host map** `{:eve.stm/kind ::ref :seq :cell :lock-cell :state}`; `:cell` is an Eve atom, `:state` a host atom (validator, watches, meta, min/max-history, lock) | `src/eve/stm.cljc:394-409` | code |
| S2 | Where the cell lives | Unseated: a fresh **heap domain per ref** — `(ea/atom-domain {})` then `lookup-or-create-mmap-atom! d "eve.stm/ref<n>" …`. Seated: a named atom of `ea/persistent-atom-domain` at `:domain-path`. Comment: "~6.8 MB eager heap and ~10^2 ms per domain on the JVM" | `:313-330`; `:305-309` | code / doc |
| S3 | What the value is stored as | The cell is one plain map **`{:value x :point 0 :version 0 :history []}`** handed to the atom's ordinary store path — so an Eve HAMT map; the user's value is a **nested value under `:value`**, never an atom root | `:326`, `:330`, `:770-777` | code |
| S3a | doc vs code | The ns docstring says the cell is `{:txid :val :touched :failures}` (`:14`); the code and `doc/stm.md:25-26` say `{:value :point :version :history}` (`:300-301`). Code wins | as cited | code |
| S4 | `deref` outside a transaction | `(:value (cell-deref r))`, `cell-deref` = `@(:cell r)` → the atom's `plain-deref` → **plain host data via `eve->clj`, the whole cell materialized — every retained history entry included — to hand back one field** | `:342`, `:589-593`; `src/eve/atom.cljc:918-931` (prior §3.1) | code |
| S5 | `deref` inside a transaction | `(view-of (touch! *tx* r))`: `touch!` does the same plain `cell-deref` once per ref per attempt, picks the entry as of the read point (`value-at`), and keeps **`:base-value` — plain host data — in the attempt's host atom**; `view-of` folds the attempt's own actions over it | `:411-421`, `:534-566`, `:590-591` | code |
| S6 | Ever a lazy slab value? | Only if the caller is itself inside `eve.alpha/$` (then `@(:cell r)` is `region-deref`, prior §3.1). `eve.stm` never opens a region, never calls `read-pinned` (grep in S11). Not exercised by anything read here | `:342` | code |
| S7 | History: stored as | `:history` = newest-first **vector of `{:point :value}` maps inside the same cell value**; each install pushes the cell's previous `{:point :value}` unconditionally (stock pushes only on fault / min-history) | `:300-301`, `:773-775` | code |
| S8 | History: bound | `trim-history`: keep `(max min-history max-history)` entries, defaults 0 / 10 → **10 old versions + the current one per ref** | `:407-408`, `:737-754` | code |
| S9 | History: sharing | **None.** `install!` builds `new-cell` from the *plain* `cur` and CASes it in with `swap!`; the store path writes a plain map whole. Every commit therefore **re-encodes the new value plus up to 10 old values**, and frees the previous cell's whole tree | `:764-778`, `:361-363`; prior §4.1 "a plain map → the registered direct writer" | code |
| S10 | Cost shape of one `alter` commit of one ref | ≥3 whole-cell materializations (`touch!` `:544`, validate `:816`, `install!` `:766`; +1 per validator `:825`), one deep `=` of the lazy current cell against the plain old one inside the swap (`:362`), one whole-cell write | as cited | code |
| S11 | Release of an old version, GC-less host | The cell atom's own swap: `swap-attempt!` → `tally/drop-root!` on the old root after the CAS → zero queue → `try-flush-retires!` → `tally/flush!`. A trimmed entry is simply absent from the new cell and dies with the old root. **Nothing in `eve.stm` calls tally / retire / free itself** | `src/eve/atom.cljc:1036`, `:1057`, `:1099`, `:890-898`, `:413-424`. ran: `grep -n 'read-pinned\|region\|alpha\|tally\|retire\|close-atom-domain\|free' src/eve/stm.cljc` → 2 hits, both the word "free" in the commit-lock docstrings (`:688`, `:707`) | code / ran |
| S12 | The one comment about GC-less hosts | `trim-history` rebuilds with `(vec (take keep h))` "never a subvec … on a host without a GC a slab tree pinned forever per ref. A trim must RELEASE, not merely unlist (the cohost lane's reclaim probe caught the subvec shape retaining exactly one trimmed version)" | `:743-753` | doc |
| S13 | What IS host-GC-dependent | (a) **The ref's domain itself**: no `close-atom-domain!` anywhere in `stm.cljc`; a dropped ref's heap domain is freed only when the host collects it (`atom-domain` also parks the newest domain in `last-atom-domain`). (b) Attempt records `{:id :rp :touched :held-sends :locks-held}` — five host atoms per attempt. (c) Every materialized cell copy and `:base-value` | `src/eve/stm.cljc:921-927`; `src/eve/atom.cljc:1357`, `:1659-1683` | code |
| S14 | `ref-set*` / `alter*` / `commute*` / `ensure*` with a value | Nothing is checked or converted at the call: `[:set v]` / `[:commute f args]` go into the attempt's host atom; `alter*` applies `f` to the plain view. The value first meets Eve at `install!`, **nested inside the plain `new-cell` map** | `:595-626`, `:568-572`, `:767-777` | code |
| S15 | …when `v` is plain data | Written whole by the map writer (S9) | — | code |
| S16 | …when `v` is a slab value of another domain | Every other ref / atom **is** another domain (S2). Nested, so the root-only `:foreign-policy :copy` does not apply; the codec's nested-foreign throw fires at commit (prior §4.3: `adapters/codec/byte-array/src/eve/codec.cljc:697-700`). The throw is not a retry signal → `run-in-transaction` rethrows (`:953`) | `:778`, `:837`, `:939-953` | code (not run) |
| S17 | Consequence of S16 | Installs are a `mapv` of one CAS per ref with **no storability check before the first**: validators run first (`:820-827`), then `claim-point!`, then installs in order. An install that throws at store time leaves the earlier installs of the same transaction **committed** (the `finally` only unlocks) | `:836-841` | code (not run) |
| S18 | In-flight replacement | "this release stores the versioned history in the OLD encoding. An accepted flat encoding exists … NOT in this release"; `doc/stm.md:177-180` names branch `lane-m/fix-for-release-d1`. **Not found** — `git branch -a` here lists only `main` and `claude/sweet-carson-6pt29d` | `src/eve/stm.cljc:105-110`; `doc/stm.md:171-191` | doc / ran |
| S19 | Consumers | `docs/stm-ants/**` (the ants demo, hosts bb / cljr / jvm / jank / lpy / glj / nbb / jolt), `test/eve/stm/portable.cljc`, `test/eve/atom_repro_scanner_test.clj` | ran: `grep -rln "eve\.stm"` | ran |

## 2. Threads — `src/eve/thread.cljc`, `src/eve/thread/*.cljc`, `adapters/thread/*`

`eve.thread` has **no value encoding of its own.** A unit of work is a plain-data *descriptor*
(`src/eve/thread/descriptor.cljc:59-73`: `{:id :state :work :retry :attempts …}`, later `:value` / `:error`,
`:104-113`); what carries it is one of three things: the host's mailbox (`eve.thread-host/send!`), an Eve atom
(a "domain cell"), or a network atom. `grep -n 'pr-str|read-string|edn/|json|codec|serialize|encode|decode|postMessage|transfer' src/eve/thread`
→ the only encoder hits are `task.cljc:77` (`pr-str` of a form, for its **hash**) and `server.cljc:55` (a JSON health
page) **[ran]**.

### 2.1 What may travel — decided before any transport (`src/eve/thread/task.cljc`)

| Fact | Where | Tag |
|---|---|---|
| Same heap: work is a closure + the binding frame (`closure-work`) — nothing is encoded | `task.cljc:158-162`; chosen by `(h/ships-closures?)`, `src/eve/thread/pool.cljc:61`, `:349-350` | code |
| Cross heap: work is `{:task sym :hash h :args [...] :conveyed {...}}` or `{:form :fargs :args}` (`data-work`) | `task.cljc:169-190` | code |
| Refused: a fn anywhere in args / conveyed values (`data?`, `:149-156`) → `:host-object`; a plain closure as the work → `:closure` (`:164-167`); a form where the pool or the runner disallows → `:form` / `:eval` (`:182-186`) | as cited | code |
| Code identity: FNV-1a over a canonical printing of the form (sorted maps/sets, UTF-16 units) — skew is a loud miss | `task.cljc:65-86`, `:93-105` | code |

### 2.2 The transports

| # | Transport | Hosts | Encoding of a value | Encode / decode | Size limit | Refused |
|---|---|---|---|---|---|---|
| T1 | Mailbox, shared heap | JVM / babashka / jolt (`LinkedBlockingQueue`), clojerl, basilisp, ClojureCLR, glojure, jank | **none — the object reference** (BEAM: the term, copied by the VM); closures ship | `adapters/thread/jvm/src/eve/thread_host.clj:53-57`, `:82-85`; `ships-closures? true` in `beam:11`, `clr:25`, `glojure:28`, `jank:91`, `python:26`, `jvm:32` | none | nothing |
| T2 | Mailbox, ValueScript mesh | VS (N wasm instances, one shared memory) | **the value itself in the shared slab**: the mailbox is a VS atom holding a vector, `(swap! (:queue mb) conj msg)` + one futex word; "a message is the value itself, closures included" | `adapters/thread/vs/src/eve/thread_host.vs:11-17`, `:89-95`, `:97-103` | none | nothing |
| T3 | Mailbox, Node workers | ClojureScript on Node, nbb | **EDN text**: `(.postMessage p (pr-str msg))` — a JS string through the port — read back with `cljs.reader/read-string` | `adapters/thread/node/src/eve/thread_host.cljs:131-145`, `:111-113`, `:120-125` | none stated | `data?` (`:98-102`): nil, boolean, number, string, keyword, symbol, uuid, inst, non-record collections of those. A fn, atom, **record**, typed array, **any eve slab value that is not a host `coll?`** → `:host-object` (`:138-140`) |
| T4 | Mailbox, browser workers | compiled ClojureScript | **EDN text → UTF-8** in a per-thread ring of one SharedArrayBuffer: `[len u32 LE][utf-8]`, padded to 4 | `adapters/thread/browser/src/eve/thread_host.cljs:129-149` (`put!`), `:151-164` (`take!` → `read-string`), `:166-177` | **a message over 262,144 bytes throws** (`RING (* 256 1024)`, `:38`, `:135-136`); a full ring throws (`:142-144`); 128 thread ids for a page's life, ~33 MB buffer (`:31-36`) | same `data?` (`:96-100`) |
| T5 | Domain cell — `:heap` / `:disk` / `:window` rung | every host with regions | **Eve's own store path.** The descriptor map (work, args, result) is `swap!`ed into the **meta-atom of a rendezvous atom** (`-swap!` = `eve.host/swap!` on the atom) and read back under `ea/read-pinned`, **materialized with `ed/eve->clj`** before the pin drops ("eve values do not outlive it") | `src/eve/thread/rung.cljc:44`, `:95-104`; `src/eve/thread/domain.cljc:167-189` (`swap-meta!`, `:180`), `:201-204`, `:552`, `:942-943`, `:970-973`, `:1070`, `:1190`; submit builds `data-work` at `:705`, `:1048`; runs it at `:599` | the atom store's own (prior §4.6) | whatever `data?` and the store path refuse |
| T6 | Domain cell — `:net` rung | hosts with an http adapter | `eve.net.atom` swap / deref — `eve.net.codec` ExtCID v3 block images (census `BYTE-PATHS.md` §4.1–4.3, not redone). "maps, vectors, sets, scalars, keywords, symbols, strings (a list of scalars only) — so a pool on this rung carries registered tasks by name, not forms" | `src/eve/thread/net.cljc:15-17`, `:41-51` | eve.net's | a form (a list of symbols) throws at submission |
| T7 | Doorbells (disk / window rung) | — | no value: 1,024 i32 words in `<path>.bell`, bumped and notified after a commit | `rung.cljc:83-91`, `:151-191` | 4,096 B | — |

What actually goes through the mailbox in the routed (isolate) form — `{:eve.thread/op :task :id :token :home :work}`
out (`src/eve/thread/cell.cljc:321-332`), `{:eve.thread/op :result :id :token :ok :value|:error}` back (`:354-356`);
a result that is not data is replaced by an `eve.thread/host-object` failure (`reply!`, `:358-365`). A thread's boot
data is EDN text too (`:boot (pr-str …)`, `node:247`, `browser:298`).

**Costs stated anywhere [doc]:** only the BEAM binding frame — "20 vars, 3,101 bytes, and a round trip carrying it
measured 6.5 µs" (`doc/unification/HOST-CONTRACT.md:471-474`). **Not found:** any measured cost of the EDN mailbox
(T3 / T4) or of a domain-cell submit — looked in `doc/unification/HOST-CONTRACT.md` §11 (grep `send!|EDN|measured`),
`doc/performance.md`, `doc/unification/*` (no thread plan file is in `doc/`; the "EVE-THREAD plan P§…" the sources
cite is not in this tree). `read-timed` keeps a running mean of a meta-atom read in ms (`domain.cljc:191-199`) — an
instrument, not a published number. Doc vs code: the §11.2 adapter table (`HOST-CONTRACT.md:459-469`) has no
`thread/jank` row; the adapter exists (`adapters/thread/jank/src/eve/thread_host.jank`, 187 lines).

## 3. The legacy ClojureScript atom — `src/eve/shared_atom.cljs` (3,543 lines; grepped, three ranges read)

**Wired in: no.** ran: `grep -rnE '\[eve\.shared-atom( |\])'` over every `.clj/.cljc/.cljs/.edn` → **0 hits**; the
name appears only in comments (`src/eve/mem.cljc:14`, `src/eve/deftype_proto/data.cljc:560`, `src/eve/atom.cljc:1686`,
`adapters/region/js/src/eve/sab.cljs:4`, `:19`, `test/eve/merkle_phase5_test.cljs:4`) and docs; not in `hosts.edn`,
`shadow-cljs.edn`, `deps.edn`. [doc] `CHANGELOG.md:198-200`: "The 0.1.0 SharedArrayBuffer atom (`eve.shared-atom`) is
no longer what `e/atom` returns; nothing in the tree requires it"; `README.md:151`: the one host-only file, "counted
in `.host-leak`". It requires `eve.atom`, `eve.codec`, `eve.process-slab`, `eve.wasm-mem` (`:1-12`) — it compiles
against the live tree but no build reaches it.

| Path | What the caller gets | Via `IPlain`? | Where |
|---|---|---|---|
| `eve->cljs` — its **own materializer** | dispatch on `sd/ISabStorable` + `-sab-tag`: `:eve-map` → `{}`, `:eve-vec` → `[]`, `:hash-set` → `#{}`, `:eve-list` → a **vector**, `:eve/array` → **returned live**, unknown tag → as is; a SAB-backed typed array → an `ArrayBuffer` copy | **no** — a 16th bypass, in dead code | `:2342-2374`, `:2336-2340` |
| `AtomDomain` `-deref` | pin (`begin-read!`), `codec/deserialize-from-dv` of the root block, `eve->cljs`; **cached per process on `(env, root descriptor index)`** — an unchanged root answers the previous materialization without a walk | no | `:2939-2968` (cache `:2950-2952`, `:2962-2964`) |
| `SharedAtom` `-deref` | pin, up to 10 read retries, `eve->cljs`; no cache | no | `:3337-3365` |
| `-swap!` / `-reset!` return | the **materialized** new value (`eve->cljs` of the outcome) — unlike `eve.atom`, whose `swap!` returns `f`'s result raw (prior §3.3 #1) | no | `:2970-2994`, `:3367-3405` |
| local watches | a host atom of `{key fn}`; fired after the swap with **materialized old and new** — `AtomDomain`: two `eve->cljs` per swap (`:2972`, `:2978`); `SharedAtom`: a full `@this` *before* the swap plus `eve->cljs` after (`:3378-3381`) | no | `notify-watches` `:2376-2379`; `:2995-2998`, `:3406-3416` |
| cross-thread watches | the writer bumps and `Atomics.notify`s the header descriptor's `RETIRED_EPOCH` word as a version counter; a watcher's `Atomics.waitAsync` loop **re-derefs, deep-compares with its cached value (`not=`) and fires local watches**; fallback: a registered message broadcast | no | `:2381-2441`, `:2447-2465` |
| validator | `SharedAtom` `-reset!` only (`:3369-3370`) | — | as cited |
| printing | `#eve/shared-atom {:id … :idx …}` | — | `:3417-3419` |

What it is evidence of: watches, a validator and a root-keyed deref cache existed and were dropped with it —
`eve.atom`'s `MmapAtom` has none of the three (prior §3.1: `add-watch` → `ClassCastException`).

## 4. Reclamation facts an ingest path needs — `src/eve/tally.cljc` (574 lines, read whole)

### 4.1 The count

| Fact | Where | Tag |
|---|---|---|
| A block's count and flags are two i32 words of **its descriptor row**, out of band — `alloc/desc-of` answers `[[count-region off] [flags-region off] [pointer-region off]]`. Bitmap classes 0-5: row *i* is block *i*'s for the slab's life; class 6: the coalescing descriptor's row | `src/eve/tally.cljc:63-79`; `src/eve/deftype_proto/alloc.cljc:754-771`, `:1389-1398` | code |
| **"Counted" = flag `F_PUB` (bit 1) set.** Count 0 + PUB = parked; `CPERM` −1 held for ever; `CDEAD` −2 claimed by a flush; `F_QUEUED`, `F_LEAF`, `F_TAKEN` as named. "A fresh block's words are 0: the free that came before reset them" | `tally.cljc:10-17`, `:68-74` | code |
| `hold-one!` on an **uncounted** block: `(when (alloc/allocated? sio off) …)` CAS PUB (+LEAF when its references are not readable here) onto the flags, count +1 → `:new`. Not allocated → `nil`, "a dangling reference", **and `hold!` does not descend** | `tally.cljc:314-343` (`:335-341`) | code |
| `hold-one!` on a block **with no row** → `:norow`: "its references are held on every hold, and it is never freed here — a leak" | `:316-317`, `:343`, `:356` | code |
| `hold!` = a worklist walk: each block the hold makes counted has its references read **one level, by kind** (`refs`: map header → root node → children + slab pointers among values; set; vector header → trie + tail; list; value array; deftype fields; digest-cache side block) and held in turn. A block already counted costs one increment and is not descended | `:345-358`, `:260-295`, `:33-49` | code |
| **`hold-root!` reads no allocation log.** It is `(hold! sio ptr (root-kind sio ptr))` — reachability from the root over uncounted blocks; `root-kind` is the header's type byte | `:440-444`, `:106-119` | code |
| `drop!` is **not descended**: k→k−1; 1→0 parks `{:off :kind :epoch}` on the domain's zero queue (unless its earlier entry is still queued) | `:374-396`; `src/eve/atom.cljc:368-372` | code |
| `flush!`: entries below the safe epoch (min pinned reader, and a journal's floor) whose count is still 0 are claimed (0→CDEAD), their references read, the block freed (`slab/free!`), **then** each reference dropped — a child reaching 0 is parked at the epoch now. With no reader pinned it loops (≤ 4,096 rounds) so a dropped tree is freed whole | `tally.cljc:476-574`; `atom.cljc:413-424` | code |
| Not freed, ever: an `:opaque` block or an eve-deftype instance whose type is not loaded here and that was not held as a leaf — "its claimed count goes back to 0, uncounted" | `tally.cljc:478-483` | code |
| `free-unheld!`: of the offsets given, every block **without PUB** is `slab/free!`d | `:451-459` | code |
| `*touches*`: every offset whose row `hold!` / `drop!` / `store!` mutated, when bound | `:301-312`, `:333`, `:339`, `:386-387`, `:433` | code |
| `ctx-free!` resets the row (count 0, flags 0, digest pointer 0) **before** the bitmap bit clears, then `SLAB_HDR_FREE_COUNT` +1; `ctx-alloc!` is find-free → `bitmap-alloc-cas!` → cursor store → free count −1, **one block per call** — no run claim exists (`grep 'alloc-run\|claim-run'` → 0 hits) | `alloc.cljc:725-752`, `:533-606` | code / ran |

### 4.2 Who reads the attempt's allocation log (`alloc/logged-allocs`)

The log is a host set of unsigned offsets on the attempt's `LoggingSlabIO`: `-sio-alloc!` conj, `-sio-free!` disj
(`alloc.cljc:1077-1096`); "freed when the CAS loses, forgotten when it wins" (`:1069-1075`).

| Reader | What it does with the log | Where |
|---|---|---|
| lost CAS | `offs` = the log → `tally/drop-root!` the attempt's **new** root (reachable blocks go by the count: park → flush cascade) → `tally/free-unheld! sio offs` (**the unreachable rest**: superseded path copies, builder temporaries) | `src/eve/atom.cljc:1064-1071` |
| durable, no journal — pre-CAS force | `alloc/sync-touched! sio (touches ∪ unforced note ∪ log)` | `:1016-1019` |
| J1 journal commit | `journal-note-commit! ds (touches ∪ log)` → `journal-flush!` sweeps `(sweep ∪ unforced note)` inside the journal lock, appends, forces | `:1036-1039`, `:561-584`, `:516-527` |
| lost CAS, durable no journal | force `(touches ∪ offs)` | `:1082-1083` |
| a transient's scope | `free-owned!` frees only offsets in that scope's own log, and nothing once a piece was stored by pointer (`keep-owned!`) | `alloc.cljc:1441-1469` |
| won CAS | **nothing** — `stop-logging!` and return: an unreachable block the attempt allocated and did not itself free is forgotten | `atom.cljc:1062-1063`; `alloc.cljc:1479-1485` |
| a throw in `f` or in `resolve-new-ptr` | **nothing** — the attempt's only handler is `(finally (unpin-epoch! ds))`: no drop, no `free-unheld!` | `atom.cljc:982`, `:1085` |

### 4.3 The force sweep — which blocks reach disk

| Fact | Where |
|---|---|
| `sync-touched!` on a `JvmSlabCtx` forces **by class, not by block**: `(sync-jvm-touched-classes! sio (into #{} (map decode-class-idx) offsets))` — per class its data region, bitmap region and the three descriptor-column regions, whole. An offset is only a witness for its class | `alloc.cljc:1571-1585`, `:962-977` |
| Any other seat: the host slab's `:sync-touched!` if registered, else the whole-domain `sync-sio!`. The ClojureScript module slab registers `:sync-domain-sio!`, `:desc-of`, `:allocated?` and **no `:sync-touched!`** → whole-domain force | `alloc.cljc:1577-1585`; `adapters/slab/module/src/eve/process_slab.cljs:1363-1367` (ran: grep) |
| The zero-queue flush forces nothing; it notes one offset per class it freed in (`:unforced`), swept by the next commit's force | `atom.cljc:398-412`, `:423-424`, `:374-382` |
| J10 batch / checkpoint / `sync!` / close: whole domain | `atom.cljc:521-522`, `:580-584` |

### 4.4 What breaks when blocks enter a domain without `-sio-alloc!`

| Case | Consequence | Why |
|---|---|---|
| bitmap bit not set (class 0-5), or no ALLOCATED coalescing descriptor (class 6) | the publish hold answers `nil` at that block and **stops descending**: the block and everything under it stay uncounted while the root CAS publishes them; the allocator may hand the same block out again | `tally.cljc:335`, `:356`; `alloc.cljc:773-790` |
| row words not zero (stale count / flags) | PUB already set → treated as counted, +1, **not descended**; children never counted | `tally.cljc:322-334` |
| allocated + clean rows, reachable from the root, **not in the log** | correct on every path read here: the hold counts them by reachability, a lost CAS frees them through `drop-root!` + flush, and their classes are in `*touches*`, so the durable / J1 sweep forces them | §4.1, §4.3 |
| allocated, **not reachable** from the published root, not in the log | leaked: `free-unheld!` never sees them (lost CAS) and nothing else names them | `atom.cljc:1064-1071` |
| `SLAB_HDR_FREE_COUNT` / `SLAB_HDR_ALLOC_CURSOR` not maintained | a heap class is skipped as full only when its free count ≤ 0 (`alloc.cljc:565-566`); a stale count delays or brings forward growth | `alloc.cljc:565-566`, `:597-599`, `:751` |
| blocks placed past `total-blocks` of a class | `ctx-allocated?` answers false (`(< bi total-blocks)`) → the nil case above | `alloc.cljc:780-781` |
| a block whose layout `refs` cannot read (a packed blob that is not one of the kinds) | held as a leaf if its header byte says leaf (`0x01`, `0x1D`, `0x1E`) — one row, one count, one free; any other unknown byte is `:opaque`: held as a leaf and **never freed** | `tally.cljc:106-119`, `:478-483` |

## 5. The journal adapter API — `adapters/journal-file/java/src/eve/journal_file.clj` (202 lines, read whole)

Ten public functions; the handle `h` is the host's file object (JVM: a `FileChannel`), `bytes` the host's byte
carrier (JVM: a flipped `ByteBuffer`).

| fn | arity | What | java lines |
|---|---|---|---|
| `open-journal` | `[path]` | open (create) read+write; answers `h` | `:56-67` |
| `with-journal-lock` | `[h f]` | `(f)` under the file's exclusive OS lock and this process's monitor on `h` | `:69-94` |
| `journal-record-of` | `[epoch table]` | one record's bytes: `[magic "EVJ1" i32][len i32][epoch i32][table-len i32][table][crc32 i32]`, big-endian | `:96-112`, `:8-14` |
| `journal-record` | `[root-r]` | the record of `root-r`'s **current** epoch and atom table (`MAX_ATOM_SLOTS × ATOM_SLOT_SIZE` bytes) | `:114-118`, `:23` |
| `journal-scan` | `[h]` | `{:newest {:epoch :table} :oldest epoch :torn? bool}`; **reads the whole file into one buffer** (`ByteBuffer/allocate (int size)`); a CRC failure is skipped by its length, a framing failure ends the scan | `:120-166` (`:131`) |
| `journal-append!` | `[h bytes]` | write at the end; no force | `:168-174` |
| `journal-write-at!` | `[h bytes pos]` | write at an absolute offset | `:176-181` |
| `journal-truncate!` | `[h]` | to length 0; no force | `:183-187` |
| `journal-force!` | `[h full?]` | force; `full?` true includes metadata | `:189-194` |
| `journal-close!` | `[h]` | close; never throws | `:196-202` |

Other adapters — the same ten names in each (ran: `grep -n '^(defn ' adapters/journal-file/*/src/eve/journal_file.*`);
arities read only in `java` and `unsupported`:

| Adapter | File (lines) | Note |
|---|---|---|
| node | `adapters/journal-file/node/src/eve/journal_file.cljs` (168) | own CRC table (`:23`) |
| clr | `adapters/journal-file/clr/src/eve/journal_file.cljr` (182) | `FileStream.Lock` over `lock-length` (`:30`); own CRC table |
| beam | `adapters/journal-file/beam/src/eve/journal_file.clje` (198) | — |
| glojure | `adapters/journal-file/glojure/src/eve/journal_file.glj` (224) | own CRC table (`:58`) |
| jank | `adapters/journal-file/jank/src/eve/journal_file.jank` (250) | — |
| python | `adapters/journal-file/python/src/eve/journal_file.lpy` (161) | — |
| jolt | `adapters/journal-file/jolt/src/eve/journal_file.clj` (307) | raw `open` / `lockf(2)` constants (`:44-49`); own CRC table |
| unsupported | `adapters/journal-file/unsupported/src/eve/journal_file.cljc` (22) | every fn throws; `journal-close!` answers nil (`:13-22`) |

Bearing on a static form: the API has **no value-bearing call** — the only record constructor takes an epoch and a
table. `journal-append!` / `journal-write-at!` are byte-agnostic, but `journal-scan` knows one record shape and loads
the file whole (an `int` size on the JVM).

