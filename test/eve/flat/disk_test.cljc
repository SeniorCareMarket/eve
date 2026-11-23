(ns eve.flat.disk-test
  "Lane H1 requirement tests (IMPL-PLAN §3.4, disk domains): H1.1
   run-claim-two-process, H1.2 place-exact-disk.

   H1 owns the mmap slab path of alloc-run!: the run claim Z2 built over
   the bitmap words must hold when the bitmap is a file two processes
   have mapped — claims interleave at the CAS, never in one process's
   head — and place (Z3) must keep its exactness on atoms whose domain
   lives in those files, including across a close and reopen."
  (:require ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
   #_{:clj-kondo/ignore [:unused-namespace :unused-referred-var]}   [clojure.test :refer [deftest is testing]]
   #?(:clj [clojure.set :as cset])
   #?(:clj [eve.atom :as ea])
   #?(:clj [eve.deftype-proto.alloc :as alloc])
   #?(:clj [eve.deftype-proto.data :as d])
   #?(:clj [eve.mem :as mem])
   #?(:clj [eve.flat :as flat])
   #?(:clj [eve.flat.check :as check])
   #?(:clj [eve.flat.image :as image])
   #?(:clj [eve.flat.place :as place]))
  #?(:clj (:import [java.io File]
                   [java.nio.file Files]
                   [java.nio.file.attribute FileAttribute]
                   [eve.deftype_proto.alloc JvmSlabCtx])))

