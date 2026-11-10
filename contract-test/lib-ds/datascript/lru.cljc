(ns ^:no-doc datascript.lru
  ;; PORT DELTA (F-LPY-LRU-CTOR): on basilisp the LRU type is written with
  ;; eve.deftype's deftype macro (see the :lpy arm below), which
  ;; datascript.db also uses for Datom/DB on this seat.
  ;; PORT DELTA (F-CLJE-LRU-CTOR): same arrangement on clojerl — see the
  ;; :clje arm below.
  ;; PORT DELTA (F-CLJE-VOLATILE): clojerl's clojure.core has no
  ;; volatile!/vswap! (stage-1/2 logs: lru.cljc:132 unresolvable even
  ;; with eve.host/eve.atom loaded first — nothing on the seat interns
  ;; them into clojure.core). Eve's seat answer is eve.host's (beam
  ;; host.clje: volatile! is a process-val cell, vswap! its macro), so
  ;; under :clje this ns refers them from eve.host — the same
  ;; per-namespace remedy Eve's own clje lane used for host names.
  #?(:lpy  (:require [eve.deftype :refer [deftype]])
     :clje (:require [eve.deftype :refer [deftype]]
                     [eve.host :refer [volatile! vswap!]])))

(declare assoc-lru cleanup-lru)

