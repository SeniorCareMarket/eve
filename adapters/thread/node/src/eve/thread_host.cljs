(ns eve.thread-host
  "eve's thread feature (HOST-CONTRACT §11) over Node's worker_threads (ClojureScript on Node, nbb).

   A thread is a worker. Heaps are separate, so a message is data (EDN): closures never travel (ships-closures?
   false), and a host object in a message is refused at the sender. Every pair of threads holds one MessagePort (the
   mesh cljs-thread grew; here any thread may start threads, a start taking a lock word so every new thread is wired
   to every thread there is); a thread blocks on its own word of one SharedArrayBuffer doorbell (Atomics.wait, legal
   on Node's main thread too), draining its ports with receiveMessageOnPort.

   How a worker gets the program's code: where this runtime can eval (nbb), the worker runs nbb's CLI on the same
   classpath and loads the :requires; where it cannot (compiled ClojureScript), by the later cljs-thread lineage's
   fat-kernel route — new Worker(kernel-source, {eval: true}), the kernel being the compiled program this process
   runs (process.argv[1]), its source handed down in workerData so a worker's own threads start the same way. The
   program's entry point calls run-thread! first: in a thread this adapter started, it runs that thread's entry.

   A worker's end is never silent: the worker itself sends its starter {:eve.thread/exit addr :reason r} when its
   entry returns or throws, or when it exits (process.exit) — synchronously, so it reaches a starter blocked in
   receive!; the Worker object's 'exit' event (which runs only on the starter's event loop) is the backstop for a
   worker killed from outside."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:worker_threads" :as wt]
            [cljs.reader :as reader]
            [clojure.string :as str]))

;; The doorbell: one Int32Array over a SharedArrayBuffer every thread holds.
(def ^:private HDR 8)
(def ^:private MAX 4096)
(def ^:private NEXT-ID 0)
(def ^:private LOCK 1)
(def ^:private SPAWN-MS 2)
(defn- bell-word [id] (+ HDR id))
(defn- reported-word [id] (+ HDR MAX id))
(defn- ended-word
  "Set once a thread can send nothing more (after its exit notice is sent, or when it is known dead): every thread
   reads it, so a port to an ended thread is dropped instead of polled by every receive! for ever."
  [id]
  (+ HDR MAX MAX id))

(defn- ours?
  "Is this a worker this adapter started?"
  []
  (and (not wt/isMainThread) (some? wt/workerData) (true? (.-eveThread wt/workerData))))

