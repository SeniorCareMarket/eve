(ns eve.host-bytes-test
  (:refer-clojure :exclude [byte-array aget alength])
  (:require [clojure.test :refer [deftest is]]
            [eve.host :refer [byte-array aget alength]]))

(deftest primitive-byte-storage
  (let [a (byte-array 3) b (byte-array [-128 -1 0 127])
        c (byte-array 3 (byte -1)) d (byte-array 4 [1 2])]
    (is (= 3 (alength a)))
    (is (= [0 0 0] (mapv #(aget a %) (range 3))))
    (is (= [-128 -1 0 127] (mapv #(aget b %) (range 4))))
    (is (= [-1 -1 -1] (mapv #(aget c %) (range 3))))
    (is (= [1 2 0 0] (mapv #(aget d %) (range 4))))))
