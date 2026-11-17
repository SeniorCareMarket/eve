(ns eve.flat.host-suite-test
  "Lane H3 — other hosts (IMPL-PLAN §3.4, requirement H3.1): Wave 1's Z, T and
   C tests green on babashka first, then the remaining slab-I/O hosts, each
   host running its <host>-flat-suite from the runner lists in
   eve.flat.host-suite.

   This namespace is the list-keeper's test: it runs on any host and checks
   the lists themselves — every host in hosts.edn is dispositioned, every
   flat test namespace that exists in the tree is in a host's suite or out
   of it for a stated reason, and every listed namespace exists. The per-host
   green runs are the lane's board (doc/flat/H3-STATUS.md); a list that
   names a namespace nobody ran is caught there, not here."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eve.flat.host-suite :as hs]))

(defn- manifest-test-namespaces
  "Every eve.flat.* test namespace named by the requirement manifest
   (log rows and whole-suite kinds name no namespace)."
  []
  (let [m (edn/read-string (slurp "test/eve/flat/requirements.edn"))]
    (into #{}
          (comp (map val)
                (remove #(#{:log :suite} (:kind %)))
                (map :test)
                (map #(first (str/split % #"/"))))
          m)))

(defn- ns-exists?
  "A namespace exists in the tree under any of its source spellings.
   The path is the namespace munged the way the compiler spells it:
   dots to slashes, hyphens to underscores (chunk-test lives in
   chunk_test.clj). The unmunged spelling was this test's first defect:
   on J (2026-11-12, h3-jvm-lists.log) it reported all 41 hyphenated
   namespaces 'not in the tree' while their files sat in test/eve/flat/."
  [ns-sym]
  (let [base (str "test/" (-> (str ns-sym)
                              (str/replace "." "/")
                              (str/replace "-" "_")))]
    (boolean (some #(.exists (java.io.File. (str base %)))
                   [".cljc" ".clj" ".cljs"]))))

(deftest host-flat-suite
  (testing "every host in hosts.edn is dispositioned: a suite or a named reason it has none"
    (let [hosts    (set (keys (edn/read-string (slurp "hosts.edn"))))
          covered  (into (set (keys hs/suites)) (keys hs/no-suite))]
      (is (= hosts covered)
          (str "undispositioned: " (pr-str (set/difference hosts covered))))))
  (testing "babashka has a suite (the plan's first host), and it is not an exclusion"
    (is (contains? hs/suites :bb)))
  (testing "every listed namespace, on every host, exists in the tree"
    (doseq [[host {:keys [namespaces]}] hs/suites
            n namespaces]
      (is (ns-exists? n) (str host " lists " n ", which is not in the tree"))))
  (testing "coverage: every manifest test namespace present in the tree is in the
            bb suite or excluded from it with a reason (the same rule the other
            hosts' lists are written to; bb is the one this test can demand,
            being first)"
    (doseq [n (manifest-test-namespaces)]
      (when (ns-exists? (symbol n))
        (is (or (contains? (set (get-in hs/suites [:bb :namespaces])) (symbol n))
                (contains? (get-in hs/suites [:bb :excluded]) (symbol n)))
            (str n " exists but is neither in the bb suite nor excluded from it")))))
  (testing "every exclusion, on every host, carries a reason"
    (doseq [[host {:keys [excluded]}] hs/suites
            [n reason] excluded]
      (is (and (string? reason) (pos? (count reason)))
          (str host " excludes " n " without a reason")))))
