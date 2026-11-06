# STATUS — Eve 0.9.3.1 adapter fix: Babashka (F-LRU-BB)

- Branch: `fix/0931-bb` off origin/main (0.9.3 release merge), worktree
  ~/workspace/wt/eve-0931-bb. Sole writer.
- Defect: vanilla deftype declaring clojure.lang.Associative / ILookup
  piecemeal (DataScript 1.8.1's datascript.lru, byte-identical upstream)
  is refused by bb's SCI ("deftype supports full map types (add
  IPersistentMap...)"), so the library cannot load on Eve's bb seat.
  Control (flagship lane): stock bb, no Eve, fails identically — the
  declaration shape vs SCI, not an Eve interaction. Fix belongs in Eve's
  bb adapter layer (John's ruling); the library stays untouched.

## Log
- 5:47 PM pickup: worktree created; evidence read (REPORT-FLAGSHIP.md
  F-LRU-BB). Eve's bb types adapter located: adapters/types/bb/src/
  eve/types.clj — per-host hooks for Eve's OWN deftype machinery
  (eve.deftype / deftype-proto); SCI takes "protocols, Object and
  IPersistentMap — nothing else" (its docstring, measured on 1.13.226).
  A foreign namespace's clojure.core/deftype is NOT routed through those
  hooks — the flagship driver loads eve.host/eve.atom first, then the
  port dies in SCI's own deftype. Next: find eve.host's completion load
  (the seat's install window) + probe SCI: (a) reproduce piecemeal
  refusal, (b) can a wrapper installed on clojure.core/deftype at
  eve.host load time rewrite the interface list (+ synthesize the
  missing map methods) before SCI's deftype sees it, (c) what method
  set SCI demands once IPersistentMap is listed.

- Probes (fix/probes/, bb 1.13.226 = flagship2's lane-local bin/bb):
  P1 reproduces the refusal verbatim on the vanilla LRU shape.
  P2: appending clojure.lang.IPersistentMap (no new methods) makes SCI
  accept the form. P3b/P3c: ILookup alone and Associative alone each
  trigger the rule; unqualified spellings pass (SCI matches the
  clojure.lang-qualified symbols only). Other clojure.lang interfaces
  (Counted, Seqable, IPersistentCollection, IHashEq, Indexed, IMeta,
  IObj, IPersistentStack, IEditableCollection, IReduce, ISeq,
  IPersistentVector/Set) are refused with a DIFFERENT error ("only
  support protocol implementations") — outside this finding.
  P4 (real file): vanilla lru.cljc + appended IPersistentMap, upstream
  datascript.test.lru under bb = 2 tests / 20 assertions / 0 failures.
  Undeclared map methods (count/seq/empty) throw "Method not
  implemented" — the type's offered surface is unchanged in kind (the
  JVM type offers neither Counted nor IPersistentCollection either);
  map? answers true (SCI's presentation of every map deftype).
  P5-P7 (the install): a first-loaded namespace CAN re-root
  clojure.core/deftype for namespaces compiled after it (the jank
  completion's measured pattern). SCI calls a macro var's root with
  (form env . args) ONLY when the root is a plain fn — a defmacro
  product is dispatched to its inner fn with plain args instead
  (measured, P5/P6). P7: plain-fn presenter + delegation to the
  captured original = piecemeal lib loads and behaves (:check 1).
- Design: new seat completion adapters/completion/bb (ns
  eve.host-completion, the file eve.host loads first), root placed
  before completion/clojure on the :bb rows of hosts.edn and bb.edn.
  Presenter appends clojure.lang.IPersistentMap at the END of opts
  (P2/P4 position) when a qualified ILookup/Associative is declared
  without it; every other deftype passes through argument-identical.
  Next: implement, smoke Eve load, then proofs 1-3.

- IMPLEMENTED: adapters/completion/bb/src/eve/host_completion.clj —
  eve.host-completion for the bb seat: captures SCI's deftype,
  re-roots clojure.core/deftype with a plain-fn presenter appending
  clojure.lang.IPersistentMap (end of opts) iff a qualified
  ILookup/Associative is declared without it; all else passes through
  argument-identical. hosts.edn :bb and :bb-minimal rows + bb.edn
  :paths: adapters/completion/bb/src inserted before
  completion/clojure. Smoke (bb 1.13.226, this tree's classpath):
  eve.host/eve.atom load, atom swap {:n 2}, a foreign piecemeal
  deftype defined after Eve loads gets/contains/map? correctly,
  Eve sorted-map/vector literals unaffected.
- Next: proof 1 (require the untouched port + upstream
  datascript.test.lru under bb 1.13.226), proof 2 (flagship bb leg),
  proof 3 (bb test:bb full suite, 297-test baseline).

- FINDING F-IFNOOL (new, behind F-LRU-BB; reported per the findings
  rule): with the presenter installed, loading the port proceeds PAST
  datascript.lru and stops at datascript/db.cljc:13 — the VANILLA
  (:import clojure.lang.IFn$OOL) (vanilla-ds line 11; the class's
  only use is the defcomp macro's reify, db.cljc:475). Measured on
  bb 1.13.226: (Class/forName "clojure.lang.IFn$OOL") SUCCEEDS (the
  class is in bb's image) but SCI's :import resolution consults only
  its curated class registry, which lacks it (RT and ArrayList
  imports resolve; IFn$OOL does not; a prior Class/forName does not
  prime it; clojure.core/import fails identically). The registry's
  runtime extension, sci.core/add-class!, requires the embedder's
  ctx, which bb does not expose to classpath code (sci.core is
  require-able; its add-class!/add-import! are [ctx name class];
  no user-reachable ctx: sci.impl.* namespaces are not registered
  for user code; babashka.impl.deftype exposes only ->scimap).
  CONCLUSION: no presentation in Eve's adapter layer can satisfy
  this import; the fix belongs to bb's class registry (upstream
  observation, repro above — our records only, per standing rule).
  Tasked proof (1)'s full require and proof (2)'s flagship leg
  (whose worker loads datascript.db) are blocked by F-IFNOOL, not
  by F-LRU-BB. F-LRU-BB itself is proven at its own frontier:
  proof 1' = untouched port's datascript.lru loads + upstream
  datascript.test.lru passes under the fixed Eve (next run);
  proof 3 (bb suite) unaffected.

- PROOF 1' GREEN (fix/logs/proof1-lru.log): bb 1.13.226, Eve loaded
  first from this tree, the UNTOUCHED port on the classpath:
  (require 'datascript.lru) succeeds; upstream datascript.test.lru
  = 2 tests / 20 assertions / 0 failures / 0 errors. Frontier check:
  (require 'datascript.db) now stops ONLY at db.cljc:13, the
  F-IFNOOL import — the F-LRU-BB wall is gone from the path.
- PROOF 3 launching on the BUILD BOX (this box's load is ~8; the
  suite is a heavy leg): worktree rsync'd to j.slaa.us:~/eve-0931-bb,
  lane bb 1.13.226 staged there, run = bb test:bb detached, log
  fix/logs/bb-suite-buildbox.log (mirrored back here after).

- PROOF 3 IN FLIGHT on the build box: first launch died instantly —
  the test:bb task shells out to a bare `bb` and none is on the build
  box's PATH (diagnosed from its log, not retried blind). Relaunched
  with the staged 1.13.226 on PATH as bb; the runner process is live
  and its classpath shows adapters/completion/bb/src ahead of
  completion/clojure; log ~/eve-0931-bb-suite.log on j.slaa.us,
  progressing through test namespaces.

- PROOF 3 GREEN (build box, log mirrored to fix/logs/
  bb-suite-buildbox.log): Ran 297 tests containing 5663 assertions.
  0 failures, 0 errors — baseline matched exactly.
- bb 1.3.190 check: Eve's own load stops at the pre-existing 0.9.3
  floor ("Could not resolve symbol: sci.impl.deftype/hashCode",
  eve.vec/eve.map) — unchanged, out of scope.
- DONE. Final declaration: fix/REPORT-0931-bb.md (this bank) + chat.
  F-LRU-BB fixed in Eve's bb adapter layer and proven at its
  frontier; tasked proofs (1)/(2) blocked by the separately-named
  F-IFNOOL (bb class registry), evidence in the report.
