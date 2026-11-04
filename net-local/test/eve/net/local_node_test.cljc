(ns eve.net.local-node-test
  "The app host's static files (eve.net.local-node/static-handler) over real HTTP: GET / answers the page, a file under
   the directory its bytes, a path out of it nothing."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [eve.http-server :as hs]
            [eve.net.local-node :as node]
            [eve.net.platform :as platform]))

(deftest get-slash-answers-the-page
  (let [dir (str (System/getProperty "java.io.tmpdir") "/eve-net-local-" (rand-int 1000000000))
        _ (.mkdirs (java.io.File. (str dir "/js")))
        _ (spit (str dir "/index.html") "<!doctype html><title>eve local network</title>")
        _ (spit (str dir "/js/main.js") "console.log(1)")
        h (node/static-handler dir)
        srv (hs/serve! {:port 0} (fn [req] (or (h req) {:status 404 :headers {} :body "no"})))
        get! (fn [p] ((:request (platform/platform)) {:method "GET" :headers {} :url (str "http://127.0.0.1:" (:port srv) p)}))]
    (try
      (let [r (get! "/")]
        (is (= 200 (:status r)))
        (is (str/includes? (:body r) "eve local network")))
      (is (= "console.log(1)" (:body (get! "/js/main.js"))))
      (is (= 404 (:status (get! "/../etc/passwd"))))
      (is (= 404 (:status (get! "/nope.html"))))
      (finally ((:stop srv))))))
