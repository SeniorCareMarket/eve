(ns eve.region.sab
  "eve's in-process region over JavaScript's shared memory: a SharedArrayBuffer
   (fixed or growable) with Atomics (eve.mem's IMemRegion and IFillRegion;
   HOST-CONTRACT §1-2). Its own namespace so a region that keeps its bytes in a
   SharedArrayBuffer and adds a backing store (the browser's OPFS region) builds
   on it without eve.region's Node file mappings; eve.region re-exports
   js-sab-region."
  (:refer-clojure :exclude [unsigned-bit-shift-right])
  (:require
   [eve.host :refer [unsigned-bit-shift-right]]
   [eve.mem :as mem :refer [-load-i64 -store-i64!]]))

;; --- Helpers ---

(defn byte-off->i32-idx
  "Convert a 4-byte-aligned byte offset to the Int32Array element index.
   IMemRegion's atomic i32/i64 ops require 4-byte alignment; an Int32Array
   index cannot express any other offset, and flooring one (byte-off >>> 2)
   would land the access on the wrong word — a silent mis-write. So a
   misaligned offset throws (F-REGION-ATOMIC-ALIGN's JS hazard). Packed slab
   payload words never come here: the slab I/O reads and writes them as
   plain bytes (alloc's -sio-read-i32 / -sio-write-i32!, the module slab's
   DataView)."
  [byte-off]
  (when-not (zero? (bit-and byte-off 3))
    (throw (ex-info (str "eve.region: misaligned i32 atomic access at byte offset " byte-off
                         " (IMemRegion atomics require 4-byte alignment)")
                    {:byte-off byte-off})))
  (unsigned-bit-shift-right byte-off 2))

(defn wait-result->kw
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
    nil)

  ;; bulk fill of a fresh, unpublished range: TypedArray fill over the
  ;; Int32Array view, then the last slot through Atomics.store, so the fill
  ;; is ordered before what follows as the single store is
  mem/IFillRegion
  (-fill-i32! [_ byte-off n val]
    (when (pos? n)
      (let [i0 (byte-off->i32-idx byte-off)]
        (.fill -i32 val i0 (+ i0 n))
        (js/Atomics.store -i32 (+ i0 (dec n)) val)))
    nil))

(defn js-sab-region
  "Wrap an existing js/SharedArrayBuffer in a JsSabRegion."
  [^js sab]
  (JsSabRegion. sab (js/Int32Array. sab) (js/Uint8Array. sab)))
