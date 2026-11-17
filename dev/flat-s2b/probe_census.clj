;; Canonical retire census (UPGRADE-PLAN S3, owed): freeze
;; 10,000 rows into a fresh domain, install nested, replace
;; the root, churn — the per-class used census must return
;; to the baseline. A leak shows as a positive delta.
(require '[eve.atom :as ea] '[eve.flat.freeze :as freeze]
         '[eve.flat.corpora :as corpora]
         '[eve.deftype-proto.data :as d] '[eve.mem :as mem])
(import '[eve.deftype_proto.alloc JvmSlabCtx])

(defn- per-class-used [sio]
  (let [ctx (loop [s sio] (if (instance? JvmSlabCtx s) s (recur (.-inner s))))
        regions (.regions ^JvmSlabCtx ctx)]
    (mapv (fn [ci]
            (let [region (aget ^objects regions ci)]
              (- (mem/-load-i32 region d/SLAB_HDR_TOTAL_BLOCKS)
                 (mem/-load-i32 region d/SLAB_HDR_FREE_COUNT))))
          (range (alength ^objects regions)))))

(def rows (:records-10k (into {} (corpora/corpora :canonical))))
(def a (ea/atom {:id :census-a} []))
(def sio (ea/atom-sio a))
(def c0 (per-class-used sio))
(def fv (freeze/freeze-value sio rows))
(def c1 (per-class-used sio))
(swap! a (fn [_] {:data [] :n 5}))
(swap! a (fn [cur] (assoc cur :data fv)))
(println :installed-deref (= @a {:data rows :n 5}))
(def c2 (per-class-used sio))
(swap! a (fn [_] []))
;; churn: distinct plain roots, with pauses so the retire
;; flush (throttled) runs and the zero queue drains
(dotimes [i 60]
  (swap! a (fn [_] (vec (range (+ 500 (* 37 i))))))
  (Thread/sleep 30))
(def c3 (per-class-used sio))
(println :census {:baseline c0
                  :after-freeze c1
                  :after-install c2
                  :after-churn c3
                  :total-baseline (reduce + c0)
                  :total-final (reduce + c3)
                  :leaked (- (reduce + c3) (reduce + c0))})
(println :DONE)
(System/exit 0)
