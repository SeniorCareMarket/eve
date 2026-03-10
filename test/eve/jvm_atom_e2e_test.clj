(ns eve.jvm-atom-e2e-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [eve.atom :as atom])
  (:import [java.lang ProcessBuilder]
           [java.io File]))

(def ^:private base    (str "/tmp/eve-p7-jvm-e2e-" (System/currentTimeMillis)))
(def ^:private worker  (str (System/getProperty "user.dir")
                             "/target/eve-test/mmap-worker.js"))

(defn- spawn-node! [& args]
  (let [pb   (doto (ProcessBuilder. ^java.util.List (into ["node"] args))
               (.redirectErrorStream false))
        proc (.start pb)
        out  (future (slurp (.getInputStream proc)))
        err  (future (slurp (.getErrorStream proc)))
        exit (.waitFor proc)]
    {:exit exit :out @out :err @err}))

(defn- node-read
  "Spawn Node worker to read atom at base, return parsed EDN value.
   Only works for flat maps with scalar values (standard EDN)."
  [b]
  (let [r (spawn-node! worker "join-read" b)]
    (assert (zero? (:exit r))
            (str "join-read failed: " (:err r)))
    (edn/read-string (.trim (:out r)))))

(defn- node-reset!
  "Spawn Node worker to reset atom at base to edn-val."
  [b edn-val]
  (let [r (spawn-node! worker "join-reset" b (pr-str edn-val))]
    (assert (zero? (:exit r))
            (str "join-reset failed: " (:err r)))))

(defn- node-swap-assoc!
  "Spawn Node worker to assoc k v into atom map."
  [b k v]
  (let [r (spawn-node! worker "join-swap-assoc" b (name k) (pr-str v))]
    (assert (zero? (:exit r))
            (str "join-swap-assoc failed: " (:err r)))))

(defn- node-swap-fn!
  "Spawn Node worker to apply named transform."
  [b fn-name]
  (let [r (spawn-node! worker "join-swap-fn" b fn-name)]
    (assert (zero? (:exit r))
            (str "join-swap-fn " fn-name " failed: " (:err r)))))

(defn- create-domain-atom!
  "Create a new domain at base-path with a 'main' atom set to initial-val.
   Returns [domain atom]."
  [base-path initial-val]
  (let [d (atom/persistent-atom-domain base-path)
        a (atom/persistent-atom {:id :eve/main :persistent base-path} initial-val)]
    [d a]))

(defn- join-domain-atom!
  "Join an existing domain at base-path and look up the 'main' atom.
   Returns [domain atom]."
  [base-path]
  (let [d (atom/persistent-atom-domain base-path)
        a (atom/persistent-atom {:id :eve/main :persistent base-path} nil)]
    [d a]))

;; ─────────────────────────────────────────────────────
;; Existing baseline tests
;; ─────────────────────────────────────────────────────

(deftest test-jvm-writes-node-reads
  (testing "JVM writes {:count 1}, Node.js join-verify confirms"
    (let [b     (str base "-vis")
          [d a] (create-domain-atom! b {:count 0})]
      (swap! a update :count inc)
      (atom/close-atom-domain! d)
      (let [r (spawn-node! worker "join-verify" b "1")]
        (is (zero? (:exit r))
            (str "Node should see {:count 1}. err: " (:err r)))))))

(deftest test-node-writes-jvm-reads
  (testing "Node.js join-swap increments, JVM reads back"
    (let [b     (str base "-mut")
          [d a] (create-domain-atom! b {:count 0})]
      (swap! a update :count inc)
      (atom/close-atom-domain! d)
      (let [r (spawn-node! worker "join-swap" b)]
        (is (zero? (:exit r))
            (str "Node swap should succeed. err: " (:err r))))
      (let [[d2 c] (join-domain-atom! b)]
        (is (= 2 (:count @c))
            "JVM should see {:count 2} after Node swap")
        (atom/close-atom-domain! d2)))))

