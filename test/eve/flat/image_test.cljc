(ns eve.flat.image-test
  "Z1 — the image: a flat slab I/O and detach (IMPL-PLAN §3.3, lane Z1).
   Requirements Z1.1–Z1.7, one named test each. The DataScript row of
   image-reads-every-type runs when datascript.core is on the classpath
   (contract-test/lib-ds and siblings, as probes/p5_types.clj ran it); the
   standard test classpath does not carry it, so there the row reports a
   skip and the lane's DS-classpath run is the evidence."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as gen]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.retire :as retire]
            [eve.atom :as ea]
            [eve.deftype :as edt]
            [eve.sorted :as es]
            [eve.map :as em]
            [eve.array :as arr]
            [eve.dataset :as ds]
            [eve.flat]
            [eve.flat.sio :as ssio]
            [eve.flat.image :as img]
            [eve.vec] [eve.set] [eve.list])
  (:import #_{:clj-kondo/ignore [:unused-import]} ; Arrays is used below; the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use [java.util Arrays Random]
           [java.util.concurrent CountDownLatch]))

;; ---------------------------------------------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------------------------------------------

(defn- heap-sio [] (alloc/heap-domain-sio! {}))

(defn- build-plain
  "A plain collection written into sio by the direct writers (what swap! uses): its eve value."
  [sio v]
  (let [kind (cond (map? v) :map (set? v) :set (vector? v) :vec :else :list)
        off (codec/write-collection! kind sio v)
        t (ser/read-header-tag sio off)]
    ((ser/get-header-constructor t) sio off)))

