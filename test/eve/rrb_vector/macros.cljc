;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns eve.rrb-vector.macros
  (:refer-clojure :exclude [assert])
  (:require [eve.host]))

(def ^:const elide-assertions? true)
(def ^:const elide-debug-printouts? true)

(defmacro assert [& args]
  (if-not elide-assertions?
    `(clojure.core/assert ~@args)))

(defmacro dbg [& args]
  (if-not elide-debug-printouts?
    `(prn ~@args)))

(defmacro dbg- [& args])

;; eve: the tail is an eve array and the root an empty node, both in eve memory
;; (the current domain's), and ->Vector allocates the vector there too
(defmacro gen-vector-method [& params]
  (let [arr (gensym "arr__")]
    `(let [~arr (eve.deftype.array/make-array ~(count params))]
       ~@(map-indexed (fn [i param]
                        `(eve.deftype.array/aset ~arr ~i ~param))
                      params)
       (eve.rrb-vector.rrbt/->Vector
        ~(count params) 5 (eve.rrb-vector.nodes/empty-node) ~arr nil nil))))
