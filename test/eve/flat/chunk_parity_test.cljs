(ns eve.flat.chunk-parity-test
  "Trunk-merge parity fixture (Nov 14 2026, audit entry 5): the
   ClojureScript handles answer through the SAME walks as the JVM
   handles — digest-first equiv, folded hash, the shared rep family,
   the shared unpack. Before the merge the CLJS half carried its own
   decode-based walks: equiv/hash paid a full decode per operation
   (visible on the context's decode counter), a typed value's rep
   came back a plain map, and metadata was dropped from reps.

   The load-bearing assertions are the counter ones: after the
   merge, equiv, hash, lookup and count on a handle run NO whole-
   value decode (chunk/full-decodes does not move), and unpack moves
   it by exactly one — C1.4's contract, now on this host too.

   Run (from the repo root):
     clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-parity-test
     node target/flat-parity-test/main.js"
  (:require [cljs.test :refer [deftest is testing]]
            [eve.flat.chunk :as chunk]))

(defn- round-trip [v]
  (chunk/open (chunk/bytes (chunk/pack v))))

(def corpus
  {:a [1 2 3]
   :b #{:x :y}
   :c "s"
   :d {:nested [true nil 1.5]}
   :e (list 1 2)})

(deftest equiv-and-hash-match-plain
  (let [h (round-trip corpus)]
    (is (= h corpus) "handle equiv against the plain value")
    (is (= corpus h) "plain equiv against the handle")
    (is (= (hash h) (hash corpus)) "hasheq matches the plain value's")
    (is (= (count h) (count corpus)))
    (is (= (get h :a) [1 2 3]))
    (is (= (seq (get h :a)) (list 1 2 3)))))

(deftest protocol-ops-run-no-decode
  (let [h (round-trip corpus)
        other (round-trip corpus)
        d0 (chunk/full-decodes h)]
    (is (= h other) "handle-handle equiv")
    (is (= (hash h) (hash other)))
    (is (= (get h :d) {:nested [true nil 1.5]}))
    (is (= d0 (chunk/full-decodes h))
        "equiv/hash/lookup ran no whole-value decode (digest-first)")
    (is (= (chunk/content-digest h) (chunk/eve-digest corpus))
        "the handle's content digest is Eve's digest of the value")
    (is (= (chunk/content-digest h) (chunk/content-digest other)))))

(deftest nan-digest-edge
  (is (some? (chunk/content-digest (chunk/pack {:a ##NaN}))))
  (is (not= (chunk/content-digest (chunk/pack {:a ##NaN}))
            (chunk/content-digest (chunk/pack {:a 0.0}))))
  (is (= (chunk/content-digest (chunk/pack {:a ##NaN}))
         (chunk/eve-digest {:a ##NaN}))))

(deftest unpack-counts-exactly-one
  (let [h (round-trip corpus)
        d0 (chunk/full-decodes h)]
    (is (= (chunk/unpack h) corpus))
    (is (= (inc d0) (chunk/full-decodes h))
        "one counted decode per unpack, not one per nested node")))

(deftest rep-matches-to-rep
  (let [h (round-trip corpus)]
    (is (= (chunk/rep-of h) (chunk/to-rep corpus))
        "the handle's rep is the shared rep family's answer"))
  (testing "metadata survives in the rep (the pre-merge CLJS walk dropped it)"
    (let [v (with-meta [1 2] {:m 1})
          h (round-trip v)
          r (chunk/rep-of h)]
      (is (instance? eve.flat.chunk/MetaVal r))
      (is (= (.-inner r) [1 2])))))

(deftest print-is-the-rep
  (let [h (round-trip corpus)]
    (is (= (pr-str h) (pr-str corpus)))))

(deftest updates-still-shared
  (let [h (round-trip corpus)]
    (is (= (assoc h :z 9) (assoc corpus :z 9)))
    (is (= (dissoc h :c) (dissoc corpus :c)))
    (is (= (conj (round-trip [1 2]) 3) [1 2 3]))))

(deftest batch-rows
  (let [rows [{:id 1 :w 1.5} {:id 2 :w 2.5}]
        h (round-trip rows)
        row (nth h 0)
        d0 (chunk/full-decodes h)]
    (is (= row {:id 1 :w 1.5}))
    (is (= (hash row) (hash {:id 1 :w 1.5})))
    (is (= (chunk/rep-of row) {:id 1 :w 1.5}))
    (is (= d0 (chunk/full-decodes h)) "row equiv/hash ran no decode")))
