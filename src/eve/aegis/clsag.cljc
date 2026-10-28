(ns eve.aegis.clsag
  "A linkable ring signature over edwards25519 with a per-scope key image, in portable cljc (aegis's clear
   evaluation, Option 2, aegis/docs/simple-spike/REPORT.md): the structure of Monero's CLSAG — rctSigs.cpp CLSAG_Gen / verRctCLSAGSimple
   (Goodell, Noether, RandomRun, ePrint 2019/654) — with its commitment layer removed (no C, D, mu_C, C_offset: one
   key per ring member) and the key-image base moved from H_p(P_i) to H_p(scope), the event-oriented linkability of
   Liu–Wong: two signatures by one member under one scope share the key image I = x·H_p(scope); under two scopes the
   images are unlinkable (DDH). The hashes are instantiated as:
     H_s(domain, items…) = SHA-512(domain padded to 32 bytes ‖ items) mod L   (Monero: Keccak over the same layout)
     H_p(scope)          = RFC 9380 edwards25519_XMD:SHA-512_ELL2_RO_ (eve.aegis.h2c, dst \"aegis-clsag-scope-v1\")
   There are no published vectors for this instantiation (Monero's use Keccak and its own hash_to_point); the
   construction is pinned to the reference's hash layout, the hash-to-point to RFC 9380's vectors, and the signature
   to completeness, tamper and linkability tests (eve/test/eve/aegis/clsag_test.cljc).

   Ring members are Ed25519 public keys (RFC 8032: P = x·B for x the clamped SHA-512 half of a seed), so a group
   roster of Ed25519 keys is a ring as it stands.

     (secret-scalar seed)                 x mod L for an Ed25519 seed
     (key-image seed scope)               I, 32 bytes
     (sign {:msg :ring :seed :index :scope :random})  {:c1 :s [...] :I}; :random is (fn [n] n random bytes)
     (verify {:msg :ring :scope :sig})    boolean

   Verification costs 2 double scalar multiplications per ring member, through the crypto feature's
   ed25519-mul-add / ed25519-scalarmult rows (a host's native big integers where its adapter has them, eve.net.ed25519's
   limbs otherwise); the key image is checked to lie in the prime-order subgroup (L·I = 0) so a torsion component
   cannot forge a fresh image."
  (:require [eve.aegis.h2c :as h2c]
            [eve.crypto :as crypto]
            [eve.net.bytes :as b]
            [eve.net.ed25519 :as ed]))

(def SCOPE-DST (b/utf8 "aegis-clsag-scope-v1"))
(def ^:private DOMAIN-AGG (b/utf8 "CLSAG_agg_0"))
(def ^:private DOMAIN-ROUND (b/utf8 "CLSAG_round"))

(defn- refuse [msg data] (throw (ex-info (str "clsag: " msg) data)))

;; ── scalars mod L (32 little-endian bytes); eve.net.ed25519/mod-l reduces 64 signed radix-2^8 digits

(defn- sc-reduce "a 32-byte scalar reduced mod L" [s] (ed/mod-l (into (vec s) (repeat 32 0))))

(def ^:private L-BE "L, 32 big-endian bytes" (vec (rseq ed/L)))

(defn sc-mul
  "a·b mod L, through the crypto feature's modmul (the host's own big integers; a 1,024-term product reduced here took
  28.8 ms on ValueScript — aegis/docs/chat-peer/U8c-REPORT.md)"
  [a b]
  (vec (rseq (vec (crypto/modmul (vec (rseq (vec a))) (vec (rseq (vec b))) L-BE)))))

(defn sc-add [a b] (ed/mod-l (into (mapv + a b) (repeat 32 0))))

(defn sc-sub "a − b mod L" [a b] (ed/mod-l (into (mapv - a b) (repeat 32 0))))

(defn hash-scalar
  "H_s: SHA-512 (the crypto feature's row) over the 32-byte domain and the items, as a scalar mod L"
  [domain items]
  (let [domain (into (vec domain) (repeat (- 32 (count domain)) 0))]
    (ed/mod-l (vec (crypto/sha512 (reduce into domain items))))))

(defn random-scalar "a scalar from 64 random bytes" [bs64] (ed/mod-l (vec bs64)))

;; ── points

;; the identity's encoding as a literal (y = 1, x = 0), held to its packing by clsag_test: ValueScript's compiler
;; evaluates a top-level def's init when it compiles it, and a packing inverts a field element there
(def IDENTITY (into [1] (repeat 31 0)))
;; B's encoding as a literal (RFC 8032 §5.1: y = 4/5, x even), held to [1]B by clsag_test: ValueScript's compiler
;; evaluates a top-level def's init when it compiles it, and a scalar multiplication is too costly there
(def BASE (into [0x58] (repeat 31 0x66)))
(defn base-computed "[1]B, encoded" [] (ed/pack (ed/scalarbase (into [1] (repeat 31 0)))))
(def ^:private L-SCALAR (vec ed/L))

