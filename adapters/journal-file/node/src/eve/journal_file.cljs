(ns eve.journal-file
  "The commit journal's file handle (eve.atom's durability mode): an
   append-only per-domain file beside the domain root, holding commit
   records only. This host: ClojureScript on Node — an fd opened in
   append mode; the journal's lock is a lock DIRECTORY beside the file
   (mkdir is the atomic test-and-set; it excludes the JVM's and the
   other hosts' seats only through the same directory protocol).

   Record format (identical bytes on every host):
     [magic i32][len i32][epoch i32][table-len i32][table bytes][crc32 i32]
   magic = \"EVJ1\" (0x45564A31); all integers big-endian; len counts the
   bytes after itself (epoch + table-len + table + crc); the CRC-32
   covers the epoch..table-end bytes. The commit point is lock, write
   blocks, force slab, append record, force journal — eve.atom runs
   that sequence over this file."
  (:require [eve.mem :as mem]
            [eve.deftype-proto.data :as d]))

(def ^:private journal-magic 0x45564A31)   ;; "EVJ1"
(def ^:private journal-table-bytes (* d/MAX_ATOM_SLOTS d/ATOM_SLOT_SIZE))

;; CRC-32 (IEEE), the JS journal record's checksum.
(def ^:private crc-table
  (let [t (js/Uint32Array. 256)]
    (dotimes [n 256]
      (loop [c n i 0]
        (if (< i 8)
          (recur (if (zero? (bit-and c 1))
                   (unsigned-bit-shift-right c 1)
                   (bit-xor 0xEDB88320 (unsigned-bit-shift-right c 1)))
                 (inc i))
          (aset t n c))))
    t))

(defn- crc32-of
  "CRC-32 of the Uint8Array SUB from OFF for LEN bytes, as a signed i32."
  [^js sub off len]
  (loop [c 0xFFFFFFFF i 0]
    (if (< i len)
      (recur (bit-xor (unsigned-bit-shift-right c 8)
                      (aget crc-table (bit-and (bit-xor c (aget sub (+ off i))) 0xFF)))
             (inc i))
      (bit-xor c 0xFFFFFFFF))))

(defn open-journal
  "Open (creating if needed) the journal file at PATH for read+append.
   Returns the file handle {:fd :path}."
  [path]
  (let [fs (js/require "fs")]
    {:fd (.openSync fs path "a+") :path path}))

(defn with-journal-lock
  "Run F holding the journal's lock: the .journal.lock directory beside
   the journal file (mkdir is atomic test-and-set). Spins with a short
   Atomics.wait on contention. A holder that dies leaves the
   directory; stale-lock recovery is the same heartbeat question as
   the domain's and out of scope — the lock is only ever held for one
   flush's duration."
  [{:keys [path]} f]
  (let [fs (js/require "fs")
        dir (str path ".lock")
        sab (js/SharedArrayBuffer. 4)
        view (js/Int32Array. sab)]
    (loop [tries 0]
      (let [got (try (.mkdirSync fs dir) true (catch :default _ false))]
        (cond got (try (f) (finally (try (.rmdirSync fs dir) (catch :default _ nil))))
              (> tries 6000) (throw (ex-info "journal lock timeout" {:dir dir}))
              :else (do (js/Atomics.wait view 0 0 5) (recur (inc tries))))))))

(defn journal-record
  "The commit record for ROOT-R's CURRENT epoch and atom table, as a
   Uint8Array."
  [root-r]
  (let [tbl (js/Uint8Array. (mem/-read-bytes root-r d/ATOM_TABLE_START journal-table-bytes))
        epoch (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)
        len (+ 4 4 (.-length tbl) 4)
        out (js/Uint8Array. (+ 8 len))
        dv (js/DataView. (.-buffer out))]
    (.setInt32 dv 0 journal-magic false)
    (.setInt32 dv 4 len false)
    (.setInt32 dv 8 epoch false)
    (.setInt32 dv 12 (.-length tbl) false)
    (.set out tbl 16)
    (.setInt32 dv (+ 16 (.-length tbl)) (crc32-of out 8 (+ 4 4 (.-length tbl))) false)
    out))

(defn journal-record-of
  "The commit record for an explicit EPOCH and TABLE (a Uint8Array)."
  [epoch tbl]
  (let [len (+ 4 4 (.-length tbl) 4)
        out (js/Uint8Array. (+ 8 len))
        dv (js/DataView. (.-buffer out))]
    (.setInt32 dv 0 journal-magic false)
    (.setInt32 dv 4 len false)
    (.setInt32 dv 8 epoch false)
    (.setInt32 dv 12 (.-length tbl) false)
    (.set out tbl 16)
    (.setInt32 dv (+ 16 (.-length tbl)) (crc32-of out 8 (+ 4 4 (.-length tbl))) false)
    out))

(defn journal-scan
  "Scan the journal's records in order. Returns {:newest {:epoch :table}
   :oldest epoch :torn? bool} — a record is accepted iff its framing
   survives (magic + length in range) and its CRC matches; a record whose
   framing survives but whose CRC fails is SKIPPED (its length still
   advances the scan); a framing failure ends the scan."
  [{:keys [path]}]
  (let [fs (js/require "fs")
        ^js data (try (.readFileSync fs path) (catch :default _ (js/Uint8Array. 0)))
        dv (js/DataView. (.-buffer data) (.-byteOffset data) (.-byteLength data))
        total (.-length data)]
    (loop [pos 0 newest nil oldest nil torn? false]
      (let [remaining (- total pos)]
        (cond
          (< remaining 8) {:newest newest :oldest oldest
                           :torn? (or torn? (pos? remaining))}
          :else
          (let [magic (.getInt32 dv pos false)
                len (.getInt32 dv (+ pos 4) false)]
            (if (or (not= magic journal-magic)
                    (not (pos? len))
                    (> len (- remaining 8)))
              {:newest newest :oldest oldest :torn? true}
              (let [epoch (.getInt32 dv (+ pos 8) false)
                    tlen (.getInt32 dv (+ pos 12) false)]
                (if (> (+ 4 4 tlen 4) len)
                  {:newest newest :oldest oldest :torn? true}
                  (let [tbl (.slice data (+ pos 16) (+ pos 16 tlen))]
                    (if (= (.getInt32 dv (+ pos 16 tlen) false)
                           (crc32-of data (+ pos 8) (+ 4 4 tlen)))
                      (recur (+ pos 8 len) {:epoch epoch :table tbl}
                             (or oldest epoch) torn?)
                      (recur (+ pos 8 len) newest oldest true))))))))))))

