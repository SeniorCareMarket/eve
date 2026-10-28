(ns eve.net.auth-test
  "eve.net.auth against a scripted broker that answers as atom-broker/services/atom-broker/src/broker/auth/device.vs and
   refresh.vs do, and its token check against tokens Python's cryptography signed (an independent Ed25519): the RFC
   8032 TEST 1 key, header {\"alg\":\"EdDSA\",\"typ\":\"JWT\"}."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.net.auth :as auth]
            [eve.net.bytes :as b]
            [eve.net.json :as json]
            [eve.net.run :as r]))

(def ^:private PUB (b/unhex "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"))
;; exp 4102444800 (2100), sub u1, domain_id d1, role admin
(def ^:private TOKEN "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE3NjAwMDAwMDAsImlzcyI6ImF1dGguYXRvbS5icm9rZXIiLCJhdWQiOiJhdG9tLmJyb2tlciIsImV4cCI6NDEwMjQ0NDgwMCwic3ViIjoidTEiLCJkb21haW5faWQiOiJkMSIsInJvbGUiOiJhZG1pbiJ9.DFT7iiG0JZTAt8J8-FlQ-KgUDnEAyV-wgFQ8P1-R9K9hrz4ZqhNkKxfYxDFDgbVwYFxny7WlseZUxQOnbUybDA")
;; exp 1760003600
(def ^:private TOKEN-EXP "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE3NjAwMDAwMDAsImlzcyI6ImF1dGguYXRvbS5icm9rZXIiLCJhdWQiOiJhdG9tLmJyb2tlciIsImV4cCI6MTc2MDAwMzYwMCwic3ViIjoidTEifQ.V11xAADEhDJFgbAXo6QcxQoVchy7tZcsOXbQjOTRuiIWvpS2jADcKB9kx1AmXnHctbJOWCcT2RZET0YKMf7LCw")
;; iss evil.example, no exp
(def ^:private TOKEN-ISS "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE3NjAwMDAwMDAsImlzcyI6ImV2aWwuZXhhbXBsZSIsImF1ZCI6ImF0b20uYnJva2VyIiwic3ViIjoidTEifQ.4-HQ-5fTZtVIblCYZn8rR0EeqJ173v2ymmSNZHzXpNwam902Dvhds-YcIGkZi7DykMb9AdxL0L77sXdApzgfBw")

(deftest the-token-check
  (testing "a token the broker's rules accept: its claims"
    (is (= {"iat" 1760000000 "iss" "auth.atom.broker" "aud" "atom.broker" "exp" 4102444800 "sub" "u1" "domain_id" "d1"
            "role" "admin"}
           (auth/claims TOKEN PUB 1760000100))))
  (testing "refused: expired, another issuer, another key, a changed payload, not three parts"
    (is (= "u1" (get (auth/claims TOKEN-EXP PUB 1760000100) "sub")) "before its exp")
    (is (nil? (auth/claims TOKEN-EXP PUB 1760003600)) "at its exp")
    (is (nil? (auth/claims TOKEN-ISS PUB 1760000100)))
    (is (nil? (auth/claims TOKEN (b/unhex "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c") 1760000100)))
    (let [[h p s] [(subs TOKEN 0 36) (subs TOKEN 37 (- (count TOKEN) 87)) (subs TOKEN (- (count TOKEN) 86))]
          p2 (str (subs p 0 10) (if (= "A" (subs p 10 11)) "B" "A") (subs p 11))]
      (is (= TOKEN (str h "." p "." s)) "the pieces")
      (is (nil? (auth/claims (str h "." p2 "." s) PUB 1760000100))))
    (is (nil? (auth/claims "a.b" PUB 0)))
    (is (nil? (auth/claims nil PUB 0)))))

(defn- broker
  "a scripted broker: the device flow answers pending `pending` times, then the token with its refresh cookie; a
   refresh of rt-1 rotates to rt-2, any other is 401. Records the requests and the sleeps in `log`."
  [log pending]
  (let [polls (atom 0)]
    {:request (fn [req]
                (swap! log conj [:request (:url req) (json/read-str (:body req))])
                (let [body (json/read-str (:body req))]
                  (case (:url req)
                    "https://broker.test/v1/auth/device/code"
                    {:status 200 :headers {}
                     :body (json/write-str {"device_code" "dc-1" "user_code" "ABCD23" "interval" 5 "expires_in" 600
                                            "verification_uri" "https://broker.test/v1/auth/device/verify"})}
                    "https://broker.test/v1/auth/device/token"
                    (cond (not= "dc-1" (get body "device_code")) {:status 400 :headers {} :body "{\"error\":\"expired_token\"}"}
                          (< @polls pending) (do (swap! polls inc)
                                                 {:status 200 :headers {} :body "{\"status\":\"authorization_pending\"}"})
                          :else {:status 200
                                 :headers {"set-cookie" "vs-refresh=rt-1; HttpOnly; Secure; SameSite=Strict; Path=/v1/auth; Max-Age=604800"}
                                 :body (json/write-str {"token" TOKEN})})
                    "https://broker.test/v1/auth/token-refresh"
                    (if (= "rt-1" (get body "refresh_token"))
                      {:status 200 :headers {"set-cookie" "vs-refresh=rt-2; HttpOnly; Path=/v1/auth"} :body (json/write-str {"token" "t-2"})}
                      {:status 401 :headers {} :body "{\"error\":\"invalid or expired refresh token\"}"}))))
     :sleep (fn [ms] (swap! log conj [:sleep ms]))
     :now-ms (fn [] 0)
     :seed 1}))

(def ^:private CFG {:endpoint "https://broker.test" :domain-id "d1"})

(deftest the-device-flow
  (let [log (atom [])
        p (broker log 2)
        code (r/run p (auth/device-code CFG))]
    (is (= {:device-code "dc-1" :user-code "ABCD23" :verification-uri "https://broker.test/v1/auth/device/verify"
            :expires-in 600 :interval 5}
           code))
    (is (= {:status :ok :token TOKEN :refresh-token "rt-1"} (r/run p (auth/await-token CFG code))))
    (is (= [[:request "https://broker.test/v1/auth/device/code" {"domain_id" "d1"}]
            [:request "https://broker.test/v1/auth/device/token" {"device_code" "dc-1"}] [:sleep 5000]
            [:request "https://broker.test/v1/auth/device/token" {"device_code" "dc-1"}] [:sleep 5000]
            [:request "https://broker.test/v1/auth/device/token" {"device_code" "dc-1"}]]
           @log)
        "polled at the broker's interval until approved")
    (is (= {:status :expired} (r/run p (auth/device-token CFG "dc-0"))) "a code the broker does not hold")
    (is (= "u1" (get (auth/claims (:token (r/run p (auth/await-token CFG code))) PUB 1760000100) "sub"))))
  (testing "a person who never approves: expired once expires-in has passed, never more polls than it allows"
    (let [log (atom [])
          p (broker log 1000)]
      (is (= {:status :expired} (r/run p (auth/await-token CFG {:device-code "dc-1" :interval 5 :expires-in 20}))))
      (is (= 4 (count (filter (fn [[k]] (= k :request)) @log)))))))

(deftest the-refresh
  (let [p (broker (atom []) 0)]
    (is (= {:status :ok :token "t-2" :refresh-token "rt-2"} (r/run p (auth/refresh CFG "rt-1"))))
    (is (= {:status :revoked} (r/run p (auth/refresh CFG "rt-x"))))
    (is (= "t-2" (:api-key (auth/with-token CFG "t-2"))) "the wire's bearer")))
