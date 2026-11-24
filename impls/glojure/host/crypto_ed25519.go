// crypto_ed25519.go: edwards25519 scalarmult, mul-add and map-to-point for eve's
// crypto feature on glojure. The walk is the portable row's (eve.net.edwards): 256
// bits, no clamp, no reduction mod L, variable-time; decoding is RFC 8032 §5.1.3
// (nil when the encoding is not a point or is short). map-to-point is RFC 9380
// G.2.1–G.2.2: clear_cofactor(map_to_curve(u0) + map_to_curve(u1)), bit 255 of
// each u dropped. Held to eve.net.portable-crypto byte for byte.
package host

import (
	"math/big"
)

var (
	edP     = mustBig("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed") // 2^255 − 19
	edD     = mustBig("52036cee2b6ffe738cc740797779e89800700a4d4141d8ab75eb4dca135978a3")
	edSqrtM = mustBig("2b8324804fc1df0b2b4d00993dfbd7a72f431806ad2fe478c4ee1b274a0ea0b0")
	edJ     = big.NewInt(486662)
	edC2    = mustBig("2b8324804fc1df0b2b4d00993dfbd7a72f431806ad2fe478c4ee1b274a0ea0b1") // C2 reduced
	edC1    = mustBig("0f26edf460a006bbd27b08dc03fc4f7ec5a1d3d14b7d1a82cc6e04aaff457e06")
	edP2    = mustBig("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeb") // p − 2
	edP58   = mustBig("0ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffd") // (p − 5)/8
	edZero  = big.NewInt(0)
	edOne   = big.NewInt(1)
	edTwo   = big.NewInt(2)
	edTwoD  = feMul(edTwo, edD)
)

func mustBig(hex string) *big.Int {
	n, ok := new(big.Int).SetString(hex, 16)
	if !ok {
		panic("eve.crypto: bad field constant")
	}
	return n
}

func feMod(x *big.Int) *big.Int {
	r := new(big.Int).Mod(x, edP)
	if r.Sign() < 0 {
		r.Add(r, edP)
	}
	return r
}

func feAdd(a, b *big.Int) *big.Int { return feMod(new(big.Int).Add(a, b)) }
func feSub(a, b *big.Int) *big.Int { return feMod(new(big.Int).Sub(a, b)) }
func feMul(a, b *big.Int) *big.Int { return feMod(new(big.Int).Mul(a, b)) }
func feSq(a *big.Int) *big.Int     { return feMul(a, a) }
func feNeg(a *big.Int) *big.Int    { return feSub(edZero, a) }
func feInv(a *big.Int) *big.Int    { return feMod(new(big.Int).Exp(a, edP2, edP)) }
func fePow2523(a *big.Int) *big.Int {
	return feMod(new(big.Int).Exp(a, edP58, edP))
}
func feEq(a, b *big.Int) bool { return feMod(a).Cmp(feMod(b)) == 0 }
func feOdd(a *big.Int) bool   { return feMod(a).Bit(0) == 1 }

// unpack25519LE is the field element of 32 little-endian bytes, bit 255 dropped.
func unpack25519LE(bs []byte) *big.Int {
	b := make([]byte, 32)
	copy(b, bs)
	if len(b) > 0 {
		b[31] &= 127
	}
	return feMod(leToInt(b))
}

func leToInt(le []byte) *big.Int {
	be := make([]byte, len(le))
	for i := range le {
		be[len(le)-1-i] = le[i]
	}
	return new(big.Int).SetBytes(be)
}

func intToLE32(n *big.Int) []byte {
	n = feMod(n)
	be := n.FillBytes(make([]byte, 32))
	le := make([]byte, 32)
	for i := range be {
		le[31-i] = be[i]
	}
	return le
}

type edPoint struct{ x, y, z, t *big.Int }

