(ns coop.scenario
  "The cooperative disk-atom scenarios' transitions, one source for the JVM orchestrator (orchestrator.clj), the
   bb worker (bb_worker.clj) and eve's Node worker (eve/test/eve/runner/mmap_worker_main.cljs, modes coop-*). The wasm
   participant implements the same transitions in ValueScript (workers/coop_worker.vs) — independently, so every
   handoff the orchestrator checks is two implementations agreeing on the bytes in the shared files."
  (:require [clojure.string :as str]))

(defn rt-step
  "The round-trip step a participant tagged `tag` takes."
  [v tag]
  (let [step (inc (or (:step v) 0))]
    (-> v
        (update :step (fnil inc 0))
        (update :trail (fnil conj []) tag)
        (assoc-in [:by tag] {:step step
                             :tags #{(keyword tag) :eve/coop}
                             :seq (list step (* 2 step) (* 3 step))
                             :text (str tag " héllo wörld ✓ " step)
                             :pi 3.25
                             :neg -7
                             :big 4294967296123
                             :sym (symbol tag)})
        (update-in [:totals :sum] (fnil + 0) 10))))

(def initial-contention
  "The contention atom's start: ten accounts of 1000, an empty pool, zero checksums."
  {:counter 0
   :accounts (into {} (map (fn [i] [(str "a" i) {:balance 1000}]) (range 10)))
   :checksums {:adds 0 :removes 0 :transfers 0 :transforms 0}
   :pool {:balance 0}
   :log []})

(defn- acct [w i] (str w "-" i))

