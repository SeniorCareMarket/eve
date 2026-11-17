(ns eve.net.presign
  "The dev presigner: HMAC-SHA-256 over a blob's key, the mode (read or write) and an expiry — a stand-in for an S3
   store's SigV4. A broker presigns with hmac-presigner (its :presign); the store (eve.net.store) holds the same secret
   and checks with hmac-ok?; the client holds only the URL. Apart from eve.net.gateway (which re-exports both) because
   the gateway needs eve.atom and a broker or a store does not."
  (:require [eve.crypto :as prim]
            [eve.net.bytes :as b]))

(defn- sig [secret k mode exp]
  (b/hex (prim/hmac-sha256 (b/utf8 secret) (b/utf8 (str k "|" mode "|" exp)))))

(defn hmac-presigner
  "A presigner for a store at url that checks HMAC-SHA-256 signatures under secret (a stand-in for an S3 store's
   SigV4: the store holds the secret, the client only the URL). now-ms: the clock (the expiry is now + ttl s)."
  [{:keys [url secret now-ms]}]
  (fn [k mode ttl]
    (let [exp (+ (quot (now-ms) 1000) (or ttl 300))]
      (str url k "?mode=" mode "&exp=" exp "&sig=" (sig secret k mode exp)))))

(defn hmac-ok?
  "Does a presigned request carry a valid signature for key k and the method's mode, unexpired at now-s?"
  [secret k mode exp signature now-s]
  (and (string? signature) (some? exp) (> exp now-s) (= signature (sig secret k mode exp))))
