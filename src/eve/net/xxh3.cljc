(ns eve.net.xxh3
  "XXH3-128 (default secret, seed 0) — the VSv2 kernel's map key hash (VS's src/vs/kernel/wasm/xxhash.vs, ported path for
   path: len 0, 1–3, 4–8, 9–16, 17–128, 129–240, long), in portable cljc over eve.net.u64's [hi lo] words: the same
   code on the JVM, bb, CLJS and any Clojure-reading host (eve/doc/network-atoms/IMPL-PLAN.md §6.0, §6.1, §9 A11). The
   digest is 16 bytes, big-endian, hi then lo — as the kernel writes it. A host may override it with a faster
   implementation through its platform map; the KATs and the fixture corpus's key pairs hold every override to this.

   Input: a byte sequence (anything `nth` and `count` read as 0..255)."
  (:require [eve.net.u64 :as u]))

(def P32-1 [0 2654435761])
(def P32-2 [0 2246822519])
(def P32-3 [0 3266489917])
(def P64-1 [2654435761 2246822535])        ;; 0x9E3779B185EBCA87
(def P64-2 [3266489917 668265295])         ;; 0xC2B2AE3D27D4EB4F
(def P64-3 [374761393 2654435833])         ;; 0x165667B19E3779F9
(def P64-4 [2246822519 3266489955])        ;; 0x85EBCA77C2B2AE63
(def P64-5 [668265263 374761413])          ;; 0x27D4EB2F165667C5
(def PMX1 [374761361 2654435833])          ;; 0x165667919E3779F9
(def PMX2 [2679250021 513335077])          ;; 0x9FB21C651E98DF25

;; the canonical 192-byte kSecret: the 48 little-endian i32 words the kernel stores (xxhash.vs init_secret_body)
(def ^:private SECRET-WORDS
  [963444408 -1102339037 746652028 481108471 -378678050 -610824061 -1532739470 526889911 1323727307 2028323020
   2110806658 561184716 1950746808 -1910225929 -426756640 1277573761 -1152243652 -889142383 -1956261752 -1557245157
   -1756863375 1324944802 1190074680 -659759447 1064762024 1060412643 -943989511 491719623 1272992138 827962573
   -914448440 1685289177 -2085829142 -1007955148 -6258235 -345828358 1373441303 1239085239 643110611 -1637297111
   1488852523 -56539267 -776406897 -835596166 -1891972283 671356565 -889464913 2118142907])

(def SECRET
  (vec (mapcat (fn [w] (let [x (u/u32 w)] [(mod x 256) (mod (quot x 256) 256) (mod (quot x 65536) 256) (quot x 16777216)]))
               SECRET-WORDS)))

(defn- s64 [o] (u/from-le SECRET o))
(defn- s32 [o] [0 (u/u32-le SECRET o)])
(defn- r64 [bs o] (u/from-le bs o))
(defn- r32 [bs o] [0 (u/u32-le bs o)])

(defn- avalanche64 "XXH64's finalizer" [h]
  (let [h (u/xor h (u/shr h 33))
        h (u/mul h P64-2)
        h (u/xor h (u/shr h 29))
        h (u/mul h P64-3)]
    (u/xor h (u/shr h 32))))

(defn- avalanche "XXH3's" [h]
  (let [h (u/xor h (u/shr h 37))
        h (u/mul h PMX1)]
    (u/xor h (u/shr h 32))))

(defn- fold64 [a b] (let [[hi lo] (u/mul128 a b)] (u/xor lo hi)))

(defn- mix16 [bs i so]
  (fold64 (u/xor (r64 bs i) (s64 so)) (u/xor (r64 bs (+ i 8)) (s64 (+ so 8)))))

(defn- mix32 "[lo hi] after one 32-byte mix" [[lo hi] bs i1 i2 so]
  (let [lo (u/add lo (mix16 bs i1 so))
        lo (u/xor lo (u/add (r64 bs i2) (r64 bs (+ i2 8))))
        hi (u/add hi (mix16 bs i2 (+ so 16)))
        hi (u/xor hi (u/add (r64 bs i1) (r64 bs (+ i1 8))))]
    [lo hi]))

(defn- len0 [] [(avalanche64 (u/xor (s64 80) (s64 88))) (avalanche64 (u/xor (s64 64) (s64 72)))])

(defn- len1to3 [bs n]
  (let [c1 (nth bs 0) c2 (nth bs (quot n 2)) c3 (nth bs (dec n))
        cl (u/u32 (+ (* c1 65536) (* c2 16777216) c3 (* n 256)))
        ch (u/rotl32 (u/bswap32 cl) 13)
        lo (avalanche64 (u/xor [0 cl] (u/xor (s32 0) (s32 4))))
        hi (avalanche64 (u/xor [0 ch] (u/xor (s32 8) (s32 12))))]
    [hi lo]))

(defn- len4to8 [bs n]
  (let [in-lo (u/u32-le bs 0)
        in-hi (u/u32-le bs (- n 4))
        keyed (u/xor [in-hi in-lo] (u/xor (s64 16) (s64 24)))
        [mh ml] (u/mul128 keyed (u/add P64-1 (u/from-int (* n 4))))
        mh (u/add mh (u/shl ml 1))
        ml (u/xor ml (u/shr mh 3))
        ml (u/xor ml (u/shr ml 35))
        ml (u/mul ml PMX2)
        ml (u/xor ml (u/shr ml 28))]
    [(avalanche mh) ml]))

