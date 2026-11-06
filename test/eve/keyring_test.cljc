(ns eve.keyring-test
  "EVM-11d (EM5): eve's at-rest key ring, read against the kernel's at-rest red set (F9-1..4) — the same postures, on
   eve's ring and every eve host with mmap domains:
     F9-1  an encrypted domain opened writes and closes;
     F9-2  a second open reads the first's value back equal, and the known string is in no file of the domain (the
           red the kernel's committed builder shows today, verified 2026-10-18: c7's on-postures 'the known string
           does not appear' FAIL — the flag on, the bytes plaintext);
     F9-3  the off posture writes plaintext (the string present) and reads back equal;
     F9-4  an explicit enable with the program's own key, then a round trip; without the key, or with another, the
           domain refuses.
   The fixtures pin the bytes — the record's offsets (enc.vs's), the fingerprint, a class key, a keystream chunk —
   each computed by an independent model (Python's hmac, hashlib and cryptography, 2026-10-18)."
  ;; the codec's ^bytes return hint names eve.host's bytes where it is called (clojerl resolves it there)
  (:refer-clojure :exclude [bytes])
  (:require [clojure.test :refer [deftest is testing]]
            [eve.host :refer [bytes]]
            [clojure.string :as str]
            [eve.keyring :as kr]
            [eve.codec :as codec]
            [eve.core :as e]
            [eve.deftype-proto.alloc :as alloc]
            [eve.region :as region]
            [eve.mem :as mem]
            [eve.process :as proc]
            [eve.sorted :as s]
            [eve.deftype-proto.data :as d]
            ;; the collection writers a host map is written through
            [eve.map] [eve.set] [eve.vec] [eve.list]
            #?@(:cljs [["fs" :as fs] ["os" :as os]])))

(defn- hex [v] (apply str (map #(let [x (bit-and % 0xFF)] (str (nth "0123456789abcdef" (quot x 16)) (nth "0123456789abcdef" (mod x 16)))) v)))

(defn- region-bytes [r off n]
  (let [b (mem/-read-bytes r off n)]
    (mapv #(bit-and (int #?(:cljs (aget b %) :default (nth b %))) 0xFF) (range n))))

(deftest the-fixtures
  (testing "the fingerprint of the key 17..48 (crypto.js _fingerprint, enc.vs enc_fingerprint)"
    (is (= "256fc4a133523a87163dfe265ffe1dd7" (hex (kr/fingerprint (vec (range 17 49)))))))
  (testing "class 3's key under GAK 0..31 and salt 100..115: HKDF-SHA256(gak, salt, \"eve.keyring\" ‖ 3)"
    (is (= "3a797670762f07ad2fc5921734e45a808999976f20d558e15b70770357b8a99c"
           (hex (kr/class-key (vec (range 32)) (vec (range 100 116)) 3)))))
  (testing "that key's keystream, class 3, block 5, chunk 1: AES-256-CTR from 00000003 00000005 00000000 00000010"
    (is (= "5ce7c386cc19b0a456dfb928ec441211c6d928f26d1c80475ec9e811d89edc62"
           (hex (subvec (kr/keystream-chunk (kr/class-key (vec (range 32)) (vec (range 100 116)) 3) 3 5 1) 0 32))))))

(deftest the-record-is-enc-vs-s
  (let [r (region/make-heap-region kr/RECORD_BYTES)
        gak (vec (range 32))]
    (is (= 1 (kr/init! r gak kr/KEY_STATUS_AUTO)) "this call initialised the ring")
    (is (= 0 (kr/init! r gak kr/KEY_STATUS_AUTO)) "a second initialiser loses the magic's CAS")
    (is (= -1 (kr/init! (region/make-heap-region kr/RECORD_BYTES) gak 4)) "a bad status")
    (is (= [0x52 0x43 0x4e 0x45] (region-bytes r 0 4)) "magic 'ENCR' (0x454E4352 LE) @0")
    (is (= [1 0 0 0 1 0 0 0 1 0 0 0] (region-bytes r 4 12)) "version 1 @4, status auto @8, AES-256-CTR @12")
    (is (= gak (region-bytes r 16 32)) "the GAK @16")
    (is (= (kr/fingerprint gak) (region-bytes r 48 16)) "its fingerprint @48")
    (is (= [0xff 0xff 0xff 0xff 0 0 0 0] (region-bytes r 64 8)) "eek head -1 @64, size 0 @68")
    (is (= gak (kr/read-gak r nil)) "an auto ring hands its GAK back")
    (is (= 16 (count (kr/domain-salt r))))))

(defn- caught-type [f]
  (try (f) nil (catch #?(:cljs :default :jank jank.runtime.object_ref :default Exception) ex (:type (ex-data ex)))))

(deftest the-epoch-key-ring
  (let [r (region/make-heap-region kr/RECORD_BYTES)
        k (fn [x] (vec (repeat 32 x)))
        iv (vec (repeat 12 9))]
    (kr/init! r (k 1) kr/KEY_STATUS_AUTO)
    (let [i1 (kr/eek-alloc! r 1 (k 11) iv 100)
          i2 (kr/eek-alloc! r 2 (k 12) iv 101)
          i3 (kr/eek-alloc! r 3 (k 13) iv 102)]
      (is (= [0 1 2] [i1 i2 i3]) "the head advances from -1")
      (is (= i2 (kr/eek-find r 2)))
      (is (= -1 (kr/eek-find r 9)))
      (is (= [(k 12) iv] (kr/eek-key r i2)))
      ;; a call with an effect is bound first: basilisp's is evaluates a
      ;; predicate's argument twice (measured 2026-10-18: (is (true? (f))) calls f twice)
      (let [added (kr/eek-add-reader! r i2)]
        (is (true? added) "epoch 2 has a reader"))
      (is (= 1 (kr/eek-drain! r 3)) "epochs before 3: the unread one is shredded, the read one drains")
      (is (= {:head 2 :size 3 :active 1 :draining 1 :shredded 1 :free 0} (kr/eek-summary r)))
      (is (= -1 (kr/eek-find r 1)) "a shredded key is not found")
      (is (= (k 0) (first (kr/eek-key r i1))) "and its bytes are zero")
      (let [added (kr/eek-add-reader! r i1)]
        (is (false? added) "a reader of a shredded entry is refused"))
      (kr/eek-remove-reader! r i2)
      (is (= {:head 2 :size 3 :active 1 :draining 0 :shredded 2 :free 0} (kr/eek-summary r))
          "the last reader of a draining key shreds it"))))

(defn- fresh-base []
  (str #?(:cljs (.tmpdir os) :lpy "/tmp" :clje "/tmp" :jank "/tmp" :cljr (System.IO.Path/GetTempPath)
          :default (System/getProperty "java.io.tmpdir"))
       "/eve-keyring-" (rand-int 1000000000) "/d"))

(defn- file-text
  "the file's bytes as one char per byte (Latin-1), or \"\" when it is missing"
  [p]
  (if-not (proc/file-exists? p)
    ""
    #?(:cljs (.toString (.readFileSync fs p) "latin1")
       :lpy (.decode (with-open [f (python/open p "rb")] (.read f)) "latin-1")
       :clje (unicode/characters_to_binary (erlang/element 2 (file/read_file p)) :latin1)
       :cljr (.GetString System.Text.Encoding/Latin1 (System.IO.File/ReadAllBytes ^String p))
       ;; (a Go string holds the bytes as they are: an ASCII needle is found in them)
       :glj (let [[b _] (os.ReadFile p)] (.String (bytes.NewBuffer b)))
       ;; (a jank string holds the bytes as they are too: slurp of 523 bytes counts 523, the needle found — measured)
       :jank (slurp p)
       :default (String. ^bytes (java.nio.file.Files/readAllBytes (.toPath (java.io.File. ^String p))) "ISO-8859-1"))))

(defn- in-domain-files? [base s]
  (boolean (some #(str/includes? (file-text (str base ".slab" %)) s) (range 7))))

(defn- needle [] (str "slab-encryption-known-" "plaintext-7f3a" "9c1e"))

(defn- write-secret [sio] (let [b (codec/encode-value sio {:secret (needle) :n [1 2 3]})] (vec b)))

(defn- host-bytes [b]
  #?(:cljs (js/Uint8Array.from (clj->js (map #(bit-and % 0xFF) b)))
     :lpy (python/bytearray (map #(bit-and % 0xFF) b))
     :clje (eve.host/byte-array (map eve.host/unchecked-byte b))
     :default (byte-array (map unchecked-byte b))))

(defn- read-secret [sio b] (e/get (codec/decode sio (host-bytes b)) :secret))

(defn- release!
  "A domain let go: on a host whose process slab is the domain (ClojureScript's
   module slabs), what it replaced comes back."
  [sio]
  (alloc/release-domain-sio! sio true))

(deftest f9-1-and-2-an-encrypted-domain-writes-closes-and-reads-back
  (let [base (fresh-base)
        [sio ring] (kr/open-encrypted-domain! base {:create? true})]
    (try
      (let [b (write-secret sio)]
        (is (kr/encrypted-sio? sio))
        (is (= 1 (kr/status ring)))
        (is (= (needle) (read-secret sio b)) "F9-1: the domain writes and reads in its first open")
        (let [;; an eve type's fields too (a sorted map's nodes and entries), made in the domain as a swap! makes them
              sm (binding [d/*domain-sio* sio] (s/sorted-map 2 (str "sorted-" (needle)) 1 "a"))
              b2 (vec (codec/encode-value sio sm))]
          (is (= (str "sorted-" (needle)) (e/get (codec/decode sio (host-bytes b2)) 2)) "an eve type in the domain")
          (let [[sio2 _] (kr/open-encrypted-domain! base {})]
            (try
              (is (= (needle) (read-secret sio2 b)) "F9-2: a second open reads the first's value back")
              (is (= [1 2 3] (vec (e/seq (e/get (codec/decode sio2 (host-bytes b)) :n)))))
              (is (= [1 2] (vec (map key (e/seq (codec/decode sio2 (host-bytes b2)))))) "and the eve type's")
              (finally (release! sio2)))))
        (is (not (in-domain-files? base (needle))) "F9-2: the known string is in no file of the domain")
        (is (not (in-domain-files? base "secret")) "nor the key it is stored under"))
      (finally (release! sio)))))

(deftest f9-3-the-off-posture-is-plaintext-and-reads-back
  (let [base (fresh-base)
        _ (proc/ensure-parent-dir! base)
        sio (alloc/mmap-domain-sio! base {:create? true})]
    (try
      (let [b (write-secret sio)]
        (is (= (needle) (read-secret sio b)))
        (is (in-domain-files? base (needle)) "off: the known string is in the files (plaintext, as it should be)")
        (let [sio2 (alloc/mmap-domain-sio! base {})]
          (try
            (is (= (needle) (read-secret sio2 b)) "and a second open reads it back")
            (finally (release! sio2)))))
      (finally (release! sio)))))

(deftest f9-4-an-explicit-enable-with-the-program-s-key
  (let [base (fresh-base)
        key (vec (range 17 49))
        [sio ring] (kr/open-encrypted-domain! base {:create? true :status kr/KEY_STATUS_USER :key key})]
    (try
      (let [b (write-secret sio)]
        (is (= 2 (kr/status ring)))
        (is (= (needle) (read-secret sio b)) "the round trip under the program's key")
        (let [[sio2 _] (kr/open-encrypted-domain! base {:key key})]
          (try
            (is (= (needle) (read-secret sio2 b)) "reopened with the key")
            (finally (release! sio2))))
        (is (= :user-key-required (caught-type #(kr/open-encrypted-domain! base {}))) "without it, refused")
        (is (= :fingerprint-mismatch (caught-type #(kr/open-encrypted-domain! base {:key (vec (range 32))})))
            "with another, refused")
        (is (not (in-domain-files? base (needle)))))
      (finally (release! sio)))))

(deftest a-key-not-stored-is-not-in-the-ring
  (let [base (fresh-base)
        key (vec (range 200 232))
        [sio ring] (kr/open-encrypted-domain! base {:create? true :status kr/KEY_STATUS_USER_NO_STORE :key key})]
    (try
      (let [b (write-secret sio)]
        (is (= 3 (kr/status ring)))
        (is (= (vec (repeat 32 0)) (region-bytes ring 16 32)) "the ring's GAK bytes stay zero")
        (is (= (kr/fingerprint key) (region-bytes ring 48 16)) "only the fingerprint is kept")
        (let [[sio2 _] (kr/open-encrypted-domain! base {:key key})]
          (try
            (is (= (needle) (read-secret sio2 b)))
            (finally (release! sio2)))))
      (finally (release! sio)))))

(deftest an-encrypted-domain-is-shared
  (let [[sio _] (kr/open-encrypted-domain! (fresh-base) {:create? true})]
    (try
      (is (alloc/shared-domain? sio) "the wrapper writes an mmap domain: no reference reaches it (§9)")
      (is (do (alloc/refresh! sio) true) "a refresh (another process grew a class) reaches the wrapped domain")
      (is (thrown? #?(:cljs js/Error :jank nil :default Exception) (codec/encode-value sio {:f (fn [x] x)})))
      (finally (release! sio)))))
