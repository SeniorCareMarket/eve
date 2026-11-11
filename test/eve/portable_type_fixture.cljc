(ns eve.portable-type-fixture
  (:refer-clojure :exclude [deftype])
  (:require [eve.deftype :refer [deftype]]))
(deftype Variable [value])
(deftype Constant [value])
