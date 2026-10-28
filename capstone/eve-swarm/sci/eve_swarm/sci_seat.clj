(ns eve-swarm.sci-seat
  "The eve-swarm seat on SCI as a library on the JVM (org.babashka/sci; capstone/eve-swarm): eve's chat seat
   (eve.aegis.chat.seat) read from source and interpreted by SCI — not babashka's native image (bb), not nbb's Node.
   One SCI context loads eve's shared src/ and the babashka host's adapter roots (eve/hosts.edn :bb, reader features
   :bb then :clj, as babashka reads them) through its :load-fn; Java classes are SCI's to allow, all of them here (a
   JVM, not a closed image). The one thing babashka adds to SCI that eve's babashka adapters lean on is its class
   table — what `(IndexOutOfBoundsException. …)` in eve's codec resolves through — built below from the JDK's module
   image and Clojure's jar. Run from eve/: clojure -M:sci-seat -m eve-swarm.sci-seat
   (bb eve-swarm seat --host sci)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]))

(defn- roots
  "eve's src/ then the babashka host's adapter roots (hosts.edn :bb), as bb test:bb puts them on its classpath"
  []
  (cons "src" (:bb (edn/read-string (slurp "hosts.edn")))))

(defn- source-of
  "[file text] of namespace ns-sym in the first root that has it (.bb, .clj, then .cljc, as babashka looks), or nil"
  [rs ns-sym]
  (let [base (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/"))]
    (some (fn [r]
            (some (fn [ext]
                    (let [f (io/file r (str base ext))]
                      (when (.exists f) [(.getPath f) (slurp f)])))
                  [".bb" ".clj" ".cljc"]))
          rs)))

;; SCI resolves a class by name only when the context names it (:classes {full.Name Class}) — `:allow :all` opens
;; every object's methods, not the names (measured: (java.lang.IndexOutOfBoundsException. "x") "Unable to resolve
;; classname" under {:classes {:allow :all}} alone). babashka names a fixed set; here, every public class of the JDK
;; packages eve's babashka adapters and shared code use, and of clojure.lang — a JVM, so any of them loads.
(def ^:private JDK-PACKAGES
  {"java.base" ["java/lang" "java/lang/invoke" "java/lang/ref" "java/util" "java/util/concurrent"
                "java/util/concurrent/atomic" "java/util/concurrent/locks" "java/util/function" "java/util/regex"
                "java/io" "java/nio" "java/nio/channels" "java/nio/charset" "java/nio/file" "java/nio/file/attribute"
                "java/net" "java/math" "java/security" "java/security/spec" "java/time" "java/text" "javax/crypto"
                "javax/crypto/spec"]
   "java.net.http" ["java/net/http"]})

(defn- class-name
  "the binary class name of a .class path inside a package directory, or nil for an anonymous or synthetic class"
  [^String path]
  (when (and (str/ends-with? path ".class") (not (re-find #"\$\d" path)) (not (str/ends-with? path "module-info.class")))
    (-> path (subs 0 (- (count path) 6)) (str/replace "/" "."))))

(defn- public-classes
  "{full.Name Class} of the public classes named"
  [names]
  (into {}
        (keep (fn [n]
                (when-let [c (try (Class/forName n false (.getContextClassLoader (Thread/currentThread)))
                                  (catch Throwable _ nil))]
                  (when (java.lang.reflect.Modifier/isPublic (.getModifiers ^Class c))
                    [(symbol (str/replace n "$" "$")) c]))))
        names))

(defn- jdk-class-names []
  (let [fs (java.nio.file.FileSystems/getFileSystem (java.net.URI/create "jrt:/"))]
    (for [[module pkgs] JDK-PACKAGES
          pkg pkgs
          :let [dir (.getPath fs (str "/modules/" module "/" pkg) (make-array String 0))]
          :when (java.nio.file.Files/isDirectory dir (make-array java.nio.file.LinkOption 0))
          p (with-open [s (java.nio.file.Files/list dir)] (vec (iterator-seq (.iterator s))))
          :let [n (class-name (str pkg "/" (.getFileName ^java.nio.file.Path p)))]
          :when n]
      n)))

(defn- clojure-lang-class-names []
  (let [jar (-> clojure.lang.RT .getProtectionDomain .getCodeSource .getLocation .toURI java.io.File.)]
    (with-open [jf (java.util.jar.JarFile. jar)]
      (vec (keep (fn [^java.util.jar.JarEntry e]
                   (let [n (.getName e)]
                     (when (str/starts-with? n "clojure/lang/") (class-name n))))
                 (enumeration-seq (.entries jf)))))))

(defn- classes [] (assoc (public-classes (concat (jdk-class-names) (clojure-lang-class-names))) :allow :all))

(defn- java-lang-imports
  "{ShortName java.lang.ShortName} for java.lang's top-level classes, as Clojure (and babashka) resolve them unqualified"
  [cs]
  (into {} (keep (fn [k] (when (symbol? k)
                           (let [n (str k)]
                             (when (and (str/starts-with? n "java.lang.") (not (str/includes? (subs n 10) "."))
                                        (not (str/includes? n "$")))
                               [(symbol (subs n 10)) k]))))
                 (keys cs))))

(defn ctx
  "the SCI context the seat runs in"
  []
  (let [rs (roots)
        cs (classes)]
    (sci/init {:classes cs
               :imports (java-lang-imports cs)
               :features #{:bb :clj}
               :load-fn (fn [{:keys [namespace]}]
                          (when-let [[file text] (source-of rs namespace)]
                            {:file file :source text}))})))

(defn -main
  "the seat's loop on standard input and output (eve.aegis.chat.seat/-main), in an SCI context"
  [& _]
  (let [c (ctx)]
    (sci/binding [sci/out *out* sci/err *err* sci/in *in*]
      (sci/eval-string* c "(require 'eve.aegis.chat.seat) (eve.aegis.chat.seat/-main)"))
    (shutdown-agents)))
