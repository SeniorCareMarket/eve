(ns eve.platform
  "Platform-agnostic utilities for Eve.

   Provides:
   - throw!: throw platform-appropriate errors
   - hash-ordered: hash an ordered collection (vec, list)
   - hash-unordered: hash an unordered collection (map, set)")

;;=============================================================================
;; Error throwing
;;=============================================================================

(defn throw!
  "Throw a platform-agnostic error with the given message."
  [msg]
  (throw (ex-info msg {})))

(defn throw-index-out-of-bounds
  "Throw an index-out-of-bounds error."
  [msg]
  (throw #?(:cljs (js/Error. msg)
            :clj (IndexOutOfBoundsException. ^String msg))))

(defn throw-unsupported
  "Throw an unsupported-operation error."
  []
  (throw #?(:cljs (js/Error. "Unsupported operation")
            :clj (UnsupportedOperationException.))))

;;=============================================================================
;; Portable collection hashing
;;=============================================================================

(defn hash-ordered
  "Hash an ordered collection (vec, list, seq).
   Uses Murmur3 ordered hashing on both CLJ and CLJS."
  [coll]
  #?(:cljs (hash-ordered-coll coll)
     :clj  (clojure.lang.Murmur3/hashOrdered coll)))

(defn hash-unordered
  "Hash an unordered collection (map, set).
   Uses Murmur3 unordered hashing on both CLJ and CLJS."
  [coll]
  #?(:cljs (hash-unordered-coll coll)
     :clj  (clojure.lang.Murmur3/hashUnordered coll)))