(def ^:private evals?
  (delay (try (= 2 (eval '(+ 1 1))) (catch :default _ false))))

(defn- arg-after [argv flag]
  (some (fn [[a b]] (when (= a flag) b)) (partition 2 1 argv)))

(defn- main-code
  "How this process's workers get its code: {:nbb cli :cp classpath} where it evals, else {:kernel source}."
  []
  (let [argv (vec (js->clj js/process.argv))
        script (.realpathSync fs (nth argv 1))]
    (if @evals?
      {:nbb script :cp (or (arg-after argv "--classpath") (arg-after argv "-cp") ".")}
      (let [src (.readFileSync fs script "utf8")
            src (if (str/starts-with? src "#!") (subs src (inc (str/index-of src "\n"))) src)
            dir (.dirname path script)]
        ;; an eval'd worker has no file: the kernel finds its runtime files from these
        {:kernel (str "var __filename = " (js/JSON.stringify script) ", __dirname = " (js/JSON.stringify dir) ";\n"
                      src)}))))

(def ^:private state
  (atom (if (ours?)
          (let [d wt/workerData]
            {:self (.-self d) :starter (.-starter d) :bell (.-bell d) :local #queue [] :rr 0
             :ports (into {} (map (fn [k] [(js/parseInt k 10) (aget (.-ports d) k)])) (js/Object.keys (.-ports d)))
             :code (if (.-kernel d) {:kernel (.-kernel d)} {:nbb (.-nbb d) :cp (.-cp d)})})
          {:self 0 :starter nil :local #queue [] :rr 0 :ports {}
           :bell (js/Int32Array. (js/SharedArrayBuffer. (* 4 (+ HDR MAX MAX MAX))))})))

(defn- code [] (or (:code @state) (:code (swap! state assoc :code (main-code)))))

(defn parallelism [] (.availableParallelism os))

(defn ships-closures? [] false)

(defn driving
  "The declared driving properties: a blocked waiter costs a worker; workers run in parallel; eve's Node region waits
   on a futex (its N-API addon); a new worker's start is measured (the last one's, ms from new Worker to its entry)."
  []
  {:block :thread :parallel true :closures false :eval @evals? :wake :futex
   :spawn-ms (js/Atomics.load (:bell @state) SPAWN-MS) :max-threads (dec MAX)
   :runtime (if @evals? :nbb :cljs)})

(defn self [] (:self @state))

(defn error-data [e]
  {:class (cond (instance? ExceptionInfo e) "cljs.core/ExceptionInfo"
                (instance? js/Error e) (str "js/" (.-name e))
                :else (str "js/" (if (some? e) (.. e -constructor -name) "null")))
   :message (or (ex-message e) (str e))
   :data (ex-data e)})

(defn- data?
  "May x travel to another heap: EDN that reads back equal?"
  [x]
  (or (nil? x) (boolean? x) (number? x) (string? x) (keyword? x) (symbol? x) (uuid? x) (inst? x)
      (and (coll? x) (not (record? x)) (every? data? (if (map? x) (mapcat identity x) x)))))

(defn- ring! [id]
  (let [b (:bell @state)]
    (js/Atomics.add b (bell-word id) 1)
    (js/Atomics.notify b (bell-word id))))

(defn- install! [peer port] (swap! state assoc-in [:ports peer] port))

(defn- read-message [m]
  (try (reader/read-string m)
       (catch :default _ (throw (ex-info (str "eve.thread-host: unreadable message: " (subs m 0 (min 400 (count m)))) {})))))

(defn- drain-ports!
  "Every message waiting on every port: a port handed over for a new peer is installed, data is queued locally (in
   each port's order)."
  []
  (loop []
    (let [got (reduce (fn [got p]
                        (if-let [r (wt/receiveMessageOnPort p)]
                          (let [m (.-message r)]
                            (if (string? m)
                              (swap! state update :local conj (read-message m))
                              (install! (.-peer m) (.-port m)))
                            true)
                          got))
                      false (vals (:ports @state)))]
      (when got (recur)))))

(defn send!
  "Post msg to the thread at addr (to oneself: onto this thread's own queue, no port); never blocks. A message must be
   data: a host object (a fn, an atom, a record) is refused here."
  [addr msg]
  (let [{:keys [self ports]} @state]
    (cond
      (= addr self) (swap! state update :local conj msg)
      (not (data? msg))
      (throw (ex-info "eve.thread-host: a message to another heap must be data (EDN): a host object is refused"
                      {:eve.thread/refused :host-object}))
      :else
      (when-let [p (or (get ports addr) (do (drain-ports!) (get (:ports @state) addr)))]
        (.postMessage p (pr-str msg))
        (ring! addr))))
  nil)

(defn- prune-ended!
  "Before a wait: drop the ports of threads that have ended — when nothing has rung this thread's bell since seen was
   read (before the poll that found every port empty). An ended thread marks itself after its last send, which rings
   the bell after posting, so its every message was in its port at that poll."
  [seen]
  (let [{:keys [bell ports self]} @state]
    (when (= seen (js/Atomics.load bell (bell-word self)))
      (let [gone (filterv (fn [id] (= 1 (js/Atomics.load bell (ended-word id)))) (keys ports))]
        (when (seq gone) (swap! state update :ports (fn [ps] (apply dissoc ps gone))))))))

(defn receive!
  "The calling thread's next message; :eve.thread/timeout after ms milliseconds (ms < 0: wait for ever; 0: poll). Its
   own queue first; a port handed over for a new peer is installed here, never returned; ports to ended threads are
   dropped before a wait (prune-ended!)."
  [ms]
  (let [deadline (when (pos? ms) (+ (js/Date.now) ms))]
    (loop []
      (let [{:keys [self ports bell local rr]} @state]
        (if-let [m (peek local)]
          (do (swap! state update :local pop) m)
          (let [seen (js/Atomics.load bell (bell-word self))
                ps (vec (vals ports))
                n (count ps)
                hit (loop [i 0]
                      (when (< i n)
                        (let [k (mod (+ rr i) n)]
                          (if-let [r (wt/receiveMessageOnPort (nth ps k))]
                            (do (swap! state assoc :rr (inc k)) (.-message r))
                            (recur (inc i))))))]
            (cond
              (and hit (not (string? hit))) (do (install! (.-peer hit) (.-port hit)) (recur))
              hit (read-message hit)
              (zero? ms) :eve.thread/timeout
              :else (let [left (if deadline (- deadline (js/Date.now)) js/Infinity)]
                      (if (<= left 0)
                        :eve.thread/timeout
                        (do (prune-ended! seen)
                            (js/Atomics.wait bell (bell-word self) seen left)
                            (recur)))))))))))

(defn- lock! []
  (let [b (:bell @state)]
    (loop []
      (when-not (zero? (js/Atomics.compareExchange b LOCK 0 1))
        (js/Atomics.wait b LOCK 1 10)
        (recur)))))

(defn- unlock! []
  (let [b (:bell @state)]
    (js/Atomics.store b LOCK 0)
    (js/Atomics.notify b LOCK)))

(defn- report-exit!
  "This worker's end, to its starter, once."
  [reason]
  (let [{:keys [self starter bell]} @state]
    (when (zero? (js/Atomics.compareExchange bell (reported-word self) 0 1))
      (try (send! starter {:eve.thread/exit self :reason reason})
           (finally (js/Atomics.store bell (ended-word self) 1))))))

(defn- watch! [^js w id]
  ;; the backstop: runs only when this (the starter's) event loop does — a worker killed from outside
  (.on w "exit" (fn [c]
                  (js/Atomics.store (:bell @state) (ended-word id) 1)
                  (when (zero? (js/Atomics.compareExchange (:bell @state) (reported-word id) 0 1))
                    (swap! state update :local conj
                           {:eve.thread/exit id
                            :reason {:class "exit" :message (str "worker exited with code " c) :data nil}})
                    (ring! (self)))))
  (.on w "error" (fn [_] nil)))

(defn start-threads!
  "Start n workers, each running ((resolve entry) (assoc boot :self addr)) once the program's code is loaded (opts
   :requires: the namespaces an nbb worker loads first); their addresses. Each new worker gets a port to every thread
   there is; each existing thread gets its port to each new one through this thread's port to it."
  [n entry boot opts]
  (if (zero? n)
    []
    (do
      (lock!)
      (try
        (drain-ports!)
        (let [{:keys [self ports bell]} @state
              c (code)
              ids (vec (repeatedly n #(inc (js/Atomics.add bell NEXT-ID 1))))
              _ (when (>= (peek ids) MAX) (throw (ex-info "eve.thread-host: thread limit" {:max (dec MAX)})))
              existing (vec (keys ports))
              everyone (into [self] (concat existing ids))
              chans (reduce (fn [m [a b]] (if (contains? m #{a b}) m (assoc m #{a b} (js/MessageChannel.))))
                            {} (for [a everyone b ids :when (not= a b)] [a b]))
              port-of (fn [me other] (let [ch (get chans #{me other})] (if (< me other) (.-port1 ch) (.-port2 ch))))]
          (doseq [i ids] (install! i (port-of self i)))
          (doseq [e existing j ids]
            (let [p (port-of e j)]
              (.postMessage (get ports e) #js {:peer j :port p} #js [p])
              (ring! e)))
          (doseq [i ids]
            (let [mine (into {} (map (fn [j] [j (port-of i j)])) (remove #{i} everyone))
                  jsports (let [o #js {}] (doseq [[j p] mine] (aset o (str j) p)) o)
                  wd #js {:eveThread true :self i :starter self :bell bell :ports jsports
                          :entry (str entry) :boot (pr-str (assoc boot :self i)) :started (js/Date.now)
                          :kernel (:kernel c) :nbb (:nbb c) :cp (:cp c)}
                  transfer (clj->js (vals mine))
                  w (if (:kernel c)
                      (wt/Worker. (:kernel c) #js {:eval true :workerData wd :transferList transfer})
                      (wt/Worker. (:nbb c)
                                  #js {:argv #js ["--classpath" (:cp c) "-e"
                                                  (str "(require 'eve.thread-host "
                                                       (str/join " " (map #(str "'" %) (:requires opts)))
                                                       ") (eve.thread-host/run-thread!)")]
                                       :workerData wd :transferList transfer}))]
              (watch! w i)))
          ids)
        (finally (unlock!))))))

(defn- entry-fn [sym]
  (if @evals?
    (eval (symbol sym))
    (js/goog.getObjectByName (str (munge (namespace (symbol sym))) "." (munge (name (symbol sym)))))))

(defn run-thread!
  "In a worker this adapter started: run its entry, then tell its starter how it ended; true. Elsewhere: false. A
   compiled program's entry point calls it first (its worker runs the whole program)."
  []
  (if-not (ours?)
    false
    (let [d wt/workerData]
      (js/Atomics.store (:bell @state) SPAWN-MS (- (js/Date.now) (.-started d)))
      (.on js/process "exit"
           (fn [c] (report-exit! {:class "exit" :message (str "worker exited with code " c) :data nil})))
      ;; the entry resolved inside the report: an entry that does not resolve is an exit notice too, at once
      (let [reason (try ((entry-fn (.-entry d)) (reader/read-string (.-boot d))) :normal
                        (catch :default e (error-data e)))]
        (report-exit! reason))
      true)))

(defn stop-threads!
  "Ask each thread to end: the message :eve.thread/stop (a request its loop honours, never a kill)."
  [addrs]
  (doseq [a addrs] (send! a :eve.thread/stop)))

(defn attempt [f] (try [true (f)] (catch :default e [false e])))

(defn capture-frame [] nil)
(defn call-with-frame [_frame f] (f))

(defn var-symbol [v] (symbol v))

(defn eval-form
  "Evaluate form (nbb); refused, loudly, where this runtime has no compiler (compiled ClojureScript)."
  [form]
  (if @evals?
    (eval form)
    (throw (ex-info "eve.thread-host: this runtime cannot eval a form (compiled ClojureScript)"
                    {:eve.thread/refused :eval}))))
