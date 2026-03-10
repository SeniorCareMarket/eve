(ns eve.readme-example-test
  "Tests that the README code examples work exactly as documented.
   Covers both the persistent-atom (cross-process) examples."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [eve.atom :as atom])
  (:import [java.lang ProcessBuilder]))

(def ^:private base (str "/tmp/eve-readme-" (System/currentTimeMillis)))
(def ^:private worker (str (System/getProperty "user.dir")
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
  "Spawn Node worker to read atom at base, return parsed EDN value."
  [b]
  (let [r (spawn-node! worker "join-read" b)]
    (assert (zero? (:exit r))
            (str "join-read failed: " (:err r)))
    (edn/read-string (.trim (:out r)))))

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Process A
;;
;;   (def counter (e/atom {:id ::counter :persistent "./my-db"} 0))
;;   (swap! counter inc)
;;   @counter ;; => 1
;; ─────────────────────────────────────────────────────────────────

(deftest readme-process-a-persistent-atom
  (testing "Process A: create persistent atom, swap! inc, deref => 1"
    (let [b     (str base "-process-a")
          d     (atom/persistent-atom-domain b)
          counter (atom/atom {:id ::counter :persistent b} 0)]
      (swap! counter inc)
      (is (= 1 @counter))
      (atom/close-atom-domain! d))))

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Process B (JVM → JVM)
;;
;;   ;; Same :id + path — detects existing atom and loads current value
;;   (def counter (e/atom {:id ::counter :persistent "./my-db"} 0))
;;   @counter ;; => 1  (sees Process A's write)
;;   (swap! counter inc)
;;   @counter ;; => 2
;; ─────────────────────────────────────────────────────────────────

(deftest readme-process-b-joins-jvm
  (testing "Process A writes 1, Process B (JVM) joins and sees 1, swaps to 2"
    (let [b (str base "-ab-jvm")]
      ;; Process A
      (let [d       (atom/persistent-atom-domain b)
            counter (atom/atom {:id ::counter :persistent b} 0)]
        (swap! counter inc)
        (is (= 1 @counter))
        (atom/close-atom-domain! d))

      ;; Process B (same JVM, simulated by closing and reopening domain)
      (let [d       (atom/persistent-atom-domain b)
            counter (atom/atom {:id ::counter :persistent b} 0)]
        (is (= 1 @counter) "Process B should see Process A's write")
        (swap! counter inc)
        (is (= 2 @counter))
        (atom/close-atom-domain! d)))))

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Process B (JVM → Node)
;;
;; Same flow but Process B is a real Node.js process.
;; ─────────────────────────────────────────────────────────────────

(deftest readme-process-b-joins-node
  (testing "Process A (JVM) writes 1, Process B (Node) joins and sees 1"
    (let [b (str base "-ab-node")]
      ;; Process A (JVM)
      (let [d       (atom/persistent-atom-domain b)
            counter (atom/atom {:id ::counter :persistent b} 0)]
        (swap! counter inc)
        (is (= 1 @counter))
        (atom/close-atom-domain! d))

      ;; Process B (Node.js) — reads and verifies the value is 1
      (let [r (spawn-node! worker "join-verify" b "1")]
        (is (zero? (:exit r))
            (str "Node Process B should see 1. err: " (:err r))))

      ;; Process B (Node.js) — swaps counter (1 → 2)
      (let [r (spawn-node! worker "join-swap" b)]
        (is (zero? (:exit r))
            (str "Node swap should succeed. err: " (:err r))))

      ;; Verify final value is 2 from JVM
      (let [d       (atom/persistent-atom-domain b)
            counter (atom/atom {:id ::counter :persistent b} 0)]
        (is (= 2 @counter) "Should be 2 after Node Process B's swap")
        (atom/close-atom-domain! d)))))

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Node → JVM
;;
;; Reverse direction: Node is Process A, JVM is Process B.
;; ─────────────────────────────────────────────────────────────────

(deftest readme-node-writes-jvm-joins
  (testing "Process A (Node) writes 1, Process B (JVM) joins and sees 1, swaps to 2"
    (let [b (str base "-na-jb")]
      ;; Process A (Node.js): create atom with 0, swap inc → 1
      ;; We need Node to create the domain first. Use join-reset to set initial
      ;; value, then join-swap to increment.
      ;; Actually, the mmap-worker always uses join-domain-atom! which creates if needed.
      ;; Let's have JVM create the domain with 0, then Node swaps to 1, then JVM joins.
      (let [d       (atom/persistent-atom-domain b)
            counter (atom/atom {:id ::counter :persistent b} 0)]
        (atom/close-atom-domain! d))

      ;; Node acts as Process A: swap 0 → 1
      (let [r (spawn-node! worker "join-swap" b)]
        (is (zero? (:exit r))
            (str "Node Process A swap failed: " (:err r))))

      ;; JVM acts as Process B: join, see 1, swap to 2
      (let [d       (atom/persistent-atom-domain b)
            counter (atom/atom {:id ::counter :persistent b} 0)]
        (is (= 1 @counter) "JVM Process B should see Node's write of 1")
        (swap! counter inc)
        (is (= 2 @counter))
        (atom/close-atom-domain! d)))))
