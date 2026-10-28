//go:build linux

package region

import (
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

// futex(2) operations; the shared (not PRIVATE) forms, so a waiter in one
// process is woken by a notify in another mapping the same file.
const (
	futexWait = 0
	futexWake = 1
)

// Wait32 blocks until the i32 at off is not exp or timeoutMs elapses
// (timeoutMs < 0: no deadline). 0 = woken (:ok), 1 = the value already
// differed (:not-equal), 2 = timed out (:timed-out). A spurious or
// interrupted wake answers 0, as the contract allows.
func Wait32(r *Region, off, exp, timeoutMs int64) int64 {
	p := p32(r, off)
	if atomic.LoadInt32(p) != int32(exp) {
		return 1
	}
	var tsp unsafe.Pointer
	if timeoutMs >= 0 {
		ts := syscall.NsecToTimespec(int64(time.Duration(timeoutMs) * time.Millisecond))
		tsp = unsafe.Pointer(&ts)
	}
	_, _, errno := syscall.Syscall6(syscall.SYS_FUTEX, uintptr(unsafe.Pointer(p)), futexWait,
		uintptr(uint32(int32(exp))), uintptr(tsp), 0, 0)
	switch errno {
	case 0:
		return 0
	case syscall.EAGAIN:
		return 1
	case syscall.ETIMEDOUT:
		return 2
	}
	return 0
}

// Notify32 wakes up to n waiters on the i32 at off; the count woken.
func Notify32(r *Region, off, n int64) int64 {
	p := p32(r, off)
	woken, _, errno := syscall.Syscall6(syscall.SYS_FUTEX, uintptr(unsafe.Pointer(p)), futexWake,
		uintptr(n), 0, 0, 0)
	if errno != 0 {
		return 0
	}
	return int64(woken)
}
