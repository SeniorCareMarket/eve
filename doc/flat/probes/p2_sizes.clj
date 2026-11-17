;; P2 — what the bytes cost (research probe): A6 (do per-chunk tables pay on Eve-shaped data), A7 (which chunk
;; size), Q12 (cap or target; small values). Same session, same data, every arm:
;;
;;   incumbents   EDN text · Transit JSON · Transit MessagePack · eve's flat codec (eve.codec/value->eve-bytes)
;;   packed       the prototype chunk (doc/static/probes/pack.clj) with each mechanism switched on in turn:
;;                bare (eve scalar bytes, offset tables) → +symbol table → +shapes → +batches → +typed vectors
;;   each also under zlib level 6, as a reference for what opaque compression would still remove
;;
;; then the chunk-size sweep: rows packed into self-contained chunks of 4 KiB … 1 MiB (each with its own tables).
;;
;; Run (from /home/user/eve):  java -Xmx6g -cp "$(doc/static/probes/eve-cp.sh)" clojure.main doc/static/probes/p2_sizes.clj
(load-file "doc/static/probes/pack.clj")
(load-file "doc/static/probes/corpora.clj")
(ns p2-sizes
  (:require [static-probe.pack :as P]
            [static-probe.corpora :as C]
            [eve.codec :as codec]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayOutputStream]))

(println "P2 — eve" (clojure.string/trim (slurp ".git/HEAD")) "| clojure" (clojure-version)
         "| java" (System/getProperty "java.version"))

(defn transit-bytes ^bytes [v fmt]
  (let [out (ByteArrayOutputStream. 4096)]
    (transit/write (transit/writer out fmt) v)
    (.toByteArray out)))

(defn same? [a b] (or (= a b) (= (pr-str a) (pr-str b))))

(def variants
  [["packed: bare"            {:sym? false :shape? false :batch? false :tvec? false}]
   ["packed: +symbols"        {:sym? true  :shape? false :batch? false :tvec? false}]
   ["packed: +shapes"         {:sym? true  :shape? true  :batch? false :tvec? false}]
   ["packed: +batches"        {:sym? true  :shape? true  :batch? true  :tvec? false}]
   ["packed: +typed vectors"  {:sym? true  :shape? true  :batch? true  :tvec? true}]])

(def corpora
  [["10k chat messages"              (C/messages 10000)]
   ["50k records (rows)"             (C/records 50000)]
   ["50k records (column-major)"     (C/columns 50000)]
   ["30k datoms (5k people)"         (C/datoms 5000)]
   ["map 10k int->int"               (zipmap (range 10000) (range 10000))]
   ["vector 100k ints"               (vec (range 100000))]
   ["repo EDN files (6)"             (C/repo-edn ".")]])

(println)
(println "== 1. sizes, one self-contained value each (bytes; in parentheses the same bytes under zlib-6)")
(doseq [[label v] corpora]
  (println)
  (println "--" label)
  (let [edn (.getBytes (pr-str v) "UTF-8")
        tj (transit-bytes v :json)
        tm (transit-bytes v :msgpack)
        flat (try (codec/value->eve-bytes v) (catch Throwable t nil))
        base (alength tm)
        line (fn [name ^bytes bs & more]
               (println (format "   %-26s %,12d  (%,10d)   x%.2f of transit-msgpack%s"
                                name (alength bs) (P/deflated-size bs) (/ (double (alength bs)) base)
                                (apply str more))))]
    (line "edn text" edn)
    (line "transit-json" tj)
    (line "transit-msgpack" tm)
    (if flat (line "eve flat codec" flat) (println "   eve flat codec: refused"))
    (doseq [[name opts] variants]
      (let [c (P/chunk! v opts)
            ok (same? v (P/unchunk (:bytes c)))]
        (line name (:bytes c)
              (format "   tables %,d B, %d symbols, %d shapes%s"
                      (:table-bytes c) (:n-sym c) (:n-shape c) (if ok "" "   ROUND-TRIP DIFFERS")))))))

(println)
(println "== 2. the chunk-size sweep: rows packed into self-contained chunks (all mechanisms on)")
(println "   target | chunks | rows/chunk | total bytes | vs one chunk | tables % | symbols/chunk | cache hit % | zlib total")
(doseq [[label rows] [["10k chat messages" (C/messages 10000)]
                      ["50k records (rows)" (C/records 50000)]
                      ["30k datoms (5k people)" (C/datoms 5000)]]]
  (println)
  (println "--" label)
  (let [one (alength ^bytes (:bytes (P/chunk! rows)))]
    (doseq [t [4096 8192 16384 32768 65536 262144 1048576 nil]]
      (let [cs (P/chunks! rows t)
            n (count cs)
            total (+ (reduce + (map #(alength ^bytes (:bytes %)) cs)) (* 8 n))   ;; + an 8-byte directory row a chunk
            tables (reduce + (map #(+ (:table-bytes %) (:header-bytes %)) cs))
            syms (reduce + (map :n-sym cs))
            occ (reduce + (map :occ-all cs))
            z (reduce + (map #(P/deflated-size (:bytes %)) cs))
            back (into [] (mapcat #(P/unchunk (:bytes %))) cs)
            mx (reduce max (map #(alength ^bytes (:bytes %)) cs))]
        (println (format "   %7s | %6d | %10.1f | %,11d | %11.3f | %7.2f%% | %13.1f | %10.2f%% | %,10d%s%s"
                         (if t (str (quot t 1024) "K") "one") n (/ (double (count rows)) n) total
                         (/ (double total) one) (* 100.0 (/ (double tables) total)) (/ (double syms) n)
                         (* 100.0 (- 1.0 (/ (double syms) (max 1 occ)))) z
                         (if (and t (> mx t)) (format "  (largest chunk %,d)" mx) "")
                         (if (same? rows back) "" "  ROUND-TRIP DIFFERS")))))))

(println)
(println "P2 done")
(System/exit 0)