(defn- in-domain
  "Build (make sio) in a fresh heap domain under the domain binding: the eve value."
  [make]
  (let [sio (heap-sio)]
    (binding [d/*domain-sio* sio] (make sio))))

(defn- through-image
  "Build (make sio) in a fresh heap domain; detach the value; answer the value read in place
   from the opened image, and the image."
  [make]
  (let [v (in-domain make)
        im (img/detach-image v)]
    [(img/image-value (img/open-image (img/image-bytes im))) im v]))

(defn- frozen-throw?
  "True when (f) throws the flat view's typed refusal."
  [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e
         (= :eve.flat.sio/frozen-view (:type (ex-data e))))))

;; a user type: a primitive, a mutable primitive, a serialized value, a reference to another instance
(edt/deftype ZTag [^:int32 id label]
  clojure.lang.ILookup
  (valAt [_this k] (case k :id id :label label nil))
  (valAt [_this k nf] (case k :id id :label label nf)))
(edt/deftype ZItem [^:int32 n ^:mutable ^:int32 hits payload ^:ZTag tag]
  clojure.lang.ILookup
  (valAt [_this k] (case k :n n :hits hits :payload payload :tag tag nil))
  (valAt [_this k nf] (case k :n n :hits hits :payload payload :tag tag nf))
  clojure.lang.Counted
  (count [_this] n))

;; ---------------------------------------------------------------------------------------------------------------
;; Z1.1 — a read-only slab I/O over one byte array refuses every mutator
;; ---------------------------------------------------------------------------------------------------------------

(deftest view-refuses-every-mutator
  (let [bs (ssio/builder-sio)
        m (ser/convert-to-eve bs {:a [1 2 3] :b #{:x :y}})
        len (ssio/builder-length bs)
        view (ssio/view-sio (Arrays/copyOf (ssio/builder-bytes bs) (int len)) len)
        off 32]
    (testing "reads work through the view"
      (is (some? (alloc/-sio-read-i32 view 8 0)))
      (is (= {:a [1 2 3] :b #{:x :y}} (d/eve->clj (img/read-value view (d/-root-header-off m))))))
    (testing "every mutator throws the typed refusal"
      (is (frozen-throw? #(alloc/-sio-alloc! view 16)) "alloc!")
      (is (frozen-throw? #(alloc/-sio-free! view off)) "free!")
      (is (frozen-throw? #(alloc/-sio-write-u8! view off 0 1)) "write-u8!")
      (is (frozen-throw? #(alloc/-sio-write-u16! view off 0 1)) "write-u16!")
      (is (frozen-throw? #(alloc/-sio-write-i32! view off 0 1)) "write-i32!")
      (is (frozen-throw? #(alloc/-sio-write-bytes! view off 0 (byte-array 4))) "write-bytes!")
      (is (frozen-throw? #(alloc/-sio-cas-i32! view off 0 0 1)) "cas-i32!")
      (is (frozen-throw? #(alloc/-sio-copy-block! view off 0 off 4 4)) "copy-block!"))
    (testing "and a write through a value read from the view is refused (assoc allocates)"
      (let [v (img/read-value view (d/-root-header-off m))]
        (is (frozen-throw? #(assoc v :c 1)))))))

;; ---------------------------------------------------------------------------------------------------------------
;; Z1.2 — every type of probes/p5_types.clj reads in place from an image
;; ---------------------------------------------------------------------------------------------------------------

(defn- check-row [label make expected & [extra]]
  (testing label
    (let [[iv im v] (through-image make)]
      (is (= expected (d/eve->clj iv)) "the image reads the same value as the slab original")
      (is (= (d/eve->clj v) (d/eve->clj iv)))
      (when extra (extra iv v im)))))

(defn- check-row-by [label make plain-of expected & [extra]]
  (testing label
    (let [[iv im v] (through-image make)]
      (is (= expected (plain-of iv)) "the image reads the same value as the slab original")
      (is (= (plain-of v) (plain-of iv)))
      (when extra (extra iv v im)))))

(deftest image-reads-every-type
  (let [xs (vec (range 200))
        kvs (zipmap (range 300) (map #(str "v" %) (range 300)))]
    (check-row "nested map/vector/set (the P1 shape)"
               (fn [sio] (build-plain sio {:id 42 :tags #{:a :b} :items xs
                                           :nested {:k {:k2 [1 2.5 "three" :four]}}}))
               {:id 42 :tags #{:a :b} :items xs :nested {:k {:k2 [1 2.5 "three" :four]}}})
    (check-row "hash set of 200"
               (fn [sio] (build-plain sio (set xs))) (set xs))
    (check-row-by "sorted-map of 300 (eve.sorted treap of eve-deftypes)"
                  (fn [_] (apply es/sorted-map (mapcat identity kvs)))
                  (fn [x] (mapv (fn [e] [(key e) (val e)]) (seq x)))
                  (vec (sort-by first kvs))
                  (fn [iv v _]
                    (is (= "v150" (get iv 150)) "lookup in place")
                    (is (= 300 (count iv)))
                    ;; eve.sorted/subseq, not clojure.core/subseq: EveSortedMap
                    ;; does not implement clojure.lang.Sorted on EITHER arm
                    ;; (measured at this pin, 2026-11-12: core subseq throws
                    ;; the identical ClassCastException on the slab original),
                    ;; so the namespace's own subseq is the like-for-like verb.
                    (is (= [[10 "v10"] [11 "v11"] [12 "v12"]]
                           (mapv (fn [e] [(key e) (val e)]) (es/subseq iv >= 10 < 13)))
                        "subseq in place")
                    (is (= (mapv (fn [e] [(key e) (val e)]) (es/subseq v >= 10 < 13))
                           (mapv (fn [e] [(key e) (val e)]) (es/subseq iv >= 10 < 13)))
                        "subseq reads the same from the image as from the slab")))
    (check-row-by "sorted-set of 200 (eve.sorted)"
                  (fn [_] (apply es/sorted-set xs)) (fn [x] (vec (seq x))) xs)
    (check-row-by "hash map of 300"
                  (fn [_] (apply em/hash-map (mapcat identity kvs)))
                  (fn [x] (into {} (seq x))) (into {} kvs))
    (check-row "eve array :float64 of 200"
               (fn [sio] (arr/eve-array sio :float64 (mapv double xs)))
               (mapv double xs)
               (fn [iv _ _] (is (= 199.0 (nth iv 199)) "nth in place")))
    (check-row "dataset of 2 columns x 200 rows"
               (fn [sio] (ds/dataset sio {:price (arr/eve-array sio :float64 (mapv double xs))
                                          :qty (arr/eve-array sio :int32 xs)}))
               {:price (mapv double xs) :qty xs}
               (fn [iv _ _] (is (= 199 (nth (ds/column iv :qty) 199)) "column read in place")))
    (testing "a user eve-deftype with all four field classes"
      (let [[iv _ v] (through-image (fn [sio] (->ZItem sio 7 0 {:k [1 2 3] :s "serialized"}
                                                       (->ZTag sio 42 "the tag"))))]
        (is (= (class v) (class iv)) "the same class reads the image — no sister needed on this host")
        (is (and (= 7 (:n iv)) (= 0 (:hits iv))
                 (= {:k [1 2 3] :s "serialized"} (d/eve->clj (:payload iv)))
                 (some? (:tag iv)) (= 42 (:id (:tag iv))) (= 7 (count iv)))
            "every field reads the same from the image")))
    (testing "list of 200 — header reads, and the walk (the walk is lane L1's fix: make-list allocates)"
      (let [[iv _ _] (through-image (fn [sio] (build-plain sio (apply list xs))))]
        (is (= 200 (count iv)) "count in place")
        (is (= 0 (first iv)) "first in place")
        (is (= 57 (nth iv 57)) "nth in place")
        (is (= xs (vec (d/eve->clj iv))) "the whole list reads in place (needs L1)")))
    (testing "a DataScript database"
      (if-let [dsc (try (require 'datascript.core) (resolve 'datascript.core/db-with)
                        (catch Throwable _ nil))]
        (let [empty-db (resolve 'datascript.core/empty-db)
              datoms (resolve 'datascript.core/datoms)
              q (resolve 'datascript.core/q)
              people (mapv (fn [i] {:db/id (inc i) :name (str "person-" (mod i 8))
                                    :age (mod (* 7 i) 100) :salary (mod (* 131 i) 100000)})
                           (range 150))
              [iv _ db] (through-image (fn [_] (dsc (empty-db) people)))
              tuples (fn [x] (mapv (fn [dt] [(:e dt) (:a dt) (:v dt)]) (datoms x :eavt)))]
          (is (= (tuples db) (tuples iv)) "the DB read in place has the same datoms")
          (is (= (q '[:find ?n :where [?e :age ?a] [(< ?a 3)] [?e :name ?n]] db)
                 (q '[:find ?n :where [?e :age ?a] [(< ?a 3)] [?e :name ?n]] iv))
              "a Datalog query runs over the image in place"))
        (println "  Z1.2 DataScript row SKIPPED: datascript.core not on this classpath"))))
  (testing "the frozen public verb: eve.flat/detach answers the image bytes"
    (let [plain {:a [1 2 3] :b {:c "x"}}
          v (in-domain (fn [sio] (build-plain sio plain)))
          bs (img/detach v)]
      (is (bytes? bs) "a value detaches to one byte array")
      (is (= plain (d/eve->clj (img/image-value (img/open-image bs))))
          "and the bytes open and read back")
      ;; byte-content equality: clojure.core/= on byte arrays is reference
      ;; identity, which no fresh answer could ever satisfy — determinism is
      ;; a claim about the BYTES (measured: two detaches of this value are
      ;; Arrays/equals; finisher #4 local probe).
      (is (Arrays/equals bs (img/detach v)) "detach is deterministic")
      (is (Arrays/equals bs (eve.flat/detach v)) "the public namespace delegates to this lane"))
    (let [dom (ea/atom-domain nil :capacities {})
          a (ea/lookup-or-create-mmap-atom! dom "z1/verb" nil)
          _ (swap! a (fn [_] {:n 42 :data (vec (range 50))}))
          bs (img/detach a)]
      (is (bytes? bs) "an atom detaches to one byte array")
      (is (= {:n 42 :data (vec (range 50))}
             (d/eve->clj (img/image-value (img/open-image bs))))
          "the atom's image is its committed value"))))

;; ---------------------------------------------------------------------------------------------------------------
;; Z1.3 — detach copies exactly the reachable blocks, root first, and emits both tables
;; ---------------------------------------------------------------------------------------------------------------

(defn- retire-edges
  "The [owner target] pairs eve.retire/children finds over the value at root in sio, derived
   independently of detach: a walk that tracks each block's kind from the edge that reached it."
  [sio root]
  (loop [stack [[:val root]] kinds {:root :val} seen #{} edges []]
    (if (empty? stack)
      edges
      (let [[k o] (peek stack)
            stack (pop stack)
            key (bit-and o 0xFFFFFFFF)]
        (if (contains? seen key)
          (recur stack kinds seen edges)
          (let [kids (retire/children sio k o)]
            (recur (into stack (map (fn [[_ ck c]] [ck c])) kids)
                   kinds
                   (conj seen key)
                   (into edges (map (fn [[_ _ c]] [o c])) kids))))))))

(deftest detach-tables-complete
  (let [g (gen/map gen/keyword
                   (gen/one-of [gen/small-integer gen/string-alphanumeric gen/boolean
                                (gen/vector gen/small-integer 0 40)
                                (gen/set gen/keyword)
                                (gen/list gen/small-integer)
                                (gen/map gen/keyword gen/small-integer)]))
        vals (gen/sample g 30)]
    (doseq [v vals]
      (let [sio (heap-sio)
            ev (binding [d/*domain-sio* sio] (ser/convert-to-eve sio v))
            root (d/-root-header-off ev)
            im (img/detach-image sio root)
            blocks (img/image-blocks im)
            relocs (img/image-relocations im)
            src-of (fn [ord] (:src-off (nth blocks ord)))]
        (is (= (count (retire/reach sio root)) (count blocks))
            "the block table names exactly the reachable blocks, each once")
        (is (= root (:src-off (first blocks))) "root first")
        (is (= (img/image-root im) (:image-off (first blocks))) "the header's root is block 0")
        (is (= (frequencies (retire-edges sio root))
               (frequencies (map (fn [r] [(src-of (:owner r)) (src-of (:target r))]) relocs)))
            "the relocation table names every pointer eve.retire/children finds")
        (let [bs (img/image-bytes im)
              by-ord (into {} (map (fn [b] [(:ordinal b) (:image-off b)])) blocks)]
          (is (every? (fn [r] (= (by-ord (:target r))
                                 (img/read-i32-at bs (:field-pos r))))
                      relocs)
              "every relocation's field position holds its target's image offset"))))
    (testing "a block reached ten times is copied once (sharing kept)"
      (let [sio (heap-sio)
            shared (binding [d/*domain-sio* sio] (ser/convert-to-eve sio (vec (range 100))))
            m (binding [d/*domain-sio* sio]
                (reduce (fn [acc i] (assoc acc (keyword (str "k" i)) shared))
                        (ser/convert-to-eve sio {}) (range 10)))
            root (d/-root-header-off m)
            im (img/detach-image sio root)
            blocks (img/image-blocks im)]
        (is (= (count (retire/reach sio root)) (count blocks)))
        (is (= 10 (count (filter (fn [r] (= (d/-root-header-off shared)
                                            (:src-off (nth blocks (:target r)))))
                                 (img/image-relocations im))))
            "ten edges land on the one copied block")))))

;; ---------------------------------------------------------------------------------------------------------------
;; Z1.4 — a detach under the pin is one committed value while another thread swaps
;; ---------------------------------------------------------------------------------------------------------------

(deftest detach-under-concurrent-writes
  (let [dom (ea/atom-domain nil :capacities {})
        a (ea/lookup-or-create-mmap-atom! dom "z1/t4" nil)
        _ (swap! a (fn [_] {:n 0 :data (vec (range 500))}))
        started (CountDownLatch. 1)
        stop (atom false)
        swapper (Thread. (fn []
                           (.countDown started)
                           (loop [i 0]
                             (when (and (< i 500) (not @stop))
                               (swap! a (fn [m] (assoc m :n (inc (:n m)))))
                               (recur (inc i))))))]
    (try
      (ea/read-pinned
       a (fn [v]
           (let [expected (d/eve->clj v)]
             (.start swapper)
             (.await started)
             (Thread/sleep 50) ;; swaps are in flight while the pin is held
             (let [im (img/detach-image v)]
               (is (= expected (d/eve->clj (img/image-value (img/open-image (img/image-bytes im))))))
               "the detached image is the pinned value, whole")
             (is (= (:n expected) 0) "the pinned value predates the swaps"))))
      (finally (reset! stop true) (.join swapper 5000)))
    (is (pos? (:n @a)) "the other thread really swapped during the detach")))

;; ---------------------------------------------------------------------------------------------------------------
;; Z1.5 — an earlier (length, root) view survives appends (property, from P1)
;; ---------------------------------------------------------------------------------------------------------------

(defn- step5 [m i]
  (case (int (mod i 5))
    0 (assoc m (keyword "p" (str "k" i)) i)
    1 (assoc m :p/vec (vec (range i)))
    2 (if-let [k (first (sort-by str (keys m)))] (dissoc m k) m)
    3 (assoc m :p/n (* 3 i))
    4 (assoc m :p/nested {:i i :s (apply str (repeat i "x")) :set (set (range (mod i 7)))})))

(deftest views-survive-appends
  (let [g (gen/map gen/keyword
                   (gen/one-of [gen/small-integer gen/string-alphanumeric gen/boolean
                                (gen/vector gen/small-integer 0 40)
                                (gen/map gen/keyword gen/small-integer)]))
        vals (gen/sample g 60)
        n-steps 12
        bad (atom [])]
    (doseq [v vals]
      (let [bs (ssio/builder-sio)
            e0 (ser/convert-to-eve bs v)
            [plains snaps]
            (loop [i 0 p v e e0 plains [v] snaps [[(d/-root-header-off e0) (ssio/builder-length bs)]]]
              (if (== i n-steps)
                [plains snaps]
                (let [p2 (step5 p i) e2 (step5 e i)]
                  (recur (inc i) p2 e2 (conj plains p2)
                         (conj snaps [(d/-root-header-off e2) (ssio/builder-length bs)])))))
            buf (ssio/builder-bytes bs)]
        (doseq [[p [r len]] (map vector plains snaps)]
          (let [grown (img/read-value bs r)
                cut (img/read-value (ssio/view-sio (Arrays/copyOfRange buf 0 (int len)) len) r)
                good (and (= p (d/eve->clj grown)) (= p (d/eve->clj cut))
                          (= grown p) (= cut p)
                          (= (hash p) (hash grown)) (= (hash p) (hash cut)))]
            (when-not good (swap! bad conj {:p p :r r :len len}))))))
    (is (empty? @bad)
        (str "every earlier view stayed equal, hash-stable, and readable from a buffer cut at "
             "its length, while the builder appended"
             (when (seq @bad) (str " — first bad: " (pr-str (first @bad))))))))

;; ---------------------------------------------------------------------------------------------------------------
;; Z1.6 — detaching a build that left garbage yields no dead byte
;; ---------------------------------------------------------------------------------------------------------------

(deftest detach-compacts
  (let [bs (ssio/builder-sio)
        m0 (ser/convert-to-eve bs (zipmap (range 200) (range 200)))
        m (reduce (fn [acc i] (assoc acc i (str "new-" i))) m0 (range 100))
        root (d/-root-header-off m)
        tail (ssio/builder-length bs)
        im (img/detach-image bs root)
        blocks (img/image-blocks im)
        align4 (fn [n] (bit-and (+ n 3) (bit-not 3)))
        blocks-end (reduce (fn [acc b] (+ acc (align4 (:size b)))) 32 blocks)
        tables-off (bit-and (+ blocks-end 7) (bit-not 7))
        expect-len (+ tables-off (* 8 (count blocks)) (* 12 (count (img/image-relocations im))))]
    (is (= (count (retire/reach bs root)) (count blocks))
        "exactly the reachable blocks")
    (is (every? (fn [[a b]] (= (:image-off b) (+ (:image-off a) (align4 (:size a)))))
                (partition 2 1 blocks))
        "blocks are packed contiguously — no dead byte between them (live ratio 1.0)")
    (is (and (>= (img/image-length im) expect-len) (<= (img/image-length im) (+ expect-len 7)))
        "the image is header + blocks + tables, nothing else")
    (is (< blocks-end (* 0.6 tail))
        (str "and the build really had garbage to leave behind (blocks end " blocks-end
             " of tail " tail ")"))
    (is (= (d/eve->clj m) (d/eve->clj (img/image-value (img/open-image (img/image-bytes im))))))))

;; ---------------------------------------------------------------------------------------------------------------
;; Z1.7 — hot path: a flat reads at slab speed, not through protocol dispatch
;; ---------------------------------------------------------------------------------------------------------------

(defn- bench-min-ms
  "Min of n runs of (f) after warm warmup runs, in ms."
  [warm n f]
  (dotimes [_ warm] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i n)
      best
      (let [t0 (System/nanoTime) _ (f) dt (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best dt))))))

(deftest image-read-speed
  (let [shapes [{:label "map 10k int->int"
                 :plain (zipmap (range 10000) (range 10000))
                 :reads (let [r (Random. 42)] (int-array (repeatedly 50000 #(.nextInt r 10000))))
                 :read (fn [m ^ints ks] (loop [i 0 s 0] (if (== i (alength ks)) s (recur (inc i) (+ s (get m (aget ks i)))))))}
                {:label "vector 100k ints"
                 :plain (vec (range 100000))
                 :reads (let [r (Random. 43)] (int-array (repeatedly 50000 #(.nextInt r 100000))))
                 :read (fn [v ^ints ks] (loop [i 0 s 0] (if (== i (alength ks)) s (recur (inc i) (+ s (nth v (aget ks i)))))))}]]
    (doseq [{:keys [label plain reads read]} shapes]
      (let [v (in-domain (fn [sio] (build-plain sio plain)))
            iv (img/image-value (img/open-image (img/image-bytes (img/detach-image v))))
            t-slab (bench-min-ms 3 7 #(read v reads))
            t-img (bench-min-ms 3 7 #(read iv reads))
            ratio (/ t-img (max 0.01 t-slab))]
        (println (format "  Z1.7 %s: slab %.2f ms, image %.2f ms, ratio %.2f" label t-slab t-img ratio))
        (is (<= ratio 1.2)
            (str label ": image reads within 1.2x of slab reads (was " (format "%.2f" ratio) "x)"))))))
