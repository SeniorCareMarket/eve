;; doc/jvm-heap-grade/bench/hotpath.clj — STEP 3: the slab access path itself. JVM seat, ONE session.
;;
;; Two arms per group, in this one JVM, interleaved:
;;   step3 — the code on the branch (plain heap-byte node reads, primitive hash / bit helpers)
;;   step2 — the step-2 read path, restored by alter-var-root for the duration of the arm:
;;           eve.deftype-proto.alloc/node-u8, node-i32 and node-bytes-equal? become the ISlabIO ops they replaced
;;           (-sio-read-u8, -sio-read-i32, slab/bytes-equal-at? — exactly what map/vec/deftype fields called at
;;           step 2), node-bytes becomes -sio-read-bytes, node-heap-array answers nil (no in-place node scan),
;;           eve.map/kv-pos becomes step 2's scan (a closure over a loop, allocated and boxed per call), and
;;           eve.hamt-util's portable-hash-bytes / bit helpers become verbatim copies of their step-2
;;           (boxed) bodies over verbatim copies of step-2's eve.int32. The call boundaries stay step 3's (a
;;           primitive call into a wrapper that calls the boxed body); the per-call work is step 2's.
;; Each round runs every group once, both arms back to back, the arm order alternating by round; 5 rounds; each cell is
;; that round's min-of-3-batch ns/op; bytes/op (ThreadMXBean) of the last batch of the last round.
;; Checksums: every group's read is run under both arms on fixed keys and compared (must be =).
;; Run: java --add-opens java.base/java.lang=ALL-UNNAMED -cp <deps.edn :paths>:clojure jars clojure.main doc/jvm-heap-grade/bench/hotpath.clj
(ns hg.hotpath
  (:require [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.guard :as g]
            [eve.slab-io :as slab]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.hamt-util :as hu]
            [eve.codec :as codec]
            [eve.map :as em]))

