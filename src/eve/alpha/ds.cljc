(ns eve.alpha.ds
  "Eve Dataset public API.

   Columnar data frames: named columns (EveArrays) with atom-native storage.

   Usage:
     (require '[eve.alpha.ds :as ds])
     (ds/dataset {:price (e/eve-array :float64 [10.5 20.3])
                  :qty   (e/eve-array :int32 [100 200])})"
  (:require
   [eve.dataset :as dataset]
   [eve.array :as arr]))

;; Construction & predicates
(def dataset dataset/dataset)
(def dataset? dataset/dataset?)

;; Column access
(def column dataset/column)
(def column-names dataset/column-names)
(def row-count dataset/row-count)
(def dtypes dataset/dtypes)

;; Structural ops
(def select-columns dataset/select-columns)
(def add-column dataset/add-column)
(def drop-column dataset/drop-column)
(def rename-columns dataset/rename-columns)

;; Row ops
(def reindex dataset/reindex)
(def filter-rows dataset/filter-rows)
(def sort-by-column dataset/sort-by-column)
(def head dataset/head)
(def tail dataset/tail)
(def slice dataset/slice)
