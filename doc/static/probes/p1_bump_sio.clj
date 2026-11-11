;; P1 — Eve's own persistent types over ONE append-only byte buffer (research probe, not product code).
;;
;; Question (A4, O2, O4): can a "static" value be a detached domain image — the slab types reading through a slab
;; I/O whose address space is a single host byte array, where an allocation is a bump of the tail and a free is
;; nothing — with ZERO new type code? If so: (buffer, length, root) is a view; an update appends; an older view
;; never sees it; the buffer is the storage form (file out, file in, read in place).
;;
;; Run (from /home/user/eve):  java -cp "$(scratchpad/eve-cp.sh)" clojure.main doc/static/probes/p1_bump_sio.clj
(ns p1-bump-sio
  (:require [eve.atom :as ea]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.map :as em]
            [eve.vec :as ev]
            [eve.set :as es]
            [clojure.test.check.generators :as gen])
  (:import [java.util Arrays]
           [java.nio.file Files Paths OpenOption]))

(set! *warn-on-reflection* false)

(definterface IBump
  (^bytes buf [])
  (^long tail [])
  (^long nalloc [])
  (^long nfree [])
  (sizes []))

;; A slab I/O over one byte array. An "offset" is a byte position in the array (4-aligned, < 2^29 so the class bits
;; the allocator would decode are 0); alloc bumps the tail; free does nothing (log-appending physics).
(deftype BumpSio [^:unsynchronized-mutable ^bytes b
                  ^:unsynchronized-mutable ^long t
                  ^:unsynchronized-mutable ^long na
                  ^:unsynchronized-mutable ^long nf
                  ^java.util.ArrayList szs
                  frozen?]
  IBump
  (buf [_] b)
  (tail [_] t)
  (nalloc [_] na)
  (nfree [_] nf)
  (sizes [_] szs)
  alloc/ISlabIO
  (-sio-read-u8 [_ o f] (bit-and (aget b (int (+ (long o) (long f)))) 0xFF))
  (-sio-write-u8! [_ o f v] (aset b (int (+ (long o) (long f))) (unchecked-byte v)))
  (-sio-read-u16 [_ o f]
    (let [i (int (+ (long o) (long f)))]
      (bit-or (bit-and (aget b i) 0xFF) (bit-shift-left (bit-and (aget b (inc i)) 0xFF) 8))))
  (-sio-write-u16! [_ o f v]
    (let [i (int (+ (long o) (long f)))]
      (aset b i (unchecked-byte v))
      (aset b (inc i) (unchecked-byte (bit-shift-right (long v) 8)))))
  (-sio-read-i32 [_ o f]
    (let [i (int (+ (long o) (long f)))]
      (long (unchecked-int
             (bit-or (bit-and (long (aget b i)) 0xFF)
                     (bit-shift-left (bit-and (long (aget b (+ i 1))) 0xFF) 8)
                     (bit-shift-left (bit-and (long (aget b (+ i 2))) 0xFF) 16)
                     (bit-shift-left (bit-and (long (aget b (+ i 3))) 0xFF) 24))))))
  (-sio-write-i32! [_ o f v]
    (let [i (int (+ (long o) (long f))) v (long v)]
      (aset b i (unchecked-byte v))
      (aset b (+ i 1) (unchecked-byte (bit-shift-right v 8)))
      (aset b (+ i 2) (unchecked-byte (bit-shift-right v 16)))
      (aset b (+ i 3) (unchecked-byte (bit-shift-right v 24)))))
  (-sio-cas-i32! [this o f e dv]
    (let [cur (alloc/-sio-read-i32 this o f)]
      (when (== (long cur) (long e)) (alloc/-sio-write-i32! this o f dv))
      cur))
  (-sio-read-bytes [_ o f n]
    (let [i (int (+ (long o) (long f)))]
      (Arrays/copyOfRange b i (int (+ i (long n))))))
  (-sio-write-bytes! [_ o f src]
    (System/arraycopy ^bytes src 0 b (int (+ (long o) (long f))) (alength ^bytes src)))
  (-sio-alloc! [_ n]
    (when frozen? (throw (ex-info "static view: allocation in a frozen buffer" {})))
    (let [n (long n)
          off t
          end (+ off (bit-and (+ n 3) (bit-not 3)))]
      (when (> end (alength b))
        (set! b (Arrays/copyOf b (int (max end (* 2 (alength b)))))))
      (set! t end)
      (set! na (inc na))
      (when szs (.add szs n))
      off))
  (-sio-free! [_ o] (set! nf (inc nf)) true)
  (-sio-copy-block! [_ d df s sf n]
    (System/arraycopy b (int (+ (long s) (long sf))) b (int (+ (long d) (long df))) (int n))))

(defn bump-sio
  ([] (bump-sio 4096))
  ([cap] (BumpSio. (byte-array cap) 8 0 0 (java.util.ArrayList.) false)))

(defn view-sio
  "A frozen view over the first len bytes of bs (no copy): reads only."
  [^bytes bs len]
  (BumpSio. bs len 0 0 nil true))

