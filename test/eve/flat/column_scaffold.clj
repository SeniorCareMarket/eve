(ns eve.flat.column-scaffold
  "SCAFFOLDING for lane C2's tests — NOT product code.

   The chunk-level pieces C2's column tests need before lane C1 lands
   src/eve/flat/chunk.cljc: a symbol table built by the prototype's rule
   (probes/pack.clj), scalar node writing/reading for the `any` column kind
   (FORMAT-V1 §3 scalar bytes, via eve.codec), a shape registry, and the ctx
   map eve.flat.column reads through. Every piece here is replaced by C1's
   chunk reader/writer on merge; the tests below the line only touch
   eve.flat.column's own API plus this ctx."
  (:require [eve.codec :as codec])
  ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
  (:import #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays HashMap]))

;; ---------------------------------------------------------------------------
;; varints (unsigned LEB128, the prototype's)
;; ---------------------------------------------------------------------------

(defn varint-bytes
  "v as an unsigned LEB128 byte array."
  ^bytes [v]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop [v (long v)]
      (if (< v 128)
        (.write out (int v))
        (do (.write out (int (bit-or (bit-and v 127) 128)))
            (recur (unsigned-bit-shift-right v 7)))))
    (.toByteArray out)))

(defn read-varint
  "[value next-pos] for the unsigned LEB128 at ba[i]."
  [^bytes ba ^long i]
  (loop [i i sh 0 v 0]
    (let [x (bit-and (aget ba (int i)) 0xFF)]
      (if (< x 128)
        [(bit-or v (bit-shift-left x sh)) (inc i)]
        (recur (inc i) (+ sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))

;; ---------------------------------------------------------------------------
;; scalar nodes (FORMAT-V1 §3): Eve's scalar bytes without the two magic
;; bytes; nil is the single byte 0x00.
;; ---------------------------------------------------------------------------

(def ^:private magic (byte-array [(unchecked-byte 0xEE) (unchecked-byte 0xDB)]))

(defn scalar-body
  "x's Eve scalar bytes without the magic; nil -> [0x00]."
  ^bytes [x]
  (if (nil? x)
    (byte-array 1)
    (let [^bytes bs (codec/value->eve-bytes x)]
      (Arrays/copyOfRange bs 2 (alength bs)))))

(defn- scalar-len
  "The byte length of the Eve scalar body at ba[i] (prototype's table, plus
   the §3 additions 0x00 and 0x28)."
  ^long [^bytes ba ^long i]
  (let [u (fn [j] (bit-and (aget ba (int j)) 0xFF))
        u32 (fn [j] (+ (u j) (* 256 (u (inc j))) (* 65536 (u (+ j 2))) (* 16777216 (u (+ j 3)))))
        t (u i)]
    (case (int t)
      0x00 1
      (0x01 0x02) 1
      0x03 5
      (0x04 0x0E 0x0F) 9
      (0x05 0x07 0x0C) (+ 2 (u (inc i)))
      (0x06 0x08) (+ 5 (u32 (inc i)))
      (0x09 0x0D) (let [nl (u (inc i))] (+ 3 nl (u (+ i 2 nl))))
      0x0A (let [nl (u32 (inc i))] (+ 9 nl (u32 (+ i 5 nl))))
      0x0B 17
      0x14 3
      0x16 (+ 5 (u32 (inc i)))
      0x17 (let [nl (u32 (inc i))] (+ 9 nl (u32 (+ i 5 nl))))
      0x18 (+ 9 (u32 (+ i 5)))
      0x28 (let [[n _] (read-varint ba (inc i))]
             (+ 1 (alength (varint-bytes n)) n))
      (throw (ex-info "scaffold: a scalar tag this reader does not size" {:tag t})))))

(defn scalar-at
  "The plain scalar value whose body sits at ba[i]."
  [^bytes ba ^long i]
  (when-not (zero? (bit-and (aget ba (int i)) 255))
    (let [n (scalar-len ba i), bs (byte-array (+ 2 n))]
      (aset bs 0 (aget ^bytes magic 0))
      (aset bs 1 (aget ^bytes magic 1))
      (System/arraycopy ba (int i) bs 2 (int n))
      (codec/decode nil bs))))

;; ---------------------------------------------------------------------------
;; the symbol table (the prototype's rule): every keyword and symbol, and
;; every string that is a map key or occurs twice in the chunk; codes by
;; descending use, ties by string form.
;; ---------------------------------------------------------------------------

(defn build-symtab
  "xs: every value the chunk will hold (rows or columns, any nesting of plain
   data). Returns {:syms [v ...] :code {v code} :nsym n}."
  [xs]
  (let [counts (HashMap.)]
    (letfn [(scan! [x key?]
              (cond
                (or (keyword? x) (symbol? x) (string? x))
                (let [^longs c (or (.get counts x)
                                   (let [c (long-array 2)] (.put counts x c) c))]
                  (aset c 0 (inc (aget c 0)))
                  (when key? (aset c 1 (inc (aget c 1)))))
                (map? x) (doseq [[k v] x] (scan! k true) (scan! v false))
                (coll? x) (doseq [e x] (scan! e false))
                :else nil))]
      (doseq [x xs] (scan! x false)))
    (let [members (filter (fn [e]
                            (let [k (key e) ^longs c (val e)]
                              (or (keyword? k) (symbol? k)
                                  (>= (aget c 0) 2) (pos? (aget c 1)))))
                          counts)
          syms (->> members
                    (sort-by (fn [e] [(- (aget ^longs (val e) 0)) (str (key e))]))
                    (mapv key))]
      {:syms syms :code (zipmap syms (range)) :nsym (count syms)})))

;; ---------------------------------------------------------------------------
;; the ctx eve.flat.column reads through
;; ---------------------------------------------------------------------------

(defn ctx
  "A read ctx over the assembled chunk bytes ba. symtab from build-symtab;
   shapes: {shape-index [key ...]}; wide?: the chunk's WIDE flag (node
   offsets u32 instead of u16)."
  [^bytes ba {:keys [symtab shapes wide?]}]
  {:bytes ba
   :syms (:syms symtab)
   :sym (fn [code] (nth (:syms symtab) code))
   :code (:code symtab)
   :shape (fn [idx] (get shapes idx))
   :node (fn [off] (scalar-at ba off))
   :wide? (boolean wide?)})
