(ns eve.journal-open-test
  "A journaled persistent domain must open, write, close and re-open on
   every seat: the journal is the disk tier's shape, so an open that throws is a release
   defect on that seat. Regression for the bb seat (2026-11-08): under
   babashka, methods of the returned FileLock (sun.nio.ch.FileLockImpl)
   are unreachable — SCI falls back to a field lookup and
   (.release fl) throws NoSuchFieldException: release — and domain open
   itself runs recovery under with-journal-lock, so every journaled
   open died in the lock's release. The java journal adapter now
   releases the way the jvm-nio-lock region adapter already did:
   (.close fl), with a channel-of-its-own route where even close is
   refused."
  (:refer-clojure :exclude [swap! aget aset alength byte-array unchecked-byte])
  (:require [clojure.test :refer [deftest is]]
            [eve.host :refer [swap! aget aset alength byte-array unchecked-byte]]
            [eve.process :as proc]
            [eve.atom :as a]
            [eve.journal-file :as jf]
            #?(:cljs ["os" :as os])))

(defn- fresh-base []
  (str #?(:cljs (.tmpdir os) :lpy "/tmp" :clje "/tmp" :jank "/tmp" :cljr (System.IO.Path/GetTempPath)
          :default (System/getProperty "java.io.tmpdir"))
       "/eve-journal-open-" (rand-int 1000000000) "/d"))

(deftest journaled-domain-opens-writes-and-reopens
  (let [base (fresh-base)
        dom (a/persistent-atom-domain base)
        x (a/lookup-or-create-mmap-atom! dom "t/x" 0)]
    (is (proc/file-exists? (str base ".journal"))
        "the default disk domain is journaled — this test exercises the journal path")
    (swap! x + 41)
    (swap! x inc)
    (a/close-atom-domain! dom)
    (let [dom2 (a/persistent-atom-domain base)
          x2 (a/lookup-or-create-mmap-atom! dom2 "t/x" 0)]
      (is (= 42 @x2) "the re-open recovers what the journal committed")
      (swap! x2 inc)
      (is (= 43 @x2))
      (a/close-atom-domain! dom2))))

(deftest journal-lock-releases
  ;; the adapter fn itself: two sequential holds on the same channel —
  ;; the second hold can only take the file lock if the first released
  ;; it (a same-process second lock would throw
  ;; OverlappingFileLockException).
  (let [base (fresh-base)
        _ (proc/ensure-parent-dir! (str base ".journal"))
        fc (jf/open-journal (str base ".journal"))]
    (is (= :one (jf/with-journal-lock fc (fn [] :one))))
    (is (= :two (jf/with-journal-lock fc (fn [] :two))))
    (jf/journal-close! fc)))

(defn- u8-array
  "A byte array whose unsigned values are XS."
  [xs]
  (let [a (byte-array (count xs))]
    (dotimes [i (count xs)]
      (aset a i (unchecked-byte (nth xs i))))
    a))

(defn- record-u8s
  "The unsigned bytes of a journal-record-of result (a byte array on
   jolt and node; a flipped ByteBuffer on the JVM)."
  [rec]
  (let [arr #?(:clj (if (instance? java.nio.ByteBuffer rec)
                      (let [bb (.duplicate ^java.nio.ByteBuffer rec)
                            a (byte-array (.remaining bb))]
                        (.get bb a)
                        a)
                      rec)
               :default rec)]
    (mapv #(bit-and (aget arr %) 0xFF) (range (alength arr)))))

(defn- be-u32 [bytes]
  (reduce (fn [a b] (+ (* a 256) b)) 0 bytes))

(deftest journal-record-crc32-matches-ieee
  ;; Goldens are the IEEE CRC-32 (java.util.zip.CRC32 / zlib) of the
  ;; record's epoch + table-len + table bytes, stored as a big-endian
  ;; i32. journal-record-of on every host must emit these exact bytes.
  (doseq [{:keys [label epoch table crc]}
          [{:label "empty" :epoch 0 :table [] :crc 0x6522DF69}
           {:label "str9" :epoch 1 :table (map int "123456789") :crc 0x85081689}
           {:label "range256" :epoch 42 :table (range 256) :crc 0xD9AD3436}
           {:label "zeros2048" :epoch 7 :table (repeat 2048 0) :crc 0xA76541C8}
           {:label "ramp2048" :epoch 99 :table (map #(bit-and % 255) (range 2048)) :crc 0xDE438E6C}
           {:label "neg-epoch" :epoch -1 :table (map int "ab") :crc 0x23217103}]]
    (let [tbl (u8-array table)
          rec (jf/journal-record-of epoch tbl)
          got (record-u8s rec)
          tlen (count table)]
      (is (= (+ 20 tlen) (count got)) label)
      (is (= 0x45564A31 (be-u32 (subvec got 0 4))) (str label " magic"))
      (is (= (+ 12 tlen) (be-u32 (subvec got 4 8))) (str label " len"))
      (is (= (bit-and epoch 0xFFFFFFFF) (be-u32 (subvec got 8 12))) (str label " epoch"))
      (is (= tlen (be-u32 (subvec got 12 16))) (str label " tlen"))
      (is (= (vec table) (subvec got 16 (+ 16 tlen))) (str label " table copy"))
      (is (= crc (be-u32 (subvec got (+ 16 tlen)))) (str label " crc32-of vs JVM IEEE")))))
