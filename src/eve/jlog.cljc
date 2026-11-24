(ns eve.jlog
  "The commit log of a durable disk domain: <base>.jlog, beside <base>.root — a redo log
   (doc/jlog-redo-spec.md).

   One implementation for every host. The log is a fixed-size file mapped
   MAP_SHARED through region/open-mmap-region — the same region, the same
   native binding, every host already opens for the root — and coordinated
   only by CAS on mapped words, as the root's atom table is. No OS file lock.
   Its only host needs are eve.mem's: i32 load/store/CAS/wait/notify, bulk
   bytes, and ISyncableRegion's -sync-range! (a durable log refuses a region
   that cannot force).

   Layout (every word a u32 in the region's own word order, as the root's):
     page 0   header: magic EVL1 (written last at creation), version 2, slot
              bytes, N slots, 1 (the stripe count of the layout), the file's
              nonce, the data offset, the prefix watermark `durable` (32) and
              the newest durable anchor's seq (36); at 64, tail (the next seq
              to reserve: the CAS target).
     page 1.. sstate[i] (the seq slot i is ready to accept) and sowner[i]
              (who holds it: the seq's low 16 bits, the owner's state bits
              and 1 + the worker slot).
     data     N slots of 4,096 bytes, one record (or one part of a run) each:
              [EVR3][seq][kind][label][nonce][writer][len][part] (32 bytes),
              len body bytes, the CRC-32 of everything before it.

   A writer reserves seq t (or a run of seqs) by CAS tail t -> t+n once each
   slot is ready for its seq (a bounded MPMC ring: a slot is never handed to
   the next lap while its writer is still writing it, nor past the newest
   anchor: a slow writer back-pressures, it is never overwritten). It writes
   its record and releases the slot; the watermark's force-through! forces
   the run of valid records after `durable` in one msync and advances it — a
   commit is acknowledged once `durable` passes it. Recovery reads the newest
   ANCHOR and the consecutive valid records after it (the prefix); nothing in
   the prefix is ever erased."
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
(def ^:const LOG_VERSION 2)                ;; the redo log (version 1, the snapshot log of a development build, is gone)
(def ^:const SLOT_BYTES 4096)
(def ^:const TABLE_BYTES (* d/MAX_ATOM_SLOTS d/ATOM_SLOT_SIZE)) ;; 2048

(def ^:const H_MAGIC 0)
(def ^:const H_VERSION 4)
(def ^:const H_SLOT_BYTES 8)
(def ^:const H_N 12)
(def ^:const H_S 16)
(def ^:const H_NONCE 20)
(def ^:const H_DATA_OFF 24)
(def ^:const H_DURABLE 32)                ;; the first seq not yet known final and forced (the prefix watermark)
(def ^:const H_ANCHOR 36)                 ;; the seq of the newest durable ANCHOR record (-1: none yet)
(def ^:const STRIPE_BASE 64)
(def ^:const STRIPE_STRIDE 64)
(def ^:const STATE_BASE 4096)

