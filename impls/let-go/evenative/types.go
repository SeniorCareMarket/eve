package evenative

// Lazy types whose state lives in a byte region (the shape
// of EVE's slab-backed values: nothing is decoded until a method asks), written
// in Go against let-go's value interfaces (pkg/vm/value.go: Lookup,
// Associative, Counted, Collection, Sequable, Seq, Indexed, Keyed, Reference).
// Every method appends "<Type>.<Method>" to a call log, which shows which
// clojure.core fns dispatch to the type. Elements are i32 (vm.Int).
//
// Layout of every region: [count i32 LE][e0 i32 LE][e1]... ; a map stores
// k0 v0 k1 v1 ...; a list region is viewed from element index `at`.

import (
	"encoding/binary"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"unsafe"

	"github.com/nooga/let-go/pkg/vm"
)

var (
	logMu   sync.Mutex
	callLog []vm.Value
)

func logc(s string) {
	logMu.Lock()
	callLog = append(callLog, vm.String(s))
	logMu.Unlock()
}

type slabType struct{ name string }

func (t *slabType) String() string     { return t.name }
func (t *slabType) Type() vm.ValueType { return vm.TypeType }
func (t *slabType) Unbox() any         { return t }
func (t *slabType) Name() string       { return t.name }
func (t *slabType) Box(any) (vm.Value, error) {
	return vm.NIL, fmt.Errorf("%s cannot box a Go value", t.name)
}

var (
	slabMapType  = &slabType{"eve.native.SlabMap"}
	slabVecType  = &slabType{"eve.native.SlabVec"}
	slabSetType  = &slabType{"eve.native.SlabSet"}
	slabListType = &slabType{"eve.native.SlabList"}
	slabAtomType = &slabType{"eve.native.SlabAtom"}
)

// ---- region helpers -------------------------------------------------------

func rd(b []byte, i int) int32     { return int32(binary.LittleEndian.Uint32(b[4*i:])) }
func wr(b []byte, i int, v int32)  { binary.LittleEndian.PutUint32(b[4*i:], uint32(v)) }
func cnt(b []byte) int             { return int(rd(b, 0)) }
func region(words int) []byte      { return make([]byte, 4*(words+1)) }
func grow(b []byte, extra int) []byte {
	n := make([]byte, len(b)+4*extra)
	copy(n, b)
	return n
}

func i32(v vm.Value) (int32, bool) {
	i, ok := v.(vm.Int)
	return int32(i), ok
}

func elems(b []byte, from, step, n int) []vm.Value {
	out := make([]vm.Value, 0, n)
	for i := 0; i < n; i++ {
		out = append(out, vm.Int(rd(b, 1+from+i*step)))
	}
	return out
}

func seqOfValues(vs []vm.Value) vm.Seq {
	if len(vs) == 0 {
		return vm.EmptyList
	}
	return vm.NewList(vs).(vm.Seq)
}

func show(open string, vs []vm.Value, close string) string {
	parts := make([]string, len(vs))
	for i, v := range vs {
		parts[i] = v.String()
	}
	return open + strings.Join(parts, " ") + close
}

// ---- map ------------------------------------------------------------------

type SlabMap struct{ b []byte }

