(ns eve.flat.check
  "Bytes nobody vouches for: the checked door of FORMAT-V1.md §10.

   (open-checked bs) makes one pass over a chunk's bytes before any
   protocol operation sees them, and answers a CheckedBytes handle.
   Anything wrong is a typed refusal — an ex-info whose ex-data carries
   :check, the name of the check that fired — never a host Error, never
   a partial value. (place and transfer!, lanes Z3/T1, accept only what
   require-checked answers: a CheckedBytes handle, not raw bytes.)

   The checks, in the order they fire:

   header   :too-short :bad-magic :unsupported-version :unknown-kind
            :unknown-required-features :length-mismatch :bad-length
            :digest-mismatch :table-offset-out-of-range
            :type-table-missing
            :root-out-of-range :not-bytes
   tables   :table-truncated :symtab-out-of-range
            :table-offsets-not-ascending :malformed-scalar
            :shape-code-out-of-range :shape-type-without-table
            :malformed-shape
   nodes    :unknown-tag :reserved-tag :forbidden-tag
            :truncated-varint :varint-malformed :node-truncated
            :shape-out-of-range :code-out-of-range :child-out-of-range
            :child-not-below-parent :count-exceeds-bytes
            :nesting-too-deep :count-mismatch :bad-keys-target
            :bad-struct-column :unknown-column-kind
            :column-out-of-range :column-offsets-not-ascending
            :bad-narrowed-width :bad-run-ends
   image    :unsupported-layout :block-out-of-range
            :blocks-not-ascending :block-overlap :root-not-a-block
            :relocation-owner-unknown :relocation-target-unknown
            :relocation-out-of-range
   contract :unchecked-bytes

   Counts are compared by division, never by multiplication, and a
   count that decodes past 2^63 (a negative long here) is refused by
   the same guard: a lying count is refused before any element is read,
   however the lie is dressed.

   Readings this lane had to take: the nesting limit defaults to 64
   (IMPL-PLAN C4.2; FORMAT-V1 §10 says 512 — pass {:max-depth n} to
   override; flagged in doc/flat/C4-STATUS.md). An image's relocation
   owner/target are ordinals into the block table and its field
   position is absolute in the buffer — the reading Z1's producer
   (eve.flat.image) writes, confirmed against its code."
  (:require [eve.crypto :as crypto]))

;; ------------------------------------------------------------------ refusals

(defn- refuse
  "Throw the typed refusal every check answers with."
  [check msg & {:as data}]
  (throw (ex-info (str "flat/open-checked refused (" (name check) "): " msg)
                  (assoc data :check check))))

;; ------------------------------------------------------------------ the handle

(deftype CheckedBytes [buf len kind root])

(defn checked?
  "Is x a handle open-checked vouched for?"
  [x]
  (instance? CheckedBytes x))

(defn checked-bytes
  "The bytes of a checked handle, as given to open-checked."
  [c]
  (.-buf ^CheckedBytes c))

(defn require-checked
  "The gate place and transfer! stand behind: the bytes of a checked
   handle, or a typed refusal for anything else — raw bytes included,
   however valid they look."
  [x]
  (if (checked? x)
    (.-buf ^CheckedBytes x)
    (refuse :unchecked-bytes
            "only bytes vouched for by eve.flat.check/open-checked are accepted"
            :given (str (type x)))))

;; ------------------------------------------------------------------ byte reading

(defn- byte-len [bs]
  #?(:clj (alength ^bytes bs) :cljs (.-length bs)))

(defn- u8 [bs i]
  #?(:clj (bit-and (aget ^bytes bs (int i)) 0xFF)
     :default (bit-and (aget bs (int i)) 0xFF)))

(defn- u16 [bs i]
  (bit-or (u8 bs i) (bit-shift-left (u8 bs (inc i)) 8)))

(defn- u32 [bs i]
  (bit-or (u16 bs i) (bit-shift-left (u16 bs (+ i 2)) 16)))

(defn- rd-off
  "An offset of the chunk's width at i."
  [ctx i]
  (if (:wide? ctx) (u32 (:bs ctx) i) (u16 (:bs ctx) i)))

(defn- rd-varint
  "Unsigned LEB128 at pos, nothing read at or past limit. Answers
   [value new-pos]. A value past 2^63 comes back as a negative long;
   every count consumer guards for that (see count-guard)."
  [ctx pos limit]
  (loop [pos pos shift 0 acc 0 n 0]
    (when (>= pos limit)
      (refuse :truncated-varint "varint runs past the bytes left"
              :offset pos :limit limit))
    (when (>= n 10)
      (refuse :varint-malformed "varint longer than ten bytes"
              :offset pos))
    (let [b (u8 (:bs ctx) pos)
          acc (bit-or acc (bit-shift-left (bit-and b 0x7F) shift))]
      (when (and (= n 9) (> (bit-and b 0x7F) 1))
        (refuse :varint-malformed "varint overflows 64 bits" :offset pos))
      (if (zero? (bit-and b 0x80))
        [acc (inc pos)]
        (recur (inc pos) (+ shift 7) acc (inc n))))))

