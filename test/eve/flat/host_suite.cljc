(ns eve.flat.host-suite
  "Lane H3 — the <host>-flat-suite runner lists (IMPL-PLAN §3.4).

   For every host in hosts.edn: the flat test namespaces that host runs
   (:namespaces), the ones it does not (:excluded, each with its reason), and
   the runner that runs them (:runner). Hosts with no slab adapter at all are
   in `no-suite`, with the reason. A namespace enters a host's :namespaces
   only when its file is in the tree; Wave 1 namespaces sat in :excluded
   until the collation merge landed their files, so the lists can never
   name a namespace nobody could run. That flip was applied at the
   Wave 2 second pass of the collation (2026-11-12): the eleven Wave 1
   suites are in the JVM-class hosts' :namespaces below, and the hosts
   that still exclude them carry their measured host reasons.

   The board — which hosts actually ran green, where, on what binary — is
   doc/flat/H3-STATUS.md. These lists say what a host's suite IS; the
   board says what it DID.")

(def ^:private wave1-jvm-family
  "Wave 1 + Wave 0 flat suites a JVM-class host (loads .clj and .cljc)
   runs. All fifteen files are present in the collated tree: the first
   four were in H3's base tree; the remaining eleven (image, alloc-run,
   place, tally-count, transfer, build, column, container, check, list,
   sister) entered at the Wave 2 second pass of the collation
   (2026-11-12), when this lane was merged onto the collated tree —
   the flip H3-STATUS's rebase call named."
  '[eve.flat.requirements-test
    eve.flat.contract-test
    eve.flat.chunk-test
    eve.flat.host-suite-test
    eve.flat.image-test
    eve.flat.alloc-run-test
    eve.flat.place-test
    eve.flat.tally-count-test
    eve.flat.transfer-test
    eve.flat.build-test
    eve.flat.column-test
    eve.flat.container-test
    eve.flat.check-test
    eve.flat.list-test
    eve.flat.sister-test])

(def ^:private not-in-any-suite
  "Manifest namespaces in no host's :namespaces yet, each with its
   reason. (Named not-yet-landed until the Wave 2 second pass: the
   eleven Wave 1 entries it used to carry flipped into
   wave1-jvm-family at that merge, and the three Wave 2 entries whose
   files landed with it — disk, journal, io — now carry their true
   state.)"
  '{eve.flat.disk-test        "H1's file landed in the collated tree at the Wave 2 second pass; suite membership unjudged — enters when a run judges it"
    eve.flat.journal-test     "H2's file landed in the collated tree at the Wave 2 second pass; suite membership unjudged — enters when a run judges it"
    eve.flat.dataset-batch-test "H6's file landed in the collated tree at the Wave 2 third pass; suite membership unjudged — enters when a run judges it"
    eve.flat.chunk-leaf-test  "H7's file has not landed in any tree yet (the lane is gated on fork F3)"
    eve.flat.io-test          "H8's file landed in the collated tree at the Wave 2 second pass; suite membership unjudged — enters when a run judges it"})

(def ^:private wave1-not-on-basilisp
  "The eleven Wave 1 suites on basilisp: their files are in the collated
   tree, but no namespace loads on basilisp 0.5.1 at this pin (measured
   on J; the board is doc/flat/H3-STATUS.md), so they stay excluded
   for the host reason, not the landed one."
  (into {}
        (map (fn [n] [n "no namespace loads on basilisp 0.5.1 at this pin (measured on J, H3 board) — enters when basilisp can load the flat suites"]))
        '[eve.flat.image-test eve.flat.alloc-run-test eve.flat.place-test
          eve.flat.tally-count-test eve.flat.transfer-test eve.flat.build-test
          eve.flat.column-test eve.flat.container-test eve.flat.check-test
          eve.flat.list-test eve.flat.sister-test]))

(def ^:private wave1-not-on-jank
  "The eleven Wave 1 suites on jank: same shape as basilisp — files in
   the tree, no namespace loads on jank at this pin (measured on J,
   each namespace alone; H3-STATUS board)."
  (into {}
        (map (fn [n] [n "no namespace loads on jank at this pin (measured on J, H3 board) — enters when jank can load the flat suites"]))
        '[eve.flat.image-test eve.flat.alloc-run-test eve.flat.place-test
          eve.flat.tally-count-test eve.flat.transfer-test eve.flat.build-test
          eve.flat.column-test eve.flat.container-test eve.flat.check-test
          eve.flat.list-test eve.flat.sister-test]))

(def ^:private jvm-suites-not-run-on-cljs
  "The eleven Wave 1 suites on the ClojureScript-class hosts: JVM/bb
   test files (.clj/.cljc machinery); those hosts run the Node-compiled
   chunk suite, and the suites' behavior is judged on the JVM-class
   seats. Enters if a compiled run ever judges them."
  (into {}
        (map (fn [n] [n "a JVM/bb suite (.clj/.cljc test machinery); this host runs the Node-compiled chunk suite — enters if a compiled run judges it"]))
        '[eve.flat.image-test eve.flat.alloc-run-test eve.flat.place-test
          eve.flat.tally-count-test eve.flat.transfer-test eve.flat.build-test
          eve.flat.column-test eve.flat.container-test eve.flat.check-test
          eve.flat.list-test eve.flat.sister-test]))

