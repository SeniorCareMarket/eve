(ns eve.crypto
  "eve's crypto feature (adapters/README.md) on ValueScript WITH the kernel's standalone Ed25519 module: crypto/vs's rows,
   except ed25519-public-key / ed25519-sign / ed25519-verify, sha512, ed25519-scalarmult, ed25519-mul-add and
   ed25519-map-to-point, which run in the kernel's own native code
   (vs/src/vs/kernel/wasm/ed25519.vs over sha512.vs, through W5 :require-wasm of the standalone module ed25519.wasm) —
   the module's memory, no slab value per field operation. Measured (aegis-simple-mainline lane, this box): crypto/vs's
   portable public key took 7.2 s on VS and its signature exhausted the 2 GiB slab (a compiled program frees only what
   an atom's write replaces; eve.net.ed25519's 16-bit-limb arithmetic builds vectors per operation).

   SELECTING IT. A program puts this root FIRST on VS_CLJ_ROOTS — the launcher reads VS_CLJ_ROOTS before eve's own roots
   (vs/scripts/vs-wasm-leg lib_roots), so this eve.crypto shadows crypto/vs's — and builds ed25519.wasm where
   :require-wasm reads it at compile time (the source's directory or the working directory):
     cd vs && bash scripts/kernel-module-build --out <program-dir> ed25519
   The aegis broker and chat client do (src/aegis/hosts/relay.py build, src/aegis/chat/build_client.py). hosts.edn's :vs
   row keeps crypto/vs, which needs no module.

   THE MODULE'S MEMORY (1 page, grown by mem_ensure), laid out as src/aegis/ed25519.vs lays it:
     1024  W   4352 B  the operation's work region      5376  KEY  32 B  the seed or the public key
     5408  SIG   64 B  the signature to verify          5472  OUT  64 B  the public key or the signature made
     8192  MSG    n B  the message                      5536  PT2  32 B  mul-add's second point
     5632  S    2048 B  map-to-point's scratch (to 7680)
   scalarmult: the scalar at KEY, the point at SIG · mul-add: a at KEY, P at SIG, b at SIG+32, Q at PT2 · sha512: the
   message at MSG, the digest at OUT, its state in W. The group rows answer nil for an encoding that is no point, as
   eve.net.edwards's do (the module's ed25519_scalarmult / ed25519_mul_add: the same decoding, all 256 scalar bits;
   MEASURED on wasmtime, a ring of 2: crypto/vs's BigInt mul-add 55 ms, scalarmult 28.5 ms; the portable SHA-512 of
   200 B 67 ms — aegis/docs/chat-peer/U8c-REPORT.md)
   Every byte an export reads is written by the call first (rule 12: no zeroing, no buffer held aside); a seed passes
   through the module's memory and is forgotten there like every other byte. Verify is RFC 8032 §5.1.7 cofactorless
   (S ≥ L, a non-canonical key or R refused) and variable-time (public data); public-key and sign follow TweetNaCl's
   branch-free structure. Bytes are vectors of 0..255 in and out."
  (:require [eve.net.bigops :as bo]
            [eve.net.edwards :as edw]
            [eve.net.portable-crypto :as p]
            [vs.net.bytes :as vb])
  (:require-wasm [eve.crypto.ed25519-module :as em :from "ed25519.wasm"
                  :bind {em.ed25519_public_key [i32 i32 i32 -> none]
                         em.ed25519_sign [i32 i32 i32 i32 i32 -> none]
                         em.ed25519_verify [i32 i32 i32 i32 i32 -> i32]
                         em.ed25519_scalarmult [i32 i32 i32 i32 -> i32]
                         em.ed25519_mul_add [i32 i32 i32 i32 i32 i32 -> i32]
                         em.sha512 [i32 i32 i32 i32 -> none]
                         em.ed25519_map_to_point [i32 i32 i32 i32 i32 -> none]
                         em.mem_ensure [i32 -> i32]}]))

(def ^:private ED-W 1024)
(def ^:private ED-KEY 5376)
(def ^:private ED-SIG 5408)
(def ^:private ED-OUT 5472)
(def ^:private ED-MSG 8192)
(def ^:private ED-PT2 5536)
(def ^:private ED-S 5632)

