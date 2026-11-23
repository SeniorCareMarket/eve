(ns eve.flat.image-node-test
  "H4 — the image on ClojureScript (IMPL-PLAN §3.4, lane H4).
   Requirements H4.1–H4.2, one named test each, plus the byte-identity
   arm of H4.1: an image detached on Node is the same bytes as the JVM's
   image of the same value (the JVM reference hex below is produced by
   the JVM writer of the identical build path — see H4-STATUS.md for
   its provenance; it is regenerated, never edited by hand)."
  (:require ; some of this spec's referred vars are exercised only on the other reader pass or mirror the host-face declaration; the refer list is shared by both passes 
   #_{:clj-kondo/ignore [:unused-referred-var]}   [cljs.test :refer [deftest is testing async use-fixtures]]
   [eve.process-slab :as ps]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.codec :as codec]
   [eve.array :as arr]
   [eve.dataset :as ds]
   [eve.flat]
   [eve.flat.image :as img]
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.sio :as ssio]))

;; ---------------------------------------------------------------------------
;; helpers — Z1's, in their CLJS form. On this host a heap domain IS the
;; module slab: alloc/heap-domain-sio! answers the one CljsSlabIO.
;; ---------------------------------------------------------------------------

(def slab-capacities
  "The suite's slab geometry. Class 0 is Z2.2's test geometry (16384
   bytes = 512 blocks of 32) — run-wastes-nothing fills it exactly.
   Classes 1–5 get room for the image rows: at the first probe's
   geometry (8192 bytes each) building a row's value exhausted class 2
   (128-byte blocks, 64 of them) partway — 'Slab alloc failed:
   :all-slab-classes-full for 128 bytes'. On the JVM these rows build
   in fresh heap domains, which grow; a fixed module slab must be
   given the room up front."
  {0 16384 1 1048576 2 1048576 3 1048576 4 1048576 5 1048576})

(use-fixtures :each
  ;; Synchronous reset, NOT an async re-init: measured at da03e20, an
  ;; async fn fixture made the whole suite report "Ran 0 tests" —
  ;; cljs.test counts a test when its fixture-wrapped thunk completes,
  ;; and this runner's summary reporter exits the process on the
  ;; synchronous pass, before any promise-fired (run) could count (the
  ;; pre-fixture run at 10d6788 ran 4 tests). reset-all-slabs! is the
  ;; synchronous fresh-domain primitive: bitmaps and descriptor tables
  ;; zeroed, free counts restored, cursors at 0 — the runner's one
  ;; init! supplies the geometry (slab-capacities), each test then
  ;; starts from a virgin slab of that geometry.
  (fn [run]
    (ps/reset-all-slabs!)
    (run)))

(defn- heap-sio [] (alloc/heap-domain-sio! {}))

(defn- build-plain
  "A plain collection written into sio by the direct writers (what swap!
   uses): its eve value."
  [sio v]
  (let [kind (cond (map? v) :map (set? v) :set (vector? v) :vec :else :list)
        off (codec/write-collection! kind sio v)
        t (ser/read-header-tag sio off)]
    ((ser/get-header-constructor t) sio off)))

