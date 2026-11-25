(ns eve.bytes-scalar-test
  "The one binary scalar (serialize/FAST_TAG_BYTES 0x28, doc/flat/FORMAT-V1.md §3) and eve's binary value (eve.bin,
   doc/flat/BIN.md): a byte[] or an eve binary keeps the same [len varint][bytes] run in the codec's bytes, an atom's
   value on disk, a flat chunk and a file — moved by copy, never re-encoded — and reads back as an eve binary that VIEWS
   the run where the reader holds it: the codec's buffer, the chunk, the disk domain's block."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.alpha :as e]
            [eve.bin :as bin]
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

(defn- same-bytes? [b ^bytes expected]
  (and (bin/bin? b) (Arrays/equals ^bytes (bin/->bytes b) expected)))

(deftest the-binary-value
  (let [b (bin/of-bytes payload)]
    (testing "count, signed nth (Clojure's byte[] reads), the unsigned read a named fn"
      (is (= 6 (count b)))
      (is (= [1 0 -1 -61 40 -128] (mapv #(nth b %) (range 6))))
      (is (= [1 0 255 195 40 128] (mapv #(bin/ubyte b %) (range 6))))
      (is (= :nf (nth b 6 :nf)))
      (is (thrown? Exception (nth b 6))))
    (testing "= and hash by content; never = to text or to a host byte array"
      (is (= b (bin/of-bytes (bs 1 0 255 195 40 128))))
      (is (= (hash b) (hash (bin/of-bytes payload))))
      (is (not= b (bin/of-bytes (bs 1 0 255 195 40))))
      (is (not= b payload))
      (is (not= (bin/of-bytes (.getBytes "ab" "UTF-8")) "ab"))
      (is (= 1 (get {b 1} (bin/of-bytes payload)))))
    (testing "the edge in copies: a later write to the host array is not seen"
      (let [h (bs 1 2 3) v (bin/of-bytes h)]
        (aset-byte h 0 9)
        (is (= 1 (nth v 0)))))
    (testing "slice is a view of the same bytes; compare is unsigned lexicographic, shorter first"
      (let [s (bin/slice b 2 4)]
        (is (= [-1 -61] (vec (map #(nth s %) (range 2)))))
        (is (= s (bin/of-bytes (bs 255 195)))))
      (is (= -1 (bin/compare (bin/of-bytes (bs 1 2)) (bin/of-bytes (bs 1 2 0)))))
      (is (= 1 (bin/compare (bin/of-bytes (bs 0x80)) (bin/of-bytes (bs 0x7f)))))
      (is (= 0 (bin/compare b (bin/of-bytes payload)))))
    (testing "prints as #eve/bytes"
      (is (= "#eve/bytes \"0100ffc32880\"" (pr-str b))))))

(deftest codec-writes-the-bytes-scalar
  (is (= "eedb28060100ffc32880" (hex (codec/value->eve-bytes payload))))
  (is (= "eedb28060100ffc32880" (hex (codec/value->eve-bytes (bin/of-bytes payload)))) "an eve binary: the same scalar")
  (is (= "eedb2800" (hex (codec/value->eve-bytes (byte-array 0)))))
  (let [big (byte-array 200 (unchecked-byte 7))
        eb (codec/value->eve-bytes big)]
    (testing "a run past 127 bytes takes a two-byte varint"
      (is (= "eedb28c801" (subs (hex eb) 0 10)))
      (is (= 205 (alength ^bytes eb)))))
  (testing "decoded back as an eve binary viewing the decode's buffer"
    (let [v (codec/eve-bytes->value (codec/value->eve-bytes payload))]
      (is (same-bytes? v payload))
      (is (not (bin/slab-view? v))))))

(deftest flat-chunk-carries-the-same-run
  (let [scalar (Arrays/copyOfRange ^bytes (codec/value->eve-bytes payload) 2 10)
        chunk (ch/bytes-of (ch/pack {:b payload}))]
    (testing "the chunk's bytes node is the codec's bytes less the magic"
      (is (= "28060100ffc32880" (hex scalar)))
      (is (= (hex scalar) (hex (ch/scalar-body payload))))
      (is (<= 0 (index-of chunk scalar))))
    (testing "an eve binary packs to the same chunk"
      (is (Arrays/equals ^bytes chunk ^bytes (ch/bytes-of (ch/pack {:b (bin/of-bytes payload)})))))
    (testing "chunk → file → read → unpack: a view of the chunk read back"
      (let [f (Files/createTempFile "eve-bytes" ".evs" (make-array FileAttribute 0))]
        (try
          (Files/write f ^bytes chunk (make-array java.nio.file.OpenOption 0))
          (let [back (Files/readAllBytes f)
                v (:b (ch/unpack back))]
            (is (Arrays/equals ^bytes back ^bytes chunk))
            (is (same-bytes? v payload))
            (is (= v (bin/of-bytes payload))))
          (finally (Files/deleteIfExists f)))))))

(deftest disk-atom-holds-the-bytes-scalar
  (let [dir (Files/createTempDirectory "eve-bytes-atom" (make-array FileAttribute 0))
        ;; the domain's files are <path>.slabN, <path>.root, ... beside the path: a path inside a fresh dir
        a (e/atom {:id :eve.bytes-scalar-test/b :persistent (str (.resolve dir "db"))} {:n 0})]
    (swap! a assoc :b payload :c (bin/of-bytes (bs 9 8 7)))
    (let [v (:b @a)]
      (testing "deref answers an eve binary VIEWING the domain's block: no copy of the run"
        (is (bin/bin? v))
        (is (bin/slab-view? v) "the read is a view of the slab block, not a copy")
        (is (same-bytes? v payload))
        (is (= v (bin/of-bytes payload)))
        (is (= [1 0 -1 -61 40 -128] (mapv #(nth v %) (range 6))))
        (is (= (bin/of-bytes (bs 9 8 7)) (:c @a))))
      (testing "an escaping value is copied once, and is still the same value"
        (let [o (bin/own v)]
          (is (not (bin/slab-view? o)))
          (is (= o v)))))
    (testing "the atom's files hold the codec's bytes scalar verbatim"
      (let [scalar (codec/value->eve-bytes payload)
            hits (for [^java.io.File f (file-seq (.toFile dir)) :when (.isFile f)
                       :when (<= 0 (index-of (Files/readAllBytes (.toPath f)) scalar))]
                   (.getName f))]
        (is (seq hits) "no file of the domain holds eedb28060100ffc32880")))
    (testing "deref → flat chunk → unpack"
      (let [c (ch/bytes-of (ch/pack {:b (:b @a)}))]
        (is (same-bytes? (:b (ch/unpack c)) payload))))
    (testing "a binary stored back into the atom keeps its bytes"
      (swap! a (fn [m] (assoc m :d (:b m))))
      (is (= (:b @a) (:d @a))))
    (doseq [^java.io.File f (reverse (file-seq (.toFile dir)))] (.delete f))))
