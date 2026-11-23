(ns ds11-fixture.box
  "Fixture for eve.user-ns-root-test: an eve-deftype in a USER namespace.
   Its class name does not start with eve. — exactly the shape the DS11
   JVM defect misclassified (the field instance is the DataScript port's
   datascript.db/DB root in a persistent Eve atom)."
  (:refer-clojure :exclude [->Box Box])
  (:require [eve.deftype :as edt]))

(defprotocol IBox
  (box-version [this])
  (box-items [this]))

(edt/deftype Box [^:int32 version items]
  IBox
  (box-version [_] version)
  (box-items [_] items))
