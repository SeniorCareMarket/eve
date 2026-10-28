;; examples/seam_bench.clj — the cost of the crypto, int32 and thread seams on one classpath (doc/adopting-eve-fast.md):
;;   bb -cp "$(bb classpath bb)" examples/seam_bench.clj          # babashka as shipped (crypto/java, int32/jvm, thread/jvm)
;;   bb -cp "$(bb classpath bb-minimal)" examples/seam_bench.clj  # the inbuilt seams (crypto/portable, int32/portable, thread/none)
;; One process, one classpath: times eve.crypto, eve.int32 (through eve.hamt-util's Murmur3) and eve.thread-host
;; as whatever adapters the classpath carries. Median of 5 runs after 2 warm-ups, milliseconds.
(require '[eve.crypto :as c] '[eve.hamt-util :as hu] '[eve.thread-host :as th])

(defn median-ms [f]
  (dotimes [_ 2] (f))
  (let [ts (sort (for [_ (range 5)]
                   (let [t0 (System/nanoTime)] (f) (/ (- (System/nanoTime) t0) 1e6))))]
    (nth ts 2)))

(def kib16 (vec (map #(mod (* 31 %) 256) (range 16384))))
(def msg200 (vec (map #(mod (* 7 %) 256) (range 200))))
(def seed (vec (range 32)))
(def pub (c/ed25519-public-key seed))
(def sig (c/ed25519-sign seed msg200))
(def key32 (vec (range 32)))
(def iv16 (vec (range 16)))
(def keys-bytes (mapv #(.getBytes (str "key-" %) "UTF-8") (range 10000)))

(defn row [label f] (println (format "%-40s %10.2f ms" label (double (median-ms f)))))

(println "driving:" (th/driving))
(row "sha256 16 KiB" #(c/sha256 kib16))
(row "aes256-ctr 16 KiB" #(c/aes256-ctr key32 iv16 kib16))
(row "xxh3-128 16 KiB" #(c/xxh3-128 kib16))
(row "ed25519-sign 200 B" #(c/ed25519-sign seed msg200))
(row "ed25519-verify 200 B" #(c/ed25519-verify pub msg200 sig))
(row "murmur3 10,000 keys (eve.int32)" #(doseq [b keys-bytes] (hu/portable-hash-bytes b)))
(println "verify ok:" (c/ed25519-verify pub msg200 sig))