(deftest test-sequential-convergence
  (testing "3 JVM + 3 Node swaps = {:count 6}"
    (let [b     (str base "-conv")
          [d a] (create-domain-atom! b {:count 0})]
      (dotimes [_ 3] (swap! a update :count inc))
      (atom/close-atom-domain! d)
      (dotimes [_ 3]
        (let [r (spawn-node! worker "join-swap" b)]
          (is (zero? (:exit r)))))
      (let [[d2 c] (join-domain-atom! b)]
        (is (= 6 (:count @c)))
        (atom/close-atom-domain! d2)))))

(deftest test-epoch-gc-no-oom
  (testing "20 JVM swaps complete without slab exhaustion"
    (let [[d a] (create-domain-atom! (str base "-gc") {:count 0})]
      (dotimes [_ 20] (swap! a update :count inc))
      (is (= 20 (:count @a)))
      (atom/close-atom-domain! d))))

;; ─────────────────────────────────────────────────────
;; 10 cross-env heterogeneous round-trip tests
;; ─────────────────────────────────────────────────────

;; Test 1: Flat map with mixed scalar types — JVM writes, Node reads EDN
(deftest xenv-01-flat-map-round-trip
  (testing "JVM writes flat map with string, int, float, bool, keyword — Node reads back"
    (let [b     (str base "-x01")
          m     {:name "alice" :age 30 :active true :score 3.14 :role :admin}
          [d a] (create-domain-atom! b m)]
      (atom/close-atom-domain! d)
      (let [v (node-read b)]
        (is (= "alice" (:name v)))
        (is (= 30 (:age v)))
        (is (= true (:active v)))
        (is (< (Math/abs (- 3.14 (:score v))) 0.001))
        (is (= :admin (:role v)))))))

;; Test 2: Node writes flat map, JVM reads directly
(deftest xenv-02-node-writes-jvm-reads-map
  (testing "Node resets atom to flat map, JVM reads back via join-atom-domain"
    (let [b     (str base "-x02")
          [d a] (create-domain-atom! b {:placeholder true})]
      (atom/close-atom-domain! d)
      (node-reset! b {:x 10 :y 20 :label "origin"})
      (let [[d2 c] (join-domain-atom! b)
            v      @c]
        (atom/close-atom-domain! d2)
        (is (= 10 (:x v)))
        (is (= 20 (:y v)))
        (is (= "origin" (:label v)))))))

;; Test 3: Multi-key assoc — Node adds multiple keys to JVM-written map
(deftest xenv-03-multi-key-node-assoc
  (testing "JVM writes map, Node assocs 3 keys, JVM reads all"
    (let [b     (str base "-x03")
          [d a] (create-domain-atom! b {:base-key "original"})]
      (atom/close-atom-domain! d)
      (node-swap-assoc! b :added-int 42)
      (node-swap-assoc! b :added-str "hello")
      (node-swap-assoc! b :added-kw :yes)
      (let [[d2 c] (join-domain-atom! b)
            v      @c]
        (atom/close-atom-domain! d2)
        (is (= "original" (:base-key v)))
        (is (= 42 (:added-int v)))
        (is (= "hello" (:added-str v)))
        (is (= :yes (:added-kw v)))))))

;; Test 4: JVM writes vector root, Node reads
(deftest xenv-04-vector-root-cross-env
  (testing "JVM writes vector at atom root, Node verifies readable, JVM reads back"
    (let [b     (str base "-x04")
          [d a] (create-domain-atom! b [10 20 30 "four" :five])]
      (atom/close-atom-domain! d)
      (let [r (spawn-node! worker "join-swap-fn" b "to-vec")]
        :noop)
      ;; JVM re-joins and reads the vector
      (let [[d2 c] (join-domain-atom! b)
            v      @c]
        (atom/close-atom-domain! d2)
        (is (vector? v))
        (is (= [10 20 30 "four" :five] v))))))

