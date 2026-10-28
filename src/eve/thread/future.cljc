(ns eve.thread.future
  "The future and the promise as eve types (eve.deftype, written with the JVM's names; each host's types adapter
   realizes them): @ waits by eve.thread's helping wait, deref-within is (deref f ms v), and realized?, future-done?,
   future-cancel and future-cancelled? read and move the descriptor. A future is its descriptor's state — the value
   or the error is written there, out of band (P§5.3 items 5, 6) — never a host delay: clojerl runs every delay's
   body inside one shared gen_server (ET-0's finding, clojerl.Delay.erl:69, :108)."
  (:refer-clojure :exclude [promise deliver realized? future-cancel future-cancelled? future-done? future?])
  (:require [eve.deftype :as edt]
            [eve.thread.cell :as c]
            [eve.thread.descriptor :as d]))

(defprotocol IFuture
  (-entry [f] "the descriptor's entry")
  (-deref-within [f ms timeout-val] "the value, or timeout-val when ms pass first")
  (-cancel! [f] "cancel: true when that changed it")
  (-deliver! [f v] "a promise's delivery: true when this one delivered it"))

(defn outcome
  "A done descriptor's answer: its value, or a throw — cancelled (ex-data :eve.thread/cancelled), or the task's error
   out of band (ex-data the error's {:class :message :data}, :eve.thread/failed true)."
  [dd]
  (cond
    (d/cancelled? dd) (throw (ex-info (str "eve.thread: cancelled: " (:id dd)) {:eve.thread/cancelled (:id dd)}))
    (= :done (:state dd)) (:value dd)
    :else (throw (ex-info (str "eve.thread: task failed: " (:message (:error dd)))
                          (assoc (:error dd) :eve.thread/failed true)))))

(defn- within [e ms v]
  (let [r (c/await! e ms)]
    (if (= r :eve.thread.cell/timeout) v (outcome r))))

(edt/deftype Future [e]
  clojure.lang.IDeref
  (deref [_] (outcome (c/await! e -1)))
  IFuture
  (-entry [_] e)
  (-deref-within [_ ms v] (within e ms v))
  (-cancel! [_] (c/cancel! e))
  (-deliver! [_ v] false))

(edt/deftype Promise [e token]
  clojure.lang.IDeref
  (deref [_] (outcome (c/await! e -1)))
  IFuture
  (-entry [_] e)
  (-deref-within [_ ms v] (within e ms v))
  (-cancel! [_] false)
  (-deliver! [_ v] (some? (c/settle! e token true v))))

(defn future-of [e] (->Future e))

(defn promise
  "A promise: a descriptor no thread runs, held :running until a deliver settles it (its waiters woken)."
  []
  (let [token (c/next-id "p:")
        dd (-> (d/descriptor (c/next-id "") nil {:submitted (c/now)})
               (d/claim :eve.thread/promise token (c/now)))]
    (->Promise (c/entry dd) token)))

(defn deliver
  "Deliver v to promise p once; p when this call delivered it, nil when it was already delivered."
  [p v]
  (when (-deliver! p v) p))

(defn descriptor-of [f] @(-entry f))

(defn deref-within [f ms v] (-deref-within f ms v))

(defn realized? [f] (d/done? (descriptor-of f)))

(defn future-done? [f] (d/done? (descriptor-of f)))

(defn future-cancelled? [f] (d/cancelled? (descriptor-of f)))

(defn future-cancel [f] (-cancel! f))

(defn future? [x] (satisfies? IFuture x))
