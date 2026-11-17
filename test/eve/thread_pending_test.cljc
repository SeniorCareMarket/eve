(ns eve.thread-pending-test
  "clojure.core/realized? must answer for an eve.thread promise and future
   (IPending, or each host's equivalent), and must agree with eve.thread/realized?.
   DataScript users call realized? on the promise transact returns."
  (:require [clojure.test :refer [deftest is]]
            [eve.thread :as t]))

(deftest core-realized?-on-eve-promise
  (let [p (t/promise)]
    (is (false? (realized? p)))
    (is (= (realized? p) (t/realized? p)))
    (is (= p (t/deliver p :ok)))
    (is (true? (realized? p)))
    (is (= (realized? p) (t/realized? p)))
    (is (= :ok @p))))

;; The future's work is a registered task, not a plain closure: eve.thread runs a closure only where the pool's
;; threads share this heap and refuses it where they do not (eve.thread's ns doc; nbb's :io pool is isolates —
;; "a closure cannot cross a heap boundary"). A deftask crosses by name on every host, as thread-test's default-pools.
(t/deftask done [] :done)

(deftest core-realized?-on-eve-future
  (let [f (t/future-call done)]
    (is (= :done @f))
    (is (true? (realized? f)))
    (is (= (realized? f) (t/realized? f)))))
