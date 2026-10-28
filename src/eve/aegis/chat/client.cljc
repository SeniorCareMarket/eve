(ns eve.aegis.chat.client
  "A chat client session in portable cljc, speaking to the aegis broker exactly as the web page's client does
   (src/aegis/chat/client_main.vs, broker_client.vs's ONE WRITE): on blocks (ExtCID v3, IMPL-PLAN.md §5 B5). It reads
   /deref — an eve-wire map whose \"pages\" are the chat atom's kernel pages and whose \"root\" is its root's 64-byte
   slot — into the state value (eve.net.pages/read-pages, every block verified); builds the action's record
   (eve.aegis.chat.records) — a message encrypted under the room (eve.aegis.chat.g2) — and ATTESTS it (EPOCH old + 1,
   SPEC_ID, EVALUATOR, GROUP_ROOT, PRINCIPAL_COMMIT: eve.net.codec/attest) and takes the new state from
   eve.aegis.claims/next-state (the attested record placed, the evaluator's [:aegis …] band); signs the spec branch's
   CLAIM over the two state digests (named
   branches with the member's Ed25519 seed, a vote spent by a blind-signed ballot of the group's issuer or a ring
   signature, a guest's scratch post by a delegated session key); writes the new state as pages (only its new blocks;
   the root stamped: PREV_ROOT the old key, EPOCH old + 1) and POSTs /cas-root as eve-wire bytes. Bodies are
   application/octet-stream both ways (B2's wire, STATUS 2026-10-24 10:00 / 10:07 AM ET). Transport is eve's http
   feature; randomness the crypto contract's; the clock eve.process's.

     (session cfg)          cfg {:endpoint :domain :atom-id :user :seed (64 hex) :room-secret (32 bytes)
                                 :spec-text :session {:secret (64 hex) :issuance (unix s)}}
     (join cfg-or-link)     a session from the page's own invite link: http(s)://<page>/#room=…&user=…&key=…
                            (+ &session=…&issued=…) — the page server's /chat/config.json and /chat/spec.edn
     (deref! s [atom-id])   the broker's /deref answer, decoded
     (state! s [d])         {:old :record :known :epoch :old-key :genesis?}: the atom's state value read out of its pages
     (post! s post)         one action: {:status \"ok\" :branch … :auth …} or {:status \"denied\"/\"error\" …}
     (say! s room text)     post a G.2 message to :public, :private or :announcements
     (view s [d])           the room as data: messages (decrypted), polls with tallies, members, scratch, audit log
   A write that loses the CAS race (409: another member wrote first) is rebuilt on the new state and sent again after a
   random pause that doubles each try, up to sixteen tries. A session holding :phases (an atom) has each write's phases
   added there in ms — :encrypt (say!), :deref, :read (the state out of its pages), :plan (the auth: a ring signature, a
   ballot), :next-state (the record attested and placed), :pages (the new blocks: only the changed paths imaged), :sign
   (the claim), :encode (the cas-root body), :cas-root (the request) — summed over a lost race's retries."
  (:refer-clojure :exclude [long bytes aget alength volatile! vswap!])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [eve.aegis.ballot :as ballot]
            [eve.aegis.chat.g2 :as g2]
            [eve.aegis.chat.records :as rec]
            [eve.aegis.claims :as claims]
            [eve.aegis.clsag :as clsag]
            [eve.aegis.merkle :as merkle]
            [eve.aegis.session :as session]
            [eve.codec :as wire]
            [eve.crypto :as crypto]
            [eve.encoding :as enc]
            [eve.host :refer [long bytes aget alength volatile! vswap!]]   ;; bytes: the hint eve.codec's ^bytes names (clojerl resolves it at the call)
            [eve.http :as http]
            [eve.net.bytes :as b]
            [eve.net.codec :as codec]
            [eve.net.json :as json]
            [eve.net.pages :as pages]
            [eve.process :as process]))

;; ── the wire: eve-wire bytes (eve.codec, the flat EVE format) on application/octet-stream ──────────────────────────

(defn- ints-of "a host byte array as eve.net.bytes ints" [arr] (mapv (fn [x] (if (neg? x) (+ x 256) x)) (seq arr)))
(defn- host-bytes "eve.net.bytes ints as the host's byte array (eve.encoding's, which its eve.codec reads)" [bs] (enc/bytes-of bs))

(defn body-bytes
  "a body value as eve-wire bytes (ints): eve.codec's flat, canonical form (encode-key: a map's entries ordered by their
   encoded keys — B2's wire)"
  [v]
  (ints-of (wire/encode-key v)))

(defn body-value
  "the value of eve-wire bytes (ints), or nil when they are not eve-wire (a host's text/plain failure line)"
  [bs]
  (when (and (>= (count bs) 3) (= 0xEE (nth bs 0)) (= 0xDB (nth bs 1))) (wire/decode nil (host-bytes bs))))

(defn- request
  "one request with an eve-wire body each way, as the host's byte arrays (eve.http's :bytes: eve.codec writes the body
   and reads the answer, no text between them — a body through base64 and a vector of ints cost a glojure or basilisp
   member most of a write); the answer {:status :body value | nil :text (a failure line that is not eve-wire)}"
  [method url body & [principal]]
  (let [r (http/request (cond-> {:method method :url url :as :bytes
                                 :headers (cond-> {"content-type" "application/octet-stream"}
                                            principal (assoc "x-aegis-principal" principal))}
                          (some? body) (assoc :body (wire/encode-key body) :body-as :bytes)))]
    (if (:error r)
      {:status 0 :error (:error r)}
      (let [hb (:body r)
            n (if (some? hb) (alength hb) 0)
            wire? (and (>= n 3) (= 0xEE (bit-and (aget hb 0) 0xFF)) (= 0xDB (bit-and (aget hb 1) 0xFF)))]
        {:status (:status r) :body (when wire? (wire/decode nil hb)) :text (when (and (not wire?) (pos? n)) (enc/utf8-string hb 0 n))}))))

(defn atom-url [s atom-id action]
  (str (:endpoint s) "/v1/domains/" (:domain s) "/atoms/" atom-id "/" action))

(defn- held-known
  "what the session holds of its atom, as /deref's request names it (remote_atom.vs deref-request's known): the root key,
   the spec id, the pages"
  [held]
  (cond-> {"have" (vec (keys (get held "pages")))}
    (string? (get held "root_key")) (assoc "root_key" (get held "root_key"))
    (string? (get held "spec_id")) (assoc "spec_id" (get held "spec_id"))))

(defn deref!
  "the broker's /deref answer for the atom (the session's own by default), decoded. For the session's own atom the
   request names what the session holds (held-known): the broker answers `unchanged` while the root is the one held, and
   leaves out the pages held and the spec it has — so a write in a busy room fetches the pages it lacks, not the room.
   The answer is the whole publication either way: the held one, or the new one over the held pages and spec"
  ([s] (deref! s (:atom-id s)))
  ([s atom-id]
   (let [c (when (= atom-id (:atom-id s)) (:cache s))
         held (when c (get @c ::deref))
         r (request "POST" (atom-url s atom-id "deref") (when held (held-known held)))]
     (when-not (= 200 (:status r))
       (throw (ex-info (str "chat: deref " atom-id " answered " (:status r) " " (or (:error r) (:text r) (get-in r [:body "reason"])))
                       {:response r})))
     (let [b (:body r)]
       (if (and held (get b "unchanged") (= (get b "root_key") (get held "root_key")))
         held
         (let [d (if held
                   (cond-> (assoc b "pages" (merge (get held "pages") (get b "pages")))
                     (nil? (get b "spec")) (assoc "spec" (get held "spec")))
                   b)]
           (when c (vswap! c assoc ::deref d))
           d))))))

;; ── the session

(defn- hex64? [s] (and (string? s) (= 64 (count s)) (every? (fn [c] (some? (b/hex-value c))) s)))

(defn session
  "a session map from cfg (the ns docstring), its spec read and its spec id computed. Its :cache (what it holds of the
   atom, the state it read, the texts it opened) is a volatile, the session's own thread's: on the BEAM an atom's every
   deref copies its whole value out of ETS — a clojerl view of nine messages took 9.4 s"
  [{:keys [spec-text seed room-secret] :as cfg}]
  (when-not (string? spec-text) (throw (ex-info "chat: the app spec's EDN text is required (:spec-text)" {})))
  (when (and seed (not (hex64? seed))) (throw (ex-info "chat: :seed is the member's Ed25519 seed, 64 hex" {})))
  (let [spec (edn/read-string spec-text)]
    (cond-> (assoc cfg :spec spec :spec-id (claims/spec-id (b/utf8 spec-text)) :cache (volatile! {}))
      room-secret (assoc :room (g2/open-room room-secret (:atom-id cfg)))
      seed (assoc :pk (b/hex (crypto/ed25519-public-key (b/unhex seed)))))))

(defn- page-origin [link] (let [u (first (str/split (str link) #"#"))] (str/replace u #"/+$" "")))

(defn join
  "a session from the web page's invite link (or a cfg map): the page server publishes /chat/config.json
   {broker domain atom …} and /chat/spec.edn; the fragment carries room, user, key and a guest's session"
  [link-or-cfg]
  (if (map? link-or-cfg)
    (session link-or-cfg)
    (let [origin (page-origin link-or-cfg)
          p (g2/fragment-params link-or-cfg)
          cfg-r (http/request {:method "GET" :url (str origin "/chat/config.json")})
          spec-r (http/request {:method "GET" :url (str origin "/chat/spec.edn")})
          c (when (= 200 (:status cfg-r)) (json/read-str-or-nil (:body cfg-r)))]
      (when-not (and (map? c) (= 200 (:status spec-r)))
        (throw (ex-info (str "chat: " origin " did not serve /chat/config.json and /chat/spec.edn") {:config cfg-r :spec (:status spec-r)})))
      (session (cond-> {:endpoint (str/replace (str (get c "broker")) #"/+$" "") :domain (get c "domain") :atom-id (get c "atom")
                        :page origin :spec-text (:body spec-r) :user (get p "user") :seed (get p "key")}
                 (get p "room") (assoc :room-secret (g2/parse-room-secret link-or-cfg))
                 (get p "session") (assoc :session {:secret (get p "session") :issuance (edn/read-string (str (get p "issued" "0")))}))))))

;; ── the state: read out of the atom's pages ─────────────────────────────────────────────────────────────────────────

(defn read-state
  "{:old :record :known :epoch :old-key :genesis?} of a /deref answer d: the value its root slot names, every block
   verified (eve.net.pages), and its codec record (its tree's keys: a write keeps them wherever the value is unchanged,
   whichever writer imaged it); an atom never written is genesis (eve.aegis.claims/genesis of the spec, EPOCH 0, no old
   key). prev, an earlier read of the atom (a state's :read): what it verified is not read again (read-pages' :prev)"
  ([spec d] (read-state spec d nil))
  ([spec d prev]
   (let [root (get d "root")]
     (if (and (string? root) (seq root))
       (let [r (pages/read-pages (b/from-b64 root) (get d "pages") {:prev prev})]
         (when (and (string? (get d "root_key")) (not= (get d "root_key") (:key r)))
           (throw (ex-info "chat: the deref's root_key is not its root slot's" {:root_key (get d "root_key") :slot (:key r)})))
         {:old (:value r) :record (:record r) :known (:known r) :epoch (or (:epoch (:stamp r)) 0) :old-key (b/unhex (:key r))
          :genesis? false :read r})
       {:old (claims/genesis spec) :record nil :known {} :epoch 0 :old-key nil :genesis? true}))))

(defn state!
  "read-state over the session's atom (or d), cached per root key — a root key names one state — and read from the last
   state read (only what the room added since is read and verified)"
  ([s] (state! s (deref! s)))
  ([s d]
   (let [k (get d "root_key") c (:cache s) hit (when (and c k) (get @c ::state))]
     (if (and hit (= k (first hit)))
       (second hit)
       (let [st (read-state (:spec s) d (:read (second hit)))]
         (when (and c k) (vswap! c assoc ::state [k st]))
         st)))))

(defn- kw-keys "a wire map's string keys as keywords, one level" [m] (into {} (map (fn [[k v]] [(keyword (str k)) v]) m)))

(defn rosters-of
  "the aegis-rosters atom's value from /deref's \"rosters\" publication (B2: string keys, sets as vectors): {:principals
   {name pk-hex} :groups {g #{pk-hex}} :issuers {g {:n :e :stmt-pk}} :quarantine #{name} :statements {info {:info :count
   :roster-size :sig}}}"
  [w]
  (let [w (or w {})]
    {:principals (or (get w "principals") {})
     :groups (into {} (map (fn [[g ks]] [g (set ks)]) (get w "groups")))
     :issuers (into {} (map (fn [[g m]] [g {:n (get m "n") :e (get m "e") :stmt-pk (get m "stmt_pk")}]) (get w "issuers")))
     :quarantine (set (get w "quarantine"))
     :statements (into {} (map (fn [[i m]] [i {:info (get m "info") :count (get m "count") :roster-size (get m "roster_size")
                                               :sig (get m "sig")}])
                               (get w "statements")))}))

;; ── one write (broker_client.vs's) ──────────────────────────────────────────────────────────────────────────────────

(defn branch-for "the spec branch whose root-owning primitive writes the action's path" [spec action]
  (let [[path prim] (get rec/ACTIONS action)
        segs (fn [p] (mapv (fn [x] (if (keyword? x) (name x) (str x))) p))]
    (first (filter (fn [br] (and (claims/primitive br prim) (= (segs path) (segs (claims/mutating-path br)))))
                   (claims/branches spec)))))

(defn- random [n] (vec (crypto/random-bytes n)))

(defn- lap!
  "the phase clock: when the session carries :phases (an atom — eve.aegis.chat.seat's), the ms since t0 added under k;
   the time now either way"
  [s k t0]
  (let [now (process/now-ms)]
    (when-let [a (:phases s)] (swap! a update k (fn [x] (+ (or x 0) (- now t0)))))
    now))

(defn- seed-bytes [s]
  (when-not (hex64? (:seed s)) (throw (ex-info "chat: this branch is signed with the member's key (:seed)" {})))
  (b/unhex (:seed s)))

(defn- digest
  "the 32-byte digest in a value's own block header (FORMAT.md §2.1 at 32, §2.6: SHA-256 of its image), eve.net.bytes ints
   — what the gate's :digest-of reads (a verdict's :group-root: the rosters atom's group set). A value's band is that
   SHA, except a SET root's, which its parent's slot carries XOR 0xEE… (eve.net.codec put-block, WIRE.md §7): a set's
   band is turned back"
  [v]
  (let [bd (vec (first (codec/band-of v)))]
    (if (set? v) (mapv (fn [x] (bit-xor x 0xEE)) bd) bd)))
(defn- bytes-of [x] (if (string? x) (b/unhex x) (vec x)))

(defn auth-kind [spec branch]
  (let [path (claims/mutating-path branch)]
    (cond (claims/binds-principal? branch) :named
          (= :per-session (claims/scope-for spec path)) :session
          :else (claims/group-anonymity spec (claims/group-name (claims/acl-for spec path))))))

(defn- mirror [spec group]
  (some (fn [e] (when (= group (str (:domain e) "/" (:name e))) (:mirror e))) (get-in spec [:groups :entries])))

(defn- scope-bytes [scope atom-id sid] (b/utf8 (str (name scope) "|" atom-id "|" sid)))

(defn- ballot-for
  "a finished ballot for the vote's scope: the blinded token sent to the group's issuer through /ballot, its blind
   signature finished (eve.aegis.ballot) — or {:refused …}"
  [s spec rosters branch sid]
  (let [path (claims/mutating-path branch)
        g (claims/group-name (claims/acl-for spec path))
        iw (get-in rosters [:issuers g])
        _ (when-not (map? iw) (throw (ex-info (str "chat: the group " g " has no ballot issuer on this broker") {})))
        pk {:n (bytes-of (:n iw)) :e (bytes-of (:e iw))}
        info (ballot/scope-info (claims/scope-for spec path) (:atom-id s) sid)
        nb (ballot/new-ballot pk info random)
        body {"branch" (:branch branch) "scope_id" sid "member" (:user s) "blinded" (b/hex (:blinded nb))
              "sig" (b/hex (crypto/ed25519-sign (seed-bytes s) (ballot/request-message (:atom-id s) info (:blinded nb))))}
        r (request "POST" (atom-url s (:atom-id s) "ballot") body (:user s))]
    (if-not (= 200 (:status r))
      {:refused {:status "denied" :by "issuer" :http (:status r) :reason (or (get-in r [:body "reasonCode"]) (:text r))}}
      (ballot/finish-ballot pk info nb (b/unhex (get-in r [:body "blind_sig"]))))))

(defn- plan
  "{:kind :principal-commit :nullifier :roster [:ballot-info] [:ballot]} — every commitment computable before the claim
   is signed (eve.aegis.claims NULLIFIERS); :roster the value the gate's evaluation reads membership in, whose digest is
   the verdict's :group-root (eve.aegis.claims THE VERDICT): the group's roster, the :mirror map, the principal
   registry (a named write to no group), the group's issuer record, the session registry"
  [s {:keys [spec branch pre principal rosters sessions record old]}]
  (let [path (claims/mutating-path branch)
        acl (claims/acl-for spec path)
        scope (claims/scope-for spec path)
        group (when (= "group" (:kind acl)) (claims/group-name acl))
        kind (auth-kind spec branch)
        sk (:scope-key (claims/record-schema spec path))
        sid (when (and record sk) (merkle/record-get record sk))]
    (case kind
      :named
      (let [pc (claims/principal-commit acl principal)]
        (when (nil? pc) (throw (ex-info "chat: the ACL's :commitFn is unsupported" {:acl acl})))
        {:kind kind :principal-commit pc :nullifier (claims/named-nullifier spec pre pc)
         :roster (cond (nil? group) (:principals rosters)
                       (some? (mirror spec group)) (get-in old (rec/kpath (mirror spec group)))
                       :else (get-in rosters [:groups group]))})
      :session
      (let [{:keys [secret issuance]} (:session s)
            _ (when-not (and (hex64? secret) (number? issuance))
                (throw (ex-info "chat: a scratch post needs a delegated session (:session)" {})))
            pk (:pk (session/session-keypair (b/unhex secret)))]
        {:kind kind :principal-commit (claims/session-commit pk (long issuance)) :nullifier (vec pk) :roster sessions})
      :ring
      (let [ki (clsag/key-image (seed-bytes s) (scope-bytes scope (:atom-id s) sid))]
        {:kind kind :principal-commit (vec ki) :nullifier (vec ki) :roster (get-in rosters [:groups group])})
      :ballot
      (let [bal (ballot-for s spec rosters branch sid)]
        (if (:refused bal)
          bal
          {:kind kind :ballot bal :principal-commit (vec (crypto/sha256 (vec (:token-pk bal))))
           :nullifier (vec (ballot/nullifier {:token-sig (:token-sig bal)}))
           :ballot-info (b/hex (ballot/scope-info scope (:atom-id s) sid)) :roster (get-in rosters [:issuers group])}))
      (throw (ex-info (str "chat: the branch's auth kind " (pr-str kind) " is not one the client signs") {:kind kind})))))

(defn- sign [s {:keys [spec spec-id kind claim branch rosters sessions record ballot]}]
  (let [path (claims/mutating-path branch)
        acl (claims/acl-for spec path)]
    (case kind
      :named (claims/sign-claim spec spec-id claim (seed-bytes s))
      :session (claims/sign-session spec spec-id claim {:secret (b/unhex (get-in s [:session :secret]))
                                                        :issuance (long (get-in s [:session :issuance]))})
      :ring (claims/sign-ring spec spec-id claim
                              {:seed (seed-bytes s) :roster (mapv bytes-of (get-in rosters [:groups (claims/group-name acl)]))
                               :atom-id (:atom-id s) :random random
                               :scope-id (merkle/record-get record (:scope-key (claims/record-schema spec path)))})
      :ballot (claims/sign-ballot spec spec-id claim ballot))))

(defn branch-write
  "ONE WRITE of a spec branch on the old state st (broker_client.vs's write, in eve; any app spec): intent {:record r}
   (an append) · {:key k :value v} (a key-assoc) · {:key k} (a key-dissoc) · {:lockbox lb} (a lockbox write) →
   {:status \"ok\" :branch :kind :record :new :old-key :new-key :epoch :claim :pages} — :pages eve.net.pages/write-pages'
   answer for the new state (only its new blocks, its root stamped: PREV_ROOT the old key, EPOCH old + 1) — or
   {:refused …} (a ballot the issuer refused)"
  [s {:keys [old known epoch old-key genesis?] rec0 :record rd :read} branch intent {:keys [rosters sessions]}]
  (let [spec (:spec s) spec-id (:spec-id s)
        path (claims/mutating-path branch)
        kpath (rec/kpath path)
        ps (claims/path-string path)
        prim (some (fn [n] (when (claims/primitive branch n) n))
                   [:p/merkle-tail-append :p/key-assoc :p/key-dissoc :p/lockbox-write])
        kind (auth-kind spec branch)
        p (when (= :named kind) (:user s))
        ;; the old state's key as its writer imaged it (the root slot's, every block verified): a writer may image a value
        ;; in a form this codec reads but does not write (the kernel's runtime FLOAT64 — the page's timestamps), so the
        ;; value is never imaged again to name it
        old-root (if genesis? (digest old) (vec old-key))
        epoch (inc epoch)
        ev (subvec (vec (claims/release-hash)) 0 16)
        record (:record intent)
        ;; the claim with every field but the new root (an append, a key-assoc, a key-dissoc) — what the nullifier reads
        what (case prim
               :p/merkle-tail-append {:record record}
               :p/key-assoc {:key (:key intent) :value (:value intent)}
               :p/key-dissoc {:key (:key intent)}
               :p/lockbox-write {:lockbox (:lockbox intent)})
        pre (if (= prim :p/lockbox-write)
              (claims/build-claim spec (:branch branch) (assoc what :principal p)
                                  {:lockboxes (if-let [lb (get-in old kpath)] {ps lb} {})})
              (cond-> (assoc what :branch (:branch branch) :old-root old-root) p (assoc :principal p)))
        t0 (process/now-ms)
        pl (plan s {:spec spec :branch branch :pre pre :principal p :rosters rosters :sessions sessions :record record :old old})
        t1 (lap! s :plan t0)]
    (if (:refused pl)
      (:refused pl)
      (let [_ (when (nil? (:roster pl))
                (throw (ex-info "chat: no roster for the write's auth in the rosters / sessions atoms" {:branch (:branch branch)})))
            group-root (digest (:roster pl))
            ;; the record attested before it is placed (R-B3.3's fields)
            record (when record (codec/attest record {:epoch epoch :spec-id (vec spec-id) :evaluator ev :group-root group-root
                                                      :principal-commit (vec (:principal-commit pl))}))
            verdict (cond-> {:ok true :nullifier (:nullifier pl)} (:ballot-info pl) (assoc :ballot-info (:ballot-info pl)))
            ;; the new state is the claim's write (next-state): the attested record placed as it is, the band
            new (claims/next-state old (cond-> pre record (assoc :record record)) spec verdict)
            t2 (lap! s :next-state t1)
            ;; the new state's pages: only the changed paths imaged (encode-rec over the old state's record), every other
            ;; part keeping the key its writer gave it; its root stamped PREV_ROOT the old key, EPOCH old + 1
            w (pages/write-pages new {:known known :record rec0 :memo (:memo rd) :salt (random 32)
                                      :stamp {:epoch epoch :spec-id (vec spec-id) :evaluator ev :prev (when-not genesis? old-key)}})
            new-key (b/unhex (:key w))
            t3 (lap! s :pages t2)
            claim (if (= prim :p/lockbox-write)
                    pre
                    (claims/build-claim spec (:branch branch) (cond-> (assoc what :principal p) record (assoc :record record))
                                        {:old-root old-root :new-root new-key}))
            signed (sign s {:spec spec :spec-id spec-id :kind kind :claim claim :branch branch :rosters rosters
                            :sessions sessions :record record :ballot (:ballot pl)})
            _ (lap! s :sign t3)]
        (when-not (= (vec (:principal-commit signed)) (vec (:principal-commit pl)))
          (throw (ex-info "chat: the signer's principal commitment is not the one the record is attested with (R-A6.5)"
                          {:attested (b/hex (:principal-commit pl)) :signer (b/hex (:principal-commit signed))})))
        {:status "ok" :branch branch :kind kind :record record :new new :old-key (when-not genesis? old-key)
         :new-key new-key :epoch epoch :claim (:claim signed) :pages w}))))

(defn write
  "ONE WRITE of a chat action on the old state: prepare-post's intent through branch-write — or prepare's {:status
   \"empty\" / \"denied\"}, or {:refused …}"
  [s st post {:keys [rand-bytes now-ms] :as ctx}]
  (let [prep (rec/prepare-post (:old st) post (:user s) rand-bytes now-ms)]
    (if (not= "ok" (:status prep))
      prep
      (let [action (:action prep)
            branch (or (branch-for (:spec s) action)
                       (throw (ex-info (str "chat: the spec has no branch writing " action "'s path") {})))
            w (branch-write s st branch (select-keys prep [:record :key :value]) ctx)]
        (cond-> w (= "ok" (:status w)) (assoc :action action))))))

(defn cas-body
  "the /cas-root body of a write (B2's wire): {\"old_key\" hex | nil \"new_key\" hex \"root\" b64 \"pages\" {cid b64}
   \"claim\" claims/claim->wire}"
  [w]
  {"old_key" (when (:old-key w) (b/hex (:old-key w))) "new_key" (b/hex (:new-key w))
   "root" (b/to-b64 (get-in w [:pages :root]))
   "pages" (into {} (map (fn [pg] [(:cid pg) (pages/page-b64 (:bytes pg))]) (get-in w [:pages :pages])))
   "claim" (claims/claim->wire (:claim w))})

(defn- attempt-post [s post]
  (let [t0 (process/now-ms)
        d (deref! s)
        t1 (lap! s :deref t0)
        published (get d "spec_id")]
    (if (and (string? published) (not= published (b/hex (:spec-id s))))
      {:status "denied" :by "client" :reason (str "spec-id-mismatch: the broker's spec id " published " is not this spec's " (b/hex (:spec-id s)))}
      (let [st (state! s d)
            t2 (lap! s :read t1)
            w (write s st post {:rosters (rosters-of (get d "rosters")) :sessions (or (get d "sessions") {})
                                :rand-bytes (random 64) :now-ms (process/now-ms)})
            t3 (process/now-ms)]
        (cond
          (:refused w) (:refused w)
          (not= "ok" (:status w)) w
          :else
          (let [body (cas-body w)
                t4 (lap! s :encode t3)
                r (request "POST" (atom-url s (:atom-id s) "cas-root") body (when (= :named (:kind w)) (:user s)))
                _ (lap! s :cas-root t4)]
            (cond (= 200 (:status r))
                  (do ;; accepted: the atom's root IS the posted root, its pages the held ones and the posted ones
                      ;; (remote_atom.vs adopt-write) — the next /deref names them, and answers `unchanged` while no one
                      ;; else has written
                      (when-let [c (:cache s)]
                        (vswap! c update ::deref
                               (fn [h] (when h (-> h (assoc "root_key" (get body "new_key") "root" (get body "root"))
                                                   (update "pages" merge (get body "pages")))))))
                      {:status "ok" :action (:action w) :branch (:branch (:branch w))
                       :auth (name (get-in w [:claim :auth :kind])) :record (:record w)})
                  (= 409 (:status r)) {:status "conflict"}
                  :else {:status "denied" :by "broker" :http (:status r) :action (:action w)
                         :reason (or (get-in r [:body "reasonCode"]) (get-in r [:body "reason"]) (:text r) (:error r))
                         :detail (get-in r [:body "detail"])})))))))

(def ^:private POST-TRIES
  "the tries a write gets when other members keep writing first. Seven writers at once (the swarm, eve/capstone/eve-swarm)
   lost one member's vote on three immediate retries; fifteen at once lost one on eight tries paused at most 3.2 s"
  16)

(defn- backoff-ms
  "the pause after a write's try n lost the race: random below 100 ms × 2^(n-1), at most 6.4 s — writers that lost
   together spread out, and the write that beat them lands meanwhile"
  [n]
  (let [r (reduce (fn [a x] (+ (* a 256) (bit-and x 255))) 0 (random 2))]
    (mod r (* 100 (bit-shift-left 1 (min (dec n) 6))))))

(defn post!
  "one action through the broker (the ns docstring); a write that loses the CAS race is built again on the new state
   after a random pause that doubles each try (backoff-ms), up to POST-TRIES tries"
  [s post]
  (loop [n 1]
    (let [r (attempt-post s post)]
      (if (and (= "conflict" (:status r)) (< n POST-TRIES))
        (do (process/sleep-ms! (backoff-ms n)) (recur (inc n)))
        r))))

(def ^:private ROOM-ACTIONS {:public "post-message" :private "post-private-message" :announcements "post-announcement"})

(defn say!
  "post the text to a room (:public, :private, :announcements), encrypted under the room key"
  [s room text]
  (when-not (:room s) (throw (ex-info "chat: the session has no room secret (#room=… in the invite link)" {})))
  (let [t0 (process/now-ms)
        r (g2/encrypt-message (:room s) (:user s) text {:iv (random 12) :id-entropy (random 16) :ts (process/now-ms)})]
    (lap! s :encrypt t0)
    (post! s {"action" (get ROOM-ACTIONS room) "record" r})))

;; ── reading

(defn- text-of
  "a record's text: decrypted once per session — the session's cache holds it under the record's id, ciphertext and
   commitment (a record is never rewritten in place, and a changed one is a new block)"
  [s r]
  (let [w (rec/wire-record r)]
    (cond (nil? (:room s)) g2/UNABLE-TO-DECRYPT
          (nil? (:cache s)) (g2/decrypt-message (:room s) w)
          :else (let [k [(:id r) (:ciphertext r) (:content_commitment r) (:author r) (:ivB64 r)]]
                  (or (get @(:cache s) k)
                      (let [t (g2/decrypt-message (:room s) w)] (vswap! (:cache s) assoc k t) t))))))

(defn- messages-at [s state path]
  (mapv (fn [r] {:id (:id r) :author (:author r) :ts (:ts r) :text (text-of s r)})
        (let [v (get-in state path)] (if (vector? v) v []))))

(defn view
  "the room as data: {:public :private :announcements [{:id :author :ts :text}] :polls [{:id :author :question :options
   :tally {choice n}}] :members {id pk} :scratch {:spaces [...] :posts [...]} :audit [...] :commits n} — :commits the
   root's EPOCH (the atom's transitions)"
  ([s] (view s (deref! s)))
  ([s d]
   (let [{st :old epoch :epoch} (state! s d)
         votes (get-in st rec/PUBLIC-VOTES)]
     {:public (messages-at s st rec/PUBLIC-MESSAGES)
      :private (messages-at s st rec/PRIVATE-MESSAGES)
      :announcements (messages-at s st rec/ANNOUNCEMENTS)
      :polls (mapv (fn [p] {:id (:id p) :author (:author p) :question (:question p) :options (:options p)
                            :tally (frequencies (keep (fn [v] (when (= (:id p) (:poll v)) (:choice v))) votes))})
                   (get-in st rec/PUBLIC-POLLS))
      :members (get-in st rec/PRIVATE-MEMBERS)
      :scratch {:spaces (get-in st rec/SCRATCH-SPACES) :posts (get-in st rec/SCRATCH-POSTS)}
      :audit (get-in st rec/AUDIT-LOG)
      :commits epoch})))
