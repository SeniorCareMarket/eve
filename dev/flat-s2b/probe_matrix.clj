;; THE swap matrix: ONE experiment. Every case x size measured
;; in this one JVM, interleaved round-robin over 5 rounds so
;; box load hits every cell equally; each cell reports the min
;; ms/swap across rounds. Cases vary ONE factor at a time:
;;   replace-plain   root <- brand-new plain heap copy
;;   replace-frozen  root <- prebuilt frozen value
;;   freeze-in-swap  root <- freeze the plain copy in the swap
;;   edit-root       assoc-in one cell of the plain root
;;   edit-frozen     assoc-in one cell of the frozen root
;;                   (the upgrade; frozen reinstalled per rep)
;;   edit-deep       assoc-in one cell at depth 4 of a tree
;;   replace-sub     one subtree <- brand-new plain heap copy
(require '[eve.atom :as ea] '[eve.flat.freeze :as freeze]
         '[eve.flat.corpora :as corpora])
(def cmap (into {} (corpora/corpora :canonical)))
(def rows10k (:records-10k cmap))
(def SIZES [{:tag :100 :rows (subvec rows10k 0 100)}
            {:tag :1500 :rows (subvec rows10k 0 1500)}
            {:tag :10000 :rows rows10k}])
(defn variant [rows] (mapv (fn [r] (update r :id + 100000)) rows))
(def results (atom {}))
(defn run-cell [label size reps f]
  ;; batch timing; a cell fn may instead return per-rep ms
  ;; values (edit-frozen: the frozen root is reinstalled
  ;; untimed before each timed edit) — then the cell value is
  ;; the min per-rep time this round.
  (let [t0 (System/nanoTime)
        rets (loop [i 0 acc []]
               (if (== i reps) acc (recur (inc i) (conj acc (f)))))]
    (let [ms (if (number? (first rets))
               (apply min rets)
               (/ (- (System/nanoTime) t0) 1e6 reps))]
      (swap! results update [label size] (fn [old] (min (or old ms) ms))))))
;; build per-size state
(def envs
  (into {}
        (for [{:keys [tag rows]} SIZES]
          (let [rowsB (variant rows)
                dir (str (System/getProperty "java.io.tmpdir") "/mx-" (name tag))
                a (ea/atom rows {:mmap-dir dir :file-size (* 256 1024 1024)})
                sio (ea/atom-sio a)
                fvA (freeze/freeze-value sio rows)
                fvB (freeze/freeze-value sio rowsB)
                tree {:a rows :b {:c {:d rowsB}}}
                ta (ea/atom tree {:mmap-dir (str dir "-t") :file-size (* 256 1024 1024)})
                tog (volatile! false)
                ecnt (volatile! 0)]
            [tag {:rows rows :rowsB rowsB :a a :sio sio
                  :fvA fvA :fvB fvB :ta ta :tog tog :ecnt ecnt}]))))
(def REPS ;; [label size] -> reps per round
  {[:replace-plain :100] 30 [:replace-plain :1500] 8 [:replace-plain :10000] 2
   [:replace-frozen :100] 30 [:replace-frozen :1500] 30 [:replace-frozen :10000] 30
   [:freeze-in-swap :100] 10 [:freeze-in-swap :1500] 3 [:freeze-in-swap :10000] 1
   [:edit-root :100] 30 [:edit-root :1500] 30 [:edit-root :10000] 20
   [:edit-frozen :100] 15 [:edit-frozen :1500] 5 [:edit-frozen :10000] 2
   [:edit-deep :100] 30 [:edit-deep :1500] 30 [:edit-deep :10000] 20
   [:replace-sub :100] 30 [:replace-sub :1500] 8 [:replace-sub :10000] 2})
(defn cell-fn [label {:keys [rows rowsB a ta fvA fvB tog ecnt]}]
  (case label
    :replace-plain (fn [] (swap! a (fn [_] (if (vswap! tog not) rows rowsB))))
    :replace-frozen (fn [] (swap! a (fn [_] (if @tog fvA fvB))))
    :freeze-in-swap (fn [] (swap! a (fn [_] (freeze/freeze-value (ea/atom-sio a)
                                                              (if (vswap! tog not) rows rowsB)))))
    :edit-root (fn [] (swap! a (fn [cur] (assoc-in cur [0 :name] (str "n" (vswap! ecnt inc))))))
    :edit-frozen (fn [] (swap! a (fn [_] fvA))
                        (let [t0 (System/nanoTime)]
                          (swap! a (fn [cur] (assoc-in cur [0 :name] (str "n" (vswap! ecnt inc)))))
                          (/ (- (System/nanoTime) t0) 1e6)))
    :edit-deep (fn [] (swap! ta (fn [cur] (assoc-in cur [:b :c :d 0 :name] (str "n" (vswap! ecnt inc))))))
    :replace-sub (fn [] (swap! ta (fn [cur] (assoc cur :a (if (vswap! tog not) rows rowsB)))))))
(def CASES [:replace-plain :edit-root :replace-frozen :edit-frozen
            :freeze-in-swap :edit-deep :replace-sub])
;; every cell announces itself before it runs and catches its
;; own throw, so a failure names its cell instead of killing
;; the session anonymously; a throwing cell records no time.
(def failures (atom []))
(defn guarded [label tag f]
  (println :cell label tag) (flush)
  (try (f)
       (catch Throwable t
         (swap! failures conj [label tag])
         (println :cell-THREW label tag (str (.getName (class t)))
                  (.getMessage t) (pr-str (ex-data t))) (flush))))
;; warmup: one unmeasured round at low reps
(doseq [label CASES {:keys [tag]} SIZES]
  (when-let [r (get REPS [label tag])]
    (guarded label tag
             (fn [] (dotimes [_ (min 2 r)] ((cell-fn label (envs tag))))))))
(doseq [round (range 5)]
  (doseq [label CASES {:keys [tag]} SIZES]
    (when-let [r (get REPS [label tag])]
      (guarded label tag
               (fn [] (run-cell label tag r (cell-fn label (envs tag)))))))
  (println :round-done round))
(when (seq @failures) (println :FAILURES @failures))
(doseq [label CASES {:keys [tag]} SIZES]
  (println :MATRIX {:case label :size tag
                    :ms-per-swap (format "%.3f" (get @results [label tag]))}))
(println :DONE)
(System/exit 0)
