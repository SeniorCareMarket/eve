(ns eve.keyring
  "eve's at-rest key ring (EVM-11d): a domain's slab blocks encrypted on disk under a key ring, written once for every
   eve host over eve.crypto's AES-256-CTR and HMAC-SHA256 (HKDF, eve.net.crypto). It replaces the kernel's key ring
   (VS's src/vs/kernel/wasm/enc.vs) and its in-memory keystream (ks.vs).

   THE RECORD — byte-identical to enc.vs's (crypto.js's GAK record and EEK ring), in the domain's {base}.keyring file:
     magic@0 'ENCR' · version@4 · key_status@8 (0 uninitialised · 1 auto · 2 user · 3 user, not stored) ·
     algorithm@12 (1 = AES-256-CTR) · gak[32]@16 (zero when not stored) · fingerprint[16]@48 ·
     eek_head@64 · eek_size@68 · the epoch-key ring@72: 64 entries × 60 B (epoch@0 status@4 readers@8 key[32]@12
     iv[12]@44 created_ts@56; status 0 free · 1 active · 2 draining · 3 shredded) — and eve's one addition, in bytes
     the kernel never wrote: the domain's salt[16]@3912.

   THE KEYSTREAM — what a domain's files hold encrypted: every block byte a slab I/O reads or writes (a node, a key, a
   value, an eve type's fields), XORed with AES-256-CTR under the class key HKDF-SHA256(ikm = the GAK, salt = the
   domain's salt, info = \"eve.keyring\" ‖ class, 32 bytes) from the IV class:u32BE ‖ block:u32BE ‖ counter:u64BE, the
   counter the block-relative offset / 16. Metadata stays plaintext, as the kernel keeps it (ks.vs's address classes):
   the slab headers and bitmaps, the overflow class's descriptors, the root file (its CAS words, the atom table). A
   block's keystream is a function of the block's place, so a block rewritten in place reuses it: CTR confidentiality
   against a single copy of the files, no integrity — the kernel's keystream exactly (epoch 0).

   encrypted-sio wraps a domain's slab I/O; open-encrypted-domain! opens one with its ring. Where the host's process
   slab is the domain and reads its blocks itself (ClojureScript's module slabs, through DataViews — eve's types'
   accessors among them), the slab takes the keystream instead (alloc/keystream-domain-sio), the same bytes on disk.
   Direct region access to an encrypted domain (alloc/-sio-mem-region: eve.array's element views, a typed array's
   block) would read ciphertext, and is refused."
  (:refer-clojure :exclude [aget aset alength unchecked-byte load bytes])
  (:require [eve.host :refer [aget aset alength unchecked-byte bytes]]
            [eve.encoding :as enc]
            [eve.mem :as mem]
            [eve.region :as region]
            [eve.crypto :as prim]
            [eve.net.crypto :as nc]
            [eve.int32 :as i32]
            [eve.process :as proc]
            [eve.deftype-proto.alloc :as alloc]))

;;-----------------------------------------------------------------------------
;; The record (enc.vs's offsets)
;;-----------------------------------------------------------------------------

(def ^:const MAGIC_VAL 1162756946)   ; 0x454E4352 'ENCR'
(def ^:const ENC_MAGIC 0)
(def ^:const ENC_VERSION 4)
(def ^:const ENC_KEY_STATUS 8)
(def ^:const ENC_KEY_ALGORITHM 12)
(def ^:const ENC_GAK_BYTES 16)
(def ^:const ENC_GAK_FINGERPRINT 48)
(def ^:const ENC_EEK_RING_HEAD 64)
(def ^:const ENC_EEK_RING_SIZE 68)
(def ^:const ENC_EEK_RING_START 72)
(def ^:const EEK_ENTRY_SIZE 60)
(def ^:const EEK_EPOCH 0)
(def ^:const EEK_STATUS 4)
(def ^:const EEK_READER_COUNT 8)
(def ^:const EEK_KEY_BYTES 12)
(def ^:const EEK_IV_BYTES 44)
(def ^:const EEK_CREATED_TS 56)
(def ^:const EEK_MAX_ENTRIES 64)
(def ^:const EEK_FREE 0)
(def ^:const EEK_ACTIVE 1)
(def ^:const EEK_DRAINING 2)
(def ^:const EEK_SHREDDED 3)
(def ^:const KEY_STATUS_AUTO 1)
(def ^:const KEY_STATUS_USER 2)
(def ^:const KEY_STATUS_USER_NO_STORE 3)
(def ^:const KEY_ALG_AES_256_CTR 1)
;; eve's: the domain's keystream salt, past the kernel's ring (72 + 64 × 60)
(def ^:const ENC_DOMAIN_SALT 3912)
(def ^:const RECORD_BYTES 4096)

(defn- load [r off] (mem/-load-i32 r off))
(defn- store! [r off v] (mem/-store-i32! r off (i32/i32 v)))
(defn- cas!
  "Compare-and-exchange the i32 at off (under the region's file lock on a
   lock-only host): the old value."
  [r off expected desired]
  (mem/-with-file-lock r off (fn [] (mem/-cas-i32! r off (i32/i32 expected) (i32/i32 desired)))))
(defn- add! [r off d] (mem/-with-file-lock r off (fn [] (mem/-add-i32! r off d))))

(defn- read-vec [r off n]
  (let [b (mem/-read-bytes r off n)]
    (mapv #(bit-and (aget b %) 0xFF) (range n))))

(defn- write-vec! [r off v] (mem/-write-bytes! r off (enc/bytes-of v)))

(defn- le4 [x] (mapv #(bit-and (bit-shift-right x (* 8 %)) 0xFF) (range 4)))

(defn fingerprint
  "The 16-byte fingerprint that guards a user key (crypto.js _fingerprint, enc.vs
   enc_fingerprint): two FNV-1a lanes over the key, forward and backward —
   h1, h2, h1 xor h2, h1 × h2 as i32 little-endian."
  [key]
  (let [n (count key)
        [h1 h2] (loop [i 0 h1 (i32/i32 0x811c9dc5) h2 16777619]
                  (if (< i n)
                    (recur (inc i)
                           (i32/imul (bit-xor h1 (nth key i)) 16777619)
                           (i32/imul (bit-xor h2 (nth key (- n 1 i))) (i32/i32 0x811c9dc5)))
                    [h1 h2]))]
    (vec (mapcat le4 [h1 h2 (i32/i32 (bit-xor h1 h2)) (i32/imul h1 h2)]))))

(defn init!
  "Initialise ring r with the 32-byte GAK and a key status (1 auto, 2 user,
   3 user not stored — the GAK's bytes stay zero, only its fingerprint is
   kept): 1 when this call initialised it, 0 when it already was (read it with
   read-gak), -1 for a bad status. The magic is claimed by CAS, so one
   initialiser wins; the key status is published last."
  [r gak status]
  (cond
    (not (contains? #{KEY_STATUS_AUTO KEY_STATUS_USER KEY_STATUS_USER_NO_STORE} status)) -1
    (not= 0 (cas! r ENC_MAGIC 0 MAGIC_VAL)) 0
    :else
    (do (store! r ENC_VERSION 1)
        (store! r ENC_KEY_ALGORITHM KEY_ALG_AES_256_CTR)
        (write-vec! r ENC_GAK_BYTES (if (= status KEY_STATUS_USER_NO_STORE) (vec (repeat 32 0)) (vec gak)))
        (write-vec! r ENC_GAK_FINGERPRINT (fingerprint gak))
        (store! r ENC_EEK_RING_HEAD -1)
        (store! r ENC_EEK_RING_SIZE 0)
        (dotimes [i EEK_MAX_ENTRIES]
          (let [e (+ ENC_EEK_RING_START (* i EEK_ENTRY_SIZE))]
            (store! r (+ e EEK_STATUS) EEK_FREE)
            (store! r (+ e EEK_READER_COUNT) 0)))
        (write-vec! r ENC_DOMAIN_SALT (proc/random-bytes 16))
        (store! r ENC_KEY_STATUS status)
        1)))

(defn status
  "The ring's key status (1/2/3), 0 while a peer is still initialising, -1 when
   there is no record."
  [r]
  (if (not= MAGIC_VAL (load r ENC_MAGIC)) -1 (load r ENC_KEY_STATUS)))

(defn read-gak
  "The domain's 32-byte GAK, or nil when it is not encrypted. A user ring needs
   its key: absent, :user-key-required; not the key the ring was made with,
   :fingerprint-mismatch (each an ex-info of that :type)."
  [r user-key]
  (let [st (status r)]
    (cond
      (<= st 0) nil
      (= st KEY_STATUS_AUTO) (read-vec r ENC_GAK_BYTES 32)
      (nil? user-key)
      (throw (ex-info "eve.keyring: this domain is encrypted under a user key, and none was given"
                      {:type :user-key-required}))
      (not= (fingerprint user-key) (read-vec r ENC_GAK_FINGERPRINT 16))
      (throw (ex-info "eve.keyring: the key is not this domain's (its fingerprint differs)"
                      {:type :fingerprint-mismatch}))
      :else (vec user-key))))

(defn domain-salt "The domain's 16-byte keystream salt." [r] (read-vec r ENC_DOMAIN_SALT 16))

;;-----------------------------------------------------------------------------
;; The epoch-key ring (crypto.js allocEpochKey / findEpochKey / eekAddReader /
;; eekRemoveReader / drainEpochKeys / eekRingSummary; enc.vs eek_*)
;;-----------------------------------------------------------------------------

(defn- eek-entry [idx] (+ ENC_EEK_RING_START (* idx EEK_ENTRY_SIZE)))

(defn- eek-cap [r] (min EEK_MAX_ENTRIES (load r ENC_EEK_RING_SIZE)))

(defn eek-alloc!
  "Put epoch's key (32 bytes) and iv (12 bytes) in the ring's next entry: its
   index. Key material first, the status last (no reader sees ACTIVE with a
   torn key); the head advances by CAS, the size saturates at 64."
  [r epoch key iv ts]
  (let [h (loop []
            (let [old (load r ENC_EEK_RING_HEAD)
                  new (mod (inc old) EEK_MAX_ENTRIES)]
              (if (== old (cas! r ENC_EEK_RING_HEAD old new)) new (recur))))
        e (eek-entry h)]
    (store! r (+ e EEK_EPOCH) epoch)
    (store! r (+ e EEK_READER_COUNT) 0)
    (write-vec! r (+ e EEK_KEY_BYTES) (vec key))
    (write-vec! r (+ e EEK_IV_BYTES) (vec iv))
    (store! r (+ e EEK_CREATED_TS) ts)
    (store! r (+ e EEK_STATUS) EEK_ACTIVE)
    (loop []
      (let [n (load r ENC_EEK_RING_SIZE)]
        (when (and (< n EEK_MAX_ENTRIES) (not= n (cas! r ENC_EEK_RING_SIZE n (inc n))))
          (recur))))
    h))

(defn eek-find
  "The index of epoch's active or draining entry, or -1."
  [r epoch]
  (or (first (filter (fn [i]
                       (let [e (eek-entry i)
                             st (load r (+ e EEK_STATUS))]
                         (and (== epoch (load r (+ e EEK_EPOCH)))
                              (or (== st EEK_ACTIVE) (== st EEK_DRAINING)))))
                     (range (eek-cap r))))
      -1))

(defn eek-key
  "[key iv] of entry idx (32 and 12 bytes)."
  [r idx]
  (let [e (eek-entry idx)]
    [(read-vec r (+ e EEK_KEY_BYTES) 32) (read-vec r (+ e EEK_IV_BYTES) 12)]))

(defn- eek-shred! [r e]
  (write-vec! r (+ e EEK_KEY_BYTES) (vec (repeat 32 0)))
  (write-vec! r (+ e EEK_IV_BYTES) (vec (repeat 12 0)))
  (store! r (+ e EEK_STATUS) EEK_SHREDDED))

(defn eek-add-reader!
  "Register a reader of entry idx: true, or false when the entry was shredded
   or freed between eek-find and this call (the count is given back)."
  [r idx]
  (let [e (eek-entry idx)]
    (add! r (+ e EEK_READER_COUNT) 1)
    (let [st (load r (+ e EEK_STATUS))]
      (if (or (== st EEK_SHREDDED) (== st EEK_FREE))
        (do (add! r (+ e EEK_READER_COUNT) -1) false)
        true))))

(defn eek-remove-reader!
  "A reader of entry idx is done: the last reader of a draining entry shreds it."
  [r idx]
  (let [e (eek-entry idx)
        old (add! r (+ e EEK_READER_COUNT) -1)]
    (when (and (== old 1) (== EEK_DRAINING (load r (+ e EEK_STATUS))))
      (eek-shred! r e))
    nil))

(defn eek-drain!
  "Active keys of epochs before min-epoch: shredded when unread, else marked
   draining (their last reader shreds them). The number shredded."
  [r min-epoch]
  (reduce (fn [n i]
            (let [e (eek-entry i)]
              (if (and (< (load r (+ e EEK_EPOCH)) min-epoch)
                       (== EEK_ACTIVE (load r (+ e EEK_STATUS))))
                (if (zero? (load r (+ e EEK_READER_COUNT)))
                  (do (eek-shred! r e) (inc n))
                  (do (cas! r (+ e EEK_STATUS) EEK_ACTIVE EEK_DRAINING) n))
                n)))
          0 (range (eek-cap r))))

(defn eek-summary
  "{:head :size :active :draining :shredded :free} of the ring (eekRingSummary)."
  [r]
  (let [sts (map #(load r (+ (eek-entry %) EEK_STATUS)) (range (eek-cap r)))
        c (fn [x] (count (filter #(== x %) sts)))]
    {:head (load r ENC_EEK_RING_HEAD) :size (load r ENC_EEK_RING_SIZE)
     :active (c EEK_ACTIVE) :draining (c EEK_DRAINING) :shredded (c EEK_SHREDDED)
     :free (- (eek-cap r) (c EEK_ACTIVE) (c EEK_DRAINING) (c EEK_SHREDDED))}))

;;-----------------------------------------------------------------------------
;; The keystream
;;-----------------------------------------------------------------------------

;; "eve.keyring", then the class byte: the info of a class key
(def ^:private class-info [101 118 101 46 107 101 121 114 105 110 103])

(defn class-key
  "The AES-256 key of slab class ci's blocks: HKDF-SHA256(the GAK, the domain's
   salt, \"eve.keyring\" ‖ ci)."
  [gak salt ci]
  (nc/hkdf-sha256 (vec gak) (vec salt) (conj class-info ci) 32))

(def ^:const CHUNK 256)   ; keystream bytes computed at once: 16 AES blocks

(defn- be [x n] (mapv #(bit-and (bit-shift-right x (* 8 (- n 1 %))) 0xFF) (range n)))

(defn keystream-chunk
  "The 256 keystream bytes of chunk q of block bi in class ci under class key
   k: AES-256-CTR from the IV ci:u32BE ‖ bi:u32BE ‖ (q × 16):u64BE."
  [k ci bi q]
  (prim/aes256-ctr k (vec (concat (be ci 4) (be bi 4) [0 0 0 0] (be (* q 16) 4))) (vec (repeat CHUNK 0))))

(defn- chunk-of
  "Chunk q of block (ci, bi), from the wrapper's cache (cleared past 4,096
   chunks: 1 MB of keystream)."
  [keys cache ci bi q]
  (let [ck [ci bi q]]
    (or (get @cache ck)
        (let [ks (keystream-chunk (nth keys ci) ci bi q)]
          (swap! cache (fn [m] (assoc (if (> (count m) 4096) {} m) ck ks)))
          ks))))

(defn- ks-range
  "The n keystream bytes of block slab-offset o from field offset f."
  [keys cache o f n]
  (let [ci (alloc/decode-class-idx o)
        bi (alloc/decode-block-idx o)]
    ;; into, not transient/conj!: clojerl's core has no transients (into builds through one where the host has it)
    (loop [out [] p f left n]
      (if (pos? left)
        (let [q (quot p CHUNK)
              c (chunk-of keys cache ci bi q)
              from (- p (* q CHUNK))
              take-n (min left (- CHUNK from))]
          (recur (into out (subvec c from (+ from take-n))) (+ p take-n) (- left take-n)))
        out))))

(defn- ks-int
  "The w keystream bytes at (o, f) as a little-endian unsigned integer."
  [keys cache o f w]
  (let [ks (ks-range keys cache o f w)]
    (reduce (fn [acc i] (bit-or acc (bit-shift-left (nth ks i) (* 8 i)))) 0 (range w))))

(defn- xor-vals [^bytes b ks] (mapv (fn [i] (bit-xor (bit-and (aget b i) 0xFF) (nth ks i))) (range (alength b))))

(defn- xor-read
  "Bytes b, read from the domain, XOR the keystream ks: in a copy of b (a read
   may be a view of the slab), so the codec reads them in the byte array the
   region answers (signed or unsigned, a typed array — as the host's codec
   holds bytes)."
  [^bytes b ks]
  (let [out (enc/bytes-copy b)]
    (dotimes [i (alength b)] (aset out i (unchecked-byte (bit-xor (bit-and (aget b i) 0xFF) (nth ks i)))))
    out))

;;-----------------------------------------------------------------------------
;; The encrypting slab I/O
;;-----------------------------------------------------------------------------

(deftype EncryptedSlabIO [inner keys cache]
  alloc/IWrappedSio
  (-wrapped-sio [_] inner)

  alloc/ISlabIO
  (-sio-read-u8 [_ o f] (bit-xor (alloc/-sio-read-u8 inner o f) (ks-int keys cache o f 1)))
  (-sio-write-u8! [_ o f v] (alloc/-sio-write-u8! inner o f (bit-xor (bit-and v 0xFF) (ks-int keys cache o f 1))))
  (-sio-read-u16 [_ o f] (bit-xor (alloc/-sio-read-u16 inner o f) (ks-int keys cache o f 2)))
  (-sio-write-u16! [_ o f v] (alloc/-sio-write-u16! inner o f (bit-xor (bit-and v 0xFFFF) (ks-int keys cache o f 2))))
  (-sio-read-i32 [_ o f] (i32/i32 (bit-xor (alloc/-sio-read-i32 inner o f) (i32/i32 (ks-int keys cache o f 4)))))
  (-sio-write-i32! [_ o f v] (alloc/-sio-write-i32! inner o f (i32/i32 (bit-xor v (i32/i32 (ks-int keys cache o f 4))))))
  (-sio-cas-i32! [_ o f e d]
    (let [k (i32/i32 (ks-int keys cache o f 4))]
      (i32/i32 (bit-xor k (alloc/-sio-cas-i32! inner o f (i32/i32 (bit-xor e k)) (i32/i32 (bit-xor d k)))))))
  (-sio-read-bytes [_ o f n] (xor-read (alloc/-sio-read-bytes inner o f n) (ks-range keys cache o f n)))
  (-sio-write-bytes! [_ o f src]
    ;; written as values 0..255: the region takes any of the host's byte arrays
    (alloc/-sio-write-bytes! inner o f (enc/bytes-of (xor-vals src (ks-range keys cache o f (alength ^bytes src))))))
  (-sio-alloc! [_ n] (alloc/-sio-alloc! inner n))
  (-sio-free! [_ o] (alloc/-sio-free! inner o))
  (-sio-copy-block! [this d df s sf n]
    (alloc/-sio-write-bytes! this d df (alloc/-sio-read-bytes this s sf n)))

  alloc/ISlabRegion
  (-sio-mem-region [_ _ _]
    (throw (ex-info "eve.keyring: an encrypted domain's blocks are read through its slab I/O, never as a region" {}))))

(defn encrypted-sio
  "The slab I/O of domain sio inner, its block bytes under the keystream of the
   GAK and the domain's salt: a wrapper over inner, or inner itself where the
   host's process slab is the domain and takes the keystream (ClojureScript's
   module slabs: their DataView readers, eve's types' accessors among them, never
   pass through a wrapper — alloc/keystream-domain-sio)."
  [inner gak salt]
  (let [keys (mapv #(class-key gak salt %) (range 7))
        cache (atom {})]
    (alloc/keystream-domain-sio inner
                                {:int (fn [o f w] (ks-int keys cache o f w))
                                 :bytes (fn [o f n] (ks-range keys cache o f n))}
                                (fn [s] (->EncryptedSlabIO s keys cache)))))

(defn encrypted-sio?
  "True for a slab I/O under eve.keyring's keystream."
  [sio]
  (or (instance? EncryptedSlabIO sio) (alloc/keystream-domain? sio)))

;;-----------------------------------------------------------------------------
;; Domains
;;-----------------------------------------------------------------------------

(defn keyring-path "The key ring file of the domain at base-path." [base-path] (str base-path ".keyring"))

(defn open-ring
  "The key ring of the domain at base-path, its file created when missing."
  [base-path]
  ;; lockable: a lock-only host (bb) takes the file lock for the ring's CAS, as
  ;; eve.atom's root does
  (region/open-mmap-region (keyring-path base-path) RECORD_BYTES {:lockable? true}))

(defn open-encrypted-domain!
  "The encrypted mmap domain at base-path, [slab-io ring]. opts: :create?
   (initialise the domain's files and its ring), :status (1 auto, the default:
   a fresh GAK kept in the ring; 2 user; 3 user, not stored), :key (the user's
   32-byte key for a user ring, created or opened), :capacities (as
   alloc/mmap-domain-sio!'s)."
  [base-path {:keys [create? status key capacities] :or {status KEY_STATUS_AUTO capacities {}}}]
  (proc/ensure-parent-dir! base-path)
  (let [r (open-ring base-path)]
    (when create?
      (let [gak (if (= status KEY_STATUS_AUTO) (proc/random-bytes 32) key)]
        (when (not= 32 (count gak))
          (throw (ex-info "eve.keyring: a user ring needs a 32-byte key" {:type :bad-key})))
        (when (neg? (init! r gak status))
          (throw (ex-info "eve.keyring: bad key status" {:status status})))))
    (let [gak (read-gak r key)]
      (when (nil? gak)
        (throw (ex-info "eve.keyring: the domain has no key ring" {:type :not-encrypted :path base-path})))
      [(encrypted-sio (alloc/mmap-domain-sio! base-path {:create? create? :capacities capacities})
                      gak (domain-salt r))
       r])))
