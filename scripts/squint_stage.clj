;; squint_stage.clj — stage the squint gate's source roots into a
;; mirror tree, rewriting the files squint 0.16.213 miscompiles, and
;; print the staged root paths (one per line) for the gate's
;; squint.edn. Run by bb.edn's test:squint; the rules are Eve's
;; squint adapter layer at the source level — every rewrite is
;; semantics-preserving and the shared sources are never touched.
;;
;;   bb scripts/squint_stage.clj <stage-dir> <root> [<root> ...]
;;
;; The four rules, each measured against 0.16.213 (see
;; adapters/types/squint and adapters/coll/squint for the runtime
;; halves of R2/R3's story):
;;
;;  R1 poison heads. A namespace that both :refer-clojure :excludes a
;;     core name and :refers Eve's macro of that name (deftype,
;;     locking, defdelay, caching-hash, ...) gets NO macro resolution
;;     for that head from squint: its core-macro-shadowed? check
;;     consults the exclude set but not the refers, the form degrades
;;     to a runtime funcall, and a deftype's method specs are then
;;     read by squint's built-in assoc-inline as key/value pairs —
;;     'assoc expects a collection followed by key/value pairs' at
;;     best, silent miscompilation at worst. Qualify every call head
;;     in the poison set to its home namespace (eve.deftype/deftype),
;;     which resolves regardless of the excludes.
;;
;;  R2 core gaps. Bare heads naming cljs.core fns squint core does
;;     not export (unchecked-byte, gensym, lazy-cat, make-array,
;;     array-seq, read-string) compile to free variables and the
;;     module dies at load (hamt-util's unchecked-byte was the first
;;     measured). Route the heads to eve.core's — adapters/coll/squint
;;     defines each name on both faces — and require eve.core in the
;;     staged ns when a gap head fired and it is not already
;;     required. Names the file itself defines or refers are left.
;;
;;  R3 empty-list values in type forms. A () value inside a
;;     deftype-family form compiles in squint's deftype emit to a
;;     call `null()` (rseq's -empty, rrbt's -chunked-rest, sorted's
;;     hasheq). Plain defn bodies compile () correctly; the type
;;     forms' bodies go through the machinery's emit, where the
;;     empty list is read as a call with no head. Rewrite unquoted
;;     () to (list) inside those forms (quote-tracked).
;;
;;  R4 same-file macros. A macro defined and called in the same
;;     file (eve.alpha's and eve.thread's api-defs) never expands —
;;     squint takes a file's own macros only through :require-macros,
;;     the .cljc self-require pattern. Insert the self
;;     :require-macros clause naming the called macros.
;;
;;  R5 test-lib :refer-macros. Squint expands clojure.test/cljs.test
;;     macros referred via :refer or an :as alias, but a
;;     :refer-macros spec is dropped: (deftest ...) compiles to a
;;     bare call to a var that does not exist. Eve's test files use
;;     [cljs.test :refer-macros [deftest testing is]] pervasively.
;;     Rewrite the spec's :refer-macros key to :refer in the staged
;;     ns form (reader conditionals in the ns form are resolved to
;;     squint's branch — :squint, else :cljs, else :default, in
;;     source order — so the spec is visible to the rewrite).
(ns squint-stage
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def macro-names #{'deftype 'eve-deftype 'eve-extend-type 'extend-host-vectors
                   'defdelay 'field 'locking 'extend-default 'caching-hash})

(def gap-names #{'unchecked-byte 'gensym 'lazy-cat 'make-array 'array-seq
                 'read-string 'seq? 'halt-when 'sequence})

;; Core fns squint has only as operators/macros, never as values:
;; passed as a bare symbol (an argument, a map value) they compile
;; to a free variable. Routed to eve.core's value defns anywhere
;; outside quote (R6).
(def value-names #{'bit-xor})

(def type-heads #{'deftype 'eve-deftype 'extend-type 'eve-extend-type
                  'eve.deftype/deftype 'eve.deftype/eve-deftype
                  'eve.deftype/eve-extend-type})

(defn read-forms [path]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. (slurp path)))
        eof (Object.)]
    (loop [acc []]
      (let [f (read {:eof eof :read-cond :preserve} rdr)]
        (if (identical? f eof) acc (recur (conj acc f)))))))

