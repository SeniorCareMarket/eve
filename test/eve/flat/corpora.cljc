(ns eve.flat.corpora
  "The scoreboard's corpora: the four values of MEASUREMENTS.md M6, built
   deterministically — map 10k int -> int, vector 100k ints, vector 10k
   records, 5k chat messages — plus a :tiny set for the smoke test. The chat
   messages are the probe corpora's (doc/flat/probes/corpora.clj, seed 42),
   copied so the board's numbers stay comparable with M6's."
  (:require ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [clojure.string :as str])
  #?(:clj (:import [java.util Random])))

(def canonical-sizes
  "Corpus kind -> its M6 size."
  {:map-10k 10000 :vec-100k 100000 :records-10k 10000 :messages-5k 5000})

(def tiny-sizes
  {:map-10k 100 :vec-100k 1000 :records-10k 100 :messages-5k 50})

(def corpus-order [:map-10k :vec-100k :records-10k :messages-5k])

(def corpus-labels
  {:map-10k "map 10k int->int" :vec-100k "vector 100k ints"
   :records-10k "vector 10k records" :messages-5k "5k chat messages"})

#?(:clj
   (do
     (def ^:private words
       ["the" "quick" "atom" "swap" "value" "epoch" "slab" "domain" "flat" "persistent" "vector" "map" "chunk" "page"
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
               (range n)))))

   :cljs
   (defn messages [n]
     (throw (ex-info "eve.flat.corpora/messages is JVM-only for now" {:n n}))))

(defn build
  "The corpus value of the given kind at the given size."
  [kind n]
  (case kind
    :map-10k (zipmap (range n) (range n))
    :vec-100k (vec (range n))
    :records-10k (mapv (fn [i] {:id i :name (str "user-" i) :active (even? i) :score (* 1.5 i)})
                       (range n))
    :messages-5k (messages n)))

(defn corpora
  "[[kind value] ...] in board order, at :canonical (M6) or :tiny size."
  [size-set]
  (let [sizes (case size-set :canonical canonical-sizes :tiny tiny-sizes)]
    (mapv (fn [k] [k (build k (get sizes k))]) corpus-order)))
