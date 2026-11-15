(ns eve.journal-file
  "EVJ1 commit journal for the :teavm seat. Record bytes are the format
   eve/adapters/journal-file/java writes:
     [magic i32][len i32][epoch i32][table-len i32][table][crc32 i32]
   magic = 0x45564A31 (\"EVJ1\"), big-endian; len counts the bytes after
   itself; CRC-32 covers epoch..table-end. The file itself is a host file
   (eve.journal imports). No other journal format.

   The header layout is defined ONCE in record-fields below, and both
   journal-record-of (the writer) and journal-scan (the reader) derive
   their byte offsets from it, so the two cannot diverge by editing one
   side. (This scan once read the fields after :epoch one i32 late —
   tlen at +16, table at +20, crc at +20+tlen, against the writer's
   +12/+16/+16+tlen — so replay on this seat recovered nothing, and no
   scan of this writer's own bytes ever ran here to catch it. Root
   cause and census: the BASE-2 entry in the C1 campaign report. The
   standing acceptance for 'EVJ1 engaged' on this seat is the
   write->scan->recover round-trip, ladder.journal-roundtrip.)"
  (:require [eve.mem :as mem]
            [eve.deftype-proto.data :as d])
  (:import [Evj1]
           [Crc32]
           [RegionBytes]))

(def ^:private journal-magic (unchecked-int 0x45564A31))
(def ^:private journal-table-bytes (* d/MAX_ATOM_SLOTS d/ATOM_SLOT_SIZE))

(def ^:private record-fields
  "The EVJ1 record header: four big-endian i32 fields in this order,
   then the table bytes, then a crc32 i32. This is the ONE definition
   of the layout — field-offset and table-offset are derived from it,
   and journal-record-of / journal-scan use nothing else."
  [:magic :len :epoch :table-len])

(def ^:private field-offset
  "Byte offset of each header field, derived from record-fields."
  (zipmap record-fields (map (fn [i] (* 4 i)) (range))))

(def ^:private table-offset
  "Byte offset of the table bytes: just past the header, derived
   from record-fields."
  (* 4 (count record-fields)))

(def ^:private payload-offset
  "Byte offset of the payload — the bytes the len field counts and
   the CRC covers start here, at the :epoch field."
  (field-offset :epoch))

(defn- push-path! [^String path]
  (Evj1/nameReset)
  (let [^bytes bs (.getBytes path "UTF-8")]
    (dotimes [i (alength bs)]
      (Evj1/nameByte (RegionBytes/get bs i)))))

(defn open-journal [path]
  (push-path! path)
  (let [fd (Evj1/open)]
    (when (neg? fd) (throw (ex-info "eve.journal-file: open failed" {:path path})))
    fd))

(defn- put-int! [^bytes a off v]
  (let [u (bit-and (long v) 0xffffffff)]
    (RegionBytes/put a (int off) (int (unsigned-bit-shift-right u 24)))
    (RegionBytes/put a (int (+ off 1)) (int (bit-and (unsigned-bit-shift-right u 16) 255)))
    (RegionBytes/put a (int (+ off 2)) (int (bit-and (unsigned-bit-shift-right u 8) 255)))
    (RegionBytes/put a (int (+ off 3)) (int (bit-and u 255)))))

(defn- get-int [^bytes a off]
  (bit-or (bit-shift-left (RegionBytes/get a off) 24)
          (bit-shift-left (RegionBytes/get a (+ off 1)) 16)
          (bit-shift-left (RegionBytes/get a (+ off 2)) 8)
          (RegionBytes/get a (+ off 3))))

(defn journal-record-of [epoch ^bytes table]
  (let [tlen (alength table)
        len (+ (- table-offset payload-offset) tlen 4)
        a (byte-array (+ payload-offset len))]
    (put-int! a (field-offset :magic) journal-magic)
    (put-int! a (field-offset :len) len)
    (put-int! a (field-offset :epoch) (int epoch))
    (put-int! a (field-offset :table-len) tlen)
    (System/arraycopy table 0 a table-offset tlen)
    (put-int! a (+ table-offset tlen)
              (Crc32/of a payload-offset (+ (- table-offset payload-offset) tlen)))
    a))

(defn journal-record [root-r]
  (journal-record-of (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)
                     (mem/-read-bytes root-r d/ATOM_TABLE_START journal-table-bytes)))

(defn- write-bytes! [fd pos ^bytes a]
  (dotimes [i (alength a)]
    (Evj1/writeAt fd (+ pos i) (RegionBytes/get a i))))

(defn journal-append! [fd ^bytes a]
  (write-bytes! fd (int (Evj1/size fd)) a)
  nil)

(defn journal-write-at! [fd ^bytes a pos]
  (write-bytes! fd (int pos) a)
  nil)

(defn journal-truncate! [fd]
  (Evj1/truncate fd)
  nil)

(defn journal-force! [fd _full?]
  (Evj1/force fd)
  nil)

(defn journal-close! [fd]
  (try (Evj1/close fd) (catch Throwable _ nil)))

(defn with-journal-lock [fd f]
  (Evj1/lock fd)
  (try (f) (finally (Evj1/unlock fd))))

(defn journal-scan [fd]
  (let [size (long (Evj1/size fd))]
    (if (zero? size)
      {:newest nil :oldest nil :torn? false}
      (let [^bytes raw (byte-array (int size))]
        (dotimes [i (int size)]
          (RegionBytes/put raw i (Evj1/readAt fd i)))
        (loop [pos 0 newest nil oldest nil torn? false]
          (let [remaining (- size pos)]
            (if (< remaining payload-offset)
              {:newest newest :oldest oldest :torn? (or torn? (pos? remaining))}
              (let [magic (get-int raw (+ pos (field-offset :magic)))
                    len (get-int raw (+ pos (field-offset :len)))]
                (if (or (not= magic (bit-and journal-magic 0xffffffff))
                        (not (pos? len))
                        (> len (- remaining payload-offset)))
                  {:newest newest :oldest oldest :torn? true}
                  (let [tlen (get-int raw (+ pos (field-offset :table-len)))]
                    (if (> (+ (- table-offset payload-offset) tlen 4) len)
                      {:newest newest :oldest oldest :torn? true}
                      (let [table (byte-array tlen)
                            _ (System/arraycopy raw (+ pos table-offset) table 0 tlen)
                            stored (get-int raw (+ pos table-offset tlen))
                            crc (Crc32/of raw (+ pos payload-offset)
                                          (+ (- table-offset payload-offset) tlen))]
                        (if (= stored (bit-and crc 0xffffffff))
                          (recur (+ pos payload-offset len)
                                 {:epoch (get-int raw (+ pos (field-offset :epoch))) :table table}
                                 (or oldest (get-int raw (+ pos (field-offset :epoch))))
                                 torn?)
                          (recur (+ pos payload-offset len) newest oldest true))))))))))))))
