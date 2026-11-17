(ns eve.aegis.regions-claims-test
  "Read regions through aegis's clear evaluator (eve.aegis.claims): a small app spec with a private room whose
   members map IS its readers' roster (:mirror), a lockbox branch only room admins write, and a message branch held to
   the region's epoch. A simulated broker keeps the state and builds the evaluator's context from it as
   aegis.broker.claim-gate does; every write is a claim built, signed, evaluated and applied. The scenario: create
   the lockbox, post, add a reader (stale, then extend), remove one (an extend cannot drop him; a rotate does), the
   removed reader locked out, a reader added later walking back to an old blob — and the broker's state never holds
   a region key. SYNTHETIC FIXTURES: principals are seeds derived from names; randomness is a SHA-256 counter stream."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eve.aegis.claims :as claims]
            [eve.aegis.merkle :as merkle]
            [eve.aegis.regions :as r]
            [eve.aegis.regions-spec :as rs]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

(def ATOM "aegis.chat.public")
(def NOW 1700000000)

(def spec rs/spec)

(def spec-id (claims/spec-id (b/utf8 (pr-str spec))))

(defn- seed [who] (crypto/sha256 (b/utf8 (str "seed:" who))))
(defn- pk [who] (vec (crypto/ed25519-public-key (seed who))))
(def PEOPLE ["admin" "alice" "bob" "carol" "dave" "mallory"])
(def principals (into {} (map (fn [p] [p (pk p)]) PEOPLE)))

(defn- random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n)
                               (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))

