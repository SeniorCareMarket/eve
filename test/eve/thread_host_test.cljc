(ns eve.thread-host-test
  "The thread feature's conformance suite (HOST-CONTRACT §11): every row run against eve.thread-host on every host,
   reading only the contract and the adapter's declared capabilities ((driving), (ships-closures?)) — no row names a
   host. The threads it starts run echo-loop or die-at-once from this namespace, so an isolated host's thread loads
   it first (:requires)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.process :as proc]
            [eve.thread-host :as h]))

(def ^:dynamic *conf* 1)

(def wait-ms
  "How long a row waits for any one answer before it fails (no row ever waits without a bound)."
  30000)

(def opts {:requires '[eve.thread-host-test] :name "eve-conformance"})

(defn- threads? [] (not= 0 (:max-threads (h/driving))))

(defn- exit-notice? [m] (and (map? m) (contains? m :eve.thread/exit)))

(defn- take-pending
  "The first message in the row's pending list that pred accepts, removed from it; nil when none."
  [pending pred]
  (loop [before [] xs @pending]
    (when (seq xs)
      (if (pred (first xs))
        (do (reset! pending (into before (rest xs))) (first xs))
        (recur (conj before (first xs)) (rest xs))))))

(defn- await-msg
  "The first message pred accepts within ms: from the row's pending list, then from this thread's mailbox — a message
   pred refuses is kept in pending for the row's next wait. :eve.thread/timeout when none comes."
  [pending pred ms]
  (or (take-pending pending pred)
      (let [deadline (+ (proc/now-ms) ms)]
        (loop []
          (let [left (- deadline (proc/now-ms))]
            (if (<= left 0)
              :eve.thread/timeout
              (let [m (h/receive! left)]
                (cond (= m :eve.thread/timeout) m
                      (pred m) m
                      :else (do (swap! pending conj m) (recur))))))))))

(defn- op? [op] (fn [m] (and (map? m) (= op (:op m)))))
(defn- exit-of? [addr] (fn [m] (and (exit-notice? m) (= addr (:eve.thread/exit m)))))

(defn- drain! [pending]
  (loop [] (let [m (h/receive! 0)] (when-not (= m :eve.thread/timeout) (swap! pending conj m) (recur)))))

(defn- stop-and-join!
  "Stop the threads and wait for each one's exit notice; the notices."
  [pending addrs]
  (h/stop-threads! addrs)
  (doall (for [a addrs] (await-msg pending (exit-of? a) wait-ms))))

(defn- start! [n] (h/start-threads! n 'eve.thread-host-test/echo-loop {} opts))

;; ---------------------------------------------------------------------------
;; The threads' entries
;; ---------------------------------------------------------------------------

(defn- answer!
  "One request answered; the thread's watcher after it (the address its started threads' exit notices go to)."
  [me watcher m]
  (if (exit-notice? m)
    (do (when watcher (h/send! watcher {:op :forwarded :notice m})) watcher)
    (case (:op m)
      :echo (do (h/send! (:reply m) {:op :echoed :from me :v (:v m) :n (:n m)}) watcher)
      :call (do (h/send! (:reply m) {:op :called :v ((:f m))}) watcher)
      :frame (do (h/send! (:reply m) {:op :framed :v (h/call-with-frame (:frame m) (:f m))}) watcher)
      :later (do (proc/sleep-ms! (:ms m)) (h/send! (:to m) {:op :late :v (:v m)}) watcher)
      :spawn (let [[g] (start! 1)]
               (h/send! (:reply m) {:op :spawned :addr g})
               (:reply m))
      :ping (do (h/send! (:to m) {:op :echo :reply me :v (:v m)})
                (h/send! (:reply m) {:op :pinged :r (h/receive! wait-ms)})
                watcher)
      :die (throw (ex-info (:msg m) {:who :conformance}))
      watcher)))

(defn echo-loop
  "A conformance thread: answers each request until :eve.thread/stop. The exit notices of the threads it starts reach
   it (it is their starter); it forwards them to whoever asked it to start them."
  [boot]
  (let [me (:self boot)]
    (loop [watcher nil]
      (let [m (h/receive! -1)]
        (when-not (= m :eve.thread/stop)
          (recur (answer! me watcher m)))))))

(defn die-at-once
  "A conformance thread whose entry throws at once."
  [_boot]
  (throw (ex-info "die" {:who :conformance})))

;; ---------------------------------------------------------------------------
;; The rows
;; ---------------------------------------------------------------------------

(deftest the-contract-answers
  (let [d (h/driving)]
    (is (pos? (h/parallelism)) "parallelism: hardware threads")
    (is (contains? #{:thread :cheap :never} (:block d)))
    (is (boolean? (:parallel d)))
    (is (= (h/ships-closures?) (:closures d)) "ships-closures? is the declared :closures")
    (is (boolean? (:eval d)))
    (is (contains? #{:futex :poll} (:wake d)))
    (is (number? (:spawn-ms d)))
    (is (or (nil? (:max-threads d)) (integer? (:max-threads d))))
    (is (keyword? (:runtime d)))
    (is (= (h/self) (h/self)) "self is a value, = to itself")
    (is (= 'eve.thread-host-test/*conf* (h/var-symbol #'*conf*)) "var-symbol: a var's qualified name")))

(deftest a-thread-sends-to-itself
  (let [me (h/self)]
    (h/send! me :a)
    (h/send! me {:b 1})
    (h/send! me "c")
    (is (= [:a {:b 1} "c"] [(h/receive! 0) (h/receive! 0) (h/receive! 0)]) "in order, without blocking")
    (is (= :eve.thread/timeout (h/receive! 0)))))

(deftest receive-times-out
  (testing "0 polls"
    (let [t0 (proc/now-ms)]
      (is (= :eve.thread/timeout (h/receive! 0)))
      (is (< (- (proc/now-ms) t0) 50))))
  (testing "n waits about n ms"
    (let [t0 (proc/now-ms)
          r (h/receive! 80)
          dt (- (proc/now-ms) t0)]
      (is (= :eve.thread/timeout r))
      (is (<= 70 dt 5000) (str "waited " dt " ms"))))
  (testing "negative waits for ever: until a message comes"
    (if (threads?)
      (let [pending (atom [])
            me (h/self)
            [a] (start! 1)]
        (drain! pending)
        (h/send! a {:op :later :to me :ms 60 :v :late})
        (let [t0 (proc/now-ms)
              m (h/receive! -1)]
          (is (= {:op :late :v :late} m))
          (is (<= 50 (- (proc/now-ms) t0))))
        (stop-and-join! pending [a]))
      (let [[ok? e] (h/attempt (fn [] (h/receive! -1)))]
        (is (false? ok?) "with no other thread, a wait for ever is refused, never a hang")
        (is (= :deadlock (:eve.thread/refused (:data (h/error-data e)))))))))

(deftest start-n-threads
  (if (threads?)
    (let [pending (atom [])
          me (h/self)
          addrs (start! 3)]
      (is (= [] (h/start-threads! 0 'eve.thread-host-test/echo-loop {} opts)))
      (is (= 3 (count addrs)))
      (is (= 3 (count (distinct addrs))) "distinct addresses")
      (is (not-any? #(= me %) addrs))
      (doseq [[i a] (map-indexed vector addrs)] (h/send! a {:op :echo :reply me :v i}))
      (let [rs (doall (for [_ addrs] (await-msg pending (op? :echoed) wait-ms)))]
        (is (= (set addrs) (set (map :from rs))) "each answers as the address start-threads! gave")
        (is (= [0 1 2] (sort (set (map :v rs))))))
      (stop-and-join! pending addrs))
    (let [[ok? e] (h/attempt (fn [] (start! 1)))]
      (is (false? ok?) "a host without threads refuses, loudly")
      (is (= :no-threads (:eve.thread/refused (:data (h/error-data e))))))))

(deftest send-and-receive
  (when (threads?)
    (let [pending (atom [])
          me (h/self)
          [a] (start! 1)
          v {:k :ns/kw :s "a string, ü" :v [1 2.5 nil true false] :set #{1 2} :sym 'a/b :neg -42
             :big 4503599627370496 :nested {:a {:b [{:c 1} '(1 2)]}}}]
      (testing "data arrives equal"
        (h/send! a {:op :echo :reply me :v v})
        (is (= v (:v (await-msg pending (op? :echoed) wait-ms)))))
      (testing "one sender's messages arrive in order"
        (doseq [i (range 100)] (h/send! a {:op :echo :reply me :n i}))
        (is (= (range 100) (map :n (doall (for [_ (range 100)] (await-msg pending (op? :echoed) wait-ms)))))))
      (stop-and-join! pending [a]))))

(deftest stop-ends-each-thread
  (when (threads?)
    (let [pending (atom [])
          addrs (start! 3)
          ns (stop-and-join! pending addrs)]
      (is (= (set addrs) (set (map :eve.thread/exit ns))) "each one's end reaches its starter")
      (is (every? #(= :normal (:reason %)) ns)))))

(deftest attempt-and-error-data
  (is (= [true 3] (h/attempt (fn [] (+ 1 2)))))
  (let [[ok? e] (h/attempt (fn [] (throw (ex-info "boom" {:x 7}))))
        d (h/error-data e)]
    (is (false? ok?))
    (is (= "boom" (:message d)))
    (is (= {:x 7} (:data d)))
    (is (and (string? (:class d)) (pos? (count (:class d)))) "the host's class, named")))

(deftest binding-frames
  (let [frame (binding [*conf* 10] (h/capture-frame))]
    (if (h/ships-closures?)
      (do
        (is (= 10 (h/call-with-frame frame (fn [] *conf*))) "re-established on this thread")
        (is (= 1 *conf*) "and gone after")
        (when (threads?)
          (let [pending (atom [])
                me (h/self)
                [a] (start! 1)]
            (h/send! a {:op :frame :reply me :frame frame :f (fn [] *conf*)})
            (is (= 10 (:v (await-msg pending (op? :framed) wait-ms))) "re-established on another thread")
            (h/send! a {:op :call :reply me :f (fn [] *conf*)})
            (is (= 1 (:v (await-msg pending (op? :called) wait-ms))) "a frame travels only when sent")
            (stop-and-join! pending [a]))))
      (do
        (is (nil? frame) "no frame on a host whose threads do not share a heap")
        (is (= 1 (h/call-with-frame frame (fn [] *conf*))))))))

(deftest closures-in-messages
  (when (threads?)
    (let [pending (atom [])
          me (h/self)
          [a] (start! 1)
          k 3]
      (if (h/ships-closures?)
        (do (h/send! a {:op :call :reply me :f (fn [] (* k 7))})
            (is (= 21 (:v (await-msg pending (op? :called) wait-ms))) "a closure runs there, its locals with it"))
        (let [[ok? e] (h/attempt (fn [] (h/send! a {:op :call :reply me :f (fn [] (* k 7))})))]
          (is (false? ok?) "a host object never crosses a heap: refused at the sender")
          (is (= :host-object (:eve.thread/refused (:data (h/error-data e)))))))
      (stop-and-join! pending [a]))))

(deftest eval-a-form
  (if (:eval (h/driving))
    (do (is (= 3 (h/eval-form '(+ 1 2))))
        (is (= "ab" (h/eval-form '(str "a" "b")))))
    (let [[ok? e] (h/attempt (fn [] (h/eval-form '(+ 1 2))))]
      (is (false? ok?) "where a host cannot eval, a form is refused, loudly")
      (is (= :eval (:eve.thread/refused (:data (h/error-data e))))))))

(deftest a-dead-thread-is-never-silent
  (when (threads?)
    (let [pending (atom [])
          me (h/self)]
      (testing "an entry that throws at once"
        (let [[d] (h/start-threads! 1 'eve.thread-host-test/die-at-once {} opts)
              n (await-msg pending (exit-of? d) wait-ms)]
          (is (= "die" (:message (:reason n))))
          (is (= {:who :conformance} (:data (:reason n))))
          (is (= [true nil] (h/attempt (fn [] (h/send! d {:op :echo :reply me})))) "a send to it is dropped, not an error")))
      (testing "a running thread that dies"
        (let [[a] (start! 1)]
          (h/send! a {:op :echo :reply me :v 1})
          (is (= 1 (:v (await-msg pending (op? :echoed) wait-ms))))
          (h/send! a {:op :die :msg "told to"})
          (let [n (await-msg pending (exit-of? a) wait-ms)]
            (is (= "told to" (:message (:reason n))))
            (is (string? (:class (:reason n))))))))))

(deftest round-trips-take-under-a-millisecond
  (when (threads?)
    (let [pending (atom [])
          me (h/self)
          [a] (start! 1)
          frame (h/capture-frame)
          batch (fn []
                  (let [t0 (proc/now-ms)]
                    (dotimes [i 100]
                      (h/send! a {:op :echo :reply me :n i :v frame})
                      (await-msg pending (fn [m] (= i (:n m))) wait-ms))
                    (- (proc/now-ms) t0)))
          _ (batch)
          ms (vec (sort (repeatedly 5 batch)))]
      (println "eve.thread-host round trips:" (:runtime (h/driving)) "ms per 100 (5 batches, sorted):" ms)
      (is (<= (nth ms 2) 100)
          (str "100 round trips, each carrying this thread's captured frame, in under 100 ms (median batch of 5): " ms))
      (stop-and-join! pending [a]))))

(deftest a-thread-starts-a-thread
  (when (threads?)
    (let [pending (atom [])
          me (h/self)
          [c] (start! 1)
          _ (h/send! c {:op :spawn :reply me})
          g (:addr (await-msg pending (op? :spawned) wait-ms))]
      (is (some? g))
      (is (not= g c))
      (is (not= g me))
      (testing "the grandchild and this thread reach each other directly"
        (h/send! g {:op :echo :reply me :v :hello})
        (let [r (await-msg pending (op? :echoed) wait-ms)]
          (is (= [:hello g] [(:v r) (:from r)]))))
      (testing "the child and the grandchild reach each other"
        (h/send! c {:op :ping :to g :reply me :v :pong})
        (is (= :pong (-> (await-msg pending (op? :pinged) wait-ms) :r :v))))
      (testing "the grandchild's end reaches its starter, the child"
        (h/stop-threads! [g])
        (let [f (await-msg pending (op? :forwarded) wait-ms)]
          (is (= g (:eve.thread/exit (:notice f))))
          (is (= :normal (:reason (:notice f))))))
      (stop-and-join! pending [c]))))
