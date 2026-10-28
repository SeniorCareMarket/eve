(ns eve.aegis.claims-bench
  "aegis's clear-evaluation cost rows (the aegis-simple-mainline lane's perf table,
   docs/port-gap-closure/coordination/reports/aegis-simple-mainline-2026-10-23.md §perf), portable: (bench! host-label)
   prints one line per row — the client's work (build a claim, sign it) and the broker's (evaluate it) per branch
   family: a named write (post-message), a ballot vote (blind, the issuer's blind signature, finalize, and the broker's
   evaluation, which verifies the ballot), a ring vote at the roster sizes the lane's worlds use (2: the steroids world,
   5: the seed) and the spike's 8, and a session post. Each row is the mean of `reps` eve.process/now-ms deltas after one
   warm-up call, on the host's crypto adapter (hosts.edn). SYNTHETIC FIXTURES, as claims-test's: seeds derived from
   names, the partially-blind draft's issuer key (no CRT), a SHA-256 counter stream for randomness, stub state digests."
  (:require [eve.aegis.ballot :as ballot]
            [eve.aegis.chat-spec :as chat]
            [eve.aegis.claims :as claims]
            [eve.aegis.rsa-vectors :as rv]
            [eve.aegis.session :as session]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.process :as process]))

(def ATOM "aegis.chat.public")
(def NOW 1700000000)
(def GROUP "_broker_defaults/trusted-anonymous")

(defn- seed-of [who] (crypto/sha256 (b/utf8 (str "seed:" who))))
(defn- pk-of [who] (crypto/ed25519-public-key (seed-of who)))
(defn- people [n] (mapv (fn [i] (str "member-" i)) (range n)))

(defn- counter-random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n) (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))

