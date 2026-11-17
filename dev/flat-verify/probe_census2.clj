;; Census discriminator: WHERE does a frozen chain fail to
;; retire? Three variants, each in a fresh domain, each ending
;; at an empty root after churn, so the live-root confound is
;; gone and class 5 (the 1024B chain class) tells the story:
;;   R  = frozen value as the ROOT, replaced
;;   N  = frozen value nested in a map, frozen inside the swap
;;   N2 = frozen value nested in a map, frozen outside first
;; NOTE (Nov 14 correction): the N/N2 cases originally ended
;; with a swap that only assoc'd :n — the frozen value was
;; still IN the root, so its chain was live, not leaked; the
;; 227 class-5 blocks were a phantom. Both cases now remove
;; the value wholesale before the final census, like R and V
;; always did. (Discriminator: probe_census5 — the value
;; reads back live while held, and retires to baseline exact
;; once removed.)
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

(defn- churn [a]
  (dotimes [i 30]
    (swap! a (fn [v] v))
    (Thread/sleep 30)))

(def rows (:records-10k (into {} (corpora/corpora :canonical))))

;; R: root install of an outside-frozen value, then replaced
(def aR (ea/atom {:id :census-r} []))
(def sioR (ea/atom-sio aR))
(def baseR (per-class-used sioR))
(def fvR (freeze/freeze-value sioR rows))
(swap! aR (fn [_] fvR))
(swap! aR (fn [_] []))
(churn aR)
(println :R {:base baseR :final (per-class-used sioR)})

;; N: nested, frozen inside the swap fn
(def aN (ea/atom {:id :census-n} {:data [] :n 5}))
(def sioN (ea/atom-sio aN))
(def baseN (per-class-used sioN))
(swap! aN (fn [cur] (assoc cur :data (freeze/freeze-value sioN rows))))
(swap! aN (fn [_] {:data [] :n 6}))
(churn aN)
(println :N {:base baseN :final (per-class-used sioN)})

;; N2: nested, frozen outside first (the census-1 shape)
(def aN2 (ea/atom {:id :census-n2} {:data [] :n 5}))
(def sioN2 (ea/atom-sio aN2))
(def baseN2 (per-class-used sioN2))
(def fvN2 (freeze/freeze-value sioN2 rows))
(swap! aN2 (fn [cur] (assoc cur :data fvN2)))
(swap! aN2 (fn [_] {:data [] :n 6}))
(churn aN2)
(println :N2 {:base baseN2 :final (per-class-used sioN2)})

;; V: frozen value as a VECTOR element, replaced
(def aV (ea/atom {:id :census-v} [[] 5]))
(def sioV (ea/atom-sio aV))
(def baseV (per-class-used sioV))
(def fvV (freeze/freeze-value sioV rows))
(swap! aV (fn [_] [fvV 5]))
(swap! aV (fn [_] [[] 6]))
(churn aV)
(println :V {:base baseV :final (per-class-used sioV)})
(println :DONE)
(System/exit 0)
