(ns eve.region-opfs-test
  "eve.region.opfs (the OPFS disk tier spec, W2) over an in-memory store that keeps a hash per page, as the io worker
   does: a sync persists exactly the pages whose bytes changed since the store last wrote them — written through the
   region's ops or straight into its buffer, as a slab's views write — a range sync the range's pages, and regions
   over one file share its buffer."
  (:require
   [cljs.test :refer [deftest is testing]]
   [eve.mem :as mem]
   [eve.region]
   [eve.region.opfs :as opfs]))

(defn- page-hash [^js u8 off]
  (let [end (min (.-length u8) (+ off opfs/page-bytes))]
    (loop [i off h 2166136261]
      (if (< i end)
        (recur (inc i) (js/Math.imul (bit-xor h (aget u8 i)) 16777619))
        h))))

(defn- write-runs! [{:keys [buf disk hashes]} ^js runs]
  (let [u8 (js/Uint8Array. buf)]
    (loop [i 0]
      (when (< i (.-length runs))
        (let [off (aget runs i) len (aget runs (inc i))]
          (.set ^js disk (.slice u8 off (+ off len)) off)
          (loop [p off]
            (when (< p (+ off len))
              (swap! hashes assoc p (page-hash u8 p))
              (recur (+ p opfs/page-bytes)))))
        (recur (+ i 2))))))

(deftype MemStore [file calls]
  opfs/IRegionStore
  (-persist! [_ _file-id runs]
    (swap! calls conj (vec runs))
    (write-runs! file runs))
  (-persist-changed! [_ _file-id]
    (let [{:keys [buf hashes]} file
          u8 (js/Uint8Array. buf)
          runs #js []]
      (loop [p 0]
        (when (< p (.-byteLength buf))
          (when (not= (get @hashes p) (page-hash u8 p))
            (let [n (min opfs/page-bytes (- (.-byteLength buf) p))
                  l (.-length runs)]
              (if (and (pos? l) (= p (+ (aget runs (- l 2)) (aget runs (- l 1)))))
                (aset runs (- l 1) (+ (aget runs (- l 1)) n))
                (.push runs p n))))
          (recur (+ p opfs/page-bytes))))
      (swap! calls conj (vec runs))
      (write-runs! file runs))))

(defn- fixture [file-len max-len]
  (let [buf   (js/SharedArrayBuffer. file-len #js {:maxByteLength max-len})
        u8    (js/Uint8Array. buf)
        h0    (into {} (for [p (range 0 file-len opfs/page-bytes)] [p (page-hash u8 p)]))
        file  {:buf buf :disk (js/Uint8Array. max-len) :hashes (atom h0)}
        calls (atom [])
        store (MemStore. file calls)]
    {:buf buf :store store :calls calls :disk (:disk file)
     :region (opfs/opfs-region buf file-len store 7)}))

(defn- disk-i32 [^js disk off]
  (.getInt32 (js/DataView. (.-buffer disk)) off true))

(deftest sync-persists-changed-pages
  (let [{:keys [buf region calls disk]} (fixture (* 16 4096) (* 64 4096))]
    (is (satisfies? mem/ISyncableRegion region))
    (mem/-sync! region)
    (is (= [[]] @calls) "a clean file has nothing to write")
    (mem/-store-i32! region 8 42)
    (mem/-cas-i32! region (+ 4096 16) 0 7)
    (mem/-write-bytes! region (* 5 4096) (js/Uint8Array. #js [1 2 3]))
    (mem/-fill-i32! region (- (* 9 4096) 4) 2 -1)
    (.setInt32 (js/DataView. buf) (* 12 4096) 99 true)      ; a slab's own view
    (reset! calls [])
    (mem/-sync! region)
    (is (= [[0 8192 (* 5 4096) 4096 (* 8 4096) 8192 (* 12 4096) 4096]] @calls)
        "pages 0-1, 5, 8-9 and 12: adjacent pages are one run")
    (is (= 42 (disk-i32 disk 8)))
    (is (= 7 (disk-i32 disk (+ 4096 16))))
    (is (= -1 (disk-i32 disk (* 9 4096))))
    (is (= 99 (disk-i32 disk (* 12 4096))) "a write past the region's ops is persisted too")
    (reset! calls [])
    (mem/-sync! region)
    (is (= [[]] @calls) "a second sync has nothing left")))

(deftest sync-range-writes-the-range-pages
  (let [{:keys [region calls disk]} (fixture (* 8 4096) (* 8 4096))]
    (mem/-store-i32! region 0 1)
    (mem/-store-i32! region (* 3 4096) 3)
    (mem/sync-region! region (+ (* 3 4096) 100) 10)
    (is (= [[(* 3 4096) 4096]] @calls))
    (is (= 3 (disk-i32 disk (* 3 4096))))
    (is (= 0 (disk-i32 disk 0)) "page 0 is not in the range")
    (reset! calls [])
    (mem/-sync! region)
    (is (= [[0 4096]] @calls) "page 0 is still changed; page 3 is written")))

(deftest regions-over-one-file-share-it
  (let [{:keys [buf store region]} (fixture 4096 (* 32 4096))
        _   (.grow buf (* 20 4096))
        big (opfs/opfs-region buf (* 20 4096) store 7)]
    (is (= 4096 (mem/-byte-length region)))
    (is (= (* 20 4096) (mem/-byte-length big)))
    (mem/-store-i32! region 0 6)
    (is (= 6 (mem/-load-i32 big 0)) "one buffer")
    (is (identical? buf (opfs/shared-buffer region)))
    (testing "a region longer than its buffer is refused"
      (is (thrown? js/Error (opfs/opfs-region buf (* 40 4096) store 7))))))

(deftest range-clipped-to-the-file
  (let [{:keys [region calls]} (fixture 5000 8192)]
    (mem/sync-region! region 4996 4)
    (is (= [[4096 904]] @calls) "the last page's run ends at the file's end")))

(deftest i64-and-rmw
  (let [{:keys [region calls disk]} (fixture (* 8 4096) (* 8 4096))]
    (mem/-store-i64! region (* 6 4096) 0x123456789)
    (mem/-add-i32! region (* 4 4096) 3)
    (mem/-sub-i32! region (* 4 4096) 1)
    (mem/-exchange-i32! region (* 2 4096) 11)
    (mem/-sync! region)
    (is (= [[(* 2 4096) 4096 (* 4 4096) 4096 (* 6 4096) 4096]] @calls))
    (is (= 2 (disk-i32 disk (* 4 4096))))
    (is (= 0x123456789 (mem/-load-i64 region (* 6 4096))))))
