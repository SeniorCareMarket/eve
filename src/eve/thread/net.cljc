(ns eve.thread.net
  "The network rung (lane ET-3, C3; rung 3 of P§5.5's ladder): a domain cell whose rendezvous atom — and so its
   meta-atom, the home of eve.thread's pools and registry (eve.thread.domain), and that one's meta-atom, the turns — is a
   network atom (eve.net.atom), at a broker or at a gateway (eve.net.gateway: any member serving its disk domain's atoms
   on eve.net's routes). eve.thread.domain's moves are unchanged: every one is an eve.net swap — a compare-and-set of the
   value's content key at the broker, retried on a conflict — and a read is an eve.net deref.

     (def r (rung {:endpoint \"http://host:port\" :domain-id \"d1\" :api-key k} \"app/state\" platform))
     (def dh (dom/attach! r {:rid \"r1\" :ttl 3000 :requires '[my.tasks]}))     ; joins over the network

   Loading this namespace registers the :net opener (eve.thread.rung/open): an isolate's thread re-opens its runtime's
   network rung from its location by the host's own http (eve.net.platform). A host with no http adapter loads none of
   this. A thread keeps its own eve.net handle (its leases and block cache are its own).

   What a network atom holds is data eve.net's codec writes: maps, vectors, sets, scalars, keywords, symbols, strings
   (a list of scalars only) — so a pool on this rung carries registered tasks by name, not forms (a form's list of
   symbols is not encodable: its submission throws there)."
  (:require [clojure.string :as str]
            [eve.net.atom :as na]
            [eve.net.platform :as platform]
            [eve.net.run :as r]
            [eve.net.wire :as wire]
            [eve.thread-host :as h]
            [eve.thread.rung :as rung]))

(defn atom-id "a registered name as eve.net's atom id: one path segment, each / written %2F (eve.net.gateway's)" [nm]
  (str/replace nm "/" "%2F"))

(defn- ra-of
  "This thread's eve.net handle on the rung's atom."
  [rr]
  (let [me (h/self)]
    (or (get @(:ras rr) me)
        (let [ra (na/remote-atom (assoc (:cfg rr) :atom-id (atom-id (:nm rr))) (:platform rr))]
          (get (swap! (:ras rr) (fn [m] (if (contains? m me) m (assoc m me ra)))) me)))))

(declare rung)

(defrecord NetRung [cfg nm platform ras])

(extend-type NetRung
  rung/IRung
  (-kind [_] :net)
  (-swap! [this f] (na/swap! (ra-of this) (fn [v] (f v))) nil)
  (-read [this f] (f (na/deref (ra-of this))))
  (-meta-rung [this init]
    (rung (:cfg this) (str "eve.meta/" (:nm this)) (:platform this) init))
  (-locate [this]
    (assoc (select-keys (:cfg this) [:endpoint :domain-id :api-key]) :rung :net
           :name (:nm this)))
  (-bell [_] nil))

(defn rung
  "The network rung of the atom registered as nm at the broker (or gateway) cfg names ({:endpoint :domain-id
   :api-key}), over platform (eve.net.platform's map; default the host's): the atom created there when absent, and made
   holding init when it holds nothing."
  ([cfg nm] (rung cfg nm (platform/platform) nil))
  ([cfg nm platform] (rung cfg nm platform nil))
  ([cfg nm platform init]
   (let [rr (->NetRung (select-keys cfg [:endpoint :domain-id :api-key :gak]) nm platform (atom {}))]
     (r/run platform (wire/create (assoc (:cfg rr) :atom-id (atom-id nm))))
     (when (seq init)
       (na/swap! (ra-of rr) (fn [v] (if (empty? v) init v))))
     rr)))

(rung/register-opener! :net (fn [loc] (rung loc (:name loc))))

(defn hello-over-disk-only
  "A gateway's :deny for eve.thread's meta-atoms (eve.net.gateway): a network write may not change an upgrade's hellos
   (eve.thread.meta's :hello) — a hello is a runtime's proof that it reached the domain by its path (P§5.5 step 2), so
   only a write through the disk proves anything. Every other move passes."
  [nm old new]
  (when (and (str/starts-with? nm "eve.meta/") (not= (:hello old) (:hello new)))
    "a hello is written over the disk path only"))
