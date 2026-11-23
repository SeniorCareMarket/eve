(ns eve.jlog-domain-test
  "A disk domain on the mmapped commit log (persistent-atom-domain :log :jlog;
   eve.jlog, eve-mmap-journal-plan §4): the round trip, the open that follows
   the domain's own files, and the recovery wounds of journal-recovery-test
   (W1-W4) replayed against <base>.jlog, plus the ring lapping, the batch form,
   and a dead process's leftovers (its ACTIVE slot, a leaked in-flight count:
   H5) on an open no live process holds."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as a]
            [eve.jlog :as jlog]
            [eve.process :as proc]
            [eve.deftype-proto.data :as d])
  (:import [java.io File RandomAccessFile]))

(defn- fresh-base []
  (str (System/getProperty "java.io.tmpdir") "/eve-jlog-domain-" (rand-int 1000000000) "/d"))

(defn- read-i32 [path off]
  (let [raf (RandomAccessFile. (File. ^String path) "r")]
    (try (.seek raf (long off)) (Integer/reverseBytes (.readInt raf)) (finally (.close raf)))))

(defn- write-i32! [path off v]
  (let [raf (RandomAccessFile. (File. ^String path) "rw")]
    (try (.seek raf (long off)) (.writeInt raf (Integer/reverseBytes (int v))) (finally (.close raf)))))

(defn- read-range [path off n]
  (let [raf (RandomAccessFile. (File. ^String path) "r")
        b (byte-array n)]
    (try (.seek raf (long off)) (.readFully raf b) b (finally (.close raf)))))

(defn- write-range! [path off ^bytes b]
  (let [raf (RandomAccessFile. (File. ^String path) "rw")]
    (try (.seek raf (long off)) (.write raf b) (finally (.close raf)))))

