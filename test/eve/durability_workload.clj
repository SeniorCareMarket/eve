(ns eve.durability-workload
  "Phase-3 workload writer (JVM + jolt share this file). One process:
  opens the shared domain, loops a seeded random mix of ops across the
  counter + record atoms until the deadline, logging every op (with
  the post-op root epoch) to its op-log file. Flush events from
  *flush-log* / *flush-timing* are drained into the op log as they
  accumulate. Variants: A (no journal, :durable? false) and J
  (:journal? true, huge count batch + :journal-window-ms 5000 — the
  5 s window is the flush trigger; jolt joins J cells as a non-journal
  participant because its host cannot resolve FileChannel.lock)."
  (:require [eve.atom :as atom]
            [eve.mem :as mem]
            [clojure.java.io :as io]))

(defn- rec-value
  "A record of the class shape: filler string sized by the class."
  [v fill]
  {:v v :data (apply str (repeat fill "x"))})

(defn make-records
  "n records {\"r<i>\" {:v 0 :data <fill chars>}} — real-world-shaped:
  many small records, never one giant string (the >1-5 KB single
  string round-trip defect on this build is pre-existing)."
  [n fill]
  (into {} (map (fn [i] [(str "r" i) (rec-value 0 fill)])) (range n)))

(def classes
  ;; class -> [atom-id record-count fill-chars approx-bytes]
  {:s1k   [:rec-1k   12    60]
   :s100k [:rec-100k 1200  60]
   :s1m   [:rec-1m   12000 60]
   :s100m [:rec-100m 1200000 60]})

(defn setup-domain!
  "Create the domain + atoms (B-mode durable, journal off): one swap
  per class carrying the full records map (a chunked build would
  re-materialize the growing value on every chunk)."
  [dir]
  (let [dom (atom/persistent-atom-domain dir {})
        c   (atom/persistent-atom {:id :counter :persistent dir} 0)]
    (doseq [[_ [aid n fill]] classes]
      (let [a (atom/persistent-atom {:id aid :persistent dir} {:records {}})]
        (reset! a {:records (make-records n fill)})
        (println :setup-class aid :records n :value-records (count (:records @a)))))
    (atom/close-atom-domain! dom)
    (println :setup-done dir)))

(defn- root-epoch [counter-atom]
  (let [ds (atom/-domain-state counter-atom)]
    (mem/-load-i32 (:root-r ds) 8)))

(defn -main
  "Config: an EDN map in wl-config.edn in the process CWD (uniform
  across runtimes — no env/argv interop): {:dir :variant :wid :seed
  :dur-ms :log}."
  [& _]
  (let [cfg    (read-string (slurp (or (first *command-line-args*) "wl-config.edn")))
        dir    (:dir cfg)
        variant (:variant cfg)
        wid    (:wid cfg)
        logfile (:log cfg)
        dur    (:dur-ms cfg)
        seed   (:seed cfg)
        ;; portable LCG (no java.util.Random — CLR/basilisp lack it)
        rng-state (clojure.core/atom (mod seed 2147483648))
        rng    (fn [] (swap! rng-state
                             (fn [x] (mod (+ (* x 1103515245) 12345) 2147483648)))
                 (/ @rng-state 2147483648.0))
        nxtint (fn [n] (long (* (rng) n)))
        jopts  (case variant
                 "A"  {:durable? false}
                 "J"  {:journal? true :journal-batch 1000000
                       :journal-window-ms 5000}
                 ;; Jn: jolt in a J cell — non-journal participant
                 "Jn" {:durable? false})
        dom    (atom/persistent-atom-domain dir jopts)
        atoms  {:counter (atom/persistent-atom {:id :counter :persistent dir} 0)}
        atoms  (into atoms
                     (map (fn [[_ [aid _ _]]] [aid (atom/persistent-atom {:id aid :persistent dir} {:records {}})]))
                     classes)
        w      (io/writer logfile :append true)
        flog   (clojure.core/atom [])
        ftim   (clojure.core/atom [])
        emit!  (fn [m] (.write w (str (pr-str m) "\n")) (.flush w))
        deadline (+ (System/currentTimeMillis) dur)]
    (emit! {:wid wid :event :start :variant variant :seed seed})
    (binding [atom/*flush-log* flog atom/*flush-timing* ftim]
      (loop [seqn 0 seen-flush 0]
        (when (< (System/currentTimeMillis) deadline)
          (let [r (rng)]
            (cond
              (< r 0.40)
              (do (swap! (:counter atoms) inc)
                  (emit! {:seq seqn :op :inc :atom :counter
                          :epoch (root-epoch (:counter atoms))}))

              (< r 0.75)
              (let [[aid n fill] (get-in classes [:s1k])
                    rid (str "r" (nxtint n))
                    v   (inc seqn)]
                (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                        :epoch (root-epoch (:counter atoms))}))

              (< r 0.90)
              (let [[aid n fill] (get-in classes [:s100k])
                    rid (str "r" (nxtint n))
                    v   (inc seqn)]
                (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                        :epoch (root-epoch (:counter atoms))}))

              (< r 0.98)
              (let [[aid n fill] (get-in classes [:s1m])
                    rid (str "r" (nxtint n))
                    v   (inc seqn)]
                (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                        :epoch (root-epoch (:counter atoms))}))

              :else
              ;; the 100 MB class: JVM-family writers only (the runner
              ;; does not assign it to Node/nbb writers)
              (if (contains? #{:jvm :jolt} (keyword wid))
                (let [[aid n fill] (get-in classes [:s100m])
                      rid (str "r" (nxtint n))
                      v   (inc seqn)]
                  (swap! (get atoms aid) assoc-in [:records rid] (rec-value v fill))
                  (emit! {:seq seqn :op :put :atom aid :rec rid :v v
                          :epoch (root-epoch (:counter atoms))}))
                (do (swap! (:counter atoms) inc)
                    (emit! {:seq seqn :op :inc :atom :counter
                            :epoch (root-epoch (:counter atoms))})))))
          ;; drain flush instrumentation
          (let [fl @flog]
            (when (> (count fl) seen-flush)
              (doseq [e (subvec fl seen-flush)]
                (emit! {:event :flush :at (:at e) :ms (:ms e)}))))
          (recur (inc seqn) (count @flog)))))
    (emit! {:wid wid :event :done})
    (.close w)
    (System/exit 0)))
