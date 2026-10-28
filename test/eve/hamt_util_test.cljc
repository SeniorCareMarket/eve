(ns eve.hamt-util-test
  "The HAMT index helpers give the same answers on every host
   (FORMAT-DECISIONS.md §6). Expected values are bits [shift, shift+5) of the
   hash's 32-bit pattern, computed outside Clojure (Python, `(h & 0xFFFFFFFF)
   >> shift & 0x1f`), not by any arm of eve.hamt-util."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.hamt-util :as hu]))

;; [hash shift (mask-hash hash shift)]. 642791526 and -430950298 are the
;; hashes of the int keys 371 and 53312: their low 30 bits are equal, so the
;; two keys meet in a node at shift 30. Before §6 the JVM arm read bits of the
;; sign-extended long there and put 53312 at slot 31; CLJS and VS's eve
;; participant (vs.eve-disk) look for it at slot 3.
(def mask-rows
  [[642791526 0 6] [642791526 5 3] [642791526 25 19] [642791526 30 0]
   [-430950298 0 6] [-430950298 5 3] [-430950298 25 19] [-430950298 30 3]
   [-1981046296 0 8] [-1981046296 5 15] [-1981046296 25 4] [-1981046296 30 2]
   [-1 0 31] [-1 5 31] [-1 25 31] [-1 30 3]
   [-2147483648 0 0] [-2147483648 5 0] [-2147483648 25 0] [-2147483648 30 2]
   [2147483647 0 31] [2147483647 5 31] [2147483647 25 31] [2147483647 30 1]
   [0 0 0] [0 30 0] [1 0 1] [1 5 0]])

(deftest mask-hash-reads-the-32-bit-pattern
  (doseq [[h s want] mask-rows]
    (is (= want (hu/mask-hash h s)) (str "(mask-hash " h " " s ")"))))

(deftest last-level-has-two-bits
  (testing "shift 30 indexes bits 30-31 only: slots 0-3"
    (is (= 1 (hu/bitpos 642791526 30)))
    (is (= 8 (hu/bitpos -430950298 30)))
    (is (= 8 (hu/bitpos -1 30)))))

(deftest popcount32-rows
  (doseq [[n want] [[0 0] [1 1] [-1 32] [-2147483648 1] [1431655765 16]
                    [252645135 16] [-430950298 14] [642791526 12]]]
    (is (= want (hu/popcount32 n)) (str "(popcount32 " n ")"))))

(deftest get-index-rows
  ;; [bitmap hash shift index] — the bit comes from bitpos on the host, so a
  ;; bit of 2^31 is whatever the host's bitpos makes of it.
  (doseq [[bm h s want] [[-1 -1 25 31] [-1 -430950298 30 3] [11 1 0 1]
                         [-2147483647 -1 0 1] [2147483647 2147483647 0 31]]]
    (is (= want (hu/get-index bm (hu/bitpos h s)))
        (str "(get-index " bm " (bitpos " h " " s "))"))))

(deftest node-bit-rows
  ;; [dbm nbm hash shift bit] — the bit a key takes in an existing node. A JVM
  ;; or bb build before FORMAT-DECISIONS §6 put a negative hash 28 slots up at
  ;; shift 30 (53312 at slot 31: bit 2^31, which an i32 bitmap holds as
  ;; -2147483648); node-bit finds it there, and a new key takes bitpos's slot.
  (doseq [[dbm nbm h s want] [[0 0 -430950298 30 8]
                              [8 0 -430950298 30 8]
                              [0 -2147483648 -430950298 30 (bit-shift-left 1 31)]
                              [-2147483648 0 -430950298 30 (bit-shift-left 1 31)]
                              [-2147483648 8 -430950298 30 8]
                              [0 -2147483648 642791526 30 1]
                              [0 -2147483648 -430950298 25 524288]]]
    (is (= want (hu/node-bit dbm nbm h s))
        (str "(node-bit " dbm " " nbm " " h " " s ")"))))
