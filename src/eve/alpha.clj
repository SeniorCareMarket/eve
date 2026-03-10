(ns eve.alpha
  "Eve macro API.
   Provides deftype and extend-type macros for SAB-backed types."
  (:refer-clojure :exclude [deftype extend-type])
  (:require [eve.deftype]))

(defmacro deftype
  "Define a SAB-backed type. See eve.deftype/eve-deftype."
  [& args]
  `(eve.deftype/eve-deftype ~@args))

(defmacro extend-type
  "Extend protocols on an existing eve/deftype."
  [& args]
  `(eve.deftype/eve-extend-type ~@args))
