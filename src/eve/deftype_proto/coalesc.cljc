(ns eve.deftype-proto.coalesc
  "Coalescing allocator for variable-size blocks (slab class 6).

   This is the general-purpose allocator that handles blocks > 1024 bytes.
   It uses a descriptor table to track contiguous regions within a data area.
   On free, adjacent FREE blocks are coalesced to reduce fragmentation.

   File layout (.slab6):
   ┌──────────────────────────────────────────────────────┐
   │ Header (64 bytes, same field offsets as slab header)  │
   │   [0..3]   magic: 0x534C4142 ('SLAB')                │
   │   [4..7]   block_size: 1 (class 6 convention)        │
   │   [8..11]  max_descriptors: u32                       │
   │   [12..15] alloc_cursor: u32 (descriptor scan hint)   │
   │   [20..23] class_idx: 6                               │
   │   [24..27] desc_table_offset: u32                     │
   │   [28..31] data_offset: u32                           │
   ├──────────────────────────────────────────────────────┤
   │ Descriptor Columns (max_desc × 28 bytes of columns)   │
   │   Each field its own dense array, indexed by desc   │
   ├──────────────────────────────────────────────────────┤
   │ Data Region (remaining space)                         │
   └──────────────────────────────────────────────────────┘

   Descriptor layout — per-field COLUMNS (28 bytes of columns per descriptor), one layout for every class,
   the same in eve and in ValueScript's kernel (VS's src/vs/kernel/wasm/alloc.vs, VS's src/vs/eve_constants.vs;
   2026-10-22; columns since 2026-10-25). Column order:
     STATUS:         FREE=0, ALLOCATED=1, ZEROED_UNUSED=-1
     GEOM:           DATA_OFFSET (byte offset relative to data region start) and BLOCK_CAPACITY (total
                     capacity of this contiguous block), collocated in one 8-byte entry
     DATA_LENGTH:    ≡ COUNT — how many use the block: a u32, 0 unused, all ones permanent (0 here
                          until the count is kept)
     LOCK_OWNER:     ≡ LOCK + FLAGS — bit 0 the lock (taken by a CAS that sets it in the word as read,
                          released by a CAS that clears it); bits 1-8 the count's flags
     RESERVED:       the POINTER word (-1)
     RESERVED2:      the EPOCH word (0)

   Addressing: slab-qualified offset = encode(6, data_offset).
   Since SLAB_SIZES[6]=1, block_idx × 1 = byte offset in data region.

   All operations go through IMemRegion for cross-platform (JVM + Node mmap)."
  (:refer-clojure :exclude [long])
  (:require
   [eve.host :refer [long]]
   [eve.deftype-proto.data :as d]
   [eve.mem]
   [eve.slab-io :as slab]))

;;=============================================================================
;; Constants
;;=============================================================================

(def ^:const desc-col-bytes 28)     ;; total column bytes per descriptor (the columns below)
(def ^:const MAX_DESCRIPTORS 16384) ;; heap-form default descriptor count; overridable per domain
;; The descriptor count is a CREATION parameter of the domain (stored in
;; the .slab6 header, read back at open) — never an intrinsic ceiling:
;; a domain opener sizes the table with :coalesc-descriptors. The mmap
;; form defaults far larger: its table lives in the sparse .slab6 file,
;; so untouched descriptor pages cost no resident memory.
(def ^:const DEFAULT_MMAP_DESCRIPTORS 1048576)

;; Descriptor field ids — a field names a COLUMN, not a byte offset in a row (2026-10-25). A field's entry
;; sits at dt-off + max-desc * (column prefix) + idx * (column width) + sub-offset (desc-byte-off):
;;   STATUS(4) GEOM(8: DATA_OFFSET +0, BLOCK_CAPACITY +4) COUNT(4) FLAGS(4) POINTER(4) EPOCH(4)
(def ^:const desc-status 0)
(def ^:const desc-data-offset 1)
(def ^:const desc-block-capacity 2)
(def ^:const desc-data-length 3)
(def ^:const desc-lock-owner 4)
(def ^:const desc-reserved 5)
(def ^:const DESC_DIGEST_PTR 5) ;; the POINTER column, claimed 2026-10-25 (Merkle P3 eve half, layout
                                ;; B-prime): the digest-cache side-block pointer — DESC_RESERVED above
                                ;; is this column under its former name (in row form both were the @16
                                ;; word). Birth stores write -1, the bitmap free resets write 0; both
                                ;; mean "no cache block". A live value is a slab offset stamped by
                                ;; one CAS (eve.digest).
(def ^:const desc-reserved2 6)

;; The count's words (the same columns in eve and vs): DATA_LENGTH is the block's COUNT, LOCK_OWNER carries the lock in
;; bit 0 and the count's flags in bits 1-8
(def ^:const desc-count 3)
(def ^:const desc-flags 4)

;; Status values
(def ^:const STATUS_FREE 0)
(def ^:const STATUS_ALLOCATED 1)
(def ^:const STATUS_ZEROED_UNUSED -1)

