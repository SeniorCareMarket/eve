(ns eve.array-hosts-test
  "EveArray on every host, as written: element access on the array a fresh
   eve-array is and on the one an atom answers, each subtype's full range
   through an atom (its subtype too), and an array as an atom's root. Runs on
   the JVM, CLJS, nbb and bb."
  (:require #?(:cljs [cljs.test :refer [deftest testing is use-fixtures]]
               :clj  [clojure.test :refer [deftest testing is use-fixtures]])
            [eve.core :as e]
            [eve.array :as arr]
            [eve.atom :as atom]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]))

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

(def ^:private ranges
  {:int8    [-128 127]
   :uint8   [0 255]
   :int16   [-32768 32767]
   :uint16  [0 65535]
   :int32   [-2147483648 2147483647]
   :uint32  [0 4294967295]
   :float32 [-1.5 2.25]
   :float64 [-1.0e300 1.0e300]})

(deftest fresh-array-access
  (doseq [[t [lo hi]] ranges]
    (testing (str t)
      (let [a (arr/eve-array t [lo hi])]
        (is (= [lo hi] [(arr/aget a 0) (arr/aget a 1)]) "aget")
        (is (= [lo hi] [(e/nth a 0) (e/nth a 1)]) "nth")
        (arr/aset! a 0 hi)
        (is (= hi (e/nth a 0)) "aset!")))))

(deftest ranges-through-an-atom
  (doseq [[t [lo hi]] ranges]
    (testing (str t)
      (let [a (atom/atom nil)
            col (:col (swap! a (fn [_] {:col (arr/eve-array t [lo hi])})))]
        (is (= t (arr/array-type col)) "the subtype survives the atom")
        (is (= [lo hi] [(e/nth col 0) (e/nth col 1)]) "the values survive the atom")
        (is (= [lo hi] [(arr/aget col 0) (arr/aget col 1)]) "aget on the atom's array")))))

(deftest array-as-root
  (let [a (atom/atom nil)
        r (swap! a (fn [_] (arr/eve-array :int32 [1 2 3])))]
    (is (= [1 2 3] (mapv #(e/nth r %) (range (e/count r)))))
    (swap! a (fn [s] (arr/aset! s 0 7) s))
    (is (= 7 (arr/aget (swap! a identity) 0)) "aset! on the atom's array, inside swap!")))
