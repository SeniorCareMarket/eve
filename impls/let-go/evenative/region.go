// Package evenative is what an EVE port to let-go would ship as its native
// layer: a Go package linked into a custom `lg` (let-go's own extension
// route, docs/guide/custom-lg.md), registering the namespace `eve.native`.
//
// Regions are let-go byte-arrays (*vm.TypedArray of kind byte). A heap region
// is an ordinary `(byte-array n)`; a file region is `(eve.native/mmap fd n)`,
// a MAP_SHARED mapping wrapped by vm.NewByteArrayFrom, so `aget`/`aset` and
// these natives work on either. The 32-bit RMW natives use sync/atomic on the
// region's bytes (LOCK CMPXCHG / LOCK XADD on amd64).
package evenative

import (
	"fmt"
	"os"
	"sync/atomic"
	"syscall"
	"unsafe"

	"github.com/nooga/let-go/pkg/rt"
	"github.com/nooga/let-go/pkg/vm"
)

func bytesOf(v vm.Value) ([]byte, error) {
	a, ok := v.(*vm.TypedArray)
	if !ok {
		return nil, fmt.Errorf("eve.native: expected byte-array, got %s", v.Type().Name())
	}
	b, ok := a.Unbox().([]byte)
	if !ok {
		return nil, fmt.Errorf("eve.native: expected a byte-array, got another array kind")
	}
	return b, nil
}

func intOf(v vm.Value, what string) (int64, error) {
	i, ok := v.(vm.Int)
	if !ok {
		return 0, fmt.Errorf("eve.native: %s must be an integer, got %s", what, v.Type().Name())
	}
	return int64(i), nil
}

// word returns the aligned 32-bit word at byte offset off of region v.
func word(v, off vm.Value) (*int32, error) {
	b, err := bytesOf(v)
	if err != nil {
		return nil, err
	}
	o, err := intOf(off, "offset")
	if err != nil {
		return nil, err
	}
	if o < 0 || o+4 > int64(len(b)) {
		return nil, fmt.Errorf("eve.native: offset %d out of range for a %d-byte region", o, len(b))
	}
	p := unsafe.Pointer(&b[o])
	if uintptr(p)%4 != 0 {
		return nil, fmt.Errorf("eve.native: offset %d is not 4-byte aligned", o)
	}
	return (*int32)(p), nil
}

func argc(name string, vs []vm.Value, n int) error {
	if len(vs) != n {
		return fmt.Errorf("eve.native/%s expects %d args, got %d", name, n, len(vs))
	}
	return nil
}

func def(ns *vm.Namespace, name string, fn func([]vm.Value) (vm.Value, error)) {
	f, err := vm.NativeFnType.Wrap(fn)
	if err != nil {
		panic(err)
	}
	ns.Def(name, f)
}

func init() {
	ns := vm.NewNamespace("eve.native")

	// (load32 a off) -> the word, atomic load
	def(ns, "load32", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("load32", vs, 2); err != nil {
			return vm.NIL, err
		}
		p, err := word(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(atomic.LoadInt32(p)), nil
	})
	// (store32! a off v) -> nil, atomic store
	def(ns, "store32!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("store32!", vs, 3); err != nil {
			return vm.NIL, err
		}
		p, err := word(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		x, err := intOf(vs[2], "value")
		if err != nil {
			return vm.NIL, err
		}
		atomic.StoreInt32(p, int32(x))
		return vm.NIL, nil
	})
	// (cas32! a off expected desired) -> the witness: the value seen. Equal to
	// expected iff the swap happened (HOST-CONTRACT §2.1).
	def(ns, "cas32!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("cas32!", vs, 4); err != nil {
			return vm.NIL, err
		}
		p, err := word(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		e, err := intOf(vs[2], "expected")
		if err != nil {
			return vm.NIL, err
		}
		d, err := intOf(vs[3], "desired")
		if err != nil {
			return vm.NIL, err
		}
		for {
			cur := atomic.LoadInt32(p)
			if cur != int32(e) {
				return vm.Int(cur), nil
			}
			if atomic.CompareAndSwapInt32(p, int32(e), int32(d)) {
				return vm.Int(int32(e)), nil
			}
		}
	})
	// (add32! a off delta) -> the old value (fetch-add)
	def(ns, "add32!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("add32!", vs, 3); err != nil {
			return vm.NIL, err
		}
		p, err := word(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		d, err := intOf(vs[2], "delta")
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(atomic.AddInt32(p, int32(d)) - int32(d)), nil
	})
	// (open-fd path size) -> fd. O_RDWR|O_CREAT, grows the file to size if it
	// is shorter, never truncates.
	def(ns, "open-fd", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("open-fd", vs, 2); err != nil {
			return vm.NIL, err
		}
		path, ok := vs[0].(vm.String)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native/open-fd: path must be a string")
		}
		size, err := intOf(vs[1], "size")
		if err != nil {
			return vm.NIL, err
		}
		fd, err := syscall.Open(string(path), syscall.O_RDWR|syscall.O_CREAT, 0o644)
		if err != nil {
			return vm.NIL, err
		}
		var st syscall.Stat_t
		if err := syscall.Fstat(fd, &st); err != nil {
			return vm.NIL, err
		}
		if st.Size < size {
			if err := syscall.Ftruncate(fd, size); err != nil {
				return vm.NIL, err
			}
		}
		return vm.Int(fd), nil
	})
	// (mmap fd size) -> a byte-array over a MAP_SHARED, PROT_READ|PROT_WRITE
	// mapping of the file's first size bytes.
	def(ns, "mmap", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("mmap", vs, 2); err != nil {
			return vm.NIL, err
		}
		fd, err := intOf(vs[0], "fd")
		if err != nil {
			return vm.NIL, err
		}
		size, err := intOf(vs[1], "size")
		if err != nil {
			return vm.NIL, err
		}
		b, err := syscall.Mmap(int(fd), 0, int(size), syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
		if err != nil {
			return vm.NIL, err
		}
		return vm.NewByteArrayFrom(b), nil
	})
	// (lock! fd off len) / (unlock! fd off len): fcntl F_SETLKW write lock /
	// F_UNLCK on the byte range [off, off+len). POSIX record locks are owned
	// by the process, so they exclude other processes, not goroutines.
	flock := func(name string, typ int16) func([]vm.Value) (vm.Value, error) {
		return func(vs []vm.Value) (vm.Value, error) {
			if err := argc(name, vs, 3); err != nil {
				return vm.NIL, err
			}
			fd, err := intOf(vs[0], "fd")
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
			lk := syscall.Flock_t{Type: typ, Whence: 0, Start: off, Len: n}
			if err := syscall.FcntlFlock(uintptr(fd), syscall.F_SETLKW, &lk); err != nil {
				return vm.NIL, err
			}
			return vm.TRUE, nil
		}
	}
	def(ns, "lock!", flock("lock!", syscall.F_WRLCK))
	def(ns, "unlock!", flock("unlock!", syscall.F_UNLCK))
	def(ns, "pid", func(vs []vm.Value) (vm.Value, error) {
		return vm.Int(os.Getpid()), nil
	})

	installTypes(ns)

	// Out-of-tree packages call RegisterNS directly from init(): pkg/rt has
	// already drained its installer queue by now (docs/guide/custom-lg.md).
	rt.RegisterNS(ns)
}
