(ns eve.jlog-fuzz-test
  "Torn-write fuzzing of a domain on its commit log (eve.jlog, a redo log; the image method of
   eve-mmap-journal-plan §6.4, doc/jlog-redo-spec.md §8): a domain driven
   through a checkpoint and K commits after it — some large enough that their
   redo is a run of slots — halted (a killed process's
   files), then each image restores the pristine files and applies one wound a
   power loss allows, crossed with the slab files either as they are or
   reverted to the checkpoint (every page a commit wrote since lost): the
   newest 1-3 records torn at every 512 B boundary, zeroed or bit-flipped; a
   hole inside the prefix (the commits after it never acknowledged); the root
   table reverted or zeroed; the log header's reservation words and watermark
   garbage. Recovery must not throw and must serve the value of the newest
   commit in the prefix the wounded log holds (the anchor's and the consecutive
   valid records after it), rebuilt from the redo where the slabs lost it; the
   domain must commit on."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [eve.atom :as a]
            [eve.jlog :as jlog]
            [eve.process :as proc]
            [eve.test-scratch :as scratch]
            [eve.deftype-proto.data :as d])
  (:import [java.io File RandomAccessFile]
           [java.nio.file Files StandardCopyOption CopyOption]))

(def ^:private scratch-bases (atom []))

(defn- fresh-base []
  (let [b (scratch/unique-base "jlog-fuzz")]
    (swap! scratch-bases conj b)
    b))

(use-fixtures :once (fn [f] (try (f) (finally (doseq [b @scratch-bases] (scratch/delete-base! b))))))

(defn- read-range [path off n]
  (let [raf (RandomAccessFile. (File. ^String path) "r")
        b (byte-array n)]
    (try (.seek raf (long off)) (.readFully raf b) b (finally (.close raf)))))

(defn- write-range! [path off ^bytes b]
  (let [raf (RandomAccessFile. (File. ^String path) "rw")]
    (try (.seek raf (long off)) (.write raf b) (finally (.close raf)))))

