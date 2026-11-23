(ns eve.rseq
  "An indexed collection's reverse seq (cljs.core's RSeq), written once in
   eve's type language for every host: (->RSeq coll (dec (count coll)) nil)
   walks coll from its last element to its first."
  (:refer-clojure :exclude [RSeq ->RSeq])
  (:require [eve.core :as e]
            [eve.coll :as c]
            [eve.deftype-proto.macros :as eve]
            ;; Load order: host-deftype's expansion ends in a
            ;; top-level eve.type-readers/register! call, which needs
            ;; the registry first in the RUNTIME graph (a macro ns's
            ;; require is macro-side only). Guard:
            ;; eve.deftype-guard-test.
            [eve.type-readers]))

#_{:clj-kondo/ignore [:unresolved-protocol-method]} ; Eve protocol method: the protocol is Eve's own and the implementing type is built by Eve's deftype macros; kondo's cljs analysis does not join the two
(eve/host-deftype RSeq [ci i mta]
                  clojure.lang.IMeta
                  (meta [_] mta)
                  clojure.lang.IObj
                  (withMeta [_ m] (RSeq. ci i m))
                  clojure.lang.Sequential
                  clojure.lang.Counted
                  (count [_] (inc i))
                  clojure.lang.IHashEq
                  (hasheq [coll] (e/hash-ordered-coll coll))
                  clojure.lang.Seqable
                  (seq [coll] coll)
                  clojure.lang.IPersistentCollection
                  (cons [coll o] (clojure.core/cons o (e/seq coll)))
                  (empty [_] ())
                  (equiv [coll other] (c/equiv-sequential coll other))
                  clojure.lang.ISeq
                  (first [_] (e/nth ci i))
                  #_{:clj-kondo/ignore [:unresolved-protocol-method]} ; Eve protocol method: the protocol is Eve's own and the implementing type is built by Eve's deftype macros; kondo's cljs analysis does not join the two
                  (next [_] (when (pos? i) (RSeq. ci (dec i) nil)))
                  (more [_] (if (pos? i) (RSeq. ci (dec i) nil) ()))
                  IPrintWithWriter
                  (-pr-writer [coll w opts] (c/pr-sequential-writer w e/pr-writer "(" " " ")" opts coll))
                  java.lang.Object
                  (toString [coll] (e/pr-str* coll))
                  (equals [coll other] (c/equiv-sequential coll other))
                  (hashCode [coll] (e/hash-ordered-coll coll)))