(defn contend-op
  "Contention op i of writer w: :counter +1, then by i mod 4 — add w's account i (balance 0), remove w's account
   i-1, transfer 1 between two of the ten base accounts (the source's balance permitting), or grow the pool — and
   the last eight [w i] in :log."
  [m w i]
  (let [m (update m :counter inc)
        m (case (int (mod i 4))
            0 (-> m (assoc-in [:accounts (acct w i)] {:balance 0})
                  (update-in [:checksums :adds] inc))
            1 (-> m (update :accounts dissoc (acct w (dec i)))
                  (update-in [:checksums :removes] inc))
            2 (let [fa (str "a" (mod (* 7 i) 10))
                    ta (str "a" (mod (inc (* 7 i)) 10))
                    amt (min 1 (or (get-in m [:accounts fa :balance]) 0))]
                (-> m (update-in [:accounts fa :balance] - amt)
                    (update-in [:accounts ta :balance] + amt)
                    (update-in [:checksums :transfers] inc)))
            3 (-> m (update-in [:pool :balance] inc)
                  (update-in [:checksums :transforms] inc)))]
    (update m :log (fn [l] (vec (take-last 8 (conj (vec (or l [])) [w i])))))))

(defn snapshot
  "The invariants' inputs, from a contention value."
  [v]
  (let [accts (:accounts v)]
    {:counter (:counter v)
     :checksums (into {} (:checksums v))
     :pool (get-in v [:pool :balance])
     :accounts (count accts)
     :balance-sum (reduce + 0 (map :balance (vals accts)))
     :log (count (:log v))}))

(defn violations
  "What a contention snapshot breaks, given the writers' total op count and per-writer op counts {w n}: [] when
   every invariant holds."
  [s total per-writer]
  (let [{:keys [adds removes transfers transforms]} (:checksums s)
        expected-adds (reduce + 0 (map (fn [[_ n]] (quot (+ n 3) 4)) per-writer))
        expected-removes (reduce + 0 (map (fn [[_ n]] (quot (+ n 2) 4)) per-writer))]
    (cond-> []
      (not= total (:counter s)) (conj [:counter (:counter s) :expected total])
      (not= (:counter s) (+ adds removes transfers transforms)) (conj [:checksums-sum (:checksums s) :counter (:counter s)])
      (not= 10000 (:balance-sum s)) (conj [:balance-conservation (:balance-sum s) :expected 10000])
      (not= transforms (:pool s)) (conj [:pool (:pool s) :transforms transforms])
      (not= (+ 10 (- adds removes)) (:accounts s)) (conj [:account-count (:accounts s) :expected (+ 10 (- adds removes))])
      (not= expected-adds adds) (conj [:adds adds :expected expected-adds])
      (not= expected-removes removes) (conj [:removes removes :expected expected-removes])
      (not= (min 8 total) (:log s)) (conj [:log (:log s) :expected (min 8 total)]))))

;; ── round 2: the semantics battery ─────────────────────────────────────────────
;; Every participant writes, under [:sem <its tag>], three layers from its own literals; every other participant
;; reads them back:
;;   sem-shapes  values eve's format has a tag for (scalars 0x01-0x0F, collections 0x10-0x13) — they must arrive
;;               exactly: the same value and the same number kind (a Long stays a Long, a Double a Double)
;;   x-shapes    values it has no tag for (bigint, ratio, BigDecimal, char) or carries only as plainer data (NaN,
;;               sorted collections, metadata): what each writer does with them is the exception list
;;   probes      the pr-str of expressions each runtime evaluates — the numeric tower, chars and strings, sorted
;;               collections, metadata, keywords and symbols. Strings: every runtime carries them; the JVM's are the
;;               reference
;; The wasm participant defines the same three in ValueScript (workers/coop_worker.vs), independently.

(def sem-writers "The semantics turn's tags, in turn order." ["node" "jvm" "bb" "wasm"])

(def sem-shapes
  "The carriage shapes: one key each, so each lands (or is refused) on its own."
  {:i32-min -2147483648
   :i32-max 2147483647
   :i64-past-i32 2147483648
   :i64-below-i32 -2147483649
   :i64-max 9223372036854775807
   :i64-min -9223372036854775808
   :int-safe-max 9007199254740991
   :int-past-safe 9007199254740993
   :dbl-integral 2.0
   :dbl-neg-zero -0.0
   :dbl-inf ##Inf
   :dbl-neg-inf ##-Inf
   :dbl-min 4.9E-324
   :dbl-max 1.7976931348623157E308
   :dbl-frac 0.1
   :str-empty ""
   :str-astral "𝄞😀"
   :str-combining "é"
   :str-nul "a\u0000b"
   :str-long (apply str (repeat 300 "é"))
   :kw-ns :a.b/c
   :kw-long (keyword (apply str (repeat 300 "k")))
   :sym-ns 'a.b/c
   :uuid #uuid "6ba7b810-9dad-11d1-80b4-00c04fd430c8"
   :inst #inst "2026-10-15T12:00:00.000-00:00"
   :bools [true false nil]
   :empty-map {}
   :empty-vec []
   :empty-set #{}
   :empty-list ()
   :nested [{:a [#{1 "x" :k}]} (list 1 [2])]
   :keys-scalar {1 :int "s" :str :k/w :kw 'sy :sym 2.5 :dbl true :bool}
   :keys-vector {[1 2] :v}
   :keys-set {#{1 2 3} :s}
   ;; two keys whose hashes share their low 30 bits (642791526, -430950298): the map's trie reaches shift 30, where
   ;; eve's child index is two bits of the 32-bit pattern on every host (eve FORMAT-DECISIONS.md §6, 2026-10-16)
   :keys-shift30 {371 :A 53312 :B}
   :set-mixed #{1 "1" :one 'one}})

(def x-shapes
  "The exception shapes: no EVE tag (bigint, ratio, BigDecimal, char), or carried as plainer data (NaN, sorted
   collections, metadata). Under [:sem tag :shapes] with the carriage shapes."
  {:x-bigint #?(:cljs :cljs/no-bigint :default 4294967296123N)
   :x-bigint-small #?(:cljs :cljs/no-bigint :default 1N)
   :x-ratio #?(:cljs :cljs/no-ratio :default 1/3)
   :x-bigdec #?(:cljs :cljs/no-bigdec :default 1.5M)
   :x-char \a
   :x-nan ##NaN
   :x-sorted-map (sorted-map-by > 1 :a 3 :c 2 :b)
   :x-sorted-set (sorted-set 3 1 2)
   :x-meta-vec (with-meta [1 2] {:k 1})
   :x-meta-map (with-meta {:a 1} {:k 1})})

(defn probes
  "The semantic probes: {key (pr-str of the expression's value, or \"throws: <message>\")}, as this runtime
   evaluates them."
  []
  (let [p (fn [f] (try (pr-str (f)) (catch #?(:cljs :default :default Exception) e (str "throws: " (ex-message e)))))]
    {:char (p (fn [] \a))
     :char=str (p (fn [] (= \a "a")))
     :char-int (p (fn [] (int \a)))
     :int-char (p (fn [] (char 97)))
     :char-str (p (fn [] (str \a \b)))
     :str-seq (p (fn [] (seq "hé")))
     :count-bmp (p (fn [] (count "héllo")))
     :count-astral (p (fn [] (count "𝄞")))
     :nth-astral (p (fn [] (int (nth "𝄞" 0))))
     :subs (p (fn [] (subs "héllo wörld" 1 4)))
     :upper (p (fn [] (str/upper-case "héllo")))
     :compare-str (p (fn [] (compare "b" "a")))
     :compare-char (p (fn [] (compare \b \a)))
     :bigint-lit (p (fn [] #?(:cljs :cljs/no-bigint :default 4294967296123N)))
     :bigint-add (p (fn [] #?(:cljs :cljs/no-bigint :default (+ 1N 2))))
     :bigint-promote (p (fn [] #?(:cljs :cljs/no-promoting-op :default (*' 4294967296 4294967296))))
     :long-overflow (p (fn [] (* 4294967296 4294967296)))
     :inc-max (p (fn [] (inc 9223372036854775807)))
     :ratio (p (fn [] (/ 1 3)))
     :ratio-add (p (fn [] #?(:cljs :cljs/no-ratio :default (+ 1/3 1/6))))
     :div-exact (p (fn [] (/ 6 3)))
     :div-double (p (fn [] (/ 1.0 4)))
     :bigdec-lit (p (fn [] #?(:cljs :cljs/no-bigdec :default 1.5M)))
     :bigdec-add (p (fn [] #?(:cljs :cljs/no-bigdec :default (+ 1.5M 1))))
     :dbl-add (p (fn [] (+ 0.1 0.2)))
     :dbl-integral (p (fn [] 2.0))
     :=long-double (p (fn [] (= 1 1.0)))
     :==long-double (p (fn [] (== 1 1.0)))
     :=long-bigint (p (fn [] #?(:cljs :cljs/no-bigint :default (= 1 1N))))
     :=ratio-double (p (fn [] #?(:cljs :cljs/no-ratio :default (= 1/2 0.5))))
     :quot-rem-mod (p (fn [] [(quot -7 2) (rem -7 2) (mod -7 2)]))
     :sorted-map-by (p (fn [] (sorted-map-by > 1 :a 3 :c 2 :b)))
     :sorted-set (p (fn [] (sorted-set "b" "a" "c")))
     :sorted? (p (fn [] (sorted? (sorted-set 1))))
     :rseq (p (fn [] (rseq (sorted-map 1 :a 2 :b))))
     :subseq (p (fn [] (subseq (sorted-set 1 2 3 4) > 2)))
     :sorted-set-by (p (fn [] (sorted-set-by (fn [a b] (compare (count a) (count b))) "ccc" "a" "bb")))
     :meta (p (fn [] (meta (with-meta [1 2] {:k 1}))))
     :meta= (p (fn [] (= (with-meta [1] {:k 1}) [1])))
     :vary-meta (p (fn [] (meta (vary-meta {:a 1} assoc :t true))))
     :kw-parts (p (fn [] [(namespace :a.b/c) (name :a.b/c)]))
     :sym-parts (p (fn [] [(namespace 'a.b/c) (name 'a.b/c)]))
     :kw-make (p (fn [] (keyword "x" "y")))
     :sym-make (p (fn [] (symbol "x" "y")))
     :kw=sym (p (fn [] (= :a 'a)))}))

(defn plain
  "A slab-backed value as plain data (what pr-str prints as EDN another runtime reads back)."
  [x]
  (cond (map? x) (into {} (map (fn [[k v]] [(plain k) (plain v)])) x)
        (set? x) (into #{} (map plain) x)
        (vector? x) (mapv plain x)
        (seq? x) (apply list (map plain x))
        :else x))

(defn rt-view
  "The round-trip part of a value, as plain data (the semantics battery shares the atom with it)."
  [v]
  (plain (select-keys v [:step :trail :by :totals])))

(defn- read-path [v path]
  (try (plain (get-in v path))
       (catch #?(:cljs :default :default Exception) e {:coop/error (str (ex-message e))})))

(def lookups
  "The keyed lookups: [shape key] — a get by a key that is no keyword, into a map another runtime wrote. Reading a
   map whole walks its tree; only a lookup proves the runtimes hash the key's EVE bytes alike."
  (concat (for [k (keys (:keys-scalar sem-shapes))] [:keys-scalar k])
          (for [k (keys (:keys-vector sem-shapes))] [:keys-vector k])
          (for [k (keys (:keys-set sem-shapes))] [:keys-set k])
          (for [k (keys (:keys-shift30 sem-shapes))] [:keys-shift30 k])))

(defn sem-read
  "What this runtime reads of every writer's semantics turn so far: {[writer :shapes key] value, [writer :lookup
   shape key] value, [writer :refused] {key message}, [writer :probes] {key string}} — each path read on its own from
   one deref, a read that throws recorded as {:coop/error message}. Writers with no turn yet are absent."
  [a]
  (let [v @a]
    (into {}
          (for [w sem-writers
                :when (some? (read-path v [:sem w :probes]))
                [label path] (concat (for [k (concat (keys sem-shapes) (keys x-shapes))]
                                       [[w :shapes k] [:sem w :shapes k]])
                                     (for [[s k] lookups] [[w :lookup s k] [:sem w :shapes s k]])
                                     [[[w :refused] [:sem w :refused]] [[w :probes] [:sem w :probes]]])]
            [label (read-path v path)]))))

(defn sem-write!
  "This runtime's semantics turn under tag: each shape its own swap! (a refused write recorded under
   [:sem tag :refused key] with its message), then the probes in one swap!."
  [a tag]
  (doseq [[k v] (concat sem-shapes x-shapes)]
    (try (swap! a assoc-in [:sem tag :shapes k] v)
         (catch #?(:cljs :default :default Exception) e
           (swap! a assoc-in [:sem tag :refused k] (str (ex-message e))))))
  (swap! a assoc-in [:sem tag :probes] (probes)))
