(ns eve.net.graft
  "SwapGraft V3 and the read Bloom (P7; eve/doc/network-atoms/WIRE.md §7) in portable
   cljc — the format of vs.net.graft (VS's src/vs/net/graft.vs) and VSv1's 0621c9989:src/vs/eve/graft.js verbatim:
   `GRFT` V3, little-endian — magic u32 0x47524654 · version u8 3 · flags u8 0 · epoch u32 · timestamp u64 (lo, hi) ·
   6 reserved · old root SHA · new root SHA · branch / blobs / retired counts (u32 each) · the three SHA lists.
   The Bloom: 512 bytes, a SHA's four positions the u32 LE at its bytes 0, 4, 8, 12, each mod 4,096."
  (:require [eve.net.bytes :as b]))

(def MAGIC 0x47524654)
(def VERSION 3)
(def PREFIX 100)
(def BLOOM-BITS 4096)
(def BLOOM-BYTES 512)

(defn- sha-bytes [h]
  (cond (nil? h) (vec (repeat 32 0))
        (= 64 (count h)) (b/unhex h)
        :else (throw (ex-info (str "graft: a SHA is 64 hex digits, not " (pr-str h)) {:sha h}))))

(defn encode
  "the GRFT V3 bytes of {:old-root :new-root (64 hex) :epoch :timestamp-ms :branch [hex…] :blobs [hex…] :retired [hex…]}"
  [{:keys [old-root new-root epoch timestamp-ms branch blobs retired]}]
  (let [ts (or timestamp-ms 0)]
    (b/join (concat [(b/u32le MAGIC) [VERSION 0] (b/u32le (or epoch 0))
                     (b/u32le (mod ts 4294967296)) (b/u32le (quot ts 4294967296)) (repeat 6 0)
                     (sha-bytes old-root) (sha-bytes new-root)
                     (b/u32le (count branch)) (b/u32le (count blobs)) (b/u32le (count retired))]
                    (map sha-bytes branch) (map sha-bytes blobs) (map sha-bytes retired)))))

(defn decode
  "{:version :flags :epoch :timestamp :old-root :new-root :branch :blobs :retired} of GRFT V3 bytes; throws on a short
   input, a bad magic, another version or short SHA lists"
  [bs]
  (let [v (vec bs) n (count v)]
    (when (< n PREFIX) (throw (ex-info (str "graftV3: truncated input (" n " < " PREFIX ")") {:len n})))
    (when (not= MAGIC (b/read-u32le v 0)) (throw (ex-info "graftV3: bad magic" {:magic (b/read-u32le v 0)})))
    (when (not= VERSION (nth v 4)) (throw (ex-info (str "graftV3: expected version " VERSION ", got " (nth v 4)) {})))
    (let [nb (b/read-u32le v 88) nl (b/read-u32le v 92) nr (b/read-u32le v 96)
          need (+ PREFIX (* 32 (+ nb nl nr)))
          sha (fn [o] (b/hex (subvec v o (+ o 32))))
          shas (fn [from k] (mapv (fn [i] (sha (+ from (* 32 i)))) (range k)))]
      (when (< n need) (throw (ex-info (str "graftV3: short SHA lists (" n " < " need ")") {:len n :need need})))
      {:version VERSION :flags (nth v 5) :epoch (b/read-u32le v 6)
       :timestamp (+ (b/read-u32le v 10) (* 4294967296 (b/read-u32le v 14)))
       :old-root (sha 24) :new-root (sha 56)
       :branch (shas PREFIX nb) :blobs (shas (+ PREFIX (* 32 nb)) nl) :retired (shas (+ PREFIX (* 32 (+ nb nl))) nr)})))

(defn- positions [sha-hex]
  (let [s (b/unhex sha-hex)] (mapv (fn [i] (mod (b/read-u32le s (* 4 i)) BLOOM-BITS)) (range 4))))

(defn bloom "a 512-byte Bloom with the SHAs (64 hex) added" [shas]
  (let [on (set (mapcat positions shas))]
    (mapv (fn [byte-i] (reduce (fn [acc bit] (if (contains? on (+ (* 8 byte-i) bit)) (+ acc (nth [1 2 4 8 16 32 64 128] bit)) acc))
                               0 (range 8)))
          (range BLOOM-BYTES))))

(defn might-contain? "may the Bloom hold the SHA — all four of its bits set" [bl sha-hex]
  (every? (fn [h] (odd? (quot (nth bl (quot h 8)) (nth [1 2 4 8 16 32 64 128] (mod h 8))))) (positions sha-hex)))

(defn possible?
  "graftPossibleV3: the loser's read Bloom (nil: none recorded) holds none of the winner's branch and retired keys"
  [reads winner]
  (or (nil? reads) (not (some (fn [k] (might-contain? reads k)) (concat (:branch winner) (:retired winner))))))
