(ns eve.flat.column
  "Columns and batches: the columnar half of the packed form (FORMAT-V1.md
   §5), lane C2 of the flat-Eve spike.

   A column is a segment of a chunk's bytes: [kind u8][validity bitmap,
   when kind has 0x80][data]. A BATCH node is [0x26][shape varint]
   [n rows varint][column offsets x fields] with the columns written
   before it (children first); a COL node is [0x23][n varint][column].
   Offsets are chunk-absolute, of the chunk's one width (u16, or u32 in a
   WIDE chunk). Where §5 leaves a byte undecided, this namespace matches
   lane C1's chunk writer (eve.flat.chunk's write-column!/parse-column)
   exactly, so the two readers cannot disagree on a layout: symbol codes
   unaligned at both widths, utf-8 offsets aligned 4 and relative to the
   string-bytes area, narrowed data aligned to its width, run-end ends
   unaligned at the values column's end, a struct column one offset wide
   naming its BATCH node.

   Reading is in place: a column handle is a parse of the segment header
   (positions, not values); scan, filter, cell and row view read the bytes
   and build nothing but their answer. The chunk around the segment —
   symbol table, shapes, nodes — arrives through a ctx map:
     {:bytes  the chunk's byte array
      :sym    (fn [code] value)          kinds 17/18
      :code   {value code} or (fn [v])   filter counts on symbol columns
      :shape  (fn [shape-index] keys)    batches and struct columns
      :node   (fn [chunk-offset] value)  kind 20
      :wide?  the chunk's WIDE flag}
   Lane C1's chunk.cljc supplies the real ctx; the tests supply a
   scaffolding one (test/eve/flat/column_scaffold.clj).

   Encoding mirrors C1's writer, including its kind inference
   (column-plan): booleans -> bits; integers -> narrowed when n >= 16 and
   the range fits a width, else i32/i64; Float -> f32, Double -> f64;
   symbol-coded -> codes; strings -> utf-8; a run-end wrap when a
   6/10/17/18 column of n >= 32 has few runs. Kinds 1-5, 7, 11 (the rest
   of Eve's typed-array subtypes) encode and read here per §5; C1's
   parser does not admit them yet (see C2-STATUS).

   JVM only in this wave; the ClojureScript side throws until lane H4.
   Every read of a chunk's bytes goes through eve.flat-io (doc/flat/FLAT-IO.md;
   flat lanes U2a): a handle carries the chunk (:c, fio/chunk of :ba) its
   reads take. Two reads stay the JVM's own: scan-sum's NIO sums over the
   f32 / f64 / i32 / i64 kinds and typed-array-view's NIO views — bulk host
   views the seam has no call for."
  (:require [eve.flat-io :as fio])
  #?(:clj (:import [java.nio ByteBuffer ByteOrder])))

(declare open-batch batch-row)

;; ---------------------------------------------------------------------------
;; kinds (FORMAT-V1 §5)
;; ---------------------------------------------------------------------------

(def ^:private dense-size
  "Element byte size of the dense kinds 1-11, or nil."
  {1 1, 2 1, 3 1, 4 2, 5 2, 6 4, 7 4, 8 4, 9 8, 10 8, 11 8})

;; ---------------------------------------------------------------------------
;; little-endian words over a byte array (absolute chunk offsets)
;; ---------------------------------------------------------------------------

#?(:clj
   (do
     (defn- put-u16! [^bytes b ^long i ^long v]
       (aset b (int i) (unchecked-byte v))
       (aset b (int (inc i)) (unchecked-byte (bit-shift-right v 8))))
     (defn- put-u32! [^bytes b ^long i ^long v]
       (put-u16! b i v)
       (put-u16! b (+ i 2) (bit-shift-right v 16)))

     (defn- put-off!
       "Write a chunk-absolute offset at the chunk's width (u32 when
        wide?, else u16). A u16 write past 65535 refuses by name: the
        span needs a WIDE chunk. (Before this check the write truncated
        silently and the reader landed inside another segment — the
        B7 bench's 'unknown column kind' at a wrapped offset.)"
       [^bytes b ^long i wide? ^long v]
       (if wide?
         (put-u32! b i v)
         (do (when (> v 65535)
               (throw (ex-info "eve.flat.column: a chunk offset past 65535 in a non-WIDE chunk"
                               {:eve.flat.column/check :offset-overflow
                                :offset v :width 2})))
             (put-u16! b i v))))

     (defn- align ^long [^long p ^long a]
       (bit-and (+ p (dec a)) (bit-not (dec a))))

     (defn- varint-len ^long [^long v]
       (loop [v v c 1]
         (if (< v 128) c (recur (unsigned-bit-shift-right v 7) (inc c)))))

     (defn- write-varint!
       "Write v as unsigned LEB128 into b at rel; returns the next rel."
       ^long [^bytes b ^long rel ^long v]
       (loop [rel rel v v]
         (if (< v 128)
           (do (aset b (int rel) (unchecked-byte v)) (inc rel))
           (do (aset b (int rel) (unchecked-byte (bit-or (bit-and v 127) 128)))
               (recur (inc rel) (unsigned-bit-shift-right v 7))))))

     (defn- bit-set?
       "Bit i of the LSB-first bitmap at chunk offset off of chunk c."
       [c ^long off ^long i]
       (not (zero? (bit-and (fio/u8 c (+ off (quot i 8)))
                            (bit-shift-left 1 (rem i 8))))))

     (defn- bitmap-loop
       [^bytes seg ^long rel vals present?]
       (let [n (count vals)]
         (loop [byte-i 0]
           (when (< byte-i (quot (+ n 7) 8))
             (aset seg (int (+ rel byte-i))
                   (unchecked-byte
                    (loop [j 0 acc 0]
                      (if (== j 8)
                        acc
                        (let [idx (+ (* byte-i 8) j)]
                          (recur (inc j)
                                 (if (and (< idx n) (present? (nth vals idx)))
                                   (bit-or acc (bit-shift-left 1 j))
                                   acc)))))))
             (recur (inc byte-i))))))

     (defn- write-bitmap!
       "Write the LSB-first bitmap of present? over vals into seg at rel."
       [seg rel vals]
       (bitmap-loop seg rel vals some?))

     (defn- write-bits!
       "Write the LSB-first bitmap of truthy over vals into seg at rel."
       [seg rel vals]
       (bitmap-loop seg rel vals (fn [v] (boolean v))))))

