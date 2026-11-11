STATUS: PARTIAL — banked early. Remaining: (Part 1) §10 DataScript index-node bodies (persistent-sorted-set `Node`/`Leaf`/`BTSet`), `ISlabIO` protocol citation, exact `hosts.edn` / `atom.cljc` line numbers, cross-check against `doc/api-guide.md`, `doc/architecture.md`, `doc/memory-model.md`; (Part 2) dataset/tensor/array test coverage, `columnar_profile.clj`, class-6 size limit, gap greps (Arrow/DLPack/group-by/join/strings), final wording of "Evidence for the assumptions".

# Census — the deftype generation pipeline and the in-domain columnar stack

Tree: `/home/user/eve`, HEAD `f605cd8`; `git diff --stat 4a92129 HEAD` shows one changed file (`doc/static/STATUS.md`),
so every source line cited here is the pin `4a92129` (release 0.9.3.1). Method: source read only — nothing was run
(no build, no test, no REPL). Every claim is tagged by its citation; "by reading" marks an inference from two or more
cited sites that no test or doc states in one place. Paths are relative to `/home/user/eve`.

---

# Part 1 — the deftype generation pipeline

## 1.1 The macros, and the path per host

### The macros that exist

| # | Macro | Defined at | Expansion fn | What it makes |
|---|---|---|---|---|
| 1 | `eve.deftype/eve-deftype` | `src/eve/deftype.cljc:24-27` | `eve.deftype.macros/eve-deftype*` `src/eve/deftype/macros.cljc:54-153` | a slab-backed type (the user-facing one) |
| 1a | `eve.alpha/deftype` (public alias) | `src/eve/alpha.cljc:27-30` | expands to `(eve.deftype/eve-deftype …)` | same as 1 |
| 2 | `eve.deftype/deftype` | `src/eve/deftype.cljc:29-34` | `eve.deftype.macros/deftype*` `src/eve/deftype/macros.cljc:35-48` → `eve.types/plain-type` | a plain type on the host (not in eve memory) |
| 3 | `eve.deftype/eve-extend-type` | `src/eve/deftype.cljc:36-40` | `eve-extend-type*` `src/eve/deftype/macros.cljc:159-177` | `extend-type` with field bindings read from the slab |
| 3a | `eve.alpha/extend-type` (public alias) | `src/eve/alpha.cljc:32-35` | expands to 3 | — |
| 4 | `eve.deftype/extend-host-vectors` | `src/eve/deftype.cljc:42-47` | `extend-host-vectors*` `src/eve/deftype/macros.cljc:179-183` | `extend-protocol` over the host's vector types |
| 5 | `eve.deftype-proto.macros/deftype` | `src/eve/deftype_proto/macros.cljc:33-68` | `eve.types/slab-type` | the LOWER layer: a slab type with fields `[sio__ offset__ …cached]`; used by eve's own collections only |
| 6 | `eve.deftype-proto.macros/host-deftype` | `src/eve/deftype_proto/macros.cljc:22-27` | `eve.types/host-type` | a plain host type written with JVM names |
| 7 | `eve.deftype-proto.macros/register-eve-type!` | `src/eve/deftype_proto/macros.cljc:74-135` | — | registration boilerplate of a core collection |

So: **two slab-type macros with two separate registries and two different layout rules** (1 and 5), two plain-type
macros (2 and 6), and the public name `eve.alpha/deftype` (1a). There is no macro named `deftype-proto`; that is a
namespace prefix (`eve.deftype-proto.*`).

Naming trap: "`eve/deftype`" means macro 1 in user docs (`doc/data-structures.md:187`, alias `eve` = `eve.alpha`) and
macro 5 inside `src` (`src/eve/map.cljc:29,1082`, `src/eve/array.cljc:37,252`: alias `eve` = `eve.deftype-proto.macros`).

### One expansion fn, one adapter seam, two emission families

