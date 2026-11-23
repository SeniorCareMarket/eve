(ns eve.flat.f3fix-probe
  "F3FIX lane re-derivation probe: the three F3-MEASURE defect claims,
   composed end to end through the shipped doors at this pin.

     CLAIM 1  build/build-image -> place/place into a fresh atom, read
              back equal? (prints the image's relocation keys/counts)
     CLAIM 2  chunk/pack + chunk/pack-all bytes -> chunk/open-checked:
              accepted or refused (and the control doors: chunk/open
              decodes equal; eve.flat.check/open-checked vouches)
     CLAIM 3  chunk/pack-all scaling series over records rows
              (n, 2n, 4n, 8n), one timed call each, plus a prefix-length
              monotonicity spot check (the seal search's premise)

   Every step prints before it runs; failures are caught per step and
   printed with their type. Run: java -cp $(clojure -Spath -X:jvm-test)
   clojure.main -m eve.flat.f3fix-probe"
  (:require [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.flat.build :as build]
            [eve.flat.check :as check]
            [eve.flat.chunk :as chunk]
            [eve.flat.corpora :as corpora]
            [eve.flat.place :as place]))

(def ^:private capacities
  {0 4194304 1 4194304 2 8388608 3 4194304 4 2097152 5 2097152})

(defn- fresh-atom [name]
  (let [dom (ea/atom-domain nil :capacities capacities)]
    (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- step [label f]
  (println "STEP" label "running") (flush)
  (try
    (let [r (f)]
      (println "STEP" label "->" r) (flush)
      r)
    (catch Throwable t
      (println "STEP" label "FAILED" (.getName (class t)) "-" (.getMessage t)) (flush)
      ::failed)))

(defn- timed-ms [f]
  (let [t0 (System/nanoTime)
        r (f)]
    [r (/ (double (- (System/nanoTime) t0)) 1e6)]))

;; ---------------------------------------------------------------------------
;; Claim 1: builder -> place
;; ---------------------------------------------------------------------------

(defn- claim1-arm [label v]
  (step label
        (fn []
          (let [img (build/build-image v)]
            (println "  image: blocks" (count (:blocks img))
                     "relocations-key count" (count (:relocations img))
                     ;; this probe exists to compare the two key
                     ;; spellings against the built image — :relocs
                     ;; is read deliberately, so its unknown-map-key
                     ;; finding is the probe working as designed.
                     "relocs-key count" (count #_{:clj-kondo/ignore [:unknown-map-key]} (:relocs img)))
            (let [a (fresh-atom (str "f3fix/c1-" (rand-int 1000000000)))
                  placed (place/place a img)]
              (= v (d/eve->clj placed)))))))

(defn- claim1 []
  (println "== CLAIM 1: build-image -> place ==")
  (claim1-arm "c1 small nested map" {:a [1 2 3] :b {:c "x"} :s #{1 2} :n [[1 2] [3 4]]})
  (claim1-arm "c1 records x200" (corpora/build :records-10k 200))
  (claim1-arm "c1 vec of maps x50" (mapv (fn [i] {:id i :tags [:x :y]}) (range 50))))

;; ---------------------------------------------------------------------------
;; Claim 2: writer -> chunk/open-checked
;; ---------------------------------------------------------------------------

(defn- try-open-checked [^bytes bs]
  (try
    (chunk/open-checked bs)
    :accepted
    (catch clojure.lang.ExceptionInfo e
      (str "REFUSED " (:type (ex-data e)) " - " (.getMessage e)))))

(defn- claim2-arm [label v]
  (step label
        (fn []
          (let [single (chunk/chunk-bytes (chunk/pack v))
                all (chunk/pack-all [v])]
            (println "  chunks: single + " (count all) "pack-all"
                     "lengths:" (mapv alength (into [single] all)))
            {:open-checked-single (try-open-checked single)
             :open-checked-pack-all (mapv try-open-checked all)
             :open-decodes-equal-single (= v (chunk/unpack (chunk/open single)))
             :open-decodes-equal-pack-all (= [v] (chunk/unpack (chunk/open (first all))))
             :check-door (mapv (fn [^bytes bs] (check/checked? (check/open-checked bs)))
                               (into [single] all))}))))

(defn- claim2 []
  (println "== CLAIM 2: writer bytes -> chunk/open-checked ==")
  (claim2-arm "c2 records x300 (as one vector value)"
              (corpora/build :records-10k 300))
  (claim2-arm "c2 nested map" {:a [1 2 3] :b {:c "x" :d [true nil 1.5]}})
  ;; pack-all over many rows: each chunk's root is the vector of its rows
  (step "c2 pack-all records x2000, per chunk"
        (fn []
          (let [rows (corpora/build :records-10k 2000)
                bss (chunk/pack-all rows)]
            (println "  chunks:" (count bss) "lengths:" (mapv alength bss))
            {:open-checked (mapv try-open-checked bss)
             :roundtrip (= rows (vec (mapcat (fn [^bytes bs] (chunk/unpack (chunk/open bs))) bss)))}))))

;; ---------------------------------------------------------------------------
;; Claim 3: pack-all scaling
;; ---------------------------------------------------------------------------

(defn- old-pack-all
  "The pre-fix pack-all, verbatim (one full encode per row), kept here
   only as the comparison arm for the seal search's output."
  [values]
  (let [rows (vec values)]
    (if (empty? rows)
      [(#'chunk/pack-rep (chunk/to-rep []))]
      (loop [i 0 cur-reps [] out []]
        (if (== i (count rows))
          (conj out (#'chunk/pack-rep cur-reps))
          (let [r (chunk/to-rep (nth rows i))
                cand (conj cur-reps r)]
            (if (and (seq cur-reps)
                     (> (alength (#'chunk/pack-rep cand)) 65535))
              (recur i [] (conj out (#'chunk/pack-rep cur-reps)))
              (recur (inc i) cand out))))))))

(defn- claim3 []
  (println "== CLAIM 3: pack-all scaling (records rows) ==")
  (let [rows (corpora/build :records-10k 10000)]
    ;; the prefix-length series, printed in full: where does encoded
    ;; length dip as the prefix grows (the seal search's premise)?
    (step "c3 prefix lengths, prefixes 1..40"
          (fn []
            (let [lens (mapv (fn [k] (alength (chunk/chunk-bytes (chunk/pack (subvec rows 0 k)))))
                             (range 1 41))]
              {:series lens :non-decreasing (apply <= lens)})))
    (doseq [n [250 500 1000 2000 4000 10000]]
      (step (str "c3 pack-all records x" n)
            (fn []
              (let [rs (subvec rows 0 n)
                    [bss ms] (timed-ms #(chunk/pack-all rs))]
                {:ms (Math/round ms)
                 :chunks (count bss)
                 :max-len (reduce max (map alength bss))
                 :roundtrip (= rs (vec (mapcat (fn [^bytes bs] (chunk/unpack (chunk/open bs))) bss)))}))))
    ;; the seal search's output vs the pre-fix algorithm's, on a corpus
    ;; that seals several chunks either way
    (step "c3 seal search vs pre-fix algorithm, payload rows x2000"
          (fn []
            (let [rs (mapv (fn [i] {:id i :payload (str (apply str (repeat 190 "x")) "-" i)}) (range 2000))
                  old (old-pack-all rs)
                  new (chunk/pack-all rs)
                  same-bytes (and (== (count old) (count new))
                                  (every? true? (map (fn [^bytes a ^bytes b]
                                                       (java.util.Arrays/equals a b)) old new)))]
              {:old-chunks (count old) :new-chunks (count new)
               :old-lens (mapv alength old) :new-lens (mapv alength new)
               :byte-identical same-bytes
               :new-roundtrip (= rs (vec (mapcat chunk/unpack new)))})))))

(defn -main [& _]
  (claim1)
  (claim2)
  (claim3)
  (println "PROBE DONE")
  (shutdown-agents)
  (System/exit 0))
