(ns eve.deftype-proto.data
  "Slab allocator constants and layout definitions.

   ARCHITECTURE:
   ═══════════════════════════════════════════════════════════════

   Instead of one SAB with a flat descriptor table + coalescing,
   we use MULTIPLE SharedArrayBuffers — one per size class.
   Each slab SAB contains only fixed-size blocks, so:
   - No coalescing ever needed (all blocks same size)
   - Free tracking via a bitmap (1 bit per block)
   - SIMD scans 128 blocks per v128 load (vs 4 descriptors before)
   - Each slab can grow independently via memory.grow

   SIZE CLASSES (power-of-2 + HAMT-optimal):
   ═══════════════════════════════════════════════════════════════

   Slab  Block   Typical contents
   ────  ──────  ────────────────────────────────────
    0     32B    Tiny: 0-1 children, 1 small KV, list nodes
    1     64B    Common: 2-3 children, 1-2 KVs
    2    128B    Medium: 4-8 children, 2-4 KVs
    3    256B    Dense: 8-16 children, multiple KVs
    4    512B    Very dense or collision nodes
    5   1024B    Large collision nodes, big KV payloads

   Requests > 1024B go to an overflow slab that uses the old
   exact-fit allocator (fallback to eve.atom behavior).

   SLAB MEMORY LAYOUT (each slab SAB):
   ═══════════════════════════════════════════════════════════════

   ┌──────────────────────────────────────────────────────┐
   │ Slab Header (64 bytes, cache-line aligned)           │
   │   [0..3]   magic: 0x534C4142 ('SLAB')               │
   │   [4..7]   block_size: u32                           │
   │   [8..11]  total_blocks: u32                         │
   │   [12..15] free_count: u32 (atomic)                  │
   │   [16..19] alloc_cursor: u32 (per-worker hint)       │
   │   [20..23] slab_class_idx: u32                       │
   │   [24..27] bitmap_offset: u32                        │
   │   [28..31] data_offset: u32                          │
   │   [32..63] reserved                                  │
   ├──────────────────────────────────────────────────────┤
   │ Bitmap Region (1 bit per block, 16-byte aligned)     │
   │   bit=0 → free, bit=1 → allocated                   │
   │   Size: ceil(total_blocks / 8) bytes, padded to 16B  │
   ├──────────────────────────────────────────────────────┤
   │ Data Region (block_size × total_blocks bytes)        │
   │   All blocks are contiguous, uniformly sized.        │
   │   Block N starts at: data_offset + N * block_size    │
   └──────────────────────────────────────────────────────┘

   ROOT POINTER SAB (separate, shared by all workers):
   ═══════════════════════════════════════════════════════════════
   Stores the atom root pointer, epoch counter, worker registry.
   Root pointer value is now:  [slab_class_idx:u8 block_idx:u24]
   packed into a single i32 so the root CAS stays a single atomic.

   BITMAP SCANNING (WASM + SIMD):
   ═══════════════════════════════════════════════════════════════
   v128.load bitmap bytes → v128.not → find first non-zero byte
   via i8x16.bitmask + ctz. Scans 128 bits (128 blocks) per
   SIMD iteration. For a 1MB slab with 64B blocks (16384 blocks),
   the full bitmap is 2KB = 128 v128 loads worst case."
  (:refer-clojure :exclude [aget alength unsigned-bit-shift-right bytes long])
  (:require ; this spec's refer list mirrors the file's :refer-clojure :exclude declaration - the host-face surface, kept in sync by construction; the flagged names are not called from this file's body 
   #_{:clj-kondo/ignore [:unused-referred-var]}   [eve.host :refer [aget alength unsigned-bit-shift-right bytes long extend-default]]
   [eve.fast-proto :as fp]))

;;=============================================================================
;; Size Classes
;;=============================================================================

(def ^:const num-slab-classes 6)

;; Block sizes for each slab class (bytes)
(def ^:const slab-size-0 32)
(def ^:const slab-size-1 64)
(def ^:const slab-size-2 128)
(def ^:const slab-size-3 256)
(def ^:const slab-size-4 512)
(def ^:const slab-size-5 1024)

;; Lookup table: class index → block size
;; Index 6 = overflow (legacy SAB): block_size=1 so block_idx * 1 = byte_offset
(def slab-sizes [32 64 128 256 512 1024 1])

