(ns eve.jvm-atom-retire-test
  "What a committed write releases, for values that hold eve-deftypes (2026-10-21
   ET; the counts on the blocks' descriptor rows, eve.tally). A tree — an int-map, a sorted collection, the
   red-black set — replaced at the root or at a map's key releases what it dropped (it released its header only:
   every node stayed allocated), a collection one of its values points at the same way, and a cons cell whose tail
   is the value it replaced keeps that value (the old root block was freed: a list kept at an atom root broke once
   its blocks were reused). Each commit simulated as eve.atom runs it — the slot holds the new root, lets the old
   one go, the zero queue flushes — and checked against the two values' reach: never a block the new value holds,
   every block the old one held that the new does not, and for a value whose every piece has one holder every
   surviving block counted once. Each reach checked against the allocation log of writing the value.
   (The 9.31 pair-walk this file timed before the descriptor count landed is gone with eve.retire's plan: the
   protocol's hold prunes at counted blocks by construction, so there is no walk left to time.)"
  (:require [clojure.test :refer [deftest is testing]]
            [eve.alpha]
            [eve.atom]
            [eve.core :as e]
            [eve.deftype :refer [eve-deftype]]
            [eve.deftype.int-map :as im]
            [eve.deftype.rb-tree :as rb]
            [eve.sorted :as so]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.retire :as retire]
            [eve.tally :as tally]))

(defprotocol ICell (-hd [c]) (-tl [c]))

(eve-deftype RetireCell [head ^:RetireCell tail]
             ICell
             (-hd [_] head)
             (-tl [_] tail))

(defn- items [c] (loop [c c out []] (if (nil? c) out (recur (-tl c) (conj out (-hd c))))))

;; a user's binary search tree: a path-copying insert, its nodes eve-deftypes
(defprotocol INode (-k [n]) (-l [n]) (-r [n]))

(eve-deftype RetireNode [k ^:RetireNode left ^:RetireNode right]
             INode
             (-k [_] k)
             (-l [_] left)
             (-r [_] right))

(defn- bst-insert [t x]
  (cond (nil? t) (->RetireNode x nil nil)
        (< x (-k t)) (->RetireNode (-k t) (bst-insert (-l t) x) (-r t))
        (> x (-k t)) (->RetireNode (-k t) (-l t) (bst-insert (-r t) x))
        :else t))

(defn- bst-of
  "A balanced tree of sorted keys ks (a vector), each node built once."
  [ks]
  (when (seq ks)
    (let [m (quot (count ks) 2)]
      (->RetireNode (nth ks m) (bst-of (subvec ks 0 m)) (bst-of (subvec ks (inc m)))))))

;; a record whose fields are values: a map, an int-map
(eve-deftype RetireBox [label data])

