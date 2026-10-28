(ns eve.runner.vs-regions-probe
  "A compiled VS program measuring the read-regions spike's operations on ValueScript (aegis/docs/read-regions-spike/
   REPORT.md), one operation per run so a run's peak memory is that operation's (a compiled program frees only what an
   atom's write replaces): the file probe.txt in the preopened directory names it (no newline) —
     none        the namespaces loaded, nothing run (the floor)
     x25519      one eve.crypto/x25519 (the crypto adapter's row over VS's BigInt)
     ed2x        one Ed25519 → X25519 public key (eve.aegis.regions/x25519-public)
     seal        one HPKE seal of a 32-byte key to a member (what a lockbox box costs)
     open        one HPKE open of it (what a reader pays to unwrap the region key)
     lockbox2    a new lockbox for 2 readers        lockbox5   … for 5 readers
     openkey     open-key from a 2-reader lockbox (the box, the commitment check)
     content     seal-content + open-content of a 200-byte message
     rotate5     rotate a 5-reader lockbox (5 boxes, the history link) and walk back one epoch
     evaluate    the broker's check of a lockbox write and of a region message (eve.aegis.claims/evaluate)
   Each prints its wall time. Randomness is a SHA-256 counter stream (a compiled program has no entropy source).
   Compile from a directory holding ed25519.wasm, the crypto/vs-kernel adapter and eve's test root on VS_CLJ_ROOTS:
     VS_CLJ_ROOTS=<eve>/adapters/crypto/vs-kernel/src:<eve>/test vs compile <eve>/test/eve/runner/vs_regions_probe.vs -o regions_probe.wasm
     printf seal > probe.txt; wasmtime run --dir .::/ -W threads=y -W shared-memory=y -W exceptions=y -W max-wasm-stack=67108864 regions_probe.wasm"
  (:require [eve.aegis.claims :as claims]
            [eve.aegis.hpke :as hpke]
            [eve.aegis.regions :as r]
            [eve.aegis.regions-spec :as rs]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.process :as process]))

(defn- t [label f]
  (let [t0 (process/now-ms) v (f) t1 (process/now-ms)]
    (println label :ms (- t1 t0))
    v))

(defn- counter-random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n) (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))

(def ^:private ATOM "aegis.chat.public")

(defn -main []
  (let [probe (slurp "probe.txt")
        rnd (counter-random "regions")
        seed (fn [who] (crypto/sha256 (b/utf8 (str "seed:" who))))
        pk (fn [who] (vec (crypto/ed25519-public-key (seed who))))
        people ["alice" "bob" "carol" "dave" "erin"]
        k1 (rnd 32) k2 (rnd 32)]
    (println :probe probe)
    (cond
      (= probe "x25519") (t :x25519 (fn [] (crypto/x25519 (rnd 32) (into [9] (repeat 31 0)))))
      (= probe "ed2x") (t :ed2x (fn [] (r/x25519-public (pk "alice"))))
      (= probe "seal") (t :seal (fn [] (r/seal-box ATOM "private" 1 k1 (pk "alice") (rnd 32))))
      (= probe "open") (let [box (t :seal (fn [] (r/seal-box ATOM "private" 1 k1 (pk "alice") (rnd 32))))]
                         (println :opened (= k1 (t :open (fn [] (r/open-box ATOM "private" 1 box (seed "alice")))))))
      (= probe "lockbox2") (t :lockbox2 (fn [] (r/new-lockbox ATOM "private" (mapv pk (take 2 people)) k1 rnd)))
      (= probe "lockbox5") (t :lockbox5 (fn [] (r/new-lockbox ATOM "private" (mapv pk people) k1 rnd)))
      (= probe "openkey") (let [lb (t :lockbox2 (fn [] (r/new-lockbox ATOM "private" (mapv pk (take 2 people)) k1 rnd)))]
                            (println :opened (= k1 (t :open-key (fn [] (r/open-key lb ATOM "private" (seed "bob")))))))
      (= probe "content") (let [pt (vec (take 200 (cycle (b/utf8 "a private message "))))
                                sealed (t :seal-content (fn [] (r/seal-content ATOM "private" 1 k1 (b/utf8 "m-0|alice") pt (rnd 12))))]
                            (println :opened (= pt (t :open-content (fn [] (r/open-content ATOM "private" 1 k1 (b/utf8 "m-0|alice") sealed))))))
      (= probe "rotate5") (let [lb (t :lockbox5 (fn [] (r/new-lockbox ATOM "private" (mapv pk people) k1 rnd)))
                                lb2 (t :rotate5 (fn [] (r/rotate-lockbox lb ATOM "private" (mapv pk (take 4 people)) k1 k2 rnd :grant)))]
                            (println :walked-back (= k1 (t :key-at (fn [] (r/key-at lb2 ATOM "private" k2 1))))))
      (= probe "evaluate")
      (let [spec rs/spec
            spec-id (claims/spec-id (b/utf8 (pr-str spec)))
            roster (set (mapv pk (take 2 people)))
            lb (r/new-lockbox ATOM "private" (mapv pk (take 2 people)) k1 rnd)
            ctx {:spec spec :spec-id spec-id :atom-id ATOM :epoch 1 :now 1700000000
                 :digest-of (fn [_] (crypto/sha256 (b/utf8 "roster")))
                 :lockboxes {} :principals {"admin" (pk "admin") "alice" (pk "alice")} :quarantine #{}
                 :groups {"chat/room-admins" #{(pk "admin")} "chat/private-members" roster} :spent #{}}
            claim (:claim (claims/sign-claim spec spec-id (claims/build-claim spec "write-private-lockbox" {:lockbox lb :principal "admin"} ctx) (seed "admin")))
            v (t :evaluate-lockbox (fn [] (claims/evaluate ctx claim)))
            {:strs [iv ct]} (r/seal-content ATOM "private" 1 k1 (b/utf8 "m-0|alice") (b/utf8 "hello") (rnd 12))
            rec {"id" "m-0" "author" "alice" "ts" 1 "key_epoch" 1 "iv" iv "ct" ct}
            roots {:old-root (crypto/sha256 (b/utf8 "root:before")) :new-root (crypto/sha256 (b/utf8 "root:after"))}
            msg (:claim (claims/sign-claim spec spec-id (claims/build-claim spec "private-message" {:record rec :principal "alice"} roots)
                                           (seed "alice")))
            leaf (claims/record-leaf (claims/record-schema spec ["rooms" "private" "messages"]) rec)
            ctx2 (assoc ctx :lockboxes {"keys/private" lb} :append-of (fn [_] {:old-count 0 :new-count 1 :leaf leaf}))
            v2 (t :evaluate-message (fn [] (claims/evaluate ctx2 msg)))]
        (println :lockbox-ok (:ok v) (:reason v) :message-ok (:ok v2) (:reason v2)))
      :else nil)
    (println :done probe)))

(-main)
