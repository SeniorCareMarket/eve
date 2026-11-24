(ns eve.gc
  "eve's GC hook (feature gc, adapters/README.md) on jolt: a Chez Scheme guardian (jolt's collector). token registers
   a fresh object with the guardian, release its representative; once the collector finds the object unreachable the
   guardian answers release, and sweep! runs those releases on eve's own thread. eve.view sweeps at every read.

   release must not reach the token's holder (a release that holds it keeps it alive). (The JVM adapter's
   ReferenceQueue path, which jolt took before, kept a map from each WeakReference to its release: a hash of a host
   object per token and per sweep, 30-40% of a small deref on jolt; the guardian keeps no map.)"
  (:require [jolt.scheme :as scheme]))

;; One lock over the guardian. Chez's guardian keeps what it found unreachable on a queue (a tconc) that a call pops
;; and a registration feeds, with no lock of its own: two threads popping at once can leave its head #f under a live
;; tail, and every later pop then faults ("invalid memory reference" in every thread that sweeps: measured, 2 jolt
;; processes x 2 threads on one domain, 1 run in 10-30 lost a process's threads; the fault's code is the tconc pop).
;; eve.region's guardian has its mutex for the same reason. This one is taken on every view read, so the lock is a
;; spin on a box (box-cas!, a locked cmpxchg) held across the one guardian call, not a mutex pair; a release runs
;; outside it.
(scheme/eval-string
 "(begin
    (define eve-gc-guardian (make-guardian))
    (define eve-gc-lock (box #f))
    (define-syntax eve-gc-locked
      (syntax-rules ()
        ((_ e) (begin (let spin () (unless (box-cas! eve-gc-lock #f #t) (spin)))
                      (let ((v e)) (memory-order-release) (set-box! eve-gc-lock #f) v)))))
    (define (eve-gc-register! t release) (eve-gc-locked (eve-gc-guardian t release)) t)
    (define (eve-gc-pop) (eve-gc-locked (eve-gc-guardian)))
    (define (eve-gc-collected) (or (eve-gc-pop) jolt-nil)))")

(def ^:private register! (scheme/proc "eve-gc-register!"))

(def ^:private collected (scheme/proc "eve-gc-collected"))

(defn available?
  "Can this host's GC run an action when an object is collected?"
  []
  true)

(defn sweep!
  "Run the releases of the objects collected since the last sweep."
  []
  (loop []
    (when-let [release (collected)]
      (release)
      (recur))))

(defn token
  "A fresh object whose collection runs (release)."
  [release]
  (sweep!)
  (register! (Object.) release))
