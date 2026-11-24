(ns eve.runner.hang-catcher
  "A test-only hang catcher for the OPFS browser suites: every page (and tab iframe) of the test bundle shares a small
   heartbeat SharedArrayBuffer with the eve workers it starts (eve.thread-host/share!); each worker claims a slot and
   wraps Atomics.wait, Atomics.add and Atomics.notify to record what it is doing (the phase, the word it waits on, the
   expected and seen values, which buffer, the last request id and op). A watchdog on the page dumps every worker that
   has been quiet for over 3 s, with the live value of the word it waits on (a lost wakeup shows as live != expected)
   and the pool's control words. Product code is untouched."
  (:require [eve.thread-host :as h]))

(def ^:private KEY "eve.test/hang")
(def ^:private SLOTS 256)
(def ^:private W 20)
;; slot words
(def ^:private BEATS 0) (def ^:private PHASE 1) (def ^:private IDX 2) (def ^:private EXPECTED 3) (def ^:private SEEN 4)
(def ^:private TMO 5) (def ^:private BUF 6) (def ^:private BUFLEN 7) (def ^:private T 8) (def ^:private RESULT 9)
(def ^:private ADD-IDX 10) (def ^:private ADD-VAL 11) (def ^:private OPW 12) (def ^:private TID 13) (def ^:private WAITS 14)
(def ^:private NOTIFY-IDX 15) (def ^:private TICKS 16) (def ^:private TICK-T 17)
(def ^:private phases {0 :none 1 :waiting 2 :running 3 :added 4 :notified 5 :booted 6 :closed})
(def ^:private bufs {0 :other 1 :pool-ctl 2 :pool-svc 3 :mailbox 4 :pool-slot})
(def ^:private results {0 :ok 1 :not-equal 2 :timed-out 3 :threw})

(defn- now [] (bit-or (mod (js/Date.now) 2147483647) 0))

(defn- pool [] (h/shared-of "eve.opfs"))

