(ns eve.net.bytes-codec-test
  "The byte codec slot (defect register #50): eve.encoding's plugged codecs (bytes->b64, b64->bytes, bytes->hex,
   hex->bytes) held to eve.net.bytes' portable implementations byte for byte, over a corpus that includes the
   edges the plugs handle specially — base64 text with characters outside the alphabet (which the contract
   skips, padding included, wherever it appears), unpadded tails of every length mod 4, and a one-digit tail
   (one byte of four times the digit). On a host that plugs nothing the two paths are the same code, and the
   corpus then pins the contract itself (with the RFC 4648 known answers). The string conversions the byte
   path reads through (eve.encoding/utf16-units, code-points->string) are pinned end to end over Unicode."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.encoding :as e]
            [eve.net.bytes :as b]))

(def ^:private field-8k
  "an 8 KB field of deterministic bytes (the size ET-3 profiled on basilisp)"
  (mapv (fn [i] (mod (* i 37) 256)) (range 8192)))

(def ^:private byte-corpus
  [[] [0] [255] [0 0 0] [255 254 253] [102] [102 111] [102 111 111]
   (vec (range 256)) (vec (reverse (range 256))) field-8k])

(def ^:private b64-text-corpus
  ["" "QQ==" "QQ" "QQQ" "QQQQQ" "QQ==QQ" "a b\nc!" "####" "====" "QUJD" "QUJDRA" "QUJDRA==" "QUJDRA="
   "Zg==\nZg==" "Zm9v\r\nYmFy" (b/to-b64-portable field-8k)])

(def ^:private hex-text-corpus
  ["" "00" "ff" "FF" "0123456789abcdef" "deadBEEF" (b/hex-portable field-8k)])

(defn- throws? [f]
  (try (f) false (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) _ true)))

(deftest known-answers
  ;; RFC 4648's vectors, as byte vectors
  (is (= "" (b/to-b64 [])))
  (is (= "Zg==" (b/to-b64 [102])))
  (is (= "Zm8=" (b/to-b64 [102 111])))
  (is (= "Zm9v" (b/to-b64 [102 111 111])))
  (is (= "Zm9vYg==" (b/to-b64 [102 111 111 98])))
  (is (= "Zm9vYmE=" (b/to-b64 [102 111 111 98 97])))
  (is (= "Zm9vYmFy" (b/to-b64 [102 111 111 98 97 114])))
  (is (= "00ff10" (b/hex [0 255 16])))
  (is (= [0 255 16] (b/unhex "00ff10"))))

(deftest plug-matches-portable-on-bytes
  (doseq [bs byte-corpus]
    (testing (str (count bs) " bytes")
      (is (= (b/to-b64-portable bs) (b/to-b64 bs)) "base64 encode")
      (is (= (b/hex-portable bs) (b/hex bs)) "hex encode")
      (is (= bs (b/from-b64 (b/to-b64 bs))) "base64 round trip through the dispatched path")
      (is (= bs (b/unhex (b/hex bs))) "hex round trip through the dispatched path"))))

(deftest plug-matches-portable-on-text
  (doseq [s b64-text-corpus]
    (testing (str "b64 text of " (count s) " chars")
      (is (= (b/from-b64-portable s) (b/from-b64 s)) "base64 decode")))
  (doseq [s hex-text-corpus]
    (testing (str "hex text of " (count s) " chars")
      (is (= (b/unhex-portable s) (b/unhex s)) "hex decode"))))

(deftest invalid-hex-throws-on-both-paths
  (doseq [s ["abc" "zz" "a b" "ab c" "0g"]]
    (testing (pr-str s)
      (is (throws? (fn [] (b/unhex-portable s))) "portable throws")
      (is (throws? (fn [] (b/unhex s))) "dispatched path throws"))))

(deftest slot-state
  ;; what this host plugged, for the report the run prints: all four, or none
  (let [plugged (filter (fn [[_ v]] (some? v))
                        {:bytes->b64 e/bytes->b64 :b64->bytes e/b64->bytes
                         :bytes->hex e/bytes->hex :hex->bytes e/hex->bytes})]
    (is (or (empty? plugged) (= 4 (count plugged))) "a host plugs the whole surface or none of it")))

(deftest string-conversions-round-trip
  (doseq [s ["" "hello" "héllo wörld" "αβγ λ" "你好世界" "🙂🚀" "a🙂b" "line\nbreak\ttab"]]
    (testing (pr-str s)
      (is (= s (b/units->str (e/utf16-units s))) "utf16 units round trip")
      (is (= s (b/utf8->str (b/utf8 s))) "utf8 round trip"))))
