(ns eve.jvm-atom-cas-test
  "compare-and-set! on an mmap atom reports the attempt that committed.
   mmap-compare-and-set! is one CAS loop whose f sets a flag when the current
   value matches; a matching attempt that then lost its root CAS used to
   leave the flag true, so a retry that no longer matched still answered
   true (measured cross-process on Node: 1000 reported CASes, 912 landed —
   doc/unification/ports/evidence/node.md at commit 1fb9cbca)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom]))

(defn- run-cas
  "mmap-compare-and-set! old->new against scripted attempts: each element of
   `currents` is the value one attempt sees; every attempt but the last loses
   its root CAS."
  [currents old new]
  (let [seen (atom 0)]
    (with-redefs-fn
      {#'eve.atom/with-domain-write (fn [_ds _hold? f] (f false))
       #'eve.atom/try-flush-retires! (fn [_ds] nil)
       #'eve.atom/cas-backoff! (fn [_attempt] nil)
       #'eve.atom/swap-attempt!
       (fn [_ds _ptr-off f _args _exclusive?]
         (let [n (swap! seen inc)
               v (f (nth currents (dec n)))]
           (if (< n (count currents)) [:retry nil] [:ok v])))}
      #(#'eve.atom/mmap-compare-and-set! {} 0 old new))))

(deftest cas-reports-the-attempt-that-committed
  (testing "matched, lost the CAS, then saw another writer's value: false"
    (is (false? (run-cas [:old :someone-else] :old :new))))
  (testing "did not match, lost the CAS, then matched and committed: true"
    (is (true? (run-cas [:someone-else :old] :old :new))))
  (testing "matched and committed on the first attempt: true"
    (is (true? (run-cas [:old] :old :new))))
  (testing "never matched: false"
    (is (false? (run-cas [:a :b] :old :new)))))
