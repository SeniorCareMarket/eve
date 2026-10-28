(ns eve.aegis.chat-test
  "The REPL chat client's portable core (eve/capstone/eve-chat): G.2 held to a vector computed independently with
   Node's crypto from the web client's construction (src/aegis/chat/content_encrypt.vs, message_crypto.vs) — the room
   key, the record (id, ciphertext, iv, commitment) and its decryption; tamper and commitment-mismatch placeholders;
   the invite fragment; the records' prepare-post; the branch each action writes in the chat spec."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.aegis.chat-spec :as chat-spec]
            [eve.aegis.chat.client :as c]
            [eve.aegis.chat.g2 :as g2]
            [eve.aegis.chat.records :as rec]
            [eve.aegis.claims :as claims]
            [eve.net.bytes :as b]))

;; Node 24: crypto.hkdfSync, createCipheriv("aes-256-gcm"), createHmac — the construction, not eve's code
(def ^:private V
  {:secret (vec (range 32)) :atom "aegis.chat.public" :author "alice" :text "héllo, wörld — G.2 ✓" :ts 1700000000000
   :iv [1 2 3 4 5 6 7 8 9 10 11 12] :id-entropy (mapv (fn [i] (+ 100 i)) (range 16))
   :room-key "73b6f5d8c214195182f8486be5ad68e0d7de8e55de72c0a416c3996ccd6fd2d0"
   :id "64656667-6869-4a6b-ac6d-6e6f70717273"
   :ciphertext "kJEm/BEuL4jrwW0lWl0zv97b/TOkLj1kA/pF7mYgQ2YQ9ZKdG3pd+3bg"
   :ivB64 "AQIDBAUGBwgJCgsM"
   :content_commitment "83cb180ce6d3f09501ac240c8ff17d3e104b239657fab90b44a31ae9b36fe1ea"
   :fragment "room=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"})

(deftest g2-matches-the-web-client
  (let [room (g2/open-room (:secret V) (:atom V))
        rec (g2/encrypt-message room (:author V) (:text V) {:iv (:iv V) :id-entropy (:id-entropy V) :ts (:ts V)})]
    (is (= (:room-key V) (b/hex (:key room))))
    (is (= {"id" (:id V) "author" "alice" "ts" (:ts V) "content_commitment" (:content_commitment V)
            "ciphertext" (:ciphertext V) "ivB64" (:ivB64 V)} rec))
    (is (= (:text V) (g2/decrypt-message room rec)))
    (testing "the invite fragment"
      (is (= (:fragment V) (g2/invite-fragment (:secret V))))
      (is (= (:secret V) (g2/parse-room-secret (str "http://127.0.0.1:5173/#user=alice&" (:fragment V) "&key=00")))))
    (testing "placeholders, as the web page renders them"
      (is (= g2/UNABLE-TO-DECRYPT (g2/decrypt-message (g2/open-room (vec (range 1 33)) (:atom V)) rec)) "another room secret")
      (is (= g2/UNABLE-TO-DECRYPT (g2/decrypt-message room (assoc rec "author" "mallory"))) "moved to another author")
      (is (= g2/COMMITMENT-MISMATCH (g2/decrypt-message room (assoc rec "content_commitment" (apply str (repeat 64 "0"))))))
      (is (= "a pre-G.2 text" (g2/decrypt-message room {"id" "x" "author" "bob" "text" "a pre-G.2 text"}))))))

(deftest records-and-branches
  (let [spec chat-spec/spec
        rb (vec (range 64))]
    (testing "the branch each action writes"
      (doseq [[action branch] [["post-message" "post-message"] ["create-poll" "create-poll"] ["cast-vote" "cast-vote"]
                               ["add-member" "add-member"] ["remove-member" "remove-member"] ["post-announcement" "post-announcement"]
                               ["post-private-message" "private-message"] ["open-scratch-space" "open-scratch-space"]
                               ["write-scratch" "scratch-post"] ["append-audit-log" "audit-log"]]]
        (is (= branch (:branch (c/branch-for spec action))) action)))
    (testing "genesis (eve.aegis.claims, the one definition): every append path [], every key path {}, the evaluator's band"
      (is (= {:rooms {:public {:messages [] :polls [] :votes []} :private {:members {} :messages []}
                      :announcements {:messages []}}
              :scratch-spaces {:rooms [] :posts []} :audit-log [] :aegis {:spent {} :ballot-spends {}}}
             (claims/genesis spec))))
    (testing "prepare-post: the intent on the old state (the write places it)"
      (let [g (claims/genesis spec)
            p (rec/prepare-post g {"action" "create-poll" "question" "Lunch?" "options" ["pizza" "sushi"]} "alice" rb 1700000000000)]
        (is (= "ok" (:status p)))
        (is (= rec/PUBLIC-POLLS (:path p)))
        (is (= ["pizza" "sushi"] (:options (:record p))))
        (is (= "alice" (:author (:record p))))
        (is (= "empty" (:status (rec/prepare-post g {"action" "create-poll" "question" "  "} "alice" rb 1))))
        (let [pk (apply str (repeat 64 "a"))
              p (rec/prepare-post g {"action" "add-member" "member_id" "m-bob" "public_key" pk} "carol" rb 1)
              with-bob (assoc-in g (conj rec/PRIVATE-MEMBERS "m-bob") pk)]
          (is (= ["m-bob" pk] [(:key p) (:value p)]))
          (is (= "denied" (:status (rec/prepare-post with-bob {"action" "add-member" "member_id" "m-bob" "public_key" pk} "carol" rb 1))))
          (is (= "denied" (:status (rec/prepare-post g {"action" "add-member" "member_id" "m-x" "public_key" "nope"} "carol" rb 1))))
          (is (= {:status "ok" :action "remove-member" :path rec/PRIVATE-MEMBERS :key "m-bob"}
                 (rec/prepare-post with-bob {"action" "remove-member" "member_id" "m-bob"} "carol" rb 1))))
        (is (= "denied" (:status (rec/prepare-post g {"action" "post-message" "text" "plaintext"} "alice" rb 1)))
            "a message must be the G.2 record")
        (is (= :ciphertext (some #{:ciphertext}
                                 (keys (:record (rec/prepare-post g {"action" "post-message" "record" {"id" "x" "ciphertext" "c"}} "alice" rb 1)))))
            "the G.2 record keyword-keyed")))))
