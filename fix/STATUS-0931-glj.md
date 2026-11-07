# STATUS — Eve 0.9.3.1 adapter fix: F-GLJ-WALL (glojure)

- Lane: Lane M, sole writer. Branch fix/0931-glj off origin/main (0.9.3 release merge), worktree ~/workspace/wt/eve-0931-glj.
- Defect: Eve itself fails to load on glojure. Load reaches eve.atom -> eve.guard evaluation and dies "runtime error: invalid memory address or nil pointer dereference" at adapters/completion/glojure/src/eve/host_completion.glj:132 — the 1-arity clojure.core/swap! var-root wrapper: (if (host-ref? a) (core a f) (-swap a f)). Trips while extending LazyMap. Pre-existing wall (STM Go chase, Phase-1 F10).
- host-ref? = (not (instance? clojure.lang.IRecord x)) — prime suspect surface: the wrapper's dispatch on a glojure value during load-time protocol extension.
- Canonical rig: bb glj-eve builds target/glj-eve/glj-eve (impls/glojure/build.sh, glojure v0.7.22 + eve Go packages + completion patches); run with GLJ_CLASSPATH = hosts.edn :glj roots (+ test roots for the runner), glj-eve -e form. glj has no -M.
- Environment: this box has NO go toolchain and load ~10 at pickup; equiv-fix tree carries a cmd/glj-eve source dir. Next: locate or build a glj-eve binary (install go, or build box), then minimal repro: (require 'eve.atom) under the :glj roots.
- Proofs owed: (1) eve.atom load chain loads on glj; (2) shared-atom smoke on glj; (3) vanilla DataScript port loads on glj + flagship glj leg lands (or next wall named); (4) JVM suite spot rows unregressed.

## Diagnosis (measured, 2026-11-06 evening)
- Repro (7 s): GLJ_CLASSPATH=(bb classpath glj), stm-glj prebuilt glj-eve, (require 'eve.atom) -> panic: eve/guard.clj evaluating (extend LazyMap clojure.lang.ILookup ...) -> completion extend wrapper (host_completion.glj:95) does (swap! p ...) -> swap! wrapper :132 routes to glojure core swap! -> nil pointer.
- Mechanism, isolated by probes: the completion's own protocols are ATOMS (P4: eve.host-completion/IDeref is a *lang.Atom); glojure's own protocols are Class objects (clojure.lang.ILookup is a *lang.Class). (swap! clojure.lang.ILookup identity) alone reproduces the :132 nil-deref (P3). Bare glojure core swap! on a real atom is fine (P1); bare glojure extend REJECTS interface classes outright ("clojure.lang.ILookup is not a protocol", P5).
- Root cause: eve.guard is a JVM-only file (its own docstring: "a JVM host only") whose deftypes implement raw JVM interfaces. eve.atom requires it under the :clj reader branch; glojure reads :clj, so the JVM-only file is pulled into the glojure load. The bb seat already excludes it in the same three splices; cljs never takes it. The wall is the :clj overreach, not the completion's extend machinery (which is correct for the protocols it owns).
- :glj verified a live reader feature on this seat (probe ns took the :glj branch).

## Fix (src/eve/atom.cljc, 3 splices, paralleling :bb exactly)
- ns require, the guard-deref defn, and the mmap-deref cond clause now also exclude :glj. On glojure, eve.atom loads without the guard and deref falls through to plain-deref — the same shape as bb and cljs. No guard state machinery is involved (no atoms/journals/counters added anywhere).

## Proof 1: PASS
- (require 'eve.atom) under the :glj roots on the prebuilt glj-eve: ":eve-atom-loaded" (32 s full chain incl. eve.core). Was: panic at guard evaluation.

## Proof runs launched
- P2: shared suite on glj via eve.runner.glj-main (prebuilt stm-glj glj-eve, GLJ_CLASSPATH = :glj roots + test), namespaces eve.atom-test first — log fix/logs/p2-glj-atom-test.log.
- P3: flagship glojure leg (init+worker+readback, fresh domain) with Eve = this worktree, port from datascript-flagship2 drivers + multihost2 lib-ds — log fix/logs/p3-glj-flagship.log.
- P4: JVM spot rows (eve.atom-test on JVM) — log fix/logs/p4-jvm-atom-test.log.

## Proof 2: PASS (shared-atom smoke on glj, cross-process)
- Write process: {:mode :write, :deref {:v [0 1 2 3 4 5 6 7 8 9], :n 10}, :seal {:ptr 87, :name-hash 1611018502, :blocks 14, :digest -851564927}} — 10 swap!s on a persistent (disk) domain atom, all landed.
- Fresh read process: identical :deref and identical :seal (digest -851564927). Cross-process shared atom WORKS on glojure.

## Second wall found + mechanism (load order / frozen refers)
- Direct (require 'eve.atom) entry: eve.atom LOADS, but its own swap path dies at runtime "unable to resolve symbol: satisfies?" (atom.cljc:770, also :755). Requiring eve.host FIRST (the completion's stated contract: "loaded first by eve.host") makes the same smoke pass.
- Mechanism: glojure has no native clojure.core/satisfies?; the completion CREATES it by intern. A namespace with an explicit (:refer-clojure :exclude [...]) form (eve.atom and most Eve namespaces) gets its core refer set materialized at ns creation; a namespace created before eve.host-completion loads therefore never maps satisfies?/extends?. Default namespaces resolve dynamically (the -e user ns worked). On JVM/bb/jank seats core satisfies? always exists, so the intern merely re-roots it there.
- Disposition: seat contract today = eve.host first (runner + drivers honor it). Hardening candidate for the adapter layer: pre-declare clojure.core/satisfies? + extends? vars in glj-eve's boot (impls/glojure patch set) so frozen snapshots refer a var the completion later re-roots — same shape as the JVM. To be sized after P3.

## Third wall: stock namespaces glojure lacks (adapter shims added)
- After the guard fix, the port load reached datascript.db and died: "failed to load /clojure/data: not found in load path". Bare-seat probe: glojure v0.7.22 embeds clojure.walk + clojure.string but NOT clojure.data, clojure.set, clojure.edn (edn already shimmed by this completion).
- Port usage: clojure.data's EqualityPartition/Diff protocols (extended to DB at db.cljc:843-848) + diff; clojure.set's union/intersection/difference/subset? (parser/query/query_v3).
- Fix, following the completion's own clojure/edn.glj precedent: adapters/completion/glojure/src/clojure/set.glj (stock set operations) and .../data.glj (the two protocols + diff over Clojure values, stock semantics). The port stays byte-identical vanilla, per the host-adapter ruling.

## Fourth wall: vanilla deftype naming JVM interfaces (the F-LRU class, on glj)
- datascript.lru's plain deftype names clojure.lang.ILookup + clojure.lang.Associative (the :clj face). The completion's deftype emitted extend-type with protocols as written -> the extend wrapper swapped into the Class object -> the :132 nil-deref again. A vanilla deftype library cannot work on this seat until the completion translates interfaces, as Eve's types adapter already does for eve-deftypes.
- Fix (adapters/completion/glojure/src/eve/host_completion.glj):
  - deftype/reify translate each impl: completion-interface-map (IDeref/IAtom/IAtom2/IPending/IBlockingDeref -> this completion's protocols, methods renamed, mirroring the types adapter's table); any other JVM interface -> eve.core's protocols through eve.deftype-proto.proto-map's canonical CLJ->CLJS table (loaded on first use; splits split, CLJ-only dropped, marker protocols recorded), emitting (require 'eve.core) before the extends when a translated impl names eve.core.
  - clojure.core get/assoc/contains?/count/seq re-rooted: for a deftype/reify instance extended to the matching eve.core protocol they answer through it (a record's own answers are its fields'); the dispatch is named by eve's types adapter via the new set-coll-dispatch! hook (adapters/types/glojure/src/eve/types.glj, beside set-type-equality!). Everything else keeps glojure's answers.
- Measured: Eve chain regression :chain-ok; datascript.lru alone: (get (assoc (lru/cache 2) :a 1) :a) -> 1, miss -> :none, contains? -> true.
- Known gaps in the translation (by table design, same as cljs): Associative's entryAt has no eve.core counterpart (dropped) — (find lru k) is not yet answered on this seat.

## Fifth wall (NAMED, with disposition): extend-clj's deftype* — datascript.conn
- Chain state: datascript.db LOADS fully on glj (lru, clojure.data/set shims, core$long interns, fn-reify all passed). datascript.conn dies at load: "Parameter declaration missing" (glojure analyzer), from (extend/deftype-atom Conn ...) at conn.cljc:11.
- Mechanism: extend-clj (DataScript's own dependency, lib-ds-deps) implements deftype-atom on its :clj face by emitting deftype* — the JVM compiler's primitive form ((deftype* name class [fields] :implements [...] methods...)) — with generated method bodies that call sibling methods through dot forms ((.deref-impl this), (.swap* this f args), (.validate this ...), (.resetMeta this ...)). glojure has no deftype*; the completion's deftype is a different construction (record + extends), and raw sibling dot-calls on a record have no seat meaning.
- Why it matters: Conn is DataScript's connection type (deref/compare-and-set custom over an Eve atom, swap!/reset!/watches/meta through the generated IAtom/IRef surface). The flagship leg cannot start without it: create-conn is the scenario's entry.
- Disposition (adapter-layer, per the host-adapter ruling; the port stays vanilla): Eve's glojure completion gains an extend_clj/core.glj adapter namespace (the nbb goog-shim shape: the seat provides the namespace the library expects) implementing deftype-atom over the completion's deftype — deref-impl/compare-and-set-impl as the only custom bodies, swap*/validate/notify-watches as fns over them, IRef watches/validator via volatile fields — plus clojure.core add-watch/remove-watch wrappers for types in the completion (same re-root pattern as get/assoc). Alternative: complete deftype* itself (method-name->interface assignment + sibling-call rewriting) — larger, and it would serve any deftype* emitter, but no other emitter is in evidence. Sizing: a focused lane-day, not a wall-shim. NOT attempted here; the five walls above were each small and in-pattern, this one is a construction.
- Also noted behind it (unreached): datascript.query-v3 constructs java.util.HashMap/ArrayList/HashSet (query.cljc is the flagship's engine; v3 is not on its require path — conn is).

## Fixes banked on this branch so far
1. src/eve/atom.cljc — :glj exclusion of JVM-only eve.guard in three splices (F-GLJ-WALL itself).
2. adapters/completion/glojure/src/clojure/set.glj + data.glj — stock namespaces glojure lacks.
3. host_completion.glj — deftype/reify JVM-interface translation (proto-map + completion interfaces); core get/assoc/contains?/count/seq dispatch on types; clojure.core$long/$double/$boolean tag interns; fn-shaped reify (Comparator/IFn) as a plain fn.
4. adapters/types/glojure/src/eve/types.glj — set-coll-dispatch! filler.

## Proof 2b: shared suite rows on glj PASS
- eve.runner.glj-main (prebuilt binary, :glj roots + test): eve.domain-open-test + eve.collection-hash-test — "Ran 4 tests containing 23 assertions. 0 failures, 0 errors." (:summary {:test 4, :pass 23, :fail 0, :error 0}; log fix/logs/p2b-glj-suite-rows.log). Includes the reopened-domain cross-process row. First attempt (P2) used a nonexistent namespace name (eve.atom-test) — runner usage error, not a product failure.
- P4 (JVM regression): running as a broad clojure -M:jvm-test pass on this branch (log fix/logs/p4-jvm-spot2.log; the first attempt died on this box's Maven egress without CLJ_CONFIG — environment, retried with the seeded local-repo config).

## Walls 4b/4c (banked with this commit): tag interns + fn-shaped reify
- datascript.db's patch-tag (db.cljc:82, the F-JOLT-TAG site) assocs :tag clojure.core$long — on the JVM that symbol resolves as the AOT class of core's long fn; glojure has no such class. The completion interns clojure.core$long/$double/$boolean as the fns themselves (a tag is a hint; the completion strips tags). db.cljc then loads fully.
- datascript.db's comparator factory reifies java.util.Comparator + clojure.lang.IFn + IFn$OOL: a reify of only the fn interfaces is a plain fn of its compare (else invoke) arities in the completion's reify. Glojure's syntax-quote qualifies the imported IFn$OOL to the defining ns (datascript.db/IFn$OOL), so the check matches that interface by name.
- After these: datascript.db LOADS; the chain stops in datascript.conn at extend-clj's deftype* (fifth wall, above).

## Proof 4: JVM broad pass — 636 tests / 10,606 assertions / 2 F / 1 E, all three reds environmental
- FAIL thread_fold chunks-are-sized (measured comparison inverted under load 10-12), FAIL thread round-trips (212 ms vs 100 ms bar, same load), ERROR three-servers-run-one-pmap (sandbox TCP permission refused the test's localhost fetch). None touches this branch's code paths; the two timing rows are contention-tainted, owed a quiet-window re-run (attempted re-run under load ~12 killed at 8 min). Shipped tree's 636/0/0 was a build-box measurement. Log fix/logs/p4-jvm-spot2.log.
- Final report: fix/REPORT-0931-glj.md. Lane complete at proof 3's named wall (W5 deftype*) with proofs 1, 2, 2b green and 4 dispositioned.
