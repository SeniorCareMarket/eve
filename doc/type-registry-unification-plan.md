# Type Registry Unification Plan

> Status: **Planning** | Branch: `claude/repo-review-report-c3C3W`
>
> Goal: Unify type-id handling, registration, and deserialization across CLJ/CLJS
> so that eve3-deftype is truly self-contained and the EVE format is self-describing.

---

## Background

The eve3-deftype macro was designed to unify CLJ/CLJS deftype definitions behind
a single macro. It succeeded at protocol translation and field access, but left
several pieces of the type lifecycle as manual, asymmetric, and inconsistent
between platforms:

1. Type-ids are manually threaded via metadata (`^{:type-id 0xED}`)
2. Constructor/disposer registration is manual and differs per platform
3. JVM and CLJS use different deref paths with hard-coded mappings
4. Header type-ids and pointer tags are inconsistent for map/set
5. No name-based type resolution for the "Extensible Value Encoding" promise

These gaps interact. Fixing them in the right order avoids rework.

---

## Gaps

### Gap 1: Pointer Tag / Header Type-ID Mismatch

**Problem:** Map and set use different numeric IDs for their slab header byte vs
their serialization pointer tag. Vec and list use the same value for both.

| Type | Header byte (slab offset 0) | Pointer tag (serialization) |
|------|----------------------------|---------------------------|
| Map  | `0xED`                     | `0x10`                    |
| Set  | `0xEE`                     | `0x11`                    |
| Vec  | `0x12`                     | `0x12`                    |
| List | `0x13`                     | `0x13`                    |

This forces a mapping step in JVM deref (`atom.cljc:591-594`):
```clojure
(let [tag (case (int type-id)
            0xED 0x10   ;; map header → map pointer tag
            0xEE 0x11   ;; set header → set pointer tag
            type-id))
```

**Root cause:** `0xED` and `0xEE` were originally `FAST_TAG_FLAT_MAP` and
`FAST_TAG_FLAT_SET` — flat serialization markers that predated the HAMT
collections. When EveHashMap/Set were introduced, they reused these values as
header bytes for backward compatibility with flat-map encoding.

**Files affected:**
- `src/eve/deftype_proto/serialize.cljc:44-47` — FAST_TAG definitions
- `src/eve/deftype_proto/serialize.cljc:65-66` — FAST_TAG_FLAT_MAP/VEC (0xED, 0xEF)
- `src/eve/map.cljc:47` — `EveHashMap-type-id 0xED`
- `src/eve/map.cljc:802` — writes 0xED to header byte 0
- `src/eve/set.cljc:40` — `EveHashSet-type-id 0xEE`
- `src/eve/set.cljc:524` — writes 0xEE to header byte 0
- `src/eve/atom.cljc:591-594` — hard-coded mapping in JVM deref
- `src/eve/atom.cljc:1406` — JVM map registration uses 0x10 (pointer tag)

**Fix approach:** Unify so header byte = pointer tag for all types. Map → `0x10`,
Set → `0x11`. This means:
- Change `EveHashMap-type-id` from `0xED` to `0x10`
- Change `EveHashSet-type-id` from `0xEE` to `0x11`
- Update `write-map-header!` and `write-set-header!`
- Remove the mapping in `jvm-read-root-value`
- Keep `0xED`/`0xEE` as `FAST_TAG_FLAT_MAP`/`FAST_TAG_FLAT_SET` (flat encoding only)
- **Migration:** Existing mmap files on disk will have `0xED`/`0xEE` headers.
  The deref path should accept both old and new values during a transition period.

**Backward compat strategy:**
```clojure
;; Accept both old (0xED/0xEE) and new (0x10/0x11) header bytes
(let [ctor (or (get-header-constructor type-id)
               (case (int type-id)
                 0xED (get-header-constructor 0x10)  ;; legacy map
                 0xEE (get-header-constructor 0x11)  ;; legacy set
                 nil))]
  ...)
```

---

### Gap 2: Manual, Asymmetric Registration

**Problem:** Each collection manually registers constructors, writers, and
disposers in platform-specific blocks. The macro doesn't help.

**Current state:**

| Collection | CLJ registrations | CLJS registrations |
|-----------|------------------|-------------------|
| Map | `register-jvm-collection-writer!` `:map`, `register-jvm-type-constructor!` 0x10 | `register-sab-type-constructor!`, `register-header-disposer!`, `register-cljs-to-sab-builder!`, `set-direct-map-encoder!` |
| Set | `register-jvm-collection-writer!` `:set`, `register-jvm-type-constructor!` 0xEE | **none** |
| Vec | `register-jvm-collection-writer!` `:vec`, `register-jvm-type-constructor!` 0x12 | **none** |
| List | `register-jvm-collection-writer!` `:list`, `register-jvm-type-constructor!` 0x13 | **none** |

