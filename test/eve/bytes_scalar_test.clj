(ns eve.bytes-scalar-test
  "The one binary scalar (serialize/FAST_TAG_BYTES 0x28, doc/flat/FORMAT-V1.md §3): a byte[] keeps the same
   [len varint][bytes] run in the codec's bytes, an atom's value on disk, a flat chunk and a file — moved by copy,
   never re-encoded — and reads back equal at every hop."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.alpha :as e]
            [eve.codec :as codec]
            [eve.flat.chunk :as ch])
  (:import [java.util Arrays]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- hex [^bytes b] (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(defn- bs [& xs] (byte-array (map unchecked-byte xs)))

(defn- index-of
  "The offset of needle's bytes inside hay, or -1."
  [^bytes hay ^bytes needle]
  (let [n (alength needle)]
    (or (first (filter #(Arrays/equals hay (int %) (int (+ % n)) needle 0 n)
                       (range (inc (- (alength hay) n)))))
        -1)))

(def ^:private payload (bs 1 0 255 195 40 128))

(deftest codec-writes-the-bytes-scalar
  (is (= "eedb28060100ffc32880" (hex (codec/value->eve-bytes payload))))
  (is (= "eedb2800" (hex (codec/value->eve-bytes (byte-array 0)))))
  (let [big (byte-array 200 (unchecked-byte 7))
        eb (codec/value->eve-bytes big)]
    (testing "a run past 127 bytes takes a two-byte varint"
      (is (= "eedb28c801" (subs (hex eb) 0 10)))
      (is (= 205 (alength ^bytes eb)))))
  (testing "decoded back as a fresh host byte array"
    (let [v (codec/eve-bytes->value (codec/value->eve-bytes payload))]
      (is (bytes? v))
      (is (Arrays/equals ^bytes v ^bytes payload)))))

(deftest flat-chunk-carries-the-same-run
  (let [scalar (Arrays/copyOfRange ^bytes (codec/value->eve-bytes payload) 2 10)
        chunk (ch/bytes-of (ch/pack {:b payload}))]
    (testing "the chunk's bytes node is the codec's bytes less the magic"
      (is (= "28060100ffc32880" (hex scalar)))
      (is (= (hex scalar) (hex (ch/scalar-body payload))))
      (is (<= 0 (index-of chunk scalar))))
    (testing "chunk → file → read → unpack"
      (let [f (Files/createTempFile "eve-bytes" ".evs" (make-array FileAttribute 0))]
        (try
          (Files/write f ^bytes chunk (make-array java.nio.file.OpenOption 0))
          (let [back (Files/readAllBytes f)
                v (:b (ch/unpack back))]
            (is (Arrays/equals ^bytes back ^bytes chunk))
            (is (bytes? v))
            (is (Arrays/equals ^bytes v ^bytes payload)))
          (finally (Files/deleteIfExists f)))))))

(deftest disk-atom-holds-the-bytes-scalar
  (let [dir (Files/createTempDirectory "eve-bytes-atom" (make-array FileAttribute 0))
        ;; the domain's files are <path>.slabN, <path>.root, ... beside the path: a path inside a fresh dir
        a (e/atom {:id :eve.bytes-scalar-test/b :persistent (str (.resolve dir "db"))} {:n 0})]
    (swap! a assoc :b payload)
    (let [v (:b @a)]
      (testing "deref answers the bytes, not an i8 column or a reference"
        (is (bytes? v))
        (is (Arrays/equals ^bytes v ^bytes payload))))
    (testing "the atom's files hold the codec's bytes scalar verbatim"
      (let [scalar (codec/value->eve-bytes payload)
            hits (for [^java.io.File f (file-seq (.toFile dir)) :when (.isFile f)
                       :when (<= 0 (index-of (Files/readAllBytes (.toPath f)) scalar))]
                   (.getName f))]
        (is (seq hits) "no file of the domain holds eedb28060100ffc32880")))
    (testing "deref → flat chunk → unpack"
      (let [c (ch/bytes-of (ch/pack {:b (:b @a)}))]
        (is (Arrays/equals ^bytes (:b (ch/unpack c)) ^bytes payload))))
    (doseq [^java.io.File f (reverse (file-seq (.toFile dir)))] (.delete f))))
