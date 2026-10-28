// Package region is eve's region surface on glojure, compiled into glj-eve
// (HOST-CONTRACT §1–§2) and driven by eve's region adapter
// (eve/adapters/region/glojure, eve.region implementing eve.mem): a heap
// region shared by goroutines (§1.1), a MAP_SHARED file mapping that grows and
// never truncates (§1.2), 32-bit atomics with a witness CAS and an old-value
// fetch-add (§2.1) — a load or store at an unaligned offset reads or writes
// the four bytes plainly, as the HAMT's node interiors need — the 64-bit ones
// the overflow header needs (§2.2), wait / notify by the futex syscall on Linux and by
// polling elsewhere (§2.3, wait_linux.go / wait_other.go),
// bulk bytes as glojure's byte arrays ([]int8, §2.4), and an fcntl byte-range
// lock (a Lustre mount's provider: a region whose read-modify-writes take it).
//
// It reaches glj code through glojure's own package registration: the build
// script runs glojure's generator (cmd/gen-import-interop) over this package,
// which emits a RegisterImports func handed to pkgmap.SetLoader; glj code then
// calls (eveglj/region.Add32 r off 1).
//
// Every numeric parameter and result is int64 because glj integers are int64.
package region

import (
	"fmt"
	"os"
	"sync/atomic"
	"syscall"
	"unsafe"
)

// Region is a byte region: Go heap memory (fd == -1) or a MAP_SHARED mapping.
// A mapping keeps its descriptor only when it was opened locked (MmapLocked):
// fcntl locks belong to the descriptor; any other mapping stays valid after
// its descriptor is closed (§1.2), so growth's many mappings hold none.
type Region struct {
	b    []byte
	fd   int
	path string
}

// Heap allocates a zero-filled in-process region of size bytes. Go's heap
// does not move, so a futex on its words is sound.
func Heap(size int64) *Region {
	return &Region{b: make([]byte, size), fd: -1}
}