(defn- ctx-of [spec roster]
  (let [v (first (:pbrsa rv/vectors))]
    {:spec spec :spec-id (claims/spec-id (b/utf8 (pr-str spec))) :atom-id ATOM :epoch 1 :now NOW
     :digest-of (fn [v] (crypto/sha256 (b/utf8 (str "digest-of:" (hash v)))))
     :principals (into {} (map (fn [p] [p (pk-of p)]) roster)) :quarantine #{}
     :groups {GROUP (set (map pk-of roster))}
     :issuers {GROUP {:n (b/unhex (:n v)) :e (b/unhex (:e v))}}
     :spent #{}}))

(def ^:private ROOTS "the stub digests of the atom's state before and after a write"
  {:old-root (crypto/sha256 (b/utf8 "root:before")) :new-root (crypto/sha256 (b/utf8 "root:after"))})

(defn- appended "the ctx with the claim's append as its caller reads it from the two states (the new one ends in the record)"
  [ctx claim]
  (let [path (claims/mutating-path (claims/branch-named (:spec ctx) (:branch claim)))
        leaf (claims/record-leaf (claims/record-schema (:spec ctx) path) (:record claim))]
    (assoc ctx :append-of (fn [_] {:old-count 0 :new-count 1 :leaf leaf}))))

(defn- time-row [host label reps f]
  (f)
  (let [t0 (process/now-ms)]
    (dotimes [_ reps] (f))
    (println (str label ": " (/ (- (process/now-ms) t0) (double reps)) " ms (mean of " reps ") [" host "]"))))

(defn- message [who] {:id "m-1" :author who :ts NOW :content_commitment (apply str (repeat 32 "ab"))
                      :ciphertext "Y2lwaGVydGV4dA==" :ivB64 "aXZpdml2aXZpdml2"})

(defn bench!
  "print the rows; host: the label each row carries (the host and its crypto adapter)"
  [host]
  (let [spec chat/spec
        roster (people 5)
        ctx (ctx-of spec roster)
        spec-id (:spec-id ctx)
        who (first roster)
        ;; the named write
        claim (claims/build-claim spec "post-message" {:record (message who) :principal who} ROOTS)
        signed (:claim (claims/sign-claim spec spec-id claim (seed-of who)))
        nctx (appended ctx signed)
        _ (assert (:ok (claims/evaluate nctx signed)) "the named row evaluates")
        v (first (:pbrsa rv/vectors))
        sk {:n (b/unhex (:n v)) :e (b/unhex (:e v)) :d (b/unhex (:d v)) :p (b/unhex (:p v)) :q (b/unhex (:q v))}
        pk (select-keys sk [:n :e])
        info (ballot/scope-info :per-poll ATOM "p-1")
        vote0 (claims/build-claim spec "cast-vote" {:record {:id "v-1" :poll "p-1" :choice "yes" :ts NOW}} ROOTS)
        random (counter-random "bench")
        b0 (ballot/new-ballot pk info random)
        issuer (ballot/make-issuer sk (seed-of "issuer") roster)
        [_ bs] (ballot/request-ballot issuer who info (:blinded b0))
        bal (ballot/finish-ballot pk info b0 bs)
        voted (:claim (claims/sign-ballot spec spec-id vote0 bal))
        vctx (appended ctx voted)
        _ (assert (:ok (claims/evaluate vctx voted)) "the ballot row evaluates")
        secret (crypto/sha256 (b/utf8 "guest-link"))
        registry (session/register {} (:pk (session/session-keypair secret)) (- NOW 100))
        post0 (claims/build-claim spec "scratch-post" {:record {:id "sp-1" :space "s-1" :ts NOW :text "hi"}} ROOTS)
        posted (:claim (claims/sign-session spec spec-id post0 {:secret secret :issuance (- NOW 100)}))
        sctx (appended (assoc ctx :session-registry registry) posted)
        _ (assert (:ok (claims/evaluate sctx posted)) "the session row evaluates")]
    (time-row host "named: build-claim (post-message)" 20
              (fn [] (claims/build-claim spec "post-message" {:record (message who) :principal who} ROOTS)))
    (time-row host "named: sign-claim (Ed25519 sign)" 20 (fn [] (claims/sign-claim spec spec-id claim (seed-of who))))
    (time-row host "named: evaluate (the broker)" 20 (fn [] (claims/evaluate nctx signed)))
    (time-row host "ballot: new-ballot (client blind)" 3 (fn [] (ballot/new-ballot pk info random)))
    (time-row host "ballot: request-ballot (issuer blind-sign, no CRT)" 3
              (fn [] (ballot/request-ballot (ballot/make-issuer sk (seed-of "issuer") roster) who info (:blinded b0))))
    (time-row host "ballot: finish-ballot (client finalize)" 3 (fn [] (ballot/finish-ballot pk info b0 bs)))
    (time-row host "ballot: evaluate (the broker; verifies the ballot)" 3 (fn [] (claims/evaluate vctx voted)))
    (doseq [n [2 5 8]]
      (let [ring-roster (people n)
            rspec (update-in spec [:groups :entries 0] assoc :anonymity :ring)
            pks (mapv pk-of ring-roster)
            opts {:seed (seed-of (first ring-roster)) :roster pks :atom-id ATOM :scope-id "p-1" :random (counter-random "ring")}
            rsigned (:claim (claims/sign-ring rspec spec-id vote0 opts))
            rctx (appended (assoc (ctx-of rspec ring-roster) :spec-id spec-id) rsigned)]
        (assert (:ok (claims/evaluate rctx rsigned)) "the ring row evaluates")
        (time-row host (str "ring " n ": sign-ring (client)") 3 (fn [] (claims/sign-ring rspec spec-id vote0 opts)))
        (time-row host (str "ring " n ": evaluate (the broker)") 3 (fn [] (claims/evaluate rctx rsigned)))))
    (time-row host "session: sign-session (client)" 20
              (fn [] (claims/sign-session spec spec-id post0 {:secret secret :issuance (- NOW 100)})))
    (time-row host "session: evaluate (the broker)" 20 (fn [] (claims/evaluate sctx posted)))))
