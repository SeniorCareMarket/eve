(ns eve.dom-test
  "eve.dom's conformance, run in a program's main worker (eve.runner.dom-browser-main): the page's DOM driven
   through the proxies, every answer from the page's own thread."
  (:require [cljs.test :refer [deftest is testing async]]
            [eve.dom.proxy :as proxy]))

(defn- el [tag] (.createElement js/document tag))

(defn- fresh-box []
  (let [b (el "div")]
    (.appendChild (.-body js/document) b)
    b))

(deftest create-query-identity
  (let [b (fresh-box)
        d (el "div")]
    (set! (.-id d) "eve-dom-one")
    (.appendChild b d)
    (is (identical? d (.querySelector js/document "#eve-dom-one")) "one proxy per page object")
    (is (identical? b (.-parentNode d)))
    (is (identical? js/document (.-ownerDocument d)))
    (is (identical? js/window (.-defaultView js/document)))
    (is (= "DIV" (.-tagName d)))
    (is (= 1 (.-length (.-childNodes b))))))

(deftest attributes-and-class-list
  (let [d (fresh-box)]
    (.setAttribute d "data-x" "7")
    (is (= "7" (.getAttribute d "data-x")))
    (is (nil? (.getAttribute d "data-none")))
    (.add (.-classList d) "a" "b")
    (is (true? (.contains (.-classList d) "b")))
    (is (= "a b" (.-className d)))
    (.removeAttribute d "data-x")
    (is (false? (.hasAttribute d "data-x")))))

(deftest style-and-computed-style
  (let [d (fresh-box)]
    (set! (.. d -style -color) "rgb(255, 0, 0)")
    (.setProperty (.-style d) "width" "33px")
    (let [cs (js/getComputedStyle d)]
      (is (= "rgb(255, 0, 0)" (.-color cs)))
      (is (= "33px" (.getPropertyValue cs "width"))))))

(deftest instance-checks
  (let [d (el "div")
        c (el "canvas")
        t (.createTextNode js/document "x")
        s (.createElementNS js/document "http://www.w3.org/2000/svg" "svg")]
    (is (instance? js/HTMLElement d))
    (is (instance? js/Element d))
    (is (instance? js/Node d))
    (is (not (instance? js/HTMLCanvasElement d)))
    (is (instance? js/HTMLCanvasElement c))
    (is (instance? js/Text t))
    (is (not (instance? js/Element t)))
    (is (instance? js/SVGElement s))
    (is (not (instance? js/HTMLElement s)))
    (is (not (fn? (.. js/HTMLCanvasElement -prototype -transferControlToOffscreen))))))

(deftest page-errors-are-thrown-here
  (let [e (try (.querySelector js/document "!!") nil (catch :default e e))]
    (is (some? e))
    (is (= "SyntaxError" (.-name e))))
  (testing "the queue survives a failed call"
    (let [d (fresh-box)]
      (set! (.-title d) "ok")
      (is (= "ok" (.-title d))))))

(deftest sync-callbacks-are-refused
  (let [d (fresh-box)
        e (try (.getComputedStyle js/window d (fn [] 1)) nil (catch :default e e))]
    (is (= :sync-callback (:eve.dom/refused (ex-data e))))
    (set! (.-title d) "after")
    (is (= "after" (.-title d)) "a refused op leaves nothing behind in the queue")))

(deftest local-properties
  (let [d (fresh-box)
        v #js {:tag "x"}]
    (aset d "reagami.core/vnode" v)
    (set! (.-__data__ d) 42)
    (is (identical? v (aget d "reagami.core/vnode")))
    (is (= 42 (.-__data__ d)))
    (is (identical? v (aget (.-lastChild (.-parentNode d)) "reagami.core/vnode")) "kept on the one proxy")))

(deftest click-listener
  (async done
         (let [b (fresh-box)
               seen (atom [])]
      ;; the worker runs a listener after the page's dispatch is over: target stays, currentTarget is gone
           (.addEventListener b "click" (fn [e]
                                          (swap! seen conj (.-type e))
                                          (is (identical? b (.-target e)))
                                          (when (= 1 (count @seen))
                                            (set! (.-onclick b) (fn [_]
                                                                  (swap! seen conj "prop")
                                                                  (is (= ["click" "click" "prop"] @seen))
                                                                  (done)))
                                            (.click b))))
           (.click b))))

(deftest animation-frame
  (async done
         (js/requestAnimationFrame (fn [ts]
                                     (is (number? ts))
                                     (done)))))

(deftest mutation-observer
  (async done
         (let [b (fresh-box)
               mo (js/MutationObserver. (fn [records _]
                                          (is (pos? (.-length records)))
                                          (is (= "childList" (.-type (aget records 0))))
                                          (done)))]
           (.observe mo b #js {:childList true})
           (.appendChild b (el "span")))))

(deftest large-frames
  (let [d (fresh-box)
        html (apply str (repeat 20000 "<i>xxxxxxx</i>"))]
    (set! (.-innerHTML d) html)
    (is (= (count html) (count (.-innerHTML d))) "a request and a reply larger than a slot")
    (is (= 20000 (.-length (.-children d))))))

(deftest many-queued-ops
  (let [d (fresh-box)
        before (:frames (proxy/stats))]
    (dotimes [i 5000] (.setAttribute d "data-i" (str i)))
    (is (= "4999" (.getAttribute d "data-i")))
    (is (< (- (:frames (proxy/stats)) before) 60) "queued in a few frames, not one each")))

(deftest page-globals
  (let [M (proxy/page-global "Map")
        m (new M)]
    (is (= "Map" (proxy/type-of m)))
    (.set m "k" 3)
    (is (= 3 (.get m "k")))
    (is (undefined? (.get m "nope")))))

(deftest transport
  (is (contains? #{:sab :sw} (proxy/transport-kind))))