Vec, list, and set are **missing CLJS registrations**. They cannot serve as
mmap atom root values on CLJS. They work in SAB atoms only through the separate
pointer-tag deserialization path in `serialize.cljc`.

**Files affected:**
- `src/eve/deftype_proto/eve3_deftype.clj:229-242` — `emit-cljs` (no registrations)
- `src/eve/deftype_proto/eve3_deftype.clj:296-311` — `emit-clj` (no registrations)
- `src/eve/map.cljc:1041-1077` — manual CLJS registrations
- `src/eve/map.cljc:1383-1408` — manual CLJ registrations
- `src/eve/vec.cljc:658-660` — CLJ only
- `src/eve/set.cljc:894-896` — CLJ only
- `src/eve/list.cljc:358-360` — CLJ only

**Fix approach:** The eve3-deftype macro should accept optional registration
directives and auto-emit them on both platforms. The macro knows:
- `type-id` — for registration keys
- `type-name` — for constructor generation (`TypeName.` creates instances)
- `type-key` — for name-based resolution

The macro should auto-emit (when directives are present):
```clojure
;; CLJS:
(ser/register-sab-type-constructor! <pointer-tag> <type-id>
  (fn [_sab header-off] (<from-header-fn> (alloc/->CljsSlabIO) header-off)))
(ser/register-header-constructor! <type-id>
  (fn [_sab header-off] (<from-header-fn> (alloc/->CljsSlabIO) header-off)))

;; CLJ:
(ser/register-jvm-type-constructor! <type-id>
  (fn [header-off] (<from-header-fn> alloc/*jvm-slab-ctx* header-off)))
```

The `from-header-fn` and `dispose-fn` are type-specific (maps need HAMT tree
walking, lists need chain walking). These should be declarable in the deftype
body or passed as macro options.

**Design question:** Collection writers (`register-jvm-collection-writer!`) and
`register-cljs-to-sab-builder!` may be too domain-specific for the macro to
auto-generate. These convert *native* Clojure/CLJS types to Eve types. Consider
keeping these manual but moving the constructor/disposer registrations into the
macro.

---

### Gap 3: Divergent Deref Paths

**Problem:** JVM and CLJS mmap atoms use different deserialization strategies.

| Platform | Function | Strategy |
|----------|----------|----------|
| CLJS mmap | `cljs-mmap-deref` (atom.cljc:254) | `get-header-constructor(type-id)` — registry lookup |
| JVM mmap | `jvm-read-root-value` (atom.cljc:582) | `case` + tag mapping + `get-jvm-type-constructor(tag)` + fallbacks |
| CLJS SAB | `atom-deserialize` (shared_atom.cljs:2383) | `deserialize-element` → pointer tag dispatch |

**Files affected:**
- `src/eve/atom.cljc:254-276` — CLJS mmap deref
- `src/eve/atom.cljc:578-603` — JVM mmap deref
- `src/eve/shared_atom.cljs:2383` — SAB deref (uses serialize.cljc)
- `src/eve/deftype_proto/serialize.cljc:97-134` — JVM registries
- `src/eve/deftype_proto/serialize.cljc:142-194` — CLJS registries

**Fix approach:** Both platforms should use the same pattern:
1. Read header byte 0 → `type-id`
2. Look up constructor in `header-constructors` registry (keyed by `type-id`)
3. Call constructor with `(ctor sio header-off)` on both platforms

This requires:
- JVM: populate `jvm-header-constructors` by header type-id (not pointer tag)
- JVM: remove the `case` mapping in `jvm-read-root-value`
- Both: use same registry key space (header byte = pointer tag, per Gap 1)

After Gap 1 is resolved, the `case` mapping disappears naturally.

---

### Gap 4: Name-Based Type Resolution

**Problem:** EVE is the "Extensible Value Encoding" — it should be self-describing.
Currently, a reader must have all type-ids pre-registered to deserialize data.
A new runtime joining an existing atom has no way to discover what types are in it.

**Current state:**
- `type-key` is computed at macro time: `"eve.vec/EveVector"` (eve3_deftype.clj:331)
- Stored in compile-time registry only (eve3_deftype/registry.clj)
- **Never written to slab or mmap files**
- `ISabpType` protocol with `-sabp-type-key` exists in data.cljc:389 but
  eve3-deftype doesn't implement it

**Files affected:**
- `src/eve/deftype_proto/eve3_deftype.clj:331-336` — type-key computed, stored in registry
- `src/eve/deftype_proto/data.cljc:389` — ISabpType protocol (unused by eve3 types)
- `src/eve/deftype_proto/data.cljc:461-464` — cleanup registry keyed by type-key string

**Fix approach:** Write a type table to a well-known location in the atom domain.

