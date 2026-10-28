(ns eve.net.client
  "Unit 4.6 (eve/doc/network-atoms/IMPL-PLAN.md §6.6): an EVE client of the network atom for the mixed run of
   vs/test-hosts/workerd/broker_sim_test.py — the JVM, babashka and Node (nbb) run this one file with their hosts.edn
   roots (their http adapter among them: adapters/http/java, adapters/http/node). It names no HTTP API: the host's platform is eve.net.platform's
   (eve.http + eve.process); the client only counts the requests it makes. One operation per run, as
   vs/test-hosts/progs/net_client.vs: BROKER_URL, API_KEY, DOMAIN, ATOM name the atom; OP is create · swap-value
   (VALUE, EDN) · deref-value (EXPECT, EDN) · incr (N swap!s of (update :n inc)). The answer is one line of JSON with
   `calls` and the broker's `statuses`."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [eve.net.atom :as a]
            [eve.net.json :as json]
            [eve.net.platform :as platform]
            [eve.net.run :as r]
            [eve.net.wire :as wire]))

(def ^:private calls (atom 0))
(def ^:private statuses (atom {}))
(defn- env [k] (let [v #?(:cljs (unchecked-get (.-env js/process) k) :default (System/getenv k))] (when-not (str/blank? v) v)))
(def ^:private broker (str (env "BROKER_URL") "/"))

(def ^:private platform
  (let [p (platform/platform)
        req (:request p)]
    (assoc p :request (fn [q]
                        (swap! calls inc)
                        (let [resp (req q)]
                          (when (str/starts-with? (:url q) broker) (swap! statuses update (str (:status resp)) (fnil inc 0)))
                          resp)))))

(def ^:private cfg {:endpoint (env "BROKER_URL") :domain-id (env "DOMAIN") :atom-id (env "ATOM") :api-key (or (env "API_KEY") "")})

(defn- answer [m] (println (json/write-str (assoc m "calls" @calls "statuses" @statuses "client" "eve"))))

(defn -main [& _]
  (try
    (case (env "OP")
      "create" (answer (r/run platform (wire/create cfg)))
      "swap-value" (let [ra (a/remote-atom cfg platform)
                         v (a/reset! ra (edn/read-string (env "VALUE")))]
                     (a/release! ra)
                     (answer {"status" "ok" "value" (pr-str v)}))
      "deref-value" (let [ra (a/remote-atom cfg platform)]
                      (try (let [v (a/deref ra)]
                             (answer {"equal" (= v (edn/read-string (env "EXPECT"))) "value" (pr-str v)}))
                           (finally (a/release! ra))))
      "incr" (let [ra (a/remote-atom cfg platform)
                   n (parse-long (env "N"))]
               (dotimes [_ n] (a/swap! ra update :n inc))
               (a/release! ra)
               (answer {"done" n "conflicts" (get @statuses "409" 0)}))
      (answer {"error" (str "unknown OP " (env "OP"))}))
    (catch #?(:cljs :default :default Exception) e
      (answer {"threw" (:status (ex-data e)) "message" (ex-message e)}))))