func mmapFile(path string, size int64) ([]byte, int, error) {
	fd, err := syscall.Open(path, syscall.O_RDWR|syscall.O_CREAT|syscall.O_CLOEXEC, 0o644)
	if err != nil {
		return nil, -1, fmt.Errorf("open %s: %w", path, err)
	}
	var st syscall.Stat_t
	if err := syscall.Fstat(fd, &st); err != nil {
		syscall.Close(fd)
		return nil, -1, err
	}
	if st.Size < size {
		if err := syscall.Ftruncate(fd, size); err != nil { // grow only
			syscall.Close(fd)
			return nil, -1, err
		}
	}
	b, err := syscall.Mmap(fd, 0, int(size), syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
	if err != nil {
		syscall.Close(fd)
		return nil, -1, fmt.Errorf("mmap %s: %w", path, err)
	}
	return b, fd, nil
}

// Mmap opens (creating if needed) path, grows it to size bytes if it is
// smaller (never truncates a larger file: a longer file maps only its first
// size bytes), maps it MAP_SHARED read/write and closes the descriptor.
func Mmap(path string, size int64) (*Region, error) {
	b, fd, err := mmapFile(path, size)
	if err != nil {
		return nil, err
	}
	syscall.Close(fd)
	return &Region{b: b, fd: -1, path: path}, nil
}

// MmapLocked is Mmap keeping the descriptor open, for Lock / Unlock.
func MmapLocked(path string, size int64) (*Region, error) {
	b, fd, err := mmapFile(path, size)
	if err != nil {
		return nil, err
	}
	return &Region{b: b, fd: fd, path: path}, nil
}

// Close unmaps a file region and closes its descriptor (drops its locks).
// eve never unmaps a region it opened (§1.2); this is for tools and tests.
func Close(r *Region) error {
	if r.path == "" {
		return nil
	}
	err := syscall.Munmap(r.b)
	if r.fd >= 0 {
		syscall.Close(r.fd)
	}
	r.fd = -1
	r.path = ""
	return err
}

func Len(r *Region) int64 { return int64(len(r.b)) }
func Fd(r *Region) int64  { return int64(r.fd) }
func Pid() int64          { return int64(os.Getpid()) }

// FileSize is path's size in bytes, or -1 when it does not exist.
func FileSize(path string) int64 {
	var st syscall.Stat_t
	if err := syscall.Stat(path, &st); err != nil {
		return -1
	}
	return st.Size
}

// Addr is the address of byte off (to show where the region lives).
func Addr(r *Region, off int64) int64 { return int64(uintptr(unsafe.Pointer(&r.b[off]))) }

func check(r *Region, off, n int64) {
	if off < 0 || off+n > int64(len(r.b)) {
		panic(fmt.Sprintf("region: offset %d (+%d) out of range (len %d)", off, n, len(r.b)))
	}
}

func p32(r *Region, off int64) *int32 {
	check(r, off, 4)
	if off%4 != 0 {
		panic(fmt.Sprintf("region: i32 atomic at unaligned offset %d", off))
	}
	return (*int32)(unsafe.Pointer(&r.b[off]))
}

func p64(r *Region, off int64) *int64 {
	check(r, off, 8)
	if off%8 != 0 {
		panic(fmt.Sprintf("region: i64 atomic at unaligned offset %d", off))
	}
	return (*int64)(unsafe.Pointer(&r.b[off]))
}

// Load32 / Store32: atomic load and store (EVE's -load-i32 / -store-i32)
// at an aligned offset; at any other, the four little-endian bytes read or
// written plainly (§2.1's ★: HAMT node interiors sit at any offset).
// A Load32 followed by a Store32 is a plain read-modify-write: two calls.
func Load32(r *Region, off int64) int64 {
	if off%4 == 0 {
		return int64(atomic.LoadInt32(p32(r, off)))
	}
	check(r, off, 4)
	b := r.b[off : off+4]
	return int64(int32(uint32(b[0]) | uint32(b[1])<<8 | uint32(b[2])<<16 | uint32(b[3])<<24))
}

func Store32(r *Region, off int64, v int64) {
	if off%4 == 0 {
		atomic.StoreInt32(p32(r, off), int32(v))
		return
	}
	check(r, off, 4)
	u := uint32(int32(v))
	b := r.b[off : off+4]
	b[0], b[1], b[2], b[3] = byte(u), byte(u>>8), byte(u>>16), byte(u>>24)
}

// CAS32 is a witness CAS: it returns the value it found; the swap happened
// iff that equals exp.
func CAS32(r *Region, off, exp, nv int64) int64 {
	p := p32(r, off)
	for {
		cur := atomic.LoadInt32(p)
		if cur != int32(exp) {
			return int64(cur)
		}
		if atomic.CompareAndSwapInt32(p, cur, int32(nv)) {
			return int64(cur)
		}
	}
}

// Add32 is fetch-add: it returns the value before the add (EVE's -add-i32!).
func Add32(r *Region, off, d int64) int64 {
	return int64(atomic.AddInt32(p32(r, off), int32(d)) - int32(d))
}

// Exchange32 stores v and returns the previous value.
func Exchange32(r *Region, off, v int64) int64 {
	return int64(atomic.SwapInt32(p32(r, off), int32(v)))
}

// Load64 / Store64 / CAS64 (witness) / Add64 (old value): the 8-aligned
// 64-bit atomics (§2.2).
func Load64(r *Region, off int64) int64     { return atomic.LoadInt64(p64(r, off)) }
func Store64(r *Region, off int64, v int64) { atomic.StoreInt64(p64(r, off), v) }

func CAS64(r *Region, off, exp, nv int64) int64 {
	p := p64(r, off)
	for {
		cur := atomic.LoadInt64(p)
		if cur != exp {
			return cur
		}
		if atomic.CompareAndSwapInt64(p, cur, nv) {
			return cur
		}
	}
}

func Add64(r *Region, off, d int64) int64 { return atomic.AddInt64(p64(r, off), d) - d }

// ReadBytes copies the n bytes at off into a new glojure byte array.
func ReadBytes(r *Region, off, n int64) []int8 {
	check(r, off, n)
	out := make([]int8, n)
	copy(unsafe.Slice((*byte)(unsafe.Pointer(unsafe.SliceData(out))), n), r.b[off:off+n])
	return out
}

// WriteBytes copies all of src into the region at off.
func WriteBytes(r *Region, off int64, src []int8) {
	n := int64(len(src))
	check(r, off, n)
	if n == 0 {
		return
	}
	copy(r.b[off:off+n], unsafe.Slice((*byte)(unsafe.Pointer(unsafe.SliceData(src))), n))
}

func fcntlLock(r *Region, typ int16, off, n int64) error {
	if r.fd < 0 {
		return fmt.Errorf("region: lock on a region without a descriptor")
	}
	lk := syscall.Flock_t{Type: typ, Whence: 0, Start: off, Len: n}
	for {
		err := syscall.FcntlFlock(uintptr(r.fd), syscall.F_SETLKW, &lk)
		if err != syscall.EINTR {
			return err
		}
	}
}

// Lock takes an exclusive fcntl byte-range lock on [off, off+n), blocking.
func Lock(r *Region, off, n int64) error { return fcntlLock(r, syscall.F_WRLCK, off, n) }

// Unlock releases the byte range.
func Unlock(r *Region, off, n int64) error { return fcntlLock(r, syscall.F_UNLCK, off, n) }
