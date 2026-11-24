(ns eve.opfs.follow
  "Follower atoms (the OPFS disk tier spec, W8): a persistent atom used from a tab that does not own the files.

   The owner tab runs a follower server (serve!, an eve thread eve.opfs.mount starts when its tab owns the files):
   its io worker hands it each follower call (the pool's svc block), and it answers from the tab's own persistent
   atoms. A follower's deref is a cached value while no owner commit was heard since it was read (eve.opfs/commits),
   else a fetch; its swap! runs f locally and sends a compare-and-set (the value it saw, the new value, a request id
   [tab seq]) the owner applies when its atom still holds that value, else answers the current value and the
   follower retries. Values travel as eve.wire bytes. When the follower's tab takes the files over (eve.opfs/owner?)
   the atom is the tab's own persistent atom from then on: the mode flip.

   A request id the owner applied answers the same reply when sent again (a follower's resend after a lost reply).
   The owner keeps those replies in its memory: a resend that outlives the owner's tab is not deduplicated (the
   spec's request id in the jlog record: W8b)."
  (:require
   [eve.atom :as atom]
   [eve.opfs :as opfs]
   [eve.wire :as wire]))

;; ---- the owner's server

(def ^:private S-REQ 0)
(def ^:private S-RESP 1)
(def ^:private S-LEN 2)
(def ^:private S-BYTES 16)

(defonce ^:private open-atoms (atom {}))   ; [base id] -> the atom (its domain opened once per base)
(defonce ^:private domains (atom {}))
(defonce ^:private applied (atom {}))      ; tab -> {seq reply}, the newest KEEP

(def ^:private KEEP 256)

(defn- the-atom [base id init]
  (or (get @open-atoms [base id])
      (do (when-not (contains? @domains base) (swap! domains assoc base (atom/persistent-atom-domain base)))
          (let [a (atom/persistent-atom {:id id :persistent base} init)]
            (swap! open-atoms assoc [base id] a)
            a))))

(defn- remember! [tab seq reply]
  (swap! applied update tab
         (fn [m] (let [m (assoc (or m (sorted-map)) seq reply)]
                   (if (> (count m) KEEP) (dissoc m (first (keys m))) m)))))

(defn- handle [{:keys [op base id init expected new rid]}]
  (case op
    :deref {:ok true :value @(the-atom base id init)}
    :cas (let [[tab seq] rid]
           (or (get-in @applied [tab seq])
               (let [a (the-atom base id init)
                     won (volatile! false)
                     after (swap! a (fn [cur] (if (= cur expected) (do (vreset! won true) new) (do (vreset! won false) cur))))
                     reply {:ok @won :value after}]
                 (remember! tab seq reply)
                 reply)))))

(defn serve!
  "The owner tab's follower server (an eve thread): answer each call its io worker puts in the svc block."
  [_boot]
  (let [^js sab (opfs/svc-block)
        svc (js/Int32Array. sab)
        bytes (js/Uint8Array. sab)
        base (* 4 S-BYTES)]
    (loop [done (js/Atomics.load svc S-RESP)]
      (let [req (js/Atomics.load svc S-REQ)]
        (if (== req done)
          (do (js/Atomics.wait svc S-REQ req 1000) (recur done))
          (let [n (js/Atomics.load svc S-LEN)
                reply (try (handle (wire/decode (.slice bytes base (+ base n))))
                           (catch :default e {:ok false :error (str e)}))
                out (wire/encode reply)]
            (.set bytes out base)
            (js/Atomics.store svc S-LEN (.-length out))
            (js/Atomics.store svc S-RESP req)
            (js/Atomics.notify svc S-RESP)
            (recur req)))))))

;; ---- the follower atom

(defn- call! [m]
  (let [deadline (+ (js/Date.now) 30000)]
    (loop [wait 10]
      (let [[ok? r] (try [true (wire/decode (opfs/call-owner! (wire/encode m)))]
                         (catch :default e
                           (if (and (opfs/no-owner? e) (< (js/Date.now) deadline) (not (opfs/owner?)))
                             [false nil]
                             (throw e))))]
        (cond ok? (if (:error r) (throw (ex-info (str "eve.opfs.follow: the owner refused: " (:error r)) m)) r)
              (opfs/owner?) ::flipped
              :else (do (js/Atomics.wait (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 wait)
                        (recur (min 500 (* 2 wait)))))))))

(deftype FollowerAtom [base id init local cache]
  IDeref
  (-deref [this]
    (if (opfs/owner?)
      @(.local! this)
      (let [c (opfs/commits)
            [c0 v] @cache]
        (if (and (some? c0) (== c c0))
          v
          (let [r (call! {:op :deref :base base :id id :init init})]
            (if (= ::flipped r)
              @(.local! this)
              (do (reset! cache [c (:value r)]) (:value r))))))))
  ISwap
  (-swap! [this f] (.swap-with! this f))
  (-swap! [this f x] (.swap-with! this #(f % x)))
  (-swap! [this f x y] (.swap-with! this #(f % x y)))
  (-swap! [this f x y more] (.swap-with! this #(apply f % x y more)))
  IReset
  (-reset! [this v] (.swap-with! this (constantly v)))
  Object
  (local! [_]
    (or @local (reset! local (the-atom base id init))))
  (swap-with! [this g]
    (loop [wait 1]
      (if (opfs/owner?)
        (swap! (.local! this) g)
        (let [c (opfs/commits)
              v0 @this
              v1 (g v0)
              r (call! {:op :cas :base base :id id :init init :expected v0 :new v1
                        :rid [(opfs/tab-id) (opfs/next-seq!)]})]
          (cond
            (= ::flipped r) (recur wait)
            (:ok r) (do (reset! cache [c (:value r)]) (:value r))
            :else (do (reset! cache [c (:value r)])
                      (js/Atomics.wait (js/Int32Array. (js/SharedArrayBuffer. 4)) 0 0 (rand-int wait))
                      (recur (min 64 (* 2 wait))))))))))

(defn follower-atom
  "The persistent atom {:id id :persistent base} (initially init) from any tab: the tab's own when it owns the
   files, else a follower atom through the owner (a worker's: its calls block)."
  [{:keys [id persistent]} init]
  (FollowerAtom. persistent id init (atom nil) (atom nil)))
