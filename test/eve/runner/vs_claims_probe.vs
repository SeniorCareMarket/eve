(ns eve.runner.vs-claims-probe
  "A compiled VS program: eve.aegis.claims (the clear evaluator) on ValueScript — one post-message claim built, signed
   and evaluated against the chat spec read from spec.vs, each step timed. Every step runs inside -main (ValueScript's
   compiler evaluates a top-level def's init as it compiles it, so a def that signs would sign in the compiler). Run
   with the repository root preopened:
     vs compile eve/test/eve/runner/vs_claims_probe.vs -o <out>.wasm
     wasmtime run --dir .::/ -W threads=y -W shared-memory=y -W exceptions=y -W max-wasm-stack=67108864 <out>.wasm"
  (:require [eve.aegis.ballot :as ballot]
            [eve.aegis.claims :as claims]
            [eve.aegis.merkle :as merkle]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.process :as process]))

(defn- t [label f]
  (let [t0 (process/now-ms) r (f) t1 (process/now-ms)]
    (println label :ms (- t1 t0))
    r))

(defn -main []
  (let [spec (t :read-spec (fn [] (read-string (slurp "src/aegis/chat/spec.vs"))))
        spec-id (t :spec-id (fn [] (claims/spec-id (b/utf8 (slurp "src/aegis/chat/spec.vs")))))
        seed (crypto/sha256 (b/utf8 "seed:alice"))
        pk (t :ed25519-public-key (fn [] (crypto/ed25519-public-key seed)))
        rec {"id" "m-1" "author" "alice" "ts" 1700000000 "content_commitment" "00" "ciphertext" "00" "ivB64" "AAAA"}
        roots {:old-root (crypto/sha256 (b/utf8 "root:before")) :new-root (crypto/sha256 (b/utf8 "root:after"))}
        claim0 (t :build-claim (fn [] (claims/build-claim spec "post-message" {:record rec :principal "alice"} roots)))
        claim (t :sign-claim (fn [] (:claim (claims/sign-claim spec spec-id claim0 seed))))
        leaf (claims/record-leaf (claims/record-schema spec ["rooms" "public" "messages"]) rec)
        wire (t :claim->wire (fn [] (claims/claim->wire claim)))
        back (t :wire->claim (fn [] (claims/wire->claim wire)))
        ;; the kernel's two digests stubbed: the append as read from the two states, a fixed roster digest
        ctx {:spec spec :spec-id spec-id :atom-id "aegis.chat.public" :epoch 0 :now 1700000000
             :append-of (fn [_] {:old-count 0 :new-count 1 :leaf leaf}) :digest-of (fn [_] (crypto/sha256 (b/utf8 "roster")))
             :principals {"alice" pk} :quarantine #{} :groups {"_broker_defaults/trusted-anonymous" #{pk}} :spent #{}}
        r (t :evaluate (fn [] (claims/evaluate ctx back)))
        r2 (t :evaluate-forged (fn [] (claims/evaluate ctx (assoc-in back [:record "author"] "bob"))))]
    (println :spec-id (b/hex spec-id))
    ;; a ballot, its fixture made on babashka (vs_claims_probe_ballot.edn: the partially-blind draft's key, a SHA-256
    ;; counter stream as randomness): the client's blind and finalize redone here with the same randomness, and the
    ;; broker's verification of the spend
    (let [fx (read-string (slurp "eve/test/eve/runner/vs_claims_probe_ballot.edn"))
          pk {:n (b/unhex (:n fx)) :e (b/unhex (:e fx))}
          info (b/unhex (:info fx))
          c (atom 0)
          random (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n)
                                            (recur (into out (crypto/sha256 (b/utf8 (str "probe-" (swap! c inc)))))))))
          b0 (t :ballot-blind (fn [] (ballot/new-ballot pk info random)))
          bal (t :ballot-finalize (fn [] (ballot/finish-ballot pk info b0 (b/unhex (:blind-sig fx)))))
          auth {:kind :ballot :token-pk (b/unhex (:token-pk fx)) :token-sig (b/unhex (:token-sig fx)) :sig (b/unhex (:sig fx))}
          ok (t :ballot-verify (fn [] (ballot/verify-ballot pk info auth (vec (repeat 32 7)))))]
      (println :ballot-blinded-equal (= (:blinded fx) (b/hex (:blinded b0))))
      (println :ballot-token-sig-equal (= (:token-sig fx) (b/hex (:token-sig bal))))
      (println :ballot-verified ok))
    (println :ok (:ok r) :reason (:reason r))
    (println :forged-refused (= false (:ok r2)) :reason (:reason r2))))

(-main)
