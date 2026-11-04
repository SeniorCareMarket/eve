(ns eve.thread-host
  "eve's thread feature (HOST-CONTRACT §11) over the JVM's java.lang.Thread and java.util.concurrent (the JVM,
   babashka, jolt): a thread is a platform or a virtual thread, its mailbox a LinkedBlockingQueue; threads share one
   heap, so a message carries closures and host values by reference and the binding frame is the host's own
   (get-thread-bindings). A started thread's end, normal or not, is a message to the thread that started it.")

(def ^:private boxes
  "address → that thread's mailbox, while the thread runs."
  (java.util.concurrent.ConcurrentHashMap.))
(def ^:private next-id (java.util.concurrent.atomic.AtomicLong. 0))
(def ^:private local-id (ThreadLocal.))

(defn- box! []
  (let [id (.incrementAndGet ^java.util.concurrent.atomic.AtomicLong next-id)]
    (.put ^java.util.concurrent.ConcurrentHashMap boxes id (java.util.concurrent.LinkedBlockingQueue.))
    id))

(defn- runtime []
  (cond (System/getProperty "babashka.version") :bb
        (System/getProperty "jolt.version") :jolt
        :else :jvm))

(defn- virtual-default? [] (= "true" (System/getProperty "eve.thread.virtual")))

(defn parallelism [] (.availableProcessors (Runtime/getRuntime)))

(defn ships-closures? [] true)

(defn driving
  "The declared driving properties (eve.thread's drivers read them, never the host's name): a blocked waiter costs a
   platform thread, or little on a virtual one (eve.thread.virtual=true makes virtual the default kind); threads run
   in parallel; a domain region's wait is a poll on the JVM's and babashka's regions, a futex on jolt's."
  []
  {:block (if (virtual-default?) :cheap :thread)
   :parallel true :closures true :eval true
   :wake (if (= :jolt (runtime)) :futex :poll)
   :spawn-ms 0 :max-threads nil
   :runtime (runtime)})

(defn self
  "The calling thread's address (a thread this adapter did not start gets one on first call)."
  []
  (or (.get ^ThreadLocal local-id)
      (let [id (box!)] (.set ^ThreadLocal local-id id) id)))

(defn error-data [e] {:class (.getName (class e)) :message (ex-message e) :data (ex-data e)})

(defn send!
  "Enqueue msg for the thread at addr; never blocks. A thread that has ended has no mailbox: the message is dropped."
  [addr msg]
  (when-let [q (.get ^java.util.concurrent.ConcurrentHashMap boxes addr)]
    (.put ^java.util.concurrent.LinkedBlockingQueue q msg))
  nil)

(defn start-threads!
  "Start n threads, each running ((resolve entry) (assoc boot :self addr)); their addresses. opts: :kind :platform or
   :virtual, :name. When a thread's entry ends — it returned, or threw — its starter receives
   {:eve.thread/exit addr :reason r}: r :normal, or the error as error-data."
  [n entry boot opts]
  (let [f (requiring-resolve entry)
        starter (self)
        virtual? (case (:kind opts) :virtual true :platform false (virtual-default?))
        tname (or (:name opts) "eve-thread")]
    (vec (for [i (range n)]
           (let [id (box!)
                 r (fn []
                     (.set ^ThreadLocal local-id id)
                     (let [reason (try (f (assoc boot :self id)) :normal
                                       (catch Throwable e (error-data e)))]
                       (.remove ^java.util.concurrent.ConcurrentHashMap boxes id)
                       (send! starter {:eve.thread/exit id :reason reason})))]
             (if virtual?
               (.start (.name (Thread/ofVirtual) (str tname "-" i)) ^Runnable r)
               (doto (Thread. ^Runnable r (str tname "-" i)) (.setDaemon true) (.start)))
             id)))))

(defn receive!
  "The calling thread's next message; :eve.thread/timeout after ms milliseconds (ms < 0: wait for ever; 0: poll)."
  [ms]
  (let [^java.util.concurrent.LinkedBlockingQueue q (.get ^java.util.concurrent.ConcurrentHashMap boxes (self))]
    (cond (neg? ms) (.take q)
          (zero? ms) (or (.poll q) :eve.thread/timeout)
          :else (or (.poll q (long ms) java.util.concurrent.TimeUnit/MILLISECONDS) :eve.thread/timeout))))

(defn stop-threads!
  "Ask each thread to end: the message :eve.thread/stop (a request its loop honours, never a kill)."
  [addrs]
  (doseq [a addrs] (send! a :eve.thread/stop)))

(defn attempt [f] (try [true (f)] (catch Throwable e [false e])))

(defn capture-frame [] (get-thread-bindings))
(defn call-with-frame [frame f] (if frame (with-bindings* frame f) (f)))

(defn var-symbol [v] (let [m (meta v)] (symbol (str (ns-name (:ns m))) (str (:name m)))))

(defn eval-form [form] (eval form))
