;; bb_lock_price.clj — the per-op price on bb of eve's lock-only RMW: -with-file-lock + -cas-i32! (the exclusive hold's
;; cross-process step) vs a plain -cas-i32! (what runs inside the hold), on one :lockable? BbMmapRegion.
;;
;;   bb --classpath <eve>/src:<eve's :bb roots in hosts.edn> test/coop/bb_lock_price.clj <region-file> <n> <rounds>
;;
;; Measured (bb 1.13.226, 2026-11-06, 20000 x 7): file-lock CAS median 5,247 ns/op; plain CAS 1,374 ns/op.
(require '[eve.region :as region] '[eve.mem :as mem])
(let [[f n rounds] *command-line-args*
      n (parse-long n) rounds (parse-long rounds)
      r (region/open-mmap-region f 4096 {:lockable? true})
      run (fn [op] (let [t0 (System/nanoTime)]
                     (dotimes [i n] (op i))
                     (/ (double (- (System/nanoTime) t0)) n)))
      locked (fn [i] (mem/-with-file-lock r 64 (fn [] (mem/-cas-i32! r 64 (mem/-load-i32 r 64) (inc i)))))
      plain  (fn [i] (mem/-cas-i32! r 128 (mem/-load-i32 r 128) (inc i)))
      med (fn [xs] (nth (sort xs) (quot (count xs) 2)))
      ls (vec (for [_ (range rounds)] (run locked)))
      ps (vec (for [_ (range rounds)] (run plain)))]
  (println "PRICE bb" (System/getProperty "babashka.version") "n" n "rounds" rounds)
  (println "  file-lock CAS ns/op median" (long (med ls)) "min" (long (apply min ls)) "max" (long (apply max ls)))
  (println "  plain CAS     ns/op median" (long (med ps)) "min" (long (apply min ps)) "max" (long (apply max ps))))
