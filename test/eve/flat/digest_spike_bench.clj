(ns eve.flat.digest-spike-bench
  "SPIKE (John, Nov 12 2026): wire eve.digest into the stream send.
   Latched root digests ship in a trailer; a root whose digest was
   already sent in the same stream is aliased to its first index and
   its whole subtree is pruned from the walk; the receiver stamps
   the placed roots' digest caches from the trailer.

   Corpora:
     repeats  — a vector of 200 independently built copies of one
                50-entry map (distinct blocks, equal content, row
                digests latched): the dedup case.
     records  — records-10k unlatched: the lookup tax with no gain.
     recordsL — records-10k with every row digest latched (all
                distinct): the trailer tax with no dedup.
   Usage: clojure.main -m eve.flat.digest-spike-bench"
  (:require [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.digest :as digest]
            [eve.flat.corpora :as corpora]
            [eve.flat.stream :as stream]))

(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(defn- bench-min [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i runs)
      best
      (let [t0 (System/nanoTime)
            _ (f)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms))))))

(defn- fresh-atom [name]
  (ea/lookup-or-create-mmap-atom!
   (ea/atom-domain nil :capacities capacities) name nil))

(defn- fresh-domain-sio []
  (let [a (fresh-atom (str "ds-seed-" (rand-int 1000000000)))]
    (swap! a (fn [_] {:seed 1}))
    (ea/read-pinned a d/-eve-sio)))

(defn- row-map []
  (into {}
        (map (fn [i] [(keyword (str "k" i)) (* i 7)]))
        (range 50)))

(defn- latch-rows!
  "Latch the digest of every element root of the pinned slab root."
  [sio root]
  (let [n (count root)]
    (loop [i 0 latched 0]
      (if (== i n)
        latched
        (let [row (nth root i)]
          (recur (inc i)
                 (if (and (satisfies? d/IEveRoot row)
                          (digest/root-digest sio (d/-root-header-off row) row))
                   (inc latched)
                   latched)))))))

(defn- send-ab [holder opts-a opts-b]
  (ea/read-pinned
   holder
   (fn [lv]
     (let [sio (d/-eve-sio lv)
           root (d/-root-header-off lv)
           fa (fn [] (stream/send-stream sio root opts-a))
           fb (fn [] (stream/send-stream sio root opts-b))
           ra (fa)
           rb (fb)
           ta (bench-min 1 3 fa)
           tb (bench-min 1 3 fb)]
       {:off ra :on rb :off-ms ta :on-ms tb}))))

(defn- receive-check [wire plain mode]
  (let [sio (fresh-domain-sio)
        t0 (System/nanoTime)
        r (stream/receive-stream! sio wire {:check? true :digest-trust mode})
        ms (/ (- (System/nanoTime) t0) 1e6)
        placed (stream/receive-value sio wire {:digest-trust mode})]
    {:ms ms :stamped (:digests-stamped r)
     :equal (= placed plain)}))

(defn -main [& _]
  ;; ---- repeats corpus -------------------------------------------------
  (let [plain (mapv (fn [_] (row-map)) (range 200))
        holder (fresh-atom "ds-repeats")]
    (swap! holder (fn [_] plain))
    (let [latched (ea/read-pinned holder
                                  (fn [lv] (latch-rows! (d/-eve-sio lv) lv)))
          {:keys [off on off-ms on-ms]} (send-ab holder {} {:digests true})
          roff (receive-check (:bytes off) plain :trusted)
          ron-t (receive-check (:bytes on) plain :trusted)
          ron-v (receive-check (:bytes on) plain :verify)]
      (println (pr-str {:corpus "repeats-200x50"
                        :rows-latched latched
                        :off {:blocks (:blocks off) :bytes (alength (:bytes off))
                              :digests (:digests off) :deduped (:deduped off) :ms off-ms}
                        :on {:blocks (:blocks on) :bytes (alength (:bytes on))
                             :digests (:digests on) :deduped (:deduped on) :ms on-ms}
                        :receive-off roff
                        :receive-on-trusted ron-t
                        :receive-on-verify ron-v}))
      (flush)))
  ;; ---- records tax corpora ---------------------------------------------
  (doseq [[label latch?] [["records-plain" false] ["records-latched" true]]]
    (let [plain (:records-10k (into {} (corpora/corpora :canonical)))
          holder (fresh-atom (str "ds-" label))]
      (swap! holder (fn [_] plain))
      (let [latched (when latch?
                      (ea/read-pinned holder
                                      (fn [lv] (latch-rows! (d/-eve-sio lv) lv))))
            {:keys [off on off-ms on-ms]} (send-ab holder {} {:digests true})
            ron-t (receive-check (:bytes on) plain :trusted)
            ron-v (when latch? (receive-check (:bytes on) plain :verify))]
        (println (pr-str {:corpus label
                          :rows-latched (or latched 0)
                          :off {:blocks (:blocks off) :bytes (alength (:bytes off)) :ms off-ms}
                          :on {:blocks (:blocks on) :bytes (alength (:bytes on))
                               :digests (:digests on) :deduped (:deduped on) :ms on-ms}
                          :receive-on-trusted ron-t
                          :receive-on-verify ron-v}))
        (flush))))
  (println "DIGEST SPIKE DONE")
  (System/exit 0))
