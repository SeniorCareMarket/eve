(ns eve.aegis.merkle
  "The SHA-256 Merkle layer of aegis's clear evaluation (aegis/docs/simple-spike/REPORT.md), which replaces aegis's ZK circuit (src/aegis/chat/spec.vs's
   :records and :p/key-assoc-full-sidecar / :p/key-dissoc): the same statements, checked by recomputing hashes in the
   open instead of proving them, with SHA-256 in place of Poseidon2. Portable: bytes are vectors of ints 0..255
   (eve.net.bytes), the hash is eve.crypto/sha256; nothing here names a host. (The depth-20 append-only frontier that
   stood beside the vector a :p/merkle-tail-append writes is gone, ExtCID v3: the vector is the atom's own, and
   eve.aegis.claims reads an append from the two states, its ctx :append-of. The record leaf below stays: it is what
   a claim's digest binds.)

   1. FIELDS AND THE RECORD LEAF (mirror of src/aegis/chat/spec_rules.vs). `field-of` takes a record value to one
      32-byte field: nil -> 32 zero bytes; string -> sha256 of its UTF-8; keyword -> the same over its name; true/false
      -> 32 bytes ending 1/0; a non-negative integer -> 32-byte big-endian; a sequential of those -> `chain` over its
      elements' fields. Anything else (a map, a nested vector, a negative or fractional number) throws. `chain` is
      acc = 32 zero bytes; acc = sha256(acc ++ f) for each f. `record-leaf` is the chain over a :leaf key list's
      fields, an absent key contributing (field-of nil). (An empty vector chains to 32 zero bytes, the same field as
      nil: the ZK encoding has the same overlap.)

   2. THE SIDECAR: a Merkle tree over a map's entries, with membership and non-membership openings. Entries sort by
      the key's UTF-8 bytes (keys are strings or keywords; a string and keyword with the same text collide: refused).
      Leaf = sha256(0x00 ++ u32-be(len kb) ++ kb ++ (field-of value)); the sorted leaves are padded with the empty leaf
      (32 zero bytes) to the next power of two (at least 1); interior = sha256(0x01 ++ left ++ right). The empty map is
      the tree of one empty leaf. The 0x00 / 0x01 tags keep a leaf from standing as an interior node, so a path's
      length is fixed by the root.

      MEMBERSHIP OPENING: {:key kb :value-field f :index i :siblings [...] :root r}.

      NON-MEMBERSHIP OPENING (key absent): {:key kb :root r :lower L :upper U} over the two sorted neighbours of the
      key, each an opening of one leaf of the tree:
        L = nil (the key is below every entry: index -1) or {:key kb' :value-field f' :index i :siblings s}
        U = nil (the key is above every entry AND the entries fill the tree: the tree's last index is L's)
            or {:key kb' :value-field f' :index j :siblings s}      (the least key above)
            or {:empty true :index j :siblings s}                   (a padded empty leaf, proving no entry beyond L)
      `verify-non-membership` checks: each present neighbour recomputes to the root; L's key < key < U's key in
      byte order; their indices are consecutive (L nil -> U's index is 0; U nil -> L's index is the last of 2^depth);
      an empty U right after L (or at 0 with L nil: the empty map) proves nothing lies beyond. An empty L is refused.

      KEY ASSOC / DISSOC: `key-assoc-witness` = {:old-root :non-membership (old tree) :new-root :membership (new tree)};
      `key-dissoc-witness` = {:old-root :membership (old) :new-root :non-membership (new)}. The verifiers also bind
      the key's position: its index in the tree that holds it is one past the lower neighbour's in the tree that does
      not. LIMIT: a sorted insertion shifts every later leaf, so these two openings do NOT prove that the rest of the
      map is unchanged; they prove the key was absent / present, with its value-field and place. Binding the rest needs the caller's own state (the old root is the stored state's)."
  (:require [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

;; ---------------------------------------------------------------------------------------------------------------
;; bytes

(defn- zero32 [] (vec (repeat 32 0)))

(defn- cat-bytes [& parts] (reduce into [] parts))

(defn- sha [bs] (crypto/sha256 (vec bs)))

(defn- u32-be [x] [(quot x 16777216) (mod (quot x 65536) 256) (mod (quot x 256) 256) (mod x 256)])

(defn- bytes32? [x] (and (sequential? x) (= 32 (count x)) (every? (fn [i] (and (integer? i) (<= 0 i 255))) x)))

(defn compare-bytes
  "Lexicographic order of two byte sequences (a proper prefix is the lesser): -1, 0 or 1."
  [a b]
  (let [a (vec a) b (vec b) na (count a) nb (count b) n (min na nb)]
    (loop [i 0]
      (if (< i n)
        (let [x (nth a i) y (nth b i)]
          (cond (< x y) -1 (> x y) 1 :else (recur (inc i))))
        (cond (< na nb) -1 (> na nb) 1 :else 0)))))

(defn key-bytes
  "The UTF-8 bytes of a sidecar key: a string, a keyword (its name), or a byte vector already."
  [k]
  (cond (string? k) (b/utf8 k)
        (keyword? k) (b/utf8 (name k))
        (vector? k) k
        :else (throw (ex-info "a sidecar key must be a string, keyword or byte vector" {:key k}))))

;; ---------------------------------------------------------------------------------------------------------------
;; 1. fields, chain, record leaf

(defn chain
  "acc = 32 zero bytes; acc = sha256(acc ++ f) for each field f (32-byte vectors); the final acc."
  [fields]
  (reduce (fn [acc f] (sha (cat-bytes acc f))) (zero32) fields))

(defn- be32 [n]
  (loop [i 0 x n out '()]
    (if (< i 32)
      (recur (inc i) (quot x 256) (cons (mod x 256) out))
      (if (zero? x)
        (vec out)
        (throw (ex-info "integer does not fit 32 bytes" {:value n}))))))

(defn- scalar-field
  "The field of a scalar, or nil when v is not one (a field is never nil: it is a 32-byte vector)."
  [v]
  (cond (nil? v) (zero32)
        (string? v) (sha (b/utf8 v))
        (keyword? v) (sha (b/utf8 (name v)))
        (true? v) (conj (vec (repeat 31 0)) 1)
        (false? v) (zero32)
        (number? v) (cond (not (integer? v)) (throw (ex-info "a non-integer number has no field encoding" {:value v}))
                          (neg? v) (throw (ex-info "a negative integer has no field encoding" {:value v}))
                          :else (be32 v))
        :else nil))

(defn field-of
  "A record value as one 32-byte field (the ns docstring's encoding); throws ex-info on anything unencodable."
  [v]
  (or (scalar-field v)
      (if (sequential? v)
        (chain (map (fn [x] (or (scalar-field x)
                                (throw (ex-info "a vector field's elements must be scalars" {:value v :element x}))))
                    v))
        (throw (ex-info (str "a :leaf value must be nil, a string, keyword, boolean, non-negative integer or a vector of "
                             "those, got " (pr-str v))
                        {:value v})))))

(defn record-get
  "The value under keyword k of a keyword- or string-keyed record; nil when absent (or not a map)."
  [record k]
  (when (map? record)
    (let [nm (name k) v (get record nm)]
      (if (nil? v) (get record (keyword nm)) v))))

(defn record-leaf
  "The leaf of `record` under the :leaf key list: the chain over (field-of (record-get record k)) per key, in order."
  [leaf-keys record]
  (chain (map (fn [k] (field-of (record-get record k))) leaf-keys)))

;; ---------------------------------------------------------------------------------------------------------------
;; opening paths (the sidecar's)

(defn- pow2 [l] (reduce * 1 (repeat l 2)))

(defn- path-root
  "The root from `leaf` at `index` over `siblings` (bit l of index: 0 -> cur is the left child), with node-fn joining
   (left right); nil when index has bits left over after the siblings are used."
  [node-fn leaf index siblings]
  (loop [cur leaf idx index sibs (seq siblings)]
    (if sibs
      (recur (if (even? idx) (node-fn cur (first sibs)) (node-fn (first sibs) cur)) (quot idx 2) (next sibs))
      (when (zero? idx) cur))))

(defn- valid-siblings? [siblings] (and (sequential? siblings) (every? bytes32? siblings)))

;; ---------------------------------------------------------------------------------------------------------------
;; 2. the sidecar

(defn- sc-leaf [kb vf] (sha (cat-bytes [0] (u32-be (count kb)) kb vf)))

(defn sidecar-leaf
  "The sidecar leaf of `key` (string, keyword or bytes) with `value-field`: what a membership opening of the key proves
   is in the tree (eve.aegis.claims/session-commit: a session's leaf in its registry)."
  [key value-field]
  (sc-leaf (key-bytes key) (vec value-field)))

(defn- sc-node [l r] (sha (cat-bytes [1] l r)))

(defn- sorted-entries
  "[[key-bytes value-field] ...] sorted by key bytes; throws on two keys with the same bytes."
  [m]
  (let [es (vec (sort-by first compare-bytes (map (fn [[k v]] [(key-bytes k) (field-of v)]) m)))]
    (doseq [[[a _] [c _]] (partition 2 1 es)]
      (when (zero? (compare-bytes a c)) (throw (ex-info "two sidecar keys have the same bytes" {:key a}))))
    es))

(defn- build
  "{:entries sorted :size 2^k :levels [leaves .. [root]] :root r}"
  [m]
  (let [es (sorted-entries m)
        leaves (mapv (fn [[kb vf]] (sc-leaf kb vf)) es)
        size (loop [p 1] (if (>= p (count leaves)) p (recur (* 2 p))))
        padded (into leaves (repeat (- size (count leaves)) (zero32)))
        levels (loop [lv [padded]]
                 (let [cur (peek lv)]
                   (if (= 1 (count cur))
                     lv
                     (recur (conj lv (mapv (fn [i] (sc-node (nth cur (* 2 i)) (nth cur (inc (* 2 i)))))
                                           (range (quot (count cur) 2))))))))]
    {:entries es :size size :levels levels :root (first (peek levels))}))

(defn- siblings-at [levels i]
  (loop [ls (pop levels) idx i out []]
    (if (seq ls)
      (let [lv (first ls)]
        (recur (rest ls) (quot idx 2) (conj out (nth lv (if (even? idx) (inc idx) (dec idx))))))
      out)))

(defn sidecar-root
  "The root of the sidecar tree over map `m` (the empty map has a root of its own: one empty leaf)."
  [m]
  (:root (build m)))

(defn- index-of [entries kb]
  (first (keep-indexed (fn [i [k _]] (when (= k kb) i)) entries)))

(defn- entry-opening [{:keys [entries levels]} i]
  (let [[kb vf] (nth entries i)]
    {:key kb :value-field vf :index i :siblings (siblings-at levels i)}))

(defn membership-opening
  "{:key kb :value-field f :index i :siblings [...] :root r} for `key` in `m`, or nil when absent."
  [m key]
  (let [t (build m) i (index-of (:entries t) (key-bytes key))]
    (when i (assoc (entry-opening t i) :root (:root t)))))

(defn verify-membership
  "True when the leaf of `key` (string, keyword or bytes) with `value-field` at `index` over `siblings` hashes to
   `root`."
  [root key value-field index siblings]
  (boolean
   (and (bytes32? root) (bytes32? value-field) (integer? index) (<= 0 index) (valid-siblings? siblings)
        (= (vec root) (path-root sc-node (sc-leaf (key-bytes key) (vec value-field)) index siblings)))))

(defn non-membership-opening
  "The non-membership opening of `key` in `m` (shape: the ns doc), or nil when the key IS present."
  [m key]
  (let [{:keys [entries size levels root] :as t} (build m)
        kb (key-bytes key)
        cmps (mapv (fn [[k _]] (compare-bytes k kb)) entries)]
    (when-not (some zero? cmps)
      (let [c (count (filter neg? cmps)) ; entries below the key: the lower neighbour is c-1, the upper is c
            lower (when (pos? c) (entry-opening t (dec c)))
            upper (cond (< c (count entries)) (entry-opening t c)
                        (< c size) {:empty true :index c :siblings (siblings-at levels c)}
                        :else nil)]
        {:key kb :root root :lower lower :upper upper}))))

(defn- neighbour-ok? [root nb]
  (and (map? nb) (integer? (:index nb)) (<= 0 (:index nb)) (valid-siblings? (:siblings nb))
       (if (:empty nb)
         (= (vec root) (path-root sc-node (zero32) (:index nb) (:siblings nb)))
         (and (vector? (:key nb)) (bytes32? (:value-field nb))
              (= (vec root) (path-root sc-node (sc-leaf (:key nb) (vec (:value-field nb))) (:index nb) (:siblings nb)))))))

(defn verify-non-membership
  "True when `opening` proves `key` is absent from the tree under `root` (the checks: the ns doc)."
  [root key opening]
  (boolean
   (and (bytes32? root) (map? opening)
        (let [kb (key-bytes key) lower (:lower opening) upper (:upper opening)]
          (and (or lower upper)
               (or (nil? lower)
                   (and (not (:empty lower)) (neighbour-ok? root lower) (neg? (compare-bytes (:key lower) kb))))
               (or (nil? upper)
                   (and (neighbour-ok? root upper) (or (:empty upper) (neg? (compare-bytes kb (:key upper))))))
               (or (nil? lower) (nil? upper) (= (count (:siblings lower)) (count (:siblings upper))))
               (let [depth (count (:siblings (or lower upper)))]
                 (cond (and lower upper) (= (inc (:index lower)) (:index upper))
                       lower (= (:index lower) (dec (pow2 depth)))
                       :else (= 0 (:index upper)))))))))

(defn- lower-index [nm] (if-let [l (:lower nm)] (:index l) -1))

(defn key-assoc-witness
  "{:old-root :non-membership (key absent from old-map) :new-root :membership (key present in new-map)}, or nil when
   the key is already in old-map or is not in new-map."
  [old-map new-map key]
  (let [nm (non-membership-opening old-map key)
        mo (membership-opening new-map key)]
    (when (and nm mo)
      {:old-root (:root nm) :non-membership nm :new-root (:root mo) :membership mo})))

(defn verify-key-assoc
  "True when the witness proves `key` absent under `old-root` and present under `new-root` with `value-field`, at the
   index one past its lower neighbour's (the circuit's key_assoc_full_sidecar; see the ns doc's LIMIT)."
  [old-root new-root key value-field witness]
  (boolean
   (and (map? witness)
        (= old-root (:old-root witness)) (= new-root (:new-root witness))
        (let [nm (:non-membership witness) mo (:membership witness)]
          (and (verify-non-membership old-root key nm)
               (map? mo)
               (= (key-bytes key) (:key mo))
               (verify-membership new-root key value-field (:index mo) (:siblings mo))
               (= (:index mo) (inc (lower-index nm))))))))

(defn key-dissoc-witness
  "{:old-root :membership (key in old-map) :new-root :non-membership (key absent from new-map)}, or nil when the key is
   not in old-map or is still in new-map."
  [old-map new-map key]
  (let [mo (membership-opening old-map key)
        nm (non-membership-opening new-map key)]
    (when (and mo nm)
      {:old-root (:root mo) :membership mo :new-root (:root nm) :non-membership nm})))

(defn verify-key-dissoc
  "True when the witness proves `key` present under `old-root` (whatever its value) and absent under `new-root`, its
   old index one past the new lower neighbour's (the circuit's key_dissoc; see the ns doc's LIMIT)."
  [old-root new-root key witness]
  (boolean
   (and (map? witness)
        (= old-root (:old-root witness)) (= new-root (:new-root witness))
        (let [mo (:membership witness) nm (:non-membership witness)]
          (and (map? mo)
               (= (key-bytes key) (:key mo))
               (verify-membership old-root key (:value-field mo) (:index mo) (:siblings mo))
               (verify-non-membership new-root key nm)
               (= (:index mo) (inc (lower-index nm))))))))