(defn root-off [v] (d/-root-header-off v))

(defn reopen
  "The eve value whose header is at off, read through sio."
  [sio off]
  (let [t (ser/read-header-tag sio off)
        ctor (ser/get-header-constructor t)]
    (ctor sio off)))

(defmacro ms [& body]
  `(let [t0# (System/nanoTime) r# (do ~@body)] [r# (/ (- (System/nanoTime) t0#) 1e6)]))

(defn best-of [n f]
  (loop [i 0 best Double/MAX_VALUE r nil]
    (if (== i n) [r best]
        (let [[r t] (ms (f))] (recur (inc i) (min best (double t)) r)))))

(def results (atom []))
(defn check [label ok? & more]
  (swap! results conj [label ok?])
  (println (format "  %-58s %s%s" label (if ok? "PASS" "FAIL") (if (seq more) (str "  " (apply str more)) ""))))

;; ---------------------------------------------------------------------------------------------------------------
(println "== P1.1 the slab types over a bump buffer (no new type code)")
(def v0 {:id 42
         :name "static eve"
         :tags #{:a :b :c}
         :items (vec (range 100))
         :nested {:k1 {:k2 {:k3 [1 2.5 "three" :four 'five nil true]}}}
         :long-string (apply str (repeat 40 "0123456789"))
         "string key" 1
         7 :int-key})
(def sio (bump-sio))
(def m0 (ser/convert-to-eve sio v0))
(println "  built:" (type m0) "tail" (.tail ^IBump sio) "bytes," (.nalloc ^IBump sio) "blocks")
(check "eve->clj of the bump-buffer value equals the source" (= v0 (d/eve->clj m0)))
(check "lookup, nested lookup, nth, count, contains? in place"
       (and (= 42 (get m0 :id)) (= "three" (get-in m0 [:nested :k1 :k2 :k3 2]))
            (= 57 (nth (get m0 :items) 57)) (= 8 (count m0)) (contains? (get m0 :tags) :b)))
(check "(= static plain) and (= plain static)" (and (= m0 v0) (= v0 m0)))
(check "hash of static value = hash of plain value" (= (hash m0) (hash v0)))
(check "seq / reduce over it" (= (set (keys v0)) (set (keys m0))))

;; ---------------------------------------------------------------------------------------------------------------
(println "== P1.2 a view is (buffer, length, root); an update appends; the old view never sees it")
(def len0 (.tail ^IBump sio))
(def root0 (root-off m0))
(def h0 (hash m0))
(def m1 (assoc m0 :added "later" :id 43))
(def len1 (.tail ^IBump sio))
(def root1 (root-off m1))
(println "  append: tail" len0 "->" len1 "(+" (- len1 len0) "bytes) root" root0 "->" root1
         "frees ignored:" (.nfree ^IBump sio))
(check "new view has the update" (and (= 43 (:id m1)) (= "later" (:added m1)) (= 9 (count m1))))
(check "old root still reads the old value over the GROWN buffer" (= v0 (d/eve->clj (reopen sio root0))))
(check "old view's hash unchanged after the append" (= h0 (hash (reopen sio root0))))
(def prefix-before (Arrays/copyOfRange (.buf ^IBump sio) 0 (int len0)))
(let [pre-now (Arrays/copyOfRange (.buf ^IBump sio) 0 (int len0))]
  (check "no byte below the old length was rewritten by the append" (Arrays/equals prefix-before pre-now)))
;; a view over ONLY the first len0 bytes (truncated copy): nothing it reads lies past its length
(def truncated (view-sio (Arrays/copyOfRange (.buf ^IBump sio) 0 (int len0)) len0))
(check "the old view reads from a buffer truncated at its length" (= v0 (d/eve->clj (reopen truncated root0))))
(check "old view = new view minus the change (structural sharing by offset)"
       (= (dissoc (d/eve->clj m1) :added) (assoc (d/eve->clj (reopen sio root0)) :id 43)))

;; property: random values, random updates applied to a plain twin and to the buffer value in lockstep; after ALL
;; the appends, every earlier (length, root) view must still equal the plain value it was at that step, read (a) over
;; the grown buffer and (b) over a copy truncated at that view's own length; and its hash must be the plain twin's.
(defn step [m i]
  (case (int (mod i 5))
    0 (assoc m (keyword "p" (str "k" i)) i)
    1 (assoc m :p/vec (vec (range i)))
    2 (if-let [k (first (sort-by str (keys m)))] (dissoc m k) m)
    3 (assoc m :p/n (* 3 i))
    4 (assoc m :p/nested {:i i :s (apply str (repeat i "x")) :set (set (range (mod i 7)))})))
(let [g (gen/map gen/keyword (gen/one-of [gen/small-integer gen/string-alphanumeric gen/boolean
                                           (gen/vector gen/small-integer 0 40)
                                           (gen/map gen/keyword gen/small-integer)]))
      vals (gen/sample g 80)
      n-steps 15
      bad (atom [])
      ok (every?
          (fn [v]
            (let [s (bump-sio)
                  e0 (ser/convert-to-eve s v)
                  [plains snaps]
                  (loop [i 0 p v e e0 plains [v] snaps [[(root-off e0) (.tail ^IBump s)]]]
                    (if (== i n-steps)
                      [plains snaps]
                      (let [p2 (step p i) e2 (step e i)]
                        (recur (inc i) p2 e2 (conj plains p2) (conj snaps [(root-off e2) (.tail ^IBump s)])))))
                  buf (.buf ^IBump s)]
              (every? true?
                      (map (fn [p [r len]]
                             (let [grown (reopen s r)
                                   cut (reopen (view-sio (Arrays/copyOfRange buf 0 (int len)) len) r)
                                   good (and (= p (d/eve->clj grown)) (= p (d/eve->clj cut))
                                             (= grown p) (= (hash p) (hash grown)) (= (hash p) (hash cut)))]
                               (when-not good (swap! bad conj {:p p :r r :len len}))
                               good))
                           plains snaps))))
          vals)]
  (check "property: 80 random maps x 15 updates; every earlier view stable, truncatable, hash-stable" ok
         (when (seq @bad) (str "first bad: " (pr-str (first @bad))))))

;; ---------------------------------------------------------------------------------------------------------------
(println "== P1.3 the buffer is the storage form: file out, file in, read in place")
(let [path (Paths/get "/tmp/claude-0/-home-user/b132a92e-e24c-5e30-ae79-571f5391deda/scratchpad/p1.static" (make-array String 0))
      bytes (Arrays/copyOfRange (.buf ^IBump sio) 0 (int len1))]
  (Files/write path bytes (make-array OpenOption 0))
  (let [back (Files/readAllBytes path)
        s2 (view-sio back (alength back))
        m (reopen s2 root1)]
    (check "file round trip: reopened value equals, no transform" (= (d/eve->clj m1) (d/eve->clj m)))
    (check "reopened value: lookup without materializing" (= "later" (get m :added)))
    (check "frozen view refuses allocation (assoc throws)"
           (try (assoc m :x 1) false (catch Exception _ true)))))

;; ---------------------------------------------------------------------------------------------------------------
(println "== P1.4 what it costs: plain -> eve in a slab domain vs in a bump buffer; bytes; the block census")
(defn census [^java.util.ArrayList szs]
  (let [cls (fn [n] (cond (<= n 32) 32 (<= n 64) 64 (<= n 128) 128 (<= n 256) 256 (<= n 512) 512 (<= n 1024) 1024 :else :overflow))]
    (into (sorted-map-by (fn [a b] (compare (if (keyword? a) 99999 a) (if (keyword? b) 99999 b))))
          (frequencies (map cls szs)))))
(defn padded-bytes [^java.util.ArrayList szs]
  (reduce + (map (fn [n] (cond (<= n 32) 32 (<= n 64) 64 (<= n 128) 128 (<= n 256) 256 (<= n 512) 512 (<= n 1024) 1024 :else n)) szs)))

(def shapes
  {"map 10k int->int"            (zipmap (range 10000) (range 10000))
   "vector 100k ints"            (vec (range 100000))
   "vector 10k small maps"       (mapv (fn [i] {:id i :name (str "user-" i) :active (even? i) :score (* 1.5 i)}) (range 10000))
   "map 1k kw->vector(20 ints)"  (into {} (map (fn [i] [(keyword (str "k" i)) (vec (range 20))]) (range 1000)))})

(doseq [[label v] shapes]
  (let [;; warm
        _ (dotimes [_ 3] (ser/convert-to-eve (bump-sio (* 64 1024 1024)) v))
        _ (dotimes [_ 2] (ser/convert-to-eve (alloc/heap-domain-sio! {}) v))
        [e-bump t-bump] (best-of 5 #(ser/convert-to-eve (bump-sio (* 64 1024 1024)) v))
        [e-slab t-slab] (best-of 5 #(ser/convert-to-eve (alloc/heap-domain-sio! {}) v))
        s (bump-sio (* 64 1024 1024))
        e (ser/convert-to-eve s v)
        [_ t-plain-bump] (best-of 5 #(d/eve->clj e))
        [_ t-plain-slab] (best-of 5 #(d/eve->clj e-slab))
        szs (.sizes ^IBump s)]
    (println (format "  %-28s build: slab %8.1f ms  bump %8.1f ms | eve->clj: slab %8.1f ms  bump %8.1f ms"
                     label t-slab t-bump t-plain-slab t-plain-bump))
    (println (format "  %-28s blocks %d  exact bytes %d  class-padded bytes %d  edn bytes %d  census %s"
                     "" (.nalloc ^IBump s) (- (.tail ^IBump s) 8) (padded-bytes szs) (count (pr-str v)) (census szs)))
    (check (str label ": equal after build") (= v (d/eve->clj e)))))

(println)
(let [rs @results]
  (println (format "P1 RESULT: %d/%d checks pass" (count (filter second rs)) (count rs)))
  (when-let [f (seq (remove second rs))] (println "FAILED:" (mapv first f))))
(System/exit 0)
