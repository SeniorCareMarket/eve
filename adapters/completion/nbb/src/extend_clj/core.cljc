(ns extend-clj.core
  "extend-clj.core on nbb (Eve's completion for the nbb seat,
   adapters/completion/nbb — the glojure completion's shape: the seat
   provides the namespace the library expects, ahead of the library's
   own on this seat's classpath).

   extend-clj's own :cljs face implements deftype-atom over
   cljs.core/deftype, building instances with (new Name ...) inside
   the ->Name factory it then defines. On compiled ClojureScript,
   `new` is the raw constructor; on this seat SCI builds a
   (new T ...) through the ->T var (probed: a plain deftype's
   (new Foo 1 2 3 4) answers the ->Foo factory's value), so the
   factory recurses into its own variadic arity and
   (apply array-map (nil {} {})) dies — \"No value supplied for
   key: {}\" (datascript.conn's ->Conn; F-NBB-NEWFACTORY).

   Here deftype-atom is extend-clj's :cljs construction with one
   seat delta: the deftype's raw factory is captured (as Name__raw)
   before ->Name is redefined — Eve's own pattern on this seat
   (eve.types-js keeps T__new the same way) — and ->Name builds
   through the capture. Everything else is extend-clj's :cljs face
   as the DataScript port carries it, including its F-NBB-MACROS
   delta (no Object equiv group on this seat; IEquiv carries it)."
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

#?(:cljs
   (defmacro deftype-atom
     "extend-clj's deftype-atom, :cljs face (see the ns docstring for
      the one seat delta: ->Name builds through the captured raw
      factory Name__raw, never through (new Name ...))."
     [name fields & methods]
     (let [->name    (symbol (str "->" name))
           raw-name  (symbol (str name "__raw"))
           validator 'validator
           watches   'watches
           meta      'meta]
       `(do
          (deftype ~name [~@fields ~validator ~watches ~meta]
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
          ;; the deftype's raw factory, captured before ->Name is
          ;; redefined: on this seat (new Name ...) routes through
          ;; the ->Name var, so the factory must build through this
          (def ~raw-name ~->name)
          (defn ~(with-meta ->name {:declared true})
            ([~@fields]
             (~raw-name ~@fields nil {} {}))
            ([~@fields & rest#]
             (let [opts# (apply array-map rest#)
                   ref#  (~raw-name ~@fields nil {} {})]
               (when-some [validator# (:validator opts#)]
                 (validate ref# validator# @ref#)
                 (set! (.-validator ref#) validator#))
               (when-some [meta# (:meta opts#)]
                 (reset-meta! ref# meta#))
               ref#)))
          ~name))))
