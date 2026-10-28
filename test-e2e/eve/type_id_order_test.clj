(ns eve.type-id-order-test
  "An eve/deftype user type's id is the same in every process (L19 C8,
   FORMAT-DECISIONS §7): two JVMs define the same two user types, Alpha and
   Beta, in opposite orders and exchange a block of each through a file-backed
   atom; the second reads each as its own type. A load-order counter gave the
   first-loaded type 64 in each process, so the second read Alpha's block as a
   Beta."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn])
  (:import [java.lang ProcessBuilder]))

(defn- child! [& args]
  (let [cmd (into ["java" "--enable-native-access=ALL-UNNAMED" "--add-opens" "java.base/java.lang=ALL-UNNAMED"
                   "-cp" (System/getProperty "java.class.path") "clojure.main" "-m" "eve.type-id-order-child"]
                  args)
        proc (.start (ProcessBuilder. ^java.util.List cmd))
        out  (future (slurp (.getInputStream proc)))
        err  (future (slurp (.getErrorStream proc)))]
    (assert (zero? (.waitFor proc)) (str "child " args " failed: " @err))
    (edn/read-string (last (clojure.string/split-lines @out)))))

(deftest two-processes-opposite-orders
  (let [base (str "/tmp/eve-type-id-order-" (System/currentTimeMillis))
        w    (child! "alpha,beta" base "create")
        r    (child! "beta,alpha" base "join")]
    (testing "both processes derive the same id for each type"
      (is (= (:ids w) (:ids r))))
    (testing "the joiner reads each block as its own type"
      (is (= {:alpha ["Alpha" 7] :beta ["Beta" 8]} (:read r))))))