;; ── the simulated broker (aegis.broker.claim-gate's context, over a state value)

(def empty-broker {:chat (claims/genesis spec)})

(defn- digest-of "the stub of the kernel's block digest (this test has no kernel): equal values, equal digests" [v]
  (crypto/sha256 (b/utf8 (str "digest-of:" (hash v)))))

(defn- members [st] (get-in st [:chat :rooms :private :members] {}))
(defn- lockbox [st] (get-in st [:chat :keys :private]))

(defn- ctx-of [st]
  {:spec spec :spec-id spec-id :atom-id ATOM :epoch 1 :now NOW
   :lockboxes {"keys/private" (lockbox st)}
   :principals principals :quarantine #{}
   :groups {"chat/room-admins" #{(pk "admin")}
            "chat/private-members" (set (map b/unhex (vals (members st))))}
   :digest-of digest-of
   :spent? (fn [n] (true? (get-in st [:chat :aegis :spent (b/hex n)])))})

(defn- submit
  "the broker's cas-root: the claim and the state the client proposes -> [verdict state']. An append's and a map
   write's roots are the two states' digests and their account (:append-of, :map-of) is read from them; the proposed
   state must be the claim's write."
  [st [claim proposed]]
  (let [chat (:chat st)
        ;; the claim crosses the wire as JSON-ready data
        claim (claims/wire->claim (claims/claim->wire claim))
        branch (claims/branch-named spec (:branch claim))
        state-roots? (not (claims/primitive branch :p/lockbox-write))
        ctx (assoc (ctx-of st) :append-of (claims/append-of spec chat proposed) :map-of (claims/map-of chat proposed))
        v (claims/evaluate ctx claim)]
    (cond (not (true? (:ok v))) [v st]
          (and state-roots? (not= [(vec (:old-root claim)) (vec (:new-root claim))] [(digest-of chat) (digest-of proposed)]))
          [{:ok false :reason :cas} st]
          (not= proposed (claims/next-state chat claim spec v)) [{:ok false :reason :not-the-claimed-write} st]
          :else [v (assoc st :chat proposed)])))

(defn- sign [claim who] (:claim (claims/sign-claim spec spec-id (assoc claim :principal who) (seed who))))

(defn- build
  "the client's write: [the signed claim, the state it proposes]. The nullifier is computed before signing (from the
   unsigned claim and the principal's commitment), so the proposed state — the spent band included — and an append's
   or a map write's new root are known when the claim is signed."
  [st branch who m]
  (let [chat (:chat st)
        br (claims/branch-named spec branch)
        pc (claims/principal-commit (claims/acl-for spec (claims/mutating-path br)) who)
        m (assoc m :principal who)
        written (fn [claim] (claims/next-state chat claim spec {:ok true :nullifier (claims/named-nullifier spec claim pc)}))
        claim (if (claims/primitive br :p/lockbox-write)
                (claims/build-claim spec branch m (ctx-of st))
                (let [old-root (digest-of chat)
                      pre (assoc (select-keys m [:record :key :value]) :branch branch :old-root old-root)]
                  (claims/build-claim spec branch m {:old-root old-root :new-root (digest-of (written pre))})))]
    [(sign claim who) (written claim)]))

(defn- add-member [st who id] (build st "add-member" "admin" {:key id :value (b/hex (pk who))}))
(defn- remove-member [st id] (build st "remove-member" "admin" {:key id}))
(defn- lockbox-claim [st who lb] (build st "write-private-lockbox" who {:lockbox lb}))

(defn- message [st who epoch key text rnd & [blob]]
  (let [id (str "m-" (count (get-in st [:chat :rooms :private :messages])))
        {:strs [iv ct]} (r/seal-content ATOM "private" epoch key (b/utf8 (str id "|" who)) (b/utf8 text) (rnd 12))
        record (merge {"id" id "author" who "ts" NOW "key_epoch" epoch "iv" iv "ct" ct} blob)]
    (build st "private-message" who {:record record})))

(defn- read-message "a reader's view of a stored message: the text, or nil" [st who idx]
  (let [rec (nth (get-in st [:chat :rooms :private :messages]) idx)
        lb (lockbox st)
        cur (r/open-key lb ATOM "private" (seed who))
        k (when cur (r/key-at lb ATOM "private" cur (get rec "key_epoch")))]
    (when k (some-> (r/open-content ATOM "private" (get rec "key_epoch") k (b/utf8 (str (get rec "id") "|" (get rec "author")))
                                    {"iv" (get rec "iv") "ct" (get rec "ct")})
                    b/utf8->str))))

(defn- ok [[v st]] (is (true? (:ok v)) (str "expected ok, got " (pr-str (select-keys v [:reason :detail])))) st)
(defn- refused [reason [v _]] (is (= reason (:reason v)) (str "expected " reason ", got " (pr-str (select-keys v [:ok :reason])))))

(deftest a-private-region-end-to-end
  (let [rnd (random "regions-claims")
        k1 (rnd 32) k2 (rnd 32)
        st (-> empty-broker
               (#(ok (submit % (add-member % "alice" "m-alice"))))
               (#(ok (submit % (add-member % "bob" "m-bob")))))]
    (testing "the first lockbox: room admins only, recipients = the roster"
      (let [lb1 (r/new-lockbox ATOM "private" [(pk "alice") (pk "bob")] k1 rnd)]
        (refused :not-a-member (submit st (lockbox-claim st "alice" lb1)))
        (refused :lockbox-recipients (submit st (lockbox-claim st "admin" (r/new-lockbox ATOM "private" [(pk "alice")] k1 rnd))))
        (refused :region-no-lockbox (submit st (message st "alice" 1 k1 "too early" rnd)))
        (let [st (ok (submit st (lockbox-claim st "admin" lb1)))
              dek (rnd 32) blob (b/utf8 "the minutes, as a file")
              blob-ct (r/seal-blob dek (rnd 12) blob)
              cid (b/hex (crypto/sha256 blob-ct))
              {:strs [iv ct]} (r/wrap-dek ATOM "private" 1 k1 cid dek (rnd 12))
              st (ok (submit st (message st "alice" 1 k1 "hello, private room" rnd {"blob_cid" cid "dek_iv" iv "dek_ct" ct})))]
          (testing "a post under epoch 1; bob reads it, mallory cannot"
            (is (= "hello, private room" (read-message st "bob" 0)))
            (is (nil? (read-message st "mallory" 0)))
            (refused :region-epoch (submit st (message st "alice" 2 k1 "a wrong epoch" rnd)))
            (refused :not-a-member (submit st (message st "mallory" 1 k1 "not a member" rnd))))
          (testing "carol added: the region is stale until an extend"
            (let [st (ok (submit st (add-member st "carol" "m-carol")))
                  _ (refused :region-stale (submit st (message st "alice" 1 k1 "stale" rnd)))
                  st (ok (submit st (lockbox-claim st "admin" (r/extend-lockbox (lockbox st) ATOM "private" [(pk "alice") (pk "bob") (pk "carol")] k1 rnd))))
                  _ (is (= "hello, private room" (read-message st "carol" 0)) "carol reads what was posted before her")
                  st (ok (submit st (message st "alice" 1 k1 "welcome, carol" rnd)))]
              (testing "bob removed: an extend cannot drop him, a rotate does"
                (let [st (ok (submit st (remove-member st "m-bob")))
                      _ (refused :region-stale (submit st (message st "alice" 1 k1 "stale again" rnd)))
                      _ (refused :lockbox-recipients
                                 (submit st (lockbox-claim st "admin" (r/extend-lockbox (lockbox st) ATOM "private" [(pk "alice") (pk "carol")] k1 rnd))))
                      lb2 (r/rotate-lockbox (lockbox st) ATOM "private" [(pk "alice") (pk "carol")] k1 k2 rnd :grant)
                      _ (refused :lockbox-history (submit st (lockbox-claim st "admin" (assoc lb2 "history" {}))))
                      st (ok (submit st (lockbox-claim st "admin" lb2)))
                      _ (refused :region-epoch (submit st (message st "alice" 1 k1 "an old key" rnd)))
                      st (ok (submit st (message st "alice" 2 k2 "bob is gone" rnd)))]
                  (is (= "bob is gone" (read-message st "carol" 2)))
                  (is (= "hello, private room" (read-message st "carol" 0)) "carol walks back to epoch 1")
                  (is (nil? (read-message st "bob" 2)) "bob holds no box at epoch 2")
                  (testing "dave, added at epoch 2, opens the epoch-1 blob; the blob never changed"
                    (let [st (ok (submit st (add-member st "dave" "m-dave")))
                          st (ok (submit st (lockbox-claim st "admin" (r/extend-lockbox (lockbox st) ATOM "private" [(pk "alice") (pk "carol") (pk "dave")] k2 rnd))))
                          rec (first (get-in st [:chat :rooms :private :messages]))
                          lb (lockbox st)
                          k1' (r/key-at lb ATOM "private" (r/open-key lb ATOM "private" (seed "dave")) 1)
                          dek' (r/unwrap-dek ATOM "private" 1 k1' (get rec "blob_cid") {"iv" (get rec "dek_iv") "ct" (get rec "dek_ct")})]
                      (is (= blob (r/open-blob dek' blob-ct)))
                      (testing "the broker's state holds no region key and no plaintext"
                        (let [dump (pr-str (:chat st))]
                          (is (not (str/includes? dump (b/hex k1))))
                          (is (not (str/includes? dump (b/hex k2))))
                          (is (not (str/includes? dump (b/hex dek))))
                          (is (not (str/includes? dump "hello, private room"))))))))))))))))

(deftest a-lockbox-claim-is-bound-to-what-it-posts
  (let [rnd (random "bound") k1 (rnd 32)
        st (-> empty-broker (#(ok (submit % (add-member % "alice" "m-alice")))))
        lb (r/new-lockbox ATOM "private" [(pk "alice")] k1 rnd)
        [claim proposed :as write] (lockbox-claim st "admin" lb)]
    (testing "a lockbox swapped after signing: the posted lockbox's root is not the signed one"
      (refused :new-root-mismatch (submit st [(assoc claim :lockbox (r/new-lockbox ATOM "private" [(pk "alice")] (rnd 32) rnd)) proposed])))
    (testing "the wire keeps the lockbox as it is"
      (is (= lb (:lockbox (claims/wire->claim (claims/claim->wire claim))))))
    (let [st (ok (submit st write))]
      (testing "a claim built over the previous lockbox no longer applies"
        (refused :old-root-mismatch (submit st write))))))
