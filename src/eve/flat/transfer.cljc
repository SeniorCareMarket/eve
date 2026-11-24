(ns eve.flat.transfer
  "Lane T1 (IMPL-PLAN §3.3): atom to atom. A slab value of one domain stored
   into another is DETACHED — every block it reaches copied once into one
   byte array, pointers rewritten to positions in the array, with the block
   table and relocation table of FORMAT-V1 §9 — and the image is PLACED into
   the receiving domain: blocks claimed, bytes copied, pointers rewritten
   from the relocation table. No host collection is built at any point, so
   sharing inside the value survives (a block reached twice is one block in
   the receiving domain too) and the receiving domain's allocated set after
   the store is exactly the value's blocks.

   Surface:
     detach         value-or-atom -> image bytes (T1's walk; the §3
                    reference refusal is fused into it)
     place-image!   atom, image bytes -> a, via lane Z3's place
     transfer!      atom, (atom | slab value | plain value) -> atom
                    (the eve.flat/transfer! verb, W0's frozen signature)
     transfer-direct!  like transfer!, copying domain to domain with no
                    image buffer between (T1.6)
     zip-root!      sio, foreign slab value -> its root offset in sio
                    (the :zip foreign-policy branch of eve.atom's
                    resolve-new-ptr)
     zip-nested     sio, plain value -> the value with every foreign slab
                    value inside it placed into sio (the :zip branch's
                    nested half)

   Placement is lane Z3's eve.flat.place: it validates the image in
   full before the first block is claimed (a throw inside a swap! frees
   nothing — eve.atom's attempt protocol — so validation is the front
   side of the point of no return, and Z3's placement frees its own
   claims if a later step throws), claims through alloc/alloc-run!, and
   rewrites pointers from the relocation table. The detach above is
   T1's own walk because the refusal of a process-local reference
   (FORMAT-V1 §3) is fused into it; Z1's eve.flat.image/detach — the
   canonical detach — has no reference screening at its pin (checked by
   grep over image.cljc and sio.cljc), so the transfer path cannot
   delegate detach until that lands there. The two walks emit the same
   §9 tables (ordinals, absolute positions, buffer-offset pointers).

   Image table semantics (FORMAT-V1 §9, as the measured probes wrote
   them): a relocation entry's :position is the absolute buffer offset of
   the pointer field; :owner and :target are block-table ORDINALS.

   Refusals are typed ex-infos: {:eve.flat.transfer/refusal <name>} —
   :process-local-reference (a 0x15 reference scalar: a fn, a regex, a
   host object — it names a handle in this process's reference table and
   cannot leave the process; FORMAT-V1 §3, A12), :bad-image (a named
   structural check), :block-size-unknown, :not-a-slab-value."
  (:require [eve.atom :as ea]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.encoding :as enc]
            [eve.flat.check :as check]
            [eve.flat.contract :as contract]
            [eve.flat.place :as place])
  #?(:clj (:import [eve.deftype_proto.alloc JvmSlabCtx]
                   [java.util ArrayDeque ArrayList Arrays HashMap])))

;; ---------------------------------------------------------------------------
;; small words over a byte array (little-endian, as the slab writes them)
;; ---------------------------------------------------------------------------

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

(defn- align4 ^long [^long n] (bit-and (+ n 3) (bit-not 3)))
(defn- align8 ^long [^long n] (bit-and (+ n 7) (bit-not 7)))

;; ---------------------------------------------------------------------------
;; refusals
;; ---------------------------------------------------------------------------

(defn- refuse! [refusal msg data]
  (throw (ex-info msg (assoc data :eve.flat.transfer/refusal refusal))))

(def ^:private ref-magic-0 (unchecked-byte 0xEE))
(def ^:private ref-magic-1 (unchecked-byte 0xDB))

(defn- reference-at?
  "The EVE bytes at pos of a are a process-local reference: the 11-byte
   [0xEE 0xDB 0x15 realm:u32 handle:u32] form (serialize FAST_TAG_REF)."
  [^bytes a ^long pos ^long len]
  (and (== len 11)
       (== (aget a (int pos)) ref-magic-0)
       (== (aget a (int (+ pos 1))) ref-magic-1)
       (== (u8 a (+ pos 2)) 0x15)))

