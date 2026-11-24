(ns eve.jlog-domain-test
  "A disk domain on its commit log (eve.jlog, a redo log; doc/jlog-redo-spec.md):
   the round trip through a killed process; slab files reverted to the last
   checkpoint (the page cache of every commit since lost) and rebuilt from the
   log's redo, the rows it touched pinned; a hole that ends the prefix; a dead
   writer's hole voided; lost CASes closed with ABORT; a large commit's run of
   slots; the forced fallback; checkpoints folding anchors; the batch form; a
   dead process's leftovers (H5); a log of the wrong magic refused; and the
   legacy <base>.journal migrated."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as a]
            [eve.jlog :as jlog]
            [eve.process :as proc]
            [eve.tally :as tally]
            [eve.deftype-proto.data :as d])
  (:import [java.io File RandomAccessFile]
           [java.nio.file Files StandardCopyOption CopyOption]))

(defn- fresh-base []
  (str (System/getProperty "java.io.tmpdir") "/eve-jlog-domain-" (rand-int 1000000000) "/d"))

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

(defn- crash-restore!
  "PATH restored to its image at SUFFIX as a power loss leaves it after a growth the code forced: the image's bytes,
   but a file that grew since keeps its grown length (the new end reads zero) and a slab data file its header page
   (force-grown! forces both before a block past the old end is used)."
  [path suffix]
  (let [f (File. ^String path)
        cur-len (.length f)
        data? (re-matches #".*\.slab\d" path)
        page0 (when data? (let [raf (RandomAccessFile. f "r") b (byte-array 4096)]
                            (try (.readFully raf b) b (finally (.close raf)))))]
    (copy! (str path suffix) path)
    (when (> cur-len (.length f))
      (let [raf (RandomAccessFile. f "rw")]
        (try (.setLength raf cur-len)
             (when page0 (.seek raf 0) (.write raf ^bytes page0))
             (finally (.close raf)))))))

(defn- restore! [paths suffix] (doseq [p paths] (crash-restore! p suffix)))

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
    (is (= jlog/LOG_VERSION (read-i32 (str base ".jlog") jlog/H_VERSION)) "a new domain's log is a redo log")
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
                          (keep #(jlog/read-slot (log-of dom2) %) (range (:n (log-of dom2))))))
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
    (is (= jlog/K_VOID (:kind (jlog/read-record log hole))) "the waiter voided the dead writer's slot")
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

(deftest a-large-commit-spans-a-run-of-slots
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" nil)
        big (vec (range 3000))]
    (a/sync! x)
    (let [files (conj (vec (slab-files base)) (str base ".root"))
          _ (snapshot! files ".ckpt")
          before (jlog/durable (log-of dom))
          _ (reset! x big)
          first-part (jlog/read-record (log-of dom) before)
          cnt (bit-and (:part first-part) 0xFFFF)]
      (is (= jlog/K_COMMIT (:kind first-part)) "a redo past one slot is still a COMMIT")
      (is (< 1 cnt) "carried by a run of consecutive slots, each its own CRC-checked part")
      (is (= (+ before cnt) (jlog/durable (log-of dom))) "the whole run is made durable together")
      (swap! x conj :end)
      (let [ds0 (halt! dom base)]
        (power-loss! base files ".ckpt" (:slot-idx ds0)))
      (let [dom2 (reopen base)
            x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" nil)]
        (is (= (conj big :end) @x2) "rebuilt from the run's redo over slabs at the checkpoint")
        (a/close-atom-domain! dom2)))))

(deftest a-commit-past-a-quarter-of-the-ring-takes-the-forced-fallback
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :jlog-slots 16)
        x (a/lookup-or-create-mmap-atom! dom "t/x" nil)
        big (vec (range 3000))]
    (reset! x big)
    (is (= jlog/K_NOREDO (:kind (jlog/read-record (log-of dom) (last-seq dom))))
        "a redo past a quarter of the ring is a NOREDO record (its blocks forced before its CAS)")
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

(defn- build-domain!
  "t/x to 41 then 42 on a jlog domain, closed cleanly; answers the root table
   as it stood at 41 and the root epoch then."
  [base]
  (let [dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        [s e] (table-range)]
    (swap! x + 41)
    (let [at41 [(read-range (str base ".root") s (- e s)) (read-i32 (str base ".root") d/ROOT_EPOCH_OFFSET)]]
      (swap! x inc)
      (a/close-atom-domain! dom)
      at41)))

(deftest batch-form-is-durable-at-its-boundary-and-close
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base :journal-batch 4)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (dotimes [_ 10] (swap! x inc))
    (a/close-atom-domain! dom)
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 10 @x2))
      (a/close-atom-domain! dom2))))

