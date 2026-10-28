(ns eve.process-slab
  "ClojureScript's process slab (nbb's too): the module slabs every value of
   this process lives in — SharedArrayBuffers, or one mmap domain's files on
   Node — read through DataViews, allocated by bitmap (SIMD where wasm is
   ready), with the coalescing overflow class 6, the atom root and the worker
   registry. CljsSlabIO is its ISlabIO. It registers itself as the process
   slab (eve.deftype-proto.alloc/register-host-slab!), so eve's openers and
   default slab I/O answer through it here.
   (Moved from eve.deftype-proto.alloc's ClojureScript arm.)"
  (:refer-clojure :exclude [aget aset alength byte-array short-array int-array float-array
                            double-array object-array into-array unchecked-byte
                            unchecked-int unsigned-bit-shift-right long double locking bytes
                            shorts ints floats doubles objects defdelay])
  (:require
   [eve.host :refer [aget aset alength byte-array short-array int-array float-array
                     double-array object-array into-array unchecked-byte unchecked-int
                     unsigned-bit-shift-right long double locking bytes shorts ints floats
                     doubles objects defdelay]]
   [eve.deftype-proto.alloc :as alloc
    :refer [ISlabIO ISlabRegion NIL_OFFSET OVERFLOW_CLASS_IDX
            decode-block-idx decode-class-idx encode-slab-offset
            -sio-alloc! -sio-cas-i32! -sio-copy-block! -sio-free! -sio-mem-region
            -sio-read-bytes -sio-read-i32 -sio-read-u16 -sio-read-u8
            -sio-write-bytes! -sio-write-i32! -sio-write-u16! -sio-write-u8!]]
   [eve.deftype-proto.coalesc :as coalesc]
   [eve.deftype-proto.data :as d]
   [eve.deftype-proto.serialize :as ser]
   [eve.region :as region]
   [eve.mem :as mem]
   [eve.codec :as codec]
   [eve.deftype-proto.wasm :as wasm]
   [eve.wasm-mem :as wasm-mem]))

;; Forward declarations
(declare init-root-sab!)
(declare mmap-coalesc-region)
(declare grow-mmap-coalesc!)
(declare grow-mmap-slab!)
(declare grow-sab-coalesc!)
(declare refresh-mmap-coalesc!)

;; Cached per-slab layout info (set during init-slab!)
;; Slots 0-5: slab classes. Slot 6: overflow (legacy SAB).
(def ^:private slab-data-offsets   #js [0 0 0 0 0 0 0])
(def ^:private slab-bitmap-offsets #js [0 0 0 0 0 0 0])
(def ^:private slab-total-blocks   #js [0 0 0 0 0 0 0])
;; File paths for mmap-backed slabs (needed for lazy growth)
(def ^:private slab-file-paths     #js [nil nil nil nil nil nil nil])
;; Bitmap file paths for mmap split-file slabs
(def ^:private slab-bitmap-paths   #js [nil nil nil nil nil nil nil])
;; Each bitmap class's descriptor COUNT column (data.cljc: the slab's own region for a SAB slab, its
;; .slabN.desc-count file for an mmap one) and where the column starts; the FLAGS column shares the slab's
;; region on a SAB slab (right after the COUNT column) or has its own .slabN.desc-flags file on an mmap one;
;; the POINTER column (the digest-cache side-block pointer) follows the same rule, after FLAGS
(def ^:private slab-desc-regions   #js [nil nil nil nil nil nil nil])
(def ^:private slab-desc-flags-regions #js [nil nil nil nil nil nil nil])
(def ^:private slab-desc-pointer-regions #js [nil nil nil nil nil nil nil])
(def ^:private slab-desc-offsets   #js [0 0 0 0 0 0 0])

(defn- desc-handle
  "The tally handle of block block-idx of bitmap class ci: [[count-region count-off]
   [flags-region flags-off] [pointer-region pointer-off]] — the addresses of its COUNT, FLAGS
   and POINTER column entries — or nil when the class carries no columns."
  [ci block-idx]
  (when-let [dr (aget slab-desc-regions ci)]
    (let [fr   (aget slab-desc-flags-regions ci)
          pr   (aget slab-desc-pointer-regions ci)
          off  (aget slab-desc-offsets ci)
          ;; a SAB slab carries all three columns in its own region (FLAGS right after COUNT,
          ;; POINTER right after FLAGS); an mmap class's flags and pointer regions are their
          ;; own files, their columns at offset 0 there
          foff (if (identical? fr dr)
                 (+ off (* 4 (aget slab-total-blocks ci)))
                 off)
          poff (if (identical? pr dr)
                 (+ off (* 8 (aget slab-total-blocks ci)))
                 off)]
      [[dr (+ off (* 4 block-idx))]
       [fr (+ foff (* 4 block-idx))]
       [pr (+ poff (* 4 block-idx))]])))

(defn slab-offset->byte-offset
  "Convert a slab-qualified offset to the actual byte offset within the slab's SAB."
  ^number [^number slab-offset]
  (let [class-idx  (decode-class-idx slab-offset)
        block-idx  (decode-block-idx slab-offset)
        block-size (d/slab-size class-idx)]
    (+ (aget slab-data-offsets class-idx) (* block-idx block-size))))

(defn get-slab-data-offset
  "Get the cached data offset for a slab class. For debugging."
  [class-idx]
  (aget slab-data-offsets class-idx))

(defn resolve-slab-mem-region
  "Resolve the IMemRegion and absolute byte offset for a slab block field.
   Returns [region absolute-byte-off].
   Used by EveArray atomic ops (cas!, add!, wait!, notify!) to route
   through IMemRegion without assuming SAB or mmap backing."
  [^number slab-offset ^number field-off]
  (let [class-idx (decode-class-idx slab-offset)
        inst (wasm/get-slab-instance class-idx)
        region (:region inst)
        byte-base (slab-offset->byte-offset slab-offset)]
    [region (+ byte-base field-off)]))

;; Overflow (class 6) — pluggable alloc/free callbacks
(def ^:private ^:mutable overflow-alloc-fn nil)
(def ^:private ^:mutable overflow-free-fn nil)
(def ^:private overflow-desc-map (js/Map.))

;; Diagnostic hooks
(def ^:private ^:mutable alloc-hook nil)
(def ^:private ^:mutable recycle-hook nil)

(defn register-alloc-hook!
  "Register a callback invoked after every successful allocation."
  [hook-fn]
  (set! alloc-hook hook-fn))

(defn register-recycle-hook!
  "Register a callback invoked before every free operation."
  [hook-fn]
  (set! recycle-hook hook-fn))

(defn clear-diagnostic-hooks!
  "Clear all diagnostic hooks."
  []
  (set! alloc-hook nil)
  (set! recycle-hook nil))

(defn register-overflow-fns!
  "Register overflow alloc/free callbacks for blocks > 1024 bytes.
   alloc-fn: (fn [size-bytes] => {:offset n :descriptor-idx n} | {:error ...})
   free-fn:  (fn [descriptor-idx] => any)
   Optionally pass :sab to register the overflow SAB for WASM access."
  [alloc-fn free-fn & {:keys [sab]}]
  (set! overflow-alloc-fn alloc-fn)
  (set! overflow-free-fn free-fn)
  (when sab (wasm/register-overflow-instance! sab)))

(defn reset-overflow-fns!
  "Clear overflow callbacks and descriptor map."
  []
  (set! overflow-alloc-fn nil)
  (set! overflow-free-fn nil)
  (.clear overflow-desc-map))

;; -----------------------------------------------------------------------
;; Slab Lifecycle
;; -----------------------------------------------------------------------

(defn init-slab!
  "Initialize a single slab for a given class index.
   Returns a Promise that resolves when the slab (+ optional WASM) is ready.
   growable? (where SharedArrayBuffers grow): the slab is a growable
   SharedArrayBuffer (wasm/create-slab-memory) at its class's initial capacity,
   doubling on out-of-memory up to capacity (grow-sab-slab!), as an mmap-backed
   slab's files do; else it holds capacity from the start."
  [class-idx & {:keys [capacity growable?]}]
  (let [capacity   (or capacity (d/default-capacity-for-class class-idx))
        block-size (d/slab-size class-idx)
        layout     (d/slab-layout block-size capacity)
        {:keys [total-bytes bitmap-offset bitmap-size desc-offset data-offset total-blocks]} layout
        initial-blocks (-> (quot (d/initial-capacity-for-class class-idx) block-size) (max 1) (min total-blocks))
        wasm-memory (wasm/create-slab-memory total-bytes (if growable?
                                                           (+ data-offset (* initial-blocks block-size))
                                                           total-bytes))
        sab         (if (instance? js/SharedArrayBuffer wasm-memory)
                      wasm-memory
                      (.-buffer wasm-memory))
        blocks      (if (.-growable ^js sab) initial-blocks total-blocks)
        slab-region (region/js-sab-region sab)
        u8-view     (js/Uint8Array. sab)]
    ;; Write slab header via IMemRegion
    (mem/store-i32! slab-region d/SLAB_HDR_MAGIC d/SLAB_MAGIC)
    (mem/store-i32! slab-region d/SLAB_HDR_BLOCK_SIZE block-size)
    (mem/store-i32! slab-region d/SLAB_HDR_TOTAL_BLOCKS blocks)
    (mem/store-i32! slab-region d/SLAB_HDR_FREE_COUNT blocks)
    (mem/store-i32! slab-region d/SLAB_HDR_ALLOC_CURSOR 0)
    (mem/store-i32! slab-region d/SLAB_HDR_CLASS_IDX class-idx)
    (mem/store-i32! slab-region d/SLAB_HDR_BITMAP_OFFSET bitmap-offset)
    (mem/store-i32! slab-region d/SLAB_HDR_DATA_OFFSET data-offset)
    ;; Zero the bitmap (all blocks free = all bits 0)
    (.fill u8-view 0 bitmap-offset (+ bitmap-offset bitmap-size))
    ;; Cache layout info
    (aset slab-data-offsets class-idx data-offset)
    (aset slab-bitmap-offsets class-idx bitmap-offset)
    (aset slab-total-blocks class-idx blocks)
    ;; its descriptor columns, between the bitmap and the data (a fresh memory: every entry virgin)
    (aset slab-desc-regions class-idx slab-region)
    (aset slab-desc-flags-regions class-idx slab-region)
    (aset slab-desc-pointer-regions class-idx slab-region)
    (aset slab-desc-offsets class-idx desc-offset)
    ;; Initialize WASM instance for this slab
    (wasm/init-slab-instance! class-idx wasm-memory)))

(def ^:private ^:mutable initialized? false)

(declare init-sab-coalesc!)

(defn init!
  "Initialize all 6 slab classes + class 6 coalesc + root SAB.
   Returns a Promise that resolves when all WASM upgrades are done.
   growable? (default wasm/grow-slabs?: in a browser): the slabs and the
   overflow region start small and grow (init-slab!, init-sab-coalesc!)."
  [& {:keys [capacities force growable?] :or {capacities {} growable? (wasm/grow-slabs?)}}]
  (if (and initialized? (not force))
    (js/Promise.resolve nil)
    (do
      (set! initialized? true)
      (let [slab-promises
            (into-array
              (for [i (range d/NUM_SLAB_CLASSES)]
                (let [cap (get capacities i (d/default-capacity-for-class i))]
                  (init-slab! i :capacity cap :growable? growable?))))]
        (init-root-sab!)
        ;; Initialize SAB-backed class 6 coalesc for overflow (>1024 byte) allocations
        (init-sab-coalesc! :growable? growable?)
        ;; Register typed array resolver for deserializer
        (codec/set-typed-array-resolver!
          (fn [slab-offset]
            (let [class-idx (decode-class-idx slab-offset)
                  block-idx (decode-block-idx slab-offset)
                  inst      (wasm/get-slab-instance class-idx)
                  sab       (when inst (.-buffer ^js (:u8 inst)))]
              (when sab
                (let [block-size (d/slab-size class-idx)
                      data-off   (aget slab-data-offsets class-idx)
                      base       (+ data-off (* block-idx block-size))
                      ;; Apply same 16-byte alignment as typed-array encoder
                      aligned    (bit-and (+ base 15) (bit-not 15))]
                  {:sab sab :base aligned})))))
        (js/Promise.all slab-promises)))))

(defn reset-all-slabs!
  "Reset all slab bitmaps to free state. Nuclear reset for test runners."
  []
  (dotimes [class-idx d/NUM_SLAB_CLASSES]
    (when-let [inst (wasm/get-slab-instance class-idx)]
      (let [region   (:region inst)
            u8-view  (:u8 inst)
            bm-offset (aget slab-bitmap-offsets class-idx)
            total     (aget slab-total-blocks class-idx)
            bm-size   (quot (+ total 7) 8)]
        (.fill u8-view 0 bm-offset (+ bm-offset bm-size))
        ;; the descriptor columns go back to virgin with the bitmap (a re-mint of the slab's management records)
        (when (identical? (aget slab-desc-regions class-idx) region)
          (let [dt (aget slab-desc-offsets class-idx)]
            (.fill u8-view 0 dt (+ dt (d/desc-table-byte-size total)))))
        (mem/store-i32! region d/SLAB_HDR_FREE_COUNT total)
        (mem/store-i32! region d/SLAB_HDR_ALLOC_CURSOR 0)))))

;; -----------------------------------------------------------------------
;; Root SAB (control plane)
;; -----------------------------------------------------------------------

(defonce ^:private root-sab    (cljs.core/atom nil))
(defonce ^:private root-i32    (cljs.core/atom nil))
(defonce ^:private root-region (cljs.core/atom nil))

(defn init-root-sab!
  "Create the root/control SharedArrayBuffer."
  []
  (let [sab (js/SharedArrayBuffer. d/ROOT_SAB_SIZE)
        i32 (js/Int32Array. sab)
        rgn (region/js-sab-region sab)]
    (mem/store-i32! rgn d/ROOT_MAGIC_OFFSET d/ROOT_MAGIC)
    (mem/store-i32! rgn d/ROOT_ATOM_PTR_OFFSET NIL_OFFSET)
    (mem/store-i32! rgn d/ROOT_EPOCH_OFFSET 1)
    (mem/store-i32! rgn d/ROOT_WORKER_REG_OFFSET d/ROOT_WORKER_REGISTRY_START)
    (dotimes [slot-idx d/MAX_WORKERS]
      (let [slot-byte-offset (+ d/ROOT_WORKER_REGISTRY_START (* slot-idx d/WORKER_SLOT_SIZE))]
        (mem/store-i32! rgn slot-byte-offset d/WORKER_STATUS_INACTIVE)))
    (reset! root-sab sab)
    (reset! root-i32 i32)
    (reset! root-region rgn)
    {:root-sab sab :root-i32 i32}))

(defn init-root-sab-from-existing!
  "Register an existing root SAB (created by main thread) on a worker."
  [sab]
  (let [i32 (js/Int32Array. sab)
        rgn (region/js-sab-region sab)]
    (reset! root-sab sab)
    (reset! root-i32 i32)
    (reset! root-region rgn)
    {:root-sab sab :root-i32 i32}))

(defn get-root-sab
  "Get the root SAB for sharing with workers."
  []
  @root-sab)

;; -----------------------------------------------------------------------
;; Worker-Side Slab Initialization
;; -----------------------------------------------------------------------

(defn get-all-slab-sabs
  "Extract all 6 slab SharedArrayBuffers for passing to workers."
  []
  (let [sabs #js []]
    (dotimes [i d/NUM_SLAB_CLASSES]
      (.push sabs (wasm/slab-buffer i)))
    sabs))

(defn populate-slab-caches-from-header!
  "Read a slab's header and populate module-level caches."
  [class-idx sab]
  (let [sab-region    (region/js-sab-region sab)
        data-offset   (mem/load-i32 sab-region d/SLAB_HDR_DATA_OFFSET)
        bitmap-offset (mem/load-i32 sab-region d/SLAB_HDR_BITMAP_OFFSET)
        total-blocks  (mem/load-i32 sab-region d/SLAB_HDR_TOTAL_BLOCKS)]
    (aset slab-data-offsets class-idx data-offset)
    (aset slab-bitmap-offsets class-idx bitmap-offset)
    (aset slab-total-blocks class-idx total-blocks)
    (aset slab-desc-regions class-idx sab-region)
    (aset slab-desc-flags-regions class-idx sab-region)
    (aset slab-desc-pointer-regions class-idx sab-region)
    (aset slab-desc-offsets class-idx (d/slab-desc-offset bitmap-offset total-blocks))))

(defn init-worker-slabs!
  "Initialize the slab system on a worker from existing SABs."
  [slab-sabs root-sab-arg legacy-sab]
  (set! initialized? true)
  (dotimes [i d/NUM_SLAB_CLASSES]
    (let [sab (if (array? slab-sabs) (aget slab-sabs i) (nth slab-sabs i))]
      (wasm/register-slab-instance-from-sab! i sab)
      (populate-slab-caches-from-header! i sab)))
  (when legacy-sab
    (wasm/register-overflow-instance! legacy-sab))
  (init-root-sab-from-existing! root-sab-arg))

;; -----------------------------------------------------------------------
;; Allocator: Slab-Routed Alloc / Free
;; -----------------------------------------------------------------------

(defn- ensure-sab-length!
  "Grow a growable SharedArrayBuffer to at least n bytes (another thread may have grown it past n first)."
  [^js sab n]
  (when (< (.-byteLength sab) n)
    (try (.grow sab n)
         (catch :default e
           (when (< (.-byteLength sab) n) (throw e))))))

(defn- grow-sab-slab!
  "Double a slab in a growable SharedArrayBuffer, up to the blocks its buffer's maximum holds: the buffer grows first
   and the header's total_blocks is CASed after, so no thread reads a total past the buffer's end. true when grown,
   or when another thread had grown it (the cached total refreshed); false at its maximum, or for a slab that does
   not grow."
  [class-idx]
  (let [inst    (wasm/get-slab-instance class-idx)
        ^js sab (when inst (.-buffer ^js (:u8 inst)))]
    (if-not (and sab (.-growable sab))
      false
      (let [region      (:region inst)
            block-size  (d/slab-size class-idx)
            data-offset (aget slab-data-offsets class-idx)
            cached      (aget slab-total-blocks class-idx)
            hdr-total   (mem/load-i32 region d/SLAB_HDR_TOTAL_BLOCKS)
            max-blocks  (quot (- (.-maxByteLength sab) data-offset) block-size)]
        (cond
          (> hdr-total cached) (do (aset slab-total-blocks class-idx hdr-total) true)
          (>= hdr-total max-blocks) false
          :else
          (let [new-blocks (min (* 2 hdr-total) max-blocks)
                _          (ensure-sab-length! sab (+ data-offset (* new-blocks block-size)))
                witness    (mem/-cas-i32! region d/SLAB_HDR_TOTAL_BLOCKS hdr-total new-blocks)]
            (if (== witness hdr-total)
              (do (mem/add-i32! region d/SLAB_HDR_FREE_COUNT (- new-blocks hdr-total))
                  (aset slab-total-blocks class-idx new-blocks))
              (aset slab-total-blocks class-idx witness))
            true))))))

(defn- grow-slab!
  "Grow class-idx's slab after an out-of-memory: an mmap-backed slab's files, a growable SharedArrayBuffer's slab its
   buffer. false when it cannot grow."
  [class-idx]
  (if (aget slab-file-paths class-idx)
    (grow-mmap-slab! class-idx)
    (grow-sab-slab! class-idx)))

(defn- alloc-from-slab
  "Try to allocate a block from a specific slab class.
   Returns {:offset :class-idx :block-idx} or {:error :out-of-memory}."
  [class-idx]
  (let [bm-offset  (aget slab-bitmap-offsets class-idx)
        total-bits (aget slab-total-blocks class-idx)
        inst       (wasm/get-slab-instance class-idx)
        region     (:region inst)
        cursor     (mem/load-i32 region d/SLAB_HDR_ALLOC_CURSOR)]
    (loop [start-bit cursor
           wrapped?  false]
      (let [candidate (wasm/bitmap-find-free class-idx bm-offset total-bits start-bit)]
        (cond
          (not= candidate -1)
          (if (wasm/bitmap-alloc-cas! class-idx bm-offset candidate)
            (let [slab-offset (encode-slab-offset class-idx candidate)]
              (mem/store-i32! region d/SLAB_HDR_ALLOC_CURSOR
                              (mod (inc candidate) total-bits))
              (mem/sub-i32! region d/SLAB_HDR_FREE_COUNT 1)
              (when alloc-hook (alloc-hook slab-offset))
              {:offset slab-offset :class-idx class-idx :block-idx candidate})
            (recur (inc candidate) wrapped?))

          (and (not wrapped?) (pos? cursor))
          (recur 0 true)

          :else
          {:error :out-of-memory :class-idx class-idx})))))

(defn alloc
  "Allocate a block of at least size-bytes.
   Returns {:offset :class-idx :block-idx} or {:error ...}.
   A slab that grows (mmap-backed, or in a growable SharedArrayBuffer) grows on OOM and retries."
  [size-bytes]
  (let [class-idx (d/size->class-idx size-bytes)]
    (if (== class-idx -1)
      ;; Overflow path — prefer mmap coalesc if registered, else legacy
      (if mmap-coalesc-region
        (let [byte-off (try
                         (coalesc/coalesc-alloc! mmap-coalesc-region size-bytes)
                         (catch :default _
                           ;; OOM — grow in a loop until alloc succeeds or at max
                           (loop [attempts 0]
                             (let [grew? (or (grow-mmap-coalesc!) (grow-sab-coalesc!))]
                               (if-let [result (try
                                                 (coalesc/coalesc-alloc! mmap-coalesc-region size-bytes)
                                                 (catch :default _ nil))]
                                 result
                                 (if (and grew? (< attempts 20))
                                   (recur (inc attempts))
                                   (throw (ex-info "coalesc-alloc!: out of memory after growth"
                                                   {:size size-bytes}))))))))
              slab-offset (encode-slab-offset OVERFLOW_CLASS_IDX byte-off)]
          (when alloc-hook (alloc-hook slab-offset))
          {:offset slab-offset :class-idx OVERFLOW_CLASS_IDX :block-idx byte-off})
        (if overflow-alloc-fn
          (let [result (overflow-alloc-fn size-bytes)]
            (if (:error result)
              {:error :overflow-oom :size size-bytes}
              (let [byte-off    (:offset result)
                    desc-idx    (:descriptor-idx result)
                    slab-offset (encode-slab-offset OVERFLOW_CLASS_IDX byte-off)]
                (.set overflow-desc-map byte-off desc-idx)
                (when alloc-hook (alloc-hook slab-offset))
                {:offset slab-offset :class-idx OVERFLOW_CLASS_IDX :block-idx byte-off})))
          {:error :overflow-no-legacy-env :size size-bytes}))
      ;; Slab path — try alloc, grow on OOM (loop up to 20x), spill to next class
      (loop [ci class-idx]
        (let [result (alloc-from-slab ci)]
          (if-not (:error result)
            result
            ;; OOM — try to grow in a loop (mmap-backed, or a growable SharedArrayBuffer)
            (let [grew-and-allocated
                  (loop [attempts 0]
                    (when (and (< attempts 20) (grow-slab! ci))
                      (let [retry (alloc-from-slab ci)]
                        (if-not (:error retry)
                          retry
                          (recur (inc attempts))))))]
              (if grew-and-allocated
                grew-and-allocated
                ;; Can't grow or still full — spill to next class
                (if (< ci 5)
                  (recur (inc ci))
                  {:error :all-slab-classes-full
                   :size size-bytes})))))))))

(defn alloc-offset
  "Like alloc but returns just the slab-qualified offset, or throws on error."
  ^number [^number size-bytes]
  (let [result (alloc size-bytes)]
    (if (:error result)
      (throw (js/Error. (str "Slab alloc failed: " (:error result)
                             " for " size-bytes " bytes")))
      (:offset result))))

(defn free!
  "Free a block identified by its slab-qualified offset.
   Returns true if freed, false if already free (double-free detected)."
  [^number slab-offset]
  (if (== slab-offset NIL_OFFSET)
    false
    (do
      (when recycle-hook (recycle-hook slab-offset))
      (let [class-idx (decode-class-idx slab-offset)]
        (if (== class-idx OVERFLOW_CLASS_IDX)
          (if mmap-coalesc-region
            ;; mmap coalescing path
            (let [byte-off (decode-block-idx slab-offset)]
              (coalesc/coalesc-free! mmap-coalesc-region byte-off))
            ;; legacy SAB path
            (let [byte-off (decode-block-idx slab-offset)
                  desc-idx (.get overflow-desc-map byte-off)]
              (if (and overflow-free-fn (some? desc-idx))
                (do
                  (overflow-free-fn desc-idx)
                  (.delete overflow-desc-map byte-off)
                  true)
                false)))
          (let [block-idx (decode-block-idx slab-offset)
                bm-offset (aget slab-bitmap-offsets class-idx)
                inst      (wasm/get-slab-instance class-idx)
                region    (:region inst)
                ;; the block's descriptor is reset BEFORE its bit clears: its count 0, its flags
                ;; clear and its digest pointer 0 (its own columns)
                _         (when-let [[[cr co] [fr fo] [pr po]] (desc-handle class-idx block-idx)]
                            (mem/store-i32! cr co 0)
                            (mem/store-i32! fr fo 0)
                            (mem/store-i32! pr po 0))
                freed?    (wasm/bitmap-free! class-idx bm-offset block-idx)]
            (when freed?
              (mem/add-i32! region d/SLAB_HDR_FREE_COUNT 1))
            freed?))))))

(defn- batch-alloc-once
  "Single-pass batch allocation without growth. Returns a JS array."
  [class-idx max-count]
  (let [bm-offset  (aget slab-bitmap-offsets class-idx)
        total-bits (aget slab-total-blocks class-idx)
        inst       (wasm/get-slab-instance class-idx)
        region     (:region inst)
        cursor     (mem/load-i32 region d/SLAB_HDR_ALLOC_CURSOR)
        results    #js []]
    (loop [start-bit cursor wrapped? false]
      (when (< (.-length results) max-count)
        (let [candidate (wasm/bitmap-find-free class-idx bm-offset total-bits start-bit)]
          (cond
            (not= candidate -1)
            (if (wasm/bitmap-alloc-cas! class-idx bm-offset candidate)
              (do (.push results (encode-slab-offset class-idx candidate))
                  (recur (inc candidate) wrapped?))
              (recur (inc candidate) wrapped?))

            (and (not wrapped?) (pos? cursor))
            (recur 0 true)

            :else nil))))
    (when (pos? (.-length results))
      (let [last-offset (aget results (dec (.-length results)))
            last-block  (decode-block-idx last-offset)]
        (mem/store-i32! region d/SLAB_HDR_ALLOC_CURSOR
                        (mod (inc last-block) total-bits))
        (mem/sub-i32! region d/SLAB_HDR_FREE_COUNT (.-length results))))
    results))

(defn batch-alloc
  "Allocate up to max-count blocks of size-bytes each.
   Returns a JS array of slab-qualified offsets.
   A slab that grows (mmap-backed, or in a growable SharedArrayBuffer) grows on OOM and retries."
  [size-bytes max-count]
  (let [class-idx (d/size->class-idx size-bytes)]
    (when (not= class-idx -1)
      (loop [attempts 0]
        (let [results (batch-alloc-once class-idx max-count)]
          (if (pos? (.-length results))
            results
            ;; OOM — try growth (up to 20 doublings)
            (when (and (< attempts 20)
                       (grow-slab! class-idx))
              (recur (inc attempts)))))))))

;; -----------------------------------------------------------------------
;; DataView-based Data Access  (CLJS hot path)
;; -----------------------------------------------------------------------
;; These use module-level mutable DataView state for maximum throughput.
;; Used by existing CLJS HAMT code (map.cljs etc.) until those files are
;; migrated to .cljc with ISlabIO in Steps 5-8.

;; Mutable scratch for resolve — avoids allocating a JS array per resolve.
;; Public so macro-generated code in eve-slab-deftype can access them.
(def ^:mutable resolved-dv nil)
(def ^:mutable resolved-u8 nil)
(def ^:mutable resolved-base 0)

;; The open domain's keystream (eve.keyring's), or nil for a plain domain. On ClojureScript the module slabs
;; ARE the open domain, read through DataViews, so its keystream is theirs: every block byte this module and
;; the eve types' accessors (eve.types-js) read or write passes through it. Metadata — the slab headers and
;; bitmaps, the overflow class's descriptors, the root — stays plaintext, as on every host. Saved and put back
;; with the module slabs (an mmap domain opened over another, then released).
;;   domain-ks        (fn [o f w]) → the w keystream bytes at block o's field offset f, an unsigned LE integer
;;   domain-ks-bytes  (fn [o f n]) → those n bytes, a vector
(def ^:mutable domain-ks nil)
(def ^:mutable domain-ks-bytes nil)

(defn- ks-xor-copy
  "A new Uint8Array: the bytes of u8 (block o's from field offset f) XOR the
   keystream there."
  [o f ^js u8]
  (let [n (.-length u8)
        ks (domain-ks-bytes o f n)
        out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (bit-xor (aget u8 i) (nth ks i))))
    out))

(defn resolve-dv!
  "Resolve a slab-qualified offset to a DataView and base byte offset.
   Sets module-level resolved-dv and resolved-base for subsequent reads.
   Returns the base byte offset."
  ^number [^number slab-offset]
  (let [class-idx  (decode-class-idx slab-offset)
        block-idx  (decode-block-idx slab-offset)
        block-size (d/slab-size class-idx)
        data-off   (aget slab-data-offsets class-idx)
        base       (+ data-off (* block-idx block-size))
        dv         (wasm/slab-data-view class-idx)]
    (when (nil? dv)
      (js/console.error "[resolve-dv! ERROR] nil DataView slab-offset:" slab-offset
                        "class:" class-idx "block:" block-idx
                        "block-size:" block-size "data-off:" data-off
                        "base:" base))
    (set! resolved-dv dv)
    (set! resolved-base base)
    base))

(defn resolve-u8!
  "Like resolve-dv! but also sets resolved-u8 (Uint8Array) for byte ops."
  ^number [^number slab-offset]
  (let [class-idx  (decode-class-idx slab-offset)
        block-idx  (decode-block-idx slab-offset)
        block-size (d/slab-size class-idx)
        base       (+ (aget slab-data-offsets class-idx) (* block-idx block-size))]
    (set! resolved-u8   (wasm/slab-u8-view class-idx))
    (set! resolved-dv   (wasm/slab-data-view class-idx))
    (set! resolved-base base)
    base))

(defn read-u8
  "Read a byte from a slab-qualified offset + byte offset within block."
  ^number [^number slab-offset ^number byte-off]
  (let [class-idx (decode-class-idx slab-offset)
        u8        (wasm/slab-u8-view class-idx)
        base      (slab-offset->byte-offset slab-offset)
        v         (aget u8 (+ base byte-off))]
    (if (nil? domain-ks) v (bit-xor v (domain-ks slab-offset byte-off 1)))))

(defn read-header-type-byte
  "Read the type-id byte at offset 0 of the slab block at slab-qualified offset."
  ^number [^number slab-off]
  (if (nil? domain-ks)
    (do (resolve-u8! slab-off)
        (.getUint8 resolved-dv resolved-base))
    (read-u8 slab-off 0)))

(defn write-u8!
  "Write a byte to a slab-qualified offset + byte offset within block."
  [^number slab-offset ^number byte-off ^number val]
  (let [class-idx (decode-class-idx slab-offset)
        u8        (wasm/slab-u8-view class-idx)
        base      (slab-offset->byte-offset slab-offset)]
    (aset u8 (+ base byte-off)
          (if (nil? domain-ks) val (bit-xor (bit-and val 0xFF) (domain-ks slab-offset byte-off 1))))))

(defn read-i32
  "Read an i32 from a slab-qualified offset + byte offset within block."
  ^number [^number slab-offset ^number byte-off]
  (let [class-idx (decode-class-idx slab-offset)
        dv        (wasm/slab-data-view class-idx)
        base      (slab-offset->byte-offset slab-offset)
        v         (.getInt32 dv (+ base byte-off) true)]
    (if (nil? domain-ks) v (bit-xor v (domain-ks slab-offset byte-off 4)))))

(defn write-i32!
  "Write an i32 to a slab-qualified offset + byte offset within block."
  [^number slab-offset ^number byte-off ^number val]
  (let [class-idx (decode-class-idx slab-offset)
        dv        (wasm/slab-data-view class-idx)
        base      (slab-offset->byte-offset slab-offset)]
    (.setInt32 dv (+ base byte-off)
               (if (nil? domain-ks) val (bit-xor val (domain-ks slab-offset byte-off 4))) true)))

(defn read-u16
  "Read a u16 from a slab-qualified offset."
  ^number [^number slab-offset ^number byte-off]
  (let [class-idx (decode-class-idx slab-offset)
        dv        (wasm/slab-data-view class-idx)
        base      (slab-offset->byte-offset slab-offset)
        v         (.getUint16 dv (+ base byte-off) true)]
    (if (nil? domain-ks) v (bit-xor v (domain-ks slab-offset byte-off 2)))))

(defn write-u16!
  "Write a u16 to a slab-qualified offset."
  [^number slab-offset ^number byte-off ^number val]
  (let [class-idx (decode-class-idx slab-offset)
        dv        (wasm/slab-data-view class-idx)
        base      (slab-offset->byte-offset slab-offset)]
    (.setUint16 dv (+ base byte-off)
                (if (nil? domain-ks) val (bit-xor (bit-and val 0xFFFF) (domain-ks slab-offset byte-off 2))) true)))

(defn read-bytes
  "Read a Uint8Array slice from a slab-qualified offset (a view of the slab;
   under a domain keystream, a decrypted copy)."
  [^number slab-offset ^number byte-off ^number len]
  (let [class-idx (decode-class-idx slab-offset)
        u8        (wasm/slab-u8-view class-idx)
        base      (slab-offset->byte-offset slab-offset)
        view      (.subarray u8 (+ base byte-off) (+ base byte-off len))]
    (if (nil? domain-ks) view (ks-xor-copy slab-offset byte-off view))))

(defn write-bytes!
  "Write a Uint8Array to a slab-qualified offset."
  [^number slab-offset ^number byte-off ^js src-bytes]
  (let [class-idx (decode-class-idx slab-offset)
        u8        (wasm/slab-u8-view class-idx)
        base      (slab-offset->byte-offset slab-offset)]
    (.set u8 (if (nil? domain-ks) src-bytes (ks-xor-copy slab-offset byte-off src-bytes)) (+ base byte-off))))

(defn copy-within-slab!
  "Copy bytes within the same slab."
  [^number dst-slab-offset ^number dst-byte-off
   ^number src-slab-offset ^number src-byte-off
   ^number len]
  (if (some? domain-ks)
    ;; the keystream is the bytes' place's: decrypted from the one, encrypted to the other
    (write-bytes! dst-slab-offset dst-byte-off (read-bytes src-slab-offset src-byte-off len))
    (let [class-idx (decode-class-idx dst-slab-offset)
          u8        (wasm/slab-u8-view class-idx)
          dst-base  (slab-offset->byte-offset dst-slab-offset)
          src-base  (slab-offset->byte-offset src-slab-offset)]
      (.copyWithin u8 (+ dst-base dst-byte-off) (+ src-base src-byte-off)
                   (+ src-base src-byte-off len)))))

(defn copy-block!
  "Copy an entire block from one slab-qualified offset to another."
  [^number dst-slab-offset ^number src-slab-offset ^number len]
  (let [src-class (decode-class-idx src-slab-offset)
        dst-class (decode-class-idx dst-slab-offset)]
    (if (== src-class dst-class)
      (copy-within-slab! dst-slab-offset 0 src-slab-offset 0 len)
      (let [src-bytes (read-bytes src-slab-offset 0 len)]
        (write-bytes! dst-slab-offset 0 src-bytes)))))

;; An eve type's field under the domain keystream (eve.types-js's accessors take these when domain-ks is set;
;; kind is the field's type hint)

(defn- kind-width [kind]
  (case kind (:int8 :uint8) 1 (:int16 :uint16) 2 :float64 8 4))

(defn ks-read
  "Field f of block o, of kind (:int8 :uint8 :int16 :uint16 :int32 :uint32
   :float32 :float64; an eve-type reference is :int32), decrypted."
  [kind o f]
  (let [n (kind-width kind)
        b (read-bytes o f n)
        dv (js/DataView. (.-buffer b) (.-byteOffset b) n)]
    (case kind
      :int8 (.getInt8 dv 0)
      :uint8 (.getUint8 dv 0)
      :int16 (.getInt16 dv 0 true)
      :uint16 (.getUint16 dv 0 true)
      :uint32 (.getUint32 dv 0 true)
      :float32 (.getFloat32 dv 0 true)
      :float64 (.getFloat64 dv 0 true)
      (.getInt32 dv 0 true))))

(defn ks-write!
  "Write v to field f of block o, of kind (as ks-read's), encrypted."
  [kind o f v]
  (let [n (kind-width kind)
        b (js/Uint8Array. n)
        dv (js/DataView. (.-buffer b))]
    (case kind
      :int8 (.setInt8 dv 0 v)
      :uint8 (.setUint8 dv 0 v)
      :int16 (.setInt16 dv 0 v true)
      :uint16 (.setUint16 dv 0 v true)
      :uint32 (.setUint32 dv 0 v true)
      :float32 (.setFloat32 dv 0 v true)
      :float64 (.setFloat64 dv 0 v true)
      (.setInt32 dv 0 v true))
    (write-bytes! o f b)))

(defn ks-atomic-load
  "A volatile i32 field f of block o, loaded atomically, decrypted."
  [o f]
  (let [[region off] (resolve-slab-mem-region o f)]
    (bit-xor (mem/-load-i32 region off) (domain-ks o f 4))))

(defn ks-atomic-store!
  "Store v to the volatile i32 field f of block o atomically, encrypted."
  [o f v]
  (let [[region off] (resolve-slab-mem-region o f)]
    (mem/-store-i32! region off (bit-xor v (domain-ks o f 4)))))

(defn ks-cas-i32!
  "CAS the i32 field f of block o, expected → desired, both under the field's
   keystream word (fixed by the field's place): the old value, decrypted."
  [o f expected desired]
  (let [[region off] (resolve-slab-mem-region o f)
        k (domain-ks o f 4)]
    (bit-xor k (mem/-cas-i32! region off (bit-xor expected k) (bit-xor desired k)))))

(defn alloc-scalar-block!
  "Allocate a slab block for a primitive scalar atom root value.
   Returns slab-qualified offset."
  [v]
  (let [ev-bytes (codec/encode-value nil v) ;; the module slab: no sio to name
        n        (inc (.-length ev-bytes))   ;; 1 type-id byte + EVE bytes
        blk-off  (alloc-offset n)]
    (if (some? domain-ks)
      (do (write-u8! blk-off 0 ser/SCALAR_BLOCK_TYPE_ID)
          (write-bytes! blk-off 1 ev-bytes))
      (do (resolve-dv! blk-off)
          (.setUint8 resolved-dv resolved-base ser/SCALAR_BLOCK_TYPE_ID)
          (dotimes [i (.-length ev-bytes)]
            (.setUint8 resolved-dv (+ resolved-base 1 i) (aget ev-bytes i)))))
    blk-off))

(declare cljs-sio)

(defn read-scalar-block
  "Read the primitive value from a scalar value block.
   Returns the deserialized value."
  [slab-off]
  (let [blk-size (d/slab-size (decode-class-idx slab-off))
        ev-bytes (read-bytes slab-off 1 (dec blk-size))]
    (codec/decode (cljs-sio) ev-bytes)))

;; -----------------------------------------------------------------------
;; CljsSlabIO — ISlabIO backed by DataView  (CLJS only)
;; -----------------------------------------------------------------------

(deftype CljsSlabIO []
  ISlabIO
  (-sio-read-u8   [_ slab-offset field-off]  (read-u8  slab-offset field-off))
  (-sio-write-u8!  [_ slab-offset field-off val] (write-u8!  slab-offset field-off val))
  (-sio-read-u16  [_ slab-offset field-off]  (read-u16 slab-offset field-off))
  (-sio-write-u16! [_ slab-offset field-off val] (write-u16! slab-offset field-off val))
  (-sio-read-i32  [_ slab-offset field-off]  (read-i32 slab-offset field-off))
  (-sio-write-i32! [_ slab-offset field-off val] (write-i32! slab-offset field-off val))
  (-sio-cas-i32! [_ slab-offset field-off expected desired]
    (if (some? domain-ks)
      (ks-cas-i32! slab-offset field-off expected desired)
      (let [[region byte-off] (resolve-slab-mem-region slab-offset field-off)]
        (mem/-cas-i32! region byte-off expected desired))))
  (-sio-read-bytes [_ slab-offset field-off len] (read-bytes slab-offset field-off len))
  (-sio-write-bytes! [_ slab-offset field-off src] (write-bytes! slab-offset field-off src))
  (-sio-alloc!    [_ size-bytes] (alloc-offset size-bytes))
  (-sio-free!     [_ slab-offset] (free! slab-offset))
  (-sio-copy-block! [_ dst-slab-offset dst-field-off src-slab-offset src-field-off len]
    (if (== (decode-class-idx dst-slab-offset) (decode-class-idx src-slab-offset))
      (copy-within-slab! dst-slab-offset dst-field-off src-slab-offset src-field-off len)
      (let [src-bytes (read-bytes src-slab-offset src-field-off len)]
        (write-bytes! dst-slab-offset dst-field-off src-bytes))))
  ISlabRegion
  (-sio-mem-region [_ slab-offset field-off]
    (when (some? domain-ks)
      (throw (js/Error. "eve: an encrypted domain's blocks are read through its slab I/O, never as a region")))
    (resolve-slab-mem-region slab-offset field-off)))

;; The one module-slab token. CljsSlabIO is stateless (it reads the
;; module-level slab state), so one instance serves every caller.
(def ^:private the-cljs-sio (CljsSlabIO.))

(defn cljs-sio
  "The CLJS slab I/O token: the module slab every CLJS Eve value lives in."
  []
  the-cljs-sio)

;; The default a builder given no slab I/O allocates in is alloc's current-sio — inside a swap!'s f and while the
;; swap writes its new value, the attempt's logging sio over the atom's domain; otherwise this module slab (the
;; :default-sio host op below). Registering cljs-sio here replaced it, so a swap's nested host collections were
;; allocated past the attempt's log: a lost CAS never freed them, and the commit's walk took them for existing
;; pieces.

;; -----------------------------------------------------------------------
;; Root Pointer Operations
;; -----------------------------------------------------------------------

(defn read-root-ptr
  "Read the atom root pointer (a slab-qualified offset)."
  ^number []
  (mem/load-i32 @root-region d/ROOT_ATOM_PTR_OFFSET))

(defn cas-root-ptr!
  "CAS the atom root pointer. Returns true on success."
  [^number expected ^number new-val]
  (== expected
      (mem/cas-i32! @root-region d/ROOT_ATOM_PTR_OFFSET expected new-val)))

;; -----------------------------------------------------------------------
;; Epoch Management
;; -----------------------------------------------------------------------

(defn get-current-epoch
  "Read the current global epoch."
  ^number []
  (mem/load-i32 @root-region d/ROOT_EPOCH_OFFSET))

(defn increment-epoch!
  "Atomically increment global epoch. Returns the new epoch."
  ^number []
  (inc (mem/add-i32! @root-region d/ROOT_EPOCH_OFFSET 1)))

;; -----------------------------------------------------------------------
;; Worker Registry
;; -----------------------------------------------------------------------

(defn- worker-slot-byte-offset
  ^number [^number slot-idx]
  (+ d/ROOT_WORKER_REGISTRY_START (* slot-idx d/WORKER_SLOT_SIZE)))

(defn register-worker!
  "Claim a worker slot. Returns slot index or nil."
  [worker-id]
  (let [rgn @root-region]
    (loop [slot-idx 0]
      (when (< slot-idx d/MAX_WORKERS)
        (let [slot-byte-off (worker-slot-byte-offset slot-idx)]
          (if (== d/WORKER_STATUS_INACTIVE
                  (mem/cas-i32! rgn slot-byte-off
                                d/WORKER_STATUS_INACTIVE
                                d/WORKER_STATUS_ACTIVE))
            (do
              (mem/store-i32! rgn (+ slot-byte-off d/OFFSET_WS_WORKER_ID) worker-id)
              (mem/store-i32! rgn (+ slot-byte-off d/OFFSET_WS_CURRENT_EPOCH) 0)
              slot-idx)
            (recur (inc slot-idx))))))))

(defn unregister-worker!
  "Release a worker slot."
  [slot-idx]
  (when (and (>= slot-idx 0) (< slot-idx d/MAX_WORKERS))
    (let [rgn          @root-region
          slot-byte-off (worker-slot-byte-offset slot-idx)]
      (mem/store-i32! rgn (+ slot-byte-off d/OFFSET_WS_CURRENT_EPOCH) 0)
      (mem/store-i32! rgn slot-byte-off d/WORKER_STATUS_INACTIVE))))

;; -----------------------------------------------------------------------
;; Diagnostic Helpers
;; -----------------------------------------------------------------------

(defn slab-stats
  "Return stats for a slab class."
  [class-idx]
  (let [inst   (wasm/get-slab-instance class-idx)
        region (:region inst)
        total  (aget slab-total-blocks class-idx)
        free   (mem/load-i32 region d/SLAB_HDR_FREE_COUNT)]
    {:block-size   (d/slab-size class-idx)
     :total-blocks total
     :free-count   free
     :used-count   (- total free)}))

(defn all-slab-stats
  "Return stats for all slab classes."
  []
  (into {} (for [i (range d/NUM_SLAB_CLASSES)]
             [i (slab-stats i)])))

;; -----------------------------------------------------------------------
;; mmap-Backed Slab Initialization (Node.js cross-process)
;; -----------------------------------------------------------------------

(defn init-mmap-slab!
  "Initialize a slab class backed by two mmap files on Node.js:
   data file (header + blocks) and bitmap file.
   bitmap-offset is 0 within the bitmap file, data-offset is SLAB_HEADER_SIZE."
  [class-idx path & {:keys [capacity]}]
  (let [capacity    (or capacity (d/initial-capacity-for-class class-idx))
        block-size  (d/slab-size class-idx)
        layout      (d/mmap-slab-layout block-size capacity)
        total-blocks (:total-blocks layout)
        data-bytes   (:data-bytes layout)
        bitmap-bytes (:bitmap-bytes layout)
        bm-path      (str path ".bm")
        region       (region/open-mmap-region path data-bytes)
        bm-region    (region/open-mmap-region bm-path bitmap-bytes)
        desc-region  (region/open-mmap-region (str path ".desc-count") (:desc-column-bytes layout))
        desc-flags-region (region/open-mmap-region (str path ".desc-flags") (:desc-column-bytes layout))
        desc-pointer-region (region/open-mmap-region (str path ".desc-pointer") (:desc-column-bytes layout))]
    ;; Write slab header
    (mem/store-i32! region d/SLAB_HDR_MAGIC d/SLAB_MAGIC)
    (mem/store-i32! region d/SLAB_HDR_BLOCK_SIZE block-size)
    (mem/store-i32! region d/SLAB_HDR_TOTAL_BLOCKS total-blocks)
    (mem/store-i32! region d/SLAB_HDR_FREE_COUNT total-blocks)
    (mem/store-i32! region d/SLAB_HDR_ALLOC_CURSOR 0)
    (mem/store-i32! region d/SLAB_HDR_CLASS_IDX class-idx)
    (mem/store-i32! region d/SLAB_HDR_BITMAP_OFFSET 0)
    (mem/store-i32! region d/SLAB_HDR_DATA_OFFSET d/SLAB_HEADER_SIZE)
    ;; Bitmap is already zeroed by ftruncate (all blocks free)
    ;; Cache layout info + paths for lazy growth
    (aset slab-data-offsets class-idx d/SLAB_HEADER_SIZE)
    (aset slab-bitmap-offsets class-idx 0)
    (aset slab-total-blocks class-idx total-blocks)
    (aset slab-file-paths class-idx path)
    (aset slab-bitmap-paths class-idx bm-path)
    (aset slab-desc-regions class-idx desc-region)
    (aset slab-desc-flags-regions class-idx desc-flags-region)
    (aset slab-desc-pointer-regions class-idx desc-pointer-region)
    (aset slab-desc-offsets class-idx 0)
    ;; Register instance with separate bitmap region
    (wasm/register-mmap-slab-instance! class-idx region bm-region)))

(defn open-mmap-slab!
  "Open (join) existing mmap-backed slab data + bitmap files on Node.js.
   Reads the slab header to determine block count, opens both files,
   and populates the module-level layout caches."
  [class-idx path]
  (let [peek-region  (region/open-mmap-region path 64)
        total-blocks (mem/load-i32 peek-region d/SLAB_HDR_TOTAL_BLOCKS)
        block-size   (d/slab-size class-idx)
        data-bytes   (+ d/SLAB_HEADER_SIZE (* total-blocks block-size))
        bm-path      (str path ".bm")
        bm-bytes     (d/bitmap-byte-size total-blocks)
        region       (region/open-mmap-region path data-bytes)
        bm-region    (region/open-mmap-region bm-path bm-bytes)
        ;; a slab written before the descriptor columns has no column files: opening creates them, every entry zero (virgin)
        desc-region  (region/open-mmap-region (str path ".desc-count") (d/desc-column-byte-size total-blocks))
        desc-flags-region (region/open-mmap-region (str path ".desc-flags") (d/desc-column-byte-size total-blocks))
        desc-pointer-region (region/open-mmap-region (str path ".desc-pointer") (d/desc-column-byte-size total-blocks))]
    ;; Cache layout info + paths for lazy growth
    (aset slab-data-offsets class-idx d/SLAB_HEADER_SIZE)
    (aset slab-bitmap-offsets class-idx 0)
    (aset slab-total-blocks class-idx total-blocks)
    (aset slab-file-paths class-idx path)
    (aset slab-bitmap-paths class-idx bm-path)
    (aset slab-desc-regions class-idx desc-region)
    (aset slab-desc-flags-regions class-idx desc-flags-region)
    (aset slab-desc-pointer-regions class-idx desc-pointer-region)
    (aset slab-desc-offsets class-idx 0)
    ;; Register instance with separate bitmap region
    (wasm/register-mmap-slab-instance! class-idx region bm-region)))

(defn- remap-mmap-slab!
  "Re-mmap both data + bitmap files for class-idx at the given total blocks."
  [class-idx total-blocks]
  (let [path       (aget slab-file-paths class-idx)
        bm-path    (aget slab-bitmap-paths class-idx)
        block-size (d/slab-size class-idx)
        data-bytes (+ d/SLAB_HEADER_SIZE (* total-blocks block-size))
        bm-bytes   (d/bitmap-byte-size total-blocks)
        region     (region/open-mmap-region path data-bytes)
        bm-region  (region/open-mmap-region bm-path bm-bytes)
        desc-region (region/open-mmap-region (str path ".desc-count") (d/desc-column-byte-size total-blocks))
        desc-flags-region (region/open-mmap-region (str path ".desc-flags") (d/desc-column-byte-size total-blocks))
        desc-pointer-region (region/open-mmap-region (str path ".desc-pointer") (d/desc-column-byte-size total-blocks))]
    (aset slab-total-blocks class-idx total-blocks)
    (aset slab-desc-regions class-idx desc-region)
    (aset slab-desc-flags-regions class-idx desc-flags-region)
    (aset slab-desc-pointer-regions class-idx desc-pointer-region)
    (wasm/register-mmap-slab-instance! class-idx region bm-region)))

(defn grow-mmap-slab!
  "Grow an mmap-backed slab using CAS on total_blocks for leader election.
   Only one process wins the CAS and does the ftruncate; losers re-map.
   Both data and bitmap files are grown independently.
   Returns true if grown/refreshed, false if already at max."
  [class-idx]
  (let [path         (aget slab-file-paths class-idx)
        cached-total (aget slab-total-blocks class-idx)
        ;; Peek header — use small region to read current state
        peek-r       (region/open-mmap-region path 64)
        hdr-total    (mem/load-i32 peek-r d/SLAB_HDR_TOTAL_BLOCKS)]
    (if (> hdr-total cached-total)
      ;; Another process grew — just re-map at the header's size
      (do (remap-mmap-slab! class-idx hdr-total)
          true)
      ;; Try to be the growth leader via CAS on total_blocks
      (let [new-blocks (* 2 hdr-total)
            added      (- new-blocks hdr-total)
            witness    (mem/-cas-i32! peek-r d/SLAB_HDR_TOTAL_BLOCKS
                         hdr-total new-blocks)]
        (if (== witness hdr-total)
          ;; We won — extend both files and re-map
          (do (remap-mmap-slab! class-idx new-blocks)
              (let [inst   (wasm/get-slab-instance class-idx)
                    region (:region inst)]
                (mem/add-i32! region d/SLAB_HDR_FREE_COUNT added))
              true)
          ;; Lost CAS — someone else grew, re-map at their size
          (do (remap-mmap-slab! class-idx witness)
              true))))))

(defn refresh-mmap-slabs!
  "Re-map any mmap-backed slabs whose header total_blocks exceeds our
   cached value (i.e. another process grew them). Called before deref
   in cross-process swap! to ensure we can read all blocks."
  []
  (dotimes [ci d/NUM_SLAB_CLASSES]
    (when-let [path (aget slab-file-paths ci)]
      (let [cached (aget slab-total-blocks ci)
            ;; The header is the first bytes of this process's own
            ;; MAP_SHARED mapping of the file, so another process's
            ;; growth is visible through it. Opening a fresh 64-byte
            ;; peek per class per call (as before) leaked a mapping each
            ;; time, up to vm.max_map_count; the JVM's refresh already
            ;; reads through its region.
            cur-r (:region (wasm/get-slab-instance ci))
            hdr-total (mem/load-i32 (or cur-r (region/open-mmap-region path 64))
                                    d/SLAB_HDR_TOTAL_BLOCKS)]
        (when (> hdr-total cached)
          (remap-mmap-slab! ci hdr-total)))))
  ;; Also refresh coalesc region
  (refresh-mmap-coalesc!))

;; -----------------------------------------------------------------------
;; mmap-Backed Coalescing Allocator (class 6, Node.js cross-process)
;; -----------------------------------------------------------------------

(def ^:private ^:mutable mmap-coalesc-region nil)
(def ^:private ^:mutable mmap-coalesc-path nil)
(def ^:private ^:mutable mmap-coalesc-max-data-size 0)
(def ^:private ^:mutable mmap-coalesc-cached-data-size 0)

;; CLJS slabs are module state, so an mmap domain takes them over. The
;; state it replaced is saved here and put back when that domain is
;; released (eve.atom/release-domain!): an in-process atom used after a
;; persistent one has closed then allocates in its own SABs again, not in
;; the closed domain's (possibly deleted) files. One mmap domain at a
;; time per CLJS process, as before.
(def ^:private module-slab-saves #js [])

(defn- snapshot-module-slabs []
  {:data (.slice slab-data-offsets) :bm (.slice slab-bitmap-offsets)
   :total (.slice slab-total-blocks) :paths (.slice slab-file-paths)
   :bm-paths (.slice slab-bitmap-paths)
   :instances (wasm/snapshot-slab-instances)
   :coalesc [mmap-coalesc-region mmap-coalesc-path
             mmap-coalesc-max-data-size mmap-coalesc-cached-data-size]
   :ks [domain-ks domain-ks-bytes]})

(defn- restore-module-slabs! [{:keys [data bm total paths bm-paths instances coalesc ks]}]
  (dotimes [i 7]
    (aset slab-data-offsets i (aget data i))
    (aset slab-bitmap-offsets i (aget bm i))
    (aset slab-total-blocks i (aget total i))
    (aset slab-file-paths i (aget paths i))
    (aset slab-bitmap-paths i (aget bm-paths i)))
  (wasm/restore-slab-instances! instances)
  (let [[r p mx cached] coalesc]
    (set! mmap-coalesc-region r)
    (set! mmap-coalesc-path p)
    (set! mmap-coalesc-max-data-size mx)
    (set! mmap-coalesc-cached-data-size cached))
  (set! domain-ks (first ks))
  (set! domain-ks-bytes (second ks)))

(defn save-module-slabs!
  "Save the module slab state an mmap domain is about to replace."
  []
  (.push module-slab-saves (snapshot-module-slabs)))

(defn restore-saved-module-slabs!
  "Put back the module slab state the last mmap domain replaced."
  []
  (when (pos? (.-length module-slab-saves))
    (restore-module-slabs! (.pop module-slab-saves))))

(defn release-domain-sio!
  "What a closed domain gives back: an mmap domain's module slabs return
   to what it replaced."
  [_sio mmap?]
  (when mmap? (restore-saved-module-slabs!)))

(defn init-domain-views!
  "The memory views over slab class 0's SharedArrayBuffer (eve.wasm-mem)
   an in-process domain's values are read through."
  []
  (let [sabs (get-all-slab-sabs)]
    (when (and sabs (pos? (alength sabs)))
      (wasm-mem/init-views-from-sab! (aget sabs 0)))))

(defn init-mmap-coalesc!
  "Initialize a .slab6 file with the coalescing allocator.
   Creates the file, writes header + initial descriptor table.
   Registers the region for mmap overflow allocation.
   Uses initial-data-size for the file and max-data-size for growth cap."
  [path & {:keys [initial-data-size max-data-size max-desc]
           :or   {initial-data-size coalesc/INITIAL_DATA_SIZE
                  max-data-size     coalesc/DEFAULT_DATA_SIZE
                  max-desc          coalesc/MAX_DESCRIPTORS}}]
  ;; Descriptor table is sized for max-desc; data region starts after it.
  ;; Use max-desc layout to compute fixed offsets (desc table + data-offset).
  (let [layout      (coalesc/coalesc-layout initial-data-size max-desc)
        total-bytes (:total-bytes layout)
        data-offset (:data-offset layout)
        region      (region/open-mmap-region path total-bytes)]
    (coalesc/init-coalesc-region! region initial-data-size max-desc)
    ;; Cache layout info so slab-offset->byte-offset works for class 6
    (aset slab-data-offsets OVERFLOW_CLASS_IDX data-offset)
    (aset slab-bitmap-offsets OVERFLOW_CLASS_IDX (:desc-table-offset layout))
    (aset slab-total-blocks OVERFLOW_CLASS_IDX max-desc)
    ;; Store path and growth caps for lazy growth
    (set! mmap-coalesc-path path)
    (set! mmap-coalesc-max-data-size max-data-size)
    (set! mmap-coalesc-cached-data-size initial-data-size)
    ;; Register for read/write access
    (wasm/register-mmap-slab-instance! OVERFLOW_CLASS_IDX region nil)
    (set! mmap-coalesc-region region)))

(defn open-mmap-coalesc!
  "Open an existing .slab6 file for the coalescing allocator."
  [path]
  (let [peek-region  (region/open-mmap-region path 64)
        data-offset  (mem/load-i32 peek-region d/SLAB_HDR_DATA_OFFSET)
        dt-offset    (mem/load-i32 peek-region d/SLAB_HDR_BITMAP_OFFSET)
        max-desc     (mem/load-i32 peek-region d/SLAB_HDR_TOTAL_BLOCKS)
        cur-data-sz  (mem/load-i64 peek-region coalesc/COALESC_HDR_DATA_SIZE)
        ;; Map the file at current data size (will grow via grow-mmap-coalesc!)
        total-bytes  (+ data-offset cur-data-sz)
        region       (region/open-mmap-region path total-bytes)]
    (aset slab-data-offsets OVERFLOW_CLASS_IDX data-offset)
    (aset slab-bitmap-offsets OVERFLOW_CLASS_IDX dt-offset)
    (aset slab-total-blocks OVERFLOW_CLASS_IDX max-desc)
    (set! mmap-coalesc-path path)
    (set! mmap-coalesc-max-data-size coalesc/DEFAULT_DATA_SIZE)
    (set! mmap-coalesc-cached-data-size cur-data-sz)
    (wasm/register-mmap-slab-instance! OVERFLOW_CLASS_IDX region nil)
    (set! mmap-coalesc-region region)))

(defn- grow-mmap-coalesc!
  "Grow the .slab6 coalescing region. Uses CAS leader election in header.
   Returns true if grown/refreshed, false if at max."
  []
  (when (and mmap-coalesc-region mmap-coalesc-path)
    (let [result (coalesc/grow-coalesc-region!
                   mmap-coalesc-region mmap-coalesc-max-data-size)]
      (when result
        (let [data-offset (aset slab-data-offsets OVERFLOW_CLASS_IDX
                                (aget slab-data-offsets OVERFLOW_CLASS_IDX))
              total-bytes (+ (aget slab-data-offsets OVERFLOW_CLASS_IDX) result)
              region      (region/open-mmap-region mmap-coalesc-path total-bytes)]
          (set! mmap-coalesc-cached-data-size result)
          (wasm/register-mmap-slab-instance! OVERFLOW_CLASS_IDX region nil)
          (set! mmap-coalesc-region region)
          true)))))

(defn- grow-sab-coalesc!
  "Grow the SharedArrayBuffer-backed class 6 region when its buffer grows: the buffer first, then the header's data
   size (coalesc/grow-coalesc-region!'s CAS publishes it). true when grown, or grown by another thread."
  []
  (when (and mmap-coalesc-region (nil? mmap-coalesc-path))
    (let [inst    (wasm/get-slab-instance OVERFLOW_CLASS_IDX)
          ^js sab (when inst (.-buffer ^js (:u8 inst)))]
      (when (and sab (.-growable sab))
        (let [data-offset (aget slab-data-offsets OVERFLOW_CLASS_IDX)
              result      (coalesc/grow-coalesc-region!
                            mmap-coalesc-region (- (.-maxByteLength sab) data-offset)
                            (fn [new-size] (ensure-sab-length! sab (+ data-offset new-size))))]
          (when result
            (set! mmap-coalesc-cached-data-size result)
            true))))))

(defn- refresh-mmap-coalesc!
  "Re-map .slab6 if another process grew it."
  []
  (when (and mmap-coalesc-region mmap-coalesc-path)
    (let [hdr-data-sz (coalesc/current-data-size mmap-coalesc-region)]
      (when (> hdr-data-sz mmap-coalesc-cached-data-size)
        (let [total-bytes (+ (aget slab-data-offsets OVERFLOW_CLASS_IDX)
                             hdr-data-sz)
              region      (region/open-mmap-region mmap-coalesc-path total-bytes)]
          (set! mmap-coalesc-cached-data-size hdr-data-sz)
          (wasm/register-mmap-slab-instance! OVERFLOW_CLASS_IDX region nil)
          (set! mmap-coalesc-region region))))))

(defn reset-mmap-coalesc!
  "Clear the mmap coalescing region reference."
  []
  (set! mmap-coalesc-region nil)
  (set! mmap-coalesc-path nil)
  (set! mmap-coalesc-max-data-size 0)
  (set! mmap-coalesc-cached-data-size 0))

(defn init-sab-coalesc!
  "Initialize a SharedArrayBuffer-backed class 6 coalescing allocator for
   overflow allocations (>1024 bytes), of max-data-size (default 1GB).
   growable? (default wasm/grow-slabs?), where SharedArrayBuffers grow: its data
   region starts at coalesc's initial size and doubles on out-of-memory
   (grow-sab-coalesc!); else it is max-data-size from the start (the OS commits
   physical pages on first touch)."
  [& {:keys [max-data-size max-desc growable?]
       :or   {max-data-size (* 1024 1024 1024) ;; 1GB
              max-desc      coalesc/MAX_DESCRIPTORS
              growable?     (wasm/grow-slabs?)}}]
  (let [grow?       (and growable? (wasm/growable-sabs?))
        data-size   (if grow? (min coalesc/INITIAL_DATA_SIZE max-data-size) max-data-size)
        layout      (coalesc/coalesc-layout data-size max-desc)
        total-bytes (:total-bytes layout)
        data-offset (:data-offset layout)
        sab         (if grow?
                      (js/SharedArrayBuffer. total-bytes #js {:maxByteLength (+ data-offset max-data-size)})
                      (js/SharedArrayBuffer. total-bytes))
        region      (region/js-sab-region sab)]
    (coalesc/init-coalesc-region! region data-size max-desc)
    (aset slab-data-offsets OVERFLOW_CLASS_IDX data-offset)
    (aset slab-bitmap-offsets OVERFLOW_CLASS_IDX (:desc-table-offset layout))
    (aset slab-total-blocks OVERFLOW_CLASS_IDX max-desc)
    (set! mmap-coalesc-path nil)
    (set! mmap-coalesc-max-data-size 0)
    (set! mmap-coalesc-cached-data-size data-size)
    (wasm/register-slab-instance-from-sab! OVERFLOW_CLASS_IDX sab)
    (set! mmap-coalesc-region region)))

;; ---------------------------------------------------------------------------
;; The process slab, registered: eve's openers answer through the module slabs
;; ---------------------------------------------------------------------------

(defn- mmap-domain-sio!
  "The mmap domain at base-path as the module slabs: the slab files (created
   or opened), the module's own slabs saved first."
  [base-path {:keys [create? capacities] :or {capacities {}}}]
  (save-module-slabs!)
  ;; plain until its key ring installs a keystream (eve.keyring)
  (set! domain-ks nil)
  (set! domain-ks-bytes nil)
  (if create?
    (do (dotimes [i d/NUM_SLAB_CLASSES]
          (init-mmap-slab! i (str base-path ".slab" i)
                           :capacity (get capacities i (d/initial-capacity-for-class i))))
        (init-mmap-coalesc! (str base-path ".slab6")))
    (do (dotimes [i d/NUM_SLAB_CLASSES]
          (open-mmap-slab! i (str base-path ".slab" i)))
        (open-mmap-coalesc! (str base-path ".slab6"))))
  (cljs-sio))

(defn- desc-of
  "The block's tally handle: [[count-region count-off] [flags-region flags-off]
   [pointer-region pointer-off]] (a bitmap class's columns, or class 6's coalescing
   descriptor on an mmap domain), or nil (the legacy SAB overflow keeps none)."
  [_sio slab-offset]
  (let [ci (decode-class-idx slab-offset)]
    (cond
      (< ci OVERFLOW_CLASS_IDX)
      (desc-handle ci (decode-block-idx slab-offset))

      (and (== ci OVERFLOW_CLASS_IDX) mmap-coalesc-region)
      (when-let [[co fo po] (coalesc/row-of mmap-coalesc-region (decode-block-idx slab-offset))]
        [[mmap-coalesc-region co] [mmap-coalesc-region fo] [mmap-coalesc-region po]])

      :else nil)))

(defn- allocated?
  "True when the block is allocated: its bitmap bit set (classes 0-5), its coalescing descriptor ALLOCATED (class 6)."
  [sio slab-offset]
  (let [ci (decode-class-idx slab-offset)]
    (if (< ci OVERFLOW_CLASS_IDX)
      (let [bi   (decode-block-idx slab-offset)
            inst (wasm/get-slab-instance ci)
            rgn  (or (:bitmap-region inst) (:region inst))]
        (and rgn (< bi (aget slab-total-blocks ci))
             (not (zero? (bit-and (mem/load-i32 rgn (+ (aget slab-bitmap-offsets ci) (* 4 (unsigned-bit-shift-right bi 5))))
                                  (bit-shift-left 1 (bit-and bi 31)))))))
      (some? (desc-of sio slab-offset)))))

(alloc/register-host-slab!
 {:default-sio cljs-sio
  :desc-of desc-of
  :allocated? allocated?
  :refresh! (fn [_sio] (when (aget slab-file-paths 0) (refresh-mmap-slabs!)))
  ;; the module slab is an mmap domain's files while it holds their paths
  :shared-domain? (fn [_sio] (some? (aget slab-file-paths 0)))
  :mmap-domain-sio! mmap-domain-sio!
  ;; the open domain's blocks under a keystream: the module's own (its DataView readers take it)
  :install-keystream! (fn [sio {:keys [int bytes]}] (set! domain-ks int) (set! domain-ks-bytes bytes) sio)
  :keystream? (fn [_sio] (some? domain-ks))
  :heap-domain-sio! (fn [_opts] (cljs-sio))
  :release-domain-sio! release-domain-sio!
  :init-domain-views! init-domain-views!})

;; A JS typed array in an eve value: a module slab block (moved from eve.array)
;;-----------------------------------------------------------------------------
;; Typed array encoder registration (called by serializer for native typed arrays)
;;-----------------------------------------------------------------------------

(codec/set-typed-array-encoder!
  (fn [elem]
    (let [subtype (codec/typed-array-subtype elem)]
      (if (and subtype (<= subtype 0x0B))
        ;; All typed arrays → allocate SAB block, return TYPED_ARRAY pointer.
        ;; This preserves the typed array identity on deref (vs converting to EveArray).
        ;;
        ;; Block format (16-byte header for SIMD alignment):
        ;;   [subtype:u8][reserved:7 bytes][byte-len:u32LE][reserved:4 bytes][data at +16]
        ;; Data starts at offset+16 for 16-byte alignment (enables SIMD).
        ;; We over-allocate by up to 15 bytes to ensure the header starts at
        ;; a 16-byte aligned position within the allocated block.
        (let [byte-view (js/Uint8Array. (.-buffer elem) (.-byteOffset elem) (.-byteLength elem))
              byte-len (.-byteLength elem)
              ;; a typed array's block is read through views of the slab (eve.typed-view): never ciphertext
              _ (when (some? domain-ks)
                  (throw (js/Error. "eve: an encrypted domain holds no typed array (its views would read ciphertext)")))
              header-size 16
              ;; Over-allocate by 15 to guarantee 16-byte alignment headroom:
              ;; worst case raw_offset % 16 == 1 → shift 15 bytes to align.
              alloc-size (+ header-size byte-len 15)
              ;; Allocate from the module slab once it has its slabs (a swap
              ;; running, or the slabs initialised); else the inline blob
              has-slab? (or (some? d/*parent-atom*) (some? (wasm/get-slab-instance 0)))
              alloc-result (when has-slab?
                             (let [r (alloc alloc-size)]
                               (when-not (:error r) r)))]
          (if alloc-result
            ;; Slab-backed: write block at aligned position, return 7-byte pointer
            (let [slab-off (:offset alloc-result)
                  base (resolve-dv! slab-off)
                  dv resolved-dv
                  ;; Align header to 16-byte boundary
                  aligned-offset (bit-and (+ base 15) (bit-not 15))
                  class-idx (decode-class-idx slab-off)
                  inst (wasm/get-slab-instance class-idx)
                  u8 (:u8 inst)]
              ;; Write header: [subtype:u8][reserved:7][byte-len:u32LE][reserved:4]
              (.setUint8 dv aligned-offset subtype)
              ;; reserved bytes 1-7 are implicitly zero from allocation
              (.setUint32 dv (+ aligned-offset 8) byte-len true)
              ;; reserved bytes 12-15 are implicitly zero
              ;; Write data at offset+16
              (.set u8 byte-view (+ aligned-offset 16))
              ;; Return 7-byte pointer with slab-qualified offset
              (codec/encode-sab-pointer ser/FAST_TAG_TYPED_ARRAY slab-off))
            ;; Fallback for pre-init: inline blob (old 8-byte format for compatibility)
            (let [buf (js/Uint8Array. (+ 8 byte-len))
                  dv (js/DataView. (.-buffer buf))]
              (cljs.core/aset buf 0 d/DIRECT_MAGIC_0)
              (cljs.core/aset buf 1 d/DIRECT_MAGIC_1)
              (cljs.core/aset buf 2 ser/FAST_TAG_TYPED_ARRAY)
              (cljs.core/aset buf 3 subtype)
              (.setUint32 dv 4 byte-len true)
              (.set buf byte-view 8)
              buf)))
        ;; Unsupported type
        (js/Uint8Array. 0)))))