;; ---------------------------------------------------------------------------
;; planning: which kind a column of values takes (mirrors C1's column-plan)
;; ---------------------------------------------------------------------------

(defn- plain-integer? [x]
  #?(:clj (and (integer? x) (not (instance? clojure.lang.BigInt x))
               (not (instance? java.math.BigInteger x))
               (not (instance? java.math.BigDecimal x)))
     :cljs (and (number? x) (== x (Math/floor x)))))

(defn- runs-of
  "[[value run-length] ...] over consecutive distinct values (nil counts
   as a value)."
  [vals]
  (reduce (fn [rs v]
            (if (and (seq rs) (= v (first (peek rs))))
              (conj (pop rs) [v (inc (second (peek rs)))])
              (conj rs [v 1])))
          [] vals))

(defn- base-plan
  "The plan for a column of values without the run-end wrap: {:kind k},
   with :base/:width for the narrowed kind. nil when no column kind
   applies (the values need nodes, or a struct the caller did not name)."
  [values opts]
  (let [present (filterv some? values)
        n (count values)]
    (when (seq present)
      (cond
        (every? boolean? present) {:kind 16}

        (every? plain-integer? present)
        (let [mn (reduce min (map long present))
              mx (reduce max (map long present))
              range (- mx mn)]
          (cond
            (and (>= n 16) (<= range 255)) {:kind 22 :base mn :width 1}
            (and (>= n 16) (<= range 65535)) {:kind 22 :base mn :width 2}
            (<= -2147483648 mn)
            (if (<= mx 2147483647)
              {:kind 6}
              (if (and (>= n 16) (<= range 4294967295))
                {:kind 22 :base mn :width 4}
                {:kind 10}))
            :else {:kind 10}))

        #?(:clj (every? #(instance? Float %) present)
           :cljs false)
        {:kind 8}

        #?(:clj (every? #(instance? Double %) present)
           :cljs (every? number? present))
        {:kind 9}

        (and (:sym-code opts)
             (every? (fn [v] (some? ((:sym-code opts) v))) present))
        {:kind (if (< (reduce max (map (:sym-code opts) present)) 256) 17 18)}

        (every? string? present) {:kind 19}

        (and (:shape opts) (every? map? present)
             (apply = (map (fn [m] (set (keys m))) present)))
        {:kind 21}

        :else nil))))