(def ^com.sun.management.ThreadMXBean tmx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn alloc-bytes ^long [] (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn calibrate ^long [f]
  (dotimes [_ 3] (f))
  (let [t0 (System/nanoTime)] (dotimes [_ 5] (f))
       (let [per (/ (double (- (System/nanoTime) t0)) 5)]
         (long (max 5 (min 2000000 (/ 4e7 per)))))))   ;; ~40 ms per batch

(defn measure
  "[min ns/op over 3 batches, bytes/op of the last batch]"
  [f n]
  (dotimes [_ n] (f))
  (let [rs (vec (for [_ (range 3)]
                  (let [b0 (alloc-bytes) t0 (System/nanoTime)]
                    (dotimes [_ n] (f))
                    [(/ (double (- (System/nanoTime) t0)) n) (/ (double (- (alloc-bytes) b0)) n)])))]
    [(apply min (map first rs)) (second (last rs))]))

(defn fmt [ns] (cond (> ns 1e6) (format "%.2f ms" (/ ns 1e6)) (> ns 1e3) (format "%.2f µs" (/ ns 1e3)) :else (format "%.1f ns" ns)))
(defn fmtb [b] (cond (> b 1e6) (format "%.1f MB" (/ b 1e6)) (> b 1e3) (format "%.1f KB" (/ b 1e3)) :else (format "%.0f B" b)))

;; ---------------------------------------------------------------------------
;; The step-2 arm: verbatim step-2 bodies (eve.int32 @ 8bf50a2, eve.hamt-util @ 8bf50a2)
;; ---------------------------------------------------------------------------
(defn s2-i32 [x] (long (unchecked-int x)))
(defn s2-imul [a b] (long (unchecked-multiply-int (unchecked-int a) (unchecked-int b))))
(defn s2-rotl [x r] (let [x (bit-and x 0xFFFFFFFF)] (long (unchecked-int (bit-or (bit-shift-left x r) (unsigned-bit-shift-right x (- 32 r)))))))
(defn s2-ushr [x n] (unsigned-bit-shift-right (bit-and x 0xFFFFFFFF) n))
(defn s2-popcount [x] (Long/bitCount (bit-and x 0xFFFFFFFF)))
(defn- s2-ubyte [buf i] (bit-and (aget ^bytes buf i) 0xFF))
(defn s2-portable-hash-bytes [buf]
  (let [len     (alength ^bytes buf)
        nblocks (s2-ushr len 2)]
    (loop [i 0, h 0]
      (if (< i nblocks)
        (let [j (* i 4)
              k (bit-or (bit-or (s2-ubyte buf j)
                                (bit-shift-left (s2-ubyte buf (+ j 1)) 8))
                        (bit-or (bit-shift-left (s2-ubyte buf (+ j 2)) 16)
                                (bit-shift-left (s2-ubyte buf (+ j 3)) 24)))
              k (-> k (s2-imul 0xcc9e2d51) (s2-rotl 15) (s2-imul 0x1b873593))
              h (bit-xor h k)
              h (-> h (s2-rotl 13) (s2-imul 5))
              h (+ h 0xe6546b64)]
          (recur (inc i) (s2-i32 h)))
        (let [t (* nblocks 4)
              r (bit-and len 3)
              k (cond (== r 3) (bit-or (bit-or (s2-ubyte buf t)
                                               (bit-shift-left (s2-ubyte buf (+ t 1)) 8))
                                       (bit-shift-left (s2-ubyte buf (+ t 2)) 16))
                      (== r 2) (bit-or (s2-ubyte buf t)
                                       (bit-shift-left (s2-ubyte buf (+ t 1)) 8))
                      (== r 1) (s2-ubyte buf t)
                      :else    0)
              h (if (pos? r)
                  (bit-xor h (-> k (s2-imul 0xcc9e2d51) (s2-rotl 15) (s2-imul 0x1b873593)))
                  h)
              h (bit-xor h len)
              h (bit-xor h (s2-ushr h 16))
              h (s2-imul h 0x85ebca6b)
              h (bit-xor h (s2-ushr h 13))
              h (s2-imul h 0xc2b2ae35)
              h (bit-xor h (s2-ushr h 16))]
          (s2-i32 h))))))
(defn s2-popcount32 [n] (s2-popcount n))
(defn s2-mask-hash [kh shift] (bit-and (s2-ushr kh shift) 0x1f))
(defn s2-bitpos [kh shift] (bit-shift-left 1 (s2-mask-hash kh shift)))
(defn s2-has-bit? [bitmap bit] (not (zero? (bit-and bitmap bit))))
(defn s2-node-bit [dbm nbm kh shift]
  (let [bit (s2-bitpos kh shift)]
    (if (and (== shift 30) (neg? kh)
             (not (s2-has-bit? dbm bit)) (not (s2-has-bit? nbm bit)))
      (let [old (bit-shift-left bit 28)]
        (if (or (s2-has-bit? dbm old) (s2-has-bit? nbm old)) old bit))
      bit)))
(defn s2-get-index [bitmap bit] (s2-popcount32 (bit-and bitmap (dec bit))))

(def step2-roots
  {#'alloc/node-u8           (fn ^long [sio ^long o ^long f] (long (alloc/-sio-read-u8 sio o f)))
   #'alloc/node-i32          (fn ^long [sio ^long o ^long f] (long (alloc/-sio-read-i32 sio o f)))
   #'alloc/node-bytes-equal? (fn [sio ^long o ^long f b] (slab/bytes-equal-at? sio o f b))
   #'alloc/node-bytes        (fn [sio ^long o ^long f ^long n] (alloc/-sio-read-bytes sio o f n))
   #'alloc/node-heap-array   (fn [sio ^long o] nil)   ;; step 2 had no in-place node scan
   ;; step 2's scan: the loop sat in a let binding, so it compiled to a closure, allocated and boxed per lookup
   #'em/kv-pos               (fn ^long [sio ^long off ^long kvs ^long di]
                               (long ((fn [] (loop [i 0 p kvs]
                                               (if (== i di) p
                                                   (recur (inc i) (.invokePrim ^clojure.lang.IFn$OLLL (deref #'em/skip-kv-at) sio off p))))))))
   #'hu/portable-hash-bytes  (fn ^long [buf] (long (s2-portable-hash-bytes buf)))
   #'hu/popcount32           (fn ^long [^long n] (long (s2-popcount32 n)))
   #'hu/mask-hash            (fn ^long [^long kh ^long sh] (long (s2-mask-hash kh sh)))
   #'hu/bitpos               (fn ^long [^long kh ^long sh] (long (s2-bitpos kh sh)))
   #'hu/has-bit?             (fn [^long bm ^long bit] (s2-has-bit? bm bit))
   #'hu/node-bit             (fn ^long [^long dbm ^long nbm ^long kh ^long sh] (long (s2-node-bit dbm nbm kh sh)))
   #'hu/get-index            (fn ^long [^long bm ^long bit] (long (s2-get-index bm bit)))})
(def step3-roots (into {} (map (fn [v] [v @v]) (keys step2-roots))))
(defn arm! [which]
  (doseq [[v f] (if (= which :step2) step2-roots step3-roots)] (alter-var-root v (constantly f))))

;; ---------------------------------------------------------------------------
;; Roots (step 2's shapes)
;; ---------------------------------------------------------------------------
(def src100 (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100))))
(def src1k (zipmap (range 1000) (range 1000)))
(def src10k (zipmap (range 10000) (range 10000)))
(def a100 (e/atom src100))
(def a1k (e/atom src1k))
(def big-domain
  (eve.atom.MmapAtomDomain. (#'ea/open-heap-domain! {:capacities (zipmap (range 6) (repeat (* 64 1024 1024)))})
                            (clojure.core/atom {})))
(def a10k (ea/lookup-or-create-mmap-atom! big-domain "hg/a10k" src10k))
(def av1k (e/atom (vec (range 1000))))
;; the raw slab values (domain-aware deref, step 2): no writes run during the bench, so their blocks stay live
(defn raw [a] (binding [g/*proxy-same-domain?* false] (g/guard* (ea/-domain-state a) (fn [] @a))))
(def m100 (raw a100))
(def m1k (raw a1k))
(def v1k (raw av1k))
(def sio100 (d/-eve-sio m100))
(def root100 (alloc/-sio-read-i32 sio100 (d/-root-header-off m100) 8))
(def kb42 (codec/encode-key :k42))
(def kh42 (hu/portable-hash-bytes kb42))

(def ks (int-array (shuffle (range 10000))))
(def ki (volatile! 0))
(defn nk ^long [^long n] (rem (aget ^ints ks (vswap! ki (fn [^long x] (if (== x 9999) 0 (inc x))))) n))
(def kw100 (mapv #(keyword (str "k" %)) (range 100)))
(defn k100 [] (nth kw100 (nk 100)))

;; [group f checksum-f]
(def groups
  [["harness floor: (identity x)"          #(identity kb42)                    #(identity 1)]
   ["direct get, 100-map (rand kw)"        #(get m100 (k100))                  #(get m100 :k42)]
   ["direct get, 1k-map (rand int)"         #(get m1k (nk 1000))                #(get m1k 420)]
   ["vec nth, 1k-vec, direct (rand)"        #(nth v1k (nk 1000))                #(nth v1k 420)]
   ["guard (get @a k), 100, extent/op"      #(g/with-guard (get @a100 (k100)))  #(g/with-guard (get @a100 :k42))]
   ["guard (get @a k), 100, in open extent" ::steady100                         #(g/with-guard (get @a100 :k42))]
   ["guard (get @a k), 1k, extent/op"       #(g/with-guard (get @a1k (nk 1000))) #(g/with-guard (get @a1k 420))]
   ["guard (get @a k), 10k, extent/op"      #(g/with-guard (get @a10k (nk 10000))) #(g/with-guard (get @a10k 4200))]
   ["guard @a alone, 100"                   #(g/with-guard (deref a100))        #(g/with-guard (count @a100))]
   ["anatomy: portable-hash-bytes, 6 B"     #(hu/portable-hash-bytes kb42)      #(hu/portable-hash-bytes kb42)]
   ["anatomy: node-u8 (node type)"          #(alloc/node-u8 sio100 root100 0)   #(alloc/node-u8 sio100 root100 0)]
   ["anatomy: node-i32, unaligned"          #(alloc/node-i32 sio100 root100 13) #(alloc/node-i32 sio100 root100 13)]
   ["anatomy: node-i32, aligned"            #(alloc/node-i32 sio100 root100 4)  #(alloc/node-i32 sio100 root100 4)]
   ["anatomy: hamt-get-bytes walk, :k42"    #(em/hamt-get-bytes sio100 root100 kh42 kb42) #(vec (em/hamt-get-bytes sio100 root100 kh42 kb42))]])

(def steady {::steady100 #(get @a100 (k100))})
(defn run-arm [f n] (if (keyword? f) (g/with-guard (measure (steady f) n)) (measure f n)))
(defn calib [f] (if (keyword? f) (g/with-guard (calibrate (steady f))) (calibrate f)))

(println "=== env ===" (System/getProperty "java.version") "cores" (.availableProcessors (Runtime/getRuntime))
         "load" (.getSystemLoadAverage (java.lang.management.ManagementFactory/getOperatingSystemMXBean)))
(println "=== checksums (each group's read under both arms, fixed keys) ===")
(def all-equal?
  (every? (fn [[grp _ cf]]
            (let [r3 (do (arm! :step3) (cf)) r2 (do (arm! :step2) (cf)) _ (arm! :step3)]
              (println (format "%-40s %s %s" grp (if (= r3 r2) "EQUAL" "DIFFER") (pr-str [r3 r2])))
              (= r3 r2)))
          groups))
(println "all groups equal:" all-equal?)

;; warm every group under both arms (~0.3 s each) before calibrating, so round 1 is not a cold JIT
(defn warm! [f] (let [f (if (keyword? f) (steady f) f) t-end (+ (System/nanoTime) 300000000)]
                  (if (= f (steady ::steady100))
                    (g/with-guard (while (< (System/nanoTime) t-end) (dotimes [_ 100] (f))))
                    (while (< (System/nanoTime) t-end) (dotimes [_ 100] (f))))))
(doseq [which [:step2 :step3] [_ f] groups] (arm! which) (warm! f))
(arm! :step3)
;; calibrate each group once under step3 (same n for both arms)
(def ns-of (into {} (for [[grp f] groups] [grp (calib f)])))
(def results (atom {}))
(def t-start (System/nanoTime))
(dotimes [round 5]
  (doseq [[grp f] groups]
    (doseq [which (if (even? round) [:step3 :step2] [:step2 :step3])]
      (arm! which)
      (swap! results update [grp which] (fnil conj []) (run-arm f (ns-of grp)))))
  (arm! :step3))
(println (format "=== timing: per-round min ns/op, 5 interleaved rounds; bytes/op (last round) === (%.0f s)"
                 (/ (- (System/nanoTime) t-start) 1e9)))
(doseq [[grp] groups
        which [:step2 :step3]]
  (let [rs (get @results [grp which])]
    (println (format "%-40s %-6s %-58s %s" grp (name which) (pr-str (mapv (comp fmt first) rs)) (fmtb (second (last rs)))))))
(println "=== ratio step2/step3 (median of rounds) ===")
(doseq [[grp] groups]
  (let [med (fn [w] (let [xs (sort (map first (get @results [grp w])))] (nth xs (quot (count xs) 2))))]
    (println (format "%-40s %s -> %s  (%.2fx)" grp (fmt (med :step2)) (fmt (med :step3)) (/ (med :step2) (med :step3))))))
(shutdown-agents)
(System/exit 0)
