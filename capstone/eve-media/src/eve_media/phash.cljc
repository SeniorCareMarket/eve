(ns eve-media.phash
  "Perceptual hashes and duplicate groups, portable cljc. A hash is the DCT pHash of a 32×32 grayscale image (1,024
   bytes, row-major, 0..255): the 2-D DCT-II, its top-left 8×8 coefficients without the DC term, each bit set when the
   coefficient is above their median — 64 bits, written as 16 hex digits. Two images are duplicates when their hashes
   differ in at most 10 bits; a duplicate group is a connected set of duplicates.")

(def ^:private N 32)

(def ^:private PI 3.141592653589793)

(defn- cosine
  "cos x by its Taylor series, x first brought into [-π, π] — the same arithmetic on every host, no host's math
   library named (clojerl has no Math/cos)."
  [x]
  (let [tau (* 2 PI)
        r (loop [r (* 1.0 x)]
            (cond
              (> r PI) (recur (- r tau))
              (< r (- PI)) (recur (+ r tau))
              :else r))
        r2 (* r r)]
    (loop [k 0
           term 1.0
           acc 0.0]
      (if (< k 30)
        (recur (inc k) (/ (* (- term) r2) (* (+ (* 2 k) 1) (+ (* 2 k) 2)))
               (+ acc term))
        acc))))

(def ^:private cosines
  "cos((2x+1)uπ/2N) for u, x in [0, N)"
  (vec (for [u (range N)]
         (vec (for [x (range N)]
                (cosine (/ (* (+ (* 2 x) 1) u PI) (* 2 N))))))))

(defn- dct-coefficient [px u v]
  (let [cu (nth cosines u)
        cv (nth cosines v)]
    (loop [y 0
           acc 0.0]
      (if (< y N)
        (recur (inc y)
               (+ acc
                  (* (nth cv y)
                     (loop [x 0
                            a 0.0]
                       (if (< x N)
                         (recur (inc x)
                                (+ a
                                   (* (nth cu x) (* 1.0 (nth px (+ (* y N) x))))))
                         a)))))
        acc))))

(defn phash
  "The 64-bit pHash of px (1,024 grayscale values, 0..255) as 16 lower-case hex digits."
  [px]
  (let [coefs (vec (for [v (range 8) u (range 8)] (dct-coefficient px u v)))
        ac (subvec coefs 1)
        median (nth (vec (sort ac)) (quot (count ac) 2))
        bits (mapv (fn [c] (if (> c median) 1 0)) coefs)
        digit (fn [i] (reduce (fn [acc b] (+ (* 2 acc) b)) 0 (subvec bits (* 4 i) (* 4 (inc i)))))]
    (apply str (map (fn [i] (nth "0123456789abcdef" (digit i))) (range 16)))))

(def ^:private hex-value (zipmap "0123456789abcdef" (range 16)))

(defn distance
  "The Hamming distance between two hex hashes."
  [a b]
  (reduce + 0 (map (fn [x y]
                     (let [v (bit-xor (hex-value x) (hex-value y))]
                       (+ (bit-and v 1) (bit-and (quot v 2) 1) (bit-and (quot v 4) 1) (bit-and (quot v 8) 1))))
                   a b)))

(def threshold 10)

(defn groups-from-edges
  "The duplicate groups of ids given the duplicate pairs edges: sorted vectors of ids, each with at least two members,
   sorted (union-find over the edges)."
  [ids edges]
  (let [root (fn root [p x] (if (= x (get p x x)) x (root p (get p x))))
        parent (reduce (fn [p [a b]]
                         (let [ra (root p a) rb (root p b)]
                           (cond (= ra rb) p (neg? (compare ra rb)) (assoc p rb ra) :else (assoc p ra rb))))
                       {} edges)]
    (->> ids
         (group-by (fn [x] (root parent x)))
         vals
         (filter (fn [g] (> (count g) 1)))
         (map (comp vec sort))
         sort
         vec)))

(defn groups
  "The duplicate groups of hashes ({id hex})."
  [hashes]
  (let [ids (vec (sort (keys hashes)))]
    (groups-from-edges ids (for [i (range (count ids)) j (range (inc i) (count ids))
                                 :let [a (nth ids i) b (nth ids j)]
                                 :when (<= (distance (get hashes a) (get hashes b)) threshold)]
                             [a b]))))
