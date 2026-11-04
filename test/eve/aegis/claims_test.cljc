(ns eve.aegis.claims-test
  "The chat spec's ten branches discharged by aegis's clear evaluator (eve.aegis.claims), each with at least
   one allow case and one refuse case; the ballot (Option 1) and ring (Option 2) rows of cast-vote; sessions; the
   hiding commitment; the ExtCID v3 verdict (:group-root, :principal-commit), the append and the map write read from the
   two states (ctx :append-of, :map-of) — no opening on any claim: a key-assoc / key-dissoc binds the two state digests,
   a session is read in the registry's value, a remote atom's member in that atom's value; the one (genesis spec); the
   nullifier in the state and the record placed with its attestation (next-state); the :aegis band refused to a branch;
   the signers' commitments. SYNTHETIC FIXTURES: principals are seeds derived from their names, the issuer's RSA key is the
   partially-blind draft's test key (2048-bit, safe primes), randomness is a SHA-256 counter stream, registries are
   built in the test, and the kernel's two digests are stubbed — the atom's roots before and after a write are two
   fixed 32-byte values, a block digest (ctx :digest-of) is SHA-256 over the value's hash; the spec is a verbatim copy
   of the chat spec (eve.aegis.chat-spec)."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.ballot :as ballot]
            [eve.aegis.chat-spec :as chat]
            [eve.aegis.claims :as claims]
            [eve.aegis.clsag :as clsag]
            [eve.aegis.commit :as commit]
            [eve.aegis.merkle :as merkle]
            [eve.aegis.rsa-vectors :as rv]
            [eve.net.codec :as codec]
            [eve.aegis.session :as session]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

;; ── fixtures

(def spec chat/spec)
(def spec-id (crypto/sha256 (b/utf8 (pr-str spec))))
(def ATOM "aegis.chat.public")
(def NOW 1700000000)

(defn- seed-of [who] (crypto/sha256 (b/utf8 (str "seed:" who))))
(defn- pk-of [who] (crypto/ed25519-public-key (seed-of who)))
(def PEOPLE ["alice" "bob" "carol" "dave" "eve" "mallory"])
(def principals (into {} (map (fn [p] [p (pk-of p)]) PEOPLE)))

(defn- counter-random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n) (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))

(def issuer-key (let [v (first (:pbrsa rv/vectors))] {:n (b/unhex (:n v)) :e (b/unhex (:e v)) :d (b/unhex (:d v)) :p (b/unhex (:p v)) :q (b/unhex (:q v))}))

