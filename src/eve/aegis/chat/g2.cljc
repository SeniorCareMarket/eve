(ns eve.aegis.chat.g2
  "The aegis chat's message encryption (G.2) in portable cljc, byte for byte the web client's
   (src/aegis/chat/content_encrypt.vs and message_crypto.vs): a REPL client and a browser in one room read each other's
   messages. The broker sees {id author ts content_commitment ciphertext ivB64} and never the room key.

     room secret   32 random bytes in the invite link's fragment, #room=<base64url> (parse-room-secret)
     room key      HKDF-SHA256(ikm = the secret, salt = \"aegis.chat.room-key.v2\", info = the atom id), 32 bytes
     ciphertext    base64(AES-256-GCM(room key, iv, UTF-8 text, aad) ‖ tag), ivB64 = base64(the 12-byte iv)
     aad           \"aegis.chat.msg.v2\" ‖ lp(atom id) ‖ lp(id) ‖ lp(author), lp = 4-byte big-endian UTF-8 length + bytes
     commitment    hex HMAC-SHA256(HKDF-SHA256(ikm = room key, salt = \"aegis.chat.commit-key.v3\"),
                   \"aegis.chat.commit.v3\" ‖ iv ‖ UTF-8 text) — eve.aegis.commit's hiding commitment
   AES-256-GCM is the crypto contract's row (a host's native GCM where it has one). Randomness is the caller's: an iv
   of 12 fresh bytes, 16 for a message id (a version-4 UUID)."
  (:require [clojure.string :as str]
            [eve.aegis.commit :as commit]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.thread-host :as h]))

(def UNABLE-TO-DECRYPT "<unable to decrypt>")
(def COMMITMENT-MISMATCH "<commitment mismatch>")

;; ── base64url (RFC 4648 §5, unpadded) over eve.net.bytes' standard base64

(defn base64url [bs]
  (-> (b/to-b64 bs) (str/replace "+" "-") (str/replace "/" "_") (str/replace "=" "")))

(defn unbase64url [s] (b/from-b64 (-> (str s) (str/replace "-" "+") (str/replace "_" "/"))))

;; ── the invite fragment

(defn fragment-params
  "the key=value pairs of an invite link's fragment (the text after #, or the whole text when it has none) as a map"
  [text]
  (let [t (str/trim (str text))
        t (if-let [i (str/index-of t "#")] (subs t (inc i)) t)]
    (into {} (keep (fn [kv] (let [i (str/index-of kv "=")] (when i [(subs kv 0 i) (subs kv (inc i))]))))
          (str/split t #"&"))))

(defn parse-room-secret
  "the 32 room-secret bytes of an invite fragment (\"#room=…\", other parameters ignored) or a key file's bare
   base64url text"
  [text]
  (let [p (get (fragment-params text) "room")
        bs (vec (unbase64url (or p (str/trim (str text)))))]
    (when-not (= 32 (count bs)) (throw (ex-info (str "g2: a room secret is 32 bytes; this text holds " (count bs)) {:count (count bs)})))
    bs))

(defn invite-fragment "\"room=<base64url>\" for a room secret (append it as #room=… to the page's address)" [secret]
  (str "room=" (base64url secret)))

;; ── keys, aad, commitment

(defn room-key [secret atom-id]
  (commit/hkdf-sha256 (vec secret) (b/utf8 "aegis.chat.room-key.v2") (b/utf8 (str atom-id)) 32))


(defn- u32-be [n] [(mod (quot n 16777216) 256) (mod (quot n 65536) 256) (mod (quot n 256) 256) (mod n 256)])
(defn- lp [s] (let [bs (b/utf8 (str s))] (into (u32-be (count bs)) bs)))

(defn message-aad [room id author]
  (-> (vec (b/utf8 "aegis.chat.msg.v2")) (into (lp (:atom-id room))) (into (lp id)) (into (lp author))))

(defn commit-key "the commitment's key: HKDF-SHA256(ikm = the room key, salt = \"aegis.chat.commit-key.v3\"), 32 bytes" [key]
  (commit/hkdf-sha256 (vec key) (b/utf8 "aegis.chat.commit-key.v3") [] 32))

(defn open-room "{:atom-id :key :commit-key}: the room a member joined (its keys derived once)" [secret atom-id]
  (let [k (room-key secret atom-id)] {:atom-id (str atom-id) :key k :commit-key (commit-key k)}))

(defn content-commitment
  "64 lowercase hex: the hiding commitment of the text under the room (its commit key) and iv"
  [room text iv-b64]
  (b/hex (commit/commitment (or (:commit-key room) (commit-key (:key room)))
                            (vec (b/from-b64 (str iv-b64))) (b/utf8 (str text)))))

;; ── a record

(defn uuid4 "a version-4 UUID from 16 random bytes (RFC 9562 §5.4)" [bs]
  (let [v (vec bs)
        v (-> v (assoc 6 (+ 64 (mod (nth v 6) 16))) (assoc 8 (+ 128 (mod (nth v 8) 64))))
        h (b/hex (subvec v 0 16))]
    (str (subs h 0 8) "-" (subs h 8 12) "-" (subs h 12 16) "-" (subs h 16 20) "-" (subs h 20 32))))

(defn encrypt-message
  "the G.2 record {\"id\" \"author\" \"ts\" \"content_commitment\" \"ciphertext\" \"ivB64\"} of a text: iv 12 fresh random
   bytes (never all zero), id-entropy 16 (or :id given), ts the caller's clock"
  [room author text {:keys [iv id-entropy id ts]}]
  (when-not (and (= 12 (count iv)) (some pos? iv)) (throw (ex-info "g2: the iv must be 12 fresh random bytes" {})))
  (let [id (or id (uuid4 id-entropy))
        author (str author) text (str text)
        sealed (crypto/aes256-gcm-seal (:key room) (vec iv) (message-aad room id author) (b/utf8 text))
        iv-b64 (b/to-b64 iv)]
    {"id" id "author" author "ts" ts
     "content_commitment" (content-commitment room text iv-b64)
     "ciphertext" (b/to-b64 sealed) "ivB64" iv-b64}))

(defn- attempt "f's value, or nil when it throws (the host's catch-all, eve.thread-host)" [f]
  (let [[ok? v] (h/attempt f)] (when ok? v)))

(defn- field [m k] (let [v (get m k)] (if (nil? v) (get m (keyword k)) v)))

(defn decrypt-message
  "the text of a stored record: opened under its aad and checked against its commitment; UNABLE-TO-DECRYPT when it
   does not open, COMMITMENT-MISMATCH when the opened text is not the committed one, a pre-G.2 record's own text"
  [room rec]
  (let [ct (field rec "ciphertext")]
    (if (nil? ct)
      (str (or (field rec "text") ""))
      (let [iv-b64 (field rec "ivB64")
            iv (vec (b/from-b64 (str iv-b64)))
            pt (when (= 12 (count iv))
                 (attempt (fn [] (crypto/aes256-gcm-open (:key room) iv (message-aad room (field rec "id") (field rec "author"))
                                                         (vec (b/from-b64 (str ct)))))))
            text (when pt (attempt (fn [] (b/utf8->str (vec pt)))))]
        (cond (nil? text) UNABLE-TO-DECRYPT
              (not= (content-commitment room text iv-b64) (str/lower-case (str (field rec "content_commitment")))) COMMITMENT-MISMATCH
              :else text)))))
