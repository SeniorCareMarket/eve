(ns eve.runner.flat-x1-main
  "Standalone Node runner for lane X1's suite only (eve.flat.chunk-node-test).
   The full eve-test build compiles every suite; this runner's compile graph
   is the packed chunk and its codec, so the suite can build and run when
   the box cannot hold the full compile. The chunk is host-heap bytes only,
   so — unlike eve.runner.merkle-phase5-main — no slab or native addon is
   initialized. Mirrors that runner's summary reporter with exit code.

   Usage: clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-x1-test
          node target/flat-x1-test/main.js"
  (:require [cljs.test :as t]
            [eve.flat.chunk-node-test]))

(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))

(defn main []
  (t/run-tests 'eve.flat.chunk-node-test))
