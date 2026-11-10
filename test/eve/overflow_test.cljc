(ns eve.overflow-test
  "The arithmetic contract (doc/arithmetic-overflow.md; the operator's decision, 2026-11-07): checked
   + - * throw on i64 overflow, +' -' *' promote, unchecked-* wrap — on every host, as the JVM's
   clojure.core does. This file pins the contract per host. A seat whose binding is not landed
   carries its current behavior in its own arm here and a named finding in that doc — never a
   quiet divergence, never this file asserting what the seat does not do."
  ;; eve.host-completion is required explicitly: every eve program loads it through eve.host, and
  ;; this test pins the bindings it installs — a runner that loads only this namespace (jolt's)
  ;; must still see them.
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host-completion]))

(def ^:private MAXL 9223372036854775807)
(def ^:private MINL (- -9223372036854775807 1))

#?(:cljs
   (deftest js-numbers-have-no-i64-to-overflow
     (testing "a JS host has no fixed-width integer type: its exact integers are BigInts, whose
              arithmetic is exact (promotes), never wraps — the seat's row in doc/arithmetic-overflow.md"
       (is (= (js/BigInt "9223372036854775808") (+ (js/BigInt "9223372036854775807") (js/BigInt 1))))
       (is (= (js/BigInt "-9223372036854775809") (- (js/BigInt "-9223372036854775808") (js/BigInt 1))))))

   :clje
   (deftest beam-promotes-until-bound
     (testing "FINDING (doc/arithmetic-overflow.md): the BEAM's integers are unbounded and clojerl
              gives eve no var re-root, so + promotes on this seat until a clojerl-side binding exists"
       (is (= 9223372036854775808 (+ MAXL 1)))
       (is (= -9223372036854775809 (- MINL 1)))))

   :jolt
   (do
     (deftest checked-throws-on-overflow
       (testing "var-mediated + - * (and inc, dec, unary minus) throw on i64 overflow; compiled
                direct calls are the seat's open finding (doc/arithmetic-overflow.md, the jolt row):
                jolt's compiler hard-codes promoting arithmetic that consults neither the var
                root nor :inline, so the completion's binding is pinned here at the var surface"
         (is (thrown? ArithmeticException (apply (var-get #'clojure.core/+) [MAXL 1])))
         (is (thrown? ArithmeticException (apply (var-get #'clojure.core/-) [MINL 1])))
         (is (thrown? ArithmeticException (apply (var-get #'clojure.core/*) [3037000500 3037000500])))
         (is (thrown? ArithmeticException (apply (var-get #'clojure.core/-) [MINL])))
         (is (thrown? ArithmeticException (apply (var-get #'clojure.core/inc) [MAXL])))
         (is (thrown? ArithmeticException (apply (var-get #'clojure.core/dec) [MINL])))))

     (deftest primed-promotes
       (testing "+' -' *' answer the exact value past i64"
         (is (= 9223372036854775808N (+' MAXL 1)))
         (is (= -9223372036854775809N (+' MINL -1)))
         (is (= -9223372036854775809N (-' MINL 1)))
         (is (= 9223372036854775808N (-' MAXL -1)))
         (is (= 9223372036854775808N (-' MINL)))
         (is (= 9223372036854775808N (*' MINL -1)))
         (is (= 18446744073709551614N (*' MAXL 2)))))

     (deftest unchecked-wraps
       (testing "unchecked-* wrap at 64 bits, as the JVM's do"
         (is (= MINL (unchecked-add MAXL 1)))
         (is (= MAXL (unchecked-subtract MINL 1)))
         (is (= MINL (unchecked-negate MINL)))
         (is (= -2 (unchecked-multiply MAXL 2))))))

   :default
   (do
     (deftest checked-throws-on-overflow
       (testing "+ - * (and inc, dec, unary minus) throw on i64 overflow"
         (is (thrown? #?(:jank nil :lpy Exception :glj Exception :default ArithmeticException) (+ MAXL 1)))
         (is (thrown? #?(:jank nil :lpy Exception :glj Exception :default ArithmeticException) (- MINL 1)))
         (is (thrown? #?(:jank nil :lpy Exception :glj Exception :default ArithmeticException) (* 3037000500 3037000500)))
         (is (thrown? #?(:jank nil :lpy Exception :glj Exception :default ArithmeticException) (- MINL)))
         (is (thrown? #?(:jank nil :lpy Exception :glj Exception :default ArithmeticException) (inc MAXL)))
         (is (thrown? #?(:jank nil :lpy Exception :glj Exception :default ArithmeticException) (dec MINL))))
       (testing "in-range arithmetic is untouched"
         (is (= 5 (+ 2 3)))
         (is (= -1 (- 2 3)))
         (is (= 6 (* 2 3)))
         (is (= MAXL (+ MAXL 0)))
         (is (= MINL (* MINL 1)))
         (is (= 0 (* MAXL 0)))
         (is (= MAXL (inc (dec MAXL))))))

     (deftest primed-promotes
       (testing "+' -' *' answer the exact value past i64"
         (is (= 9223372036854775808N (+' MAXL 1)))
         (is (= -9223372036854775809N (+' MINL -1)))
         (is (= -9223372036854775809N (-' MINL 1)))
         (is (= 9223372036854775808N (-' MAXL -1)))
         (is (= 9223372036854775808N (-' MINL)))
         (is (= 9223372036854775808N (*' MINL -1)))
         (is (= 18446744073709551614N (*' MAXL 2)))))

     (deftest unchecked-wraps
       (testing "unchecked-* wrap at 64 bits, as the JVM's do"
         (is (= MINL (unchecked-add MAXL 1)))
         (is (= MAXL (unchecked-subtract MINL 1)))
         (is (= MINL (unchecked-negate MINL)))
         (is (= -2 (unchecked-multiply MAXL 2)))))))