(defn- plan-for
  "The full plan for a column of values, with C1's run-end wrap: a base
   kind in {6, 10, 17, 18}, n >= 32, and at most max(2, n/16) runs."
  ([values opts] (plan-for values opts false))
  ([values opts no-wrap?]
   (let [base (base-plan values opts)]
     (if (and base (not no-wrap?)
              (contains? #{6 10 17 18} (:kind base))
              (>= (count values) 32))
       (let [runs (runs-of values)]
         (if (<= (count runs) (max 2 (quot (count values) 16)))
           {:kind 23 :runs runs :base-plan base}
           base))
       (or base {:kind 20})))))

#_{:clj-kondo/ignore [:unused-private-var]} ; used only on the :clj pass (encode's :clj arm; the :cljs arm throws not-built yet)
(defn- explicit-plan
  "The plan when the caller names :kind — narrowed takes :base/:width
   from opts or from the values; run-end plans its values column."
  [values opts]
  (let [kind (int (:kind opts))]
    (case kind
      22 (let [present (filterv some? values)
               base (long (or (:base opts)
                              (when (seq present) (reduce min (map long present)))
                              0))
               width (int (or (:width opts)
                              (let [mx (when (seq present)
                                         (reduce max (map long present)))
                                    range (if mx (- mx base) 0)]
                                (cond (<= range 255) 1
                                      (<= range 65535) 2
                                      :else 4))))]
           {:kind 22 :base base :width width})
      23 {:kind 23 :runs (runs-of values)
          :base-plan (plan-for (mapv first (runs-of values)) opts true)}
      {:kind kind})))

;; ---------------------------------------------------------------------------
;; encoding
;; ---------------------------------------------------------------------------

(declare encode-with-plan)

#?(:clj
   (defn- seg-buffer
     "A little-endian ByteBuffer over a fresh segment array of len bytes."
     [^long len]
     (let [ba (byte-array len)]
       [ba (doto (ByteBuffer/wrap ba) (.order ByteOrder/LITTLE_ENDIAN))])))

(defn encode-column
  "The bytes of the column segment holding values, as they will sit at
   chunk offset (:at opts, default 0) — alignment padding is computed
   against that offset. Options: :kind (force a kind instead of planning),
   :sym-code (value -> code), :node-off (value -> chunk offset, or a map,
   for kind 20), :batch-off (the struct column's BATCH node offset, kind
   21), :shape and :ks (a struct column's inner batch), :wide? (the
   chunk's offset width), :base/:width (kind 22)."
  [values opts]
  #?(:cljs (throw (ex-info "eve.flat.column: encoding is not built on ClojureScript yet (lane H4)"
                           {:values (count values)}))
     :clj (encode-with-plan values (if (:kind opts)
                                     (explicit-plan values opts)
                                     (plan-for values opts))
                            opts)))

