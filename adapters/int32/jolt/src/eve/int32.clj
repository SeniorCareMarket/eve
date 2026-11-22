(ns eve.int32
  "32-bit integer arithmetic as eve's hashes take it (Murmur3, FNV-1a, a
   HAMT's bitmaps): signed results, the same on every host. On jolt rotl, ushr
   and popcount are Chez Scheme procedures over fixnums (fxand, fxsll, fxsrl,
   fxpopcount32), defined once at load through jolt.scheme. jolt's own
   bit-shift-left / unsigned-bit-shift-right are never open-coded: each goes
   through a helper that masks with a 2^64 bignum and wraps to 64 bits
   (wrap64), ~48 ns per shift (measured 2026-11-22 ET), where these stay in
   the fixnum range throughout (a 32-bit operand never needs more than 49
   bits here). Results are identical to adapters/int32/jvm's.
   A long outside the fixnum range (|x| >= 2^60) is narrowed by the generic
   bitwise-and first, as the JVM's unchecked-int narrows any long."
  (:require [jolt.scheme :as scheme]))

(scheme/eval-string
 "(begin
    (define (eve-i32-u32 x) (if (fixnum? x) (fxand x #xFFFFFFFF) (bitwise-and x #xFFFFFFFF)))
    (define (eve-i32-s32 u) (if (fx>= u #x80000000) (fx- u #x100000000) u))
    (define (eve-i32-i32 x) (eve-i32-s32 (eve-i32-u32 x)))
    (define (eve-i32-imul a b)
      (let* ((ua (eve-i32-u32 a)) (ub (eve-i32-u32 b))
             (lo (fx* (fxand ua #xFFFF) ub))
             (hi (fx* (fxsrl ua 16) ub)))
        (eve-i32-s32 (fxand (fx+ (fxand lo #xFFFFFFFF) (fxsll (fxand hi #xFFFF) 16)) #xFFFFFFFF))))
    (define (eve-i32-rotl x r)
      (let ((u (eve-i32-u32 x)) (s (fxand r 31)))
        (if (fx= s 0)
            (eve-i32-s32 u)
            (eve-i32-s32 (fxior (fxsll (fxand u (fx- (fxsll 1 (fx- 32 s)) 1)) s)
                                (fxsrl u (fx- 32 s)))))))
    (define (eve-i32-ushr x n)
      (let ((m (fxand n 63))) (if (fx>= m 32) 0 (fxsrl (eve-i32-u32 x) m))))
    (define (eve-i32-popcount x) (fxpopcount32 (eve-i32-u32 x)))
    #t)")

(def ^:private rotl* (scheme/proc "eve-i32-rotl"))
(def ^:private ushr* (scheme/proc "eve-i32-ushr"))
(def ^:private popcount* (scheme/proc "eve-i32-popcount"))

(defn i32
  "x's low 32 bits, as a signed integer. (The JVM adapter's form: jolt's unchecked-int is already cheap.)"
  ^long [^long x]
  (long (unchecked-int x)))

(defn imul
  "a × b mod 2^32, signed. (The JVM adapter's form: 13 ns against the Scheme split multiply's 16.)"
  ^long [^long a ^long b]
  (long (unchecked-multiply-int (unchecked-int a) (unchecked-int b))))

(defn rotl
  "x's 32-bit pattern rotated left by r bits (0 < r < 32), signed."
  [x r]
  (rotl* x r))

(defn ushr
  "x's 32-bit pattern shifted right by n bits, zeros in: non-negative."
  [x n]
  (ushr* x n))

(defn popcount
  "The number of bits set in x's 32-bit pattern."
  [x]
  (popcount* x))
