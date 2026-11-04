(ns eve.region
  "eve's regions (eve.mem's IMemRegion and IHostAtomics; HOST-CONTRACT §1-2)
   over JavaScript's shared memory (ClojureScript, nbb): a SharedArrayBuffer with
   Atomics in-process, a file mapped MAP_SHARED through eve's N-API addon
   (native/mmap_cas.cc) across processes, and a Lustre mount's fcntl locks.

   make-heap-region and open-mmap-region open them; lustre-region? says
   whether a region's atomics are fcntl byte-range locks (a Lustre mount)."
  (:refer-clojure :exclude [aget aset alength byte-array int-array long-array object-array
                            into-array unchecked-byte unchecked-int unchecked-long
                            unchecked-inc-int unsigned-bit-shift-right long double class
                            locking transient persistent! conj! assoc! bytes objects
                            const-bytes const-objects])
  (:require
   [eve.host :refer [aget aset alength byte-array int-array long-array object-array
                     into-array unchecked-byte unchecked-int unchecked-long
                     unchecked-inc-int unsigned-bit-shift-right long double class locking
                     transient persistent! conj! assoc! bytes objects const-bytes
                     const-objects]]
   [eve.mem :as mem :refer [-byte-length -load-i32 -store-i32! -cas-i32!
                     -add-i32! -sub-i32! -exchange-i32! -load-i64 -store-i64! -cas-i64!
                     -add-i64! -sub-i64! -wait-i32! -notify-i32! -supports-watch?
                     -read-bytes -write-bytes! -atomic-rmw? -with-file-lock]]))

;; Every region has atomic RMW unless it says otherwise.
(extend-protocol mem/IHostAtomics
  default
  (-atomic-rmw? [_] true)
  (-with-file-lock [_ _byte-off f] (f)))

;; --- Helpers ---

(defn- byte-off->i32-idx
  "Convert a 4-byte-aligned byte offset to the Int32Array element index."
  [byte-off]
  (unsigned-bit-shift-right byte-off 2))

(defn- wait-result->kw
  "Coerce the string returned by Atomics.wait to a keyword."
  [s]
  (case s
    "ok"         :ok
    "not-equal"  :not-equal
    "timed-out"  :timed-out
    :timed-out))

;; --- JsSabRegion — SharedArrayBuffer + Atomics ---
;; This is the existing EVE backing for both browser and Node worker_threads.
;; worker_threads workers share SABs via postMessage / workerData; the Atomics
;; API provides the necessary synchronisation.

(deftype JsSabRegion [^js sab
                      ^js -i32   ; cached Int32Array view — atomic ops
                      ^js -u8]   ; cached Uint8Array view  — byte I/O
  mem/IMemRegion
  (-byte-length [_]
    (.-byteLength sab))

  (-load-i32 [_ byte-off]
    (js/Atomics.load -i32 (byte-off->i32-idx byte-off)))

  (-store-i32! [_ byte-off val]
    (js/Atomics.store -i32 (byte-off->i32-idx byte-off) val)
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (js/Atomics.compareExchange -i32 (byte-off->i32-idx byte-off) expected desired))

  (-add-i32! [_ byte-off delta]
    (js/Atomics.add -i32 (byte-off->i32-idx byte-off) delta))

  (-sub-i32! [_ byte-off delta]
    (js/Atomics.sub -i32 (byte-off->i32-idx byte-off) delta))

  (-exchange-i32! [_ byte-off val]
    (js/Atomics.exchange -i32 (byte-off->i32-idx byte-off) val))

  (-load-i64 [_ byte-off]
    ;; Emulate i64 load via two i32 loads (little-endian).
    ;; Sufficient for single-process SAB use (no cross-process atomicity).
    (let [lo (unsigned-bit-shift-right
               (js/Atomics.load -i32 (byte-off->i32-idx byte-off)) 0)
          hi (unsigned-bit-shift-right
               (js/Atomics.load -i32 (byte-off->i32-idx (+ byte-off 4))) 0)]
      (+ (* hi 0x100000000) lo)))
  (-store-i64! [_ byte-off val]
    ;; Emulate i64 store via two i32 stores (little-endian). The high word is a division: JS masks a shift count to
    ;; five bits, so (unsigned-bit-shift-right val 32) is val itself.
    (let [lo (bit-and val 0xFFFFFFFF)
          hi (js/Math.floor (/ val 0x100000000))]
      (js/Atomics.store -i32 (byte-off->i32-idx byte-off) lo)
      (js/Atomics.store -i32 (byte-off->i32-idx (+ byte-off 4)) hi)))
  (-cas-i64! [this byte-off expected desired]
    ;; Single-process emulation: read, compare, write if match
    (let [current (-load-i64 this byte-off)]
      (when (== current expected)
        (-store-i64! this byte-off desired))
      current))
  (-add-i64! [_ _byte-off _delta]
    (throw (ex-info "JsSabRegion does not support atomic i64 add" {})))
  (-sub-i64! [_ _byte-off _delta]
    (throw (ex-info "JsSabRegion does not support atomic i64 sub" {})))

  (-wait-i32! [_ byte-off expected timeout-ms]
    (wait-result->kw
     (js/Atomics.wait -i32 (byte-off->i32-idx byte-off) expected timeout-ms)))

  (-notify-i32! [_ byte-off n]
    (js/Atomics.notify -i32 (byte-off->i32-idx byte-off) n))

  (-supports-watch? [_] true)

  (-read-bytes [_ byte-off len]
    (.slice -u8 byte-off (+ byte-off len)))

  (-write-bytes! [_ byte-off src]
    (.set -u8 src byte-off)
    nil))

