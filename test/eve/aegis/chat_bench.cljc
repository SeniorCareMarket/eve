(ns eve.aegis.chat-bench
  "The eve-chat capstone's cost rows (eve/capstone/eve-chat/README.md), portable: what a REPL member's crypto costs on
   this host's crypto adapter (hosts.edn). Each row is the mean of eve.process/now-ms deltas after one warm-up call,
   repeated until ~400 ms have passed (at least once, at most 200 times). Printed as `CHAT-BENCH <row> <ms> ms`:
     join            the room key (HKDF-SHA256) and the member's Ed25519 public key
     encrypt         one 100-character G.2 message: AES-256-GCM seal + the HMAC-SHA256 commitment
     decrypt         one stored record opened and its commitment re-checked
     read 100        (chat/msgs) on a 100-message room the session has not read before (every record decrypted)
     reread 100      the same room again, its root key unchanged (the session's parse and decrypt caches)
                     The room is a /deref answer as the broker serves it: the chat state of blocks (genesis of the
                     spec, its public log the 100 records) written by eve.net.pages — the root slot and every page.
                     (Until 2026-10-24 the fixture was the retired JSON root_blob, which read-state never reads: both
                     rows timed an empty genesis room. The deftest pins that the view reads all 100.)
     sign claim      a post-message claim built over two state digests (stub, fixed) and signed (Ed25519)
     ballot          a vote's ballot: blinded, the issuer's blind signature (the broker's half, here too), finished
                     (RSAPBSSA-SHA384, the rsa-vectors key)
   SYNTHETIC FIXTURES: seeds derived from names, a SHA-256 counter stream for randomness, the issuer's key fixed."
  (:refer-clojure :exclude [volatile!])
  (:require [clojure.test :refer [deftest is]]
            [eve.host :refer [volatile!]]
            [eve.aegis.ballot :as ballot]
            [eve.aegis.chat-spec :as chat-spec]
            [eve.aegis.chat.client :as c]
            [eve.aegis.chat.g2 :as g2]
            [eve.aegis.chat.records :as records]
            [eve.aegis.claims :as claims]
            [eve.aegis.rsa-vectors :as rv]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.net.pages :as pages]
            [eve.process :as process]))

(def ^:private ATOM "aegis.chat.public")

(defn- counter-random [label]
  (let [c (atom 0)]
    (fn [n] (loop [out []] (if (>= (count out) n) (subvec out 0 n) (recur (into out (crypto/sha256 (b/utf8 (str label "-" (swap! c inc)))))))))))

(defn- mean-ms [f]
  (f)
  (let [t0 (process/now-ms)]
    (loop [n 1]
      (f)
      (let [dt (- (process/now-ms) t0)]
        (if (or (>= dt 400) (>= n 200)) (/ dt (* 1.0 (inc n))) (recur (inc n)))))))

(defn- row [label f] (let [ms (mean-ms f)] (println "CHAT-BENCH" label (str ms " ms")) ms))

(defn bench! []
  (let [rnd (counter-random "chat-bench")
        secret (rnd 32)
        seed (crypto/sha256 (b/utf8 "seed:alice"))
        room (g2/open-room secret ATOM)
        text (apply str (take 100 (cycle "a message from the REPL, ")))
        enc (fn [i] (g2/encrypt-message room "alice" (str text i) {:iv (rnd 12) :id-entropy (rnd 16) :ts (+ 1700000000000 i)}))
        rec (enc 0)
        recs (mapv enc (range 100))
        spec chat-spec/spec
        spec-text (pr-str spec)
        s (c/session {:endpoint "http://127.0.0.1:1" :domain "aegis-chat" :atom-id ATOM :user "alice" :seed (b/hex seed)
                      :room-secret secret :spec-text spec-text})
        w (pages/write-pages (assoc-in (claims/genesis spec) records/PUBLIC-MESSAGES (mapv records/message-record recs)) {:salt (rnd 32)})
        d {"root" (b/to-b64 (:root w)) "root_key" (:key w)
           "pages" (into {} (map (fn [p] [(:cid p) (pages/page-b64 (:bytes p))]) (:pages w)))}
        roots {:old-root (crypto/sha256 (b/utf8 "root:before")) :new-root (crypto/sha256 (b/utf8 "root:after"))}
        br (c/branch-for spec "post-message")
        sid (claims/spec-id (b/utf8 spec-text))
        v (first (:pbrsa rv/vectors))
        sk {:n (b/unhex (:n v)) :e (b/unhex (:e v)) :d (b/unhex (:d v)) :p (b/unhex (:p v)) :q (b/unhex (:q v))}
        pk (select-keys sk [:n :e])
        issuer (ballot/make-issuer sk (crypto/sha256 (b/utf8 "seed:issuer")) ["alice"])
        info (ballot/scope-info :per-poll ATOM "p-1")]
    (row "join" (fn [] (g2/open-room secret ATOM) (crypto/ed25519-public-key seed)))
    (row "encrypt" (fn [] (enc 1)))
    (row "decrypt" (fn [] (g2/decrypt-message room rec)))
    (row "read 100" (fn [] (c/view (assoc s :cache (volatile! {})) d)))
    (let [s2 (assoc s :cache (volatile! {}))] (c/view s2 d) (row "reread 100" (fn [] (c/view s2 d))))
    (row "sign claim" (fn [] (claims/sign-claim spec sid (claims/build-claim spec (:branch br) {:record rec :principal "alice"}
                                                                             roots)
                                                seed)))
    (row "ballot" (fn [] (let [nb (ballot/new-ballot pk info rnd)
                               [_ bs] (ballot/request-ballot issuer "alice" info (:blinded nb))]
                           (ballot/finish-ballot pk info nb bs))))
    (count (:public (c/view (assoc s :cache (volatile! {})) d)))))

(deftest chat-bench (is (= 100 (bench!)) "the read rows read the 100-message room of blocks"))
