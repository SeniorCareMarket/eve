(ns eve.flat.tally-count-test
  "Lane Z4 (IMPL-PLAN SS3.3): the count taken at the zip -- fork F5 spiked
   behind eve.tally/*count-at-place*. The commit's walk (tally/hold-root!)
   discovers each placed block's holders by reading the tree; the image's
   block and relocation tables already say who holds whom, so with the flag
   bound, hold-root! writes the counts from the tables instead. These tests
   hold the spike to the plan's bar: the written words equal the walked
   words, row for row, or the lane reports instead of approximating.

   The harness places a value's graph itself (Z3's place is not built yet):
   it reads the graph with the tally's own reference reader, allocates a
   same-class block per source block in the target domain, copies the class
   bytes and rewrites the pointers -- and then proves the placement faithful
   by reading the target's graph back with the same reader and comparing
   edge for edge. A placement that does not read back identical fails the
   harness, not the tally."
  (:refer-clojure :exclude [swap! reset! ->Box Box])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [swap! reset!]]
            [eve.atom :as a]
            [eve.tally :as tally]
            [eve.deftype :as edt]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.hamt-util :refer [popcount32]]
            [eve.map :as em]
            [eve.vec :as v]
            [eve.core :as e]
            [eve.slab-io :as slab])
  #?(:clj (:import [java.util Random])))

;; ---------------------------------------------------------------------------
;; The graph of a committed value, and a faithful placement of it
;; ---------------------------------------------------------------------------

(defn- graph-of
  "The value graph at root of sio, read by the tally's own reference reader:
   {:root off :order [off ...] :blocks {off kind} :edges [[owner target] ...]}.
   Every refs entry of every reached block is an edge, duplicates kept -- a
   block pointed at twice is held twice by the walk, so it is two edges here."
  [sio root]
  (let [refs @#'tally/refs]
    (loop [work [[root (tally/root-kind sio root)]] order [] blocks {} edges []]
      (if (empty? work)
        {:root root :order order :blocks blocks :edges edges}
        (let [[o k] (peek work) work (pop work)]
          (if (contains? blocks o)
            (recur work order blocks edges)
            (let [rs (refs sio o k)]
              (recur (into work rs)
                     (conj order o)
                     (assoc blocks o k)
                     (into edges (map (fn [[t _]] [o t]) rs))))))))))

(def ^:private class-slot-bytes [32 64 128 256 512 1024])

(defn- flat-element-bytes
  "The byte extent of the one flat element whose [0xEE 0xDB tag] header is
   at byte p of block off in sio, for the scalar tags whose layouts
   eve.deftype-proto.serialize documents (its FAST_TAG table and FORMAT-
   DECISIONS notes). Any other tag throws: the harness does not guess an
   extent."
  [sio off p]
  (when-not (and (== 0xEE (slab/read-u8 sio off p))
                 (== 0xDB (slab/read-u8 sio off (+ p 1))))
    (throw (ex-info "place-graph!: no flat element header at the expected byte"
                    {:off off :at p})))
  (let [tag (slab/read-u8 sio off (+ p 2))
        len-at (fn [q] (slab/read-i32 sio off q))]
    (case (int tag)
      (0x01 0x02) 3                                   ; false, true
      0x03 7                                          ; int32
      (0x04 0x0F) 11                                  ; float64, int64
      0x06 (+ 7 (len-at (+ p 3)))                     ; string, long form
      0x14 5                                          ; char
      0x15 11                                         ; reference (realm + handle)
      0x16 (+ 7 (len-at (+ p 3)))                     ; bigint
      0x17 (let [n (len-at (+ p 3))]                  ; ratio
             (+ 11 n (len-at (+ p 7 n))))
      0x18 (+ 11 (len-at (+ p 7)))                    ; bigdec (scale, then len)
      0x19 11                                         ; value block (block + len)
      0x1B (+ 8 (len-at (+ p 4)))                     ; typed array (subtype, then byte length)
      (throw (ex-info "place-graph!: a scalar tag with no documented extent is out of harness scope"
                      {:off off :tag tag})))))

(defn- block-bytes
  "How many bytes of the block at off in sio the harness copies: the class
   slot size for the fixed classes. An overflow-class block has no fixed
   size; the only overflow shape the harness places is a scalar block --
   [0x01][one flat element] -- whose extent its element declares (a long
   string at the root is one block of 8 + its length word, probe-measured
   at this pin). Any other overflow block throws, named, not guessed."
  [sio off]
  (let [c (alloc/decode-class-idx off)]
    (cond (< c 6) (nth class-slot-bytes c)
          (== c alloc/OVERFLOW_CLASS_IDX)
          (if (== 1 (slab/read-u8 sio off 0))
            (+ 1 (flat-element-bytes sio off 1))
            (throw (ex-info "place-graph!: a non-scalar overflow-class block is out of harness scope"
                            {:off off})))
          :else (throw (ex-info "place-graph!: a sentinel-class offset is not a block"
                                {:off off})))))

(def ^:private tally-tag-kind @#'tally/tag-kind)
(def ^:private tally-value-ref @#'tally/value-ref)
(def ^:private tally-type-layout @#'tally/type-layout)
(def ^:private tally-tail-offset @#'tally/tail-offset)
(def ^:private tally-live-slots @#'tally/live-slots)

(defn- live-word?
  "The word at p of block off in sio names a block (not NIL, not a sentinel)."
  [sio off p]
  (< (alloc/decode-class-idx (slab/read-i32 sio off p)) alloc/SENTINEL_CLASS_IDX))

(defn- kv-word-positions
  "The offset-word positions of the slab pointers among the n key/value
   pairs from pos of block off ([klen][key][vlen][value] each): the tally's
   kv-refs walk, positions kept."
  [sio off pos n]
  (loop [i 0 pos pos acc []]
    (if (>= i n)
      acc
      (let [klen (slab/read-i32 sio off pos)
            vpos (+ pos 4 klen)
            vlen (slab/read-i32 sio off vpos)]
        (recur (inc i) (+ vpos 4 vlen)
               (if (tally-value-ref sio off (+ vpos 4) vlen)
                 (conj acc (+ vpos 7))
                 acc))))))

(defn- slot-word-positions
  "The positions of the live slots 0..n-1 of block off (slot i's word is at
   4*i; a NIL slot names no block and is not a pointer)."
  [sio off n]
  (loop [i 0 acc []]
    (if (>= i n)
      acc
      (recur (inc i) (if (live-word? sio off (* 4 i)) (conj acc (* 4 i)) acc)))))

(defn- pointer-word-positions
  "The byte positions of block off's pointer words in sio: the words the
   tally's refs reads as this block's edges, by kind, positions kept (refs
   itself, re-walked -- the two must agree, and patch-block! enforces it
   against the graph's edge multiset). The digest-cache edge is not here:
   its pointer lives in the block's descriptor row, out of band (cache-word)."
  [sio off kind]
  (let [k (if (vector? kind) (nth kind 0) kind)
        word (fn [p] (slab/read-i32 sio off p))]
    (case k
      :map (if (live-word? sio off 8) [8] [])
      :set (if (live-word? sio off 8) [8] [])
      :list (if (live-word? sio off 8) [8] [])
      :hamt (let [t (slab/read-u8 sio off 0)]
              (cond (== t 1) (let [dc (popcount32 (word 4))
                                   nc (popcount32 (word 8))]
                               (into (mapv (fn [i] (+ 12 (* 4 i))) (range nc))
                                     (kv-word-positions sio off (+ 12 (* 4 nc) (* 4 dc)) dc)))
                    (== t 3) (kv-word-positions sio off 8 (slab/read-u8 sio off 1))
                    :else []))
      :shamt (if (== 1 (slab/read-u8 sio off 0))
               (mapv (fn [i] (+ 12 (* 4 i))) (range (popcount32 (word 8))))
               [])
      :vec (let [cnt (word 4) root (word 12) tail (word 16)]
             (cond-> []
               (and (pos? (tally-tail-offset cnt))
                    (< (alloc/decode-class-idx root) alloc/SENTINEL_CLASS_IDX)) (conj 12)
               (< (alloc/decode-class-idx tail) alloc/SENTINEL_CLASS_IDX) (conj 16)))
      :vnode (slot-word-positions sio off (tally-live-slots (nth kind 3) (nth kind 2) (nth kind 1)))
      :vleaf (slot-word-positions sio off (nth kind 1))
      :vval (if (tally-value-ref sio off 4 (word 0)) [7] [])
      :lnode (cond-> []
               (live-word? sio off 0) (conj 0)
               (tally-value-ref sio off 8 (word 4)) (conj 11))
      :varray (let [n (word 4)
                    base (bit-and (+ 8 (* 2 n) 3) (bit-not 3))]
                (loop [i 0 acc []]
                  (if (>= i n)
                    acc
                    (let [kd (slab/read-u8 sio off (+ 8 i))
                          p (word (+ base (* 4 i)))
                          hit (and (or (and (== kd 2)
                                            (tally-tag-kind (slab/read-u8 sio off (+ 8 n i))))
                                       (== kd 4))
                                   (< (alloc/decode-class-idx p) alloc/SENTINEL_CLASS_IDX))]
                      (recur (inc i) (if hit (conj acc (+ base (* 4 i))) acc))))))
      :deftype (into [] (keep (fn [[foff _]] (when (live-word? sio off foff) foff)))
                     (tally-type-layout sio off))
      [])))

(defn- cache-word
  "The offset of block off's digest-cache side block in sio, when its
   descriptor's POINTER column names a live, allocated block: the tally's
   cache+ edge. It lives out of band, in the row, not in the block's bytes."
  [sio off]
  (when-let [row (alloc/desc-of sio off)]
    (let [p (slab/region-load-i32 (nth (nth row 2) 0) (nth (nth row 2) 1))]
      (when (and (not (zero? p)) (not (== p alloc/NIL_OFFSET))
                 (< (alloc/decode-class-idx p) alloc/SENTINEL_CLASS_IDX)
                 (alloc/allocated? sio p))
        p))))

(defn- patch-block!
  "Rewrite tgt-off's copied pointers from source offsets to target offsets,
   at exactly the positions the tally's reader designates for the block's
   kind (pointer-word-positions, read off the source block). No scanning:
   a data word that merely equals a child offset is never mistaken for a
   pointer (an eve value array's element count can BE another block's
   offset -- 32, in the vector case; a scan patched the count and left the
   pointer stale, probe-measured). children is the multiset of this
   block's child offsets in the SOURCE numbering, its cache edge already
   removed (placed in the descriptor row by place-graph!); every
   designated word must consume one entry and none may be left over. A
   mismatch throws: the harness does not guess."
  [src tgt src-off tgt-off kind children xlate]
  (let [left (volatile! children)]
    (doseq [p (pointer-word-positions src src-off kind)]
      (let [t (slab/read-i32 tgt tgt-off p)
            c (get @left t 0)]
        (when-not (pos? c)
          (throw (ex-info "place-graph!: a designated pointer word is not among the block's graph edges"
                          {:block src-off :at p :word t})))
        (vswap! left assoc t (dec c))
        (slab/write-i32! tgt tgt-off p (get xlate t))))
    (when (some (fn [[_ c]] (pos? c)) @left)
      (throw (ex-info "place-graph!: a graph edge has no designated pointer word"
                      {:block src-off :unpatched @left})))))

(defn- place-graph!
  "Place graph (read in src) into tgt: a fresh same-class block per source
   block, its class bytes copied, its pointers rewritten. Returns
   {:root tgt-off :blocks {src-off tgt-off} :kinds {tgt-off kind}
    :edges [[tgt-owner tgt-target] ...]}."
  [src graph tgt]
  (let [order (:order graph)
        sizes (into {} (map (fn [o] [o (block-bytes src o)]) order))
        blocks (into {}
                     (map (fn [o] [o (alloc/-sio-alloc! tgt (int (get sizes o)))])
                          order))]
    ;; the bytes
    (doseq [o order]
      (alloc/-sio-write-bytes! tgt (get blocks o) 0
                               (alloc/-sio-read-bytes src o 0 (get sizes o))))
    ;; the pointers: at the reader's designated positions in the bytes,
    ;; and the digest-cache edge (if any) in the target descriptor's
    ;; POINTER column, where the tally reads it from
    (let [by-owner (group-by first (:edges graph))]
      (doseq [o order]
        (let [cache (cache-word src o)
              children (frequencies (map second (get by-owner o)))
              children (if cache
                         (let [c (get children cache 0)]
                           (when-not (pos? c)
                             (throw (ex-info "place-graph!: the cache edge is not in the graph"
                                             {:block o :cache cache})))
                           (assoc children cache (dec c)))
                         children)]
          (patch-block! src tgt o (get blocks o) (get (:blocks graph) o) children blocks)
          (when cache
            (let [row (alloc/desc-of tgt (get blocks o))]
              (slab/region-store-i32! (nth (nth row 2) 0) (nth (nth row 2) 1)
                                      (int (get blocks cache))))))))
    {:root (get blocks (:root graph))
     :blocks blocks
     :kinds (into {} (map (fn [[o k]] [(get blocks o) k]) (:blocks graph)))
     :edges (mapv (fn [[o t]] [(get blocks o) (get blocks t)]) (:edges graph))}))

(defn- faithful?
  "The placed tree reads back, through the tally's own reader, as the source
   graph: same blocks under the correspondence, same kinds, same edges."
  [tgt placed graph]
  (let [g2 (graph-of tgt (:root placed))
        inv (into {} (map (fn [[s t]] [t s]) (:blocks placed)))]
    (and (= (count (:blocks g2)) (count (:blocks graph)))
         (every? (fn [[t k]] (= k (get (:blocks graph) (get inv t)))) (:blocks g2))
         (= (frequencies (:edges graph))
            (frequencies (map (fn [[o t]] [(get inv o) (get inv t)]) (:edges g2)))))))

(defn- rows
  "{off [count-word flags-word]} read straight off the descriptor rows."
  [sio offs]
  (into {}
        (map (fn [o]
               (let [r (alloc/desc-of sio o)]
                 [o [(slab/region-load-i32 (nth (nth r 0) 0) (nth (nth r 0) 1))
                     (slab/region-load-i32 (nth (nth r 1) 0) (nth (nth r 1) 1))]]))
             offs)))

(defn- rows-by-source
  "rows over a placement, keyed by the SOURCE offsets, so two placements of
   one graph -- and the source's own committed rows -- compare directly."
  [sio placed]
  (let [rs (rows sio (vals (:blocks placed)))]
    (into {} (map (fn [[s t]] [s (get rs t)]) (:blocks placed)))))

(defn- expected-counts
  "What the walk writes, derived independently of it: a block's count is its
   in-degree over the graph's edges, the root's one more (the slot's hold)."
  [graph]
  (let [deg (frequencies (map second (:edges graph)))]
    (into {}
          (map (fn [[o _]] [o (+ (get deg o 0) (if (== o (:root graph)) 1 0))])
               (:blocks graph)))))

(defn- reopen
  "The Eve value whose header block is at off, read through sio (the probe
   lib's reopen: what a swap function returns for a placed root)."
  [sio off]
  (let [t (ser/read-header-tag sio off)
        ctor (ser/get-header-constructor t)]
    (ctor sio off)))

(defn- ds-of [x] (a/-domain-state x))
(defn- flush! [x] (#'a/flush-zero-queue! (ds-of x)))
(defn- root-ptr [x] (second (a/version x)))

;; ---------------------------------------------------------------------------
;; Test values
;; ---------------------------------------------------------------------------

(defprotocol IBox (box-label [b]))

(edt/deftype Box [label]
  IBox
  (box-label [_] label))

(defprotocol IHolder
  (the-box [h])
  (the-note [h]))

(edt/deftype Holder [^:Box box ^:volatile-mutable note]
  IHolder
  (the-box [_] box)
  (the-note [_] note))

#?(:clj
   (defn- gen-value [^Random r ^long depth]
     (let [c (if (zero? depth) (mod (.nextInt r) 3) (mod (.nextInt r) 7))]
       (case (int c)
         0 (mod (.nextInt r) 1000)
         1 (str "s-" (mod (.nextInt r) 50))
         2 (keyword (str "k" (mod (.nextInt r) 20)))
         3 (vec (repeatedly (mod (.nextInt r) 12) #(gen-value r (dec depth))))
         4 (into {} (repeatedly (mod (.nextInt r) 8)
                                (fn [] [(keyword (str "k" (mod (.nextInt r) 20)))
                                        (gen-value r (dec depth))])))
         5 (into #{} (repeatedly (mod (.nextInt r) 8) #(gen-value r (dec depth))))
         6 (apply list (repeatedly (mod (.nextInt r) 8) #(gen-value r (dec depth))))))))

(defn- install-nested! [x]
  (reset! x {:a [1 2 {:b #{"x" "y"}} (list 3 4)]
             :c {:d (vec (range 40)) :e "text"}
             7 {:deep {:deeper [#{1 2} {:z 9}]}}}))

(defn- install-string! [x]
  (reset! x (apply str (repeat 300 "abcdefgh"))))

(defn- install-deftype! [x]
  (swap! x (fn [_] {:h (->Holder (->Box "b1") {:note [1 2 3]})})))

(defn- install-hash-map! [x]
  (swap! x (fn [_]
             {:m (reduce (fn [m i] (e/assoc m i (str "v" i)))
                         (em/hash-map) (range 300))})))

(defn- install-eve-vec! [x]
  (swap! x (fn [_] {:v (v/eve-vec (range 2000))})))

#?(:clj
   (do
     (defn- install-gen-7! [x] (reset! x (gen-value (Random. 7) 3)))
     (defn- install-gen-99! [x] (reset! x (gen-value (Random. 99) 3)))))

(def ^:private cases
  "Z4.1's values: [label install!] -- each installs its value in the source
   atom (plain values by reset!, domain-built ones inside a swap!)."
  [["nested plain" install-nested!]
   ["a long string at the root" install-string!]
   ["an eve-deftype with a reference and a serialized field" install-deftype!]
   ["a hash map of 300" install-hash-map!]
   ["an eve vector of 2000" install-eve-vec!]
   #?@(:clj [["generated, seed 7" install-gen-7!]
             ["generated, seed 99" install-gen-99!]])])

;; ---------------------------------------------------------------------------
;; Z4.1 -- written counts equal the walk's, row for row
;; ---------------------------------------------------------------------------

(deftest written-counts-equal-walked
  (doseq [[label install!] cases]
    (testing label
      (let [dom (a/atom-domain nil)
            x (a/lookup-or-create-mmap-atom! dom "z4/src" nil)
            _ (install! x)
            sio-src (:sio (ds-of x))
            graph (graph-of sio-src (root-ptr x))
            walked (rows sio-src (keys (:blocks graph)))
            ;; the walk's own words match the independent in-degree model
            exp (expected-counts graph)]
        (is (= exp (into {} (map (fn [[o [c _]]] [o c]) walked)))
            "the source's committed counts are the graph's in-degrees, root +1")
        ;; arm B: placed, counts written from the tables
        (let [sio-b (alloc/heap-domain-sio! {})
              placed-b (place-graph! sio-src graph sio-b)]
          (is (faithful? sio-b placed-b graph) "the placement reads back as the source graph")
          (let [before (tally/placed-write-count)]
            (binding [tally/*count-at-place* true]
              (is (true? (tally/register-placed! sio-b (:root placed-b)
                                                 (:kinds placed-b) (:edges placed-b))))
              (tally/hold-root! sio-b (:root placed-b)))
            (is (= (inc before) (tally/placed-write-count)) "the written path ran, not the walk")
            (is (= walked (rows-by-source sio-b placed-b))
                "written rows equal walked rows, count word and flags word"))
          ;; arm C: the same placement, counted by the walk itself
          (let [sio-c (alloc/heap-domain-sio! {})
                placed-c (place-graph! sio-src graph sio-c)]
            (is (faithful? sio-c placed-c graph))
            (tally/hold-root! sio-c (:root placed-c))
            (is (= walked (rows-by-source sio-c placed-c))
                "a placed tree walked in its own domain also equals the source's rows")))))))

;; ---------------------------------------------------------------------------
;; Z4.2 -- exactness through a real atom: a won swap, a lost CAS, a throw
;; ---------------------------------------------------------------------------

(def ^:private z42-value
  {:a (vec (range 60)) :m {:x [1 2 3] :y #{"p" "q"}} :s "placed"})

(defn- place-commit!
  "swap! atom x to the value graph describes (read in src-sio's domain),
   placing it inside the swap function. register? decides whether the
   placement is registered for written counts; the flag is the caller's
   binding. Returns the placements the attempts made."
  [x src-sio graph register?]
  (let [attempts (clojure.core/atom [])]
    (swap! x (fn [_]
               (let [sio d/*domain-sio*
                     placed (place-graph! src-sio graph sio)]
                 (when register?
                   (tally/register-placed! sio (:root placed) (:kinds placed) (:edges placed)))
                 (clojure.core/swap! attempts conj placed)
                 (reopen sio (:root placed)))))
    @attempts))

(defn- src-graph
  "The graph of z42-value, committed in its own throwaway domain."
  []
  (let [dom (a/atom-domain nil)
        s (a/lookup-or-create-mmap-atom! dom "z4/z42-src" nil)]
    (reset! s z42-value)
    [(:sio (ds-of s)) (graph-of (:sio (ds-of s)) (root-ptr s))]))

(deftest written-counts-exact
  (let [[src-sio graph] (src-graph)
        walked (rows src-sio (keys (:blocks graph)))]
    (testing "a won swap!: the placed value's counts are the walk's, and it reads back"
      (let [dom (a/atom-domain nil)
            x (a/lookup-or-create-mmap-atom! dom "z4/won" nil)
            before (tally/placed-write-count)
            placements (binding [tally/*count-at-place* true]
                         (place-commit! x src-sio graph true))
            placed (peek placements)]
        (is (= 1 (count placements)))
        (is (= (inc before) (tally/placed-write-count)))
        (is (= z42-value @x))
        (is (= walked (rows-by-source (:sio (ds-of x)) placed)))))
    (testing "a lost CAS: the loser's written counts unwind whole, the winner's are the walk's"
      (with-redefs-fn {#'a/try-flush-retires! (fn [_ds] nil)}
        (fn []
          (let [dom (a/atom-domain nil)
                x (a/lookup-or-create-mmap-atom! dom "z4/lost" nil)
                _ (reset! x {:other 0})
                attempts (clojure.core/atom 0)
                placements (clojure.core/atom [])
                before (tally/placed-write-count)]
            (binding [tally/*count-at-place* true]
              (swap! x (fn [_]
                         (let [n (clojure.core/swap! attempts inc)
                               sio d/*domain-sio*
                               placed (place-graph! src-sio graph sio)]
                           (tally/register-placed! sio (:root placed) (:kinds placed) (:edges placed))
                           (clojure.core/swap! placements conj placed)
                           (when (== n 1)
                             @(future (reset! x {:other 1})))
                           (reopen sio (:root placed))))))
            (is (= 2 @attempts) "the first attempt lost its CAS and the swap retried")
            (is (= (+ before 2) (tally/placed-write-count))
                "both attempts' holds were written (the loser's, then the winner's)")
            (is (= z42-value @x))
            (let [[loser winner] @placements
                  sio (:sio (ds-of x))]
              (is (= walked (rows-by-source sio winner)) "the winner's rows are the walk's")
              (flush! x)
              (is (not-any? #(alloc/allocated? sio %) (vals (:blocks loser)))
                  "after the flush, no block of the losing placement is allocated")
              (is (every? #(alloc/allocated? sio %) (vals (:blocks winner)))
                  "and every block of the winner still is"))))))
    (testing "a throw after placement: no count is written, nothing is held, the atom is untouched"
      (let [dom (a/atom-domain nil)
            x (a/lookup-or-create-mmap-atom! dom "z4/throw" nil)
            _ (reset! x {:old 1})
            placed-box (clojure.core/atom nil)
            before (tally/placed-write-count)]
        (is (thrown? clojure.lang.ExceptionInfo
                     (binding [tally/*count-at-place* true]
                       (swap! x (fn [_]
                                  (let [sio d/*domain-sio*
                                        placed (place-graph! src-sio graph sio)]
                                    (tally/register-placed! sio (:root placed)
                                                            (:kinds placed) (:edges placed))
                                    (clojure.core/reset! placed-box placed)
                                    (throw (ex-info "boom" {}))))))))
        (let [placed @placed-box
              sio (:sio (ds-of x))]
          (is (= before (tally/placed-write-count)) "the write never ran")
          (is (every? (fn [[_ t]] (== -1 (tally/count-of sio t))) (:blocks placed))
              "every placed block is uncounted, exactly as a thrown build leaves its blocks")
          (is (= {:old 1} @x))
          (testing "the stale registration is the place owner's to forget; a later plain swap walks"
            (is (true? (tally/forget-placed! sio (:root placed))))
            (is (false? (tally/forget-placed! sio (:root placed))))
            (swap! x assoc :old 2)
            (is (= {:old 2} @x))))))))

;; ---------------------------------------------------------------------------
;; Z4.3 -- dropping the root frees the domain to empty
;; ---------------------------------------------------------------------------

(deftest written-counts-release-clean
  (doseq [[label flag?] [["written counts" true] ["the walk" false]]]
    (testing label
      (let [dom (a/atom-domain nil)
            x (a/lookup-or-create-mmap-atom! dom (str "z4/rel-" label) nil)
            [src-sio graph] (src-graph)
            placements (binding [tally/*count-at-place* flag?]
                         (place-commit! x src-sio graph flag?))
            placed (peek placements)
            sio (:sio (ds-of x))]
        (is (= z42-value @x))
        (is (every? #(alloc/allocated? sio %) (vals (:blocks placed))))
        (reset! x nil)
        (flush! x)
        (is (not-any? #(alloc/allocated? sio %) (vals (:blocks placed)))
            "every placed block is freed once the root is dropped and the queue flushed")))))

;; ---------------------------------------------------------------------------
;; Z4.4 -- a block with several holders is counted once per holder (M7, k=100)
;; ---------------------------------------------------------------------------

(deftest written-counts-shared
  (let [dom (a/atom-domain nil)
        s (a/lookup-or-create-mmap-atom! dom "z4/shared-src" nil)
        _ (reset! s {:v (vec (range 1000))})
        vec-ptr (a/read-pinned s (fn [m] (d/-root-header-off (get m :v))))]
    (swap! s (fn [m]
               (let [v (get m :v)]
                 (reduce (fn [acc i] (assoc acc (keyword (str "k" i)) v))
                         (dissoc m :v) (range 100)))))
    (let [sio-src (:sio (ds-of s))
          graph (graph-of sio-src (root-ptr s))
          deg (frequencies (map second (:edges graph)))]
      (is (= 100 (get deg vec-ptr)) "the source graph holds the one vector at 100 places")
      (let [walked (rows sio-src (keys (:blocks graph)))
            sio-b (alloc/heap-domain-sio! {})
            placed-b (place-graph! sio-src graph sio-b)]
        (is (faithful? sio-b placed-b graph))
        (binding [tally/*count-at-place* true]
          (tally/register-placed! sio-b (:root placed-b) (:kinds placed-b) (:edges placed-b))
          (tally/hold-root! sio-b (:root placed-b)))
        (is (= 100 (tally/count-of sio-b (get (:blocks placed-b) vec-ptr)))
            "the shared vector's header is counted once per holder")
        (is (= walked (rows-by-source sio-b placed-b))
            "and every other row equals the walk's, shared blocks included")))))

;; ---------------------------------------------------------------------------
;; Z4.5 -- flag off: behaviour identical to the walk
;; ---------------------------------------------------------------------------

(deftest flag-off-is-the-walk
  (let [[src-sio graph] (src-graph)
        walked (rows src-sio (keys (:blocks graph)))]
    (testing "flag off: register-placed! records nothing, the commit walks"
      (let [dom (a/atom-domain nil)
            x (a/lookup-or-create-mmap-atom! dom "z4/off" nil)
            before (tally/placed-write-count)
            placements (binding [tally/*count-at-place* false]
                         (place-commit! x src-sio graph true))
            placed (peek placements)
            sio (:sio (ds-of x))]
        (is (= before (tally/placed-write-count)) "no count was written from tables")
        (is (false? (tally/forget-placed! sio (:root placed))) "no registration was taken")
        (is (= z42-value @x))
        (is (= walked (rows-by-source sio placed)) "the rows are the walk's")))
    (testing "flag on but nothing registered: an ordinary swap! walks, as today"
      (let [dom (a/atom-domain nil)
            x (a/lookup-or-create-mmap-atom! dom "z4/on-plain" nil)
            before (tally/placed-write-count)]
        (binding [tally/*count-at-place* true]
          (reset! x z42-value))
        (is (= before (tally/placed-write-count)))
        (is (= z42-value @x))
        (let [g2 (graph-of (:sio (ds-of x)) (root-ptr x))]
          (is (= (expected-counts g2)
                 (into {} (map (fn [[o [c _]]] [o c])
                               (rows (:sio (ds-of x)) (keys (:blocks g2))))))))))))
