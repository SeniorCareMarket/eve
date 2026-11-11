(ns eve.host-completion
  "What eve.host loads first on the babashka seat.

   Babashka's SCI refuses a deftype that declares the map interfaces
   piecemeal:

     Babashka's deftype supports full map types (add IPersistentMap to
     the interface list). Alternatively, use reify for individual
     interfaces like ILookup, Associative.

   A vanilla library type written for the JVM — datascript.lru's LRU,
   which declares clojure.lang.ILookup and clojure.lang.Associative
   and implements exactly the lookup surface it offers — therefore
   cannot load on this seat at all, though the same declaration is an
   ordinary full citizen on the JVM. Measured: stock bb with no Eve
   refuses it identically; the refusal is the declaration shape
   against SCI, not an Eve interaction. The rule matches only the
   clojure.lang-qualified spellings of those two interfaces, and only
   when clojure.lang.IPersistentMap is absent (bb 1.13.226).

   The seat's answer is presentation, in the seat: loading this
   namespace re-roots clojure.core/deftype — the pattern the jank
   completion uses for its core re-roots — with a presenter that
   appends clojure.lang.IPersistentMap to a declaration listing
   ILookup or Associative without it, and passes every other deftype
   through to SCI's own deftype argument-identical. The library is
   untouched; namespaces compiled after this one see the presenter.

   The presenter is a plain function, not a macro: SCI calls a macro
   var's root with (form env . args) only when the root is a plain
   fn — a defmacro product is dispatched to its inner fn with plain
   args instead (measured on this seat).

   What the presentation means for the type: SCI implements a deftype
   listing IPersistentMap as its map type, synthesizing the map
   surface the declaration does not give — the methods it did not
   declare (count, seq, empty, cons, without) answer by throwing
   \"Method not implemented\", and map? answers true. A type that
   offers only lookup and assoc on the JVM offers only lookup and
   assoc here; nothing it does offer changes meaning (the upstream
   datascript.test.lru suite passes against the presented type)."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------
