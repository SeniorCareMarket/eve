(ns eve.jvm-legacy-index-test
  "Maps and sets a JVM or bb build wrote before FORMAT-DECISIONS §6 stay
   readable and writable. That build indexed a node at shift 30 by bits 30-34
   of the hash's sign-extended long, so a negative-hash key sits 28 slots
   above the 32-bit pattern's slot (30/31 for 2/3). The int keys 371 and
   53312 (hashes 642791526 and -430950298, equal in their low 30 bits) meet in
   such a node; each value below is written with the old :clj arm in force and
   read, updated and removed with today's."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.deftype-proto.alloc :as alloc]
            [eve.hamt-util :as hu]
            [eve.map :as eve-map]
            [eve.codec :as codec]
            [eve.set :as eve-set]))

(defn- pre-fix-mask-hash
  "eve.hamt-util/mask-hash's :clj arm before §6."
  [kh shift]
  (bit-and (unsigned-bit-shift-right kh shift) 0x1f))

(defn- legacy-map [sio m]
  (eve-map/jvm-eve-hash-map-from-offset
   sio (with-redefs [hu/mask-hash pre-fix-mask-hash] (eve-map/jvm-write-map! sio m))))

(defn- legacy-set [sio s]
  (eve-set/jvm-eve-hash-set-from-offset
   sio (with-redefs [hu/mask-hash pre-fix-mask-hash]
         (eve-set/jvm-write-set! sio (partial codec/value+sio->eve-bytes sio) s))))

(deftest the-keys-meet-at-shift-30
  (is (= [642791526 -430950298]
         (mapv #(hu/portable-hash-bytes (codec/value->eve-bytes %)) [371 53312])))
  (is (= [3 31] [(hu/mask-hash -430950298 30) (pre-fix-mask-hash -430950298 30)])))

(deftest legacy-map-reads
  (let [m (legacy-map (alloc/make-jvm-heap-slab-ctx {}) {371 :a 53312 :b})]
    (is (= :b (get m 53312)))
    (is (= :a (get m 371)))
    (is (contains? m 53312))
    (is (= [53312 :b] (vec (find m 53312))))
    (is (= 2 (count m)))
    (is (= {371 :a 53312 :b} (into {} m)))
    (is (= m {371 :a 53312 :b}))))

(deftest legacy-map-writes
  (let [m (legacy-map (alloc/make-jvm-heap-slab-ctx {}) {371 :a 53312 :b})]
    (testing "assoc replaces the key where it was written, never adds it twice"
      (let [m2 (assoc m 53312 :c)]
        (is (= 2 (count m2)))
        (is (= :c (get m2 53312)))
        (is (= {371 :a 53312 :c} (into {} m2)))))
    (testing "dissoc removes it"
      (let [m3 (dissoc m 53312)]
        (is (= 1 (count m3)))
        (is (nil? (get m3 53312)))
        (is (= {371 :a} (into {} m3)))))))

(deftest legacy-set-reads-and-writes
  (let [s (legacy-set (alloc/make-jvm-heap-slab-ctx {}) #{371 53312})]
    (is (contains? s 53312))
    (is (= 53312 (get s 53312)))
    (is (= 2 (count s)))
    (is (= 2 (count (conj s 53312))) "conj of a present member adds nothing")
    (let [s2 (disj s 53312)]
      (is (= 1 (count s2)))
      (is (not (contains? s2 53312)))
      (is (= #{371} (into #{} s2))))))
