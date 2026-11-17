(require 'clojure.test)
(let [nss (map symbol (seq (.split (System/getProperty "eve.test.ns" "eve.flat.sister-test") ",")))]
  (doseq [n nss] (require n))
  (let [{:keys [fail error] :as r} (apply clojure.test/run-tests nss)]
    (println :RESULT (select-keys r [:test :pass :fail :error]))
    (System/exit (if (zero? (+ fail error)) 0 1))))
