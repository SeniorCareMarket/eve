(ns eve.dom.headless
  "eve.dom's headless executor: a page with no browser — an in-process document that answers eve.dom.core's request
   frames exactly as the page's executor (eve.dom.exec) does, over a model of the DOM. A guest with no page (a test, a
   server-side render, a ValueScript program run without the browser host) attaches it as its host's :send, and its
   proxies, ops and callbacks work unchanged.

   THE MODEL: every object a guest can name is a node of the model — the window (handle 1), the document (handle 2),
   elements, text nodes, an element's style and classList, events, page functions, and the stubs of guest callbacks —
   each an atom of a map with :class, registered under an integer handle on first sight (:h in the map), so a guest
   sees one proxy per object. A property read is the class's getter, else the object's own property; a method is the
   class's method table (read by GET as the method marker, called by OP-CALL / OP-CALLQ).

   What a page does, the model does: attributes in the order they were set (innerHTML serializes them so), the
   properties that reflect attributes (id, className, disabled) and the ones that do not once the user (or a program)
   set them (value, checked, selected), text escaping, void elements, svg children in the svg namespace, selectors
   (tag, #id, .class, [attr], [attr=v], compound, descendant), listeners added once per function, events with
   preventDefault. NOT modelled: layout, computed style, CSS transitions, HTML parsing (an innerHTML write of markup is
   kept as text).

   CALLBACKS: a guest function crosses as a callback id; the model holds a stub and, when the page would call it (a
   listener, an animation frame), files the call. take-calls! hands the filed calls to the guest's host, which runs
   each with eve.dom.core/run-callback! after the frame that filed it has returned — as a page's posted callbacks
   arrive after its reply."
  (:require [clojure.string :as str]
            [eve.dom.codec :as c]))

;; ---------------------------------------------------------------------------------------------------------------
;; the registry

(defonce ^:private st (atom {:objs {} :next 16 :calls [] :t0 nil :raf [] :stubs {}}))

