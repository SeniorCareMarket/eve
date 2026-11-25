(ns eve.dom.values
  "The browser's JavaScript values as eve.dom sees them (both ends of the browser transport: eve.dom.proxy's host and
   eve.dom.screen's executor). eve.dom.codec, the bytes, is eve's portable src/eve/dom/codec.cljc.")

(defn plain-object?
  "A plain JS object: its prototype is Object.prototype or null."
  [x]
  (and (some? x) (identical? "object" (goog/typeOf x))
       (let [p (js/Object.getPrototypeOf x)]
         (or (nil? p) (identical? p (.-prototype js/Object))))))

(defn data?
  "Does x copy by value (primitives, arrays and plain objects of them, to depth 8, no cycles)? A value carrying
   behaviour (a function anywhere inside) or a host object stays where it is, crossing as a handle."
  ([x] (data? x 0))
  ([x depth]
   (cond
     (or (nil? x) (undefined? x) (boolean? x) (number? x) (string? x)) true
     (> depth 8) false
     (js/Array.isArray x) (.every x #(data? % (inc depth)))
     (plain-object? x) (.every (js/Object.keys x) #(data? (aget x %) (inc depth)))
     :else false)))

(defn type-name
  "The constructor name of a page-side object, for the guest's prototype and method caches."
  [x]
  (try (let [c (.-constructor x)]
         (or (when c (.-name c)) "Object"))
       (catch :default _ "Object")))

(defn copy-out
  "n bytes of a (possibly shared) Uint8Array from offset at, into a fresh unshared array."
  [^js src at n]
  (let [out (js/Uint8Array. n)]
    (.set out (.subarray src at (+ at n)))
    out))
