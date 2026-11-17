//go:build !linux

package evenative

// The region wait/notify off Linux (no futex): a bounded poll on the
// word, the glojure port's fallback shape (its wait_other.go). A waiter
// whose word changes under it reports :not-equal; a notify wakes nobody
// directly (0 is a legal answer) and waiters observe the new value on
// their next poll — correct, at poll latency. Eve's fleet venues for
// let-go are Linux, where wait_futex_linux.go serves instead.

import (
	"sync/atomic"
	"time"

	"github.com/nooga/let-go/pkg/vm"
)

func wait32Impl(p *int32, expected int32, timeoutMs int64) (vm.Value, error) {
	var deadline time.Time
	if timeoutMs >= 0 {
		deadline = time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	}
	for {
		if atomic.LoadInt32(p) != expected {
			return kw("not-equal"), nil
		}
		if timeoutMs >= 0 && !time.Now().Before(deadline) {
			return kw("timed-out"), nil
		}
		time.Sleep(100 * time.Microsecond)
	}
}

func notify32Impl(_ *int32, _ int64) (vm.Value, error) {
	return vm.Int(0), nil
}
