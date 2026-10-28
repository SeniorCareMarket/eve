(ns eve.net.codec-compat-test
  "The kernel's own runtime numbers in a slot (ExtCID v3: eve.vs _tier2_span, a scalar imaged inline as [type CID 16]
   [payload][zeros]) read back through eve.net.codec as the numbers they hold: the FLOAT64 box (CID f6×8 f4×8, the double
   @16) and the LONG box (CID f5×8 f3×8, the i64 @16) — boxes the kernel writes and eve's codec never does. (The v2
   images this file read before ExtCID v3 — the pre-narrowing corpus vsv2-blocks-256.edn and landing A's blocks
   vsv2-landing-a.edn — left with the v2 readers: FORMAT.md §2.3, a v2 header is refused by every v3 reader.)"
  (:require [clojure.test :refer [deftest is]]
            [eve.net.bytes :as b]
            [eve.net.codec :as c]))

(deftest the-runtime-number-bands
  ;; the kernel's FLOAT64 band (CID f6x8 f4x8, the double @16) and LONG band (CID f5x8 f3x8, the i64 @16), and the zero band
  (let [z8 (vec (repeat 8 0))
        f64 (fn [d] (c/value-of-band (vec (concat (b/unhex "f6f6f6f6f6f6f6f6f4f4f4f4f4f4f4f4") (c/f64-le d) z8)) {}))
        i64 (fn [bs] (c/value-of-band (vec (concat (b/unhex "f5f5f5f5f5f5f5f5f3f3f3f3f3f3f3f3") bs z8)) {}))]
    (is (= 1760000000000 (f64 1.76E12)) "an integral float64: the integer")
    (is (= 0.5 (f64 0.5)) "a fraction: the double")
    (is (= -3 (f64 -3.0)))
    (is (float? (f64 1.0E300)) "past 2^53: the double")
    (is (= 4294967296 (i64 [0 0 0 0 1 0 0 0])))
    (is (= -1 (i64 [255 255 255 255 255 255 255 255])))))

(deftest a-neg-zero-map-key-round-trips
  ;; MD-4 defect 4 (buck bucket P1 L81): a number key is its f64 bytes in the tree; key-of-bytes collapsed every
  ;; integral double to a long, so {-0.0 :x} decoded as {0 :x} — a different map whose own key misses. -0.0 is the
  ;; one integral double the long 0 does not re-encode byte-exactly (the sign bit), and it now decodes as the
  ;; double it is. The integral positives still canonicalize to longs, exactly as scalar values do
  ;; (the-runtime-number-bands): one number model for keys and values alike.
  (let [rt (fn [v] (let [enc (c/encode v)]
                     (first (c/decode-rec (b/unhex (:root enc)) (:blocks enc)))))]
    (is (= {-0.0 :x} (rt {-0.0 :x})) "-0.0 survives as a key")
    (is (= :x (get (rt {-0.0 :x}) -0.0)) "and answers its own lookup")
    (is (= {0 :x} (rt {0.0 :x})) "+0.0 canonicalizes to the long 0, as a value does")
    (is (= {5 :x} (rt {5.0 :x})) "an integral double key canonicalizes to the long")
    (is (= {5 :x} (rt {5 :x})))
    (is (= {-0.5 :x} (rt {-0.5 :x})) "a fractional double key is untouched")))

