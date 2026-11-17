(ns eve.flat.z1-diag
  "Z1.7 diagnostic (finisher #3): where does the image read arm's time go?
   Layers, each min of 5 after 2 warmups, one session:
   L0 raw alloc/-sio-read-i32 at a fixed offset (slab sio vs image view sio)
   L1 alloc/node-i32 at a fixed offset (the HAMT/trie read verb)
   L2 raw alloc/-sio-read-bytes of 8 bytes
   L3 the bench itself: 50k random get (map 10k) / nth (vec 100k).
   Run: java -Xmx4g -cp <jvm-test classpath> clojure.main -m eve.flat.z1-diag"
  (:require [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.flat.image :as img])
  (:import [java.util Random]))

(defn- bench-min [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i runs)
      best
      (let [t0 (System/nanoTime) _ (f) ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms))))))

(defn -main [& _]
  (let [sio (alloc/heap-domain-sio! {})]
    (binding [d/*domain-sio* sio]
      (let [moff (codec/write-collection! :map sio (zipmap (range 10000) (range 10000)))
            m ((ser/get-header-constructor (ser/read-header-tag sio moff)) sio moff)
            voff (codec/write-collection! :vec sio (vec (range 100000)))
            v ((ser/get-header-constructor (ser/read-header-tag sio voff)) sio voff)
            mim (img/open-image (img/image-bytes (img/detach-image m)))
            vim (img/open-image (img/image-bytes (img/detach-image v)))
            miv (img/image-value mim)
            viv (img/image-value vim)
            mview (img/image-view mim)
            mroot (img/image-root mim)
            raw (fn [s o] (loop [i 0 acc 0] (if (== i 200000) acc (recur (inc i) (+ acc (alloc/-sio-read-i32 s o 4))))))
            nd (fn [s o] (loop [i 0 acc 0] (if (== i 200000) acc (recur (inc i) (+ acc (alloc/node-i32 s o 4))))))
            rb (fn [s o] (loop [i 0 acc 0] (if (== i 100000) acc (recur (inc i) (+ acc (alength (alloc/-sio-read-bytes s o 0 8)))))))
            r (Random. 42)
            ks (int-array (repeatedly 50000 #(.nextInt r 10000)))
            r2 (Random. 43)
            ks2 (int-array (repeatedly 50000 #(.nextInt r2 100000)))
            rd (fn [mm] (loop [i 0 s 0] (if (== i (alength ks)) s (recur (inc i) (+ s (get mm (aget ks i)))))))
            rn (fn [vv] (loop [i 0 s 0] (if (== i (alength ks2)) s (recur (inc i) (+ s (nth vv (aget ks2 i)))))))]
        (println (format "DIAG L0 raw-i32 x200k  slab %.2f ms  view %.2f ms" (bench-min 2 5 #(raw sio moff)) (bench-min 2 5 #(raw mview mroot))))
        (println (format "DIAG L1 node-i32 x200k slab %.2f ms  view %.2f ms" (bench-min 2 5 #(nd sio moff)) (bench-min 2 5 #(nd mview mroot))))
        (println (format "DIAG L2 read-bytes8 x100k slab %.2f ms  view %.2f ms" (bench-min 2 5 #(rb sio moff)) (bench-min 2 5 #(rb mview mroot))))
        (println (format "DIAG L3 map get x50k  slab %.2f ms  image %.2f ms" (bench-min 2 5 #(rd m)) (bench-min 2 5 #(rd miv))))
        (println (format "DIAG L3 vec nth x50k  slab %.2f ms  image %.2f ms" (bench-min 2 5 #(rn v)) (bench-min 2 5 #(rn viv))))
        (flush))))
  (System/exit 0))
