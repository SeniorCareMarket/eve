(ns eve2.vec
  "Eve2 persistent vector — delegation layer over eve.vec.

   Establishes the eve2 API surface for persistent vectors. All trie
   algorithms delegate to the battle-tested eve.vec implementations.
   As ISlabIO unification progresses, the algorithms will be ported
   to use ISlabIO exclusively (one unified code path for CLJ/CLJS)."
  (:require
   [eve.vec :as eve-vec]
   [eve2.alloc :as alloc]))

;;=============================================================================
;; Re-export constructors
;;=============================================================================

(def empty-sab-vec
  "Return an empty EVE persistent vector."
  eve-vec/empty-sab-vec)

(def sab-vec
  "Create an EVE persistent vector from a CLJS collection."
  eve-vec/sab-vec)

(def empty-sab-vec-n
  "Return an empty EVE persistent vector with custom node size."
  eve-vec/empty-sab-vec-n)

(def sab-vec-n
  "Create an EVE persistent vector with custom node size."
  eve-vec/sab-vec-n)

;;=============================================================================
;; Re-export pool management
;;=============================================================================

(def reset-pools! eve-vec/reset-pools!)
(def drain-pools! eve-vec/drain-pools!)

;;=============================================================================
;; Re-export lifecycle
;;=============================================================================

(def dispose! eve-vec/dispose!)
(def retire-replaced-trie-path! eve-vec/retire-replaced-trie-path!)
