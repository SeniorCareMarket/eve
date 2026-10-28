(ns eve.net.auth
  "Signing in to the broker (eve/doc/network-atoms/ADAPTERS.md §4.6) as sans-IO steps (eve.net.run): RFC 8628's device
   flow, the refresh, and the check of the broker's EdDSA tokens — plain HTTP and JSON over the http feature and
   eve.crypto/ed25519-verify, no new host primitive. The broker's side is atom-broker/services/atom-broker/src/broker/auth/
   (device.vs, refresh.vs, jwt.vs); every rule here is read from there.

   The flow: (device-code cfg) → show its :user-code and :verification-uri to a person → (await-token cfg code) polls
   until they approve → {:token :refresh-token}. The token is the wire's bearer: (with-token cfg token) — the broker
   reads a bearer that contains a dot as a JWT (broker.auth.resolve). It lives an hour; (refresh cfg refresh-token)
   answers the next pair. The refresh token comes back only in a `set-cookie: vs-refresh=…` header, which a server-side
   http adapter shows and a browser's never does (a browser keeps the cookie in its jar instead).

   Config: the wire's {:endpoint :domain-id}."
  (:require [eve.net.run :as r]
            [eve.net.json :as json]
            [eve.net.bytes :as b]
            [eve.encoding :as e]
            [eve.crypto :as prim]))

(defn- post [cfg path body]
  {:method "POST" :url (str (:endpoint cfg) path) :headers {"Content-Type" "application/json"} :body (json/write-str body)})

(defn- body-of [resp]
  (let [t (:body resp)] (when (and (string? t) (pos? (count t))) (json/read-str-or-nil t))))

(defn- fail! [resp what]
  (throw (ex-info (str what ": " (or (:error resp) (str "HTTP " (:status resp)))) {:status (:status resp) :body (:body resp)})))

(defn- split-units
  "the pieces of s between the UTF-16 unit u (by unit, as eve.net.json reads: no host string method)"
  [s u]
  (loop [us (seq (e/utf16-units s)) cur [] out []]
    (cond (nil? us) (conj out (b/units->str cur))
          (= u (first us)) (recur (next us) [] (conj out (b/units->str cur)))
          :else (recur (next us) (conj cur (first us)) out))))

(defn- cookie-value
  "the value of cookie `nm` in a set-cookie header value, or nil"
  [header nm]
  (when (string? header)
    (some (fn [part]
            (let [[k & vs] (split-units part 61)]                        ;; 61: =
              (when (= nm (apply str (remove (fn [c] (= " " (str c))) k)))
                (apply str (interpose "=" vs)))))
          (split-units header 59))))                                     ;; 59: ;

(defn- refresh-token-of [resp] (cookie-value (get (:headers resp) "set-cookie") "vs-refresh"))

(defn device-code
  "POST /v1/auth/device/code → {:device-code :user-code :verification-uri :expires-in :interval} (seconds)"
  [cfg]
  (r/request (post cfg "/v1/auth/device/code" {"domain_id" (:domain-id cfg)})
             (fn [resp]
               (when-not (= 200 (:status resp)) (fail! resp "device code"))
               (let [bd (body-of resp)]
                 (r/done {:device-code (get bd "device_code") :user-code (get bd "user_code")
                          :verification-uri (get bd "verification_uri")
                          :expires-in (get bd "expires_in") :interval (or (get bd "interval") 5)})))))

(defn device-token
  "one poll of POST /v1/auth/device/token: {:status :pending} until a person approves the code, then
   {:status :ok :token :refresh-token}; {:status :expired} for a code the broker no longer holds (spent or timed out),
   {:status :denied} for an approver the broker refused since (RFC 8628 §3.5)"
  [cfg device-code]
  (r/request (post cfg "/v1/auth/device/token" {"device_code" device-code})
             (fn [resp]
               (let [bd (body-of resp) s (:status resp)]
                 (r/done
                  (cond (and (= 200 s) (= "authorization_pending" (get bd "status"))) {:status :pending}
                        (and (= 200 s) (string? (get bd "token"))) {:status :ok :token (get bd "token")
                                                                    :refresh-token (refresh-token-of resp)}
                        (and (= 400 s) (= "expired_token" (get bd "error"))) {:status :expired}
                        (and (= 400 s) (= "access_denied" (get bd "error"))) {:status :denied}
                        :else (fail! resp "device token")))))))

(defn await-token
  "polls device-token every :interval seconds until it is not pending, or until the code's :expires-in has passed
   ({:status :expired}); answers the last poll"
  [cfg {:keys [device-code interval expires-in]}]
  (let [ms (* 1000 (or interval 5))
        polls (max 1 (quot (+ (or expires-in 600) (or interval 5) -1) (or interval 5)))]
    (letfn [(go [n]
              (r/then (device-token cfg device-code)
                      (fn [ans]
                        (cond (not= :pending (:status ans)) (r/done ans)
                              (>= n polls) (r/done {:status :expired})
                              :else (r/sleep ms (fn [] (go (inc n))))))))]
      (go 1))))

(defn refresh
  "POST /v1/auth/token-refresh with the refresh token → {:status :ok :token :refresh-token} (the broker rotates it:
   the old one is spent), or {:status :revoked} (401: expired, revoked, or no longer a member of its domain)"
  [cfg refresh-token]
  (r/request (post cfg "/v1/auth/token-refresh" {"refresh_token" refresh-token})
             (fn [resp]
               (let [bd (body-of resp) s (:status resp)]
                 (r/done
                  (cond (and (= 200 s) (string? (get bd "token"))) {:status :ok :token (get bd "token")
                                                                    :refresh-token (refresh-token-of resp)}
                        (= 401 s) {:status :revoked}
                        :else (fail! resp "token refresh")))))))

(defn with-token "the wire's config bearing the token (the wire sends :api-key as its bearer)" [cfg token]
  (assoc cfg :api-key token))

;; ── the token: what broker.auth.jwt/verify checks, read from there

(def ISSUERS #{"auth.atom.broker" "dev.auth.atom.broker"})

(defn- b64url->bytes [s]
  (b/from-b64 (apply str (map (fn [c] (let [c (str c)] (cond (= c "-") "+" (= c "_") "/" :else c))) s))))

(defn claims
  "the claims (a string-keyed map) of a broker token that verifies under the 32-byte public key at now-s (seconds since
   the epoch), else nil: an EdDSA signature over the token's first two parts, audience atom.broker, an issuer in
   ISSUERS, and an exp, when it has one, after now-s — broker.auth.jwt/verify's checks"
  [token public-key now-s]
  (let [parts (when (string? token) (split-units token 46))]               ;; 46: .
    (when (= 3 (count parts))
      (let [[h p s] parts
            header (json/read-str-or-nil (b/utf8->str (b64url->bytes h)))
            payload (json/read-str-or-nil (b/utf8->str (b64url->bytes p)))]
        (when (and (map? header) (= "EdDSA" (get header "alg")) (map? payload)
                   (prim/ed25519-verify public-key (b/utf8 (str h "." p)) (b64url->bytes s))
                   (= "atom.broker" (get payload "aud"))
                   (contains? ISSUERS (get payload "iss"))
                   (let [exp (get payload "exp")] (or (nil? exp) (and (number? exp) (> exp now-s)))))
          payload)))))