(defn slab-size
  "The block size of slab class class-idx (SLAB_SIZES' entry)."
  [class-idx]
  (case (int class-idx)
    0 32 1 64 2 128 3 256 4 512 5 1024 6 1))

;; Max block size handled by slab allocator
;; Anything larger goes to the overflow (old-style) allocator
(def ^:const slab-max-block-size 1024)

;; Default max capacity per bitmap slab (bytes of data region).
;; Bitmap slabs use i32 header fields; the practical limit is the packed
;; slab pointer (block_idx:u24 = 16M blocks).  2 GB is the fallback for
;; out-of-range class indices.
(def ^:const default-slab-capacity (* 2 1024 1024 1024)) ;; 2GB

;; Per-class max capacities sized for production workloads.
;; Bitmap and data offsets are computed for max capacity at init time so the
;; data region never moves during growth.  On Linux/macOS, sparse files and
;; lazy mmap page commit mean untouched bitmap/data pages cost zero physical
;; memory or disk — only virtual address space.  Growth doubles lazily via
;; CAS on the header's total_blocks field.
;;
;; Class 0 (32B):    1GB =   32M blocks  (HAMT bitmap nodes — heaviest use)
;; Class 1 (64B):    1GB =   16M blocks  (HAMT nodes, small values)
;; Class 2 (128B):   1GB =    8M blocks  (vec trie nodes, larger HAMT nodes)
;; Class 3 (256B): 512MB =    2M blocks  (collision nodes, serialized values)
;; Class 4 (512B): 256MB =  512K blocks  (less common)
;; Class 5 (1024B):256MB =  256K blocks  (least common)
(def ^:private slab-class-capacities
  [(* 1024 1024 1024)      ;; Class 0: 1GB
   (* 1024 1024 1024)      ;; Class 1: 1GB
   (* 1024 1024 1024)      ;; Class 2: 1GB
   (* 512 1024 1024)       ;; Class 3: 512MB
   (* 256 1024 1024)       ;; Class 4: 256MB
   (* 256 1024 1024)])     ;; Class 5: 256MB

;; Initial (small) capacities for lazy slab growth.
;; Files start at these sizes and grow on demand up to max capacities.
;; Total initial data: ~224KB (grows lazily to GB/TB-scale max).
;; Note: bitmap region is sized for max capacity but sparse-file semantics
;; mean untouched bitmap pages cost zero physical memory/disk.
(def ^:private slab-class-initial-capacities
  [(* 64 1024)     ;; Class 0 (32B):  64KB = 2K blocks
   (* 64 1024)     ;; Class 1 (64B):  64KB = 1K blocks
   (* 32 1024)     ;; Class 2 (128B): 32KB = 256 blocks
   (* 32 1024)     ;; Class 3 (256B): 32KB = 128 blocks
   (* 16 1024)     ;; Class 4 (512B): 16KB = 32 blocks
   (* 16 1024)])   ;; Class 5 (1024B):16KB = 16 blocks

(defn default-capacity-for-class
  "Get the default (max) capacity for a slab class index."
  [class-idx]
  (let [caps slab-class-capacities
        len  (count caps)]
    (if (and (>= class-idx 0) (< class-idx len))
      (nth caps class-idx)
      default-slab-capacity)))

(defn initial-capacity-for-class
  "Get the initial (small) capacity for a slab class index.
   Files start at this size and grow on demand."
  [class-idx]
  (let [caps slab-class-initial-capacities
        len  (count caps)]
    (if (and (>= class-idx 0) (< class-idx len))
      (nth caps class-idx)
      (* 64 1024))))

;;=============================================================================
;; Slab Header Layout (64 bytes, cache-line aligned)
;;=============================================================================

(def ^:const slab-header-size 64)

;; Byte offsets within slab header
(def ^:const slab-hdr-magic 0)           ;; u32: 0x534C4142
(def ^:const slab-hdr-block-size 4)      ;; u32: block size in bytes
(def ^:const slab-hdr-total-blocks 8)    ;; u32: number of blocks in this slab
(def ^:const slab-hdr-free-count 12)     ;; u32: atomic free block counter
(def ^:const slab-hdr-alloc-cursor 16)   ;; u32: hint for next scan start
(def ^:const slab-hdr-class-idx 20)      ;; u32: which size class (0-5)
(def ^:const slab-hdr-bitmap-offset 24)  ;; u32: byte offset where bitmap starts
(def ^:const slab-hdr-data-offset 28)    ;; u32: byte offset where data region starts
;; 32..63 reserved for future use (epoch, generation, etc.)

