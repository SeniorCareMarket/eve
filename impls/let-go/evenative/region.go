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
	"encoding/binary"
	"fmt"
	"hash/crc32"
	"os"
	"path/filepath"
	"runtime"
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

// word64 returns the aligned 64-bit word at byte offset off of region v.
func word64(v, off vm.Value) (*int64, error) {
	b, err := bytesOf(v)
	if err != nil {
		return nil, err
	}
	o, err := intOf(off, "offset")
	if err != nil {
		return nil, err
	}
	if o < 0 || o+8 > int64(len(b)) {
		return nil, fmt.Errorf("eve.native: offset %d out of range for a %d-byte region", o, len(b))
	}
	p := unsafe.Pointer(&b[o])
	if uintptr(p)%8 != 0 {
		return nil, fmt.Errorf("eve.native: offset %d is not 8-byte aligned", o)
	}
	return (*int64)(p), nil
}

// wait32Impl / notify32Impl are the region's wait/notify: on Linux the
// futex syscall itself (wait_futex_linux.go — a futex works on a shared
// mapping across processes, which is what Eve's mmap regions coordinate
// through); off Linux a bounded poll on the word (wait_poll_other.go),
// the glojure port's shape there. Declared per file by build tags.

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

// sliceAt returns the region's bytes and off, range-checked for n bytes —
// the plain-access counterpart of word()/word64() (which the RMW natives
// keep: mem.cljc, "atomic ops require 4-byte alignment").
//
// HOST-CONTRACT §2: -load-i32/-store-i32! (and the i64 pair) accept ANY
// offset — HAMT node interiors are [len:i32][bytes] packed (map.cljc's
// write-kv!), so a length prefix lands at whatever offset the previous
// key's bytes end at. They are non-atomic: "acquire when aligned",
// "release when aligned". So: aligned → the atomic op on the word;
// unaligned → byte-wise little-endian (the JVM candidate's fallback for
// the same contract clause, HOST-CONTRACT residue 8.2). First measured
// on the port tree: the first full-stack atom mint on :lg died in
// write-kv! at "offset 235 is not 4-byte aligned" (Bucket A, Agent P).
func sliceAt(v, off vm.Value, n int64) ([]byte, int64, error) {
	b, err := bytesOf(v)
	if err != nil {
		return nil, 0, err
	}
	o, err := intOf(off, "offset")
	if err != nil {
		return nil, 0, err
	}
	if o < 0 || o+n > int64(len(b)) {
		return nil, 0, fmt.Errorf("eve.native: offset %d out of range for a %d-byte region", o, len(b))
	}
	return b, o, nil
}

func load32At(v, off vm.Value) (int32, error) {
	b, o, err := sliceAt(v, off, 4)
	if err != nil {
		return 0, err
	}
	if o%4 == 0 {
		return atomic.LoadInt32((*int32)(unsafe.Pointer(&b[o]))), nil
	}
	return int32(binary.LittleEndian.Uint32(b[o:])), nil
}

func store32At(v, off vm.Value, x int32) error {
	b, o, err := sliceAt(v, off, 4)
	if err != nil {
		return err
	}
	if o%4 == 0 {
		atomic.StoreInt32((*int32)(unsafe.Pointer(&b[o])), x)
		return nil
	}
	binary.LittleEndian.PutUint32(b[o:], uint32(x))
	return nil
}

func load64At(v, off vm.Value) (int64, error) {
	b, o, err := sliceAt(v, off, 8)
	if err != nil {
		return 0, err
	}
	if o%8 == 0 {
		return atomic.LoadInt64((*int64)(unsafe.Pointer(&b[o]))), nil
	}
	return int64(binary.LittleEndian.Uint64(b[o:])), nil
}

func store64At(v, off vm.Value, x int64) error {
	b, o, err := sliceAt(v, off, 8)
	if err != nil {
		return err
	}
	if o%8 == 0 {
		atomic.StoreInt64((*int64)(unsafe.Pointer(&b[o])), x)
		return nil
	}
	binary.LittleEndian.PutUint64(b[o:], uint64(x))
	return nil
}

