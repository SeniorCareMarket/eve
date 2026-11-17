;; Subtree form acceptance (UPGRADE-PLAN S3, governing
;; correction): freeze at the root and nested in a persistent
;; map; fixed-class audit; deref materializes; delivery
;; collates; sibling edit leaves the frozen subtree working.
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

(def rows (vec (take 1500 (:records-10k (into {} (corpora/corpora :canonical))))))
(def a (ea/atom {:id :subtree-a} []))
(def sio (ea/atom-sio a))
(println :classes-baseline (per-class-used sio))

;; --- freeze at the root ------------------------------------------------------
(def fv (freeze/freeze-value sio rows))
(println :classes-after-freeze (per-class-used sio))
(swap! a (fn [_] fv))
(println :root {:raw-class (ea/read-pinned a (fn [r] (class r)))
                :deref-class (class @a)
                :deref-equal (= @a rows)
                :deliver-equal (= (freeze/deliver-atom a) rows)
                :deliver-class (class (freeze/deliver-atom a))})

;; --- freeze nested in a persistent map ---------------------------------------
(def b (ea/atom {:id :subtree-b} {:data [] :n 5}))
(def sio-b (ea/atom-sio b))
(def base-classes (per-class-used sio-b))
(def fv-b (freeze/freeze-value sio-b rows))
(def frozen-classes (per-class-used sio-b))
(swap! b (fn [cur] (assoc cur :data fv-b)))
(println :nested {:deref-equal (= @b {:data rows :n 5})
                  :coalescing-before (nth base-classes 6)
                  :coalescing-after-freeze (nth frozen-classes 6)
                  :fixed-grew (> (reduce + (subvec frozen-classes 0 6))
                                 (reduce + (subvec base-classes 0 6)))})
(swap! b assoc :n 6)
(println :sibling-edit {:deref-equal (= @b {:data rows :n 6})
                        :data-name (:name (nth (:data @b) 700))})
;; replace the frozen subtree wholesale (a re-freeze of edited data)
(def rows-z (assoc rows 700 (assoc (nth rows 700) :name "zed")))
(swap! b (fn [cur] (assoc cur :data (freeze/freeze-value sio-b rows-z))))
(println :subtree-replace {:zed (= "zed" (:name (nth (:data @b) 700)))
                           :deref-equal (= @b {:data rows-z :n 6})})
(println :DONE)
(System/exit 0)
