;; P4 — what the chunk size costs to READ (research probe): A7's access half, A18 (encodings keep per-value
;; access), Q9b (columnar ops with row assembly interleaved). Rows packed into self-contained chunks of 4 KiB …
;; 1 MiB; then, per size, in one session on the same data:
;;   open        a chunk's tables decoded (the fixed cost of touching a chunk cold)
;;   decode      a whole chunk to plain rows (the decode unit)
;;   scan        sum a float64 column, count a keyword column = a value — in place, no decode
;;   point row   a random row assembled into a plain map from its columns (warm: tables open; cold: opened each time)
;;   point cell  one field of a random row
;; against the host's own collections (a vector of maps) as the baseline, and Transit / the flat codec for decode.
;;
;; Run (from /home/user/eve):  java -Xmx6g -cp "$(doc/static/probes/eve-cp.sh)" clojure.main doc/static/probes/p4_access.clj
(load-file "doc/static/probes/lib.clj")
(load-file "doc/static/probes/pack.clj")
(load-file "doc/static/probes/corpora.clj")
(ns p4-access
  (:require [static-probe.lib :as L :refer [bench]]
            [static-probe.pack :as P]
            [static-probe.corpora :as C]
            [eve.codec :as codec]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util Random Arrays]))

(println "P4 — eve" (clojure.string/trim (slurp ".git/HEAD")) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(defn locate
  "The index of the chunk holding row i, by binary search over the chunks' first rows."
  ^long [^longs starts ^long i]
  (let [j (Arrays/binarySearch starts i)] (if (neg? j) (- (- j) 2) j)))

(defn bt-of "The chunk's root batch, or nil when its root is not one (a chunk of a single row)." [ch]
  (try (P/batch ch) (catch Exception _ nil)))
(defn rows-of [ch] (P/read-node ch (:root ch)))
(defn row* [ch bt i] (if bt (P/row-at ch bt i) (nth (rows-of ch) i)))
(defn cell* [ch bt k i] (if bt (P/cell ch bt (P/col-index bt k) i) (get (nth (rows-of ch) i) k)))
(defn sum* [ch bt k] (if bt (P/f64-sum ch bt (P/col-index bt k)) (transduce (map k) + 0.0 (rows-of ch))))
(defn count=* [ch bt k v]
  (if bt
    (let [code (P/sym-code ch v)] (if (neg? code) 0 (P/count-sym= ch bt (P/col-index bt k) code)))
    (count (filter #(= v (get % k)) (rows-of ch)))))

(defn run [label rows scan?]
  (println)
  (println "--" label (format "(%,d rows)" (count rows)))
  (let [n (count rows)
        rnd (Random. 5)
        idx (int-array (repeatedly 20000 #(.nextInt rnd n)))
        idx2 (int-array (repeatedly 200000 #(.nextInt rnd n)))
        tm (let [out (ByteArrayOutputStream.)] (transit/write (transit/writer out :msgpack) rows) (.toByteArray out))
        flat (codec/value->eve-bytes rows)]
    ;; baselines on the host's own collections
    (println (format "   host vector of maps:   point row %.3f us | point cell %.1f ns%s"
                     (/ (* 1000.0 (:min (bench #(loop [j 0 acc 0] (if (== j 20000) acc (recur (inc j) (+ acc (count (nth rows (aget idx j)))))))))) 20000)
                     (/ (* 1e6 (:min (bench #(loop [j 0 acc 0.0] (if (== j 200000) acc (recur (inc j) (+ acc (double (or (:ts (nth rows (aget idx2 j))) 0))))))))) 200000)
                     (if scan?
                       (format " | scan sum %.2f ns/row | filter count %.2f ns/row"
                               (/ (* 1e6 (:min (bench #(transduce (map :score) + 0.0 rows)))) n)
                               (/ (* 1e6 (:min (bench #(count (filterv (fn [r] (identical? :eng (:dept r))) rows))))) n))
                       "")))
    (println (format "   whole-value decode:    transit-msgpack %.1f ms (%.2f us/row) | eve flat codec %.1f ms (%.2f us/row)"
                     (:min (bench 2 5 #(transit/read (transit/reader (ByteArrayInputStream. tm) :msgpack))))
                     (/ (* 1000.0 (:min (bench 2 5 #(transit/read (transit/reader (ByteArrayInputStream. tm) :msgpack))))) n)
                     (:min (bench 2 5 #(codec/decode nil flat)))
                     (/ (* 1000.0 (:min (bench 2 5 #(codec/decode nil flat)))) n)))
    (println "   target | chunks | open us/chunk | decode ms/chunk  us/row |"
             (if scan? "scan ns/row warm  cold | filter ns/row |" "")
             "point row us warm  cold | point cell ns")
    (doseq [t [4096 8192 16384 32768 65536 262144 1048576 nil]]
      (let [cs (P/chunks! rows t)
            nc (count cs)
            bytes (mapv :bytes cs)
            starts (long-array (reductions + 0 (map :rows (butlast cs))))
            opened (mapv P/open-chunk bytes)
            batches (mapv bt-of opened)
            t-open (:min (bench #(mapv P/open-chunk bytes)))
            t-dec (:min (bench 2 5 #(reduce (fn [acc b] (+ acc (count (P/unchunk b)))) 0 bytes)))
            ;; scans
            scan (when scan?
                   (let [warm (:min (bench #(loop [j 0 acc 0.0] (if (== j nc) acc (recur (inc j) (+ acc (double (sum* (nth opened j) (nth batches j) :score))))))))
                         cold (:min (bench #(loop [j 0 acc 0.0]
                                              (if (== j nc) acc
                                                  (let [ch (P/open-chunk (nth bytes j))]
                                                    (recur (inc j) (+ acc (double (sum* ch (bt-of ch) :score)))))))))
                         filt (:min (bench #(loop [j 0 acc 0]
                                              (if (== j nc) acc
                                                  (recur (inc j) (+ acc (long (count=* (nth opened j) (nth batches j) :dept :eng))))))))
                         check (loop [j 0 acc 0.0] (if (== j nc) acc (recur (inc j) (+ acc (double (sum* (nth opened j) (nth batches j) :score))))))]
                     (assert (< (Math/abs (- check (transduce (map :score) + 0.0 rows))) 1e-6))
                     [warm cold filt]))
            ;; point reads
            row-warm (:min (bench #(loop [j 0 acc 0]
                                     (if (== j 20000) acc
                                         (let [i (aget idx j) c (locate starts i)]
                                           (recur (inc j) (+ acc (count (row* (nth opened c) (nth batches c) (- i (aget starts c)))))))))))
            row-cold (:min (bench 1 3 #(loop [j 0 acc 0]
                                         (if (== j 2000) acc
                                             (let [i (aget idx j) c (locate starts i)
                                                   ch (P/open-chunk (nth bytes c)) bt (bt-of ch)]
                                               (recur (inc j) (+ acc (count (row* ch bt (- i (aget starts c)))))))))))
            cell (:min (bench #(loop [j 0 acc 0.0]
                                 (if (== j 200000) acc
                                     (let [i (aget idx2 j) c (locate starts i)]
                                       (recur (inc j) (+ acc (double (or (cell* (nth opened c) (nth batches c) :ts (- i (aget starts c))) 0)))))))))]
        ;; correctness of the in-place row
        (let [i (aget idx 0) c (locate starts i)]
          (assert (= (nth rows i) (row* (nth opened c) (nth batches c) (- i (aget starts c)))) "row-at differs"))
        (println (format "   %7s | %6d | %13.1f | %15.3f %7.2f |%s %12.3f %5.2f | %13.1f"
                         (if t (str (quot t 1024) "K") "one") nc
                         (/ (* 1000.0 t-open) nc) (/ t-dec nc) (/ (* 1000.0 t-dec) n)
                         (if scan
                           (format " %16.2f %5.2f | %13.2f |" (/ (* 1e6 (nth scan 0)) n) (/ (* 1e6 (nth scan 1)) n) (/ (* 1e6 (nth scan 2)) n))
                           "")
                         (/ (* 1000.0 row-warm) 20000) (/ (* 1000.0 row-cold) 2000)
                         (/ (* 1e6 cell) 200000)))))))

(run "50k records" (C/records 50000) true)
(run "10k chat messages" (C/messages 10000) false)

(println)
(println "P4 done")
(System/exit 0)