func edIdentity() edPoint {
	return edPoint{new(big.Int).Set(edZero), new(big.Int).Set(edOne), new(big.Int).Set(edOne), new(big.Int).Set(edZero)}
}

// edPadd is the complete a = −1 addition (add-2008-hwcd-3), as eve.net.ed25519/padd.
func edPadd(p, q edPoint) edPoint {
	a := feMul(feSub(p.y, p.x), feSub(q.y, q.x))
	b := feMul(feAdd(p.y, p.x), feAdd(q.y, q.x))
	c := feMul(feMul(p.t, q.t), edTwoD)
	d := feMul(feMul(p.z, q.z), edTwo)
	e := feSub(b, a)
	f := feSub(d, c)
	g := feAdd(d, c)
	h := feAdd(b, a)
	return edPoint{feMul(e, f), feMul(h, g), feMul(g, f), feMul(e, h)}
}

func edEncode(p edPoint) []byte {
	zi := feInv(p.z)
	x := feMul(p.x, zi)
	y := feMul(p.y, zi)
	out := intToLE32(y)
	if feOdd(x) {
		out[31] |= 128
	}
	return out
}

// edDecode is RFC 8032 §5.1.3. nil when bs is not 32 bytes, y ≥ p, the candidate
// is not a square, or x = 0 with the sign bit set.
func edDecode(bs []byte) *edPoint {
	if len(bs) != 32 {
		return nil
	}
	sign := bs[31] >> 7
	yb := append([]byte(nil), bs...)
	yb[31] &= 127
	y := leToInt(yb)
	if y.Cmp(edP) >= 0 {
		return nil
	}
	y2 := feSq(y)
	u := feSub(y2, edOne)
	v := feAdd(feMul(edD, y2), edOne)
	// x = u v^3 (u v^7)^((p−5)/8); times √−1 when x² v ≠ u
	v3 := feMul(feSq(v), v)
	v7 := feMul(feSq(v3), v)
	x := feMul(feMul(u, v3), fePow2523(feMul(u, v7)))
	if !feEq(feMul(feSq(x), v), u) {
		x = feMul(x, edSqrtM)
	}
	if !feEq(feMul(feSq(x), v), u) {
		return nil
	}
	if sign == 1 && feEq(x, edZero) {
		return nil
	}
	xOdd := byte(0)
	if feOdd(x) {
		xOdd = 1
	}
	if sign != xOdd {
		x = feNeg(x)
	}
	return &edPoint{x, y, new(big.Int).Set(edOne), feMul(x, y)}
}

func edBit(s []byte, i int) int {
	if i < 0 || i >= 8*len(s) {
		return 0
	}
	return int((s[i/8] >> (uint(i) % 8)) & 1)
}

// edSmul is [s]P, s any 32 little-endian bytes (256-bit walk, no clamp).
func edSmul(q edPoint, s []byte) edPoint {
	acc := edIdentity()
	for i := 255; i >= 0; i-- {
		acc = edPadd(acc, acc)
		if edBit(s, i) == 1 {
			acc = edPadd(acc, q)
		}
	}
	return acc
}

func as32(x any) ([]byte, bool) {
	b := eveBytes(x)
	if len(b) != 32 {
		return nil, false
	}
	return b, true
}

// Ed25519ScalarMult is the 32-byte encoding of [s]P, or nil when P is not a point.
func Ed25519ScalarMult(s, p any) any {
	sb, ok := as32(s)
	if !ok {
		return nil
	}
	pb, ok := as32(p)
	if !ok {
		return nil
	}
	q := edDecode(pb)
	if q == nil {
		return nil
	}
	return eveVec(edEncode(edSmul(*q, sb)))
}

