(ns eve.jlog
  "The commit log of a durable disk domain: <base>.jlog, beside <base>.root.

   One implementation for every host. The log is a fixed-size file mapped
   MAP_SHARED through region/open-mmap-region — the same region, the same
   native binding, every host already opens for the root — and coordinated
   only by CAS on mapped words, as the root's atom table is. No OS file lock.
   Its only host needs are eve.mem's: i32 load/store/CAS, bulk bytes, and
   ISyncableRegion's -sync-range! (a durable log refuses a region that cannot
   force).

   Layout (every word a u32 in the region's own word order, as the root's):
     page 0   header: magic EVL1 (written last at creation), version, slot
              bytes, N slots per stripe, S stripes, the file's nonce, the data
              offset, the recovered epoch; stripe k's 64-byte line at 64+64k
              holds tail_k (the next seq to reserve: the CAS target) and hint_k
              (the last completed seq, never authoritative).
     page 1.. sstate[k][i] (the seq slot i of stripe k is ready to accept) and
              sowner[k][i] (who holds it: the seq's low 16 bits and 1 + the
              worker slot).
     data     S x N slots of 4,096 bytes, one record each:
              [EVR2][seq][stripe][epoch label][kind][nonce][writer][table-len]
              [2,048 table bytes][crc32 over everything before it]
              little-endian.

   A writer reserves seq t of stripe k by CAS tail_k t -> t+1 once slot
   t mod N is ready for t (a bounded MPMC ring: a slot is never handed to the
   next lap while its writer is still writing or forcing it — a slow writer
   back-pressures, it is never overwritten). It then reads the epoch label and
   the atom table (the caller's snapshot), writes the record, forces the
   slot's page (the commit point), and marks the slot ready for seq t+N.

   Recovery scans every slot and keeps the valid records (magic, nonce,
   stripe, seq mod N, table length and CRC all agree); the record it installs
   is the one with the highest epoch label (serial arithmetic), never the last
   in file order, and no record is ever erased."
  (:refer-clojure :exclude [aget aset alength byte-array unchecked-byte unsigned-bit-shift-right])
  (:require
   [eve.host :refer [aget aset alength byte-array unchecked-byte unsigned-bit-shift-right]]
   [eve.encoding :as enc]
   [eve.int32 :as i32]
   [eve.crc32 :as crc]
   [eve.mem :as mem]
   [eve.process :as proc]
   [eve.region :as region]
   [eve.deftype-proto.data :as d]))

;; ---------------------------------------------------------------------------
;; Layout
;; ---------------------------------------------------------------------------

(def ^:const LOG_MAGIC 0x45564C31)        ;; "EVL1"
(def ^:const LOG_VERSION 1)
(def ^:const SLOT_BYTES 4096)
(def ^:const REC_MAGIC 0x45565232)        ;; "EVR2"
(def ^:const KIND_COMMIT 1)
(def ^:const KIND_ANCHOR 2)
(def ^:const TABLE_BYTES (* d/MAX_ATOM_SLOTS d/ATOM_SLOT_SIZE)) ;; 2048
(def ^:const REC_TABLE_OFF 32)
(def ^:const REC_CRC_OFF (+ REC_TABLE_OFF TABLE_BYTES))         ;; 2080
(def ^:const REC_BYTES (+ REC_CRC_OFF 4))                      ;; 2084

(def ^:const H_MAGIC 0)
(def ^:const H_VERSION 4)
(def ^:const H_SLOT_BYTES 8)
(def ^:const H_N 12)
(def ^:const H_S 16)
(def ^:const H_NONCE 20)
(def ^:const H_DATA_OFF 24)
(def ^:const H_RECOVERED_EPOCH 28)
(def ^:const H_GROUP_DURABLE 32)
(def ^:const STRIPE_BASE 64)
(def ^:const STRIPE_STRIDE 64)
(def ^:const MAX_STRIPES 63)
(def ^:const STATE_BASE 4096)

(def ^:const DEFAULT_SLOTS 1024)
(def ^:const DEFAULT_STRIPES 1)

(defn- pow2? [n] (and (pos? n) (zero? (bit-and n (dec n)))))

(defn- round-up [n m] (* m (quot (+ n (dec m)) m)))

