(ns eve.flat.chunk-bench
  "Lane C1 board rows B6 (chunk bytes) and B7 (chunk reads), on the scoreboard
   corpora of IMPL-PLAN §3.1: 5,000 chat messages and 10,000 records (the
   research probes' generators, doc/flat/probes/corpora.clj — the same data
   MEASUREMENTS M2/M4 measured). Both arms in one session, minimum of N runs.
   Run: java -cp $(doc/flat/c1-cp.sh) clojure.main -m eve.flat.chunk-bench"
  (:require [cognitect.transit :as transit]
            [eve.codec :as codec])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]))

(load-file "doc/flat/probes/corpora.clj")
(def messages ((resolve 'flat-probe.corpora/messages) 5000))
(def records ((resolve 'flat-probe.corpora/records) 10000))

(defn- transit-bytes ^bytes [v]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :msgpack) v)
    (.toByteArray out)))

(defn- transit-read [^bytes bs]
  (transit/read (transit/reader (ByteArrayInputStream. bs) :msgpack)))

(defn- bench-min-ms
  "Minimum wall time in ms of (f) over n runs, after one warmup."
  [f n]
  (f)
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i n) best
        (let [t0 (System/nanoTime) _ (f) dt (/ (- (System/nanoTime) t0) 1e6)]
          (recur (inc i) (min best dt))))))

(defn- chunk-lib []
  (try (require 'eve.flat.chunk)
       (resolve 'eve.flat.chunk/pack-all)
       #_{:splint/disable [lint/catch-throwable]}   (catch Throwable _ nil)))

(defn- b7-arms
  "B7 read arms on the records corpus (M4's definitions, tables open: the
   chunk handles are opened once and reused across every timed run).
   scan = sum of one numeric column over every row; filter = count of rows
   whose keyword column equals a value; cell = one field of one row;
   row view = one row assembled into a plain map. Host arms (the plain
   vector of maps) run in the same session for the cell ratio."
  [rows]
  (let [pack-all (resolve 'eve.flat.chunk/pack-all)
        open (resolve 'eve.flat.chunk/open)
        unpack (resolve 'eve.flat.chunk/unpack)
        chunks (pack-all rows)
        handles (mapv open chunks)
        n (count rows)
        scan (fn [] (reduce (fn [acc h] (reduce (fn [a row] (+ a (long (:id row)))) acc h)) 0 handles))
        filt (fn [] (reduce (fn [acc h] (reduce (fn [a row] (if (= :eng (:dept row)) (inc a) a)) acc h)) 0 handles))
        mid-h (nth handles (quot (count handles) 2))
        mid-row (nth mid-h (quot (count mid-h) 2))
        cell (fn [] (:name mid-row))
        views (fn [] (dotimes [i 200] (unpack (nth mid-h (mod i (count mid-h))))))
        hscan (fn [] (reduce (fn [a row] (+ a (long (:id row)))) 0 rows))
        hfilt (fn [] (reduce (fn [a row] (if (= :eng (:dept row)) (inc a) a)) 0 rows))
        hrow (nth rows 5000)
        hcell (fn [] (:name hrow))
        scan-ms (bench-min-ms scan 5)
        filt-ms (bench-min-ms filt 5)
        hscan-ms (bench-min-ms hscan 5)
        hfilt-ms (bench-min-ms hfilt 5)
        cell-ns (* (bench-min-ms (fn [] (dotimes [_ 10000] (cell))) 5) 100.0)
        hcell-ns (* (bench-min-ms (fn [] (dotimes [_ 10000] (hcell))) 5) 100.0)
        view-us (* (bench-min-ms views 5) 5.0)
        scan-ns (/ (* scan-ms 1e6) n)
        filt-ns (/ (* filt-ms 1e6) n)]
    (println (format "B7 records check: packed scan sum %d = host %d; packed filter %d = host %d"
                     (scan) (hscan) (filt) (hfilt)))
    (println (format "B7 records packed scan %.2f ns/row (host %.2f) %s"
                     scan-ns (/ (* hscan-ms 1e6) n) (if (<= scan-ns 2.0) "GATE PASS" "GATE MISS")))
    (println (format "B7 records packed filter %.2f ns/row (host %.2f) %s"
                     filt-ns (/ (* hfilt-ms 1e6) n) (if (<= filt-ns 4.0) "GATE PASS" "GATE MISS")))
    (println (format "B7 records packed cell %.1f ns (host %.1f, ratio %.2fx) %s"
                     cell-ns hcell-ns (/ cell-ns hcell-ns) (if (<= cell-ns (* 2.0 hcell-ns)) "GATE PASS" "GATE MISS")))
    (println (format "B7 records packed row view %.3f us %s"
                     view-us (if (<= view-us 1.0) "GATE PASS" "GATE MISS")))))

(defn -main [& _]
  (let [pack-all (chunk-lib)]
    (doseq [[label rows] [["messages" messages] ["records" records]]]
      (let [tb (transit-bytes rows)
            flat (codec/value->eve-bytes rows)
            t-decode (bench-min-ms #(transit-read tb) 5)]
        (println (format "B6 %-9s transit-msgpack %,d bytes | eve flat %,d bytes | rows %,d"
                         label (alength tb) (alength flat) (count rows)))
        (println (format "B7 %-9s transit decode %.1f ms (%.2f us/row)"
                         label t-decode (/ (* t-decode 1000.0) (count rows))))
        (when pack-all
          (let [chunks (pack-all rows)
                total (reduce + (map #_{:splint/disable [lint/fn-wrapper]} (fn [b] (alength b)) chunks))
                unpack (resolve 'eve.flat.chunk/unpack)
                _open (resolve 'eve.flat.chunk/open)
                p-decode (bench-min-ms (fn [] (mapv unpack chunks)) 5)]
            (println (format "B6 %-9s packed %,d bytes in %d chunks = %.3fx transit-msgpack %s"
                             label total (count chunks) (/ (double total) (alength tb))
                             (if (<= total (* 0.9 (alength tb))) "GATE PASS" "GATE MISS")))
            (println (format "B7 %-9s packed decode %.1f ms (%.2f us/row) = %.2fx transit %s"
                             label p-decode (/ (* p-decode 1000.0) (count rows))
                             (/ t-decode p-decode)
                             (if (>= (/ t-decode p-decode) 2.5) "GATE PASS" "GATE MISS")))))))
    (when pack-all (b7-arms records)))
  (shutdown-agents))
