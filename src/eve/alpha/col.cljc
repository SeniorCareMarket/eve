(ns eve.alpha.col
  "Eve Columnar Operations public API.

   Element-wise and index-space operations on EveArrays. Works on
   standalone arrays and dataset columns (columns ARE EveArrays).

   Usage:
     (require '[eve.alpha.col :as col])
     (col/sum my-array)
     (col/argsort my-array :asc)"
  (:require
   [eve.dataset.functional :as func]
   [eve.dataset.argops :as argops]))

;; Arithmetic (element-wise, return new EveArray)
(def add func/add)
(def sub func/sub)
(def mul func/mul)
(def div func/div)

;; Aggregations (return scalars)
(def sum func/sum)
(def mean func/mean)
(def min-val func/min-val)
(def max-val func/max-val)

;; Comparisons (return :uint8 mask arrays)
(def gt func/gt)
(def lt func/lt)
(def eq func/eq)

;; Element-wise map
(def emap func/emap)
(def emap2 func/emap2)

;; Index-space operations
(def argsort argops/argsort)
(def argfilter argops/argfilter)
(def argmin argops/argmin)
(def argmax argops/argmax)
(def arggroup argops/arggroup)
(def take-indices argops/take-indices)
