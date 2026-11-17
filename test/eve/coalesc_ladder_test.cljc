(ns eve.coalesc-ladder-test
  "The unified ladder inside class 6 (ladder-on-eve lane): rung blocks
   are fixed-size class-6 blocks stamped in their descriptor's
   LOCK_OWNER bits 3-6 (rung-idx + 1); the pointer format is
   untouched. These tests pin: (A) the slab-offset encoding contract,
   unchanged; (B) rung alloc/free semantics at the coalesc level —
   carve exactly, reuse by rung, never a variable candidate, never
   coalesced on either side; (C) the flat door's routing on the JVM
   ctx, refusal included; (D) values written before rung traffic
   still read after it."
  (:require
   #?(:cljs [cljs.test :refer-macros [deftest testing is]]
      :clj  [clojure.test :refer [deftest testing is]])
   [eve.array :as arr]
   [eve.atom :as eatom]
   [eve.core :as e]
   [eve.deftype-proto.alloc :as alloc]
   [eve.deftype-proto.coalesc :as coalesc]
   [eve.deftype-proto.data :as d]
   [eve.encoding :as enc]
   [eve.flat.freeze :as freeze]
   [eve.flat.sister]
   [eve.mem :as mem]
   [eve.region :as region]
   [eve.slab-io :as slab])
  #?(:clj (:import [eve.deftype_proto.alloc JvmSlabCtx])))

;; ---------------------------------------------------------------------------
;; A. The pointer format contract (unchanged by the ladder)

(deftest slab-offset-encoding-unchanged
  (doseq [ci [0 1 2 3 4 5 6]
          bi [0 1 12345 536870910]]
    (let [off (alloc/encode-slab-offset ci bi)]
      (is (= ci (alloc/decode-class-idx off)))
      (is (= bi (alloc/decode-block-idx off)))))
  (testing "a class 6 offset's block index is its data-region byte offset"
    (is (= 231072 (alloc/decode-block-idx (alloc/encode-slab-offset 6 231072)))))
  (testing "NIL is class 7, block all-ones"
    (is (= 7 (alloc/decode-class-idx alloc/NIL_OFFSET)))
    (is (= 536870911 (alloc/decode-block-idx alloc/NIL_OFFSET))))
  (testing "the rung table is the kernel's coarse table"
    (is (= 131072 (coalesc/rung-size 0)))
    (is (= 983040 (coalesc/rung-size 13)))
    (is (= 0 (coalesc/rung-idx-for-size 131072)))
    (is (= 13 (coalesc/rung-idx-for-size 983040)))
    (is (= -1 (coalesc/rung-idx-for-size 65536)))
    (is (= -1 (coalesc/rung-idx-for-size 100000)))
    (is (= -1 (coalesc/rung-idx-for-size 1048576)))))

;; ---------------------------------------------------------------------------
;; B. Rung semantics at the coalesc level (portable: JVM + JS)

(defn- fresh-region
  "A coalesc region of data-size bytes with max-desc descriptors."
  [data-size max-desc]
  (let [layout (coalesc/coalesc-layout data-size max-desc)
        r      (region/make-heap-region (:total-bytes layout))]
    (coalesc/init-coalesc-region! r data-size max-desc)
    r))

(defn- rung-field-of
  "The rung field (rung-idx + 1, 0 = variable) of the allocated block
   at data-off in region r, read through its descriptor row's flags
   word — the same word eve.tally reads its flag bits from."
  [r data-off]
  (let [[_co fo _po] (coalesc/row-of r data-off)]
    (bit-shift-right (bit-and (mem/-load-i32 r fo) coalesc/RUNG_FIELD_MASK)
                     coalesc/RUNG_FIELD_SHIFT)))