;; Lock: bit 0 of LOCK_OWNER (bits 1-8 are the count's flags, which a lock never touches)
(def ^:const lock-unlocked 0)
(def ^:const lock-sentinel 1)  ;; the lock bit
(def ^:const lock-clear -2)    ;; every bit but the lock's

;; Rung blocks (the unified ladder inside class 6): a rung block is a
;; fixed-size class-6 block stamped in its descriptor's LOCK_OWNER
;; flags. Bits 1, 2, 7, 8 of that word are eve.tally's (F_PUB, F_QUEUED,
;; F_LEAF, F_TAKEN); bits 3-6 are assigned nowhere else and carry the
;; rung field: rung-idx + 1 (1-14), 0 = an ordinary variable block.
;; The pointer format is untouched — a rung block's slab offset is an
;; ordinary class-6 offset (its data-region byte offset); the rung
;; identity lives in the descriptor, never in the pointer.
(def ^:const RUNG_FIELD_SHIFT 3)
(def ^:const RUNG_FIELD_MASK 120) ;; bits 3-6
(def ^:const rung-chunk 65536)    ;; one chunk, the rung quantum
(def ^:const rung-count 14)       ;; rungs of 2..15 chunks

(defn rung-size
  "The byte size of rung rung-idx (0-13): (rung-idx + 2) whole chunks."
  ^long [rung-idx]
  (* (long rung-chunk) (+ 2 (long rung-idx))))

(defn rung-idx-for-size
  "The rung index for an exact whole-chunk size (2..15 chunks of
   65536), or -1 when size-bytes is not a rung size."
  ^long [size-bytes]
  (let [size (long size-bytes)]
    (if (and (>= size 131072) (<= size 983040)
             (zero? (mod size (long rung-chunk))))
      (- (quot size (long rung-chunk)) 2)
      -1)))

;; Default data region max capacity for growth (4 PiB).
;; COALESC_HDR_DATA_SIZE is stored as i64, so the theoretical ceiling is 8 EiB.
;; 4 PiB = 2^52 bytes, exactly representable as both JS Number and JVM long.
(def ^:const DEFAULT_DATA_SIZE (* 4 1024 1024 1024 1024 1024))

;; Initial data region size for lazy growth (64 KB)
(def ^:const INITIAL_DATA_SIZE (* 64 1024))

;; Header extension: current data-region size at offset 32 (reserved bytes).
;; Stored as i64 (8 bytes at offsets 32-39) for >2 GB capacity.
(def ^:const COALESC_HDR_DATA_SIZE 32)

;;=============================================================================
;; Header access (reuses standard slab header field offsets)
;;=============================================================================

(defn- desc-table-offset
  "Read the descriptor table byte offset from the .slab6 header."
  [region]
  (slab/region-load-i32 region d/SLAB_HDR_BITMAP_OFFSET))

(defn- max-descs
  "Read the max descriptor count from the .slab6 header."
  [region]
  (slab/region-load-i32 region d/SLAB_HDR_TOTAL_BLOCKS))

(defn current-data-size
  "Read the current data-region size from the .slab6 header."
  [region]
  (slab/region-load-i64 region COALESC_HDR_DATA_SIZE))

;;=============================================================================
;; Descriptor field access
;;=============================================================================

(defn- desc-col-prefix
  "The per-entry bytes of the columns before field's column."
  [field]
  (cond (== field desc-status) 0
        (== field desc-data-length) 12
        (== field desc-lock-owner) 16
        (== field desc-reserved) 20
        (== field desc-reserved2) 24
        :else 4)) ;; the GEOM column (DATA_OFFSET / BLOCK_CAPACITY)

(defn- desc-col-width
  "The column's entry width: GEOM entries are 8 bytes, the rest 4."
  [field]
  (if (or (== field desc-data-offset) (== field desc-block-capacity)) 8 4))

(defn- desc-col-sub
  "The field's offset within its column entry: BLOCK_CAPACITY sits at +4."
  [field]
  (if (== field desc-block-capacity) 4 0))

(defn- desc-byte-off
  "Absolute byte offset of field within descriptor idx: the field's column entry."
  [dt-off max-desc idx field]
  (+ dt-off (* max-desc (desc-col-prefix field)) (* idx (desc-col-width field)) (desc-col-sub field)))

(defn- desc-load
  "Load an i32 field from descriptor idx."
  [region dt-off max-desc idx field]
  (slab/region-load-i32 region (desc-byte-off dt-off max-desc idx field)))

(defn- desc-store!
  "Store an i32 field to descriptor idx."
  [region dt-off max-desc idx field val]
  (slab/region-store-i32! region (desc-byte-off dt-off max-desc idx field) val))

(defn- desc-cas!
  "CAS an i32 field in descriptor idx. Returns old value."
  [region dt-off max-desc idx field expected desired]
  (slab/region-cas-i32! region (desc-byte-off dt-off max-desc idx field) expected desired))

;;=============================================================================
;; Lock helpers
;;=============================================================================

