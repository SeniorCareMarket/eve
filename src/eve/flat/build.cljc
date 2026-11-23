(ns eve.flat.build
  "Lane B1 (IMPL-PLAN §3.3): a builder with no garbage.

   Plain data -> Eve blocks in final layout, in one pass, in an append-only
   build buffer: every block allocated is reachable from the root when the
   build finishes, so the buffer IS an image (FORMAT-V1 §9) with no
   compaction pass. Sealing appends the block table and the relocation
   table (found by one walk of eve.retire/children over the finished
   blocks — metadata only, no byte moves) and stamps the chunk header
   (eve.flat.contract).

   Why a new builder, when swap! already writes plain data into a slab?
   Today's writers assemble vectors and lists bottom-up already, but the
   map and set writers build their HAMT by repeated assoc/conj: every
   insert path-copies, and the writer frees the replaced nodes back to the
   allocator. In a slab that churn is the allocator cost M6 prices; in an
   append-only buffer, where free is a no-op, it leaves a 10,000-entry
   map 6.2 % live (M7). This builder groups a map's or set's entries by
   hash fragment, level by level, and allocates every node once, in the
   exact layout eve.map / eve.set / eve.vec / eve.list read — the built
   value is the value today's writer produces, with the same root digest
   (B1.1), and takes a further assoc in the same buffer (the buffer is
   the builder's own slab I/O; sealing freezes it).

   Coverage: nil and the codec's scalars, maps, sets, vectors, lists and
   seqs, nested to any depth. A type the direct writers do not cover
   falls back to today's writer for that subtree and is recorded in the
   build's :fallbacks (B1.4): a host array (today's array writer), a
   value with no Eve bytes (written by reference, as today), an Eve
   value of a foreign domain (materialized with eve->clj and rebuilt —
   its offsets mean nothing in this buffer)."
  (:require ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.deftype-proto.alloc :as alloc]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.deftype-proto.data :as d]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.deftype-proto.serialize :as ser]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.encoding :as enc]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.hamt-util :as hu]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.list :as elist]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.map :as emap]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.retire :as retire]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.set :as eset]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.slab-io :as slab]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.flat.contract :as contract]
   ; required for load on both passes (the flat build cone); the alias is exercised on the :clj pass 
   #_{:clj-kondo/ignore [:unused-namespace]}   [eve.vec :as evec]
   #?(:clj [eve.codec :as codec]))
  ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
  #?(:clj (:import #_{:clj-kondo/ignore [:unused-import]} [java.util ArrayList Arrays HashMap])))

