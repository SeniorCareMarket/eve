(ns eve.bin-lines-test
  "eve.bin/lines — THE LINE VIEW: a binary's lines as views of its own bytes, terminated as
   java.io.BufferedReader's readLine terminates them."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.bin :as bin]
            [eve.encoding :as enc])
  #?(:clj (:import [java.io BufferedReader StringReader])))

(defn- b-of [s] (bin/of-bytes (enc/utf8-bytes s)))
(defn- text [b] (let [bs (bin/->bytes b)] (enc/utf8-string bs 0 (count b))))

;; [text expected-lines] — the expectations are java.io.BufferedReader's readLine answers (checked on the JVM below)
(def cases
  [["" []] ["\n" [""]] ["\n\n" ["" ""]] ["a" ["a"]] ["a\n" ["a"]] ["a\nb" ["a" "b"]] ["a\nb\n" ["a" "b"]]
   ["a\n\nb" ["a" "" "b"]] ["a\r\nb\r\n" ["a" "b"]] ["a\rb" ["a" "b"]] ["a\r\rb" ["a" "" "b"]] ["a\r" ["a"]]
   ["\r\n" [""]] ["\r\r\n" ["" ""]] ["x\r\ny\rz\n" ["x" "y" "z"]] ["é漢\n😀" ["é漢" "😀"]]
   ["line one\nline two\nline three" ["line one" "line two" "line three"]]])

#?(:clj
   (deftest expectations-are-buffered-readers
     (doseq [[s want] cases]
       (is (= want (vec (line-seq (BufferedReader. (StringReader. s))))) (pr-str s)))))

(deftest lines-match-buffered-reader
  (doseq [[s want] cases]
    (testing (pr-str s)
      (is (= want (mapv text (seq (bin/lines (b-of s))))) "seq face")
      (is (= want (mapv text (reduce conj [] (bin/lines (b-of s))))) "reduce face")
      (is (= want (into [] (map text) (bin/lines (b-of s)))) "transduce face"))))

(deftest lines-are-views
  (let [b (b-of "alpha\nbeta\r\ngamma")
        ls (vec (seq (bin/lines b)))]
    (is (every? bin/bin? ls))
    (is (= [5 4 5] (mapv count ls)))
    (is (= (bin/slice b 6 10) (nth ls 1)) "a line equals the slice of b it views")
    (testing "a slice's lines view the slice"
      (is (= ["beta" "gamma"] (mapv text (seq (bin/lines (bin/slice b 6)))))))))

(deftest reduce-arities-and-reduced
  (is (= 0 (reduce (fn ([] 0) ([a l] (+ a (count l)))) (bin/lines (b-of "")))) "no line, no init: (f)")
  (is (= "a" (text (reduce (fn [a l] a) (bin/lines (b-of "a"))))) "one line, no init: the line itself")
  (is (= 9 (reduce (fn [a l] (+ (if (number? a) a (count a)) (count l))) (bin/lines (b-of "abc\nde\nfour")))))
  (is (= ["a" "b"] (mapv text (reduce (fn [acc l] (if (= "c" (text l)) (reduced acc) (conj acc l)))
                                      [] (bin/lines (b-of "a\nb\nc\nd")))))
      "a reduced answer ends the walk")
  (is (= ["b"] (mapv text (transduce (comp (filter #(= 1 (count %))) (drop 1) (take 1)) conj []
                                     (bin/lines (b-of "a\nb\ncc\nd")))))))

(deftest chunked-seq-face
  (let [s (apply str (map #(str % "\n") (range 100)))
        sq (seq (bin/lines (b-of s)))]
    (is (chunked-seq? sq))
    (is (= 32 (count (chunk-first sq))))
    (is (= (mapv str (range 100)) (mapv text sq)))
    (is (= "99" (text (last sq))))))
