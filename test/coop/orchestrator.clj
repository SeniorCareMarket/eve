(ns coop.orchestrator
  "The cooperative disk-atom scenarios with the wasm runtime in the mix (doc/testing.md; run by test/coop/run.sh).
   The JVM is this process (eve.atom); Node is eve's own worker (target/eve-test/mmap-worker.js, coop-* modes);
   bb is bb_worker.clj over eve's bb regions; cljr is cljr_worker.clj under ClojureCLR over eve's CLR regions; lpy is
   lpy_worker.lpy under basilisp over eve's basilisp regions (the capstone's modes only); jolt is jolt_worker.clj
   under jolt over eve's jolt regions; clje is clje_worker.clje under clojerl over eve's NIF regions (the
   capstone's modes only); wasm
   is workers/coop_worker.vs compiled by ValueScript's builder and run by its vs-eve-host (hosts/eve-mmap). Every participant
   joins the same atom eve/main at one base path.

   Environment (run.sh sets it): EVE_DIR, COOP_DIR (this test's directory), COOP_WASM_HOST, COOP_WASM_WORKER,
   COOP_ROOT (a directory the bases live under: the wasm host's --eve-root), COOP_SCALE (contention ops per writer)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [eve.atom :as atom]
            [eve.tally]
            [eve.deftype-proto.alloc]
            [coop.scenario :as sc]
            [coop.mix :as mix])
  (:import [java.lang ProcessBuilder]
           [java.io File]))

(defn- env [k dflt] (or (System/getenv k) dflt))
(def ^:private coop-dir (env "COOP_DIR" (str (System/getProperty "user.dir") "/test/coop")))
(def ^:private eve-dir (env "EVE_DIR" (str coop-dir "/../..")))
(def ^:private wasm-host (env "COOP_WASM_HOST" ""))
(def ^:private wasm-worker (env "COOP_WASM_WORKER" ""))
(def ^:private root (env "COOP_ROOT" "/tmp/coop-atom"))
(def ^:private scale (parse-long (env "COOP_SCALE" "40")))

(defn- base [tag] (str root "/" tag "-" (System/currentTimeMillis)))

(defn- sh! [dir cmd & [env-vars]]
  (let [pb (doto (ProcessBuilder. ^java.util.List cmd) (.directory (File. ^String dir)))
        _ (when env-vars (.putAll (.environment pb) ^java.util.Map env-vars))
        p (.start pb)
        out (future (slurp (.getInputStream p)))
        err (future (slurp (.getErrorStream p)))
        exit (.waitFor p)]
    {:exit exit :out @out :err @err :cmd cmd}))

(def ^:private host-roots (edn/read-string (slurp (str eve-dir "/hosts.edn"))))

(defn- cp
  "A host's classpath: eve's src, the host's adapter roots (eve/hosts.edn), eve's test (coop.scenario, coop.mix)."
  [host]
  (concat [(str eve-dir "/src")] (map #(str eve-dir "/" %) (host-roots host)) [(str eve-dir "/test")]))

(defn- node! [& args] (sh! eve-dir (into ["node" "target/eve-test/mmap-worker.js"] args)))
(defn- bb! [& args]
  (sh! coop-dir (into ["bb" "--classpath" (str/join ":" (cp :bb)) (str coop-dir "/workers/bb_worker.clj")] args)))
(defn- nbb! [& args]
  ;; from eve/: the worker loads eve's addon from build/Release there
  (sh! eve-dir (into ["nbb" "--classpath" (str/join ":" (cp :nbb)) (str coop-dir "/workers/nbb_worker.cljs")] args)))
(defn- cljr! [& args]
  ;; ClojureCLR's classpath is CLOJURE_LOAD_PATH
  (sh! coop-dir (into ["cljr" (str coop-dir "/workers/cljr_worker.clj")] args)
       {"CLOJURE_LOAD_PATH" (str/join ":" (cp :cljr))}))
(defn- lpy! [& args]
  ;; basilisp's compiled namespaces in eve's target/ (run.sh builds them), not beside the sources
  ;; its roots in reverse: basilisp searches the last -p first
  (sh! coop-dir (-> ["basilisp" "run"] (into (mapcat #(vector "-p" %)) (reverse (cp :lpy)))
                    (conj (str coop-dir "/workers/lpy_worker.lpy")) (into args))
       {"PYTHONPYCACHEPREFIX" (str eve-dir "/target/lpycache")}))
(defn- jolt! [& args]
  ;; jolt's source roots, given (-Scp): no deps.edn to resolve
  (sh! coop-dir (into ["jolt" "-Scp" (str/join ":" (cp :jolt)) (str coop-dir "/workers/jolt_worker.clj")] args)))
(defn- clje! [& args]
  ;; clojerl's code path, given with -pz (one path each: -pa takes every path up to the next option, the script
  ;; included); eve's NIF is built by run.sh before any of these start
  (sh! coop-dir (-> ["clojerl"] (into (mapcat #(vector "-pz" %)) (cp :clje))
                    (conj (str coop-dir "/workers/clje_worker.clje")) (into args))))
(defn- wasm! [& args] (sh! coop-dir (into [wasm-host "--eve-root" root wasm-worker] args)))

(defn- ok! [r]
  (is (zero? (:exit r)) (str (str/join " " (:cmd r)) " exited " (:exit r) "\n" (:err r) (:out r)))
  r)

(defn- first-edn [r] (edn/read-string (first (str/split-lines (:out r)))))

(defn- plain [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(plain k) (plain v)])) x)
        (set? x) (into #{} (map plain) x)
        (vector? x) (mapv plain x)
        (seq? x) (apply list (map plain x))
        :else x))

(defn- jvm-read [b]
  (let [d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} nil)
        v (plain @a)]
    (atom/close-atom-domain! d)
    v))

(defn- jvm-swap! [b f & args]
  (let [d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} nil)]
    (apply swap! a f args)
    (atom/close-atom-domain! d)))

