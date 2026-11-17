;; T1 finisher probe 2 — disposition of the P1 residuals from
;; finisher_probe.clj (J run, t1-finisher-probe3.log):
;;
;;   R-a  census-minus-delta = [2 1 0 0 0 0]: B's free-count delta over
;;        transfer! exceeded the image census by 2 class-0 + 1 class-1
;;        blocks, while reach-count B == census nblocks exactly.
;;   R-b  shared block: x-off 8 vs y-off 5 in B (round 1: 2223 vs 2220 —
;;        a constant difference of 3 at both scales).
;;   R-c  reads moved A by [3 0 0 0 0 0] after derefs.
;;
;; Q1  R-b's premise: in A ITSELF, do :x and :y (assoc'ed from ONE host
;;     vector) share a root block? And in a second atom whose value was
;;     built slab-side (assoc from (get cur :part), one slab block stored
;;     twice), do they? Census sizes of the two constructions are the
;;     second witness (a duplicated 1000-vec costs its blocks twice).
;; Q2  R-a's nature: stage a fresh B — create, transfer!, replace with
;;     nil (+ churn swaps), transfer! again. If the residual blocks are
;;     freed when the placed root is superseded, they are per-root
;;     bookkeeping held with the root, not a leak. Q2b: the same staging
;;     for a plain WRITER store of the same value (swap! of the host
;;     value) — if that route also pays ~+3 over its own value census,
;;     the bookkeeping is generic store overhead, not placement's.
;; Q3  R-c's control: an atom that never transfers — deref, value
;;     equality against the host value, pinned gets; measure its domain.
;; Q4  can a named mmap domain hold a process-local reference at all
;;     (the T1.5 construction question)?
;;
;; Run: cd <tree> && clojure -M doc/static/probes-t1/finisher_probe2.clj
(ns t1.finisher-probe2
  (:require [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.mem :as mem]
            [eve.retire :as retire]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.static.contract :as contract]
            [eve.static.transfer :as st])
  (:import [eve.deftype_proto.alloc JvmSlabCtx]))

(println "java" (System/getProperty "java.version")
         "| clojure" (clojure-version))

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

(defn- xy-offs [a]
  (ea/read-pinned a (fn [bv] [(d/-root-header-off (get bv :x))
                              (d/-root-header-off (get bv :y))])))

(def sample
  {:name "transfer"
   :nums (vec (range 200))
   :nested {:deep [1 2 {:three 3}] :flag true}
   :tags #{:a :b :c}
   :items (list 1 2 3)})

(def shared (vec (range 1000)))
(def v (assoc sample :x shared :y shared))

;; ---------------------------------------------------------------------------
;; Q1 — the sharing premise, in the source atom itself
;; ---------------------------------------------------------------------------

(def dom-a (ea/atom-domain {}))
(def a (ea/lookup-or-create-mmap-atom! dom-a "probe2/a" v))
(println "Q1 host-assoc construction, in A: x/y offs" (xy-offs a)
         " equal:" (apply = (xy-offs a)))
(def census-a (image-census (st/detach a)))
(println "Q1 census of A's image (host construction):" census-a)

(def dom-a2 (ea/atom-domain {}))
(def a2 (ea/lookup-or-create-mmap-atom! dom-a2 "probe2/a2"
                                        (assoc sample :part shared)))
(swap! a2 (fn [cur] (assoc cur :x (get cur :part) :y (get cur :part))))
(println "Q1 slab-assoc construction, in A2: x/y offs" (xy-offs a2)
         " equal:" (apply = (xy-offs a2)))
(def census-a2 (image-census (st/detach a2)))
(println "Q1 census of A2's image (slab construction, :part also present):"
         census-a2)

(def dom-b2 (ea/atom-domain {}))
(def b2 (ea/lookup-or-create-mmap-atom! dom-b2 "probe2/b2" nil))
(st/transfer! b2 a2)
(println "Q1 after transfer A2->B2: x/y offs" (xy-offs b2)
         " equal:" (apply = (xy-offs b2))
         " reach:" (reach-count b2)
         " census nblocks:" (:nblocks census-a2)
         " @b2 = v+part:" (= (assoc v :part shared) @b2))

;; ---------------------------------------------------------------------------
;; Q2 — the residual's nature, staged on one fresh domain
;; ---------------------------------------------------------------------------

(def dom-b (ea/atom-domain {}))
(def b (ea/lookup-or-create-mmap-atom! dom-b "probe2/b" nil))
(def census (image-census (st/detach a)))
(def cvec (census-vec census))
(println "Q2 census (of A, host construction):" census)
(def f0 (free-counts b))
(st/transfer! b a)
(def f1 (free-counts b))
(println "Q2 transfer#1: delta" (mapv - f0 f1)
         " residual vs census:" (mapv - (mapv - f0 f1) cvec)
         " reach:" (reach-count b))
(println "Q2 detach-B census == census:" (= census (image-census (st/detach b))))
(swap! b (fn [_] nil))
(swap! b (fn [_] 0))
(swap! b (fn [_] nil))
(def f2 (free-counts b))
(println "Q2 after replace-with-nil + churn: free" f2
         " (started" f0 ", net domain growth:" (mapv - f0 f2) ")")
(st/transfer! b a)
(def f3 (free-counts b))
(println "Q2 transfer#2: delta" (mapv - f2 f3)
         " residual vs census:" (mapv - (mapv - f2 f3) cvec)
         " reach:" (reach-count b))

;; Q2b — the writer route pays what, over its own value census?
(def dom-b3 (ea/atom-domain {}))
(def b3 (ea/lookup-or-create-mmap-atom! dom-b3 "probe2/b3" nil))
(def g0 (free-counts b3))
(swap! b3 (fn [_] v))
(def g1 (free-counts b3))
(def census-b3 (image-census (st/detach b3)))
(println "Q2b writer store: delta" (mapv - g0 g1)
         " own census:" census-b3
         " residual vs own census:" (mapv - (mapv - g0 g1) (census-vec census-b3))
         " reach:" (reach-count b3))

;; ---------------------------------------------------------------------------
;; Q3 — read allocation on an atom that never transfers
;; ---------------------------------------------------------------------------

(def dom-c (ea/atom-domain {}))
(def c (ea/lookup-or-create-mmap-atom! dom-c "probe2/c" v))
(def cf0 (free-counts c))
(def cv @c)
(println "Q3 deref done; free moved by" (mapv - cf0 (free-counts c)))
(println "Q3 (= v @c):" (= v @c)
         "; free moved by" (mapv - cf0 (free-counts c)))
(ea/read-pinned c (fn [vv] (get vv :nested)))
(ea/read-pinned c (fn [vv] (get vv :items)))
(println "Q3 after pinned gets; free moved by" (mapv - cf0 (free-counts c)))

;; ---------------------------------------------------------------------------
;; Q4 — a process-local reference in a named mmap domain
;; ---------------------------------------------------------------------------

(def dom-f (ea/atom-domain {}))
(try
  (def f (ea/lookup-or-create-mmap-atom! dom-f "probe2/f" [1 (fn [] :local) 3]))
  (println "Q4 named mmap atom created holding a fn; @f class:" (class @f))
  (try
    (st/detach f)
    (println "Q4 detach of the fn value: NO REFUSAL")
    (catch Throwable t
      (println "Q4 detach refused:" (type t) (pr-str (ex-data t)))))
  (catch Throwable t
    (println "Q4 creation itself threw:" (type t) (ex-message t))))

(System/exit 0)
