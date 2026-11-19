(ns eve.flat.freeze
  "FLAT-IN-ATOM (doc/flat/FLAT-IN-ATOM-PLAN.md): a packed chunk as
   atom content. A frozen value too large for one block lives in
   a domain as a chain under one header block — header type-id
   0x20 (eve's fixed fast-tag band; see CHUNK-TAG below):

     header (48 bytes): [0]=0x20 [4]=total stream bytes (i32)
       [8]=first segment (i32 slab offset, -1 = none)
       [12..43]=digest words (8 x i32) [44]=flags (bit 0: digest
       present; bit 1: stream is framed — a chunk directory,
       see frame-stream)
     segment (scan kind 7, ONE size-routed block each):
       [0]=next (i32) [4]=payload length (i32) [8..]=payload,
       cut at the flat ladder's top rung (16 KiB less the link
       header — plan S2b)

   The chain carries a byte STREAM: for a one-chunk value the
   raw chunk bytes; for a value spanning chunks, a framed
   stream (directory of chunk lengths, then the chunks in
   order), opened as a spanning batch handle.

   The value is CLOSED (plan R1): the chain is the only pointer
   structure, payload bytes are never pointer-interpreted
   (block-scan in eve.flat.transfer carries the matching cases).
   Reads walk the links once, linearly, gather the stream and
   open it; the gathered handle is cached on the value."
  (:require [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.slab-io :as slab]
            [eve.digest :as digest]
            [eve.encoding :as enc]
            [eve.atom :as ea]
            [eve.flat.chunk :as chunk]
            [eve.flat.sister :as sister]
            [eve.flat.source :as source]
            ;; The protocol hosts (bb, glojure) spell the frozen
            ;; types' surface through the seat's eve.core protocols,
            ;; as chunk's handles do there.
            #?@(:bb [[eve.core :as ecore]]
                :glj [[eve.core :as ecore]])))

;; 0x20: eve's fixed fast-tag band (0x00-0x3F) ends its assignments
;; at 0x1F; 0x20 is the first unassigned byte there. NOT 0xF0:
;; FORMAT-DECISIONS §9 reserves 0xF0-0xFF for process-private block
;; kinds, never written to a shared domain — a frozen chain is
;; shared-domain content by design.
(def ^:const CHUNK-TAG 32) ;; 0x20

;; The EXTENT form (a chunk stored as one variable-size block)
;; is REMOVED (Nov 13 2026, John's fixed-classes ruling): flat
;; objects live in fixed-size classes only, never the variable
;; class. Every frozen value is a chain (below), however small.
(def ^:const HEADER-SIZE 48)
;; The chain cut: segments carry at most this many payload
;; bytes — the seat's largest FIXED class less the 8-byte link
;; header, so every allocation a freeze makes is one fixed-
;; class block. This seat has the ladder (eve.deftype-proto
;; .coalesc's rung blocks inside class 6), so the stripe is
;; the ladder's top rung less the header: a full segment is
;; one 983,040-byte rung block, allocated through the flat
;; door (alloc/alloc-flat-block!); on a ctx without the door
;; the same call degrades to an ordinary variable block of
;; the same size. The final partial segment — the tail —
;; allocates through the ordinary path and sits in the
;; variable class. The chain is the unit the format defines
;; (16 KiB chunks of stream, last one partial); the stripe
;; is only how a seat's fixed classes carry it.
(def ^:const SEG-PAYLOAD 983032)

;; ---------------------------------------------------------------------------
;; byte helpers (headers/segments assembled in heap arrays, then
;; written to the slab in one call each)

;; v is written as its low 32 bits, never range-checked: slab
;; pointer words ([class:3 | index:29], what -sio-alloc! returns)
;; exceed Integer/MAX_VALUE as unsigned longs and are stored in
;; i32 fields bit-exact (sign-extended on the way back out).
;; byte stores go through eve.encoding/typed-aset! (subtype 1):
;; clojure.core/aset-byte is a host form glojure cannot resolve
;; ("unable to resolve host form: clojure.core.Array", measured in
;; the flat smoke's freeze row); typed-aset! is the encoding
;; adapter's portable store, present on every host.
(defn- put-u8!
  "One byte into a heap byte array. The :default arm is
   eve.encoding/typed-aset! (subtype 1), the portable store the
   comment above describes; on ClojureScript the encoding
   adapter's typed-aset! is a stub that writes nothing (its
   'never reached' posture assumes typed arrays are slab-backed
   there — freeze's heap header/segment arrays are the measured
   counterexample: every header byte was silently dropped and
   the chain walk read zeros), so the cljs arm stores directly."
  [b i v]
  #?(:cljs (aset b (int i) (bit-and v 0xFF))
     :default (enc/typed-aset! b 1 (int i) (bit-and v 0xFF))))

(defn- put-i32! [^bytes b ^long off ^long v]
  #?(:cljs (let [dv (js/DataView. (.-buffer b) (.-byteOffset b) (.-byteLength b))]
             (.setInt32 dv (int off) v true))
     :default (do (enc/typed-aset! b 1 (int off) (bit-and v 0xFF))
                  (enc/typed-aset! b 1 (int (+ off 1)) (bit-and (unsigned-bit-shift-right v 8) 0xFF))
                  (enc/typed-aset! b 1 (int (+ off 2)) (bit-and (unsigned-bit-shift-right v 16) 0xFF))
                  (enc/typed-aset! b 1 (int (+ off 3)) (bit-and (unsigned-bit-shift-right v 24) 0xFF)))))

;; ---------------------------------------------------------------------------
;; the chain: write + gather

