;; T1 re-derivation probe — A16's claim at THIS pin (7402ef9 + W0 cfa86c6):
;; "a two-ref transaction whose second value is a slab value of another domain
;;  throws at store time and leaves the first ref installed"
;; (ASSUMPTIONS.md A16; census TYPES-AND-DEREF-2.md §1 R4/R5, at eve 4a92129).
;;
;; Run: cd <tree> && CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;        /home/hatch/.local/bin/clojure -M doc/static/probes-t1/a16_half_commit.clj
(ns t1.a16-half-commit
  (:require [eve.stm :as stm]
            [eve.atom :as ea]
            [eve.deftype-proto.data :as d]))

(println "pin: 7402ef9 + w0 cfa86c6 | clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(def ra (stm/make-ref 0))
(def rb (stm/make-ref 0))
(def rc (stm/make-ref {:donor 1}))

;; a slab value of rc's cell domain: rc's committed cell map itself, read
;; under the pin and kept (rc stays alive and unswapped for the probe).
(def foreign (ea/read-pinned (:cell rc) (fn [v] v)))
(println "foreign type:" (type foreign)
         "| foreign domain is rc's cell domain:"
         (identical? (ea/atom-sio (:cell rc)) (d/-eve-sio foreign)))

(def outcome
  (try
    (stm/dosync
      (stm/ref-set* ra 1)
      (stm/ref-set* rb foreign))
    :no-throw
    (catch Throwable t
      [:threw (ex-message t)])))

(println "dosync outcome:" outcome)
(println "ra after:" (stm/deref-ref ra) "(1 = the first ref's install COMMITTED)")
(println "rb after:" (stm/deref-ref rb) "(0 = the second ref's install never happened)")
(println (if (and (= :no-throw outcome) false)
           "VERDICT: claim does not reproduce as stated"
           (if (and (vector? outcome) (= 1 (stm/deref-ref ra)) (= 0 (stm/deref-ref rb)))
             "VERDICT: A16 REPRODUCED at this pin — threw at store time, first ref installed, second not."
             "VERDICT: outcome differs from the claim — see lines above.")))
(System/exit 0)
