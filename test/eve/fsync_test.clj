(ns eve.fsync-test
  "eve.fsync (option 1, doc/jlog-redo-spec.md §9): a set of files forced with one device flush where that is safe —
   a file is trusted (written back by sync_file_range, made durable by another file's fdatasync) only when it is
   hole-free on ext4/xfs and its inode and size are those this process last fdatasync'd hole-free; any other file gets
   its own fdatasync. And a new domain's whole-domain force, once its files are trusted, is one flush."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [eve.test-scratch :as scratch]
            [eve.atom :as a]
            [eve.fsync :as fsync])
  (:import [java.io File RandomAccessFile]))

(def ^:private scratch-dirs (atom []))

(defn- tmp-dir []
  (let [d (File. (scratch/unique-dir "fsync"))]
    (.mkdirs d)
    (swap! scratch-dirs conj d)
    d))

(use-fixtures :once (fn [f] (try (f) (finally (doseq [d @scratch-dirs] (scratch/delete-tree! (.getPath ^File d)))))))

(defn- write-file! [^File f n]
  (let [raf (RandomAccessFile. f "rw")]
    (try (.write raf (byte-array n)) (finally (.close raf)))))

(defn- sparse-file! [^File f n]
  (let [raf (RandomAccessFile. f "rw")]
    (try (.setLength raf (long n)) (finally (.close raf)))))

(defn- touch! [^File f off]
  (let [raf (RandomAccessFile. f "rw")]
    (try (.seek raf (long off)) (.write raf (byte-array [1 2 3])) (finally (.close raf)))))

(deftest one-flush-for-trusted-files
  (if-not (fsync/available?)
    (is true "no file syscalls on this host: callers force region by region")
    (let [d (tmp-dir)
          fs (mapv #(File. d (str "f" %)) (range 4))
          paths (mapv #(.getPath ^File %) fs)]
      (doseq [f fs] (write-file! f 65536))
      (testing "files never fdatasync'd by this process are forced one by one, then trusted"
        (is (= 4 (fsync/sync-files! paths))))
      (doseq [f fs] (touch! f 4096))
      (testing "hole-free, same inode and size: written back, one flush for all four"
        (is (= 1 (fsync/sync-files! paths))))
      (testing "a file that grew is forced on its own (its new blocks), the others still share one flush"
        (write-file! (first fs) 8192)            ;; RandomAccessFile write at 0: same size
        (let [raf (RandomAccessFile. ^File (second fs) "rw")]
          (try (.seek raf 65536) (.write raf (byte-array 4096)) (finally (.close raf))))
        (is (= 2 (fsync/sync-files! paths)))
        (is (= 1 (fsync/sync-files! paths)) "and is trusted again once forced hole-free"))
      (testing "a file with holes is never trusted"
        (let [sp (File. d "sparse")]
          (sparse-file! sp (* 1024 1024))
          (touch! sp 0)
          (is (= 2 (fsync/sync-files! [(.getPath sp) (first paths)])))
          (touch! sp 8192)
          (is (= 2 (fsync/sync-files! [(.getPath sp) (first paths)])) "still forced on its own")))
      (testing "a missing file is skipped"
        (is (= 1 (fsync/sync-files! (conj paths (.getPath (File. d "absent"))))))))))

(deftest a-new-domains-checkpoint-is-one-flush
  (if-not (fsync/available?)
    (is true "no file syscalls on this host")
    (let [calls (atom [])
          base (str (.getPath ^File (tmp-dir)) "/d")
          dom (a/persistent-atom-domain base)
          x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
      (with-redefs [fsync/sync-files! (let [f fsync/sync-files!] (fn [ps] (let [r (f ps)] (swap! calls conj r) r)))]
        (swap! x inc)
        (a/sync! x)                              ;; the creator's files: forced one by one, then trusted
        (swap! x inc)
        (reset! calls [])
        (a/sync! x)
        (is (= [1] @calls) "the whole-domain force of a checkpoint: one device flush"))
      (a/close-atom-domain! dom))))
