;; T1 probe — what actually happens when a ref's cell (with :zip set) is
;; swapped to a plain cell map whose :value is a foreign slab value.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/stm_zip_probe.clj
(ns t1.stm-zip-probe
  (:require [eve.stm :as stm]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]))

(def rb (stm/make-ref 0))
(def rc (stm/make-ref {:donor 1}))
(def foreign (ea/read-pinned (:cell rc) (fn [v] v)))
(println "foreign:" (pr-str foreign))
(println "foreign root off:" (d/-root-header-off foreign)
         "in rc cell domain:"
         (identical? (alloc/sio-root (ea/atom-sio (:cell rc)))
                     (alloc/sio-root (d/-eve-sio foreign))))

(println "policy BEFORE set:" @(:foreign-policy (ea/-domain-state (:cell rb))))
(ea/set-foreign-policy! (:cell rb) :zip)
(println "policy AFTER set:" @(:foreign-policy (ea/-domain-state (:cell rb))))

(def outcome
  (try
    (swap! (:cell rb) (fn [_] {:value foreign :point 1 :version 1 :history []}))
    :swapped
    (catch Throwable t [:threw (ex-message t) (ex-data t)])))
(println "cell swap outcome:" outcome)
(println "@cell rb after:" (pr-str @(:cell rb)))
(println "deref-ref rb:" (pr-str (stm/deref-ref rb)))
(System/exit 0)