(def ^:private cljs-only
  '{eve.flat.chunk-node-test "the Node ClojureScript suite — X1's runner, not a JVM-class host's"
    eve.flat.image-node-test "the Node ClojureScript image suite — H4's lane"})

(defn- jvm-family-suite [runner]
  {:runner     runner
   :namespaces wave1-jvm-family
   :excluded   (merge not-in-any-suite cljs-only)})

(def ^:private bb-chunk-wall
  "Measured on J (h3-bb-suite6.log): eve.flat.chunk's ns imports
   clojure.lang.Murmur3 under :clj with no :bb branch, and bb has no
   Murmur3 class (Eve's own precedent: deftype_proto/sio.cljc — '(hash
   long) is Murmur3's hashLong; bb has no Murmur3 class'). The chunk's
   hasheq folds (chunk.cljc:1239-1269) need the same :bb treatment at
   collation; until then chunk-test cannot load on any bb."
  '{eve.flat.chunk-test "eve.flat.chunk imports clojure.lang.Murmur3 under :clj with no :bb branch; bb has no Murmur3 class (measured on J, h3-bb-suite6.log) — returns when chunk.cljc gains a :bb branch at collation"})

(defn- bb-suite [runner]
  (let [s (jvm-family-suite runner)]
    (-> s
        (update :namespaces (fn [nses] (vec (remove #{'eve.flat.chunk-test} nses))))
        (update :excluded merge bb-chunk-wall))))

(def suites
  {:jvm        (jvm-family-suite "clojure -X:jvm-test, one -n per namespace (the reference seat)")
   :bb         (bb-suite "bb (>= 1.12.214; on J, PATH bb is 1.3.190 and is refused by eve.host-completion — call the current bb) -cp src:<hosts.edn :bb roots>:test:<test.check 1.1.1 jar, the :jvm-test dep> -m eve.runner.bb-main test <ns...>")
   :bb-minimal (bb-suite "bb (as :bb) -cp src:<hosts.edn :bb-minimal roots>:test:<test.check 1.1.1 jar> -m eve.runner.bb-main test <ns...>")
   :cljr       (jvm-family-suite "ClojureCLR runner over hosts.edn :cljr roots (no in-tree task; binary + roots)")
   :lpy        {:runner     "basilisp run over (reverse (roots :lpy \"test\")) -n eve.runner.lpy-main test <ns...> (bb.edn test:lpy form; basilisp 0.5.1)"
                :namespaces []
                :excluded   (merge not-in-any-suite cljs-only wave1-not-on-basilisp
                                   '{eve.flat.requirements-test "basilisp 0.5.1 cannot macroexpand its api-surface probe's is-form (measured on J, h3-lpy3.log: CompilerException, MACROEXPANSION)"
                                     eve.flat.contract-test     "basilisp 0.5.1 cannot macroexpand its (is (thrown? ...)) forms (measured on J, h3-lpy3.log: CompilerException, MACROEXPANSION)"
                                     eve.flat.chunk-test        "a .clj file; not a basilisp module (measured on J, h3-lpy3.log: ImportError, namespace not found)"
                                     eve.flat.host-suite-test   "basilisp cannot compile its java.io.File constructor form (measured on J, h3-lpy3.log: CompilerException, MACROEXPANSION)"})}
   :jolt       (jvm-family-suite "jolt -Scp src:<hosts.edn :jolt roots>:test:<test.check 1.1.1 sources> -m eve.runner.jolt-main test <ns...> (bb.edn test:jolt form; jolt v0.8.23 on J)")
   :clje       (jvm-family-suite "ClojureErl runner over hosts.edn :clje roots (no in-tree task; binary + roots)")
   :glj        (jvm-family-suite "glj-eve (impls/glojure build) over hosts.edn :glj roots")
   :jank       {:runner     "LC_ALL=en_US.UTF-8 jank --module-path src:<hosts.edn :jank roots>:adapters/completion/jank/test:adapters/slab-io/test:test run-main eve.runner.jank-main -- test <ns...> (bb.edn test:jank form)"
                :namespaces []
                :excluded   (merge not-in-any-suite cljs-only wave1-not-on-jank
                                   '{eve.flat.requirements-test "requires clojure.java.io under :clj; jank has no clojure.java.io module (measured on J, h3-jank-probe.log: runtime/module-not-found)"
                                     eve.flat.contract-test     "requires clojure.java.io under :clj; jank has no clojure.java.io module (measured on J, h3-jank-probe.log: runtime/module-not-found)"
                                     eve.flat.chunk-test        "a .clj file; not a jank module at all (measured on J, h3-jank-probe.log: module eve.flat.chunk-test could not be found)"
                                     eve.flat.host-suite-test   "java.io.File over the source tree; jank resolves no java.io.File (measured on J, h3-jank-probe.log)"})}
   :cljs       {:runner     "clojure -M:node-test (deps.edn) — the Node-compiled ClojureScript suite (eve.flat.sister-test: the :flat-sister-test build, shadow-cljs.edn)"
                ;; sister-test entered 2026-11-17 (flat lanes U4a): the sister is realized on ClojureScript and a
                ;; compiled run judged the suite — 4 tests / 30 assertions green (its H5.5 / H5.6 tests are :clj)
                :namespaces '[eve.flat.chunk-node-test eve.flat.sister-test]
                :excluded   (dissoc (merge not-in-any-suite jvm-suites-not-run-on-cljs
                                           '{eve.flat.requirements-test "JVM/bb test machinery (slurp + var resolution over the manifest); the list it guards is host-independent"
                                             eve.flat.contract-test     "not yet run compiled; enters when a run judges it"
                                             eve.flat.chunk-test        ".clj JVM file; its bytes are judged on Node by chunk-node-test (X1.2)"
                                             eve.flat.host-suite-test   "the list-keeper: java.io.File over the source tree; runs on JVM-class hosts"
                                             eve.flat.image-node-test   "H4's lane"})
                                    'eve.flat.sister-test)}
   :nbb        {:runner     "nbb --classpath src:<hosts.edn :nbb roots>:test -m eve.runner.nbb-main test <ns...> (bb.edn test:nbb form)"
                :namespaces []
                :excluded   (merge not-in-any-suite jvm-suites-not-run-on-cljs
                                   '{eve.flat.requirements-test "JVM/bb test machinery; the list it guards is host-independent"
                                     eve.flat.contract-test     "not yet run interpreted; enters when a run judges it"
                                     eve.flat.chunk-test        ".clj JVM file; its bytes are judged by chunk-node-test"
                                     eve.flat.chunk-node-test   "eve.flat.chunk's CLJS side uses a js* form (chunk.cljc:84) SCI cannot resolve at analysis (measured on J, h3-nbb3.log: 'Unable to resolve symbol: js*', chunk.cljc:84:23) — the shadow-compiled :cljs seat runs it green; returns when the chunk's js* use is SCI-safe at collation"
                                     eve.flat.host-suite-test   "the list-keeper: java.io.File over the source tree; runs on JVM-class hosts"
                                     eve.flat.image-node-test   "H4's lane"})}
   :browser    {:runner     "bb test:browser (shadow build, browser worker)"
                :namespaces '[eve.flat.chunk-node-test]
                :excluded   (merge not-in-any-suite jvm-suites-not-run-on-cljs
                                   '{eve.flat.requirements-test "JVM/bb test machinery; the list it guards is host-independent"
                                     eve.flat.contract-test     "not yet run in a browser; enters when a run judges it"
                                     eve.flat.chunk-test        ".clj JVM file; its bytes are judged by chunk-node-test"
                                     eve.flat.host-suite-test   "the list-keeper: java.io.File over the source tree; runs on JVM-class hosts"
                                     eve.flat.image-node-test   "H4's lane"})}
   :scittle    {:runner     "a page's loader over hosts.edn :scittle roots (SCI in a browser worker)"
                :namespaces '[eve.flat.chunk-node-test]
                :excluded   (merge not-in-any-suite jvm-suites-not-run-on-cljs
                                   '{eve.flat.requirements-test "JVM/bb test machinery; the list it guards is host-independent"
                                     eve.flat.contract-test     "hosts.edn: the slab collections have not been run on scittle at all; enters when a run judges it"
                                     eve.flat.chunk-test        ".clj JVM file; its bytes are judged by chunk-node-test"
                                     eve.flat.host-suite-test   "the list-keeper: java.io.File over the source tree; runs on JVM-class hosts"
                                     eve.flat.image-node-test   "H4's lane"})}
   :cherry     {:runner     "cherry-cljs compiled file-by-file, run on Node, over hosts.edn :cherry roots"
                :namespaces '[eve.flat.chunk-node-test]
                :excluded   (merge not-in-any-suite jvm-suites-not-run-on-cljs
                                   '{eve.flat.requirements-test "JVM/bb test machinery; the list it guards is host-independent"
                                     eve.flat.contract-test     "not yet run under cherry; enters when a run judges it"
                                     eve.flat.chunk-test        ".clj JVM file; its bytes are judged by chunk-node-test"
                                     eve.flat.host-suite-test   "the list-keeper: java.io.File over the source tree; runs on JVM-class hosts"
                                     eve.flat.image-node-test   "H4's lane"})}})

(def no-suite
  {:vs   "Wave 3 (plan §4): ValueScript does not expand eve-deftype at this pin"
   :lg   "no slab, region or types adapter in hosts.edn — not a slab-I/O host (hosts.edn: not yet eve memory)"
   :cljd "no slab, region or types adapter in hosts.edn — not a slab-I/O host (hosts.edn: no memory-level atoms)"})