#?(:cljs
   (deftype LRU [key-value gen-key key-gen gen limit]
     IAssociative
     (-assoc [this k v] (assoc-lru this k v))
     (-contains-key? [_ k] (-contains-key? key-value k))
     ILookup
     (-lookup [_ k]    (-lookup key-value k nil))
     (-lookup [_ k nf] (-lookup key-value k nf))
     IPrintWithWriter
     (-pr-writer [_ writer opts]
       (-pr-writer key-value writer opts)))
   ;; PORT DELTA (F-LPY-LRU-CTOR): vanilla has no :lpy arm — on basilisp
   ;; neither the :cljs nor the :clj deftype reads, so LRU does not exist
   ;; and (LRU. ...) in assoc-lru is unresolvable (CompilerException,
   ;; :phase :macroexpansion). A plain basilisp deftype cannot name the
   ;; cljs protocol vars IAssociative/ILookup either (they do not exist
   ;; on this seat). The port's house form for a host type here is the
   ;; JVM-interface spelling (as datascript.db's Datom/DB use): the
   ;; :clj arm's interfaces and methods, which Eve's basilisp type
   ;; layer (eve.types) translates onto basilisp.lang.interfaces at
   ;; expansion — clojure.lang.Associative -> IAssociative (whose
   ;; abstract assoc the layer dispatches by arity) and
   ;; clojure.lang.ILookup -> ILookup (val_at). So the :lpy arm is the
   ;; :clj arm verbatim.
   :lpy
   ;; basilisp.lang.interfaces.IAssociative extends IPersistentCollection
   ;; -> ISeqable, whose abstract seq the layer would otherwise leave
   ;; unimplemented (Python ABC: the class cannot instantiate). JVM's
   ;; Associative does not extend Seqable; seq reads over the entries
   ;; (JVM entry seq of a map-like) — the cache is used via get/assoc
   ;; only, so this member is definitional, not a behavior surface.
   (deftype LRU [key-value gen-key key-gen gen limit]
     clojure.lang.ILookup
     (valAt [_ k]           (get key-value k))
     (valAt [_ k not-found] (get key-value k not-found))
     clojure.lang.Associative
     (containsKey [_ k] (contains? key-value k))
     (entryAt [_ k]     (find key-value k))
     (assoc [this k v]  (assoc-lru this k v))
     clojure.lang.Seqable
     (seq [_] (seq key-value)))
   ;; PORT DELTA (F-CLJE-LRU-CTOR): vanilla has no :clje arm — on
   ;; clojerl neither the :cljs nor the :clj deftype reads, so LRU does
   ;; not exist and the ^LRU hint in assoc-lru is unresolvable
   ;; (clje-ds-fix13.log on lane-m/eve-clje-unchecked). Like the :lpy
   ;; arm above, the :clje arm is written with eve.deftype's deftype
   ;; (referred in the ns) in the :clj arm's JVM-interface spelling:
   ;; Eve's beam type layer (eve.types) translates
   ;; clojure.lang.ILookup -> clojerl.ILookup (valAt is get) and
   ;; clojure.lang.Associative -> clojerl.IAssociative
   ;; (containsKey/entryAt/assoc) at expansion. No Seqable member,
   ;; unlike :lpy: clojerl's IAssociative does not extend ISeqable, so
   ;; nothing forces one, and the :clj arm has none either — the cache
   ;; is used via get/assoc only. So the :clje arm is the :lpy arm
   ;; minus its seq member.
   :clje
   (deftype LRU [key-value gen-key key-gen gen limit]
     clojure.lang.ILookup
     (valAt [_ k]           (get key-value k))
     (valAt [_ k not-found] (get key-value k not-found))
     clojure.lang.Associative
     (containsKey [_ k] (contains? key-value k))
     (entryAt [_ k]     (find key-value k))
     (assoc [this k v]  (assoc-lru this k v)))
   :clj
   (deftype LRU [^clojure.lang.Associative key-value gen-key key-gen gen limit]
     clojure.lang.ILookup
     (valAt [_ k]           (.valAt key-value k))
     (valAt [_ k not-found] (.valAt key-value k not-found))
     clojure.lang.Associative
     (containsKey [_ k] (.containsKey key-value k))
     (entryAt [_ k]     (.entryAt key-value k))
     (assoc [this k v]  (assoc-lru this k v)))
   ;; PORT DELTA (F-CLJR-LRU-CTOR): vanilla has no :cljr arm -- on
   ;; ClojureCLR neither the :cljs nor the :clj deftype reads (:cljr is
   ;; the seat's only reader feature), so LRU does not exist and the
   ;; ^LRU hint in assoc-lru is unresolvable ("Unable to resolve
   ;; typename: LRU", compiled-clr smoke 2026-11-08). ClojureCLR's
   ;; clojure.lang.ILookup / clojure.lang.Associative are native .NET
   ;; interfaces in Clojure.dll and the seat's own clojure.core deftype
   ;; names them directly, so the :cljr arm is the :clj arm verbatim.
   :cljr
   (deftype LRU [^clojure.lang.Associative key-value gen-key key-gen gen limit]
     clojure.lang.ILookup
     (valAt [_ k]           (.valAt key-value k))
     (valAt [_ k not-found] (.valAt key-value k not-found))
     clojure.lang.Associative
     (containsKey [_ k] (.containsKey key-value k))
     (entryAt [_ k]     (.entryAt key-value k))
     (assoc [this k v]  (assoc-lru this k v))))

(defn assoc-lru [^LRU lru k v]
  (let [key-value (.-key-value lru)
        gen-key   (.-gen-key lru)
        key-gen   (.-key-gen lru)
        gen       (.-gen lru)
        limit     (.-limit lru)]
    (if-let [g (key-gen k nil)]
      (LRU.
        key-value
        (-> gen-key
          (dissoc g)
          (assoc gen k))
        (assoc key-gen k gen)
        (inc gen)
        limit)
      (cleanup-lru
        (LRU.
          (assoc key-value k v)
          (assoc gen-key gen k)
          (assoc key-gen k gen)
          (inc gen)
          limit)))))

(defn cleanup-lru [^LRU lru]
  (if (> (count (.-key-value lru)) (.-limit lru))
    (let [key-value (.-key-value lru)
          gen-key   (.-gen-key lru)
          key-gen   (.-key-gen lru)
          gen       (.-gen lru)
          limit     (.-limit lru)
          [g k]     (first gen-key)]
      (LRU.
        (dissoc key-value k)
        (dissoc gen-key g)
        (dissoc key-gen k)
        gen
        limit))
    lru))

(defn lru [limit]
  (LRU. {} (sorted-map) {} 0 limit))

(defprotocol ICache
  (-get [this key compute-fn]))

;; PORT DELTA (F-LPY-REIFY-STAR-CAPTURE): basilisp 0.5.1's reify
;; codegen never defines the generated capture var for a captured
;; local whose name needs munging (*impl -> __STAR__impl), so the
;; reified ICache dies with NameError on its first -get (measured
;; 2026-11-08, tw97: the star-named capture fails in pure basilisp,
;; a plain-named one works). The :lpy arm is the shared body with
;; the capture renamed to impl; every other seat reads :default.
#?(:lpy
   (defn cache [limit]
     (let [impl (volatile! (lru limit))]
       (reify ICache
         (-get [_ key compute-fn]
           (if-some [cached (get @impl key nil)]
             (do (vswap! impl assoc key cached)
               cached)
             (let [computed (compute-fn)]
               (vswap! impl assoc key computed)
               computed))))))
   :default
(defn cache [limit]
  ;; PORT DELTA (F-CLJE-LRU-CELL): eve.host's volatile! is a
  ;; process-val cell -- its value lives in the creating process's
  ;; dictionary. A cache is created once, at namespace load, and used
  ;; from whatever process runs the query (clojerl's -main runs in a
  ;; fresh process), where the load-time cell's deref fails with "No
  ;; process value available" (stage-44 stack: lru/-get <- query/q).
  ;; The :clje arm holds the impl in a clojerl.Atom, which any process
  ;; may deref/swap!; swap! re-runs assoc-lru on retry, which is
  ;; idempotent for a cache and so keeps the volatile's semantics.
  (let [*impl #?(:clje (atom (lru limit))
                 :clj  (volatile! (lru limit))
                 :cljr (volatile! (lru limit))
                 :cljs (volatile! (lru limit))
                 :lpy  (volatile! (lru limit)))]
    (reify ICache
      (-get [_ key compute-fn]
        (if-some [cached (get @*impl key nil)]
          (do #?(:clje (swap! *impl assoc key cached)
                 :clj  (vswap! *impl assoc key cached)
                 :cljr (vswap! *impl assoc key cached)
                 :cljs (vswap! *impl assoc key cached)
                 :lpy  (vswap! *impl assoc key cached))
            cached)
          (let [computed (compute-fn)]
            #?(:clje (swap! *impl assoc key computed)
               :clj  (vswap! *impl assoc key computed)
               :cljr (vswap! *impl assoc key computed)
               :cljs (vswap! *impl assoc key computed)
               :lpy  (vswap! *impl assoc key computed))
            computed)))))))
