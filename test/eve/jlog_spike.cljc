(ns eve.jlog-spike
  "The jlog spike's driver (plan §5), one program for the JVM, jolt and Node:
   writers on several runtimes append to ONE <base>.jlog at once, each
   record's table a snapshot of a shared mapped 'root' (<base>.sroot: an epoch
   word and one counter per writer), as eve.atom's commit will.

     init   <base> <slots>                 create <base>.sroot and <base>.jlog
     writer <base> <w> <start-ms> <dur-ms> [slow]
                                           from start-ms (epoch ms) for dur-ms:
                                           bump w's counter, bump the epoch,
                                           append; print ACK w counter seq label
                                           once the append's force returned;
                                           'slow' makes every 50th append sleep
                                           150 ms between its write and its force
                                           (the others then lap it: back-pressure)
     scan   <base>                         the valid records, R* and its table
     golden                                a fixed record's bytes and CRCs

   scripts/jlog-spike.sh runs it across runtimes and scripts/jlog_spike_check.clj
   judges the plan's exit criteria."
  (:refer-clojure :exclude [aget aset alength byte-array unchecked-byte])
  (:require
   [eve.host :refer [aget aset alength byte-array unchecked-byte]]
   [eve.crc32 :as crc]
   [eve.encoding :as enc]
   [eve.int32 :as i32]
   [eve.jlog :as jlog]
   [eve.mem :as mem]
   [eve.process :as proc]
   [eve.region :as region]
   [clojure.string :as str]))

(def ^:private SROOT_BYTES 4096)
(def ^:private EPOCH_OFF 0)
(def ^:private TABLE_OFF 64)
(def ^:private HB_OFF 2560)
(def ^:private STALE_DS 30)              ;; a writer whose heartbeat is 3 s old is dead

(defn- now-ns []
  #?(:cljs (* 1e6 (.now js/performance))
     :default (System/nanoTime)))

(defn- emit!
  "One line to stdout, written through before the next step (an ACK line
   must not sit in a buffer when the writer is killed)."
  [s]
  #?(:cljs (.writeSync (js/require "fs") 1 (str s "\n"))
     :default (do (println s) (flush))))

(defn- now-ds [] (i32/i32 (quot (proc/now-ms) 100)))

(defn- sroot [base] (region/open-mmap-region (str base ".sroot") SROOT_BYTES))

(defn- hex [b n]
  (apply str (for [i (range n)]
               (let [v (bit-and (aget b i) 0xFF)
                     h "0123456789abcdef"]
                 (str (nth h (quot v 16)) (nth h (mod v 16)))))))

(defn- counter
  "Writer W's counter in a record's TABLE: the little-endian i32 at 8w."
  [table w]
  (let [o (* 8 w)
        u #(bit-and (aget table (+ o %)) 0xFF)]
    (i32/i32 (bit-or (u 0) (bit-shift-left (u 1) 8) (bit-shift-left (u 2) 16) (bit-shift-left (u 3) 24)))))

(defn- init! [base slots]
  (let [r (sroot base)]
    (mem/-store-i32! r EPOCH_OFF 0)
    (mem/sync-region! r 0 SROOT_BYTES))
  (let [log (jlog/create! (str base ".jlog") {:slots slots})]
    (emit! (str "INIT slots " (:n log) " nonce " (:nonce log)))))

(defn- percentile [xs p]
  (let [v (vec (sort xs))]
    (if (empty? v) 0 (nth v (min (dec (count v)) (long (* p (count v))))))))

(defn- writer! [base w start-ms dur-ms slow?]
  (let [r (sroot base)
        log (jlog/open (str base ".jlog"))
        hb! #(mem/-store-i32! r (+ HB_OFF (* 4 w)) (now-ds))
        stale? (fn [owner] (> (- (now-ds) (mem/-load-i32 r (+ HB_OFF (* 4 owner)))) STALE_DS))
        snapshot! (fn [] [(mem/-load-i32 r EPOCH_OFF) (mem/-read-bytes r TABLE_OFF jlog/TABLE_BYTES)])]
    (hb!)
    (loop [] (when (< (proc/now-ms) start-ms) (proc/sleep-ms! 1) (hb!) (recur)))
    (let [end (+ start-ms dur-ms)]
      (loop [c 1 times [] waits 0]
        (if (< (proc/now-ms) end)
          (let [_ (mem/-store-i32! r (+ TABLE_OFF (* 8 w)) c)   ;; the "CAS": w's counter published
                _ (mem/-add-i32! r EPOCH_OFF 1)                  ;; the epoch bump
                t0 (now-ns)
                hook (when (and slow? (zero? (mod c 50))) #(proc/sleep-ms! 150))
                res (jlog/append! log 0 w snapshot! {:stale? stale? :stale-ms 3000 :before-force hook})
                dt (- (now-ns) t0)]
            (emit! (str "ACK " w " " c " " (:seq res) " " (:label res)))
            (hb!)
            (recur (inc c) (if hook times (conj times dt)) (+ waits (:waits res))))
          (emit! (str "STATS " w " appends " (dec c) " waits " waits
                      " p50ns " (long (percentile times 0.5)) " p90ns " (long (percentile times 0.9)))))))))

(defn- scan! [base nwriters]
  (let [log (jlog/open (str base ".jlog"))
        recs (sort-by :seq (jlog/scan log))
        rstar (jlog/pick recs)]
    (doseq [rec recs]
      (emit! (str "REC " (:seq rec) " " (:label rec) " " (:writer rec) " " (:crc rec))))
    (emit! (str "VALID " (count recs)))
    (when rstar
      (emit! (str "RSTAR " (:seq rstar) " " (:label rstar)))
      (emit! (str "RTABLE " (str/join " " (for [w (range nwriters)] (counter (:table rstar) w))))))))

(defn- golden! []
  (let [table (byte-array jlog/TABLE_BYTES)]
    (dotimes [i jlog/TABLE_BYTES] (aset table i (unchecked-byte (bit-and (* i 7) 0xFF))))
    (let [rec (jlog/encode-record 7 0 12345 jlog/KIND_COMMIT 0x01020304 3 table)
          check (enc/utf8-bytes "123456789")]
      (emit! (str "GOLDEN-CRC-CHECK " (crc/crc32 check 0 9) " " (crc/crc32-portable check 0 9)))
      (emit! (str "GOLDEN-REC " (hex rec 40) " len " (alength rec)
                  " crc " (crc/crc32 rec 0 jlog/REC_CRC_OFF) " portable " (crc/crc32-portable rec 0 jlog/REC_CRC_OFF)))
      (emit! (str "GOLDEN-DECODE " (some-> (jlog/decode-record rec 0 7 8 0x01020304) (select-keys [:seq :label :writer :kind])))))))

(defn -main [& args]
  (let [[mode base a b c d] args
        num #?(:cljs js/parseInt :default #(Long/parseLong %))]
    (case mode
      "init" (init! base (num a))
      "writer" (writer! base (num a) (num b) (num c) (= d "slow"))
      "scan" (scan! base (num (or a "6")))
      "golden" (golden!))))

#?(:cljs (defn main [& args] (apply -main args)))
