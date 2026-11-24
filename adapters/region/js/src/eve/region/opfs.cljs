(ns eve.region.opfs
  "eve's file region in a browser (doc: the OPFS disk tier spec, W2): the
   file's bytes live in a growable SharedArrayBuffer every worker of the page
   shares, its atomics are the SharedArrayBuffer's (eve.region.sab), and its
   durability is a backing store's (eve.mem/ISyncableRegion).

   The browser maps no file: OPFS's FileSystemSyncAccessHandle reads and
   writes copies, and only in the worker holding the handle. So a region marks
   each 4 KiB page it writes in the file's dirty map (one bit per page, an
   Int32Array over a second shared buffer, set with Atomics.or AFTER the write),
   and a sync hands the store the byte runs to write. -sync-range! writes the
   pages its range touches; -sync! writes every page marked since the last
   sync. A sync claims a page's bit (Atomics.and/exchange) BEFORE the store
   copies its bytes, so a write that lands during the copy marks the page again
   and the next sync carries it.

   The store is IRegionStore: the io worker's client in a browser (it owns the
   sync handles: a request on the shared ring, a doorbell, Atomics.wait), an
   in-memory twin in the tests. Every region over one file shares its buffer
   and its dirty map, whatever its own byte length: a file reopened bigger
   grows the same buffer in place, so a region opened earlier stays valid."
  (:refer-clojure :exclude [unsigned-bit-shift-right])
  (:require
   [eve.host :refer [unsigned-bit-shift-right]]
   [eve.mem :as mem]
   [eve.region.sab :as sab]))

(def page-shift
  "log2 of the dirty map's page: 4 KiB."
  12)

(def page-bytes
  "The dirty map's page: 4 KiB, OPFS's and the jlog record's unit."
  4096)

(defn dirty-map-bytes
  "The dirty map's byte length for a file of byte-len bytes (one bit per page,
   whole i32 words)."
  [byte-len]
  (let [pages (unsigned-bit-shift-right (+ byte-len (dec page-bytes)) page-shift)]
    (* 4 (max 1 (unsigned-bit-shift-right (+ pages 31) 5)))))

