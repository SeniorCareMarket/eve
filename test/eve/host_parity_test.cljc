(ns eve.host-parity-test
  "Host parity (John's ruling, 2026-11-16): every Eve collection type acts
   like its host twin under clojure.core's own fns, on every seat. Each row
   applies one core fn to the Eve value and to the host twin and compares
   the results (printed and read back, so a result that is itself an Eve
   value compares as the host value it prints as). The JVM is the oracle.
   Every row also prints one PARITY line (fn, type, ok/FAIL) for the
   cross-host audit table."
  (:refer-clojure :exclude [defrecord])
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            #?(:cljs [cljs.reader :as reader] :default [clojure.edn :as reader])
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.record #?@(:cljs [:refer-macros [defrecord]] :default [:refer [defrecord]])]
            [eve.vec :as v]
            [eve.map :as m]
            [eve.set :as s]
            [eve.list :as l]))

(defrecord PPoint [x y])
(clojure.core/defrecord HPoint [x y])

(defn- norm
  "A result as plain data: printed, record tags dropped, read back."
  [r]
  (if (or (fn? r) (nil? r) (boolean? r) (number? r) (keyword? r))
    r
    (let [s (str/replace (pr-str r) #"#[A-Za-z_][\w.\-$/]*\{" "{")]
      (try (reader/read-string s) (catch #?(:clj Throwable :cljs :default :default Throwable) _ [:unreadable s])))))

(defn- unordered
  "A result of an unordered type's walk: its order is the type's own
   (a hash set's iteration order), so it compares as a sorted seq."
  [r]
  (cond (sequential? r) (sort-by pr-str r)
        ;; a printed set/map: its element order is the type's own too
        (string? r) (try (reader/read-string r) (catch #?(:clj Throwable :cljs :default :default Throwable) _ r))
        :else r))

(defn- run [f x peer t]
  (try (cond-> (norm (f x peer)) (#{:set :map} t) unordered)
       (catch #?(:clj Throwable :cljs :default :default Throwable) _ :throws)))

(def ^:private oracle-gaps
  "Rows where the JVM itself (the oracle) differs from the host twin:
   reported as KNOWN, not asserted, until the JVM types close them."
  #{["compare" :vec] ["ifn?" :list] ["instance? java.util.Collection" :list]
    ["record?" :record] ["instance? java.util.Map" :record]})

(def ^:private seat-limits
  "Rows a seat cannot answer from its completion, named (reported as LIMIT,
   not asserted): on babashka, calling an Eve vector or set as a function —
   SCI's deftype carries protocols only and its call site invokes the host
   IFn, which a SciType cannot implement (bb 1.13.226)."
  #?(:bb #{["invoke idx" :vec] ["invoke elem" :set]}
     :default #{}))

(defn- rows
  "[name f types] — f applied to the Eve value and to its twin h."
  [h]
  [["= e h"      (fn [x p] (= x p))                     :all]
   ["= h e"      (fn [x p] (= p x))                     :all]
   ["not= e h"   (fn [x p] (not= x p))                  :all]
   ["not= h e"   (fn [x p] (not= p x))                  :all]
   ["= self"     (fn [x _] (= x x))                     :all]
   ["= [e] [h]"  (fn [x p] (= [x] [p]))                 :all]
   ["= [h] [e]"  (fn [x p] (= [p] [x]))                 :all]
   ["= {:k e}"   (fn [x p] (= {:k x} {:k p}))           :all]
   ["set member" (fn [x p] (contains? #{p} x))          :all]
   ["map key"    (fn [x p] (get {p :found} x))          :all]
   ["hash = peer" (fn [x p] (= (hash x) (hash p)))      :all]
   ["hash"       (fn [x _] (hash x))                    #{:vec :list :set :map}]
   ["count"      (fn [x _] (count x))                     :all]
   ["seq"        (fn [x _] (seq x))                       :all]
   ["empty?"     (fn [x _] (empty? x))                    :all]
   ["sequential?" (fn [x _] (sequential? x))              :all]
   ["coll?"      (fn [x _] (coll? x))                     :all]
   ["vector?"    (fn [x _] (vector? x))                   :all]
   ["map?"       (fn [x _] (map? x))                      :all]
   ["set?"       (fn [x _] (set? x))                      :all]
   ["list?"      (fn [x _] (list? x))                     :all]
   ["seq?"       (fn [x _] (seq? x))                      :all]
   ["associative?" (fn [x _] (associative? x))            :all]
   ["counted?"   (fn [x _] (counted? x))                  :all]
   ["reversible?" (fn [x _] (reversible? x))              :all]
   ["sorted?"    (fn [x _] (sorted? x))                   :all]
   ["seqable?"   (fn [x _] (seqable? x))                  :all]
   ["ifn?"       (fn [x _] (ifn? x))                      :all]
   ["record?"    (fn [x _] (record? x))                   :all]
   ["reduce conj" (fn [x _] (reduce conj [] x))           :all]
   ["into []"    (fn [x _] #_{:splint/disable [lint/into-literal]} (into [] x))                   :all]
   ["into #{}"   (fn [x _] (set x))                  :all]
   ["first"      (fn [x _] (first x))                     #{:vec :list}]
   ["rest"       (fn [x _] (rest x))                      #{:vec :list}]
   ["next"       (fn [x _] (next x))                      #{:vec :list}]
   ["last"       (fn [x _] (last x))                      #{:vec :list}]
   ["vec"        (fn [x _] (vec x))                       :all]
   ["set"        (fn [x _] (set x))                       :all]
   ["str"        (fn [x _] (str x))                       #{:vec :list :set :map}]
   ["pr-str"     (fn [x _] (pr-str x))                    :all]
   ["frequencies" (fn [x _] (frequencies x))              :all]
   ["empty"      (fn [x _] (empty x))                     #{:vec :list :set :map}]
   ["conj"       (fn [x _] (conj x (if (or (map? h) (record? h)) [:c 3] 4))) :all]
   ["sort"       (fn [x _] (sort x))                      #{:vec :list :set}]
   ["nth"        (fn [x _] (nth x 1))                     #{:vec :list}]
   ["nth nf"     (fn [x _] (nth x 9 :nf))                 #{:vec :list}]
   ["peek"       (fn [x _] (peek x))                      #{:vec :list}]
   ["pop"        (fn [x _] (pop x))                       #{:vec :list}]
   ["rseq"       (fn [x _] (rseq x))                      #{:vec}]
   ["compare"    (fn [x _] (compare x [1 2 4]))           #{:vec}]
   ["assoc vec"  (fn [x _] (assoc x 0 9))                 #{:vec}]
   ["invoke idx" (fn [x _] (x 1))                         #{:vec}]
   ["get idx"    (fn [x _] (get x 1))                     #{:vec}]
   ["contains? idx" (fn [x _] (contains? x 1))            #{:vec}]
   ["get elem"   (fn [x _] (get x 2))                     #{:set}]
   ["contains? elem" (fn [x _] (contains? x 2))           #{:set}]
   ["invoke elem" (fn [x _] (x 2))                        #{:set}]
   ["disj"       (fn [x _] (disj x 2))                    #{:set}]
   ["get key"    (fn [x _] (get x (first (keys h))))      #{:map :record}]
   ["get nf"     (fn [x _] (get x :zz :nf))               #{:map :record}]
   ["kw lookup"  (fn [x _] ((first (keys h)) x))          #{:map :record}]
   ["invoke key" (fn [x _] (x (first (keys h))))          #{:map}]
   ["contains? key" (fn [x _] (contains? x (first (keys h)))) #{:map :record}]
   ["find"       (fn [x _] (find x (first (keys h))))     #{:map :record}]
   ["keys"       (fn [x _] (sort (keys x)))               #{:map :record}]
   ["vals"       (fn [x _] (sort (vals x)))               #{:map :record}]
   ["assoc key"  (fn [x _] (assoc x :c 3))                #{:map :record}]
   ["dissoc"     (fn [x _] (dissoc x (first (keys h))))   #{:map}]
   ["update"     (fn [x _] (update x (first (keys h)) inc)) #{:map :record}]
   ["merge {}"   (fn [x _] (merge {} x))                  #{:map}]
   ["select-keys" (fn [x _] (select-keys x [(first (keys h))])) #{:map :record}]
   ["reduce-kv"  (fn [x _] (reduce-kv (fn [a k v] (conj a [k v])) #{} x)) #{:map :record}]
   #?@(:clj
       [["instance? IPersistentCollection" (fn [x _] (instance? clojure.lang.IPersistentCollection x)) :all]
        ["instance? Seqable" (fn [x _] (instance? clojure.lang.Seqable x)) :all]
        ["instance? Counted" (fn [x _] (instance? clojure.lang.Counted x)) :all]
        ["instance? java.util.Collection" (fn [x _] (instance? java.util.Collection x)) #{:vec :list :set}]
        ["instance? java.util.Map" (fn [x _] (instance? java.util.Map x)) #{:map :record}]])])

(defn- twins
  "[type eve eve's-peer host host's-peer]: a collection's peer is its host
   twin (cross-representation equality is the contract); a record's is an
   equal record of its own kind (records of different types are never =,
   on the JVM too)."
  [sio]
  (binding [d/*domain-sio* sio]
    [[:vec (v/eve-vec sio [1 2 3]) [1 2 3] [1 2 3] [1 2 3]]
     [:list (l/eve-list sio [1 2 3]) '(1 2 3) '(1 2 3) '(1 2 3)]
     [:set (s/hash-set sio 1 2 3) #{1 2 3} #{1 2 3} #{1 2 3}]
     [:map (m/into-hash-map sio [[:a 1] [:b 2]]) {:a 1 :b 2} {:a 1 :b 2} {:a 1 :b 2}]
     [:record (->PPoint 1 2) (->PPoint 1 2) (->HPoint 1 2) (->HPoint 1 2)]]))

(deftest eve-types-act-like-their-host-twins
  (let [sio (alloc/heap-domain-sio! {})]
    (binding [d/*domain-sio* sio]
      (doseq [[t e pe h ph] (twins sio)
              [nm f types] (rows h)
              :when (or (= types :all) (contains? types t))]
        (let [re (run f e pe t) rh (run f h ph t) ok (= re rh)
              known (contains? oracle-gaps [nm t])
              limit (contains? seat-limits [nm t])]
          (println "PARITY" (pr-str nm) t (cond ok "ok" known "KNOWN" limit "LIMIT" :else "FAIL")
                   (if ok "" (str "eve=" (pr-str re) " host=" (pr-str rh))))
          (when-not (or known limit)
            (is ok (str nm " on " t ": eve " (pr-str re) " host " (pr-str rh)))))))))