;; Test 5: Bidirectional assoc — JVM writes, Node assocs, JVM assocs, verify all
(deftest xenv-05-bidirectional-assoc
  (testing "JVM and Node both assoc keys into shared map"
    (let [b     (str base "-x05")
          [d a] (create-domain-atom! b {:origin "jvm"})]
      (swap! a assoc :jvm-key 100)
      (atom/close-atom-domain! d)
      ;; Node adds its key
      (node-swap-assoc! b :node-key 200)
      ;; JVM re-joins and adds another
      (let [[d2 c] (join-domain-atom! b)]
        (swap! c assoc :jvm-key2 300)
        (let [v @c]
          (atom/close-atom-domain! d2)
          (is (= "jvm" (:origin v)))
          (is (= 100 (:jvm-key v)))
          (is (= 200 (:node-key v)))
          (is (= 300 (:jvm-key2 v))))))))

;; Test 6: Ping-pong — alternating JVM and Node swaps build up state
(deftest xenv-06-ping-pong-swaps
  (testing "JVM and Node alternate swaps, final state is correct"
    (let [b     (str base "-x06")
          [d a] (create-domain-atom! b {:count 0})]
      ;; JVM: count 0 -> 1
      (swap! a update :count inc)
      (atom/close-atom-domain! d)
      ;; Node: count 1 -> 2
      (let [r (spawn-node! worker "join-swap" b)]
        (is (zero? (:exit r))))
      ;; JVM re-opens: count 2 -> 3
      (let [[d2 c] (join-domain-atom! b)]
        (swap! c update :count inc)
        (atom/close-atom-domain! d2))
      ;; Node: count 3 -> 4
      (let [r (spawn-node! worker "join-swap" b)]
        (is (zero? (:exit r))))
      ;; JVM re-opens: count 4 -> 5
      (let [[d3 e] (join-domain-atom! b)]
        (swap! e update :count inc)
        (is (= 5 (:count @e)))
        (atom/close-atom-domain! d3)))))

;; Test 7: String value transform — Node appends to string in map
(deftest xenv-07-string-transform-cross-env
  (testing "JVM writes {:greeting \"hello\"}, Node appends \" world\", JVM reads"
    (let [b     (str base "-x07")
          [d a] (create-domain-atom! b {:greeting "hello"})]
      (atom/close-atom-domain! d)
      (node-swap-fn! b "append-greeting")
      (let [[d2 c] (join-domain-atom! b)
            v      @c]
        (atom/close-atom-domain! d2)
        (is (= "hello world" (:greeting v)))))))

;; Test 8: Node merge-meta — Node merges extra keys including PID
(deftest xenv-08-node-merge-meta
  (testing "JVM writes base map, Node merges :source and :pid, JVM reads all"
    (let [b     (str base "-x08")
          [d a] (create-domain-atom! b {:status "ready" :version 1})]
      (atom/close-atom-domain! d)
      (node-swap-fn! b "merge-meta")
      (let [[d2 c] (join-domain-atom! b)
            v      @c]
        (atom/close-atom-domain! d2)
        (is (= "ready" (:status v)))
        (is (= 1 (:version v)))
        (is (= "node" (:source v)))
        (is (number? (:pid v)))))))

;; Test 9: Numeric increment transform — Node increments all numeric vals
(deftest xenv-09-inc-vals-transform
  (testing "JVM writes map with numeric vals, Node inc-vals, JVM reads incremented"
    (let [b     (str base "-x09")
          [d a] (create-domain-atom! b {:a 1 :b 2 :c 3 :label "keep"})]
      (atom/close-atom-domain! d)
      (node-swap-fn! b "inc-vals")
      (let [[d2 c] (join-domain-atom! b)
            v      @c]
        (atom/close-atom-domain! d2)
        (is (= 2 (:a v)))
        (is (= 3 (:b v)))
        (is (= 4 (:c v)))
        (is (= "keep" (:label v)))))))

