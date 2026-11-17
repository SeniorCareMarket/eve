(ns eve.journal-file
  "The commit journal's file handle (eve.atom's durability mode): an
   append-only per-domain file beside the domain root, holding commit
   records only. This host: ClojureScript on Node — an fd opened in
   append mode; the journal's lock is a lock DIRECTORY beside the file
   (mkdir is the atomic test-and-set). The directory protocol excludes
   the other NODE-seat processes; it does not exclude the POSIX seats:
   the JVM, jolt, python and beam adapters lock the journal FILE
   itself with fcntl locks and never look at this directory, so two
   seats of different families can hold 'the journal lock' at once.
   That cross-family gap is recorded, not hidden: this adapter's
   protocol is the honest version of the one this seat can implement
   in pure JS (Node core has no flock), and within it the fleet's
   doctrine holds — wait while the holder lives, and a dead holder's
   lock never blocks the domain (see with-journal-lock).

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

;; The lock protocol's only tunables. lock-poll-ms is the contention
;; spin's sleep. lock-ownerless-stale-ms is the age after which a lock
;; directory with NO readable owner is judged abandoned (a pre-fix
;; leftover, or a holder that died in the mkdir→owner-write window —
;; that window is sub-millisecond, so this is three orders of margin);
;; a directory whose owner names a pid is judged by the pid's life,
;; never by age.
(def ^:private lock-poll-ms 5)
(def ^:private lock-ownerless-stale-ms 5000)

(defn- pid-alive?
  "Whether PID names a live process on this machine (signal 0: an
   ESRCH throw is death; success or EPERM is life)."
  [pid]
  (try (.kill js/process pid 0)
       true
       (catch :default e (= (.-code e) "EPERM"))))

(defn- read-owner
  "The lock directory's owner file as {:pid :token}, or nil when it is
   absent or unparseable. The holder writes it immediately after its
   mkdir, inside the directory only it can have created."
  [fs owner-path]
  (try (let [s (.readFileSync fs owner-path "utf8")
             [pid token] (.split s " ")
             pid (js/parseInt pid 10)]
         (when (and (not (js/isNaN pid)) token)
           {:pid pid :token token}))
       (catch :default _ nil)))

(defn- lock-stale?
  "Whether the lock directory at DIR is abandoned: its owner names a
   pid and the pid is dead, or it has no readable owner and the
   directory itself is older than lock-ownerless-stale-ms. A directory
   whose owner lives is never stale, however long the hold runs — the
   fleet's other seats wait on their fcntl locks without a timeout,
   and a peer's cold recovery under this lock takes as long as it
   takes."
  [fs dir owner-path]
  (let [owner (read-owner fs owner-path)]
    (cond owner (not (pid-alive? (:pid owner)))
          :else (try (> (- (js/Date.now) (.-mtimeMs (.statSync fs dir)))
                        lock-ownerless-stale-ms)
                     (catch :default _ false)))))

(defn- break-lock!
  "Dispose of a lock directory judged stale, by atomic rename: the
   directory moves to a graveyard name only one breaker can win, and
   what landed there is re-judged before anything is deleted — if the
   rename in fact took a FRESH holder's directory (its owner slipped
   in between the judgment and the rename), the directory goes back,
   with its owner file invalidated first so that, the theft having
   already broken that hold, later waiters judge it by the ownerless
   age rule instead of waiting on a pid that no longer holds it."
  [fs dir owner-path]
  (let [grave (str dir ".stale-" (.-pid js/process) "-"
                   (.toString (js/Math.random) 36))
        renamed (try (.renameSync fs dir grave) true
                     (catch :default _ false))]
    (when renamed
      (let [g-owner (read-owner fs (str grave "/owner"))
            dispose? (cond g-owner (not (pid-alive? (:pid g-owner)))
                           :else (try (> (- (js/Date.now)
                                            (.-mtimeMs (.statSync fs grave)))
                                         lock-ownerless-stale-ms)
                                      (catch :default _ true)))]
        (if dispose?
          (try (.rmSync fs grave #js {:recursive true :force true})
               (catch :default _ nil))
          (do (try (.unlinkSync fs (str grave "/owner"))
                   (catch :default _ nil))
              (try (.renameSync fs grave dir)
                   (catch :default _
                     (try (.rmSync fs grave #js {:recursive true :force true})
                          (catch :default _ nil))))))))))

(defn with-journal-lock
  "Run F holding the journal's lock: the .journal.lock directory beside
   the journal file (mkdir is atomic test-and-set), the holder's pid
   and a per-hold token written to an owner file inside it. On
   contention, spin with a short Atomics.wait — WITHOUT a timeout: a
   live holder is waited out, as on every other seat. A lock whose
   holder is dead is broken by atomic rename (break-lock!) and the
   directory re-contended, so a member that dies mid-flush or
   mid-recovery cannot permanently block the domain. The lock is only
   ever held for one flush's (or one recovery's) duration. A mkdir
   failure other than EEXIST is an IO error, not contention, and
   throws at once."
  [{:keys [path]} f]
  (let [fs (js/require "fs")
        dir (str path ".lock")
        owner-path (str dir "/owner")
        token (.toString (js/Math.random) 36)
        sab (js/SharedArrayBuffer. 4)
        view (js/Int32Array. sab)]
    (loop []
      (let [mkdir-err (try (.mkdirSync fs dir) nil (catch :default e e))]
        (cond
          (nil? mkdir-err)
          (do (try (.writeFileSync fs owner-path
                      (str (.-pid js/process) " " token))
                   (catch :default e
                     (try (.rmdirSync fs dir) (catch :default _ nil))
                     (throw e)))
              (try (f)
                   (finally
                     ;; Release only a directory that is still ours:
                     ;; if it was broken as stale and re-taken (or
                     ;; restored ownerless), the one at this path is
                     ;; another hold's, or no one's to release here.
                     (let [owner (read-owner fs owner-path)]
                       (when (= (:token owner) token)
                         (try (.unlinkSync fs owner-path)
                              (catch :default _ nil))
                         (try (.rmdirSync fs dir)
                              (catch :default _ nil)))))))
          (= (.-code mkdir-err) "EEXIST")
          (do (when (lock-stale? fs dir owner-path)
                (break-lock! fs dir owner-path))
              (js/Atomics.wait view 0 0 lock-poll-ms)
              (recur))
          :else (throw mkdir-err))))))

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
