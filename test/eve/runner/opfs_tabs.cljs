(ns eve.runner.opfs-tabs
  "The OPFS disk tier's ownership (W7) on the test page: tabs are same-origin iframes of the page (index.html?tab=…),
   each mounting the pool over one root, so their io workers contend for its Web Lock as tabs do. Tab A owns the
   root and commits to a domain that it never closes; tab B and tab C follow (their requests are refused); A is
   removed (its document, workers and lock gone: a tab that dies); B takes over in the next term, its worker
   recovers the domain and finds every commit A made; C hears B's term. Followers (W8): B and C swap through A
   before it goes (25 each, racing), C reads the total, and after the takeover C's swaps go to B. Exactly once
   (W8b): a compare-and-set A applied, resent to B after the takeover with the same request id, is answered as
   applied (B read the id from A's journal), not applied again or refused. The relay (W9): C's follower atom then
   swaps through eve's service worker (eve.opfs.relay) to B, where this browser relays a worker's synchronous XHR
   (where it does not, the step says so). The page's half (takeover!) answers a map of
   steps for the result; a tab's half (tab!) runs in the iframe."
  (:require
   [cljs.reader :as reader]
   [eve.atom :as atom]
   [eve.opfs :as opfs]
   [eve.opfs.follow :as follow]
   [eve.opfs.mount :as mount]
   [eve.opfs.relay :as relay]
   [eve.thread-host :as h]
   [eve.wire :as wire]))

;; ---- a tab's workers

(defn tab-commit
  "A worker's entry: open the domain at base, commit :count n times, report, and never close (its tab dies)."
  [{:keys [reply base n]}]
  (let [_d (atom/persistent-atom-domain base)
        a (atom/persistent-atom {:id :eve/main :persistent base} {:count 0})]
    (dotimes [_ n] (swap! a update :count inc))
    (h/send! reply {:op :committed :count (:count @a)})
    (loop [] (when-not (= :eve.thread/stop (h/receive! -1)) (recur)))))

(defn tab-probe
  "A worker's entry: open the domain at base; a follower's open is refused."
  [{:keys [reply base]}]
  (try
    (atom/persistent-atom-domain base)
    (h/send! reply {:op :probed :opened true})
    (catch :default e
      (h/send! reply {:op :probed :opened false :not-owner (opfs/not-owner? e) :error (str e)}))))

(defn tab-join
  "A worker's entry: wait to own the files, open the domain at base (a fresh root: recovered), swap :count up,
   close, report."
  [{:keys [reply base]}]
  (try
    (let [owner (opfs/await-owner! 30000)
          d (atom/persistent-atom-domain base)
          a (atom/persistent-atom {:id :eve/main :persistent base} nil)
          before (:count @a)
          after (:count (swap! a update :count inc))]
      (atom/close-atom-domain! d)
      (h/send! reply {:op :joined :owner owner :term (opfs/term) :before before :after after}))
    (catch :default e
      (h/send! reply {:op :joined :error (str e) :stack (some-> (.-stack e) (subs 0 1500))}))))

(defn tab-fswap
  "A worker's entry: swap :count up n times through a follower atom (the owner tab applies them), report the last."
  [{:keys [reply base n]}]
  (try
    (let [a (follow/follower-atom {:id :eve/main :persistent base} nil)
          end (last (repeatedly n #(swap! a update :count inc)))]
      (h/send! reply {:op :fswapped :count (:count end) :owner (opfs/owner?)}))
    (catch :default e
      (h/send! reply {:op :fswapped :error (str e) :stack (some-> (.-stack e) (subs 0 1500))}))))

(defn tab-fread
  "A worker's entry: read :count through a follower atom."
  [{:keys [reply base]}]
  (try
    (h/send! reply {:op :fread :count (:count @(follow/follower-atom {:id :eve/main :persistent base} nil))})
    (catch :default e
      (h/send! reply {:op :fread :error (str e)}))))

(defn tab-cas
  "A worker's entry: one compare-and-set to the owner, by hand — :count up by 100 with request id [4242 1] — report
   the request and the reply (a resend later sends the same request)."
  [{:keys [reply base]}]
  (let [cur (:value (wire/decode (opfs/call-owner! (wire/encode {:op :deref :base base :id :eve/main}))))
        req {:op :cas :base base :id :eve/main :expected cur :new (update cur :count + 100) :rid [4242 1]}
        r (wire/decode (opfs/call-owner! (wire/encode req)))]
    (h/send! reply {:op :cased :ok (:ok r) :count (:count (:value r)) :req (pr-str req)})))

(defn tab-resend
  "A worker's entry: send a request again (its pr-str), report the reply: an applied request answers won, resent."
  [{:keys [reply req]}]
  (let [r (wire/decode (opfs/call-owner! (wire/encode (reader/read-string req))))]
    (h/send! reply {:op :resent :ok (:ok r) :resent (:resent r) :count (:count (:value r))})))

(defn tab-relay
  "A worker's entry: swap :count up n times through a follower atom over eve's service worker relay, report the last
   (or that this worker's synchronous XHRs do not reach the service worker)."
  [{:keys [reply base n]}]
  (try
    (if-not (relay/relayed?)
      (h/send! reply {:op :relayed :relayed false
                      :ping (try (let [x (js/XMLHttpRequest.)]
                                   (.open x "GET" "/eve-sw/opfs/ping" false)
                                   (.send x)
                                   [(.-status x) (.getAllResponseHeaders x)])
                                 (catch :default e (str e)))
                      :location (str (.-href js/location))})
      (let [_ (follow/use-relay!)
            a (follow/follower-atom {:id :eve/main :persistent base} nil)
            end (last (repeatedly n #(swap! a update :count inc)))]
        (h/send! reply {:op :relayed :relayed true :count (:count end) :owner (opfs/owner?)})))
    (catch :default e
      (h/send! reply {:op :relayed :error (str e) :stack (some-> (.-stack e) (subs 0 1500))}))))

;; ---- a tab (the iframe's page)

(defn- to-parent! [tab m]
  (.postMessage (.-parent js/window) (pr-str (assoc m :tab tab)) (.-origin js/location)))

(defn tab!
  "The iframe's page: mount the pool over root, tell the parent its role, run the parent's commands in workers and
   pass their reports on."
  [tab root]
  (-> (mount/mount! {:root root :slots 64 :max-file-bytes (* 64 1024 1024)})
      (.then
       (fn [^js p]
         (to-parent! tab {:op :ready :role (.-role p) :term (.-term p)
                          :controlled (some? (some-> (.-serviceWorker js/navigator) .-controller))})
         (mount/on-role! p (fn [role term] (to-parent! tab {:op :role :role role :term term})))
         (js/setInterval (fn [] (let [m (h/receive! 0)]
                                  (when (and (map? m) (or (:op m) (:eve.thread/exit m))) (to-parent! tab m))))
                         20)
         (.addEventListener js/window "message"
                            (fn [e]
                              (let [{:keys [cmd] :as m} (reader/read-string (.-data e))
                                    entry (case cmd
                                            :commit 'eve.runner.opfs-tabs/tab-commit
                                            :probe 'eve.runner.opfs-tabs/tab-probe
                                            :join 'eve.runner.opfs-tabs/tab-join
                                            :fswap 'eve.runner.opfs-tabs/tab-fswap
                                            :fread 'eve.runner.opfs-tabs/tab-fread
                                            :cas 'eve.runner.opfs-tabs/tab-cas
                                            :resend 'eve.runner.opfs-tabs/tab-resend
                                            :relay 'eve.runner.opfs-tabs/tab-relay)]
                                (h/start-threads! 1 entry (assoc (dissoc m :cmd) :reply (h/self)) {})))))
       (fn [e] (to-parent! tab {:op :mount-failed :error (str e)})))))

;; ---- the page

(defn controlled!
  "A promise, resolved once eve's service worker controls this page (registered here when the page was isolated by
   its headers: the relay's tabs are frames it then controls)."
  []
  (let [sw (.-serviceWorker js/navigator)]
    (cond
      (nil? sw) (js/Promise.resolve false)
      (.-controller sw) (js/Promise.resolve true)
      :else (-> (.register sw "/eve-sw.js?coi=1" #js {:scope "/"})
                (.then (fn [_] (.-ready sw)))
                (.then (fn [_] (if (.-controller sw)
                                 true
                                 (js/Promise. (fn [resolve _]
                                                (.addEventListener sw "controllerchange" (fn [_] (resolve true))
                                                                   #js {:once true}))))))
                (.catch (fn [_] false))))))

(defn- frame! [tab root]
  (let [f (.createElement js/document "iframe")]
    (set! (.-src f) (str "index.html?tab=" tab "&root=" (js/encodeURIComponent root)))
    (.appendChild (.-body js/document) f)
    f))

(defn- send-tab! [^js f m]
  (.postMessage (.-contentWindow f) (pr-str m) (.-origin js/location)))

(defn takeover!
  "On the page: run the tabs' ownership scenario; a promise of {:ok bool :steps [...]}."
  []
  (let [root (str "eve-opfs-tabs-" (js/Date.now))
        inbox (atom [])
        waiters (atom [])
        steps (atom [])
        check (fn []
                (doseq [[pred resolve :as w] @waiters]
                  (when-let [m (first (filter pred @inbox))]
                    (swap! inbox (fn [xs] (vec (remove #(identical? m %) xs))))
                    (swap! waiters (fn [ws] (vec (remove #(identical? w %) ws))))
                    (resolve m))))
        listener (fn [e] (when (string? (.-data e))
                           (let [m (try (reader/read-string (.-data e)) (catch :default _ nil))]
                             (when (map? m) (swap! inbox conj m) (check)))))
        wait (fn [step tab op ms]
               (js/Promise.
                (fn [resolve]
                  (let [w [#(and (= tab (:tab %)) (= op (:op %))) resolve]]
                    (swap! waiters conj w)
                    (js/setTimeout (fn [] (when (some #(identical? w %) @waiters)
                                            (swap! waiters (fn [ws] (vec (remove #(identical? w %) ws))))
                                            (resolve {:timeout step})))
                                   ms)
                    (check)))))
        step! (fn [k ok? m] (swap! steps conj [k (boolean ok?) (dissoc m :tab)]) (boolean ok?))
        base "/tabs/d"
        frames (atom {})
        cas-req (atom nil)]
    (.addEventListener js/window "message" listener)
    (-> (controlled!)
        (.then (fn [_] (swap! frames assoc "A" (frame! "A" root)) (wait :a-ready "A" :ready 30000)))
        (.then (fn [m] (step! :a-owns (= "owner" (:role m)) m)
                 (send-tab! (@frames "A") {:cmd :commit :base base :n 20})
                 (wait :a-commits "A" :committed 60000)))
        (.then (fn [m] (step! :a-commits (= 20 (:count m)) m)
                 (swap! frames assoc "B" (frame! "B" root))
                 (wait :b-ready "B" :ready 30000)))
        (.then (fn [m] (step! :b-follows (and (= "follower" (:role m)) (= 1 (:term m))) m)
                 (swap! frames assoc "C" (frame! "C" root))
                 (wait :c-ready "C" :ready 30000)))
        (.then (fn [m] (step! :c-follows (and (= "follower" (:role m)) (= 1 (:term m))) m)
                 (send-tab! (@frames "B") {:cmd :probe :base base})
                 (wait :b-probe "B" :probed 30000)))
        (.then (fn [m] (step! :b-refused (and (false? (:opened m)) (:not-owner m)) m)
                 (send-tab! (@frames "B") {:cmd :fswap :base base :n 25})
                 (send-tab! (@frames "C") {:cmd :fswap :base base :n 25})
                 (js/Promise.all #js [(wait :b-fswap "B" :fswapped 60000) (wait :c-fswap "C" :fswapped 60000)])))
        (.then (fn [[b c]] (step! :followers-swap (and (number? (:count b)) (number? (:count c))
                                                       (= 70 (max (:count b) (:count c))) (not (:owner b)))
                                  {:b b :c c})
                 (send-tab! (@frames "C") {:cmd :fread :base base})
                 (wait :c-fread "C" :fread 30000)))
        (.then (fn [m] (step! :follower-reads (= 70 (:count m)) m)
                 (send-tab! (@frames "C") {:cmd :cas :base base})
                 (wait :c-cas "C" :cased 30000)))
        (.then (fn [m] (step! :c-cas (and (:ok m) (= 170 (:count m))) (dissoc m :req))
                 (reset! cas-req (:req m))
                 (send-tab! (@frames "B") {:cmd :join :base base})
                 (.remove (@frames "A"))
                 (js/Promise.all #js [(wait :b-role "B" :role 30000) (wait :c-heard "C" :role 30000)])))
        (.then (fn [[b c]] (step! :b-takes-over (and (= "owner" (:role b)) (= 2 (:term b))) b)
                 (step! :c-hears (and (= "follower" (:role c)) (= 2 (:term c))) c)
                 (wait :b-joins "B" :joined 60000)))
        (.then (fn [m] (step! :b-recovers (and (:owner m) (= 2 (:term m)) (= 170 (:before m)) (= 171 (:after m))) m)
                 (send-tab! (@frames "C") {:cmd :resend :req @cas-req})
                 (wait :c-resend "C" :resent 30000)))
        (.then (fn [m] (step! :resend-applied-once (and (:ok m) (:resent m) (= 171 (:count m))) m)
                 (send-tab! (@frames "C") {:cmd :fswap :base base :n 5})
                 (wait :c-retargets "C" :fswapped 60000)))
        (.then (fn [m] (step! :c-retargets (= 176 (:count m)) m)
                 (send-tab! (@frames "C") {:cmd :relay :base base :n 4})
                 (wait :c-relay "C" :relayed 60000)))
        (.then (fn [m] (step! :c-relays (if (false? (:relayed m)) (not (:error m)) (= 180 (:count m))) m)))
        (.catch (fn [e] (step! :error false {:error (str e)})))
        (.then (fn [_]
                 (.removeEventListener js/window "message" listener)
                 (doseq [f (vals @frames)] (.remove f))
                 {:ok (and (= 14 (count @steps)) (every? second @steps)) :steps @steps})))))
