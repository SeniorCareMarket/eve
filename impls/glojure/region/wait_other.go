//go:build !linux

package region

import (
	"sync/atomic"
	"time"
)

// Wait32 blocks until the i32 at off is not exp or timeoutMs elapses
// (timeoutMs < 0: no deadline), polling every 100 µs: no futex outside Linux
// (macOS's wait-on-address is a private API until 14.4), as eve's Node addon
// waits there. 0 = woken (:ok), 1 = the value already differed (:not-equal),
// 2 = timed out (:timed-out).
func Wait32(r *Region, off, exp, timeoutMs int64) int64 {
	p := p32(r, off)
	if atomic.LoadInt32(p) != int32(exp) {
		return 1
	}
	var deadline time.Time
	if timeoutMs >= 0 {
		deadline = time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	}
	for {
		if atomic.LoadInt32(p) != int32(exp) {
			return 0
		}
		if timeoutMs >= 0 && !time.Now().Before(deadline) {
			return 2
		}
		time.Sleep(100 * time.Microsecond)
	}
}

// Notify32 wakes no one: a poller sees the new value on its next look. It
// answers 0, as a futex wake with no waiter does.
func Notify32(r *Region, off, n int64) int64 { return 0 }
