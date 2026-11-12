STATUS: PARTIAL (bank 2, 2026-11-11 5:15 PM ET) — Part 1 (types) COMPLETE and probed on the JVM. Part 2 complete for
`eve.atom`; REMAINING there: `shared_atom.cljs`, STM, `eve.thread.*`, network atoms, the classified list of other
`-plain` callers. Part 3 complete for the store entry, codecs, allocation log, journal sequence, sizes; REMAINING there:
`eve.tally` / `eve.retire` internals, the `eve.journal-file` adapter API, `coalesc.cljc` limits. "Evidence for the seams"
is drafted from what is verified so far and will be finalised last.

# Census — persistent types, materialization / deref paths, the store path

Tree: `/home/user/eve`, working tree at `f605cd8` = pin `4a92129` + `doc/static/STATUS.md` only
(`git diff --stat 4a92129 HEAD` → 1 file). Every `path:line` is relative to `/home/user/eve`, read at this tree.

Provenance tags:

- **[read]** — read from source at this pin; not executed.
- **[ran-JVM]** — executed in this session on the JVM seat (OpenJDK 21.0.12, Clojure 1.12.0; classpath = `deps.edn`
  `:paths` + `test`; heap domains only; no file of the repo written). Two scripts, 4.2 s and 7.3 s wall clock; their
  output lines are quoted where used.
- **[doc]** — a claim of the repo's own docs, cited, not re-derived.
- **not found** — searched, with where.

No ClojureScript, babashka, nbb, CLR, basilisp, jolt, clojerl, glojure, jank or VS runtime was executed (no `bb` /
`clojure` CLI on the box). Every statement about those hosts is **[read]**.

---

## 0. Corrections to the brief's premises

| # | Premise | What the tree says | Where |
|---|---|---|---|
| C1 | `src/eve/guard.clj` is a construction law / guard | `eve.guard` is a **read** guard, a prototype ("jvm-heap-grade step 2"): inside `with-guard` a deref answers a lazy proxy; a use after the extent closes throws. It guards nothing about construction. JVM only; jank ships a no-op stand-in. | `src/eve/guard.clj:1-21`, `:36-39`; `src/eve/atom.cljc:29-31`, `:933-962`; `adapters/completion/jank/src/eve/guard.jank:1-47` |
| C2 | constructing outside a `swap!` may throw | **No constructor throws for being outside a swap.** Every constructor given no sio allocates in `alloc/current-sio` = `(or d/*domain-sio* (default-sio))`. The refusal is at **store** time. [ran-JVM] A1–A10. | `src/eve/deftype_proto/alloc.cljc:1713-1731`; §2.5, §4.3 |
| C3 | "the deftype constructor guard" (`data.cljc:557-560`) | **Stale comment — no such guard exists.** The generated constructors read only `alloc/current-sio` (`src/eve/deftype_proto/sio.cljc:265-277`; `adapters/types/js/src/eve/types_js.cljc:453-479`). The only readers of `d/*parent-atom*` in `src/` + `adapters/` are the ClojureScript typed-array encoder's `has-slab?` (a **fallback** to an inline blob, `adapters/slab/module/src/eve/process_slab.cljs:1405-1410`, `:1430-1439`) and the ClojureScript typed-array decoder (in a swap: a live SAB view; outside: a copy — `adapters/codec/js/src/eve/codec.cljs:1048`, `:1060`, `:1313`). Grep for `guard`, "outside", "must be … inside" over `src` + `adapters` finds nothing else. | as cited |
| C4 | `overflow_test` is about size limits | `test/eve/overflow_test.cljc` pins **arithmetic** overflow (`+ - *` on i64). Size tests: `test/eve/big_value_test.cljc`, `test/eve/tier_repair_round2_test.clj`. | `test/eve/overflow_test.cljc:1-6` |
| C5 | `VALUE_INLINE_MAX` 64 B bounds an inline value | The constant and `encode-val-out-of-line` exist, but **nothing in `src/` calls it** (only `test/eve/big_value_test.cljc:51-66`). `encode-val` writes every map value inline whatever its size. [ran-JVM] G7: a 100,000-char string nested in a map round-trips. | `src/eve/deftype_proto/serialize.cljc:54-58`; `adapters/codec/byte-array/src/eve/codec.cljc:757-776`; `adapters/codec/js/src/eve/codec.cljs:1408-1436` |
| C6 | records (tag `0x1A`) are a type of every host | `0x1A` is written and read by the **ClojureScript codec only**. On the byte-array codec a record is `map?` and is written as a plain eve map. [ran-JVM] H1/H2: a `defrecord` nested or at the root derefs as `clojure.lang.PersistentArrayMap`. | `adapters/codec/js/src/eve/codec.cljs:116-141`, `:1011-1021`, `:1284-1293`; `adapters/codec/byte-array/src/eve/codec.cljc:711-714`, `:323-328` |
| C7 | docs: a write plans `eve.retire/plan`, applies `retire/apply!` (`doc/architecture.md:181-187`, `doc/memory-model.md:125`) | `swap-attempt!` at this pin calls neither: it holds the new root (`tally/hold-root!`), CASes, drops the old (`tally/drop-root!`). `eve.retire` still serves `seal` (`retire/reach`). (Completed in §4 when `tally.cljc` / `retire.cljc` are read.) | `src/eve/atom.cljc:964-1085`, `:1542`; `src/eve/deftype_proto/data.cljc:435-438` |
| C8 | docs: "an RRB vector … reads back as the eve type itself" (`doc/data-structures.md:272-276`) | On the JVM a deref of an atom holding an RRB vector answers a **`clojure.lang.PersistentVector`** [ran-JVM] B1 — rebuilt by `eve->clj`'s `vector?` arm, not by `IPlain`. (True on babashka only, where the types adapter marks it `IsEve`.) | `src/eve/deftype_proto/data.cljc:537-540`; `adapters/types/bb/src/eve/types.clj:212-227` |
| C9 | docs: "a JVM-family heap domain's classes are fixed … a full class spills to the next" (`doc/architecture.md:118-121`, `doc/internals.md:62-64`) | Heap classes **grow by replacement** (`grow-heap-class!`) before they spill. | `src/eve/deftype_proto/alloc.cljc:467-531`, `:635-639`, `:686-700` |

---

## 1. Host families

One `.cljc` definition per type; a host differs in how its `eve.types` adapter realizes it and in what a registered
collection **reads back as** (`eve.types/reader-of`, used by `register-eve-type!`,
`src/eve/deftype_proto/macros.cljc:104-113`).

| Family | Hosts (`hosts.edn`) | `reader-of` | `eve.core` (coll) | Codec |
|---|---|---|---|---|
| **A** — lazy type with the host's own collection interfaces | JVM, jolt (`adapters/types/jvm/src/eve/types.clj:88-93`); ClojureScript Node/browser, cherry (same adapter at compile time → `adapters/types/js/src/eve/types_js.cljc`); nbb, scittle (`adapters/types/nbb/src/eve/types.cljs:218-223`); ClojureCLR (`adapters/types/clr/src/eve/types.cljr:232-237`); basilisp (`adapters/types/basilisp/src/eve/types.lpy:900-905`); clojerl (`adapters/types/beam/src/eve/types.clje:412-417`) | `from-header` — the lazy slab-backed eve type | the host's core (`adapters/coll/jvm`, `coll/cljs`, `coll/nbb`, `coll/basilisp`, `coll/beam`) | `codec/js` on the JS hosts; `codec/byte-array` elsewhere |
| **B** — host value where the type registers one | babashka (`adapters/types/bb/src/eve/types.clj:287-293`), glojure (`adapters/types/glojure/src/eve/types.glj:171-176`), jank (`adapters/types/jank/src/eve/types.jank:217-222`), ValueScript (`adapters/types/vs/src/eve/types.vs:200-204`) | `(or host-value from-header)` | protocols (`adapters/coll/protocols/src/eve/core.clj:33-67`; `adapters/coll/jank`) | `codec/byte-array` |
| not eve memory | let-go, ClojureDart | — (no region / slab / coll / types root: `hosts.edn` `:lg`, `:cljd`) | — | — |

`:host-value` is registered by the **vector** (`src/eve/vec.cljc:973-987`), **set** (`src/eve/set.cljc:1074-1085`) and
**list** (`src/eve/list.cljc:423-439`); **not** by the map (`src/eve/map.cljc:1797-1806`). So on family B **[read]**:

- every read of a vector / set / list pointer — the root in `read-root-value` (`src/eve/atom.cljc:781-791`), a nested
  value in the codec — builds the **host's own collection eagerly** by the type's own loop, in or out of a swap, and
  never touches `IPlain`;
