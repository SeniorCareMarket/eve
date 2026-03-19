(ns eve.alpha
  "Eve public API.
   Provides macros for SAB-backed types and the cross-process atom API.

   Columnar subsystems are in separate namespaces:
     eve.alpha.ds      — Dataset (columnar data frames)
     eve.alpha.tensor  — Tensor (N-dimensional arrays)
     eve.alpha.col     — Columnar ops (element-wise + index-space)"
  (:refer-clojure :exclude [deftype extend-type atom])
  (:require [eve.deftype]
            [eve.atom :as eve-atom]
            [eve.array :as arr]))

(defmacro deftype
  "Define a SAB-backed type. See eve.deftype/eve-deftype."
  [& args]
  `(eve.deftype/eve-deftype ~@args))

(defmacro extend-type
  "Extend protocols on an existing eve/deftype."
  [& args]
  `(eve.deftype/eve-extend-type ~@args))

;; Atom API — delegates to eve.atom
(def atom eve-atom/atom)
(def close-atom-domain! eve-atom/close-atom-domain!)

;; Typed Array API
(def eve-array arr/eve-array)
