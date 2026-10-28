;; node_http_server.cljs — eve's http-server feature on Node (adapters/http-server/node, change list 4.3): serve! on a
;; free port, one POST carrying text and one GET answered with :body-as :b64 bytes, then :stop; prints what came back.
;;   nbb --classpath adapters/http-server/node/src node_http_server.cljs     (from eve/)
(ns node-http-server (:require [eve.http-server :as hs]))
(def seen (atom []))
(def srv (hs/serve! {:port 0}
                    (fn [req]
                      (swap! seen conj (select-keys req [:method :url :body :body-b64]))
                      (if (= "GET" (:method req))
                        {:status 200 :headers {"x-eve" "b64"} :body (.toString (js/Buffer.from #js [0 1 2 255]) "base64") :body-as :b64}
                        {:status 201 :headers {"x-eve" "text"} :body (str "got " (:body req))}))))
(-> (:bound srv)
    (.then (fn [port]
             (println "bound port:" (pos? port))
             (-> (js/fetch (str "http://127.0.0.1:" port "/v1/atoms/a%2Fb?x=1") #js {:method "POST" :body "hello"})
                 (.then (fn [r] (println "POST status" (.-status r) "x-eve" (.get (.-headers r) "x-eve")) (.text r)))
                 (.then (fn [t] (println "POST body" (pr-str t))
                          (js/fetch (str "http://127.0.0.1:" port "/blobs/x"))))
                 (.then (fn [r] (println "GET status" (.-status r) "x-eve" (.get (.-headers r) "x-eve")) (.arrayBuffer r)))
                 (.then (fn [ab] (println "GET bytes" (vec (js/Array.from (js/Uint8Array. ab)))))))))
    (.then (fn [_] (println "handler saw" (pr-str (mapv #(dissoc % :body-b64) @seen)))
             (println "POST :body-b64" (pr-str (:body-b64 (first @seen))))
             ((:stop srv)) (println "stopped")))
    (.catch (fn [e] (println "FAILED" (str e)) (js/process.exit 1))))
