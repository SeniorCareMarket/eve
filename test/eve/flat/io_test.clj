(ns eve.flat.io-test
  "Lane H8 — the writer/reader surface (IMPL-PLAN §3.4, requirements
   H8.1–H8.3). A stream of values framed as packed chunks; handlers
   keyed by type key on the read side; the default handler answers the
   generic record; a file written in one process reads in another.
   The reader's only door for stream bytes is eve.flat.check —
   there is no unchecked read in this namespace's tests either."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eve.flat.chunk :as chunk]
            [eve.flat.io :as io])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream File]
           [java.nio.file Files]
           [java.util Arrays]))

(defrecord Widget [name size])
(defrecord Gadget [label weight])

(defn- key-of
  "The wire type key (FORMAT-V1 §6) the format itself assigns a record
   instance: read it off the packed handle."
  [v]
  (chunk/record-tag (chunk/pack v)))

(defn- stream-bytes
  "Write values with io/write-bytes; the bytes of the whole stream."
  ^bytes [values]
  (io/write-bytes values))

(defn- read-stream
  "Read a whole stream back with the given reader opts."
  [^bytes bs opts]
  (io/read-bytes bs opts))

;; ---------------------------------------------------------------------------
;; H8.1 — a stream of values, handlers keyed by type key
;; ---------------------------------------------------------------------------