(defn- copy! [from to]
  (Files/copy (.toPath (File. ^String from)) (.toPath (File. ^String to))
              ^"[Ljava.nio.file.CopyOption;" (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))

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

(defn- slab-files [base]
  (let [dir (.getParentFile (File. ^String base))
        stem (str (.getName (File. ^String base)) ".slab")]
    (sort (for [^File f (.listFiles dir) :when (.startsWith (.getName f) stem)] (.getPath f)))))

(defn- halt! [dom base]
  (let [ds (#'a/atom-domain-state dom)]
    (proc/stop-timer! (:heartbeat ds))
    (#'a/mmap-release-slot! (:root-r ds) (:slot-idx ds))
    (swap! @#'a/domain-cache dissoc base)
    ds))

(defn- log-of [dom] (:log (:journal (#'a/atom-domain-state dom))))

(defn- build!
  "t/m through a checkpoint and K commits after it, each adding a key (new blocks every commit); answers {:by-seq
   (sorted seq -> value after it) :files [the files an image restores] :slot (the killed process's worker slot)}.
   The pristine files are copied to <path>.pristine, the slabs and root as they were at the checkpoint to
   <path>.ckpt."
  [base k]
  (let [dom (a/persistent-atom-domain base :jlog-slots 256)
        m (a/lookup-or-create-mmap-atom! dom "t/m" {})
        step (fn [f] (swap! m f) [(dec (jlog/durable (log-of dom))) (into {} @m)])
        early (into (sorted-map) (for [i (range 10)] (step #(assoc % i (str "v" i)))))
        _ (a/sync! m)
        ckpt-seq (dec (jlog/durable (log-of dom)))
        files (conj (vec (slab-files base)) (str base ".root"))
        _ (doseq [p files] (copy! p (str p ".ckpt")))
        late (into (sorted-map) (for [i (range k)]
                                  ;; every eighth commit a large one: its redo is a run of slots
                                  (step (if (zero? (mod i 8))
                                          #(assoc % (+ 100 i) (vec (range (* 400 (inc (quot i 8))))))
                                          #(assoc % (+ 100 i) [i (str "w" i)])))))
        by-seq (-> early (into late) (assoc ckpt-seq (into {} @m)))
        ds (halt! dom base)
        files (conj files (str base ".jlog"))]
    (doseq [p files] (copy! p (str p ".pristine")))
    {:by-seq by-seq :files files :slot (:slot-idx ds)}))

(defn- expected
  "The value of the newest commit in the wounded log's prefix: the anchor and the consecutive valid records after it."
  [base by-seq]
  (let [log (jlog/open (str base ".jlog"))
        prefix (jlog/recovery-prefix log)
        top (:seq (last prefix))]
    (val (first (rsubseq by-seq <= top)))))

(defn- image!
  "Restore the pristine files (the slabs reverted to the checkpoint when REVERT?), apply WOUND! (fn [base log recs]
   — recs the log's valid records, newest seq first), stale the killed slot's heartbeat (a reboot), reopen; answers
   [expected got committed-on?]."
  [base {:keys [by-seq files slot]} revert? wound!]
  (doseq [p files] (copy! (str p ".pristine") p))
  (when revert? (doseq [p files :when (.exists (File. (str p ".ckpt")))] (crash-restore! p ".ckpt")))
  (let [log (jlog/open (str base ".jlog"))
        recs (sort-by :seq #(compare %2 %1) (keep #(jlog/read-slot log %) (range (:n log))))]
    (wound! base log recs))
  (doseq [w [d/OFFSET_WS_HEARTBEAT_LO d/OFFSET_WS_HEARTBEAT_HI]]
    (write-range! (str base ".root") (#'a/worker-slot-offset slot w) (byte-array 4)))
  (let [want (expected base by-seq)
        dom (a/persistent-atom-domain base)
        m (a/lookup-or-create-mmap-atom! dom "t/m" {})
        got (try (into {} @m) (catch Exception e [:threw (.getMessage e)]))
        on? (try (swap! m assoc :on 1) (= (assoc got :on 1) (into {} @m)) (catch Exception _ false))]
    (halt! dom base)
    [want got on?]))

(defn- slot-off [log rec] (+ (:data-off log) (* jlog/SLOT_BYTES (bit-and (:seq rec) (:mask log)))))

(deftest torn-writes-recover-to-the-newest-commit-of-the-prefix
  (let [base (fresh-base)
        built (build! base 24)
        rnd (java.util.Random. 20261124)
        jpath (str base ".jlog")
        wounds (concat
                ;; the newest 1-3 records torn at every 512 B boundary (garbage past the tear), zeroed, bit-flipped
                (for [m [1 2 3]
                      kind (concat (for [t (range 0 1024 512)] [:tear t]) [[:zero] [:flip] [:flip]])]
                  [(str "newest " m " " kind)
                   (fn [_ log recs]
                     (doseq [rec (take m recs)]
                       (let [off (slot-off log rec)]
                         (case (first kind)
                           :tear (write-range! jpath (+ off (second kind))
                                               (let [b (byte-array (- jlog/SLOT_BYTES (second kind)))] (.nextBytes rnd b) b))
                           :zero (write-range! jpath off (byte-array jlog/SLOT_BYTES))
                           :flip (let [i (.nextInt rnd (+ jlog/REC_HEADER 32))
                                       b (read-range jpath (+ off i) 1)]
                                   (write-range! jpath (+ off i)
                                                 (byte-array [(unchecked-byte (bit-xor (aget b 0)
                                                                                       (bit-shift-left 1 (.nextInt rnd 8))))])))))))])
                ;; a hole inside the prefix: the commits after it were never acknowledged
                [["a hole five records back"
                  (fn [_ log recs] (write-range! jpath (slot-off log (nth recs 5)) (byte-array jlog/SLOT_BYTES)))]]
                ;; the root table reverted to the checkpoint's or zeroed (recovery installs the folded table)
                [["root table zeroed"
                  (fn [base _ _] (write-range! (str base ".root") d/ATOM_TABLE_START (byte-array jlog/TABLE_BYTES)))]]
                ;; the header's tail, watermark and anchor words and the slot states garbage
                [["header words and slot states garbage"
                  (fn [_ log _]
                    (write-range! jpath 32 (let [b (byte-array 8)] (.nextBytes rnd b) b))
                    (write-range! jpath 64 (byte-array (repeat 4 (byte 0x5A))))
                    (write-range! jpath 4096 (let [b (byte-array (* 8 (:n log)))] (.nextBytes rnd b) b)))]])
        results (vec (for [[label wound!] wounds
                           revert? [false true]]
                       (let [[want got on?] (image! base built revert? wound!)]
                         [(str label (when revert? ", slabs at the checkpoint")) want got on?])))]
    (testing "every image recovers to the newest commit of its prefix and commits on"
      (doseq [[label want got on?] results]
        (is (= want got) label)
        (is on? (str label ": the domain commits after recovery"))))
    (is (every? (fn [[_ want got]] (= want got)) results) (str (count results) " images"))))
