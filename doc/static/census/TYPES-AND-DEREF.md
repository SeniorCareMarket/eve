STATUS: PARTIAL — banked early on the coordinator's order. DONE so far: `eve.atom` (all of `src/eve/atom.cljc`), the
allocator and both codecs, vector / map / set / list / `EveArray` / `HeapEveArray` / value array / `RSeq` / value buffer,
the read region and `eve.guard`. REMAINING: sorted (`sorted.cljc`, `rb_tree.cljc`), int-map, rrb-vector, dataset, tensor,
obj/ObjArray, the `adapters/types/*` + `adapters/coll/*` shells and the deftype constructor path (`sio.cljc`,
`types_js.cljc`), `shared_atom.cljs`, STM, `eve.thread.*`, network atoms, printing, `eve.tally` / `eve.retire`, the
`eve.journal-file` adapter API, the JVM probe run, and the closing "Evidence for the seams".

# Census — persistent types, materialization / deref paths, the store path

Tree: `/home/user/eve`, working tree at `f605cd8` = pin `4a92129` + `doc/static/STATUS.md` only
(`git diff --stat 4a92129 HEAD` → 1 file, `doc/static/STATUS.md`). Every `path:line` below is relative to
`/home/user/eve` and was read at this tree.

Provenance tags used in this file:

