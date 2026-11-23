(ns eve.jlog-fuzz-test
  "Torn-write fuzzing of a jlog domain (eve-mmap-journal-plan §6.4, the image
   method): a domain driven through K commits and halted (a killed process's
   files, no checkpoint), then each image restores the pristine .root and .jlog,
   applies one wound the crash model allows — the newest records torn at every
   512 B boundary, zeroed or bit-flipped (a power loss mid-append); the root
   table's second page reverted or zeroed; a CRC-valid record of another file
   (nonce) or misplaced (seq mod N) planted; the header's reservation words
   garbage — and reopens. Recovery must not throw, must install the newest
   record that is still valid (the highest label: every commit whose record
   survived), and the domain must commit on."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as a]
            [eve.jlog :as jlog]
            [eve.process :as proc]
            [eve.deftype-proto.data :as d])
  (:import [java.io File RandomAccessFile]
           [java.nio.file Files StandardCopyOption CopyOption]))

(defn- fresh-base []
  (str (System/getProperty "java.io.tmpdir") "/eve-jlog-fuzz-" (rand-int 1000000000) "/d"))

(defn- read-range [path off n]
  (let [raf (RandomAccessFile. (File. ^String path) "r")
        b (byte-array n)]
    (try (.seek raf (long off)) (.readFully raf b) b (finally (.close raf)))))

(defn- write-range! [path off ^bytes b]
  (let [raf (RandomAccessFile. (File. ^String path) "rw")]
    (try (.seek raf (long off)) (.write raf b) (finally (.close raf)))))

(defn- read-i32 [path off]
  (let [raf (RandomAccessFile. (File. ^String path) "r")]
    (try (.seek raf (long off)) (Integer/reverseBytes (.readInt raf)) (finally (.close raf)))))