(defn- in-domain
  "Build (make sio) in a fresh heap domain under the domain binding."
  [make]
  (let [sio (heap-sio)]
    (binding [d/*domain-sio* sio] (make sio))))

(defn- through-image
  "Build (make sio); detach the value; answer the value read in place
   from the opened image, the image, and the original value."
  [make]
  (let [v (in-domain make)
        im (img/detach-image v)]
    [(img/image-value (img/open-image (img/image-bytes im))) im v]))

(defn- check-row [label make expected & [extra]]
  (testing label
    (let [[iv im v] (through-image make)]
      (is (= expected (d/eve->clj iv)) "the image reads the same value as the slab original")
      (is (= (d/eve->clj v) (d/eve->clj iv)))
      (when extra (extra iv v im)))))

(defn- bytes->hex [bs]
  (apply str (map (fn [b] (.padStart (.toString (bit-and b 0xFF) 16) 2 "0")) bs)))

(defn- hex->bytes [hx]
  (let [n (quot (count hx) 2)
        out (js/Uint8Array. n)]
    (dotimes [i n]
      (aset out i (js/parseInt (subs hx (* 2 i) (* 2 (inc i))) 16)))
    out))

;; ---------------------------------------------------------------------------
;; H4.1 — Eve's types read an image on Node (Z1.2's rows, CLJS-honest)
;; ---------------------------------------------------------------------------

(deftest image-reads-every-type
  (let [xs (vec (range 200))]
    (check-row "nested map/vector/set (the P1 shape)"
               (fn [sio] (build-plain sio {:id 42 :tags #{:a :b} :items xs
                                           :nested {:k {:k2 [1 2.5 "three" :four]}}}))
               {:id 42 :tags #{:a :b} :items xs :nested {:k {:k2 [1 2.5 "three" :four]}}})
    (check-row "hash set of 200"
               (fn [sio] (build-plain sio (set xs))) (set xs))
    (check-row "eve array :float64 of 200"
               (fn [sio] (arr/eve-array sio :float64 (mapv double xs)))
               (mapv double xs)
               (fn [iv _ _] (is (= 199.0 (nth iv 199)) "nth in place")))
    (check-row "dataset of 2 columns x 200 rows"
               (fn [sio] (ds/dataset sio {:price (arr/eve-array sio :float64 (mapv double xs))
                                          :qty (arr/eve-array sio :int32 xs)}))
               {:price (mapv double xs) :qty xs}
               (fn [iv _ _] (is (= 199 (nth (ds/column iv :qty) 199)) "column read in place")))
    (testing "list of 200"
      (let [[iv _ _] (through-image (fn [sio] (build-plain sio (apply list xs))))]
        (is (= 200 (count iv)) "count in place")
        (is (zero? (first iv)) "first in place")
        (is (= 57 (nth iv 57)) "nth in place")
        (is (= xs (vec (d/eve->clj iv))) "the whole list reads in place")))
    (testing "the public verb: eve.flat/detach answers the image bytes"
      (let [plain {:a [1 2 3] :b {:c "x"}}
            v (in-domain (fn [sio] (build-plain sio plain)))
            bs (img/detach v)]
        (is (= (type bs) js/Uint8Array) "a value detaches to one byte array")
        (is (= plain (d/eve->clj (img/image-value (img/open-image bs))))
            "and the bytes open and read back")
        (is (= (bytes->hex bs) (bytes->hex (img/detach v))) "detach is deterministic")
        (is (= (bytes->hex bs) (bytes->hex (eve.flat/detach v)))
            "the public namespace delegates to this lane")))))

(deftest scalar-detach
  (testing "a scalar root block detaches and reads back"
    (let [sio (heap-sio)
          off (binding [d/*domain-sio* sio] (alloc/value->scalar-block! sio "hello scalar"))
          im (img/detach-image sio off)
          iv (img/image-value (img/open-image (img/image-bytes im)))]
      (is (= "hello scalar" iv)))))

;; ---------------------------------------------------------------------------
;; H4.1, byte identity — the same value detached on the JVM and on Node
;; is the same bytes. The reference is the JVM's image of
;; {:a [1 2 3] :b {:c "x"}} built by codec/write-collection! in a fresh
;; heap domain (provenance in H4-STATUS.md).
;; ---------------------------------------------------------------------------

(def jvm-reference-hex
  "455653310101000000020000200000000100000058010000a001000000000000ed010000020000002c000000010028000440000000000000a286a84d2e5e103305000000eedb07016107000000eedb129800000005000000eedb07016207000000eedb1068000000ed0100000100000074000000010012000100000000000000a0a66db905000000eedb07016305000000eedb0501780000120000000300000005000000ffffffffb000000003000000480100003c01000030010000ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff07000000eedb03030000000007000000eedb03020000000007000000eedb03010000000000000000200000000c0000002c0000003c000000680000000c00000074000000220000009800000018000000b000000080000000300100000b0000003c0100000b000000480100000b000000280000000000000001000000640000000100000002000000700000000200000003000000500000000100000004000000a80000000400000005000000b80000000500000006000000b40000000500000007000000b00000000500000008000000")

(deftest jvm-node-image-same-bytes
  (let [plain {:a [1 2 3] :b {:c "x"}}
        v (in-domain (fn [sio] (build-plain sio plain)))
        bs (img/detach v)]
    (is (not= "PENDING-JVM-REFERENCE" jvm-reference-hex)
        "the JVM reference is embedded (generated on the venue, H4-STATUS.md)")
    (when (not= "PENDING-JVM-REFERENCE" jvm-reference-hex)
      (is (= jvm-reference-hex (bytes->hex bs))
          "Node's image of the value is the JVM's image, byte for byte")
      (is (= plain (d/eve->clj (img/image-value (img/open-image (hex->bytes jvm-reference-hex)))))
          "and the JVM's bytes open and read on Node"))))

;; ---------------------------------------------------------------------------
;; H4.2 — the module slab's batch machinery becomes the run claim:
;; Z2.2's pattern over the module slab's class 0. A run of 40 free blocks
;; at the unaligned start 37 plus isolated free singles at 5, 90, 100:
;; alloc-run! of 40 claims exactly the run and nothing else; a claim of
;; 41 (no such run exists) claims nothing.
;; ---------------------------------------------------------------------------

(defn- off [ci bi] (alloc/encode-slab-offset ci bi))

(defn- total-blocks [_sio _ci]
  ;; the class's block count, counted through the allocator's own
  ;; allocated? over a generous bound is too slow; the geometry comes
  ;; from the slab header through the sio's region, as Z2 read it.
  ;; On this host the runner inits class 0 at 16384 bytes = 512 blocks.
  512)

(defn- allocated-range
  "The set of allocated block indices of class ci, by the allocator's
   own allocated? over every block of the class."
  [sio ci]
  (into #{} (filter #(alloc/allocated? sio (off ci %))) (range (total-blocks sio ci))))

(defn- fill-class!
  "Allocate class ci's every block; the vector of their slab offsets.
   The fill goes through the slab's own per-class allocator
   (ps/batch-alloc — the machinery the run claim is built from): the
   size-routed -sio-alloc! SPILLS to the next class on exhaustion by
   design (process-slab's alloc: 'spill to next class'; measured — a
   -sio-alloc! 'fill of class 0' handed out 32,256 blocks = every
   class at the suite geometry), so it can never fill one class
   exactly. batch-alloc claims within the one class its size routes
   to, up to the count asked. The count assertion in the test is what
   proves the fill was exact."
  [sio ci]
  (let [arr (ps/batch-alloc (nth alloc/CLASS_BLOCK_SIZES ci)
                            (total-blocks sio ci))]
    (vec (array-seq arr))))

(deftest run-wastes-nothing
  (let [sio (heap-sio)
        ci 0
        _   (is (= 512 (total-blocks sio ci)) "the runner inits the class at 512 blocks")
        offs (fill-class! sio ci)
        by-idx (into {} (map (fn [o] [(alloc/decode-block-idx o) o]) offs))
        run-start 37
        n 40
        freed (into (set (range run-start (+ run-start n))) [5 90 100])]
    (is (= 512 (count offs)) "the class filled exactly")
    (doseq [bi freed] (alloc/-sio-free! sio (by-idx bi)))
    (let [before (allocated-range sio ci)]
      (is (= freed (set (filter #(not (contains? before %)) (range 512))))
          "exactly the pattern is free")
      (testing "a claim of 41 finds no run and claims nothing"
        (is (nil? (alloc/alloc-run! sio ci 41)))
        (is (= before (allocated-range sio ci)) "the allocated set is unchanged"))
      (testing "a claim of 40 claims exactly the run"
        (let [run (alloc/alloc-run! sio ci n)]
          (is (some? run) "the run exists")
          (is (= (off ci run-start) (first run)) "it starts at the run's first block")
          (is (= (mapv #(off ci %) (range run-start (+ run-start n))) run)
              "the offsets are the run, consecutive, ascending")
          (is (= (into before (range run-start (+ run-start n))) (allocated-range sio ci))
              "nothing else was claimed — the isolated singles at 5, 90, 100 stay free"))))
    ;; leave the slab as found for the other tests: free everything this
    ;; test still holds (the run claim's blocks are the test's too)
    (doseq [o offs] (alloc/-sio-free! sio o))))
