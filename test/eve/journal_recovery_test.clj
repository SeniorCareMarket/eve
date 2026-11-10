(ns eve.journal-recovery-test
  "Journal recovery wounds — the F2 durability battery's four shapes
   (lane-m/cli-shootout-f2-jolt-journal, logs/durability1.log, run
   cross-process on jolt), replayed here on the JVM seat against
   eve.atom's shared recovery code, journal-recover!. The wounds are
   byte-level, applied to a closed (or halt-simulated) domain's files;
   the domain is then re-opened and the atom read back through the
   same lookup-or-create path the battery used.

   W1 stale root: the root epoch is wound back -> recovery installs
     the journal's newest record. (The pre-existing repair.)
   W2 root atom-table corruption at EQUAL epoch: the .root atom-table
     range is XORed, the epoch left intact, and the journal's newest
     record is CRC-valid at the SAME epoch. This was silent atom
     loss: recovery installed only on epoch mismatch, the corrupted
     table missed the lookup, and lookup-or-create minted a fresh
     atom over the lost one — the read returned the fresh atom's
     initial value with no error anywhere.
   W3 torn journal tail: the newest record is truncated -> the
     newest VALID record is installed, one epoch back.
   W4 bad journal magic: the journal is rejected wholesale (dropped
     by the scan) and the intact root serves."
  (:require [clojure.test :refer [deftest is]]
            [eve.atom :as a]
            [eve.process :as proc]
            [eve.deftype-proto.data :as d])
  (:import [java.io File RandomAccessFile]))

(defn- fresh-base []
  (str (System/getProperty "java.io.tmpdir")
       "/eve-journal-recovery-" (rand-int 1000000000) "/d"))

(defn- xor-range!
  "XOR every byte of PATH in [start, end) with 0xFF — the battery's
   corruption shape."
  [path start end]
  (let [raf (RandomAccessFile. (File. path) "rw")]
    (try
      (loop [i (long start)]
        (when (< i end)
          (.seek raf i)
          (let [b (.read raf)]
            (.seek raf i)
            (.write raf (bit-xor b 0xFF)))
          (recur (inc i))))
      (finally (.close raf)))))

(defn- read-i32 [path off]
  (let [raf (RandomAccessFile. (File. path) "r")]
    (try (.seek raf (long off)) (.readInt raf) (finally (.close raf)))))

(defn- write-i32! [path off v]
  (let [raf (RandomAccessFile. (File. path) "rw")]
    (try (.seek raf (long off)) (.writeInt raf (int v)) (finally (.close raf)))))

(defn- truncate! [path len]
  (let [raf (RandomAccessFile. (File. path) "rw")]
    (try (.setLength raf (long len)) (finally (.close raf)))))

(defn- file-len [path] (.length (File. path)))

(defn- build-domain!
  "Create a journaled domain at BASE, drive t/x to 42 in three
   commits, close cleanly (the close-time checkpoint anchors the
   journal to the newest single record, root and journal at the same
   epoch). Returns the root epoch at close."
  [base]
  (let [dom (a/persistent-atom-domain base)
        x   (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (swap! x + 40)
    (swap! x inc)
    (swap! x inc)
    (a/close-atom-domain! dom)
    (read-i32 (str base ".root") d/ROOT_EPOCH_OFFSET)))

(defn- halt-domain!
  "Simulate a process halt: stop the heartbeat and release the worker
   slot WITHOUT release-domain!'s close-time checkpoint, so the
   journal keeps every record it accumulated and the root keeps the
   newest epoch — the state a killed process leaves on disk."
  [dom base]
  (let [ds (#'a/atom-domain-state dom)]
    (proc/stop-timer! (:heartbeat ds))
    (#'a/mmap-release-slot! (:root-r ds) (:slot-idx ds))
    (swap! @#'a/domain-cache dissoc base)))

(deftest w2-root-table-corruption-at-equal-epoch
  (let [base  (fresh-base)
        epoch (build-domain! base)]
    (is (pos? epoch) "the closed domain carries a real epoch")
    ;; the wound: XOR the .root atom table, epoch untouched — the
    ;; battery's exact range (ATOM_TABLE_START .. +256 slots x 8 B).
    (xor-range! (str base ".root")
                d/ATOM_TABLE_START
                (+ d/ATOM_TABLE_START (* d/MAX_ATOM_SLOTS d/ATOM_SLOT_SIZE)))
    (let [dom (a/persistent-atom-domain base)
          x   (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 42 @x)
          "the CRC-valid journal record at the same epoch must repair the corrupted root table — the stored value survives")
      (a/close-atom-domain! dom))))

(deftest w1-stale-root-epoch-is-repaired
  (let [base  (fresh-base)
        epoch (build-domain! base)]
    ;; the wound: wind the root epoch back; the journal's newest
    ;; record is newer and must be installed.
    (write-i32! (str base ".root") d/ROOT_EPOCH_OFFSET (- epoch 2))
    (let [dom (a/persistent-atom-domain base)
          x   (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 42 @x) "the journal's newest record is installed over the stale root")
      (a/close-atom-domain! dom))))

(deftest w3-torn-journal-tail-falls-back-one-epoch
  (let [base (fresh-base)
        dom  (a/persistent-atom-domain base)
        x    (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (swap! x + 41)   ;; 41, journaled
    (swap! x inc)    ;; 42, journaled as the newest record
    (halt-domain! dom base)
    ;; the wound: tear the newest journal record (the battery cut
    ;; 1,000 B off the journal's tail).
    (truncate! (str base ".journal") (- (file-len (str base ".journal")) 1000))
    (let [dom2 (a/persistent-atom-domain base)
          x2   (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 41 @x2)
          "the torn newest record is discarded; the newest valid record, one epoch back, is installed")
      (a/close-atom-domain! dom2))))

(deftest w4-bad-journal-magic-serves-the-root
  (let [base (fresh-base)
        _    (build-domain! base)]
    ;; the wound: one magic byte of the journal's only record.
    (xor-range! (str base ".journal") 0 1)
    (let [dom (a/persistent-atom-domain base)
          x   (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (is (= 42 @x) "a journal the scan rejects is dropped whole; the intact root serves")
      (a/close-atom-domain! dom))))