(def ^:const DEFAULT_SLOTS 1024)

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
  "Create <path> as a fresh log of N slots (:slots, a power of two). The file is grown to its full size and every
   byte written once through the mapping (the preallocation: no page of the log is a hole, so a commit's force never
   allocates), the header and slot states are laid down, and the magic is written last, after a force: a joiner that
   sees the magic sees a complete log. :region is the map region/open-mmap-region takes (the domain's own: {:lustre?
   true} on a Lustre mount, whose atomics are byte-range locks across nodes, as the root's are). Answers the open
   log; its anchor is laid by the caller (eve.atom)."
  ([path] (create! path nil))
  ([path {:keys [slots nonce region] :or {slots DEFAULT_SLOTS}}]
   (when-not (pow2? slots)
     (throw (ex-info "eve.jlog: the slot count must be a power of two" {:slots slots})))
   (let [n slots
         s 1
         size (file-bytes n s)
         data-off (data-offset n s)
         nonce (or nonce (random-u32))
         r (region/open-mmap-region path size region)
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
     (mem/-store-i32! r H_DURABLE 0)
     (mem/-store-i32! r H_ANCHOR -1)
     (let [log (handle r path n s nonce data-off)]
       (dotimes [i n]
         (mem/-store-i32! r (sstate-off log 0 i) i))
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
   finish), mapped with REGION-OPTS (create!'s :region). Throws on a wrong
   magic, version or layout: a log is never overwritten (a version-1 log, the snapshot log of a development build of
   this log, is refused too)."
  ([path] (open path d/HEARTBEAT_TIMEOUT_MS nil))
  ([path timeout-ms] (open path timeout-ms nil))
  ([path timeout-ms region-opts]
   (let [hr (region/open-mmap-region path 4096 region-opts)
         _ (await-magic hr path timeout-ms)
         version (mem/-load-i32 hr H_VERSION)
         slot-bytes (mem/-load-i32 hr H_SLOT_BYTES)
         n (mem/-load-i32 hr H_N)
         s (mem/-load-i32 hr H_S)
         data-off (mem/-load-i32 hr H_DATA_OFF)]
     (when-not (and (== version LOG_VERSION) (== slot-bytes SLOT_BYTES) (pow2? n) (== s 1)
                    (== data-off (data-offset n s)))
       (throw (ex-info "eve.jlog: unsupported log version or layout"
                       {:path path :version version :slot-bytes slot-bytes :n n :s s})))
     (let [r (region/open-mmap-region path (file-bytes n s) region-opts)]
       (check-syncable! r path)
       (handle r path n s (mem/-load-i32 r H_NONCE) data-off)))))

;; ---------------------------------------------------------------------------
;; Reserve
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
      (let [owner (dec (bit-and w 0x3FFF))]
        (and (>= owner 0) (stale? owner)))
      (> (- (proc/now-ms) first-seen) stale-ms))))

(defn reserve!
  "Reserve the next seq of stripe K for worker ME. Waits while the slot is
   still held by its previous lap's writer (back-pressure), reclaiming it only
   when that writer is dead by STALE? (or, for a holder that never named
   itself, after STALE-MS). Answers {:seq :slot :waits}, waits counting the
   reclaim checks made. With GATE (fn [t] ok?), a seq the gate refuses is not
   reserved: :full is answered instead (the ring would lap its newest
   anchor)."
  ([log k me stale? stale-ms] (reserve! log k me stale? stale-ms nil))
  ([log k me stale? stale-ms gate]
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
           (and gate (not (gate t)))
           :full

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
           (recur waits nil)))))))

