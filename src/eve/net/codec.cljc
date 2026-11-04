(ns eve.net.codec
  "A value ⇄ its ExtCID v3 block images, in portable cljc — the kernel's own layouts (VS's src/vs/kernel/wasm/eve.vs THE
   SEAL IMAGE, hamt.vs, vec.vs, list.vs; ExtCID v3's seal image), so a block eve writes is one the kernel
   adopts and a block the kernel cuts is one eve reads. A collection block's wire image is its seal image:
   [tag][payload] with every child slot imaged by the child's 32-byte image alone (§2.4 E: a slot's head 0..31 is
   addressing, never imaged) — a sealed child's SHA-256, a SET root's SHA-256 XOR 0xEE…; its key is sha256(image). A
   value block (a string, a keyword, a symbol, a char) is its canonical bytes under their SHA-256. Scalars are imaged
   inline (eve.vs _slot_image tier 2: the 16-byte type-CID then the payload): nil, true, false, an int32, a program
   DOUBLE, a program LONG — an integer in int32 is an INT32, past it a LONG, a float a DOUBLE. A HAMT kv value is the
   block's 64-byte slot (vlen 64), imaged by its 32 bytes; a list node holds its value by reference (list.vs: vlen 64).

   encode: value → {:root key-hex :set-root? bool :blocks {key-hex image}}; decode: (root key, set-root?, a function
   from a key to its image) → the value. encode-rec (MD-4): a value's RECORD beside its blocks, and an encode against
   the record of the value it replaces that images only the changed paths, every identical part kept at its known key;
   decode-rec: a fetched tree checked canonical (MD-2's rule, the kernel's reason codes) and decoded into the same
   record. THE SEALER (the end of this file): a block image behind its v3 own header, its slots the children's 64-byte
   cores, its digest §2.6's — attested records with their band, roots stamped outside it. Images are byte vectors
   (eve.net.bytes). Not encodable (a thrown ex-info of :type :unencodable): a ratio or big number, a string whose first
   byte the kernel reads as a type (':' or 0x01), a key that is not a string, keyword, symbol or number."
  (:refer-clojure :exclude [long double volatile! vswap!])
  (:require [eve.host :refer [long double volatile! vswap!]]
            [eve.net.bytes :as b]
            [eve.crypto :as prim]))

;; ── constants: the kernel's type CIDs (16 bytes, little-endian words) and typekeys ───────────────────────────────