;; The seat's floor. Eve's types on this seat carry Object's equals and
;; hashCode (eve.types/bb-translate-protos: = and hash answer through the
;; type's equiv and hasheq), and SCI's deftype takes an Object hashCode
;; from babashka 1.12.214 on (babashka's CHANGELOG, 1.12.214: "deftype
;; supports Object + hashCode"). An older bb dies analyzing the first
;; such type with "Could not resolve symbol: sci.impl.deftype/hashCode"
;; (measured: bb 1.3.190 at eve/rseq.cljc via eve/vec.cljc — the ants
;; ladder's bb member, which a launcher had resolved to a stale bb on
;; PATH). That message names no version, so the seat refuses an older
;; bb here, before any type loads, saying which bb it is and what it needs.

(def min-babashka-version
  "The oldest babashka this seat loads on: [major minor patch]."
  [1 12 214])

(defn- parse-version [s]
  (mapv #(Long/parseLong %) (re-seq #"\d+" (str s))))

(let [running (System/getProperty "babashka.version")
      v (parse-version running)]
  (when (neg? (compare (vec (take 3 (concat v (repeat 0)))) min-babashka-version))
    (throw (ex-info (str "Eve's babashka seat needs babashka >= "
                         (str/join "." min-babashka-version)
                         " (deftype with Object hashCode); this is babashka " running
                         " — run Eve on a current bb")
                    {:type :eve/babashka-too-old
                     :running running
                     :required min-babashka-version}))))

(defonce ^:private sci-deftype
  @#'clojure.core/deftype)

(def ^:private piecemeal-map-interfaces
  "The interfaces SCI's deftype refuses piecemeal (measured)."
  '#{clojure.lang.ILookup clojure.lang.Associative})

(def ^:private full-map-interface 'clojure.lang.IPersistentMap)

(defn- interface-symbols
  "The bare symbols heading a deftype's spec sections, in order —
   every top-level symbol in opts except a keyword option's value."
  [opts]
  (loop [xs (seq opts), acc []]
    (cond (nil? xs)            acc
          (keyword? (first xs)) (recur (nnext xs) acc)
          (symbol? (first xs))  (recur (next xs) (conj acc (first xs)))
          :else                 (recur (next xs) acc))))

(defn- present-deftype
  "clojure.core/deftype, presented for SCI: a declaration of
   clojure.lang.ILookup or clojure.lang.Associative is presented as
   the full map declaration SCI takes, by appending
   clojure.lang.IPersistentMap after the specs (the position SCI
   accepts, measured); any other declaration is passed through
   unchanged."
  [form env type-name fields & opts]
  (let [ifaces (interface-symbols opts)]
    (if (and (some piecemeal-map-interfaces ifaces)
             (not (some #(= full-map-interface %) ifaces)))
      ;; The map-mode instance is a babashka.impl.SciMap, which
      ;; carries no reference to its type: (type x) answers SciMap
      ;; and (instance? T x) answers false (measured on datascript's
      ;; Entity, fix-for-release E4 — entity? false, so touch, seq,
      ;; count, keys, into and pr-str all died at entity?'s assert).
      ;; The presented factory stamps the type on each instance it
      ;; builds (present-map-mode-type!, below), where the presented
      ;; type / instance? / meta read it back.
      (list 'do
            (apply sci-deftype form env type-name fields
                   (concat opts [full-map-interface]))
            (list 'eve.host-completion/present-map-mode-type!
                  (list 'var (symbol (str "->" (name type-name))))
                  type-name))
      (apply sci-deftype form env type-name fields opts))))

(alter-var-root #'clojure.core/deftype (fn [_] present-deftype))

;; ---------------------------------------------------------------------
;; The clojure.lang statics surface.
;;
;; Vanilla JVM library code calls a small set of clojure.lang statics
;; by name: clojure.lang.Util/hashCombine and Util's hasheq (the
;; latter in the (. clojure.lang.Util (hasheq x)) static-call form),
;; clojure.lang.Numbers/compare, clojure.lang.RT/assoc. Measured on
;; bb 1.13.226: Util and Numbers are absent from the seat entirely
;; (Class/forName throws, and SCI cannot resolve the names), and RT
;; is present but its assoc cannot be reflectively invoked in the
;; native image (MissingReflectionRegistrationError). Integer/compare,
;; which the same library code names, resolves and runs natively and
;; needs no presentation.
;;
;; The seat's answer is presentation, in the seat, in the shape the
;; jolt/glojure completions use for the names their seats lack: a
;; namespace bearing the class's name, with the statics interned as
;; vars holding implementations of the same semantics. SCI resolves
;; clojure.lang.Util/hashCombine to the var when no class by that
;; name is registered, and an interned var shadows the class
;; member-by-member — clojure.lang.RT/assoc answers through the var
;; while RT/aset, which the seat can invoke, still answers through
;; the class (all measured).
;;
;; The implementations are the seat's own, and they agree with the
;; JVM's values: hash-combine is clojure.core/hash-combine (measured
;; equal to Util/hashCombine on samples), hash is Util/hasheq
;; (measured equal on nil, longs, doubles, strings, keywords,
;; symbols, chars, vectors, maps, BigInt, BigDecimal, UUID), compare
;; on numbers is the numeric comparison Numbers/compare performs,
;; and assoc is clojure.core/assoc, which is what RT/assoc returns.

;; The clojure.core$<fn> tag names (F-JOLT-TAG family): a library
;; written for the JVM names a primitive in a type tag by the class
;; of clojure.core's cast fn — datascript's patch-tag tags 'number
;; as clojure.core$long, the AOT class of clojure.core/long. SCI has
;; no such class and its analyzer dies resolving the tag (measured,
;; db.cljc's value-compare). The jolt and glojure completions intern
;; the names on their seats; this seat never got them. Interned here
;; as the fns themselves — a tag is a hint, and nothing on this seat
;; lets a tag act.
(doseq [f '[long double boolean int short byte char float]]
  (intern 'clojure.core (symbol (str "clojure.core$" (name f)))
          @(find-var (symbol "clojure.core" (name f)))))

(create-ns 'clojure.lang.Util)
(intern 'clojure.lang.Util 'hashCombine
        (fn [seed hash] (hash-combine seed hash)))
(intern 'clojure.lang.Util 'hasheq
        (fn [x] (hash x)))

(create-ns 'clojure.lang.Numbers)
(intern 'clojure.lang.Numbers 'compare
        (fn [x y] (cond (< x y) -1 (> x y) 1 :else 0)))

(create-ns 'clojure.lang.RT)
(intern 'clojure.lang.RT 'assoc
        (fn [m k v] (assoc m k v)))

;; The (. clojure.lang.Util (hasheq x)) form is the one shape the
;; interned vars cannot reach: SCI reads the dot form's target as a
;; class or as a var's value to invoke a method on, and no object on
;; this seat carries a hasheq method (SCI-created types refuse
;; interop method calls outright — "Method toString on class
;; sci.impl.deftype.SciType not allowed!", measured). So defn and
;; defmacro — the forms a library's function bodies are written in —
;; are re-rooted with presenters in the deftype pattern above: a
;; body form (. <presented-class> (method args...)) or
;; (. <presented-class> method args...) naming one of the presented
;; statics is presented as the slash call (<class>/method args...),
;; which resolves to the interned var; every other form passes
;; through structurally identical, and (quote ...) subtrees — data,
;; not code — are never entered. SCI does not honor :inline on this
;; seat (measured), so a defn's :inline template naming the dot form
;; is inert data here and needs no presentation. The one shape not
;; presented is a macro whose expansion itself is a presented dot
;; call: expansions are analyzed in the caller, where no presenter
;; sits. No such form exists in the library code this surface was
;; censused against — the one macro template there naming a
;; presented static (datascript.inline's assoc) uses the slash form,
;; which the interned vars answer.

;; clojure.lang.LazilyPersistentVector is likewise absent from
;; the image, and datascript.query imports the class and calls
;; its createOwning static (a vector owning an Object array —
;; (vec arr) here). The import is satisfied by a placeholder type
;; of that name in clojure.lang (SCI's import resolves type vars,
;; as it does for Eve's own types); the static is interned as a
;; var, and the defn presenter below rewrites the imported short
;; name's call, which resolves through neither.
(binding [*ns* (the-ns 'clojure.lang)]
  (eval '(deftype LazilyPersistentVector [lpv])))

(create-ns 'clojure.lang.LazilyPersistentVector)
(intern 'clojure.lang.LazilyPersistentVector 'createOwning
        (fn [arr] (vec arr)))

(def ^:private presented-statics
  "The statics presented above, by class name: the dot-form presenter
   rewrites calls to exactly these."
  '{clojure.lang.Util    #{hashCombine hasheq}
    clojure.lang.Numbers #{compare}
    clojure.lang.RT      #{assoc}})

(defn- rewrite-statics
  "form with presented statics dot-calls presented as slash calls."
  [form]
  (cond
    (seq? form)
    (if (= 'quote (first form))
      form
      (let [[head target method-call] form]
        (cond
          (contains? '#{LazilyPersistentVector/createOwning
                        clojure.lang.LazilyPersistentVector/createOwning}
                     head)
          (list 'vec (rewrite-statics (second form)))
          (and (= '. head)
               (contains? presented-statics target)
               (sequential? method-call)
               (contains? (get presented-statics target)
                          (first method-call)))
          (apply list (symbol (str target) (str (first method-call)))
                 (map rewrite-statics (concat (rest method-call)
                                              (nnext (nnext form)))))

          (and (= '. head)
               (contains? presented-statics target)
               (symbol? method-call)
               (contains? (get presented-statics target) method-call))
          (apply list (symbol (str target) (str method-call))
                 (map rewrite-statics (nthnext form 3)))

          :else
          (with-meta (apply list (map rewrite-statics form))
            (meta form)))))

    (vector? form)
    (with-meta (mapv rewrite-statics form) (meta form))

    (map? form)
    (with-meta (into {} (map (fn [[k v]] [(rewrite-statics k)
                                          (rewrite-statics v)])
                             form))
      (meta form))

    (set? form)
    (with-meta (into #{} (map rewrite-statics form)) (meta form))

    :else form))

(defn- lookup-ns?
  "The namespace being analyzed is written against clojure.core's
   vocabulary — library and user code — as opposed to the seat's
   own implementation (eve.* is written in eve.core's vocabulary
   already, and this completion's defaults name clojure.core
   qualified). *ns* is the analyzed file's namespace when a
   presenter runs (measured)."
  []
  (let [n (str *ns*)]
    ;; eve.db.ds is a library written against clojure.core's
    ;; vocabulary (keyword lookup, get, nth) on Eve types (Datom
    ;; is the measured case). The eve.* skip is for Eve's own
    ;; sources, which already use eve.core; without this carve-out
    ;; (:e datom) in eve.db.ds stays a host keyword invoke and
    ;; answers nil on SciType (bb contract.only-core: #{[nil]}).
    (not (or (and (str/starts-with? n "eve.")
                  (not (str/starts-with? n "eve.db.ds")))
             (str/starts-with? n "clojure.")
             (= n "eve.host-completion")))))

(defn- rewrite-lookup
  "form with the core lookup calls presented as eve.core protocol
   calls. clojure.core/get, nth and count cannot be presented at
   the var: the analyzer compiles direct calls to them past the
   var, to host fns that cannot see this seat's types (measured).
   But those types' get/nth/count impls live on eve.core's
   protocols — the translation put them there — so in a body
   written against clojure.core's vocabulary, (get x k) is
   (eve.core/get x k), (nth x i) is (eve.core/nth x i), (count x)
   is (eve.core/count x), and a (.valAt x k) interop call is
   (eve.core/-lookup x k). On host values eve.core's defaults
   delegate to the host fns, so the presentation is
   value-preserving. The measured consumers are the DataScript
   port's bodies (its query copy loop reads (get datom \"e\"),
   its join getters call (.valAt tuple idx)) and driver code
   counting a datoms seq. Quoted subtrees are data and are never
   entered."
  [form]
  (cond
    (seq? form)
    (if (= 'quote (first form))
      form
      (let [head (first form)
            args (rest form)]
        (cond
          ;; a keyword in call position is a lookup: (:name e),
          ;; (:e datom) — the keyword's own invoke is host code
          ;; and cannot see this seat's types, so it is presented
          ;; as eve.core/get, whose defaults answer host values.
          (and (keyword? head) (<= 1 (count args) 2))
          (apply list 'eve.core/get
                 (rewrite-lookup (first args)) head
                 (map rewrite-lookup (rest args)))

          ;; a bare keyword step in a threading form is the same
          ;; lookup, threaded: (-> d first :e). The step becomes
          ;; a fn of the threaded value, which -> and ->> both
          ;; place correctly.
          (contains? '#{-> ->> some-> some->>} head)
          (apply list head
                 (rewrite-lookup (first args))
                 (map (fn [step]
                        (if (keyword? step)
                          (list (list 'fn '[x]
                                      (list 'eve.core/get 'x step)))
                          (rewrite-lookup step)))
                      (rest args)))

          (and (= 'get head) (<= 2 (count args) 3))
          (apply list 'eve.core/get (map rewrite-lookup args))

          (and (= 'nth head) (<= 2 (count args) 3))
          (apply list 'eve.core/nth (map rewrite-lookup args))

          (and (= 'count head) (= 1 (count args)))
          (list 'eve.core/count (rewrite-lookup (first args)))

          (and (= '.valAt head) (<= 2 (count args) 3))
          (apply list 'eve.core/-lookup (map rewrite-lookup args))

          (and (= '. head)
               (sequential? (nth form 2 nil))
               (= 'valAt (first (nth form 2 nil)))
               (<= 2 (count (rest (nth form 2 nil))) 3))
          (apply list 'eve.core/-lookup
                 (rewrite-lookup (second form))
                 (map rewrite-lookup (rest (nth form 2 nil))))

          :else
          (with-meta (apply list (map rewrite-lookup form))
            (meta form)))))

    (vector? form)
    (with-meta (mapv rewrite-lookup form) (meta form))

    (map? form)
    (with-meta (into {} (map (fn [[k v]] [(rewrite-lookup k)
                                          (rewrite-lookup v)])
                             form))
      (meta form))

    (set? form)
    (with-meta (into #{} (map rewrite-lookup form)) (meta form))

    :else form))

(defn- eve-core-ready?
  "eve.core is loaded, loading it now if it is not. rewrite-lookup emits
   eve.core/get, nth, count and -lookup, so a presented body may only carry
   them once eve.core exists. The load below this namespace's try (require
   'eve.core) runs while eve.host is still loading it — eve.core requires
   eve.host — and is refused there, so on a classpath whose namespaces never
   name eve.core (eve.crypto, eve.hamt-util, eve.thread-host: the seam bench)
   eve.core was absent and every presented (nth ...) failed analysis with
   \"Unable to resolve symbol: eve.core/nth\" (measured, fix-for-release E1).
   By the time a port namespace's defn is analyzed eve.host has finished, so
   the load succeeds here. If it cannot load, no value carries eve.core's
   protocols, and clojure.core's lookups are the right calls: no rewrite."
  []
  (or (some? (find-ns 'eve.core))
      (try (require 'eve.core) true
           (catch Throwable _ false))))

(defonce ^:private sci-defn
  @#'clojure.core/defn)

(defn- present-defn
  "clojure.core/defn, presented for SCI: statics dot-calls in the
   body are presented as slash calls (see rewrite-statics), and
   in a port namespace the core lookup calls are presented as
   eve.core protocol calls (see rewrite-lookup); any other defn
   passes through argument-identical."
  [form env name & fdecl]
  (apply sci-defn form env name
         (if (and (lookup-ns?) (eve-core-ready?))
           (map (fn [f] (rewrite-lookup (rewrite-statics f))) fdecl)
           (map rewrite-statics fdecl))))

(alter-var-root #'clojure.core/defn (fn [_] present-defn))

(defonce ^:private sci-defmacro
  @#'clojure.core/defmacro)

(defn- present-defmacro
  "clojure.core/defmacro, presented as present-defn."
  [form env name & fdecl]
  (apply sci-defmacro form env name
         (if (and (lookup-ns?) (eve-core-ready?))
           (map (fn [f] (rewrite-lookup (rewrite-statics f))) fdecl)
           (map rewrite-statics fdecl))))

(alter-var-root #'clojure.core/defmacro (fn [_] present-defmacro))

;; ---------------------------------------------------------------------
;; A Comparator that is also a function.
;;
;; Vanilla JVM library code defines a comparator as a reify of two
;; interfaces — java.util.Comparator with a compare method, and
;; clojure.lang.IFn with an invoke that delegates to it (datascript's
;; defcomp, which defines every index comparator in the library at
;; load). Babashka's reify supports a single interface
;; ("babashka reify only supports implementing a single interface",
;; measured on bb 1.13.226), so the definition throws as it is
;; evaluated and the library cannot load.
;;
;; On this seat the value's whole observable contract is that it is
;; callable with two arguments and returns the comparison: the
;; consumers (the sorted-set library's (cmp a b) calls, sort) call
;; it as a function, nothing in the censused code calls .compare on
;; it or tests it for Comparator, and where a Comparator object is
;; needed from a function, clojure.core/comparator is the seat's own
;; bridge. So reify is re-rooted with a presenter in the pattern
;; above: a reify of exactly {java.util.Comparator, clojure.lang.IFn}
;; whose invoke methods all delegate to .compare is presented as a
;; plain fn of the compare body; every other reify — including other
;; multi-interface reifies, which keep the seat's native refusal —
;; passes through argument-identical.

(defonce ^:private sci-reify
  @#'clojure.core/reify)

(defn- reify-spec-groups
  "A reify's specs grouped as [head method-form...] lists."
  [specs]
  (reduce (fn [groups x]
            (if (symbol? x)
              (conj groups [x])
              (update groups (dec (count groups)) conj x)))
          [] specs))

(defn- subst-symbol
  "form with occurrences of `from` replaced by `to`; (quote ...)
   subtrees are data and are never entered."
  [form from to]
  (cond
    (= form from) to
    (seq? form)    (if (= 'quote (first form))
                     form
                     (with-meta (apply list (map #(subst-symbol % from to) form))
                       (meta form)))
    (vector? form) (with-meta (mapv #(subst-symbol % from to) form) (meta form))
    (map? form)    (with-meta (into {} (map (fn [[k v]] [(subst-symbol k from to)
                                                         (subst-symbol v from to)])
                                            form))
                     (meta form))
    (set? form)    (with-meta (into #{} (map #(subst-symbol % from to) form)) (meta form))
    :else          form))

(defn- present-reify
  "clojure.core/reify, presented for SCI: the Comparator+IFn pair is
   presented as a fn of the compare body (see above); the
   IDeref-family triple below is presented as an IDeref reify; any
   other reify passes through argument-identical."
  [form env & specs]
  (let [groups (reify-spec-groups specs)
        by-head (into {} (map (fn [[head & methods]] [head methods]) groups))]
    (cond
      ;; clojure.lang.IDeref with IBlockingDeref / IPending
      ;; companions (datascript's transact result is the measured
      ;; case): IBlockingDeref does not resolve in a reify spec on
      ;; this seat and IPending is refused, and the seat's own
      ;; deref-with-timeout and realized? reach only host
      ;; Future/IPending objects in any case — no reify can answer
      ;; them here. The reachable contract is deref, so the reify
      ;; is presented as IDeref alone with its 1-arg deref body.
      (and (contains? by-head 'clojure.lang.IDeref)
           (every? (fn [h] (contains? '#{clojure.lang.IDeref
                                         clojure.lang.IBlockingDeref
                                         clojure.lang.IPending} h))
                   (keys by-head)))
      (let [deref1 (first (filter #(and (= "deref" (name (first %)))
                                        (= 1 (count (second %))))
                                  (get by-head 'clojure.lang.IDeref)))]
        (if deref1
          (apply sci-reify form env
                 (list 'clojure.lang.IDeref deref1))
          (apply sci-reify form env specs)))

      (and (= '#{java.util.Comparator clojure.lang.IFn}
              (set (keys by-head)))
             ;; the Comparator group carries a single 2-arg compare
             ;; (method names are matched by name: a macro's syntax
             ;; quote qualifies them — clojure.core/compare, the
             ;; defining ns's invoke — in its expansion)
             (let [cmps (filter #(= "compare" (name (first %)))
                                (get by-head 'java.util.Comparator))]
               (and (= 1 (count cmps))
                    (= 3 (count (second (first cmps)))))))
      (let [[_ [this a b] & body] (first (filter #(= "compare" (name (first %)))
                                                 (get by-head 'java.util.Comparator)))
            invokes (get by-head 'clojure.lang.IFn)
            delegates? (every? (fn [[_ [ithis & iargs] & ibody]]
                                 (and (= 1 (count ibody))
                                      (let [[head target & args] (first ibody)]
                                        ;; (.compare this a b), or
                                        ;; (. this (compare a b))
                                        (or (and (= '.compare head)
                                                 (= ithis target)
                                                 (= (vec iargs) (vec args)))
                                            (and (= '. head)
                                                 (= ithis target)
                                                 (sequential? (first args))
                                                 (= 'compare (first (first args)))
                                                 (= (vec iargs)
                                                    (vec (rest (first args)))))))))
                               invokes)]
        (if delegates?
          (let [self (gensym "cmp")]
            (list 'fn self [a b]
                  (subst-symbol (cons 'do body) this self)))
          (apply sci-reify form env specs)))

      :else
      (apply sci-reify form env specs))))

(alter-var-root #'clojure.core/reify (fn [_] present-reify))

;; ---------------------------------------------------------------------
;; clojure.data's two protocols.
;;
;; A JVM library type can implement clojure.data/EqualityPartition
;; and clojure.data/Diff in its deftype spec (datascript's DB does:
;; equality-partition is :datascript/db, diff-similar diffs its
;; indexes). Babashka's clojure.data is a partial port — it carries
;; the diff and equality-partition fns but never defines the
;; protocols — so the seat's deftype translation dies with
;; "Protocol not found: clojure.data/Diff" and the library cannot
;; load. (The same wall, filed against the nbb seat, had two named
;; Eve-side shapes: define the protocols in the seat's clojure.data,
;; or drop the groups in translation. This seat's clojure.data is a
;; SCI namespace, so the protocols can simply be defined, and the
;; type's own impls then register and dispatch as they do on the
;; JVM.)
;;
;; The definitions are clojure.data's own. One guard: bb's
;; equality-partition was a plain fn answering :sequential/:map/
;; :set/:atom for ordinary values, and defining the protocol
;; replaces it with a protocol fn — so the protocol is extended
;; with the JVM's default partitions (Object and nil :atom, Set
;; :set, List :sequential, Map :map; the JVM's array branch has no
;; seat counterpart and is not carried). bb's diff fn does not
;; route through these protocols (measured: it answers for maps
;; with the protocols defined and unextended), so Diff needs no
;; default extensions to keep it whole; types bring their own
;; diff-similar, as DB does.

;; ---------------------------------------------------------------------
;; A defrecord written with JVM interface groups.
;;
;; SCI's defrecord takes protocol implementations and Object —
;; "defrecord/deftype currently only support protocol
;; implementations, found: clojure.lang.IHashEq" (measured) — so a
;; vanilla record whose spec is written in JVM interfaces
;; (datascript's FilteredDB: IHashEq, IPersistentCollection,
;; ILookup, IKeywordLookup, Associative, beside its own protocols)
;; cannot load on this seat at all. Eve's bb types layer already
;; translates exactly those groups onto eve.core's protocols for
;; eve-deftypes (eve.types/bb-translate-protos, over
;; eve.deftype-proto.vocab's parse). So defrecord is re-rooted with
;; a presenter in the pattern above: when a record's spec heads
;; include a JVM interface name, the spec is parsed and translated
;; by that same translation (resolved at call time — the seat's
;; types adapter is loaded by then whenever Eve is), and the
;; translated spec goes to SCI's defrecord; a record with no JVM
;; interface group — the ordinary case, which loads today — passes
;; through argument-identical, as does any record when the types
;; adapter is not loaded.

(defonce ^:private sci-defrecord
  @#'clojure.core/defrecord)

(defn- record-count-home
  "A record spec's count methods, re-homed for translation: on the
   JVM count is an IPersistentCollection method, but the seat's
   translation table homes it under clojure.lang.Counted (its
   ICounted entry), and a count left under IPersistentCollection is
   dropped by the split. Returns parsed impls with any such count
   methods moved to a Counted group."
  [parsed]
  (let [counts (vec (mapcat (fn [{:keys [protocol methods]}]
                              (if (= 'clojure.lang.IPersistentCollection protocol)
                                (filter #(= 'count (:name %)) methods)
                                []))
                            parsed))]
    (if (empty? counts)
      parsed
      (let [moved (mapv (fn [{:keys [protocol methods] :as impl}]
                          (if (= 'clojure.lang.IPersistentCollection protocol)
                            (assoc impl :methods
                                   (vec (remove #(= 'count (:name %)) methods)))
                            impl))
                        parsed)]
        (if (some #(= 'clojure.lang.Counted (:protocol %)) moved)
          (mapv (fn [impl]
                  (if (= 'clojure.lang.Counted (:protocol impl))
                    (update impl :methods into counts)
                    impl))
                moved)
          (conj moved {:protocol 'clojure.lang.Counted :methods counts}))))))

(defn- record-object-group
  "The translated Object group of a record, cut to what a record on
   this seat can carry: SCI's defrecord rewrites an Object
   equals/hashCode in a record spec to sci.impl.records internals
   that do not resolve here (measured), so a record keeps the seat's
   native record equality and hash; a toString the user wrote is
   kept, the translation's synthesized one is not (a record prints
   as a record)."
  [translated user-tostring?]
  (vec (keep (fn [{:keys [protocol methods] :as impl}]
               (if (contains? '#{Object java.lang.Object} protocol)
                 (let [ts (filter #(= 'toString (:name %)) methods)]
                   (when (and user-tostring? (seq ts))
                     (assoc impl :methods (vec ts))))
                 impl))
             translated)))

(defn- jvm-group-spec?
  "True when a defrecord/deftype spec sequence names a JVM interface
   group (a clojure.lang.* or java.* head symbol) — the specs this
   seat's defrecord cannot take untranslated."
  [specs]
  (boolean
    (some (fn [s] (let [t (str s)]
                    (or (str/starts-with? t "clojure.lang.")
                        (str/starts-with? t "java."))))
          (filter symbol? specs))))

(defn- present-defrecord
  "clojure.core/defrecord, presented for SCI: a spec carrying JVM
   interface groups is translated onto eve.core's protocols by
   eve.types' translation, with the two record adjustments above;
   any other record passes through argument-identical. A spec
   whose only JVM group is clojure.lang.IDeref also passes
   through: a record carries deref natively on this seat
   (measured), and the translation would only move it off the
   name SCI's deref consults."
  [form env name fields & specs]
  (let [heads (filter symbol? specs)
        ideref-only? (and (seq heads)
                          (every? #(= 'clojure.lang.IDeref %) heads))
        jvm-group? (and (jvm-group-spec? specs) (not ideref-only?))
        _ (when jvm-group?
            ;; eve.types only: its ns form pulls eve.deftype-proto.vocab
            ;; with its refers; requiring vocab first leaves types'
            ;; refers unresolved (measured).
            (try (require 'eve.types)
                 (catch Throwable _ nil)))
        vocab (find-ns 'eve.deftype-proto.vocab)
        types (find-ns 'eve.types)
        parse (and jvm-group? vocab
                   (ns-resolve vocab 'parse-protocol-impls))
        translate (and parse types
                       (ns-resolve types 'bb-translate-protos))
        ->forms (and translate vocab
                     (ns-resolve vocab 'impls->forms))]
    (if ->forms
      (let [parsed (@parse specs)
            user-tostring? (boolean
                             (some (fn [{:keys [protocol methods]}]
                                     (and (contains? '#{Object java.lang.Object} protocol)
                                          (some #(= 'toString (:name %)) methods)))
                                   parsed))]
        (apply sci-defrecord form env name fields
               (@->forms (record-object-group
                           ;; a record is not a deftype: the map-mode
                           ;; groups bb-translate-protos keeps for a
                           ;; map-signature deftype (IPersistentMap
                           ;; and friends) are refused by SCI's
                           ;; defrecord path, so they come back off;
                           ;; the eve.core translations stay.
                           (vec (remove (fn [impl]
                                          (contains? '#{clojure.lang.IPersistentMap
                                                        clojure.lang.ILookup
                                                        clojure.lang.Associative
                                                        clojure.lang.IPersistentCollection
                                                        clojure.lang.Counted
                                                        clojure.lang.Seqable}
                                                     (:protocol impl)))
                                        (@translate (record-count-home parsed) name)))
                           user-tostring?))))
      (apply sci-defrecord form env name fields specs))))

(alter-var-root #'clojure.core/defrecord (fn [_] present-defrecord))

;; clojure.core/macroexpand-1, presented for one shape: a macro
;; that expands a defrecord by calling macroexpand-1 on it and
;; walking the result for the deftype* form (datascript's
;; defrecord-updatable is the measured case) cannot work on this
;; seat — the walk hands back a raw deftype* form, and a raw
;; deftype* with any :implements NPEs in SCI's analyzer here; only
;; the defrecord macro's own path can define such a type. When the
;; form being expanded is a defrecord whose spec carries JVM
;; interface groups, the expansion is therefore the defrecord form
;; itself under a do: the walk finds no deftype* to rewrite, and
;; analysis reaches the defrecord presenter above. Every other
;; form delegates to the seat's own macroexpand-1 unchanged.
(def sci-macroexpand-1 @#'clojure.core/macroexpand-1)

(defn- present-macroexpand-1 [form]
  (if (and (seq? form)
           (= 'defrecord (first form))
           (jvm-group-spec? (drop 3 form)))
    (list 'do form)
    (sci-macroexpand-1 form)))

(alter-var-root #'clojure.core/macroexpand-1
                (fn [_] present-macroexpand-1))

;; extend-clj.core/deftype-atom, presented when that namespace is
;; on the classpath. Its JVM expansion is a raw deftype* form,
;; which this seat's analyzer cannot take (the :method-counts
;; metadata on :implements that analyze-deftype* reads is attached
;; only by SCI's own deftype/defrecord macros). The presentation
;; keeps the macro's documented contract — an atom-like type built
;; from a deref-impl and a compare-and-set-impl — in the one shape
;; this seat gives all of at once: a defrecord. A record answers
;; keyword and clojure.core/get field access natively (the
;; library's (:atom conn) reads), carries clojure.lang.IDeref
;; natively (so @conn and (instance? IDeref conn) work), and its
;; positional ->Name factory is the record's own. What a record
;; cannot be is an IAtom, so the type's compare-and-set fn is
;; registered here and the presented clojure.core/swap! and
;; reset! (below, with the transient family) run their CAS loops
;; through it for registered types. Validator/watches on the type
;; itself are not presented — this seat has no IRef home for
;; them, and the measured use (datascript's Conn) never calls
;; them: its listeners live in the conn's state atom, reached
;; through the field the record exposes. Shapes without both
;; impl forms pass through to the library's own macro unchanged.
(def sci-deftype-atom nil)

(def deftype-atom-cas
  "Presented deftype-atom types: type -> compare-and-set fn
   (fn [this old new])."
  (atom {}))

(defn register-deftype-atom!
  "Called by the definition present-deftype-atom emits: record
   TYPE's compare-and-set fn for the presented swap!/reset!."
  [type cas-fn]
  (swap! deftype-atom-cas assoc type cas-fn)
  nil)

(defn- present-deftype-atom
  ;; the type-name parameter is not called `name`: that local
  ;; would shadow clojure.core/name in the body, and (name f) on a
  ;; field below would silently be a call of the type symbol,
  ;; yielding nil (measured: the emitted field access (nil this)).
  [form env type-name fields & methods]
  (let [impl-form? (fn [m] (and (seq? m)
                                (contains? '#{deref-impl compare-and-set-impl}
                                           (first m))))
        deref-impl (first (filter #(and (seq? %) (= 'deref-impl (first %))) methods))
        cas-impl (first (filter #(and (seq? %) (= 'compare-and-set-impl (first %))) methods))
        extra (vec (remove impl-form? methods))]
    (if (and deref-impl cas-impl)
      ;; The deref body goes into the record's deref method, where
      ;; field symbols bind as fields. The compare-and-set body
      ;; becomes a registered fn: field symbols there are field
      ;; accesses on the impl's this (a fn body has no field
      ;; binding), the impl's old/new symbols stay its arguments.
      (let [d-this (first (second deref-impl))
            d-body (cons 'do (drop 2 deref-impl))
            [c-this c-old c-new] (second cas-impl)
            c-body (cons 'do (drop 2 cas-impl))
            cas-body (reduce (fn [b f]
                               (subst-symbol b f
                                             (list (keyword (name f)) c-this)))
                             c-body fields)]
        ;; Built with plain list construction, not syntax-quote,
        ;; so the substituted bodies pass through untouched.
        (list 'do
              (list* 'defrecord type-name fields
                     'clojure.lang.IDeref
                     (list 'deref [d-this] d-body)
                     extra)
              (list 'eve.host-completion/register-deftype-atom! type-name
                    (list 'fn [c-this c-old c-new] cas-body))))
      (apply sci-deftype-atom form env type-name fields methods))))

(try
  ;; eve.core first: the defn presentation rewrites lookup calls
  ;; in library bodies to eve.core vars, so a library loaded from
  ;; here (extend-clj.core's macro bodies carry keyword lookups)
  ;; only analyzes once eve.core exists.
  (require 'eve.core)
  (require 'extend-clj.core)
  (when-let [v (ns-resolve 'extend-clj.core 'deftype-atom)]
    (alter-var-root #'sci-deftype-atom (fn [_] @v))
    (alter-var-root v (fn [_] present-deftype-atom)))
  (catch Throwable _ nil))

(require '[clojure.data])

(binding [*ns* (the-ns 'clojure.data)]
  (eval '(defprotocol EqualityPartition
           "Implementation detail. Subject to change."
           (equality-partition [x] "Implementation detail. Subject to change.")))
  (eval '(defprotocol Diff
           "Implementation detail. Subject to change."
           (diff-similar [a b] "Implementation detail. Subject to change."))))

(extend-protocol clojure.data/EqualityPartition
  nil
  (equality-partition [x] :atom)

  Object
  (equality-partition [x] :atom)

  java.util.Set
  (equality-partition [x] :set)

  java.util.List
  (equality-partition [x] :sequential)

  java.util.Map
  (equality-partition [x] :map))

;; clojure.pprint's logical-block surface, presented: bb's
;; clojure.pprint is a partial port without pprint-logical-block,
;; pprint-newline and print-length-loop (measured against its
;; ns-publics), and libraries that pprint their types
;; (datascript.pprint is the measured case) cannot load without
;; them. The presentations keep the observable contract — prefix
;; and suffix are written around the body, a :mandatory newline
;; breaks the line and the other kinds write a space, and the
;; length loop honors *print-length* — without the pretty
;; writer's column fitting, which this seat's pprint does not
;; implement for its own vars either.
(require '[clojure.pprint])

(intern 'clojure.pprint 'pprint-newline
        (fn [kind & _opts]
          (.write *out* (if (= :mandatory kind) "\n" " "))))

(intern 'clojure.pprint 'pprint-logical-block
        (fn [_form _env & args]
          (let [pairs (loop [acc [] xs args]
                        (if (keyword? (first xs))
                          (recur (conj acc (first xs) (second xs)) (drop 2 xs))
                          acc))
                opts (apply hash-map pairs)
                body (drop (count pairs) args)]
            `(do
               (when-let [prefix# ~(:prefix opts)]
                 (.write *out* prefix#))
               ~@body
               (when-let [suffix# ~(:suffix opts)]
                 (.write *out* suffix#))))))
(alter-meta! (find-var 'clojure.pprint/pprint-logical-block)
             assoc :macro true)

;; clojure.core's transient family, presented for Eve types:
;; SCI's transient / persistent! / conj! / assoc! / dissoc! /
;; pop! / disjoin! reach only host collections (a SciType argument
;; dies in a cast to the host transient interfaces, measured),
;; while an Eve type's transient surface lives on eve.core's
;; protocols — which is where the seat's own deftype translation
;; puts a spec's IEditableCollection / ITransient* groups (a DB's
;; asTransient, a sorted set's conj!/persistent!, datascript's
;; transact path is the measured consumer). Each presented fn
;; answers through eve.core's protocol when the value satisfies
;; it and delegates to the seat's own fn otherwise, so host
;; collections are untouched.
(def sci-transient @#'clojure.core/transient)
(def sci-persistent! @#'clojure.core/persistent!)
(def sci-conj! @#'clojure.core/conj!)
(def sci-assoc! @#'clojure.core/assoc!)
(def sci-dissoc! @#'clojure.core/dissoc!)
(def sci-pop! @#'clojure.core/pop!)

;; eve.core loads after this completion, so its protocols and
;; method fns are resolved at call time, not referenced.
(defn- eve-method
  "eve.core's protocol method fn named method-sym."
  [method-sym]
  (when-let [mv (ns-resolve 'eve.core method-sym)]
    @mv))

(def ^:private eve-inflight
  "The [op value] pairs a presented transient fn is currently
   answering through eve.core, on this thread's dynamic scope."
  (atom []))

(defn- eve-or-sci
  "A presented transient fn: eve.core's protocol dispatch answers
   the seat's own types — SciTypes through their registrations,
   Eve's collections (an Eve map is a babashka.impl.SciMap here)
   through theirs — so the call is tried there first. Two values
   must not take that path: nil (the seat's own error is the
   answer), and a value already in flight for the same op —
   eve.core's Object default for these protocols delegates back
   to clojure.core's fn, so a host collection (which only the
   default would answer) would cycle presenter → default →
   presenter forever (measured: (transient a-host-vector) never
   returns). The re-entrant call goes to the seat's own fn, which
   is the host collection's correct path anyway; a genuine Eve
   impl calling the same op on a DIFFERENT value (a DB transient
   transienting its indexes) is unaffected. A value the dispatch
   refuses outright falls to the seat's fn the same way."
  [method-sym sci-fn op]
  (fn [& args]
    ;; (nth args 0), not (first args): presented-first below IS
    ;; an eve-or-sci fn, and a body that called first would
    ;; re-enter itself before the guard binds (measured — the
    ;; completion stopped loading, at macroexpand of a later
    ;; defn whose presenter called first). nth is not presented.
    (let [x (nth args 0)
          inflight @eve-inflight]
      (if (and (some? x)
               (not (some (fn [[o v]] (and (= o op) (identical? v x)))
                          inflight))
               (eve-method method-sym))
        (do (swap! eve-inflight conj [op x])
            (try (apply (eve-method method-sym) args)
                 ;; An ex-info is the impl's own answer (Eve's domain-
                 ;; construction contract, a type's refusal) — never a
                 ;; dispatch refusal — so it propagates: re-dispatching
                 ;; it to the seat's fn turned the JVM's "allocated in a
                 ;; different slab domain" refusal into SCI's cast error
                 ;; "SciMap cannot be cast to IEditableCollection"
                 ;; (fix-for-release B2, measured on (d/db-with @a tx)).
                 (catch clojure.lang.ExceptionInfo e (throw e))
                 (catch Throwable _ (apply sci-fn args))
                 (finally (swap! eve-inflight
                                 (fn [pairs]
                                   (vec (remove (fn [[o v]]
                                                  (and (= o op) (identical? v x)))
                                                pairs)))))))
        (apply sci-fn args)))))

(alter-var-root #'clojure.core/transient
                (fn [_] (eve-or-sci '-as-transient sci-transient :transient)))
(alter-var-root #'clojure.core/persistent!
                (fn [_] (eve-or-sci '-persistent! sci-persistent! :persistent!)))
(alter-var-root #'clojure.core/conj!
                (fn [_] (eve-or-sci '-conj! sci-conj! :conj!)))
(alter-var-root #'clojure.core/assoc!
                (fn [_] (eve-or-sci '-assoc! sci-assoc! :assoc!)))
(alter-var-root #'clojure.core/dissoc!
                (fn [_] (eve-or-sci '-dissoc! sci-dissoc! :dissoc!)))
(alter-var-root #'clojure.core/pop!
                (fn [_] (eve-or-sci '-pop! sci-pop! :pop!)))

;; clojure.core/swap! and reset!, presented for the deftype-atom
;; types above: a registered type's CAS loop runs through its
;; registered compare-and-set fn (its deref is the record's own);
;; every other value takes the seat's own fn untouched. (Direct
;; calls to swap!/reset! see a re-rooted var on this seat,
;; measured — unlike get/inc, whose compiled fast path does not.)
(def sci-swap! @#'clojure.core/swap!)
(def sci-reset! @#'clojure.core/reset!)

(alter-var-root #'clojure.core/swap!
                (fn [_] (fn [a f & args]
                          (if-let [cas (get @deftype-atom-cas (type a))]
                            (loop []
                              (let [old @a
                                    new (apply f old args)]
                                (if (cas a old new)
                                  new
                                  (recur))))
                            (apply sci-swap! a f args)))))
(alter-var-root #'clojure.core/reset!
                (fn [_] (fn [a v]
                          (if-let [cas (get @deftype-atom-cas (type a))]
                            (loop []
                              (if (cas a @a v)
                                v
                                (recur)))
                            (sci-reset! a v)))))

;; clojure.core's seq family, presented for this seat's own
;; types. The seat's seq/first/rest/next are host fns: they
;; cannot create an ISeq from, or walk, a SciType (a sorted
;; set's Iter, a Datom), and eve.core's own -rest on such a type
;; calls clojure.core/next on it (measured in the sorted set's
;; more). The presented fns route a value of one of the seat's
;; types through eve.core's protocols and hand back host seqs:
;; an Eve seq that is not already a host ISeq is viewed through
;; a host lazy seq over the same elements, so host consumers
;; (count, into, sort, reduce) can walk it. Host values take the
;; seat's own fns untouched. count, nth and get cannot be
;; presented this way — the analyzer compiles direct calls to
;; them past the var (measured), and SCI's deftype refuses the
;; host Counted/Indexed interfaces — so those stay the seat's
;; named limit for non-map types; map-signature types answer
;; them through SCI's map mode (above, in eve.types).
(def sci-seq @#'clojure.core/seq)
(def sci-first @#'clojure.core/first)
(def sci-rest @#'clojure.core/rest)
(def sci-cons @#'clojure.core/cons)

(defn- eve-typed?
  "x's type is one of this seat's own: a SciType, or a SciMap
   (Eve's map representation here, map-mode deftypes included).
   Keyed on the host class: SCI's type of a SciType instance is
   the type object itself, whose str is the type's name."
  [x]
  (let [s (str (class x))]
    (or (= s "class sci.impl.deftype.SciType")
        (= s "class babashka.impl.SciMap"))))

(defn- host-seq-view
  "The Eve seq s as a host lazy seq over the same elements,
   walked through eve.core's -first/-rest."
  [s]
  (let [f (eve-method '-first)
        rf (eve-method '-rest)]
    ((fn view [s]
       (lazy-seq
         (cons (f s)
               (let [r (rf s)]
                 (cond (nil? r) nil
                       (instance? clojure.lang.ISeq r) r
                       :else (view r))))))
     s)))

(defn- presented-seq
  [x]
  (cond
    (nil? x) nil
    (eve-typed? x)
    (let [s (try ((eve-method '-seq) x)
                 (catch Throwable _ ::sci))]
      (cond (identical? s ::sci) (sci-seq x)
            (nil? s) nil
            (instance? clojure.lang.ISeq s) s
            :else (host-seq-view s)))
    :else (sci-seq x)))

(alter-var-root #'clojure.core/seq (fn [_] presented-seq))

;; first/rest/next on the seat's own types go through eve.core's
;; -first/-next directly, NOT through presented-seq's host view:
;; a sorted set Iter's own more is (or (next this) ()), so a
;; presented next that walked the view (whose -rest reaches that
;; same more) recursed without end (measured). -next on such a
;; type is the type's own step — a fresh Iter, or nil at the
;; end — which is exactly clojure.core/next's contract; rest is
;; (or (next x) ()). Host values keep the seat's own fns.
(def sci-next @#'clojure.core/next)
(def sci-last @#'clojure.core/last)

;; first/next route through -seq first, then dispatch on the
;; seq's kind: -seq is the one protocol every Eve collection
;; registers, while -first/-next are not (an Eve map registers
;; -seq but not -next — calling -next on it lands on eve.core's
;; Object default, which calls clojure.core/next, which is this
;; very presenter: a StackOverflowError, measured in
;; eve.thread-domain-test's network-rung upgrade row and again
;; at Eve's own load). A host seq result (a map's entry seq, a
;; DB's datom seq) takes the seat's fn; a seq object of the
;; seat's own types (an Iter, an RSeq — -seq returns the object
;; itself) takes its own -first/-next through the eve-or-sci
;; inflight guard, which is what keeps a PSS Iter's more (it
;; calls (next this)) terminating.
(defn- seq-of [x]
  (try ((eve-method '-seq) x)
       (catch Throwable _ ::sci)))

(defn- presented-first
  [x]
  (cond (nil? x) nil
        (eve-typed? x)
        (let [s (seq-of x)]
          (cond (identical? s ::sci) (sci-first x)
                (nil? s) nil
                (instance? clojure.lang.ISeq s) (sci-first s)
                :else ((eve-or-sci '-first sci-first :first) s)))
        :else (sci-first x)))

(defn- presented-next
  [x]
  (cond (nil? x) nil
        (eve-typed? x)
        (let [s (seq-of x)]
          (cond (identical? s ::sci) (sci-next x)
                (nil? s) nil
                (instance? clojure.lang.ISeq s) (sci-next s)
                :else ((eve-or-sci '-next sci-next :next) s)))
        :else (sci-next x)))

(alter-var-root #'clojure.core/first (fn [_] presented-first))
(alter-var-root #'clojure.core/next (fn [_] presented-next))
(alter-var-root #'clojure.core/rest
                (fn [_] (fn [x]
                          (cond (nil? x) ()
                                (eve-typed? x) (or (presented-next x) ())
                                :else (sci-rest x)))))
(alter-var-root #'clojure.core/second
                (fn [_] (fn [x] (presented-first (presented-next x)))))
(alter-var-root #'clojure.core/last
                (fn [_] (fn [x] (sci-last (presented-seq x)))))
(alter-var-root #'clojure.core/empty?
                (fn [_] (fn [x] (not (presented-seq x)))))
;; clojure.core's meta / with-meta, presented for the seat's own
;; types: SCI's with-meta on a SciType / SciMap keeps the map on
;; SCI's side of the value and never calls the type's IObj group,
;; so the type's own meta (a field its withMeta sets, as datascript's
;; DB keeps :meta) stays nil, and every rebuild of the value from
;; its fields drops it — (d/with (with-meta db m) tx) answered nil
;; meta on bb, m on the JVM (fix-for-release B2, measured). The
;; type's IMeta/IWithMeta groups land on eve.core's -meta /
;; -with-meta here, so those answer; a type without them takes
;; eve.core's Object default, which re-enters these fns and lands
;; on the seat's own (eve-or-sci's in-flight guard). Host values
;; take the seat's fns untouched.
(def sci-meta @#'clojure.core/meta)
(def sci-with-meta @#'clojure.core/with-meta)
(def sci-type @#'clojure.core/type)
(def sci-instance? @#'clojure.core/instance?)

;; A map-mode deftype's instance (present-deftype, top of this ns)
;; is a babashka.impl.SciMap: the type it was built from is stamped
;; under :type in the instance's SCI-side meta by the presented
;; factory (Clojure's convention for a meta-carried type), never
;; shown by the presented meta, and carried across with-meta. Only
;; map-mode instances (scimap? and stamped by a presented factory)
;; carry it. Printing: SCI keeps a type's own print-method in the
;; type's meta (:sci.impl/print-method — what SCI's defmethod expands
;; to for a sci.lang.Type, measured on bb 1.13.226), and the seat's
;; printer consults it for a SciType only; a SciMap dispatches by its
;; class to the map printer, so an Entity printed as a plain map
;; without :db/id (fix-for-release E4 residual). The SciMap-class
;; method installed below (fix-for-release-2 3.4a) reaches a stamped
;; instance's own print-method and hands every other SciMap — Eve's
;; maps included — to the map printer, as before.
(defn- scimap? [x]
  (and (some? x) (= "babashka.impl.SciMap" (.getName ^Class (class x)))))

(defn- map-mode-type [x]
  (when (scimap? x) (get (sci-meta x) :type)))

(defn present-map-mode-type!
  "Called by a presented map-mode deftype's expansion: the factory
   ->T stamps T on every instance it builds. Answers T, the value
   the deftype form answers."
  [factory-var t]
  (let [f @factory-var]
    (alter-var-root factory-var
                    (fn [_] (fn [& args]
                              (let [x (apply f args)]
                                (sci-with-meta x (assoc (sci-meta x) :type t)))))))
  t)

(let [^clojure.lang.MultiFn pm print-method]
  (.addMethod pm (Class/forName "babashka.impl.SciMap")
              (fn [x w]
                (if-let [own (some-> (map-mode-type x) sci-meta :sci.impl/print-method)]
                  (own x w)
                  ((.getMethod pm clojure.lang.IPersistentMap) x w)))))

(defn- user-meta
  "Meta as the program sees it: the stamp removed."
  [m]
  (if (and (map? m) (contains? m :type))
    (let [m' (dissoc m :type)] (when (seq m') m'))
    m))

(let [eve-meta (eve-or-sci '-meta sci-meta :meta)
      eve-with-meta (eve-or-sci '-with-meta sci-with-meta :with-meta)]
  (alter-var-root #'clojure.core/meta
                  (fn [_] (fn [x] (if (eve-typed? x) (user-meta (eve-meta x)) (sci-meta x)))))
  (alter-var-root #'clojure.core/with-meta
                  (fn [_] (fn [x m]
                            (if (eve-typed? x)
                              (if-let [t (map-mode-type x)]
                                (eve-with-meta x (assoc m :type t))
                                (eve-with-meta x m))
                              (sci-with-meta x m))))))

(alter-var-root #'clojure.core/type
                (fn [_] (fn [x] (or (map-mode-type x) (sci-type x)))))
(alter-var-root #'clojure.core/instance?
                (fn [_] (fn [c x]
                          (if-let [t (map-mode-type x)]
                            (or (identical? c t) (sci-instance? c x))
                            (sci-instance? c x)))))

;; A tail that is already a host seq passes to the seat's cons
;; untouched: seq-ing a LazySeq tail forces a node, and bodies
;; that build lazy seqs with cons (eve.core's own host-seq is
;; the measured case) would then force the next node
;; synchronously inside the first — the walk becomes a stack
;; recursion and overflows on large collections (eve.rrb-vector
;; test-assoc). Host cons takes an ISeq tail as-is. The
;; pass-through lives HERE, not in presented-seq: a (seq? x)
;; branch in presented-seq made Eve's own namespace load spin
;; (measured; the site inside Eve's load was not isolated).
(alter-var-root #'clojure.core/cons
                (fn [_] (fn [x s] (sci-cons x (if (seq? s) s (presented-seq s))))))

;; The seq-CONSUMING core fns, presented the same way: the
;; seat's own are host fns whose internals seq their argument
;; host-side (into/transduce reach clojure.core.protocols'
;; seq-reduce, which calls the host seq), so a SciType argument
;; dies there no matter what clojure.core/seq says. Each
;; presented fn seqs its collection argument through
;; presented-seq first, then delegates to the seat's own fn —
;; for a host collection that is just seq-normalization, the
;; same elements in the same order. The set is the port's
;; censused seq-consumers (map, reduce, into, filter, concat,
;; remove, mapcat, sort, take, drop, group-by, transduce,
;; partition-by, keep and their kin).
(def sci-concat @#'clojure.core/concat)
(def sci-map @#'clojure.core/map)

(defn- seq-last
  "Wrap f so its last argument goes through presented-seq. The
   plumbing uses the captured originals: by the time any wrapped
   fn is called, concat and map are themselves presented, and
   routing through them would recurse without end (measured)."
  [f]
  (fn [& args]
    (apply f (sci-concat (butlast args) [(presented-seq (last args))]))))

(defn- seq-args
  "Wrap f so every argument goes through presented-seq."
  [f]
  (fn [& args]
    (apply f (sci-map presented-seq args))))

(defn- presented-fn
  "A keyword used as a function is re-presented as a lookup closure
   over eve.core/get. The captured host originals apply their
   function argument host-side, and on this seat both of the
   host-side spellings miss a SciType's fields (a port deftype
   under SCI -- a Datom is the measured case): keyword INVOCATION
   yields nil in every spelling (bound keyword called directly,
   apply of a literal, apply of a bound keyword), and plain
   clojure.core/get yields nil too -- the get/keyword rewriting
   above routes later namespaces' lookups through eve.core/get
   precisely because of that, but this namespace's own forms are
   not rewritten. eve.core/get resolves the field (measured:
   (map :v datoms) came back [nil nil nil] while
   (map (fn [x] (:v x)) datoms) -- a rewritten form -- was
   exact). The closure carries the one- and two-argument
   (not-found) keyword shapes. Other functions pass through
   untouched: host fns and SCI closures already apply correctly
   host-side."
  [f]
  (if (keyword? f)
    (let [g (if-let [v (find-var 'eve.core/get)] @v get)]
      (fn
        ([x] (g x f))
        ([x nf] (g x f nf))))
    f))

(defn- xf-last
  "Like seq-last, but a single argument is the transducer arity
   (map's (map f), take's (take n)): it passes through untouched
   — seq'ing it would seq the fn itself (measured: (map vec) in
   the query engine). The function-first members of this family
   (filter, remove, keep, mapcat, partition-by, take-while,
   drop-while) also get presented-fn on their first argument;
   take/drop's first argument is a count, which presented-fn
   passes through."
  [f]
  (fn [& args]
    (let [args (cons (presented-fn (first args)) (rest args))]
      (if (= 1 (count args))
        (apply f args)
        (apply f (sci-concat (butlast args) [(presented-seq (last args))]))))))

(defn- xf-map
  "clojure.core/map: one argument is the transducer arity; with
   collections, every argument after f is seq'd. f itself goes
   through presented-fn in both arities."
  [f]
  (fn [& args]
    (if (= 1 (count args))
      (apply f [(presented-fn (first args))])
      (apply f (presented-fn (first args)) (sci-map presented-seq (rest args))))))

(defn- seq-last-fn
  "Like seq-last, for the function-first seq consumers
   (sort-by's keyfn, group-by's f): the first argument also
   goes through presented-fn."
  [f]
  (fn [& args]
    (apply f (presented-fn (first args))
           (sci-concat (butlast (rest args)) [(presented-seq (last args))]))))

(defn- xf-coll
  "distinct/dedupe: zero arguments is the transducer arity."
  [f]
  (fn [& args]
    (if (zero? (count args))
      (apply f args)
      (apply f [(presented-seq (first args))]))))

;; ->Eduction captures its collection raw, and the host reduce
;; an eduction performs later seqs that collection host-side —
;; the query engine's -collect-tuples builds eductions over a
;; relation's tuples, which on this seat can be a sorted set.
;; The presented constructor views the collection first.
(def sci-->eduction @#'clojure.core/->Eduction)

(alter-var-root #'clojure.core/->Eduction
                (fn [_] (fn [xform coll]
                          (sci-->eduction xform (presented-seq coll)))))

;; rseq, presented for the seat's own types (fix-for-release
;; B2): a sorted-set slice is a SciType whose Reversible group the
;; seat's translation homes on eve.core's -rseq, while SCI's rseq
;; casts its argument to clojure.lang.Reversible host-side —
;; persistent-sorted-set's rslice (some-> slice rseq), and with it
;; datascript's -rseek-datoms, died in that cast on this seat
;; (measured). A type without the group re-enters and lands on the
;; seat's own fn (eve-or-sci's in-flight guard).
(def sci-rseq @#'clojure.core/rseq)
(let [eve-rseq (eve-or-sci '-rseq sci-rseq :rseq)]
  (alter-var-root #'clojure.core/rseq
                  (fn [_] (fn [x] (if (eve-typed? x) (eve-rseq x) (sci-rseq x))))))

;; not-empty seqs its argument (entity lookup calls it on an
;; index search result, which on this seat is a sorted-set
;; slice).
(alter-var-root #'clojure.core/not-empty
                (fn [_] (fn [x] (when (presented-seq x) x))))

;; clojure.core/realized? is (.isRealized ^clojure.lang.IPending x) and
;; sci's deftype will not carry IPending (the types adapter homes it on
;; this protocol). Host delays, promises, futures and lazy seqs stay on
;; the original path; an eve.thread promise or future — the measured
;; case, DataScript users calling realized? on transact's result —
;; answers through -realized?, which is isRealized / d/done?.
(defprotocol IPending
  (-realized? [o]))

(def ^:private sci-realized? @#'clojure.core/realized?)
(alter-var-root #'clojure.core/realized?
                (fn [_]
                  (fn [x]
                    (if (instance? clojure.lang.IPending x)
                      (sci-realized? x)
                      (-realized? x)))))

;; A hand-rolled loop, not doseq: while presented-seq carried
;; a host-seq pass-through branch (since moved to presented
;; cons), SCI's doseq (whose chunked expansion walks the pairs
;; through the presented seq/first/next) died derefing its
;; vars at load — a named SCI quirk, mechanism not isolated.
;; The loop is the same iteration and is verified in this
;; configuration, so it stays.
(loop [ps (seq [[#'clojure.core/map xf-map]
                ;; mapv/filterv (fix-for-release B2): the eager
                ;; spellings of map/filter, measured missing —
                ;; (mapv :e (d/seek-datoms ...)) answered nils on
                ;; this seat where (map :e ...) was exact. Same
                ;; wraps: f through presented-fn, colls viewed.
                [#'clojure.core/mapv xf-map]
                [#'clojure.core/filterv xf-last]
                [#'clojure.core/mapcat xf-last]
                [#'clojure.core/filter xf-last]
                [#'clojure.core/remove xf-last]
                [#'clojure.core/keep xf-last]
                [#'clojure.core/concat seq-args]
                [#'clojure.core/reduce seq-last]
                [#'clojure.core/into seq-last]
                [#'clojure.core/transduce seq-last]
                [#'clojure.core/sort seq-last]
                [#'clojure.core/sort-by seq-last-fn]
                [#'clojure.core/group-by seq-last-fn]
                [#'clojure.core/take xf-last]
                [#'clojure.core/drop xf-last]
                [#'clojure.core/take-while xf-last]
                [#'clojure.core/drop-while xf-last]
                [#'clojure.core/partition-by xf-last]
                [#'clojure.core/sequence seq-last]
                [#'clojure.core/eduction seq-last]
                [#'clojure.core/distinct xf-coll]
                [#'clojure.core/dedupe xf-coll]
                [#'clojure.core/doall seq-last]
                [#'clojure.core/dorun seq-last]])]
  (when ps
    (let [[v wrap] (first ps)
          orig @v]
      (alter-var-root v (fn [_] (wrap orig)))
      (recur (next ps)))))

(intern 'clojure.pprint 'print-length-loop
        (fn [_form _env bindings & body]
          (let [[sym val] bindings
                ctr (gensym "__print_length_")
                body (clojure.walk/postwalk
                       (fn [f]
                         (if (and (seq? f) (= 'recur (first f)))
                           (list* 'recur (concat (rest f) [(list 'inc ctr)]))
                           f))
                       body)]
            `(loop [~sym ~val ~ctr 0]
               (when (and ~sym
                          (or (nil? *print-length*)
                              (< ~ctr *print-length*)))
                 ~@body)))))
(alter-meta! (find-var 'clojure.pprint/print-length-loop)
             assoc :macro true)