(defn- check-no-reference!
  "Refuse (by name) when the [len][EVE bytes] segment whose bytes start at
   pos holds a process-local reference."
  [^bytes a ^long pos ^long len ^long owner-off]
  (when (reference-at? a pos len)
    (refuse! :process-local-reference
             "transfer: the value holds a process-local reference (tag 0x15 — a fn, a regex or a host object); it names a handle in this process's reference table and cannot leave the process"
             {:block owner-off :at pos})))

;; ---------------------------------------------------------------------------
;; the walk: every block a value reaches, each once, with the byte position
;; of every pointer it holds. Kinds are eve.retire's (retire.cljc):
;;   0 a value's header (by its type-id byte)   1 a map's HAMT node
;;   2 a set's HAMT node                        3 a value block [len][bytes]
;;   4 a list cell                              5 a vector trie node
;;   6 a vector tail
;; Ported from the research probes (doc/flat/probes/lib.clj image! and
;; java/staticprobe/ImageCopier.java), which read every Eve type in place
;; (P5). Pointer discovery and the block's used size are computed together.
;; ---------------------------------------------------------------------------

(defn- pointer-tag? [^long tag]
  (or (<= 0x10 tag 0x13) (<= 0x1C tag 0x1F) (<= 0x40 tag 0xEC)))

(defn- inline-ptr
  "The buffer offset the 7 EVE pointer bytes at p of a point at, or -1."
  ^long [^bytes a ^long p]
  (let [w (i32 a p)]
    (if (and (== 0xDBEE (bit-and w 0xFFFF)) (pointer-tag? (bit-and (bit-shift-right w 16) 0xFF)))
      (i32 a (+ p 3))
      -1)))

(defn- popcount ^long [^long x] (long (Integer/bitCount (unchecked-int x))))

(defn- trie-live ^long [^long toff ^long base ^long sh]
  (let [n (- toff base)]
    (if (<= n 0) 0 (let [w (bit-shift-left 1 sh)] (min 32 (quot (+ n (dec w)) w))))))

(def ^:private esize-by-subtype [0 1 1 1 2 2 4 4 4 8 8 8]) ;; serialize.cljc subtypes 1-11

(defn- class-size
  "The block size of class ci in sio's domain (classes 0-5), for the kinds
   whose used size is their allocation: scalar blocks, eve-deftype
   instances, obj blocks. The JVM ctx's own geometry where there is one."
  ^long [sio ^long ci]
  #?(:clj (let [root (alloc/sio-root sio)]
            (if (instance? JvmSlabCtx root)
              (long (aget ^ints (.-block-sizes ^JvmSlabCtx root) (int ci)))
              (long (nth alloc/CLASS_BLOCK_SIZES ci))))
     :cljs (long (nth alloc/CLASS_BLOCK_SIZES ci))))

(defn- fallback-size
  "The size of a block of kind/tag whose used size is its allocation:
   its class size, or a named refusal in the overflow class, whose blocks
   have no fixed size and whose self-delimiting kinds are sized by layout."
  ^long [sio ^long ci ^long off ^long tag]
  (if (< ci 6)
    (class-size sio ci)
    (refuse! :block-size-unknown
             "transfer: a block in the overflow class whose size its layout does not state (a scalar block, an eve-deftype instance or an obj block past 1024 bytes)"
             {:offset off :tag tag})))

(defn- hamt-bitmap-scan
  "A bitmap HAMT node (tag 1): child pointers by node bitmap, then the
   entries — for a map [klen][key][vlen][value] each, values that are
   7-byte inline pointers become relocations; for a set, keys only.
   The node's used size and its pointers."
  [^bytes a map? ^long old]
  (let [dbm (i32 a 4) nbm (i32 a 8)
        nc (popcount nbm) nd (popcount dbm)
        kids (into []
                   (keep (fn [i]
                           (let [p (+ 12 (* 4 i)) c (i32 a p)]
                             (when-not (== c -1) [p (if map? 1 2) 0 0 0 c]))))
                   (range nc))]
    (if map?
      (loop [j 0 pos (+ 12 (* 4 nc) (* 4 nd)) ptrs kids]
        (if (== j nd)
          {:size pos :ptrs ptrs}
          (let [klen (i32 a pos) vpos (+ pos 4 klen) vlen (i32 a vpos)]
            (check-no-reference! a (+ pos 4) klen old)
            (check-no-reference! a (+ vpos 4) vlen old)
            (let [ip (if (== vlen 7) (inline-ptr a (+ vpos 4)) -1)]
              (recur (inc j) (+ vpos 4 vlen)
                     (if (== ip -1) ptrs (conj ptrs [(+ vpos 7) 0 0 0 0 ip])))))))
      (loop [j 0 pos (+ 12 (* 4 nc))]
        (if (== j nd)
          {:size pos :ptrs kids}
          (let [klen (i32 a pos)]
            (check-no-reference! a (+ pos 4) klen old)
            (recur (inc j) (+ pos 4 klen))))))))

