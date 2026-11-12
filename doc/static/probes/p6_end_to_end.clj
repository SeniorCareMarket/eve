;; P6 — the whole path through a real atom, and the remaining priced questions (research probe).
;;
;;   6.1  A15 / Q10: one swap! storing a value, end to end (the store, the tally's hold of the new root, the CAS):
;;        a plain value through the direct writer (the incumbent) · an image placed by run claims · a plain value
;;        built in an append-only buffer, compacted and placed. And the way out: @a (eve->clj) vs an image copied
;;        under the pin.
;;   6.2  Q2: sharing. One sub-value stored at k places of a map: what each form carries.
;;   6.3  A5: retention. What an append-only buffer holds after updates; what a small view pins.
;;   6.4  A18: column encodings on a datom table — bytes and the cost of ONE value read, for plain i32, narrowed
;;        (frame-of-reference) and run-end columns.
;;
;; Run (from /home/user/eve): EXTRA_CP=<classes> java -Xmx6g -cp "$(doc/static/probes/eve-cp.sh)" clojure.main doc/static/probes/p6_end_to_end.clj
(load-file "doc/static/probes/lib.clj")
(load-file "doc/static/probes/corpora.clj")
(ns p6-end-to-end
  (:require [static-probe.lib :as L :refer [bench]]
            [static-probe.corpora :as C]
            [eve.atom :as ea]
            [eve.map :as em]
            [eve.codec :as codec]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem])
  (:import [staticprobe ImageCopier]
           [eve.deftype_proto.alloc JvmSlabCtx]
           [java.util Random Arrays]))

