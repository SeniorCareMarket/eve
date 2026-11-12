(ns eve.coalesc-align-test
  "Class-6 (coalescing) blocks start 8-byte aligned on every host. Rounded to
   4 bytes, a block after one of an odd number of words started 4 bytes off,
   and the CLJS Float64Array view of a :float64 EveArray placed there began on
   its header's count — element 0 overwrote it (data-structures.md, Fixed
   issues). Measured before the fix: a 100K dataset pipeline failed 3 of 4 runs."
  (:require
   #?(:cljs [cljs.test :refer-macros [deftest testing is use-fixtures]]
      :clj  [clojure.test :refer [deftest testing is use-fixtures]])
   [eve.array :as arr]
   [eve.core :as e]
   [eve.deftype-proto.coalesc :as coalesc]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]
   [eve.region :as region]
   [eve.mem :as mem]))

(defn- in-a-domain-of-its-own
  "Construction law (2026-11-11): Eve data is constructed in a domain
   context, never through a process-heap fallback (alloc/current-sio throws
   outside one). Each test here builds in an explicit heap domain of its
   own (rrb-vector-test's fixture), dropped with the test. Collation fix
   (eve-final): with the pinfix P1 fix, eve-array's no-sio arities allocate
   in the running domain on every host, so these test bodies need the
   fixture on JVM/bb too, not only on ClojureScript."
  [f]
  (binding [d/*domain-sio* (alloc/heap-domain-sio!
                              {:capacities {0 (* 64 1024 1024)
                                            1 (* 16 1024 1024)
                                            2 (* 16 1024 1024)
                                            3 (* 192 1024 1024)
                                            4 (* 16 1024 1024)
                                            5 (* 16 1024 1024)}})]
    (f)))

(use-fixtures :each in-a-domain-of-its-own)

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