(defn js-sab-region
  "Wrap an existing js/SharedArrayBuffer in a JsSabRegion."
  [^js sab]
  (JsSabRegion. sab (js/Int32Array. sab) (js/Uint8Array. sab)))

(defn make-heap-region
  "An in-process region over a fresh SharedArrayBuffer (the CLJS twin of
   the JVM heap region): shareable with worker threads, not with processes."
  [size-bytes]
  (js-sab-region (js/SharedArrayBuffer. size-bytes)))

(defn make-js-sab-region
  "Allocate a fresh js/SharedArrayBuffer of byte-length bytes and wrap it."
  [byte-length]
  (js-sab-region (js/SharedArrayBuffer. byte-length)))

;; --- NodeMmapRegion — file-backed MAP_SHARED mmap (cross-process) ---
;; Used when EVE needs to share memory with a process that cannot receive a
;; SharedArrayBuffer via postMessage — e.g. a JVM process, or a separate
;; Node process not related by worker_threads.
;;
;; The backing is a native Node addon (native/mmap_cas.cc) that:
;;   • opens / creates a file, ftruncates to size, and calls mmap(MAP_SHARED)
;;   • returns the mapping as a Node.js Buffer (zero-copy, same virtual page)
;;   • provides atomic int32 ops via std::atomic_ref<int32_t> (C++20 stdlib)
;;   • provides futex wait/notify via Linux futex(2) syscall directly
;;
;; The Buffer's underlying memory IS the mmap'd page — reads and writes to the
;; Buffer are immediately visible to any other process (or JVM thread) that has
;; the same file mapped.

;; Lazy reference to the native addon — auto-loaded on first access
(defonce ^:private native-addon (atom nil))

(defn- try-auto-load-addon!
  "Attempt to auto-load the mmap_cas native addon via node-gyp-build.
   Tries three strategies:
   1. resolve 'eve-native/package.json' — works when eve is an npm dep
      (compiled, or on nbb, whose require resolves from the working dir)
   2. walk up from this file's compiled location — works in local dev
   3. the working directory — eve's own checkout, run with nbb (which
      has no compiled location)
   Each is tried until one loads: a strategy can name a root that holds no
   addon (a compiled program's worker runs its code by eval, whose module
   names no file, so strategy 2 names a directory above the checkout —
   measured 2026-10-19: every compiled worker that opened a disk domain
   died \"addon not found\" while strategy 3 had it).
   Returns the addon or nil if not available."
  []
  (try
    (let [ngb  (js/require "node-gyp-build")
          path (js/require "path")
          roots [;; Strategy 1: npm dep — resolve via node module resolution
                 (try (.dirname path (js/require.resolve "eve-native/package.json"))
                      (catch :default _ nil))
                 ;; Strategy 2: local dev — walk up from compiled output
                 (try (.resolve path (.dirname path (.-filename js/module)) ".." "..")
                      (catch :default _ nil))
                 ;; Strategy 3: the working directory (nbb in a checkout)
                 (.cwd js/process)]]
      (some (fn [root] (when root (try (reset! native-addon (ngb root)) (catch :default _ nil)))) roots))
    (catch :default _
      nil)))

