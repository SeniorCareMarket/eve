(ns eve.numeric-test
  "Portable contract for Eve's exact numbers (Clojure 1.12 / bb 1.13.226).
   The required surface is lossless print/read, =, hash, accessors, and (with
   eve.numeric-tags-test) the 0x16/0x17/0x18 codec. Arithmetic is optional
   and is exercised lightly here. Source contains no 123N / 1/3 / 1.5M
   literals so the file loads on cljs / nbb."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.numeric :as n]))

(defn- N [s] (n/bigint s))
(defn- R [a b] (n/ratio a b))
(defn- M [s] (n/bigdec s))
(defn- rd [s] (n/read-number s))

(deftest typetags-and-accessors
  (testing "bigint typetag"
    (is (n/bigint? (N "123")))
    (is (n/bigint? (rd "123N")))
    (is (not (n/bigint? 123)))
    (is (n/equiv (N "123") (rd "123N"))))
  (testing "ratio typetag, sign on the numerator, reduced"
    (is (n/ratio? (R 1 2)))
    (is (n/ratio? (rd "1/3")))
    (is (not (n/ratio? (R 4 2)))) ; 4/2 is the integer 2N
    (is (n/equiv (n/numerator (R -1 3)) (N "-1")))
    (is (n/equiv (n/denominator (R 1 -3)) (N "3")))
    (is (n/equiv (R 2 4) (R 1 2))))
  (testing "decimal typetag preserves scale"
    (is (n/decimal? (M "1.5")))
    (is (n/decimal? (rd "1.2300M")))
    (is (= 1 (n/scale (M "1.5"))))
    (is (= 4 (n/scale (rd "1.2300M"))))
    (is (n/equiv (n/unscaled-value (M "1.50")) (N "150")))))

(deftest clojure-equality
  (testing "(= 5 5N) true, same hasheq; typetags otherwise stay distinct"
    (is (n/equiv 5 (N "5")))
    (is (n/equiv (N "5") 5))
    (is (= (n/hasheq 5) (n/hasheq (N "5"))))
    (is (= 823512154 (n/hasheq 123) (n/hasheq (N "123"))))
    (is (not (n/equiv 1 (M "1"))))
    (is (not (n/equiv (R 1 2) 0.5)))
    (is (n/equiv (M "1.5") (M "1.50")))
    (is (n/equiv (M "0") (M "0.0")))
    (is (n/equiv (M "10") (M "1E+1")))
    (is (= (n/hasheq (M "1.5")) (n/hasheq (M "1.50")) 466))
    (is (= (n/hasheq (M "0")) (n/hasheq (M "0.0")) 0))
    (is (= 3 (n/hasheq (R 1 2))))
    (is (= 2 (n/hasheq (R 1 3))))
    (is (= -7 (n/hasheq (R -3 4))))))

(deftest clojure-compare
  (is (zero? (n/exact-compare 5 (N "5"))))
  (is (zero? (n/exact-compare (M "1.5") (M "1.50"))))
  (is (zero? (n/exact-compare (R 1 2) 0.5)))
  (is (zero? (n/exact-compare 1 (M "1"))))
  (is (pos? (n/exact-compare (R 1 2) (R 1 3))))
  (is (neg? (n/exact-compare (R 1 2) 1))))

(deftest print-and-read-clojure-tokens
  (testing "pr-number is JVM Clojure's pr-str, on every host"
    (is (= "123N" (n/pr-number (N "123"))))
    (is (= "123" (n/str-number (N "123"))))
    (is (= "1/3" (n/pr-number (R 1 3)) (n/str-number (R 1 3))))
    (is (= "-1/3" (n/pr-number (R 1 -3))))
    (is (= "1.5M" (n/pr-number (M "1.5"))))
    (is (= "1.5" (n/str-number (M "1.5"))))
    (is (= "1.2300M" (n/pr-number (rd "1.2300M"))))
    (is (= "1E+2M" (n/pr-number (M "1E+2"))))
    (is (= "0M" (n/pr-number (M "0")))))
  (testing "read-number of those tokens, plus LispReader integer arms"
    (is (n/equiv (rd "123N") (N "123")))
    (is (n/equiv (rd "1/3") (R 1 3)))
    (is (n/equiv (rd "1.2300M") (rd "1.2300M")))
    (is (= 4 (n/scale (rd "1.2300M"))))
    (is (n/bigint? (rd "9223372036854775808")))
    (is (= "9223372036854775808N" (n/pr-number (rd "9223372036854775808"))))
    (is (= "9223372036854775807" (n/pr-number (rd "9223372036854775807"))))
    (is (not (n/bigint? (rd "9223372036854775807"))))
    (is (= "18446744073709551615N" (n/pr-number (rd "0xffffffffffffffff"))))
    (is (= "18446744073709551615N" (n/pr-number (rd "0xffffffffffffffffN"))))
    (is (= "16N" (n/pr-number (rd "0x10N"))))
    (is (= 511 (rd "0777")))
    (is (n/equiv (rd "10N") (N "10")))
    (is (double? (rd "1e20")))))

(deftest tagged-round-trip
  (testing "hosts that cannot spell 123N / 1/3 / 1.5M use #eve/N, #eve/ratio, #eve/M"
    ;; host-pr-number: JVM/bb emit Clojure tokens; others emit tags
    (is (or (= "123N" (n/host-pr-number (N "123")))
            (= "#eve/N \"123\"" (n/host-pr-number (N "123")))))
    (is (or (= "1/3" (n/host-pr-number (R 1 3)))
            (= "#eve/ratio \"1/3\"" (n/host-pr-number (R 1 3)))))
    (is (or (= "1.2300M" (n/host-pr-number (rd "1.2300M")))
            (= "#eve/M \"1.2300\"" (n/host-pr-number (rd "1.2300M"))))))
  (testing "every host reads the tagged forms (cross-host EDN)"
    (is (n/equiv (n/read-edn "#eve/N \"9007199254740993\"") (N "9007199254740993")))
    (is (n/equiv (n/read-edn "#eve/ratio \"1/3\"") (R 1 3)))
    (is (n/equiv (n/read-edn "#eve/M \"1.2300\"") (rd "1.2300M")))
    (is (= 4 (n/scale (n/read-edn "#eve/M \"1.2300\""))))
    (is (n/equiv (n/read-edn "{:k #eve/ratio \"1/2\"}") {:k (R 1 2)})))
  (testing "tag readers live on eve.deftype.registry, not a parallel table"
    (require 'eve.deftype.registry)
    (n/install!)
    (let [dr (eve.deftype.registry/data-readers)
          entry (eve.deftype.registry/lookup-type "eve.numeric/bigdec")]
      (is (fn? (get dr 'eve/N)))
      (is (fn? (get dr 'eve/ratio)))
      (is (fn? (get dr 'eve/M)))
      (is (fn? (get dr 'eve.numeric/n)))
      (is (= 'eve/M (get-in entry [:type-io :tag])))
      (is (= :custom (get-in entry [:type-io :print :mode]))))))

(deftest optional-arithmetic
  (testing "optional: Clojure promotion and exact decimal / ratio arithmetic"
    (is (n/equiv (n/add (R 1 2) (R 1 3)) (R 5 6)))
    (is (n/equiv (n/add (R 1 2) (R 1 2)) (N "1")))
    (is (n/equiv (n/add 1 (N "1")) (N "2")))
    (is (n/equiv (n/add (M "1.5") 1) (M "2.5")))
    (is (n/equiv (n/add (M "1.5") (M "1.50")) (M "3.00")))
    (is (n/equiv (n/mul (R 1 2) (R 1 3)) (R 1 6)))
    (is (n/equiv (n/divide 1 3) (R 1 3)))
    (is (n/equiv (n/divide (M "3") (M "2")) (M "1.5")))
    (is (n/equiv (n/add (M "1.5") (R 1 2)) (M "2.0")))
    (is (n/equiv (n/quot' (N "10") (N "3")) (N "3")))
    (is (n/equiv (n/rem' (N "10") (N "3")) (N "1")))
    (is (n/equiv (n/mod' (N "-10") (N "3")) (N "2")))))
