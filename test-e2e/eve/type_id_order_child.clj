(ns eve.type-id-order-child
  "One process of eve.type-id-order-test: defines the user types Alpha and Beta
   (eve.deftype-proto.macros/deftype, no declared ids) in the order it is given,
   in the namespace eve.type-id-order, then creates or joins the mmap domain at
   base. The creator writes an Alpha block (x 7) and a Beta block (x 8) — its
   type id at byte 0, x at the field's offset — and publishes their offsets in
   the atom; a joiner reads each block back through the header constructor
   registered under its own type ids. Prints an EDN map."
  (:require [clojure.string :as str]
            [eve.atom :as atom]
            [eve.slab-io :as slab]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.macros]
            [eve.deftype-proto.macros.registry :as reg]
            [eve.deftype-proto.serialize :as ser]))

(def ^:private forms
  {"alpha" '[(eve.deftype-proto.macros/deftype Alpha [^:int32 x] IX (x-of [_] x))
             (eve.deftype-proto.serialize/register-header-constructor! Alpha-type-id (fn [sio off] (Alpha. sio off)))]
   "beta"  '[(eve.deftype-proto.macros/deftype Beta [^:int32 x] IX (x-of [_] x))
             (eve.deftype-proto.serialize/register-header-constructor! Beta-type-id (fn [sio off] (Beta. sio off)))]})

(defn- define-types! [order]
  (binding [*ns* (create-ns 'eve.type-id-order)]
    (refer-clojure)
    (eval '(defprotocol IX (x-of [this])))
    (doseq [t (str/split order #",") f (forms t)] (eval f))))

(defn- in-types [s] @(ns-resolve 'eve.type-id-order (symbol s)))

(defn- write-block! [sio type-name x]
  (let [{:keys [type-id fields]} (reg/lookup-type type-name)
        off (slab/alloc! sio 16)]
    (slab/write-u8! sio off 0 type-id)
    (slab/write-i32! sio off (:offset (first fields)) x)
    off))

(defn -main [order base role]
  (define-types! order)
  (let [ids {:alpha (in-types "Alpha-type-id") :beta (in-types "Beta-type-id")}
        _   (atom/persistent-atom-domain base)
        a   (atom/persistent-atom {:id :eve/main :persistent base} {})
        sio (atom/atom-sio a)
        out (if (= role "create")
              (do (reset! a {:alpha (write-block! sio "Alpha" 7) :beta (write-block! sio "Beta" 8)})
                  {})
              (let [x-of (in-types "x-of")]
                {:read (into {} (for [[k off] @a]
                                  (let [v ((ser/get-header-constructor (alloc/read-header-tag sio off)) sio off)]
                                    [k [(.getSimpleName (class v)) (x-of v)]])))}))]
    (prn (assoc out :ids ids :order order))
    (shutdown-agents)
    (System/exit 0)))
