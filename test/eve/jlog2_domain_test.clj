(ns eve.jlog2-domain-test
  "A disk domain on the redo commit log (jlog v2, doc/jlog-redo-spec.md): the
   round trip through a killed process; slab files reverted to the last
   checkpoint (the page cache of every commit since lost) and rebuilt from the
   log's redo, the rows it touched pinned; a hole that ends the prefix; a dead
   writer's hole voided; lost CASes closed with ABORT; the forced fallback;
   checkpoints folding anchors; and a v1 log moved to v2."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as a]
            [eve.jlog :as jlog]
            [eve.process :as proc]
            [eve.tally :as tally]
            [eve.deftype-proto.data :as d])
  (:import [java.io File RandomAccessFile]
           [java.nio.file Files StandardCopyOption CopyOption]))

(defn- fresh-base []
  (str (System/getProperty "java.io.tmpdir") "/eve-jlog2-" (rand-int 1000000000) "/d"))

(defn- write-range! [path off ^bytes b]
  (let [raf (RandomAccessFile. (File. ^String path) "rw")]
    (try (.seek raf (long off)) (.write raf b) (finally (.close raf)))))

(defn- copy! [from to]
  (Files/copy (.toPath (File. ^String from)) (.toPath (File. ^String to))
              ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn- slab-files
  "The domain's slab files (data, bitmap and descriptor columns of every class): the files a v2 commit never forces."
  [base]
  (let [dir (.getParentFile (File. ^String base))
        stem (str (.getName (File. ^String base)) ".slab")]
    (sort (for [^File f (.listFiles dir)
                :when (.startsWith (.getName f) stem)]
            (.getPath f)))))

(defn- snapshot! [paths suffix] (doseq [p paths] (copy! p (str p suffix))))
(defn- restore! [paths suffix] (doseq [p paths] (copy! (str p suffix) p)))

(defn- halt!
  "A killed process's leavings: the heartbeat stops, the slot is let go, no checkpoint."
  [dom base]
  (let [ds (#'a/atom-domain-state dom)]
    (proc/stop-timer! (:heartbeat ds))
    (#'a/mmap-release-slot! (:root-r ds) (:slot-idx ds))
    (swap! @#'a/domain-cache dissoc base)
    ds))

(defn- power-loss!
  "Files restored to an image taken at SUFFIX, as a power loss leaves them, and the killed process's worker slot
   (SLOT) stale as a reboot finds it: its heartbeat words zero (an image taken while it ran shows it fresh, and an open
   would join the domain as live)."
  [base paths suffix slot]
  (restore! paths suffix)
  (doseq [w [d/OFFSET_WS_HEARTBEAT_LO d/OFFSET_WS_HEARTBEAT_HI]]
    (write-range! (str base ".root") (#'a/worker-slot-offset slot w) (byte-array 4))))

(defn- log-of [dom] (:log (:journal (#'a/atom-domain-state dom))))

(defn- last-seq
  "The seq of this process's newest record (one writer: the watermark less one)."
  [dom]
  (dec (jlog/durable (log-of dom))))

(defn- reopen [base] (a/persistent-atom-domain base))

(deftest redo-round-trip
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        y (a/lookup-or-create-mmap-atom! dom "t/y" {:a 0})]
    (is (= 2 (:version (log-of dom))) "a new domain's log is v2")
    (dotimes [_ 41] (swap! x inc) (swap! y update :a inc))
    (halt! dom base)
    (testing "a killed process's acknowledged commits are recovered"
      (let [dom2 (reopen base)
            x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)
            y2 (a/lookup-or-create-mmap-atom! dom2 "t/y" nil)]
        (is (= 41 @x2))
        (is (= {:a 41} @y2))
        (swap! x2 inc)
        (a/close-atom-domain! dom2)))
    (testing "and a clean close's"
      (let [dom3 (reopen base)
            x3 (a/lookup-or-create-mmap-atom! dom3 "t/x" 0)]
        (is (= 42 @x3))
        (a/close-atom-domain! dom3)))))

(deftest reverted-slabs-are-rebuilt-from-the-redo
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        m (a/lookup-or-create-mmap-atom! dom "t/m" {})]
    (dotimes [i 20] (swap! m assoc i (str "v" i)))
    (a/sync! m)                                  ;; a checkpoint: the slabs as they are now are durable
    (let [files (slab-files base)]
      (snapshot! (conj files (str base ".root")) ".ckpt")
      ;; new blocks for every commit after the checkpoint: new keys, a nested vector, a long string
      (dotimes [i 30] (swap! m assoc (+ 100 i) [i (str "w" i) {:k i}]))
      (swap! m assoc :s (apply str (repeat 300 "z")))
      (let [want (into {} @m)
            ds0 (halt! dom base)]
        ;; every page a commit wrote since the checkpoint is lost — slabs and root as they were at the checkpoint
        (power-loss! base (conj files (str base ".root")) ".ckpt" (:slot-idx ds0))
        (let [dom2 (reopen base)
              m2 (a/lookup-or-create-mmap-atom! dom2 "t/m" nil)
              ds (#'a/atom-domain-state dom2)
              ptr (second (a/version m2))]
          (is (= want @m2) "the value is rebuilt from the log's redo")
          (is (== 2147483647 (tally/count-of (:sio ds) ptr)) "a replayed commit's rows are pinned (count := CPERM)")
          (swap! m2 assoc :after 1)
          (a/close-atom-domain! dom2))
        (let [dom3 (reopen base)
              m3 (a/lookup-or-create-mmap-atom! dom3 "t/m" nil)]
          (is (= (assoc want :after 1) @m3) "and the domain commits on")
          (a/close-atom-domain! dom3))))))

(deftest a-hole-ends-the-prefix
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        by-seq (into (sorted-map) (for [_ (range 12)] (do (swap! x inc) [(last-seq dom) @x])))
        [hole-seq _] (nth (seq by-seq) 7)
        log (log-of dom)]
    (halt! dom base)
    ;; the record at hole-seq never reached the disk: the commits after it were never acknowledged
    (write-range! (str base ".jlog") (+ (:data-off log) (* jlog/SLOT_BYTES (bit-and hole-seq (:mask log))))
                  (byte-array jlog/SLOT_BYTES))
    (let [want (get by-seq (dec hole-seq))
          dom2 (reopen base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= want @x2) "recovery stops at the hole")
      (is (empty? (filter #(and (pos? (- (:seq %) hole-seq)) (#{jlog/K_COMMIT jlog/K_NOREDO} (:kind %)))
                          (keep #(jlog/read-slot3 (log-of dom2) %) (range (:n (log-of dom2))))))
          "the records past the hole are voided")
      (swap! x2 inc)
      (halt! dom2 base)
      (let [dom3 (reopen base)
            x3 (a/lookup-or-create-mmap-atom! dom3 "t/x" 0)]
        (is (= (inc want) @x3) "a commit after the recovery is not shadowed by the voided records")
        (a/close-atom-domain! dom3)))))

(deftest a-dead-writers-hole-is-voided
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        _ (swap! x inc)
        log (log-of dom)
        ;; worker 200 reserves a seq and dies before writing it (its heartbeat was never written: stale)
        {hole :seq} (jlog/reserve! log 0 200 (constantly false) 30000)]
    (swap! x inc)
    (is (= jlog/K_VOID (:kind (jlog/read-record3 log hole))) "the waiter voided the dead writer's slot")
    (is (pos? (- (jlog/durable log) hole)) "and the watermark passed it")
    (halt! dom base)
    (let [dom2 (reopen base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 2 @x2) "the commit behind the void is recovered")
      (a/close-atom-domain! dom2))))

(deftest lost-cases-are-closed-and-every-commit-lands
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        ts (doall (for [_ (range 3)] (future (dotimes [_ 150] (swap! x inc)))))]
    (run! deref ts)
    (is (= 450 @x))
    (halt! dom base)
    (let [dom2 (reopen base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 450 @x2) "every acknowledged commit of three racing threads is recovered")
      (a/close-atom-domain! dom2))))

(deftest an-oversize-commit-takes-the-forced-fallback
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" nil)
        big (vec (range 3000))]
    (reset! x big)
    (is (= jlog/K_NOREDO (:kind (jlog/read-record3 (log-of dom) (last-seq dom))))
        "a redo past one slot is a NOREDO record (its blocks forced before its CAS)")
    (swap! x conj :end)
    (halt! dom base)
    (let [dom2 (reopen base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" nil)]
      (is (= (conj big :end) @x2))
      (a/close-atom-domain! dom2))))

(deftest checkpoints-fold-the-ring
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :jlog-slots 16)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        y (a/lookup-or-create-mmap-atom! dom "t/y" 0)
        log (log-of dom)]
    (dotimes [i 60] (swap! (if (even? i) x y) inc))
    (is (pos? (jlog/anchor-seq log)) "the ring laps only past a newer anchor")
    (halt! dom base)
    (let [dom2 (reopen base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)
          y2 (a/lookup-or-create-mmap-atom! dom2 "t/y" 0)]
      (is (= [30 30] [@x2 @y2]) "an anchor folded from the ring, then the prefix after it")
      (a/close-atom-domain! dom2))))

(deftest a-v1-log-moves-to-v2
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :jlog-version 1)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (dotimes [_ 9] (swap! x inc))
    (is (= 1 (:version (log-of dom))))
    (halt! dom base)
    (let [dom2 (reopen base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 9 @x2))
      (is (= 2 (:version (log-of dom2))) "the open that recovers moves the domain to v2")
      (is (.exists (File. (str base ".jlog.v1"))) "the v1 log is renamed aside, not deleted")
      (swap! x2 inc)
      (halt! dom2 base))
    (let [dom3 (reopen base)
          x3 (a/lookup-or-create-mmap-atom! dom3 "t/x" 0)]
      (is (= 10 @x3))
      (a/close-atom-domain! dom3))))
