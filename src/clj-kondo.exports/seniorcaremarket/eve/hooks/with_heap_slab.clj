(ns hooks.with-heap-slab
  (:require [clj-kondo.hooks-api :as api]))

;; The JVM test suites each define a local anaphoric macro
;;   (defmacro with-heap-slab [& body]
;;     `(let [~'sio (alloc/make-jvm-heap-slab-ctx {})] ~@body))
;; whose body names the implicit `sio` binding. kondo does not
;; expand the same-file macro at its call sites, so sio reads as
;; unresolved. Model the call as the let it is.
(defn- mentions-sio? [node]
  (boolean (some (fn [n] (= 'sio (try (api/sexpr n)
                                      (catch Exception _ nil))))
                 (tree-seq #(seq (:children %)) :children node))))

(defn with-heap-slab [{:keys [node]}]
  (let [children (rest (:children node))
        body (api/list-node (into [(api/token-node 'do)] children))]
    ;; Bind sio only when the body names it: a call site that uses
    ;; the slab for side effects alone would otherwise read as an
    ;; unused binding at every such site.
    {:node (if (some mentions-sio? children)
             (api/list-node
              [(api/token-node 'let)
               (api/vector-node [(api/token-node 'sio) (api/token-node nil)])
               body])
             body)}))