(defn journal-append!
  "Write BYTES (a Uint8Array) at the end of the journal file (the fd is
   in append mode). Does not force — the caller forces at the commit
   point."
  [{:keys [fd]} bytes]
  (let [fs (js/require "fs")]
    (.writeSync fs fd bytes)))

(defn journal-write-at!
  "Write BYTES (a Uint8Array) at absolute offset POS."
  [{:keys [fd]} bytes pos]
  (let [fs (js/require "fs")]
    (.writeSync fs fd bytes 0 (.-length ^js bytes) pos)))

(defn journal-truncate!
  "Truncate the journal file to zero length (no force; the caller
   forces as part of its checkpoint/recovery sequence)."
  [{:keys [fd]}]
  (let [fs (js/require "fs")]
    (.ftruncateSync fs fd 0)))

(defn journal-force!
  "Force the journal file to storage. Node's fsyncSync forces data and
   metadata alike — FULL? is ignored (the stronger force is taken on
   both paths, as the spike's JS arm did)."
  [{:keys [fd]} full?]
  (let [fs (js/require "fs")]
    (.fsyncSync fs fd)))

(defn journal-close!
  "Close the journal file; never throws."
  [{:keys [fd]}]
  (let [fs (js/require "fs")]
    (try (.closeSync fs fd) (catch :default _ nil))))
