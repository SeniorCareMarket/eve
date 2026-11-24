(ns eve.opfs-browser-test
  "eve.opfs in a browser (the OPFS disk tier spec, W3/W4): run in a worker of a page that mounted the pool before its
   threads started. A region's synced bytes are the file's after the io worker closes every handle and the file is
   opened afresh; workers that open one path share its bytes; a file reopened bigger keeps its bytes; and the file
   operations eve.process needs answer as a file system does; and a persistent atom (an mmap domain: eve.region's
   open-mmap-region opens through eve.opfs here) keeps its value across a domain close, another worker's swap and
   the io worker closing every file."
  (:require
   [cljs.test :refer [deftest is testing]]
   [eve.atom :as atom]
   [eve.mem :as mem]
   [eve.opfs :as opfs]
   [eve.region]
   [eve.thread-host :as h]))

(def ^:private dir (str "t" (js/Date.now)))

(defn- p [f] (str dir "/" f))

(def ^:private exits (atom []))

(defn- await-op [op n ms]
  (let [deadline (+ (js/Date.now) ms)]
    (loop [got []]
      (if (= n (count got))
        got
        (let [left (- deadline (js/Date.now))
              m (if (pos? left) (h/receive! left) :eve.thread/timeout)]
          (cond (= m :eve.thread/timeout) got
                (and (map? m) (= op (:op m))) (recur (conj got m))
                (and (map? m) (:eve.thread/exit m) (not= :normal (:reason m)))
                (do (swap! exits conj m) got)
                :else (recur got)))))))

(defn adder
  "A worker's entry: add 1 n times to the i32 at 0 of the file at path, sync, report."
  [{:keys [reply path n]}]
  (let [r (opfs/open-region path 4096)]
    (dotimes [_ n] (mem/-add-i32! r 0 1))
    (mem/-sync! r)
    (h/send! reply {:op :added :self (h/self)})))

