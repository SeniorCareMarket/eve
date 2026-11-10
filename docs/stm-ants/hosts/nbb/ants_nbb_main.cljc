(ns ants-nbb-main
  "The ants demo's nbb ant host: the CLJS seats run eve.process-slab's
   init ritual before any domain is opened (the eve.runner.<host>-main
   pattern), then open the shared world, wait for the ready mark,
   place ants, and run their behave loops round-robin until the
   deadline. Config is read from ants-config.edn in the working
   directory via node's fs (host boundary): {:root :dim :ants
   :seconds}."
  (:require [eve.process-slab :as ps]
            [ants]
            [eve.stm :as stm]
            [clojure.edn :as edn]))

(defn- run []
  (let [fs (js/require "fs")
        cfg (edn/read-string (.readFileSync fs "ants-config.edn" "utf8"))
        w (ants/open-world (:root cfg) (:dim cfg))]
    (ants/await-ready! w)
    (let [ids (ants/place-ants! w (:ants cfg))
          deadline (+ (js/Date.now) (* 1000 (:seconds cfg)))
          calls (atom 0)
          errors (atom [])]
      (println "NBB-ANT-HOST-READY tag=" (:tag w) "ants=" ids)
      (loop []
        (when (< (js/Date.now) deadline)
          (doseq [id ids]
            (try (ants/behave-once! w id)
                 (swap! calls inc)
                 (catch :default t
                   (swap! errors conj (str id " " t)))))
          (ants/sleep! 30)
          (recur)))
      (println "NBB-ANT-HOST-DONE tag=" (:tag w)
               "behave-calls=" @calls
               "stats=" (stm/stats)
               "errors=" @errors)))
  (js/process.exit 0))

(defn -main [& _args]
  (-> (ps/init! :force true)
      (.then (fn [_] (run)))
      (.catch (fn [e] (println "NBB-ANT-HOST ERROR:" e) (js/process.exit 1)))))
