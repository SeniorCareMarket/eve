// Package types is the Go side of eve's types on glojure (eve's types adapter,
// eve/adapters/types/glojure; its completion, adapters/completion/glojure):
// a type that stands in for a map — eve's slab map, as babashka's map-mode
// deftype does — is a MapRecord, a Go value glojure's core takes as a map. Its
// map methods (count, lookup, assoc, dissoc, seq, equiv, hasheq, conj, empty,
// meta, invoke) call the glj functions the types adapter names
// (SetMethods: eve.core's protocol functions, which dispatch on the value's
// class to the type's own impls). It is a glojure RecordValue whose record
// type has no fields, so (class x) is the type's own for protocol dispatch and
// no key lookup reads a field; its fields are read by name, (.-f x), through
// lang.FieldOrMethodResolver.
//
// (new T ...) and ->T make one: Define registers the record type's factory
// with glojure (lang.SetRecordFactory, which eve's glj-eve build adds:
// adapters/completion/glojure/go/record-factory.patch).
package types

import (
	"fmt"
	"sync"
	"sync/atomic"

	"github.com/glojurelang/glojure/pkg/lang"
)

// Methods are the glj functions a MapRecord's map methods call, each with
// the MapRecord first.
type Methods struct {
	Count, Lookup, Assoc, Dissoc, Seq, Equiv, Hash, Conj, Empty, Meta, WithMeta, Invoke, Str lang.IFn
}

var methods atomic.Pointer[Methods]

// SetMethods names them: m maps :count :lookup :assoc :dissoc :seq :equiv
// :hash :conj :empty :meta :with-meta :invoke :str to functions.
func SetMethods(m lang.ILookup) {
	f := func(k string) lang.IFn {
		v, _ := m.ValAt(lang.NewKeyword(k)).(lang.IFn)
		if v == nil {
			panic(fmt.Sprintf("eveglj/types: SetMethods: no :%s", k))
		}
		return v
	}
	methods.Store(&Methods{
		Count: f("count"), Lookup: f("lookup"), Assoc: f("assoc"), Dissoc: f("dissoc"), Seq: f("seq"),
		Equiv: f("equiv"), Hash: f("hash"), Conj: f("conj"), Empty: f("empty"), Meta: f("meta"),
		WithMeta: f("with-meta"), Invoke: f("invoke"), Str: f("str"),
	})
}

func ms() *Methods {
	m := methods.Load()
	if m == nil {
		panic("eveglj/types: SetMethods has not been called")
	}
	return m
}

type mapType struct {
	rt    *lang.RecordType
	names []string
	index map[string]int
}

var mapTypes sync.Map // *lang.RecordType -> *mapType

// Define makes record type rt a map type whose instances hold the fields
// names (a seq of strings, their order the constructor's), and registers its
// factory.
func Define(rt *lang.RecordType, names any) {
	t := &mapType{rt: rt, index: map[string]int{}}
	for s := lang.Seq(names); s != nil; s = s.Next() {
		t.index[lang.ToString(s.First())] = len(t.names)
		t.names = append(t.names, lang.ToString(s.First()))
	}
	mapTypes.Store(rt, t)
	lang.SetRecordFactory(rt, func(values ...any) any { return newMap(t, values) })
}

// New is an instance of map type rt with values as its fields.
func New(rt *lang.RecordType, values ...any) *MapRecord {
	t, ok := mapTypes.Load(rt)
	if !ok {
		panic(fmt.Sprintf("eveglj/types: %s is no map type", rt))
	}
	return newMap(t.(*mapType), values)
}

func newMap(t *mapType, values []any) *MapRecord {
	if len(values) != len(t.names) {
		panic(lang.NewIllegalArgumentError(fmt.Sprintf("wrong number of arguments for %s: expected %d, got %d",
			t.rt.FullName(), len(t.names), len(values))))
	}
	return &MapRecord{t: t, fields: append([]any(nil), values...)}
}

// MapRecord is an instance of a map type.
type MapRecord struct {
	lang.RecordMarker
	t      *mapType
	fields []any
}

var (
	_ lang.RecordValue = (*MapRecord)(nil)
	_ lang.Counted     = (*MapRecord)(nil)
	_ lang.IReduce     = (*MapRecord)(nil)
	_ lang.IReduceInit = (*MapRecord)(nil)
)

// ResolveFieldOrMethod reads a field by name: (.-f x).
func (r *MapRecord) ResolveFieldOrMethod(name string) (any, bool) {
	if i, ok := r.t.index[name]; ok {
		return r.fields[i], true
	}
	return nil, false
}

func (r *MapRecord) RecordType() *lang.RecordType { return r.t.rt }
func (r *MapRecord) RecordField(index int) any    { return r.fields[index] }

// RecordExtMap is the value itself: glojure's record paths read a key no
// field names (none here) from the record's extension map.
func (r *MapRecord) RecordExtMap() lang.IPersistentMap { return r }
func (r *MapRecord) RecordMeta() lang.IPersistentMap   { return r.Meta() }