(deftest synced-bytes-are-the-files
  (is (some? (opfs/pool-of)) "the worker's start carried the pool")
  (is (contains? #{"readwrite-unsafe" "readwrite"} (opfs/handle-mode)) "the mode the io worker probed")
  (let [r (opfs/open-region (p "a.bin") 10000)]
    (is (= 10000 (mem/-byte-length r)))
    (mem/-store-i32! r 0 11)
    (mem/-store-i32! r 4096 22)
    (mem/-write-bytes! r 9990 (js/Uint8Array. #js [1 2 3 4 5 6 7 8 9 10]))
    (mem/-sync! r)
    (mem/-store-i32! r 8 33)                            ; never synced
    (opfs/close-all!)
    (let [r2 (opfs/open-region (p "a.bin") 10000)]
      (is (= 11 (mem/-load-i32 r2 0)))
      (is (= 22 (mem/-load-i32 r2 4096)))
      (is (= [1 2 3 4 5 6 7 8 9 10] (vec (array-seq (mem/-read-bytes r2 9990 10)))))
      (is (= 0 (mem/-load-i32 r2 8)) "a write after the last sync is not the file's")
      (is (= 10000 (opfs/file-size (p "a.bin")))))))

(deftest sync-range-only-its-pages
  (let [r (opfs/open-region (p "b.bin") (* 3 4096))]
    (mem/-store-i32! r 0 1)
    (mem/-store-i32! r (* 2 4096) 2)
    (mem/sync-region! r (* 2 4096) 4)
    (opfs/close-all!)
    (let [r2 (opfs/open-region (p "b.bin") (* 3 4096))]
      (is (= 0 (mem/-load-i32 r2 0)) "page 0 was not synced")
      (is (= 2 (mem/-load-i32 r2 (* 2 4096)))))))

(deftest reopened-bigger-keeps-its-bytes
  (let [small (opfs/open-region (p "c.bin") 64)
        _ (mem/-store-i32! small 0 7)
        big (opfs/open-region (p "c.bin") (* 1024 1024))]
    (is (= 7 (mem/-load-i32 big 0)) "one buffer")
    (mem/-store-i32! big (- (* 1024 1024) 4) 9)
    (is (= 64 (mem/-byte-length small)))
    (mem/-sync! small)
    (opfs/close-all!)
    (let [r (opfs/open-region (p "c.bin") 64)]
      (is (= 7 (mem/-load-i32 r 0)))
      (is (= (* 1024 1024) (opfs/file-size (p "c.bin"))) "a file never shrinks on open")
      (let [r (opfs/open-region (p "c.bin") (* 1024 1024))]
        (is (= 9 (mem/-load-i32 r (- (* 1024 1024) 4))) "the small region's sync carried the file's pages")))))

(deftest workers-share-a-file
  (let [path (p "shared.bin")
        n 2000
        addrs (h/start-threads! 4 'eve.opfs-browser-test/adder {:reply (h/self) :path path :n n} {})
        got (await-op :added 4 60000)]
    (is (= 4 (count got)))
    (opfs/close-all!)
    (is (= (* 4 n) (mem/-load-i32 (opfs/open-region path 4096) 0)))
    (h/stop-threads! addrs)))

(deftest file-operations
  (is (false? (opfs/file-exists? (p "none"))))
  (is (nil? (opfs/file-size (p "none"))))
  (is (true? (opfs/create-exclusive! (p "x/y/new"))))
  (is (false? (opfs/create-exclusive! (p "x/y/new"))))
  (is (true? (opfs/file-exists? (p "x/y/new"))))
  (is (= 0 (opfs/file-size (p "x/y/new"))))
  (let [r (opfs/open-region (p "x/from") 100)]
    (mem/-store-i32! r 0 5)
    (mem/-sync! r))
  (opfs/close-all!)
  (opfs/rename-file! (p "x/from") (p "x/to"))
  (is (false? (opfs/file-exists? (p "x/from"))))
  (is (= 5 (mem/-load-i32 (opfs/open-region (p "x/to") 100) 0)))
  (opfs/close-all!)
  (is (true? (opfs/delete-file! (p "x/to"))))
  (is (false? (opfs/delete-file! (p "x/to"))))
  (testing "errors come back as ex-info"
    (is (thrown-with-msg? js/Error #"not a file path" (opfs/file-size "../up")))))

(defn joiner
  "A worker's entry: join the domain at base, swap :count up, close, report."
  [{:keys [reply base]}]
  (let [d (atom/persistent-atom-domain base)
        a (atom/persistent-atom {:id :eve/main :persistent base} nil)
        n (:count (swap! a update :count inc))]
    (atom/close-atom-domain! d)
    (h/send! reply {:op :joined :count n})))

(deftest a-persistent-atom-on-opfs
  (let [b (p "db")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} {:count 0})]
    (swap! a update :count inc)
    (is (= 1 (:count @a)))
    (atom/close-atom-domain! d)
    (testing "another worker joins and swaps"
      (let [[w] (h/start-threads! 1 'eve.opfs-browser-test/joiner {:reply (h/self) :base b} {})
            got (await-op :joined 1 60000)]
        (is (= [2] (map :count got)) (pr-str @exits))
        (h/stop-threads! [w])))
    (testing "every file closed, the domain reopened from OPFS"
      (opfs/close-all!)
      (let [d2 (atom/persistent-atom-domain b)
            c (atom/persistent-atom {:id :eve/main :persistent b} nil)]
        (is (= 2 (:count @c)))
        (swap! c update :count inc)
        (atom/close-atom-domain! d2)))
    (opfs/close-all!)
    (let [d3 (atom/persistent-atom-domain b)
          c (atom/persistent-atom {:id :eve/main :persistent b} nil)]
      (is (= 3 (:count @c)))
      (atom/close-atom-domain! d3))))

(defn committer
  "A worker's entry: open the domain at base, commit :count n times, report, and never close (a tab that dies)."
  [{:keys [reply base n]}]
  (let [_d (atom/persistent-atom-domain base)
        a (atom/persistent-atom {:id :eve/main :persistent base} {:count 0})]
    (dotimes [_ n] (swap! a update :count inc))
    (h/send! reply {:op :committed :count (:count @a)})
    (loop [] (when-not (= :eve.thread/stop (h/receive! -1)) (recur)))))

(defn reader
  "A worker's entry: open the domain at base, report :count."
  [{:keys [reply base]}]
  (try
    (let [d (atom/persistent-atom-domain base)
          a (atom/persistent-atom {:id :eve/main :persistent base} nil)
          n (:count @a)]
      (atom/close-atom-domain! d)
      (h/send! reply {:op :read :count n}))
    (catch :default e
      (h/send! reply {:op :read :error (str e) :stack (some-> (.-stack e) (subs 0 1500))}))))

(deftest a-commit-survives-without-a-close
  (let [b (p "crash")
        [w] (h/start-threads! 1 'eve.opfs-browser-test/committer {:reply (h/self) :base b :n 25} {})
        got (await-op :committed 1 60000)]
    (is (= [25] (map :count got)) (pr-str @exits))
    ;; every handle closed with the committer's domain open: what the file holds is what its commits forced
    (opfs/close-all!)
    (let [[r] (h/start-threads! 1 'eve.opfs-browser-test/reader {:reply (h/self) :base b} {})
          got (await-op :read 1 60000)]
      (is (= [25] (map :count got)) (pr-str [@exits got]))
      (h/stop-threads! [w r]))))
