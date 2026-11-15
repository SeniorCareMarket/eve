(ns eve.thread-host
  "eve's thread feature (HOST-CONTRACT §11) for the :teavm seat — the
   TeaVM/WasmGC body: Eve's thread surface over the fork's fibers, the
   coroutine backend's @Async keep-set and the runner's teavmAsync
   offer pump. One carrier thread executes every fiber in the body;
   Thread.start offers the new fiber to the EventQueue and the pump
   starts it. Fibers interleave only at park points, so between two
   parks this leaf runs uninterleaved — its check-then-act sequences
   are atomic with respect to other fibers for exactly that reason,
   and the mailbox needs no lock. Scheduling is COOPERATIVE: a fiber
   that never parks starves every other fiber and the pump itself.

   THE MAILBOX. Each address owns a box in the boxes atom:
   {:queue (atom PersistentQueue) :bell ArrayBlockingQueue(1)}. The
   queue is Eve-side state in an atom — unbounded, FIFO; the bell is
   a pure doorbell: send! offers a token after enqueueing, receive!
   parks in the bell's take/poll. That park is
   ArrayBlockingQueue.waitForChange, a kept @Async root of the
   coroutine backend: it suspends the fiber, and the mutation-side
   notify offers the wake back through the EventQueue — prompt, no
   poll slice. (The seat's LinkedBlockingQueue cannot carry this: in
   this body it is a non-blocking ArrayDeque provision whose take
   throws on empty and whose timed poll returns nil at once.) No
   Object monitor is touched anywhere in this leaf — nothing here
   parks in Object.wait — so the leaf stands on the kept roots
   alone and does not depend on the state of the host's monitor/wait
   machinery.

   NAMED GAPS, none smoothed (mirrored in `driving`). parallelism is
   1: pools sized from it get one carrier's worth of threads, which
   is the truth of this body. :kind :virtual and :platform start the
   same fiber thread — the classlib's Thread/ofVirtual is a link
   shim that starts an ordinary thread, and there is no carrier or
   pinning distinction here to honor. eval-form refuses
   (:eve.thread/refused :eval): the body is a closed AOT world with
   no evaluator; the seat's evaluator lives behind the cohost bodies
   layer, not in this leaf. A blocking host call inside a fiber (a
   synchronous eve.region / eve.journal import) stalls the one
   carrier and therefore every fiber until it returns; no leaf can
   preempt it. Thread identity rides the fiber machinery: the host
   restores the current thread (and with it this leaf's ThreadLocal
   address and Clojure's binding frames, both keyed on it) at every
   resume."
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [clojure.lang PersistentQueue]))

(def ^:private boxes
  "address → {:queue (atom PersistentQueue) :bell ArrayBlockingQueue},
   while the thread runs. Shared scheduler state lives in atoms on
   this seat; the bell is a host wait object, not state."
  (atom {}))
(def ^:private next-id (atom 0))
(def ^:private ^ThreadLocal local-id (ThreadLocal.))
(def ^:private bell-token (Object.))

(defn- box! []
  (let [id (swap! next-id inc)]
    (swap! boxes assoc id {:queue (atom PersistentQueue/EMPTY)
                           :bell (ArrayBlockingQueue. 1)})
    id))

(defn parallelism
  "One carrier executes every fiber in the body: the hardware
   parallelism this runtime offers its threads is 1."
  [] 1)

(defn ships-closures? [] true)

(defn driving
  "The declared driving properties (eve.thread's drivers read them,
   never the host's name): a parked waiter costs a suspended fiber,
   not a thread (:cheap); fibers do not run in parallel (:parallel
   false — one carrier); a domain region's wait on this seat is the
   region adapter's i32 wait/notify imports (:futex); no evaluator
   (:eval false — eval-form refuses)."
  []
  {:block :cheap
   :parallel false :closures true :eval false
   :wake :futex
   :spawn-ms 0 :max-threads nil
   :runtime :teavm})