(defn- need
  "Refuse with check when pos+n passes limit."
  [_ctx pos n limit check what]
  (when (> (+ pos n) limit)
    (refuse check (str what " runs past the bytes left")
            :offset pos :needed n :limit limit)))

(defn- count-guard
  "C4.2 as FORMAT-V1 §10 states it: a count times its smallest element
   never exceeds the bytes left. Compared by division so the count
   itself cannot overflow the comparison; a count past 2^63 (negative
   here) is a lie of the same kind."
  [ctx pos count min-elem what]
  (when (or (neg? count)
            (> count (quot (- (:end ctx) pos) min-elem)))
    (refuse :count-exceeds-bytes
            (str what " count " count " cannot fit in " (- (:end ctx) pos) " bytes")
            :offset pos :count count)))

;; ------------------------------------------------------------------ scalars

(def ^:private scalar-min-size
  "tag -> smallest total size, tag included (FORMAT-V1 §3 over
   eve.deftype-proto.serialize's layouts)."
  {0x00 1, 0x01 1, 0x02 1, 0x03 5, 0x04 9, 0x05 2, 0x06 5, 0x07 2,
   0x08 5, 0x09 3, 0x0A 9, 0x0B 17, 0x0C 2, 0x0D 3, 0x0E 9, 0x0F 9,
   0x14 3, 0x16 5, 0x17 9, 0x18 9, 0x28 2})

(defn- parse-scalar
  "Validate the scalar at pos, nothing read at or past limit; trunc is
   the refusal for an overrun. Answers the offset just past it."
  [ctx pos limit trunc]
  (let [bs (:bs ctx)
        tag (u8 bs pos)]
    (case tag
      (0x00 0x01 0x02) (do (need ctx pos 1 limit trunc "scalar") (inc pos))
      0x03 (do (need ctx pos 5 limit trunc "int32") (+ pos 5))
      (0x04 0x0E 0x0F) (do (need ctx pos 9 limit trunc "64-bit scalar") (+ pos 9))
      0x0B (do (need ctx pos 17 limit trunc "uuid") (+ pos 17))
      0x14 (do (need ctx pos 3 limit trunc "char") (+ pos 3))
      (0x05 0x07 0x0C)
      (do (need ctx pos 2 limit trunc "short string header")
          (let [end (+ pos 2 (u8 bs (inc pos)))]
            (need ctx end 0 limit trunc "short string")
            end))
      (0x06 0x08)
      (do (need ctx pos 5 limit trunc "long string header")
          (let [end (+ pos 5 (u32 bs (inc pos)))]
            (need ctx end 0 limit trunc "long string")
            end))
      (0x09 0x0D)
      (do (need ctx pos 2 limit trunc "namespaced name header")
          (let [mid (+ pos 2 (u8 bs (inc pos)))]
            (need ctx mid 1 limit trunc "namespaced name")
            (let [end (inc (+ mid (u8 bs mid)))]
              (need ctx end 0 limit trunc "namespaced name")
              end)))
      0x0A
      (do (need ctx pos 5 limit trunc "namespaced name header")
          (let [mid (+ pos 5 (u32 bs (inc pos)))]
            (need ctx mid 4 limit trunc "namespaced name")
            (let [end (+ mid 4 (u32 bs mid))]
              (need ctx end 0 limit trunc "namespaced name")
              end)))
      0x16
      (do (need ctx pos 5 limit trunc "bigint header")
          (let [end (+ pos 5 (u32 bs (inc pos)))]
            (need ctx end 0 limit trunc "bigint")
            end))
      0x17
      (do (need ctx pos 5 limit trunc "ratio header")
          (let [mid (+ pos 5 (u32 bs (inc pos)))]
            (need ctx mid 4 limit trunc "ratio")
            (let [end (+ mid 4 (u32 bs mid))]
              (need ctx end 0 limit trunc "ratio")
              end)))
      0x18
      (do (need ctx pos 9 limit trunc "bigdec header")
          (let [end (+ pos 9 (u32 bs (+ pos 5)))]
            (need ctx end 0 limit trunc "bigdec")
            end))
      0x28
      (let [[l q] (rd-varint ctx (inc pos) limit)
            end (+ q l)]
        (when (neg? l)
          (refuse :varint-malformed "bytes length overflows 64 bits" :offset pos))
        (need ctx end 0 limit trunc "bytes")
        end)
      (refuse :unknown-tag (str "tag " tag " is not a scalar")
              :offset pos :tag tag))))

