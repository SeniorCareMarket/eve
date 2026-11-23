(ns eve.data)

;;-----------------------------------------------------------------------------
;; Block Status Values
;;-----------------------------------------------------------------------------
(defonce ^:const STATUS_FREE 0)
(defonce ^:const STATUS_ALLOCATED 1)
;; STATUS_LOCKED_FOR_UPDATE is not used by the per-descriptor lock allocator,
;; but your find-unused-descriptor-for-shared-atom! uses it.
;; We need to decide if find-unused-descriptor-for-shared-atom! will also use per-descriptor lock or if it needs STATUS_LOCKED_FOR_UPDATE.
;; For now, let's assume find-unused-descriptor-for-shared-atom! (from old atom ns) still uses STATUS_LOCKED_FOR_UPDATE.
(defonce ^:const STATUS_LOCKED_FOR_UPDATE 2)
(defonce ^:const STATUS_EMBEDDED_ATOM_HEADER 3)
(defonce ^:const STATUS_ORPHANED 4)
(defonce ^:const STATUS_RETIRED 5) ;; Block retired at an epoch, awaiting GC when no readers
(defonce ^:const status-zeroed-unused -1)
(defonce ^:const ROOT_POINTER_NIL_SENTINEL -1) ; Used for "no block" or "nil value"

;;-----------------------------------------------------------------------------
;; SAB Header Layout
;;-----------------------------------------------------------------------------
(defonce ^:const OFFSET_SAB_TOTAL_SIZE 0)
(defonce ^:const OFFSET_INDEX_REGION_SIZE 4)
(defonce ^:const OFFSET_DATA_REGION_START 8)
(defonce ^:const OFFSET_MAX_BLOCK_DESCRIPTORS 12)

;; For AtomDomain's root map pointer. This points to a DATA_BLOCK's DESCRIPTOR_IDX
(defonce ^:const OFFSET_ATOM_ROOT_DATA_DESC_IDX 16)

;;-----------------------------------------------------------------------------
;; Epoch-Based GC Infrastructure
;;-----------------------------------------------------------------------------
;; Global epoch counter - incremented on each modification
(defonce ^:const OFFSET_GLOBAL_EPOCH 20)

;; Worker registry starts after the header fields
(defonce ^:const OFFSET_WORKER_REGISTRY_START 24)

;; Worker Registry Configuration
(defonce ^:const MAX_WORKERS 256) ;; Max concurrent workers
(defonce ^:const WORKER_SLOT_SIZE 24) ;; Bytes per worker slot
(defonce ^:const worker-registry-size (* MAX_WORKERS WORKER_SLOT_SIZE)) ;; 6KB total

;; Worker Slot Layout (24 bytes per worker)
;; Each slot tracks a worker's status, current read epoch, and heartbeat
(defonce ^:const OFFSET_WS_STATUS 0)          ;; i32: 0=inactive, 1=active, 2=stale
(defonce ^:const OFFSET_WS_CURRENT_EPOCH 4)   ;; i32: epoch being read (0 = not reading)
(defonce ^:const OFFSET_WS_HEARTBEAT_LO 8)    ;; i32: timestamp lower 32 bits
(defonce ^:const OFFSET_WS_HEARTBEAT_HI 12)   ;; i32: timestamp upper 32 bits
(defonce ^:const OFFSET_WS_WORKER_ID 16)      ;; i32: worker's unique ID
(defonce ^:const offset-ws-reserved 20)       ;; i32: reserved for future use

;; Worker Status Values
(defonce ^:const WORKER_STATUS_INACTIVE 0)
(defonce ^:const WORKER_STATUS_ACTIVE 1)
(defonce ^:const worker-status-stale 2)

;; Heartbeat timeout - workers not heard from in this time are considered stale
(defonce ^:const HEARTBEAT_TIMEOUT_MS 30000) ;; 30 seconds

;; Block descriptors start after the worker registry
(defonce ^:const OFFSET_BLOCK_DESCRIPTORS_ARRAY_START
  (+ OFFSET_WORKER_REGISTRY_START worker-registry-size))

;; Block Descriptor Columns (the column layout — one layout in eve and vs alike):
;; the descriptor table is a set of per-field dense columns, descriptor i's entry in
;; column c at word (c * max-block-descriptors + i) from the array start. Adding a
;; field adds a column, never widens a record. The field ids:
(defonce ^:const BD_COL_STATUS 0)
(defonce ^:const BD_COL_DATA_OFFSET 1) ; Stores data offset if STATUS_ALLOCATED or STATUS_FREE
(defonce ^:const BD_COL_DATA_LENGTH 2) ; Stores data length if STATUS_ALLOCATED
(defonce ^:const BD_COL_BLOCK_CAPACITY 3) ; Stores total capacity of the block's data region

;; IF STATUS_EMBEDDED_ATOM_HEADER, this field stores the DESCRIPTOR_IDX of the data block holding the atom's value.
;; IF STATUS_ALLOCATED or STATUS_FREE, this field is unused (or could be ROOT_POINTER_NIL_SENTINEL).
(defonce ^:const BD_COL_VALUE_DATA_DESC_IDX 4)

