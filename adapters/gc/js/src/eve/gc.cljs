(ns eve.gc
  "eve's GC hook (feature gc, adapters/README.md) on a JavaScript host: FinalizationRegistry (Node, browsers; nbb,
   scittle, cherry, squint run on them). The registry runs a release after its object is collected.

   token answers a fresh object; whatever holds it (eve.view's read cell) is collected with it, and release runs then.
   release must not reach the token's holder (the registry holds the release strongly).")

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

(defn token
  "A fresh object whose collection runs (release); nil where the host has no hook."
  [release]
  (when registry
    (let [t #js {}]
      (.register registry t release)
      t)))
