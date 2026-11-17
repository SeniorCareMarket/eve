(ns eve.runner.flat-smoke-main
  "Standalone Node runner for the flat-all-hosts smoke
   (eve.flat-smoke-test). Mirrors flat-x1-main's reporter; the slab
   init mirrors eve-test-main's run-suite! (the smoke's atom rows
   need a slab and a global atom domain).

   Usage: clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-smoke-test
          node target/flat-smoke-test/main.js"
  (:require [cljs.test :as t]
            ;; ordered first: several eve namespaces register into
            ;; each other at load (eve.rseq into eve.type-readers,
            ;; eve.vec into eve.flat.sister), and this runner's
            ;; smaller graph initialized them in the wrong order
            ;; (measured import failures in both spots)
            [eve.type-readers]
            [eve.flat.sister]
            [eve.process-slab :as ps]
            [eve.atom :as a]
            [eve.data :as d]
            [eve.flat-smoke-test]))

(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))

(defn main []
  (-> (ps/init! :force true)
      (.then (fn [_]
               (set! d/*worker-id* 1)
               (set! a/*global-atom-instance* (a/atom-domain {}))
               (t/run-tests 'eve.flat-smoke-test)))
      (.catch (fn [e]
                (println (str "ERROR: " (.-message e)))
                (when-let [st (.-stack e)] (println st))
                (js/process.exit 1)))))
