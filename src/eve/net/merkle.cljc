(ns eve.net.merkle
  "aegis's binary Merkle tree over Poseidon2 (depth D = 20) and the sorted-entry sidecar a map's MERKLE_ROOT commits to
   (ExtCID v2 @160) — the kernel's merkle.vs and hamt.vs hm_sidecar_* moved into
   eve's crypto feature, held to the kernel's answers by
   test/eve/net/merkle_test.cljc.

     (zh)                       the empty subtree at each level: zh[0] = 0, zh[l+1] = hash(zh[l], zh[l])
     (root leaves)              the depth-20 root over the leaves in slots 0..n-1, every other slot empty (0)
     (opening leaves i)         {:leaf :index :directions :siblings} of slot i (merkle.nr merkle_path_membership<20>)
     (path-root opening)        the root an opening folds to
     (sidecar-leaves entries)   [MIN, the entries' leaves sorted by kvhash then key bytes, MAX]; an entry is
                                {:kvhash 16 bytes :key bytes :val bytes}; MIN = hash(0, 0), MAX = hash(BE16(0xFF × 16), 0)
     (sidecar-root entries)     (root (sidecar-leaves entries))

   Field elements are eve.net.bn254 limbs; bytes are vectors of 0..255. The constants (zh, MIN, MAX: 23 hashes) are
   computed at first use, never at load: a program that requires this namespace through eve.net.codec and never seals
   pays nothing (MEASURED 2026-10-19 on VS, computed at load: requiring this namespace took the slab from 1,345 to 22,929
   64 KB pages of 32,768 — what every VS network atom's program started at, the mirror's included)."
  (:require [eve.net.bn254 :as f]
            [eve.net.poseidon2 :as p]))

(def D 20)

(defonce ^:private consts (atom {}))
(defn- constant
  "constant k, f computed at its first use and kept in consts (an atom: a delay would do, but clojerl compiles a def's
   value as a literal, and a delay is not one)"
  [k f]
  (if (contains? @consts k)
    (get @consts k)
    (let [v (f)]
      (swap! consts assoc k v)
      v)))

(defn zh
  "the empty subtree at each level, 0..D"
  []
  (constant :zh (fn [] (vec (reductions (fn [z _] (p/hash z z)) f/zero (range D))))))

(defn- level-up
  "the next level of a level's nodes (l its depth from the leaves): pairs hashed, an odd last node with zh[l]"
  [nodes l]
  (mapv (fn [[a b]] (p/hash a (if (nil? b) (nth (zh) l) b))) (partition-all 2 nodes)))

(defn root
  "the depth-20 root over the leaves (at most 2^20)"
  [leaves]
  (when (> (count leaves) 1048576) (throw (ex-info "merkle: more than 2^20 leaves" {:n (count leaves)})))
  (loop [l 0 nodes (vec leaves)]
    (cond (= l D) (if (seq nodes) (first nodes) (nth (zh) D))
          (empty? nodes) (nth (zh) D)
          :else (recur (inc l) (level-up nodes l)))))

(defn opening
  "slot i's opening: its leaf (0 for an empty slot), i, the 20 directions (bit l of i: 1 = the node is the RIGHT child)
   and the 20 siblings, level 0 first"
  [leaves i]
  (loop [l 0 nodes (vec leaves) idx i sibs []]
    (if (= l D)
      {:leaf (if (< i (count leaves)) (nth leaves i) f/zero)
       :index i
       :directions (mapv (fn [l] (mod (quot i (reduce * 1 (repeat l 2))) 2)) (range D))
       :siblings sibs}
      (let [s (if (even? idx) (inc idx) (dec idx))
            sib (if (< s (count nodes)) (nth nodes s) (nth (zh) l))]
        (recur (inc l) (level-up nodes l) (quot idx 2) (conj sibs sib))))))

(defn path-root "the root an opening folds to" [{:keys [leaf directions siblings]}]
  (reduce (fn [acc [d s]] (if (= 1 d) (p/hash s acc) (p/hash acc s))) leaf (map vector directions siblings)))

(defn- bytes-compare "bytewise, a shorter prefix first" [a b]
  (let [n (min (count a) (count b))]
    (loop [i 0]
      (cond (= i n) (compare (count a) (count b))
            (< (nth a i) (nth b i)) -1
            (> (nth a i) (nth b i)) 1
            :else (recur (inc i))))))

(defn- min-leaf []
  (constant :min-leaf (fn [] (p/hash f/zero f/zero))))
(defn- max-leaf []
  (constant :max-leaf
         (fn [] (p/hash (f/from-bytes-be (vec (repeat 16 255))) f/zero))))

(defn sidecar-leaves
  "the sidecar's leaves: MIN, the entries sorted by kvhash then key bytes (a stable sort), each its\n   hash(BE16(kvhash), hash(string->field(key), string->field(val))), MAX"
  [entries]
  (when (> (count entries) 1048574)
    (throw (ex-info "merkle: more than 2^20 - 2 sidecar entries"
                    {:n (count entries)})))
  (let [sorted (sort (fn [x y]
                       (let [c (bytes-compare (:kvhash x) (:kvhash y))]
                         (if (zero? c) (bytes-compare (:key x) (:key y)) c)))
                     entries)]
    (-> [(min-leaf)]
        (into (map (fn [e] (p/leaf (:kvhash e) (:key e) (:val e))) sorted))
        (conj (max-leaf)))))

(defn sidecar-root "the MERKLE_ROOT of a map's entries" [entries] (root (sidecar-leaves entries)))
