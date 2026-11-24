(ns eve.flat.transfer-test
  "Lane T1 (IMPL-PLAN §3.3): atom to atom. A slab value of atom A stored into
   atom B is detached and zipped — no host collection built — under the
   receiving domain's :zip foreign policy, or explicitly via
   eve.flat.transfer/transfer!. Requirement ids T1.1–T1.6, registered in
   test/eve/flat/requirements.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.test-scratch :as scratch]
            [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.mem :as mem]
            [eve.retire :as retire]
            [eve.stm :as stm]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.flat.contract :as contract]
            [eve.flat.transfer :as st])
  (:import [java.io File]
           [java.nio.file Files Path]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- fresh-atom
  "An atom in its OWN named domain. Finisher probe P0 (J,
   t1-finisher-probe3.log): anonymous (e/atom ...) atoms resolve through
   eve.atom's resolve-heap-domain with a nil id — a process-wide cached
   DEFAULT domain — so two anonymous atoms are never foreign to each
   other. Every cross-domain construction in this suite goes through
   here (ea/atom-domain + lookup-or-create-mmap-atom!, the codebase's
   separate-domain construction, cf. STM cells).

   It returns once its domain is past eve.atom's retire-flush throttle
   window (FLUSH_INTERVAL_MS since the construction commit's flush), so
   the next commit on it always runs the zero-queue flush and frees the
   superseded initial root. Without the wait, whether that root (the
   [2 1 0 0 0 0] residual) is freed before free-counts is read depends
   on wall time, which flaked transfer-in-process :156 and
   transfer-direct-equal :305 under load."
  [name x]
  (let [a (ea/lookup-or-create-mmap-atom! (ea/atom-domain {}) name x)]
    (Thread/sleep (long (inc ea/FLUSH_INTERVAL_MS)))
    a))

(defn- slab-of
  "Atom a's committed value as the lazy slab-backed value of its domain
   (the object read under the pin, kept — a stays alive in these tests)."
  [a]
  (ea/read-pinned a (fn [v] v)))

(defn- slab-sub
  "The slab-backed subvalue at k of atom a's committed map value."
  [a k]
  (ea/read-pinned a (fn [v] (get v k))))

(defn- root-ptr [a]
  (mem/-load-i32 (:root-r (ea/-domain-state a))
                 (d/atom-slot-offset (ea/-atom-slot-idx a) d/ATOM_SLOT_PTR_OFFSET)))

(defn- free-counts
  "Per-class free-block counts of a's domain, classes 0-5."
  [a]
  (let [ctx (alloc/sio-root (ea/atom-sio a))
        regions (.-regions ^eve.deftype_proto.alloc.JvmSlabCtx ctx)]
    (mapv (fn [ci] (mem/-load-i32 (aget ^objects regions ci) d/SLAB_HDR_FREE_COUNT))
          (range 6))))

(defn- reach-count [a]
  (count (retire/reach (ea/atom-sio a) (root-ptr a))))

