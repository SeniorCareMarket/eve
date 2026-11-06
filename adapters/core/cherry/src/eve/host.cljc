(ns eve.host
  "eve.host's macros on cherry (host.cljs has the rest, as functions): the
   names that take forms rather than values. Their expansions name no core
   function eve.host also defines, which cherry would read as eve.host's own.")

(defmacro locking
  "JavaScript runs one thread an isolate: the body, as cljs.core's locking."
  [x & body]
  `(do ~x ~@body))

(defmacro defdelay
  "(defonce name (delay body))."
  [nm & body]
  `(defonce ~nm (delay ~@body)))

(defmacro extend-default
  "proto for every value, nil included: extend-type on nil and on default
   (cherry has extend-type, not extend-protocol)."
  [proto & impls]
  `(do (extend-type nil ~proto ~@impls)
       (extend-type ~'default ~proto ~@impls)))

(defmacro field
  "(field x f): the value of field f of x, a type's instance: (.-f x) (eve.host's name for a field read; on jank the
   completion's read by name)."
  [x f]
  (list (symbol (str ".-" (name f))) x))
