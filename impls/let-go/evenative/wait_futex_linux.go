//go:build linux

package evenative

// The region wait/notify on Linux: the futex syscall on the word itself.
// A futex on a MAP_SHARED mapping coordinates across the processes that
// share it — Eve's cross-process waits (eve.mem -wait-i32! / -notify-i32!)
// are exactly that shape. FUTEX_WAIT / FUTEX_WAKE without the PRIVATE
// flag, so the wait queue is the shared one.

import (
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"github.com/nooga/let-go/pkg/vm"
)

const (
	futexWait = 0 // FUTEX_WAIT
	futexWake = 1 // FUTEX_WAKE
)

type timespec struct {
	Sec  int64
	Nsec int64
}

func wait32Impl(p *int32, expected int32, timeoutMs int64) (vm.Value, error) {
	var deadline time.Time
	if timeoutMs >= 0 {
		deadline = time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	}
	for {
		if atomic.LoadInt32(p) != expected {
			return kw("not-equal"), nil
		}
		var ts *timespec
		if timeoutMs >= 0 {
			left := time.Until(deadline)
			if left <= 0 {
				return kw("timed-out"), nil
			}
			ts = &timespec{Sec: int64(left / time.Second), Nsec: int64(left % time.Second)}
		}
		_, _, errno := syscall.RawSyscall6(syscall.SYS_FUTEX,
			uintptr(unsafe.Pointer(p)), futexWait, uintptr(uint32(expected)),
			uintptr(unsafe.Pointer(ts)), 0, 0)
		switch errno {
		case 0:
			return kw("ok"), nil
		case syscall.EAGAIN:
			return kw("not-equal"), nil
		case syscall.ETIMEDOUT:
			return kw("timed-out"), nil
		case syscall.EINTR:
			// a signal, not a wake: re-check the word and the clock
			continue
		default:
			return vm.NIL, errno
		}
	}
}

func notify32Impl(p *int32, n int64) (vm.Value, error) {
	k := n
	if k < 0 {
		k = 1<<31 - 1 // INT_MAX: wake every waiter
	}
	r, _, errno := syscall.RawSyscall6(syscall.SYS_FUTEX,
		uintptr(unsafe.Pointer(p)), futexWake, uintptr(k), 0, 0, 0)
	if errno != 0 {
		return vm.NIL, errno
	}
	return vm.Int(int64(r)), nil
}