- inside `swap!` the function therefore receives a plain host vector / set / list for those kinds, and what it returns
  is re-written whole by the store path (the "materializing host" row of `doc/unification/HOST-CONTRACT.md:236-238`,
  still true for three types);
- a map is still the lazy `EveHashMap`.

On family B the bb types adapter also marks **every** non-map type it defines `IsEve`
(`adapters/types/bb/src/eve/types.clj:212-227`); the glojure / jank / VS adapters do not (grep `IsEve` over `adapters/`:
bb only).

---

## 2. Part 1 — the persistent types

### 2.1 Definitions, ids, layouts

No type body has a reader-conditional branch: one definition serves every host. The JVM-named interface groups a body
lists (`java.util.List`, `java.util.Map`, `clojure.lang.MapEquivalence`, `java.lang.Object`) are dropped or translated
per host by the adapter (`src/eve/vec.cljc:478`, `:585`; `src/eve/map.cljc:1128`, `:1218-1219`;
`src/eve/set.cljc:811-812`, `:821-823`).

Three macros define a type:

- `eve.deftype-proto.macros/deftype` (alias `eve/deftype`) — the core collections; instance fields `[sio__ offset__]`
  (+ `^:cached` ones); header written by the type's own code (`src/eve/deftype_proto/macros.cljc:33-68`;
  `src/eve/deftype_proto/sio.cljc:380-419`).
- `eve.deftype/eve-deftype` — a type whose block is `[type-id:u8][name-hash:u24][fields, 4-byte slots]`; `->T` allocates
  and writes it; type-id `0x40 + hash(name) mod 173` (`src/eve/deftype/macros.cljc:54-153`;
  `src/eve/deftype_proto/sio.cljc:172-299`; `src/eve/deftype/registry.cljc:15-67`). A field with no hint is
  "serialized": the slot holds the offset of a block `[len:i32][EVE bytes]` (`src/eve/deftype/slab_runtime.cljc:22-55`).
- `eve.deftype/deftype` (alias `edt/deftype`) — a **host** object (no block of its own), translated per host.

