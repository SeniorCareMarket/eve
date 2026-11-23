(ns eve.flat.perf-suite
  "PERF SUITE — every flat objective in one same-session battery
   (John, Nov 12 2026). Per corpus, all numbers from the same JVM and
   the same pinned value, min-of-N:

     SIZES    image / stream / packed / transit bytes (storage + wire)
     WIRE     stream send + receive, detach + place, transit encode +
              decode, pack (row corpora)
     DELIVERY @atom materialization (eve->clj, today's deref) vs
              detach (the eve->flat candidate) vs stream atom->atom
     ACCESS   random probes (get/nth) over heap / slab / image-in-place
     SCAN     full traversal over heap / slab / image / packed rows
     CACHE    a second identical random-access pass over the same
              delivered object (deliver once, read many)

   Forms that do not apply to a corpus print NOT-RUN with the reason.
   Usage: clojure.main -m eve.flat.perf-suite [--tiny] [--runs N]"
  (:require [eve.atom :as ea]
            [eve.deftype-proto.data :as d]
            [eve.flat.corpora :as corpora]
            [eve.flat.image :as img]
            [eve.flat.stream :as stream]
            [eve.flat.transfer :as tf]
            [eve.flat.chunk :as chunk]
            [eve.flat.freeze :as freeze]
            [cognitect.transit :as transit])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def ^:private capacities
  {0 4194304 1 8388608 2 16777216 3 8388608 4 4194304 5 4194304})

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
  (ea/lookup-or-create-mmap-atom!
   (ea/atom-domain nil :capacities capacities) name nil))

(defn- fresh-domain-sio []
  (let [a (fresh-atom (str "ps-seed-" (rand-int 1000000000)))]
    (swap! a (fn [_] {:seed 1}))
    (ea/read-pinned a d/-eve-sio)))

(defn- transit-bytes ^bytes [v]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :msgpack) v)
    (.toByteArray out)))

(defn- transit-read [^bytes bs]
  (transit/read (transit/reader (ByteArrayInputStream. bs) :msgpack)))

(defn- probes
  "1000 deterministic probe keys/indices for the corpus kind."
  [kind n]
  (case kind
    :map-10k (mapv (fn [i] (mod (* i 7919) n)) (range 1000))
    :vec-100k (mapv (fn [i] (mod (* i 7919) n)) (range 1000))
    :records-10k (mapv (fn [i] (mod (* i 7919) n)) (range 1000))
    :messages-5k (mapv (fn [i] (mod (* i 7919) n)) (range 1000))))

(defn- probe-get
  "One random-access pass: sum of the probed cells (forces the reads)."
  [kind v ks]
  (case kind
    :map-10k (reduce (fn [s k] (+ s (long (get v k 0)))) 0 ks)
    :vec-100k (reduce (fn [s i] (+ s (long (nth v i)))) 0 ks)
    :records-10k (reduce (fn [s i] (+ s (long (:id (nth v i))))) 0 ks)
    :messages-5k (reduce (fn [s i] (+ s (long (count (:text (nth v i)))))) 0 ks)))

(defn- scan-all
  "One full traversal: a checksum over every element."
  [kind v]
  (case kind
    :map-10k (reduce (fn [s e] (+ s (long (val e)))) 0 v)
    :vec-100k (reduce (fn [s x] (+ s (long x))) 0 v)
    :records-10k (reduce (fn [s r] (+ s (long (:id r)))) 0 v)
    :messages-5k (reduce (fn [s m] (+ s (long (count (:text m))))) 0 v)))

