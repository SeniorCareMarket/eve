(ns eve.coalesc-align-test
  "Class-6 (coalescing) blocks start 8-byte aligned on every host. Rounded to
   4 bytes, a block after one of an odd number of words started 4 bytes off,
   and the CLJS Float64Array view of a :float64 EveArray placed there began on
   its header's count — element 0 overwrote it (data-structures.md, Fixed
   issues). Measured before the fix: a 100K dataset pipeline failed 3 of 4 runs."
  (:require
   #?(:cljs [cljs.test :refer-macros [deftest testing is]]
      :clj  [clojure.test :refer [deftest testing is]])
   [eve.array :as arr]
   [eve.core :as e]
   [eve.deftype-proto.coalesc :as coalesc]
   [eve.region :as region]
   [eve.mem :as mem]))

(deftest class-6-blocks-are-8-aligned
  (let [data-size (* 64 1024)
        layout    (coalesc/coalesc-layout data-size coalesc/MAX_DESCRIPTORS)
        r         (region/make-heap-region (:total-bytes layout))]
    (coalesc/init-coalesc-region! r data-size coalesc/MAX_DESCRIPTORS)
    ;; sizes of an odd number of words, as an int32 array of odd length makes
    (doseq [n [1036 1028 2052 1500 1025 4100 1036]]
      (let [off (coalesc/coalesc-alloc! r n)]
        (is (zero? (mod off 8)) (str n " bytes at offset " off))))))

(deftest float64-after-odd-int32-keeps-its-count
  (testing "an odd-length int32 array, then a float64 array big enough for class 6"
    (dotimes [_ 3]
      (let [ints (arr/eve-array :int32 (range 257))
            fl   (arr/eve-array :float64 (map double (range 300)))]
        (is (= 257 (e/count ints)))
        (is (= 300 (e/count fl)))
        (is (= 0.0 (e/nth fl 0)))
        (is (= 299.0 (e/nth fl 299)))))))