(defn reserve-run!
  "Reserve CNT consecutive seqs for worker ME — a commit whose redo takes CNT slots: once
   every slot of the run is ready for its seq (a slot its previous lap still holds is waited on, and reclaimed from a
   dead holder, as reserve! does), one CAS moves the tail past the run. GATE (fn [seq] ok?) is asked of the run's
   last seq; a refusal answers :full. Answers {:seq (the run's first) :n :waits}."
  [log me cnt stale? stale-ms gate]
  (if (<= cnt 1)
    (let [res (reserve! log 0 me stale? stale-ms gate)]
      (if (= :full res) res (assoc res :n 1)))
    (let [r (:r log)
          to (tail-off 0)
          mask (:mask log)]
      (loop [waits 0
             first-seen nil]
        (let [t (mem/-load-i32 r to)]
          (if (and gate (not (gate (i32/i32 (+ t (dec cnt))))))
            :full
            (let [blocked (loop [i 0]
                            (when (< i cnt)
                              (let [s (i32/i32 (+ t i))
                                    so (sstate-off log 0 (bit-and s mask))
                                    st (mem/-load-i32 r so)]
                                (if (== st s) (recur (inc i)) [s st so]))))]
              (cond
                (nil? blocked)
                (if (== t (mem/-cas-i32! r to t (i32/i32 (+ t cnt))))
                  (do (dotimes [i cnt]
                        (let [s (i32/i32 (+ t i))]
                          (mem/-store-i32! r (sowner-off log 0 (bit-and s mask)) (owner-word s me))))
                      {:seq t :n cnt :waits waits})
                  (recur waits nil))

                (pos? (serial- (nth blocked 0) (nth blocked 1)))
                ;; a slot of the run still holds its previous lap's seq: its writer is writing or forcing it
                (let [[s st so] blocked
                      fs (or first-seen (proc/now-ms))]
                  (if (holder-dead? log 0 (bit-and s mask) st stale? stale-ms fs)
                    (do (mem/-cas-i32! r so st s) (recur (inc waits) nil))
                    (do (when (> waits 64) (proc/sleep-ms! 1))
                        (recur (inc waits) fs))))

                :else                   ;; the tail read was stale
                (recur waits nil)))))))))

(defn max-seq
  "The highest seq (serial arithmetic) among RECS, or nil."
  [recs]
  (when (seq recs)
    (reduce (fn [m r] (if (pos? (serial- (:seq r) m)) (:seq r) m))
            (:seq (first recs))
            (rest recs))))

;; ---------------------------------------------------------------------------
;; Records and the prefix watermark (doc/jlog-redo-spec.md)
;;
;; A record is [EVR3][seq][kind][label][nonce][writer][len][part]
;; then len body bytes, then the CRC-32 of everything before it. A writer
;; reserves its seq BEFORE its root CAS (log order then extends
;; happens-before: a commit that read another's published root reserves
;; after it), wins its slot's body (claim-seal!: CAS on the slot's owner
;; word, which a resolver of a dead writer's slot races), writes it and
;; releases the slot. `durable` (H_DURABLE) is the first seq not yet known
;; final and forced: force-through! forces the run of valid records after
;; it in one msync and CAS-advances it; a commit is acknowledged once
;; `durable` passes its seq. Recovery reads the newest ANCHOR and the
;; consecutive valid records after it — the prefix.
;; ---------------------------------------------------------------------------

(def ^:const REC_MAGIC 0x45565233)       ;; "EVR3"
(def ^:const REC_HEADER 32)
(def ^:const REC_MAX_BODY (- SLOT_BYTES REC_HEADER 4)) ;; 4060
(def ^:const K_COMMIT 1)                  ;; delta + redo
(def ^:const K_NOREDO 2)                  ;; delta only: its blocks were forced before it was written
(def ^:const K_ABORT 3)                   ;; a reserved slot whose CAS lost
(def ^:const K_VOID 4)                    ;; a dead writer's slot, after a whole-domain force
(def ^:const K_ANCHOR 5)                  ;; a whole atom table: a checkpoint
(def ^:const OWNER_SEALING 0x8000)        ;; sowner: the reserver is writing its record
(def ^:const OWNER_RESOLVED 0x4000)       ;; sowner: a resolver voided the slot

(defn part-word
  "The header word of part IDX of a run of CNT slots (0: a record of one slot)."
  [idx cnt]
  (if (<= cnt 1) 0 (bit-or (bit-shift-left idx 16) cnt)))

(defn encode-record
  "A record's bytes: header, BODY (a byte array, or nil for none), CRC-32. PART (part-word) names a part of a run
   of slots that carries one commit."
  ([seq kind label nonce writer body] (encode-record seq kind label nonce writer body 0))
  ([seq kind label nonce writer ^bytes body part]
   (let [len (if body (alength body) 0)
         _ (when (> len REC_MAX_BODY)
             (throw (ex-info "eve.jlog: a record body larger than its slot" {:len len})))
         b (byte-array (+ REC_HEADER len 4))]
     (put-u32! b 0 REC_MAGIC)
     (put-u32! b 4 seq)
     (put-u32! b 8 kind)
     (put-u32! b 12 label)
     (put-u32! b 16 nonce)
     (put-u32! b 20 writer)
     (put-u32! b 24 len)
     (put-u32! b 28 part)
     (when (pos? len) (enc/arraycopy body 0 b REC_HEADER len))
     (put-u32! b (+ REC_HEADER len) (crc/crc32 b 0 (+ REC_HEADER len)))
     b)))

(defn- decode-at
  "The valid record in slot I, or nil; with SEQ, only a record of that seq."
  [log i seq]
  (let [r (:r log)
        off (slot-off log 0 i)
        h (mem/-read-bytes r off REC_HEADER)
        s (get-i32 h 4)]
    (when (and (== (get-i32 h 0) (i32/i32 REC_MAGIC))
               (== (get-i32 h 16) (i32/i32 (:nonce log)))
               (== (bit-and s (:mask log)) i)
               (or (nil? seq) (== s (i32/i32 seq))))
      (let [len (get-i32 h 24)]
        (when (<= 0 len REC_MAX_BODY)
          (let [b (mem/-read-bytes r off (+ REC_HEADER len 4))]
            (when (== (get-i32 b (+ REC_HEADER len)) (crc/crc32 b 0 (+ REC_HEADER len)))
              (let [body (byte-array len)]
                (when (pos? len) (enc/arraycopy b REC_HEADER body 0 len))
                {:seq s :kind (get-i32 b 8) :label (get-i32 b 12) :writer (get-i32 b 20) :part (get-i32 b 28)
                 :body body}))))))))

(defn read-record
  "The valid record of SEQ (in its slot), or nil: unwritten, torn, another lap's, another file's."
  [log seq]
  (decode-at log (bit-and seq (:mask log)) seq))

(defn read-slot
  "The valid record in slot I, whatever its seq, or nil."
  [log i]
  (decode-at log i nil))

(defn void-from!
  "Void every valid record whose seq is at or past NEXT (serial): its magic zeroed and its slot forced. Recovery
   runs this before writing at NEXT: such records were never acknowledged, and left valid they would read as the
   continuation of a later prefix."
  [log next]
  (dotimes [i (:n log)]
    (when-let [rec (decode-at log i nil)]
      (when-not (neg? (serial- (:seq rec) next))
        (let [off (slot-off log 0 i)]
          (mem/-write-bytes! (:r log) off (byte-array 4))
          (mem/sync-region! (:r log) off SLOT_BYTES))))))

(defn durable
  "The prefix watermark: the first seq not yet known final and forced."
  [log]
  (mem/-load-i32 (:r log) H_DURABLE))

(defn anchor-seq
  "The seq of the newest durable ANCHOR (-1: none yet)."
  [log]
  (mem/-load-i32 (:r log) H_ANCHOR))

(defn advance-anchor!
  "Move H_ANCHOR to SEQ if that is later (serial). Only once SEQ's ANCHOR is durable."
  [log seq]
  (let [r (:r log)]
    (loop []
      (let [a (mem/-load-i32 r H_ANCHOR)]
        (when (or (== a -1) (pos? (serial- seq a)))
          (when-not (== a (mem/-cas-i32! r H_ANCHOR a (i32/i32 seq)))
            (recur)))))))

(defn claim-seal!
  "Win the body of SEQ's slot for its reserver ME before writing its record: false when a resolver voided the slot
   (the reserver was judged dead)."
  [log seq me]
  (let [o (sowner-off log 0 (bit-and seq (:mask log)))
        w (owner-word seq me)]
    (== w (mem/-cas-i32! (:r log) o w (i32/i32 (bit-or w OWNER_SEALING))))))

(defn write-slot!
  "Write the record bytes REC into SEQ's slot (no force)."
  [log seq rec]
  (mem/-write-bytes! (:r log) (slot-off log 0 (bit-and seq (:mask log))) rec)
  nil)

(defn release!
  "Mark SEQ's slot ready for its next lap (the anchor gate keeps that lap from coming while the record is needed)."
  [log seq]
  (mem/-store-i32! (:r log) (sstate-off log 0 (bit-and seq (:mask log))) (i32/i32 (+ seq (:n log))))
  nil)

(defn- sync-seqs!
  "Force the slots of seqs [from, to): contiguous pages, one msync per run up to the ring's end."
  [log from to]
  (let [r (:r log)
        n (:n log)
        mask (:mask log)]
    (loop [a from]
      (let [left (serial- to a)]
        (when (pos? left)
          (let [i (bit-and a mask)
                run (min left (- n i))]
            (mem/sync-region! r (slot-off log 0 i) (* run SLOT_BYTES))
            (recur (i32/i32 (+ a run)))))))))

(defn force-through!
  "Advance the prefix watermark: the run of seqs from `durable` whose slots hold valid records is forced (one msync
   per ring run) and `durable` CAS-advanced past it, waking its waiters. A record is checked valid BEFORE the msync
   that makes it durable. Answers the watermark after."
  [log]
  (let [r (:r log)]
    (loop []
      (let [d (mem/-load-i32 r H_DURABLE)
            e (loop [e d] (if (read-record log e) (recur (i32/i32 (inc e))) e))]
        (if (== e d)
          d
          (do (sync-seqs! log d e)
              (when (== d (mem/-cas-i32! r H_DURABLE d e))
                (mem/-notify-i32! r H_DURABLE 2147483647))
              (recur)))))))

(defn- hole-dead?
  "Whether the reserver of hole H (an unsealed seq at the watermark) is dead: its owner word names H and a worker
   STALE? judges dead, or (never named) the hole has stood for STALE-MS since WAITED-SINCE."
  [log h stale? stale-ms waited-since]
  (let [w (mem/-load-i32 (:r log) (sowner-off log 0 (bit-and h (:mask log))))]
    (if (== (bit-and (unsigned-bit-shift-right w 16) 0xFFFF) (bit-and h 0xFFFF))
      (let [owner (dec (bit-and w 0x3FFF))]
        (and (>= owner 0) (stale? owner)))
      (> (- (proc/now-ms) waited-since) stale-ms))))

(defn- resolve-void!
  "Void hole H whose reserver is dead: win its owner word, run (FORCE!) — a whole-domain force: the dead writer's
   published blocks are in the page cache, and the VOID lets later records' commits, which may reach them, become
   durable — then write VOID and release the slot."
  [log h force!]
  (let [r (:r log)
        o (sowner-off log 0 (bit-and h (:mask log)))
        w (mem/-load-i32 r o)
        resolved (i32/i32 (bit-or (bit-shift-left (bit-and h 0xFFFF) 16) OWNER_RESOLVED))]
    (when (and (not (== w resolved)) (== w (mem/-cas-i32! r o w resolved)))
      (force!)
      (write-slot! log h (encode-record h K_VOID 0 (:nonce log) 0 nil))
      (release! log h))
    nil))

(defn await-durable!
  "Return once the watermark has passed SEQ. Forces what is ready (force-through!); a hole at the watermark whose
   reserver is dead (STALE?, STALE-MS) is voided after (FORCE!); otherwise waits on the watermark word."
  [log seq stale? stale-ms force!]
  (loop [waited-since nil
         spins 0]
    (let [d (force-through! log)]
      (when-not (pos? (serial- d seq))
        (let [ws (or waited-since (proc/now-ms))]
          (if (hole-dead? log d stale? stale-ms ws)
            (do (resolve-void! log d force!) (recur nil 0))
            (do (when (> spins 20) (mem/-wait-i32! (:r log) H_DURABLE d 1))
                (recur ws (inc spins)))))))))

(defn recovery-prefix
  "[anchor & records] — the newest valid ANCHOR in the ring and the records of the consecutive seqs after it (the
   first missing or invalid seq ends it); nil when no ANCHOR survives."
  [log]
  (let [anchors (keep (fn [i] (let [rec (decode-at log i nil)]
                                (when (and rec (== K_ANCHOR (:kind rec))) rec)))
                      (range (:n log)))]
    (when (seq anchors)
      (let [ref (:seq (first anchors))
            a (reduce (fn [best rec] (if (pos? (serial- (serial- (:seq rec) ref) (serial- (:seq best) ref))) rec best))
                      anchors)]
        (cons a (loop [s (i32/i32 (inc (:seq a)))
                       acc []]
                  (if-let [rec (read-record log s)]
                    (recur (i32/i32 (inc s)) (conj acc rec))
                    acc)))))))

