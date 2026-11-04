(ns eve.net.ell2
  "RFC 9380's map to edwards25519 in portable cljc: the straight-line Elligator 2 map of Appendix G.2.1 (curve25519)
   and the rational map of G.2.2 (to edwards25519), and for a _RO_ suite the two points added and the cofactor cleared
   by multiplication by 8 (§7, h_eff = 8) — the steps of edwards25519_XMD:SHA-512_ELL2_RO_ after hash_to_field
   (eve.aegis.h2c holds the rest and the RFC's vectors, eve/test/eve/aegis/h2c_test.cljc). The field elements and
   points are eve.net.ed25519's (16-bit limbs, points [X Y Z T]); the powers (the square-root power, the inversions)
   go through the `modpow` the caller passes — the crypto feature's big-integer row over big-endian bytes, so each
   crypto adapter passes its own native one (eve.net.portable-crypto/ed25519-map-to-point). Moved from eve.aegis.h2c
   unchanged but for that argument.

     (map-to-curve modpow u)        Q = map_to_curve(u), a point [X Y Z T]
     (clear-cofactor p)             8·P
     (point->affine modpow p)       [x y]
     (map-to-point u0 u1 modpow)    the 32-byte encoding of clear_cofactor(map_to_curve(u0) + map_to_curve(u1)), u0 and
                                    u1 32 little-endian bytes"
  (:require [eve.net.bignum :as bn]
            [eve.net.ed25519 :as ed]))

;; ── the field (p = 2^255 − 19, limbs as eve.net.ed25519 holds them)

(defn- limbs16 "16 limbs of a bignum below p" [v] (into (vec v) (repeat (- 16 (count v)) 0)))

(defn fe-of-int [n] (limbs16 (bn/from-int n)))

;; p, p − 2 and (p − 5)/8 as 32 big-endian bytes, for the modpow row (h2c_test holds each to its computation)
(def P-BE (conj (into [0x7f] (repeat 30 0xff)) 0xed))
(def P-2-BE (conj (into [0x7f] (repeat 30 0xff)) 0xeb))
(def P58-BE (conj (into [0x0f] (repeat 30 0xff)) 0xfd))

(defn fe->be "the 32 big-endian bytes of a field element's canonical value" [a] (vec (rseq (ed/pack25519 a))))
(defn be->fe "the field element of 32 big-endian bytes below p" [bs] (ed/unpack25519 (vec (rseq (vec bs)))))
(defn fpow "a^e mod p, e big-endian bytes, through modpow" [modpow a e] (be->fe (modpow (fe->be a) e P-BE)))
(defn finv "a^(p−2): the inverse (0 for 0, as eve.net.ed25519/inv)" [modpow a] (fpow modpow a P-2-BE))
(defn fpow2523 "a^((p−5)/8)" [modpow a] (fpow modpow a P58-BE))

(defn fe= [a b] (= (ed/pack25519 a) (ed/pack25519 b)))
(defn fneg [a] (ed/fsub ed/gf0 a))
(defn- cmov "b where e, a otherwise" [a b e] (if e b a))
(defn sgn0 [a] (ed/parity a))

(def J (fe-of-int 486662))
(def ^:private NEG-J (fneg J))
;; The two constants of the maps are LITERALS (limbs), each held to its computation by h2c_test (eve.aegis.h2c's
;; c2-computed, c1-ed-computed): ValueScript's compiler evaluates a top-level def's init when it compiles it, and the
;; field square root behind c1 filled its 2 GiB slab there (measured on
;; ValueScript). C2 is 2^((p+3)/8) unreduced (its value ≥ p); the limb arithmetic takes it as is.
(def C2 [41118 18958 6951 50414 58488 44335 6150 12099 55207 15867 153 11085 57099 20417 9344 43907])
(def C1-ED [32262 65349 1194 52334 6786 19325 54225 50593 20350 1020 2268 53883 1723 24736 60916 3878])

(defn map-to-curve-elligator2-curve25519 "Appendix G.2.1: (xn xd yn yd) on curve25519" [modpow u]
  (let [tv1 (ed/fsq u)
        tv1 (ed/fadd tv1 tv1)
        xd (ed/fadd tv1 ed/gf1)
        x1n NEG-J
        tv2 (ed/fsq xd)
        gxd (ed/fmul tv2 xd)
        gx1 (ed/fmul J tv1)
        gx1 (ed/fmul gx1 x1n)
        gx1 (ed/fadd gx1 tv2)
        gx1 (ed/fmul gx1 x1n)
        tv3 (ed/fsq gxd)
        tv2 (ed/fsq tv3)
        tv3 (ed/fmul tv3 gxd)
        tv3 (ed/fmul tv3 gx1)
        tv2 (ed/fmul tv2 tv3)
        y11 (fpow2523 modpow tv2)
        y11 (ed/fmul y11 tv3)
        y12 (ed/fmul y11 ed/SQRTM1)
        tv2 (ed/fsq y11)
        tv2 (ed/fmul tv2 gxd)
        e1 (fe= tv2 gx1)
        y1 (cmov y12 y11 e1)
        x2n (ed/fmul x1n tv1)
        y21 (ed/fmul y11 u)
        y21 (ed/fmul y21 C2)
        y22 (ed/fmul y21 ed/SQRTM1)
        gx2 (ed/fmul gx1 tv1)
        tv2 (ed/fsq y21)
        tv2 (ed/fmul tv2 gxd)
        e2 (fe= tv2 gx2)
        y2 (cmov y22 y21 e2)
        tv2 (ed/fsq y1)
        tv2 (ed/fmul tv2 gxd)
        e3 (fe= tv2 gx1)
        xn (cmov x2n x1n e3)
        y (cmov y2 y1 e3)
        e4 (= 1 (sgn0 y))
        y (cmov y (fneg y) (not= e3 e4))]
    [xn xd y ed/gf1]))

(defn map-to-curve-elligator2-edwards25519 "Appendix G.2.2: (xn xd yn yd) on edwards25519" [modpow u]
  (let [[xmn xmd ymn ymd] (map-to-curve-elligator2-curve25519 modpow u)
        xn (ed/fmul xmn ymd)
        xn (ed/fmul xn C1-ED)
        xd (ed/fmul xmd ymn)
        yn (ed/fsub xmn xmd)
        yd (ed/fadd xmn xmd)
        tv1 (ed/fmul xd yd)
        e (fe= tv1 ed/gf0)]
    [(cmov xn ed/gf0 e) (cmov xd ed/gf1 e) (cmov yn ed/gf1 e) (cmov yd ed/gf1 e)]))

(defn- affine->point "[X Y Z T] of (xn/xd, yn/yd)" [modpow [xn xd yn yd]]
  (let [x (ed/fmul xn (finv modpow xd)) y (ed/fmul yn (finv modpow yd))]
    [x y ed/gf1 (ed/fmul x y)]))

(defn point->affine "[x y] (limbs) of a point" [modpow [x y z _]]
  (let [zi (finv modpow z)] [(ed/fmul x zi) (ed/fmul y zi)]))

(defn map-to-curve "Q = map_to_curve(u), a point [X Y Z T]" [modpow u]
  (affine->point modpow (map-to-curve-elligator2-edwards25519 modpow u)))

(defn clear-cofactor "8·P: three doublings" [p] (let [p2 (ed/padd p p) p4 (ed/padd p2 p2)] (ed/padd p4 p4)))

(defn map-to-point
  "the 32-byte encoding (eve.net.ed25519/pack's bytes: y, x's parity in bit 255) of
   clear_cofactor(map_to_curve(u0) + map_to_curve(u1)), u0 and u1 32 little-endian bytes (bit 255 dropped)"
  [u0 u1 modpow]
  (let [q (clear-cofactor (ed/padd (map-to-curve modpow (ed/unpack25519 (vec u0)))
                                   (map-to-curve modpow (ed/unpack25519 (vec u1)))))
        [x y] (point->affine modpow q)
        r (ed/pack25519 y)]
    (assoc r 31 (+ (nth r 31) (* 128 (ed/parity x))))))
