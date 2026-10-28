;; dollar_docs.cljc — every doc sample that reads through eve.alpha/$ (2026-10-23: the macro restored), its setup as the
;; doc gives it and its answer printed beside the one the doc shows: [doc-and-line expected actual same?].
;;
;;   bb -cp "$(bb classpath bb)" doc/release-0.9/logs-2026-10-23/probes/dollar_docs.cljc <dir>      (from eve/)
;;   clojure -M doc/release-0.9/logs-2026-10-23/probes/dollar_docs.cljc <dir>
(require '[eve.alpha :as e]
         '[eve.atom :as ea]
         '[eve.core :as ec]
         '[eve.deftype.int-map :as im]
         '[eve.deftype.rb-tree :as rb]
         '[eve.sorted :as sorted]
         '[eve.obj :as obj]
         '[eve.alpha.ds :as ds])

(def failures (atom 0))
(defn check [where expected actual]
  (let [same? (= expected actual)]
    (when-not same? (swap! failures inc))
    (prn [where expected actual same?])))

;; doc/api-guide.md, eve.alpha
(def counter (e/atom ::counter {:count 0}))
(swap! counter update :count inc)
(reset! counter {:count 10})
(compare-and-set! counter {:count 10} {:count 11})
(check "api-guide eve.alpha" 11 (e/$ (e/get @counter :count)))

;; doc/getting-started.md
(def s (e/atom ::colls {}))
(swap! s (fn [_] {:ints (im/int-map 3 :c 1 :a) :by-name (sorted/sorted-map "b" 2 "a" 1)}))
(swap! s (fn [v] (ec/assoc v :ints (ec/assoc (ec/get v :ints) -10 :neg))))
(check "getting-started :c" :c (e/$ (ec/get (ec/get @s :ints) 3)))

;; doc/atom-domains.md, the domain API
(def d (ea/persistent-atom-domain (str (first *command-line-args*) "/db")))
(def users (ea/lookup-or-create-mmap-atom! d "app/users" {}))
(swap! users assoc :ada {:role :admin})
(swap! users assoc :bob {:role :user})
(check "atom-domains count" 2 (e/$ (e/count @users)))
(check "atom-domains reads section" :admin (e/$ (e/get (e/get @users :ada) :role)))
(ea/close-atom-domain! d)

;; doc/collections.md
(def tags (e/atom ::tags {}))
(swap! tags (fn [v] (assoc v :tags (rb/sorted-set))))
(swap! tags update :tags e/conj "clojure")
(swap! tags update :tags e/conj "javascript")
(swap! tags update :tags e/conj "python")
(swap! tags update :tags e/conj "clojure")
(check "collections 171" true (e/$ (rb/member? (:tags @tags) "python")))
(check "collections 172" ["clojure" "javascript" "python"] (e/$ (vec (e/seq (:tags @tags)))))
(def kws (e/atom ::kws {}))
(swap! kws (fn [v] (assoc v :kw-set (rb/sorted-set :z :a :m :f :b :q))))
(check "collections 182" [:a :b :f :m :q :z] (e/$ (vec (e/seq (:kw-set @kws)))))
(def sm (e/atom ::sm {}))
(swap! sm (fn [v] (assoc v :by-id (sorted/sorted-map 3 :c 1 :a 2 :b 5 :e))))
(check "collections 197" [[1 :a] [2 :b] [3 :c] [5 :e]] (e/$ (vec (e/seq (:by-id @sm)))))
(check "collections 198" [[2 :b] [3 :c] [5 :e]] (e/$ (vec (sorted/subseq (:by-id @sm) >= 2))))
(check "collections 199" [[2 :b] [3 :c]] (e/$ (vec (sorted/subseq (:by-id @sm) > 1 <= 3))))
(check "collections 200" [[3 :c] [2 :b] [1 :a]] (e/$ (vec (sorted/rsubseq (:by-id @sm) < 5))))
(check "collections 201" 2 (e/$ (sorted/rank (:by-id @sm) 3)))
(check "collections 202" [1 :a] (e/$ (sorted/nth-entry (:by-id @sm) 0)))
(swap! sm (fn [v] (assoc v :desc (sorted/sorted-set-by > 1 5 3))))
(check "collections 206" [5 3 1] (e/$ (vec (e/seq (:desc @sm)))))
(swap! sm (fn [v] (assoc v :by-len (sorted/sorted-map-by (fn [a b] (compare (count a) (count b)))
                                                         "ccc" 3 "a" 1 "bb" 2))))
(check "collections 210" [["a" 1] ["bb" 2] ["ccc" 3]] (e/$ (vec (e/seq (:by-len @sm)))))

;; doc/obj.md
(def node-schema (obj/create-schema {:key :int32 :left :int32 :right :int32 :value :float64}))
(def state (e/atom ::state {}))
(swap! state (fn [s] (assoc s :tree (obj/obj node-schema {:key 42 :left -1 :right -1 :value 3.14}))))
(check "obj 73" [42 3.14 4 -1] (e/$ (let [node (:tree @state)]
                                     [(obj/get node :key) (obj/get node :value) (e/count node) (obj/get node :left)])))
(swap! state (fn [s] (obj/assoc! (:tree s) :key 100) s))
(check "obj 87" 100 (e/$ (obj/get (:tree @state) :key)))
(check "obj 89" true (e/$ (obj/cas! (:tree @state) :key 100 200)))
(check "obj 90" 200 (e/$ (obj/add! (:tree @state) :key 10)))
(check "obj 91" 210 (e/$ (obj/sub! (:tree @state) :key 5)))
(check "obj 92" 205 (e/$ (obj/exchange! (:tree @state) :key 999)))
(check "obj 93" 999 (e/$ (obj/get (:tree @state) :key)))
(check "obj 95 throws" "add! not supported on :float64 fields"
       (try (e/$ (obj/add! (:tree @state) :value 1)) nil
            (catch Exception ex (ex-message ex))))
(swap! state (fn [s] (assoc s :point (obj/obj {:x :float64 :y :float64} {:x 1.5 :y 2.5}))))
(check "obj 105" [1.5 2.5] (e/$ [(obj/get (:point @state) :x) (obj/get (:point @state) :y)]))
(def particles (e/atom ::particles {}))
(swap! particles (fn [s] (assoc s :data (obj/obj-array 1000 {:x :int32 :y :int32 :velocity :int32}))))
(swap! particles (fn [s]
                   (let [data (:data s)]
                     (obj/assoc-in! data [0 :x] 42)
                     (obj/assoc-in! data [0 :y] 100)
                     (obj/assoc-in! data [1 :x] 8))
                   s))
(check "obj 127" 42 (e/$ (obj/get-in (:data @particles) [0 :x])))
(check "obj 128" 42 (e/$ (e/get (e/nth (:data @particles) 0) :x)))
(check "obj 129" 0 (e/$ (obj/add-in! (:data @particles) [0 :velocity] 3)))
(check "obj 140" 50 (e/$ (obj/column-reduce (:data @particles) :x 0 (fn [acc _idx v] (+ acc v)))))
(swap! particles (fn [s] (obj/column-map! (:data s) :x (fn [_idx v] (* v 2))) s))
(check "obj 142" [84 16] (e/$ [(obj/get-in (:data @particles) [0 :x]) (obj/get-in (:data @particles) [1 :x])]))

;; doc/data-structures.md, the dataset
(def my-atom (e/atom ::data {}))
(swap! my-atom (fn [v] (assoc v :ds (ds/dataset {:price (e/eve-array :float64 [10.5 20.3 15.0])
                                                 :qty   (e/eve-array :int32 [100 200 150])}))))
(check "data-structures 595" [:price :qty] (e/$ (ds/column-names (:ds @my-atom))))
(check "data-structures 596" 3 (e/$ (ds/row-count (:ds @my-atom))))
(check "data-structures 597" {:price :float64, :qty :int32} (e/$ (ds/dtypes (:ds @my-atom))))
(check "data-structures 598" 2 (e/$ (ds/row-count (ds/filter-rows (:ds @my-atom) :price #(> % 12.0)))))
(check "data-structures 599" [10.5 15.0 20.3]
       (e/$ (vec (e/seq (ds/column (ds/sort-by-column (:ds @my-atom) :price :asc) :price)))))

;; doc/api-guide.md known limitation: im/range on an int-map read through $
(check "api-guide im/range under $ (the limitation: throws)" :throws
       (try (e/$ (into {} (im/range (ec/get @s :ints) 0 5))) :answered
            (catch Exception _ :throws)))

(println (if (zero? @failures) "dollar_docs: ok" (str "dollar_docs: " @failures " differ")))
