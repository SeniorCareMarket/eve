(ns smoke-main
  "Shared-atom smoke for the glj seat: write in one process, read back in another."
  (:require [eve.atom :as atom]))

(defn -main [& [mode dir]]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain "smoke" nil)]
    (case mode
      "write" (do (swap! a (fn [_] {:n 0 :v []}))
                  (dotimes [_ 10] (swap! a (fn [m] (-> m (update :n inc) (update :v conj (:n m))))))
                  (println (pr-str {:mode :write :deref @a :seal (atom/seal a)})))
      "read" (println (pr-str {:mode :read :deref @a :seal (atom/seal a)})))))
