(ns flat-io-seam-bench
  "The eve.flat-io seam's micro-bench (lane U2b, doc/flat/lanes/U2b-flat-io-fast.md): whichever root is on the
   classpath — flat-io/portable, flat-io/jvm, flat-io/js — on a million u32 / i64 reads, a varint stream, a key-cmp
   binary search over 407 keys, a tuple's reduce and nth, short utf8 decodes and a million writer u32s. Every row is
   the best of 20 runs after a first, with a checksum so two roots' answers can be compared. Run by
   bench/flat-io/run-bench (JVM, Node, bb)."
  (:require [eve.flat-io :as fio]))

(defn- now-ns []
  #?(:clj (System/nanoTime)
     :cljs (js/Number (js/process.hrtime.bigint))))

(def ^:private host #?(:bb "bb" :clj "jvm" :cljs "node"))

;; bb interprets: a tenth of the iterations, the same per-op unit
(def ^:private N #?(:bb 100000 :default 1000000))

(defn- bench
  "Run f once, then 20 times; print the best, per op (ops per run)."
  [label ops f]
  (let [t0 (now-ns) r (f) first-ns (- (now-ns) t0)
        best (loop [i 0 b #?(:clj Long/MAX_VALUE :cljs js/Infinity)]
               (if (< i 20)
                 (let [t (now-ns) _ (f) dt (- (now-ns) t)] (recur (inc i) (min b dt)))
                 b))]
    (println "S" host label "ops" ops "first-us" (quot first-ns 1000) "best-us" (quot best 1000)
             "ns/op" (/ (Math/round (* 100.0 (/ best ops))) 100.0) "check" (str r))
    r))

;; Park–Miller, the same sequence on every host
(defn- rng [seed] (atom seed))
(defn- rnd [r n] (mod (swap! r (fn [x] (mod (* x 48271) 2147483647))) n))

(defn- host-bytes [n]
  #?(:clj (byte-array (int n)) :cljs (js/Uint8Array. n)))

(defn- random-chunk [r n]
  (let [w (fio/buf 4)]
    (dotimes [_ (quot n 4)] (fio/buf-u32 w (+ (* (rnd r 2) 2147483648) (rnd r 2147483647))))
    (fio/buf-chunk w n)))

(defn- key-order
  "Eve's key-byte order of two strings (the order a KMAP's keys are written in), from their UTF-8 bytes."
  [a b]
  (let [ua (fio/chunk-bytes (let [w (fio/buf 2)] (fio/buf-bytes w (#?(:clj .getBytes :cljs (fn [s _] (.encode (js/TextEncoder.) s))) a "UTF-8") 0 (count a)) (fio/buf-chunk w (count a))))
        ub (fio/chunk-bytes (let [w (fio/buf 2)] (fio/buf-bytes w (#?(:clj .getBytes :cljs (fn [s _] (.encode (js/TextEncoder.) s))) b "UTF-8") 0 (count b)) (fio/buf-chunk w (count b))))]
    ;; ASCII keys: the UTF-8 length is the string length, and below 256 the order is length, then bytes
    (if (not= (count a) (count b))
      (compare (count a) (count b))
      (fio/bytes-cmp (fio/chunk ua) 0 (count a) ub 0 (count b)))))

(defn run []
  (println "S" host "root" (str (:doc (meta (find-ns 'eve.flat-io)))))
  (let [r (rng 20261117)
        size (* 4 1024 1024)
        c (random-chunk r size)]
    ;; the loop the read rows run, without the read: what of a row is the loop
    (bench "loop-baseline" N
           (fn [] (loop [i 0 acc 0]
                    (if (< i N) (recur (inc i) (+ acc (bit-and (* i 4) 0x3FFFFC))) acc))))
    (bench "u32-read" N
           (fn [] (loop [i 0 acc 0]
                    (if (< i N) (recur (inc i) (+ acc (fio/u32 c (bit-and (* i 4) 0x3FFFFC)))) acc))))
    (bench "i64-read" N
           (fn [] (loop [i 0 acc #?(:clj 0 :cljs (js/BigInt 0))]
                    (if (< i N)
                      (recur (inc i) #?(:clj (unchecked-add acc (fio/i64 c (bit-and (* i 8) 0x3FFFF8)))
                                        :cljs (+ acc (fio/i64 c (bit-and (* i 8) 0x3FFFF8)))))
                      #?(:clj acc :cljs (.toString acc))))))
    ;; a varint stream (1-3-byte values), read as chunk.cljc reads node headers: value, then next
    (let [w (fio/buf 4)
          _ (dotimes [_ N] (fio/buf-varint w (case (rnd r 3) 0 (rnd r 128) 1 (rnd r 16384) 2 (rnd r 2097152))))
          vc (fio/buf-chunk w (fio/buf-pos w))]
      (bench "varint-read" N
             (fn [] (loop [p 0 k 0 acc 0]
                      (if (< k N) (recur (fio/varint-next vc p) (inc k) (+ acc (fio/varint-v vc p))) acc)))))
    ;; 407 keys in key order, stored back to back; a binary search for each (FLAT-IO.md key-cmp)
    (let [ks (vec (sort key-order (map #(str "Station-" %) (range 407))))
          w (fio/buf 2)
          spans (reduce (fn [acc k]
                          (let [p (fio/buf-pos w)
                                bs #?(:clj (.getBytes ^String k "UTF-8") :cljs (.encode (js/TextEncoder.) k))]
                            (fio/buf-bytes w bs 0 (count k))
                            (conj acc [p (count k)])))
                        [] ks)
          kc (fio/buf-chunk w (fio/buf-pos w))
          offs (mapv first spans) lens (mapv second spans)
          find (fn [k] (loop [lo 0 hi 406]
                         (if (<= lo hi)
                           (let [mid (quot (+ lo hi) 2)
                                 x (fio/key-cmp kc (nth offs mid) (nth lens mid) k)]
                             (cond (zero? x) mid (neg? x) (recur (inc mid) hi) :else (recur lo (dec mid))))
                           -1)))]
      (bench "key-cmp-search-x407" 407
             (fn [] (loop [i 0 acc 0] (if (< i 407) (recur (inc i) (+ acc (find (nth ks i)))) acc)))))
    ;; a 1024-element i32 column, reduced
    (let [tc (random-chunk r 8192)
          reps (quot N 1024)]
      (bench "tuple-reduce-i32x1024" (* reps 1024)
             (fn [] (loop [k 0 acc 0]
                      (if (< k reps) (recur (inc k) (+ acc (reduce + 0 (fio/tuple tc 64 1024 6)))) acc)))))
    ;; the 1BRC value shape: a 4-element i32 tuple made and its last element read
    (let [tc (random-chunk r 4096)
          reps (quot N 10)]
      (bench "tuple-make-nth-i32x4" reps
             (fn [] (loop [k 0 acc 0]
                      (if (< k reps)
                        (recur (inc k) (+ acc (nth (fio/tuple tc (* 16 (bit-and k 255)) 4 6) 3)))
                        acc)))))
    ;; key-sized utf8 decodes
    (let [w (fio/buf 2)
          _ (dotimes [i 256] (let [s (str "Station-" i)
                                   bs #?(:clj (.getBytes ^String s "UTF-8") :cljs (.encode (js/TextEncoder.) s))]
                               (fio/buf-u8 w (count s)) (fio/buf-bytes w bs 0 (count s))))
          sc (fio/buf-chunk w (fio/buf-pos w))
          reps (quot N 10)]
      (bench "utf8-key" reps
             (fn [] (loop [k 0 p 0 acc 0]
                      (if (< k reps)
                        (let [n (fio/u8 sc p) s (fio/utf8 sc (inc p) n) p2 (+ p 1 n)]
                          (recur (inc k) (if (>= p2 (fio/chunk-len sc)) 0 p2) (+ acc (count s))))
                        acc)))))
    (bench "buf-u32-write" N
           (fn [] (let [w (fio/buf 4)]
                    (loop [i 0] (when (< i N) (fio/buf-u32 w i) (recur (inc i))))
                    (fio/buf-pos w))))
    nil))

(defn -main [& _] (run) #?(:clj (shutdown-agents)))
(defn main [& _] (run))
