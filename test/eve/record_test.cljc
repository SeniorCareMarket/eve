(ns eve.record-test
  (:refer-clojure :exclude [defrecord])
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            #?(:cljs [cljs.reader :as reader] :default [clojure.edn :as reader])
            [eve.host :refer [swap!]]
            [eve.record :as r #?@(:cljs [:refer-macros [defrecord]] :default [:refer [defrecord]])]
            [eve.deftype #?@(:cljs [:refer-macros [eve-deftype]] :default [:refer [eve-deftype]])]
            [eve.core :as e]
            [eve.atom :as a]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.slab-io :as slab]
            [eve.map :as em]
            [eve.retire :as retire]
            [eve.process :as proc]
            [eve.vec :as ev]))

(defn- in-a-domain-of-its-own
  "Construction law (2026-11-11): Eve data is constructed in a domain
   context, never through a process-heap fallback (alloc/current-sio throws
   outside one). Each test here builds in an explicit heap domain of its
   own (rrb-vector-test's fixture), dropped with the test."
  [f]
  (binding [d/*domain-sio* (alloc/heap-domain-sio!
                              {:capacities {0 (* 64 1024 1024)
                                            1 (* 16 1024 1024)
                                            2 (* 16 1024 1024)
                                            3 (* 192 1024 1024)
                                            4 (* 16 1024 1024)
                                            5 (* 16 1024 1024)}})]
    (f)))

(use-fixtures :each in-a-domain-of-its-own)

(defn field [r k] (e/get r k))

(defrecord Point [x y])
(defrecord OtherPoint [x y])
(defrecord Five [a b c d e])

(defprotocol Flavor (flavor [this]))
(extend-type Point Flavor (flavor [this] [:point (e/get this :x)]))
(extend-type OtherPoint Flavor (flavor [this] [:other (e/get this :y)]))
(extend-type nil Flavor (flavor [_] :native-nil))

(deftest logical-protocol-dispatch
  (is (= [:point 1] (flavor (->Point 1 2))))
  (is (= [:other 2] (flavor (->OtherPoint 1 2))))
  (is (= [:point 3] (flavor (e/assoc (->Point 1 2) :x 3))))
  (is (= [:other 2] (flavor (e/with-meta (->OtherPoint 1 2) {:m true}))))
  (is (= :native-nil (flavor nil))))

(deftest record-operations
  (let [p (->Point 1 [2 3])
        q (e/assoc p :extra {:n 4})
        m (e/with-meta q {:where :slab})]
    (is (r/record? p))
    (is (satisfies? d/IEveRoot p))
    (is (= 1 (e/get p :x)))
    (is (= :missing (e/get p :absent :missing)))
    (is (e/contains? p :x))
    (is (not (e/contains? p :absent)))
    (is (= [:x 1] (vec (r/entry-at p :x))))
    (is (= 2 (e/count p)))
    (is (= 3 (e/count q)))
    (is (= #{:x :y :extra} (set (map key (e/seq q)))))
    (is (ev/eve-vector? (e/get p :y)))
    (is (em/eve-hash-map? (e/get q :extra)))
    (is (= 4 (e/get (e/get q :extra) :n)))
    (is (= q m))
    (is (= (hash q) (hash m)))
    (is (= (r/hash-record q) (r/hash-record m)))
    (is (= :slab (e/get (e/meta m) :where)))
    (is (em/eve-hash-map? (r/-extras m)))
    (is (em/eve-hash-map? (e/meta m)))
    (is (r/record? (e/dissoc q :extra)))
    (is (= p (e/dissoc q :extra)))
    (is (em/eve-hash-map? (e/dissoc p :x)))
    (is (= {:y [2 3]} (d/eve->clj (e/dissoc p :x))))
    (is (em/eve-hash-map? (e/empty p)))
    (is (zero? (e/count (e/empty p))))
    (is (= 9 (field (e/assoc p :x 9) :x)))
    (is (= 1 (field p :x)))
    (is (not= p (->OtherPoint 1 [2 3])))
    (is (not= p {:x 1 :y [2 3]}))
    (is (= p (map->Point {:x 1 :y [2 3]})))
    (is (= nil (field (map->Point {:x 1}) :y)))
    (is (= 8 (field (map->Point {:x 1 :extra 8}) :extra)))
    (is (= (r/hash-record (->Point 1 2)) (r/hash-record (map->Point {:y 2 :x 1}))))))

(deftest unchanged-slots-share-slab-payloads
  (let [p (e/with-meta (e/assoc (->Point [1 2] {:nested [3 4]}) :extra [5]) {:m [6]})
        q (e/assoc p :x [8])
        sio (d/-eve-sio p)
        off (d/-root-header-off p)
        off2 (d/-root-header-off q)]
    (is (not= off off2))
    (doseq [slot [8 12 16]]
      (is (= (slab/read-i32 sio off slot) (slab/read-i32 sio off2 slot))))
    (is (= [[4 :vval] [8 :vval] [12 :vval] [16 :vval]]
           (ser/type-refs (slab/read-u8 sio off 0)
                          (@ser/header-name-hash-reader sio off))))))

(defn rejects? [f]
  (try (f) false (catch #?(:cljs :default :lpy python/Exception :default Exception) _ true)))

(deftest no-private-ref-escape
  (is (rejects? #(->Point inc 1)))
  (is (rejects? #(->Point {:deep [inc]} 1)))
  (is (rejects? #(e/assoc (->Point 1 2) :extra inc)))
  (is (rejects? #(e/with-meta (->Point 1 2) {:bad inc})))
  (is (rejects? #(map->Point {:x 1 :extra {:bad inc}})))
  (let [private-map (em/hash-map :bad inc)]
    (is (rejects? #(->Point private-map 2)))
    (is (rejects? #(e/assoc (->Point 1 2) :extra private-map))))
  (let [sio (alloc/current-sio) xs (ev/eve-vec sio [1 2]) p (->Point xs 2)]
    (is (= (d/-root-header-off xs) (d/-root-header-off (field p :x))))))

(deftest persistent-record-survives-reopen
  ;; Same pattern as eve.domain-open-test, but the root is a real nominal record,
  ;; created in swap!'s domain. No process-local constructor/cache survives reopen.
  (let [base (str "/tmp/eve-record-e7-" (rand-int 1000000000) "/d")
        dom (a/persistent-atom-domain base)
        root (a/lookup-or-create-mmap-atom! dom "test/record" nil)]
    (swap! root (fn [_] (e/with-meta (e/assoc (->Point {:items [1 2]} 7) :extra #{3 4}) {:saved true})))
    (a/close-atom-domain! dom)
    (let [dom2 (a/persistent-atom-domain base)
          root2 (a/lookup-or-create-mmap-atom! dom2 "test/record" nil)
          start (proc/now-ms)
          p @root2
          first-value (field p :x)
          first-read-ms (- (proc/now-ms) start)]
      (println "RECORD-REOPEN first-deref+lookup-ms" first-read-ms)
      (is (= {:items [1 2]} first-value))
      (is (r/record? p))
      (is (= "eve.record-test/Point" (r/type-key p)))
      (is (= {:items [1 2]} (field p :x)))
      (is (satisfies? d/IEveRoot (field p :extra)))
      (is (= #{3 4} (d/eve->clj (field p :extra))))
      (is (true? (e/get (e/meta p) :saved)))
      (let [started (proc/now-ms)]
        (swap! root2 e/assoc :y 8)
        (println "RECORD-REOPEN first-transaction-ms" (- (proc/now-ms) started)))
      (is (= 8 (field @root2 :y)))
      (a/close-atom-domain! dom2))))

(deftest functions-have-an-actionable-error
  (doseq [f [#(->Point inc nil)
             #(->Point {:nested [inc]} nil)
             #(e/assoc (->Point 1 2) :f inc)
             #(e/with-meta (->Point 1 2) {:f inc})
             #(map->Point {:x 1 :f inc})]]
    (let [message (try (f) nil
                      (catch #?(:cljs :default :lpy python/Exception :default Exception) error
                        (ex-message error)))]
      (is (and message (str/includes? message "functions can't be stored in Eve memory; pass them as call arguments"))))))

(eve-deftype Gem [^:int32 x])
(defrecord Bag [v])

(deftest portable-field-values
  (testing "symbols and keywords are data, not functions: ifn? is their
            lookup protocol, and assert-portable! must not refuse them"
    (is (= 'some-symbol (field (->Bag 'some-symbol) :v)))
    (is (= :kw (field (->Bag :kw) :v)))
    (is (= 'a (first (field (->Bag ['a 'b]) :v))))
    (is (= 1 (e/get (field (->Bag {'k 1}) :v) 'k))))
  (testing "a field may hold a value of a registered eve-deftype type
            (its registry entry certifies :record/portable?)"
    ;; Gem declares no equiv group, so views of its block are compared
    ;; by field, not by =.
    (let [g (->Gem 7)]
      (is (= 7 (.-x (field (->Bag g) :v))))
      (is (= 7 (.-x (e/get (field (->Bag {:inner g}) :v) :inner)))))))

(deftest hidden-payloads-are-reclaimed-after-last-root
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "record/reclaim" nil)
        y (a/lookup-or-create-mmap-atom! dom "record/share" nil)
        sio (:sio (a/-domain-state x))]
    (swap! x (fn [_] (e/with-meta (e/assoc (->Point [1 2] {:k [3 4]}) :extra {:v [5 6]}) {:m [7 8]})))
    (let [before (a/read-pinned x (fn [p] (set (retire/reach sio (d/-root-header-off p)))))
          _ (a/read-pinned x (fn [p] (swap! y (constantly p)) nil))]
      (swap! x (constantly nil))
      (#'a/flush-zero-queue! (a/-domain-state x))
      (is (every? #(alloc/allocated? sio %) before))
      (is (= {:v [5 6]} (field @y :extra)))
      (swap! y (constantly nil))
      (#'a/flush-zero-queue! (a/-domain-state y))
      (is (not-any? #(alloc/allocated? sio %) before)))))

(deftest readers-keep-extension-fields
  (let [original (e/assoc (->Point 11 12) :extension 13)
        text (pr-str original)
        copy (reader/read-string text)]
    (is (= original copy))
    (is (= 13 (field copy :extension)))
    (is (not (str/includes? text "record-extmap__")))
    (is (not (str/includes? text "record-meta__"))))
)