(defn- hamt-collision-scan
  "A collision HAMT node (tag 3): a count byte, then the entries packed —
   for a map [klen][key][vlen][value] each, for a set keys only."
  [^bytes a map? ^long old]
  (let [cnt (u8 a 1)]
    (if map?
      (loop [j 0 pos 8 ptrs []]
        (if (== j cnt)
          {:size pos :ptrs ptrs}
          (let [klen (i32 a pos) vpos (+ pos 4 klen) vlen (i32 a vpos)]
            (check-no-reference! a (+ pos 4) klen old)
            (check-no-reference! a (+ vpos 4) vlen old)
            (let [ip (if (== vlen 7) (inline-ptr a (+ vpos 4)) -1)]
              (recur (inc j) (+ vpos 4 vlen)
                     (if (== ip -1) ptrs (conj ptrs [(+ vpos 7) 0 0 0 0 ip])))))))
      (loop [j 0 pos 8]
        (if (== j cnt)
          {:size pos :ptrs []}
          (let [klen (i32 a pos)]
            (check-no-reference! a (+ pos 4) klen old)
            (recur (inc j) (+ pos 4 klen))))))))

(defn block-scan
  "The pointers of the block whose bytes are a[0..size) — size not yet
   known, so this also answers it: {:size n :ptrs [[rel-pos kind p1 p2 p3
   target-old-offset] ...]}, rel-pos relative to the block's start.
   src/sio are used only for an eve-deftype's registered layout.
   Throws the :process-local-reference refusal."
  [src sio ^bytes a kind p1 p2 p3 ci old]
  (let [kind (long kind) p1 (long p1) p2 (long p2) p3 (long p3)
        ci (long ci) old (long old)]
    (case (int kind)
    0 (let [t (u8 a 0)]
        (case (int t)
          0xED (let [r (i32 a 8)] {:size 12 :ptrs (if (== r -1) [] [[8 1 0 0 0 r]])})
          0xEE (let [r (i32 a 8)] {:size 12 :ptrs (if (== r -1) [] [[8 2 0 0 0 r]])})
          0x12 (let [cnt (i32 a 4) sh (i32 a 8) root (i32 a 12)
                     tl-off (i32 a 16) tl (i32 a 20)
                     toff (if (< cnt 32) 0 (bit-shift-left (bit-shift-right (dec cnt) 5) 5))]
                 {:size 24
                  :ptrs (cond-> []
                          (and (pos? toff) (not (== root -1))) (conj [12 5 sh 0 toff root])
                          (not (== tl-off -1)) (conj [16 6 tl 0 0 tl-off]))})
          0x13 (let [h (i32 a 8)] {:size 12 :ptrs (if (== h -1) [] [[8 4 0 0 0 h]])})
          0x01 (do (when (and (>= (alength a) 3)
                              (== (aget a 0) ref-magic-0)
                              (== (aget a 1) ref-magic-1)
                              (== (u8 a 2) 0x15))
                     (refuse! :process-local-reference
                              "transfer: the value holds a process-local reference (tag 0x15 — a fn, a regex or a host object); it names a handle in this process's reference table and cannot leave the process"
                              {:block old :at 0}))
                   {:size (fallback-size sio ci old t) :ptrs []})
          0x1D (let [n (i32 a 4) es (long (nth esize-by-subtype (u8 a 1)))]
                 {:size (+ 8 (* n es)) :ptrs []})
          0x1E {:size (fallback-size sio ci old t) :ptrs []}
          0x1F (let [n (i32 a 4) pb (align4 (+ 8 (* 2 n)))]
                 {:size (+ pb (* 4 n))
                  :ptrs (into []
                              (keep (fn [i]
                                      (let [k (u8 a (+ 8 i)) p (+ pb (* 4 i)) r (i32 a p)]
                                        (cond (== k 2) [p 0 0 0 0 r]
                                              (== k 4) [p 3 0 0 0 r]
                                              :else nil))))
                              (range n))})
          ;; a frozen chunk (FLAT-IN-ATOM R2/S1): header of a
          ;; kind-7 segment chain carrying packed chunk bytes.
          ;; Closed — the only pointer is the chain itself.
          0x20 (let [seg (i32 a 8)]
                 {:size 48
                  :ptrs (if (== seg -1) [] [[8 7 0 0 0 seg]])})
          ;; a frozen chunk EXTENT (FLAT-IN-ATOM S1'): ONE block,
          ;; 44-byte header + chunk bytes. Closed — no pointers.
          0x21 {:size (+ 44 (i32 a 4)) :ptrs []}
          ;; an eve-deftype instance, by its registered layout
          (let [layout (ser/deftype-layout src old t)]
            (if (vector? layout)
              {:size (fallback-size sio ci old t)
               :ptrs (into []
                           (keep (fn [[foff k]]
                                   (let [r (i32 a (long foff))]
                                     (when-not (== r -1)
                                       [(long foff) (if (= k :ref) 0 3) 0 0 0 r]))))
                           layout)}
              ;; a type not loaded here: copied as a leaf, as the tally
              ;; holds it — its interior pointers, if any, are not walked
              {:size (fallback-size sio ci old t) :ptrs []}))))
    (1 2) (let [t (u8 a 0) map? (== kind 1)]
            (cond (== t 1) (hamt-bitmap-scan a map? old)
                  (== t 3) (hamt-collision-scan a map? old)
                  :else {:size (fallback-size sio ci old t) :ptrs []}))
    3 (let [len (i32 a 0)]
        (check-no-reference! a 4 len old)
        (let [ip (if (== len 7) (inline-ptr a 4) -1)]
          {:size (+ 4 len) :ptrs (if (== ip -1) [] [[7 0 0 0 0 ip]])}))
    4 (let [nx (i32 a 0) vlen (i32 a 4)]
        (check-no-reference! a 8 vlen old)
        (let [ip (if (== vlen 7) (inline-ptr a 8) -1)]
          {:size (+ 8 vlen)
           :ptrs (cond-> []
                   (not (== nx -1)) (conj [0 4 0 0 0 nx])
                   (not (== ip -1)) (conj [11 0 0 0 0 ip]))}))
    5 (let [n (trie-live p3 p2 p1) w (bit-shift-left 1 p1)]
        {:size 128
         :ptrs (into []
                     (keep (fn [i]
                             (let [p (* 4 i) c (i32 a p)]
                               (when-not (== c -1)
                                 (if (zero? p1)
                                   [p 3 0 0 0 c]
                                   [p 5 (- p1 5) (+ p2 (* i w)) p3 c])))))
                     (range n))})
    6 {:size 128
       :ptrs (into []
                   (keep (fn [i]
                           (let [p (* 4 i) c (i32 a p)]
                             (when-not (== c -1) [p 3 0 0 0 c]))))
                   (range p1))}
    ;; a frozen chunk's payload segment (FLAT-IN-ATOM S1): the
    ;; kind-4 chain shape — [next][len][payload bytes]. Payload
    ;; is never pointer-interpreted (the value is closed, R1).
    7 (let [nx (i32 a 0) vlen (i32 a 4)]
        {:size (+ 8 vlen)
         :ptrs (if (== nx -1) [] [[0 7 0 0 0 nx]])}))))

;; ---------------------------------------------------------------------------
;; fetch + walk
;; ---------------------------------------------------------------------------

(defn fetch-block
  "The bytes of the block at old in sio, long enough for block-scan: the
   exact size where the block's own header states it, its class size
   otherwise, a wide window for a HAMT node in the overflow class."
  ^bytes [sio ^long old ^long kind ^long ci]
  (let [^bytes prefix (alloc/-sio-read-bytes sio old 0 24)
        exact (case (int kind)
                3 (+ 4 (i32 prefix 0))
                4 (+ 8 (i32 prefix 4))
                0 (case (int (u8 prefix 0))
                    0xED 12
                    0xEE 12
                    0x12 24
                    0x13 12
                    0x1D (+ 8 (* (i32 prefix 4) (long (nth esize-by-subtype (u8 prefix 1)))))
                    0x1F (let [n (i32 prefix 4)] (+ (align4 (+ 8 (* 2 n))) (* 4 n)))
                    -1)
                -1)]
    (cond (pos? exact) (if (<= exact 24)
                         (let [nb (byte-array (int exact))]
                           (enc/arraycopy prefix 0 nb 0 (int (min (alength prefix) exact)))
                           nb)
                         (alloc/-sio-read-bytes sio old 0 exact))
          (< ci 6) (if (<= (class-size sio ci) 24)
                     prefix
                     (alloc/-sio-read-bytes sio old 0 (class-size sio ci)))
          :else (try (alloc/-sio-read-bytes sio old 0 262144)
                     (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _
                       (try (alloc/-sio-read-bytes sio old 0 32768)
                            (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _
                              (alloc/-sio-read-bytes sio old 0 4096))))))))

(defn- walk
  "Every block the value rooted at root-off in sio reaches, each once.
   Ordinals are assigned at discovery (the root is ordinal 0), so a block
   two parents reach before either is copied is still one block:
     {:chunks [{:bytes .. :size n} ...]          (index = ordinal)
      :relocs [{:owner ordinal :field rel-pos :target ordinal} ...]}"
  [sio ^long root-off]
  #?(:clj
     (let [ordinals (HashMap.)
           chunks (ArrayList.)
           relocs (volatile! [])
           stack (ArrayDeque.)
           ord-of (fn [^long old]
                    (let [k (Long. (unchecked-inc (bit-and old 0xFFFFFFFF)))
                          seen (.get ordinals k)]
                      (if (some? seen)
                        (int seen)
                        (let [ord (.size ordinals)]
                          (.put ordinals k (Integer. ord))
                          (while (<= (.size chunks) ord) (.add chunks nil))
                          ord))))]
       (.push stack (long-array [root-off 0 0 0 0 (ord-of root-off)]))
       (while (not (.isEmpty stack))
         (let [^longs item (.pop stack)
               old (aget item 0) kind (aget item 1)
               p1 (aget item 2) p2 (aget item 3) p3 (aget item 4)
               ord (aget item 5)
               ci (alloc/decode-class-idx old)
               bytes (fetch-block sio old kind ci)
               {:keys [size ptrs]} (block-scan sio sio bytes kind p1 p2 p3 ci old)]
           (.set chunks (int ord) {:bytes (Arrays/copyOf ^bytes bytes (int size)) :size size})
           (doseq [[rel pk pp1 pp2 pp3 tgt] ptrs]
             (let [seen (.get ordinals (Long. (unchecked-inc (bit-and tgt 0xFFFFFFFF))))]
               (if (some? seen)
                 (vswap! relocs conj {:owner ord :field rel :target (int seen)})
                 (let [tord (ord-of tgt)]
                   (vswap! relocs conj {:owner ord :field rel :target tord})
                   (.push stack (long-array [tgt pk pp1 pp2 pp3 tord]))))))))
       {:chunks (vec chunks) :relocs @relocs})
     :cljs (refuse! :not-built "transfer: the walk is built for the JVM in Wave 1" {})))