;; ---------- R1 ----------
(defn poison-map [nsform]
  (let [clauses (drop 2 nsform)
        excl (into #{}
                   (mapcat (fn [c]
                             (if (and (seq? c) (= :refer-clojure (first c)))
                               (mapcat (fn [[k v]] (if (= :exclude k) v []))
                                       (partition 2 (rest c)))
                               []))
                           clauses))
        req-specs (mapcat (fn [c]
                            (cond (and (seq? c) (= :require (first c))) (rest c)
                                  (vector? c) [c]
                                  :else []))
                          clauses)]
    (into {}
          (keep (fn [c]
                  (when (and (vector? c) (symbol? (first c))
                             (str/starts-with? (str (first c)) "eve"))
                    (let [home (first c)]
                      (into {}
                            (mapcat (fn [[k v]]
                                      (if (and (= :refer k) (sequential? v))
                                        (keep (fn [s]
                                                (when (and (contains? excl s)
                                                           (contains? macro-names s))
                                                  [s home]))
                                              v)
                                        []))
                                    (partition 2 (rest c)))))))
                req-specs))))

(defn qualify-heads [form poison]
  (walk/postwalk
   (fn [x]
     (if (and (seq? x) (symbol? (first x)) (contains? poison (first x)))
       (let [h (first x)
             q (symbol (str (get poison h)) (str h))]
         (apply list (with-meta q (meta h)) (rest x)))
       x))
   form))

;; ---------- ns-form helpers ----------
(defn ns-clauses [nsform] (drop 2 nsform))

(defn referred-symbols [nsform]
  (into #{}
        (mapcat (fn [c]
                  (if (and (seq? c) (= :require (first c)))
                    (mapcat (fn [spec]
                              (if (vector? spec)
                                (mapcat (fn [[k v]]
                                          (if (and (= :refer k) (sequential? v)) v []))
                                        (partition 2 (rest spec)))
                                []))
                            (rest c))
                    []))
                (ns-clauses nsform))))

