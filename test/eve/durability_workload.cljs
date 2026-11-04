(ns eve.durability-workload
  "Phase-3 workload writer, CLJS twin of test/eve/durability_workload.clj
  — compiled by shadow (build :durability-workload) for Node, and
  interpreted from source by nbb (-m eve.durability-workload). Config
  via env: EVE_WL_DIR, EVE_WL_VARIANT (A|J), EVE_WL_WID, EVE_WL_SEED,
  EVE_WL_DUR_MS, EVE_WL_LOG. Node/nbb writers never take the 100 MB
  class (materializing a ~100 MB value into JS objects per op) —
  those draws become counter increments; named in the census."
  (:require [eve.atom :as atom]
            [eve.mem :as mem]))

(def fs (js/require "fs"))

(defn- rec-value [v fill]
  {:v v :data (apply str (repeat fill "x"))})

(def classes
  {:s1k   [:rec-1k   12    60]
   :s100k [:rec-100k 1200  60]
   :s1m   [:rec-1m   12000 60]
   :s100m [:rec-100m 1200000 60]})

(defn- lcg
  "Deterministic per-writer PRNG ( seeded LCG over doubles)."
  [seed]
  (let [s (atom (mod seed 2147483648))]
    (fn []
      (swap! s (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)))
      (/ @s 2147483648.0))))

(defn- root-epoch [counter-atom]
  (let [ds (atom/-domain-state counter-atom)]
    (mem/-load-i32 (:root-r ds) 8)))

(defn -main [& args]
  (let [cfgpath (or (first args) (aget (.-argv js/process) 2) "wl-config.json")
        cfg    (js/JSON.parse (.readFileSync fs cfgpath "utf8"))
        dir    (aget cfg "dir")
        variant (aget cfg "variant")
        wid    (aget cfg "wid")
        logfile (aget cfg "log")
        dur    (aget cfg "dur-ms")
        seed   (aget cfg "seed")
        rnd    (lcg seed)
        nxtint (fn [n] (js/Math.floor (* (rnd) n)))
        jopts  (case variant
                 "A" {:durable? false}
                 "J" {:journal? true :journal-batch 1000000
                      :journal-window-ms 5000}
                 {:durable? false})
        dom    (atom/persistent-atom-domain dir jopts)
        atoms  (into {:counter (atom/persistent-atom {:id :counter :persistent dir} 0)}
                     (map (fn [[_ [aid _ _]]] [aid (atom/persistent-atom {:id aid :persistent dir} {:records {}})]))
                     classes)
        fd     (.openSync fs logfile "a")
        emit!  (fn [m] (.writeSync fs fd (str (pr-str m) "\n")))
        flog   (atom [])
        ftim   (atom [])
        deadline (+ (js/Date.now) dur)]
    (emit! {:wid wid :event :start :variant variant :seed seed})
    (binding [atom/*flush-log* flog atom/*flush-timing* ftim]
      (loop [seqn 0 seen-flush 0]
        (when (< (js/Date.now) deadline)
          (let [r (rnd)]
            (cond
              (< r 0.40)
              (do (swap! (:counter atoms) inc)
                  (emit! {:seq seqn :op :inc :atom :counter
                          :epoch (root-epoch (:counter atoms))}))

              (< r 0.75)
              (let [[aid n fill] (:s1k classes)
                    rid (str "r" (nxtint n))
                    v   (inc seqn)]
                (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                        :epoch (root-epoch (:counter atoms))}))

              (< r 0.90)
              (let [[aid n fill] (:s100k classes)
                    rid (str "r" (nxtint n))
                    v   (inc seqn)]
                (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                        :epoch (root-epoch (:counter atoms))}))

              (< r 0.98)
              (let [[aid n fill] (:s1m classes)
                    rid (str "r" (nxtint n))
                    v   (inc seqn)]
                (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                        :epoch (root-epoch (:counter atoms))}))

              :else
              (do (swap! (:counter atoms) inc)
                  (emit! {:seq seqn :op :inc :atom :counter
                          :epoch (root-epoch (:counter atoms))}))))
          (let [fl @flog]
            (when (> (count fl) seen-flush)
              (doseq [e (subvec fl seen-flush)]
                (emit! {:event :flush :at (:at e) :ms (:ms e)}))))
          (recur (inc seqn) (count @flog)))))
    (emit! {:wid wid :event :done})
    (.closeSync fs fd)
    (js/process.exit 0)))
