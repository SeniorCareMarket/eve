;; The corpora of the static-Eve probes: data Eve actually moves, built deterministically (research code).
;;   :messages   chat-shaped EDN (the net layer's shape: nested maps, sets, short vectors, text)
;;   :records    a dataset as rows (int, unique string, boolean, double, low-cardinality keyword and string, long)
;;   :columns    the same dataset column-major ({col [values]})
;;   :datoms     a DataScript-shaped fact table as rows {:e :a :v :tx} — the logical content of a DB of n people
;;   :repo-edn   real EDN files of this repository, read as data (hosts.edn, deps.edn, shadow-cljs.edn, the
;;               perf results, the byte fixtures): heterogeneous, little repetition — the dictionary's hard case
(ns static-probe.corpora
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.util Random]))

(def ^:private words
  ["the" "quick" "atom" "swap" "value" "epoch" "slab" "domain" "static" "persistent" "vector" "map" "chunk" "page"
   "column" "row" "eve" "hello" "world" "ok" "thanks" "lgtm" "ship" "it" "reading" "writing" "journal" "retire"
   "tally" "broker" "kernel" "wasm" "thread" "fold" "digest" "merkle" "claim" "policy" "spec" "ballot"])

(defn- text [^Random r n] (str/join " " (repeatedly n #(nth words (.nextInt r (count words))))))

(defn messages [n]
  (let [r (Random. 42)]
    (mapv (fn [i]
            {:id (str "m-" i)
             :room (nth [:general :random :dev :ops] (mod i 4))
             :author {:id (mod i 37) :name (str "user-" (mod i 37))}
             :text (text r (+ 3 (.nextInt r 25)))
             :ts (+ 1700000000000 (* i 1000))
             :tags (set (take (mod i 3) [:pinned :edited :thread]))
             :reactions (mapv (fn [j] {:emoji (nth [:+1 :heart :eyes] (mod j 3)) :by (mod (+ i j) 37)})
                              (range (mod i 4)))})
          (range n))))

(def ^:private cities (mapv #(str "city-" %) (range 50)))
(def ^:private depts [:eng :ops :sales :legal :finance :design :support :research])

(defn records [n]
  (let [r (Random. 7)]
    (mapv (fn [i]
            {:id i
             :name (str "user-" i)
             :active (even? (.nextInt r 10))
             :score (* 0.5 (.nextInt r 2000))
             :dept (nth depts (.nextInt r (count depts)))
             :city (nth cities (.nextInt r (count cities)))
             :ts (+ 1700000000000 (* 1000 (long i)))})
          (range n))))

(defn columns [n]
  (let [rows (records n)]
    (into {} (map (fn [k] [k (mapv k rows)])) (keys (first rows)))))

(defn people
  "n entity maps in the shape DataScript's own benchmarks use."
  [n]
  (let [r (Random. 11)
        firsts ["Ivan" "Petr" "Sergei" "Oleg" "Yuri" "Dmitry" "Fedor" "Denis"]
        lasts ["Ivanov" "Petrov" "Sidorov" "Kovalev" "Kuznetsov" "Voronoi"]]
    (mapv (fn [i]
            {:db/id (inc i)
             :name (nth firsts (.nextInt r (count firsts)))
             :last-name (nth lasts (.nextInt r (count lasts)))
             :sex (if (zero? (.nextInt r 2)) :male :female)
             :age (.nextInt r 100)
             :salary (.nextInt r 100000)
             :email (str "p" i "@example.com")})
          (range n))))

(defn datoms
  "The EAVT facts of (people n), as rows, in e-then-a order; one transaction per 100 people."
  [n]
  (vec (for [p (people n)
             a [:age :email :last-name :name :salary :sex]]
         {:e (:db/id p) :a a :v (get p a) :tx (+ 536870913 (quot (dec (long (:db/id p))) 100))})))

(defn- plain
  "EDN as read, made into values every codec here encodes: a tagged literal becomes [tag form], a char a string,
   a regex its source, a symbol stays."
  [x]
  (walk/postwalk (fn [v] (cond (tagged-literal? v) [(str (:tag v)) (:form v)]
                               (char? v) (str v)
                               (instance? java.util.regex.Pattern v) (str v)
                               :else v))
                 x))

(defn repo-edn
  "The repository's own EDN files, each read whole: {file value}."
  [root]
  (into {}
        (keep (fn [f]
                (try [f (plain (edn/read-string {:default tagged-literal} (slurp (str root "/" f))))]
                     (catch Exception e (println "  (skipped" f (.getMessage e) ")") nil))))
        ["hosts.edn" "deps.edn" "shadow-cljs.edn" "doc/hosts/perf-results.edn"
         "test/eve/fixtures/eve-bytes.edn" "test/eve/fixtures/xxh3-vectors.edn"]))