#?(:clj
   (defn- encode-with-plan
     [values plan opts]
     (let [n (count values)
           kind (int (:kind plan))
           at (long (:at opts 0))
           wide? (boolean (:wide? opts))
           ow (if wide? 4 2)
           nils? (boolean (some nil? values))
           flag (if nils? 0x80 0)
           vlen (if nils? (quot (+ n 7) 8) 0)
           head (inc vlen) ;; segment bytes before the kind's data area
           ]
       (case kind
         ;; -- dense numbers, kinds 1-11: data aligned to the element size
         (1 2 3 4 5 6 7 8 9 10 11)
         (let [esz (long (dense-size kind))
               data-at (align (+ at head) esz)
               total (+ (- data-at at) (* esz n))
               [seg ^ByteBuffer bb] (seg-buffer total)
               drel (- data-at at)]
           (aset seg 0 (unchecked-byte (bit-or kind flag)))
           (when nils? (write-bitmap! seg 1 values))
           (case kind
             (1 3) (dotimes [i n] (aset seg (int (+ drel i))
                                        (unchecked-byte (long (or (nth values i) 0)))))
             2 (dotimes [i n] (aset seg (int (+ drel i))
                                    (byte (long (or (nth values i) 0)))))
             (4 5) (dotimes [i n] (.putShort bb (int (+ drel (* 2 i)))
                                             (unchecked-short (long (or (nth values i) 0)))))
             (6 7) (dotimes [i n] (.putInt bb (int (+ drel (* 4 i)))
                                           (unchecked-int (long (or (nth values i) 0)))))
             8 (dotimes [i n] (.putFloat bb (int (+ drel (* 4 i)))
                                         (float (or (nth values i) 0.0))))
             9 (dotimes [i n] (.putDouble bb (int (+ drel (* 8 i)))
                                          (double (or (nth values i) 0.0))))
             (10 11) (dotimes [i n] (.putLong bb (int (+ drel (* 8 i)))
                                              (long (or (nth values i) 0)))))
           seg)

         ;; -- bits
         16 (let [total (+ head (quot (+ n 7) 8))
                  [seg _] (seg-buffer total)]
              (aset seg 0 (unchecked-byte (bit-or kind flag)))
              (when nils? (write-bitmap! seg 1 values))
              (write-bits! seg head values)
              seg)

         ;; -- symbol codes, unaligned at both widths
         17 (let [total (+ head n)
                  [seg _] (seg-buffer total)
                  code (:sym-code opts)]
              (when-not code
                (throw (ex-info "eve.flat.column: a symbol column needs :sym-code"
                                {:kind kind})))
              (aset seg 0 (unchecked-byte (bit-or kind flag)))
              (when nils? (write-bitmap! seg 1 values))
              (dotimes [i n]
                (let [v (nth values i)
                      c (if (nil? v) 0 (code v))]
                  (when (and (some? v) (nil? c))
                    (throw (ex-info "eve.flat.column: a value with no symbol code"
                                    {:value v :kind kind})))
                  (aset seg (int (+ head i)) (unchecked-byte (long (or c 0))))))
              seg)
         18 (let [total (+ head (* 2 n))
                  [seg _] (seg-buffer total)
                  code (:sym-code opts)]
              (when-not code
                (throw (ex-info "eve.flat.column: a symbol column needs :sym-code"
                                {:kind kind})))
              (aset seg 0 (unchecked-byte (bit-or kind flag)))
              (when nils? (write-bitmap! seg 1 values))
              (dotimes [i n]
                (let [v (nth values i)
                      c (if (nil? v) 0 (code v))]
                  (when (and (some? v) (nil? c))
                    (throw (ex-info "eve.flat.column: a value with no symbol code"
                                    {:value v :kind kind})))
                  (put-u16! seg (+ head (* 2 i)) (long (or c 0)))))
              seg)

         ;; -- utf-8: offsets aligned 4, relative to the bytes area
         19 (let [bs (mapv (fn [v] (if (nil? v)
                                     (byte-array 0)
                                     (.getBytes ^String v "UTF-8")))
                           values)
                  total-bytes (reduce (fn [a ^bytes b] (+ a (alength b))) 0 bs)
                  data-at (align (+ at head) 4)
                  orel (- data-at at)
                  total (+ orel (* 4 (inc n)) total-bytes)
                  [seg _] (seg-buffer total)]
              (aset seg 0 (unchecked-byte (bit-or kind flag)))
              (when nils? (write-bitmap! seg 1 values))
              (loop [i 0 acc 0]
                (when (<= i n)
                  (put-u32! seg (+ orel (* 4 i)) acc)
                  (when (< i n)
                    (let [^bytes b (nth bs i)]
                      (System/arraycopy b 0 seg (int (+ orel (* 4 (inc n)) acc))
                                        (alength b))
                      (recur (inc i) (+ acc (alength b)))))))
              seg)

         ;; -- any: node offsets of the chunk's width, absolute
         20 (let [total (+ head (* ow n))
                  [seg _] (seg-buffer total)
                  no (:node-off opts)
                  off-of (cond (fn? no) no
                               (map? no) (fn [v] (get no v))
                               :else nil)]
              (when-not off-of
                (throw (ex-info "eve.flat.column: an any column needs :node-off"
                                {:kind kind})))
              (aset seg 0 (unchecked-byte (bit-or kind flag)))
              (when nils? (write-bitmap! seg 1 values))
              (dotimes [i n]
                (let [v (nth values i)
                      o (if (nil? v) 0 (off-of v))]
                  (when (and (some? v) (nil? o))
                    (throw (ex-info "eve.flat.column: a value with no node offset"
                                    {:value v :kind kind})))
                  (put-off! seg (+ head (* (if wide? 4 2) i)) wide?
                            (long (or o 0)))))
              seg)

         ;; -- struct: one offset naming the BATCH node (C1 writes no
         ;;    validity here; nil struct values are refused by name)
         21 (do
              (when nils?
                (throw (ex-info "eve.flat.column: a struct column holds no nil rows (C1 falls back to kind 20)"
                                {:kind kind})))
              (when-not (:batch-off opts)
                (throw (ex-info "eve.flat.column: a struct column needs :batch-off"
                                {:kind kind})))
              (let [total (+ head ow)
                    [seg _] (seg-buffer total)]
                (aset seg 0 (unchecked-byte kind))
                (put-off! seg head wide? (long (:batch-off opts)))
                seg))

         ;; -- narrowed: [base i64][width u8][data aligned to the width]
         22 (let [base (long (:base plan))
                  width (long (:width plan))
                  data-at (align (+ at head 9) width)
                  drel (- data-at at)
                  total (+ drel (* width n))
                  [seg ^ByteBuffer bb] (seg-buffer total)]
              (aset seg 0 (unchecked-byte (bit-or kind flag)))
              (when nils? (write-bitmap! seg 1 values))
              (.putLong bb (int head) base)
              (aset seg (int (+ head 8)) (unchecked-byte width))
              (dotimes [i n]
                (let [v (nth values i)
                      x (if (nil? v) 0 (- (long v) base))]
                  (when (or (neg? x) (>= x (bit-shift-left 1 (* 8 width))))
                    (throw (ex-info "eve.flat.column: a value outside the narrowed range"
                                    {:value v :base base :width width})))
                  (case (int width)
                    1 (aset seg (int (+ drel i)) (unchecked-byte x))
                    2 (put-u16! seg (+ drel (* 2 i)) x)
                    4 (put-u32! seg (+ drel (* 4 i)) x))))
              seg)

         ;; -- run-end: [runs varint][values column][ends u32 x runs]
         23 (let [runs (:runs plan)
                  nruns (count runs)
                  run-vals (mapv first runs)
                  vseg (encode-with-plan run-vals (:base-plan plan)
                                         (assoc opts :at (+ at head (varint-len nruns))))
                  total (+ head (varint-len nruns) (alength vseg) (* 4 nruns))
                  [seg _] (seg-buffer total)]
              (aset seg 0 (unchecked-byte (bit-or kind flag)))
              (when nils? (write-bitmap! seg 1 values))
              (let [q (write-varint! seg head nruns)]
                (System/arraycopy vseg 0 seg (int q) (alength vseg))
                (loop [i 0 end 0 q (+ q (alength vseg))]
                  (when (< i nruns)
                    (let [end (+ end (second (nth runs i)))]
                      (put-u32! seg q end)
                      (recur (inc i) end (+ q 4))))))
              seg)

         (throw (ex-info "eve.flat.column: no writer for column kind"
                         {:kind kind}))))))

