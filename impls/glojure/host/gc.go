package host

import (
	"runtime"
	"sync"
)

// A GC hook for eve.view (read-end B on glojure): the host GC ends a read whose
// last view the program dropped. A view's cell holds a GCBox; when the GC
// collects the cell, the box goes with it and its finalizer records the box's
// id. eve.view's sweep! takes the recorded ids (TakeCollected) and releases
// those reads on eve's own thread — the finalizer runs no glj code (Go's
// finalizer must be a Go func, and it runs on the runtime's finalizer
// goroutine). jolt's weak references are swept the same way.

// GCBox is what a cell holds. Its padding keeps it out of Go's tiny allocator,
// whose objects may never be finalized (runtime.SetFinalizer's doc).
type GCBox struct {
	id  int64
	pad [2]int64
}

var (
	collectedMu sync.Mutex
	collected   []any
)

// NewGCBox answers a box whose collection records id for TakeCollected.
func NewGCBox(id int64) *GCBox {
	b := &GCBox{id: id}
	runtime.SetFinalizer(b, func(b *GCBox) {
		collectedMu.Lock()
		collected = append(collected, b.id)
		collectedMu.Unlock()
	})
	return b
}

// TakeCollected answers the ids of the boxes collected since the last call
// (nil when none), and forgets them.
func TakeCollected() []any {
	collectedMu.Lock()
	out := collected
	collected = nil
	collectedMu.Unlock()
	return out
}
