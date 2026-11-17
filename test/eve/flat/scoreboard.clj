(ns eve.flat.scoreboard
  "The spike scoreboard (IMPL-PLAN §3.1): rows B1-B8 over the four corpora of
   MEASUREMENTS.md M6, both arms in one session, the minimum of N runs,
   appended to doc/flat/SPIKE-LOG.md.

   An arm that does not exist yet records \"not built\"; the incumbent arm is
   measured. B4 (atom->atom) is timed as ONE run through today's
   :foreign-policy :copy path (W0.6) - it had only ever been a sum of parts.

   Run it (from the repo root):
     CLJ_CONFIG=/home/hatch/workspace/eve-env/cljconfig \\
       clojure -Sdeps '{:deps {com.cognitect/transit-clj {:mvn/version \"1.0.333\"}}}' \\
       -M -e \"(require 'eve.flat.scoreboard)\" -m eve.flat.scoreboard
   The classpath needs the test paths: use -X:jvm-test's classpath, e.g.
     CP=$(CLJ_CONFIG=... clojure -Spath -X:jvm-test -Sdeps '{...transit...}')
     java -Xmx4g -cp \"$CP\" clojure.main -m eve.flat.scoreboard -- --runs 5
   Options: --runs N --warmup N --tiny --label TEXT --log PATH."
  (:require [clojure.string :as str]
            [eve.atom :as ea]
            [eve.flat.corpora :as corpora])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream File]
           [java.util.zip GZIPOutputStream]
           [java.time ZonedDateTime ZoneId]
           [java.time.format DateTimeFormatter]))

;; Pre-sized in-process domains, so a store's number is not a growth number
;; (the P6 probe's method). {class-idx bytes} Sized from the corpora's block
;; censuses with slack, not flat: a domain is ~44 MB of regions, and a run
;; makes dozens of them.
(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(defn- bench-min
  "The minimum of `runs` measured runs of f, after `warmup` unmeasured ones."
  [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i runs)
      best
      (let [t0 (System/nanoTime)
            _ (f)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms))))))

