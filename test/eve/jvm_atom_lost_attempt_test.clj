(ns eve.jvm-atom-lost-attempt-test
  "A swap! attempt that loses its root CAS frees every block it allocated
   (VS's docs/memory-model.md: a lost CAS frees its own tree at once). eve.atom
   stopped the attempt's allocation log before reading it, and a stopped log
   reads empty, so the losing attempt's blocks stayed allocated (found by the
   slab-io conformance test's logging case, L19, 2026-10-17). The same writes
   run twice, each in its own in-process domain: once without contention, once
   with another thread's reset! landing inside the first attempt so it loses
   and retries. With the count (eve.tally) the slot holds a new value before
   its CAS, so a lost attempt lets its new value go through the zero queue and
   frees the rest of what it allocated at once: the writes' own flushes are held
   back, then each domain's zero queue is flushed with no reader pinned, so the
   blocks in use differ only by what the lost attempt kept.

   No reader pinned means read-end :commit (C): since eve 9addb4f a swap!/reset! answers a view that, in read-end
   :gc (B), holds its pin until the JVM's GC collects it, so the writes' own discarded answers pinned the epochs the
   flush needed and the lost domain read 94 blocks against the quiet one's 48 (measured 2026-11-20 ET at dcba2ee;
   0 failures in C). The test binds C for its body, the premise it states."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as atom]
            [eve.view :as view]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]))

(defn- blocks-in-use [a]
  (let [ctx (atom/atom-sio a)]
    (reduce + (for [ci (range d/NUM_SLAB_CLASSES)
                    :let [r (aget ^objects (.-regions ctx) ci)]]
                (- (mem/-load-i32 r d/SLAB_HDR_TOTAL_BLOCKS) (mem/-load-i32 r d/SLAB_HDR_FREE_COUNT))))))

(defn- writes! [id contend?]
  (let [a (atom/atom id {})
        attempts (clojure.core/atom 0)
        before (blocks-in-use a)]
    (when-not contend? (reset! a {:other 1}))
    (swap! a (fn [m]
               (when (and contend? (= 1 (swap! attempts inc)))
                 @(future (reset! a {:other 1})))
               (assoc m :k (vec (range 40)))))
    (#'eve.atom/flush-zero-queue! (atom/-domain-state a))
    {:value @a :used (- (blocks-in-use a) before) :attempts @attempts}))

(deftest a-lost-attempt-frees-what-it-allocated
  (let [mode (view/read-end-mode)]
   (view/set-read-end! :commit)
   (try
  (with-redefs-fn {#'eve.atom/try-flush-retires! (fn [_ds] nil)}
    (fn []
      (let [quiet (writes! ::quiet false)
            lost  (writes! ::lost true)]
        (testing "the same value either way, the contended swap! after a retry"
          (is (= {:other 1 :k (vec (range 40))} (:value quiet) (:value lost)))
          (is (= 2 (:attempts lost))))
        (testing "the lost attempt's blocks are free again"
          (is (pos? (:used quiet)))
          (is (= (:used quiet) (:used lost)))))))
   (finally (view/set-read-end! mode)))))
