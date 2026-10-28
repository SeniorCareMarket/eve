;; leak_probe_oneop.clj — the sorted set of bench/eve/leak_probe.clj, written one persistent operation a swap
;; (a disj on one swap, the conj back on the next) against two in one swap (disj then conj: the disj's tree is a
;; persistent intermediate the swap makes and drops, which doc/memory-model.md says stays). Blocks in use in the disk
;; domain after N swaps.
(require '[eve.atom :as ea])
(import '[java.io RandomAccessFile] '[java.nio ByteBuffer ByteOrder])
(defn hdr-used [path]
  (let [f (java.io.File. path)]
    (if-not (.exists f) 0
      (with-open [r (RandomAccessFile. f "r")]
        (let [b (byte-array 16) _ (.readFully r b)
              bb (doto (ByteBuffer/wrap b) (.order ByteOrder/LITTLE_ENDIAN))]
          (- (.getInt bb 8) (.getInt bb 12)))))))
(defn used [base] (reduce + (for [c (range 6)] (hdr-used (str base ".slab" c)))))
(defn run [label init step]
  (let [base (str "/tmp/eve-leak-" label "-" (System/nanoTime) "/db")
        _ (.mkdirs (.getParentFile (java.io.File. base)))
        a (ea/persistent-atom {:id (keyword "leak" label) :persistent base} {})]
    (swap! a (fn [_] (init)))
    (let [marks (loop [i 0 out []]
                  (if (> i 1280) out
                      (let [out (if (#{40 160 320 640 960 1280} i) (conj out [i (used base)]) out)]
                        (swap! a (fn [m] (step m i)))
                        (recur (inc i) out))))]
      (println label "blocks in use after N swaps:" marks)
      (flush))))
(require '[eve.core :as e] '[eve.sorted :as so])
(let [e-assoc (resolve 'eve.core/assoc) e-get (resolve 'eve.core/get) e-conj (resolve 'eve.core/conj)
      e-disj (resolve 'eve.core/disj) sorted-set (resolve 'eve.sorted/sorted-set)
      ks (vec (repeatedly 641 #(rand-int 200)))]
  (run "sorted-set-two-ops-a-swap" (fn [] {:s (apply sorted-set (range 200))})
       (fn [m i] (let [k (ks (quot i 2))] (e-assoc m :s (e-conj (e-disj (e-get m :s) k) k)))))
  (run "sorted-set-one-op-a-swap" (fn [] {:s (apply sorted-set (range 200))})
       (fn [m i] (let [k (ks (quot i 2))]
                   (e-assoc m :s (if (even? i) (e-disj (e-get m :s) k) (e-conj (e-get m :s) k)))))))
(shutdown-agents)
(System/exit 0)