(defn load-native-addon!
  "Load the mmap_cas native addon explicitly. Normally not needed —
   the addon is auto-loaded on first use via node-gyp-build."
  [addon-js-obj]
  (reset! native-addon addon-js-obj))

(defn native-addon-loaded?
  "Returns true if the mmap_cas native addon has been loaded."
  []
  (some? @native-addon))

(defn- native
  "Return the loaded native addon, auto-loading if needed."
  ^js []
  (or @native-addon
      (try-auto-load-addon!)
      (throw (ex-info "mmap_cas native addon not found — run npm install to build it" {}))))

(deftype NodeMmapRegion [^js buf   ; Node.js Buffer from addon open()
                         size]     ; byte length
  mem/IMemRegion
  (-byte-length [_]
    size)

  (-load-i32 [_ byte-off]
    (.load32 ^js (native) buf byte-off))

  (-store-i32! [_ byte-off val]
    (.store32 ^js (native) buf byte-off val)
    nil)

  (-cas-i32! [_ byte-off expected desired]
    ;; Native cas32 returns the *old* value — same contract as Atomics.compareExchange
    (.cas32 ^js (native) buf byte-off expected desired))

  (-add-i32! [_ byte-off delta]
    (.add32 ^js (native) buf byte-off delta))

  (-sub-i32! [_ byte-off delta]
    (.sub32 ^js (native) buf byte-off delta))

  (-exchange-i32! [_ byte-off val]
    ;; Simulate exchange via CAS loop (native addon may not expose exchange32).
    (loop []
      (let [old (.load32 ^js (native) buf byte-off)]
        (if (== old (.cas32 ^js (native) buf byte-off old val))
          old
          (recur)))))

  (-load-i64 [_ byte-off]
    (.load64 ^js (native) buf byte-off))

  (-store-i64! [_ byte-off val]
    (.store64 ^js (native) buf byte-off val)
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (.cas64 ^js (native) buf byte-off expected desired))

  (-add-i64! [_ byte-off delta]
    (.add64 ^js (native) buf byte-off delta))

  (-sub-i64! [_ byte-off delta]
    (.sub64 ^js (native) buf byte-off delta))

  (-wait-i32! [_ byte-off expected timeout-ms]
    ;; Native wait32 returns "ok" | "not-equal" | "timed-out"
    (wait-result->kw (.wait32 ^js (native) buf byte-off expected timeout-ms)))

  (-notify-i32! [_ byte-off n]
    (.notify32 ^js (native) buf byte-off n))

  (-supports-watch? [_] false)

  (-read-bytes [_ byte-off len]
    ;; Buffer.slice returns a view sharing the same ArrayBuffer.
    ;; Preserve byteOffset so deserialize-element reads from the correct position.
    (let [sliced (.slice buf byte-off (+ byte-off len))]
      (js/Uint8Array. (.-buffer sliced) (.-byteOffset sliced) (.-byteLength sliced))))

  (-write-bytes! [_ byte-off src]
    ;; src is a Uint8Array; copy into the Buffer via Node's Buffer.from +
    ;; Buffer.copy for zero-extra-copy semantics
    (let [src-buf (js/Buffer.from (.-buffer src) (.-byteOffset src) (.-byteLength src))]
      (.copy src-buf buf byte-off))
    nil)

  ;; --- durability (eve.mem/ISyncableRegion): the addon's msync — the
  ;; same primitive its Lustre fcntl ops apply to a write's page ---
  mem/ISyncableRegion
  (-sync! [_]
    (.msync ^js (native) buf 0 size)
    nil)

  (-sync-range! [_ byte-off len]
    (.msync ^js (native) buf byte-off len)
    nil))

(declare ->LustreMmapRegion)