(defn write-chain!
  "Write stream-bytes into sio's domain as a frozen chain and
   answer the header block's slab offset. digest-words (8 i32s)
   may be nil; framed? marks the stream as a framed multi-chunk
   stream (header flag bit 1). Each segment is ONE size-routed
   allocation at the ladder cut. The caller owns publishing the
   header as a value root (a swap does, through the FrozenValue
   this header constructs); blocks written here are plain slab
   allocations until then."
  ([sio ^bytes stream-bytes] (write-chain! sio stream-bytes nil false))
  ([sio ^bytes stream-bytes digest-words] (write-chain! sio stream-bytes digest-words false))
  ([sio ^bytes stream-bytes digest-words framed?]
   (write-chain! sio stream-bytes digest-words framed? false))
  ([sio ^bytes stream-bytes digest-words framed? typed?]
   (let [total (alength stream-bytes)
         nseg (int (quot (+ total (dec SEG-PAYLOAD)) SEG-PAYLOAD))
         ;; segments last-to-first, so each knows its next
         first-seg (loop [i (dec nseg) next (long -1)]
                     (if (neg? i)
                       next
                       (let [from (* i SEG-PAYLOAD)
                             len (min SEG-PAYLOAD (- total from))
                             ;; a full segment is one rung block through
                             ;; the flat door; the tail takes the
                             ;; ordinary path (the variable class)
                             off (if (== len SEG-PAYLOAD)
                                   (alloc/alloc-flat-block! sio (+ 8 len))
                                   (alloc/-sio-alloc! sio (+ 8 len)))
                             arr (enc/make-bytes (+ 8 len))]
                         (put-i32! arr 0 next)
                         (put-i32! arr 4 len)
                         (enc/arraycopy stream-bytes (int from) arr 8 (int len))
                         (alloc/-sio-write-bytes! sio off 0 arr)
                         (recur (dec i) (long off)))))
         hdr (alloc/-sio-alloc! sio HEADER-SIZE)
         harr (enc/make-bytes HEADER-SIZE)]
     (put-u8! harr 0 CHUNK-TAG)
     (put-i32! harr 4 total)
     (put-i32! harr 8 first-seg)
     (when digest-words
       (dotimes [w 8] (put-i32! harr (+ 12 (* 4 w)) (long (nth digest-words w)))))
     ;; flags: 1 digest latched, 2 framed stream, 4 typed root (a flat
     ;; eve-deftype: it reads back as its sister, EVE-FIX-PLAN X4)
     (put-u8! harr 44 (bit-or (if digest-words 1 0)
                              (if framed? 2 0)
                              (if typed? 4 0)))
     (alloc/-sio-write-bytes! sio hdr 0 harr)
     hdr)))

(defn frame-stream
  "A multi-chunk byte stream (plan S2b): [nchunks i32]
   [len i32 x n] then the chunks' bytes concatenated, in
   order. The two cut grids — stream segments and chunk
   lengths — are independent: segments slice the stream,
   the directory re-slices the gathered stream into chunks."
  ^bytes [chunks]
  (let [n (count chunks)
        total (+ 4 (* 4 n) (reduce + (map (fn [^bytes b] (alength b)) chunks)))
        out (enc/make-bytes total)]
    (put-i32! out 0 n)
    (loop [i 0 pos (long (+ 4 (* 4 n)))]
      (when (< i n)
        (let [^bytes c (nth chunks i) len (alength c)]
          (put-i32! out (+ 4 (* 4 i)) len)
          (enc/arraycopy c 0 out (int pos) (int len))
          (recur (inc i) (+ pos len)))))
    out))

(defn- get-i32 ^long [^bytes b ^long off]
  (bit-or (bit-and (aget b (int off)) 0xFF)
          (bit-shift-left (bit-and (aget b (int (+ off 1))) 0xFF) 8)
          (bit-shift-left (bit-and (aget b (int (+ off 2))) 0xFF) 16)
          (bit-shift-left (bit-and (aget b (int (+ off 3))) 0xFF) 24)))

(defn unframe-stream
  "A framed stream's chunk byte arrays, in order."
  [^bytes stream]
  (let [n (get-i32 stream 0)]
    (loop [i 0 pos (long (+ 4 (* 4 n))) out []]
      (if (== i n)
        out
        (let [len (get-i32 stream (+ 4 (* 4 i)))
              part (enc/make-bytes (int len))]
          (enc/arraycopy stream (int pos) part 0 (int len))
          (recur (inc i) (+ pos len) (conj out part)))))))

(defn read-chain-bytes
  "Gather the frozen chain under header-off in sio back into the
   chunk byte array it carries."
  ^bytes [sio ^long header-off]
  (let [total (slab/read-i32 sio header-off 4)
        out (enc/make-bytes total)]
    (loop [seg (long (slab/read-i32 sio header-off 8)) pos (long 0)]
      (when (not= seg -1)
        (let [len (slab/read-i32 sio seg 4)
              part (slab/read-bytes sio seg 8 len)]
          (enc/arraycopy part 0 out (int pos) (int len))
          (recur (long (slab/read-i32 sio seg 0)) (+ pos len)))))
    out))

(defn chain-digest-words
  "The digest words the header under header-off carries, or nil."
  [sio ^long header-off]
  (let [flags (slab/read-i32 sio header-off 44)]
    (when (== (bit-and flags 1) 1)
      (mapv (fn [w] (slab/read-i32 sio header-off (+ 12 (* 4 w)))) (range 8)))))

(defn dispose-chain!
  "Free the whole chain under header-off (the header's registered
   disposer): every segment, then the header."
  [sio ^long header-off]
  (loop [seg (long (slab/read-i32 sio header-off 8))]
    (when (not= seg -1)
      (let [nx (long (slab/read-i32 sio seg 0))]
        (slab/free! sio seg)
        (recur nx))))
  (slab/free! sio header-off))

;; ---------------------------------------------------------------------------
;; the value: a subtree (or root) whose reads delegate to the
;; opened chunk. It is an ordinary Eve value in the tree — the
;; root pointer neither knows nor cares that it is flat.

(declare -handle frozen-value?)
#?(:clj (declare based-of based-assoc based-pop))
;; The protocol hosts' spellings of the based/concat helpers
;; (eve.core calls instead of host collection calls), defined
;; after BasedValue below.
#?(:bb (declare pg-based-of pg-based-assoc pg-based-pop pg-concat-assoc-n)
    :glj (declare pg-based-of pg-based-assoc pg-based-pop pg-concat-assoc-n))

(defn plain-of
  "Materialize a chunk handle (or a plain value) into plain
   Clojure data: maps to maps, batches/vectors to vectors,
   leaves pass through. The upgrade path's conversion (plan
   S2): total, domain-free, cost = the value's size."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [k (plain-of v)])) x)
    (vector? x) (mapv plain-of x)
    (set? x) (into #{} (map plain-of) x)
    (sequential? x) (mapv plain-of x)
    ;; a frozen value materializes as the handle it wraps,
    ;; never as the view object itself
    (frozen-value? x) (plain-of (-handle x (.-sio x) (.-hdr x)))
    ;; a generated sister is none of the collection shapes:
    ;; its plain form is its field structure, each field
    ;; materialized in turn (a nested sister recurses here)
    (satisfies? source/ISister x)
    (into {} (map (fn [[k v]] [k (plain-of v)]))
          (source/-field-map (source/-sister-source x)))
    :else x))