(def groups {"_broker_defaults/trusted-anonymous" #{(pk-of "alice") (pk-of "bob") (pk-of "carol") (pk-of "eve")}
             "chat/moderators" #{(pk-of "bob")}
             "chat/room-admins" #{(pk-of "carol")}
             "chat/credential-holders" #{(pk-of "dave")}
             "chat/private-members" #{(pk-of "eve")}})

(defn- digest-of "the stub of the kernel's block digest: 32 bytes per value, equal values equal" [v]
  (crypto/sha256 (b/utf8 (str "digest-of:" (hash v)))))

(def ROOT0 "the stub digest of the atom's state before a write" (crypto/sha256 (b/utf8 "root:before")))
(def ROOT1 "the stub digest of the atom's state after a write" (crypto/sha256 (b/utf8 "root:after")))

(def base-ctx {:spec spec :spec-id spec-id :atom-id ATOM :epoch 7 :now NOW
               :principals principals :quarantine #{"mallory"} :groups groups
               :issuers {"_broker_defaults/trusted-anonymous" (select-keys issuer-key [:n :e])}
               :spent #{} :digest-of digest-of})

(defn- seg-path [path] (mapv (fn [s] (if (keyword? s) (name s) (str s))) path))

(defn- appended
  "The ctx as an append's caller builds it: :append-of read from the two states, the new one ending in the claim's
   record (old-count 0, new-count 1). A ctx that already has :append-of, or a claim that appends nothing or whose
   record the schema refuses, is left as it is."
  [ctx claim]
  (let [s (:spec ctx)
        branch (claims/branch-named s (:branch claim))
        path (when branch (claims/mutating-path branch))
        schema (when path (claims/record-schema s path))]
    (if (or (contains? ctx :append-of) (nil? branch) (not (claims/primitive branch :p/merkle-tail-append))
            (claims/record-refusal schema (:record claim)))
      ctx
      (assoc ctx :append-of (fn [p] (when (= p (seg-path path))
                                      {:old-count 0 :new-count 1 :leaf (claims/record-leaf schema (:record claim))}))))))

(defn- ev "evaluate, with the append read from the two states as the caller reads it" [ctx claim]
  (claims/evaluate (appended ctx claim) claim))

;; ── claim builders (the client's side)

(defn- append-claim "a :p/merkle-tail-append claim over the stub roots" [ctx branch-name record principal]
  (claims/build-claim (:spec ctx) branch-name {:record record :principal principal} {:old-root ROOT0 :new-root ROOT1}))

(defn- named [ctx claim who]
  (:claim (claims/sign-claim (:spec ctx) spec-id (assoc claim :principal who) (seed-of who))))

(defn- map-claim "a key-assoc / key-dissoc claim over the stub roots (the claim carries no opening)" [branch m]
  (claims/build-claim spec branch m {:old-root ROOT0 :new-root ROOT1}))

(def MEMBERS [:rooms :private :members])

(defn- members-ctx "the ctx as a map write's caller builds it: :map-of read from the two states, the members map old → new"
  [ctx old new]
  (assoc ctx :map-of (claims/map-of (assoc-in {} MEMBERS old) (assoc-in {} MEMBERS new))))

(defn- message-record [who text room-key iv]
  (let [{:keys [ciphertext tag commitment]} (commit/seal room-key iv (b/utf8 (str ATOM "|" who)) (b/utf8 text))]
    {:id "m-1" :author who :ts NOW :content_commitment (b/hex commitment) :ciphertext (b/hex (into ciphertext tag)) :ivB64 (b/to-b64 iv)}))

(def room-key (commit/room-key (crypto/sha256 (b/utf8 "room-secret")) ATOM))
(def IV (vec (range 16)))

(defn- ok? [r] (true? (:ok r)))
(defn- reason [r] (:reason r))

(defn- ballot-vote "a cast-vote claim spent with a ballot alice obtained from the group's issuer: the signer's answer"
  [ctx vote label]
  (let [info (ballot/scope-info :per-poll ATOM (:poll vote))
        issuer (ballot/make-issuer issuer-key (seed-of "issuer") ["alice" "bob" "carol" "eve"])
        b0 (ballot/new-ballot (:pk issuer) info (counter-random label))
        [_ bs] (ballot/request-ballot issuer "alice" info (:blinded b0))]
    (claims/sign-ballot (:spec ctx) spec-id (append-claim ctx "cast-vote" vote nil) (ballot/finish-ballot (:pk issuer) info b0 bs))))

(def ring-spec "the chat spec with the public room's group declaring :anonymity :ring (Option 2)"
  (update-in spec [:groups :entries 0] assoc :anonymity :ring))

(defn- ring-vote "a cast-vote claim ring-signed by `who` under ring-spec: the signer's answer" [vote who label]
  (claims/sign-ring ring-spec spec-id (append-claim (assoc base-ctx :spec ring-spec) "cast-vote" vote nil)
                    {:seed (seed-of who) :roster (get groups "_broker_defaults/trusted-anonymous") :atom-id ATOM
                     :scope-id (:poll vote) :random (counter-random label)}))

(defn- session-fixture "a guest's session, registered: {:ctx :registry :secret :issuance :pk}" [label]
  (let [secret (crypto/sha256 (b/utf8 label))
        issuance (- NOW 100)
        pk (:pk (session/session-keypair secret))
        registry (session/register {} pk issuance)]
    {:ctx (assoc base-ctx :session-registry registry)
     :registry registry :secret secret :issuance issuance :pk pk}))

(defn- scratch-post "a scratch-post claim signed by the fixture's session: the signer's answer" [{:keys [ctx secret issuance]} post]
  (claims/sign-session spec spec-id (append-claim ctx "scratch-post" post nil) {:secret secret :issuance issuance}))

;; ── the eight named branches

(deftest post-message
  (let [rec (message-record "alice" "hello" room-key IV)]
    (is (ok? (ev base-ctx (named base-ctx (append-claim base-ctx "post-message" rec "alice") "alice"))))
    (testing "impersonation: the record names bob, alice signs"
      (is (= :field-equals (reason (ev base-ctx (named base-ctx (append-claim base-ctx "post-message" (assoc rec :author "bob") "alice") "alice"))))))
    (testing "quarantined" (is (= :quarantined (reason (ev base-ctx (named base-ctx (append-claim base-ctx "post-message" (assoc rec :author "mallory") "mallory") "mallory"))))))
    (testing "a plaintext record is refused by shape"
      (is (= :record (reason (ev base-ctx (named base-ctx (append-claim base-ctx "post-message" (assoc rec :text "hello") "alice") "alice"))))))
    (testing "not in the public room's group" (is (= :not-a-member (reason (ev base-ctx (named base-ctx (append-claim base-ctx "post-message" (assoc rec :author "dave") "dave") "dave"))))))
    (testing "another principal's signature"
      (let [c (named base-ctx (append-claim base-ctx "post-message" rec "alice") "alice")]
        (is (= :signature (reason (ev base-ctx (assoc c :auth {:kind :ed25519 :sig (crypto/ed25519-sign (seed-of "bob") (vec (repeat 32 0)))})))))))))

(deftest create-poll-and-open-scratch-space
  (let [poll {:id "p-1" :author "alice" :ts NOW :question "lunch?" :options ["yes" "no"]}]
    (is (ok? (ev base-ctx (named base-ctx (append-claim base-ctx "create-poll" poll "alice") "alice"))))
    (is (= :field-equals (reason (ev base-ctx (named base-ctx (append-claim base-ctx "create-poll" (assoc poll :author "carol") "alice") "alice"))))))
  (let [space {:id "s-1" :owner "alice" :ts NOW :title "scratch"}]
    (is (ok? (ev base-ctx (named base-ctx (append-claim base-ctx "open-scratch-space" space "alice") "alice"))))
    (is (= :field-equals (reason (ev base-ctx (named base-ctx (append-claim base-ctx "open-scratch-space" (assoc space :owner "bob") "alice") "alice")))))))

(deftest private-message-and-audit-log
  (let [rec (message-record "eve" "psst" room-key IV)]
    (is (ok? (ev base-ctx (named base-ctx (append-claim base-ctx "private-message" rec "eve") "eve"))))
    (is (= :not-a-member (reason (ev base-ctx (named base-ctx (append-claim base-ctx "private-message" (assoc rec :author "alice") "alice") "alice"))))))
  (let [entry {:id "a-1" :moderator "bob" :action :ban :target "mallory" :ts NOW}]
    (is (ok? (ev base-ctx (named base-ctx (append-claim base-ctx "audit-log" entry "bob") "bob"))))
    (is (= :not-a-member (reason (ev base-ctx (named base-ctx (append-claim base-ctx "audit-log" (assoc entry :moderator "alice") "alice") "alice")))))))

(deftest post-announcement
  (let [registry {"dave" "credential-7" "bob" "credential-2"}
        ctx (assoc base-ctx :cross-atom {"aegis.chat.credential-registry" registry})
        rec (message-record "dave" "announcing" room-key IV)]
    (is (ok? (ev ctx (named ctx (append-claim ctx "post-announcement" rec "dave") "dave"))) "the registry's value holds dave")
    (testing "the registry unresolved" (is (= :remote-atom-unresolved (reason (ev base-ctx (named base-ctx (append-claim base-ctx "post-announcement" rec "dave") "dave"))))))
    (testing "a holder of no credential"
      (let [rec2 (assoc rec :author "alice")]
        (is (= :not-in-remote-atom (reason (ev ctx (named ctx (append-claim ctx "post-announcement" rec2 "alice") "alice")))))))
    (testing "a claim carrying the retired remote-atom opening is malformed"
      (is (= :malformed (reason (ev ctx (assoc-in (named ctx (append-claim ctx "post-announcement" rec "dave") "dave")
                                                  [:witness :remote "aegis.chat.credential-registry"] {:index 0}))))))))

(deftest add-and-remove-member
  (let [eve-pk (b/hex (pk-of "eve"))
        old {} new {"m-1" eve-pk}
        ctx (members-ctx base-ctx old new)
        claim (map-claim "add-member" {:key "m-1" :value eve-pk})]
    (is (ok? (ev ctx (named ctx claim "carol"))))
    (is (= :not-a-member (reason (ev ctx (named ctx claim "alice")))) "only room-admins")
    (is (= :member-value-not-a-key (reason (ev ctx (named ctx (assoc claim :value "not-a-key") "carol")))))
    (is (= :map-change (reason (ev ctx (named ctx (assoc claim :value (b/hex (pk-of "bob"))) "carol")))) "the new map holds the claim's value")
    (is (= :key-present (reason (ev (members-ctx base-ctx new new) (named ctx claim "carol")))) "an add of a present key")
    (is (= :no-map-state (reason (ev base-ctx (named ctx claim "carol")))) "no account of the map write")
    (let [claim2 (map-claim "remove-member" {:key "m-1"})
          ctx2 (members-ctx base-ctx new old)]
      (is (ok? (ev ctx2 (named ctx2 claim2 "carol"))))
      (is (= :key-absent (reason (ev ctx2 (named ctx2 (assoc claim2 :key "m-2") "carol")))) "a remove of an absent key")
      (is (= :map-change (reason (ev (members-ctx base-ctx (assoc new "m-0" eve-pk) {}) (named ctx2 claim2 "carol"))))
          "a remove that drops another key too"))))

;; ── the two anonymous branches

(deftest cast-vote-with-ballots
  (let [vote {:id "v-1" :poll "p-1" :choice "yes" :ts NOW}
        claim0 (append-claim base-ctx "cast-vote" vote nil)
        branch (claims/branch-named spec "cast-vote") path (claims/mutating-path branch)
        digest (claims/claim-digest spec-id claim0 path (claims/record-leaf (claims/record-schema spec path) vote))
        info (ballot/scope-info :per-poll ATOM "p-1")
        issuer (ballot/make-issuer issuer-key (seed-of "issuer") ["alice" "bob" "carol" "eve"])
        random (counter-random "ballot")
        pk (:pk issuer)
        b0 (ballot/new-ballot pk info random)
        [issuer' bs] (ballot/request-ballot issuer "alice" info (:blinded b0))
        bal (ballot/finish-ballot pk info b0 bs)
        claim (assoc claim0 :auth (ballot/spend bal digest))
        r (ev base-ctx claim)]
    (is (ok? r))
    (is (= (ballot/nullifier (:auth claim)) (:nullifier r)))
    (testing "double spend" (is (= :nullifier-spent (reason (ev (update base-ctx :spent conj (:nullifier r)) claim)))))
    (testing "one ballot per member per poll" (is (thrown? #?(:cljs js/Error :default Exception) (ballot/request-ballot issuer' "alice" info (:blinded b0)))))
    (testing "a non-member" (is (thrown? #?(:cljs js/Error :default Exception) (ballot/request-ballot issuer' "dave" info (:blinded b0)))))
    (testing "a ballot for another poll does not spend here"
      (let [info2 (ballot/scope-info :per-poll ATOM "p-2")
            b2 (ballot/new-ballot pk info2 random)
            [_ bs2] (ballot/request-ballot issuer' "alice" info2 (:blinded b2))
            bal2 (ballot/finish-ballot pk info2 b2 bs2)]
        (is (= :ballot (reason (ev base-ctx (assoc claim0 :auth (ballot/spend bal2 digest))))))
        (is (not= (:token-pk bal) (:token-pk bal2)))
        (is (not= (:token-sig bal) (:token-sig bal2)))))
    (testing "a ballot spent on another vote's digest" (is (= :ballot (reason (ev base-ctx (assoc claim0 :auth (ballot/spend bal (vec (repeat 32 1)))))))))
    (testing "an unknown issuer" (is (= :no-issuer (reason (ev (assoc base-ctx :issuers {}) claim)))))
    (testing "over-issuance is provable from the artefacts"
      (let [stmt (ballot/count-statement issuer' info)
            honest {:log (:log issuer') :roster-size 4 :stmt stmt :stmt-pk (:stmt-pk issuer') :spent #{(:nullifier r)} :info info}]
        (is (ballot/verify-statement (:stmt-pk issuer') stmt))
        (is (not (ballot/over-issued? honest)))
        (is (ballot/over-issued? (assoc honest :roster-size 0)) "more entries than members")
        (is (ballot/over-issued? (update honest :log into (repeat 5 {:info (b/hex info) :member "ghost" :blinded []}))) "five ghosts")
        (is (ballot/over-issued? (assoc honest :spent #{[1] [2]})) "more spent than issued")
        (is (ballot/over-issued? (assoc honest :stmt (assoc stmt :count 9))) "a forged count")))))

(deftest cast-vote-with-a-ring
  (let [base-ctx (assoc base-ctx :spec ring-spec)
        vote {:id "v-2" :poll "p-1" :choice "no" :ts NOW}
        claim0 (append-claim base-ctx "cast-vote" vote nil)
        branch (claims/branch-named spec "cast-vote") path (claims/mutating-path branch)
        digest (claims/claim-digest spec-id claim0 path (claims/record-leaf (claims/record-schema spec path) vote))
        roster (get groups "_broker_defaults/trusted-anonymous")
        ring (vec (sort merkle/compare-bytes roster))
        scope (b/utf8 (str "per-poll|" ATOM "|p-1"))
        sign (fn [who label] (clsag/sign {:msg digest :ring ring :seed (seed-of who) :index (first (keep-indexed (fn [i p] (when (= p (pk-of who)) i)) ring)) :scope scope :random (counter-random label)}))
        claim (assoc claim0 :auth {:kind :ring :sig (sign "carol" "r1")})
        r (ev base-ctx claim)]
    (is (ok? r))
    (is (= (clsag/key-image (seed-of "carol") scope) (:nullifier r)))
    (testing "the same voter again: the key image repeats, the spent set refuses"
      (is (= :nullifier-spent (reason (ev (update base-ctx :spent conj (:nullifier r)) (assoc claim0 :auth {:kind :ring :sig (sign "carol" "r2")}))))))
    (testing "a non-member cannot sign" (is (thrown? #?(:cljs js/Error :default Exception) (sign "dave" "r3"))))
    (testing "a tampered signature" (is (= :ring (reason (ev base-ctx (assoc-in claim [:auth :sig :c1 0] 0))))))
    (testing "the group declares :ring: a ring vote under a :ballot spec is refused, and the declaration is the spec's"
      (is (= :anonymity (reason (ev (assoc base-ctx :spec spec) claim)))))))

(deftest scratch-post-with-a-session
  (let [{:keys [ctx registry pk issuance] :as fx} (session-fixture "guest-link")
        {:keys [seed]} (session/session-keypair (:secret fx))
        post {:id "sp-1" :space "s-1" :ts NOW :text "hi"}
        claim0 (append-claim ctx "scratch-post" post nil)
        branch (claims/branch-named spec "scratch-post") path (claims/mutating-path branch)
        digest (claims/claim-digest spec-id claim0 path (claims/record-leaf (claims/record-schema spec path) post))
        auth {:kind :session :pk pk :issuance issuance :sig (crypto/ed25519-sign seed digest)}
        r (ev ctx (assoc claim0 :auth auth))]
    (is (ok? r))
    (is (= (vec pk) (:nullifier r)) "the session key is the nullifier")
    (testing "one post per session" (is (= :nullifier-spent (reason (ev (update ctx :spent conj (:nullifier r)) (assoc claim0 :auth auth))))))
    (testing "expired" (is (= :session (reason (ev (assoc ctx :now (+ NOW 4000)) (assoc claim0 :auth auth))))))
    (testing "a session the registry never held"
      (let [other (session/session-keypair (crypto/sha256 (b/utf8 "forged")))]
        (is (= :session (reason (ev ctx (assoc claim0 :auth (assoc auth :pk (:pk other) :sig (crypto/ed25519-sign (:seed other) digest)))))))))
    (testing "another key's signature" (is (= :session-signature (reason (ev ctx (assoc claim0 :auth (assoc auth :sig (crypto/ed25519-sign (seed-of "alice") digest))))))))
    (testing "the registry not published" (is (= :no-session-registry (reason (ev base-ctx (assoc claim0 :auth auth))))))
    (testing "an issuance the registry does not hold for the key"
      (is (= :session (reason (ev ctx (assoc claim0 :auth (assoc auth :issuance (inc issuance))))))))
    (testing "the session is read in the registry's value: a registry without it refuses"
      (is (= :session (reason (ev (assoc ctx :session-registry (dissoc registry (b/hex pk))) (assoc claim0 :auth auth))))))
    (testing "an auth carrying the retired registry opening is malformed"
      (is (= :malformed (reason (ev ctx (assoc claim0 :auth (assoc auth :opening {:index 0 :siblings []})))))))))

;; ── the hiding commitment

(deftest hiding-commitment
  (let [aad (b/utf8 "aegis.chat.msg|room|m-1|alice")
        {:keys [ciphertext tag commitment]} (commit/seal room-key IV aad (b/utf8 "yes"))]
    (is (= (b/utf8 "yes") (commit/open room-key IV aad ciphertext tag)))
    (is (nil? (commit/open room-key IV aad (update ciphertext 0 (fn [x] (mod (inc x) 256))) tag)) "tampered")
    (is (nil? (commit/open room-key IV (conj aad 1) ciphertext tag)) "moved to another record")
    (is (= commitment (commit/commitment room-key IV (b/utf8 "yes"))) "a member confirms the text")
    (is (not= commitment (commit/commitment (commit/room-key (crypto/sha256 (b/utf8 "guess")) ATOM) IV (b/utf8 "yes")))
        "without the room key a dictionary guess of \"yes\" does not confirm")))

;; ── the reroute's additions: spec id, the two DSL additions, the stored-map binding, issuance counts

(deftest spec-id-binds-spec-and-release
  (let [bs (b/utf8 "{:op :aegis/app}")]
    (is (= (claims/spec-id bs) (crypto/sha256 (into (vec bs) (crypto/sha256 (b/utf8 claims/RELEASE))))))
    (is (= 32 (count (claims/spec-id bs))))
    (is (not= (claims/spec-id bs) (claims/spec-id (conj (vec bs) 32))) "one byte more is another spec")))

(deftest anonymity-declarations
  (is (= :ballot (claims/group-anonymity spec "_broker_defaults/trusted-anonymous")) "spec.vs declares :ballot")
  (is (= :ballot (claims/group-anonymity spec "chat/moderators")) "an undeclared group defaults to :ballot")
  (is (= :ring (claims/group-anonymity ring-spec "_broker_defaults/trusted-anonymous")))
  (testing "a ballot vote under a group declaring :ring is refused"
    (let [vote {:id "v-9" :poll "p-1" :choice "yes" :ts NOW}
          claim (assoc (append-claim base-ctx "cast-vote" vote nil) :auth {:kind :ballot :token-pk [] :token-sig [] :sig []})]
      (is (= :anonymity (reason (ev (assoc base-ctx :spec ring-spec) claim))))
      (is (= :anonymity-unknown (reason (ev (assoc base-ctx :spec (update-in spec [:groups :entries 0] assoc :anonymity :sealed)) claim)))))))

(deftest commit-fn-sha256
  (is (= (b/hex (crypto/sha256 (b/utf8 "alice"))) (claims/principal-key "sha256" "alice")))
  (is (= "alice" (claims/principal-key nil "alice")) "no :commitFn: the key is the name")
  (is (nil? (claims/principal-key "poseidon2" "alice")) "the circuit's scheme is refused in the clear")
  (is (= "sha256" (:commitFn (claims/acl-for spec ["users" "x"]))) "spec.vs's users ACL")
  (testing "the principal commitment is SHA-256 of the name under either key form"
    (is (= (crypto/sha256 (b/utf8 "alice")) (claims/principal-commit {:commitFn "sha256"} "alice")))
    (is (= (crypto/sha256 (b/utf8 "alice")) (claims/principal-commit nil "alice")))
    (is (nil? (claims/principal-commit {:commitFn "poseidon2"} "alice")))))

(deftest key-assoc-read-from-the-two-states
  (let [eve-pk (b/hex (pk-of "eve"))
        old {"m-0" (b/hex (pk-of "bob"))}
        new (assoc old "m-1" eve-pk)
        claim (map-claim "add-member" {:key "m-1" :value eve-pk})
        asked (atom nil)
        with (fn [m] (assoc base-ctx :map-of (fn [p] (reset! asked p) m)))]
    (testing "the claim carries no opening: a map write's roots are the caller's two state digests"
      (is (not (contains? claim :witness)))
      (is (= [ROOT0 ROOT1] [(:old-root claim) (:new-root claim)]))
      (is (thrown? #?(:cljs js/Error :default Exception)
                   (claims/build-claim spec "add-member" {:key "m-1" :value eve-pk :principal "carol"} {}))))
    (testing "accepted: the new map is the old one with the key assoc'd, the rest held"
      (is (ok? (ev (with {:old old :new new}) (named base-ctx claim "carol"))))
      (is (= ["rooms" "private" "members"] @asked) "the evaluator asks for the branch's path as string segments"))
    (testing "refused: a new map that also dropped m-0"
      (is (= :map-change (reason (ev (with {:old old :new {"m-1" eve-pk}}) (named base-ctx claim "carol"))))))
    (testing "refused: no account of the map write"
      (is (= :no-map-state (reason (ev (with nil) (named base-ctx claim "carol")))))
      (is (= :no-map-state (reason (ev (with {:old old :new [1]}) (named base-ctx claim "carol"))))))
    (testing "map-of reads the two states at the spec's keywords"
      (is (= {:old old :new new} ((claims/map-of {:rooms {:private {:members old}}} {:rooms {:private {:members new}}})
                                  ["rooms" "private" "members"])))
      (is (= {:old {} :new new} ((claims/map-of {} {:rooms {:private {:members new}}}) ["rooms" "private" "members"]))
          "no map before the write: {}")
      (is (nil? ((claims/map-of {:rooms {:private {:members old}}} {}) ["rooms" "private" "members"])) "no map after it: nil"))
    (testing "a claim carrying the retired key-assoc opening is malformed"
      (is (= :malformed (reason (ev (with {:old old :new new}) (assoc (named base-ctx claim "carol") :witness {:assoc {}}))))))))

(deftest ballot-spends-held-to-the-issuance-count
  (let [vote {:id "v-3" :poll "p-7" :choice "yes" :ts NOW}
        claim0 (append-claim base-ctx "cast-vote" vote nil)
        info (ballot/scope-info :per-poll ATOM "p-7")
        issuer (ballot/make-issuer issuer-key (seed-of "issuer") ["alice" "bob"])
        random (counter-random "count")
        b0 (ballot/new-ballot (:pk issuer) info random)
        [issuer' bs] (ballot/request-ballot issuer "alice" info (:blinded b0))
        bal (ballot/finish-ballot (:pk issuer) info b0 bs)
        claim (:claim (claims/sign-ballot spec spec-id claim0 bal))
        stmt (ballot/count-statement issuer' info)
        ctx (assoc base-ctx :issuers {"_broker_defaults/trusted-anonymous" (assoc (:pk issuer) :stmt-pk (:stmt-pk issuer))}
                   :statements {(b/hex info) stmt})
        r (ev ctx claim)]
    (is (ok? r))
    (is (= (b/hex info) (:ballot-info r)))
    (is (= :over-issued (reason (ev (assoc ctx :spent-counts {(b/hex info) 1}) claim))) "one issued, one spent: no second")
    (is (= :nullifier-spent (reason (ev (assoc ctx :spent-counts {(b/hex info) 1} :spent #{(:nullifier r)}) claim)))
        "the same ballot again is a double spend, whatever the count")
    (is (= :issuance-statement (reason (ev (assoc-in ctx [:statements (b/hex info) :count] 5) claim))) "a forged count")))

;; ── ExtCID v3: the verdict, the append from the two states, the nullifier in the state, the signers (R-A6.1 … R-A6.6)

(def no-group-spec "the chat spec with the public room's ACL removed: post-message is a named write to no group"
  (update-in spec [:acls :entries] (fn [es] (vec (remove (fn [e] (= ["rooms" "public" "messages"] (:path e))) es)))))

(deftest verdict-carries-group-root-and-principal-commit
  (testing "named, to a group path: the group's roster; SHA-256 of the name"
    (let [r (ev base-ctx (named base-ctx (append-claim base-ctx "post-message" (message-record "alice" "hi" room-key IV) "alice") "alice"))]
      (is (ok? r))
      (is (= (digest-of (get groups "_broker_defaults/trusted-anonymous")) (:group-root r)))
      (is (= (crypto/sha256 (b/utf8 "alice")) (:principal-commit r)))
      (is (= (claims/principal-commit (claims/acl-for spec ["rooms" "public" "messages"]) "alice") (:principal-commit r)))))
  (testing "named, to no group: the principal registry"
    (let [ctx (assoc base-ctx :spec no-group-spec)
          r (ev ctx (named ctx (append-claim ctx "post-message" (message-record "dave" "hi" room-key IV) "dave") "dave"))]
      (is (ok? r))
      (is (= (digest-of principals) (:group-root r)))
      (is (= (crypto/sha256 (b/utf8 "dave")) (:principal-commit r)))))
  (testing "ballot: the group's issuer record; SHA-256 of the token public key"
    (let [{:keys [claim]} (ballot-vote base-ctx {:id "v-1" :poll "p-1" :choice "yes" :ts NOW} "v61")
          r (ev base-ctx claim)]
      (is (ok? r))
      (is (= (digest-of (get-in base-ctx [:issuers "_broker_defaults/trusted-anonymous"])) (:group-root r)))
      (is (= (crypto/sha256 (vec (get-in claim [:auth :token-pk]))) (:principal-commit r)))))
  (testing "ring: the group's roster; the key image"
    (let [{:keys [claim]} (ring-vote {:id "v-2" :poll "p-1" :choice "no" :ts NOW} "carol" "r61")
          r (ev (assoc base-ctx :spec ring-spec) claim)]
      (is (ok? r))
      (is (= (digest-of (get groups "_broker_defaults/trusted-anonymous")) (:group-root r)))
      (is (= (clsag/key-image (seed-of "carol") (b/utf8 (str "per-poll|" ATOM "|p-1"))) (:principal-commit r)))))
  (testing "session: the session registry; the session's leaf in it"
    (let [{:keys [ctx pk issuance] :as fx} (session-fixture "guest-61")
          r (ev ctx (:claim (scratch-post fx {:id "sp-1" :space "s-1" :ts NOW :text "hi"})))]
      (is (ok? r))
      (is (= (digest-of (:session-registry ctx)) (:group-root r)))
      (is (= (merkle/sidecar-leaf (b/hex pk) (merkle/field-of issuance)) (:principal-commit r)))
      (is (= (claims/session-commit pk issuance) (:principal-commit r)))))
  (testing "both fields are 32 bytes"
    (let [r (ev base-ctx (named base-ctx (append-claim base-ctx "audit-log" {:id "a-1" :moderator "bob" :action :ban :target "x" :ts NOW} "bob") "bob"))]
      (is (= [32 32] [(count (:group-root r)) (count (:principal-commit r))]))))
  (testing "no :digest-of, or one that answers anything but 32 bytes: refused"
    (let [c (named base-ctx (append-claim base-ctx "create-poll" {:id "p-1" :author "alice" :ts NOW :question "q" :options []} "alice") "alice")]
      (is (= :no-digest-of (reason (ev (dissoc base-ctx :digest-of) c))))
      (is (= :digest-of (reason (ev (assoc base-ctx :digest-of (fn [_] [1 2 3])) c)))))))

(deftest tail-append-read-from-the-two-states
  (let [rec (message-record "alice" "hello" room-key IV)
        claim (named base-ctx (append-claim base-ctx "post-message" rec "alice") "alice")
        leaf (claims/record-leaf (claims/record-schema spec [:rooms :public :messages]) rec)
        asked (atom nil)
        with (fn [a] (assoc base-ctx :append-of (fn [p] (reset! asked p) a)))]
    (testing "the claim carries no witness: an append's roots are the caller's two state digests"
      (is (nil? (:witness claim)))
      (is (= [ROOT0 ROOT1] [(:old-root claim) (:new-root claim)]))
      (is (thrown? #?(:cljs js/Error :default Exception)
                   (claims/build-claim spec "post-message" {:record rec :principal "alice"} {}))))
    (testing "accepted: new = old + 1, the last record's leaf is the claim's record leaf"
      (is (ok? (claims/evaluate (with {:old-count 4 :new-count 5 :leaf leaf}) claim)))
      (is (= ["rooms" "public" "messages"] @asked) "the evaluator asks for the branch's path as string segments"))
    (testing "refused: new ≠ old + 1"
      (is (= :append-count (reason (claims/evaluate (with {:old-count 4 :new-count 6 :leaf leaf}) claim))))
      (is (= :append-count (reason (claims/evaluate (with {:old-count 4 :new-count 4 :leaf leaf}) claim))))
      (is (= :append-count (reason (claims/evaluate (with {:new-count 1 :leaf leaf}) claim)))))
    (testing "refused: the appended leaf is not the claim's record"
      (is (= :append-leaf (reason (claims/evaluate (with {:old-count 0 :new-count 1 :leaf (vec (repeat 32 9))}) claim)))))
    (testing "refused: no account of the append"
      (is (= :no-append-state (reason (claims/evaluate base-ctx claim))))
      (is (= :no-append-state (reason (claims/evaluate (with nil) claim)))))
    (testing "a claim carrying siblings is malformed"
      (let [sibs (vec (repeat 20 (vec (repeat 32 0))))]
        (is (= :malformed (reason (ev base-ctx (assoc-in claim [:witness :append] {:index 0 :siblings sibs})))))
        (is (= :malformed (reason (ev base-ctx (assoc claim :siblings sibs)))))))))

(def aegis-spec "the chat spec with two branches that would write the evaluator's band"
  (update-in spec [:policy :children] conj
             {:op :and :branch "forge-spent"
              :children [{:op :primitive :name :p/merkle-tail-append :args {:path-vec [:aegis :spent]}}]}
             {:op :and :branch "forge-ballot-count"
              :children [{:op :primitive :name :p/not-quarantined :args {}}
                         {:op :primitive :name :p/key-assoc :args {:path-vec ["aegis" "ballot-spends"] :key :info}}]}))

(deftest the-nullifier-lands-in-the-state
  (let [rec (message-record "alice" "hello" room-key IV)
        claim (named base-ctx (append-claim base-ctx "post-message" rec "alice") "alice")
        v (ev base-ctx claim)
        st {:rooms {:public {:messages []}}}
        st' (claims/next-state st claim spec v)]
    (is (ok? v))
    (is (= 1 (count (get-in st' [:rooms :public :messages]))) "at the spec's keyword path")
    (is (= "alice" (get-in st' [:rooms :public :messages 0 :author])))
    (is (identical? (:record claim) (get-in st' [:rooms :public :messages 0])) "the claim's record, placed as it is")
    (is (true? (get-in st' [:aegis :spent (b/hex (:nullifier v))])) "[:aegis :spent <nullifier hex>] true")
    (is (= (claims/named-nullifier spec claim (crypto/sha256 (b/utf8 "alice"))) (:nullifier v))
        "a named nullifier: the commitment, the old root and the write, from the unsigned claim")
    (is (= (:nullifier v) (claims/named-nullifier spec (dissoc claim :auth) (:principal-commit v))) "computable before signing")
    (testing "the band is the spent set the next evaluation reads"
      (let [ctx (assoc base-ctx :spent? (fn [n] (true? (get-in st' [:aegis :spent (b/hex n)]))))]
        (is (= :nullifier-spent (reason (ev ctx claim))))))
    (testing "the same write twice by one principal on one old root: the second refused as spent"
      (let [again (named base-ctx (append-claim base-ctx "post-message" rec "alice") "alice")
            ctx (assoc base-ctx :spent? (fn [n] (true? (get-in st' [:aegis :spent (b/hex n)]))))]
        (is (= :nullifier-spent (reason (ev ctx again))))))
    (testing "one principal's distinct writes on one root that repeats are distinct keys"
      (let [other (named base-ctx (append-claim base-ctx "post-message" (assoc rec :id "m-2" :ts (inc NOW)) "alice") "alice")
            add (fn [k] (named base-ctx (map-claim "add-member" {:key k :value (b/hex (pk-of "eve"))}) "carol"))
            n (fn [c] (:nullifier (ev (if (:key c) (members-ctx base-ctx {} {(:key c) (:value c)}) base-ctx) c)))]
        (is (not= (n claim) (n other)) "another record on the same old root (the CAS lets one land, not the spent set)")
        (is (= (:old-root (add "m-1")) (:old-root (add "m-2"))) "two adds on one state: one old root")
        (is (not= (n (add "m-1")) (n (add "m-2"))) "two adds of two keys on one root: two keys of the spent set")))
    (testing "the verdict is required"
      (is (thrown? #?(:cljs js/Error :default Exception) (claims/next-state st claim spec {:ok false :reason :signature}))))
    (testing "a ballot: its spend counted under the scope's info"
      (let [{bc :claim} (ballot-vote base-ctx {:id "v-1" :poll "p-1" :choice "yes" :ts NOW} "v63")
            bv (ev base-ctx bc)
            info (:ballot-info bv)
            s1 (claims/next-state {} bc spec bv)]
        (is (ok? bv))
        (is (= 1 (get-in s1 [:aegis :ballot-spends info])))
        (is (true? (get-in s1 [:aegis :spent (b/hex (:nullifier bv))])))
        (is (= 2 (get-in (claims/next-state (assoc-in {} [:aegis :ballot-spends info] 1) bc spec bv) [:aegis :ballot-spends info])))))
    (testing "key-assoc and key-dissoc write the map and the band"
      (let [m (claims/next-state {} {:branch "add-member" :key "m-1" :value "ab"} spec {:ok true :nullifier [1 2]})]
        (is (= {"m-1" "ab"} (get-in m [:rooms :private :members])))
        (is (= {} (get-in (claims/next-state m {:branch "remove-member" :key "m-1"} spec {:ok true :nullifier [3]}) [:rooms :private :members])))
        (is (= {"0102" true} (get-in m [:aegis :spent])))))
    (testing "an attested record keeps its attestation (eve.net.codec/attest: the record's metadata, a block's header)"
      (let [at {:epoch 8 :spec-id spec-id :evaluator (subvec (vec (claims/release-hash)) 0 16)
                :group-root (vec (repeat 32 1)) :principal-commit (crypto/sha256 (b/utf8 "alice"))}
            att (codec/attest rec at)
            placed (get-in (claims/next-state st (assoc claim :record att) spec v) [:rooms :public :messages 0])]
        (is (identical? att placed))
        (is (= at (codec/attestation placed)))))))

(deftest genesis-is-read-from-the-spec
  (is (= {:rooms {:public {:messages [] :polls [] :votes []} :private {:members {} :messages []}
                  :announcements {:messages []}}
          :scratch-spaces {:rooms [] :posts []} :audit-log [] :aegis {:spent {} :ballot-spends {}}}
         (claims/genesis spec))
      "every append path [], every key-assoc / key-dissoc path {}, the evaluator's band")
  (is (= {:aegis {:spent {} :ballot-spends {}}} (claims/genesis {:policy {:op :or :children []}})) "no branch: the band alone")
  (testing "the old state of a genesis claim: the first append and the first add read from it"
    (let [g (claims/genesis spec)
          rec (message-record "alice" "first" room-key IV)
          claim (named base-ctx (append-claim base-ctx "post-message" rec "alice") "alice")
          g' (claims/next-state g claim spec (ev base-ctx claim))
          add (named base-ctx (map-claim "add-member" {:key "m-1" :value (b/hex (pk-of "eve"))}) "carol")
          ga (claims/next-state g add spec {:ok true :nullifier [9]})]
      (is (= {:old-count 0 :new-count 1 :leaf (claims/record-leaf (claims/record-schema spec [:rooms :public :messages]) rec)}
             ((claims/append-of spec g g') ["rooms" "public" "messages"])))
      (is (ok? (claims/evaluate (assoc base-ctx :map-of (claims/map-of g ga)) add))))))

(deftest a-branch-writing-the-aegis-band-is-refused
  (let [ctx (assoc base-ctx :spec aegis-spec)]
    (is (= :aegis-path (reason (ev ctx {:branch "forge-spent" :record {} :old-root ROOT0 :new-root ROOT1}))))
    (is (= :aegis-path (reason (ev ctx (named ctx {:branch "forge-ballot-count" :key "x" :value "y" :old-root ROOT0 :new-root ROOT1} "alice")))))
    (is (ok? (ev ctx (named ctx (append-claim ctx "audit-log" {:id "a-1" :moderator "bob" :action :ban :target "x" :ts NOW} "bob") "bob")))
        "the spec's own branches are untouched")))

(deftest each-signer-returns-the-verdicts-principal-commit
  (let [named-s (claims/sign-claim spec spec-id (append-claim base-ctx "post-message" (message-record "alice" "hi" room-key IV) "alice") (seed-of "alice"))
        ballot-s (ballot-vote base-ctx {:id "v-1" :poll "p-1" :choice "yes" :ts NOW} "v65")
        ring-s (ring-vote {:id "v-2" :poll "p-1" :choice "no" :ts NOW} "bob" "r65")
        fx (session-fixture "guest-65")
        session-s (scratch-post fx {:id "sp-1" :space "s-1" :ts NOW :text "hi"})]
    (doseq [[label ctx s] [["sign-claim" base-ctx named-s] ["sign-ballot" base-ctx ballot-s]
                           ["sign-ring" (assoc base-ctx :spec ring-spec) ring-s] ["sign-session" (:ctx fx) session-s]]]
      (testing label
        (let [r (ev ctx (:claim s))]
          (is (ok? r))
          (is (= 32 (count (:principal-commit s))))
          (is (= (:principal-commit s) (:principal-commit r))))))))

;; ── the ten branches of chat_spec, each accepted, through the wire and back

(deftest the-ten-branches-through-the-wire
  (let [rec (message-record "alice" "hello" room-key IV)
        old {} new {"m-1" (b/hex (pk-of "eve"))}
        actx (members-ctx base-ctx old new)
        dctx (members-ctx base-ctx new old)
        registry {"dave" "credential-7"}
        xctx (assoc base-ctx :cross-atom {"aegis.chat.credential-registry" registry})
        fx (session-fixture "guest")
        rctx (assoc base-ctx :spec ring-spec)
        rows [["post-message" base-ctx (named base-ctx (append-claim base-ctx "post-message" rec "alice") "alice")]
              ["create-poll" base-ctx (named base-ctx (append-claim base-ctx "create-poll" {:id "p-1" :author "alice" :ts NOW :question "q" :options ["a" "b"]} "alice") "alice")]
              ["cast-vote" base-ctx (:claim (ballot-vote base-ctx {:id "v" :poll "p-1" :choice "yes" :ts NOW} "wire-ballot"))]
              ["cast-vote" rctx (:claim (ring-vote {:id "v" :poll "p-1" :choice "yes" :ts NOW} "bob" "wire-ring"))]
              ["add-member" actx (named actx (map-claim "add-member" {:key "m-1" :value (b/hex (pk-of "eve"))}) "carol")]
              ["remove-member" dctx (named dctx (map-claim "remove-member" {:key "m-1"}) "carol")]
              ["post-announcement" xctx (named xctx (append-claim xctx "post-announcement" (message-record "dave" "x" room-key IV) "dave") "dave")]
              ["scratch-post" (:ctx fx) (:claim (scratch-post fx {:id "sp" :space "s" :ts NOW :text "t"}))]
              ["private-message" base-ctx (named base-ctx (append-claim base-ctx "private-message" (message-record "eve" "psst" room-key IV) "eve") "eve")]
              ["audit-log" base-ctx (named base-ctx (append-claim base-ctx "audit-log" {:id "a-1" :moderator "bob" :action :ban :target "x" :ts NOW} "bob") "bob")]
              ["open-scratch-space" base-ctx (named base-ctx (append-claim base-ctx "open-scratch-space" {:id "s-1" :owner "alice" :ts NOW :title "t"} "alice") "alice")]]]
    (is (= (set (map :branch (claims/branches spec))) (set (map first rows))) "every branch of chat_spec has a row")
    (is (= 10 (count (claims/branches spec))))
    (doseq [[label ctx claim] rows]
      (testing label
        (let [wire (claims/claim->wire claim)
              back (claims/wire->claim wire)
              r (ev ctx claim)]
          (is (ok? r) "the claim as built")
          (is (every? string? (keys wire)) "JSON keys")
          (is (string? (get wire "old-root")) "roots as hex")
          (is (not (contains? wire "siblings")) "no top-level siblings")
          (is (not (contains? wire "witness")) "no opening")
          (is (ok? (ev ctx back)) "the claim read back off the wire")
          (is (= (:nullifier r) (:nullifier (ev ctx back))))
          (is (= [(:group-root r) (:principal-commit r)] [(:group-root (ev ctx back)) (:principal-commit (ev ctx back))])))))))
