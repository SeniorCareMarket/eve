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
  (:refer-clojure :exclude [deftype extend-type atom aget hash-map hash-set
                            count nth get seq conj empty contains? disj meta
                            with-meta reduce])
  ;; the api table names its values by these aliases (adapters/core/
  ;; cljs-compiler's eve/alpha.cljs requires the same ones)
  (:require [eve.deftype]
            [eve.core :as ec]
            [eve.atom :as atm]
            [eve.array :as arr]
            [eve.map :as emap]
            [eve.set :as eset]
            [eve.vec]
            [eve.list]
            [eve.process-slab :as ps]))

(defmacro deftype
  "Define a SAB-backed type. See eve.deftype/eve-deftype."
  [& args]
  `(eve.deftype/eve-deftype ~@args))

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
    [close-atom-domain! atm/close-atom-domain!]
    [shared-atom? atm/shared-atom?]
    ;; Data structure API
    [hash-map emap/hash-map]
    [empty-hash-map emap/empty-hash-map]
    [hash-set eset/hash-set]
    [empty-hash-set eset/empty-hash-set]
    ;; Typed array API (eve-array after the other eve.array names: basilisp
    ;; reaches eve.array through the module's munged name, eve_array, which
    ;; (def eve-array ...) then rebinds in this module)
    [aget arr/aget]
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