(defn data-offset
  "Byte offset of stripe 0 slot 0 for N slots per stripe and S stripes."
  [n s]
  (round-up (+ STATE_BASE (* 8 s n)) 4096))

(defn file-bytes
  "The .jlog file's size for N slots per stripe and S stripes."
  [n s]
  (+ (data-offset n s) (* s n SLOT_BYTES)))

(defn- tail-off [k] (+ STRIPE_BASE (* STRIPE_STRIDE k)))
(defn- hint-off [k] (+ STRIPE_BASE (* STRIPE_STRIDE k) 4))
(defn- sstate-off [{:keys [n]} k i] (+ STATE_BASE (* 4 (+ (* k n) i))))
(defn- sowner-off [{:keys [n s]} k i] (+ STATE_BASE (* 4 s n) (* 4 (+ (* k n) i))))
(defn- slot-off [{:keys [n data-off]} k i] (+ data-off (* SLOT_BYTES (+ (* k n) i))))

(defn- serial-
  "a - b as a signed 32-bit difference (u32 sequence and epoch arithmetic)."
  [a b]
  (i32/i32 (- a b)))

;; ---------------------------------------------------------------------------
;; Little-endian u32 in a byte array
;; ---------------------------------------------------------------------------

(defn- put-u32! [^bytes b off v]
  (aset b off (unchecked-byte (bit-and v 0xFF)))
  (aset b (+ off 1) (unchecked-byte (bit-and (unsigned-bit-shift-right v 8) 0xFF)))
  (aset b (+ off 2) (unchecked-byte (bit-and (unsigned-bit-shift-right v 16) 0xFF)))
  (aset b (+ off 3) (unchecked-byte (bit-and (unsigned-bit-shift-right v 24) 0xFF)))
  nil)

(defn- get-i32
  "The little-endian u32 at OFF in B, as a signed i32."
  [^bytes b off]
  (i32/i32 (bit-or (bit-and (aget b off) 0xFF)
                   (bit-shift-left (bit-and (aget b (+ off 1)) 0xFF) 8)
                   (bit-shift-left (bit-and (aget b (+ off 2)) 0xFF) 16)
                   (bit-shift-left (bit-and (aget b (+ off 3)) 0xFF) 24))))

;; ---------------------------------------------------------------------------
;; Records
;; ---------------------------------------------------------------------------

(defn encode-record
  "A record's REC_BYTES bytes: seq, stripe, epoch label, kind, nonce, writer
   (1 + worker slot; 0 for the creator or recovery) and the TABLE_BYTES atom
   table, then the CRC-32 of everything before it. Every field is written:
   the buffer is fresh and nothing is left to a previous use."
  [seq stripe label kind nonce writer ^bytes table]
  (when-not (== (alength table) TABLE_BYTES)
    (throw (ex-info "eve.jlog: a record's table is the root's atom table" {:len (alength table)})))
  (let [b (byte-array REC_BYTES)]
    (put-u32! b 0 REC_MAGIC)
    (put-u32! b 4 seq)
    (put-u32! b 8 stripe)
    (put-u32! b 12 label)
    (put-u32! b 16 kind)
    (put-u32! b 20 nonce)
    (put-u32! b 24 writer)
    (put-u32! b 28 TABLE_BYTES)
    (enc/arraycopy table 0 b REC_TABLE_OFF TABLE_BYTES)
    (put-u32! b REC_CRC_OFF (crc/crc32 b 0 REC_CRC_OFF))
    b))

(defn decode-record
  "The record in B (REC_BYTES bytes read from stripe K's slot I of a log with
   N slots per stripe and NONCE) as {:seq :stripe :label :kind :writer :table
   :crc}, or nil when it is not a valid record of that slot: magic, nonce,
   stripe, seq mod N, table length and CRC must all agree."
  [^bytes b k i n nonce]
  (let [seq (get-i32 b 4)]
    (when (and (== (get-i32 b 0) (i32/i32 REC_MAGIC))
               (== (get-i32 b 20) (i32/i32 nonce))
               (== (get-i32 b 8) k)
               (== (bit-and seq (dec n)) i)
               (== (get-i32 b 28) TABLE_BYTES)
               (== (get-i32 b REC_CRC_OFF) (crc/crc32 b 0 REC_CRC_OFF)))
      (let [table (byte-array TABLE_BYTES)]
        (enc/arraycopy b REC_TABLE_OFF table 0 TABLE_BYTES)
        {:seq seq :stripe k :slot i :label (get-i32 b 12) :kind (get-i32 b 16)
         :writer (get-i32 b 24) :crc (get-i32 b REC_CRC_OFF) :table table}))))