(def ^:const slab-magic 0x534C4142)      ;; 'SLAB' in ASCII

;;=============================================================================
;; Bitmap Layout
;;=============================================================================

;; Bitmap is packed: 1 bit per block, 0=free, 1=allocated.
;; Aligned to 16 bytes for SIMD v128 loads.
(def ^:const bitmap-alignment 16)

(defn bitmap-byte-size
  "Calculate bitmap size in bytes for n blocks, padded to 16-byte alignment."
  [total-blocks]
  (let [raw-bytes (quot (+ total-blocks 7) 8)
        padded    (* (quot (+ raw-bytes (dec bitmap-alignment)) bitmap-alignment)
                     bitmap-alignment)]
    padded))

;;=============================================================================
;; Descriptor Table Layout
;;=============================================================================

;; Every block of a bitmap class has a descriptor: per-field COLUMNS, the same layout discipline as class 6's
;; coalescing columns and ValueScript's kernel (VS's src/vs/kernel/wasm/alloc.vs; one layout
;; and one implementation, 2026-10-22; columns since 2026-10-25). A column exists where a
;; class reads it: eve's bitmap allocator reads and writes a block's COUNT and FLAGS, and eve.digest reads
;; and stamps its POINTER (the digest-cache side-block pointer, coalesc/DESC_DIGEST_PTR — a collection root
;; header is a bitmap-class block, so the claim the row form kept at @16 lives here as the third column),
;; so an eve bitmap class carries those three columns (the kernel's bitmap classes carry STATUS, SIDE and
;; EPOCH columns beside their own). The columns are block i's management record and never split or merge.
;; A heap or SAB slab carries them between its bitmap and its data (the columns end where the blocks begin,
;; as on a kernel page); an mmap slab carries each column in its own file beside the .bm
;; ({base}.slabN.desc-count / .desc-flags / .desc-pointer), so a class's growth — re-mapping its files
;; bigger — never moves an entry.

(defn desc-table-byte-size
  "The descriptor columns' bytes for n blocks in ONE buffer (a heap/SAB slab carries the COUNT, FLAGS
   and POINTER columns between its bitmap and its data): 12 bytes a block, padded to 16 bytes (the
   data region after it stays 16-aligned)."
  [total-blocks]
  (let [raw (* total-blocks 12)]
    (* (quot (+ raw (dec bitmap-alignment)) bitmap-alignment) bitmap-alignment)))

(defn desc-column-byte-size
  "One descriptor column's bytes for n blocks: a flat i32 array, 4 bytes a block. An mmap slab carries
   each column in its own file ({base}.slabN.desc-count / .desc-flags / .desc-pointer), so growth stays
   append-only: re-mapping a bigger file never moves an entry."
  [total-blocks]
  (* total-blocks 4))

(defn slab-desc-offset
  "Where a heap or SAB slab's descriptor columns start: right after its bitmap."
  [bitmap-offset total-blocks]
  (+ bitmap-offset (bitmap-byte-size total-blocks)))

(defn desc-col-addr
  "The byte offset of a bitmap descriptor column entry in ONE buffer: the COUNT column starts at
   desc-offset, the FLAGS column right after it (desc-offset + 4 * total-blocks), the POINTER column
   after that (desc-offset + 8 * total-blocks); block idx's entry is 4 * idx into its column."
  [desc-offset total-blocks col idx]
  (+ desc-offset (* total-blocks 4 col) (* idx 4)))

;; The three columns an eve bitmap class carries, as desc-col-addr's col argument.
(def ^:const desc-col-count 0)
(def ^:const desc-col-flags 1)
(def ^:const desc-col-pointer 2)

(defn slab-layout
  "Calculate layout for a slab with given block-size and capacity.
   Returns {:total-bytes :bitmap-offset :bitmap-size :desc-offset :data-offset :total-blocks}.
   Used for SAB/heap slabs where bitmap, descriptor columns and data share one buffer:
   [header 64][bitmap][descriptor columns][data]."
  [block-size capacity-bytes]
  (let [total-blocks  (quot capacity-bytes block-size)
        bitmap-offset slab-header-size
        bm-size       (bitmap-byte-size total-blocks)
        desc-offset   (+ bitmap-offset bm-size)
        data-offset   (+ desc-offset (desc-table-byte-size total-blocks))
        total-bytes   (+ data-offset (* total-blocks block-size))]
    {:total-bytes   total-bytes
     :bitmap-offset bitmap-offset
     :bitmap-size   bm-size
     :desc-offset   desc-offset
     :data-offset   data-offset
     :total-blocks  total-blocks}))