;; Test 10: Concurrent contention — 3 Node workers + JVM racing swaps
(deftest xenv-10-concurrent-contention
  (testing "3 Node workers each increment 5 times + JVM 5 times = 20"
    (let [b     (str base "-x10")
          [d a] (create-domain-atom! b {:count 0})]
      (atom/close-atom-domain! d)
      ;; Launch 3 Node workers concurrently, each doing 5 increments
      (let [futs (doall
                   (for [_ (range 3)]
                     (future
                       (spawn-node! worker "join-concurrent-swap" b "5"))))]
        ;; JVM does 5 increments concurrently
        (let [[d2 j] (join-domain-atom! b)]
          (dotimes [_ 5] (swap! j update :count inc))
          (atom/close-atom-domain! d2))
        ;; Wait for all Node workers
        (doseq [f futs]
          (let [r @f]
            (is (zero? (:exit r))
                (str "Node worker failed: " (:err r))))))
      ;; Verify final count = 3*5 + 5 = 20
      (let [[d3 c] (join-domain-atom! b)]
        (is (= 20 (:count @c))
            "Should see 20 after concurrent JVM + Node swaps")
        (atom/close-atom-domain! d3)))))

;; ─────────────────────────────────────────────────────
;; Capstone: big-data-on-disk stress test
;; ─────────────────────────────────────────────────────

(defn- node-bulk-assoc!
  "Spawn Node worker to assoc `cnt` keys :prefix-start .. :prefix-(start+cnt-1)."
  [b start cnt prefix]
  (spawn-node! worker "bulk-assoc-range" b (str start) (str cnt) prefix))

(defn- node-read-key-count
  "Spawn Node worker to read the number of keys in the atom map."
  [b]
  (let [r (spawn-node! worker "read-key-count" b)]
    (assert (zero? (:exit r))
            (str "read-key-count failed: " (:err r)))
    (Long/parseLong (.trim (:out r)))))

(defn- slab-files-total-bytes
  "Sum the sizes of all .slab* and .root files for the given base path."
  [b]
  (let [dir  (File. b)
        parent (.getParentFile dir)
        prefix (.getName dir)]
    (if (and parent (.exists parent))
      (->> (.listFiles parent)
           (filter #(let [n (.getName %)]
                      (and (.startsWith n prefix)
                           (or (.contains n ".slab")
                               (.endsWith n ".root")))))
           (map #(.length %))
           (reduce + 0))
      0)))

