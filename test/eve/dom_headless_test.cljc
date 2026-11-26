(ns eve.dom-headless-test
  "eve.dom over the headless executor: eve.dom.core's ops — a guest's proxies, property reads and writes, method calls,
   queued calls, globals, errors and callbacks — answered by eve.dom.headless's model document in-process, the same
   frames a page's executor answers."
  (:require [clojure.test :refer [deftest is testing]]
            [eve.dom.core :as core]
            [eve.dom.headless :as hl]))

(def ^:private proxies (atom {}))

(defn- meta-of [x] (when (and (map? x) (contains? x ::meta)) (::meta x)))

(defn- proxy-for [h kind ty]
  (or (get @proxies h)
      (let [p {::meta (core/new-meta h kind ty)}]
        (swap! proxies assoc h p)
        p)))

(def ^:private host
  {:send (fn [frame] (hl/exec-frame frame))
   :defer (fn [f] (f))
   :proxy proxy-for
   :meta-of meta-of
   :undefined (fn [] nil)
   :undefined? (fn [_] false)
   :array? vector?
   :array-items identity
   :make-array identity
   :object? (fn [x] (and (map? x) (not (contains? x ::meta))))
   :object-items (fn [x] (mapv (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) x))
   :make-object (fn [kvs] (into {} kvs))
   :error (fn [nm msg] (ex-info msg {:name nm}))
   :describe str
   :call (fn [f args] (apply f args))})

(defn- fresh! []
  (reset! proxies {})
  (hl/reset-page!)
  (core/attach! host (* 64 1024) :headless))

(defn- run-calls! []
  (doseq [[id args] (hl/take-calls!)] (core/run-callback! id args)))

(defn- get* [o k] (core/get-prop (meta-of o) k))
(defn- call [o k & args] (core/call-method (meta-of o) k args))
(defn- set*! [o k v] (core/op-set! (meta-of o) k v) (core/flush!))

(deftest document-and-elements
  (fresh!)
  (let [doc (core/document)
        app (call doc "getElementById" "app")
        div (call doc "createElement" "div")]
    (testing "the document's #app; a new element; attributes in the order set; innerHTML"
      (is (some? (meta-of app)))
      (call div "setAttribute" "class" "a b")
      (call div "setAttribute" "id" "x")
      (call div "appendChild" (call doc "createTextNode" "hi & <you>"))
      (call app "appendChild" div)
      (is (= "<div class=\"a b\" id=\"x\">hi &amp; &lt;you&gt;</div>" (get* app "innerHTML")))
      (is (= "hi & <you>" (get* div "textContent")))
      (is (= true (get* div "isConnected"))))
    (testing "one proxy per node; selectors"
      (is (identical? div (call doc "querySelector" "#x")))
      (is (identical? div (call app "querySelector" "div.a.b")))
      (is (identical? div (call doc "querySelector" "body #app .b")))
      (is (= 1 (count (call doc "querySelectorAll" "div.a")))))
    (testing "properties: id and className reflect; value set apart from its attribute; disabled reflects"
      (let [in (call doc "createElement" "input")]
        (call in "setAttribute" "value" "Hello")
        (set*! in "value" "I typed")
        (is (= "I typed" (get* in "value")))
        (is (= "Hello" (call in "getAttribute" "value")))
        (set*! in "disabled" true)
        (is (= true (get* in "disabled")))
        (is (= "" (call in "getAttribute" "disabled")))
        (call app "appendChild" in)
        (is (re-find #"<input value=\"Hello\" disabled=\"\">" (get* app "innerHTML")) "a void element")))
    (testing "classList and style write the attributes"
      (let [cl (get* div "classList")]
        (call cl "add" "c")
        (call cl "remove" "a")
        (is (= "b c" (call div "getAttribute" "class"))))
      (let [s (get* div "style")]
        (call s "setProperty" "color" "red")
        (is (= "color: red;" (call div "getAttribute" "style")))
        (is (= "red" (call s "getPropertyValue" "color")))))
    (testing "svg in its namespace"
      (let [svg (call doc "createElementNS" hl/SVG "svg")]
        (call svg "setAttribute" "width" "10")
        (call svg "appendChild" (call doc "createElementNS" hl/SVG "rect"))
        (is (= "<svg width=\"10\"><rect></rect></svg>" (get* svg "outerHTML")))))
    (testing "a page error is thrown here with its name (an answering call: a queued one's error is counted, as on a page)"
      (let [e (try (call doc "querySelector" "[[") nil (catch #?(:clj Throwable :cljs :default :default :default) e e))]
        (is (some? e))))
    (testing "remove and replace"
      (let [p (call doc "createElement" "p")
            q (call doc "createElement" "q")]
        (call app "appendChild" p)
        (call app "replaceChild" q p)
        (is (nil? (get* p "parentNode")))
        (is (identical? app (get* q "parentNode")))
        (call app "removeChild" q)
        (is (= false (get* q "isConnected")))))))

(deftest listeners-and-callbacks
  (fresh!)
  (let [doc (core/document)
        btn (call doc "createElement" "button")
        hits (atom [])
        f (fn [e] (swap! hits conj (get* e "type")))]
    (call (core/document) "addEventListener" "click" (fn [_] (swap! hits conj :doc)))
    (call (call doc "getElementById" "app") "appendChild" btn)
    (call btn "addEventListener" "click" f)
    (call btn "addEventListener" "click" f)
    (call btn "click")
    (core/flush!)
    (run-calls!)
    (is (= ["click" :doc] @hits) "one listener per function; the click bubbles to the document")
    (testing "an animation frame calls back with the page's clock"
      (let [ts (atom nil)]
        (call (core/window) "requestAnimationFrame" (fn [t] (reset! ts t)))
        (core/flush!)
        (hl/tick-animation-frame!)
        (run-calls!)
        (is (number? @ts))))))
