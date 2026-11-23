(ns eve.wire
  "Eve's wire codec: what crosses between hosts that do not share an atom
   domain — threads/workers of different heaps, processes, machines — is
   flat data, one chunk of FORMAT-V1 bytes, the same bytes on every host
   (John, 2026-11-20: \"We want eve to register its flat type as the handler
   for all wire traffic\"; \"They should all do it the same\").

   encode: any value -> host bytes (one flat chunk). A view is consumed (its
           subtree packed); a persistent value is packed (the caller reads it
           under its pin, e.g. inside its swap!); a node of a larger flat value
           is packed as its own chunk; host data is packed. A host object (a
           fn, an object of no eve kind) is refused.
   decode: host bytes -> the flat value they hold.

   The codec is registered here, as a Transit handler set is registered for
   an app's wire: set-codec! replaces it process-wide (both ends of a wire
   must agree). eve.thread's adapters move only what encode answers.
   Members of one atom domain (a network atom's replicas, the disk rung's
   processes) share that domain's own block format instead: that is the
   domain, not the wire."
  (:require [eve.flat.chunk :as chunk]
            [eve.deftype-proto.data :as d]
            [eve.flat.source :as source]))

(defn- flat-encode [v]
  (chunk/pack-rep (chunk/to-rep v)))

(defn- flat-decode [bs]
  (let [h (chunk/open bs)]
    h))

(defonce ^:private codec
  (atom {:encode flat-encode :decode flat-decode}))

(defn set-codec!
  "Replace the wire codec, process-wide: {:encode (fn [v] -> bytes)
   :decode (fn [bytes] -> v)}. Both ends of a wire must agree."
  [c]
  (reset! codec c))

(defn encode
  "v as wire bytes: one flat chunk (the registered codec's)."
  [v]
  ((:encode @codec) v))

(defn decode
  "The value wire bytes hold (the registered codec's)."
  [bs]
  ((:decode @codec) bs))

(defn data?
  "Is v data a wire can carry — scalars, host collections of data, eve values
   (flat handles, views, persistent values) — and no host object (a fn, a
   var, an object of no eve kind)? A walk, no packing: the cheap test a
   sender runs before encoding."
  [v]
  #_{:splint/disable [lint/identical-branches]}   (cond
                                                    (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v)
                                                        (char? v) (uuid? v) (inst? v)) true
                                                    (satisfies? chunk/IFlatHandle v) true
                                                    (record? v) false
                                                    (map? v) (every? (fn [[k x]] (and (data? k) (data? x))) v)
                                                    (coll? v) (every? data? v)
                                                    :else (or (satisfies? d/IEveRoot v)
                                                              (satisfies? d/ISabpType v)
                                                              (satisfies? source/ISister v)
                                                              #?(:clj (instance? (Class/forName "[B") v)
                                                                 :cljs (instance? js/Uint8Array v)
                                                                 :default false))))

(defn encodable?
  "Can v cross a wire (it holds no host object)?"
  [v]
  (and (data? v)
       (try (encode v) true
            (catch #?(:cljs :default :lpy python/Exception :default Throwable) _ false))))