;; ---------------------------------------------------------------------------
;; encoding batches: the columns first, the node last (children first)
;; ---------------------------------------------------------------------------

(defn encode-batch
  "The bytes of a BATCH node over ks/cols (column-major), as they will sit
   at chunk offset (:at opts, default 0): the column segments in field
   order, then [0x26][shape varint][n varint][absolute column offsets].
   Answers {:bytes :node-off :n}. Per-field encode options ride in
   (:field-opts opts) {key opts}; :ks/:shape name the batch's shape."
  [ks cols opts]
  #?(:cljs (throw (ex-info "eve.flat.column: encoding is not built on ClojureScript yet (lane H4)" {}))
     :clj
     (let [n (count (first cols))
           shape (long (:shape opts 0))
           at (long (:at opts 0))
           wide? (boolean (:wide? opts))
           ow (if wide? 4 2)
           nf (count ks)
           head-len (+ 1 (varint-len shape) (varint-len n) (* ow nf))
           base-opts (dissoc opts :kind :base :width :batch-off :node-off
                             :ks :shape :at)
           segs (loop [i 0 pos at acc []]
                  (if (== i nf)
                    acc
                    (let [fopts (merge base-opts
                                       (get (:field-opts opts) (nth ks i))
                                       {:at pos})
                          seg (encode-column (nth cols i) fopts)]
                      (recur (inc i) (+ pos (alength seg)) (conj acc seg)))))
           cols-len (reduce (fn [a ^bytes s] (+ a (alength s))) 0 segs)
           node-off (+ at cols-len)
           total (+ cols-len head-len)
           out (byte-array total)]
       (loop [i 0 pos 0]
         (when (< i nf)
           (System/arraycopy (nth segs i) 0 out (int pos) (alength (nth segs i)))
           (recur (inc i) (+ pos (alength (nth segs i))))))
       (aset out (int cols-len) (unchecked-byte 0x26))
       (let [q (write-varint! out (inc cols-len) shape)
             q (write-varint! out q n)]
         (loop [i 0 pos at q q]
           (when (< i nf)
             (put-off! out q wide? pos)
             (recur (inc i) (+ pos (alength (nth segs i))) (+ q ow)))))
       {:bytes out :node-off node-off :n n})))

(defn encode-batch-rows
  "The bytes of a BATCH node over rows (a vector of maps of one shape):
   the transpose of encode-batch, through the same per-field encoding —
   a vector of maps and a map of vectors pack to the same bytes (C2.4).
   (:ks opts) is the shape's key order."
  [rows opts]
  (let [ks (:ks opts)]
    (when-not ks
      (throw (ex-info "eve.flat.column: encode-batch-rows needs :ks" {})))
    (encode-batch ks (mapv (fn [k] (mapv (fn [r] (get r k)) rows)) ks) opts)))

;; ---------------------------------------------------------------------------
;; reading: a handle is a parse of positions, never of values
;; ---------------------------------------------------------------------------

