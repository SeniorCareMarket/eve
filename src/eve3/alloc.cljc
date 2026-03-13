(ns eve3.alloc
  "Eve3 allocator bridge.

   Re-exports ISlabIO and slab-qualified offset helpers from
   eve.deftype-proto.alloc. Unlike eve2, there is NO dynamic var for
   the JVM slab context — sio is always threaded explicitly.

   CLJS: use `cljs-sio` as the ISlabIO singleton.
   CLJ:  create a JvmSlabCtx and pass it through."
  (:require
   [eve.deftype-proto.alloc :as alloc]))

;;=============================================================================
;; ISlabIO protocol — re-exported
;;=============================================================================

(def ISlabIO alloc/ISlabIO)

(def -sio-read-u8     alloc/-sio-read-u8)
(def -sio-write-u8!   alloc/-sio-write-u8!)
(def -sio-read-u16    alloc/-sio-read-u16)
(def -sio-write-u16!  alloc/-sio-write-u16!)
(def -sio-read-i32    alloc/-sio-read-i32)
(def -sio-write-i32!  alloc/-sio-write-i32!)
(def -sio-read-bytes  alloc/-sio-read-bytes)
(def -sio-write-bytes! alloc/-sio-write-bytes!)
(def -sio-alloc!      alloc/-sio-alloc!)
(def -sio-free!       alloc/-sio-free!)
(def -sio-copy-block! alloc/-sio-copy-block!)

;;=============================================================================
;; Constants
;;=============================================================================

(def ^:const NIL_OFFSET alloc/NIL_OFFSET)

;;=============================================================================
;; Platform-specific allocator access
;;=============================================================================

#?(:cljs
   (do
     ;; CljsSlabIO singleton — pass as sio to constructors and algorithms
     (defonce cljs-sio (alloc/->CljsSlabIO)))
   :clj
   (do
     ;; JVM: create slab contexts, pass them explicitly
     (def make-jvm-slab-ctx       alloc/make-jvm-slab-ctx)
     (def make-jvm-heap-slab-ctx  alloc/make-jvm-heap-slab-ctx)
     (def refresh-jvm-slab-regions! alloc/refresh-jvm-slab-regions!)))
