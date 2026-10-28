(ns eve.net.broker-disk
  "eve.net.broker's rows in an eve disk atom, so a local broker survives a restart: (broker/broker {:rows (disk-rows
   domain)}). Apart from eve.net.broker because it needs eve.atom (eve/deftype), and the passive broker does not."
  (:require [eve.atom :as ea]
            [eve.deftype-proto.data :as ed]
            [eve.host :as host]
            [eve.net.broker :as broker]))

(defn disk-rows
  "rows as one map in the eve atom named nm (default \"eve.net.broker/rows\") of domain (an eve disk domain): every
   change one eve swap. The leases stay in memory (a restart outlives every TTL)"
  ([domain] (disk-rows domain "eve.net.broker/rows"))
  ([domain nm]
   (let [a (ea/lookup-or-create-mmap-atom! domain nm {})
         rows (fn [cur] (or (ed/eve->clj cur) {}))
         change! (fn [g]
                   (let [out (host/volatile! nil)]
                     (host/swap! a (fn [cur] (let [[m o] (g (rows cur))] (vreset! out o) m)))
                     @out))
         ls (broker/lease-ops (atom {}) :leases)]
     (reify broker/IAtomRows
       (-row [_ aid] (get (ea/read-pinned a rows) aid))
       (-create! [_ aid initial-key]
         (change! (fn [m] (if-let [row (get m aid)]
                            [m row]
                            (let [row {:root_key initial-key :epoch 0}] [(assoc m aid row) row])))))
       (-cas! [_ aid old-key fields] (change! (fn [m] (broker/row-step m aid old-key fields))))
       (-lease! [_ aid epoch expires-at] ((:lease! ls) aid epoch expires-at))
       (-release! [_ lid] ((:release! ls) lid))
       (-renew! [_ lid now expires-at] ((:renew! ls) lid now expires-at))
       (-lease-floor [_ now] ((:floor ls) now))))))
