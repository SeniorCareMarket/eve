(ns eve.flat.z1-diag2
  "Z1.7 diagnostic round 2 (finisher #4): bisect L3. diag1 showed word reads
   at a FIXED offset at parity (L0/L1) while full get/nth blow up 73x/17x.
   Layers here, one session, min of 5 after 2 warmups:
   G0 full get x50k (reproduce)          G1 key-bytes+hash x50k (shared prep)
   G2 hamt-get-words, precomputed kbkh, slab sio vs view sio (descent only)
   G3 hamt-get-view on the slab sio (the production descent)
   G4 node-i32 x200k at VARIED real block offsets of the image (view only)
   G5 -sio-read-i32 x200k, same varied offsets (the op under node-i32)
   G6 node-bytes x50k (the per-get value-bytes copy), slab vs view
   G7 full vec nth x50k (reproduce)
   Run: java -Xmx4g -cp <jvm-test classpath> clojure.main -m eve.flat.z1-diag2"
  (:require [eve.deftype-proto.data :as d]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.map]
            [eve.flat.image :as img])
  (:import [java.util Random]))

(defn- bench-min [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i runs)
      best
      (let [t0 (System/nanoTime) _ (f) ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms))))))

(def ^:private get-words (var eve.map/hamt-get-words))
(def ^:private get-view (var eve.map/hamt-get-view))
(def ^:private kb+hash (var eve.map/key-bytes+hash))

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
            ;; HAMT root node offset = header field at +8 on each arm's own sio
            sroot (alloc/-sio-read-i32 sio moff 8)
            vroot (alloc/-sio-read-i32 mview (img/image-root mim) 8)
            r (Random. 42)
            ks (int-array (repeatedly 50000 #(.nextInt r 10000)))
            kbkhs (object-array (map (fn [i] (kb+hash (aget ks i))) (range (alength ks))))
            nf (Object.)
            g0 (fn [mm] (loop [i 0 s 0] (if (== i (alength ks)) s (recur (inc i) (+ s (get mm (aget ks i)))))))
            g1 (fn [] (loop [i 0 s 0] (if (== i (alength ks)) s (recur (inc i) (+ s (long (nth (kb+hash (aget ks i)) 1)))))))
            g2 (fn [s root] (loop [i 0 acc 0] (if (== i (alength ks)) acc (recur (inc i) (+ acc (long (get-words s root (aget kbkhs i) nf)))))))
            g3 (fn [] (loop [i 0 s 0] (if (== i (alength ks)) s (recur (inc i) (+ s (long (get-view sio sroot (aget kbkhs i) nf)))))))
            ;; varied real offsets: the image's own block offsets, cycled
            boffs (int-array (map :image-off (img/image-blocks mim)))
            nb (alength ^ints boffs)
            g4 (fn [] (loop [i 0 acc 0] (if (== i 200000) acc (recur (inc i) (+ acc (alloc/node-i32 mview (aget ^ints boffs (mod i nb)) 4))))))
            g5 (fn [] (loop [i 0 acc 0] (if (== i 200000) acc (recur (inc i) (+ acc (alloc/-sio-read-i32 mview (aget ^ints boffs (mod i nb)) 4))))))
            g6 (fn [s root] (loop [i 0 acc 0] (if (== i 50000) acc (recur (inc i) (+ acc (alength (alloc/node-bytes s root 4 8)))))))
            r2 (Random. 43)
            ks2 (int-array (repeatedly 50000 #(.nextInt r2 100000)))
            g7 (fn [vv] (loop [i 0 s 0] (if (== i (alength ks2)) s (recur (inc i) (+ s (nth vv (aget ks2 i)))))))]
        (println (format "DIAG2 G0 get x50k        slab %.2f ms  image %.2f ms" (bench-min 2 5 #(g0 m)) (bench-min 2 5 #(g0 miv))))
        (println (format "DIAG2 G1 keyprep x50k    %.2f ms" (bench-min 2 5 g1)))
        (println (format "DIAG2 G2 words-descent   slab %.2f ms  view %.2f ms" (bench-min 2 5 #(g2 sio sroot)) (bench-min 2 5 #(g2 mview vroot))))
        (println (format "DIAG2 G3 view-descent    slab %.2f ms" (bench-min 2 5 g3)))
        (println (format "DIAG2 G4 node-i32 varied view %.2f ms (x200k)" (bench-min 2 5 g4)))
        (println (format "DIAG2 G5 raw-i32 varied  view %.2f ms (x200k)" (bench-min 2 5 g5)))
        (println (format "DIAG2 G6 node-bytes x50k slab %.2f ms  view %.2f ms" (bench-min 2 5 #(g6 sio sroot)) (bench-min 2 5 #(g6 mview vroot))))
        (println (format "DIAG2 G7 nth x50k        slab %.2f ms  image %.2f ms" (bench-min 2 5 #(g7 v)) (bench-min 2 5 #(g7 viv))))
        (flush))))
  (System/exit 0))
