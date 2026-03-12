(ns eve2.serialize
  "Thin wrapper over eve.deftype-proto.serialize for the eve2 namespace tree.

   Re-exports serialization constants, serialize/deserialize functions,
   and the type-constructor registries. As ISlabIO unification progresses,
   the serialize functions will be rewritten to use ISlabIO exclusively
   (eliminating the ~44 remaining DataView calls on CLJS)."
  (:require
   [eve.deftype-proto.serialize :as ser]
   [eve.deftype-proto.data :as d]))

;;=============================================================================
;; Fast-path type tags (shared)
;;=============================================================================

(def ^:const FAST_TAG_FALSE        ser/FAST_TAG_FALSE)
(def ^:const FAST_TAG_TRUE         ser/FAST_TAG_TRUE)
(def ^:const FAST_TAG_INT32        ser/FAST_TAG_INT32)
(def ^:const FAST_TAG_FLOAT64      ser/FAST_TAG_FLOAT64)
(def ^:const FAST_TAG_STRING_SHORT ser/FAST_TAG_STRING_SHORT)
(def ^:const FAST_TAG_STRING_LONG  ser/FAST_TAG_STRING_LONG)
(def ^:const FAST_TAG_KEYWORD_SHORT ser/FAST_TAG_KEYWORD_SHORT)
(def ^:const FAST_TAG_KEYWORD_LONG  ser/FAST_TAG_KEYWORD_LONG)
(def ^:const FAST_TAG_KEYWORD_NS_SHORT ser/FAST_TAG_KEYWORD_NS_SHORT)
(def ^:const FAST_TAG_KEYWORD_NS_LONG  ser/FAST_TAG_KEYWORD_NS_LONG)
(def ^:const FAST_TAG_UUID         ser/FAST_TAG_UUID)
(def ^:const FAST_TAG_SYMBOL_SHORT ser/FAST_TAG_SYMBOL_SHORT)
(def ^:const FAST_TAG_SYMBOL_NS_SHORT ser/FAST_TAG_SYMBOL_NS_SHORT)
(def ^:const FAST_TAG_DATE         ser/FAST_TAG_DATE)
(def ^:const FAST_TAG_INT64        ser/FAST_TAG_INT64)

;; SAB pointer tags
(def ^:const FAST_TAG_SAB_MAP  ser/FAST_TAG_SAB_MAP)
(def ^:const FAST_TAG_SAB_SET  ser/FAST_TAG_SAB_SET)
(def ^:const FAST_TAG_SAB_VEC  ser/FAST_TAG_SAB_VEC)
(def ^:const FAST_TAG_SAB_LIST ser/FAST_TAG_SAB_LIST)

;; Flat collection tags
(def ^:const FAST_TAG_FLAT_MAP ser/FAST_TAG_FLAT_MAP)
(def ^:const FAST_TAG_FLAT_VEC ser/FAST_TAG_FLAT_VEC)

;; Scalar/typed-array block type IDs
(def ^:const SCALAR_BLOCK_TYPE_ID    ser/SCALAR_BLOCK_TYPE_ID)
(def ^:const EVE_ARRAY_SLAB_TYPE_ID  ser/EVE_ARRAY_SLAB_TYPE_ID)
(def ^:const EVE_OBJ_SLAB_TYPE_ID    ser/EVE_OBJ_SLAB_TYPE_ID)

;;=============================================================================
;; Constructor registries (shared)
;;=============================================================================

#?(:cljs
   (do
     (def register-sab-type-constructor!  ser/register-sab-type-constructor!)
     (def register-header-constructor!    ser/register-header-constructor!)
     (def get-header-constructor          ser/get-header-constructor)
     (def register-header-disposer!       ser/register-header-disposer!)
     (def get-header-disposer             ser/get-header-disposer)
     (def register-cljs-to-sab-builder!   ser/register-cljs-to-sab-builder!)
     (def convert-to-sab                  ser/convert-to-sab)
     (def set-direct-map-encoder!         ser/set-direct-map-encoder!)
     (def set-typed-array-encoder!        ser/set-typed-array-encoder!)
     (def register-record-type!           ser/register-record-type!)
     (def encode-sab-pointer              ser/encode-sab-pointer)))

#?(:clj
   (do
     (def register-jvm-type-constructor!   ser/register-jvm-type-constructor!)
     (def register-jvm-header-constructor! ser/register-jvm-header-constructor!)
     (def get-jvm-type-constructor         ser/get-jvm-type-constructor)
     (def get-jvm-header-constructor       ser/get-jvm-header-constructor)))

;;=============================================================================
;; Serialize/Deserialize (CLJS — JVM uses eve.mem equivalents)
;;=============================================================================

#?(:cljs
   (do
     (def serialize-key             ser/serialize-key)
     (def serialize-val             ser/serialize-val)
     (def serialize-element         ser/serialize-element)
     (def serialize-flat-collection ser/serialize-flat-collection)
     (def serialize-flat-element    ser/serialize-flat-element)
     (def deserialize-element       ser/deserialize-element)
     (def deserialize-from-dv       ser/deserialize-from-dv)
     (def dispose-sab-value!        ser/dispose-sab-value!)
     (def clear-deser-caches!       ser/clear-deser-caches!)))
