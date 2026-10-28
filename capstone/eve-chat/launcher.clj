;; eve-chat, the aegis chat from a Clojure REPL (capstone/eve-chat/README.md): a REPL of any installed dialect, on eve's
;; classpath for that dialect (hosts.edn), with eve.aegis.chat.repl loaded as `chat` and, given the web page's invite
;; link, already joined. Run from eve/:
;;
;;   bb eve-chat repl [--host bb|jvm|nbb|cljr|lpy|glj|clje] [link]   a REPL in the room (default host: bb)
;;   bb eve-chat hosts                                               the dialects, and which this machine runs
;;   bb eve-chat local                                               a local broker and page (atom.broker's tooling),
;;                                                                   seeded, each member's invite link printed
;;
;; A dialect whose REPL takes an init file (babashka, the JVM, ClojureCLR, clojerl) or an init expression (nbb) runs its
;; own REPL, history and all. basilisp's and glojure's take none, so the launcher relays the terminal's lines to them,
;; after typing the init forms itself. basilisp's REPL is the plain loop in repl_loop.lpy (its header says why).
;; glojure prints no prompt off a terminal, so a sentinel keyword typed after each line marks the end of an answer,
;; shown as `user=> `. It also stops at its first uncaught exception there, so each line goes to it under eve's portable
;; catch-all, eve.thread-host/attempt: an error is printed and the session kept.
(ns eve-chat.launcher
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def eve-root (str (fs/canonicalize (fs/path (fs/parent *file*) ".." ".."))))
(def repo-root "the repository eve sits in (atom.broker: the broker, the page, the seed script)" (str (fs/parent eve-root)))

(defn roots "eve's src and the host's adapter roots (hosts.edn), absolute" [host]
  (mapv (fn [r] (str (fs/path eve-root r))) (cons "src" (get (edn/read-string (slurp (str (fs/path eve-root "hosts.edn")))) host))))

(defn- cp [host] (str/join ":" (roots host)))

(def ^:private SENTINEL ":eve-repl/ready")

(def ^:private LOCALE
  "a UTF-8 locale where the terminal names none (the JVM then prints ✓ and ü as ?); a locale the user set is kept"
  (if (or (System/getenv "LANG") (System/getenv "LC_ALL")) {} {"LANG" "C.UTF-8"}))

(defn- glj-wrap [line]
  (str "(let [r (eve.thread-host/attempt (fn [] " line "))] (if (first r) (second r) "
       "(println (str \"error: \" (first (clojure.string/split-lines (str (ex-message (second r)))))))))"))

(defn- init-forms [link]
  (cond-> ["(require '[eve.aegis.chat.repl :as chat])" "(chat/help)"]
    link (conj (str "(chat/join! " (pr-str link) ")"))))

(defn- init-file [link ext]
  (let [f (fs/create-temp-file {:prefix "eve-chat-init-" :suffix ext})]
    (fs/delete-on-exit f)
    (spit (str f) (str/join "\n" (init-forms link)))
    (str f)))

(def hosts
  "each dialect: its binary (to check), and how its REPL starts — {:cmd :env :dir} natively with the init file, or
   {:relay true} for a REPL the launcher types into"
  {:bb {:what "babashka" :bin "bb"
        :run (fn [link] {:cmd ["bb" "-cp" (cp :bb) "--init" (init-file link ".clj") "repl"]})}
   :jvm {:what "Clojure on the JVM" :bin "clojure"
         :run (fn [link] {:cmd ["clojure" "-M" "-i" (init-file link ".clj") "-r"] :dir eve-root})}
   :nbb {:what "nbb (ClojureScript on Node)" :bin "nbb"
         :run (fn [link] {:cmd ["nbb" "--classpath" (cp :nbb) "-e"
                                (str (str/join " " (init-forms link)) " (require '[nbb.repl]) (nbb.repl/repl)")]})}
   :cljr {:what "ClojureCLR (.NET)" :bin "cljr"
          :run (fn [link] {:cmd ["cljr" "-i" (init-file link ".clj") "-r"]
                           :env {"CLOJURE_LOAD_PATH" (cp :cljr) "DOTNET_ROOT" (or (System/getenv "DOTNET_ROOT") "/opt/dotnet")}})}
   :clje {:what "clojerl (the BEAM)" :bin "clojerl"
          :run (fn [link] {:cmd (concat ["clojerl"] (mapcat (fn [r] ["-pa" r]) (roots :clje)) ["-i" (init-file link ".clje") "-r"])})}
   :lpy {:what "basilisp (CPython)" :bin "basilisp"
         ;; repl_loop.lpy, not `basilisp repl` (its header says why); unbuffered, or prompts wait in Python's pipe buffer
         :run (fn [link] {:relay true :forms (init-forms link)
                          :cmd (concat ["basilisp" "run"] (mapcat (fn [r] ["-p" r]) (reverse (roots :lpy)))
                                       [(str (fs/path eve-root "capstone" "eve-chat" "repl_loop.lpy"))])
                          :env {"PYTHONPYCACHEPREFIX" (str (fs/path eve-root "target" "lpycache-repl")) "PYTHONUNBUFFERED" "1"}})}
   :glj {:what "glojure (Go; glj-eve, built by bb glj-eve)" :bin (str (fs/path eve-root "target" "glj-eve" "glj-eve"))
         :run (fn [link] {:relay true :sentinel SENTINEL :wrap glj-wrap :preamble ["(require 'eve.thread-host 'clojure.string)"]
                          :forms (init-forms link)
                          :cmd [(str (fs/path eve-root "target" "glj-eve" "glj-eve"))]
                          :env {"GLJ_CLASSPATH" (cp :glj)}})}})

(defn- installed? [{:keys [bin]}] (boolean (or (fs/which bin) (and (fs/absolute? bin) (fs/executable? bin)))))

(defn- relay!
  "start the REPL with its input piped: type the init forms, then each line read from this terminal; its output shown as
   it comes (with a sentinel: line by line, the sentinel's echo shown as the prompt)"
  [{:keys [cmd env preamble forms sentinel wrap] :or {wrap identity}}]
  (let [proc (p/process {:cmd (vec cmd) :extra-env (merge LOCALE env) :dir eve-root :in :pipe :out :pipe :err :inherit})
        w (java.io.OutputStreamWriter. ^java.io.OutputStream (:in proc) "UTF-8")
        send! (fn [line] (.write w (str (if (str/blank? line) line (wrap line)) "\n")) (when sentinel (.write w (str sentinel "\n"))) (.flush w))
        out (future
              (let [in ^java.io.InputStream (:out proc)]
                (if sentinel
                  (with-open [r (java.io.BufferedReader. (java.io.InputStreamReader. in "UTF-8"))]
                    (loop []
                      (when-let [l (.readLine r)]
                        (if (= l SENTINEL) (do (print "user=> ") (flush)) (println l))
                        (recur))))
                  (let [buf (byte-array 4096)]
                    (loop []
                      (let [n (.read in buf)]
                        (when (pos? n) (.write System/out buf 0 n) (.flush System/out) (recur))))))))]
    (doseq [f preamble] (.write w (str f "\n")))
    (doseq [f forms] (send! f))
    (loop [] (when-let [l (read-line)] (send! l) (recur)))
    (.close w)
    @out
    (:exit @proc)))

(defn repl! [args]
  (let [[opts link] (loop [as args opts {:host :bb}]
                      (cond (empty? as) [opts nil]
                            (= "--host" (first as)) (recur (drop 2 as) (assoc opts :host (keyword (second as))))
                            :else [opts (first as)]))
        h (get hosts (:host opts))]
    (when-not h (binding [*out* *err*] (println "eve-chat: no dialect" (:host opts) "— one of" (str/join " " (map name (keys hosts))))) (System/exit 2))
    (when-not (installed? h) (binding [*out* *err*] (println "eve-chat:" (:what h) "is not installed here (" (:bin h) "; doc/hosts/)")) (System/exit 2))
    (let [r ((:run h) link)]
      (if (:relay r)
        (System/exit (relay! r))
        (System/exit (:exit @(p/process {:cmd (vec (:cmd r)) :extra-env (merge LOCALE (:env r)) :dir (or (:dir r) eve-root) :inherit true})))))))

(defn hosts! []
  (doseq [[k h] (sort-by key hosts)]
    (println (format "%-5s %-46s %s" (name k) (:what h) (if (installed? h) "installed" "not installed"))))
  (println (format "%-5s %-46s %s" "jolt" "jolt (Chez Scheme)" "no REPL client: no http adapter (README.md)"))
  (println (format "%-5s %-46s %s" "vs" "ValueScript" "the web page's own client (client.wasm)")))

(defn- json-read [f] ((requiring-resolve 'cheshire.core/parse-string) (slurp f)))

(defn- broker-wasm
  "broker.wasm, built (or the cached build of the current sources) by atom.broker's e2e helper"
  []
  (-> (p/shell {:dir repo-root :out :string} "node" "--input-type=module" "-e"
               "import('./test/aegis/helpers/aegis_e2e.mjs').then((h) => console.log(h.buildBroker().wasm))")
      :out str/trim str/split-lines last))

(defn local!
  "atom.broker's local stack: the seed (atom-broker/scripts/populate-chat-local.mjs, once), relay.py serve (broker.wasm
   on 8787), chat_page.py (the page on 8790); each seeded member's invite link printed; Enter stops both"
  []
  (let [dir (str (fs/path repo-root "build" "chat-local"))
        ids (str (fs/path dir "identities.json"))]
    (when-not (fs/exists? (fs/path repo-root "src" "aegis" "hosts" "relay.py"))
      (binding [*out* *err*] (println "eve-chat: local needs atom.broker's tree around eve (src/aegis/hosts/relay.py)")) (System/exit 2))
    (when-not (fs/exists? ids)
      (println "eve-chat: seeding" dir "(the first run builds broker.wasm and client.wasm: minutes)")
      (p/shell {:dir repo-root} "node" "atom-broker/scripts/populate-chat-local.mjs" "--dir" dir))
    (let [relay (p/process {:dir repo-root :inherit true}
                           "python3" "src/aegis/hosts/relay.py" "serve" "--state" (str (fs/path dir "state"))
                           "--broker" (broker-wasm) "--port" "8787")
          page (p/process {:dir repo-root :inherit true}
                          "python3" "src/aegis/hosts/chat_page.py" "--page" (str (fs/path dir "page")) "--port" "8790")
          {:strs [fragments]} (json-read ids)]
      (Thread/sleep 1500)
      (println "\nthe room's invite links (a browser, or: bb eve-chat repl --host <dialect> '<link>'):")
      (doseq [[who frag] (sort fragments)] (println (str "  " who "  http://127.0.0.1:8790/#" frag)))
      (println "\nEnter stops the broker and the page.")
      (read-line)
      (p/destroy-tree relay) (p/destroy-tree page))))

(let [[cmd & args] *command-line-args*]
  (case cmd
    "repl" (repl! args)
    "hosts" (hosts!)
    "local" (local!)
    (do (println "bb eve-chat repl [--host bb|jvm|nbb|cljr|lpy|glj|clje] [invite link]  |  bb eve-chat hosts  |  bb eve-chat local")
        (System/exit (if cmd 2 0)))))