(def ^:private resolve-new-ptr #'eve.atom/resolve-new-ptr)
(def ^:private read-root-value #'eve.atom/read-root-value)

(defn- block [off] [(alloc/decode-class-idx off) (alloc/decode-block-idx off)])

(defn- root
  "[ptr value]: v written as an atom root in sio (a plain collection by the codec's writers), read back."
  [sio v]
  (let [p (resolve-new-ptr sio v)]
    [p (read-root-value sio p)]))

(defn- reach [sio p] (retire/reach sio p))

(defn- check
  "old replaced by new at an atom root of sio (the old one made by (mk-old), the new by (mk-new old)), the commit
   run as eve.atom runs it: the slot holds the new root, lets the old one go, the zero queue flushes with no
   reader pinned. Answers the freed blocks, as [class block] pairs."
  [label sio mk-old mk-new]
  (let [q (atom [])
        park! (fn [off kind] (swap! q conj {:off off :kind kind :epoch 0}))
        [op _] (binding [d/*domain-sio* sio] (root sio (mk-old)))
        _ (tally/hold-root! sio op)
        r0o (reach sio op)
        lsio (alloc/logging-sio sio)
        [np _] (binding [d/*domain-sio* lsio] (root lsio (mk-new (read-root-value lsio op))))
        r1o (reach sio np)
        r0 (set (map block r0o))
        r1 (set (map block r1o))]
    (tally/hold-root! sio np)
    (tally/drop-root! sio op park!)
    (tally/flush! sio q (fn [] 1) (fn [] nil))
    (let [freed (set (map block (filter #(not (alloc/allocated? sio %)) r0o)))]
      (is (empty? (filter r1 freed)) (str label ": never a block the new value holds"))
      (is (= freed (set (remove r1 r0))) (str label ": every block the old value held that the new does not"))
      (is (every? #(alloc/allocated? sio %) r1o)
          (str label ": every block the new value holds stays allocated"))
      (is (every? #(== 1 (tally/count-of sio %)) r1o)
          (str label ": every piece has one holder — each counted once"))
      freed)))

(defn- written-reach
  "The value (mk) written as a root: its reach, and what writing it allocated."
  [mk]
  (let [sio (alloc/heap-domain-sio! {})
        lsio (alloc/logging-sio sio)
        [p v] (binding [d/*domain-sio* lsio] (root lsio (mk)))]
    [(set (map block (reach sio p))) (set (map block (alloc/logged-allocs lsio)))]))

(def ^:private kv (interleave (range 200) (range 200)))

(deftest a-value-reach-is-what-writing-it-allocated
  (doseq [[label mk] [["an int-map" #(apply im/int-map kv)]
                      ["a sorted set" #(apply so/sorted-set (range 200))]
                      ["a red-black set" #(apply rb/sorted-set (range 200))]
                      ["a cons list" #(reduce (fn [c i] (->RetireCell i c)) nil (range 50))]
                      ["a map of each" (fn [] {:m (apply im/int-map kv) :s (apply so/sorted-set (range 50))
                                               :l (reduce (fn [c i] (->RetireCell i c)) nil (range 10)) :n 1})]
                      ["an int-map of maps" #(apply im/int-map (mapcat (fn [k] [k {:k k :v [k]}]) (range 50)))]]]
    (let [[reach allocs] (written-reach mk)]
      (is (= allocs reach) (str label ": its reach is every block writing it allocated")))))

(deftest a-tree-retires-by-its-own-walk
  (let [sio (alloc/heap-domain-sio! {})]
    (check "an int-map at the root, one assoc" sio #(apply im/int-map kv) #(e/assoc % 7 :x))
    (check "an int-map at the root, rebuilt" sio #(apply im/int-map kv) (fn [_] (apply im/int-map (range 40))))
    (check "an int-map at a key, one assoc" sio (fn [] {:m (apply im/int-map kv) :x 1})
           #(e/assoc % :m (e/assoc (e/get % :m) 7 :x)))
    (check "an int-map at a key, rebuilt" sio (fn [] {:m (apply im/int-map kv)}) (fn [_] {:m (apply im/int-map kv)}))
    (check "an int-map dropped from a map" sio (fn [] {:m (apply im/int-map kv) :x 1}) (fn [_] {:x 2}))
    (check "an int-map of maps, one value replaced" sio
           #(apply im/int-map (mapcat (fn [k] [k {:k k}]) (range 50)))
           #(e/assoc % 3 {:k :new}))
    (check "a sorted set at a key" sio (fn [] {:s (apply so/sorted-set (range 200))})
           #(e/assoc % :s (e/conj (e/get % :s) 1000)))
    (check "a sorted map at the root" sio #(apply so/sorted-map kv) #(e/dissoc % 50))
    (check "a red-black set at the root" sio #(apply rb/sorted-set (range 200)) #(e/disj (e/conj % 1000) 3))
    (is (seq (check "a tree replaced by a map" sio #(apply im/int-map kv) (fn [_] {:a 1})))
        "the tree's blocks are retired")))

(deftest a-list-retires-only-the-cells-it-drops
  (let [sio (alloc/heap-domain-sio! {})]
    (is (= 1 (count (check "a list at the root, one conj" sio #(apply list (range 30)) #(e/conj % :x))))
        "the old header only: the new list holds every old cell")
    (is (= 2 (count (check "a list at the root, one pop" sio #(apply list (range 30)) e/pop)))
        "the old header and the popped cell")
    (check "a list at a key, one conj" sio (fn [] {:l (apply list (range 30))}) #(e/assoc % :l (e/conj (e/get % :l) :x)))))

(deftest a-plain-eve-deftype-keeps-what-the-new-value-holds
  (let [sio (alloc/heap-domain-sio! {})]
    (is (empty? (check "a cell over the old list" sio #(reduce (fn [c i] (->RetireCell i c)) nil (range 30))
                       #(->RetireCell :new %)))
        "the old list is the new one's tail: nothing retired")
    (is (= 2 (count (check "the list's tail" sio #(reduce (fn [c i] (->RetireCell i c)) nil (range 30)) -tl)))
        "the head cell and its value's block retired, the tail kept")
    (check "a list at a key, grown" sio (fn [] {:l (reduce (fn [c i] (->RetireCell i c)) nil (range 30))})
           #(e/assoc % :l (->RetireCell :new (e/get % :l))))
    (is (empty? (check "three cells over the old list" sio #(reduce (fn [c i] (->RetireCell i c)) nil (range 30))
                       #(->RetireCell :c (->RetireCell :b (->RetireCell :a %)))))
        "pushed three deep: nothing retired")
    (is (= 6 (count (check "three cells popped" sio #(reduce (fn [c i] (->RetireCell i c)) nil (range 30))
                           #(-tl (-tl (-tl %))))))
        "three cells and their values' blocks retired")
    (let [mk #(bst-of (vec (range 200)))]
      (check "a tree's insert of a key it holds (the path copied)" sio mk #(bst-insert % 50))
      (doseq [x [-1 1000 77.5 150.5]]
        (check (str "a tree's insert of " x) sio mk #(bst-insert % x)))
      (check "a tree's subtree as the value" sio mk -l)
      (check "a tree rebuilt" sio mk (fn [_] (bst-of (vec (range 50))))))
    (check "a box's map replaced" sio #(->RetireBox "b" {:a [1 2 3]}) (fn [_] (->RetireBox "b" {:b 2})))
    (check "a box's int-map grown" sio #(->RetireBox "b" (apply im/int-map kv)) #(->RetireBox "c" (e/assoc (.-data %) 7 :x)))
    (check "a box replaced by a map" sio #(->RetireBox "b" {:a 1}) (fn [_] {:z 1}))))

(deftest a-list-at-an-atom-root-survives-its-blocks-reuse
  ;; the old root block was retired with the new cell's tail pointing at it: once the allocator reused it, the list
  ;; read back as other values' bytes ("EVE bytes truncated")
  (let [base (str (System/getProperty "java.io.tmpdir") "/eve-retire-cell-" (System/nanoTime) "/db")
        x (eve.atom/persistent-atom {:id ::cells :persistent base} nil)
        churn (eve.atom/persistent-atom {:id ::churn :persistent base} {:n 0})]
    (dotimes [i 300]
      (swap! x (fn [c] (->RetireCell i c)))
      (when (zero? (mod i 50)) (Thread/sleep 60)))
    (dotimes [i 6000]
      (swap! churn (fn [m] (assoc m :n i :s (str "s" i))))
      (when (zero? (mod i 500)) (Thread/sleep 60)))
    (is (= (vec (reverse (range 300))) (eve.atom/read-pinned x items)) "the list reads back whole")
    (eve.atom/close-atom-domain! (eve.atom/persistent-atom-domain base))))

(deftest an-eve-list-at-an-atom-root-survives-its-blocks-reuse
  ;; eve.list's own walk retired every old cell on a conj: the same breakage, for eve's list
  (let [base (str (System/getProperty "java.io.tmpdir") "/eve-retire-list-" (System/nanoTime) "/db")
        x (eve.atom/persistent-atom {:id ::list :persistent base} '())
        churn (eve.atom/persistent-atom {:id ::churn :persistent base} {:n 0})]
    (dotimes [i 300]
      (swap! x conj i)
      (when (zero? (mod i 50)) (Thread/sleep 60)))
    (dotimes [i 6000]
      (swap! churn (fn [m] (assoc m :n i :s (str "s" i))))
      (when (zero? (mod i 500)) (Thread/sleep 60)))
    (is (= (reverse (range 300)) (eve.atom/read-pinned x (fn [v] (doall (e/seq v))))) "the list reads back whole")
    (eve.atom/close-atom-domain! (eve.atom/persistent-atom-domain base))))

(defn- held-elsewhere
  "9.31's forms on a disk domain: (place m) stores what the value holds at :a (or something made from it) at :b,
   then :a is replaced, the domain's released blocks reused by another atom's writes, and :b read back."
  [label a0 place expect-b]
  (let [base (str (System/getProperty "java.io.tmpdir") "/eve-tally-" (System/nanoTime) "/db")
        a (eve.atom/persistent-atom {:id (keyword "tally" label) :persistent base} {:a a0 :n 0})
        churn (eve.atom/persistent-atom {:id ::tally-churn :persistent base} {:n 0})]
    (swap! a (fn [m] (e/assoc m :b (place m))))
    (swap! a (fn [m] (e/assoc m :a {:y 1})))
    (Thread/sleep 80) (swap! a update :n inc) (Thread/sleep 80) (swap! a update :n inc)
    (dotimes [i 6000]
      (swap! churn (fn [m] (assoc m :n i :s (str "s" i) :v [i i i])))
      (when (zero? (mod i 500)) (Thread/sleep 60)))
    (is (= expect-b (:b @a)) (str label ": the place that still holds it reads it back whole"))
    (eve.atom/close-atom-domain! (eve.atom/persistent-atom-domain base))))

(deftest a-piece-held-at-two-places-survives-its-blocks-reuse
  ;; 9.31: the walk is single-pair, so before the tally the second place read freed blocks once they were reused
  ;; ("Index ... out of bounds")
  (held-elsewhere "same" {:x (vec (range 50))} #(e/get % :a) {:x (vec (range 50))})
  (held-elsewhere "made-from" {:x (vec (range 50))} #(e/assoc (e/get % :a) :z 1) {:x (vec (range 50)) :z 1})
  (let [big (into {} (map (fn [k] [k (vec (range 8))])) (range 200))]
    (held-elsewhere "interior" big #(e/assoc (e/get % :a) :z 1) (assoc big :z 1)))
  (held-elsewhere "transient" {:x (vec (range 50))}
                  (fn [m] (let [t (e/transient (eve.alpha/hash-map))
                                t (e/assoc! t :v {:x (vec (range 50))})
                                t (e/assoc! t :w (e/get t :v))
                                t (e/assoc! t :v 1)]
                            (e/get (e/persistent! t) :w)))
                  {:x (vec (range 50))}))

(deftest a-piece-of-one-atom-held-by-another-survives-its-blocks-reuse
  (let [base (str (System/getProperty "java.io.tmpdir") "/eve-tally-x-" (System/nanoTime) "/db")
        x (eve.atom/persistent-atom {:id ::tally-x :persistent base} {:a {:x (vec (range 50))}})
        y (eve.atom/persistent-atom {:id ::tally-y :persistent base} {})
        churn (eve.atom/persistent-atom {:id ::tally-churn2 :persistent base} {:n 0})]
    (swap! y (fn [m] (e/assoc m :k (eve.atom/read-pinned x (fn [v] (e/get v :a))))))
    (swap! x (fn [m] (e/assoc m :a 0)))
    (Thread/sleep 80) (swap! x assoc :n 1) (Thread/sleep 80) (swap! x assoc :n 2)
    (dotimes [i 6000]
      (swap! churn (fn [m] (assoc m :n i :s (str "s" i) :v [i i i])))
      (when (zero? (mod i 500)) (Thread/sleep 60)))
    (is (= {:k {:x (vec (range 50))}} @y))
    (eve.atom/close-atom-domain! (eve.atom/persistent-atom-domain base))))

(deftest threads-racing-on-a-shared-piece
  ;; writers on two atoms of one domain each store and drop a piece the other holds: the counts end where the places
  ;; do, and every value reads back
  (let [dom (eve.atom/atom-domain nil)
        x (eve.atom/lookup-or-create-mmap-atom! dom "x" {:p {:v (vec (range 20))}})
        y (eve.atom/lookup-or-create-mmap-atom! dom "y" {})
        n 200
        fs (doall (for [t (range 4)]
                    (future
                      (dotimes [i n]
                        (if (even? t)
                          (swap! y (fn [m] (e/assoc m (keyword (str "k" (mod i 5))) (eve.atom/read-pinned x (fn [v] (e/get v :p))))))
                          (swap! x (fn [m] (e/assoc m :p {:v (vec (range (+ 20 (mod i 3))))}))))))))]
    (run! deref fs)
    (is (every? (fn [[_ v]] (and (map? v) (vector? (:v v)))) (dissoc @y)) "every piece y holds reads back")
    (is (map? (:p @x)))))