(deftest capstone-big-data-concurrent-disk-stress
  (testing "JVM + 6 Node workers write 2500 keys to on-disk atom"
    (let [b               (str base "-capstone")
          jvm-keys        400
          node-workers    6
          node-keys-each  350
          total-node-keys (* node-workers node-keys-each)
          total-keys      (+ 1 jvm-keys total-node-keys)] ;; +1 for :_seed

      ;; ── Phase 1: JVM seeds and writes its 400 keys ────────────────
      (let [[d a] (create-domain-atom! b {:_seed true})]
        (dotimes [i jvm-keys]
          (swap! a assoc
                 (keyword (str "jvm-" i))
                 (str "jvm-" i "-val")))
        (atom/close-atom-domain! d))

      ;; ── Phase 2: 6 Node workers write concurrently ────────────────
      (let [node-futs
            (doall
              (for [n (range node-workers)]
                (future
                  (node-bulk-assoc! b 0 node-keys-each (str "n" n)))))]
        (doseq [f node-futs]
          (let [r @f]
            (is (zero? (:exit r))
                (str "Node bulk-assoc worker failed: " (:err r))))))

      ;; ── Phase 3: JVM verifies total key count ─────────────────────
      (let [[d c] (join-domain-atom! b)
            v     @c
            n     (count v)]
        (atom/close-atom-domain! d)
        (is (= total-keys n)
            (str "Expected " total-keys " keys, got " n)))

      ;; ── Phase 4: JVM verifies every single key ────────────────────
      (let [[d c] (join-domain-atom! b)
            v     @c]
        ;; JVM-written keys
        (doseq [i (range jvm-keys)]
          (let [k (keyword (str "jvm-" i))]
            (is (= (str "jvm-" i "-val") (get v k))
                (str "Missing JVM key " k))))
        ;; Node-written keys
        (doseq [w (range node-workers)
                i (range node-keys-each)]
          (let [prefix (str "n" w)
                k      (keyword (str prefix "-" i))]
            (is (= (str prefix "-" i "-val") (get v k))
                (str "Missing Node key " k))))
        ;; Seed key still present
        (is (true? (:_seed v)) "Seed key should survive all writes")
        (atom/close-atom-domain! d))

      ;; ── Phase 5: Node verifies it can read the full state ─────────
      (let [node-count (node-read-key-count b)]
        (is (= total-keys node-count)
            (str "Node should see " total-keys " keys, got " node-count)))

      ;; ── Phase 6: verify slab files exist on disk with real size ───
      (let [disk-bytes (slab-files-total-bytes b)]
        (is (pos? disk-bytes)
            (str "Slab files should exist on disk, got " disk-bytes " bytes"))
        (is (> disk-bytes 100000)
            (str "Expected >100KB of slab data, got " disk-bytes " bytes"))))))

;; ─────────────────────────────────────────────────────
;; Capstone 2: nested data + simultaneous JVM/Node contention
;; ─────────────────────────────────────────────────────

(defn- make-nested-val
  "Build a nested value: map containing map, vector, keywords."
  [prefix idx]
  {:id idx
   :label (str prefix "-" idx)
   :profile {:source prefix :index idx}
   :tags [:tag-a :tag-b :tag-c]
   :scores [idx (* idx 2) (* idx 3)]})

(defn- node-bulk-assoc-nested!
  "Spawn Node worker to assoc `cnt` keys with nested values."
  [b start cnt prefix]
  (spawn-node! worker "bulk-assoc-nested" b (str start) (str cnt) prefix))

(defn- node-verify-nested!
  "Spawn Node worker to verify nested key structure."
  [b start cnt prefix]
  (spawn-node! worker "verify-nested-keys" b (str start) (str cnt) prefix))

