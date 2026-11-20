(ns eve.fast-proto
  "(fast-satisfies? p x): clojure.core/satisfies?'s answer, without its cost on a miss.

   On the JVM, satisfies? on a value whose class does not implement the protocol walks the class's supers on every
   call (find-protocol-impl -> supers -> a fresh hash set). MEASURED 2026-11-20 ET, a ds-in-eve build: one such
   check per value encoded was 53% of the build's samples and 60% of its allocation; the read path's flat delivery
   cascade asked six more per value. Here: one isInstance on the protocol's interface (every inline implementation —
   every eve-deftype and eve type implements its protocols inline), else the class's answer remembered per protocol.
   The memory is dropped whenever the protocol is extended — extend replaces the protocol's :impls map, and an answer
   is kept only beside the :impls it was computed from — so an extend-type after a first miss is seen.

   bb, glojure and jank (whose protocols generate no JVM interface) and every other host (ClojureScript's satisfies?
   is a property test already) answer with satisfies? itself.")

#?(:bb nil :jank nil :glj nil
   :clj (def ^:private ^java.util.concurrent.ConcurrentHashMap answers
          ;; protocol interface -> [the :impls the answers belong to, ConcurrentHashMap class -> Boolean]
          (java.util.concurrent.ConcurrentHashMap.)))

#?(:bb nil :jank nil :glj nil
   :clj (defn- remembered
          "The answers kept for protocol p (interface i), started over when p's :impls is not the one they were
           computed from."
          ^java.util.concurrent.ConcurrentHashMap [p ^Class i]
          (let [impls (:impls p)
                ^objects e (.get answers i)]
            (if (and e (identical? (aget e 0) impls))
              (aget e 1)
              (let [m (java.util.concurrent.ConcurrentHashMap.)]
                (.put answers i (object-array [impls m]))
                m)))))

(defn fast-satisfies?
  "True when x satisfies protocol p — satisfies?'s answer, in one instanceof for an inline implementation and one
   lookup for any other class (the JVM); satisfies? itself elsewhere."
  [p x]
  #?(:bb (satisfies? p x) :jank (satisfies? p x) :glj (satisfies? p x)
     :clj (let [^Class i (:on-interface p)]
            (cond
              (and i (.isInstance i x)) true
              (or (nil? x) (nil? i)) (boolean (satisfies? p x))
              :else (let [^java.util.concurrent.ConcurrentHashMap m (remembered p i)
                          c (class x)
                          hit (.get m c)]
                      (if (nil? hit)
                        (let [r (boolean (satisfies? p x))] (.put m c r) r)
                        (boolean hit)))))
     :default (satisfies? p x)))
