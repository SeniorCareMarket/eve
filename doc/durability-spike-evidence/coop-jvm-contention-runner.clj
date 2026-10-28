;; Standalone driver for the coop-atom suite's shared scenario source
;; (test/coop/scenario.cljc) — the suite's own orchestrator namespace
;; (test/coop-atom/clj/coop_atom_test.clj) does not compile against the
;; vendored eve at main (pre-existing: "Syntax error compiling at
;; (coop_atom_test.clj:166:7). No such var: atom/$", identical on the
;; pristine base tree), so this runner drives the same scenario ops
;; directly, in the shape of the orchestrator's contention-jvm-only
;; control: writers x ops on ONE shared disk atom, then snapshot +
;; violations (coop.scenario/violations takes [snapshot total
;; per-writer]).
;;
;; Modes:
;;   full    <root> [ops]              init + 4 JVM threads + verify
;;   init    <root>                    write the initial content
;;   contend <root> <prefix> <threads> <ops>
;;   verify  <root> <total> <w=n,...>  snapshot + violations for the
;;                                     named writers' op counts
;;
;; With init/contend/verify, Node workers (the built mmap-worker,
;; "coop-contend <base> <writer> <ops>") contend alongside the JVM
;; threads on the same atom — the mixed-host shape.
(require '[eve.atom :as atom]
         '[coop.scenario :as sc]
         '[clojure.string :as str])

(def mode (first *command-line-args*))
(def root (second *command-line-args*))

(defn open-domain! []
  (atom/persistent-atom-domain root))

(defn init! []
  (let [dom (atom/persistent-atom-domain root {:create? true})
        ;; initial-contention is a VALUE (the start map), not a fn
        a (atom/persistent-atom {:id :eve/main :persistent root}
                                sc/initial-contention)]
    (atom/close-atom-domain! dom)
    (println :init-done)))

(defn contend! [prefix n-threads ops]
  (let [dom (open-domain!)
        a (atom/persistent-atom {:id :eve/main :persistent root} nil)
        writers (mapv #(str prefix %) (range n-threads))
        futs (mapv (fn [w]
                     (future
                       (dotimes [i ops] (swap! a sc/contend-op w i))
                       w))
                   writers)
        done (mapv deref futs)]
    (atom/close-atom-domain! dom)
    (println :contended {:writers done :ops-each ops})))

(defn verify! [total per-writer]
  (let [dom (open-domain!)
        a (atom/persistent-atom {:id :eve/main :persistent root} nil)
        snap (sc/snapshot @a)
        v (sc/violations snap total per-writer)]
    (atom/close-atom-domain! dom)
    (println :snapshot snap)
    (println :per-writer per-writer)
    (println :violations v)
    (println (if (empty? v) :COOP-VERIFY-OK :COOP-VERIFY-VIOLATIONS))))

(defn parse-per-writer [s]
  (into {} (map (fn [pair]
                  (let [[w n] (str/split pair #"=")]
                    [w (parse-long n)]))
                (str/split s #","))))

(case mode
  "init" (init!)
  "contend" (let [[prefix n-threads ops] (drop 2 *command-line-args*)]
              (contend! prefix (parse-long n-threads) (parse-long ops)))
  "verify" (let [[total pw] (drop 2 *command-line-args*)]
             (verify! (parse-long total) (parse-per-writer pw)))
  ;; full: the JVM-only contention check
  (let [ops (or (some-> (nth *command-line-args* 2 nil) parse-long) 40)]
    (init!)
    (contend! "jvm" 4 ops)
    (verify! (* 4 ops) {"jvm0" ops "jvm1" ops "jvm2" ops "jvm3" ops})))

(shutdown-agents)
(System/exit 0)