(defprotocol IRegionStore
  "Where a browser file region's bytes are made durable."
  (-persist! [store file-id runs]
    "Write the byte runs RUNS (a JS array: offset, length, offset, length, ...)
     of file FILE-ID's shared bytes to durable storage and flush; returns when
     they are durable, throws when they are not."))

(defn- mark-dirty!
  "Mark the pages of [byte-off, byte-off+len) in dirty (after their write)."
  [^js dirty byte-off len]
  (when (pos? len)
    (let [p1 (unsigned-bit-shift-right (+ byte-off (dec len)) page-shift)]
      (loop [p (unsigned-bit-shift-right byte-off page-shift)]
        (when (<= p p1)
          (js/Atomics.or dirty (unsigned-bit-shift-right p 5) (bit-shift-left 1 (bit-and p 31)))
          (recur (inc p))))))
  nil)

(defn- add-run!
  "Append the run of pages [p0, p1) to runs, clipped to file-len bytes."
  [^js runs p0 p1 file-len]
  (let [off (* p0 page-bytes)
        end (min file-len (* p1 page-bytes))]
    (when (< off end)
      (.push runs off (- end off)))))

(defn- claim-range!
  "Claim (clear) the dirty bits of pages p0..p1 inclusive; returns nil."
  [^js dirty p0 p1]
  (loop [p p0]
    (when (<= p p1)
      (js/Atomics.and dirty (unsigned-bit-shift-right p 5) (bit-not (bit-shift-left 1 (bit-and p 31))))
      (recur (inc p))))
  nil)

(defn- claim-all!
  "Claim every dirty page of a file of file-len bytes; returns its runs."
  [^js dirty file-len]
  (let [runs  #js []
        pages (unsigned-bit-shift-right (+ file-len (dec page-bytes)) page-shift)
        words (min (.-length dirty) (unsigned-bit-shift-right (+ pages 31) 5))]
    (loop [w 0 start -1]
      (if (< w words)
        (let [bits (js/Atomics.exchange dirty w 0)]
          (if (zero? bits)
            (do (when-not (neg? start) (add-run! runs start (* w 32) file-len))
                (recur (inc w) -1))
            (let [start (loop [b 0 start start]
                          (if (< b 32)
                            (let [p (+ (* w 32) b)]
                              (if (zero? (bit-and bits (bit-shift-left 1 b)))
                                (do (when-not (neg? start) (add-run! runs start p file-len))
                                    (recur (inc b) -1))
                                (recur (inc b) (if (neg? start) p start))))
                            start))]
              (recur (inc w) start))))
        (when-not (neg? start) (add-run! runs start (* words 32) file-len))))
    runs))

(defn- remark-runs!
  "Mark runs dirty again (a store that failed leaves them for the next sync)."
  [^js dirty ^js runs]
  (loop [i 0]
    (when (< i (.-length runs))
      (mark-dirty! dirty (aget runs i) (aget runs (inc i)))
      (recur (+ i 2)))))

(defn- persist!
  [store file-id ^js dirty ^js runs]
  (when (pos? (.-length runs))
    (try
      (-persist! store file-id runs)
      (catch :default e
        (remark-runs! dirty runs)
        (throw e))))
  nil)

(deftype OpfsRegion [^js buf    ; the file's growable SharedArrayBuffer (shared)
                     ^js dirty  ; Int32Array over the file's dirty map (shared)
                     inner      ; eve.region.sab JsSabRegion over buf
                     size       ; this region's byte length
                     store      ; IRegionStore
                     file-id]
  mem/IMemRegion
  (-byte-length [_] size)
  (-load-i32 [_ byte-off] (mem/-load-i32 inner byte-off))
  (-store-i32! [_ byte-off val]
    (mem/-store-i32! inner byte-off val)
    (mark-dirty! dirty byte-off 4))
  (-cas-i32! [_ byte-off expected desired]
    (let [r (mem/-cas-i32! inner byte-off expected desired)]
      (mark-dirty! dirty byte-off 4)
      r))
  (-add-i32! [_ byte-off delta]
    (let [r (mem/-add-i32! inner byte-off delta)]
      (mark-dirty! dirty byte-off 4)
      r))
  (-sub-i32! [_ byte-off delta]
    (let [r (mem/-sub-i32! inner byte-off delta)]
      (mark-dirty! dirty byte-off 4)
      r))
  (-exchange-i32! [_ byte-off val]
    (let [r (mem/-exchange-i32! inner byte-off val)]
      (mark-dirty! dirty byte-off 4)
      r))
  (-load-i64 [_ byte-off] (mem/-load-i64 inner byte-off))
  (-store-i64! [_ byte-off val]
    (mem/-store-i64! inner byte-off val)
    (mark-dirty! dirty byte-off 8))
  (-cas-i64! [_ byte-off expected desired]
    (let [r (mem/-cas-i64! inner byte-off expected desired)]
      (mark-dirty! dirty byte-off 8)
      r))
  (-add-i64! [_ byte-off delta] (mem/-add-i64! inner byte-off delta))
  (-sub-i64! [_ byte-off delta] (mem/-sub-i64! inner byte-off delta))
  (-wait-i32! [_ byte-off expected timeout-ms] (mem/-wait-i32! inner byte-off expected timeout-ms))
  (-notify-i32! [_ byte-off n] (mem/-notify-i32! inner byte-off n))
  (-supports-watch? [_] true)
  (-read-bytes [_ byte-off len] (mem/-read-bytes inner byte-off len))
  (-write-bytes! [_ byte-off src]
    (mem/-write-bytes! inner byte-off src)
    (mark-dirty! dirty byte-off (.-length ^js src)))

  mem/IFillRegion
  (-fill-i32! [_ byte-off n val]
    (mem/-fill-i32! inner byte-off n val)
    (mark-dirty! dirty byte-off (* 4 n)))

  mem/ISyncableRegion
  (-sync! [_]
    (persist! store file-id dirty (claim-all! dirty (.-byteLength buf))))
  (-sync-range! [_ byte-off len]
    (when (pos? len)
      (let [file-len (.-byteLength buf)
            p0       (unsigned-bit-shift-right byte-off page-shift)
            p1       (unsigned-bit-shift-right (+ byte-off (dec len)) page-shift)
            runs     #js []]
        (claim-range! dirty p0 p1)
        (add-run! runs p0 (inc p1) file-len)
        (persist! store file-id dirty runs)))))

(defn opfs-region
  "A region of size bytes over a browser file's shared bytes: buf (its growable
   SharedArrayBuffer, at least size bytes), dirty-buf (its dirty map, at least
   (dirty-map-bytes (.-byteLength buf)) bytes), store (IRegionStore) and the
   store's file-id."
  [^js buf ^js dirty-buf size store file-id]
  (when (< (.-byteLength buf) size)
    (throw (ex-info "eve.region.opfs: the file's buffer is shorter than the region"
                    {:buffer-bytes (.-byteLength buf) :size size})))
  (OpfsRegion. buf (js/Int32Array. dirty-buf) (sab/js-sab-region buf) size store file-id))

(defn opfs-region?
  "true for a browser file region."
  [r]
  (instance? OpfsRegion r))
