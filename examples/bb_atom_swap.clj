#!/usr/bin/env bb
;; examples/bb_atom_swap.clj — Babashka mmap atom smoke test & micro-benchmark
;;
;; Usage:  bb -f examples/bb_atom_swap.clj
;;   or:   bb run atom-swap

(require '[eve.atom :as atom])

(def base-path "/tmp/bb-eve-test")

;; Clean up any prior run
(doseq [ext [".root" ".rmap" ".slab0" ".slab1" ".slab2" ".slab3" ".slab4" ".slab5"]]
  (let [f (java.io.File. (str base-path ext))]
    (when (.exists f) (.delete f))))

(println "=== Babashka mmap-atom smoke test ===\n")

;; --- Domain open ---
(let [t0 (System/nanoTime)]
  (def domain (atom/persistent-atom-domain base-path))
  (printf "Domain open:   %.1f ms%n" (/ (- (System/nanoTime) t0) 1e6)))

;; --- Atom create ---
(let [t0 (System/nanoTime)]
  (def my-atom (atom/lookup-or-create-mmap-atom! domain "test/counter" 0))
  (printf "Atom create:   %.1f ms%n" (/ (- (System/nanoTime) t0) 1e6)))

;; --- Scalar swap ---
(println "\n--- Scalar swaps ---")
(let [n 100
      t0 (System/nanoTime)]
  (dotimes [_ n]
    (swap! my-atom inc))
  (let [elapsed (/ (- (System/nanoTime) t0) 1e6)]
    (printf "%d scalar swaps: %.1f ms (%.0f us/swap)%n" n elapsed (/ (* elapsed 1000) n))))
(printf "Counter value: %s%n" (pr-str @my-atom))

;; --- Map atom ---
(println "\n--- Map assoc swaps ---")
(def map-atom (atom/lookup-or-create-mmap-atom! domain "test/data" {}))

(let [n 50
      t0 (System/nanoTime)]
  (dotimes [i n]
    (swap! map-atom assoc (keyword (str "k" i)) i))
  (let [elapsed (/ (- (System/nanoTime) t0) 1e6)]
    (printf "%d map assoc swaps: %.1f ms (%.1f ms/swap)%n" n elapsed (/ elapsed n))))
(printf "Map size: %d%n" (count @map-atom))
(printf "Sample keys: %s%n" (pr-str (take 5 (keys @map-atom))))

;; --- Deref throughput ---
(println "\n--- Deref throughput ---")
(let [n 1000
      t0 (System/nanoTime)]
  (dotimes [_ n]
    @map-atom)
  (let [elapsed (/ (- (System/nanoTime) t0) 1e6)]
    (printf "%d map derefs: %.1f ms (%.0f us/deref)%n" n elapsed (/ (* elapsed 1000) n))))

;; --- Set atom ---
(println "\n--- Set swap ---")
(def set-atom (atom/lookup-or-create-mmap-atom! domain "test/tags" #{}))
(swap! set-atom conj :alpha)
(swap! set-atom conj :beta)
(swap! set-atom conj :gamma)
(printf "Set value: %s%n" (pr-str @set-atom))

;; --- List atom ---
(println "\n--- List swap ---")
(def list-atom (atom/lookup-or-create-mmap-atom! domain "test/log" '()))
(swap! list-atom conj :first)
(swap! list-atom conj :second)
(swap! list-atom conj :third)
(printf "List value: %s%n" (pr-str @list-atom))

;; --- Vec atom ---
(println "\n--- Vec swap ---")
(def vec-atom (atom/lookup-or-create-mmap-atom! domain "test/items" []))
(swap! vec-atom conj "a")
(swap! vec-atom conj "b")
(swap! vec-atom conj "c")
(printf "Vec value: %s%n" (pr-str @vec-atom))

;; --- Cleanup ---
(atom/close-atom-domain! domain)
(println "\nDomain closed. Done!")