(defn rebuild-header!
  "Lay the reservation state down at NEXT (the seq after the recovered prefix): the tail, every slot ready for its
   residue's next seq at or after NEXT, no owners, the watermark at NEXT; force the header and state pages. Run only
   with no live writer (recovery, under the recovery hold)."
  [log next]
  (let [r (:r log)
        mask (:mask log)]
    (dotimes [i (:n log)]
      (mem/-store-i32! r (sstate-off log 0 i) (i32/i32 (+ next (bit-and (- i next) mask))))
      (mem/-store-i32! r (sowner-off log 0 i) 0))
    (mem/-store-i32! r (tail-off 0) (i32/i32 next))
    (mem/-store-i32! r H_DURABLE (i32/i32 next))
    (mem/sync-region! r 0 (:data-off log))
    nil))

;; --- bodies ---

(defn- body-writer
  "A byte array of N bytes and a cursor: [b put-i32! put-bytes!]."
  [n]
  (let [b (byte-array n)
        pos (volatile! 0)]
    [b
     (fn [v] (put-u32! b @pos v) (vswap! pos + 4) nil)
     (fn [^bytes src] (enc/arraycopy src 0 b @pos (alength src)) (vswap! pos + (alength src)) nil)]))

(defn commit-body-size
  "The bytes a COMMIT body takes for BLOCKS ([{:off :bytes}]) and PINS ([{:off}])."
  [blocks pins]
  (+ 16 4 (reduce + 0 (map (fn [blk] (+ 16 (alength ^bytes (:bytes blk)))) blocks)) 4 (* 8 (count pins))))

