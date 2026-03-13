(ns eve2.alloc
  "Eve2 allocator bridge.

   Re-exports ISlabIO and slab-qualified offset helpers from
   eve.deftype-proto.alloc. The allocator itself is shared between
   eve1 and eve2 — there's only one set of slab files / SharedArrayBuffers.

   Phase 2 of EVE2_IMPL_PLAN.md: 'Initially a thin wrapper over
   eve.deftype-proto.alloc … The allocator is battle-tested and shared
   with eve1 during transition. Forking would mean maintaining two
   allocators.'"
  (:require
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]))

;;=============================================================================
;; ISlabIO protocol — re-exported so eve2 consumers don't reach into eve1
;;=============================================================================

(def ISlabIO alloc/ISlabIO)

;; Re-export protocol functions for direct use in algorithms
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
;; Slab-qualified offset encoding — shared constants and helpers
;;=============================================================================

(def ^:const NIL_OFFSET alloc/NIL_OFFSET)

(def encode-slab-offset alloc/encode-slab-offset)
(def decode-class-idx   alloc/decode-class-idx)
(def decode-block-idx   alloc/decode-block-idx)

;;=============================================================================
;; Platform-specific allocator access
;;=============================================================================

#?(:cljs
   (do
     ;; CLJS: module-level DataView functions + CljsSlabIO singleton
     (def resolve-dv!  alloc/resolve-dv!)
     (def resolve-u8!  alloc/resolve-u8!)
     (def alloc-offset alloc/alloc-offset)
     (def free!        alloc/free!)
     (def init!        alloc/init!)
     ;; Module-level resolved state (for hot-path reads after resolve-dv!)
     (def resolved-dv  alloc/resolved-dv)
     (def resolved-u8  alloc/resolved-u8)
     (def resolved-base alloc/resolved-base)
     ;; CljsSlabIO singleton — use as `sio` arg in unified algorithms
     (defonce cljs-sio (alloc/->CljsSlabIO)))
   :clj
   (do
     ;; CLJ: JvmSlabCtx via dynamic var
     (def ^:dynamic *jvm-slab-ctx* nil)
     (def make-jvm-slab-ctx       alloc/make-jvm-slab-ctx)
     (def make-jvm-heap-slab-ctx  alloc/make-jvm-heap-slab-ctx)
     (def refresh-jvm-slab-regions! alloc/refresh-jvm-slab-regions!)
     ;; JVM scalar block helpers
     (def jvm-alloc-scalar-block! alloc/jvm-alloc-scalar-block!)
     (def jvm-read-scalar-block   alloc/jvm-read-scalar-block)
     (def jvm-read-header-type-byte alloc/jvm-read-header-type-byte)
     ;; JVM obj/array helpers
     (def jvm-write-obj!          alloc/jvm-write-obj!)
     (def jvm-write-eve-array!    alloc/jvm-write-eve-array!)))
