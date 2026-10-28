(ns eve.thread-ladder-gateway
  "The network rung over real HTTP , the side that serves: a host with the http-server feature (the JVM,
   babashka) runs

     <runner> -m eve.thread-ladder-gateway store   <secret>                          the object store
     <runner> -m eve.thread-ladder-gateway gateway <base> <store-url> <secret>       the gateway member G
     <runner> -m eve.thread-ladder-gateway serve   <base> <store-url> <secret>       only the gateway (rung readings)

   The store keeps blobs behind presigned URLs (eve.net.store: each request's HMAC checked) — an object store's part,
   in a process of its own: the gateway never serves a blob. G opens the disk domain at base (in the
   window: /dev/shm), makes the rendezvous atom app/state, serves the domain's atoms on eve.net's routes
   (eve.net.gateway; eve.thread.net/hello-over-disk-only its :deny), attaches by the disk and drives the steps with R
   (eve.thread-ladder's remote) through harness/state. Each step prints an EDN line {:step ...}."
  (:refer-clojure :exclude [swap!])
  (:require [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.http-server :as hs]
            [eve.net.store :as es]
            [eve.net.gateway :as gw]
            [eve.net.platform :as platform]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-ladder :as l]
            [eve.thread.domain :as dom]
            [eve.thread.net :as net]))

(defn store [secret]
  (let [url-v (volatile! nil)
        st (volatile! nil)
        srv (hs/serve! {:port 0 :threads 16} (fn [req] (es/handle @st req)))
        url (str "http://127.0.0.1:" (:port srv) "/blobs/")]
    (vreset! st (es/store {:url url :secret secret :now-ms proc/now-ms}))
    (vreset! url-v url)
    (l/say {:step :store :url url})
    (loop [] (proc/sleep-ms! 60000) (recur))))

(defn serve
  "Only the gateway, over the domain at base (the rung readings' network rung: eve.thread-rungs netswap)."
  [base store-url secret]
  (let [d (ea/persistent-atom-domain base)
        gv (volatile! nil)
        srv (hs/serve! {:port 0 :threads 16} (fn [req] ((:handle @gv) req)))
        ep (str "http://127.0.0.1:" (:port srv))]
    (vreset! gv (gw/gateway d {:endpoint ep :domain-id l/domain-id :api-key l/api-key
                               :presign (gw/hmac-presigner {:url store-url :secret secret :now-ms proc/now-ms})
                               :platform (platform/platform)}))
    (l/say {:step :gateway :endpoint ep})
    (loop [] (proc/sleep-ms! 60000) (recur))))

(defn gateway [base store-url secret]
  (let [d (ea/persistent-atom-domain base)
        a (ea/lookup-or-create-mmap-atom! d "app/state" l/app-value)
        h (ea/lookup-or-create-mmap-atom! d "harness/state" {})
        counter (ea/lookup-or-create-mmap-atom! d "test/counter" {:n 0})
        seal0 (ea/seal a)
        gv (volatile! nil)
        srv (hs/serve! {:port 0 :threads 16} (fn [req] ((:handle @gv) req)))
        ep (str "http://127.0.0.1:" (:port srv))
        g (gw/gateway d {:endpoint ep :domain-id l/domain-id :api-key l/api-key
                         :presign (gw/hmac-presigner {:url store-url :secret secret :now-ms proc/now-ms})
                         :platform (platform/platform) :deny net/hello-over-disk-only})
        _ (vreset! gv g)
        dh (dom/attach! a {:rid "G" :ttl l/ttl :requires '[eve.thread-ladder]
                           :transports [{:rung :window :domain base} {:rung :net :endpoint ep :domain-id l/domain-id}]})
        pg (dom/pool dh :g {:threads 0})
        _ (dom/pool dh :g2 {:threads 2})
        hv (fn [] (ea/read-pinned h (fn [m] (into {} (map (fn [[k v]] [k (boolean v)])) m))))
        hset! (fn [k v] (swap! h assoc k v))]
    (l/say {:step :gateway :endpoint ep :seal seal0})
    (hset! :g-up true)
    ;; G submits to :g, which only R's threads serve — over the network
    (l/wait-for 600000 "R up" (fn [] (:r-up (hv))))
    (let [w (l/run-who! dh pg 6)]
      (l/say {:step :work :result w :registry (dom/registry dh) :stats (dissoc (gw/stats g) :by-atom)}))
    ;; one counter, two rungs at once: a swap here by the disk every 100 ms for as long as R makes its 20 over the
    ;; network (measured: a disk writer in a tight loop starves a network writer of the same atom — eve.net's 50
    ;; retries run out; an application atom has no turns)
    (hset! :g-counting true)
    (let [t0 (proc/now-ms)
          k (loop [k 0] (if (:r-counted (hv)) k (do (swap! counter update :n inc) (proc/sleep-ms! 100) (recur (inc k)))))]
      (l/say {:step :counted :n k :ms (- (proc/now-ms) t0)}))
    (l/say {:step :count :n (:n @counter)})
    (hset! :g-done-work true)
    (l/wait-for 600000 "R done" (fn [] (:r-done (hv))))
    (dom/detach! dh)
    (l/say {:step :g-done :seal (ea/seal a) :seal-same (= seal0 (ea/seal a)) :value-same (= l/app-value @a)
            :stats (gw/stats g)})
    ((:stop srv))))

(defn -main [& [mode & args]]
  (let [[ok? e] (t/attempt (fn [] (case mode "store" (apply store args) "gateway" (apply gateway args) "serve" (apply serve args))))]
    (when-not ok? (l/say {:step :error :mode mode :error e}))
    (l/exit! (if ok? 0 1))))
