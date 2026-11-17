;; S4 acceptance: host data containing heap flat handles,
;; swapped into an atom, EMBEDS the flat parts as frozen
;; chains (the raw stored child is a FrozenValue) instead of
;; walking them into plain blocks. Deref and delivery read
;; the logical value back whole.
(require '[eve.atom :as ea] '[eve.flat.freeze :as freeze]
         '[eve.flat.corpora :as corpora])
(def rows (vec (take 1500 (:records-10k (into {} (corpora/corpora :canonical))))))
(def rows10k (:records-10k (into {} (corpora/corpora :canonical))))
(def dv (freeze/deliver-flat rows))
(def dv10 (freeze/deliver-flat rows10k))
(def a (ea/atom {:id :embed-a} {:data [] :n 5}))
(swap! a (fn [cur] (assoc cur :data dv)))
(def raw (ea/read-pinned a (fn [r] r)))
(println :embed {:raw-child-class (class (:data raw))
                 :deref-equal (= @a {:data rows :n 5})
                 :deliver-class (class (freeze/deliver-atom a))
                 :deliver-equal (= (freeze/deliver-atom a) {:data rows :n 5})})
;; multi-chunk (ConcatBatch) handle, nested one level deeper
(def dir (str (System/getProperty "java.io.tmpdir") "/embed-mmap-" (System/nanoTime)))
(def b (ea/atom {:id :embed-b} {:outer []} {:mmap-dir dir}))
(swap! b (fn [cur] (assoc cur :outer [dv10])))
(def rawb (ea/read-pinned b (fn [r] r)))
(println :embed-deep {:raw-grandchild-class (class (nth (:outer rawb) 0))
                      :deref-equal (= @b {:outer [rows10k]})})
(println :DONE)
(System/exit 0)
