(ns extend-clj.core
  (:require
    [clojure.string :as str])
  #?(:cljs
     (:require-macros
       [extend-clj.core])))

(defprotocol IAtom3
  (deref-impl [this])
  (compare-and-set-impl [this oldv newv])
  
  (validate [this validator value])
  (notify-watches [this oldv newv])
  (swap* [this f args]))

#?(:clj (defn- cljs-env? [env]
     (boolean (:ns env))) :cljr (defn- cljs-env? [env]
     (boolean (:ns env)))
   :lpy (defn- cljs-env? [env]
     (boolean (:ns env)))
   ;; PORT DELTA (F-NBB-MACROS): the :cljs arm is only read by a CLJS
   ;; macro harvest, where expansions are always into cljs and &env
   ;; carries no :ns to test (measured in nbb's SCI: &env is {})
   :cljs (defn- cljs-env? [_env]
     true))

#?(:jank (defmacro deftype-atom
     "jank form: the :clj expansion is JVM-shaped (a syntax-quoted
      deftype* whose special-form head this seat's syntax quote
      ns-qualifies away). This arm builds the expansion as data (no
      syntax quote): an eve.deftype whose clojure.lang groups the
      types adapter realizes (IDeref/IAtom/IAtom2/IMeta/IReference/
      IRef/ILookup — the MmapAtom shape), IAtom3 carried as the
      protocol group it is, deref-impl/compare-and-set-impl routed
      there from the use site, and every internal call a protocol-fn
      call (no (.m this) anywhere). __validator/__watches/__meta are
      atom-valued fields seeded by the factory, so no field
      mutability is needed."
     [name fields & methods]
     (let [deref-impl (first (filter #(= 'deref-impl (first %)) methods))
           cas-impl   (first (filter #(= 'compare-and-set-impl (first %)) methods))
           extra      (remove #(contains? #{'deref-impl 'compare-and-set-impl} (first %)) methods)
           ->name     (symbol (str "->" name))
           all-fields (into fields '[__validator __watches __meta])]
       (list 'do
         (list* 'eve.deftype/deftype name all-fields
           ;; eve.deftype's spec grammar is FLAT (a protocol symbol, then its
           ;; method lists, then the next symbol): the groups below are built
           ;; as (head method...) lists and spliced flat by the mapcat -
           ;; nested, the spec parser drops every group and the type
           ;; registers no protocols at all (F-JANK-DEFTYPE-ATOM-FLAT)
           (concat
            (mapcat (fn [g] (cons (first g) (rest g)))
             [(list 'extend-clj.core/IAtom3
               deref-impl
               cas-impl
               '(validate [this vf value]
                  (when (some? vf)
                    (when-not (vf value)
                      (throw (ex-info "Invalid reference state" {:value value})))))
               '(notify-watches [this oldv newv]
                  (doseq [[k w] @__watches]
                    (w k this oldv newv)))
               '(swap* [this f args]
                  (loop []
                    (let [oldv (extend-clj.core/deref-impl this)
                          newv (apply f oldv args)]
                      (extend-clj.core/validate this @__validator newv)
                      (if (extend-clj.core/compare-and-set-impl this oldv newv)
                        (do (extend-clj.core/notify-watches this oldv newv) [oldv newv])
                        (recur))))))
             (list 'clojure.lang.IDeref
               '(deref [this] (extend-clj.core/deref-impl this)))
             (list 'clojure.lang.IAtom
               '(swap [this f] (nth (extend-clj.core/swap* this f ()) 1))
               '(swap [this f a] (nth (extend-clj.core/swap* this f (list a)) 1))
               '(swap [this f a b] (nth (extend-clj.core/swap* this f (list a b)) 1))
               '(swap [this f a b xs] (nth (extend-clj.core/swap* this f (cons a (cons b xs))) 1))
               '(compareAndSet [this oldv newv]
                  (extend-clj.core/validate this @__validator newv)
                  (if (extend-clj.core/compare-and-set-impl this oldv newv)
                    (do (extend-clj.core/notify-watches this oldv newv) true)
                    false))
               '(reset [this newv] (nth (extend-clj.core/swap* this (constantly newv) ()) 1)))
             (list 'clojure.lang.IAtom2
               '(swapVals [this f] (extend-clj.core/swap* this f ()))
               '(swapVals [this f a] (extend-clj.core/swap* this f (list a)))
               '(swapVals [this f a b] (extend-clj.core/swap* this f (list a b)))
               '(swapVals [this f a b xs] (extend-clj.core/swap* this f (cons a (cons b xs))))
               '(resetVals [this newv]
                  (extend-clj.core/validate this @__validator newv)
                  (loop []
                    (let [oldv (extend-clj.core/deref-impl this)]
                      (if (extend-clj.core/compare-and-set-impl this oldv newv)
                        (do (extend-clj.core/notify-watches this oldv newv) [oldv newv])
                        (recur))))))
             (list 'clojure.lang.IMeta
               '(meta [this] @__meta))
             (list 'clojure.lang.IReference
               '(alterMeta [this alter args]
                  (let [m (apply alter @__meta args)]
                    (reset! __meta m)
                    m))
               '(resetMeta [this m]
                  (reset! __meta m)
                  m))
             (list 'clojure.lang.IRef
               '(setValidator [this vf]
                  (extend-clj.core/validate this vf (extend-clj.core/deref-impl this))
                  (reset! __validator vf))
               '(getValidator [this] @__validator)
               '(getWatches [this] @__watches)
               '(addWatch [this key callback]
                  (swap! __watches assoc key callback)
                  this)
               '(removeWatch [this key]
                  (swap! __watches dissoc key)
                  this))
             (list 'clojure.lang.ILookup
               (list 'valAt '[this key]
                 (list* 'case 'key
                   (concat (mapcat (fn [f] [(keyword (str f)) f]) fields)
                           ['nil])))
               (list 'valAt '[this key else]
                 (list* 'case 'key
                   (concat (mapcat (fn [f] [(keyword (str f)) f]) fields)
                           ['else]))))])
            extra))
         (list 'defn ->name
           (list fields
             (list* (symbol (str name ".")) (concat fields ['(clojure.core/atom nil) '(clojure.core/atom {}) '(clojure.core/atom {})])))
           (list (into fields '[& rest])
             (list 'let ['opts '(apply array-map rest)
                         'ref (list* (symbol (str name ".")) (concat fields ['(clojure.core/atom nil) '(clojure.core/atom {}) '(clojure.core/atom {})]))]
               '(when-some [validator (:validator opts)]
                  (extend-clj.core/validate ref validator (extend-clj.core/deref-impl ref))
                  (reset! (.-__validator ref) validator))
               '(when-some [meta (:meta opts)]
                  (reset! (.-__meta ref) meta))
               'ref))))))
   :clj (defmacro deftype-atom
     "Allows you to declare new type that will behave like native Clojure Atom,
      but with custom `deref` and `compare-and-set` behaviors. Rest of swap!/reset!/
      swap-vals!/reset-vals! will be implemented through these two.
      
      Supports meta/validator/watches and ILookup for fields.
      
      Syntax:
      
          (deftype-atom <name> [<field> ...]
            (deref-impl [this]
              <impl>)
            (compare-and-set-impl [this oldv newv]
              <impl>))
      
      To use this structure, use `(-><name> <field> ...)` function. E.g. if you
      
          (deftype-atom X [a b c])
      
      then create instances like
      
          (->X a b c)
      
      You can add more interfaces after deref-impl/compare-and-set-impl and implement them as you wish:
      
          (deftype-atom <name> [<field> ...]
            (deref-impl [this]
              <impl>)
      
            (compare-and-set-impl [this oldv newv]
              <impl>)
            
            clojure.lang.Counted
            (count [this]
              7)
            
            clojure.lang.IBlockingDeref
            (deref [this ms timeout]
              (.deref this)))
      
      Warning: swap-vals! and reset-vals! don’t work in ClojureScript."
     [name fields & methods]
     (if (cljs-env? &env)
       (let [->name    (symbol (str "->" name))
             validator 'validator
             watches   'watches
             meta      'meta]
         `(do
            (deftype ~name [~@fields ~validator ~watches ~meta]
              ~'Object
              (~'equiv [this# other#]
                (cljs.core/-equiv this# other#))

              cljs.core/IEquiv
              (cljs.core/-equiv [o# other#]
                (identical? o# other#))

              cljs.core/IDeref
              (cljs.core/-deref [this#]
                (deref-impl this#))
           
              cljs.core/IReset
              (cljs.core/-reset! [this# newv#]
                (nth (swap* this# (constantly newv#) ()) 1))
           
              cljs.core/ISwap
              (cljs.core/-swap! [this# f#]
                (nth (swap* this# f# ()) 1))
           
              (cljs.core/-swap! [this# f# a#]
                (nth (swap* this# f# (list a#)) 1))
           
              (cljs.core/-swap! [this# f# a# b#]
                (nth (swap* this# f# (list a# b#)) 1))
           
              (cljs.core/-swap! [this# f# a# b# xs#]
                (nth (swap* this# f# (cons a# (cons b# xs#))) 1))

              cljs.core/IMeta
              (cljs.core/-meta [this#]
                ~meta)

              cljs.core/IWatchable
              (cljs.core/-notify-watches [this# oldv# newv#]
                (doseq [[k# f#] ~watches]
                  (f# k# this# oldv# newv#)))
           
              (cljs.core/-add-watch [this# key# f#]
                (set! (.-watches this#) (assoc ~watches key# f#))
                this#)
           
              (cljs.core/-remove-watch [this# key#]
                (set! (.-watches this#) (dissoc ~watches key#)))

              cljs.core/IHash
              (cljs.core/-hash [this#]
                (goog/getUid this#))
              
              cljs.core/ILookup
              (cljs.core/-lookup [this# k#]
                (cljs.core/-lookup this# k# nil))

              (cljs.core/-lookup [this# k# not-found#]
                (case k#
                  ~@(mapcat identity
                      (for [field fields]
                        [(keyword (str field))
                         field]))
                  not-found#))
              
              IAtom3
              (validate [this# validator# value#]
                (when (some? validator#)
                  (when-not (validator# value#)
                    (throw (ex-info "Invalid reference state" {:value value#})))))

              (notify-watches [this# oldv# newv#]
                (doseq [[k# w#] ~watches]
                  (w# k# this# oldv# newv#)))

              (swap* [this# f# args#]
                (let [oldv# (deref this#)
                      newv# (apply f# oldv# args#)]
                  (validate this# ~validator newv#)
                  (compare-and-set-impl this# oldv# newv#)
                  (notify-watches this# oldv# newv#)
                  [oldv# newv#]))
              
              ~@methods)
            (defn ~(with-meta ->name {:declared true})
              ([~@fields]
               (new ~name ~@fields nil {} {}))
              ([~@fields & rest#]
               (let [opts# (apply array-map rest#)
                     ref#  (new ~name ~@fields nil {} {})]
                 (when-some [validator# (:validator opts#)]
                   (validate ref# validator# @ref#)
                   (set! (.-validator ref#) validator#))
                 (when-some [meta# (:meta opts#)]
                   (reset-meta! ref# meta#))
                 ref#)))
            ~name))

       (let [->name      (symbol (str "->" name))
             class       (symbol (str (clojure.string/replace (str *ns*) "-" "_") "." name))
             __validator '__validator
             __watches   '__watches
             __meta      '__meta
             interfaces  (->> methods
                           (filter symbol?)
                           (map #(if (var? (resolve %)) 
                                   (:on (deref (resolve %)))
                                   %)))
             methods     (remove symbol? methods)]
         `(do
            (deftype* ~(symbol (str *ns*) (str name)) ~class
              [~@fields
               ~(with-meta __validator {:volatile-mutable true})
               ~(with-meta __watches {:volatile-mutable true})
               ~(with-meta __meta {:unsynchronized-mutable true})
               __extmap
               ^:unsynchronized-mutable ^int __hash
               ^:unsynchronized-mutable ^int __hasheq]
              :implements [clojure.lang.IMeta
                           clojure.lang.IReference
                           clojure.lang.IDeref
                           clojure.lang.IRef
                           clojure.lang.IAtom
                           clojure.lang.IAtom2
                           extend_clj.core.IAtom3
                           clojure.lang.ILookup
                           clojure.lang.IKeywordLookup
                           ~@interfaces]
              ~@methods
       
              ; clojure.lang.IMeta
              (meta [this#]
                ~__meta)
  
              ; clojure.lang.IReference
              (alterMeta [this# alter# args#]
                (locking this#
                  (.resetMeta this# (apply alter# ~__meta args#))))
      
              (resetMeta [this# m#]
                (locking this#
                  (set! ~__meta m#)
                  m#))

              ; clojure.lang.IDeref
              (deref [this#]
                (.deref-impl this#))

              ; clojure.lang.IRef
              (setValidator [this# vf#]
                (.validate this# vf# (.deref this#))
                (set! ~__validator vf#))

              (getValidator [this#]
                ~__validator)

              (getWatches [this#]
                ~__watches)

              (addWatch [this# key# callback#]
                (locking this#
                  (set! ~__watches (assoc ~__watches key# callback#))
                  this#))

              (removeWatch [this# key#]
                (locking this#
                  (set! ~__watches (dissoc ~__watches key#))
                  this#))
  
              ; clojure.lang.IAtom
              (swap [this# f#]
                (nth (.swap* this# f# ()) 1))
  
              (swap [this# f# arg#]
                (nth (.swap* this# f# (list arg#)) 1))
  
              (swap [this# f# arg1# arg2#]
                (nth (.swap* this# f# (list arg1# arg2#)) 1))
  
              (swap [this# f# arg1# arg2# rest#]
                (nth (.swap* this# f# (cons arg1# (cons arg2# rest#))) 1))

              (compareAndSet [this# oldv# newv#]
                (.validate this# ~__validator newv#)
                (if (.compare-and-set-impl this# oldv# newv#)
                  (do
                    (.notify-watches this# oldv# newv#)
                    true)
                  false))

              (reset [this# newv#]
                (nth (.resetVals this# newv#) 1))
  
              ; clojure.lang.IAtom2
              (swapVals [this# f#]
                (.swap* this# f# ()))
  
              (swapVals [this# f# arg#]
                (.swap* this# f# (list arg#)))
  
              (swapVals [this# f# arg1# arg2#]
                (.swap* this# f# (list arg1# arg2#)))
  
              (swapVals [this# f# arg1# arg2# rest#]
                (.swap* this# f# (cons arg1# (cons arg2# rest#))))

              (resetVals [this# newv#]
                (.validate this# ~__validator newv#)
                (loop []
                  (let [oldv# (.deref this#)]
                    (if (.compare-and-set-impl this# oldv# newv#)
                      (do
                        (.notify-watches this# oldv# newv#)
                        [oldv# newv#])
                      (recur)))))

              ; IAtom3
              (validate [this# validator# value#]
                (when (some? validator#)
                  (when-not (validator# value#)
                    (throw (ex-info "Invalid reference state" {:value value#})))))

              (notify-watches [this# oldv# newv#]
                (doseq [[k# w#] ~__watches]
                  (w# k# this# oldv# newv#)))

              (swap* [this# f# args#]
                (loop []
                  (let [oldv# (.deref this#)
                        newv# (apply f# oldv# args#)]
                    (if (.compareAndSet this# oldv# newv#)
                      [oldv# newv#]
                      (recur)))))
  
              ; clojure.lang.ILookup
              (valAt [this# key#]
                (.valAt this# key# nil))

              (valAt [this# key# else#]
                (case key#
                  ~@(mapcat identity
                      (for [field fields]
                        [(keyword (clojure.core/name field)) (with-meta field {})]))
                  else#))
         
              ; clojure.lang.IKeywordLookup
              ~(let [gclass  (gensym "gclass")
                     gtarget (gensym "gtarget")] 
                 `(getLookupThunk [this# k#]
                    (let [~gclass (class this#)]
                      (case k#
                        ~@(mapcat identity
                            (for [field fields]
                              [(keyword (clojure.core/name field))
                               `(reify clojure.lang.ILookupThunk
                                  (get [thunk# ~gtarget]
                                    (if (identical? (class ~gtarget) ~gclass)
                                      (. ~(with-meta gtarget {:tag name}) ~(symbol (str "-" (str field))))
                                      thunk#)))]))
                        nil)))))
       
            (import ~class)
       
            (defn ~->name
              ([~@fields]
               (new ~name ~@fields nil {}))
              ([~@fields & rest#]
               (let [opts# (apply array-map rest#)
                     ref#  (new ~name ~@fields nil {})]
                 (when-some [validator# (:validator opts#)]
                   (.setValidator ref# validator#))
                 (when-some [meta# (:meta opts#)]
                   (.resetMeta ref# meta#))
                 ref#)))

            ~class)))) :cljr (defmacro deftype-atom
     "Allows you to declare new type that will behave like native Clojure Atom,
      but with custom `deref` and `compare-and-set` behaviors. Rest of swap!/reset!/
      swap-vals!/reset-vals! will be implemented through these two.
      
      Supports meta/validator/watches and ILookup for fields.
      
      Syntax:
      
          (deftype-atom <name> [<field> ...]
            (deref-impl [this]
              <impl>)
            (compare-and-set-impl [this oldv newv]
              <impl>))
      
      To use this structure, use `(-><name> <field> ...)` function. E.g. if you
      
          (deftype-atom X [a b c])
      
      then create instances like
      
          (->X a b c)
      
      You can add more interfaces after deref-impl/compare-and-set-impl and implement them as you wish:
      
          (deftype-atom <name> [<field> ...]
            (deref-impl [this]
              <impl>)
      
            (compare-and-set-impl [this oldv newv]
              <impl>)
            
            clojure.lang.Counted
            (count [this]
              7)
            
            clojure.lang.IBlockingDeref
            (deref [this ms timeout]
              (.deref this)))
      
      Warning: swap-vals! and reset-vals! don’t work in ClojureScript."
     [name fields & methods]
     (if (cljs-env? &env)
       (let [->name    (symbol (str "->" name))
             validator 'validator
             watches   'watches
             meta      'meta]
         `(do
            (deftype ~name [~@fields ~validator ~watches ~meta]
              ~'Object
              (~'equiv [this# other#]
                (cljs.core/-equiv this# other#))

              cljs.core/IEquiv
              (cljs.core/-equiv [o# other#]
                (identical? o# other#))

              cljs.core/IDeref
              (cljs.core/-deref [this#]
                (deref-impl this#))
           
              cljs.core/IReset
              (cljs.core/-reset! [this# newv#]
                (nth (swap* this# (constantly newv#) ()) 1))
           
              cljs.core/ISwap
              (cljs.core/-swap! [this# f#]
                (nth (swap* this# f# ()) 1))
           
              (cljs.core/-swap! [this# f# a#]
                (nth (swap* this# f# (list a#)) 1))
           
              (cljs.core/-swap! [this# f# a# b#]
                (nth (swap* this# f# (list a# b#)) 1))
           
              (cljs.core/-swap! [this# f# a# b# xs#]
                (nth (swap* this# f# (cons a# (cons b# xs#))) 1))

              cljs.core/IMeta
              (cljs.core/-meta [this#]
                ~meta)

              cljs.core/IWatchable
              (cljs.core/-notify-watches [this# oldv# newv#]
                (doseq [[k# f#] ~watches]
                  (f# k# this# oldv# newv#)))
           
              (cljs.core/-add-watch [this# key# f#]
                (set! (.-watches this#) (assoc ~watches key# f#))
                this#)
           
              (cljs.core/-remove-watch [this# key#]
                (set! (.-watches this#) (dissoc ~watches key#)))

              cljs.core/IHash
              (cljs.core/-hash [this#]
                (goog/getUid this#))
              
              cljs.core/ILookup
              (cljs.core/-lookup [this# k#]
                (cljs.core/-lookup this# k# nil))

              (cljs.core/-lookup [this# k# not-found#]
                (case k#
                  ~@(mapcat identity
                      (for [field fields]
                        [(keyword (str field))
                         field]))
                  not-found#))
              
              IAtom3
              (validate [this# validator# value#]
                (when (some? validator#)
                  (when-not (validator# value#)
                    (throw (ex-info "Invalid reference state" {:value value#})))))

              (notify-watches [this# oldv# newv#]
                (doseq [[k# w#] ~watches]
                  (w# k# this# oldv# newv#)))

              (swap* [this# f# args#]
                (let [oldv# (deref this#)
                      newv# (apply f# oldv# args#)]
                  (validate this# ~validator newv#)
                  (compare-and-set-impl this# oldv# newv#)
                  (notify-watches this# oldv# newv#)
                  [oldv# newv#]))
              
              ~@methods)
            (defn ~(with-meta ->name {:declared true})
              ([~@fields]
               (new ~name ~@fields nil {} {}))
              ([~@fields & rest#]
               (let [opts# (apply array-map rest#)
                     ref#  (new ~name ~@fields nil {} {})]
                 (when-some [validator# (:validator opts#)]
                   (validate ref# validator# @ref#)
                   (set! (.-validator ref#) validator#))
                 (when-some [meta# (:meta opts#)]
                   (reset-meta! ref# meta#))
                 ref#)))
            ~name))

       (let [->name      (symbol (str "->" name))
             class       (symbol (str (clojure.string/replace (str *ns*) "-" "_") "." name))
             __validator '__validator
             __watches   '__watches
             __meta      '__meta
             interfaces  (->> methods
                           (filter symbol?)
                           (map #(if (var? (resolve %)) 
                                   (:on (deref (resolve %)))
                                   %)))
             methods     (remove symbol? methods)]
         `(do
            (deftype* ~(symbol (str *ns*) (str name)) ~class
              [~@fields
               ~(with-meta __validator {:volatile-mutable true})
               ~(with-meta __watches {:volatile-mutable true})
               ~(with-meta __meta {:unsynchronized-mutable true})
               __extmap
               ^:unsynchronized-mutable ^int __hash
               ^:unsynchronized-mutable ^int __hasheq]
              :implements [clojure.lang.IMeta
                           clojure.lang.IReference
                           clojure.lang.IDeref
                           clojure.lang.IRef
                           clojure.lang.IAtom
                           clojure.lang.IAtom2
                           extend_clj.core.IAtom3
                           clojure.lang.ILookup
                           clojure.lang.IKeywordLookup
                           ~@interfaces]
              ~@methods
       
              ; clojure.lang.IMeta
              (meta [this#]
                ~__meta)
  
              ; clojure.lang.IReference
              (alterMeta [this# alter# args#]
                (locking this#
                  (.resetMeta this# (apply alter# ~__meta args#))))
      
              (resetMeta [this# m#]
                (locking this#
                  (set! ~__meta m#)
                  m#))

              ; clojure.lang.IDeref
              (deref [this#]
                (.deref-impl this#))

              ; clojure.lang.IRef
              (setValidator [this# vf#]
                (.validate this# vf# (.deref this#))
                (set! ~__validator vf#))

              (getValidator [this#]
                ~__validator)

              (getWatches [this#]
                ~__watches)

              (addWatch [this# key# callback#]
                (locking this#
                  (set! ~__watches (assoc ~__watches key# callback#))
                  this#))

              (removeWatch [this# key#]
                (locking this#
                  (set! ~__watches (dissoc ~__watches key#))
                  this#))
  
              ; clojure.lang.IAtom
              (swap [this# f#]
                (nth (.swap* this# f# ()) 1))
  
              (swap [this# f# arg#]
                (nth (.swap* this# f# (list arg#)) 1))
  
              (swap [this# f# arg1# arg2#]
                (nth (.swap* this# f# (list arg1# arg2#)) 1))
  
              (swap [this# f# arg1# arg2# rest#]
                (nth (.swap* this# f# (cons arg1# (cons arg2# rest#))) 1))

              (compareAndSet [this# oldv# newv#]
                (.validate this# ~__validator newv#)
                (if (.compare-and-set-impl this# oldv# newv#)
                  (do
                    (.notify-watches this# oldv# newv#)
                    true)
                  false))

              (reset [this# newv#]
                (nth (.resetVals this# newv#) 1))
  
              ; clojure.lang.IAtom2
              (swapVals [this# f#]
                (.swap* this# f# ()))
  
              (swapVals [this# f# arg#]
                (.swap* this# f# (list arg#)))
  
              (swapVals [this# f# arg1# arg2#]
                (.swap* this# f# (list arg1# arg2#)))
  
              (swapVals [this# f# arg1# arg2# rest#]
                (.swap* this# f# (cons arg1# (cons arg2# rest#))))

              (resetVals [this# newv#]
                (.validate this# ~__validator newv#)
                (loop []
                  (let [oldv# (.deref this#)]
                    (if (.compare-and-set-impl this# oldv# newv#)
                      (do
                        (.notify-watches this# oldv# newv#)
                        [oldv# newv#])
                      (recur)))))

              ; IAtom3
              (validate [this# validator# value#]
                (when (some? validator#)
                  (when-not (validator# value#)
                    (throw (ex-info "Invalid reference state" {:value value#})))))

              (notify-watches [this# oldv# newv#]
                (doseq [[k# w#] ~__watches]
                  (w# k# this# oldv# newv#)))

              (swap* [this# f# args#]
                (loop []
                  (let [oldv# (.deref this#)
                        newv# (apply f# oldv# args#)]
                    (if (.compareAndSet this# oldv# newv#)
                      [oldv# newv#]
                      (recur)))))
  
              ; clojure.lang.ILookup
              (valAt [this# key#]
                (.valAt this# key# nil))

              (valAt [this# key# else#]
                (case key#
                  ~@(mapcat identity
                      (for [field fields]
                        [(keyword (clojure.core/name field)) (with-meta field {})]))
                  else#))
         
              ; clojure.lang.IKeywordLookup
              ~(let [gclass  (gensym "gclass")
                     gtarget (gensym "gtarget")] 
                 `(getLookupThunk [this# k#]
                    (let [~gclass (class this#)]
                      (case k#
                        ~@(mapcat identity
                            (for [field fields]
                              [(keyword (clojure.core/name field))
                               `(reify clojure.lang.ILookupThunk
                                  (get [thunk# ~gtarget]
                                    (if (identical? (class ~gtarget) ~gclass)
                                      (. ~(with-meta gtarget {:tag name}) ~(symbol (str "-" (str field))))
                                      thunk#)))]))
                        nil)))))
       
            (import ~class)
       
            (defn ~->name
              ([~@fields]
               (new ~name ~@fields nil {}))
              ([~@fields & rest#]
               (let [opts# (apply array-map rest#)
                     ref#  (new ~name ~@fields nil {})]
                 (when-some [validator# (:validator opts#)]
                   (.setValidator ref# validator#))
                 (when-some [meta# (:meta opts#)]
                   (.resetMeta ref# meta#))
                 ref#)))

            ~class))))
   :lpy (defmacro deftype-atom
     "basilisp form: the :clj expansion is JVM-shaped (deftype* with a
      symbol in the fields slot, protocol-var interface mapping) and
      cannot run here. This arm builds the expansion as data (no
      syntax quote — it ns-qualifies group symbols on this seat):
      an eve.deftype whose JVM-interface groups Eve's basilisp type
      layer translates (IDeref/IAtom/IAtom2/IMeta/IReference/IRef/
      ILookup), with deref-impl/compare-and-set-impl and the atom
      helpers as Object methods called via (.m this), exactly as the
      :clj text calls them. __validator/__watches/__meta are
      atom-valued fields (seeded by the factory), so no field
      mutability is needed. A deftype on this seat must not carry a
      single JVM-interface group (it is silently dropped — measured),
      which this shape never risks."
     [name fields & methods]
     (let [deref-impl (first (filter #(= 'deref-impl (first %)) methods))
           cas-impl   (first (filter #(= 'compare-and-set-impl (first %)) methods))
           extra      (remove #(contains? #{'deref-impl 'compare-and-set-impl} (first %)) methods)
           ->name     (symbol (str "->" name))
           all-fields (into fields '[__validator __watches __meta])]
       (list 'do
         (list* 'deftype name all-fields
           ;; eve.deftype takes FLAT groups: protocol symbol, then its
           ;; methods as siblings — flatten each (head m...) one level
           (mapcat (fn [g] (cons (first g) (rest g)))
           (concat
            [(list 'Object
               (list 'deref-impl (second deref-impl) (nth deref-impl 2))
               (list 'compare-and-set-impl (second cas-impl) (nth cas-impl 2))
               '(validate [this vf value]
                  (when (some? vf)
                    (when-not (vf value)
                      (throw (ex-info "Invalid reference state" {:value value})))))
               '(notify-watches [this oldv newv]
                  (doseq [[k w] @__watches]
                    (w k this oldv newv)))
               '(swap* [this f args]
                  (loop []
                    (let [oldv (.deref-impl this)
                          newv (apply f oldv args)]
                      (if (.compareAndSet this oldv newv)
                        [oldv newv]
                        (recur)))))
               '(swap [this f] (nth (.swap* this f ()) 1))
               '(swap [this f arg] (nth (.swap* this f (list arg)) 1))
               '(swap [this f arg1 arg2] (nth (.swap* this f (list arg1 arg2)) 1))
               '(swap [this f arg1 arg2 rest] (nth (.swap* this f (cons arg1 (cons arg2 rest))) 1))
               '(compareAndSet [this oldv newv]
                  (.validate this @__validator newv)
                  (if (.compare-and-set-impl this oldv newv)
                    (do (.notify-watches this oldv newv) true)
                    false))
               '(reset [this newv]
                  (.validate this @__validator newv)
                  (loop []
                    (let [oldv (.deref-impl this)]
                      (if (.compare-and-set-impl this oldv newv)
                        (do (.notify-watches this oldv newv) newv)
                        (recur))))))
             (list 'clojure.lang.IDeref
               '(deref [this] (.deref-impl this)))
             (list 'clojure.lang.IMeta
               '(meta [this] @__meta)
               '(alterMeta [this alter args]
                  (let [m (apply alter @__meta args)]
                    (reset! __meta m)
                    m))
               '(resetMeta [this m]
                  (reset! __meta m)
                  m)
               '(setValidator [this vf]
                  (.validate this vf (.deref-impl this))
                  (reset! __validator vf))
               '(getValidator [this] @__validator)
               '(getWatches [this] @__watches)
               '(addWatch [this key callback]
                  (swap! __watches assoc key callback)
                  this)
               '(removeWatch [this key]
                  (swap! __watches dissoc key)
                  this))
             (list 'clojure.lang.ILookup
               '(valAt [this key] (.valAt this key nil))
               (list 'valAt '[this key else]
                 (list* 'case 'key
                   (concat (mapcat (fn [f] [(keyword (str f)) f]) fields)
                           ['else]))))]
            extra)))
         (list 'defn ->name
           (list fields
             (list* 'new name (concat fields ['(atom nil) '(atom {}) '(atom {})])))
           (list (into fields '[& rest])
             (list 'let ['opts '(apply array-map rest)
                         'ref (list* 'new name (concat fields ['(atom nil) '(atom {}) '(atom {})]))]
               '(when-some [validator (:validator opts)]
                  (.setValidator ref validator))
               '(when-some [meta (:meta opts)]
                  (.resetMeta ref meta))
               'ref))))))
   :cljs (defmacro deftype-atom
     "Allows you to declare new type that will behave like native Clojure Atom,
      but with custom `deref` and `compare-and-set` behaviors. Rest of swap!/reset!/
      swap-vals!/reset-vals! will be implemented through these two.
      
      Supports meta/validator/watches and ILookup for fields.
      
      Syntax:
      
          (deftype-atom <name> [<field> ...]
            (deref-impl [this]
              <impl>)
            (compare-and-set-impl [this oldv newv]
              <impl>))
      
      To use this structure, use `(-><name> <field> ...)` function. E.g. if you
      
          (deftype-atom X [a b c])
      
      then create instances like
      
          (->X a b c)
      
      You can add more interfaces after deref-impl/compare-and-set-impl and implement them as you wish:
      
          (deftype-atom <name> [<field> ...]
            (deref-impl [this]
              <impl>)
      
            (compare-and-set-impl [this oldv newv]
              <impl>)
            
            clojure.lang.Counted
            (count [this]
              7)
            
            clojure.lang.IBlockingDeref
            (deref [this ms timeout]
              (.deref this)))
      
      Warning: swap-vals! and reset-vals! don’t work in ClojureScript."
     [name fields & methods]
     (if (cljs-env? &env)
       (let [->name    (symbol (str "->" name))
             validator 'validator
             watches   'watches
             meta      'meta]
         `(do
            (deftype ~name [~@fields ~validator ~watches ~meta]
              ;; PORT DELTA (F-NBB-MACROS): vanilla's expansion also
              ;; emits an Object group with equiv; the interpreted
              ;; CLJS seat's deftype (nbb/SCI) has no Object equiv
              ;; (sci.impl.deftype/equiv does not exist there), and
              ;; cljs.core/IEquiv below carries the same body

              cljs.core/IEquiv
              (cljs.core/-equiv [o# other#]
                (identical? o# other#))

              cljs.core/IDeref
              (cljs.core/-deref [this#]
                (deref-impl this#))
           
              cljs.core/IReset
              (cljs.core/-reset! [this# newv#]
                (nth (swap* this# (constantly newv#) ()) 1))
           
              cljs.core/ISwap
              (cljs.core/-swap! [this# f#]
                (nth (swap* this# f# ()) 1))
           
              (cljs.core/-swap! [this# f# a#]
                (nth (swap* this# f# (list a#)) 1))
           
              (cljs.core/-swap! [this# f# a# b#]
                (nth (swap* this# f# (list a# b#)) 1))
           
              (cljs.core/-swap! [this# f# a# b# xs#]
                (nth (swap* this# f# (cons a# (cons b# xs#))) 1))

              cljs.core/IMeta
              (cljs.core/-meta [this#]
                ~meta)

              cljs.core/IWatchable
              (cljs.core/-notify-watches [this# oldv# newv#]
                (doseq [[k# f#] ~watches]
                  (f# k# this# oldv# newv#)))
           
              (cljs.core/-add-watch [this# key# f#]
                (set! (.-watches this#) (assoc ~watches key# f#))
                this#)
           
              (cljs.core/-remove-watch [this# key#]
                (set! (.-watches this#) (dissoc ~watches key#)))

              cljs.core/IHash
              (cljs.core/-hash [this#]
                (goog/getUid this#))
              
              cljs.core/ILookup
              (cljs.core/-lookup [this# k#]
                (cljs.core/-lookup this# k# nil))

              (cljs.core/-lookup [this# k# not-found#]
                (case k#
                  ~@(mapcat identity
                      (for [field fields]
                        [(keyword (str field))
                         field]))
                  not-found#))
              
              IAtom3
              (validate [this# validator# value#]
                (when (some? validator#)
                  (when-not (validator# value#)
                    (throw (ex-info "Invalid reference state" {:value value#})))))

              (notify-watches [this# oldv# newv#]
                (doseq [[k# w#] ~watches]
                  (w# k# this# oldv# newv#)))

              (swap* [this# f# args#]
                (let [oldv# (deref this#)
                      newv# (apply f# oldv# args#)]
                  (validate this# ~validator newv#)
                  (compare-and-set-impl this# oldv# newv#)
                  (notify-watches this# oldv# newv#)
                  [oldv# newv#]))
              
              ~@methods)
            (defn ~(with-meta ->name {:declared true})
              ([~@fields]
               (new ~name ~@fields nil {} {}))
              ([~@fields & rest#]
               (let [opts# (apply array-map rest#)
                     ref#  (new ~name ~@fields nil {} {})]
                 (when-some [validator# (:validator opts#)]
                   (validate ref# validator# @ref#)
                   (set! (.-validator ref#) validator#))
                 (when-some [meta# (:meta opts#)]
                   (reset-meta! ref# meta#))
                 ref#)))
            ~name))

       (let [->name      (symbol (str "->" name))
             class       (symbol (str (clojure.string/replace (str *ns*) "-" "_") "." name))
             __validator '__validator
             __watches   '__watches
             __meta      '__meta
             interfaces  (->> methods
                           (filter symbol?)
                           (map #(if (var? (resolve %)) 
                                   (:on (deref (resolve %)))
                                   %)))
             methods     (remove symbol? methods)]
         `(do
            (deftype* ~(symbol (str *ns*) (str name)) ~class
              [~@fields
               ~(with-meta __validator {:volatile-mutable true})
               ~(with-meta __watches {:volatile-mutable true})
               ~(with-meta __meta {:unsynchronized-mutable true})
               __extmap
               ^:unsynchronized-mutable ^int __hash
               ^:unsynchronized-mutable ^int __hasheq]
              :implements [clojure.lang.IMeta
                           clojure.lang.IReference
                           clojure.lang.IDeref
                           clojure.lang.IRef
                           clojure.lang.IAtom
                           clojure.lang.IAtom2
                           extend_clj.core.IAtom3
                           clojure.lang.ILookup
                           clojure.lang.IKeywordLookup
                           ~@interfaces]
              ~@methods
       
              ; clojure.lang.IMeta
              (meta [this#]
                ~__meta)
  
              ; clojure.lang.IReference
              (alterMeta [this# alter# args#]
                (locking this#
                  (.resetMeta this# (apply alter# ~__meta args#))))
      
              (resetMeta [this# m#]
                (locking this#
                  (set! ~__meta m#)
                  m#))

              ; clojure.lang.IDeref
              (deref [this#]
                (.deref-impl this#))

              ; clojure.lang.IRef
              (setValidator [this# vf#]
                (.validate this# vf# (.deref this#))
                (set! ~__validator vf#))

              (getValidator [this#]
                ~__validator)

              (getWatches [this#]
                ~__watches)

              (addWatch [this# key# callback#]
                (locking this#
                  (set! ~__watches (assoc ~__watches key# callback#))
                  this#))

              (removeWatch [this# key#]
                (locking this#
                  (set! ~__watches (dissoc ~__watches key#))
                  this#))
  
              ; clojure.lang.IAtom
              (swap [this# f#]
                (nth (.swap* this# f# ()) 1))
  
              (swap [this# f# arg#]
                (nth (.swap* this# f# (list arg#)) 1))
  
              (swap [this# f# arg1# arg2#]
                (nth (.swap* this# f# (list arg1# arg2#)) 1))
  
              (swap [this# f# arg1# arg2# rest#]
                (nth (.swap* this# f# (cons arg1# (cons arg2# rest#))) 1))

              (compareAndSet [this# oldv# newv#]
                (.validate this# ~__validator newv#)
                (if (.compare-and-set-impl this# oldv# newv#)
                  (do
                    (.notify-watches this# oldv# newv#)
                    true)
                  false))

              (reset [this# newv#]
                (nth (.resetVals this# newv#) 1))
  
              ; clojure.lang.IAtom2
              (swapVals [this# f#]
                (.swap* this# f# ()))
  
              (swapVals [this# f# arg#]
                (.swap* this# f# (list arg#)))
  
              (swapVals [this# f# arg1# arg2#]
                (.swap* this# f# (list arg1# arg2#)))
  
              (swapVals [this# f# arg1# arg2# rest#]
                (.swap* this# f# (cons arg1# (cons arg2# rest#))))

              (resetVals [this# newv#]
                (.validate this# ~__validator newv#)
                (loop []
                  (let [oldv# (.deref this#)]
                    (if (.compare-and-set-impl this# oldv# newv#)
                      (do
                        (.notify-watches this# oldv# newv#)
                        [oldv# newv#])
                      (recur)))))

              ; IAtom3
              (validate [this# validator# value#]
                (when (some? validator#)
                  (when-not (validator# value#)
                    (throw (ex-info "Invalid reference state" {:value value#})))))

              (notify-watches [this# oldv# newv#]
                (doseq [[k# w#] ~__watches]
                  (w# k# this# oldv# newv#)))

              (swap* [this# f# args#]
                (loop []
                  (let [oldv# (.deref this#)
                        newv# (apply f# oldv# args#)]
                    (if (.compareAndSet this# oldv# newv#)
                      [oldv# newv#]
                      (recur)))))
  
              ; clojure.lang.ILookup
              (valAt [this# key#]
                (.valAt this# key# nil))

              (valAt [this# key# else#]
                (case key#
                  ~@(mapcat identity
                      (for [field fields]
                        [(keyword (clojure.core/name field)) (with-meta field {})]))
                  else#))
         
              ; clojure.lang.IKeywordLookup
              ~(let [gclass  (gensym "gclass")
                     gtarget (gensym "gtarget")] 
                 `(getLookupThunk [this# k#]
                    (let [~gclass (class this#)]
                      (case k#
                        ~@(mapcat identity
                            (for [field fields]
                              [(keyword (clojure.core/name field))
                               `(reify clojure.lang.ILookupThunk
                                  (get [thunk# ~gtarget]
                                    (if (identical? (class ~gtarget) ~gclass)
                                      (. ~(with-meta gtarget {:tag name}) ~(symbol (str "-" (str field))))
                                      thunk#)))]))
                        nil)))))
       
            (import ~class)
       
            (defn ~->name
              ([~@fields]
               (new ~name ~@fields nil {}))
              ([~@fields & rest#]
               (let [opts# (apply array-map rest#)
                     ref#  (new ~name ~@fields nil {})]
                 (when-some [validator# (:validator opts#)]
                   (.setValidator ref# validator#))
                 (when-some [meta# (:meta opts#)]
                   (.resetMeta ref# meta#))
                 ref#)))

            ~class)))))

;; PORT DELTA (F-CLJE-DEFTYPE-ATOM): deftype-atom had :clj/:cljs arms
;; only; on clojerl the macro did not exist and datascript.conn's
;; (extend/deftype-atom Conn ...) analyzed Conn as a value. The :clje
;; arm below expands to a plain clojerl deftype with clojerl protocol
;; spellings: clojerl.IDeref for deref, clojerl.ILookup for field
;; lookup, this file's IAtom3 for the atom machinery (user methods
;; splice into it, as in the :cljs arm), and eve.host/IAtom so
;; eve.host/swap!, reset! and compare-and-set! (which dispatch
;; clojerl.Atom vs IAtom) reach the type. swap* follows the :clj arm's
;; CAS loop (the :cljs arm is single-attempt). Validator/watches/meta
;; are plain fields set at construction: clojerl core add-watch throws
;; "unimplemented watches", so watch mutation is a named seat gap.
#?(:clje (defmacro deftype-atom
           [name fields & methods]
           (let [->name   (symbol (str "->" name))
                  validator 'validator
                  watches   'watches
                  meta      'meta]
             `(do
                (deftype ~name [~@fields ~validator ~watches ~meta]
                  clojerl.IDeref
                  (deref [this#]
                    (deref-impl this#))

                  clojerl.ILookup
                  (get [this# k#]
                    (case k#
                      ~@(mapcat identity
                          (for [field fields]
                            [(keyword (str field)) field]))
                      nil))
                  (get [this# k# not-found#]
                    (case k#
                      ~@(mapcat identity
                          (for [field fields]
                            [(keyword (str field)) field]))
                      not-found#))

                  extend-clj.core/IAtom3
                  (validate [this# validator# value#]
                    (when (some? validator#)
                      (when-not (validator# value#)
                        (throw (ex-info "Invalid reference state" {:value value#})))))

                  (notify-watches [this# oldv# newv#]
                    (doseq [[k# w#] ~watches]
                      (w# k# this# oldv# newv#)))

                  (swap* [this# f# args#]
                    (loop []
                      (let [oldv# (deref this#)
                            newv# (apply f# oldv# args#)]
                        (validate this# ~validator newv#)
                        (if (compare-and-set-impl this# oldv# newv#)
                          (do (notify-watches this# oldv# newv#)
                              [oldv# newv#])
                          (recur)))))

                  ~@methods

                  eve.host/IAtom
                  (-swap [this# f# args#]
                    (nth (swap* this# f# args#) 1))
                  (-reset [this# v#]
                    (nth (swap* this# (constantly v#) ()) 1))
                  (-compare-and-set [this# oldv# newv#]
                    (compare-and-set-impl this# oldv# newv#)))

                (defn ~->name
                  ([~@fields]
                   (new ~name ~@fields nil {} nil))
                  ([~@fields & rest#]
                   (let [opts# (apply array-map rest#)
                         ref#  (new ~name ~@fields (:validator opts#) {} (:meta opts#))]
                     (when-some [validator# (:validator opts#)]
                       (validate ref# validator# @ref#))
                     ref#)))

                ~name))))
