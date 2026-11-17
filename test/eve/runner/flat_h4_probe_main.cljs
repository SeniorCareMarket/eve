(ns eve.runner.flat-h4-probe-main
  "Lane H4 diagnostic probe (not a test): discriminates the Node suite's
   three red classes at the current tip.
   P-A: build the nested-map row value at the runner geometry — which
        class exhausts, if any.
   P-B: the scalar-detach path step by step, with JS stack traces (the
        suite's reporter prints none).
   P-C: on a RESET slab (ps/reset-all-slabs! — bitmaps zeroed, free
        counts restored; no prior arm's allocations survive it):
        fill class 0, free Z2.2's pattern, read the pattern back through
        alloc/allocated?, then alloc-run! of 41 and of 40. Discriminates
        cross-test slab pollution from a free/allocated?/run-claim defect.
   Usage: clojure -M:node-test -m shadow.cljs.devtools.cli compile flat-h4-probe
          node target/flat-h4-probe/main.js"
  (:require [eve.process-slab :as ps]
            [eve.deftype-proto.alloc :as alloc]
            [eve.deftype-proto.data :as d]
            [eve.deftype-proto.serialize :as ser]
            [eve.codec :as codec]
            [eve.array :as arr]
            [eve.dataset :as ds]
            [eve.deftype.slab-runtime :as slab-rt]
            [eve.flat.image :as img]
            [eve.flat.image-node-test :as t]))

(defn- step [label f]
  (try (let [v (f)] (println label "OK" (pr-str v)) v)
       (catch :default e
         (println label "THREW" (.-message e))
         (println (.-stack e))
         nil)))

(defn- off [ci bi] (alloc/encode-slab-offset ci bi))

(defn- allocated-range [sio ci]
  (into #{} (filter #(alloc/allocated? sio (off ci %))) (range 512)))

(defn- build-plain*
  "The suite's build-plain, replicated (it is private to the test ns):
   a plain collection written into sio by the direct writers."
  [sio v]
  (let [kind (cond (map? v) :map (set? v) :set (vector? v) :vec :else :list)
        off (codec/write-collection! kind sio v)
        tg (ser/read-header-tag sio off)]
    ((ser/get-header-constructor tg) sio off)))

(defn- run-p-c []
  ;; P-C on the (already initialized) slab, reset synchronously to a
  ;; virgin state first: the discriminator is pollution vs allocator,
  ;; and reset-all-slabs! is the synchronous fresh-domain primitive.
  (ps/reset-all-slabs!)
  (let [sio (alloc/heap-domain-sio! {})
        ci 0
        size (nth alloc/CLASS_BLOCK_SIZES ci)
        ;; the fill door is the slab's per-class batch-alloc: the
        ;; size-routed -sio-alloc! spills to the next class on
        ;; exhaustion by design (measured: 32,256 blocks = all classes)
        offs (vec (array-seq (ps/batch-alloc size 512)))]
    (println "P-C fill count:" (count offs)
             "classes:" (pr-str (frequencies (map alloc/decode-class-idx offs))))
    (let [by-idx (into {} (map (fn [o] [(alloc/decode-block-idx o) o]) offs))
          freed (into (set (range 37 77)) [5 90 100])]
      (doseq [bi freed] (alloc/-sio-free! sio (by-idx bi)))
      (let [before (allocated-range sio ci)
            free-now (set (filter #(not (contains? before %)) (range 512)))]
        (println "P-C pattern free after frees:" (= freed free-now)
                 "| free set:" (pr-str (sort free-now)))
        (println "P-C alloc-run! 41:" (pr-str (alloc/alloc-run! sio ci 41)))
        (let [run (alloc/alloc-run! sio ci 40)]
          (println "P-C alloc-run! 40 first:" (pr-str (first run))
                   "count:" (count run)
                   "expected first:" (pr-str (off ci 37))))))))

(defn main []
  ;; Flat on purpose: NO -> threading over (.then …)/(.catch …) and no
  ;; nested chains. The first probe build compiled its nested chains
  ;; to `}).catch());` — .catch invoked on the handler fn itself with
  ;; no argument (measured in the compiled JS on the venue) — while
  ;; the suite main's single flat chain compiled correctly. Named
  ;; promises, statement-level handlers only.
  (let [p (ps/init! :force true :capacities t/slab-capacities)]
    (.then p
           (fn [_]
             (let [sio (alloc/heap-domain-sio! {})]
               (println "== P-A build at runner geometry")
               (step "P-A nested-map build"
                     (fn []
                       (binding [d/*domain-sio* sio]
                         (let [o (codec/write-collection!
                                  :map sio {:id 42 :tags #{:a :b} :items (vec (range 200))
                                            :nested {:k {:k2 [1 2.5 "three" :four]}}})
                               tg (ser/read-header-tag sio o)]
                           ((ser/get-header-constructor tg) sio o)))))
               (println "== P-B scalar path, step by step")
               (let [off (step "P-B1 value->scalar-block!"
                               (fn [] (binding [d/*domain-sio* sio]
                                        (alloc/value->scalar-block! sio "hello scalar"))))]
                 (when off
                   (let [im (step "P-B2 detach-image" (fn [] (img/detach-image sio off)))]
                     (when im
                       (step "P-B3 open+read"
                             (fn [] (img/image-value (img/open-image (img/image-bytes im)))))))))
               (println "== P-C pattern on a reset slab")
               (step "P-C" run-p-c)
               (println "== P-E dataset image forensics")
               (step "P-E"
                     (fn []
                       (binding [d/*domain-sio* sio]
                         (let [xs (vec (range 5))
                               v (ds/dataset sio {:price (arr/eve-array sio :float64 (mapv double xs))
                                                  :qty (arr/eve-array sio :int32 xs)})
                               bs (img/image-bytes (img/detach-image v))
                               u32 (fn [o] (bit-or (aget bs o)
                                                   (bit-shift-left (aget bs (+ o 1)) 8)
                                                   (bit-shift-left (aget bs (+ o 2)) 16)
                                                   (bit-shift-left (aget bs (+ o 3)) 24)))
                               root (u32 12)]
                           (println "P-E root off:" root
                                    "root words:" (pr-str (mapv u32 (range root (+ root 64) 4))))
                           ;; the serialized-field slots: scan the root
                           ;; block's words for values that land inside
                           ;; the image and try slab-read-serialized at
                           ;; each candidate slot offset
                           (let [vsio (img/image-view (img/open-image bs))]
                             (doseq [slot (range 0 64 4)]
                               (let [w (u32 (+ root slot))]
                                 (when (and (<= 32 w) (< w (alength bs)))
                                   (println "P-E slot" slot "-> word" w
                                            "serialized:"
                                            (pr-str (try (slab-rt/slab-read-serialized
                                                          vsio root slot)
                                                         (catch :default e
                                                           (str "THREW " (.-message e))))))))))))))
               (println "== P-D dataset detach census (the suite's own construction)")
               (step "P-D"
                     (fn []
                       (binding [d/*domain-sio* sio]
                         (let [xs (vec (range 5))
                               v (ds/dataset sio {:price (arr/eve-array sio :float64 (mapv double xs))
                                                  :qty (arr/eve-array sio :int32 xs)})
                               bs (img/image-bytes (img/detach-image v))
                               u32 (fn [o] (bit-or (aget bs o)
                                                   (bit-shift-left (aget bs (+ o 1)) 8)
                                                   (bit-shift-left (aget bs (+ o 2)) 16)
                                                   (bit-shift-left (aget bs (+ o 3)) 24)))
                               symtab (u32 20) shapetab (u32 24)
                               iv (img/image-value (img/open-image bs))]
                           (println "P-D source dataset?" (ds/dataset? v)
                                    "eve->clj:" (pr-str (d/eve->clj v)))
                           (println "P-D image len:" (alength bs)
                                    "blocks:" (quot (- shapetab symtab) 8)
                                    "relocs:" (quot (- (alength bs) shapetab) 12))
                           (println "P-D image dataset?" (ds/dataset? iv)
                                    "type:" (pr-str (type iv)))
                           (println "P-D image eve->clj:" (pr-str (d/eve->clj iv)))
                           (println "P-D image column :qty:"
                                    (pr-str (try (vec (ds/column iv :qty))
                                                 (catch :default e
                                                   (str "THREW " (.-message e))))))
                           ;; control: the col-map alone, no Dataset wrapper
                           (let [m (build-plain* sio {:price (arr/eve-array sio :float64 (mapv double xs))
                                                      :qty (arr/eve-array sio :int32 xs)})
                                 mv (img/image-value (img/open-image (img/image-bytes (img/detach-image m))))]
                             (println "P-D control map-of-arrays eve->clj:"
                                      (pr-str (d/eve->clj mv))))))))
               (js/process.exit 0))))
    (.catch p
            (fn [e]
              (println "INIT THREW" (.-message e))
              (println (.-stack e))
              (js/process.exit 1)))))
