(ns eve.net.snapshot
  "The atom's root blob (P5, eve/doc/network-atoms/WIRE.md §7) — `VSNP`: magic u32 LE 0x56534E50, entry count u32 LE,
   then per top-level entry [klen u32 LE][key][vlen u32 LE][value] — in portable cljc.

   CONSOLIDATION (trunk audit entries 4 + 7, Nov 14 2026): the serial form is the TRUNK's canonical
   bytes now. A scalar entry (and every key) is its eve.codec/value->eve-bytes inline — Eve's one
   value⇄bytes derivation; this file's hand-copied kernel scalar tags (the third derivation of the
   fast-tag layout) are deleted. A COLLECTION entry is a 256-byte reference by content (CID 0, inner
   0, VERSION @32, FLAGS ACTIVE @40, its key @80) whose key names the entry's trunk image in the
   store — the image is the value's value->eve-bytes, addressed by its SHA-256 as every net blob is.
   The reference VERSION is 3: version 2 named an eve.net.codec band root (a kernel seal graph); a
   reader meeting a v2 reference refuses it (:legacy-band-root) rather than misdecoding (see
   WIRE-FOLD-NOTE.md). Entries are ordered by their key's XXH3-128, so every host writes the same
   blob for a value. The root key is the hex SHA-256 of the blob.

   The whole snapshot is here, for every client of the atom (eve.net.atom, and VS's vs.net.remote-atom):
   `snapshot` writes one; `ref-keys` / `value-of` read one."
  (:require [eve.net.bytes :as b]
            [eve.codec :as ec]
            [eve.crypto :as prim]
            [eve.host :as host]))

(def MAGIC 0x56534E50)
(def REF-VERSION 3)

;; ── the byte bridge: eve.codec speaks the host's byte arrays; eve.net speaks byte vectors (0..255) ──

(defn arr->vec "a host byte array as a byte vector" [bs]
  (let [n (host/alength bs)] (mapv (fn [i] (bit-and (host/aget bs i) 0xFF)) (range n))))

(defn vec->arr "a byte vector as the host's byte array" [v]
  (let [a (host/byte-array (count v))]
    (doseq [i (range (count v))] (host/aset a i (host/unchecked-byte (nth v i))))
    a))

(defn trunk-bytes "the canonical trunk bytes of a value, as a byte vector" [v]
  (arr->vec (ec/value->eve-bytes v)))

(defn trunk-value "the value of canonical trunk bytes (a byte vector)" [bs]
  (ec/eve-bytes->value (vec->arr bs)))

;; ── references by content ──

(defn ref-slot
  "the 256-byte reference by content to the trunk image under key (64 hex)"
  [key-hex]
  (b/join [(repeat 32 0) (b/u32le REF-VERSION) (repeat 4 0) (b/u32le 1) (repeat 36 0) (b/unhex key-hex) (repeat 144 0)]))

(defn slot-key "the key a reference carries @80" [slot] (b/hex (subvec (vec slot) 80 112)))

(defn slot-version "the version a reference carries @32" [slot] (b/read-u32le (vec slot) 32))

;; ── serial forms ──

(defn serialize-scalar
  "the serial form of a key or a scalar value: its canonical trunk bytes (eve.codec/value->eve-bytes)"
  [v]
  (trunk-bytes v))

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
  "the value a serial form names, or [::ref key] for a reference by content (a 256-byte slot naming a
   trunk image). A reference of an older version (v2: an eve.net.codec band root) is refused, never
   misdecoded."
  [bs]
  (let [v (vec bs) n (count v)]
    (if (= 256 n)
      (let [ver (slot-version v)]
        (when (not= REF-VERSION ver)
          (throw (ex-info (str "snapshot: a version-" ver " reference names a codec band root; this reader reads trunk images (v3)")
                          {:type :legacy-band-root :version ver})))
        [::ref (slot-key v)])
      (trunk-value v))))

(defn ref? "is x a reference by content (unserialize's [::ref key])" [x] (and (vector? x) (= ::ref (first x))))

(defn- collection? [v] (or (map? v) (vector? v) (set? v) (seq? v)))

(defn- serialize
  "[the serial form of v, the trunk images a collection value needs ({img-key img})]"
  [v]
  (if (collection? v)
    (let [img (trunk-bytes v) k (b/hex (prim/sha256 img))] [(ref-slot k) {k img}])
    [(serialize-scalar v) {}]))

(defn snapshot
  "{:blob :key :blocks} of a map: its VSNP blob, the root key, the trunk images its collection values need"
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
  "the map a VSNP blob names, each collection decoded from its trunk image through get-block, a function of a key
   hex → its image (bytes the store addressed by that key: their SHA-256 is the key, checked by the fetch)"
  [blob get-block]
  (into {} (map (fn [[k v]] [k (if (ref? v) (trunk-value (get-block (second v))) v)]) (entries blob))))
