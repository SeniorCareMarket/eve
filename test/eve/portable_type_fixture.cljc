(ns eve.portable-type-fixture
  (:refer-clojure :exclude [deftype])
  (:require [eve.deftype-proto.macros :refer [host-deftype]]))
(host-deftype Variable [value])
(host-deftype Constant [value])