(def ^:const max-heap-class-bytes
  "The largest one-buffer heap slab a bitmap class may grow to, in bytes. Its
   offsets (header data/bitmap offsets, block addresses) are i32, and a JVM
   heap region is one byte array, indexed by int (Integer/MAX_VALUE - 8, the
   array headroom the JDK's own collections keep)."
  2147483639)

(defn heap-class-growth-total
  "The block count a heap bitmap class of block-size holding old-total blocks
   grows to: double, capped by max-blocks when positive. nil when that does
   not grow the class, or when the grown class's one-buffer layout
   (slab-layout) would pass MAX_HEAP_CLASS_BYTES: growth is then refused (the
   allocator spills to the next class) instead of asking the host for a region
   its offsets cannot address."
  [block-size old-total max-blocks]
  (let [block-size (long block-size)
        old-total  (long old-total)
        max-blocks (long max-blocks)
        new-total  (if (pos? max-blocks)
                     (min (* 2 old-total) max-blocks)
                     (* 2 old-total))]
    (when (and (> new-total old-total)
               (<= (long (:total-bytes (slab-layout block-size (* new-total block-size))))
                   max-heap-class-bytes))
      new-total)))

(defn mmap-slab-layout
  "Calculate split-file layout for an mmap slab.
   Bitmap, descriptor table and data live in separate files so data_offset is always
   SLAB_HEADER_SIZE. Returns {:data-bytes :bitmap-bytes :desc-column-bytes :total-blocks}."
  [block-size capacity-bytes]
  (let [total-blocks (quot capacity-bytes block-size)]
    {:data-bytes   (+ slab-header-size (* total-blocks block-size))
     :bitmap-bytes (bitmap-byte-size total-blocks)
     :desc-column-bytes (desc-column-byte-size total-blocks)
     :total-blocks total-blocks}))

(defn slab-bitmap-path
  "Return the bitmap file path for a slab class, e.g. \"/tmp/eve.slab0.bm\"."
  [base-path class-idx]
  (str base-path ".slab" class-idx ".bm"))

(defn slab-desc-count-path
  "Return the COUNT column file path for a slab class, e.g. \"/tmp/eve.slab0.desc-count\"."
  [base-path class-idx]
  (str base-path ".slab" class-idx ".desc-count"))

(defn slab-desc-flags-path
  "Return the FLAGS column file path for a slab class, e.g. \"/tmp/eve.slab0.desc-flags\"."
  [base-path class-idx]
  (str base-path ".slab" class-idx ".desc-flags"))

(defn slab-desc-pointer-path
  "Return the POINTER column file path for a slab class, e.g. \"/tmp/eve.slab0.desc-pointer\"."
  [base-path class-idx]
  (str base-path ".slab" class-idx ".desc-pointer"))

(defn slab-data-path
  "Return the data file path for a slab class, e.g. \"/tmp/eve.slab0\"."
  [base-path class-idx]
  (str base-path ".slab" class-idx))

;;=============================================================================
;; Size-Class Routing
;;=============================================================================

(defn size->class-idx
  "Given a requested byte size, return the slab class index (0-5).
   Returns -1 if the size exceeds SLAB_MAX_BLOCK_SIZE (use overflow allocator)."
  [size-bytes]
  (cond
    (<= size-bytes slab-size-0) 0
    (<= size-bytes slab-size-1) 1
    (<= size-bytes slab-size-2) 2
    (<= size-bytes slab-size-3) 3
    (<= size-bytes slab-size-4) 4
    (<= size-bytes slab-size-5) 5
    :else -1))

;;=============================================================================
;; Packed Slab Pointer (for root pointer CAS)
;;=============================================================================
;; A slab pointer packs [class_idx:u8 | block_idx:u24] into one i32.
;; This lets the root pointer CAS remain a single atomic i32 operation.
;; Max block index: 2^24 - 1 = 16,777,215 (plenty for 1MB slabs)

(def ^:const slab-ptr-nil -1)  ;; sentinel for "no value" / empty

(defn pack-slab-ptr
  "Pack a slab class index and block index into a single i32."
  [class-idx block-idx]
  (bit-or (bit-shift-left (bit-and class-idx 0xFF) 24)
          (bit-and block-idx 0xFFFFFF)))

(defn unpack-slab-class
  "Extract the slab class index (0-5) from a packed slab pointer."
  [slab-ptr]
  (bit-and (unsigned-bit-shift-right slab-ptr 24) 0xFF))

(defn unpack-slab-block-idx
  "Extract the block index from a packed slab pointer."
  [slab-ptr]
  (bit-and slab-ptr 0xFFFFFF))

;;=============================================================================
;; Root/Control SAB Layout
;;=============================================================================
;; The root SAB stores global state shared across workers:
;; - Atom root pointer (packed slab ptr)
;; - Global epoch counter
;; - Worker registry

(def ^:const size-of-int32 4)

(def ^:const root-sab-header-size 64)   ;; cache-line aligned header

(def ^:const root-magic-offset 0)         ;; u32: 0x524F4F54 ('ROOT')
(def ^:const root-atom-ptr-offset 4)      ;; i32: packed slab ptr (class|block)
(def ^:const root-epoch-offset 8)         ;; u32: global epoch counter
(def ^:const root-worker-reg-offset 12)   ;; u32: byte offset to worker registry start
;; Bytes 16..63 reserved

(def ^:const root-magic 0x524F4F54)       ;; 'ROOT' in ASCII

;; Worker registry begins at byte 64 (right after header)
(def ^:const root-worker-registry-start root-sab-header-size)

;; Worker registry config (same as eve.data)
(def ^:const max-workers 256)
(def ^:const worker-slot-size 24)
(def ^:const worker-registry-size (* max-workers worker-slot-size))

;; Total root SAB size
(def ^:const root-sab-size (+ root-sab-header-size worker-registry-size))

;; Worker status values
(def ^:const worker-status-inactive 0)
(def ^:const worker-status-active 1)
(def ^:const worker-status-stale 2)

;; Worker slot layout (24 bytes per worker)
(def ^:const offset-ws-status 0)
(def ^:const offset-ws-current-epoch 4)
(def ^:const offset-ws-heartbeat-lo 8)
(def ^:const offset-ws-heartbeat-hi 12)
(def ^:const offset-ws-worker-id 16)
(def ^:const offset-ws-reserved 20)

(def ^:const heartbeat-timeout-ms 30000)

;;=============================================================================
;; Atom Slot Table (multi-atom domain support)
;;=============================================================================
;; Appended after the worker registry in .root files (V2 format).
;; 256 slots × 8 bytes each = 2048 bytes + 64-byte table header.
;; Slot 0 is reserved for the registry atom (keyword→slot-idx HAMT).

(def ^:const root-magic-v2 0x524F4F55)       ;; bumped from 0x524F4F54

;; Atom table header starts right after the worker registry
(def ^:const atom-table-header-start (+ root-sab-header-size worker-registry-size))
(def ^:const atom-table-header-size 64)
(def ^:const atom-table-magic 0x41544F4D)    ;; 'ATOM' in ASCII

;; Atom slots start after the table header
(def ^:const atom-table-start (+ atom-table-header-start atom-table-header-size))
(def ^:const atom-slot-size 8)
(def ^:const max-atom-slots 256)
(def ^:const atom-slot-ptr-offset 0)         ;; i32: slab-qualified CAS target
(def ^:const atom-slot-hash-offset 4)        ;; u32: FNV-1a hash of keyword name

;; Total V2 root file size
(def ^:const root-file-size-v2
  (+ atom-table-start (* max-atom-slots atom-slot-size)))

;; Header field for atom table offset (byte 16 in .root header)
(def ^:const root-atom-table-offset 16)
;; Header field for atom table capacity (byte 20 in .root header)
(def ^:const root-atom-table-capacity 20)

;; The exclusive-hold protocol (bytes 24 and 28 of the .root header; zero in
;; every domain written before it, which is how a domain without a lock-only
;; participant behaves exactly as before). A host with no atomic
;; read-modify-write on shared memory (babashka: no MethodHandles, so no
;; VarHandle CAS — measured 2026-10-15) still takes part in the CAS protocol:
;;   * ROOT_HOLD     i32: 0, or 1 + the worker slot of the lock-only participant
;;                   whose swap is in progress. That participant serialises
;;                   against its own kind with a byte-range file lock on this
;;                   word, then stores the hold, then waits for ROOT_INFLIGHT
;;                   to reach 0, then runs its whole swap with plain loads and
;;                   stores (nothing else is writing), then clears the hold.
;;   * ROOT_INFLIGHT i32: the number of lock-free swaps in progress. A
;;                   lock-free host adds 1 before its swap and subtracts 1
;;                   after; if it sees ROOT_HOLD set after adding, it subtracts
;;                   again and waits for the hold to clear before retrying.
;; eve/doc/unification/FORMAT-DECISIONS.md names the proof and the one gap
;; (a participant that does not implement the two words — the wasm host at
;; the time of writing — races a lock-only participant, never a lock-free one).
(def ^:const root-hold-offset 24)
(def ^:const root-inflight-offset 28)

;; Byte 32 of the .root header is reserved (zero). The count-pages tally of the 0.9 line kept its directory in
;; this word (1 + the slab offset of the directory block, for the domain's life); the descriptor-row count
;; (eve.tally) keeps every count on the block's own descriptor row and reads no directory, so the word is never
;; written — a domain whose header carries a nonzero word here simply has it ignored.
(def ^:const root-slot-hint-offset 36)     ;; FORMAT-DECISIONS §12: the next-free atom slot hint; word 20 is the capacity

(defn atom-slot-offset
  "Byte offset of a field within the atom slot table in the .root file."
  [slot-idx field-offset]
  (+ atom-table-start (* slot-idx atom-slot-size) field-offset))

;; Block status (for epoch-GC on the bitmap: we only need 1-bit allocated,
;; but for retirement we track status per-block in a separate status byte array.
;; However, the slab design simplifies this: most blocks are either free or allocated.
;; Retired blocks get their bitmap bit set but are tracked in a retired-list.)
(def ^:const status-free 0)
(def ^:const status-allocated 1)
(def ^:const status-retired 2)

;;=============================================================================
;; Protocols (interface-compatible with eve.data)
;;=============================================================================

(defprotocol IEveRoot
  "Marks an Eve type that can live at the atom root.
   Implement (-root-header-off this) to return the slab-qualified offset of the
   type's header block. That one method is sufficient for atom swap! to work."
  (-root-header-off [this]))

(defprotocol IEveSio
  "The slab I/O a slab-backed value reads through (its domain). nil for a
   value that is not slab-backed. eve.atom uses it to refuse a value from a
   foreign domain at the root."
  (-eve-sio [this]))

(extend-default IEveSio
                (-eve-sio [_] nil))

(defprotocol IDirectSerialize
  (-direct-serialize [this]))

(defprotocol ISabStorable
  (-sab-tag [this])
  (-sab-encode [this slab-env])
  (-sab-dispose [this slab-env]))

(defprotocol ISabRetirable
  (-sab-retire-diff! [old-value new-value slab-env mode]))

(defprotocol IRetireDiff
  "An eve-deftype value that is a tree of eve blocks (an int-map, a sorted
   collection), walked by its own structure: what it holds that new does not
   (new nil, or another kind: everything it holds), as [blocks values] —
   values the [old new] serialized-value blocks among blocks (new -1 where
   new holds none at that place), for a caller that diffs a value that is a
   collection. The tree's own walk serves its transients: a transient frees
   what each of its writes superseded through it. Since 9.31 eve.atom retires
   every kind through eve.retire, not through this walk."
  (-retire-diff [old new]))

(defprotocol ISabpType
  (-sabp-type-key [this]))

(defn type-key-of
  "The type key of an eve-deftype value, as a string — its persistent
   form and its flat sister carry the same key (both implement
   ISabpType) — or nil when x is not an eve-deftype value at all."
  [x]
  ;; fast-satisfies?: a generated Foo? asks this of every value it is handed, and a miss (any value that is no
  ;; eve-deftype) walked the class's supers each time on the JVM
  (when (fp/fast-satisfies? ISabpType x)
    (-sabp-type-key x)))

(defprotocol IsEve
  "Marker protocol for EVE types. Used by serialization to detect
   EVE objects without walking into their internals."
  (-eve? [this]))

(defprotocol IBackingArray
  "Protocol for Eve array types that expose a raw platform array for slab storage.
   Used by value+sio->eve-bytes to serialize JvmHeapEveArray without circular deps."
  (-backing-array [this] "Return the underlying platform array (e.g. int[], double[]).")
  (-backing-subtype [this] "The element subtype code (1-9): a byte[] may hold :uint8 or :int8."))

(defonce ^:private flat-of-hook
  ;; eve.flat.freeze's registration (it loads the packer and the sisters,
  ;; which this namespace cannot require): (fn [v] -> v's flat form, or nil
  ;; when v is not a value it delivers by kind). One hook for the delivery
  ;; subsystem — not a per-type protocol.
  (atom nil))