;; ------------------------------------------------------------------ columns

(def ^:private dense-sizes
  {1 1, 2 1, 3 1, 4 2, 5 2, 6 4, 7 4, 8 4, 9 8, 10 8, 11 8})

(def ^:private column-kinds
  (into (set (keys dense-sizes)) [16 17 18 19 20 21 22 23]))

(declare walk walk-node)

(defn- column-check
  "Validate the column whose kind byte is at pos, holding n rows.
   bound is the offset referenced element nodes must stay below (the
   owning node's offset, or the column's own offset inside a batch);
   depth is the owning node's depth, for the element nodes' depth.
   Answers {:end offset-past-the-column :height tallest-child-height}."
  [ctx pos n bound depth]
  (when (neg? n)
    (refuse :count-exceeds-bytes (str "column count " n " is not a count")
            :offset pos :count n))
  (let [bs (:bs ctx)
        end (:end ctx)
        kind (u8 bs pos)
        base (bit-and kind 0x7F)
        validity? (not (zero? (bit-and kind 0x80)))]
    (when-not (contains? column-kinds base)
      (refuse :unknown-column-kind (str "column kind " base)
              :offset pos :kind base))
    (let [data-pos (+ pos 1 (if validity? (quot (+ n 7) 8) 0))]
      (need ctx data-pos 0 end :column-out-of-range "column header")
      ;; the count guard (C4.2): a count times its smallest element
      ;; never exceeds the bytes left
      (let [min-row (case base
                      (1 2 3) 1, (4 5) 2, (6 7 8) 4, (9 10 11) 8,
                      16 0, 17 1, 18 2, 19 4, 20 (:offw ctx),
                      21 0, 22 1, 23 0)]
        (when (pos? min-row)
          (count-guard ctx data-pos n min-row "column")))
      (case base
        (1 2 3 4 5 6 7 8 9 10 11)
        (let [es (dense-sizes base)
              ;; §5: the data is aligned to the element size from the
              ;; chunk's start — the writer pads between the kind byte
              ;; (and any validity bitmap) and the data, as C1's golden
              ;; bytes show (kind at 38, pad at 39, data at 40). The pad
              ;; bytes are skipped, never read.
              data-pos (if (> es 1) (* es (quot (+ data-pos (dec es)) es)) data-pos)
              data-end (+ data-pos (* n es))]
          (need ctx data-end 0 end :column-out-of-range "dense column")
          {:end data-end :height 0})

        16 {:end (+ data-pos (quot (+ n 7) 8)) :height 0}

        (17 18)
        (let [w (if (= base 17) 1 2)
              data-end (+ data-pos (* n w))]
          (need ctx data-end 0 end :column-out-of-range "codes column")
          (doseq [i (range n)]
            (let [c (if (= w 1) (u8 bs (+ data-pos i)) (u16 bs (+ data-pos (* 2 i))))]
              (when (>= c (:nsym ctx))
                (refuse :code-out-of-range
                        (str "column code " c " with " (:nsym ctx) " symbols")
                        :offset (+ data-pos (* w i)) :code c))))
          {:end data-end :height 0})

        19
        ;; utf-8 offsets are u32 always, and like dense data they start
        ;; at their aligned position (the writer pads after the kind
        ;; byte): C1's writer emits kind at 38, a pad byte at 39, offsets
        ;; [0 1 3 6] at 40 in a non-wide chunk, and its reader reads the
        ;; same (round-trip verified). §5 does not give these offsets the
        ;; chunk's offset width.
        (let [data-pos (* 4 (quot (+ data-pos 3) 4))
              offs-end (+ data-pos (* (inc n) 4))]
          (need ctx offs-end 0 end :column-out-of-range "utf-8 offsets")
          (let [offs (mapv (fn [i] (u32 bs (+ data-pos (* 4 i)))) (range (inc n)))]
            (doseq [[a b] (partition 2 1 offs)]
              (when (< b a)
                (refuse :column-offsets-not-ascending "utf-8 offsets are not ascending"
                        :offset pos)))
            (let [data-end (+ offs-end (peek offs))]
              (need ctx data-end 0 end :column-out-of-range "utf-8 bytes")
              {:end data-end :height 0})))

        20
        (let [w (:offw ctx)
              data-end (+ data-pos (* n w))]
          (need ctx data-end 0 end :column-out-of-range "any column")
          (loop [i 0 h 0]
            (if (< i n)
              (let [child (rd-off ctx (+ data-pos (* w i)))]
                (when (or (< child 32) (>= child end))
                  (refuse :child-out-of-range (str "column element at " child)
                          :offset (+ data-pos (* w i)) :child child))
                (when (>= child bound)
                  (refuse :child-not-below-parent
                          (str "column element at " child " is not below " bound)
                          :offset (+ data-pos (* w i)) :child child))
                (recur (inc i) (max h (first (walk ctx child (inc depth))))))
              {:end data-end :height h})))

        21
        (do (when (or (>= data-pos end) (not= (u8 bs data-pos) 0x26))
              (refuse :bad-struct-column "a struct column's value is not a batch"
                      :offset pos))
            (let [[h e] (walk ctx data-pos (inc depth) n)]
              {:end e :height h}))

        22
        (do (need ctx data-pos 9 end :column-out-of-range "narrowed column header")
            (let [w (u8 bs (+ data-pos 8))]
              (when-not (contains? #{1 2 4} w)
                (refuse :bad-narrowed-width (str "narrowed width " w)
                        :offset (+ data-pos 8) :width w))
              (let [data-end (+ data-pos 9 (* n w))]
                (need ctx data-end 0 end :column-out-of-range "narrowed column")
                {:end data-end :height 0})))

        23
        (let [[runs q] (rd-varint ctx data-pos end)
              inner (column-check ctx q runs bound depth)
              ends-pos (:end inner)]
          (need ctx ends-pos (* 4 runs) end :column-out-of-range "run ends")
          (let [ends (mapv (fn [i] (u32 bs (+ ends-pos (* 4 i)))) (range runs))]
            (when (or (and (pos? n) (zero? runs))
                      (and (zero? n) (pos? runs))
                      (some (fn [[a b]] (>= a b)) (partition 2 1 ends))
                      (and (pos? runs) (not= (peek ends) n))
                      (some (fn [e] (> e n)) ends))
              (refuse :bad-run-ends (str "run ends " ends " for " n " rows")
                      :offset pos :ends ends :rows n))
            {:end (+ ends-pos (* 4 runs)) :height (:height inner)}))))))

;; ------------------------------------------------------------------ nodes

(def ^:private node-tags
  #{0x20 0x21 0x22 0x23 0x24 0x25 0x26 0x27 0x29 0x2A 0x2C})

(def ^:private forbidden-tags
  ;; a reference and the slab pointer tags never occur in a packed chunk
  ;; (FORMAT-V1 §3); 0x1A/0x1B are slab-only forms; 0x40-0xEF are slab
  ;; type ids and the flat-collection tags.
  (into #{0x10 0x11 0x12 0x13 0x15 0x19 0x1A 0x1B 0x1C 0x1D 0x1E 0x1F}
        (range 0x40 0xF0)))

(defn- edge
  "A child reference from the node at parent: in range, and below it —
   children are written before their parents (FORMAT-V1 §4)."
  [ctx parent child]
  (when (or (< child 32) (>= child (:end ctx)))
    (refuse :child-out-of-range (str "child offset " child)
            :offset parent :child child))
  (when (>= child parent)
    (refuse :child-not-below-parent
            (str "child offset " child " is not below its parent at " parent)
            :offset parent :child child)))

(defn- expect-count
  "The keys/values target of a SET or KMAP must be a VEC or COL node of
   exactly n elements (FORMAT-V1 §4)."
  [ctx target n]
  (let [tag (u8 (:bs ctx) target)]
    (when-not (contains? #{0x22 0x23} tag)
      (refuse :bad-keys-target (str "keys target tag " tag " is not a vector or column")
              :offset target :tag tag))
    (let [[actual _] (rd-varint ctx (inc target) (:end ctx))]
      (when (not= actual n)
        (refuse :count-mismatch (str "keys hold " actual ", the node declares " n)
                :offset target :declared n :actual actual)))))

(defn- walk
  "Validate the node at offset; depth is its nesting depth from the
   root (the root is 1). The fourth argument, when given, is the row
   count a batch must declare (a struct column's batch). Answers
   [height offset-past-the-node]. Shared subtrees are walked once:
   heights are properties of the subtree, memoized on the context,
   and a memoized subtree reached too deep is refused on the spot."
  ([ctx offset depth] (walk ctx offset depth nil))
  ([ctx offset depth expect-rows]
   (let [bs (:bs ctx)
         end (:end ctx)
         tag (u8 bs offset)]
     (cond
       (contains? scalar-min-size tag)
       [0 (parse-scalar ctx offset end :node-truncated)]

       (contains? node-tags tag)
       (if-let [he (get @(:heights ctx) offset)]
         (do (when (> (+ depth (first he) -1) (:max-depth ctx))
               (refuse :nesting-too-deep
                       (str "nesting past " (:max-depth ctx) " at offset " offset)
                       :offset offset :depth depth))
             he)
         (do (when (> depth (:max-depth ctx))
               (refuse :nesting-too-deep
                       (str "nesting past " (:max-depth ctx) " at offset " offset)
                       :offset offset :depth depth))
             (let [he (walk-node ctx offset depth tag expect-rows)]
               (swap! (:heights ctx) assoc offset he)
               he)))

       (<= 0x2D tag 0x3F)
       (refuse :reserved-tag (str "tag " tag " is reserved")
               :offset offset :tag tag)

       (contains? forbidden-tags tag)
       (refuse :forbidden-tag (str "tag " tag " never occurs in a packed chunk")
               :offset offset :tag tag)

       :else
       (refuse :unknown-tag (str "tag " tag)
               :offset offset :tag tag)))))

(defn- child-height
  [ctx parent child depth]
  (edge ctx parent child)
  (first (walk ctx child (inc depth))))

(defn- walk-offsets
  "Validate n child offsets at pos (the count guard has already run).
   Answers [end-pos tallest-child-height]."
  [ctx parent pos n depth]
  (let [w (:offw ctx)]
    (loop [i 0 h 0]
      (if (< i n)
        (recur (inc i)
               (max h (child-height ctx parent (rd-off ctx (+ pos (* w i))) depth)))
        [(+ pos (* w n)) h]))))

(defn- walk-node
  "The node cases of walk. Answers [height offset-past-the-node]."
  [ctx offset depth tag expect-rows]
  (let [_bs (:bs ctx)
        end (:end ctx)
        w (:offw ctx)]
    (case tag
      0x20 ;; SYM
      (let [[code q] (rd-varint ctx (inc offset) end)]
        (when (or (neg? code) (>= code (:nsym ctx)))
          (refuse :code-out-of-range
                  (str "symbol code " code " with " (:nsym ctx) " symbols")
                  :offset offset :code code))
        [1 q])

      (0x21 0x22 0x25) ;; MAP, VEC, LIST
      (let [[v q] (rd-varint ctx (inc offset) end)
            n (if (= tag 0x21)
                (do (when (or (neg? v) (>= v (:nshape ctx)))
                      (refuse :shape-out-of-range
                              (str "shape " v " with " (:nshape ctx) " shapes")
                              :offset offset :shape v))
                    (:n (nth (:shapes ctx) v)))
                v)]
        (count-guard ctx q n w "element offsets")
        (let [[e h] (walk-offsets ctx offset q n depth)]
          [(inc h) e]))

      0x23 ;; COL
      (let [[n q] (rd-varint ctx (inc offset) end)
            r (column-check ctx q n offset depth)]
        [(inc (:height r)) (:end r)])

      0x24 ;; SET
      (let [[n q] (rd-varint ctx (inc offset) end)]
        (need ctx q w end :node-truncated "set keys offset")
        (let [keys (rd-off ctx q)]
          (edge ctx offset keys)
          (expect-count ctx keys n)
          [(inc (first (walk ctx keys (inc depth)))) (+ q w)]))

      0x26 ;; BATCH
      (let [[shape q] (rd-varint ctx (inc offset) end)]
        (when (or (neg? shape) (>= shape (:nshape ctx)))
          (refuse :shape-out-of-range
                  (str "shape " shape " with " (:nshape ctx) " shapes")
                  :offset offset :shape shape))
        (let [[rows q2] (rd-varint ctx q end)
              fields (:n (nth (:shapes ctx) shape))]
          (when (and expect-rows (not= rows expect-rows))
            (refuse :count-mismatch
                    (str "struct batch holds " rows " rows, the column declares " expect-rows)
                    :offset offset :declared expect-rows :actual rows))
          (count-guard ctx q2 fields w "column offsets")
          (loop [i 0 h 0]
            (if (< i fields)
              (let [c (rd-off ctx (+ q2 (* w i)))]
                (edge ctx offset c)
                (let [r (column-check ctx c rows c depth)]
                  (when (> (:end r) offset)
                    (refuse :column-out-of-range
                            "a batch column's bytes run into the batch"
                            :offset c))
                  (recur (inc i) (max h (:height r)))))
              [(inc h) (+ q2 (* w fields))]))))

      0x27 ;; KMAP
      (let [[n q] (rd-varint ctx (inc offset) end)]
        (need ctx q (* 2 w) end :node-truncated "kmap offsets")
        (let [ks (rd-off ctx q)
              vs (rd-off ctx (+ q w))]
          (edge ctx offset ks)
          (edge ctx offset vs)
          (expect-count ctx ks n)
          (expect-count ctx vs n)
          [(inc (max (first (walk ctx ks (inc depth)))
                     (first (walk ctx vs (inc depth)))))
           (+ q (* 2 w))]))

      0x29 ;; META
      (do (need ctx (inc offset) (* 2 w) end :node-truncated "meta offsets")
          (let [v (rd-off ctx (inc offset))
                m (rd-off ctx (+ (inc offset) w))]
            [(inc (max (child-height ctx offset v depth)
                       (child-height ctx offset m depth)))
             (+ (inc offset) (* 2 w))]))

      0x2A ;; TAGGED
      (let [[code q] (rd-varint ctx (inc offset) end)]
        (when (or (neg? code) (>= code (:nsym ctx)))
          (refuse :code-out-of-range
                  (str "tag code " code " with " (:nsym ctx) " symbols")
                  :offset offset :code code))
        (need ctx q w end :node-truncated "tagged value offset")
        (let [v (rd-off ctx q)]
          [(inc (child-height ctx offset v depth)) (+ q w)]))

      0x2C ;; REF — a node of another chunk of the same value; structural only
      (let [[_ q] (rd-varint ctx (inc offset) end)]
        (need ctx q 4 end :node-truncated "ref offset")
        [1 (+ q 4)]))))

;; ------------------------------------------------------------------ tables

(defn- check-table-offsets
  "The shared shape of the symbol and shape tables: count+1 offsets of
   the chunk's width, relative to the entries region. Answers
   [entries-start offsets]."
  [ctx table count what oor]
  (let [w (:offw ctx)
        end (:end ctx)
        bytes (* (inc count) w)]
    (when (> (+ table bytes) end)
      (refuse :table-truncated (str what " offsets run past the chunk")
              :offset table :count count))
    (let [entries-start (+ table bytes)
          offs (mapv (fn [i] (rd-off ctx (+ table (* i w)))) (range (inc count)))]
      (doseq [o (pop offs)]
        (when (>= (+ entries-start o) end)
          (refuse oor
                  (str what " entry at " (+ entries-start o) " is past the chunk")
                  :offset table :entry (+ entries-start o))))
      (when (> (+ entries-start (peek offs)) end)
        (refuse oor
                (str what " entries run past the chunk") :offset table))
      (doseq [[a b] (partition 2 1 offs)]
        (when (<= b a)
          (refuse :table-offsets-not-ascending
                  (str what " offsets are not ascending")
                  :offset table)))
      [entries-start offs])))

(defn- check-symtab [ctx]
  (let [{:keys [symtab nsym]} ctx]
    (when (and (pos? nsym) (zero? symtab))
      (refuse :table-offset-out-of-range
              (str nsym " symbols but no symbol table") :offset 20))
    (when (pos? symtab)
      (let [[entries-start offs] (check-table-offsets ctx symtab nsym "symbol table"
                                                      :symtab-out-of-range)]
        (doseq [i (range nsym)]
          (let [s (+ entries-start (nth offs i))
                e (+ entries-start (nth offs (inc i)))
                tag (u8 (:bs ctx) s)]
            (when-not (contains? scalar-min-size tag)
              (refuse :malformed-scalar
                      (str "symbol entry " i " has tag " tag)
                      :offset s :tag tag))
            (let [end (parse-scalar ctx s e :malformed-scalar)]
              (when (not= end e)
                (refuse :malformed-scalar
                        (str "symbol entry " i " does not fill its slot")
                        :offset s)))))))))

(defn- check-shapetab
  "Answers the shapes: [{:n keys :type type-index-plus-1}]."
  [ctx]
  (let [{:keys [shapetab nshape nsym]} ctx]
    (when (and (pos? nshape) (zero? shapetab))
      (refuse :table-offset-out-of-range
              (str nshape " shapes but no shape table") :offset 24))
    (if (zero? shapetab)
      []
      (let [[entries-start offs] (check-table-offsets ctx shapetab nshape "shape table"
                                                      :table-offset-out-of-range)]
        (mapv (fn [i]
                (let [s (+ entries-start (nth offs i))
                      e (+ entries-start (nth offs (inc i)))
                      [n q] (rd-varint ctx s e)
                      [type q] (rd-varint ctx q e)]
                  (when (and (pos? type) (not (:types? ctx)))
                    (refuse :shape-type-without-table
                            (str "shape " i " names a type, the chunk has no type table")
                            :offset s :shape i))
                  (loop [j 0 q q]
                    (if (< j n)
                      (let [[c q] (rd-varint ctx q e)]
                        (when (or (neg? c) (>= c nsym))
                          (refuse :shape-code-out-of-range
                                  (str "shape " i " uses symbol code " c " with " nsym " symbols")
                                  :offset s :shape i :code c))
                        (recur (inc j) q))
                      (when (not= q e)
                        (refuse :malformed-shape
                                (str "shape " i " does not fill its slot")
                                :offset s))))
                  {:n n :type type}))
              (range nshape))))))

;; ------------------------------------------------------------------ the image

(defn- check-image [ctx]
  (let [bs (:bs ctx)
        end (:end ctx)
        layout (u16 bs 16)]
    (when (not= layout 1)
      (refuse :unsupported-layout
              (str "image layout " layout " — this reader places layout 1 only")
              :layout layout))
    (let [blocktab (u32 bs 20)
          reloctab (u32 bs 24)
          typetab (u32 bs 28)]
      (when (pos? blocktab)
        (let [region-end (cond (and (pos? reloctab) (> reloctab blocktab)) reloctab
                               (and (pos? typetab) (> typetab blocktab)) typetab
                               :else end)
              region (- region-end blocktab)]
          (when-not (zero? (rem region 8))
            (refuse
             :table-truncated
             "the block table is not a whole number of entries"
             :offset
             blocktab))
          (let [blocks (mapv (fn [i]
                               {:off (u32 bs (+ blocktab (* i 8)))
                                :size (u32 bs (+ blocktab (* i 8) 4))})
                             (range (quot region 8)))]
            (doseq [{:keys [off size]} blocks]
              (when (or (< off 32) (> (+ off size) end))
                (refuse :block-out-of-range
                        (str "block at " off " of " size " bytes")
                        :offset off :size size)))
            (doseq [[a b] (partition 2 1 blocks)]
              (when (<= (:off b) (:off a))
                (refuse :blocks-not-ascending "block offsets are not ascending"
                        :offset (:off b)))
              (when (< (:off b) (+ (:off a) (:size a)))
                (refuse :block-overlap
                        (str "block at " (:off b) " overlaps the block at " (:off a))
                        :offset (:off b))))
            (let [starts (set (map :off blocks))]
              (when-not (contains? starts (:root ctx))
                (refuse :root-not-a-block (str "root " (:root ctx) " is not a block start")
                        :root (:root ctx)))
              (when (pos? reloctab)
                (let [bounded? (and (pos? typetab) (> typetab reloctab))
                      rregion (- (if bounded? typetab end) reloctab)]
                  ;; Bounded by the type table, the region is exact. Running
                  ;; to the chunk end it carries the writer's align8 tail
                  ;; (Z1: total = align8 (tables + 12*r)), so the remainder
                  ;; is 0 or 4 — never anything else.
                  (when-not (if bounded?
                              (zero? (rem rregion 12))
                              (contains? #{0 4} (rem rregion 12)))
                    (refuse :table-truncated
                            "the relocation table is not a whole number of entries"
                            :offset reloctab))
                  (doseq [i (range (quot rregion 12))]
                    (let [p (+ reloctab (* i 12))
                          position (u32 bs p)
                          owner (u32 bs (+ p 4))
                          target (u32 bs (+ p 8))]
                      (when-not (< owner (count blocks))
                        (refuse :relocation-owner-unknown
                                (str "relocation owner ordinal " owner
                                     " with " (count blocks) " blocks")
                                :offset p :owner owner))
                      (when-not (< target (count blocks))
                        (refuse :relocation-target-unknown
                                (str "relocation target ordinal " target
                                     " with " (count blocks) " blocks")
                                :offset p :target target))
                      (let [ob (nth blocks owner)]
                        (when (or (< position (:off ob))
                                  (>= position (+ (:off ob) (:size ob))))
                          (refuse :relocation-out-of-range
                                  (str "relocation position " position
                                       " is outside its owner block at " (:off ob))
                                  :offset p :position position))))))))))))))

;; ------------------------------------------------------------------ the digest

(defn- check-digest [bs len]
  (let [start (- len 32)
        want (mapv (fn [i] (u8 bs (+ start i))) (range 32))
        got (mapv (fn [b] (bit-and b 0xFF))
                  (crypto/sha256
                   (mapv (fn [i] (if (>= i start) 0 (u8 bs i))) (range len))))]
    (when (not= want got)
      (refuse :digest-mismatch "the chunk's SHA-256 does not match its content"
              :offset start))))

;; ------------------------------------------------------------------ the door

(defn open-checked
  "One pass over bs (FORMAT-V1 §10): a CheckedBytes handle when the
   bytes are well-formed, a typed refusal otherwise. Options:
   :max-depth — the nesting limit, 64 by default (IMPL-PLAN C4.2)."
  ([bs] (open-checked bs {}))
  ([bs opts]
   (when-not #?(:clj (bytes? bs) :cljs (instance? js/Uint8Array bs))
     (refuse :not-bytes "open-checked wants a byte array" :given (str (type bs))))
   (let [len (byte-len bs)
         max-depth (long (get opts :max-depth 64))]
     (when (< len 32)
       (refuse :too-short (str len " bytes cannot hold a chunk header") :length len))
     (when-not (and (= (u8 bs 0) 0x45) (= (u8 bs 1) 0x56)
                    (= (u8 bs 2) 0x53) (= (u8 bs 3) 0x31))
       (refuse :bad-magic "the first four bytes are not EVS1" :offset 0))
     (let [version (u8 bs 4)
           kind (u8 bs 5)
           flags (u16 bs 6)
           length (u32 bs 8)]
       (when (not= version 1)
         (refuse :unsupported-version (str "chunk version " version)
                 :version version))
       (when-not (contains? #{0 1 2 3} kind)
         (refuse :unknown-kind (str "chunk kind " kind) :kind kind))
       (when-not (zero? (bit-and flags 65280))
         (refuse
          :unknown-required-features
          (str "required feature bits " (bit-and flags 65280) " are not known")
          :flags
          flags))
       (when (not= length len)
         (refuse :length-mismatch
                 (str "the header says " length " bytes, " len " were given")
                 :declared length :given len))
       (when-not (zero? (rem length 8))
         (refuse
          :bad-length
          (str "chunk length " length " is not a multiple of 8")
          :length
          length))
       (let [digest? (not (zero? (bit-and flags 2)))
             wide? (not (zero? (bit-and flags 1)))
             types? (not (zero? (bit-and flags 4)))
             end (- length (if digest? 32 0))
             root (u32 bs 12)
             nsym (u16 bs 16)
             nshape (u16 bs 18)
             symtab (u32 bs 20)
             shapetab (u32 bs 24)
             typetab (u32 bs 28)]
         (when digest? (check-digest bs length))
         (doseq [[nm off] [[:symtab symtab] [:shapetab shapetab] [:typetab typetab]]]
           (when (pos? off)
             ;; off == end is the empty table at the buffer's end — the
             ;; producers emit it (an image with no typed values writes
             ;; typetab at the relocation table's exact end, which can be
             ;; the buffer end) and place's parser reads the span
             ;; [off, end) as zero entries. SUCCESSOR REPAIR (2026-11-12):
             ;; the strict >= refused that boundary and broke transfer!
             ;; of a 100k-int vector on the collated tree; count-bearing
             ;; tables are still caught by their own count checks.
             (when (or (< off 32) (> off end))
               (refuse :table-offset-out-of-range
                       (str (name nm) " at " off " is outside the chunk")
                       :table nm :offset off))))
         ;; NOTE: FORMAT-V1 §10 also asks table offsets to be 8-aligned,
         ;; but §2-§5 place the tables contiguously and C1's golden bytes
         ;; (test/eve/fixtures/flat-bytes.edn, hand-computed from §2-§5)
         ;; put the shape table at 34 and 39. The producer's layout wins
         ;; over the unreviewed §10 clause (plan risk R11); the conflict
         ;; is recorded in doc/flat/C4-REPORT.md for the format owner.
         (when (and types? (zero? typetab))
           (refuse :type-table-missing "the TYPES flag is set, the type table is absent"
                   :offset 28))
         (when (contains? #{0 1} kind)
           (when (or (< root 32) (>= root end))
             (refuse :root-out-of-range (str "root at " root) :root root)))
         (let [ctx {:bs bs :len length :end end :wide? wide?
                    :offw (if wide? 4 2)
                    :nsym nsym :nshape nshape
                    :symtab symtab :shapetab shapetab :types? types?
                    :root root :max-depth max-depth
                    :heights (atom {})}]
           (case kind
             0 (do (check-symtab ctx)
                   (walk (assoc ctx :shapes (check-shapetab ctx)) root 1))
             1 (check-image ctx)
             ;; 2 directory and 3 raw bytes: header and digest only at
             ;; this lane; the directory's own checks are lane C3's.
             nil))
         (CheckedBytes. bs length kind root))))))

(def check
  "open-checked under its shorter name."
  open-checked)
