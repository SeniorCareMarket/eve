(ns eve
  "Legacy entry point — use eve.alpha instead.
   Kept for backward compatibility but does NOT export atom
   (which clashes with the eve.atom namespace)."
  (:refer-clojure :exclude [aget aset hash-map hash-set])
  (:require
   [eve.alpha :as alpha]))

;; Re-export everything except atom (which clashes with eve.atom namespace)
(def init! alpha/init!)
(def atom-domain alpha/atom-domain)
(def hash-map alpha/hash-map)
(def empty-hash-map alpha/empty-hash-map)
(def hash-set alpha/hash-set)
(def empty-hash-set alpha/empty-hash-set)
(def aget alpha/aget)
(def aset! alpha/aset!)
(def get-typed-view alpha/get-typed-view)