(defn- try-lock!
  "Attempt to CAS-lock a descriptor: set bit 0 of its LOCK_OWNER word as read (a flag that moved meanwhile fails the
   CAS: the caller tries again). Returns true on success."
  [region dt-off max-desc idx]
  (let [w (desc-load region dt-off max-desc idx desc-lock-owner)]
    (and (zero? (bit-and w lock-sentinel))
         (== w (desc-cas! region dt-off max-desc idx desc-lock-owner w (bit-or w lock-sentinel))))))

(defn- unlock!
  "Release the lock on a descriptor: clear bit 0 alone (the count's flags in bits 1-8 stay)."
  [region dt-off max-desc idx]
  (loop []
    (let [w (desc-load region dt-off max-desc idx desc-lock-owner)]
      (when-not (== w (desc-cas! region dt-off max-desc idx desc-lock-owner w (bit-and w lock-clear)))
        (recur)))))

(defn- spin-lock!
  "Spin-lock a descriptor with bounded retries. Returns true on success."
  [region dt-off max-desc idx max-retries]
  (loop [i 0]
    (cond
      (try-lock! region dt-off max-desc idx) true
      (>= i max-retries) false
      :else (recur (inc i)))))

;;=============================================================================
;; Find ZEROED_UNUSED descriptor slot
;;=============================================================================

(defn- find-zeroed-slot
  "Scan for a ZEROED_UNUSED descriptor slot starting from hint.
   Returns slot index or -1 if none found."
  [region dt-off max-desc hint]
  (loop [i hint wrapped? false]
    (cond
      (and (>= i max-desc) (not wrapped?))
      (recur 0 true)

      (and (>= i max-desc) wrapped?)
      -1

      (and wrapped? (>= i hint))
      -1

      :else
      (let [status (desc-load region dt-off max-desc i desc-status)]
        (if (== status STATUS_ZEROED_UNUSED)
          i
          (recur (inc i) wrapped?))))))

;;=============================================================================
;; Init
;;=============================================================================

(defn coalesc-layout
  "Calculate .slab6 file layout.
   Returns {:total-bytes :desc-table-offset :data-offset :max-descriptors}."
  [data-size max-desc]
  (let [dt-off   d/SLAB_HEADER_SIZE
        dt-size  (* max-desc desc-col-bytes)
        ;; Align data region to 16 bytes
        data-off (let [raw (+ dt-off dt-size)]
                   (* (quot (+ raw 15) 16) 16))]
    {:total-bytes     (+ data-off data-size)
     :desc-table-offset dt-off
     :data-offset     data-off
     :max-descriptors max-desc}))

(defn init-coalesc-region!
  "Initialize a .slab6 mmap region. Writes header, creates one FREE descriptor
   covering the entire data region, marks remaining descriptors ZEROED_UNUSED.
   Returns the IMemRegion."
  [region data-size max-desc]
  (let [{:keys [desc-table-offset data-offset]} (coalesc-layout data-size max-desc)]
    ;; Write header (reuse standard slab header field offsets)
    (slab/region-store-i32! region d/SLAB_HDR_MAGIC d/SLAB_MAGIC)
    (slab/region-store-i32! region d/SLAB_HDR_BLOCK_SIZE 1)       ;; class 6 convention
    (slab/region-store-i32! region d/SLAB_HDR_TOTAL_BLOCKS max-desc)
    (slab/region-store-i32! region d/SLAB_HDR_FREE_COUNT 1)       ;; 1 free descriptor
    (slab/region-store-i32! region d/SLAB_HDR_ALLOC_CURSOR 0)
    (slab/region-store-i32! region d/SLAB_HDR_CLASS_IDX 6)
    (slab/region-store-i32! region d/SLAB_HDR_BITMAP_OFFSET desc-table-offset)
    (slab/region-store-i32! region d/SLAB_HDR_DATA_OFFSET data-offset)
    ;; Extension: store current data-region size for lazy growth (i64)
    (slab/region-store-i64! region COALESC_HDR_DATA_SIZE data-size)

    ;; Descriptor 0: one big FREE block covering entire data region
    (desc-store! region desc-table-offset max-desc 0 desc-status STATUS_FREE)
    (desc-store! region desc-table-offset max-desc 0 desc-data-offset 0)
    (desc-store! region desc-table-offset max-desc 0 desc-data-length 0)
    (desc-store! region desc-table-offset max-desc 0 desc-block-capacity data-size)
    (desc-store! region desc-table-offset max-desc 0 desc-reserved -1)
    (desc-store! region desc-table-offset max-desc 0 desc-lock-owner lock-unlocked)
    (desc-store! region desc-table-offset max-desc 0 desc-reserved2 0)

    ;; Remaining descriptors 1..max-desc-1: STATUS <- ZEROED_UNUSED and
    ;; LOCK_OWNER <- UNLOCKED, one fill per column (each field's column is
    ;; contiguous, 4-byte entries — desc-byte-off), the same bytes the
    ;; per-descriptor loop wrote at ~1 us of protocol dispatch per store
    ;; (2.2 s at 1,048,576 descriptors on jank). LOCK_OWNER's zeros stay
    ;; explicit: a reused file is not guaranteed zero. This initializes a
    ;; fresh region before publication — it is not zeroing reused memory.
    (let [n (dec max-desc)]
      (when (pos? n)
        (eve.mem/fill-i32! region (desc-byte-off desc-table-offset max-desc 1 desc-status)
                           n STATUS_ZEROED_UNUSED)
        (eve.mem/fill-i32! region (desc-byte-off desc-table-offset max-desc 1 desc-lock-owner)
                           n lock-unlocked)))

    region))