(defn- len9to16 [bs n]
  (let [in-lo (r64 bs 0)
        in-hi (r64 bs (- n 8))
        bfl (u/xor (s64 32) (s64 40))
        bfh (u/xor (s64 48) (s64 56))
        [mh ml] (u/mul128 (u/xor (u/xor in-lo in-hi) bfl) P64-1)
        ml (u/add ml (u/shl (u/from-int (dec n)) 54))
        in-hi (u/xor in-hi bfh)
        mh (u/add mh (u/add in-hi (u/mul [0 (second in-hi)] [0 (dec (second P32-2))])))
        ml (u/xor ml (u/bswap64 mh))
        [hh hl] (u/mul128 ml P64-2)
        hh (u/add hh (u/mul mh P64-2))]
    [(avalanche hh) (avalanche hl)]))

(defn- finish-mid "h_lo / h_hi of the 17–240 paths" [[lo hi] n]
  [(u/add lo hi)
   (u/add (u/add (u/mul lo P64-1) (u/mul hi P64-4)) (u/mul (u/from-int n) P64-2))])

(defn- len17to128 [bs n]
  (let [acc [(u/mul (u/from-int n) P64-1) u/ZERO]
        acc (if (> n 32)
              (let [acc (if (> n 64)
                          (let [acc (if (> n 96) (mix32 acc bs 48 (- n 64) 96) acc)]
                            (mix32 acc bs 32 (- n 48) 64))
                          acc)]
                (mix32 acc bs 16 (- n 32) 32))
              acc)
        acc (mix32 acc bs 0 (- n 16) 0)
        [hl hh] (finish-mid acc n)]
    [(u/neg (avalanche hh)) (avalanche hl)]))

(defn- len129to240 [bs n]
  (let [acc (reduce (fn [acc i] (mix32 acc bs (- i 32) (- i 16) (- i 32)))
                    [(u/mul (u/from-int n) P64-1) u/ZERO] (u/steps 32 160 32))
        acc [(avalanche (first acc)) (avalanche (second acc))]
        acc (reduce (fn [acc i] (mix32 acc bs (- i 32) (- i 16) (- i 157)))
                    acc (u/steps 160 (inc n) 32))
        acc (mix32 acc bs (- n 16) (- n 32) 103)
        [hl hh] (finish-mid acc n)]
    [(u/neg (avalanche hh)) (avalanche hl)]))

(defn- accumulate-512 [acc bs i so]
  (reduce (fn [acc k]
            (let [dv (r64 bs (+ i (* 8 k)))
                  dk (u/xor dv (s64 (+ so (* 8 k))))
                  acc (update acc (bit-xor k 1) u/add dv)]
              (update acc k u/add (u/mul [0 (second dk)] [0 (first dk)]))))
          acc (range 8)))

(defn- scramble [acc]
  (mapv (fn [k a] (let [a (u/xor a (u/shr a 47))
                        a (u/xor a (s64 (+ 128 (* 8 k))))]
                    (u/mul a P32-1)))
        (range 8) acc))

(defn- merge-accs [acc so start]
  (avalanche (reduce (fn [r k] (u/add r (fold64 (u/xor (nth acc (* 2 k)) (s64 (+ so (* 16 k))))
                                                (u/xor (nth acc (inc (* 2 k))) (s64 (+ so (* 16 k) 8))))))
                     start (range 4))))

(defn- hash-long [bs n]
  (let [blocks (quot (dec n) 1024)
        acc (reduce (fn [acc b]
                      (scramble (reduce (fn [acc s] (accumulate-512 acc bs (+ (* b 1024) (* s 64)) (* s 8))) acc (range 16))))
                    [P32-3 P64-1 P64-2 P64-3 P64-4 P32-2 P64-5 P32-1] (range blocks))
        stripes (quot (- (dec n) (* blocks 1024)) 64)
        acc (reduce (fn [acc s] (accumulate-512 acc bs (+ (* blocks 1024) (* s 64)) (* s 8))) acc (range stripes))
        acc (accumulate-512 acc bs (- n 64) 121)
        lo (merge-accs acc 11 (u/mul (u/from-int n) P64-1))
        hi (merge-accs acc 117 (u/not* (u/mul (u/from-int n) P64-2)))]
    [hi lo]))

(defn xxh3-128-words
  "[hi lo] (each [hi32 lo32]) of the bytes"
  [bs]
  (let [n (count bs)]
    (cond (zero? n) (len0)
          (<= n 3) (len1to3 bs n)
          (<= n 8) (len4to8 bs n)
          (<= n 16) (len9to16 bs n)
          (<= n 128) (len17to128 bs n)
          (<= n 240) (len129to240 bs n)
          :else (hash-long bs n))))

(defn xxh3-128
  "the 16-byte digest (big-endian, hi then lo, as the kernel writes it) as a vector of 0..255"
  [bs]
  (let [[hi lo] (xxh3-128-words (vec bs))] (into (u/be-bytes hi) (u/be-bytes lo))))

(defn xxh3-128-hex "the digest as 32 lower-case hex digits" [bs]
  (let [[hi lo] (xxh3-128-words (vec bs))] (str (u/hex hi) (u/hex lo))))
