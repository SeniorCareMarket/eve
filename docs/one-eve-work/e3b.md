# e3b — reuse Eve's existing registry (John's correction)

## Finding
Yes: e3 f1d1cd58d7 introduced a separate `eve.type/identities` atom. It was
unnecessary. This follow-up deletes that state and repairs the existing
`eve.deftype.registry/registry` at the producer. `eve.type` is only API sugar.

## Before (13421bebb9, before e3's duplicate registry)

| Host | Process-local type | Slab-backed type | Portable instance identity |
|---|---|---|---|
| JVM | `t/plain-type` / `t/host-type`, no existing-registry runtime entry | layout auto-registered during macro expansion; eve-deftype constructor auto-registered through `sio/eve-type`; collection constructors/writers use explicit `register-eve-type!` | missing bridge from host realization to existing qualified type key |
| bb | same outer macro; SCI realization is host adapter's concern | same compile-time layout and sio constructor registration | missing bridge; callers resorted to SCI class-name dispatch/dummy constructors |
| nbb | same outer macro, JS realization | compile-time registration runs in self-hosted runtime; JS constructor registration automatic | missing bridge; manual host identities/dummy constructors were application workarounds |
| compiled cljs | JVM-side macros, JS-side runtime; process type never registers into runtime registry | macro registry stays in compiler; generated constructor registration works in JS runtime | missing runtime registry entry (compiler registry isn't runtime state) |
| Basilisp | same outer macro, Python realization | same compile-time layout and sio constructor registration | missing bridge; host class/var difference leaked into callers |
| CLR / BEAM / jank / glojure / VS | same shared outer macro, host-specific realization | same shared sio path (source inspection only) | same absent load-time identity bridge; not runtime-validated here |

No evidence located of a once-working runtime identity bridge later removed in
this ancestry: the registry explicitly said compile-time-only from b24c8c0347e4
(`registry.clj:6-10`). `eve-deftype` short-name layout registration is present
since acb0a8cb1257 (`macros:105-112` at base); proto/deftype registry since
b24c8c0347e4 (`deftype_proto/macros:60-64`). The shared adapter refactor
 eb3449e2d92a (`deftype/macros:48`, proto/macros:66-68 at base) emits no runtime
identity registration. These are provenance points, NOT a proven breaking
commit. Existing serializer registration was not missing and was not replaced.
At f1d1cd58d7, e3 had added the duplicate identity atom for the two
`eve.deftype` wrappers only; `host-deftype` and proto `deftype` still lacked
that identity registration on every host. This follow-up covers all four.
The proto registry's explicit `<Type>-type-id` collision override is a format
constraint, not manual runtime identity registration; it is retained.

## Repairs
- Existing registry is usable at expansion time and runtime. Qualified
  `:type-key` entries are canonical; legacy short aliases remain for macros.
- Every shared macro producer (`eve.deftype/deftype`, `eve-deftype`,
  `eve.deftype-proto.macros/host-deftype`, and proto `deftype`) emits
  `register-host-type!` after the host adapter's definition. No per-user calls.
- Compiled-cljs proto macro adapter requires the runtime registry explicitly.
- Slab macro qualification uses the compiler env namespace where available,
  instead of relying solely on the macro host's `*ns*`.
- bb MapEntry presentation is unwrapped through the EXISTING
  `serialize/presented-backing` hook, not class-name dispatch or another cache.
  Native entries that do not present an Eve value remain unregistered.
- Entry updates merge, rather than replace, so reader/printer information from
  e1b survives and layout information is not lost.
- `eve.type/type-token`, `instance-of?`, `token`, `instance?` hold NO state.
  `type-token` reads the qualified type key in the existing registry. Lookup
  scans registered host realizations; no separate identity cache/index added.
- Existing `registry`, `claimed`, proto `registry`/`derived-ids`, and serializer
  tables remain. NO new atom/registry is introduced, no type-id/hash changes.

### e1b integration contract
Canonical key: qualified string `"my.ns/Type"`. Fields used: `:type-key`,
`:host-type` (actual native realization, not a class-name string), `:storage`
(`:process-local` / `:slab`), and for slabs `:type-id`, `:name-hash` when
applicable, `:total-size`, `:fields`. Reader/printer fields can be merged on
this same entry and are preserved. e1b must not restore `eve.type/register!`
or the deleted `identities` atom. No e1 worktree was edited.

## Remaining e3 duplication audit
- `eve.protocol`: delegates to native defprotocol except the missing Basilisp
  metadata option. No existing portable equivalent found; keep.
- Basilisp omitted-method stubs are in its existing type producer; keep.
- `eve.math/sqrt`: thin native-host wrapper. Existing `Math.jank` is a jank
  completion, not the portable API; keep.
- `eve.compare`: existing `eve.core/-compare` delegates to native comparison;
  `eve.sorted/default-compare` only fixes nil ordering; `coll/compare-indexed`
  and byte comparators cover narrower domains. None supplies this cross-host
  total mixed-data ordering. Keep, with prior persisted-index rebuild warning.
- Instant fixes reuse `eve.encoding/date-ms` (existing API); no new mechanism.

## Downstream
Same sites as e3.md: the 16 query.cljc instance checks (base lines 235,238,241,
253,270,271,273,274,936,938,940,943,987,992,995,998), parser import glue at
28-35 once those move, and query_v3.cljc's 14 dummy constructor tokens at
19-37. No DS source edited here. The remaining e3 protocol/math/compare sites
are unchanged (see e3.md for exact snippets and caveats).

## Validation
Final four-host gate passed on 2026-11-11 at 11:46 ET. Namespaces:
`eve.portable-surface-test`, `eve.deftype.type-id-test`,
`eve.map-entry-test`, `eve.deftype.type-id-steps-test`.

| Host | Tests | Assertions | Fail / error |
|---|---:|---:|---:|
| JVM Clojure 1.12 | 11 | 1265 | 0 / 0 |
| bb 1.13.226 | 11 | 1265 | 0 / 0 |
| cljs / nbb 1.6.214 | 11 | 1221 | 0 / 0 |
| Basilisp 0.5.1 | 11 | 1222 | 0 / 0 |

The existing portable-surface namespace is already registered in JVM discovery
and bb/nbb/Basilisp/compiled-cljs runners by e3; these new regression assertions
therefore run in the normal suites. They cover both process-local macro paths,
both slab macro paths (including EveList), bb MapEntry presentation, native
MapEntry nonidentity, qualified-name collisions, and preservation of reader /
printer fields on an existing registry entry. Existing serialized type-id
literals, colliding type-id round trips, MapEntry behavior and all type-id hash
derivation steps remain green.

Logs: `/workspace/ds-wt/one-eve-workers/e3/e3-test/registry-{jvm,bb,nbb,lpy}-final.log`.
Reproducer script: same directory's `run.sh HOST`, under the shared flock.
Compiled cljs, BEAM/clje, CLR, jank, glojure and VS were NOT run; table rows for
those hosts are source inspection, not runtime proof. The old cljs-only
`eve.deftype-test` suite was not run; the existing shared deftype/type-id suites
above were. No full DataScript suite was run.

The first bb regression test exposed presented MapEntry identity; it was fixed
through the existing backing hook. Basilisp first failed importing another
test namespace under eve.deftype (its file/package collision); the new test now
has no cross-test dependency and still covers both slab macro paths. Initial
JVM execution was interrupted during shared-lock turnover; its retry passed.
No failure was retried unchanged twice.

Every run held `/workspace/ds-wt/one-eve-workers/test.lock`, checked disk and
inodes, and refused below 50k free inodes. Resource start: 37 GB / 1.53m free
inodes; final: 36 GB / 1.49m. Owned `.cpcache` and Python cache cleaned after
testing. Most elapsed time was waiting for the shared test lock, not test
execution. WIP checkpoint was bf433a8964e, pushed and ls-remote verified before
finishing, per coordinator's 11:14 ET direction.
