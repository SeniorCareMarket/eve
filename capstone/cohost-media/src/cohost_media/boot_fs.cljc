(ns cohost-media.boot-fs
  "The filesystem surface cohost-media.boot uses, over java.nio — the subset of babashka.fs the old
   babashka launcher called, with the same names and shapes (paths are strings; file answers a
   java.io.File), so the boot program's body reads the way the launcher's did. JVM-seat only: the
   boot program runs on the JVM. As a .cljc the whole surface is the :clj arm: every seat's reader
   reads this namespace, and no other seat calls it — the boot program is the primary seat's."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.io File]
                   [java.nio.file Files Path Paths StandardCopyOption CopyOption]
                   [java.nio.file.attribute FileAttribute]
                   [java.util.stream Collectors])))

#?(:clj
   (do
   (defn- ->path ^Path [x] (if (instance? Path x) x (Paths/get (str x) (into-array String []))))

   (defn path
     "Join parts into a path string."
     [a & more]
     (str (reduce (fn [^Path p part] (.resolve p (str part))) (->path a) more)))

   (defn file ^File [x & more] (File. (apply path x more)))

   (defn exists? [x] (Files/exists (->path x) (into-array java.nio.file.LinkOption [])))

   (defn directory? [x] (Files/isDirectory (->path x) (into-array java.nio.file.LinkOption [])))

   (defn executable? [x] (Files/isExecutable (->path x)))

   (defn size [x] (Files/size (->path x)))

   (defn extension [x]
     (let [n (.getName (file x)) i (.lastIndexOf n ".")]
       (when (pos? i) (subs n (inc i)))))

   (defn parent [x] (some-> (->path x) .getParent str))

   (defn create-dirs [x] (Files/createDirectories (->path x) (into-array FileAttribute [])) (str x))

   (defn list-dir
     "The entries of dir, as path strings."
     [x]
     (with-open [s (Files/list (->path x))]
       (vec (.collect s (Collectors/toList)))))

   (defn copy [src dst & _opts]
     (Files/copy (->path src) (->path dst) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
     (str dst))

   (defn move [src dst & _opts]
     (Files/move (->path src) (->path dst) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))
     (str dst))

   (defn- walk-files
     "Every file under root (root itself when it is a file), as path strings."
     [root]
     (let [p (->path root)]
       (if (Files/isDirectory p (into-array java.nio.file.LinkOption []))
         (with-open [s (Files/walk p (into-array java.nio.file.FileVisitOption []))]
           (vec (map str (.collect s (Collectors/toList)))))
         [(str p)])))

   (defn copy-tree [src dst]
     (let [s (->path src) d (->path dst)]
       (doseq [f (walk-files src)]
         (let [rel (.relativize s (->path f)) target (.resolve d rel)]
           (if (Files/isDirectory (->path f) (into-array java.nio.file.LinkOption []))
             (Files/createDirectories target (into-array FileAttribute []))
             (do (Files/createDirectories (.getParent target) (into-array FileAttribute []))
                 (Files/copy (->path f) target (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))))
     (str dst))

   (defn delete-tree [x]
     (let [p (->path x)]
       (when (exists? x)
         (doseq [f (reverse (sort (walk-files x)))]
           (Files/deleteIfExists (->path f)))
         (Files/deleteIfExists p)))
     nil)

   (defn home [] (System/getProperty "user.home"))

   (defn temp-dir [] (System/getProperty "java.io.tmpdir"))

   (defn create-temp-dir
     "A fresh temp directory: {:prefix p} names it, or a bare prefix string."
     ([] (create-temp-dir {}))
     ([opts]
      (str (Files/createTempDirectory (str (or (:prefix opts) opts "cohost-media-")) (into-array FileAttribute [])))))

   (defn absolutize [x] (str (.toAbsolutePath (.normalize (->path x)))))

   (defn canonicalize [x]
     (let [p (->path x)]
       (str (if (exists? x) (.toRealPath p (into-array java.nio.file.LinkOption [])) (.toAbsolutePath (.normalize p))))))

   (defn last-modified-time [x] (Files/getLastModifiedTime (->path x) (into-array java.nio.file.LinkOption [])))

   (defn read-all-bytes [x] (Files/readAllBytes (->path x)))

   (defn write-bytes [x ^bytes bs]
     (Files/write (->path x) bs (into-array java.nio.file.OpenOption []))
     (str x))

   (defn glob
     "Files under root matching a pattern like \"**.{clj,cljs,cljc}\" (brace extensions) or \"*.txt\"."
     [root pattern]
     (let [exts (when-let [[_ inner] (re-find #"\.\{([^}]*)\}$" pattern)]
                  (set (str/split inner #",")))
           suffix (when-not exts (second (re-find #"(\.[A-Za-z0-9]+)$" pattern)))]
       (filter (fn [f]
                 (let [n (.getName (file f))]
                   (cond exts (contains? exts (extension f))
                         suffix (str/ends-with? n suffix)
                         :else true)))
               (walk-files root))))

   (defn which
     "name's path on PATH (or in extra dirs), or nil."
     ([name] (which name {}))
     ([name {:keys [extra-env]}]
      (let [path-env (or (get extra-env "PATH") (System/getenv "PATH"))
            dirs (str/split (or path-env "") (re-pattern (java.util.regex.Pattern/quote File/pathSeparator)))]
        (some (fn [d]
                (let [f (path d name)]
                  (when (and (exists? f) (executable? f)) f)))
              dirs))))
     ))
