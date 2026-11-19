;; CLJS parity, full-cljc stage: the converted cohost-media files as .cljc.
;; Checks, for each file named on the command line:
;;
;;   clj-reads    every form reads with the JVM reader at :clj features
;;   cljs-reads   every form reads with the cljs reader at :cljs features
;;   ns-shape     the :clj-resolved and :cljs-resolved ns forms require
;;                exactly the libraries listed in `expected` below
;;
;;   dev/fullcljc/run.sh
(require '[clojure.java.io :as io]
         '[cljs.vendor.clojure.tools.reader :as cljs-reader])

(defn read-all [read-one file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (loop [acc []]
      (let [f (read-one r)]
        (if (= ::eof f) acc (recur (conj acc f)))))))

(defn read-all-clj [file]
  (read-all (fn [r] (read {:eof ::eof :read-cond :allow :features #{:clj}} r)) file))

(defn read-all-cljs [file]
  ;; the vendored reader's defaults carry no js tag reader (the
  ;; compiler's env registers one); for a read-validity check a
  ;; stand-in is the honest binding (stage 2's app check, same shape)
  (binding [cljs-reader/*data-readers*
            (assoc cljs-reader/default-data-readers
                   'js (fn [v] (list 'js-literal v)))]
    (let [opts {:read-cond :allow :features #{:cljs} :eof ::eof}]
      (read-all (fn [r] (cljs-reader/read opts r)) file))))

(defn required-libs [ns-form]
  ;; the lib specs of every :require clause in a resolved ns form
  (into #{}
        (comp (filter seq?)
              (filter #(= :require (first %)))
              (mapcat rest)
              (map (fn [spec] (if (sequential? spec) (first spec) spec))))
        ns-form))

;; per-file expectations: the :cljs-resolved and :clj-resolved require
;; sets of the ns form, keyed by the file's base name before its first dot.
(def expected
  {"boot"       {:clj  '#{cohost.hosts.auto cohost-media.start}
                 :cljs '#{cohost.hosts.auto cohost-media.start}}
   "start"      {:clj  '#{cohost.pool cohost.thread cohost.cljs-pool cohost-media.boot-fs cohost-media.boot-proc
                          clojure.data.json clojure.edn clojure.string
                          cohost-media.core cohost-media.domain cohost-media.gateway
                          cohost-media.member cohost-media.supervise}
                 :cljs '#{cohost.pool cohost.thread cohost-media.boot-fs cohost-media.boot-proc
                          clojure.data.json clojure.edn clojure.string
                          cohost-media.core cohost-media.domain cohost-media.gateway
                          cohost-media.member cohost-media.supervise}}
   "boot_fs"    {:clj  '#{clojure.string}
                 :cljs '#{clojure.string}}
   "boot_proc"  {:clj  '#{clojure.string}
                 :cljs '#{clojure.string}}
   "gateway"    {:clj  '#{clojure.string cohost.pool cohost-media.domain cohost-media.serve}
                 :cljs '#{clojure.string cohost.pool cohost-media.domain cohost-media.serve}}
   "meta"       {:clj  '#{clojure.string clojure.java.io cohost-media.work}
                 :cljs '#{clojure.string cohost-media.work}}
   "member"     {:clj  '#{clojure.edn clojure.string cohost.pool
                          cohost-media.core cohost-media.coordinator cohost-media.domain
                          cohost-media.media cohost-media.state cohost-media.tasks}
                 :cljs '#{clojure.edn clojure.string cohost.pool
                          cohost-media.core cohost-media.coordinator cohost-media.domain
                          cohost-media.media cohost-media.state cohost-media.tasks}}
   ;; the spec-API rewrite (2026-11-17): every file that required eve.*
   ;; now requires only clojure/cohost/app namespaces; the sets below
   ;; are the enforcement
   "coordinator" {:clj  '#{cohost.pool cohost-media.core cohost-media.domain}
                 :cljs '#{cohost.pool cohost-media.core cohost-media.domain}}
   "domain"     {:clj  '#{cohost.pool cohost-media.core}
                 :cljs '#{cohost.pool cohost-media.core}}
   "tasks"      {:clj  '#{clojure.string cohost.pool cohost.region
                          cohost-media.av cohost-media.core cohost-media.media
                          cohost-media.state cohost-media.work
                          cohost-media.phash cohost-media.r128}
                 :cljs '#{clojure.string cohost.pool
                          cohost-media.av cohost-media.core cohost-media.media
                          cohost-media.state cohost-media.work}}
   "work"       {:clj  '#{cohost.pool cohost-media.domain cohost-media.phash cohost-media.state}
                 :cljs '#{cohost.pool cohost-media.domain cohost-media.phash cohost-media.state}}
   "serve"      {:clj  '#{clojure.string cohost.net cohost.pool}
                 :cljs '#{clojure.string cohost.net cohost.pool}}
   "core"       {:clj  '#{clojure.string}
                 :cljs '#{clojure.string}}
   "page"       {:clj  '#{}
                 :cljs '#{clojure.string cohost.pool cohost.thread-host
                          cohost-media.core cohost-media.tasks reagami.core}}
   "media_impl" {:clj  '#{cohost.pool cohost-media.core cohost-media.state cohost-media.work}
                 :cljs '#{cohost.pool cohost-media.core cohost-media.state cohost-media.work}}
   "av_jolt"    {:clj  '#{clojure.string jolt.ffi cohost-media.core cohost-media.work}
                 :cljs '#{clojure.string jolt.ffi cohost-media.core cohost-media.work}}})

(def results (atom []))
(defn check [label pass detail]
  (swap! results conj pass)
  (println (if pass "PASS" "FAIL") label (when-not pass (str " -- " detail))))

(doseq [file *command-line-args*]
  (let [base (last (clojure.string/split file #"/"))
        kind (first (clojure.string/split base #"\."))
        clj-forms (try (read-all-clj file) (catch Throwable t (str "THREW: " (ex-message t))))
        cljs-forms (try (read-all-cljs file) (catch Throwable t (str "THREW: " (ex-message t))))]
    (check (str base " clj-reads") (vector? clj-forms) (str clj-forms))
    (check (str base " cljs-reads") (vector? cljs-forms) (str cljs-forms))
    (when (vector? clj-forms)
      (check (str base " clj-side requires as expected")
             (= (get-in expected [kind :clj]) (required-libs (first clj-forms)))
             (str "got " (required-libs (first clj-forms)))))
    (when (vector? cljs-forms)
      (check (str base " cljs-side requires as expected")
             (= (get-in expected [kind :cljs]) (required-libs (first cljs-forms)))
             (str "got " (required-libs (first cljs-forms)))))))

(let [rs @results]
  (println "FULLCLJC-READER-CHECK:" (count (filter identity rs)) "/" (count rs) "passed")
  (System/exit (if (every? identity rs) 0 1)))
