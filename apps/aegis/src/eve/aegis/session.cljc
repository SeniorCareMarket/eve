(ns eve.aegis.session
  "Delegated sessions as one-show keys (aegis's clear evaluation; the chat spec's :sessions and its
   :per-session scope, chat-steroids phase 5). The owner hands a guest a 32-byte session secret; the guest derives an
   Ed25519 key from it; the broker registers the session's PUBLIC key with its issuance epoch in the session registry
   — a map {pk-hex issuance-epoch}, the value of the broker's sessions atom. A scratch post is signed by the session
   key and names the session's key and issuance; the checker reads the registry's value (the session registered with
   that issuance), the expiry (now − issuance ≤ :max-lifetime-epochs) and the signature; the session public key is
   the nullifier, so a session posts once. Nothing identifies the guest, and the delegator is known only to the broker
   that registered the session (as today).

     (session-keypair secret)              {:seed :pk}
     (register registry pk issuance)       the registry with the session added (refuses a second registration)
     (valid-session? registry pk issuance now max-lifetime)  boolean: registered with that issuance, not expired"
  (:require [eve.crypto :as crypto]
            [eve.net.bytes :as b]))

(defn session-keypair [secret]
  (let [seed (crypto/sha256 (into (b/utf8 "aegis.session.key.v1") (vec secret)))]
    {:seed seed :pk (crypto/ed25519-public-key seed)}))

(defn- pk-key [pk] (b/hex pk))

(defn register [registry pk issuance]
  (let [k (pk-key pk)]
    (when (contains? registry k) (throw (ex-info "session/register: already registered" {:pk k})))
    (assoc registry k issuance)))

(defn valid-session?
  "is the session pk registered in the registry's value with this issuance, and now within max-lifetime of it"
  [registry pk issuance now max-lifetime]
  (boolean
   (and (map? registry) (integer? issuance) (integer? now) (integer? max-lifetime)
        (= issuance (get registry (pk-key pk)))
        (<= 0 (- now issuance) max-lifetime))))