(defn- run-corpus [kind v {:keys [runs warmup]}]
  (let [label (name kind)
        holder (fresh-atom (str "ps-" label))
        _ (swap! holder (fn [_] v))
        heap @holder
        n (count v)
        ks (probes kind n)
        ;; sizes (untimed)
        image (ea/read-pinned holder (fn [lv] (img/open-image (img/detach lv))))
        image-bytes (img/image-length image)
        wire (ea/read-pinned holder
                             (fn [lv] (:bytes (stream/send-stream
                                               (d/-eve-sio lv)
                                               (d/-root-header-off lv)))))
        tb (transit-bytes v)
        row-corpus? (contains? #{:records-10k :messages-5k} kind)
        packed (when row-corpus? (chunk/pack-all v))
        packed-bytes (when packed
                       (reduce (fn [s ^bytes c] (+ s (alength c))) 0 packed))
        ;; wire timings
        send-ms (ea/read-pinned
                 holder
                 (fn [lv] (bench-min warmup runs
                                     (fn [] (stream/send-stream
                                             (d/-eve-sio lv)
                                             (d/-root-header-off lv))))))
        recv-sios (mapv (fn [_] (fresh-domain-sio)) (range (+ warmup runs)))
        pick (atom -1)
        recv-ms (bench-min warmup runs
                           (fn [] (stream/receive-stream!
                                   (nth recv-sios (swap! pick inc)) wire)))
        detach-ms (ea/read-pinned
                   holder
                   (fn [lv] (bench-min warmup runs (fn [] (img/detach lv)))))
        place-target (fresh-atom (str "ps-place-" label))
        place-ms (bench-min warmup runs
                            (fn [] (tf/place-image! place-target
                                                    (java.util.Arrays/copyOf
                                                     (img/image-bytes image)
                                                     (int (img/image-length image))))))
        tenc-ms (bench-min warmup runs (fn [] (transit-bytes v)))
        tdec-ms (bench-min warmup runs (fn [] (transit-read tb)))
        pack-ms (when row-corpus?
                  (bench-min warmup runs (fn [] (chunk/pack-all v))))
        ;; delivery
        deref-ms (bench-min warmup runs (fn [] @holder))
        xfer-target (fresh-atom (str "ps-xfer-" label))
        xfer-ms (bench-min warmup runs
                           (fn [] (stream/stream-transfer! xfer-target holder)))
        ;; access + scan: heap / slab / image
        iv (img/image-value image)
        acc (fn [vv] (let [t0 (System/nanoTime)
                           s (probe-get kind vv ks)]
                       {:ms (/ (- (System/nanoTime) t0) 1e6) :sum s}))
        heap-acc (acc heap)
        slab-acc (ea/read-pinned holder acc)
        img-acc (acc iv)
        img-acc2 (acc iv)
        heap-scan (bench-min warmup runs (fn [] (scan-all kind heap)))
        slab-scan (ea/read-pinned
                   holder
                   (fn [lv] (bench-min warmup runs (fn [] (scan-all kind lv)))))
        img-scan (bench-min warmup runs (fn [] (scan-all kind iv)))]
    (println (pr-str {:corpus label :n n
                      :sizes {:image image-bytes :stream (alength wire)
                              :packed (or packed-bytes :not-run-row-form-only)
                              :transit (alength tb)}
                      :wire-ms {:send send-ms :receive recv-ms
                                :detach detach-ms :place place-ms
                                :transit-enc tenc-ms :transit-dec tdec-ms
                                :pack (or pack-ms :not-run)}
                      :delivery-ms {:deref-materialize deref-ms
                                    :detach-flat detach-ms
                                    :atom->atom-stream xfer-ms}
                      :access-ns-per {:heap (/ (* (:ms heap-acc) 1e6) 1000.0)
                                      :slab (/ (* (:ms slab-acc) 1e6) 1000.0)
                                      :image (/ (* (:ms img-acc) 1e6) 1000.0)
                                      :image-warm (/ (* (:ms img-acc2) 1e6) 1000.0)
                                      :sums [(:sum heap-acc) (:sum slab-acc) (:sum img-acc)]}
                      :scan-ms {:heap heap-scan :slab slab-scan :image img-scan}}))
    (flush)))

(defn- assoc-probe
  "One edit, per corpus kind, at a deterministic place."
  [kind v i]
  (case kind
    :map-10k (assoc v i -1)
    :vec-100k (assoc v i -1)
    :records-10k (assoc-in v [i :id] -1)
    :messages-5k (assoc-in v [i :text] "x")))

(defn- try-op [f]
  (try (f) #_{:splint/disable [lint/catch-throwable]}   (catch Throwable t [:throws (type t)])))

(defn heap-battery
  "S4 / R7: the flat heap object an atom delivers, against the
   Clojure heap object and the in-atom slab value — same value,
   same session: random-access probes, full scan, hash, equality,
   one assoc, footprint. assoc on a delivered batch handle is
   reported as it behaves (the S0 gap: it throws; upgrade goes
   through the FrozenValue path, costed in the plan's S2)."
  [kind v {:keys [runs warmup]}]
  (let [label (name kind)
        holder (fresh-atom (str "hb-" label))
        _ (swap! holder (fn [_] v))
        heap @holder
        flat (freeze/deliver-flat v)
        n (count v)
        ks (probes kind n)
        i0 (first ks)
        row (fn [_op f]
              {:heap (bench-min warmup runs (fn [] (f heap)))
               :flat (try-op (fn [] (bench-min warmup runs (fn [] (f flat)))))})
        slab-acc (ea/read-pinned holder
                                 (fn [lv] (bench-min warmup runs
                                                     (fn [] (probe-get kind lv ks)))))
        slab-scan (ea/read-pinned holder
                                  (fn [lv] (bench-min warmup runs
                                                      (fn [] (scan-all kind lv)))))
        image-bytes (ea/read-pinned holder
                                    (fn [lv] (img/image-length (img/open-image (img/detach lv)))))
        chunk-bytes (reduce (fn [s ^bytes c] (+ s (alength c))) 0 (chunk/pack-all v))
        tb (transit-bytes v)]
    (println (pr-str {:heap-battery label :n n
                      :counts {:heap (count heap) :flat (count flat)}
                      :access-ns-per {:heap (/ (* (bench-min warmup runs (fn [] (probe-get kind heap ks))) 1e6) 1000.0)
                                      :flat (/ (* (bench-min warmup runs (fn [] (probe-get kind flat ks))) 1e6) 1000.0)
                                      :slab (/ (* slab-acc 1e6) 1000.0)}
                      :scan-ms {:heap (bench-min warmup runs (fn [] (scan-all kind heap)))
                                :flat (bench-min warmup runs (fn [] (scan-all kind flat)))
                                :slab slab-scan}
                      :hash-ms (row :hash hash)
                      :equality-ms (row :eq (fn [x] (= x v)))
                      :assoc-ms (row :assoc (fn [x] (assoc-probe kind x i0)))
                      :footprint-bytes {:flat-chunk chunk-bytes
                                        :slab-image image-bytes
                                        :heap-transit-proxy (alength tb)}}))
    (flush)))

(defn -main [& args]
  (let [size-set (if (some #{"--tiny"} args) :tiny :canonical)
        runs (let [i (.indexOf (vec args) "--runs")]
               (if (>= i 0) (Integer/parseInt (nth args (inc i))) 3))
        only (let [i (.indexOf (vec args) "--only")]
               (when (>= i 0) (nth args (inc i))))
        heap-only? (some #{"--heap"} args)
        opts {:runs runs :warmup 1}]
    (println "PERF SUITE" (name size-set) "runs" runs (when only (str "only " only)) (when heap-only? "heap-battery"))
    (doseq [[kind v] (corpora/corpora size-set)
            :when (or (nil? only) (= only (name kind)))]
      (if heap-only?
        (heap-battery kind v opts)
        (run-corpus kind v opts)))
    (println "PERF SUITE DONE"))
  (System/exit 0))