func init() {
	ns := vm.NewNamespace("eve.native")

	// (load32 a off) -> the word: atomic when aligned, byte-wise at any
	// offset (HOST-CONTRACT §2 via load32At)
	def(ns, "load32", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("load32", vs, 2); err != nil {
			return vm.NIL, err
		}
		x, err := load32At(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(x), nil
	})
	// (store32! a off v) -> nil: atomic when aligned, byte-wise at any offset
	def(ns, "store32!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("store32!", vs, 3); err != nil {
			return vm.NIL, err
		}
		x, err := intOf(vs[2], "value")
		if err != nil {
			return vm.NIL, err
		}
		if err := store32At(vs[0], vs[1], int32(x)); err != nil {
			return vm.NIL, err
		}
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
	// (exchange32! a off v) -> the old value (atomic swap)
	def(ns, "exchange32!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("exchange32!", vs, 3); err != nil {
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
		return vm.Int(atomic.SwapInt32(p, int32(x))), nil
	})
	// (load64 a off) -> the word: atomic when aligned, byte-wise at any offset
	def(ns, "load64", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("load64", vs, 2); err != nil {
			return vm.NIL, err
		}
		x, err := load64At(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(x), nil
	})
	// (store64! a off v) -> nil: atomic when aligned, byte-wise at any offset
	def(ns, "store64!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("store64!", vs, 3); err != nil {
			return vm.NIL, err
		}
		x, err := intOf(vs[2], "value")
		if err != nil {
			return vm.NIL, err
		}
		if err := store64At(vs[0], vs[1], x); err != nil {
			return vm.NIL, err
		}
		return vm.NIL, nil
	})
	// (cas64! a off expected desired) -> the witness, as cas32!
	def(ns, "cas64!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("cas64!", vs, 4); err != nil {
			return vm.NIL, err
		}
		p, err := word64(vs[0], vs[1])
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
			cur := atomic.LoadInt64(p)
			if cur != e {
				return vm.Int(cur), nil
			}
			if atomic.CompareAndSwapInt64(p, e, d) {
				return vm.Int(e), nil
			}
		}
	})
	// (add64! a off delta) -> the old value (fetch-add)
	def(ns, "add64!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("add64!", vs, 3); err != nil {
			return vm.NIL, err
		}
		p, err := word64(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		d, err := intOf(vs[2], "delta")
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(atomic.AddInt64(p, d) - d), nil
	})
	// (read-bytes a off len) -> a new byte-array holding a copy of a's bytes
	// at [off, off+len). Plain loads: the caller synchronises.
	def(ns, "read-bytes", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("read-bytes", vs, 3); err != nil {
			return vm.NIL, err
		}
		b, err := bytesOf(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		o, err := intOf(vs[1], "offset")
		if err != nil {
			return vm.NIL, err
		}
		n, err := intOf(vs[2], "length")
		if err != nil {
			return vm.NIL, err
		}
		if o < 0 || n < 0 || o+n > int64(len(b)) {
			return vm.NIL, fmt.Errorf("eve.native: read-bytes [%d, %d) out of range for a %d-byte region", o, o+n, len(b))
		}
		out := make([]byte, n)
		copy(out, b[o:o+n])
		return vm.NewByteArrayFrom(out), nil
	})
	// (write-bytes! a off src) -> nil. Copies src's bytes into a at off.
	// Plain stores: the caller synchronises.
	def(ns, "write-bytes!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("write-bytes!", vs, 3); err != nil {
			return vm.NIL, err
		}
		b, err := bytesOf(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		o, err := intOf(vs[1], "offset")
		if err != nil {
			return vm.NIL, err
		}
		src, err := anyBytes(vs[2])
		if err != nil {
			return vm.NIL, err
		}
		if o < 0 || o+int64(len(src)) > int64(len(b)) {
			return vm.NIL, fmt.Errorf("eve.native: write-bytes! [%d, %d) out of range for a %d-byte region", o, o+int64(len(src)), len(b))
		}
		copy(b[o:], src)
		return vm.NIL, nil
	})
	// (wait32! a off expected timeout-ms) -> :ok | :not-equal | :timed-out.
	// Blocks until notify32! wakes this word (in any process sharing the
	// mapping), the word's value differs from expected, or the timeout
	// elapses (a negative timeout waits forever).
	def(ns, "wait32!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("wait32!", vs, 4); err != nil {
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
		tmo, err := intOf(vs[3], "timeout-ms")
		if err != nil {
			return vm.NIL, err
		}
		return wait32Impl(p, int32(e), tmo)
	})
	// (notify32! a off n) -> the number of waiters woken. Wakes up to n of
	// the goroutines blocked in wait32! on the word (all of them when n is
	// negative).
	def(ns, "notify32!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("notify32!", vs, 3); err != nil {
			return vm.NIL, err
		}
		p, err := word(vs[0], vs[1])
		if err != nil {
			return vm.NIL, err
		}
		n, err := intOf(vs[2], "count")
		if err != nil {
			return vm.NIL, err
		}
		return notify32Impl(p, n)
	})
	// (sync! a) -> true when a is a file mapping whose bytes are now forced
	// to the file (msync, MS_SYNC); false when a is not mappable that way
	// (a heap array answers false only when the kernel refuses it).
	def(ns, "sync!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("sync!", vs, 1); err != nil {
			return vm.NIL, err
		}
		b, err := bytesOf(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		if len(b) == 0 {
			return vm.FALSE, nil
		}
		base := uintptr(unsafe.Pointer(&b[0]))
		start := base &^ 4095
		end := base + uintptr(len(b))
		_, _, errno := syscall.Syscall(syscall.SYS_MSYNC, start, end-start, uintptr(syscall.MS_SYNC))
		if errno != 0 {
			return vm.FALSE, nil
		}
		return vm.TRUE, nil
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
	// (create-exclusive! path) -> true when this call created the empty
	// file at path, false when it already existed — one atomic step
	// against every other process (O_CREAT|O_EXCL; HOST-CONTRACT §5,
	// eve.process/create-exclusive!: an mmap domain's creator is the
	// process that gets true).
	def(ns, "create-exclusive!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("create-exclusive!", vs, 1); err != nil {
			return vm.NIL, err
		}
		path, ok := vs[0].(vm.String)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native/create-exclusive!: path must be a string")
		}
		f, err := os.OpenFile(string(path), os.O_RDWR|os.O_CREATE|os.O_EXCL, 0o644)
		if err != nil {
			if os.IsExist(err) {
				return vm.FALSE, nil
			}
			return vm.NIL, err
		}
		if err := f.Close(); err != nil {
			return vm.NIL, err
		}
		return vm.TRUE, nil
	})
	// (ensure-parent-dir! base-path) -> nil. Creates the directory the
	// domain file <base-path>.root lives in, when it is not there
	// (eve.process/ensure-parent-dir!).
	def(ns, "ensure-parent-dir!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("ensure-parent-dir!", vs, 1); err != nil {
			return vm.NIL, err
		}
		path, ok := vs[0].(vm.String)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native/ensure-parent-dir!: path must be a string")
		}
		dir := filepath.Dir(string(path) + ".root")
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return vm.NIL, err
		}
		return vm.NIL, nil
	})
	// (num-cpu) -> the number of logical CPUs the Go runtime can use
	// (eve.thread-host's parallelism on this host).
	def(ns, "num-cpu", func(vs []vm.Value) (vm.Value, error) {
		return vm.Int(runtime.NumCPU()), nil
	})
	// ── file-descriptor I/O (the journal file's natives; eve.journal-file) ──
	// (fd-open path) -> fd. O_RDWR|O_CREAT, no growth, no truncation.
	def(ns, "fd-open", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("fd-open", vs, 1); err != nil {
			return vm.NIL, err
		}
		path, ok := vs[0].(vm.String)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.native/fd-open: path must be a string")
		}
		fd, err := syscall.Open(string(path), syscall.O_RDWR|syscall.O_CREAT, 0o644)
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(fd), nil
	})
	// (fd-size fd) -> the file's length in bytes.
	def(ns, "fd-size", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("fd-size", vs, 1); err != nil {
			return vm.NIL, err
		}
		fd, err := intOf(vs[0], "fd")
		if err != nil {
			return vm.NIL, err
		}
		var st syscall.Stat_t
		if err := syscall.Fstat(int(fd), &st); err != nil {
			return vm.NIL, err
		}
		return vm.Int(st.Size), nil
	})
	// (fd-read-at fd off len) -> a new byte-array with the file's bytes at
	// [off, off+len); a short file answers a shorter array.
	def(ns, "fd-read-at", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("fd-read-at", vs, 3); err != nil {
			return vm.NIL, err
		}
		fd, err := intOf(vs[0], "fd")
		if err != nil {
			return vm.NIL, err
		}
		off, err := intOf(vs[1], "offset")
		if err != nil {
			return vm.NIL, err
		}
		n, err := intOf(vs[2], "length")
		if err != nil {
			return vm.NIL, err
		}
		buf := make([]byte, n)
		got := 0
		for got < int(n) {
			m, err := syscall.Pread(int(fd), buf[got:], off+int64(got))
			if err != nil {
				return vm.NIL, err
			}
			if m == 0 {
				break
			}
			got += m
		}
		return vm.NewByteArrayFrom(buf[:got]), nil
	})
	// (fd-write-at! fd bytes off) -> nil. Writes all of bytes at off.
	def(ns, "fd-write-at!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("fd-write-at!", vs, 3); err != nil {
			return vm.NIL, err
		}
		fd, err := intOf(vs[0], "fd")
		if err != nil {
			return vm.NIL, err
		}
		src, err := anyBytes(vs[1])
		if err != nil {
			return vm.NIL, err
		}
		off, err := intOf(vs[2], "offset")
		if err != nil {
			return vm.NIL, err
		}
		for len(src) > 0 {
			m, err := syscall.Pwrite(int(fd), src, off)
			if err != nil {
				return vm.NIL, err
			}
			src = src[m:]
			off += int64(m)
		}
		return vm.NIL, nil
	})
	// (fd-truncate! fd size) -> nil.
	def(ns, "fd-truncate!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("fd-truncate!", vs, 2); err != nil {
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
		if err := syscall.Ftruncate(int(fd), size); err != nil {
			return vm.NIL, err
		}
		return vm.NIL, nil
	})
	// (fd-sync! fd full?) -> nil. fsync when full?, fdatasync otherwise.
	def(ns, "fd-sync!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("fd-sync!", vs, 2); err != nil {
			return vm.NIL, err
		}
		fd, err := intOf(vs[0], "fd")
		if err != nil {
			return vm.NIL, err
		}
		if vs[1] == vm.TRUE {
			if err := syscall.Fsync(int(fd)); err != nil {
				return vm.NIL, err
			}
		} else {
			if err := syscall.Fdatasync(int(fd)); err != nil {
				return vm.NIL, err
			}
		}
		return vm.NIL, nil
	})
	// (fd-close! fd) -> nil.
	def(ns, "fd-close!", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("fd-close!", vs, 1); err != nil {
			return vm.NIL, err
		}
		fd, err := intOf(vs[0], "fd")
		if err != nil {
			return vm.NIL, err
		}
		if err := syscall.Close(int(fd)); err != nil {
			return vm.NIL, err
		}
		return vm.NIL, nil
	})
	// (crc32 bytes) -> the CRC-32 (IEEE) of bytes, as an unsigned 32-bit
	// value — java.util.zip.CRC32's checksum, the journal record's CRC.
	def(ns, "crc32", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("crc32", vs, 1); err != nil {
			return vm.NIL, err
		}
		b, err := anyBytes(vs[0])
		if err != nil {
			return vm.NIL, err
		}
		return vm.Int(int64(crc32.ChecksumIEEE(b))), nil
	})
	def(ns, "pid", func(vs []vm.Value) (vm.Value, error) {
		return vm.Int(os.Getpid()), nil
	})

	installTypes(ns)

	// Out-of-tree packages call RegisterNS directly from init(): pkg/rt has
	// already drained its installer queue by now (docs/guide/custom-lg.md).
	rt.RegisterNS(ns)
}
