(ns eve.retire
  "The block graph's readers: the pointers a block holds, by its layout (children), and every block a value
   reaches (reach — eve.atom/seal digests them). The counts that decide what a write frees live on the blocks'
   descriptor rows (eve.tally); this walk reads the graph, it keeps no count of its own.

   Block kinds, each read by its layout: :val (a value's header — a map's, a set's, a vector's, a list's, an eve
   array's, an eve value array's, a scalar root, an eve-deftype instance by its registered field layout), :map-node /
   :set-node (HAMT nodes and their inline values), [:vec shift base toff] (a vector's trie node), [:leaf n] (a
   vector's tail), :vblock (a serialized value block), :cell (a list cell), :varr (an eve value array's slots)."
  (:require
   [eve.deftype-proto.serialize :as ser]
   [eve.hamt-util :as hu]
   [eve.slab-io :as slab]))

(def ^:const NIL -1)

(defn u32 [off] (bit-and off 0xFFFFFFFF))

;; ---------------------------------------------------------------------------
;; A block's pointers, by kind: [[position kind offset] …] in position order
;; ---------------------------------------------------------------------------

(defn- pointer-tag?
  "A tag whose EVE bytes are a pointer to a value of its own: a collection (0x10-0x13), an eve array (0x1C, 0x1D),
   an obj (0x1E), an eve value array (0x1F), an eve-deftype (0x40-0xEC)."
  [tag]
  (or (<= 0x10 tag 0x13) (<= 0x1C tag 0x1F) (<= 0x40 tag 0xEC)))

(defn- inline-pointer
  "The block the 7 EVE bytes at byte p of the block at off point at, or nil when they are no pointer: their first
   three bytes [0xEE 0xDB tag], read as one little-endian word."
  [sio off p]
  (let [w (slab/read-i32 sio off p)]
    (when (and (== 0xDBEE (bit-and w 0xFFFF))
               (pointer-tag? (bit-and (bit-shift-right w 16) 0xFF)))
      (slab/read-i32 sio off (+ p 3)))))

(defn- bit-pos
  "The index (0-31) of the one set bit of b."
  [b]
  (hu/popcount32 (dec b)))