(defn- fresh-atom
  "A fresh pre-sized domain and one atom in it."
  [name]
  (let [dom (ea/atom-domain nil :capacities capacities)]
    (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- fmt-ms [x] (if (number? x) (format "%.2f ms" (double x)) (str x)))
(defn- fmt-bytes [x] (if (number? x) (format "%d B" (long x)) (str x)))

;; ---------------------------------------------------------------------------
;; Transit, when it is on the classpath (B6/B7's incumbent arm)
;; ---------------------------------------------------------------------------

(defn- transit-fns []
  (try
    (require 'cognitect.transit)
    {:writer (resolve 'cognitect.transit/writer)
     :reader (resolve 'cognitect.transit/reader)
     :write (resolve 'cognitect.transit/write)
     :read (resolve 'cognitect.transit/read)}
    (catch Throwable _ nil)))

(defn- transit-bytes [{:keys [writer write]} v]
  (let [out (ByteArrayOutputStream.)
        w (writer out :msgpack)]
    (write w v)
    (.toByteArray out)))

(defn- transit-read [{:keys [reader read]} ^bytes bs]
  (read (reader (ByteArrayInputStream. bs) :msgpack)))

(defn- gzip-size [^bytes bs]
  (let [out (ByteArrayOutputStream.)
        gz (GZIPOutputStream. out)]
    (.write gz bs)
    (.close gz)
    (alength (.toByteArray out))))

;; ---------------------------------------------------------------------------
;; One corpus, every row
;; ---------------------------------------------------------------------------

(defn- measure-corpus
  [kind v {:keys [warmup runs]}]
  ;; One full unmeasured cycle first: in a one-corpus JVM the first corpus
  ;; would otherwise eat all of Eve's compile time (the first baseline's
  ;; map-10k rows were ~50x M6 for exactly this reason).
  (let [warm (fresh-atom "sb/warm")]
    (swap! warm (fn [_] v))
    (deref warm)
    (let [tgt (fresh-atom "sb/warm-target")]
      (ea/set-foreign-policy! tgt :copy)
      (ea/read-pinned warm (fn [lv] (swap! tgt (fn [_] lv))))))
  (let [t (transit-fns)
        ;; B2/B5 today: a swap! of the plain value into a fresh pre-sized
        ;; domain, and the domain+atom alone it is netted against.
        dom-ms (bench-min warmup runs #(fresh-atom "sb/dom"))
        store-ms (bench-min warmup runs
                            (fn [] (let [a (fresh-atom "sb/store")]
                                     (swap! a (fn [_] v)))))
        ;; B3 today: @a (eve->clj) of an atom holding the value.
        holder (fresh-atom "sb/hold")
        _ (swap! holder (fn [_] v))
        value-ok (= v @holder)
        deref-ms (bench-min warmup runs #(deref holder))
        ;; B4 today, as one run: A's lazy value under the pin, stored into B
        ;; through B's domain's :foreign-policy :copy.
        target (fresh-atom "sb/target")
        _ (ea/set-foreign-policy! target :copy)
        transfer-ms (bench-min warmup runs
                               (fn [] (ea/read-pinned holder
                                                      (fn [lv] (swap! target (fn [_] lv))))))
        transfer-ok (= v @target)
        ;; B6/B7/B8 today: Transit MessagePack.
        tbytes (when t (transit-bytes t v))
        decode-ms (when t (bench-min warmup runs #(transit-read t tbytes)))]
    {:kind kind
     :value-ok value-ok
     :transfer-ok transfer-ok
     :rows [{:row :B1 :today "—" :arm "not built"
             :gate "≤ 50 ns/block"}
            {:row :B2 :today (str (fmt-ms store-ms) " (incl. domain; domain alone " (fmt-ms dom-ms) ")")
             :arm "not built" :gate "≤ 2x row B1"}
            {:row :B3 :today (fmt-ms deref-ms) :arm "not built"
             :gate "arm at least 2x faster than @a on every corpus"}
            {:row :B4 :today (fmt-ms transfer-ms) :arm "not built"
             :gate "≤ 2x (row B3 + row B1)"}
            {:row :B5 :today (fmt-ms store-ms) :arm "not built"
             :gate "under the probe's 0.37-0.56x of today's swap!"}
            {:row :B6 :today (if t (str "Transit MessagePack " (fmt-bytes (alength tbytes))) "transit not on classpath")
             :arm "not built" :gate "≤ 0.9x Transit MessagePack (messages, records)"}
            {:row :B7 :today (if t (str "Transit decode " (fmt-ms decode-ms)) "transit not on classpath")
             :arm "not built" :gate "decode ≥ 2.5x Transit - scan ≤ 2 ns/row - filter ≤ 4 ns/row - cell ≤ 2x host - row view ≤ 1 µs"}
            {:row :B8 :today (if t (str "whole value as one gzip stream: " (fmt-bytes (gzip-size tbytes))) "transit not on classpath")
             :arm "not built" :gate "≤ 5% over the whole value compressed as one"}]}))

;; ---------------------------------------------------------------------------
;; The run
;; ---------------------------------------------------------------------------

(defn- git-short []
  (try
    (let [head (str/trim (slurp ".git/HEAD"))]
      (if (str/starts-with? head "ref:")
        (subs (str/trim (slurp (str ".git/" (str/trim (subs head 4))))) 0 7)
        (subs head 0 7)))
    (catch Throwable _ "unknown")))

(defn- load1 []
  (try (first (str/split (str/trim (slurp "/proc/loadavg")) #" "))
       (catch Throwable _
         (try (first (str/split (str/trim (str (first (java.nio.file.Files/readAllLines
                                                       (java.nio.file.Paths/get "/proc/loadavg"
                                                                                (into-array String []))))))
                                #" "))
              (catch Throwable _ "?")))))

(defn render-section
  [results {:keys [label runs]}]
  (let [ts (.format (ZonedDateTime/now (ZoneId/of "America/New_York"))
                    (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))
        lines (into [(str "## " ts " ET — " (or label "scoreboard run")
                          " — commit " (git-short)
                          " - JVM " (System/getProperty "java.version")
                          " - min of " runs " runs - load1 " (load1))
                     ""
                     "| row | corpus | today | flat arm | gate |"
                     "|---|---|---|---|---|"]
                    (concat (for [{:keys [kind rows]} results
                                  {:keys [row today arm gate]} rows]
                              (str "| " (name row) " | " (corpora/corpus-labels kind)
                                   " | " today " | " arm " | " gate " |"))
                            [""
                             "B4 (atom->atom) is timed as ONE run: the source value read under the pin and stored through the target domain's :foreign-policy :copy — never a sum of parts."]))]
    (str (str/join "\n" lines) "\n\n")))

(defn run!
  "Run every row on every corpus and append the section to :log (default
   doc/flat/SPIKE-LOG.md). Options: :corpora :canonical|:tiny, :only (one
   corpus kind — a full run makes dozens of heap domains, so the baseline is
   taken one corpus per JVM), :runs, :warmup, :log, :label. Returns the
   per-corpus results."
  [{:keys [corpora runs warmup log label only]
    :or {corpora :canonical runs 5 warmup 2 log "doc/flat/SPIKE-LOG.md"}}]
  (let [results (mapv (fn [[kind v]]
                        (System/gc)
                        (try (measure-corpus kind v {:warmup warmup :runs runs})
                             (catch Throwable e
                               {:kind kind :error (str e) :rows []})))
                      (cond->> (corpora/corpora corpora)
                        only (filter (fn [[k _]] (= k only)))))
        section (render-section results {:label label :runs runs})
        f (File. log)]
    (when-not (.exists f)
      (spit f "# Flat Eve — SPIKE-LOG\n\nThe scoreboard of IMPL-PLAN §3.1, appended by every run.\n\n"))
    (spit f section :append true)
    (println section)
    (doseq [{:keys [kind value-ok transfer-ok error]} results]
      (when error (println "SCOREBOARD ERROR" kind error))
      (when (false? value-ok) (println "SCOREBOARD MISMATCH: stored value differs for" kind))
      (when (false? transfer-ok) (println "SCOREBOARD MISMATCH: transferred value differs for" kind)))
    results))

(defn -main [& args]
  (let [opts (loop [args args opts {}]
               (if (empty? args)
                 opts
                 (let [[a & more] args]
                   (case a
                     "--tiny" (recur more (assoc opts :corpora :tiny))
                     "--runs" (recur (rest more) (assoc opts :runs (parse-long (first more))))
                     "--warmup" (recur (rest more) (assoc opts :warmup (parse-long (first more))))
                     "--label" (recur (rest more) (assoc opts :label (first more)))
                     "--corpus" (recur (rest more) (assoc opts :only (keyword (first more))))
                     "--log" (recur (rest more) (assoc opts :log (first more)))
                     (recur more opts)))))]
    (run! opts)
    (System/exit 0)))
