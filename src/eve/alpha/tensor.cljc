(ns eve.alpha.tensor
  "Eve Tensor public API.

   N-dimensional views over EveArrays with zero-copy reshaping,
   transposing, and slicing.

   Usage:
     (require '[eve.alpha.tensor :as tensor])
     (tensor/from-array (e/eve-array :float64 (range 12)) [3 4])"
  (:require
   [eve.tensor :as t]))

;; Construction & predicates
(def tensor? t/tensor?)
(def from-array t/from-array)
(def zeros t/zeros)
(def ones t/ones)

;; Shape inspection
(def shape t/shape)
(def dtype t/dtype)
(def rank t/rank)
(def contiguous? t/contiguous?)

;; Element access
(def mget t/mget)
(def mset! t/mset!)

;; Shape ops (zero-copy)
(def reshape t/reshape)
(def transpose t/transpose)
(def slice-axis t/slice-axis)

;; Bulk ops
(def emap t/emap)
(def ereduce t/ereduce)
(def to-array t/to-array)
(def to-dataset t/to-dataset)
