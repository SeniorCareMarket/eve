(ns eve.aegis.claims
  "The claim format and the clear evaluator of an aegis app spec (aegis's clear evaluation): what
   the proof era built as circuit inputs and its broker bound as public inputs, re-cast as a claim any checker
   verifies by recomputing — no prover. A broker evaluates each write's claim with it. The walk mirrors spec_rules.vs (the record
   schema and leaf, binds-principal?) and policy.vs (the branch table read from the spec); every primitive of the chat
   spec's ten branches has a clear check here.

   THE CLAIM  {:branch name  :record r  :principal username|nil  :old-root  :new-root  :auth {...}
               [:key k :value v]  (key-assoc / dissoc)  [:lockbox lb]  (a lockbox write)}
   A claim carries no opening. An append (:p/merkle-tail-append), a key-assoc and a key-dissoc bind the digests of the
   atom's state before and after the write (the caller's old_key / new_key) as their two roots, and the evaluator reads
   the write itself from those two states — the append through ctx :append-of, the map write through ctx :map-of; a
   session is read in the registry's value (ctx :session-registry), a remote atom's member in that atom's value (ctx
   :cross-atom). A claim carrying :siblings, a :witness (the retired frontier, key-assoc, key-dissoc and remote-atom
   openings) or a session auth's :opening (the retired registry opening) is refused :malformed.
   and   :auth    {:kind :ed25519 :sig}                  a named branch: the principal's signature over the digest
                  {:kind :ballot :token-pk :token-sig :sig} (eve.aegis.ballot/spend)   anonymous, Option 1
                  {:kind :ring :sig {:c1 :s :I}}          (eve.aegis.clsag/sign)       anonymous, Option 2
                  {:kind :session :pk :issuance :sig}     (eve.aegis.session)          :per-session
   THE DIGEST  (claim-digest spec-id claim) = merkle/chain over [spec-id, branch, path, record leaf, old-root,
   new-root]: what every signature and ring signature signs, binding the write to the spec and the state chain —
   replay against another state is impossible by construction, so a named branch's nullifier is only a spent-set key.

   THE CONTEXT  {:spec :spec-id :atom-id :epoch :now
                 :append-of (fn [path] {:old-count :new-count :leaf})       (an append, read from the two states: the
                                                                             vector at path — the branch's path as
                                                                             string segments — holds old-count records
                                                                             before the write and new-count after;
                                                                             leaf is the record leaf of the last)
                 :map-of (fn [path] {:old m :new m'})                       (a key-assoc / key-dissoc, read from the
                                                                             two states: the map at path — string
                                                                             segments, as :append-of's — before the
                                                                             write ({} where there is none) and after)
                 :digest-of (fn [v] digest)                                 (the 32-byte block digest of a value the
                                                                             evaluator read from this context: a
                                                                             :groups roster, an :issuers record, the
                                                                             :session-registry, :principals)
                 :principals {username pubkey}  :quarantine #{username}
                 :groups {\"domain/name\" #{pubkey}}                        (rosters: Ed25519 public keys)
                 :issuers {\"domain/name\" {:n :e [:stmt-pk]}}               (Option 1: the group's ballot issuer)
                 :statements {info-hex {:info :count :roster-size :sig}}    (optional: the issuer's signed per-scope
                                                                             issuance counts; given for a ballot's
                                                                             scope, a spend past its count is refused)
                 :spent-counts {info-hex n}                                 (the ballots already spent per scope)
                 :session-registry {pk-hex issuance}                        (the session registry's value: the
                                                                             sessions atom's; nil when none is kept)
                 :cross-atom {atom-symbolic-id value}                       (the value of each atom a
                                                                             :p/remote-atom-state primitive reads,
                                                                             on this broker; absent: unresolved)
                 :spent #{nullifier}  or  :spent? (fn [nullifier] boolean)}  (the spent set, or a membership test of
                                                                             the caller's own store)
                 :lockboxes {path-string lockbox}                           (read regions: each region's stored
                                                                             lockbox, eve.aegis.regions)
   (evaluate ctx claim) -> {:ok true :branch name :nullifier bytes :scope s :digest bytes :group-root bytes
                            :principal-commit bytes [:ballot-info hex]} or {:ok false :reason keyword ...}.

   THE VERDICT's two attestation fields — what the kernel's seal_block_attested writes into a record's GROUP_ROOT and
   PRINCIPAL_COMMIT (ExtCID v3) — are 32-byte vectors of ints 0..255
   (eve.net.bytes), the representation of every digest in this namespace:
     :group-root        (digest-of v) of the value the auth check read membership in: the group's roster (a named
                        write to a group path; a ring), the group's issuer record (a ballot), the session registry
                        (a session), the principal registry :principals (a named write to no group)
     :principal-commit  named: (principal-commit acl principal), SHA-256 of the principal's UTF-8 name; ring: the key
                        image; ballot: SHA-256 of the token public key; session: (session-commit pk issuance), the
                        session's leaf in its registry
   NULLIFIERS — the spent-set key, each computable before the claim is signed, since the spent set is in the state
   whose digest the claim's new-root is: named (named-nullifier spec claim principal-commit) — the commitment, the old
   root and what the write puts; ring the key image; ballot
   SHA-256 of the token signature; session the session key.

   THE SPEC ID  (spec-id spec-bytes) = SHA-256(spec bytes ‖ release-hash), release-hash = SHA-256(RELEASE): the
   app spec's exact bytes bound to the evaluator release that reads them. Every claim digest carries it, so a
   signature made under one spec or one evaluator release verifies under no other.

   THE DSL's TWO CLEAR-EVALUATION ADDITIONS: an ACL's :commitFn \"sha256\" (a
   per-key-principal path's key is the lowercase hex SHA-256 of the principal's UTF-8 name; with no :commitFn the key
   is the name itself; \"poseidon2\" needs the retired circuit and is refused), and a :groups entry's :anonymity —
   :ballot (the default, Option 1) or :ring (Option 2) — the one auth kind an anonymous write to that group's path
   may carry.

   THE WIRE  (claim->wire claim) / (wire->claim w): the claim as JSON-ready data — keywords as their names, every
   byte value (roots, signatures, public keys) as lowercase hex, the record and the lockbox as they stand — and back.

   GENESIS  (genesis spec) is the atom's value before its first write — the old state of a genesis claim, whose
   :old-root is its digest: in branch order every path a branch appends to an empty vector, every path a key-assoc /
   key-dissoc writes an empty map (the spec's keyword paths), over the evaluator's band {:aegis {:spent {}
   :ballot-spends {}}}. The one definition: the gate, the VS client and eve's client all read it here.

   APPLYING  (next-state state claim spec verdict) is the atom's state after the claim's write, at the spec's keyword
   paths: the claim's record appended to the vector at the branch's path AS THE CLAIM CARRIES IT — the same value, so
   an attested record keeps its attestation (its block's header in VS, its metadata in eve: eve.net.codec/attest) —
   the key assoc'd / dissoc'd, the lockbox put; and the evaluator's own band, [:aegis :spent <nullifier hex>] true and,
   for a ballot, [:aegis :ballot-spends <ballot-info hex>] the scope's spend count. No branch writes under aegis
   (refused :aegis-path); ctx :spent? and :spent-counts are that band, read by the caller.

   READ REGIONS (the read-regions spike; eve.aegis.regions): a spec's
   :read-regions name the paths encrypted under a region key, its readers' group and its lockbox path. Two primitives:
   :p/lockbox-write {:path-vec p :region r} owns the lockbox's root (a claim's :lockbox; the roots are
   regions/lockbox-root of the stored and the posted lockbox) and checks a rotate or an extend against the readers'
   roster; :p/region-epoch {:region r} holds a record's :key_epoch to the lockbox's epoch and refuses a stale region.
   The broker checks structure only: it holds no region key.

   WHAT IS NOT HERE: transport, the atom's commit (the caller stores next-state), rollup
   batching (a batch is claims evaluated in order), reading another atom (ctx :cross-atom holds its value)."
  (:require [eve.aegis.ballot :as ballot]
            [eve.aegis.clsag :as clsag]
            [eve.aegis.merkle :as merkle]
            [eve.aegis.regions :as regions]
            [eve.aegis.session :as session]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

;; ── the spec

(defn- seg [s] (if (keyword? s) (name s) (str s)))
(defn path-string [p] (apply str (interpose "/" (map seg p))))

(defn branches [spec] (get-in spec [:policy :children]))
(defn branch-named [spec name] (first (filter (fn [br] (= name (:branch br))) (branches spec))))
(defn primitives [branch] (filter (fn [c] (= :primitive (:op c))) (:children branch)))
(defn primitive [branch pname] (first (filter (fn [p] (= pname (:name p))) (primitives branch))))

(defn mutating-path "the path the branch's root-owning primitive mutates" [branch]
  (some (fn [p] (when (#{:p/merkle-tail-append :p/tail-append :p/key-assoc :p/key-dissoc :p/lockbox-write} (:name p)) (:path-vec (:args p))))
        (primitives branch)))

(defn record-schema [spec path]
  (let [ps (path-string path)]
    (first (filter (fn [e] (= ps (path-string (:path e)))) (get-in spec [:records :entries])))))

(defn acl-for "the longest-prefix ACL config over a path, or nil" [spec path]
  (let [segs (mapv seg path)]
    (->> (get-in spec [:acls :entries])
         (filter (fn [e] (let [p (mapv seg (:path e))] (and (<= (count p) (count segs)) (= p (subvec segs 0 (count p)))))))
         (sort-by (fn [e] (- (count (:path e)))))
         first
         :config)))

(defn scope-for "the nullifier scope governing a path" [spec path]
  (let [segs (mapv seg path)
        ns (:nullifier-scopes spec)
        hit (some (fn [e] (let [p (mapv seg (:path e))] (when (and (<= (count p) (count segs)) (= p (subvec segs 0 (count p)))) (:scope e))))
                  (:byPath ns))]
    (or hit (:default ns) :per-epoch)))

(defn binds-principal? "spec_rules.vs's rule: does the branch read the principal" [branch]
  (boolean (some (fn [p] (or (#{:p/not-quarantined :p/signed-by :p/remote-atom-state} (:name p))
                             (some (fn [v] (and (sequential? v) (= :principal (first v)))) (vals (:args p)))))
                 (primitives branch))))

(defn group-name [{:keys [groupDomain groupName]}] (str groupDomain "/" groupName))

(defn- kpath "a spec path as the state keys it: keywords (a string segment read as its keyword)" [p]
  (mapv (fn [s] (if (keyword? s) s (keyword (str s)))) p))

(defn genesis
  "the atom's value before its first write (the ns docstring's GENESIS): every append path [], every key-assoc /
   key-dissoc path {}, over the evaluator's band"
  [spec]
  (reduce (fn [st br]
            (let [p (kpath (mutating-path br))]
              (cond (primitive br :p/merkle-tail-append) (assoc-in st p [])
                    (or (primitive br :p/key-assoc) (primitive br :p/key-dissoc)) (assoc-in st p {})
                    :else st)))
          {:aegis {:spent {} :ballot-spends {}}}
          (branches spec)))

(def RELEASE "eve.aegis.claims 1 (2026-10-23)")

(defn release-hash "SHA-256 of the evaluator release identifier" [] (crypto/sha256 (b/utf8 RELEASE)))

(defn spec-id "SHA-256(spec bytes ‖ release-hash): the spec's exact bytes bound to this evaluator release" [spec-bytes]
  (crypto/sha256 (into (vec spec-bytes) (release-hash))))

(def ANONYMITY #{:ballot :ring})

(defn group-anonymity "a group's declared anonymous auth kind: its :groups entry's :anonymity, else :ballot" [spec g]
  (or (some (fn [e] (when (= g (str (:domain e) "/" (:name e))) (:anonymity e))) (get-in spec [:groups :entries]))
      :ballot))

(defn principal-key "the key a per-key-principal path holds for a principal under the ACL's :commitFn, or nil (unsupported)"
  [commit-fn principal]
  (cond (nil? commit-fn) principal
        (= "sha256" commit-fn) (b/hex (crypto/sha256 (b/utf8 principal)))
        :else nil))

(defn principal-commit
  "A named write's 32-byte principal commitment under the ACL's :commitFn (principal-key): the \"sha256\" key's bytes,
   or with no :commitFn the key's field (field-of a string: SHA-256 of its UTF-8) — SHA-256 of the name either way;
   nil when the :commitFn is unsupported"
  [acl principal]
  (let [cf (:commitFn acl) k (principal-key cf principal)]
    (cond (nil? k) nil
          (= "sha256" cf) (b/unhex k)
          :else (merkle/field-of k))))

(defn session-commit "a session's commitment: its leaf in the session registry {pk-hex issuance} (eve.aegis.session)" [pk issuance]
  (merkle/sidecar-leaf (b/hex pk) (merkle/field-of issuance)))

(defn- bytes32? [x] (and (sequential? x) (= 32 (count x)) (every? (fn [i] (and (integer? i) (<= 0 i 255))) x)))

;; ── records and the digest

(defn record-refusal "nil, or why the record does not match its schema (spec_rules.vs record-refusal)" [schema record]
  (cond (nil? schema) :no-schema
        (not (map? record)) :not-a-map
        :else (let [keys-of (fn [r] (set (map (fn [k] (seg k)) (keys r))))
                    present (keys-of record)
                    required (set (map name (:required schema)))
                    allowed (into required (map name (:optional schema)))]
                (cond (not (every? present required)) :required-key-missing
                      (not (every? allowed present)) :undeclared-key
                      :else nil))))

(defn record-leaf [schema record] (merkle/record-leaf (:leaf schema) record))

(defn claim-digest [spec-id {:keys [branch old-root new-root record key] :as claim} path leaf]
  (merkle/chain [(merkle/field-of (vec spec-id)) (merkle/field-of branch) (merkle/field-of (path-string path))
                 leaf (vec old-root) (vec new-root) (merkle/field-of (when key (seg key)))]))

(defn named-nullifier
  "A named write's spent-set key, computable from the unsigned claim: the chain over the principal commitment, the old
   root, and what the write is — its path, its key, and what it puts (the record leaf, the assoc'd value's field, the
   posted lockbox's root; 32 zero bytes for a dissoc). Never the new root: the spent set is in the state the new root
   digests. Never the old root alone: one principal's distinct writes on one old state (two records, two keys) must
   not share a key, and a lockbox's root is the root of its own lockbox, which repeats (32 zero bytes when absent)."
  [spec claim principal-commit]
  (let [branch (branch-named spec (:branch claim))
        path (mutating-path branch)
        put (cond (primitive branch :p/merkle-tail-append) (record-leaf (record-schema spec path) (:record claim))
                  (primitive branch :p/key-assoc) (merkle/field-of (:value claim))
                  (primitive branch :p/lockbox-write) (regions/lockbox-root (:lockbox claim))
                  :else (vec (repeat 32 0)))]
    (merkle/chain [(vec principal-commit) (vec (:old-root claim)) (merkle/field-of (path-string path))
                   (merkle/field-of (when (:key claim) (seg (:key claim)))) (vec put)])))

;; ── evaluation

(defn- fail [reason & [detail]] {:ok false :reason reason :detail detail})

(defn- resolve-eq "a :p/field-equals side: [:new ... :last k] reads the appended record, [:principal :username] the principal" [side claim]
  (case (first side)
    :principal (:principal claim)
    :new (let [k (last side)] (when (= :last (nth side (- (count side) 2))) (merkle/record-get (:record claim) k)))
    nil))

(defn- hex-pubkey? [s] (and (string? s) (= 64 (count s)) (every? (fn [c] (b/hex-value c)) s)))

(defn- check-primitive [ctx claim branch {:keys [name args]} schema leaf path]
  (case name
    :p/not-quarantined
    (cond (nil? (:principal claim)) (fail :no-principal)
          (contains? (:quarantine ctx) (:principal claim)) (fail :quarantined)
          :else nil)

    :p/merkle-tail-append
    (let [append-of (:append-of ctx)
          a (when append-of (append-of (mapv seg (:path-vec args))))
          {:keys [old-count new-count]} a]
      (cond (not (map? a)) (fail :no-append-state {:path (path-string (:path-vec args))})
            (not (and (integer? old-count) (integer? new-count) (= new-count (inc old-count))))
            (fail :append-count {:old-count old-count :new-count new-count})
            (not= (vec (:leaf a)) (vec leaf)) (fail :append-leaf)
            :else nil))

    :p/field-equals
    (let [a (resolve-eq (:in args) claim) e (resolve-eq (:eq args) claim)]
      (if (and a (= a e)) nil (fail :field-equals {:in a :eq e})))

    :p/semaphore-membership nil ; the anonymous gate is checked with the ACL below

    (:p/key-assoc :p/key-dissoc)
    ;; read like an append: the map at the path in the two states (ctx :map-of); the claim's roots are the two state
    ;; digests, bound by the caller (the gate's :claim/state-roots)
    (let [k (when (some? (:key claim)) (seg (:key claim))) v (:value claim)
          read-map (:map-of ctx)
          m (when read-map (read-map (mapv seg (:path-vec args))))
          old (:old m) new (:new m)]
      (cond (not (string? k)) (fail :no-key)
            (and (= name :p/key-assoc) (not (hex-pubkey? v))) (fail :member-value-not-a-key)
            (not (and (map? old) (map? new))) (fail :no-map-state {:path (path-string (:path-vec args))})
            (= name :p/key-assoc)
            (cond (contains? old k) (fail :key-present {:key k})
                  (not= new (assoc old k v)) (fail :map-change {:path (path-string (:path-vec args))})
                  :else nil)
            :else
            (cond (not (contains? old k)) (fail :key-absent {:key k})
                  (not= new (dissoc old k)) (fail :map-change {:path (path-string (:path-vec args))})
                  :else nil)))

    :p/lockbox-write
    (let [region (regions/region-named (:spec ctx) (:region args))
          ps (path-string (:path-vec args))
          old (get (:lockboxes ctx) ps)
          new (:lockbox claim)
          roster (set (map b/hex (get (:groups ctx) (when region (regions/reader-group region)))))]
      (cond (nil? region) (fail :no-such-region {:region (:region args)})
            (not= ps (path-string (:lockbox region))) (fail :lockbox-path {:region (:region args)})
            (not= (vec (:old-root claim)) (regions/lockbox-root old)) (fail :old-root-mismatch)
            (not (map? new)) (fail :lockbox-malformed)
            (not= (vec (:new-root claim)) (regions/lockbox-root new)) (fail :new-root-mismatch)
            :else (when-let [why (regions/lockbox-refusal old new roster (regions/history-mode region))] (fail why))))

    :p/region-epoch
    (let [region (regions/region-named (:spec ctx) (:region args))
          lockbox (when region (get (:lockboxes ctx) (path-string (:lockbox region))))
          roster (set (map b/hex (get (:groups ctx) (when region (regions/reader-group region)))))]
      (if (nil? region)
        (fail :no-such-region {:region (:region args)})
        (when-let [why (regions/write-refusal lockbox roster (merkle/record-get (:record claim) :key_epoch))]
          (fail why {:region (:region args)}))))

    :p/remote-atom-state
    ;; the principal is a key of the other atom's value (ctx :cross-atom, read by the caller on its own broker)
    (let [{:keys [atom-symbolic-id]} args
          remote (get (:cross-atom ctx) atom-symbolic-id)]
      (cond (not (map? remote)) (fail :remote-atom-unresolved {:atom atom-symbolic-id})
            (nil? (:principal claim)) (fail :no-principal)
            (not (contains? remote (:principal claim))) (fail :not-in-remote-atom {:atom atom-symbolic-id})
            :else nil))

    (fail :unknown-primitive {:name name})))

(defn- scope-id "the record key a :per-poll scope reads (the schema's :scope-key)" [schema record]
  (merkle/record-get record (:scope-key schema)))

(defn- check-named-auth
  "a refusal, or {:roster v :principal-commit pc :nullifier n}: v the value the principal's membership was read in"
  [ctx claim digest acl path]
  (let [{:keys [principal auth]} claim
        pk (get (:principals ctx) principal)
        pc (when principal (principal-commit acl principal))]
    (cond (nil? principal) (fail :no-principal)
          (not= :ed25519 (:kind auth)) (fail :auth-kind {:expected :ed25519})
          (nil? pk) (fail :unknown-principal {:principal principal})
          (not (crypto/ed25519-verify pk digest (:sig auth))) (fail :signature)
          (nil? pc) (fail :commit-fn-unsupported {:commitFn (:commitFn acl)})
          :else
          (let [named {:principal-commit pc :nullifier (named-nullifier (:spec ctx) claim pc)}]
            (cond (nil? acl) (assoc named :roster (:principals ctx))
                  (= "per-key-principal" (:kind acl))
                  (if (not= (principal-key (:commitFn acl) principal) (seg (nth path (count (:path acl)) nil)))
                    (fail :not-the-key-owner)
                    (assoc named :roster (:principals ctx)))
                  (= "group" (:kind acl))
                  (let [g (group-name acl) roster (get (:groups ctx) g)]
                    (cond (nil? roster) (fail :no-roster {:group g})
                          (not (contains? roster pk)) (fail :not-a-member {:group g})
                          :else (assoc named :roster roster)))
                  :else (fail :acl-kind {:kind (:kind acl)}))))))

(defn- statement-refusal
  "nil, or why a ballot spend under `info` exceeds the issuer's signed count for its scope (ctx :statements)"
  [ctx issuer info]
  (let [ih (b/hex info)
        stmt (get (:statements ctx) ih)]
    (cond (nil? stmt) nil
          (and (:stmt-pk issuer) (not (ballot/verify-statement (:stmt-pk issuer) stmt))) (fail :issuance-statement)
          (not= (vec info) (vec (:info stmt))) (fail :issuance-statement)
          (>= (get (:spent-counts ctx) ih 0) (:count stmt)) (fail :over-issued {:count (:count stmt)})
          :else nil)))

(defn- check-anonymous-auth "cast-vote: a ballot of the group's issuer, or a ring signature over the group's roster" [ctx claim digest acl schema scope]
  (let [{:keys [auth record]} claim
        g (when acl (group-name acl))
        sid (scope-id schema record)
        declared (when g (group-anonymity (:spec ctx) g))]
    (cond (not= "group" (:kind acl)) (fail :anonymous-needs-a-group)
          (nil? sid) (fail :no-scope-id)
          (not (contains? ANONYMITY declared)) (fail :anonymity-unknown {:group g :anonymity declared})
          (not= declared (:kind auth)) (fail :anonymity {:group g :declared declared :got (:kind auth)})
          (= :ballot (:kind auth))
          (let [issuer (get (:issuers ctx) g)
                info (ballot/scope-info scope (:atom-id ctx) sid)]
            (cond (nil? issuer) (fail :no-issuer {:group g})
                  (not (ballot/verify-ballot issuer info auth digest)) (fail :ballot)
                  ;; a ballot already spent is a double spend (the spent set refuses it below, :nullifier-spent), not
                  ;; one more spend against the count: the count bounds the distinct spends
                  :else (let [nullifier (ballot/nullifier auth)
                              spent (if-let [spent? (:spent? ctx)] (spent? nullifier) (contains? (:spent ctx) nullifier))]
                          (or (when-not spent (statement-refusal ctx issuer info))
                              {:nullifier nullifier :ballot-info (b/hex info) :roster issuer
                               :principal-commit (crypto/sha256 (vec (:token-pk auth)))}))))
          (= :ring (:kind auth))
          (let [roster (get (:groups ctx) g)
                scope-bytes (b/utf8 (str (name scope) "|" (:atom-id ctx) "|" sid))]
            (cond (nil? roster) (fail :no-roster {:group g})
                  (not (clsag/verify {:msg digest :ring (vec (sort merkle/compare-bytes roster)) :scope scope-bytes :sig (:sig auth)})) (fail :ring)
                  :else (let [key-image (vec (get-in auth [:sig :I]))]
                          {:nullifier key-image :roster roster :principal-commit key-image})))
          :else (fail :auth-kind {:expected #{:ballot :ring}}))))

(defn- check-session-auth
  "a delegated session: registered in the registry's value (ctx :session-registry) with the claim's issuance, within
   the spec's lifetime, and the session key's signature over the digest"
  [ctx claim digest]
  (let [{:keys [auth]} claim
        {:keys [pk issuance sig]} auth
        reg (:session-registry ctx)
        max-life (get-in ctx [:spec :sessions :max-lifetime-epochs])]
    (cond (not= :session (:kind auth)) (fail :auth-kind {:expected :session})
          (nil? reg) (fail :no-session-registry)
          (not (session/valid-session? reg pk issuance (:now ctx) max-life)) (fail :session)
          (not (crypto/ed25519-verify pk digest sig)) (fail :session-signature)
          :else {:nullifier (vec pk) :roster reg :principal-commit (session-commit pk issuance)})))

(defn- aegis-path? "does the path begin with aegis: the evaluator's own band of the state (next-state)" [path]
  (boolean (and (seq path) (= "aegis" (seg (first path))))))

(defn evaluate [ctx claim]
  (let [spec (:spec ctx)
        branch (branch-named spec (:branch claim))]
    (cond
      (nil? branch) (fail :no-such-branch {:branch (:branch claim)})
      (contains? claim :siblings) (fail :malformed {:retired :siblings})
      (contains? claim :witness) (fail :malformed {:retired :witness})
      (and (map? (:auth claim)) (contains? (:auth claim) :opening)) (fail :malformed {:retired :opening})
      (aegis-path? (mutating-path branch)) (fail :aegis-path {:branch (:branch claim)})
      :else
      (let [path (mutating-path branch)
            appends? (primitive branch :p/merkle-tail-append)
            schema (when appends? (record-schema spec path))
            refusal (when appends? (record-refusal schema (:record claim)))
            leaf (if appends? (when-not refusal (record-leaf schema (:record claim))) (vec (repeat 32 0)))
            digest (when leaf (claim-digest (:spec-id ctx) claim path leaf))
            acl (acl-for spec path)
            scope (scope-for spec path)
            named? (binds-principal? branch)]
        (or (when refusal (fail :record refusal))
            (some (fn [p] (check-primitive ctx claim branch p schema leaf path)) (primitives branch))
            (let [auth-result (cond named? (check-named-auth ctx claim digest acl path)
                                    (= :per-session scope) (check-session-auth ctx claim digest)
                                    :else (check-anonymous-auth ctx claim digest acl schema scope))]
              (if (false? (:ok auth-result)) auth-result
                  (let [nullifier (:nullifier auth-result)
                        digest-of (:digest-of ctx)
                        group-root (when digest-of (digest-of (:roster auth-result)))]
                    (cond (if-let [spent? (:spent? ctx)] (spent? nullifier) (contains? (:spent ctx) nullifier))
                          (fail :nullifier-spent {:scope scope})
                          (nil? digest-of) (fail :no-digest-of)
                          (not (bytes32? group-root)) (fail :digest-of)
                          :else
                          (cond-> {:ok true :branch (:branch claim) :nullifier nullifier :scope scope :digest digest
                                   :group-root (vec group-root) :principal-commit (vec (:principal-commit auth-result))}
                            (:ballot-info auth-result) (assoc :ballot-info (:ballot-info auth-result))))))))))))

;; ── building claims (the client's side, shared with the tests)

(defn claim-digest-of "the digest a claim's auth signs, computed from the spec as evaluate computes it" [spec spec-id claim]
  (let [branch (branch-named spec (:branch claim))
        path (mutating-path branch)
        leaf (if (primitive branch :p/merkle-tail-append)
               (record-leaf (record-schema spec path) (:record claim))
               (vec (repeat 32 0)))]
    (claim-digest spec-id claim path leaf)))

(defn build-claim
  "The unsigned claim for a write on `branch-name`, from the state the caller holds:
   {:record r :principal p} for an append, {:key k :value v :principal p} for a key-assoc, {:key k :principal p} for a
   key-dissoc — each with its roots the caller's :old-root and :new-root (the digests of the atom's state before and
   after the write; the claim carries no opening) —
   {:lockbox lb :principal p} for a lockbox write (the stored lockbox: (get lockboxes path-string), else none)."
  [spec branch-name {:keys [record principal key value lockbox]} {:keys [old-root new-root lockboxes]}]
  (let [branch (branch-named spec branch-name)
        _ (when (nil? branch) (throw (ex-info "build-claim: no such branch" {:branch branch-name})))
        path (mutating-path branch)
        ps (path-string path)
        base (cond-> {:branch branch-name} principal (assoc :principal principal))
        roots (fn [m]
                (if (and (bytes32? old-root) (bytes32? new-root))
                  (assoc m :old-root (vec old-root) :new-root (vec new-root))
                  (throw (ex-info "build-claim: the write binds the two state digests, :old-root and :new-root (32 bytes each)"
                                  {:branch branch-name}))))]
    (cond (primitive branch :p/merkle-tail-append) (roots (assoc base :record record))
          (primitive branch :p/key-assoc) (roots (assoc base :key key :value value))
          (primitive branch :p/key-dissoc) (roots (assoc base :key key))
          (primitive branch :p/lockbox-write)
          (assoc base :lockbox lockbox :old-root (regions/lockbox-root (get lockboxes ps)) :new-root (regions/lockbox-root lockbox))
          :else (throw (ex-info "build-claim: the branch owns no root" {:branch branch-name})))))

;; ── :append-of and :map-of over two state values (the gate reads the same from the atom's pages)

(defn append-of
  "ctx :append-of for an append between the state values old and new (THE CONTEXT above): (fn [segs] {:old-count
   :new-count :leaf}) — the vector at the branch's path, given as string segments and read at the state's own keys
   (keywords, the spec's :path-vec; a string-keyed state is read at the strings), holds old-count records before the
   write and new-count after, leaf the record leaf of new's last under the path's schema; nil where either is no vector"
  [spec old new]
  (fn [segs]
    (let [at (fn [st] (let [v (get-in st (mapv keyword segs))] (if (vector? v) v (get-in st (mapv seg segs)))))
          o (at old) n (at new)
          schema (record-schema spec segs)]
      (when (and (vector? o) (vector? n) schema)
        {:old-count (count o) :new-count (count n) :leaf (when (seq n) (record-leaf schema (peek n)))}))))

(defn map-of
  "ctx :map-of for a key-assoc / key-dissoc between the state values old and new (THE CONTEXT above): (fn [segs] {:old
   m :new m'}) — the map at the branch's path, given as string segments and read at the state's keywords, before the
   write ({} where old holds none) and after; nil where new holds no map there"
  [old new]
  (fn [segs]
    (let [p (kpath segs)
          o (get-in old p) n (get-in new p)]
      (when (map? n) {:old (if (map? o) o {}) :new n}))))

;; Every signer answers {:claim c :principal-commit pc}: the signed claim, and the principal commitment the evaluator's
;; verdict will carry (the ns docstring's THE VERDICT) — what the client attests the record with.

(defn sign-claim "the claim with the principal's Ed25519 auth (a named branch), its digest computed from the spec" [spec spec-id claim seed]
  (let [acl (acl-for spec (mutating-path (branch-named spec (:branch claim))))]
    {:claim (assoc claim :auth {:kind :ed25519 :sig (crypto/ed25519-sign seed (claim-digest-of spec spec-id claim))})
     :principal-commit (principal-commit acl (:principal claim))}))

(defn sign-ballot "the claim spent with a finished ballot (eve.aegis.ballot/finish-ballot)" [spec spec-id claim ballot]
  (let [auth (ballot/spend ballot (claim-digest-of spec spec-id claim))]
    {:claim (assoc claim :auth auth) :principal-commit (crypto/sha256 (vec (:token-pk auth)))}))

(defn sign-ring "the claim with a ring signature over the group's roster (Option 2): seed, the roster, :random (fn [n])"
  [spec spec-id claim {:keys [seed roster atom-id scope-id random]}]
  (let [branch (branch-named spec (:branch claim))
        scope (scope-for spec (mutating-path branch))
        ring (vec (sort merkle/compare-bytes roster))
        pk (crypto/ed25519-public-key seed)
        index (first (keep-indexed (fn [i p] (when (= (vec p) (vec pk)) i)) ring))]
    (when (nil? index) (throw (ex-info "sign-ring: the signer is not in the roster" {})))
    (let [sig (clsag/sign {:msg (claim-digest-of spec spec-id claim) :ring ring :seed seed :index index
                           :scope (b/utf8 (str (name scope) "|" atom-id "|" scope-id)) :random random})]
      {:claim (assoc claim :auth {:kind :ring :sig sig}) :principal-commit (vec (:I sig))})))

(defn sign-session "the claim signed by a delegated session's key (eve.aegis.session), the session's key and issuance"
  [spec spec-id claim {:keys [secret issuance]}]
  (let [{:keys [seed pk]} (session/session-keypair secret)]
    {:claim (assoc claim :auth {:kind :session :pk pk :issuance issuance
                                :sig (crypto/ed25519-sign seed (claim-digest-of spec spec-id claim))})
     :principal-commit (session-commit pk issuance)}))

;; ── applying a claim to the state it was evaluated against

(defn next-state
  "The atom's state after the claim's write, given the :ok verdict evaluate answered for it (the ns docstring's
   APPLYING), at the spec's keyword paths: the claim's record appended to the vector at the branch's path as the claim
   carries it (its attestation kept), or the key assoc'd / dissoc'd in the map there, or the lockbox put; and the
   evaluator's band — [:aegis :spent <nullifier hex>] true, and for a ballot [:aegis :ballot-spends <ballot-info hex>]
   the scope's spend count, incremented."
  [state claim spec verdict]
  (when-not (true? (:ok verdict))
    (throw (ex-info "next-state: the claim's verdict is not :ok" {:reason (:reason verdict)})))
  (let [branch (branch-named spec (:branch claim))
        p (kpath (mutating-path branch))
        state (or state {})
        record (:record claim)
        info (:ballot-info verdict)
        written (cond (primitive branch :p/merkle-tail-append) (update-in state p (fn [v] (conj (if (vector? v) v []) record)))
                      (primitive branch :p/key-assoc) (assoc-in state (conj p (seg (:key claim))) (:value claim))
                      (primitive branch :p/key-dissoc) (update-in state p (fn [m] (dissoc (or m {}) (seg (:key claim)))))
                      (primitive branch :p/lockbox-write) (assoc-in state p (:lockbox claim))
                      :else state)]
    (cond-> (assoc-in written [:aegis :spent (b/hex (:nullifier verdict))] true)
      info (update-in [:aegis :ballot-spends info] (fn [n] (inc (or n 0)))))))

;; ── the wire: JSON-ready data and back

(def ^:private BYTE-KEYS #{"old-root" "new-root" "token-pk" "token-sig" "c1" "s" "I" "pk"})

(defn- byte-seq? [v] (and (sequential? v) (seq v) (every? (fn [x] (and (integer? x) (<= 0 x 255))) v)))

(defn- to-wire [k v]
  (cond (nil? v) nil
        (keyword? v) (name v)
        (map? v) (into {} (map (fn [[k2 v2]] (let [ks (seg k2)] [ks (if (#{"record" "lockbox"} ks) v2 (to-wire ks v2))]))) v)
        (and (or (contains? BYTE-KEYS k) (= k "sig")) (byte-seq? v)) (b/hex v)
        (and (contains? BYTE-KEYS k) (sequential? v)) (mapv (fn [x] (if (byte-seq? x) (b/hex x) x)) v)
        :else v))

(defn claim->wire "the claim as JSON-ready data (the ns docstring's THE WIRE)" [claim]
  (let [w (to-wire nil (dissoc claim :record))]
    (if (contains? claim :record)
      (assoc w "record" (into {} (map (fn [[k v]] [(seg k) v])) (:record claim)))
      w)))

(defn- hex-bytes [s] (if (string? s) (b/unhex s) s))

(defn- from-wire [k v]
  (cond (nil? v) nil
        (map? v) (into {} (map (fn [[k2 v2]] (let [ks (seg k2)] [(keyword ks) (from-wire ks v2)]))) v)
        (= k "kind") (keyword v)
        (= k "sig") (hex-bytes v)
        (and (contains? BYTE-KEYS k) (sequential? v)) (mapv hex-bytes v)
        (contains? BYTE-KEYS k) (hex-bytes v)
        :else v))

(defn wire->claim "the claim of its JSON-ready data (keys and values as JSON parses them)" [w]
  (let [rec (get w "record")
        lb (get w "lockbox")
        c (from-wire nil (dissoc w "record" "lockbox"))]
    (cond-> c (map? rec) (assoc :record rec) (map? lb) (assoc :lockbox lb))))
