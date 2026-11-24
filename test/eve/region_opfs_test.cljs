(ns eve.region-opfs-test
  "eve.region.opfs (the OPFS disk tier spec, W2) over an in-memory store: what a
   sync hands the store is exactly the pages written since the last sync (or the
   range asked for), the bytes it reads are the region's, a failed store leaves
   its pages for the next sync, and regions over one file share its buffer."
  (:require
   [cljs.test :refer [deftest is testing]]
   [eve.mem :as mem]
   [eve.region]
   [eve.region.opfs :as opfs]))

(deftype MemStore [files calls]
  opfs/IRegionStore
  (-persist! [_ file-id runs]
    (let [{:keys [buf disk fail?]} (get @files file-id)]
      (when (and fail? @fail?)
        (throw (ex-info "store down" {:file-id file-id})))
      (swap! calls conj (vec runs))
      (loop [i 0]
        (when (< i (.-length runs))
          (let [off (aget runs i) len (aget runs (inc i))]
            (.set ^js disk (js/Uint8Array. buf off len) off))
          (recur (+ i 2)))))))

(defn- fixture [file-len max-len]
  (let [buf   (js/SharedArrayBuffer. file-len #js {:maxByteLength max-len})
        dirty (js/SharedArrayBuffer. (opfs/dirty-map-bytes file-len)
                                     #js {:maxByteLength (opfs/dirty-map-bytes max-len)})
        fail? (atom false)
        files (atom {7 {:buf buf :disk (js/Uint8Array. max-len) :fail? fail?}})
        calls (atom [])
        store (MemStore. files calls)]
    {:buf buf :dirty dirty :store store :calls calls :fail? fail?
     :disk (get-in @files [7 :disk])
     :region (opfs/opfs-region buf dirty file-len store 7)}))

(defn- disk-i32 [^js disk off]
  (.getInt32 (js/DataView. (.-buffer disk)) off true))

(deftest sync-writes-only-dirty-pages
  (let [{:keys [region calls disk]} (fixture (* 16 4096) (* 64 4096))]
    (is (satisfies? mem/ISyncableRegion region))
    (mem/-sync! region)
    (is (= [] @calls) "a clean file hands the store nothing")
    (mem/-store-i32! region 8 42)
    (mem/-cas-i32! region (+ 4096 16) 0 7)
    (mem/-write-bytes! region (* 5 4096) (js/Uint8Array. #js [1 2 3]))
    (mem/-fill-i32! region (- (* 9 4096) 4) 2 -1)
    (mem/-sync! region)
    (is (= [[0 8192 (* 5 4096) 4096 (* 8 4096) 8192]] @calls)
        "pages 0-1, 5 and 8-9: adjacent pages are one run")
    (is (= 42 (disk-i32 disk 8)))
    (is (= 7 (disk-i32 disk (+ 4096 16))))
    (is (= -1 (disk-i32 disk (* 9 4096))))
    (reset! calls [])
    (mem/-sync! region)
    (is (= [] @calls) "a second sync has nothing left")))

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
    (is (= [[0 4096]] @calls) "the range's page was claimed; page 0 still dirty")))

(deftest a-failed-store-keeps-its-pages
  (let [{:keys [region calls fail? disk]} (fixture (* 4 4096) (* 4 4096))]
    (mem/-store-i32! region (* 2 4096) 9)
    (reset! fail? true)
    (is (thrown? js/Error (mem/-sync! region)))
    (reset! fail? false)
    (mem/-sync! region)
    (is (= [[(* 2 4096) 4096]] @calls))
    (is (= 9 (disk-i32 disk (* 2 4096))))))

(deftest regions-over-one-file-share-it
  (let [{:keys [buf dirty store calls disk region]} (fixture 4096 (* 32 4096))
        _     (.grow buf (* 20 4096))
        _     (.grow dirty (opfs/dirty-map-bytes (* 20 4096)))
        big   (opfs/opfs-region buf dirty (* 20 4096) store 7)]
    (is (= 4096 (mem/-byte-length region)))
    (is (= (* 20 4096) (mem/-byte-length big)))
    (mem/-store-i32! big (* 19 4096) 5)
    (mem/-store-i32! region 0 6)
    (is (= 6 (mem/-load-i32 big 0)) "one buffer")
    (mem/-sync! region)
    (is (= [[0 4096 (* 19 4096) 4096]] @calls) "a sync from either carries the file's pages")
    (is (= 5 (disk-i32 disk (* 19 4096))))
    (testing "a region longer than its buffer is refused"
      (is (thrown? js/Error (opfs/opfs-region buf dirty (* 40 4096) store 7))))))

(deftest clipped-to-the-file
  (let [{:keys [region calls]} (fixture 5000 8192)]
    (mem/-store-i32! region 4996 1)
    (mem/-sync! region)
    (is (= [[4096 904]] @calls) "the last page's run ends at the file's end")))

(deftest i64-and-rmw-mark
  (let [{:keys [region calls disk]} (fixture (* 8 4096) (* 8 4096))]
    (mem/-store-i64! region (* 6 4096) 0x123456789)
    (mem/-add-i32! region (* 4 4096) 3)
    (mem/-sub-i32! region (* 4 4096) 1)
    (mem/-exchange-i32! region (* 2 4096) 11)
    (mem/-sync! region)
    (is (= [[(* 2 4096) 4096 (* 4 4096) 4096 (* 6 4096) 4096]] @calls))
    (is (= 2 (disk-i32 disk (* 4 4096))))
    (is (= 0x123456789 (mem/-load-i64 region (* 6 4096))))))
