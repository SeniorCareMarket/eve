(ns eve.stm-write-test
  (:require #?(:cljs [cljs.test :refer [deftest is]]
               :default [clojure.test :refer [deftest is]])
            [eve.stm :as stm]))

(deftest write-installs-into-an-eve-cell
  ;; make-ref creates an Eve atom, NOT the host's Atom. In particular,
  ;; clojerl's native swap! cannot update it (function_clause).
  (let [r (stm/make-ref 41)
        watched (atom [])]
    (is (= :eve-own-domain (stm/cell-residence r)))
    (stm/add-watch* r :trace
                    (fn [key _ old new] (swap! watched conj [key old new])))
    (is (= :noop (stm/run-in-transaction (fn [] :noop))))
    (is (= 41 (stm/run-in-transaction (fn [] (stm/deref-ref r)))))
    (is (= 42 (stm/run-in-transaction (fn [] (stm/alter* r inc [])))))
    (is (= 42 (stm/deref-ref r)))
    (is (= [[:trace 41 42]] @watched))
    (is (= 100 (stm/run-in-transaction (fn [] (stm/ref-set* r 100)))))
    (is (= 100 (stm/deref-ref r)))
    (is (= 2 (stm/ref-history-count r)))
    (is (= [[:trace 41 42] [:trace 42 100]] @watched))))

(deftest abort-does-not-install-and-releases-the-next-write
  (let [r (stm/make-ref 7)]
    (is (= :aborted
           (try (stm/run-in-transaction
                 (fn [] (stm/ref-set* r 99) (throw (ex-info "abort" {}))))
                :not-aborted
                (catch #?(:clje _ :cljs :default :default Throwable) e :aborted))))
    (is (= 7 (stm/deref-ref r)))
    (is (= 8 (stm/run-in-transaction (fn [] (stm/alter* r inc [])))))
    (is (= 8 (stm/deref-ref r)))))
