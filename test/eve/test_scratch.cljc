(ns eve.test-scratch
  "Official tests' scratch paths. One helper honors TMPDIR,
   java.io.tmpdir and os.tmpdir per host; unique eve-* trees are
   deleted at the end of a test."
  (:require [clojure.string :as str]
            [eve.process :as proc])
  #?(:cljr (:import [System Environment]
                    [System.IO Directory File Path])
     :lpy (:import os shutil tempfile)))

(defn- strip-sep
  "Drop trailing file separators, but never the root path."
  [s]
  (let [s (str s)]
    (loop [s s]
      (if (and (> (count s) 1)
               (or (str/ends-with? s "/") (str/ends-with? s "\\")))
        (recur (subs s 0 (dec (count s))))
        s))))

(defn- env-tmpdir
  "TMPDIR from the process environment, or nil."
  []
  (let [v #?(:cljs (when (exists? js/process)
                     (let [env (.-env js/process)]
                       (when env (or (.-TMPDIR env) (aget env "TMPDIR")))))
             :cljr (Environment/GetEnvironmentVariable "TMPDIR")
             :lpy (os/environ.get "TMPDIR")
             :clje (let [x (try (os/getenv "TMPDIR") (catch :default _ nil))]
                     (when (string? x) x))
             :default (System/getenv "TMPDIR"))]
    (when (and v (string? v) (pos? (count v))) v)))

(defn- host-tmpdir
  "The host's usual temp directory, or nil when the host has no API for one."
  []
  #?(:cljs (try (.-tmpdir (js/require "os")) (catch :default _ nil))
     :cljr (Path/GetTempPath)
     :lpy (tempfile/gettempdir)
     :clje nil
     :jank nil
     :default (System/getProperty "java.io.tmpdir")))

(defn tmpdir
  "The process temp directory, no trailing file separator. Prefers the
   host property the official gates set (java.io.tmpdir on the JVM,
   os.tmpdir on Node), then TMPDIR, then /tmp."
  []
  (strip-sep (or #?(:cljs (or (env-tmpdir) (host-tmpdir))
                    :cljr (or (env-tmpdir) (host-tmpdir))
                    :lpy (or (env-tmpdir) (host-tmpdir))
                    :clje (env-tmpdir)
                    :jank (env-tmpdir)
                    :default (or (host-tmpdir) (env-tmpdir)))
                 "/tmp")))

(defn- dir-exists?
  [p]
  (let [p (str p)]
    #?(:cljs (try (.existsSync (js/require "fs") p) (catch :default _ false))
       :cljr (Directory/Exists p)
       :lpy (os/path.isdir p)
       :clje (filelib/is_dir (erlang/binary_to_list p))
       :default (.isDirectory (java.io.File. p)))))

