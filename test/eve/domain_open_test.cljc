(ns eve.domain-open-test
  "An mmap domain's creator is decided by one atomic create of its .root file (eve.process/create-exclusive!), never
   by an existence check then a create: two processes opening a fresh domain together both passed the check and each
   initialised the domain over the other's writes — measured  on 2026-10-19 with three processes
   opening at once: 4 of 5 trials lost counts or a process on babashka, 3 of 5 on nbb, 5 of 5 on the JVM (the second
   creator zeroed ROOT_INFLIGHT under the first). The race needs processes (spike/eve-thread/run/joinrace.sh); this
   pins the primitive and a join through the root's magic, which the creator now writes last."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve.test-scratch :as scratch]
            [eve.atom :as a]
            [eve.mem :as mem]
            [eve.region :as region]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

(def ^:private scratch-bases (atom []))

(defn- fresh-base []
  (let [b (scratch/unique-base "domain-open")]
    (swap! scratch-bases conj b)
    b))

(use-fixtures :once (fn [f] (try (f) (finally (doseq [b @scratch-bases] (scratch/delete-base! b))))))

(deftest one-process-creates-the-file
  (let [f (str (fresh-base) ".x")]
    (proc/ensure-parent-dir! f)
    (is (true? (proc/create-exclusive! f)) "the first create makes it")
    (is (false? (proc/create-exclusive! f)) "a second finds it there")
    (is (proc/file-exists? f))))

(deftest a-reopened-domain-joins-what-it-wrote
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (swap! x + 41)
    (a/close-atom-domain! dom)
    (testing "the .root is there: the second open joins, through the root's magic"
      (let [dom2 (a/persistent-atom-domain base)
            x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
        (is (= 41 @x2))
        (swap! x2 inc)
        (is (= 42 @x2))
        (a/close-atom-domain! dom2)))))

(defn- settle-construction-views!
  "Construction may pin views for reads nobody holds (read-end B). Those
   releases run on the JVM/bb Cleaner thread, so a snapshot taken too
   early races the table the portable model then freezes. Drain
   collectible views and wait until the pin table is stable across a
   GC cycle; held views stay."
  [_ds]
  #?(:cljs nil
     :clje nil
     :lpy nil
     :cljr nil
     :glj nil
     :default
     (loop [i 0 prev ::unset]
       (System/gc)
       (Thread/sleep 50)
       (when-let [s (resolve 'eve.view/sweep!)] (s))
       (let [cur (into {} @(:pins _ds))]
         (if (or (= cur prev) (>= i 40))
           cur
           (recur (inc i) cur))))))

(defn- published-epoch
  "The worker slot's CURRENT_EPOCH word (0 when nothing is pinned)."
  [ds]
  (mem/-load-i32 (:root-r ds) (+ d/ROOT_WORKER_REGISTRY_START
                                 (* (:slot-idx ds) d/WORKER_SLOT_SIZE)
                                 d/OFFSET_WS_CURRENT_EPOCH)))

(defn- portable-pin-step
  "Independent copy of the portable pins-map update (atom.cljc pin-epoch! /
   unpin-epoch! / view-pin! / view-unpin!)."
  [m tid op epoch]
  (case op
    :pin (if-let [[e depth] (get m tid)]
           (assoc m tid [(min e epoch) (inc depth)])
           (assoc m tid [epoch 1]))
    :unpin (if-let [[e depth] (get m tid)]
             (if (> depth 1)
               (assoc m tid [e (dec depth)])
               (dissoc m tid))
             m)
    :view-pin (let [vs (or (get m :eve.atom/views) (sorted-map))]
                (assoc m :eve.atom/views (assoc vs epoch (inc (get vs epoch 0)))))
    :view-unpin (let [vs (get m :eve.atom/views)
                      n (dec (get vs epoch 0))
                      vs (if (pos? n) (assoc vs epoch n) (dissoc vs epoch))]
                  (if (pos? (count vs)) (assoc m :eve.atom/views vs) (dissoc m :eve.atom/views)))))

(defn- portable-published
  "Independent copy of slot-pin!'s published word (0 when no pin remains)."
  [m]
  (let [views (get m :eve.atom/views)
        tmin (reduce-kv (fn [acc k v]
                          (if (= k :eve.atom/views)
                            acc
                            (let [e (first v)]
                              (if (or (nil? acc) (< e acc)) e acc))))
                        nil m)
        vmin (when (pos? (count views)) (key (first views)))]
    (if (and (nil? tmin) (nil? vmin))
      0
      (min (or tmin vmin) (or vmin tmin)))))

(deftest pin-state-matches-portable-path
  (let [dom (a/atom-domain nil)
        at (a/lookup-or-create-mmap-atom! dom "pin-table" {})
        ds (a/-domain-state at)
        tid (proc/thread-id)
        apply-live! (fn [op epoch]
                      (case op
                        :pin (#'a/pin-epoch! ds epoch)
                        :unpin (#'a/unpin-epoch! ds)
                        :view-pin (#'a/view-pin! ds epoch)
                        :view-unpin (#'a/view-unpin! ds epoch)))
        live (fn [] {:pins (into {} @(:pins ds)) :published (published-epoch ds)})]
    (try
      (settle-construction-views! ds)
      (let [start (into {} @(:pins ds))]
        (is (= (portable-published start) (published-epoch ds))
            "the word already published matches the table (construction may hold views)")
        (let [script [[:pin 5] [:pin 7] [:unpin] [:unpin]
                      [:view-pin 10] [:view-pin 10] [:view-pin 4] [:view-unpin 4]
                      [:pin 8] [:unpin] [:view-unpin 10] [:view-unpin 10]
                      [:pin 3] [:view-pin 3] [:unpin] [:view-unpin 3]
                      [:pin 12] [:view-pin 20] [:view-unpin 20] [:unpin]]]
          (reduce (fn [m [op epoch]]
                    (apply-live! op epoch)
                    (let [m (portable-pin-step m tid op epoch)
                          want {:pins m :published (portable-published m)}
                          got (live)]
                      (is (= want got) (str op " " epoch))
                      m))
                  start
                  script)))
      #?(:cljs nil
         :clje nil
         :lpy nil
         :cljr nil
         :glj nil
         :default
         (let [before (:pins (live))
               ready (java.util.concurrent.CountDownLatch. 1)
               hold (java.util.concurrent.CountDownLatch. 1)
               other-tid (clojure.core/atom nil)
               t (Thread. (fn []
                            (reset! other-tid (proc/thread-id))
                            (#'a/pin-epoch! ds 9)
                            (.countDown ready)
                            (.await hold)
                            (#'a/unpin-epoch! ds)))]
           (#'a/pin-epoch! ds 20)
           (let [m (portable-pin-step before tid :pin 20)]
             (is (= {:pins m :published (portable-published m)} (live)))
             (.start t)
             (.await ready)
             (let [m2 (portable-pin-step m @other-tid :pin 9)]
               (is (= {:pins m2 :published (portable-published m2)} (live))
                   "the slot publishes the min of the threads (and any views)")
               (.countDown hold)
               (.join t)
               (is (= {:pins m :published (portable-published m)} (live))))
             (#'a/unpin-epoch! ds)
             (is (= {:pins before :published (portable-published before)} (live))))))
      (finally
        (a/close-atom-domain! dom)))))

(deftest growth-forces-only-bytes-that-exist
  ;; The people-500 DataScript build grows a descriptor column that is
  ;; 32 bytes long. force-grown! always asked for a 64-byte force, which
  ;; basilisp's mmap.flush refuses ("flush values out of range"); msync
  ;; on the other hosts let it pass. Each size forces without a throw.
  (let [p (str (fresh-base) ".desc-count")]
    (proc/ensure-parent-dir! p)
    (doseq [n [32 64 128]]
      (let [r (region/open-mmap-region p n)]
        (is (nil? (#'alloc/force-grown! r)) (str "a " n "-byte region"))
        (is (= n (mem/-byte-length r)))))
    (scratch/delete-base! p)))
