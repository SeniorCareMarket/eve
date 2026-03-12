(ns eve2.alloc
  "Thin wrapper over eve.deftype-proto.alloc for the eve2 namespace tree.

   Re-exports ISlabIO, CljsSlabIO (CLJS), JvmSlabCtx (CLJ), offset
   encoding/decoding, and allocation primitives. The eve1 allocator is
   battle-tested and shared during the eve1→eve2 transition."
  #?(:cljs (:refer-clojure :exclude [atom]))
  (:require
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]))

;;=============================================================================
;; Re-export offset encoding/decoding (shared)
;;=============================================================================

(def encode-slab-offset alloc/encode-slab-offset)
(def decode-class-idx   alloc/decode-class-idx)
(def decode-block-idx   alloc/decode-block-idx)

(def ^:const NIL_OFFSET alloc/NIL_OFFSET)

;;=============================================================================
;; Re-export ISlabIO protocol
;;=============================================================================

;; The protocol itself — users can satisfy it for custom backends.
;; Protocol vars are re-exported so callers can use eve2.alloc/ISlabIO
;; and eve2.alloc/-sio-read-i32 etc. directly.

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
;; CLJS: module-level DataView state + allocation
;;=============================================================================

#?(:cljs
   (do
     (def resolve-dv!  alloc/resolve-dv!)
     (def resolve-u8!  alloc/resolve-u8!)
     (def resolved-dv  alloc/resolved-dv)
     (def resolved-u8  alloc/resolved-u8)
     (def resolved-base alloc/resolved-base)
     (def alloc-offset alloc/alloc-offset)
     (def free!        alloc/free!)
     (def init!        alloc/init!)))

;;=============================================================================
;; CLJ: JvmSlabCtx + dynamic var
;;=============================================================================

#?(:clj
   (do
     (def ^:dynamic *jvm-slab-ctx*
       "Alias for alloc/*jvm-slab-ctx* — the current JVM slab I/O context."
       nil)

     (defn jvm-slab-ctx
       "Get the currently-bound JVM slab context, preferring eve2's binding
        then falling back to eve1's."
       []
       (or *jvm-slab-ctx* alloc/*jvm-slab-ctx*))

     (def start-jvm-alloc-log!    alloc/start-jvm-alloc-log!)
     (def drain-jvm-alloc-log!    alloc/drain-jvm-alloc-log!)
     (def start-jvm-replaced-log! alloc/start-jvm-replaced-log!)
     (def drain-jvm-replaced-log! alloc/drain-jvm-replaced-log!)
     (def log-replaced-node!      alloc/log-replaced-node!)))