- **[read]** — read from source at this pin; not executed.
- **[ran-JVM]** — executed in this session on the JVM seat (OpenJDK 21.0.12, Clojure 1.12.0, classpath = `deps.edn`
  `:paths` + `test`, jars from the lane's scratchpad). No file of the repo was written by a probe; heap domains only.
- **[doc]** — a claim of the repo's own docs, cited, not re-derived.
- **not found** — searched, with where.

No ClojureScript, babashka, nbb, CLR, basilisp, jolt, clojerl, glojure, jank or VS runtime was executed (no `bb`,
no `clojure` CLI on the box); every statement about those hosts is **[read]**.

---

## 0. Corrections to the brief's premises (banked first)

| # | Premise in the brief | What the tree says | Where |
|---|---|---|---|
| C1 | "`src/eve/guard.clj` — a construction law / guard" | `eve.guard` is a **read** guard (a prototype, "jvm-heap-grade step 2"): inside `with-guard` an atom's deref answers a lazy proxy and a use after the extent closes throws. It guards nothing about construction. JVM only (`#?@(:bb [] :glj [] :clj [[eve.guard :as guard]])`). | `src/eve/guard.clj:1-21`, `:36-39`; `src/eve/atom.cljc:31`, `:933-962` |
| C2 | "what happens OUTSIDE a `swap!` (throws? falls back?)" | No constructor throws. Every constructor given no sio allocates in `alloc/current-sio` = `(or d/*domain-sio* (default-sio))`. The refusal comes later, at **store** time, when the value's domain is not the atom's. | `src/eve/deftype_proto/alloc.cljc:1713-1731`; store-time throws in §3.3 |
| C3 | "`overflow_test` … size limits" | `test/eve/overflow_test.cljc` pins **arithmetic** overflow (`+`/`-`/`*` on i64), nothing about block sizes. The size tests are `test/eve/big_value_test.cljc` and `test/eve/tier_repair_round2_test.clj`. | `test/eve/overflow_test.cljc:1-6` |
| C4 | "`VALUE_INLINE_MAX` 64 bytes" bounds an inline value | The constant exists and `encode-val-out-of-line` implements it, but **nothing in `src/` calls it** — only `test/eve/big_value_test.cljc:51-66`. `encode-val` writes every map value inline whatever its size ("Not yet what encode-val writes"). | `src/eve/deftype_proto/serialize.cljc:54-58`; `adapters/codec/byte-array/src/eve/codec.cljc:757-776`; `adapters/codec/js/src/eve/codec.cljs:1408-1436` |
| C5 | "records (tag 0x1A)" as a type of every host | `0x1A` is written and read by the **ClojureScript codec only**. The byte-array codec (JVM, bb, CLR, basilisp, jolt, clojerl, glojure, VS) has no `0x1A` arm: a record there satisfies `map?` and is written as a plain eve map (its type is lost), and a `0x1A` pointer read there falls to "Unknown EVE type tag". | `adapters/codec/js/src/eve/codec.cljs:116-141`, `:1011-1021`, `:1284-1293`; `adapters/codec/byte-array/src/eve/codec.cljc:711-714`, `:323-328`; `src/eve/deftype_proto/serialize.cljc:68-70` |
| C6 | docs: "a write plans `eve.retire/plan` … `retire/apply!`" (`doc/architecture.md:181-187`, `doc/memory-model.md:125`) | `swap-attempt!` at this pin calls neither. It holds the new root (`tally/hold-root!`), CASes, and drops the old root (`tally/drop-root!`); counts live on descriptor rows. `eve.retire` is still required and `retire/reach` serves `seal`. (To be completed when `tally.cljc` / `retire.cljc` are read.) | `src/eve/atom.cljc:964-1085`, `:1542`; `src/eve/deftype_proto/data.cljc:435-438` |

---

## 1. Host families (which definition a host runs)

Shared code is one `.cljc` per type; a host differs in **how its `eve.types` adapter realizes a type** and in **what a
registered collection reads back as** (`eve.types/reader-of`, called by `register-eve-type!`,
`src/eve/deftype_proto/macros.cljc:104-113`).

| Family | Hosts (`hosts.edn`) | `eve.types` adapter | `reader-of` answers | Codec |
|---|---|---|---|---|
| **A — lazy, host interfaces** | JVM, jolt; ClojureScript (Node, browser), nbb, cherry, scittle; ClojureCLR; basilisp; clojerl | `types/jvm` (`adapters/types/jvm/src/eve/types.clj:88-93`), `types/js` + `types/nbb` (`adapters/types/nbb/src/eve/types.cljs:218-223`), `types/clr` (`adapters/types/clr/src/eve/types.cljr:232-237`), `types/basilisp` (`adapters/types/basilisp/src/eve/types.lpy:900-905`), `types/beam` (`adapters/types/beam/src/eve/types.clje:412-417`) | `from-header` — the lazy slab-backed eve type | `codec/js` on CLJS/nbb/cherry/scittle; `codec/byte-array` elsewhere |
| **B — host value where registered** | babashka, glojure, jank, ValueScript | `types/bb` (`adapters/types/bb/src/eve/types.clj:287-293`), `types/glojure` (`adapters/types/glojure/src/eve/types.glj:171-176`), `types/jank` (`adapters/types/jank/src/eve/types.jank:217-222`), `types/vs` (`adapters/types/vs/src/eve/types.vs:200-204`) | `(or host-value from-header)` — the **host's own collection**, built eagerly, for every type that registers `:host-value` | `codec/byte-array` (jank also ships `adapters/codec/jank/src/eve/codec.jank`) |
| not eve memory | let-go, ClojureDart | no region / slab / coll / types root (`hosts.edn` `:lg`, `:cljd` comments) | — | — |

Which types register `:host-value` (so read as plain host data on family B, **without** going through `IPlain`):
vector (`src/eve/vec.cljc:973-987`), set (`src/eve/set.cljc:1074-1085`), list (`src/eve/list.cljc:423-439`). The map does
not (`src/eve/map.cljc:1797-1806`) — on family B a map is still the lazy `EveHashMap`.

Consequence on family B **[read]**: `read-root-value` (`src/eve/atom.cljc:781-791`) calls the registered header
constructor, so inside `swap!` the function receives a **plain host vector / set / list**, not an eve one, for a root or
nested value of those three kinds; whatever it returns is re-written whole by the store path. That is the
"materializing host" row of `doc/unification/HOST-CONTRACT.md:236-238` still in force for three types.

---

## 2. Part 1 — the persistent types

Legend for the columns that follow: **id/tag** = header type-id byte at block offset 0 / the 7-byte pointer tag
`[0xEE 0xDB tag off:i32]`; **sio** = `eve.deftype-proto.alloc/current-sio` unless a sio is passed.

### 2.1 Summary table (types read so far)

| Type | Defined (one definition, every host) | id / pointer tag · header | `IPlain` | Storable | Meta | Seq | Print |
|---|---|---|---|---|---|---|---|
| `EveVector` | `eve/deftype`, `src/eve/vec.cljc:391-598` | `0x12` / `0x12` · 24 B `[id][pad3][cnt][shift][root][tail][tail-len]` (`vec.cljc:34-42`, `:192-200`); each element in its own value block `[len:u32][EVE bytes]` (`:93-106`) | `(mapv d/eve->clj (ecore/seq this))` — a host vector, eager, recursive (`:541-542`) | `ISabStorable` → 7-byte pointer (`:525-528`); `IDirectSerialize` → offset (`:522-523`) | **none**: `(meta [_] nil)`, `(withMeta [this m] this)` (`:409-413`) | `lazy-seq`, one element per step, unchunked (`:399-407`) | registered `{:open "[" :close "]"}` (`:993`) |
| `EveHashMap` | `eve/deftype`, `src/eve/map.cljc:1082-1248` | `0xED` / `0x10` · 12 B `[id][flags][u16][cnt][root-off]` (`map.cljc:1027-1036`); HAMT nodes hold KVs **inline** `[klen][kbytes][vlen][vbytes]` (`:960-1010`) | `(into {} (map (fn [kv] [(eve->clj k) (eve->clj v)])) (ecore/seq this))` (`:1215-1216`) | pointer (`:1197-1200`) | **none** (`:1093-1097`) | **eager**: `hamt-seq` reduces the whole tree into a transient vector, then `seq`s it (`:1012-1021`, `:1088-1091`) | registered `:map` (`:1806`) |
| `EveHashSet` | `eve/deftype`, `src/eve/set.cljc:700-850` | `0xEE` / `0x11` · 12 B (`set.cljc:533-540`); elements are **keys**: flat bytes inline, never a pointer (`:53-58`) | `(into #{} (map d/eve->clj) (ecore/seq this))` (`:808-809`) | pointer (`:792-795`) | **none** (`:709-713`) | **eager** (`hamt-seq`, `:522-527`) | `{:open "#{" :close "}"}` (`:1091`) |
| `EveList` | `eve/deftype`, `src/eve/list.cljc:137-271` | `0x13` / `0x13` · 12 B `[id][pad3][cnt][head-off]`; cell `[next:i32][vlen:u32][EVE bytes]` (`list.cljc:5-6`, `:27-36`) | `(apply list (map d/eve->clj (ecore/seq this)))` (`:255-256`) | pointer (`:239-242`) | **none** (`:147-151`) | the list **is** its own seq (`(seq [this] (when (pos? cnt) this))`, `:145`); **`next` / `more` / `pop` each allocate a new 12-byte header in the slab** (`:157-161`, `:195-203`, `make-list` `:365-369`) | `{:open "(" :close ")"}` (`:445`) |
| `EveArray` (slab) | `eve/deftype`, `src/eve/array.cljc:252-399` | `0x1D` / written `0x1C` by `-sab-encode` (`:342-345`), `0x1D` by the byte-array codec's writers (`codec.cljc:731-739`); both read (`array.cljc:790-791`, `:830-837`) · 8 B `[id][subtype][u16][cnt:i32]` then raw LE elements (`:245-250`, `:405-419`) | a host **vector** of the elements (`:359-366`) — the typed-array-ness is lost | pointer; **mutable in place** (see 2.4) | **none** (`:289-293`) | `lazy-seq` (`:280-287`) | `#eve/array :int32 [..]` (`:334-337`, `:397`) |
| `HeapEveArray` (the brief's "JvmHeapEveArray") | `edt/deftype` (host type), `src/eve/array.cljc:865-950` | **no block**: fields `[cnt subtype-code backing]`, `backing` the host's primitive array | **none** (not `IPlain`, not `IsEve`, not `IEveRoot`) | `d/IBackingArray` (`:942-944`): the store path writes its elements as a `0x1D` block | none (no `IMeta`) | `lazy-seq` (`:883-889`) | `#eve/array …` (`:861-863`, `:924-928`) |
| eve value array `ValueArray` | `edt/deftype` (host handle `[sio off n]`), `src/eve/deftype/array.cljc:184-226` | `0x1F` / `0x1F` · `[0x1F][pad3][n:i32][kind:u8×n][tag:u8×n][pad to 4][payload:i32×n]` (`:14-23`) | **none** (and no `IsEve`) | pointer `0x1F` (`:191-194`) | none | lazy `map` over slots (`:173-175`) | `#eve/array [..]` (`:220-226`) |
| `RSeq` | `edt/deftype` (host type), `src/eve/rseq.cljc:10-35` | no block | none | none | **yes, on the instance** (field `mta`, `:11-14`) | itself | `( … )` (`:30-31`) |
| `ValueBuffer` | `eve.deftype/deftype` (host type), `src/eve/deftype/value_buffer.cljc:28-34` | no block of its own; holds a `ValueArray` | none | none (`->array` publishes a `ValueArray`, `:91-96`) | none | — | — |

Reader-conditional branches: there are **none** in these type bodies — one definition per type; the host split is in the
`eve.types` adapter (§1). The JVM-named interface groups a body lists (`java.util.List`, `java.util.Map`,
`clojure.lang.MapEquivalence`, `java.lang.Object`) are dropped or translated by each host's adapter (the comments at
`src/eve/vec.cljc:478`, `:585`; `src/eve/map.cljc:1128`, `:1218-1219`; `src/eve/set.cljc:811-812`, `:821-823`).

### 2.2 Hashing and equality (read so far)

| Type | `hasheq` | `hashCode` | `equiv` | `equals` |
|---|---|---|---|---|
| `EveVector` | `(dg/root-hash sio__ offset__ (fn [] (p/hash-ordered this)))` — the **host's** ordered Murmur3 hash of the decoded elements, computed once and **latched in a 48-byte side block in the slab** (`src/eve/vec.cljc:458-459`; `src/eve/digest.cljc:201-215`) | `(p/hash-ordered this)` (`vec.cljc:598`) | `sequential?` + count gate, then a digest verdict when both are `EveVector`, else element-wise `=` against `(nth other i)` (`vec.cljc:441-456`) | element-wise `Util/equals`, `IPersistentVector` only (`:587-597`) |
| `EveHashMap` | `(dg/root-hash … (p/hash-unordered this))` (`src/eve/map.cljc:1185-1186`) | `enc/map-hash-code` (`:1247-1248`) | digest verdict when both are `EveHashMap`, else `map-equiv?`: `(map? other)`, same count, every entry of `other` found by `hamt-get` and `=` (`:1057-1063`, `:1151-1156`) | `APersistentMap/mapEquals` (`:1245-1246`) |
| `EveHashSet` | `(dg/root-hash … (p/hash-unordered this))` (`src/eve/set.cljc:774-775`) | `(unchecked-int (reduce + 0 (map hash (.seq this))))` — sums `hash` (hasheq), not `.hashCode` (`:818-819`) | digest verdict, else `set-equiv?` (`eve-set?`, same count, each member found) (`:671-681`, `:756-761`) | `set-equiv?` (`:815-817`) |
| `EveList` | `(dg/root-hash … (p/hash-ordered this))` (`src/eve/list.cljc:188-189`) | `Murmur3/hashOrdered` (`:270-271`) | `sequential?` + count, digest verdict when both `EveList`, else walk (`:170-186`) | `.equiv` (`:265-269`) |
| `EveArray` | own formula `31*h + (hash elem)` seeded with the subtype, cached **on the instance** (`^:cached ^:mutable hash-cache__`, `src/eve/array.cljc:256`, `:315-325`) | `.hasheq` (`:399`) | `array=`: same subtype, count, elements — against another `IEveArray` only, **never equal to a host vector** (`:223-235`, `:331`) | `array=` (`:398`) |
| `HeapEveArray` | `array-hash`, uncached (`:855-859`, `:914-915`) | same (`:930`) | `array=` (`:921`) | `array=` (`:929`) |
| `ValueArray` | `(hash off)` — the block's offset, i.e. **identity** (`src/eve/deftype/array.cljc:217-218`) | `(hash off)` (`:225`) | `va=`: the same block (`:228-231`) | same |

What is hashed: never eve bytes. The HAMT's *trie* hash is Murmur3 over a key's EVE bytes (`eve.hamt-util/portable-hash-bytes`,
`src/eve/map.cljc:82-92`), but a collection's `hash` is the host's Clojure hash of its decoded elements
(`src/eve/platform.cljc:29-41`), so `(hash eve-coll)` equals `(hash plain-coll)` by construction on a host whose
`eve.core` hash is its core's **[read]**; `doc/unification/HOST-CONTRACT.md:275` (8.11) states the same.

The digest cache (`src/eve/digest.cljc:1-53`): the first `hash` or `=` of a map / set / vector / list root **allocates a
48-byte block in the value's own domain** (`slab/alloc! sio CACHE_SIZE`, `digest.cljc:118`) and CASes its offset into
the root header's descriptor POINTER column (`:122`). The digest itself is `SHA-256` over
`eve.codec/value->eve-bytes` — the codec's **flat form** — of the root (`:163-179`). On ClojureScript the JS codec has no
flat form (`adapters/codec/js/src/eve/codec.cljs:1470-1476` throws), so the digest verdict is always `:walk` there.

`(= eve-coll plain-coll)` both ways — to be confirmed by the JVM probe; from source: eve→plain goes through the `equiv`
bodies above; plain→eve relies on the host collection's own `equiv` meeting the eve type's host interfaces
(`java.util.Map` + `clojure.lang.MapEquivalence` on the map, `java.util.List`/`Sequential` on the vector and list,
`java.util.Set` on the set — `src/eve/map.cljc:1220-1237`, `src/eve/vec.cljc:394`, `:547-582`, `src/eve/set.cljc:833-846`).

### 2.3 Constructors and where they allocate (read so far)

Every public constructor either takes a sio or uses `default-sio` → `alloc/current-sio`
(`src/eve/deftype_proto/alloc.cljc:1726-1731`):

```clojure
(defn current-sio [] (or d/*domain-sio* (default-sio)))      ; alloc.cljc:1726-1731
(defn default-sio [] (if-let [f (host-op :default-sio)] (f) @process-heap-ctx))   ; alloc.cljc:1718-1724
(defdelay ^:private process-heap-ctx (make-jvm-heap-slab-ctx))                     ; alloc.cljc:1713-1716
```

`d/*domain-sio*` is bound only by `swap-attempt!` (`src/eve/atom.cljc:987-994`), to the attempt's `LoggingSlabIO`.

| Type | Public constructors | Allocates in |
|---|---|---|
| vector | `empty-vec` (`src/eve/vec.cljc:676-683`), `eve-vec` (`:685-689`), `vec-from-header` (`:671-674`, no allocation), block-level `build-vec` / `splice-vec` / `subvec-sharing` / `concat-vec` / `replace-range-vec` (`:922-970`, in the source vector's sio), aliases `empty-sab-vec` / `sab-vec` (`:822-824`), `->transient-vec` (`:655-658`) | given sio, else `current-sio` |
| map | `empty-hash-map` (`src/eve/map.cljc:1274-1279`), `hash-map` (`:1281-1297`, a leading `ISlabIO` arg is the sio), `into-hash-map` (`:1299-1307`), `hash-map-from-header` (`:1269-1272`), `->transient-map` (`:1387-1390`); the CLJS direct map encoder (`:1409-1414`) | given sio, else `current-sio` |
| set | `empty-hash-set` (`src/eve/set.cljc:947-952`), `hash-set` (`:954-963`), `hash-set-from-header` (`:942-945`), `->transient-set` (`:1027-1030`) | same |
| list | `empty-list` (`src/eve/list.cljc:376-381`), `eve-list` (`:383-387`), `list-from-header` (`:371-374`), aliases (`:390-391`) | same |
| `EveArray` | `eve-array` (`src/eve/array.cljc:472-504`), `eve-array-uninit` (`:506-517`), `int32-array`, `int32-array-from` (`:523-531`), `from-double-array` / `from-int-array` / `from-byte-array` (`:982-1002`), `eve-array-from-offset` (`:765-768`) | **JVM, bb, jolt: no slab at all** — `(heap-arrays?)` is true where `eve.encoding` has host typed arrays, and `eve-array` with no sio returns a `HeapEveArray` (`:489-497`, `:847-853`). Elsewhere, or with an explicit sio: the sio / `current-sio` |
| `ValueArray` | `make-array` (`src/eve/deftype/array.cljc:251-263`), `aclone` (`:287-296`) | `current-sio` (`:254`) — no sio parameter exists |
| `ValueBuffer` | `buffer` (`src/eve/deftype/value_buffer.cljc:36-40`), `->array` (`:91-96`) | through `make-array` |

What "outside a `swap!`" means per host **[read]**:

- **JVM family (JvmSlabCtx hosts: JVM, bb, jolt, CLR, basilisp, clojerl, glojure, jank, VS).** `host-op :default-sio` is
  unset, so the value lands in `process-heap-ctx`: one heap domain per process, six 1 MB classes + a 64 KB class 6 that
  grows (`src/eve/deftype_proto/alloc.cljc:1012-1035`). It is **not** any atom's domain — each named in-process atom has
  its own heap domain and all anonymous ones share another (`src/eve/atom.cljc:1615-1635`) — so storing it is refused
  (§3.3). Nothing frees it: there is no atom to retire it ("An array the buffer outgrew stays where it is, as a dropped
  value does outside an atom", `src/eve/deftype/value_buffer.cljc:14-15`; `doc/data-structures.md:513-516`).
- **ClojureScript / nbb / cherry / scittle.** `:default-sio` is `cljs-sio`, the one stateless token over the module
  slabs, and `:heap-domain-sio!` answers **the same token** for every in-process domain
  (`adapters/slab/module/src/eve/process_slab.cljs:824-859`, `:1363-1377`). So a value built outside a `swap!` is in the
  *same* slab as every in-process atom, the `same-domain?` test passes, and it **is storable by pointer**. When an mmap
  domain is open the module slabs *are* that domain's files (`:1301-1317`; "One mmap domain at a time per CLJS process",
  `:1107-1112`), so a constructor outside a `swap!` allocates **directly in the shared files**, unlogged.

The "deftype constructor guard" named at `src/eve/deftype_proto/data.cljc:557-560` — pending (the constructor expansion
in `src/eve/deftype_proto/sio.cljc` / `adapters/types/js/src/eve/types_js.cljc` is not read yet). The one reader of
`*parent-atom*` found so far that changes behaviour is the CLJS typed-array encoder's `has-slab?`
(`adapters/slab/module/src/eve/process_slab.cljs:1405-1410`), which **falls back** (an inline blob) rather than throws.

### 2.4 Mutability a type's contract already has (read so far)

| Surface | What is mutated in place | Where |
|---|---|---|
| `EveArray` `aset!`, `cas!`, `exchange!`, `add!`, `sub!`, `band!`, `bor!`, `bxor!`, `amap!`, `afill!`, `acopy!` | the array's slab block, by plain write or a CAS loop on the element's i32 word; `wait!` / `notify!` block on that word through the region | `src/eve/array.cljc:546-554`, `:570-624`, `:635-658`, `:690-727` |
| `HeapEveArray` same API | the host array, under `locking` | `src/eve/array.cljc:932-940` |
| `ValueArray` `aset`, `array-copy` | the block's slot — "written while it is new: by the code that made it" (a convention, not enforced) | `src/eve/deftype/array.cljc:272-278`, `:298-306` |
| transients | a transient-owned header and tail are written in place (`conj!-owned`) | `src/eve/vec.cljc:620-634` |
| JS typed arrays stored in an atom (CLJS) | decoded **inside a swap** as a *mutable view into the SAB*; outside, a copy | `adapters/codec/js/src/eve/codec.cljs:1031-1094`, `:1295-1346` |
| digest / hash cache | a side block allocated and latched by `hash` / `=` | `src/eve/digest.cljc:103-126`, `:201-215` |
| `EveList` traversal | a fresh header block per `next` / `more` / `pop` | `src/eve/list.cljc:157-161`, `:195-203` |

(eve-deftype `^:mutable` / `^:volatile-mutable` fields, tensor `mset!`, `eve.typed-view` — pending.)

---

## 3. Part 3 (written early, complete for `eve.atom` + codecs) — the store path

### 3.1 The entry: `resolve-new-ptr`

`swap!` → `mmap-swap!` (`src/eve/atom.cljc:1087-1104`) → `swap-attempt!` (`:964-1085`) → `resolve-new-ptr`
(`:852-876`), under `(binding [d/*domain-sio* lsio] …)`:

| The value `f` returned | What happens | Where |
|---|---|---|
| `nil` | `NIL_OFFSET` (the slot is empty / free) | `atom.cljc:861-862` |
| satisfies `d/IEveRoot`, same domain (`(identical? (alloc/sio-root vs) (alloc/sio-root sio))`, or `-eve-sio` nil) | **its own header offset — stored by pointer, nothing copied** | `atom.cljc:864-871` |
| satisfies `d/IEveRoot`, another domain | per-domain `:foreign-policy`: `:throw` (default) → `"swap!: Eve value was allocated in a different slab domain; construct it with the atom's domain sio (eve.atom/atom-sio)"`; `:copy` → `(resolve-new-ptr sio (d/eve->clj new-val) :throw)` (materialize, then write) | `atom.cljc:361-363`, `:866-870`, `:1142-1146` |
| a host typed array, or an `IBackingArray` (`HeapEveArray`) | `arr/write-host-array!` → one `0x1D` block | `atom.cljc:843-850`, `:874`; `src/eve/array.cljc:800-812` |
| a plain map / set / vector / list / seq | the registered direct writer (`ser/get-coll-writer`) when one is registered, else `ser/convert-to-eve` (the builders) | `atom.cljc:825-841`, `:875` |
| anything else (a scalar; a value with no eve bytes) | `alloc/value->scalar-block!`: a block `[0x01][EVE bytes]` | `atom.cljc:876`; `src/eve/deftype_proto/alloc.cljc:1494-1502` |

`scalar-leaf?` (`atom.cljc:793-823`) screens the protocol tests on the JVM only; it never changes the outcome.

### 3.2 Per-type writers and what dispatches

Registered through `register-eve-type!` (`src/eve/deftype_proto/macros.cljc:74-135`) into
`eve.deftype-proto.serialize`'s registries (`src/eve/deftype_proto/serialize.cljc:132-139`, `:272-276`):

| Kind | Direct writer (`coll-writers`) | Builder (`builders`: pred → ctor) |
|---|---|---|
| map | `-write-map-coll!` (`src/eve/map.cljc:1773-1794`): `hamt-assoc` per entry, freeing its own superseded path copies | `map?` → `into-hash-map` (`map.cljc:1802-1803`) |
| set | `-write-set-coll!` (`src/eve/set.cljc:1044-1065`) | `set?` → `into-hash-set` (`set.cljc:1087-1088`) |
| vector | `-write-vec-coll!` → `assemble-vec!` (`src/eve/vec.cljc:838-893`) | `vector?` → `eve-vec` (`vec.cljc:989-990`) |
| list | `-write-list-coll!` (`src/eve/list.cljc:406-420`) | `seq?` → `eve-list` (`list.cljc:441-442`) |
| array | `:array` → `write-host-array!` (`src/eve/array.cljc:814-815`) | `EveArray` instance → **a copy** into the receiving sio (`array.cljc:770-787`) |

The dispatch is **not** `ISabStorable` on the byte-array hosts. `value+sio->eve-bytes`
(`adapters/codec/byte-array/src/eve/codec.cljc:668-745`) is a `cond` in this order: scalar → inline bytes (`:679-682`);
`d/IEveRoot` → domain check, `ser/stored-piece!`, 7-byte pointer whose tag is read from the block's own header byte
(`:692-706`); `map?` / `set?` / `list?` / `vector?`-or-`sequential?` → the direct writer, 7-byte pointer (`:711-729`);
`d/IBackingArray` / host array → `0x1D` block (`:731-739`); else a **reference** `0x15` into this process's table, or a
refusal in a shared domain (`:741-745`; `src/eve/deftype_proto/serialize.cljc:220-259`).

On ClojureScript `serialize-value-a` / `serialize-val` (`adapters/codec/js/src/eve/codec.cljs:572-681`, `:698-803`)
dispatch on `d/ISabStorable` first (`-sab-encode` at `:586-587`; `encode-eve-pointer` at `:712-713`), then `IRecord`
(`:590-592`), a plain map through the direct map encoder (`:595-596`), typed arrays (`:669-670`),
`d/IDirectSerialize` (`:673-674`), then `convert-to-eve` (`:677-681`).

What every `-sab-encode` writes: a 7-byte pointer, never flat bytes (`src/eve/vec.cljc:527`, `src/eve/map.cljc:1199`,
`src/eve/set.cljc:794`, `src/eve/list.cljc:241`, `src/eve/array.cljc:344-345`, `src/eve/deftype/array.cljc:193`).

**Flat bytes exist already, for keys.** `encode-key` = `value->eve-bytes` writes a collection as a self-contained blob,
no pointers, canonical order — `FLAT_MAP 0xED`, `FLAT_SET 0xEE`, `FLAT_VEC 0xEF`
(`adapters/codec/byte-array/src/eve/codec.cljc:422-467`, `:622-629`, `:778-782`;
`adapters/codec/js/src/eve/codec.cljs:683-696`, `:838-891`; `src/eve/deftype_proto/serialize.cljc:80-91`). A map key and
a set element that is a collection is stored that way and **decodes to plain host collections**, eagerly
(`codec.cljc:290-321`). The same flat form is the input of the root digest (`src/eve/digest.cljc:163-179`). In the flat
encoder an eve collection is walked through the host predicates (`map?` → `flat-map->eve-bytes` iterating its seq),
**not** through `IPlain` — `IPlain` is consulted only where the predicates are blind (`codec.cljc:631-639`, the jank arm).

### 3.3 Same domain, another domain, plain value

- **Same domain: stored by pointer.** Root: `atom.cljc:871`. Nested: `codec.cljc:701-706` (byte-array),
  `codec.cljs:62-79` (JS). The store reports `ser/stored-piece!` → `alloc/keep-owned!`
  (`src/eve/deftype_proto/alloc.cljc:1441-1453`): every logging scope in the chain stops freeing within itself, and the
  commit's holds count the piece (`tally/hold-root!`, `atom.cljc:1001`).
- **Another domain: refused.** Three throw sites:
  1. root — `src/eve/atom.cljc:869-870` (message above), unless the domain's `:foreign-policy` is `:copy`;
  2. nested, byte-array codec — `adapters/codec/byte-array/src/eve/codec.cljc:697-700`:
     `"An Eve value allocated in a different slab domain cannot be put in this one; build it inside swap! (or with the atom's domain sio, eve.atom/atom-sio)"`;
  3. nested, JS codec — `adapters/codec/js/src/eve/codec.cljs:70-77`, the same message (and
     `adapters/codec/jank/src/eve/codec.jank:700`).
  Tested: `test/eve/jvm_atom_test.clj:276-280`.
- **Plain host value: built.** Through the writers of §3.2, allocating in `lsio` (the attempt's log).

### 3.4 The sequence inside `swap-attempt!` (`src/eve/atom.cljc:964-1085`)

| Line | Step |
|---|---|
| 979-980 | `refresh-slabs!`; pin the domain epoch for this thread (`pin-epoch!`, nested by depth, `:751-758`) |
| 981 | `(binding [tally/*touches* (atom #{})] …)` |
| 983-984 | load the slot's root pointer; `refresh-slabs!` again behind the load |
| 985 | `lsio (alloc/logging-sio sio)` — a fresh `LoggingSlabIO` over the domain's root sio (`alloc.cljc:1077-1099`, `:1423-1426`): every `alloc!` through it is added to a set, every `free!` removed |
| 986 | `old-val (read-root-value lsio old-ptr)` — the **lazy** value, reading through `lsio` |
| 987-989 | `new-val (binding [d/*parent-atom* ds, d/*domain-sio* lsio] (apply f old-val args))` |
| 992-994 | `new-ptr (binding [d/*domain-sio* lsio] (resolve-new-ptr lsio new-val foreign-policy))` |
| 995-999 | `new-ptr == old-ptr` → `stop-logging!`, `[:ok new-val]`: nothing published, nothing retired |
| 1001 | `tally/hold-root! sio new-ptr` — the slot's hold, before the CAS |
| 1016-1019 | durable, no journal: `alloc/sync-touched!` over the touched descriptor rows + the unforced note + `logged-allocs` |
| 1020 | `mem/-cas-i32! root-r ptr-off old-ptr new-ptr` |
| 1023 | won: epoch `+1` |
| 1024-1039 | J1 journal domain: `tally/drop-root!` old first, then `journal-note-commit!` with the attempt's touched set |
| 1040-1061 | otherwise: journal batch note or root-range force; `tally/drop-root!` old; force the drop's rows |
| 1062-1063 | `stop-logging!`; `[:ok new-val]` |
| 1064-1084 | lost: read `logged-allocs`, `stop-logging!`, `tally/drop-root!` **new**, `tally/free-unheld!` the attempt's blocks, force (non-journal), `[:retry nil]` |
| 1085 | `finally (unpin-epoch! ds)` |

`mmap-swap!` (`:1087-1104`) runs the attempt inside `with-domain-write` (in-flight count, or the exclusive hold on a
lock-only host, `:288-299`), calls `try-flush-retires!` after an `:ok` (`:1099`; throttle 50 ms / 64 entries,
`:62-65`, `:890-898`), backs off and retries up to `MAX_SWAP_RETRIES` = 1000 (`src/eve/deftype_proto/data.cljc:622`).

The scope log (`alloc/scope-sio`, `alloc.cljc:1428-1432`) is a `LoggingSlabIO` whose `inner` is the sio it was given
(so, under a swap, the attempt's log): a transient's allocations are recorded in both, `free-owned!` (`:1460-1469`)
frees only what that scope allocated and only while no existing piece was stored through it (`keep`).

(`eve.tally` and `eve.retire` internals — pending.)

### 3.5 Disk (journal) domains

`src/eve/atom.cljc:452-475` **[read]**, verbatim: "Records carry COMMIT METADATA ONLY — never values:
`[magic i32][len i32][epoch i32][table-len i32][table bytes][crc32 i32]`". The table is the root region's raw atom-table
range, `MAX_ATOM_SLOTS × ATOM_SLOT_SIZE` = 256 × 8 = 2,048 bytes: one root pointer per atom per epoch. So the journal
appends **neither value bytes, block images nor ops**, and it does not care how the new value's blocks were produced —
only that every page the record's pointers reach was forced before the record lands (`journal-flush!`, `:496-559`: under
the journal file's lock, sweep → `jf/journal-append!` → `jf/journal-force!`).

- J1 (`:journal-batch` ≤ 1, the default): the sweep is exactly the publishing attempt's touched set
  (`alloc/sync-touched!` over `tally/*touches*` + `logged-allocs`, `:1037-1039`, `:519-520`). **A block the commit
  references that was neither allocated through the attempt's log nor touched by its holds is not in that sweep.**
- Batch / checkpoint: a whole-domain sweep (`alloc/sync-sio!`, `:521-522`).
- Retention floor: `:floor` in the journal map (`:693-707`), set at recovery (`:619-626`), advanced at each flush to the
  previous flush's epoch (`:529-555`) and at checkpoint to the anchor's epoch (`:649-672`); `flush-zero-queue!` frees only
  below `(min readers floor)` (`:413-424`).
- Adapter calls the shared code makes: `jf/open-journal`, `with-journal-lock`, `journal-append!`, `journal-record`,
  `journal-record-of`, `journal-force!`, `journal-scan`, `journal-truncate!`, `journal-write-at!`, `journal-close!`
  (`:516-527`, `:612-647`, `:663-676`, `:694`). (Adapter file itself — pending.)

### 3.6 Size limits (read so far)

| Limit | Value | Where |
|---|---|---|
| fixed block classes | 32 / 64 / 128 / 256 / 512 / 1024 B; anything larger → class 6 (coalescing) | `src/eve/deftype_proto/data.cljc:73-95`, `:300-311`; `src/eve/deftype_proto/alloc.cljc:551-554` |
| slab-qualified offset | `[class:3 | block:29]` in one i32; class 6's "block index" is a byte offset → 2^29 bytes addressable **(to verify in `coalesc.cljc`)** | `alloc.cljc:60-85` |
| heap domain | 1 MB per class and a 64 KB class 6 to start; **both grow** by replacement (`grow-heap-class!`, `grow-coalesc-once!`), up to 20 doublings per allocation, bounded by `:mem-budget` when set | `alloc.cljc:382-531`, `:686-700`, `:1012-1035` (the docs' "fixed … spills to the next", `doc/architecture.md:118-121`, describes the older behaviour) |
| HAMT node KV total | header field is a u16; a node whose KV bytes pass 65,535 caches 0 and is scanned | `src/eve/map.cljc:206`; `test/eve/big_value_test.cljc:1-4` |
| map value inline | unbounded in practice (see C4); six 12,000-char values in one node are tested | `test/eve/big_value_test.cljc:17-42` |
| vector element | its own block `[len:u32][bytes]`, any size (class 6 past 1020 B) | `src/eve/vec.cljc:93-100` |
| symbol segment | 255 UTF-8 bytes, else refused | `adapters/codec/byte-array/src/eve/codec.cljc:401-409` |
| scalar at an atom root | `scalar-block->value` reads `(dec (d/slab-size class))` bytes — for a class-6 block `slab-size` is **1**, so it reads **0 bytes** and decodes `nil`. From source a root scalar whose encoding exceeds 1,023 bytes (a 1,020+-char string) does not read back. **[read] — to be confirmed by the JVM probe.** | `src/eve/deftype_proto/alloc.cljc:1494-1522`; `src/eve/deftype_proto/data.cljc:85-91` |
| swap retries | 1,000 | `data.cljc:622` |
| atoms per domain | 255 named (256 slots, slot 0 the registry) | `data.cljc:401`; `src/eve/atom.cljc:1397-1424` |

Bearing on ingesting one large packed buffer in one swap: as a **typed array / `IBackingArray`** it is one `0x1D` block
in class 6 (`write-host-array!`); as a **vector element or list cell** it is one block; as a **map value** it is inline
in a HAMT node, which then itself becomes a class-6 block and is re-copied whole by every path copy through that node.

---

## 4. Part 2 (written early, complete for `eve.atom`) — materialization / deref / value delivery

### 4.1 `eve.atom` — one atom type, three deref modes

There is one atom implementation, `MmapAtom` (`src/eve/atom.cljc:1174-1193`), and the domain `MmapAtomDomain`
(`:1206-1230`), over two kinds of domain state: in-process (`open-heap-domain!`, `:727-737`) and disk
(`open-mmap-domain!`, `:678-725`). Both share every function below.

| Path | The caller receives | Via `IPlain`? | Where |
|---|---|---|---|
| `@a` outside any region | **plain host data**: `(d/eve->clj (read-root-value sio ptr))` under a pin | yes (`eve->clj`) | `plain-deref`, `atom.cljc:918-931` |
| `@a` inside `eve.alpha/$` | the **lazy slab-backed value** (`read-root-value`), domain pinned until the outermost `$` exits | no — nothing materialized at the read | `region-deref`, `:904-916`; `mmap-deref` `:955-962` |
| `$`'s result | plain: `(rr/run f d/eve->clj)` | yes | `$*`, `:1134-1139`; `src/eve/read_region.cljc:35-47` |
| `@a` inside `eve.guard/with-guard` (JVM only, prototype) | a `LazyMap` / `LazyColl` proxy over the lazy value; raw when `*proxy-same-domain?*` is false and the atom is in the extent's receiving domain; **`guard*` returns its result as is** — "lazy values in it are dead once this returns" | no | `guard-deref`, `:933-953`; `src/eve/guard.clj:70-85`, `:183-190` |
| `swap!` — `f`'s argument | the **lazy** value, read through the attempt's `LoggingSlabIO` | no | `:985-989` |
| `swap!` — return value | **whatever `f` returned, unconverted**: `[:ok new-val]` → `result`. If `f` returned an eve value (`(swap! a assoc :k v)` does), the caller holds a slab-backed value **after the pin is released** | **no — a bypass** | `:1063`, `:1101-1102` |
| `reset!` — return value | the argument `v` itself (`(constantly v)`) | n/a | `:1182` |
| `compare-and-set!` | boolean; the comparison is `(= (d/eve->clj cur) old-val)` — the **whole root is materialized on every attempt** | yes | `mmap-compare-and-set!`, `:1106-1120` |
| `read-pinned a f & args` | `(apply f lazy-value args)`'s result **as is** — "f's result must not keep the value" | no — a bypass by contract | `:1148-1163` |
| `add-watch`, validators | **not implemented**: `MmapAtom` lists `IDeref`, `IAtom`, `IEveAtom`, `IPrintWithWriter` only | — | `:1174-1193`; `doc/atom-domains.md:245` |
| registry reads (`lookup-or-create-mmap-atom!`, `registered-name`) | plain map (`plain-deref ds 0`) | yes | `:1442-1443`, `:1473`, `:1500` |
| `register-name!` | inside a swap: `ecore/get` / `ecore/assoc` on the lazy registry | no | `:1426-1440` |
| `version` | `[epoch root-pointer]` — two words, no value | — | `:1514-1522` |
| `seal` | `{:ptr :name-hash :blocks :digest}`: an FNV-1a fold over the **raw bytes of every block** `retire/reach` finds | no — reads bytes | `:1526-1553` |
| printing an atom | `#eve/shared-atom {:id i :idx i}` — the slot, not the value | — | `:1190-1193` |

**[ran-JVM]** (the lane's `smoke.clj`, 3.9 s): `@a` of `{:a 1 :b [1 2 3] :c #{:x :y} :d "hello"}` is a
`clojure.lang.PersistentArrayMap`; inside `swap!`, `f`'s argument is `eve.map.EveHashMap` and its `:b` an
`eve.vec.EveVector`.

Root dispatch for every read: `read-root-value` (`atom.cljc:781-791`) reads the header byte; `0x01` → 
`alloc/scalar-block->value` (codec decode, plain); otherwise the registered header constructor `(ctor sio ptr)`.

### 4.2 What `eve->clj` does with a value that is not `IPlain`

`src/eve/deftype_proto/data.cljc:515-543`: scalars pass; `(satisfies? IPlain v)` → `(-plain v)`; `(satisfies? IsEve v)`
→ **`v` itself, still slab-backed**; then the host predicates `map?` / `set?` / `vector?` / `list?` / `sequential?`
rebuild a host collection element by element; `:else v`.

So a deref hands back a **live slab-backed object** for every type that is not `IPlain` — after the pin has been
released. `doc/data-structures.md:196-198` says so of user types: "deref answers the instance itself — a reference into
the domain, meaningful while the domain holds it". (The list of such types is completed below once the remaining type
files are read; from what is read: `ValueArray` — neither `IPlain` nor `IsEve`, not a host collection by predicate on
the JVM → `:else v`.)

### 4.3 Bypasses of `IPlain` found so far

1. `swap!`'s return value (`src/eve/atom.cljc:1063`, `:1102`).
2. `read-pinned` (`:1148-1163`) and `eve.guard/guard*` (`src/eve/guard.clj:70-85`): the caller's function result is
   returned unconverted.
3. Family B hosts' `:host-value` readers — `vec->host-value` (`src/eve/vec.cljc:973-981`), `set->host-value`
   (`src/eve/set.cljc:1074-1079`), `list->host-value` (`src/eve/list.cljc:423-433`): an eager host collection built by
   the type's own loop at **every read of a pointer**, in or out of a swap.
4. The codecs' flat decode (`0xED` / `0xEE` / `0xEF`): map keys and set elements that are collections become plain host
   collections inside `eve-bytes->value` / `deserialize-element`
   (`adapters/codec/byte-array/src/eve/codec.cljc:290-321`; `adapters/codec/js/src/eve/codec.cljs:1098-1132`).
5. The byte-array codec's flat **encode** of an eve collection used as a key or digested: walks it by host predicates
   (`codec.cljc:622-629`), `IPlain` only on jank (`:631-639`).
6. `eve->clj`'s own fallthrough arms: a non-`IPlain` value rebuilt by `map?` / `set?` / `vector?` / `sequential?`
   (`data.cljc:538-542`) or returned live (`:537`, `:543`).
7. ClojureScript records: `(dissoc (reduce-kv assoc {} sab-m) :eve/record-tag)` then `map->Record`
   (`codec.cljs:1011-1021`, `:1284-1293`) — the fields are **not** recursively materialized.
8. ClojureScript typed arrays: a copy outside a swap, a live SAB view inside one (`codec.cljs:1031-1094`).
9. `seal` (`atom.cljc:1526-1553`): raw block bytes.
10. `EveArray`'s `IBulkAccess` (`-as-double-array` / `-as-int-array`, a host-array copy) and `array-values`
    (`src/eve/array.cljc:384-390`, `:817-821`).
11. `:foreign-policy :copy` — goes *through* `eve->clj`, listed because it is a second materialization site inside the
    store path (`atom.cljc:868`).

(More to come from `shared_atom.cljs`, STM, threads, network atoms, printing.)

---

## 5. Evidence for the seams

PENDING — written when Parts 1 and 2 are complete.