#?(:bb (do (deftype FrozenValue [sio hdr cells]
           d/IEveRoot
           (-root-header-off [_] hdr)
           d/IEveSio
           (-eve-sio [_] sio)
           ecore/ICounted
           (-count [this] (ecore/count (-handle this sio hdr)))
           ecore/IIndexed
           (-nth [this i] (ecore/nth (-handle this sio hdr) i))
           (-nth [this i nf] (ecore/nth (-handle this sio hdr) i nf))
           ecore/ILookup
           (-lookup [this k] (ecore/get (-handle this sio hdr) k))
           (-lookup [this k nf] (ecore/get (-handle this sio hdr) k nf))
           ecore/IAssociative
           (-assoc [this k v] (try (ecore/assoc (-handle this sio hdr) k v)
                                   (catch UnsupportedOperationException _
                                     (pg-based-assoc this k v))))
           (-contains-key? [this k] (ecore/contains? (-handle this sio hdr) k))
           ecore/ISeqable
           (-seq [this] (ecore/seq (-handle this sio hdr)))
           ecore/ICollection
           (-conj [this x] (try (ecore/conj (-handle this sio hdr) x)
                                (catch UnsupportedOperationException _
                                  (ecore/conj (pg-based-of this) x))))
           ecore/IEmptyableCollection
           (-empty [_] (throw (UnsupportedOperationException. "empty on a frozen value")))
           ecore/IStack
           (-peek [this] (ecore/peek (-handle this sio hdr)))
           (-pop [this] (try (ecore/pop (-handle this sio hdr))
                             (catch UnsupportedOperationException _
                               (pg-based-pop this))))
           ecore/IEquiv
           (-equiv [this other] (ecore/equiv (-handle this sio hdr) other))
           ecore/IHash
           (-hash [this] (ecore/-hash (-handle this sio hdr)))
           Object
           (equals [this other] (or (identical? this other)
                                    (ecore/equiv (-handle this sio hdr) other)))
           (hashCode [this] (unchecked-int (ecore/-hash (-handle this sio hdr))))
           (toString [_] (str "#eve/frozen " hdr))))
    :glj (do (deftype FrozenValue [sio hdr cells]
            d/IEveRoot
            (-root-header-off [_] hdr)
            d/IEveSio
            (-eve-sio [_] sio)
            ecore/ICounted
            (-count [this] (ecore/count (-handle this sio hdr)))
            ecore/IIndexed
            (-nth [this i] (ecore/nth (-handle this sio hdr) i))
            (-nth [this i nf] (ecore/nth (-handle this sio hdr) i nf))
            ecore/ILookup
            (-lookup [this k] (ecore/get (-handle this sio hdr) k))
            (-lookup [this k nf] (ecore/get (-handle this sio hdr) k nf))
            ecore/IAssociative
            (-assoc [this k v] (try (ecore/assoc (-handle this sio hdr) k v)
                                    (catch UnsupportedOperationException _
                                      (pg-based-assoc this k v))))
            (-contains-key? [this k] (ecore/contains? (-handle this sio hdr) k))
            ecore/ISeqable
            (-seq [this] (ecore/seq (-handle this sio hdr)))
            ecore/ICollection
            (-conj [this x] (try (ecore/conj (-handle this sio hdr) x)
                                 (catch UnsupportedOperationException _
                                   (ecore/conj (pg-based-of this) x))))
            ecore/IEmptyableCollection
            (-empty [_] (throw (UnsupportedOperationException. "empty on a frozen value")))
            ecore/IStack
            (-peek [this] (ecore/peek (-handle this sio hdr)))
            (-pop [this] (try (ecore/pop (-handle this sio hdr))
                              (catch UnsupportedOperationException _
                                (pg-based-pop this))))
            ecore/IEquiv
            (-equiv [this other] (ecore/equiv (-handle this sio hdr) other))
            ecore/IHash
            (-hash [this] (ecore/-hash (-handle this sio hdr)))
            ecore/IPrintWithWriter
            (-pr-writer [_ w _] (ecore/-write w (str "#eve/frozen " hdr))))
           (defmethod print-method FrozenValue [x w] (ecore/-pr-writer x w nil)))
    :clj (deftype FrozenValue [sio ^long hdr cells]
     d/IEveRoot
     (-root-header-off [_] hdr)
     d/IEveSio
     (-eve-sio [_] sio)
     clojure.lang.ILookup
     (valAt [this k] (get (-handle this sio hdr) k))
     (valAt [this k nf] (get (-handle this sio hdr) k nf))
     clojure.lang.IPersistentCollection
     (count [this] (count (-handle this sio hdr)))
     ;; writes follow the upgrade law (UPGRADE-PLAN R2/R4):
     ;; an edit the chunk form expresses stays flat — the
     ;; handle's own path-copy answers. Only an edit the
     ;; handle refuses upgrades, and it upgrades to a
     ;; BasedValue EMBEDDING this frozen value by reference —
     ;; never to a materialization. plain-of stays available
     ;; as the explicit escape hatch.
     (cons [this x] (try (conj (-handle this sio hdr) x)
                         (catch #?(:jank jank.runtime.object_ref :clj UnsupportedOperationException :cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :default Throwable) _
                           (conj (based-of this) x))))
     (empty [_] (throw (UnsupportedOperationException. "empty on a frozen value")))
     (equiv [this other] (= (-handle this sio hdr) other))
     clojure.lang.Indexed
     (nth [this i] (nth (-handle this sio hdr) i))
     (nth [this i nf] (nth (-handle this sio hdr) i nf))
     clojure.lang.Associative
     (containsKey [this k] (contains? (-handle this sio hdr) k))
     (entryAt [this k] (find (-handle this sio hdr) k))
     (assoc [this k v] (try (assoc (-handle this sio hdr) k v)
                            (catch #?(:jank jank.runtime.object_ref :clj UnsupportedOperationException :cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :default Throwable) _
                              (based-assoc this k v))))
     clojure.lang.IPersistentStack
     (peek [this] (peek (-handle this sio hdr)))
     (pop [this] (try (pop (-handle this sio hdr))
                       (catch #?(:jank jank.runtime.object_ref :clj UnsupportedOperationException :cljs :default :cljd dynamic :cljr Exception :lpy python/BaseException :clje _ :glj go/any :default Throwable) _
                         (based-pop this))))
     clojure.lang.Seqable
     (seq [this] (seq (-handle this sio hdr)))
     clojure.lang.IHashEq
     (hasheq [this] (hash (-handle this sio hdr)))
     Object
     (equals [this other] (or (identical? this other) (= (-handle this sio hdr) other)))
     (hashCode [this] (hash (-handle this sio hdr)))
     (toString [_] (str "#eve/frozen " hdr))))

;; ---------------------------------------------------------------------------
;; the spanning handle: a value whose rows live in several chunks
;; (plan S2b). Per-chunk handles plus cumulative counts; nth/seq/
;; count/equiv delegate across the seams. Writes PATH-COPY
;; (UPGRADE-PLAN R4): the chain is a persistent list of links —
;; an edit re-packs only the touched link and shares every
;; other link object as-is.

(defn- cb-locate
  "The index of the chunk containing row i: the largest j with
   cums[j] <= i (cums[0] is 0)."
  ^long [cums ^long i]
  (loop [lo 0 hi (dec (count cums))]
    (if (<= hi lo)
      lo
      (let [mid (quot (+ lo hi 1) 2)]
        (if (<= (nth cums mid) i)
          (recur mid hi)
          (recur lo (dec mid)))))))

(declare concat-assoc-n based-assoc-n)

