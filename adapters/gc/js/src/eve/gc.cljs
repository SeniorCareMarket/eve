(ns eve.gc
  "eve's GC hook (feature gc, adapters/README.md) on a JavaScript host: FinalizationRegistry (Node, browsers; nbb,
   scittle, cherry, squint run on them). The registry runs a release after its object is collected.

   on-collect's release must not reach obj (the registry holds the release strongly).")

(def ^:private registry
  (when (exists? js/FinalizationRegistry)
    (js/FinalizationRegistry. (fn [release] (release)))))

(defn available?
  "Can this host's GC run an action when an object is collected?"
  []
  (some? registry))

(defn sweep!
  "Nothing to sweep: the registry runs its releases itself."
  []
  nil)

(defn on-collect
  "Run (release) once obj has been collected. Answers what obj must hold for that to happen (nil here)."
  [obj release]
  (when registry (.register registry obj release))
  nil)
