(ns contract.contend
  "Shared-atom contention driver for the external-library contract test.

   Modes (argv after the mode word):
     init     <dir>                      create the disk domain + the atom
                                         holding an empty BTSet; print seal
     worker   <dir> <w>                  join the domain; swap! this worker's
                                         shared batch + private batch in;
                                         print swaps done and local count
     readback <dir>                      join fresh; deref; print count, seq
                                         checksum, first/last, seal, and
                                         equality against the locally built
                                         expected union

   Workload (identical for every host): shared range [0,100) conj'd by every
   worker (overlap, idempotent), private range [10000 + w*1000, +300) per
   worker w in {0,1,2}. Expected final value: the union — 1000 elements,
   seq = [0..100) ++ [10000..10300) ++ [11000..11300) ++ [12000..12300)."
  (:refer-clojure :exclude [swap!])
  (:require
   [eve.host :refer [swap!]]
   [eve.atom :as atom]
   [eve.core :as e]
   [me.tonsky.persistent-sorted-set :as pss]))

(defmacro attempt
  "Run body; on any throw, answer the string ':throws <err>' (reader-
   conditional catch class: basilisp rejects (catch Throwable ..))."
  [& body]
  `(try ~@body
     (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t#
       (str :throws " " t#))))

(def ^:private atom-name "contract/pss")
(def ^:private workers 3)
(def ^:private shared-range (range 0 100))
(def ^:private priv-size 300)

(defn- priv-range [w]
  (range (+ 10000 (* w 1000)) (+ 10000 (* w 1000) priv-size)))

(defn- expected []
  (apply pss/sorted-set
         (concat shared-range (mapcat priv-range (range workers)))))

(defn- batches [xs]
  (partition 10 xs))

(defn- conj-batch [s batch]
  (reduce (fn [s x] (e/conj s x)) s batch))

(defn- open-atom [dir]
  (let [domain (atom/persistent-atom-domain dir)
        a (atom/lookup-or-create-mmap-atom! domain atom-name nil)]
    ;; the structure is constructed inside a swap so it allocates in the
    ;; atom's own domain (Eve's domain-construction contract)
    (when (nil? @a)
      (swap! a (fn [_] (pss/sorted-set))))
    a))

(defn -main [& [mode dir w]]
  (try
    (case mode
      "init"
      (let [a (open-atom dir)]
        (println (pr-str {:mode :init :count (e/count @a) :seal (atom/seal a)})))

      "worker"
      (let [a (open-atom dir)
            w (parse-long w)
            bs (concat (batches shared-range) (batches (priv-range w)))]
        (doseq [b bs]
          (swap! a (fn [s] (conj-batch s b))))
        (println (pr-str {:mode :worker :w w :swaps (count bs) :count (attempt (e/count @a))})))

      "readback"
      (let [a (open-atom dir)
            v @a
            exp (expected)]
        (println (pr-str {:mode :readback
                          :count (attempt (e/count v))
                          :seq-sum (reduce + (e/seq v))
                          :first (first (e/seq v))
                          :last (last (e/seq v))
                          :seal (atom/seal a)
                          :equiv-expected (= v exp)
                          :seq-eq (= (vec (e/seq v)) (vec (e/seq exp)))
                          :hash (attempt (hash v))}))))
    (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) t
      (println (pr-str {:mode (keyword (or mode "none")) :fatal (str t)})))))