;; ---------------------------------------------------------------------------
;; Open / create
;; ---------------------------------------------------------------------------

(defn- check-syncable! [r path]
  (when-not (satisfies? mem/ISyncableRegion r)
    (throw (ex-info (str "eve.jlog: this host's region cannot force its pages to storage, so it cannot make durable"
                         " commits; open the domain with :durable? false")
                    {:path path}))))

(defn- random-u32 []
  (let [[a b c e] (proc/random-bytes 4)]
    (i32/i32 (bit-or a (bit-shift-left b 8) (bit-shift-left c 16) (bit-shift-left e 24)))))

(defn- handle [r path n s nonce data-off]
  {:r r :path path :n n :s s :mask (dec n) :nonce nonce :data-off data-off})

(defn create!
  "Create <path> as a fresh log: N slots per stripe (a power of two), S stripes
   (a power of two, at most 63). The file is grown to its full size and every
   byte written once through the mapping (the preallocation: no page of the
   log is a hole, so a commit's force never allocates), the header and slot
   states are laid down, and the magic is written last, after a force: a
   joiner that sees the magic sees a complete log. Answers the open log."
  ([path] (create! path nil))
  ([path {:keys [slots stripes nonce] :or {slots DEFAULT_SLOTS stripes DEFAULT_STRIPES}}]
   (when-not (and (pow2? slots) (pow2? stripes) (<= stripes MAX_STRIPES))
     (throw (ex-info "eve.jlog: slots and stripes must be powers of two (stripes <= 63)"
                     {:slots slots :stripes stripes})))
   (let [n slots
         s stripes
         size (file-bytes n s)
         data-off (data-offset n s)
         nonce (or nonce (random-u32))
         r (region/open-mmap-region path size)
         _ (check-syncable! r path)
         chunk (byte-array 65536)]
     ;; preallocate: every page written once (a sparse page would allocate at the force)
     (loop [off 0]
       (when (< off size)
         (let [len (min 65536 (- size off))]
           (mem/-write-bytes! r off (if (== len 65536) chunk (byte-array len)))
           (recur (+ off len)))))
     (mem/-store-i32! r H_VERSION LOG_VERSION)
     (mem/-store-i32! r H_SLOT_BYTES SLOT_BYTES)
     (mem/-store-i32! r H_N n)
     (mem/-store-i32! r H_S s)
     (mem/-store-i32! r H_NONCE nonce)
     (mem/-store-i32! r H_DATA_OFF data-off)
     (let [log (handle r path n s nonce data-off)]
       (dotimes [k s]
         (dotimes [i n]
           (mem/-store-i32! r (sstate-off log k i) i)))
       (mem/sync-region! r 0 size)
       (mem/-store-i32! r H_MAGIC (i32/i32 LOG_MAGIC))
       (mem/sync-region! r 0 4096)
       log))))

(defn- await-magic
  "The header's magic once it is there: a joiner waits for the creator, which
   writes it last. A creator that never finishes is reported after TIMEOUT-MS."
  [r path timeout-ms]
  (let [t0 (proc/now-ms)]
    (loop [i 0]
      (let [m (mem/-load-i32 r H_MAGIC)]
        (cond
          (== m (i32/i32 LOG_MAGIC)) m
          (not (zero? m))
          (throw (ex-info "eve.jlog: not a jlog file (wrong magic)" {:path path :magic m}))
          (> (- (proc/now-ms) t0) timeout-ms)
          (throw (ex-info "eve.jlog: the log's creator never finished writing it" {:path path}))
          :else (do (when (> i 100) (proc/sleep-ms! 1)) (recur (inc i))))))))

(defn magic
  "The magic word of the log at PATH as it stands (0 for a log not yet
   created, or a file shorter than its header), without waiting."
  [path]
  (mem/-load-i32 (region/open-mmap-region path 4096) H_MAGIC))

