(ns coop-worker
  "The wasm participant of the cooperative disk-atom scenarios: a VS program
   run by vs-eve-host (vs/hosts/eve-mmap) that joins eve's mmap atom `eve/main` at a base path and takes its turn —
   the same transitions the JVM, Node and bb participants make (orchestrator.clj holds the reference).

     read     <base>                    the value, printed as EDN
     reset    <base> <edn>              the value replaced by <edn> (a fresh tree)
     rt-step  <base> <tag>              the round-trip step: prints the value read before it, then commits the step
     contend  <base> <writer> <n> [s]   n contention ops (op i by i mod 4, i from s, default 0), each one CAS; prints
                                        {:ops :ms} — the start lets one writer's ops span several processes, each under
                                        the program's allocation budget (VS frees no temporaries; round 2, §6)
     snapshot <base>                    the contention state's invariant summary, as EDN
     sem-step <base> <tag>              round 2's semantics turn: prints the round-trip view and every writer's
                                        semantics turn as read here, then commits the round-trip step and its own turn
     sem-read <base>                    prints the round-trip view and every writer's semantics turn as read here

   Exit 0, or 1 with the error on stderr."
  (:require [vs.eve-disk :as ed]))

(defn- open [base] (let [d (ed/join! base)] [d (ed/named-atom d "eve/main")]))

(defn- rt-step
  "The round-trip step (orchestrator.clj rt-step): one CAS of four path edits."
  [a tag]
  (ed/edit! a (fn [e]
                (let [step (inc (or (ed/e-get-in e [:step]) 0))]
                  (-> e
                      (ed/e-update-in [:step] (fnil inc 0))
                      (ed/e-update-in [:trail] (fnil conj []) tag)
                      (ed/e-assoc-in [:by tag] {:step step
                                                :tags #{(keyword tag) :eve/coop}
                                                :seq (list step (* 2 step) (* 3 step))
                                                :text (str tag " héllo wörld ✓ " step)
                                                :pi 3.25
                                                :neg -7
                                                :big 4294967296123
                                                :sym (symbol tag)})
                      (ed/e-update-in [:totals :sum] (fnil + 0) 10))))))

(defn- acct [w i] (str w "-" i))

(defn- contend-op
  "Contention op i of writer w (orchestrator.clj contend-op): :counter +1, one of add / remove / transfer /
   transform, and the bounded :log."
  [e w i]
  (let [e (ed/e-update-in e [:counter] inc)
        e (case (mod i 4)
            0 (-> e (ed/e-assoc-in [:accounts (acct w i)] {:balance 0})
                  (ed/e-update-in [:checksums :adds] inc))
            1 (-> e (ed/e-dissoc-in [:accounts (acct w (dec i))])
                  (ed/e-update-in [:checksums :removes] inc))
            2 (let [fa (str "a" (mod (* 7 i) 10))
                    ta (str "a" (mod (inc (* 7 i)) 10))
                    amt (min 1 (or (ed/e-get-in e [:accounts fa :balance]) 0))]
                (-> e (ed/e-update-in [:accounts fa :balance] - amt)
                    (ed/e-update-in [:accounts ta :balance] + amt)
                    (ed/e-update-in [:checksums :transfers] inc)))
            3 (-> e (ed/e-update-in [:pool :balance] inc)
                  (ed/e-update-in [:checksums :transforms] inc)))]
    (ed/e-update-in e [:log] (fn [l] (vec (take-last 8 (conj (or l []) [w i])))))))

(defn- snapshot [v]
  (let [accts (:accounts v)]
    {:counter (:counter v)
     :checksums (:checksums v)
     :pool (get-in v [:pool :balance])
     :accounts (count accts)
     :balance-sum (reduce + 0 (map :balance (vals accts)))
     :log (count (:log v))}))

;; ── round 2: the semantics battery (scenario.cljc sem-shapes / x-shapes / probes / sem-read / sem-write!) —
;;    the same three layers from this runtime's own literals, each path read and each shape written on its own ─────

(def ^:private sem-writers ["node" "jvm" "bb" "wasm"])