(defonce ^:const BD_COL_LOCK_OWNER 5) ; For per-descriptor locking
(defonce ^:const BD_COL_RETIRED_EPOCH 6) ; Epoch when block was retired (for GC)

(defonce ^:const SIZE_OF_INT32 4)
(defonce ^:const BD_NUM_COLUMNS 7) ; Status, DataOffset, DataLength, BlockCapacity, ValueDataDescIdx, LockOwner, RetiredEpoch

(defonce ^:const MINIMUM_USABLE_BLOCK_SIZE 1)
(defonce ^:const MAX_SWAP_RETRIES 1000) ; You set this

;;-----------------------------------------------------------------------------
;; WASM SIMD Scratch Area
;;-----------------------------------------------------------------------------
;; Reserved area at start of data region for WASM SIMD operations.
;; Used for copying serialized keys before WASM lookup.
(defonce ^:const WASM_SCRATCH_SIZE 4096)  ;; 4KB per worker, supports keys up to 4KB

;;-----------------------------------------------------------------------------
;; Custom Tags for JS Types
;;-----------------------------------------------------------------------------
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
;; Tags for other types handled as structs
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

;; --- Constants for SAB Chunked List ---
(def ^:const chunked-list-chunk-size 32)
;; Chunk layout: [num_elements:u32][elem_0_len:u32][elem_0_bytes...][elem_1_len:u32][elem_1_bytes...]...
;; Directory layout: [num_chunks:u32][chunk_0_offset:i32][chunk_1_offset:i32]...

;; --- Constants for SAB HAMT Map Nodes ---
(def ^:const hamt-bitmap-node-type 3)
(def ^:const hamt-collision-node-type 4)
(def ^:const hamt-bitmap-node-columnar-type 6) ;; columnar K/V separation variant
(def ^:const hamt-bitmap-node-header-size 10) ;; type:u8 pad:u8 data_bitmap:u32 node_bitmap:u32
(def ^:const hamt-collision-node-header-size 8) ;; type:u8 count:u8 pad:u16 hash:i32

;; Direct serialization markers (inside the magic prefix envelope)
(def ^:const DIRECT_MAGIC_0 0xEE)
(def ^:const DIRECT_MAGIC_1 0xDB)
(def ^:const direct-marker-map 0xED)
(defprotocol IDirectSerialize
  (-direct-serialize [this]))

(defprotocol ISabStorable
  "Protocol for types that can be stored in SAB data structures.
   Replaces Fressian handler registration (raw/reg!) with protocol dispatch."
  (-sab-tag [this]
    "Return a keyword tag identifying this type for deserialization.")
  (-sab-encode [this s-atom-env]
    "Encode this value into a Uint8Array for storage in SAB.
     May allocate SAB blocks for nested structures.
     Returns bytes using the fast-path format.")
  (-sab-dispose [this s-atom-env]
    "Free any SAB resources owned by this value.
     Called during tree-walk freeing. No-op for types without SAB allocations."))

(defprotocol ISabRetirable
  "Protocol for SAB-backed types that support tree-diff retirement.
   Used by atom swap to retire replaced nodes after a successful CAS."
  (-sab-retire-diff! [old-value new-value s-atom-env mode]
    "Retire nodes in old-value's tree that are not shared with new-value's tree.
     mode: :retire (epoch-based, multi-worker) or :free (immediate).
     new-value may be nil or a different type, in which case dispose the entire old tree."))

;; --- Constants for SAB Linked List Nodes ---
(def ^:const offset-ll-node-value 0)
(def ^:const ll-node-next-offset-size-bytes SIZE_OF_INT32)

(defprotocol ISabpType
  (-sabp-type-key [this] "Returns the string key used for registering this SAB-P type."))

(def ^:dynamic *persistent?* true)

(def ^:dynamic *parent-atom* nil)

(def ^:dynamic *worker-id* nil)

;; Worker slot index in the registry (set when worker registers)
(def ^:dynamic *worker-slot-idx* nil)

;; Current read epoch (set during begin-read!, cleared on end-read!)
(def ^:dynamic *read-epoch* nil)

;; --- Optimization flags ---
(def ^:dynamic *use-flat-hashtable* false)
(def ^:dynamic *parallel-reduce* false)

(defonce ^:const READER_MAP_NUM_COUNTERS 65536) ; Number of 32-bit integer counters
(defonce ^:const READER_MAP_TOTAL_SIZE_BYTES (* READER_MAP_NUM_COUNTERS SIZE_OF_INT32))

(defonce ^:const OFFSET_READER_MAP_START OFFSET_BLOCK_DESCRIPTORS_ARRAY_START) ; Placeholder, will be adjusted in atom-domain

(defonce ^:const READER_MAP_SAB_SIZE_BYTES (* READER_MAP_NUM_COUNTERS SIZE_OF_INT32))