(defn- buf-tag [^js b mailbox]
  (let [^js p (pool)]
    (cond
      (and p (identical? b (.-ctl p))) 1
      (and p (identical? b (.-svc p))) 2
      (and mailbox (identical? b mailbox)) 3
      (and p (.-slots p) (some #(identical? b %) (array-seq (.-slots p)))) 4
      :else 0)))

(defn- install-worker! [^js hb]
  (let [hbw (js/Int32Array. hb)
        store (.-store js/Atomics)
        add (.-add js/Atomics)
        load (.-load js/Atomics)
        wait (.-wait js/Atomics)
        notify (.-notify js/Atomics)
        slot (add hbw 0 1)                         ; word 0 of the buffer: the slot counter
        base (* W (inc slot))
        put! (fn [k v] (store hbw (+ base k) v))
        beat! (fn [phase] (put! PHASE phase) (put! T (now)) (add hbw (+ base BEATS) 1))
        mailbox (some-> js/self .-__eve_thread .-sab)
        mine? (fn [^js ta] (identical? (.-buffer ta) hb))]
    (when (< slot SLOTS)
      (put! TID (let [s (h/self)] (if (number? s) s -1)))
      (beat! 5)
      ;; the worker's event loop turns (idle, between tasks): a worker blocked in synchronous code stops ticking
      (js/setInterval (fn [] (put! TICK-T (now)) (add hbw (+ base TICKS) 1)) 250)
      (let [close (.-close js/self)] (set! (.-close js/self) (fn [] (beat! 6) (.call close js/self))))
      (set! (.-wait js/Atomics)
            (fn [^js ta i v t]
              (if (mine? ta)
                (wait ta i v t)
                (let [b (.-buffer ta)]
                  (put! IDX i) (put! EXPECTED v) (put! SEEN (load ta i))
                  (put! TMO (if (or (undefined? t) (== t js/Infinity)) -1 (bit-or t 0)))
                  (put! BUF (buf-tag b mailbox)) (put! BUFLEN (.-byteLength b))
                  (add hbw (+ base WAITS) 1)
                  (beat! 1)
                  (let [r (try (wait ta i v t) (catch :default e (put! RESULT 3) (beat! 2) (throw e)))]
                    (put! RESULT (case r "ok" 0 "not-equal" 1 2))
                    (beat! 2)
                    r)))))
      (set! (.-add js/Atomics)
            (fn [^js ta i v]
              (let [r (add ta i v)]
                (when-let [^js p (pool)]
                  (when (identical? (.-buffer ta) (.-ctl p))
                    (put! ADD-IDX i) (put! ADD-VAL (+ r v)) (put! OPW (aget ta 3)) (beat! 3)))
                r)))
      (set! (.-notify js/Atomics)
            (fn [^js ta i n]
              (when-let [^js p (pool)]
                (when (identical? (.-buffer ta) (.-ctl p)) (put! NOTIFY-IDX i) (beat! 4)))
              (notify ta i n))))))

(defn- dump-slot [^js hbw slot mailbox-view t]
  (let [base (* W (inc slot))
        g #(aget hbw (+ base %))
        tag (get bufs (g BUF))
        ^js p (pool)
        live (case tag
               :pool-ctl (when p (aget (js/Int32Array. (.-ctl p)) (g IDX)))
               :pool-svc (when p (aget (js/Int32Array. (.-svc p)) (g IDX)))
               :mailbox (when mailbox-view (aget mailbox-view (g IDX)))
               nil)]
    {:slot slot :tid (g TID) :phase (get phases (g PHASE)) :quiet-ms (- t (max (g T) (g TICK-T))) :beat-ms (- t (g T))
     :beats (g BEATS) :waits (g WAITS) :ticks (g TICKS)
     :wait {:buf tag :buf-bytes (g BUFLEN) :idx (g IDX) :expected (g EXPECTED) :seen-at-entry (g SEEN) :live live
            :lost-wakeup? (and (= :waiting (get phases (g PHASE))) (some? live) (not= live (g EXPECTED)))
            :timeout-ms (g TMO) :last-result (get results (g RESULT))}
     :last-request {:doorbell-idx (g ADD-IDX) :request-id (g ADD-VAL) :op (g OPW) :last-notify-idx (g NOTIFY-IDX)}}))

(defn- pool-words []
  (when-let [^js p (pool)]
    (let [c (js/Int32Array. (.-ctl p))]
      {:lock (aget c 0) :doorbell (aget c 1) :done (aget c 2) :op (aget c 3) :status (aget c 4)
       :role (aget c 10) :term (aget c 11) :commits (aget c 13)
       :svc (when (.-svc p) (let [s (js/Int32Array. (.-svc p))] {:req (aget s 0) :resp (aget s 1)}))})))

(defn- install-page! []
  (let [hb (js/SharedArrayBuffer. (* 4 W (inc SLOTS)))
        hbw (js/Int32Array. hb)
        tag (or (second (re-find #"[?&]tab=([^&]+)" (.-search js/location))) "page")
        reported (atom #{})
        last-words (atom nil)
        ;; [doorbell since] of an in-flight io request (lock 1, done 0), and whether it was dumped
        in-flight (atom nil)]
    (h/share! KEY hb)
    (js/setInterval
     (fn []
       (let [t (now)
             pw (pool-words)
             _ (when (not= pw @last-words) (reset! last-words pw) (js/console.log (str "POOL-WORDS " tag " " t " " (pr-str pw))))
             n (min SLOTS (aget hbw 0))
             mailbox-view (some-> (:sab @@#'h/state) (js/Int32Array.))
             _ (if (and pw (== 1 (:lock pw)) (zero? (:done pw)))
                 (let [[db since dumped] @in-flight]
                   (cond
                     (not= db (:doorbell pw)) (reset! in-flight [(:doorbell pw) t false])
                     (and (not dumped) (> (- t since) 3000))
                     (do (reset! in-flight [db since true])
                         (js/console.log (str "HANG-DUMP " tag " io-request-unanswered "
                                              (pr-str {:t t :in-flight-ms (- t since) :pool pw
                                                       :workers (mapv #(dump-slot hbw % mailbox-view t) (range n))}))))))
                 (reset! in-flight nil))
             quiet (for [s (range n)
                         :let [base (* W (inc s))
                               ph (aget hbw (+ base PHASE))
                               tmo (aget hbw (+ base TMO))]
                         :when (and (> (- t (max (aget hbw (+ base T)) (aget hbw (+ base TICK-T)))) 3000)
                                    (not= ph 6)
                                    ;; a worker parked on its mailbox forever is idle, not hung, unless the word moved
                                    (not (and (== ph 1) (== 3 (aget hbw (+ base BUF))) mailbox-view
                                              (== (aget mailbox-view (aget hbw (+ base IDX))) (aget hbw (+ base EXPECTED)))))
                                    (or (not= ph 1) (== tmo -1) (> tmo 3000)))]
                     s)
             fresh (remove #(contains? @reported [% (aget hbw (+ (* W (inc %)) BEATS))]) quiet)]
         (when (seq fresh)
           (doseq [s fresh] (swap! reported conj [s (aget hbw (+ (* W (inc s)) BEATS))]))
           (js/console.log (str "HANG-DUMP " tag " "
                                (pr-str {:t t :pool (pool-words)
                                         :stuck (mapv #(dump-slot hbw % mailbox-view t) fresh)}))))))
     1000)))

(defonce installed
  (cond
    ;; a page that is not cross-origin isolated (yet: the service worker's reload) has no SharedArrayBuffer
    (exists? js/window) (if (and (exists? js/SharedArrayBuffer) (.-crossOriginIsolated js/self)) (do (install-page!) true) false)
    (and (exists? js/SharedArrayBuffer) (instance? js/SharedArrayBuffer (h/shared-of KEY))) (do (install-worker! (h/shared-of KEY)) true)
    :else false))
