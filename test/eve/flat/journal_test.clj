(ns eve.flat.journal-test
  "H2 — flat placement and Eve's write journal (IMPL-PLAN §3.4).

   The disk tier is journaled by design: a host or branch where the
   journal cannot engage is broken, not an alternate configuration.
   Placement is a commit like any other, so a placed value must be
   exactly as durable as a written one. The shape is
   test/eve/jlog_domain_test.clj's: build on a persistent
   (journaled, mmap) domain, simulate a process halt WITHOUT the
   close-time checkpoint, reopen, read back.

   H2.1 place-survives-crash, three arms:
   1. place inside a swap!, kill before the next commit, reopen —
      the value reads back equal and the allocated set is exactly
      the set the place commit left.
   2. an ordinary commit on top of the placed value, kill, reopen —
      placed blocks under a later commit survive the same way.
   3. a placement whose swap fn throws after placing — the brief's
      naming arm: nothing commits, so nothing may journal; what the
      failed attempt leaves behind (the Z3.4 leak class) is printed
      from both the live domain and the reopened one, with the
      value-side facts asserted."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as a]
            [eve.process :as proc]
            [eve.mem :as mem]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.flat :as flat]
            [eve.flat.check :as check]
            [eve.jlog :as jlog]
            [eve.flat.image :as image]
            [eve.flat.place :as place])
  (:import [java.util Arrays]
           [eve.deftype_proto.alloc JvmSlabCtx]))

;; ---------------------------------------------------------------------------
;; Helpers — the recovery battery's shapes, plus the free-count yardstick
;; (per-class SLAB_HDR_FREE_COUNT off the atom's sio root; the way
;; test/eve/jvm_atom_lost_attempt_test.clj and the T1 probes count).
;; ---------------------------------------------------------------------------

(defn- fresh-base []
  (str (System/getProperty "java.io.tmpdir")
       "/eve-flat-journal-" (rand-int 1000000000) "/d"))

(defn- jpos
  "The domain's journal position: the jlog's next seq (stripe 0) — it moves
   by one for every record appended (the log's file never grows)."
  [base]
  (jlog/tail (jlog/open (str base ".jlog")) 0))

(defn- halt-domain!
  "Simulate a process halt: stop the heartbeat and release the worker
   slot WITHOUT release-domain!'s close-time checkpoint, so the
   journal keeps every record it accumulated — the state a killed
   process leaves on disk. (journal_recovery_test.clj's shape.)"
  [dom base]
  (let [ds (#'a/atom-domain-state dom)]
    (proc/stop-timer! (:heartbeat ds))
    (#'a/mmap-release-slot! (:root-r ds) (:slot-idx ds))
    (swap! @#'a/domain-cache dissoc base)))

(defn- free-counts
  "Per-class free counts of a's domain, classes 0..5."
  [x]
  (let [ctx (alloc/sio-root (a/atom-sio x))
        regions (.-regions ^JvmSlabCtx ctx)]
    (mapv (fn [ci] (mem/-load-i32 (aget ^objects regions ci) d/SLAB_HDR_FREE_COUNT))
          (range 6))))

(def ^:private capacities
  {0 (* 4 1024 1024) 1 (* 4 1024 1024) 2 (* 8 1024 1024) 3 (* 4 1024 1024) 4 (* 2 1024 1024) 5 (* 2 1024 1024)})

(def ^:private the-value
  {:m      (into {} (map (fn [i] [i (* i i)])) (range 200))
   :v      (vec (range 500))
   :s      #{"a" "b" "c"}
   :nested {:deep [true nil 2.5] :name "journal"}})

(defn- checked-image
  "The image of v, built the real way: reset! into a heap source atom,
   detach (which answers the image byte array itself), open-checked —
   the handle place's gate accepts."
  [v]
  (let [dom (a/atom-domain nil :capacities capacities)
        src (a/lookup-or-create-mmap-atom! dom (str "h2/src-" (rand-int 1000000000)) nil)]
    (reset! src v)
    (check/open-checked (flat/detach src))))

;; ---------------------------------------------------------------------------
;; H2.1 — place, kill before the next commit, reopen
;; ---------------------------------------------------------------------------

(deftest place-survives-crash
  (testing "a placed commit journals: kill before the next commit, reopen reads it, allocated set exact"
    (let [base (fresh-base)
          img  (checked-image the-value)
          dom  (a/persistent-atom-domain base)
          x    (a/lookup-or-create-mmap-atom! dom "h2/placed" nil)
          j0   (jpos base)]
      (flat/place x img)
      (let [f1 (free-counts x)
            j1 (jpos base)]
        (is (= the-value (d/eve->clj @x)) "the place committed in the live domain")
        (is (> j1 j0) "the place commit appended to the journal — the journal engaged for a placement")
        (halt-domain! dom base)
        (let [dom2 (a/persistent-atom-domain base)
              x2   (a/lookup-or-create-mmap-atom! dom2 "h2/placed" nil)
              f2   (free-counts x2)]
          (is (= f1 f2)
              "the allocated set after recovery is exactly the set the place commit left")
          (is (= the-value (d/eve->clj @x2))
              "after the kill, recovery serves the placed value")
          (testing "an ordinary commit on top of the placed value survives a second kill"
            (swap! x2 assoc :after 99)
            (let [f3 (free-counts x2)]
              (halt-domain! dom2 base)
              (let [dom3 (a/persistent-atom-domain base)
                    x3   (a/lookup-or-create-mmap-atom! dom3 "h2/placed" nil)
                    f4   (free-counts x3)]
                (is (= f3 f4) "allocated set exact after the second cycle")
                (is (= (assoc the-value :after 99) (d/eve->clj @x3))
                    "the placed value under a later commit reads back whole")
                (a/close-atom-domain! dom3))))))))
  (testing "a placement whose swap throws commits nothing and journals nothing"
    (let [base (fresh-base)
          v0   {:stable [1 2 3]}
          img  (checked-image the-value)
          dom  (a/persistent-atom-domain base)
          y    (a/lookup-or-create-mmap-atom! dom "h2/failed" nil)]
      (reset! y v0)
      (let [f0 (free-counts y)
            j0 (jpos base)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (swap! y (fn [_]
                                (place/place-value d/*domain-sio* img)
                                (throw (ex-info "h2: deliberate throw after placement" {})))))
            "the throw propagates out of the swap")
        (is (= v0 (d/eve->clj @y)) "the atom still holds the pre-attempt value")
        (is (= j0 (jpos base))
            "no journal record was appended for an attempt that never committed")
        (let [f-live (free-counts y)]
          (println "H2 failed placement — free-count delta, live domain (Z3.4 leak class):"
                   (mapv - f0 f-live) "blocks held by class, of the image placed then thrown over")
          (halt-domain! dom base)
          (let [dom2 (a/persistent-atom-domain base)
                y2   (a/lookup-or-create-mmap-atom! dom2 "h2/failed" nil)
                f-re (free-counts y2)]
            (println "H2 failed placement — free-count delta, after kill + reopen:"
                     (mapv - f0 f-re) "blocks held by class")
            (is (= v0 (d/eve->clj @y2))
                "after the kill, recovery serves the pre-attempt value — no phantom commit")
            (a/close-atom-domain! dom2)))))))