(defn open-column
  "Parse the column segment at chunk offset off in ba, holding n values,
   under ctx. Answers a handle: {:ba :ctx :off :kind :n :valid-off
   :data-off :end} plus kind extras (:base/:width for narrowed, :runs/
   :values-col/:ends-off for run-end, :batch-off/:batch for struct).
   Mirrors C1's parse-column, extended with §5's dense kinds 1-5, 7, 11."
  [ba off n ctx]
  #?(:cljs (throw (ex-info "eve.flat.column: reading is not built on ClojureScript yet (lane H4)" {}))
     :clj
     (let [b (fio/chunk ba)
           off (long off) n (long n)
           raw (fio/u8 b off)
           nils? (>= raw 128)
           kind (bit-and raw 0x7F)
           q (inc off)
           valid-off (when nils? q)
           q (if nils? (+ q (quot (+ n 7) 8)) q)
           base {:ba ba :c b :ctx ctx :off off :kind kind :n n :valid-off valid-off
                 :bb (doto (ByteBuffer/wrap ^bytes ba) (.order ByteOrder/LITTLE_ENDIAN))}]
       (case (int kind)
         (1 2 3) (assoc base :data-off q :end (+ q n))
         (4 5) (let [d (align q 2)] (assoc base :data-off d :end (+ d (* 2 n))))
         (6 7 8) (let [d (align q 4)] (assoc base :data-off d :end (+ d (* 4 n))))
         (9 10 11) (let [d (align q 8)] (assoc base :data-off d :end (+ d (* 8 n))))
         16 (assoc base :data-off q :end (+ q (quot (+ n 7) 8)))
         17 (assoc base :data-off q :end (+ q n))
         18 (assoc base :data-off q :end (+ q (* 2 n)))
         19 (let [d (align q 4)
                  total (fio/u32 b (+ d (* 4 n)))]
              (assoc base :data-off d :bytes-off (+ d (* 4 (inc n)))
                     :end (+ d (* 4 (inc n)) total)))
         20 (assoc base :data-off q
                   :end (+ q (* (if (:wide? ctx) 4 2) n)))
         21 (let [boff (if (:wide? ctx) (fio/u32 b q) (fio/u16 b q))]
              (assoc base :data-off q :batch-off boff
                     :batch (open-batch ba boff ctx)
                     :end (+ q (if (:wide? ctx) 4 2))))
         22 (let [base-val (fio/i64 b q)
                  width (fio/u8 b (+ q 8))
                  d (align (+ q 9) width)]
              (assoc base :data-off d :base base-val :width width
                     :end (+ d (* width n))))
         23 (let [[runs q2] (fio/varint b q)
                  vc (open-column ba q2 runs ctx)]
              (assoc base :data-off q2 :runs runs :values-col vc
                     :ends-off (:end vc)
                     :end (+ (:end vc) (* 4 runs))))
         (throw (ex-info "eve.flat.column: unknown column kind"
                         {:eve.flat.column/check :bad-column :kind kind
                          :offset off}))))))

(defn column-span
  "The byte length of the column segment a handle parsed."
  [col]
  (- (:end col) (:off col)))

(defn open-batch
  "Parse the BATCH node at chunk offset off in ba under ctx: [0x26]
   [shape varint][n varint][absolute column offsets of the chunk's
   width]. Answers {:ba :ctx :off :shape :n :ks :cols [column handles]}."
  [ba off ctx]
  #?(:cljs (throw (ex-info "eve.flat.column: reading is not built on ClojureScript yet (lane H4)" {}))
     :clj
     (let [b (fio/chunk ba)
           off (long off)]
       (when-not (== 0x26 (fio/u8 b off))
         (throw (ex-info "eve.flat.column: not a BATCH node"
                         {:eve.flat.column/check :not-a-batch :offset off
                          :tag (fio/u8 b off)})))
       (let [[shape p] (fio/varint b (inc off))
             [n p] (fio/varint b p)
             ks ((:shape ctx) shape)]
         (when-not ks
           (throw (ex-info "eve.flat.column: a shape the ctx does not know"
                           {:eve.flat.column/check :unknown-shape :shape shape})))
         (let [w (if (:wide? ctx) 4 2)
               cols (mapv (fn [c]
                            (let [coff (if (:wide? ctx)
                                         (fio/u32 b (+ p (* w c)))
                                         (fio/u16 b (+ p (* w c))))]
                              (open-column ba coff n ctx)))
                          (range (count ks)))]
           {:ba ba :c b :ctx ctx :off off :shape shape :n n :ks ks :cols cols})))))

;; ---------------------------------------------------------------------------
;; point reads, in place
;; ---------------------------------------------------------------------------

