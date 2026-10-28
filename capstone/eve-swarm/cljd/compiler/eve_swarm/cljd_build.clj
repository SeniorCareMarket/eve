(ns eve-swarm.cljd-build
  "ClojureDart's build (cljd.build) with two readings eve's shared code needs of the compiler, a ClojureDart program
   being compiled ahead of time:

   - the JVM's type hints read as Dart's dynamic. eve's shared code hints for the JVM (^bytes ^long ^longs ^objects
     ^double ...: the JVM's primitive and array types, which eve.host names as functions — a cast — on every host);
     ClojureDart reads a hint as a Dart type and refuses one it cannot resolve, and on Dart those names are eve.host's
     functions. A hint that fails to resolve and names one of them is dropped (the value is typed dynamic, as an
     unhinted one); any other unresolved hint is still an error.
   - the core's completion. eve.host-completion (adapters/completion/dart) defines what cljd.core lacks; an unqualified
     symbol nothing else resolves is resolved there when that namespace defines it — where basilisp's and glojure's
     completions intern into their cores at run time."
  (:require [cljd.build]
            [cljd.compiler :as c]))

(alter-var-root #'c/resolve-symbol
                (fn [resolve-symbol]
                  (fn [sym env]
                    (or (resolve-symbol sym env)
                        (when (and (symbol? sym) (nil? (namespace sym))
                                   (contains? (get @c/nses 'eve.host-completion) sym))
                          (resolve-symbol (symbol "eve.host-completion" (name sym)) env))))))

(def jvm-hints
  '#{bytes shorts ints longs floats doubles objects booleans chars long double int short byte float char boolean})

(defn- jvm-hint? [sym] (and (symbol? sym) (contains? jvm-hints (symbol (name sym)))))

(alter-var-root #'c/resolve-type
                (fn [resolve-type]
                  (fn this
                    ([sym type-vars]
                     (or (this sym type-vars nil) (throw (ex-info (str "Can't resolve type: " sym) {:sym sym}))))
                    ([sym type-vars not-found]
                     (let [r (resolve-type sym type-vars ::none)]
                       (if (or (nil? r) (= ::none r))
                         (if (jvm-hint? sym) c/dc-dynamic not-found)
                         r))))))

(defn -main [& args] (apply cljd.build/-main args))