func (m *SlabMap) find(k vm.Value) int {
	kk, ok := i32(k)
	if !ok {
		return -1
	}
	for i := 0; i < cnt(m.b); i++ {
		if rd(m.b, 1+2*i) == kk {
			return i
		}
	}
	return -1
}
func (m *SlabMap) Type() vm.ValueType { return slabMapType }
func (m *SlabMap) Unbox() any         { return m }
func (m *SlabMap) String() string {
	vs := elems(m.b, 0, 1, 2*cnt(m.b))
	return show("#eve/slab-map{", vs, "}")
}
func (m *SlabMap) ValueAt(k vm.Value) vm.Value {
	logc("SlabMap.ValueAt")
	return m.lookup(k, vm.NIL)
}
func (m *SlabMap) ValueAtOr(k, d vm.Value) vm.Value {
	logc("SlabMap.ValueAtOr")
	return m.lookup(k, d)
}
func (m *SlabMap) lookup(k, d vm.Value) vm.Value {
	if i := m.find(k); i >= 0 {
		return vm.Int(rd(m.b, 2+2*i))
	}
	return d
}
func (m *SlabMap) assoc(k, v vm.Value) *SlabMap {
	kk, ok1 := i32(k)
	vv, ok2 := i32(v)
	if !ok1 || !ok2 {
		return nil
	}
	if i := m.find(k); i >= 0 {
		n := grow(m.b, 0)
		wr(n, 2+2*i, vv)
		return &SlabMap{n}
	}
	c := cnt(m.b)
	n := grow(m.b, 2)
	wr(n, 1+2*c, kk)
	wr(n, 2+2*c, vv)
	wr(n, 0, int32(c+1))
	return &SlabMap{n}
}
func (m *SlabMap) Assoc(k, v vm.Value) vm.Associative {
	logc("SlabMap.Assoc")
	if r := m.assoc(k, v); r != nil {
		return r
	}
	return m // non-integer key or value: unchanged (these types store i32 only)
}
func (m *SlabMap) Dissoc(k vm.Value) vm.Associative {
	logc("SlabMap.Dissoc")
	i := m.find(k)
	if i < 0 {
		return m
	}
	c := cnt(m.b)
	n := region(2 * (c - 1))
	j := 0
	for x := 0; x < c; x++ {
		if x == i {
			continue
		}
		wr(n, 1+2*j, rd(m.b, 1+2*x))
		wr(n, 2+2*j, rd(m.b, 2+2*x))
		j++
	}
	wr(n, 0, int32(c-1))
	return &SlabMap{n}
}
func (m *SlabMap) RawCount() int   { logc("SlabMap.RawCount"); return cnt(m.b) }
func (m *SlabMap) Count() vm.Value { logc("SlabMap.Count"); return vm.Int(cnt(m.b)) }
func (m *SlabMap) Seq() vm.Seq {
	logc("SlabMap.Seq")
	c := cnt(m.b)
	es := make([]vm.Value, c)
	for i := 0; i < c; i++ {
		es[i] = vm.MapEntry{Key: vm.Int(rd(m.b, 1+2*i)), Value: vm.Int(rd(m.b, 2+2*i))}
	}
	return seqOfValues(es)
}
func (m *SlabMap) Contains(k vm.Value) vm.Boolean {
	logc("SlabMap.Contains")
	return vm.Boolean(m.find(k) >= 0)
}
func (m *SlabMap) Empty() vm.Collection { logc("SlabMap.Empty"); return &SlabMap{region(0)} }
func (m *SlabMap) Conj(e vm.Value) vm.Collection {
	logc("SlabMap.Conj")
	var k, v vm.Value
	switch p := e.(type) {
	case vm.MapEntry:
		k, v = p.Key, p.Value
	case vm.ArrayVector:
		if len(p) != 2 {
			return m
		}
		k, v = p[0], p[1]
	default:
		return m
	}
	if r := m.assoc(k, v); r != nil {
		return r
	}
	return m
}

// ---- vector ---------------------------------------------------------------

type SlabVec struct{ b []byte }

func (v *SlabVec) Type() vm.ValueType { return slabVecType }
func (v *SlabVec) Unbox() any         { return v }
func (v *SlabVec) String() string     { return show("#eve/slab-vec[", elems(v.b, 0, 1, cnt(v.b)), "]") }
func (v *SlabVec) Nth(i int) vm.Value {
	logc("SlabVec.Nth")
	return vm.Int(rd(v.b, 1+i))
}
func (v *SlabVec) RawCount() int   { logc("SlabVec.RawCount"); return cnt(v.b) }
func (v *SlabVec) Count() vm.Value { logc("SlabVec.Count"); return vm.Int(cnt(v.b)) }
func (v *SlabVec) Seq() vm.Seq {
	logc("SlabVec.Seq")
	return seqOfValues(elems(v.b, 0, 1, cnt(v.b)))
}
func (v *SlabVec) Empty() vm.Collection { logc("SlabVec.Empty"); return &SlabVec{region(0)} }
func (v *SlabVec) Conj(x vm.Value) vm.Collection {
	logc("SlabVec.Conj")
	xx, ok := i32(x)
	if !ok {
		return v
	}
	c := cnt(v.b)
	n := grow(v.b, 1)
	wr(n, 1+c, xx)
	wr(n, 0, int32(c+1))
	return &SlabVec{n}
}

// Lookup (get v i) and Fn ((v i)) on the vector; Fn ((m k)) on the map.
func (v *SlabVec) ValueAt(i vm.Value) vm.Value {
	logc("SlabVec.ValueAt")
	return v.at(i, vm.NIL)
}
func (v *SlabVec) ValueAtOr(i, d vm.Value) vm.Value {
	logc("SlabVec.ValueAtOr")
	return v.at(i, d)
}
func (v *SlabVec) at(i, d vm.Value) vm.Value {
	ii, ok := i.(vm.Int)
	if !ok || ii < 0 || int(ii) >= cnt(v.b) {
		return d
	}
	return vm.Int(rd(v.b, 1+int(ii)))
}
func (v *SlabVec) Invoke(args []vm.Value) (vm.Value, error) {
	logc("SlabVec.Invoke")
	if len(args) != 1 {
		return vm.NIL, fmt.Errorf("SlabVec invoked with %d args", len(args))
	}
	ii, ok := args[0].(vm.Int)
	if !ok || ii < 0 || int(ii) >= cnt(v.b) {
		return vm.NIL, fmt.Errorf("index out of bounds")
	}
	return vm.Int(rd(v.b, 1+int(ii))), nil
}
func (v *SlabVec) Arity() int { return 1 }
func (m *SlabMap) Invoke(args []vm.Value) (vm.Value, error) {
	logc("SlabMap.Invoke")
	switch len(args) {
	case 1:
		return m.lookup(args[0], vm.NIL), nil
	case 2:
		return m.lookup(args[0], args[1]), nil
	}
	return vm.NIL, fmt.Errorf("SlabMap invoked with %d args", len(args))
}
func (m *SlabMap) Arity() int { return -1 }

