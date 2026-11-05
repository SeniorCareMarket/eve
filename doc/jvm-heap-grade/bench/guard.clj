;; doc/jvm-heap-grade/bench/guard.clj — STEP 2: the automatic guard vs whole-root materialization. JVM seat, ONE session.
;; Every arm runs in this one JVM, interleaved: each round runs every arm once, 3 rounds; per arm we print each round's
;; min-of-3-batch ns/op, plus bytes allocated per op (ThreadMXBean, last batch) as the materialization witness.
;; Checksums: every arm of a group is run once on the same fixed key set and the results compared (must be =).
;; Run: java --add-opens java.base/java.lang=ALL-UNNAMED -cp <deps.edn :paths>:clojure jars clojure.main doc/jvm-heap-grade/bench/guard.clj
(ns hg.guard
  (:require [eve.alpha :as e]
            [eve.atom :as ea]
            [eve.guard :as g]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]))

(def ^com.sun.management.ThreadMXBean tmx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn alloc-bytes ^long [] (.getThreadAllocatedBytes tmx (.getId (Thread/currentThread))))

(defn calibrate ^long [f]
  (dotimes [_ 3] (f))
  (let [t0 (System/nanoTime)] (dotimes [_ 5] (f))
       (let [per (/ (double (- (System/nanoTime) t0)) 5)]
         (long (max 5 (min 200000 (/ 4e7 per)))))))   ;; ~40 ms per batch

(defn measure
  "[min ns/op over 3 batches, bytes/op of the last batch]"
  [f n]
  (dotimes [_ n] (f))
  (let [rs (for [_ (range 3)]
             (let [b0 (alloc-bytes) t0 (System/nanoTime)]
               (dotimes [_ n] (f))
               [(/ (double (- (System/nanoTime) t0)) n) (/ (double (- (alloc-bytes) b0)) n)]))]
    [(apply min (map first rs)) (second (last rs))]))

(defn fmt [ns] (cond (> ns 1e6) (format "%.2f ms" (/ ns 1e6)) (> ns 1e3) (format "%.2f µs" (/ ns 1e3)) :else (format "%.0f ns" ns)))
(defn fmtb [b] (cond (> b 1e6) (format "%.1f MB" (/ b 1e6)) (> b 1e3) (format "%.1f KB" (/ b 1e3)) :else (format "%.0f B" b)))

