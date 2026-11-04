(ns eve.runner.vs-thread-main
  "The eve.thread-host conformance suite on the ValueScript mesh (bb test:vs-thread): compiled by the installed
   vs over hosts.edn's :vs roots, transformed by vs/wasm-threads/transform.py, and run on a mesh host. A VS
   deftest runs as its namespace loads, so requiring the suite runs it; the program ends printing
   `N passed, M failed`. The driving line below is printed after the suite: the mesh arm counts only when it
   shows :parallel true — a run that silently fell back to serial semantics would pass the no-threads rows and
   prove nothing."
  (:require [clojure.test]
            [eve.thread-host]
            [eve.thread-host-test]))

(println "driving:" (pr-str (eve.thread-host/driving)))
