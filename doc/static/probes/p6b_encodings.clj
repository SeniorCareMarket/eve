;; P6b — A18 again, measured fairly. P6 §6.4 read its narrowed and run-end columns through a Clojure map
;; destructured on every call, which priced the map, not the encoding. Here each reader is a closure over
;; primitives. A datom table (30,000 datoms of 5,000 people, EAVT order): bytes, and the cost of ONE value read at a
;; random index, for a plain i32 column, a narrowed one (frame of reference: the smallest of 1 / 2 / 4 bytes that
;; holds max − min) and a run-end one (a value per run, the index after its last element; a read is a binary search).
;;
;; Run (from /home/user/eve):  java -Xmx2g -cp "$(doc/static/probes/eve-cp.sh)" clojure.main doc/static/probes/p6b_encodings.clj
(load-file "doc/static/probes/lib.clj")
(load-file "doc/static/probes/corpora.clj")
(ns p6b-encodings
  (:require [static-probe.lib :as L :refer [bench]]
            [static-probe.corpora :as C])
  (:import [java.util Random Arrays ArrayList]))

(set! *unchecked-math* true)
(println "P6b — eve" (clojure.string/trim (slurp ".git/HEAD")) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(definterface IGet (^long at [^long i]))

(defn plain-reader [^ints xs] (reify IGet (at [_ i] (long (aget xs i)))))

(defn narrow-reader
  "[reader bytes width]"
  [^ints xs]
  (let [n (alength xs)
        lo (long (areduce xs i m Integer/MAX_VALUE (min m (aget xs i))))
        hi (long (areduce xs i m Integer/MIN_VALUE (max m (aget xs i))))
        w (cond (< (- hi lo) 256) 1 (< (- hi lo) 65536) 2 :else 4)
        b (byte-array (* w n))]
    (dotimes [i n]
      (let [d (- (aget xs i) lo)]
        (dotimes [k w] (aset b (+ (* i w) k) (unchecked-byte (bit-shift-right d (* 8 k)))))))
    [(case (int w)
       1 (reify IGet (at [_ i] (+ lo (bit-and (long (aget b i)) 0xFF))))
       2 (reify IGet (at [_ i] (let [p (* 2 i)]
                                 (+ lo (bit-or (bit-and (long (aget b p)) 0xFF)
                                               (bit-shift-left (bit-and (long (aget b (inc p))) 0xFF) 8))))))
       4 (reify IGet (at [_ i] (+ lo (L/i32 b (* 4 i))))))
     (alength b) w]))

(defn run-end-reader
  "[reader bytes runs]"
  [^ints xs]
  (let [n (alength xs) vs (ArrayList.) es (ArrayList.)]
    (loop [i 0]
      (when (< i n)
        (let [x (aget xs i)
              j (loop [j (inc i)] (if (and (< j n) (== x (aget xs j))) (recur (inc j)) j))]
          (.add vs x) (.add es j) (recur j))))
    (let [vals (int-array vs) ends (int-array es)]
      [(reify IGet (at [_ i]
                     (let [j (Arrays/binarySearch ends (int (inc i)))]
                       (long (aget vals (if (neg? j) (- (- j) 1) j))))))
       (* 8 (alength vals)) (alength vals)])))

(let [ds (C/datoms 5000)
      n (count ds)
      es (int-array (map :e ds))
      txs (int-array (map #(int (- (long (:tx %)) 536870913)) ds))
      rnd (Random. 3)
      idx (int-array (repeatedly 1000000 #(.nextInt rnd n)))
      time-get (fn [^IGet r]
                 (/ (* 1e6 (:min (bench 5 9 #(loop [j 0 acc 0] (if (== j 1000000) acc (recur (inc j) (+ acc (.at r (aget idx j))))))))) 1000000))
      empty-loop (/ (* 1e6 (:min (bench 5 9 #(loop [j 0 acc 0] (if (== j 1000000) acc (recur (inc j) (+ acc (long (aget idx j))))))))) 1000000)]
  (println (format "    (the loop and the index load alone: %.2f ns)" empty-loop))
  (doseq [[name ^ints xs] [[":e  (sorted; 6 datoms an entity)" es] [":tx (one transaction per 100 people)" txs]]]
    (let [p (plain-reader xs)
          [^IGet nr nb w] (narrow-reader xs)
          [^IGet rr rb runs] (run-end-reader xs)]
      (dotimes [i 5000] (let [k (aget idx i)] (assert (== (aget xs k) (.at nr k) (.at rr k)))))
      (println (format "    %-38s plain i32 %,8d B %5.2f ns | narrowed to %d B: %,7d B %5.2f ns | run-end: %,7d B (%,d runs) %5.2f ns"
                       name (* 4 n) (time-get p) w nb (time-get nr) rb runs (time-get rr))))))
(println "P6b done")
(System/exit 0)
