(ns eve.alpha
  "Eve public API.
   Provides macros for SAB-backed types and the cross-process atom API."
  (:refer-clojure :exclude [deftype extend-type atom])
  (:require [eve.deftype]
            [eve.atom :as eve-atom]
            [eve.array :as arr]
            [eve.dataset :as ds]
            [eve.tensor :as tensor]
            [eve.dataset.functional :as func]
            [eve.dataset.argops :as argops]))

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

;; Dataset API
(def dataset ds/dataset)
(def dataset? ds/dataset?)
(def ds-column ds/column)
(def ds-column-names ds/column-names)
(def ds-row-count ds/row-count)
(def ds-dtypes ds/dtypes)
(def ds-select-columns ds/select-columns)
(def ds-add-column ds/add-column)
(def ds-drop-column ds/drop-column)
(def ds-rename-columns ds/rename-columns)
(def ds-reindex ds/reindex)
(def ds-filter-rows ds/filter-rows)
(def ds-sort-by-column ds/sort-by-column)
(def ds-head ds/head)
(def ds-tail ds/tail)
(def ds-slice ds/slice)

;; Tensor API
(def tensor? tensor/tensor?)
(def tensor-from-array tensor/from-array)
(def tensor-zeros tensor/zeros)
(def tensor-ones tensor/ones)
(def tensor-shape tensor/shape)
(def tensor-dtype tensor/dtype)
(def tensor-rank tensor/rank)
(def tensor-contiguous? tensor/contiguous?)
(def tensor-mget tensor/mget)
(def tensor-mset! tensor/mset!)
(def tensor-reshape tensor/reshape)
(def tensor-transpose tensor/transpose)
(def tensor-slice-axis tensor/slice-axis)
(def tensor-emap tensor/emap)
(def tensor-ereduce tensor/ereduce)
(def tensor-to-array tensor/to-array)
(def tensor-to-dataset tensor/to-dataset)

;; Columnar Functional API (element-wise ops on EveArrays)
(def col-add func/add)
(def col-sub func/sub)
(def col-mul func/mul)
(def col-div func/div)
(def col-sum func/sum)
(def col-mean func/mean)
(def col-min-val func/min-val)
(def col-max-val func/max-val)
(def col-gt func/gt)
(def col-lt func/lt)
(def col-eq func/eq)
(def col-emap func/emap)
(def col-emap2 func/emap2)

;; Columnar Argops API (index-space ops on EveArrays)
(def argsort argops/argsort)
(def argfilter argops/argfilter)
(def argmin argops/argmin)
(def argmax argops/argmax)
(def arggroup argops/arggroup)
(def take-indices argops/take-indices)
