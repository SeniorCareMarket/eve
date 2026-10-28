(ns eve.tally-test
  "How many use a block (eve.tally, on each block's descriptor row): a value two atoms of one domain hold is freed only
   when the last of them lets it go. Before the count, a swap! freed what its old value had and its new value lacked,
   whoever else held it: atom b stored atom a's map (through `$`, which hands out a's value without copying it), a let
   go of it, and a's next flush freed the map under b — every block of it back in the allocator while b's slot still
   named it (vs/docs/reclaim/atom-domains.md §1, T5; vs/spike/atom-domain/gaps.py G1). The same for a value moved
   between keys of one atom (G2), and an unshared atom's writes still free exactly what they replace."
  (:refer-clojure :exclude [swap! reset!])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap! reset!]]
            [eve.atom :as a]
            [eve.retire :as retire]
            [eve.tally :as tally]
            [eve.deftype #?@(:cljs [:refer-macros [eve-deftype]] :default [:refer [eve-deftype]])]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype.int-map :as im]
            [eve.rrb-vector :as fv]
            [eve.core :as e]
            [eve.slab-io :as slab]))

(defn- ds-of [x] (a/-domain-state x))
(defn- flush! [x] (#'a/flush-zero-queue! (ds-of x)))

(defn- root-ptr [x] (second (a/version x)))

(defn- reach
  "Every block the atom's value owns (eve.atom's reach reader, the one seal digests)."
  [x]
  (let [{:keys [sio]} (ds-of x)
        p (root-ptr x)]
    (set (retire/reach sio p))))

(defn- all-allocated? [x offs] (every? #(alloc/allocated? (:sio (ds-of x)) %) offs))
(defn- none-allocated? [x offs] (not-any? #(alloc/allocated? (:sio (ds-of x)) %) offs))

(defn- value-of [i]
  {:i i :s (str "value-" i) :m {:k i :v [i (inc i) (+ i 2)]} :l (list i :x "y") :e #{i (str i)}})

(defn- big [n] (into {} (map (fn [i] [(keyword (str "k" i)) (value-of i)])) (range n)))

(defn- churn!
  "Writes enough into atom d to hand freed blocks out again."
  [d n]
  (dotimes [i n]
    (reset! d (big (+ 40 (mod i 7))))
    (flush! d)))

(deftest a-value-two-atoms-hold-is-freed-by-the-last
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "t/a" nil)
        y (a/lookup-or-create-mmap-atom! dom "t/b" nil)
        z (a/lookup-or-create-mmap-atom! dom "t/c" nil)
        d (a/lookup-or-create-mmap-atom! dom "t/d" nil)
        v (big 120)
        sio (:sio (ds-of x))]
    (reset! x v)
    (let [shared (root-ptr x)
          blocks (reach x)]
      (testing "b's slot holds a's map itself, c's map holds it as a value: no copy"
        (a/read-pinned x (fn [m] (swap! y (constantly m)) nil))
        (a/read-pinned x (fn [m] (swap! z assoc :x m) nil))
        (is (== shared (root-ptr y)))
        (is (= 3 (tally/count-of sio shared)) "a's slot, b's slot, c's map"))
      (testing "a lets go: the map is b's and c's, whole"
        (reset! x nil)
        (flush! x)
        (is (= 2 (tally/count-of sio shared)))
        (is (all-allocated? x blocks) "no block of the map is free")
        (churn! d 30)
        (is (= v @y) "b's map reads back after the allocator handed blocks out again")
        (is (= {:x v} @z)))
      (testing "b lets go: c still holds it"
        (reset! y nil)
        (flush! y)
        (churn! d 30)
        (is (all-allocated? x blocks))
        (is (= {:x v} @z)))
      (testing "c lets go: every block of the map is freed"
        (reset! z nil)
        (flush! z)
        (is (none-allocated? x blocks))))))

(deftest a-value-moved-between-keys-of-one-atom-stays
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "t/a" nil)
        d (a/lookup-or-create-mmap-atom! dom "t/d" nil)]
    (reset! x {:x (big 60) :n 1})
    (swap! x (fn [m] (-> m (assoc :y (get m :x)) (dissoc :x))))
    (flush! x)
    (churn! d 30)
    (is (= {:y (big 60) :n 1} @x))
    (is (all-allocated? x (reach x)))))

(deftest an-unshared-atom-frees-exactly-what-a-write-replaces
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "t/a" nil)]
    (reset! x (big 400))
    (flush! x)
    (dotimes [i 60]
      (let [before (reach x)]
        (swap! x assoc (keyword (str "k" (mod (* 7 i) 400))) (value-of (+ 1000 i)))
        (flush! x)
        (let [after (reach x)]
          (is (all-allocated? x after) (str "write " i ": the new value is whole"))
          (is (none-allocated? x (remove after before)) (str "write " i ": what it replaced is freed")))))
    (is (= 400 (count @x)))))