(def ^:private NIL-CID    (b/unhex "eaeaeaeaeaeaeaeaeeeeeeeeeeeeeeee"))
(def ^:private TRUE-CID   (b/unhex "ababababababababc4c4c4c4c4c4c4c4"))
(def ^:private FALSE-CID  (b/unhex "88888888888888889999999999999999"))
(def ^:private INT32-CID  (b/unhex "d1d0d0d0d0d0d0d01379116eb4d4d4d4"))
(def ^:private DOUBLE-CID (b/unhex "f6f6f6f6f6f6f6f65550413264626c20"))   ;; "UPA2dbl "
(def ^:private LONG-CID   (b/unhex "f6f6f6f6f6f6f6f6555041326c6e6720"))   ;; "UPA2lng "
(def ^:private STR-CID    (b/unhex "329d9c9b9a999897aaaaaaaaaaaaaaaa"))   ;; STRING_LEAF
;; read, never written: the kernel's own runtime float64 and long, imaged in a band as the value block's first 32 bytes
;; (the CID, then the payload @16: eve.vs EVE_FLOAT64_CID / EVE_LONG_CID) — the form a writer before this codec gave a
;; runtime number (VS's vs.net.walk at landing A: a seeded timestamp past int32 is a runtime float64)
(def ^:private K-FLOAT64-CID (b/unhex "f6f6f6f6f6f6f6f6f4f4f4f4f4f4f4f4"))
(def ^:private K-LONG-CID    (b/unhex "f5f5f5f5f5f5f5f5f3f3f3f3f3f3f3f3"))
(def ^:private KWLF       (b/unhex "666c776b"))                           ;; a keyword's typekey
(def ^:private CHLF       (b/unhex "666c6863"))                           ;; a char's

(def ^:private T-MAP 0xED)
(def ^:private T-NODE 0x01)
(def ^:private T-VEC 0x12)
(def ^:private T-LIST 0x13)
(def ^:private EE (vec (repeat 32 0xEE)))
(def ^:private POW2 (vec (take 57 (iterate (fn [p] (* 2 p)) 1))))   ;; 2^0..2^56: a 64-bit long holds them

(defn- zeros [n] (vec (repeat n 0)))
(defn- xor-bytes [x y] (mapv (fn [p q] (bit-xor p q)) x y))
(defn- prefix? [bs p] (and (>= (count bs) (count p)) (= (vec p) (subvec (vec bs) 0 (count p)))))
(defn- u16le [x] [(mod x 256) (mod (quot x 256) 256)])
(defn- read-u16le [bs i] (+ (nth bs i) (* 256 (nth bs (inc i)))))
(defn- popcount [x] (loop [x x c 0] (if (zero? x) c (recur (quot x 2) (+ c (mod x 2))))))
(defn- bit? [x i] (= 1 (mod (quot x (nth POW2 i)) 2)))

;; ── IEEE 754 doubles and 64-bit integers, by arithmetic (no host's bit casts) ──────────────────────────────────

(defn- f64-bytes
  "the 8 little-endian bytes of the double x"
  [x]
  (let [x (double x)]
    (cond
      (not (== x x)) [0 0 0 0 0 0 0xF8 0x7F]                                    ;; NaN (the canonical quiet NaN)
      ;; -0.0, told by its text (a division by zero throws in Clojure, whatever the type): a host whose str of -0.0 is
      ;; "0" (ClojureScript) writes it as 0.0, which = treats as equal
      (and (zero? x) (= "-0.0" (str x))) [0 0 0 0 0 0 0 0x80]
      (zero? x) (zeros 8)
      :else
      (let [sign (if (neg? x) 1 0)
            a (if (neg? x) (- x) x)
            [field mant] (if (> a 1.7976931348623157e308)
                           [2047 0]                                               ;; ±Infinity
                           (let [[e m] (loop [e 0 m a]                            ;; 1 <= a / 2^e < 2, exactly
                                         (cond (>= m 2.0) (recur (inc e) (/ m 2.0))
                                               (< m 1.0) (recur (dec e) (* m 2.0))
                                               :else [e m]))]
                             (if (< e -1022)
                               [0 (long (loop [k 0 v a] (if (< k 1074) (recur (inc k) (* v 2.0)) v)))]
                               [(+ e 1023) (long (loop [k 0 v (- m 1.0)] (if (< k 52) (recur (inc k) (* v 2.0)) v)))])))
            low (mapv (fn [i] (mod (quot mant (nth POW2 (* 8 i))) 256)) (range 6))]
        (conj low
              (+ (mod (quot mant (nth POW2 48)) 16) (* 16 (mod field 16)))
              (+ (quot field 16) (* 128 sign)))))))

(defn- bytes-f64
  "the double of 8 little-endian bytes"
  [bs]
  (let [sign (quot (nth bs 7) 128)
        field (+ (* 16 (mod (nth bs 7) 128)) (quot (nth bs 6) 16))
        mant (+ (reduce + (map (fn [i] (* (nth bs i) (nth POW2 (* 8 i)))) (range 6)))
                (* (mod (nth bs 6) 16) (nth POW2 48)))
        scale (fn [v e] (if (neg? e)
                          (loop [k 0 v v] (if (< k (- e)) (recur (inc k) (/ v 2.0)) v))
                          (loop [k 0 v v] (if (< k e) (recur (inc k) (* v 2.0)) v))))
        ;; ±Infinity and NaN by arithmetic, not ##Inf / ##NaN (a reader syntax clojerl lacks), and only for a double
        ;; that holds one: the BEAM's floats have neither, so there the decode raises, where the value cannot exist
        inf (fn [] (* 1.7976931348623157e308 2.0))
        mag (cond (= field 2047) (if (zero? mant) (inf) (- (inf) (inf)))
                  (zero? field) (scale (double mant) -1074)
                  :else (scale (+ 1.0 (scale (double mant) -52)) (- field 1023)))]
    (if (= 1 sign) (- mag) mag)))

(defn- i64-bytes "the 8 little-endian bytes of the integer x (two's complement)" [x]
  (loop [i 0 v x out []]
    (if (= i 8) out (let [r (mod v 256)] (recur (inc i) (quot (- v r) 256) (conj out r))))))

(defn- bytes-i64 "the integer of 8 little-endian two's-complement bytes" [bs]
  (let [u (reduce + (map (fn [i] (* (nth bs i) (nth POW2 (* 8 i)))) (range 7)))
        top (nth bs 7)]
    (if (< top 128)
      (+ u (* top (nth POW2 56)))
      (- u (* (- 256 top) (nth POW2 56))))))

;; ── UTF-8 text ─────────────────────────────────────────────────────────────────────────────────────────────────────

(defn- utf8-text "the string of UTF-8 bytes (eve.net.bytes: the host builds it from code points)" [bs] (b/utf8->str bs))

(defn- kw-text [k] (str ":" (if (namespace k) (str (namespace k) "/" (name k)) (name k))))

;; ── the scalars a slot carries inline, and the value blocks ─────────────────────────────────────────────────────

(def ^:private I32-MIN -2147483648)
(def ^:private I32-MAX 2147483647)
(def ^:private I64-MIN -9223372036854775808)
(def ^:private I64-MAX 9223372036854775807)

(defn- unencodable [msg data] (throw (ex-info (str "codec: " msg) (assoc data :type :unencodable))))

(defn- scalar-band
  "the 32-byte inline image of a scalar (tier 2), or nil for a value that is not one"
  [v]
  (cond (nil? v) (b/join [NIL-CID (zeros 16)])
        (true? v) (b/join [TRUE-CID (zeros 16)])
        (false? v) (b/join [FALSE-CID (zeros 16)])
        (integer? v) (cond (<= I32-MIN v I32-MAX) (b/join [INT32-CID (subvec (i64-bytes v) 0 4) (zeros 12)])
                           (<= I64-MIN v I64-MAX) (b/join [LONG-CID (f64-bytes v) (i64-bytes v)])
                           :else (unencodable "an integer past 64 bits" {:value v}))
        (float? v) (b/join [DOUBLE-CID (f64-bytes v) (zeros 8)])
        (number? v) (unencodable "a number that is neither an integer nor a float" {:value v})
        :else nil))

(def ^:private F64-EXACT 9007199254740992.0)   ;; 2^53: every integer below it in magnitude is a double exactly

(defn- runtime-number
  "the number a kernel FLOAT64 band held: an integer when it is one (the writer's runtime held an integer literal past
   int32 as a float64 and printed it as the integer — landing A's broker seed, `ts 1760000000000`), else the double"
  [d]
  (if (and (< d F64-EXACT) (> d (- F64-EXACT)) (== d (long d))) (long d) d))

(defn- scalar-of-band
  "the scalar a tier-2 band images (eve.vs _tier2_span: the kernel's FLOAT64 and LONG boxes read too), or ::none. An
   all-zero band names no child and no value"
  [bd]
  (let [cid (subvec bd 0 16)]
    (cond (= cid NIL-CID) nil
          (= cid K-FLOAT64-CID) (runtime-number (bytes-f64 (subvec bd 16 24)))
          (= cid K-LONG-CID) (bytes-i64 (subvec bd 16 24))
          (= cid TRUE-CID) true
          (= cid FALSE-CID) false
          (= cid INT32-CID) (let [u (b/read-u32le bd 16)] (if (>= u 2147483648) (- u 4294967296) u))
          (= cid DOUBLE-CID) (bytes-f64 (subvec bd 16 24))
          (= cid LONG-CID) (bytes-i64 (subvec bd 24 32))
          :else ::none)))

(defn- value-image
  "the canonical image of a string / keyword / symbol / char value, or nil"
  [v]
  (cond (string? v) (let [u (b/utf8 v)]
                      (when (and (seq u) (or (= 0x3A (first u)) (= 0x01 (first u))))
                        (unencodable "a string whose first byte the kernel reads as a type (':' or 0x01)" {:value v}))
                      (b/join [STR-CID (b/u32le (count u)) (zeros 4) u]))
        (keyword? v) (let [u (b/utf8 (kw-text v))] (b/join [KWLF (zeros 12) (b/u32le (count u)) (zeros 4) u]))
        (symbol? v) (let [u (into [1] (b/utf8 (str v)))] (b/join [STR-CID (b/u32le (count u)) (zeros 4) u]))
        (char? v) (let [u (b/utf8 (str v))] (b/join [CHLF (zeros 12) (b/u32le (count u)) (b/u32le (first (b/decode-cp u 0))) u]))
        :else nil))

(defn- value-of-image
  "the string / keyword / symbol / char a value image holds, or ::none"
  [img]
  (let [n (b/read-u32le img 16)
        body (fn [] (subvec (vec img) 24 (+ 24 n)))]
    (cond (prefix? img STR-CID) (let [u (body)]
                                  (cond (and (seq u) (= 1 (first u))) (symbol (utf8-text (subvec u 1)))
                                        (and (seq u) (= 0x3A (first u))) (keyword (utf8-text (subvec u 1)))
                                        :else (utf8-text u)))
          (prefix? img KWLF) (keyword (utf8-text (subvec (body) 1)))
          (prefix? img CHLF) (first (utf8-text (body)))
          :else ::none)))

;; ── hamt keys: [0x01][text] for strings, keywords and symbols; [0x00][f64] for numbers ─────────────────────────

(defn- key-bytes [k]
  (cond (string? k) (into [1] (b/utf8 k))
        (keyword? k) (into [1] (b/utf8 (kw-text k)))
        (symbol? k) (into [1 1] (b/utf8 (str k)))
        (number? k) (into [0] (f64-bytes (double k)))
        :else (unencodable "a key that is not a string, keyword, symbol or number" {:key k})))

(defn- key-of-bytes [kb]
  (if (zero? (first kb))
    (let [d (bytes-f64 (subvec kb 1 9))
          a (if (neg? d) (- d) d)
          l (long d)]
      ;; collapse to a long only when the long's double re-encodes to the key's
      ;; own bytes: -0.0 is the one integral double whose bytes the long 0 does
      ;; not reproduce (the sign bit), and it decodes as the double it is
      (if (and (< a 9.007199254740992E15) (== d (double l)) (= (f64-bytes (double l)) (subvec kb 1 9)))
        l
        d))
    (let [u (subvec kb 1)]
      (cond (and (seq u) (= 0x3A (first u))) (keyword (utf8-text (subvec u 1)))
            (and (seq u) (= 1 (first u))) (symbol (utf8-text (subvec u 1)))
            :else (utf8-text u)))))

(defn- frag
  "the 5-bit slice of the 16-byte key hash h at bit shift (hamt.vs mask_hash: two bytes, the last one alone)"
  [h shift]
  (let [bi (quot shift 8)
        combined (if (>= bi 15) (nth h 15) (+ (nth h bi) (* 256 (nth h (inc bi)))))]
    (mod (quot combined (nth POW2 (mod shift 8))) 32)))

;; ── encode ────────────────────────────────────────────────────────────────────────────────────────────────────────
;; A value is encoded into a RECORD as well as its blocks (MD-4 C1): the record keeps what the next encode of a value
;; replacing it needs — each map / set entry's key bytes, hash, band and the value it was cut from, each node's key and
;; slices, a vector's element bands and the keys of its leaves and levels. An encode given the record of the value its
;; value replaces at the same reference (`encode-rec`) keeps every part whose value is the IDENTICAL one — its key known
;; at its reference, neither re-imaged nor re-keyed — and rebuilds only the slices a changed entry falls in, so a 1-key
;; change re-images the path to it, never the tree. A full encode is the same code with no record. Either way the
;; shape is the one the entries give whatever the order they arrived in, so the keys are the full encode's.
;;
;;   map / set record  {:t :map | :set, :band, :n, :root node | nil, :by {key entry}, :v the value, :delta}
;;   node              {:k key, :n entries under it, :sl {frag {:e {:kb :h :vb}} | {:c node}}}
;;   entry (in :by)    {:kb :h :vb, :vr the value's record (a collection's) | nil, :v the value}
;;   vector record     {:t :vec, :band, :n, :tailoff, :tail key | nil, :trie key | nil, :levels [[leaf key …] [key …] …],
;;                      :es [{:vb :vr :v} …], :v}
;;   list record       {:t :list, :band, :v}   (a list holds scalars: re-encoded whole when it is not identical)
;; :delta names, against the record given, the keys whose band changed (:set) and the keys gone (:gone) — nil for a
;; full build; eve.net.atom rebuilds only those snapshot entries.

(def ^:private SLOT 32)    ;; a slot's image: the child's 32-byte image (eve.vs _ser_slot; FORMAT.md §2.4 E)
(def ^:private CORE 64)    ;; a slot in the block: the child's 64-byte core (§2.1, §2.4 D) — a kv value's and a list value's vlen
(defn- slot "a child slot's image: the child's 32-byte band alone" [band] (vec band))
(def ^:private NO-SLOT (zeros SLOT))   ;; an empty slot's image (no child: the slot's SHA field zero)

