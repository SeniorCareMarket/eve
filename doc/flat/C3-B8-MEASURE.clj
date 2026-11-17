;; Lane C3 — board row B8, measured for real (the research figure was a proxy: M3/M8
;; compressed 1 MiB chunks alone and 64 KiB chunks alone, never sixteen 64 KiB chunks
;; under ONE compression). Arms, same rows, one session:
;;   container : the first 16 chunks (probe-packed at 64 KiB) + this lane's directory
;;               bytes, concatenated, one zlib stream over the run
;;   one       : the same rows packed as one chunk, one zlib stream
;;   per-chunk : each of the 16 chunks under its own zlib stream, summed (context)
;; Gate (IMPL-PLAN §3.1 B8): container ≤ 5 % over one.
;; Run: CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \
;;        clojure -M -i doc/flat/C3-B8-MEASURE.clj -e (c3-b8/-main)
;; The probe files declare flat-probe.* namespaces but sit flat in
;; doc/flat/probes/, so they are loaded by file, not by classpath require.
(load-file "doc/flat/probes/corpora.clj")
(load-file "doc/flat/probes/pack.clj")

(ns c3-b8
  (:require [eve.flat.container :as cont])
  (:import [java.io ByteArrayOutputStream]))

(alias 'C 'flat-probe.corpora)
(alias 'P 'flat-probe.pack)

(defn- cat-bytes ^bytes [parts]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes p parts] (.write out p 0 (alength p)))
    (.toByteArray out)))

(defn- deflated-size-dict
  "zlib (level 6) with a preset dictionary: the first chunk's bytes — whose symbol
   and shape tables are what every later chunk repeats — kept in window."
  ^long [^bytes bs ^bytes dict]
  (let [df (java.util.zip.Deflater. 6) out (byte-array 65536)]
    (.setDictionary df dict)
    (.setInput df bs) (.finish df)
    (loop [n 0] (if (.finished df) (do (.end df) n) (recur (+ n (.deflate df out)))))))

(defn measure [label rows]
  (let [chunks (P/chunks! rows 65536)
        sixteen (vec (take 16 chunks))
        rows16 (reduce + (map :rows sixteen))
        one (P/chunk! (subvec rows 0 rows16))
        dir (cont/directory-bytes {:chunks (mapv :bytes sixteen)
                                   :first-keys (vec (repeat 16 nil))
                                   :root {:chunk 0 :offset 32}})
        run (cat-bytes (conj (mapv :bytes sixteen) dir))
        z-container (cont/deflated-size run)
        z-one (cont/deflated-size (:bytes one))
        z-per (reduce + (map (fn [c] (cont/deflated-size (:bytes c))) sixteen))
        z-dict (deflated-size-dict run (:bytes (first sixteen)))
        over (* 100.0 (/ (- (double z-container) (double z-one)) (double z-one)))]
    (println (format "%s: rows-in-16-chunks=%d chunks-total=%d" label rows16 (count chunks)))
    (println (format "  raw: run=%d one=%d | zlib: container=%d one=%d per-chunk-summed=%d container-with-chunk0-dict=%d"
                     (alength run) (alength (:bytes one)) z-container z-one z-per z-dict))
    (println (format "  B8 overhead of 16-chunks-one-compression over one-chunk: %+.2f%% (gate <= +5%%); per-chunk-alone overhead: %+.2f%%; with-dict overhead: %+.2f%%"
                     over (* 100.0 (/ (- (double z-per) (double z-one)) (double z-one)))
                     (* 100.0 (/ (- (double z-dict) (double z-one)) (double z-one)))))
    {:label label :z-container z-container :z-one z-one :over-pct over}))

(defn -main []
  (let [results [(measure "messages (10k chat)" (C/messages 10000))
                 (measure "records (50k rows)" (C/records 50000))]]
    (println (if (every? (fn [r] (<= (:over-pct r) 5.0)) results)
               "B8 GATE: PASS on both corpora"
               "B8 GATE: FAIL on at least one corpus"))))