// Ed25519MulAdd is the 32-byte encoding of [a]P + [b]Q, or nil when either encoding
// is short or not a point.
func Ed25519MulAdd(a, p, b, q any) any {
	ab, ok := as32(a)
	if !ok {
		return nil
	}
	pb, ok := as32(p)
	if !ok {
		return nil
	}
	bb, ok := as32(b)
	if !ok {
		return nil
	}
	qb, ok := as32(q)
	if !ok {
		return nil
	}
	P := edDecode(pb)
	Q := edDecode(qb)
	if P == nil || Q == nil {
		return nil
	}
	return eveVec(edEncode(edPadd(edSmul(*P, ab), edSmul(*Q, bb))))
}

func cmov(a, b *big.Int, e bool) *big.Int {
	if e {
		return new(big.Int).Set(b)
	}
	return new(big.Int).Set(a)
}

// mapToCurveEdwards is RFC 9380 G.2.1 then G.2.2, then the affine point.
func mapToCurveEdwards(u *big.Int) edPoint {
	tv1 := feSq(u)
	tv1 = feAdd(tv1, tv1)
	xd := feAdd(tv1, edOne)
	x1n := feNeg(edJ)
	tv2 := feSq(xd)
	gxd := feMul(tv2, xd)
	gx1 := feMul(edJ, tv1)
	gx1 = feMul(gx1, x1n)
	gx1 = feAdd(gx1, tv2)
	gx1 = feMul(gx1, x1n)
	tv3 := feSq(gxd)
	tv2 = feSq(tv3)
	tv3 = feMul(tv3, gxd)
	tv3 = feMul(tv3, gx1)
	tv2 = feMul(tv2, tv3)
	y11 := fePow2523(tv2)
	y11 = feMul(y11, tv3)
	y12 := feMul(y11, edSqrtM)
	tv2 = feSq(y11)
	tv2 = feMul(tv2, gxd)
	e1 := feEq(tv2, gx1)
	y1 := cmov(y12, y11, e1)
	x2n := feMul(x1n, tv1)
	y21 := feMul(y11, u)
	y21 = feMul(y21, edC2)
	y22 := feMul(y21, edSqrtM)
	gx2 := feMul(gx1, tv1)
	tv2 = feSq(y21)
	tv2 = feMul(tv2, gxd)
	e2 := feEq(tv2, gx2)
	y2 := cmov(y22, y21, e2)
	tv2 = feSq(y1)
	tv2 = feMul(tv2, gxd)
	e3 := feEq(tv2, gx1)
	xnM := cmov(x2n, x1n, e3)
	y := cmov(y2, y1, e3)
	e4 := feOdd(y)
	if e3 != e4 {
		y = feNeg(y)
	}
	// G.2.2: (xn xd yn yd) on edwards25519
	xmn, xmd, ymn, ymd := xnM, xd, y, new(big.Int).Set(edOne)
	xn := feMul(feMul(xmn, ymd), edC1)
	xd2 := feMul(xmd, ymn)
	yn := feSub(xmn, xmd)
	yd := feAdd(xmn, xmd)
	tv1b := feMul(xd2, yd)
	e := feEq(tv1b, edZero)
	xn = cmov(xn, edZero, e)
	xd2 = cmov(xd2, edOne, e)
	yn = cmov(yn, edOne, e)
	yd = cmov(yd, edOne, e)
	x := feMul(xn, feInv(xd2))
	yy := feMul(yn, feInv(yd))
	return edPoint{x, yy, new(big.Int).Set(edOne), feMul(x, yy)}
}

func edClearCofactor(p edPoint) edPoint {
	p2 := edPadd(p, p)
	p4 := edPadd(p2, p2)
	return edPadd(p4, p4)
}

// Ed25519MapToPoint is the 32-byte encoding of clear_cofactor(map_to_curve(u0)+map_to_curve(u1)).
func Ed25519MapToPoint(u0, u1 any) any {
	a := unpack25519LE(eveBytes(u0))
	b := unpack25519LE(eveBytes(u1))
	q := edClearCofactor(edPadd(mapToCurveEdwards(a), mapToCurveEdwards(b)))
	return eveVec(edEncode(q))
}