#?(:bb (do (deftype ConcatBatch [handles cums total mm]
           chunk/IFlatHandle
           (-rep [this] (mapv chunk/rep-of this))
           (-chunk-bytes [_] (frame-stream (mapv chunk/bytes-of handles)))
           ecore/ICounted
           (-count [_] total)
           ecore/IIndexed
           (-nth [this i] (if (and (<= 0 i) (< i total))
                            (let [j (cb-locate cums i)]
                              (ecore/nth (nth handles j) (- i (nth cums j))))
                            (throw (IndexOutOfBoundsException. (str i)))))
           (-nth [this i nf] (if (and (<= 0 i) (< i total)) (ecore/nth this i) nf))
           ecore/ILookup
           (-lookup [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) total))
                               (ecore/nth this (long k)) nil))
           (-lookup [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) total))
                                  (ecore/nth this (long k)) nf))
           ecore/IAssociative
           (-assoc [this k v] (cond
                                (not (integer? k)) (throw (IllegalArgumentException.
                                                           "Key must be integer"))
                                (== (long k) total) (ecore/conj this v)
                                :else (pg-concat-assoc-n handles cums total mm (long k) v)))
           (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) total)))
           ecore/IVector
           (-assoc-n [this i v] (pg-concat-assoc-n handles cums total mm i v))
           ecore/ICollection
           (-conj [this v] (let [h' (chunk/open (first (chunk/pack-all [v])))]
                             (ConcatBatch. (conj handles h') (conj cums total)
                                           (inc total) mm)))
           ecore/IEmptyableCollection
           (-empty [_] [])
           ecore/IStack
           (-peek [this] (when (pos? total) (ecore/nth this (dec total))))
           (-pop [this] (if (zero? total)
                          (throw (IllegalStateException. "Can't pop empty vector"))
                          (let [hl (peek handles)]
                            (if (== 1 (ecore/count hl))
                              (ConcatBatch. (pop handles) (pop cums) (dec total) mm)
                              (ConcatBatch. (assoc handles (dec (count handles)) (ecore/pop hl))
                                            cums (dec total) mm)))))
           ecore/ISeqable
           (-seq [_] (apply concat (map ecore/seq handles)))
           ecore/IReduce
           (-reduce [this f] (reduce f (ecore/seq this)))
           (-reduce [this f init] (reduce f init (ecore/seq this)))
           ecore/IEquiv
           (-equiv [this other] (and (ecore/sequential? other)
                                     (== total (ecore/count other))
                                     (ecore/equiv (vec (ecore/seq this)) (vec (ecore/seq other)))))
           ecore/IHash
           (-hash [this] (ecore/hash-ordered-coll (ecore/seq this)))
           ecore/IMeta
           (-meta [_] mm)
           ecore/IWithMeta
           (-with-meta [_ m] (ConcatBatch. handles cums total m))
           Object
           (equals [this other] (and (ecore/sequential? other)
                                     (== total (ecore/count other))
                                     (ecore/equiv (vec (ecore/seq this)) (vec (ecore/seq other)))))
           (hashCode [this] (unchecked-int (ecore/hash-ordered-coll (ecore/seq this))))
           (toString [this] (pr-str (ecore/seq this)))))
    :glj (do (deftype ConcatBatch [handles cums total mm]
            chunk/IFlatHandle
            (-rep [this] (mapv chunk/rep-of this))
            (-chunk-bytes [_] (frame-stream (mapv chunk/bytes-of handles)))
            ecore/ICounted
            (-count [_] total)
            ecore/IIndexed
            (-nth [this i] (if (and (<= 0 i) (< i total))
                             (let [j (cb-locate cums i)]
                               (ecore/nth (nth handles j) (- i (nth cums j))))
                             (throw (IndexOutOfBoundsException. (str i)))))
            (-nth [this i nf] (if (and (<= 0 i) (< i total)) (ecore/nth this i) nf))
            ecore/ILookup
            (-lookup [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) total))
                                (ecore/nth this (long k)) nil))
            (-lookup [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) total))
                                   (ecore/nth this (long k)) nf))
            ecore/IAssociative
            (-assoc [this k v] (cond
                                 (not (integer? k)) (throw (IllegalArgumentException.
                                                            "Key must be integer"))
                                 (== (long k) total) (ecore/conj this v)
                                 :else (pg-concat-assoc-n handles cums total mm (long k) v)))
            (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) total)))
            ecore/IVector
            (-assoc-n [this i v] (pg-concat-assoc-n handles cums total mm i v))
            ecore/ICollection
            (-conj [this v] (let [h' (chunk/open (first (chunk/pack-all [v])))]
                              (ConcatBatch. (conj handles h') (conj cums total)
                                            (inc total) mm)))
            ecore/IEmptyableCollection
            (-empty [_] [])
            ecore/IStack
            (-peek [this] (when (pos? total) (ecore/nth this (dec total))))
            (-pop [this] (if (zero? total)
                           (throw (IllegalStateException. "Can't pop empty vector"))
                           (let [hl (peek handles)]
                             (if (== 1 (ecore/count hl))
                               (ConcatBatch. (pop handles) (pop cums) (dec total) mm)
                               (ConcatBatch. (assoc handles (dec (count handles)) (ecore/pop hl))
                                             cums (dec total) mm)))))
            ecore/ISeqable
            (-seq [_] (apply concat (map ecore/seq handles)))
            ecore/IReduce
            (-reduce [this f] (reduce f (ecore/seq this)))
            (-reduce [this f init] (reduce f init (ecore/seq this)))
            ecore/IEquiv
            (-equiv [this other] (and (ecore/sequential? other)
                                      (== total (ecore/count other))
                                      (ecore/equiv (vec (ecore/seq this)) (vec (ecore/seq other)))))
            ecore/IHash
            (-hash [this] (ecore/hash-ordered-coll (ecore/seq this)))
            ecore/IMeta
            (-meta [_] mm)
            ecore/IWithMeta
            (-with-meta [_ m] (ConcatBatch. handles cums total m))
            ecore/IPrintWithWriter
            (-pr-writer [this w _] (ecore/-write w (pr-str (ecore/seq this)))))
           (defmethod print-method ConcatBatch [x w] (ecore/-pr-writer x w nil)))
    :clj (deftype ConcatBatch [handles cums ^long total mm]
     clojure.lang.Counted
     (count [_] total)
     clojure.lang.Indexed
     (nth [this i] (if (and (<= 0 i) (< i total))
                     (let [j (cb-locate cums i)]
                       (nth (nth handles j) (- i (nth cums j))))
                     (throw (IndexOutOfBoundsException. (str i)))))
     (nth [this i nf] (if (and (<= 0 i) (< i total))
                        (nth this i) nf))
     clojure.lang.ILookup
     (valAt [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) total))
                       (nth this (long k)) nil))
     (valAt [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) total))
                          (nth this (long k)) nf))
     clojure.lang.Associative
     (containsKey [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) total)))
     (entryAt [this k] (when (.containsKey this k)
                         (clojure.lang.MapEntry. k (nth this (long k)))))
     (assoc [this k v] (cond
                         (not (integer? k)) (throw (IllegalArgumentException.
                                                    "Key must be integer"))
                         (== (long k) total) (conj this v)
                         :else (concat-assoc-n handles cums total mm (long k) v)))
     clojure.lang.IPersistentVector
     (length [_] total)
     (assocN [this i v] (concat-assoc-n handles cums total mm i v))
     (cons [this v] (let [h' (chunk/open (first (chunk/pack-all [v])))]
                      (ConcatBatch. (conj handles h') (conj cums total)
                                    (inc total) mm)))
     (peek [this] (when (pos? total) (nth this (dec total))))
     (pop [this] (if (zero? total)
                   (throw #?(:jank (ex-info "Can't pop empty vector" {}) :default (IllegalStateException. "Can't pop empty vector")))
                   (let [hl (peek handles)]
                     (if (== 1 (count hl))
                       (ConcatBatch. (pop handles) (pop cums) (dec total) mm)
                       (ConcatBatch. (assoc handles (dec (count handles)) (pop hl))
                                     cums (dec total) mm)))))
     clojure.lang.IPersistentCollection
     (empty [_] [])
     (equiv [this other] (.equals this other))
     clojure.lang.Seqable
     (seq [_] (apply concat (map seq handles)))
     clojure.lang.IMeta
     (meta [_] mm)
     clojure.lang.IObj
     (withMeta [_ m] (ConcatBatch. handles cums total m))
     clojure.lang.IHashEq
     (hasheq [this] (hash (vec (seq this))))
     ;; a delivered multi-chunk batch IS a flat handle: its rep
     ;; is its rows' reps (the same shape a single chunk's batch
     ;; rep has), and its chunk bytes are the framed stream —
     ;; the byte form embed writes and open-stream reads. Without
     ;; this, -handle of a framed FrozenValue (a ConcatBatch) had
     ;; no -rep and packing a value holding one threw.
     chunk/IFlatHandle
     (-rep [this] (mapv chunk/rep-of this))
     (-chunk-bytes [_] (frame-stream (mapv chunk/bytes-of handles)))
     Object
     (equals [this other] (and (sequential? other)
                               (== total (count other))
                               (= (seq this) (seq other))))
     (hashCode [this] (hash (vec (seq this))))
     (toString [this] (pr-str (seq this)))))

