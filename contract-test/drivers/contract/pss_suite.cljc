(ns contract.pss-suite
  "Runner for the UPSTREAM persistent-sorted-set test suite (tag 0.2.3,
   test-clojure/) against the Eve port. The .cljc namespaces run unchanged:
   core, small, stress. The storage namespace is excluded: it imports the
   JVM form's Java classes (me.tonsky.persistent_sorted_set.PersistentSortedSet
   et al.) and tests that implementation's storage protocol — not the
   portable form ported here."
  ;; eve.host + eve.atom: the standard Eve entry load — Eve's codec writers
  ;; register as those namespaces load; without them, encoding an Eve-vector
  ;; field fails with ":vec writer not registered" (codec.cljc). Every Eve
  ;; program entry loads them; the behave drivers do too.
  (:require [eve.host]
            [eve.atom]
            [clojure.test :as t]))

(defn -main [& [mode & [ns-name var-name]]]
  ;; mode "one" <ns> <var>: run a single test var in this (fresh) process —
  ;; used to separate a slab-capacity failure from a behavior failure: each
  ;; var gets the process-default domain to itself.
  ;; mode "portable": core+small only — for seats where stress's :clj-only
  ;; storage dependency (the JVM form's Java classes) cannot load at all
  ;; (bb/SCI, jolt). The stress namespace itself is unchanged either way.
  (if (= mode "one")
    (let [v (do (require (symbol ns-name))
                (resolve (symbol ns-name var-name)))]
      (t/test-var v)
      (println (pr-str {:suite :upstream-pss :mode "one" :var (str v)
                        :summary (try (deref t/*report-counters*)
                                      (catch #?(:cljs :default :default Exception) _
                                        :unavailable))})))
    (let [nses (if (= mode "portable")
                 ['me.tonsky.persistent-sorted-set.test.core
                  'me.tonsky.persistent-sorted-set.test.small]
                 ['me.tonsky.persistent-sorted-set.test.core
                  'me.tonsky.persistent-sorted-set.test.small
                  'me.tonsky.persistent-sorted-set.test.stress])
          _ (apply require nses)
          r (apply t/run-tests nses)]
      (println (pr-str {:suite :upstream-pss :mode (or mode "full")
                        :summary (select-keys r [:test :pass :fail :error])})))))
