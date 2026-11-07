# REPORT — Eve 0.9.3.1 adapter fix: F-GLJ-WALL (glojure)

Lane M (glojure), sole writer. Branch `fix/0931-glj` off origin/main (0.9.3), worktree
`~/workspace/wt/eve-0931-glj`. Rig: prebuilt glj-eve (glojure v0.7.22 + Eve's Go packages,
`~/workspace/stm-glj/build/glj-eve`), GLJ_CLASSPATH = hosts.edn `:glj` roots, `-e` form.
All numbers as printed; logs under `fix/logs/` in the worktree.

## The named defect — FIXED

**F-GLJ-WALL**: Eve itself failed to load on glojure — `(require 'eve.atom)` died evaluating
eve/guard.clj: "runtime error: invalid memory address or nil pointer dereference" at
`adapters/completion/glojure/src/eve/host_completion.glj:132`, in the swap! wrapper, while
extending LazyMap (reproduced in ~7 s).

**Root cause** (isolated by probes P1–P5): eve.guard is a JVM-only file (its own docstring:
"a JVM host only") whose deftypes implement raw JVM interfaces. `eve.atom` requires it under
the `:clj` reader branch; glojure reads `:clj`, so the JVM-only file is pulled into the
glojure load, where the completion's extend machinery meets a protocol value it cannot hold
(glojure's own protocols are Class objects, not the completion's atom-protocols; bare glojure
extend rejects interface classes outright — "clojure.lang.ILookup is not a protocol"). The bb
seat already excludes eve.guard in the same splices; cljs never takes it.

**Fix** (`src/eve/atom.cljc`, 3 splices, paralleling `:bb` exactly): the ns require, the
`guard-deref` defn, and the `mmap-deref` cond clause now also exclude `:glj`. On glojure,
eve.atom loads without the guard and deref falls through to plain-deref — the bb/cljs shape.
No state machinery added anywhere (the memory rule is untouched: no journals, scans,
counters, or arenas; the guard's own cell/holds design is unchanged on the JVM).

## Proofs

1. **eve.atom load chain loads on glojure** — `(require 'eve.atom)` → `:eve-atom-loaded`
   (full chain incl. eve.core). Was: panic at guard evaluation.
2. **Shared-atom smoke on glj, cross-process** — write process (persistent disk domain,
   10 swap!s): `{:deref {:v [0 1 2 3 4 5 6 7 8 9], :n 10}, :seal {:ptr 87, :name-hash
   1611018502, :blocks 14, :digest -851564927}}`; fresh read process: **identical deref,
   identical seal**. (fix/smoke_main.cljc; entry honors the seat's documented contract —
   eve.host-completion loads first, as eve.runner.glj-main's docstring states.)
2b. **Shared suite rows on glj** — eve.runner.glj-main, eve.domain-open-test +
   eve.collection-hash-test: **"Ran 4 tests containing 23 assertions. 0 failures,
   0 errors."** (log p2b-glj-suite-rows.log; includes the reopened-domain row.)
3. **Vanilla DataScript port on glj** — loads through **datascript.db in full** (was:
   eve.atom wall at load). The flagship scenario leg itself stops in **datascript.conn**
   at the fifth wall (below), named with its disposition.
4. **Other seats unregressed** — JVM: broad `clojure -M:jvm-test` pass on this branch:
   **"Ran 636 tests containing 10606 assertions. 2 failures, 1 errors."** (log
   p4-jvm-spot2.log; same 636 as the shipped tree). The three reds are environmental,
   none in a code path this branch touches: (a) thread_fold_test's
   chunks-are-sized-from-the-measured-swap-cost — a measured chunk-size comparison
   inverted under load (box load 10–12 throughout: "39 < 3"); (b) thread_test's
   round-trips — a perf bar, 212 ms against the 100 ms conformance bar under the same
   load; (c) three-servers-run-one-pmap ERROR — the sandbox's own network permission
   refused the test's localhost fetch ("Other TCP connections is turned off for this
   assistant"). (a) and (b) are contention-tainted timing rows: they stand for a quiet-
   window re-run rather than a verdict (a targeted re-run attempted under load ~12 did
   not complete in 8 minutes and was killed). The shipped tree's 636/0/0 was measured
   on the build box. The atom.cljc change is additionally inert on the JVM by
   construction (a `:glj` reader branch the JVM reader never selects); every other
   change is inside the glojure adapter roots, which no other seat loads.

## Further walls found on the port's road — four fixed, one named

All fixes are in Eve's glojure adapter layer; the port stayed byte-identical vanilla at
every point (read-only reference: atom.broker worktree datascript-multihost2).

- **W2 — frozen-refer load order** (diagnosed, seat contract): glojure has no native
  `clojure.core/satisfies?`; the completion interns it. A namespace with an explicit
  `(:refer-clojure :exclude [...])` form created *before* eve.host-completion loads never
  maps it, so a direct `(require 'eve.atom)` entry loads but its swap path cannot resolve
  `satisfies?` (atom.cljc:770). Eve's own runner docstring states the contract
  ("eve.host-completion loads first"); entries honoring it are unaffected. Hardening
  candidate: pre-declare the vars in glj-eve's boot patch set so snapshots refer a var the
  completion later re-roots, as on the JVM.
- **W3 — stock namespaces glojure lacks**: the port died at "failed to load /clojure/data".
  Glojure v0.7.22 embeds clojure.walk/string but not clojure.data, clojure.set, clojure.edn
  (edn already shimmed by this completion). **Fixed**: `adapters/completion/glojure/src/
  clojure/set.glj` (stock set operations) and `.../data.glj` (EqualityPartition + Diff
  protocols and diff, stock semantics) — the edn.glj precedent.
- **W4 — vanilla deftype naming JVM interfaces** (the F-LRU class, on glj): the completion's
  deftype emitted extend-type with protocols as written, so the extend wrapper swapped into
  a Class object — the :132 nil-deref again. **Fixed** in host_completion.glj: deftype/reify
  translate each impl — the atom interfaces onto the completion's own protocols (mirroring
  the types adapter's table), every other JVM interface onto eve.core's protocols through
  `eve.deftype-proto.proto-map`'s canonical table (loaded on first use; splits split,
  CLJ-only dropped), emitting `(require 'eve.core)` first when a translated impl names
  eve.core. Plus `clojure.core` get/assoc/contains?/count/seq re-rooted to answer through
  eve.core's protocols for types (dispatch named by the types adapter via the new
  `set-coll-dispatch!` hook in adapters/types/glojure). Measured: vanilla
  datascript.lru alone — `(get (assoc (lru/cache 2) :a 1) :a)` → 1, miss → :none,
  contains? → true. Known table gap: Associative's entryAt has no eve.core counterpart
  (dropped, as in the cljs translation) — `(find lru k)` is not yet answered on this seat.
