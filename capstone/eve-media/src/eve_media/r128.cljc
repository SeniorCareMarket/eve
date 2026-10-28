(ns eve-media.r128
  "EBU R128 integrated loudness (ITU-R BS.1770-4) and waveform peaks over interleaved float PCM, portable cljc — the
   :loudness pool's computation, the same on every host that runs it. Input: a host double array of interleaved
   samples, its channel count, its sample rate (48 kHz: the filter coefficients are BS.1770's for 48 kHz; the :pcm job
   resamples to it).

   K-weighting is BS.1770's two biquads (the high shelf, then the high pass); blocks are 400 ms with 75 % overlap; the
   absolute gate is -70 LUFS and the relative gate 10 LU below the absolutely gated mean; channel weights are 1 for
   the first three channels and 1.41 for the surround pair, as in the recommendation.")

(def ^:private stage1 {:b [1.53512485958697 -2.69169618940638 1.19839281085285]
                       :a [-1.69065929318241 0.73248077421585]})
(def ^:private stage2 {:b [1.0 -2.0 1.0]
                       :a [-1.99004745483398 0.99007225036621]})

(defn- channel-weight [c] (if (>= c 3) 1.41 1.0))

(defn- k-weighted-squares
  "The squared K-weighted samples of channel c of the interleaved array xs (n frames, ch channels), as prefix sums:
   a double array of n+1 entries, entry i the sum of the first i squares."
  [^doubles xs ch c n]
  (let [[b0 b1 b2] (:b stage1) [a1 a2] (:a stage1)
        [d0 d1 d2] (:b stage2) [e1 e2] (:a stage2)
        ^doubles out (double-array (inc n))]
    (loop [i 0 x1 0.0 x2 0.0 y1 0.0 y2 0.0 u1 0.0 u2 0.0 acc 0.0]
      (if (< i n)
        (let [x (double (aget xs (+ (* i ch) c)))
              y (- (+ (* b0 x) (* b1 x1) (* b2 x2)) (* a1 y1) (* a2 y2))
              u (- (+ (* d0 y) (* d1 y1) (* d2 y2)) (* e1 u1) (* e2 u2))
              acc (+ acc (* u u))]
          (aset out (inc i) acc)
          (recur (inc i) x x1 y y1 u u1 acc))
        out))))

(defn- log10 [x] (/ (Math/log x) (Math/log 10.0)))

(defn- lufs [power] (+ -0.691 (* 10.0 (log10 power))))

(defn integrated
  "The integrated loudness of xs (interleaved, ch channels, at rate Hz) in LUFS, or nil when no 400 ms block passes the
   gates (silence, or under 400 ms)."
  [^doubles xs ch rate]
  (let [n (quot (alength xs) ch)
        block (long (* 0.4 rate))
        step (long (* 0.1 rate))
        sums (mapv (fn [c] (k-weighted-squares xs ch c n)) (range ch))
        powers (vec (for [start (range 0 (inc (- n block)) step)]
                      (reduce + 0.0 (map-indexed (fn [c ^doubles s]
                                                   (* (channel-weight c)
                                                      (/ (- (aget s (+ start block)) (aget s start)) block)))
                                                 sums))))
        abs-gated (filterv (fn [p] (and (pos? p) (> (lufs p) -70.0))) powers)]
    (when (seq abs-gated)
      (let [rel (- (lufs (/ (reduce + 0.0 abs-gated) (count abs-gated))) 10.0)
            gated (filterv (fn [p] (> (lufs p) rel)) abs-gated)]
        (when (seq gated)
          (lufs (/ (reduce + 0.0 gated) (count gated))))))))

(defn peaks
  "k waveform peaks of xs: the largest absolute sample (any channel) in each of k equal spans, rounded to 1e-4."
  [^doubles xs ch k]
  (let [n (quot (alength xs) ch)
        span (max 1 (quot n k))]
    (vec (for [j (range k)
               :let [lo (* j span) hi (min n (* (inc j) span))]
               :when (< lo hi)]
           (let [m (loop [i (* lo ch) m 0.0]
                     (if (< i (* hi ch)) (recur (inc i) (max m (Math/abs (double (aget xs i))))) m))]
             (/ (Math/round (* m 10000.0)) 10000.0))))))