#?(:clj
   (do

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

     (def ^:private capacities
  ;; bytes, as every domain constructor takes them: 8192 blocks of each
  ;; of classes 0-2 (the two-process race claims ~2,500 per class),
  ;; smaller tails for the rest.
       {0 (* 8192 32) 1 (* 8192 64) 2 (* 8192 128)
        3 (* 2048 256) 4 (* 1024 512) 5 (* 512 1024)})

     (defn- temp-dir []
       (str (Files/createTempDirectory "eve-h1" (into-array FileAttribute []))))

     (defn- class-total [^JvmSlabCtx ctx ci]
       (mem/-load-i32 (aget ^objects (.-regions ctx) ci) d/SLAB_HDR_TOTAL_BLOCKS))

     (defn- class-free [^JvmSlabCtx ctx ci]
       (mem/-load-i32 (aget ^objects (.-regions ctx) ci) d/SLAB_HDR_FREE_COUNT))

     (defn- blocks-in-use
       "The domain's allocated set, counted the way place_test counts it:
   per bitmap class, TOTAL_BLOCKS - FREE_COUNT from the region headers."
       [sio]
       (let [ctx (alloc/sio-root sio)]
         (reduce + (for [ci (range d/NUM_SLAB_CLASSES)]
                     (- (class-total ctx ci) (class-free ctx ci))))))

     (defn- spawn-worker!
       "A second JVM running eve.flat.disk-worker against base. Answers
   the Process; its stdout/stderr land in log-path."
       [base out go seed log-path]
       (let [pb (ProcessBuilder.
                 ^java.util.List
                 ["java" "-cp" (System/getProperty "java.class.path")
                  "clojure.main" "-m" "eve.flat.disk-worker"
                  base out go (str seed)])]
         (.redirectErrorStream pb true)
         (.redirectOutput pb (java.lang.ProcessBuilder$Redirect/to (File. log-path)))
         (.start pb)))

;; ===========================================================================
;; H1.1 — two processes claiming runs and blocks in one disk domain:
;; no block twice, free counts exact
;; ===========================================================================

     (deftest run-claim-two-process
       (let [dir (temp-dir)
             base (str dir "/dom")
        ;; the parent creates the domain's files, then lets go of them:
        ;; both children join the same files cold.
             _ (let [sio (alloc/mmap-domain-sio! base {:create? true :capacities capacities})]
                 (alloc/release-domain-sio! sio true))
             go (str dir "/go")
             out-a (str dir "/out-a.edn")
             out-b (str dir "/out-b.edn")
             pa (spawn-worker! base out-a go 111 (str dir "/worker-a.log"))
             pb (spawn-worker! base out-b go 222 (str dir "/worker-b.log"))]
         (spit go "go")
         (is (.waitFor pa 180 java.util.concurrent.TimeUnit/SECONDS)
             "worker A finishes in time (its log is in the temp dir)")
         (is (.waitFor pb 180 java.util.concurrent.TimeUnit/SECONDS)
             "worker B finishes in time")
         (is (= 0 (.exitValue pa)) "worker A exits clean")
         (is (= 0 (.exitValue pb)) "worker B exits clean")
         (let [claimed-a (read-string (slurp out-a))
               claimed-b (read-string (slurp out-b))
               set-a (set claimed-a)
               set-b (set claimed-b)]
           (is (= (count claimed-a) (count set-a)) "worker A was handed no block twice")
           (is (= (count claimed-b) (count set-b)) "worker B was handed no block twice")
           (is (empty? (cset/intersection set-a set-b))
               "no block was handed to both processes")
      ;; free counts exact against a fresh open of the same files, and
      ;; freeing the claimed set once each restores every class exactly
           (let [sio (alloc/mmap-domain-sio! base {:create? false})
                 ctx (alloc/sio-root sio)
                 all (into set-a set-b)
                 by-class (group-by alloc/decode-class-idx all)]
             (doseq [ci (range 3)]
               (is (= (- (class-total ctx ci) (count (get by-class ci)))
                      (class-free ctx ci))
                   (str "class " ci ": free count is total minus exactly what both processes claimed")))
             (doseq [off all]
               (is (true? (alloc/-sio-free! sio off))
                   "every claimed block frees exactly once"))
             (doseq [ci (range 3)]
               (is (= (class-total ctx ci) (class-free ctx ci))
                   (str "class " ci ": free count back to total after freeing the claimed set")))
             (alloc/release-domain-sio! sio true)))))

;; ===========================================================================
;; H1.2 — place exact on won, lost and thrown swaps in a disk domain
;; ===========================================================================

     (def ^:private disk-value
       {:payload (vec (range 400)) :tags #{"x" "y"}})

     (defn- image-of
       "The checked image of disk-value, detached from a throwaway heap atom;
   :init-blocks is the block count of the atoms' initial value
   {:other 1}, measured by the same detach — a committed swap retires
   its predecessor, so the won and lost arms grow by
   nblocks - init-blocks, not nblocks."
       []
       (let [mk (fn [v suffix]
                  (let [src (ea/lookup-or-create-mmap-atom!
                             (ea/atom-domain nil :capacities capacities)
                             (str "h1/" suffix "-" (rand-int 1000000000)) nil)]
                    (reset! src v)
                    (flat/detach src)))
             bs (mk disk-value "src")
             ibs (mk {:other 1} "init")]
         {:bytes bs
          :checked (check/open-checked bs)
          :nblocks (count (image/image-blocks (image/open-image bs)))
          :init-blocks (count (image/image-blocks (image/open-image ibs)))}))

     (defn- disk-atom!
       "A fresh atom in the disk domain at base (domain opened by the caller),
   holding {:other 1}, and the domain's blocks-in-use before placement."
       [dom name]
       (let [a (ea/lookup-or-create-mmap-atom! dom name nil)]
         (reset! a {:other 1})
         (#'ea/flush-zero-queue! (ea/-domain-state a))
         [a (blocks-in-use (ea/atom-sio a))]))

     (deftest place-exact-disk
       (let [dir (temp-dir)
             base (str dir "/dom")
             img (image-of)
             dom (ea/persistent-atom-domain base :capacities capacities :journal? false)]
         (testing "a won swap: the value lands, the growth is exactly the image's blocks"
           (let [[a used0] (disk-atom! dom "h1/won")]
             (flat/place a (:checked img))
             (#'ea/flush-zero-queue! (ea/-domain-state a))
             (is (= disk-value (d/eve->clj @a)))
             (is (= (+ used0 (- (:nblocks img) (:init-blocks img)))
                    (blocks-in-use (ea/atom-sio a)))
                 "the placement claimed exactly the image's blocks, in the disk domain (net of the retired predecessor)")))
         (testing "a lost CAS: the retry lands it once, the lost attempt's blocks are all back"
           (let [[a used0] (disk-atom! dom "h1/lost")
                 attempts (clojure.core/atom 0)]
             (swap! a (fn [_]
                        (when (== 1 (swap! attempts inc))
                          @(future (reset! a {:other 1})))
                        (place/place-value d/*domain-sio* (:checked img))))
             (#'ea/flush-zero-queue! (ea/-domain-state a))
             (is (= disk-value (d/eve->clj @a)))
             (is (= (+ used0 (- (:nblocks img) (:init-blocks img)))
                    (blocks-in-use (ea/atom-sio a)))
                 "same total as the won swap: nothing of the lost attempt remains claimed")))
         (testing "a throw after placement: the atom is untouched, the leak is exactly the placed attempt"
      ;; The atom's swap machinery has no catch around the swap fn, for
      ;; any writer (Z3.4's banked correction, measured on heap domains):
      ;; what place guarantees — and what is asserted here, on disk — is
      ;; that the growth is exactly the image's blocks: nothing partial,
      ;; nothing extra.
           (let [[a used0] (disk-atom! dom "h1/throw")]
             (is (thrown? clojure.lang.ExceptionInfo
                          (swap! a (fn [_]
                                     (place/place-value d/*domain-sio* (:checked img))
                                     (throw (ex-info "boom" {}))))))
             (is (= {:other 1} (d/eve->clj @a)) "the atom's value is untouched")
             (is (= (+ used0 (:nblocks img)) (blocks-in-use (ea/atom-sio a)))
                 "the throw leaks exactly the placed attempt, nothing partial, nothing extra")))
         (testing "close and reopen: the placed value reads back from the files, set unchanged"
           (let [a (ea/lookup-or-create-mmap-atom! dom "h1/won" nil)
                 used-before (blocks-in-use (ea/atom-sio a))]
             (ea/close-atom-domain! dom)
             (let [dom2 (ea/persistent-atom-domain base :capacities capacities :journal? false)
                   a2 (ea/lookup-or-create-mmap-atom! dom2 "h1/won" nil)]
               (is (= disk-value (d/eve->clj @a2))
                   "the value placed from the image is the value the reopened domain holds")
               (is (= used-before (blocks-in-use (ea/atom-sio a2)))
                   "the allocated set survives the reopen exactly")
               (ea/close-atom-domain! dom2))))))))