(defn self
  "The calling thread's address (a thread this adapter did not start
   gets one on first call). The address rides a ThreadLocal keyed by
   the host on the current thread, which the fiber machinery
   restores at every resume; the guard covers a call made before
   any fiber context exists."
  []
  (or (try (.get local-id) (catch Throwable _ nil))
      (let [id (box!)]
        (try (.set local-id id) (catch Throwable _ nil))
        id)))

(defn error-data [e] {:class (.getName (class e)) :message (ex-message e) :data (ex-data e)})

(defn send!
  "Enqueue msg for the thread at addr; never blocks. A thread that
   has ended has no mailbox: the message is dropped."
  [addr msg]
  (when-let [{:keys [queue bell]} (get @boxes addr)]
    (swap! queue conj msg)
    (.offer ^ArrayBlockingQueue bell bell-token))
  nil)

(defn start-threads!
  "Start n threads, each running ((resolve entry) (assoc boot :self
   addr)); their addresses. opts: :kind :platform or :virtual (on
   this seat both start the same fiber thread — see the ns
   docstring), :name. When a thread's entry ends — it returned, or
   threw — its starter receives {:eve.thread/exit addr :reason r}:
   r :normal, or the error as error-data."
  [n entry boot opts]
  (let [f (requiring-resolve entry)
        starter (self)
        tname (or (:name opts) "eve-thread")]
    (vec (for [i (range n)]
           (let [id (box!)
                 r (fn []
                     (.set local-id id)
                     (let [reason (try (f (assoc boot :self id)) :normal
                                       (catch Throwable e (error-data e)))]
                       (swap! boxes dissoc id)
                       (send! starter {:eve.thread/exit id :reason reason})))]
             (doto (Thread. ^Runnable r (str tname "-" i)) (.setDaemon true) (.start))
             id)))))

(defn receive!
  "The calling thread's next message; :eve.thread/timeout after ms
   milliseconds (ms < 0: wait for ever; 0: poll). The block is the
   mailbox bell's park (ArrayBlockingQueue.waitForChange, a kept
   @Async root) — the host's real block on this seat. An interrupt
   of the park is treated as a wake: the queue is re-checked and
   only a message or the deadline ends the wait."
  [ms]
  (let [{:keys [queue bell]} (get @boxes (self))
        ^ArrayBlockingQueue bell bell
        deadline (when (pos? ms) (+ (System/currentTimeMillis) (long ms)))]
    (loop []
      (let [q @queue]
        (if (seq q)
          (do (swap! queue pop) (peek q))
          (cond (zero? ms) :eve.thread/timeout
                (neg? ms) (do (try (.take bell)
                                   (catch InterruptedException _ nil))
                              (recur))
                :else (let [remaining (- deadline (System/currentTimeMillis))]
                        (if (<= remaining 0)
                          :eve.thread/timeout
                          (do (try (.poll bell remaining TimeUnit/MILLISECONDS)
                                   (catch InterruptedException _ nil))
                              (recur))))))))))

(defn stop-threads!
  "Ask each thread to end: the message :eve.thread/stop (a request
   its loop honours, never a kill)."
  [addrs]
  (doseq [a addrs] (send! a :eve.thread/stop)))

(defn attempt [f] (try [true (f)] (catch Throwable e [false e])))

(defn capture-frame [] (get-thread-bindings))
(defn call-with-frame [frame f] (if frame (with-bindings* frame f) (f)))

(defn var-symbol [v] (let [m (meta v)] (symbol (str (ns-name (:ns m))) (str (:name m)))))

(defn eval-form
  "Refused: the body is a closed AOT world with no evaluator. The
   seat's evaluator is the interpreter behind the cohost bodies
   layer, reached as a body, not through this leaf."
  [form]
  (throw (ex-info "eval-form is not on the :teavm seat: the body has no evaluator"
                  {:eve.thread/refused :eval})))
