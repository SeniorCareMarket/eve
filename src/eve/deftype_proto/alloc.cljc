(ns eve.deftype-proto.alloc
  "Slab-based allocator for EVE — shared CLJ/CLJS implementation.

   ARCHITECTURE:
   ═══════════════════════════════════════════════════════════════

   Multiple SharedArrayBuffers (CLJS) or mmap files (JVM) — one per size
   class — each with:
   - Fixed-size blocks (no coalescing)
   - Bitmap free-tracking (SIMD-accelerated on CLJS, IMemRegion CAS on JVM)
   - Independent growth via memory.grow (CLJS) or file truncation (JVM)

   ADDRESSING:
   ═══════════════════════════════════════════════════════════════

   All node offsets are 'slab-qualified': a 32-bit value encoding both the
   slab class AND the block index within that slab.

   Format: [class_idx:3 bits | block_idx:29 bits]
   - class_idx 0-5: slab classes (32B..1024B)
   - class_idx 6: overflow (old-style allocator)
   - class_idx 7: reserved/sentinel
   - block_idx: up to 536M blocks per slab

   ISLABIO:
   ═══════════════════════════════════════════════════════════════

   ISlabIO is the new platform-neutral protocol for slab block I/O.
   - CLJS: CljsSlabIO delegates to DataView-based alloc fns (hot path)
   - JVM:  JvmSlabCtx holds one IMemRegion per slab file and accesses
           block data via direct byte reads"
  (:refer-clojure :exclude [aget aset alength byte-array short-array int-array float-array
                            double-array object-array into-array unchecked-byte
                            unchecked-int unsigned-bit-shift-right long double locking bytes
                            shorts ints floats doubles objects defdelay])
  (:require
   [eve.host :refer [aget aset alength byte-array short-array int-array float-array
                     double-array object-array into-array unchecked-byte unchecked-int
                     unsigned-bit-shift-right long double locking bytes shorts ints floats
                     doubles objects defdelay field]]
   [eve.slab-io :as slab]
   [eve.deftype-proto.coalesc :as coalesc]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.region :as region]
   [eve.mem :as mem]
   [eve.codec :as codec]
   [eve.encoding :as enc]))

(defn- region-opts
  "The open-mmap-region opts that reopen a file the way r was opened — a
   slab class grown by another process is re-mapped with the same kind."
  [r]
  (if (region/lustre-region? r) {:lustre? true} nil))

;;=============================================================================
;; Slab-Qualified Offset Encoding  (shared)
;;=============================================================================

(def ^:const CLASS_BITS 3)
(def ^:const BLOCK_IDX_BITS 29)
(def ^:const BLOCK_IDX_MASK (dec (bit-shift-left 1 BLOCK_IDX_BITS)))
(def ^:const OVERFLOW_CLASS_IDX 6)
(def ^:const SENTINEL_CLASS_IDX 7)

;; NIL sentinel: all bits set = class 7, block 0x1FFFFFFF
(def ^:const NIL_OFFSET -1)

(defn encode-slab-offset
  "Encode a slab class index and block index into a slab-qualified offset."
  [class-idx block-idx]
  (bit-or (bit-shift-left class-idx BLOCK_IDX_BITS)
          (bit-and block-idx BLOCK_IDX_MASK)))

(defn decode-class-idx
  "Extract the slab class index from a slab-qualified offset.
   Treats the offset as an unsigned 32-bit integer (handles sign-extended i32 reads)."
  [slab-offset]
  (unsigned-bit-shift-right (bit-and (long slab-offset) 0xFFFFFFFF) BLOCK_IDX_BITS))

(defn decode-block-idx
  "Extract the block index from a slab-qualified offset.
   Treats the offset as an unsigned 32-bit integer (handles sign-extended i32 reads)."
  [slab-offset]
  (bit-and (bit-and (long slab-offset) 0xFFFFFFFF) BLOCK_IDX_MASK))

;;=============================================================================
;; ISlabIO Protocol  (shared)
;;=============================================================================
;; Platform-neutral interface for slab block I/O.
;; CLJS implementations use DataView; JVM uses IMemRegion byte reads.
;; HAMT algorithms (map.cljc, vec.cljc, etc.) receive an ISlabIO ctx
;; instead of reaching for module-level DataView state.