func (r *MapRecord) RecordWithField(index int, value any) lang.RecordValue {
	fs := append([]any(nil), r.fields...)
	fs[index] = value
	return &MapRecord{t: r.t, fields: fs}
}

func (r *MapRecord) RecordWithExtMap(lang.IPersistentMap) lang.RecordValue {
	panic(lang.NewUnsupportedOperationError("eveglj/types: a map type has no extension map"))
}

func (r *MapRecord) RecordWithMeta(meta lang.IPersistentMap) lang.RecordValue {
	return r.WithMeta(meta).(lang.RecordValue)
}

func toInt(x any) int {
	switch v := x.(type) {
	case int:
		return v
	case int64:
		return int(v)
	case int32:
		return int(v)
	}
	n, _ := lang.AsInt(x)
	return n
}

func toU32(x any) uint32 {
	switch v := x.(type) {
	case uint32:
		return v
	case int:
		return uint32(v)
	case int64:
		return uint32(v)
	case int32:
		return uint32(v)
	}
	n, _ := lang.AsInt(x)
	return uint32(n)
}

var absent = new(int)

func (r *MapRecord) Count() int        { return toInt(ms().Count.Invoke(r)) }
func (r *MapRecord) ValAt(key any) any { return ms().Lookup.Invoke(r, key) }
func (r *MapRecord) ValAtDefault(key, fallback any) any {
	return ms().Lookup.Invoke(r, key, fallback)
}
func (r *MapRecord) ContainsKey(key any) bool { return ms().Lookup.Invoke(r, key, absent) != absent }

func (r *MapRecord) EntryAt(key any) lang.IMapEntry {
	v := ms().Lookup.Invoke(r, key, absent)
	if v == absent {
		return nil
	}
	return lang.NewMapEntry(key, v)
}

func (r *MapRecord) Assoc(key, val any) lang.Associative {
	return ms().Assoc.Invoke(r, key, val).(lang.Associative)
}

func (r *MapRecord) AssocEx(key, val any) lang.IPersistentMap {
	if r.ContainsKey(key) {
		panic(lang.NewIllegalArgumentError("key already present"))
	}
	return r.Assoc(key, val).(lang.IPersistentMap)
}

func (r *MapRecord) Without(key any) lang.IPersistentMap {
	return ms().Dissoc.Invoke(r, key).(lang.IPersistentMap)
}

func (r *MapRecord) Seq() lang.ISeq {
	s := ms().Seq.Invoke(r)
	if s == nil {
		return nil
	}
	return lang.Seq(s)
}

func (r *MapRecord) Cons(x any) lang.Conser { return ms().Conj.Invoke(r, x).(lang.Conser) }
func (r *MapRecord) Empty() lang.IPersistentCollection {
	return ms().Empty.Invoke(r).(lang.IPersistentCollection)
}
func (r *MapRecord) Equiv(other any) bool { return lang.IsTruthy(ms().Equiv.Invoke(r, other)) }
func (r *MapRecord) HashEq() uint32       { return toU32(ms().Hash.Invoke(r)) }
func (r *MapRecord) Hash() uint32         { return r.HashEq() }

func (r *MapRecord) Meta() lang.IPersistentMap {
	m, _ := ms().Meta.Invoke(r).(lang.IPersistentMap)
	return m
}

func (r *MapRecord) WithMeta(meta lang.IPersistentMap) any { return ms().WithMeta.Invoke(r, meta) }

func (r *MapRecord) Invoke(args ...any) any {
	return ms().Invoke.Invoke(append([]any{r}, args...)...)
}

func (r *MapRecord) ApplyTo(args lang.ISeq) any {
	as := []any{r}
	for s := args; s != nil; s = s.Next() {
		as = append(as, s.First())
	}
	return ms().Invoke.Invoke(as...)
}

func (r *MapRecord) String() string { return lang.ToString(ms().Str.Invoke(r)) }

// Reduce and ReduceInit walk the entries (glojure's reduce and transduce take
// an IReduce / IReduceInit before its CollReduce protocol, which has no case
// for a record type), stopping at a reduced value.
func (r *MapRecord) ReduceInit(f lang.IFn, init any) any {
	acc := init
	for s := r.Seq(); s != nil; s = s.Next() {
		acc = f.Invoke(acc, s.First())
		if rd, ok := acc.(*lang.Reduced); ok {
			return rd.Deref()
		}
	}
	return acc
}

func (r *MapRecord) Reduce(f lang.IFn) any {
	s := r.Seq()
	if s == nil {
		return f.Invoke()
	}
	acc := s.First()
	for s = s.Next(); s != nil; s = s.Next() {
		acc = f.Invoke(acc, s.First())
		if rd, ok := acc.(*lang.Reduced); ok {
			return rd.Deref()
		}
	}
	return acc
}
