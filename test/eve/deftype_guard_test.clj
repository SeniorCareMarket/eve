(ns eve.deftype-guard-test
  "THE RESURRECTION GUARD — John's ruling, Nov 14 2026: \"After you slay
   this phantom deftype zombie another agent will try to resurrect it.\"

   This test is the permanent structural tripwire. It fails if:

   1. the fake deftype's funnel (eve.deftype-proto.host / proto-deftype)
      reappears anywhere in src/ or test/, in any form;
   2. any namespace under src/ defines a NEW type-defining macro (a
      defmacro named *deftype*) outside the sanctioned defining files;
   3. any namespace under src/ defines an Eve type through a head that
      does not resolve to one of the three sanctioned macros
      (eve.deftype/deftype, eve.deftype-proto.macros/deftype,
      eve.deftype-proto.macros/host-deftype — the eve.alpha forward
      included), or refers `deftype` from an unsanctioned home;
   4. the one-tree rule disappears from eve.deftype's ns docstring;
   5. any namespace under src/ that defines a type through a
      registration-emitting macro (eve.deftype/deftype or
      eve.deftype-proto.macros/deftype — both expansions end in a
      top-level eve.flat.sister/register-sister! call) does not itself
      require eve.flat.sister. A macro namespace's require cannot
      order a ClojureScript bundle (it is not a runtime graph node);
      without the defining namespace's own require, bundle order is
      luck, and the bundle dies at load when the registration runs
      before the registry's namespace object exists (the cohost-media
      page bundle, Nov 16 2026: list/map/set/vec registered early);
   6. any namespace under src/ that defines a type through a
      reader-registering macro (eve.deftype/deftype or
      eve.deftype-proto.macros/host-deftype — both expansions end in
      a top-level eve.type-readers/register! call, type-io's
      reader-form) does not itself require eve.type-readers. The
      same load-order law as rule 5, for the second registry the
      sanctioned macros emit into (measured the same day in the same
      bundle shape: rseq and the vec/map/set transients registered
      their readers early).

   Host types are NOT Eve types: a bare clojure.core/deftype or
   defrecord in a file that refers no Eve deftype macro is host
   machinery (SIO wrappers, thread records) and is none of this
   guard's business. What is forbidden is a fourth EVE type macro.

   The scan is structural: every src file is read as forms (reader
   conditionals resolved for :clj); a file that cannot be form-read
   falls back to a token scan for the forbidden tokens, and the test
   reports how many files took each path — a file is never skipped
   silently."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The sanctioned world, censused at the consolidation (Nov 14 2026)
;; ---------------------------------------------------------------------------