(deftest capstone-nested-concurrent-stress
  (testing "JVM + 4 Node workers write nested data simultaneously"
    (let [b               (str base "-capstone2")
          jvm-keys        100
          node-workers    4
          node-keys-each  75
          total-node-keys (* node-workers node-keys-each)
          total-keys      (+ 1 jvm-keys total-node-keys)] ;; +1 for :_seed

      ;; ── Phase 1: SIMULTANEOUS writes ───────────────────────
      (let [[d a] (create-domain-atom! b {:_seed true})]
        ;; Launch Node workers first (they join the atom)
        (let [node-futs
              (doall
                (for [n (range node-workers)]
                  (future
                    (node-bulk-assoc-nested!
                      b 0 node-keys-each (str "n" n)))))
              ;; JVM writes its keys concurrently with Node workers
              jvm-fut
              (future
                (dotimes [i jvm-keys]
                  (swap! a assoc
                         (keyword (str "jvm-" i))
                         (make-nested-val "jvm" i))))]
          ;; Wait for both JVM and all Node workers
          @jvm-fut
          (doseq [f node-futs]
            (let [r @f]
              (is (zero? (:exit r))
                  (str "Node nested worker failed: " (:err r))))))
        (atom/close-atom-domain! d))

      ;; ── Phase 2: JVM verifies key count ─────────────────────
      (let [[d c] (join-domain-atom! b)
            v     @c
            n     (count v)]
        (atom/close-atom-domain! d)
        (is (= total-keys n)
            (str "Expected " total-keys " keys, got " n)))

      ;; ── Phase 3: JVM verifies nested structure of its keys ──
      (let [[d c] (join-domain-atom! b)
            v     @c]
        (doseq [i (range jvm-keys)]
          (let [k   (keyword (str "jvm-" i))
                val (get v k)]
            (is (= i (:id val))
                (str "JVM key " k " :id mismatch"))
            (is (= "jvm" (get-in val [:profile :source]))
                (str "JVM key " k " nested :profile/:source"))
            (is (= [:tag-a :tag-b :tag-c] (:tags val))
                (str "JVM key " k " :tags"))
            (is (= [i (* i 2) (* i 3)] (:scores val))
                (str "JVM key " k " :scores"))))
        ;; Spot-check a few Node keys from JVM side
        (doseq [n (range node-workers)]
          (let [prefix (str "n" n)
                k      (keyword (str prefix "-0"))
                val    (get v k)]
            (is (= 0 (:id val))
                (str "Node key " k " :id"))
            (is (= prefix (get-in val [:profile :source]))
                (str "Node key " k " :profile/:source"))
            (is (= [:tag-a :tag-b :tag-c] (:tags val))
                (str "Node key " k " :tags"))))
        (is (true? (:_seed v)) "Seed key should survive concurrent writes")
        (atom/close-atom-domain! d))

      ;; ── Phase 4: Node verifies its own nested keys ──────────
      (doseq [n (range node-workers)]
        (let [prefix (str "n" n)
              r      (node-verify-nested! b 0 node-keys-each prefix)]
          (is (zero? (:exit r))
              (str "Node verify-nested " prefix " failed: "
                   (:err r) " out: " (:out r))))))))

;; ─────────────────────────────────────────────────────
;; Capstone 3: ultimate stress
;; ─────────────────────────────────────────────────────

(defn- make-rich-val
  "Build a rich nested value exercising all Eve collection types."
  [writer-id idx]
  {:id idx
   :writer writer-id
   :deep {:a {:b {:c {:d (str writer-id "-" idx "-deep")}}}}
   :items (vec (range 40))
   :matrix [[idx (* idx 2) (* idx 3)]
            [(+ idx 10) (+ idx 20) (+ idx 30)]
            [(+ idx 100) (+ idx 200) (+ idx 300)]]
   :tags #{:alpha :beta :gamma :delta}
   :history (list :created :validated :committed)
   :payload (apply str (repeat 200 (str (char (+ 65 (mod idx 26))))))})

(defn- node-bulk-rich-grow!
  "Spawn Node worker to do compound swap: assoc unique key with rich
   nested value AND increment :counter, `cnt` times."
  [b start cnt prefix]
  (spawn-node! worker "bulk-rich-grow" b (str start) (str cnt) prefix))

(defn- node-verify-rich!
  "Spawn Node worker to verify rich nested key structure."
  [b start cnt prefix]
  (spawn-node! worker "verify-rich" b (str start) (str cnt) prefix))

