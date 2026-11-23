(ns eve.flat.build-test
  "Lane B1's requirement tests (IMPL-PLAN §3.3 lane B1; requirements.edn
   B1.1-B1.4). The builder under test is eve.flat.build: plain data ->
   blocks in final layout in one pass, every byte reachable, out as an
   image with both tables."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.atom :as ea]
            [eve.codec :as codec]
            [eve.crypto :as crypto]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.retire :as retire]
            [eve.flat.build :as build]
            [eve.flat.contract :as contract]
            [eve.flat.corpora :as corpora])
  ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
  #?(:clj (:import #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays])))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- digest-of
  "The root digest of a value, computed the way eve.digest computes it:
   SHA-256 over the value's flat bytes (eve.codec/value->eve-bytes)."
  [v]
  (mapv (fn [b] (bit-and b 0xFF))
        (crypto/sha256 (mapv (fn [b] (bit-and b 0xFF)) (codec/value->eve-bytes v)))))

(defn- heap-sio [] (alloc/heap-domain-sio! {}))

(defn- today-value
  "v built by today's writer into a fresh heap domain: the registered
   collection writer for a collection root (what swap! uses), else
   convert-to-eve. Returns the Eve value."
  [v]
  (let [sio (heap-sio)]
    (if (or (map? v) (set? v) (vector? v) (list? v) (seq? v))
      (let [tag (cond (map? v) :map (set? v) :set (vector? v) :vec :else :list)
            off (codec/write-collection! tag sio v)
            ctor (ser/get-header-constructor (ser/read-header-tag sio off))]
        (ctor sio off))
      (ser/convert-to-eve sio v))))