(defn- copy! [from to]
  (Files/copy (.toPath (File. ^String from)) (.toPath (File. ^String to))
              ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

(defn- halt! [dom base]
  (let [ds (#'a/atom-domain-state dom)]
    (proc/stop-timer! (:heartbeat ds))
    (#'a/mmap-release-slot! (:root-r ds) (:slot-idx ds))
    (swap! @#'a/domain-cache dissoc base)))

(defn- build!
  "t/x through 1..k, one record per commit; answers {label value} (the label a
   commit's record carries is the epoch after its bump: one writer)."
  [base k]
  (let [dom (a/persistent-atom-domain base :jlog-slots 16)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        m (into {} (for [_ (range k)]
                     (do (swap! x inc) [(read-i32 (str base ".root") d/ROOT_EPOCH_OFFSET) @x])))]
    (halt! dom base)
    m))

(defn- slot-off [log rec] (+ (:data-off log) (* jlog/SLOT_BYTES (:slot rec))))

(defn- expected
  "The value recovery must serve once WOUND! has been applied to the restored
   files: the commit of the highest label among the records still valid, the
   root's own value when no commit record is (an ANCHOR or no record at all)."
  [base by-label root-value]
  (let [log (jlog/open (str base ".jlog"))
        rstar (jlog/pick (jlog/scan log))]
    (get by-label (:label rstar) root-value)))

(defn- image!
  "Restore the pristine root and log, apply WOUND! (fn [base log
   recs-newest-first]), reopen, answer [expected got committed-on?]. The slab
   files are the images' common ground, so an image only reads — unless
   COMMIT? (the last image): then it commits once after recovery, and answers
   whether the commit landed."
  [base pristine by-label wound! commit?]
  (copy! (str pristine ".root") (str base ".root"))
  (copy! (str pristine ".jlog") (str base ".jlog"))
  (let [log (jlog/open (str base ".jlog"))
        recs (sort-by :label #(compare %2 %1) (jlog/scan log))]
    (wound! base log recs))
  (let [want (expected base by-label nil)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)
        got @x
        on? (if commit? (do (swap! x inc) (= (inc got) @x)) true)]
    (halt! dom base)
    [want got on?]))

(deftest torn-writes-recover-to-the-newest-valid-record
  (let [base (fresh-base)
        k 40
        by-label (build! base k)
        pristine (str base "-pristine")]
    (copy! (str base ".root") (str pristine ".root"))
    (copy! (str base ".jlog") (str pristine ".jlog"))
    (let [rnd (java.util.Random. 20261123)
          wounds (concat
                  ;; (i) the newest 1-3 records torn at every 512 B boundary (garbage past the tear), zeroed, bit-flipped
                  (for [m [1 2 3]
                        kind (concat (for [t (range 0 2560 512)] [:tear t]) [[:zero] [:flip] [:flip] [:flip]])]
                    [(str "newest " m " " kind)
                     (fn [base log recs]
                       (doseq [rec (take m recs)]
                         (let [off (slot-off log rec)
                               path (str base ".jlog")]
                           (case (first kind)
                             :tear (write-range! path (+ off (second kind))
                                                 (let [b (byte-array (- 4096 (second kind)))] (.nextBytes rnd b) b))
                             :zero (write-range! path off (byte-array 4096))
                             :flip (let [i (.nextInt rnd jlog/REC_BYTES)
                                         b (read-range path (+ off i) 1)]
                                     (write-range! path (+ off i)
                                                   (byte-array [(unchecked-byte (bit-xor (aget b 0)
                                                                                         (bit-shift-left 1 (.nextInt rnd 8))))])))))))])
                  ;; (ii) the root table's second page (8192..8320 of the table's 6272..8320) reverted to an older commit, or zero
                  [["root table page reverted"
                    (fn [base _ recs]
                      (let [old (last recs)]
                        (write-range! (str base ".root") 8192
                                      (java.util.Arrays/copyOfRange ^bytes (:table old) (- 8192 d/ATOM_TABLE_START)
                                                                    jlog/TABLE_BYTES))))]
                   ["root table page zeroed"
                    (fn [base _ _] (write-range! (str base ".root") 8192 (byte-array 128)))]]
                  ;; (iii) a CRC-valid record of another file (nonce) with a higher label, and a misplaced copy
                  [["foreign-nonce record, higher label"
                    (fn [base log recs]
                      (let [top (first recs)
                            forged (jlog/encode-record (inc (:seq top)) 0 (+ 1000 (:label top)) jlog/KIND_COMMIT
                                                       (inc (:nonce log)) 1 (byte-array jlog/TABLE_BYTES))
                            slot (bit-and (inc (:seq top)) (dec (:n log)))]
                        (write-range! (str base ".jlog") (+ (:data-off log) (* jlog/SLOT_BYTES slot)) forged)))]
                   ["misplaced copy of the newest record"
                    (fn [base log recs]
                      (let [top (first recs)
                            rec (read-range (str base ".jlog") (slot-off log top) jlog/REC_BYTES)
                            other (bit-and (+ 3 (:slot top)) (dec (:n log)))]
                        (write-range! (str base ".jlog") (+ (:data-off log) (* jlog/SLOT_BYTES other)) rec)))]]
                  ;; (iv) the header's reservation words garbage (recovery rebuilds them)
                  [["header tail and slot states garbage"
                    (fn [base log _]
                      (write-range! (str base ".jlog") 64 (byte-array (repeat 4 (byte 0x5A))))
                      (write-range! (str base ".jlog") 4096 (let [b (byte-array (* 8 (:n log)))] (.nextBytes rnd b) b)))]])
          n (count wounds)
          results (vec (map-indexed (fn [i [label wound!]]
                                      (let [[want got on?] (image! base pristine by-label wound! (= i (dec n)))]
                                        [label want got on?]))
                                    wounds))]
      (testing "every image recovers to the newest still-valid record and commits on"
        (doseq [[label want got on?] results]
          (is (some? got) label)
          (when want (is (= want got) label))
          (is on? (str label ": the domain commits after recovery"))))
      (is (= k (count by-label)))
      (is (every? (fn [[_ want got]] (or (nil? want) (= want got))) results)
          (str (count results) " images")))))
