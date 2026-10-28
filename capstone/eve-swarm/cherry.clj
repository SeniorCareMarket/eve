;; The cherry seat's build (capstone/eve-swarm/README.md): cherry (npm cherry-cljs) compiles eve.aegis.chat.seat and
;; the namespaces it requires on hosts.edn :cherry's roots, file by file, to ES modules in target/eve-swarm/cherry/out,
;; and seat.mjs calls the seat's -main. cherry compiles only the files it is given, so the closure is walked here: a
;; namespace's .cljs on any root before its .cljc, a macro namespace's .clj or .cljc, read with the :cljs feature
;; (cherry's). Run from eve/: bb eve-swarm:cherry
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[edamame.core :as e])

(def CHERRY "cherry-cljs@0.6.38")
(def eve-root (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".."))))
(def out-dir (str (fs/path eve-root "target" "eve-swarm" "cherry")))
(def roots (mapv (fn [r] (str (fs/path eve-root r)))
                 (cons "src" (:cherry (edn/read-string (slurp (str (fs/path eve-root "hosts.edn"))))))))

(defn- file-of [ns exts]
  (let [base (str/replace (munge (str ns)) "." "/")]
    (first (for [ext exts r roots :let [f (fs/path r (str base ext))] :when (fs/exists? f)] (str f)))))

(defn- deps [f]
  (let [form (first (e/parse-string-all (slurp f) {:all true :read-cond :allow :features #{:cljs} :auto-resolve name
                                                   :readers (fn [_] identity)}))
        libs (fn [k] (for [c (filter seq? (drop 2 form)) :when (k (first c)) x (rest c)
                           :let [l (cond (symbol? x) x (and (vector? x) (symbol? (first x))) (first x))] :when l]
                       l))]
    [(libs #{:require :use}) (libs #{:require-macros :use-macros})]))

(defn closure
  "the runtime files of ns and everything it requires, transitively (a macro namespace's own requires load in
   cherry's macro evaluator, not here)"
  [ns]
  (loop [todo [[ns false]] seen #{} files []]
    (if-let [[n mac?] (first todo)]
      (if-let [f (when-not (seen [n mac?])
                   (or (file-of n (if mac? [".clj" ".cljc"] [".cljs" ".cljc"]))
                       ;; cherry's own (cljs.core, clojure.string, ...) unless a root completes it (clojure.edn)
                       (when-not (re-find #"^(cljs|clojure|goog)\." (str n))
                         (throw (ex-info (str "eve-swarm cherry: no file for " n) {:ns n})))))]
        (let [[req mac] (deps f)]
          (recur (concat (rest todo) (map (fn [r] [r mac?]) req) (map (fn [m] [m true]) mac))
                 (conj seen [n mac?]) (if mac? files (conj files f))))
        (recur (rest todo) seen files))
      files)))

(fs/create-dirs out-dir)
(when-not (fs/exists? (fs/path out-dir "node_modules" "cherry-cljs"))
  (spit (str (fs/path out-dir "package.json")) "{\"name\": \"eve-swarm-cherry\", \"private\": true}\n")
  (p/shell {:dir out-dir} "npm" "install" "--no-audit" "--no-fund" CHERRY))
;; cherry's compiler completion (adapters/completion/cherry/compiler/patches.edn)
(doseq [{:keys [file find replace]} (edn/read-string (slurp (str (fs/path eve-root "adapters" "completion" "cherry" "compiler"
                                                                          "patches.edn"))))
        :let [f (str (fs/path out-dir "node_modules" "cherry-cljs" file)) s (slurp f)]
        :when (not (str/includes? s replace))]
  (let [n (count (re-seq (re-pattern (java.util.regex.Pattern/quote find)) s))]
    (when-not (= 1 n)
      (binding [*out* *err*] (println "eve-swarm cherry: a patch's text occurs" n "times in" f "(not" CHERRY "?)"))
      (System/exit 1))
    (spit f (str/replace s find replace))))
(spit (str (fs/path out-dir "cherry.edn")) (pr-str {:paths roots :output-dir "out"}))
(let [files (closure 'eve.aegis.chat.seat)
      r (apply p/shell {:dir out-dir :continue true :out :string :err :string}
               (str (fs/path out-dir "node_modules" ".bin" "cherry")) "compile" files)
      log (str (:out r) (:err r))
      failed (re-seq #"Error while compiling (\S+)" log)]
  (spit (str (fs/path out-dir "compile.log")) log)
  (when (or (seq failed) (not (zero? (:exit r))))
    (binding [*out* *err*]
      (println "eve-swarm cherry: compile failed —" (str/join " " (map second failed)) "(" (str (fs/path out-dir "compile.log")) ")"))
    (System/exit 1))
  ;; ClojureScript's Node adapters call js/require, which its :node target (CommonJS) has and an ES module has not;
  ;; imported first, so it runs before the seat's modules
  (spit (str (fs/path out-dir "node.mjs"))
        (str "// the CommonJS require ClojureScript's Node adapters call (eve/capstone/eve-swarm/cherry.clj)\n"
             "import { createRequire } from 'node:module';\n"
             "globalThis.require = createRequire(import.meta.url);\n"))
  (spit (str (fs/path out-dir "seat.mjs"))
        (str "// eve.aegis.chat.seat's -main, compiled by cherry (eve/capstone/eve-swarm/cherry.clj)\n"
             "import './node.mjs';\n"
             "import { _main } from './out/eve/aegis/chat/seat.mjs';\n"
             "_main(...process.argv.slice(2));\n"))
  (println "eve-swarm cherry:" (count files) "namespaces →" (str (fs/path out-dir "seat.mjs"))))