(defn- children-of
  [p]
  (let [p (str p)]
    #?(:cljs (try (vec (map #(str p "/" %) (js->clj (.readdirSync (js/require "fs") p))))
                  (catch :default _ []))
       :cljr (try (vec (concat (Directory/GetDirectories p) (Directory/GetFiles p)))
                  (catch :default _ []))
       :lpy (try (vec (map #(os/path.join p %) (os/listdir p)))
                 (catch :default _ []))
       :clje (let [r (file/list_dir (erlang/binary_to_list p))]
               (if (= :ok (erlang/element 1 r))
                 (mapv str (erlang/element 2 r))
                 []))
       :default (let [f (java.io.File. p)
                      xs (.listFiles f)]
                  (if xs (mapv #(.getPath ^java.io.File %) xs) [])))))

(defn- delete-leaf!
  [p]
  (let [p (str p)]
    #?(:cljs (let [fs (js/require "fs")]
               (try (.rmSync fs p #js {:recursive true :force true})
                    (catch :default _
                      (try (.unlinkSync fs p)
                           (catch :default _
                             (try (.rmdirSync fs p) (catch :default _ nil)))))))
       :cljr (cond (Directory/Exists p) (Directory/Delete p true)
                   (File/Exists p) (File/Delete p))
       :lpy (if (os/path.isdir p)
              (shutil/rmtree p)
              (when (os/path.exists p) (os/remove p)))
       :clje (file/del_dir_r (erlang/binary_to_list p))
       :default (let [f (java.io.File. p)]
                  (.delete f)))))

(defn delete-tree!
  "Best-effort recursive delete of a file or directory. Never deletes
   the process tmpdir, /dev/shm, or the filesystem root."
  [path]
  (when path
    (let [p (strip-sep path)
          guarded #{"/" "\\" "/dev/shm" (tmpdir)}]
      (when (and (pos? (count p)) (not (contains? guarded p)))
        (doseq [c (children-of p)]
          (delete-tree! c))
        (try (delete-leaf! p) (catch #?(:cljs :default :lpy python/Exception :default Throwable) _ nil))))))

(defn- safe-label
  [label]
  (let [s (str/replace (str label) #"[^A-Za-z0-9._-]" "-")
        s (if (str/starts-with? s "eve-") s (str "eve-" s))]
    s))

(defn- unique-name
  [label]
  (str (safe-label label) "-" (proc/now-ms) "-" (rand-int 1000000000)))

(def ^:private allocated (atom #{}))

(defn unique-dir
  "Create and return <tmpdir>/eve-<label>-<uniq> (no trailing slash)."
  [label]
  (let [d (str (tmpdir) "/" (unique-name label))]
    (proc/ensure-parent-dir! (str d "/.keep"))
    (swap! allocated conj d)
    d))

(defn unique-base
  "A domain base under a unique dir: <unique-dir>/<child>. child defaults to \"d\"."
  ([label] (unique-base label "d"))
  ([label child]
   (str (unique-dir label) "/" child)))

(defn unique-file
  "A file path <unique-dir>/<stem><ext> (the unique dir is created)."
  ([label] (unique-file label "t" ""))
  ([label ext] (unique-file label "t" ext))
  ([label stem ext]
   (str (unique-dir label) "/" stem ext)))

(defn fixed-file
  "A stable path <tmpdir>/<name> — the same every call in this process.
   For a file two modules must share (the class-5 mmap slab and its runner)."
  [name]
  (str (tmpdir) "/" name))

(defn window-dir
  "A unique directory under /dev/shm when that filesystem is present,
   otherwise unique-dir. For tests that place a domain in the window."
  [label]
  (if (dir-exists? "/dev/shm")
    (let [d (str "/dev/shm/" (unique-name label))]
      (proc/ensure-parent-dir! (str d "/.keep"))
      (swap! allocated conj d)
      d)
    (unique-dir label)))

(defn- infer-root
  "The unique eve-* directory (or eve-* file in tmpdir / /dev/shm) that owns path."
  [path]
  (let [p (strip-sep path)
        roots [(tmpdir) "/dev/shm"]]
    (some (fn [root]
            (let [prefix (str root "/")]
              (when (str/starts-with? p prefix)
                (let [top (first (str/split (subs p (count prefix)) #"[/\\\\]"))]
                  (when (and top (str/starts-with? top "eve-"))
                    (str root "/" top))))))
          roots)))

(defn delete-base!
  "Delete the unique eve-* tree that owns a unique-base, unique-file,
   unique-dir, window-dir, or fixed-file path. Best-effort."
  [path]
  (when path
    (let [p (strip-sep path)
          root (or (first (filter (fn [d] (or (= p d) (str/starts-with? p (str d "/"))))
                                  @allocated))
                   (infer-root p)
                   (when (and (or (str/starts-with? (let [n (last (str/split p #"[/\\\\]"))] n) "eve-")
                                  (str/starts-with? p (str (tmpdir) "/eve-")))
                              (not (dir-exists? p)))
                     p))]
      (when root
        (delete-tree! root)
        (swap! allocated disj root)))))

(defn with-base
  "Call (f base) with a unique domain base; delete the tree afterwards."
  [label f]
  (let [base (unique-base label)]
    (try (f base) (finally (delete-base! base)))))

(defn with-file
  "Call (f path) with a unique file path; delete the tree afterwards."
  [label ext f]
  (let [p (unique-file label ext)]
    (try (f p) (finally (delete-base! p)))))

(defn once-dir-fixture
  "A clojure.test :once fixture that deletes root after the namespace's tests."
  [root]
  (fn [f]
    (try (f) (finally (delete-tree! root)))))
