;; Readings per rung (lane ET-3, the brief's §8): bb scripts/thread-rungs.clj <what> <host> [args]
;;
;;   swaps   <host> <n>        swap! inc on one atom, n times: the domain on disk (/tmp) and in the window (/dev/shm),
;;                             one process alone and two at once — µs a swap (the plan's §6.4, re-run on the product)
;;   net     <host> <n>        the same through a network rung: a JVM gateway over a window domain, its store a babashka
;;                             process, the swaps made by <host> over real HTTP
;;   drivers <host> <n> <ms>   a worker process of <host> serving pool :g with 4 threads; a coordinator of <host> runs n
;;                             naps of ms, collected by :in-order, :sweep, :waiters in turn — wall ms and meta-atom reads
;;
;; Run from eve/. Prints one EDN line per reading.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(load-file "scripts/thread-ladder-cmd.clj")

(defn steps [f]
  (keep (fn [l] (when (and (str/starts-with? l "{") (str/includes? l ":step "))
                  (edn/read-string {:default (fn [_ v] v)} l)))
        (str/split-lines (if (fs/exists? f) (slurp f) ""))))

(defn run-host! [host & args]
  (let [f (str (fs/create-temp-file {:prefix "eve-rungs-" :suffix ".log"}))]
    (apply p/shell {:out f :err (str f ".err") :continue true} (apply cmd host "eve.thread-rungs" args))
    (steps f)))

(defn start! [host m & args]
  (let [f (str (fs/create-temp-file {:prefix "eve-rungs-" :suffix ".log"}))]
    [(apply p/process {:out f :err (str f ".err")} (apply cmd host m args)) f]))

(defn wait-step [f s ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (first (filter (fn [m] (= s (:step m))) (steps f)))
                 (if (> (System/currentTimeMillis) deadline) (throw (ex-info (str "timed out: " s) {})) (do (Thread/sleep 50) (recur)))))))

(let [[what host & args] *command-line-args*]
  (case what
    "swaps"
    (let [n (first args)]
      (doseq [[placement root] [[:disk "/tmp"] [:window "/dev/shm"]]]
        (let [one (str (fs/create-temp-dir {:dir root :prefix "eve-rungs-"}) "/d")
              two (str (fs/create-temp-dir {:dir root :prefix "eve-rungs-"}) "/d")
              alone (first (run-host! host "swap" one n))
              fs (vec (repeatedly 2 (fn [] (start! host "eve.thread-rungs" "swap" two n))))
              both (mapv (fn [[pr f]] (deref pr 1800000 nil) (first (steps f))) fs)]
          (println (pr-str {:reading :swap :host host :placement placement :n n :alone-us (:us alone)
                            :two-us (mapv :us both) :finals (conj (mapv :final both) (:final alone))
                            :errors (vec (filter :error (concat [alone] both)))})))))
    "net"
    (let [n (first args)
          base (str (fs/create-temp-dir {:dir "/dev/shm" :prefix "eve-rungs-"}) "/d")
          secret (str (random-uuid))
          [ps fs] (start! "bb" "eve.thread-ladder-gateway" "store" secret)
          store-url (:url (wait-step fs :store 120000))
          [pg fg] (start! "jvm" "eve.thread-ladder-gateway" "serve" base store-url secret)
          ep (:endpoint (wait-step fg :gateway 300000))
          rs (mapv (fn [size] (first (run-host! host "netswap" ep n size))) ["small" "meta"])]
      (doseq [r rs]
        (println (pr-str {:reading :netswap :host host :size (:size r) :n n :us (:us r) :final (:final r) :error (:error r)})))
      (p/destroy-tree pg) (p/destroy-tree ps))
    "drivers"
    (let [[n ms] args
          base (str (fs/create-temp-dir {:dir "/dev/shm" :prefix "eve-rungs-"}) "/d")
          [pw fw] (start! host "eve.thread-rungs" "worker" base "4")
          _ (wait-step fw :worker-up 300000)
          rs (run-host! host "coord" base n ms "in-order" "sweep" "waiters")]
      (deref pw 300000 nil)
      (doseq [r rs] (println (pr-str (assoc (dissoc r :step) :reading :driver :host host)))))))
