;; T1 probe 2 — domain identity facts behind the STM :value read-back.
;; Run: cd <tree> && PATH=/home/hatch/.local/bin:$PATH \
;;   CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;   /home/hatch/.local/bin/clojure -M doc/static/probes-t1/domain_identity_probe.clj
(ns t1.domain-identity-probe
  (:require [eve.stm :as stm]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]))

(def rb (stm/make-ref 0))
(def rc (stm/make-ref {:donor 1}))
(def sio-b (ea/atom-sio (:cell rb)))
(def sio-c (ea/atom-sio (:cell rc)))
(def foreign (ea/read-pinned (:cell rc) (fn [v] v)))

(println "atom-sio rb identical to atom-sio rc:" (identical? sio-b sio-c))
(println "sio-root rb identical to sio-root rc:"
         (identical? (alloc/sio-root sio-b) (alloc/sio-root sio-c)))
(println "foreign -eve-sio identical to atom-sio rc:" (identical? (d/-eve-sio foreign) sio-c))
(println "foreign -eve-sio identical to atom-sio rb:" (identical? (d/-eve-sio foreign) sio-b))
(println "atom-domain rb identical to atom-domain rc:"
         (identical? (ea/atom-domain (:cell rb)) (ea/atom-domain (:cell rc))))
(println "domain-state rb identical to rc:"
         (identical? (ea/-domain-state (:cell rb)) (ea/-domain-state (:cell rc))))

;; and A16's throw site, with the stack's top eve frames
(def ra (stm/make-ref 0))
(def rb2 (stm/make-ref 0))
(try
  (stm/dosync
    (stm/ref-set* ra 1)
    (stm/ref-set* rb2 foreign))
  (println "A16 rerun: NO THROW (unexpected)")
  (catch Throwable t
    (println "A16 rerun threw:" (ex-message t))
    (doseq [f (take 12 (filter (fn [^StackTraceElement e]
                                 (let [c (.getClassName e)]
                                   (or (.contains c "eve.codec")
                                       (.contains c "eve.atom")
                                       (.contains c "eve.stm"))))
                               (.getStackTrace t)))]
      (println "  " f))))
(System/exit 0)