;; ---------------------------------------------------------------------------
;; the upgrade answer: a persistent overlay embedding a flat
;; base BY REFERENCE (UPGRADE-PLAN R2). The base is never
;; touched, copied, or re-packed: edits live in `edits` (index
;; -> value for in-base-range replacements) and `tail`
;; (appends past the base); n is the logical count, bc the
;; base's count. Reads consult the overlay first, then fall
;; through to the base. Sequential bases in v1 (the chain
;; forms). Equality and hash run against the LOGICAL value, so
;; a BasedValue equals its materialization and its refreeze.

#?(:bb (do (deftype BasedValue [base edits tail n bc mm]
           ecore/ICounted
           (-count [_] n)
           ecore/IIndexed
           (-nth [this i] (if (and (<= 0 i) (< i n))
                            (if (< i bc)
                              (if (contains? edits i) (get edits i) (ecore/nth base i))
                              (nth tail (- i bc)))
                            (throw (IndexOutOfBoundsException. (str i)))))
           (-nth [this i nf] (if (and (<= 0 i) (< i n)) (ecore/nth this i) nf))
           ecore/ILookup
           (-lookup [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) n))
                               (ecore/nth this (long k)) nil))
           (-lookup [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) n))
                                  (ecore/nth this (long k)) nf))
           ecore/IAssociative
           (-assoc [this k v] (if (integer? k)
                                (based-assoc-n base edits tail n bc mm (long k) v)
                                (throw (IllegalArgumentException.
                                        "Key must be integer"))))
           (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) n)))
           ecore/IVector
           (-assoc-n [this i v] (based-assoc-n base edits tail n bc mm i v))
           ecore/ICollection
           (-conj [this v] (based-assoc-n base edits tail n bc mm n v))
           ecore/IEmptyableCollection
           (-empty [_] [])
           ecore/IStack
           (-peek [this] (when (pos? n) (ecore/nth this (dec n))))
           (-pop [this] (if (zero? n)
                          (throw (IllegalStateException. "Can't pop empty vector"))
                          (let [i (dec n)]
                            (if (< i bc)
                              (BasedValue. base (dissoc edits i) tail i bc mm)
                              (BasedValue. base edits (pop tail) i bc mm)))))
           ecore/ISeqable
           (-seq [this] (when (pos? n) (map (fn [i] (ecore/nth this i)) (range n))))
           ecore/IReduce
           (-reduce [this f] (reduce f (ecore/seq this)))
           (-reduce [this f init] (reduce f init (ecore/seq this)))
           ecore/IEquiv
           (-equiv [this other] (and (ecore/sequential? other)
                                     (== n (ecore/count other))
                                     (ecore/equiv (vec (ecore/seq this)) (vec (ecore/seq other)))))
           ecore/IHash
           (-hash [this] (ecore/hash-ordered-coll (ecore/seq this)))
           ecore/IMeta
           (-meta [_] mm)
           ecore/IWithMeta
           (-with-meta [_ m] (BasedValue. base edits tail n bc m))
           Object
           (equals [this other] (and (ecore/sequential? other)
                                     (== n (ecore/count other))
                                     (ecore/equiv (vec (ecore/seq this)) (vec (ecore/seq other)))))
           (hashCode [this] (unchecked-int (ecore/hash-ordered-coll (ecore/seq this))))
           (toString [this] (pr-str (ecore/seq this)))))
    :glj (do (deftype BasedValue [base edits tail n bc mm]
            ecore/ICounted
            (-count [_] n)
            ecore/IIndexed
            (-nth [this i] (if (and (<= 0 i) (< i n))
                             (if (< i bc)
                               (if (contains? edits i) (get edits i) (ecore/nth base i))
                               (nth tail (- i bc)))
                             (throw (IndexOutOfBoundsException. (str i)))))
            (-nth [this i nf] (if (and (<= 0 i) (< i n)) (ecore/nth this i) nf))
            ecore/ILookup
            (-lookup [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) n))
                                (ecore/nth this (long k)) nil))
            (-lookup [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) n))
                                   (ecore/nth this (long k)) nf))
            ecore/IAssociative
            (-assoc [this k v] (if (integer? k)
                                 (based-assoc-n base edits tail n bc mm (long k) v)
                                 (throw (IllegalArgumentException.
                                         "Key must be integer"))))
            (-contains-key? [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) n)))
            ecore/IVector
            (-assoc-n [this i v] (based-assoc-n base edits tail n bc mm i v))
            ecore/ICollection
            (-conj [this v] (based-assoc-n base edits tail n bc mm n v))
            ecore/IEmptyableCollection
            (-empty [_] [])
            ecore/IStack
            (-peek [this] (when (pos? n) (ecore/nth this (dec n))))
            (-pop [this] (if (zero? n)
                           (throw (IllegalStateException. "Can't pop empty vector"))
                           (let [i (dec n)]
                             (if (< i bc)
                               (BasedValue. base (dissoc edits i) tail i bc mm)
                               (BasedValue. base edits (pop tail) i bc mm)))))
            ecore/ISeqable
            (-seq [this] (when (pos? n) (map (fn [i] (ecore/nth this i)) (range n))))
            ecore/IReduce
            (-reduce [this f] (reduce f (ecore/seq this)))
            (-reduce [this f init] (reduce f init (ecore/seq this)))
            ecore/IEquiv
            (-equiv [this other] (and (ecore/sequential? other)
                                      (== n (ecore/count other))
                                      (ecore/equiv (vec (ecore/seq this)) (vec (ecore/seq other)))))
            ecore/IHash
            (-hash [this] (ecore/hash-ordered-coll (ecore/seq this)))
            ecore/IMeta
            (-meta [_] mm)
            ecore/IWithMeta
            (-with-meta [_ m] (BasedValue. base edits tail n bc m))
            ecore/IPrintWithWriter
            (-pr-writer [this w _] (ecore/-write w (pr-str (ecore/seq this)))))
           (defmethod print-method BasedValue [x w] (ecore/-pr-writer x w nil)))
    :clj (deftype BasedValue [base edits tail ^long n ^long bc mm]
     clojure.lang.Counted
     (count [_] n)
     clojure.lang.Indexed
     (nth [this i] (if (and (<= 0 i) (< i n))
                     (if (< i bc)
                       (if (contains? edits i) (get edits i) (nth base i))
                       (nth tail (- i bc)))
                     (throw (IndexOutOfBoundsException. (str i)))))
     (nth [this i nf] (if (and (<= 0 i) (< i n)) (nth this i) nf))
     clojure.lang.ILookup
     (valAt [this k] (if (and (integer? k) (<= 0 (long k)) (< (long k) n))
                       (nth this (long k)) nil))
     (valAt [this k nf] (if (and (integer? k) (<= 0 (long k)) (< (long k) n))
                          (nth this (long k)) nf))
     clojure.lang.Associative
     (containsKey [_ k] (and (integer? k) (<= 0 (long k)) (< (long k) n)))
     (entryAt [this k] (when (.containsKey this k)
                         (clojure.lang.MapEntry. k (nth this (long k)))))
     (assoc [this k v] (if (integer? k)
                         (based-assoc-n base edits tail n bc mm (long k) v)
                         (throw (IllegalArgumentException.
                                 "Key must be integer"))))
     clojure.lang.IPersistentVector
     (length [_] n)
     (assocN [this i v] (based-assoc-n base edits tail n bc mm i v))
     (cons [this v] (based-assoc-n base edits tail n bc mm n v))
     (peek [this] (when (pos? n) (nth this (dec n))))
     (pop [this] (if (zero? n)
                   (throw #?(:jank (ex-info "Can't pop empty vector" {}) :default (IllegalStateException. "Can't pop empty vector")))
                   (let [i (dec n)]
                     (if (< i bc)
                       (BasedValue. base (dissoc edits i) tail i bc mm)
                       (BasedValue. base edits (pop tail) i bc mm)))))
     clojure.lang.IPersistentCollection
     (empty [_] [])
     (equiv [this other] (.equals this other))
     clojure.lang.Seqable
     (seq [this] (when (pos? n) (map (fn [i] (nth this i)) (range n))))
     clojure.lang.IMeta
     (meta [_] mm)
     clojure.lang.IObj
     (withMeta [_ m] (BasedValue. base edits tail n bc m))
     clojure.lang.IHashEq
     (hasheq [this] (hash (vec (seq this))))
     Object
     (equals [this other] (and (sequential? other)
                               (== n (count other))
                               (= (seq this) (seq other))))
     (hashCode [this] (hash (vec (seq this))))
     (toString [this] (pr-str (seq this)))))

