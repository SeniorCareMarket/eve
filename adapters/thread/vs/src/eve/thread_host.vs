(ns eve.thread-host
  "eve's thread feature (HOST-CONTRACT §11) on the ValueScript mesh — ADAPTER, new: the VS host's shim in Eve's
   adapter tree, written for this seat. It is not Eve code and not a residence piece.

   A VS program is N wasm instances over one shared memory (vs/wasm-threads): instance 0 runs _start, workers
   enter at vs_worker_loop, and vs.thread/mesh-spawn posts a closure to a worker. An Eve thread here is one
   long-lived mesh task: start-threads! posts a wrapper closure that binds *self*, runs the entry under the
   starter's conveyed binding frame, and — on any end, return or throw — sends its starter the exit notice
   before its mailbox is dropped, so a dead thread is never silent.

   The heap is SHARED (one slab): a message is the value itself, closures included (ships-closures? true), and a
   binding frame is a value that travels (get-thread-bindings / with-bindings* — core's own conveyance, the
   machinery vs.thread/thread-future runs on). A mailbox is a VS atom holding a vector — a value stored into an
   atom is held by the runtime, so a message's custody is the atom's, never a raw pointer's — plus one futex
   word in a reserved raw window: send! conjs, bumps the word and notifies it; receive! checks the queue, then
   wait-i32 on the word it just read, so a send between the check and the wait changes the word and the wait
   answers at once — no missed wakeup. The block is the host's real block (memory.atomic.wait32).

   Off the mesh (a plain run — the bb test:vs seat) there is no second instance: start-threads! refuses
   :no-threads, a forever receive! refuses :deadlock, and self / send-to-self / bounded receive! work serially —
   the thread/none seat's semantics, with real mailboxes.

   A compiled program carries no evaluator (measured: 'eval needs the REPL: this artifact was compiled without
   it'), so :eval is false and eval-form refuses :eval, loudly, as the contract provides.

   The raw memory door is called by its literal mmap/ prefix (prog:mmap/*): spelled through a namespace alias
   the same names compile to trip stubs, and load-i32 / store-i32! ARE the sequential-consistency atomic forms —
   there are no atomic-load-i32 / atomic-store-i32! names."
  (:require [vs.thread :as th]))

;; ---------------------------------------------------------------------------
;; The futex window: one reserved raw run. Word 0 is the address counter;
;; mailbox `addr` owns the word at +8 + 8*addr. Reserved at namespace load —
;; on main, during _start, before any spawn; workers meet the address in the
;; globals image they adopt (the vs.thread pool-state pattern).

(def ^:private window (mmap/reserve! 1048576))

(defn- futex-of [addr] (+ window 8 (* 8 addr)))

(defn- alloc-addr! [] (inc (mmap/add-i32! window 1)))

(defn- mesh?
  "mesh-active? answers a bare i32 where a boxed value belongs (the transform maps it to the raw $vs_mesh_active
   primitive): true in a branch, a trap as a value. Normalized here, once."
  []
  (if (th/mesh-active?) true false))

;; ---------------------------------------------------------------------------
;; The registry: address -> {:queue (atom []) :starter addr}. One top-level
;; atom; on the mesh every instance reads the one main made.

(def ^:private registry (atom {}))

(defn- register! [addr starter]
  (swap! registry assoc addr {:queue (atom []) :starter starter})
  addr)

(defn- mailbox-of [addr] (get @registry addr))

;; ---------------------------------------------------------------------------
;; self: a started thread reads the *self* its wrapper bound; an unstarted
;; one (main, or any context no wrapper ran) registers its own mailbox the
;; first time it asks, memoized per instance — instances share the slab, so
;; the memo is keyed by worker-id: a bare atom would hand every instance
;; main's address.

(def ^:dynamic *self* nil)

(def ^:private selves (atom {}))

(defn self []
  (or *self*
      (let [wid (th/worker-id)]
        (or (get @selves wid)
            (let [addr (alloc-addr!)]
              (register! addr addr)
              (swap! selves assoc wid addr)
              addr)))))

(defn parallelism []
  (if (mesh?) (inc (th/mesh-pool-size)) 1))

(defn ships-closures? [] true)

;; ---------------------------------------------------------------------------
;; send! / receive!: the mailbox atom and its futex word.

(defn send! [addr msg]
  (when-let [mb (mailbox-of addr)]
    (swap! (:queue mb) conj msg)
    (let [f (futex-of addr)]
      (mmap/add-i32! f 1)
      (mmap/notify! f 1)))
  nil)

(defn- pop-queue!
  "The head of queue-atom, or nil. The owner is the only consumer and senders only append, so the head a
   swap! retry sees is the head the committed swap removes."
  [q]
  (let [taken (atom nil)]
    (swap! q (fn [v] (if (seq v) (do (reset! taken (nth v 0)) (subvec v 1)) v)))
    @taken))

(defn- now-ms [] (System/currentTimeMillis))

(defn receive! [ms]
  (let [mb (mailbox-of (self))]
    (if-not (mesh?)
      ;; serial: nothing can arrive but what is already here (only this thread can send, and it is here)
      (or (pop-queue! (:queue mb))
          (cond (zero? ms) :eve.thread/timeout
                (< ms 0) (throw (ex-info "eve.thread-host: receive! forever on a seat with no second thread"
                                         {:eve.thread/refused :deadlock}))
                :else (do (Thread/sleep ms) (or (pop-queue! (:queue mb)) :eve.thread/timeout))))
      (let [q (:queue mb) f (futex-of (self))]
        (cond (zero? ms) (or (pop-queue! q) :eve.thread/timeout)
              :else (let [deadline (when (> ms 0) (+ (now-ms) ms))]
                      (loop []
                        (let [v (mmap/load-i32 f)]
                          (or (pop-queue! q)
                              (if (and deadline (>= (now-ms) deadline))
                                :eve.thread/timeout
                                (do (mmap/wait-i32 f v (if deadline
                                                         (max 0 (- deadline (now-ms)))
                                                         -1))
                                    (recur))))))))))))

;; ---------------------------------------------------------------------------
;; start / stop. A thread is one mesh task running thread-main; the entry is
;; resolved on the thread itself, under the starter's conveyed frame.

(defn- error-data* [e]
  {:class (str (type e)) :message (ex-message e) :data (ex-data e)})

(defn- thread-main [entry boot starter addr frame]
  (with-bindings* frame
    (fn []
      (let [reason (try (binding [*self* addr]
                          ((resolve entry) (assoc boot :self addr))
                          :normal)
                        (catch :default e (error-data* e)))]
        (when (some? starter)
          (send! starter {:eve.thread/exit addr :reason reason}))
        (swap! registry dissoc addr)
        nil))))

(defn start-threads! [n entry boot opts]
  (when-not (mesh?)
    (throw (ex-info "eve.thread-host: this seat has no second thread (the program is not running on the mesh)"
                    {:eve.thread/refused :no-threads})))
  (let [starter (self)
        frame (get-thread-bindings)]
    (mapv (fn [_]
            (let [addr (alloc-addr!)]
              (register! addr starter)
              (let [h (th/mesh-spawn (fn [] (thread-main entry boot starter addr frame)))]
                (when (neg? h)
                  (swap! registry dissoc addr)
                  (throw (ex-info "eve.thread-host: the mesh has no free task slot"
                                  {:eve.thread/refused :no-threads})))
                addr)))
          (range n))))

(defn stop-threads! [addrs]
  (doseq [a addrs] (send! a :eve.thread/stop))
  nil)

;; ---------------------------------------------------------------------------
;; The rest of the seam.

(defn driving []
  (if (mesh?)
    {:block :thread :parallel true :closures true :eval false :wake :futex
     :spawn-ms 0 :max-threads (th/mesh-pool-size) :runtime :vs}
    {:block :thread :parallel false :closures true :eval false :wake :poll
     :spawn-ms 0 :max-threads 0 :runtime :vs}))

(defn attempt [thunk]
  (try [true (thunk)] (catch :default e [false e])))

(defn error-data [e] (error-data* e))

(defn capture-frame [] (get-thread-bindings))

(defn call-with-frame [frame f] (with-bindings* frame f))

(defn var-symbol [v]
  (let [m (meta v)]
    (symbol (str (ns-name (:ns m))) (str (:name m)))))

(defn eval-form [_form]
  (throw (ex-info "eve.thread-host: a compiled program carries no evaluator (vs compile --repl does)"
                  {:eve.thread/refused :eval})))