(defn col-ref
  "Row i of a column, read in place: the plain value, or nil when the
   validity bitmap marks the row absent."
  [col ^long i]
  #?(:cljs (throw (ex-info "eve.flat.column: reading is not built on ClojureScript yet (lane H4)" {}))
     :clj
     (let [b (:c col)
           kind (int (:kind col))
           d (long (:data-off col))]
       (if (and (:valid-off col) (not (bit-set? b (long (:valid-off col)) i)))
         nil
         (case kind
           1 (fio/u8 b (+ d i))
           2 (let [v (fio/u8 b (+ d i))] (if (>= v 128) (- v 256) v))
           3 (fio/u8 b (+ d i))
           4 (let [v (fio/u16 b (+ d (* 2 i)))] (if (>= v 32768) (- v 65536) v))
           5 (fio/u16 b (+ d (* 2 i)))
           6 (fio/i32 b (+ d (* 4 i)))
           7 (fio/u32 b (+ d (* 4 i)))
           8 (float (fio/f32 b (+ d (* 4 i))))
           9 (fio/f64 b (+ d (* 8 i)))
           10 (fio/i64 b (+ d (* 8 i)))
           11 (fio/i64 b (+ d (* 8 i)))
           16 (bit-set? b d i)
           17 ((:sym (:ctx col)) (fio/u8 b (+ d i)))
           18 ((:sym (:ctx col)) (fio/u16 b (+ d (* 2 i))))
           19 (let [o (fio/u32 b (+ d (* 4 i)))
                    e (fio/u32 b (+ d (* 4 (inc i))))]
                (fio/utf8 b (+ (long (:bytes-off col)) o) (- e o)))
           20 ((:node (:ctx col))
               (if (:wide? (:ctx col))
                 (fio/u32 b (+ d (* 4 i)))
                 (fio/u16 b (+ d (* 2 i)))))
           21 (batch-row (:batch col) i)
           22 (let [w (long (:width col))]
                (+ (long (:base col))
                   (case (int w)
                     1 (fio/u8 b (+ d i))
                     2 (fio/u16 b (+ d (* 2 i)))
                     4 (fio/u32 b (+ d (* 4 i))))))
           23 (let [ends (long (:ends-off col))
                    runs (long (:runs col))
                    r (loop [lo 0 hi (dec runs)]
                        (if (== lo hi)
                          lo
                          (let [mid (quot (+ lo hi) 2)]
                            (if (> (fio/u32 b (+ ends (* 4 mid))) i)
                              (recur lo mid)
                              (recur (inc mid) hi)))))]
                (col-ref (:values-col col) r))
           (throw (ex-info "eve.flat.column: unknown column kind"
                           {:kind kind})))))))

(defn col-values
  "The whole column as a plain vector — the one read that materializes,
   for tests and for handing a column to code that wants values."
  [col]
  (mapv (fn [i] (col-ref col i)) (range (:n col))))

(defn batch-cell
  "Field ci of row i of a batch, read in place."
  [batch ^long ci ^long i]
  (col-ref (nth (:cols batch) ci) i))

(defn batch-row
  "Row i of a batch as a plain map, assembled from its columns in place."
  [batch ^long i]
  (let [ks (:ks batch) cols (:cols batch) nc (count ks)]
    (loop [c 0 m (transient {})]
      (if (== c nc)
        (persistent! m)
        (recur (inc c) (assoc! m (nth ks c) (col-ref (nth cols c) i)))))))

(defn batch-values
  "The whole batch as a vector of plain maps."
  [batch]
  (mapv (fn [i] (batch-row batch i)) (range (:n batch))))

;; ---------------------------------------------------------------------------
;; scans and filters, in place
;; ---------------------------------------------------------------------------

(defn scan-sum
  "The sum of a numeric column, read in place: a double for the float
   kinds, a long for the integer kinds. Nil rows contribute nothing
   (their slots are zero by the writer's rule). Kinds without a fast
   path fold over col-ref."
  [col]
  #?(:cljs (throw (ex-info "eve.flat.column: reading is not built on ClojureScript yet (lane H4)" {}))
     :clj
     (let [kind (int (:kind col))
           n (long (:n col))
           d (long (:data-off col))]
       (case kind
         9 (let [^ByteBuffer bb (:bb col)]
             (loop [i 0 acc 0.0]
               (if (== i n) acc (recur (inc i) (+ acc (.getDouble bb (int (+ d (* 8 i)))))))))
         8 (let [^ByteBuffer bb (:bb col)]
             (loop [i 0 acc 0.0]
               (if (== i n) acc (recur (inc i) (+ acc (double (.getFloat bb (int (+ d (* 4 i))))))))))
         6 (let [^ByteBuffer bb (:bb col)]
             (loop [i 0 acc 0]
               (if (== i n) acc (recur (inc i) (+ acc (.getInt bb (int (+ d (* 4 i)))))))))
         10 (let [^ByteBuffer bb (:bb col)]
              (loop [i 0 acc 0]
                (if (== i n) acc (recur (inc i) (+ acc (.getLong bb (int (+ d (* 8 i)))))))))
         22 (let [b (:c col)
                  base (long (:base col))
                  w (long (:width col))]
              (loop [i 0 acc 0]
                (if (== i n)
                  acc
                  (recur (inc i)
                         (+ acc base
                            (case (int w)
                              1 (fio/u8 b (+ d i))
                              2 (fio/u16 b (+ d (* 2 i)))
                              4 (fio/u32 b (+ d (* 4 i)))))))))
         (loop [i 0 acc 0]
           (if (== i n)
             acc
             (recur (inc i) (let [v (col-ref col i)] (if (nil? v) acc (+ acc v))))))))))

