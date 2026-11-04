(ns eve.jcost-probe
  "Journal cost exploration probes (2026-10-28). Small-scale, REPL-grade
   measurements of the retire zero-queue's flush discipline. Run:
     clojure -M:t -Sdeps '{:aliases {:t {:extra-paths [\"bench\"]}}}' \\
       -e \"(require 'eve.jcost-probe) (eve.jcost-probe/probe1)\"
   Scratch domains live under ~/workspace/jcost-runs/."
  (:require [eve.atom :as atom]
            [eve.mem :as mem]
            [eve.deftype-proto.data :as d]
            [clojure.java.io :as io]))

(def ^:private runs-dir "/home/hatch/workspace/jcost-runs")

(defn- delete-recursively! [^java.io.File f]
  (when (.exists f)
    (doseq [c (reverse (file-seq f))] (.delete ^java.io.File c))))

(defn- cur-epoch [ds]
  (mem/-load-i32 (:root-r ds) d/ROOT_EPOCH_OFFSET))

(defn- time-us [f n]
  (let [t0 (System/nanoTime)]
    (dotimes [_ n] (f))
    (/ (double (- (System/nanoTime) t0)) (* n 1000.0))))

(defn probe1
  "P1 (micro): pure scan cost of tally/flush! vs standing queue length.
   A J100000 domain (no flush ever fires; floor starts nil so the queue
   self-cleans), then the journal floor atom is set BY HAND to the
   current epoch: every entry parked from then on is ineligible
   (stamp >= floor), so flush-zero-queue! performs the pure
   drain-scan-and-restore with zero frees. Queue length is grown in
   steps by swapping; at each step flush-zero-queue! is timed.
   Finally the floor is dropped to 0 and one flush frees everything
   (the free path's per-entry price), and the safe-epoch scan is
   timed on its own."
  []
  (let [dir (str runs-dir "/probe1")
        _ (delete-recursively! (io/file dir))
        dom (atom/persistent-atom-domain dir {:journal? true :journal-batch 100000})
        ds (.-domain-state dom)
        a (atom/persistent-atom {:id :eve/main :persistent dir} 0)
        rq (:retire-q ds)
        floor (:floor (:journal ds))]
    ;; phase 0: no floor -> self-cleaning sanity
    (dotimes [_ 2000] (swap! a inc))
    (println (pr-str {:probe :p1 :phase :no-floor :swaps 2000
                      :epoch (cur-epoch ds) :queue (count @rq)}))
    ;; pin the floor here; everything parked later stays
    (reset! floor (cur-epoch ds))
    (println (pr-str {:probe :p1 :phase :floor-set :floor @floor}))
    ;; phase 1: grow the standing queue in steps; time the pure scan
    (doseq [target [250 500 1000 2000 4000]]
      (while (< (count @rq) target) (swap! a inc))
      (let [us (time-us #(@#'eve.atom/flush-zero-queue! ds) 10)]
        (println (pr-str {:probe :p1 :phase :pure-scan
                          :queue (count @rq) :flush-us us
                          :us-per-entry (/ us (max 1 (count @rq)))}))))
    ;; the safe-epoch scan alone (256 worker slots)
    (let [us (time-us #(@#'eve.atom/mmap-min-safe-epoch (:root-r ds)) 1000)]
      (println (pr-str {:probe :p1 :phase :safe-scan :us us})))
    ;; phase 2: floor past every stamp -> one flush frees (nearly)
    ;; everything. (Entries free when stamp < safe; the floor is a
    ;; lower bound on safe, so freeing all means floor ABOVE the
    ;; stamps, not 0.)
    (reset! floor Long/MAX_VALUE)
    (let [q0 (count @rq)
          t0 (System/nanoTime)
          freed (@#'eve.atom/flush-zero-queue! ds)
          ms (/ (double (- (System/nanoTime) t0)) 1e6)]
      (println (pr-str {:probe :p1 :phase :free-path :queue-before q0
                        :freed freed :queue-after (count @rq)
                        :ms ms :us-per-entry (/ (* ms 1000.0) (max 1 q0))})))
    ;; empty-queue flush overhead (safe-fn + drain of nothing)
    (let [us (time-us #(@#'eve.atom/flush-zero-queue! ds) 100)]
      (println (pr-str {:probe :p1 :phase :empty-flush :queue (count @rq) :flush-us us})))
    (atom/close-atom-domain! dom)
    (println :probe1-done)))

(defn probe2
  "P2 (trajectory): the bench's own shape — J1000 counter — sampled in
   windows: per-window us/op next to the standing queue length and the
   floor, on the checkpoint-floor code as it stands. Shows cost (a)'s
   shape (per-commit price as a function of standing queue) in the
   real commit path."
  [windows window-size]
  (let [dir (str runs-dir "/probe2")
        _ (delete-recursively! (io/file dir))
        dom (atom/persistent-atom-domain dir {:journal? true :journal-batch 1000})
        ds (.-domain-state dom)
        a (atom/persistent-atom {:id :eve/main :persistent dir} 0)
        rq (:retire-q ds)
        floor (:floor (:journal ds))]
    (dotimes [w windows]
      (let [t0 (System/nanoTime)]
        (dotimes [_ window-size] (swap! a inc))
        (let [ms (/ (double (- (System/nanoTime) t0)) 1e6)]
          (println (pr-str {:probe :p2 :window w
                            :us-per-op (/ (* ms 1000.0) window-size)
                            :queue (count @rq) :floor @floor
                            :epoch (cur-epoch ds)})))))
    (atom/close-atom-domain! dom)
    (println :probe2-done)))

(defn probe3
  "P3 (boundedness): probe2's shape run against the FLOOR-ADVANCE tree
   (invoke with this bench dir on the classpath from that tree's eve/).
   Samples the standing queue across 8 windows of 1000: bounded
   (levels off) or creeping (still +1000/window)?"
  [windows window-size]
  (let [dir (str runs-dir "/probe3-fa")
        _ (delete-recursively! (io/file dir))
        dom (atom/persistent-atom-domain dir {:journal? true :journal-batch 1000})
        ds (.-domain-state dom)
        a (atom/persistent-atom {:id :eve/main :persistent dir} 0)
        rq (:retire-q ds)
        floor (:floor (:journal ds))
        last-ep (:last-epoch (:journal ds))]
    (dotimes [w windows]
      (let [t0 (System/nanoTime)]
        (dotimes [_ window-size] (swap! a inc))
        (let [ms (/ (double (- (System/nanoTime) t0)) 1e6)]
          (println (pr-str {:probe :p3 :window w
                            :us-per-op (/ (* ms 1000.0) window-size)
                            :queue (count @rq) :floor @floor
                            :last-epoch (some-> last-ep deref)
                            :epoch (cur-epoch ds)})))))
    (atom/close-atom-domain! dom)
    (println :probe3-done)))