;; A request id (W8 of the OPFS tier: a follower tab's compare-and-set the owner applied): [client seq], two i32s,
;; in an optional trailer — RID_TAG client seq — after a COMMIT or NOREDO body, and as a table — RID_TAG count, then
;; [client seq] pairs, each client's highest applied seq — after an ANCHOR's atom table. A body without one reads as
;; before.
(def ^:const RID_TAG 0x52494431)            ;; "RID1"
(def ^:const RID_BYTES 12)
(def ^:const ANCHOR_RIDS_MAX
  "The clients an ANCHOR's request-id table holds (the rest of its slot after the atom table)."
  240)

(defn encode-commit-body
  "A COMMIT body: the delta [slot old new hash]; the new blocks, each [off flags pointer len bytes] (W1, W2 by its
   offset, W3); the pinned rows, each [off flags] (W4). With BLOCKS and PINS nil, a NOREDO body (the delta alone).
   With RID ([client seq]), its trailer after."
  ([delta blocks pins] (encode-commit-body delta blocks pins nil))
  ([{:keys [slot old new hash]} blocks pins rid]
   (let [n (+ (if (or blocks pins) (commit-body-size blocks pins) 16) (if rid RID_BYTES 0))
         [b i32! bytes!] (body-writer n)]
     (i32! slot) (i32! old) (i32! new) (i32! hash)
     (when (or blocks pins)
       (i32! (count blocks))
       (doseq [{:keys [off flags pointer bytes]} blocks]
         (i32! off) (i32! flags) (i32! pointer) (i32! (alength ^bytes bytes)) (bytes! bytes))
       (i32! (count pins))
       (doseq [{:keys [off flags]} pins]
         (i32! off) (i32! flags)))
     (when rid
       (i32! RID_TAG) (i32! (nth rid 0)) (i32! (nth rid 1)))
     b)))