;; ---------------------------------------------------------------------------
;; eve-deftype instances: the count reads the fields each type registers (a ^:Box reference, a serialized value)
;; and keeps a field written in place by the store rule
;; ---------------------------------------------------------------------------

(defprotocol IBox (box-label [b]))

(eve-deftype Box [label]
  IBox
  (box-label [_] label))

(defprotocol IHolder
  (the-box [h])
  (the-note [h])
  (put-box! [h b])
  (put-note! [h n]))

(eve-deftype Holder [^:mutable ^:Box box ^:volatile-mutable note]
  IHolder
  (the-box [_] box)
  (the-note [_] note)
  (put-box! [this b] (set! box b) this)
  (put-note! [this n] (set! note n) this))

(defn- offs
  "[holder box note-block] offsets of the Holder at :h of the atom's map, read under a pin."
  [x]
  (a/read-pinned x (fn [m]
           (let [h (get m :h) sio (d/-eve-sio h) ho (d/-root-header-off h)]
             [ho
              (slab/read-i32 sio ho 4)
              (slab/read-i32 sio ho 8)]))))

(defn- read-holder [x] (a/read-pinned x (fn [m] (let [h (get m :h)] [(box-label (the-box h)) (the-note h)]))))

(deftest an-eve-deftype-value-two-atoms-hold
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "t/a" nil)
        y (a/lookup-or-create-mmap-atom! dom "t/b" nil)
        d (a/lookup-or-create-mmap-atom! dom "t/d" nil)
        sio (:sio (ds-of x))]
    (swap! x (fn [_] {:h (->Holder (->Box (str "b" 1)) {:note [1 2 3]})}))
    (let [[ho bo no] (offs x)]
      (is (= 1 (tally/count-of sio ho)) "the holder: the map's node")
      (is (= 1 (tally/count-of sio bo)) "the box: the holder's ^:Box field")
      (is (= 1 (tally/count-of sio no)) "the note's block: the holder's serialized field")
      (a/read-pinned x (fn [m] (swap! y (constantly m)) nil))
      (reset! x nil)
      (flush! x)
      (churn! d 20)
      (is (= ["b1" {:note [1 2 3]}] (read-holder y)) "b holds the map, and through it the holder, its box and note")
      (is (every? #(alloc/allocated? sio %) [ho bo no]))
      (reset! y nil)
      (flush! y)
      (is (not-any? #(alloc/allocated? sio %) [ho bo no]) "the last holder let go: the holder, its box and note freed"))))

(deftest a-reference-field-written-in-place-keeps-its-counts
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "t/a" nil)
        sio (:sio (ds-of x))]
    (swap! x (fn [_] {:h (->Holder (->Box "old") "first")}))
    (let [[ho bo1 no1] (offs x)]
      (testing "set! of the ^:Box field of a counted holder: the new box held, the old one let go"
        (swap! x (fn [m] (put-box! (get m :h) (->Box "new")) m))
        (flush! x)
        (let [[_ bo2 _] (offs x)]
          (is (= ["new" "first"] (read-holder x)))
          (is (= 1 (tally/count-of sio bo2)))
          (is (alloc/allocated? sio bo2))
          (is (not (alloc/allocated? sio bo1)) "the box it replaced is freed")))
      (testing "set! of the serialized field: the new block held, the old one let go"
        (swap! x (fn [m] (put-note! (get m :h) "second") m))
        (flush! x)
        (let [[_ _ no2] (offs x)]
          (is (= ["new" "second"] (read-holder x)))
          (is (= 1 (tally/count-of sio no2)))
          (is (not (alloc/allocated? sio no1)) "the note block it replaced is freed")))
      (testing "the holder let go: everything it references is freed with it"
        (let [[_ bo no] (offs x)]
          (reset! x nil)
          (flush! x)
          (is (not-any? #(alloc/allocated? sio %) [ho bo no])))))))

;; ---------------------------------------------------------------------------
;; An int-map: its nodes keep their children in ^:int32 ^:ref fields (any node type), so the count follows them
;; ---------------------------------------------------------------------------

(defn- tally-reach
  "Every block the atom's value reaches, by the count's own reader (eve.tally's references, kind by kind)."
  [x]
  (let [sio (:sio (ds-of x))
        refs @#'tally/refs
        p (root-ptr x)]
    (if (or (== p -1) (== p -2))
      #{}
      (loop [work [[p (tally/root-kind sio p)]] seen #{}]
        (if (empty? work)
          seen
          (let [[o k] (peek work) work (pop work)]
            (if (contains? seen o)
              (recur work seen)
              (recur (into work (refs sio o k)) (conj seen o)))))))))

(deftest an-int-map-is-counted-node-by-node
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "t/a" nil)
        y (a/lookup-or-create-mmap-atom! dom "t/b" nil)
        d (a/lookup-or-create-mmap-atom! dom "t/d" nil)
        sio (:sio (ds-of x))
        get-m (fn [at k] (a/read-pinned at (fn [m] (e/get (get m :m) k))))]
    (swap! x (fn [_] {:m (reduce (fn [m i] (e/assoc m i (str "v" i))) (im/int-map) (range 300))}))
    (flush! x)
    (let [blocks (tally-reach x)]
      (is (> (count blocks) 300) "the reader reaches the int-map's nodes (branches, leaves, their values)")
      (testing "b holds a's map; a lets go; the int-map is whole"
        (a/read-pinned x (fn [m] (swap! y (constantly m)) nil))
        (reset! x nil)
        (flush! x)
        (churn! d 20)
        (is (= "v17" (get-m y 17)))
        (is (all-allocated? y blocks)))
      (testing "each write frees exactly the nodes it replaced"
        (dotimes [i 20]
          (let [before (tally-reach y)]
            (swap! y update :m e/assoc (* 7 i) (str "w" i))
            (flush! y)
            (let [after (tally-reach y)]
              (is (all-allocated? y after))
              (is (none-allocated? y (remove after before)) (str "write " i)))))
        (is (= "w3" (get-m y 21))))
      (testing "the last holder lets go: every node freed"
        (let [last-blocks (tally-reach y)]
          (reset! y nil)
          (flush! y)
          (is (none-allocated? y last-blocks)))))))

;; ---------------------------------------------------------------------------
;; An RRB vector: its nodes keep their children in eve value arrays (eve.deftype.array, 0x1F), which the count reads
;; slot by slot
;; ---------------------------------------------------------------------------

(deftest an-rrb-vector-is-counted-node-by-node
  (let [dom (a/atom-domain nil)
        x (a/lookup-or-create-mmap-atom! dom "t/a" nil)
        y (a/lookup-or-create-mmap-atom! dom "t/b" nil)
        d (a/lookup-or-create-mmap-atom! dom "t/d" nil)
        get-v (fn [at i] (a/read-pinned at (fn [m] (e/nth (get m :v) i))))]
    (swap! x (fn [_] {:v (fv/vec (range 2000))}))
    (flush! x)
    (let [blocks (tally-reach x)]
      (is (> (count blocks) 60) "the reader reaches the vector's nodes and their arrays")
      (testing "b holds a's vector; a lets go; the vector is whole"
        (a/read-pinned x (fn [m] (swap! y (constantly m)) nil))
        (reset! x nil)
        (flush! x)
        (churn! d 20)
        (is (= 1234 (get-v y 1234)))
        (is (all-allocated? y blocks)))
      (testing "each write frees exactly the blocks it replaced"
        (dotimes [i 10]
          (let [before (tally-reach y)]
            (swap! y update :v e/assoc (* 97 i) (- i))
            (flush! y)
            (let [after (tally-reach y)]
              (is (all-allocated? y after))
              (is (none-allocated? y (remove after before)) (str "write " i)))))
        (is (= -3 (get-v y 291))))
      (testing "the last holder lets go: every block freed"
        (let [last-blocks (tally-reach y)]
          (reset! y nil)
          (flush! y)
          (is (none-allocated? y last-blocks)))))))