;; ── the round trip: each runtime takes a turn, the value asserted at every handoff ──────────────────────────────

(def ^:private order ["node" "jvm" "bb" "wasm" "node" "jvm" "bb" "wasm" "node"])

(deftest round-trip-node-jvm-bb-wasm
  (let [b (base "rt")
        init {:step 0 :trail [] :totals {:sum 0}}]
    (testing "Node creates the domain and the atom"
      (ok! (node! "join-reset" b (pr-str init)))
      (is (= init (jvm-read b)) "the JVM reads Node's initial value"))
    (reduce (fn [expected [k tag]]
              (testing (str "turn " k ": " tag)
                (let [seen (case tag
                             "jvm" (let [v (jvm-read b)] (jvm-swap! b sc/rt-step "jvm") v)
                             "node" (first-edn (ok! (node! "coop-rt-step" b "node")))
                             "bb" (first-edn (ok! (bb! "rt-step" b "bb")))
                             "wasm" (first-edn (ok! (wasm! "rt-step" b "wasm"))))
                      after (sc/rt-step expected tag)]
                  (is (= expected seen) (str tag " read the value the previous turn left"))
                  (is (= after (jvm-read b)) (str "after " tag "'s turn the JVM reads exactly the step it committed"))
                  after)))
            init (map-indexed (fn [i t] [(inc i) t]) order))
    (testing "the last word: Node and wasm read the final value"
      (let [final (reduce sc/rt-step init order)]
        (is (= final (first-edn (ok! (node! "coop-read" b)))))
        (is (= final (first-edn (ok! (wasm! "read" b)))))
        (is (= 9 (:step final)))))))

;; ── the count across participants: a value two atoms share outlives a wasm write ────────────────────────────────
;; The JVM puts one map in two atoms of the domain (`$` hands a's map out without copying it: the second atom's slot
;; names the same blocks), then the wasm participant (vs.eve-disk) replaces the first atom's value. Every participant
;; reclaims by eve's count on the blocks' descriptor rows (eve.tally): the wasm write lets the map go once — its count
;; 2 -> 1 — and frees nothing of it. Before vs.eve-disk counted, its write freed every block the old value reached, and
;; the second atom's map was read out of freed (then reused) blocks.

