(ns eve.runner.flat-parity-main
  "Standalone Node runner for the trunk-merge parity suite only
   (eve.flat.chunk-parity-test). Mirrors eve.runner.flat-x1-main's
   summary reporter with exit code.

   Usage: clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-parity-test
          node target/flat-parity-test/main.js"
  (:require [cljs.test :as t]
            [eve.flat.chunk-parity-test]))

(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))

(defn main []
  (t/run-tests 'eve.flat.chunk-parity-test))
