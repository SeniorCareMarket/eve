(ns eve.gc
  "eve's GC hook (feature gc, adapters/README.md) on a JVM-shaped host: what ends a read whose last view the program
   dropped (eve.view, read-end B). java.lang.ref.Cleaner where the host has it (the JVM; babashka, measured on bb
   1.13.229), its action on the Cleaner's own thread; else weak references on a ReferenceQueue, swept by eve itself at
   every read (sweep!: jolt).

   token answers a fresh object; whatever holds it (eve.view's read cell, in an immutable field) is collected with it,
   and release runs then. release must not reach the token's holder (a release that holds it keeps it alive).")

(set! *warn-on-reflection* true)

(defonce ^:private cleaner
  (try (java.lang.ref.Cleaner/create) #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ nil)))

(defonce ^:private queue
  (when-not cleaner (try (java.lang.ref.ReferenceQueue.) #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ nil))))

(defonce ^:private registry (atom {}))

(defn available?
  "Can this host's GC run an action when an object is collected?"
  []
  (boolean (or cleaner queue)))

(defn sweep!
  "Run the releases of the objects collected since the last sweep (the ReferenceQueue hosts; the Cleaner runs its
   own)."
  []
  (when queue
    (loop []
      (when-let [r (.poll ^java.lang.ref.ReferenceQueue queue)]
        (when-let [release (get @registry r)]
          (swap! registry dissoc r)
          (release))
        (recur)))))

(defn token
  "A fresh object whose collection runs (release); nil where the host has no hook."
  [release]
  (let [t (Object.)]
    (cond
      cleaner (do (.register ^java.lang.ref.Cleaner cleaner t ^Runnable release) t)
      queue (do (sweep!)
                (swap! registry assoc (java.lang.ref.WeakReference. t queue) release)
                t)
      :else nil)))