`eve-deftype*` is one portable `.cljc` function for every host (`src/eve/deftype/macros.cljc:1-5`: "one
implementation for every host that expands them; what a host realizes its own way … is its eve.types"). It parses
fields, computes the layout, claims the type id, registers the type in the compile-time registry, then calls the
host's `eve.types/eve-type` with a context map (`src/eve/deftype/macros.cljc:131-137`).

Every `eve.types` adapter ends in one of two shared emitters:

- **Family A — `eve.deftype-proto.sio/eve-type`** (`src/eve/deftype_proto/sio.cljc:172-299`): "every host whose types
  reach eve memory through ISlabIO — an instance holds sio__ and its block's offset: the JVM, babashka, ClojureCLR,
  basilisp, jolt and clojerl" (`sio.cljc:2-4`); glojure, jank and VS adapters call it too.
  Instance fields: `[<cached + host-local fields> ^long slab-off__ sio__]` (`sio.cljc:179-180,242`).
- **Family B — `eve.types-js/eve-type`** (`adapters/types/js/src/eve/types_js.cljc:283-498`): "the realization every
  ClojureScript host shares (shadow-cljs's compiler, nbb …): an instance holds its cached fields and its block's
  offset in the module slab (offset__), its fields read through the slab's DataView" (`types_js.cljc:2-5`).
  Instance fields: `[<cached + host-local fields> offset__]` (`types_js.cljc:291,294,425`) — **no sio on the instance**.

| Host (`hosts.edn` key) | Where the macro runs | `eve.types` adapter → emitter | What a type is |
|---|---|---|---|
| JVM (`:jvm`) | same JVM, `src/eve/deftype.cljc` | `adapters/types/jvm/src/eve/types.clj:65-68` → A with hooks `:26-38` | `deftype T [… ^long slab-off__ sio__]` with JVM interfaces (ClojureScript names translated by `v/jvm-translate-protos`, `src/eve/deftype_proto/vocab.cljc:336-393`) |
| ClojureScript compiled (`:cljs`, `:browser`) | the compiler's JVM: `adapters/core/cljs-compiler/src/eve/deftype.cljs:1-11` does `(:require-macros [eve.deftype])`, so `src/eve/deftype.cljc` is loaded as Clojure, with the JVM's `eve.types` | `adapters/types/jvm/src/eve/types.clj:17-21` detects `(:ns env)`, `:68` → B with `js/compiler-twist` (`types_js.cljc:572`) | cljs `deftype T [… offset__]`; JVM names translated to cljs protocols (`types_js.cljc:293`) |
| nbb (`:nbb`) | SCI, in process, `src/eve/deftype.cljc` | `adapters/types/nbb/src/eve/types.cljs:209` → B with twist `:197-202` (`:declare-type? false`, missing protocols from `eve.core`) | the same cljs `deftype`, interpreted |
| scittle (`:scittle`) | SCI in a browser | same roots as nbb (`hosts.edn` `:scittle` row) | per the row's comment "the slab collections (types, regions, slabs) have not been run on it" |
| cherry (`:cherry`) | cherry's compiler | row lists `adapters/types/js/src` only — which namespace is `eve.types` there was not traced (not found — looked in `hosts.edn`, `adapters/core/cherry`) | — |
| babashka (`:bb`) | SCI, in process | `adapters/types/bb/src/eve/types.clj:273` → A with hooks `:232-255` | sci `deftype` whose interfaces are translated onto `eve.core`'s protocols (`bb-translate-protos` `:121-227`); built through `T__new` (`:factory-via-var? true`); marked `IsEve` (`:212-227`) |
| ClojureCLR (`:cljr`) | same process | `adapters/types/clr/src/eve/types.cljr:218` → A, hooks `:189-201` | `deftype` with CLR-translated interfaces (`cljr-translate-protos` `:84-184`) |
| basilisp (`:lpy`) | same process | `adapters/types/basilisp/src/eve/types.lpy:882` → A, hooks `:822-845` | basilisp `deftype` (a Python class), `lpy-translate-protos`, field renames `:797-803` |
| jolt (`:jolt`) | same process | the JVM's adapter (`hosts.edn` `:jolt` row lists `adapters/types/jvm/src`) → A | the JVM's `deftype` |
| clojerl (`:clje`) | same process | `adapters/types/beam/src/eve/types.clje:396` → A, hooks `:346-362` | clojerl `deftype` on its protocols; no mutable fields — a mutable field holds a cell, hash caches are never set (`:228-344`) |
| glojure (`:glj`) | same process | `adapters/types/glojure/src/eve/types.glj:158` → A, hooks `:112-138` | the completion's `deftype` = a record extended to protocols; `^:eve/map` for map types |
| jank (`:jank`) | same process | `adapters/types/jank/src/eve/types.jank:203` → A, hooks `:168-185` | the completion's `deftype`, one C++ object; all interfaces onto `eve.core` |
| ValueScript (`:vs`) | — | `adapters/types/vs/src/eve/types.vs:186` → A, hooks `:154-169` (written) | **the expansion does not run on VS at this pin**: `hosts.edn` `:vs` comment "eve/deftype's expansion waits on a builder carrying EM4's held half"; `doc/data-structures.md:242`; `doc/hosts/valuescript.md:5-6,25-27,77-79` |
| let-go (`:lg`), ClojureDart (`:cljd`) | — | no `types` root in their rows | no eve types ("Not yet eve memory", `hosts.edn` `:lg`/`:cljd` comments) |

Per-host differences inside family A are confined to the hooks (`sio.cljc:5-22`): `:normalize`, `:define`, `:build`,
`:factory-via-var?` (eve-deftype) and `:slab-prepare`, `:object-boilerplate?`, `:slab-define` (lower layer).

## 1.2 The accepted surface

Signature: `(eve-deftype TypeName [fields…] & body)` — **no options map** (`src/eve/deftype.cljc:24-27`).

Field parsing — `eve.deftype.registry/parse-field` (`src/eve/deftype/registry.cljc:115-158`):

| Declaration | `:type-class` | Slot bytes | Notes |
|---|---|---|---|
| `^:int8`, `^:uint8` | `:primitive` | 4 ("padded to 4 for alignment") | `registry.cljc:96-97,106` |
| `^:int16`, `^:uint16` | `:primitive` | 4 | `registry.cljc:107` |
| `^:int32`, `^:uint32` | `:primitive` | 4 | `registry.cljc:108` |
| `^:float32` | `:primitive` | 4 | `registry.cljc:109` |
| `^:float64` | `:primitive` | 8, aligned to 8 | `registry.cljc:110,182-186` |
| `^:SomeOtherName` (any other un-namespaced hint) | `:eve-type` | 4 (offset, −1 = nil) | `registry.cljc:146-149`; comment says "will be validated at macro expansion time" — no validation exists; the read emits a call to `<Hint>__from-slab` (`sio.cljc:43`, `types_js.cljc:131`), so the referenced type must be resolvable in the expanding namespace |
| (no hint) | `:serialized` | 4 (offset of a `[len:i32][EVE bytes]` block, −1 = nil) | `registry.cljc:111,145` |
| `^:host`, or `^:mutable` with no type hint | `:host` | 0 — not in eve memory | `registry.cljc:140-144`; `nil` when read back (`macros.cljc:68-70`) |
| `^:int32 ^:eve/pointer f` | `:primitive`, `:pointer? true` | 4 | a block offset the retire walk follows (`registry.cljc:119-121,155`) |
| `^:int32 ^:ref f` | `:primitive`, `:ref? true` | 4 | another instance's offset, counted by the tally (`registry.cljc:121-123,156-158`) |

Mutability (`registry.cljc:125-129`): `^:volatile-mutable` → `:volatile-mutable`; `^:mutable` → `:mutable`; else
`:immutable`. Metadata keys ignored when looking for the hint: `:mutable :volatile-mutable :tag :host :ref
:unsynchronized-mutable :eve/field :eve/pointer :file :line :column :end-line :end-column :source` and every
namespaced key (`registry.cljc:134-137`). Only the **first** remaining key is the hint (`registry.cljc:138`); a second
is dropped silently.

Field parse result (`registry.cljc:150-158`): `{:name "f" :sym f :mutability … :type-hint kw-or-nil :type-class …
:pointer? bool :ref? bool}`; `compute-layout` adds `:offset` and `:size` (`registry.cljc:187-189`).

Body: a flat list of protocol/interface symbols each followed by method forms, parsed by
`eve.deftype-proto/parse-protocol-impls` into `[{:protocol sym :methods [{:name :args :body}]}]`
(`src/eve/deftype_proto.cljc:23-41`). Names may be ClojureScript's (`ICounted/-count`) or the JVM's
(`clojure.lang.Counted/count`); a name neither table knows passes through (`src/eve/deftype_proto/vocab.cljc:102-109,354`).
Inside bodies, `(set! field v)` and `(cas! field expected new)` on declared fields are rewritten
(`sio.cljc:84-149`, `types_js.cljc:180-256`); `set!` of an immutable field throws at expansion (`sio.cljc:95-96`).

The lower-layer macro 5 has a different field parser (`src/eve/deftype_proto/macros/registry.cljc:59-80`): it knows
`^:cached` (a host field, no slab bytes) and does **not** know `^:host`, `^:ref`, `^:eve/pointer`.

Primitive field hints in actual use: only `^:int32`. A grep for `^:float64|^:float32|^:int8|^:uint8|^:int16|^:uint16|^:uint32`
followed by a field name across `src test contract-test examples bench docs` returns nothing — no type in the tree
declares a float or sub-word deftype field.

## 1.3 Layout computation

`eve.deftype.registry/compute-layout` (`src/eve/deftype/registry.cljc:160-189`), called at
`src/eve/deftype/macros.cljc:90`:

1. offset starts at **4** — the header `[type-id:u8][name-hash:u24 LE]` (`registry.cljc:166`; header written at
   `sio.cljc:270-273`, `types_js.cljc:469-475`).
2. fields in **declaration order**; nothing is reordered.
3. a `:host` field takes no bytes and gets no `:offset` (`registry.cljc:173-175`).
4. size = 4 for an `:eve-type`, else `field-size` of the hint (`registry.cljc:179-181`); `nil` hint (serialized) → 4.
5. **only `:float64` is aligned** (to 8) (`registry.cljc:182-186`); everything else lands at the running offset (always a multiple of 4).
6. `:total-size` = the final offset, **not rounded** (`registry.cljc:169-171`).
7. the block is `(eve.slab-io/alloc! sio__ total-size)` (`sio.cljc:269`; cljs `types_js.cljc:457-459`); the size class is
   `size->class-idx` (`src/eve/deftype_proto/data.cljc:300-311`): ≤32→0, ≤64→1, ≤128→2, ≤256→3, ≤512→4, ≤1024→5, else −1
   (the overflow class).

Sub-word primitives occupy a 4-byte slot but are read and written as u8/u16 at the slot's first byte(s)
(`src/eve/deftype_proto.cljc:63-70,100-105`).

Worked example — `IntMapBranch` (`src/eve/deftype/int_map.cljc:140-148`, its own comment: "Layout:
[type-id:1][name-hash:3][prefix:4][bit-offset:4][epoch:4][c0:4]...[c15:4]"):

| bytes | field | class |
|---|---|---|
| 0 | type-id (u8) | header |
| 1–3 | name-hash (u24 LE) | header |
| 4–7 | `prefix` `^:int32` | primitive |
| 8–11 | `bit-offset` `^:int32` | primitive |
| 12–15 | `epoch` `^:int32` | primitive |
| 16–79 | `c0`…`c15` `^:int32 ^:eve/pointer` (16 × 4) | primitive, pointer |
| total | 80 bytes → class 2 (128-byte block) | |

Second example — `Dataset [^:int32 nrows col-order col-map]` (`src/eve/dataset.cljc:50-52`): `nrows`@4, `col-order`@8
(serialized slot), `col-map`@12 (serialized slot); total 16 → class 0 (32-byte block), plus one `[len][bytes]` block per
non-nil serialized field.

The lower-layer layout (`src/eve/deftype_proto/macros/registry.cljc:40-57,86-108`) differs: true sizes (int8 = 1,
int16 = 2), natural alignment per field, total rounded up to 4, `^:cached` fields skipped.

## 1.4 Type identity

(Coordinator-verified facts, cited not re-derived: `src/eve/deftype/registry.cljc:12-45,47-66` — `type-id = 0x40 +
(hash type-key) mod 173`, `name-hash = hash & 0xFFFFFF`, `hash = fold-hash-int(xxh3-128(encode-key type-key))`.)

Additional facts:

- `type-key` is the string `"<ns-name>/<TypeName>"` (`src/eve/deftype/macros.cljc:95`).
- **Two compile-time atoms**, both in `eve.deftype.registry`:
  - `registry` (`registry.cljc:12`) — keyed by the type's **simple name string** (`(name type-name)`, `macros.cljc:106`),
    entry `{:type-id :name-hash :type-tag :total-size :fields :type-key}` (`macros.cljc:106-112`), e.g. for the test type
    `Apple`: `{"Apple" {:type-id 0x4E :name-hash 0xD3B566 :type-tag "eve/Apple" :total-size 12 :fields [{:name "n"
    :type-class :primitive :type-hint :int32 :offset 4 :size 4 …} {:name "label" :type-class :serialized :offset 8 :size 4 …}]
    :type-key "eve.deftype.type-id-test/Apple"}}` (ids from `test/eve/deftype/type_id_steps_test.cljc:31-36`; shape by
    reading). `:fields` holds the slab fields only (`macros.cljc:91,111`). Two types with one simple name in different
    namespaces overwrite each other here.
  - `claimed` (`registry.cljc:70`) — `{[type-id name-hash] → type-key}`; `claim-type-id!` throws when another type-key
    already holds the pair (`registry.cljc:72-82`).
- The registry's only reader is `eve-extend-type*` (`src/eve/deftype/macros.cljc:167`; grep `lookup-type` over `src`,
  `adapters`, `test`). `eve-deftype*` reads nothing back from it.
- The lower layer has its own pair: `registry` and `derived-ids` (`src/eve/deftype_proto/macros/registry.cljc:7,15`);
  an id is derived only when the namespace has no `(def T-type-id …)` (`src/eve/deftype_proto/macros.cljc:52-59`), and
  there a shared type-id alone is refused (no name-hash arm) (`macros/registry.cljc:17-26`). The core collections
  declare their own: `EveHashMap-type-id 0xED` (`src/eve/map.cljc:49`), `EveArray-type-id 0x1D` (`src/eve/array.cljc:112`).
- Stability: the derivation uses eve's own hash so "every host derives it alike" (`registry.cljc:19-23`); pinned as
  literals on every host by `test/eve/deftype/type_id_test.cljc:29-32` (Apple and Peach both `0x4E`) and step by step by
  `test/eve/deftype/type_id_steps_test.cljc:30-55`; a host that disagreed (jank) was fixed (`CHANGELOG.md:49-60`).
- "DECLARED BREAK (Oct 25 2026)": the derivation replaced Murmur3-of-`String.hashCode`; "values persisted under the old
  ids do not read back under the new ones, and there is no migration" (`registry.cljc:28-37`).
  `doc/unification/FORMAT-DECISIONS.md:193-221` (§7, dated 2026-10-16) still describes the older `hash` derivation — the
  doc is stale against the code.
- **0xEC overflow cannot happen**: the id is a residue mod 173 (`registry.cljc:47-48,59-62`). Types that land on one
  byte are told apart by the 24-bit name hash in header bytes 1–3 (`src/eve/deftype_proto/serialize.cljc:294-324`).
  A 7-byte pointer carries only the type-id byte; the name hash is read from the target block
  (`serialize.cljc:319-324`).
- Renaming a type or its namespace changes its identity: "rename a type or its namespace and the values written under
  the old name no longer read as it" (`doc/data-structures.md:385-392`).

## 1.5 What a reader knows at runtime

Nothing about a type is stored in the domain. The reader side is three process-local atoms in
`eve.deftype-proto.serialize`, filled by calls the macro emits and which run when the defining namespace loads:

| Registry | Atom | Filled by (emitted call) | Read by |
|---|---|---|---|
| constructors | `deftype-constructors` `serialize.cljc:297`, and through it `type-constructors` / `header-constructors` `:132,136` | `(eve.deftype-proto.serialize/register-deftype-constructor! type-id name-hash T__from-header)` — `sio.cljc:284-287`; cljs `types_js.cljc:493-496` | atom deref `read-root-value` (`src/eve/atom.cljc`, ~`:781-791`), codecs (`adapters/codec/byte-array/src/eve/codec.cljc:323-328`) |
| pointing fields | `deftype-layouts` `serialize.cljc:332` | `(eve.deftype-proto.serialize/register-deftype-layout! type-id name-hash [[offset :ref|:value] …])` — `src/eve/deftype/macros.cljc:140-146` | `eve.retire/deftype-children` `src/eve/retire.cljc:118-128` via `ser/deftype-layout` `serialize.cljc:340-347` |
| reference fields | `type-refs-registry` `serialize.cljc:353` | `(eve.deftype-proto.serialize/register-type-refs! type-id name-hash [[offset :deftype|:vval] …])` — `macros.cljc:150-153` | `eve.tally/type-layout` `src/eve/tally.cljc:203-210` |

The emitted layout call, quoted (`src/eve/deftype/macros.cljc:140-146`):

```clojure
(list 'eve.deftype-proto.serialize/register-deftype-layout! type-id name-hash
      (vec (keep (fn [f] (case (:type-class f)
                           :eve-type [(:offset f) :ref]
                           :serialized [(:offset f) :value]
                           :primitive (when (:pointer? f) [(:offset f) :ref])
                           nil))
                 slab-fields)))
```

What is registered is only *which offsets hold references* — not field names, primitive types, sizes or the total
size. A process that has not loaded the type's namespace:

- deref of a root of that type throws `"mmap-atom: unknown root value type-id"` (`src/eve/atom.cljc`, `read-root-value`);
- a nested pointer throws `"Unknown EVE type tag"` (`adapters/codec/byte-array/src/eve/codec.cljc:323-328`);
- the tally holds the block "as a leaf and never freed here — leak over use-after-free" (`src/eve/tally.cljc:47-49,212-219`);
- `eve.retire` sees no children (`src/eve/retire.cljc:121-128`: `(if (vector? layout) … [])`).

Not found: any persisted type table (looked in `src/eve/deftype_proto/serialize.cljc`, `src/eve/deftype_proto/data.cljc:338-444`
root layout, `doc/internals.md:365-399`).

## 1.6 Everything the macro emits for one type

Host-independent, in `eve-deftype*` (`src/eve/deftype/macros.cljc:130-153`): `(do <t/eve-type ctx> <register-deftype-layout!> <register-type-refs!>)`.

### Family A (`src/eve/deftype_proto/sio.cljc:246-299`)

| # | Emitted form | Line |
|---|---|---|
| 1 | `(declare ->T T__from-slab)` (+ `T__new` when `:factory-via-var?`) | `sio.cljc:248` |
| 2 | the host `deftype` through the `:define` hook: fields `[cached/host fields… ^long slab-off__ sio__]`, body = boilerplate + transformed user impls; plus a `print-method` when the body has `IPrintWithWriter` | `sio.cljc:251` (fields `:179-180,242`; body `:244`; print `:161-166`) |
| 2a | boilerplate `ISabpType/-sabp-type-key` → the type-key string (always) | `sio.cljc:199-200` |
| 2b | `IEveRoot/-root-header-off` → `slab-off__` (unless provided) | `sio.cljc:206-208` |
| 2c | `IEveSio/-eve-sio` → `sio__` (unless provided) | `sio.cljc:211-213` |
| 2d | `clojure.lang.IHashEq/hasheq` → `(hash slab-off__)` (unless provided) | `sio.cljc:216-220` |
| 2e | `java.lang.Object` `equals` (same type and same `slab-off__`), `hashCode`, `toString` `"#eve/T {:offset N}"` (unless provided) | `sio.cljc:223-234` |
| 3 | `(def T__new ->T)` when `:factory-via-var?` | `sio.cljc:252` |
| 4 | `(defn T__from-slab [sio__ slab-off__] …)` — builds an instance, reading the **cached** fields once | `sio.cljc:255-260` |
| 5 | `(defn ->T ([args…]) ([sio__ args…]))` — alloc `total-size`, write 4 header bytes, write every slab field | `sio.cljc:265-277` |
| 6 | `(defn T__from-header [sio header-off] …)` + `register-deftype-constructor!` | `sio.cljc:284-287` |
| 7 | `(defn T-offset [inst])` | `sio.cljc:290-291` |
| 8 | `(def T-type-id <id>)` | `sio.cljc:294` |
| 9 | `(def T-nil -1)` | `sio.cljc:297` |
| 10 | `T` | `sio.cljc:299` |

### Family B (`adapters/types/js/src/eve/types_js.cljc:414-498`)

| # | Emitted form | Line |
|---|---|---|
| 1 | `(declare ->T T__new T__from-slab)`; `(declare T)` when `:declare-type?` | `types_js.cljc:418,422` |
| 2 | `(def T-type-id <id>)` | `types_js.cljc:423` |
| 3 | `(deftype T [cached/host fields… offset__] boilerplate… impls…)` | `types_js.cljc:425-427` |
| 3a | `ISabpType` (always) | `types_js.cljc:317-318` |
| 3b | **`IDirectSerialize/-direct-serialize` → `offset__`** (always) | `types_js.cljc:320-322` |
| 3c | `IEveRoot` (unless provided) | `types_js.cljc:326-328` |
| 3d | `IEveSio/-eve-sio` → `(eve.process-slab/cljs-sio)` (unless provided) | `types_js.cljc:335-337` |
| 3e | **`ISabStorable`**: `-sab-tag` `:eve-deftype/<T>`, `-sab-encode` `(eve.codec/encode-sab-pointer type-id offset__)`, `-sab-dispose` frees each serialized field block then the block (unless provided) | `types_js.cljc:340-355` |
| 3f | `IHash/-hash` → `(hash offset__)` (unless provided) | `types_js.cljc:358-359` |
| 3g | `IEquiv/-equiv` same type and same offset (unless provided) | `types_js.cljc:362-367` |
| 3h | `IPrintWithWriter` (`"#eve/T {:offset N}"`, or `toString` when the type has one) (unless provided) | `types_js.cljc:370-379` |
| 4 | `(def T__new ->T)` | `types_js.cljc:433` |
| 5 | `(defn T__from-slab [off] …)` | `types_js.cljc:436-444` |
| 6 | `(defn ->T ([args…]) ([sio__ args…]))` | `types_js.cljc:453-479` |
| 7 | `(defn T-offset [inst])` | `types_js.cljc:482-483` |
| 8 | `(def T-nil -1)` | `types_js.cljc:486` |
| 9 | `(register-sab-fast-tag! :eve-deftype/T <id>)` | `types_js.cljc:489-490` |
| 10 | `(register-deftype-constructor! <id> <name-hash> (fn [_sio off] (T__from-slab off)))` | `types_js.cljc:493-496` |
| 11 | `T` | `types_js.cljc:498` |

### Auto-generated protocol matrix

| Protocol / behaviour | Family A (JVM, bb, CLR, lpy, jolt, clje, glj, jank) | Family B (cljs, nbb) |
|---|---|---|
| `ISabpType` (type key string) | yes `sio.cljc:199-200` | yes `types_js.cljc:317-318` |
| `IEveRoot`, `IEveSio` | yes | yes |
| `IDirectSerialize` | **no** | yes `types_js.cljc:320-322` |
| `ISabStorable` | **no** | yes `types_js.cljc:340-355` |
| `IPlain` | **no** | **no** |
| `IsEve` | no — except babashka, whose adapter adds it (`adapters/types/bb/src/eve/types.clj:212-227`) | no |
| hash | `(hash slab-off__)` — identity of the block | `(hash offset__)` |
| equality | same type and same offset | same type and same offset |
| print | `toString` `#eve/T {:offset N}`; `print-method` only when the body gives `IPrintWithWriter` | `IPrintWithWriter` |
| field accessor fns | none emitted; a cached field is a real field of the host type, read with `eve.host/field` (`doc/data-structures.md:405-409`) | none; `(.-f x)` |
| mutators | none emitted as fns; `set!`/`cas!` only inside method bodies | same |
| retire-layout + tally-refs registration | yes (`macros.cljc:140-153`) | yes |
| type-id constant `T-type-id` | yes `sio.cljc:294` | yes `types_js.cljc:423` |
| pointer-tag registration `register-sab-fast-tag!` | no (the byte-array codec reads the tag from the block header, `adapters/codec/byte-array/src/eve/codec.cljc` IEveRoot arm) | yes `types_js.cljc:489-490` |

### Where a sister-type emission would hook in

| Hook | Site | In scope there |
|---|---|---|
| H1 — host-independent (one edit for every host) | the `(list 'do …)` in `eve-deftype*`, `src/eve/deftype/macros.cljc:130-153` — append a form beside the two `register-…!` forms | `env`, `type-name`, renamed `fields` + `body` (`:86`), all parsed fields with `:offset`/`:size` (`:88-90`), `slab-fields`/`live-fields`/`inst-fields` (`:91-93`), `total-size`, `type-key` (`:95`), `type-id` + `name-hash` (`:98`), raw `parsed-protos` as written (`:118`), `ctor-name`/`ctor-args` (`:121-122`) |
| H2 — family A | `eve.deftype-proto.sio/eve-type`'s result, `sio.cljc:246-299` (or the `:define` hook per host, `sio.cljc:251`) | the ctx keys (`sio.cljc:175-178`) and the host hooks; **the normalized impls** `(normalize parsed-protos)` (`:184`) — i.e. the per-host protocol translation the sister also needs |
| H3 — family B | `eve.types-js/eve-type`'s result, `types_js.cljc:414-498` | the ctx keys (`types_js.cljc:287-289`) and the twist; translated impls (`:293`) |
| H4 — per adapter | each `eve.types/eve-type` (table in §1.1) | whatever the adapter adds (bb's `IsEve`, clojerl's `host-math-calls` wrap `types.clje:378-396`) |

Two facts that bear on H1–H3:

1. The context map handed to the adapter (`macros.cljc:131-137`) does **not** carry the raw `body` or the full `fields`
   vector; it carries `:parsed-protos`, `:inst-fields`, `:live-fields`, `:slab-fields`, `:field-map`.
2. The field-read generators are private and hard-wired to their memory: family A emits `eve.slab-io/read-*` on `sio__`
   and `slab-off__` (`jvm-emit-field-read` `sio.cljc:30-48`; `src/eve/deftype_proto.cljc:56-92`); family B emits DataView
   reads on `slab-dv__`/`slab-base__` resolved from the **process's module slab** (`eve.process-slab/resolve-dv!`,
   `types_js.cljc:264-277,436-439`). In family A an instance is parametric in its `sio__`; in family B it is not.

## 1.7 Field classes in practice

| Class | Write | Read | Codec |
|---|---|---|---|
| primitive | `proto/jvm-write-fn-for-type` `src/eve/deftype_proto.cljc:94-135` (`write-u8!`/`write-u16!`/`write-i32!`; float64 as two i32 words `:119-125`) — cljs DataView `setXxx` `types_js.cljc:84-111` | `proto/jvm-read-fn-for-type` `deftype_proto.cljc:56-92` — cljs `types_js.cljc:55-82` | none (raw little-endian) |
| eve-type (`^:T`) | the instance's header offset, −1 for nil: `(d/-root-header-off v)` `sio.cljc:70-78`; cljs `(.-offset__ v)` `types_js.cljc:153-162` | i32 offset → `(T__from-slab sio__ off)`, nil for −1 `sio.cljc:39-44`; cljs `types_js.cljc:128-134` | none |
| serialized (no hint) | `eve.deftype.slab-runtime/slab-write-serialized!` `src/eve/deftype/slab_runtime.cljc:34-55`: `(codec/encode-value sio val)`, a new block `[len:i32][bytes]`, its offset in the slot; nil → −1 | `slab-read-serialized` `slab_runtime.cljc:22-32`: `(codec/decode sio raw)` | `eve.codec` (the host's) |
| host | the instance's own field | the instance's own field; nil in an instance read back | — |

What a serialized field can hold: "any eve value, in eve's encoding" (`src/eve/deftype/macros.cljc:67`). Through
`encode-value` (`adapters/codec/byte-array/src/eve/codec.cljc`, `value+sio->eve-bytes`): a scalar inline; an existing
slab value of the same domain as its 7-byte pointer; a host map/set/list/vector written into the slab and pointed at;
a host typed array or `HeapEveArray` copied into a `0x1D` block; anything else **by reference** (tag 0x15,
process-local) and refused in a shared (disk) domain. So one serialized field costs the 4-byte slot plus a separate
block of at least 4+3 bytes, and for a collection a further header and node blocks.

Cached vs live (`src/eve/deftype_proto/vocab.cljc:453-467`): an immutable field that is not a `^:T` reference is read
once when the instance is built from memory and kept on the host object (`cached?`); a mutable field or a `^:T`
reference is read from memory at each method call (`live?`).

`IDirectSerialize` (`src/eve/deftype_proto/data.cljc:473-474`): one method, `-direct-serialize`, answering the
instance's block offset — the offset half of a 7-byte pointer.

| Implementors | Site |
|---|---|
| `EveHashMap` | `src/eve/map.cljc:1194-1195` |
| `EveVector` | `src/eve/vec.cljc:522-523` |
| `EveList` | `src/eve/list.cljc:236-237` |
| `EveHashSet` | `src/eve/set.cljc:789-790` |
| `EveArray` | `src/eve/array.cljc:339-340` |
| `ValueArray` | `src/eve/deftype/array.cljc:190` |
| every eve-deftype — family B only | `adapters/types/js/src/eve/types_js.cljc:320-322` |
| (a second protocol of the same name in the legacy module slab) | `adapters/slab/module/src/eve/data.cljs:149-150` |

| Callers | Site |
|---|---|
| `encode-eve-pointer` (tag from `-sab-tag`, offset from `-direct-serialize`) | `adapters/codec/byte-array/src/eve/codec.cljc:37-47`, `adapters/codec/jank/src/eve/codec.jank:73-81`, `adapters/codec/js/src/eve/codec.cljs:62-…` |
| `encode-fresh-pointer` | `adapters/codec/js/src/eve/codec.cljs:49-60` |
| the JS codec's "Eve types — already in shared memory" arms | `adapters/codec/js/src/eve/codec.cljs:673-674,795-796` |

On the byte-array codec the general value path does not use it: it takes the offset from `IEveRoot` and the tag from the
block's own header byte (`adapters/codec/byte-array/src/eve/codec.cljc`, the `(satisfies? d/IEveRoot v)` arm).

## 1.8 Mutable fields

- `^:mutable` + a slab type: `(set! f v)` is rewritten to a plain in-place `eve.slab-io/write-*!` into the instance's
  block (`sio.cljc:84-104`; cljs DataView write `types_js.cljc:141-168,184-201`). Documented as "Mutable via `set!`
  (single-worker), in eve memory" (`doc/data-structures.md:416`).
- `^:volatile-mutable`: cljs reads/writes with `Atomics.load`/`Atomics.store` on the slab's `Int32Array` and `cas!` with
  `Atomics.compareExchange` (`types_js.cljc:59-66,88-95,203-241`); family A `cas!` is `eve.slab-io/cas-i32!` and is
  "only supported on :int32 fields on JVM" (`sio.cljc:106-135`, the throw at `:115-116`); `cas!` on a serialized field
  throws (`sio.cljc:113-114`). Documented as "Atomic via `set!`/`cas!` (cross-worker safe)" (`doc/data-structures.md:418`).
- `^:mutable` with no slab type is host-local — "On the instance only (as `^:host`)" (`doc/data-structures.md:417`,
  `registry.cljc:140-144`).
- A reference field (`^:T`, serialized, `^:ref`, `^:eve/pointer`) written in place also runs the tally's store rule
  (`slab-stored!`: hold new, drop old) (`sio.cljc:97-104`; `src/eve/deftype/slab_runtime.cljc:64-74`; `src/eve/tally.cljc:30-31`).
- Visibility: a live field is read from memory at each method call (`vocab.cljc:463-467`), and deref of a user type
  answers the instance itself (§1.9), so an in-place mutation is seen through every handle on the block, including
  handles obtained by an earlier `@`. Cached (immutable) fields are a snapshot taken when the handle was built.
- Not found: a documented contract for how an in-place mutation interacts with `eve->clj`, epochs or `seal` (looked in
  `doc/data-structures.md:394-418`; `doc/memory-model.md` and `doc/api-guide.md` still to be cross-checked).
- Tests: `test/eve/jvm_deftype_cas_test.clj:14-40` (JVM `cas!`, 4 threads contending), `test/eve/deftype_test.cljs:57-135`
  (cljs mutable + volatile + CAS), `test/eve/tally_test.cljc:116-126` (`Holder [^:mutable ^:Box box ^:volatile-mutable note]`).

## 1.9 What `eve->clj` returns for a user eve-deftype instance

The instance itself. `eve->clj` (`src/eve/deftype_proto/data.cljc:515-543`) dispatches `(satisfies? IPlain v)` first; no
path auto-generates `IPlain` (§1.6), so a user type falls through to `:else v` — or, on babashka, to the
`(satisfies? IsEve v) v` arm (`data.cljc:526-537`). Doc: "A type of your own has no host collection to materialize to, so
deref answers the instance itself — a reference into the domain, meaningful while the domain holds it"
(`doc/data-structures.md:196-198`; also `:272-276`).

By reading (not stated in a doc): a user type that implements a host collection interface and gives no `IPlain` is
descended on the hosts whose predicates see it — `(map? v)`, `(set? v)`, `(vector? v)`, `(list? v)`, `(sequential? v)`
come before `:else` (`data.cljc:538-542`).

Types that do give `IPlain`: `Dataset` (`src/eve/dataset.cljc:59-61`), `NDBuffer` (`src/eve/tensor.cljc:64-79`),
`PersistentIntMap` (`src/eve/deftype/int_map.cljc:656-657`), rb-tree `SortedSet` (`src/eve/deftype/rb_tree.cljc:524-525`),
`EveSortedMap`/`EveSortedSet` (`src/eve/sorted.cljc:470-471,545-546`).

Tests that read a user type back from an atom and call its own methods on the result of `@`:
`test/eve/user_ns_root_test.clj:36-37,41-42` (JVM, across a close and reopen of a disk domain),
`test/eve/deftype/type_id_test.cljc:42-49` (every host).

## 1.10 Inventory of uses

### Slab types via `eve.deftype/eve-deftype` (macro 1)

| Type | Site | Fields |
|---|---|---|
| `Dataset` | `src/eve/dataset.cljc:50-52` | `^:int32 nrows`, `col-order`, `col-map` |
| `NDBuffer` | `src/eve/tensor.cljc:53-56` | `^:int32 elem-offset`, `data`, `shape`, `strides` |
| `RBNode` | `src/eve/deftype/rb_tree.cljc:65` | `^:int32 color`, `^:RBNode left`, `^:RBNode right`, `value` |
| `SortedSet` | `src/eve/deftype/rb_tree.cljc:475` | `^:RBNode root`, `mta` |
| `IntMapLeaf` | `src/eve/deftype/int_map.cljc:122` | `^:int32 key`, `value` |
| `IntMapBranch` | `src/eve/deftype/int_map.cljc:142-148` | `^:int32 prefix`, `^:int32 bit-offset`, `^:int32 epoch`, `^:int32 ^:eve/pointer c0`…`c15` |
| `IntMapBinaryBranch` | `src/eve/deftype/int_map.cljc:173` | `^:int32 ^:eve/pointer neg-off`, `^:int32 ^:eve/pointer pos-off` |
| `PersistentIntMap` | `src/eve/deftype/int_map.cljc:589` | `^:int32 ^:eve/pointer root-off`, `^:int32 epoch`, `mta` |
| `SortedEntry` | `src/eve/sorted.cljc:70` | `k`, `v` |
| `SortedNode` | `src/eve/sorted.cljc:75` | `^:int32 prio`, `^:int32 size`, `^:SortedNode left`, `^:SortedNode right`, `^:SortedEntry entry` |
| `EveSortedMap` | `src/eve/sorted.cljc:393` | `^:SortedNode root`, `cmp`, `mta` |
| `EveSortedSet` | `src/eve/sorted.cljc:481` | `^:SortedNode root`, `cmp`, `mta` |
| RRB `Vector` | `src/eve/rrb_vector/rrbt.cljc:313` | `^:int32 cnt`, `^:int32 shift`, `root`, `tail`, `meta`, `^:mutable __hash` (host-local) |
| RRB `VectorNode` | `src/eve/rrb_vector/nodes.cljc:22` | `^:host edit`, `arr` |
| `Box`, `Holder` | `test/eve/tally_test.cljc:116,126` | `label`; `^:mutable ^:Box box`, `^:volatile-mutable note` |
| `Apple`, `Peach` | `test/eve/deftype/type_id_test.cljc:21,25` | `^:int32 n`, `label` |
| `Rank`, `Tagged` | `test/eve/identity_test.cljc:55,68` | `^:int32 n`; `label`, `n` |
| `RetireCell`, `RetireNode`, `RetireBox` | `test/eve/jvm_atom_retire_test.clj:28,38,58` | `head`, `^:RetireCell tail`; `k`, `^:RetireNode left`, `^:RetireNode right`; `label`, `data` |
| `Box` (fixture) | `test/ds11_fixture/box.cljc:13` | `^:int32 version`, `items` |
| `Counter`, `Point`, `LabeledPoint`, `AtomicCounter` (through `eve.alpha/deftype`) | `test/eve/deftype_test.cljs:20,24,31,38` | `^:mutable ^:int32 count`; `^:int32 x ^:int32 y`; `^:int32 x ^:int32 y label`; `^:volatile-mutable ^:int32 value` |
| `CasCounter` (through `eve.alpha/deftype`) | `test/eve/jvm_deftype_cas_test.clj:14` | `^:volatile-mutable ^:int32 value` |
| `Node`, `Leaf`, `BTSet` (persistent-sorted-set port) | `contract-test/lib/me/tonsky/persistent_sorted_set.cljc:281,337,424` | `keys`, `pointers`; `keys`; `root`, `^:int32 shift`, `cnt`, `comparator`, `meta` |
| `Datom`, `DB` (DataScript port) | `contract-test/lib-ds/datascript/db.cljc:548,1409` | `e a v tx idx`; `schema eavt aevt avet max-eid max-tx rschema meta` |
| `Counter` (doc sample) | `doc/data-structures.md:187` (+ `eve/extend-type` at `:452`) | `^:mutable ^:int32 count`, `label` |

### Slab types via the lower layer `eve.deftype-proto.macros/deftype` (macro 5)

| Type | Site | Fields |
|---|---|---|
| `EveHashMap` | `src/eve/map.cljc:1082` | `^:int32 cnt`, `^:int32 root-off` |
| `EveHashSet` | `src/eve/set.cljc:700` | `^:int32 cnt`, `^:int32 root-off` |
| `EveVector` | `src/eve/vec.cljc:391-392` | `^:int32 cnt`, `^:int32 shift`, `^:int32 root`, `^:int32 tail`, `^:int32 tail-len` |
| `EveList` | `src/eve/list.cljc:137` | `^:int32 cnt`, `^:int32 head-off` |
| `EveArray` | `src/eve/array.cljc:252-256` | `^:int32 cnt`, `^:cached subtype__`, `^:cached elem-shift__`, `^:cached atomic?__`, `^:cached ^:mutable hash-cache__` |

### Plain host types via `eve.deftype/deftype` (macro 2) and `host-deftype` (macro 6)

`HeapEveArray [cnt subtype-code backing]` `src/eve/array.cljc:865`; `ValueArray [sio off n]` `src/eve/deftype/array.cljc:184`
(a handle on a `0x1F` block); `ValueBuffer` `src/eve/deftype/value_buffer.cljc:28`; `TransientIntMap`
`src/eve/deftype/int_map.cljc:859`; `TransientSortedMap`/`TransientSortedSet` `src/eve/sorted.cljc:615,644`;
`Obj`/`ObjArrayRow`/`ObjArray` `src/eve/obj/impl.cljc:187,326,347`; `MmapAtom`/`MmapAtomDomain` `src/eve/atom.cljc:1174,1206`;
`RSeq` `src/eve/rseq.cljc:10`; `Future`/`Promise` `src/eve/thread/future.cljc:32,41`; `RemoteEntry` `src/eve/thread/cell.cljc:191`;
`RRBChunkedSeq`/`Transient` `src/eve/rrb_vector/rrbt.cljc:68,1223`. Macro 6: `TransientEveHashMap` `src/eve/map.cljc:1365`,
`TransientEveVector` `src/eve/vec.cljc:637`, `TransientEveHashSet` `src/eve/set.cljc:1012`.

None found in `examples/`, `bench/`, `capstone/`, `docs/stm-ants/` (grep `deftype|defrecord`: `docs/stm-ants/ants-original.clj:35,48`
are stock `defrecord`s; examples only *use* `eve.deftype.int-map`).

### DataScript on Eve (to be completed)

A database is one `DB` eve-deftype with eight **serialized** fields (`contract-test/lib-ds/datascript/db.cljc:1409`); its
three indexes `eavt`/`aevt`/`avet` are `BTSet` eve-deftypes of the persistent-sorted-set port
(`contract-test/lib/me/tonsky/persistent_sorted_set.cljc:424`), whose nodes are `Node [keys pointers]` / `Leaf [keys]`
(`:281,337`); a datom is a `Datom [e a v tx idx]` eve-deftype with five serialized fields (`db.cljc:541-548`: "Datom is an
eve-deftype — it lives inside the index BTSets, inside DBs, inside Eve atoms, so it must be a slab value").
`docs/datascript-eve/GUIDE.md:23-45`. Recorded cost: Eve-DS writes 32×–275× stock DataScript on the JVM, with a journaled
commit per swap (`doc/performance.md:24-49`).

---

# Part 2 — the columnar / numeric stack

## 2.1 EveArray

- Element subtypes (`src/eve/array.cljc:53-101`; codes in `src/eve/deftype_proto/serialize.cljc:105-116`):

  | code | keyword | bytes | atomic ops |
  |---|---|---|---|
  | 1 | `:uint8` | 1 | yes |
  | 2 | `:int8` | 1 | yes |
  | 3 | `:uint8-clamped` | 1 | no |
  | 4 | `:int16` | 2 | yes |
  | 5 | `:uint16` | 2 | yes |
  | 6 | `:int32` | 4 | yes (+ band/bor/bxor, wait/notify) |
  | 7 | `:uint32` | 4 | yes |
  | 8 | `:float32` | 4 | no |
  | 9 | `:float64` | 8 | no |

  Codes `0x0A`/`0x0B` (BigInt64/BigUint64) exist for typed-array encoding (`serialize.cljc:115-116`) but
  `type-kw->subtype` has no 64-bit integer element type (`array.cljc:72-87`).
- Block layout, type-id `0x1D`: `[type-id:u8 @0][subtype:u8 @1][pad:u16 @2-3][cnt:i32 @4-7][data @8+]`
  (`array.cljc:245-250,405-419`; `HEADER_SIZE` 8 `:47`).
- Alignment: data starts at +8 with "no alignment padding — DataView handles unaligned" (`array.cljc:408`). Blocks of
  the six bitmap classes are 32–1024 bytes in a 16-aligned data region (`src/eve/deftype_proto/data.cljc:182,209-215,241-258`);
  overflow-class blocks start 8-aligned (`src/eve/deftype_proto/coalesc.cljc:349-359`, pinned by
  `test/eve/coalesc_align_test.cljc:16-25`, whose docstring records the failure when they were not: "the CLJS
  Float64Array view of a :float64 EveArray placed there began on its header's count"). So element 0 of an 8-byte
  column is 8-aligned in its slab buffer (by reading; the class-6 case is tested).
- Atomic ops (`array.cljc:570-624`): `cas!`, `exchange!`, `add!`, `sub!` on integer subtypes; `band!`/`bor!`/`bxor!` on
  `:int32`; each a CAS loop on the i32 word holding the element, a sub-word element masked in place
  (`update-element!` `:162-183`). `wait!`/`notify!` on `:int32` (`:635-658`). A `HeapEveArray` locks itself (`:936-940`).
- Bulk ops (`array.cljc:664-727`): `areduce`, `amap`, `amap!`, `afill!`, `acopy!` — each an element-at-a-time loop over
  `aget`/`aset!`. Fill at construction is a doubling `copy-block!` (`:185-196`). `IBulkAccess` (§2.5).
  ClojureScript only: `eve.typed-view` — a JS TypedArray over the elements (`adapters/typed-view/js/src/eve/typed_view.cljs:52-66`)
  and WASM-SIMD fill/copy/sum/min/max/equal on `:int32` (`:98-178`).
- Materialization: `-plain` → a host vector of the elements (`array.cljc:359-366`).
- In a map: a 7-byte pointer. Tag `0x1C` (`FAST_TAG_EVE_ARRAY`) from `-sab-encode` (`array.cljc:342-345`), tag `0x1D`
  from the byte-array codec (the block's header byte; a `HeapEveArray` is copied into a fresh `0x1D` block). Both are
  registered readers (`array.cljc:789-791,823-837`); `0x1C` also reads a legacy block whose byte 0 is the subtype.
- Heap variant: `HeapEveArray [cnt subtype-code backing]` (`array.cljc:839-950`; the name `JvmHeapEveArray` survives only
  in a docstring, `data.cljc:503-507`). A fresh `eve-array` with no sio is one on hosts with typed arrays — "the JVM, bb,
  jolt" (`array.cljc:472-504,847-853`). "It belongs to no slab domain; storing it in an atom writes its elements into the
  atom's slab" (`array.cljc:839-845`). `-as-double-array`/`-as-int-array` answer the backing array itself (`:946-950`).
- Max size: count is an i32 (`array.cljc:418`); a block over 1024 bytes goes to the overflow class (to be completed:
  the offset encoding's limit).

## 2.2 Dataset

- Representation: `(eve-deftype Dataset [^:int32 nrows col-order col-map])` (`src/eve/dataset.cljc:50-52`) — a 16-byte
  instance block; `col-order` a serialized vector of keywords; `col-map` a serialized map keyword → EveArray. No
  dedicated dataset header; the row count is the `nrows` field. Columns are separate `0x1D` blocks reached through an
  eve HAMT map.
- Column dtypes: whatever `EveArray` holds — the nine numeric subtypes. No string, keyword, boolean, date or nil/missing
  representation. `dataset` checks only that the columns have one length (`dataset.cljc:138-150`); `dtypes` calls
  `arr/array-subtype-code` on each column (`:175-183`).
- API (`src/eve/dataset.cljc`, re-exported by `src/eve/alpha/ds.cljc:14-36`):

  | fn | semantics | site |
  |---|---|---|
  | `dataset` | from a map `{keyword → EveArray}`; `([sio col-map])` allocates in `sio` | `:126-150` |
  | `dataset?` | predicate | `:100-103` |
  | `column` | the column's EveArray (a handle on its block) | `:160-163` |
  | `column-names`, `row-count`, `dtypes` | metadata | `:165-183` |
  | `select-columns`, `add-column`, `drop-column`, `rename-columns` | a new Dataset sharing the unchanged columns | `:189-225` |
  | `reindex` | gather every column by an `:int32` index array → new arrays | `:231-238` |
  | `filter-rows` | `argfilter` on one column, then `reindex` | `:240-244` |
  | `sort-by-column` | `argsort`, then `reindex` | `:246-252` |
  | `head`, `tail`, `slice` | build `(vec (range …))` as an int32 array, then `reindex` — always a copy (the `slice` docstring says "Zero-copy if possible") | `:254-275` |

- In place vs materialize: structural ops share column blocks; every row op copies every column. Nothing scans a
  column in place — see §2.5.
- Row access: none. There is no row view and no row constructor. `seq` of a Dataset yields `[col-name EveArray]`
  entries while `count` answers `nrows` (`dataset.cljc:63-64,74-76`).
- Creation: from a column map only. No constructor from a seq of maps. `tensor/to-dataset` returns a **column map**, not
  a Dataset (`src/eve/tensor.cljc:481-502`: "Return the column map — caller can wrap with (eve.dataset/dataset ...)").
- `eve->clj`: `{col-name → vector of numbers}` (`dataset.cljc:59-61`).

## 2.3 Tensor

- Representation: `(eve-deftype NDBuffer [^:int32 elem-offset data shape strides])` (`src/eve/tensor.cljc:53-56`):
  `data` the backing EveArray, `shape` and `strides` serialized vectors of ints in element units, `elem-offset` the
  first element.
- dtype: the backing array's (`tensor.cljc:237-240`).
- Zero-copy views: `reshape` (contiguous only), `transpose` (reverse or permute axes), `slice-axis` (one index along
  one axis) (`tensor.cljc:275-315`) — each a new `NDBuffer` block sharing `data`. No range slice, no broadcasting.
- Element access `mget`/`mset!` (`:256-269`); bulk `emap`, `ereduce`, `to-array` (`:335-479`) through `IBulkAccess`
  with 2-D fast paths.
- `eve->clj`: nested vectors (`tensor.cljc:64-79`).
- Interop hooks: `IBulkAccess` on the backing array only (to be completed: grep for Arrow/DLPack/NIO).

## 2.4 `eve.obj`

- `Obj` (AoS): one `0x1E` block `[0x1E:u8][pad:u8][schema-len:u16][schema bytes][field data @ 4+schema-len]`; schema bytes
  `[n:u8]` then per field `[name-len:u8][name UTF-8][type code:u8]`, zero-padded so the field data starts 8-aligned
  (`src/eve/obj/impl.cljc:108-133`); fields laid out by alignment descending, ties by name (`:58-80`). Field types and
  codes: int8 0, uint8 1, int16 2, uint16 3, int32 4, uint32 5, float32 6, float64 7, obj 8, array 9 (`:24-48,98-106`).
  The schema travels in every block — an `Obj` is self-describing (`obj-from-offset` `:254-259`). `-plain` → a map
  (`:218-220`).
- `ObjArray` (SoA): `(edt/deftype ObjArray [schema length columns mta])` (`impl.cljc:347`) — a **plain host type**;
  `columns` is a host map field-key → EveArray. The constructor makes **every column `:int32` whatever the schema says**
  (`impl.cljc:386-394`: `(arr/int32-array n …)` for each field of the layout). Rows: `ObjArrayRow [parent idx]`, a row
  view answering `ILookup`/`IFn`/`Seqable` by reading each column at `idx` (`:315-345`); `nth` of an ObjArray builds one
  (`:357-363`).
- An `ObjArray` has no block, no schema block, no pointer tag, no `IEveRoot`, no `IPlain` and no registered builder —
  only `Obj` registers (`impl.cljc:396-418`). On the JVM family its columns are `HeapEveArray`s (§2.1). By reading: stored
  in an atom it has no eve bytes, so the codec holds it by reference and a disk domain refuses it.
- Relation to deftypes: `Obj`, `ObjArrayRow` and `ObjArray` are macro-2 host types; the schema is runtime data
  (`create-schema` `:82-92`), not a macro expansion.

## 2.5 `IBulkAccess`

`(defprotocol IBulkAccess (-as-double-array [this]) (-as-int-array [this]))` (`src/eve/deftype_proto/data.cljc:545-551`):
"the host's primitive array of the whole column in one shot … A host whose arrays have none implements nothing."

| Implementor | Behaviour | Site |
|---|---|---|
| `EveArray` (slab) | **a copy**: `(enc/f64s-le (slab/read-bytes sio__ offset__ HEADER_SIZE (* cnt 8)) 0 cnt)`; nil unless float64 / int32 | `src/eve/array.cljc:382-390` (JS: "A Float64Array over a copy" `adapters/encoding/js/src/eve/encoding.cljs:123-132`) |
| `HeapEveArray` | the backing array itself | `src/eve/array.cljc:946-950` |

Callers: `src/eve/dataset/argops.cljc:18-36,116-117,145-146,162,180,216-224`; `src/eve/dataset/functional.cljc:46-47,72-73,
96-97,120-121,148-152,169,185,205,246`; `src/eve/tensor.cljc:343,374-375,414,453`. Results go back through
`arr/from-double-array` / `from-int-array` — the array itself on a heap-array host, a slab copy elsewhere
(`array.cljc:973-995`).

It is the columnar fast path, and it is an **extract-to-host-array** contract, not an in-place view: over a slab column
it copies the column out, loops, and copies the result in. Only two dtypes have it (float64, int32).

## 2.6 The `eve.alpha.*` facades

Pure `def` re-exports: `eve.alpha.col` → `eve.dataset.functional` + `eve.dataset.argops` (`src/eve/alpha/col.cljc:15-42`:
`add sub mul div sum mean min-val max-val gt lt eq emap emap2 argsort argfilter argmin argmax arggroup take-indices`);
`eve.alpha.ds` → `eve.dataset` (`src/eve/alpha/ds.cljc:14-36`); `eve.alpha.tensor` → `eve.tensor`
(`src/eve/alpha/tensor.cljc:13-38`).

## 2.7 Performance evidence in the tree

`test/eve/columnar_bench.cljc` runs five pipelines inside one `swap!` each, Eve columns vs plain Clojure vectors, mmap
and in-memory, at 10K/100K/1M, trimmed mean of the middle three of five timed runs after two warm-ups
(`:1-12,44-61`): column arithmetic (`:186-208`), filter + aggregate (`:215-243`), sort + top-N (`:250-271`), dataset
pipeline (`:279-322`), tensor pipeline (`:333-360`). Recorded run, 1M elements, one 4-core box, 2026-10-21 ET
(`doc/data-structures.md:749-770`), Eve / stock ms:

| Pipeline | Mode | JVM | Node | nbb | bb |
|---|---|---|---|---|---|
| Column Arithmetic | mmap | 18 / 147 | 115 / 710 | 206 / 625 | 339 / 485 |
| Dataset Pipeline | mmap | 128 / 998 | 484 / 3434 | 1494 / 3496 | 3533 / 3388 |
| Filter + Aggregate | mmap | 37 / 35 | 109 / 90 | 141 / 66 | 462 / 86 |
| Sort + Top-N | mmap | 154 / 552 | 354 / 1269 | 2364 / 1273 | 6402 / 1190 |
| Tensor Pipeline | mmap | 243 / 167 | 315 / 938 | 261 / 547 | 429 / 384 |
| Column Arithmetic | in-memory | 22 / 127 | 186 / 799 | 202 / 647 | 360 / 433 |

The same page records a regression against the 2026-10-16 run ("column arithmetic mmap 8 → 115 ms" on Node, JVM tensor
"48 → 243 ms mmap"), not bisected (`doc/data-structures.md:752-754`).

`doc/hosts/perf-results.edn` is the perf suite (`test/eve/perf_suite.cljc:52-91`), which has **no column workload**; its
two deftype-heavy rows: `:int-map-build-200` 4.48 ms/op JVM, 8.09 ms cljs, 133 ms bb, 171 ms nbb
(`perf-results.edn:138,220,37,198`); `:sorted-set-build-200` 11.1 ms JVM, 36.4 ms cljs, 484 ms bb
(`:134-137,216-219,33-36`). `doc/performance.md` has no column figure (it is DataScript and startup).

## 2.8 Gaps against a data-science user's expectations (to be completed)

Exists: nine numeric dtypes; named-column frames; filter / sort / gather / head / tail / slice; element-wise arithmetic
and comparisons; sum / mean / min / max; argsort / argfilter / argmin / argmax / arggroup; N-d views.
Not found so far: string / categorical / date / boolean columns; nulls; 64-bit integer columns; group-by aggregation
(only `arggroup`, a host map of index arrays, `src/eve/dataset/argops.cljc:195-210`); join; row views on a Dataset;
construction from rows; in-place column scans; Arrow / DLPack interop.

---

# Evidence for the assumptions (draft — to be finalised)

- **A2.** The information a sister type needs is all present at expansion time in `eve-deftype*`
  (`src/eve/deftype/macros.cljc:84-128`) on every path that runs the macro, because that function *is* every path. It is
  held in local bindings, not in the registry: the registry keeps slab fields under the simple type name and nothing
  reads it but `eve-extend-type`. What exists only at runtime: the constructor / layout / refs registries. What exists on
  one family only: `IDirectSerialize`, `ISabStorable`, the pointer-tag registration (family B). ValueScript does not run
  the macro at this pin.
- **A11.** Identity is name-derived: an 8-bit type-id plus a 24-bit name hash in every block, and the full
  `"ns/Type"` string as `ISabpType/-sabp-type-key`. The string is never written to memory.
- **A12 / A13.** A serialized field is a pointer to a separate block whose bytes may themselves be pointers into the
  domain (or a process-local reference); a `^:T` field is a raw domain offset; a mutable field is written in place with
  no versioning.
- **Columnar.** No SoA form of "a collection of deftype instances" exists. SoA is reachable only by hand through
  `Dataset` (numeric columns) or `ObjArray` (a host-side map of int32 arrays).
