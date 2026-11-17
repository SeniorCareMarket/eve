(ns eve.clock
  "Portable Unix wall clock. Not a monotonic clock; suitable for timestamps."
  (:require [eve.process :as process]))

(defn current-time-millis
  "Milliseconds since the Unix epoch, as an integer. Uses Eve's host adapter."
  []
  (process/now-ms))
