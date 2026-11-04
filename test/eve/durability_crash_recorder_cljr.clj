(ns eve.durability-crash-recorder-cljr
  "Crash-suite recording run, ClojureCLR seat — the JVM recorder's
  twin (eve.durability-crash-recorder) with this host's file copies.
  Drives a scripted commit sequence on a fresh domain, snapshotting
  the whole domain after each commit, and prints a manifest:
    commit <i> journal-len <n>
  Exits WITHOUT closing the domain (close would checkpoint).
  Usage: record <variant J1|J10> <size counter|kb64> <workdir> <n>"
  (:require [eve.atom :as atom])
  (:import [System.IO Directory File Path]))

(defn- opts [variant]
  (case variant
    "J1" {:journal? true :journal-batch 1}
    "J10" {:journal? true :journal-batch 10}
    {}))

(defn- copy-domain! [parent dst]
  ;; the domain is a file PREFIX (<parent>/domain.root, .slab*, ...),
  ;; not a directory: copy every file whose name starts with "domain".
  (Directory/CreateDirectory dst)
  (doseq [p (Directory/GetFiles parent)
          :when (.StartsWith (Path/GetFileName p) "domain")]
    (File/Copy p (Path/Combine dst (Path/GetFileName p)) true)))

(defn -main [variant size workdir n-str]
  (let [n (parse-long n-str)
        dir (str workdir "/domain")
        snap (fn [i] (copy-domain! workdir (str workdir "/snap-" i)))
        dom (atom/persistent-atom-domain dir (opts variant))
        big (fn [ch] (mapv (fn [_] (apply str (repeat 30 ch))) (range 2200)))
        init (case size "counter" 0 (big \z))
        a (atom/persistent-atom {:id :eve/main :persistent dir} init)]
    (atom/sync! a)
    (snap 0)
    (println "commit" 0 "journal-len"
             (let [j (str dir ".journal")]
               (if (File/Exists j) (.Length (System.IO.FileInfo. j)) 0)))
    (dotimes [i n]
      (let [k (inc i)]
        (case size
          "counter" (swap! a inc)
          (swap! a (fn [_] (big (char (+ 97 (mod k 26)))))))
        (snap k)
        (println "commit" k "journal-len"
                 (let [j (str dir ".journal")]
                   (if (File/Exists j) (.Length (System.IO.FileInfo. j)) 0)))
        (flush)))
    (System.Environment/Exit 0)))
