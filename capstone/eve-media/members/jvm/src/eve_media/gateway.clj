(ns eve-media.gateway
  "The gateway process (the JVM): eve-media.serve's gateway and store over the run's disk domain, on the launcher's
   ports (the control atom's :config), until the launcher sets :stop.

     java -cp ... clojure.main -m eve-media.gateway <base>

   A process of its own: no member's death takes the network rung down (both kill tests kill a member), and its
   request threads serve the network members — babashka, the browser — in parallel (each request materializes the
   value it serves: on one event loop they queued behind each other, and the members' leases lapsed). It serves atoms;
   it is no runtime of the job atom and runs no jobs."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.process :as proc]
            [eve-media.domain :as domain]
            [eve-media.serve :as serve]))

(defn -main [base]
  (let [{:keys [control domain]} (domain/open-atoms base)
        cfg (:config @control)
        srv (serve/start-gateway! domain (assoc (select-keys cfg [:domain-id :api-key :store-secret])
                                                :port (:gateway-port cfg)
                                                :origin (str "http://127.0.0.1:" (:media-port cfg))))]
    (swap! control assoc-in [:servers :gateway] {:port (:port srv) :process "gateway" :t (proc/now-ms)})
    (println (pr-str {:step :gateway :port (:port srv)}))
    (loop []
      (if (:stop @control)
        (do ((:stop srv)) (println (pr-str {:step :stopped})) (shutdown-agents) (System/exit 0))
        (do (proc/sleep-ms! 500) (recur))))))