(deftest a-dead-process-leftovers-are-recovered
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
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

(deftest w4-bad-log-magic-is-refused-not-overwritten
  (let [base (fresh-base)
        _ (build-domain! base)
        before (read-range (str base ".jlog") 0 64)]
    (xor-range! (str base ".jlog") 0 1)
    (is (thrown? Exception (a/persistent-atom-domain base)) "a log of the wrong magic is refused")
    (is (= (seq (byte-array (cons (bit-xor (first before) -1) (rest before))))
           (seq (read-range (str base ".jlog") 0 64)))
        "and left exactly as it was")))

;; ---------------------------------------------------------------------------
;; Legacy journal migration (eve-mmap-journal-plan §4.7)
;; ---------------------------------------------------------------------------

(defn- evj1-record
  "One EVJ1 record's bytes: [magic][len][epoch][table-len][table][crc32 over
   epoch..table-end], big-endian — what the legacy journal adapters wrote."
  ^bytes [epoch ^bytes table]
  (let [tlen (alength table)
        buf (java.nio.ByteBuffer/allocate (+ 8 4 4 tlen 4))]
    (.putInt buf (unchecked-int 0x45564A31))
    (.putInt buf (int (+ 12 tlen)))
    (.putInt buf (int epoch))
    (.putInt buf (int tlen))
    (.put buf table)
    (let [crc (java.util.zip.CRC32.)]
      (.update crc (.array buf) 8 (+ 8 tlen))
      (.putInt buf (unchecked-int (.getValue crc))))
    (.array buf)))

(defn- legacy-domain!
  "A domain as a pre-jlog binary leaves it after a kill: t/x taken to 1..N,
   one EVJ1 record per commit in <base>.journal, no <base>.jlog. (Built on
   the jlog — the legacy writer is gone — then the log removed and the
   journal written from the root states each commit left.) Answers
   [[value epoch table] ...]."
  [base n]
  (let [dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        [s e] (table-range)
        states (doall (for [_ (range n)]
                        (do (swap! x inc)
                            [@x (read-i32 (str base ".root") d/ROOT_EPOCH_OFFSET)
                             (read-range (str base ".root") s (- e s))])))]
    (halt-domain! dom base)
    (.delete (File. (str base ".jlog")))
    (let [raf (RandomAccessFile. (File. (str base ".journal")) "rw")]
      (try (doseq [[_ ep tbl] states] (.write raf (evj1-record ep tbl)))
           (finally (.close raf))))
    states))

(deftest legacy-journal-migrates-to-its-newest-record
  (let [base (fresh-base)
        states (legacy-domain! base 5)
        [_ e2 t2] (nth states 1)]
    ;; the wound: the root wound back to the second commit's state (the journal's newest record is ahead)
    (write-i32! (str base ".root") d/ROOT_EPOCH_OFFSET e2)
    (write-range! (str base ".root") d/ATOM_TABLE_START t2)
    (let [dom (a/persistent-atom-domain base)
          x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 5 @x) "the legacy journal's newest record is installed")
      (swap! x inc)
      (a/close-atom-domain! dom))
    (is (not (.exists (File. (str base ".journal")))) "the legacy journal is renamed aside")
    (is (.exists (File. (str base ".journal.migrated"))) "... to <base>.journal.migrated, never deleted")
    (is (.exists (File. (str base ".jlog"))))
    (let [dom (a/persistent-atom-domain base)
          x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 6 @x) "and the domain lives on the jlog from here")
      (a/close-atom-domain! dom))))

(deftest legacy-journal-with-an-overwritten-record-resyncs
  (let [base (fresh-base)
        states (legacy-domain! base 6)
        [_ e1 t1] (first states)
        rec 2068]
    ;; the wound: the framing of the third record overwritten (a foreign append's bytes); the read resyncs past it
    (write-range! (str base ".journal") (* 2 rec) (byte-array (repeat 40 (byte 7))))
    (write-i32! (str base ".root") d/ROOT_EPOCH_OFFSET e1)
    (write-range! (str base ".root") d/ATOM_TABLE_START t1)
    (let [dom (a/persistent-atom-domain base)
          x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 6 @x) "the newest record, after the overwritten one, is found and installed")
      (a/close-atom-domain! dom))))

(deftest a-legacy-record-older-than-the-root-never-rolls-it-back
  (let [base (fresh-base)
        states (legacy-domain! base 6)
        [_ e1 t1] (first states)]
    ;; the wound: the journal holds only the FIRST commit's record while the root is at the sixth (the shootout's
    ;; shape: the file-order newest record older than the root)
    (let [raf (RandomAccessFile. (File. (str base ".journal")) "rw")]
      (try (.setLength raf 0) (.write raf (evj1-record e1 t1)) (finally (.close raf))))
    (let [dom (a/persistent-atom-domain base)
          x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 6 @x) "the root, newer than every legacy record, is kept")
      (a/close-atom-domain! dom))))