(defn- sha-hex [bs] (b/hex (prim/sha256 bs)))

(defn- put-block
  "[key-hex acc]: the image under its key in acc — sha256(image), or a SET root's sha256(image) XOR 0xEE… (WIRE.md §7:
   the key its parent's band carries). Every block image the codec builds becomes its key here, and only here"
  ([acc image] (let [k (sha-hex image)] [k (assoc acc k image)]))
  ([acc image set-root?]
   (let [k (if set-root? (b/hex (xor-bytes (b/unhex (sha-hex image)) EE)) (sha-hex image))] [k (assoc acc k image)])))

(declare band-rec coll-rec)

;; ── hamt nodes ──

(defn- bitmap [fs] (reduce + 0 (map (fn [f] (nth POW2 f)) fs)))

(defn- node-image
  "the image of the bitmap node whose slices are sl ({frag {:e entry} | {:c node}}): a slice one entry has is a data
   entry, a slice two or more share a child node one level down"
  [sl]
  (let [fs (sort (keys sl))
        singles (filter (fn [f] (contains? (get sl f) :e)) fs)
        multis (filter (fn [f] (contains? (get sl f) :c)) fs)
        data (map (fn [f] (:e (get sl f))) singles)
        kvs (map (fn [e] (b/join [(b/u32le (count (:kb e))) (:kb e) (b/u32le CORE) (slot (:vb e))])) data)
        ;; the node's kv byte count is the BLOCK's (each value a 64-B slot there, its 32-B image here)
        kv-bytes (reduce + 0 (map (fn [e] (+ 8 (count (:kb e)) CORE)) data))]
    (b/join (concat [[T-NODE] (u16le 0) (u16le kv-bytes) (b/u32le (bitmap singles)) (b/u32le (bitmap multis))]
                    (map (fn [f] (slot (b/unhex (:k (:c (get sl f)))))) multis)
                    (map :h data)
                    kvs))))

(defn- seal-node "[the node of slices sl holding n entries, acc with its image]" [sl n acc]
  (let [[k acc] (put-block acc (node-image sl))] [{:k k :n n :sl sl} acc]))

(defn- hamt-build
  "[the node holding entries es ({:kb :h :vb}) at shift, acc]: the canonical shape whatever the order es come in"
  [es shift acc]
  (when (> shift 125)
    (unencodable "two keys share a full 128-bit hash (a collision node)" {}))
  (let [[sl acc] (reduce (fn [[sl acc] [f g]]
                           (if (= 1 (count g))
                             [(assoc sl f {:e (first g)}) acc]
                             (let [[c acc] (hamt-build g (+ shift 5) acc)] [(assoc sl f {:c c}) acc])))
                         [{} acc] (group-by (fn [e] (frag (:h e) shift)) es))]
    (seal-node sl (count es) acc)))

(defn- apply-changes
  "the entries es after the changes cs, by key bytes: a :gone change drops its key, any other is its entry. An :add of
   key bytes already held is two keys with one image (1 and 1.0 are two Clojure keys, one f64): refused, as a full build
   refuses it"
  [es cs]
  (vals (reduce (fn [m c]
                  (cond (:gone c) (dissoc m (:kb c))
                        (and (:add c) (contains? m (:kb c))) (unencodable "two keys share a full 128-bit hash (a collision node)" {})
                        :else (assoc m (:kb c) {:kb (:kb c) :h (:h c) :vb (:vb c)})))
                (reduce (fn [m e] (assoc m (:kb e) e)) {} es)
                cs)))

(defn- hamt-upd
  "[{:n :sl} — node (nil: none) after the changes cs — acc]: only the slices a change falls in are rebuilt (a slice left
   one entry its data entry, two or more a child sealed one level down — hamt-build's shape), every other slice is kept,
   its child's key with it. Unsealed: the caller seals it, or takes its one entry up a level"
  [node cs shift acc]
  (let [[sl dn acc]
        (reduce (fn [[sl dn acc] [f fcs]]
                  (let [old (get sl f)
                        n0 (cond (nil? old) 0 (contains? old :e) 1 :else (:n (:c old)))
                        [r acc] (if (contains? old :c)
                                  (hamt-upd (:c old) fcs (+ shift 5) acc)
                                  (let [es (apply-changes (if old [(:e old)] []) fcs)] [{:n (count es) :es es} acc]))
                        n (:n r)
                        dn (+ dn (- n n0))]
                    (cond (zero? n) [(dissoc sl f) dn acc]
                          (= 1 n) [(assoc sl f {:e (if (contains? r :es) (first (:es r)) (:e (val (first (:sl r)))))}) dn acc]
                          (contains? r :es) (let [[c acc] (hamt-build (:es r) (+ shift 5) acc)] [(assoc sl f {:c c}) dn acc])
                          :else (let [[c acc] (seal-node (:sl r) n acc)] [(assoc sl f {:c c}) dn acc]))))
                [(or (:sl node) {}) 0 acc]
                (group-by (fn [c] (frag (:h c) shift)) cs))]
    [{:n (+ (or (:n node) 0) dn) :sl sl} acc]))

;; ── maps and sets ──

(def ^:private MEMO-MAX 20000)