(def ^:private sem-shapes
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

(def ^:private x-shapes
  {:x-bigint 4294967296123N
   :x-bigint-small 1N
   :x-ratio 1/3
   :x-bigdec 1.5M
   :x-char \a
   :x-nan ##NaN
   :x-sorted-map (sorted-map-by > 1 :a 3 :c 2 :b)
   :x-sorted-set (sorted-set 3 1 2)
   :x-meta-vec (with-meta [1 2] {:k 1})
   :x-meta-map (with-meta {:a 1} {:k 1})})

(defn- probes []
  (let [p (fn [f] (try (pr-str (f)) (catch Exception e (str "throws: " (ex-message e)))))]
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
     :upper (p (fn [] (clojure.string/upper-case "héllo")))
     :compare-str (p (fn [] (compare "b" "a")))
     :compare-char (p (fn [] (compare \b \a)))
     :bigint-lit (p (fn [] 4294967296123N))
     :bigint-add (p (fn [] (+ 1N 2)))
     :bigint-promote (p (fn [] (*' 4294967296 4294967296)))
     :long-overflow (p (fn [] (* 4294967296 4294967296)))
     :inc-max (p (fn [] (inc 9223372036854775807)))
     :ratio (p (fn [] (/ 1 3)))
     :ratio-add (p (fn [] (+ 1/3 1/6)))
     :div-exact (p (fn [] (/ 6 3)))
     :div-double (p (fn [] (/ 1.0 4)))
     :bigdec-lit (p (fn [] 1.5M))
     :bigdec-add (p (fn [] (+ 1.5M 1)))
     :dbl-add (p (fn [] (+ 0.1 0.2)))
     :dbl-integral (p (fn [] 2.0))
     :=long-double (p (fn [] (= 1 1.0)))
     :==long-double (p (fn [] (== 1 1.0)))
     :=long-bigint (p (fn [] (= 1 1N)))
     :=ratio-double (p (fn [] (= 1/2 0.5)))
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

(defn- read-path [a path]
  (try (ed/get-in* a path) (catch Exception e {:coop/error (str (ex-message e))})))

(defn- rt-view
  "The round-trip part of the value (scenario.cljc rt-view): each top-level key read on its own."
  [a]
  (into {} (for [k [:step :trail :by :totals] :let [v (ed/get-in* a [k])] :when (some? v)] [k v])))

(def ^:private lookups
  (concat (for [k (keys (:keys-scalar sem-shapes))] [:keys-scalar k])
          (for [k (keys (:keys-vector sem-shapes))] [:keys-vector k])
          (for [k (keys (:keys-set sem-shapes))] [:keys-set k])
          (for [k (keys (:keys-shift30 sem-shapes))] [:keys-shift30 k])))

(defn- sem-read [a]
  (into {} (for [w sem-writers
                 :when (some? (read-path a [:sem w :probes]))
                 [label path] (concat (for [k (concat (keys sem-shapes) (keys x-shapes))]
                                        [[w :shapes k] [:sem w :shapes k]])
                                      (for [[s k] lookups] [[w :lookup s k] [:sem w :shapes s k]])
                                      [[[w :refused] [:sem w :refused]] [[w :probes] [:sem w :probes]]])]
             [label (read-path a path)])))

(defn- sem-write! [a tag]
  (doseq [[k v] (concat sem-shapes x-shapes)]
    (try (ed/assoc-in! a [:sem tag :shapes k] v)
         (catch Exception e (ed/assoc-in! a [:sem tag :refused k] (str (ex-message e))))))
  (ed/assoc-in! a [:sem tag :probes] (probes)))

(defn- run [args]
  (let [[mode base & more] args]
    (case mode
      "read" (let [[d a] (open base)] (println (pr-str (ed/value a))) (ed/close! d))
      "reset" (let [[d a] (open base)] (ed/reset-value! a (read-string (first more))) (ed/close! d))
      "rt-step" (let [[d a] (open base)]
                  (println (pr-str (ed/value a)))
                  (rt-step a (first more))
                  (ed/close! d))
      "contend" (let [[d a] (open base)
                      w (first more) n (parse-long (second more))
                      start (if (nth more 2 nil) (parse-long (nth more 2)) 0)
                      t0 (System/currentTimeMillis)]
                  (dotimes [j n] (let [i (+ start j)] (ed/edit! a #(contend-op % w i))))
                  (println (pr-str {:ops n :ms (- (System/currentTimeMillis) t0)}))
                  (ed/close! d))
      "snapshot" (let [[d a] (open base)] (println (pr-str (snapshot (ed/value a)))) (ed/close! d))
      "sem-step" (let [[d a] (open base) tag (first more)]
                   (println (pr-str (rt-view a)))
                   (println (pr-str (sem-read a)))
                   (rt-step a tag)
                   (sem-write! a tag)
                   (ed/close! d))
      "sem-read" (let [[d a] (open base)]
                   (println (pr-str (rt-view a)))
                   (println (pr-str (sem-read a)))
                   (ed/close! d))
      (throw (ex-info (str "coop-worker: unknown mode " mode) {})))))

(try
  (run *command-line-args*)
  (catch Exception e
    (binding [*out* *err*] (println "coop-worker:" (ex-message e) (pr-str (ex-data e))))
    (System/exit 1)))