;;=============================================================================
;; Growth
;;=============================================================================

(declare coalesce-adjacent!)

(defn grow-coalesc-region!
  "Grow the coalescing data region by doubling, up to max-data-size.
   Uses CAS on COALESC_HDR_DATA_SIZE for leader election.
   The leader creates a new FREE descriptor covering the appended space and
   merges it with a FREE block ending where the region ended (coalesce-adjacent!),
   so the region's tail stays one block. ensure! (optional) is called with the
   new data size before the CAS publishes it: a region that must be extended
   first (a growable SharedArrayBuffer) is extended there, so no thread reads a
   size past the region's end.
   Returns the new data size on success, or nil if already at max."
  ([region max-data-size] (grow-coalesc-region! region max-data-size nil))
  ([region max-data-size ensure!]
   (let [cur-data-size (slab/region-load-i64 region COALESC_HDR_DATA_SIZE)]
     (when (< cur-data-size max-data-size)
       (let [new-data-size (min (* cur-data-size 2) max-data-size)
             _             (when ensure! (ensure! new-data-size))
             witness       (slab/region-cas-i64! region COALESC_HDR_DATA_SIZE
                                                 cur-data-size new-data-size)]
         (if (== witness cur-data-size)
          ;; We won — create a FREE descriptor for the new space
           (let [dt-off     (desc-table-offset region)
                 max-desc   (max-descs region)
                 added-size (- new-data-size cur-data-size)
                 slot       (find-zeroed-slot region dt-off max-desc 0)]
             (when (not= slot -1)
               (when (try-lock! region dt-off max-desc slot)
                 (let [ss (desc-load region dt-off max-desc slot desc-status)]
                   (if (== ss STATUS_ZEROED_UNUSED)
                     (do
                       (desc-store! region dt-off max-desc slot desc-data-offset cur-data-size)
                       (desc-store! region dt-off max-desc slot desc-data-length 0)
                       (desc-store! region dt-off max-desc slot desc-block-capacity added-size)
                       (desc-store! region dt-off max-desc slot desc-reserved -1)
                       (desc-store! region dt-off max-desc slot desc-reserved2 0)
                       (desc-store! region dt-off max-desc slot desc-lock-owner lock-sentinel)  ;; flags clear, still locked
                       (desc-store! region dt-off max-desc slot desc-status STATUS_FREE)
                      ;; merge with the FREE block ending at cur-data-size (releases slot's lock)
                       (coalesce-adjacent! region dt-off max-desc slot))
                     (unlock! region dt-off max-desc slot)))))
             new-data-size)
          ;; Lost CAS — someone else grew, return their new size
           (let [actual (slab/region-load-i64 region COALESC_HDR_DATA_SIZE)]
             actual)))))))

;;=============================================================================
;; Alloc
;;=============================================================================

(defn- desc-rung-field
  "The rung field of descriptor idx: rung-idx + 1 (1-14) for a rung
   block, 0 for an ordinary variable block. Read from the LOCK_OWNER
   word's bits 3-6; the lock bit and eve.tally's flag bits are masked
   out, so the field reads the same locked or unlocked."
  ^long [region dt-off max-desc idx]
  (bit-shift-right
   (bit-and (desc-load region dt-off max-desc idx desc-lock-owner)
            (long RUNG_FIELD_MASK))
   (long RUNG_FIELD_SHIFT)))

(defn try-coalesc-alloc!
  "Allocate a contiguous block of at least size-bytes from the coalescing region.
   Returns the byte offset relative to data region start (= block_idx for class 6),
   or nil on OOM (coalesc-alloc! throws instead).

   Algorithm:
   1. Round size to 8-byte alignment (every block starts 8-aligned, so a
      :float64 array's data — 8 bytes into its block — can be viewed by a
      Float64Array; 4-byte rounding put it 4 bytes off after any block of an
      odd number of words, and the CLJS view then overlapped the header's count)
   2. Scan descriptors from cursor for FREE with capacity >= size
   3. CAS-lock candidate
   4. If capacity > size + 32: split remainder into new ZEROED slot
   5. Mark ALLOCATED, return data_offset"
  [region size-bytes]
  (let [size     (let [raw (long size-bytes)]
                   (* (quot (+ raw 7) 8) 8))  ;; 8-byte align
        dt-off   (desc-table-offset region)
        max-desc (max-descs region)
        cursor   (slab/region-load-i32 region d/SLAB_HDR_ALLOC_CURSOR)]
    (loop [i cursor wrapped? false]
      (cond
        ;; Wrapped past cursor — OOM
        (and wrapped? (>= i cursor))
        nil

        ;; Past end — wrap around
        (>= i max-desc)
        (if wrapped?
          nil
          (recur 0 true))

        :else
        (let [status   (desc-load region dt-off max-desc i desc-status)
              ;; A FREE descriptor stamped with a rung field is reserved
              ;; to its rung — never a variable candidate.
              capacity (when (and (== status STATUS_FREE)
                                  (zero? (desc-rung-field region dt-off max-desc i)))
                         (desc-load region dt-off max-desc i desc-block-capacity))]
          (if (and (== status STATUS_FREE)
                   (not (nil? capacity))
                   (>= capacity size))
            ;; Candidate found — try to lock
            (if (try-lock! region dt-off max-desc i)
              ;; Got the lock — verify still FREE, unflagged, with sufficient capacity (TOCTOU)
              (let [status2   (desc-load region dt-off max-desc i desc-status)
                    capacity2 (desc-load region dt-off max-desc i desc-block-capacity)]
                (if (and (== status2 STATUS_FREE)
                         (zero? (desc-rung-field region dt-off max-desc i))
                         (>= capacity2 size))
                  ;; Good — allocate
                  (let [data-off (desc-load region dt-off max-desc i desc-data-offset)
                        remainder (- capacity2 size)]
                    ;; Try split if remainder is worth it (>= 32 bytes)
                    (when (>= remainder 32)
                      (let [split-slot (find-zeroed-slot region dt-off max-desc
                                                         (mod (inc i) max-desc))]
                        (when (not= split-slot -1)
                          (when (try-lock! region dt-off max-desc split-slot)
                            ;; Verify still ZEROED (TOCTOU)
                            (let [ss (desc-load region dt-off max-desc split-slot desc-status)]
                              (if (== ss STATUS_ZEROED_UNUSED)
                                (do
                                  ;; Write split descriptor
                                  (desc-store! region dt-off max-desc split-slot desc-data-offset
                                               (+ data-off size))
                                  (desc-store! region dt-off max-desc split-slot desc-data-length 0)
                                  (desc-store! region dt-off max-desc split-slot desc-block-capacity
                                               remainder)
                                  (desc-store! region dt-off max-desc split-slot desc-reserved -1)
                                  (desc-store! region dt-off max-desc split-slot desc-reserved2 0)
                                  (desc-store! region dt-off max-desc split-slot desc-lock-owner lock-sentinel)
                                  (desc-store! region dt-off max-desc split-slot desc-status STATUS_FREE)
                                  (unlock! region dt-off max-desc split-slot)
                                  ;; Shrink our capacity
                                  (desc-store! region dt-off max-desc i desc-block-capacity size))
                                ;; Split slot taken — skip split, keep full capacity
                                (unlock! region dt-off max-desc split-slot)))))))
                    ;; Mark as allocated: its count 0 and its flags clear (it is ours, still locked)
                    (desc-store! region dt-off max-desc i desc-data-length 0)
                    (desc-store! region dt-off max-desc i desc-lock-owner lock-sentinel)
                    (desc-store! region dt-off max-desc i desc-reserved -1)
                    (desc-store! region dt-off max-desc i desc-reserved2 0)
                    (desc-store! region dt-off max-desc i desc-status STATUS_ALLOCATED)
                    ;; Update cursor
                    (slab/region-store-i32! region d/SLAB_HDR_ALLOC_CURSOR
                                            (mod (inc i) max-desc))
                    ;; Release lock and return
                    (unlock! region dt-off max-desc i)
                    data-off)
                  ;; TOCTOU: lost the race — release lock, continue scan
                  (do (unlock! region dt-off max-desc i)
                      (recur (inc i) wrapped?))))
              ;; Lock contention — skip, continue
              (recur (inc i) wrapped?))
            ;; Not a suitable candidate — continue
            (recur (inc i) wrapped?)))))))

(defn descriptors-exhausted?
  "True when no ZEROED_UNUSED descriptor remains in the region's
   table — allocation failure is then the table's size (the domain's
   :coalesc-descriptors creation parameter), not space: growing the
   data region cannot mint descriptors and must not be attempted
   (the growing allocator once doubled the data region twenty times
   chasing a descriptor failure — the wall the DataScript port hit)."
  [region]
  (== -1 (find-zeroed-slot region (desc-table-offset region) (max-descs region) 0)))

(defn coalesc-alloc!
  "try-coalesc-alloc!, throwing on OOM. The two failures are named
   apart: descriptor-table exhaustion (every descriptor in use — the
   table's size is the domain's :coalesc-descriptors creation
   parameter, not a space limit) vs a genuinely unplaceable block."
  [region size-bytes]
  (or (try-coalesc-alloc! region size-bytes)
      (let [dt-off   (desc-table-offset region)
            max-desc (max-descs region)]
        (if (== -1 (find-zeroed-slot region dt-off max-desc 0))
          (throw (ex-info (str "coalesc-alloc!: descriptor table exhausted — all "
                               max-desc " descriptors are in use. The count is a "
                               "creation parameter: reopen the domain with a larger "
                               ":coalesc-descriptors.")
                          {:size size-bytes :max-descriptors max-desc
                           :descriptor-exhausted true}))
          (throw (ex-info "coalesc-alloc!: out of memory" {:size size-bytes}))))))

(defn- try-reuse-rung!
  "Descriptor i is a candidate rung-free block: lock it, verify it is
   still FREE, stamped with field, and exactly size; publish it
   ALLOCATED and return its data offset. Any other outcome: nil (the
   caller keeps scanning); a lock we took is always released."
  [region dt-off max-desc i size field]
  (when (and (== (desc-load region dt-off max-desc i desc-status) STATUS_FREE)
             (== (desc-rung-field region dt-off max-desc i) field)
             (try-lock! region dt-off max-desc i))
    (let [ok?      (and (== (desc-load region dt-off max-desc i desc-status) STATUS_FREE)
                        (== (desc-rung-field region dt-off max-desc i) field)
                        (== (desc-load region dt-off max-desc i desc-block-capacity) size))
          data-off (when ok?
                     (desc-load region dt-off max-desc i desc-data-offset))]
      (when ok?
        (desc-store! region dt-off max-desc i desc-data-length 0)
        (desc-store! region dt-off max-desc i desc-reserved -1)
        (desc-store! region dt-off max-desc i desc-reserved2 0)
        (desc-store! region dt-off max-desc i desc-status STATUS_ALLOCATED))
      (unlock! region dt-off max-desc i)
      data-off)))

(defn- mark-rung-allocated!
  "Publish descriptor i (locked by the caller) as an ALLOCATED rung
   block: its capacity set to size, its count 0, its rung field
   stamped into the flags the lock word carries."
  [region dt-off max-desc i size field]
  (desc-store! region dt-off max-desc i desc-block-capacity size)
  (desc-store! region dt-off max-desc i desc-data-length 0)
  (desc-store! region dt-off max-desc i desc-lock-owner
               (bit-or lock-sentinel
                       (bit-shift-left field (long RUNG_FIELD_SHIFT))))
  (desc-store! region dt-off max-desc i desc-reserved -1)
  (desc-store! region dt-off max-desc i desc-reserved2 0)
  (desc-store! region dt-off max-desc i desc-status STATUS_ALLOCATED)
  (slab/region-store-i32! region d/SLAB_HDR_ALLOC_CURSOR
                          (mod (inc i) max-desc)))

(defn- try-carve-rung!
  "Descriptor i is a candidate variable block: lock it, verify it is
   still FREE, unflagged, and big enough, and carve exactly size as a
   rung block — whole on an exact fit, by a split that must complete
   on a remainder >= 32; a remainder in 1..31 or a split that cannot
   complete is not a carve. Returns the data offset, or nil to keep
   scanning; a lock we took is always released."
  [region dt-off max-desc i size field]
  (when (and (== (desc-load region dt-off max-desc i desc-status) STATUS_FREE)
             (zero? (desc-rung-field region dt-off max-desc i))
             (>= (desc-load region dt-off max-desc i desc-block-capacity) size)
             (try-lock! region dt-off max-desc i))
    (let [status2   (desc-load region dt-off max-desc i desc-status)
          capacity2 (desc-load region dt-off max-desc i desc-block-capacity)]
      (if (and (== status2 STATUS_FREE)
               (zero? (desc-rung-field region dt-off max-desc i))
               (>= capacity2 size))
        (let [data-off  (desc-load region dt-off max-desc i desc-data-offset)
              remainder (- capacity2 size)]
          (cond
            (zero? remainder)
            (do (mark-rung-allocated! region dt-off max-desc i size field)
                (unlock! region dt-off max-desc i)
                data-off)

            (>= remainder 32)
            (let [split-slot    (find-zeroed-slot region dt-off max-desc
                                                  (mod (inc i) max-desc))
                  split-locked? (and (not= split-slot -1)
                                     (try-lock! region dt-off max-desc split-slot))]
              (if (and split-locked?
                       (== (desc-load region dt-off max-desc split-slot desc-status)
                           STATUS_ZEROED_UNUSED))
                (do
                  (desc-store! region dt-off max-desc split-slot desc-data-offset
                               (+ data-off size))
                  (desc-store! region dt-off max-desc split-slot desc-data-length 0)
                  (desc-store! region dt-off max-desc split-slot desc-block-capacity
                               remainder)
                  (desc-store! region dt-off max-desc split-slot desc-reserved -1)
                  (desc-store! region dt-off max-desc split-slot desc-reserved2 0)
                  (desc-store! region dt-off max-desc split-slot desc-lock-owner lock-sentinel)
                  (desc-store! region dt-off max-desc split-slot desc-status STATUS_FREE)
                  (unlock! region dt-off max-desc split-slot)
                  (mark-rung-allocated! region dt-off max-desc i size field)
                  (unlock! region dt-off max-desc i)
                  data-off)
                (do (when split-locked?
                      (unlock! region dt-off max-desc split-slot))
                    (unlock! region dt-off max-desc i)
                    nil)))

            :else
            (do (unlock! region dt-off max-desc i)
                nil)))
        (do (unlock! region dt-off max-desc i)
            nil)))))

(defn try-coalesc-alloc-rung!
  "A rung block of rung-idx (0-13; (rung-idx + 2) whole 65536 chunks)
   from the coalescing region: its data-region byte offset, or nil
   when no rung block is free and no variable block can be carved to
   exactly the rung size. Pass 1 reuses a FREE descriptor stamped
   with this rung's field — the per-rung free list. Pass 2 carves.
   The rung field is stamped under the descriptor's lock before the
   block is published ALLOCATED."
  [region rung-idx]
  (let [size     (rung-size rung-idx)
        field    (inc (long rung-idx))
        dt-off   (desc-table-offset region)
        max-desc (max-descs region)]
    (or (loop [i 0]
          (when-not (>= i max-desc)
            (or (try-reuse-rung! region dt-off max-desc i size field) (recur (inc i)))))
        (loop [i 0]
          (if (>= i max-desc)
            nil
            (or (try-carve-rung! region dt-off max-desc i size field)
                (recur (inc i))))))))

(defn coalesc-alloc-rung!
  "try-coalesc-alloc-rung!, throwing on failure with the same named
   distinction as coalesc-alloc!: descriptor-table exhaustion vs a
   genuinely unplaceable rung."
  [region rung-idx]
  (or (try-coalesc-alloc-rung! region rung-idx)
      (let [dt-off   (desc-table-offset region)
            max-desc (max-descs region)]
        (if (== -1 (find-zeroed-slot region dt-off max-desc 0))
          (throw (ex-info (str "coalesc-alloc-rung!: descriptor table exhausted — all "
                               max-desc " descriptors are in use. The count is a "
                               "creation parameter: reopen the domain with a larger "
                               ":coalesc-descriptors.")
                          {:rung-idx rung-idx :max-descriptors max-desc
                           :descriptor-exhausted true}))
          (throw (ex-info "coalesc-alloc-rung!: out of memory" {:rung-idx rung-idx}))))))

;;=============================================================================
;; Free
;;=============================================================================

(defn- find-desc-by-data-offset
  "Find the descriptor index whose DATA_OFFSET == target-data-off.
   Returns index or -1."
  [region dt-off max-desc target-data-off]
  (loop [i 0]
    (if (>= i max-desc)
      -1
      (let [status (desc-load region dt-off max-desc i desc-status)]
        (if (and (not= status STATUS_ZEROED_UNUSED)
                 (== (desc-load region dt-off max-desc i desc-data-offset) target-data-off))
          i
          (recur (inc i)))))))

(defn row-of
  "The tally handle of the ALLOCATED block whose DATA_OFFSET is data-off-in-region: [count-byte-offset
   flags-byte-offset pointer-byte-offset] in region — the addresses of its COUNT, FLAGS and POINTER
   column entries — or nil when no allocated block starts there."
  [region data-off-in-region]
  (let [dt-off   (desc-table-offset region)
        max-desc (max-descs region)
        idx      (find-desc-by-data-offset region dt-off max-desc data-off-in-region)]
    (when (and (not= idx -1) (== STATUS_ALLOCATED (desc-load region dt-off max-desc idx desc-status)))
      [(desc-byte-off dt-off max-desc idx desc-count)
       (desc-byte-off dt-off max-desc idx desc-flags)
       (desc-byte-off dt-off max-desc idx desc-reserved)])))

