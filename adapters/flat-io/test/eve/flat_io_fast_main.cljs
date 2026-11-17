(ns eve.flat-io-fast-main
  "Node entry for lane U2b's eve.flat-io contract test (eve.flat-io-fast-test) over flat-io/js, built without a
   shadow-cljs.edn entry (from the repository root):
     clojure -Sdeps '{:aliases {:u2b-node {:extra-paths [\"adapters/flat-io/js/src\" \"adapters/flat-io/test\"]}}}' \\
       -M:node-test:u2b-node -e \"(require 'shadow.cljs.devtools.api) (do (shadow.cljs.devtools.api/with-runtime
       (shadow.cljs.devtools.api/compile* {:build-id :u2b-flat-io-test :target :node-script
       :main 'eve.flat-io-fast-main/main :output-to \\\"target/u2b/flat-io-test.js\\\"} {})) nil)\"
     node target/u2b/flat-io-test.js"
  (:require [cljs.test :as t]
            [eve.flat-io-fast-test]))

(defmethod t/report [:cljs.test/default :end-run-tests] [m]
  (js/process.exit (if (t/successful? m) 0 1)))

(defn main [& _]
  (t/run-tests 'eve.flat-io-fast-test))