(println "P6 — eve" (clojure.string/trim (slurp ".git/HEAD")) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(defn- as-image [^ImageCopier c root]
  (L/->Image (.-img c) (.-len c) root (.-boff c) (.-bsz c) (.-nb c) (.-rpos c) (.-rown c) (.-rtgt c) (.-nr c)))
(defn slab-image [^JvmSlabCtx ctx root]
  (let [regions ^objects (.-regions ctx)
        reg (into-array (Class/forName "[B") (map (fn [i] (mem/-heap-bytes (aget regions i))) (range 7)))
        c (ImageCopier. reg (.-data_offsets ctx) (.-block_sizes ctx))]
    (as-image c (.run c (unchecked-int root)))))
(defn bump-image [bs root]
  (let [reg (into-array (Class/forName "[B") [(.buf ^static_probe.lib.IBump bs)])
        c (ImageCopier. reg (int-array [0]) (int-array [1]))]
    (as-image c (.run c (unchecked-int root)))))

(defn row [label & kvs] (println (format "    %-62s %s" label (apply str kvs))))
(defn fmt [{:keys [min med]}] (format "%9.2f ms (med %9.2f)" min med))

;; ---------------------------------------------------------------------------------------------------------------
(println)
(println "== 6.1 one swap! into a fresh in-process atom, end to end (each row creates its own pre-sized domain)")
(def corpora
  [["map 10k int->int"        (zipmap (range 10000) (range 10000))]
   ["vector 100k ints"        (vec (range 100000))]
   ["vector 10k records"      (mapv (fn [i] {:id i :name (str "user-" i) :active (even? i) :score (* 1.5 i)}) (range 10000))]
   ["5k chat messages"        (C/messages 5000)]])

(defn fresh-atom [caps]
  (let [dom (ea/atom-domain nil :capacities caps)]
    (ea/lookup-or-create-mmap-atom! dom "p6/a" nil)))

(doseq [[label v] corpora]
  (println "  --" label)
  (let [bs0 (L/bump-sio (* 8 1024 1024) true)
        im (bump-image bs0 (L/build! bs0 v))
        caps (L/caps-for im 3)
        place (fn [sio im] (L/reopen sio (L/ingest! (alloc/sio-root sio) im :runs)))
        store-plain (fn [] (let [a (fresh-atom caps)] (swap! a (fn [_] v)) a))
        store-image (fn [] (let [a (fresh-atom caps)] (swap! a (fn [_] (place d/*domain-sio* im))) a))
        store-via   (fn [] (let [a (fresh-atom caps)]
                             (swap! a (fn [_] (let [bs (L/bump-sio (* 32 1024 1024) false)]
                                                (place d/*domain-sio* (bump-image bs (L/build! bs v))))))
                             a))
        r-dom (bench #(fresh-atom caps))
        r-plain (bench 2 5 store-plain)
        r-image (bench 2 5 store-image)
        r-via (bench 2 5 store-via)
        a (store-image)
        ctx (alloc/sio-root (ea/atom-sio a))]
    (assert (= v @(store-plain)) "plain store differs")
    (assert (= v @a) "image store differs")
    (assert (= v @(store-via)) "bump store differs")
    ;; a second write on top of the placed value: the domain is sound after a run claim + hold
    (when (map? v) (swap! a assoc :after 1) (assert (= (assoc v :after 1) @a) "write after a placed value differs"))
    (row "(a fresh pre-sized domain + atom alone)" (fmt r-dom))
    (row "swap! of the plain value (direct writer + hold + CAS)" (fmt r-plain))
    (row "swap! placing the image by run claims (+ hold + CAS)" (fmt r-image)
         (format "   = %.1fx faster, net of the domain" (/ (- (:min r-plain) (:min r-dom)) (max 0.001 (- (:min r-image) (:min r-dom))))))
    (row "swap! building in a bump buffer, compacting, placing" (fmt r-via)
         (format "   = %.2fx the plain swap" (/ (:min r-via) (:min r-plain))))
    (let [a2 (store-plain)
          ctx2 (alloc/sio-root (ea/atom-sio a2))
          r-deref (bench #(deref a2))
          r-snap (bench 5 9 #(ea/read-pinned a2 (fn [val] (slab-image ctx2 (d/-root-header-off val)))))
          snap (ea/read-pinned a2 (fn [val] (slab-image ctx2 (d/-root-header-off val))))]
      (assert (= v (d/eve->clj (L/image-view snap))) "pinned image differs")
      (row "@a (eve->clj under the pin)" (fmt r-deref))
      (row "an image copied under the pin" (fmt r-snap) (format "   = %.1fx faster" (/ (:min r-deref) (:min r-snap)))))))

;; ---------------------------------------------------------------------------------------------------------------
(println)
(println "== 6.2 sharing: one 1,000-element vector stored at k places of an eve map (same domain: by pointer)")
(doseq [k [1 10 100]]
  (let [ctx (L/slab-domain {0 (* 8 1024 1024) 2 (* 4 1024 1024)})
        rs (L/rec-sio ctx)
        big (L/reopen rs (L/build! rs (vec (range 1000))))
        m (reduce (fn [m i] (assoc m (keyword (str "k" i)) big)) (em/empty-hash-map rs) (range k))
        im (slab-image ctx (d/-root-header-off m))
        plain (d/eve->clj m)]
    (assert (= plain (d/eve->clj (L/image-view im))))
    (println (format "    k=%-4d image %,9d B (%,6d blocks) | eve flat codec %,10d B | edn %,9d B | distinct host vectors after eve->clj: %d"
                     k (:len im) (:nblocks im) (alength ^bytes (codec/value->eve-bytes plain))
                     (count (.getBytes (pr-str plain) "UTF-8"))
                     (count (distinct (map #(System/identityHashCode %) (vals plain))))))))

;; ---------------------------------------------------------------------------------------------------------------
(println)
(println "== 6.3 retention")
(let [v (zipmap (range 10000) (range 10000))
      bs (L/bump-sio (* 64 1024 1024) true)
      m0 (L/reopen bs (L/build! bs v))
      base (.tail ^static_probe.lib.IBump bs)
      live0 (:len (bump-image bs (d/-root-header-off m0)))]
  (println (format "    a 10k-entry map built by the direct writer into an append-only buffer: buffer %,d B, live image %,d B (%.1f%% live — the rest is what the writer's own path copies superseded)"
                   base live0 (* 100.0 (/ (double live0) base))))
  (let [m1 (reduce (fn [m i] (assoc m (+ 20000 i) i)) m0 (range 100))
        t1 (.tail ^static_probe.lib.IBump bs)
        live1 (:len (bump-image bs (d/-root-header-off m1)))]
    (println (format "    + 100 single-key assocs on it: the buffer grew %,d B (%,d B an update); the newest view's live image is %,d B; an old view still reads, and pins the whole %,d B"
                     (- t1 base) (quot (- t1 base) 100) live1 t1))))
(let [msgs (C/messages 5000)
      ctx (L/slab-domain {0 (* 8 1024 1024) 1 (* 4 1024 1024) 2 (* 8 1024 1024) 3 (* 8 1024 1024)})
      off (L/build! ctx msgs)
      im (slab-image ctx off)
      view (L/image-view im)
      one (nth view 2500)                              ;; a view of one message: it holds the whole image
      one-im (let [bs (.buf ^static_probe.lib.IBump (d/-eve-sio one))]
               (bump-image (d/-eve-sio one) (d/-root-header-off one)))
      r (bench 5 9 #(bump-image (d/-eve-sio one) (d/-root-header-off one)))]
  (assert (= (nth msgs 2500) (d/eve->clj (L/image-view one-im))))
  (println (format "    one message read out of a 5,000-message image: the view pins %,d B to hold %,d B (%.3f%%); copied out on its own by the same walk in %.1f us"
                   (:len im) (:len one-im) (* 100.0 (/ (double (:len one-im)) (:len im))) (* 1000.0 (:min r)))))

;; ---------------------------------------------------------------------------------------------------------------
(println)
(println "== 6.4 column encodings on a datom table (30,000 datoms of 5,000 people, EAVT order): bytes, and one value read")
(let [ds (C/datoms 5000)
      n (count ds)
      es (int-array (map :e ds))
      txs (int-array (map #(int (- (long (:tx %)) 536870913)) ds))
      rnd (Random. 3)
      idx (int-array (repeatedly 200000 #(.nextInt rnd n)))
      ;; narrowed: the smallest of 1, 2, 4 bytes that holds max - min, read as base + stored
      narrow (fn [^ints xs]
               (let [lo (areduce xs i m Integer/MAX_VALUE (min m (aget xs i)))
                     hi (areduce xs i m Integer/MIN_VALUE (max m (aget xs i)))
                     w (cond (< (- hi lo) 256) 1 (< (- hi lo) 65536) 2 :else 4)
                     b (byte-array (* w n))]
                 (dotimes [i n]
                   (let [d (- (aget xs i) lo)]
                     (dotimes [k w] (aset b (+ (* i w) k) (unchecked-byte (bit-shift-right d (* 8 k)))))))
                 {:w w :lo lo :b b}))
      narrow-get (fn ^long [{:keys [^long w ^long lo ^bytes b]} ^long i]
                   (let [p (* i w)]
                     (+ lo (case (int w)
                             1 (bit-and (aget b p) 0xFF)
                             2 (bit-or (bit-and (aget b p) 0xFF) (bit-shift-left (bit-and (aget b (inc p)) 0xFF) 8))
                             4 (L/i32 b p)))))
      ;; run-end: values[r], ends[r] = the index after run r's last element; a read is a binary search over ends
      run-end (fn [^ints xs]
                (let [vals (java.util.ArrayList.) ends (java.util.ArrayList.)]
                  (loop [i 0]
                    (when (< i n)
                      (let [x (aget xs i)
                            j (loop [j (inc i)] (if (and (< j n) (== x (aget xs j))) (recur (inc j)) j))]
                        (.add vals x) (.add ends j) (recur j))))
                  {:vals (int-array vals) :ends (int-array ends)}))
      re-get (fn ^long [{:keys [^ints vals ^ints ends]} ^long i]
               (let [j (Arrays/binarySearch ends (int (inc i)))]
                 (long (aget vals (if (neg? j) (- (- j) 1) j)))))
      time-get (fn [f] (/ (* 1e6 (:min (bench #(loop [j 0 acc 0] (if (== j 200000) acc (recur (inc j) (+ acc (long (f (aget idx j)))))))))) 200000))]
  (doseq [[name ^ints xs] [[":e (sorted, 6 datoms an entity)" es] [":tx (one transaction per 100 people)" txs]]]
    (let [nw (narrow xs) re (run-end xs)]
      (dotimes [i 1000] (let [k (aget idx i)] (assert (== (aget xs k) (narrow-get nw k) (re-get re k)))))
      (println (format "    %-38s plain i32 %,8d B %5.1f ns | narrowed to %d B %,8d B %5.1f ns | run-end %,7d B (%,d runs) %5.1f ns"
                       name (* 4 n) (time-get (fn [i] (aget xs i)))
                       (:w nw) (alength ^bytes (:b nw)) (time-get (fn [i] (narrow-get nw i)))
                       (* 8 (alength ^ints (:vals re))) (alength ^ints (:vals re)) (time-get (fn [i] (re-get re i))))))))

(println)
(println "P6 done")
(System/exit 0)