#_{:clj-kondo/ignore [:unused-binding]} ; bindings in this form are used on the :cljs pass only; the :clj arm does not reference them (per-pass binding surface)
#?(:clj
   (do

;; ---------------------------------------------------------------------------
;; The build buffer: a slab I/O whose allocations bump a tail and are logged
;; ---------------------------------------------------------------------------

     (definterface IBuildSio
       (^bytes buf [])
       (^long tail [])
       (^long allocCount [])
       (blockSize [^long off])
       (allocations [])
       (^boolean frozen [])
       (seal [])
       (^long appendRaw [^bytes bs]))

     (deftype BuildSio [^:unsynchronized-mutable ^bytes b
                        ^:unsynchronized-mutable ^long t
                        ^ArrayList offs
                        ^ArrayList szs
                        ^HashMap by-off
                        ^:unsynchronized-mutable frozen-flag]
       IBuildSio
       (buf [_] b)
       (tail [_] t)
       (allocCount [_] (.size offs))
       (blockSize [_ off] (let [s (.get by-off (long off))] (if (nil? s) -1 (long s))))
       (allocations [_]
         (mapv (fn [i] [(long (.get offs i)) (long (.get szs i))]) (range (.size offs))))
       (frozen [_] frozen-flag)
       (seal [_] (set! frozen-flag true) nil)
       (appendRaw [_ bs]
         (let [off t
               end (+ off (alength ^bytes bs))]
           (when (> end (alength b))
             (set! b (Arrays/copyOf b (int (max end (* 2 (alength b)))))))
           (System/arraycopy ^bytes bs 0 b (int off) (alength ^bytes bs))
           (set! t end)
           off))
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
       (-sio-read-i32 [_ o f]
         (let [i (int (+ (long o) (long f)))]
           (long (unchecked-int
                  (bit-or (bit-and (long (aget b i)) 0xFF)
                          (bit-shift-left (bit-and (long (aget b (inc i))) 0xFF) 8)
                          (bit-shift-left (bit-and (long (aget b (+ i 2))) 0xFF) 16)
                          (bit-shift-left (bit-and (long (aget b (+ i 3))) 0xFF) 24))))))
       (-sio-write-i32! [_ o f v]
         (let [i (int (+ (long o) (long f))) v (long v)]
           (aset b i (unchecked-byte v))
           (aset b (inc i) (unchecked-byte (bit-shift-right v 8)))
           (aset b (+ i 2) (unchecked-byte (bit-shift-right v 16)))
           (aset b (+ i 3) (unchecked-byte (bit-shift-right v 24)))))
       (-sio-cas-i32! [this o f e dv]
         (let [cur (alloc/-sio-read-i32 this o f)]
           (when (== (long cur) (long e)) (alloc/-sio-write-i32! this o f dv))
           cur))
       (-sio-read-bytes [_ o f n]
         (let [i (int (+ (long o) (long f)))]
           (Arrays/copyOfRange b i (int (+ i (long n))))))
       (-sio-write-bytes! [_ o f src]
         (System/arraycopy ^bytes src 0 b (int (+ (long o) (long f))) (alength ^bytes src)))
       (-sio-alloc! [_ n]
         (when frozen-flag (throw (ex-info "eve.flat.build: allocation in a sealed build buffer" {})))
         (let [n (long n)
               off t
               end (+ off (bit-and (+ n 3) (bit-not 3)))]
           (when (> end (alength b))
             (set! b (Arrays/copyOf b (int (max end (* 2 (alength b)))))))
           (set! t end)
           (.add offs (int off))
           (.add szs (int n))
           (.put by-off off n)
           off))
       (-sio-free! [_ _o] true)
       (-sio-copy-block! [_ dd df s sf n]
         (System/arraycopy b (int (+ (long s) (long sf))) b (int (+ (long dd) (long df))) (int n))))

     (defn new-sio
       "A fresh build buffer: a slab I/O over one growable byte array, its
   allocations logged. The first 32 bytes are reserved for the chunk
   header (FORMAT-V1 §2), so the first block lands at offset 32."
       ([] (new-sio 65536))
       ([cap] (BuildSio. (byte-array (max (long cap) 64)) contract/header-size
                         (ArrayList.) (ArrayList.) (HashMap.) false)))

     (defn allocations
       "The [offset size] of every block allocated in sio, in allocation order."
       [sio]
       (.allocations ^IBuildSio sio))

     (defn alloc-count
       "How many blocks sio has allocated."
       [sio]
       (.allocCount ^IBuildSio sio))

     (defn read-i32-at
       "The little-endian i32 at absolute offset off in byte array ba."
       [^bytes ba off]
       (let [i (int off)]
         (long (unchecked-int
                (bit-or (bit-and (long (aget ba i)) 0xFF)
                        (bit-shift-left (bit-and (long (aget ba (inc i))) 0xFF) 8)
                        (bit-shift-left (bit-and (long (aget ba (+ i 2))) 0xFF) 16)
                        (bit-shift-left (bit-and (long (aget ba (+ i 3))) 0xFF) 24))))))

;; ---------------------------------------------------------------------------
;; Encoding one value to its Eve bytes, building what it names
;; (mirrors eve.codec/value+sio->eve-bytes branch for branch, with the
;; direct writers below in place of the registered collection writers)
;; ---------------------------------------------------------------------------

     (def ^:private NIL alloc/NIL_OFFSET)

     (defn- scalar? [v]
       (or (nil? v) (boolean? v) (integer? v) (float? v) (string? v)
           (keyword? v) (symbol? v) (enc/host-char? v)
           (uuid? v) (enc/date? v) (enc/ratio? v) (enc/bigdec? v)))

     (defn- ptr-bytes [tag off]
       (codec/encode-sab-pointer tag off))

     (defn- record-fallback! [ctx m]
       (swap! (:fallbacks ctx) conj m)
       nil)

     (declare build-value)

     (defn- encode-val
       "v's Eve bytes as a value in the build: a scalar inline; a collection
   the 7-byte pointer to the block this builder just built for it."
       [ctx v]
       (let [sio (:sio ctx)]
         (cond
           (scalar? v)
      ;; encode-value, not value->eve-bytes: the same bytes for a scalar
      ;; (its sio goes unused on the scalar branch), measured ~2.7x
      ;; cheaper per call on this host.
           (codec/encode-value sio v)

           (satisfies? d/IEveRoot v)
           (if (identical? (d/-eve-sio v) sio)
             (ptr-bytes (let [h (ser/read-header-tag sio (d/-root-header-off v))]
                          (case (int h) 0xED 0x10 0xEE 0x11 h))
                        (d/-root-header-off v))
             (do (record-fallback! ctx {:reason :eve-value :type (type v)})
                 (encode-val ctx (d/eve->clj v))))

           (map? v) (ptr-bytes 0x10 (build-value ctx :map v))
           (set? v) (ptr-bytes 0x11 (build-value ctx :set v))
           (list? v) (ptr-bytes 0x13 (build-value ctx :list v))
           (or (vector? v) (sequential? v)) (ptr-bytes 0x12 (build-value ctx :vec v))

           (or (satisfies? d/IBackingArray v) (enc/host-array? v))
           (do (record-fallback! ctx {:reason :host-array :type (type v)})
               (codec/encode-value sio v))

           :else
           (do (record-fallback! ctx {:reason :reference :type (type v)})
               (codec/encode-value sio v)))))

     (defn- value-block!
       "A value block in sio: [len:u32][bytes]."
       [sio ^bytes bs]
       (let [n (alength bs)
             off (slab/alloc! sio (+ 4 n))]
         (slab/write-i32! sio off 0 n)
         (when (pos? n) (slab/write-bytes! sio off 4 bs))
         off))

;; ---------------------------------------------------------------------------
;; Vectors and lists: assembled in one pass, as today's writers assemble them
;; ---------------------------------------------------------------------------

     (defn- build-vec [ctx v]
       (let [sio (:sio ctx)
             val-offs (mapv (fn [e] (value-block! sio (encode-val ctx e))) (vec v))]
         (d/-root-header-off (evec/build-vec sio val-offs))))

     (defn- build-list [ctx v]
       (let [sio (:sio ctx)
             elems (vec v)
             cnt (count elems)]
         (if (zero? cnt)
           (elist/write-list-header! sio 0 NIL)
           (let [head (loop [i (dec cnt) next NIL]
                        (if (neg? i)
                          next
                          (let [vb (encode-val ctx (nth elems i))
                                n (alength ^bytes vb)
                                cell (slab/alloc! sio (+ 8 n))]
                            (slab/write-i32! sio cell 0 next)
                            (slab/write-i32! sio cell 4 n)
                            (when (pos? n) (slab/write-bytes! sio cell 8 vb))
                            (recur (dec i) cell))))]
             (elist/write-list-header! sio cnt head)))))

;; ---------------------------------------------------------------------------
;; The HAMT, bottom-up (maps and sets share the engine; the node shapes
;; are eve.map's and eve.set's own, mirrored field for field)
;; ---------------------------------------------------------------------------

     (defn- frag [^long kh ^long shift]
       (bit-and (unsigned-bit-shift-right kh shift) 0x1f))

     (defn- all-same-kh?
       "Every entry in the ArrayList g carries the first entry's hash."
       [^ArrayList g]
       (let [kh (nth (.get g 0) 0)
             n (.size g)]
         (loop [i 1]
           (cond (== i n) true
                 (== kh (nth (.get g i) 0)) (recur (inc i))
                 :else false))))

     (defn- kv-total-cache [n] (if (< n 65536) n 0))

     (defn- map-node!
       "One bitmap node of a map: children and data as [frag x] pairs in
   ascending fragment order (hamt-build hands them over sorted)."
       [sio children data]
       (let [nc (count children)
             nd (count data)
             kv-size (loop [i 0 a 0]
                       (if (== i nd)
                         a
                         (let [[_ kb vb] (nth (nth data i) 1)]
                           (recur (inc i) (+ a 4 (alength ^bytes kb) 4 (alength ^bytes vb))))))
             data-bm (loop [i 0 a 0]
                       (if (== i nd) a (recur (inc i) (bit-or a (bit-shift-left 1 (nth (nth data i) 0))))))
             node-bm (loop [i 0 a 0]
                       (if (== i nc) a (recur (inc i) (bit-or a (bit-shift-left 1 (nth (nth children i) 0))))))
             off (slab/alloc! sio (+ 12 (* 4 nc) (* 4 nd) kv-size))]
         (slab/write-u8! sio off 0 1)
         (slab/write-u8! sio off 1 0)
         (slab/write-u16! sio off 2 (kv-total-cache kv-size))
         (slab/write-i32! sio off 4 data-bm)
         (slab/write-i32! sio off 8 node-bm)
         (loop [i 0]
           (when (< i nc)
             (slab/write-i32! sio off (+ 12 (* 4 i)) (nth (nth children i) 1))
             (recur (inc i))))
         (loop [i 0]
           (when (< i nd)
             (slab/write-i32! sio off (+ 12 (* 4 nc) (* 4 i)) (nth (nth (nth data i) 1) 0))
             (recur (inc i))))
         (loop [i 0 pos (+ 12 (* 4 nc) (* 4 nd))]
           (when (< i nd)
             (let [[_ kb vb] (nth (nth data i) 1)
                   klen (alength ^bytes kb)
                   vlen (alength ^bytes vb)]
               (slab/write-i32! sio off pos klen)
               (when (pos? klen) (slab/write-bytes! sio off (+ pos 4) kb))
               (slab/write-i32! sio off (+ pos 4 klen) vlen)
               (when (pos? vlen) (slab/write-bytes! sio off (+ pos 4 klen 4) vb))
               (recur (inc i) (+ pos 4 klen 4 vlen)))))
         off))

     (defn- map-collision!
       "A map collision node: entries [[kh kb vb] ...] of one hash."
       [sio kh entries]
       (let [kv-size (reduce (fn [a [_ kb vb]] (+ a 4 (alength ^bytes kb) 4 (alength ^bytes vb))) 0 entries)
             off (slab/alloc! sio (+ 8 kv-size))]
         (slab/write-u8! sio off 0 3)
         (slab/write-u8! sio off 1 (count entries))
         (slab/write-u16! sio off 2 0)
         (slab/write-i32! sio off 4 kh)
         (reduce (fn [pos [_ kb vb]]
                   (let [klen (alength ^bytes kb)
                         vlen (alength ^bytes vb)]
                     (slab/write-i32! sio off pos klen)
                     (when (pos? klen) (slab/write-bytes! sio off (+ pos 4) kb))
                     (slab/write-i32! sio off (+ pos 4 klen) vlen)
                     (when (pos? vlen) (slab/write-bytes! sio off (+ pos 4 klen 4) vb))
                     (+ pos 4 klen 4 vlen)))
                 8 entries)
         off))

     (defn- set-node!
       "One bitmap node of a set: children and data as [frag x] pairs in
   ascending fragment order — eve.set's node: no hash array, values
   directly after the children."
       [sio children data]
       (let [nc (count children)
             nd (count data)
             vals-size (loop [i 0 a 0]
                         (if (== i nd)
                           a
                           (let [[_ vb] (nth (nth data i) 1)]
                             (recur (inc i) (+ a 4 (alength ^bytes vb))))))
             data-bm (loop [i 0 a 0]
                       (if (== i nd) a (recur (inc i) (bit-or a (bit-shift-left 1 (nth (nth data i) 0))))))
             node-bm (loop [i 0 a 0]
                       (if (== i nc) a (recur (inc i) (bit-or a (bit-shift-left 1 (nth (nth children i) 0))))))
             off (slab/alloc! sio (+ 12 (* 4 nc) vals-size))]
         (slab/write-u8! sio off 0 1)
         (slab/write-u8! sio off 1 0)
         (slab/write-u16! sio off 2 0)
         (slab/write-i32! sio off 4 data-bm)
         (slab/write-i32! sio off 8 node-bm)
         (loop [i 0]
           (when (< i nc)
             (slab/write-i32! sio off (+ 12 (* 4 i)) (nth (nth children i) 1))
             (recur (inc i))))
         (loop [i 0 pos (+ 12 (* 4 nc))]
           (when (< i nd)
             (let [[_ vb] (nth (nth data i) 1)
                   vlen (alength ^bytes vb)]
               (slab/write-i32! sio off pos vlen)
               (when (pos? vlen) (slab/write-bytes! sio off (+ pos 4) vb))
               (recur (inc i) (+ pos 4 vlen)))))
         off))

     (defn- set-collision!
       "A set collision node: entries [[vh vb] ...] of one hash."
       [sio vh entries]
       (let [vals-size (reduce (fn [a [_ vb]] (+ a 4 (alength ^bytes vb))) 0 entries)
             off (slab/alloc! sio (+ 12 vals-size))]
         (slab/write-u8! sio off 0 2)
         (slab/write-u8! sio off 1 (count entries))
         (slab/write-u16! sio off 2 0)
         (slab/write-i32! sio off 4 vh)
         (slab/write-i32! sio off 8 0)
         (reduce (fn [pos [_ vb]]
                   (let [vlen (alength ^bytes vb)]
                     (slab/write-i32! sio off pos vlen)
                     (when (pos? vlen) (slab/write-bytes! sio off (+ pos 4) vb))
                     (+ pos 4 vlen)))
                 12 entries)
         off))

     (defn- hamt-build
       "The node offset for entries at shift, built bottom-up: children are
   built before the node that names them; every node is allocated once.
   make-node: (fn [children data]) -> off; make-collision: (fn [kh
   entries]) -> off; an entry is [kh & rest]."
       [entries shift make-node make-collision]
  ;; Grouping rides a 32-slot array of ArrayLists, not persistent maps:
  ;; a level's grouping is allocation-light, and scanning the slots in
  ;; order hands the node writers their fragments already sorted. Entry
  ;; order inside a group is insertion order (a collision node keeps
  ;; it, as hamt-assoc's appends do).
       (let [groups (object-array 32)]
         (doseq [e entries]
           (let [f (frag (nth e 0) shift)
                 g (aget groups (int f))]
             (if (nil? g)
               (aset groups (int f) (doto (ArrayList.) (.add e)))
               (.add ^ArrayList g e))))
         (loop [f 0 children [] data []]
           (if (== f 32)
             (make-node children data)
             (let [g (aget groups (int f))]
               (if (nil? g)
                 (recur (inc f) children data)
                 (let [n (.size ^ArrayList g)]
                   (cond
                     (== n 1)
                     (recur (inc f) children (conj data [f (.get ^ArrayList g 0)]))

                     (all-same-kh? g)
                     (recur (inc f)
                            (conj children [f (make-collision (nth (.get ^ArrayList g 0) 0) (vec g))])
                            data)

                     (>= shift 30)
                     (recur (inc f)
                            (conj children [f (make-collision (nth (.get ^ArrayList g 0) 0) (vec g))])
                            data)

                     :else
                     (recur (inc f)
                            (conj children [f (hamt-build (vec g) (+ shift 5) make-node make-collision)])
                            data)))))))))

     (defn- key-info
       "A key's [bytes hash], memoized for the length of one build: record
   and message shapes repeat the same keys thousands of times, and the
   encoding is a pure function of the key. Today's writer pays the
   encode per assoc; a builder walking the whole value at once can pay
   it once per distinct key."
       [ctx k]
       (let [^HashMap cache (:key-cache ctx)
             hit (.get cache k)]
         (if (nil? hit)
           (let [kb (codec/encode-key k)
                 info [kb (int (hu/portable-hash-bytes kb))]]
             (.put cache k info)
             info)
           hit)))

     (defn- build-map [ctx m]
       (let [sio (:sio ctx)
             entries (mapv (fn [[k v]]
                             (let [[kb kh] (key-info ctx k)]
                               [kh kb (encode-val ctx v)]))
                           m)]
         (if (empty? entries)
           (emap/write-map-header! sio 0 NIL)
           (let [root (hamt-build entries 0
                                  (fn [children data] (map-node! sio children data))
                                  (fn [kh es] (map-collision! sio kh es)))]
             (emap/write-map-header! sio (count m) root)))))

     (defn- build-set [ctx s]
       (let [sio (:sio ctx)
             entries (mapv (fn [v]
                             (let [[vb vh] (key-info ctx v)]
                               [vh vb]))
                           s)]
         (if (empty? entries)
           (eset/write-set-header! sio 0 NIL)
           (let [root (hamt-build entries 0
                                  (fn [children data] (set-node! sio children data))
                                  (fn [vh es] (set-collision! sio vh es)))]
             (eset/write-set-header! sio (count s) root)))))

     (defn build-value
       "The header offset of kind's block for plain collection v in the build
   (kind :map, :set, :vec, :list). Internal: build! is the entry point."
       [ctx kind v]
       (case kind
         :map (build-map ctx v)
         :set (build-set ctx v)
         :vec (build-vec ctx v)
         :list (build-list ctx v)))

;; ---------------------------------------------------------------------------
;; The walk: block table + relocation table over finished blocks
;; (eve.retire/children finds the pointers; positions are byte offsets —
;;  the same positions the probe copier recorded, doc/flat/probes/lib.clj)
;; ---------------------------------------------------------------------------

     (defn- u32 [x] (bit-and (long x) 0xFFFFFFFF))

     (defn- kv-value-pos
       "The byte offset, within the node at off, of the value-length field of
   the entry reached by walking entries from start to the entry whose
   running index satisfies pick. The value's bytes begin 4 past it; a
   pointer's offset field sits 3 into those bytes."
       [sio off start pick]
       (loop [i 0 pos start]
         (let [klen (alloc/-sio-read-i32 sio off pos)
               vpos (+ pos 4 klen)
               vlen (alloc/-sio-read-i32 sio off vpos)]
           (if (pick i)
             vpos
             (recur (inc i) (+ vpos 4 vlen))))))

     (defn- child-byte-pos
       "The byte offset within the block at off where the pointer retire's
   logical position pos names lives."
       [sio kind off pos]
       (case (if (keyword? kind) kind (nth kind 0))
         :val (let [t (ser/read-header-tag sio off)]
                (case (int t)
                  (0xED 0xEE 0x13) 8
                  0x12 (if (zero? pos) 12 16)
                  0x1F (let [n (alloc/-sio-read-i32 sio off 4)
                             pb (bit-and (+ 8 (* 2 n) 3) (bit-not 3))]
                         (+ pb (* 4 pos)))
             ;; an eve-deftype's layout positions are byte offsets already
                  pos))
         :map-node (let [dbm (u32 (alloc/-sio-read-i32 sio off 4))
                         nbm (u32 (alloc/-sio-read-i32 sio off 8))
                         nc (hu/popcount32 (unchecked-int nbm))]
                     (if (< pos 32)
                       (+ 12 (* 4 (hu/popcount32 (unchecked-int (bit-and nbm (dec (bit-shift-left 1 pos)))))))
                       (let [start (+ 12 (* 4 nc) (* 4 (hu/popcount32 (unchecked-int dbm))))]
                         (if (< pos 64)
                           (let [target (bit-shift-left 1 (- pos 32))]
                             (loop [r dbm p start]
                               (let [klen (alloc/-sio-read-i32 sio off p)
                                     vpos (+ p 4 klen)
                                     vlen (alloc/-sio-read-i32 sio off vpos)]
                                 (if (== (bit-and r (- r)) target)
                                   (+ vpos 4 3)
                                   (recur (bit-and r (dec r)) (+ vpos 4 vlen))))))
                           (+ (kv-value-pos sio off 8 (fn [i] (== i (- pos 64)))) 4 3)))))
         :set-node (let [nbm (u32 (alloc/-sio-read-i32 sio off 8))]
                     (+ 12 (* 4 (hu/popcount32 (unchecked-int (bit-and nbm (dec (bit-shift-left 1 pos))))))))
         :vblock 7
         :cell (if (zero? pos) 0 11)
         :vec (* 4 pos)
         :leaf (* 4 pos)))

     #_{:clj-kondo/ignore [:unused-binding]} ; ck in the retire/children destructuring below is used on the :cljs pass only
     (defn image-tables
       "The image tables of the value at root in a build sio: {:blocks
   [{:offset :size} ...] (root first, then ascending offset),
   :relocations [{:position :owner :target} ...] (owner/target are
   block ordinals in :blocks; :position is the pointer field's byte
   offset in the buffer), :root the root block's offset}."
       [sio root]
       (if (== (long root) NIL)
         {:blocks [] :relocations [] :root NIL}
         (let [seen (volatile! #{})
               blocks (volatile! [])
               relocs (volatile! [])]
           (loop [stack [[:val root]]]
             (when (seq stack)
               (let [[k o] (peek stack)
                     stack (pop stack)
                     key (u32 o)]
                 (if (contains? @seen key)
                   (recur stack)
                   (do (vswap! seen conj key)
                       (vswap! blocks conj (long o))
                       (let [kids (retire/children sio k o)]
                         (doseq [[pos ck c] kids]
                           (vswap! relocs conj {:position (+ (long o) (child-byte-pos sio k o pos))
                                                :owner (long o)
                                                :target (long c)}))
                         (recur (into stack (map (fn [[_ ck c]] [ck c])) kids))))))))
           (let [offs (vec (sort (distinct @blocks)))
                 ordered (into [root] (remove (fn [o] (== (long o) (long root))) offs))
                 ord (into {} (map-indexed (fn [i o] [(long o) i]) ordered))]
             {:blocks (mapv (fn [o] {:offset (long o) :size (.blockSize ^IBuildSio sio (long o))}) ordered)
              :relocations (mapv (fn [r] {:position (:position r)
                                          :owner (get ord (:owner r))
                                          :target (get ord (:target r))})
                                 (sort-by :position @relocs))
              :root (long root)}))))

;; ---------------------------------------------------------------------------
;; build! and image
;; ---------------------------------------------------------------------------

     (defn- reopen [sio off]
       (let [t (ser/read-header-tag sio off)
             ctor (ser/get-header-constructor t)]
         (when ctor (ctor sio off))))

     (defn build!
       "Build plain value v in a fresh build buffer. Returns
   {:sio :root :value :fallbacks} — the buffer's slab I/O, the root
   block's offset (NIL for nil), the value read back through the buffer
   (the plain scalar itself for a scalar root), and the fallbacks the
   build took, each {:reason :type}. The value stays updatable in the
   buffer (assoc/conj append) until image seals it."
       [v]
       (if (satisfies? d/IEveRoot v)
    ;; a foreign Eve value as the root: materialize and rebuild, saying so
         (let [b (build! (d/eve->clj v))]
           (update b :fallbacks (fn [fs] (into [{:reason :eve-value :type (type v)}] fs))))
         (let [sio (new-sio)
               ctx {:sio sio :fallbacks (atom []) :key-cache (HashMap.)}
               root (cond
                      (nil? v) NIL
                      (scalar? v) (alloc/value->scalar-block! sio v)
                      (map? v) (build-map ctx v)
                      (set? v) (build-set ctx v)
                      (vector? v) (build-vec ctx v)
                      (or (list? v) (seq? v)) (build-list ctx v)
                      (or (satisfies? d/IBackingArray v) (enc/host-array? v))
                      (do (record-fallback! ctx {:reason :host-array :type (type v)})
                          (let [w (ser/get-coll-writer :array)]
                            (if w
                              (w sio nil v)
                              (throw (ex-info "eve.flat.build: no :array writer registered" {})))))
                      :else
                      (do (record-fallback! ctx {:reason :reference :type (type v)})
                          (alloc/value->scalar-block! sio v)))]
           {:sio sio
            :root (long root)
            :value (cond (nil? v) nil
                         (scalar? v) v
                         :else (or (reopen sio root) v))
            :fallbacks @(:fallbacks ctx)})))

     (defn- put-u32-hinted!
       "put-u32! over a ^bytes-hinted array. eve.flat.contract's own writers
   take ba unhinted, so every aset is reflective — measured at ~0.8 ms per
   table entry on this box, against ~0 here. The bytes written are the
   contract's exactly; B1.3 reads both tables back through the contract."
       [^bytes ba ^long i ^long v]
       (aset ba (int i) (unchecked-byte v))
       (aset ba (int (inc i)) (unchecked-byte (bit-shift-right v 8)))
       (aset ba (int (+ i 2)) (unchecked-byte (bit-shift-right v 16)))
       (aset ba (int (+ i 3)) (unchecked-byte (bit-shift-right v 24))))

     (defn image
       "Seal a build: append the block and relocation tables to its buffer,
   stamp the chunk header (kind :image, layout 1), freeze the buffer.
   Returns {:bytes :length :root :blocks :relocations :fallbacks} —
   the chunk byte array and the tables as eve.flat.contract reads
   them. Seal a build before updating its value: the tables name the
   value as built."
       [b]
       (let [sio (:sio b)
             {:keys [blocks relocations root]} (image-tables sio (:root b))
             align8 (fn [n] (bit-and (+ n 7) (bit-not 7)))
             tables-off (align8 (.tail ^IBuildSio sio))
             _ (when (> tables-off (.tail ^IBuildSio sio))
                 (.appendRaw ^IBuildSio sio (byte-array (- tables-off (.tail ^IBuildSio sio)))))
             bt (byte-array (* 8 (count blocks)))
             _ (doseq [[i {:keys [offset size]}] (map-indexed vector blocks)]
                 (put-u32-hinted! bt (* i 8) offset)
                 (put-u32-hinted! bt (+ (* i 8) 4) size))
             bt-off (.appendRaw ^IBuildSio sio bt)
             rt (byte-array (* 12 (count relocations)))
             _ (doseq [[i {:keys [position owner target]}] (map-indexed vector relocations)]
                 (put-u32-hinted! rt (* i 12) position)
                 (put-u32-hinted! rt (+ (* i 12) 4) owner)
                 (put-u32-hinted! rt (+ (* i 12) 8) target))
             rt-off (.appendRaw ^IBuildSio sio rt)
             length (align8 (.tail ^IBuildSio sio))
             buf (.buf ^IBuildSio sio)]
         (contract/write-header! buf 0 {:kind :image
                                        :length length
                                        :root root
                                        :layout-id contract/layout-canonical
                                        :symtab bt-off
                                        :shapetab rt-off})
         (.seal ^IBuildSio sio)
         {:bytes (Arrays/copyOf buf (int length))
          :length length
          :root root
          :blocks blocks
          :relocations relocations
          :fallbacks (:fallbacks b)}))

     (defn build-image
       "build! + image: the sealed chunk of plain value v."
       [v]
       (image (build! v)))) ;; end :clj

   :cljs
   (do
     (defn build! [_v]
       (throw (ex-info "eve.flat.build: not built on ClojureScript yet" {})))
     (defn build-image [_v]
       (throw (ex-info "eve.flat.build: not built on ClojureScript yet" {})))
     (defn image [_b]
       (throw (ex-info "eve.flat.build: not built on ClojureScript yet" {})))))

