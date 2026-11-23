(ns hooks.with-heap-slab
  (:require [clj-kondo.hooks-api :as api]))

;; The JVM test suites each define a local anaphoric macro
;;   (defmacro with-heap-slab [& body]
;;     `(let [~'sio (alloc/make-jvm-heap-slab-ctx {})] ~@body))
;; whose body names the implicit `sio` binding. kondo does not
;; expand the same-file macro at its call sites, so sio reads as
;; unresolved. Model the call as the let it is.
(defn with-heap-slab [{:keys [node]}]
  (let [children (rest (:children node))]
    {:node (api/list-node
            [(api/token-node 'let)
             (api/vector-node [(api/token-node 'sio) (api/token-node nil)])
             (api/list-node (into [(api/token-node 'do)] children))])}))
