(ns eve.alpha
  "Eve public API.

   Provides shared-memory persistent data structures and atoms.
   Re-exports core functionality for standalone library consumption.

   Columnar subsystems are in separate namespaces:
     eve.alpha.ds      — Dataset (columnar data frames)
     eve.alpha.tensor  — Tensor (N-dimensional arrays)
     eve.alpha.col     — Columnar ops (element-wise + index-space)"
  (:refer-clojure :exclude [atom aget aset hash-map hash-set])
  (:require
   [eve.atom :as a]
   [eve.array :as arr]
   [eve.deftype-proto.alloc :as eve-alloc]
   [eve.map :as eve-map]
   [eve.vec]
   [eve.set :as eve-set]
   [eve.list]))

;; Auto-initialize slab allocator on namespace load
(defonce ^:private init-promise (eve-alloc/init!))

;; Atom API
(def atom a/atom)
(def atom-domain a/atom-domain)

;; Data Structure API
(def hash-map eve-map/hash-map)
(def empty-hash-map eve-map/empty-hash-map)
(def hash-set eve-set/hash-set)
(def empty-hash-set eve-set/empty-hash-set)

;; Type Predicates
(def shared-atom? a/shared-atom?)

;; Cross-Worker Support
(def sab-transfer-data a/sab-transfer-data)

;; Typed Array API
(def eve-array arr/eve-array)
(def aget arr/aget)
(def aset! arr/aset!)
(def get-typed-view arr/get-typed-view)
