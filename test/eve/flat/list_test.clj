(ns eve.flat.list-test
  "L1 — the list reads without allocating (IMPL-PLAN §3.3, lane L1).

   L1.1 list-seq-allocates-nothing: a full walk of an EveList — seq/next,
   the plain materialization a deref answers (eve->clj), reduce, into —
   allocates no block in the list's domain. Measured with the allocation
   log (eve.deftype-proto.alloc/logging-sio, the reclaim test's
   instrument): every block the walk allocates through the list's own sio
   and does not free is counted. The census claim this re-derives
   (CENSUS.md defect 3): one plain deref of a 50-element list took 50
   class-0 blocks, one leaked 12-byte header per seq step.

   L1.2 list-reads-from-view: an EveList reopened through a frozen view —
   an ISlabIO whose every mutator throws, as a flat view's does — reads
   in full. (P5's one failing row: \"list of 200\" ERROR \"flat view:
   allocation in a frozen buffer\".)

   L1.3 is the existing list suite, test/eve/jvm_list_test.clj — not here."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.list :as el]))

(defn- frozen-view
  "A read-only ISlabIO over inner: reads delegate, every mutator throws.
   The probe's frozen view (doc/flat/probes/lib.clj view-sio) refuses
   allocation; Z1.1's flat sio refuses every mutator — a value that
   reads from a frozen view must call none of them."
  [inner]
  (let [refuse (fn [what]
                 (throw (ex-info (str "flat view: " what " in a frozen buffer") {})))]
    (reify alloc/ISlabIO
      (-sio-read-u8 [_ o f] (alloc/-sio-read-u8 inner o f))
      (-sio-write-u8! [_ _ _ _] (refuse "write"))
      (-sio-read-u16 [_ o f] (alloc/-sio-read-u16 inner o f))
      (-sio-write-u16! [_ _ _ _] (refuse "write"))
      (-sio-read-i32 [_ o f] (alloc/-sio-read-i32 inner o f))
      (-sio-write-i32! [_ _ _ _] (refuse "write"))
      (-sio-cas-i32! [_ _ _ _ _] (refuse "cas"))
      (-sio-read-bytes [_ o f n] (alloc/-sio-read-bytes inner o f n))
      (-sio-write-bytes! [_ _ _ _] (refuse "write"))
      (-sio-alloc! [_ _] (refuse "allocation"))
      (-sio-free! [_ _] (refuse "free"))
      (-sio-copy-block! [_ _ _ _ _ _] (refuse "copy")))))

(deftest list-seq-allocates-nothing
  (let [sio    (alloc/heap-domain-sio! {})
        xs     (apply list (range 50))
        l      (el/eve-list sio xs)
        lsio   (alloc/logging-sio sio)
        walked (el/list-from-header lsio (d/-root-header-off l))]
    (testing "the walk reads the list, every way a reader walks it"
      (is (= xs (doall (seq walked))))
      (is (= 50 (loop [s (seq walked) n 0]
                  (if s (recur (next s) (inc n)) n))))
      (is (= xs (d/eve->clj walked))) ;; the plain deref path: -plain
      (is (= 50 (reduce (fn [a _] (inc a)) 0 walked)))
      (is (= (vec xs) (into [] walked))))
    (testing "and allocates nothing in the domain"
      (is (empty? (alloc/logged-allocs lsio))
          (str "walking allocated " (count (alloc/logged-allocs lsio))
               " block(s) in the domain: " (alloc/logged-allocs lsio))))))

(deftest list-reads-from-view
  (let [sio (alloc/heap-domain-sio! {})
        xs  (apply list (range 200))
        l   (el/eve-list sio xs)
        view (el/list-from-header (frozen-view sio) (d/-root-header-off l))]
    (testing "the value P5 could not read: a list of 200 from a frozen view"
      (is (= (vec xs) (vec (d/eve->clj view))))
      (is (= xs (doall (seq view))))
      (is (= 200 (count view)))
      (is (= 0 (first view)))
      (is (= 199 (last (seq view))))
      (is (= 200 (reduce (fn [a _] (inc a)) 0 view))))))