// ---- set ------------------------------------------------------------------

type SlabSet struct{ b []byte }

func (s *SlabSet) has(x vm.Value) int {
	xx, ok := i32(x)
	if !ok {
		return -1
	}
	for i := 0; i < cnt(s.b); i++ {
		if rd(s.b, 1+i) == xx {
			return i
		}
	}
	return -1
}
func (s *SlabSet) Type() vm.ValueType { return slabSetType }
func (s *SlabSet) Unbox() any         { return s }
func (s *SlabSet) String() string     { return show("#eve/slab-set#{", elems(s.b, 0, 1, cnt(s.b)), "}") }
func (s *SlabSet) RawCount() int      { logc("SlabSet.RawCount"); return cnt(s.b) }
func (s *SlabSet) Count() vm.Value    { logc("SlabSet.Count"); return vm.Int(cnt(s.b)) }
func (s *SlabSet) Seq() vm.Seq {
	logc("SlabSet.Seq")
	return seqOfValues(elems(s.b, 0, 1, cnt(s.b)))
}
func (s *SlabSet) Contains(x vm.Value) vm.Boolean {
	logc("SlabSet.Contains")
	return vm.Boolean(s.has(x) >= 0)
}
func (s *SlabSet) Empty() vm.Collection { logc("SlabSet.Empty"); return &SlabSet{region(0)} }
func (s *SlabSet) Conj(x vm.Value) vm.Collection {
	logc("SlabSet.Conj")
	xx, ok := i32(x)
	if !ok || s.has(x) >= 0 {
		return s
	}
	c := cnt(s.b)
	n := grow(s.b, 1)
	wr(n, 1+c, xx)
	wr(n, 0, int32(c+1))
	return &SlabSet{n}
}

// Disj has the shape a set-removal interface would call; stock let-go's
// `disj` never reaches it (a type switch on its own set types).
func (s *SlabSet) Disj(x vm.Value) vm.Value {
	logc("SlabSet.Disj")
	i := s.has(x)
	if i < 0 {
		return s
	}
	c := cnt(s.b)
	n := region(c - 1)
	j := 0
	for k := 0; k < c; k++ {
		if k != i {
			wr(n, 1+j, rd(s.b, 1+k))
			j++
		}
	}
	wr(n, 0, int32(c-1))
	return &SlabSet{n}
}

// ---- list -----------------------------------------------------------------

type SlabList struct {
	b  []byte
	at int
}

func (l *SlabList) left() int          { return cnt(l.b) - l.at }
func (l *SlabList) Type() vm.ValueType { return slabListType }
func (l *SlabList) Unbox() any         { return l }
func (l *SlabList) String() string     { return show("#eve/slab-list(", elems(l.b, l.at, 1, l.left()), ")") }
func (l *SlabList) First() vm.Value {
	logc("SlabList.First")
	if l.left() <= 0 {
		return vm.NIL
	}
	return vm.Int(rd(l.b, 1+l.at))
}
func (l *SlabList) More() vm.Seq {
	logc("SlabList.More")
	if l.left() <= 1 {
		return vm.EmptyList
	}
	return &SlabList{l.b, l.at + 1}
}
func (l *SlabList) Next() vm.Seq {
	logc("SlabList.Next")
	if l.left() <= 1 {
		return nil
	}
	return &SlabList{l.b, l.at + 1}
}
func (l *SlabList) Cons(x vm.Value) vm.Seq {
	logc("SlabList.Cons")
	return vm.NewCons(x, l)
}
func (l *SlabList) Seq() vm.Seq {
	logc("SlabList.Seq")
	if l.left() <= 0 {
		return vm.EmptyList
	}
	return l
}
func (l *SlabList) RawCount() int   { logc("SlabList.RawCount"); return l.left() }
func (l *SlabList) Count() vm.Value { logc("SlabList.Count"); return vm.Int(l.left()) }

// ---- atom -----------------------------------------------------------------