(defn- rid-at
  "The request id of a trailer at P in B, or nil."
  [^bytes b p]
  (when (and (== (+ p RID_BYTES) (alength b)) (== (get-i32 b p) (i32/i32 RID_TAG)))
    [(get-i32 b (+ p 4)) (get-i32 b (+ p 8))]))

(defn body-rid
  "A COMMIT or NOREDO body's request id [client seq], or nil: its structure walked, no bytes copied."
  [^bytes b]
  (let [n (alength b)]
    (cond
      (<= n 16) nil
      (== n (+ 16 RID_BYTES)) (rid-at b 16)
      :else (let [nb (get-i32 b 16)
                  p (loop [k 0 p 20] (if (and (< k nb) (<= (+ p 16) n)) (recur (inc k) (+ p 16 (get-i32 b (+ p 12)))) p))]
              (when (<= (+ p 4) n)
                (rid-at b (+ p 4 (* 8 (get-i32 b p)))))))))

(defn decode-commit-body
  "A COMMIT or NOREDO body as {:slot :old :new :hash :blocks [...] :pins [...]}."
  [^bytes b]
  (let [delta {:slot (get-i32 b 0) :old (get-i32 b 4) :new (get-i32 b 8) :hash (get-i32 b 12)}]
    (if (or (<= (alength b) 16) (some? (rid-at b 16)))
      (assoc delta :blocks [] :pins [])
      (let [nb (get-i32 b 16)
            [blocks p] (loop [k 0 p 20 acc []]
                         (if (< k nb)
                           (let [len (get-i32 b (+ p 12))
                                 bytes (byte-array len)]
                             (enc/arraycopy b (+ p 16) bytes 0 len)
                             (recur (inc k) (+ p 16 len)
                                    (conj acc {:off (get-i32 b p) :flags (get-i32 b (+ p 4))
                                               :pointer (get-i32 b (+ p 8)) :bytes bytes})))
                           [acc p]))
            np (get-i32 b p)
            pins (mapv (fn [k] {:off (get-i32 b (+ p 4 (* 8 k))) :flags (get-i32 b (+ p 8 (* 8 k)))}) (range np))]
        (assoc delta :blocks blocks :pins pins)))))