(deftest writer-reader-handlers
  (let [wkey (key-of (->Widget "probe" 0))
        gkey (key-of (->Gadget "probe" 0))]
    (is (= "eve.flat.io_test/Widget" wkey) "the type key is the §6 ns/Type key")
    (is (= "eve.flat.io_test/Gadget" gkey))
    (let [handled (atom [])
          handlers {wkey (fn [h] (swap! handled conj wkey) [:widget (:name h) (:size h)])
                    gkey (fn [h] (swap! handled conj gkey) [:gadget (:label h) (:weight h)])}
          values [42 "hello" [1 2 3] {:a 1}
                  (->Widget "w1" 3)
                  (->Gadget "g1" 9)
                  {:items [(->Widget "w2" 4)]}
                  nil
                  #{1 2}]
          bs (stream-bytes values)
          out (read-stream bs {:handlers handlers})]
      (is (= (count values) (count out)) "every value in the stream reads back, in order")
      (is (= 42 (nth out 0)) "a scalar travels as itself")
      (is (= "hello" (nth out 1)))
      (is (= [1 2 3] (chunk/unpack (nth out 2))) "a plain collection reads as a flat, unpacks equal")
      (is (= {:a 1} (chunk/unpack (nth out 3))))
      (is (= [:widget "w1" 3] (nth out 4))
          "the Widget handler, keyed by its type key, answered for the record")
      (is (= [:gadget "g1" 9] (nth out 5))
          "the Gadget handler, keyed by its type key, answered for the record")
      (is (= {:items [{:name "w2" :size 4}]} (chunk/unpack (nth out 6)))
          "a record nested in a plain value needs no handler: it materializes by fields")
      (is (nil? (nth out 7)))
      (is (= #{1 2} (chunk/unpack (nth out 8))))
      (is (= [wkey gkey] @handled) "handlers fired only for the two top-level records, in stream order"))
    (testing "a value at a time: write! emits, read! consumes, eof is a value"
      (let [baos (ByteArrayOutputStream.)
            w (io/writer baos)
            _ (io/write! w (->Widget "solo" 1))
            _ (io/write! w 7)
            _ (io/close! w)
            r (io/reader (ByteArrayInputStream. (.toByteArray baos))
                         {:handlers {wkey (fn [h] [:widget (:name h) (:size h)])}})]
        (is (= [:widget "solo" 1] (io/read! r)))
        (is (= 7 (io/read! r)))
        (is (= :eve.flat.io/eof (io/read! r)) "past the last value, read! answers the eof value")
        (is (= :eve.flat.io/eof (io/read! r)) "and keeps answering it")))
    (testing "the checked door: corrupted stream bytes are a typed refusal, never a value"
      ;; corrupt the second frame's chunk magic: deterministic
      ;; ill-formedness (a flipped value byte would only make a
      ;; different well-formed value — check vouches form; the digest
      ;; flag is the integrity door, and pack does not set it)
      (let [bs (stream-bytes [(->Widget "w1" 3) {:a 1}])
            len1 (bit-or (bit-shift-left (bit-and (aget bs 0) 0xFF) 24)
                         (bit-shift-left (bit-and (aget bs 1) 0xFF) 16)
                         (bit-shift-left (bit-and (aget bs 2) 0xFF) 8)
                         (bit-and (aget bs 3) 0xFF))
            bad (Arrays/copyOf bs (alength bs))
            magic-at (+ 4 len1 4)]
        (aset bad magic-at (unchecked-byte (bit-xor (aget bad magic-at) 0xFF)))
        (let [r (io/reader (ByteArrayInputStream. bad) {})]
          (is (chunk/generic-record? (io/read! r))
              "the first, intact value still reads")
          (let [e (try (io/read! r) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (some? e) "the corrupted frame is refused, not read")
            (is (= :bad-magic (:check (ex-data e)))
                "the refusal is check's, naming its check — the bytes were vouched, not trusted")))))
    (testing "a stream cut mid-frame is a typed refusal, not a hang or a host error"
      (let [bs (stream-bytes [{:a 1} {:b 2}])
            cut (Arrays/copyOf bs (- (alength bs) 2))]
        (is (thrown? clojure.lang.ExceptionInfo (read-stream cut {})))))))

;; ---------------------------------------------------------------------------
;; H8.2 — the default handler answers the generic record
;; ---------------------------------------------------------------------------

(deftest reader-default
  (let [wkey (key-of (->Widget "probe" 0))
        bs (stream-bytes [(->Widget "w1" 3) (->Gadget "g1" 9)])]
    (testing "no handlers at all: a record reads as the generic record"
      (let [out (read-stream bs {})]
        (is (chunk/generic-record? (nth out 0)) "the default answer is the generic record")
        (is (= wkey (chunk/record-tag (nth out 0))) "carrying its type key")
        (is (= "w1" (:name (nth out 0))) "its fields read as a map's")
        (is (= 3 (:size (nth out 0))))
        (is (chunk/generic-record? (nth out 1)))))
    (testing "a handler for one key leaves the other to the default"
      (let [out (read-stream bs {:handlers {wkey (fn [h] [:widget (:name h) (:size h)])}})]
        (is (= [:widget "w1" 3] (nth out 0)))
        (is (chunk/generic-record? (nth out 1)) "the unhandled Gadget fell to the default")))
    (testing "a custom :default is called with the key and the record"
      (let [out (read-stream bs {:default (fn [k h] [:defaulted k (:name h) (:label h)])})]
        (is (= [:defaulted wkey "w1" nil] (nth out 0)))
        (is (= [:defaulted "eve.flat.io_test/Gadget" nil "g1"] (nth out 1)))))))

;; ---------------------------------------------------------------------------
;; H8.3 — a file written in one process reads in another
;; ---------------------------------------------------------------------------

(deftest file-roundtrip
  (let [f (File/createTempFile "eve-flat-io" ".evs")
        path (.getAbsolutePath f)
        values [42 {:a 1 :b [1 2 3]} (->Widget "file-widget" 5) "tail"]]
    (try
      (with-open [w (io/file-writer f)]
        (doseq [v values] (io/write! w v)))
      (testing "a fresh reader in this process reads what the writer wrote"
        (with-open [r (io/file-reader f)]
          (let [out (io/read-all r)]
            (is (= 4 (count out)))
            (is (= 42 (nth out 0)))
            (is (= {:a 1 :b [1 2 3]} (chunk/unpack (nth out 1))))
            (is (chunk/generic-record? (nth out 2)) "no handlers here: the record is the generic record")
            (is (= "file-widget" (:name (nth out 2))))
            (is (= "tail" (nth out 3))))))
      (testing "a second JVM reads the same file through the same door"
        (let [script (str "(require (quote eve.flat.io) (quote eve.flat.chunk))"
                          "(let [r (eve.flat.io/file-reader (java.io.File. \"" path "\"))"
                          "      vs (eve.flat.io/read-all r)]"
                          "  (prn (mapv eve.flat.chunk/unpack vs))"
                          "  (.close r))")
              pb (ProcessBuilder.
                   (into-array String
                               ["java" "-cp" (System/getProperty "java.class.path")
                                "clojure.main" "-e" script]))
              _ (.redirectErrorStream pb true)
              p (.start pb)
              out (String. (.readAllBytes (.getInputStream p)) "UTF-8")
              done (.waitFor p 120 java.util.concurrent.TimeUnit/SECONDS)]
          (is done "the reader process finished")
          (when done
            (is (zero? (.exitValue p)) (str "the reader process exited clean: " out))
            (is (= [42 {:a 1 :b [1 2 3]} {:name "file-widget" :size 5} "tail"]
                   (edn/read-string (last (filter seq (clojure.string/split-lines out)))))
                "the other process unpacked the same values, the record by its fields"))))
      (finally (.delete f)))))
