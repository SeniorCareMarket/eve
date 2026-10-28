(ns eve.net.snapshot
  "The atom's root blob (P5, eve/doc/network-atoms/WIRE.md §7) — `VSNP`: magic u32 LE 0x56534E50, entry count u32 LE,
   then per top-level entry [klen u32 LE][key][vlen u32 LE][value] — in portable cljc, the framing and the kernel's
   scalar serial form (vs/src/vs/net/snapshot.vs, vs/src/vs/kernel/wasm/eve.vs serialize_value): nil is empty; false /
   true / int32 / float64 / a string-shaped value are [0xEE 0xDB][tag][payload] (tags 1 2 3 4; 5 a short string, u8
   length; 6 a long one, u32 LE length; a keyword's bytes lead with ':', a symbol's with 0x01). A COLLECTION value is a
   256-byte reference by content (CID 0, inner 0, VERSION 2 @32, FLAGS ACTIVE @40, its key @80) — eve.net.codec's
   blocks carry it. A long outside int32 is tag 7, its 8 bytes little-endian two's complement (EM6, 2026-10-19: the
   kernel's serial form had no stored form for it, and every writer refused it). The root key is the hex SHA-256 of
   the blob.

   The whole snapshot is here, for every client of the atom (eve.net.atom, and VS's vs.net.remote-atom): `snapshot`
   writes one — entries ordered by their key's XXH3-128, so every host writes the same blob for a value — and
   `ref-keys` / `value-of` read one."
  (:require [eve.net.bytes :as b]
            [eve.net.codec :as codec]
            [eve.crypto :as prim]))

(def MAGIC 0x56534E50)

(defn ref-slot
  "the 256-byte reference by content to the block under key (64 hex)"
  [key-hex]
  (b/join [(repeat 32 0) (b/u32le 2) (repeat 4 0) (b/u32le 1) (repeat 36 0) (b/unhex key-hex) (repeat 144 0)]))

(defn slot-key "the key a reference carries @80" [slot] (b/hex (subvec (vec slot) 80 112)))

(defn- tagged [tag payload] (b/join [[0xEE 0xDB tag] payload]))

(defn- string-form [payload]
  (let [n (count payload)]
    (if (<= n 255) (tagged 5 (into [n] payload)) (tagged 6 (into (b/u32le n) payload)))))

(defn- int32 [n] (b/u32le (mod n 4294967296)))

(def ^:private TAG-LONG 7)
(def ^:private POW32 4294967296)

(defn- int64-le "the 8 little-endian bytes of the integer n (two's complement), by arithmetic" [n]
  (let [lo (mod n POW32) hi (mod (quot (- n lo) POW32) POW32)]
    (into (b/u32le lo) (b/u32le hi))))

(defn- le-int64 "the integer of 8 little-endian two's-complement bytes" [bs]
  (let [lo (b/read-u32le bs 0) hi (b/read-u32le bs 4)]
    (+ lo (* POW32 (if (>= hi 2147483648) (- hi POW32) hi)))))

(defn serialize-scalar
  "the serial form of a scalar (nil, a boolean, an integer, a string, a keyword, a symbol); a double needs the host's
   bits — `float64-bits` (fn [d] [lo32 hi32]); an integer in int32 is tag 3, past it tag 7"
  [v float64-bits]
  (cond (nil? v) []
        (false? v) (tagged 1 [])
        (true? v) (tagged 2 [])
        (keyword? v) (string-form (b/utf8 (str v)))
        (symbol? v) (string-form (into [1] (b/utf8 (str v))))
        (string? v) (string-form (b/utf8 v))
        (integer? v) (if (<= -2147483648 v 2147483647)
                       (tagged 3 (int32 v))
                       (tagged TAG-LONG (int64-le v)))
        (number? v) (let [[lo hi] (float64-bits v)] (tagged 4 (into (b/u32le lo) (b/u32le hi))))
        :else (throw (ex-info (str "snapshot: not serializable: " (pr-str v)) {:value v}))))

(defn frame
  "the VSNP blob of entries [[key-bytes value-bytes] …]"
  [entries]
  (b/join (concat [(b/u32le MAGIC) (b/u32le (count entries))]
                  (mapcat (fn [[k v]] [(b/u32le (count k)) k (b/u32le (count v)) v]) entries))))

(defn unframe
  "the [[key-bytes value-bytes] …] of a VSNP blob; throws on a bad magic or a short blob"
  [blob]
  (let [v (vec blob) n (count v)]
    (when (or (< n 8) (not= MAGIC (b/read-u32le v 0)))
      (throw (ex-info "snapshot: not a VSNP blob" {:len n})))
    (loop [i 8 k (b/read-u32le v 4) out []]
      (if (zero? k)
        out
        (let [kl (b/read-u32le v i) ke (+ i 4 kl) vl (b/read-u32le v ke) ve (+ ke 4 vl)]
          (when (> ve n) (throw (ex-info "snapshot: short blob" {:len n :need ve})))
          (recur ve (dec k) (conj out [(subvec v (+ i 4) ke) (subvec v (+ ke 4) ve)])))))))

(defn unserialize
  "the scalar a serial form names, or [::ref key] for a reference by content (a 256-byte slot)"
  [bs]
  (let [v (vec bs)
        n (count v)]
    (cond
      (zero? n) nil
      (= 256 n) [::ref (slot-key v)]
      (not (and (= 0xEE (nth v 0)) (= 0xDB (nth v 1)))) (throw (ex-info "snapshot: not a serial form" {:bytes v}))
      :else
      (let [tag (nth v 2)]
        (cond (= 1 tag) false
              (= 2 tag) true
              (= 3 tag) (let [u (b/read-u32le v 3)] (if (>= u 2147483648) (- u 4294967296) u))
              (= 4 tag) (codec/le-f64 (subvec v 3 11))
              (= 5 tag) (codec/text-value (subvec v 4 (+ 4 (nth v 3))))
              (= 6 tag) (codec/text-value (subvec v 7 (+ 7 (b/read-u32le v 3))))
              (= TAG-LONG tag) (le-int64 (subvec v 3 11))
              :else (throw (ex-info (str "snapshot: an unknown serial tag " tag) {:tag tag})))))))

(defn ref? "is x a reference by content (unserialize's [::ref key])" [x] (and (vector? x) (= ::ref (first x))))

(defn- f64-bits [d] (let [bs (codec/f64-le d)] [(b/read-u32le bs 0) (b/read-u32le bs 4)]))

(defn- collection? [v] (or (map? v) (vector? v) (set? v) (seq? v)))

(defn- serialize
  "[the serial form of v, the blocks a collection value needs]"
  [v]
  (if (collection? v)
    (let [[bd blocks] (codec/band-of v)] [(ref-slot (b/hex bd)) blocks])
    [(serialize-scalar v f64-bits) {}]))

(defn snapshot
  "{:blob :key :blocks} of a map: its VSNP blob, the root key, the blocks its collection values need"
  [m]
  (when-not (map? m)
    (throw (ex-info (str "snapshot: the atom's value must be a map, not " (pr-str m)) {:type :not-a-map :value m})))
  (let [parts (map (fn [[k v]] (let [[kb _] (serialize k) [vb blocks] (serialize v)] {:kb kb :vb vb :blocks blocks})) m)
        entries (map (fn [p] [(:kb p) (:vb p)]) (sort-by (fn [p] (b/hex (prim/xxh3-128 (:kb p)))) parts))
        blob (frame entries)]
    {:blob blob :key (b/hex (prim/sha256 blob)) :blocks (apply merge {} (map :blocks parts))}))

(defn entries
  "the [key value] pairs of a VSNP blob, each scalar read and each collection [::ref key]; none for an empty or short
   blob"
  [blob]
  (if (and (seq blob) (<= 8 (count blob)))
    (map (fn [[kb vb]] [(unserialize kb) (unserialize vb)]) (unframe blob))
    []))

(defn ref-keys "the keys of the collections a VSNP blob's entries name: the roots a reader fetches" [blob]
  (keep (fn [[_ v]] (when (ref? v) (second v))) (entries blob)))

(defn value-of
  "the map a VSNP blob names, each collection decoded (eve.net.codec) through get-block, a function of a key hex →
   its image"
  [blob get-block]
  (into {} (map (fn [[k v]] [k (if (ref? v) (codec/value-of-band (b/unhex (second v)) get-block) v)]) (entries blob))))
