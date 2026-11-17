;; Z2 bench — board row B1's allocator arm, probe-faithful (probes/lib.clj
;; ingest! :each vs :runs): an image's blocks of one class laid into a heap
;; domain, the copy by arraycopy into the domain's heap array (the zip's
;; copy path).
;;
;; Methodology (third form; the first two are recorded as failures in
;; Z2-STATUS): domain creation is NOT netted by subtraction — a heap
;; region's cost is lazy page commitment, which lands in whichever arm
;; touches the pages first, so subtracted nets went negative. Instead each
;; trial runs on its own fresh domain whose pages a warmup batch of
;; single alloc/free has already committed, and only the work is timed:
;; domain creation is excluded from both arms identically. Arms
;; interleaved, min over trials. Two rows per class: alloc-only and
;; alloc+copy, so the copy's dilution of the ratio is visible.
;;
;; Run:  clojure -M -e "(load-file \"doc/static/z2/bench.clj\")"
(ns z2.bench
  (:require [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.mem :as mem]
            [eve.slab-io :as slab])
  (:import [eve.deftype_proto.alloc JvmSlabCtx]))

(defn- domain [caps] (alloc/heap-domain-sio! {:capacities caps}))

(defn- copy-into!
  "src's size bytes into the block at slab-off, by arraycopy over the
   domain's heap array (probes/lib.clj ingest!'s copy)."
  [^JvmSlabCtx ctx slab-off ^bytes src]
  (System/arraycopy src 0 (alloc/node-heap-array ctx slab-off)
                    (int (alloc/node-base ctx slab-off)) (alength src)))

(defn run-bench []
  (let [alloc-run! (resolve 'eve.deftype-proto.alloc/alloc-run!)]
    (doseq [[ci n] [[0 20000] [2 20000] [3 5000]]]
      (let [size (d/slab-size ci)
            caps (assoc {0 65536 1 65536 2 65536 3 65536 4 65536 5 65536}
                        ci 4194304)
            src  (byte-array size)
            iters 7
            trial
            (fn [work]
              (loop [i iters best Long/MAX_VALUE]
                (if (zero? i)
                  best
                  (let [sio  (domain caps)
                        ;; commit the domain's pages before timing either arm:
                        ;; alloc/free the whole class in batches, so no timed
                        ;; copy first-touches a page (a 1,024-block warmup
                        ;; left class 3's run arm copying into virgin pages:
                        ;; 2,013 ns/block against a 99.5 ns/block claim)
                        total (mem/-load-i32 (aget ^objects (.regions (slab/domain-ctx sio)) ci)
                                             d/SLAB_HDR_TOTAL_BLOCKS)]
                    (loop [left total]
                      (when (pos? left)
                        (let [batch (mapv (fn [_] (slab/alloc! sio size))
                                          (range (min 4096 left)))]
                          (doseq [o batch] (slab/free! sio o))
                          (recur (- left (count batch))))))
                    (let [t0 (System/nanoTime) _ (work sio) dt (- (System/nanoTime) t0)]
                      (recur (dec i) (min best dt)))))))
            per-block (trial (fn [sio]
                               (let [ctx (slab/domain-ctx sio)]
                                 (dotimes [_ n]
                                   (copy-into! ctx (slab/alloc! sio size) src)))))
            per-block-alloc (trial (fn [sio]
                                     (dotimes [_ n] (slab/alloc! sio size))))
            run-c     (when alloc-run!
                        (trial (fn [sio]
                                 (let [ctx (slab/domain-ctx sio)
                                       s (alloc/decode-block-idx (first (alloc-run! sio ci n)))]
                                   (dotimes [i n]
                                     (copy-into! ctx (alloc/encode-slab-offset ci (+ s i)) src))))))
            run-alloc (when alloc-run!
                        (trial (fn [sio] (alloc-run! sio ci n))))
            run-raw   (trial (fn [sio] (alloc/-sio-alloc-run! sio ci n)))]
        (println (format "class %d (%dB) x %d blocks | per-block alloc+copy %.1f ns/block | alloc-only %.1f"
                         ci size n (/ (double per-block) n) (/ (double per-block-alloc) n)))
        (when run-c
          (println (format "   run claim+copy %.1f ns/block (%.2fx vs per-block) | claim-only %.1f ns/block (%.2fx vs alloc-only) | raw -sio-alloc-run! %.1f ns/block"
                           (/ (double run-c) n)
                           (/ (double per-block) (double run-c))
                           (/ (double run-alloc) n)
                           (/ (double per-block-alloc) (double (max 1 run-alloc)))
                           (/ (double run-raw) n))))))))

(run-bench)
