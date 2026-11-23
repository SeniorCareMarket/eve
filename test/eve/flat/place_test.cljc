(ns eve.flat.place-test
  "Lane Z3 requirement tests (IMPL-PLAN §3.3, the zip): Z3.1 place-reads-back-and-updates,
   Z3.2 place-refuses-bad-tables, Z3.3 place-fallback-equal, Z3.4 place-exact-on-loss-and-throw,
   Z3.5 place-large-blocks, Z3.6 place-encrypted, Z3.7 place-by-units.

   Hand-built images: tests build an image the way the research probes did
   (doc/flat/probes/lib.clj) — the value is written into an append-only
   bump slab I/O by Eve's own writers, then a relocating walk copies exactly
   the reachable blocks into one byte array with the block table and the
   relocation table. That walk is test code here, adapted from the probe;
   the product path (eve.flat.place) sees only the finished image, as the
   FORMAT-V1 §9 chunk bytes or as the equivalent map. Table surgery (Z3.2)
   needs the hand-built form — detach emits only well-formed images; Z3.1's
   end-to-end arm places a real detach instead. Chunk bytes reach place
   only as an eve.flat.check CheckedBytes handle (lane C4's contract):
   raw byte arrays are refused :unchecked-bytes before any parsing."
  (:require ; the test-framework require (clojure.test on :clj, cljs.test on :cljs); its referred macros are this file's test surface 
   #_{:clj-kondo/ignore [:unused-namespace :unused-referred-var]}   [clojure.test :refer [deftest is testing]]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.check :as check]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.contract :as contract]
            ; required for load as part of this namespace's cone (Eve's type/protocol registrations are load-time effects); no vars are referenced from it here 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.image :as image]
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.place :as place]
   #?(:clj [eve.atom :as ea])
   #?(:clj [eve.codec :as codec])
   #?(:clj ; part of the #?(:clj ...) cone block below: it loads the JVM writer cone these hand-built-image tests drive; registrations are load-time effects 
      #_{:clj-kondo/ignore [:unused-namespace]}   [eve.core :as e])
   #?(:clj [eve.deftype-proto.alloc :as alloc])
   #?(:clj [eve.deftype-proto.data :as d])
   #?(:clj [eve.deftype-proto.serialize :as ser])
   #?(:clj [eve.keyring :as kr])
   #?(:clj [eve.mem :as mem])
   #?(:clj ; part of the #?(:clj ...) cone block: it loads the JVM writer cone these hand-built-image tests drive; registrations are load-time effects 
      #_{:clj-kondo/ignore [:unused-namespace]}   [eve.process :as proc])
   #?(:clj [eve.retire :as retire])
   #?(:clj [eve.flat :as flat]))
  #?(:clj (:import #_{:clj-kondo/ignore [:unused-import]} ; Arrays is used below; the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use [java.util Arrays ArrayDeque]
           [eve.deftype_proto.alloc JvmSlabCtx])))

;; ===========================================================================
;; Hand-built images (test code, adapted from doc/flat/probes/lib.clj)
;; ===========================================================================

#?(:clj
   (do

     (defn- i32 ^long [^bytes a ^long i]
       (let [i (int i)]
         (long (unchecked-int
                (bit-or (bit-and (long (aget a i)) 0xFF)
                        (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 1))) 0xFF) 8)
                        (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 2))) 0xFF) 16)
                        (bit-shift-left (bit-and (long (aget a (unchecked-add-int i 3))) 0xFF) 24))))))

     (defn- put-i32! [^bytes a ^long i ^long v]
       (let [i (int i)]
         (aset a i (unchecked-byte v))
         (aset a (unchecked-add-int i 1) (unchecked-byte (bit-shift-right v 8)))
         (aset a (unchecked-add-int i 2) (unchecked-byte (bit-shift-right v 16)))
         (aset a (unchecked-add-int i 3) (unchecked-byte (bit-shift-right v 24)))
         nil))

     (defn- u8 ^long [^bytes a ^long i] (bit-and (long (aget a (int i))) 0xFF))

     (definterface IBump
       (^bytes buf [])
       (^long tail [])
       (^long sizeOf [^long off]))

     (deftype BumpSio [^:unsynchronized-mutable ^bytes b
                       ^:unsynchronized-mutable ^long t
                       ^:unsynchronized-mutable ^long na
                       ^:unsynchronized-mutable ^ints offs
                       ^:unsynchronized-mutable ^ints szs]
       IBump
       (buf [_] b)
       (tail [_] t)
       (sizeOf [_ off]
         (let [i (Arrays/binarySearch offs 0 (int na) (int off))]
           (if (neg? i) -1 (long (aget szs i)))))
       alloc/ISlabIO
       (-sio-read-u8 [_ o f] (bit-and (aget b (int (+ (long o) (long f)))) 0xFF))
       (-sio-write-u8! [_ o f v] (aset b (int (+ (long o) (long f))) (unchecked-byte v)))
       (-sio-read-u16 [_ o f]
         (let [i (int (+ (long o) (long f)))]
           (bit-or (bit-and (aget b i) 0xFF) (bit-shift-left (bit-and (aget b (inc i)) 0xFF) 8))))
       (-sio-write-u16! [_ o f v]
         (let [i (int (+ (long o) (long f)))]
           (aset b i (unchecked-byte v))
           (aset b (inc i) (unchecked-byte (bit-shift-right (long v) 8)))))
       (-sio-read-i32 [_ o f] (i32 b (+ (long o) (long f))))
       (-sio-write-i32! [_ o f v] (put-i32! b (+ (long o) (long f)) (long v)))
       (-sio-cas-i32! [_ o f e dv]
         (let [p (+ (long o) (long f)) cur (i32 b p)]
           (when (== cur (long e)) (put-i32! b p (long dv)))
           cur))
       (-sio-read-bytes [_ o f n]
         (let [i (int (+ (long o) (long f)))]
           (Arrays/copyOfRange b i (int (+ i (long n))))))
       (-sio-write-bytes! [_ o f src]
         (System/arraycopy ^bytes src 0 b (int (+ (long o) (long f))) (alength ^bytes src)))
       (-sio-alloc! [_ n]
         (let [n (long n)
               off t
               end (+ off (bit-and (+ n 3) (bit-not 3)))]
           (when (> end (alength b))
             (set! b (Arrays/copyOf b (int (max end (* 2 (alength b)))))))
           (when (== na (alength offs))
             (set! offs (Arrays/copyOf offs (int (* 2 na))))
             (set! szs (Arrays/copyOf szs (int (* 2 na)))))
           (aset offs (int na) (int off))
           (aset szs (int na) (int n))
           (set! t end)
           (set! na (inc na))
           off))
       (-sio-free! [_ _o] true)
       (-sio-copy-block! [_ dd df s sf n]
         (System/arraycopy b (int (+ (long s) (long sf))) b (int (+ (long dd) (long df))) (int n))))

     (defn- bump-sio [cap]
       (BumpSio. (byte-array cap) 8 0 (int-array 4096) (int-array 4096)))

