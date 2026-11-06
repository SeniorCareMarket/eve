(ns eve.host-completion
  "What eve.host loads first on the babashka seat.

   Babashka's SCI refuses a deftype that declares the map interfaces
   piecemeal:

     Babashka's deftype supports full map types (add IPersistentMap to
     the interface list). Alternatively, use reify for individual
     interfaces like ILookup, Associative.

   A vanilla library type written for the JVM — datascript.lru's LRU,
   which declares clojure.lang.ILookup and clojure.lang.Associative
   and implements exactly the lookup surface it offers — therefore
   cannot load on this seat at all, though the same declaration is an
   ordinary full citizen on the JVM. Measured: stock bb with no Eve
   refuses it identically; the refusal is the declaration shape
   against SCI, not an Eve interaction. The rule matches only the
   clojure.lang-qualified spellings of those two interfaces, and only
   when clojure.lang.IPersistentMap is absent (bb 1.13.226).

   The seat's answer is presentation, in the seat: loading this
   namespace re-roots clojure.core/deftype — the pattern the jank
   completion uses for its core re-roots — with a presenter that
   appends clojure.lang.IPersistentMap to a declaration listing
   ILookup or Associative without it, and passes every other deftype
   through to SCI's own deftype argument-identical. The library is
   untouched; namespaces compiled after this one see the presenter.

   The presenter is a plain function, not a macro: SCI calls a macro
   var's root with (form env . args) only when the root is a plain
   fn — a defmacro product is dispatched to its inner fn with plain
   args instead (measured on this seat).

   What the presentation means for the type: SCI implements a deftype
   listing IPersistentMap as its map type, synthesizing the map
   surface the declaration does not give — the methods it did not
   declare (count, seq, empty, cons, without) answer by throwing
   \"Method not implemented\", and map? answers true. A type that
   offers only lookup and assoc on the JVM offers only lookup and
   assoc here; nothing it does offer changes meaning (the upstream
   datascript.test.lru suite passes against the presented type).")

(defonce ^:private sci-deftype
  @#'clojure.core/deftype)

(def ^:private piecemeal-map-interfaces
  "The interfaces SCI's deftype refuses piecemeal (measured)."
  '#{clojure.lang.ILookup clojure.lang.Associative})

(def ^:private full-map-interface 'clojure.lang.IPersistentMap)

(defn- interface-symbols
  "The bare symbols heading a deftype's spec sections, in order —
   every top-level symbol in opts except a keyword option's value."
  [opts]
  (loop [xs (seq opts), acc []]
    (cond (nil? xs)            acc
          (keyword? (first xs)) (recur (nnext xs) acc)
          (symbol? (first xs))  (recur (next xs) (conj acc (first xs)))
          :else                 (recur (next xs) acc))))

(defn- present-deftype
  "clojure.core/deftype, presented for SCI: a declaration of
   clojure.lang.ILookup or clojure.lang.Associative is presented as
   the full map declaration SCI takes, by appending
   clojure.lang.IPersistentMap after the specs (the position SCI
   accepts, measured); any other declaration is passed through
   unchanged."
  [form env type-name fields & opts]
  (let [ifaces (interface-symbols opts)]
    (if (and (some piecemeal-map-interfaces ifaces)
             (not (some #(= full-map-interface %) ifaces)))
      (apply sci-deftype form env type-name fields
             (concat opts [full-map-interface]))
      (apply sci-deftype form env type-name fields opts))))

(alter-var-root #'clojure.core/deftype (fn [_] present-deftype))