;; ---------------------------------------------------------------------------
;; detach: the walk, assembled as one FORMAT-V1 §9 image
;; ---------------------------------------------------------------------------

(defn- image-bytes
  "Assemble walked chunks + relocs into one image byte array: the 32-byte
   header, the blocks (4-aligned) with every pointer field rewritten to
   its target's buffer offset (§9: the blocks are the slab's, except
   pointers hold buffer offsets), the block table, the relocation table;
   the whole zero-padded to a multiple of 8 (FORMAT-V1 §10).

   The type table is present and empty (a walked value holds no
   eve-deftype instance whose name needs tabling in Wave 1's corpora):
   typetab names the relocation table's exact end. That is load-bearing,
   not cosmetic — a consumer deriving the relocation table's end from
   the header length (a multiple of 8) miscounts whenever the pointer
   count is odd, the pad being indivisible by the 12-byte entry; Z1
   writes typetab 0 for the same values and Z3's place refuses those
   images (measured, T1-STATUS 2026-11-12). The canonical convention is
   the Z1/Z3 owners' to settle; this is the reading under which the
   composition works today."
  ^bytes [{:keys [chunks relocs]}]
  (let [n (count chunks)
        offs (long-array n)
        end (loop [i 0 pos contract/header-size]
              (if (== i n)
                pos
                (do (aset offs i (long pos))
                    (recur (inc i) (align4 (+ pos (long (:size (nth chunks i)))))))))
        bt-off (align8 end)
        rt-off (+ bt-off (* 8 n))
        len (align8 (+ rt-off (* 12 (count relocs))))
        ba (byte-array len)]
    (contract/write-header! ba 0 {:kind :image
                                  :layout-id contract/layout-canonical
                                  :length len
                                  :root (aget offs 0)
                                  :symtab bt-off
                                  :shapetab rt-off
                                  :typetab (+ rt-off (* 12 (count relocs)))})
    (dotimes [i n]
      (let [c (nth chunks i)]
        (enc/arraycopy ^bytes (:bytes c) 0 ba (int (aget offs i)) (int (:size c)))))
    (doseq [r relocs]
      (put-i32! ba (+ (aget offs (int (:owner r))) (long (:field r)))
                (aget offs (int (:target r)))))
    (contract/write-block-table! ba bt-off
                                 (mapv (fn [i] {:offset (aget offs i)
                                                :size (long (:size (nth chunks i)))})
                                       (range n)))
    (contract/write-relocation-table! ba rt-off
                                      (mapv (fn [r] {:position (+ (aget offs (int (:owner r)))
                                                                  (long (:field r)))
                                                     :owner (long (:owner r))
                                                     :target (long (:target r))})
                                            relocs))
    ba))

