> **Historical record** — Unit 3 of the unification (2026-10-15): the keep/modify/replace verdicts on the September candidate,
> against BASE line numbers. It describes eve as it stood then, not eve 0.9; kept as the rationale for the VarHandle region and
> the unaligned-access rule. Current authority: `doc/unification/HOST-CONTRACT.md`.

# Judging the candidate — Unit 3

Written 2026-10-15 16:25 ET against BASE `b24c8c034`, after `SEAM-MAP.md` (Unit 1) and
`HOST-CONTRACT.md` (Unit 2). Each decision the September session made is judged
**keep / modify / replace** against the standard the work set: *does this make the next
port smaller, or does it smuggle one host's semantics into the shared code?* The verdicts
here are the implementation plan for Units 4–5 (§8).

---

## 1. No `sun.misc.Unsafe`; VarHandle over `ByteBuffer`; one `JvmByteBufferRegion` for heap and mmap; unaligned i32/i64 by byte-wise composition; atomic RMW throws on misalignment; `LockSupport` wait/notify

**Verdict: keep.**

* It is a clean closure (`L-45`): no Unsafe anywhere, one region type for both backings,
  real blocking wait/notify. The witness-return convention of `compareAndExchange` is the
  same contract `Atomics.compareExchange` and the native `cas32` already had, so nothing
  VarHandle-shaped reached `ISlabIO` (checked: `-sio-cas-i32!` returns the witness on every
  host, alloc.cljc:102/:256/:1483).
* The unaligned fallback is not a JVM quirk leaking out; it is the JVM *complying* with a
  contract the HAMT layout already imposed on every host (HOST-CONTRACT §2.1 ★ and §8.2).
  The contract now says so explicitly; a port that only has aligned loads must do the same
  composition.
* Not a ByteBuffer-shaped contract: `IMemRegion` consumers never see a `ByteBuffer`; the
  one `ByteBuffer` outside the region deftypes (`imr-bitmap-find-free-bulk`, mem.cljc:1347)
  is a JVM/bb-only bulk scan over `-read-bytes`, i.e. a host specialisation of a portable
  function — the right shape.
