(ns eve.gc
  "eve's GC hook (feature gc, adapters/README.md) on jolt: a Chez Scheme guardian (jolt's collector). token registers
   a fresh object with the guardian, release its representative; once the collector finds the object unreachable the
   guardian answers release, and sweep! runs those releases on eve's own thread. eve.view sweeps at every read.

   release must not reach the token's holder (a release that holds it keeps it alive). (The JVM adapter's
   ReferenceQueue path, which jolt took before, kept a map from each WeakReference to its release: a hash of a host
   object per token and per sweep, 30-40% of a small deref on jolt; the guardian keeps no map.)"
  (:require [jolt.scheme :as scheme]))

(scheme/eval-string
 "(begin
    (define eve-gc-guardian (make-guardian))
    (define (eve-gc-register! t release) (eve-gc-guardian t release) t)
    (define (eve-gc-collected) (or (eve-gc-guardian) jolt-nil)))")

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