(defn- ea-atom? [x]
  (some? (try (ea/-domain-state x) (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))))

(defn- detach-value
  "Image bytes for one slab value. Refuses anything that is not a slab
   value: a scalar is never a flat value (FORMAT-V1 §2)."
  ^bytes [v]
  (when-not (satisfies? d/IEveRoot v)
    (refuse! :not-a-slab-value
             "detach: not a slab value — a scalar is never a flat value (FORMAT-V1 §2)"
             {:value-type (type v)}))
  (let [src (d/-eve-sio v)
        root (d/-root-header-off v)]
    (when (or (nil? src) (== root -1))
      (refuse! :not-a-slab-value
               "detach: the value has no slab root (it is not rooted in a domain)"
               {}))
    (image-bytes (walk src root))))

(defn detach
  "x — an Eve atom or a slab value — to image bytes. An atom is read under
   its pin, so a concurrent writer can neither tear the copy nor free a
   block mid-walk (T1.2). T1's own walk (not Z1's canonical detach)
   because the §3 reference refusal is fused into it — see the ns doc."
  ^bytes [x]
  (if (ea-atom? x)
    (ea/read-pinned x (fn [v] (detach-value v)))
    (detach-value x)))

;; ---------------------------------------------------------------------------
;; place: lane Z3's eve.flat.place (validate in full before any claim —
;; A15 — claim by alloc-run!, copy, relocate, with a rollback arm that
;; frees its own claims when a later step throws). T1 keeps only the
;; claim/copy/rewrite pieces the direct route (T1.6) needs: it has no
;; image to validate, its tables come from the walk itself.
;; ---------------------------------------------------------------------------