(defn- reach
  "Every block the atom's value reaches, by eve.tally's reader."
  [a]
  (let [{:keys [sio]} (atom/-domain-state a)
        p (second (atom/version a))
        refs @#'eve.tally/refs]
    (if (or (== p -1) (== p -2))
      #{}
      (loop [work [[p (eve.tally/root-kind sio p)]] seen #{}]
        (if (empty? work)
          seen
          (let [[o k] (peek work) work (pop work)]
            (if (contains? seen o)
              (recur work seen)
              (recur (into work (refs sio o k)) (conj seen o)))))))))

(defn- flush-zero-queue! [a] (#'atom/flush-zero-queue! (atom/-domain-state a)))

(deftest a-shared-value-outlives-a-wasm-write
  (let [b (base "share")
        d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} nil)
        c (atom/persistent-atom {:id :coop/shared :persistent b} nil)
        churn (atom/persistent-atom {:id :coop/churn :persistent b} nil)
        v (into {} (map (fn [i] [(keyword (str "k" i)) {:i i :s (str "value " i) :v [i (* 2 i) (* 3 i)]}])) (range 150))
        sio (:sio (atom/-domain-state a))
        allocated? (fn [o] (eve.deftype-proto.alloc/allocated? sio o))]
    (try
      (reset! a v)
      (atom/$ a (fn [m] (swap! c (constantly m)) nil))
      (let [shared (second (atom/version a))
            blocks (reach c)]
        (testing "one map, two atoms"
          (is (== shared (second (atom/version c))) "c's slot names a's map: nothing copied")
          (is (= 2 (eve.tally/count-of sio shared)) "a's slot and c's slot"))
        (testing "the wasm participant replaces a's value"
          (ok! (wasm! "reset" b (pr-str {:replaced-by "wasm"})))
          (is (= {:replaced-by "wasm"} (plain @a)))
          (is (= 1 (eve.tally/count-of sio shared)) "the wasm write let the map go once: c still holds it"))
        (testing "c's map is whole after the allocator hands blocks out again"
          (dotimes [i 20]
            (reset! churn (into {} (map (fn [j] [j (str "churn " i " " j)])) (range 120)))
            (flush-zero-queue! churn))
          (is (every? allocated? blocks) "no block of the shared map was freed")
          (is (= v (plain @c))))
        (testing "c lets go: every block of the map is freed"
          (reset! c nil)
          (flush-zero-queue! c)
          (is (not-any? allocated? blocks))))
      (finally
        (atom/close-atom-domain! d)))))

;; ── contention: every runtime swapping the one atom at once ────────────────────────────────────────────────────

(defn- wasm-contend!
  "A wasm writer's n ops: one process, or — COOP_WASM_BATCH set and n past it — a run of processes of that many ops each,
   one after another (the contend mode's start), since a VS program frees no temporaries and its whole run's
   allocation must fit under 2 GiB. The first failing process's result, else the last."
  [b w n]
  (let [batch (some-> (System/getenv "COOP_WASM_BATCH") parse-long)]
    (if (and batch (> n batch))
      (reduce (fn [_ s] (let [r (wasm! "contend" b w (str (min batch (- n s))) (str s))]
                          (if (zero? (:exit r)) r (reduced r))))
              nil (range 0 n batch))
      (wasm! "contend" b w (str n)))))

(defn- contend!
  "Runs the writers at once — {:jvm k-threads} plus [kind writer-id] processes — each `scale` ops; the per-writer
   op counts."
  [b jvm-threads procs n]
  (let [futs (doall (concat
                     (for [t (range jvm-threads)]
                       (future (let [w (str "jvm" t)
                                     d (atom/persistent-atom-domain b)
                                     a (atom/persistent-atom {:id :eve/main :persistent b} nil)]
                                 (dotimes [i n] (swap! a sc/contend-op w i))
                                 (atom/close-atom-domain! d)
                                 {:exit 0 :out "" :err "" :cmd ["jvm" w]})))
                     (for [[kind w] procs]
                       (future (case kind
                                 :node (node! "coop-contend" b w (str n))
                                 :bb (bb! "contend" b w (str n))
                                 :nbb (nbb! "contend" b w (str n))
                                 :wasm (wasm-contend! b w n))))))]
    (doseq [f futs] (ok! @f))
    (merge (into {} (for [t (range jvm-threads)] [(str "jvm" t) n]))
           (into {} (for [[_ w] procs] [w n])))))

(defn- check-contention [b per-writer]
  (let [snap (sc/snapshot (jvm-read b))
        total (reduce + 0 (vals per-writer))
        bad (sc/violations snap total per-writer)]
    (is (= [] bad) (str "invariants broken: " (pr-str bad) " snapshot " (pr-str snap)))
    (testing "Node and wasm read the same final state"
      (is (= (jvm-read b) (first-edn (ok! (node! "coop-read" b)))))
      (is (= snap (first-edn (ok! (wasm! "snapshot" b))))))
    snap))

(deftest contention-eve-only
  (testing "the control: 2 JVM threads + 2 Node + 2 nbb + 2 bb processes on one atom, no wasm"
    (let [b (base "ce")]
      (ok! (node! "join-reset" b (pr-str sc/initial-contention)))
      (let [pw (contend! b 2 [[:node "node0"] [:node "node1"] [:nbb "nbb0"] [:nbb "nbb1"] [:bb "bb0"] [:bb "bb1"]] scale)
            snap (sc/snapshot (jvm-read b))]
        (is (= [] (sc/violations snap (reduce + 0 (vals pw)) pw)) (str "invariants broken: snapshot " (pr-str snap)))
        (is (= (jvm-read b) (first-edn (ok! (node! "coop-read" b)))) "Node reads the JVM's final state")))))

(defn- control! [tag jvm-threads procs]
  (let [b (base tag)]
    (ok! (node! "join-reset" b (pr-str sc/initial-contention)))
    (let [pw (contend! b jvm-threads procs scale)
          snap (sc/snapshot (jvm-read b))]
      (is (= [] (sc/violations snap (reduce + 0 (vals pw)) pw)) (str "invariants broken: snapshot " (pr-str snap))))))

(deftest ^:control contention-jvm-only (testing "4 JVM threads" (control! "cj" 4 [])))
(deftest ^:control contention-node-only (testing "4 Node processes" (control! "cn" 0 (for [i (range 4)] [:node (str "node" i)]))))
(deftest ^:control contention-bb-only (testing "4 bb processes" (control! "cb" 0 (for [i (range 4)] [:bb (str "bb" i)]))))
(deftest ^:control contention-nbb-only (testing "4 nbb processes" (control! "cnb" 0 (for [i (range 4)] [:nbb (str "nbb" i)]))))

(deftest contention-jvm-wasm
  (testing "2 JVM threads + 2 wasm processes on one atom"
    (let [b (base "cjw")]
      (ok! (node! "join-reset" b (pr-str sc/initial-contention)))
      (let [pw (contend! b 2 [[:wasm "wasm0"] [:wasm "wasm1"]] scale)]
        (check-contention b pw)))))

(deftest contention-jvm-node-wasm
  (testing "2 JVM threads + 2 Node processes + 2 wasm processes on one atom"
    (let [b (base "ct")]
      (atom/persistent-atom-domain b)
      (atom/persistent-atom {:id :eve/main :persistent b} sc/initial-contention)
      (let [pw (contend! b 2 [[:node "node0"] [:node "node1"] [:wasm "wasm0"] [:wasm "wasm1"]] scale)]
        (check-contention b pw)))))

(deftest contention-wasm-only
  (testing "4 wasm processes on one atom (no other runtime)"
    (let [b (base "cw")]
      (ok! (node! "join-reset" b (pr-str sc/initial-contention)))
      (let [pw (contend! b 0 [[:wasm "w0"] [:wasm "w1"] [:wasm "w2"] [:wasm "w3"]] scale)]
        (check-contention b pw)))))

(deftest contention-all-runtimes
  (testing "2 JVM threads + 2 Node + 2 nbb + 2 bb + 2 wasm processes on one atom"
    (let [b (base "ca")]
      (ok! (node! "join-reset" b (pr-str sc/initial-contention)))
      (let [pw (contend! b 2 [[:node "node0"] [:node "node1"] [:nbb "nbb0"] [:nbb "nbb1"] [:bb "bb0"] [:bb "bb1"]
                              [:wasm "wasm0"] [:wasm "wasm1"]] scale)]
        (check-contention b pw)))))

;; ── round 2: the soak ───────────────────────────────────────────────────────────
;; The contention scenario at a load of its own: more writers and more ops, every runtime on one atom at once (bb
;; too since the eve unification's exclusive hold, §10). Run by name only (run.sh excludes ^:soak by default):
;;   COOP_SOAK_JVM (threads, default 2) · COOP_SOAK_NODE · COOP_SOAK_NBB · COOP_SOAK_BB · COOP_SOAK_WASM (processes,
;;   defaults 3, 2, 2, 3) · COOP_SCALE (ops per writer) · COOP_WASM_BATCH (a wasm writer's ops per process)

(deftest ^:soak contention-soak
  (let [j (parse-long (env "COOP_SOAK_JVM" "2"))
        nn (parse-long (env "COOP_SOAK_NODE" "3"))
        nb (parse-long (env "COOP_SOAK_NBB" "2"))
        bb (parse-long (env "COOP_SOAK_BB" "2"))
        nw (parse-long (env "COOP_SOAK_WASM" "3"))]
    (testing (str j " JVM threads + " nn " Node + " nb " nbb + " bb " bb + " nw " wasm processes × " scale
                  " ops on one atom")
      (let [b (base "cs")]
        (ok! (node! "join-reset" b (pr-str sc/initial-contention)))
        (let [pw (contend! b j (concat (for [i (range nn)] [:node (str "node" i)])
                                       (for [i (range nb)] [:nbb (str "nbb" i)])
                                       (for [i (range bb)] [:bb (str "bb" i)])
                                       (for [i (range nw)] [:wasm (str "wasm" i)]))
                           scale)]
          (check-contention b pw))))))

;; ── round 2: the semantics battery ─────────────────────────────────────────────
;; The round trip again, each turn also a semantics turn (scenario.cljc sem-write!): the round-trip part asserted as
;; above, and every divergence collected — a writer's shape or probe that differs from the JVM's own (a writer
;; divergence), a reader that sees other bytes than the JVM sees (a reader divergence) — then held against the named
;; list below. The list is the exception list of the report: each entry names the participant and why.

(def ^:private log-dir (env "COOP_LOG_DIR" "/tmp/coop-atom-logs"))

(defn- num-kind [x]
  (cond (instance? Long x) :long (instance? Integer x) :long (instance? Double x) :double
        (instance? clojure.lang.BigInt x) :bigint (instance? java.math.BigInteger x) :bigint
        (instance? clojure.lang.Ratio x) :ratio (instance? java.math.BigDecimal x) :bigdec :else (class x)))

(defn- strict=
  "= that also holds the number kind (Long 2 is not Double 2.0, BigInt 1N not Long 1), -0.0 apart from 0.0 and NaN
   equal to NaN, a vector apart from a list, a char apart from a one-char string."
  [a b]
  (cond
    (and (number? a) (number? b))
    (and (= (num-kind a) (num-kind b))
         (if (and (double? a) (double? b))
           (= (Double/doubleToLongBits a) (Double/doubleToLongBits b))
           (= a b)))
    (and (map? a) (map? b))
    (and (= (count a) (count b)) (every? (fn [[k v]] (and (contains? b k) (strict= v (get b k)))) a))
    (and (set? a) (set? b)) (= a b)
    (and (sequential? a) (sequential? b))
    (and (= (vector? a) (vector? b)) (= (count a) (count b)) (every? true? (map strict= a b)))
    :else (and (= (class a) (class b)) (= a b))))

(defn- losses
  "What a value lost on the way through eve besides its value: its sortedness, its metadata."
  [ref got]
  (cond-> []
    (and (sorted? ref) (not (sorted? got))) (conj :sortedness)
    (and (some? (meta ref)) (not= (meta ref) (meta got))) (conj :metadata)))

(defn- with-jvm-atom [b f]
  (let [d (atom/persistent-atom-domain b)
        a (atom/persistent-atom {:id :eve/main :persistent b} nil)]
    (try (f a) (finally (atom/close-atom-domain! d)))))

(defn- jvm-sem-read [b] (with-jvm-atom b sc/sem-read))
(defn- jvm-rt-read [b] (with-jvm-atom b #(sc/rt-view @%)))

(defn- two-edn [r]
  (let [[l1 l2] (str/split-lines (:out r))] [(edn/read-string l1) (edn/read-string l2)]))

(defn- writer-divergences
  "Where writer tag's semantics turn, as the JVM reads it back (r: jvm-sem-read), differs from the JVM's own."
  [r tag]
  (let [refused (get r [tag :refused])
        probes (get r [tag :probes])]
    (concat
     (for [[k v] (merge sc/sem-shapes sc/x-shapes)
           :let [why (get refused k) got (get r [tag :shapes k])]
           :when (or why (not (strict= v got)) (seq (losses v got)))]
       (cond why [:refused tag k why]
             (not (strict= v got)) [:wrote tag k got]
             :else [:lost tag k (losses v got)]))
     (for [[s k] sc/lookups
           :let [got (get r [tag :lookup s k])]
           :when (and (not (get refused s)) (not (strict= (get-in sc/sem-shapes [s k]) got)))]
       [:lookup tag [s k] got])
     (for [[k pv] (sc/probes) :let [got (get probes k)] :when (not= pv got)]
       [:probe tag k got]))))

(defn- reader-divergences
  "Where reader tag's view (seen: its sem-read output) differs from the JVM's view of the same bytes (r)."
  [tag r seen]
  (for [path (distinct (concat (keys r) (keys seen)))
        :let [v (get r path ::absent) s (get seen path ::absent)]
        :when (not (strict= v s))]
    [:read tag path s]))

(def ^:private untagged #{:x-bigint :x-bigint-small :x-ratio :x-bigdec :x-char})
(def ^:private cljs-numbers #{:dbl-integral :dbl-neg-zero :i64-max :i64-min :int-past-safe})
(def ^:private cljs-probes
  #{:=long-bigint :=long-double :=ratio-double :bigdec-add :bigdec-lit :bigint-add :bigint-lit :bigint-promote :char
    :char-int :char=str :dbl-integral :inc-max :int-char :long-overflow :nth-astral :ratio :ratio-add :str-seq})

(def ^:private exception-list
  "The exception list: every divergence the semantics battery expects, as named
   classes [id who why (fn [divergence] matches?)], a divergence being [:refused|:wrote|:lost|:lookup|:probe writer …]
   or [:read reader [writer …] seen]. A divergence no class names fails the test, and so does a class nothing matches
   any more (it was fixed: the list and the report move with the fix)."
  [[:F1 "every writer" "eve's format has no tag for sortedness or metadata: a sorted map/set is written as a hash
    map/set, metadata dropped — silently, by every runtime"
    (fn [[k _ s]] (and (= k :lost) (#{:x-sorted-map :x-sorted-set :x-meta-vec :x-meta-map} s)))]
   [:F2 "jvm bb wasm" "eve's format has no tag for a BigInt, a Ratio or a BigDecimal: refused (eve's JVM and bb throw
    value->eve-bytes: unsupported type; vs.eve-disk refuses alike). A char has one since 2026-10-16 (0x14, eve
    FORMAT-DECISIONS §4): the JVM, bb and the wasm participant write it"
    (fn [[k w s]] (and (= k :refused) (#{"jvm" "bb" "wasm"} w) (untagged s)))]
   [:C1 "node" "CLJS has no BigInt, Ratio, BigDecimal or char: not expressible (scenario.cljc writes a :cljs/no-*
    keyword), and a char literal is a one-character string"
    (fn [[k w s]] (and (= k :wrote) (= w "node") (untagged s)))]
   [:C2 "node" "CLJS numbers are doubles: an integral double and -0.0 go out INT32, a long past ±2^53 FLOAT64"
    (fn [[k w s]] (and (= k :wrote) (= w "node") (cljs-numbers s)))]
   [:C3 "node" "CLJS numbers are doubles, reading: a FLOAT64 2.0 or -0.0 prints as 2 or 0, an INT64 past ±2^53 rounds"
    (fn [[k w p]] (and (= k :read) (= w "node") (= :shapes (second p)) (cljs-numbers (get p 2))))]
   [:C5 "node" "CLJS has no char, reading: a char another runtime wrote (0x14) reads as a one-character string"
    (fn [[k w p]] (and (= k :read) (= w "node") (= :shapes (second p)) (= :x-char (get p 2))))]
   [:C4 "node" "CLJS semantics: no chars (\\a is \"a\", (= \\a \"a\") true), (= 1 1.0) true, no BigInt/Ratio/BigDecimal,
    no overflow (a long past 2^63 rounds to a double), (int \\a) 0"
    (fn [[k w s]] (and (= k :probe) (= w "node") (cljs-probes s)))]
   ;; K1 (Node wrote collection keys as pointers, read set keys as nil), K2 (bb looked keys up with Clojure's hash)
   ;; and K3 (a flat set's bytes followed the writer's iteration order) are fixed and gone from the list: K1 and K2 by
   ;; the eve unification (main 3cf77f1b — one byte format, keys flat on every host, bb on eve's map type), K3 on its
   ;; JVM, bb and Node sides there and on the wasm side by vs.eve-disk's canonical order (eve FORMAT-DECISIONS.md §2).
   ;; MEASURED on main, before vs.eve-disk followed it (1:47 AM ET 2026-10-16): K1 0, K2 0, K3 3, every K3 the wasm's.
   [:V1 "wasm (VS)" "a string's count and nth are code points in VS, UTF-16 units in Clojure: (count \"𝄞\") 1 vs 2,
    (int (nth \"𝄞\" 0)) 119070 vs 55348"
    (fn [[k w s]] (and (= k :probe) (= w "wasm") (#{:count-astral :nth-astral} s)))]
   [:V2 "wasm (VS)" "the overflow message: VS throws \"integer overflow\", Clojure 1.12.0 (and bb 1.13.226) \"long
    overflow\" — an ArithmeticException either way"
    (fn [[k w s]] (and (= k :probe) (= w "wasm") (#{:inc-max :long-overflow} s)))]])

(defn- classify [found]
  {:unnamed (set (remove (fn [d] (some (fn [[_ _ _ m?]] (m? d)) exception-list)) found))
   :vanished (set (keep (fn [[id _ _ m?]] (when-not (some m? found) id)) exception-list))
   :by-class (into (sorted-map) (for [[id _ _ m?] exception-list] [id (count (filter m? found))]))})

(deftest round-trip-semantics
  (let [b (base "rs")
        init {:step 0 :trail [] :totals {:sum 0}}
        divs (atom #{})]
    (ok! (node! "join-reset" b (pr-str init)))
    (reduce (fn [expected [k tag]]
              (testing (str "turn " k ": " tag)
                (let [before (jvm-sem-read b)
                      [seen-rt seen-sem]
                      (case tag
                        "jvm" (let [v (jvm-rt-read b)]
                                (jvm-swap! b sc/rt-step "jvm")
                                (with-jvm-atom b #(sc/sem-write! % "jvm"))
                                [v before])
                        "node" (two-edn (ok! (node! "coop-sem-step" b "node")))
                        "bb" (two-edn (ok! (bb! "sem-step" b "bb")))
                        "wasm" (two-edn (ok! (wasm! "sem-step" b "wasm"))))
                      after (sc/rt-step expected tag)]
                  (is (= expected seen-rt) (str tag " read the round-trip value the previous turn left"))
                  (is (= after (jvm-rt-read b)) (str "after " tag "'s turn the JVM reads exactly the round-trip step"))
                  (swap! divs into (reader-divergences tag before seen-sem))
                  (swap! divs into (writer-divergences (jvm-sem-read b) tag))
                  after)))
            init (map-indexed (fn [i t] [(inc i) t]) order))
    (testing "the last word: Node and wasm read every writer's turn"
      (let [r (jvm-sem-read b)]
        (swap! divs into (reader-divergences "node" r (second (two-edn (ok! (node! "coop-sem-read" b))))))
        (swap! divs into (reader-divergences "wasm" r (second (two-edn (ok! (wasm! "sem-read" b))))))))
    (let [found @divs
          {:keys [unnamed vanished by-class]} (classify found)]
      (spit (str log-dir "/semantics-divergences.edn")
            (with-out-str (doseq [d (sort-by pr-str found)] (prn d))))
      (println "SEMANTICS-DIVERGENCES" (count found) "by class" (pr-str by-class) "unnamed" (count unnamed)
               "vanished" (pr-str vanished))
      (is (= #{} unnamed) (str "divergences no exception-list class names (" log-dir "/semantics-divergences.edn)"))
      (is (= #{} vanished) "exception-list classes that no longer occur — fixed: move the list and the report with it"))))

;; ── the capstone: eve data structures on eve data structures, every eve host at once (coop/mix.cljc) ──────────────
;; One atom holds RRB vectors (eve.rrb-vector: an eve-deftype over eve arrays) of eve maps, a shared RRB vector turned
;; by catvec/subvec, a sorted set (eve.deftype.rb-tree: an eve-deftype red-black tree) and nested maps; every op is
;; one swap! that changes several of them. Writers: JVM threads in this process, Node (compiled ClojureScript), nbb,
;; bb, ClojureCLR, basilisp, jolt and clojerl processes, all at once. Each host then reads the final value and computes the snapshot itself: every
;; snapshot must be the one mix/expected derives from the op counts. The types those structures are made of are
;; read by every host from their type ids, which come from the types' names (eve FORMAT-DECISIONS.md §7).

(defn- mix-contend!
  "Runs the capstone's writers at once — jvm-threads threads plus [kind writer-id] processes — each n ops; the
   per-writer op counts."
  [b jvm-threads procs n]
  (let [futs (doall (concat
                     (for [t (range jvm-threads)]
                       (future (let [w (str "jvm" t)
                                     d (atom/persistent-atom-domain b)
                                     a (atom/persistent-atom {:id :eve/main :persistent b} nil)]
                                 (dotimes [i n] (swap! a mix/mix-op w i))
                                 (atom/close-atom-domain! d)
                                 {:exit 0 :out "" :err "" :cmd ["jvm" w]})))
                     (for [[kind w] procs]
                       (future (case kind
                                 :node (node! "coop-mix-contend" b w (str n))
                                 :nbb (nbb! "mix-contend" b w (str n))
                                 :bb (bb! "mix-contend" b w (str n))
                                 :cljr (cljr! "mix-contend" b w (str n))
                                 :lpy (lpy! "mix-contend" b w (str n))
                                 :jolt (jolt! "mix-contend" b w (str n))
                                 :clje (clje! "mix-contend" b w (str n)))))))]
    (doseq [f futs] (ok! @f))
    (merge (into {} (for [t (range jvm-threads)] [(str "jvm" t) n]))
           (into {} (for [[_ w] procs] [w n])))))

(defn- check-mix
  "Every host's snapshot of the final value: each the expected one."
  [b per-writer]
  (let [expected (mix/expected per-writer)
        snaps {"jvm" (with-jvm-atom b #(mix/snapshot @%))
               "node" (first-edn (ok! (node! "coop-mix-snapshot" b)))
               "nbb" (first-edn (ok! (nbb! "mix-snapshot" b)))
               "bb" (first-edn (ok! (bb! "mix-snapshot" b)))
               "cljr" (first-edn (ok! (cljr! "mix-snapshot" b)))
               "lpy" (first-edn (ok! (lpy! "mix-snapshot" b)))
               "jolt" (first-edn (ok! (jolt! "mix-snapshot" b)))
               "clje" (first-edn (ok! (clje! "mix-snapshot" b)))}]
    (doseq [[host snap] snaps]
      (is (= [] (mix/violations snap per-writer))
          (str host " read a value that breaks the invariants: " (pr-str (mix/violations snap per-writer)))))
    (is (apply = (map #(dissoc % :ledger) (vals snaps))) "every host read the same final value")
    (is (apply = (map :ledger (vals snaps))) "every host read the same ledger")
    (is (= (:counter expected) (:counter (get snaps "jvm"))))
    snaps))

(defn- mix-start!
  "A fresh atom holding mix/initial — built inside swap!, so every structure is in the atom's domain."
  [b]
  (jvm-swap! b (fn [_] (mix/initial))))

(deftest capstone-every-host-alone
  (doseq [[tag threads procs] [["mj" 2 []]
                               ["mn" 0 [[:node "node0"] [:node "node1"]]]
                               ["mnb" 0 [[:nbb "nbb0"] [:nbb "nbb1"]]]
                               ["mb" 0 [[:bb "bb0"] [:bb "bb1"]]]
                               ["mr" 0 [[:cljr "cljr0"] [:cljr "cljr1"]]]
                               ["ml" 0 [[:lpy "lpy0"] [:lpy "lpy1"]]]
                               ["mo" 0 [[:jolt "jolt0"] [:jolt "jolt1"]]]
                               ["me" 0 [[:clje "clje0"] [:clje "clje1"]]]]]
    (testing (str "two writers of one host (" tag "), every host reading")
      (let [b (base tag)]
        (mix-start! b)
        (check-mix b (mix-contend! b threads procs scale))))))

(deftest capstone-every-host-at-once
  (testing (str "2 JVM threads + 2 Node + 2 nbb + 2 bb + 2 ClojureCLR + 2 basilisp + 2 jolt + 2 clojerl processes "
                "on one atom of mixed eve data structures")
    (let [b (base "mx")]
      (mix-start! b)
      (check-mix b (mix-contend! b 2 [[:node "node0"] [:node "node1"] [:nbb "nbb0"] [:nbb "nbb1"]
                                      [:bb "bb0"] [:bb "bb1"] [:cljr "cljr0"] [:cljr "cljr1"]
                                      [:lpy "lpy0"] [:lpy "lpy1"] [:jolt "jolt0"] [:jolt "jolt1"]
                                      [:clje "clje0"] [:clje "clje1"]] scale)))))

;; Run by name only: COOP_MIX_JVM · COOP_MIX_NODE · COOP_MIX_NBB · COOP_MIX_BB · COOP_MIX_CLJR · COOP_MIX_LPY ·
;; COOP_MIX_JOLT · COOP_MIX_CLJE (writers of each, default 3) · COOP_SCALE (ops per writer)
(deftest ^:soak capstone-soak
  (let [[j nn nb bb nr nl no ne] (map #(parse-long (env % "3"))
                                      ["COOP_MIX_JVM" "COOP_MIX_NODE" "COOP_MIX_NBB" "COOP_MIX_BB" "COOP_MIX_CLJR"
                                       "COOP_MIX_LPY" "COOP_MIX_JOLT" "COOP_MIX_CLJE"])]
    (testing (str j " JVM threads + " nn " Node + " nb " nbb + " bb " bb + " nr " ClojureCLR + " nl " basilisp + " no
                  " jolt + " ne " clojerl processes × " scale " mixed ops on one atom")
      (let [b (base "mxs")]
        (mix-start! b)
        (check-mix b (mix-contend! b j (concat (for [i (range nn)] [:node (str "node" i)])
                                               (for [i (range nb)] [:nbb (str "nbb" i)])
                                               (for [i (range bb)] [:bb (str "bb" i)])
                                               (for [i (range nr)] [:cljr (str "cljr" i)])
                                               (for [i (range nl)] [:lpy (str "lpy" i)])
                                               (for [i (range no)] [:jolt (str "jolt" i)])
                                               (for [i (range ne)] [:clje (str "clje" i)]))
                                   scale))))))
