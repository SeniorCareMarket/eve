(ns eve.journal-file
  "The commit journal's file (eve.atom's durability mode): an append-only
   per-domain file beside the domain root, holding commit records only.
   This host: the JVM — a FileChannel; the channel's own lock is the
   journal's lock (it conflicts with a ClojureCLR FileStream.Lock and
   a POSIX fcntl lock on the same path, measured).

   Record format (identical bytes on every host):
     [magic i32][len i32][epoch i32][table-len i32][table bytes][crc32 i32]
   magic = \"EVJ1\" (0x45564A31); all integers big-endian; len counts the
   bytes after itself (epoch + table-len + table + crc); the CRC-32
   covers the epoch..table-end bytes. The commit point is lock, write
   blocks, force slab, append record, force journal — eve.atom runs
   that sequence over this file."
  (:require [eve.mem :as mem]
            [eve.deftype-proto.data :as d])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file Paths StandardOpenOption OpenOption]
           [java.util.zip CRC32]))

(def ^:private journal-magic (unchecked-int 0x45564A31))        ;; "EVJ1"
(def ^:private journal-table-bytes (* d/MAX_ATOM_SLOTS d/ATOM_SLOT_SIZE))

(def ^:private journal-paths
  "The journal file's path by channel, for with-journal-lock's
   channel route (open-journal records it; journal-close! drops it)."
  (atom {}))

(def ^:private lock-close-ok?
  "Can this process call a method on a FileLock? Not on every seat
   that loads this adapter: a babashka refuses every method of the
   lock itself (sun.nio.ch.FileLockImpl — (.release fl) throws
   NoSuchFieldException: release there, and a journaled domain's open
   runs recovery under with-journal-lock, so every journaled open died
   in the release; measured on fleet bb 1.13.226, 2026-11-08).
   (.close fl) IS reachable there and is release()'s JDK-specified
   equivalent. Checked once per process, by locking a scratch file and
   closing the lock — the jvm-nio-lock region adapter's probe,
   carried here. EVE_BB_JOURNAL_LOCK=channel forces the channel route
   (its test)."
  (delay (and (not= "channel" (System/getenv "EVE_BB_JOURNAL_LOCK"))
              (let [f (java.io.File/createTempFile "eve-journal-lock" ".probe")
                    ^FileChannel fc (FileChannel/open (.toPath f)
                                                      (into-array OpenOption
                                                                  [StandardOpenOption/READ
                                                                   StandardOpenOption/WRITE]))]
                (try
                  (let [fl (.lock fc)]
                    (try
                      (.close fl)
                      true
                      (catch Exception _ false)))
                  (finally (.close fc) (.delete f)))))))

(defn open-journal
  "Open (creating if needed) the journal file at PATH for read+write.
   The path is remembered by channel: with-journal-lock's channel
   route needs it to open the lock channel (see lock-close-ok?)."
  [path]
  (let [fc (FileChannel/open (Paths/get path (into-array String []))
                             (into-array OpenOption
                                         [StandardOpenOption/CREATE
                                          StandardOpenOption/READ
                                          StandardOpenOption/WRITE]))]
    (swap! journal-paths assoc fc path)
    fc))

(defn with-journal-lock
  "Run F holding the journal file's exclusive lock (and this process's
   monitor on the channel, so the OS lock never overlaps within one
   process). The lock is released by closing it — FileLock.close(),
   the JDK-specified equivalent of release() — wherever that is
   reachable (lock-close-ok?); where even close is refused, the lock
   is taken on a channel of its own, opened for it and closed to
   release it."
  [^FileChannel fc f]
  (locking fc
           (if (and (not @lock-close-ok?) (get @journal-paths fc))
             (let [^FileChannel c (FileChannel/open (Paths/get (get @journal-paths
                                                                    fc)
                                                               (into-array String
                                                                           []))
                                                    (into-array OpenOption
                                                                [StandardOpenOption/READ
                                                                 StandardOpenOption/WRITE]))]
               (try
                 (.lock c)
                 (f)
                 (finally (.close c))))
             (let [fl (.lock fc)]
               (try
                 (f)
                 (finally (if @lock-close-ok? (.close fl) (.release fl))))))))

