(ns probe-jank-world
  "Stage-(a) probe for the jank ants seat: the disk-tier STM
   arrangement under jank alone, across two processes.
   probe-config.edn in the cwd: {:mode :create|:reopen :root :dim}.
   :create opens (creating) the world, marks it ready (build! —
   in this probe jank plays driver for its own world), places
   2 ants, behaves each 3 times, and prints the world's digest.
   :reopen opens the same world in a fresh process and prints the
   same digest. The digests must be EQUAL: every value the first
   process committed is read back exactly by the second."
  (:require [eve.host-completion]
            [eve.process :as proc]
            [ants]
            [clojure.edn :as edn]))

(defn digest [w]
  (hash [(ants/snapshot w) @(:registry w) @(:clock w)]))

(defn report [mode w]
  (let [a (ants/audit w (ants/snapshot w))]
    (println mode "tag=" (:tag w)
             "digest=" (digest w)
             "clock=" (:clock a)
             "ants-ok=" (:ants-ok? a)
             "food-ok=" (:food-ok? a)
             "registered-ok=" (:registered-ok? a)
             "ant-count=" (:ant-count a)
             "violations=" (:violations a))))

(defn -main [& _args]
  (let [cfg (edn/read-string (slurp "probe-config.edn"))
        w (ants/open-world (:root cfg) (:dim cfg))]
    (case (:mode cfg)
      :create (do (ants/build! w)
                  (let [ids (ants/place-ants! w 2)]
                    (doseq [id ids]
                      (dotimes [_ 3] (ants/behave-once! w id))))
                  (report "PROBE-CREATE" w))
      :reopen (report "PROBE-REOPEN" w)))
  (proc/exit! 0))
