(ns eve.flat.reengine-bench
  "Lane REENGINE's bench: the measurement arms the lane's verdicts rest on.

   Modes (one per invocation; every mode prints its rows to stdout and the
   leg script's redirect carries them):

     --transfer  B4's arms ported from doc/flat/probes-t1/b4_bench.clj
                 (which still requires the pre-rename eve.static names):
                 detach (eve.flat.transfer's walk, via read-pinned) and
                 zip (transfer/place-image! — eve.flat.place under it)
                 timed separately, then eve.flat/transfer! whole; each
                 arm read-back-verified; caps 5/26/9/26 ms and the
                 transfer <= 2x (detach+zip) composition gate printed.
     --census    per corpus: the detach block census (block count, count
                 per size class from the image's recorded source offsets,
                 content bytes, image length, image SHA-256 — the
                 byte-identity anchor a re-engineered detach must match),
                 detach-image time from the mmap atom, and detach-image
                 time of the same value read through the image's own
                 FlatSio view (the arraycopy read arm), one session.

   Run (on J, from the repo root):
     CP=$(clojure -Spath -X:jvm-test)
     java -Xmx4g -cp \"$CP\" clojure.main -m eve.flat.reengine-bench -- --transfer
     java -Xmx4g -cp \"$CP\" clojure.main -m eve.flat.reengine-bench -- --census"
  (:require [clojure.string :as str]
            [eve.atom :as ea]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.flat :as flat]
            [eve.flat.corpora :as corpora]
            [eve.flat.image :as img]
            [eve.flat.place :as place]
            [eve.flat.stream :as stream]
            [eve.flat.transfer :as tf])
  (:import [java.security MessageDigest]
           ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
           #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays]))

(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

(def ^:private caps-ms
  {:map-10k 5.0 :vec-100k 26.0 :records-10k 9.0 :messages-5k 26.0})

(def ^:private tf-detach-value @#'tf/detach-value)

(defn- bench-min [warmup runs f]
  (dotimes [_ warmup] (f))
  (loop [i 0 best Double/MAX_VALUE]
    (if (== i runs)
      best
      (let [t0 (System/nanoTime)
            _ (f)
            ms (/ (- (System/nanoTime) t0) 1e6)]
        (recur (inc i) (min best ms))))))

(defn- fresh-atom [name]
  (let [dom (ea/atom-domain nil :capacities capacities)]
    (ea/lookup-or-create-mmap-atom! dom name nil)))

(defn- sha256-hex ^String [^bytes bs ^long len]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md bs 0 (int len))
    (str/join (map (fn [b] (format "%02x" (bit-and b 0xFF))) (.digest md)))))

;; ---------------------------------------------------------------------------
;; --transfer: B4's arms
;; ---------------------------------------------------------------------------

(defn- measure-transfer [kind v {:keys [warmup runs]}]
  (let [holder (fresh-atom "rb/hold")
        _ (swap! holder (fn [_] v))
        bytes (ea/read-pinned holder (fn [lv] (tf-detach-value lv)))
        zip-ok (let [t (fresh-atom "rb/zip-check")]
                 (tf/place-image! t bytes)
                 (= v @t))
        tf-ok (let [t (fresh-atom "rb/tf-check")]
                (flat/transfer! t holder)
                (= v @t))
        detach-ms (bench-min warmup runs
                             #(ea/read-pinned holder (fn [lv] (tf-detach-value lv))))
        zip-ms (bench-min warmup runs
                          #(tf/place-image! (fresh-atom "rb/zip") bytes))
        transfer-ms (bench-min warmup runs
                               #(flat/transfer! (fresh-atom "rb/tf") holder))]
    {:kind kind :bytes (alength bytes) :zip-ok zip-ok :tf-ok tf-ok
     :detach-ms detach-ms :zip-ms zip-ms :transfer-ms transfer-ms}))

(defn- run-transfer [{:keys [warmup runs]}]
  (println "B4 atom->atom — reengine bench (T1 arms ported to eve.flat)")
  (doseq [[kind v] (corpora/corpora :canonical)]
    (let [r (measure-transfer kind v {:warmup warmup :runs runs})
          dz (+ (:detach-ms r) (:zip-ms r))
          ratio (/ (:transfer-ms r) dz)
          cap (get caps-ms kind)]
      (println (format "%-12s bytes=%d zip-ok=%s tf-ok=%s detach=%.2fms zip=%.2fms detach+zip=%.2fms transfer=%.2fms ratio=%.2f cap=%.0fms ratio<=2x:%s under-cap:%s"
                       (name kind) (:bytes r) (:zip-ok r) (:tf-ok r)
                       (:detach-ms r) (:zip-ms r) dz (:transfer-ms r)
                       ratio cap (<= ratio 2.0) (<= (:transfer-ms r) cap))))
    (System/gc))
  (println "TRANSFER BENCH DONE"))

;; ---------------------------------------------------------------------------
;; --census: where detach's time goes, by source read path
;; ---------------------------------------------------------------------------

(defn- census-one [kind v]
  (let [holder (fresh-atom "rc/hold")
        _ (swap! holder (fn [_] v))
        deref-ms (bench-min 2 5 #(deref holder))
        im (ea/read-pinned holder (fn [lv] (img/detach-image lv)))
        mmap-ms (bench-min 1 3 #(ea/read-pinned holder (fn [lv] (img/detach-image lv))))
        blocks (img/image-blocks im)
        n (count blocks)
        by-class (frequencies (map (fn [b] (alloc/decode-class-idx (bit-and (long (:src-off b)) 0xFFFFFFFF)))
                                   blocks))
        content (reduce + (map :size blocks))
        view-val (img/image-value im)
        view-ms (bench-min 1 3 #(img/detach-image view-val))
        view-im (img/detach-image view-val)
        same-bytes (Arrays/equals (img/image-bytes im) (img/image-bytes view-im))]
    {:kind kind :deref-ms deref-ms :mmap-ms mmap-ms :view-ms view-ms
     :n n :by-class by-class :content content :length (img/image-length im)
     :sha (sha256-hex (img/image-bytes im) (img/image-length im)) :same-bytes same-bytes
     :value-ok (= v (d/eve->clj view-val))}))

(defn- run-census [_]
  (println "DETACH CENSUS — blocks by class, detach by source read path")
  (doseq [[kind v] (corpora/corpora :canonical)]
    (let [r (census-one kind v)]
      (println (format "%-12s blocks=%d classes=%s content=%dB image=%dB sha=%s view==mmap-bytes:%s value-ok:%s deref=%.2fms detach-mmap=%.2fms (%.1f us/blk) detach-view=%.2fms (%.1f us/blk)"
                       (name kind) (:n r) (pr-str (into (sorted-map) (:by-class r)))
                       (:content r) (:length r) (:sha r) (:same-bytes r) (:value-ok r)
                       (:deref-ms r) (:mmap-ms r) (/ (* (:mmap-ms r) 1000.0) (:n r))
                       (:view-ms r) (/ (* (:view-ms r) 1000.0) (:n r)))))
    (System/gc))
  (println "CENSUS DONE"))

;; ---------------------------------------------------------------------------
;; --placeprobe: where shipped place!'s time goes, per corpus
;;   plan     eve.flat.place/placement-plan alone (normalize + validate)
;;   full     place! of the hand image into a fresh heap ctx (place-bench's
;;            own domain shape), read-back verified once
;;   noreloc  place! of the same image with :relocs blanked — the pointer
;;            pass removed; the placed value is wrong by construction and
;;            is never read back. full - noreloc prices the pointer pass;
;;            noreloc - plan prices claims + copies.
;; ---------------------------------------------------------------------------

(def ^:private place-caps
  {0 (* 4 1024 1024) 1 (* 4 1024 1024) 2 (* 8 1024 1024) 3 (* 4 1024 1024) 4 (* 2 1024 1024) 5 (* 2 1024 1024)})

(defn- run-placeprobe [_]
  (require 'eve.flat.place-test)
  (let [hand-image (resolve 'eve.flat.place-test/hand-image)
        reopen (resolve 'eve.flat.place-test/reopen)]
    (println "PLACE PROBE — plan / full / no-reloc decomposition, heap ctx")
    (doseq [[kind v] (corpora/corpora :canonical)]
      (let [img (hand-image v)
            nb (count (:blocks img))
            nr (count (:relocs img))
            mk (fn [] (alloc/make-jvm-heap-slab-ctx {:capacities place-caps}))
            plan-ms (bench-min 1 3 #(place/placement-plan img Long/MAX_VALUE))
            full-ms (bench-min 2 5 #(place/place! (mk) img))
            noreloc-ms (bench-min 2 5 #(place/place! (mk) (assoc img :relocs [])))
            ok (let [sio (mk) root (place/place! sio img)]
                 (= v (d/eve->clj (reopen sio root))))]
        (println (format "%-12s blocks=%d relocs=%d plan=%.2fms full=%.2fms (%.0f ns/blk) noreloc=%.2fms (%.0f ns/blk) ptrpass=%.2fms read-back-ok=%s"
                         (name kind) nb nr plan-ms full-ms (/ (* full-ms 1e6) nb)
                         noreloc-ms (/ (* noreloc-ms 1e6) nb) (- full-ms noreloc-ms) ok)))
      (System/gc))
    (println "PLACE PROBE DONE")))

;; ---------------------------------------------------------------------------
;; --detachprobe: sample the detach worker's stack while a slow detach
;; runs; the tally of eve.* frames names the hot path directly.
;; ---------------------------------------------------------------------------

(defn- run-detachprobe [_]
  (let [_mx nil]
    (println "DETACH PROBE — stack sampling during img/detach")
    (doseq [[kind v] (corpora/corpora :canonical)]
      (let [holder (fresh-atom "rd/hold")
            _ (swap! holder (fn [_] v))
            fut (future (img/detach holder))
            tally (atom {})]
        (dotimes [_ 40]
          (when-not (realized? fut)
            (Thread/sleep 100)
            (doseq [[_ stack] (Thread/getAllStackTraces)]
              (let [frames (map (fn [^StackTraceElement e]
                                  (str (.getClassName e) "." (.getMethodName e)))
                                stack)
                    hot (first (filter (fn [f] (str/starts-with? f "eve.flat.image")) frames))]
                (when hot
                  (let [idx (.indexOf frames hot)
                        ctx (subvec (vec frames) idx (min (count frames) (+ idx 3)))]
                    (swap! tally update ctx (fnil inc 0))))))))
        (let [total @fut]
          (println (format "%-12s detach completed, image %d bytes; sampled frames:" (name kind) (alength total)))
          (doseq [[ctx n] (take 6 (sort-by val > @tally))]
            (println (format "  x%-3d %s" n (str/join " <- " ctx))))))
      (System/gc))
    (println "DETACH PROBE DONE")))

;; ---------------------------------------------------------------------------
;; --placesample: like --detachprobe, but the worker loops shipped place!
;; on fresh heap ctxs; sampled frames name execute-plan!'s hot path.
;; ---------------------------------------------------------------------------

(defn- run-placesample [_]
  (require 'eve.flat.place-test)
  (let [hand-image (resolve 'eve.flat.place-test/hand-image)
        _mx nil]
    (println "PLACE SAMPLE — stack sampling during shipped place!")
    (doseq [[kind v] (corpora/corpora :canonical)]
      (let [img (hand-image v)
            mk (fn [] (alloc/make-jvm-heap-slab-ctx {:capacities place-caps}))
            fut (future (loop [] (place/place! (mk) img) (recur)))
            tally (atom {})]
        (dotimes [_ 30]
          (Thread/sleep 100)
          (doseq [[_ stack] (Thread/getAllStackTraces)]
            (let [frames (map (fn [^StackTraceElement e]
                                (str (.getClassName e) "." (.getMethodName e)))
                              stack)
                  hot (first (filter (fn [f] (or (str/starts-with? f "eve.flat.place")
                                                 (str/starts-with? f "eve.deftype_proto.alloc")))
                                     frames))]
              (when hot
                (let [idx (.indexOf frames hot)
                      ctx (subvec (vec frames) idx (min (count frames) (+ idx 3)))]
                  (swap! tally update ctx (fnil inc 0)))))))
        (future-cancel fut)
        (println (format "%-12s sampled frames:" (name kind)))
        (doseq [[ctx n] (take 6 (sort-by val > @tally))]
          (println (format "  x%-3d %s" n (str/join " <- " ctx)))))
      (System/gc))
    (println "PLACE SAMPLE DONE")))

;; ---------------------------------------------------------------------------
;; --s0: STREAM-PLAN phase S0 — the post-order cursor walk (count +
;; checksum, emitting nothing) vs @a in the same session, per corpus.
;; Gate: >= 10x the shipped detach walk AND within 5x of @a totals.
;; ---------------------------------------------------------------------------

(defn run-s0 [{:keys [runs warmup]}]
  (println "STREAM S0 — post-order cursor walk (count+checksum) vs @a")
  (doseq [[kind v] (corpora/corpora :canonical)]
    (let [label (name kind)
          holder (ea/lookup-or-create-mmap-atom!
                  (ea/atom-domain nil :capacities capacities)
                  (str "s0-" label) nil)
          _ (swap! holder (fn [_] v))
          ;; slab access goes through read-pinned: @holder is the
          ;; MATERIALIZED value, and -eve-sio/-root-header-off on a
          ;; plain map throw (this arm's first-run defect, found at
          ;; close-out: the s0 leg had never produced a row).
          res (ea/read-pinned holder
                              (fn [lv] (stream/walk-count (d/-eve-sio lv)
                                                          (d/-root-header-off lv))))
          wms (ea/read-pinned holder
                              (fn [lv] (bench-min warmup runs
                                                  (fn [] (stream/walk-count (d/-eve-sio lv)
                                                                            (d/-root-header-off lv))))))
          ams (bench-min warmup runs (fn [] @holder))]
      (println (pr-str {:corpus label :blocks (:blocks res) :bytes (:bytes res)
                        :checksum (:checksum res) :per-class (:per-class res)
                        :walk-ms wms :deref-ms ams
                        :walk-us-per-block (/ (* wms 1000.0) (:blocks res))}))
      (System/gc)))
  (println "S0 DONE"))

;; ---------------------------------------------------------------------------
;; --s1: STREAM-PLAN phase S1 — the pipe end to end. Per corpus: send
;; (sender -> wire bytes), receive-pure (receive-stream! into a fresh
;; pre-made domain: the protocol's own receiver cost), and the verb
;; (stream-transfer! into a fresh atom, the shipped --transfer arm's
;; shape). Gates printed: values equal; receiver <= 500 ns/block;
;; send+receive-pure >= 20x better than the shipped transfer totals;
;; claims == segments.
;; ---------------------------------------------------------------------------

(def ^:private shipped-transfer-ms
  {:map-10k 1539.74 :vec-100k 28645.78 :records-10k 8371.81 :messages-5k 45284.57})

(defn- fresh-domain-sio []
  (let [dom (ea/atom-domain nil :capacities capacities)
        a (ea/lookup-or-create-mmap-atom! dom "s1/seed" nil)]
    (swap! a (fn [_] {:seed 1}))
    (ea/read-pinned a (fn [lv] (d/-eve-sio lv)))))

(defn- send-of
  "send-stream over holder's pinned slab value (the shipped arms'
   access shape: the walk runs under the pin)."
  [holder]
  (ea/read-pinned holder
                  (fn [lv] (stream/send-stream (d/-eve-sio lv) (d/-root-header-off lv)))))

(defn run-s1 [{:keys [runs warmup]} size-set]
  (println "STREAM S1 — send + receive vs shipped transfer totals")
  (doseq [[kind v] (corpora/corpora size-set)]
    (let [label (name kind)
          holder (fresh-atom (str "s1-" label))
          _ (swap! holder (fn [_] v))
          sent (send-of holder)
          wire (:bytes sent)
          image (ea/read-pinned holder (fn [lv] (tf-detach-value lv)))
          ok (let [t (fresh-atom (str "s1-check-" label))]
               (swap! t (fn [_] (stream/receive-value d/*domain-sio* wire)))
               (= v @t))
          send-ms (bench-min warmup runs (fn [] (send-of holder)))
          reps (+ warmup runs)
          sios (mapv (fn [_] (fresh-domain-sio)) (range reps))
          pick (atom -1)
          recv (atom nil)
          recv-ms (bench-min warmup runs
                             (fn [] (reset! recv (stream/receive-stream!
                                                  (nth sios (swap! pick inc)) wire))))
          verb-ms (bench-min warmup runs
                             (fn [] (stream/stream-transfer! (fresh-atom (str "s1-verb-" label)) holder)))
          r @recv
          total (+ send-ms recv-ms)
          shipped (get shipped-transfer-ms kind)
          recv-ns-blk (/ (* recv-ms 1e6) (:blocks r))]
      (println (pr-str {:corpus label :equal ok :blocks (:blocks r)
                        :segments (:segments r) :claims (:claims r)
                        :claims-eq-segments (= (:claims r) (:segments r))
                        :wire-bytes (alength wire) :image-bytes (alength image)
                        :send-ms send-ms :recv-ms recv-ms
                        :recv-ns-per-block recv-ns-blk
                        :recv-le-500ns (<= recv-ns-blk 500.0)
                        :total-ms total :verb-ms verb-ms
                        :shipped-ms shipped
                        :speedup (when shipped (/ shipped total))
                        :speedup-ge-20x (when shipped (>= (/ shipped total) 20.0))}))
      (System/gc)))
  (println "S1 DONE"))

;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; --s2: STREAM-PLAN phase S2 — the knobs. Per corpus: the v1 wire at
;; no opts; segment budgets 64 KiB / 256 KiB / 1 MiB (segments, claims,
;; receive ms); run thresholds 8 / 16 / 32 (wire bytes, receive ms);
;; wire bytes vs image bytes; and the fragmentation probe —
;; largest allocatable run per class in a fresh control domain vs in
;; a domain that just received the corpus (fragmentation := 1 -
;; after/control). The probe claims and immediately frees.
;; ---------------------------------------------------------------------------

(defn- send-opts [holder opts]
  (ea/read-pinned holder
                  (fn [lv] (stream/send-stream (d/-eve-sio lv) (d/-root-header-off lv) opts))))

(defn- recv-bench [{:keys [runs warmup]} wire]
  (let [reps (+ warmup runs)
        sios (mapv (fn [_] (fresh-domain-sio)) (range reps))
        pick (atom -1)
        stats (atom nil)
        ms (bench-min warmup runs
                      (fn [] (reset! stats (stream/receive-stream!
                                            (nth sios (swap! pick inc)) wire))))]
    {:ms ms :claims (:claims @stats) :segments (:segments @stats)}))

(defn- largest-run [sio ci cap-blocks]
  (loop [lo 1 hi cap-blocks best 0]
    (if (> lo hi)
      best
      (let [mid (quot (+ lo hi) 2)
            r (try (alloc/alloc-run! sio ci mid) (catch Throwable _ nil))]
        (if (and r (== (count r) mid))
          (do (doseq [o r] (try (alloc/-sio-free! sio o) (catch Throwable _ nil)))
              (recur (inc mid) hi mid))
          (recur lo (dec mid) best))))))

(defn- frag-probe [wire classes]
  (let [control (fresh-domain-sio)
        after (fresh-domain-sio)
        _ (stream/receive-stream! after wire)]
    (into {}
          (map (fn [ci]
                 (let [cap-blocks (quot (get capacities ci) (nth alloc/CLASS_BLOCK_SIZES ci))
                       c (largest-run control ci cap-blocks)
                       a (largest-run after ci cap-blocks)]
                   [ci {:control c :after a
                        :frag (when (pos? c) (- 1.0 (/ (double a) (double c))))}])))
          classes)))

(defn run-s2 [{:keys [only] :as o} size-set]
  (println "STREAM S2 — segment budgets, run thresholds, wire vs image, fragmentation")
  (doseq [[kind v] (corpora/corpora size-set)
          :when (or (nil? only) (contains? only kind))]
    (let [label (name kind)
          holder (fresh-atom (str "s2-" label))
          _ (swap! holder (fn [_] v))
          image (ea/read-pinned holder (fn [lv] (tf-detach-value lv)))
          base (send-of holder)
          base-recv (recv-bench o (:bytes base))
          budgets (into {}
                        (map (fn [b]
                               (let [s (send-opts holder {:budget b})
                                     r (recv-bench o (:bytes s))]
                                 (System/gc)
                                 [b {:segments (:segments s) :claims (:claims r)
                                     :recv-ms (:ms r) :wire (alength (:bytes s))}])))
                        [65536 262144 1048576])
          thresholds (into {}
                           (map (fn [t]
                                  (let [s (send-opts holder {:run-threshold t})
                                        r (recv-bench o (:bytes s))]
                                    (System/gc)
                                    [t {:runs (:runs s) :segments (:segments s)
                                        :claims (:claims r) :recv-ms (:ms r)
                                        :wire (alength (:bytes s))}])))
                           [8 16 32])
          classes (vec (keep-indexed (fn [ci c] (when (pos? c) ci)) (:per-class base)))
          frag (frag-probe (:bytes base) classes)]
      (println (pr-str {:corpus label :blocks (:blocks base)
                        :image-bytes (alength image)
                        :wire-v1 (alength (:bytes base))
                        :wire-le-image (<= (alength (:bytes base)) (alength image))
                        :segments (:segments base)
                        :base-recv-ms (:ms base-recv) :base-claims (:claims base-recv)
                        :budgets budgets :thresholds thresholds
                        :fragmentation frag}))
      (System/gc)))
  (println "S2 DONE"))

(defn -main [& args]
  (let [args (vec args)
        opt (fn [k d] (let [i (.indexOf args k)]
                        (if (neg? i) d (parse-long (nth args (inc i))))))
        o {:runs (opt "--runs" 5) :warmup (opt "--warmup" 2)
           :only (let [i (.indexOf args "--only")]
                   (when-not (neg? i)
                     (set (map keyword (str/split (nth args (inc i)) #",")))))}]
    (cond (some #{"--transfer"} args) (run-transfer o)
          (some #{"--s0"} args) (run-s0 o)
          (some #{"--s1"} args) (run-s1 o (if (some #{"--tiny"} args) :tiny :canonical))
          (some #{"--s2"} args) (run-s2 o (if (some #{"--tiny"} args) :tiny :canonical))
          (some #{"--census"} args) (run-census o)
          (some #{"--placeprobe"} args) (run-placeprobe o)
          (some #{"--detachprobe"} args) (run-detachprobe o)
          (some #{"--placesample"} args) (run-placesample o)
          :else (println "usage: --transfer | --s0 | --s1 | --s2 [--tiny] | --census | --placeprobe | --detachprobe | --placesample [--runs N] [--warmup N]")))
  (System/exit 0))
