;; examples/disk_domain.cljc — one process's part in a disk domain shared by several processes (of any hosts).
;; Loaded by examples/disk_domain_processes.clj, which starts the processes and checks their sum; by hand:
;;
;;   bb -cp "$(bb classpath bb)" -e '(load-file "examples/disk_domain.cljc") (disk-domain/run! "/tmp/eve-demo/db" 200)'
;;   clojure -M -e '(load-file "examples/disk_domain.cljc") (disk-domain/run! "/tmp/eve-demo/db" 200)'
;;
;; A disk domain is a set of files at a base path (<base>.root, .rmap, .slab0-6, .slab0-5.bm); the first process to
;; open it creates it (one atomic create decides), the rest join. A named atom in it is a slot every process shares;
;; swap! is a CAS on the slot's root pointer, retried on conflict, so concurrent writers never lose an update.
(ns disk-domain
  (:require [eve.alpha :as e]
            [eve.atom :as ea]))

(defn run!
  "Open (or join) the domain at base, swap :n up by one, n times, and print what this process last saw."
  [base n]
  (let [counter (e/atom {:id :example/counter :persistent base} {:n 0})]
    (dotimes [_ n] (swap! counter update :n inc))
    (println "process done, its last read:" (pr-str @counter))
    (ea/close-atom-domain! (ea/persistent-atom-domain base))))

(defn total
  "The counter's value in the domain at base."
  [base]
  (let [counter (e/atom {:id :example/counter :persistent base} {:n 0})
        v @counter]
    (ea/close-atom-domain! (ea/persistent-atom-domain base))
    v))