Option A: **Type table in `.root` file** — a section mapping `type-id → type-key`
```
Offset 0-255: existing root layout
Offset 256+: type table
  [count:i32]
  ([type-id:u8][name-len:u16][name-bytes:utf8])* count
```

Option B: **Separate `.types` file** — simpler, no root layout changes
```
[magic:u32][count:i32]
([type-id:u8][name-len:u16][name-bytes:utf8])* count
```

A new reader joining the atom:
1. Reads the type table
2. For each entry, resolves `type-key` → local constructor via name registry
3. Registers the numeric type-id → constructor mapping
4. Can now deref the atom

The core 4 types (map/set/vec/list) have well-known names and IDs that every
runtime knows. User-defined types would be resolved by name, with the numeric
ID serving as a fast-path optimization.

---

### Gap 5: Remove `^{:type-id}` Metadata Requirement

**Problem:** Users must manually specify type-ids as metadata:
```clojure
(eve3/eve3-deftype ^{:type-id 0xED} EveHashMap ...)
```

This is error-prone, redundant (the same value exists as `def ^:const`), and
exposes binary-format internals in user code.

**Files affected:**
- `src/eve/deftype_proto/eve3_deftype.clj:329-330` — metadata read + fallback
- `src/eve/map.cljc:47,824` — constant + metadata both specify 0xED
- `src/eve/set.cljc:40,660` — constant + metadata both specify 0xEE
- `src/eve/vec.cljc:29,312` — constant + metadata both specify 0x12
- `src/eve/list.cljc:26,107` — constant + metadata both specify 0x13

**Fix approach:** Convention-based lookup. The macro resolves the type-id from
an existing `def` in the same namespace:

```clojure
;; In the macro:
type-id (or (:type-id (clojure.core/meta type-name))          ;; explicit override
             (when-let [v (resolve (symbol (str (name type-name) "-type-id")))]
               @v)                                              ;; convention lookup
             (reg/next-type-id!))                               ;; auto-assign
```

Then the user code simplifies to:
```clojure
(def ^:const EveHashMap-type-id 0xED)
(eve3/eve3-deftype EveHashMap [^:int32 cnt ^:int32 root-off] ...)
```

Or for user-defined types with no pre-existing constant, auto-assignment works:
```clojure
(eve3/eve3-deftype MyCustomType [^:int32 x ^:int32 y] ...)
;; → auto-assigns type-id, emits (def MyCustomType-type-id <auto>)
```

---

## Implementation Order

```
Phase 1: Gap 5 — Remove metadata requirement (low risk, no behavior change)
   │  Convention-based type-id lookup in macro
   │  Remove ^{:type-id} from map/set/vec/list deftype calls
   │  Tests: all green baseline suites
   │
Phase 2: Gap 2 — Auto-emit registrations from macro
   │  Add :register directive support to eve3-deftype
   │  Add missing CLJS registrations for vec/list/set
   │  Unify CLJ registrations into macro
   │  Tests: all green baseline + mmap-atom suites
   │
Phase 3: Gap 1 + Gap 3 — Unify type-ids and deref paths
   │  Change map header to 0x10, set header to 0x11
   │  Backward-compat: accept old 0xED/0xEE during transition
   │  Unify JVM deref to use header-constructor registry
   │  Remove hard-coded case mapping in atom.cljc
   │  Tests: all green baseline + mmap-atom + stress tests (10MB, 100MB)
   │
Phase 4: Gap 4 — Name-based type resolution
      Add type table to atom domain (.types file or .root section)
      Write type-key→type-id mappings on domain creation
      Read and resolve on domain join
      Tests: cross-process atom tests with type discovery
```

---

## Work Tracking

| Phase | Gap | Status | Notes |
|-------|-----|--------|-------|
| 1 | Gap 5: Remove metadata | Done | Convention-based type-id lookup, java.util.Set/List, map reduce fix |
| 2 | Gap 2: Auto-register | Done | CLJS registrations for set/vec/list (constructor, disposer, builder) |
| 3 | Gap 1+3: Unify IDs + deref | Done | Registry-based JVM deref, dual registration by tag+header-id |
| 4 | Gap 4: Name resolution | Done | ISabpType protocol on all types, TypeName-type-key defs |

---

## Test Plan

Each phase must pass the full green baseline before proceeding:

```bash
npx shadow-cljs compile eve-test
node target/eve-test/all.js all

# Phase 3 additionally:
clojure -M:native-build-atom /tmp/eve-10m 10
clojure -M:native-x-stress-atom /tmp/eve-10m
clojure -M:native-build-atom /tmp/eve-100m 100
clojure -M:native-x-stress-atom /tmp/eve-100m

# JVM tests:
clojure -M:jvm-test
```
