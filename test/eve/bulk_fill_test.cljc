(ns eve.bulk-fill-test
  (:require #?(:cljs [cljs.test :refer [deftest is]]
               :clj [clojure.test :refer [deftest is]])
            [eve.mem :as mem]))

(deftest default-fill-pattern-and-chunk-boundaries
  ;; No native IFillRegion implementation: exercise the portable default.
  (doseq [n [0 1 65536 65537 131073]
          value [0 -1 305419896 -2147483648]]
    (let [spans (atom [])
          expected (mapv #(bit-and (unsigned-bit-shift-right value %) 255)
                         [0 8 16 24])
          region (reify mem/IMemRegion
                   (-write-bytes! [_ offset bs]
                     (let [values (vec bs)]
                       (is (every? true?
                                   (map-indexed
                                    (fn [i b] (= (expected (mod i 4)) (bit-and b 255)))
                                    values)))
                       (swap! spans conj [offset (count values)]))))]
      (is (nil? (mem/-fill-i32! region 12 n value)))
      (is (= (* 4 n) (reduce + 0 (map second @spans))))
      (is (every? #(<= (second %) (* 4 65536)) @spans))
      (is (= (mapv #(+ 12 (* 4 %)) (range 0 n 65536))
             (mapv first @spans))))))
