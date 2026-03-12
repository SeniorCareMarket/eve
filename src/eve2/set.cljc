(ns eve2.set
  "Eve2 persistent set — delegation layer over eve.set.

   Wraps the eve.set implementations which themselves wrap eve.map's
   HAMT for storage. As eve2/map is ported to ISlabIO, this namespace
   will re-delegate to the new eve2/map internals."
  (:refer-clojure :exclude [hash-set])
  (:require
   [eve.set :as eve-set]
   [eve2.alloc :as alloc]))

;;=============================================================================
;; Re-export constructors
;;=============================================================================

(def empty-hash-set
  "Return an empty EVE persistent hash-set."
  eve-set/empty-hash-set)

(def hash-set
  "Create a new EVE hash-set from values."
  eve-set/hash-set)

(def into-hash-set
  "Create an EVE hash-set from a collection."
  eve-set/into-hash-set)

;;=============================================================================
;; Re-export pool management
;;=============================================================================

(def reset-pools! eve-set/reset-pools!)
(def drain-pools! eve-set/drain-pools!)

;;=============================================================================
;; Re-export lifecycle
;;=============================================================================

(def dispose! eve-set/dispose!)
(def retire-replaced-path! eve-set/retire-replaced-path!)
(def retire-tree-diff! eve-set/retire-tree-diff!)
