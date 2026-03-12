(ns eve2.alpha
  "Eve2 public API entry point.

   Unified .cljc entry point that re-exports:
   - eve2/deftype macro (via eve2.deftype)
   - Data structure constructors (map, vec, set, list)
   - Atom API (persistent cross-process atoms)

   This namespace is the primary consumer-facing API for eve2."
  (:refer-clojure :exclude [hash-map hash-set #?(:clj atom)])
  (:require
   [eve2.alloc :as alloc]
   [eve2.serialize :as ser]
   [eve2.map :as eve2-map]
   [eve2.vec :as eve2-vec]
   [eve2.set :as eve2-set]
   [eve2.list :as eve2-list]
   [eve2.atom :as eve2-atom]
   #?@(:cljs [[eve.deftype-proto.alloc :as eve-alloc]]))
  #?(:clj (:require [eve2.deftype])))

;;=============================================================================
;; Map API
;;=============================================================================

(def hash-map
  "Create a new EVE hash-map from key-value pairs."
  eve2-map/hash-map)

(def empty-hash-map
  "Return an empty EVE persistent hash-map."
  eve2-map/empty-hash-map)

(def into-hash-map
  "Create an EVE map from a collection of [key value] entries."
  eve2-map/into-hash-map)

;;=============================================================================
;; Set API
;;=============================================================================

(def hash-set
  "Create a new EVE hash-set from values."
  eve2-set/hash-set)

(def empty-hash-set
  "Return an empty EVE persistent hash-set."
  eve2-set/empty-hash-set)

(def into-hash-set
  "Create an EVE hash-set from a collection."
  eve2-set/into-hash-set)

;;=============================================================================
;; Vector API
;;=============================================================================

(def empty-vec
  "Return an empty EVE persistent vector."
  eve2-vec/empty-sab-vec)

(def vec
  "Create an EVE persistent vector from a collection."
  eve2-vec/sab-vec)

;;=============================================================================
;; List API
;;=============================================================================

(def empty-list
  "Return an empty EVE persistent list."
  eve2-list/empty-sab-list)

(def into-list
  "Create an EVE persistent list from a collection."
  eve2-list/into-eve-list)

;;=============================================================================
;; Atom API
;;=============================================================================

(def persistent-atom
  "Create or look up a named persistent atom in an mmap-backed domain."
  eve2-atom/persistent-atom)

(def persistent-atom-domain
  "Open or create an mmap-backed atom domain at base-path."
  eve2-atom/persistent-atom-domain)

(def close-atom-domain!
  "Release the worker slot and cancel heartbeat for a domain."
  eve2-atom/close-atom-domain!)

#?(:clj
   (def atom
     "Create or look up an atom (JVM)."
     eve2-atom/atom))

;;=============================================================================
;; CLJS: auto-initialize slab allocator on namespace load
;;=============================================================================

#?(:cljs
   (defonce ^:private init-promise (eve-alloc/init!)))
