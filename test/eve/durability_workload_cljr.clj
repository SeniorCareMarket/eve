(ns eve.durability-workload-cljr
  "Phase-3 workload writer for ClojureCLR: same op mix and op-log
  format as eve.durability-workload, with CLR host idioms for the
  clock and file appends (the shared .clj writer's clojure.java.io /
  System/currentTimeMillis spellings do not exist on this host)."
  (:require [eve.atom :as atom]
            [eve.mem :as mem]))

(defn- now-ms [] (.ToUnixTimeMilliseconds System.DateTimeOffset/Now))

(defn- rec-value [v fill]
  {:v v :data (apply str (repeat fill "x"))})

(def classes
  {:s1k   [:rec-1k   12    60]
   :s100k [:rec-100k 1200  60]
   :s1m   [:rec-1m   12000 60]
   :s100m [:rec-100m 1200000 60]})

(defn- root-epoch [counter-atom]
  (let [ds (atom/-domain-state counter-atom)]
    (mem/-load-i32 (:root-r ds) 8)))

(defn -main [& _]
  (let [cfg    (read-string (slurp (or (first *command-line-args*) "wl-config.edn")))
        dir    (:dir cfg)
        variant (:variant cfg)
        wid    (:wid cfg)
        logfile (:log cfg)
        dur    (:dur-ms cfg)
        seed   (:seed cfg)
        rng-state (atom (mod seed 2147483648))
        rng    (fn [] (swap! rng-state
                             (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)))
                 (/ @rng-state 2147483648.0))
        nxtint (fn [n] (long (* (rng) n)))
        jopts  (case variant
                 "A" {:durable? false}
                 "J" {:journal? true :journal-batch 1000000
                      :journal-window-ms 5000}
                 {:durable? false})
        dom    (atom/persistent-atom-domain dir jopts)
        atoms  (into {:counter (atom/persistent-atom {:id :counter :persistent dir} 0)}
                     (map (fn [[_ [aid _ _]]] [aid (atom/persistent-atom {:id aid :persistent dir} {:records {}})]))
                     classes)
        emit!  (fn [m] (System.IO.File/AppendAllText logfile (str (pr-str m) "\n")))
        flog   (atom [])
        ftim   (atom [])
        deadline (+ (now-ms) dur)]
    (emit! {:wid wid :event :start :variant variant :seed seed})
    (binding [atom/*flush-log* flog atom/*flush-timing* ftim]
      (loop [seqn 0 seen-flush 0]
        (when (< (now-ms) deadline)
          (let [r (rng)]
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
              (let [[aid n fill] (:s100m classes)
                    rid (str "r" (nxtint n))
                    v   (inc seqn)]
                (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                        :epoch (root-epoch (:counter atoms))}))))
          (let [fl @flog]
            (when (> (count fl) seen-flush)
              (doseq [e (subvec fl seen-flush)]
                (emit! {:event :flush :at (:at e) :ms (:ms e)}))))
          (recur (inc seqn) (count @flog)))))
    (emit! {:wid wid :event :done})))
