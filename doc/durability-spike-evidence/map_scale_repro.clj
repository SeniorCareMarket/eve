(ns map-scale-repro
  "Side repro (phase 3 setup observation): a host map of N distinct
  string keys \"r<i>\" -> {:v 0 :data <60 x>} reset! into a disk atom
  and deref'd back loses keys at N=1.2M (1,199,977 of 1,200,000).
  Finds the missing keys at several N and whether they collide under
  eve's key hash."
  (:require [eve.atom :as atom]
            [eve.hamt-util :as hu]))

(defn -main [& [dir n-str]]
  (let [n    (Long/parseLong n-str)
        dom  (atom/persistent-atom-domain dir {})
        a    (atom/persistent-atom {:id :m :persistent dir} {})
        m    (into {} (map (fn [i] [(str "r" i) {:v 0 :data (apply str (repeat 60 "x"))}]))
                   (range n))]
    (reset! a m)
    (let [back    @a
          missing (into [] (remove #(contains? back %)) (keys m))]
      (println :n n :host (count m) :back (count back)
               :missing (count missing))
      (doseq [k (take 10 missing)]
        (println :missing-key k :hash (hu/portable-hash-bytes (.getBytes k "UTF-8"))))
      (when (seq missing)
        (let [present-hashes (into #{} (map (fn [k] (hu/portable-hash-bytes (.getBytes k "UTF-8")))) (keys back))]
          (println :missing-with-present-hash
                   (count (filter #(contains? present-hashes (hu/portable-hash-bytes (.getBytes % "UTF-8"))) missing)))))
      (atom/close-atom-domain! dom)
      (System/exit 0))))
