(ns eve2.list
  "Eve2 persistent list — delegation layer over eve.list.

   Establishes the eve2 API surface for persistent cons-cell lists.
   All algorithms delegate to the battle-tested eve.list implementations."
  (:require
   [eve.list :as eve-list]
   [eve2.alloc :as alloc]))

;;=============================================================================
;; Re-export constructors
;;=============================================================================

(def empty-sab-list
  "Return an empty EVE persistent list."
  eve-list/empty-sab-list)

(def sab-list
  "Create an EVE persistent list from a first value and optional rest."
  eve-list/sab-list)

(def into-eve-list
  "Create an EVE persistent list from a collection."
  eve-list/into-eve-list)

(def empty-sab-list-n
  "Return an empty EVE persistent list with columnar chunk encoding."
  eve-list/empty-sab-list-n)

(def sab-list-n
  "Create an EVE persistent list with columnar chunk encoding."
  eve-list/sab-list-n)

(def into-eve-list-n
  "Create an EVE persistent columnar list from a collection."
  eve-list/into-eve-list-n)

;;=============================================================================
;; Re-export pool management
;;=============================================================================

(def reset-pools! eve-list/reset-pools!)
(def drain-pools! eve-list/drain-pools!)

;;=============================================================================
;; Re-export lifecycle
;;=============================================================================

(def dispose! eve-list/dispose!)
(def retire-replaced-chain! eve-list/retire-replaced-chain!)
