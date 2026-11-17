;; T1 board row B4 — atom -> atom, both arms in one session, the minimum
;; of N runs after warmup (the scoreboard's method and domain capacities,
;; so the numbers stand next to W0's :copy baseline).
;;
;;   detach+zip arm: detach (T1's walk — Z1's canonical detach is measured
;;     broken at z1 tip 22ceacb: ArithmeticException in its hash-of,
;;     image.cljc:345, on the STM cell map) + place-image! (Z3's place).
;;   transfer arm:   eve.static/transfer! (atom -> atom, under the pin).
;;
;; Gate (IMPL-PLAN board row B4): transfer <= 2x (detach + zip alone),
;; and <= 5 / 26 / 9 / 26 ms on map-10k / vec-100k / records-10k /
;; messages-5k. Both legs are printed per corpus; a failed leg is a
;; failed leg, never relabeled.
;;
;; Run (from the repo root, on the venue box):
;;   CP=$(clojure -Spath -A:jvm-test)
;;   java -Xmx4g -cp "$CP" clojure.main doc/static/probes-t1/b4_bench.clj [--runs 5] [--warmup 2] [--tiny]
(ns t1.b4-bench
  (:require [clojure.string :as str]
            [eve.atom :as ea]
            [eve.static :as st]
            [eve.static.corpora :as corpora]
            [eve.static.transfer :as tf]))

(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(def ^:private caps-ms
  {:map-10k 5.0 :vec-100k 26.0 :records-10k 9.0 :messages-5k 26.0})

(def ^:private detach-value @#'tf/detach-value)

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
  (let [dom (ea/atom-domain nil :capacities capacities)]
    (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- attempt [f]
  (try {:ok (f)} (catch Throwable t {:refused (ex-message t)})))

(defn- git-short []
  (try
    (let [head (str/trim (slurp ".git/HEAD"))]
      (if (str/starts-with? head "ref:")
        (subs (str/trim (slurp (str ".git/" (str/trim (subs head 4))))) 0 7)
        (subs head 0 7)))
    (catch Throwable _ "unknown")))

(defn- measure [kind v {:keys [warmup runs]}]
  (let [holder (fresh-atom "b4/hold")
        _ (swap! holder (fn [_] v))
        bytes-r (attempt #(ea/read-pinned holder (fn [lv] (detach-value lv))))
        bytes (:ok bytes-r)
        ;; correctness first, untimed: each arm must read back equal
        zip-ok (when bytes
                 (let [t (fresh-atom "b4/zip-check")]
                   (tf/place-image! t bytes)
                   (= v @t)))
        tf-ok (let [t (fresh-atom "b4/tf-check")]
                (st/transfer! t holder)
                (= v @t))
        detach-ms (when bytes
                    (bench-min warmup runs
                               #(ea/read-pinned holder (fn [lv] (detach-value lv)))))
        zip-ms (when bytes
                 (bench-min warmup runs
                            #(tf/place-image! (fresh-atom "b4/zip") bytes)))
        transfer-ms (bench-min warmup runs
                               #(st/transfer! (fresh-atom "b4/tf") holder))]
    {:kind kind :bytes (when bytes (alength bytes))
     :zip-ok zip-ok :tf-ok tf-ok
     :detach-ms detach-ms :zip-ms zip-ms :transfer-ms transfer-ms
     :refused (or (:refused bytes-r) nil)}))

(let [args (set *command-line-args*)
      opt (fn [k d] (let [i (.indexOf (vec *command-line-args*) k)]
                      (if (neg? i) d (Long/parseLong (nth *command-line-args* (inc i))))))
      runs (opt "--runs" 5)
      warmup (opt "--warmup" 2)
      sizes (if (contains? args "--tiny") :tiny :canonical)
      only (let [i (.indexOf (vec *command-line-args*) "--only")]
             (when-not (neg? i) (keyword (nth *command-line-args* (inc i)))))]
  (println "B4 atom->atom — T1 arms, one session")
  (println " tree:" (git-short)
           "| java:" (System/getProperty "java.version")
           "| load1:" (try (first (str/split (str/trim (slurp "/proc/loadavg")) #" ")) (catch Throwable _ "?"))
           "| runs:" runs "warmup:" warmup "sizes:" (name sizes))
  (println " detach arm: eve.static.transfer walk (T1); zip arm: eve.static.place via place-image! (Z3); transfer arm: eve.static/transfer!")
  (doseq [[kind v] (cond->> (corpora/corpora sizes)
                     only (filter #(= only (first %))))]
    (let [r (measure kind v {:warmup warmup :runs runs})
          dz (when (and (:detach-ms r) (:zip-ms r)) (+ (:detach-ms r) (:zip-ms r)))
          ratio (when (and dz (:transfer-ms r)) (/ (:transfer-ms r) dz))
          cap (get caps-ms kind)
          ratio-ok (when ratio (<= ratio 2.0))
          cap-ok (when (:transfer-ms r) (<= (:transfer-ms r) cap))]
      (println (format "%s bytes=%s zip-ok=%s tf-ok=%s detach=%s zip=%s detach+zip=%s transfer=%s ratio=%s cap=%s ratio<=2x:%s under-cap:%s %s"
                       (name kind)
                       (pr-str (:bytes r))
                       (pr-str (:zip-ok r))
                       (pr-str (:tf-ok r))
                       (if (:detach-ms r) (format "%.2fms" (:detach-ms r)) "—")
                       (if (:zip-ms r) (format "%.2fms" (:zip-ms r)) "—")
                       (if dz (format "%.2fms" dz) "—")
                       (if (:transfer-ms r) (format "%.2fms" (:transfer-ms r)) "—")
                       (if ratio (format "%.2f" ratio) "—")
                       (format "%.0fms" cap)
                       (pr-str ratio-ok)
                       (pr-str cap-ok)
                       (if (:refused r) (str "REFUSED: " (:refused r)) "")))))
  (println "B4 BENCH DONE"))
(System/exit 0)
