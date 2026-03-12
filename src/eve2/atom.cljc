(ns eve2.atom
  "Eve2 cross-process mmap atom — delegation layer over eve.atom.

   Establishes the eve2 API surface for persistent atoms. All mmap
   operations, epoch GC, and CAS mechanics delegate to the battle-tested
   eve.atom implementation. Port after data structures are stable."
  (:refer-clojure :exclude [atom])
  (:require
   [eve.atom :as eve-atom]
   [eve2.alloc :as alloc]))

;;=============================================================================
;; Domain lifecycle
;;=============================================================================

(def persistent-atom-domain
  "Open or create an mmap-backed atom domain at base-path.
   Caches domains by path. Returns an MmapAtomDomain."
  eve-atom/persistent-atom-domain)

(def join-atom-domain
  "Join an existing mmap-backed atom domain at base-path."
  eve-atom/join-atom-domain)

(def close-atom-domain!
  "Release the worker slot and cancel heartbeat for a domain."
  eve-atom/close-atom-domain!)

(def close!
  "Release the worker slot. Safe to call multiple times (idempotent)."
  eve-atom/close!)

;;=============================================================================
;; Atom creation
;;=============================================================================

(def persistent-atom
  "Create or look up a named persistent atom in an mmap-backed domain."
  eve-atom/persistent-atom)

(def lookup-or-create-mmap-atom!
  "Look up an atom by keyword string, or create it if not found."
  eve-atom/lookup-or-create-mmap-atom!)

;;=============================================================================
;; JVM: heap-backed atom (non-persistent, analogous to SAB atoms)
;;=============================================================================

#?(:clj
   (def atom
     "Create or look up an atom (JVM).
      Without :persistent, creates in-memory heap-backed atom.
      With :persistent, creates mmap-backed cross-process atom."
     eve-atom/atom))
