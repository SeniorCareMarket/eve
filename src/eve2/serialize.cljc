(ns eve2.serialize
  "Eve2 serializer bridge.

   Re-exports type tags, constructor registries, and serialize/deserialize
   functions from eve.deftype-proto.serialize.

   Phase 2 of EVE2_IMPL_PLAN.md: 'Initially wraps eve.deftype-proto.serialize.
   As ISlabIO unification progresses, the serialize functions get rewritten to
   use ISlabIO exclusively.'"
  (:require
   [eve.deftype-proto.serialize :as ser]
   [eve.deftype-proto.data :as d]))

;;=============================================================================
;; Type tags (FAST_TAG encoding for slab pointers)
;;=============================================================================

(def ^:const FAST_TAG_SAB_MAP  ser/FAST_TAG_SAB_MAP)
(def ^:const FAST_TAG_SAB_SET  ser/FAST_TAG_SAB_SET)
(def ^:const FAST_TAG_SAB_VEC  ser/FAST_TAG_SAB_VEC)
(def ^:const FAST_TAG_SAB_LIST ser/FAST_TAG_SAB_LIST)

;;=============================================================================
;; Serialize / deserialize
;;=============================================================================

(def serialize-key     ser/serialize-key)
(def serialize-val     ser/serialize-val)
(def serialize-element ser/serialize-element)
(def encode-sab-pointer ser/encode-sab-pointer)

;;=============================================================================
;; Constructor registries (CLJS type tag → constructor fn)
;;=============================================================================

(def register-type-constructor!    ser/register-type-constructor!)
(def register-header-constructor!  ser/register-header-constructor!)

#?(:clj
   (do
     (def register-jvm-type-constructor!   ser/register-jvm-type-constructor!)
     (def register-jvm-header-constructor! ser/register-jvm-header-constructor!)
     (def get-jvm-type-constructor         ser/get-jvm-type-constructor)))

;;=============================================================================
;; Data protocol re-exports (IDirectSerialize, ISabStorable, etc.)
;;=============================================================================

(def IDirectSerialize d/IDirectSerialize)
(def ISabStorable     d/ISabStorable)
(def IsEve            d/IsEve)
(def IEveRoot         d/IEveRoot)
(def ISabRetirable    d/ISabRetirable)
