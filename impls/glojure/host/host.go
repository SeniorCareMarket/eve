// Package host is what eve's glojure adapters take from Go where glj code
// would be a byte-at-a-time loop or cannot reach at all: UTF-8 between Go
// strings and glojure's byte arrays ([]int8), array copies, little-endian
// floats and float arrays, IEEE-754 bits as a signed long, a goroutine's
// identity, an object's identity hash (eve.encoding and eve.process on
// glojure: eve/adapters/encoding/go, eve/adapters/process/go). Compiled into
// glj-eve with the region package (impls/glojure/build.sh).
//
// glojure's arrays: byte-array is []int8, short-array []int16, int-array
// []int, long-array []int64, float-array []float32, double-array []float64,
// object-array []any. Every integer parameter is int64 (glj's integers).
package host

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"math"
	"math/bits"
	"os"
	"reflect"
	"strings"
	"unsafe"

	"github.com/glojurelang/glojure/pkg/lang"
)

func asBytes(b []int8) []byte {
	if len(b) == 0 {
		return nil
	}
	return unsafe.Slice((*byte)(unsafe.Pointer(unsafe.SliceData(b))), len(b))
}

func asInt8s(b []byte) []int8 {
	out := make([]int8, len(b))
	copy(asBytes(out), b)
	return out
}

// ByteSlice is the glojure byte array b as Go's []byte, the same memory (what
// net/http sends: eve's http feature, :body-as :bytes).
func ByteSlice(b []int8) []byte { return asBytes(b) }

// Int8Slice is Go's []byte b as a glojure byte array ([]int8), the same memory
// (a response body, :as :bytes — eve.codec reads it as it came).
func Int8Slice(b []byte) []int8 {
	if len(b) == 0 {
		return []int8{}
	}
	return unsafe.Slice((*int8)(unsafe.Pointer(unsafe.SliceData(b))), len(b))
}

// Utf8Bytes is s's UTF-8 bytes (a Go string holds them already).
func Utf8Bytes(s string) []int8 { return asInt8s([]byte(s)) }

// Utf8String is the string the n UTF-8 bytes of b from off encode.
func Utf8String(b []int8, off, n int64) string { return string(asBytes(b)[off : off+n]) }

// ArrayCopy copies n elements of slice src from soff into slice dst at doff
// (System/arraycopy: any two slices of one element type).
func ArrayCopy(src any, soff int64, dst any, doff int64, n int64) {
	if n <= 0 {
		return
	}
	reflect.Copy(reflect.ValueOf(dst).Slice(int(doff), int(doff+n)),
		reflect.ValueOf(src).Slice(int(soff), int(soff+n)))
}

// BytesEqual: two byte arrays equal, byte for byte.
func BytesEqual(a, b []int8) bool { return bytes.Equal(asBytes(a), asBytes(b)) }

// IsArray: x is a Go slice or array (a host array: glojure's arrays are
// slices), and no string.
func IsArray(x any) bool {
	if x == nil {
		return false
	}
	k := reflect.TypeOf(x).Kind()
	return k == reflect.Slice || k == reflect.Array
}

// DoubleToLongBits is d's IEEE-754 bits as a signed long (NaN payloads kept);
// LongBitsToDouble the inverse.
func DoubleToLongBits(d float64) int64 { return int64(math.Float64bits(d)) }
func LongBitsToDouble(x int64) float64 { return math.Float64frombits(uint64(x)) }

// ReadF32LE / ReadF64LE: the float at byte off of b (little-endian), as a
// float64.
func ReadF32LE(b []int8, off int64) float64 {
	return float64(math.Float32frombits(binary.LittleEndian.Uint32(asBytes(b)[off:])))
}
func ReadF64LE(b []int8, off int64) float64 {
	return math.Float64frombits(binary.LittleEndian.Uint64(asBytes(b)[off:]))
}

// F32LEBytes / F64LEBytes: v's 4 / 8 little-endian bytes as a float32 /
// float64.
func F32LEBytes(v float64) []int8 {
	out := make([]int8, 4)
	binary.LittleEndian.PutUint32(asBytes(out), math.Float32bits(float32(v)))
	return out
}
func F64LEBytes(v float64) []int8 {
	out := make([]int8, 8)
	binary.LittleEndian.PutUint64(asBytes(out), math.Float64bits(v))
	return out
}