(def ^:dynamic *checked*
  "When bound (a volatile — eve.host's: one reader's, never shared across threads; on the BEAM a process value, which a
   read does not copy, where an atom's every deref copies its whole value out of ETS), what decode-rec made of each
   block — [value record], by the block's band (hex) —, of each map node at its place, and each key's XXH3-128, so the
   next read of an atom (eve.net.pages/read-pages over its next value's pages) checks and decodes only the blocks it has
   not met, and the next write hashes only its new keys: a band names one image, every block under it checked when it
   was first met. new-checked makes one; emptied past MEMO-MAX entries."
  nil)

(defn new-checked "an empty *checked* memo" [] (volatile! {}))

(defn- checked! "v kept in the *checked* memo m under k" [m k v]
  (vswap! m (fn [x] (assoc (if (> (count x) MEMO-MAX) {} x) k v)))
  v)

(defn- key-hash
  "XXH3-128 of a key's bytes (a byte vector), kept in the *checked* memo when one is bound: an atom's keys recur — each
   write images its changed maps' nodes again and each read checks them again, every key's hash with them (eve's
   portable XXH3 on most hosts: ~4 ms a key on basilisp, measured 2026-10-26 under cProfile)"
  [kb]
  (let [kb (vec kb)
        m *checked*]
    (if m
      (let [mk [::key-hash kb]]
        (or (get @m mk) (checked! m mk (vec (prim/xxh3-128 kb)))))
      (vec (prim/xxh3-128 kb)))))

(defn- entry-for
  "[the entry of key k holding v, acc]: e0 the entry k had (its key bytes and hash kept; nil computes them)"
  [k v e0 known acc]
  (let [kb (if e0 (:kb e0) (key-bytes k))
        h (if e0 (:h e0) (key-hash kb))
        [vb vr acc] (band-rec v e0 known acc)]
    [{:kb kb :h h :vb vb :vr vr :v v} acc]))

(defn- node-entry [e] {:kb (:kb e) :h (:h e) :vb (:vb e)})

(defn- map-rec
  "[the record of the map m (a set: set?, each element its own value), acc]: r0 the record of the value m replaces —
   the entries whose value is the identical one are kept; those whose band changes, the new ones and the gone ones are
   the changes the hamt takes. nil builds every entry"
  [m set? r0 known acc]
  (let [old (:v r0)
        val-of (fn [e] (if set? e (val e)))
        key-of (fn [e] (if set? e (key e)))
        [adds mods] (if r0
                      (reduce (fn [[adds mods] e]
                                (let [k (key-of e)]
                                  (cond (not (contains? old k)) [(conj adds k) mods]
                                        (or set? (identical? (val-of e) (get old k))) [adds mods]
                                        :else [adds (conj mods k)])))
                              [[] []] m)
                      [nil nil])
        gone (if (and r0 (< (- (count m) (count adds)) (count old)))
               (vec (remove (fn [k] (contains? m k)) (if set? old (keys old))))
               [])
        value (fn [k] (if set? k (get m k)))]
    (if (nil? r0)
      ;; a full build
      (let [[by es acc] (reduce (fn [[by es acc] e]
                                  (let [k (key-of e) [en acc] (entry-for k (val-of e) nil known acc)]
                                    [(assoc by k en) (conj es (node-entry en)) acc]))
                                [{} [] acc] m)
            [root acc] (if (seq es) (hamt-build (mapv (fn [e] {:kb (:kb e) :h (:h e) :vb (or (:vband e) (:vb e))}) es) 0 acc) [nil acc])
            n (count es)
            [k acc] (put-block acc (b/join [[T-MAP] (b/u32le n) (if root (slot (b/unhex (:k root))) NO-SLOT)]) set?)]
        [{:t (if set? :set :map) :band (b/unhex k) :n n :root root :by by :v m :delta nil} acc])
      (let [by0 (:by r0)
            [by cs set-ks acc] (reduce (fn [[by cs set-ks acc] [k add?]]
                                         (let [e0 (when-not add? (get by0 k))
                                               [en acc] (entry-for k (value k) e0 known acc)
                                               by (assoc by k en)]
                                           (if (and e0 (= (:vb en) (:vb e0)))
                                             [by cs set-ks acc]   ;; the same band: no block holds a change
                                             [by (conj cs (assoc (node-entry en) :add add?)) (conj set-ks k) acc])))
                                       [by0 [] [] acc]
                                       (concat (map (fn [k] [k true]) adds) (map (fn [k] [k false]) mods)))
            ;; the gone first: a key whose bytes another takes in the same change (1 out, 1.0 in) is no collision
            cs (into (mapv (fn [k] (let [e0 (get by0 k)] {:kb (:kb e0) :h (:h e0) :gone true})) gone) cs)
            by (reduce dissoc by gone)
            delta {:set set-ks :gone gone}]
        (if (empty? cs)
          [(assoc r0 :by by :v m :delta delta) acc]
          (let [[r acc] (hamt-upd (:root r0) cs 0 acc)
                n (:n r)
                [root acc] (if (pos? n) (seal-node (:sl r) n acc) [nil acc])
                [k acc] (put-block acc (b/join [[T-MAP] (b/u32le n) (if root (slot (b/unhex (:k root))) NO-SLOT)]) set?)]
            [{:t (if set? :set :map) :band (b/unhex k) :n n :root root :by by :v m :delta delta} acc]))))))

;; ── vectors and lists ──

(defn- hamt-root-of
  "[key-hex acc] of a map root over entries [{:kb :h :vband}] — or a set root, its key XOR 0xEE…"
  [es set? acc]
  (let [[child acc] (if (seq es) (hamt-build (mapv (fn [e] {:kb (:kb e) :h (:h e) :vb (or (:vband e) (:vb e))}) es) 0 acc) [nil acc])
        img (b/join [[T-MAP] (b/u32le (count es)) (if child (slot (b/unhex (:k child))) NO-SLOT)])]
    (if set?
      ;; a set root is stored under its SHA XOR 0xEE… (WIRE.md §7): the key its parent's band carries
      (let [k (b/hex (xor-bytes (b/unhex (sha-hex img)) EE))] [k (assoc acc k img)])
      (put-block acc img))))

(defn- entry [kb vband] {:kb kb :h (key-hash kb) :vband vband})


(defn- vec-node [bands acc] (put-block acc (b/join (concat [[T-VEC] (zeros 8)] (map slot bands)))))

(defn- vec-rec
  "[the record of the vector xs, acc]: its last 1..32 elements in the tail node, the rest in full 32-element leaves under
   a trie whose nodes hold only their occupied slots (vec.vs; shift 0 when the trie is one leaf). r0 the record of the
   vector xs replaces: an element identical to the one at its index keeps its band, a leaf none of whose elements
   changed keeps its key, and a node over the same children keeps its key"
  [xs r0 known acc]
  (let [n (count xs)
        es0 (:es r0)
        [es dirty acc] (reduce (fn [[es dirty acc] i]
                                 (let [x (nth xs i)
                                       e0 (when (< i (count es0)) (nth es0 i))
                                       [vb vr acc] (band-rec x e0 known acc)]
                                   [(conj es {:vb vb :vr vr :v x})
                                    (if (and e0 (= vb (:vb e0))) dirty (conj dirty (quot i 32)))
                                    acc]))
                               [[] #{} acc] (range n))
        tailoff (if (zero? n) 0 (* 32 (quot (dec n) 32)))
        nleaves (quot tailoff 32)
        leaves0 (first (:levels r0))
        bands (fn [from to] (map :vb (subvec es from to)))
        [leaf-keys acc] (reduce (fn [[ks acc] j]
                                  (cond
                                    (and (< j (count leaves0)) (not (contains? dirty j))) [(conj ks (nth leaves0 j)) acc]
                                    ;; a full tail the vector grew past is this leaf, unchanged
                                    (and r0 (= j (count leaves0)) (= (* 32 j) (:tailoff r0)) (= (:n r0) (* 32 (inc j)))
                                         (not (contains? dirty j)))
                                    [(conj ks (:tail r0)) acc]
                                    :else (let [[k acc] (vec-node (bands (* 32 j) (* 32 (inc j))) acc)] [(conj ks k) acc])))
                                [[] acc] (range nleaves))
        [tail-key acc] (cond (= tailoff n) [nil acc]
                             (and r0 (= tailoff (:tailoff r0)) (= n (:n r0)) (not (contains? dirty nleaves))) [(:tail r0) acc]
                             :else (vec-node (bands tailoff n) acc))
        [levels acc] (loop [level leaf-keys lv 1 levels [leaf-keys] acc acc]
                       (if (<= (count level) 1)
                         [levels acc]
                         (let [old-lv (get (:levels r0) lv)
                               old-below (get (:levels r0) (dec lv))
                               [up acc] (reduce (fn [[ks acc] [j g]]
                                                  (if (and (< j (count old-lv))
                                                           (= (vec g) (subvec old-below (* 32 j) (min (count old-below) (* 32 (inc j))))))
                                                    [(conj ks (nth old-lv j)) acc]
                                                    (let [[k acc] (vec-node (map b/unhex g) acc)] [(conj ks k) acc])))
                                                [[] acc] (map-indexed vector (partition-all 32 level)))]
                           (recur up (inc lv) (conj levels up) acc))))
        trie (first (peek levels))
        shift (if (<= nleaves 1) 0 (* 5 (dec (count levels))))
        rec {:t :vec :n n :tailoff tailoff :tail tail-key :trie trie :levels levels :es es :v xs}]
    (if (and r0 (= n (:n r0)) (= tail-key (:tail r0)) (= trie (:trie r0)))
      [(assoc rec :band (:band r0)) acc]
      (let [[k acc] (put-block acc (b/join [[T-VEC] (b/u32le n) (b/u32le shift) (b/u32le (- n tailoff)) (zeros 4)
                                            (if tail-key (slot (b/unhex tail-key)) NO-SLOT)
                                            (if trie (slot (b/unhex trie)) NO-SLOT)]))]
        [(assoc rec :band (b/unhex k)) acc]))))

(defn- list-rec
  "[the record of a list, acc]: a node per element ([tag][vlen 64][next slot][the element's slot] — list.vs: the value
   always by reference, at LNODE_HEADER_SIZE), then the root ([tag][count][head slot])"
  [xs known acc]
  (let [[bands acc] (reduce (fn [[bs acc] x] (let [[vb _ acc] (band-rec x nil known acc)] [(conj bs vb) acc])) [[] acc] xs)
        [head acc] (reduce (fn [[next acc] bd]
                             (put-block acc (b/join [[T-LIST] (b/u32le CORE) (if next (slot (b/unhex next)) NO-SLOT) (slot bd)])))
                           [nil acc] (reverse bands))
        [k acc] (put-block acc (b/join [[T-LIST] (b/u32le (count xs)) (if head (slot (b/unhex head)) NO-SLOT)]))]
    [{:t :list :band (b/unhex k) :v xs} acc]))

(def ^:dynamic *memo*
  "When bound (a host atom), what encoding a collection gave, by the collection — so a value that shares most of its
   structure with one encoded before (the next value of an atom) is encoded in what changed: every unchanged subtree's
   key and blocks are looked up, not rebuilt. Keyed by the collection's kind and the collection (a vector and a list of
   the same elements are equal in Clojure, and their images are not). Emptied past MEMO-MAX entries."
  nil)

(def ^:dynamic *decoded*
  "When bound (a host atom), the value each collection block decoded to, by its key — the next decode of an atom's value
   shares every unchanged subtree (and its cached hash) with the last."
  nil)




(defn- remember! [a k v]
  (swap! a (fn [m] (assoc (if (> (count m) MEMO-MAX) {} m) k v)))
  v)

(defn- coll-rec
  "[the record of the collection x, acc]: r0 the record of the value x replaces at its reference, used when it is of x's
   kind"
  [x r0 known acc]
  ;; the map itself, not (seq x): glojure v0.7.22 has no reduce over a large map's ArrayNodeSeq, and the entries are the same
  (cond
    (map? x) (map-rec x false (when (= :map (:t r0)) r0) known acc)
    (set? x) (map-rec x true (when (= :set (:t r0)) r0) known acc)
    (vector? x) (vec-rec x (when (= :vec (:t r0)) r0) known acc)
    (seq? x) (list-rec x known acc)
    :else (unencodable (str "no image for " (pr-str x)) {:value x})))

(defn- attest-of "the attestation a record carries (::attest in its metadata), or nil" [x] (::attest (meta x)))

(defn- fixed! [bs n what]
  (when-not (and bs (= n (count bs))) (unencodable (str "an attestation's " what " is " n " bytes") {:got (count bs)}))
  (vec bs))

(defn attest-band
  "THE ATTESTED BAND (FORMAT.md §2.2, §2.6): own header bytes 64..191 of a record, the 128 bytes its digest takes after
   its image — EPOCH (little-endian u128; the low 8 bytes written) ‖ SPEC_ID (32) ‖ GROUP_ROOT (32, zero when nil: its
   HAS bit clear) ‖ PRINCIPAL_COMMIT (32, zero when nil) ‖ EVALUATOR (16), in header order. at:
   {:epoch n :spec-id bytes :group-root bytes|nil :principal-commit bytes|nil :evaluator bytes}"
  [{:keys [epoch spec-id group-root principal-commit evaluator]}]
  (when-not (and (integer? epoch) (<= 0 epoch I64-MAX)) (unencodable "an attestation's epoch is a non-negative integer" {:epoch epoch}))
  (b/join [(i64-bytes epoch) (zeros 8) (fixed! spec-id 32 "spec id")
           (if group-root (fixed! group-root 32 "group root") (zeros 32))
           (if principal-commit (fixed! principal-commit 32 "principal commitment") (zeros 32))
           (fixed! evaluator 16 "evaluator")]))

(defn attest
  "x (a map or a vector) as a RECORD (FORMAT.md §2.4 G, H): its block ATTESTED, its digest SHA-256(image ‖ attest-band
   at) — who, when and under which spec it was written — so two equal texts written in two epochs are two messages. The
   attestation rides in x's metadata, which a block's header is to eve's value: encode, band-of and the page writer
   seal x attested; the page reader gives a decoded record its header's attestation back"
  [x at]
  (when-not (or (map? x) (vector? x))
    (unencodable "only a map or a vector is a record (attest.vs: a map or vector root)" {:value x}))
  (attest-band at)
  (vary-meta x assoc ::attest at))