(def sanctioned-homes
  "Namespaces that home a sanctioned type macro (eve.alpha only forwards
   eve.deftype's)."
  '#{eve.deftype eve.deftype-proto.macros eve.alpha})

(def sanctioned-macro-definitions
  "path (relative to src/) -> the deftype-named defmacros that file may
   define. Any other (defmacro <*deftype*>) anywhere under src/ fails."
  {"eve/alpha.cljc"           '#{deftype}
   "eve/deftype.cljc"         '#{deftype}
   "eve/deftype_proto/macros.cljc" '#{deftype host-deftype}})

(def forbidden-tokens
  "Substrings that may not appear in any src/ or test/ file at all —
   the funnel's namespace and the fake macro as a form/engine. (The
   data keyword :proto-deftype in sister registrations' :generated-by
   is provenance, not the macro, and does not match these tokens.)"
  ["deftype-proto.host" "(proto-deftype" "proto-deftype*"])

;; ---------------------------------------------------------------------------
;; Scanning
;; ---------------------------------------------------------------------------

(defn- guard-root []
  (io/file (System/getProperty "eve.guard.root" ".")))

(defn- source-files [root & subdirs]
  (let [exts #{"cljc" "cljs" "clj"}]
    (->> subdirs
         (map #(io/file root %))
         (filter #(.exists %))
         (mapcat file-seq)
         (filter (fn [f] (and (.isFile f)
                              (exts (last (str/split (.getName f) #"\."))))))
         (remove (fn [f] (str/includes? (.getPath f) "__pycache__")))
         vec)))

(defn- read-forms
  "All top-level forms of f, reader conditionals resolved for :clj.
   Returns {:forms [...]} or {:error msg}."
  [f]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader f))]
      (loop [forms []]
        (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} r)]
          (if (= ::eof form)
            {:forms forms}
            (recur (conj forms form))))))
    #_{:splint/disable [lint/catch-throwable]}   (catch Throwable e {:error (ex-message e)})))

(defn- ns-form [forms]
  (first (filter (fn [f] (and (seq? f) (= 'ns (first f)))) forms)))

(defn- libspecs
  "The [ns-sym & opts] vectors of an ns form's :require clauses."
  [form]
  (letfn [(walk [x]
            (cond
              (vector? x) (when (symbol? (first x)) [x])
              (sequential? x) (mapcat walk x)
              :else nil))]
    (->> (rest form)
         (filter (fn [x] (and (sequential? x) (= :require (first x)))))
         (mapcat (fn [clause] (mapcat walk (rest clause)))))))

(defn- requires-of
  "forms -> {:nses #{required ns symbols}
             :refers {ns-sym #{referred symbols}}}"
  [forms]
  (let [specs (libspecs (ns-form forms))]
    {:nses (set (map first specs))
     :refers (into {}
                   (keep (fn [[ns-sym & opts]]
                           (let [m (apply hash-map opts)
                                 ref (or (:refer m) (:refer-macros m))]
                             (when (coll? ref)
                               [ns-sym (set ref)]))))
                   specs)}))

(defn- top-heads [forms]
  (keep (fn [f] (when (seq? f)
                  (let [h (first f)] (when (symbol? h) h))))
        forms))

(defn- rel-path [root f]
  (let [rp (.getPath (io/file root "src"))]
    (str/replace (.getPath f) (str rp java.io.File/separator) "")))

;; ---------------------------------------------------------------------------
;; The guard
;; ---------------------------------------------------------------------------

(deftest funnel-stays-dead
  (testing "eve.deftype-proto.host does not exist and is named nowhere"
    (let [root (guard-root)]
      (is (not (.exists (io/file root "src" "eve" "deftype_proto" "host.cljc")))
          "src/eve/deftype_proto/host.cljc must not exist — the funnel stays deleted")
      (doseq [f (source-files root "src" "test" "apps")
              ;; this guard file itself names the tokens it forbids
              :when (not (str/ends-with? (.getName f) "deftype_guard_test.clj"))
              token forbidden-tokens]
        (is (not (str/includes? (slurp f) token))
            (str (.getPath f) " mentions " token " — the fake deftype stays dead"))))))

(deftest no-new-deftype-macros
  (testing "no defmacro named *deftype* outside the sanctioned defining files"
    (let [root (guard-root)
          files (source-files root "src")
          stats (atom {:read 0 :scanned 0})]
      (doseq [f files]
        (let [{:keys [forms error]} (read-forms f)
              path (rel-path root f)]
          (if error
            (do (swap! stats update :scanned inc)
                ;; token fallback: a deftype defmacro in an unreadable
                ;; file is still caught by its spelling
                (is (not (re-find #"\(defmacro\s+\S*deftype" (slurp f)))
                    (str path " (unreadable, token-scanned) defines a deftype macro")))
            (do (swap! stats update :read inc)
                (doseq [form forms
                        :when (and (seq? form) (= 'defmacro (first form))
                                   (symbol? (second form))
                                   (str/includes? (name (second form)) "deftype"))]
                  (is (contains? (get sanctioned-macro-definitions path #{})
                                 (second form))
                      (str path " defines (defmacro " (second form)
                           " ...) — a new Eve type macro. The one-tree rule "
                           "allows only eve.deftype/deftype, "
                           "eve.deftype-proto.macros/deftype and "
                           "eve.deftype-proto.macros/host-deftype.")))))))
      (println "deftype-guard: form-read" (:read @stats)
               "files," (:scanned @stats) "token-scanned (unreadable)"))))

(deftest eve-types-only-via-sanctioned-macros
  (testing "Eve-type heads resolve to a sanctioned home; deftype is never
            referred from an unsanctioned home"
    (let [root (guard-root)]
      (doseq [f (source-files root "src")
              :let [{:keys [forms]} (read-forms f)]
              :when forms]
        (let [path (rel-path root f)
              {:keys [nses refers]} (requires-of forms)
              referred-deftype-homes (into #{}
                                           (keep (fn [[home syms]]
                                                   (when (contains? syms 'deftype)
                                                     home)))
                                           refers)]
          ;; referring deftype at all: only from a sanctioned home
          (doseq [home referred-deftype-homes]
            (is (contains? sanctioned-homes home)
                (str path " refers deftype from " home
                     " — not a sanctioned home " sanctioned-homes)))
          (doseq [h (top-heads forms)
                  :when (= "proto-deftype" (name h))]
            (is false
                (str path " calls (" h " ...) — the fake deftype stays dead")))
          (doseq [h (top-heads forms)
                  :let [hname (name h) hns (namespace h)]
                  :when (contains? #{"deftype" "host-deftype" "eve-deftype"} hname)]
            (cond
              ;; a qualified Eve-type head: its file must require a
              ;; sanctioned home (the alias itself is arbitrary)
              hns
              (is (boolean (some sanctioned-homes nses))
                  (str path " calls (" h " ...) but requires no sanctioned "
                       "type-macro home " sanctioned-homes
                       " — an Eve type defined outside the sanctioned macros"))
              ;; an unqualified head: sanctioned iff referred from a
              ;; sanctioned home, or referred from nowhere (host deftype)
              (= hname "deftype")
              (is (or (empty? referred-deftype-homes)
                      (some sanctioned-homes referred-deftype-homes))
                  (str path " calls (deftype ...) referred from "
                       referred-deftype-homes " — not sanctioned"))
              ;; unqualified host-deftype with no sanctioned home in
              ;; sight is a route-around
              :else
              (is (boolean (some sanctioned-homes nses))
                  (str path " calls (" hname " ...) with no sanctioned home required")))))))))

(defn- aliases-of
  "forms -> {alias-sym home-ns-sym} from the ns form's :require specs
   (a bare [ns] spec maps the ns symbol to itself)."
  [forms]
  (into {}
        (keep (fn [[ns-sym & opts]]
                (let [m (apply hash-map opts)]
                  [(or (:as m) ns-sym) ns-sym])))
        (libspecs (ns-form forms))))

(def registering-homes
  "Homes whose deftype emits a sister registration (eve.alpha only
   forwards eve.deftype's, so it counts; host-deftype emits none and
   is not here)."
  '#{eve.deftype eve.deftype-proto.macros eve.alpha})

(defn- invokes-registering-deftype?
  "True when any seq inside form is a call whose head resolves —
   through the file's alias map, or an unqualified head referred from
   a registering home — to a registration-emitting deftype macro."
  [form aliases referred-homes]
  (boolean
   (some (fn [x]
           (and (seq? x)
                (symbol? (first x))
                (= "deftype" (name (first x)))
                (let [h (first x)]
                  (if-let [ans (namespace h)]
                    (contains? registering-homes
                               (get aliases (symbol ans)))
                    (boolean (some registering-homes referred-homes))))))
         (tree-seq coll? seq form))))

(def reader-homes
  "Macro homes whose expansions register a tagged reader with
   eve.type-readers (type-io's reader-form), by the var name each
   registers through: eve.deftype-proto.macros's host-deftype and the
   public eve.deftype's deftype. The proto engine's own deftype emits
   no reader form and is not here."
  {'eve.deftype-proto.macros #{'host-deftype}
   'eve.deftype #{'deftype}})

(defn- invokes-reader-deftype?
  "True when any seq inside form is a call whose head resolves —
   through the file's alias map, or an unqualified head referred from
   a reader home — to a reader-registering macro."
  [form aliases refers]
  (boolean
   (some (fn [x]
           (and (seq? x)
                (symbol? (first x))
                (let [h (first x)
                      v (symbol (name h))]
                  (if-let [ans (namespace h)]
                    (contains? (get reader-homes
                                    (get aliases (symbol ans)) #{})
                               v)
                    (boolean
                     (some (fn [[home syms]]
                             (and (contains? syms v)
                                  (contains? (get reader-homes home #{})
                                             v)))
                           refers))))))
         (tree-seq coll? seq form))))

(deftest sister-registration-load-order
  (testing "every src/ namespace that defines a type through a
            registration-emitting macro requires eve.flat.sister"
    (let [root (guard-root)]
      (doseq [f (source-files root "src")]
        (let [{:keys [forms error]} (read-forms f)
              path (rel-path root f)]
          (if error
            ;; token fallback, mirroring no-new-deftype-macros: an
            ;; unreadable file that spells a deftype call and never
            ;; mentions the registry is flagged, never skipped silently
            (let [text (slurp f)]
              (when (and (re-find #"/deftype\s" text)
                         (not (str/includes? text "eve.flat.sister")))
                (is false
                    (str path " (unreadable, token-scanned) appears to "
                         "define an Eve type without requiring "
                         "eve.flat.sister"))))
            (let [{:keys [nses refers]} (requires-of forms)
                  aliases (aliases-of forms)
                  referred-homes (into #{}
                                       (keep (fn [[home syms]]
                                               (when (contains? syms 'deftype)
                                                 home)))
                                       refers)]
              (when (some #(invokes-registering-deftype? % aliases
                                                         referred-homes)
                          forms)
                (is (contains? nses 'eve.flat.sister)
                    (str path " defines an Eve type — its expansion "
                         "emits a top-level eve.flat.sister/"
                         "register-sister! call — but does not require "
                         "eve.flat.sister. In a ClojureScript bundle "
                         "nothing then orders the registry before the "
                         "call and the bundle dies at load. Add "
                         "[eve.flat.sister] to its ns :require."))))))))))

(deftest reader-registration-load-order
  (testing "every src/ namespace that defines a type through a
            reader-registering macro requires eve.type-readers"
    (let [root (guard-root)]
      (doseq [f (source-files root "src")]
        (let [{:keys [forms error]} (read-forms f)
              path (rel-path root f)]
          (if error
            ;; token fallback, mirroring sister-registration-load-order
            (let [text (slurp f)]
              (when (and (or (re-find #"/host-deftype\s" text)
                             (and (re-find #"\[eve\.deftype\s" text)
                                  (re-find #"/deftype\s" text)))
                         (not (str/includes? text "eve.type-readers")))
                (is false
                    (str path " (unreadable, token-scanned) appears to "
                         "define a reader-registered type without "
                         "requiring eve.type-readers"))))
            (let [{:keys [nses refers]} (requires-of forms)
                  aliases (aliases-of forms)]
              (when (some #(invokes-reader-deftype? % aliases refers)
                          forms)
                (is (contains? nses 'eve.type-readers)
                    (str path " defines a reader-registered type — its "
                         "expansion emits a top-level eve.type-readers/"
                         "register! call — but does not require "
                         "eve.type-readers. In a ClojureScript bundle "
                         "nothing then orders the registry before the "
                         "call and the bundle dies at load. Add "
                         "[eve.type-readers] to its ns :require."))))))))))

(deftest one-tree-rule-is-written
  (testing "eve.deftype's ns docstring carries the one-tree rule"
    (let [f (io/file (guard-root) "src" "eve" "deftype.cljc")
          {:keys [forms]} (read-forms f)
          doc (some (fn [form]
                      (when (and (seq? form) (= 'ns (first form)))
                        (first (filter string? (rest form)))))
                    forms)]
      (is (and doc (str/includes? doc "ONE-TREE RULE"))
          "eve.deftype's ns docstring must state the one-tree rule"))))