(defprotocol ISlabIO
  "Abstraction over slab block I/O.
   slab-offset is always a slab-qualified offset [class:3 | block:29].
   field-off   is a byte offset within the block (relative to block start)."

  (-sio-read-u8   [ctx slab-offset field-off]
    "Read one unsigned byte from slab-offset+field-off.")

  (-sio-write-u8!  [ctx slab-offset field-off val]
    "Write one byte to slab-offset+field-off.")

  (-sio-read-u16  [ctx slab-offset field-off]
    "Read a little-endian u16 from slab-offset+field-off.")

  (-sio-write-u16! [ctx slab-offset field-off val]
    "Write a little-endian u16 to slab-offset+field-off.")

  (-sio-read-i32  [ctx slab-offset field-off]
    "Read a little-endian i32 from slab-offset+field-off.")

  (-sio-write-i32! [ctx slab-offset field-off val]
    "Write a little-endian i32 to slab-offset+field-off.")

  (-sio-cas-i32! [ctx slab-offset field-off expected desired]
    "Atomic compare-and-exchange on the i32 at slab-offset+field-off.
     Returns the *old* value — success iff (= returned expected).
     Used by eve-deftype (cas! field expected new) on ^:volatile-mutable fields.")

  (-sio-read-bytes [ctx slab-offset field-off len]
    "Read len bytes starting at slab-offset+field-off.
     Returns a platform byte array (Uint8Array on CLJS, byte[] on JVM).")

  (-sio-write-bytes! [ctx slab-offset field-off src]
    "Write src bytes to slab-offset+field-off.")

  (-sio-alloc!    [ctx size-bytes]
    "Allocate a block of at least size-bytes. Returns slab-qualified offset.
     Throws on OOM.")

  (-sio-free!     [ctx slab-offset]
    "Free a block. Returns true if freed, false on double-free.")

  (-sio-copy-block! [ctx dst-slab-offset dst-field-off src-slab-offset src-field-off len]
    "Bulk copy len bytes from src to dst within slab memory.
     Both offsets are slab-qualified; field-offs are byte offsets within block."))

(defprotocol ISlabRegion
  "Where a slab block's field lives, for the region's own operations that
   ISlabIO does not carry (wait/notify on an i32)."
  (-sio-mem-region [ctx slab-offset field-off]
    "[region byte-off]: the IMemRegion holding slab-offset+field-off and the
     field's byte offset within it."))

;; The header-tag reader for eve.mem (which cannot require alloc — a cycle):
;; byte 0 of a slab block is its type-id. Registered on every host, as a named
;; fn: a fn that a top-level form makes is interpreted on clojerl (its Core
;; Erlang evaluator), a defn's compiled — and every read of a value calls this.
(defn read-header-tag
  "The type-id byte at the start of the block at slab-off."
  [sio slab-off]
  (slab/read-u8 sio slab-off 0))
(ser/register-header-tag-reader! read-header-tag)

;;=============================================================================
;; JvmSlabCtx — a slab domain over IMemRegions (every host)
;;=============================================================================

   (do
     ;; The slab context over a set of IMemRegions — every host's (its regions
     ;; are each host's adapter's; this code is not). A domain is one of these.

     (declare ctx-alloc! ctx-free!)

     ;; Every ISlabIO operation takes an explicit sio (JvmSlabCtx). Constructors
     ;; called without one allocate in current-sio: the running swap!'s domain
     ;; (d/*domain-sio*). Outside a swap/domain context current-sio THROWS
     ;; (construction law, 2026-11-11) — the 2026-10-16 automatic process-heap
     ;; fallback is abolished; only Eve plumbing uses plumbing-sio.

     ;; There is no thread-local allocation log either: a swap's allocations
     ;; are recorded by the per-swap LoggingSlabIO wrapper (below), and the
     ;; nodes a path-copy replaces are found by the retire-time structural
     ;; diff (eve.atom), on every host alike.

     (deftype JvmSlabCtx
       [^objects regions         ;; IMemRegion[6+] — one per slab class (data files)
        ^objects bitmap-regions  ;; IMemRegion[6+] — one per slab class (bitmap files)
        ^ints    data-offsets    ;; byte offset of data region per class
        ^ints    bitmap-offsets  ;; byte offset of bitmap per class (0 for split files)
        ^ints    total-blocks    ;; total blocks per class
        ^ints    block-sizes     ;; block size per class [32 64 128 256 512 1024]
        ^objects file-paths      ;; String[6+] — data file paths for lazy growth
        ^objects bitmap-paths    ;; String[6+] — bitmap file paths for lazy growth
        ^ints    max-blocks      ;; max blocks per class (growth cap)
        ^objects desc-regions    ;; IMemRegion[6+] — each bitmap class's descriptor COUNT column (data.cljc: the slab's
                                 ;; own region for a heap / SAB slab, its .slabN.desc-count file for an mmap one; nil for class 6)
        ^objects desc-flags-regions ;; IMemRegion[6+] — each bitmap class's descriptor FLAGS column (the slab's own
                                 ;; region for a heap / SAB slab — the column sits right after the COUNT column —
                                 ;; its .slabN.desc-flags file for an mmap one; nil for class 6)
        ^objects desc-pointer-regions ;; IMemRegion[6+] — each bitmap class's descriptor POINTER column (the
                                 ;; digest-cache side-block pointer, coalesc/DESC_DIGEST_PTR; the slab's own
                                 ;; region for a heap / SAB slab — the column sits right after the FLAGS
                                 ;; column — its .slabN.desc-pointer file for an mmap one; nil for class 6)
        ^ints    desc-offsets    ;; byte offset of the COUNT column in desc-regions' region (block i's count entry
                                 ;; at + i * 4; its flags entry at the same offset in desc-flags-regions, + 4 * total
                                 ;; instead when the two regions are one — a heap / SAB slab; its pointer entry
                                 ;; likewise in desc-pointer-regions, + 8 * total when the regions are one)
        ^long    mem-budget      ;; the domain's byte budget (creation parameter :mem-budget); 0 = no budget.
                                 ;; Growth that would push the domain's total region bytes past it is
                                 ;; refused, and the allocation names the budget — never the JVM's OOM.
        ^long    segment-bytes]  ;; the domain's region mapping window (creation parameter
                                 ;; :segment-bytes); 0 = the region adapter's default.  Presentation,
                                 ;; not format: growth/refresh reopens carry it and reuse each
                                 ;; file's old region's unchanged windows (the delta maps).

       ISlabIO

       (-sio-read-u8 [_ slab-offset field-off]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)
               b         (mem/-read-bytes region (+ base field-off) 1)]
           (bit-and (aget ^bytes b 0) 0xFF)))

       (-sio-write-u8! [_ slab-offset field-off val]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)]
           (mem/-write-bytes! region (+ base field-off) (enc/bytes-of [(bit-and val 0xFF)]))))

       (-sio-read-u16 [_ slab-offset field-off]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)
               b         (mem/-read-bytes region (+ base field-off) 2)]
           ;; Little-endian u16
           (bit-or (bit-and (aget ^bytes b 0) 0xFF)
                   (bit-shift-left (bit-and (aget ^bytes b 1) 0xFF) 8))))

       (-sio-write-u16! [_ slab-offset field-off val]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)]
           (mem/-write-bytes! region (+ base field-off)
                              (enc/bytes-of [(bit-and val 0xFF)
                                             (bit-and (unsigned-bit-shift-right val 8) 0xFF)]))))

       (-sio-read-i32 [_ slab-offset field-off]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)
               b         (mem/-read-bytes region (+ base field-off) 4)
               u         (bit-or (bit-and (aget ^bytes b 0) 0xFF)
                                 (bit-shift-left (bit-and (aget ^bytes b 1) 0xFF) 8)
                                 (bit-shift-left (bit-and (aget ^bytes b 2) 0xFF) 16)
                                 (bit-shift-left (bit-and (aget ^bytes b 3) 0xFF) 24))]
           ;; Little-endian i32 through the PLAIN byte path (the u16
           ;; pair's shape), never the region's atomic -load-i32: slab
           ;; payload words are packed at arbitrary offsets (HAMT node
           ;; write-kv! lays out [klen:i32][k][vlen:i32][v], so vlen
           ;; lands at 4+klen), and IMemRegion's contract requires
           ;; 4-byte alignment for its atomic ops -- arm64 faults on a
           ;; misaligned atomic (F-REGION-ATOMIC-ALIGN). Payload words
           ;; are not atomic fields: a node is immutable once published
           ;; and publication is ordered by the root CAS, so a plain
           ;; read is the correct semantics. Real atomic words (root
           ;; fields, doorbells, CAS) go through mem/ directly, aligned.
           (if (>= u 2147483648) (- u 4294967296) u)))

       (-sio-write-i32! [_ slab-offset field-off val]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)]
           ;; Plain bytes, as -sio-write-u16!: see -sio-read-i32 --
           ;; payload words are packed, never atomic fields. The byte
           ;; layout is unchanged, so seals stay byte-identical.
           (mem/-write-bytes! region (+ base field-off)
                              (enc/bytes-of [(bit-and val 0xFF)
                                             (bit-and (unsigned-bit-shift-right val 8) 0xFF)
                                             (bit-and (unsigned-bit-shift-right val 16) 0xFF)
                                             (bit-and (unsigned-bit-shift-right val 24) 0xFF)]))))

       (-sio-cas-i32! [_ slab-offset field-off expected desired]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)]
           (mem/-cas-i32! region (+ base field-off) expected desired)))

       (-sio-read-bytes [_ slab-offset field-off len]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)]
           (mem/-read-bytes region (+ base field-off) len)))

       (-sio-write-bytes! [_ slab-offset field-off src]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))
               region    (aget regions class-idx)]
           (mem/-write-bytes! region (+ base field-off) src)))

       (-sio-alloc! [ctx size-bytes] (ctx-alloc! ctx size-bytes))

       (-sio-copy-block! [_ dst-slab-offset dst-field-off src-slab-offset src-field-off len]
         (let [src-ci  (decode-class-idx src-slab-offset)
               src-bi  (decode-block-idx src-slab-offset)
               src-base (+ (aget data-offsets src-ci) (* src-bi (aget block-sizes src-ci)))
               src-rgn  (aget regions src-ci)
               dst-ci  (decode-class-idx dst-slab-offset)
               dst-bi  (decode-block-idx dst-slab-offset)
               dst-base (+ (aget data-offsets dst-ci) (* dst-bi (aget block-sizes dst-ci)))
               dst-rgn  (aget regions dst-ci)]
           (mem/copy-region! src-rgn (+ src-base (long src-field-off))
                             dst-rgn (+ dst-base (long dst-field-off))
                             len)))

       (-sio-free! [ctx slab-offset] (ctx-free! ctx slab-offset))

       ISlabRegion
       (-sio-mem-region [_ slab-offset field-off]
         (let [class-idx (decode-class-idx slab-offset)
               block-idx (decode-block-idx slab-offset)
               base      (+ (aget data-offsets class-idx)
                            (* block-idx (aget block-sizes class-idx)))]
           [(aget regions class-idx) (+ base field-off)])))

     ;; The allocator: JvmSlabCtx's -sio-alloc! / -sio-free!, and what a host's
     ;; slab-io calls for a domain directly (eve.slab-io alloc! / free!). Its
     ;; header words and bitmaps are read and written through eve.slab-io's
     ;; region names.

     (defn- ctx-domain-bytes
       "The domain's total region bytes: every distinct region the ctx holds,
        counted once (a heap class's data, bitmap and descriptor columns are
        one region; an mmap class's are separate files). Computed only at
        growth time, where doubling makes the walk's cost noise. The
        distinctness walk is by identity over a plain vector — this section
        is shared by every host, so no java.util identity set here (it broke
        the basilisp load of this file; the walk is over <=35 regions and
        runs only at growth time, where its cost is noise by the same
        argument as the walk itself)."
       [^JvmSlabCtx ctx]
       (let [count-region (fn [[seen total] r]
                            (if (some #(identical? % r) seen)
                              [seen total]
                              [(conj seen r)
                               (+ total (long (mem/-byte-length r)))]))
             count-array  (fn [acc ^objects rs]
                            (reduce (fn [acc i]
                                      (if-let [r (aget rs i)]
                                        (count-region acc r)
                                        acc))
                                    acc (range (alength rs))))]
         (second (reduce count-array [[] 0]
                         [(field ctx regions)
                          (field ctx bitmap-regions)
                          (field ctx desc-regions)
                          (field ctx desc-flags-regions)
                          (field ctx desc-pointer-regions)]))))

     (defn- budget-allows?
       "True when growing the domain by add-bytes stays within its :mem-budget
        (or it has none). The budget caps the domain's total region bytes:
        growth past it is refused at the growth points, so a budgeted domain
        spills to its caller's named error instead of growing into the host's
        hard limit (a JVM OOM, a workerd kill)."
       [^JvmSlabCtx ctx add-bytes]
       (let [budget (long (field ctx mem-budget))]
         (or (not (pos? budget))
             (<= (+ (ctx-domain-bytes ctx) (long add-bytes)) budget))))

     (defn- grow-coalesc-once!
       "One growth step of ctx's class-6 (coalescing) region: double its data
        size (coalesc/grow-coalesc-region!), then make the ctx's region
        physically that big — an mmap region re-opened at the new size, a heap
        region replaced by a larger copy. Under the regions lock; true when
        the region grew, false when it is at its max or absent."
       [^JvmSlabCtx ctx]
       (locking (field ctx regions)
         (let [^objects regions (field ctx regions)
               region           (aget regions OVERFLOW_CLASS_IDX)]
           (when region
             (let [budget   (long (field ctx mem-budget))
                   ;; under a :mem-budget, class 6 may grow only into the
                   ;; budget the rest of the domain leaves it (the region's
                   ;; total bytes are data-off + data-size); grow-coalesc-
                   ;; region! returns nil at its max, so a capped max is a
                   ;; refused growth, never a partial one
                   max-data (if (pos? budget)
                              (let [data-off (long (aget ^ints (field ctx data-offsets) OVERFLOW_CLASS_IDX))
                                    others   (- (ctx-domain-bytes ctx) (long (mem/-byte-length region)))]
                                (min coalesc/DEFAULT_DATA_SIZE
                                     (max 0 (- budget others data-off))))
                              coalesc/DEFAULT_DATA_SIZE)
                   result (coalesc/grow-coalesc-region! region max-data)]
               (when result
                 (let [data-off (long (aget ^ints (field ctx data-offsets) OVERFLOW_CLASS_IDX))
                       fpath    (aget ^objects (field ctx file-paths) OVERFLOW_CLASS_IDX)
                       new-r    (if fpath
                                  (region/open-mmap-region fpath (+ data-off result)
                                                           (cond-> (region-opts region)
                                                             (pos? (long (field ctx segment-bytes)))
                                                             (assoc :segment-bytes (long (field ctx segment-bytes)))
                                                             region
                                                             (assoc :reuse-from region)))
                                  ;; Heap-backed: create larger region and copy
                                  (let [old-r   (aget regions OVERFLOW_CLASS_IDX)
                                        old-sz  (mem/-byte-length old-r)
                                        new-sz  (+ data-off result)
                                        new-rgn (region/make-heap-region new-sz)]
                                    (mem/copy-region! old-r 0 new-rgn 0 old-sz)
                                    new-rgn))]
                   (aset regions OVERFLOW_CLASS_IDX new-r)
                   (slab/regions-changed! ctx)
                   true)))))))

     (defn- coalesc-alloc-growing!
       "A class-6 block of size-bytes in ctx, growing the region until the
        block fits (20 growth steps at most): its slab-qualified offset.
        The one class-6 allocation discipline: direct large blocks and
        bitmap-class spills alike come through here, so no path reaches
        class 6 without the growth the region supports."
       [^JvmSlabCtx ctx size-bytes]
       (let [^objects regions (field ctx regions)]
         (if (aget regions OVERFLOW_CLASS_IDX)
           (or (when-let [byte-off (coalesc/try-coalesc-alloc!
                                    (aget regions OVERFLOW_CLASS_IDX) size-bytes)]
                 (encode-slab-offset OVERFLOW_CLASS_IDX byte-off))
               (loop [attempts 0]
                 ;; descriptor exhaustion is not a space failure:
                 ;; growing the data region cannot mint descriptors
                 ;; (and itself consumes one) — stop and throw the
                 ;; named error instead of doubling toward it.
                 (if (coalesc/descriptors-exhausted? (aget regions OVERFLOW_CLASS_IDX))
                   (coalesc/coalesc-alloc! (aget regions OVERFLOW_CLASS_IDX) size-bytes)
                   (let [grew? (grow-coalesc-once! ctx)]
                     (if-let [byte-off (coalesc/try-coalesc-alloc!
                                        (aget regions OVERFLOW_CLASS_IDX) size-bytes)]
                       (encode-slab-offset OVERFLOW_CLASS_IDX byte-off)
                       (if (and grew? (< attempts 20))
                         (recur (inc attempts))
                         ;; when the domain's :mem-budget is what refused
                         ;; the growth, the error names it — a budgeted
                         ;; domain's ceiling is the caller's parameter,
                         ;; never the host's hard out-of-memory
                         (let [budget (long (field ctx mem-budget))]
                           (throw (if (and (pos? budget)
                                           (not (budget-allows? ctx size-bytes)))
                                    (ex-info (str "coalesc-alloc!: domain :mem-budget of " budget
                                                  " bytes reached (domain holds " (ctx-domain-bytes ctx) " bytes)")
                                             {:mem-budget budget :size size-bytes})
                                    (ex-info "coalesc-alloc!: out of memory after growth"
                                             {:size size-bytes}))))))))))
           (throw (ex-info "JvmSlabCtx: no .slab6 region for overflow alloc"
                           {:size-bytes size-bytes})))))

     (defn- grow-heap-class!
       "Grow heap bitmap class ci of ctx by replacement: a new heap region at
        double the blocks (max-blocks caps it when positive), header, bitmap,
        descriptor columns and data copied at the new layout's offsets —
        block i stays block i, so no live reference moves. The class-6 heap
        arm's shape (a larger region and a copy), for the bitmap classes.
        A doubling past d/MAX_HEAP_CLASS_BYTES is refused (false: the caller
        spills to the next class), never an int overflow in make-heap-region.
        Under the caller's regions lock; true when grown."
       [^JvmSlabCtx ctx ci]
       (let [^objects regions (field ctx regions)
             old-r              (aget regions ci)
             blk-size           (long (aget ^ints (field ctx block-sizes) ci))
             old-total          (long (aget ^ints (field ctx total-blocks) ci))
             max-blks           (long (aget ^ints (field ctx max-blocks) ci))
             new-total          (d/heap-class-growth-total blk-size old-total max-blks)
             layout             (when new-total
                                  (d/slab-layout blk-size (* (long new-total) blk-size)))]
         (when (and old-r new-total
                    ;; a :mem-budget refuses the growth whose replacement
                    ;; region would push the domain past it
                    (budget-allows? ctx (- (long (:total-bytes layout))
                                           (long (mem/-byte-length old-r)))))
           (let [new-r      (region/make-heap-region (:total-bytes layout))
                 old-bm-off (long (aget ^ints (field ctx bitmap-offsets) ci))
                 old-desc   (long (aget ^ints (field ctx desc-offsets) ci))
                 old-data   (long (aget ^ints (field ctx data-offsets) ci))
                 new-bm-off (long (:bitmap-offset layout))
                 new-desc   (long (:desc-offset layout))
                 new-data   (long (:data-offset layout))]
             ;; header: the old header's words, at the new layout's geometry
             (mem/-store-i32! new-r d/SLAB_HDR_MAGIC
                              (mem/-load-i32 old-r d/SLAB_HDR_MAGIC))
             (mem/-store-i32! new-r d/SLAB_HDR_BLOCK_SIZE (int blk-size))
             (mem/-store-i32! new-r d/SLAB_HDR_TOTAL_BLOCKS (int new-total))
             (mem/-store-i32! new-r d/SLAB_HDR_FREE_COUNT
                              (int (+ (mem/-load-i32 old-r d/SLAB_HDR_FREE_COUNT)
                                      (- new-total old-total))))
             (mem/-store-i32! new-r d/SLAB_HDR_ALLOC_CURSOR
                              (mem/-load-i32 old-r d/SLAB_HDR_ALLOC_CURSOR))
             (mem/-store-i32! new-r d/SLAB_HDR_CLASS_IDX (int ci))
             (mem/-store-i32! new-r d/SLAB_HDR_BITMAP_OFFSET (int new-bm-off))
             (mem/-store-i32! new-r d/SLAB_HDR_DATA_OFFSET (int new-data))
             ;; bitmap: the old bitmap's bytes; past them the new region is
             ;; zero-filled, and zero is a free bit
             (mem/copy-region! old-r old-bm-off new-r new-bm-off
                               (d/bitmap-byte-size old-total))
             ;; descriptor columns COUNT, FLAGS, POINTER: column c sits at
             ;; desc + c * 4 * total in each layout
             (dotimes [c 3]
               (mem/copy-region! old-r (+ old-desc (* c 4 old-total))
                                 new-r (+ new-desc (* c 4 new-total))
                                 (* 4 old-total)))
             ;; data: block i at data + i * blk-size in each layout
             (mem/copy-region! old-r old-data new-r new-data (* old-total blk-size))
             (aset regions ci new-r)
             (aset ^objects (field ctx bitmap-regions) ci new-r)
             (aset ^objects (field ctx desc-regions) ci new-r)
             (aset ^objects (field ctx desc-flags-regions) ci new-r)
             (aset ^objects (field ctx desc-pointer-regions) ci new-r)
             (aset ^ints (field ctx data-offsets) ci (int new-data))
             (aset ^ints (field ctx bitmap-offsets) ci (int new-bm-off))
             (aset ^ints (field ctx desc-offsets) ci (int new-desc))
             (aset ^ints (field ctx total-blocks) ci (int new-total))
             (slab/regions-changed! ctx)
             true))))

     (defn ctx-alloc!
       "A block of at least size-bytes in ctx's slabs: its slab-qualified
        offset. Classes 0-5 are bitmap slabs that grow (a file-backed class
        by extension, a heap class by replacement), then spill to the next
        class; larger blocks, and a spill past class 5, go to the coalescing
        class 6, which grows the same way."
       [^JvmSlabCtx ctx size-bytes]
       (let [^objects regions        (field ctx regions)
             ^objects bitmap-regions (field ctx bitmap-regions)
             ^ints    data-offsets   (field ctx data-offsets)
             ^ints    bitmap-offsets (field ctx bitmap-offsets)
             ^ints    total-blocks   (field ctx total-blocks)
             ^ints    block-sizes    (field ctx block-sizes)
             ^objects file-paths     (field ctx file-paths)
             ^objects bitmap-paths   (field ctx bitmap-paths)
             ^objects desc-regions   (field ctx desc-regions)
             ^objects desc-flags-regions (field ctx desc-flags-regions)
             ^objects desc-pointer-regions (field ctx desc-pointer-regions)
             class-idx (d/size->class-idx size-bytes)]
         (if (== class-idx -1)
           ;; Coalescing allocator (class 6) with lazy growth
           (coalesc-alloc-growing! ctx size-bytes)
           ;; Bitmap slab allocator (class 0-5) with lazy growth
           (letfn [(try-alloc [ci]
                     ;; A heap class (no file) grows by replacement (grow!
                     ;; below), not by this scan: once its free count
                     ;; says it is full, every allocation that spills from it
                     ;; would first scan its whole bitmap twice — nanoseconds
                     ;; a word on the JVM, a foreign call a word on jolt and
                     ;; basilisp (minutes over a large build). Skip it. A
                     ;; file-backed class keeps the scan: another process may
                     ;; have freed into it, and growing follows.
                     (when-not (and (nil? (aget file-paths ci))
                                    (<= (slab/region-load-i32 (aget regions ci) d/SLAB_HDR_FREE_COUNT) 0))
                     (let [;; Snapshot order: a remap (grow!/refresh!) publishes the
                            ;; new region BEFORE the new total, and totals only grow —
                            ;; so read total first, then the region, then total again.
                            ;; Equal totals certify the pair; a changed total means a
                            ;; remap interleaved, and the triple is re-read under the
                            ;; monitor the publishers themselves hold. (The previous
                            ;; order — region first, total third, unchecked — could
                            ;; pair the OLD bitmap with the NEW total and scan past
                            ;; its buffer: IndexOutOfBounds in imr-bitmap-find-free.)
                       total0 (aget total-blocks ci)
                       bm-rgn0 (aget bitmap-regions ci)
                       bm-off0 (aget bitmap-offsets ci)
                       [bm-rgn bm-off total]
                       (if (= total0 (aget total-blocks ci))
                         [bm-rgn0 bm-off0 total0]
                         (locking regions
                           [(aget bitmap-regions ci)
                            (aget bitmap-offsets ci)
                            (aget total-blocks ci)]))
                       ;; Lustre bitmap regions: bulk-read to avoid per-word fcntl lock
                       lustre? (region/lustre-region? bm-rgn)
                       cursor (slab/region-load-i32 (aget regions ci) d/SLAB_HDR_ALLOC_CURSOR)]
                       (loop [start-bit (mod cursor total) wrapped? false]
                         (let [candidate (if lustre?
                                           (mem/imr-bitmap-find-free-bulk bm-rgn bm-off total start-bit)
                                           (slab/bitmap-find-free bm-rgn bm-off total start-bit))]
                           (cond
                             (not= candidate -1)
                             (if (slab/bitmap-alloc-cas! bm-rgn bm-off candidate)
                               (let [slab-off (encode-slab-offset ci candidate)]
                                 (slab/region-store-i32! (aget regions ci) d/SLAB_HDR_ALLOC_CURSOR
                                                         (mod (inc candidate) total))
                                 (slab/region-sub-i32! (aget regions ci) d/SLAB_HDR_FREE_COUNT 1)
                                 slab-off)
                               (recur (inc candidate) wrapped?))

                             (and (not wrapped?) (pos? start-bit))
                             (recur 0 true)

                             :else nil))))))
                   (remap-jvm! [ci n-blocks]
                     (let [blk-size   (long (aget block-sizes ci))
                           path       (aget file-paths ci)
                           bm-path    (aget bitmap-paths ci)
                           data-bytes (+ (long d/SLAB_HEADER_SIZE) (* n-blocks blk-size))
                           bm-bytes   (d/bitmap-byte-size n-blocks)
                           ;; re-map the way the class was opened (Lustre or
                           ;; not), at the domain's segment window, each
                           ;; file reusing its own old region's unchanged
                           ;; windows — a growth re-map maps only the delta
                           seg        (long (field ctx segment-bytes))
                           opts       (cond-> (region-opts (aget regions ci))
                                        (pos? seg) (assoc :segment-bytes seg))
                           new-region (region/open-mmap-region path data-bytes (assoc opts :reuse-from (aget regions ci)))
                           new-bm     (region/open-mmap-region bm-path bm-bytes (assoc opts :reuse-from (aget bitmap-regions ci)))
                           ;; the descriptor columns grow with their class: each column its own file,
                           ;; re-mapped bigger — an entry never moves, so no migration and no window
                           ;; where a reader sees a half-moved table
                           new-desc   (region/open-mmap-region (str path ".desc-count") (d/desc-column-byte-size n-blocks) (assoc opts :reuse-from (aget desc-regions ci)))
                           new-desc-f (region/open-mmap-region (str path ".desc-flags") (d/desc-column-byte-size n-blocks) (assoc opts :reuse-from (aget desc-flags-regions ci)))
                           new-desc-p (region/open-mmap-region (str path ".desc-pointer") (d/desc-column-byte-size n-blocks) (assoc opts :reuse-from (aget desc-pointer-regions ci)))]
                       (aset regions ci new-region)
                       (aset bitmap-regions ci new-bm)
                       (aset desc-regions ci new-desc)
                       (aset desc-flags-regions ci new-desc-f)
                       (aset desc-pointer-regions ci new-desc-p)
                       (aset total-blocks ci (int n-blocks))
                       (slab/regions-changed! ctx)))
                   (grow! [ci]
                     (locking regions ;; serialize growth across JVM threads
                       (if (nil? (aget file-paths ci))
                         ;; Heap class (no file): grow by replacement
                         (grow-heap-class! ctx ci)
                         (when-let [path (aget file-paths ci)]
                         (let [cached    (long (aget total-blocks ci))
                               ;; Peek header to detect external growth
                               peek-r    (region/open-mmap-region path 64 (region-opts (aget regions ci)))
                               hdr-total (long (slab/region-load-i32 peek-r d/SLAB_HDR_TOTAL_BLOCKS))]
                           (cond
                             (> hdr-total cached)
                             ;; Another process/thread grew — re-map at header size
                             (do (remap-jvm! ci hdr-total) true)

                             ;; Revalidate before leading: a positive header
                             ;; free-count at CURRENT geometry means the nil
                             ;; that brought this thread here came from a stale
                             ;; snapshot (a peer grew, or frees landed) —
                             ;; doubling again would run the class away
                             ;; geometrically. Report growth; the retry scans
                             ;; the current regions and allocates.
                             (pos? (slab/region-load-i32 peek-r d/SLAB_HDR_FREE_COUNT))
                             true

                             :else
                             ;; Try to be growth leader via CAS on total_blocks
                             (let [new-total (* 2 hdr-total)
                                   added     (- new-total hdr-total)
                                   ;; a :mem-budget refuses BEFORE the CAS can
                                   ;; publish a doubled size it leaves no room
                                   ;; for (witness -2): data, bitmap and the
                                   ;; three descriptor files all grow by it
                                   delta     (+ (* added (long (aget block-sizes ci)))
                                                (- (d/bitmap-byte-size new-total) (d/bitmap-byte-size hdr-total))
                                                (* 3 (- (d/desc-column-byte-size new-total) (d/desc-column-byte-size hdr-total))))
                                   witness   (int (if (budget-allows? ctx delta)
                                                    (slab/region-cas-i32! peek-r d/SLAB_HDR_TOTAL_BLOCKS
                                                                          (int hdr-total) (int new-total))
                                                    -2))]
                               (cond
                                 (== witness -2) false
                                 (== witness (int hdr-total))
                                 ;; Won CAS — extend both files
                                 (do (remap-jvm! ci new-total)
                                     (slab/region-add-i32! (aget regions ci) d/SLAB_HDR_FREE_COUNT (int added))
                                     true)
                                 :else
                                 ;; Lost CAS — re-map at winner's size
                                 (do (remap-jvm! ci (long witness))
                                     true)))))))))]
             (loop [ci class-idx, attempts 0]
               (or (try-alloc ci)
                   (if (and (< attempts 20) (grow! ci))
                     (recur ci (inc attempts))
                     ;; Growth exhausted — spill to next larger class
                     (if (< ci 5)
                       (recur (inc ci) 0)
                       ;; All bitmap classes full — spill to coalesc, through
                       ;; the same growth discipline as a direct class-6 alloc
                       (let [region (aget regions OVERFLOW_CLASS_IDX)]
                         (if region
                           (coalesc-alloc-growing! ctx size-bytes)
                           (throw
                             (ex-info "JvmSlabCtx: all slab classes full"
                                      {:class-idx ci}))))))))))))

     (defn- desc-handle
       "The tally handle of block block-idx of bitmap class ci in ctx: [[count-region count-off]
        [flags-region flags-off] [pointer-region pointer-off]] — the addresses of the block's COUNT,
        FLAGS and POINTER column entries — or nil when the class carries no columns."
       [^JvmSlabCtx ctx ci block-idx]
       (let [dr (aget ^objects (field ctx desc-regions) ci)]
         (when dr
           (let [fr   (aget ^objects (field ctx desc-flags-regions) ci)
                 pr   (aget ^objects (field ctx desc-pointer-regions) ci)
                 off  (aget ^ints (field ctx desc-offsets) ci)
                 ;; a heap / SAB slab carries all three columns in its own region (FLAGS right after
                 ;; COUNT, POINTER right after FLAGS); an mmap class's flags and pointer regions are
                 ;; their own files, their columns at offset 0 there
                 foff (if (identical? fr dr)
                        (+ off (* 4 (aget ^ints (field ctx total-blocks) ci)))
                        off)
                 poff (if (identical? pr dr)
                        (+ off (* 8 (aget ^ints (field ctx total-blocks) ci)))
                        off)]
             [[dr (+ off (* 4 block-idx))]
              [fr (+ foff (* 4 block-idx))]
              [pr (+ poff (* 4 block-idx))]]))))

     (defn ctx-free!
       "The block at slab-offset in ctx's slabs freed: true, or false when it
        was free already (a double free)."
       [^JvmSlabCtx ctx slab-offset]
       (let [^objects regions (field ctx regions)
             class-idx        (decode-class-idx slab-offset)]
         (if (== class-idx OVERFLOW_CLASS_IDX)
           ;; Coalescing free (class 6)
           (let [byte-off (decode-block-idx slab-offset)
                 region   (aget regions OVERFLOW_CLASS_IDX)]
             (if region
               (coalesc/coalesc-free! region byte-off)
               false))
           ;; Bitmap free (class 0-5)
           (let [block-idx (decode-block-idx slab-offset)
                 bm-rgn    (aget ^objects (field ctx bitmap-regions) class-idx)
                 bm-off    (aget ^ints (field ctx bitmap-offsets) class-idx)
                 ;; the block's descriptor is reset BEFORE its bit clears (after it another thread may claim the
                 ;; block): its count 0, its flags clear and its digest pointer 0 — the block's own
                 ;; columns, nothing else
                 _         (when-let [[[cr co] [fr fo] [pr po]] (desc-handle ctx class-idx block-idx)]
                             (slab/region-store-i32! cr co 0)
                             (slab/region-store-i32! fr fo 0)
                             (slab/region-store-i32! pr po 0))
                 freed?    (slab/bitmap-free! bm-rgn bm-off block-idx)]
             (when freed?
               (slab/region-add-i32! (aget regions class-idx) d/SLAB_HDR_FREE_COUNT 1))
             freed?))))

     (defn ctx-desc-of
       "The tally handle of the block at slab-offset in ctx (data.cljc, Descriptor Column Layout):
        [[count-region count-off] [flags-region flags-off] [pointer-region pointer-off]], or nil
        (a class with no columns, or a class-6 offset no allocated block starts at). A bitmap
        class's descriptor i is block i's for the slab's life; a class-6 block's handle is its
        coalescing descriptor's."
       [^JvmSlabCtx ctx slab-offset]
       (let [ci (decode-class-idx slab-offset)]
         (cond
           (< ci OVERFLOW_CLASS_IDX)
           (desc-handle ctx ci (decode-block-idx slab-offset))

           (== ci OVERFLOW_CLASS_IDX)
           (when-let [r (aget ^objects (field ctx regions) OVERFLOW_CLASS_IDX)]
             (when-let [[co fo po] (coalesc/row-of r (decode-block-idx slab-offset))]
               [[r co] [r fo] [r po]]))

           :else nil)))

     (defn ctx-allocated?
       "True when the block at slab-offset in ctx is allocated: its bitmap bit set (classes 0-5), or an ALLOCATED
        coalescing descriptor starting there (class 6)."
       [^JvmSlabCtx ctx slab-offset]
       (let [ci (decode-class-idx slab-offset)]
         (cond
           (< ci OVERFLOW_CLASS_IDX)
           (let [bi (decode-block-idx slab-offset)]
             (and (< bi (aget ^ints (field ctx total-blocks) ci))
                  (let [w (slab/region-load-i32 (aget ^objects (field ctx bitmap-regions) ci)
                                                (+ (aget ^ints (field ctx bitmap-offsets) ci)
                                                   (* 4 (unsigned-bit-shift-right bi 5))))]
                    (not (zero? (bit-and w (bit-shift-left 1 (bit-and bi 31))))))))

           (== ci OVERFLOW_CLASS_IDX)
           (some? (ctx-desc-of ctx slab-offset))

           :else false)))

     (defn make-jvm-slab-ctx
       "Build a JvmSlabCtx from vectors of data regions and bitmap regions.
        Indices 0-5: bitmap slab classes. Index 6 (optional): coalescing overflow.
        Reads slab headers to populate data-offset, bitmap-offset, total-blocks caches.
        Optional paths-vec, bm-paths-vec, and max-caps-vec enable lazy slab growth."
       ([regions-vec bm-regions-vec]
        (make-jvm-slab-ctx regions-vec bm-regions-vec nil nil nil nil))
       ([regions-vec bm-regions-vec paths-vec bm-paths-vec max-caps-vec]
        (make-jvm-slab-ctx regions-vec bm-regions-vec paths-vec bm-paths-vec max-caps-vec nil))
       ([regions-vec bm-regions-vec paths-vec bm-paths-vec max-caps-vec desc-regions-vec]
        (make-jvm-slab-ctx regions-vec bm-regions-vec paths-vec bm-paths-vec max-caps-vec desc-regions-vec 0))
       ([regions-vec bm-regions-vec paths-vec bm-paths-vec max-caps-vec desc-regions-vec mem-budget]
        (make-jvm-slab-ctx regions-vec bm-regions-vec paths-vec bm-paths-vec max-caps-vec desc-regions-vec mem-budget 0))
       ([regions-vec bm-regions-vec paths-vec bm-paths-vec max-caps-vec desc-regions-vec mem-budget segment-bytes]
        (let [n           (count regions-vec)
              data-offs   (int-array n)
              bm-offs     (int-array n)
              tot-blocks  (int-array n)
              blk-sizes   (int-array n)
              fpaths      (object-array n)
              bm-fpaths   (object-array n)
              max-blks    (int-array n)
              desc-offs   (int-array n)
              desc-rgns   (object-array n)
              desc-flags-rgns (object-array n)
              desc-pointer-rgns (object-array n)]
          (dotimes [i n]
            (let [region (nth regions-vec i)]
              (when region
                (aset data-offs  i (int (mem/-load-i32 region d/SLAB_HDR_DATA_OFFSET)))
                (aset bm-offs    i (int (mem/-load-i32 region d/SLAB_HDR_BITMAP_OFFSET)))
                (aset tot-blocks i (int (mem/-load-i32 region d/SLAB_HDR_TOTAL_BLOCKS)))
                (aset blk-sizes  i (int (mem/-load-i32 region d/SLAB_HDR_BLOCK_SIZE)))))
            (when paths-vec
              (aset fpaths i (nth paths-vec i nil)))
            (when bm-paths-vec
              (aset bm-fpaths i (nth bm-paths-vec i nil)))
            (when max-caps-vec
              (aset max-blks i (int (nth max-caps-vec i 0))))
            ;; a bitmap class's descriptor columns: its .desc-count / .desc-flags / .desc-pointer
            ;; files' regions (mmap: desc-regions-vec, [count flags pointer] triples), else the ones
            ;; the slab's own region carries right after its bitmap (heap / SAB, data.cljc
            ;; slab-layout); class 6 has its own
            (when (< i OVERFLOW_CLASS_IDX)
              (if desc-regions-vec
                (when-let [[dr fr pr] (nth desc-regions-vec i nil)]
                  (aset desc-rgns i dr)
                  (aset desc-flags-rgns i fr)
                  (aset desc-pointer-rgns i pr)
                  (aset desc-offs i (int 0)))
                (when-let [region (nth regions-vec i)]
                  (aset desc-rgns i region)
                  (aset desc-flags-rgns i region)
                  (aset desc-pointer-rgns i region)
                  (aset desc-offs i (int (d/slab-desc-offset (aget bm-offs i) (aget tot-blocks i))))))))
          (slab/domain
            (JvmSlabCtx.
              (into-array regions-vec)
              (into-array bm-regions-vec)
              data-offs bm-offs tot-blocks blk-sizes fpaths bm-fpaths max-blks
              desc-rgns desc-flags-rgns desc-pointer-rgns desc-offs
              (long mem-budget) (long segment-bytes))))))

     (defn refresh-jvm-slab-regions!
       "Re-map any JVM slab regions whose header total_blocks exceeds the
        cached value (i.e. another process grew them). Thread-safe.
        Fast path: if no slab class grew, skip the lock entirely.
        Takes an explicit sio."
       ([sio]
       (let [^JvmSlabCtx ctx (slab/domain-ctx sio)
             ^objects regions      (field ctx regions)
             ^ints total-blocks    (field ctx total-blocks)
             ^objects file-paths   (field ctx file-paths)
             ^ints data-offsets    (field ctx data-offsets)
             ;; Fast path: lockless scan for growth in classes 0-5
             any-grew?
             (loop [ci 0]
               (cond
                 (>= ci (int d/NUM_SLAB_CLASSES)) false
                 (nil? (aget file-paths ci)) (recur (inc ci))
                 :else
                 (let [cached    (long (aget total-blocks ci))
                       cur-r     (aget regions ci)
                       hdr-total (long (slab/region-load-i32 cur-r d/SLAB_HDR_TOTAL_BLOCKS))]
                   (if (> hdr-total cached)
                     true
                     (recur (inc ci))))))
             ;; Check coalesc (class 6) growth without lock
             coalesc-grew?
             (when-let [path (aget file-paths OVERFLOW_CLASS_IDX)]
               (let [cur-region (aget regions OVERFLOW_CLASS_IDX)]
                 (if (nil? cur-region)
                   true ;; never opened — must check
                   (let [data-off    (long (aget data-offsets OVERFLOW_CLASS_IDX))
                         hdr-data-sz (long (slab/region-load-i64 cur-region coalesc/COALESC_HDR_DATA_SIZE))
                         cur-size    (- (long (mem/-byte-length cur-region)) data-off)]
                     (> hdr-data-sz cur-size)))))]
         (when (or any-grew? coalesc-grew?)
           ;; re-map every class the way it was opened (Lustre or not),
           ;; at the domain's segment window; each file's re-map reuses
           ;; its own old region's unchanged windows (only the delta maps)
           (let [seg  (long (field ctx segment-bytes))
                 opts (cond-> (region-opts (first (filter some? regions)))
                        (pos? seg) (assoc :segment-bytes seg))
                 ^objects bitmap-regions (field ctx bitmap-regions)
                 ^ints block-sizes       (field ctx block-sizes)
                 ^objects bitmap-paths   (field ctx bitmap-paths)
                 ^objects desc-regions   (field ctx desc-regions)
                 ^objects desc-flags-regions (field ctx desc-flags-regions)
                 ^objects desc-pointer-regions (field ctx desc-pointer-regions)]
             (do
               (locking regions
                 (when any-grew?
                   (dotimes [ci d/NUM_SLAB_CLASSES]
                     (when-let [path (aget file-paths ci)]
                       (let [cached    (long (aget total-blocks ci))
                             cur-r     (aget regions ci)
                             hdr-total (long (slab/region-load-i32 cur-r d/SLAB_HDR_TOTAL_BLOCKS))]
                         (when (> hdr-total cached)
                           (let [blk-size   (long (aget block-sizes ci))
                                 data-bytes (+ (long d/SLAB_HEADER_SIZE) (* hdr-total blk-size))
                                 bm-bytes   (d/bitmap-byte-size hdr-total)
                                 new-region (region/open-mmap-region path data-bytes (assoc opts :reuse-from cur-r))
                                 new-bm     (region/open-mmap-region (aget bitmap-paths ci) bm-bytes (assoc opts :reuse-from (aget bitmap-regions ci)))
                                 new-desc   (region/open-mmap-region (str path ".desc-count")
                                                                     (d/desc-column-byte-size hdr-total) (assoc opts :reuse-from (aget desc-regions ci)))
                                 new-desc-f (region/open-mmap-region (str path ".desc-flags")
                                                                     (d/desc-column-byte-size hdr-total) (assoc opts :reuse-from (aget desc-flags-regions ci)))
                                 new-desc-p (region/open-mmap-region (str path ".desc-pointer")
                                                                     (d/desc-column-byte-size hdr-total) (assoc opts :reuse-from (aget desc-pointer-regions ci)))]
                             (aset regions ci new-region)
                             (aset bitmap-regions ci new-bm)
                             (aset desc-regions ci new-desc)
                             (aset desc-flags-regions ci new-desc-f)
                             (aset desc-pointer-regions ci new-desc-p)
                             (aset total-blocks ci (int hdr-total))
                             (slab/regions-changed! ctx)))))))
                 (when coalesc-grew?
                   (when-let [path (aget file-paths OVERFLOW_CLASS_IDX)]
                     (let [cur-region (aget regions OVERFLOW_CLASS_IDX)
                           data-off   (long (aget data-offsets OVERFLOW_CLASS_IDX))]
                       (if (nil? cur-region)
                         (let [peek-r      (region/open-mmap-region path 64 opts)
                               hdr-data-sz (long (slab/region-load-i64 peek-r coalesc/COALESC_HDR_DATA_SIZE))]
                           (when (pos? hdr-data-sz)
                             (let [new-r (region/open-mmap-region path (+ data-off hdr-data-sz) opts)]
                               (aset regions OVERFLOW_CLASS_IDX new-r)
                               (slab/regions-changed! ctx))))
                         (let [hdr-data-sz (long (slab/region-load-i64 cur-region coalesc/COALESC_HDR_DATA_SIZE))
                               cur-size    (- (long (mem/-byte-length cur-region)) data-off)]
                           (when (> hdr-data-sz cur-size)
                             (let [new-r (region/open-mmap-region path (+ data-off hdr-data-sz) (assoc opts :reuse-from cur-region))]
                               (aset regions OVERFLOW_CLASS_IDX new-r)
                               (slab/regions-changed! ctx)))))))))))))))

     (defn sync-jvm-slab-regions!
       "Force every region the JVM slab ctx holds — the slab data regions,
        the allocator bitmap regions and the descriptor-table regions — to
        durable storage (eve.mem/sync-region!: a region that cannot force,
        a heap region, answers nil). The durability spike's commit ordering
        calls this before a root pointer is published; eve.atom/sync!
        calls it for the whole domain."
       [sio]
       (let [^JvmSlabCtx ctx (slab/domain-ctx sio)]
         (doseq [^objects arr [(field ctx regions) (field ctx bitmap-regions) (field ctx desc-regions)]
                 :when arr
                 r arr
                 :when r]
           (mem/sync-region! r))))

     (defn sync-jvm-touched-classes!
       "Force, per slab class in classes, that class's data region, bitmap
        region and three descriptor-column regions — the regions a commit
        in those classes wrote — instead of every region the ctx holds.
        (Broader in one way than sync-jvm-slab-regions!: on an mmap domain
        the flags/pointer column regions are their own files and ARE
        forced here; the whole-slab form never forced them.)"
       [sio classes]
       (let [^JvmSlabCtx ctx (slab/domain-ctx sio)]
         (doseq [ci classes
                 ^objects arr [(field ctx regions) (field ctx bitmap-regions)
                               (field ctx desc-regions) (field ctx desc-flags-regions)
                               (field ctx desc-pointer-regions)]
                 :when (and arr (< ci (alength arr)))]
           (when-let [r (aget arr ci)]
             (mem/sync-region! r)))))

     ;; -----------------------------------------------------------------------
     ;; Heap-Backed Slab Lifecycle  (JVM only)
     ;; -----------------------------------------------------------------------
     ;; init-jvm-heap-slab! creates one in-memory (non-file) slab backed by a
     ;; heap JvmByteBufferRegion.  make-jvm-heap-slab-ctx wraps all 6 size classes into a
     ;; JvmSlabCtx, giving non-persistent JVM atom domains the same slab API as
     ;; the Node.js CLJS implementation.
     ;;
     ;; Default per-slab capacity is 1 MB (vs. 32–64 MB on Node.js) because JVM
     ;; heap memory is committed immediately, not lazily via virtual pages.  Pass
     ;; :capacities {class-idx capacity-bytes} to override.

     (defn init-jvm-heap-slab!
       "Create a single in-memory slab for class-idx using a heap region.
        Writes the slab header and returns the initialized region."
       [class-idx & {:keys [capacity]}]
       (let [capacity   (long (or capacity (* 1 1024 1024)))   ; default 1 MB
             block-size (d/slab-size class-idx)
             layout     (d/slab-layout block-size capacity)
             {:keys [total-bytes bitmap-offset bitmap-size data-offset total-blocks]} layout
             region     (region/make-heap-region total-bytes)]
         (mem/-store-i32! region d/SLAB_HDR_MAGIC d/SLAB_MAGIC)
         (mem/-store-i32! region d/SLAB_HDR_BLOCK_SIZE block-size)
         (mem/-store-i32! region d/SLAB_HDR_TOTAL_BLOCKS total-blocks)
         (mem/-store-i32! region d/SLAB_HDR_FREE_COUNT total-blocks)
         (mem/-store-i32! region d/SLAB_HDR_ALLOC_CURSOR 0)
         (mem/-store-i32! region d/SLAB_HDR_CLASS_IDX class-idx)
         (mem/-store-i32! region d/SLAB_HDR_BITMAP_OFFSET bitmap-offset)
         (mem/-store-i32! region d/SLAB_HDR_DATA_OFFSET data-offset)
         ;; byte-array is zero-initialized by the JVM, so the bitmap (0 = free)
         ;; is already correct — no explicit zeroing needed.
         region))

     (defn make-jvm-heap-slab-ctx
       "Create a JvmSlabCtx from fresh in-memory heap slabs (no mmap files).
        Used for non-persistent JVM atom domains.

        Options map (optional):
          :capacities — {class-idx capacity-bytes}, defaults to 1 MB each"
       ([] (make-jvm-heap-slab-ctx {}))
       ([{:keys [capacities coalesc-descriptors mem-budget]
          :or {capacities {} coalesc-descriptors coalesc/MAX_DESCRIPTORS}}]
        (let [regions (mapv (fn [i]
                              (let [cap (get capacities i (* 1 1024 1024))]
                                (init-jvm-heap-slab! i :capacity cap)))
                            (range d/NUM_SLAB_CLASSES))
              ;; class 6: the coalescing allocator for blocks over 1024 bytes;
              ;; its descriptor table is sized by the domain's
              ;; :coalesc-descriptors creation parameter
              coalesc-init (* 64 1024)
              layout       (coalesc/coalesc-layout coalesc-init coalesc-descriptors)
              coalesc-r    (region/make-heap-region (:total-bytes layout))]
          (coalesc/init-coalesc-region! coalesc-r coalesc-init coalesc-descriptors)
          ;; Heap slabs: bitmap is embedded in same region (no separate file);
          ;; class 6 has none
          (make-jvm-slab-ctx (conj regions coalesc-r) (conj regions nil)
                             nil nil nil nil (long (or mem-budget 0))))))

     (defn jvm-read-header-type-byte
       "Read the type-id byte at offset 0 of the slab block at slab-qualified offset.
        Takes an explicit sio."
       ([sio slab-off]
        (slab/read-u8 sio slab-off 0)))

     (defn jvm-alloc-scalar-block!
       "Allocate a slab block for a primitive scalar atom root value (JVM).
        Returns slab-qualified offset. Takes an explicit sio."
       ([sio v]
        (let [ev-bytes (codec/encode-value sio v)
              n        (inc (alength ^bytes ev-bytes))
              blk-off  (slab/alloc! sio n)]
          (slab/write-u8! sio blk-off 0 ser/SCALAR_BLOCK_TYPE_ID)
          (slab/write-bytes! sio blk-off 1 ev-bytes)
          blk-off)))

     (defn jvm-read-scalar-block
       "Read the primitive value from a scalar value block (JVM).
        Takes an explicit sio."
       ([sio slab-off]
        (let [class-idx (decode-class-idx slab-off)
              blk-size  (d/slab-size class-idx)
              ev-bytes  (slab/read-bytes sio slab-off 1 (dec blk-size))]
          (codec/decode sio ev-bytes)))))




;; ---------------------------------------------------------------------------
;; LoggingSlabIO — one swap attempt's allocation log (portable)
;; ---------------------------------------------------------------------------
;; eve.atom reads the old root through a LoggingSlabIO over the domain's sio;
;; every value derived from it (a path copy, a builder's result) allocates
;; through the same wrapper, so the attempt's allocations are known exactly:
;; freed when the CAS loses, forgotten when it wins. A block the attempt frees
;; itself (a writer's own superseded path copy) leaves the log, so a lost CAS
;; never frees it twice. No thread-local, no module hook, no dynamic var — the
;; handle is the value's own sio.

(deftype LoggingSlabIO [inner log keep]
  ISlabIO
  (-sio-read-u8    [_ o f]      (slab/read-u8 inner o f))
  (-sio-write-u8!  [_ o f v]    (slab/write-u8! inner o f v))
  (-sio-read-u16   [_ o f]      (slab/read-u16 inner o f))
  (-sio-write-u16! [_ o f v]    (slab/write-u16! inner o f v))
  (-sio-read-i32   [_ o f]      (slab/read-i32 inner o f))
  (-sio-write-i32! [_ o f v]    (slab/write-i32! inner o f v))
  (-sio-cas-i32!   [_ o f e d]  (slab/cas-i32! inner o f e d))
  (-sio-read-bytes [_ o f n]    (slab/read-bytes inner o f n))
  (-sio-write-bytes! [_ o f b]  (slab/write-bytes! inner o f b))
  (-sio-alloc!     [_ n]
    (let [off (slab/alloc! inner n)]
      (when @log (swap! log conj (bit-and (long off) 0xFFFFFFFF)))
      off))
  (-sio-free!      [_ o]
    ;; as its unsigned 32 bits: an offset read back from a block is sign-extended from class 4 up, and the allocator
    ;; answered it unsigned
    (when @log (swap! log disj (bit-and (long o) 0xFFFFFFFF)))
    (slab/free! inner o))
  (-sio-copy-block! [_ d df s sf n] (slab/copy-block! inner d df s sf n))
  ISlabRegion
  (-sio-mem-region [_ o f]      (slab/mem-region inner o f)))

(defn logging-sio?
  [sio]
  (instance? LoggingSlabIO sio))

(defn sio-root
  "The domain's own slab I/O under any logging wrappers (a transient's log over a swap attempt's)."
  [sio]
  (if (instance? LoggingSlabIO sio)
    (recur (field ^LoggingSlabIO sio inner))
    sio))

(ser/register-sio-root! sio-root)

;; ---------------------------------------------------------------------------
;; The hot read path: a published node's bytes (jvm-heap-grade step 3, 2026-11-05)
;; ---------------------------------------------------------------------------
;; A collection's lookup reads a node it reached through a pointer it acquired (the atom slot's atomic load, or
;; a parent node read the same way), and a published node is never written again (step 2: immutability of
;; published nodes), so these reads need no atomic op. Where the domain is a JvmSlabCtx — through any logging
;; wrappers — and the block's region is this process's heap bytes (eve.mem/IHeapBytes), each read is a plain
;; array load: one region call for the array, no ISlabIO dispatch, no allocation, no boxing. Anything else (a
;; mapped region, a wrapping sio that transforms bytes, another host's slab) keeps the ISlabIO op.
;;
;; Use these ONLY for bytes the reader cannot see change: nodes of a published (or this thread's own transient)
;; collection. A word other threads write (a tally, a descriptor, an atom slot) keeps slab/read-i32.

(defn- node-ctx*
  "sio's JvmSlabCtx under its logging wrappers, or nil."
  [sio]
  (loop [s sio]
    (cond (instance? JvmSlabCtx s) s
          (instance? LoggingSlabIO s) (recur (field ^LoggingSlabIO s inner))
          :else nil)))

(defn- node-heap-bytes
  "The heap byte array holding the block at slab-off in ctx's domain, or nil."
  [^JvmSlabCtx ctx ^long slab-off]
  (let [ci (unsigned-bit-shift-right (bit-and slab-off 0xFFFFFFFF) BLOCK_IDX_BITS)]
    (mem/-heap-bytes (aget ^objects (field ctx regions) (int ci)))))

(defn- node-at
  "The byte offset in its region of field-off in the block at slab-off."
   ^long [^JvmSlabCtx ctx ^long slab-off ^long field-off]
  (let [u  (bit-and slab-off 0xFFFFFFFF)
        ci (int (unsigned-bit-shift-right u BLOCK_IDX_BITS))
        bi (bit-and u BLOCK_IDX_MASK)]
    (+ (aget ^ints (field ctx data-offsets) ci)
       (* bi (aget ^ints (field ctx block-sizes) ci))
       field-off)))

(defn bytes-i32
  "The little-endian i32 at byte i of byte array a (plain loads)."
   ^long [^bytes a ^long i]
  (let [a a
        i (int i)]
    (long (unchecked-int
           (bit-or (bit-and (long (aget a i)) 0xFF)
                   (bit-shift-left (bit-and (long (aget a (eve.host/unchecked-add-int i 1))) 0xFF) 8)
                   (bit-shift-left (bit-and (long (aget a (eve.host/unchecked-add-int i 2))) 0xFF) 16)
                   (bit-shift-left (bit-and (long (aget a (eve.host/unchecked-add-int i 3))) 0xFF) 24))))))

;; A node scanned in place (a map node's KV pairs): its heap array and the byte offset of its block in it, resolved
;; once per node; then bytes-i32 per word.
(defn node-heap-array
  "The heap byte array holding the published node at slab-off, or nil (read it with node-i32 then)."
  [sio ^long slab-off]
  (let [ctx (if (instance? JvmSlabCtx sio) sio (node-ctx* sio))]
    (when ctx (node-heap-bytes ctx slab-off))))

(defn node-base
  "The byte offset in node-heap-array's array of the block at slab-off (call only where node-heap-array answered one)."
   [sio ^long slab-off]
  (let [ctx (if (instance? JvmSlabCtx sio) sio (node-ctx* sio))]
    (node-at ctx slab-off 0)))

(defn node-bytes
  "The len bytes at slab-off + field-off of a published node, as a new byte array: the region's own bulk read,
   without the ISlabIO hop."
  [sio ^long slab-off ^long field-off ^long len]
  (let [ctx (if (instance? JvmSlabCtx sio) sio (node-ctx* sio))]
    (if (nil? ctx)
      (-sio-read-bytes sio slab-off field-off len)
      (let [ci (unsigned-bit-shift-right (bit-and slab-off 0xFFFFFFFF) BLOCK_IDX_BITS)]
        (mem/-read-bytes (aget ^objects (field ^JvmSlabCtx ctx regions) (int ci)) (node-at ctx slab-off field-off) len)))))

(defn- node-region
  "The IMemRegion holding the block at slab-off in ctx's domain."
  [^JvmSlabCtx ctx ^long slab-off]
  (aget ^objects (field ctx regions)
        (int (unsigned-bit-shift-right (bit-and slab-off 0xFFFFFFFF) BLOCK_IDX_BITS))))

(defn node-u8
  "The unsigned byte at slab-off + field-off of a published node."
   ^long [sio ^long slab-off ^long field-off]
  (let [ctx (if (instance? JvmSlabCtx sio) sio (node-ctx* sio))
        arr (when ctx (node-heap-bytes ctx slab-off))]
    (if (nil? arr)
      ;; no heap array (a mapped region, another backing): a PLAIN load of
      ;; the region (eve.mem/IPlainLoad) — the node's bytes are published
      ;; and never change; the atomic -sio-read-u8 path cost ~100x per step
      ;; on the JVM seat (cleanup-sweep item 9b). A non-JvmSlabCtx sio
      ;; keeps its own op.
      (if ctx
        (long (mem/-plain-load-u8 (node-region ctx slab-off)
                                  (node-at ctx slab-off field-off)))
        (long (-sio-read-u8 sio slab-off field-off)))
      (bit-and (long (aget ^bytes arr (int (node-at ctx slab-off field-off)))) 0xFF))))

(defn node-i32
  "The little-endian i32 at slab-off + field-off (any alignment) of a published node."
   ^long [sio ^long slab-off ^long field-off]
  (let [ctx (if (instance? JvmSlabCtx sio) sio (node-ctx* sio))
        arr (when ctx (node-heap-bytes ctx slab-off))]
    (if (nil? arr)
      (if ctx
        (long (mem/-plain-load-i32 (node-region ctx slab-off)
                                   (node-at ctx slab-off field-off)))
        (long (-sio-read-i32 sio slab-off field-off)))
      (bytes-i32 arr (node-at ctx slab-off field-off)))))

;; IWrappedSio is defined before node-bytes-equal? (not after it, as the JVM's
;; load order allowed): the jank slab-io adapter's bytes-equal-at? is a macro
;; whose expansion names this protocol, and jank's analyzer resolves the name
;; at expansion time.
(defprotocol IWrappedSio
  "A slab I/O that transforms another's bytes (eve.keyring's encrypting one):
   the domain it writes is the wrapped one's."
  (-wrapped-sio [w]))

(defn node-bytes-equal?
  "Do the (alength b) bytes at slab-off + field-off of a published node equal byte array b."
  [sio ^long slab-off ^long field-off ^bytes b]
  (let [ctx (if (instance? JvmSlabCtx sio) sio (node-ctx* sio))
        arr (when ctx (node-heap-bytes ctx slab-off))]
    (if (nil? arr)
      (slab/bytes-equal-at? sio slab-off field-off b)
      (let [^bytes a arr
            b b
            n (alength b)
            i0 (node-at ctx slab-off field-off)]
        (loop [j 0]
          (cond (== j n) true
                (== (aget a (int (+ i0 j))) (aget b (int j))) (recur (inc j))
                :else false))))))


;; ---------------------------------------------------------------------------
;; A published node, resolved once: [arr region base] — arr the region's
;; heap byte array when the block's region is heap-backed (nil otherwise),
;; region the IMemRegion holding the block, base the byte offset of the
;; block in its backing. The per-word readers above re-walk the wrapper
;; chain (node-ctx*), the region lookup (node-heap-bytes / node-region),
;; and the offset arithmetic (node-at) on EVERY word; a descent reads
;; ~10 words over ~4 nodes, so the resolution, not the load, was the
;; per-word price (tier-repair round 2, adder A4). The view readers pay
;; the resolution once per node. Same contract as the node readers:
;; published (or this thread's own transient) nodes only.

(defn node-view
  "Resolve the published node at slab-off to its view, or nil when sio
   is not a JvmSlabCtx under its wrappers (a foreign sio: the caller
   keeps the per-word path)."
  [sio ^long slab-off]
  (let [ctx (if (instance? JvmSlabCtx sio) sio (node-ctx* sio))]
    (when ctx
      [(node-heap-bytes ctx slab-off)
       (node-region ctx slab-off)
       (node-at ctx slab-off 0)])))

(defn node-viewable?
  "Does node-view resolve nodes in sio (a JvmSlabCtx under its wrappers)? The
   same test node-view makes, without building a view."
  [sio]
  (or (instance? JvmSlabCtx sio) (some? (node-ctx* sio))))

(defn view-u8
  "The unsigned byte at field-off of a node view."
  [view ^long field-off]
  (let [arr (nth view 0)
        base (long (nth view 2))]
    (if (nil? arr)
      (long (mem/-plain-load-u8 (nth view 1) (+ base field-off)))
      (bit-and (long (aget ^bytes arr (int (+ base field-off)))) 0xFF))))

(defn view-i32
  "The little-endian i32 at field-off of a node view."
  [view ^long field-off]
  (let [arr (nth view 0)
        base (long (nth view 2))]
    (if (nil? arr)
      (long (mem/-plain-load-i32 (nth view 1) (+ base field-off)))
      (bytes-i32 arr (+ base field-off)))))

(defn view-bytes
  "The len bytes at field-off of a node view, as a new byte array."
  [view ^long field-off ^long len]
  (mem/-read-bytes (nth view 1) (+ (long (nth view 2)) field-off) len))

(defn view-bytes-equal?
  "Do the (alength b) bytes at field-off of a node view equal byte array b."
  [view ^long field-off ^bytes b]
  (let [arr (nth view 0)
        base (long (nth view 2))]
    (if (nil? arr)
      (enc/bytes= (mem/-read-bytes (nth view 1) (+ base field-off) (alength b)) b)
      (let [^bytes a arr
            n (alength b)
            i0 (+ base field-off)]
        (loop [j 0]
          (cond (== j n) true
                (== (aget a (int (+ i0 j))) (aget b (int j))) (recur (inc j))
                :else false))))))

;; ---------------------------------------------------------------------------
;; A node view's parts, read directly: hamt-get-view (eve.map) resolves
;; each node once into [arr region base] and then reads ~10 words in it.
;; The view readers above re-pay (nth view i) on EVERY word — two to
;; three vector dispatches per load. These take the parts as arguments:
;; the caller destructures once per node, the per-word price is the load
;; itself (tier-repair round 3). Same contract as the view readers.

(defn parts-u8
  "The unsigned byte at field-off of a node given as its view parts."
  ^long [arr region ^long base ^long field-off]
  (if (nil? arr)
    (long (mem/-plain-load-u8 region (+ base field-off)))
    (bit-and (long (aget ^bytes arr (int (+ base field-off)))) 0xFF)))

(defn parts-i32
  "The little-endian i32 at field-off of a node given as its view parts."
  ^long [arr region ^long base ^long field-off]
  (if (nil? arr)
    (long (mem/-plain-load-i32 region (+ base field-off)))
    (bytes-i32 arr (+ base field-off))))

(defn parts-bytes
  "The len bytes at the absolute offset abs-off (the node's base
   already added by the caller — a fn taking primitives supports at
   most 4 args) of a node given as its view parts, as a new byte
   array."
  [arr region ^long abs-off ^long len]
  (mem/-read-bytes region abs-off len))

(defn parts-bytes-equal?
  "Do the (alength b) bytes at the absolute offset abs-off (the
   node's base already added by the caller — a fn taking primitives
   supports at most 4 args) of a node given as its view parts equal
   byte array b."
  [arr region ^long abs-off ^bytes b]
  (if (nil? arr)
    ;; a region with no heap array (a mapped file): compared in place, a
    ;; word then the tail bytes through the region's plain loads — never a
    ;; copy of the stored key (fix-for-release-2 E3: the copy was ~45% of a
    ;; warm map get's allocation, a fresh array plus a boxed length)
    (let [n (alength b)
          n4 (- n 3)]
      (loop [j 0]
        (cond (< j n4)
              (if (== (long (mem/-plain-load-i32 region (+ abs-off j))) (bytes-i32 b j))
                (recur (+ j 4))
                false)
              (< j n)
              (if (== (long (mem/-plain-load-u8 region (+ abs-off j))) (bit-and (long (aget b (int j))) 0xFF))
                (recur (inc j))
                false)
              :else true)))
    (let [^bytes a arr
          n (alength b)
          i0 abs-off]
      (loop [j 0]
        (cond (== j n) true
              (== (aget a (int (+ i0 j))) (aget b (int j))) (recur (inc j))
              :else false)))))

(declare host-op)

(defn domain-root
  "The domain's own slab I/O (through logging and through a wrapper).
   A slab context is its own domain and wraps nothing: answered before the
   wrapper test, whose negative satisfies? walks the type's supers on every
   call — measured 20% of an add-5 commit's CPU (fix-for-release-2 E2: the
   tally's desc-of / allocated? / parker each ask, per visited block)."
  [sio]
  (loop [r (sio-root sio)]
    (cond (instance? JvmSlabCtx r) r
          (satisfies? IWrappedSio r) (recur (sio-root (-wrapped-sio r)))
          :else r)))

(defn desc-of
  "The tally handle of the block at slab-offset in sio's domain: [[count-region count-off]
   [flags-region flags-off] [pointer-region pointer-off]] (data.cljc, Descriptor Column
   Layout) — or nil where the block has none. The process slab answers where it is the
   domain (ClojureScript's module slabs)."
  [sio slab-offset]
  (let [ctx (slab/domain-ctx (domain-root sio))]
    (if (instance? JvmSlabCtx ctx)
      (ctx-desc-of ctx slab-offset)
      (when-let [f (host-op :desc-of)] (f ctx slab-offset)))))

(defn allocated?
  "True when the block at slab-offset in sio's domain is allocated (not free, never handed out)."
  [sio slab-offset]
  (let [ctx (slab/domain-ctx (domain-root sio))]
    (if (instance? JvmSlabCtx ctx)
      (ctx-allocated? ctx slab-offset)
      (if-let [f (host-op :allocated?)] (f ctx slab-offset) false))))

(defn shared-domain?
  "True when sio's domain is files other processes map (an mmap domain): eve's
   codec never writes a reference there (FORMAT-DECISIONS.md §9). A heap
   domain is this process's alone."
  [sio]
  (let [r (sio-root sio)]
    (if (satisfies? IWrappedSio r)
      (shared-domain? (-wrapped-sio r))
      (if-let [f (host-op :shared-domain?)]
        (f r)
        (and (instance? JvmSlabCtx r)
             (some? (aget ^objects (field ^JvmSlabCtx r file-paths) 0)))))))

(ser/register-shared-domain-fn! shared-domain?)

(defn logging-sio
  "A fresh allocation log over sio's domain."
  [sio]
  (LoggingSlabIO. (sio-root sio) (clojure.core/atom #{}) (clojure.core/atom false)))

(defn scope-sio
  "A fresh allocation log over sio as it is — a transient's: what the transient allocates is logged here and, under a
   swap attempt's log, there too (a lost attempt still frees it); what it frees leaves both."
  [sio]
  (LoggingSlabIO. sio (clojure.core/atom #{}) (clojure.core/atom false)))

(defn owns?
  "True when a logging sio allocated the block at off and has not freed it — a transient's own block, which no
   other version holds."
  [sio off]
  (and (instance? LoggingSlabIO sio)
       (contains? @(field ^LoggingSlabIO sio log) (bit-and (long off) 0xFFFFFFFF))))

(defn keep-owned!
  "A write through a scope stored an existing eve value by pointer: the piece, or blocks a value derived from it
   shares, may now be held at two places in the scope's value, so the scope frees nothing more within itself — the
   commit's walk counts what is held where (eve.retire)."
  [sio]
  ;; every log in the chain: a transient's scope, the swap attempt's under it
  (loop [s sio]
    (when (instance? LoggingSlabIO s)
      (reset! (field ^LoggingSlabIO s keep) true)
      (recur (field ^LoggingSlabIO s inner))))
  nil)

(ser/register-stored-piece! keep-owned!)

(defn stored-pieces?
  "True when a write through the logging sio (or a scope under it) stored an existing eve value by pointer."
  [sio]
  (and (instance? LoggingSlabIO sio) (boolean @(field ^LoggingSlabIO sio keep))))

(defn free-owned!
  "Free each of offs a logging sio allocated and has not freed, through it: a block it did not allocate (one of the
   value the scope began from) is not its to free. Each checked as it goes, so an offset named twice is freed once.
   A scope that stored an existing eve value by pointer (keep-owned!) frees nothing."
  [sio offs]
  (when (and (instance? LoggingSlabIO sio) (not @(field ^LoggingSlabIO sio keep)))
    (let [log (field ^LoggingSlabIO sio log)]
      (doseq [off offs]
        (when (contains? @log (bit-and (long off) 0xFFFFFFFF))
          (slab/free! sio off))))))

(defn logged-allocs
  "The slab offsets allocated through a logging sio so far and not freed through it (a set, each offset's unsigned
   32 bits)."
  [sio]
  (if (instance? LoggingSlabIO sio)
    (or @(field ^LoggingSlabIO sio log) [])
    []))

(defn stop-logging!
  "Stop recording through a logging sio (the swap is over; values that still
   carry the wrapper keep working, their allocations no longer logged)."
  [sio]
  (when (instance? LoggingSlabIO sio)
    (reset! (field ^LoggingSlabIO sio log) nil))
  nil)

;; ---------------------------------------------------------------------------
;; Scalar root blocks — portable
;; ---------------------------------------------------------------------------
;; A primitive at the atom root lives in one block: [0x01][EVE bytes…], in the
;; host's codec (eve.codec — the one codec split that survives,
;; FORMAT-DECISIONS.md).

(defn value->scalar-block!
  "Allocate a scalar block for primitive v in sio. Returns its slab offset."
  [sio v]
  (let [ev  (codec/encode-value sio v)
        n   (inc (alength ^bytes ev))
        blk (slab/alloc! sio n)]
    (slab/write-u8! sio blk 0 ser/SCALAR_BLOCK_TYPE_ID)
    (slab/write-bytes! sio blk 1 ev)
    blk))

;; An out-of-line value (FORMAT-DECISIONS.md §10) is a scalar block too; its
;; pointer carries the length. Through eve.slab-io, as every slab access in the
;; shared code (on ValueScript a domain's slab I/O is no ISlabIO: the protocol
;; calls this block made refused it — EM4, eve.big-value-test on VS)
(ser/register-value-block-io!
 {:read (fn [sio blk len] (slab/read-bytes sio blk 1 len))
  :write (fn [sio bs]
           (let [blk (slab/alloc! sio (inc (alength ^bytes bs)))]
             (slab/write-u8! sio blk 0 ser/SCALAR_BLOCK_TYPE_ID)
             (slab/write-bytes! sio blk 1 bs)
             blk))})

(defn scalar-block->value
  "The primitive in the scalar block at slab-off."
  [sio slab-off]
  (let [ci       (decode-class-idx slab-off)
        blk-size (d/slab-size ci)
        ev       (slab/read-bytes sio slab-off 1 (dec blk-size))]
    (codec/decode sio ev)))

;; Bytes 1-3 of an eve-deftype block: its type's 24-bit name hash, which picks
;; the type among those sharing the type-id byte.
(defn- read-header-name-hash [sio slab-off]
  (let [b (fn [k] (slab/read-u8 sio slab-off k))]
    (bit-or (b 1) (bit-shift-left (b 2) 8) (bit-shift-left (b 3) 16))))
(ser/register-header-name-hash-reader! read-header-name-hash)

;; ---------------------------------------------------------------------------
;; Domain slab I/O — portable openers
;; ---------------------------------------------------------------------------
;; CLJS slabs are module state (one process, one set of slab files or SABs);
;; JVM/bb slabs are a JvmSlabCtx per domain. Both answer to the same two
;; constructors, so eve.atom opens a domain the same way on every host.

;; The process slab: ClojureScript's module slab registers its own
;; (adapters/slab/module, eve.process-slab); elsewhere a domain is a
;; JvmSlabCtx and this process's own is a heap one (below). Keys: :default-sio
;; :refresh! :mmap-domain-sio! :heap-domain-sio! :release-domain-sio!
;; :init-domain-views!, each a fn of the opener's args.
(defonce ^:private host-slab (clojure.core/atom nil))

(defn register-host-slab!
  "The host's process slab: a map of the openers it answers itself."
  [m]
  (reset! host-slab m))

(defn- host-op [k] (get @host-slab k))

(defn release-domain-sio!
  "A domain's slab I/O let go (nothing for a JvmSlabCtx: its regions close
   with it)."
  [sio mmap?]
  (when-let [f (host-op :release-domain-sio!)] (f sio mmap?)))

(defn sync-sio!
  "Force every file-backed region a domain's slab I/O holds to durable
   storage: the host slab's :sync-domain-sio! when it registers one (the
   Node module slabs), else the slab ctx's slab, bitmap and descriptor
   regions (sync-jvm-slab-regions!) — the same unconditional fallback
   refresh! takes: every non-CLJS host's ctx is that ctx, and a region
   that cannot force answers nil (eve.mem/sync-region!), so a heap
   domain is a no-op."
  [sio]
  (if-let [f (host-op :sync-domain-sio!)]
    (f sio)
    (sync-jvm-slab-regions! (sio-root sio))))

(defn sync-touched!
  "Force only the slab classes a commit touched — the classes of its
   logged allocations and of the descriptor rows its tally holds/drops
   mutated (eve.tally/*touches*) — instead of the whole slab: the
   commit path's durability force (eve.atom/swap-attempt!). The host
   slab's :sync-touched! when it registers one; a JvmSlabCtx answers
   class by class; any other seat falls back to sync-sio!, the
   whole-domain force — never to nothing."
  [sio offsets]
  (if-let [f (host-op :sync-touched!)]
    (f sio offsets)
    (let [ctx (slab/domain-ctx (domain-root sio))]
      (if (instance? JvmSlabCtx ctx)
        (sync-jvm-touched-classes! sio (into #{} (map decode-class-idx) offsets))
        (sync-sio! sio)))))

(defn init-domain-views!
  "Ready the process slab's own views of a heap domain (nothing for a
   JvmSlabCtx: values are read through it)."
  []
  (when-let [f (host-op :init-domain-views!)] (f)))

(defn refresh!
  "Re-map any slab class another process grew past this process's mapping."
  [sio]
  (if-let [f (host-op :refresh!)]
    (f sio)
    ;; through a wrapper (eve.keyring's) to the domain it writes
    (refresh-jvm-slab-regions! (domain-root sio))))

(defn- jvm-mmap-domain-sio! [base-path {:keys [create? capacities lustre? coalesc-descriptors mem-budget segment-bytes] :or {capacities {} coalesc-descriptors coalesc/DEFAULT_MMAP_DESCRIPTORS}}]
     (let [opts       (cond-> nil
                        lustre?       (assoc :lustre? true)
                        segment-bytes (assoc :segment-bytes (long segment-bytes)))
           slab-paths (mapv #(str base-path ".slab" %) (range d/NUM_SLAB_CLASSES))
           bm-paths   (mapv #(str base-path ".slab" % ".bm") (range d/NUM_SLAB_CLASSES))
           per-class
           (mapv (fn [i]
                   (if create?
                     (let [block-size  (d/slab-size i)
                           init-cap    (get capacities i (d/initial-capacity-for-class i))
                           layout      (d/mmap-slab-layout block-size init-cap)
                           init-blocks (:total-blocks layout)
                           region      (region/open-mmap-region (nth slab-paths i) (:data-bytes layout) opts)
                           bm-region   (region/open-mmap-region (nth bm-paths i) (:bitmap-bytes layout) opts)
                           desc-count-region (region/open-mmap-region (str (nth slab-paths i) ".desc-count") (:desc-column-bytes layout) opts)
                           desc-flags-region (region/open-mmap-region (str (nth slab-paths i) ".desc-flags") (:desc-column-bytes layout) opts)
                           desc-pointer-region (region/open-mmap-region (str (nth slab-paths i) ".desc-pointer") (:desc-column-bytes layout) opts)]
                       (mem/-store-i32! region d/SLAB_HDR_MAGIC d/SLAB_MAGIC)
                       (mem/-store-i32! region d/SLAB_HDR_BLOCK_SIZE block-size)
                       (mem/-store-i32! region d/SLAB_HDR_TOTAL_BLOCKS init-blocks)
                       (mem/-store-i32! region d/SLAB_HDR_FREE_COUNT init-blocks)
                       (mem/-store-i32! region d/SLAB_HDR_ALLOC_CURSOR 0)
                       (mem/-store-i32! region d/SLAB_HDR_CLASS_IDX i)
                       (mem/-store-i32! region d/SLAB_HDR_BITMAP_OFFSET 0)
                       (mem/-store-i32! region d/SLAB_HDR_DATA_OFFSET d/SLAB_HEADER_SIZE)
                       {:region region :bm-region bm-region
                        :desc-regions [desc-count-region desc-flags-region desc-pointer-region]})
                     (let [peek-r     (region/open-mmap-region (nth slab-paths i) 64 opts)
                           total      (mem/-load-i32 peek-r d/SLAB_HDR_TOTAL_BLOCKS)
                           bs         (d/slab-size i)
                           data-bytes (+ d/SLAB_HEADER_SIZE (* total bs))
                           bm-bytes   (d/bitmap-byte-size total)]
                       {:region      (region/open-mmap-region (nth slab-paths i) data-bytes opts)
                        :bm-region   (region/open-mmap-region (nth bm-paths i) bm-bytes opts)
                        ;; a domain written before descriptor columns: open-mmap-region creates its files —
                        ;; virgin (all-zero) columns, every count 0, as a pre-descriptor block's count is
                        :desc-regions [(region/open-mmap-region (str (nth slab-paths i) ".desc-count")
                                                                (d/desc-column-byte-size total) opts)
                                       (region/open-mmap-region (str (nth slab-paths i) ".desc-flags")
                                                                (d/desc-column-byte-size total) opts)
                                       (region/open-mmap-region (str (nth slab-paths i) ".desc-pointer")
                                                                (d/desc-column-byte-size total) opts)]}) ))
                 (range d/NUM_SLAB_CLASSES))
           coalesc-path (str base-path ".slab6")
           coalesc-r
           (if create?
             (let [layout (coalesc/coalesc-layout coalesc/INITIAL_DATA_SIZE coalesc-descriptors)
                   r      (region/open-mmap-region coalesc-path (:total-bytes layout) opts)]
               (coalesc/init-coalesc-region! r coalesc/INITIAL_DATA_SIZE coalesc-descriptors)
               r)
             (let [peek     (region/open-mmap-region coalesc-path 64 opts)
                   data-off (mem/-load-i32 peek d/SLAB_HDR_DATA_OFFSET)
                   cur-sz   (mem/-load-i64 peek coalesc/COALESC_HDR_DATA_SIZE)]
               (region/open-mmap-region coalesc-path (+ data-off cur-sz) opts)))]
       (make-jvm-slab-ctx (conj (mapv :region per-class) coalesc-r)
                          (conj (mapv :bm-region per-class) nil)
                          (conj slab-paths coalesc-path)
                          (conj bm-paths nil)
                          nil
                          (conj (mapv :desc-regions per-class) nil)
                          (long (or mem-budget 0))
                          (long (or segment-bytes 0)))))

(defn keystream-domain-sio
  "Domain sio's blocks under keystream ks ({:int (fn [o f w]) → the w keystream
   bytes at block o's field offset f as an unsigned little-endian integer,
   :bytes (fn [o f n]) → those n bytes}): where the host's process slab is the
   domain and reads its blocks itself (ClojureScript's module slabs, through
   DataViews), the slab takes the keystream and sio answers; else (wrap sio),
   a slab I/O that applies it (eve.keyring)."
  [sio ks wrap]
  (if-let [f (host-op :install-keystream!)]
    (f sio ks)
    (wrap sio)))

(defn keystream-domain?
  "True when sio's domain is under a keystream its process slab applies
   (keystream-domain-sio's first arm)."
  [sio]
  (boolean (when-let [f (host-op :keystream?)] (f sio))))

(defn mmap-domain-sio!
  "The slab I/O of the mmap domain at base-path: {base}.slab0–5 (+ .bm) and
   .slab6. opts: :create? (initialise fresh files; else open existing),
   :capacities {class-idx blocks}, :lustre? (fcntl-locked regions),
   :mem-budget (domain byte budget; growth past it is refused and named),
   :segment-bytes (region mapping window; absent = the adapter's default)."
  [base-path opts]
  (if-let [f (host-op :mmap-domain-sio!)]
    (f base-path opts)
    (jvm-mmap-domain-sio! base-path opts)))

(defn heap-domain-sio!
  "The slab I/O of an in-process domain: fresh heap slabs (1 MB per class by
   default), or the process slab's own where it answers (ClojureScript's
   module slabs, already initialised by the runtime).
   opts: :capacities {class-idx bytes}, :mem-budget (domain byte budget;
   growth past it is refused and named — see JvmSlabCtx's mem-budget field)."
  [{:keys [capacities coalesc-descriptors mem-budget] :or {capacities {}} :as opts}]
  (if-let [f (host-op :heap-domain-sio!)]
    (f opts)
    (make-jvm-heap-slab-ctx (cond-> {:capacities capacities}
                              coalesc-descriptors
                              (assoc :coalesc-descriptors coalesc-descriptors)
                              mem-budget
                              (assoc :mem-budget mem-budget)))))

;; ---------------------------------------------------------------------------
;; The default slab I/O of this process
;; ---------------------------------------------------------------------------

(defdelay ^:private process-heap-ctx
  ;; The in-process slab domain a value lands in when its constructor is
  ;; given no sio: one per process.
  (make-jvm-heap-slab-ctx))

(defn default-sio
  "This process's own slab I/O: the process slab's (ClojureScript's module
   slab), else this process's heap slab domain."
  []
  (if-let [f (host-op :default-sio)]
    (f)
    @process-heap-ctx))

(defn no-domain-context!
  "The construction law (John, 2026-11-11): all Eve data must be constructed
   in a swapping operation, in the atom domain. Instantiating Eve data
   outside an Eve context throws — there is no silent process-heap fallback."
  []
  (throw (ex-info "Eve data must be constructed inside a swap! (or an explicit Eve domain context)"
                  {:eve.alloc/error :no-domain-context})))

(defn current-sio
  "The slab I/O a constructor allocates in when given none: inside a swap!'s
   f (or another binding of d/*domain-sio* over a value's own domain), that
   sio. Outside any Eve domain context it throws (no-domain-context!) —
   constructors never fall back to this process's own domain."
  []
  (or d/*domain-sio* (no-domain-context!)))

(defn plumbing-sio
  "Eve's OWN plumbing only: the sio a converter/materializer uses when its
   caller passes none — deref materialization, codec/read paths, serialize's
   builders. Inside a context, that context's sio; outside, this process's
   own (default-sio). User-level construction must NOT call this: it goes
   through current-sio and throws outside a context."
  []
  (or d/*domain-sio* (default-sio)))

(ser/register-default-sio! plumbing-sio)
