(ns alloc-growth-race.audit
  "Post-storm audit for alloc-growth-race.repro's multi-process shape
   (fix-for-release E5). Run once every storm process has exited: it reopens
   the shared domain and checks each per-thread atom holds exactly what P
   processes x swaps x burst committed — every {:p k :i i :j j} element
   present exactly P times with its :s string intact. A lost update, a torn
   or duplicated element, or an unreadable tree fails the audit.

   Usage (JVM): clojure -M:test -m alloc-growth-race.audit <dir> <procs> <threads> <swaps> <burst>
   Prints one AUDIT-ATOM line per atom and AUDIT-PASS / AUDIT-FAIL; exit 1 on fail."
  (:require [eve.atom :as atom]))

(defn audit-atom [domain k procs swaps burst]
  (let [a     (atom/lookup-or-create-mmap-atom! domain (str "repro/vec-" k) nil)
        v     @a
        n     (count v)
        want  (* procs swaps burst)
        freqs (frequencies (map (fn [e] [(:p e) (:i e) (:j e)]) (seq v)))
        bad-s (count (remove (fn [e] (= (:s e) (str "value-" (:p e) "-" (:i e) "-" (:j e)))) (seq v)))
        wrong (count (remove (fn [[[p i j] c]] (and (= p k) (< -1 i swaps) (< -1 j burst) (= c procs))) freqs))
        missing (- (* swaps burst) (count freqs))
        ok?   (and (= n want) (zero? bad-s) (zero? wrong) (zero? missing))]
    (println "AUDIT-ATOM" k :count n :want want :distinct (count freqs)
             :missing missing :wrong-multiplicity wrong :bad-s bad-s (if ok? :ok :FAIL))
    ok?))

(defn -main [& [dir procs threads swaps burst]]
  (let [procs   (parse-long procs)
        threads (parse-long threads)
        swaps   (parse-long swaps)
        burst   (parse-long burst)
        domain  (atom/persistent-atom-domain
                  dir
                  :capacities {0 8192 1 16384 2 32768 3 65536 4 131072 5 262144}
                  :journal? false)
        oks     (doall (map #(audit-atom domain % procs swaps burst) (range threads)))]
    (if (every? true? oks)
      (do (println "AUDIT-PASS" :atoms threads :elements-each (* procs swaps burst)) (System/exit 0))
      (do (println "AUDIT-FAIL") (System/exit 1)))))
