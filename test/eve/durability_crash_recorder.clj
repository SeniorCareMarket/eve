(ns eve.durability-crash-recorder
  "Phase 2 (SQLite-pattern crash suite) — recording run. Drives a
  scripted commit sequence on a fresh domain, snapshotting the whole
  domain directory after each commit, and prints a manifest:
    commit <i> journal-len <n>
  Snap-0 is the checkpoint state (after sync!), before user swaps.
  Exits WITHOUT closing the domain (close would checkpoint).
  Usage: record <variant B|J1|J10> <size counter|kb64> <workdir> <n>"
  (:require [eve.atom :as atom]
            [clojure.java.io :as io])
  (:import [java.nio.file Files Path CopyOption StandardCopyOption]
           [java.io File]))

(defn- opts [variant]
  (case variant
    "J1" {:journal? true :journal-batch 1}
    "J10" {:journal? true :journal-batch 10}
    {}))

(defn- copy-domain! [^File parent ^File dst]
  ;; the domain is a file PREFIX (<parent>/domain.root, .slab*, ...),
  ;; not a directory: copy every file whose name starts with "domain".
  (.mkdirs dst)
  (doseq [^File f (.listFiles parent)
          :when (and (.isFile f) (.startsWith (.getName f) "domain"))]
    (Files/copy (.toPath f)
                (.toPath (io/file dst (.getName f)))
                (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))

(defn -main [variant size workdir n-str]
  (let [n (parse-long n-str)
        dir (str workdir "/domain")
        snap (fn [i] (copy-domain! (io/file workdir) (io/file (str workdir "/snap-" i))))
        dom (atom/persistent-atom-domain dir (opts variant))
        ;; the "kb64" shape is a 2200-element vector of 30-char
        ;; strings (~66 KB across many blocks): a single string
        ;; above ~1-5 KB does NOT round-trip through this eve
        ;; build's mmap atom at all (reads back empty — verified
        ;; identical on pristine main; a pre-existing limitation,
        ;; not the durability fix's doing), so multi-block coverage
        ;; uses a collection of small strings instead.
        big (fn [ch] (mapv (fn [_] (apply str (repeat 30 ch))) (range 2200)))
        init (case size "counter" 0 (big "z"))
        a (atom/persistent-atom {:id :eve/main :persistent dir} init)]
    (atom/sync! a)
    (snap 0)
    (println "commit" 0 "journal-len"
             (let [j (io/file (str dir ".journal"))] (if (.exists j) (.length j) 0)))
    (dotimes [i n]
      (let [k (inc i)]
        (case size
          "counter" (swap! a inc)
          (swap! a (fn [_] (big (char (+ 97 (mod k 26)))))))
        (snap k)
        (println "commit" k "journal-len"
                 (let [j (io/file (str dir ".journal"))] (if (.exists j) (.length j) 0)))
        (flush)))
    (System/exit 0)))
