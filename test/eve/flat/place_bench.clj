(ns eve.flat.place-bench
  "Lane Z3's board row B1 (the zip, alone): for each M6 corpus, build the
   hand image once, then time eve.flat.place/place! into a fresh pre-sized
   heap domain, net of domain creation (the probe discipline: the domain is
   made outside the timing, its cost measured separately). Min of 5 runs
   after 2 warmups. Reports ns, blocks, ns/block and the claim mode this
   tree's alloc/alloc-run! answers in (W0 default per-block loop vs a real
   run claim), so a figure always names the tree it came from.

   Run: CP=$(clojure -Spath -X:jvm-test) && java --add-opens
   java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -cp
   \"$CP\" clojure.main -e \"(require 'eve.flat.place-bench)
   (eve.flat.place-bench/-main)\" — there is no :test alias in deps.edn;
   the :jvm-test alias carries the test paths (corrected 2026-11-12)."
  (:require [eve.core :as e]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.flat.corpora :as corpora]
            [eve.flat.place :as place]
            [eve.flat.place-test :as pt])
  (:import [eve.deftype_proto.alloc JvmSlabCtx]))

(def ^:private caps
  {0 (* 4 1024 1024) 1 (* 4 1024 1024) 2 (* 8 1024 1024) 3 (* 4 1024 1024) 4 (* 2 1024 1024) 5 (* 2 1024 1024)})

(defn- claim-mode []
  (let [sio (alloc/make-jvm-heap-slab-ctx {:capacities {0 (* 32 4096)}})
        r (alloc/alloc-run! sio 0 4)]
    (cond (and (sequential? r)
               (== 3 (- (alloc/decode-block-idx (nth r 3)) (alloc/decode-block-idx (nth r 0)))))
          "run (consecutive offsets)"
          (number? r)
          "run (first-offset scalar — the run's blocks are consecutive)"
          :else
          "default (per-block answers)")))

(defn- bench-one [label v]
  (let [img (pt/hand-image v)
        nblocks (count (:blocks img))
        mk (fn [] (alloc/make-jvm-heap-slab-ctx {:capacities caps}))
        _ (dotimes [_ 2] (place/place! (mk) img))
        times (mapv (fn [_]
                      (let [sio (mk)
                            t0 (System/nanoTime)
                            root (place/place! sio img)
                            t1 (System/nanoTime)]
                        (when (not= v (d/eve->clj (pt/reopen sio root)))
                          (throw (ex-info "bench: placed value differs" {:label label})))
                        (/ (- t1 t0) 1e6)))
                    (range 5))
        best (reduce min times)]
    {:label label :blocks nblocks :ms best :ns-per-block (/ (* best 1e6) nblocks)}))

(defn -main [& _]
  (println "B1 zip — claim mode:" (claim-mode))
  (doseq [[kind v] (corpora/corpora :canonical)]
    (let [r (bench-one kind v)]
      (println (format "%-22s blocks %7d   %8.2f ms   %6.1f ns/block"
                       (get corpora/corpus-labels kind) (:blocks r) (:ms r) (double (:ns-per-block r))))))
  (System/exit 0))
