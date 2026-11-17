(ns eve.runner.flat-io-main
  "Standalone Node runner for eve.flat-io's conformance test (adapters/flat-io/test; flat lanes U2a). Its
   compile graph is eve.flat-io and the codec its key-cmp row is held to — no slab. Mirrors
   eve.runner.flat-x1-main's summary reporter and exit code.

   Usage: clojure -M:node-test:flat-io-node -m shadow.cljs.devtools.cli compile flat-io-test
          node target/flat-io-test/main.js            (from the repo root: it reads test/eve/fixtures)"
  (:require [cljs.test :as t]
            [eve.flat-io-conformance-test]))

(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))

(defn main []
  (t/run-tests 'eve.flat-io-conformance-test))
