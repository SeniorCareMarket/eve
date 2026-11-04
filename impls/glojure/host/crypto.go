// crypto.go: Go's standard-library crypto for eve's crypto feature on glojure (eve/adapters/crypto/go): SHA-256,
// SHA-384, SHA-512, HMAC-SHA256, AES-256-CTR, AES-256-GCM, Ed25519 and X25519, each called once per operation where
// glj code would run the portable rows' byte-at-a-time loops (eve.net.portable-crypto). Bytes cross as eve's: any sequential
// collection of integers 0..255 in (or a byte array), a vector of int64 0..255 out. A malformed input (a key or iv of
// the wrong length) panics, as the portable rows throw; a GCM open that does not authenticate answers nil.
package host

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/sha512"

	"github.com/glojurelang/glojure/pkg/lang"
)

// eveBytes is x's bytes: a []int8 or []byte as it is, else every element of a seqable collection, each taken mod 256.
func eveBytes(x any) []byte {
	switch b := x.(type) {
	case nil:
		return nil
	case []byte:
		return b
	case []int8:
		return asBytes(b)
	}
	out := make([]byte, 0, lang.Count(x))
	for s := lang.Seq(x); s != nil; s = s.Next() {
		out = append(out, byte(lang.AsInt64(s.First())))
	}
	return out
}

// eveVec is b as eve's bytes: a vector of int64 0..255.
func eveVec(b []byte) any {
	vs := make([]any, len(b))
	for i, c := range b {
		vs[i] = int64(c)
	}
	return lang.NewVector(vs...)
}

// Sha256 is the 32-byte SHA-256 digest.
func Sha256(x any) any { d := sha256.Sum256(eveBytes(x)); return eveVec(d[:]) }

// Sha512 is the 64-byte SHA-512 digest.
func Sha512(x any) any { d := sha512.Sum512(eveBytes(x)); return eveVec(d[:]) }

// Sha384 is the 48-byte SHA-384 digest (a ballot's RSASSA-PSS encoding hashes with it).
func Sha384(x any) any { d := sha512.Sum384(eveBytes(x)); return eveVec(d[:]) }

// HmacSha256 is RFC 2104 over SHA-256.
func HmacSha256(key, msg any) any {
	m := hmac.New(sha256.New, eveBytes(key))
	m.Write(eveBytes(msg))
	return eveVec(m.Sum(nil))
}

func aesBlock(key any) cipher.Block {
	k := eveBytes(key)
	if len(k) != 32 {
		panic("eve.crypto: an AES-256 key is 32 bytes")
	}
	b, err := aes.NewCipher(k)
	if err != nil {
		panic(err)
	}
	return b
}

// Aes256Ctr is data XOR the AES-256-CTR keystream from the 16-byte iv (the whole block a big-endian counter, as
// SP 800-38A F.5.5 and eve.net.aes).
func Aes256Ctr(key, iv, data any) any {
	v := eveBytes(iv)
	if len(v) != 16 {
		panic("eve.crypto: an AES-CTR iv is 16 bytes")
	}
	d := eveBytes(data)
	out := make([]byte, len(d))
	cipher.NewCTR(aesBlock(key), v).XORKeyStream(out, d)
	return eveVec(out)
}

func gcm(key any) cipher.AEAD {
	g, err := cipher.NewGCM(aesBlock(key))
	if err != nil {
		panic(err)
	}
	return g
}

func gcmIV(iv any) []byte {
	v := eveBytes(iv)
	if len(v) != 12 {
		panic("eve.crypto: an AES-GCM iv is 12 bytes")
	}
	return v
}

// Aes256GcmSeal is AES-256-GCM's ciphertext ‖ 16-byte tag (NIST SP 800-38D; a 12-byte iv).
func Aes256GcmSeal(key, iv, aad, pt any) any {
	return eveVec(gcm(key).Seal(nil, gcmIV(iv), eveBytes(pt), eveBytes(aad)))
}

// Aes256GcmOpen is the plaintext of ciphertext ‖ tag, or nil when it does not authenticate.
func Aes256GcmOpen(key, iv, aad, sealed any) any {
	s := eveBytes(sealed)
	if len(s) < 16 {
		return nil
	}
	pt, err := gcm(key).Open(nil, gcmIV(iv), s, eveBytes(aad))
	if err != nil {
		return nil
	}
	return eveVec(pt)
}

func edKey(seed any) ed25519.PrivateKey {
	s := eveBytes(seed)
	if len(s) != ed25519.SeedSize {
		panic("eve.crypto: an Ed25519 seed is 32 bytes")
	}
	return ed25519.NewKeyFromSeed(s)
}

// Ed25519PublicKey is the 32-byte public key of a 32-byte seed (RFC 8032 §5.1.5).
func Ed25519PublicKey(seed any) any { return eveVec(edKey(seed).Public().(ed25519.PublicKey)) }

// Ed25519Sign is the 64-byte signature of msg under a 32-byte seed.
func Ed25519Sign(seed, msg any) any { return eveVec(ed25519.Sign(edKey(seed), eveBytes(msg))) }

// Ed25519Verify is whether sig is a valid signature of msg under the 32-byte public key. Go's verify is RFC 8032's
// cofactorless check with a canonical S, which eve's well-formedness rules (eve.net.ed25519/well-formed?) are applied
// beside by the adapter.
func Ed25519Verify(pub, msg, sig any) bool {
	p, s := eveBytes(pub), eveBytes(sig)
	if len(p) != ed25519.PublicKeySize || len(s) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(p), eveBytes(msg), s)
}

// X25519 is RFC 7748's [k]u, k and u 32 little-endian bytes (crypto/ecdh clamps k and masks u's top bit, as the RFC
// does). A low-order u gives the all-zero output, which crypto/ecdh refuses with an error: answered as those 32 zero
// bytes, as the portable row computes them (a caller that must refuse it checks, as HPKE's DH does).
func X25519(k, u any) any {
	priv, err := ecdh.X25519().NewPrivateKey(eveBytes(k))
	if err != nil {
		panic(err)
	}
	pub, err := ecdh.X25519().NewPublicKey(eveBytes(u))
	if err != nil {
		panic(err)
	}
	out, err := priv.ECDH(pub)
	if err != nil {
		out = make([]byte, 32)
	}
	return eveVec(out)
}
