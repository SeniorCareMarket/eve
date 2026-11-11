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

(deftest core-realized?-on-eve-future
  (let [f (t/future-call (fn [] :done))]
    (is (= :done @f))
    (is (true? (realized? f)))
    (is (= (realized? f) (t/realized? f)))))