(defn defined-symbols [forms]
  (into #{}
        (keep (fn [f]
                (when (and (seq? f) (#{'def 'defn 'defn- 'defmacro 'defonce} (first f))
                           (symbol? (second f)))
                  (second f)))
              forms)))

(defn requires-eve-core? [nsform]
  (boolean
   (some (fn [c]
           (and (seq? c) (= :require (first c))
                (some (fn [spec] (and (sequential? spec) (= 'eve.core (first spec))))
                      (rest c))))
         (ns-clauses nsform))))

(defn add-require [nsform spec]
  (let [clauses (vec (ns-clauses nsform))
        idx (first (keep-indexed (fn [i c] (when (and (seq? c) (= :require (first c))) i)) clauses))]
    (apply list 'ns (second nsform)
           (if idx
             (assoc clauses idx (concat (nth clauses idx) [spec]))
             (conj clauses (list :require spec))))))

(defn add-require-macros [nsform specs]
  (let [clauses (vec (ns-clauses nsform))
        idx (first (keep-indexed (fn [i c] (when (and (seq? c) (= :require-macros (first c))) i)) clauses))]
    (apply list 'ns (second nsform)
           (if idx
             (assoc clauses idx (concat (nth clauses idx) specs))
             (conj clauses (apply list :require-macros specs))))))

;; ---------- R2 ----------
(defn route-gap-heads [form local-names]
  (walk/postwalk
   (fn [x]
     (if (and (seq? x) (symbol? (first x))
              (contains? gap-names (first x))
              (not (contains? local-names (first x))))
       (apply list (symbol "eve.core" (str (first x))) (rest x))
       x))
   form))

(defn gap-hit? [forms locals]
  (let [hit (atom false)]
    (walk/postwalk
     (fn [x]
       (when (or (and (seq? x) (symbol? (first x))
                      (contains? gap-names (first x))
                      (not (contains? locals (first x))))
                 (and (symbol? x)
                      (contains? value-names x)
                      (not (contains? locals x))))
         (reset! hit true))
       x)
     forms)
    @hit))

;; ---------- R6: bare value symbols -> eve.core ----------
;; ---------- R5c: repair fn* literals whose % the reader lost ----------
;; bb's reader (like squint's) scans a #() literal's raw body for
;; % to build its binding vector; a % that sits inside a #? in the
;; body is invisible to the scan, so the literal reads as (fn* []
;; ... %) — arity 0 with a free %. After R5b resolves the #?, the
;; % is a plain symbol in the body: recompute the bindings from
;; the resolved body (a nested fn*'s %s are its own).
(defn- fn-literal-refs
  "The % symbols a fn* body references at its own level."
  [body]
  (let [refs (atom #{})]
    (letfn [(walk [x]
              (cond
                (and (symbol? x) (re-matches #"(%|%\d+|%&)" (str x)))
                (swap! refs conj x)
                (and (seq? x) (= 'fn* (first x))) nil ; nested: its own
                (seq? x) (run! walk x)
                (vector? x) (run! walk x)
                (map? x) (run! (fn [[k v]] (walk k) (walk v)) x)
                (set? x) (run! walk x)
                :else nil))]
      (run! walk body))
    @refs))

(defn repair-fn-literals [form]
  ;; quote-aware: fn* forms inside quote/syntax-quote are DATA
  ;; (deftype-proto's macro templates carry fn* shapes whose %1
  ;; names the GENERATED fn's arguments) — a postwalk version of
  ;; this repair rewrote those and broke alloc/vocab's SCI load.
  (letfn [(rw [x]
            (cond
              (and (seq? x) (#{'quote 'syntax-quote} (first x))) x
              (and (seq? x) (= 'fn* (first x)) (vector? (second x)))
              (let [body (map rw (nnext x))
                    refs (fn-literal-refs body)
                    ;; the reader's canonical arg names are %1..%n
                    ;; (a bare % in source is read as %1): bindings
                    ;; are rebuilt in that form, and only when the
                    ;; existing vector does not already cover the
                    ;; referenced indices.
                    idx (fn [s] (cond (= '% s) 1
                                       (re-matches #"%\d+" (str s)) (parse-long (subs (str s) 1))
                                       :else nil))
                    mx (reduce max 0 (keep idx refs))
                    have (reduce max 0 (keep idx (second x)))
                    need (cond
                           (contains? refs '%&) '[%1 & %&]
                           (pos? mx) (mapv (fn [i] (symbol (str "%" i))) (range 1 (inc mx)))
                           :else nil)]
                (if (and need (> mx have) (not (contains? (set (second x)) '&)))
                  ;; rebuilding the binding in canonical %1 form:
                  ;; the body's own-level bare % names the same
                  ;; argument and is normalized to match (a % the
                  ;; reader left bare sat inside a #? it could not
                  ;; see into when the literal was read).
                  (apply list 'fn* need
                         (map (fn [b]
                                (letfn [(nz [y]
                                          (cond (= '% y) '%1
                                                (and (seq? y) (= 'fn* (first y))) y
                                                (seq? y) (apply list (map nz y))
                                                (vector? y) (mapv nz y)
                                                (map? y) (into {} (map (fn [[k v]] [(nz k) (nz v)])) y)
                                                (set? y) (into #{} (map nz) y)
                                                :else y))]
                                  (nz b)))
                              body))
                  (apply list 'fn* (second x) body)))
              (seq? x) (apply list (map rw x))
              (vector? x) (mapv rw x)
              (map? x) (into {} (map (fn [[k v]] [(rw k) (rw v)])) x)
              (set? x) (into #{} (map rw) x)
              :else x))]
    (rw form)))

(defn route-value-symbols [form local-names]
  (letfn [(rw [x quoted?]
            (cond
              quoted? x
              (and (symbol? x)
                   (contains? value-names x)
                   (not (contains? local-names x)))
              (symbol "eve.core" (str x))
              (seq? x) (if (#{'quote 'syntax-quote} (first x))
                         x
                         (apply list (map #(rw % false) x)))
              (vector? x) (mapv #(rw % false) x)
              (map? x) (into {} (map (fn [[k v]] [(rw k false) (rw v false)])) x)
              (set? x) (into #{} (map #(rw % false)) x)
              :else x))]
    (rw form false)))

;; ---------- R3 ----------
(defn fix-empty-lists [form]
  (letfn [(rw [x]
            (cond
              (and (seq? x) (empty? x)) (list 'list)
              (seq? x) (if (#{'quote 'syntax-quote} (first x))
                         x
                         (apply list (map rw x)))
              (vector? x) (mapv rw x)
              (map? x) (into {} (map (fn [[k v]] [(rw k) (rw v)])) x)
              (set? x) (into #{} (map rw) x)
              :else x))]
    (if (and (seq? form) (contains? type-heads (first form)))
      (rw form)
      form)))

;; ---------- R8 ----------
;; Protocols squint core names but gives no method keys. A deftype
;; spec naming a protocol bare (cljs.core's names — ICounted,
;; IIndexed, ...) compiles to a marker on squint_core.<P> and method
;; keys squint_core.<P>__<m>; squint 0.16.213's core exports the
;; marker for IVector and IReduce but NO IVector__assoc_n /
;; IReduce__reduce, so the emit falls back to the bare, undefined
;; JS names (measured: `ConcatBatch.prototype[IVector__assoc_n]` —
;; ReferenceError at load of eve/flat/freeze.mjs; the ClojureScript
;; arm of freeze.cljc spells the two bare). eve.core's squint face
;; defprotocols both (adapters/coll/squint, which exports the keys,
;; as eve/vec.cljc's ecore/IVector already compiles to
;; eve_DOT_core.IVector__assoc_n). Route the bare names, inside type
;; forms only, to eve.core's, and require eve.core in the staged ns
;; when one fired and it is not already required. Names the file
;; itself defines or refers are left.
(def core-gap-protocols #{'IVector 'IReduce})

(defn- map-type-forms
  "f applied to every type form (head in type-heads) at any depth outside
   quote — freeze.cljc wraps its deftypes in (do ...)."
  [f form]
  (letfn [(walk1 [x]
            (cond
              (and (seq? x) (#{'quote 'syntax-quote} (first x))) x
              (and (seq? x) (contains? type-heads (first x))) (f x)
              (seq? x) (with-meta (apply list (map walk1 x)) (meta x))
              (vector? x) (with-meta (mapv walk1 x) (meta x))
              (map? x) (with-meta (into {} (map (fn [[k v]] [(walk1 k) (walk1 v)])) x) (meta x))
              (set? x) (with-meta (into #{} (map walk1) x) (meta x))
              :else x))]
    (walk1 form)))

(defn- gap-protocol? [x locals]
  (and (symbol? x) (contains? core-gap-protocols x) (not (contains? locals x))))

(defn gap-protocol-hit? [forms locals]
  (let [hit (atom false)]
    (doseq [form forms]
      (map-type-forms
       (fn [t] (walk/postwalk (fn [x] (when (gap-protocol? x locals) (reset! hit true)) x) t) t)
       form))
    @hit))

(defn route-gap-protocols [form locals]
  (map-type-forms
   (fn [t]
     (letfn [(rw [x]
               (cond
                 (gap-protocol? x locals) (with-meta (symbol "eve.core" (str x)) (meta x))
                 (seq? x) (if (#{'quote 'syntax-quote} (first x))
                            x
                            (with-meta (apply list (map rw x)) (meta x)))
                 (vector? x) (with-meta (mapv rw x) (meta x))
                 (map? x) (with-meta (into {} (map (fn [[k v]] [(rw k) (rw v)])) x) (meta x))
                 (set? x) (with-meta (into #{} (map rw) x) (meta x))
                 :else x))]
       (rw t)))
   form))

;; ---------- R4 ----------
(defn self-macros [forms]
  (into #{}
        (keep (fn [f]
                (when (and (seq? f) (= 'defmacro (first f)) (symbol? (second f)))
                  (second f))))
        forms))

(defn self-macro-calls [forms macros]
  (into #{}
        (keep (fn [f]
                (when (and (seq? f) (contains? macros (first f)))
                  (first f))))
        forms))

;; R4b: expand a same-file macro call at stage time when the macro
;; reads only the file's own quoted-literal defs (eve.alpha's and
;; eve.thread's api-defs over their quoted api tables). The self
;; :require-macros of R4 makes squint load the WHOLE namespace into
;; its macro interpreter, and with it every namespace it requires —
;; eve.alpha requires eve.flat.freeze (9fd57f8), whose deftypes name
;; ClojureScript protocols (ICounted, IIndexed, ...) the macro
;; interpreter does not have: 'Protocol not found: ICounted' at the
;; ns form, and every file loading eve.alpha fails with it. The
;; expansion is the same forms squint would have spliced; a macro
;; that does not evaluate here (it reads more than quoted defs), or
;; whose expansion fails or names a symbol of the sandbox namespace
;; (syntax quote), keeps R4's self-require.
(defn quoted-def? [f]
  (and (seq? f) (= 'def (first f)) (symbol? (second f))
       (let [v (last f)] (and (seq? v) (= 'quote (first v))))))

(defn expand-self-calls [forms macros]
  (let [sb (gensym "squint-stage-r4b-")
        sbns (create-ns sb)
        mine? (fn [x] (and (symbol? x) (= (str sb) (namespace x))))
        _ (binding [*ns* sbns]
            (refer-clojure)
            (doseq [f forms
                    :when (or (quoted-def? f)
                              (and (seq? f) (= 'defmacro (first f))
                                   (contains? macros (second f))))]
              (try (eval f) (catch Exception _ nil))))
        expand (fn [f]
                 (if (and (seq? f) (contains? macros (first f))
                          (ns-resolve sbns (first f)))
                   (let [e (try (binding [*ns* sbns] (macroexpand-1 f))
                                (catch Exception _ f))]
                     (if (some mine? (tree-seq coll? seq e)) f e))
                   f))
        out (mapv expand forms)]
    (remove-ns sb)
    out))

;; ---------- R4c ----------
;; Forwarding macros of a namespace squint's macro loader cannot
;; load. A .cljc that defines a macro is loaded into squint's SCI
;; macro interpreter whenever another file requires it — whole, with
;; every namespace it requires (measured on 0.16.213: a required
;; .cljc without defmacro is not loaded; with one it is, even when
;; the requirer calls no macro). eve.alpha requires eve.flat.freeze
;; (9fd57f8), whose deftypes name ClojureScript protocols (ICounted,
;; IIndexed, ...) the interpreter does not have: 'Protocol not
;; found: ICounted' (loading eve/alpha.cljc), and every file that
;; requires eve.alpha fails. eve.alpha's macros only forward (its
;; api-defs is expanded by R4b): the staged eve.alpha drops its
;; defmacros, so it is compiled and never macro-loaded, and its
;; callers' heads are rewritten to the forms the macros return —
;; deftype/extend-type to eve.deftype's macros, ($ body...) to
;; (eve.atom/$* (fn [] body...)), the function the macro calls.
(def forward-macros
  '{eve.alpha {deftype [:head eve.deftype/deftype]
               extend-type [:head eve.deftype/eve-extend-type]
               $ [:thunk eve.atom/$*]}})

(defn- spec-ns [spec] (when (and (vector? spec) (symbol? (first spec))) (first spec)))

(defn- spec-opts [spec] (apply hash-map (rest spec)))

(defn r4c-file [forms]
  (let [nsidx (first (keep-indexed (fn [i f] (when (and (seq? f) (= 'ns (first f))) i)) forms))
        nsform (when nsidx (nth forms nsidx))
        own (when nsform (second nsform))]
    (cond
      (nil? nsform) forms
      ;; the forwarding namespace itself: its own calls expanded (R4b),
      ;; then no defmacro left
      (contains? forward-macros own)
      (let [expanded (expand-self-calls forms (self-macros forms))]
        (if (seq (self-macro-calls (remove #(and (seq? %) (= 'defmacro (first %))) expanded)
                                   (self-macros forms)))
          forms ; a call R4b could not expand: leave the file to R4
          (vec (remove #(and (seq? %) (= 'defmacro (first %))) expanded))))
      :else
      (let [specs (for [c (drop 2 nsform)
                        :when (and (seq? c) (#{:require :require-macros} (first c)))
                        spec (rest c)
                        :let [n (spec-ns spec)]
                        :when (contains? forward-macros n)]
                    [n (first c) (spec-opts spec)])
            heads (into {}
                        (mapcat (fn [[n _ opts]]
                                  (let [tbl (forward-macros n)]
                                    (concat
                                     (when-let [a (:as opts)]
                                       (for [[m v] tbl] [(symbol (str a) (str m)) v]))
                                     (for [m (:refer opts) :when (contains? tbl m)] [m (tbl m)])))))
                        specs)]
        (if (empty? heads)
          forms
          (let [fired (atom #{})
                rw (fn rw [x]
                     (cond
                       (and (seq? x) (= 'quote (first x))) x
                       (and (seq? x) (contains? heads (first x)))
                       (let [[kind target] (heads (first x))
                             args (map rw (rest x))]
                         (swap! fired conj (symbol (namespace target)))
                         (case kind
                           :head (apply list (with-meta target (meta (first x))) args)
                           :thunk (list target (apply list 'fn [] args))))
                       (seq? x) (with-meta (apply list (map rw x)) (meta x))
                       (vector? x) (with-meta (mapv rw x) (meta x))
                       (map? x) (with-meta (into {} (map (fn [[k v]] [(rw k) (rw v)])) x) (meta x))
                       (set? x) (with-meta (into #{} (map rw) x) (meta x))
                       :else x))
                body (vec (map-indexed (fn [i f] (if (= i nsidx) f (rw f))) forms))
                fwd (set (mapcat (comp keys forward-macros) (keys forward-macros)))
                fix-spec (fn [spec]
                           (if (contains? forward-macros (spec-ns spec))
                             (let [opts (spec-opts spec)
                                   refer (when (:refer opts) (vec (remove fwd (:refer opts))))
                                   opts (if (seq refer) (assoc opts :refer refer) (dissoc opts :refer))]
                               (into [(first spec)] (mapcat identity opts)))
                             spec))
                clauses (keep (fn [c]
                                (cond
                                  (and (seq? c) (= :require-macros (first c)))
                                  (let [ss (keep (fn [spec]
                                                   (if (contains? forward-macros (spec-ns spec))
                                                     nil
                                                     spec))
                                                 (rest c))]
                                    (when (seq ss) (apply list :require-macros ss)))
                                  (and (seq? c) (= :require (first c)))
                                  (apply list :require (map fix-spec (rest c)))
                                  :else c))
                              (drop 2 nsform))
                required (set (for [c clauses :when (and (seq? c) (= :require (first c)))
                                    spec (rest c) :let [n (spec-ns spec)] :when n] n))
                nsform2 (apply list 'ns own clauses)
                nsform3 (reduce (fn [nf t] (if (contains? required t) nf (add-require nf [t])))
                                nsform2 @fired)]
            (assoc body nsidx (with-meta nsform3 (meta nsform)))))))))

;; ---------- R5 ----------
;; ---------- R7 ----------
;; An unmatched reader conditional (no :squint/:cljs branch and no
;; :default) VANISHES under every real reader — the form contributes
;; nothing to the containing list. Substituting nil for it (the old
;; rc-select tail) staged a literal nil where the conditional sat,
;; and a nil sitting in an ns form's clause list kills squint's
;; load of the namespace inside a macro chain: squint's ns scan
;; dispatches on clause heads and throws "No matching clause: "
;; (the value, nil, prints empty) — eve.type-readers' #?(:lpy
;; (:import ...)) clause staged that nil, and every dependent of
;; eve.type-readers failed to compile (kind-hypatia RESULT #45:
;; 65 sources, one identical cause). rc-select therefore returns
;; the no-branch sentinel for unmatched conditionals — distinct
;; from a branch whose VALUE is nil, which is a real selection and
;; is kept — and resolve-ns-rcs drops sentinel elements from seqs
;; and vectors. A whole top-level form that selects nothing still
;; stages as nil (a benign top-level literal), as before.
(def ^:private no-branch (Object.))

(defn rc-select
  "The branch form squint's reader selects from a preserved reader
   conditional: the first of :squint/:cljs present in source order,
   else :default, else the no-branch sentinel."
  [rc]
  ;; *print-meta* is bound for the round trip: the re-read below is
  ;; how a conditional's selected branch re-enters the form stream,
  ;; and metadata inside the branch (^:mutable on a deftype's fields,
  ;; ^tag hints) is load-bearing downstream — SCI registers a
  ;; deftype's field mutators from the fields' :mutable meta when it
  ;; evaluates the staged file in a macro cone, and a stripped field
  ;; reads as immutable there ("Invalid assignment target" on the
  ;; body's set!). The final spit binds *print-meta* for the same
  ;; reason; the round trip must not defeat it.
  (let [s (binding [*print-meta* true] (pr-str rc))
        splicing (str/starts-with? s "#?@")
        ;; :read-cond :preserve is load-bearing: a plain re-read
        ;; resolves NESTED #?s with bb's own feature set (#{:bb
        ;; :clj}) before resolve-ns-rcs can see them — an RC inside
        ;; this RC's selected branch came out :clj-resolved
        ;; (reference-tag-test's tmpdir read (System/getProperty)
        ;; from its :clj arm instead of "/tmp" from :default).
        inner (read-string {:read-cond :preserve}
                           (str "(" (subs s (if splicing 4 3) (dec (count s))) ")"))
        pairs (partition 2 inner)
        ;; a branch's value may itself be nil (#?(:cljs nil
        ;; :default (do ...)) is the standard no-op-on-this-host
        ;; idiom): match on the KEY with a sentinel, never on the
        ;; value's truthiness, or a nil :cljs branch falls through
        ;; to :default and stages the wrong host's forms
        ;; (numeric-tags-test's ratio/decimal round-trips ran on
        ;; squint that way).
        hit (first (filter (fn [[k _]] (#{:squint :cljs} k)) pairs))
        dflt (first (filter (fn [[k _]] (= :default k)) pairs))]
    (cond hit (second hit)
          dflt (second dflt)
          :else no-branch)))

(defn resolve-ns-rcs
  "Resolve reader conditionals in an ns form to squint's branches,
   splicing where the conditional splices."
  [x]
  (cond
    (instance? clojure.lang.ReaderConditional x)
    (let [sel (rc-select x)]
      (if (identical? sel no-branch) no-branch (resolve-ns-rcs sel)))

    (seq? x)
    (apply list
           (mapcat (fn [e]
                     (if (and (instance? clojure.lang.ReaderConditional e)
                              (str/starts-with? (pr-str e) "#?@"))
                       (let [sel (rc-select e)]
                         (if (identical? sel no-branch)
                           []
                           (resolve-ns-rcs sel)))
                       (let [r (resolve-ns-rcs e)]
                         (if (identical? r no-branch) [] [r]))))
                   x))

    (vector? x)
    (into []
          (mapcat (fn [e]
                    (if (and (instance? clojure.lang.ReaderConditional e)
                             (str/starts-with? (pr-str e) "#?@"))
                      (let [sel (rc-select e)]
                        (if (identical? sel no-branch)
                          []
                          (resolve-ns-rcs sel)))
                      (let [r (resolve-ns-rcs e)]
                        (if (identical? r no-branch) [] [r])))))
          x)

    :else x))

(defn fix-test-spec
  "One require spec: [cljs.test :refer-macros [deftest ...]] ->
   [cljs.test :refer [deftest ...]] (merging an existing :refer)."
  [spec]
  (if (and (vector? spec) (#{'cljs.test 'clojure.test} (first spec)))
    (let [parts (partition 2 (rest spec))
          rm (some (fn [[k v]] (when (= :refer-macros k) v)) parts)
          rf (some (fn [[k v]] (when (= :refer k) v)) parts)]
      (if rm
        (into [(first spec)]
              (concat
               (mapcat (fn [[k v]]
                         (cond (= :refer-macros k) []
                               (= :refer k) [:refer (vec (distinct (concat v rm)))]
                               :else [k v]))
                       parts)
               ;; no existing :refer pair: the merged refer must be
               ;; emitted explicitly, or the names vanish (the
               ;; coalesc_align_test deftest failure, 2026-11-13).
               (when-not rf [:refer (vec rm)])))
        spec))
    spec))

(defn fix-test-specs [nsform]
  (let [nsform (resolve-ns-rcs nsform)]
    (apply list 'ns (second nsform)
           (map (fn [c]
                  (if (and (seq? c) (= :require (first c)))
                    (apply list :require (map fix-test-spec (rest c)))
                    c))
                (drop 2 nsform)))))

;; ---------- per-file transform ----------
(declare transform-file*)
(defn transform-file [forms]
  ;; R5b: reader conditionals resolved file-wide, not only in the
  ;; ns form — squint's own #? handling loses a #() literal's %
  ;; when a #? sits inside it (keyring-test's region-bytes).
  ;; R7: a top-level form that is itself an unmatched conditional
  ;; stages as nil (benign); unmatched conditionals NESTED in a
  ;; form are dropped by resolve-ns-rcs, never nil-substituted.
  (let [resolved (mapv (fn [f]
                         (let [r (resolve-ns-rcs f)]
                           (if (identical? r no-branch) nil r)))
                       forms)
        ;; R4c: callers of forwarding macros rewritten (the forwarding
        ;; namespace's own defmacros drop after R4b, in transform-file*)
        resolved (r4c-file resolved)]
    (transform-file* resolved (not= resolved forms))))

(defn transform-file* [forms r5b-changed?]
  (let [nsidx (first (keep-indexed (fn [i f] (when (and (seq? f) (= 'ns (first f))) i)) forms))
        nsform (when nsidx (nth forms nsidx))
        nsform (when nsform (fix-test-specs nsform))
        r5-changed (and nsform (not= nsform (nth forms nsidx)))
        poison (when nsform (poison-map nsform))
        locals (into (defined-symbols forms) (referred-symbols nsform))
        macros (self-macros forms)
        forms0 forms
        forms (if (seq macros) (expand-self-calls forms macros) forms)
        r4b-changed (not= forms0 forms)
        calls (self-macro-calls (remove #(and (seq? %) (= 'defmacro (first %))) forms) macros)
        need-core (and (or (gap-hit? forms locals)
                           (gap-protocol-hit? forms locals))
                       (not (requires-eve-core? nsform)))
        has-type-form (boolean (some (fn [f] (and (seq? f) (contains? type-heads (first f)))) forms))]
    (when (or (seq poison) need-core (seq calls) has-type-form r5-changed r5b-changed? r4b-changed)
      (let [forms2 (mapv (fn [f]
                           (cond-> f
                             (seq poison) (qualify-heads poison)
                             true (route-gap-heads locals)
                             true (route-value-symbols locals)
                             true (route-gap-protocols locals)
                             true (repair-fn-literals)
                             true (fix-empty-lists)))
                         (assoc forms nsidx nsform))
            nsform2 (nth forms2 nsidx)
            nsform3 (cond-> nsform2
                      need-core (add-require '[eve.core])
                      (seq calls) (add-require-macros
                                   (mapv (fn [m] [(second nsform) :refer [m]]) calls)))]
        (assoc forms2 nsidx nsform3)))))

;; ---------- staging ----------
(let [[stage & roots] *command-line-args*]
  (when (fs/exists? stage) (fs/delete-tree stage))
  (fs/create-dirs stage)
  (doseq [[i root] (map-indexed vector roots)]
    (let [src-root (fs/file root)
          dst-root (str stage "/root-" i)]
      (fs/create-dirs dst-root)
      (doseq [f (file-seq src-root) :when (fs/regular-file? f)]
        (let [rel (str (fs/relativize src-root f))
              dst (str dst-root "/" rel)]
          (fs/create-dirs (str (fs/parent dst)))
          (if (or (str/ends-with? rel ".cljc") (str/ends-with? rel ".cljs"))
            (let [forms (try (read-forms (str f)) (catch Exception e nil))
                  t (when forms (try (transform-file forms) (catch Exception e nil)))]
              (if t
                (spit dst (with-out-str
                            (binding [*print-meta* true]
                              (doseq [fm t] (prn fm)))))
                (fs/create-sym-link dst (str f))))
            (fs/create-sym-link dst (str f)))))
      (println (str (fs/absolutize dst-root))))))