(defn open-mmap-region
  "Open (or create) a file-backed shared memory region at path.
   If the file is new it is truncated to size-bytes.
   Returns a NodeMmapRegion (default) or LustreMmapRegion (when opts
   carries :lustre? true — a provider choice at domain-open time, never
   an ambient flag).

   Example:
     (mem/open-mmap-region \"/tmp/eve-shard-0.mem\" (* 64 1024 1024))"
  ([path size-bytes] (open-mmap-region path size-bytes nil))
  ([path size-bytes opts]
   (if (:lustre? opts)
     (let [result (.openWithFd ^js (native) path size-bytes)]
       ;; its deftype is below: the factory, declared first (sci, nbb's
       ;; interpreter, resolves a form's symbols when it reads the form)
       (->LustreMmapRegion (.-buf result) (.-fd result) size-bytes))
     (let [buf (.open ^js (native) path size-bytes)]
       (NodeMmapRegion. buf size-bytes)))))

;; --- LustreMmapRegion — fcntl byte-range locking for cross-node atomics ---
;; Uses mmap for data access (zero-copy reads) but fcntl byte-range locks
;; for all atomic operations, enabling Eve atoms to work across machines on
;; a Lustre filesystem mounted with -o flock.
;;
;; Pattern: lock N bytes via fcntl(F_SETLKW), read via mmap, compare,
;; conditionally write + msync, unlock. This is a pessimistic lock-based
;; CAS — slower than hardware CAS (~50-200us vs ~10ns on Lustre) but
;; correct across nodes via the Lustre LDLM.

(deftype LustreMmapRegion [^js buf   ; Node.js Buffer (mmap'd page)
                            fd        ; file descriptor (kept open for fcntl)
                            size]     ; byte length
  mem/IMemRegion
  (-byte-length [_] size)

  ;; --- atomic i32 via fcntl ---
  (-load-i32 [_ byte-off]
    (.fcntlLoad32 (native) fd buf byte-off))

  (-store-i32! [_ byte-off val]
    (.fcntlStore32 (native) fd buf byte-off val)
    nil)

  (-cas-i32! [_ byte-off expected desired]
    (.fcntlCas32 (native) fd buf byte-off expected desired))

  (-add-i32! [_ byte-off delta]
    (.fcntlAdd32 (native) fd buf byte-off delta))

  (-sub-i32! [_ byte-off delta]
    (.fcntlSub32 (native) fd buf byte-off delta))

  (-exchange-i32! [_ byte-off val]
    (.fcntlExchange32 (native) fd buf byte-off val))

  ;; --- atomic i64 via fcntl ---
  (-load-i64 [_ byte-off]
    (.fcntlLoad64 (native) fd buf byte-off))

  (-store-i64! [_ byte-off val]
    (.fcntlStore64 (native) fd buf byte-off val)
    nil)

  (-cas-i64! [_ byte-off expected desired]
    (.fcntlCas64 (native) fd buf byte-off expected desired))

  (-add-i64! [_ byte-off delta]
    (.fcntlAdd64 (native) fd buf byte-off delta))

  (-sub-i64! [_ byte-off delta]
    (.fcntlSub64 (native) fd buf byte-off delta))

  ;; --- wait/notify — polling fallback (no futex across Lustre nodes) ---
  (-wait-i32! [this byte-off expected timeout-ms]
    (let [deadline (+ (js/Date.now) timeout-ms)]
      (loop []
        (let [cur (-load-i32 this byte-off)]
          (cond
            (not= cur expected) :not-equal
            (>= (js/Date.now) deadline) :timed-out
            :else (do (.nanosleep (native) 100000)  ; 100µs
                      (recur)))))))

  (-notify-i32! [_ _byte-off _n] 0)

  (-supports-watch? [_] false)

  ;; --- byte I/O — direct mmap access (same as NodeMmapRegion) ---
  (-read-bytes [_ byte-off len]
    (let [sliced (.slice buf byte-off (+ byte-off len))]
      (js/Uint8Array. (.-buffer sliced) (.-byteOffset sliced) (.-byteLength sliced))))

  (-write-bytes! [_ byte-off src]
    (let [src-buf (js/Buffer.from (.-buffer src) (.-byteOffset src) (.-byteLength src))]
      (.copy src-buf buf byte-off))
    nil))

(defn open-lustre-region
  "Open (or create) a file-backed Lustre-compatible memory region at path.
   Like open-mmap-region but keeps the fd open for fcntl byte-range locking.
   Returns a LustreMmapRegion."
  [path size-bytes]
  (let [result (.openWithFd (native) path size-bytes)]
    (LustreMmapRegion. (.-buf result) (.-fd result) size-bytes)))

(defn lustre-region?
  "true for a region whose atomics are fcntl byte-range locks (a Lustre mount)."
  [r]
  (instance? LustreMmapRegion r))
