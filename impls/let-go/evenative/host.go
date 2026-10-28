package evenative

// The host natives eve's let-go adapters (eve/adapters/*/letgo) call, beside the
// region natives: what stock let-go has no word for, or answers in a shape eve
// cannot use — its http/request sends a string body only (eve's cas-root posts
// eve-wire bytes), and it carries no AES, Ed25519, X25519, SHA-384/512 or HMAC.
// Bytes come in as a byte-array, a string, or any seq of integers (each 0..255,
// or its signed byte), and go out as a vector of 0..255 — eve.net's byte
// sequences — unless a native says a byte-array.

import (
	"bufio"
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"math"
	"math/big"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unicode/utf8"

	"github.com/nooga/let-go/pkg/rt"
	"github.com/nooga/let-go/pkg/vm"
)

func anyBytes(v vm.Value) ([]byte, error) {
	switch b := v.(type) {
	case nil:
		return nil, nil
	case vm.String:
		return []byte(string(b)), nil
	case *vm.TypedArray:
		if s, ok := b.Unbox().([]byte); ok {
			return s, nil
		}
	}
	if v == vm.NIL {
		return nil, nil
	}
	sq, ok := v.(vm.Sequable)
	if !ok {
		return nil, fmt.Errorf("eve.native: expected bytes, got %s", v.Type().Name())
	}
	out := []byte{}
	for s := sq.Seq(); s != nil && s != vm.EmptyList; s = s.Next() {
		i, ok := s.First().(vm.Int)
		if !ok {
			return nil, fmt.Errorf("eve.native: a byte must be an integer, got %s", s.First().Type().Name())
		}
		out = append(out, byte(i))
	}
	return out, nil
}

func byteVec(b []byte) vm.Value {
	vs := make([]vm.Value, len(b))
	for i, c := range b {
		vs[i] = vm.Int(c)
	}
	return vm.NewPersistentVector(vs)
}

func strOf(v vm.Value, what string) (string, error) {
	s, ok := v.(vm.String)
	if !ok {
		return "", fmt.Errorf("eve.native: %s must be a string, got %s", what, v.Type().Name())
	}
	return string(s), nil
}

func bigOf(v vm.Value) (*big.Int, error) {
	switch x := v.(type) {
	case vm.Int:
		return big.NewInt(int64(x)), nil
	case *vm.BigInt:
		return x.Val(), nil
	}
	return nil, fmt.Errorf("eve.native: expected an integer, got %s", v.Type().Name())
}