(deftest rung-carve-reserve-reuse
  (let [r (fresh-region (* 1024 1024) 64)
        v1   (coalesc/coalesc-alloc! r 100000)
        r1   (coalesc/try-coalesc-alloc-rung! r 0)
        vbig (coalesc/coalesc-alloc! r 817504)]
    (testing "carve: offsets are sequential and exact"
      (is (= 0 v1))
      (is (= 100000 r1))
      (is (= 231072 vbig))
      (is (= 1 (rung-field-of r r1)))
      (is (= 0 (rung-field-of r v1)))
      (is (= 0 (rung-field-of r vbig))))
    (is (true? (coalesc/coalesc-free! r v1)))
    (is (true? (coalesc/coalesc-free! r r1)))
    (testing "a FREE rung block is reserved: no variable alloc takes it,
              even at exactly its size, even though it is the only fit"
      (is (nil? (coalesc/try-coalesc-alloc! r 131072))))
    (testing "the rung takes its own block back"
      (is (= 100000 (coalesc/try-coalesc-alloc-rung! r 0))))
    (is (true? (coalesc/coalesc-free! r 100000)))
    (testing "a variable free does not merge into a FREE rung neighbour"
      (is (true? (coalesc/coalesc-free! r vbig)))
      (is (nil? (coalesc/try-coalesc-alloc! r 948576)))
      (is (= 231072 (coalesc/try-coalesc-alloc! r 817504)))
      (is (= 100000 (coalesc/try-coalesc-alloc-rung! r 0))))))

(deftest rung-top-and-small-in-one-region
  (let [r (fresh-region (* 2 1024 1024) 64)
        top (coalesc/try-coalesc-alloc-rung! r 13)]
    (is (= 0 top))
    (is (= 14 (rung-field-of r top)))
    (is (true? (coalesc/coalesc-free! r top)))
    (testing "the top rung is reused whole, and a small rung carves after it"
      (is (= 0 (coalesc/try-coalesc-alloc-rung! r 13)))
      (is (= 983040 (coalesc/try-coalesc-alloc-rung! r 0))))))

;; ---------------------------------------------------------------------------
;; C. The flat door on the JVM ctx

#?(:clj
   (deftest flat-door-routing
     (let [sio (alloc/heap-domain-sio!
                {:capacities {0 (* 1024 1024) 1 (* 1024 1024) 2 (* 1024 1024)
                              3 (* 4 1024 1024) 4 (* 1024 1024) 5 (* 1024 1024)}})
           small (alloc/alloc-flat-block! sio 65536)
           rung1 (alloc/alloc-flat-block! sio 131072)
           big   (alloc/alloc-flat-block! sio 1048576)]
       (testing "small and raw-tier sizes allocate through the ordinary path"
         (is (some? small))
         (is (some? big)))
       (testing "a whole-chunk size lands in class 6 as a rung block"
         (is (= 6 (alloc/decode-class-idx rung1))))
       (testing "a non-chunk-aligned size above 64 KiB is refused"
         (let [ex (try (alloc/alloc-flat-block! sio 100000)
                       nil
                       (catch clojure.lang.ExceptionInfo ex ex))]
           (is (some? ex))
           (is (= -3 (:refusal (ex-data ex))))))
       (testing "bytes round-trip through the rung block"
         (let [payload (byte-array (map (fn [i] (mod (* i 31) 256)) (range 131072)))]
           (alloc/-sio-write-bytes! sio rung1 0 payload)
           (is (= (seq payload)
                  (seq (alloc/-sio-read-bytes sio rung1 0 131072))))))
       (testing "a freed rung block is invisible to the ordinary path
                 and is the door's next block of its rung"
         (is (true? (alloc/-sio-free! sio rung1)))
         (is (not= rung1 (alloc/-sio-alloc! sio 131072)))
         (is (= rung1 (alloc/alloc-flat-block! sio 131072)))))))