(defn- now-ms []
  #?(:cljs (js/Date.now) :default (System/currentTimeMillis)))

(defn- register! [o]
  (or (:h @o)
      (let [h (:next @st)]
        (swap! st (fn [s] (-> s (assoc :next (inc h)) (assoc-in [:objs h] o))))
        (swap! o assoc :h h)
        h)))

(defn- put-at! [h o]
  (swap! o assoc :h h)
  (swap! st assoc-in [:objs h] o)
  h)

(defn- lookup [h]
  (or (get-in @st [:objs h])
      (throw (ex-info (str "eve.dom: no page object under handle " h " (released)") {:name "Error"}))))

(defn- page-error [nm msg] (ex-info msg {:name nm}))

(defn- obj [m] (atom m))

(defn- model? [v] #?(:vs (atom? v) :clj (instance? clojure.lang.Atom v) :cljs (instance? Atom v)))

;; ---------------------------------------------------------------------------------------------------------------
;; nodes

(def SVG "http://www.w3.org/2000/svg")
(def ^:private VOID #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "source" "track" "wbr"})

(defn- element [tag ns]
  (obj {:class :element :tag (if ns tag (str/lower-case tag)) :ns ns :attrs [] :props {} :children [] :parent nil
        :listeners {}}))

(defn- text-node [s] (obj {:class :text :text (str s) :parent nil :listeners {}}))

(defn- attr [n k] (some (fn [[ak av]] (when (= ak k) av)) (:attrs @n)))

(defn- set-attr! [n k v]
  (let [v (str v)]
    (swap! n update :attrs (fn [as] (if (some (fn [[ak _]] (= ak k)) as)
                                      (mapv (fn [[ak av]] (if (= ak k) [ak v] [ak av])) as)
                                      (conj as [k v]))))))

(defn- remove-attr! [n k] (swap! n update :attrs (fn [as] (filterv (fn [[ak _]] (not= ak k)) as))))

(defn- detach! [child]
  (when-let [p (:parent @child)]
    (swap! p update :children (fn [cs] (filterv (fn [x] (not (identical? x child))) cs)))
    (swap! child assoc :parent nil)))

(defn- insert! [parent child ref]
  (when (identical? :fragment (:class @child))
    (throw (page-error "NotSupportedError" "eve.dom headless: document fragments are not modelled")))
  (when-not (contains? #{:element :text} (:class @child))
    (throw (page-error "TypeError" "Failed to execute 'appendChild' on 'Node': parameter 1 is not of type 'Node'.")))
  (detach! child)
  (swap! parent update :children
         (fn [cs] (if ref
                    (let [i (first (keep-indexed (fn [i x] (when (identical? x ref) i)) cs))]
                      (when (nil? i)
                        (throw (page-error "NotFoundError" "The node before which the new node is to be inserted is not a child of this node.")))
                      (vec (concat (subvec cs 0 i) [child] (subvec cs i))))
                    (conj cs child))))
  (swap! child assoc :parent parent)
  child)

(defn- text-of [n]
  (if (= :text (:class @n)) (:text @n) (apply str (map text-of (:children @n)))))

(defn- esc-text [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
(defn- esc-attr [s] (-> (str s) (str/replace "&" "&amp;") (str/replace "\"" "&quot;")))

(declare outer-html)

(defn- inner-html [n] (apply str (map outer-html (:children @n))))

(defn- outer-html [n]
  (let [m @n]
    (if (= :text (:class m))
      (esc-text (:text m))
      (let [tag (:tag m)
            as (apply str (map (fn [[k v]] (str " " k "=\"" (esc-attr v) "\"")) (:attrs m)))]
        (if (and (nil? (:ns m)) (contains? VOID tag))
          (str "<" tag as ">")
          (str "<" tag as ">" (inner-html n) "</" tag ">"))))))

(defn- descendants [n]
  (mapcat (fn [ch] (when (= :element (:class @ch)) (cons ch (descendants ch)))) (:children @n)))

(defn- connected? [n]
  (loop [x n] (cond (nil? x) false (= :document (:class @x)) true :else (recur (:parent @x)))))

;; ---------------------------------------------------------------------------------------------------------------
;; selectors: compound selectors (tag, #id, .class, [attr], [attr=v], *) joined by descendant combinators, and lists

(defn- parse-compound [s]
  (loop [s s out {:classes [] :attrs []}]
    (if (= "" s)
      out
      (let [m (or (re-find #"^\*" s) (re-find #"^[A-Za-z][A-Za-z0-9-]*" s) (re-find #"^#[A-Za-z0-9_-]+" s)
                  (re-find #"^\.[A-Za-z0-9_-]+" s) (re-find #"^\[[^\]]+\]" s))]
        (when-not m (throw (page-error "SyntaxError" (str "'" s "' is not a valid selector"))))
        (recur (subs s (count m))
               (let [c0 (subs m 0 1)]
                 (cond
                   (= "*" c0) out
                   (= "#" c0) (assoc out :id (subs m 1))
                   (= "." c0) (update out :classes conj (subs m 1))
                   (= "[" c0) (let [body (subs m 1 (dec (count m)))
                                    [k v] (str/split body #"=" 2)]
                                (update out :attrs conj [(str/trim k) (when v (str/replace (str/trim v) #"^[\"']|[\"']$" ""))]))
                   :else (assoc out :tag (str/lower-case m)))))))))

(defn- classes-of [n] (set (remove str/blank? (str/split (or (attr n "class") "") #"\s+"))))

(defn- matches-compound? [n {:keys [tag id classes attrs]}]
  (and (= :element (:class @n))
       (or (nil? tag) (= tag (str/lower-case (:tag @n))))
       (or (nil? id) (= id (attr n "id")))
       (every? (classes-of n) classes)
       (every? (fn [[k v]] (let [a (attr n k)] (and (some? a) (or (nil? v) (= v a))))) attrs)))

(defn- matches? [n sel]
  (let [parts (mapv parse-compound (str/split (str/trim sel) #"\s+"))]
    (and (matches-compound? n (peek parts))
         (loop [ps (pop parts) x (:parent @n)]
           (cond (empty? ps) true
                 (nil? x) false
                 (matches-compound? x (peek ps)) (recur (pop ps) (:parent @x))
                 :else (recur ps (:parent @x)))))))

(defn- query-all [root sel]
  (let [sels (map str/trim (str/split sel #","))]
    (filterv (fn [n] (some (fn [s] (matches? n s)) sels)) (descendants root))))

;; ---------------------------------------------------------------------------------------------------------------
;; events and callbacks

(defn- stub
  "the page's stub of guest callback id (one per id, so a function added twice is one listener), at generation gen"
  [id gen]
  (if-let [s (get-in @st [:stubs id])]
    (do (swap! s assoc :gen gen) s)
    (let [s (obj {:class :callback :cb id :gen gen})]
      (swap! st assoc-in [:stubs id] s)
      s)))

(declare write-value)

(defn- call-fn!
  "Call page function f (a callback stub: filed for the guest; a native page function: run here) with args."
  [f args]
  (let [m @f]
    (case (:class m)
      :callback (let [w (c/writer 256)]
                  (c/u32! w (count args))
                  (doseq [a args] (write-value w a false 0))
                  (swap! st update :calls conj [(:cb m) (c/bytes w)])
                  nil)
      :native ((:f m) args)
      (throw (page-error "TypeError" "not a function")))))

(defn- event [type target init]
  (obj {:class :event :type type :target target :bubbles (boolean (get init "bubbles")) :default-prevented false
        :props {}}))

(defn- dispatch! [target ev]
  (swap! ev assoc :target target)
  (loop [n target]
    (when n
      (swap! ev assoc :current n)
      (let [hs (get-in @n [:listeners (:type @ev)])
            on (get-in @n [:props (str "on" (:type @ev))])]
        (doseq [h hs] (call-fn! h [ev]))
        (when (and on (map? (deref on))) (call-fn! on [ev])))
      (when (:bubbles @ev) (recur (:parent @n)))))
  (not (:default-prevented @ev)))

;; ---------------------------------------------------------------------------------------------------------------
;; the classes: getters, setters, methods

(defn- children-els [n] (filterv (fn [x] (= :element (:class @x))) (:children @n)))

(defn- type-name [o]
  (let [m @o]
    (case (:class m)
      :window "Window"
      :document "HTMLDocument"
      :text "Text"
      :element (if (:ns m)
                 (if (= SVG (:ns m)) (str "SVG" (str/capitalize (:tag m)) "Element") "Element")
                 (case (:tag m)
                   "div" "HTMLDivElement" "span" "HTMLSpanElement" "p" "HTMLParagraphElement"
                   "input" "HTMLInputElement" "button" "HTMLButtonElement" "a" "HTMLAnchorElement"
                   "body" "HTMLBodyElement" "html" "HTMLHtmlElement" "head" "HTMLHeadElement"
                   "table" "HTMLTableElement" "tr" "HTMLTableRowElement" "td" "HTMLTableCellElement"
                   "select" "HTMLSelectElement" "option" "HTMLOptionElement" "textarea" "HTMLTextAreaElement"
                   "HTMLElement"))
      :style "CSSStyleDeclaration"
      :class-list "DOMTokenList"
      :event "Event"
      :native "Function"
      :callback "Function"
      "Object")))

(defn- style-text [s] (str/join " " (map (fn [[k v]] (str k ": " v ";")) (:decls @s))))

(defn- sync-style! [s]
  (let [owner (:owner @s)]
    (if (empty? (:decls @s)) (remove-attr! owner "style") (set-attr! owner "style" (style-text s)))))

(defn- style-of [n]
  (or (:style @n)
      (let [s (obj {:class :style :owner n :decls []})]
        (swap! n assoc :style s)
        s)))

(defn- parse-style! [s text]
  (swap! s assoc :decls (vec (keep (fn [d] (let [[k v] (str/split d #":" 2)]
                                             (when (and v (not (str/blank? k))) [(str/trim k) (str/trim v)])))
                                   (str/split (str text) #";")))))

(defn- set-decl! [s k v]
  (swap! s update :decls (fn [ds] (let [ds (filterv (fn [[dk _]] (not= dk k)) ds)]
                                    (if (or (nil? v) (= "" v)) ds (conj ds [k (str v)])))))
  (sync-style! s))

(defn- class-list-of [n]
  (or (:class-list @n)
      (let [l (obj {:class :class-list :owner n})]
        (swap! n assoc :class-list l)
        l)))

(defn- set-classes! [n cs] (if (empty? cs) (remove-attr! n "class") (set-attr! n "class" (str/join " " cs))))

(defn- class-vec [n] (vec (remove str/blank? (str/split (or (attr n "class") "") #"\s+"))))

(def ^:private reflected-bool #{"disabled" "hidden" "required" "readonly" "multiple" "autofocus"})

(declare doc)

(defn- el-get [n k]
  (let [m @n]
    (cond
      (contains? (:props m) k) (get (:props m) k)
      :else
      (case k
        "nodeType" (if (= :text (:class m)) 3 1)
        "nodeName" (if (= :text (:class m)) "#text" (str/upper-case (:tag m)))
        "tagName" (str/upper-case (:tag m))
        "localName" (:tag m)
        "namespaceURI" (or (:ns m) "http://www.w3.org/1999/xhtml")
        "parentNode" (:parent m)
        "parentElement" (:parent m)
        "childNodes" (:children m)
        "children" (children-els n)
        "firstChild" (first (:children m))
        "lastChild" (peek (:children m))
        "firstElementChild" (first (children-els n))
        "nextSibling" (when-let [p (:parent m)]
                        (let [cs (:children @p) i (first (keep-indexed (fn [i x] (when (identical? x n) i)) cs))]
                          (get cs (inc i))))
        "isConnected" (connected? n)
        "ownerDocument" (doc)
        "textContent" (text-of n)
        "innerText" (text-of n)
        "data" (:text m)
        "nodeValue" (:text m)
        "innerHTML" (inner-html n)
        "outerHTML" (outer-html n)
        "id" (or (attr n "id") "")
        "className" (or (attr n "class") "")
        "style" (style-of n)
        "classList" (class-list-of n)
        "value" (or (attr n "value") "")
        "checked" (some? (attr n "checked"))
        "selected" (some? (attr n "selected"))
        (cond (contains? reflected-bool k) (some? (attr n k))
              :else ::undefined)))))

(defn- el-set! [n k v]
  (let [m @n]
    (case k
      "textContent" (if (= :text (:class m))
                      (swap! n assoc :text (str v))
                      (do (doseq [ch (:children m)] (swap! ch assoc :parent nil))
                          (swap! n assoc :children [])
                          (when (and (some? v) (not= "" v)) (insert! n (text-node v) nil))))
      "innerText" (el-set! n "textContent" v)
      "data" (swap! n assoc :text (str v))
      "nodeValue" (swap! n assoc :text (str v))
      ;; markup is not parsed: an innerHTML write keeps its text (empty clears the children)
      "innerHTML" (el-set! n "textContent" v)
      "id" (set-attr! n "id" v)
      "className" (set-attr! n "class" v)
      "style" (parse-style! (style-of n) v)
      ;; the user's state: set apart from the attribute once written
      ("value" "checked" "selected") (swap! n assoc-in [:props k] (if (= "value" k) (if (nil? v) "" (str v)) (boolean v)))
      (if (contains? reflected-bool k)
        (if v (set-attr! n k "") (remove-attr! n k))
        (swap! n assoc-in [:props k] v)))
    (when (= "style" k) (sync-style! (style-of n)))))

(defn- add-listener! [n type f]
  (when (and f (not (some (fn [x] (identical? x f)) (get-in @n [:listeners type]))))
    (swap! n update-in [:listeners type] (fnil conj []) f)))

(defn- remove-listener! [n type f]
  (swap! n update-in [:listeners type] (fn [hs] (filterv (fn [x] (not (identical? x f))) hs))))

(def ^:private node-methods
  {"setAttribute" (fn [n [k v]] (set-attr! n (str k) v) ::undefined)
   "setAttributeNS" (fn [n [_ k v]] (set-attr! n (str k) v) ::undefined)
   "getAttribute" (fn [n [k]] (attr n (str k)))
   "getAttributeNS" (fn [n [_ k]] (attr n (str k)))
   "hasAttribute" (fn [n [k]] (some? (attr n (str k))))
   "removeAttribute" (fn [n [k]] (remove-attr! n (str k)) ::undefined)
   "removeAttributeNS" (fn [n [_ k]] (remove-attr! n (str k)) ::undefined)
   "appendChild" (fn [n [ch]] (insert! n ch nil))
   "append" (fn [n chs] (doseq [ch chs] (insert! n (if (string? ch) (text-node ch) ch) nil)) ::undefined)
   ;; ParentNode.replaceChildren: every child out, then the arguments in, in order (a string as a text node).
   ;; MEASURED before (CP26 pair 36670ea8fb6b7002, test/vs/reagami_test.vs): reagami's
   ;; (.apply parent.replaceChildren parent …) read the property as ::undefined and walled at run form 7659,
   ;; "Cannot read properties of null (reading 'apply')".
   "replaceChildren" (fn [n chs]
                       (doseq [ch (:children @n)] (detach! ch))
                       (doseq [ch chs] (insert! n (if (string? ch) (text-node ch) ch) nil))
                       ::undefined)
   "insertBefore" (fn [n [ch ref]] (insert! n ch ref))
   "removeChild" (fn [n [ch]]
                   (when-not (identical? n (:parent @ch))
                     (throw (page-error "NotFoundError" "The node to be removed is not a child of this node.")))
                   (detach! ch) ch)
   "replaceChild" (fn [n [new-ch old]]
                    (when-not (identical? n (:parent @old))
                      (throw (page-error "NotFoundError" "The node to be replaced is not a child of this node.")))
                    (insert! n new-ch old) (detach! old) old)
   "remove" (fn [n _] (detach! n) ::undefined)
   "contains" (fn [n [x]] (boolean (loop [y x] (cond (nil? y) false (identical? y n) true :else (recur (:parent @y))))))
   "hasChildNodes" (fn [n _] (boolean (seq (:children @n))))
   "querySelector" (fn [n [s]] (first (query-all n (str s))))
   "querySelectorAll" (fn [n [s]] (query-all n (str s)))
   "getElementsByTagName" (fn [n [t]] (query-all n (str t)))
   "getElementsByClassName" (fn [n [cl]] (query-all n (str "." cl)))
   "matches" (fn [n [s]] (matches? n (str s)))
   "closest" (fn [n [s]] (loop [x n] (cond (nil? x) nil (and (= :element (:class @x)) (matches? x (str s))) x
                                           :else (recur (:parent @x)))))
   "addEventListener" (fn [n [type f]] (add-listener! n (str type) f) ::undefined)
   "removeEventListener" (fn [n [type f]] (remove-listener! n (str type) f) ::undefined)
   "dispatchEvent" (fn [n [ev]] (dispatch! n ev))
   "click" (fn [n _] (dispatch! n (event "click" n {"bubbles" true})) ::undefined)
   "focus" (fn [_ _] ::undefined)
   "blur" (fn [_ _] ::undefined)
   "getBoundingClientRect" (fn [_ _] {"x" 0 "y" 0 "width" 0 "height" 0 "top" 0 "left" 0 "right" 0 "bottom" 0})
   "cloneNode" (fn [n [deep]]
                 ((fn cl [x] (let [m @x
                                   y (obj (assoc m :h nil :parent nil :children [] :listeners {} :style nil :class-list nil))]
                               (when deep (doseq [ch (:children m)] (insert! y (cl ch) nil)))
                               y))
                  n))})

(def ^:private style-methods
  {"setProperty" (fn [s [k v]] (set-decl! s (str k) v) ::undefined)
   "removeProperty" (fn [s [k]] (let [old (some (fn [[dk dv]] (when (= dk k) dv)) (:decls @s))]
                                  (set-decl! s (str k) nil) (or old "")))
   "getPropertyValue" (fn [s [k]] (or (some (fn [[dk dv]] (when (= dk k) dv)) (:decls @s)) ""))})

(def ^:private class-list-methods
  {"add" (fn [l cs] (let [n (:owner @l) v (class-vec n)] (set-classes! n (into v (remove (set v) (map str cs))))) ::undefined)
   "remove" (fn [l cs] (let [n (:owner @l)] (set-classes! n (vec (remove (set (map str cs)) (class-vec n))))) ::undefined)
   "contains" (fn [l [cl]] (contains? (set (class-vec (:owner @l))) (str cl)))
   "toggle" (fn [l [cl force]]
              (let [n (:owner @l) has (contains? (set (class-vec n)) cl) on (if (some? force) (boolean force) (not has))]
                (set-classes! n (if on (if has (class-vec n) (conj (class-vec n) cl)) (vec (remove #{cl} (class-vec n)))))
                on))})

(def ^:private event-methods
  {"preventDefault" (fn [e _] (swap! e assoc :default-prevented true) ::undefined)
   "stopPropagation" (fn [e _] (swap! e assoc :bubbles false) ::undefined)
   "stopImmediatePropagation" (fn [e _] (swap! e assoc :bubbles false) ::undefined)})

(declare win)

(defn- doc-get [d k]
  (let [m @d]
    (case k
      "body" (:body m)
      "head" (:head m)
      "documentElement" (:html m)
      "defaultView" (win)
      "nodeType" 9
      "readyState" "complete"
      "title" (or (get-in m [:props "title"]) "")
      (if (contains? (:props m) k) (get (:props m) k) ::undefined))))

(def ^:private doc-methods
  {"createElement" (fn [_ [t]] (element (str t) nil))
   "createElementNS" (fn [_ [ns t]] (element (str t) (if (= "http://www.w3.org/1999/xhtml" ns) nil ns)))
   "createTextNode" (fn [_ [s]] (text-node s))
   "createEvent" (fn [_ [_]] (event "" nil {}))
   "getElementById" (fn [d [id]] (first (filter (fn [n] (= (str id) (attr n "id"))) (descendants (:html @d)))))
   "querySelector" (fn [d [s]] (first (query-all (:html @d) (str s))))
   "querySelectorAll" (fn [d [s]] (query-all (:html @d) (str s)))
   "getElementsByTagName" (fn [d [t]] (query-all (:html @d) (str t)))
   "addEventListener" (fn [d [type f]] (add-listener! d (str type) f) ::undefined)
   "removeEventListener" (fn [d [type f]] (remove-listener! d (str type) f) ::undefined)
   "dispatchEvent" (fn [d [ev]] (dispatch! d ev))})

(defn- raf! [f]
  (let [id (inc (count (:raf @st)))]
    (swap! st update :raf conj [id f])
    id))

(def ^:private win-methods
  {"requestAnimationFrame" (fn [_ [f]] (raf! f))
   "cancelAnimationFrame" (fn [_ [id]] (swap! st update :raf (fn [rs] (filterv (fn [[i _]] (not= i id)) rs))) ::undefined)
   "addEventListener" (fn [w [type f]] (add-listener! w (str type) f) ::undefined)
   "removeEventListener" (fn [w [type f]] (remove-listener! w (str type) f) ::undefined)
   "getComputedStyle" (fn [_ [n]] (style-of n))
   "setTimeout" (fn [_ [f]] (call-fn! f []) 0)})

(def ^:private performance (delay (obj {:class :performance})))

(defn- win-get [w k]
  (case k
    ("window" "self" "globalThis") (win)
    "document" (doc)
    "performance" @performance
    (if (contains? (:props @w) k) (get (:props @w) k) ::undefined)))

(defn- methods-of [o]
  (case (:class @o)
    (:element :text) node-methods
    :document doc-methods
    :window win-methods
    :style style-methods
    :class-list class-list-methods
    :event event-methods
    :performance {"now" (fn [_ _] (double (- (now-ms) (or (:t0 @st) 0))))}
    {}))

(defn- prop-get [o k]
  (let [m @o]
    (if (contains? (methods-of o) k)
      ::method
      (case (:class m)
        (:element :text) (el-get o k)
        :document (doc-get o k)
        :window (win-get o k)
        :style (case k
                 "cssText" (style-text o)
                 (or (some (fn [[dk dv]] (when (= dk k) dv)) (:decls m)) (get (:props m) k ::undefined)))
        :class-list (case k "length" (count (class-vec (:owner m))) "value" (or (attr (:owner m) "class") "") ::undefined)
        :event (case k
                 "type" (:type m) "target" (:target m) "currentTarget" (:current m)
                 "defaultPrevented" (:default-prevented m) "bubbles" (:bubbles m)
                 (get (:props m) k ::undefined))
        (get (:props m) k ::undefined)))))

(defn- prop-set! [o k v]
  (case (:class @o)
    (:element :text) (el-set! o k v)
    :style (if (= "cssText" k) (do (parse-style! o v) (sync-style! o)) (set-decl! o k v))
    (swap! o assoc-in [:props k] v)))

(defn- call! [o k args]
  (if-let [f (get (methods-of o) k)]
    (f o args)
    (let [v (prop-get o k)]
      (if (and (model? v) (contains? #{:callback :native} (:class @v)))
        (or (call-fn! v args) ::undefined)
        (throw (page-error "TypeError" (str k " is not a function on " (type-name o))))))))

;; ---------------------------------------------------------------------------------------------------------------
;; the document

(defn- fresh-page []
  (let [w (obj {:class :window :props {}})
        d (obj {:class :document :props {}})
        html (element "html" nil)
        head (element "head" nil)
        body (element "body" nil)
        app (element "div" nil)]
    (set-attr! app "id" "app")
    (insert! html head nil)
    (insert! html body nil)
    (insert! body app nil)
    (swap! html assoc :parent d)
    (swap! d assoc :html html :head head :body body :children [html])
    ;; handles only ever grow: a guest keeps the range it was allocated across a fresh page
    (reset! st {:objs {} :next (max 16 (or (:next @st) 16)) :calls [] :t0 (now-ms) :raf [] :stubs {}})
    (put-at! 1 w)
    (put-at! 2 d)
    nil))

(defn- win [] (get-in @st [:objs 1]))
(defn- doc [] (get-in @st [:objs 2]))

(defn reset-page!
  "A fresh document: <html><head></head><body><div id=\"app\"></div></body></html>, nothing registered, nothing filed."
  []
  (fresh-page))

;; ---------------------------------------------------------------------------------------------------------------
;; values

(defn- write-handle [w o]
  (c/u8! w c/T-HANDLE)
  (c/i32! w (register! o))
  (c/u8! w (if (contains? #{:callback :native} (:class @o)) 1 0))
  (c/str! w (type-name o)))

(defn write-value
  "Write model value v as eve.dom.exec writes a page value (method? true: a method's marker)."
  [w v method? depth]
  (cond
    (= ::undefined v) (c/u8! w c/T-UNDEF)
    (= ::method v) (if method? (c/u8! w c/T-METHOD) (c/u8! w c/T-UNDEF))
    (nil? v) (c/u8! w c/T-NULL)
    (true? v) (c/u8! w c/T-TRUE)
    (false? v) (c/u8! w c/T-FALSE)
    (number? v) (do (c/u8! w c/T-NUM) (c/f64! w (double v)))
    (string? v) (do (c/u8! w c/T-STR) (c/str! w v))
    (model? v) (write-handle w v)
    (vector? v) (do (c/u8! w c/T-ARRAY)
                    (c/u32! w (count v))
                    (doseq [x v] (write-value w x false (inc depth))))
    (map? v) (do (c/u8! w c/T-OBJECT)
                 (c/u32! w (count v))
                 (doseq [[k x] v] (c/str! w (if (keyword? k) (name k) (str k))) (write-value w x false (inc depth))))
    :else (do (c/u8! w c/T-STR) (c/str! w (str v)))))

(defn- read-value [r]
  (let [t (c/r-u8 r)]
    (case t
      0 ::undefined
      1 nil
      2 false
      3 true
      4 (c/r-f64 r)
      5 (c/r-str r)
      6 (let [hd (c/r-i32 r)] (c/r-u8 r) (c/r-str r) (lookup hd))
      7 (let [n (c/r-u32 r)] (vec (repeatedly n #(read-value r))))
      8 (let [n (c/r-u32 r)] (into {} (repeatedly n (fn [] (let [k (c/r-str r)] [k (read-value r)])))))
      10 (let [id (c/r-i32 r) gen (c/r-f64 r)] (stub id gen))
      (throw (page-error "Error" (str "eve.dom: unexpected value tag " t))))))

(defn- read-args [r] (let [n (c/r-u32 r)] (vec (repeatedly n #(read-value r)))))

(defn- arg [v] (if (= ::undefined v) nil v))

;; ---------------------------------------------------------------------------------------------------------------
;; the executor

(defn- write-error [out e]
  (c/u8! out c/T-ERROR)
  (c/str! out (or (:name (ex-data e)) "Error"))
  (c/str! out (or (ex-message e) (str e))))

(defn- new! [f args]
  (let [m @f]
    (if (= :native (:class m))
      ((or (:ctor m) (throw (page-error "TypeError" (str (:name m) " is not a constructor")))) args)
      (throw (page-error "TypeError" "not a constructor")))))

(defn- global-get [k]
  (case k
    "Event" (obj {:class :native :name "Event" :f (fn [_] nil)
                  :ctor (fn [[type init]] (event (str type) nil (if (map? init) init {})))})
    "CustomEvent" (obj {:class :native :name "CustomEvent" :f (fn [_] nil)
                        :ctor (fn [[type init]] (let [e (event (str type) nil (if (map? init) init {}))]
                                                  (swap! e assoc-in [:props "detail"] (get init "detail")) e))})
    (let [v (prop-get (win) k)]
      (if (= ::method v)
        (obj {:class :native :name k :f (fn [args] (call! (win) k args))})
        v))))

(defn exec-frame
  "Run request frame byts from the guest: the reply frame's bytes (eve.dom.exec's exec-frame, over the model)."
  [bs]
  (when-not (doc) (fresh-page))
  (let [r (c/reader bs)
        out (c/writer 1024)
        n (c/r-u32 r)
        errors (volatile! 0)]
    (c/u32! out 0)
    (dotimes [_ n]
      (let [op (c/r-u8 r)]
        (if (contains? #{c/OP-SET c/OP-CALLQ c/OP-RELEASE c/OP-DEL c/OP-CALLAS} op)
          (try
            (condp = op
              c/OP-SET (let [o (lookup (c/r-i32 r)) k (c/r-str r) v (read-value r)] (prop-set! o k (arg v)))
              c/OP-CALLQ (let [o (lookup (c/r-i32 r)) k (c/r-str r) args (read-args r)] (call! o k (mapv arg args)))
              c/OP-CALLAS (let [rh (c/r-i32 r) o (lookup (c/r-i32 r)) k (c/r-str r) args (read-args r)
                                v (call! o k (mapv arg args))]
                            (when (model? v) (put-at! rh v)))
              c/OP-DEL (let [o (lookup (c/r-i32 r)) k (c/r-str r)] (swap! o update :props dissoc k))
              c/OP-RELEASE (dotimes [_ (c/r-u32 r)] (let [hd (c/r-i32 r)]
                                                       (when (> hd 2) (swap! st update :objs dissoc hd)))))
            (catch #?(:clj Throwable :cljs :default :default :default) _
              (vswap! errors inc)))
          (let [at (c/pos out)]
            (try
              (condp = op
                c/OP-GET (let [o (lookup (c/r-i32 r)) k (c/r-str r)]
                           (write-value out (prop-get o k) (not (re-find #"^[A-Z]" k)) 0))
                c/OP-CALL (let [o (lookup (c/r-i32 r)) k (c/r-str r) args (read-args r)]
                            (write-value out (call! o k (mapv arg args)) false 0))
                c/OP-APPLY (let [f (lookup (c/r-i32 r)) _this (read-value r) args (read-args r)]
                             (write-value out (or (call-fn! f (mapv arg args)) ::undefined) false 0))
                c/OP-NEW (let [f (lookup (c/r-i32 r)) args (read-args r)]
                           (write-value out (new! f (mapv arg args)) false 0))
                c/OP-GLOBAL (write-value out (global-get (c/r-str r)) false 0)
                c/OP-HAS (let [o (lookup (c/r-i32 r)) k (c/r-str r)]
                           (write-value out (not= ::undefined (prop-get o k)) false 0))
                c/OP-KEYS (let [o (lookup (c/r-i32 r))] (write-value out (vec (keys (:props @o))) false 0))
                c/OP-ALLOC (let [k (c/r-u32 r) b (:next @st)]
                             (swap! st assoc :next (+ b k))
                             (write-value out b false 0))
                (throw (page-error "Error" (str "eve.dom: unknown op " op))))
              (catch #?(:clj Throwable :cljs :default :default :default) e
                (c/seek! out at)
                (write-error out e)))))))
    (c/patch-u32! out 0 @errors)
    (c/bytes out)))

(defn take-calls!
  "The guest callback calls the page has filed since the last take ([id args-bytes] each, in order), now forgotten."
  []
  (let [cs (:calls @st)]
    (swap! st assoc :calls [])
    cs))

(defn tick-animation-frame!
  "Run the animation frames requested so far, as one frame at the page's clock (their calls filed)."
  []
  (let [rs (:raf @st) ts (double (- (now-ms) (or (:t0 @st) 0)))]
    (swap! st assoc :raf [])
    (doseq [[_ f] rs] (call-fn! f [ts]))
    (count rs)))