// F64sLE / I32sLE: the n float64s / int32s little-endian in b from byte
// off, as glojure's double-array / int-array.
func F64sLE(b []int8, off, n int64) []float64 {
	bs, out := asBytes(b)[off:], make([]float64, n)
	for i := range out {
		out[i] = math.Float64frombits(binary.LittleEndian.Uint64(bs[8*i:]))
	}
	return out
}
func I32sLE(b []int8, off, n int64) []int {
	bs, out := asBytes(b)[off:], make([]int, n)
	for i := range out {
		out[i] = int(int32(binary.LittleEndian.Uint32(bs[4*i:])))
	}
	return out
}

// The little-endian bytes of glojure's typed arrays, element by element.
func F64sToBytesLE(a []float64) []int8 {
	out := make([]int8, 8*len(a))
	bs := asBytes(out)
	for i, v := range a {
		binary.LittleEndian.PutUint64(bs[8*i:], math.Float64bits(v))
	}
	return out
}
func F32sToBytesLE(a []float32) []int8 {
	out := make([]int8, 4*len(a))
	bs := asBytes(out)
	for i, v := range a {
		binary.LittleEndian.PutUint32(bs[4*i:], math.Float32bits(v))
	}
	return out
}
func I32sToBytesLE(a []int) []int8 {
	out := make([]int8, 4*len(a))
	bs := asBytes(out)
	for i, v := range a {
		binary.LittleEndian.PutUint32(bs[4*i:], uint32(int32(v)))
	}
	return out
}
func I16sToBytesLE(a []int16) []int8 {
	out := make([]int8, 2*len(a))
	bs := asBytes(out)
	for i, v := range a {
		binary.LittleEndian.PutUint16(bs[2*i:], uint16(v))
	}
	return out
}

// GoroutineID is the calling goroutine's id: the identity eve keeps a
// thread's pinned epochs and a buffer's owner under (glojure's own, which its
// thread bindings use: lang.GoroutineID, exported by eve's glj-eve build,
// adapters/completion/glojure/go/goroutine-id.patch).
func GoroutineID() int64 { return lang.GoroutineID() }

// stdin is the process's one reader of standard input, so no line is split between two buffers.
var stdin = bufio.NewReader(os.Stdin)

// StdinLine is the next line of standard input, its line end dropped, and false at the end of the input — the
// process feature's read-line (eve.process on glojure: glj-eve's imports hold no bufio).
func StdinLine() (string, bool) {
	s, err := stdin.ReadString('\n')
	if err != nil && s == "" {
		return "", false
	}
	return strings.TrimRight(s, "\r\n"), true
}

// IdentityHash is x's identity: its address for a reference (a pointer, map,
// slice, func, chan), else 0 — a value with no address is told apart by
// identical? among the values hashing alike.
func IdentityHash(x any) int64 {
	if x == nil {
		return 0
	}
	v := reflect.ValueOf(x)
	switch v.Kind() {
	case reflect.Pointer, reflect.Map, reflect.Slice, reflect.Func, reflect.Chan, reflect.UnsafePointer:
		return int64(v.Pointer())
	}
	return 0
}

// Clojure's collection hashes (Murmur3's mixCollHash over the elements'
// hasheq: what glojure's own maps and sets hash by, in its internal murmur3
// package, which no other module can import): glojure's
// hash-ordered-coll, hash-unordered-coll and mix-collection-hash refer to
// a Murmur3 class it lacks (v0.7.22). uint32, as glojure's hash answers.
func MixCollHash(hash, count int64) uint32 { return mixColl(uint32(hash), uint32(count)) }

func HashOrdered(coll any) uint32 {
	var n, h uint32 = 0, 1
	for s := lang.Seq(coll); s != nil; s = s.Next() {
		h = 31*h + lang.HashEq(s.First())
		n++
	}
	return mixColl(h, n)
}

func HashUnordered(coll any) uint32 {
	var n, h uint32
	for s := lang.Seq(coll); s != nil; s = s.Next() {
		h += lang.HashEq(s.First())
		n++
	}
	return mixColl(h, n)
}

func mixColl(hash, count uint32) uint32 {
	k1 := hash * 0xcc9e2d51
	k1 = bits.RotateLeft32(k1, 15)
	k1 *= 0x1b873593
	h1 := uint32(0) ^ k1
	h1 = bits.RotateLeft32(h1, 13)
	h1 = h1*5 + 0xe6546b64
	h1 ^= count
	h1 ^= h1 >> 16
	h1 *= 0x85ebca6b
	h1 ^= h1 >> 13
	h1 *= 0xc2b2ae35
	h1 ^= h1 >> 16
	return h1
}
