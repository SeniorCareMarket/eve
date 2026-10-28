(ns eve.thread-net-test
  "The transport ladder (lane ET-3): a domain placed in the window by configuration only (C1); doorbells and the drivers
   that wait by them (C2); the network rung — a domain cell over a network atom at a broker, and at a GATEWAY, a member
   serving its disk domain's atoms on eve.net's routes (C3). Each handle uses the rung it was opened on: the rung is its
   developer's choice (operator, 2026-10-19 ET). In one process: the broker is eve.net.stand-in, the gateway
   eve.net.gateway's handler, the store eve.net.dev-store, all reached through the platform's :request (the two-process
   network rung, over real HTTP: scripts/thread-ladder.clj). Rows read only the thread contract's declarations
   ((driving)), never a host's name. A handle over an in-process broker runs no threads of its own where threads are isolates (they could not
   reach this heap's broker): :threads false, its waits run the work."
  (:refer-clojure :exclude [swap!])
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap!]]
            [eve.atom :as ea]
            [eve.net.dev-store :as ds]
            [eve.net.gateway :as gw]
            [eve.net.stand-in :as si]
            [eve.process :as proc]
            [eve.thread :as t]
            [eve.thread-host :as h]
            [eve.thread.domain :as dom]
            [eve.thread.meta :as mt]
            [eve.thread.net :as net]
            [eve.thread.rung :as rung]))

;; ---------------------------------------------------------------------------
;; Tasks
;; ---------------------------------------------------------------------------

(t/deftask sq [x] (* x x))

