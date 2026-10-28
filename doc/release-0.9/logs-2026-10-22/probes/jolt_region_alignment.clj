;; jolt_region_alignment.clj — the word accesses an eve-media domain's open makes through jolt's mapped regions, those
;; whose offset is not a multiple of the word's width recorded (a 4-byte atomic at an offset not ≡ 0 mod 4, an 8-byte
;; one not ≡ 0 mod 8). x86-64 performs them; arm64's atomics fault on them — the operator's Mac, 2026-10-22: every
;; region step of jolt_region_steps.clj :ok on aligned offsets, the domain's open "invalid memory reference".
;;
;;   (from eve/, CP as in jolt_region_steps.clj)  jolt -Scp "$CP" doc/release-0.9/logs-2026-10-22/probes/jolt_region_alignment.clj
(require '[eve.region :as r] '[eve.mem :as mem] '[eve-media.domain :as domain])

(def seen (atom {}))

(defn- note! [op off width]
  (when-not (zero? (mod off width))
    (swap! seen update [op (mod off 8)] (fnil inc 0))))

(defn checked [region path]
  (reify
    mem/IMemRegion
    (-byte-length [_] (mem/-byte-length region))
    (-load-i32 [_ off] (note! :load-i32 off 4) (mem/-load-i32 region off))
    (-store-i32! [_ off v] (note! :store-i32 off 4) (mem/-store-i32! region off v))
    (-cas-i32! [_ off e d] (note! :cas-i32 off 4) (mem/-cas-i32! region off e d))
    (-add-i32! [_ off d] (note! :add-i32 off 4) (mem/-add-i32! region off d))
    (-sub-i32! [_ off d] (note! :sub-i32 off 4) (mem/-sub-i32! region off d))
    (-exchange-i32! [_ off v] (note! :exchange-i32 off 4) (mem/-exchange-i32! region off v))
    (-load-i64 [_ off] (note! :load-i64 off 8) (mem/-load-i64 region off))
    (-store-i64! [_ off v] (note! :store-i64 off 8) (mem/-store-i64! region off v))
    (-cas-i64! [_ off e d] (note! :cas-i64 off 8) (mem/-cas-i64! region off e d))
    (-add-i64! [_ off d] (note! :add-i64 off 8) (mem/-add-i64! region off d))
    (-sub-i64! [_ off d] (note! :sub-i64 off 8) (mem/-sub-i64! region off d))
    (-wait-i32! [_ off e t] (note! :wait-i32 off 4) (mem/-wait-i32! region off e t))
    (-notify-i32! [_ off n] (note! :notify-i32 off 4) (mem/-notify-i32! region off n))
    (-supports-watch? [_] (mem/-supports-watch? region))
    (-read-bytes [_ off len] (mem/-read-bytes region off len))
    (-write-bytes! [_ off src] (mem/-write-bytes! region off src))))

(def opened (atom 0))
(def open-mmap-region r/open-mmap-region)

(let [base (str (System/getProperty "java.io.tmpdir") "/eve-jolt-alignment-" (System/currentTimeMillis) "/d")
      atoms (with-redefs [r/open-mmap-region (fn [& args] (swap! opened inc) (checked (apply open-mmap-region args) (first args)))]
              (domain/open-atoms base))]
  (println (pr-str {:regions-opened @opened :atoms (vec (sort (keys atoms)))
                    :unaligned (into (sorted-map) @seen)})))
