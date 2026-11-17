(ns eve.runner.flat-h4-main
  "Standalone Node runner for lane H4's suite only (eve.flat.image-node-test).
   Unlike X1's runner this suite DOES touch the module slab — the image is
   Eve's own blocks — so, like eve.runner.merkle-phase5-main, it initializes
   the process slab first. Slab geometry is the test namespace's
   slab-capacities (class 0 at Z2's test geometry, 16384 bytes = 512
   blocks, so run-wastes-nothing can fill a class exactly; classes 1–5
   sized for the image rows), and the suite's :each fixture re-inits
   the slab per deftest — the CLJS form of Z1/Z2's fresh heap domain
   per test, since this host's heap domain is the one module slab.

   Usage: clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-h4-test
          node target/flat-h4-test/main.js"
  (:require [cljs.test :as t]
            [eve.process-slab :as ps]
            [eve.flat.image-node-test]))

(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))

(defn main []
  (-> (ps/init! :force true
                :capacities eve.flat.image-node-test/slab-capacities)
      (.then (fn [_] (t/run-tests 'eve.flat.image-node-test)))
      (.catch (fn [e]
                (println (str "ERROR: " (.-message e)))
                (js/process.exit 1)))))