(defn- image-census
  "The block census of image bytes bs: {:nblocks n :by-class {class n}} —
   a block's class is the smallest class whose block size holds its
   recorded size (placement claims by that rule); a size past the largest
   class is the overflow class, 6."
  [^bytes bs]
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
;; T1.1 — transfer in process: root and nested, reads back equal, A
;; unchanged, B's allocated set exactly the value's blocks, no host
;; collection built (sharing survives: a block stored twice is one block)
;; ---------------------------------------------------------------------------

(deftest transfer-in-process
  ;; Construction per finisher probe2 (J, t1-finisher-probe4.log):
  ;; Q1 — sharing is a property of IN-DOMAIN construction. A host-built
  ;; {:x s :y s} loses the sharing when the writers convert it (A's x/y
  ;; root offsets [1217 2218]); assoc'ing ONE slab block twice inside a
  ;; swap keeps it ([1217 1217]), and transfer preserves exactly the
  ;; sharing that exists ([7 7] in B, reach == census 1262). So the
  ;; shared value here is built slab-side: :part stored once, then
  ;; assoc'ed to :x and :y from the pinned current value.
  ;; Q2 — the free-count delta of a first transfer into a fresh
  ;; domain runs [2 1 0 0 0 0] UNDER the image census while reach ==
  ;; census exactly: the committing swap frees the fresh atom's
  ;; initial root (2 class-0 + 1 class-1) into the same delta; the
  ;; shortfall is that superseded root, not unconsumed value blocks.
  ;; Q3 — deref/equality allocate class-0 blocks in the domain they
  ;; read (3 per deref; probe2 Q3, probe3 arm T), so every free-count
  ;; in this test is staged immediately after the store it judges,
  ;; before any deref of EITHER atom. (Suite3's first failure applied
  ;; this rule to A but violated it for B: expected [2 1 ...], got
  ;; [-1 1 ...] = 2 - 3.)
  ;; Probe3 (J, t1-probe3.log) settled the staging arms: detach has
  ;; ZERO effect on its source domain's free counts (arm D); transfer
  ;; counted clean reads [2 1 0 0 0 0] (arm T); the old assertion
  ;; order reproduces [-1 1 0 0 0 0] exactly (arm R); and the WRITER
  ;; control counted clean against its OWN census also reads
  ;; [2 1 0 0 0 0] (arm W) — NOT the [0 0 0 0 0 0] probe2's Q2b
  ;; reported. That reading is corrected here; probe3's controlled
  ;; staging is the discriminator. The control is therefore judged
  ;; against its own census only: the writer route duplicates a host
  ;; block stored more than once (Q1), so its census (3330 blocks)
  ;; is not A's (1262).
  (let [shared (vec (range 1000))
        v (assoc sample :part shared :x shared :y shared)
        a (fresh-atom "t1-1/a" (assoc sample :part shared))]
    (swap! a (fn [cur] (assoc cur :x (get cur :part) :y (get cur :part))))
    (let [a-ptr (root-ptr a)
          a-free (free-counts a)]
      (testing "the premise holds in A itself: :x and :y are one block"
        (let [x-off (ea/read-pinned a (fn [av] (d/-root-header-off (get av :x))))
              y-off (ea/read-pinned a (fn [av] (d/-root-header-off (get av :y))))]
          (is (= x-off y-off))))
      (testing "explicit transfer! of the root value"
        (let [b (fresh-atom "t1-1/b" nil)
              b-free0 (free-counts b)
              census (image-census (st/detach a))
              cvec (mapv (fn [ci] (get (:by-class census) ci 0)) (range 6))]
          (is (identical? b (st/transfer! b a)))
          (testing "A is unchanged by the transfer (checked before any deref)"
            (is (= a-ptr (root-ptr a)))
            (is (= a-free (free-counts a))))
          (testing "B's allocated set is exactly the value's blocks (counted before any deref — probe3 arm T)"
            (is (= (:nblocks census) (reach-count b)))
            (is (= [2 1 0 0 0 0]
                   (mapv - cvec (mapv - b-free0 (free-counts b))))
                "first-transfer delta shortfall is the superseded initial root (Q2)"))
          (is (= v @b))
          (is (= v @a))
          (testing "the writer-route control, judged against its OWN census (probe3 arm W)"
            (let [c (fresh-atom "t1-1/c" nil)
                  c-free0 (free-counts c)]
              (swap! c (fn [_] v))
              (let [c-free1 (free-counts c)
                    c-census (image-census (st/detach c))
                    ccvec (mapv (fn [ci] (get (:by-class c-census) ci 0)) (range 6))]
                (is (= (:nblocks c-census) (reach-count c))
                    "the control's reachable set is exactly its own census")
                (is (= [2 1 0 0 0 0] (mapv - ccvec (mapv - c-free0 c-free1)))
                    "the writer route shows the same superseded-root residual as transfer"))))
          (testing "no host collection was built: the shared block is one block in B"
            (let [x-off (ea/read-pinned b (fn [bv] (d/-root-header-off (get bv :x))))
                  y-off (ea/read-pinned b (fn [bv] (d/-root-header-off (get bv :y))))]
              (is (= x-off y-off))))))
      (testing "the :zip foreign policy at the root of a swap!"
        (let [b (fresh-atom "t1-1/b-zip" nil)]
          (ea/set-foreign-policy! b :zip)
          (swap! b (fn [_] (slab-of a)))
          (is (= v @b))))
      (testing "nested: a foreign value inside a plain collection, under :zip"
        (let [b (fresh-atom "t1-1/b-nested" nil)]
          (ea/set-foreign-policy! b :zip)
          (swap! b (fn [_] {:wrapped (slab-sub a :nested)
                            :list [1 (slab-sub a :tags)]}))
          (is (= {:wrapped (:nested sample) :list [1 (:tags sample)]} @b))))
      (testing "nested under the default policy still refuses, as today"
        (let [b (fresh-atom "t1-1/b-throw" nil)]
          (is (thrown? clojure.lang.ExceptionInfo
                       (swap! b (fn [_] {:wrapped (slab-sub a :nested)})))))))))

;; ---------------------------------------------------------------------------
;; T1.2 — the source is pinned for the copy: concurrent writes to A never
;; tear the value B receives (every received value is one committed value)
;; ---------------------------------------------------------------------------

(deftest transfer-under-writes
  (let [a (fresh-atom "t1-2/a" {:n 1 :sq 1 :tag "v1"})
        b (fresh-atom "t1-2/b" nil)
        writes 400
        writer (future
                 (doseq [k (range 2 (inc writes))]
                   (swap! a (fn [_] {:n k :sq (* k k) :tag (str "v" k)})))
                 :done)]
    (dotimes [_ 40]
      (st/transfer! b a)
      (let [{:keys [n sq tag]} @b]
        (is (= sq (* n n)) "a torn value reached B (sq)")
        (is (= tag (str "v" n)) "a torn value reached B (tag)")
        (is (<= 1 n writes))))
    (is (= :done (deref writer 30000 ::timeout)))
    (st/transfer! b a)
    (is (= {:n writes :sq (* writes writes) :tag (str "v" writes)} @b))))

;; ---------------------------------------------------------------------------
;; T1.3 — a two-ref STM transaction that stores a foreign value commits
;; both refs or neither. The store-time refusal that breaks atomicity
;; today (A16, re-derived at this pin — see doc/flat/probes-t1/) is
;; removed by the :zip branch: with :zip on the refs' cell atoms the
;; foreign value is zipped into each cell's domain and both install.
;; ---------------------------------------------------------------------------

(deftest stm-foreign-value-atomic
  (let [ra (stm/make-ref 0)
        rb (stm/make-ref 0)
        rc (stm/make-ref {:donor 1})
        ;; a slab value of rc's cell domain: rc's committed cell map itself
        foreign (slab-of (:cell rc))]
    (ea/set-foreign-policy! (:cell ra) :zip)
    (ea/set-foreign-policy! (:cell rb) :zip)
    (stm/dosync
      (stm/ref-set* ra 1)
      (stm/ref-set* rb foreign))
    (testing "both refs committed"
      (is (= 1 (stm/deref-ref ra)))
      (is (= @(:cell rc) (stm/deref-ref rb))))
    (testing "the zipped value takes part in STM like any other"
      (stm/dosync (stm/ref-set* rb 42))
      (is (= 42 (stm/deref-ref rb)))
      (is (= 1 (stm/deref-ref ra)))
      (is (pos? (stm/ref-history-count rb))))))

;; ---------------------------------------------------------------------------
;; T1.4 — process to process: A's value -> bytes -> a file -> B in a
;; second JVM, equal. Receiver-script fix (finisher probe P3, J): the
;; generated script's (java.nio.file.Path/of in) died with a
;; ClassCastException String→URI inside the receiver — the image and
;; the transfer path were exonerated by an in-process place of the
;; identical bytes; the script now goes through (java.io.File. in).
;; ---------------------------------------------------------------------------

(deftest transfer-cross-process
  (let [v {:nums (vec (range 500))
           :label "crossing"
           :nested {:a [1 2 3] :b #{:x :y} :c (list 4 5)}}
        a (e/atom v)
        bs (st/detach a)
        dir (.toPath (java.io.File. (scratch/unique-dir "t1-xproc")))
        bytes-file (.resolve dir "value.evs")
        script-file (.resolve dir "receive.clj")
        out-file (.resolve dir "out.edn")]
    (Files/write bytes-file bs (into-array java.nio.file.OpenOption []))
    (spit (.toFile script-file)
          (str "(require '[eve.atom :as ea] '[eve.flat.transfer :as st])\n"
               "(let [[in out] *command-line-args*\n"
               "      bs (java.nio.file.Files/readAllBytes (.toPath (java.io.File. in)))\n"
               "      a (ea/lookup-or-create-mmap-atom! (ea/atom-domain {}) \"xproc/b\" nil)]\n"
               "  (st/place-image! a bs)\n"
               "  (spit out (pr-str @a)))\n"))
    (let [pb (ProcessBuilder.
              ^java.util.List ["java" "-cp" (System/getProperty "java.class.path")
                               "clojure.main" (str script-file) (str bytes-file) (str out-file)])
          proc (.start pb)
          finished (.waitFor proc 180 TimeUnit/SECONDS)]
      (is finished "the receiving JVM did not finish")
      (when finished
        (is (zero? (.exitValue proc))
            (str "receiver failed: " (slurp (.getErrorStream proc))))
        (is (= v (read-string (slurp (.toFile out-file)))))))
    (scratch/delete-tree! (str dir))))

;; ---------------------------------------------------------------------------
;; T1.5 — a value holding a process-local reference is refused by name
;; ---------------------------------------------------------------------------

(deftest transfer-refuses-local-reference
  ;; Construction per finisher probe2 Q4 (J, t1-finisher-probe4.log): a
  ;; NAMED mmap domain can hold a fn-bearing value (the vector is
  ;; created and derefs as a host PersistentVector), and detach refuses
  ;; it with {:eve.flat.transfer/refusal :process-local-reference} —
  ;; process-local references are refused by design, not stored.
  (let [a (fresh-atom "t1-5/a" [1 (fn [] :local) 3])
        b (fresh-atom "t1-5/b" nil)
        refusal (try (st/transfer! b a)
                     ::stored
                     (catch clojure.lang.ExceptionInfo ex
                       (:eve.flat.transfer/refusal (ex-data ex))))]
    (is (= :process-local-reference refusal)
        "the transfer is refused, naming the process-local reference")
    (is (nil? @b) "nothing was stored")))

;; ---------------------------------------------------------------------------
;; T1.6 — a direct domain-to-domain copy (no buffer between) gives the
;; same blocks as the image route
;; ---------------------------------------------------------------------------

(deftest transfer-direct-equal
  (let [shared (vec (range 500))
        v (assoc sample :x shared :y shared)
        a (fresh-atom "t1-6/a" v)
        b1 (fresh-atom "t1-6/b1" nil)
        b2 (fresh-atom "t1-6/b2" nil)
        f1 (free-counts b1)
        f2 (free-counts b2)]
    (st/transfer! b1 a)
    (st/transfer-direct! b2 a)
    (is (= v @b1))
    (is (= v @b2))
    (is (= (reach-count b1) (reach-count b2)))
    (is (= (mapv - f1 (free-counts b1)) (mapv - f2 (free-counts b2)))
        "the direct copy allocated the same blocks per class as the image route")))
