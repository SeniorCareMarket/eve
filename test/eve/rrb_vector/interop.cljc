;   Copyright (c) Rich Hickey and contributors. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns eve.rrb-vector.interop
  (:require [eve.rrb-vector.protocols :as protos
             ;; the protocols by alias: a clojerl protocol is no var to refer
             :refer [-slicev -splicev]]
            [eve.rrb-vector.rrbt :refer [-as-rrbt]]
            [eve.deftype :refer [extend-host-vectors]]))

;; eve: the host's own vectors (eve.types/vector-types)
(extend-host-vectors protos/PSliceableVector
  (-slicev [v start end]
    (-slicev (-as-rrbt v) start end)))

(extend-host-vectors protos/PSpliceableVector
  (-splicev [v1 v2]
    (-splicev (-as-rrbt v1) v2)))
