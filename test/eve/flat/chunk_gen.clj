(ns eve.flat.chunk-gen
  "Test support for lane C4: a small builder that writes FORMAT-V1 chunks
   byte-for-byte as the spec describes them, plus mutation helpers for the
   malformed-fixture corpus. This is test code, not the product writer
   (lane C1 owns src/eve/flat/chunk.cljc). Where FORMAT-V1 left a point
   to the prototype (probes/pack.clj) this builder follows the prototype:
   symbol/shape table offsets are relative to the entries region, in the
   chunk's offset width."
  (:require [eve.codec :as codec]
            [eve.crypto :as crypto] [clojure.string])
  (:import [java.io ByteArrayOutputStream]
           ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
           #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays]))

;; ---------------------------------------------------------------- hex

(defn bytes->hex [^bytes bs]
  (clojure.string/join (map (fn [b] (format "%02x" (bit-and b 255))) bs)))

(defn hex->bytes [^String s]
  (let [n (quot (count s) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte
                   (Integer/parseInt (subs s (* 2 i) (+ (* 2 i) 2)) 16))))
    out))

;; ---------------------------------------------------------------- writer

(defn buf [] {:out (ByteArrayOutputStream.)})

(defn pos [b] (.size ^ByteArrayOutputStream (:out b)))

(defn w8 [b v]
  (.write ^ByteArrayOutputStream (:out b) (int (bit-and v 0xFF)))
  b)

(defn wbytes [b ^bytes bs]
  (.write ^ByteArrayOutputStream (:out b) bs)
  b)

(defn wvarint [b v]
  (loop [v (long v)]
    (if (< v 128)
      (w8 b v)
      (do (w8 b (bit-or (bit-and v 127) 128))
          (recur (unsigned-bit-shift-right v 7))))))

(defn wu16 [b v] (w8 b v) (w8 b (bit-shift-right v 8)))

(defn wu32 [b v]
  (wu16 b v) (wu16 b (bit-shift-right v 16)))

(defn woff [b wide? v] (if wide? (wu32 b v) (wu16 b v)))

(defn buf-bytes [b] (.toByteArray ^ByteArrayOutputStream (:out b)))

(defn pad-to [b alignment]
  (while (not (zero? (rem (pos b) alignment))) (w8 b 0))
  b)

;; patching on a finished byte array

(defn patch-u16! [^bytes bs at v]
  (aset bs (int at) (unchecked-byte (bit-and v 0xFF)))
  (aset bs (int (inc at)) (unchecked-byte (bit-and (bit-shift-right v 8) 0xFF)))
  bs)

(defn patch-u32! [^bytes bs at v]
  (patch-u16! bs at v)
  (patch-u16! bs (+ at 2) (bit-shift-right v 16))
  bs)

(defn patch-off! [^bytes bs at wide? v]
  (if wide? (patch-u32! bs at v) (patch-u16! bs at v)))

;; ---------------------------------------------------------------- scalars

(defn scalar-bytes
  "A scalar in flat form: Eve's scalar bytes (eve.codec) without the two
   magic bytes, per FORMAT-V1 §3. nil is the added 0x00 tag."
  [v]
  (if (nil? v)
    (byte-array [(unchecked-byte 0x00)])
    (let [bs (codec/value->eve-bytes v)]
      (Arrays/copyOfRange bs 2 (alength bs)))))

;; ---------------------------------------------------------------- nodes
;; Each emitter appends to b and returns the node's chunk offset.
;; Children are emitted by the caller before their parents (FORMAT-V1 §4).

(defn emit-raw [b ^bytes bs]
  (let [o (pos b)] (wbytes b bs) o))

(defn emit-scalar [b v] (emit-raw b (scalar-bytes v)))

(defn emit-sym [b code]
  (let [o (pos b)] (w8 b 0x20) (wvarint b code) o))

(defn- emit-offsets-node [b tag head-fn wide? offsets]
  (let [o (pos b)]
    (w8 b tag)
    (head-fn b)
    (doseq [off offsets] (woff b wide? off))
    o))

(defn emit-vec [b wide? child-offsets]
  (emit-offsets-node b 0x22 (fn [b] (wvarint b (count child-offsets))) wide? child-offsets))

(defn emit-list [b wide? child-offsets]
  (emit-offsets-node b 0x25 (fn [b] (wvarint b (count child-offsets))) wide? child-offsets))

(defn emit-map [b wide? shape-idx value-offsets]
  (emit-offsets-node b 0x21 (fn [b] (wvarint b shape-idx)) wide? value-offsets))

(defn emit-set [b wide? n keys-offset]
  (emit-offsets-node b 0x24 (fn [b] (wvarint b n)) wide? [keys-offset]))

(defn emit-kmap [b wide? n keys-offset values-offset]
  (emit-offsets-node b 0x27 (fn [b] (wvarint b n)) wide? [keys-offset values-offset]))

(defn emit-batch [b wide? shape-idx rows column-offsets]
  (let [o (pos b)]
    (w8 b 0x26) (wvarint b shape-idx) (wvarint b rows)
    (doseq [off column-offsets] (woff b wide? off))
    o))

(defn emit-meta [b wide? value-offset meta-offset]
  (emit-offsets-node b 0x29 (fn [_]) wide? [value-offset meta-offset]))

(defn emit-tagged [b wide? code value-offset]
  (let [o (pos b)]
    (w8 b 0x2A) (wvarint b code) (woff b wide? value-offset)
    o))

(defn emit-ref [b chunk-idx offset32]
  (let [o (pos b)]
    (w8 b 0x2C) (wvarint b chunk-idx) (wu32 b offset32)
    o))

;; ---------------------------------------------------------------- columns
;; A column is raw bytes: [kind u8][validity bitmap if kind has 0x80][data].
;; These helpers return byte arrays; emit them with emit-raw.

(defn col-bytes [kind data-bytes & {:keys [validity]}]
  (let [b (buf)]
    (w8 b (bit-or kind (if validity 0x80 0)))
    (when validity (wbytes b validity))
    (wbytes b data-bytes)
    (buf-bytes b)))

(defn dense-data
  "Little-endian dense numeric data for kinds 1-11."
  [elem-size values]
  (let [b (buf)]
    (doseq [v values]
      (loop [i 0 x (long v)]
        (when (< i elem-size)
          (w8 b x)
          (recur (inc i) (bit-shift-right x 8)))))
    (buf-bytes b)))

(defn align-column-data
  "Pad b with zeros so that a column kind byte written now is followed by
   data aligned to align from the chunk's start (FORMAT-V1 §5)."
  [b align]
  (when (> align 1)
    (dotimes [_ (mod (- (inc (pos b))) align)] (w8 b 0)))
  b)

(defn codes1-data [codes] (byte-array (map unchecked-byte codes)))

(defn codes2-data [codes]
  (let [b (buf)] (doseq [c codes] (wu16 b c)) (buf-bytes b)))

(defn utf8-data
  "kind 19: n+1 offsets (chunk offset width) relative to the bytes region,
   then the bytes."
  [wide? strings]
  (let [b (buf)
        enc (mapv (fn [^String s] (.getBytes s "UTF-8")) strings)
        offs (vec (reductions (fn [a ^bytes e] (+ a (alength e))) 0 enc))]
    (doseq [o offs] (woff b wide? o))
    (doseq [^bytes e enc] (wbytes b e))
    (buf-bytes b)))

;; ---------------------------------------------------------------- tables

(defn- write-shape [b {:keys [type codes]}]
  (wvarint b (count codes))
  (wvarint b (or type 0))
  (doseq [c codes] (wvarint b c)))

(defn- table-bytes
  "nsym/nshape + 1 offsets (chunk width, relative to the entries region),
   then the entries written by write-entry."
  [wide? entries write-entry]
  (let [b (buf)
        w (if wide? 4 2)]
    (dotimes [_ (* (inc (count entries)) w)] (w8 b 0))
    (let [offs (mapv (fn [e] (let [o (pos b)] (write-entry b e) o)) entries)
          end (pos b)
          bs (buf-bytes b)
          entries-start (* (inc (count entries)) w)]
      (doseq [[i o] (map-indexed vector (conj offs end))]
        (patch-off! bs (* i w) wide? (- o entries-start)))
      bs)))

;; ---------------------------------------------------------------- chunks

(defn- finish-chunk
  "bs is header placeholder + body. Patches the header per FORMAT-V1 §2,
   pads to a multiple of 8, appends the digest when asked."
  [^bytes bs {:keys [kind wide? digest? types? root nsym nshape symtab shapetab typetab]}]
  (let [flags (bit-or (if wide? 1 0) (if digest? 2 0) (if types? 4 0))
        total (+ (alength bs) (if digest? 32 0))
        pad (rem (- 8 (rem total 8)) 8)
        total (+ total pad)
        out (Arrays/copyOf bs (+ (alength bs) pad))]
    (System/arraycopy (.getBytes "EVS1" "US-ASCII") 0 out 0 4)
    (aset out 4 (unchecked-byte 1))                 ;; version
    (aset out 5 (unchecked-byte kind))
    (patch-u16! out 6 flags)
    (patch-u32! out 8 total)                        ;; length
    (patch-u32! out 12 root)
    (patch-u16! out 16 nsym)
    (patch-u16! out 18 nshape)
    (patch-u32! out 20 symtab)
    (patch-u32! out 24 shapetab)
    (patch-u32! out 28 typetab)
    (if digest?
      (let [tmp (Arrays/copyOf out total)         ;; body+pad extended with 32 zero bytes
            dig (crypto/sha256 (mapv (fn [x] (bit-and x 0xFF)) tmp))]
        (doseq [[i d] (map-indexed vector dig)]
          (aset tmp (+ (- total 32) i) (unchecked-byte d)))
        tmp)
      out)))

(defn packed-chunk
  "Build a kind-0 (packed) chunk. syms: vector of flat scalar byte arrays.
   shapes: vector of {:type type-index-plus-1 :codes [sym codes]}.
   emit: (fn [buf] root-offset) — emits nodes children-first.
   opts: :wide? :digest? :typetab? (a dummy 8-byte type-table region)."
  [{:keys [syms shapes emit wide? digest? typetab?]}]
  (let [b (buf)]
    (dotimes [_ 32] (w8 b 0))
    (let [symtab (pos b)]
      (wbytes b (table-bytes wide? (or syms []) (fn [bb s] (wbytes bb s))))
      ;; Tables are contiguous, as in C1's golden bytes (§2-§5); §10's
      ;; 8-alignment clause for table offsets is not enforced (C4 report).
      (let [shapetab (pos b)]
        (wbytes b (table-bytes wide? (or shapes []) write-shape))
        (let [typetab (if typetab? (do (dotimes [_ 8] (w8 b 0)) (- (pos b) 8)) 0)
              root (emit b)]
          (finish-chunk (buf-bytes b)
                        {:kind 0 :wide? wide? :digest? digest? :types? typetab?
                         :root root :nsym (count syms) :nshape (count shapes)
                         :symtab symtab :shapetab shapetab :typetab typetab}))))))

(defn image-chunk
  "Build a kind-1 (image) chunk, layout 1. blocks: vector of
   {:bytes byte-array :slots [{:at byte-offset-in-block :target block-index}]}.
   Pointer slots are patched to the target block's chunk offset (u32 LE).
   Blocks are 4-aligned; block 0 is the root."
  [{:keys [blocks digest?]}]
  (let [b (buf)]
    (dotimes [_ 32] (w8 b 0))
    (let [offs (mapv (fn [blk]
                       (pad-to b 4)
                       (let [o (pos b)] (wbytes b (:bytes blk)) o))
                     blocks)
          blocktab (do (pad-to b 8) (pos b))]
      (doseq [[i blk] (map-indexed vector blocks)]
        (wu32 b (nth offs i)) (wu32 b (alength ^bytes (:bytes blk))))
      (let [reloctab (pos b)]
        (doseq [[i blk] (map-indexed vector blocks)]
          (doseq [{:keys [at target]} (:slots blk)]
            (wu32 b (+ (nth offs i) at)) ;; field position, absolute
            (wu32 b i)                   ;; owner: ordinal into the block table
            (wu32 b target)))            ;; target: ordinal (Z1's reading)
        (let [bs (buf-bytes b)]
          ;; patch pointer slots with target block offsets
          (doseq [[i blk] (map-indexed vector blocks)]
            (doseq [{:keys [at target]} (:slots blk)]
              (patch-u32! bs (+ (nth offs i) at) (nth offs target))))
          (finish-chunk bs {:kind 1 :root (nth offs 0) :nsym 1 :nshape 0
                            :symtab blocktab :shapetab reloctab :typetab 0
                            :digest? digest?}))))))

;; ---------------------------------------------------------------- readers

(defn u8-at [^bytes bs i] (bit-and (aget bs (int i)) 0xFF))

(defn u16-at [^bytes bs i] (bit-or (u8-at bs i) (bit-shift-left (u8-at bs (inc i)) 8)))

(defn u32-at [^bytes bs i]
  (bit-or (u16-at bs i) (bit-shift-left (u16-at bs (+ i 2)) 16)))

;; ---------------------------------------------------------------- valid chunks

(defn valid-chunks
  "A map of named, spec-conformant chunks used by the fixture generator,
   the check tests and the fuzzer."
  []
  (let [syms [(scalar-bytes :a) (scalar-bytes :b) (scalar-bytes :name)]
        shapes [{:type 0 :codes [0 1]} {:type 0 :codes [2]}]
        vec-chunk
        (packed-chunk
         {:syms syms :shapes shapes
          :emit (fn [b]
                  (let [s1 (emit-scalar b 1)
                        s2 (emit-scalar b 2)
                        v (emit-vec b false [s1 s2])]
                    (emit-map b false 0 [v s1])))})
        batch-chunk
        (packed-chunk
         {:syms [(scalar-bytes :x) (scalar-bytes :y)]
          :shapes [{:type 0 :codes [0 1]}]
          :emit (fn [b]
                  (let [c1 (do (align-column-data b 8)
                               (emit-raw b (col-bytes 9 (dense-data 8 [(Double/doubleToRawLongBits 1.0)
                                                                       (Double/doubleToRawLongBits 2.0)
                                                                       (Double/doubleToRawLongBits 3.0)]))))
                        c2 (emit-raw b (col-bytes 17 (codes1-data [0 1 0])))]
                    (emit-batch b false 0 3 [c1 c2])))})
        col-chunk
        (packed-chunk
         {:syms [] :shapes []
          :emit (fn [b]
                  ;; §4 reads [tag][count][column] contiguously, so §5's
                  ;; data alignment is a placement property: pad BEFORE
                  ;; the node so the data (3 bytes in: tag, count, kind)
                  ;; lands 4-aligned.
                  (dotimes [_ (mod (- (+ (pos b) 3)) 4)] (w8 b 0))
                  (let [o (pos b)]
                    (w8 b 0x23) (wvarint b 4)
                    (wbytes b (col-bytes 6 (dense-data 4 [10 20 30 40])))
                    o))})
        digest-chunk
        (packed-chunk
         {:syms syms :shapes shapes :digest? true
          :emit (fn [b]
                  (let [s1 (emit-scalar b 7)]
                    (emit-vec b false [s1 s1])))})
        wide-chunk
        (packed-chunk
         {:syms syms :shapes shapes :wide? true
          :emit (fn [b]
                  (let [s1 (emit-scalar b 1)
                        s2 (emit-scalar b 2)]
                    (emit-vec b true [s1 s2 s1])))})
        image
        (image-chunk
         {:blocks [{:bytes (byte-array 8) :slots [{:at 0 :target 1}]}
                   {:bytes (byte-array [(unchecked-byte 0x03) (unchecked-byte 5)
                                        (unchecked-byte 0) (unchecked-byte 0)])}]})]
    {:vec vec-chunk :batch batch-chunk :col col-chunk
     :digest digest-chunk :wide wide-chunk :image image}))

;; ---------------------------------------------------------------- mutations

(defn set-byte [^bytes bs i v]
  (let [out (Arrays/copyOf bs (alength bs))]
    (aset out (int i) (unchecked-byte v))
    out))

(defn set-u16 [^bytes bs i v] (patch-u16! (Arrays/copyOf bs (alength bs)) i v))

(defn set-u32 [^bytes bs i v] (patch-u32! (Arrays/copyOf bs (alength bs)) i v))

(defn truncate [^bytes bs n] (Arrays/copyOf bs (int n)))

(defn append-bytes [^bytes bs extra]
  (let [out (Arrays/copyOf bs (+ (alength bs) (count extra)))]
    (doseq [[i v] (map-indexed vector extra)]
      (aset out (+ (alength bs) i) (unchecked-byte v)))
    out))