(defn- class-for-size ^long [^long size]
  (loop [ci 0]
    (cond (== ci 6) 6
          (<= size (long (nth alloc/CLASS_BLOCK_SIZES ci))) ci
          :else (recur (inc ci)))))

(defn- claim-blocks!
  "Claim every block of sizes in sio: one alloc-run! per size class (the
   W0.4 contract; the default claims block by block), -sio-alloc! per
   overflow block, a per-block fallback if a run claim is refused.
   Returns a long array of the new offsets, in block order."
  ^longs [sio blocks]
  (let [n (count blocks)
        offs (long-array n)
        by-class (group-by (fn [[_ b]] (class-for-size (long (:size b)))) (map-indexed vector blocks))]
    (doseq [[ci idxs] by-class]
      (if (== ci 6)
        (doseq [[i b] idxs]
          (aset offs (int i) (long (alloc/-sio-alloc! sio (long (:size b))))))
        (let [csize (long (nth alloc/CLASS_BLOCK_SIZES ci))
              run (try (alloc/alloc-run! sio ci (count idxs))
                       (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))
              run (or run (mapv (fn [_] (alloc/-sio-alloc! sio csize)) idxs))]
          (doseq [[k [i _]] (map-indexed vector idxs)]
            (aset offs (int i) (long (nth run k)))))))
    offs))