(defn- put! "the bytes bs (0..255) into the module's memory at off" [off bs]
  (let [n (count bs)]
    (loop [i 0]
      (when (< i n)
        (em/mem-write-u8! (+ off i) (nth bs i))
        (recur (inc i))))))

(defn- bytes-at "n bytes of the module's memory at off, a vector" [off n]
  (loop [i 0 out (transient [])]
    (if (< i n) (recur (inc i) (conj! out (em/mem-read-u8 (+ off i)))) (persistent! out))))

(defn- stage-msg! "the message at MSG; its byte count" [msg]
  (let [m (vec msg) n (count m)]
    (when (zero? (em/mem_ensure (+ ED-MSG n)))
      (throw (ex-info (str "eve.crypto: the Ed25519 module's memory cannot hold a " n "-byte message") {:bytes n})))
    (put! ED-MSG m)
    n))

(defn sha256 "the 32-byte digest" [bs] (vb/ints (vs.core/rt-crypto-sha256 (vb/of-ints bs))))
(defn hmac-sha256
  "RFC 2104 over this adapter's sha256 (the kernel's): a key past the 64-byte block hashed first, then
   H((K ⊕ opad) ‖ H((K ⊕ ipad) ‖ msg)) — MEASURED 2026-10-26, the portable HMAC (eve.net.sha256 in cljc) was 95 ms of a
   chat post's client (HKDF's subkeys, aegis/docs/chat-peer/U8c-REPORT.md §6)"
  [key msg]
  (let [k (vec key)
        k (if (> (count k) 64) (sha256 k) k)
        k (into k (repeat (- 64 (count k)) 0))]
    (sha256 (into (mapv (fn [b] (bit-xor b 0x5c)) k)
                  (sha256 (into (mapv (fn [b] (bit-xor b 0x36)) k) (vec msg)))))))
(defn aes256-ctr "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key" [key iv data]
  (p/aes256-ctr key iv data))
(defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo" [bs] (p/xxh3-128 bs))
(defn random-bytes "refused: a compiled VS program has no entropy source" [n]
  (throw (ex-info "eve.crypto/random-bytes: a compiled ValueScript program has no entropy source (vs/docs/abi.md)"
                  {:n n})))

(defn ed25519-public-key "the 32-byte public key of a 32-byte secret seed (RFC 8032 §5.1.5)" [seed]
  (let [s (vec seed)]
    (when (not= 32 (count s)) (throw (ex-info "eve.crypto/ed25519-public-key: the seed is 32 bytes" {:count (count s)})))
    (put! ED-KEY s)
    (em/ed25519_public_key ED-KEY ED-OUT ED-W)
    (bytes-at ED-OUT 32)))

(defn ed25519-sign "the 64-byte signature of the message under a 32-byte secret seed (RFC 8032 §5.1.6)" [seed msg]
  (let [s (vec seed)]
    (when (not= 32 (count s)) (throw (ex-info "eve.crypto/ed25519-sign: the seed is 32 bytes" {:count (count s)})))
    (let [n (stage-msg! msg)]
      (put! ED-KEY s)
      (em/ed25519_sign ED-KEY ED-MSG n ED-OUT ED-W)
      (bytes-at ED-OUT 64))))

(defn ed25519-verify "is sig a valid signature of the message under the 32-byte public key (RFC 8032 §5.1.7)" [pub msg sig]
  (let [pk (vec pub) sg (vec sig)]
    (if (or (not= 32 (count pk)) (not= 64 (count sg)))
      false
      (let [n (stage-msg! msg)]
        (put! ED-KEY pk)
        (put! ED-SIG sg)
        (= 1 (em/ed25519_verify ED-KEY ED-MSG n ED-SIG ED-W))))))

;; ── the aegis clear-evaluation rows, as crypto/vs has them: SHA-384 portable, the big-integer rows and X25519 over VS's
;; own BigInt tower (+' -' *' quot mod) through eve.net.bigops / eve.net.edwards; the edwards25519 group rows in the module
(def ^:private OPS
  (bo/complete
   {:int (fn [n] (bigint n))
    :from-be (fn [bs] (reduce (fn [acc b] (+' (*' acc 256) b)) (bigint 0) bs))
    :to-be (fn [x len] (bo/to-be-by {:int (fn [n] (bigint n)) :mod mod :quot quot} (fn [d] (long d)) x len))
    :add (fn [a b] (+' a b)) :sub (fn [a b] (-' a b)) :mul (fn [a b] (*' a b)) :quot quot :mod mod
    :even? (fn [a] (zero? (mod a 2))) :zero? zero? :eq =}))
(def ^:private ED (edw/make OPS))
(defn sha384 "the 48-byte SHA-384 digest" [bs] (p/sha384 bs))
(defn modpow "base^exp mod n over big-endian bytes; the result is as long as n" [base exp n] (bo/modpow-bytes OPS base exp n))
(defn modmul "a·b mod n over big-endian bytes; the result is as long as n" [a b n] (bo/modmul-bytes OPS a b n))
(defn modinv "a^-1 mod n over big-endian bytes, or nil when gcd(a, n) ≠ 1" [a n] (bo/modinv-bytes OPS a n))
(defn ed25519-scalarmult
  "the encoding of [s]P: s 32 little-endian bytes (all 256 bits), P a 32-byte encoding; nil when P is no point"
  [s p]
  (let [s (vec s)
        q (vec p)]
    (when (= 32 (count q))
      (put! ED-KEY (subvec s 0 32))
      (put! ED-SIG q)
      (when (= 1 (em/ed25519_scalarmult ED-KEY ED-SIG ED-OUT ED-W))
        (bytes-at ED-OUT 32)))))
(defn ed25519-mul-add
  "the encoding of [a]P + [b]Q: a and b 32 little-endian bytes (all 256 bits), P and Q 32-byte encodings; nil when
   either is no point"
  [a p b q]
  (let [a (vec a)
        p (vec p)
        b (vec b)
        q (vec q)]
    (when (and (= 32 (count p)) (= 32 (count q)))
      (put! ED-KEY (subvec a 0 32))
      (put! ED-SIG p)
      (put! (+ ED-SIG 32) (subvec b 0 32))
      (put! ED-PT2 q)
      (when (= 1
               (em/ed25519_mul_add ED-KEY ED-SIG (+ ED-SIG 32) ED-PT2 ED-OUT
                                   ED-W))
        (bytes-at ED-OUT 32)))))
(defn x25519 "X25519 (RFC 7748): the 32-byte u-coordinate of [k]u, k and u 32 little-endian bytes" [k u] ((:x25519 ED) k u))
(defn sha512
  "the 64-byte SHA-512 digest (the module's sha512, its state in the work region)"
  [bs]
  (let [n (stage-msg! bs)]
    (em/sha512 ED-MSG n ED-OUT ED-W)
    (bytes-at ED-OUT 64)))
(defn aes256-gcm-seal "AES-256-GCM (portable)" [key iv aad pt] (p/aes256-gcm-seal key iv aad pt))
(defn aes256-gcm-open "the plaintext of AES-256-GCM ciphertext ‖ tag, or nil (portable)" [key iv aad sealed] (p/aes256-gcm-open key iv aad sealed))

(defn ed25519-map-to-point
  "the 32-byte encoding of clear_cofactor(map_to_curve(u0) + map_to_curve(u1)) on edwards25519 (RFC 9380 G.2.1–G.2.2),
   u0 and u1 32 little-endian bytes (bit 255 dropped) — the module's ed25519_map_to_point: the straight-line map over
   its own field, projective, no inversion until the encoding (MEASURED 2026-10-26 on wasmtime: the limb map with
   BigInt powers was ~80 ms of each CLSAG signature and verification, aegis/docs/chat-peer/U8c-REPORT.md)"
  [u0 u1]
  (put! ED-KEY (subvec (vec u0) 0 32))
  (put! ED-SIG (subvec (vec u1) 0 32))
  (em/ed25519_map_to_point ED-KEY ED-SIG ED-OUT ED-W ED-S)
  (bytes-at ED-OUT 32))