(defn journal-record-of
  "The commit record for an explicit EPOCH and TABLE (byte array), as
   a flipped ByteBuffer."
  [epoch ^bytes table]
  (let [tlen (alength table)
        len  (+ 4 4 tlen 4)
        buf  (ByteBuffer/allocate (+ 8 len))]
    (.putInt buf (int journal-magic))
    (.putInt buf (int len))
    (.putInt buf (int epoch))
    (.putInt buf tlen)
    (.put buf table)
    (let [crc (CRC32.)]
      (.update crc (.array buf) 8 (+ 4 4 tlen))
      (.putInt buf (unchecked-int (.getValue crc))))
    (.flip buf)
    buf))

(defn journal-record
  "The commit record for ROOT-R's CURRENT epoch and atom table."
  [root-r]
  (journal-record-of (mem/-load-i32 root-r d/ROOT_EPOCH_OFFSET)
                     (mem/-read-bytes root-r d/ATOM_TABLE_START journal-table-bytes)))

(defn journal-scan
  "Scan the journal's records in order. Returns {:newest {:epoch :table}
   :oldest epoch :torn? bool} — a record is accepted iff its framing
   survives (magic + length in range) and its CRC matches; a record
   whose framing survives but whose CRC fails is SKIPPED (its length
   still advances the scan — a dead earlier writer must not hide a
   later good record from any host); a framing failure ends the scan."
  [^FileChannel fc]
  (let [size (.size fc)]
    (if (zero? size)
      {:newest nil :oldest nil :torn? false}
      (let [buf (ByteBuffer/allocate (int size))]
        (.position fc 0)
        (while (.hasRemaining buf) (.read fc buf))
        (.flip buf)
        (loop [pos 0 newest nil oldest nil torn? false]
          (let [remaining (- size pos)]
            (if (< remaining 8)
              {:newest newest :oldest oldest
               :torn? (or torn? (pos? remaining))}
              (let [magic (.getInt buf pos)
                    len   (.getInt buf (+ pos 4))]
                (if (or (not= magic journal-magic)
                        (not (pos? len))
                        (> len (- remaining 8)))
                  {:newest newest :oldest oldest :torn? true}
                  (let [payload (byte-array len)]
                    (.position buf (+ pos 8))
                    (.get buf payload)
                    (let [pb     (ByteBuffer/wrap payload)
                          epoch  (.getInt pb)
                          tlen   (.getInt pb)]
                      (if (> (+ 4 4 tlen 4) len)
                        ;; a table length that cannot fit its own
                        ;; record: the tail is torn beyond framing.
                        {:newest newest :oldest oldest :torn? true}
                        (let [table  (byte-array tlen)
                              _      (.get pb table)
                              stored (.getInt pb)
                              crc    (CRC32.)]
                          (.update crc payload 0 (+ 4 4 tlen))
                          (if (= stored (unchecked-int (.getValue crc)))
                            (recur (+ pos 8 len)
                                   {:epoch epoch :table table}
                                   (or oldest epoch)
                                   torn?)
                            (recur (+ pos 8 len) newest oldest true)))))))))))))))

(defn journal-append!
  "Write BYTES (a flipped ByteBuffer) at the end of the journal file.
   Does not force — the caller forces at the commit point."
  [^FileChannel fc ^ByteBuffer buf]
  (.position fc (.size fc))
  (while (.hasRemaining buf)
    (.write fc buf)))

(defn journal-write-at!
  "Write BYTES (a flipped ByteBuffer) at absolute offset POS."
  [^FileChannel fc ^ByteBuffer buf pos]
  (.position fc (long pos))
  (while (.hasRemaining buf)
    (.write fc buf)))

(defn journal-truncate!
  "Truncate the journal file to zero length (no force; the caller
   forces as part of its checkpoint/recovery sequence)."
  [^FileChannel fc]
  (.truncate fc 0))

(defn journal-force!
  "Force the journal file to storage. FULL? true includes file metadata
   (a truncate/checkpoint boundary); false is the append path's
   data+size force."
  [^FileChannel fc full?]
  (.force fc (boolean full?)))

(defn journal-close!
  "Close the journal file; never throws."
  [^FileChannel fc]
  (swap! journal-paths dissoc fc)
  (try
    (.close fc)
    (catch Throwable _ nil)))
