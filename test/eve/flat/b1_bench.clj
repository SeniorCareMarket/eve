(ns eve.flat.b1-bench
  "Lane B1's board-row bench (IMPL-PLAN §3.1, row B5: plain -> atom).

   Method follows the scoreboard (test/eve/flat/scoreboard.clj): a fresh
   pre-sized atom per rep (its domain made once per corpus — see fresh-atom),
   the minimum of N runs after warmup, both arms in one session on the four
   M6 corpora (eve.flat.corpora).

   Arms:
     today   (swap! a (fn [_] v)) — the incumbent plain store.
     direct  eve.flat.build/build-image of v (outside the atom), then a
             swap! whose fn places the image in the atom's domain (one
             alloc-run! claim per size class, bytes copied, pointers
             patched from the relocation table) and returns the placed
             value. Resolved lazily: \"not built\" until lane B1 lands.

   Run (from the repo root):
     CP=$(CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig clojure -Spath -X:jvm-test)
     java -Xmx4g -cp \"$CP\" clojure.main -m eve.flat.b1-bench -- --runs 5
   Options: --runs N --warmup N --tiny"
  (:require [eve.atom :as ea]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.flat.corpora :as corpora])
  ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
  (:import #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays]))

(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(defn- fresh-domain []
  (ea/atom-domain nil :capacities capacities))

(defn- fresh-atom
  "A fresh atom in dom. Per-rep freshness is the atom; the domain is made
   once per corpus (the P6 probe's rows are likewise insensitive to domain
   creation: its caps were image-sized and creation was ~3% of the plain
   arm). A 44 MB flat domain per rep would measure region zeroing, not the
   store."
  ([name] (fresh-atom (fresh-domain) name))
  ([dom name] (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- bench-min [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i runs)
      best
      (let [t0 (System/nanoTime)
            _ (f)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms))))))

;; ---------------------------------------------------------------------------
;; Placing an image in a domain (the bench's stand-in for lane Z3, on the
;; Wave 0 default: alloc-run! per size class, per-block for the overflow
;; class; bytes copied; every relocation patched.)
;; ---------------------------------------------------------------------------

(defn- class-of [size]
  (let [sizes alloc/CLASS_BLOCK_SIZES]
    (loop [c 0]
      (cond (== c (count sizes)) 6
            (<= size (nth sizes c)) c
            :else (recur (inc c))))))

(defn place-image
  "Place image {:bytes :blocks :relocations :root} in sio. Returns the
   placed root's slab offset."
  [sio {:keys [bytes blocks relocations root]}]
  (let [n (count blocks)
        new-offs (long-array n)
        by-class (group-by (fn [i] (class-of (:size (nth blocks i)))) (range n))]
    (doseq [[c idxs] by-class]
      (if (== c 6)
        (doseq [i idxs]
          (aset new-offs i (alloc/-sio-alloc! sio (:size (nth blocks i)))))
        (let [offs (alloc/alloc-run! sio c (count idxs))]
          (when (nil? offs)
            (throw (ex-info "b1-bench place: alloc-run! found no run" {:class c :n (count idxs)})))
          (doseq [[i o] (map vector idxs offs)]
            (aset new-offs i (long o))))))
    (doseq [i (range n)
            :let [{:keys [offset size]} (nth blocks i)]]
      (alloc/-sio-write-bytes! sio (aget new-offs i) 0
                               (Arrays/copyOfRange ^bytes bytes (int offset) (int (+ offset size)))))
    (doseq [{:keys [position owner target]} relocations]
      (let [owner-old (:offset (nth blocks owner))
            pos-in-owner (- (long position) owner-old)]
        (alloc/-sio-write-i32! sio (aget new-offs owner) pos-in-owner (aget new-offs target))))
    (aget new-offs 0)))

(defn- reopen [sio off]
  (let [t (ser/read-header-tag sio off)
        ctor (ser/get-header-constructor t)]
    (ctor sio off)))

(defn- build-fns []
  (try
    (require 'eve.flat.build)
    {:build-image (resolve 'eve.flat.build/build-image)}
    (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------

(defn- measure [kind v {:keys [warmup runs]} bf]
  ;; Each arm gets its own domain: the direct arm must not inherit the
  ;; today arm's allocator state (seven churned stores fragment the class
  ;; regions; the probe's arms likewise ran in their own domains).
  (let [counter (atom 0)
        dom-t (fresh-domain)
        dom-d (fresh-domain)
        fresh-t (fn [prefix] (fresh-atom dom-t (str prefix "-" (swap! counter inc))))
        fresh-d (fn [prefix] (fresh-atom dom-d (str prefix "-" (swap! counter inc))))
        holder (fresh-t "b1/hold")
        _ (swap! holder (fn [_] v))
        ok-today (= v @holder)
        ;; Arms alternate per rep: on this shared box the load swings
        ;; minute to minute, and sequential arms were catching different
        ;; weather (the same corpus read 0.92x in one run, 2.52x in the
        ;; next). Minima over interleaved reps sample the same sky.
        today-rep (fn [] (let [a (fresh-t "b1/store")
                               t0 (System/nanoTime)]
                           (swap! a (fn [_] v))
                           (/ (- (System/nanoTime) t0) 1e6)))
        direct-rep (when bf
                     (fn [] (let [a (fresh-d "b1/direct")
                                  t0 (System/nanoTime)
                                  im ((:build-image bf) v)
                                  t1 (System/nanoTime)]
                              (swap! a (fn [_]
                                         (let [sio d/*domain-sio*
                                               off (place-image sio im)]
                                           (reopen sio off))))
                              (let [t2 (System/nanoTime)]
                                {:build (/ (- t1 t0) 1e6) :place (/ (- t2 t1) 1e6)}))))
        _ (dotimes [_ warmup] (today-rep) (when direct-rep (direct-rep)))
        measured (loop [i 0 s {:today Double/MAX_VALUE :build Double/MAX_VALUE :place Double/MAX_VALUE}]
                   (if (== i runs)
                     s
                     (let [s (assoc s :today (min (:today s) (today-rep)))
                           s (if direct-rep
                               (let [r (direct-rep)]
                                 (assoc s :build (min (:build s) (:build r))
                                        :place (min (:place s) (:place r))))
                               s)]
                       (recur (inc i) s))))
        today-ms (:today measured)
        direct (when direct-rep
                 (let [split measured
                       placed (let [a (fresh-d "b1/verify")
                                    im ((:build-image bf) v)]
                                (swap! a (fn [_]
                                           (let [sio d/*domain-sio*
                                                 off (place-image sio im)]
                                             (reopen sio off))))
                                @a)]
                   {:ms (+ (:build split) (:place split))
                    :build-ms (:build split)
                    :place-ms (:place split)
                    :ok (= v placed)}))]
    {:kind kind :today-ms today-ms :today-ok ok-today :direct direct}))

(defn -main [& args]
  (let [opts (loop [args (seq args) m {:runs 5 :warmup 2 :tiny false :build-only false :corpus nil}]
               (if (nil? args)
                 m
                 (let [[a & r] args]
                   (case a
                     "--runs" (recur (next r) (assoc m :runs (parse-long (first r))))
                     "--warmup" (recur (next r) (assoc m :warmup (parse-long (first r))))
                     "--tiny" (recur r (assoc m :tiny true))
                     "--build-only" (recur r (assoc m :build-only true))
                     "--corpus" (recur (next r) (assoc m :corpus (keyword (first r))))
                     (recur r m)))))
        sizes (if (:tiny opts) corpora/tiny-sizes corpora/canonical-sizes)
        kinds (if (:corpus opts) [(:corpus opts)] corpora/corpus-order)
        bf (build-fns)]
    (if (:build-only opts)
      (do (println "B1 bench — build-only attribution: build-image alone, min of runs")
          (doseq [kind kinds]
            (let [v (corpora/build kind (get sizes kind))
                  ms (bench-min (:warmup opts) (:runs opts) (fn [] ((:build-image bf) v)))
                  im ((:build-image bf) v)]
              (println (format "  %-24s build %9.2f ms | chunk %d B, %d blocks, %d relocations"
                               (get corpora/corpus-labels kind) (double ms)
                               (long (:length im)) (count (:blocks im)) (count (:relocations im)))))
            (System/gc)))
      (do (println "B1 bench — row B5 plain -> atom | today: swap! of the plain value"
                   "| direct: build-image + place + swap!"
                   (when (nil? bf) "| direct arm: NOT BUILT"))
          (doseq [kind kinds]
            (let [v (corpora/build kind (get sizes kind))
                  {:keys [today-ms today-ok direct]} (measure kind v opts bf)]
              (println (format "  %-24s today %9.2f ms (read-back %s)%s"
                               (get corpora/corpus-labels kind)
                               (double today-ms)
                               (if today-ok "ok" "MISMATCH")
                               (if direct
                                 (format " | direct %9.2f ms (%.2fx, build %.2f + place/swap %.2f, read-back %s)"
                                         (double (:ms direct))
                                         (double (/ (:ms direct) today-ms))
                                         (double (:build-ms direct))
                                         (double (:place-ms direct))
                                         (if (:ok direct) "ok" "MISMATCH"))
                                 "")))
              (System/gc)))))
    (shutdown-agents)
    (System/exit 0)))
