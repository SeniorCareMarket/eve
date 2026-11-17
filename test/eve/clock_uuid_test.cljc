(ns eve.clock-uuid-test
  (:require #?(:cljs [cljs.test :refer-macros [deftest is]]
               :default [clojure.test :refer [deftest is]])
            [eve.clock :as clock] [eve.uuid :as uuid]))
(deftest clock-contract
  (let [a (clock/current-time-millis) b (clock/current-time-millis)]
    (is (integer? a)) (is (> a 1700000000000)) (is (<= a b))))
(deftest uuid-contract
  (doseq [s ["00000000-0000-0000-0000-000000000000"
             "ffffffff-ffff-ffff-ffff-ffffffffffff"
             "80000000-0000-0000-7fff-ffffffffffff"
             "12345678-9abc-def0-fedc-ba9876543210"]]
    (let [u (uuid/from-string s)]
      (is (= s (str u)))
      (is (= u (apply uuid/from-longs (uuid/to-words u))))
      (is (= u (uuid/from-longs (uuid/most-significant-bits u) (uuid/least-significant-bits u))))))
  (is (= "ffffffff-ffff-ffff-8000-000000000000" (str (uuid/from-longs -1 "-9223372036854775808"))))
  (is (= "7fffffff-ffff-ffff-0000-000000000001" (str (uuid/from-longs "9223372036854775807" 1))))
  (is (= "ffffffff-ffff-ffff-ffff-ffffffffffff" (str (uuid/from-string "FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF"))))
  (doseq [s ["garbage" "0-0-0-0-0" "ffffffff-ffff-ffff-ffff-ffffffffffff junk"]]
    (is (try (uuid/from-string s) false (catch #?(:cljs :default :clje _ :jank jank.runtime.object_ref :default Exception) _ true))))
  (doseq [s ["9223372036854775808" "-9223372036854775809"]]
    (is (try (uuid/from-longs s 0) false (catch #?(:cljs :default :clje _ :jank jank.runtime.object_ref :default Exception) _ true)))))
(deftest squuid-contract
  (doseq [ms [0 999 1000 1700000000123 2147483648000 4294967295999 4294967296000]]
    (let [u (uuid/squuid ms) s (str u)]
      (is (= (* 1000 (mod (quot ms 1000) 4294967296)) (uuid/squuid-time-millis u)))
      (is (= "4" (subs s 14 15)))
      (is (contains? #{"8" "9" "a" "b"} (subs s 19 20)))))
  (is (not= (uuid/random-uuid) (uuid/random-uuid)))
  (let [a (clock/current-time-millis) u (uuid/squuid) b (clock/current-time-millis)]
    (is (<= (* 1000 (quot a 1000)) (uuid/squuid-time-millis u) (* 1000 (quot b 1000))))))