(defn attestation "the attestation x carries (attest's at), or nil" [x] (attest-of x))

(defn- attested-rec
  "[the band of the record x, its record, acc with its image]: §2.6 with ATTESTED set — the image built whole (a
   record's band is its content and its attestation, never a cut of a value it replaces), its digest
   SHA-256(image ‖ hdr[64..191]), the image held under that digest carrying the attestation (the page writer's header)"
  [x at known acc]
  (when (set? x) (unencodable "a set is not a record" {:value x}))
  (let [[r acc] (coll-rec (with-meta x nil) nil known acc)
        img (vec (get acc (b/hex (:band r))))
        d (vec (prim/sha256 (b/join [img (attest-band at)])))]
    [d (assoc r :band d :attest at) (assoc acc (b/hex d) (with-meta img {::attest at}))]))

(defn- band-rec
  "[the 32-byte band a slot carries for x, x's record (a collection's; nil else), acc with x's blocks]: e0 the entry
   (map, set or vector element) x replaces — its value the identical one, or an equal string, keyword, symbol or char,
   keeps its band and record (the cut: the key is known at its reference); known a function of a value to the record a
   reader already holds for exactly it (eve.net.atom's decoded trees), or nil"
  [x e0 known acc]
  (cond
    (and e0 (identical? x (:v e0))) [(:vb e0) (:vr e0) acc]
    :else
    (if-let [s (scalar-band x)]
      [s nil acc]
      (if (or (string? x) (keyword? x) (symbol? x) (char? x))
        (if (and e0 (nil? (:vr e0)) (= x (:v e0)))
          [(:vb e0) nil acc]
          (if-let [img (value-image x)]
            (let [[k acc] (put-block acc img)] [(b/unhex k) nil acc])
            (unencodable (str "no image for " (pr-str x)) {:value x})))
        (if-let [at (attest-of x)]
          (attested-rec x at known acc)
          (if-let [r (when known (known x))]
            [(:band r) r acc]
            (let [[r acc] (coll-rec x (:vr e0) known acc)] [(:band r) r acc])))))))

(defn encode-rec
  "[the record of the collection v, {key-hex image} of every block image built]: r0 (or nil) the record of the value v
   replaces at its reference (the previous value of an atom: eve.net.atom) — every part of v identical to r0's is kept,
   its key known, and only the changed paths are imaged; known (or nil) a function of a value to its record. The keys
   are a full encode's (encode)"
  ([v r0] (encode-rec v r0 nil))
  ([v r0 known] (coll-rec v r0 known {})))

(defn rec-key "the key hex of a record's root block" [r] (b/hex (:band r)))
(defn rec-delta "the keys whose band changed (:set) and the keys gone (:gone) against the record encoded from (nil: all)" [r] (:delta r))
(defn rec-band-of "the band of the value under key k of a map record's entry" [r k] (get-in r [:by k :vb]))

(defn encode
  "{:root key-hex :set-root? bool :blocks {key-hex image}} of a map, set, vector or list value"
  [v]
  (let [[r acc] (coll-rec v nil nil {})]
    {:root (b/hex (:band r)) :set-root? (= :set (:t r)) :blocks acc}))

(defn map-of-bands
  "{:root key-hex :blocks {key-hex image}} of the map whose entries are `kbands` ([k band] pairs, each value's 32-byte
   band given — band-of's, or the reference a snapshot holds): the map's own blocks, its root and bitmap nodes, no value
   encoded again. The same blocks encode makes for the map of those values"
  [kbands]
  (let [[k acc] (hamt-root-of (map (fn [[k bd]] (entry (key-bytes k) (vec bd))) kbands) false {})]
    {:root k :blocks acc}))

;; ── decode ────────────────────────────────────────────────────────────────────────────────────────────────────────
;; One image: every slot its child's 32 bytes (FORMAT.md §2.6). A v2 image (a slot's 64 or 256 bytes) is refused (§2.3).

(declare decode-band)

(defn- band-at "the child band of the slot image at off" [img off] (subvec (vec img) off (+ off SLOT)))
(def ^:private slot-band band-at)
(defn- empty-band? [bd] (every? zero? bd))

(def ^:private ROOT-N (+ 5 SLOT))          ;; a map / set / list root image: [tag][count][the slot]
(def ^:private VROOT-N (+ 17 SLOT SLOT))   ;; a vector root image: [tag][count][shift][tail length][0 ×4][tail][trie]
(def ^:private LNODE-N (+ 5 SLOT SLOT))    ;; a list node image: [tag][vlen 64][next][the value]

(defn- root-image!
  "img when it is a collection root image of its tag's size, else a thrown :bad-image"
  [img]
  (let [n (count img) tag (first img)]
    (when-not (or (and (or (= tag T-MAP) (= tag T-LIST)) (= n ROOT-N)) (and (= tag T-VEC) (= n VROOT-N)))
      (throw (ex-info "codec: not a v3 collection root image" {:type :bad-image :tag tag :size n})))
    img))

(defn- hamt-entries
  "every [key-bytes value-band] of the node image under key, depth first"
  [get-block key]
  (let [img (vec (get-block key))
        dbm (b/read-u32le img 5)
        nbm (b/read-u32le img 9)
        cc (popcount nbm)
        dc (popcount dbm)
        children (map (fn [i] (b/hex (band-at img (+ 13 (* SLOT i))))) (range cc))
        kv0 (+ 13 (* SLOT cc) (* 16 dc))
        data (loop [i 0 p kv0 out []]
               (if (= i dc)
                 out
                 (let [kl (b/read-u32le img p)
                       kb (subvec img (+ p 4) (+ p 4 kl))
                       vl (b/read-u32le img (+ p 4 kl))
                       v (+ p 8 kl)]
                   (when-not (= vl CORE) (throw (ex-info "codec: a kv value that is not a slot" {:type :bad-image :key key})))
                   (recur (inc i) (+ v SLOT) (conj out [kb (band-at img v)])))))]
    (when (not= T-NODE (first img))
      (throw (ex-info "codec: not a hamt bitmap node" {:type :bad-image :key key :tag (first img)})))
    (concat data (mapcat (fn [c] (hamt-entries get-block c)) children))))

(defn- vec-leaves
  "the bands of the trie under key, in order: a node at shift 0 holds elements, above it child nodes"
  [get-block key shift]
  (let [img (vec (get-block key))
        n (quot (- (count img) 9) SLOT)
        bands (map (fn [i] (band-at img (+ 9 (* SLOT i)))) (range n))]
    (if (zero? shift)
      bands
      (mapcat (fn [bd] (vec-leaves get-block (b/hex bd) (- shift 5))) bands))))

(declare decode-coll*)

(defn- with-attest
  "v with the attestation the image under key carries (a record the page reader read: its header's band), else v"
  [v get-block key]
  (if-let [at (::attest (meta (get-block key)))] (vary-meta v assoc ::attest at) v))

(defn- decode-coll
  "the collection whose block is under key (set? when the key named it XOR 0xEE…; through *decoded* when bound)"
  [get-block key set?]
  (if-let [m *decoded*]
    (let [mk [key set?]]
      (if-let [v (get @m mk)] (first v) (first (remember! m mk [(with-attest (decode-coll* get-block key set?) get-block key)]))))
    (with-attest (decode-coll* get-block key set?) get-block key)))

(defn- set-key?
  "key names its block as a SET root: the image's SHA-256 XOR 0xEE… (a map's key is the SHA-256, a record's its
   attested digest)"
  [key img]
  (= key (b/hex (xor-bytes (vec (prim/sha256 img)) EE))))