(defn count-eq
  "How many rows of a column equal v, counted in place. Symbol columns
   compare codes; dense integer columns compare words; anything else
   folds over col-ref."
  ^long [col v]
  #?(:cljs (throw (ex-info "eve.flat.column: reading is not built on ClojureScript yet (lane H4)" {}))
     :clj
     (let [kind (int (:kind col))
           n (long (:n col))
           d (long (:data-off col))
           b (:c col)
           voff (:valid-off col)
           valid? (fn [i] (or (nil? voff) (bit-set? b (long voff) i)))]
       (cond
         (nil? v)
         (if voff
           (loop [i 0 acc 0]
             (if (== i n) acc (recur (inc i) (if (valid? i) acc (inc acc)))))
           0)

         (== kind 17)
         (let [code (let [c (:code (:ctx col))] (if (fn? c) (c v) (get c v)))]
           (if (nil? code)
             0
             (let [code (long code)]
               (loop [i 0 acc 0]
                 (if (== i n)
                   acc
                   (recur (inc i)
                          (if (and (valid? i) (== code (fio/u8 b (+ d i))))
                            (inc acc) acc)))))))

         (== kind 18)
         (let [code (let [c (:code (:ctx col))] (if (fn? c) (c v) (get c v)))]
           (if (nil? code)
             0
             (let [code (long code)]
               (loop [i 0 acc 0]
                 (if (== i n)
                   acc
                   (recur (inc i)
                          (if (and (valid? i) (== code (fio/u16 b (+ d (* 2 i)))))
                            (inc acc) acc)))))))

         (== kind 16)
         (let [want (boolean v)]
           (loop [i 0 acc 0]
             (if (== i n)
               acc
               (recur (inc i)
                      (if (and (valid? i) (== want (bit-set? b d i)))
                        (inc acc) acc)))))

         :else
         (loop [i 0 acc 0]
           (if (== i n)
             acc
             (recur (inc i) (if (= v (col-ref col i)) (inc acc) acc))))))))

(defn indices-eq
  "The row indexes whose cell equals v, read in place."
  [col v]
  (let [n (:n col)]
    (loop [i 0 acc (transient [])]
      (if (== i n)
        (persistent! acc)
        (recur (inc i) (if (= v (col-ref col i)) (conj! acc i) acc))))))

;; ---------------------------------------------------------------------------
;; the zero-copy handoff (C2.5)
;; ---------------------------------------------------------------------------

(defn typed-array-view
  "A dense column (kinds 1-11) handed to the host as its typed array
   without a copy: on the JVM, a read-only NIO buffer view over the
   column's own byte array (ByteBuffer for u8/i8/u8-clamped, ShortBuffer
   for i16/u16, IntBuffer for i32/u32, FloatBuffer, DoubleBuffer,
   LongBuffer for i64/u64). The view shares the array — writing the
   column's bytes moves it — and refuses writes. A nilable column hands
   over its data slots as they lie (nil rows read as zero). Any other
   kind is refused by name."
  [col]
  #?(:cljs (throw (ex-info "eve.flat.column: the typed-array handoff is not built on ClojureScript yet (lane H4)" {}))
     :clj
     (let [kind (int (:kind col))
           esz (dense-size kind)]
       (when-not esz
         (throw (ex-info "eve.flat.column: only a dense column (kinds 1-11) hands over a typed array"
                         {:eve.flat.column/check :not-dense :kind kind})))
       (let [n (long (:n col))
             d (long (:data-off col))
             bb (doto (ByteBuffer/wrap ^bytes (:ba col) (int d) (int (* esz n)))
                  (.order ByteOrder/LITTLE_ENDIAN))]
         (case kind
           (1 2 3) (.asReadOnlyBuffer bb)
           (4 5) (.asReadOnlyBuffer (.asShortBuffer bb))
           (6 7) (.asReadOnlyBuffer (.asIntBuffer bb))
           8 (.asReadOnlyBuffer (.asFloatBuffer bb))
           9 (.asReadOnlyBuffer (.asDoubleBuffer bb))
           (10 11) (.asReadOnlyBuffer (.asLongBuffer bb)))))))