(defn open
  "Open the existing log at PATH (waiting up to TIMEOUT-MS for its creator to
   finish). Throws on a wrong magic or version: a log is never overwritten."
  ([path] (open path d/HEARTBEAT_TIMEOUT_MS))
  ([path timeout-ms]
   (let [hr (region/open-mmap-region path 4096)
         _ (await-magic hr path timeout-ms)
         version (mem/-load-i32 hr H_VERSION)
         slot-bytes (mem/-load-i32 hr H_SLOT_BYTES)
         n (mem/-load-i32 hr H_N)
         s (mem/-load-i32 hr H_S)
         data-off (mem/-load-i32 hr H_DATA_OFF)]
     (when-not (and (== version LOG_VERSION) (== slot-bytes SLOT_BYTES) (pow2? n) (pow2? s)
                    (== data-off (data-offset n s)))
       (throw (ex-info "eve.jlog: unsupported log version or layout"
                       {:path path :version version :slot-bytes slot-bytes :n n :s s})))
     (let [r (region/open-mmap-region path (file-bytes n s))]
       (check-syncable! r path)
       (handle r path n s (mem/-load-i32 r H_NONCE) data-off)))))

;; ---------------------------------------------------------------------------
;; Reserve and append (plan §4.3 steps 5-10)
;; ---------------------------------------------------------------------------

(defn- owner-word
  "sowner's value for SEQ held by worker ME: the seq's low 16 bits and 1 + the
   worker slot, so a waiter can tell the current holder's word from an older
   lap's."
  [seq me]
  (i32/i32 (bit-or (bit-shift-left (bit-and seq 0xFFFF) 16) (bit-and (inc me) 0xFFFF))))

(defn- holder-dead?
  "Whether the holder of seq ST in stripe K's slot I is dead: its owner word
   names ST and a worker STALE? judges dead, or (its owner word not yet naming
   ST: the holder died between its tail CAS and its owner store) the slot has
   not moved for longer than STALE-MS since this waiter first saw it held."
  [log k i st stale? stale-ms first-seen]
  (let [w (mem/-load-i32 (:r log) (sowner-off log k i))]
    (if (== (bit-and (unsigned-bit-shift-right w 16) 0xFFFF) (bit-and st 0xFFFF))
      (let [owner (dec (bit-and w 0xFFFF))]
        (and (>= owner 0) (stale? owner)))
      (> (- (proc/now-ms) first-seen) stale-ms))))

(defn reserve!
  "Reserve the next seq of stripe K for worker ME. Waits while the slot is
   still held by its previous lap's writer (back-pressure), reclaiming it only
   when that writer is dead by STALE? (or, for a holder that never named
   itself, after STALE-MS). Answers {:seq :slot :waits}, waits counting the
   reclaim checks made."
  [log k me stale? stale-ms]
  (let [r (:r log)
        to (tail-off k)
        mask (:mask log)]
    (loop [waits 0
           first-seen nil]
      (let [t (mem/-load-i32 r to)
            i (bit-and t mask)
            so (sstate-off log k i)
            st (mem/-load-i32 r so)
            dlt (serial- t st)]
        (cond
          (zero? dlt)
          (if (== t (mem/-cas-i32! r to t (i32/i32 (inc t))))
            (do (mem/-store-i32! r (sowner-off log k i) (owner-word t me))
                {:seq t :slot i :waits waits})
            (recur waits nil))

          (pos? dlt)
          ;; the slot still holds seq st, a lap behind: its writer is writing or forcing it
          (let [fs (or first-seen (proc/now-ms))]
            (if (holder-dead? log k i st stale? stale-ms fs)
              (do (mem/-cas-i32! r so st t) (recur (inc waits) nil))
              (do (when (> waits 64) (proc/sleep-ms! 1))
                  (recur (inc waits) fs))))

          :else                         ;; the tail read was stale
          (recur waits nil))))))

