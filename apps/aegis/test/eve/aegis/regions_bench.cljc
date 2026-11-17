(ns eve.aegis.regions-bench
  "The read-regions spike's cost rows, portable: (bench! host-label) prints
   one line per row, each the mean of `reps` eve.process/now-ms deltas after one warm-up call, on the host's crypto
   adapter (hosts.edn): one X25519; one HPKE box sealed and opened (what an admin pays per reader, what a reader pays to
   unwrap); a new lockbox and a rotate at 5 and 50 readers; open-key; a 200-byte message sealed and opened; the broker's
   check of a 50-reader lockbox write and of a region message (eve.aegis.claims/evaluate). SYNTHETIC FIXTURES: seeds
   derived from names, a SHA-256 counter stream for randomness."
  (:require [eve.aegis.claims :as claims]
            [eve.aegis.regions :as r]
            [eve.aegis.regions-spec :as rs]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.process :as process]))

(def ^:private ATOM "aegis.chat.public")

(defn- seed-of [who] (crypto/sha256 (b/utf8 (str "seed:" who))))
(defn- pk-of [who] (vec (crypto/ed25519-public-key (seed-of who))))
(defn- people [n] (mapv (fn [i] (str "member-" i)) (range n)))

(defn- counter-random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n) (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))

(defn- mean-ms [reps f]
  (f)
  (let [t0 (process/now-ms)]
    (dotimes [_ reps] (f))
    (/ (- (process/now-ms) t0) (* 1.0 reps))))

(defn- row [host label reps f] (println "REGIONS-BENCH" host label (str (mean-ms reps f) " ms")))

(defn bench!
  ([host] (bench! host 5))
  ([host reps]
   (let [rnd (counter-random "bench")
         k1 (rnd 32) k2 (rnd 32)
         p5 (mapv pk-of (people 5)) p50 (mapv pk-of (people 50))
         box (r/seal-box ATOM "private" 1 k1 (first p5) (rnd 32))
         lb5 (r/new-lockbox ATOM "private" p5 k1 rnd)
         lb50 (r/new-lockbox ATOM "private" p50 k1 rnd)
         pt (vec (take 200 (cycle (b/utf8 "a private message "))))
         sealed (r/seal-content ATOM "private" 1 k1 (b/utf8 "m-0|member-0") pt (rnd 12))
         spec rs/spec spec-id (claims/spec-id (b/utf8 (pr-str spec)))
         ctx {:spec spec :spec-id spec-id :atom-id ATOM :epoch 1 :now 1700000000 :lockboxes {}
              :digest-of (fn [v] (crypto/sha256 (b/utf8 (str "digest-of:" (hash v)))))
              :principals {"admin" (pk-of "admin") "member-0" (first p50)} :quarantine #{}
              :groups {"chat/room-admins" #{(pk-of "admin")} "chat/private-members" (set p50)} :spent #{}}
         lb-claim (:claim (claims/sign-claim spec spec-id (claims/build-claim spec "write-private-lockbox" {:lockbox lb50 :principal "admin"} ctx) (seed-of "admin")))
         roots {:old-root (crypto/sha256 (b/utf8 "root:before")) :new-root (crypto/sha256 (b/utf8 "root:after"))}
         {:strs [iv ct]} (r/seal-content ATOM "private" 1 k1 (b/utf8 "m-0|member-0") (b/utf8 "hello") (rnd 12))
         msg (:claim (claims/sign-claim spec spec-id (claims/build-claim spec "private-message"
                                                                         {:record {"id" "m-0" "author" "member-0" "ts" 1 "key_epoch" 1 "iv" iv "ct" ct}
                                                                          :principal "member-0"} roots)
                                        (seed-of "member-0")))
         leaf (claims/record-leaf (claims/record-schema spec ["rooms" "private" "messages"]) (:record msg))
         ctx2 (assoc ctx :lockboxes {"keys/private" lb50} :append-of (fn [_] {:old-count 0 :new-count 1 :leaf leaf}))]
     (row host "x25519" reps (fn [] (crypto/x25519 (rnd 32) (into [9] (repeat 31 0)))))
     (row host "hpke-seal (one box)" reps (fn [] (r/seal-box ATOM "private" 1 k1 (first p5) (rnd 32))))
     (row host "hpke-open (one box)" reps (fn [] (r/open-box ATOM "private" 1 box (seed-of "member-0"))))
     (row host "new-lockbox 5" reps (fn [] (r/new-lockbox ATOM "private" p5 k1 rnd)))
     (row host "rotate 5 -> 4" reps (fn [] (r/rotate-lockbox lb5 ATOM "private" (pop p5) k1 k2 rnd :grant)))
     (row host "new-lockbox 50" 1 (fn [] (r/new-lockbox ATOM "private" p50 k1 rnd)))
     (row host "open-key (5)" reps (fn [] (r/open-key lb5 ATOM "private" (seed-of "member-1"))))
     (row host "message seal 200 B" reps (fn [] (r/seal-content ATOM "private" 1 k1 (b/utf8 "m-0|member-0") pt (rnd 12))))
     (row host "message open 200 B" reps (fn [] (r/open-content ATOM "private" 1 k1 (b/utf8 "m-0|member-0") sealed)))
     (row host "broker: evaluate lockbox write (50)" reps (fn [] (claims/evaluate ctx lb-claim)))
     (row host "broker: evaluate region message" reps (fn [] (claims/evaluate ctx2 msg)))
     (println "REGIONS-BENCH" host "checks" (:ok (claims/evaluate ctx lb-claim)) (:ok (claims/evaluate ctx2 msg))))))
