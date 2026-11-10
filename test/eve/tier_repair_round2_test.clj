(ns eve.tier-repair-round2-test
  "Tier-repair round 2 regressions. (1) A read of an empty Eve map whose
   root is NIL_OFFSET, through the A4 view descent: hamt-get's dispatch
   probed alloc/node-view on the NIL root before either descent's NIL
   test, and node-view decoded class 7 off the regions array — the read
   eve.atom's name registration makes when an atom domain is created.
   (2) :mem-budget: growth past a domain's byte budget is refused with
   the budget named (never the host's out-of-memory), the refused domain
   keeps serving reads, and an unbudgeted domain grows as before."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is]]
            [eve.host :refer [swap!]]
            [eve.atom :as a]))

(def ^:private big (apply str (repeat 2000 "x")))

(defn- fill! [x n]
  (dotimes [i n]
    (swap! x assoc (keyword (str "k" i)) big)))

(deftest empty-map-root-read-through-a-domain
  (let [d (a/atom-domain {})
        x (a/lookup-or-create-mmap-atom! d "t/x" {})]
    (is (= {} @x))
    (swap! x assoc :k "v")
    (is (= {:k "v"} @x))))

(deftest heap-domain-mem-budget-refuses-growth-by-name
  (let [d (a/atom-domain {} :mem-budget 1)
        x (a/lookup-or-create-mmap-atom! d "t/x" {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":mem-budget"
                          (fill! x 400)))
    (is (map? @x)))
  (let [d (a/atom-domain {})
        x (a/lookup-or-create-mmap-atom! d "t/x" {})]
    (fill! x 400)
    (is (= 400 (count @x)))))

(deftest mmap-domain-mem-budget-refuses-growth-by-name
  (let [base (str (System/getProperty "java.io.tmpdir")
                  "/eve-mem-budget-test-" (System/nanoTime))]
    (let [d (a/persistent-atom-domain base :mem-budget 1 :journal? false)
          x (a/lookup-or-create-mmap-atom! d "t/x" {})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":mem-budget"
                            (fill! x 400)))
      (is (map? @x))
      (a/close-atom-domain! d))
    (let [d (a/persistent-atom-domain (str base "-ctl") :journal? false)
          x (a/lookup-or-create-mmap-atom! d "t/x" {})]
      (fill! x 400)
      (is (= 400 (count @x)))
      (a/close-atom-domain! d))))
