(ns eve.aegis.ballot
  "Option 1 of aegis's clear evaluation: anonymous one-show ballots from partially-blind RSA
   (eve.aegis.rsabssa, RSAPBSSA-SHA384-PSS) for the chat spec's cast-vote branch (:p/semaphore-membership under the
   :per-poll scope).

   ISSUANCE. A member authenticates to the issuer in the clear and asks for ONE ballot per scope: the scope's public
   metadata is info = SHA-256(scope-kind ‖ atom-id ‖ scope-id) (per-poll: the poll id). The member mints a fresh
   one-time Ed25519 key and blinds its PUBLIC key as the ballot message; the issuer blind-signs under the key the
   metadata derives, records {:info :member :blinded} in its issuance log, and refuses a second request by the same
   member for the same info. The issuer learns who took a ballot for which poll, never which vote it became.

   SPENDING. The vote claim reveals the ballot (the one-time public key and its signature) and is signed by the
   one-time key over the claim digest, so a ballot seen on the wire cannot be re-spent on another vote. Any checker
   verifies the ballot with the issuer's public key and the scope's metadata; SHA-256 of the ballot signature is the
   nullifier, held in the spent set: a second spend repeats it.

   ACCOUNTABILITY. The issuer publishes, per scope, an Ed25519-signed count statement {:info :count :roster-size}.
   Over-issuance is provable from the artefacts alone: a log with more entries for a scope than the roster has
   members, a statement whose count exceeds the roster, a spent set larger than the statement's count, or two
   statements for one scope that disagree.

   State is a value (atoms all the way down: the caller holds it in one); nothing here keeps module state."
  (:require [eve.aegis.rsabssa :as rsa]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

(defn scope-info "SHA-256(scope-kind ‖ atom-id ‖ scope-id), the ballot's public metadata" [scope-kind atom-id scope-id]
  (crypto/sha256 (-> (b/utf8 (str scope-kind)) (into (b/utf8 (str atom-id))) (into (b/utf8 (str scope-id))))))

;; ── the issuer (a value)

(defn make-issuer "sk: the RSA private key {:n :e :d :p :q}; stmt-seed: the Ed25519 seed that signs count statements;
   roster: the member ids allowed a ballot" [sk stmt-seed roster]
  {:sk sk :pk (select-keys sk [:n :e]) :stmt-seed stmt-seed :stmt-pk (crypto/ed25519-public-key stmt-seed)
   :roster (set roster) :issued {} :log []})

(defn request-ballot
  "[issuer' blind-sig] for a member's blinded ballot under the scope's info, or an ex-info: not a member, or already
   issued for this info. arith: the issuer's big-integer rows (its host's native ones)"
  ([issuer member info blinded] (request-ballot issuer member info blinded (rsa/adapter-arith)))
  ([issuer member info blinded arith]
   (let [ih (b/hex info)]
     (when-not (contains? (:roster issuer) member) (throw (ex-info "ballot: not a member" {:member member})))
     (when (contains? (get-in issuer [:issued ih]) member) (throw (ex-info "ballot: already issued" {:member member :info ih})))
     (let [blind-sig (rsa/blind-sign (:sk issuer) blinded info arith)]
       [(-> issuer
            (update-in [:issued ih] (fnil conj #{}) member)
            (update :log conj {:info ih :member member :blinded (crypto/sha256 (vec blinded))}))
        blind-sig]))))

(defn- stmt-bytes [info count roster-size]
  (-> (b/utf8 "aegis.ballot.count.v1") (into (vec info)) (into (b/utf8 (str count "/" roster-size)))))

(defn sign-statement "the signed count statement {:info :count :roster-size :sig} under the issuer's statement seed"
  [stmt-seed info c r]
  {:info (vec info) :count c :roster-size r :sig (crypto/ed25519-sign stmt-seed (stmt-bytes info c r))})

(defn count-statement "the issuer's signed per-scope issuance count" [issuer info]
  (sign-statement (:stmt-seed issuer) info (count (get-in issuer [:issued (b/hex info)])) (count (:roster issuer))))

(defn verify-statement [stmt-pk {:keys [info count roster-size sig]}]
  (crypto/ed25519-verify stmt-pk (stmt-bytes info count roster-size) sig))

;; ── the member

(defn request-message
  "the bytes a member signs (Ed25519, the roster key) to ask for a ballot: the atom, the scope's info and the blinded
   ballot, so a request names its scope and cannot be replayed for another"
  [atom-id info blinded]
  (b/utf8 (str "aegis.ballot.request.v1|" atom-id "|" (b/hex info) "|" (b/hex blinded))))

(defn new-ballot
  "the blinded ballot for a scope: random → 32 bytes for the one-time key, 48 for the salt, (count n) for r.
   {:token-seed :token-pk :blinded :inv}"
  ([pk info random] (new-ballot pk info random (rsa/adapter-arith)))
  ([pk info random arith]
   (let [seed (random 32)
         token-pk (crypto/ed25519-public-key seed)
         salt (random 48)
         ;; r mod n through the arith rows (the host's native integers): eve.net.bignum's portable divmod builds limb
         ;; vectors a ValueScript program never frees, and ran a client out of its 2 GiB slab on some draws of r
         r ((:modmul arith) (random (count (:n pk))) [1] (:n pk))
         r (if (every? zero? r) (assoc r (dec (count r)) 3) r)
         {:keys [blinded-msg inv]} (rsa/blind pk token-pk {:salt salt :r r :info info :arith arith})]
     {:token-seed seed :token-pk token-pk :blinded blinded-msg :inv inv})))

(defn finish-ballot "the ballot {:token-seed :token-pk :token-sig} once the issuer answered"
  ([pk info ballot blind-sig] (finish-ballot pk info ballot blind-sig (rsa/adapter-arith)))
  ([pk info {:keys [token-seed token-pk inv]} blind-sig arith]
   {:token-seed token-seed :token-pk token-pk
    :token-sig (rsa/finalize pk token-pk blind-sig inv info arith rsa/SALT-LEN)}))

(defn spend "the claim's auth: the ballot revealed, the digest signed by the one-time key" [ballot digest]
  {:kind :ballot :token-pk (:token-pk ballot) :token-sig (:token-sig ballot)
   :sig (crypto/ed25519-sign (:token-seed ballot) (vec digest))})

(defn nullifier [auth] (crypto/sha256 (vec (:token-sig auth))))

(defn verify-ballot "is the auth a valid spend of a ballot of this issuer under this scope's info, over this digest"
  ([pk info auth digest] (verify-ballot pk info auth digest (rsa/adapter-arith)))
  ([pk info {:keys [token-pk token-sig sig]} digest arith]
   (boolean (and (= 32 (count token-pk))
                 (rsa/verify pk token-pk token-sig info arith rsa/SALT-LEN)
                 (crypto/ed25519-verify token-pk (vec digest) sig)))))

;; ── auditing from the artefacts

(defn over-issued?
  "is a scope over-issued, judged from the public artefacts alone: the issuance log, the roster size, the issuer's
   signed statement (or nil) and the scope's spent set"
  [{:keys [log roster-size stmt stmt-pk spent info]}]
  (let [ih (b/hex info)
        entries (filter (fn [e] (= ih (:info e))) log)
        members (map :member entries)]
    (boolean (or (> (count entries) roster-size)
                 (not= (count members) (count (set members)))
                 (and stmt (or (not (verify-statement stmt-pk stmt))
                               (> (:count stmt) roster-size)
                               (> (count spent) (:count stmt))))
                 (> (count spent) (count entries))))))
