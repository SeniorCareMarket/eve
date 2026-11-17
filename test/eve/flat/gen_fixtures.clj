(ns eve.flat.gen-fixtures
  "Generates test/eve/fixtures/flat-malformed.edn from the valid chunks
   in eve.flat.chunk-gen. Run once, by hand, when the corpus changes:

     clojure -M:jvm-test -m eve.flat.gen-fixtures

   Each row is {:note .. :check <the named check that must refuse it>
   :bytes \"hex\"} — one row per line, the fixture discipline of
   test/eve/fixtures/eve-bytes.edn."
  (:require [eve.flat.chunk-gen :as g]
            [clojure.java.io :as io])
  (:import [java.util Arrays]))

(defn- raw-chunk
  "Hand-assemble a chunk: header + body written by f starting at offset 32."
  [{:keys [kind root nsym nshape symtab shapetab flags]} f]
  (let [b (g/buf)]
    (dotimes [_ 32] (g/w8 b 0))
    (f b)
    (g/pad-to b 8)
    (let [bs (g/buf-bytes b)]
      (System/arraycopy (.getBytes "EVS1" "US-ASCII") 0 bs 0 4)
      (aset bs 4 (unchecked-byte 1))
      (aset bs 5 (unchecked-byte (or kind 0)))
      (g/patch-u16! bs 6 (or flags 0))
      (g/patch-u32! bs 8 (alength bs))
      (g/patch-u32! bs 12 (or root 0))
      (g/patch-u16! bs 16 (or nsym 0))
      (g/patch-u16! bs 18 (or nshape 0))
      (g/patch-u32! bs 20 (or symtab 0))
      (g/patch-u32! bs 24 (or shapetab 0))
      bs)))

(defn rows []
  (let [vc (g/valid-chunks)
        V (:vec vc)
        D (:digest vc)
        I (:image vc)
        vlen (alength V)
        symtab (g/u32-at V 20)
        shapetab (g/u32-at V 24)
        vroot (g/u32-at V 12)
        entries (+ symtab 8) ;; nsym = 3 -> 4 u16 offsets
        bt (g/u32-at I 20)
        rt (g/u32-at I 24)
        b0 (g/u32-at I bt)
        syms [(g/scalar-bytes :a) (g/scalar-bytes :b)]
        row (fn [note check bs] {:note note :check check :bytes (g/bytes->hex bs)})]
    [;; ---- header
     (row "empty input" :too-short (byte-array 0))
     (row "half a header" :too-short (g/truncate V 16))
     (row "magic zeroed" :bad-magic (g/set-byte V 0 0))
     (row "version 2" :unsupported-version (g/set-byte V 4 2))
     (row "version 0" :unsupported-version (g/set-byte V 4 0))
     (row "kind 9" :unknown-kind (g/set-byte V 5 9))
     (row "required feature bit 8 set" :unknown-required-features
          (g/set-u16 V 6 (bit-or (g/u16-at V 6) 0x100)))
     (row "length field larger than the bytes given" :length-mismatch
          (g/set-u32 V 8 (+ (g/u32-at V 8) 8)))
     (row "bytes after the chunk" :length-mismatch
          (g/append-bytes V [1 2 3]))
     (row "length not a multiple of 8" :bad-length
          (g/truncate (g/set-u32 V 8 36) 36))
     (row "symtab offset past the end" :table-offset-out-of-range
          (g/set-u32 V 20 0xFFF0))
     ;; (No "table offset not 8-aligned" row: §10's alignment clause for
     ;; table offsets conflicts with §2-§5 and C1's golden bytes — the
     ;; checker does not enforce it. See C4-REPORT.md.)
     (row "root past the end" :root-out-of-range
          (g/set-u32 V 12 (+ vlen 64)))
     (row "root inside the header" :root-out-of-range
          (g/set-u32 V 12 8))
     (row "types flag set, no type table" :type-table-missing
          (g/set-u16 V 6 (bit-or (g/u16-at V 6) 4)))
     ;; ---- digest
     (row "digest flag set, content byte flipped" :digest-mismatch
          (g/set-byte D 40 (bit-xor (g/u8-at D 40) 0xFF)))
     ;; ---- tables
     (row "symtab offsets swapped" :table-offsets-not-ascending
          (let [a (g/u16-at V symtab) c (g/u16-at V (+ symtab 2))]
            (g/set-u16 (g/set-u16 V symtab c) (+ symtab 2) a)))
     (row "symtab entry offset past the end" :symtab-out-of-range
          (g/set-u16 V symtab 0xFF00))
     (row "symtab entry length lies" :malformed-scalar
          (g/set-byte V (inc entries) 200))
     (row "shape code past nsym" :shape-code-out-of-range
          (g/set-byte V (+ shapetab 6 3) 7)) ;; shapetab entries at +6 (3 offsets); entry: [n][type][code0][code1]
     (row "shape names a type, chunk has no type table" :shape-type-without-table
          (g/packed-chunk
           {:syms syms :shapes [{:type 3 :codes [0]}]
            :emit (fn [b] (let [s (g/emit-scalar b 1)] (g/emit-map b false 0 [s])))}))
     ;; ---- nodes
     (row "map shape index past nshape" :shape-out-of-range
          (g/set-byte V (inc vroot) 5))
     (row "sym code past nsym" :code-out-of-range
          (g/packed-chunk {:syms syms :shapes [] :emit (fn [b] (g/emit-sym b 9))}))
     (row "reserved tag 0x2D at the root" :reserved-tag
          (g/packed-chunk {:syms [] :shapes [] :emit (fn [b] (g/emit-raw b (byte-array [(unchecked-byte 0x2D)])))}))
     (row "reference tag 0x15 in a packed chunk" :forbidden-tag
          (g/packed-chunk {:syms [] :shapes [] :emit (fn [b] (g/emit-raw b (byte-array [(unchecked-byte 0x15)])))}))
     (row "unassigned tag 0xF0 at the root" :unknown-tag
          (g/packed-chunk {:syms [] :shapes [] :emit (fn [b] (g/emit-raw b (byte-array [(unchecked-byte 0xF0)])))}))
     (row "vec child is itself" :child-not-below-parent
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [o (g/pos b)] (g/w8 b 0x22) (g/wvarint b 1) (g/woff b false o) o))}))
     (row "vec child inside the header" :child-out-of-range
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [o (g/pos b)] (g/w8 b 0x22) (g/wvarint b 1) (g/woff b false 8) o))}))
     (row "vec count of 100000 in a small chunk" :count-exceeds-bytes
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [s (g/emit-scalar b 1) o (g/pos b)]
                            (g/w8 b 0x22) (g/wvarint b 100000) (g/woff b false s) o))}))
     (row "varint runs off the end" :truncated-varint
          (raw-chunk {:root 32}
                     (fn [b] (g/w8 b 0x22) (dotimes [_ 7] (g/w8 b 0x80)))))
     (row "varint of fifteen continuation bytes" :varint-malformed
          (raw-chunk {:root 32}
                     (fn [b] (g/w8 b 0x22) (dotimes [_ 15] (g/w8 b 0x80)))))
     (row "string-long of 100 bytes, 3 present" :node-truncated
          (raw-chunk {:root 32}
                     (fn [b] (g/w8 b 0x06) (g/wu32 b 100) (g/wbytes b (.getBytes "abc" "UTF-8")))))
     ;; ---- columns
     (row "column kind 99" :unknown-column-kind
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [o (g/pos b)]
                            (g/w8 b 0x23) (g/wvarint b 3)
                            (g/wbytes b (byte-array [(unchecked-byte 99) (unchecked-byte 1)
                                                     (unchecked-byte 2) (unchecked-byte 3)]))
                            o))}))
     (row "narrowed column of width 3" :bad-narrowed-width
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [o (g/pos b)]
                            (g/w8 b 0x23) (g/wvarint b 2)
                            (g/wbytes b (g/col-bytes 22 (let [bb (g/buf)]
                                                          (dotimes [_ 8] (g/w8 bb 0))
                                                          (g/w8 bb 3)
                                                          (g/wbytes bb (byte-array [1 2 3 4 5 6]))
                                                          (g/buf-bytes bb))))
                            o))}))
     (row "run ends 3 then 2" :bad-run-ends
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] ;; pad so the values column's data (5 bytes in) is 4-aligned
                          (dotimes [_ (mod (- 3 (g/pos b)) 4)] (g/w8 b 0))
                          (let [o (g/pos b)]
                            (g/w8 b 0x23) (g/wvarint b 5)
                            (g/wbytes b (g/col-bytes 23 (let [bb (g/buf)]
                                                          (g/wvarint bb 2)
                                                          (g/wbytes bb (g/col-bytes 6 (g/dense-data 4 [7 9])))
                                                          (g/wu32 bb 3) (g/wu32 bb 2)
                                                          (g/buf-bytes bb))))
                            o))}))
     (row "utf8 column last offset past the end" :column-out-of-range
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [o (g/pos b)]
                            (g/w8 b 0x23) (g/wvarint b 2)
                            ;; kind 19, one pad byte (offsets are u32 and
                            ;; 4-aligned: data would start at 39), offsets
                            ;; [0 2 5000], then 2 bytes of string data
                            (g/wbytes b (let [bb (g/buf)]
                                          (g/w8 bb 19) (g/w8 bb 0)
                                          (g/wu32 bb 0) (g/wu32 bb 2) (g/wu32 bb 5000)
                                          (g/wbytes bb (.getBytes "ab" "UTF-8"))
                                          (g/buf-bytes bb)))
                            o))}))
     (row "f64 column count exceeds the bytes left" :count-exceeds-bytes
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [o (g/pos b)]
                            ;; declares 2 f64; after the §5 alignment pad
                            ;; only one double's bytes are present
                            (g/w8 b 0x23) (g/wvarint b 2)
                            (g/wbytes b (g/col-bytes 9 (g/dense-data 8 [(Double/doubleToRawLongBits 1.5)])))
                            o))}))
     (row "set of 3, keys vector of 2" :count-mismatch
          (g/packed-chunk
           {:syms [] :shapes []
            :emit (fn [b] (let [s1 (g/emit-scalar b 1)
                                s2 (g/emit-scalar b 2)
                                v (g/emit-vec b false [s1 s2])]
                            (g/emit-set b false 3 v)))}))
     (row "set keys are a map" :bad-keys-target
          (g/packed-chunk
           {:syms syms :shapes [{:type 0 :codes [0]}]
            :emit (fn [b] (let [s (g/emit-scalar b 1)
                                m (g/emit-map b false 0 [s])]
                            (g/emit-set b false 1 m)))}))
     ;; ---- image
     (row "image layout 7" :unsupported-layout (g/set-u16 I 16 7))
     (row "second block overlaps the first" :block-overlap
          (g/set-u32 I (+ bt 8) (+ b0 2)))
     (row "block offsets equal" :blocks-not-ascending
          (g/set-u32 I (+ bt 8) b0))
     (row "block size past the end" :block-out-of-range
          (g/set-u32 I (+ bt 12) 0xFFFFFFF0))
     (row "image root not a block start" :root-not-a-block
          (g/set-u32 I 12 (+ b0 4)))
     (row "relocation owner ordinal past the block table" :relocation-owner-unknown
          (g/set-u32 I (+ rt 4) 36))
     (row "relocation target ordinal past the block table" :relocation-target-unknown
          (g/set-u32 I (+ rt 8) 36))
     (row "relocation position outside its owner" :relocation-out-of-range
          (g/set-u32 I rt (+ b0 100)))]))

(defn -main [& _]
  (let [rs (rows)
        f (io/file "test/eve/fixtures/flat-malformed.edn")]
    (with-open [w (io/writer f)]
      (.write w ";; flat Eve malformed chunks — FORMAT-V1.md §10, IMPL-PLAN.md lane C4.\n")
      (.write w ";; One row per line: {:note .. :check <named check> :bytes \"hex\"}.\n")
      (.write w ";; Generated by eve.flat.gen-fixtures from the valid chunks in\n")
      (.write w ";; eve.flat.chunk-gen; regenerate, do not hand-edit.\n")
      (.write w "[\n")
      (doseq [r rs]
        (.write w (pr-str r))
        (.write w "\n"))
      (.write w "]\n"))
    (println "wrote" (count rs) "rows to" (.getPath f))))