(defn append!
  "Append one record to stripe K for worker ME (plan §4.3 steps 5-10):
   reserve, call (SNAPSHOT!) for [epoch-label table-bytes] (read after the
   reservation), write the record, force the slot's page — the commit point —
   then mark the slot ready for its next lap. opts: :kind (KIND_COMMIT),
   :stale? (fn [worker] dead?), :stale-ms, :before-force (a test hook run
   between the write and the force). Answers {:seq :stripe :slot :label
   :waits}. The slot is released even when the write or the force throws, so
   a failed append never wedges the ring; the throw reaches the caller."
  [log k me snapshot! {:keys [kind stale? stale-ms before-force]
                       :or {kind KIND_COMMIT stale-ms d/HEARTBEAT_TIMEOUT_MS}}]
  (let [r (:r log)
        stale? (or stale? (constantly false))
        {:keys [seq slot waits]} (reserve! log k me stale? stale-ms)
        off (slot-off log k slot)]
    (try
      (let [[label table] (snapshot!)
            rec (encode-record seq k label kind (:nonce log) (inc me) table)]
        (mem/-write-bytes! r off rec)
        (when before-force (before-force))
        (mem/sync-region! r off SLOT_BYTES)
        {:seq seq :stripe k :slot slot :label label :waits waits})
      (finally
        (mem/-store-i32! r (sstate-off log k slot) (i32/i32 (+ seq (:n log))))
        (mem/-store-i32! r (hint-off k) seq)))))

;; ---------------------------------------------------------------------------
;; Scan, pick, rebuild (plan §4.4)
;; ---------------------------------------------------------------------------

(defn read-slot
  "The valid record in stripe K's slot I, or nil."
  [log k i]
  (decode-record (mem/-read-bytes (:r log) (slot-off log k i) REC_BYTES) k i (:n log) (:nonce log)))

(defn scan
  "Every valid record in the log, in stripe then slot order."
  [log]
  (vec (for [k (range (:s log))
             i (range (:n log))
             :let [rec (read-slot log k i)]
             :when rec]
         rec)))

(defn pick
  "R*: among RECS (valid records), the one with the highest epoch label by
   serial arithmetic relative to the first record's label; equal labels go to
   the lower stripe, then the higher seq. nil when RECS is empty."
  [recs]
  (when (seq recs)
    (let [ref (:label (first recs))]
      (reduce (fn [best r]
                (let [dl (serial- (serial- (:label r) ref) (serial- (:label best) ref))]
                  (cond
                    (pos? dl) r
                    (neg? dl) best
                    (< (:stripe r) (:stripe best)) r
                    (> (:stripe r) (:stripe best)) best
                    (pos? (serial- (:seq r) (:seq best))) r
                    :else best)))
              (first recs)
              (rest recs)))))

(defn max-seq
  "The highest seq (serial arithmetic) among RECS of one stripe, or nil."
  [recs]
  (when (seq recs)
    (reduce (fn [m r] (if (pos? (serial- (:seq r) m)) (:seq r) m))
            (:seq (first recs))
            (rest recs))))

(defn rebuild-header!
  "Lay the reservation state down again from RECS (the valid records): each
   stripe's tail is one past its highest valid seq (0 for a stripe with none),
   every slot ready for the next seq of its residue at or after that tail, no
   owners; then force the header and state pages. Run only with no live
   writer (recovery, under the domain's recovery hold)."
  [log recs recovered-epoch]
  (let [r (:r log)
        n (:n log)
        mask (:mask log)
        by-stripe (group-by :stripe recs)]
    (dotimes [k (:s log)]
      (let [t (or (some-> (max-seq (get by-stripe k)) inc i32/i32) 0)]
        (dotimes [i n]
          (mem/-store-i32! r (sstate-off log k i) (i32/i32 (+ t (bit-and (- i t) mask))))
          (mem/-store-i32! r (sowner-off log k i) 0))
        (mem/-store-i32! r (hint-off k) (i32/i32 (dec t)))
        (mem/-store-i32! r (tail-off k) t)))
    (mem/-store-i32! r H_RECOVERED_EPOCH (i32/i32 recovered-epoch))
    (mem/sync-region! r 0 (:data-off log))
    nil))

(defn tail
  "Stripe K's tail word as it stands (the next seq to reserve)."
  [log k]
  (mem/-load-i32 (:r log) (tail-off k)))