(defn- concat-bodies [bodies]
  (let [n (reduce + 0 (map (fn [^bytes b] (alength b)) bodies))
        out (byte-array n)]
    (loop [bs (seq bodies)
           pos 0]
      (if-let [b (first bs)]
        (do (enc/arraycopy b 0 out pos (alength ^bytes b))
            (recur (next bs) (+ pos (alength ^bytes b))))
        out))))

(defn logical
  "RECS (consecutive records of a prefix, in seq order) as logical records: a run of CNT slots that carries one commit
   (part i at seq s+i, one writer, kind and label) as one record at s whose body is its parts' bodies in order; a run
   with a part missing, torn or voided drops out whole — its commit was never acknowledged."
  [recs]
  (loop [rs (seq recs)
         acc []]
    (if-let [rec (first rs)]
      (let [cnt (bit-and (:part rec) 0xFFFF)]
        (if (<= cnt 1)
          (recur (next rs) (if (zero? (:part rec)) (conj acc rec) acc))
          (let [run (vec (take cnt rs))
                ok? (and (== cnt (count run))
                         (every? (fn [i]
                                   (let [p (nth run i)]
                                     (and (== (:part p) (part-word i cnt))
                                          (== (:seq p) (i32/i32 (+ (:seq rec) i)))
                                          (== (:writer p) (:writer rec))
                                          (== (:kind p) (:kind rec))
                                          (== (:label p) (:label rec)))))
                                 (range cnt)))]
            (if ok?
              (recur (nthnext rs cnt) (conj acc (assoc rec :part 0 :body (concat-bodies (map :body run)))))
              (recur (next rs) acc)))))
      acc)))

(defn split-body
  "BODY in CNT parts of at most REC_MAX_BODY bytes each, in order (the last ones empty when it is shorter)."
  [^bytes body cnt]
  (let [n (alength body)
        _ (when (> n (* cnt REC_MAX_BODY))
            (throw (ex-info "eve.jlog: a record body past its reserved run" {:len n :parts cnt})))]
    (mapv (fn [i]
            (let [from (min n (* i REC_MAX_BODY))
                  to (min n (* (inc i) REC_MAX_BODY))
                  b (byte-array (- to from))]
              (when (pos? (- to from)) (enc/arraycopy body from b 0 (- to from)))
              b))
          (range cnt))))

(defn fold
  "[table label]: ANCHOR's atom table (a copy) with the deltas of RECS' COMMIT and NOREDO records applied in log
   order — each the slot's new pointer and its name hash — and the highest label (serial) among the anchor's and
   theirs. ABORT and VOID records carry no delta; an ANCHOR among RECS is a fold of what came before it."
  [anchor recs]
  (let [t (byte-array TABLE_BYTES)]
    (enc/arraycopy (:body anchor) 0 t 0 TABLE_BYTES)
    (loop [rs (seq recs)
           label (:label anchor)]
      (if-let [rec (first rs)]
        (if (or (== K_COMMIT (:kind rec)) (== K_NOREDO (:kind rec)))
          (let [b (:body rec)
                o (* 8 (get-i32 b 0))]
            (when (and (<= 0 o) (< o TABLE_BYTES))
              (put-u32! t o (get-i32 b 8))
              (put-u32! t (+ o 4) (get-i32 b 12)))
            (recur (next rs) (if (pos? (serial- (:label rec) label)) (:label rec) label)))
          (recur (next rs) label))
        [t label]))))