(defn- coalesce-adjacent!
  "Merge physically adjacent FREE blocks around descriptor idx.
   Must be called with idx already locked and marked FREE.
   Releases idx lock when done."
  [region dt-off max-desc idx]
  ;; A rung block never merges, on either side: its descriptor keeps
  ;; its exact capacity for its rung's free list. (Callers never pass
  ;; a rung idx today — rung free does not call this fn — but the
  ;; guard keeps the invariant local to the merge itself.)
  (if (pos? (desc-rung-field region dt-off max-desc idx))
    (unlock! region dt-off max-desc idx)
    (let [our-off   (desc-load region dt-off max-desc idx desc-data-offset)
          our-cap   (desc-load region dt-off max-desc idx desc-block-capacity)
          _our-end   (+ our-off our-cap) left-idx
          (loop [j 0]
            (if (>= j max-desc)
              -1
              (let [status (desc-load region dt-off max-desc j desc-status)]
                (if (and (== status STATUS_FREE)
                         (not= j idx)
                         (zero? (desc-rung-field region dt-off max-desc j))
                         (let [loff (desc-load region dt-off max-desc j desc-data-offset)
                               lcap (desc-load region dt-off max-desc j desc-block-capacity)]
                           (== (+ loff lcap) our-off)))
                  j
                  (recur (inc j))))))

              ;; If left found, try to lock and merge
          [survivor surv-off surv-cap _left-locked?]
          (if (and (not= left-idx -1) (spin-lock! region dt-off max-desc left-idx 200))
            ;; Verify still FREE (TOCTOU)
            (let [ls (desc-load region dt-off max-desc left-idx desc-status)]
              (if (and (== ls STATUS_FREE)
                       (zero? (desc-rung-field region dt-off max-desc left-idx)))
                (let [loff (desc-load region dt-off max-desc left-idx desc-data-offset)
                      lcap (desc-load region dt-off max-desc left-idx desc-block-capacity)]
                  (if (== (+ loff lcap) our-off)
                    ;; Merge: left absorbs us
                    (let [new-cap (+ lcap our-cap)]
                      (desc-store! region dt-off max-desc left-idx desc-block-capacity new-cap)
                      ;; Mark ourselves as ZEROED_UNUSED (reclaim descriptor)
                      (desc-store! region dt-off max-desc idx desc-status STATUS_ZEROED_UNUSED)
                      (desc-store! region dt-off max-desc idx desc-lock-owner lock-unlocked)
                      ;; Survivor is left (keep left locked for right merge)
                      [left-idx loff new-cap true])
                    ;; Offsets don't match anymore — release left
                    (do (unlock! region dt-off max-desc left-idx)
                        [idx our-off our-cap false])))
                ;; Left no longer FREE — release
                (do (unlock! region dt-off max-desc left-idx)
                    [idx our-off our-cap false])))
            ;; Left not found or lock failed
            [idx our-off our-cap false])   surv-end     (+ surv-off surv-cap)
          right-idx
          (loop [j 0]
            (if (>= j max-desc)
              -1
              (let [status (desc-load region dt-off max-desc j desc-status)]
                (if (and (== status STATUS_FREE)
                         (not= j survivor)
                         (zero? (desc-rung-field region dt-off max-desc j))
                         (== (desc-load region dt-off max-desc j desc-data-offset) surv-end))
                  j
                  (recur (inc j))))))] (when (and (not= right-idx -1) (spin-lock! region dt-off max-desc right-idx 200))
                                         (let [rs (desc-load region dt-off max-desc right-idx desc-status)]
                                           (if (and (== rs STATUS_FREE)
                                                    (zero? (desc-rung-field region dt-off max-desc right-idx))
                                                    (== (desc-load region dt-off max-desc right-idx desc-data-offset) surv-end))
                                             (let [rcap (desc-load region dt-off max-desc right-idx desc-block-capacity)]
                ;; Merge: survivor absorbs right
                                               (desc-store! region dt-off max-desc survivor desc-block-capacity
                                                            (+ surv-cap rcap))
                ;; Mark right as ZEROED_UNUSED
                                               (desc-store! region dt-off max-desc right-idx desc-status STATUS_ZEROED_UNUSED)
                                               (unlock! region dt-off max-desc right-idx))
              ;; Right not valid anymore
                                             (unlock! region dt-off max-desc right-idx)))) (unlock! region dt-off max-desc survivor))))