| Type | Defined | Header id / pointer tag | Block layout |
|---|---|---|---|
| `EveVector` | `eve/deftype`, `src/eve/vec.cljc:391-598` | `0x12` / `0x12` | header 24 B `[id][pad3][cnt:i32][shift:i32][root:i32][tail:i32][tail-len:i32]` (`:34-42`, `:192-200`); trie and tail nodes 128 B = 32 × i32 offsets (`:68-72`); **each element its own block** `[len:u32][EVE bytes]` (`:93-106`) |
| `EveHashMap` | `eve/deftype`, `src/eve/map.cljc:1082-1248` | `0xED` / `0x10` | header 12 B `[id][flags:u8][u16][cnt:i32][root-off:i32]` (`:1027-1036`); bitmap node `[type][flags][kv_total:u16][data_bm][node_bm][child offs][hashes][inline KVs]`, a KV `[klen:i32][kbytes][vlen:i32][vbytes]` (`:41-46`, `:960-1010`); collision node (`:995-1007`) |
| `EveHashSet` | `eve/deftype`, `src/eve/set.cljc:700-850` | `0xEE` / `0x11` | header 12 B (`:533-540`); nodes hold elements inline `[len:u32][bytes]` (`:87-107`); **an element is a key: flat bytes, never a pointer** (`:53-58`) |
| `EveList` | `eve/deftype`, `src/eve/list.cljc:137-271` | `0x13` / `0x13` | header 12 B `[id][pad3][cnt:i32][head-off:i32]`; cell `[next:i32][vlen:u32][EVE bytes]` (`:5-6`, `:27-36`, `:62-71`) |
| `EveArray` | `eve/deftype`, `src/eve/array.cljc:252-399` | `0x1D` / `0x1C` written by `-sab-encode` (`:342-345`), `0x1D` by the byte-array codec (`adapters/codec/byte-array/src/eve/codec.cljc:731-739`); both read (`array.cljc:790-791`, `:830-837`) | 8 B `[id][subtype:u8][u16][cnt:i32]` then raw little-endian elements (`:245-250`, `:405-419`); subtypes 1–9 (`:72-101`) |
| `HeapEveArray` (the brief's "JvmHeapEveArray") | `edt/deftype` host type, `src/eve/array.cljc:865-950` | none — **no block**; fields `[cnt subtype-code backing]` | the host's primitive array; exists where `eve.encoding/typed-array` answers one (JVM, bb, jolt: `:847-853`) |
| `ValueArray` (eve value array) | `edt/deftype` host handle `[sio off n]`, `src/eve/deftype/array.cljc:184-226` | `0x1F` / `0x1F` | `[0x1F][pad3][n:i32][kind:u8×n][tag:u8×n][pad→4][payload:i32×n]`; kinds nil / int32 / pointer / tag-only / block (`:14-23`, `:47-62`) |
| `EveSortedMap`, `EveSortedSet` | `eve-deftype`, `src/eve/sorted.cljc:393-479`, `:481-554` | derived id / same | `[type:4][root:4][cmp:4][mta:4]` (`:255-256`); `SortedNode` `[type:4][prio:4][size:4][left:4][right:4][entry:4]`, `SortedEntry` `[type:4][k:4][v:4]` (`:70-81`, `:252-254`); a treap, priorities = Murmur3 of the key bytes (`:117-120`) |
| rb-tree `SortedSet` | `eve-deftype`, `src/eve/deftype/rb_tree.cljc:475-535` | derived | `[type:4][root:4][mta:4]` (`:90`); `RBNode` `[type-id:1][pad:3][color:4][left:4][right:4][value:4]` (`:44-46`, `:65-76`, `:89`) |
| `PersistentIntMap` | `eve-deftype`, `src/eve/deftype/int_map.cljc:589-667` | derived | `[type:4][root-off:4][epoch:4][mta:4]` (`:725`); `IntMapLeaf` `[id][hash:3][key:4][value-off:4]`, `IntMapBranch` `[..][prefix][bit-offset][epoch][c0..c15]`, `IntMapBinaryBranch` `[..][neg-off][pos-off]` (`:121-189`) |
| RRB `Vector` | `eve-deftype`, `src/eve/rrb_vector/rrbt.cljc:313-…` | derived | fields `[^:int32 cnt ^:int32 shift root tail meta ^:mutable __hash]` — `root`/`tail`/`meta` serialized slots, `__hash` on the instance only (`:306-313`); `VectorNode [^:host edit arr]`, `arr` a `ValueArray` (`src/eve/rrb_vector/nodes.cljc:15-30`) |
| `Dataset` | `eve-deftype`, `src/eve/dataset.cljc:50-94` | derived | `[type:4][nrows:4][col-order:4][col-map:4]`; `col-map` a serialized map keyword → array |
| tensor `NDBuffer` | `eve-deftype`, `src/eve/tensor.cljc:53-106` | derived | `[type:4][elem-offset:4][data:4][shape:4][strides:4]` |
| `Obj` (eve object) | `edt/deftype` host handle `[schema sio slab-off data-off mta]`, `src/eve/obj/impl.cljc:187-240` | `0x1E` / `0x1E` | `[0x1E][pad:u8][schema-len:u16][schema bytes][field data]` (`:108-116`, `:266-281`); field types int8…float64, `:obj`, `:array` (i32 offsets) (`:24-35`) |
| `ObjArray`, `ObjArrayRow` | `edt/deftype` host types, `src/eve/obj/impl.cljc:326-380` | **none — not an eve value** | a host map `field → int32 eve-array` (`:386-394`); no `IEveRoot`, no storable protocol |
| record | ClojureScript codec only | pointer tag `0x1A` → an `EveHashMap` holding the fields + `:eve/record-tag` | `adapters/codec/js/src/eve/codec.cljs:116-141` |
| user `eve-deftype` | — | derived | as declared |
| `RSeq` | `edt/deftype` host type, `src/eve/rseq.cljc:10-35` | none | — |
| `ValueBuffer` | `eve.deftype/deftype` host type, `src/eve/deftype/value_buffer.cljc:28-34` | none (holds a `ValueArray`) | — |
| transients | `eve/host-deftype` / `deftype` host types: `TransientEveVector` (`src/eve/vec.cljc:637-658`), `TransientEveHashMap` (`src/eve/map.cljc:1365-1390`), `TransientEveHashSet` (`src/eve/set.cljc:1012-1030`), `TransientSortedMap/Set` (`src/eve/sorted.cljc:615-663`), `TransientIntMap` (`src/eve/deftype/int_map.cljc:859-871`), RRB `Transient` (`src/eve/rrb_vector/rrbt.cljc:1223`) | none | a persistent value behind a transient interface, over a scope log (`alloc/scope-sio`) |

Boilerplate every `eve-deftype` gets on the ISlabIO hosts (`src/eve/deftype_proto/sio.cljc:196-234`): `ISabpType`,
`IEveRoot`, `IEveSio`, `IHashEq` = `(hash slab-off__)`, `Object` `equals` = same type and same offset, `toString`
`#eve/T {:offset n}`. **Not** generated: `IPlain`, `IsEve`, `ISabStorable`. On ClojureScript
(`adapters/types/js/src/eve/types_js.cljc:314-379`) also `IDirectSerialize` and `ISabStorable` (a 7-byte pointer with the
type-id; `-sab-dispose` frees the block and its serialized-field blocks).

### 2.2 `IPlain` — who implements it, what it answers

`IPlain` (`src/eve/deftype_proto/data.cljc:509-513`) is implemented by exactly these types (grep `IPlain` over `src` +
`adapters`):

| Type | `-plain` | Answers | Eager / recursion | Meta | [ran-JVM] B1 — `(class (get @a k))` |
|---|---|---|---|---|---|
| `EveVector` | `(mapv d/eve->clj (ecore/seq this))` (`src/eve/vec.cljc:541-542`) | host vector | eager, recursive | none to keep | `PersistentVector` |
| `EveHashMap` | `(into {} (map (fn [kv] [(eve->clj k) (eve->clj v)])) (ecore/seq this))` (`src/eve/map.cljc:1215-1216`) | host map | eager, recursive | none | `PersistentArrayMap` |
| `EveHashSet` | `(into #{} (map d/eve->clj) (ecore/seq this))` (`src/eve/set.cljc:808-809`) | host set | eager | none | `PersistentHashSet` |
| `EveList` | `(apply list (map d/eve->clj (ecore/seq this)))` (`src/eve/list.cljc:255-256`) | host list | eager; **allocates one slab header per element while walking** (§2.6) | none | `PersistentList` |
| `EveArray` | a transient vector of `(d/eve->clj (e/nth this i))` (`src/eve/array.cljc:359-366`) | host **vector** — not an array | eager | none | `PersistentVector` (stored from a `HeapEveArray`: A13) |
| `EveSortedMap` / `EveSortedSet` | `to-plain` (`src/eve/sorted.cljc:377-387`, `:470-471`, `:545-546`) | **another `EveSortedMap` / `EveSortedSet`, built in the process's own slab** — `(binding [d/*domain-sio* nil] (build-map c …))`; "the same sorted collection, its contents plain, in this process's own domain (never the atom's)" | eager copy, keys and values through `eve->clj` | **dropped** (`end!` builds `(ctor root cmp nil)`, `:607-613`) | `eve.sorted.EveSortedMap`, **slab-backed**, its sio the process default-sio (B3 `true`) |
| rb-tree `SortedSet` | `(into (clojure.core/sorted-set) (map d/eve->clj) (rb-seq root))` (`src/eve/deftype/rb_tree.cljc:524-525`) | host sorted set | eager | dropped | `PersistentTreeSet` |
| `PersistentIntMap` | `(into {} (map (fn [kv] [(key kv) (d/eve->clj (val kv))])) (e/seq this))` (`src/eve/deftype/int_map.cljc:656-657`) | host (unsorted) map | eager | dropped | `PersistentArrayMap` |
| `Dataset` | `(into {} (map (fn [k] [k (d/eve->clj (get col-map k))])) col-order)` (`src/eve/dataset.cljc:59-61`) | host map column → vector; row count, dtypes, column order are not in it | eager | — | `PersistentArrayMap` |
| `NDBuffer` | nested `mapv` by shape and strides, a scalar for rank 0 (`src/eve/tensor.cljc:64-79`) | nested host vectors | eager | — | `PersistentVector` |
| `Obj` | `(into {} (map (fn [k] [k (d/eve->clj (field-value this k nil))])) (:field-keys schema))` (`src/eve/obj/impl.cljc:218-220`) | host map; an `:obj` / `:array` field is its raw i32 offset | eager | dropped (instance field) | `PersistentArrayMap` |
| `eve.guard` `LazyMap`, `LazyColl` | `(d/eve->clj v)` after `check!` (`src/eve/guard.clj:141-142`, `:176-177`) | as the wrapped value | — | — | — |

**Not `IPlain`:** RRB `Vector` and `VectorNode`; `ValueArray`; `HeapEveArray`; `ObjArray`, `ObjArrayRow`; the interior
node types (`SortedNode`, `SortedEntry`, `RBNode`, `IntMapLeaf`, `IntMapBranch`, `IntMapBinaryBranch`); `RSeq`,
`RRBChunkedSeq`, `ValueBuffer`; every transient; every user `eve-deftype`; ClojureScript records and JS typed arrays
(plain host values the codec decodes).

What a plain deref hands back for those, through `eve->clj`'s later arms (`src/eve/deftype_proto/data.cljc:537-543`):

| Type | Host family A — JVM | Family A — other [read] | Family B — babashka [read] |
|---|---|---|---|
| RRB `Vector` | [ran-JVM] B1: `clojure.lang.PersistentVector` — the `(vector? v) (mapv eve->clj v)` arm (`:540`); meta lost (D5) | ClojureScript: `vector?` is `IVector`, which the type implements → rebuilt likewise | `IsEve` arm (`:537`) → **the live eve value** |
| `ValueArray` | [ran-JVM] B1, S5: **`eve.deftype.array.ValueArray`, slab-backed, readable after the deref returned** — no predicate matches, `:else v` (`:543`) | `:else v` | `IsEve` → live |
| user `eve-deftype` | [ran-JVM] B1, S3–S4: **the live instance** (`user.Counter`, slab-backed); one that implements a host collection interface is instead rebuilt by the matching predicate arm | same rule | `IsEve` → live, whatever it implements |
| `HeapEveArray` | never stored as itself (written as an `EveArray` block) | — | — |
| `ObjArray` | not an eve value: stored only **by reference** (`0x15`) in an in-process domain, refused by a disk domain (§4.2) | same | same |

So `IsEve`-without-`IPlain` ("returned as-is") applies, at this pin, to: **no type on family A** (the five `IsEve`
types of `src/` — `src/eve/vec.cljc:530`, `src/eve/map.cljc:1202`, `src/eve/set.cljc:797`, `src/eve/list.cljc:244`,
`src/eve/array.cljc:350` — are all `IPlain`, and the `IPlain` arm is tested first); and on **babashka** to every
non-`IPlain` type its adapter defines: RRB `Vector`, `VectorNode`, `ValueArray`, `RSeq`, `HeapEveArray`, `ObjArray`,
`ObjArrayRow`, the interior node types, the transients, every user `eve-deftype` (the measured case in the comment is a
DataScript `DB`, `data.cljc:526-536`). On family A the as-is return of a slab-backed value happens through `:else v`
instead.

### 2.3 Storability

| Type | Protocols | What is written |
|---|---|---|
| vector, map, set, list | `d/ISabStorable` (`-sab-encode` → `codec/encode-eve-pointer`), `d/IDirectSerialize` (the offset), `d/IEveRoot`, `d/IEveSio` (`src/eve/vec.cljc:522-537`; `src/eve/map.cljc:1194-1209`; `src/eve/set.cljc:789-804`; `src/eve/list.cljc:236-251`) | **a 7-byte pointer** `[0xEE 0xDB tag off:i32]`; never flat bytes |
| `EveArray` | same four; `-sab-dispose` frees the block (`src/eve/array.cljc:339-357`) | 7-byte pointer (tag `0x1C`) |
| `HeapEveArray` | `d/IBackingArray` only (`src/eve/array.cljc:942-944`) | its elements copied into a fresh `0x1D` block of the receiving domain, then a pointer |
| `ValueArray` | `ISabStorable`, `IDirectSerialize`, `IEveRoot`, `IEveSio` (`src/eve/deftype/array.cljc:187-194`) | 7-byte pointer (tag `0x1F`) |
| every `eve-deftype` (sorted, rb, int-map, RRB, dataset, tensor, user) | `IEveRoot` + `IEveSio` (generated); on ClojureScript also `ISabStorable` / `IDirectSerialize` | 7-byte pointer whose tag is the block's type-id |
| `Obj` | `IEveRoot` + `IEveSio` (`src/eve/obj/impl.cljc:227-231`); a builder that **copies** the block into another sio (`:400-409`) | pointer (tag `0x1E`) |
| `ObjArray`, `RSeq`, transients, `ValueBuffer` | none | a `0x15` reference in a heap domain; refused in a disk domain |

Flat bytes are written only for **keys** (and digests): §4.2.

### 2.4 Hashing and equality

| Type | `hasheq` | `hashCode` | `equiv` | [ran-JVM] C — `[(= eve host) (= host eve) (= (hash eve) (hash host)) (= hashCodes)]` |
|---|---|---|---|---|
| `EveVector` | `(dg/root-hash sio__ offset__ (fn [] (p/hash-ordered this)))` (`src/eve/vec.cljc:458-459`) | `(p/hash-ordered this)` (`:598`) | `sequential?` + count, digest verdict when both `EveVector`, else element-wise `=` (`:441-456`) | `[true true true false]`; also `=` a host list both ways |
| `EveHashMap` | `(dg/root-hash … (p/hash-unordered this))` (`src/eve/map.cljc:1185-1186`) | `enc/map-hash-code` (`:1247-1248`) | digest verdict when both `EveHashMap`, else `map-equiv?` (`:1057-1063`, `:1151-1156`); `MapEquivalence` + `java.util.Map` (`:1220-1237`) | `[true true true true]` |
| `EveHashSet` | `(dg/root-hash … (p/hash-unordered this))` (`src/eve/set.cljc:774-775`) | sum of `hash` of the elements (`:818-819`) | digest verdict, else `set-equiv?` (`:671-681`, `:756-761`) | `[true true true false]` |
| `EveList` | `(dg/root-hash … (p/hash-ordered this))` (`src/eve/list.cljc:188-189`) | `Murmur3/hashOrdered` (`:270-271`) | `sequential?` + count, digest verdict when both `EveList`, else walk (`:170-186`) | `[true true true false]` |
| `EveArray` | own `31*h + hash(elem)` seeded by the subtype, cached on the instance (`src/eve/array.cljc:315-325`) | same | `array=`: another `IEveArray` of the same subtype, count, elements (`:223-235`) | vs a host vector: `[false false]` |
| `EveSortedMap` / `Set` | `(p/hash-unordered entries)` — recomputed each call (`src/eve/sorted.cljc:440-441`, `:520-521`) | same (`:479`, `:554`) | `map-equiv` / `set-equiv` (`:345-355`) | map vs hash-map and vs host sorted-map: `[true true true false]`; set: `[true true true false]` |
| rb `SortedSet` | `(reduce + 0 (map hash …))` (`src/eve/deftype/rb_tree.cljc:519-521`) | same | `(or (coll? other) (instance? SortedSet other))`, same count, every element of `other` a member (`:494-497`) | **`[true false false false]`**; and `(= rb-set [1 2 3])` → **`true`** |
| `PersistentIntMap` | `(hash (into {} …))` — builds a host map each call (`src/eve/deftype/int_map.cljc:700-708`) | same | `(or (map? other) (instance? PersistentIntMap other))`, count, every entry (`:694-698`) | **`[true false true false]`** — a host map is not `=` to it (no `java.util.Map` on the type) |
| RRB `Vector` | `(caching-hash this hash-ordered-coll __hash)`, cache on the instance (`src/eve/rrb_vector/rrbt.cljc:335-337`) | derived from it | `equiv-sequential` (`:331-333`; `src/eve/coll.cljc:12-24`) | `[true true true false]` |
| `ValueArray` | `(hash off)` — the block's offset (`src/eve/deftype/array.cljc:217-218`) | same | `va=`: the same block (`:228-231`) | identity |
| `Dataset` | `(hash [col-order nrows (mapv hash columns)])` (`src/eve/dataset.cljc:78-80`) | same | `equals` only: another `Dataset`, same rows/order/columns (`:85-91`); no `equiv` | — |
| `NDBuffer` | `(hash [shape (vec (take 100 (e/seq this)))])` (`src/eve/tensor.cljc:85-87`) | same | `equals` only (`:98-102`) | — |
| `Obj` | `obj-hash` over `[k value]` pairs (`src/eve/obj/impl.cljc:242-244`) | same | `obj=`: another `IEveObj` with the same field keys and values (`:246-252`) | — |
| any other `eve-deftype` | `(hash slab-off__)` (`src/eve/deftype_proto/sio.cljc:215-220`) | same | same type, same offset (`:222-231`) | identity |

What is hashed: never eve bytes. The HAMT's *trie* hash is Murmur3 over a key's EVE bytes (`src/eve/map.cljc:82-92`);
a collection's `hash` is the host's Clojure hash of its decoded elements (`src/eve/platform.cljc:29-41`;
`adapters/coll/jvm/src/eve/core.clj:87-88`, `:170-181`), which is why `(hash eve)` = `(hash host)` on the four core types.
`hashCode` agrees with the host's only for the map.

**The digest cache** (`src/eve/digest.cljc:1-53`) — for map, set, vector and list roots: the first `hash` or `=`
**allocates a 48-byte block in the value's own domain** (`slab/alloc! sio CACHE_SIZE`, `:118`) and CASes its offset into
the root header's descriptor POINTER column (`:122`); the hash word and an SHA-256 digest are latched there. The digest is
over `eve.codec/value->eve-bytes` — the codec's **flat form** — of the root (`:163-179`). [ran-JVM] F5→F6: one
`(hash lazy-vec)` inside `$` took one 64-byte-class block (`16383 → 16382`); a following `=` took none. The JS codec has
no flat form (`adapters/codec/js/src/eve/codec.cljs:1470-1476` throws), so on ClojureScript the digest verdict is always
`:walk`.

### 2.5 Seq, reduce, iterator

| Type | `seq` | `reduce` | [ran-JVM] M1 `(class (seq x))` |
|---|---|---|---|
| `EveVector` | `lazy-seq`, one `nth-impl` per step, unchunked (`src/eve/vec.cljc:399-407`) | `IReduceInit` / `IReduce` by index (`:504-520`); `rseq` an `RSeq` (`:483-484`) | `LazySeq` |
| `EveHashMap` | **eager**: `hamt-kv-reduce` into a transient vector, then its seq (`src/eve/map.cljc:1012-1021`, `:1088-1091`) | `IReduce` / `IReduceInit` / `IKVReduce` walk the tree in place (`:1158-1183`) | `PersistentVector$ChunkedSeq` |
| `EveHashSet` | **eager** (`src/eve/set.cljc:522-527`) | `hamt-val-reduce` (`:770-772`) | `PersistentVector$ChunkedSeq` |
| `EveList` | itself (`src/eve/list.cljc:145`); `first` decodes the head cell; **`next` / `more` allocate a header** (`:195-203`) | `IReduceInit` / `IReduce` walk the cells, no allocation (`:214-234`) | `eve.list.EveList` |
| `EveArray` | `lazy-seq` (`src/eve/array.cljc:280-287`) | by index (`:295-313`) | — |
| `EveSortedMap` / `Set` | lazy in-order walk over an explicit stack (`src/eve/sorted.cljc:196-206`, `:431`, `:502`) | via seq | `LazySeq` |
| rb `SortedSet` | `lazy-cat` in-order (`src/eve/deftype/rb_tree.cljc:271-277`); `count` walks the whole tree (`:279-284`) | via seq | `LazySeq` |
| `PersistentIntMap` | per-node seqs (`src/eve/deftype/int_map.cljc:624-628`) | `IKVReduce` / `IReduceInit` in place (`:634-644`) | `Cons` |
| RRB `Vector` | `array-seq` of the tail, or a chunked `RRBChunkedSeq` (`src/eve/rrb_vector/rrbt.cljc:339-344`, `:68-150`) | — | `LazySeq` (5 elements) |
| `ValueArray` | lazy `map` over the slots (`src/eve/deftype/array.cljc:173-175`) | — | — |

Every `Iterable/iterator` is `(clojure.lang.SeqIterator. (.seq this))`. Every element read decodes through
`codec/decode`, which answers a nested collection as its lazy type (family A) — **except** a map key or set element that
is a collection, which is stored flat and decodes to a plain host collection: [ran-JVM] P1 (keys read in `$`:
`PersistentVector`, `PersistentHashSet`, `PersistentArrayMap`), P2 (a map element of a set, and the vector inside it:
`PersistentArrayMap`, `PersistentVector`).

### 2.6 Metadata

| Type | Where meta lives | [ran-JVM] D |
|---|---|---|
| vector, map, set, list, `EveArray` | **nowhere**: `(meta [_] nil)`, `(withMeta [this m] this)` (`src/eve/vec.cljc:409-413`; `src/eve/map.cljc:1093-1097`; `src/eve/set.cljc:709-713`; `src/eve/list.cljc:147-151`; `src/eve/array.cljc:289-293`) | D1: `(meta (with-meta eve-vec {:a 1}))` → `nil`, and the result is `identical?` to the input. D2: a host vector stored with meta reads back with none, in `$` and by deref |
| `EveSortedMap` / `Set` | a serialized field `mta` **in the slab** (`src/eve/sorted.cljc:393`, `:399-403`, `:481`, `:487-491`) | D3: `{:m 1}` in `$`, `nil` after a deref |
| rb `SortedSet` | serialized field `mta` (`src/eve/deftype/rb_tree.cljc:475`, `:482-486`) | — |
| `PersistentIntMap` | serialized field `mta` (`src/eve/deftype/int_map.cljc:589`, `:595-599`) | D4: `{:m 1}` in `$`, `nil` after a deref |
| RRB `Vector` | serialized field `meta` (`src/eve/rrb_vector/rrbt.cljc:313`, `:322-328`) | D5: `{:m 1}` in `$`, `nil` after a deref |
| `Obj`, `ObjArray`, `RSeq` | a field of the **host handle**, never written to the slab (`src/eve/obj/impl.cljc:187-192`, `:347-352`; `src/eve/rseq.cljc:10-14`) | — |
| `ValueArray`, `Dataset`, `NDBuffer`, `HeapEveArray` | no `IMeta` | — |

**No materialization path preserves metadata.**

### 2.7 Printing

- vector / map / set / list register `:print` (`src/eve/vec.cljc:993`; `src/eve/map.cljc:1806`; `src/eve/set.cljc:1091`;
  `src/eve/list.cljc:445`); the types adapter makes the host's printer: `print-method` through
  `clojure.core/print-sequential` / `print-map` on the JVM and CLR (`adapters/types/jvm/src/eve/types.clj:95-114`),
  `IPrintWithWriter` on ClojureScript / nbb (`adapters/types/js/src/eve/types_js.cljc:669-697`), none on babashka,
  glojure, jank, VS, basilisp, clojerl ("prints it by its own", e.g. `adapters/types/bb/src/eve/types.clj:295-300`).
  `toString` is `(pr-str this)`. [ran-JVM] J1: `{:vec [1 [2 3]], :map {:k {:n 1}}, :set #{1 2}, :lst (1 2 3)}` — as host
  data, walking the slab.
- [ran-JVM] J2 / J4, `(str x)`: int-map `#eve/IntMap{1 :a, 2 :b}` (`src/eve/deftype/int_map.cljc:710-711`); rb set and
  `eve.sorted` set `#eve/SortedSet#{1 2 3}` (`src/eve/deftype/rb_tree.cljc:467-468`; `src/eve/sorted.cljc:339-343`);
  sorted map `#eve/SortedMap{1 :a, 2 :b}`; RRB `[0 1 2 3 4]`; value array `#eve/array [:x [1 2]]`; `Obj`
  `#eve/obj {:y 2.5, :x 1}`; dataset `#eve/dataset {1 cols × 2 rows}`; tensor `#eve/tensor [2 3] :float64`; a user type
  `#eve/Counter {:offset 92}`; arrays `#eve/array :int32 [1 2 3]`.
- An atom prints `#eve/shared-atom {:id 1 :idx 1}` — its slot, not its value (`src/eve/atom.cljc:1190-1193`; J3).

### 2.8 Constructors, and what "outside a `swap!`" does

```clojure
(defn current-sio [] (or d/*domain-sio* (default-sio)))                              ; alloc.cljc:1726-1731
(defn default-sio [] (if-let [f (host-op :default-sio)] (f) @process-heap-ctx))     ; alloc.cljc:1718-1724
(defdelay ^:private process-heap-ctx (make-jvm-heap-slab-ctx))                       ; alloc.cljc:1713-1716
```

`d/*domain-sio*` is bound only by `swap-attempt!` (`src/eve/atom.cljc:987-994`) — to the attempt's `LoggingSlabIO` — and
by the transients / scoped builders around their own scope logs.

| Type | Public constructors | Allocates in |
|---|---|---|
| vector | `empty-vec`, `eve-vec` (`src/eve/vec.cljc:676-689`); `vec-from-header` (`:671-674`); block-level `build-vec`, `splice-vec`, `subvec-sharing`, `concat-vec`, `replace-range-vec`, `wrap-elem-block!` (`:922-970`); aliases (`:822-824`); `->transient-vec` (`:655-658`) | the sio given, else `current-sio`; the block-level ones the source vector's sio |
| map | `empty-hash-map`, `hash-map` (a leading `ISlabIO` argument is the sio), `into-hash-map`, `hash-map-from-header` (`src/eve/map.cljc:1269-1307`); `->transient-map` (`:1387-1390`) | same |
| set | `empty-hash-set`, `hash-set`, `hash-set-from-header` (`src/eve/set.cljc:942-963`); `->transient-set` (`:1027-1030`) | same |
| list | `empty-list`, `eve-list`, `list-from-header` (`src/eve/list.cljc:371-391`) | same |
| `EveArray` | `eve-array`, `eve-array-uninit`, `int32-array`, `int32-array-from`, `from-double-array`, `from-int-array`, `from-byte-array`, `eve-array-from-offset` (`src/eve/array.cljc:472-531`, `:765-768`, `:982-1002`) | **JVM, bb, jolt with no sio: no slab** — a `HeapEveArray` (`:489-497`, `:847-853`; [ran-JVM] A12 `eve.array.HeapEveArray`). Elsewhere or with a sio: the sio / `current-sio` |
| `ValueArray` | `make-array`, `aclone` (`src/eve/deftype/array.cljc:251-263`, `:287-296`) | `current-sio`; no sio parameter |
| `eve.sorted` | `sorted-map`, `sorted-map-by`, `sorted-set`, `sorted-set-by` (`src/eve/sorted.cljc:669-691`) via a transient over `(alloc/scope-sio (alloc/current-sio))` (`:367-375`) | `current-sio` |
| rb | `sorted-set` (`src/eve/deftype/rb_tree.cljc:560-575`), `->SortedSet` | `current-sio` |
| int-map | `int-map`, `merge`, `merge-with`, `update`, `range` (`src/eve/deftype/int_map.cljc:877-921`) | `current-sio`; derived nodes the node's own sio (`:191-195`) |
| RRB | `vector`, `vec`, `catvec`, `subvec` (`src/eve/rrb_vector.cljc:59-120`); `->Vector`, `->VectorNode`, `empty-node` | `current-sio` |
| dataset | `dataset` (`src/eve/dataset.cljc:126-150`); structural ops stay in the dataset's own sio (`:115-120`) | the sio given, else `current-sio` |
| tensor | `from-array`, `zeros`, `ones` (`src/eve/tensor.cljc:193-226`) | the array's own sio when slab-backed, else the sio given / `current-sio` |
| `Obj` | `obj`, `obj-from-offset`; `obj-array` (`src/eve/obj/impl.cljc:254-259`, `:291-301`, `:386-394`) | `current-sio`; `obj-array`'s columns are `int32-array`s (heap arrays on the JVM) |
| any `eve-deftype` | `->T` (`src/eve/deftype_proto/sio.cljc:265-277`): `([args] (->T (alloc/current-sio) args))`, `([sio__ args] …)` | `current-sio`, or the leading sio |

**JVM, outside a `swap!`** — [ran-JVM]:

- A1–A2: `(eve.vec/eve-vec [1 2 3])` is an `eve.vec.EveVector` whose sio is `(alloc/default-sio)`: this process's heap
  domain (`make-jvm-heap-slab-ctx`: six 1 MB classes + a 64 KB class 6, `alloc.cljc:1012-1035`). A15: the same for
  `(im/int-map 1 :a)`.
- A3–A5: two anonymous atoms share one domain; a named atom has its own; **the process default-sio is neither**
  (`src/eve/atom.cljc:1615-1635`).
- A6, at a root: `swap!: Eve value was allocated in a different slab domain; construct it with the atom's domain sio
  (eve.atom/atom-sio)` (`src/eve/atom.cljc:869-870`).
- A7–A10, nested — in a named atom, in an anonymous atom, as `(atom {:v (eve-vec [1])})`, and as
  `(swap! a assoc :k (eve-vec …))` (the argument is evaluated before the swap runs): `An Eve value allocated in a
  different slab domain cannot be put in this one; build it inside swap! (or with the atom's domain sio,
  eve.atom/atom-sio)` (`adapters/codec/byte-array/src/eve/codec.cljc:697-700`).
- A11: built inside `f` it stores.
- The blocks of a value built outside are never reclaimed — nothing retires them ("as a dropped value does outside an
  atom", `src/eve/deftype/value_buffer.cljc:14-15`; `doc/data-structures.md:513-516`).

**ClojureScript / nbb / cherry / scittle, outside a `swap!`** — [read]: `:default-sio` is `cljs-sio`, the one stateless
token over the module slabs, and `:heap-domain-sio!` answers **the same token** for every in-process domain
(`adapters/slab/module/src/eve/process_slab.cljs:824-859`, `:1363-1377`). So the value lands in the *same* slab every
in-process atom uses, `same-domain?` passes, and it **is storable by pointer** — no refusal. When an mmap domain is open
the module slabs *are* that domain's files (`:1301-1317`; "One mmap domain at a time per CLJS process", `:1107-1112`), so
a constructor outside a `swap!` allocates **in the shared files, unlogged**: no attempt log covers it, a lost CAS cannot
free it, and nothing retires it unless a later swap stores it.

What does throw at construction (none of it about being outside a swap): argument checks (`src/eve/dataset.cljc:140-149`;
`src/eve/tensor.cljc:204-206`; `src/eve/array.cljc:85-87`), allocator limits (`src/eve/deftype_proto/alloc.cljc:456-465`,
`:698-700`), a type-id collision at definition (`src/eve/deftype/registry.cljc:72-82`). Use-after-end throws: a
transient after `persistent!` (`src/eve/sorted.cljc:595-597`; `src/eve/deftype/int_map.cljc:834-836`), a `ValueBuffer`
written by another thread (`src/eve/deftype/value_buffer.cljc:46-48`), a guard value after its extent
(`src/eve/guard.clj:36-39`).

### 2.9 Reads that write, and in-place mutation

| Surface | What is written | Evidence |
|---|---|---|
| `EveList` `next` / `more` / `pop` | a fresh 12-byte header block per step (`src/eve/list.cljc:157-161`, `:195-203`, `:365-369`). `-plain` walks by `seq`, so **each plain deref of an N-element list allocates N blocks in the atom's domain; nothing frees them** | [ran-JVM] F1→F2→F3: class-0 free blocks `32716 → 32666` after one deref of a 50-element list, `→ 32166` after ten more; a 50-element vector: unchanged over ten derefs (F4→F5) |
| `eve.sorted` `-plain` | a whole copy of the collection in the **process** slab per deref, never freed | [ran-JVM] F8→F9: ten derefs of a 3-entry sorted map took 130 class-0 blocks of the process default-sio |
| `hash` / `=` on a map / set / vector / list root | the 48-byte digest side block + a CAS on the descriptor row (`src/eve/digest.cljc:103-126`) | [ran-JVM] F6 |
| `EveArray` `aset!`, `cas!`, `exchange!`, `add!`, `sub!`, `band!`, `bor!`, `bxor!`, `amap!`, `afill!`, `acopy!`, `wait!` / `notify!` | the array's block, in place, atomically (`src/eve/array.cljc:546-554`, `:570-624`, `:635-658`, `:690-727`) | [ran-JVM] S1→S2: `aset!` on the stored array inside `$`, no swap — the atom then derefs `[99 2 3]` and `(version a)` is **unchanged** (`[3 1]` before and after) |
| `eve-deftype` `^:mutable` / `^:volatile-mutable` fields (`set!`, `cas!` rewritten to slab writes, `src/eve/deftype_proto/sio.cljc:84-135`) | the instance's block | [ran-JVM] S3→S4: mutating the instance a **plain deref** returned changed the atom's stored value (count 3 → 4), no swap |
| `Obj` `assoc!`, `cas!`, `add!`, `sub!`, `exchange!`; `ObjArray` `assoc-in!`, `cas-in!`, … (`src/eve/obj.cljc:43-117`) | the object's block / the column arrays | [read] |
| tensor `mset!` (`src/eve/tensor.cljc:262-269`) | the backing array | [read] |
| `ValueArray` `aset`, `array-copy` (`src/eve/deftype/array.cljc:272-306`) | the block's slots — "written while it is new", a convention | [read] |
| transients | a transient-owned header and tail are written in place (`conj!-owned`, `src/eve/vec.cljc:620-634`) | [read] |
| ClojureScript: a JS typed array stored in an atom | decoded **inside a swap** as a mutable view into the SAB; outside, a copy (`adapters/codec/js/src/eve/codec.cljs:1031-1094`, `:1295-1346`); `eve.typed-view` hands out raw views and SIMD fills (`doc/data-structures.md:160-176`) | [read] |

---

## 3. Part 2 — materialization, deref, value delivery

### 3.1 `eve.atom` — one atom type, three deref modes

One implementation, `MmapAtom` (`src/eve/atom.cljc:1174-1193`) and `MmapAtomDomain` (`:1206-1230`), over two kinds of
domain state: in-process (`open-heap-domain!`, `:727-737`) and disk (`open-mmap-domain!`, `:678-725`); every function
below is shared by both.

| Path | The caller receives | Via `IPlain`? | Where / evidence |
|---|---|---|---|
| `@a` outside any region | plain data: `(d/eve->clj (read-root-value sio ptr))` under a pin | yes — but see §2.2 for what `eve->clj` leaves slab-backed | `plain-deref`, `:918-931` |
| `@a` inside `eve.alpha/$` | the **lazy slab-backed value**; the domain stays pinned until the outermost `$` exits | no | `region-deref`, `:904-916`; `mmap-deref`, `:955-962` |
| `$`'s result | `(rr/run f d/eve->clj)` | yes, with `eve->clj`'s holes (a slab value inside a host atom, closure, array or non-collection object passes through `:else v`) | `$*`, `:1134-1139`; `src/eve/read_region.cljc:35-47` |
| `@a` inside `eve.guard/with-guard` (JVM only, prototype) | a `LazyMap` / `LazyColl` proxy; raw when `*proxy-same-domain?*` is false and the atom is in the extent's receiving domain; **`guard*` returns its result as is** | no | `guard-deref`, `:933-953`; `src/eve/guard.clj:70-85`; [ran-JVM] K2 `eve.guard.LazyMap`, K3 throws after the extent |
| `swap!` — `f`'s argument | the lazy value, read through the attempt's `LoggingSlabIO` | no | `:985-989`; [ran-JVM] smoke: `eve.map.EveHashMap`, nested `eve.vec.EveVector` |
| `swap!` — return value | **whatever `f` returned, unconverted** | **no** | `:1063`, `:1101-1102`; [ran-JVM] E1: `(swap! a assoc :z 1)` returns an `eve.map.EveHashMap` whose sio is a (stopped) `LoggingSlabIO` over the atom's domain (E2), and it reads after the swap returned and the pin was released (E3); E6: `(swap! a (fn [m] (get m :vec)))` returns an `eve.vec.EveVector` |
| `reset!` — return value | the argument itself | n/a | `:1182`; E5 `PersistentArrayMap` |
| `compare-and-set!` | boolean; compares `(= (d/eve->clj cur) old-val)` — the whole root materialized on every attempt | yes | `:1106-1120`; I1 |
| `read-pinned a f & args` | `f`'s result as is | no — a bypass by contract ("f's result must not keep the value") | `:1148-1163`; K1: `(read-pinned a identity)` → `eve.map.EveHashMap` |
| `add-watch`, `set-validator!` | **not implemented** | — | `MmapAtom` lists `IDeref`, `IAtom`, `IEveAtom`, `IPrintWithWriter` only; [ran-JVM] I2/I3: `ClassCastException: class eve.atom.MmapAtom cannot be cast to class clojure.lang.IRef`; `doc/atom-domains.md:245` |
| registry reads | plain map (`plain-deref ds 0`) | yes | `:1442-1443`, `:1473`, `:1500` |
| `register-name!` | in a swap, `ecore/get` / `ecore/assoc` on the lazy registry | no | `:1426-1440` |
| `version` | `[epoch root-pointer]` | — | `:1514-1522` |
| `seal` | an FNV-1a fold over the raw bytes of every block `retire/reach` finds | no — reads bytes | `:1526-1553` |

Root dispatch for every read: `read-root-value` (`:781-791`) — header byte `0x01` → `alloc/scalar-block->value` (codec
decode); else the registered header constructor.

Same-domain pointer sharing exists only under a lazy read: [ran-JVM] Q1 — inside `$`,
`(swap! a2 (fn [_] (get @a1 :sub)))` (two anonymous atoms) stores a1's own subtree offset (`[4 4]`); Q2 — the same
outside `$` copies (`[4 12]`).

### 3.2 `eve->clj` itself (`src/eve/deftype_proto/data.cljc:515-543`)

Order: nil; scalars; `IPlain` → `-plain`; `IsEve` → `v`; `map?` → `(into {} …)`; `set?` → `(into #{} …)`; `vector?` →
`mapv`; `list?`; `sequential?` → `(doall (map …))`; `:else v`. Consequences beyond §2.2: it is not a type-preserving
copy even for host data — a host `sorted-map`, a record, or a collection with metadata passed through it comes back a
plain unsorted map / plain map / without metadata (the arms rebuild with `into {}` / `mapv`). The `IPlain` test is a
`satisfies?`, measured at ~19 µs a miss on an `EveVector` by the jvm-heap-grade lane (`doc/jvm-heap-grade/REPORT.md:260`).

### 3.3 Bypasses of `IPlain` (complete for what is read so far)

1. **`swap!`'s return value** (`src/eve/atom.cljc:1063`, `:1102`) — a slab-backed value handed to the caller unpinned.
2. **`read-pinned`** (`:1148-1163`) and **`eve.guard/guard*`** (`src/eve/guard.clj:70-85`) — the function's result
   unconverted.
3. **Family B `:host-value` readers** — `vec->host-value` (`src/eve/vec.cljc:973-981`), `set->host-value`
   (`src/eve/set.cljc:1074-1079`), `list->host-value` (`src/eve/list.cljc:423-433`): an eager host collection at every
   pointer read.
4. **The codecs' flat decode** (`0xED` / `0xEE` / `0xEF`) — map keys and set elements that are collections
   (`adapters/codec/byte-array/src/eve/codec.cljc:290-321`; `adapters/codec/js/src/eve/codec.cljs:1098-1132`).
5. **The byte-array codec's flat encode** of an eve collection used as a key or digested — walks it by the host
   predicates (`codec.cljc:622-629`); `IPlain` only where they are blind (`:631-639`).
6. **`eve->clj`'s predicate arms** — a non-`IPlain` eve type that carries a host collection interface is rebuilt
   generically (the RRB vector on the JVM), `data.cljc:538-542`.
7. **`eve->clj`'s as-is arms** — `IsEve` (`:537`) and `:else` (`:543`): `ValueArray`, user `eve-deftype`s, and on
   babashka every non-`IPlain` type.
8. **`eve.sorted`'s `-plain`** — formally through `IPlain`, but it answers an eve value in another domain, not plain
   data (`src/eve/sorted.cljc:377-387`).
9. **ClojureScript records** — `(dissoc (reduce-kv assoc {} sab-m) :eve/record-tag)` then the registered `map->`; the
   field values are not recursively materialized (`codec.cljs:1011-1021`, `:1284-1293`).
10. **ClojureScript typed arrays** — a copy outside a swap, a live SAB view inside one (`codec.cljs:1031-1094`).
11. **`seal`** — raw block bytes (`atom.cljc:1526-1553`).
12. **`EveArray` bulk access** — `IBulkAccess` `-as-double-array` / `-as-int-array` (a host-array copy), `array-values`
    (`src/eve/array.cljc:384-390`, `:817-821`); `tensor/to-array`, `ds/column` hand out the `EveArray` itself.
13. **Printing** — `print-method` / `toString` / `-pr-writer` walk the slab and print (§2.7); never `IPlain`.
14. **Reference decode** (`0x15`) — a value with no eve bytes comes back as the very host object, by identity, from
    this process's table (`src/eve/deftype_proto/serialize.cljc:186-259`).
15. `:foreign-policy :copy` — goes *through* `eve->clj`; listed as a second materialization site inside the store path
    (`atom.cljc:868`).

(STM, threads, network atoms, `shared_atom.cljs`: pending.)

---

## 4. Part 3 — the store path

### 4.1 The entry: `resolve-new-ptr`

`swap!` → `mmap-swap!` (`src/eve/atom.cljc:1087-1104`) → `swap-attempt!` (`:964-1085`) → `resolve-new-ptr`
(`:852-876`), under `(binding [d/*domain-sio* lsio] …)`:

| The value `f` returned | What happens | Where |
|---|---|---|
| `nil` | `NIL_OFFSET` | `:861-862` |
| `d/IEveRoot`, same domain (`(identical? (alloc/sio-root vs) (alloc/sio-root sio))`, or `-eve-sio` nil) | **its own header offset — by pointer, nothing copied** | `:864-871` |
| `d/IEveRoot`, another domain | per-domain `:foreign-policy`: `:throw` (default) or `:copy` → `(resolve-new-ptr sio (d/eve->clj new-val) :throw)` | `:361-363`, `:866-870`, `:1142-1146` |
| a host typed array, or a `d/IBackingArray` | `arr/write-host-array!` → one `0x1D` block | `:843-850`, `:874`; `src/eve/array.cljc:800-812` |
| a plain map / set / vector / list / seq | the registered direct writer, else `ser/convert-to-eve` | `:825-841`, `:875` |
| anything else | `alloc/value->scalar-block!`: `[0x01][EVE bytes]` | `:876`; `src/eve/deftype_proto/alloc.cljc:1494-1502` |

`scalar-leaf?` (`:793-823`) only screens the protocol tests on the JVM.

### 4.2 Per-type writers; what dispatches

| Kind | Direct writer (`coll-writers`) | Builder (`builders`) |
|---|---|---|
| map | `-write-map-coll!` (`src/eve/map.cljc:1773-1794`) | `map?` → `into-hash-map` (`:1802-1803`) |
| set | `-write-set-coll!` (`src/eve/set.cljc:1044-1065`) | `set?` → `into-hash-set` (`:1087-1088`) |
| vector | `-write-vec-coll!` → `assemble-vec!` (`src/eve/vec.cljc:838-893`) | `vector?` → `eve-vec` (`:989-990`) |
| list | `-write-list-coll!` (`src/eve/list.cljc:406-420`) | `seq?` → `eve-list` (`:441-442`) |
| array | `:array` → `write-host-array!` (`src/eve/array.cljc:814-815`) | `EveArray` instance → a **copy** (`:770-787`) |
| `Obj` | — | `IEveObj` → a **copy** of the block (`src/eve/obj/impl.cljc:400-409`) |

**Byte-array hosts** — the dispatch is **not** `ISabStorable`. `value+sio->eve-bytes`
(`adapters/codec/byte-array/src/eve/codec.cljc:668-745`) is a `cond`: scalar → inline bytes (`:679-682`); `d/IEveRoot`
→ domain check, `ser/stored-piece!`, a 7-byte pointer whose tag is read from the block's own header byte (`:692-706`);
`map?` / `set?` / `list?` / `vector?`-or-`sequential?` → the direct writer (`:711-729`); `d/IBackingArray` / host array →
a `0x1D` block (`:731-739`); else a **reference** `0x15`, or a refusal in a shared domain (`:741-745`).

**ClojureScript** — `serialize-value-a` / `serialize-val` (`adapters/codec/js/src/eve/codec.cljs:572-681`, `:698-803`):
`d/ISabStorable` first (`:586-587`, `:712-713`), then `IRecord` (`:590-592`), a plain map through the direct map encoder
(`:595-596`; `src/eve/map.cljc:1409-1414`), typed arrays (`:669-670`), `d/IDirectSerialize` (`:673-674`), then
`convert-to-eve` (`:677-681`).

**Flat bytes exist already — for keys and digests.** `encode-key` = `value->eve-bytes` writes a collection as a
self-contained blob with no pointers, in canonical order — `FLAT_MAP 0xED`, `FLAT_SET 0xEE`, `FLAT_VEC 0xEF`,
`[0xEE 0xDB tag][count:i32]([len:i32][bytes])*` (`adapters/codec/byte-array/src/eve/codec.cljc:422-467`, `:622-629`,
`:778-782`; `adapters/codec/js/src/eve/codec.cljs:683-696`, `:838-891`; `src/eve/deftype_proto/serialize.cljc:80-91`). It
is the stored form of every collection-valued map key and set element, the input of the root digest
(`src/eve/digest.cljc:163-179`), and it **decodes to plain host collections**. There is no flat form for sorted
collections, int-maps, arrays, eve-deftypes or metadata, and none at all on the JS codec's `value->eve-bytes`
(`codec.cljs:1470-1476`).

### 4.3 Same domain, another domain, plain value

- **Same domain: by pointer.** Root `atom.cljc:871`; nested `codec.cljc:701-706`, `codec.cljs:62-79`. The store reports
  `ser/stored-piece!` → `alloc/keep-owned!` (`src/eve/deftype_proto/alloc.cljc:1441-1453`): every logging scope in the
  chain stops freeing within itself; the commit's hold counts the piece (`tally/hold-root!`, `atom.cljc:1001`).
- **Another domain: refused**, three throw sites — root `src/eve/atom.cljc:869-870`; nested
  `adapters/codec/byte-array/src/eve/codec.cljc:697-700` and `adapters/codec/js/src/eve/codec.cljs:70-77`
  (+ `adapters/codec/jank/src/eve/codec.jank:700`). Tested `test/eve/jvm_atom_test.clj:276-280`; [ran-JVM] A6–A10, A14.
  The `:copy` policy applies at the **root only** (`atom.cljc:866-870`); a nested foreign value still reaches the codec's
  throw **[read]** (probe pending).
- **Plain host value: built**, allocating in `lsio`.

### 4.4 The sequence inside `swap-attempt!` (`src/eve/atom.cljc:964-1085`)

| Line | Step |
|---|---|
| 979-980 | `refresh-slabs!`; pin the domain epoch for this thread (`pin-epoch!`, `:751-758`) |
| 981 | `(binding [tally/*touches* (atom #{})] …)` |
| 983-984 | load the slot's root pointer; `refresh-slabs!` behind the load |
| 985 | `lsio (alloc/logging-sio sio)` — a fresh `LoggingSlabIO` (`alloc.cljc:1077-1099`, `:1423-1426`): `alloc!` adds the offset to a set, `free!` removes it |
| 986 | `old-val (read-root-value lsio old-ptr)` — lazy, reading through `lsio` |
| 987-989 | `new-val (binding [d/*parent-atom* ds, d/*domain-sio* lsio] (apply f old-val args))` |
| 992-994 | `new-ptr (binding [d/*domain-sio* lsio] (resolve-new-ptr lsio new-val foreign-policy))` |
| 995-999 | `new-ptr == old-ptr` → `stop-logging!`, `[:ok new-val]` |
| 1001 | `tally/hold-root! sio new-ptr` |
| 1016-1019 | durable, no journal: `alloc/sync-touched!` over touched descriptor rows + unforced note + `logged-allocs` |
| 1020 | `mem/-cas-i32! root-r ptr-off old-ptr new-ptr` |
| 1023 | won: epoch `+1` |
| 1024-1039 | J1 journal domain: `tally/drop-root!` old, then `journal-note-commit!` with the attempt's touched set |
| 1040-1061 | otherwise: journal batch note or root-range force; `tally/drop-root!` old; force the drop's rows |
| 1062-1063 | `stop-logging!`; `[:ok new-val]` |
| 1064-1084 | lost: `logged-allocs`, `stop-logging!`, `tally/drop-root!` **new**, `tally/free-unheld!`, force (non-journal), `[:retry nil]` |
| 1085 | `finally (unpin-epoch! ds)` |

`mmap-swap!` (`:1087-1104`) wraps the attempt in `with-domain-write` (`:288-299`), calls `try-flush-retires!` after an
`:ok` (`:1099`; 50 ms / 64 entries, `:62-65`, `:890-898`), backs off, retries up to 1,000 times
(`src/eve/deftype_proto/data.cljc:622`).

`alloc/scope-sio` (`alloc.cljc:1428-1432`) is a `LoggingSlabIO` whose `inner` is the sio given (under a swap, the
attempt's log): a transient's allocations are recorded in both; `free-owned!` (`:1460-1469`) frees only what that scope
allocated, and only while no existing piece was stored through it.

(`eve.tally`, `eve.retire`: pending.)

### 4.5 Disk (journal) domains

`src/eve/atom.cljc:452-475`, verbatim: "Records carry COMMIT METADATA ONLY — never values:
`[magic i32][len i32][epoch i32][table-len i32][table bytes][crc32 i32]`". The table is the root region's raw atom-table
range, 256 × 8 = 2,048 bytes: one root pointer per atom per epoch. The journal appends **neither value bytes, block
images nor ops**, and does not care how the new value's blocks were produced — only that every page the record's
pointers reach was forced first (`journal-flush!`, `:496-559`: under the journal lock, sweep → append → force).

- J1 (`:journal-batch` ≤ 1, the default): the sweep is the publishing attempt's touched set — `tally/*touches*` +
  `logged-allocs` (`:1037-1039`, `:519-520`). **A block the commit references that was neither allocated through the
  attempt's log nor touched by its holds is outside that sweep.**
- Batch / checkpoint: a whole-domain sweep (`:521-522`).
- Retention floor: `:floor` (`:693-707`), set at recovery (`:619-626`), advanced at each flush to the previous flush's
  epoch (`:529-555`) and at checkpoint to the anchor's (`:649-672`); the zero-queue flush frees only below
  `(min readers floor)` (`:413-424`).
- Adapter calls made: `jf/open-journal`, `with-journal-lock`, `journal-append!`, `journal-record`, `journal-record-of`,
  `journal-force!`, `journal-scan`, `journal-truncate!`, `journal-write-at!`, `journal-close!`
  (`:516-527`, `:612-647`, `:663-676`, `:694`). (Adapter file: pending.)

### 4.6 Size limits

| Limit | Value | Where / evidence |
|---|---|---|
| fixed classes | 32…1024 B; larger → class 6 | `src/eve/deftype_proto/data.cljc:73-95`, `:300-311`; `src/eve/deftype_proto/alloc.cljc:551-554` |
| offset | `[class:3 | block:29]`; a class-6 "block index" is a byte offset (to verify in `coalesc.cljc`) | `alloc.cljc:60-85` |
| heap domain | 1 MB per class + 64 KB class 6 to start; **both grow** (≤ 20 doublings per allocation), bounded by `:mem-budget` | `alloc.cljc:382-531`, `:686-700`, `:1012-1035` |
| HAMT node KV total | u16 header field; past 65,535 the node caches 0 and is scanned | `src/eve/map.cljc:206`; `test/eve/big_value_test.cljc:1-4` |
| map value | inline, unbounded in practice | [ran-JVM] G5 (2,000 chars), G7 (100,000 chars) round-trip |
| vector element / list cell | its own block, any size | [ran-JVM] G6 |
| host array | one `0x1D` block | [ran-JVM] G8: a 1 MiB `byte[]` nested stores; G9: an 8 MiB `byte[]` at a root stores. **Both deref as a `PersistentVector` of that many boxed elements** |
| **scalar at an atom root** | `scalar-block->value` reads `(dec (d/slab-size class))` bytes; for a class-6 block `slab-size` is 1 → 0 bytes → `nil` | `alloc.cljc:1494-1522`; `data.cljc:85-91`. [ran-JVM] G1 1,000 chars → 1000; G2 1,016 chars (block exactly 1,024 B) → 1016; **G3 1,017 chars → `nil`; G4 2,000 chars → `nil`** — a root string past 1,016 bytes is stored and silently reads back `nil` |
| symbol segment | 255 UTF-8 bytes, else refused | `adapters/codec/byte-array/src/eve/codec.cljc:401-409` |
| swap retries | 1,000 | `data.cljc:622` |
| atoms per domain | 255 named | `data.cljc:401`; `src/eve/atom.cljc:1397-1424` |

Bearing on ingesting one large packed buffer in one swap: as a **host array / `IBackingArray`** it is one class-6 block
(`write-host-array!`), but there is no typed read-back — a deref boxes it into a vector, and only `$` + `EveArray`
`IBulkAccess` (`src/eve/array.cljc:384-390`) gives a host-array copy, for `:float64` / `:int32` only. As a **map value** it
is inline in a HAMT node, which becomes a class-6 block re-copied whole by every path copy through it. As a **root
scalar** it does not read back past 1,016 bytes.

---

## 5. Evidence for the seams

DRAFT — from what is verified so far; finalised when Parts 2 and 3 are complete.

1. **Is `IPlain` implemented by every persistent type on every host?** No. Gaps: RRB vector, eve value array, user
   `eve-deftype`s, records, `HeapEveArray`, `ObjArray` (§2.2). And where it is implemented it does not always answer
   plain data: `eve.sorted`'s answers another slab-backed eve collection. On family B hosts the vector, set and list
   never reach it (the `:host-value` reader runs first).
2. **How many materialization paths bypass `IPlain`?** Fifteen listed in §3.3 so far.
3. **Constructing eve data outside a `swap!` today.** JVM: it succeeds, in the process's own heap slab; it can never be
   stored in an atom (refused at the root and nested), and is never reclaimed. ClojureScript: it succeeds in the module
   slab, which is every in-process atom's domain — and the open mmap domain's files when one is open — so it is storable
   by pointer and, on a disk domain, allocates unlogged in shared files.
4. **Does any type contract promise live mutability visible through materialization?** Yes: `EveArray` (atomics,
   `wait!`/`notify!`), `Obj`/`ObjArray`, tensor `mset!`, `eve-deftype` `^:mutable` / `^:volatile-mutable` fields, JS typed
   arrays in a swap. A deref returns such a type **live** when it is not `IPlain` (verified: a user type mutated through
   the value a plain deref returned changed the atom with no swap).