(defn- reopen
  "The placed value at root offset off in sio, as the lazy Eve value its
   header constructor makes — the same object a plain store returns."
  [sio ^long off]
  (let [t (ser/read-header-tag sio off)
        ctor (ser/get-header-constructor t)]
    (when-not ctor
      (refuse! :bad-image
               "place: the placed root block has no header constructor in this process"
               {:tag t}))
    (ctor sio off)))

(defn place-image!
  "Place image bytes bs into Eve atom a, through lane Z3's place (a swap!
   whose function validates, claims, copies and rewrites, then returns
   the reopened value). Returns a. The bytes are vouched through
   eve.flat.check/open-checked first: place's door accepts only a
   CheckedBytes handle or the map form — raw bytes are refused
   :unchecked-bytes (Check's gate, collation repair: transfer's own
   detach output crossed that door unvouched)."
  [a ^bytes bs]
  (place/place a (check/open-checked bs))
  a)

;; ---------------------------------------------------------------------------
;; transfer
;; ---------------------------------------------------------------------------

;; clojerl: no requiring-resolve, and a def's value compiles in as a literal
;; (a delay is not one); transfer! takes its fallback path there
#?(:clje (def ^:private stream-transfer* (atom nil)) :default
(def ^:private stream-transfer*
  "STREAM-PLAN S3: the stream pipe is the transfer engine behind
   this verb. Resolved dynamically, once, because eve.flat.stream
   requires this namespace (block-scan / fetch-block): the require
   cycle is broken here, at the verb. If the stream namespace cannot
   be resolved (it always can in this tree), transfer! falls back to
   the detach+zip path below."
  (delay (try (requiring-resolve 'eve.flat.stream/stream-transfer!)
              (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil)))))

(defn transfer!
  "T1's verb (eve.flat/transfer! delegates here; the signature is W0's):
   store x into Eve atom a and return a. An Eve atom x contributes its
   committed value; a slab value x is stored directly; anything else
   is stored by a plain swap!. A value holding a process-local
   reference is refused by name. Since STREAM-PLAN S3 the slab cases
   travel by the stream (eve.flat.stream: post-order walk, run
   claims, per-segment checked door); the detach+zip path remains as
   the fallback and for image-shaped callers."
  [a x]
  (when (nil? a)
    (refuse! :no-atom "transfer! wants an Eve atom, got nil" {}))
  (if-let [st @stream-transfer*]
    (st a x)
    (cond (ea-atom? x)
          (let [bs (ea/read-pinned x (fn [v] (when (satisfies? d/IEveRoot v)
                                               (detach-value v))))]
            (if bs
              (place-image! a bs)
              (do (swap! a (fn [_] @x)) a)))
          (satisfies? d/IEveRoot x)
          (place-image! a (detach-value x))
          :else
          (do (swap! a (fn [_] x)) a))))