(defn register-flat-of!
  "eve.flat.freeze's registration of the kind arm eve->clj consults: a fn
   answering a value's flat form (an eve-deftype or record, persistent or
   flat → its flat instance; a stored flat value → its content; an eve
   value array → its slots), or nil."
  [f]
  (reset! flat-of-hook f))

(defonce ^:private unwrap-hook
  ;; read-proxy layers' unwraps (eve.guard's validity proxies, eve.view's
  ;; views): the value under a proxy; anything else as is. eve->clj looks
  ;; through them.
  (atom []))

(defn register-unwrap!
  "A proxy layer's registration of its unwrap fn (x -> the value under x, or x)."
  [f]
  (swap! unwrap-hook conj f))

(defn- flat-of-fn
  "The registered kind arm, loading eve.flat.freeze (which registers it) the
   first time it is needed — as eve.atom's deref delivery resolves it: a
   caller of eve->clj need not have required the flat layer first. ::none
   caches its absence (a host with no flat layer)."
  []
  (let [h @flat-of-hook]
    (cond
      (fn? h) h
      (= ::none h) nil
      :else #?(:cljs nil
               :clje nil
               :default (do (try (requiring-resolve 'eve.flat.freeze/deliver-flat)
                                 (catch #?(:cljd dynamic :cljr Exception :lpy python/BaseException :glj go/any :jank jank.runtime.object_ref :default Throwable) _ nil))
                            (let [h @flat-of-hook]
                              (if (fn? h) h (do (reset! flat-of-hook ::none) nil))))))))

(defn- eve-slab-value?
  "x is persistent Eve data: slab-backed, rooted in a domain."
  [x]
  (and (satisfies? IEveRoot x)
       (satisfies? IEveSio x)
       (some? (-eve-sio x))))

(declare eve->clj)

(defn- seq->host [v]
  (doall (map eve->clj v)))

(defn eve->clj
  "Eve data as this host's Clojure data — eve->cljs's rule, on every host
   (the value model, docs/ds-in-eve/EVE-FIX-PLAN.md §0, V5). One function,
   no per-type protocol:
     nil, scalars, host byte arrays      → themselves
     an eve-deftype or record, a stored  → its flat form (the registered
     flat value, an eve value array        flat-of: flat instance, content,
                                           slots)
     eve maps, sets, vectors, lists,     → host collections, recursively
     sequences
     any other persistent Eve value      → refused (:eve/no-materialization):
                                           nothing slab-backed passes through
     anything else                       → itself.
   Inside an atom, storing the result back makes it eve data again; outside,
   it runs on flat data. Idempotent."
  [v]
  (cond
    (nil? v) nil
    (or (string? v) (number? v) (boolean? v)
        (keyword? v) (symbol? v) (char? v)) v
    :else
    (let [u (reduce (fn [x uf] (let [y (uf x)] (if (identical? y x) x (reduced y)))) v @unwrap-hook)]
      (if-not (identical? u v)
        (eve->clj u)
        (let [h (flat-of-fn)
              f (when h (h v))]
          (cond
            (some? f) f
            (map? v) (into {} (map (fn [[k x]] [(eve->clj k) (eve->clj x)])) v)
            (set? v) (into #{} (map eve->clj) v)
            (vector? v) (mapv eve->clj v)
            (list? v) (apply list (map eve->clj v))
            (sequential? v) (seq->host v)
            (eve-slab-value? v)
            (throw (ex-info "eve->clj: a persistent Eve value of no kind eve->clj knows"
                            {:type :eve/no-materialization
                             :value-type (str (type v))}))
            :else v))))))

(defprotocol IBulkAccess
  "Bulk typed-array access to EveArray data: the host's primitive array of
   the whole column in one shot (a JVM double[] or int[]), for aget/aset
   loops with no per-element overhead. A host whose arrays have none
   implements nothing."
  (-as-double-array [this] "The double array of all elements, or nil if not float64.")
  (-as-int-array    [this] "The int array of all elements, or nil if not int32."))

;;=============================================================================
;; Dynamic Vars
;;=============================================================================

;; *parent-atom* marks the dynamic extent of a swap's f (bound by
;; eve.atom/swap-attempt! on every host; read by the typed-array codec path,
;; eve.array has-slab? and the deftype constructor guard). *worker-id* is the
;; CLJS worker identity of the legacy SAB atom (eve.shared-atom). The former
;; *persistent?*, *worker-slot-idx* and *read-epoch* had no reader anywhere
;; and are gone (UNIFICATION-REPORT.md §5).
(def ^:dynamic *parent-atom* nil)
(def ^:dynamic *worker-id* nil)

;; *domain-sio* is the slab domain a constructor allocates in while a swap!'s
;; f runs: the attempt's logging sio over the atom's domain (bound by
;; eve.atom/swap-attempt!). Outside a swap it is nil: eve-deftype, dataset
;; and eve-array constructors then take the FLAT path and produce the type's
;; flat variant (Nov 14 2026 — the construction law's no-domain throw,
;; 2026-11-11, survives only for direct alloc/current-sio callers with no
;; flat form). Users of deftype, eve-array, dataset, tensor and the
;; collections never pass an sio; they construct inside a swap!, in an
;; explicit domain context, or — outside both — as flat values.
(def ^:dynamic *domain-sio* nil)

;;=============================================================================
;; HAMT Node Constants (same as eve.data)
;;=============================================================================

(def ^:const hamt-bitmap-node-type 3)
(def ^:const hamt-collision-node-type 4)
(def ^:const hamt-bitmap-node-columnar-type 6)
(def ^:const hamt-bitmap-node-header-size 10)
(def ^:const hamt-collision-node-header-size 8)

;;=============================================================================
;; Serialization Tags (same as eve.data)
;;=============================================================================

(def ^:const tag-js-array "js/Array")
(def ^:const tag-uint8-array "js/Uint8Array")
(def ^:const tag-int8-array "js/Int8Array")
(def ^:const tag-uint8-clamped-array "js/Uint8ClampedArray")
(def ^:const tag-int16-array "js/Int16Array")
(def ^:const tag-uint16-array "js/Uint16Array")
(def ^:const tag-int32-array "js/Int32Array")
(def ^:const tag-uint32-array "js/Uint32Array")
(def ^:const tag-float32-array "js/Float32Array")
(def ^:const tag-float64-array "js/Float64Array")
(def ^:const tag-bigint64-array "js/BigInt64Array")
(def ^:const tag-biguint64-array "js/BigUint64Array")
(def ^:const tag-regex "regex")
(def ^:const tag-uri "uri")
(def ^:const tag-char "char")
(def ^:const tag-bigint "bigint")
(def ^:const tag-record "record")

(def ^:const tag-sabp-linked-list-state "eve/SabpListStateV1")
(def ^:const tag-sabp-chunked-list-state "eve/SabpChunkedListV1")
(def ^:const tag-sabp-chunked-vec-state "eve/SabpChunkedVecV1")
(def ^:const tag-sabp-map-state "eve/SabpMapStateV1")
(def ^:const tag-sabp-set-state "eve/SabpSetStateV1")

(def ^:const direct-magic-0 0xEE)
(def ^:const direct-magic-1 0xDB)
(def ^:const direct-marker-map 0xED)
;; Linked list constants
(def ^:const offset-ll-node-value 0)
(def ^:const ll-node-next-offset-size-bytes size-of-int32)

;; Chunked list
(def ^:const chunked-list-chunk-size 32)

;; Max swap retries for CAS loops
(def ^:const max-swap-retries 1000)

;; Canonical byte order (eve/doc/unification/FORMAT-DECISIONS.md §2): a flat
;; set's elements and a flat map's entries are written in ascending order of
;; their encoded bytes, so two writers encode equal values to equal bytes.
(defn compare-bytes
  "Compare two byte sequences (Uint8Array on CLJS, byte[] on the JVM and bb,
   the host's byte array elsewhere) unsigned and lexicographically, a shorter
   prefix first. Negative, zero or positive like `compare`."
  [^bytes a ^bytes b]
  (let [la (alength a)
        lb (alength b)
        n  (if (< la lb) la lb)]
    (loop [i 0]
      (if (< i n)
        (let [x (bit-and (aget a i) 0xFF)
              y (bit-and (aget b i) 0xFF)]
          (if (== x y) (recur (inc i)) (- x y)))
        (- la lb)))))
