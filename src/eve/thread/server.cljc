(ns eve.thread.server
  "A THREAD SERVER, the in-memory kind: an eve runtime attached to a domain cell over the network rung (its rendezvous
   atom at a broker, eve.thread.net/rung), serving a named pool with its threads until stopped. Clients anywhere submit
   registered tasks (eve.thread/deftask) to the pool by name, and whichever server claims one runs it.

     (def s (serve! {:endpoint \"http://127.0.0.1:8700\" :domain-id \"d1\" :api-key k :rid \"t0\" :pool :work
                     :threads 2 :requires '[my.tasks] :port 0}))
     ((:stop s))

   It answers GET /health with {role rid pool threads jobs atom} (jobs: the descriptors its threads have run), CORS
   open, so a page can map it; every other request goes to opts :handler (an app host's static files, say) or is a
   404. It holds no state of its own: what it runs and what it answers are the domain cell's.

   eve.thread.probe/who is always served. The namespaces that define the other tasks (:requires) are loaded by the caller in this heap (a host's main); attach!
   names them to the isolate threads a host may start."
  (:require [clojure.string :as str]
            [eve.http-server :as hs]
            [eve.net.json :as json]
            [eve.thread.probe]
            [eve.thread.domain :as dom]
            [eve.thread.net :as net]))

(def CORS {"access-control-allow-origin" "*" "access-control-allow-methods" "GET, OPTIONS"
           "access-control-allow-headers" "content-type"})

(defn health
  "what /health answers for a server"
  [s]
  {"role" "thread-server" "rid" (:rid s) "pool" (name (:pool s)) "threads" (:threads s) "jobs" (dom/ran (:dh s))
   "atom" (:atom s)})

(defn- path-of [url] (if-let [i (str/index-of url "?")] (subs url 0 i) url))

(defn serve!
  "Attach over the network rung and serve pool :pool with :threads threads (default 2), and GET /health on :port (0:
   any free one) at :host (127.0.0.1). opts :endpoint :domain-id :api-key (the broker's), :atom (the rendezvous atom's
   name, \"eve.thread/cell\"), :rid, :ttl (ms, 4000), :requires, :platform (eve.net.platform's map), :handler (fn
   [req] resp-or-nil, for what is not /health). {:rid :pool :threads :atom :dh :port :stop}."
  [opts]
  (let [cfg (select-keys opts [:endpoint :domain-id :api-key])
        nm (or (:atom opts) "eve.thread/cell")
        rr (if (:platform opts) (net/rung cfg nm (:platform opts)) (net/rung cfg nm))
        dh (dom/attach! rr {:rid (:rid opts) :ttl (or (:ttl opts) 4000)
                            ;; (eve.thread.net registers the :net opener: a host whose threads are isolates loads it in each)
                            :requires (vec (distinct (concat '[eve.thread.net eve.thread.probe] (:requires opts))))
                            :transports [{:rung :net :endpoint (:endpoint opts) :domain-id (:domain-id opts)}]})
        n (or (:threads opts) 2)
        pl (or (:pool opts) :work)
        _ (dom/pool dh pl {:threads n})
        s {:rid (:rid dh) :pool pl :threads n :atom nm :dh dh}
        handler (fn [req]
                  (cond
                    (= "OPTIONS" (:method req)) {:status 204 :headers CORS :body ""}
                    (= "/health" (path-of (:url req)))
                    {:status 200 :headers (assoc CORS "content-type" "application/json") :body (json/write-str (health s))}
                    :else (or (when-let [h (:handler opts)] (h req))
                              {:status 404 :headers CORS :body "not found"})))
        srv (hs/serve! {:port (or (:port opts) 0) :host (or (:host opts) "127.0.0.1") :threads 8} handler)]
    (assoc s :port (:port srv)
           :stop (fn [] ((:stop srv)) (dom/detach! dh)))))
