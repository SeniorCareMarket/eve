(ns eve.net.codec-fixture-test
  "Unit 4.2 (eve/doc/network-atoms/IMPL-PLAN.md §6.2): G1 — eve.net.codec against the kernel's own blocks (ExtCID v3's
   seal image; the fixture re-minted by the landed builder). Every row
   of test/eve/fixtures/vsv2-blocks.edn (cut by the kernel: VS's scripts/gen-net-fixtures.vs) decodes value-exact from its
   images, and the row's value re-encodes byte-exact: the same root key, the same set of keys, every image equal."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string]
            #?@(:glj [] :default [[clojure.edn :as edn]])
            [eve.net.bytes :as b]
            [eve.net.codec :as c]))

;; glojure has no clojure.edn: its core read-string reads these fixtures
(def ^:private read-edn #?(:glj read-string :default edn/read-string))

(def ^:private rows
  (let [path "test/eve/fixtures/vsv2-blocks.edn"]
    (read-edn #?(:cljs (.readFileSync (js/require "fs") path "utf8") :default (slurp path)))))

(defn- images [row] (into {} (map (fn [{:keys [sha image]}] [sha (b/unhex image)]) (:blocks row))))

(defn- imaged? [row] (seq (:blocks row)))

(deftest the-corpus
  (is (= 22 (count rows)))
  (is (= 22 (count (filter imaged? rows)))
      "every row, list-mixed among them: a v3 list node holds its value by reference (list.vs), so a list of a string and a vector images"))

(deftest decodes-every-row
  (doseq [row (filter imaged? rows)]
    (testing (:name row)
      (is (= (read-edn (:value row)) (c/decode (:root row) (:set-root? row) (images row)))))))

;; ClojureScript has no character type (nor do basilisp and clojerl): \c reads as the string "c", which the codec writes
;; as a string leaf, so a row holding a character re-encodes byte-exact only where a character is one. Its decode is
;; checked on every host (decodes-every-row).
(def ^:private chars? (not (string? \c)))
(defn- holds-char? [row] (clojure.string/includes? (:value row) " \\"))   ;; " \c": a space, then a character literal

(deftest encodes-every-row-byte-exact
  (is (= 1 (count (filter holds-char? rows))) "one row holds a character: vec-of-kinds")
  (doseq [row (filter imaged? rows)]
    (testing (:name row)
      (if (and (holds-char? row) (not chars?))
        (is (= "c" (last (read-edn (:value row)))) "this host reads the row's character as a one-character string")
        (let [want (images row)
              {:keys [root set-root? blocks]} (c/encode (read-edn (:value row)))]
          (is (= (:root row) root) "the root key")
          (is (= (boolean (:set-root? row)) (boolean set-root?)))
          (is (= (set (keys want)) (set (keys blocks))) "the keys")
          (is (every? (fn [[k img]] (= img (get blocks k))) want) "every image"))))))

(deftest refusals
  (let [thrown (fn [v] (try (c/encode v) nil (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) e (:type (ex-data e)))))]
    (is (= :unencodable (thrown {:k ":looks-like-a-keyword"})) "a string the kernel would read as a keyword")
    (is (= :unencodable (thrown {[1] 2})) "a vector as a key")))

(deftest round-trips-beyond-the-corpus
  (doseq [v [{:big 4294967296 :neg -9007199254740993 :d -0.0 :tiny 5.0E-324 :pi 3.141592653589793 :e 1.0E300}
             {"ünïcødé ✓ 𝄞" [\é 'a.b/sym :ns/kw]}
             (vec (range 2000))
             #{"a" :b 3 4.5}]]
    (let [{:keys [root set-root? blocks]} (c/encode v)]
      (is (= v (c/decode root set-root? blocks)) (pr-str v)))))

(deftest child-keys-walk-the-tree
  (doseq [row (filter imaged? rows)]
    (testing (:name row)
      (let [blocks (images row)
            reach (loop [frontier [(:root row)] seen #{}]
                    (if (empty? frontier)
                      seen
                      (let [nxt (remove seen (mapcat (fn [k] (c/child-keys (get blocks k))) frontier))]
                        (recur (vec (distinct nxt)) (into seen frontier)))))]
        (is (= (set (keys blocks)) reach) "every block is reached from the root, and nothing else")))))

(deftest a-map-of-bands-is-the-maps-own-blocks
  ;; map-of-bands builds a map's own blocks from its values' bands (a writer that holds them — a snapshot's references —
  ;; encodes no value again): the kernel's root, and with each value's own blocks, every block encode makes
  (doseq [row (filter (fn [r] (and (imaged? r) (map? (read-edn (:value r))))) rows)]
    (testing (:name row)
      (let [m (read-edn (:value row))
            pairs (map (fn [[k v]] [k (c/band-of v)]) m)
            {root :root own :blocks} (c/map-of-bands (map (fn [[k [bd _]]] [k bd]) pairs))]
        (is (= (:root row) root))
        (is (= (set (keys (images row))) (set (keys (apply merge own (map (fn [[_ [_ bl]]] bl) pairs))))))
        (is (every? (fn [[k img]] (= (vec img) (vec (get (images row) k)))) own))))))
