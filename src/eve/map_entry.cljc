(ns eve.map-entry
  "A two-element map entry in Eve memory, written once for every host.
   Require this namespace and call map-entry; unlike a host MapEntry its
   fields survive an Eve atom/codec round trip."
  (:refer-clojure :exclude [MapEntry ->MapEntry map-entry])
  (:require [eve.deftype :as edt]
            [eve.core :as e]
            [eve.encoding :as enc]
            [eve.vec]
            [eve.map]))

(edt/eve-deftype MapEntry [k v]
  clojure.lang.IMapEntry
  (key [_] k)
  (val [_] v)
  clojure.lang.Sequential
  clojure.lang.Counted
  (count [_] 2)
  clojure.lang.Indexed
  (nth [_ i]
    (case i 0 k 1 v (enc/throw-index-out-of-bounds (str "Map entry index " i))))
  (nth [_ i not-found] (case i 0 k 1 v not-found))
  clojure.lang.Seqable
  (seq [_] (seq [k v]))
  clojure.lang.IPersistentCollection
  (cons [_ x] (conj [k v] x))
  (empty [_] [])
  (equiv [_ other] (= [k v] other))
  clojure.lang.IHashEq
  (hasheq [_] (hash [k v]))
  IPrintWithWriter
  (-pr-writer [entry w opts] (e/pr-writer [(e/nth entry 0) (e/nth entry 1)] w opts))
  java.lang.Object
  (equals [_ other] (= [k v] other))
  (hashCode [_] (hash [k v]))
  (toString [_] (pr-str [k v])))

(defn map-entry
  "An immutable Eve map entry with Clojure's key/val, indexed and seq semantics."
  [k v]
  (->MapEntry k v))