(defn- decode-coll*
  [get-block key set?]
  (let [img (vec (get-block key))
        tag (first img)]
    (cond
      (= tag T-MAP)
      (let [child (band-at (root-image! img) 5)
            es (if (empty-band? child) [] (hamt-entries get-block (b/hex child)))]
        (if set?
          (into #{} (map (fn [[_ vb]] (decode-band get-block vb)) es))
          (into {} (map (fn [[kb vb]] [(key-of-bytes kb) (decode-band get-block vb)]) es))))
      (= tag T-VEC)
      (let [img (root-image! img)
            shift (b/read-u32le img 5)
            tail (band-at img 17)
            trie (band-at img (+ 17 SLOT))]
        (mapv (fn [bd] (decode-band get-block bd))
              (concat (when-not (empty-band? trie) (vec-leaves get-block (b/hex trie) shift))
                      (when-not (empty-band? tail) (vec-leaves get-block (b/hex tail) 0)))))
      (= tag T-LIST)
      (let [head (band-at (root-image! img) 5)]
        (apply list (loop [k (when-not (empty-band? head) (b/hex head)) out []]
                      (if (nil? k)
                        out
                        (let [node (vec (get-block k))
                              _ (when-not (and (= LNODE-N (count node)) (= T-LIST (first node)) (= CORE (b/read-u32le node 1)))
                                  (throw (ex-info "codec: not a v3 list node" {:type :bad-image :key k})))
                              nxt (band-at node 5)]
                          (recur (when-not (empty-band? nxt) (b/hex nxt))
                                 (conj out (decode-band get-block (band-at node (+ 5 SLOT))))))))))
      :else (throw (ex-info "codec: not a collection image" {:type :bad-image :key key :tag tag})))))

(defn- decode-band
  "the value a 32-byte band names: a scalar inline, else the block under it — a value block, a collection, or a SET root
   (the band is its SHA XOR 0xEE…: the block under the band hashes to something else)"
  [get-block bd]
  (let [s (scalar-of-band bd)]
    (if (not= s ::none)
      s
      (let [k (b/hex bd)
            img (vec (get-block k))
            v (value-of-image img)]
        (cond (not= v ::none) v
              (and (= T-MAP (first img)) (set-key? k img)) (decode-coll get-block k true)
              :else (decode-coll get-block k false))))))

(defn decode
  "the value under root (key hex), its blocks read through get-block (a function of a key hex → the image); set-root?
   when root names a set (its SHA XOR 0xEE…)"
  [root set-root? get-block]
  (decode-coll get-block root set-root?))

;; ── a fetched tree, checked canonical and decoded into its record (MD-4 C3) ───────────────────────────────────────
;; A reader keeps the record of every tree it fetches (eve.net.atom), and a later swap keeps that tree's keys wherever
;; its value is unchanged — so a tree is TRUSTED for its keys only once it is checked to be in canonical form: the one
;; form this codec (and the kernel: MD-0's C7) writes for its content. A tree in another form for the same value would
;; be kept under keys no writer of that value makes, and a swap of it would re-image only its changed path in this
;; codec's form beside the rest in the other. MD-2's rule (vs.net.walk check-canonical!, hamt.vs THE CANONICAL-FORM
;; VERIFIER), mirrored over the wire images and answered with the kernel's reason codes:
;;    8 a nil child under a set bit, or an empty element slot
;;    9 a node below the root holding one entry (it belongs in its parent)
;;   12 a key hash that is not its key's
;;   13 an entry where its hash does not lead
;;   14 a count that is not its entries (a map root's, a vector's tail, a node's occupancy, a list's)
;;   15 a field the producers write otherwise: the image this codec writes for the content parsed differs, a slot's
;;      epoch / spec band not zero, a value that is not a slot, a tag where another kind lies
;;   17 an RRB relaxed vector node (vec_slice / vec_concat before MD-1: a second layout of the same elements)
;; (2 and 7 are the address check's: on the wire a seal and a slot's digest ARE the keys, eve.net.blobs.) A refusal
;; throws ex-info {:type :non-canonical :reason code :key key}.

(def CANON-REASONS
  "the reason codes a refused tree names (the kernel verifier's, hamt.vs THE CANONICAL-FORM VERIFIER)"
  {8 "a nil child under a set bit" 9 "a lone survivor its parent should hold" 12 "a key hash that is not its key's"
   13 "an entry where its hash does not lead" 14 "a count that is not its entries"
   15 "a field the producers write otherwise" 17 "an RRB relaxed vector node"})

(defn- refuse [code key what]
  (throw (ex-info (str "codec: the fetched block " (subs key 0 (min 16 (count key))) "… is not in canonical form: "
                       (get CANON-REASONS code) " (" what ")")
                  {:type :non-canonical :reason code :key key})))

(defn- need! "refuses unless the image holds n bytes" [img n key what] (when (< (count img) n) (refuse 15 key what)))

(defn- bits [x] (filter (fn [i] (bit? x i)) (range 32)))

(defn- keyable? [k] (or (string? k) (keyword? k) (symbol? k) (number? k)))

(declare check-band)

(defn- check-value-image
  "the string / keyword / symbol / char an image holds, its layout checked ([CID or typekey][count][0, a char's code
   point][bytes], nothing after; a string, keyword or symbol the image this codec writes for it — a string led by ':'
   is the kernel's keyword in its string form, which a collection holds as a keyword), or ::none for an image of no
   value kind"
  [img key]
  (if-not (and (>= (count img) 24) (or (prefix? img STR-CID) (prefix? img KWLF) (prefix? img CHLF)))
    ::none
    (let [n (b/read-u32le img 16)]
      (when-not (= (count img) (+ 24 n)) (refuse 15 key "a value block's byte count"))
      (if (prefix? img CHLF)
        ;; a char: its layout only (a host with no character type reads it as a one-character string)
        (do (when-not (and (pos? n) (every? zero? (subvec img 4 16)) (= (b/read-u32le img 20) (first (b/decode-cp (subvec img 24) 0))))
              (refuse 15 key "a char's pad or code point"))
            (value-of-image img))
        (do (when (and (prefix? img KWLF) (not (and (pos? n) (= 0x3A (nth img 24)))))
              (refuse 15 key "a keyword whose text does not lead with ':'"))
            (let [v (value-of-image img)]
              (when-not (and (or (string? v) (keyword? v) (symbol? v))
                             (not (and (string? v) (seq v) (= \: (first v))))
                             (= img (value-image v)))
                (refuse 15 key "the image this codec writes for its value differs"))
              v))))))

(declare check-node)

(defn- check-node*
  "[the node record of the bitmap node under key at shift — reached from the root by the [shift frag] steps of path —,
   out with every [clj-key entry] under it]: its layout (the image this codec writes for what it holds), each data
   entry's hash its key's and leading to where it lies, every child named and canonical one level down, a node below
   the root holding two entries or more, each value checked and decoded"
  [get-block key shift path root? set? out]
  (let [img (vec (get-block key))]
    (need! img 13 key "a bitmap node's head")
    (when-not (= T-NODE (first img)) (refuse 15 key (str "tag " (first img) " where a bitmap node lies")))
    (when (> shift 125) (refuse 15 key "a node past the hash's depth"))
    (let [dbm (b/read-u32le img 5)
          nbm (b/read-u32le img 9)
          dfs (vec (bits dbm))
          cfs (vec (bits nbm))
          _ (when (some (fn [f] (bit? nbm f)) dfs) (refuse 15 key "a slice that is both a data entry and a child"))
          cc (count cfs)
          h0 (+ 13 (* SLOT cc))
          kv0 (+ h0 (* 16 (count dfs)))
          _ (need! img kv0 key "a bitmap node's children and hashes")
          [data _] (reduce (fn [[data p] [i f]]
                             (need! img (+ p 4) key "a key's length")
                             (let [kl (b/read-u32le img p)
                                   _ (need! img (+ p 8 kl) key "a key and its value's length")
                                   kb (subvec img (+ p 4) (+ p 4 kl))
                                   vl (b/read-u32le img (+ p 4 kl))
                                   v (+ p 8 kl)
                                   h (subvec img (+ h0 (* 16 i)) (+ h0 (* 16 (inc i))))]
                               (when-not (= CORE vl) (refuse 15 key "a value that is not a slot"))
                               (need! img (+ v SLOT) key "a value's slot")
                               (when-not (= h (key-hash kb)) (refuse 12 key "a data entry's hash"))
                               (when-not (and (= f (frag h shift)) (every? (fn [[s pf]] (= pf (frag h s))) path))
                                 (refuse 13 key "a data entry off its hash's path"))
                               [(conj data {:f f :kb kb :h h :vb (band-at img v)}) (+ v SLOT)]))
                           [[] kv0] (map-indexed vector dfs))
          sl (reduce (fn [sl e] (assoc sl (:f e) {:e (dissoc e :f)})) {} data)
          [sl n out] (reduce (fn [[sl n out] [i f]]
                               (let [bd (band-at img (+ 13 (* SLOT i)))
                                     _ (when (empty-band? bd) (refuse 8 key "a nil child under a set bit"))
                                     [c out] (check-node get-block (b/hex bd) (+ shift 5) (conj path [shift f]) false set? out)]
                                 [(assoc sl f {:c c}) (+ n (:n c)) out]))
                             [sl (count data) out] (map-indexed vector cfs))]
      (when (and (not root?) (< n 2)) (refuse 9 key "a node below the root holding one entry"))
      (when-not (= img (node-image sl)) (refuse 15 key "the image this codec writes for its content differs"))
      [{:k key :n n :sl sl}
       (reduce (fn [out e]
                 (let [[v vr] (check-band get-block (:vb e))
                       k (if set? v (key-of-bytes (:kb e)))]
                   (when (and set? vr) (refuse 15 key "a set member that is a collection"))
                   ;; a number's key bytes decode lossily by design (-0.0 reads as 0): their check is the entry's hash
                   (when (and (keyable? k) (not (number? k)) (not= (:kb e) (key-bytes k)))
                     (refuse 15 key "a key whose bytes are not its value's"))
                   (conj out [k {:kb (:kb e) :h (:h e) :vb (:vb e) :vr vr :v v}])))
               out data)])))

