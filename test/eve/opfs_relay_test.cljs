(ns eve.opfs-relay-test
  "eve-sw.js's follower relay (the OPFS disk tier spec, W9), driven by hand from a worker of the test page, which eve's
   service worker controls: a call no host takes is answered 504 and sent again under its key is queued once; a host's
   poll takes it, and its reply answers the call (kept for a caller that comes back later); a call a host of an older
   term took and never answered goes to the next term's host; and eve.opfs.relay's call! round-trips with its serve! (a host in another worker)."
  (:require
   [cljs.test :refer [deftest is testing]]
   [eve.opfs.relay :as relay]
   [eve.thread-host :as h]))

(defn- xhr [method path body]
  (let [x (js/XMLHttpRequest.)]
    (.open x method (str (.-origin js/location) path) false)
    (set! (.-responseType x) "arraybuffer")
    (if body (.send x body) (.send x))
    {:status (.-status x)
     :key (.getResponseHeader x "X-Eve-Key")
     :text (when (= 200 (.-status x)) (.decode (js/TextDecoder.) (js/Uint8Array. (.-response x))))}))

(defn- utf8 [s] (.encode (js/TextEncoder.) s))

(deftest the-relay-queues-once-answers-once-and-redelivers
  (if-not (relay/relayed?)
    (is true "this worker's synchronous XHRs do not reach the service worker: the relay is not tested here")
    (let [host (str "relay-test-" (js/Date.now))
          call (fn [k ms] (xhr "POST" (str "/eve-sw/opfs/call?to=" host "&k=" k "&ms=" ms) (utf8 (str "req " k))))
          poll (fn [term] (xhr "GET" (str "/eve-sw/opfs/next?host=" host "&term=" term "&ms=50") nil))
          reply (fn [k s] (xhr "POST" (str "/eve-sw/opfs/reply?k=" k) (utf8 s)))]
      (testing "a call no host takes is answered 504; sent again under its key, it is queued once"
        (is (= 504 (:status (call "k1" 30))))
        (is (= 504 (:status (call "k1" 30)))))
      (testing "a host's poll takes it once; the next poll finds nothing"
        (let [n (poll 1)]
          (is (= [200 "k1" "req k1"] [(:status n) (:key n) (:text n)])))
        (is (= 204 (:status (poll 1)))))
      (testing "the host's reply answers the caller that comes back for it"
        (is (= 204 (:status (reply "k1" "answer 1"))))
        (is (= [200 "answer 1"] ((juxt :status :text) (call "k1" 30)))))
      (testing "a call an older term's host took and never answered goes to the next term's host"
        (is (= 504 (:status (call "k2" 30))))
        (is (= "k2" (:key (poll 1))))
        (is (= 204 (:status (poll 1))) "the same term is not handed it again")
        (let [n (poll 2)]
          (is (= [200 "k2"] [(:status n) (:key n)])))
        (reply "k2" "answer 2")
        (is (= "answer 2" (:text (call "k2" 30))))))))

(defn echo-host
  "A worker's entry (an eve thread the page starts, so its event loop runs while the test's worker blocks): serve
   host's calls with eve.opfs.relay/serve!, each answered with its bytes and a \"!\", until n are answered."
  [{:keys [host n]}]
  (js/Promise.
   (fn [resolve _]
     (let [left (atom n)
           stop (atom nil)]
       (reset! stop (relay/serve! host 1
                                  (fn [^js req]
                                    (let [out (js/Uint8Array. (inc (.-length req)))]
                                      (.set out req)
                                      (aset out (.-length req) 33)
                                      (when (zero? (swap! left dec)) (js/setTimeout (fn [] (@stop) (resolve nil)) 0))
                                      out))))))))

(deftest call-and-serve-round-trip
  (if-not (relay/relayed?)
    (is true "this worker's synchronous XHRs do not reach the service worker: the relay is not tested here")
    (let [host (str "relay-echo-" (js/Date.now))]
      (h/start-threads! 1 'eve.opfs-relay-test/echo-host {:host host :n 3} {})
      (let [answers (mapv (fn [s] (.decode (js/TextDecoder.) (relay/call! host (utf8 s) {:timeout-ms 20000})))
                          ["a" "bb" "ccc"])]
        (is (= ["a!" "bb!" "ccc!"] answers) "each call answered by the host's reply, in order")))))
