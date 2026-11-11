(ns eve.type-io-test
  (:refer-clojure :exclude [deftype])
  (:require #?(:cljs [cljs.test :refer-macros [deftest is testing]]
               :default [clojure.test :refer [deftest is testing]])
            #?(:cljs [cljs.reader :as edn] :default [clojure.edn :as edn])
            [eve.deftype #?@(:cljs [:refer-macros [deftype eve-deftype]]
                             :default [:refer [deftype eve-deftype]])]
            [eve.type-readers :as readers]
            [eve.deftype.registry :as registry]
            [eve.deftype.registry :as registry]
            [eve.encoding :as enc]))

(deftype Point [x y])
(deftype LegacyPoint [x] {:tag example.LegacyPoint})
(eve-deftype SharedPoint [^:int32 x y])
;; A declared field projection must not be emitted a second time by the
;; default-printer machinery (on either plain or slab-backed types).
(eve-deftype ProjectedPoint [^:int32 x]
  readers/ITypeFields
  (-type-fields [_] {:x x}))
(defprotocol IMove (move! [value]))
(eve-deftype MovingPoint [^:mutable ^:int32 x]
  IMove
  (move! [_] (set! x (inc x))))

(deftype Custom [x]
  {:tag example/custom
   :print (fn [v w _] (enc/write! w (str "#example/custom " (pr-str (readers/-type-fields v)))))
   :read (fn [v] (->Custom (:x v)))}
  readers/ITypeFields
  (-type-fields [_] {:x x}))

(deftype ProtocolPrint [x]
  {:tag example/protocol
   :read (fn [v] (->ProtocolPrint v))}
  IPrintWithWriter
  (-pr-writer [this w _]
    (enc/write! w (str "#example/protocol " (pr-str (:x (readers/-type-fields this))))))
  readers/ITypeFields
  (-type-fields [_] {:x x}))

(deftype TaggedMap [m]
  {:tag example/map
   :read (fn [data] (->TaggedMap data))}
  clojure.lang.ILookup
  (valAt [_ k] (get m k))
  (valAt [_ k nf] (get m k nf))
  clojure.lang.Associative
  (containsKey [_ k] (contains? m k))
  (entryAt [_ k] (find m k))
  (assoc [_ k v] (assoc m k v))
  clojure.lang.IPersistentMap
  (without [_ k] (dissoc m k))
  (assocEx [_ k v] (assoc m k v))
  clojure.lang.Counted
  (count [_] (count m))
  clojure.lang.IPersistentCollection
  (cons [_ v] (conj m v))
  (empty [_] {})
  (equiv [_ other] (= m other))
  clojure.lang.Seqable
  (seq [_] (seq m))
  readers/ITypeFields
  (-type-fields [_] m)
  IPrintWithWriter
  (-pr-writer [this w _]
    (enc/write! w (str "#example/map " (pr-str (readers/-type-fields this))))))

(deftest default-tagged-print-and-read
  (doseq [v [(->Point 1 "two") (->SharedPoint 1 "two") (->ProjectedPoint 1) (->MovingPoint 1)]]
    (let [s (pr-str v) restored (edn/read-string s)]
      (is (= (readers/-type-fields v) (readers/-type-fields restored)))
      (is (= s (pr-str restored)))
      #?(:cljs nil :default (is (= s (pr-str (read-string s)))))))
  (is (= "#eve.type-io-test/Point {:x 1, :y \"two\"}" (pr-str (->Point 1 "two")))))

(deftest custom-print-and-reader
  (is (= "#example/map {:a 42}" (pr-str (->TaggedMap {:a 42}))))
  (doseq [v [(->Custom 42) (->ProtocolPrint 42) (->TaggedMap {:a 42})]]
    (is (= (pr-str v) (pr-str (edn/read-string (pr-str v)))))
    #?(:cljs nil :default
       (is (= (pr-str v) (pr-str (read-string (pr-str v))))))))

#?(:bb nil :clj
   (deftest duplicate-print-is-readable
     (let [v (->Point 1 2) s (binding [*print-dup* true] (pr-str v))]
       (is (= (pr-str v) (pr-str (read-string s)))))))

(deftest default-reader-validates-payload
  (is (try (edn/read-string "#eve.type-io-test/Point [1 2]") false
           (catch #?(:cljs :default :clj Exception :lpy python/Exception :default Exception) _ true))))

#?(:lpy
   (deftest python-printer-agrees
     (let [v (->TaggedMap {:a 42})]
       (is (= (pr-str v) (python/repr v)))
       (is (= "#example/map {:a 42}" (pr-str v))))))

(deftest live-fields-and-reader-overrides
  (let [v (->MovingPoint 1)]
    (move! v)
    (is (= {:x 2} (readers/-type-fields v)))
    (is (= "#eve.type-io-test/MovingPoint {:x 2}" (pr-str v))))
  (is (= {:x 42} (edn/read-string {:readers {'example/custom identity}}
                                "#example/custom {:x 42}")))
  (is (= "#example/custom {:x 42}"
         (pr-str (edn/read-string "#example/custom {:x 42}")))))

#?(:cljs nil :default
   (deftest dynamic-reader-overrides
     (binding [*data-readers* {'example/custom identity}]
       ;; Core reader bindings take precedence over Eve defaults. EDN's
       ;; portable explicit override surface is the :readers option above.
       (is (= {:x 42} (read-string "#example/custom {:x 42}"))))))

(deftest io-contract-is-in-the-existing-type-registry
  (doseq [[type-name mode] [["Point" :field-map] ["SharedPoint" :field-map]
                           ["ProjectedPoint" :field-map] ["Custom" :custom]
                           ["ProtocolPrint" :protocol]]]
    (let [entry (registry/lookup-type (str "eve.type-io-test/" type-name))
          io (:type-io entry)]
      (is (= mode (get-in io [:print :mode])))
      (is (fn? (:read io)))
      (is (= (:read io) (get (registry/data-readers) (:tag io)))))))

(deftest io-contract-is-on-the-type-registry
  (let [entry (registry/lookup-type "eve.type-io-test/Point")
        io (:type-io entry)]
    (is (= 'eve.type-io-test/Point (:tag io)))
    (is (= :field-map (get-in io [:print :mode])))
    (is (fn? (:read io)))
    (is (identical? (:read io) (get (registry/data-readers) (:tag io))))))

(deftest unqualified-tag-reader-overrides
  (is (= "#example.LegacyPoint {:x 7}"
         (pr-str (edn/read-string (pr-str (->LegacyPoint 7))))))
  (is (= {:x 7}
         (edn/read-string {:readers {'example.LegacyPoint identity}}
                          "#example.LegacyPoint {:x 7}"))))
