(ns cohost-media.boot-proc
  "The process surface cohost-media.boot uses, over java.lang.ProcessBuilder — the subset of
   babashka.process the old babashka launcher called, with the same shapes: shell answers
   {:exit :out :err} (strings, when asked), process answers a Proc whose :proc is the Process,
   deref waits (with a timeout, babashka's way), alive? and destroy-tree by the name. JVM-seat
   only: the boot program runs on the JVM. As a .cljc the surface is the :clj arm, with one
   two-armed exception: proc?, so portable code (cohost-media.start's watches and kill steps)
   can ask whether a pool entry is a started process without naming the Proc class."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.io File]
                   [java.lang Process ProcessBuilder ProcessHandle]
                   [java.util.concurrent TimeUnit]
                   [clojure.lang IDeref IBlockingDeref])))

#?(:clj
   (do
   (deftype Proc [^Process proc]
     IDeref
     (deref [_] {:exit (.waitFor proc) :proc proc})
     IBlockingDeref
     (deref [_ timeout-ms timeout-val]
       (if (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)
         {:exit (.exitValue proc) :proc proc}
         timeout-val)))

   (defn- builder
     ^ProcessBuilder [cmd {:keys [dir extra-env]}]
     (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd))]
       (when dir (.directory pb (File. (str dir))))
       (when extra-env
         (doseq [[k v] extra-env] (.put (.environment pb) (str k) (str v))))
       pb))

   (defn- flatten-cmd
     "A command given as separate args and/or one vector of them, as one vector of strings (arguments
      are never split: an argument may itself contain spaces — a -e form, an EDN config)."
     [args]
     (vec (mapcat (fn [a] (if (sequential? a) a [a])) args)))

   (defn shell
     "Run a command to completion: {:exit :out :err} (out/err strings when :out/:err are :string).
      The opts map, when present, is the first argument (babashka.process's shape)."
     [& args]
     (let [opts (if (map? (first args)) (first args) {})
           cmd (flatten-cmd (if (map? (first args)) (rest args) args))
           pb (builder cmd opts)
           out-file (when (and (:out opts) (not= :string (:out opts))) (File. (str (:out opts))))
           err-file (when (and (:err opts) (not= :string (:err opts))) (File. (str (:err opts))))]
       (when out-file (.redirectOutput pb out-file))
       (when err-file (.redirectError pb err-file))
       (let [p (.start pb)
             out (when-not out-file (future (slurp (.getInputStream p))))
             err (when-not err-file (future (slurp (.getErrorStream p))))
             exit (.waitFor p)
             res {:exit exit :out (when out @out) :err (when err @err)}]
         (when (and (not (:continue opts)) (not (zero? exit)))
           (throw (ex-info (str "command failed: " (str/join " " cmd)) res)))
         res)))

   (defn process
     "Start a command: a Proc. :out/:err name files (or :string, drained and discarded), :dir and
      :extra-env as in shell."
     [opts & cmd]
     (let [pb (builder (flatten-cmd cmd) opts)]
       (if (and (:out opts) (not= :string (:out opts)))
         (.redirectOutput pb (File. (str (:out opts))))
         (.redirectOutput pb java.lang.ProcessBuilder$Redirect/DISCARD))
       (if (and (:err opts) (not= :string (:err opts)))
         (.redirectError pb (File. (str (:err opts))))
         (.redirectError pb java.lang.ProcessBuilder$Redirect/DISCARD))
       (->Proc (.start pb))))

   (defn alive? [^Proc pr] (.isAlive ^Process (.-proc pr)))

   (defn destroy-tree
     "Kill pr's process and its descendants."
     [^Proc pr]
     (let [^Process p (.-proc pr)]
       (try
         (let [it (.iterator (.descendants (.toHandle p)))]
           (while (.hasNext it)
             (.destroyForcibly ^ProcessHandle (.next it))))
         (catch Throwable _ nil))
       (.destroyForcibly p)
       nil))
     ))

(defn proc?
  "Is x a started process (a Proc)? Only the boot seat starts processes; on any other seat the
   answer is false, so a portable watch or kill step can hold pool entries of either kind."
  [x]
  #?(:clj (instance? Proc x)
     :cljs false))
