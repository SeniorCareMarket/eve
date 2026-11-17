;; A prototype PACKED chunk codec for the static-Eve research (research code: it exists to be measured, not shipped).
;;
;; One chunk is self-contained:  [header 28 B][symbol table][shape table][body]
;;   symbol table  the chunk's cache: every keyword / symbol, and every string that is a map key or repeats, stored
;;                 ONCE as its EVE scalar bytes (eve.codec/value->eve-bytes minus the two magic bytes); a use is a
;;                 SYMREF [0x20][varint code]. Codes are assigned by descending use count.
;;   shape table   every distinct key tuple of a map whose keys are all symbols, stored once (keys in ascending
;;                 EVE-key-byte order, eve's canonical order); a map of that shape is [0x21][shape][value offsets]
;;                 [values].
;;   body          nodes. A scalar is its EVE bytes minus the magic ([tag][payload], byte-identical to eve's); nil
;;                 is 0x00. Containers (the second tag is the same node with u32 offsets, taken when the node
;;                 passes 64 KiB): SMAP 0x21/0x2D, VEC 0x22/0x2A, TVEC 0x23 (a dense typed vector: bool bitmap, i32,
;;                 i64, f64, 8-aligned), SET 0x24/0x2E, LIST 0x25/0x2B, BATCH 0x26 (rows of one shape, stored column
;;                 by column), GMAP 0x27/0x2C (a map with non-symbol keys, entries in key-byte order).
;;
;; Options (to price each mechanism separately): :sym? :shape? :batch? :tvec?
(ns static-probe.pack
  (:require [eve.codec :as codec]
            [eve.deftype-proto.data :as d])
  (:import [java.util Arrays HashMap ArrayList]
           [java.nio ByteBuffer ByteOrder]
           [java.util.zip Deflater]))

(set! *warn-on-reflection* false)

;; ---------------------------------------------------------------------------------------------------------------
;; a growable little-endian writer
;; ---------------------------------------------------------------------------------------------------------------

(definterface IW
  (^bytes buf [])
  (^long pos [])
  (setPos [^long p])
  (u8 [^long v])
  (u16 [^long v])
  (u32 [^long v])
  (i64 [^long v])
  (f64 [^double v])
  (varint [^long v])
  (bytes [^bytes b ^long off ^long n])
  (putU16 [^long at ^long v])
  (putU32 [^long at ^long v])
  (alignTo [^long a])
  (insertGap [^long at ^long n]))

(deftype W [^:unsynchronized-mutable ^bytes b ^:unsynchronized-mutable ^long p]
  IW
  (buf [_] b)
  (pos [_] p)
  (setPos [_ np] (set! p np))
  (u8 [this v] (when (>= p (alength b)) (set! b (Arrays/copyOf b (* 2 (alength b)))))
    (aset b p (unchecked-byte v)) (set! p (inc p)))
  (u16 [this v] (.u8 this v) (.u8 this (bit-shift-right v 8)))
  (u32 [this v] (.u8 this v) (.u8 this (bit-shift-right v 8)) (.u8 this (bit-shift-right v 16)) (.u8 this (bit-shift-right v 24)))
  (i64 [this v] (.u32 this v) (.u32 this (bit-shift-right v 32)))
  (f64 [this v] (.i64 this (Double/doubleToRawLongBits v)))
  (varint [this v]
    (loop [v v]
      (if (< v 128) (.u8 this v)
          (do (.u8 this (bit-or (bit-and v 127) 128)) (recur (unsigned-bit-shift-right v 7))))))
  (bytes [this src off n]
    (when (> (+ p n) (alength b)) (set! b (Arrays/copyOf b (int (max (+ p n) (* 2 (alength b)))))))
    (System/arraycopy src (int off) b (int p) (int n)) (set! p (+ p n)))
  (putU16 [_ at v] (aset b (int at) (unchecked-byte v)) (aset b (int (inc at)) (unchecked-byte (bit-shift-right v 8))))
  (putU32 [_ at v]
    (aset b (int at) (unchecked-byte v)) (aset b (int (+ at 1)) (unchecked-byte (bit-shift-right v 8)))
    (aset b (int (+ at 2)) (unchecked-byte (bit-shift-right v 16))) (aset b (int (+ at 3)) (unchecked-byte (bit-shift-right v 24))))
  (alignTo [this a] (loop [] (when-not (zero? (rem p a)) (.u8 this 0) (recur))))
  (insertGap [_ at n]
    (when (> (+ p n) (alength b)) (set! b (Arrays/copyOf b (int (max (+ p n) (* 2 (alength b)))))))
    (System/arraycopy b (int at) b (int (+ at n)) (int (- p at)))
    (set! p (+ p n))))

(defn writer ^W [] (W. (byte-array 4096) 0))

;; ---------------------------------------------------------------------------------------------------------------
;; classification
;; ---------------------------------------------------------------------------------------------------------------

(defn symbolic? [x] (or (keyword? x) (symbol? x)))
(defn scalar? [x] (not (coll? x)))

(defn scalar-body
  "x's EVE bytes without the two magic bytes: [tag][payload]; nil is the single byte 0x00."
  ^bytes [x]
  (if (nil? x)
    (byte-array 1)
    (let [^bytes bs (codec/value->eve-bytes x)]
      (Arrays/copyOfRange bs 2 (alength bs)))))

(defn- shaped? [m] (and (pos? (count m)) (every? #(or (symbolic? %) (string? %)) (keys m))))

(defn- canon-keys
  "m's keys in eve's canonical order: ascending encoded key bytes."
  [m]
  (mapv second (sort-by first d/compare-bytes (map (fn [k] [(codec/encode-key k) k]) (keys m)))))

;; ---------------------------------------------------------------------------------------------------------------
;; pass 1: what the chunk's tables hold
;; ---------------------------------------------------------------------------------------------------------------

(defn- scan!
  "Count symbol candidates (value -> long[2]: occurrences, occurrences as a map key) and collect shapes under x."
  [x ^HashMap counts ^HashMap shapes opts key?]
  (cond
    (or (symbolic? x) (string? x))
    (let [^longs c (or (.get counts x) (let [c (long-array 2)] (.put counts x c) c))]
      (aset c 0 (inc (aget c 0)))
      (when key? (aset c 1 (inc (aget c 1)))))
    (map? x)
    (do (when (and (:shape? opts) (shaped? x))
          (let [ks (canon-keys x)]
            (when-not (.containsKey shapes ks) (.put shapes ks (.size shapes)))))
        (doseq [[k v] x] (scan! k counts shapes opts true) (scan! v counts shapes opts false)))
    (coll? x) (doseq [e x] (scan! e counts shapes opts false))
    :else nil))

(defn- tables
  "{:sym {value code} :syms [value …] :shapes {keys code} :shape-list [keys …] :occ n :occ-all n} for the values of
   one chunk. A keyword or symbol is always in the symbol table; a string when it is a map key or occurs twice.
   :occ-all counts every keyword / symbol / string occurrence under xs, :occ those the table holds."
  [xs opts]
  (let [counts (HashMap.) shapes (HashMap.)]
    (doseq [x xs] (scan! x counts shapes opts false))
    (let [member? (fn [e] (let [k (key e) ^longs c (val e)]
                            (and (:sym? opts) (or (symbolic? k) (>= (aget c 0) 2) (pos? (aget c 1))))))
          members (filter member? counts)
          syms (->> members
                    (sort-by (fn [e] [(- (aget ^longs (val e) 0)) (str (key e))]))
                    (mapv key))
          shape-list (mapv first (sort-by second (map (fn [e] [(key e) (val e)]) shapes)))]
      {:sym (zipmap syms (range)) :syms syms
       :shapes (zipmap shape-list (range)) :shape-list shape-list
       :occ (reduce + 0 (map (fn [e] (aget ^longs (val e) 0)) members))
       :occ-all (reduce + 0 (map (fn [e] (aget ^longs (val e) 0)) counts))})))

;; ---------------------------------------------------------------------------------------------------------------
;; pass 2: the nodes
;; ---------------------------------------------------------------------------------------------------------------

(declare node!)

(defn- scalar! [^W w x tb]
  (let [code (when tb (get (:sym tb) x))]
    (if code
      (do (.u8 w 0x20) (.varint w code))
      (let [b (scalar-body x)] (.bytes w b 0 (alength b))))))

(defn- offsets-node!
  "A node [head …][n offsets][children…]: write-head writes the bytes before the table; children are written by
   (write-child i). Offsets are relative to the node's start; u16 unless the node passes 64 KiB (then the tag's wide
   twin and u32)."
  [^W w tag wide-tag n write-head write-child]
  (let [start (.pos w)]
    (.u8 w tag)
    (write-head)
    (let [table (.pos w)
          offs (long-array n)]
      (dotimes [_ n] (.u16 w 0))
      (dotimes [i n]
        (aset offs i (- (.pos w) start))
        (write-child i))
      (if (<= (- (.pos w) start) 65535)
        (dotimes [i n] (.putU16 w (+ table (* 2 i)) (aget offs i)))
        ;; the gap is a multiple of 8, so a child's alignment padding (written before the move) still holds
        (let [gap (bit-and (+ (* 2 n) 7) (bit-not 7))]
          (.insertGap w (+ table (* 2 n)) gap)
          (aset (.buf w) (int start) (unchecked-byte wide-tag))
          (dotimes [i n] (.putU32 w (+ table (* 4 i)) (+ (aget offs i) gap))))))))

(defn- typed-kind
  "The dense element type of xs when every element fits one: :bool :i32 :i64 :f64, else nil."
  [xs]
  (when (seq xs)
    (cond (every? boolean? xs) :bool
          (every? #(and (integer? %) (not (instance? clojure.lang.BigInt %)) (not (instance? java.math.BigInteger %))) xs)
          (if (every? #(<= Integer/MIN_VALUE (long %) Integer/MAX_VALUE) xs) :i32 :i64)
          (every? #(instance? Double %) xs) :f64
          :else nil)))

(defn- dense! [^W w kind xs chunk-start]
  (case kind
    :bool (let [n (count xs) bs (byte-array (quot (+ n 7) 8))]
            (dorun (map-indexed (fn [i x] (when x (aset bs (quot i 8) (unchecked-byte (bit-or (aget bs (quot i 8)) (bit-shift-left 1 (rem i 8))))))) xs))
            (.bytes w bs 0 (alength bs)))
    :i32 (do (loop [] (when-not (zero? (rem (- (.pos w) chunk-start) 4)) (.u8 w 0) (recur)))
             (doseq [x xs] (.u32 w (long x))))
    :i64 (do (loop [] (when-not (zero? (rem (- (.pos w) chunk-start) 8)) (.u8 w 0) (recur)))
             (doseq [x xs] (.i64 w (long x))))
    :f64 (do (loop [] (when-not (zero? (rem (- (.pos w) chunk-start) 8)) (.u8 w 0) (recur)))
             (doseq [x xs] (.f64 w (double x))))))

(def ^:private kind-code {:bool 1 :i32 2 :i64 3 :f64 4})

(defn- batchable?
  "Are xs (≥ 2) all maps of one shape?"
  [xs]
  (and (>= (count xs) 2)
       (map? (first xs)) (shaped? (first xs))
       (let [ks (set (keys (first xs))) n (count ks)]
         (every? #(and (map? %) (== n (count %)) (every? ks (keys %))) (rest xs)))))

(defn- column! [^W w col tb opts chunk-start]
  ;; [coltype u8][data]; coltype | 0x80 = a validity bitmap precedes the data (a nil-able column)
  (let [nils? (some nil? col)
        present (if nils? (remove nil? col) col)
        tk (when (:tvec? opts) (typed-kind present))
        sym-col? (and tb (seq present) (every? #(contains? (:sym tb) %) present))
        nested? (and (not tk) (not nils?) (batchable? col))
        strs? (and (seq present) (every? string? present) (not sym-col?))
        flag (if nils? 0x80 0)
        validity! (fn [] (when nils? (dense! w :bool (map some? col) chunk-start)))
        fill (fn [zero] (map #(if (nil? %) zero %) col))]
    (cond
      tk (do (.u8 w (bit-or flag (kind-code tk))) (validity!)
             (dense! w tk (fill (case tk :bool false :f64 0.0 0)) chunk-start))
      sym-col? (let [codes (map #(if (nil? %) 0 (get (:sym tb) %)) col)
                     wide? (> (count (:syms tb)) 256)]
                 (.u8 w (bit-or flag (if wide? 6 5))) (validity!)
                 (doseq [c codes] (if wide? (.u16 w c) (.u8 w c))))
      nested? (do (.u8 w 8) (node! w (vec col) tb opts chunk-start))
      strs? (do (.u8 w (bit-or flag 7)) (validity!)
                (let [bs (mapv #(if (nil? %) (byte-array 0) (.getBytes ^String % "UTF-8")) col)]
                  (loop [] (when-not (zero? (rem (- (.pos w) chunk-start) 4)) (.u8 w 0) (recur)))
                  (reduce (fn [acc ^bytes b] (.u32 w acc) (+ acc (alength b))) 0 bs)
                  (.u32 w (reduce + (map #(alength ^bytes %) bs)))
                  (doseq [^bytes b bs] (.bytes w b 0 (alength b)))))
      :else (do (.u8 w 0)
                (offsets-node! w 0x22 0x2A (count col)
                               #(.varint w (count col))
                               #(node! w (nth col %) tb opts chunk-start))))))

(defn node! [^W w x tb opts chunk-start]
  (cond
    (scalar? x) (scalar! w x tb)

    (map? x)
    (if (and (:shape? opts) tb (shaped? x))
      (let [ks (canon-keys x) code (get (:shapes tb) ks)]
        (offsets-node! w 0x21 0x2D (count ks) #(.varint w code) #(node! w (get x (nth ks %)) tb opts chunk-start)))
      (let [es (sort-by first d/compare-bytes (map (fn [[k v]] [(codec/encode-key k) k v]) x))
            n (count es)]
        (offsets-node! w 0x27 0x2C (* 2 n) #(.varint w n)
                       (fn [i] (if (< i n)
                                 (node! w (nth (nth es i) 1) tb opts chunk-start)
                                 (node! w (nth (nth es (- i n)) 2) tb opts chunk-start))))))

    (set? x)
    (let [es (mapv second (sort-by first d/compare-bytes (map (fn [e] [(codec/encode-key e) e]) x)))]
      (offsets-node! w 0x24 0x2E (count es) #(.varint w (count es)) #(node! w (nth es %) tb opts chunk-start)))

    :else                                 ;; sequential: a vector or a list
    (let [xs (vec x) n (count xs) list? (list? x)
          tk (when (and (:tvec? opts) (not list?)) (typed-kind xs))]
      (cond
        tk (do (.u8 w 0x23) (.u8 w (kind-code tk)) (.varint w n) (dense! w tk xs chunk-start))

        (and (:batch? opts) (:shape? opts) tb (not list?) (batchable? xs))
        (let [ks (canon-keys (first xs)) code (get (:shapes tb) ks) nc (count ks)
              start (.pos w)]
          (.u8 w 0x26) (.varint w code) (.varint w n)
          (let [table (.pos w)]
            (dotimes [_ nc] (.u32 w 0))
            (dotimes [c nc]
              (.putU32 w (+ table (* 4 c)) (- (.pos w) start))
              (column! w (mapv #(get % (nth ks c)) xs) tb opts chunk-start))))

        :else
        (offsets-node! w (if list? 0x25 0x22) (if list? 0x2B 0x2A) n #(.varint w n) #(node! w (nth xs %) tb opts chunk-start))))))

;; ---------------------------------------------------------------------------------------------------------------
;; a chunk
;; ---------------------------------------------------------------------------------------------------------------

(def default-opts {:sym? true :shape? true :batch? true :tvec? true})

(defn chunk!
  "One self-contained chunk holding root (any value): {:bytes :n-sym :n-shape :table-bytes :header-bytes}."
  ([root] (chunk! root default-opts))
  ([root opts]
   (let [w (writer)
         tb (when (or (:sym? opts) (:shape? opts)) (tables [root] opts))
         nsym (count (:syms tb)) nshape (count (:shape-list tb))]
     ;; header
     (.bytes w (.getBytes "EVS1" "US-ASCII") 0 4)
     (.u8 w 0) (.u8 w 0) (.u16 w 0)
     (.u32 w 0)                                   ;; chunk length, patched
     (.u16 w nsym) (.u16 w nshape)
     (.u32 w 0) (.u32 w 0) (.u32 w 0)             ;; symtab, shapetab, root offsets, patched
     (let [symtab (.pos w)]
       ;; symbol table: nsym+1 offsets, then the entries
       (dotimes [_ (inc nsym)] (.u16 w 0))
       (let [entries (.pos w)]
         (dotimes [i nsym]
           (.putU16 w (+ symtab (* 2 i)) (- (.pos w) entries))
           (let [b (scalar-body (nth (:syms tb) i))] (.bytes w b 0 (alength b))))
         (.putU16 w (+ symtab (* 2 nsym)) (- (.pos w) entries)))
       (let [shapetab (.pos w)]
         (dotimes [_ (inc nshape)] (.u16 w 0))
         (let [entries (.pos w)]
           (dotimes [i nshape]
             (.putU16 w (+ shapetab (* 2 i)) (- (.pos w) entries))
             (let [ks (nth (:shape-list tb) i)]
               (.varint w (count ks))
               (.varint w 0)                      ;; the type key's code + 1 (0: a plain map)
               (doseq [k ks] (.varint w (get (:sym tb) k)))))
           (.putU16 w (+ shapetab (* 2 nshape)) (- (.pos w) entries)))
         (let [root-off (.pos w)]
           (node! w root tb opts 0)
           (.putU32 w 8 (.pos w))
           (.putU32 w 16 symtab) (.putU32 w 20 shapetab) (.putU32 w 24 root-off)
           {:bytes (Arrays/copyOf (.buf w) (int (.pos w)))
            :n-sym nsym :n-shape nshape
            :occ (or (:occ tb) 0) :occ-all (or (:occ-all tb) 0)
            :header-bytes 28
            :table-bytes (- root-off 28)}))))))

(defn chunks!
  "rows (a vector) packed into self-contained chunks of at most target bytes each (one row may pass it alone):
   [{:bytes :rows n …chunk! stats} …]. Each chunk's root is the vector of its rows, so each has its own tables."
  ([rows target] (chunks! rows target default-opts))
  ([rows target opts]
   (let [n (count rows)]
     (if (or (nil? target) (zero? n))
       [(assoc (chunk! rows opts) :rows n)]
       (loop [i 0 k (max 1 (min n 64)) out [] tries 0]
         (if (>= i n)
           out
           (let [k (max 1 (min k (- n i)))
                 c (chunk! (subvec rows i (+ i k)) opts)
                 size (alength ^bytes (:bytes c))]
             (cond
               ;; too big: shrink (k strictly falls) and retry; a single row is accepted whatever its size
               (and (> size target) (> k 1))
               (recur i (max 1 (long (* k (/ (double target) size) 0.9))) out (inc tries))
               ;; well under the target with rows left: grow, a bounded number of times
               (and (< size (* 0.7 target)) (< (+ i k) n) (< tries 4))
               (recur i (min (- n i) (max (inc k) (long (* k (/ (double target) (max 1 size)) 0.92)))) out (inc tries))
               :else
               (recur (+ i k) k (conj out (assoc c :rows k)) 0)))))))))

;; ---------------------------------------------------------------------------------------------------------------
;; reading a chunk back (to plain data — the correctness check; in-place accessors are in the probes)
;; ---------------------------------------------------------------------------------------------------------------

(defn- ru8 ^long [^bytes b ^long i] (bit-and (long (aget b (int i))) 0xFF))
(defn- ru16 ^long [^bytes b ^long i] (bit-or (ru8 b i) (bit-shift-left (ru8 b (inc i)) 8)))
(defn- ru32 ^long [^bytes b ^long i] (bit-or (ru16 b i) (bit-shift-left (ru16 b (+ i 2)) 16)))
(defn- ri32 ^long [^bytes b ^long i] (long (unchecked-int (ru32 b i))))
(defn- ri64 ^long [^bytes b ^long i] (bit-or (ru32 b i) (bit-shift-left (ru32 b (+ i 4)) 32)))
(defn- rvarint
  "[value next-pos]"
  [^bytes b ^long i]
  (loop [i i sh 0 v 0]
    (let [x (ru8 b i)]
      (if (< x 128) [(bit-or v (bit-shift-left x sh)) (inc i)]
          (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

(def ^:private MAGIC (byte-array [(unchecked-byte 0xEE) (unchecked-byte 0xDB)]))

(defn- scalar-len
  "The byte length of the EVE scalar body at i (its tag and payload)."
  ^long [^bytes b ^long i]
  (let [t (ru8 b i)]
    (case (int t)
      0x00 1 (0x01 0x02) 1 0x03 5 (0x04 0x0E 0x0F) 9
      (0x05 0x07 0x0C) (+ 2 (ru8 b (inc i)))
      (0x06 0x08) (+ 5 (ru32 b (inc i)))
      (0x09 0x0D) (let [nl (ru8 b (inc i))] (+ 3 nl (ru8 b (+ i 2 nl))))
      0x0A (let [nl (ru32 b (inc i))] (+ 9 nl (ru32 b (+ i 5 nl))))
      0x0B 17 0x14 3
      0x16 (+ 5 (ru32 b (inc i)))
      0x17 (let [nl (ru32 b (inc i))] (+ 9 nl (ru32 b (+ i 5 nl))))
      0x18 (+ 9 (ru32 b (+ i 5)))
      (throw (ex-info "pack: a scalar tag this reader does not size" {:tag t})))))

(defn- scalar-at [^bytes b ^long i]
  (if (zero? (ru8 b i))
    nil
    (let [n (scalar-len b i) bs (byte-array (+ 2 n))]
      (aset bs 0 (aget ^bytes MAGIC 0)) (aset bs 1 (aget ^bytes MAGIC 1))
      (System/arraycopy b (int i) bs 2 (int n))
      (codec/decode nil bs))))

(defn open-chunk
  "A chunk's tables decoded once: {:b bytes :syms [value…] :shapes [[key…]…] :root offset}."
  [^bytes b]
  (assert (= "EVS1" (String. b 0 4 "US-ASCII")))
  (let [nsym (ru16 b 12) nshape (ru16 b 14) symtab (ru32 b 16) shapetab (ru32 b 20) root (ru32 b 24)
        sym-entries (+ symtab (* 2 (inc nsym)))
        syms (mapv (fn [i] (scalar-at b (+ sym-entries (ru16 b (+ symtab (* 2 i)))))) (range nsym))
        shape-entries (+ shapetab (* 2 (inc nshape)))
        shapes (mapv (fn [i]
                       (let [p (+ shape-entries (ru16 b (+ shapetab (* 2 i))))
                             [n p] (rvarint b p) [_ p] (rvarint b p)]
                         (loop [j 0 p p ks []]
                           (if (== j n) ks
                               (let [[c p] (rvarint b p)] (recur (inc j) p (conj ks (nth syms c))))))))
                     (range nshape))]
    {:b b :syms syms :shapes shapes :root root}))

(declare read-node)

(defn- read-dense [^bytes b kind n p]
  ;; p: position right after the count (alignment padding follows for the wide kinds)
  (case (int kind)
    1 (mapv (fn [i] (not (zero? (bit-and (ru8 b (+ p (quot i 8))) (bit-shift-left 1 (rem i 8)))))) (range n))
    2 (let [p (bit-and (+ p 3) (bit-not 3))] (mapv (fn [i] (ri32 b (+ p (* 4 i)))) (range n)))
    3 (let [p (bit-and (+ p 7) (bit-not 7))] (mapv (fn [i] (ri64 b (+ p (* 8 i)))) (range n)))
    4 (let [p (bit-and (+ p 7) (bit-not 7))] (mapv (fn [i] (Double/longBitsToDouble (ri64 b (+ p (* 8 i))))) (range n)))))

(defn- dense-bytes ^long [kind n p]
  (case (int kind)
    1 (quot (+ n 7) 8)
    2 (+ (- (bit-and (+ p 3) (bit-not 3)) p) (* 4 n))
    (3 4) (+ (- (bit-and (+ p 7) (bit-not 7)) p) (* 8 n))))

(defn- read-offsets-node [ch ^bytes b start head-end n wide?]
  (mapv (fn [i] (read-node ch (+ start (if wide? (ru32 b (+ head-end (* 4 i))) (ru16 b (+ head-end (* 2 i)))))))
        (range n)))

(defn- read-column [ch ^bytes b p n]
  (let [ct (ru8 b p) nil-able? (not (zero? (bit-and ct 0x80))) ct (bit-and ct 0x7F)
        p (inc p)
        valid (when nil-able? (read-dense b 1 n p))
        p (if nil-able? (+ p (quot (+ n 7) 8)) p)
        mask (fn [xs] (if valid (mapv (fn [ok x] (when ok x)) valid xs) xs))]
    (case (int ct)
      0 (read-node ch p)
      (1 2 3 4) (mask (read-dense b ct n p))
      5 (mask (mapv (fn [i] (nth (:syms ch) (ru8 b (+ p i)))) (range n)))
      6 (mask (mapv (fn [i] (nth (:syms ch) (ru16 b (+ p (* 2 i))))) (range n)))
      7 (let [p (bit-and (+ p 3) (bit-not 3)) data (+ p (* 4 (inc n)))]
          (mask (mapv (fn [i] (let [o (ru32 b (+ p (* 4 i))) e (ru32 b (+ p (* 4 (inc i))))]
                                (String. b (int (+ data o)) (int (- e o)) "UTF-8")))
                      (range n))))
      8 (read-node ch p))))

(defn read-node
  "The plain value of the node at offset p of an opened chunk."
  [ch ^long p]
  (let [^bytes b (:b ch) t (ru8 b p)]
    (case (int t)
      0x20 (let [[c _] (rvarint b (inc p))] (nth (:syms ch) c))
      (0x21 0x2D) (let [[code q] (rvarint b (inc p)) ks (nth (:shapes ch) code)]
                    (zipmap ks (read-offsets-node ch b p q (count ks) (== t 0x2D))))
      (0x22 0x2A 0x25 0x2B)
      (let [[n q] (rvarint b (inc p))
            xs (read-offsets-node ch b p q n (or (== t 0x2A) (== t 0x2B)))]
        (if (or (== t 0x25) (== t 0x2B)) (apply list xs) xs))
      0x23 (let [kind (ru8 b (inc p)) [n q] (rvarint b (+ p 2))] (read-dense b kind n q))
      (0x24 0x2E) (let [[n q] (rvarint b (inc p))] (set (read-offsets-node ch b p q n (== t 0x2E))))
      0x26 (let [[code q] (rvarint b (inc p)) [n q] (rvarint b q)
                 ks (nth (:shapes ch) code)
                 cols (mapv (fn [c] (read-column ch b (+ p (ru32 b (+ q (* 4 c)))) n)) (range (count ks)))]
             (mapv (fn [i] (zipmap ks (map #(nth % i) cols))) (range n)))
      (0x27 0x2C)
      (let [[n q] (rvarint b (inc p))
            xs (read-offsets-node ch b p q (* 2 n) (== t 0x2C))]
        (zipmap (subvec xs 0 n) (subvec xs n)))
      (scalar-at b p))))

(defn unchunk
  "The plain value a chunk holds."
  [^bytes b]
  (let [ch (open-chunk b)] (read-node ch (:root ch))))

(defn deflated-size
  "The size of bs under zlib (level 6): a reference for what opaque compression would still take off."
  ^long [^bytes bs]
  (let [df (Deflater. 6) out (byte-array 65536)]
    (.setInput df bs) (.finish df)
    (loop [n 0] (if (.finished df) (do (.end df) n) (recur (+ n (.deflate df out)))))))

;; ---------------------------------------------------------------------------------------------------------------
;; in-place access to a batch: nothing of the chunk is decoded but what is asked for
;; ---------------------------------------------------------------------------------------------------------------

(defn- align ^long [^long p ^long a] (bit-and (+ p (dec a)) (bit-not (dec a))))

(defn batch-at
  "The batch node at p of opened chunk ch: {:n rows :ks [key…] :cols [{:ct type :valid pos|nil :data pos} …]}."
  [ch ^long p]
  (let [^bytes b (:b ch)]
    (when-not (== 0x26 (ru8 b p)) (throw (ex-info "pack: not a batch" {:tag (ru8 b p)})))
    (let [[code q] (rvarint b (inc p)) [n q] (rvarint b q) ks (nth (:shapes ch) code)
          n (long n) q (long q)]
      {:n n :ks ks
       :cols (mapv (fn [c]
                     (let [cp (+ p (ru32 b (+ q (* 4 c))))
                           ct0 (ru8 b cp) nil-able? (not (zero? (bit-and ct0 0x80))) ct (bit-and ct0 0x7F)
                           v (inc cp)
                           dd (if nil-able? (+ v (quot (+ n 7) 8)) v)
                           dd (case (int ct) (2 7) (align dd 4) (3 4) (align dd 8) dd)]
                       {:ct ct :valid (when nil-able? v) :data dd
                        :nested (when (== ct 8) (delay (batch-at ch dd)))}))
                   (range (count ks)))})))

(defn batch
  "The batch at the root of an opened chunk."
  [ch]
  (batch-at ch (:root ch)))

(defn col-index ^long [bt k] (long (.indexOf ^java.util.List (:ks bt) k)))

(defn f64-sum
  "The sum of float64 column ci of batch bt, read in place."
  ^double [ch bt ^long ci]
  (let [^bytes b (:b ch)
        bb (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))
        col (nth (:cols bt) ci)
        n (long (:n bt)) d (long (:data col))]
    (when-not (== 4 (long (:ct col))) (throw (ex-info "pack: not a float64 column" col)))
    (loop [i 0 acc 0.0]
      (if (== i n) acc (recur (inc i) (+ acc (.getDouble bb (int (+ d (* 8 i))))))))))

(defn sym-code
  "The code of symbol v in the chunk's table, or -1."
  ^long [ch v]
  (long (.indexOf ^java.util.List (:syms ch) v)))

(defn count-sym=
  "How many rows of symbol-code column ci hold code."
  ^long [ch bt ^long ci ^long code]
  (let [^bytes b (:b ch) col (nth (:cols bt) ci) n (long (:n bt)) d (long (:data col)) ct (long (:ct col))]
    (case (int ct)
      5 (loop [i 0 acc 0] (if (== i n) acc (recur (inc i) (if (== code (ru8 b (+ d i))) (inc acc) acc))))
      6 (loop [i 0 acc 0] (if (== i n) acc (recur (inc i) (if (== code (ru16 b (+ d (* 2 i)))) (inc acc) acc))))
      (throw (ex-info "pack: not a symbol column" col)))))

(declare row-at)

(defn cell
  "The plain value of row i of column ci, read in place."
  [ch bt ^long ci ^long i]
  (let [^bytes b (:b ch) col (nth (:cols bt) ci) d (long (:data col)) ct (long (:ct col))]
    (if (and (:valid col) (zero? (bit-and (ru8 b (+ (long (:valid col)) (quot i 8))) (bit-shift-left 1 (rem i 8)))))
      nil
      (case (int ct)
        0 (let [[n q] (rvarint b (inc d)) wide? (== 0x2A (ru8 b d))
                off (if wide? (ru32 b (+ (long q) (* 4 i))) (ru16 b (+ (long q) (* 2 i))))]
            (read-node ch (+ d off)))
        1 (not (zero? (bit-and (ru8 b (+ d (quot i 8))) (bit-shift-left 1 (rem i 8)))))
        2 (ri32 b (+ d (* 4 i)))
        3 (ri64 b (+ d (* 8 i)))
        4 (Double/longBitsToDouble (ri64 b (+ d (* 8 i))))
        5 (nth (:syms ch) (ru8 b (+ d i)))
        6 (nth (:syms ch) (ru16 b (+ d (* 2 i))))
        7 (let [n (long (:n bt)) data (+ d (* 4 (inc n)))
                o (ru32 b (+ d (* 4 i))) e (ru32 b (+ d (* 4 (inc i))))]
            (String. b (int (+ data o)) (int (- e o)) "UTF-8"))
        8 (row-at ch @(:nested col) i)))))

(defn row-at
  "Row i of batch bt as a plain map, assembled from its columns."
  [ch bt ^long i]
  (let [ks (:ks bt) nc (count ks)]
    (loop [c 0 m (transient {})]
      (if (== c nc) (persistent! m) (recur (inc c) (assoc! m (nth ks c) (cell ch bt c i)))))))
