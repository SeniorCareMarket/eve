(ns eve.net.blobs
  "Blocks to and from the object store as sans-IO steps (eve.net.run), the protocol of vs.net.blob-io in portable cljc:
   every URL comes from the broker's /presign (B-creds, WIRE.md §8 — the client holds no store credential), every blob
   goes DIRECT to the store (never a /blob/<sha> endpoint), and THE ADDRESS IS THE CHECK
   (WIRE.md §7): a fetched blob's bytes hash to its key — or, for a set root (a map-root image, tag 0xED), to its key
   XOR 0xEE…. Uploads HEAD first and skip a blob the store holds (the dedup of P2).

   Config: the wire's {:endpoint :domain-id :api-key}, and optional :gak, the domain's 32-byte group key: with it every blob is
   stored ENCRYPTED (G4, eve.net.crypto) under the block key HKDF(gak, nonce = the first 16 bytes of the blob's key,
   page 0, slot 0) — any reader holding the gak derives it from the key alone; equal plaintexts encrypt equally, as
   their keys already show — and decrypted on fetch before the address check (the plaintext's SHA is the address)."
  (:require [eve.net.run :as r]
            [eve.net.wire :as wire]
            [eve.net.bytes :as b]
            [eve.net.crypto :as crypto]
            [eve.crypto :as prim]))

;; the digest is the host's crypto feature (eve.crypto): a faster one is a native adapter, never a config key
(defn- digest [_cfg bs] (prim/sha256 bs))

(defn- ctx-of [cfg k] {:gak (:gak cfg) :nonce (subvec (b/unhex k) 0 16) :page-id 0 :slot-idx 0})

(defn- seal "the bytes stored for blob k: encrypted when cfg has a gak" [cfg k bs]
  (if (:gak cfg) (crypto/encrypt-blob (ctx-of cfg k) bs) (vec bs)))

(defn- unseal "the plaintext of stored bytes" [cfg k bs]
  (if (:gak cfg) (crypto/decrypt-blob (ctx-of cfg k) bs) bs))

(defn key-of
  "the key of a blob's bytes: the hex SHA-256 — XOR 0xEE… when `set-root?`"
  [cfg bs set-root?]
  (b/hex (if set-root? (mapv (fn [x] (bit-xor x 0xEE)) (digest cfg bs)) (digest cfg bs))))

(defn address-ok?
  "do the bytes answer to the key"
  [cfg k bs]
  (let [d (digest cfg bs)]
    (or (= k (b/hex d))
        (and (pos? (count bs)) (= 0xED (nth bs 0)) (= k (b/hex (mapv (fn [x] (bit-xor x 0xEE)) d)))))))

(def PARALLEL "requests sent together — 32 at a time" 32)

(defn- in-batches
  "one requests step per PARALLEL-wide chunk of xs, (reqs-of chunk) its requests, folding (f acc chunk answers)"
  [xs reqs-of f init]
  (letfn [(go [left acc]
            (if (empty? left)
              (r/done acc)
              (let [chunk (vec (take PARALLEL left))]
                (r/requests (reqs-of chunk) (fn [resps] (go (drop PARALLEL left) (f acc chunk resps)))))))]
    (go (seq xs) init)))

(defn- got
  "the plaintext a GET of key k answered, checked against k; throws on a failed GET or a mismatch"
  [cfg k resp]
  (let [s (:status resp)]
    (when (or (contains? resp :error) (nil? s) (< s 200) (>= s 300))
      (throw (ex-info (str "Blob fetch failed: " (subs k 0 16) "… -> " (or s (:error resp))) {:status s :sha k})))
    (let [bs (unseal cfg k (b/from-b64 (:body resp)))]
      (if (address-ok? cfg k bs)
        bs
        (throw (ex-info (str "blob hash mismatch: asked for " k ", received " (b/hex (digest cfg bs))) {:sha k}))))))

(defn fetch-blocks
  "{key bytes} of every key: one presign call per 100 keys, then the presigned GETs, PARALLEL at a time, each checked
   against its key"
  [cfg ks]
  (let [ks (vec (distinct ks))]
    (r/then (wire/presign cfg :read ks)
            (fn [urls]
              (doseq [k ks]
                (when (nil? (get urls k)) (throw (ex-info (str "no presigned URL for " (subs k 0 16) "…") {:sha k}))))
              (in-batches ks
                          (fn [chunk] (mapv (fn [k] {:method "GET" :url (get urls k) :headers {} :body nil :as :b64}) chunk))
                          (fn [acc chunk resps] (reduce (fn [acc [k resp]] (assoc acc k (got cfg k resp))) acc (map vector chunk resps)))
                          {})))))

(defn- head-missing
  "the keys whose presigned HEAD does not answer 200, PARALLEL HEADs at a time"
  [heads ks]
  (in-batches ks
              (fn [chunk] (mapv (fn [k] {:method "HEAD" :url (get heads k) :headers {} :body nil}) chunk))
              (fn [missing chunk resps]
                (into missing (keep (fn [[k resp]] (when-not (= 200 (:status resp)) k)) (map vector chunk resps))))
              []))

(defn- put-all
  "a presigned PUT of each missing key's bytes (sealed: encrypted under a gak), PARALLEL at a time; throws on a
   non-2xx"
  [cfg puts blocks missing]
  (r/fmap (in-batches missing
                      (fn [chunk] (mapv (fn [k] {:method "PUT" :url (get puts k) :headers {"Content-Type" "application/octet-stream"}
                                                 :body (b/to-b64 (seal cfg k (get blocks k))) :body-as :b64})
                                        chunk))
                      (fn [_ chunk resps]
                        (doseq [[k resp] (map vector chunk resps)]
                          (let [s (:status resp)]
                            (when (or (nil? s) (< s 200) (>= s 300))
                              (throw (ex-info (str "Blob upload failed: " (subs k 0 16) "… -> " (or s (:error resp)))
                                              {:status s :sha k})))))
                        nil)
                      nil)
          (fn [_] missing)))

(defn upload-blocks
  "PUTs every {key bytes} of blocks the store does not hold yet (a presigned HEAD each first; 200 skips), and every one
   of fresh — blobs no store holds yet (a swap's graft: its epoch and roots name the swap) — with no HEAD: the keys PUT"
  ([cfg blocks] (upload-blocks cfg blocks {}))
  ([cfg blocks fresh]
   (let [ks (vec (keys blocks))
         all (merge blocks fresh)
         put! (fn [missing]
                (let [ps (into (vec missing) (remove (set missing) (keys fresh)))]
                  (if (empty? ps)
                    (r/done [])
                    (r/then (wire/presign cfg :write ps) (fn [puts] (put-all cfg puts all ps))))))]
     (if (empty? ks)
       (put! [])
       (r/then (wire/presign cfg :head ks)
               (fn [heads] (r/then (head-missing heads ks) put!)))))))
