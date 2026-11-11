(ns eve.protocol
  "Protocols with the same declaration and metadata extension on every host."
  #?(:cljs (:require-macros [eve.protocol]))
  (:refer-clojure :exclude [defprotocol]))

#?(:lpy
   (defn metadata-dispatch!
     "Keep singledispatch registration (extend/extend-type) while giving
      metadata precedence over external extensions, but not direct impls."
     [protocol method-var method-key]
     (let [original @method-var
           interface (:interface protocol)
           wrapper (fn [& args]
                     (let [target (first args)
                           via-meta (when-not (instance? interface target)
                                      (get (meta target) method-key))]
                       (cond
                         (instance? interface target)
                         (apply (python/getattr target (munge (name method-key))) (rest args))
                         via-meta (apply via-meta args)
                         :else (apply (.dispatch original (type target)) args))))]
       (doseq [attr ["register" "dispatch" "registry" "_clear_cache" "arities"]]
         (python/setattr wrapper attr (python/getattr original attr)))
       (alter-var-root method-var (constantly wrapper))
       wrapper)))

(defmacro defprotocol
  "Clojure defprotocol, including :extend-via-metadata true on Basilisp."
  [protocol-name & declarations]
  #?(:lpy
     (let [doc (when (string? (first declarations)) (first declarations))
           declarations (if doc (rest declarations) declarations)
           [options methods] (loop [opts {} xs declarations]
                               (if (keyword? (first xs))
                                 (recur (assoc opts (first xs) (second xs)) (nnext xs))
                                 [opts xs]))
           methods (if (:extend-via-metadata options)
                     (map (fn [method]
                            (cons (vary-meta (first method) assoc :redef true) (rest method))) methods)
                     methods)
           unsupported (dissoc options :extend-via-metadata)]
       (when (seq unsupported)
         (throw (ex-info "Unsupported protocol options" {:options unsupported})))
       `(do (clojure.core/defprotocol ~protocol-name ~@(when doc [doc]) ~@methods)
            ~@(when (:extend-via-metadata options)
                (for [[method-name] methods]
                  `(alter-var-root (var ~protocol-name) assoc-in [:methods ~(keyword (name method-name))]
                     (metadata-dispatch! ~protocol-name (var ~method-name)
                       '~(symbol (str (ns-name *ns*)) (name method-name))))))
            ~protocol-name))
     :default (list* 'clojure.core/defprotocol protocol-name declarations)))
