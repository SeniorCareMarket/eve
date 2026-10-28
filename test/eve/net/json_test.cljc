(ns eve.net.json-test
  "eve.net.json: the broker's small JSON bodies, portable. The reader is total — read-str-or-nil answers nil for text
   that is not JSON, so eve.net catches nothing (eve/CLAUDE.md, \"eve is portable\") — and strict about the grammar."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.encoding :as e]
            [eve.net.json :as j]))

(deftest reads-json
  (is (= {"a" 1 "b" [true false nil "xé\n"] "c" {"d" -25.0}}
         (j/read-str "{\"a\":1,\"b\":[true,false,null,\"x\\u00e9\\n\"],\"c\":{\"d\":-2.5e1}}")))
  (is (= [] (j/read-str "[]")))
  (is (= {} (j/read-str "{}")))
  (is (= 42 (j/read-str "  42 ")))
  (is (= [1 [2 3] {}] (j/read-str "[1,[2,3],{}]"))))

(deftest malformed-is-nil
  (testing "every truncation, a bad escape, trailing text, unquoted keys and trailing commas answer nil"
    (doseq [t ["" "{" "{\"a\"" "{\"a\":" "{\"a\":1" "[1,2" "tru" "nul" "\"abc" "\"\\u12\"" "{\"a\":1}x" "{a:1}" "-"
               "[1,]" "{\"a\":1,}"]]
      (is (nil? (j/read-str-or-nil t)) t))))

(deftest read-str-throws-on-malformed
  ;; the message's start: glojure's ex-message appends its stack to the message
  (is (= "json: malformed text"
         (let [m (try (j/read-str "{") nil (catch #?(:cljs :default :lpy python/Exception :default Exception) e (ex-message e)))]
           (subs m 0 (min (count m) 20))))))

(deftest round-trips
  (is (= {"k" [1 "two" nil true] "m" {"n" 2}} (j/read-str (j/write-str {:k [1 "two" nil true] :m {:n 2}})))))

(deftest text-past-the-bmp
  (testing "a \\u surrogate-pair escape reads as ONE code point on every host (a JVM or JavaScript string holds it as
            two units, a Python or clojerl string as one character), and such a string round-trips"
    (let [clef (e/code-points->string [0x1D11E])
          mixed (e/code-points->string [0x78 0xE9 0x1D11E 0x2713])]
      (is (= clef (j/read-str "\"\\ud834\\udd1e\"")))
      (is (= mixed (j/read-str (j/write-str mixed))))
      (is (= {"k" [mixed]} (j/read-str (j/write-str {"k" [mixed]}))))
      (is (= [0xD834 0xDD1E] (e/utf16-units clef)) "utf16-units answers UTF-16 on every host"))))