;; the assocN logic of the two vector-shaped frozen types, as plain
;; fns in eve.flat.chunk's assocN-flat style: a method body that
;; self-calls (.assocN this ...) is a C++ member call on hosts whose
;; completion has no such member (jank), so assoc/assocN/cons all
;; route here instead.
(defn- concat-assoc-n [handles cums total mm i v]
  (let [j (cb-locate cums i)
        h' (assoc (nth handles j) (- i (nth cums j)) v)]
    (ConcatBatch. (assoc handles j h') cums total mm)))

(defn- based-assoc-n [base edits tail n bc mm i v]
  (cond
    (or (< i 0) (> i n))
    (throw (IndexOutOfBoundsException. (str i)))
    (< i bc)
    (BasedValue. base (assoc edits i v) tail n bc mm)
    :else
    (BasedValue. base edits
                 (if (== i n) (conj tail v)
                     (assoc tail (- i bc) v))
                 (if (== i n) (inc n) n) bc mm)))

#?(:clj
   (defn based-of
     "Wrap a flat base (a chunk handle or a FrozenValue) in an
      empty overlay: the upgrade answer's starting point. The
      base object is held as-is — embedding is by reference."
     [base]
     (BasedValue. base {} [] (count base) (count base) nil)))

#?(:clj (defn based-assoc [base k v] (assoc (based-of base) k v)))
#?(:clj (defn based-pop [base] (pop (based-of base))))

;; The protocol hosts' spellings of the three helpers above and of
;; concat-assoc-n's handle assoc: the same logic with eve.core
;; calls where the base or the edited element is a flat value.
#?(:bb (do (defn- pg-concat-assoc-n [handles cums total mm i v]
           (let [j (cb-locate cums i)
                 h' (ecore/assoc (nth handles j) (- i (nth cums j)) v)]
             (ConcatBatch. (assoc handles j h') cums total mm)))
         (defn- pg-based-of [base]
           (BasedValue. base {} [] (ecore/count base) (ecore/count base) nil))
         (defn- pg-based-assoc [base k v] (ecore/assoc (pg-based-of base) k v))
         (defn- pg-based-pop [base] (ecore/pop (pg-based-of base))))
    :glj (do (defn- pg-concat-assoc-n [handles cums total mm i v]
            (let [j (cb-locate cums i)
                  h' (ecore/assoc (nth handles j) (- i (nth cums j)) v)]
              (ConcatBatch. (assoc handles j h') cums total mm)))
          (defn- pg-based-of [base]
            (BasedValue. base {} [] (ecore/count base) (ecore/count base) nil))
          (defn- pg-based-assoc [base k v] (ecore/assoc (pg-based-of base) k v))
          (defn- pg-based-pop [base] (ecore/pop (pg-based-of base)))))

(defn open-stream
  "Open a frozen value's chunks (a vector of chunk byte arrays)
   as one handle: one chunk opens directly; several open as a
   spanning ConcatBatch over the per-chunk handles."
  [chunks]
  (if (== 1 (count chunks))
    (chunk/open (first chunks))
    #?(:clj (let [handles (mapv chunk/open chunks)
                  counts (mapv count handles)
                  cums (vec (reductions + 0 (butlast counts)))]
              (ConcatBatch. handles cums (long (reduce + counts)) nil))
       :cljs (throw (ex-info "eve.flat.freeze: multi-chunk open not built on this host"
                             {:chunks (count chunks)})))))

;; the opened-chunk cache lives in a ^objects cell: deftype
;; mutable fields are not reachable from outside the type (the
;; digest-spike lesson), a plain public array field is.
#?(:clj
   (defn -handle [^FrozenValue fv sio ^long hdr]
     (let [cells (.-cells fv)]
       (or (aget cells 0)
           (let [mode (aget cells 1)
                 h (case mode
                     :framed (open-stream (unframe-stream (read-chain-bytes sio hdr)))
                     (chunk/open (read-chain-bytes sio hdr)))]
             (aset cells 0 h)
             h)))))

(defn frozen-value
  "The header constructor for 0x20: the value a frozen chain
   reads as. Registered below; also the direct constructor for
   code that just wrote a chain with write-chain!. The framed
   flag is read back from the header (bit 1) so a value read
   out of a domain by the serializer dispatches the same way
   as one constructed at write time."
  [sio header-off]
  #?(:clj (let [cells (object-array 2)
                flags (slab/read-i32 sio header-off 44)]
            (if (== (bit-and flags 4) 4)
              ;; a typed root (a flat eve-deftype stored in the atom): it
              ;; answers as its type inside the atom — its sister over the
              ;; chunk, as the ClojureScript arm below opens every chain
              (chunk/open (read-chain-bytes sio header-off))
              (do (when (== (bit-and flags 2) 2)
                    (aset cells 1 :framed))
                  (->FrozenValue sio (long header-off) cells))))
     :cljs (chunk/open (read-chain-bytes sio header-off))))

(defn enter-checked!
  "The outside door (plan S1): chunk bytes from elsewhere become
   atom content only after vouching. The bytes are opened through
   chunk/open-checked first (malformed content is refused before
   a single block is written), the content digest is computed
   from the vouched value and latched into the header (R9), the
   chain is written, and the FrozenValue is answered — install
   it with swap! like any value."
  [sio ^bytes chunk-bytes]
  (let [h (chunk/open-checked chunk-bytes)
        words (digest/digest-words-of h)
        hdr (write-chain! sio chunk-bytes words)]
    (frozen-value sio hdr)))

(defn freeze-value
  "Freeze a value (plan S2 + S2b, subtree form per the Nov 13
   governing correction): pack it and store it as a chunk
   chain in fixed-size classes, its digest latched from the
   value. The answer is an ordinary Eve value: install it at
   any depth of a tree with swap! — the root pointer neither
   knows nor cares that it is flat. One chunk travels as the
   raw chunk bytes; several travel as a framed stream.
   Eligibility (R4): content held by reference — fns, host
   objects — cannot be packed and is refused by the packer;
   a scalar is its own flat form and is refused as well."
  [sio v]
  (let [chunks (if (sequential? v)
                 (chunk/pack-all v)
                 (let [h (chunk/pack v)]
                   (when-not (satisfies? chunk/IFlatHandle h)
                     (throw (ex-info "freeze: a scalar is its own flat form; nothing to freeze"
                                     {:value v})))
                   [(chunk/bytes-of h)]))
        words (digest/digest-words-of v)]
    (if (== 1 (count chunks))
      (frozen-value sio (write-chain! sio (first chunks) words false))
      (frozen-value sio (write-chain! sio (frame-stream chunks) words true)))))

;; freeze's wrapper types exist on the runtime-require hosts; on
;; ClojureScript a frozen chain opens directly as its chunk handle
;; (frozen-value's cljs arm), so the wrappers never occur there —
;; the predicates answer false, the same splice deliver-flat's
;; FrozenValue arm already makes inline.
(defn- frozen-value? [v] #?(:cljs false :default (instance? FrozenValue v)))
(defn- concat-batch? [v] #?(:cljs false :default (instance? ConcatBatch v)))
(defn- based-value? [v] #?(:cljs false :default (instance? BasedValue v)))

(defn deliver-flat
  "The S3 delivery: a HEAP value as a heap flat object that
   OWNS its bytes — pack it, and open the chunk over the byte
   array. The delivered object needs no domain, no pin, no
   epoch: it reads after the source domain is closed, and
   anywhere else heap data goes. Cost = one pack; reads
   thereafter are the flat in-place reads.
   CAUTION: the input must be heap data or a value whose reads
   are otherwise kept alive by the caller. Packing a slab-
   backed value AFTER its read pin has dropped races with
   retirement — a concurrent swap can free the blocks mid-
   walk and the delivery comes out torn. For atom values use
   deliver-atom, which packs inside the pin."
  [v]
  ;; one flat form on every host (the value model, EVE-FIX-PLAN §0): a
  ;; deftype delivers as its flat sister, never as a copy placed into a
  ;; private heap domain
  (cond
    ;; already flat: a frozen subtree delivers as its opened
    ;; handle (whose bytes were copied out of the slab — the
    ;; handle owns them); a heap flat handle delivers as itself.
    #?(:clj (instance? FrozenValue v) :cljs false)
    (-handle v (.-sio ^FrozenValue v) (.-hdr ^FrozenValue v))
    #?(:clj (instance? ConcatBatch v) :cljs false) v
    (satisfies? chunk/IFlatHandle v) v
    (sequential? v) (open-stream (chunk/pack-all v))
    (coll? v) (chunk/pack v)
    ;; a slab instance of a sister-registered type that is not
    ;; itself a collection (a field-oriented deftype root): the
    ;; same pack — the writer's rep converter turns it into its
    ;; typed rep by its descriptor, and it delivers as its flat
    ;; sister, as a collection root does (plan §4, M4: proto types
    ;; ride this funnel with no type-side hook)
    (and (satisfies? d/ISabpType v)
         (sister/descriptor (d/-sabp-type-key v)))
    (chunk/pack v)
    :else v))

(defn deliver-atom
  "Deliver an Eve atom's current value as a heap flat object,
   snapshot-correctly: the pack runs INSIDE the atom's read
   pin — the same discipline plain-deref's materialization
   obeys (pin, convert, unpin) — so a concurrent writer
   swapping and retiring the old root cannot tear the value
   mid-conversion. What crosses the pin boundary is only the
   finished handle, which owns its bytes."
  [a]
  (ea/read-pinned a (fn [root] (deliver-flat root))))

(defn- embed-handle-bytes
  "Ingestion (UPGRADE-PLAN R10/S4): the slab writer met a heap
   flat handle inside host data — stripe its chunks into sio
   as a frozen chain and answer the SAB pointer bytes for the
   chain header, so the flat part is embedded by reference at
   its position instead of being walked into plain blocks.
   nil when v is not an embeddable handle: not flat, or
   already in-atom (a FrozenValue rides the IEveRoot pointer
   arm). A BasedValue (an edited flat value) has no chunk
   bytes of its own: it is embedded as the flat packing of
   its logical value — the repack is the edit's write cost,
   the unedited base case never pays it."
  [sio v]
  ;; freeze's own heap types (ConcatBatch, BasedValue) do not
  ;; implement chunk/IFlatHandle — they have no single-chunk
  ;; ctx/off for the position/digest machinery — so the gate
  ;; names them beside the protocol; without them here, a
  ;; multi-chunk delivery fell through to the codec's vector
  ;; walk and re-embedded a chunk per element until the domain
  ;; OOMed (probe_embed, 2026-11-14).
  (when (and (or (satisfies? chunk/IFlatHandle v)
                 (concat-batch? v)
                 (based-value? v))
             (not (frozen-value? v)))
    (let [h (if (based-value? v) (deliver-flat (plain-of v)) v)
          [stream framed?] (if (concat-batch? h)
                              [(frame-stream (mapv chunk/bytes-of (.-handles h))) true]
                              [(chunk/bytes-of h) false])
          hdr (long (write-chain! sio stream nil framed?
                                  (satisfies? source/ISister h)))
          ^bytes b (enc/make-bytes 7)]
      (aset b 0 (unchecked-byte 0xEE))
      (aset b 1 (unchecked-byte 0xDB))
      (aset b 2 (unchecked-byte CHUNK-TAG))
      (aset b 3 (unchecked-byte (bit-and hdr 0xFF)))
      (aset b 4 (unchecked-byte (bit-and (bit-shift-right hdr 8) 0xFF)))
      (aset b 5 (unchecked-byte (bit-and (bit-shift-right hdr 16) 0xFF)))
      (aset b 6 (unchecked-byte (bit-and (bit-shift-right hdr 24) 0xFF)))
      b)))

(defn refreeze
  "The explicit refreeze verb (UPGRADE-PLAN R3/R15): v's
   logical value packed fresh into sio as a pure flat chain.
   A composite (a BasedValue, a value with overlays) collapses
   to the flat form of its materialization; a pure flat value
   re-packs to an equal, digest-equal chain. Nothing refreezes
   except by this verb."
  [sio v]
  (freeze-value sio (plain-of v)))

(defn refreeze!
  "refreeze as an atom operation: the atom's root becomes
   the refrozen form of its current stored value — the one
   sanctioned in-place collapse of a composite root to a
   pure flat root. Answers the new root (a FrozenValue)."
  [a]
  (let [sio (ea/atom-sio a)]
    (swap! a (fn [cur] (refreeze sio cur)))
    (ea/read-pinned a (fn [r] r))))

;; Atom deref delivers flat (FLAT-IN-ATOM R5 / S3): freeze registers
;; the delivery with eve.atom, whose deref path calls it inside the
;; read pin. Registered here because atom cannot require freeze.
(ea/register-deref-deliver! deliver-flat)

;; eve->clj's kind arm (eve.deftype-proto.data/register-flat-of!): the one
;; place eve->clj learns what the flat subsystem knows — a stored flat value
;; is its content, an eve-deftype or record is its flat instance, an eve value
;; array is its slots. Plain host collections answer nil at once.
(defn- host-coll? [v]
  #?(:clj (and (instance? clojure.lang.IPersistentCollection v)
               (.startsWith (.getName (class v)) "clojure.lang."))
     :default false))

(defn- flat-of [v]
  (when-not (host-coll? v)
    (cond
      (frozen-value? v)
      (let [h (-handle v (.-sio v) (.-hdr v))]
        (if (satisfies? source/ISister h) h (d/eve->clj h)))

      (satisfies? source/ISister v) v

      (not (satisfies? d/ISabpType v)) nil

      (= "eve/ValueArray" (d/-sabp-type-key v))
      (mapv d/eve->clj (seq v))

      (and (satisfies? d/IEveRoot v)
           (sister/descriptor (d/-sabp-type-key v))
           (or (record? v)
               (sister/own-type-instance? v)
               (not (or (map? v) (set? v) (vector? v) (sequential? v)))))
      (let [p (chunk/pack v)]
        (if (satisfies? source/ISister p) p (d/eve->clj p)))

      :else nil)))

(d/register-flat-of! flat-of)

(ser/register-header-constructor! CHUNK-TAG frozen-value)
(ser/register-header-disposer! CHUNK-TAG dispose-chain!)
(ser/register-flat-embed! embed-handle-bytes)

;; The generated freeze/thaw methods (eve.flat.sister's
;; IFreezeThaw, emitted by eve-deftype for every type) route
;; here: freeze is freeze-value, thaw is the materialization.
(sister/register-flat-hooks! (fn [sio v] (freeze-value sio v))
                             (fn [v] (plain-of v)))

;; The chunk writer's rep converter consults this hook for a
;; FrozenValue: its rep is its CONTENT's rep (the wrapped
;; handle's), so packing or delivering a value that contains a
;; frozen subtree packs the subtree's content. Without it the
;; writer has no arm for a FrozenValue and emits the wrapper
;; itself as a node — the corrupt-delivery defect.
(sister/register-flat-rep-hook!
  (fn [v] (when (frozen-value? v)
            (chunk/-rep (-handle v (.-sio v) (.-hdr v))))))

;; The same conversion registered DIRECTLY with the chunk
;; writer, not only through sister's converter: sister's
;; registration is a delay forced by the first eve-deftype
;; registration in the process, so a process that packs a
;; frozen-bearing value before any eve type is defined (a
;; plain data pipeline: freeze + chunk + corpora) found NO
;; converter at all, and to-rep fell through to the value
;; itself — the writer then emitted the FrozenValue wrapper
;; as a node and delivery read garbage. Freeze loads chunk
;; outright, so registering here closes the window at the
;; subsystem's own load.
(chunk/register-rep-converter!
  (fn [v] (when (frozen-value? v)
            (chunk/-rep (-handle v (.-sio v) (.-hdr v))))))
;; the codec's nested-value reader resolves SAB pointer tags
;; through the TYPE-constructor registry (its fallback arm):
;; registering there is what lets a frozen subtree sit inside
;; a persistent map/vector and read back — the subtree form.
(ser/register-type-constructor! CHUNK-TAG frozen-value)
