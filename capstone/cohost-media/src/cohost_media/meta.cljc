(ns cohost-media.meta
  "The metadata facility (a JVM facility: Apache Tika's audio/video and image parsers,
   metadata-extractor through Tika's image module, ffprobe's stream listing). cohost-media.tasks'
   meta resolves this namespace lazily — only a member whose caps include :meta ever loads it.
   As a .cljc the facility is the :clj arm: Tika is a JVM library, and on a JS seat run! answers
   the same 'JVM facility' error cohost-media.tasks' own :cljs arm gives."
  (:refer-clojure :exclude [run!])
  (:require ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [clojure.string :as str]
   #?(:clj [clojure.java.io :as io])
            ; the alias is exercised on the other reader pass; the require loads on both 
   #_{:clj-kondo/ignore [:unused-namespace]}   [cohost-media.work :as work])
  #?(:clj (:import [org.apache.tika Tika]
                   [org.apache.tika.metadata Metadata]
                   [org.apache.tika.parser AutoDetectParser ParseContext]
                   [org.xml.sax.helpers DefaultHandler])))

#?(:clj
   (do
     (defn- sh
       "Run a command, answer {:exit :out (bytes) :err (text)}."
       [& args]
       (let [p (.start (doto (ProcessBuilder. ^java.util.List (mapv str args)) (.redirectErrorStream false)))
             out (future (with-open [in (.getInputStream p)] (.readAllBytes in)))
             err (future (slurp (.getErrorStream p)))]
         {:exit (.waitFor p) :out @out :err @err}))

     (defn- ffprobe-streams [path]
       (let [{:keys [out]} (sh "ffprobe" "-v" "error" "-show_entries"
                               "stream=index,codec_type,codec_name,width,height,sample_rate,channels:format=duration,format_name"
                               "-of" "compact=p=0" path)]
         (str/split-lines (String. ^bytes out "UTF-8"))))))

(defn run!
  "The meta job: parse the file with Tika, list its streams with ffprobe, record both."
  [{:keys [path]}]
  #?(:clj (let [md (Metadata.)
                mime (.detect (Tika.) (io/file path))]
            (with-open [in (io/input-stream path)]
              (.parse (AutoDetectParser.) in (DefaultHandler.) md (ParseContext.)))
            (work/record! path :meta
                          {:mime mime
                           :tika (into (sorted-map) (keep (fn [n] (when-not (str/starts-with? n "X-TIKA") [n (.get md n)]))
                                                          (.names md)))
                           :streams (ffprobe-streams path)
                           :size (.length (io/file path))}))
     :cljs (throw (ex-info "meta is a JVM facility (Apache Tika)" {}))))