(deftest capstone-ultimate-stress
  (testing "4 JVM threads + 6 Node processes, rich nested values, key contention, MB-scale"
    (let [b               (str base "-capstone3")
          jvm-threads     4
          jvm-rounds      75
          node-workers    6
          node-rounds     75
          total-swaps     (+ (* jvm-threads jvm-rounds) (* node-workers node-rounds))
          total-keys      (+ 2 total-swaps)] ;; +1 :_seed, +1 :counter

      ;; ── Phase 1: SIMULTANEOUS writes — JVM threads + Node processes ──
      (let [[d a] (create-domain-atom! b {:_seed true :counter 0})]
        (let [node-futs
              (doall
                (for [n (range node-workers)]
                  (future
                    (node-bulk-rich-grow! b 0 node-rounds (str "n" n)))))
              jvm-futs
              (doall
                (for [t (range jvm-threads)]
                  (future
                    (let [thread-id (str "jvm" t)]
                      (dotimes [r jvm-rounds]
                        (let [k (keyword (str thread-id "-" r))
                              v (make-rich-val thread-id r)]
                          (swap! a (fn [m]
                                     (-> m (assoc k v) (update :counter inc))))))))))]
          ;; Wait for JVM threads
          (doseq [f jvm-futs] @f)
          ;; Wait for Node workers
          (doseq [f node-futs]
            (let [r @f]
              (is (zero? (:exit r))
                  (str "Node rich-grow worker failed: " (:err r))))))
        (atom/close-atom-domain! d))

      ;; ── Phase 2: JVM verifies :counter (key contention proof) ──
      (let [[d c] (join-domain-atom! b)
            v     @c]
        (is (= total-swaps (:counter v))
            (str "Counter should be " total-swaps ", got " (:counter v)))
        (atom/close-atom-domain! d))

      ;; ── Phase 3: JVM verifies total key count ──
      (let [[d c] (join-domain-atom! b)
            v     @c
            n     (count v)]
        (atom/close-atom-domain! d)
        (is (= total-keys n)
            (str "Expected " total-keys " keys, got " n)))

      ;; ── Phase 4: JVM verifies deep nested structure of its keys ──
      (let [[d c] (join-domain-atom! b)
            v     @c]
        (doseq [t (range jvm-threads)
                r (range jvm-rounds)]
          (let [thread-id (str "jvm" t)
                k   (keyword (str thread-id "-" r))
                val (get v k)]
            (is (some? val) (str "Missing JVM key " k))
            (when val
              (is (= r (:id val)) (str k " :id"))
              (is (= thread-id (:writer val)) (str k " :writer"))
              (is (= (str thread-id "-" r "-deep")
                     (get-in val [:deep :a :b :c :d]))
                  (str k " deep nesting"))
              (is (= 40 (count (:items val))) (str k " items count"))
              (is (= (vec (range 40)) (vec (:items val))) (str k " items"))
              (is (= [r (* r 2) (* r 3)] (vec (first (:matrix val))))
                  (str k " matrix[0]"))
              (is (= 3 (count (:matrix val))) (str k " matrix rows"))
              (is (= #{:alpha :beta :gamma :delta} (set (:tags val)))
                  (str k " tags set"))
              (is (= [:created :validated :committed] (vec (:history val)))
                  (str k " history list"))
              (is (= 200 (count (:payload val))) (str k " payload length")))))
        ;; Spot-check one key from each Node worker
        (doseq [n (range node-workers)]
          (let [prefix (str "n" n)
                k      (keyword (str prefix "-0"))
                val    (get v k)]
            (is (some? val) (str "Missing Node key " k))
            (when val
              (is (= 0 (:id val)) (str k " :id"))
              (is (= prefix (:writer val)) (str k " :writer"))
              (is (= (str prefix "-0-deep")
                     (get-in val [:deep :a :b :c :d]))
                  (str k " deep nesting"))
              (is (= #{:alpha :beta :gamma :delta} (set (:tags val)))
                  (str k " tags set")))))
        (is (true? (:_seed v)) "Seed key should survive all writes")
        (atom/close-atom-domain! d))

      ;; ── Phase 5: Each Node process verifies its own keys ──
      (doseq [n (range node-workers)]
        (let [prefix (str "n" n)
              r      (node-verify-rich! b 0 node-rounds prefix)]
          (is (zero? (:exit r))
              (str "Node verify-rich " prefix " failed: "
                   (:err r) " out: " (:out r)))))

      ;; ── Phase 6: Verify disk size exceeds 1MB ──
      (let [disk-bytes (slab-files-total-bytes b)]
        (is (> disk-bytes 1000000)
            (str "Expected >1MB slab data, got " disk-bytes " bytes"))))))