(def ^:private hand-values
  [{}
   []
   #{}
   ()
   {:a 1}
   {:a {:b {:c [1 2 #{"x" :y} ()]}}}
   [1 "two" :three 4.5 nil true [6] {:seven 7} #{8} (list 9 10)]
   (zipmap (range 200) (range 200))            ;; a HAMT past one level
   (into #{} (range 200))
   (vec (range 1000))                          ;; a trie past one level
   (apply list (range 50))
   {:deep (vec (map (fn [i] {:i i :s (str "s" i) :v (vec (range (mod i 7)))})
                    (range 60)))}
   {[:a :b] 1 "string key" 2 42 3 [1 2] 4}])    ;; collection keys (flat-encoded)

(def ^:private corpus-values
  (mapv (fn [kind] (corpora/build kind (get corpora/tiny-sizes kind)))
        corpora/corpus-order))

(def ^:private test-values (into hand-values corpus-values))

;; ---------------------------------------------------------------------------
;; B1.1 — equal, same root digest, updatable
;; ---------------------------------------------------------------------------

(deftest direct-build-equal-and-updatable
  (doseq [v test-values]
    (let [b (build/build! v)
          ev (:value b)
          tv (today-value v)]
      (is (= v (d/eve->clj ev)) "the built value materializes to the source")
      (is (= ev v) "built = plain")
      (is (= v ev) "plain = built")
      (is (= tv ev) "built = today's writer's value")
      (is (= (hash ev) (hash v)) "hash agrees with the plain value")
      (is (= (digest-of ev) (digest-of v)) "root digest of built = of plain")
      (is (= (digest-of tv) (digest-of ev)) "root digest of built = of today's")))
  (testing "a further assoc on the built value; the original is untouched"
    (let [v (zipmap (range 200) (range 200))
          b (build/build! v)
          ev (:value b)
          ev2 (assoc ev 200 :new :extra "x")]
      (is (= (assoc v 200 :new :extra "x") (d/eve->clj ev2)))
      (is (= v (d/eve->clj ev)) "the pre-assoc view still reads the old value")))
  (testing "a further conj on a built vector"
    (let [v (vec (range 100))
          ev (:value (build/build! v))]
      (is (= (conj v 100) (d/eve->clj (conj ev 100))))))
  (testing "scalar roots"
    (doseq [v [42 "hello" :kw 'sym 4.5 true nil]]
      (let [b (build/build! v)]
        (is (= v (:value b)))
        (is (= (digest-of v) (digest-of (:value b))))))))

;; ---------------------------------------------------------------------------
;; B1.2 — every byte written is reachable; no compaction pass
;; ---------------------------------------------------------------------------

(deftest direct-build-no-garbage
  (doseq [v (into test-values [(zipmap (range 10000) (range 10000))
                               (into #{} (range 5000))])]
    (let [b (build/build! v)
          sio (:sio b)
          root (:root b)
          reached (set (map (fn [o] (bit-and o 0xFFFFFFFF)) (retire/reach sio root)))
          allocated (set (map first (build/allocations sio)))]
      (is (= allocated reached)
          "the allocation set is exactly the reached set — no dead block, no compaction")
      (is (= (count allocated) (build/alloc-count sio))))))

;; ---------------------------------------------------------------------------
;; B1.3 — the output is an image with both tables, and it places
;; ---------------------------------------------------------------------------

(defn- place-image
  "The bench's placement, in miniature (per-block through alloc-run!):
   place image im in sio, return the placed root offset."
  [sio {:keys [bytes blocks relocations]}]
  (let [n (count blocks)
        new-offs (long-array n)
        class-of (fn [size] (loop [c 0] (cond (== c 6) 6
                                              (<= size (nth alloc/CLASS_BLOCK_SIZES c)) c
                                              :else (recur (inc c)))))
        by-class (group-by (fn [i] (class-of (:size (nth blocks i)))) (range n))]
    (doseq [[c idxs] by-class]
      (if (== c 6)
        (doseq [i idxs] (aset new-offs i (alloc/-sio-alloc! sio (:size (nth blocks i)))))
        (doseq [[i o] (map vector idxs (alloc/alloc-run! sio c (count idxs)))]
          (aset new-offs i (long o)))))
    (doseq [i (range n)
            :let [{:keys [offset size]} (nth blocks i)]]
      (alloc/-sio-write-bytes! sio (aget new-offs i) 0
                               (Arrays/copyOfRange ^bytes bytes (int offset) (int (+ offset size)))))
    (doseq [{:keys [position owner target]} relocations]
      (alloc/-sio-write-i32! sio (aget new-offs owner)
                             (- (long position) (:offset (nth blocks owner)))
                             (aget new-offs target)))
    (aget new-offs 0)))

(defn- retire-edges
  "The [owner target] offset pairs eve.retire/children finds over the value
   at root in sio (the independent derivation, as in lane Z1's tests)."
  [sio root]
  (loop [stack [[:val root]] seen #{} edges []]
    (if (empty? stack)
      edges
      (let [[k o] (peek stack)
            stack (pop stack)
            key (bit-and o 0xFFFFFFFF)]
        (if (contains? seen key)
          (recur stack seen edges)
          (let [kids (retire/children sio k o)]
            (recur (into stack (map (fn [[_ ck c]] [ck c])) kids)
                   (conj seen key)
                   (into edges (map (fn [[_ _ c]] [o c])) kids))))))))

(deftest direct-build-is-placeable
  (doseq [v test-values]
    (let [b (build/build! v)
          im (build/image b)
          {:keys [bytes blocks relocations root]} im
          hdr (contract/read-header bytes 0)]
      (is (= :image (:kind hdr)) "a kind-1 chunk")
      (is (= 1 (contract/layout-id hdr)) "canonical layout")
      (is (= (alength ^bytes bytes) (:length hdr)) "the header's length is the chunk's")
      (is (= root (:offset (first blocks))) "root first in the block table")
      (is (= blocks (contract/read-block-table bytes (:symtab hdr) (count blocks)))
          "the block table round-trips through the contract")
      (is (= relocations (contract/read-relocation-table bytes (:shapetab hdr) (count relocations)))
          "the relocation table round-trips through the contract")
      (is (= (frequencies (retire-edges (:sio b) (:root b)))
             (frequencies (map (fn [r] [(:offset (nth blocks (:owner r)))
                                        (:offset (nth blocks (:target r)))])
                               relocations)))
          "the relocation table names every pointer eve.retire/children finds")
      (is (every? (fn [r] (= (:offset (nth blocks (:target r)))
                             (build/read-i32-at bytes (:position r))))
                  relocations)
          "every relocation's field position holds its target's offset in the image")
      (testing "and it actually places: into a domain, through a swap!, reads back"
        (let [dom (ea/atom-domain nil :capacities {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})
              a (ea/lookup-or-create-mmap-atom! dom "b1/place" nil)]
          (swap! a (fn [_] (let [sio d/*domain-sio*
                                 off (place-image sio im)
                                 ctor (ser/get-header-constructor (ser/read-header-tag sio off))]
                             (ctor sio off))))
          (is (= v @a) "the placed value reads back equal through the atom")
          (swap! a (fn [cur] (if (map? cur) (assoc cur :b1/after 1) cur)))
          (is (= (if (map? v) (assoc v :b1/after 1) v) @a)
              "a further write on top of the placed value is sound"))))))

;; ---------------------------------------------------------------------------
;; B1.4 — a type it does not cover falls back to today's writer, and says so
;; ---------------------------------------------------------------------------

(deftest direct-build-fallback
  (testing "a host array element: written by today's array writer, recorded"
    (let [v {:data (#_{:clj-kondo/ignore [:unresolved-symbol]} byte-array (map unchecked-byte [1 2 3])) :n 1}
          b (build/build! v)]
      (is (seq (:fallbacks b)) "the fallback is said")
      (is (some (fn [f] (= :host-array (:reason f))) (:fallbacks b)))
      (is (= 1 (get (:value b) :n)))
      (is (= [1 2 3] (map (fn [x] (bit-and x 0xFF)) (get (d/eve->clj (:value b)) :data))))))
  (testing "a value with no eve bytes (a fn): written by reference, recorded"
    (let [f inc
          v {:handler f :n 2}
          b (build/build! v)]
      (is (some (fn [x] (= :reference (:reason x))) (:fallbacks b)))
      (is (= 2 (get (:value b) :n)))
      (is (identical? f (get (d/eve->clj (:value b)) :handler))
          "the reference names the same fn in this process")))
  (testing "an Eve value of another domain: materialized and rebuilt, recorded"
    (let [sio (heap-sio)
          inner (ser/convert-to-eve sio [1 2 3])
          v {:inner inner :n 3}
          b (build/build! v)]
      (is (some (fn [x] (= :eve-value (:reason x))) (:fallbacks b)))
      (is (= {:inner [1 2 3] :n 3} (d/eve->clj (:value b))))))
  (testing "a covered value records no fallback"
    (is (empty? (:fallbacks (build/build! {:a [1 2 #{"x"}]}))))))
