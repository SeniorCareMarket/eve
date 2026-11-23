(ns eve.thread-host
  "eve's thread feature (HOST-CONTRACT §11) for a host with no threads: the current thread only. start-threads!
   refuses ((driving) declares :max-threads 0); a thread sends only to itself, onto its own queue; receive! takes
   from that queue, and with it empty a timed wait sleeps out its time (no other thread could fill it) and a wait for
   ever refuses rather than hang. What it needs of the host is eve.process's clock and sleep, and the host's own
   catch-all and binding frame, spelled here."
  (:require [eve.process :as proc]))

(def ^:private queue (atom []))

(defn parallelism [] 1)

(defn ships-closures?
  "One heap and nowhere else to send: a closure stays a closure. Where the host cannot capture a binding frame
   (ClojureScript), declared false, as the Node adapter's is."
  []
  #?(:cljs false :cljd false :default true))

(defn driving []
  {:block :thread :parallel false :closures (ships-closures?) :eval false :wake :poll
   :spawn-ms 0 :max-threads 0 :runtime :none})

(defn self [] :eve.thread/main)

(defn error-data [e] {:class (str #?(:cljd (.-runtimeType e) :default (type e))) :message (ex-message e) :data (ex-data e)})

(defn send!
  "Enqueue msg for this thread (the only address there is); a message to any other is dropped."
  [addr msg]
  (when (= addr (self)) (swap! queue conj msg))
  nil)

(defn start-threads! [n _entry _boot _opts]
  (if (zero? n)
    []
    (throw (ex-info "eve.thread-host: this host has no threads (thread/none)" {:eve.thread/refused :no-threads}))))

(defn receive!
  "This thread's next message; :eve.thread/timeout after ms milliseconds (0 polls). With the queue empty no message
   can come: ms > 0 sleeps out its time, ms < 0 is refused."
  [ms]
  (if-let [m (first @queue)]
    (do (swap! queue (comp vec rest)) m)
    (cond (zero? ms) :eve.thread/timeout
          (pos? ms) (do (proc/sleep-ms! ms) :eve.thread/timeout)
          :else (throw (ex-info "eve.thread-host: a wait for ever with no other thread would never end (thread/none)"
                                {:eve.thread/refused :deadlock})))))

(defn stop-threads! [_addrs] nil)

(defn attempt [f]
  (try [true (f)]
       (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) e
         [false e])))

(defn capture-frame [] #?(:cljs nil :cljd nil :default (get-thread-bindings)))
(defn call-with-frame [frame f] #?(:cljs (f) :cljd (f) :default (if frame (with-bindings* frame f) (f))))

(defn var-symbol [v]
  #?(:cljs (symbol v) :glj (symbol v) :cljd (symbol (str v)) :jank (symbol (subs (str v) 2))
     :default (let [m (meta v)] (symbol (str (ns-name (:ns m))) (str (:name m))))))

(defn eval-form
  "Refused: thread/none carries no evaluator (a host that evals declares a thread adapter of its own)."
  [_form]
  (throw (ex-info "eve.thread-host: no evaluator (thread/none)" {:eve.thread/refused :eval})))
