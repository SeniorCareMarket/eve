(ns eve.logical-protocol
  "SCI protocol extensions dispatched by the EXISTING Eve logical type entry.
   No class→type table and no extension registry: entries own their extensions.
   Installed by the bb type producer; ordinary host dispatch is untouched.")

(defn- registry [] (when-let [v (resolve 'eve.deftype.registry/registry)] @v))

(defn registered-key [host-type]
  (some (fn [[key spec]]
          (when (and (some? host-type) (= key (:type-key spec)) (= host-type (:host-type spec))) key))
        (when-let [r (registry)] @r)))

(defn value-key [x]
  ;; type on this seat already honors the producer's SciMap :type stamp.
  ;; e3b registers that realization on the canonical existing entry.
  (if-let [identity-api (resolve 'eve.deftype.registry/type-key-of)]
    (@identity-api x)
    ;; PENDING e3b collation: same existing-entry lookup, no fallback registry.
    (registered-key (type x))))

(defn- extension [x method]
  (when-let [key (value-key x)]
    (get-in @(registry) [key :protocol-extensions method])))

(defn register-extension! [t protocol methods method-vars]
  (let [key (registered-key t)]
    (when-not key (throw (ex-info "Eve logical protocol: type not registered" {:type t})))
    (swap! (registry) update-in [key :protocol-extensions] merge methods)
    (swap! (registry) update-in [key :extended-protocols] (fnil conj #{}) protocol)
    (doseq [[method v] method-vars]
      ;; SCI's protocol method MUST remain a MultiFn: native extend-type casts
      ;; its root to MultiFn even after an Eve logical extension was installed.
      (let [^clojure.lang.MultiFn multifn @v]
        (.addMethod multifn t (get methods method))
        (when-not (:eve/logical-dispatch (meta v))
          (let [scimap (Class/forName "babashka.impl.SciMap")
                previous (.getMethod multifn scimap)]
            (.addMethod multifn scimap
              (fn [& args]
                (let [value (first args)
                      logical-type (type value)
                      own (when (not= logical-type scimap)
                            (.getMethod multifn logical-type))
                      f (or (extension value method) own previous
                            (.getMethod multifn Object))]
                  (if f (apply f args)
                    (throw (ex-info "No implementation of protocol method"
                                    {:method method :type logical-type}))))))
            (alter-meta! v assoc :eve/logical-dispatch true)))))
    nil))

(defn- groups [specs]
  (loop [xs specs out []]
    (if (empty? xs) out
      (let [p (first xs) [methods more] (split-with seq? (rest xs))]
        (recur more (conj out [p methods]))))))

(defn- method-symbol [p method]
  (let [pvar (resolve p)
        n (:ns (meta pvar))]
    (symbol (str (ns-name n)) (name method))))

(defn- extension-forms [type specs]
  (for [[p methods] (groups specs)]
    (let [names (mapv #(method-symbol p (first %)) methods)
          protocol (symbol (str (ns-name (:ns (meta (resolve p))))) (name p))]
      `(register-extension! ~type '~protocol
         ~(into {} (map (fn [name method] [(list 'quote name) (cons 'fn (rest method))]) names methods))
         ~(into {} (map (fn [name] [(list 'quote name) (list 'var name)]) names))))))

(defn install!
  "Called by the bb producer after a type is emitted. Wrappers live on vars,
   so reload is idempotent and this module allocates no registry of its own."
  []
  (when-not (:eve/logical-dispatch (meta #'clojure.core/extend-type))
    (let [original @#'clojure.core/extend-type]
      (alter-var-root #'clojure.core/extend-type
        (fn [_] (fn [form env type & specs]
                  `(if (registered-key ~type)
                     (do ~@(extension-forms type specs) nil)
                     ~(apply original form env type specs)))))
      (alter-meta! #'clojure.core/extend-type assoc :eve/logical-dispatch true)))
  nil)
