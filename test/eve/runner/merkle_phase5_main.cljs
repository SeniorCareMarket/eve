(ns eve.runner.merkle-phase5-main
  "Standalone runner for the merkle-phase5 acceptance suite only.
   The full eve-test build compiles every suite; this runner's
   compile graph is just the shared-atom stack plus the phase5
   tests, so the suite can build and run when the box cannot hold
   the full compile. Mirrors eve.runner.eve-test-main's flow
   (allocator init, native addon, summary reporter with exit code).
   Usage: node target/merkle-phase5/main.js"
  (:require [cljs.test :as t]
            [eve.deftype-proto.alloc :as eve-alloc]
            [eve.atom :as a]
            [eve.data :as d]
            [eve.mem :as mem]
            [eve.merkle-phase5-test]))

(defmethod t/report [::t/default :summary] [{:keys [test pass fail error]}]
  (println)
  (println (str "Ran " test " tests containing "
                (+ pass fail error) " assertions."))
  (println (str fail " failures, " error " errors."))
  (js/process.exit (if (pos? (+ fail error)) 1 0)))

(defn main []
  (let [path (.resolve (js/require "path") "build/Release/mmap_cas.node")]
    (mem/load-native-addon! (js/require path)))
  (-> (eve-alloc/init! :force true)
      (.then (fn [_]
               (set! d/*worker-id* 1)
               (set! a/*global-atom-instance* (a/atom-domain {}))
               (t/run-tests 'eve.merkle-phase5-test)))
      (.catch (fn [e]
                (println (str "ERROR: " (.-message e)))
                (js/process.exit 1)))))
