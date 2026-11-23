(ns eve.flat.requirements-test
  "W0.1 and W0.2: the public API surface of eve.flat, and the requirement
   manifest (test/eve/flat/requirements.edn) covering every requirement id
   in doc/flat/IMPL-PLAN.md."
  (:require [clojure.test :refer [deftest is testing]]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [clojure.edn :as edn]
            ; the alias is exercised on the other reader pass; the require loads on both 
            #_{:clj-kondo/ignore [:unused-namespace]}   [clojure.string :as str]
            [eve.flat]
            #?(:clj [clojure.java.io :as io])))

;; ---------------------------------------------------------------------------
;; W0.2 — requirements-covered
;; ---------------------------------------------------------------------------

#?(:clj
   (defn- plan-ids
     "Every numbered requirement id in IMPL-PLAN.md (Waves 0 and 1 carry
      numbers in the plan; Wave 2's rows are numbered by the manifest)."
     []
     (let [text (slurp (io/file "doc/flat/IMPL-PLAN.md"))]
       (into #{}
             (map (fn [[_ p n]] (keyword (str p "." n))))
             (re-seq #"\b(W0|Z[1-4]|T1|B1|C[1-4]|L1|X1)\.(\d+)\b" text)))))

#?(:clj
   (defn- manifest []
     (edn/read-string (slurp (io/file "test/eve/flat/requirements.edn")))))

#?(:clj
   (deftest requirements-covered
     (let [m (manifest)
           pids (plan-ids)]
       (testing "every requirement id in the plan has a manifest entry"
         (is (seq pids) "the plan parse found no ids — the pattern or the path is wrong")
         (doseq [id pids]
           (is (contains? m id) (str "no manifest entry for " id))))
       (testing "every numbered manifest entry is an id in the plan (Wave 2 H* ids are manifest-numbered)"
         (doseq [id (keys m)]
           (when-not (str/starts-with? (name id) "H")
             (is (contains? pids id) (str "manifest entry " id " is not an id in the plan")))))
       (testing "every entry is well-formed"
         (doseq [[id {:keys [lane test kind status]}] m]
           (is (string? lane) (str id ": :lane"))
           (is (string? test) (str id ": :test"))
           (is (#{:planned :built} status) (str id ": :status"))
           (case (or kind :var)
             :var (is (re-matches #"^[a-z0-9_.\-]+/[a-z0-9_.*?!=<>+'\-]+$" test)
                      (str id ": :test is not an ns/var name: " test))
             :suite (is (re-matches #"^[a-z0-9_.\-]+$" test)
                        (str id ": :suite :test is not a namespace: " test))
             :log (is (str/starts-with? test "doc/")
                      (str id ": :log :test is not a doc path: " test))
             (is false (str id ": unknown :kind " kind)))))
       (testing "entries marked :built name things that exist now"
         (doseq [[id {:keys [test kind status]}] m
                 :when (= :built status)]
           (case (or kind :var)
             :var (is (some? (requiring-resolve (symbol test)))
                      (str id ": :built but " test " does not resolve"))
             :suite (is (some? (find-ns (symbol test)))
                        (str id ": :built but namespace " test " does not exist"))
             :log (is (.exists (io/file test))
                      (str id ": :built but " test " does not exist"))
             (is false (str id ": unknown :kind " kind))))))))

;; ---------------------------------------------------------------------------
;; W0.1 — api-surface
;; ---------------------------------------------------------------------------

;; The frozen arities of the public verbs (IMPL-PLAN §3.2, W0.1). A lane that
;; lands a verb keeps its arity; the stub answers "not built" until then.
(def ^:private api-arities
  {'detach 1 'place 2 'transfer! 2 'pack 1 'open 1 'open-checked 1 'bytes 1})

;; ---------------------------------------------------------------------------
;; CONTRACT AMENDMENT (collation, 2026-11-12 — FLAGGED for the operator;
;; this probe is the frozen W0 contract and this change is deliberately a
;; separate, named commit, not buried in a merge).
;;
;; As frozen, the probe accepted exactly two answers to a nil call: a return
;; value, or the :eve.flat/not-built ex-info. Every lane that landed hit
;; the same wall: a built verb rightly refuses nil with its TYPED REFUSAL —
;; detach answers {:type :eve.flat.image/nil-root}, open answers
;; {:type :eve.flat.chunk/short}, place/transfer!/open-checked answer
;; under their :eve.flat.place/check, :eve.flat.transfer/refusal and
;; check keys — and the probe rethrew it as the suite's one error (hit
;; identically by Detach, Chunk and Sister). The amendment accepts the
;; typed refusal as a valid answer. The probe still fails on anything
;; untyped: host errors (NPE, arity, class-cast) are not ExceptionInfo, and
;; an ex-info outside the refusal vocabulary is rethrown as before.
;; ---------------------------------------------------------------------------
(defn- typed-refusal?
  "An ex-info's data carries the flat layer's refusal vocabulary: a
   :type, or any key in an eve.flat.* namespace (place's
   :eve.flat.place/check, transfer's :eve.flat.transfer/refusal,
   chunk's :eve.flat.chunk/…), or Check's :check key (its refusals
   are {:check <name>}). SUCCESSOR CORRECTION (2026-11-12): as first
   amended, the namespace test demanded exactly \"eve.flat\", which
   none of the sub-namespace keys satisfy — (namespace
   :eve.flat.place/check) is \"eve.flat.place\" — so the amended
   probe rethrew the very refusals its own comment names as valid.
   The prefix test below is what the amendment's comment always said."
  [data]
  (boolean (or (:type data)
               (:check data)
               (some (fn [k] (and (keyword? k)
                                  (some-> (namespace k)
                                          (.startsWith "eve.flat"))))
                     (keys data)))))

(deftest api-surface
  (doseq [[sym arity] api-arities]
    (let [v (ns-resolve 'eve.flat sym)]
      (is (some? v) (str "eve.flat/" sym " does not exist"))
      (when v
        (is (fn? @v) (str "eve.flat/" sym " is not a function"))
        (testing (str "eve.flat/" sym " delegates or answers not-built")
          (let [outcome (try (apply @v (repeat arity nil))
                             ::returned
                             (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                               (cond (:eve.flat/not-built (ex-data e)) ::not-built
                                     (typed-refusal? (ex-data e)) ::refused
                                     :else (throw e))))]
            (is (#{::returned ::not-built ::refused} outcome)
                (str "eve.flat/" sym " neither returned, answered not-built, nor refused typed"))))))))
