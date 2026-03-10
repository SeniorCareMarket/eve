(ns eve.readme-example-test
  "Tests that the README code examples work exactly as documented.
   Uses [eve.alpha :as e] — the same require the README shows.
   Atom creation uses e/atom with :persistent — no explicit domain management."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [eve.alpha :as e]
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

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Process A
;;
;;   (require '[eve.alpha :as e])
;;   (def counter (e/atom {:id ::counter :persistent "./my-db"} 0))
;;   (swap! counter inc)
;;   @counter ;; => 1
;; ─────────────────────────────────────────────────────────────────

(deftest readme-process-a-persistent-atom
  (testing "Process A: create persistent atom, swap! inc, deref => 1"
    (let [b       (str base "-process-a")
          counter (e/atom {:id ::counter :persistent b} 0)]
      (swap! counter inc)
      (is (= 1 @counter)))))

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Process B (JVM → JVM)
;;
;;   (def counter (e/atom {:id ::counter :persistent "./my-db"} 0))
;;   @counter ;; => 1  (sees Process A's write)
;;   (swap! counter inc)
;;   @counter ;; => 2
;; ─────────────────────────────────────────────────────────────────

(deftest readme-process-b-joins-jvm
  (testing "Process A writes 1, Process B (JVM) joins and sees 1, swaps to 2"
    (let [b (str base "-ab-jvm")]
      ;; Process A — lazy domain creation via :persistent
      (let [counter (e/atom {:id ::counter :persistent b} 0)]
        (swap! counter inc)
        (is (= 1 @counter))
        ;; Close domain to simulate process exit so Process B gets a fresh join
        (atom/close-atom-domain! (atom/persistent-atom-domain b)))

      ;; Process B — domain re-created lazily, sees Process A's write
      (let [counter (e/atom {:id ::counter :persistent b} 0)]
        (is (= 1 @counter) "Process B should see Process A's write")
        (swap! counter inc)
        (is (= 2 @counter))))))

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Process B (JVM → Node)
;; ─────────────────────────────────────────────────────────────────

(deftest readme-process-b-joins-node
  (testing "Process A (JVM) writes 1, Process B (Node) joins and sees 1"
    (let [b (str base "-ab-node")]
      ;; Process A (JVM)
      (let [counter (e/atom {:id ::counter :persistent b} 0)]
        (swap! counter inc)
        (is (= 1 @counter))
        (atom/close-atom-domain! (atom/persistent-atom-domain b)))

      ;; Process B (Node.js) — reads and verifies the value is 1
      (let [r (spawn-node! worker "join-verify" b "1")]
        (is (zero? (:exit r))
            (str "Node Process B should see 1. err: " (:err r))))

      ;; Process B (Node.js) — swaps counter (1 → 2)
      (let [r (spawn-node! worker "join-swap" b)]
        (is (zero? (:exit r))
            (str "Node swap should succeed. err: " (:err r))))

      ;; Verify final value is 2 from JVM
      (let [counter (e/atom {:id ::counter :persistent b} 0)]
        (is (= 2 @counter) "Should be 2 after Node Process B's swap")))))

;; ─────────────────────────────────────────────────────────────────
;; README § Cross-process persistent atoms — Node → JVM
;; ─────────────────────────────────────────────────────────────────

(deftest readme-node-writes-jvm-joins
  (testing "Process A (Node) writes 1, Process B (JVM) joins and sees 1, swaps to 2"
    (let [b (str base "-na-jb")]
      ;; Bootstrap domain files so Node can join
      (let [counter (e/atom {:id ::counter :persistent b} 0)]
        (atom/close-atom-domain! (atom/persistent-atom-domain b)))

      ;; Node acts as Process A: swap 0 → 1
      (let [r (spawn-node! worker "join-swap" b)]
        (is (zero? (:exit r))
            (str "Node Process A swap failed: " (:err r))))

      ;; JVM acts as Process B: join, see 1, swap to 2
      (let [counter (e/atom {:id ::counter :persistent b} 0)]
        (is (= 1 @counter) "JVM Process B should see Node's write of 1")
        (swap! counter inc)
        (is (= 2 @counter))))))
