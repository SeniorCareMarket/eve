(ns eve.aegis.regions
  "Read regions: per-region read keys an app spec declares, wrapped for each reader in a lockbox the atom itself
   stores, so read access is cryptographic and the broker stays blind to every key while it arbitrates who may change
   the lockbox (the read-regions spike). Portable cljc over eve's crypto
   contract and eve.aegis.hpke.

   THE SPEC  :read-regions {:op :defreadregions
                            :entries [{:name \"private\" :paths [[\"rooms\" \"private\" \"messages\"]]
                                       :readers {:groupDomain \"chat\" :groupName \"private-members\"}
                                       :lockbox [\"keys\" \"private\"] :history :grant}]}
     a region names the paths whose records are encrypted under its key, the group whose members read it, the path
     of its lockbox in the atom, and whether a new key carries the old one (:grant, the default) or not (:withhold).

   KEYS. A region has one 32-byte key per EPOCH. Members' encryption keys are their roster keys: the X25519 secret of an
   Ed25519 seed is the clamped SHA-512 half RFC 8032 already derives, and the X25519 public key of an Ed25519 public
   key is the birational map u = (1 + y) / (1 − y) (libsodium's crypto_sign_ed25519_pk_to_curve25519) — so the broker
   reads each reader's recipient slot straight off the roster it already holds.

   THE LOCKBOX (JSON-native: string keys, lowercase hex)
     {\"epoch\" n
      \"commit\" hex32     SHA-256(\"aegis.region.key.v1\" ‖ lp(atom) ‖ lp(region) ‖ u64(n) ‖ key): one value every
                         member checks the key they unwrapped against, so an admin cannot hand members different keys
      \"boxes\" {ed25519-pk-hex {\"enc\" hex32 \"ct\" hex48}}   the key, HPKE-sealed to that reader (info: the box label,
                         atom, region, epoch; aad: the reader's Ed25519 key)
      \"history\" {\"k\" hex48}  key k sealed under key k+1's history subkey (AES-256-GCM, one message per subkey), so a
                         holder of the current key walks back to every earlier one (absent under :withhold)}

   THE TWO WRITES, both one root-owning primitive (:p/lockbox-write) the broker checks in the clear:
     rotate  epoch n → n+1, boxes for exactly the readers' roster, history[n] added (under :grant), a fresh key
     extend  the same epoch, commit and history; every existing box byte-identical; boxes added so the recipients
             equal the roster — an extend can add readers, never drop one (a removal needs a rotate)
   A write into a region's paths (:p/region-epoch) must carry the lockbox's current epoch, and is refused while the
   region is STALE (its recipients differ from the readers' roster: a member was added or removed and the admin has not
   extended or rotated yet).

   CONTENT. A record's payload is sealed under the region key's content subkey (AES-256-GCM, a fresh 12-byte iv, the
   aad binding atom, region, epoch and the caller's record identity). A blob is sealed ONCE under its own random
   32-byte data key; the data key is wrapped under the region key's DEK subkey and travels in the record. A blob is
   never re-encrypted when readers change: a new reader gets the old key through the history chain.

   Randomness (keys, ivs, HPKE ephemeral seeds) is the caller's: `random` is (fn [n]) → n fresh bytes."
  (:refer-clojure :exclude [long])
  (:require [eve.aegis.commit :as commit]
            [eve.aegis.hpke :as hpke]
            [eve.aegis.merkle :as merkle]
            [eve.crypto :as crypto]
            [eve.host :refer [long]]
            [eve.net.bytes :as b]
            [eve.net.ed25519 :as ed]))

;; ── encodings

(defn- u32-be [n] [(mod (quot n 16777216) 256) (mod (quot n 65536) 256) (mod (quot n 256) 256) (mod n 256)])
(defn- u64-be [n] (into [0 0 0 0] (u32-be n)))
(defn- lp "a 4-byte big-endian length, then the bytes" [bs] (into (u32-be (count bs)) bs))

(defn- ctx-bytes [label atom-id region epoch]
  (-> (b/utf8 label) (into (lp (b/utf8 (str atom-id)))) (into (lp (b/utf8 (str region)))) (into (u64-be epoch))))

(defn- hexn? "lowercase or uppercase hex text of n bytes" [s n] (and (string? s) (= (* 2 n) (count s)) (every? (fn [c] (some? (b/hex-value c))) s)))

(defn- int-of "a non-negative integer of decimal text, or nil" [s]
  (when (and (string? s) (seq s) (every? (fn [c] (some? (#{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9} c))) s))
    (reduce (fn [acc c] (+ (* 10 acc) (b/hex-value c))) 0 s)))

(defn- same-int? [a b] (and (number? a) (number? b) (== a b)))

;; ── members' encryption keys, from their Ed25519 keys

(defn x25519-secret "the X25519 secret of an Ed25519 seed: the first half of SHA-512(seed) (X25519 clamps it as RFC 8032 does)" [seed]
  (subvec (vec (crypto/sha512 (vec seed))) 0 32))

(def ^:private P-BE (conj (into [0x7f] (repeat 30 0xff)) 0xed))
(def ^:private P-2-BE (conj (into [0x7f] (repeat 30 0xff)) 0xeb))

(defn x25519-public "the X25519 public key of an Ed25519 public key: u = (1 + y) / (1 − y) mod p, little-endian" [ed-pk]
  (let [ed-pk (vec ed-pk)
        y (ed/unpack25519 (assoc ed-pk 31 (mod (nth ed-pk 31) 128)))
        num (vec (rseq (ed/pack25519 (ed/fadd ed/gf1 y))))
        den (vec (rseq (ed/pack25519 (ed/fsub ed/gf1 y))))]
    (vec (rseq (vec (crypto/modmul num (crypto/modpow den P-2-BE P-BE) P-BE))))))

;; ── the key's subkeys and commitment

(defn- subkey [key label] (commit/hkdf-sha256 (vec key) (b/utf8 label) [] 32))
(def ^:private ZERO-IV (vec (repeat 12 0)))

(defn key-commit [atom-id region epoch key] (crypto/sha256 (into (ctx-bytes "aegis.region.key.v1" atom-id region epoch) (vec key))))

;; ── boxes

(defn- box-info [atom-id region epoch] (ctx-bytes "aegis.region.box.v1" atom-id region epoch))

(defn seal-box "the key HPKE-sealed to one reader: {\"enc\" \"ct\"}" [atom-id region epoch key reader-pk ikm-e]
  (let [{:keys [enc ct]} (hpke/seal (x25519-public reader-pk) (box-info atom-id region epoch) (vec reader-pk) (vec key) ikm-e)]
    {"enc" (b/hex enc) "ct" (b/hex ct)}))

(defn open-box "the key in a reader's box, or nil" [atom-id region epoch box seed]
  (when (map? box)
    (hpke/open (x25519-secret seed) (b/unhex (get box "enc")) (box-info atom-id region epoch)
               (vec (crypto/ed25519-public-key (vec seed))) (b/unhex (get box "ct")))))

;; ── lockboxes (built by an admin's client)

(defn- boxes-for [atom-id region epoch key pks random]
  (into {} (map (fn [pk] [(b/hex pk) (seal-box atom-id region epoch key pk (random 32))])) pks))

(defn- wrap-history [atom-id region old-epoch old-key new-key]
  (b/hex (hpke/gcm-seal (subkey new-key "aegis.region.history.v1") ZERO-IV
                        (ctx-bytes "aegis.region.history.v1" atom-id region old-epoch) (vec old-key))))

(defn new-lockbox "epoch 1 for the readers' public keys (byte vectors)" [atom-id region pks key random]
  {"epoch" 1 "commit" (b/hex (key-commit atom-id region 1 key)) "boxes" (boxes-for atom-id region 1 key pks random)
   "history" {}})

(defn rotate-lockbox "epoch n+1 under new-key for exactly pks; history[n] = the old key under the new one unless :withhold"
  [lockbox atom-id region pks old-key new-key random history]
  (let [n (get lockbox "epoch") n' (inc n)]
    {"epoch" n' "commit" (b/hex (key-commit atom-id region n' new-key)) "boxes" (boxes-for atom-id region n' new-key pks random)
     "history" (if (= :withhold history)
                 (get lockbox "history" {})
                 (assoc (get lockbox "history" {}) (str n) (wrap-history atom-id region n old-key new-key)))}))

(defn extend-lockbox "the same epoch, a box added for each of pks not yet holding one" [lockbox atom-id region pks key random]
  (let [have (set (keys (get lockbox "boxes")))
        add (remove (fn [pk] (contains? have (b/hex pk))) pks)]
    (update lockbox "boxes" merge (boxes-for atom-id region (get lockbox "epoch") key add random))))

;; ── readers (a member's client)

(defn open-key
  "the current epoch's key for the member's seed, checked against the lockbox's commitment: nil when the member holds no
   box (or it does not open); throws ex-info {:reason :region/commit-mismatch} when the key it holds is not the
   committed one (an admin handed this member a different key)"
  [lockbox atom-id region seed]
  (let [epoch (get lockbox "epoch")
        box (get-in lockbox ["boxes" (b/hex (crypto/ed25519-public-key (vec seed)))])]
    (when-let [k (open-box atom-id region epoch box seed)]
      (if (= (b/hex (key-commit atom-id region epoch k)) (get lockbox "commit"))
        k
        (throw (ex-info "regions: the unwrapped key is not the committed one" {:reason :region/commit-mismatch :epoch epoch}))))))

(defn key-at
  "the key of epoch `target` (≤ the lockbox's) from the current key, walking the history chain down; nil when a link is
   missing (:withhold) or does not open"
  [lockbox atom-id region key target]
  (loop [e (get lockbox "epoch") k (vec key)]
    (cond (nil? k) nil
          (== e target) k
          (< e target) nil
          :else (let [w (get-in lockbox ["history" (str (dec e))])]
                  (recur (dec e) (when w (hpke/gcm-open (subkey k "aegis.region.history.v1") ZERO-IV
                                                        (ctx-bytes "aegis.region.history.v1" atom-id region (dec e)) (b/unhex w))))))))

;; ── content and blobs

(defn seal-content "a record payload under the epoch's key: {\"iv\" \"ct\"} (iv: 12 fresh random bytes)" [atom-id region epoch key aad pt iv]
  {"iv" (b/hex iv)
   "ct" (b/hex (hpke/gcm-seal (subkey key "aegis.region.content.v1") (vec iv)
                              (into (ctx-bytes "aegis.region.content.v1" atom-id region epoch) (vec aad)) (vec pt)))})

(defn open-content "the payload, or nil" [atom-id region epoch key aad {:strs [iv ct]}]
  (hpke/gcm-open (subkey key "aegis.region.content.v1") (b/unhex iv)
                 (into (ctx-bytes "aegis.region.content.v1" atom-id region epoch) (vec aad)) (b/unhex ct)))

(defn seal-blob "a blob sealed once under its own data key: iv ‖ ciphertext ‖ tag (the bytes the blob store holds)" [dek iv bytes]
  (into (vec iv) (hpke/gcm-seal (vec dek) (vec iv) (b/utf8 "aegis.region.blob.v1") (vec bytes))))

(defn open-blob "the blob's bytes, or nil" [dek sealed]
  (let [sealed (vec sealed)]
    (when (> (count sealed) 28)
      (hpke/gcm-open (vec dek) (subvec sealed 0 12) (b/utf8 "aegis.region.blob.v1") (subvec sealed 12)))))

(defn wrap-dek "a blob's data key under the epoch's key, bound to the blob's content id: {\"iv\" \"ct\"}" [atom-id region epoch key cid dek iv]
  {"iv" (b/hex iv)
   "ct" (b/hex (hpke/gcm-seal (subkey key "aegis.region.dek.v1") (vec iv)
                              (into (ctx-bytes "aegis.region.dek.v1" atom-id region epoch) (lp (b/utf8 (str cid)))) (vec dek)))})

(defn unwrap-dek "the blob's data key, or nil" [atom-id region epoch key cid {:strs [iv ct]}]
  (hpke/gcm-open (subkey key "aegis.region.dek.v1") (b/unhex iv)
                 (into (ctx-bytes "aegis.region.dek.v1" atom-id region epoch) (lp (b/utf8 (str cid)))) (b/unhex ct)))

;; ── the spec

(defn regions [spec] (get-in spec [:read-regions :entries]))
(defn region-named [spec region] (first (filter (fn [r] (= region (:name r))) (regions spec))))
(defn reader-group "\"domain/name\" of a region's readers" [r] (str (get-in r [:readers :groupDomain]) "/" (get-in r [:readers :groupName])))
(defn history-mode [r] (if (= :withhold (:history r)) :withhold :grant))

;; ── what the broker checks (structure only: it holds no key)

(defn recipients "the lockbox's recipient set, Ed25519 public keys as lowercase hex" [lockbox] (set (keys (get lockbox "boxes"))))

(defn lockbox-root
  "the 32-byte root a claim binds for a lockbox (32 zero bytes for none): the SHA-256 chain over its label, epoch,
   commitment, each box (sorted by recipient) and each history link (by epoch)"
  [lockbox]
  (if (nil? lockbox)
    (vec (repeat 32 0))
    (let [boxes (get lockbox "boxes" {}) hist (get lockbox "history" {})]
      (merkle/chain (concat [(merkle/field-of "aegis.region.lockbox.v1") (merkle/field-of (long (get lockbox "epoch")))
                             (b/unhex (get lockbox "commit"))]
                            (mapcat (fn [pk] [(b/unhex pk) (crypto/sha256 (into (b/unhex (get-in boxes [pk "enc"])) (b/unhex (get-in boxes [pk "ct"]))))])
                                    (sort (keys boxes)))
                            (mapcat (fn [e] [(merkle/field-of (int-of e)) (crypto/sha256 (b/unhex (get hist e)))])
                                    (sort-by int-of (keys hist))))))))

(defn- box-ok? [box] (and (map? box) (hexn? (get box "enc") 32) (hexn? (get box "ct") 48)))

(defn lockbox-shape-refusal "nil, or why a lockbox is malformed" [lb]
  (cond (not (map? lb)) :lockbox-malformed
        (not (and (integer? (get lb "epoch")) (pos? (get lb "epoch")))) :lockbox-epoch
        (not (hexn? (get lb "commit") 32)) :lockbox-malformed
        (not (and (map? (get lb "boxes")) (every? (fn [[k v]] (and (hexn? k 32) (box-ok? v))) (get lb "boxes")))) :lockbox-malformed
        (not (and (map? (get lb "history" {})) (every? (fn [[k v]] (and (int-of k) (hexn? v 48))) (get lb "history" {})))) :lockbox-malformed
        :else nil))

(defn lockbox-refusal
  "nil when `new` is a valid next lockbox after `old` (nil: none yet) for the readers' roster (a set of hex public keys)
   under the region's history mode, else the reason: a rotate (epoch + 1) or an extend (the same epoch) as the ns
   docstring states"
  [old new roster history]
  (let [e-old (if old (get old "epoch") 0) e-new (get new "epoch")
        h-old (get old "history" {}) h-new (get new "history" {})]
    (or (lockbox-shape-refusal new)
        (cond (not= (recipients new) (set roster)) :lockbox-recipients
              (same-int? e-new (inc e-old))
              (cond (and old (= :grant history))
                    (when-not (and (= (dissoc h-new (str e-old)) h-old) (hexn? (get h-new (str e-old)) 48)) :lockbox-history)
                    (not= h-new h-old) :lockbox-history
                    :else nil)
              (and old (same-int? e-new e-old))
              (cond (not= (get new "commit") (get old "commit")) :lockbox-extend-commit
                    (not= h-new h-old) :lockbox-history
                    (not (every? (fn [[k v]] (= v (get-in new ["boxes" k]))) (get old "boxes"))) :lockbox-extend-changed
                    :else nil)
              :else :lockbox-epoch))))

(defn write-refusal
  "nil when a record may be written into a region whose lockbox is `lockbox`, for the readers' roster and the record's
   key_epoch, else :region-no-lockbox, :region-stale (the recipients are not the roster) or :region-epoch"
  [lockbox roster key-epoch]
  (cond (nil? lockbox) :region-no-lockbox
        (not= (recipients lockbox) (set roster)) :region-stale
        (not (same-int? key-epoch (get lockbox "epoch"))) :region-epoch
        :else nil))
