;; examples/in_memory.cljc — eve in one process: atoms, a domain with member atoms, eve collections, zero-copy reads,
;; the thread feature. Host-neutral: the same file runs on every host whose classpath is src + its hosts.edn roots.
;;
;;   bb -cp "$(bb classpath bb)" examples/in_memory.cljc            # babashka
;;   bb -cp "$(bb classpath bb-minimal)" examples/in_memory.cljc    # babashka on the minimal + inbuilt set
;;   clojure -M examples/in_memory.cljc                             # the JVM (deps.edn :paths are its roots)
;;   bb glj-eve                                                     # glojure: build glj-eve once (Go), then
;;   GLJ_CLASSPATH="$(bb classpath glj)" target/glj-eve/glj-eve \
;;     -e "(require 'eve.host-completion) (load-file \"examples/in_memory.cljc\")"
;;   (on glojure the core completion loads before any namespace that uses deftype is made: a launcher's job)
;;
;; Prints each step and ends with "in_memory: ok", or throws.
(ns in-memory
  (:require [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.core :as ec]
            [eve.deftype.int-map :as im]
            [eve.sorted :as sorted]
            [eve.thread :as t]
            [eve.thread.fold :as tf]))

(defn check [label expected actual]
  (println label "=>" (pr-str actual))
  (when-not (= expected actual)
    (throw (ex-info (str label ": expected " (pr-str expected)) {:actual actual}))))

;; 1. An atom: a named one is a heap domain of its own, cached by its :id in this process.
(def counter (e/atom ::counter {:n 0}))
(dotimes [_ 10] (swap! counter update :n inc))
(check "counter" {:n 10} @counter)

;; 2. A domain holding member atoms (slots of one domain-state), and an atom's meta-atom beside it.
(def domain (ea/atom-domain {}))
(def users (ea/lookup-or-create-mmap-atom! domain "app/users" {}))
(def jobs (ea/lookup-or-create-mmap-atom! domain "app/jobs" []))
(swap! users assoc "ada" {:role :admin})
(swap! jobs conj :index-docs)
(check "users" {"ada" {:role :admin}} @users)
(check "jobs" [:index-docs] @jobs)
(check "member by name" {"ada" {:role :admin}} @(ea/domain-lookup domain :app/users))
(check "registered name" "app/users" (ea/registered-name users))
(check "meta-atom" {:owner "worker-1"} @(ea/meta-atom users {:owner "worker-1"}))

;; 3. eve collections live in an atom's own domain: build them inside swap!. An eve type answers eve.core's
;;    vocabulary on every host (clojure.core's own too where the host's types carry its interfaces — not babashka's).
(defn assoc-all [m kvs] (reduce (fn [m [k v]] (ec/assoc m k v)) m kvs))

(def colls (e/atom ::colls {}))
(swap! colls (fn [_] {:ints (im/int-map) :by-name (sorted/sorted-map)}))
(swap! colls (fn [v] (-> v
                         (update :ints assoc-all [[3 :c] [1 :a] [-10 :neg]])
                         (update :by-name assoc-all [["carol" 3] ["alice" 1] ["bob" 2]]))))
;; deref materializes a map, a vector, a set, a list and an int-map into the host's own collections
(check "int-map, materialized" {-10 :neg 1 :a 3 :c} (into {} (:ints @colls)))
;; e/$ is a read region: inside it @ answers the stored (lazy, zero-copy) value, held until the region exits; what
;; the region answers leaves as plain data
(check "$: the stored sorted map's keys, in order" ["alice" "bob" "carol"]
       (e/$ (mapv first (ec/seq (:by-name @colls)))))
(check "$: subseq over the stored sorted map" [["bob" 2] ["carol" 3]]
       (e/$ (mapv vec (sorted/subseq (:by-name @colls) >= "b"))))

;; 4. The thread feature: futures, pmap and fold over eve.thread's pools (a host without threads runs them inline).
(check "future" 3 @(t/future (+ 1 2)))
(check "pmap" [2 3 4] (vec (t/pmap inc [1 2 3])))
(check "fold" 499500 (tf/fold + (vec (range 1000))))
(t/shutdown-default-pools!)

(println "in_memory: ok")