// bytes1 defines a native of one byte argument answering bytes.
func bytes1(ns *vm.Namespace, name string, f func([]byte) ([]byte, error)) {
	def(ns, name, func(vs []vm.Value) (vm.Value, error) {
		if err := argc(name, vs, 1); err != nil {
			return vm.NIL, err
		}
		b, err := anyBytes(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		r, err := f(b)
		if err != nil {
			return vm.NIL, err
		}
		return byteVec(r), nil
	})
}

// bytesN defines a native of n byte arguments; f answers a value.
func bytesN(ns *vm.Namespace, name string, n int, f func([][]byte) (vm.Value, error)) {
	def(ns, name, func(vs []vm.Value) (vm.Value, error) {
		if err := argc(name, vs, n); err != nil {
			return vm.NIL, err
		}
		bs := make([][]byte, n)
		for i, v := range vs {
			b, err := anyBytes(v)
			if err != nil {
				return vm.NIL, err
			}
			bs[i] = b
		}
		return f(bs)
	})
}

func aesGCM(key []byte, iv []byte) (cipher.AEAD, error) {
	if len(key) != 32 {
		return nil, fmt.Errorf("eve.crypto: an AES-256 key is 32 bytes")
	}
	if len(iv) != 12 {
		return nil, fmt.Errorf("eve.crypto: an AES-GCM iv is 12 bytes")
	}
	b, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(b)
}

func edKey(seed []byte) (ed25519.PrivateKey, error) {
	if len(seed) != ed25519.SeedSize {
		return nil, fmt.Errorf("eve.crypto: an Ed25519 seed is 32 bytes")
	}
	return ed25519.NewKeyFromSeed(seed), nil
}

// one reader for the process's standard input: every line read through it
var (
	stdinOnce sync.Once
	stdin     *bufio.Reader
)

func kw(s string) vm.Value { return vm.Keyword(s) }

func init() {
	ns := vm.NewNamespace("eve.host-native")

	// ── crypto (eve.crypto, adapters/crypto/letgo) ─────────────────────────────
	bytes1(ns, "sha256", func(b []byte) ([]byte, error) { d := sha256.Sum256(b); return d[:], nil })
	bytes1(ns, "sha384", func(b []byte) ([]byte, error) { d := sha512.Sum384(b); return d[:], nil })
	bytes1(ns, "sha512", func(b []byte) ([]byte, error) { d := sha512.Sum512(b); return d[:], nil })
	bytesN(ns, "hmac-sha256", 2, func(b [][]byte) (vm.Value, error) {
		m := hmac.New(sha256.New, b[0])
		m.Write(b[1])
		return byteVec(m.Sum(nil)), nil
	})
	bytesN(ns, "aes256-ctr", 3, func(b [][]byte) (vm.Value, error) {
		if len(b[0]) != 32 || len(b[1]) != 16 {
			return vm.NIL, fmt.Errorf("eve.crypto: AES-256-CTR takes a 32-byte key and a 16-byte iv")
		}
		c, err := aes.NewCipher(b[0])
		if err != nil {
			return vm.NIL, err
		}
		out := make([]byte, len(b[2]))
		cipher.NewCTR(c, b[1]).XORKeyStream(out, b[2])
		return byteVec(out), nil
	})
	bytesN(ns, "aes256-gcm-seal", 4, func(b [][]byte) (vm.Value, error) {
		g, err := aesGCM(b[0], b[1])
		if err != nil {
			return vm.NIL, err
		}
		return byteVec(g.Seal(nil, b[1], b[3], b[2])), nil
	})
	bytesN(ns, "aes256-gcm-open", 4, func(b [][]byte) (vm.Value, error) {
		g, err := aesGCM(b[0], b[1])
		if err != nil {
			return vm.NIL, err
		}
		if len(b[3]) < 16 {
			return vm.NIL, nil
		}
		pt, err := g.Open(nil, b[1], b[3], b[2])
		if err != nil {
			return vm.NIL, nil
		}
		return byteVec(pt), nil
	})
	bytes1(ns, "ed25519-public-key", func(seed []byte) ([]byte, error) {
		k, err := edKey(seed)
		if err != nil {
			return nil, err
		}
		return k.Public().(ed25519.PublicKey), nil
	})
	bytesN(ns, "ed25519-sign", 2, func(b [][]byte) (vm.Value, error) {
		k, err := edKey(b[0])
		if err != nil {
			return vm.NIL, err
		}
		return byteVec(ed25519.Sign(k, b[1])), nil
	})
	bytesN(ns, "ed25519-verify", 3, func(b [][]byte) (vm.Value, error) {
		if len(b[0]) != ed25519.PublicKeySize || len(b[2]) != ed25519.SignatureSize {
			return vm.FALSE, nil
		}
		return vm.Boolean(ed25519.Verify(ed25519.PublicKey(b[0]), b[1], b[2])), nil
	})
	bytesN(ns, "x25519", 2, func(b [][]byte) (vm.Value, error) {
		k, err := ecdh.X25519().NewPrivateKey(b[0])
		if err != nil {
			return vm.NIL, err
		}
		p, err := ecdh.X25519().NewPublicKey(b[1])
		if err != nil {
			return vm.NIL, err
		}
		s, err := k.ECDH(p)
		if err != nil {
			return vm.NIL, err
		}
		return byteVec(s), nil
	})
	def(ns, "random-bytes", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("random-bytes", vs, 1); err != nil {
			return vm.NIL, err
		}
		n, err := intOf(vs[0], "n")
		if err != nil {
			return vm.NIL, err
		}
		b := make([]byte, n)
		if _, err := rand.Read(b); err != nil {
			return vm.NIL, err
		}
		return byteVec(b), nil
	})

	// ── big integers (eve.net.bigops' ops, Go's math/big) ──────────────────────
	def(ns, "big-from-be", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("big-from-be", vs, 1); err != nil {
			return vm.NIL, err
		}
		b, err := anyBytes(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		return vm.NewBigInt(new(big.Int).SetBytes(b)), nil
	})
	def(ns, "big-to-be", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("big-to-be", vs, 2); err != nil {
			return vm.NIL, err
		}
		x, err := bigOf(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		n, err := intOf(vs[1], "len")
		if err != nil {
			return vm.NIL, err
		}
		b := x.Bytes()
		if int64(len(b)) > n {
			b = b[int64(len(b))-n:]
		}
		out := make([]byte, n)
		copy(out[n-int64(len(b)):], b)
		return byteVec(out), nil
	})
	big2 := func(name string, f func(z, a, b *big.Int) *big.Int) {
		def(ns, name, func(vs []vm.Value) (vm.Value, error) {
			if err := argc(name, vs, 2); err != nil {
				return vm.NIL, err
			}
			a, err := bigOf(vs[0])
			if err != nil {
				return vm.NIL, err
			}
			b, err := bigOf(vs[1])
			if err != nil {
				return vm.NIL, err
			}
			return vm.NewBigInt(f(new(big.Int), a, b)), nil
		})
	}
	big2("big-add", (*big.Int).Add)
	big2("big-sub", (*big.Int).Sub)
	big2("big-mul", (*big.Int).Mul)
	big2("big-quot", (*big.Int).Quo)
	big2("big-mod", (*big.Int).Mod)
	def(ns, "big-modpow", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("big-modpow", vs, 3); err != nil {
			return vm.NIL, err
		}
		xs := make([]*big.Int, 3)
		for i, v := range vs {
			x, err := bigOf(v)
			if err != nil {
				return vm.NIL, err
			}
			xs[i] = x
		}
		return vm.NewBigInt(new(big.Int).Exp(xs[0], xs[1], xs[2])), nil
	})
	def(ns, "big-int", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("big-int", vs, 1); err != nil {
			return vm.NIL, err
		}
		x, err := bigOf(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		return vm.NewBigInt(new(big.Int).Set(x)), nil
	})
	def(ns, "big-cmp", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("big-cmp", vs, 2); err != nil {
			return vm.NIL, err
		}
		a, err := bigOf(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		b, err := bigOf(vs[1])
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(a.Cmp(b)), nil
	})
	def(ns, "big-bit0", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("big-bit0", vs, 1); err != nil {
			return vm.NIL, err
		}
		a, err := bigOf(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(a.Bit(0)), nil
	})

	// ── encoding (eve.encoding, adapters/encoding/letgo) ───────────────────────
	def(ns, "utf8-bytes", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("utf8-bytes", vs, 1); err != nil {
			return vm.NIL, err
		}
		s, err := strOf(vs[0], "s")
		if err != nil {
			return vm.NIL, err
		}
		return vm.NewByteArrayFrom([]byte(s)), nil
	})
	def(ns, "utf8-string", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("utf8-string", vs, 3); err != nil {
			return vm.NIL, err
		}
		b, err := anyBytes(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		off, err := intOf(vs[1], "off")
		if err != nil {
			return vm.NIL, err
		}
		n, err := intOf(vs[2], "len")
		if err != nil {
			return vm.NIL, err
		}
		return vm.String(strings.ToValidUTF8(string(b[off:off+n]), "�")), nil
	})
	def(ns, "utf16-units", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("utf16-units", vs, 1); err != nil {
			return vm.NIL, err
		}
		s, err := strOf(vs[0], "s")
		if err != nil {
			return vm.NIL, err
		}
		u := utf16.Encode([]rune(s))
		out := make([]vm.Value, len(u))
		for i, c := range u {
			out[i] = vm.Int(c)
		}
		return vm.NewPersistentVector(out), nil
	})
	def(ns, "code-points->string", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("code-points->string", vs, 1); err != nil {
			return vm.NIL, err
		}
		var sb strings.Builder
		if sq, ok := vs[0].(vm.Sequable); ok {
			for s := sq.Seq(); s != nil && s != vm.EmptyList; s = s.Next() {
				i, ok := s.First().(vm.Int)
				if !ok {
					return vm.NIL, fmt.Errorf("eve.native: a code point must be an integer")
				}
				r := rune(i)
				if !utf8.ValidRune(r) {
					r = utf8.RuneError
				}
				sb.WriteRune(r)
			}
		}
		return vm.String(sb.String()), nil
	})
	def(ns, "long-bits->double", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("long-bits->double", vs, 1); err != nil {
			return vm.NIL, err
		}
		x, err := intOf(vs[0], "bits")
		if err != nil {
			return vm.NIL, err
		}
		return vm.Float(math.Float64frombits(uint64(x))), nil
	})
	def(ns, "double->long-bits", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("double->long-bits", vs, 1); err != nil {
			return vm.NIL, err
		}
		f, ok := vs[0].(vm.Float)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native: double->long-bits takes a double")
		}
		return vm.Int(int64(math.Float64bits(float64(f)))), nil
	})
	def(ns, "read-f64-le", func(vs []vm.Value) (vm.Value, error) {
		b, err := anyBytes(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		off, err := intOf(vs[1], "off")
		if err != nil {
			return vm.NIL, err
		}
		return vm.Float(math.Float64frombits(binary.LittleEndian.Uint64(b[off:]))), nil
	})
	def(ns, "read-f32-le", func(vs []vm.Value) (vm.Value, error) {
		b, err := anyBytes(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		off, err := intOf(vs[1], "off")
		if err != nil {
			return vm.NIL, err
		}
		return vm.Float(float64(math.Float32frombits(binary.LittleEndian.Uint32(b[off:])))), nil
	})
	def(ns, "f64-le-bytes", func(vs []vm.Value) (vm.Value, error) {
		f, ok := vs[0].(vm.Float)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native: f64-le-bytes takes a double")
		}
		b := make([]byte, 8)
		binary.LittleEndian.PutUint64(b, math.Float64bits(float64(f)))
		return vm.NewByteArrayFrom(b), nil
	})
	def(ns, "f32-le-bytes", func(vs []vm.Value) (vm.Value, error) {
		f, ok := vs[0].(vm.Float)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native: f32-le-bytes takes a double")
		}
		b := make([]byte, 4)
		binary.LittleEndian.PutUint32(b, math.Float32bits(float32(f)))
		return vm.NewByteArrayFrom(b), nil
	})
	bytesN(ns, "bytes=", 2, func(b [][]byte) (vm.Value, error) { return vm.Boolean(bytes.Equal(b[0], b[1])), nil })
	bytesN(ns, "bytes->b64", 1, func(b [][]byte) (vm.Value, error) {
		return vm.String(base64.StdEncoding.EncodeToString(b[0])), nil
	})
	bytesN(ns, "bytes->hex", 1, func(b [][]byte) (vm.Value, error) { return vm.String(hex.EncodeToString(b[0])), nil })
	def(ns, "b64->bytes", func(vs []vm.Value) (vm.Value, error) {
		s, err := strOf(vs[0], "b64")
		if err != nil {
			return vm.NIL, err
		}
		// characters outside the alphabet are skipped, as the portable reference does
		clean := strings.Map(func(r rune) rune {
			if (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '+' || r == '/' {
				return r
			}
			return -1
		}, s)
		b, err := base64.RawStdEncoding.DecodeString(clean)
		if err != nil {
			return vm.NIL, err
		}
		return byteVec(b), nil
	})
	def(ns, "hex->bytes", func(vs []vm.Value) (vm.Value, error) {
		s, err := strOf(vs[0], "hex")
		if err != nil {
			return vm.NIL, err
		}
		b, err := hex.DecodeString(s)
		if err != nil {
			return vm.NIL, err
		}
		return byteVec(b), nil
	})
	def(ns, "byte-array-of", func(vs []vm.Value) (vm.Value, error) {
		b, err := anyBytes(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		return vm.NewByteArrayFrom(append([]byte(nil), b...)), nil
	})

	// ── process (eve.process, adapters/process/letgo) ──────────────────────────
	def(ns, "stdin-line", func(vs []vm.Value) (vm.Value, error) {
		stdinOnce.Do(func() { stdin = bufio.NewReaderSize(os.Stdin, 1<<16) })
		l, err := stdin.ReadString('\n')
		if err != nil && (err != io.EOF || l == "") {
			return vm.NIL, nil
		}
		return vm.String(strings.TrimSuffix(strings.TrimSuffix(l, "\n"), "\r")), nil
	})
	def(ns, "now-ms", func(vs []vm.Value) (vm.Value, error) { return vm.Int(time.Now().UnixMilli()), nil })
	def(ns, "sleep-ms", func(vs []vm.Value) (vm.Value, error) {
		ms, err := intOf(vs[0], "ms")
		if err != nil {
			return vm.NIL, err
		}
		time.Sleep(time.Duration(ms) * time.Millisecond)
		return vm.NIL, nil
	})

	// ── http (eve.http, adapters/http/letgo) ───────────────────────────────────
	// (http-request method url headers body as) -> {:status :headers :body}: body nil, a string or bytes; as "b64"
	// answers the body base64, "bytes" a byte-array, anything else UTF-8 text. A transport failure answers {:error}.
	client := &http.Client{}
	def(ns, "http-request", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("http-request", vs, 5); err != nil {
			return vm.NIL, err
		}
		method, err := strOf(vs[0], "method")
		if err != nil {
			return vm.NIL, err
		}
		url, err := strOf(vs[1], "url")
		if err != nil {
			return vm.NIL, err
		}
		var body io.Reader
		if vs[3] != vm.NIL {
			b, err := anyBytes(vs[3])
			if err != nil {
				return vm.NIL, err
			}
			body = bytes.NewReader(b)
		}
		req, err := http.NewRequest(method, url, body)
		if err != nil {
			return vm.EmptyPersistentMap.Assoc(kw("error"), vm.String(err.Error())), nil
		}
		if sq, ok := vs[2].(vm.Sequable); ok && vs[2] != vm.NIL {
			for s := sq.Seq(); s != nil && s != vm.EmptyList; s = s.Next() {
				e, ok := s.First().(vm.Sequable)
				if !ok {
					continue
				}
				es := e.Seq()
				k, v := es.First(), es.Next().First()
				ks := k.String()
				if kk, ok := k.(vm.Keyword); ok {
					ks = string(kk)
				} else if sk, ok := k.(vm.String); ok {
					ks = string(sk)
				}
				vstr := v.String()
				if sv, ok := v.(vm.String); ok {
					vstr = string(sv)
				}
				req.Header.Set(ks, vstr)
			}
		}
		resp, err := client.Do(req)
		if err != nil {
			return vm.EmptyPersistentMap.Assoc(kw("error"), vm.String(err.Error())), nil
		}
		defer resp.Body.Close()
		rb, err := io.ReadAll(resp.Body)
		if err != nil {
			return vm.EmptyPersistentMap.Assoc(kw("error"), vm.String(err.Error())), nil
		}
		hs := vm.Associative(vm.EmptyPersistentMap)
		for k, v := range resp.Header {
			hs = hs.Assoc(vm.String(strings.ToLower(k)), vm.String(strings.Join(v, ",")))
		}
		var bv vm.Value
		as, _ := vs[4].(vm.String)
		switch string(as) {
		case "b64":
			bv = vm.String(base64.StdEncoding.EncodeToString(rb))
		case "bytes":
			bv = vm.NewByteArrayFrom(rb)
		default:
			bv = vm.String(string(rb))
		}
		return vm.EmptyPersistentMap.Assoc(kw("status"), vm.Int(resp.StatusCode)).
			Assoc(kw("headers"), hs).Assoc(kw("body"), bv), nil
	})

	rt.RegisterNS(ns)
}
