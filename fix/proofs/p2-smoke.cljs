;; Proof 2: create-conn / transact! / query smoke on nbb over the fixed
;; Eve, against the vanilla port. Expected answers are asserted in-script,
;; so the exit code carries the verdict.
(require 'eve.process-slab)
(eve.process-slab/init! :force true)
(require '[datascript.core :as d])

(def conn (d/create-conn))
(d/transact! conn [{:db/id 1 :name "Ivan" :age 30}
                   {:db/id 2 :name "Anna" :age 25}
                   {:db/id 3 :name "Petr" :age 41}])

(def names (d/q '[:find ?n :where [?e :name ?n]] @conn))
(def over-26 (d/q '[:find ?n :where [?e :name ?n] [?e :age ?a] [(> ?a 26)]] @conn))
(def ivan-age (d/q '[:find ?a :where [?e :name "Ivan"] [?e :age ?a]] @conn))
(def datom-count (count (:eavt @conn)))

(println :names names)
(println :over-26 over-26)
(println :ivan-age ivan-age)
(println :datom-count datom-count)

(assert (= names #{["Ivan"] ["Anna"] ["Petr"]}))
(assert (= over-26 #{["Ivan"] ["Petr"]}))
(assert (= ivan-age #{[30]}))
(assert (= datom-count 6))
(println :SMOKE-OK)