(t/deftask who [x] [x (:rid (:dh dom/*running*)) (dom/via (:dh dom/*running*))])

(t/deftask bell-wait
  [path word seen ms]
  (let [r (rung/open-atom path "app/state" nil)
        t0 (proc/now-ms)
        w (rung/wait-bell! r word seen ms)]
    [w (- (proc/now-ms) t0)]))

(defn- threads? [] (not= 0 (:max-threads (h/driving))))

;; a handle over an in-process broker or gateway runs threads only where they share this heap
(defn- local-threads? [] (and (threads?) (h/ships-closures?)))

(def ttl 1500)

(def opts {:ttl ttl :requires '[eve.thread-net-test]})

(def app-value {:app "eve" :xs [1 2 3] :nested {:k #{:a :b} :n 42}})

(defn- fresh [] (str "et3-" (rand-int 1000000000)))

(defn- until [ms f]
  (let [deadline (+ (proc/now-ms) ms)]
    (loop [] (or (f) (if (> (proc/now-ms) deadline) nil (do (proc/sleep-ms! 10) (recur)))))))

(defn- close-path! [path] (ea/close-atom-domain! (ea/persistent-atom-domain path)))

;; ---------------------------------------------------------------------------
;; C1: the window, by configuration only
;; ---------------------------------------------------------------------------

(deftest a-domain-is-placed-in-the-window-by-configuration-only
  (let [nm (fresh)
        roots {:disk-root (str "/tmp/eve-et3/" nm) :window-root (str "/dev/shm/eve-et3/" nm)}]
    ;; (a ClojureScript process holds one mmap domain at a time: each placement in turn, closed before the next)
    (doseq [placement [:disk :window]]
      (testing (str "placement " placement ": the same program, one key of a map apart")
        (let [config (merge roots {:name "media" :placement placement})
              path (rung/place config)
              r (rung/rendezvous config "app/state" app-value)
              dh (dom/attach! r (assoc opts :rid "r1"))]
          (try
            (let [g (dom/pool dh :g {:threads (if (threads?) 1 0)})]
              (is (str/starts-with? path (if (= :window placement) (:window-root roots) (:disk-root roots))))
              (is (proc/file-exists? (str path ".root")) "the domain's files are where the configuration put them")
              (is (= placement (rung/-kind r) (dom/via dh) (rung/placement-of path)))
              (is (= {:rung placement :domain path :name "app/state"} (rung/-locate r)) "the transport it advertises")
              (is (= 16 @(t/submit g 'eve.thread-net-test/sq [4])))
              (is (= app-value (rung/value r)) "the rendezvous atom is untouched by coordination"))
            (finally (dom/detach! dh)))
          (close-path! path))))
    (is (= "./media" (rung/place {:name "media"})) "unspecified, a domain is placed in the current directory")))

;; ---------------------------------------------------------------------------
;; C2: doorbells, and the drivers that wait by them
;; ---------------------------------------------------------------------------

(deftest doorbells-ring-and-a-futex-wait-wakes
  (let [path (rung/place {:name (fresh) :placement :window :window-root "/dev/shm/eve-et3"})
        r (rung/open-atom path "app/state" app-value)
        w (rung/descriptor-word "d42")
        b0 (rung/bell-value r w)]
    (is (= w (rung/descriptor-word "d42")) "one word for one id, on every host (the registry's FNV-1a)")
    (is (<= 4 w 2044) "a descriptor's word lies in 1-511")
    (is (<= 2048 (rung/pool-word :g) 4092) "a pool's in 512-1023")
    (rung/ring! r [w])
    (is (= (inc b0) (rung/bell-value r w)) "a ring bumps its word")
    (is (= (= :futex (:wake (h/driving))) (rung/futex? r)) "a futex wait exactly where the host declares one")
    (is (= :rung (if (rung/futex? r) (rung/wait-bell! r w b0 1000) :rung))
        "a wait for a word already past seen answers at once")
    (when (and (threads?) (rung/futex? r))
      (testing "a thread blocked on a word wakes when another rings it"
        (let [dh (dom/attach! r (assoc opts :rid "r1"))]
          (try
            (let [g (dom/pool dh :g {:threads 1})
                  seen (rung/bell-value r w)
                  f (t/submit g 'eve.thread-net-test/bell-wait [path w seen 10000])]
              (proc/sleep-ms! 300)
              (rung/ring! r [w])
              (let [[how ms] @f]
                (is (= :rung how))
                (is (< ms 5000) "woken by the ring, not its 10 s bound")))
            (finally (dom/detach! dh))))))
    (close-path! path)))

(deftest the-drivers-collect-the-same-results
  (let [path (rung/place {:name (fresh) :placement :window :window-root "/dev/shm/eve-et3"})
        r (rung/open-atom path "app/state" app-value)
        dh (dom/attach! r (assoc opts :rid "r1"))]
    (try
      (let [g (dom/pool dh :g {:threads (if (threads?) 2 0)})
            want (mapv (fn [x] (* x x)) (range 12))
            {:keys [block]} (h/driving)]
        (is (= (cond (= :never block) :sweep (not (rung/futex? r)) :sweep (= :cheap block) :waiters :else :in-order)
               (dom/choose-driver dh))
            "the driver P§5.7 reads from the declared :block and the rung's wake")
        (doseq [driver [:in-order :sweep :waiters]]
          (testing (str driver)
            (let [ids (dom/submit-all! g (mapv (fn [x] ['eve.thread-net-test/sq [x]]) (range 12)))]
              (if (and (= :waiters driver) (false? (:closures (h/driving))))
                ;; a thread here is an isolate: a waiter per descriptor is refused, not started (the work is collected
                ;; by a sweep)
                (let [[ok? e] (t/attempt (fn [] (dom/collect dh ids :waiters)))]
                  (is (false? ok?))
                  (is (str/includes? (str (:message e)) "a thread here is an isolate") (pr-str e))
                  (is (= want (mapv :value (dom/collect dh ids :sweep)))))
                (let [xs (dom/collect dh ids driver)]
                  (is (= want (mapv :value xs)))
                  (is (every? (fn [x] (= :done (:state x))) xs))))))))
      (finally (dom/detach! dh)))
    (is (= app-value (rung/value r)))
    (close-path! path)))

;; ---------------------------------------------------------------------------
;; C3: the network rung — at a broker, and at a gateway
;; ---------------------------------------------------------------------------

(def ^:private cfg {:endpoint "https://dev.atom.broker" :domain-id "d1" :api-key "k"})

(defn- broker-platform [s]
  {:request (:request s) :sleep proc/sleep-ms! :now-ms proc/now-ms :seed 11})

(deftest a-domain-pool-over-a-network-atom-at-a-broker
  (let [s (si/stand-in {:api-key "k"})
        p (broker-platform s)
        local? (local-threads?)
        r1 (dom/attach! (net/rung cfg "app/state" p) (assoc opts :rid "r1" :ttl 4000 :threads local?))]
    (try
      (let [r2 (dom/attach! (net/rung cfg "app/state" p) (assoc opts :rid "r2" :ttl 4000 :threads local?))]
        (try
          (let [g1 (dom/pool r1 :g {:threads (if local? 1 0)})
                g2 (dom/pool r2 :g {:threads (if local? 1 0)})]
            (is (= :net (dom/via r1) (dom/via r2)))
            (is (= ["r1" "r2"] (sort (keys (:runtimes (dom/registry r1))))) "one registry, in the broker's atom")
            (is (= {:rung :net :endpoint (:endpoint cfg) :domain-id "d1" :atom "app/state"}
                   (dom/read-meta r1 [:pools :g :home]))
                "the pool's home is the network atom (its key is not written there)")
            (is (= [1 4 9] (mapv deref [(t/submit g1 'eve.thread-net-test/sq [1]) (t/submit g2 'eve.thread-net-test/sq [2])
                                        (t/submit g1 'eve.thread-net-test/sq [3])])))
            (is (every? (fn [k] (not (str/includes? k "/"))) (keys (:atoms @(:state s))))
                "an atom id is one path segment (a name's / written %2F)"))
          (finally (dom/detach! r2))))
      (finally (dom/detach! r1)))))

(defn- gateway-of
  "A disk domain's gateway over a dev store, and a platform reaching both through :request."
  [d]
  (let [store-url "http://store.test/blobs/"
        store (ds/dev-store {:url store-url :secret "s" :now-ms proc/now-ms})
        ep "http://gw.test"
        g (gw/gateway d {:endpoint ep :domain-id "d1" :api-key "k"
                         :presign (gw/hmac-presigner {:url store-url :secret "s" :now-ms proc/now-ms})
                         :platform {:request (:request store)}
                         :deny net/hello-over-disk-only})
        p {:request (fn [req] (if (str/starts-with? (:url req) ep) ((:handle g) req) ((:request store) req)))
           :sleep proc/sleep-ms! :now-ms proc/now-ms :seed 13}]
    {:g g :store store :platform p :cfg {:endpoint ep :domain-id "d1" :api-key "k"}}))

(deftest a-gateway-serves-the-disk-atom-on-eve-net's-routes
  (let [path (rung/place {:name (fresh) :placement :window :window-root "/dev/shm/eve-et3"})
        d (ea/persistent-atom-domain path)
        a (ea/lookup-or-create-mmap-atom! d "app/state" app-value)
        counter (ea/lookup-or-create-mmap-atom! d "test/counter" {:n 0})
        seal0 (ea/seal a)
        {:keys [g store platform] gcfg :cfg} (gateway-of d)
        nc (net/rung gcfg "test/counter" platform)]
    (testing "one atom, two rungs: every swap counts, whichever rung made it"
      (dotimes [i 10]
        (swap! counter update :n inc)
        (when (even? i) (rung/-swap! nc (fn [v] (update v :n inc)))))
      (is (= {:n 15} @counter (rung/value nc))))
    (testing "blobs go to the store, signed; the gateway relays small JSON only"
      (is (pos? (count @(:blobs store))))
      (is (every? (fn [[_ _ ok?]] ok?) @(:log store)) "every store request carried a valid signature")
      (is (= 403 (:status ((:request store) {:method "GET" :url "http://store.test/blobs/00?mode=read&exp=9999999999&sig=x"}))))
      (is (= 404 (:status ((:handle g) {:method "GET" :url "http://gw.test/blob/00" :headers {} :body ""})))
          "no blob route"))
    (let [local? (local-threads?)
          rd (dom/attach! a (assoc opts :rid "disk" :ttl 4000))]
      (try
        (let [rn (dom/attach! (net/rung gcfg "app/state" platform) (assoc opts :rid "net" :ttl 4000 :threads local?))]
          (try
            (let [gd (dom/pool rd :g {:threads 0})
                  gn (dom/pool rn :g {:threads (if local? 1 0)})]
              (testing "a disk runtime and a network runtime in one registry, one pool"
                (is (= ["disk" "net"] (sort (keys (:runtimes (dom/registry rd))))))
                (is (= (dom/registry rd) (dom/registry rn)) "the same registry by either rung")
                ;; the disk runtime runs no threads here: the network runtime's thread (or its wait) runs what it submits
                (let [ids (dom/submit-all! gd [['eve.thread-net-test/who [7]]])
                      ;; (a network runtime with no threads of its own runs its pool's work in its waits)
                      [x] (dom/collect (if local? rd rn) ids :in-order)]
                  (is (= [7 "net" :net] (:value x)) (str "the network runtime claimed and ran the disk runtime's work: "
                                                         (pr-str x)))))
              (testing "a network write may not touch the upgrade's hellos"
                (let [[ok? e] (t/attempt (fn [] (dom/swap-meta! rn (fn [v] [(mt/hello v "net" "forged") nil]))))]
                  (is (false? ok?))
                  (is (str/includes? (str (:message e)) "denied")))
                (is (nil? (dom/read-meta rd [:hello "net"])))))
            (finally (dom/detach! rn))))
        (finally (dom/detach! rd))))
    (is (= seal0 (ea/seal a)) "the rendezvous atom's seal: coordination wrote only its meta-atom")
    (is (pos? (:commits (gw/stats g))))
    (close-path! path)))
