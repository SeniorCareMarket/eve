;; T1 finisher probe — re-derivation of the first J suite run's failures
;; (t1-transfer-tests-1.log: 6 failures, 1 error) at the current pin.
;;
;; Source-level finding this probe measures: eve.atom/atom (eve.alpha/atom
;; delegates to it) resolves an anonymous atom through resolve-heap-domain
;; with a nil id — a process-wide cached DEFAULT domain. The suite's
;; transfer-in-process built a and b with (e/atom ...), so both lived in
;; ONE domain: the "A unchanged" free-count check measured B's placement
;; in the shared domain, the per-class deltas were polluted by the second
;; atom's creation, and the "nested foreign value" of the default-policy
;; check was not foreign at all. This probe rebuilds the scenario with
;; a and b in SEPARATE fresh domains (ea/atom-domain + lookup-or-create)
;; and stages every count, so each J failure is either reproduced against
;; its real discriminator or shown to belong to the construction.
;;
;; P0  domain identity: anonymous e/atom atoms share a sio-root.
;; P1  staged free-counts around detach / transfer! / derefs, per-class
;;     B deltas vs the image census, reach count, x/y shared-block offs.
;; P2  the nested default-policy refusal, with a genuinely foreign value.
;; P3  the cross-process leg replicated, receiver stderr printed in full.
;;
;; Run: cd <tree> && CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;        /home/hatch/.local/bin/clojure -M doc/static/probes-t1/finisher_probe.clj
(ns t1.finisher-probe
  (:require [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.mem :as mem]
            [eve.retire :as retire]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.static.contract :as contract]
            [eve.static.transfer :as st])
  (:import [eve.deftype_proto.alloc JvmSlabCtx]
           [java.io File]
           [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(println "java" (System/getProperty "java.version")
         "| clojure" (clojure-version))

;; helpers copied from test/eve/static/transfer_test.clj
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

(def sample
  {:name "transfer"
   :nums (vec (range 200))
   :nested {:deep [1 2 {:three 3}] :flag true}
   :tags #{:a :b :c}
   :items (list 1 2 3)})

;; ---------------------------------------------------------------------------
;; P0 — anonymous atoms share the default domain
;; ---------------------------------------------------------------------------

(let [a1 (e/atom 1) a2 (e/atom 2)]
  (println "P0 anonymous e/atom atoms share one sio-root:"
           (identical? (alloc/sio-root (ea/atom-sio a1))
                       (alloc/sio-root (ea/atom-sio a2)))))

;; ---------------------------------------------------------------------------
;; P1 — staged counts, separate domains
;; ---------------------------------------------------------------------------

(def shared (vec (range 1000)))
(def v (assoc sample :x shared :y shared))
(def dom-a (ea/atom-domain {}))
(def a (ea/lookup-or-create-mmap-atom! dom-a "finisher/a" v))
(def dom-b (ea/atom-domain {}))
(def b (ea/lookup-or-create-mmap-atom! dom-b "finisher/b" nil))

(println "P1 a/b sio-roots identical:"
         (identical? (alloc/sio-root (ea/atom-sio a))
                     (alloc/sio-root (ea/atom-sio b))))
(def a-free0 (free-counts a))
(def b-free0 (free-counts b))
(println "P1 stage created:      a-free" a-free0 " b-free" b-free0)

(def census (image-census (st/detach a)))
(println "P1 census:" census)
(println "P1 stage after detach: a-free" (free-counts a)
         " (detach alone moved A by" (mapv - a-free0 (free-counts a)) ")")

(def a-ptr (root-ptr a))
(st/transfer! b a)
(def a-free1 (free-counts a))
(def b-free1 (free-counts b))
(def b-delta (mapv - b-free0 b-free1))
(println "P1 stage after transfer!: a-free" a-free1
         " (A moved by" (mapv - a-free0 a-free1) ")")
(println "P1 B per-class delta:" b-delta
         " census-minus-delta:"
         (mapv (fn [ci] (- (get (:by-class census) ci 0) (nth b-delta ci))) (range 6)))
(println "P1 census nblocks:" (:nblocks census) " reach-count b:" (reach-count b))
(println "P1 @b = v:" (= v @b) " @a = v:" (= v @a) " a root unchanged:" (= a-ptr (root-ptr a)))
(println "P1 stage after derefs:   a-free" (free-counts a)
         " (reads moved A by" (mapv - a-free1 (free-counts a)) ")")

(let [x-off (ea/read-pinned b (fn [bv] (d/-root-header-off (get bv :x))))
      y-off (ea/read-pinned b (fn [bv] (d/-root-header-off (get bv :y))))]
  (println "P1 shared block in B: x-off" x-off " y-off" y-off " equal:" (= x-off y-off)))

;; ---------------------------------------------------------------------------
;; P2 — nested refusal with a genuinely foreign value
;; ---------------------------------------------------------------------------

(def dom-b2 (ea/atom-domain {}))
(def b2 (ea/lookup-or-create-mmap-atom! dom-b2 "finisher/b2" nil))
(def nested (ea/read-pinned a (fn [vv] (get vv :nested))))
(println "P2 nested value's sio-root is a's:"
         (identical? (alloc/sio-root (d/-eve-sio nested))
                     (alloc/sio-root (ea/atom-sio a)))
         "| foreign to b2:"
         (not (identical? (alloc/sio-root (d/-eve-sio nested))
                          (alloc/sio-root (ea/atom-sio b2)))))
(try
  (swap! b2 (fn [_] {:wrapped nested}))
  (println "P2 nested default-policy swap: NO THROW; @b2 =" @b2)
  (catch Throwable t
    (println "P2 nested default-policy swap threw:" (type t)
             "\n    msg:" (ex-message t)
             "\n    data:" (pr-str (ex-data t)))))

;; ---------------------------------------------------------------------------
;; P3 — cross-process, receiver output captured whole
;; ---------------------------------------------------------------------------

(def vx {:nums (vec (range 500))
         :label "crossing"
         :nested {:a [1 2 3] :b #{:x :y} :c (list 4 5)}})
(def dom-x (ea/atom-domain {}))
(def ax (ea/lookup-or-create-mmap-atom! dom-x "finisher/x" vx))
(def bs (st/detach ax))
(println "P3 image bytes:" (alength bs) " census:" (image-census bs))
;; in-process place of the same bytes into a fresh domain — isolates
;; "the image/place is broken" from "the subprocess leg is broken"
(def dom-y (ea/atom-domain {}))
(def by (ea/lookup-or-create-mmap-atom! dom-y "finisher/y" nil))
(try
  (st/place-image! by bs)
  (println "P3 in-process place of the same image: ok, equal:" (= vx @by))
  (catch Throwable t
    (println "P3 in-process place of the same image THREW:" (type t)
             "\n    msg:" (ex-message t)
             "\n    data:" (pr-str (ex-data t)))))

(let [dir (Files/createTempDirectory "eve-t1-finisher" (into-array java.nio.file.attribute.FileAttribute []))
      bytes-file (.resolve dir "value.evs")
      script-file (.resolve dir "receive.clj")
      out-file (.resolve dir "out.edn")
      err-file (.resolve dir "err.txt")
      stdout-file (.resolve dir "stdout.txt")]
  (Files/write bytes-file bs (into-array java.nio.file.OpenOption []))
  (spit (.toFile script-file)
        (str "(require '[eve.atom :as ea] '[eve.static.transfer :as st])\n"
             "(let [[in out] *command-line-args*\n"
             "      bs (java.nio.file.Files/readAllBytes (java.nio.file.Path/of in))\n"
             "      a (ea/lookup-or-create-mmap-atom! (ea/atom-domain {}) \"xproc/b\" nil)]\n"
             "  (st/place-image! a bs)\n"
             "  (spit out (pr-str @a)))\n"))
  (let [pb (ProcessBuilder.
            ^java.util.List ["java" "-cp" (System/getProperty "java.class.path")
                             "clojure.main" (str script-file) (str bytes-file) (str out-file)])]
    (.redirectError pb (.toFile err-file))
    (.redirectOutput pb (.toFile stdout-file))
    (let [proc (.start pb)
          finished (.waitFor proc 180 TimeUnit/SECONDS)]
      (println "P3 receiver finished:" finished
               (when finished (str " exit=" (.exitValue proc))))
      (println "P3 receiver stderr:\n" (slurp (.toFile err-file)))
      (println "P3 receiver stdout:\n" (slurp (.toFile stdout-file)))
      (println "P3 out.edn exists:" (Files/exists out-file (into-array java.nio.file.LinkOption []))
               (when (Files/exists out-file (into-array java.nio.file.LinkOption []))
                 (str " equal:" (= vx (read-string (slurp (.toFile out-file))))))))))

(System/exit 0)
