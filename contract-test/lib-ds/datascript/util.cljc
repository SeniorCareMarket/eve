(ns datascript.util
  (:refer-clojure :exclude [find])
  ;; PORT DELTA (F-CLJE-HOST-REFERS): clojerl's clojure.core has no
  ;; transient family (bare-boot inventory in STATUS-DS-CLJE-LRU);
  ;; Eve's beam eve.host supplies them with persistent semantics
  ;; (transient/persistent! are identity there), so under :clje this
  ;; ns refers them from eve.host. Same remedy in db/conn/query/
  ;; pull-api/entity/serialize for the names each of those files
  ;; calls bare.
  #?(:clj
     (:import
       [java.util UUID]) :cljr (:import [System Guid])
     :clje
     (:require
       [eve.host :refer [transient conj! assoc! persistent! volatile! vswap!]])))

(def ^:dynamic *debug*
  false)

#?(:clj (defmacro log [& body]
     (when (System/getProperty "datascript.debug")
       `(when *debug*
          (println ~@body)))) :cljr (defmacro log [& body]
     (when (System.Environment/GetEnvironmentVariable "DATASCRIPT_DEBUG")
       `(when *debug*
          (println ~@body))))
   :lpy (defmacro log [& body]
     `(when *debug*
        (println ~@body)))
   ;; PORT DELTA (F-NBB-MACROS): no System/getProperty in the :cljs
   ;; macro read; the runtime *debug* gate alone carries it there
   :cljs (defmacro log [& body]
     `(when *debug*
        (println ~@body))))