(defn- check-node
  "check-node*, once a read for a node at its place (*checked*: a node a value kept, where it was, is not checked
   again — its keys' hashes and its image are a map's whole cost: an atom's growing sets and maps were checked whole at
   every write's read)"
  [get-block key shift path root? set? out]
  (let [m *checked*
        mk (when m [key shift path root? set?])]
    (if-let [[rec es] (when m (get @m mk))]
      [rec (into out es)]
      (let [[rec es] (check-node* get-block key shift path root? set? [])]
        (when m (checked! m mk [rec es]))
        [rec (into out es)]))))

(defn- check-map
  "[the map (set? a set) whose root block's image under key is img, its record]"
  [get-block key img set?]
  (when-not (= ROOT-N (count img)) (refuse 15 key (str "a map root is " ROOT-N " bytes")))
  (let [cnt (b/read-u32le img 1)
        child (slot-band img 5)
        [root es] (if (empty-band? child) [nil []] (check-node get-block (b/hex child) 0 [] true set? []))
        n (if root (:n root) 0)]
    (when-not (= cnt n) (refuse 14 key (str "a map root counting " cnt " of " n " entries")))
    (let [v (if set? (into #{} (map first es)) (into {} (map (fn [[k e]] [k (:v e)]) es)))]
      (when-not (= n (count v)) (refuse 15 key "two entries of one key"))
      [v {:t (if set? :set :map) :band (b/unhex key) :n n :root root :by (into {} es) :v v :delta nil}])))

(defn- vcc-shift
  "the trie shift of t elements below the tail (vec_conj's): 0 for one leaf or none, else the least multiple of 5 with
   t ≤ 32 << shift"
  [t]
  (if (<= t 32) 0 (loop [s 5] (if (or (>= s 30) (<= t (* 32 (nth POW2 s)))) s (recur (+ s 5))))))

(defn- check-vnode
  "[the bands of the t elements under the vec node at key and shift sh, levels with its key and its descendants' (levels
   {level [key …]}, leaves at 0, each level left to right)]: strict (no relaxed flag, no pad), its occupancy t's share, each
   slot named, each child holding its share"
  [get-block key sh t levels]
  (let [img (vec (get-block key))]
    (need! img 9 key "a vec node's head")
    (when-not (= T-VEC (first img)) (refuse 15 key (str "tag " (first img) " where a vec node lies")))
    (when-not (zero? (nth img 1)) (refuse (if (odd? (nth img 1)) 17 15) key "a vec node's flags"))
    (when-not (every? zero? (subvec img 2 9)) (refuse 15 key "a vec node's pad"))
    (when-not (zero? (mod (- (count img) 9) SLOT)) (refuse 15 key "a vec node's slots"))
    (let [k (quot (- (count img) 9) SLOT)
          full (when (pos? sh) (* 32 (nth POW2 (- sh 5))))
          want (if (zero? sh) t (quot (+ t (dec full)) full))
          _ (when-not (= k want) (refuse 14 key (str "a vec node of " k " slots holding " t " elements")))
          bands (mapv (fn [i] (let [bd (band-at img (+ 9 (* SLOT i)))]
                                (when (empty-band? bd) (refuse 8 key "an empty slot in a vec node"))
                                bd))
                      (range k))
          levels (update levels (quot sh 5) (fn [lv] (conj (or lv []) key)))]   ;; levels: {level [key …]}
      (if (zero? sh)
        [bands levels]
        (reduce (fn [[out levels] [i bd]]
                  (let [[bs levels] (check-vnode get-block (b/hex bd) (- sh 5) (if (< i (dec k)) full (- t (* (dec k) full))) levels)]
                    [(into out bs) levels]))
                [[] levels] (map-indexed vector bands))))))

(defn- check-vec
  "[the vector whose root block's image under key is img, its record]: its tail length and shift the ones its count
   names, an empty tail / trie a zero slot, each node strict and holding its share"
  [get-block key img]
  (when-not (= VROOT-N (count img)) (refuse 15 key (str "a vec root is " VROOT-N " bytes")))
  (let [cnt (b/read-u32le img 1)
        sh (b/read-u32le img 5)
        tl (b/read-u32le img 9)
        t (if (zero? cnt) 0 (* 32 (quot (dec cnt) 32)))
        tail (slot-band img 17)
        trie (slot-band img (+ 17 SLOT))]
    (when-not (every? zero? (subvec img 13 17)) (refuse 15 key "a vec root's pad"))
    (when-not (= tl (- cnt t)) (refuse 14 key (str "a tail of " tl " where the count names " (- cnt t))))
    (when-not (= sh (vcc-shift t)) (refuse 15 key (str "a shift of " sh " where the count names " (vcc-shift t))))
    (when-not (= (zero? tl) (empty-band? tail)) (refuse 8 key "a tail slot that does not match its length"))
    (when-not (= (zero? t) (empty-band? trie)) (refuse 8 key "a trie slot that does not match the count"))
    (let [[tbs levels] (if (zero? t) [[] {}] (check-vnode get-block (b/hex trie) sh t {}))
          levels (mapv (fn [i] (get levels i [])) (range (inc (quot sh 5))))
          [lbs _] (if (zero? tl) [[] nil] (check-vnode get-block (b/hex tail) 0 tl {}))
          es (mapv (fn [bd] (let [[v vr] (check-band get-block bd)] {:vb bd :vr vr :v v})) (into tbs lbs))
          v (mapv :v es)]
      [v {:t :vec :band (b/unhex key) :n cnt :tailoff t :tail (when (pos? tl) (b/hex tail)) :trie (when (pos? t) (b/hex trie))
          :levels levels :es es :v v}])))

(defn- check-list
  "[the list whose root block's image under key is img, its record]: a chain of its count's nodes, each [tag][vlen 64]
   [next][the value's slot], each value checked"
  [get-block key img]
  (when-not (= ROOT-N (count img)) (refuse 15 key (str "a list root is " ROOT-N " bytes")))
  (let [cnt (b/read-u32le img 1)
        bds (loop [bd (slot-band img 5) out []]
              (if (empty-band? bd)
                out
                (let [k (b/hex bd) node (vec (get-block k))]
                  (when-not (and (= LNODE-N (count node)) (= T-LIST (first node)) (= CORE (b/read-u32le node 1)))
                    (refuse 15 k "a list node is [tag][64][next slot][the value's slot]"))
                  (when (>= (count out) cnt) (refuse 14 key "a list longer than its count"))
                  (let [vb (band-at node (+ 5 SLOT))]
                    (when (empty-band? vb) (refuse 8 k "a list node holding no value"))
                    (recur (slot-band node 5) (conj out vb))))))]
    (when-not (= cnt (count bds)) (refuse 14 key (str "a list of " (count bds) " nodes counting " cnt)))
    (let [v (apply list (map (fn [bd] (first (check-band get-block bd))) bds))] [v {:t :list :band (b/unhex key) :v v}])))

(defn- check-band
  "[the value a 32-byte band names, its record (a collection's; nil else)], every block under it checked canonical
   (once a read: *checked*)"
  [get-block bd]
  (let [s (scalar-of-band bd)]
    (if (not= s ::none)
      [s nil]
      (let [k (b/hex bd)
            m *checked*]
        (if-let [hit (when m (get @m k))]
          hit
          (let [img (vec (get-block k))
                v (check-value-image img k)
                r (cond (not= v ::none) [v nil]
                        (empty? img) (refuse 15 k "an empty block")
                        (= T-MAP (first img)) (let [[v r] (check-map get-block k img (set-key? k img))] [(with-attest v get-block k) r])
                        (= T-VEC (first img)) (let [[v r] (check-vec get-block k img)] [(with-attest v get-block k) r])
                        (= T-LIST (first img)) (check-list get-block k img)
                        :else (refuse 15 k (str "tag " (first img) " where a collection or a value lies")))]
            (if m (checked! m k r) r)))))))

(defn decode-rec
  "[the value a 32-byte band names, its record] — the value read through get-block (a key hex → its image), every block
   of it checked canonical first (throws ex-info {:type :non-canonical :reason code :key key}, CANON-REASONS); the
   record is the one encode-rec makes of the value, its keys the tree's"
  [bd get-block]
  (check-band get-block (vec bd)))

;; ── the keys an image names (a reader fetches a tree level by level: eve.net.atom) ───────────────────────────────

(defn- ref-band? [bd] (and (not (empty-band? bd)) (= ::none (scalar-of-band bd))))

(defn child-keys
  "the keys of the blocks the image names (its child collections and value blocks, by their bands); none for a value
   block"
  [image]
  (let [img (vec image)
        tag (first img)
        n (count img)
        bands (cond
                (not= ::none (value-of-image img)) []
                (= tag T-MAP) [(band-at img 5)]
                (= tag T-NODE) (let [dbm (b/read-u32le img 5) nbm (b/read-u32le img 9)
                                     cc (popcount nbm) dc (popcount dbm)]
                                 (concat (map (fn [i] (band-at img (+ 13 (* SLOT i)))) (range cc))
                                         (loop [i 0 p (+ 13 (* SLOT cc) (* 16 dc)) out []]
                                           (if (= i dc)
                                             out
                                             (let [kl (b/read-u32le img p) vl (b/read-u32le img (+ p 4 kl)) v (+ p 8 kl)]
                                               (if (= vl CORE)
                                                 (recur (inc i) (+ v SLOT) (conj out (band-at img v)))
                                                 (recur (inc i) (+ v vl) out)))))))
                (and (= tag T-VEC) (= n VROOT-N)) [(band-at img 17) (band-at img (+ 17 SLOT))]
                (= tag T-VEC) (map (fn [i] (band-at img (+ 9 (* SLOT i)))) (range (quot (- n 9) SLOT)))
                (and (= tag T-LIST) (= n ROOT-N)) [(band-at img 5)]
                (and (= tag T-LIST) (= n LNODE-N)) [(band-at img 5) (band-at img (+ 5 SLOT))]
                :else [])]
    (vec (distinct (map b/hex (filter ref-band? bands))))))

(defn band-of
  "the 32-byte band a slot carries for x, and x's blocks: [band {key image}] — a scalar's band names no block"
  [x]
  (let [[bd _ acc] (band-rec x nil nil {})] [bd acc]))

(defn value-of-band
  "the value a band names, its blocks read through get-block"
  [bd get-block]
  (decode-band get-block (vec bd)))

(defn scalar-band?
  "true when the 32-byte band images a scalar inline (eve.vs _slot_image tier 2: the band is the value; the slot points
   at a box of those bytes)"
  [bd]
  (not= ::none (scalar-of-band (vec bd))))

(defn set-band?
  "true when band names the image img as a SET root (its SHA-256 XOR 0xEE…)"
  [band img]
  (set-key? (b/hex band) img))

(defn f64-le "the 8 little-endian bytes of a double (IEEE 754, by arithmetic)" [x] (f64-bytes x))
(defn le-f64 "the double of 8 little-endian bytes" [bs] (bytes-f64 (vec bs)))

(defn text-value
  "the value a string-shaped payload names by its lead byte (the kernel's string form, A8): ':' a keyword, 0x01 a symbol,
   else the string"
  [payload]
  (let [u (vec payload)]
    (cond (and (seq u) (= 0x3A (first u))) (keyword (utf8-text (subvec u 1)))
          (and (seq u) (= 1 (first u))) (symbol (utf8-text (subvec u 1)))
          :else (utf8-text u))))