;; A delegating slab I/O whose nth -sio-alloc! throws: the failure-injection
;; seam for Z3.4's rollback arm. It rides the object because it must —
;; measured on this pin: with-redefs on the protocol fn alloc/-sio-alloc!
;; does not intercept compiled call sites (a var-deref call sees the stub,
;; direct calls never do, and place's claims are direct calls).
     (deftype FailNthAllocSio [inner calls ^long n]
       alloc/ISlabIO
       (-sio-read-u8 [_ o f] (alloc/-sio-read-u8 inner o f))
       (-sio-write-u8! [_ o f v] (alloc/-sio-write-u8! inner o f v))
       (-sio-read-u16 [_ o f] (alloc/-sio-read-u16 inner o f))
       (-sio-write-u16! [_ o f v] (alloc/-sio-write-u16! inner o f v))
       (-sio-read-i32 [_ o f] (alloc/-sio-read-i32 inner o f))
       (-sio-write-i32! [_ o f v] (alloc/-sio-write-i32! inner o f v))
       (-sio-cas-i32! [_ o f e dv] (alloc/-sio-cas-i32! inner o f e dv))
       (-sio-read-bytes [_ o f len] (alloc/-sio-read-bytes inner o f len))
       (-sio-write-bytes! [_ o f src] (alloc/-sio-write-bytes! inner o f src))
       (-sio-alloc! [_ size]
         (if (== n (swap! calls inc))
           (throw (ex-info "mid-placement failure" {}))
           (alloc/-sio-alloc! inner size)))
       (-sio-free! [_ o] (alloc/-sio-free! inner o))
       (-sio-copy-block! [_ dd df s sf len] (alloc/-sio-copy-block! inner dd df s sf len))
       alloc/ISlabRunAlloc
       (-sio-alloc-run! [_ ci k] (alloc/-sio-alloc-run! inner ci k)))

     (defn reopen
       "The eve value whose header block is at off, read through sio."
       [sio off]
       (let [t (ser/read-header-tag sio off)
             ctor (ser/get-header-constructor t)]
         (ctor sio off)))

     (defn- build!
       "Plain collection v written into sio by the host's direct writer: its header offset."
       [sio v]
       (codec/write-collection! (cond (map? v) :map (set? v) :set (vector? v) :vec :else :list) sio v))

     (defn- pointer-tag? [^long tag]
       (or (<= 0x10 tag 0x13) (<= 0x1C tag 0x1F) (<= 0x40 tag 0xEC)))

     (defn- inline-ptr?
       "Do the bytes at p of a start an eve pointer [0xEE 0xDB tag off:i32]?"
       [^bytes a ^long p]
       (let [w (i32 a p)]
         (and (== 0xDBEE (bit-and w 0xFFFF)) (pointer-tag? (bit-and (bit-shift-right w 16) 0xFF)))))

     (defn- popcount ^long [^long x] (long (Integer/bitCount (unchecked-int x))))

     (defn- trie-live ^long [^long toff ^long base ^long sh]
       (let [n (- toff base)]
         (if (<= n 0) 0 (let [w (bit-shift-left 1 sh)] (min 32 (quot (+ n (dec w)) w))))))

     (defn- slot-of ^long [^long k ^long m]
       (let [h (unchecked-multiply k -7046029254386353131)]
         (bit-and (bit-xor h (unsigned-bit-shift-right h 32)) m)))

     (defrecord HandImage [^bytes img ^long len ^long root
                           ^ints boff ^ints bsz ^long nblocks
                           ^ints rpos ^ints rown ^ints rtgt ^long nrel])

     (defn- hand-image!
       "Every block the value at root of bump sio bs reaches, copied once into one
   byte array, pointers rewritten to positions in it; with the block table
   and the relocation table. The probe copier of doc/flat/probes/lib.clj,
   specialized to a bump source."
       [^BumpSio bs ^long root]
       (let [img (volatile! (byte-array (* 1024 1024)))
             tail (long-array 1)
             cap0 (int 65536)
             keys (volatile! (long-array cap0))
             vals (volatile! (int-array cap0))
             nkeys (long-array 1)
             boff (volatile! (int-array 4096))
             bsz (volatile! (int-array 4096))
             nb (long-array 1)
             rpos (volatile! (int-array 4096))
             rown (volatile! (int-array 4096))
             rtgt (volatile! (int-array 4096))
             nr (long-array 1)
             stack (ArrayDeque.)
             lookup (fn ^long [^long k]
                      (let [^longs ks @keys ^ints vs @vals m (dec (alength ks))]
                        (loop [i (slot-of k m)]
                          (let [x (aget ks i)]
                            (cond (zero? x) -1
                                  (== x k) (long (aget vs i))
                                  :else (recur (bit-and (inc i) m)))))))
             insert! (fn [^long k ^long v]
                       (when (> (* 2 (aget nkeys 0)) (alength ^longs @keys))
                         (let [^longs ok @keys ^ints ov @vals n2 (* 2 (alength ok))
                               nk (long-array n2) nv (int-array n2) m (dec n2)]
                           (dotimes [j (alength ok)]
                             (let [x (aget ok j)]
                               (when-not (zero? x)
                                 (loop [i (slot-of x m)]
                                   (if (zero? (aget nk i))
                                     (do (aset nk i x) (aset nv i (aget ov j)))
                                     (recur (bit-and (inc i) m)))))))
                           (vreset! keys nk) (vreset! vals nv)))
                       (let [^longs ks @keys ^ints vs @vals m (dec (alength ks))]
                         (loop [i (slot-of k m)]
                           (if (zero? (aget ks i))
                             (do (aset ks i k) (aset vs i (int v)) (aset nkeys 0 (inc (aget nkeys 0))))
                             (recur (bit-and (inc i) m))))))
             add-rel! (fn [^long pos ^long own ^long tgt]
                        (let [n (aget nr 0)]
                          (when (== n (alength ^ints @rpos))
                            (vswap! rpos #(Arrays/copyOf ^ints % (int (* 2 n))))
                            (vswap! rown #(Arrays/copyOf ^ints % (int (* 2 n))))
                            (vswap! rtgt #(Arrays/copyOf ^ints % (int (* 2 n)))))
                          (aset ^ints @rpos n (int pos)) (aset ^ints @rown n (int own)) (aset ^ints @rtgt n (int tgt))
                          (aset nr 0 (inc n))))
             push! (fn [old kind p1 p2 p3 patch owner]
                     (.push stack (doto (long-array 7)
                                    (aset 0 (long old)) (aset 1 (long kind)) (aset 2 (long p1)) (aset 3 (long p2))
                                    (aset 4 (long p3)) (aset 5 (long patch)) (aset 6 (long owner)))))]
         (push! root 0 0 0 0 -1 -1)
         (loop []
           (when-not (.isEmpty stack)
             (let [^longs it (.pop stack)
                   old (aget it 0) kind (aget it 1) patch (aget it 5) owner (aget it 6)
                   key (inc (bit-and old 0xFFFFFFFF))
                   ord0 (lookup key)]
               (if (>= ord0 0)
                 (do (put-i32! @img patch (aget ^ints @boff ord0))
                     (add-rel! patch owner ord0))
                 (let [size (long (.sizeOf bs old))
                       _ (when (neg? size) (throw (ex-info "hand-image!: a block of unknown size" {:off old :kind kind})))
                       base (aget tail 0)
                       end (+ base (bit-and (+ size 3) (bit-not 3)))
                       _ (when (> end (alength ^bytes @img))
                           (vswap! img #(Arrays/copyOf ^bytes % (int (max end (* 2 (alength ^bytes %)))))))
                       ^bytes a @img
                       ord (aget nb 0)]
                   (System/arraycopy (.buf bs) (int old) a (int base) (int size))
                   (aset tail 0 end)
                   (when (== ord (alength ^ints @boff))
                     (vswap! boff #(Arrays/copyOf ^ints % (int (* 2 ord))))
                     (vswap! bsz #(Arrays/copyOf ^ints % (int (* 2 ord)))))
                   (aset ^ints @boff ord (int base))
                   (aset ^ints @bsz ord (int size))
                   (aset nb 0 (inc ord))
                   (insert! key ord)
                   (when (>= patch 0)
                     (put-i32! a patch base)
                     (add-rel! patch owner ord))
                   (case (int kind)
                     0 (let [t (u8 a base)]
                         (case (int t)
                           0xED (let [r (i32 a (+ base 8))] (when-not (== r -1) (push! r 1 0 0 0 (+ base 8) ord)))
                           0xEE (let [r (i32 a (+ base 8))] (when-not (== r -1) (push! r 2 0 0 0 (+ base 8) ord)))
                           0x12 (let [cnt (i32 a (+ base 4)) sh (i32 a (+ base 8)) root (i32 a (+ base 12))
                                      tl-off (i32 a (+ base 16)) tl (i32 a (+ base 20))
                                      toff (if (< cnt 32) 0 (bit-shift-left (bit-shift-right (dec cnt) 5) 5))]
                                  (when (and (pos? toff) (not (== root -1))) (push! root 5 sh 0 toff (+ base 12) ord))
                                  (when-not (== tl-off -1) (push! tl-off 6 tl 0 0 (+ base 16) ord)))
                           0x13 (let [h (i32 a (+ base 8))] (when-not (== h -1) (push! h 4 0 0 0 (+ base 8) ord)))
                           (0x01 0x1D 0x1E) nil
                           0x1F (let [n (i32 a (+ base 4)) pb (bit-and (+ 8 (* 2 n) 3) (bit-not 3))]
                                  (dotimes [i n]
                                    (let [k (u8 a (+ base 8 i)) p (+ base pb (* 4 i))]
                                      (cond (== k 2) (push! (i32 a p) 0 0 0 0 p ord)
                                            (== k 4) (push! (i32 a p) 3 0 0 0 p ord)))))
                           (let [layout (ser/deftype-layout bs old t)]
                             (when (vector? layout)
                               (doseq [[foff k] layout]
                                 (let [p (+ base (long foff)) r (i32 a p)]
                                   (when-not (== r -1) (push! r (if (= k :ref) 0 3) 0 0 0 p ord))))))))
                     (1 2) (let [t (u8 a base)]
                             (cond
                               (== t 1)
                               (let [dbm (i32 a (+ base 4)) nbm (i32 a (+ base 8))
                                     nc (popcount nbm) nd (popcount dbm)]
                                 (dotimes [i nc]
                                   (let [p (+ base 12 (* 4 i)) c (i32 a p)]
                                     (when-not (== c -1) (push! c kind 0 0 0 p ord))))
                                 (when (== kind 1)
                                   (loop [j 0 pos (+ base 12 (* 4 nc) (* 4 nd))]
                                     (when (< j nd)
                                       (let [klen (i32 a pos) vpos (+ pos 4 klen) vlen (i32 a vpos)]
                                         (when (and (== vlen 7) (inline-ptr? a (+ vpos 4)))
                                           (push! (i32 a (+ vpos 7)) 0 0 0 0 (+ vpos 7) ord))
                                         (recur (inc j) (+ vpos 4 vlen)))))))
                               (and (== kind 1) (== t 3))
                               (let [cnt (u8 a (+ base 1))]
                                 (loop [j 0 pos (+ base 8)]
                                   (when (< j cnt)
                                     (let [klen (i32 a pos) vpos (+ pos 4 klen) vlen (i32 a vpos)]
                                       (when (and (== vlen 7) (inline-ptr? a (+ vpos 4)))
                                         (push! (i32 a (+ vpos 7)) 0 0 0 0 (+ vpos 7) ord))
                                       (recur (inc j) (+ vpos 4 vlen))))))))
                     3 (when (and (== 7 (i32 a base)) (inline-ptr? a (+ base 4)))
                         (push! (i32 a (+ base 7)) 0 0 0 0 (+ base 7) ord))
                     4 (let [nx (i32 a base)]
                         (when-not (== nx -1) (push! nx 4 0 0 0 base ord))
                         (when (and (== 7 (i32 a (+ base 4))) (inline-ptr? a (+ base 8)))
                           (push! (i32 a (+ base 11)) 0 0 0 0 (+ base 11) ord)))
                     5 (let [sh (aget it 2) b0 (aget it 3) toff (aget it 4)
                             n (trie-live toff b0 sh) w (bit-shift-left 1 sh)]
                         (dotimes [i n]
                           (let [p (+ base (* 4 i)) c (i32 a p)]
                             (when-not (== c -1)
                               (if (zero? sh)
                                 (push! c 3 0 0 0 p ord)
                                 (push! c 5 (- sh 5) (+ b0 (* i w)) toff p ord))))))
                     6 (let [n (aget it 2)]
                         (dotimes [i n]
                           (let [p (+ base (* 4 i)) c (i32 a p)]
                             (when-not (== c -1) (push! c 3 0 0 0 p ord))))))))
               (recur))))
         (->HandImage (Arrays/copyOf ^bytes @img (int (aget tail 0))) (aget tail 0) (aget ^ints @boff 0)
                      @boff @bsz (aget nb 0) @rpos @rown @rtgt (aget nr 0))))

     (defn hand-image
       "The hand-built image of plain value v, in eve.flat.place's map form:
   {:bytes :length :root :blocks [{:offset :size}] :relocs [{:position
   :owner :target}] :layout 1} — offsets are positions in :bytes, blocks
   root-first."
       [v]
       (let [bs (bump-sio (* 32 1024 1024))
             im (hand-image! bs (build! bs v))
             n (int (:nblocks im)) nr (int (:nrel im))
             ^ints boff (:boff im) ^ints bsz (:bsz im)
             ^ints rpos (:rpos im) ^ints rown (:rown im) ^ints rtgt (:rtgt im)]
         {:bytes (:img im)
          :length (:len im)
          :root (:root im)
          :blocks (mapv (fn [i] {:offset (aget boff i) :size (aget bsz i)}) (range n))
          :relocs (mapv (fn [j] {:position (aget rpos j) :owner (aget rown j) :target (aget rtgt j)}) (range nr))
          :layout 1
          :value v}))

     (defn image-chunk-bytes
       "A hand-built image map as the FORMAT-V1 §9 chunk byte array place's public
   door parses: 32-byte header, the blocks (shifted past the header), the
   block table, the relocation table."
       [{:keys [bytes length root blocks relocs]}]
       (let [n (count blocks) nr (count relocs)
             hdr contract/header-size
             tab-off (+ hdr length)
             rel-off (+ tab-off (* contract/block-entry-size n))
             total (+ rel-off (* contract/relocation-entry-size nr))
             total (bit-and (+ total 7) (bit-not 7))
             ba (byte-array total)]
         (System/arraycopy ^bytes bytes 0 ba hdr (int length))
         (contract/write-block-table! ba tab-off
                                      (mapv (fn [{:keys [offset size]}] {:offset (+ hdr offset) :size size}) blocks))
         (contract/write-relocation-table! ba rel-off
                                           (mapv (fn [{:keys [position owner target]}]
                                                   {:position (+ hdr position) :owner owner :target target})
                                                 relocs))
    ;; the pointer words inside the copied blocks must hold chunk offsets
    ;; too (§9: pointers hold buffer offsets, and the buffer is the chunk) —
    ;; the map form's words hold map offsets, hdr short of the chunk's
         (doseq [{:keys [position target]} relocs]
           (put-i32! ba (+ hdr position) (+ hdr (:offset (nth blocks target)))))
         (contract/write-header! ba 0 {:kind :image :length total :root (+ hdr root)
                                       :layout-id 1 :symtab tab-off :shapetab rel-off :typetab 0})
         ba))

;; ---------------------------------------------------------------------------
;; Domains and the allocated set
;; ---------------------------------------------------------------------------

     (def ^:private capacities
       {0 (* 4 1024 1024) 1 (* 4 1024 1024) 2 (* 8 1024 1024) 3 (* 4 1024 1024) 4 (* 2 1024 1024) 5 (* 2 1024 1024)})

     (defn- fresh-atom [name]
       (let [dom (ea/atom-domain nil :capacities capacities)]
         (ea/lookup-or-create-mmap-atom! dom name nil)))

     (defn- blocks-in-use
       "The domain's allocated set, counted the way test/eve/jvm_atom_lost_attempt_test.clj
   counts it: per bitmap class, TOTAL_BLOCKS - FREE_COUNT from the region headers."
       [sio]
       (let [ctx (alloc/sio-root sio)]
         (reduce + (for [ci (range d/NUM_SLAB_CLASSES)
                         :let [r (aget ^objects (.-regions ^JvmSlabCtx ctx) ci)]]
                     (- (mem/-load-i32 r d/SLAB_HDR_TOTAL_BLOCKS)
                        (mem/-load-i32 r d/SLAB_HDR_FREE_COUNT))))))

     (defn- caught-check
       "The :eve.flat.place/check of the ex-info f throws, or nil when f does not throw."
       [f]
       (try (f) nil
            (catch clojure.lang.ExceptionInfo ex
              (:eve.flat.place/check (ex-data ex)))))

     (defn- caught-gate
       "The :check of the ex-info f throws at eve.flat.check's door, or nil
   when f does not throw."
       [f]
       (try (f) nil
            (catch clojure.lang.ExceptionInfo ex
              (:check (ex-data ex)))))))

;; ===========================================================================
;; Z3.1 — an image placed inside a swap! reads back equal, and a further
;; write on top of it is sound
;; ===========================================================================

#?(:clj
   (deftest place-reads-back-and-updates
     (doseq [[label v next]
             [["nested collections"
               {:a 1 :b [1 2 3] :c #{:x :y} :d "text" :e {:deep [true nil 2.5]}}
               (fn [cur] (assoc cur :after 99))]
              ["a 200-entry map"
               (into {} (map (fn [i] [i (* i i)])) (range 200))
               (fn [cur] (assoc cur 1000 :new))]
              ["a 5000-int vector"
               (vec (range 5000))
               (fn [cur] (conj cur 5000))]
              ["a list"
               (apply list (range 50))
               (fn [cur] (conj cur -1))]
              ["an empty map" {} (fn [cur] (assoc cur :k 1))]
              ["an empty vector" [] (fn [cur] (conj cur 1))]]]
       (testing label
         (let [a (fresh-atom (str "z3/rw-" (rand-int 1000000000)))
               img (hand-image v)]
           (is (= v (d/eve->clj (flat/place a (check/open-checked (image-chunk-bytes img)))))
               "the checked chunk form, through the frozen eve.flat door")
           (is (= v (d/eve->clj @a)))
           (is (= (next v) (d/eve->clj (swap! a next))) "a further write on top of the placed value is sound")
           (is (= (next v) (d/eve->clj @a))))))
     (testing "the map form places the same value"
       (let [a (fresh-atom (str "z3/rw-map-" (rand-int 1000000000)))
             v {:m (vec (range 300)) :s #{"a" "b" "c"}}
             img (hand-image v)]
         (is (= v (d/eve->clj (place/place a img))))
         (is (= v (d/eve->clj @a)))))
     (testing "a real detach, checked and placed end to end"
       (doseq [[label v next] [["a nested map" {:a 1 :b [1 2 3] :c #{:x :y} :d "text" :e {:deep [true nil 2.5]}}
                                (fn [cur] (assoc cur :after 99))]
                               ["a 200-entry map" (into {} (map (fn [i] [i (* i i)])) (range 200))
                                (fn [cur] (assoc cur 1000 :new))]
                               ["a vector of records" (mapv (fn [i] {:id i :name (str "r" i)}) (range 50))
                                (fn [cur] (conj cur {:id 50 :name "r50"}))]]]
         (let [src (fresh-atom (str "z3/e2e-src-" (rand-int 1000000000)))
               dst (fresh-atom (str "z3/e2e-dst-" (rand-int 1000000000)))]
           (reset! src v)
           (let [;; eve.flat/detach answers the image BYTES directly (the
                 ;; frozen verb's contract, image.cljc's detach: image-bytes
                 ;; of the internal Image, whose array is already trimmed
                 ;; to the written length) — no Image record to take
                 ;; accessors of here.
                 ^bytes ba (flat/detach src)]
             (is (= v (d/eve->clj (flat/place dst (check/open-checked ba)))) label)
             (is (= v (d/eve->clj @dst)) label)
             (is (= (next v) (d/eve->clj (swap! dst next)))
                 (str label ": a further write on top of the placed value is sound"))))))))

;; ===========================================================================
;; Z3.2 — the tables are checked before anything is claimed; each refusal is
;; typed and leaves the domain's allocated set unchanged
;; ===========================================================================

#?(:clj
   (deftest place-refuses-bad-tables
     (let [v {:k (vec (range 60)) :s #{1 2 3}}
           img (hand-image v)
           n (count (:blocks img))
           sio (alloc/make-jvm-heap-slab-ctx {:capacities {0 (* 1024 1024) 1 (* 1024 1024) 2 (* 2 1024 1024)}})
           used0 (blocks-in-use sio)
           refuse (fn [label bad want]
                    (testing label
                      (is (= want (caught-check #(place/place! sio bad))))
                      (is (= used0 (blocks-in-use sio)) "nothing was claimed")))]
       (refuse "a non-image chunk is refused"
               (assoc img :layout 1 :kind :packed) :wrong-kind)
       (refuse "an unknown layout is refused"
               (assoc img :layout 7) :unknown-layout)
       (refuse "a block offset past the image is refused"
               (assoc-in img [:blocks 1 :offset] (+ (:length img) 64)) :block-out-of-range)
       (refuse "a block running past the image end is refused"
               (assoc-in img [:blocks 0 :size] (+ (:length img) 64)) :block-out-of-range)
       (refuse "two overlapping blocks are refused"
               (assoc-in img [:blocks 1 :offset] (get-in img [:blocks 0 :offset])) :blocks-overlap)
       (refuse "a relocation to a non-block is refused"
               (assoc-in img [:relocs 0 :target] (+ n 5)) :pointer-to-non-block)
       (refuse "a relocation owned by a non-block is refused"
               (assoc-in img [:relocs 0 :owner] (+ n 5)) :pointer-to-non-block)
       (refuse "a size the class cannot hold (zero) is refused"
               (assoc-in img [:blocks 2 :size] 0) :size-cannot-hold)
       (refuse "a relocation position outside its owner's bytes is refused"
               (assoc-in img [:relocs 0 :position] (+ (:length img) 4096)) :relocation-out-of-range)
       (refuse "a relocation whose stored word is not the target's buffer offset is refused"
               (assoc-in img [:relocs 0 :target]
                         (mod (inc (get-in img [:relocs 0 :target])) n)) :relocation-value)
       (refuse "a root that is no block's offset is refused"
               (assoc img :root (+ (:length img) 8192)) :root-not-a-block)
       (testing "the root block's first byte is checked against the known kinds"
         (let [ba (Arrays/copyOf ^bytes (:bytes img) (alength ^bytes (:bytes img)))]
           ;; 0xF0: outside every assigned first-byte space — eve.deftype
           ;; type-ids are 0x40-0xEC (eve.deftype.registry: base 0x40, count
           ;; 173), the fixed kinds sit below 0x40 and at 0xED/0xEE, and
           ;; 0xF0-0xFF is unassigned. (0x55, this arm's first choice, IS a
           ;; deftype type-id — measured: place accepted it, correctly.)
           (aset ba (int (:root img)) (unchecked-byte 0xF0))
           (is (= :unknown-kind (caught-check #(place/place! sio (assoc img :bytes ba)))))
           (is (= used0 (blocks-in-use sio)))))
       (testing "raw chunk bytes are refused at the checked gate, before any parsing"
         (let [ba (image-chunk-bytes img)]
           (is (= :unchecked-bytes (caught-gate #(place/place! sio ba)))
               "valid-looking raw bytes are still raw bytes")
           (is (= used0 (blocks-in-use sio)) "nothing was claimed"))
         (let [ba (image-chunk-bytes img)]
           (aset ba 0 (unchecked-byte 0x00))
           (is (= :unchecked-bytes (caught-gate #(place/place! sio ba)))
               "bad magic in raw bytes meets the gate first, not the parser")
           (is (= :bad-magic (caught-gate #(check/open-checked ba)))
               "the checker itself names the bad magic")
           (is (= used0 (blocks-in-use sio)))))
       (testing "raw chunk bytes are refused through the atom door too"
         (let [a (fresh-atom (str "z3/gate-" (rand-int 1000000000)))]
           (reset! a {:other 1})
           (is (= :unchecked-bytes (caught-gate #(place/place a (image-chunk-bytes img)))))
           (is (= {:other 1} @a) "the atom's value is untouched")))
       (testing "the un-mutated image still places"
         (place/place! sio img)
         (is (= (+ used0 (count (:blocks img))) (blocks-in-use sio)))))))

;; ===========================================================================
;; Z3.3 — no free run: fall back to shorter runs, then single blocks, with
;; the same result
;; ===========================================================================

#?(:clj
   (deftest place-fallback-equal
     (let [v (into {} (map (fn [i] [i (vec (range 10))])) (range 120))
           img (hand-image v)
           nblocks (count (:blocks img))]
       (testing "alloc-run! answering nil for every run: singles carry the placement"
         (let [a (fresh-atom (str "z3/fb-stub-" (rand-int 1000000000)))]
           (with-redefs [alloc/alloc-run! (fn [_sio _ci _n] nil)]
             (is (= v (d/eve->clj (place/place a img)))))
           (is (= v (d/eve->clj @a)))))
       (testing "a fragmented class 0: every free run is a single block"
         (let [sio (alloc/make-jvm-heap-slab-ctx {:capacities {0 (* 32 4096) 1 (* 64 4096) 2 (* 128 4096) 3 (* 256 1024) 4 (* 512 1024) 5 (* 1024 512)}})
               keep (mapv (fn [_] (alloc/-sio-alloc! sio 32)) (range 600))]
           (doseq [i (range 1 600 2)] (alloc/-sio-free! sio (nth keep i)))
           (let [used0 (blocks-in-use sio)
                 root (place/place! sio img)]
             (is (= v (d/eve->clj (reopen sio root))))
             (is (= (+ used0 nblocks) (blocks-in-use sio))
                 "the placement claimed exactly the image's blocks, one way or another")))))))

;; ===========================================================================
;; Z3.4 — a lost CAS and a throw after placement leave the allocated set
;; what it was
;; ===========================================================================

#?(:clj
   (deftest place-exact-on-loss-and-throw
     (let [v {:payload (vec (range 400)) :tags #{"x" "y"}}
           img (hand-image v)
           scenario (fn [contend?]
                      (let [a (fresh-atom (str "z3/loss-" (rand-int 1000000000)))
                            _ (reset! a {:other 1})
                            attempts (clojure.core/atom 0)]
                        (swap! a (fn [_]
                                   (when (and contend? (== 1 (swap! attempts inc)))
                                     @(future (reset! a {:other 1})))
                                   (place/place-value d/*domain-sio* img)))
                        (#'ea/flush-zero-queue! (ea/-domain-state a))
                        [(blocks-in-use (ea/atom-sio a)) @a]))]
       (testing "a lost CAS frees the whole placement; the retry lands it once"
         (let [[used-plain placed] (scenario false)
               [used-lost placed-lost] (scenario true)]
           (is (= v placed))
           (is (= v placed-lost))
           (is (= used-plain used-lost) "the lost attempt's blocks are all back")))
       (testing "a failure inside placement itself rolls back every claim"
         ;; the failure lands midway, after five blocks are claimed:
         ;; alloc-run! answers nil (every group splits to singles) and the
         ;; delegating sio's sixth single claim throws. The throw rides a
         ;; wrapper object, not a with-redefs on alloc/-sio-alloc! —
         ;; measured on this pin, that redef never reaches place's compiled
         ;; call sites (see FailNthAllocSio).
         (let [inner (alloc/make-jvm-heap-slab-ctx {:capacities {0 (* 32 4096) 1 (* 64 4096) 2 (* 128 4096)}})
               sio (FailNthAllocSio. inner (clojure.core/atom 0) 6)
               used0 (blocks-in-use inner)]
           (with-redefs [alloc/alloc-run! (fn [_sio _ci _n] nil)]
             (is (thrown? clojure.lang.ExceptionInfo (place/place! sio img))))
           (is (= used0 (blocks-in-use inner))
               "every offset claimed before the failure is freed again")))
       (testing "a throw after placement"
         (let [a (fresh-atom (str "z3/throw-" (rand-int 1000000000)))
               _ (reset! a {:other 1})
               sio (ea/atom-sio a)
               used0 (blocks-in-use sio)]
           (is (thrown? clojure.lang.ExceptionInfo
                        (swap! a (fn [_]
                                   (place/place-value d/*domain-sio* img)
                                   (throw (ex-info "boom" {}))))))
           (is (= {:other 1} @a) "the atom's value is untouched")
           ;; The atom's swap machinery has no catch around the swap fn: a
           ;; throw after the fn's allocations are made drops the attempt's
           ;; log with the attempt. Measured on this pin for an ordinary
           ;; in-domain build (probe, doc/flat/Z3-STATUS.md): before 4
           ;; blocks in use, a build-then-throw attempt left 7,524 — the
           ;; delta is exactly the attempt's blocks, still there after the
           ;; retire flush; the same build committed lands at 15,042. The
           ;; plan's stronger wording (the set is what it was) is therefore
           ;; not placement's to keep — it is a property eve.atom does not
           ;; have for any writer, and it is banked as a correction in the
           ;; lane report. What place guarantees exactly, and what is
           ;; asserted here: the placement claimed nothing beyond the
           ;; image's blocks — the growth is the image's block count, no
           ;; partial claims, no extras.
           (is (= (+ used0 (count (:blocks img))) (blocks-in-use sio))
               "the throw leaks exactly the placed attempt, nothing partial, nothing extra"))))))

;; ===========================================================================
;; Z3.5 — large-class blocks are placed 8-aligned
;; ===========================================================================

#?(:clj
   (deftest place-large-blocks
     (let [v {:payload (apply str (repeat 5000 "x")) :small [1 2 3]}
           img (hand-image v)
           sio (alloc/make-jvm-heap-slab-ctx {:capacities {0 (* 1024 1024) 1 (* 1024 1024) 2 (* 2 1024 1024)}})
           root (place/place! sio img)
           big (filter #(== 6 (alloc/decode-class-idx %)) (retire/reach sio root))]
       (is (seq big) "the 5004-byte string block is a class-6 block")
       (is (every? #(zero? (mod (alloc/decode-block-idx %) 8)) big)
           "every placed class-6 block starts at a byte offset divisible by 8")
       (is (= v (d/eve->clj (reopen sio root)))))))

;; ===========================================================================
;; Z3.6 — an encrypted domain's placed bytes pass through the keystream
;; ===========================================================================

#?(:clj
   (defn- bytes-contains? [^bytes hay ^bytes needle]
     (let [n (alength hay) m (alength needle)]
       (loop [i 0]
         (cond (> (+ i m) n) false
               (Arrays/equals hay i (+ i m) needle 0 m) true
               :else (recur (inc i)))))))

#?(:clj
   (deftest place-encrypted
     (let [needle "PLACED-THROUGH-THE-KEYSTREAM-0123456789"
           v {:secret (str "xx" needle "yy") :n 7}
           img (hand-image v)
           base (str (System/getProperty "java.io.tmpdir") "/eve-z3-enc-" (rand-int 1000000000) "/d")
           [sio _ring] (kr/open-encrypted-domain! base {:create? true})]
       (try
         (is (bytes-contains? ^bytes (:bytes img) (.getBytes needle "UTF-8"))
             "sanity: the needle is contiguous in the image itself")
         (let [root (place/place! sio img)]
           (is (= v (d/eve->clj (reopen sio root)))
               "read back through the keystream, the placed value is the value")
           (doseq [i (range 7)
                   :let [p (str base ".slab" i)
                         f (java.io.File. p)]
                   :when (.exists f)]
             (is (not (bytes-contains? (java.nio.file.Files/readAllBytes (.toPath f))
                                       (.getBytes needle "UTF-8")))
                 (str "the needle is in no slab file: " p))))
         (finally
           (try (alloc/release-domain-sio! sio true) (catch Throwable _ nil)))))))

;; ===========================================================================
;; Z3.7 — a 1 MiB-unit image places unit by unit; pointers that leave a unit
;; are resolved from the plan in the first unit
;; ===========================================================================

#?(:clj
   (deftest place-by-units
     (let [v (mapv (fn [i] {:id i :name (str "record-" i)}) (range 2000))
           img (hand-image v)
           plan (place/placement-plan img (* 64 1024))]
       (is (< 1 (:nunits plan)) "the image spans several 64 KiB units")
       (is (pos? (:crossings plan)) "some pointers leave their unit")
       (let [sio (alloc/make-jvm-heap-slab-ctx {:capacities {0 (* 2 1024 1024) 1 (* 2 1024 1024) 2 (* 4 1024 1024) 3 (* 2 1024 1024)}})
             root (place/place! sio img {:unit-size (* 64 1024)})]
         (is (= v (d/eve->clj (reopen sio root)))
             "placed unit by unit, cross-unit pointers resolved, the value is whole"))
       (let [sio (alloc/make-jvm-heap-slab-ctx {:capacities {0 (* 2 1024 1024) 1 (* 2 1024 1024) 2 (* 4 1024 1024) 3 (* 2 1024 1024)}})
             root (place/place! sio img {:unit-size (* 1024 1024)})]
         (is (= 1 (:nunits (place/placement-plan img (* 1024 1024)))))
         (is (= v (d/eve->clj (reopen sio root))))))))