;; ---------------------------------------------------------------------------
;; E. A frozen chain at the ladder stripe (JVM: the descriptor peek
;;    needs the ctx's class-6 region)

#?(:clj
   (deftest freeze-chain-at-the-ladder-stripe
     (let [sio (alloc/heap-domain-sio!
                {:capacities {0 (* 1024 1024) 1 (* 1024 1024) 2 (* 1024 1024)
                              3 (* 4 1024 1024) 4 (* 1024 1024) 5 (* 1024 1024)}})
           ctx      (slab/domain-ctx sio)
           payload  (let [b (byte-array 2000000)]
                      (dotimes [i 2000000]
                        (aset b i (unchecked-byte (mod (* i 13) 256))))
                      b)
           hdr      (freeze/write-chain! sio payload)
           ;; fetched after the write: heap class-6 growth replaces
           ;; the region object, so an earlier handle would be stale
           region6  (aget ^objects (.regions ^JvmSlabCtx ctx) 6)
           seg0     (alloc/-sio-read-i32 sio hdr 8)
           seg1     (alloc/-sio-read-i32 sio seg0 0)
           seg2     (alloc/-sio-read-i32 sio seg1 0)
           field-of (fn [slab-off]
                      (let [[_co fo _po] (coalesc/row-of region6
                                                          (alloc/decode-block-idx slab-off))]
                        (bit-shift-right
                         (bit-and (mem/-load-i32 region6 fo) coalesc/RUNG_FIELD_MASK)
                         coalesc/RUNG_FIELD_SHIFT)))]
       (testing "the chain is two rung segments and a variable tail"
         (is (= 6 (alloc/decode-class-idx seg0)))
         (is (= 14 (field-of seg0)))
         (is (= 14 (field-of seg1)))
         (is (= 0 (field-of seg2)))
         (is (= -1 (alloc/-sio-read-i32 sio seg2 0)))
         (is (= 983032 (alloc/-sio-read-i32 sio seg0 4)))
         (is (= 33936 (alloc/-sio-read-i32 sio seg2 4))))
       (testing "the gathered bytes are the payload, byte for byte"
         (is (= (seq payload) (seq (freeze/read-chain-bytes sio hdr)))))
       (testing "dispose frees the rungs back to their rung: the door's
                 next top-rung block is one of the two disposed ones
                 (the reuse scan is in descriptor order, not LIFO)"
         (freeze/dispose-chain! sio hdr)
         (is (contains? #{(alloc/decode-block-idx seg0)
                          (alloc/decode-block-idx seg1)}
                        (alloc/decode-block-idx
                         (alloc/alloc-flat-block! sio 983040))))))))

;; ---------------------------------------------------------------------------
;; D. Values written before allocator churn still read after it

(deftest existing-values-survive-churn
  (binding [d/*domain-sio* (alloc/heap-domain-sio!
                            {:capacities {0 (* 1024 1024) 1 (* 1024 1024)
                                          2 (* 1024 1024) 3 (* 4 1024 1024)
                                          4 (* 1024 1024) 5 (* 1024 1024)}})]
    (let [sio      d/*domain-sio*
          payload  (let [b (enc/make-bytes 5000)]
                     (dotimes [i 5000]
                       (enc/typed-aset! b 1 (int i) (mod (* i 17) 256)))
                     b)
          hdr      (freeze/write-chain! sio payload)
          ints     (arr/eve-array :int32 (range 50000))
          before-i (e/nth ints 49999)]
      (dotimes [_ 5]
        (doseq [n [131072 262144 983040 100000]]
          (let [off (alloc/-sio-alloc! sio n)]
            (alloc/-sio-write-i32! sio off 0 -559030611)
            (is (= -559030611 (alloc/-sio-read-i32 sio off 0)))
            (alloc/-sio-free! sio off))))
      (testing "the frozen chain and the class-6 array read as written"
        (is (= (reduce + (map (fn [i] (bit-and (aget payload i) 0xFF)) (range 5000)))
               (reduce + (map (fn [i] (bit-and (aget (freeze/read-chain-bytes sio hdr) i) 0xFF))
                              (range 5000)))))
        (is (= 50000 (e/count ints)))
        (is (= before-i (e/nth ints 49999)))
        (is (= 0 (e/nth ints 0)))))))

;; ---------------------------------------------------------------------------
;; F. An atom value whose frozen form spans the rungs: freeze, install,
;;    deref, replace, deref again (JVM)

#?(:clj
   (deftest atom-deref-across-the-rungs
     (let [freeze (resolve 'eve.flat.sister/freeze)
           thaw   (resolve 'eve.flat.sister/thaw)]
       (is (some? freeze) "eve.flat.sister/freeze does not resolve")
       (when freeze
         (let [block (apply str (repeat 1000 "z"))
               ;; a vector holding a ~1.5 MB string (a bare string
               ;; is a scalar to the packer and is refused by
               ;; design): the frozen body is past the 983,032
               ;; stripe, so the chain is rung segment(s) + a tail.
               ;; Assertions compare through booleans/counts so a
               ;; failure never prints the megabyte values.
               v   [(apply str (repeat 1500 block))]
               a   (eatom/atom {})
               sio (eatom/atom-sio a)
               f   (@freeze sio v)]
           (is (true? (= v (@thaw f))) "thaw across the rungs differs")
           (reset! a f)
           (is (true? (= v @a)) "deref across the rungs differs")
           (is (= 1500000 (count (first @a))))
           (let [v2 [(apply str (repeat 1400 block))]
                 f2 (@freeze sio v2)]
             (reset! a f2)
             (is (true? (= v2 @a)) "deref after replacement differs")))))))