(defn transfer-direct!
  "transfer! without the buffer between the domains (T1.6): the walk's
   chunks are claimed, copied and rewritten straight into a's domain,
   inside one swap attempt — the same blocks, no image assembled."
  [a x]
  (let [place (fn [src root-off]
                (swap! a (fn [_]
                           (let [sio d/*domain-sio*
                                 {:keys [chunks relocs]} (walk src root-off)
                                 offs (claim-blocks! sio (mapv (fn [c] {:size (:size c)}) chunks))]
                             (doseq [[i c] (map-indexed vector chunks)]
                               (alloc/-sio-write-bytes! sio (aget offs (int i)) 0
                                                        (let [cb ^bytes (:bytes c) n (int (:size c)) nb (byte-array n)]
                                                          (enc/arraycopy cb 0 nb 0 (int (min (alength cb) n)))
                                                          nb)))
                             (doseq [r relocs]
                               (alloc/-sio-write-i32! sio (aget offs (int (:owner r)))
                                                      (long (:field r))
                                                      (aget offs (int (:target r)))))
                             (reopen sio (aget offs 0)))))
                a)]
    (cond (ea-atom? x)
          (ea/read-pinned x (fn [v] (if (satisfies? d/IEveRoot v)
                                      (place (d/-eve-sio v) (d/-root-header-off v))
                                      (do (swap! a (fn [_] v)) a))))
          (satisfies? d/IEveRoot x)
          (place (d/-eve-sio x) (d/-root-header-off x))
          :else
          (do (swap! a (fn [_] x)) a))))

;; ---------------------------------------------------------------------------
;; the :zip foreign policy (eve.atom's resolve-new-ptr branch calls in)
;; ---------------------------------------------------------------------------

(defn- place-walked!
  "The walk's chunks claimed, copied and rewritten straight into sio (the
   transfer-direct! route, T1.6): the root's new offset. No image is
   assembled, so there is nothing to vouch: the blocks are the walk's own
   reads of a live slab value (its section-3 reference refusal fused in), not
   bytes from outside. On a failure every block claimed is freed and the
   failure rethrown, as place's execute-plan! does."
  ^long [sio {:keys [chunks relocs]}]
  (let [n (count chunks)
        offs (claim-blocks! sio (mapv (fn [c] {:size (:size c)}) chunks))]
    (try
      ;; every pointer rewritten in its owner's bytes first (the walk's
      ;; chunk bytes are this placement's own copies, exactly :size long),
      ;; then one block write per block: no per-pointer slab write
      (run! (fn [r]
              (put-i32! (:bytes (nth chunks (int (:owner r))))
                        (long (:field r))
                        (aget offs (int (:target r)))))
            relocs)
      (dotimes [i n]
        (alloc/-sio-write-bytes! sio (aget offs i) 0 (:bytes (nth chunks i))))
      (aget offs 0)
      (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) t
        (dotimes [i n]
          (try (alloc/-sio-free! sio (aget offs i))
               (catch #?(:cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil)))
        (throw t)))))

(defn zip-root!
  "Place the foreign slab value v into sio (a swap attempt's domain) and
   return its root offset there. No host collection is built. The value's
   blocks go straight from the walk into sio (place-walked!): the image
   route (assemble, open-checked, validate, plan) vouched bytes Eve had
   just produced itself from a live value, and cost ~70% of a 4000-datom
   eve.db.ds delivery."
  ^long [sio v]
  (when-not (satisfies? d/IEveRoot v)
    (refuse! :not-a-slab-value
             "zip: not a slab value — a scalar is never a flat value (FORMAT-V1 §2)"
             {:value-type (type v)}))
  (let [src (d/-eve-sio v)
        root (d/-root-header-off v)]
    (when (or (nil? src) (== root -1))
      (refuse! :not-a-slab-value
               "zip: the value has no slab root (it is not rooted in a domain)"
               {}))
    (place-walked! sio (walk src root))))

(defn- foreign-root?
  "v is a slab value rooted in a different domain than sio's."
  [sio x]
  (and (not (or (nil? x) (string? x) (number? x) (keyword? x) (boolean? x) (symbol? x)))
       (satisfies? d/IEveRoot x)
       (let [vs (d/-eve-sio x)]
         (and (some? vs)
              (not (identical? (alloc/sio-root vs) (alloc/sio-root sio)))))))

(declare zip-walk)

(defn- zip-walk [sio x]
  (cond (foreign-root? sio x)
        [(reopen sio (zip-root! sio x)) true]
        (map? x)
        (let [rs (mapv (fn [[k v]] (let [[k2 kc] (zip-walk sio k)
                                         [v2 vc] (zip-walk sio v)]
                                     [[k2 v2] (or kc vc)]))
                       x)]
          (if (some second rs) [(into {} (map first) rs) true] [x false]))
        (vector? x)
        (let [rs (mapv (fn [e] (zip-walk sio e)) x)]
          (if (some second rs) [(mapv first rs) true] [x false]))
        (set? x)
        (let [rs (mapv (fn [e] (zip-walk sio e)) x)]
          (if (some second rs) [(into #{} (map first) rs) true] [x false]))
        (sequential? x)
        (let [rs (mapv (fn [e] (zip-walk sio e)) x)]
          (if (some second rs) [(doall (map first rs)) true] [x false]))
        :else [x false]))

(defn zip-nested
  "A plain value with every foreign slab value inside it placed into sio
   and replaced by its placed counterpart; the value itself (identical)
   when it holds nothing foreign. Stores through the plain writers then
   never meet a foreign pointer (T1.1's nested half, :zip only)."
  [sio v]
  (first (zip-walk sio v)))
