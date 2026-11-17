;; Deliver discriminator 2: pack the READ-PINNED root itself
;; and inspect the rep the frozen child becomes.
(require '[eve.atom :as ea] '[eve.flat.freeze :as freeze]
         '[eve.flat.chunk :as chunk] '[eve.flat.corpora :as corpora])
(def rows (vec (take 1500 (:records-10k (into {} (corpora/corpora :canonical))))))
(def a (ea/atom {:id :packnest2-a} {:data [] :n 5}))
(def fv (freeze/freeze-value (ea/atom-sio a) rows))
(swap! a (fn [cur] (assoc cur :data fv)))
(def root (ea/read-pinned a (fn [r] r)))
(def rep (chunk/to-rep root))
(println :rep {:root-class (class rep) :data-rep-class (class (get rep :data))
               :data-rep-head (try (pr-str (take 2 (seq (get rep :data))))
                                   (catch Throwable t (str "no-seq " (.getMessage t))))})
(def packed (chunk/pack root))
(println :packed {:equal (try (= packed {:data rows :n 5})
                              (catch Throwable t (str "THREW " (.getMessage t))))
                  :data-class (try (class (:data packed)) (catch Throwable t (str "THREW " (.getMessage t))))})
(println :DONE)
(System/exit 0)