- **W4b — tag names**: datascript.db's patch-tag (db.cljc:82 — the F-JOLT-TAG site) assocs
  `:tag clojure.core$long`, an AOT class name on the JVM and nothing on glojure. **Fixed**:
  the completion interns `clojure.core$long/$double/$boolean` as the fns themselves.
- **W4c — fn-shaped reify**: db's comparator factory reifies java.util.Comparator +
  clojure.lang.IFn + IFn$OOL. **Fixed**: a reify of only the fn interfaces is a plain fn of
  its compare (else invoke) arities. (Glojure's syntax-quote qualifies the imported
  IFn$OOL to the defining ns, so that interface is matched by name.)
- **W5 — NAMED, not fixed: extend-clj's `deftype*`, in datascript.conn.** conn dies at load
  ("Parameter declaration missing", glojure analyzer) from `(extend/deftype-atom Conn ...)`
  (conn.cljc:11). extend-clj's :clj face emits `deftype*` — the JVM compiler's primitive
  form — with generated bodies calling sibling methods through dot forms ((.deref-impl
  this), (.swap* this ...)); neither has a seat meaning here. Conn is DataScript's
  connection type; the flagship leg cannot start without it. **Disposition** (adapter
  layer, the goog-shim shape): an `extend_clj/core.glj` adapter namespace in Eve's glojure
  completion implementing deftype-atom over the completion's deftype (deref-impl /
  compare-and-set-impl custom; swap*/validate/notify-watches as fns over them; IRef
  watches/validator as volatile fields) plus add-watch/remove-watch wrappers for types.
  Alternative: complete deftype* itself — larger, no other emitter in evidence. Sized as a
  focused follow-on piece, not a wall-shim. Behind it, unreached: datascript.query-v3
  constructs java.util.HashMap/ArrayList/HashSet (not on the flagship's require path).

## Files changed

- `src/eve/atom.cljc` — the F-GLJ-WALL fix (3 splices).
- `adapters/completion/glojure/src/eve/host_completion.glj` — interface translation in
  deftype/reify, collection dispatch on types, tag interns, fn-shaped reify.
- `adapters/completion/glojure/src/clojure/set.glj`, `.../data.glj` — new stock shims.
- `adapters/types/glojure/src/eve/types.glj` — set-coll-dispatch! filler.
- `fix/STATUS-0931-glj.md`, `fix/smoke_main.cljc`, this report — lane records + smoke driver.