* FFM/Panama rejection stands: preview on Java 21 and not callable reflectively.
* Cost accepted for a reference implementation: every atomic boxes through
  `MethodHandle.invokeWithArguments` (HOST-CONTRACT §8.14). Not a seam issue; a later JVM
  specialisation (an AOT'd helper class) is allowed by decision 4 precisely because it
  would not change any seam.

One modification, not to the region but to its *selection*: `*lustre-mode*` (`L-14`) is
replaced by a region-provider function held by the domain, so the Lustre regions stay as
they are and become a provider choice instead of a dynamic var that the allocator
infers-and-rebinds.

## 2. Heap domain kept (multi-threaded in-JVM shared memory, the SAB analogue); in-process-only is honest, cross-process is mmap's job

**Verdict: keep the domain, replace the way it is reached.**

* The heap domain is exactly `make-heap-region` of the host contract (§1.1); it is the JVM's
  SAB. Keep.
* What must go is `default-sio` = *a fresh heap domain per constructor call* (`L-27`). It is
  the worst of both worlds: CLJS has one process slab (every default-constructed value can be
  nested in every other), the JVM has one island per value, and nesting an island into
  another domain writes a dangling pointer silently (mem.cljc:1958–1967 trusts any
  `IEveRoot`). Decision for Unit 4:
  1. one **process-default in-process domain** per host — on the JVM the same domain
     anonymous heap atoms already use (`resolve-heap-domain nil`), on CLJS the module slab
     (what `->CljsSlabIO` is); `default-sio` returns *its* `sio`;
  2. **crossing a domain copies**: when a slab-backed value from domain A is nested into or
     stored at the root of domain B, it is materialised (`eve->clj`) and re-encoded into B.
     The candidate's loud `swap!` error (atom.cljc:928) and the silent nested pointer are
     replaced by one rule on both paths. Deref already returns plain data, so this only
     affects values the user built with `atom-sio`/`default-sio` and moved across domains.
* The heap domain's fixed per-class capacity (1 MB, no growth) is unchanged and documented;
  growing heap slabs under concurrent readers is a different project.

## 3. Deref boundary: plain `@atom` materialises through portable `eve->clj`; a `$` operator bounds a region where Eve views may exist (epoch pinned for its dynamic extent) and materialises only the final result

**Verdict: keep the boundary; modify `$` from a language feature into a function.**

* The boundary is correct and already in force on both hosts at BASE (`cljs-mmap-deref`
  :364 and `jvm-mmap-deref` :850 both call `d/eve->clj` while pinned). A slab-backed view
  that escapes the pin is a use-after-free under epoch GC; there is no cheaper safe
  default. Keep.
* **Reversed 2026-10-23** (CHANGELOG [Unreleased]): `$` is the macro it was designed as, `eve.alpha/$` — a read
  region in which a deref answers the lazy value and every pin (a network atom's lease) is held to the region's end.
  No port implements it in its macro system: the macro is one syntax-quote in the shared `alpha.cljc`, the region is
  portable `src/eve/read_region.cljc`. The function below is `eve.atom/read-pinned`. The verdict as written:
* `$` as designed ("a `$` operator … the dynamic extent") reads like a macro or reader
  feature that every port would have to implement in its macro system. It does not need to
  be one. It is a function:

  ```clojure
  (eve.atom/$ a f)        ; => (eve->clj (f lazy-view-of-@a)) with the epoch pinned
  ```

  pin → read the root as a lazy value → `(f v)` → `eve->clj` of the result → unpin. One
  portable `.cljc` function (~15 lines, no host arm), exported from `eve.alpha` on both
  hosts. On a materialising host (bb) `f` receives the materialised value — the same
  semantics without laziness, which is the host's declared capability, not a fork.
* Reader-side laziness inside `$` is a real win on the JVM and CLJS (a lookup in a 100 MB
  map does not materialise the map). It is exactly the "bounded Eve-backed access" the
  candidate's docstring promised (atom.cljc:853).

## 4. Reference implementation first: JVM fast paths (`IBulkAccess`, typed-array paths) are not constraints; the one hard constraint is the CLJS hot paths (N-API crossing minimisation, zero-fill fast path)

**Verdict: keep, and apply it to the candidate's own work.**

* Concretely, for Unit 4 the CLJS slab read/write path (`CljsSlabIO` → DataView module
  functions, `resolve-dv!`, typed-view caches in `EveArray`, the scratch-buffer encoders)
  is left verbatim. The shared deref/swap will call the same `alloc/...` functions the CLJS
  twins call today, through the `sio` that CLJS domain-state will now carry.
* The consequence the candidate did not draw: because the CLJS context must stay the module
  slab for this work (restructuring where the layout arrays live is CLJS performance work
  and is excluded by the brief), `L-19` stays **open by decision** — recorded, not hidden.
  The contract is written so that a future CLJS change (a `CljsSlabIO` with fields) is a
  pure host change.
* `IBulkAccess` and `JvmHeapEveArray` stay as JVM specialisations, but they may not
  *shadow* the portable API (`L-33`): `eve.array/eve-array` and `aset!` must mean the
  slab-backed thing on every host; the heap array keeps its own constructors.

## 5. Sequencing: domain-init → slab-context unification (kill the dynamic var, thread `sio`) → serializer registry merge → deref/swap merge → alpha parity + deftype completion; both suites + the round trip green after every phase

**Verdict: modify.** Two defects in the order and one in the done-bar:

1. **The registry merge must precede the deref/swap merge** — a shared deref needs one
   header-constructor registry with one constructor signature (`L-24`/`L-51`); the candidate
   merged the *names* and stopped, so the shared deref could not have been written against
   it. It also broke bb loading in the process (`L-58`) because the merge was done per
   reader-conditional arm instead of once.
2. **"Kill the dynamic var" was done by introducing two other ambient contexts**: the
   ThreadLocal swap logs (`L-18`) and the per-call heap islands (`L-27`). The slab-context
   phase must end with *no* ambient context at all. The portable replacement for the
   ThreadLocal logs is a **per-swap slab handle**: the swap wraps the domain's `sio` in a
   handle that carries an allocation log and a replaced-node log; the old root is
   reconstructed with that handle, so every allocation `f` makes through derived values is
   logged without thread identity, and `log-replaced-node!` becomes a method on `ISlabIO`
   (`-sio-note-replaced!`, a no-op on a plain handle). On the JVM this is the same deftype
   with one extra field sharing the same region arrays (so `refresh-jvm-slab-regions!`
   stays visible); on CLJS it is `CljsSlabIO` with one field. Thread identity is no longer
   required by the swap at all; it remains a host *capability* used only for per-thread
   epoch aggregation (HOST-CONTRACT §5).
3. **The done-bar needs bb**: "both suites + the round trip" never included bb, which is
   why bb could be left unloadable. Every phase below ends with the CLJS suite, the JVM
   suite, the Node→JVM→Node round trip **and** a bb load + swap.

## 6. Decisions the candidate did not make but the code did

| Decision in the code | Verdict | Reason |
|---|---|---|
| JVM-only `{:schema :values}` map → Obj coercion (atom.cljc:937) | **replace** (delete) | A map's keys are not a type tag; this makes one host store a different thing. Objs are constructed explicitly. |
| JVM `MmapAtom` without `compareAndSet`; bb's non-atomic `compareAndSet`; CLJS none | **replace** | One `compare-and-set!` as one CAS-loop iteration (`f` aborts unless `(= cur old)`), on all hosts. |
| CLJS failure path frees only the new root; JVM success path never retires nested collections (`L-03`) | **replace** both with one collector | The per-swap log fixes the failure path on every host; the CLJS nested-aware collector becomes the only success-path collector. |
| `*parent-atom*` bound around `f` on CLJS only (`L-15`) | **modify** | Keep the binding on CLJS only as *legacy residue* for the CLJS `eve-slab-deftype` constructor guard and the typed-array copy-vs-view policy; it is not bound on the JVM and nothing shared may read it. Listed as a surviving conditional with its reason. |
| `perf/timed` in the JVM swap (`L-35`) | **modify** | `eve.perf` gets a CLJS no-op arm so the shared swap keeps its instrumentation. |
| Three `MmapAtom`/`MmapAtomDomain` deftypes (`L-09`) | **replace** with one body | Interface names are the only host difference (`IDeref`/`clojure.lang.IDeref`); reader conditionals shrink to the names. |
| bb materialising constructors in `eve.atom` (atom.cljc:63–105) and the bb arm of `jvm-read-root-value` (`L-28`) | **modify** | They move behind the one registry as bb's registrations of `0xED/0xEE/0x12/0x13` (a materialising host registers materialisers); `eve.atom` stops knowing about bb. |
| Flat-set tag `0xEE` (`L-36`) | **keep the JVM's tag, fix the CLJS decoder and both encoders** | Argued from the format's history in `FORMAT-DECISIONS.md` (Unit 5): the documented format (internals.md) has no set tag because the original CLJS flat encoder dropped set-ness (`set → FLAT_VEC`), which is lossy; the JVM port added `0xEE` to keep it. Tag `0xEE` at byte 2 is unambiguous (byte 0 of a block is a different namespace — `0xED` is already both `FLAT_MAP` and the map header id). Elements are written in **canonical order** (sorted by their encoded bytes) so equal sets encode identically on every host — the property a map *key* needs. |
| Composite keys pointer-encoded on CLJS (`L-37`) | **replace** | Keys are values, not references: every host flat-encodes collection keys, with canonical ordering for sets and maps. This is the only CLJS codec change on a hot path and it touches only collection-typed keys (scalar keys are byte-for-byte unchanged). |
| Obj layout from map iteration order (`L-32`) | **replace** | Layout from the encoded field order (a vector), stable sort by alignment, on both hosts. |
| FNV-1a over UTF-16 units (`L-43`) | **modify** (spec it over UTF-8 bytes) | Identical for ASCII; the field is write-only, so no existing data is misread. |
| Long symbols silently truncated (`L-40`) | **modify** (throw on both hosts) | No long-symbol tag exists; inventing one is a format change outside the work. |
| `:default` reader-conditional arms used to mean "JVM and bb" (mem.cljc:1411 — the whole JVM codec — and atom.cljc:617, map/set/vec/list) | **replace** with explicit `:clj`/`:bb` arms where the arm contains JVM interop | A fourth host reading these `.cljc` files is handed the JVM arm by default. `:default` is reserved for portable code. This is the single largest "next port smaller" change in the register and costs nothing. **Applied 2026-10-16**: 37 JVM-only `:default` keys became `:clj`, each proven to leave every `.cljc` reading identically on CLJS, the JVM and bb; 25 `#?(:bb nil :default …)` arms stay (CLJS reads them — `eve/deftype` bodies), and 12 portable arms stay `:default` (the `^js` pass-throughs, the ns requires, `copy-region!`, the pointer encoder, the `SLAB_SIZES` lookup); `compare-bytes` and `hamt_util` gained portable `:default` arms. let-go now loads `eve.mem` (`PORT-TARGETS.md` §3.2). |

## 7. Does the registry merge shape survive a host with different type-identity semantics?

Yes, where it is keyed by integers; no, where it is keyed by host object identity:

* Keyed by `int` (type-id, fast-tag): `type-constructors`, `header-constructors`,
  `header-disposers` — portable, and the merged shape (one atom holding `{int → fn}`
  maps) is what Unit 4 implements. `satisfies?`/predicate-keyed builders — portable.
* Keyed by host identity: the CLJS record registry (`record-tag-by-ctor`, a `js/Map` keyed
  by the record's constructor function) and the `direct-map-encoder`/`typed-array-encoder`
  function slots. Records (`0x1A`) are a CLJS-only tag at BASE; they stay a CLJS host
  extension and do not enter the shared registry. The two encoder slots are the CLJS
  codec's internal fast paths and stay inside the CLJS arm of the codec.

## 8. The implementation plan (Units 4–5)

Each phase ends green on: CLJS suite, JVM suite, Node→JVM→Node round trip, bb load +
swap; each phase is one commit + push.

| Phase | Content | Closes |
|---|---|---|
| **P0 — measure and unblock** | STEP-0 baselines on the pristine tree; the one-line bb unblock (`register-header-tag-reader!`/`read-header-tag` exist on bb) so bb can be measured | `L-58` |
| **P1 — one registry, one constructor convention** | portable registry in `serialize.cljc` (no per-arm stores); `(fn [sio header-off])` everywhere; `-sab-encode` returns the offset on every host; CLJS domain-state carries `:sio`; bb materialisers registered from `eve.set`/`eve.vec`/`eve.list` (or a `eve.bb` host ns), not from `eve.atom`; `:default` arms containing JVM interop become `:clj`/`:bb` | `L-24`, `L-26` (one registry), `L-30`, `L-50`, `L-51`, `L-52`, `L-56` |
| **P2 — one domain, one deref, one swap** | `open-domain!` over a region provider (`:open-region`), one domain-state shape (retire queue = atom of a vector, flush-ts = atom), per-swap logging slab handle, one `mmap-deref`, one `mmap-swap!`, one flusher, one root resolver, one retirement collector (nested-aware), `compare-and-set!`, `$`, JVM exit hook, root re-read after refresh (coop-atom 0004), list retire walk (0003), `*lustre-mode*` deleted, ThreadLocal logs deleted, `jvm-collect-replaced-nodes` deleted, `mmap-mode?` deleted, `perf` CLJS arm | `L-01`–`L-09`, `L-11`, `L-13`, `L-14`, `L-18`, `L-20`, `L-35`, `L-42`, `L-57` |
| **P3 — parity and completion** | `default-sio` → process-default domain + cross-domain copy; `atom-sio` on CLJS; `close-atom-domain!` on CLJS alpha; slab-backed `eve-array`/`aset!` on the JVM (heap array keeps its own names); one `Obj` over `ISlabIO` with ordered layout; deftype `cas!` test on the JVM; `eve.platform` throw helpers | `L-12`, `L-27`, `L-29` (partial), `L-32`, `L-33`, `L-34` (test), `L-54` |
| **P4 — byte fixtures and format fixes** | `FORMAT-DECISIONS.md`; `0xEE` on CLJS; canonical order for flat sets/maps; composite keys flat on CLJS; FNV over UTF-8; long-symbol throw; fixtures for every scalar tag, every flat/pointer shape, every header block, the root/slab/coalesc headers, the atom block, worker and atom slots; the suite runs under JVM, Node and bb | `L-36`–`L-44`, `L-47`-contract |
| **P5 — report** | `UNIFICATION-REPORT.md` | — |

What stays open by decision and is reported as such: `L-19` (CLJS module slab — CLJS
host work excluded by the brief), `L-28` (bb as a materialising host — a capability, not
a fork, once the materialisers sit behind the registry), `L-55` (bb atomics — the correct
fcntl provider is a bb host change; it is *implemented if time permits* after P4, otherwise
named with its exact failing scenario), `L-21`/`L-46`/`L-48`/`L-49` (contract wording,
done in HOST-CONTRACT; code unchanged), `L-17` (default-domain var, API configuration),
`L-25` full codec merge (the format fixes are made on both codecs and pinned by fixtures;
the merge into one `.cljc` codec is started only if P0–P4 are green with time left —
the fixtures are what make the later merge safe).
