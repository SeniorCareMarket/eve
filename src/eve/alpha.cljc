(ns eve.alpha
  "Eve public API, the same on every host: macros for eve types, the
   cross-process atom, eve's collections and typed arrays, and eve.core's
   collection vocabulary.
   A host's own extras are its adapters' namespaces (on ClojureScript and nbb:
   eve.typed-view's get-typed-view, eve.sab's sab-transfer-data).

   Columnar subsystems are in separate namespaces:
     eve.alpha.ds      — Dataset (columnar data frames)
     eve.alpha.tensor  — Tensor (N-dimensional arrays)
     eve.alpha.col     — Columnar ops (element-wise + index-space)"
  (:refer-clojure :exclude [deftype extend-type atom aget aset alength
                            aclone make-array hash-map hash-set
                            count nth get seq conj empty contains? disj meta
                            with-meta reduce])
  ;; the api table names its values by these aliases (adapters/core/
  ;; cljs-compiler's eve/alpha.cljs requires the same ones)
  (:require [eve.deftype]
            [eve.core :as ec]
            [eve.atom :as atm]
            [eve.array :as arr]
            [eve.deftype.array :as darr]
            [eve.map :as emap]
            [eve.set :as eset]
            [eve.vec]
            [eve.list]
            [eve.process-slab :as ps]
            ;; the flat delivery (deref, eve->clj's kind arm): part of the
            ;; value model on every host (EVE-FIX-PLAN §0), loaded with alpha.
            ;; basilisp has no flat layer yet (eve.flat-io's reads and
            ;; eve.flat.chunk's handles have no :lpy arms): there, values the
            ;; flat layer would deliver are refused by eve->clj, never leaked.
            #?@(:lpy [] :default [[eve.flat.freeze]])))

(defmacro deftype
  "Define a SAB-backed type. See eve.deftype/deftype."
  [& args]
  `(eve.deftype/deftype ~@args))

(defmacro extend-type
  "Extend protocols on an existing eve/deftype."
  [& args]
  `(eve.deftype/eve-extend-type ~@args))

(defmacro $
  "A read region (eve.read-region): body runs with every eve atom deref'd in it answering its stored value as it is —
   lazy and slab-backed, nothing materialized but what body touches — and every read held (a domain's epoch pinned,
   a network atom's lease kept) until the outermost $ exits. body's result is materialized as plain data before the
   holds are let go, so a large atom's branch is reached without the rest of the atom being read:
     (e/$ (e/get (e/get @users :by-id) 42))
   A $ inside another joins it."
  [& body]
  `(eve.atom/$* (fn [] ~@body)))

;; The API: each name and what it is (a ClojureScript compiler's eve.alpha,
;; adapters/core/cljs-compiler, defines the same names through api-defs).
(def ^:private api
  '[;; Atom API
    [atom atm/atom]
    [atom-domain atm/atom-domain]
    [persistent-atom-domain atm/persistent-atom-domain]
    [close-atom-domain! atm/close-atom-domain!]
    [shared-atom? atm/shared-atom?]
    ;; The value model (docs/ds-in-eve/EVE-FIX-PLAN.md §0): flat outside an
    ;; atom, persistent inside; the atom confers identity
    [persistent? atm/persistent?]
    [same? atm/same?]
    [eve->clj atm/eve->clj]
    ;; Data structure API
    [hash-map emap/hash-map]
    [empty-hash-map emap/empty-hash-map]
    [hash-set eset/hash-set]
    [empty-hash-set eset/empty-hash-set]
    ;; Value array API: an array of eve values, any value in a slot —
    ;; the array a deftype's fields are stored as, and a standalone
    ;; structure in its own right. aget dispatches: a value array
    ;; reads its slot, anything else is the host's own aget.
    [value-array? darr/value-array?]
    [make-array darr/make-array]
    [aget darr/aget]
    [aset darr/aset]
    [alength darr/alength]
    [aclone darr/aclone]
    [array-copy darr/array-copy]
    [array-seq darr/array-seq]
    [array-chunk darr/array-chunk]
    ;; Typed array API (eve-array after the other eve.array names: basilisp
    ;; reaches eve.array through the module's munged name, eve_array, which
    ;; (def eve-array ...) then rebinds in this module). The typed arrays'
    ;; own aget stays in eve.array — alpha's aget above is the value
    ;; array's.
    [aset! arr/aset!]
    [eve-array arr/eve-array]
    ;; Collection vocabulary (eve.core): what every EVE value answers on every
    ;; host — clojure.core's own where the host's types carry its collection
    ;; interfaces (JVM, ClojureScript, nbb), EVE's protocols where they cannot
    ;; (babashka). See doc/data-structures.md#hosts.
    [count ec/count]
    [nth ec/nth]
    [get ec/get]
    [seq ec/seq]
    [conj ec/conj]
    [empty ec/empty]
    [contains? ec/contains?]
    [disj ec/disj]
    [meta ec/meta]
    [with-meta ec/with-meta]
    [invoke ec/invoke]
    [reduce ec/reduce]])

(defmacro api-defs
  "The API's defs, (def name value) for each entry of api."
  []
  (cons 'do (map (fn [[n v]] (list 'def n v)) api)))

(api-defs)

;; this process's slab readied as eve.alpha loads (a promise of the module
;; slab's WASM upgrades on ClojureScript and nbb; nil elsewhere)
(defonce ^:private init-promise (ps/init!))
