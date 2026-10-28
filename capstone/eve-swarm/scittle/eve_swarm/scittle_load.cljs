(ns eve-swarm.scittle-load
  "What Scittle (SCI in a browser, babashka/scittle) lacks to run eve from source, supplied from Clojure inside it: a
   loader. Scittle evaluates script tags and strings; its require finds only a namespace already evaluated (or a
   JavaScript library on globalThis) and loads no file. load! reads a namespace's source from the page's origin —
   eve's tree served at /eve/ — in the first root that has it (eve's src/, then hosts.edn :scittle, then
   capstone/eve-swarm/scittle; .cljs, then .cljc), synchronously (a worker's XMLHttpRequest may be), and evaluates it
   after what its ns form needs: a require Scittle cannot answer (\"Could not find namespace: x.\") is loaded and the
   file evaluated again from its ns form, so a namespace's requires load first, in its ns form's order.

   What eve's JavaScript adapters name and Scittle does not is supplied here, as Scittle supplies cljs.core's Cons:
   cljs.core's types MapEntry (eve's JS encoding adapter builds a map entry as (MapEntry. k v nil)) and ExceptionInfo
   (eve's browser thread adapter names an error's class with (instance? ExceptionInfo e)) by name; cljs.core's uuid
   (eve's JS codec reads a uuid from its hex), as parse-uuid of the same string; and the namespace cljs.reader (the
   thread adapter reads a worker's messages with it) as clojure.edn's reader — which Scittle's
   clojure.core/read-string is."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(intern 'clojure.core 'MapEntry (type (first {0 0})))
(intern 'clojure.core 'ExceptionInfo (type (ex-info "" {})))
(intern 'clojure.core 'uuid (fn uuid [s] (parse-uuid s)))

(defn- fetch
  "the text at path on this page's origin, or nil"
  [path]
  (let [x (js/XMLHttpRequest.)]
    (.open x "GET" path false)
    (.send x)
    (when (= 200 (.-status x)) (.-responseText x))))

(def ^:private roots
  (delay (concat ["src"] (:scittle (edn/read-string (fetch "/eve/hosts.edn"))) ["capstone/eve-swarm/scittle"])))

(defn- source-of
  "the source text of namespace n in the first root that has it, or nil"
  [n]
  (let [base (-> (str n) (str/replace "-" "_") (str/replace "." "/"))]
    (some (fn [r] (some (fn [ext] (fetch (str "/eve/" r "/" base ext))) [".cljs" ".cljc"])) @roots)))

(def ^:private SHIMS
  {'cljs.reader "(ns cljs.reader (:require [clojure.edn :as edn])) (def read-string edn/read-string) (def read edn/read)"})

(defn- missing
  "the namespace an error says Scittle could not find, or nil"
  [e]
  (some->> (re-find #"Could not find namespace:? (\S+)" (str (ex-message e))) second (re-find #"^(.*?)\.?$") second
           symbol))

(def ^:private loaded (atom #{}))

(def times "ms each namespace's own evaluation took (its requires' loads apart), in load order" (atom []))

(defn load!
  "evaluate namespace n from source, what it requires first"
  [n]
  (when-not (contains? @loaded n)
    (let [text (or (get SHIMS n) (source-of n)
                   (throw (ex-info (str "eve-swarm: no source for namespace " n " under " (str/join " " @roots))
                                   {:ns n})))]
      (loop [tries 0]
        (let [t0 (js/Date.now)
              need (try (load-string text) nil
                        (catch :default e
                          (let [m (missing e)]
                            (if (and m (not= m n) (< tries 100))
                              m
                              (throw (js/Error. (str "loading " n ": " (or (ex-message e) e))))))))]
          (if need
            (do (load! need) (recur (inc tries)))
            (swap! times conj [(str n) (- (js/Date.now) t0)])))))
    (swap! loaded conj n))
  n)