(defn encode-anchor-body
  "An ANCHOR body: the whole atom table; with RIDS ({client seq}, at most ANCHOR_RIDS_MAX clients: the highest
   clients kept), the request-id table after it."
  ([table] (encode-anchor-body table nil))
  ([^bytes table rids]
   (let [rids (when (seq rids) (take ANCHOR_RIDS_MAX (sort-by (comp - key) rids)))
         [b i32! bytes!] (body-writer (+ (alength table) (if rids (+ 8 (* 8 (count rids))) 0)))]
     (bytes! table)
     (when rids
       (i32! RID_TAG) (i32! (count rids))
       (doseq [[c q] rids] (i32! c) (i32! q)))
     b)))

(defn anchor-rids
  "An ANCHOR record's request-id table {client seq} ({} for none)."
  [anchor]
  (let [^bytes b (:body anchor)
        p TABLE_BYTES]
    (if (and b (>= (alength b) (+ p 8)) (== (get-i32 b p) (i32/i32 RID_TAG)))
      (let [n (get-i32 b (+ p 4))]
        (into {} (map (fn [k] [(get-i32 b (+ p 8 (* 8 k))) (get-i32 b (+ p 12 (* 8 k)))]))
              (range (min n (quot (- (alength b) p 8) 8)))))
      {})))

(defn fold-rids
  "{client seq}: ANCHOR's request-id table with the request ids of RECS' COMMIT and NOREDO records (logical records,
   in log order), each client's highest seq."
  [anchor recs]
  (reduce (fn [m rec]
            (if-let [[c q] (when (or (== K_COMMIT (:kind rec)) (== K_NOREDO (:kind rec))) (body-rid (:body rec)))]
              (assoc m c (max q (get m c q)))
              m))
          (anchor-rids anchor)
          recs))

;; ---------------------------------------------------------------------------
;; The legacy journal (EVJ1, <base>.journal) — read once, for migration (§4.7)
;; ---------------------------------------------------------------------------

(def ^:const LEGACY_MAGIC 0x45564A31)     ;; "EVJ1"

(defn- get-i32-be
  "The big-endian u32 at OFF in B (EVJ1's word order), as a signed i32."
  [^bytes b off]
  (i32/i32 (bit-or (bit-shift-left (bit-and (aget b off) 0xFF) 24)
                   (bit-shift-left (bit-and (aget b (+ off 1)) 0xFF) 16)
                   (bit-shift-left (bit-and (aget b (+ off 2)) 0xFF) 8)
                   (bit-and (aget b (+ off 3)) 0xFF))))

(defn legacy-records
  "The CRC-valid EVJ1 records in B (a legacy journal file's whole bytes), in
   file order, each {:epoch :table :pos}. Record: [magic][len][epoch]
   [table-len][table][crc32 over epoch..table-end], big-endian. Unlike the
   per-host scanners it replaces, a framing failure does not end the read: the
   scan resyncs on the magic a byte further on, so a record another host's
   append overwrote never hides the good ones after it."
  [^bytes b]
  (let [n (alength b)
        magic (i32/i32 LEGACY_MAGIC)]
    (loop [pos 0
           acc []]
      (if (> (+ pos 16) n)
        acc
        (let [len (get-i32-be b (+ pos 4))
              tlen (get-i32-be b (+ pos 12))]
          (if (and (== (get-i32-be b pos) magic)
                   (== tlen TABLE_BYTES)
                   (== len (+ tlen 12))
                   (<= (+ pos 8 len) n)
                   (== (get-i32-be b (+ pos 16 tlen)) (crc/crc32 b (+ pos 8) (+ 8 tlen))))
            (let [table (byte-array TABLE_BYTES)]
              (enc/arraycopy b (+ pos 16) table 0 TABLE_BYTES)
              (recur (+ pos 8 len) (conj acc {:epoch (get-i32-be b (+ pos 8)) :table table :pos pos})))
            (recur (inc pos) acc)))))))

(defn tail
  "Stripe K's tail word as it stands (the next seq to reserve)."
  [log k]
  (mem/-load-i32 (:r log) (tail-off k)))
