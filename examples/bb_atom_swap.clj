#!/usr/bin/env bb
;; Eve persistent atom example — Babashka
;;
;; Demonstrates swap! into an Eve mmap-backed atom from bb,
;; showing the time for various operations.
;;
;; Usage:
;;   bb examples/bb_atom_swap.clj

(require '[eve.atom :as atom])

(def base-path "/tmp/eve-bb-example/")

;; Ensure directory exists
(.mkdirs (java.io.File. base-path))

(println "=== Eve Persistent Atom — Babashka Example ===")
(println)

;; --- 1. Create domain and atom ---
(let [t0 (System/nanoTime)
      d  (atom/persistent-atom-domain base-path)
      t1 (System/nanoTime)]
  (printf "Domain open:  %.2f ms%n" (/ (- t1 t0) 1e6))

  (let [t0 (System/nanoTime)
        a  (atom/persistent-atom {:id :demo/counter :persistent base-path} 0)
        t1 (System/nanoTime)]
    (printf "Atom create:  %.2f ms%n" (/ (- t1 t0) 1e6))
    (println)

    ;; --- 2. Simple scalar swaps ---
    (println "--- Scalar swap! ---")
    (let [n     100
          t0    (System/nanoTime)
          _     (dotimes [_ n] (swap! a inc))
          t1    (System/nanoTime)
          total (/ (- t1 t0) 1e6)]
      (printf "%d increments: %.2f ms  (%.1f μs/swap)%n"
              n total (* 1000 (/ total n)))
      (println "Final value:" @a))
    (println)

    ;; --- 3. Map swap! ---
    (println "--- Map swap! ---")
    (let [a2 (atom/persistent-atom {:id :demo/data :persistent base-path} {})
          n  50
          t0 (System/nanoTime)
          _  (dotimes [i n]
               (swap! a2 assoc (keyword (str "k" i)) i))
          t1 (System/nanoTime)
          total (/ (- t1 t0) 1e6)]
      (printf "%d assocs into map: %.2f ms  (%.1f μs/swap)%n"
              n total (* 1000 (/ total n)))
      (printf "Map size: %d, sample: %s%n"
              (count @a2)
              (select-keys @a2 [:k0 :k1 :k49])))
    (println)

    ;; --- 4. Vector swap! ---
    (println "--- Vector swap! ---")
    (let [a3 (atom/persistent-atom {:id :demo/vec :persistent base-path} [])
          n  50
          t0 (System/nanoTime)
          _  (dotimes [i n]
               (swap! a3 conj i))
          t1 (System/nanoTime)
          total (/ (- t1 t0) 1e6)]
      (printf "%d conjs into vec: %.2f ms  (%.1f μs/swap)%n"
              n total (* 1000 (/ total n)))
      (printf "Vec size: %d, first 5: %s%n"
              (count @a3)
              (vec (take 5 @a3))))
    (println)

    ;; --- 5. Deref timing ---
    (println "--- Deref timing ---")
    (let [n  1000
          t0 (System/nanoTime)
          _  (dotimes [_ n] @a)
          t1 (System/nanoTime)
          total (/ (- t1 t0) 1e6)]
      (printf "%d derefs: %.2f ms  (%.1f μs/deref)%n"
              n total (* 1000 (/ total n))))
    (println)

    ;; --- 6. Cross-process verification ---
    (println "--- Cross-process persistence ---")
    (println "Files on disk:")
    (doseq [f (sort (.list (java.io.File. base-path)))]
      (let [sz (.length (java.io.File. (str base-path f)))]
        (printf "  %s  (%d bytes)%n" f sz)))

    ;; Clean up
    (atom/close-atom-domain! d)
    (println)
    (println "Done. Domain closed.")))
