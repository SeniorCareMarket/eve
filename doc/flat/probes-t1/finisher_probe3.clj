;; T1 finisher probe 3 — discriminator for the suite3 failures
;; (t1-suite3.log: rewritten tests, 3 failures, all in
;; transfer-in-process; the log carries no HEAD line, so its tip is
;; unproven — these failures are treated as LEADS, re-derived here).
;;
;; The failures:
;;   L132  transfer residual (census - delta) expected [2 1 0 0 0 0],
;;         actual [-1 1 0 0 0 0]. In the test, (free-counts b) is
;;         evaluated AFTER (is (= v @b)) and (is (= v @a)) — deref +
;;         equality run in B's domain before the count. Probe2 Q3
;;         measured one deref moving a control domain by [3 0 0 0 0 0];
;;         2 - 3 = -1 would reproduce the actual exactly.
;;   L139  (= (image-census (st/detach c)) census) — cross-construction
;;         census equality. The control C stores the HOST value v via
;;         writers; probe2 Q1 proved writers duplicate a host block
;;         stored twice. Test v stores `shared` THREE times (:part,
;;         :x, :y); A's slab construction stores it once. The censuses
;;         cannot be equal (suite: 3330 vs 1262).
;;   L142  (= cvec delta-c) — the control's delta against the
;;         TRANSFER's census vector. Suite numbers give the control's
;;         residual vs its OWN census as [2 1 0 0 0 0], where probe2
;;         Q2b reported [0 0 0 0 0 0]. In the test, (free-counts c) is
;;         evaluated AFTER (st/detach c) (L139) — detach runs in C's
;;         domain between the store and the count.
;;
;; This probe stages free-counts IMMEDIATELY around each operation,
;; with the test's exact constructions, and prints every vector:
;;   ARM D  detach's effect on its SOURCE domain (A staged around
;;          the census detach).
;;   ARM T  transfer!: count before any deref; then @b staged; then
;;          detach-of-B census equality + its effect on B.
;;   ARM W  writer store: count BEFORE the census detach; the detach
;;          staged; @c staged.
;;   ARM R  the test's exact assertion order for B (transfer, @b,
;;          @a, then count) — must reproduce [-1 1 0 0 0 0] if the
;;          deref-pollution mechanism is the discriminator.
;;
;; Run: cd <tree> && clojure -M doc/static/probes-t1/finisher_probe3.clj
(ns t1.finisher-probe3
  (:require [eve.atom :as ea]
            [eve.mem :as mem]
            [eve.retire :as retire]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.static.contract :as contract]
            [eve.static.transfer :as st])
  (:import [eve.deftype_proto.alloc JvmSlabCtx]))

(println "java" (System/getProperty "java.version")
         "| clojure" (clojure-version))

(defn- fresh-atom [name x]
  (ea/lookup-or-create-mmap-atom! (ea/atom-domain {}) name x))

(defn- free-counts [a]
  (let [ctx (alloc/sio-root (ea/atom-sio a))
        regions (.-regions ^JvmSlabCtx ctx)]
    (mapv (fn [ci] (mem/-load-i32 (aget ^objects regions ci) d/SLAB_HDR_FREE_COUNT))
          (range 6))))

(defn- root-ptr [a]
  (mem/-load-i32 (:root-r (ea/-domain-state a))
                 (d/atom-slot-offset (ea/-atom-slot-idx a) d/ATOM_SLOT_PTR_OFFSET)))

(defn- reach-count [a]
  (count (retire/reach (ea/atom-sio a) (root-ptr a))))

(defn- image-census [^bytes bs]
  (let [h (contract/read-header bs 0)
        nb (quot (- (:shapetab h) (:symtab h)) contract/block-entry-size)
        blocks (contract/read-block-table bs (:symtab h) nb)
        class-of (fn [size] (or (first (keep-indexed (fn [ci cs] (when (<= size cs) ci))
                                                     alloc/CLASS_BLOCK_SIZES))
                                6))]
    {:nblocks nb
     :by-class (frequencies (map (fn [b] (class-of (:size b))) blocks))}))

(defn- census-vec [census]
  (mapv (fn [ci] (get (:by-class census) ci 0)) (range 6)))

(defn- delta [before after] (mapv - before after))
(defn- resid [cvec d] (mapv - cvec d))

(def sample
  {:name "transfer"
   :nums (vec (range 200))
   :nested {:deep [1 2 {:three 3}] :flag true}
   :tags #{:a :b :c}
   :items (list 1 2 3)})

(def shared (vec (range 1000)))
(def v (assoc sample :part shared :x shared :y shared))

;; Source A: the test's exact construction (slab-side sharing).
(def a (fresh-atom "probe3/a" (assoc sample :part shared)))
(swap! a (fn [cur] (assoc cur :x (get cur :part) :y (get cur :part))))

;; ---------------------------------------------------------------------------
;; ARM D — the census detach, staged on its source A
;; ---------------------------------------------------------------------------

(def af0 (free-counts a))
(def census (image-census (st/detach a)))
(def af1 (free-counts a))
(def cvec (census-vec census))
(println "ARM D census of A:" census)
(println "ARM D detach effect on source A (af0-af1):" (delta af0 af1))

;; ---------------------------------------------------------------------------
;; ARM T — transfer!, counts staged before any deref
;; ---------------------------------------------------------------------------

(def b (fresh-atom "probe3/b" nil))
(def bf0 (free-counts b))
(st/transfer! b a)
(def bf1 (free-counts b))
(println "ARM T transfer delta (clean):" (delta bf0 bf1)
         " residual vs census:" (resid cvec (delta bf0 bf1))
         " reach:" (reach-count b) " census:" (:nblocks census))
(def bv @b)
(println "ARM T @b = v:" (= v bv)
         " deref effect on B:" (delta bf1 (free-counts b)))
(def bf2 (free-counts b))
(def census-b (image-census (st/detach b)))
(println "ARM T detach-B census == census:" (= census census-b)
         " detach effect on B:" (delta bf2 (free-counts b)))

;; ---------------------------------------------------------------------------
;; ARM R — the test's exact assertion order, replicated on a second B
;; ---------------------------------------------------------------------------

(def b2 (fresh-atom "probe3/b2" nil))
(def b2f0 (free-counts b2))
(st/transfer! b2 a)
(def r-eq-b (= v @b2))
(def r-eq-a (= v @a))
(println "ARM R test-order residual (expect the suite's [-1 1 0 0 0 0]"
         "if deref pollution is the mechanism):"
         (resid cvec (delta b2f0 (free-counts b2)))
         " eq-b:" r-eq-b " eq-a:" r-eq-a)

;; ---------------------------------------------------------------------------
;; ARM W — writer store of the host value, counts staged around detach
;; ---------------------------------------------------------------------------

(def c (fresh-atom "probe3/c" nil))
(def cf0 (free-counts c))
(swap! c (fn [_] v))
(def cf1 (free-counts c))
(def census-c (image-census (st/detach c)))
(def cf2 (free-counts c))
(println "ARM W writer delta (clean, before detach):" (delta cf0 cf1))
(println "ARM W own census:" census-c)
(println "ARM W residual vs OWN census (clean):"
         (resid (census-vec census-c) (delta cf0 cf1))
         " reach:" (reach-count c))
(println "ARM W detach effect on C:" (delta cf1 cf2)
         " residual as the test computes it (count after detach):"
         (resid (census-vec census-c) (delta cf0 cf2)))
(def cv @c)
(println "ARM W @c = v:" (= v cv)
         " deref effect on C:" (delta cf2 (free-counts c)))
(println "PROBE3 DONE")