(defn- xor-range! [path start end]
  (write-range! path start (byte-array (map #(bit-xor % -1) (read-range path start (- end start))))))

(defn- table-range [] [d/ATOM_TABLE_START (+ d/ATOM_TABLE_START jlog/TABLE_BYTES)])

(defn- halt-domain!
  "A killed process's leavings: the heartbeat stops and the domain is forgotten
   WITHOUT release-domain!'s checkpoint; RELEASE? also frees the worker slot
   (otherwise it stays ACTIVE, as kill -9 leaves it)."
  ([dom base] (halt-domain! dom base true))
  ([dom base release?]
   (let [ds (#'a/atom-domain-state dom)]
     (proc/stop-timer! (:heartbeat ds))
     (when release? (#'a/mmap-release-slot! (:root-r ds) (:slot-idx ds)))
     (swap! @#'a/domain-cache dissoc base)
     ds)))

(defn- newest-record [base]
  (let [log (jlog/open (str base ".jlog"))]
    [log (jlog/pick (jlog/scan log))]))

(deftest round-trip-and-the-domain-decides
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :log :jlog)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (swap! x + 40)
    (swap! x inc)
    (swap! x inc)
    (a/close-atom-domain! dom)
    (is (.exists (File. (str base ".jlog"))) "the log is <base>.jlog")
    (is (not (.exists (File. (str base ".journal")))) "no legacy journal is written")
    (testing "a reopen without :log follows the domain's files"
      (let [dom2 (a/persistent-atom-domain base)
            x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
        (is (= 42 @x2))
        (is (some? (:log (:journal (#'a/atom-domain-state dom2)))) "opened on the jlog")
        (swap! x2 inc)
        (a/close-atom-domain! dom2)))
    (let [[_ rstar] (newest-record base)]
      (is (= jlog/KIND_ANCHOR (:kind rstar)) "close appends an ANCHOR: the newest record")
      (is (= (seq (:table rstar)) (seq (apply read-range (str base ".root") (let [[s e] (table-range)] [s (- e s)]))))
          "the newest record's table is the root's"))))

(defn- build-domain!
  "t/x to 41 then 42 on a jlog domain, closed cleanly; answers the root table
   as it stood at 41 and the root epoch then."
  [base]
  (let [dom (a/persistent-atom-domain base :log :jlog)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        [s e] (table-range)]
    (swap! x + 41)
    (let [at41 [(read-range (str base ".root") s (- e s)) (read-i32 (str base ".root") d/ROOT_EPOCH_OFFSET)]]
      (swap! x inc)
      (a/close-atom-domain! dom)
      at41)))

(deftest w1-stale-root-is-repaired
  (let [base (fresh-base)
        [table41 epoch41] (build-domain! base)]
    ;; the wound: the root wound back to the state of an older commit (its table and epoch)
    (write-range! (str base ".root") d/ATOM_TABLE_START table41)
    (write-i32! (str base ".root") d/ROOT_EPOCH_OFFSET epoch41)
    (let [dom (a/persistent-atom-domain base)
          x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 42 @x) "the log's newest record is installed over the stale root")
      (a/close-atom-domain! dom))))

(deftest w2-root-table-corruption-at-equal-epoch
  (let [base (fresh-base)
        _ (build-domain! base)]
    (apply xor-range! (str base ".root") (table-range))
    (let [dom (a/persistent-atom-domain base)
          x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 42 @x) "the CRC-valid record at the same epoch repairs the corrupted root table")
      (a/close-atom-domain! dom))))

(deftest w3-torn-newest-record-falls-back
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :log :jlog)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (swap! x + 41)
    (swap! x inc)
    (halt-domain! dom base)
    ;; the wound: tear the newest record (bytes inside its table: the CRC no longer matches)
    (let [[log rstar] (newest-record base)
          off (+ (:data-off log) (* jlog/SLOT_BYTES (:slot rstar)))]
      (xor-range! (str base ".jlog") (+ off 100) (+ off 200)))
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 41 @x2) "the torn newest record is passed over; the newest valid one, a commit back, is installed")
      (swap! x2 inc)
      (is (= 42 @x2) "and the domain commits on")
      (a/close-atom-domain! dom2))))

(deftest w4-bad-log-magic-is-refused-not-overwritten
  (let [base (fresh-base)
        _ (build-domain! base)
        before (read-range (str base ".jlog") 0 64)]
    (xor-range! (str base ".jlog") 0 1)
    (is (thrown? Exception (a/persistent-atom-domain base)) "a log of the wrong magic is refused")
    (is (= (seq (byte-array (cons (bit-xor (first before) -1) (rest before))))
           (seq (read-range (str base ".jlog") 0 64)))
        "and left exactly as it was")))

(deftest ring-laps
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :log :jlog :jlog-slots 8)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (dotimes [_ 50] (swap! x inc))
    (halt-domain! dom base)
    (let [log (jlog/open (str base ".jlog"))]
      (is (= 8 (count (jlog/scan log))) "every slot of the lapped ring holds a valid record"))
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 50 @x2))
      (a/close-atom-domain! dom2))))

(deftest batch-form-is-durable-at-its-boundary-and-close
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :log :jlog :journal-batch 4)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (dotimes [_ 10] (swap! x inc))
    (a/close-atom-domain! dom)
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 10 @x2))
      (a/close-atom-domain! dom2))))

(deftest a-dead-process-leftovers-are-recovered
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :log :jlog)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        _ (swap! x + 7)
        ds (halt-domain! dom base false)
        root (str base ".root")
        hb (+ d/ROOT_WORKER_REGISTRY_START (* (:slot-idx ds) d/WORKER_SLOT_SIZE))]
    ;; kill -9's leavings, aged: the slot ACTIVE with a heartbeat long stale, and a write's in-flight count leaked
    (write-i32! root (+ hb d/OFFSET_WS_HEARTBEAT_LO) 1)
    (write-i32! root (+ hb d/OFFSET_WS_HEARTBEAT_HI) 0)
    (write-i32! root d/ROOT_INFLIGHT_OFFSET 1)
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 7 @x2))
      (is (zero? (read-i32 root d/ROOT_INFLIGHT_OFFSET)) "recovery repaired the leaked in-flight count (H5)")
      (swap! x2 inc)
      (is (= 8 @x2) "a write after recovery does not wait on the dead writer")
      (a/close-atom-domain! dom2))))