(defn- in-subgroup? "L·P = 0, for P an encoding" [p] (= IDENTITY (crypto/ed25519-scalarmult L-SCALAR p)))

(defn scope-point
  "H_p(scope), the key-image base of a scope, as a 32-byte encoding"
  [scope]
  (h2c/hash-to-point (vec scope) SCOPE-DST))

(defn secret-scalar
  "x mod L of an Ed25519 seed (RFC 8032 §5.1.5's clamped scalar: eve.net.ed25519/seed-hash's, its SHA-512 the crypto
   feature's row)"
  [seed]
  (let [seed (vec seed)]
    (when-not (= 32 (count seed))
      (throw (ex-info (str "ed25519: a seed is 32 bytes, not " (count seed))
                      {:count (count seed)})))
    (let [d (vec (crypto/sha512 seed))]
      (sc-reduce (-> (subvec d 0 32)
                     (assoc 0 (- (nth d 0) (mod (nth d 0) 8)))
                     (assoc 31 (+ 64 (mod (nth d 31) 64))))))))

(defn key-image "I = x·H_p(scope), 32 bytes" [seed scope]
  (crypto/ed25519-scalarmult (secret-scalar seed) (scope-point scope)))

(defn- mu "the aggregation scalar: H_s(CLSAG_agg_0, P_1..P_n, H_p(scope), I)" [ring hp i]
  (hash-scalar DOMAIN-AGG (-> (vec ring) (conj hp) (conj i))))

(defn- round-hash "c = H_s(CLSAG_round, P_1..P_n, H_p(scope), msg, L, R)" [ring hp msg L R]
  (hash-scalar DOMAIN-ROUND (-> (vec ring) (conj hp) (conj (vec msg)) (conj L) (conj R))))

(defn- lr "L = s·B + c·μ·P, R = s·H + c·μ·I, both packed — nil when P or I is no point" [s c-mu P hp I]
  (let [L (crypto/ed25519-mul-add s BASE c-mu P)
        R (crypto/ed25519-mul-add s hp c-mu I)]
    (when (and L R) [L R])))

(defn sign
  "{:c1 :s :I}: the ring (32-byte public keys), the signer's seed at :index, the scope, :random (fn [n]) for the nonce
   and the n−1 decoy responses"
  [{:keys [msg ring seed index scope random]}]
  (let [ring (mapv vec ring) n (count ring)
        _ (when-not (< -1 index n) (refuse "index out of range" {:index index :n n}))
        x (secret-scalar seed)
        ;; the crypto feature's public key (the kernel's Ed25519 on ValueScript, where eve.net.ed25519's scalar walk over
        ;; limbs does not fit a program's slab)
        _ (when-not (= (nth ring index) (vec (crypto/ed25519-public-key seed))) (refuse "the seed's key is not ring[index]" {}))
        hp (scope-point scope)
        I (crypto/ed25519-scalarmult x hp)
        mu* (mu ring hp I)
        a (random-scalar (random 64))
        c-next (round-hash ring hp msg (crypto/ed25519-scalarmult a BASE) (crypto/ed25519-scalarmult a hp))]
    ;; walk i = l+1, l+2, … around the ring to l, a decoy response per step
    (loop [i (mod (inc index) n) c c-next s (vec (repeat n nil)) c1 (when (zero? (mod (inc index) n)) c-next)]
      (if (= i index)
        {:c1 c1 :s (assoc s index (sc-sub a (sc-mul c (sc-mul mu* x)))) :I I}
        (let [si (random-scalar (random 64))
              c-mu (sc-mul c mu*)
              [L R] (or (lr si c-mu (nth ring i) hp I) (refuse "ring[i] is not a point" {:i i}))
              c' (round-hash ring hp msg L R)
              i' (mod (inc i) n)]
          (recur i' c' (assoc s i si) (if (zero? i') c' c1)))))))

(defn verify
  "is sig a signature of msg by some member of the ring under the scope (rctSigs.cpp verRctCLSAGSimple without the
   commitment layer; the key image must be a non-identity point of the prime-order subgroup)"
  [{:keys [msg ring scope sig]}]
  (let [{:keys [c1 s I]} sig
        ring (mapv vec ring) n (count ring)]
    (and (pos? n)
         (= n (count s))
         (every? (fn [si] (and (= 32 (count si)) (ed/below-l? (vec si)))) s)
         (and (= 32 (count c1)) (ed/below-l? (vec c1)))
         (= 32 (count I))
         (not= IDENTITY (vec I))
         (in-subgroup? (vec I))
         (let [hp (scope-point scope)
               mu* (mu ring hp (vec I))
               c (loop [i 0 c (vec c1)]
                   (cond (nil? c) nil
                         (= i n) c
                         :else (let [[L R] (lr (vec (nth s i)) (sc-mul c mu*) (nth ring i) hp (vec I))]
                                 (recur (inc i) (when L (round-hash ring hp msg L R))))))]
           (= c (vec c1))))))
