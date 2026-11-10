(ns goog
  "Google Closure's base `goog` namespace, for nbb (ClojureScript on SCI):
   nbb carries no Closure library, and ClojureScript code names this
   namespace without requiring it (the compiler treats `goog` as the
   ambient Closure base) — DataScript's datascript.db writes
   (goog/exportSymbol \"datascript.db.Datom\" Datom) and its entity
   namespace (goog/getUid e) for an identity hash. Until the seat
   provides the namespace, such a library cannot load on eve's nbb seat
   (F-NBB-GOOG). Each var is Closure's own definition (goog/base.js),
   over JavaScript's objects. The surface is census-driven: a var joins
   this namespace when a library eve's adopters load names it — the
   DataScript port names exportSymbol and getUid.")

(defn exportSymbol
  "Closure's goog.exportSymbol: exports a value as a globally accessible
   symbol, walking the dotted path from the global object and creating
   each intermediate object that does not yet exist."
  ([public-path object] (exportSymbol public-path object js/globalThis))
  ([public-path object object-to-export-to]
   (let [parts (.split public-path ".")
         last-idx (dec (count parts))]
     (loop [cur object-to-export-to i 0]
       (let [part (aget parts i)]
         (if (= i last-idx)
           (aset cur part object)
           (recur (or (aget cur part)
                      (let [o #js {}] (aset cur part o) o))
                  (inc i))))))))

(def ^:private uid-property
  "Closure's goog.UID_PROPERTY_: the property an object's uid is stored
   under, suffixed so it does not collide with a page's own properties."
  (str "closure_uid_" (unsigned-bit-shift-right (* (rand) 1e9) 0)))

(def ^:private uid-counter (atom 0))

(defn getUid
  "Closure's goog.getUid: a unique id for the object, assigned on first
   ask and stable for the object's lifetime."
  [obj]
  (or (aget obj uid-property)
      (aset obj uid-property (swap! uid-counter inc))))