(defn- hamt-children
  "A HAMT node's pointers: its child nodes by hash-slot bit (positions 0-31), and for a map the values among its
   entries that point at a value (32 + bit; a collision node's, 64 + entry). A set's elements are keys: flat."
  [sio off map?]
  (let [t (slab/read-u8 sio off 0)]
    (cond
      (== t 1)
      (let [data-bm (u32 (slab/read-i32 sio off 4))
            node-bm (u32 (slab/read-i32 sio off 8))
            nc (hu/popcount32 node-bm)
            ;; a child slot can hold NIL under its bit (the walk before this one skipped it too)
            kids (loop [r node-bm i 0 acc []]
                   (if (zero? r)
                     acc
                     (let [c (slab/read-i32 sio off (+ 12 (* 4 i)))]
                       (recur (bit-and r (dec r)) (inc i)
                              (if (== c NIL)
                                acc
                                (conj acc [(bit-pos (bit-and r (- r))) (if map? :map-node :set-node) c]))))))]
        (if map?
          (loop [r data-bm pos (+ 12 (* 4 nc) (* 4 (hu/popcount32 data-bm))) acc kids]
            (if (zero? r)
              acc
              (let [klen (slab/read-i32 sio off pos)
                    vpos (+ pos 4 klen)
                    vlen (slab/read-i32 sio off vpos)
                    p (when (== vlen 7) (inline-pointer sio off (+ vpos 4)))]
                (recur (bit-and r (dec r)) (+ vpos 4 vlen)
                       (if p (conj acc [(+ 32 (bit-pos (bit-and r (- r)))) :val p]) acc)))))
          kids))

      (and map? (== t 3))
      (let [cnt (slab/read-u8 sio off 1)]
        (loop [i 0 pos 8 acc []]
          (if (== i cnt)
            acc
            (let [klen (slab/read-i32 sio off pos)
                  vpos (+ pos 4 klen)
                  vlen (slab/read-i32 sio off vpos)
                  p (when (== vlen 7) (inline-pointer sio off (+ vpos 4)))]
              (recur (inc i) (+ vpos 4 vlen) (if p (conj acc [(+ 64 i) :val p]) acc))))))

      :else [])))

(defn- trie-live
  "How many slots of a trie node at shift sh covering elements from base hold live entries, of toff in the trie."
  [toff base sh]
  (let [n (- toff base)]
    (if (<= n 0)
      0
      (let [w (bit-shift-left 1 sh)]
        (min 32 (quot (+ n (dec w)) w))))))

(defn- slots
  "Positions 0..n-1 of a 32-slot node at off, each not NIL as kind (f i)."
  [sio off n f]
  (loop [i 0 acc []]
    (if (== i n)
      acc
      (let [c (slab/read-i32 sio off (* 4 i))]
        (recur (inc i) (if (== c NIL) acc (conj acc [i (f i) c])))))))

(defn- vec-header-children
  "A vector header's trie (what its count says it holds — never a slot past it) and tail."
  [sio off]
  (let [cnt (slab/read-i32 sio off 4)
        sh (slab/read-i32 sio off 8)
        root (slab/read-i32 sio off 12)
        tail (slab/read-i32 sio off 16)
        tail-len (slab/read-i32 sio off 20)
        toff (if (< cnt 32) 0 (bit-shift-left (bit-shift-right (dec cnt) 5) 5))]
    (cond-> []
      (and (pos? toff) (not= root NIL)) (conj [0 [:vec sh 0 toff] root])
      (not= tail NIL) (conj [1 [:leaf tail-len] tail]))))

(defn- deftype-children
  "An eve-deftype instance's pointers by its registered layout: a :ref field's block, a :value field's value block."
  [sio off t]
  (let [layout (ser/deftype-layout sio off t)]
    (if (vector? layout)
      (reduce (fn [acc [foff k]]
                (let [r (slab/read-i32 sio off foff)]
                  (if (== r NIL) acc (conj acc [foff (if (= k :ref) :val :vblock) r]))))
              []
              layout)
      [])))

(defn- varr-children
  "An eve value array's slots that point at a value (kind 2) or a value block (kind 4)."
  [sio off]
  (let [n (slab/read-i32 sio off 4)
        pb (bit-and (+ 8 (* 2 n) 3) (bit-not 3))]
    (loop [i 0 acc []]
      (if (== i n)
        acc
        (let [k (slab/read-u8 sio off (+ 8 i))]
          (recur (inc i)
                 (cond
                   (== k 2) (conj acc [i :val (slab/read-i32 sio off (+ pb (* 4 i)))])
                   (== k 4) (conj acc [i :vblock (slab/read-i32 sio off (+ pb (* 4 i)))])
                   :else acc)))))))

(defn- val-children
  "A value's header's pointers, by its type-id byte."
  [sio off]
  (let [t (slab/read-u8 sio off 0)]
    (case (int t)
      0xED (let [r (slab/read-i32 sio off 8)] (if (== r NIL) [] [[0 :map-node r]]))
      0xEE (let [r (slab/read-i32 sio off 8)] (if (== r NIL) [] [[0 :set-node r]]))
      0x12 (vec-header-children sio off)
      0x13 (let [h (slab/read-i32 sio off 8)] (if (== h NIL) [] [[0 :cell h]]))
      0x01 []
      0x1D []
      0x1E []
      0x1F (varr-children sio off)
      (deftype-children sio off t))))

(defn children
  "The pointers the block at off, of kind, holds: [[position kind offset] …], positions ascending."
  [sio kind off]
  (if (keyword? kind)
    (case kind
      :val (val-children sio off)
      :map-node (hamt-children sio off true)
      :set-node (hamt-children sio off false)
      :vblock (if-let [p (when (== 7 (slab/read-i32 sio off 0)) (inline-pointer sio off 4))] [[0 :val p]] [])
      :cell (let [nx (slab/read-i32 sio off 0)
                  p (when (== 7 (slab/read-i32 sio off 4)) (inline-pointer sio off 8))]
              (cond-> [] (not= nx NIL) (conj [0 :cell nx]) p (conj [1 :val p])))
      [])
    (case (nth kind 0)
      :vec (let [[_ sh base toff] kind
                 w (bit-shift-left 1 sh)]
             (slots sio off (trie-live toff base sh)
                    (fn [i] (if (zero? sh) :vblock [:vec (- sh 5) (+ base (* i w)) toff]))))
      :leaf (slots sio off (nth kind 1) (constantly :vblock))
      [])))

(defn reach
  "Every block the value at ptr holds, each once (eve.atom/seal)."
  [sio ptr]
  (if (== ptr NIL)
    []
    (loop [stack [[:val ptr]] seen #{} out []]
      (if (empty? stack)
        out
        (let [[k o] (peek stack)
              stack (pop stack)
              key (u32 o)]
          (if (contains? seen key)
            (recur stack seen out)
            (recur (into stack (map (fn [[_ ck c]] [ck c])) (children sio k o))
                   (conj seen key)
                   (conj out o))))))))
