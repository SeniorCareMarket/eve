(ns eve2.map
  "Eve2 persistent HAMT map — delegation layer over eve.map.

   Establishes the eve2 API surface for persistent maps. All HAMT
   algorithms delegate to the battle-tested eve.map implementations.
   As ISlabIO unification progresses, the algorithms will be ported
   to use ISlabIO exclusively (one unified code path for CLJ/CLJS)."
  (:refer-clojure :exclude [hash-map])
  (:require
   [eve.map :as eve-map]
   [eve2.alloc :as alloc]
   [eve2.serialize :as ser]))

;;=============================================================================
;; Re-export constructors
;;=============================================================================

(def empty-hash-map
  "Return an empty EVE persistent hash-map."
  eve-map/empty-hash-map)

(def hash-map
  "Create a new EVE hash-map from key-value pairs."
  eve-map/hash-map)

(def into-hash-map
  "Create an EVE map from a collection of [key value] entries."
  eve-map/into-hash-map)

;;=============================================================================
;; Re-export make helpers (used by atom swap)
;;=============================================================================

(def make-eve-hash-map eve-map/make-eve-hash-map)
(def make-eve-hash-map-from-header eve-map/make-eve-hash-map-from-header)

;;=============================================================================
;; Re-export pool management
;;=============================================================================

(def reset-pools! eve-map/reset-pools!)
(def drain-pools! eve-map/drain-pools!)

;;=============================================================================
;; Re-export lifecycle (dispose, retire)
;;=============================================================================

(def dispose! eve-map/dispose!)
(def retire-replaced-path! eve-map/retire-replaced-path!)
(def retire-tree-diff! eve-map/retire-tree-diff!)

;;=============================================================================
;; Re-export validation
;;=============================================================================

(def validate-hamt-tree eve-map/validate-hamt-tree)
(def validate-eve-hash-map eve-map/validate-eve-hash-map)
(def validate-from-header-offset eve-map/validate-from-header-offset)

;;=============================================================================
;; Re-export public HAMT operations
;;=============================================================================

(def hamt-assoc-pub eve-map/hamt-assoc-pub)
(def hamt-dissoc-pub eve-map/hamt-dissoc-pub)
(def hamt-graft eve-map/hamt-graft)
(def hamt-graft-added? eve-map/hamt-graft-added?)
(def direct-assoc-pub eve-map/direct-assoc-pub)
(def direct-assoc-with-khs-pub eve-map/direct-assoc-with-khs-pub)
(def alloc-bytes-pub eve-map/alloc-bytes-pub)

;;=============================================================================
;; Re-export preduce
;;=============================================================================

#?(:cljs (def preduce eve-map/preduce))

;;=============================================================================
;; Re-export CAS abandoned cleanup
;;=============================================================================

(def free-cas-abandoned! eve-map/free-cas-abandoned!)

;;=============================================================================
;; Re-export collect helpers
;;=============================================================================

(def collect-tree-diff-offsets eve-map/collect-tree-diff-offsets)
(def collect-replaced-path-offsets eve-map/collect-replaced-path-offsets)
(def collect-retire-diff-offsets eve-map/collect-retire-diff-offsets)
