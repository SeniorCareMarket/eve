(ns eve.crypto
  "eve's crypto feature (adapters/README.md) for the :teavm seat.
   SHA-256 and AES-256-CTR run over in-module classes (Sha256, Aes256 —
   TeaVM's classlib has no MessageDigest or Cipher); HMAC-SHA256 is
   composed over that SHA-256 (RFC 2104, the portable construction, so
   this adapter answers what eve.net.portable-crypto answers);
   XXH3-128 is delegated to eve.net.xxh3, as the JVM and Node adapters
   delegate it — neither platform has one either; random bytes are
   eve.process's, over that adapter's entropy host import (the JVM
   adapter's own route). Bytes are vectors of 0..255 in and out."
  (:require [eve.net.xxh3 :as xxh3]
            [eve.process :as process])
  (:import [Aes256]
           [RegionBytes]
           [Sha256]))

(defn- arr ^bytes [bs]
  (let [^bytes a (byte-array (count bs))]
    (dotimes [i (count bs)]
      (RegionBytes/put a i (int (nth bs i))))
    a))

(defn- vec* [^bytes a]
  (let [n (alength a)]
    (loop [i 0 acc []]
      (if (>= i n)
        acc
        (recur (inc i) (conj acc (RegionBytes/get a i)))))))

(defn sha256 "the 32-byte digest" [bs]
  (vec* (Sha256/digest (arr bs))))

(defn hmac-sha256 "RFC 2104 over SHA-256" [key msg]
  (let [k (vec key)
        k (if (> (count k) 64) (sha256 k) k)
        k (into k (repeat (- 64 (count k)) 0))]
    (sha256 (into (mapv (fn [x] (bit-xor x 0x5c)) k)
                  (sha256 (into (mapv (fn [x] (bit-xor x 0x36)) k) msg))))))

(defn aes256-ctr "data XOR the AES-256-CTR keystream from the 16-byte iv under a 32-byte key" [key iv data]
  (when-not (= 32 (count key))
    (throw (ex-info "eve.crypto/aes256-ctr: the key is 32 bytes" {:count (count key)})))
  (when-not (= 16 (count iv))
    (throw (ex-info "eve.crypto/aes256-ctr: the iv is 16 bytes" {:count (count iv)})))
  (vec* (Aes256/ctr (arr key) (arr iv) (arr data))))

(defn xxh3-128 "the 16-byte XXH3-128 digest, big-endian hi then lo (portable: this seat has none either)" [bs]
  (xxh3/xxh3-128 bs))

(defn random-bytes "n bytes of the operating system's entropy — eve.process/random-bytes, over its entropy import" [n]
  (process/random-bytes n))
