(ns eve.runner.vs-bigint-probe
  "Where VS's edwards25519 fast path spends its time: VS's BigInt tower op by op (multiply, mod, a 255-bit modpow), the
   OPS map's byte conversions, the shared eve.net.edwards walk, and the portable limb row for comparison. Times are
   eve.process/now-ms deltas of a compiled VS program under wasmtime."
  (:require [eve.crypto :as c]
            [eve.net.bytes :as b]
            [eve.net.ed25519 :as ed]
            [eve.net.portable-crypto :as p]
            [eve.net.sha512 :as sha]
            [eve.process :as process]))

(def P (-' (reduce (fn [acc _] (*' acc 2)) (bigint 1) (range 255)) 19))
(def A (reduce (fn [acc b] (+' (*' acc 256) b)) (bigint 0) (sha/sha512 (b/utf8 "a"))))
(def B (reduce (fn [acc b] (+' (*' acc 256) b)) (bigint 0) (sha/sha512 (b/utf8 "b"))))
(def A' (mod A P))
(def B' (mod B P))
(def E (mod (reduce (fn [acc b] (+' (*' acc 256) b)) (bigint 0) (subvec (sha/sha512 (b/utf8 "e")) 0 32)) P))

(defn timed [label f reps]
  (let [t0 (process/now-ms)]
    (dotimes [_ reps] (f))
    (println label (/ (- (process/now-ms) t0) (double reps)) "ms")))

(timed ":mul 255x255" (fn [] (*' A' B')) 1000)
(timed ":mul+mod 255" (fn [] (mod (*' A' B') P)) 1000)
(timed ":quot 510/255" (fn [] (quot (*' A' B') P)) 1000)
(timed ":modpow 255-bit exponent (square-and-multiply, ~380 mul+mod)"
       (fn [] (loop [e E acc (bigint 1) base A']
                (if (zero? e) acc
                    (recur (quot e 2) (if (zero? (mod e 2)) acc (mod (*' acc base) P)) (mod (*' base base) P))))) 3)
;; The point-row timings are the earlier probe's (test/eve/runner/vs_fast_probe.vs: ed25519-mul-add 4,231 ms); the
;; four BigInt timings above are what this probe adds. Measured 2026-10-23: mul 0.064 ms, mul+mod 0.488 ms,
;; quot 0.438 ms, a 255-bit modpow 166 ms — the division is the cost (the JVM's BigInteger: ~1 µs a reduction).
