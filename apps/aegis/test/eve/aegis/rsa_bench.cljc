(ns eve.aegis.rsa-bench
  "aegis's RSA-2048 cost rows, portable: (run! path) prints one line per
   row for :adapter (the host's crypto feature) or :portable-client (eve.net.bignum through a cached Montgomery
   context; the issuer's row is left out there — it is minutes on the portable path). Times are eve.process/now-ms
   deltas over the RFC 9474 A.3 key and encoded message; run on a host, named in the report with its path."
  (:require [eve.aegis.rsabssa :as rsa]
            [eve.aegis.rsa-vectors :as vectors]
            [eve.net.bignum :as bn]
            [eve.net.bytes :as b]
            [eve.process :as process]))

(defn run! [path]
  (let [v (nth (:rfc9474 vectors/vectors) 2)
        n (b/unhex (:n v)) e (b/unhex (:e v)) d (b/unhex (:d v)) m (b/unhex (:encoded_msg v)) inv (b/unhex (:inv v))
        adapter? (= path :adapter)
        A (if adapter? (rsa/adapter-arith) (bn/cached-arith n))
        time-it (fn [label f reps]
                  (f)
                  (let [t0 (process/now-ms)] (dotimes [_ reps] (f))
                       (println (str label ": " (/ (- (process/now-ms) t0) (double reps)) " ms [" (name path) "]"))))]
    (time-it "modpow e=65537 (verify, blind)" (fn [] ((:modpow A) m e n)) (if adapter? 50 3))
    (time-it "modmul" (fn [] ((:modmul A) m inv n)) (if adapter? 200 5))
    (time-it "modinv (blind)" (fn [] ((:modinv A) inv n)) (if adapter? 20 1))
    (when adapter? (time-it "modpow d (issuer sign, no CRT)" (fn [] ((:modpow A) m d n)) 2))))
