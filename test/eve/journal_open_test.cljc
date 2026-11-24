(ns eve.journal-open-test
  "A journaled persistent domain must open, write, close and re-open on
   every seat: the journal is the disk tier's shape, so an open that throws is a release
   defect on that seat. The journal is <base>.jlog (eve.jlog: the mmapped commit
   log, one implementation for every host, eve-mmap-journal-plan §4); a seat whose
   region cannot force its pages refuses the open instead of committing undurably.
   (The bb regression this test was written for, 2026-11-08 — a FileLock whose
   release bb could not reach, in the java journal adapter — went with that
   adapter: the jlog takes no file lock.)

   Also here, every seat's arm of eve.crc32 against the IEEE CRC-32 goldens the
   EVJ1 journal records carried (java.util.zip.CRC32 / zlib), and a jlog record's
   bytes against the golden every host must emit."
  (:refer-clojure :exclude [swap! aget aset alength byte-array unchecked-byte])
  (:require [clojure.test :refer [deftest is]]
            [eve.host :refer [swap! aget aset alength byte-array unchecked-byte]]
            [eve.process :as proc]
            [eve.atom :as a]
            [eve.crc32 :as crc]
            [eve.jlog :as jlog]
            #?(:cljs ["os" :as os])))

(defn- fresh-base []
  (str #?(:cljs (.tmpdir os) :lpy "/tmp" :clje "/tmp" :jank "/tmp" :cljr (System.IO.Path/GetTempPath)
          :default (System/getProperty "java.io.tmpdir"))
       "/eve-journal-open-" (rand-int 1000000000) "/d"))

(deftest journaled-domain-opens-writes-and-reopens
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (is (proc/file-exists? (str base ".jlog"))
        "the default disk domain is journaled on the jlog — this test exercises the journal path")
    (is (not (proc/file-exists? (str base ".journal"))) "no legacy journal is written")
    (swap! x + 41)
    (swap! x inc)
    (a/close-atom-domain! dom)
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 42 @x2) "the re-open recovers what the journal committed")
      (swap! x2 inc)
      (is (= 43 @x2))
      (a/close-atom-domain! dom2))))

(defn- u8-array
  "A byte array whose unsigned values are XS."
  [xs]
  (let [a (byte-array (count xs))]
    (dotimes [i (count xs)]
      (aset a i (unchecked-byte (nth xs i))))
    a))

(defn- be-bytes [v] [(bit-and (bit-shift-right v 24) 0xFF) (bit-and (bit-shift-right v 16) 0xFF)
                     (bit-and (bit-shift-right v 8) 0xFF) (bit-and v 0xFF)])

(defn- u32 [v] (bit-and v 0xFFFFFFFF))

(deftest crc32-matches-ieee
  ;; The goldens are the IEEE CRC-32 (java.util.zip.CRC32 / zlib) of an EVJ1
  ;; record's epoch + table-len + table bytes (big-endian words): the host's
  ;; arm and the portable walk must both answer them.
  (doseq [{:keys [label epoch table crc]}
          [{:label "empty" :epoch 0 :table [] :crc 0x6522DF69}
           {:label "str9" :epoch 1 :table (map int "123456789") :crc 0x85081689}
           {:label "range256" :epoch 42 :table (range 256) :crc 0xD9AD3436}
           {:label "zeros2048" :epoch 7 :table (repeat 2048 0) :crc 0xA76541C8}
           {:label "ramp2048" :epoch 99 :table (map #(bit-and % 255) (range 2048)) :crc 0xDE438E6C}
           {:label "neg-epoch" :epoch -1 :table (map int "ab") :crc 0x23217103}]]
    (let [b (u8-array (concat (be-bytes epoch) (be-bytes (count table)) table))]
      (is (= crc (u32 (crc/crc32 b 0 (alength b)))) (str label " host arm"))
      (is (= crc (u32 (crc/crc32-portable b 0 (alength b)))) (str label " portable"))))
  (let [b (u8-array (map int "xx123456789"))]
    (is (= 0xCBF43926 (u32 (crc/crc32 b 2 9))) "the check value, at an offset")))

(deftest jlog-record-bytes-are-the-golden
  ;; one record every host encodes byte for byte the same: an ANCHOR over a 2048-byte table
  (let [table (u8-array (map #(bit-and (* % 7) 0xFF) (range jlog/TABLE_BYTES)))
        rec (jlog/encode-record 7 jlog/K_ANCHOR 12345 0x01020304 3 table)
        head (mapv #(bit-and (aget rec %) 0xFF) (range 40))]
    (is (= (+ jlog/REC_HEADER jlog/TABLE_BYTES 4) (alength rec)))
    (is (= [0x33 0x52 0x56 0x45 7 0 0 0 5 0 0 0 0x39 0x30 0 0 4 3 2 1 3 0 0 0 0 8 0 0 0 0 0 0
            0 7 14 21 28 35 42 49]
           head))
    (is (= 1911069110 (u32 (crc/crc32 rec 0 (+ jlog/REC_HEADER jlog/TABLE_BYTES)))))))