(defn coalesc-free!
  "Free a block identified by its data-region-relative byte offset.
   Returns true if freed, false if not found."
  [region data-off-in-region]
  (let [dt-off   (desc-table-offset region)
        max-desc (max-descs region)
        idx      (find-desc-by-data-offset region dt-off max-desc data-off-in-region)]
    (if (== idx -1)
      false
      (if (spin-lock! region dt-off max-desc idx 400)
        (let [status (desc-load region dt-off max-desc idx desc-status)]
          (if (== status STATUS_ALLOCATED)
            (let [rfield (desc-rung-field region dt-off max-desc idx)]
              (if (pos? rfield)
                ;; Rung free: mark FREE, KEEP the rung field and the
                ;; capacity — the block returns to its rung's free
                ;; list and is never split or coalesced.
                (do
                  (desc-store! region dt-off max-desc idx desc-data-length 0)
                  (desc-store! region dt-off max-desc idx desc-lock-owner
                               (bit-or lock-sentinel
                                       (bit-shift-left rfield (long RUNG_FIELD_SHIFT))))
                  (desc-store! region dt-off max-desc idx desc-status STATUS_FREE)
                  (unlock! region dt-off max-desc idx)
                  true)
                (do
                  ;; Mark FREE: its count and flags go with it (still locked)
                  (desc-store! region dt-off max-desc idx desc-data-length 0)
                  (desc-store! region dt-off max-desc idx desc-lock-owner lock-sentinel)
                  (desc-store! region dt-off max-desc idx desc-status STATUS_FREE)
                  ;; Coalesce (releases lock)
                  (coalesce-adjacent! region dt-off max-desc idx)
                  true)))
            ;; Already free or zeroed — release lock
            (do (unlock! region dt-off max-desc idx)
                false)))
        ;; Lock contention — give up
        false))))
