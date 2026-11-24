(ns eve.region.opfs
  "eve's file region in a browser (the OPFS disk tier spec, W2): the file's bytes live in a growable
   SharedArrayBuffer every worker of the page shares, its atomics are the SharedArrayBuffer's (eve.region.sab), and
   its durability is a backing store's (eve.mem/ISyncableRegion).

   The browser maps no file: OPFS's FileSystemSyncAccessHandle reads and writes copies, and only in the worker
   holding the handle. And a region's bytes are written by more than its own ops — the module slab writes a block's
   bytes through its own views of the buffer (eve.deftype-proto.wasm's :u8 / :dv), as a mapped file's are written
   through the mapping — so a region cannot know which of its bytes changed. The store does: -sync! asks it to
   persist every page whose bytes differ from what it last wrote (it keeps a 64-bit hash per page of the file as
   written), -sync-range! the pages a range touches, whatever they hold.

   The store is IRegionStore: the io worker's client in a browser (eve.opfs: it owns the sync handles), an
   in-memory twin in the tests. Every region over one file shares its buffer, whatever its own byte length: a file
   reopened bigger grows the same buffer in place, so a region opened earlier stays valid."
  (:refer-clojure :exclude [unsigned-bit-shift-right])
  (:require
   [eve.host :refer [unsigned-bit-shift-right]]
   [eve.mem :as mem]
   [eve.region.sab :as sab]))

(def page-shift
  "log2 of a store's page: 4 KiB."
  12)

(def page-bytes
  "A store's page: 4 KiB, OPFS's and the jlog record's unit."
  4096)

(defprotocol IRegionStore
  "Where a browser file region's bytes are made durable."
  (-persist! [store file-id runs]
    "Write the byte runs RUNS (a JS array: offset, length, offset, length, ...) of file FILE-ID's shared bytes to
     durable storage and flush; returns when they are durable, throws when they are not.")
  (-persist-changed! [store file-id]
    "Write every page of file FILE-ID whose shared bytes differ from what the store last wrote, and flush; returns
     when they are durable, throws when they are not."))

(deftype OpfsRegion [^js buf    ; the file's growable SharedArrayBuffer (shared)
                     inner      ; eve.region.sab JsSabRegion over buf
                     size       ; this region's byte length
                     store      ; IRegionStore
                     file-id
                     fresh?]    ; this open read the file in from storage (eve.mem/IFreshRegion)
  mem/IMemRegion
  (-byte-length [_] size)
  (-load-i32 [_ byte-off] (mem/-load-i32 inner byte-off))
  (-store-i32! [_ byte-off val] (mem/-store-i32! inner byte-off val))
  (-cas-i32! [_ byte-off expected desired] (mem/-cas-i32! inner byte-off expected desired))
  (-add-i32! [_ byte-off delta] (mem/-add-i32! inner byte-off delta))
  (-sub-i32! [_ byte-off delta] (mem/-sub-i32! inner byte-off delta))
  (-exchange-i32! [_ byte-off val] (mem/-exchange-i32! inner byte-off val))
  (-load-i64 [_ byte-off] (mem/-load-i64 inner byte-off))
  (-store-i64! [_ byte-off val] (mem/-store-i64! inner byte-off val))
  (-cas-i64! [_ byte-off expected desired] (mem/-cas-i64! inner byte-off expected desired))
  (-add-i64! [_ byte-off delta] (mem/-add-i64! inner byte-off delta))
  (-sub-i64! [_ byte-off delta] (mem/-sub-i64! inner byte-off delta))
  (-wait-i32! [_ byte-off expected timeout-ms] (mem/-wait-i32! inner byte-off expected timeout-ms))
  (-notify-i32! [_ byte-off n] (mem/-notify-i32! inner byte-off n))
  (-supports-watch? [_] true)
  (-read-bytes [_ byte-off len] (mem/-read-bytes inner byte-off len))
  (-write-bytes! [_ byte-off src] (mem/-write-bytes! inner byte-off src))

  mem/IFillRegion
  (-fill-i32! [_ byte-off n val] (mem/-fill-i32! inner byte-off n val))

  mem/IFreshRegion
  (-fresh? [_] fresh?)

  mem/ISyncableRegion
  (-sync! [_]
    (-persist-changed! store file-id)
    nil)
  (-sync-range! [_ byte-off len]
    (when (pos? len)
      (let [file-len (.-byteLength buf)
            off      (* page-bytes (unsigned-bit-shift-right byte-off page-shift))
            end      (min file-len (* page-bytes (inc (unsigned-bit-shift-right (+ byte-off (dec len)) page-shift))))]
        (when (< off end)
          (-persist! store file-id #js [off (- end off)]))))
    nil))

(defn opfs-region
  "A region of size bytes over a browser file's shared bytes: buf (its growable SharedArrayBuffer, at least size
   bytes), store (IRegionStore) and the store's file-id; fresh? when the open read the file in from storage (no
   live worker holds its bytes: eve.mem/fresh-region?)."
  ([buf size store file-id] (opfs-region buf size store file-id false))
  ([^js buf size store file-id fresh?]
   (when (< (.-byteLength buf) size)
     (throw (ex-info "eve.region.opfs: the file's buffer is shorter than the region"
                     {:buffer-bytes (.-byteLength buf) :size size})))
   (OpfsRegion. buf (sab/js-sab-region buf) size store file-id (boolean fresh?))))

(defn opfs-region?
  "true for a browser file region."
  [r]
  (instance? OpfsRegion r))

(defn shared-buffer
  "The region's SharedArrayBuffer (a slab's own views are over it, as a mapped file's are over its mapping)."
  [^OpfsRegion r]
  (.-buf r))
