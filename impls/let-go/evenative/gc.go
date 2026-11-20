package evenative

// A GC hook for eve.view (read-end B on let-go; eve/adapters/gc/letgo): the host
// GC ends a read whose last view the program dropped. A view's cell holds a box
// (eve.gc-native/new-box); when the GC collects the cell the box goes with it,
// and its finalizer records the box's id. eve.gc's sweep! takes the recorded
// ids (eve.gc-native/take-collected) and runs those releases on eve's own
// goroutine — the finalizer runs no let-go code. glojure's hook is the same
// (eve/impls/glojure/host/gc.go).

import (
	"fmt"
	"runtime"
	"sync"

	"github.com/nooga/let-go/pkg/rt"
	"github.com/nooga/let-go/pkg/vm"
)

// gcBox is what a cell holds. Its padding keeps it out of Go's tiny allocator,
// whose objects may never be finalized (runtime.SetFinalizer's doc).
type gcBox struct {
	id  int64
	pad [2]int64
}

var (
	collectedMu sync.Mutex
	collected   []vm.Value
)

func init() {
	ns := vm.NewNamespace("eve.gc-native")

	def(ns, "new-box", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("new-box", vs, 1); err != nil {
			return vm.NIL, err
		}
		id, ok := vs[0].(vm.Int)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.gc-native/new-box expects an integer id, got %s", vs[0].Type().Name())
		}
		b := &gcBox{id: int64(id)}
		runtime.SetFinalizer(b, func(b *gcBox) {
			collectedMu.Lock()
			collected = append(collected, vm.Int(b.id))
			collectedMu.Unlock()
		})
		return vm.NewBoxed(b), nil
	})

	def(ns, "take-collected", func(vs []vm.Value) (vm.Value, error) {
		collectedMu.Lock()
		out := collected
		collected = nil
		collectedMu.Unlock()
		if len(out) == 0 {
			return vm.NIL, nil
		}
		return vm.NewArrayVector(out), nil
	})

	def(ns, "collect", func(vs []vm.Value) (vm.Value, error) {
		runtime.GC()
		return vm.NIL, nil
	})

	rt.RegisterNS(ns)
}
