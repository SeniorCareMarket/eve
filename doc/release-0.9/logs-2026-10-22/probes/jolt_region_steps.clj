;; jolt_region_steps.clj — jolt's eve region adapter (adapters/region/jolt-ffi), one operation a step, each step's
;; answer or error printed: where a platform's region faults ("invalid memory reference": the operator's Mac,
;; 2026-10-22, the jolt member stopping at its domain's open). Runs on any platform jolt's region supports.
;;
;;   CP=$(bb -e "(require '[babashka.fs :as fs] '[clojure.string :as str]) (println (str/join \":\" (map #(str (fs/absolutize %)) (concat [\"src\"] (:jolt (clojure.edn/read-string (slurp \"hosts.edn\"))) [\"capstone/eve-media/src\" \"capstone/eve-media/members/jolt/src\"]))))")
;;   jolt -Scp "$CP" doc/release-0.9/logs-2026-10-22/probes/jolt_region_steps.clj          (from eve/)
;;
;; With eve-media's sources on the classpath (as above) it then does what eve-media's jolt member does before it
;; joins, in its order: loads its transcoder (eve-media.av: FFmpeg's libraries, their majors checked) and opens an
;; eve-media domain.
(defn step [label f]
  (let [r (try [:ok (f)] (catch Throwable e [:FAIL (str (ex-message e))]))]
    (println (pr-str (into [label] r)))
    (flush)
    (= :ok (first r))))

(println (pr-str [:platform (System/getProperty "os.name") (System/getProperty "os.arch")]))
(when (step :require-eve.region (fn [] (require '[eve.region]) :loaded))
  (let [mem (fn [s] (ns-resolve 'eve.mem s))
        load32 @(mem '-load-i32) store32 @(mem '-store-i32!) add32 @(mem '-add-i32!) cas32 @(mem '-cas-i32!)
        xchg32 @(mem '-exchange-i32!) load64 @(mem '-load-i64) store64 @(mem '-store-i64!) add64 @(mem '-add-i64!)
        cas64 @(mem '-cas-i64!) wait32 @(mem '-wait-i32!) notify32 @(mem '-notify-i32!)
        read-bytes @(mem '-read-bytes) write-bytes @(mem '-write-bytes!)
        ops (fn [kind r]
              (step [kind :store-i32] (fn [] (store32 r 64 41) :stored))
              (step [kind :load-i32] (fn [] (load32 r 64)))
              (step [kind :add-i32] (fn [] [(add32 r 64 1) (load32 r 64)]))
              (step [kind :cas-i32-swaps] (fn [] [(cas32 r 64 42 7) (load32 r 64)]))
              (step [kind :cas-i32-witness] (fn [] [(cas32 r 64 99 1) (load32 r 64)]))
              (step [kind :exchange-i32] (fn [] [(xchg32 r 64 5) (load32 r 64)]))
              (step [kind :store-i64] (fn [] (store64 r 128 1234567890123) :stored))
              (step [kind :load-i64] (fn [] (load64 r 128)))
              (step [kind :add-i64] (fn [] [(add64 r 128 1) (load64 r 128)]))
              (step [kind :cas-i64] (fn [] [(cas64 r 128 1234567890124 9) (load64 r 128)]))
              (step [kind :write-bytes] (fn [] (write-bytes r 256 (byte-array (map byte [1 2 3 4]))) :written))
              (step [kind :read-bytes] (fn [] (vec (read-bytes r 256 4))))
              (step [kind :wait-not-equal] (fn [] (wait32 r 64 1 100)))
              (step [kind :wait-timed-out] (fn [] (wait32 r 64 5 100)))
              (step [kind :notify] (fn [] (notify32 r 64 1))))]
    (let [h (atom nil)]
      (when (step :make-heap-region (fn [] (reset! h ((ns-resolve 'eve.region 'make-heap-region) 4096)) :made))
        (ops :heap @h)))
    (let [path (str (System/getProperty "java.io.tmpdir") "/eve-jolt-region-steps-" (System/currentTimeMillis))
          m (atom nil)]
      (when (step :open-mmap-region (fn [] (reset! m ((ns-resolve 'eve.region 'open-mmap-region) path 65536)) path))
        (ops :mmap @m)
        (step :mmap-second-map-reads-the-first
              (fn [] (load32 ((ns-resolve 'eve.region 'open-mmap-region) path 65536) 64)))))
    (step :require-eve-media.av (fn [] (require 'eve-media.av) :loaded))
    (step :open-an-eve-media-domain
          (fn [] (require 'eve-media.domain)
            (vec (sort (keys ((ns-resolve 'eve-media.domain 'open-atoms)
                              (str (System/getProperty "java.io.tmpdir") "/eve-jolt-domain-steps-"
                                   (System/currentTimeMillis) "/d")))))))))
(println :done)
