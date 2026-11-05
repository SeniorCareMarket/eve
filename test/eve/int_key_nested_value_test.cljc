(ns eve.int-key-nested-value-test
  "A number key whose value holds a nested collection (2026-11-05 ET).

   On ClojureScript's codec a number's key bytes were a view of a scratch buffer
   (eve.codec's scratch A) that the encode of its value reused: a nested vector's
   or set's numbers are written there, so (assoc m 1 {:z [1 2]}) stored its key
   as 2 — the last number the value wrote — and (get m 1) found nothing. A
   scalar value, or a keyword or string key, was untouched. encode-key and
   encode-val now hand back bytes of the caller's own."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.codec :as codec]
            [eve.core :as ecore]
            [eve.deftype-proto.alloc :as alloc]
            [eve.map :as em]))

(def ^:private cases
  "Number keys over values that write numbers of their own when encoded."
  [{1 {:z [1 2]}}
   {1 [1 2]}
   {7 #{3 9}}
   {1 {:z 5}}
   {1 5}
   {1.5 [1 2]}
   {-3 [[4 5] 6]}
   {:a {:z [1 2]}}
   {"s" {:z [1 2]}}
   {1 {:z [1 2]} 2 {:z [3 4]} 3 [5 6]}])

(deftest number-key-nested-value-reads-back
  (doseq [m cases]
    (testing (pr-str m)
      (let [e (em/into-hash-map (alloc/current-sio) m)]
        (is (= m (into {} e)))
        (doseq [[k v] m]
          (is (= v (get e k))))))))

(deftest number-key-assoc-reads-back
  (let [e (ecore/assoc (em/empty-hash-map (alloc/current-sio)) 1 {:z [1 2]})]
    (is (= {1 {:z [1 2]}} (into {} e)))
    (is (= {:z [1 2]} (get e 1)))
    (is (nil? (get e 2)))))

(defn- byte-seq [bs] (vec (map #(bit-and % 0xFF) bs)))

(deftest key-bytes-are-the-callers-own
  (testing "a key's bytes are unchanged by the encode of a value that follows"
    (let [kb (codec/encode-key 1)
          before (byte-seq kb)]
      (codec/encode-val (alloc/current-sio) {:z [7 8]})
      (codec/encode-key 9)
      (is (= before (byte-seq kb)))))
  (testing "a value's bytes are unchanged by the encode of the next pair's value"
    (let [vb (codec/encode-val (alloc/current-sio) 5)
          before (byte-seq vb)]
      (codec/encode-val (alloc/current-sio) 6)
      (is (= before (byte-seq vb))))))