// SlabAtom keeps its state in a 4-byte region word; every write is a CAS on
// that word (the shape of EVE's atom root). Swap/Reset/CompareAndSet carry the
// same signatures as *vm.Atom's, so an interface over those methods (the
// proposal-let-go.patch) would reach them; stock let-go does not.
type SlabAtom struct{ b []byte }

func (a *SlabAtom) p() *int32         { return (*int32)(unsafe.Pointer(&a.b[0])) }
func (a *SlabAtom) Type() vm.ValueType { return slabAtomType }
func (a *SlabAtom) Unbox() any         { return a }
func (a *SlabAtom) String() string     { return fmt.Sprintf("#eve/slab-atom[%d]", atomic.LoadInt32(a.p())) }
func (a *SlabAtom) Deref() vm.Value {
	logc("SlabAtom.Deref")
	return vm.Int(atomic.LoadInt32(a.p()))
}
func (a *SlabAtom) Swap(fn vm.Fn, args []vm.Value) (vm.Value, error) {
	logc("SlabAtom.Swap")
	for {
		old := atomic.LoadInt32(a.p())
		nv, err := fn.Invoke(append([]vm.Value{vm.Int(old)}, args...))
		if err != nil {
			return vm.NIL, err
		}
		n, ok := i32(nv)
		if !ok {
			return vm.NIL, fmt.Errorf("SlabAtom holds i32 only")
		}
		if atomic.CompareAndSwapInt32(a.p(), old, n) {
			return nv, nil
		}
	}
}
func (a *SlabAtom) Reset(v vm.Value) (vm.Value, error) {
	logc("SlabAtom.Reset")
	n, ok := i32(v)
	if !ok {
		return vm.NIL, fmt.Errorf("SlabAtom holds i32 only")
	}
	atomic.StoreInt32(a.p(), n)
	return v, nil
}
func (a *SlabAtom) CompareAndSet(old, nv vm.Value) (bool, error) {
	logc("SlabAtom.CompareAndSet")
	o, ok1 := i32(old)
	n, ok2 := i32(nv)
	if !ok1 || !ok2 {
		return false, nil
	}
	return atomic.CompareAndSwapInt32(a.p(), o, n), nil
}

// ---- constructors + log ---------------------------------------------------

func fromInts(vs []vm.Value) ([]byte, error) {
	b := region(len(vs))
	for i, v := range vs {
		x, ok := i32(v)
		if !ok {
			return nil, fmt.Errorf("eve.native: slab types hold integers, got %s", v.Type().Name())
		}
		wr(b, 1+i, x)
	}
	wr(b, 0, int32(len(vs)))
	return b, nil
}

func installTypes(ns *vm.Namespace) {
	// (slab-map k v ...), (slab-vec x ...), (slab-set x ...), (slab-list x ...)
	def(ns, "slab-map", func(vs []vm.Value) (vm.Value, error) {
		if len(vs)%2 != 0 {
			return vm.NIL, fmt.Errorf("eve.native/slab-map expects key/value pairs")
		}
		b, err := fromInts(vs)
		if err != nil {
			return vm.NIL, err
		}
		wr(b, 0, int32(len(vs)/2))
		return &SlabMap{b}, nil
	})
	def(ns, "slab-vec", func(vs []vm.Value) (vm.Value, error) {
		b, err := fromInts(vs)
		if err != nil {
			return vm.NIL, err
		}
		return &SlabVec{b}, nil
	})
	def(ns, "slab-set", func(vs []vm.Value) (vm.Value, error) {
		b, err := fromInts(vs)
		if err != nil {
			return vm.NIL, err
		}
		return &SlabSet{b}, nil
	})
	def(ns, "slab-list", func(vs []vm.Value) (vm.Value, error) {
		b, err := fromInts(vs)
		if err != nil {
			return vm.NIL, err
		}
		return &SlabList{b, 0}, nil
	})
	def(ns, "slab-atom", func(vs []vm.Value) (vm.Value, error) {
		if len(vs) != 1 {
			return vm.NIL, fmt.Errorf("eve.native/slab-atom expects 1 arg")
		}
		x, ok := i32(vs[0])
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native/slab-atom holds an integer")
		}
		a := &SlabAtom{make([]byte, 8)}
		atomic.StoreInt32(a.p(), x)
		return a, nil
	})
	// (take-log!) -> the calls logged since the last take, as a vector of
	// strings; clears the log.
	def(ns, "take-log!", func(vs []vm.Value) (vm.Value, error) {
		logMu.Lock()
		out := vm.ArrayVector(callLog)
		callLog = nil
		logMu.Unlock()
		if out == nil {
			out = vm.ArrayVector{}
		}
		return out, nil
	})
}