;; ---------------------------------------------------------------------------
;; Roots
;; ---------------------------------------------------------------------------
;; 100 and 1k: the step-1 shapes, in eve's default in-process domain (e/atom, anonymous) — step-1 reproduction.
;; 10k: the heap domain's default capacities die between 1k and 5k entries (step-1 §3, out of scope here), so the 10k
;; root lives in a heap domain opened with 64 MB per class through the same open-heap-domain! eve/atom uses.
(def src100 (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 100))))
(def src1k (zipmap (range 1000) (range 1000)))
(def src10k (zipmap (range 10000) (range 10000)))
(def a100 (e/atom src100))
(def a1k (e/atom src1k))
(def big-domain
  (eve.atom.MmapAtomDomain. (#'ea/open-heap-domain! {:capacities (zipmap (range 6) (repeat (* 64 1024 1024)))})
                            (clojure.core/atom {})))
(def a10k (ea/lookup-or-create-mmap-atom! big-domain "hg/a10k" src10k))

;; same-domain arm: a nested root and a receiving atom in the SAME domain (anonymous atoms share one domain)
(def srcN (into {} (map (fn [i] [(keyword (str "u" i)) {:id i :name (str "n" i) :tags [i (inc i) (+ i 2)]}]) (range 100))))
(def aN (e/atom srcN))
(def bN (e/atom {}))
(def ds-same (ea/-domain-state aN))
(def slot-ptr #(mem/-load-i32 (:root-r (ea/-domain-state %)) (d/atom-slot-offset (ea/-atom-slot-idx %) d/ATOM_SLOT_PTR_OFFSET)))

(def ks (int-array (shuffle (range 10000))))
(def ki (volatile! 0))
(defn nk ^long [^long n] (rem (aget ^ints ks (vswap! ki (fn [^long x] (if (== x 9999) 0 (inc x))))) n))
(def kw100 (mapv #(keyword (str "k" %)) (range 100)))
(defn k100 [] (nth kw100 (nk 100)))
(def sparse100 (mapv #(keyword (str "k" %)) [3 17 29 42 55 61 78 99]))
(def sparse-int [3 170 290 420 555 610 780 999])

(defn sum-get [m ks'] (reduce (fn [^long s k] (+ s (long (get m k)))) 0 ks'))
(defn sum-vals [m] (reduce-kv (fn [^long s _ v] (+ s (long v))) 0 m))

;; ---------------------------------------------------------------------------
;; Arms: [group label f checksum-f]  — checksum-f runs the arm's read on fixed keys
;; ---------------------------------------------------------------------------
(defn get-arms [tag a kf fixed]
  [[tag (str "plain   (get @a k), " tag)          #(get @a (kf))                     #(get @a fixed)]
   [tag (str "$       (get @a k), " tag)          #(e/$ (get @a (kf)))               #(e/$ (get @a fixed))]
   [tag (str "guard   (get @a k), " tag ", extent/op") #(g/with-guard (get @a (kf))) #(g/with-guard (get @a fixed))]
   [tag (str "guard   (get @a k), " tag ", in open extent") ::steady         #(g/with-guard (get @a fixed))]
   [tag (str "floor   read-pinned get, " tag)     #(ea/read-pinned a get (kf))       #(ea/read-pinned a get fixed)]])

(def steady-fns
  {"guard   (get @a k), 100, in open extent" #(get @a100 (k100))
   "guard   (get @a k), 1k, in open extent"  #(get @a1k (nk 1000))
   "guard   (get @a k), 10k, in open extent" #(get @a10k (nk 10000))})

(def arms
  (vec
   (concat
    (get-arms "100" a100 k100 :k42)
    (get-arms "1k" a1k #(nk 1000) 420)
    (get-arms "10k" a10k #(nk 10000) 4200)
    ;; deref of the root alone
    [["deref" "plain   @a, 100"   #(deref a100)                 #(count @a100)]
     ["deref" "guard   @a, 100"   #(g/with-guard (deref a100))  #(g/with-guard (count @a100))]
     ["deref" "plain   @a, 1k"    #(deref a1k)                  #(count @a1k)]
     ["deref" "guard   @a, 1k"    #(g/with-guard (deref a1k))   #(g/with-guard (count @a1k))]
     ["deref" "plain   @a, 10k"   #(deref a10k)                 #(count @a10k)]
     ["deref" "guard   @a, 10k"   #(g/with-guard (deref a10k))  #(g/with-guard (count @a10k))]]
    ;; sparse multi-key: 8 keys from one deref
    (for [[tag a ks'] [["100" a100 sparse100] ["1k" a1k sparse-int] ["10k" a10k sparse-int]]
          [lbl f] [["plain " #(sum-get @a ks')]
                   ["$     " #(e/$ (sum-get @a ks'))]
                   ["guard " #(g/with-guard (sum-get @a ks'))]]]
      [(str "sparse8-" tag) (str lbl "  8-key sum, " tag) f f])
    ;; full traversal (the weak case)
    (for [[tag a] [["100" a100] ["1k" a1k] ["10k" a10k]]
          [lbl f] [["plain " #(sum-vals @a)]
                   ["$     " #(e/$ (sum-vals @a))]
                   ["guard " #(g/with-guard (sum-vals @a))]]]
      [(str "full-" tag) (str lbl "  reduce-kv sum all, " tag) f f])
    ;; SAME-DOMAIN arm — reads: a nested 100-root, consumer extent owned by the SAME domain
    [["same-read" "plain   (get-in @aN [k :tags 1])"        #(get-in @aN [:u42 :tags 1])                #(get-in @aN [:u42 :tags 1])]
     ["same-read" "$       (get-in @aN [k :tags 1])"        #(e/$ (get-in @aN [:u42 :tags 1]))          #(e/$ (get-in @aN [:u42 :tags 1]))]
     ["same-read" "guard   proxied, same-domain extent"      #(g/guard* ds-same (fn [] (get-in @aN [:u42 :tags 1])))
      #(g/guard* ds-same (fn [] (get-in @aN [:u42 :tags 1])))]
     ["same-read" "domain-aware raw, same-domain extent"     #(binding [g/*proxy-same-domain?* false] (g/guard* ds-same (fn [] (get-in @aN [:u42 :tags 1]))))
      #(binding [g/*proxy-same-domain?* false] (g/guard* ds-same (fn [] (get-in @aN [:u42 :tags 1]))))]
     ;; SAME-DOMAIN arm — a subtree read from aN stored into bN (same domain): copy vs shared pointer
     ["same-swap" "plain   (swap! bN (fn [_] (get @aN k)))"  #(swap! bN (fn [_] (get @aN :u42)))       #(do (swap! bN (fn [_] (get @aN :u42))) (e/$ (e/get @bN :name)))]
     ["same-swap" "$       (swap! bN (fn [_] (get @aN k)))"  #(e/$ (swap! bN (fn [_] (get @aN :u42)))) #(do (e/$ (swap! bN (fn [_] (get @aN :u42)))) (e/$ (e/get @bN :name)))]
     ["same-swap" "guard   proxied, same-domain extent"      #(g/guard* ds-same (fn [] (swap! bN (fn [_] (get @aN :u42)))))
      #(do (g/guard* ds-same (fn [] (swap! bN (fn [_] (get @aN :u42))))) (e/$ (e/get @bN :name)))]
     ["same-swap" "domain-aware raw, same-domain extent"     #(binding [g/*proxy-same-domain?* false] (g/guard* ds-same (fn [] (swap! bN (fn [_] (get @aN :u42))))))
      #(do (binding [g/*proxy-same-domain?* false] (g/guard* ds-same (fn [] (swap! bN (fn [_] (get @aN :u42)))))) (e/$ (e/get @bN :name)))]])))

;; ---------------------------------------------------------------------------
;; Checksums — every arm of a group answers the same value
;; ---------------------------------------------------------------------------
(println "=== checksums (each arm once, fixed keys) ===")
(def checks
  (reduce (fn [m [grp label _ cf]] (update m grp (fnil conj []) [label (cf)])) (sorted-map) arms))
(def all-equal?
  (every? (fn [[grp rs]]
            (let [vs (distinct (map second rs))]
              (println (format "%-10s %s %s" grp (if (= 1 (count vs)) "EQUAL" "DIFFER") (pr-str (vec vs))))
              (= 1 (count vs))))
          checks))
(println "all groups equal:" all-equal?)

;; ---------------------------------------------------------------------------
;; Pointer identity — same-domain
;; ---------------------------------------------------------------------------
(println "=== same-domain pointer identity ===")
(let [u42 (e/$ (d/-root-header-off (get @aN :u42)))
      root (slot-ptr aN)
      rows [["plain swap"        #(swap! bN (fn [_] (get @aN :u42)))]
            ["$ swap"            #(e/$ (swap! bN (fn [_] (get @aN :u42))))]
            ["guard proxied swap" #(g/guard* ds-same (fn [] (swap! bN (fn [_] (get @aN :u42)))))]
            ["domain-aware raw swap" #(binding [g/*proxy-same-domain?* false] (g/guard* ds-same (fn [] (swap! bN (fn [_] (get @aN :u42))))))]]]
  (println "aN slot ptr" root " aN[:u42] subtree ptr" u42)
  (println "guard proxied @aN: type" (g/guard* ds-same (fn [] (type @aN)))
           " header" (g/guard* ds-same (fn [] (d/-root-header-off @aN)))
           " sio identical?" (g/guard* ds-same (fn [] (identical? (d/-eve-sio @aN) (ea/atom-sio aN)))))
  (println "raw @aN: type" (binding [g/*proxy-same-domain?* false] (g/guard* ds-same (fn [] (type @aN))))
           " header" (binding [g/*proxy-same-domain?* false] (g/guard* ds-same (fn [] (d/-root-header-off @aN)))))
  (doseq [[lbl f] rows]
    (f)
    (println (format "%-24s bN slot ptr %d  %s" lbl (slot-ptr bN) (if (== (slot-ptr bN) u42) "== source subtree (shared)" "!= source (COPIED)")))))

;; ---------------------------------------------------------------------------
;; Timing — interleaved
;; ---------------------------------------------------------------------------
(def ns-of (into {} (for [[_ label f] arms]
                      [label (if (= f ::steady)
                               (let [sf (steady-fns label)] (g/with-guard (calibrate sf)))
                               (calibrate f))])))
(def results (atom {}))
(def t-start (System/nanoTime))
(dotimes [round 3]
  (doseq [[_ label f] arms]
    (let [n (ns-of label)
          r (if (= f ::steady)
              (let [sf (steady-fns label)] (g/with-guard (measure sf n)))
              (measure f n))]
      (swap! results update label (fnil conj []) r))))
(println (format "=== timing: per-round min ns/op, 3 interleaved rounds; bytes/op (round 3) === (%.0f s)"
                 (/ (- (System/nanoTime) t-start) 1e9)))
(doseq [[grp label] arms]
  (let [rs (get @results label)]
    (println (format "%-10s %-48s %-34s %s" grp label (pr-str (mapv (comp fmt first) rs)) (fmtb (second (last rs)))))))

;; ---------------------------------------------------------------------------
;; Pin lifetime: an extent held open across writes keeps the zero queue from flushing
;; ---------------------------------------------------------------------------
(println "=== pin lifetime (100 swap! assoc on a100's domain while a guard extent is open vs closed) ===")
(let [ds (ea/-domain-state a100)
      q #(count @(:retire-q ds))]
  (#'ea/flush-zero-queue! ds)
  (println "queue before:" (q))
  (g/with-guard
    (let [_ (get @a100 :k1)]
      (dotimes [i 100] (swap! a100 assoc :k0 i))
      (#'ea/flush-zero-queue! ds)
      (println "queue after 100 swaps + flush, extent OPEN:" (q))))
  (#'ea/flush-zero-queue! ds)
  (println "queue after the extent closed + flush:" (q)))
(shutdown-agents)
(System/exit 0)
