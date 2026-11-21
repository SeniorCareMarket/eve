(ns eve.fast-proto
  "(fast-satisfies? p x): clojure.core/satisfies?'s answer, without its cost on a miss.

   On the JVM, satisfies? on a value whose class does not implement the protocol walks the class's supers on every
   call (find-protocol-impl -> supers -> a fresh hash set). MEASURED 2026-11-20 ET, a ds-in-eve build: one such
   check per value encoded was 53% of the build's samples and 60% of its allocation; the read path's flat delivery
   cascade asked six more per value. Here: one isInstance on the protocol's interface (every inline implementation —
   every eve-deftype and eve type implements its protocols inline), else the class's answer remembered per protocol.
   The memory is dropped whenever the protocol is extended — extend replaces the protocol's :impls map, and an answer
   is kept only beside the :impls it was computed from — so an extend-type after a first miss is seen.

   bb, glojure and jank (whose protocols generate no JVM interface) answer with satisfies? itself, a runtime
   fn on those hosts. ClojureScript is the exception that shapes the entry point: its satisfies? is a
   COMPILE-TIME property test — the macro resolves the protocol symbol and emits a check for the property
   implementing types carry — so it must see the protocol as a literal at the call site, and a fn taking
   the protocol as a runtime argument silently answers false for every value (measured 2026-11-21 ET:
   the emitted check tested a property named after the argument, eve$fast_proto$p$, which no type
   carries; every cljs materialization of a delivered flat value died on it). fast-satisfies? is
   therefore a macro: under the ClojureScript compiler it expands to satisfies? at the call site (every
   call site in the tree passes a literal protocol symbol, as satisfies? itself requires); everywhere
   else it expands to the runtime fn fast-satisfies?-impl below.")

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

(defn fast-satisfies?-impl
  "The runtime arm of fast-satisfies? (the macro below is the entry point): satisfies?'s answer, in one
   instanceof for an inline implementation and one lookup for any other class (the JVM); satisfies?
   itself on the hosts whose satisfies? is a runtime fn. Never call this with a runtime protocol value
   from ClojureScript-compiled code — there the answer is the macro's, expanded at the call site."
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

(defmacro fast-satisfies?
  "True when x satisfies protocol p — satisfies?'s answer, without its cost on a miss (see the ns
   docstring). A macro for one host's sake: under the ClojureScript compiler (whose expansion env
   carries :ns) it expands to cljs.core/satisfies? at the call site, where the protocol must be the
   literal symbol satisfies? itself requires; on every other host it expands to the runtime fn
   fast-satisfies?-impl, which takes the protocol's value. The protocol argument is therefore a
   literal protocol symbol at every call site, exactly as with satisfies?."
  [p x]
  (if (:ns &env)
    (list 'cljs.core/satisfies? p x)
    (list 'eve.fast-proto/fast-satisfies?-impl p x)))
