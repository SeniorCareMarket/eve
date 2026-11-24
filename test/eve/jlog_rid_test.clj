(ns eve.jlog-rid-test
  "Request ids in the commit log (the OPFS tier's W8b): a COMMIT or NOREDO body's optional [client seq] trailer and an
   ANCHOR's request-id table round-trip, a body without one reads as before; and a journaled domain whose commits
   carry ids (eve.atom/*request-id*) answers each client's highest applied seq (applied-request-seq) — after the
   domain is closed (its checkpoint's anchor) and reopened."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.test-scratch :as scratch]
            [eve.atom :as a]
            [eve.jlog :as jlog])
  (:import [java.io File]))

(defn- delete-tree! [^File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(deftest bodies-carry-request-ids
  (let [delta {:slot 3 :old 1 :new 2 :hash 9}
        blk {:off 64 :flags 1 :pointer 0 :bytes (byte-array (range 20))}]
    (testing "a NOREDO body"
      (is (nil? (jlog/body-rid (jlog/encode-commit-body delta nil nil))))
      (let [b (jlog/encode-commit-body delta nil nil [77 5])]
        (is (= [77 5] (jlog/body-rid b)))
        (is (= {:slot 3 :old 1 :new 2 :hash 9 :blocks [] :pins []} (jlog/decode-commit-body b)))))
    (testing "a COMMIT body"
      (is (nil? (jlog/body-rid (jlog/encode-commit-body delta [blk] [{:off 128 :flags 0}]))))
      (let [b (jlog/encode-commit-body delta [blk] [{:off 128 :flags 0}] [-3 41])
            d (jlog/decode-commit-body b)]
        (is (= [-3 41] (jlog/body-rid b)))
        (is (= [64] (map :off (:blocks d))))
        (is (= (vec (range 20)) (vec (:bytes (first (:blocks d))))))
        (is (= [{:off 128 :flags 0}] (:pins d)))))
    (testing "an ANCHOR body"
      (let [table (byte-array jlog/TABLE_BYTES)]
        (is (= {} (jlog/anchor-rids {:body (jlog/encode-anchor-body table)})))
        (is (= {77 5 -3 41} (jlog/anchor-rids {:body (jlog/encode-anchor-body table {77 5 -3 41})})))
        (is (= {77 9 5 1}
               (jlog/fold-rids {:body (jlog/encode-anchor-body table {77 5})}
                               [{:kind jlog/K_COMMIT :body (jlog/encode-commit-body delta nil nil [77 9])}
                                {:kind jlog/K_NOREDO :body (jlog/encode-commit-body delta nil nil [5 1])}
                                {:kind jlog/K_COMMIT :body (jlog/encode-commit-body delta nil nil [77 7])}])))))))

(deftest a-domain-answers-applied-requests
  (let [dir (File. (scratch/unique-dir "jlog-rid"))
        base (str dir "/d")]
    (try
      (let [d (a/persistent-atom-domain base)
            c (a/persistent-atom {:id ::c :persistent base} 0)]
        (doseq [q [1 2 3]] (binding [a/*request-id* [77 q]] (swap! c inc)))
        (swap! c inc)
        (is (= 4 @c))
        (is (= 3 (a/applied-request-seq d 77)))
        (is (nil? (a/applied-request-seq d 78)))
        (a/close-atom-domain! d))
      (testing "reopened: the checkpoint's anchor carries the table"
        (let [d (a/persistent-atom-domain base)]
          (is (= 3 (a/applied-request-seq d 77)))
          (a/close-atom-domain! d)))
      (finally (delete-tree! dir)))))