;; PORT DELTA (F-CLJE-UTIL-MACROS): :clje twins for util's macro
;; layer — every #?(:clj X :cljs X) form below gets its X appended
;; as a plain #?(:clje X); the forms are untouched. log takes the
;; :cljs arm (no System/getProperty at macro time on this seat).
#?(:clje (defmacro log [& body]
           `(when *debug*
              (println ~@body))))

;; PORT DELTA (F-LPY-RAISE-GENSYM): basilisp's reader rejects a gensym
;; symbol (m#) in a macro body outside syntax-quote even inside a
;; function passed to map at expansion time; the :lpy arm names the
;; predicate's argument plainly (auto-gensym only matters inside the
;; syntax-quote that follows, and m is not referenced there).
#?(:lpy (defmacro raise [& fragments]
     (let [msgs (butlast fragments)
           data (last fragments)]
       `(throw (ex-info (str ~@(map (fn [m] (if (string? m) m (list 'pr-str m))) msgs)) ~data))))
   ;; PORT DELTA (F-LPY-RAISE-GENSYM, cont.): basilisp READS the whole
   ;; #? form, so the :clj arm's body is read too — its m# trips the
   ;; reader's gensym check regardless of the :lpy arm above. The :clj
   ;; arm therefore uses the plain name as well; semantics identical
   ;; (the fn's argument is never referenced inside the syntax-quote).
   :clj (defmacro raise [& fragments]
     (let [msgs (butlast fragments)
           data (last fragments)]
       `(throw (ex-info (str ~@(map (fn [m] (if (string? m) m (list 'pr-str m))) msgs)) ~data)))) :cljr (defmacro raise [& fragments]
     (let [msgs (butlast fragments)
           data (last fragments)]
       `(throw (ex-info (str ~@(map (fn [m] (if (string? m) m (list 'pr-str m))) msgs)) ~data))))
   :cljs (defmacro raise [& fragments]
     (let [msgs (butlast fragments)
           data (last fragments)]
       `(throw (ex-info (str ~@(map (fn [m] (if (string? m) m (list 'pr-str m))) msgs)) ~data)))))

#?(:clje (defmacro raise [& fragments]
           (let [msgs (butlast fragments)
                 data (last fragments)]
             `(throw (ex-info (str ~@(map (fn [m] (if (string? m) m (list 'pr-str m))) msgs)) ~data)))))

#?(:clj (def ^:private ^:dynamic *if+-syms) :cljr (def ^:private ^:dynamic *if+-syms)
   :lpy (def ^:private ^:dynamic *if+-syms)
   :cljs (def ^:private ^:dynamic *if+-syms))

#?(:clje (def ^:private ^:dynamic *if+-syms))
  
#?(:clj (defn- if+-rewrite-cond-impl [cond]
     (clojure.core/cond
       (empty? cond)
       true
    
       (and
         (= :let (first cond))
         (empty? (second cond)))
       (if+-rewrite-cond-impl (nnext cond))
    
       (= :let (first cond))
       (let [[var val & rest] (second cond)
             sym                (gensym)]
         (vswap! *if+-syms conj [var sym])
         (list 'let [var (list 'clojure.core/vreset! sym val)]
           (if+-rewrite-cond-impl
             (cons 
               :let
               (cons rest
                 (nnext cond))))))
    
       :else
       (list 'and
         (first cond)
         (if+-rewrite-cond-impl (next cond))))) :cljr (defn- if+-rewrite-cond-impl [cond]
     (clojure.core/cond
       (empty? cond)
       true
    
       (and
         (= :let (first cond))
         (empty? (second cond)))
       (if+-rewrite-cond-impl (nnext cond))
    
       (= :let (first cond))
       (let [[var val & rest] (second cond)
             sym                (gensym)]
         (vswap! *if+-syms conj [var sym])
         (list 'let [var (list 'clojure.core/vreset! sym val)]
           (if+-rewrite-cond-impl
             (cons 
               :let
               (cons rest
                 (nnext cond))))))
    
       :else
       (list 'and
         (first cond)
         (if+-rewrite-cond-impl (next cond)))))
   :lpy (defn- if+-rewrite-cond-impl [cond]
     (clojure.core/cond
       (empty? cond)
       true
    
       (and
         (= :let (first cond))
         (empty? (second cond)))
       (if+-rewrite-cond-impl (nnext cond))
    
       (= :let (first cond))
       (let [[var val & rest] (second cond)
             sym                (gensym)]
         (vswap! *if+-syms conj [var sym])
         (list 'let [var (list 'clojure.core/vreset! sym val)]
           (if+-rewrite-cond-impl
             (cons 
               :let
               (cons rest
                 (nnext cond))))))
    
       :else
       (list 'and
         (first cond)
         (if+-rewrite-cond-impl (next cond)))))
   :cljs (defn- if+-rewrite-cond-impl [cond]
     (clojure.core/cond
       (empty? cond)
       true
    
       (and
         (= :let (first cond))
         (empty? (second cond)))
       (if+-rewrite-cond-impl (nnext cond))
    
       (= :let (first cond))
       (let [[var val & rest] (second cond)
             sym                (gensym)]
         (vswap! *if+-syms conj [var sym])
         (list 'let [var (list 'clojure.core/vreset! sym val)]
           (if+-rewrite-cond-impl
             (cons 
               :let
               (cons rest
                 (nnext cond))))))
    
       :else
       (list 'and
         (first cond)
         (if+-rewrite-cond-impl (next cond))))))

#?(:clje (defn- if+-rewrite-cond-impl [cond]
           (clojure.core/cond
             (empty? cond)
             true

             (and
               (= :let (first cond))
               (empty? (second cond)))
             (if+-rewrite-cond-impl (nnext cond))

             (= :let (first cond))
             (let [[var val & rest] (second cond)
                   sym                (gensym)]
               (vswap! *if+-syms conj [var sym])
               (list 'let [var (list 'clojure.core/vreset! sym val)]
                 (if+-rewrite-cond-impl
                   (cons
                     :let
                     (cons rest
                       (nnext cond))))))

             :else
             (list 'and
               (first cond)
               (if+-rewrite-cond-impl (next cond))))))

#?(:clj (defn- if+-rewrite-cond [cond]
     (binding [*if+-syms (volatile! [])]
       [(if+-rewrite-cond-impl cond) @*if+-syms])) :cljr (defn- if+-rewrite-cond [cond]
     (binding [*if+-syms (volatile! [])]
       [(if+-rewrite-cond-impl cond) @*if+-syms]))
   :lpy (defn- if+-rewrite-cond [cond]
     (binding [*if+-syms (volatile! [])]
       [(if+-rewrite-cond-impl cond) @*if+-syms]))
   :cljs (defn- if+-rewrite-cond [cond]
     (binding [*if+-syms (volatile! [])]
       [(if+-rewrite-cond-impl cond) @*if+-syms])))

#?(:clje (defn- if+-rewrite-cond [cond]
           (binding [*if+-syms (volatile! [])]
             [(if+-rewrite-cond-impl cond) @*if+-syms])))

#?(:clj (defn- flatten-1 [xs]
     (vec
       (mapcat identity xs))) :cljr (defn- flatten-1 [xs]
     (vec
       (mapcat identity xs)))
   :lpy (defn- flatten-1 [xs]
     (vec
       (mapcat identity xs)))
   :cljs (defn- flatten-1 [xs]
     (vec
       (mapcat identity xs))))

#?(:clje (defn- flatten-1 [xs]
           (vec
             (mapcat identity xs))))

#?(:clj (defmacro if+
     "Allows sharing local variables between condition and then clause.
      
      Use `:let [...]` form (not nested!) inside `and` condition and its bindings
      will be visible in later `and` clauses and inside `then` branch:
      
        (if+ (and
               (= 1 2)
               ;; same :let syntax as in doseq/for
               :let [x 3
                     y (+ x 4)]
               ;; x and y visible downstream
               (> y x))
          
          ;; then: x and y visible here!
          (+ x y 5)
          
          ;; else: no x or y
          6)"
     [cond then else]
     (if (and
           (seq? cond)
           (or
             (= 'and (first cond))
             (= 'clojure.core/and (first cond))))
       (let [[cond' syms] (if+-rewrite-cond (next cond))]
         `(let ~(flatten-1
                  (for [[_ sym] syms]
                    [sym '(volatile! nil)]))
            (if ~cond'
              (let ~(flatten-1
                      (for [[binding sym] syms]
                        [binding (list 'deref sym)]))
                ~then)
              ~else)))
       (list 'if cond then else))) :cljr (defmacro if+
     "Allows sharing local variables between condition and then clause.
      
      Use `:let [...]` form (not nested!) inside `and` condition and its bindings
      will be visible in later `and` clauses and inside `then` branch:
      
        (if+ (and
               (= 1 2)
               ;; same :let syntax as in doseq/for
               :let [x 3
                     y (+ x 4)]
               ;; x and y visible downstream
               (> y x))
          
          ;; then: x and y visible here!
          (+ x y 5)
          
          ;; else: no x or y
          6)"
     [cond then else]
     (if (and
           (seq? cond)
           (or
             (= 'and (first cond))
             (= 'clojure.core/and (first cond))))
       (let [[cond' syms] (if+-rewrite-cond (next cond))]
         `(let ~(flatten-1
                  (for [[_ sym] syms]
                    [sym '(volatile! nil)]))
            (if ~cond'
              (let ~(flatten-1
                      (for [[binding sym] syms]
                        [binding (list 'deref sym)]))
                ~then)
              ~else)))
       (list 'if cond then else)))
   :lpy (defmacro if+
     "Allows sharing local variables between condition and then clause.
      
      Use `:let [...]` form (not nested!) inside `and` condition and its bindings
      will be visible in later `and` clauses and inside `then` branch:
      
        (if+ (and
               (= 1 2)
               ;; same :let syntax as in doseq/for
               :let [x 3
                     y (+ x 4)]
               ;; x and y visible downstream
               (> y x))
          
          ;; then: x and y visible here!
          (+ x y 5)
          
          ;; else: no x or y
          6)"
     [cond then else]
     (if (and
           (seq? cond)
           (or
             (= 'and (first cond))
             (= 'clojure.core/and (first cond))))
       (let [[cond' syms] (if+-rewrite-cond (next cond))]
         `(let ~(flatten-1
                  (for [[_ sym] syms]
                    [sym '(volatile! nil)]))
            (if ~cond'
              (let ~(flatten-1
                      (for [[binding sym] syms]
                        [binding (list 'deref sym)]))
                ~then)
              ~else)))
       (list 'if cond then else)))
   :cljs (defmacro if+
     "Allows sharing local variables between condition and then clause.
      
      Use `:let [...]` form (not nested!) inside `and` condition and its bindings
      will be visible in later `and` clauses and inside `then` branch:
      
        (if+ (and
               (= 1 2)
               ;; same :let syntax as in doseq/for
               :let [x 3
                     y (+ x 4)]
               ;; x and y visible downstream
               (> y x))
          
          ;; then: x and y visible here!
          (+ x y 5)
          
          ;; else: no x or y
          6)"
     [cond then else]
     (if (and
           (seq? cond)
           (or
             (= 'and (first cond))
             (= 'clojure.core/and (first cond))))
       (let [[cond' syms] (if+-rewrite-cond (next cond))]
         `(let ~(flatten-1
                  (for [[_ sym] syms]
                    [sym '(volatile! nil)]))
            (if ~cond'
              (let ~(flatten-1
                      (for [[binding sym] syms]
                        [binding (list 'deref sym)]))
                ~then)
              ~else)))
       (list 'if cond then else))))


#?(:clje (defmacro if+
           [cond then else]
           (if (and
                 (seq? cond)
                 (or
                   (= 'and (first cond))
                   (= 'clojure.core/and (first cond))))
             (let [[cond' syms] (if+-rewrite-cond (next cond))]
               `(let ~(flatten-1
                        (for [[_ sym] syms]
                          [sym '(volatile! nil)]))
                  (if ~cond'
                    (let ~(flatten-1
                            (for [[binding sym] syms]
                              [binding (list 'deref sym)]))
                      ~then)
                    ~else)))
             (list 'if cond then else))))

#?(:clj (defmacro cond+ [& clauses]
     (when-some [[test expr & rest] clauses]
       (case test
         :do   `(do ~expr (util/cond+ ~@rest))
         :let  `(let ~expr (util/cond+ ~@rest))
         :some `(or ~expr (util/cond+ ~@rest))
         `(util/if+ ~test ~expr (util/cond+ ~@rest))))) :cljr (defmacro cond+ [& clauses]
     (when-some [[test expr & rest] clauses]
       (case test
         :do   `(do ~expr (util/cond+ ~@rest))
         :let  `(let ~expr (util/cond+ ~@rest))
         :some `(or ~expr (util/cond+ ~@rest))
         `(util/if+ ~test ~expr (util/cond+ ~@rest)))))
   :lpy (defmacro cond+ [& clauses]
     (when-some [[test expr & rest] clauses]
       (case test
         :do   `(do ~expr (util/cond+ ~@rest))
         :let  `(let ~expr (util/cond+ ~@rest))
         :some `(or ~expr (util/cond+ ~@rest))
         `(util/if+ ~test ~expr (util/cond+ ~@rest)))))
   :cljs (defmacro cond+ [& clauses]
     (when-some [[test expr & rest] clauses]
       (case test
         :do   `(do ~expr (util/cond+ ~@rest))
         :let  `(let ~expr (util/cond+ ~@rest))
         :some `(or ~expr (util/cond+ ~@rest))
         `(util/if+ ~test ~expr (util/cond+ ~@rest))))))

#?(:clje (defmacro cond+ [& clauses]
           (when-some [[test expr & rest] clauses]
             (case test
               :do   `(do ~expr (util/cond+ ~@rest))
               :let  `(let ~expr (util/cond+ ~@rest))
               :some `(or ~expr (util/cond+ ~@rest))
               `(util/if+ ~test ~expr (util/cond+ ~@rest))))))

#?(:clj (defmacro some-of
     ([] nil)
     ([x] x)
     ([x & more]
      `(let [x# ~x] (if (nil? x#) (some-of ~@more) x#)))) :cljr (defmacro some-of
     ([] nil)
     ([x] x)
     ([x & more]
      `(let [x# ~x] (if (nil? x#) (some-of ~@more) x#))))
   :lpy (defmacro some-of
     ([] nil)
     ([x] x)
     ([x & more]
      `(let [x# ~x] (if (nil? x#) (some-of ~@more) x#))))
   :cljs (defmacro some-of
     ([] nil)
     ([x] x)
     ([x & more]
      `(let [x# ~x] (if (nil? x#) (some-of ~@more) x#)))))

#?(:clje (defmacro some-of
           ([] nil)
           ([x] x)
           ([x & more]
            `(let [x# ~x] (if (nil? x#) (some-of ~@more) x#)))))

(defn- rand-bits [pow]
  (rand-int (bit-shift-left 1 pow)))

#?(:cljr
   (defn- to-hex-string [n l]
     (let [s (System.Convert/ToString (long n) 16)
           c (count s)]
       (cond
         (> c l) (subs s 0 l)
         (< c l) (str (apply str (repeat (- l c) "0")) s)
         :else   s))))

#?(:cljs
   (defn- to-hex-string [n l]
     (let [s (.toString n 16)
           c (count s)]
       (cond
         (> c l) (subs s 0 l)
         (< c l) (str (apply str (repeat (- l c) "0")) s)
         :else   s))))

(defn squuid
  ([]
   (squuid #?(:clj  (System/currentTimeMillis) :cljr (.ToUnixTimeMilliseconds (System.DateTimeOffset/UtcNow))
              :cljs (.getTime (js/Date.)))))
  ([msec]
   #?(:clj
      (let [uuid     (UUID/randomUUID)
            time     (int (/ msec 1000))
            high     (.getMostSignificantBits uuid)
            low      (.getLeastSignificantBits uuid)
            new-high (bit-or (bit-and high 0x00000000FFFFFFFF)
                       (bit-shift-left time 32))]
        (UUID. new-high low)) :cljr (System.Guid/Parse
        (str
          (-> (int (/ msec 1000))
            (to-hex-string 8))
          "-" (-> (rand-bits 16) (to-hex-string 4))
          "-" (-> (rand-bits 16) (bit-and 0x0FFF) (bit-or 0x4000) (to-hex-string 4))
          "-" (-> (rand-bits 16) (bit-and 0x3FFF) (bit-or 0x8000) (to-hex-string 4))
          "-" (-> (rand-bits 16) (to-hex-string 4))
          (-> (rand-bits 16) (to-hex-string 4))
          (-> (rand-bits 16) (to-hex-string 4))))
      :cljs
      (uuid
        (str
          (-> (int (/ msec 1000))
            (to-hex-string 8))
          "-" (-> (rand-bits 16) (to-hex-string 4))
          "-" (-> (rand-bits 16) (bit-and 0x0FFF) (bit-or 0x4000) (to-hex-string 4))
          "-" (-> (rand-bits 16) (bit-and 0x3FFF) (bit-or 0x8000) (to-hex-string 4))
          "-" (-> (rand-bits 16) (to-hex-string 4))
          (-> (rand-bits 16) (to-hex-string 4))
          (-> (rand-bits 16) (to-hex-string 4)))))))

(defn squuid-time-millis
  "Returns time that was used in [[squuid]] call, in milliseconds, rounded to the closest second."
  [uuid]
  #?(:clj (-> (.getMostSignificantBits ^UUID uuid)
            (bit-shift-right 32)
            (* 1000)) :cljr (-> (subs (str uuid) 0 8)
            (System.Convert/ToInt64 16)
            (* 1000))
     :cljs (-> (subs (str uuid) 0 8)
             (js/parseInt 16)
             (* 1000))))

(defn distinct-by [f coll]
  (->>
    (reduce
      (fn [[seen res :as acc] el]
        (let [key (f el)]
          (if (contains? seen key)
            acc
            [(conj! seen key) (conj! res el)])))
      [(transient #{}) (transient [])]
      coll)
    second
    persistent!))

(defn find [pred xs]
  (reduce
    (fn [_ x]
      (when (pred x)
        (reduced x)))
    nil xs))

(defn single [coll]
  (assert (nil? (next coll)) "Expected single element")
  (first coll))

(defn concatv [& xs]
  (into [] cat xs))

(defn zip
  ([a b]
   (mapv vector a b))
  ([a b & rest]
   (apply mapv vector a b rest)))

(defn removem [key-pred m]
  (persistent!
    (reduce-kv
      (fn [m k v]
        (if (key-pred k)
          m
          (assoc! m k v)))
      (transient (empty m)) m)))

;; PORT DELTA (F-CLJE-LITERAL-DEF): these two defs were (fnil conj [])
;; and (fnil conj #{}) — clojerl's emitter rejects a def init that
;; evaluates to an anonymous fn object ("Init value for
;; #'datascript.util/conjv is not a literal", util.cljc:309). The
;; explicit fns are fnil's exact semantics (a nil first arg reads as
;; the empty collection); a fn FORM is emittable on every seat.
(def conjv
  (fn [coll x] (conj (if (nil? coll) [] coll) x)))

(def conjs
  (fn [coll x] (conj (if (nil? coll) #{} coll) x)))

(defn reduce-indexed
  "Same as reduce, but `f` takes [acc el idx]"
  [f init xs]
  (first
    (reduce
      (fn [[acc idx] x]
        (let [res (f acc x idx)]
          (if (reduced? res)
            (reduced [res idx])
            [res (inc idx)])))
      [init 0]
      xs)))
